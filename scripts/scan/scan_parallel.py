#!/usr/bin/env python3
"""Parallel driver for the static CI checks.

The checks themselves live in `scripts/scan/scan_blocks.sh` (a data file, one
block per check). This driver splits that file on the `echo "━━━ [N/D] … ━━━"`
header lines, runs every block in its own `sh -c` (cwd = repo root, ROOT
exported), and prints the block output in the original 1..N order.

Why a pool: the checks are independent single-file/small-tree analyses (each a
python process, a few seconds of wall clock, mostly I/O + tree walks). Run
serially they cost ~9-10 minutes of the CI job; the critical path is the single
slowest check, so a bounded pool collapses the whole phase.

Contract kept identical to the old serial scan.sh:
  * banner and summary box text,
  * per-check `━━━ [N/D] title ━━━` header lines,
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

# The denominator in the block headers is NOT matched literally.
#
# [gateway-context-1112] It used to be: `\[(\d+)/114\]`. Every time a check
# was added, the denominator had to be bumped in FOUR places — the header
# lines in scan_blocks.sh, this regex, the `expected N` guard below, and the
# two print formats — and a miss was SILENT until CI ran the driver: the
# driver then found no blocks at all ("no check blocks found") and the whole
# gate went red for a reason that had nothing to do with the change under
# test (this exact failure: the denominator was bumped in the headers but
# not in this regex, so the gate read zero blocks).
#
# So the denominator is now DERIVED: the header regex accepts any
# denominator, and the count of contiguous blocks IS the denominator. Adding
# a check means appending one block — nothing else to keep in sync.
HEADER_RE = re.compile(r'^echo "━━━ \[(\d+)/(\d+)\] (.*) ━━━"$', re.M)

BANNER = """╔══════════════════════════════════════════════════╗
║  RikkaMinis CI Scan Gate                        ║
╚══════════════════════════════════════════════════╝
"""


def parse_blocks(path=BLOCKS):
    text = open(path, encoding="utf-8").read()
    hits = [(int(m.group(1)), int(m.group(2)), m.group(3), m.start())
            for m in HEADER_RE.finditer(text)]
    if not hits:
        sys.exit(f"scan_parallel: no check blocks found in {path}")
    nums = [n for n, _, _, _ in hits]
    if nums != list(range(1, len(nums) + 1)):
        sys.exit(f"scan_parallel: block numbering is not 1..N contiguous: {nums[:5]}…{nums[-3:]}")
    # [gateway-context-1112] The DENOMINATOR written in the headers must equal
    # the number of blocks.
    #
    # This is the check that would have caught the failure this branch
    # actually hit: the denominator was bumped in the 115 header lines but
    # missed in HEADER_RE, so the driver matched ZERO blocks and the whole
    # gate went red with "no check blocks found" — a failure that pointed
    # nowhere near the cause. The denominator is now ignored by the parser
    # (it is derived), so this assertion is the only thing left that keeps
    # the human-readable headers honest — and it fails LOUDLY and SPECIFICALLY
    # instead of silently.
    denominators = sorted({d for _, d, _, _ in hits})
    if denominators != [len(nums)]:
        sys.exit(
            "scan_parallel: the header denominator does not match the block count "
            f"({len(nums)} blocks, denominators seen: {denominators}) — "
            f"update the `[N/{denominators[0] if denominators else '?'}]` headers in "
            f"{path} to /{len(nums)}"
        )
    blocks = []
    for i, (num, _den, title, pos) in enumerate(hits):
        end = hits[i + 1][3] if i + 1 < len(hits) else len(text)
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
    # [gateway-context-1112] The denominator is DERIVED from the blocks (see
    # HEADER_RE) — there is no hard-coded count to fall out of sync with the
    # header lines. The count guard stays, but it compares the block numbers
    # against the count of blocks (parse_blocks already enforces 1..N
    # contiguity), so it can only fire if the file itself is malformed.
    total = len(blocks)
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
    # serial transcript: [1/N] … [2/N] … no interleaving.
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
            print(f"  [{num}/{total}] {title} (rc={rc})", flush=True)
    if args.timings:
        print("", flush=True)
        print("SLOWEST CHECKS:", flush=True)
        for secs, num, title in sorted(timings, reverse=True)[:12]:
            print(f"  {secs:6.1f}s  [{num}/{total}] {title}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
