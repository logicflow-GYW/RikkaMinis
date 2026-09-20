#!/usr/bin/env python3
"""Stream-flow dispatch guard — RikkaMinis CI scan gate.

## Why this gate exists

2026-09-20, for real: the user reported "the LLM call hangs, nothing comes out
for a very long time". A 1.6 MB / 348-message request sat for 272s with zero
bytes back.

The mechanism is a coroutine/Flow footgun with three parts:

  1. `callbackFlow { ... }` runs its body in the COLLECTOR's context. There is
     no dispatcher of its own unless the chain carries `flowOn`.
  2. The provider stream bodies are SYNCHRONOUS: they block inside
     `call.execute()` and then inside `reader.readLine()` for the whole stream.
  3. Every timer scheduled on that same dispatcher therefore starves —
     including the flow's OWN watchdogs (`launch { delay(TTFB) ... }`,
     `launch { delay(firstData) ... }`).

That was invisible for years because the only collector was a thread pool (the
pre-offload main process ran `launch(Dispatchers.IO)`). `01cfcc0e`
(2026-08-22, TF-D) forced every LLM call through the `:modelservice` worker,
whose collector is:

    runBlocking { withTimeoutOrNull(firstChunkTimeoutMs) { ... .collect {} } }

`runBlocking` has NO dispatcher — its event loop IS the calling thread. The
blocked producer then owns that single thread, so:

  * the in-flow TTFB watchdog (90s) never fires,
  * the in-flow first-data watchdog (30 min) never fires,
  * the worker's outer `withTimeoutOrNull` (30 min) never fires either.

Three independent guards, all silently unreachable, from one scheduling
mistake. The provider code itself never changed.

**No test can catch this.** A test would have to sit and wait 30 real minutes
for a watchdog that is never going to fire. It has to be a static judgement.

## What this gate flags

A cold-flow producer (`callbackFlow` / `channelFlow`) whose body contains a
blocking call and whose chain has NO `flowOn` — i.e. the body is left on the
collector's dispatcher. `flowOn(Dispatchers.Main)` counts as a violation too:
it moves the blocking to the UI thread instead of off it.

Escape hatch: `stream-ok: <reason>` on the flow line, on one of the two lines
above it, or anywhere inside the body.

Scope: production sources only (`src/android/app/src/main/java`). A blocking
cold flow in test code is not a shipping hazard.

Usage: python3 scripts/scan/stream_flow_dispatch_guard.py <repo_root>
Exit:  0 = clean, 1 = violation(s) found
"""
import os
import re
import sys

SRC_ROOTS = [
    "src/android/app/src/main/java",
]

# Cold-flow builders whose body runs on the COLLECTOR's context unless the
# chain carries a flowOn. Generics are allowed to nest one level.
FLOW_RE = re.compile(r"\b(callbackFlow|channelFlow)\b\s*(?:<[^;{}]{0,200}?>\s*)?\{")

# Synchronous calls that occupy the thread for an unbounded / long time.
# Deliberately narrow: only constructs that really park the thread. `.await()`
# is NOT here — a coroutine `deferred.await()` is a suspension, not a block.
BLOCKING_PATTERNS = [
    (r"\.execute\s*\(\s*\)", "call.execute() (synchronous network I/O)"),
    (r"\.readLine\s*\(\s*\)", "readLine() (blocking stream read)"),
    (r"\breadUtf8Line\b", "readUtf8Line() (blocking stream read)"),
    (r"Thread\s*\.\s*sleep\s*\(", "Thread.sleep() (parks the thread)"),
    (r"\brunBlocking\s*[\({]", "runBlocking {} (parks the thread)"),
    (r"\.blockingRead\b", "blockingRead()"),
]

ESCAPE_RE = re.compile(r"stream-ok\s*:")


def mask_non_code(text):
    """Blank comments AND string/char literals, preserving offsets + newlines.

    Both halves matter, and each was a real bug in the first version of this
    gate:

      * a LINE comment containing a brace (`// runBlocking { ... }`) leaves the
        brace counter unbalanced, so `match_brace` runs past the real closing
        brace and swallows unrelated code further down the file;
      * `"https://..."` contains `//`, so a naive comment stripper would eat
        the rest of the line in files that are full of URLs.

    Newlines are preserved so line numbers computed against the masked text
    still match the original file.
    """
    out = list(text)
    n = len(text)
    i = 0

    def blank(a, b):
        for k in range(a, min(b, n)):
            if text[k] != "\n":
                out[k] = " "

    while i < n:
        c = text[i]
        # line comment
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
            continue
        # block comment
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
            continue
        # string literal (raw """ or normal)
        if c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n:
                    if text[j] == "\\":
                        j += 2
                        continue
                    if text[j] == '"' or text[j] == "\n":
                        j += 1
                        break
                    j += 1
            blank(i, j)
            i = j
            continue
        # char literal
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    j += 1
                    break
                j += 1
            blank(i, j)
            i = j
            continue
        i += 1
    return "".join(out)


def match_brace(text, open_idx):
    """Index of the '}' matching the '{' at open_idx (-1 if unbalanced).

    Expects comment/string masking to have run first.
    """
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def match_paren(text, open_idx):
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def chain_dispatch(text, close_idx):
    """Walk the operator chain after a flow builder's closing brace.

    Returns "ok"    -> a flowOn with a non-Main dispatcher was found
            "main"  -> flowOn(Dispatchers.Main...) found (still a hazard)
            None    -> no flowOn on the chain
    """
    i = close_idx + 1
    n = len(text)
    while True:
        while i < n and text[i] in " \t\r\n":
            i += 1
        if i >= n or text[i] != ".":
            return None
        m = re.match(r"\.\s*([A-Za-z_]\w*)", text[i:])
        if not m:
            return None
        name = m.group(1)
        i += m.end()
        while i < n and text[i] in " \t\r\n":
            i += 1
        arg = ""
        if i < n and text[i] == "(":
            j = match_paren(text, i)
            if j < 0:
                return None
            arg = text[i:j + 1]
            i = j + 1
        if name == "flowOn":
            return "main" if re.search(r"\bMain\b", arg) else "ok"


def scan_file(path):
    raw = open(path, encoding="utf-8", errors="replace").read()
    text = mask_non_code(raw)
    raw_lines = raw.split("\n")
    findings = []

    for m in FLOW_RE.finditer(text):
        open_idx = text.index("{", m.end() - 1)
        close_idx = match_brace(text, open_idx)
        if close_idx < 0:
            continue
        body = text[open_idx:close_idx + 1]

        hits = []
        for pat, why in BLOCKING_PATTERNS:
            for hm in re.finditer(pat, body):
                hits.append((text.count("\n", 0, open_idx + hm.start()) + 1, why))
        if not hits:
            continue

        disp = chain_dispatch(text, close_idx)
        if disp == "ok":
            continue

        start_line = text.count("\n", 0, m.start()) + 1
        end_line = text.count("\n", 0, close_idx) + 1
        # Escape hatch: the flow line, the two lines above it, or the body.
        exempt = False
        for k in range(max(0, start_line - 3), min(len(raw_lines), end_line)):
            if ESCAPE_RE.search(raw_lines[k]):
                exempt = True
                break
        if exempt:
            continue

        for ln, why in sorted(set(hits)):
            findings.append((start_line, ln, why, disp))
    return findings


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    violations = []
    for sr in SRC_ROOTS:
        src = os.path.join(root, sr)
        if not os.path.isdir(src):
            continue
        for base, _dirs, files in os.walk(src):
            for fn in sorted(files):
                if not fn.endswith(".kt"):
                    continue
                path = os.path.join(base, fn)
                rel = os.path.relpath(path, src).replace(os.sep, "/")
                for start_line, hit_line, why, disp in scan_file(path):
                    tag = "flowOn(Dispatchers.Main)" if disp == "main" else "no flowOn"
                    violations.append(
                        f"{rel}:{start_line}  (blocking at :{hit_line})  {why}  [{tag}]")

    if violations:
        print(f"❌ Stream-flow dispatch guard: {len(violations)} violation(s)")
        print("   A callbackFlow/channelFlow body blocks the thread, but its chain does")
        print("   not hand the producer off to a background dispatcher. The producer")
        print("   therefore runs on the COLLECTOR's dispatcher and starves every timer")
        print("   on it — including the flow's own watchdogs. The :modelservice")
        print("   collector is `runBlocking` (single thread), so those guards become")
        print("   unreachable and the user sees an indefinite hang.")
        print("   Fix: append `.flowOn(Dispatchers.IO)` to the flow chain.")
        print("   Or justify inline with `stream-ok: <reason>`.")
        for v in violations:
            print("     " + v)
        return 1
    print("✅ Stream-flow dispatch guard: every blocking cold-flow body is dispatched off the collector")
    return 0


if __name__ == "__main__":
    sys.exit(main())
