import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
GIT = shutil.which("git")


class GitHooksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kirikick hooks ")
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / "repo with spaces"
        self.repo.mkdir()
        self.log = Path(self.temporary.name) / "commands.log"
        self.env = os.environ.copy()
        self.env["HOOK_TEST_LOG"] = self.log.as_posix()
        self.env["GIT_CONFIG_GLOBAL"] = os.devnull
        self.env["GIT_CONFIG_NOSYSTEM"] = "1"
        self.git("init", "-q")
        self.git("config", "user.name", "Hook Test")
        self.git("config", "user.email", "hooks@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", ".githooks")
        self.assertTrue((ROOT / ".githooks/pre-commit").is_file(), "pre-commit hook is missing")
        shutil.copytree(ROOT / ".githooks", self.repo / ".githooks")
        shutil.copyfile(ROOT / ".gitattributes", self.repo / ".gitattributes")
        tools = Path(self.temporary.name) / "bin"
        tools.mkdir()
        self.tools = tools
        self.env["PATH"] = str(tools) + os.pathsep + self.env["PATH"]
        self.executable(tools / "gitleaks", 'printf "gitleaks %s\\n" "$*" >> "$HOOK_TEST_LOG"\nexit "${HOOK_TEST_GITLEAKS_EXIT:-0}"\n')
        self.executable(self.repo / "gradlew", 'printf "gradle %s\\n" "$*" >> "$HOOK_TEST_LOG"\nif IFS= read -r ignored; then exit 99; fi\nexit "${HOOK_TEST_GRADLE_EXIT:-0}"\n')
        (self.repo / "sample.txt").write_text("safe test fixture\n", encoding="utf-8")
        self.git("add", "--chmod=+x", ".githooks/pre-commit", ".githooks/pre-push", "gradlew")
        self.git("add", "sample.txt")

    @staticmethod
    def executable(path, body):
        path.write_bytes(("#!/bin/sh\n" + body).encode())
        path.chmod(0o755)

    def git(self, *arguments, success=True):
        result = subprocess.run([GIT, *arguments], cwd=self.repo, env=self.env,
                                text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_commit_checks_staged_secrets_then_format(self):
        self.git("commit", "-qm", "clean fixture")
        self.assertEqual(self.log.read_text(),
                         "gitleaks git --staged --redact --no-banner\ngradle spotlessCheck\n")

    def test_secret_failure_stops_commit_before_format(self):
        self.env["HOOK_TEST_GITLEAKS_EXIT"] = "23"
        self.git("commit", "-qm", "rejected fixture", success=False)
        self.assertEqual(self.log.read_text(), "gitleaks git --staged --redact --no-banner\n")

    def test_missing_gitleaks_stops_commit(self):
        (self.tools / "gitleaks").unlink()
        self.executable(self.tools / "git", 'exec "$HOOK_TEST_GIT" "$@"\n')
        self.env["HOOK_TEST_GIT"] = Path(GIT).as_posix()
        self.env["PATH"] = str(self.tools)
        result = self.git("commit", "-qm", "rejected fixture", success=False)
        self.assertIn("Gitleaks is required on PATH", result.stderr)
        self.assertFalse(self.log.exists())

    def test_format_failure_stops_commit(self):
        self.env["HOOK_TEST_GRADLE_EXIT"] = "24"
        self.git("commit", "-qm", "rejected fixture", success=False)
        self.assertIn("gradle spotlessCheck\n", self.log.read_text())

    def test_push_runs_tests_without_consuming_ref_input(self):
        self.git("commit", "-qm", "clean fixture")
        remote = Path(self.temporary.name) / "remote.git"
        self.git("init", "--bare", "-q", str(remote))
        self.log.write_text("")
        self.git("push", str(remote), "HEAD:refs/heads/hook-test")
        self.assertEqual(self.log.read_text(), "gradle test\n")

    def test_failed_tests_stop_push(self):
        self.git("commit", "-qm", "clean fixture")
        remote = Path(self.temporary.name) / "remote.git"
        self.git("init", "--bare", "-q", str(remote))
        self.env["HOOK_TEST_GRADLE_EXIT"] = "25"
        self.git("push", str(remote), "HEAD:refs/heads/hook-test", success=False)
        refs = self.git("--git-dir", str(remote), "show-ref", success=False)
        self.assertEqual(refs.stdout, "")

    def test_line_endings_are_independent_of_autocrlf(self):
        files = {"Example.java": b"class Example {}\r\n", "sample.bat": b"@echo off\n",
                 "sample.cmd": b"@echo off\n", "wrapper.jar": b"\x00binary\r\n\x00"}
        for name, content in files.items():
            (self.repo / name).write_bytes(content)
        self.git("add", ".gitattributes", *files)
        for autocrlf in ("true", "false"):
            with self.subTest(autocrlf=autocrlf):
                self.git("config", "core.autocrlf", autocrlf)
                output = Path(self.temporary.name) / ("checkout-" + autocrlf)
                self.git("checkout-index", "--all", "--prefix=" + output.as_posix() + "/")
                self.assertEqual((output / "Example.java").read_bytes(), b"class Example {}\n")
                for name in ("sample.bat", "sample.cmd"):
                    self.assertEqual((output / name).read_bytes(), b"@echo off\r\n")
                self.assertNotIn(b"\r\n", (output / ".githooks/pre-commit").read_bytes())
                self.assertNotIn(b"\r\n", (output / "gradlew").read_bytes())
                self.assertEqual((output / "wrapper.jar").read_bytes(), files["wrapper.jar"])


if __name__ == "__main__":
    unittest.main()
