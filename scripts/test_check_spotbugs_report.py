"""Exercise report diagnostics without running Gradle or external services."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/check_spotbugs_report.py"


class SpotBugsReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kirikick-spotbugs-report-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def report(self, module="api", bugs="", errors='errors="0" missingClasses="0"',
               diagnostics="", inputs="<Jar>fixture.class</Jar>"):
        directory = self.root / module / "build/reports/spotbugs"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "main.html").write_text("<html>SpotBugs fixture</html>", encoding="utf-8")
        (directory / "main.xml").write_text(
            f'<BugCollection version="4.10.5"><Project>{inputs}</Project>'
            f'<Errors {errors}>{diagnostics}</Errors>{bugs}'
            '<FindBugsSummary total_classes="0" total_bugs="0"/></BugCollection>',
            encoding="utf-8",
        )
        return directory

    def run_guard(self, expected):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.root)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        if expected == 2:
            self.assertIn("INCOMPLETE_ANALYSIS", result.stdout)
        return result.stdout

    def test_complete_reports_do_not_fail_on_zero_xml_class_statistics(self):
        self.report()
        output = self.run_guard(0)
        self.assertIn("inputs=1", output)
        self.assertIn("findings=0", output)
        self.assertIn("configured checks reported no findings", output)

    def test_findings_remain_failure_even_when_summary_count_is_zero(self):
        self.report(bugs='<BugInstance type="NP_FIXTURE"/>')
        output = self.run_guard(1)
        self.assertIn("findings=1", output)
        self.assertIn("HUMAN_REVIEW_REQUIRED", output)

    def test_reported_analysis_errors_are_incomplete(self):
        self.report(errors='errors="1" missingClasses="0"')
        self.assertIn("INCOMPLETE_ANALYSIS", self.run_guard(2))

    def test_error_elements_are_not_hidden_by_zero_counters(self):
        self.report(diagnostics="<Error><ErrorMessage>private diagnostic</ErrorMessage></Error>")
        output = self.run_guard(2)
        self.assertNotIn("private diagnostic", output)

    def test_missing_class_counter_is_incomplete(self):
        self.report(errors='errors="0" missingClasses="1"')
        self.run_guard(2)

    def test_missing_class_elements_are_incomplete_without_leaking_names(self):
        self.report(diagnostics="<MissingClass>private.example.Type</MissingClass>")
        self.assertNotIn("private.example.Type", self.run_guard(2))

    def test_missing_or_invalid_diagnostic_counters_are_incomplete(self):
        for errors in ('errors="0"', 'errors="invalid" missingClasses="0"',
                       'errors="-1" missingClasses="0"'):
            with self.subTest(errors=errors):
                self.report(errors=errors)
                self.run_guard(2)

    def test_no_inputs_is_incomplete(self):
        for inputs in ("", "<Jar> </Jar>"):
            with self.subTest(inputs=inputs):
                self.report(inputs=inputs)
                self.run_guard(2)

    def test_absent_api_pair_cannot_be_replaced_by_another_module(self):
        self.report(module="other")
        self.assertIn("api", self.run_guard(2))

    def test_missing_empty_or_malformed_reports_are_incomplete(self):
        for filename in ("main.html", "main.xml"):
            for content in (None, "", " \n"):
                with self.subTest(filename=filename, content=content):
                    path = self.report() / filename
                    if content is None:
                        path.unlink()
                    else:
                        path.write_text(content, encoding="utf-8")
                    self.run_guard(2)
        for xml in ("not XML", "<WrongRoot/>", "<BugCollection/>"):
            with self.subTest(xml=xml):
                (self.report() / "main.xml").write_text(xml, encoding="utf-8")
                self.run_guard(2)

    def test_nested_modules_are_checked_and_identified(self):
        self.report()
        self.report(module="services/foo", bugs="<BugInstance/>")
        output = self.run_guard(1)
        self.assertIn("services/foo", output)
        self.assertIn("api", output)

    def test_either_partial_nested_pair_is_incomplete(self):
        self.report()
        for filename in ("main.xml", "main.html"):
            with self.subTest(filename=filename):
                (self.report(module="services/foo") / filename).unlink()
                self.run_guard(2)

    def test_incomplete_module_does_not_prevent_other_module_diagnostics(self):
        self.report(bugs="<BugInstance/>")
        (self.report(module="services/foo") / "main.xml").write_text("invalid", encoding="utf-8")
        output = self.run_guard(2)
        self.assertIn("findings=1", output)
        self.assertIn("services/foo", output)

    def test_non_report_files_are_ignored(self):
        self.report()
        (self.root / ".env").write_text("PRIVATE=do-not-print", encoding="utf-8")
        self.assertNotIn("do-not-print", self.run_guard(0))

    def test_guard_failure_survives_the_workflow_tee_pipeline(self):
        self.report(bugs="<BugInstance/>")
        env = os.environ.copy()
        env.update(GUARD=str(SCRIPT), REPORT_ROOT=str(self.root),
                   GITHUB_STEP_SUMMARY=str(self.root / "summary.md"), PYTHON=sys.executable)
        result = subprocess.run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
             '"$PYTHON" "$GUARD" "$REPORT_ROOT" | tee -a "$GITHUB_STEP_SUMMARY"'],
            env=env, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("HUMAN_REVIEW_REQUIRED", (self.root / "summary.md").read_text())


class SpotBugsWorkflowTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / ".github/workflows/spotbugs.yml"
        self.assertTrue(path.is_file(), "independent SpotBugs workflow is missing")
        self.workflow = path.read_text(encoding="utf-8")

    def test_pr_only_workflow_keeps_ci_and_cd_independent(self):
        self.assertIn("on:\n  pull_request:\n    branches: [main]", self.workflow)
        for forbidden in ("pull_request_target", "workflow_run:", "push:", "schedule:",
                          "workflow_dispatch:", "needs:", "services:", "secrets.", ": write"):
            self.assertNotIn(forbidden, self.workflow)

    def test_analysis_command_and_failure_visibility_are_preserved(self):
        self.assertIn("run: ./gradlew spotbugsMain --continue --no-daemon", self.workflow)
        self.assertIn("shell: bash", self.workflow)
        self.assertIn('run: python3 scripts/check_spotbugs_report.py | tee -a "$GITHUB_STEP_SUMMARY"',
                      self.workflow)
        self.assertEqual(self.workflow.count("if: ${{ !cancelled() }}"), 3)
        for forbidden in ("continue-on-error", "|| true", "ignoreFailures", ":api:spotbugsMain",
                          "./gradlew build", "./gradlew check", "./gradlew test", "docker "):
            self.assertNotIn(forbidden, self.workflow)

    def test_advisory_runs_its_guard_contract_tests(self):
        self.assertIn("run: python3 scripts/test_check_spotbugs_report.py", self.workflow)

    def test_advisory_self_tests_do_not_require_cd_workflow(self):
        with tempfile.TemporaryDirectory(prefix="kirikick-spotbugs-independent-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / ".github/workflows").mkdir(parents=True)
            shutil.copy2(__file__, root / "scripts/test_check_spotbugs_report.py")
            shutil.copy2(ROOT / ".github/workflows/spotbugs.yml", root / ".github/workflows/spotbugs.yml")
            result = subprocess.run(
                [sys.executable, str(root / "scripts/test_check_spotbugs_report.py"),
                 "SpotBugsWorkflowTest.test_pr_only_workflow_keeps_ci_and_cd_independent"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_least_privilege_pinned_actions_and_pr_cancellation(self):
        self.assertIn("permissions:\n  contents: read", self.workflow)
        self.assertIn("persist-credentials: false", self.workflow)
        self.assertIn("cache-read-only: true", self.workflow)
        self.assertIn("cancel-in-progress: true", self.workflow)
        self.assertIn("github.event.pull_request.number", self.workflow)
        for line in self.workflow.splitlines():
            if "uses:" in line:
                self.assertRegex(line, r"uses: [\w/-]+@[a-f0-9]{40}(?:\s|$)")

    def test_only_recursive_main_report_paths_are_uploaded(self):
        upload = self.workflow.split("      - name: Upload reports\n", 1)[1].split(
            "      - name: Summarize outcomes\n", 1)[0]
        self.assertIn("**/build/reports/spotbugs/main.html", upload)
        self.assertIn("**/build/reports/spotbugs/main.xml", upload)
        self.assertIn("retention-days: 7", upload)
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("github.run_id", upload)
        self.assertIn("github.run_attempt", upload)

    def test_summary_preserves_nonzero_analysis_even_with_clean_reports(self):
        self.assertIn("${{ steps.analysis.outcome }}", self.workflow)
        self.assertIn("${{ steps.reports.outcome }}", self.workflow)
        self.assertIn("${{ steps.upload.outcome }}", self.workflow)
        script = textwrap.dedent(self.workflow.split(
            "      - name: Summarize outcomes\n", 1)[1].split("        run: |\n", 1)[1])
        for analyzer, reports, upload, needs_review in (
                ("success", "success", "success", False),
                ("failure", "success", "success", True),
                ("success", "failure", "success", True),
                ("success", "success", "failure", True)):
            with self.subTest(analyzer=analyzer, reports=reports, upload=upload):
                with tempfile.TemporaryDirectory(prefix="kirikick-spotbugs-summary-") as temporary:
                    summary = Path(temporary) / "summary.md"
                    env = os.environ.copy()
                    env.update(ANALYZER_OUTCOME=analyzer, REPORT_OUTCOME=reports,
                               UPLOAD_OUTCOME=upload, GITHUB_STEP_SUMMARY=str(summary))
                    result = subprocess.run(
                        ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script],
                        env=env, capture_output=True, text=True, check=False,
                    )
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    output = summary.read_text(encoding="utf-8")
                    self.assertIn(f"Analyzer: {analyzer}", output)
                    self.assertIn(f"Report guard: {reports}", output)
                    self.assertIn(f"Artifact upload: {upload}", output)
                    self.assertEqual("HUMAN_REVIEW_REQUIRED" in output, needs_review)


if __name__ == "__main__":
    unittest.main()
