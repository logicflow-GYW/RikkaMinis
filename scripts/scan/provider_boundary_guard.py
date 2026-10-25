#!/usr/bin/env python3
"""TF-E static process-boundary guard.

Mechanical constraint: the app process (`:app`) must NEVER invoke a provider's
network entry points directly. All LLM traffic routes through
ProviderExecutionGateway -> :modelservice. TF-D removed the last in-process
`provider.sendMessage` / `provider.streamMessage` calls and locked it in a JVM
unit test (NoInProcessProviderGuardTest); this scanner is the CI-level,
source-tree gate that runs before every build and every PR.

Rules (scan src/android/app/src/main/java, production only — never tests):

  HARD (exit 1):
   * `<receiver>.sendMessage(`        — receiver is anything but a sanctioned one
   * `<receiver>.streamMessage(`      — ditto
   * `<receiver>.rawPassthroughRequest(`  — Model Use's raw mode; worker only
   * `<receiver>.generateImage(`          — Model Use's image mode; worker only

  WHAT COUNTS AS A CALL (2026-10-25, wide3 witness wave):
   * the receiver is matched as a NAME CHAIN, not as the literal `provider` —
     the earlier pattern list spelled `provider.sendMessage(`, so `p.sendMessage(`
     (the receiver in `CompactOrchestration.sendVia(p: LLMProvider)`) and
     `activeProvider.streamMessage(` were invisible (witness C1, spelling);
   * the whole file is matched after folding whitespace, so a chain broken across
     lines — `provider\\n    .sendMessage(` — is still a call (witness C1,
     parser-death); the earlier per-line `in` test could not see it;
   * comments and string-literal CONTENTS are blanked (offsets preserved) before
     matching, so a line-end `// ProviderExecutionGateway.generateImage(` no
     longer exempts the code in front of it (witness C2, anchor);
   * exemptions are receiver-based (`ProviderExecutionGateway`, the sanctioned
     chokepoint, plus the ChatViewModel entry points `viewModel` / `vm`) and are
     checked against the RECEIVER of the call, never against "some substring
     somewhere on this line".

  DENY BY DEFAULT: an unknown receiver is a finding. The boundary is "no provider
  network call in :app"; a new call site is a review event, not a pass. Add the
  receiver here only when it is provably not a provider.

  SKIPPED:
   * comment lines (//, /*, *, */) and comment tails
   * the worker file itself (ModelExecutionService.kt) — it OWNS provider calls

  WARN (informational, exit 0):
   * ProviderFactory.create anywhere outside the worker (currently legitimate
     metadata-only builds: ChatViewModel.currentProvider, ModelUseOffloadHandler).
     The runtime guard (ProviderBoundary in LLMProvider defaults) refuses any
     such provider the moment it attempts a network call.

Usage: python3 scripts/scan/provider_boundary_guard.py $ROOT
Exit:  0 clean, 1 offences found, 2 usage.
"""

import os
import re
import sys

WORKER_RE = re.compile(r".*[/\\]offload[/\\]ModelExecutionService\.kt$")

# Provider network entry points. Matched as `<receiver>.<name>(`, depth- and
# chain-aware, on comment/string-stripped source.
TARGET_METHODS = (
    "sendMessage",
    "streamMessage",
    "rawPassthroughRequest",
    "generateImage",
)

# Receivers that are legitimately NOT providers. `viewModel` / `vm` is the
# ChatViewModel entry point (enqueues a turn; the network call happens in the
# worker); `ProviderExecutionGateway` is the sanctioned chokepoint itself — the
# app-side call that dispatches INTO the worker. Everything else, including an
# unreadable receiver, is a finding.
EXEMPT_RECEIVERS = frozenset({"viewModel", "vm", "ProviderExecutionGateway"})

# `[fix/audit3-1015 F-03]` Model Use's own provider entry points used to be
# called from :app, where the runtime ProviderBoundary refuses them.
CALL_RE = re.compile(
    r"\.\s*(?P<name>" + "|".join(TARGET_METHODS) + r")\s*\("
)

CREATE_INFO = "ProviderFactory.create("

CHAIN_RE = re.compile(r"[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*")


def receiver_before(text: str, dot: int):
    """The receiver name of the call whose `.` is at `dot` (None if unreadable).

    Extracted by a BACKWARD scan instead of a receiver-shaped regex: the nested
    quantifier in `(?:name(?:\\s*\\.\\s*name)*)` backtracks catastrophically on a
    long dotted chain, which hung the gate on the first witness patch. A chain
    broken across lines (`provider\\n    .sendMessage(`) still lands here because
    the scan skips whitespace, including the newline.
    """
    i = dot - 1
    while i >= 0 and text[i] in " \t\r\n":
        i -= 1
    while i >= 0 and text[i] in "?!":
        i -= 1
        while i >= 0 and text[i] in " \t\r\n":
            i -= 1
    end = i + 1
    while i >= 0 and (text[i].isalnum() or text[i] in "_." or text[i] in " \t\r\n"):
        i -= 1
    chain = text[i + 1:end].strip()
    if not chain:
        return None
    # The scan may pick up the keyword in front (`return p`, `val x = provider`);
    # only the last whitespace-separated token can be the receiver chain.
    tok = chain.split()[-1]
    if not CHAIN_RE.fullmatch(tok):
        return None
    return tok.split(".")[-1].strip()


def strip_noise(text: str) -> str:
    """Blank comments and string/char literal contents; keep every offset.

    Line-end comments matter: `is_comment()` only skipped whole-line comments, so
    a trailing `// ProviderExecutionGateway.generateImage(` exempted a real
    `provider.streamMessage(` on the same line (witness C2). String literal
    CONTENTS are blanked too — a log message that mentions a target pattern is
    not a call.
    """
    out = list(text)
    i, n = 0, len(text)
    in_line = in_block = in_str = in_char = False
    while i < n:
        ch = text[i]
        two = text[i:i + 2]
        if in_line:
            if ch == "\n":
                in_line = False
            else:
                out[i] = " "
            i += 1
            continue
        if in_block:
            if two == "*/":
                out[i] = out[i + 1] = " "
                in_block = False
                i += 2
                continue
            if ch != "\n":
                out[i] = " "
            i += 1
            continue
        if in_str or in_char:
            if ch == "\\":
                out[i] = " "
                if i + 1 < n and text[i + 1] != "\n":
                    out[i + 1] = " "
                i += 2
                continue
            if (in_str and ch == '"') or (in_char and ch == "'"):
                in_str = in_char = False        # keep the delimiter
                i += 1
                continue
            if ch != "\n":
                out[i] = " "
            i += 1
            continue
        if two == "//":
            in_line = True
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if two == "/*":
            in_block = True
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        i += 1
    return "".join(out)


# ── selftest ────────────────────────────────────────────────────────────────
PKG = "src/android/app/src/main/java/com/rikkaminis/app/"

SELFTEST_CLEAN_APP = (
    "package com.rikkaminis.app.ui.chat\n"
    "class ChatViewModel {\n"
    "    fun send() {\n"
    "        viewModel.sendMessage(msg)\n"
    "        vm.sendMessage(msg)\n"
    "        com.rikkaminis.app.sandbox.offload.ProviderExecutionGateway.generateImage(\n"
    "            request = r,\n"
    "        )\n"
    "        // provider.sendMessage(commented) — comments are skipped\n"
    '        val note = "never call provider.sendMessage( here"\n'
    "    }\n"
    "}\n"
)

SELFTEST_WORKER = (
    "package com.rikkaminis.app.sandbox.offload\n"
    "class ModelExecutionService {\n"
    "    fun run() {\n"
    "        provider.streamMessage(request) {}\n"
    "    }\n"
    "}\n"
)


def _selftest_tree(app_body: str):
    return {
        PKG + "ui/chat/ChatViewModel.kt": app_body,
        PKG + "sandbox/offload/ModelExecutionService.kt": SELFTEST_WORKER,
    }


def _run_tree(tree):
    import tempfile
    d = tempfile.mkdtemp(prefix="pbg-selftest-")
    try:
        for rel, text in tree.items():
            p = os.path.join(d, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)
        return _scan(d)
    finally:
        import shutil
        shutil.rmtree(d, ignore_errors=True)


def _scan(root):
    """(exit_code, output) for `root` — a crash reads as red, never as a pass."""
    argv = sys.argv
    try:
        sys.argv = ["provider_boundary_guard.py", root]
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = main()
        return rc, buf.getvalue()
    except Exception as e:
        return 1, f"❌ provider_boundary_guard raised: {type(e).__name__}: {e}"
    finally:
        sys.argv = argv


def selftest() -> int:
    """Negative arms — each witness shape must red on ITS criterion."""
    cases = [
        ("sanctioned receivers + comments + string contents", _selftest_tree(SELFTEST_CLEAN_APP), 0, None),
        ("worker may call providers directly", {PKG + "sandbox/offload/ModelExecutionService.kt": SELFTEST_WORKER}, 0, None),
        ("provider.sendMessage in :app (original defect)",
         _selftest_tree(SELFTEST_CLEAN_APP + "class X { fun f() { provider.sendMessage(m) } }\n"),
         1, "receiver 'provider'"),
        # wide3 witness C1 (spelling): the receiver is a parameter name, not
        # `provider` — the old literal pattern list never fired.
        ("receiver named `p` typed LLMProvider (witness C1 spelling)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun sendVia(p: LLMProvider): Result {\n"
             "        return p.sendMessage(msgs, null, 1024, null)\n"
             "    }\n"
             "}\n"),
         1, "receiver 'p'"),
        # wide3 witness C3 (parser-death): the chain is broken across lines.
        ("chain broken across lines (witness C1 parser-death)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun sendVia(provider: LLMProvider): Result {\n"
             "        val _leak = provider\n"
             "            .sendMessage(emptyList(), null, 1024, null)\n"
             "        return _leak\n"
             "    }\n"
             "}\n"),
         1, "receiver 'provider'"),
        # wide3 witness C2 (anchor): a line-end comment used to exempt the line.
        ("exempt substring in a trailing comment (witness C2)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun sendVia(provider: LLMProvider): Result {\n"
             "        val _leak = provider.streamMessage(emptyList(), null, 1024, null) "
             "// ProviderExecutionGateway.generateImage(\n"
             "        return _leak\n"
             "    }\n"
             "}\n"),
         1, "receiver 'provider'"),
        ("raw passthrough / image entry points are covered too",
         _selftest_tree(SELFTEST_CLEAN_APP + "class Y { fun f() { openAI.rawPassthroughRequest(r) } }\n"),
         1, "receiver 'openAI'"),
        # fail-closed: an empty scan面 must not read as "no provider calls".
        ("empty tree fails closed", {PKG + "Empty.kt": ""}, 1, "collapsed"),
    ]
    failures = []
    for label, tree, want_rc, want_text in cases:
        if want_text == "collapsed":
            import tempfile
            d = tempfile.mkdtemp(prefix="pbg-selftest-empty-")
            try:
                os.makedirs(os.path.join(d, "src/android/app/src/main/java/com/rikkaminis/app"))
                rc, out = _scan(d)
            finally:
                import shutil
                shutil.rmtree(d, ignore_errors=True)
        else:
            rc, out = _run_tree(tree)
        if rc != want_rc or (want_text and want_text not in out):
            failures.append(f"{label}: rc={rc} want={want_rc}\n{out[-600:]}")
    if failures:
        print("❌ provider_boundary_guard selftest:")
        for f in failures:
            print(f"   • {f}")
        return 1
    print(f"PROVIDER_BOUNDARY_SELFTEST=PASS ({len(cases)} fixtures)")
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: provider_boundary_guard.py <repo-root>")
        print("       provider_boundary_guard.py --selftest")
        return 2
    if sys.argv[1] == "--selftest":
        return selftest()
    root = sys.argv[1]
    src = os.path.join(root, "src/android/app/src/main/java")
    if not os.path.isdir(src):
        print(f"⚠️  source tree not found at {src} — skipping (redecorated tree)")
        return 0

    offences = []
    warns = []
    scanned = 0
    for dirpath, _, fns in os.walk(src):
        for fn in fns:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            display = os.path.relpath(path, root)
            scanned += 1
            try:
                with open(path, encoding="utf-8") as f:
                    raw = f.read()
            except OSError as e:
                # Unreadable source = unverifiable call site: fail closed rather
                # than skip the file (a skipped file is a silent hole). On CI this
                # never fires; inside the PRoot sandbox a transient `ENOSYS` open
                # failure lands here, which gate_run.sh classifies as SANDBOX.
                offences.append(f"{display}: unreadable ({type(e).__name__}: {e}) — "
                                "fail-closed, the file's call sites were not checked")
                continue
            if WORKER_RE.match(path):
                continue  # worker owns provider calls
            code = strip_noise(raw)
            lines = raw.splitlines()
            for m in CALL_RE.finditer(code):
                recv = receiver_before(code, m.start())
                if recv in EXEMPT_RECEIVERS:
                    continue
                line_no = code.count("\n", 0, m.start()) + 1
                text = lines[line_no - 1].strip() if line_no <= len(lines) else ""
                where = f" (receiver {recv!r})" if recv else " (receiver not readable)"
                offences.append(f"{display}:{line_no}: {text}{where}")
            for i, line in enumerate(lines, 1):
                if CREATE_INFO in line and not line.lstrip().startswith("//"):
                    # Metadata-only construction is tracked (informational).
                    warns.append(f"{display}:{i}: {line.strip()}")

    if scanned == 0:
        print(
            f"❌ ProviderBoundary: no .kt file under {src} — the scan surface collapsed to "
            "an empty set, which would read as 'no provider calls' (fail-closed)"
        )
        return 1

    if offences:
        print("❌ ProviderBoundary: app-process provider network calls found:")
        for o in offences:
            print(f"   - {o}")
        print(
            "   All provider network entry points (sendMessage / streamMessage / "
            "rawPassthroughRequest / generateImage) MUST live in "
            "ModelExecutionService.kt (:modelservice). Route through "
            "ProviderExecutionGateway instead."
        )
        return 1

    print("✅ ProviderBoundary: no provider network calls outside :modelservice")
    for w in warns:
        print(f"   (info) ProviderFactory.create outside worker (metadata-only): {w}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
