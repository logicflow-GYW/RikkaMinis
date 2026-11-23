#!/usr/bin/env python3
"""Per-item credential re-read guard — RikkaMinis CI scan gate.

## Why this gate exists

`EnvVarRedactor.redactForDiagnostics` / `redactForModel` do NOT just run string
rules: each call re-reads every environment variable (`allAsDict()` decrypts
each value out of EncryptedSharedPreferences with AES-GCM) and re-reads every
provider API key. That is correct for a single tool result and catastrophic
inside a loop.

2026-11-23 (release 1.0.0+3485, user report): opening an existing conversation
took about one second. Device log for a 266-row session:

    loadMessages          count=266  tookMs=14     <- the database read
    toChatMessages                   tookMs=53     <- the UI transform
    agentHistory rebuilt             tookMs=935..1375   <- the one-second stall
    loadSession EXIT                 totalMs=1029

`agentHistory rebuilt` was `redactToolHistoryForModel(...)` calling the
single-shot redactor once per content part, so the credential decrypt cost was
multiplied by the part count. Pure rule matching for that same session measures
~30 ms (probe/perf/LoadRedactionCost.kt), so ~97% of the stall was credential
re-reading, not redaction logic.

## What it flags

A SINGLE-SHOT redaction call inside a loop over items (for/while/forEach/map/
flatMap/filter/any/all/none/fold), in the credential-bearing subtrees. Those
must hoist `EnvVarRedactor.snapshot()` out of the loop and call
`redactWithSnapshot(...)` (or `redactForModel(text, snapshot)`) instead.

## Escape hatch

`snapshot-ok: <reason>` on the line above the call, for a loop that provably
runs once or whose per-item call is intentional.

Usage:
  python3 scripts/scan/redaction_loop_snapshot_check.py <repo_root>
  python3 scripts/scan/redaction_loop_snapshot_check.py --selftest
Exit: 0 = no per-item credential re-read, 1 = violation (file:line printed)
"""

import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_lexer import KotlinLexError, mask_code   # noqa: E402

SRC_SUBDIR = "src/android/app/src/main/java"

# Subtrees where bulk redaction actually happens.
SCAN_DIRS = (
    "com/rikkaminis/app/ui/chat",
    "com/rikkaminis/app/conversation",
    "com/rikkaminis/app/tools",
    "com/rikkaminis/app/provider",
    "com/rikkaminis/app/data",
)

# The single-shot forms. The snapshot form is `redactWithSnapshot(` /
# `redactForModel(text, snapshot)` — deliberately not matched here.
# `redactForModel(text, snapshot)` already uses a snapshot; only the one-arg
# form re-reads. Matched by requiring the argument list to end right after the
# first expression (no top-level comma).
SINGLE_SHOT = re.compile(r"\bEnvVarRedactor\.(?:redactForDiagnostics|redactForModel)\s*\(")
WITH_SNAPSHOT = re.compile(r"\bEnvVarRedactor\.redactForModel\s*\([^()]*(?:snapshot|snap)\s*\)")

# A loop header or an iterable combinator. `map`/`forEach` etc. are where the
# multiplication actually happened (one call per element).
LOOP_HEAD = re.compile(r"\b(?:for|while)\s*\(")
ITER_COMBINATOR = re.compile(
    r"\.(?:forEach|map|flatMap|filter|any|all|none|fold|onEach|mapNotNull)\s*[({]"
)

EXEMPT = "snapshot-ok:"

# One logical line, tolerating a trailing `//` comment.
LINE_COMMENT = re.compile(r"//[^\n]*")


def _exempted(lines, idx):
    """Marker on this line or in the contiguous comment block above it."""
    if 0 <= idx < len(lines) and EXEMPT in lines[idx]:
        return True
    j = idx - 1
    while j >= 0:
        text = lines[j].strip()
        if not (text.startswith("//") or text.startswith("/*") or text.startswith("*")):
            break
        if EXEMPT in lines[j]:
            return True
        j -= 1
    return False


def scan_text(src):
    """Return [(line, snippet)] for single-shot redaction inside a loop body."""
    try:
        masked = mask_code(src)
    except KotlinLexError:
        # Fail closed: an unreadable file is reported, never skipped.
        return [(1, "<unreadable: lexer error — cannot prove loops hoist the snapshot>")]

    lines = masked.split("\n")
    raw_lines = src.split("\n")
    out = []

    # Track the brace depth at which each loop body starts, so a redaction call
    # anywhere inside that body is attributed to the loop. A redaction BEFORE
    # the loop (the fix) has a depth <= the loop's opening depth.
    depth = 0
    loop_depths = []      # stack of body depths for open loops
    pending_loop = False

    for n, line in enumerate(lines):
        code = LINE_COMMENT.sub("", line)

        opens = code.count("{")
        closes = code.count("}")

        # Does this line open a loop?
        if LOOP_HEAD.search(code) or ITER_COMBINATOR.search(code):
            pending_loop = True

        if pending_loop and opens > 0:
            loop_depths.append(depth + 1)
            pending_loop = False

        # A single-shot call while any loop body is still open. A call that
        # already receives a snapshot is the FIX, not the bug.
        if SINGLE_SHOT.search(code) and not WITH_SNAPSHOT.search(code):
            inside = [d for d in loop_depths if depth >= d]
            if inside and not _exempted(raw_lines, n):
                snippet = " ".join(raw_lines[n].strip().split())[:120]
                out.append((n + 1, snippet))

        depth += opens - closes
        # Pop loops whose body has closed.
        loop_depths = [d for d in loop_depths if depth >= d]
        if depth < 0:
            depth = 0

    return out


def _iter_sources(root):
    base = os.path.join(root, SRC_SUBDIR)
    for d in SCAN_DIRS:
        walk = os.path.join(base, d)
        if not os.path.isdir(walk):
            continue
        for dirpath, _dirs, files in os.walk(walk):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, f)
                yield path, os.path.relpath(path, root)


def run(root):
    findings = 0
    for path, rel in _iter_sources(root):
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
        except OSError as exc:
            print("UNREADABLE %s (%s)" % (rel, exc))
            findings += 1
            continue
        for line, snippet in scan_text(src):
            print("%s:%d  per-item credential re-read inside a loop: %s" % (rel, line, snippet))
            findings += 1
    if findings:
        print("")
        print("Each redactForDiagnostics/redactForModel call DECRYPTS every credential.")
        print("Hoist it: val snap = EnvVarRedactor.snapshot()  then  redactWithSnapshot(text, snap, null)")
        print("If the loop provably runs once, add `%s <reason>` above the call." % EXEMPT)
        return 1
    print("OK: no per-item credential re-read inside a loop")
    return 0


DIRTY_LOOP = '''
class Fake {
    fun f(parts: List<String>) {
        for (p in parts) {
            val r = EnvVarRedactor.redactForDiagnostics(p)
        }
    }
}
'''

DIRTY_FOREACH = '''
class Fake2 {
    fun f(msgs: List<Msg>) {
        val out = msgs.map { m ->
            EnvVarRedactor.redactForModel(m.text)
        }
    }
}
'''

CLEAN_HOISTED = '''
class Fake3 {
    fun f(parts: List<String>) {
        val snap = EnvVarRedactor.snapshot()
        for (p in parts) {
            val r = EnvVarRedactor.redactWithSnapshot(p, snap, null)
        }
    }
}
'''

CLEAN_SINGLE = '''
class Fake4 {
    fun f(text: String) {
        val r = EnvVarRedactor.redactForDiagnostics(text)
    }
}
'''

EXEMPTED_LOOP = '''
class Fake5 {
    fun f(parts: List<String>) {
        for (p in parts) {
            // snapshot-ok: this loop provably runs once (single-element list)
            val r = EnvVarRedactor.redactForDiagnostics(p)
        }
    }
}
'''


def selftest():
    failures = 0

    def check(name, cond, detail=""):
        nonlocal failures
        if cond:
            print("  PASS %s" % name)
        else:
            print("  FAIL %s %s" % (name, detail))
            failures += 1

    check("dirty for-loop is caught", len(scan_text(DIRTY_LOOP)) == 1,
          "got %r" % (scan_text(DIRTY_LOOP),))
    check("dirty forEach/map is caught", len(scan_text(DIRTY_FOREACH)) == 1,
          "got %r" % (scan_text(DIRTY_FOREACH),))
    check("hoisted snapshot passes", scan_text(CLEAN_HOISTED) == [],
          "got %r" % (scan_text(CLEAN_HOISTED),))
    check("single (non-loop) call passes", scan_text(CLEAN_SINGLE) == [],
          "got %r" % (scan_text(CLEAN_SINGLE),))
    check("inline exemption is recognised", scan_text(EXEMPTED_LOOP) == [],
          "got %r" % (scan_text(EXEMPTED_LOOP),))

    # Negative control: hoisting the snapshot MUST make the dirty fixture clean.
    fixed = DIRTY_LOOP.replace(
        "for (p in parts) {\n            val r = EnvVarRedactor.redactForDiagnostics(p)",
        "val snap = EnvVarRedactor.snapshot()\n        for (p in parts) {\n            val r = EnvVarRedactor.redactWithSnapshot(p, snap, null)")
    check("dirty fixture becomes clean once hoisted", scan_text(fixed) == [],
          "got %r" % (scan_text(fixed),))

    if failures:
        print("SELFTEST=FAIL (%d)" % failures)
        return 1
    print("SELFTEST=PASS")
    return 0


def main(argv):
    args = argv[1:]
    if "--selftest" in args:
        return selftest()
    root = args[0] if args else os.getcwd()
    return run(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
