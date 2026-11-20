import contextlib
import io
import os
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[2] / "src/android/app/src/main/assets/skills/github-ops/scripts"))
import gh_ci


SHA = "d53a0ae8966cbed8d28aa1a53c2ca9e87c28ca62"
FAILED = [{"name": "Run pre-build scan gate", "status": "completed", "conclusion": "failure"},
          {"name": "Download APK Artifact", "status": "pending", "conclusion": None}]


def run(status="in_progress", conclusion=None, attempt=2):
    return {"id": 7, "run_number": 42, "run_attempt": attempt,
            "status": status, "conclusion": conclusion, "head_sha": SHA,
            "head_branch": "fix/ci", "event": "workflow_dispatch",
            "name": "Build RikkaMinis", "path": ".github/workflows/build-apk.yml",
            "html_url": "https://github.com/example/run/7"}


def job(name, status="completed", conclusion="success", steps=None):
    return {"name": name, "status": status, "conclusion": conclusion,
            "steps": steps or [], "html_url": "https://github.com/example/job/1"}


class GhCiTests(unittest.TestCase):
    def test_job_failure_surfaces_while_workflow_and_siblings_run(self):
        jobs = [
            job("scan-gate", conclusion="failure", steps=FAILED),
            job("unit-tests", "in_progress", None, [
                {"name": "Run unit tests", "status": "in_progress", "conclusion": None}]),
            job("build-apk", "in_progress", None, [
                {"name": "Build APK with Gradle", "status": "in_progress", "conclusion": None}]),
            job("lint", conclusion="success"),
        ]
        with patch.object(gh_ci, "list_jobs", return_value=jobs), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(len(jobs), len(gh_ci.show_snapshot("o/r", run())))
        text = out.getvalue()
        self.assertIn("1 个 job 失败；workflow 仍为 in_progress", text)
        self.assertIn("scan-gate: failure；失败步骤：Run pre-build scan gate", text)
        self.assertIn("unit-tests: in_progress；当前步骤：Run unit tests", text)
        self.assertIn("build-apk: in_progress；当前步骤：Build APK with Gradle", text)
        self.assertIn("lint: success", text)
        self.assertNotIn("VERDICT", text)

    def test_single_page_result_stops_pagination(self):
        calls = []
        def fake_api(repo, path, params=None, payload=None):
            calls.append((path, params))
            if params["page"] == 1:
                return {"total_count": 1, "jobs": [{"name": "scan"}]}
            raise AssertionError("should not request another page")
        with patch.object(gh_ci, "api", side_effect=fake_api):
            self.assertEqual(["scan"], [item["name"] for item in gh_ci.list_jobs("o/r", run(attempt=3))])
        self.assertEqual(["runs/7/attempts/3/jobs"], [item[0] for item in calls])
        self.assertEqual([1], [item[1]["page"] for item in calls])

    def test_pagination_acquires_all_current_jobs(self):
        calls = []
        def fake_api(repo, path, params=None, payload=None):
            calls.append((path, params))
            if params["page"] == 1:
                return {"total_count": 2, "jobs": [{"name": "scan"}]}
            if params["page"] == 2:
                return {"total_count": 2, "jobs": [{"name": "unit"}]}
            raise AssertionError("should not request another page")
        with patch.object(gh_ci, "api", side_effect=fake_api):
            self.assertEqual(["scan", "unit"], [item["name"] for item in gh_ci.list_jobs("o/r", run(attempt=3))])
        self.assertEqual([1, 2], [item[1]["page"] for item in calls])

    def test_missing_job_details_is_unknown_not_green(self):
        args = SimpleNamespace(repo="o/r", run_id=7, workflow=None, branch=None,
                               status=None, expect=SHA, summary=False)
        with patch.object(gh_ci, "api", return_value=run("completed", "success")), \
             patch.object(gh_ci, "list_jobs", return_value=[]), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(3, gh_ci.snapshot(args))
        self.assertIn("不能据此判断通过", out.getvalue())

    def test_failed_completed_run_is_nonzero(self):
        args = SimpleNamespace(repo="o/r", run_id=7, workflow=None, branch=None,
                               status=None, expect=SHA, summary=False)
        with patch.object(gh_ci, "api", return_value=run("completed", "failure")), \
             patch.object(gh_ci, "list_jobs", return_value=[job("scan-gate", conclusion="failure", steps=FAILED)]), \
             contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(1, gh_ci.snapshot(args))
        text = out.getvalue()
        self.assertIn("→ failure", text)
        self.assertIn("VERDICT", text)
        self.assertNotIn("→ success", text)

    def test_wait_reuses_completed_success_for_expected_head(self):
        active = dict(run("completed", "success"), id=9, run_number=44)
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=False, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=False)
        paths = []
        def fake_api(repo, path, params=None, payload=None):
            paths.append(path)
            if path == "workflows/build-apk.yml/runs":
                return {"workflow_runs": [active]}
            if path == "runs/9":
                return active
            if path == "runs/9/attempts/2/jobs":
                return {"total_count": 1, "jobs": [job("scan-gate")]}
            raise AssertionError(f"unexpected API path: {path}")
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, gh_ci.wait(args))
        self.assertIn("不重复 dispatch", out.getvalue())
        self.assertNotIn("workflows/build-apk.yml/dispatches", paths)

    def test_wait_dispatches_when_no_prior_run_on_expected_head(self):
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=False, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=False)
        fresh = dict(run("in_progress", None), id=9, run_number=44)
        calls = {"n": 0}
        def fake_api(repo, path, params=None, payload=None):
            if path == "workflows/build-apk.yml/dispatches":
                return {}
            if path == "workflows/build-apk.yml/runs":
                calls["n"] += 1
                # First call (before dispatch) sees an unrelated-head run,
                # second call sees our fresh run on the expected head.
                if calls["n"] == 1:
                    unrelated = dict(run("completed", "success"), head_sha="0" * 40)
                    return {"workflow_runs": [unrelated]}
                return {"workflow_runs": [fresh]}
            if path == "runs/9":
                return dict(fresh, status="completed", conclusion="success")
            if path == "runs/9/attempts/2/jobs":
                return {"total_count": 1, "jobs": [job("scan-gate")]}
            raise AssertionError(f"unexpected API path: {path}")
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, gh_ci.wait(args))
        self.assertIn("dispatched", out.getvalue())

    def test_dispatch_selects_only_newer_run_than_baseline(self):
        stale = dict(run("completed", "failure"), id=7, run_number=42)
        fresh = dict(run("in_progress", None), id=8, run_number=43)
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=False, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=False)
        calls = {"n": 0}
        def fake_api(repo, path, params=None, payload=None):
            if path == "workflows/build-apk.yml/dispatches":
                return {}
            if path == "workflows/build-apk.yml/runs":
                calls["n"] += 1
                if calls["n"] == 1:
                    return {"workflow_runs": [stale]}
                # API cache may list the old run first even after dispatch.
                return {"workflow_runs": [stale, fresh]}
            if path == "runs/8":
                return dict(fresh, status="completed", conclusion="failure")
            if path == "runs/8/attempts/2/jobs":
                return {"total_count": 1, "jobs": [job("scan-gate", conclusion="failure")]}
            raise AssertionError(f"unexpected API path: {path}")
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(1, gh_ci.wait(args))
        self.assertIn("#43", out.getvalue())
        self.assertNotIn("VERDICT #42", out.getvalue())

    def test_dispatch_without_new_run_times_out(self):
        stale = dict(run("completed", "failure"), id=7, run_number=42)
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=False, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=False)
        paths = []
        def fake_api(repo, path, params=None, payload=None):
            paths.append(path)
            if path == "workflows/build-apk.yml/runs":
                return {"workflow_runs": [stale]}
            if path == "workflows/build-apk.yml/dispatches":
                return {}
            raise AssertionError(f"unexpected API path: {path}")
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(2, gh_ci.wait(args))
        self.assertIn("workflows/build-apk.yml/dispatches", paths)
        self.assertIn("timeout after 3s", out.getvalue())

    def test_no_dispatch_locks_run_id_and_reads_current_attempt(self):
        # no_dispatch now locks the run ID upfront, so polls must return the
        # completed run; attempt=2 keeps the "reads current attempt only"
        # contract under test.
        completed = run("completed", "success")
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=True, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=False)
        def fake_api(repo, path, params=None, payload=None):
            if path == "workflows/build-apk.yml/runs":
                return {"workflow_runs": [completed]}
            if path == "runs/7/attempts/2/jobs":
                return {"total_count": 1, "jobs": [job("scan-gate")]}
            if path != "runs/7":
                raise AssertionError(f"unexpected API path: {path}")
            return completed
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, gh_ci.wait(args))
        self.assertIn("attempt=2", out.getvalue())
        self.assertIn("VERDICT #42", out.getvalue())

    def test_fail_fast_stops_when_one_parallel_job_fails(self):
        active = run()
        failed = job("scan-gate", conclusion="failure", steps=FAILED)
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=True, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=True)
        def fake_api(repo, path, params=None, payload=None):
            if path == "workflows/build-apk.yml/runs":
                return {"workflow_runs": [active]}
            if path == "runs/7/attempts/2/jobs":
                return {"total_count": 1, "jobs": [failed]}
            if path != "runs/7":
                raise AssertionError(f"unexpected API path: {path}")
            return active
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(1, gh_ci.wait(args))
        self.assertIn("workflow 总状态仍为 in_progress", out.getvalue())

    def test_completed_failure_does_not_claim_fail_fast_siblings_remain(self):
        completed = run("completed", "failure")
        args = SimpleNamespace(repo="o/r", workflow="build-apk.yml", branch="fix/ci",
                               expect=SHA, no_dispatch=True, exclude_main_backfill=False,
                               timeout=3, poll=1, fail_fast=True)
        def fake_api(repo, path, params=None, payload=None):
            if path == "workflows/build-apk.yml/runs":
                return {"workflow_runs": [completed]}
            if path == "runs/7/attempts/2/jobs":
                return {"total_count": 1, "jobs": [job("scan-gate", conclusion="failure", steps=FAILED)]}
            if path != "runs/7":
                raise AssertionError(f"unexpected API path: {path}")
            return completed
        with patch.object(gh_ci, "api", side_effect=fake_api), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(1, gh_ci.wait(args))
        text = out.getvalue()
        self.assertIn("按 --fail-fast 停止等待。", text)
        self.assertNotIn("workflow 总状态仍为", text)

    def test_head_mismatch_rejected(self):
        self.assertFalse(gh_ci.matches_head(run(), "0" * 40))
        self.assertTrue(gh_ci.matches_head(run(), SHA))

    def test_curl_config_uses_stdin_and_redacts_token(self):
        calls = []
        def fake_run(cmd, **kwargs):
            calls.append((cmd, kwargs))
            return subprocess.CompletedProcess(cmd, 1, stdout="", stderr="Authorization: Bearer secret")
        with patch.dict(os.environ, {"GITHUB_TOKEN": "secret"}), patch.object(gh_ci.subprocess, "run", fake_run):
            with self.assertRaisesRegex(gh_ci.QueryError, r"\[REDACTED\]"):
                gh_ci.api("o/r", "x", payload={"ref": "main"})
        cmd, kwargs = calls[0]
        self.assertEqual("-", cmd[cmd.index("--config") + 1])
        self.assertNotIn("secret", str(cmd))
        self.assertIn("Authorization: Bearer secret", kwargs["input"])
        upload_arg = cmd[cmd.index("--upload-file") + 1]
        self.assertTrue(upload_arg.startswith("/tmp/minis-gh-body."))
        self.assertNotIn("secret", upload_arg)
        self.assertNotIn('{"ref": "main"}', kwargs["input"])


if __name__ == "__main__":
    unittest.main()
