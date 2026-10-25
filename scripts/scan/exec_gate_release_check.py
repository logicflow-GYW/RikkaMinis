#!/usr/bin/env python3
"""
Execution-gate guard — deep-audit-1016 FIX-D.

Why this gate exists
--------------------
`sandbox/ExecutionCoordinator.kt` acquires two semaphores to serialize shell
work: a process-wide heavy gate (`Semaphore(1, true)`) and a per-process global
slot (`Semaphore(MAX_CONCURRENT_SHELLS, true)`). Two properties of that code are
invisible to a compiler, to the existing unit tests and to a code reviewer
reading only a diff, yet both fail silently:

  1. INTERRUPTIBILITY (4b). Both waits used `Semaphore.tryAcquire(timeout, unit)`
     directly. That is a blocking JUC call with no suspension point, so a
     coroutine cancelled while parked in it stayed parked — the shell stayed
     reserved for up to the timeout and the session's next command reported
     "System busy". The fix routes every blocking acquire through
     `runInterruptible(Dispatchers.IO)`. Deleting that wrapper restores the bug
     with zero compile errors.

  2. PERMIT ROLLBACK (4a). Between taking the heavy permit and entering the
     `try` whose `finally` releases it there is exactly one throwing statement:
     the global-slot acquire. Before 4b nothing could throw there. Once the wait
     became interruptible, an interrupt on that line propagates out of `execute`
     still holding the ONE heavy permit, and a leaked heavy permit queues every
     HEAVY command in the process until it dies.

  3. THREAD OF THE RECOVERY LOOP (4c). `postRecycleMemoryRecovery` runs up to
     three `System.gc()` rounds with a 120ms settle each. It is reachable
     straight from the main thread (Compose -> promoteDraftIfNeeded ->
     viewModelScope(Main.immediate) -> ensureSession -> migrateDraftResources ->
     sessionDidTerminate, and ViewModel.onCleared), where it is UI-thread jank.

Criterion (mechanical, not "does it look right")
-----------------------------------------------
  G1  `tryAcquire(` appears EXACTLY twice in the file — once per interruptible
      wrapper. A third occurrence means a raw blocking acquire came back; zero
      means the anchors moved and this gate can no longer see them (that is a
      FAIL, never a silent pass — the gate's own blind spot is how a gate dies).
  G2  `awaitHeavyGatePermitInterruptibly`'s body contains `runInterruptible(`.
  G3  `awaitGlobalShellSlotInterruptibly`'s body contains `runInterruptible(`
      and delegates to `internalAcquireGlobalShellSlot(`.
  G4  `internalAcquireGlobalShellSlot`'s body contains a `catch` that releases
      the heavy permit AND rethrows (`heavyGate.release()`, `throw`).
  G5  `postRecycleMemoryRecovery` dispatches on the caller thread:
      `Looper.getMainLooper()` + `recycleDispatchMode(` + `recoveryScope.launch`,
      with the synchronous body kept in `postRecycleMemoryRecoveryNow`.
  G6  The calibrated settle `Thread.sleep(120)` is still there: the value is a
      measured tuning constant (see the KDoc it sits in), so changing it must be
      a deliberate act that also updates this gate.

Deliberately NOT checked: that `execute()` calls the wrappers by name — a rename
is visible in review, and pinning call-site text is the brittle kind of rule
that makes a gate get disabled later. The two wrapper bodies are pinned instead.

Usage: python3 scripts/scan/exec_gate_release_check.py [repo_root]
Exit:  0 = clean, 1 = violation or lost anchor
"""
import os
import re
import sys

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"

MARK_RAW_ACQUIRE = "[exec-gate] raw blocking acquire"
MARK_HEAVY_WRAP = "[exec-gate] heavy-gate wait not interruptible"
MARK_GLOBAL_WRAP = "[exec-gate] global-slot wait not interruptible"
MARK_ROLLBACK = "[exec-gate] heavy permit rollback missing"
MARK_DISPATCH = "[exec-gate] main-thread recovery dispatch missing"
MARK_WIRING = "[exec-gate] wrapper wiring changed"
MARK_SETTLE = "[exec-gate] calibrated settle changed"
MARK_ANCHOR = "[exec-gate] anchor lost"
MARK_DEAD = "[exec-gate] decision short-circuited by a constant"
MARK_INNER = "[exec-gate] blocking acquire outside the interruptible block"


def match_paren(text: str, open_idx: int) -> int:
    """Index of the `)` closing text[open_idx], or -1."""
    depth, i = 0, open_idx
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def brace_span_from(text: str, open_idx: int) -> tuple:
    """(start, end_exclusive) of the `{...}` opened at open_idx; unbalanced = (open_idx, len)."""
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return open_idx, i + 1
        i += 1
    return open_idx, n


def skip_ws(text: str, i: int) -> int:
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return i


def lambda_after_call(text: str, call_idx: int) -> tuple:
    """The trailing `{...}` lambda of the call that starts at call_idx.

    `runInterruptible(Dispatchers.IO) { ... }` — the braces AFTER the balanced
    argument list. Parsing this is the whole point: the wide3 witness smuggled
    the real acquire OUT of the lambda into a sibling `run { ... }`, which left
    `runInterruptible(` in the body and the acquire in the same function and so
    satisfied both substring checks while deleting the decision.
    """
    op = text.find("(", call_idx)
    if op < 0:
        return None
    cl = match_paren(text, op)
    if cl < 0:
        return None
    b = skip_ws(text, cl + 1)
    if b < len(text) and text[b] == "{":
        return brace_span_from(text, b)
    return None


def if_branches(text: str):
    """[(cond_start, cond_end, body_start, body_end)] for every `if (...)`.

    Handles both `{ ... }` bodies and the single-statement body that runs to the
    end of the line (`if (heavyGateHeld) heavyGate.release()`).
    """
    out = []
    for m in re.finditer(r"\bif\s*\(", text):
        op = m.end() - 1
        cl = match_paren(text, op)
        if cl < 0:
            continue
        b = skip_ws(text, cl + 1)
        if b < len(text) and text[b] == "{":
            bs, be = brace_span_from(text, b)
        else:
            bs = b
            eol = text.find("\n", b)
            be = len(text) if eol < 0 else eol
        out.append((op + 1, cl, bs, be))
    return out


def enclosing_if(text: str, idx: int):
    """The innermost `if (...)` whose CONDITION or BODY contains idx.

    Returns (cond_start, cond_end, body_start, body_end, in_body) or None.
    """
    best = None
    for cs, ce, bs, be in if_branches(text):
        if (cs <= idx < ce) or (bs <= idx < be):
            if best is None or (ce - cs) < (best[1] - best[0]):
                best = (cs, ce, bs, be, bs <= idx < be)
    return best


def split_top(s: str, tokens) -> list:
    """Split on the given operators at parenthesis/brace depth 0."""
    parts, depth, cur = [], 0, []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        hit = None
        if depth == 0:
            for t in tokens:
                if s.startswith(t, i):
                    hit = t
                    break
        if hit:
            parts.append("".join(cur).strip())
            cur = []
            i += len(hit)
            continue
        cur.append(c)
        i += 1
    parts.append("".join(cur).strip())
    return parts


def strip_outer_parens(s: str) -> str:
    s = s.strip()
    while s.startswith("(") and s.endswith(")"):
        depth = 0
        ok = True
        for i, ch in enumerate(s):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0 and i != len(s) - 1:
                    ok = False
                    break
        if not ok:
            break
        s = s[1:-1].strip()
    return s


def constant_truth(cond: str):
    """True/False when the condition's value is decidable without running it.

    The wide3 witnesses did not delete the guarded call — they prefixed the
    guard with `false &&` (or the thread check with `false &&`), which keeps
    every substring the gate looked for while making the decision constant.
    `false && anything` and `anything || true` are the two shapes that decide.
    """
    s = strip_outer_parens(cond)
    ors = split_top(s, ("||",))
    if len(ors) > 1:
        vals = [constant_truth(p) for p in ors]
        if any(v is True for v in vals):
            return True
        if all(v is False for v in vals):
            return False
        return None
    ands = split_top(s, ("&&",))
    if len(ands) > 1:
        vals = [constant_truth(p) for p in ands]
        if any(v is False for v in vals):
            return False
        if all(v is True for v in vals):
            return True
        return None
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!"):
        inner = constant_truth(s[1:])
        return None if inner is None else (not inner)
    return None


def catch_blocks(body: str):
    """(start, end_exclusive) of every `catch (...) { ... }` in body."""
    out = []
    for m in re.finditer(r"\bcatch\s*\(", body):
        cl = match_paren(body, m.end() - 1)
        if cl < 0:
            continue
        b = skip_ws(body, cl + 1)
        if b < len(body) and body[b] == "{":
            out.append(brace_span_from(body, b))
    return out


def mask(src: str) -> str:
    """Blank out comments and string-literal interiors, preserving offsets.

    Kotlin source is full of braces and parentheses inside `"${x}ms"` strings
    and inside KDoc, so balancing braces on the raw text is unreliable. Masking
    keeps every identifier reachable (what this gate asserts on) while removing
    everything that could unbalance a scan.
    """
    buf = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                buf[k] = " "
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if buf[k] != "\n":
                    buf[k] = " "
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, max(i + 3, j - 3)):
                if buf[k] != "\n":
                    buf[k] = " "
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            for k in range(i + 1, min(j, n)):
                if buf[k] != "\n":
                    buf[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(buf)


def body_of(masked: str, signature: str) -> str:
    """Return the body of the declaration whose text contains `signature`.

    The declaration's extent is bounded FIRST (by the next declaration at the
    same or lower indentation / the closing brace of its enclosing block) and
    the braces are balanced only INSIDE that region. Doing it the other way
    round is how this gate first shipped a blind spot: an expression body with
    no braces at all (`fun f(): Boolean = g()`) has no brace of its own, so
    balancing from the first `{` after the signature ran straight into the NEXT
    declaration's braces and read that neighbour's `runInterruptible(` as this
    function's — reporting green for the exact regression it was built to catch.
    The fixture `no_interruptible` in test_scan.py now pins that shape.

    Handles block bodies (`fun f() { ... }`) and expression bodies
    (`fun f(): Boolean = runInterruptible(...) { ... }`) alike, and returns the
    brace-less expression body as-is so a missing marker is still reported.
    """
    m = re.search(re.escape(signature), masked)
    if not m:
        return ""
    line_start = masked.rfind("\n", 0, m.start()) + 1
    indent = len(masked[line_start:m.start()]) - len(masked[line_start:m.start()].lstrip())

    end = len(masked)
    pos = masked.find("\n", m.end())
    while pos != -1:
        cand_start = pos + 1
        if cand_start >= len(masked):
            break
        cand_end = masked.find("\n", cand_start)
        if cand_end == -1:
            cand_end = len(masked)
        line = masked[cand_start:cand_end]
        stripped = line.strip()
        if stripped:
            line_indent = len(line) - len(line.lstrip())
            if line_indent <= indent and re.match(
                    r"(@|internal\b|private\b|public\b|protected\b|override\b|object\b|"
                    r"class\b|enum\b|fun\b|val\b|var\b|const\b|\})", stripped):
                end = cand_start
                break
        pos = cand_end

    region = masked[m.end():end]
    open_idx = region.find("{")
    if open_idx < 0:
        return region
    depth = 0
    for k in range(open_idx, len(region)):
        ch = region[k]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return region[:k + 1]
    return region


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..")
    path = os.path.join(root, TARGET)
    if not os.path.isfile(path):
        print(f"❌ {MARK_ANCHOR}: {TARGET} not found under {root}")
        return 1

    raw = open(path, encoding="utf-8").read()
    masked = mask(raw)

    findings: list[str] = []

    # --- G1: exactly two blocking acquires, both inside the wrappers ---------
    acquire_sites = [m.start() for m in re.finditer(r"tryAcquire\(", masked)]
    if len(acquire_sites) != 2:
        lines = sorted({masked[:s].count("\n") + 1 for s in acquire_sites})
        findings.append(
            f"{MARK_RAW_ACQUIRE}: expected exactly 2 `tryAcquire(` (one inside each "
            f"interruptible wrapper), found {len(acquire_sites)}"
            + (f" at lines {lines}" if lines else "")
            + ". A blocking acquire outside `runInterruptible(Dispatchers.IO)` "
              "cannot be cancelled (4b)."
        )

    heavy_wrap = body_of(masked, "suspend fun awaitHeavyGatePermitInterruptibly(")
    if not heavy_wrap:
        findings.append(f"{MARK_ANCHOR}: `awaitHeavyGatePermitInterruptibly` not found")
    else:
        if "runInterruptible(" not in heavy_wrap:
            findings.append(
                f"{MARK_HEAVY_WRAP}: the heavy-gate wait is no longer wrapped in "
                "runInterruptible — a cancelled coroutine parks in tryAcquire for the "
                "whole timeout (4b)"
            )
        # G2b (wide3): `runInterruptible(` being IN the body is not the contract —
        # the ACQUIRE must run inside its lambda. `run { runInterruptible(IO) { true
        # }; heavyGate.tryAcquire(...) }` keeps every substring while the acquire
        # runs uninterruptibly on the worker thread (4b returns).
        spans = [lambda_after_call(heavy_wrap, m.start())
                 for m in re.finditer(r"\brunInterruptible\s*\(", heavy_wrap)]
        spans = [s for s in spans if s]
        acquires = [m.start() for m in re.finditer(r"\btryAcquire\s*\(", heavy_wrap)]
        if not acquires:
            findings.append(
                f"{MARK_INNER}: `awaitHeavyGatePermitInterruptibly` no longer performs "
                "any tryAcquire — the wait may have moved where nothing checks it"
            )
        elif spans and not any(bs <= a < be for a in acquires for bs, be in spans):
            findings.append(
                f"{MARK_INNER}: the heavy-gate tryAcquire is no longer inside the "
                "runInterruptible lambda — it runs uninterruptibly, so a cancelled "
                "coroutine parks until the timeout (4b)"
            )

    global_wrap = body_of(masked, "suspend fun awaitGlobalShellSlotInterruptibly(")
    if not global_wrap:
        findings.append(f"{MARK_ANCHOR}: `awaitGlobalShellSlotInterruptibly` not found")
    else:
        if "runInterruptible(" not in global_wrap:
            findings.append(
                f"{MARK_GLOBAL_WRAP}: the global-slot wait is no longer wrapped in "
                "runInterruptible (4b)"
            )
        if "internalAcquireGlobalShellSlot(" not in global_wrap:
            findings.append(
                f"{MARK_ROLLBACK}: the global-slot wait no longer goes through "
                "internalAcquireGlobalShellSlot, so the heavy permit taken before "
                "it is not handed back when the wait throws (4a)"
            )
        else:
            # Same containment rule as the heavy wrapper: the delegated acquire
            # (the only blocking call this wrapper reaches) must run INSIDE the
            # interruptible lambda, not merely be mentioned in the same body.
            spans = [lambda_after_call(global_wrap, m.start())
                     for m in re.finditer(r"\brunInterruptible\s*\(", global_wrap)]
            spans = [s for s in spans if s]
            delegates = [m.start() for m in re.finditer(
                r"\binternalAcquireGlobalShellSlot\s*\(", global_wrap)]
            if spans and delegates and not any(
                    bs <= d < be for d in delegates for bs, be in spans):
                findings.append(
                    f"{MARK_INNER}: internalAcquireGlobalShellSlot is no longer called "
                    "inside the runInterruptible lambda of the global-slot wait (4b)"
                )

    # --- G3b: the wiring the wrappers exist for -------------------------------
    # Counted, not text-pinned (comments are masked out above): each name occurs
    # exactly twice — its own declaration plus the single call on the command
    # path. This is the shape a reviewer cannot see in a diff and a JVM test
    # cannot reach: if `execute()` goes back to calling the bare helper, both
    # wrappers stay behind as dead code, every JVM arm stays green (it tests the
    # wrappers, which still work) and the regression is live.
    for name in (
        "awaitHeavyGatePermitInterruptibly(",
        "awaitGlobalShellSlotInterruptibly(",
        "internalAcquireGlobalShellSlot(",
    ):
        found = len(re.findall(re.escape(name), masked))
        if found != 2:
            findings.append(
                f"{MARK_WIRING}: `{name}` appears {found}x, expected 2 (declaration "
                "+ the single call on the command path). An interruptible wrapper "
                "is only worth having if the path goes through it."
            )

    rollback = body_of(masked, "fun internalAcquireGlobalShellSlot(")
    if not rollback:
        findings.append(f"{MARK_ANCHOR}: `internalAcquireGlobalShellSlot` not found")
    else:
        catches = catch_blocks(rollback)
        if "catch" not in rollback or not catches:
            findings.append(
                f"{MARK_ROLLBACK}: no catch clause — an interrupt while waiting for "
                "the global slot throws out of execute() holding the heavy permit (4a)"
            )
        else:
            # The rollback is judged on the CATCH BODY, not on the whole function:
            # `heavyGate.release()` also appears in the caller, and a substring
            # anywhere in the body was satisfied by the wide3 witness
            # `if (false && heavyGateHeld) heavyGate.release()` — the call stayed,
            # the decision went (4a comes straight back).
            released = []
            for cs, ce in catches:
                block = rollback[cs:ce]
                for m in re.finditer(r"\bheavyGate\s*\.\s*release\s*\(\s*\)", block):
                    idx = cs + m.start()
                    guard = enclosing_if(rollback, idx)
                    if guard is not None and guard[4]:
                        cond = rollback[guard[0]:guard[1]]
                        if constant_truth(cond) is False:
                            findings.append(
                                f"{MARK_DEAD}: the catch's heavy-permit rollback sits "
                                f"behind `if ({cond.strip()})`, which is constantly "
                                "false — release() never runs (4a)"
                            )
                            continue
                    released.append(idx)
                if not any(cs <= idx < ce for idx in released):
                    findings.append(
                        f"{MARK_ROLLBACK}: the catch clause no longer releases the "
                        "heavy permit (4a)"
                    )
                # ...and the rethrow must come AFTER the release: a `throw` before
                # it (or without it) leaks the permit just the same.
                throw_at = block.find("throw")
                if throw_at < 0:
                    findings.append(
                        f"{MARK_ROLLBACK}: the catch no longer rethrows, which would "
                        "silently turn a cancellation into a 'no slot' rejection"
                    )
                elif released and not any(
                        cs <= idx < cs + throw_at for idx in released):
                    findings.append(
                        f"{MARK_ROLLBACK}: the catch rethrows BEFORE releasing the "
                        "heavy permit — the permit leaks on the throwing path (4a)"
                    )
                break

        if "throw" not in rollback:
            findings.append(
                f"{MARK_ROLLBACK}: the catch no longer rethrows, which would silently "
                "turn a cancellation into a 'no slot' rejection"
            )

    wrapper = body_of(masked, "fun postRecycleMemoryRecovery(")
    if not wrapper:
        findings.append(f"{MARK_ANCHOR}: `postRecycleMemoryRecovery` not found")
    else:
        if "Looper.getMainLooper()" not in wrapper:
            findings.append(
                f"{MARK_DISPATCH}: no main-thread check — the GC loop runs inline on "
                "whatever thread calls it, including the UI thread (4c)"
            )
        if "recycleDispatchMode(" not in wrapper:
            findings.append(
                f"{MARK_DISPATCH}: the dispatch decision no longer goes through "
                "recycleDispatchMode (4c)"
            )
        else:
            # G5b (wide3): the ARGUMENT is the decision. `isOnMainThread = false &&
            # Looper.myLooper() == Looper.getMainLooper()` keeps both substrings and
            # pins the argument to false, so main-thread callers run the GC loop
            # inline (4c) — the review-visible shape, invisibly inverted.
            at = wrapper.find("recycleDispatchMode(")
            op = wrapper.find("(", at)
            cl = match_paren(wrapper, op)
            if cl < 0:
                findings.append(
                    f"{MARK_DEAD}: cannot parse the recycleDispatchMode argument list "
                    "(4c decision unreadable — fail closed)"
                )
            else:
                args = wrapper[op + 1:cl]
                if "Looper.myLooper()" not in args or "Looper.getMainLooper()" not in args:
                    findings.append(
                        f"{MARK_DISPATCH}: the dispatch argument no longer compares the "
                        "caller thread with the main looper (4c)"
                    )
                truth = constant_truth(args.split("=", 1)[-1] if "=" in args else args)
                if truth is not None:
                    findings.append(
                        f"{MARK_DEAD}: the isOnMainThread argument is constantly "
                        f"{str(truth).lower()} — every call takes the same branch, so "
                        "main-thread callers run the GC loop inline (4c)"
                    )
                # The decision must be CONSUMED: the `when`'s subject is the call.
                if not re.search(r"\bwhen\s*\(\s*recycleDispatchMode\s*\(", wrapper):
                    findings.append(
                        f"{MARK_DISPATCH}: the recycleDispatchMode(...) result is not the "
                        "`when` subject — the decision is computed and discarded (4c)"
                    )
        if "recoveryScope.launch" not in wrapper:
            findings.append(
                f"{MARK_DISPATCH}: main-thread callers are no longer deferred to "
                "recoveryScope (4c)"
            )
    if "fun postRecycleMemoryRecoveryNow(" not in masked:
        findings.append(
            f"{MARK_ANCHOR}: `postRecycleMemoryRecoveryNow` not found — the "
            "synchronous body must stay reachable for background callers"
        )

    # --- G6: the calibrated settle value --------------------------------------
    if "Thread.sleep(120)" not in masked.replace("  ", " "):
        findings.append(
            f"{MARK_SETTLE}: `Thread.sleep(120)` is gone. It is the measured "
            "post-GC settle used by the recycle loop; changing it is a deliberate "
            "act (update this gate together with the reason)."
        )

    if findings:
        print("❌ Execution-gate guard found issues:")
        for f in findings:
            print(f"   • {f}")
        return 1

    print("✅ Execution-gate guard: 4a rollback + 4b interruptible waits + "
          "4c main-thread dispatch all wired")
    return 0


HEAVY_WRAPPER = (
    "internal suspend fun awaitHeavyGatePermitInterruptibly(\n"
    "    heavyGate: Semaphore,\n"
    "    timeoutMs: Long,\n"
    "): Boolean = runInterruptible(Dispatchers.IO) {\n"
    "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
    "}\n"
)

ROLLBACK_BODY = (
    "    return try {\n"
    "        globalConcurrency.tryAcquire(1, TimeUnit.SECONDS)\n"
    "    } catch (t: Throwable) {\n"
    "        if (heavyGateHeld) heavyGate.release()\n"
    "        throw t\n"
    "    }\n"
)

DISPATCH_BODY = (
    "        when (recycleDispatchMode(isOnMainThread = "
    "Looper.myLooper() == Looper.getMainLooper())) {\n"
    "            RecycleDispatch.DEFERRED -> "
    "recoveryScope.launch { postRecycleMemoryRecoveryNow() }\n"
    "            RecycleDispatch.INLINE -> postRecycleMemoryRecoveryNow()\n"
    "        }\n"
)


def selftest_fixture(shape):
    """The criterion written out — one dirty shape per wide3 witness."""
    heavy = HEAVY_WRAPPER
    if shape == "acquire_outside_lambda":
        # wide3 G2: the wrapper keeps `runInterruptible(` and the file keeps two
        # `tryAcquire(`s, but the blocking wait left the lambda.
        heavy = heavy.replace(
            "): Boolean = runInterruptible(Dispatchers.IO) {\n"
            "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
            "}\n",
            "): Boolean = run { runInterruptible(Dispatchers.IO) { true }; "
            "heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS) }\n",
        )
    rollback = ROLLBACK_BODY
    if shape == "dead_rollback":
        # wide3 G4: the rollback call stays, the decision goes.
        rollback = rollback.replace(
            "        if (heavyGateHeld) heavyGate.release()\n",
            "        if (false && heavyGateHeld) heavyGate.release()\n",
        )
    dispatch = DISPATCH_BODY
    if shape == "dead_dispatch_arg":
        # wide3 G5: the thread comparison stays, pinned to false.
        dispatch = dispatch.replace(
            "isOnMainThread = Looper.myLooper() == Looper.getMainLooper()",
            "isOnMainThread = false && Looper.myLooper() == Looper.getMainLooper()",
        )
    return (
        "package com.rikkaminis.app.sandbox\n"
        "\n"
        "import java.util.concurrent.Semaphore\n"
        "import java.util.concurrent.TimeUnit\n"
        "\n"
        + heavy +
        "\n"
        "internal suspend fun awaitGlobalShellSlotInterruptibly(\n"
        "    globalConcurrency: Semaphore,\n"
        "    heavyGate: Semaphore,\n"
        "    heavyGateHeld: Boolean,\n"
        "): Boolean = runInterruptible(Dispatchers.IO) {\n"
        "    internalAcquireGlobalShellSlot(globalConcurrency, heavyGate, heavyGateHeld)\n"
        "}\n"
        "\n"
        "internal fun internalAcquireGlobalShellSlot(\n"
        "    globalConcurrency: Semaphore,\n"
        "    heavyGate: Semaphore,\n"
        "    heavyGateHeld: Boolean,\n"
        "): Boolean {\n"
        + rollback +
        "}\n"
        "\n"
        "internal enum class RecycleDispatch { INLINE, DEFERRED }\n"
        "\n"
        "internal fun recycleDispatchMode(isOnMainThread: Boolean): RecycleDispatch =\n"
        "    if (isOnMainThread) RecycleDispatch.DEFERRED else RecycleDispatch.INLINE\n"
        "\n"
        "object ExecutionCoordinator {\n"
        "    private suspend fun executeCommand(\n"
        "        globalConcurrency: Semaphore,\n"
        "        heavyGate: Semaphore,\n"
        "    ) {\n"
        "        val heavyHeld = awaitHeavyGatePermitInterruptibly(heavyGate, 1000L)\n"
        "        awaitGlobalShellSlotInterruptibly(globalConcurrency, heavyGate, heavyHeld)\n"
        "    }\n"
        "\n"
        "    private fun postRecycleMemoryRecovery() {\n"
        + dispatch +
        "    }\n"
        "\n"
        "    private fun postRecycleMemoryRecoveryNow() {\n"
        "        Thread.sleep(120)\n"
        "    }\n"
        "}\n"
    )


def selftest():
    """Negative arms for the three wide3 witnesses + the clean shape."""
    import tempfile
    import shutil

    arms = [
        ("clean tree stays green", "good", 0, None),
        ("witness G2: acquire moved out of the interruptible lambda",
         "acquire_outside_lambda", 1, MARK_INNER),
        ("witness G4: rollback behind `false &&`", "dead_rollback", 1, MARK_DEAD),
        ("witness G5: dispatch argument pinned to false",
         "dead_dispatch_arg", 1, MARK_DEAD),
    ]
    ok = True
    for name, shape, want_rc, needle in arms:
        root = tempfile.mkdtemp(prefix="execgate_")
        try:
            target = os.path.join(root, *TARGET.split("/"))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as f:
                f.write(selftest_fixture(shape))
            old_argv = sys.argv
            sys.argv = ["exec_gate_release_check.py", root]
            try:
                import contextlib
                import io
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    rc = main()
                out = buf.getvalue()
            finally:
                sys.argv = old_argv
        finally:
            shutil.rmtree(root, ignore_errors=True)
        passed = rc == want_rc and (needle is None or needle in out)
        print(f"  selftest {name}: {'ok' if passed else 'FAIL'} (rc={rc})")
        if not passed:
            ok = False
            if needle is not None and needle not in out:
                print(f"      - expected {needle!r} in the gate output")
            print(out)
    print(f"EXEC_GATE_SELFTEST={'PASS' if ok else 'FAIL'} ({len(arms)} arms)")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
