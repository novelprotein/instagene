"""Exercise real Git hooks with lightweight check stubs in isolated repositories."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent.parent
BASH = shutil.which("bash") or shutil.which("bash", path="C:/Program Files/Git/bin")


class CommitGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="instagene hook tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.log = Path(self.temp.name) / "checks.log"
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        self.env.pop("JAVA_HOME", None)
        self.env.pop("INSTAGENE_PYTHON", None)
        self.env["CHECK_LOG"] = str(self.log)
        for name in (".githooks/pre-commit", "scripts/check-ci.sh", "scripts/check-build.sh",
                     "scripts/check-env.sh", "scripts/install-hooks.sh"):
            target = self.root / name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(SOURCE / name, target)
            target.chmod(0o755)
        runtime = self.root / "runtime" / "bin"
        runtime.mkdir(parents=True)
        (runtime / "java").write_text('#!/usr/bin/env bash\nexit "${NO_JAVA:-0}"\n', encoding="utf-8")
        (runtime / "java").chmod(0o755)
        for script, phase in (("test", "tests"), ("compile", "build"),
                              ("check-docs", "docs"), ("check-distributions", "distributions")):
            (self.root / "scripts" / f"{script}.sh").write_text(
                '#!/usr/bin/env bash\nset -eu\n'
                f'echo "{phase}:$*" >> "$CHECK_LOG"\n'
                f'if [[ "${{FAIL_PHASE:-}}" == "{phase}" ]]; then exit 7; fi\n'
                'if [[ "${MUTATE_PHASE:-}" == "' + phase + '" ]]; then\n'
                '    echo changed-during-checks >> fixture.txt\n'
                '    if [[ "${STAGE_MUTATION:-}" == 1 ]]; then git add fixture.txt; fi\n'
                'fi\n', encoding="utf-8")
        (self.root / "scripts" / "test-hooks.py").write_text(
            'import os, sys\n'
            'with open(os.environ["CHECK_LOG"], "a") as log: log.write("hooks:\\n")\n'
            'sys.exit(7 if os.environ.get("FAIL_PHASE") == "hooks" else 0)\n', encoding="utf-8")
        (self.root / "fixture.txt").write_text("base\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("config", "user.name", "Hook Test")
        self.git("config", "user.email", "hook-test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "disabled-hooks"))
        self.git("config", "instagene.javaHome", str(runtime.parent))
        self.git("config", "instagene.python", sys.executable)
        self.git("add", ".")
        self.git("commit", "-qm", "Initial fixture")
        self.head = self.git("rev-parse", "HEAD").stdout.strip()
        self.run_command([BASH, str(self.root / "scripts/install-hooks.sh")], cwd=self.temp.name)
        self.assertEqual(".githooks", self.git("config", "--local", "--get", "core.hooksPath").stdout.strip())
        (self.root / "fixture.txt").write_text("staged\n", encoding="utf-8")
        self.git("add", "fixture.txt")

    def run_command(self, args, *, check=True, cwd=None):
        result = subprocess.run(args, cwd=cwd or self.root, env=self.env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8")
        if check:
            self.assertEqual(0, result.returncode, result.stdout)
        return result

    def git(self, *args, check=True):
        return self.run_command(["git", *args], check=check)

    def assert_blocked(self, message):
        result = self.git("commit", "-qm", "Validate commit gate", check=False)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(message, result.stdout)
        self.assertEqual(self.head, self.git("rev-parse", "HEAD").stdout.strip())

    def test_success_runs_fresh_tests_build_docs_and_distributions(self):
        self.git("commit", "-qm", "Validate commit gate")
        self.assertEqual(["tests:all --rerun", "build:all build", "docs:", "distributions:", "hooks:"],
                         self.log.read_text().splitlines())

    def test_each_failed_phase_blocks_commit_and_stops_later_checks(self):
        phases = ["tests", "build", "docs", "distributions", "hooks"]
        for index, phase in enumerate(phases):
            with self.subTest(phase=phase):
                self.log.unlink(missing_ok=True)
                self.env["FAIL_PHASE"] = phase
                self.assert_blocked("Commit blocked: local checks failed")
                self.assertEqual(phases[:index + 1], [line.split(":")[0] for line in self.log.read_text().splitlines()])

    def test_unstaged_tracked_changes_block_before_checks(self):
        (self.root / "fixture.txt").write_text("unstaged\n", encoding="utf-8")
        self.assert_blocked("stage or restore all unstaged tracked changes")
        self.assertFalse(self.log.exists())

    def test_working_tree_change_during_checks_blocks_commit(self):
        self.env["MUTATE_PHASE"] = "docs"
        self.assert_blocked("stage or restore all unstaged tracked changes")

    def test_index_change_during_checks_blocks_commit(self):
        self.env.update(MUTATE_PHASE="docs", STAGE_MUTATION="1")
        self.assert_blocked("staged files changed during validation")

    def test_merge_cherry_pick_and_revert_do_not_bypass_checks(self):
        self.env["FAIL_PHASE"] = "tests"
        for marker in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD"):
            with self.subTest(marker=marker):
                path = self.root / ".git" / marker
                path.write_text(self.head + "\n", encoding="utf-8")
                try:
                    self.log.unlink(missing_ok=True)
                    result = self.run_command([BASH, ".githooks/pre-commit"], check=False)
                    self.assertNotEqual(0, result.returncode, result.stdout)
                    self.assertEqual(["tests:all --rerun"], self.log.read_text().splitlines())
                finally:
                    path.unlink()

    def test_unavailable_java_blocks_before_tests(self):
        self.env["NO_JAVA"] = "127"
        self.assert_blocked("Java is unavailable")
        self.assertFalse(self.log.exists())

    def test_unavailable_python_blocks_docs_and_distributions(self):
        self.env["INSTAGENE_PYTHON"] = str(self.root / "missing-python")
        for name, message in (("check-docs", "MkDocs is not installed"),
                              ("check-distributions", "Python is unavailable")):
            with self.subTest(script=name):
                shutil.copyfile(SOURCE / "scripts" / f"{name}.sh", self.root / "scripts" / f"{name}.sh")
                self.git("add", f"scripts/{name}.sh")
                if name == "check-distributions":
                    # Isolate the distribution prerequisite check from the docs prerequisite.
                    result = self.run_command([BASH, "scripts/check-distributions.sh"], check=False)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stdout)
                else:
                    self.assert_blocked(message)


if __name__ == "__main__":
    if BASH is None:
        raise SystemExit("Bash is required to test the commit hooks.")
    unittest.main()
