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
   * `sendMessage(` with NO receiver    — the implicit receiver of `.run { }` etc.

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

  EXEMPTION PROVENANCE (2026-10-25, wide4 witness wave — three holes closed):
   * a call with NO receiver is a call: inside `.run { … }` / `.also { … }` the
     provider is the implicit receiver, and `sendMessage(…)` on its own used to
     match nothing at all (w4-C1). Declarations (`fun sendMessage(`) are told
     apart by the `fun` prefix, not by the pattern;
   * exemptions are (receiver, method) PAIRS: `viewModel` / `vm` exposes only
     `sendMessage` (it enqueues; the worker calls), so `vm.streamMessage(…)` is a
     provider call wearing the entry point's name (w4-C3);
   * an entry-point name must be PROVEN not to hold a provider where it is used:
     a local `val vm = ProviderFactory.create(…)` (w4-C2), a `vm: LLMProvider`
     parameter, or a chain prefix (`holder.vm`) defeats the exemption. The proof
     is taken from the ENCLOSING FUNCTION only — a file-wide reading would
     condemn legitimate lambdas (`refresh = { vm -> vm.setThinkingLevel(…) }`).

  DENY BY DEFAULT: an unknown receiver is a finding. The boundary is "no provider
  network call in :app"; a new call site is a review event, not a pass. Add the
  receiver here only when it is provably not a provider.

  SKIPPED:
   * comment lines (//, /*, *, */) and comment tails
   * the worker file itself (ModelExecutionService.kt) — it OWNS provider calls

  FAIL CLOSED: a file that cannot be lexed is a finding, not a pass — the gate
  refuses to judge a projection it could not build.

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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse                                      # noqa: E402

WORKER_RE = re.compile(r".*[/\\]offload[/\\]ModelExecutionService\.kt$")

# Provider network entry points.
TARGET_METHODS = (
    "sendMessage",
    "streamMessage",
    "rawPassthroughRequest",
    "generateImage",
)

GATEWAY = "ProviderExecutionGateway"

# Exemptions are (receiver, method) PAIRS, not receiver name sets.
# `viewModel` / `vm` is the ChatViewModel entry point and exposes ONLY
# `sendMessage` (it enqueues a turn; the network call happens in the worker) —
# ChatViewModel has no `streamMessage` / `rawPassthroughRequest` /
# `generateImage`, so a call spelled `vm.streamMessage(...)` is a provider call
# wearing the entry point's name. `ProviderExecutionGateway` is the sanctioned
# chokepoint itself and may use every entry point.
# [wide4 w4-C2] The old set-based test accepted `vm` for ANY method, and a local
# `val vm = ProviderFactory.create(…)` then called `vm.sendMessage(...)` — a real
# provider call exempted by the variable's name.
EXEMPT_PAIRS = frozenset(
    {(GATEWAY, m) for m in TARGET_METHODS}
    | {("viewModel", "sendMessage"), ("vm", "sendMessage")}
)

# Names whose exemption additionally requires proof that the receiver is not a
# provider: no chain prefix (`holder.vm`), no provider typing, no provider
# construction in the enclosing function.
ENTRY_POINT_RECEIVERS = frozenset({"viewModel", "vm"})

# [wide4 w4-C1] A call may be spelled WITHOUT a receiver: inside `.run { … }` /
# `.also { … }` the provider is the implicit receiver, so `sendMessage(…)` on its
# own is the same network call. Declarations (`fun sendMessage(`, including
# `suspend` / `override` / generic / qualified forms) are excluded by the
# `fun`-prefix test at the match site, not by the pattern.
CALL_RE = re.compile(
    r"(?<![A-Za-z0-9_])(?P<name>" + "|".join(TARGET_METHODS) + r")\s*\("
)

DECL_RE = re.compile(r"\bfun\s+(?:<[^<>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?\s*$")

# A local binding that makes an entry-point name hold an actual provider.
PROVIDER_BIND_TEMPLATES = (
    r"\b(?:val|var)\s+%s\s*(?::[^=\n]*)?=[^\n]*(?:ProviderFactory\s*\.\s*create|LLMProvider)",
    r"\b%s\s*:\s*(?:[A-Za-z_][\w.]*\s*\.\s*)*LLMProvider\b",
)
FUN_HEAD_RE = re.compile(r"^[ \t]*(?:@\w+[^\n]*\n[ \t]*)*(?:[\w@]+\s+)*fun\s+\w+", re.M)

CREATE_INFO = "ProviderFactory.create("

CHAIN_RE = re.compile(r"[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*")


class LexFailure(Exception):
    """The file cannot be lexed — its call sites are unverifiable."""


def _receiver_chain(text: str, dot: int):
    """The whole receiver CHAIN of the call whose `.` is at `dot` (None if
    unreadable).

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
    return tok


def receiver_before(text: str, dot: int):
    """The receiver NAME (last chain segment) of the call at `dot`, or None."""
    chain = _receiver_chain(text, dot)
    return None if chain is None else chain.split(".")[-1].strip()


def strip_noise(text: str) -> str:
    """Blank comments and string/char literal contents; keep every offset.

    Line-end comments matter: `is_comment()` only skipped whole-line comments, so
    a trailing `// ProviderExecutionGateway.generateImage(` exempted a real
    `provider.streamMessage(` on the same line (witness C2). String literal
    CONTENTS are blanked too — a log message that mentions a target pattern is
    not a call.

    [wide4 w4-C3] This used to be a hand-written scanner with its own idea of
    strings. A RAW STRING holding a lone double quote (a triple-quoted literal
    whose body is ` " `) flipped it into "inside a string" for the rest of the
    file, so every later call site became invisible — the projection now comes
    from the shared state machine, and an unlexable file raises instead of
    silently reading as "no calls".
    """
    ok, reason, views = analyse(text)
    if not ok:
        raise LexFailure(reason)
    return views["code"]


def _dot_before(text: str, idx: int) -> int:
    """Index of the `.` immediately before a call name, or -1 (bare call)."""
    i = idx - 1
    while i >= 0 and text[i] in " \t\r\n":
        i -= 1
    return i if i >= 0 and text[i] == "." else -1


def _enclosing_function(code: str, at: int) -> str:
    """Text from the nearest `fun …` head before `at` up to the call.

    The exemption proof has to be local: a file-wide search would let an
    unrelated `val vm = ProviderFactory.create(…)` in another function condemn
    a legitimate entry-point call, and a file-wide "is vm bound to a provider"
    reading in `ConfigBuiltins.kt` (`refresh = { vm -> … }`) does exactly that.
    """
    head = None
    for m in FUN_HEAD_RE.finditer(code, 0, at):
        head = m
    return code[head.start():at] if head else code[:at]


def _provider_bound(code: str, at: int, recv: str) -> bool:
    """True when `recv` provably holds a provider where the call happens."""
    window = _enclosing_function(code, at)
    for tmpl in PROVIDER_BIND_TEMPLATES:
        if re.search(tmpl % re.escape(recv), window):
            return True
    return False


def _exemption(code: str, at: int, chain: str, recv, name: str):
    """True = sanctioned; None = plain finding; str = finding with the reason.

    The exemption is judged on the (receiver, method) PAIR plus the receiver's
    PROVENANCE:
      * `ProviderExecutionGateway` may use every entry point (it dispatches into
        the worker) — qualification does not change that;
      * `viewModel` / `vm` may only `sendMessage`, only as a bare identifier, and
        only when the enclosing function does not bind the name to a provider
        (`val vm = ProviderFactory.create(…)` — wide4 w4-C2 — or `vm: LLMProvider`);
      * anything else is a finding, deny by default.
    """
    if recv is None or (recv, name) not in EXEMPT_PAIRS:
        return None
    if recv not in ENTRY_POINT_RECEIVERS:
        return True
    segs = [s.strip() for s in chain.split(".")]
    if segs != [recv]:
        return f"receiver {recv!r} is reached through {chain!r} — whatever holds it decides"
    if _provider_bound(code, at, recv):
        return f"receiver {recv!r} is bound to a provider in this function"
    return True


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
        # ── wide4 witnesses ──────────────────────────────────────────────────
        # w4-C1 (family-hole): with no receiver the name was invisible — inside
        # `.run { … }` the provider IS the implicit receiver, the same call.
        ("bare call on an implicit receiver (w4-C1)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun resolve(entryId: String): LLMProvider? {\n"
             "        return try {\n"
             "            ProviderFactory.create(instance, apiKey, entry.model, context)\n"
             "                .also { it.run { sendMessage(emptyList(), null, 1024, null) } }\n"
             "        } catch (_: Exception) { null }\n"
             "    }\n"
             "}\n"),
         1, "receiver implicit"),
        # w4-C2 (dataflow): the receiver's NAME was the whole exemption — a local
        # `val vm = ProviderFactory.create(...)` is a provider called `vm`.
        ("entry-point name bound to a provider (w4-C2)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun resolve(entryId: String): LLMProvider? {\n"
             "        return try {\n"
             "            val vm = ProviderFactory.create(instance, apiKey, entry.model, context)\n"
             "            vm.sendMessage(emptyList(), null, 1024, null)\n"
             "            vm\n"
             "        } catch (_: Exception) { null }\n"
             "    }\n"
             "}\n"),
         1, "bound to a provider"),
        # w4-C3 (family-hole): a chain PREFIX borrowed the entry point's name.
        ("entry-point name behind a chain prefix (w4-C3)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class Holder(val vm: Any)\n"
             "class CompactOrchestration {\n"
             "    suspend fun go(holder: Holder) {\n"
             "        holder.vm.sendMessage(emptyList(), null, 1024, null)\n"
             "    }\n"
             "}\n"),
         1, "reached through"),
        # w4-C3 (parser-death): a raw string holding a bare double quote flipped
        # the hand-written scanner into "inside a string" for the rest of the
        # file — every later call site vanished.
        ("raw string with a bare quote hides later calls (w4-C3)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun resolve(entryId: String): LLMProvider? {\n"
             "        val _raw = \"\"\" \" \"\"\"\n"
             "        return try {\n"
             "            val p = ProviderFactory.create(instance, apiKey, entry.model, context)\n"
             "            p.sendMessage(emptyList(), null, 1024, null)\n"
             "            p\n"
             "        } catch (_: Exception) { null }\n"
             "    }\n"
             "}\n"),
         1, "receiver 'p'"),
        # w4-C3 (family-hole): the entry-point name never had a streamMessage —
        # a lambda-bound `viewModel` calling it is a provider call either way.
        ("entry-point name calling a non-entry-point method (w4-C3)",
         _selftest_tree(
             "package com.rikkaminis.app.conversation\n"
             "class CompactOrchestration {\n"
             "    suspend fun resolve(entryId: String): LLMProvider? {\n"
             "        return try {\n"
             "            ProviderFactory.create(instance, apiKey, entry.model, context)\n"
             "                .also { viewModel -> viewModel.streamMessage(emptyList(), null, 1024, null) {} }\n"
             "        } catch (_: Exception) { null }\n"
             "    }\n"
             "}\n"),
         1, "receiver 'viewModel'"),
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
            try:
                code = strip_noise(raw)
            except LexFailure as e:
                # Unlexable source = unverifiable call sites: fail closed.
                offences.append(f"{display}: unlexable ({e}) — fail-closed, the file's call "
                                "sites were not checked")
                continue
            lines = raw.splitlines()
            for m in CALL_RE.finditer(code):
                name = m.group("name")
                line_no = code.count("\n", 0, m.start()) + 1
                text = lines[line_no - 1].strip() if line_no <= len(lines) else ""
                dot = _dot_before(code, m.start())
                if dot < 0:
                    line_start = code.rfind("\n", 0, m.start()) + 1
                    if DECL_RE.search(code[line_start:m.start()]):
                        continue  # `fun sendMessage(` declares, it does not call
                    offences.append(
                        f"{display}:{line_no}: {text} (receiver implicit — the call rides "
                        "whatever receiver the enclosing scope function bound)")
                    continue
                chain = _receiver_chain(code, dot) or ""
                recv = chain.split(".")[-1].strip() if chain else None
                verdict = _exemption(code, m.start(), chain, recv, name)
                if verdict is True:
                    continue
                where = f" (receiver {recv!r}{'' if verdict is None else ' — ' + verdict})" \
                    if recv else " (receiver not readable)"
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
