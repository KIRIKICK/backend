import contextlib
import io
import json
import os
import tempfile
import textwrap
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))

assert (SCRIPTS / "deploy_dev_cloud_run.py").is_file(), "DEV rollout implementation is missing"
import deploy_dev_cloud_run as cd


IMAGE = "asia-northeast3-docker.pkg.dev/kirikick/kirikick/kirikick-api@sha256:" + "a" * 64
PROJECT = "kirikick"
REGION = "asia-northeast3"
SERVICE = "kirikick-api"
SUFFIX = "run123-attempt1"
TAG = "candidate-run123-attempt1"


def completed(argv, payload=None, returncode=0, stderr=""):
    return subprocess.CompletedProcess(argv, returncode, json.dumps(payload) if payload is not None else "", stderr)


def service(traffic, url="https://service-test.a.run.app"):
    return {
        "metadata": {"name": SERVICE, "labels": {"cloud.googleapis.com/location": REGION}},
        "status": {"url": url, "traffic": traffic},
    }


def revision(name, ready=True, image=IMAGE):
    return {
        "metadata": {"name": name, "generation": 1},
        "status": {
            "conditions": [{"type": "Ready", "status": "True" if ready else "False"}],
            "observedGeneration": 1,
            "imageDigest": image,
        },
        "spec": {"containers": [{"image": image}]},
    }


class FakeRunner:
    """Small command boundary fake: rollout logic still builds and interprets argv/JSON."""

    def __init__(self, traffic=None, promotion_result=0, post_health=200, candidate_ready=True,
                 candidate_image=IMAGE, candidate_health=None, cleanup_result=0, rollback_result=0,
                 promotion_traffic=None):
        self.prior = traffic or [{"revisionName": f"{SERVICE}-old", "percent": 100}]
        self.current = list(self.prior)
        self.calls = []
        self.deployed = False
        self.promotion_result = promotion_result
        self.promotion_traffic = promotion_traffic
        self.post_health = post_health
        self.candidate_ready = candidate_ready
        self.candidate_image = candidate_image
        self.candidate_health = list(candidate_health or [])
        self.cleanup_result = cleanup_result
        self.rollback_result = rollback_result
        self.candidate = f"{SERVICE}-{SUFFIX}"
        self.promoted = False
        self.tag_removed = False
        self.interrupted_promotion = False

    def __call__(self, argv, *, capture_output, text, timeout, check):
        self.calls.append(list(argv))
        self.assert_safe_subprocess_arguments(capture_output, text, check)
        if argv[:4] == ["gcloud", "run", "services", "describe"]:
            candidate_url = "https://candidate---service-test.a.run.app" if self.deployed else "https://service-test.a.run.app"
            tagged = [] if not self.deployed or self.tag_removed else [
                {"revisionName": self.candidate, "percent": 0, "tag": TAG, "url": candidate_url}
            ]
            return completed(argv, service(self.current + tagged))
        if argv[:4] == ["gcloud", "run", "revisions", "describe"]:
            return completed(argv, revision(self.candidate, self.candidate_ready, self.candidate_image))
        if argv[:3] == ["gcloud", "run", "deploy"]:
            self.deployed = True
            return completed(argv)
        if argv[:4] == ["gcloud", "run", "services", "update-traffic"] and any(
                argument.startswith("--remove-tags=") for argument in argv):
            if self.cleanup_result:
                return completed(argv, returncode=self.cleanup_result)
            self.tag_removed = True
            return completed(argv)
        if argv[:4] == ["gcloud", "run", "services", "update-traffic"]:
            self.promoted = True
            target = next(value for value in argv if value.startswith("--to-revisions="))
            is_candidate_promotion = target.endswith("=100") and self.candidate in target
            if is_candidate_promotion and self.promotion_result == "timeout":
                self.current = [{"revisionName": self.candidate, "percent": 100}]
                raise subprocess.TimeoutExpired(argv, timeout)
            if is_candidate_promotion and self.promotion_result == "interrupt" and not self.interrupted_promotion:
                self.interrupted_promotion = True
                self.current = [{"revisionName": self.candidate, "percent": 100}]
                raise KeyboardInterrupt
            if is_candidate_promotion and self.promotion_result == "drift":
                self.current = [{"revisionName": f"{SERVICE}-other", "percent": 100}]
                raise subprocess.TimeoutExpired(argv, timeout)
            if is_candidate_promotion:
                self.current = (self.promotion_traffic if self.promotion_traffic is not None
                                else [{"revisionName": self.candidate, "percent": 100}])
            else:
                if self.rollback_result:
                    return completed(argv, returncode=self.rollback_result)
                self.current = [
                    {"revisionName": revision_name, "percent": int(percent)}
                    for revision_name, percent in (entry.split("=", 1) for entry in target.split("=", 1)[1].split(","))
                ]
            return completed(argv, returncode=self.promotion_result
                             if is_candidate_promotion and isinstance(self.promotion_result, int) else 0)
        if argv[:4] == ["gcloud", "run", "services", "update-traffic"]:
            raise AssertionError("unreachable")
        if argv[0] == "curl":
            url = next(argument for argument in argv if argument.startswith("https://"))
            status = self.post_health if self.promoted and "service-test.a.run.app" in url and "---" not in url else 200
            if "---" in url and self.candidate_health:
                status = self.candidate_health.pop(0)
            return subprocess.CompletedProcess(argv, 0, str(status), "")
        raise AssertionError(f"unexpected command: {argv}")

    @staticmethod
    def assert_safe_subprocess_arguments(capture_output, text, check):
        if not (capture_output and text and check is False):
            raise AssertionError("runner must receive capture_output=True, text=True, check=False")


class RolloutTest(unittest.TestCase):
    def config(self, **changes):
        values = {
            "project": PROJECT,
            "region": REGION,
            "service": SERVICE,
            "image": IMAGE,
            "revision_suffix": SUFFIX,
            "tag": TAG,
        }
        values.update(changes)
        return cd.Config(**values)

    def rollout(self, fake, **changes):
        return cd.Rollout(self.config(**changes), runner=fake, sleep=lambda _: None)

    def commands(self, fake, prefix):
        return [argv for argv in fake.calls if argv[:len(prefix)] == prefix]

    def promotions(self, fake):
        return [argv for argv in self.commands(fake, ["gcloud", "run", "services", "update-traffic"])
                if any(argument.startswith("--to-revisions=") for argument in argv)]

    def test_command_preserves_interrupted_error(self):
        interruption = InterruptedError("termination requested")

        def interrupt(argv, **kwargs):
            raise interruption

        with self.assertRaises(InterruptedError) as raised:
            self.rollout(interrupt).command(["gcloud", "run", "services", "describe", SERVICE])
        self.assertIs(raised.exception, interruption)

    def test_interrupted_candidate_health_stops_without_retry_or_promotion(self):
        class InterruptedHealth(FakeRunner):
            health_interrupted = False

            def __call__(self, argv, **kwargs):
                if argv[0] == "curl" and not self.health_interrupted:
                    self.health_interrupted = True
                    self.calls.append(list(argv))
                    raise InterruptedError()
                return super().__call__(argv, **kwargs)

        fake = InterruptedHealth()
        rollout = self.rollout(fake)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertEqual(len(self.commands(fake, ["curl"])), 1)
        self.assertEqual(self.promotions(fake), [])
        self.assertEqual(fake.current, fake.prior)
        self.assertFalse(rollout.rollback_attempted)
        self.assertEqual(json.loads(output.getvalue().splitlines()[-1])["reason"], "interrupted")

    def test_interrupted_command_during_promotion_recovers_prior_traffic(self):
        class InterruptedPromotion(FakeRunner):
            def __call__(self, argv, **kwargs):
                result = super().__call__(argv, **kwargs)
                if f"--to-revisions={self.candidate}=100" in argv:
                    raise InterruptedError()
                return result

        fake = InterruptedPromotion()
        rollout = self.rollout(fake)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertTrue(rollout.rollback_attempted)
        self.assertTrue(rollout.rollback_succeeded)
        self.assertEqual(fake.current, fake.prior)
        self.assertEqual(json.loads(output.getvalue().splitlines()[-1])["reason"], "interrupted")

    def test_config_builds_candidate_revision_from_service_and_suffix(self):
        self.assertEqual(self.config().revision, f"{SERVICE}-{SUFFIX}")

    def test_traffic_map_preserves_a_split_concrete_revision_baseline(self):
        baseline = service([
            {"revisionName": f"{SERVICE}-previous-a", "percent": 90},
            {"revisionName": f"{SERVICE}-previous-b", "percent": 10},
        ])
        self.assertEqual(cd.traffic_map(baseline), {
            f"{SERVICE}-previous-a": 90,
            f"{SERVICE}-previous-b": 10,
        })

    def test_traffic_map_rejects_unresolved_latest_traffic(self):
        with self.assertRaises(cd.DeploymentError):
            cd.traffic_map(service([{"latestRevision": True, "percent": 100}]))

    def test_health_base_rejects_non_cloud_run_url(self):
        with self.assertRaises(cd.DeploymentError):
            cd.health_base("https://service.example/actuator/health/readiness")

    def test_health_base_rejects_explicit_zero_port_and_empty_userinfo(self):
        for url in ("https://service-test.a.run.app:0", "https://@service-test.a.run.app"):
            with self.subTest(url=url), self.assertRaises(cd.DeploymentError):
                cd.health_base(url)

    def test_rejects_mutable_image_before_any_command(self):
        fake = FakeRunner()
        with self.assertRaises(cd.DeploymentError):
            self.rollout(fake, image="asia-northeast3-docker.pkg.dev/kirikick/kirikick/kirikick-api:main").execute()
        self.assertEqual(fake.calls, [])

    def test_describe_failure_does_not_create_a_service(self):
        class MissingService(FakeRunner):
            def __call__(self, argv, **kwargs):
                if argv[:4] == ["gcloud", "run", "services", "describe"]:
                    return completed(argv, returncode=1, stderr="not found")
                return super().__call__(argv, **kwargs)

        fake = MissingService()
        with self.assertRaises(cd.DeploymentError):
            self.rollout(fake).execute()
        self.assertEqual(self.commands(fake, ["gcloud", "run", "deploy"]), [])

    def test_deploys_exact_digest_without_runtime_configuration_flags(self):
        fake = FakeRunner()
        self.rollout(fake).execute()
        deploy = self.commands(fake, ["gcloud", "run", "deploy"])[0]
        self.assertIn(f"--image={IMAGE}", deploy)
        self.assertIn(f"--revision-suffix={SUFFIX}", deploy)
        self.assertIn(f"--tag={TAG}", deploy)
        self.assertIn("--no-traffic", deploy)
        forbidden = ("--set-env-vars", "--update-env-vars", "--set-secrets", "--update-secrets",
                     "--service-account", "--memory", "--cpu", "--min", "--max", "--ingress", "--network")
        self.assertFalse(any(argument.startswith(forbidden) for argument in deploy))

    def test_candidate_digest_mismatch_prevents_promotion(self):
        fake = FakeRunner(candidate_image=IMAGE.replace("a" * 64, "b" * 64))
        with self.assertRaises(cd.DeploymentError):
            self.rollout(fake).execute()
        self.assertEqual(self.promotions(fake), [])

    def test_candidate_not_ready_prevents_promotion(self):
        fake = FakeRunner(candidate_ready=False)
        with self.assertRaises(cd.DeploymentError):
            self.rollout(fake).execute()
        self.assertEqual(self.promotions(fake), [])

    def test_candidate_health_uses_tagged_url_not_service_url(self):
        fake = FakeRunner()
        self.rollout(fake).execute()
        curl_urls = [next(argument for argument in argv if argument.startswith("https://"))
                     for argv in self.commands(fake, ["curl"])]
        pre_promotion_curls = [url for url in curl_urls if "---" in url]
        self.assertTrue(any("candidate---service-test.a.run.app/actuator/health/liveness" in url for url in pre_promotion_curls))
        self.assertTrue(any("candidate---service-test.a.run.app/actuator/health/readiness" in url for url in pre_promotion_curls))

    def test_candidate_health_exhaustion_keeps_prior_traffic(self):
        for endpoint, successful_checks in (("liveness", []), ("readiness", [200])):
            with self.subTest(endpoint=endpoint):
                fake = FakeRunner(candidate_health=successful_checks + [503] * cd.HEALTH_ATTEMPTS)
                rollout = self.rollout(fake)
                with self.assertRaises(cd.DeploymentError):
                    rollout.execute()
                checks = [argv for argv in self.commands(fake, ["curl"])
                          if argv[-1].endswith(f"/actuator/health/{endpoint}")]
                self.assertEqual(len(checks), cd.HEALTH_ATTEMPTS)
                self.assertEqual(rollout.health_checks[f"candidate_{endpoint}"], "503")
                self.assertEqual(self.promotions(fake), [])
                self.assertEqual(fake.current, fake.prior)
                self.assertFalse(rollout.rollback_attempted)
                self.assertEqual(rollout.result, "failed")

    def test_promotion_command_failure_recovers_according_to_observed_traffic(self):
        prior = [{"revisionName": f"{SERVICE}-old", "percent": 100}]
        candidate = [{"revisionName": f"{SERVICE}-{SUFFIX}", "percent": 100}]
        unrelated = [{"revisionName": f"{SERVICE}-other", "percent": 100}]
        for name, observed, should_recover in (
                ("unchanged", prior, False),
                ("candidate", candidate, True),
                ("unrelated", unrelated, False)):
            with self.subTest(traffic=name):
                fake = FakeRunner(promotion_result=1, promotion_traffic=observed)
                rollout = self.rollout(fake)
                with self.assertRaises(cd.DeploymentError):
                    rollout.execute()
                updates = self.promotions(fake)
                self.assertEqual(len(updates), 1 + int(should_recover))
                if should_recover:
                    self.assertIn(f"--to-revisions={SERVICE}-old=100", updates[-1])
                self.assertEqual(fake.current, prior if should_recover else observed)
                self.assertEqual(rollout.rollback_attempted, should_recover)
                self.assertEqual(rollout.rollback_succeeded, should_recover)
                self.assertEqual(rollout.result, "failed")

    def test_partial_promotion_restores_prior_revision_percentages(self):
        prior = [
            {"revisionName": f"{SERVICE}-previous-a", "percent": 90},
            {"revisionName": f"{SERVICE}-previous-b", "percent": 10},
        ]
        partial = [
            {"revisionName": f"{SERVICE}-previous-a", "percent": 60},
            {"revisionName": f"{SERVICE}-previous-b", "percent": 10},
            {"revisionName": f"{SERVICE}-{SUFFIX}", "percent": 30},
        ]
        for promotion_result in (0, 1):
            with self.subTest(promotion_result=promotion_result):
                fake = FakeRunner(traffic=prior, promotion_result=promotion_result,
                                  promotion_traffic=partial)
                rollout = self.rollout(fake)
                with self.assertRaises(cd.DeploymentError):
                    rollout.execute()
                updates = self.promotions(fake)
                self.assertEqual(len(updates), 2)
                self.assertIn(f"--to-revisions={SERVICE}-previous-a=90,{SERVICE}-previous-b=10",
                              updates[-1])
                self.assertEqual(fake.current, prior)
                self.assertEqual(rollout.observed, cd.traffic_map(service(prior)))
                self.assertTrue(rollout.rollback_attempted)
                self.assertTrue(rollout.rollback_succeeded)
                self.assertEqual(rollout.result, "failed")

    def test_post_promotion_health_failure_restores_prior_concrete_traffic(self):
        fake = FakeRunner(post_health=503)
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        updates = self.promotions(fake)
        self.assertGreaterEqual(len(updates), 2)
        self.assertIn(f"--to-revisions={SERVICE}-old=100", updates[-1])
        self.assertTrue(rollout.rollback_attempted)

    def test_post_promotion_failure_restores_every_split_prior_percentage(self):
        fake = FakeRunner(traffic=[
            {"revisionName": f"{SERVICE}-previous-a", "percent": 90},
            {"revisionName": f"{SERVICE}-previous-b", "percent": 10},
        ], post_health=503)
        with self.assertRaises(cd.DeploymentError):
            self.rollout(fake).execute()
        rollback = self.promotions(fake)[-1]
        self.assertIn(f"--to-revisions={SERVICE}-previous-a=90,{SERVICE}-previous-b=10", rollback)

    def test_promotion_timeout_with_candidate_traffic_restores_prior_traffic(self):
        fake = FakeRunner(promotion_result="timeout")
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        updates = self.promotions(fake)
        self.assertGreaterEqual(len(updates), 2)
        self.assertIn(f"--to-revisions={SERVICE}-old=100", updates[-1])
        self.assertTrue(rollout.rollback_attempted)

    def test_interrupted_promotion_with_candidate_traffic_attempts_recovery(self):
        fake = FakeRunner(promotion_result="interrupt")
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertTrue(rollout.rollback_attempted)
        self.assertTrue(rollout.rollback_succeeded)

    def test_unrelated_traffic_drift_does_not_overwrite_the_other_revision(self):
        fake = FakeRunner(promotion_result="drift")
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertFalse(rollout.rollback_attempted)
        self.assertEqual(fake.current, [{"revisionName": f"{SERVICE}-other", "percent": 100}])

    def test_post_promotion_traffic_read_failure_does_not_report_stale_observed_traffic(self):
        class LostTrafficRead(FakeRunner):
            def __call__(self, argv, **kwargs):
                if self.promoted and argv[:4] == ["gcloud", "run", "services", "describe"]:
                    return completed(argv, returncode=1, stderr="temporarily unavailable")
                return super().__call__(argv, **kwargs)

        rollout = self.rollout(LostTrafficRead())
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertIsNone(rollout.observed)

    def test_malformed_post_promotion_service_response_is_a_deployment_failure(self):
        class MalformedTrafficRead(FakeRunner):
            def __call__(self, argv, **kwargs):
                if self.promoted and argv[:4] == ["gcloud", "run", "services", "describe"]:
                    malformed = service(self.current)
                    malformed["status"] = None
                    return completed(argv, malformed)
                return super().__call__(argv, **kwargs)

        with self.assertRaises(cd.DeploymentError):
            self.rollout(MalformedTrafficRead()).execute()

    def test_rollback_command_failure_is_reported_without_hiding_the_deployment_failure(self):
        fake = FakeRunner(post_health=503, rollback_result=1)
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertTrue(rollout.rollback_attempted)
        self.assertFalse(rollout.rollback_succeeded)

    def test_pre_promotion_failure_keeps_prior_traffic_without_rollback(self):
        fake = FakeRunner(candidate_ready=False)
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertFalse(rollout.rollback_attempted)
        self.assertEqual(fake.current, fake.prior)

    def test_candidate_health_retries_a_transient_status_before_promoting(self):
        fake = FakeRunner(candidate_health=[503, 200, 200])
        self.rollout(fake).execute()
        candidate_curls = [argv for argv in self.commands(fake, ["curl"])
                           if "---" in next(argument for argument in argv if argument.startswith("https://"))]
        self.assertEqual(len(candidate_curls), 3)

    def test_tag_cleanup_failure_keeps_verified_candidate_traffic(self):
        fake = FakeRunner(cleanup_result=1)
        rollout = self.rollout(fake)
        with self.assertRaises(cd.DeploymentError):
            rollout.execute()
        self.assertFalse(rollout.rollback_attempted)
        self.assertEqual(fake.current, [{"revisionName": f"{SERVICE}-{SUFFIX}", "percent": 100}])

    def test_workflow_preserves_ci_and_limits_oidc_to_deploy_job(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        cd_workflow = (ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8")
        self.assertIn("pull_request:", ci)
        self.assertIn("cancel-in-progress: true", ci)
        self.assertNotIn("deploy-dev:", ci)
        self.assertIn("workflow_run:", cd_workflow)
        self.assertIn("workflows: [CI]", cd_workflow)
        self.assertIn("cancel-in-progress: false", cd_workflow)
        self.assertIn("id-token: write", cd_workflow)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", cd_workflow)
        self.assertIn("github.event.workflow_run.event == 'push'", cd_workflow)
        self.assertIn("github.event.workflow_run.head_repository.id == github.event.repository.id", cd_workflow)

    def test_workflow_uses_pinned_actionlint_and_excludes_forbidden_deploy_surface(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        deploy_job = (ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8").split("deploy-dev:", 1)[1]
        self.assertRegex(ci, r"rhysd/actionlint:[0-9][^@\s]*@sha256:[0-9a-f]{64}")
        self.assertIn("python3 scripts/test_deploy_dev_cloud_run.py", ci)
        self.assertIn("SOURCE_SHA: ${{ github.event.workflow_run.head_sha }}", deploy_job)
        self.assertIn("ref: ${{ github.event.workflow_run.head_sha }}", deploy_job)
        self.assertNotIn("$GITHUB_SHA", deploy_job)
        self.assertNotIn("deploy-cloudrun", deploy_job)
        self.assertNotIn("ghcr.io", deploy_job)
        for flag in ("--set-env-vars", "--update-env-vars", "--set-secrets", "--update-secrets",
                     "--service-account", "--allow-unauthenticated", "--ingress"):
            self.assertNotIn(flag, deploy_job)

    def test_cli_has_no_tunable_retry_timeout_or_freshness_bypass(self):
        parser = cd.argument_parser()
        options = {action.dest for action in parser._actions}
        self.assertTrue({"project", "region", "service", "image", "revision_suffix", "tag"} <= options)
        self.assertFalse({"retry", "timeout", "skip_freshness", "skip_health"} & options)

    def test_failures_do_not_write_raw_runner_output_to_stdout(self):
        class LeakyFailure(FakeRunner):
            def __call__(self, argv, **kwargs):
                if argv[:4] == ["gcloud", "run", "services", "describe"]:
                    return completed(argv, returncode=1, stderr="redis://secret.example")
                return super().__call__(argv, **kwargs)

        fake = LeakyFailure()
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(cd.DeploymentError):
            self.rollout(fake).execute()
        self.assertNotIn("redis://secret.example", output.getvalue())


class ManualRecoveryHealthTest(unittest.TestCase):
    def test_documented_health_checks_accept_only_http_200(self):
        document = (ROOT / "docs/deployment/cloud-run-cd.md").read_text(encoding="utf-8")
        script = next(block.split("```", 1)[0] for block in document.split("```sh\n")[1:]
                      if 'SERVICE_URL="' in block and "/actuator/health/" in block)
        commands = """
gcloud() { printf '%s\\n' 'https://service-test.a.run.app'; }
curl() {
  local url="${@: -1}"
  printf '%s\\n' "$url" >> "$HEALTH_LOG"
  if [[ "$url" == */liveness ]]; then
    printf '%s' "$LIVENESS_STATUS"
  else
    printf '%s' "$READINESS_STATUS"
  fi
}
"""
        for liveness, readiness in ((200, 200), (302, 200), (200, 302),
                                    (204, 200), (200, 204), (503, 200), (200, 503)):
            with self.subTest(liveness=liveness, readiness=readiness), tempfile.TemporaryDirectory() as temp:
                log = Path(temp) / "health.log"
                env = {**os.environ, "HEALTH_LOG": str(log),
                       "LIVENESS_STATUS": str(liveness), "READINESS_STATUS": str(readiness)}
                result = subprocess.run(["bash", "-c", commands + script], env=env,
                                        capture_output=True, text=True, check=False, timeout=5)
                self.assertEqual(result.returncode, 0 if (liveness, readiness) == (200, 200) else 1,
                                 result.stderr)
                endpoints = [url.rsplit("/", 1)[-1] for url in log.read_text().splitlines()]
                self.assertEqual(endpoints, ["liveness", "readiness"] if liveness == 200 else ["liveness"])


class WorkflowPublishShellTest(unittest.TestCase):
    """Executes the publish shell body with local command fakes only."""

    def publish_script(self):
        workflow = (ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Publish and roll out the tested commit\n", 1)[1]
        return textwrap.dedent(step.split("        run: |\n", 1)[1])

    def run_publish(self, responses):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name)
        bin_path = path / "bin"
        bin_path.mkdir()
        log = path / "commands.log"
        answer_path = path / "answers"
        answer_path.write_text("\n".join(responses) + "\n", encoding="utf-8")
        scripts = {
            "gh": "response=$(head -n 1 \"$GH_RESPONSES\")\nsed -i.bak '1d' \"$GH_RESPONSES\"\nrm -f \"$GH_RESPONSES.bak\"\n[ \"$response\" = __FAIL__ ] && exit 9\nprintf '%s\\n' \"$response\"\n",
            "gcloud": "printf 'gcloud %s\\n' \"$*\" >> \"$COMMAND_LOG\"\nif [ \"$1 $2 $3 $4\" = 'artifacts docker images describe' ]; then printf '%s@sha256:%064d\\n' \"$IMAGE_BASE\" 0 | tr 0 a; fi\n",
            "python3": "printf 'python3 %s\\n' \"$*\" >> \"$COMMAND_LOG\"\n",
        }
        for name, body in scripts.items():
            executable = bin_path / name
            executable.write_text("#!/usr/bin/env bash\nset -eu\n" + body, encoding="utf-8")
            executable.chmod(0o755)
        project = path / "project"
        project.mkdir()
        gradlew = project / "gradlew"
        gradlew.write_text("#!/usr/bin/env bash\nprintf 'gradlew %s\\n' \"$*\" >> \"$COMMAND_LOG\"\n", encoding="utf-8")
        gradlew.chmod(0o755)
        image_base = f"{REGION}-docker.pkg.dev/{PROJECT}/kirikick/{SERVICE}"
        env = os.environ | {
            "PATH": f"{bin_path}:{os.environ['PATH']}", "COMMAND_LOG": str(log),
            "GH_RESPONSES": str(answer_path), "GITHUB_STEP_SUMMARY": str(path / "summary"),
            "GITHUB_REPOSITORY": "KIRIKICK/backend", "GITHUB_SHA": "c" * 40,
            "SOURCE_SHA": "a" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", "GCP_REGION": REGION,
            "GCP_PROJECT_ID": PROJECT, "AR_REPOSITORY": "kirikick", "CLOUD_RUN_SERVICE": SERVICE,
            "IMAGE_BASE": image_base,
        }
        result = subprocess.run(["bash", "-eo", "pipefail", "-c", self.publish_script()], cwd=project, env=env, capture_output=True, text=True, check=False)
        return result, log.read_text(encoding="utf-8") if log.exists() else "", image_base

    def test_initial_stale_commit_exits_without_publishing_or_rollout(self):
        result, commands, _ = self.run_publish(["b" * 40])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(commands, "")
        self.assertIn("SUPERSEDED_NOOP", result.stdout)

    def test_stale_after_publish_does_not_start_rollout(self):
        result, commands, _ = self.run_publish(["a" * 40, "b" * 40])
        self.assertEqual(result.returncode, 0)
        self.assertIn("gradlew", commands)
        self.assertNotIn("python3", commands)

    def test_current_commit_publishes_digest_and_invokes_rollout_with_it(self):
        result, commands, image_base = self.run_publish(["a" * 40, "a" * 40])
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("gradlew", commands)
        self.assertIn(f"python3 scripts/deploy_dev_cloud_run.py --project {PROJECT}", commands)
        self.assertIn(f"--image {image_base}@sha256:" + "a" * 64, commands)


    def test_main_lookup_failure_stops_before_publishing(self):
        result, commands, _ = self.run_publish(["__FAIL__"])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, "")

class RolloutCliBoundaryTest(unittest.TestCase):
    def run_cli(self, fail_read=False):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        directory = Path(temp.name)
        binary = directory / "bin"
        binary.mkdir()
        shim = textwrap.dedent(f"""\
            #!{sys.executable}
            import json
            import os
            from pathlib import Path
            import sys
            from test_deploy_dev_cloud_run import FakeRunner

            argv = [Path(sys.argv[0]).name, *sys.argv[1:]]
            if os.environ.get('FAIL_CLI_READ') == '1':
                print('private command diagnostic', file=sys.stderr)
                sys.exit(9)
            state = Path(os.environ['CLI_STATE'])
            fake = FakeRunner()
            if state.exists():
                fake.__dict__.update(json.loads(state.read_text()))
            result = fake(argv, capture_output=True, text=True, check=False, timeout=30)
            state.write_text(json.dumps(fake.__dict__))
            print(result.stdout, end='')
            sys.exit(result.returncode)
        """)
        for name in ("gcloud", "curl"):
            executable = binary / name
            executable.write_text(shim, encoding="utf-8")
            executable.chmod(0o755)
        summary = directory / "summary"
        result = subprocess.run([
            sys.executable, str(SCRIPTS / "deploy_dev_cloud_run.py"),
            "--project", PROJECT, "--region", REGION, "--service", SERVICE,
            "--image", IMAGE, "--revision-suffix", SUFFIX, "--tag", TAG,
        ], env=os.environ | {
            "PATH": f"{binary}:{os.environ['PATH']}", "PYTHONPATH": str(SCRIPTS),
            "CLI_STATE": str(directory / "state.json"), "FAIL_CLI_READ": str(int(fail_read)),
            "SOURCE_SHA": "b" * 40, "GITHUB_STEP_SUMMARY": str(summary),
        }, capture_output=True, text=True, check=False, timeout=15)
        return result, summary.read_text(encoding="utf-8")

    def test_actual_cli_verifies_candidate_and_writes_final_summary(self):
        result, summary = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(payload["commit"], "b" * 40)
        self.assertEqual(payload["result"], "succeeded")
        self.assertTrue(payload["candidate_ready"])
        self.assertEqual(payload["observed_traffic"], {f"{SERVICE}-{SUFFIX}": 100})
        self.assertEqual(payload["tag_cleanup"], "removed")
        self.assertEqual(payload["health_checks"], {
            "candidate_liveness": "200", "candidate_readiness": "200",
            "service_liveness": "200", "service_readiness": "200",
        })
        self.assertIn('"result": "succeeded"', summary)

    def test_actual_cli_read_failure_is_nonzero_and_never_logs_command_diagnostics(self):
        result, summary = self.run_cli(fail_read=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("private command diagnostic", result.stdout + result.stderr + summary)
        self.assertIn('"result": "failed"', summary)
        self.assertIn('"observed_traffic": null', summary)


if __name__ == "__main__":
    unittest.main()
