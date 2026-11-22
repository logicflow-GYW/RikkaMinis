#!/usr/bin/env python3
"""[surgery-1122 G3] Static gate: the worker reap decision is process-scoped.

Invariant 7 of docs/stability/protocol-invariants.md: `selfReap()` kills the
whole `:modelservice` PROCESS, and `stopSelf` does NOT end that process. So every
value the reap decision reads — active requests, the queue counter, pending ACKs,
the request generation, the lifecycle state, the lock — must live in the
COMPANION (process scope), not on the Service instance, because the instance is
replaceable while old run threads are still alive.

The regression this pins is the one an independent review caught twice:

  * pass 1: the counters were instance fields, so an old instance's finishing
    thread saw 0/0/0 and killed the process a NEW instance had just admitted a
    request into;
  * pass 2 (the first attempt at fixing pass 1): the lock lived on the instance,
    so two instances never excluded each other.

Judgments:

  [C1] No reap-decision value may be declared outside the companion object.
  [C2] `selfReap()` must assert it runs under the process lock
       (`Thread.holdsLock(lifecycleLock)`), so a future call site that forgets
       the lock fails loudly instead of silently reintroducing pass 1.
  [C3] Every `selfReap()` call must be inside a LOCKED SCOPE, where a locked
       scope is (a) a `synchronized(lifecycleLock) { … }` block, or (b) the body
       of a `…Locked()` helper whose every call site is itself in a locked
       scope. (b) is the codebase's own naming contract, checked transitively —
       it is not an assumption granted to the name.

Everything is judged on structure (declaration scope, guard presence, enclosing
block), never on a spelling of a variable name: renaming keeps the gate working,
dropping a decision does not.

Usage: python3 scripts/scan/worker_process_scope_check.py [repo_root] [--selftest]
Exit:  0 PASS, 1 violation (file:line printed), 2 usage/ROT.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import function_body, match_bracket  # noqa: E402

SERVICE_REL = ("src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/"
               "ModelExecutionService.kt")
SELFTEST_MARK = "WORKER_PROCESS_SCOPE_SELFTEST=PASS"

DECISION_VALUES = (
    "executionSlots",
    "activeRequests",
    "queuedRequests",
    "pendingAckTokens",
    "requestGeneration",
    "lifecycleState",
    "lifecycleLock",
)
SELF_REAP = "selfReap"
LOCK = "lifecycleLock"
LOCKED_SUFFIX = "Locked"


def line_of(src, pos):
    return src.count("\n", 0, pos) + 1


def function_text(code, name):
    """Declaration + body text of the FIRST function named [name]."""
    for m in re.finditer(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(", code):
        got = function_body(code[m.start():], name)
        if not got:
            continue
        return code[m.start():m.start() + got[1] + 1], m.start()
    return None, None


def all_functions(code):
    """[(name, start, end)] for every function declaration."""
    out = []
    for m in re.finditer(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(", code):
        got = function_body(code[m.start():], m.group(1))
        if got:
            out.append((m.group(1), m.start(), m.start() + got[1] + 1))
    return out


def companion_spans(code):
    out = []
    for m in re.finditer(r"\bcompanion\s+object\b[^{]*\{", code):
        close = match_bracket(code, m.end() - 1)
        if close > 0:
            out.append((m.end() - 1, close))
    return out


def declaration_sites(code, name):
    comps = companion_spans(code)
    out = []
    for m in re.finditer(r"\b(?:private\s+|internal\s+|public\s+|protected\s+|@\w+\s+)*"
                         r"(?:val|var)\s+" + re.escape(name) + r"\b", code):
        pos = m.start()
        out.append((pos, any(a <= pos < b for a, b in comps)))
    return out


def sync_spans(code):
    out = []
    for m in re.finditer(r"synchronized\s*\(\s*" + LOCK + r"\s*\)\s*\{", code):
        close = match_bracket(code, m.end() - 1)
        if close > 0:
            out.append((m.start(), close))
    return out


def call_positions(code, name):
    return [m.start() for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*\(", code)]


def locked_scopes(code):
    """synchronized blocks + `…Locked()` function bodies whose calls are all locked.

    The fixpoint is what makes (b) a checked fact: a `…Locked()` helper earns its
    scope only while every call site of it is inside a locked scope. If somebody
    calls it from a plain thread, the helper stops being locked and every
    selfReap() inside it is reported.
    """
    funcs = all_functions(code)
    scopes = list(sync_spans(code))

    def inside(pos):
        return any(a <= pos < b for a, b in scopes)

    changed = True
    while changed:
        changed = False
        for name, lo, hi in funcs:
            if not name.endswith(LOCKED_SUFFIX):
                continue
            if any(a <= lo and hi <= b for a, b in scopes):
                continue
            sites = [p for p in call_positions(code, name) if not (lo <= p < hi)]
            if not sites:
                continue
            if all(inside(p) for p in sites):
                scopes.append((lo, hi))
                changed = True
    return scopes, funcs


def check_service(src):
    v = []
    try:
        code = mask_code(src)
    except KotlinLexError as exc:
        return ["lexical failure (fail-closed): %s" % exc]

    if not companion_spans(code):
        return ["ModelExecutionService.kt: no companion object — the process-scoped reap decision "
                "has no home (ROT)"]

    for name in DECISION_VALUES:
        sites = declaration_sites(code, name)
        if not sites:
            v.append("ModelExecutionService.kt: reap-decision value `%s` is not declared at all "
                     "(ROT — update this gate)" % name)
            continue
        outside = [p for p, inside_companion in sites if not inside_companion]
        if outside:
            v.append("ModelExecutionService.kt:%d: `%s` is declared on the Service INSTANCE — the "
                     "instance is replaced by stopSelf while old run threads live, so this copy is a "
                     "second (wrong) truth for a decision that kills the whole process"
                     % (line_of(code, outside[0]), name))

    body, decl_pos = function_text(code, SELF_REAP)
    if body is None:
        v.append("ModelExecutionService.kt: selfReap() is gone or has no body (ROT — update this gate)")
        return v
    if "holdsLock(" + LOCK + ")" not in re.sub(r"\s+", "", body):
        v.append("ModelExecutionService.kt:%d: selfReap() does not assert Thread.holdsLock(%s) — a "
                 "call site that forgets the lock kills the process on a stale snapshot"
                 % (line_of(code, decl_pos), LOCK))

    scopes, _funcs = locked_scopes(code)
    if not scopes:
        v.append("ModelExecutionService.kt: no locked scope found at all (ROT)")
        return v
    decl_end = decl_pos + len(body)
    for pos in call_positions(code, SELF_REAP):
        if decl_pos <= pos < decl_end:
            continue
        if not any(a <= pos < b for a, b in scopes):
            v.append("ModelExecutionService.kt:%d: selfReap() is called outside a locked scope — it "
                     "can kill a process another instance just admitted a request into"
                     % line_of(code, pos))
    return v


def run(root):
    if not os.path.isdir(root):
        print("worker_process_scope_check: not a directory: %s" % root)
        return 2
    path = os.path.join(root, SERVICE_REL)
    if not os.path.isfile(path):
        print("worker_process_scope_check: cannot read sources: %s" % path)
        return 2
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    violations = check_service(src)
    if violations:
        print("FAIL worker_process_scope_check")
        for violation in violations:
            print("  " + violation)
        return 1
    print("PASS worker_process_scope_check (reap decision is process-scoped)")
    return 0


GOOD = '''package com.rikkaminis.app.sandbox.offload

class ModelExecutionService : Service() {
    companion object {
        private val executionSlots = Semaphore(6)
        private val activeRequests = AtomicInteger(0)
        private val queuedRequests = AtomicInteger(0)
        private val pendingAckTokens = ConcurrentHashMap<String, AtomicBoolean>()
        private val requestGeneration = AtomicLong(0L)
        @Volatile
        private var lifecycleState = ACTIVE
        private val lifecycleLock = Any()
    }

    private fun finishRequestLocked() {
        if (activeRequests.get() == 0) maybeSelfReapLocked()
    }

    private fun maybeSelfReapLocked() {
        selfReap()
    }

    fun onSomeWorkDone() {
        synchronized(lifecycleLock) { finishRequestLocked() }
    }

    private fun selfReap() {
        check(Thread.holdsLock(lifecycleLock)) { "process reap requires the lifecycle lock" }
        runCatching { Process.killProcess(Process.myPid()) }
    }
}
'''


def _arm(label, src, expect_rc, marker, failures):
    import shutil
    import subprocess
    import tempfile
    root = tempfile.mkdtemp(prefix="wps-arm-")
    try:
        path = os.path.join(root, SERVICE_REL)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(src)
        proc = subprocess.run([sys.executable, os.path.abspath(__file__), root],
                              capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        print("selftest %-34s -> rc=%d" % (label, proc.returncode))
        if proc.returncode != expect_rc or marker not in out:
            failures.append("%s: rc=%d out=%s" % (label, proc.returncode, out[-500:]))
    finally:
        shutil.rmtree(root)


def selftest():
    failures = []
    _arm("compliant (Locked helper)", GOOD, 0, "PASS worker_process_scope_check", failures)

    instance_counter = GOOD.replace(
        """    companion object {
        private val executionSlots = Semaphore(6)
        private val activeRequests = AtomicInteger(0)""",
        """    private val activeRequests = AtomicInteger(0)

    companion object {
        private val executionSlots = Semaphore(6)""")
    _arm("instance counter copy", instance_counter, 1, "declared on the Service INSTANCE", failures)

    instance_lock = GOOD.replace(
        """        private val lifecycleLock = Any()
    }""",
        """    }

    private val lifecycleLock = Any()""")
    _arm("instance lock copy", instance_lock, 1, "declared on the Service INSTANCE", failures)

    no_assert = GOOD.replace(
        '        check(Thread.holdsLock(lifecycleLock)) { "process reap requires the lifecycle lock" }\n', "")
    _arm("holdsLock assertion dropped", no_assert, 1, "does not assert Thread.holdsLock", failures)

    unlocked_call = GOOD.replace(
        """    fun onSomeWorkDone() {""",
        """    fun onOtherThread() {
        selfReap()
    }

    fun onSomeWorkDone() {""")
    _arm("selfReap outside the lock", unlocked_call, 1, "called outside a locked scope", failures)

    # the naming contract is CHECKED, not granted: a `…Locked` helper called from
    # an unlocked path loses its scope, and its selfReap() is reported
    leaked_helper = GOOD.replace(
        """    fun onSomeWorkDone() {
        synchronized(lifecycleLock) { finishRequestLocked() }
    }""",
        """    fun onSomeWorkDone() {
        synchronized(lifecycleLock) { finishRequestLocked() }
    }

    fun onPlainThread() {
        maybeSelfReapLocked()
    }""")
    _arm("Locked helper called unlocked", leaked_helper, 1, "called outside a locked scope", failures)

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
