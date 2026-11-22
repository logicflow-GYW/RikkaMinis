#!/usr/bin/env python3
"""[surgery-1122 G2] Static gate: every provider outbound HTTP dispatch is cancellable.

Invariant 4 of docs/stability/protocol-invariants.md: the cancel check must sit
on the SAME run, synchronously, immediately before the request leaves. Polling a
marker every 250 ms leaves a window; a second POST, an image retry or a
follow-up download can leave inside it. Two shapes are legal, and nothing else:

  (a) `executeOrCancel(...)` — the shared bridge, which reads the run's
      `CallDispatchGuard` before `execute()` (see [C2]);
  (b) a blocking `call.execute()` in a stream body that armed a
      `StreamCancelBridge` FIRST (the bridge tears the transport down when the
      collecting job dies, which is what unblocks the read).

[C1] Every `newCall(…).execute()` / `call.execute()` in the provider package must
     be one of those two shapes, judged in the SAME function body — a bridge in a
     neighbouring function does not make this call cancellable.
[C2] The shared bridge must keep its synchronous preflight: `withCallCancellation`
     reads `CallDispatchGuard.ensureBeforeDispatch` BEFORE `execute()`, not only
     via the watcher. Removing that line is exactly the regression this gate
     exists for, and it is invisible to the streaming tests.

Deliberate exemptions carry a reason: `cancel-guard-ok: <why>` above the call.
A background registry refresh has no run to cancel and says so; a provider
request never qualifies.

Usage: python3 scripts/scan/provider_dispatch_cancel_check.py [repo_root] [--selftest]
Exit:  0 PASS, 1 violation (file:line printed), 2 usage/ROT.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from _kotlin_lexer import KotlinLexError, mask_code, mask_strings  # noqa: E402
from _kotlin_ast import FUN_RE, function_body  # noqa: E402
from _kotlin_valueflow import call_args  # noqa: E402

PROVIDER_DIR = "src/android/app/src/main/java/com/rikkaminis/app/provider"
CANCEL_BRIDGE_REL = PROVIDER_DIR + "/CallCancellation.kt"
ESCAPE = "cancel-guard-ok:"
SELFTEST_MARK = "PROVIDER_DISPATCH_CANCEL_SELFTEST=PASS"
# `.execute(` / `.execute {` / `.execute()` — a BLOCKING dispatch in any spelling.
# `executeOrCancel` is excluded by the negative lookahead, not by a prefix test.
EXECUTE_RE = re.compile(r"\.\s*execute\b(?!\s*OrCancel)")
BRIDGE_RE = re.compile(r"StreamCancelBridge\s*\(")
BRIDGE_CANCEL_RE = re.compile(r"StreamCancelBridge\s*\([^)]*\)\s*\{[^}]*\.cancel\s*\(")


def line_of(src, pos):
    return src.count("\n", 0, pos) + 1


def functions(code):
    out = []
    for m in FUN_RE.finditer(code):
        name = m.group(1)
        got = function_body(code[m.start():], name)
        if got:
            out.append((name, m.start(), m.start() + got[1] + 1))
    return out


def enclosing(funcs, pos):
    best = None
    for name, start, end in funcs:
        if start <= pos < end and (best is None or start > best[1]):
            best = (name, start, end)
    return best


def _escape_above(text, start):
    """Is `cancel-guard-ok:` in the contiguous `//` block directly above `start`?

    A reason is usually several lines long ("background refresh, no run to
    cancel, routing it through the bridge would only add an always-absent
    lookup"), so the scan walks the whole contiguous block, not one line.
    Read from the comment-preserving projection: a marker inside a string
    literal cannot exempt a call.
    """
    prev_end = text.rfind("\n", 0, start) + 1
    while prev_end > 0:
        prev_start = text.rfind("\n", 0, prev_end - 1) + 1
        line = text[prev_start:prev_end].strip()
        if ESCAPE in line:
            return True
        if line and not line.startswith("//"):
            break
        prev_end = prev_start
    return False


def call_exempt(raw, pos):
    """An escape comment sitting directly above THIS call."""
    return _escape_above(mask_strings(raw), pos)


def function_exempt(raw, decl_start):
    """An escape comment sitting directly above the declaration.

    [fix/protocol-gate-teeth-1122 F3] On its own this is NOT enough — see
    [is_exempt]. It used to exempt the whole function body, so dropping a second
    uncancellable dispatch into `ModelsDevApi.refreshFromNetwork` was silently
    covered by the reason written for the first one. Measured on the real tree:
    that arm reported PASS.
    """
    return _escape_above(mask_strings(raw), decl_start)


def is_exempt(raw, pos, decl_start, dispatch_count_in_function):
    """Exemption is bound to a CALL, not to a function.

    Two accepted bindings:
      * a reason written directly above the call — precise, preferred for new
        sites;
      * a reason above the declaration, honoured only while that function holds
        EXACTLY ONE dispatch. The single-dispatch case is what the existing
        reason documents ("background registry refresh, no run to cancel"), and
        the count makes the exemption expire the moment a second dispatch is
        added — which is the hole this closes.
    """
    if call_exempt(raw, pos):
        return True
    return dispatch_count_in_function == 1 and function_exempt(raw, decl_start)


def dispatch_positions(code):
    """Every blocking `.execute(` call position, comments already masked."""
    out = []
    for m in EXECUTE_RE.finditer(code):
        tail = code[m.end():m.end() + 8].lstrip()
        if tail.startswith(("(", "{")):
            out.append(m.start())
    return out


def check_file(rel, src):
    v = []
    try:
        code = mask_code(src)
    except KotlinLexError as exc:
        return ["%s: lexical failure (fail-closed): %s" % (rel, exc)]
    funcs = functions(code)
    sites = dispatch_positions(code)
    # [fix/protocol-gate-teeth-1122 F3] How many dispatches each function holds.
    # The declaration-level escape comment is only honoured while the count is
    # exactly one — see [is_exempt].
    per_function = {}
    for pos in sites:
        enc = enclosing(funcs, pos)
        if enc is not None:
            per_function[enc[1]] = per_function.get(enc[1], 0) + 1
    for pos in sites:
        enc = enclosing(funcs, pos)
        if enc is None:
            v.append("%s:%d: dispatch outside any function — cannot judge (ROT)" % (rel, line_of(code, pos)))
            continue
        name, lo, hi = enc
        body = code[lo:hi]
        if is_exempt(src, pos, lo, per_function.get(lo, 0)):
            continue
        armed = any(b.start() < (pos - lo) and BRIDGE_CANCEL_RE.search(body)
                    for b in BRIDGE_RE.finditer(body))
        if not armed:
            v.append("%s:%d: blocking execute() in %s() with no armed StreamCancelBridge and no "
                     "executeOrCancel — a user cancel cannot reach this socket read"
                     % (rel, line_of(code, pos), name))
    return v


def bridge_param_name(code, decl_pos):
    """Name of the first parameter of the function declared at `decl_pos`.

    [fix/protocol-gate-teeth-1122 F4] The ordering arm used to look for the
    literal `block()`. Renaming the lambda parameter to `body` (a pure rename,
    no behaviour change) made that search return -1, which silently skipped the
    ordering check and left only "is the preflight still there" — so
    rename + reorder was a PASS. The name is a spelling; the parameter is the
    decision point, so derive it from the declaration.
    """
    op = code.find("(", decl_pos)
    if op < 0:
        return None
    args = call_args(code, op)
    if not args:
        return None
    a, b = args[0]
    m = re.match(r"\s*([A-Za-z_]\w*)\s*:", code[a:b])
    return m.group(1) if m else None


def check_shared_bridge(src):
    """[C2] the synchronous preflight must sit before the dispatch."""
    v = []
    try:
        code = mask_code(src)
    except KotlinLexError as exc:
        return ["CallCancellation.kt: lexical failure (fail-closed): %s" % exc]
    funcs = functions(code)
    target = None
    for name, lo, hi in funcs:
        if name == "withCallCancellation":
            target = (lo, hi)
            break
    if target is None:
        return ["CallCancellation.kt: withCallCancellation() is gone (ROT — update this gate)"]
    lo, hi = target
    body = code[lo:hi]
    guard = body.find("CallDispatchGuard]")
    invoke = body.find("ensureBeforeDispatch?.invoke()")
    param = bridge_param_name(code, lo)
    if param is None:
        return ["CallCancellation.kt:%d: withCallCancellation's lambda parameter cannot be read — "
                "cannot judge the dispatch order (ROT)" % line_of(code, lo)]
    execute = body.find(param + "()")
    if guard < 0 or invoke < 0:
        v.append("CallCancellation.kt:%d: withCallCancellation no longer reads "
                 "CallDispatchGuard.ensureBeforeDispatch — the synchronous preflight is gone, so a "
                 "cancel that lands before the next watcher poll still sends the request"
                 % line_of(code, lo))
    elif execute < 0:
        v.append("CallCancellation.kt:%d: withCallCancellation never invokes its lambda '%s()' — the "
                 "dispatch moved out of the guarded body (ROT)" % (line_of(code, lo), param))
    elif not (guard < execute and invoke < execute):
        v.append("CallCancellation.kt:%d: the preflight sits AFTER the dispatch (%s()) — it can "
                 "no longer stop the request it was meant to stop" % (line_of(code, lo), param))
    return v


def run(root):
    if not os.path.isdir(root):
        print("provider_dispatch_cancel_check: not a directory: %s" % root)
        return 2
    base = os.path.join(root, PROVIDER_DIR)
    if not os.path.isdir(base):
        print("provider_dispatch_cancel_check: cannot read sources: %s" % base)
        return 2
    violations = []
    for dirpath, _dirs, files in os.walk(base):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8") as fh:
                src = fh.read()
            if rel.endswith("CallCancellation.kt"):
                violations.extend(check_shared_bridge(src))
                continue
            violations.extend(check_file(rel, src))
    if violations:
        print("FAIL provider_dispatch_cancel_check")
        for violation in violations:
            print("  " + violation)
        return 1
    print("PASS provider_dispatch_cancel_check (every provider dispatch is cancellable)")
    return 0


# ── selftest ───────────────────────────────────────────────────────────────
GOOD_PROVIDER = '''package com.rikkaminis.app.provider.openai

class OpenAIProvider {
    suspend fun chat() {
        val response = client.newCall(request).executeOrCancel { resp -> resp.body?.string() }
    }

    fun stream(call: Call, scope: CoroutineScope) {
        val cancelBridge = StreamCancelBridge(scope = scope) { call.cancel() }
        val response = call.execute()
        reader.readLine()
    }
}
'''

GOOD_BRIDGE = '''package com.rikkaminis.app.provider

internal suspend fun <T> Call.withCallCancellation(block: suspend () -> T): T = coroutineScope {
    currentCoroutineContext()[CallDispatchGuard]?.ensureBeforeDispatch?.invoke()
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}
'''

BAD_BARE = '''package com.rikkaminis.app.provider.openai

class OpenAIProvider {
    fun send() {
        val response = client.newCall(request).execute()
        response.body?.string()
    }
}
'''

BAD_BRIDGE_ELSEWHERE = '''package com.rikkaminis.app.provider.openai

class OpenAIProvider {
    fun unrelated(scope: CoroutineScope) {
        val cancelBridge = StreamCancelBridge(scope = scope) { call.cancel() }
    }

    fun send() {
        val response = client.newCall(request).execute()
    }
}
'''

BAD_BRIDGE_NO_CANCEL = '''package com.rikkaminis.app.provider.openai

class OpenAIProvider {
    fun stream(call: Call, scope: CoroutineScope) {
        val cancelBridge = StreamCancelBridge(scope = scope) { log("armed") }
        val response = call.execute()
    }
}
'''

BAD_PREFLIGHT_GONE = GOOD_BRIDGE.replace(
    "    currentCoroutineContext()[CallDispatchGuard]?.ensureBeforeDispatch?.invoke()\n", "")

BAD_PREFLIGHT_AFTER = GOOD_BRIDGE.replace(
    "    currentCoroutineContext()[CallDispatchGuard]?.ensureBeforeDispatch?.invoke()\n"
    "    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }\n"
    "    try {\n        block()\n",
    "    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }\n"
    "    try {\n        block()\n"
    "        currentCoroutineContext()[CallDispatchGuard]?.ensureBeforeDispatch?.invoke()\n")

EXEMPT = '''package com.rikkaminis.app.provider

class ModelsDevApi {
    // cancel-guard-ok: background registry refresh, no run to cancel
    private fun refresh() {
        val response = client.newCall(request).execute()
    }
}
'''


def _arm(label, files, expect_rc, marker, failures):
    import shutil
    import subprocess
    import tempfile
    root = tempfile.mkdtemp(prefix="pdc-arm-")
    try:
        for rel, content in files.items():
            path = os.path.join(root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)
        proc = subprocess.run([sys.executable, os.path.abspath(__file__), root],
                              capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        print("selftest %-30s -> rc=%d" % (label, proc.returncode))
        if proc.returncode != expect_rc or marker not in out:
            failures.append("%s: rc=%d out=%s" % (label, proc.returncode, out[-500:]))
    finally:
        shutil.rmtree(root)


def selftest():
    failures = []
    bridge = {CANCEL_BRIDGE_REL: GOOD_BRIDGE}
    good = dict(bridge)
    good[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = GOOD_PROVIDER
    _arm("compliant provider", good, 0, "PASS provider_dispatch_cancel_check", failures)

    bare = dict(bridge)
    bare[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = BAD_BARE
    _arm("bare execute", bare, 1, "no armed StreamCancelBridge", failures)

    elsewhere = dict(bridge)
    elsewhere[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = BAD_BRIDGE_ELSEWHERE
    _arm("bridge in another function", elsewhere, 1, "no armed StreamCancelBridge", failures)

    no_cancel = dict(bridge)
    no_cancel[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = BAD_BRIDGE_NO_CANCEL
    _arm("bridge does not cancel", no_cancel, 1, "no armed StreamCancelBridge", failures)

    gone = {CANCEL_BRIDGE_REL: BAD_PREFLIGHT_GONE}
    gone[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = GOOD_PROVIDER
    _arm("preflight removed", gone, 1, "synchronous preflight is gone", failures)

    after = {CANCEL_BRIDGE_REL: BAD_PREFLIGHT_AFTER}
    after[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = GOOD_PROVIDER
    _arm("preflight after dispatch", after, 1, "preflight sits AFTER the dispatch", failures)

    exempt = dict(bridge)
    exempt[PROVIDER_DIR + "/ModelsDevApi.kt"] = EXEMPT
    _arm("documented exemption", exempt, 0, "PASS provider_dispatch_cancel_check", failures)

    # [fix/protocol-gate-teeth-1122 F3] A declaration-level reason must not cover
    # a SECOND dispatch added later to the same function. Under the old
    # function-wide exemption this arm reported PASS.
    exempt_two = dict(bridge)
    exempt_two[PROVIDER_DIR + "/ModelsDevApi.kt"] = EXEMPT.replace(
        "        val response = client.newCall(request).execute()",
        "        val response = client.newCall(request).execute()\n"
        "        val again = client.newCall(other).execute()")
    _arm("exemption does not cover a 2nd dispatch", exempt_two, 1,
         "no armed StreamCancelBridge", failures)

    # [F3] The same reason written directly ABOVE one call is still honoured
    # (the precise binding), and does not leak to its sibling.
    exempt_precise = dict(bridge)
    exempt_precise[PROVIDER_DIR + "/ModelsDevApi.kt"] = '''package com.rikkaminis.app.provider

class ModelsDevApi {
    private fun refresh() {
        // cancel-guard-ok: background registry refresh, no run to cancel
        val response = client.newCall(request).execute()
    }
}
'''
    _arm("reason directly above the call", exempt_precise, 0,
         "PASS provider_dispatch_cancel_check", failures)

    # [F4] Renaming the lambda parameter is a spelling change; the ORDER arm must
    # survive it. Under the old `block()` literal the rename alone skipped the
    # ordering check, so rename + reorder was a silent PASS.
    renamed_ok = {CANCEL_BRIDGE_REL: GOOD_BRIDGE.replace(
        "withCallCancellation(block: suspend () -> T)", "withCallCancellation(body: suspend () -> T)"
    ).replace("        block()\n", "        body()\n")}
    renamed_ok[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = GOOD_PROVIDER
    _arm("lambda parameter renamed", renamed_ok, 0, "PASS provider_dispatch_cancel_check", failures)

    renamed_after = {CANCEL_BRIDGE_REL: BAD_PREFLIGHT_AFTER.replace(
        "withCallCancellation(block: suspend () -> T)", "withCallCancellation(body: suspend () -> T)"
    ).replace("        block()\n", "        body()\n")}
    renamed_after[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = GOOD_PROVIDER
    _arm("renamed + preflight after dispatch", renamed_after, 1,
         "preflight sits AFTER the dispatch", failures)

    # [F4] The dispatch leaving the guarded body entirely is a ROT, not a pass.
    never_invoked = {CANCEL_BRIDGE_REL: GOOD_BRIDGE.replace("        block()\n", "")}
    never_invoked[PROVIDER_DIR + "/openai/OpenAIProvider.kt"] = GOOD_PROVIDER
    _arm("lambda never invoked", never_invoked, 1, "never invokes its lambda", failures)

    if failures:
        for failure in failures:
            print("SELFTEST FAIL " + failure)
        return 1
    print(SELFTEST_MARK)
    return 0


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = next((arg for arg in args if not arg.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
