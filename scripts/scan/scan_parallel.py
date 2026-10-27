#!/usr/bin/env python3
"""Parallel driver for the 113 static CI checks.

The checks themselves live in `scripts/scan/scan_blocks.sh` (a data file, one
block per check). This driver splits that file on the `echo "━━━ [N/113] … ━━━"`
header lines, runs every block in its own `sh -c` (cwd = repo root, ROOT
exported), and prints the block output in the original 1..113 order.

Why a pool: the checks are independent single-file/small-tree analyses (each a
python process, a few seconds of wall clock, mostly I/O + tree walks). Run
serially they cost ~9-10 minutes of the CI job; the critical path is the single
slowest check, so a bounded pool collapses the whole phase.

Contract kept identical to the old serial scan.sh:
  * banner and summary box text,
  * per-check `━━━ [N/113] title ━━━` header lines,
  * exit 0 = all checks green, 1 = at least one red.

A block's verdict is its RC variable (the block's failure branch sets RC=1).
Because each block runs in its own shell, one failing check can no longer abort
the remaining ones — strictly more information than `set -e` serial execution.

Usage:
  python3 scripts/scan/scan_parallel.py [--root DIR] [--jobs N|auto]
                                        [--timings] [--block-timeout SEC]
  MINIS_SCAN_JOBS=1 forces serial (byte-comparable with the old scan.sh).
"""
from __future__ import annotations

import argparse
import os
import queue
import re
import shlex
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BLOCKS = os.path.join(HERE, "scan_blocks.sh")
HEADER_RE = re.compile(r'^echo "━━━ \[(\d+)/113\] (.*) ━━━"$', re.M)

BANNER = """╔══════════════════════════════════════════════════╗
║  RikkaMinis CI Scan Gate                        ║
╚══════════════════════════════════════════════════╝
"""


def parse_blocks(path=BLOCKS):
    text = open(path, encoding="utf-8").read()
    hits = [(int(m.group(1)), m.group(2), m.start()) for m in HEADER_RE.finditer(text)]
    if not hits:
        sys.exit(f"scan_parallel: no check blocks found in {path}")
    nums = [n for n, _, _ in hits]
    if nums != list(range(1, len(nums) + 1)):
        sys.exit(f"scan_parallel: block numbering is not 1..N contiguous: {nums[:5]}…{nums[-3:]}")
    blocks = []
    for i, (num, title, pos) in enumerate(hits):
        end = hits[i + 1][2] if i + 1 < len(hits) else len(text)
        blocks.append((num, title, text[pos:end]))
    return blocks


def run_block(root, num, title, snippet, block_timeout):
    """Execute one block; return (num, rc, output, seconds)."""
    script = "RC=0\n" + snippet + "\nexit ${RC:-0}\n"
    env = dict(os.environ)
    env["ROOT"] = root
    env["LC_ALL"] = env.get("LC_ALL", "C.UTF-8")
    started = time.time()
    try:
        p = subprocess.run(["sh", "-c", script], cwd=root, env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=block_timeout)
        out = p.stdout.decode("utf-8", "replace")
        rc = p.returncode
    except subprocess.TimeoutExpired as exc:
        out = (exc.output or b"").decode("utf-8", "replace")
        out += f"\n⏱ TIMEOUT after {block_timeout}s\n"
        rc = 124
    return num, rc, out, time.time() - started


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(HERE)))
    ap.add_argument("--jobs", default=os.environ.get("MINIS_SCAN_JOBS", "auto"))
    ap.add_argument("--timings", action="store_true", help="print a slowest-first timing table")
    ap.add_argument("--block-timeout", type=int, default=900)
    args = ap.parse_args(argv)

    root = os.path.abspath(args.root)
    if not os.path.isdir(os.path.join(root, "src", "android", "app", "src")):
        sys.exit(f"scan_parallel: {root} does not look like the repo root (fail closed)")

    jobs = args.jobs
    if jobs == "auto":
        jobs = max(1, min(8, os.cpu_count() or 4))
    else:
        jobs = max(1, int(jobs))

    blocks = parse_blocks()
    if len(blocks) != 113:
        print(f"note: {len(blocks)} check blocks (expected 113)")
    print(BANNER, flush=True)
    if jobs > 1:
        print(f"running {len(blocks)} checks with {jobs} workers", flush=True)
        print("", flush=True)

    titles = {num: title for num, title, _ in blocks}
    results = queue.Queue()

    def worker():
        while True:
            item = pending_q.get()
            if item is None:
                return
            num, title, snippet = item
            results.put(run_block(root, num, title, snippet, args.block_timeout))

    pending_q = queue.Queue()
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(jobs)]
    for t in threads:
        t.start()
    for b in blocks:
        pending_q.put(b)
    for _ in threads:
        pending_q.put(None)

    # Print in block order as results complete, so CI logs read exactly like the
    # serial transcript: [1/113] … [2/113] … no interleaving.
    buffered = {}
    next_num = blocks[0][0]
    passed = failed = 0
    failures = []
    timings = []
    for _ in range(len(blocks)):
        num, rc, out, secs = results.get()
        buffered[num] = (rc, out, secs)
        timings.append((secs, num, titles[num]))
        while next_num in buffered:
            rc, out, secs = buffered.pop(next_num)
            sys.stdout.write(out)
            sys.stdout.flush()
            if rc == 0:
                passed += 1
            else:
                failed += 1
                failures.append((next_num, titles[next_num], rc))
            next_num += 1

    print("", flush=True)
    print("╔══════════════════════════════════════════════════╗")
    print(f"║  Summary: {passed} passed, {failed} failed")
    print("╚══════════════════════════════════════════════════╝")
    if failures:
        print("", flush=True)
        print("FAILED CHECKS:", flush=True)
        for num, title, rc in failures:
            print(f"  [{num}/113] {title} (rc={rc})", flush=True)
    if args.timings:
        print("", flush=True)
        print("SLOWEST CHECKS:", flush=True)
        for secs, num, title in sorted(timings, reverse=True)[:12]:
            print(f"  {secs:6.1f}s  [{num}/113] {title}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
