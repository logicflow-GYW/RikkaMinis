#!/usr/bin/env python3
"""Guard the cheap (scan-only) CI channel.

Why this exists
---------------
`build-apk.yml` costs 18-27 minutes. For a diff that touches no production
source, almost every one of those minutes is a step that CANNOT fail: Gradle
compiles code the branch did not change. Measured 2026-10-27 over 10-12..10-27:
123 apparatus-only branch runs burned 2,356 CI minutes (20% of all CI time) at
a 19.7 min median, and 12/12 sampled runs were green on every step outside the
scan gate — i.e. the 20-minute run produced no branch-specific evidence beyond
what `scan-gate-push.yml` produces in ~7 minutes.

The risk this guard removes
---------------------------
An agent dispatches this cheap workflow for a `src/**` change, sees green, and
treats it as the deterministic stop node (branch CI green = stop). Production
code would then land with no build / no unit-test evidence at all. So the cheap
channel refuses to run unless the diff is apparatus-only: fail closed, and name
the offending paths so the caller dispatches `build-apk.yml` instead.

Whitelist — paths that cannot affect the Gradle build or the scanners' subjects
    scripts/scan/**   scripts/diag/**   fix-reports/**   docs/**   *.md (root)
    .github/workflows/scan-gate-push.yml

Modes
    --repo OWNER/NAME --head <sha> [--base main]   CI mode ($GITHUB_TOKEN)
    --git-range <base>..<head>                     local mode (real diffs, no API)
    --paths-file <file|->                          offline mode (one path per line)

Exit codes: 0 apparatus-only · 1 non-apparatus paths · 2 cannot prove (fail closed)
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

# A path is apparatus-only if it matches one of these and nothing else.
ALLOWED = (
    r"^scripts/scan/",
    r"^scripts/diag/",
    r"^fix-reports/",
    r"^docs/",
    r"^[^/]+\.md$",  # repo-root markdown (README, BUILDING, ...) — no build input
    r"^\.github/workflows/scan-gate-push\.yml$",
)
ALLOWED_RE = tuple(re.compile(p) for p in ALLOWED)

# The compare API caps `files` at 300 entries with no truncation flag; hitting
# the cap means "we cannot prove this diff is small", which fails closed.
COMPARE_FILE_CAP = 300


def classify(paths):
    """Pure classifier. -> (ok, offenders)."""
    offenders = [p for p in paths if p and not any(r.search(p) for r in ALLOWED_RE)]
    return (not offenders), offenders


def read_paths_file(path):
    if path == "-":
        return [ln.strip() for ln in sys.stdin if ln.strip()]
    with open(path, encoding="utf-8") as fh:
        return [ln.strip() for ln in fh if ln.strip()]


def git_range_paths(spec):
    base, _, head = spec.partition("..")
    if not base or not head:
        raise SystemExit("--git-range expects BASE..HEAD")
    # Three-dot: diff against the merge base, so a main that advanced while the
    # branch was open does not show up as branch changes.
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    ).stdout
    return [ln.strip() for ln in out.splitlines() if ln.strip()]


def cannot_prove(msg):
    # Exit 2 = guard could not produce a verdict. Still red on CI (nonzero),
    # but distinguishable from exit 1 ("wrong channel for this diff").
    print(f"cannot prove: {msg}")
    raise SystemExit(2)


def api_compare_paths(repo, base, head):
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        cannot_prove("GITHUB_TOKEN is not set")
    url = f"https://api.github.com/repos/{repo}/compare/{base}...{head}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.load(resp)
    except (urllib.error.URLError, urllib.error.HTTPError, OSError) as exc:
        cannot_prove(f"compare API failed ({exc})")
    files = payload.get("files")
    if files is None:
        cannot_prove("compare API returned no file list")
    if len(files) >= COMPARE_FILE_CAP:
        cannot_prove(
            f"compare API hit its {COMPARE_FILE_CAP}-file cap; "
            "dispatch build-apk.yml for a diff this large"
        )
    return [f.get("filename", "") for f in files]


def main(argv):
    ap = argparse.ArgumentParser(description="apparatus-only diff guard")
    ap.add_argument("--repo")
    ap.add_argument("--base", default="main")
    ap.add_argument("--head")
    ap.add_argument("--git-range")
    ap.add_argument("--paths-file")
    args = ap.parse_args(argv)

    if args.git_range:
        paths = git_range_paths(args.git_range)
        source = f"git {args.git_range}"
    elif args.paths_file:
        paths = read_paths_file(args.paths_file)
        source = args.paths_file
    elif args.repo and args.head:
        paths = api_compare_paths(args.repo, args.base, args.head)
        source = f"{args.base}...{args.head}"
    else:
        ap.error("give --git-range, --paths-file, or --repo with --head")

    ok, offenders = classify(paths)
    print(f"apparatus-guard: {len(paths)} changed path(s) from {source}")
    if ok:
        print("apparatus-guard: OK — nothing here can affect the Gradle build")
        return 0
    print("::error::This workflow is the CHEAP channel (scan gates + fixtures only).")
    print("::error::The diff touches paths it cannot verify — dispatch build-apk.yml instead.")
    for p in offenders:
        print(f"  offending: {p}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
