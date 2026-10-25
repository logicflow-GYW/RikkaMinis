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
#
# [wide3-04] TWO syntactic forms, both legal Kotlin and both meaning the same
# thing: the trailing lambda (`callbackFlow { ... }`) and the parenthesised
# argument (`callbackFlow({ ... })`). The first version of this gate only knew
# the trailing form, so `callbackFlow({ ... })` was invisible to the whole
# judgement — the flow was never located, the missing `flowOn` never reported.
FLOW_BRACE_RE = re.compile(
    r"\b(callbackFlow|channelFlow)\b\s*(?:<[^;{}]{0,200}?>\s*)?\{")
FLOW_PAREN_RE = re.compile(
    r"\b(callbackFlow|channelFlow)\b\s*(?:<[^;{}]{0,200}?>\s*)?\(\s*\{")

# [wide3-04] Allowlist, not blacklist. `flowOn` used to be judged by
# "does the argument contain the word Main" — anything else counted as safe, so
# `Dispatchers.Unconfined` (which runs the blocking body on the COLLECTOR's
# thread until the first suspension) and `EmptyCoroutineContext` passed. Only
# dispatchers that really hand the producer to another thread may pass.
SAFE_DISPATCHERS = ("IO", "Default")

# Calls that can move execution to another dispatcher *inside* the flow body.
# A correct chain-level `flowOn` says nothing about what the body does
# afterwards: `withContext(Dispatchers.Main) { call.execute() }` inside the
# body runs the blocking call on the UI thread while the chain still looks
# properly dispatched.
DISPATCH_SWITCH_RE = re.compile(
    r"\b(withContext|launch|async|flowOn|runBlocking)\s*\(")

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


def dispatcher_verdict(arg):
    """Classify a dispatcher expression (the text inside `flowOn(...)`).

    Returns ("ok", name) only for an allowlisted background dispatcher;
    ("main", name) for the UI thread; ("unsafe", name) for everything else —
    including expressions this gate cannot read, which fail CLOSED.
    """
    inner = arg.strip()
    if inner.startswith("(") and inner.endswith(")"):
        inner = inner[1:-1]
    if re.search(r"\bEmptyCoroutineContext\b|\bnewSingleThreadContext\b", inner):
        return "unsafe", inner.strip()
    names = re.findall(r"\bDispatchers?\s*\.\s*([A-Za-z_]\w*)", inner)
    if not names:
        return "unsafe", (inner.strip() or "<empty>")
    for n in names:
        if n not in SAFE_DISPATCHERS:
            return ("main" if n == "Main" else "unsafe"), n
    return "ok", names[0]


def chain_dispatch(text, close_idx):
    """Walk the operator chain after a flow builder's closing brace.

    Returns ("ok", name)     -> allowlisted background dispatcher
            ("main", name)   -> Dispatchers.Main (still a hazard)
            ("unsafe", expr) -> any other dispatcher (Unconfined, custom, …)
            (None, None)     -> no flowOn on the chain
    """
    i = close_idx + 1
    n = len(text)
    while True:
        while i < n and text[i] in " \t\r\n":
            i += 1
        if i >= n or text[i] != ".":
            return None, None
        m = re.match(r"\.\s*([A-Za-z_]\w*)", text[i:])
        if not m:
            return None, None
        name = m.group(1)
        i += m.end()
        while i < n and text[i] in " \t\r\n":
            i += 1
        arg = ""
        if i < n and text[i] == "(":
            j = match_paren(text, i)
            if j < 0:
                return None, None
            arg = text[i:j + 1]
            i = j + 1
        if name == "flowOn":
            return dispatcher_verdict(arg)


def body_dispatch_hazards(body):
    """Blocking work moved onto a non-background dispatcher INSIDE the body.

    Returns [(dispatcher_name, verdict, why)] — empty when the body keeps every
    blocking call on the dispatcher the chain handed it.
    """
    out = []
    for m in DISPATCH_SWITCH_RE.finditer(body):
        po = body.find("(", m.end() - 1)
        pc = match_paren(body, po) if po >= 0 else -1
        if pc < 0:
            continue
        args = body[po + 1:pc]
        if "Dispatcher" not in args and "CoroutineContext" not in args:
            continue
        verdict, name = dispatcher_verdict(args)
        if verdict == "ok":
            continue
        # The blocking call may live in the arguments themselves (a passed
        # lambda) or in the trailing lambda block.
        region = args
        j = pc + 1
        while j < len(body) and body[j] in " \t\r\n":
            j += 1
        if j < len(body) and body[j] == "{":
            be = match_brace(body, j)
            if be > 0:
                region += body[j:be + 1]
        for pat, why in BLOCKING_PATTERNS:
            if re.search(pat, region):
                out.append((name, verdict, why))
                break
    return out


def flow_sites(text):
    """[(builder, body_open, chain_anchor, problem)] for every cold-flow site.

    `chain_anchor` is the index after which the operator chain starts: the body's
    closing brace for the trailing-lambda form, the CALL's closing paren for the
    `callbackFlow({ … })` form. `problem` is non-None when the site cannot be
    parsed — such a site is reported instead of skipped, because an unparseable
    flow is exactly what a regression looks like.
    """
    sites = []
    seen = set()
    for rx, paren_form in ((FLOW_PAREN_RE, True), (FLOW_BRACE_RE, False)):
        for m in rx.finditer(text):
            open_idx = text.index("{", m.end() - 1)
            if open_idx in seen:
                continue
            seen.add(open_idx)
            close_idx = match_brace(text, open_idx)
            if close_idx < 0:
                sites.append((m.group(1), open_idx, -1,
                              "body braces do not balance — the flow body cannot be "
                              "delimited"))
                continue
            if paren_form:
                paren_idx = text.rindex("(", 0, open_idx)
                pc = match_paren(text, paren_idx)
                # The call's own `)` must follow the body (optionally + a comma).
                # A runaway match (the next `)` in the file belongs to unrelated
                # code) means the site is unparseable, not that it is fine.
                if pc < 0 or text[close_idx + 1:pc].strip() not in ("", ","):
                    sites.append((m.group(1), open_idx, -1,
                                  "the `builder({ … })` call does not close after the "
                                  "body — the chain that carries flowOn cannot be located"))
                    continue
                anchor = pc
            else:
                anchor = close_idx
            sites.append((m.group(1), open_idx, anchor, None))
    return sites, len(sites)


def scan_file(path):
    raw = open(path, encoding="utf-8", errors="replace").read()
    return scan_text(raw)


def scan_text(raw):
    text = mask_non_code(raw)
    raw_lines = raw.split("\n")
    findings = []

    sites, _n = flow_sites(text)
    for builder, open_idx, anchor, problem in sites:
        start_line = text.count("\n", 0, open_idx) + 1
        if problem:
            findings.append((start_line, start_line, builder + ": " + problem,
                             "[unparseable — fail-closed]"))
            continue
        close_idx = match_brace(text, open_idx)
        body = text[open_idx:close_idx + 1]
        end_line = text.count("\n", 0, close_idx) + 1

        hits = []
        for pat, why in BLOCKING_PATTERNS:
            for hm in re.finditer(pat, body):
                hits.append((text.count("\n", 0, open_idx + hm.start()) + 1, why))
        if not hits:
            continue

        # Escape hatch: the flow line, the two lines above it, or the body.
        exempt = False
        for k in range(max(0, start_line - 3), min(len(raw_lines), end_line)):
            if ESCAPE_RE.search(raw_lines[k]):
                exempt = True
                break
        if exempt:
            continue

        disp, dname = chain_dispatch(text, anchor)
        if disp != "ok":
            tag = {"main": "flowOn(Dispatchers.Main)",
                   "unsafe": "flowOn(Dispatchers.%s)" % dname,
                   None: "no flowOn"}[disp]
            for ln, why in sorted(set(hits)):
                findings.append((start_line, ln, why, tag))

        # [wide3-04] …and the body must not hand the blocking call to a
        # non-background dispatcher itself: a correct chain-level flowOn is
        # defeated by one `withContext(Dispatchers.Main)` inside the body.
        for name, verdict, why in body_dispatch_hazards(body):
            findings.append((
                start_line,
                text.count("\n", 0, open_idx) + 1,
                "body switches to %s for %s" % (name, why),
                "[%s — the chain's flowOn cannot reach it]" % (
                    "Dispatchers.Main" if verdict == "main" else "non-background dispatcher"),
            ))
    return findings


def selftest():
    """[wide3-04] One arm per judgement: each arm is the regression shape the
    external audit used, and each must flip its OWN criterion only."""
    failures = []

    def arm(name, raw, expect_tags):
        got = scan_text(raw)
        tags = sorted(t for _s, _h, _w, t in got)
        if tags != sorted(expect_tags):
            failures.append("%s: expected %s got %s" % (name, sorted(expect_tags), tags))

    CLEAN_BODY = (
        "fun ok(): Flow<Int> = callbackFlow<Int> {\n"
        "    val resp = call.execute()\n"
        "    awaitClose { }\n"
        "}.flowOn(Dispatchers.IO)\n"
    )
    arm("clean trailing-lambda form", CLEAN_BODY, [])

    # paren form, correctly dispatched — must be judged, and must pass
    arm("clean parenthesised form",
        "fun ok2(): Flow<Int> = callbackFlow({\n"
        "    val resp = call.execute()\n"
        "    awaitClose { }\n"
        "}).flowOn(Dispatchers.IO)\n", [])

    # witness 1 (spelling): Unconfined is not Main, but it runs the body on the
    # collector's thread until the first suspension
    arm("flowOn(Dispatchers.Unconfined) is unsafe",
        CLEAN_BODY.replace("Dispatchers.IO", "Dispatchers.Unconfined"),
        ["flowOn(Dispatchers.Unconfined)"])

    arm("flowOn(EmptyCoroutineContext) is unsafe",
        CLEAN_BODY.replace("Dispatchers.IO", "EmptyCoroutineContext"),
        ["flowOn(Dispatchers.EmptyCoroutineContext)"])

    # witness 2 (scope): the chain is right, the body switches to the UI thread
    arm("body withContext(Dispatchers.Main) carrying the blocking call",
        "fun bad(): Flow<Int> = callbackFlow<Int> {\n"
        "    val resp = withContext(Dispatchers.Main) { call.execute() }\n"
        "    awaitClose { }\n"
        "}.flowOn(Dispatchers.IO)\n",
        ["[Dispatchers.Main — the chain's flowOn cannot reach it]"])

    arm("body launch(Main) carrying a blocking read",
        "fun bad2(): Flow<Int> = callbackFlow<Int> {\n"
        "    launch(Dispatchers.Main) { reader.readLine() }\n"
        "}.flowOn(Dispatchers.IO)\n",
        ["[Dispatchers.Main — the chain's flowOn cannot reach it]"])

    # witness 3 (parser-death): `callbackFlow({` — the closing paren is gone, so
    # the call never closes. The whole flow used to be invisible.
    arm("callbackFlow({ without the closing paren",
        "fun bad3(): Flow<Int> = callbackFlow({\n"
        "    val resp = call.execute()\n"
        "}.flowOn(Dispatchers.IO)\n",
        ["[unparseable — fail-closed]"])

    # control: a body that switches to IO is fine
    arm("body withContext(Dispatchers.IO) stays green",
        "fun ok3(): Flow<Int> = callbackFlow<Int> {\n"
        "    val resp = withContext(Dispatchers.IO) { call.execute() }\n"
        "}.flowOn(Dispatchers.IO)\n", [])

    if failures:
        print("STREAM_FLOW_DISPATCH_SELFTEST=FAIL")
        for f in failures:
            print("   - " + f)
        return 1
    print("STREAM_FLOW_DISPATCH_SELFTEST=PASS")
    return 0


def main():
    argv = [a for a in sys.argv[1:] if not a.startswith("-")]
    if "--selftest" in sys.argv:
        return selftest()
    root = os.path.abspath(argv[0] if argv else ".")
    violations = []
    total_sites = 0
    saw_src = False
    for sr in SRC_ROOTS:
        src = os.path.join(root, sr)
        if not os.path.isdir(src):
            continue
        saw_src = True
        for base, _dirs, files in os.walk(src):
            for fn in sorted(files):
                if not fn.endswith(".kt"):
                    continue
                path = os.path.join(base, fn)
                rel = os.path.relpath(path, src).replace(os.sep, "/")
                text = mask_non_code(open(path, encoding="utf-8", errors="replace").read())
                total_sites += flow_sites(text)[1]
                for start_line, hit_line, why, tag in scan_file(path):
                    violations.append(
                        f"{rel}:{start_line}  (blocking at :{hit_line})  {why}  [{tag}]")

    # [wide3-04] Fail-closed on a collapsed scan surface: a judgement that found
    # nothing to judge must never read as "everything is fine".
    if not saw_src:
        violations.append("production source root missing (%s) — nothing was judged "
                          "(fail-closed)" % ", ".join(SRC_ROOTS))
    elif total_sites == 0:
        violations.append("no callbackFlow/channelFlow site found under %s — the scan "
                          "surface collapsed (fail-closed)" % ", ".join(SRC_ROOTS))

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
