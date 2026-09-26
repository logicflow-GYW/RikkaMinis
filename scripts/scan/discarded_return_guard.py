#!/usr/bin/env python3
"""Discarded-return guard — RikkaMinis CI scan gate.

## Why this gate exists

Some APIs in this repo are immutable-by-copy: the call returns a NEW object and
the receiver is untouched (ProviderHealthTracker.recordFirstToken is the one that
already bit). A call written in STATEMENT position therefore compiles, returns a
value nobody reads, and silently does nothing — the observer runs, the probe
fires, the log line looks right, and the state never changes.

2026-09-26: `ProviderHealthTraceListener.responseHeadersStart` called
`ProviderHealthTracker.recordFirstToken(it, ms())` as a statement. TTFB was
never recorded, so `budget(realHistory, routeDefault = 30s)` stayed at the
default forever and every route was judged against 30 s. Every gate was green:
it compiled, the four-way sync was complete, the i18n keys matched. A probe
comparing "attempts=1 p50=null recordedTtfbs=[]" against the control run
"recordedTtfbs=[8000]" is what proved it — and no static gate asks that question.

## What it flags

A statement-position call to a function in [WATCHLIST] — i.e. the call starts the
line (after indentation), optionally through a receiver (`x.`, `x?.`) or a
fully-qualified path. Assignments (`val updated = ...`), returns, argument
positions and one-line lambdas are all fine.

Watchlist entries carry the reason the value must be consumed. Exempt a line
inline with `record-ok: <reason>` (the repo's `debug-ok:` convention). Adding an
entry is the point: when a function is discovered to return state-bearing copies,
it belongs here so the next caller cannot quietly drop it.

Usage:
  python3 scripts/scan/discarded_return_guard.py <repo_root>
  python3 scripts/scan/discarded_return_guard.py --self-test
"""

import os
import re
import sys
import tempfile

SRC_SUBDIR = "src/android/app/src/main/java"

# name -> why the returned value must be consumed
WATCHLIST = {
    "recordFirstToken": (
        "returns a COPY carrying the TTFB; the tracker is immutable-by-copy, so a "
        "statement-position call records nothing (2026-09-26 TTFB wire-up defect)"
    ),
}

EXEMPT = "record-ok:"
_CALL = re.compile(
    r"^\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*\??\.?\s*%s\s*\("
)


def _pattern(name):
    return re.compile(_CALL.pattern % re.escape(name))


def scan(root):
    violations = []
    base = os.path.join(root, SRC_SUBDIR)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8", errors="replace") as fh:
                lines = fh.read().split("\n")
            for i, line in enumerate(lines):
                for fn, why in WATCHLIST.items():
                    if not _pattern(fn).match(line):
                        continue
                    if EXEMPT in line or (i > 0 and EXEMPT in lines[i - 1]):
                        continue
                    violations.append((rel, i + 1, line.strip()[:80], fn, why))
    return violations


def self_test():
    fixture = "src/android/app/src/main/java/com/rikkaminis/app/network/Trace.kt"
    lines = [
        # 1 statement position: the defect — must be caught
        "            com.rikkaminis.app.diagnostics.ProviderHealthTracker.recordFirstToken(it, ms())",
        # 2 consumed: must pass
        "            val updated = com.rikkaminis.app.diagnostics.ProviderHealthTracker.recordFirstToken(it, ms())",
        # 3 returned: must pass
        "            return com.rikkaminis.app.diagnostics.ProviderHealthTracker.recordFirstToken(it, ms())",
        # 4 safe-call receiver, still dropped: must be caught
        "        tracker?.recordFirstToken(it, ms())",
        # 5 inline exemption: must pass
        "        tracker.recordFirstToken(it, ms()) // record-ok: probe fixture, result unused on purpose",
        # 6 argument position: must pass
        "        println(providerHealthTracker.recordFirstToken(it, ms()))",
    ]
    tmp = tempfile.mkdtemp(prefix="retguard-")
    path = os.path.join(tmp, fixture)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    got = {(rel, line) for rel, line, _t, _f, _w in scan(tmp)}
    want = {(fixture, 1), (fixture, 4)}
    if got != want:
        print("SELF-TEST FAILED")
        print("  expected:", sorted(want))
        print("  got     :", sorted(got))
        return 1
    print(
        "✅ self-test: catches the dropped recordFirstToken (plain + safe-call), "
        "passes assigned / returned / argument / exempt forms"
    )
    return 0


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--self-test":
        return self_test()
    violations = scan(args[0])
    if violations:
        print("❌ 丢弃了必须消费的返回值（看似接线，实则没生效）：")
        for rel, line, snippet, fn, why in violations:
            print("  %s:%d" % (rel, line))
            print("      %s" % snippet)
            print("      %s: %s" % (fn, why))
            print("      修法: 把返回值接下去（val x = ... 后写入/追加），或在行内加 `record-ok: <理由>`。")
        print("")
        print("  共 %d 处。" % len(violations))
        return 1
    print(
        "✅ discarded-return guard: no watchlisted return value is dropped "
        "(%d watched function(s))" % len(WATCHLIST)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
