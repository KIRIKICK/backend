"""Roll out one existing DEV service without changing its runtime configuration."""

import argparse
from dataclasses import dataclass
import json
import os
import re
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit


COMMAND_TIMEOUT = 30
DEPLOY_TIMEOUT = 600
TRAFFIC_TIMEOUT = 120
HEALTH_ATTEMPTS = 6
POLL_ATTEMPTS = 12
POLL_DELAY = 5
READY_DEADLINE = 120
HEALTH_DEADLINE = 90
TRAFFIC_DEADLINE = 120


class DeploymentError(Exception):
    """A sanitized rollout failure; command output must never enter this message."""


@dataclass(frozen=True)
class Config:
    project: str
    region: str
    service: str
    image: str
    revision_suffix: str
    tag: str

    def __post_init__(self):
        for value in (self.project, self.region, self.service, self.revision_suffix, self.tag):
            if not re.fullmatch(r"[a-z][a-z0-9-]*[a-z0-9]|[a-z]", value):
                raise DeploymentError("invalid_resource_identifier")
        if len(self.revision) > 63 or len(self.tag) > 63:
            raise DeploymentError("resource_identifier_too_long")
        registry = re.escape(f"{self.region}-docker.pkg.dev/{self.project}/")
        if not re.fullmatch(registry + r"[a-z0-9._/-]+@sha256:[a-f0-9]{64}", self.image):
            raise DeploymentError("expected_digest_in_target_artifact_registry")

    @property
    def revision(self):
        return f"{self.service}-{self.revision_suffix}"


def health_base(url):
    if not isinstance(url, str):
        raise DeploymentError("expected_cloud_run_https_url")
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError:
        raise DeploymentError("invalid_cloud_run_url") from None
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or not parsed.hostname.endswith(".run.app")
        or "@" in parsed.netloc
        or port is not None
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise DeploymentError("expected_cloud_run_https_url")
    return url.rstrip("/")


def traffic_entries(service):
    status = service.get("status")
    if not isinstance(status, dict) or not isinstance(status.get("traffic"), list):
        raise DeploymentError("invalid_service_traffic_status")
    entries = status["traffic"]
    if any(not isinstance(entry, dict) for entry in entries):
        raise DeploymentError("invalid_traffic_entry")
    return entries


def traffic_map(service):
    result = {}
    for entry in traffic_entries(service):
        percent = entry.get("percent", 0)
        if type(percent) is not int or not 0 <= percent <= 100:
            raise DeploymentError("invalid_traffic_percentage")
        if percent:
            revision = entry.get("revisionName", "")
            if not isinstance(revision, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", revision):
                raise DeploymentError("traffic_requires_resolved_revision_names")
            result[revision] = result.get(revision, 0) + percent
    if sum(result.values()) != 100:
        raise DeploymentError("traffic_must_total_100")
    return result


def traffic_tags(service):
    result = {}
    for entry in traffic_entries(service):
        if "tag" in entry:
            tag = entry["tag"]
            if (not isinstance(tag, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", tag)
                    or tag in result or not isinstance(entry.get("revisionName"), str)
                    or not re.fullmatch(r"[a-z][a-z0-9-]*", entry["revisionName"])):
                raise DeploymentError("ambiguous_traffic_tag")
            result[tag] = (entry["revisionName"], health_base(entry.get("url", "")))
    return result


class Rollout:
    def __init__(self, config, runner=subprocess.run, sleep=time.sleep, clock=time.monotonic):
        self.config = config
        self.runner = runner
        self.sleep = sleep
        self.clock = clock
        self.phase = "INITIAL"
        self.prior = {}
        self.prior_tags = {}
        self.observed = {}
        self.rollback_attempted = False
        self.rollback_succeeded = False
        self.rollback_armed = False
        self.recovering = False
        self.candidate_attempted = False
        self.cleanup_attempted = False
        self.ready = False
        self.health_checks = {}
        self.result = "running"
        self.cleanup_status = "not_attempted"

    def emit(self, event, **fields):
        print(json.dumps({
            "event": event, "phase": self.phase,
            "commit": os.environ.get("SOURCE_SHA", ""),
            "image": self.config.image, "revision": self.config.revision,
            "prior_traffic": self.prior, "observed_traffic": self.observed,
            "rollback_armed": self.rollback_armed,
            "rollback_attempted": self.rollback_attempted,
            "rollback_succeeded": self.rollback_succeeded,
            "candidate_ready": self.ready, "health_checks": self.health_checks,
            "result": self.result, "tag_cleanup": self.cleanup_status,
            **fields,
        }, sort_keys=True), flush=True)

    def command(self, argv, timeout=COMMAND_TIMEOUT):
        """Run a bounded command while preserving termination requests for execute()."""
        try:
            return self.runner(argv, capture_output=True, text=True, timeout=timeout, check=False)
        except InterruptedError:
            raise
        except (OSError, subprocess.SubprocessError):
            raise DeploymentError("external_command_failed_or_timed_out") from None

    def cloud(self, *args, fields=None, timeout=COMMAND_TIMEOUT):
        argv = ["gcloud", "run", *args, f"--project={self.config.project}",
                f"--region={self.config.region}", "--quiet",
                f"--format=json({fields})" if fields else "--format=value(metadata.name)"]
        result = self.command(argv, timeout)
        if result.returncode:
            raise DeploymentError("gcloud_command_failed")
        if fields:
            try:
                data = json.loads(result.stdout)
            except (ValueError, TypeError):
                raise DeploymentError("invalid_gcloud_json") from None
            if not isinstance(data, dict):
                raise DeploymentError("expected_gcloud_object")
            return data

    def service(self):
        self.observed = None
        data = self.cloud("services", "describe", self.config.service,
                          fields="metadata.name,metadata.labels,status.url,status.traffic")
        metadata = data.get("metadata", {})
        if (not isinstance(metadata, dict) or not isinstance(metadata.get("labels"), dict)
                or metadata.get("name") != self.config.service
                or metadata.get("labels", {}).get("cloud.googleapis.com/location") != self.config.region):
            raise DeploymentError("service_identity_mismatch")
        self.observed = traffic_map(data)
        health_base(data["status"].get("url"))
        return data

    def check_tags(self, data):
        tags = traffic_tags(data)
        if {key: value for key, value in tags.items() if key != self.config.tag} != self.prior_tags:
            raise DeploymentError("unrelated_tag_drift_requires_manual_action")
        if self.config.tag not in tags or tags[self.config.tag][0] != self.config.revision:
            raise DeploymentError("candidate_tag_revision_mismatch")
        return tags[self.config.tag][1]

    def wait_revision(self):
        deadline = self.clock() + READY_DEADLINE
        for _ in range(POLL_ATTEMPTS):
            if self.clock() >= deadline:
                break
            data = self.cloud("revisions", "describe", self.config.revision,
                              fields="metadata.name,metadata.generation,status.observedGeneration,status.conditions,status.imageDigest")
            metadata = data.get("metadata")
            status = data.get("status")
            if (not isinstance(metadata, dict) or not isinstance(status, dict)
                    or not isinstance(status.get("conditions"), list)
                    or any(not isinstance(item, dict) for item in status["conditions"])):
                raise DeploymentError("invalid_revision_status")
            if metadata.get("name") != self.config.revision:
                raise DeploymentError("candidate_revision_mismatch")
            ready = any(item.get("type") == "Ready" and item.get("status") == "True"
                        for item in status.get("conditions", []))
            generation = str(metadata.get("generation", ""))
            if (ready and re.fullmatch(r"[1-9][0-9]*", generation)
                    and str(status.get("observedGeneration")) == generation):
                if status.get("imageDigest") != self.config.image:
                    raise DeploymentError("candidate_digest_mismatch")
                self.ready = True
                self.emit("candidate_ready", ready=True)
                return
            self.sleep(POLL_DELAY)
        raise DeploymentError("candidate_not_ready")

    def health(self, base):
        for endpoint in ("liveness", "readiness"):
            deadline = self.clock() + HEALTH_DEADLINE
            healthy = False
            for _ in range(HEALTH_ATTEMPTS):
                if self.clock() >= deadline:
                    break
                try:
                    result = self.command([
                        "curl", "--silent", "--show-error", "--output", os.devnull,
                        "--write-out", "%{http_code}", "--connect-timeout", "5",
                        "--max-time", "10", f"{base}/actuator/health/{endpoint}",
                    ], timeout=15)
                    code = result.stdout.strip()
                    healthy = result.returncode == 0 and code == "200"
                except DeploymentError:
                    code, healthy = "transport_error", False
                status = code if re.fullmatch(r"[0-9]{3}", code) else "transport_error"
                target = "service" if base == self.service_url else "candidate"
                self.health_checks[f"{target}_{endpoint}"] = status
                self.emit("health", endpoint=endpoint, http_status=status, target=target)
                if healthy:
                    break
                self.sleep(POLL_DELAY)
            else:
                raise DeploymentError("health_check_failed")
            if not healthy:
                raise DeploymentError("health_check_deadline_exceeded")

    def wait_traffic(self, expected):
        deadline = self.clock() + TRAFFIC_DEADLINE
        for _ in range(POLL_ATTEMPTS):
            if self.clock() >= deadline:
                break
            data = self.service()
            if self.observed == expected:
                return data
            if not set(self.observed) <= set(self.prior) | {self.config.revision}:
                raise DeploymentError("unrelated_traffic_drift_requires_manual_action")
            self.sleep(POLL_DELAY)
        raise DeploymentError("traffic_did_not_converge")

    def recover(self):
        self.recovering = True
        try:
            self.service()
            if self.observed == self.prior:
                self.emit("rollback_not_needed")
                return
            if (self.observed.get(self.config.revision, 0) <= 0
                    or not set(self.observed) <= set(self.prior) | {self.config.revision}):
                raise DeploymentError("unrelated_traffic_drift_requires_manual_action")
            self.rollback_attempted = True
            self.emit("rollback_started")
            target = ",".join(f"{name}={percent}" for name, percent in sorted(self.prior.items()))
            self.cloud("services", "update-traffic", self.config.service,
                       f"--to-revisions={target}", timeout=TRAFFIC_TIMEOUT)
            self.wait_traffic(self.prior)
            self.rollback_succeeded = True
            self.emit("rollback_verified")
        except (DeploymentError, KeyboardInterrupt, InterruptedError):
            self.emit("rollback_failed_manual_action_required")
        finally:
            self.recovering = False

    def cleanup_tag(self):
        self.cleanup_attempted = True
        self.cleanup_status = "in_progress"
        data = self.service()
        tags = traffic_tags(data)
        if {key: value for key, value in tags.items() if key != self.config.tag} != self.prior_tags:
            raise DeploymentError("unrelated_tag_drift_requires_manual_action")
        if self.config.tag not in tags:
            self.cleanup_status = "not_present"
            return
        if tags[self.config.tag][0] != self.config.revision:
            raise DeploymentError("candidate_tag_drift_requires_manual_action")
        before = dict(self.observed)
        self.cloud("services", "update-traffic", self.config.service,
                   f"--remove-tags={self.config.tag}")
        data = self.service()
        if traffic_tags(data) != self.prior_tags or self.observed != before:
            raise DeploymentError("tag_cleanup_not_verified_manual_action_required")
        self.cleanup_status = "removed"
        self.emit("candidate_tag_removed")

    def execute(self):
        try:
            data = self.service()
            self.prior = dict(self.observed)
            self.prior_tags = traffic_tags(data)
            if self.config.tag in self.prior_tags:
                raise DeploymentError("candidate_tag_already_exists")
            self.service_url = health_base(data["status"]["url"])
            self.phase = "SNAPSHOT"
            self.emit("snapshot")
            self.candidate_attempted = True
            self.cloud("deploy", self.config.service, f"--image={self.config.image}",
                       f"--revision-suffix={self.config.revision_suffix}",
                       f"--tag={self.config.tag}", "--no-traffic", timeout=DEPLOY_TIMEOUT)
            self.phase = "CANDIDATE"
            self.wait_revision()
            data = self.service()
            candidate_url = self.check_tags(data)
            if self.observed != self.prior or candidate_url == self.service_url:
                raise DeploymentError("candidate_must_preserve_service_traffic")
            self.health(candidate_url)
            self.phase = "PRECHECKED"
            data = self.service()
            if self.observed != self.prior or self.check_tags(data) != candidate_url:
                raise DeploymentError("prepromotion_drift_requires_manual_action")
            self.phase = "PROMOTION_ATTEMPTED"
            self.rollback_armed = True
            self.emit("promotion_attempted")
            self.cloud("services", "update-traffic", self.config.service,
                       f"--to-revisions={self.config.revision}=100", timeout=TRAFFIC_TIMEOUT)
            self.wait_traffic({self.config.revision: 100})
            self.phase = "PROMOTED"
            self.health(self.service_url)
            self.phase = "POSTCHECKED"
            data = self.service()
            if self.observed != {self.config.revision: 100}:
                raise DeploymentError("final_traffic_not_candidate_100")
            self.check_tags(data)
            self.phase = "FINAL_VERIFIED"
            self.rollback_armed = False
            self.emit("final_verified")
            self.cleanup_tag()
            self.result = "succeeded"
            self.emit("succeeded")
        except (DeploymentError, KeyboardInterrupt, InterruptedError) as error:
            if self.rollback_armed:
                self.recover()
            if self.candidate_attempted and not self.cleanup_attempted:
                try:
                    self.cleanup_tag()
                except (DeploymentError, KeyboardInterrupt, InterruptedError):
                    self.emit("tag_cleanup_failed_manual_action_required")
            if self.cleanup_status == "in_progress":
                self.cleanup_status = "failed_manual_action_required"
            self.result = "failed"
            self.emit("failed", reason=str(error) if isinstance(error, DeploymentError) else "interrupted")
            raise DeploymentError("rollout_failed_check_summary_for_manual_recovery") from None


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("project", "region", "service", "image", "revision-suffix", "tag"):
        parser.add_argument(f"--{name}", required=True)
    return parser


def main():
    args = argument_parser().parse_args()
    rollout = None
    try:
        rollout = Rollout(Config(**vars(args)))

        def interrupt(_signum, _frame):
            if not rollout.recovering:
                raise InterruptedError()

        signal.signal(signal.SIGTERM, interrupt)
        rollout.execute()
        return 0
    except DeploymentError:
        print("DEV rollout failed; inspect structured events for traffic and manual recovery.", file=sys.stderr)
        return 1
    finally:
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary and rollout:
            with open(summary, "a", encoding="utf-8") as output:
                output.write("\n### DEV Cloud Run rollout\n\n```json\n")
                output.write(json.dumps({
                    "phase": rollout.phase, "commit": os.environ.get("SOURCE_SHA", ""),
                    "image": rollout.config.image, "revision": rollout.config.revision,
                    "prior_traffic": rollout.prior, "observed_traffic": rollout.observed,
                    "rollback_armed": rollout.rollback_armed,
                    "rollback_attempted": rollout.rollback_attempted,
                    "rollback_succeeded": rollout.rollback_succeeded,
                    "candidate_ready": rollout.ready, "health_checks": rollout.health_checks,
                    "result": rollout.result, "tag_cleanup": rollout.cleanup_status,
                }, sort_keys=True))
                output.write("\n```\n")


if __name__ == "__main__":
    sys.exit(main())
