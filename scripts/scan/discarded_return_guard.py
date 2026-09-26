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

A statement-position call to a function in [WATCHLIST] — the call starts the line
after indentation, optionally through a receiver (`x.`, `x?.`) or a qualified
path. Skipped (they consume the value or are not calls):

  * declarations (`fun recordFirstToken(...)`) and comment lines;
  * assignments / returns / argument positions (the pattern does not match);
  * a call that is the body of a value-consuming lambda — `let`, `run`, `with`,
    `map`, `mapNotNull`, `flatMap`, `fold` — **but only when that lambda's own
    value is used** (assigned, returned, or in argument position). `also` /
    `apply` / `forEach` are never in that list: they return the receiver (or
    Unit), so a value dropped inside them is still dropped. A statement-position
    `x?.let { recordFirstToken(…) }` is flagged: the `let`'s value is dropped,
    which is exactly the shape that shipped (an earlier revision of this gate
    exempted it and could not see its own counter-example — the reverse-control
    run against the pre-fix source is what caught that).

Exempt a line inline with `record-ok: <reason>` (the repo's `debug-ok:`
convention). Adding a watchlist entry is the maintenance path when another
value-returning recorder is discovered.

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
_DECL = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public |private |internal |protected |override |suspend "
    r"|inline |operator |abstract |open )*fun\s"
)
_COMMENT = re.compile(r"^\s*(?://|\*|/\*)")
_CONSUMING_LAMBDA = re.compile(
    r"\b(?:let|run|with|map|mapNotNull|flatMap|fold|sumOf)\s*\{\s*$"
)


def _pattern(name):
    return re.compile(_CALL.pattern % re.escape(name))


def _enclosing_open(lines, i):
    """Innermost enclosing block opener above line i (nearest `... {` line)."""
    for j in range(i - 1, -1, -1):
        stripped = lines[j].strip()
        if not stripped or stripped.startswith(("//", "*", "/*")):
            continue
        if stripped.endswith("{"):
            return j, lines[j]
        return -1, ""
    return -1, ""


def _consumes_lambda_value(open_line):
    """True only when the lambda's own value is used.

    `x?.let { … }` written as a STATEMENT drops the lambda's value, so a
    watchlisted call inside it is dropped too — that is the exact shape of the
    2026-09-26 defect, and an earlier version of this gate exempted it and thus
    could not see its own counter-example. A consuming lambda only counts when
    its open line sits in expression position: assigned, returned, or inside an
    argument list.
    """
    if not _CONSUMING_LAMBDA.search(open_line):
        return False
    head = open_line.split("//")[0]
    if "=" in head:
        return True
    if head.strip().startswith("return "):
        return True
    return head.count("(") > head.count(")")


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
                if _DECL.match(line) or _COMMENT.match(line):
                    continue
                _oi, _oline = _enclosing_open(lines, i)
                if _consumes_lambda_value(_oline):
                    continue
                for fn, why in WATCHLIST.items():
                    if not _pattern(fn).match(line):
                        continue
                    if EXEMPT in line or (i > 0 and EXEMPT in lines[i - 1]):
                        continue
                    violations.append((rel, i + 1, line.strip()[:80], fn, why))
    return violations


FIXTURE = "src/android/app/src/main/java/com/rikkaminis/app/network/Trace.kt"
_Q = "com.rikkaminis.app.diagnostics.ProviderHealthTracker"
FIXTURE_LINES = [
    # 1 caught — the defect that shipped: bare statement call
    "            %s.recordFirstToken(it, ms())" % _Q,
    # 2 pass — consumed by assignment
    "            val updated = %s.recordFirstToken(it, ms())" % _Q,
    # 3 pass — consumed by return
    "            return %s.recordFirstToken(it, ms())" % _Q,
    # 4 caught — safe-call receiver, still dropped
    "        tracker?.recordFirstToken(it, ms())",
    # 5 pass — inline exemption
    "        tracker.recordFirstToken(it, ms()) // record-ok: fixture, unused on purpose",
    # 6 pass — argument position
    "        println(providerHealthTracker.recordFirstToken(it, ms()))",
    # 7 pass — declaration, not a call (false positive seen on the merged tree)
    "    fun recordFirstToken(attempt: Attempt, ttfbMs: Long): Attempt =",
    # 8 pass — body of a consuming lambda (false positive seen on the merged tree)
    "            attempt = attempt?.let {",
    "                %s.recordFirstToken(it, ms())" % _Q,
    "            }",
    # 9 caught — `also` returns the receiver, so the value is still dropped
    "            attempt?.also {",
    "                %s.recordFirstToken(it, ms())" % _Q,
    "            }",
    # 10 caught — the shape that actually shipped: a statement-position `let`,
    #             whose own value is dropped, so the call inside is dropped too
    "        attempt?.let {",
    "            %s.recordFirstToken(it, ms())" % _Q,
    "        }",
    # 11 pass — lambda in argument position: the value flows into the call
    "        consume(list.map {",
    "            %s.recordFirstToken(it, ms())" % _Q,
    "        })",
]
EXPECTED_HITS = {1, 4, 12, 15}


def self_test():
    tmp = tempfile.mkdtemp(prefix="retguard-")
    path = os.path.join(tmp, FIXTURE)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(FIXTURE_LINES) + "\n")
    got = {line for _rel, line, _t, _f, _w in scan(tmp)}
    if got != EXPECTED_HITS:
        print("SELF-TEST FAILED")
        print("  expected:", sorted(EXPECTED_HITS))
        print("  got     :", sorted(got))
        return 1
    print(
        "✅ self-test: catches the dropped recordFirstToken (bare + safe-call + "
        "inside `also`), passes assigned / returned / argument / declaration / "
        "consuming-lambda / exempt forms"
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
