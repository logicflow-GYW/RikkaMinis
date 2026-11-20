#!/usr/bin/env python3
"""Shared GitHub Actions snapshot for gh_sync.sh and gh_ci_wait.sh."""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlencode

HARD_FAILURE = {"failure", "timed_out", "action_required", "stale", "startup_failure"}
ACTIVE = {"queued", "in_progress", "requested", "waiting", "pending"}


class QueryError(Exception):
    pass


def curl_json(method, url, payload=None):
    """Use curl because api.github.com intermittently breaks urllib's TLS path here.

    The config is passed on stdin: GitHub tokens therefore never appear in argv,
    where another process could read them through /proc.
    """
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise QueryError("missing env: GITHUB_TOKEN")
    config = "\n".join([
        f'header = "Authorization: Bearer {token}"',
        'header = "Accept: application/vnd.github+json"',
        'header = "User-Agent: minis"',
        "noproxy = \"*\"",
        "fail = \"\"",
        "retry = 3",
        "retry-delay = 1",
        "max-time = 30",
        f'request = "{method}"',
    ])
    # A separate temporary body prevents the request payload and curl's
    # configuration from competing for stdin. The final command uses
    # --upload-file, so the temporary file's contents remain the POST body.
    body_fd, body_path = tempfile.mkstemp(prefix="minis-gh-body.")
    with os.fdopen(body_fd, "w") as body:
        body.write(json.dumps(payload))
    config += '\nheader = "Content-Type: application/json"\n'
    # curl reads the request body from a separate temporary file; stdin carries
    # only the configuration so neither argv nor stdin can expose the bearer token.
    cmd = ["curl", "-sS", "--config", "-", "--upload-file", body_path, url]
    result = subprocess.run(cmd, input=config, capture_output=True, text=True)
    try:
        if result.returncode:
            first_line = (result.stderr.splitlines() or [f"curl exit {result.returncode}"])[-1]
            raise QueryError(first_line.replace(token, "[REDACTED]"))
        if method == "POST" and not result.stdout.strip():
            return {}
        try:
            return json.loads(result.stdout)
        except (json.JSONDecodeError, ValueError) as exc:
            raise QueryError(f"响应不是合法 JSON: {exc}") from None
    finally:
        os.unlink(body_path)


def api(repo, path, params=None, payload=None):
    url = f"https://api.github.com/repos/{repo}/actions/{path}"
    if params:
        url += "?" + urlencode(params)
    return curl_json("POST" if payload is not None else "GET", url, payload)


def list_runs(args):
    path = f"workflows/{args.workflow}/runs" if args.workflow else "runs"
    params = {"per_page": 15}
    for key in ("branch", "status"):
        if getattr(args, key, None):
            params[key] = getattr(args, key)
    runs = api(args.repo, path, params)["workflow_runs"]
    return [run for run in runs if not (
        getattr(args, "exclude_main_backfill", False)
        and run["event"] == "push" and run["head_branch"] == "main"
    )]


def matches_head(run, expect):
    return not expect or run["head_sha"] == expect


def list_jobs(repo, run):
    # Pin to the attempt read with the run status, never mix in an old failed rerun.
    path = f"runs/{run['id']}/attempts/{run['run_attempt']}/jobs"
    jobs = []
    for page in range(1, sys.maxsize):
        doc = api(repo, path, {"per_page": 100, "page": page})
        batch = doc["jobs"]
        jobs.extend(batch)
        if len(jobs) >= doc["total_count"]:
            return jobs
        if len(batch) == 0:
            raise QueryError("GitHub 返回的 job 列表不完整")


def state(item):
    status = item.get("status") or "unknown"
    return (item.get("conclusion") or "completed/unknown") if status == "completed" else status


def title(item):
    return " ".join((item.get("name") or "(unnamed)").split())


def run_line(run):
    return (f"#{run['run_number']} (id={run['id']}, attempt={run['run_attempt']}) "
            f"{title(run)} [{run['path']}] {run['head_branch']} @ {run['head_sha']} "
            f"→ {state(run)} · {run['html_url']}")


def job_line(job):
    steps = job.get("steps") or []
    failed = next((step for step in steps if state(step) in HARD_FAILURE), None)
    running = next((step for step in steps if state(step) == "in_progress"), None)
    line = f"  - {title(job)}: {state(job)}"
    if failed:
        line += f"；失败步骤：{title(failed)} ({state(failed)})"
    if running:
        line += f"；当前步骤：{title(running)}"
    release = next((step for step in steps if step.get("name") == "Publish to Releases"), None)
    if release and release is not failed and release is not running:
        line += f"；发布步骤：{state(release)}"
    if state(job) in HARD_FAILURE and job.get("html_url"):
        line += f" · {job['html_url']}"
    return line


def show_snapshot(repo, run, require_completed_details=False):
    """Print the run and every current job; return its jobs once details are available."""
    print(run_line(run), flush=True)
    try:
        jobs = list_jobs(repo, run)
    except (QueryError, KeyError, TypeError) as exc:
        print(f"  job 明细未知：{exc}", flush=True)
        return None
    if not jobs:
        incomplete = run["status"] == "completed" or require_completed_details
        if incomplete:
            print("  GitHub 暂未返回本次 attempt 的 job，不能据此判断通过。", flush=True)
            return None
        print("  GitHub 尚未创建本次 attempt 的 job；workflow 仍为 " + run["status"] + "。", flush=True)
    failed = [job for job in jobs if state(job) in HARD_FAILURE]
    if failed and run["status"] != "completed":
        print(f"  已有 {len(failed)} 个 job 失败；workflow 仍为 {run['status']}。", flush=True)
    for job in sorted(jobs, key=lambda job: state(job) not in HARD_FAILURE):
        print(job_line(job), flush=True)
    return jobs


def snapshot(args):
    if args.run_id:
        runs = [api(args.repo, f"runs/{args.run_id}")]
        if not matches_head(runs[0], args.expect):
            raise QueryError(f"run {args.run_id} 的 head_sha 不匹配，拒绝使用该结果")
    else:
        runs = [run for run in list_runs(args) if matches_head(run, args.expect)]
    if not runs:
        raise QueryError("未找到匹配的 run（请核对 workflow、分支、run ID 和完整 SHA）")
    if args.summary:
        for run in runs[:10]:
            print(run_line(run))
        return 0
    jobs = show_snapshot(args.repo, runs[0])
    verdict = state(runs[0])
    if runs[0]["status"] == "completed":
        print(f"VERDICT #{runs[0]['run_number']} head={runs[0]['head_sha']} → {verdict}")
        return 3 if jobs is None else (0 if verdict == "success" else 1)
    return 3 if jobs is None else 0


def wait(args):
    run_id = None
    if args.no_dispatch:
        # The caller asked to observe an existing run; lock its ID before polling.
        matches = [item for item in list_runs(args) if matches_head(item, args.expect)]
        if not matches:
            raise QueryError("未找到匹配的 run（请核对 workflow、分支和完整 SHA）")
        run_id = matches[0]["id"]
    dispatched_after = None
    if not args.no_dispatch:
        # A branch may also run a separate scan workflow; never reuse one for this workflow.
        before = list_runs(args)
        if args.expect:
            latest = next((r for r in before if matches_head(r, args.expect)), None)
            if latest and (latest["status"] in ACTIVE or latest.get("conclusion") == "success"):
                run_id = latest["id"]
                print(f"复用 #{latest['run_number']}，不重复 dispatch。", flush=True)
        if not run_id:
            dispatched_after = (max((int(item["id"]) for item in before), default=0),
                                max((item["run_number"] for item in before), default=0))
            api(args.repo, f"workflows/{args.workflow}/dispatches", payload={"ref": args.branch})
            print(f"dispatched {args.workflow}@{args.branch}", flush=True)

    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        if run_id is None:
            # Find once, then pin the run ID so an older cached list cannot replace it.
            # A dispatch cannot rely on created_at: concurrent runners can create an
            # older timestamp after ours. A higher run ID or number is the safe signal.
            run = next((r for r in list_runs(args) if matches_head(r, args.expect) and dispatched_after and (
                    r["event"] == "workflow_dispatch" and (
                        int(r["id"]) > dispatched_after[0] or
                        r["run_number"] > dispatched_after[1]
                    )
                )), None)
        else:
            run = api(args.repo, f"runs/{run_id}")
        if run is None:
            print(f"  {args.branch}: 暂无匹配的 run", flush=True)
        else:
            run_id = run["id"]
            if not matches_head(run, args.expect):
                raise QueryError(f"run {run_id} 的 head_sha 不匹配，拒绝使用该结果")
            result = show_snapshot(args.repo, run, require_completed_details=True)
            if run["status"] == "completed":
                verdict = state(run)
                print(f"VERDICT #{run['run_number']} head={run['head_sha']} on {args.branch} → {verdict}")
                if result is None:
                    return 3
                failed = [item for item in result if state(item) in HARD_FAILURE]
                if failed and args.fail_fast:
                    print(f"  已有 {len(failed)} 个 job 失败，按 --fail-fast 停止等待。", flush=True)
                return 0 if verdict == "success" else 1
            if args.fail_fast and result:
                failed = [item for item in result if state(item) in HARD_FAILURE]
                if failed:
                    print(f"  已有 {len(failed)} 个 job 失败，按 --fail-fast 停止等待；"
                          "workflow 总状态仍为 " + state(run) + "。", flush=True)
                    return 1
        time.sleep(max(0, min(args.poll, deadline - time.monotonic())))
    if run_id is None:
        print(f"VERDICT timeout after {args.timeout}s: {args.branch} 仍未出现匹配的 run")
        return 2
    print(f"VERDICT timeout after {args.timeout}s: run {run_id} 无最终结论")
    return 2



def positive_int(raw):
    value = int(raw)
    if value < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="mode", required=True)
    check = commands.add_parser("runs", help="read one run and its jobs without dispatching")
    watch = commands.add_parser("wait", help="watch jobs while waiting for a final run verdict")
    for cmd in (check, watch):
        cmd.add_argument("--repo")
        cmd.add_argument("--workflow")
        cmd.add_argument("--expect", help="full commit SHA")
    check.add_argument("--branch")
    check.add_argument("--status")
    check.add_argument("--run-id", type=positive_int)
    check.add_argument("--summary", action="store_true", help="list recent runs without jobs")
    watch.add_argument("--ref", dest="branch", required=True)
    watch.add_argument("--timeout", type=positive_int, default=900)
    watch.add_argument("--poll", type=positive_int, default=60)
    watch.add_argument("--no-dispatch", action="store_true")
    watch.add_argument("--fail-fast", action="store_true",
                       help="return once a job or run has a hard failure, without waiting for siblings")
    watch.add_argument("--exclude-main-backfill", action="store_true")
    watch.add_argument("--job-detail", action="store_true", help="compatibility: job detail is now default")
    args = parser.parse_args(argv)
    if not args.repo or not all(re.fullmatch(r"[\w.-]+", part, re.ASCII) and part not in {".", ".."}
                               for part in args.repo.split("/")) or args.repo.count("/") != 1:
        parser.error("--repo must be owner/repo (or use an origin on github.com)")
    if args.workflow:
        args.workflow = args.workflow.removeprefix(".github/workflows/")
        if not re.fullmatch(r"[\w.-]+", args.workflow, re.ASCII) or args.workflow in {".", ".."}:
            parser.error("--workflow must be an ID or workflow filename")
    if args.expect:
        if not re.fullmatch(r"[a-fA-F0-9]{40}", args.expect):
            parser.error("--expect must be the full 40-character SHA")
        args.expect = args.expect.lower()
    if args.mode == "wait" and not args.no_dispatch and not args.workflow:
        parser.error("--workflow is required when dispatching")
    if args.mode == "runs" and args.run_id and (args.workflow or args.branch or args.status):
        parser.error("--run-id cannot be combined with --workflow, --branch or --status")
    if not os.environ.get("GITHUB_TOKEN"):
        print("UNKNOWN: missing env: GITHUB_TOKEN", file=sys.stderr)
        return 3
    try:
        if args.mode == "runs":
            rc = snapshot(args)
        else:
            rc = wait(args)
    except (QueryError, KeyError, TypeError) as exc:
        print(f"UNKNOWN: {exc}", file=sys.stderr)
        return 3
    sys.stdout.flush()
    sys.stderr.flush()
    return rc


if __name__ == "__main__":
    sys.exit(main())
