#!/usr/bin/env python3
"""deep-audit-1019 ISSUE-07 (P1) — KaTeX ready-gate & alias read-path lock gate.

Pins the two race fixes from branch fix/da1019-katex-store-race:

A. KatexWebViewPool.kt — "page finished" / "waiter" coordination must be a
   single atomic step (pre-fix: `awaitReady()` did an UNLOCKED
   `if (isReady) return true` followed by a SEPARATE unlocked deferred
   registration, so a main-thread onPageFinished landing in between completed
   nothing and stranded the waiter for the full 4 s budget).
   The fix routes the coordination through `KatexReadyGate` — pinned here are
   its load-bearing shapes:
     A1  `onPageFinished` (the main-thread entry) calls
         `readyGate.markPageFinished()` **on a live path**;
     A2  the pool no longer juggles its own `isReady` / `readyDeferred` fields
         (any surviving direct flip = the old unlocked shape creeping back);
     A3  `markPageFinished` flips the ready flag and takes the waiter inside
         ONE `synchronized(lock)` block and **reaches** the completion outside
         the lock (an unconditional early return, or a guarded completion,
         strands exactly the requests that were already waiting);
     A4  `await` registers/reads the waiter under the same lock and waits on
         **that** deferred, with the caller's budget (`withTimeoutOrNull(0L)`
         times out on every call) and the failure polarity (`== null`).

B. ChatViewModelStore.kt — the alias table read path must share the writers'
   monitor (pre-fix: writers `@Synchronized`, `activeSessionId` getter +
   `resolveKey` unlocked; the getter is read from offload worker threads via
   `minis-config session.*` → ConfigBuiltins' sessionIdProvider).
     B1  `resolveKey` (the single aliases lookup) is `@Synchronized`;
     B2  `resolveKey` still **returns** something derived from
         `aliases[sessionId]` (`aliases[sessionId].let { sessionId }` calls the
         lookup and throws the answer away);
     B3  the `activeSessionId` getter is `@Synchronized`;
     B4  every value the getter can publish routes through `resolveKey`
         (`.also { resolveKey(it) }` and `?.let { resolveKey(it); it }` both
         publish the un-resolved draft key).

[r3-t05 / dual-gate + symmetric seams] Four properties that live BETWEEN the
pinned shapes — each passes every spelling check above while the effect it
exists to guarantee is gone:
     A5  the renderer-death hook (`onRenderProcessGone` / `onRendererGone`)
         must call `readyGate.markRendererGone(…)` on a live path, and the
         gate's `markRendererGone` must actually clear `ready` and fail the
         waiter — otherwise `ready` keeps its stale true and the next
         `awaitReady()` admits renders onto a dead page (witness #100);
     A6  the consumer `awaitReady()` must ANSWER with the gate's `failure`
         (non-constant, sourced from it, right polarity) — a `return true`
         reports a dead renderer as ready (witness #101);
     A7  `renderInternal` must consume that answer behind a live `if
         (!awaitReady())` that exits — `if (false && …)` or a bare call makes
         A4 green while every cold-start render fails (same witness);
     B5  the WRITE side of the alias table: `rename(from, to)` must record
         `aliases[from] = to` on a reachable path — B1-B4 pin the read side
         only, and a missing alias resolves a draft key to a fresh empty
         ViewModelStore (witness #102).

Analysis runs on `_kotlin_lexer.mask_code()` (comments and string bodies
blanked, offsets preserved): this file's own KDoc quotes the pre-fix shapes and
the sources contain `"http://"`-style literals whose `//` used to swallow the
rest of the line.  A lexer failure is a violation (fail-closed), never a
fallback to raw text.

Usage: python3 katex_ready_race_check.py [ROOT]   (ROOT = repo root, default cwd)
       python3 katex_ready_race_check.py --root ROOT
       python3 katex_ready_race_check.py --selftest
Exit:  0 = gate green, 1 = violations / selftest fail, 2 = usage error.
"""
import os
import re
import sys
import tempfile
from pathlib import Path

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from _kotlin_lexer import KotlinLexError                      # noqa: E402
from _kotlin_ast import (                                      # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
    tail_expr,
)
from _kotlin_valueflow import (                                # noqa: E402
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    path_feasible,
    returns_exiting,
    value_sources,
)

KATEX = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/KatexWebViewPool.kt"
STORE = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModelStore.kt"

SELFTEST_MARKER = "KATEX_READY_RACE_SELFTEST=PASS"

CRIT_A1 = "A1 onPageFinished routes through readyGate.markPageFinished()"
CRIT_A2 = "A2 pool has no direct isReady/readyDeferred field juggling left"
CRIT_A3 = "A3 markPageFinished flips ready + takes waiter in ONE synchronized block, completes outside"
CRIT_A4 = "A4 gate.await() reads the waiter under the same lock"
CRIT_B1 = "B1 ChatViewModelStore.resolveKey is @Synchronized"
CRIT_B2 = "B2 resolveKey still reads aliases[sessionId]"
CRIT_B3 = "B3 ChatViewModelStore.activeSessionId getter is @Synchronized"
CRIT_B4 = "B4 activeSessionId getter still resolves through resolveKey"
CRIT_A5 = ("A5 onRendererGone tells the gate the renderer died, and that signal "
           "clears `ready` + fails the waiter")
CRIT_A6 = "A6 awaitReady() answers from the gate's failure (not a constant, right polarity)"
CRIT_A7 = "A7 renderInternal consumes that answer behind a live, terminating guard"
CRIT_B5 = "B5 rename() records the alias it promises"

# ── shared-primitive glue ──────────────────────────────────────────────────
# Everything below is derived from the lexer/AST/valueflow modules; this gate
# owns only the *decisions*, never a lexer or a brace scanner of its own.


def _body(text: str, name: str):
    """(start, end, kind) of `fun name(...)`'s body region, or None."""
    got = function_body(text, name)
    if not got:
        return None
    start, end, kind = got
    if start < 0 or end < start:
        return None
    return start, end, kind


def _params(text: str, name: str):
    """Declared parameter names of `fun name(...)` (spelling-safe)."""
    m = re.search(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(", text)
    if not m:
        return []
    open_pos = m.end() - 1
    close = match_bracket(text, open_pos)
    if close < 0:
        return []
    out = []
    for a, b in split_top(text[open_pos + 1:close], (",",)):
        piece = text[open_pos + 1 + a:open_pos + 1 + b]
        pm = re.match(r"\s*(?:(?:vararg|noinline|crossinline)\s+)?([A-Za-z_]\w*)\s*:", piece)
        if pm:
            out.append(pm.group(1))
    return out


def _local_env(text: str, start: int, limit: int):
    """`name -> constant` for every binding/assignment in [start, limit).

    Both spellings matter: a *declaration* (`val done = true`) and a plain
    *assignment* to a field (`ready = true`, which is how the gate's own flag is
    flipped). The latter is what makes `if (ready) return` — a reachable early
    exit right after the flag was set — fold to a constant-true guard.
    """
    env = {}
    region = text[start:limit]
    for m in re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?\s*=(?!=)\s*([^\n;]+)", region):
        v = const_value(m.group(2).strip())
        if v is not None and v[0] in ("bool", "num"):
            env[m.group(1)] = v
    for m in re.finditer(r"(?<![\w.`])([A-Za-z_]\w*)\s*=(?!=)\s*([^\n;]+)", region):
        v = const_value(m.group(2).strip())
        if v is not None and v[0] in ("bool", "num"):
            env[m.group(1)] = v
    return env


def _subst_consts(cond: str, env) -> str:
    c = cond
    for name, val in env.items():
        if val[0] == "bool":
            c = re.sub(r"(?<![\w`])" + re.escape(name) + r"(?![\w`])",
                       "true" if val[1] else "false", c)
        elif val[0] == "num":
            c = re.sub(r"(?<![\w`])" + re.escape(name) + r"(?![\w`])",
                       repr(val[1]), c)
    return c


def _cond_truth(cond: str, env):
    """True / False / None for a condition, folding locals we know."""
    return const_bool(_subst_consts(cond, env))


def _strip_local_decls(text: str) -> str:
    """Drop local `fun`/`class`/`object` bodies: their `return`s are not ours."""
    out, i = [], 0
    for m in re.finditer(r"\b(?:fun|class|object)\s+[A-Za-z_]\w*\s*[({]", text):
        if m.start() < i:
            continue
        close = match_bracket(text, m.end() - 1)
        if close < 0:
            continue
        out.append(text[i:m.start()])
        i = close + 1
    out.append(text[i:])
    return " ".join(out)


def _inside_catch(text: str, pos: int) -> bool:
    for m in re.finditer(r"\bcatch\s*\(", text):
        p_close = match_bracket(text, m.end() - 1)
        if p_close < 0:
            continue
        k = p_close + 1
        while k < len(text) and text[k] in " \t\r\n":
            k += 1
        if k >= len(text) or text[k] != "{":
            continue
        b_close = match_bracket(text, k)
        if b_close > pos > k:
            return True
    return False


def _has_unconditional_exit(seg: str, env) -> bool:
    """Does `seg` always leave the enclosing function?

    A bare (un-labelled) `return` / `throw` that no reachable conditional
    guards: it sits in no `if`/`when` branch, or in one whose condition folds
    to true given the locals assigned above it.  `return@label` only leaves a
    lambda, and a local `fun … { return }` leaves only itself.
    """
    body = _strip_local_decls(seg)
    for m in re.finditer(r"\breturn\b(?!\s*@)|\bthrow\b", body):
        pos = m.start()
        if _inside_catch(body, pos):
            continue
        conds = [c for _k, c in enclosing_conditions(body, pos)]
        if not conds:
            return True
        if all(_cond_truth(c, env) is True for c in conds):
            return True
    return False


def _dead_after_exit(text: str, start: int, pos: int) -> bool:
    """An earlier statement in the same block always leaves the function."""
    env = _local_env(text, start, pos)
    for a, b in stmt_spans(text[start:pos]):
        if _has_unconditional_exit(text[start + a:start + b], env):
            return True
    return False


def _is_null_check(cond: str, name: str) -> bool:
    return bool(re.match(
        r"^\s*(?:" + re.escape(name) + r"\s*!==?\s*null|null\s*!==?\s*" + re.escape(name) + r")\s*$",
        cond))


def _statement_at(text: str, start: int, end: int, pos: int):
    """Text of the top-level statement in [start, end) that covers pos."""
    for a, b in stmt_spans(text[start:end]):
        if a <= pos - start < b:
            return text[start + a:start + b]
    return text[start:end]


def line_of(text: str, needle: str) -> int:
    pos = text.find(needle)
    return text[:pos].count("\n") + 1 if pos >= 0 else -1


# ── annotations attached to a declaration (B1) ─────────────────────────────
ANNOTATION_ONLY_RE = re.compile(r"^@[A-Za-z_][\w.]*(?:\([^()]*\))?$")


def annotation_only_lines_above(text: str, decl_idx: int):
    """Annotation lines attached to the declaration at [decl_idx].

    A line counts only when it carries NOTHING but annotations — the anchor
    drift this closes is `@Synchronized private fun noopLock() {}` sitting in
    the 80-char window above an unlocked `resolveKey`. A real modifier line or
    another function body terminates the search.
    """
    prefix = text[:decl_idx]
    lines = prefix.split("\n")[:-1]
    anns = []
    for line in reversed(lines):
        s = line.strip()
        if not s:
            continue
        if ANNOTATION_ONLY_RE.match(s):
            anns.append(s)
            continue
        break
    return anns


# ── A: gate coordination ───────────────────────────────────────────────────
def _crit_a1(katex_code: str):
    span = _body(katex_code, "onPageFinished")
    if span is None:
        return False, "onPageFinished not found", -1
    start, end, _kind = span
    hits = [start + m.start() for m in
            re.finditer(r"readyGate\s*\.\s*markPageFinished\s*\(\s*\)", katex_code[start:end])]
    if not hits:
        return False, "onPageFinished does not call readyGate.markPageFinished()", -1
    reasons = []
    for pos in hits:
        if not path_feasible(katex_code, pos):
            reasons.append("the call sits in a dead branch (a constant-false guard)")
            continue
        if _dead_after_exit(katex_code, start, pos):
            reasons.append("an unconditional early return above it makes the call "
                           "unreachable")
            continue
        return True, "", line_of(katex_code, "readyGate.markPageFinished()")
    return False, ("the call to readyGate.markPageFinished() never runs: "
                   + "; ".join(sorted(set(reasons)))), -1


def _crit_a2(katex_code: str):
    bad_tokens = [
        ("isReady = true", "direct ready-flag flip"),
        ("isReady = false", "direct ready-flag clear"),
        ("readyDeferred?.complete", "direct deferred completion"),
        ("readyDeferred = null", "direct deferred clearing"),
        ("private var isReady", "pool-level ready field"),
        ("private var readyDeferred", "pool-level deferred field"),
    ]
    bad = [(tok, why) for tok, why in bad_tokens if tok in katex_code]
    if bad:
        tok, why = bad[0]
        return False, f"{why} present: {tok!r}", line_of(katex_code, tok)
    return True, "", -1


def _crit_a3(katex_code: str):
    span = _body(katex_code, "markPageFinished")
    if span is None:
        return False, "KatexReadyGate.markPageFinished not found", -1
    start, end, _kind = span
    body = katex_code[start:end]
    cap = re.search(r"\bval\s+([A-Za-z_]\w*)\s*=\s*synchronized\s*\(\s*lock\s*\)\s*\{", body)
    if not cap:
        return False, ("flag flip + waiter take not one atomic step: no "
                       "`val d = synchronized(lock) { … }` in markPageFinished"), -1
    name = cap.group(1)
    open_pos = start + cap.end() - 1
    close_pos = match_bracket(katex_code, open_pos)
    if close_pos < 0:
        return False, "unbalanced `synchronized(lock) {` in markPageFinished", -1
    inner = katex_code[open_pos + 1:close_pos]
    if not re.search(r"\bready\s*=\s*true\b", inner):
        return False, "the synchronized block does not set `ready = true`", -1
    if not contains_ident(tail_expr(inner), "waiter"):
        return False, ("the synchronized block does not hand the waiter out "
                       "(its last statement is not `waiter`)"), -1
    completions = [start + m.start() for m in
                   re.finditer(r"\b" + re.escape(name) + r"\s*\??\.\s*complete\s*\(", body)]
    if not completions:
        return False, f"the waiter taken under the lock (`{name}`) is never completed", -1
    problems = []
    for pos in completions:
        guards = [c for _k, c in enclosing_conditions(katex_code, pos)
                  if not _is_null_check(c, name)]
        if guards:
            problems.append("the completion sits under `%s` — a guarded completion "
                            "skips exactly the requests that were already waiting"
                            % guards[0].strip()[:60])
            continue
        if not path_feasible(katex_code, pos):
            problems.append("the completion sits in a dead branch (constant-false guard)")
            continue
        if _dead_after_exit(katex_code, start, pos):
            problems.append("an unconditional early return above the completion "
                            "makes it dead code — the waiter it just took is never woken")
            continue
        return True, "", line_of(katex_code, "fun markPageFinished()")
    return False, "; ".join(sorted(set(problems))), -1


def _crit_a4(katex_code: str):
    span = _body(katex_code, "await")
    if span is None:
        return False, "KatexReadyGate.await not found", -1
    start, end, _kind = span
    body = katex_code[start:end]
    cap = re.search(r"\bval\s+([A-Za-z_]\w*)\s*=\s*synchronized\s*\(\s*lock\s*\)\s*\{", body)
    if not cap:
        return False, "check-then-act not under one lock in await()", -1
    name = cap.group(1)
    open_pos = start + cap.end() - 1
    close_pos = match_bracket(katex_code, open_pos)
    if close_pos < 0:
        return False, "unbalanced `synchronized(lock) {` in await()", -1
    inner = katex_code[open_pos + 1:close_pos]
    if not re.search(r"\bif\s*\(\s*ready\s*\)\s*return\b", inner):
        return False, ("await() must re-check `ready` inside the lock "
                       "(check-then-act outside it is the original defect)"), -1
    if not (contains_ident(inner, "waiter") and "CompletableDeferred<Unit>(" in inner):
        return False, ("await() must register the waiter inside the lock "
                       "(`waiter ?: CompletableDeferred<Unit>()`)"), -1
    params = _params(katex_code, "await")
    hits = list(re.finditer(r"withTimeoutOrNull\s*\(", body))
    if not hits:
        return False, "await() has no withTimeoutOrNull budget", -1
    problems = []
    for m in hits:
        a_open = start + m.end() - 1
        a_close = match_bracket(katex_code, a_open)
        if a_close < 0:
            problems.append("unbalanced `withTimeoutOrNull(`")
            continue
        arg = katex_code[a_open + 1:a_close].strip()
        lam = _following_lambda(katex_code, a_close)
        if lam is None:
            problems.append("`withTimeoutOrNull(…)` is not followed by a lambda")
            continue
        lam_text = katex_code[lam[0]:lam[1]]
        if not re.search(r"\b" + re.escape(name) + r"\s*\.\s*await\s*\(", lam_text):
            problems.append("the timeout body awaits something other than the `%s` "
                            "produced under the lock" % name)
            continue
        if params and not any(contains_ident(arg, p) for p in params):
            problems.append("the timeout budget `%s` does not derive from the caller's "
                            "`%s`" % (arg[:40], params[0]))
            continue
        val = const_value(arg)
        if val and val[0] == "num" and val[1] <= 0:
            problems.append("`withTimeoutOrNull(%s)` — a non-positive budget times out "
                            "on every call" % arg[:40])
            continue
        stmt = _statement_at(katex_code, start, end, a_open)
        if re.search(r"!=\s*null", stmt) or not re.search(r"==\s*null", stmt):
            problems.append("the timeout result is compared with the wrong polarity "
                            "(a completed wait must read `== null`, i.e. no timeout)")
            continue
        return True, "", line_of(katex_code, "fun await(")
    return False, ("check-then-act not under one lock in await(): "
                   + "; ".join(sorted(set(problems)))), -1


def _following_lambda(text: str, pos: int):
    """(open, close_exclusive) of the `{ … }` that follows the bracket at `pos`."""
    i = pos + 1
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    if i >= len(text) or text[i] != "{":
        return None
    close = match_bracket(text, i)
    return (i, close + 1) if close > 0 else None


# ── B: alias read path ─────────────────────────────────────────────────────
def _returned_expr(text: str, start: int, end: int, kind: str) -> str:
    if kind == "expr":
        return text[start:end].strip()
    body = text[start:end]
    spans = stmt_spans(body)
    if not spans:
        return body.strip()
    tail = body[spans[-1][0]:spans[-1][1]].strip()
    m = re.match(r"^return\b\s*(.*)$", tail, re.S)
    return (m.group(1) if m else tail).strip()


def _cond_span(text: str, t_start: int):
    """`if` 条件表达式的字符区间（then 体起点之前的那个括号里）。"""
    last = None
    for m in re.finditer(r"\bif\s*\(", text[:t_start]):
        last = m
    if last is None:
        return None
    open_paren = last.end() - 1
    close = match_bracket(text, open_paren)
    if close < 0:
        return None
    return open_paren + 1, close


def _crit_a5(katex_code: str):
    """渲染进程死亡信号必须真的到达 gate。

    缝（W100）：A1 只钉 `onPageFinished -> markPageFinished`、Y 门只钉浏览器的
    `onRendererGone`，中间「pool 的 onRendererGone 有没有告诉 gate」两门都不查。
    漏掉这条，`ready` 在不该为 true 的时候保持 true —— 下一次 `awaitReady()`
    立即放行，`renderMath` 跑在死页面上，4 秒后公式退化成源码。
    """
    span = _body(katex_code, "onRenderProcessGone") or _body(katex_code, "onRendererGone")
    if span is None:
        return False, ("no renderer-death hook (onRenderProcessGone / onRendererGone) in "
                       "the pool -- re-point this guard"), -1
    start, end, _kind = span
    hits = [start + m.start() for m in
            re.finditer(r"readyGate\s*\.\s*markRendererGone\s*\(",
                        katex_code[start:end])]
    if not hits:
        return False, ("the renderer-death hook never calls readyGate.markRendererGone(…) -- "
                       "`ready` keeps its stale true, so the next awaitReady() returns "
                       "immediately and renderMath runs on a dead page"), -1
    gate = _body(katex_code, "markRendererGone")
    gbody = katex_code[gate[0]:gate[1]] if gate else ""
    reasons = []
    for pos in hits:
        if not path_feasible(katex_code, pos):
            reasons.append("the call sits in a dead branch (a constant-false guard)")
            continue
        if _dead_after_exit(katex_code, start, pos):
            reasons.append("an unconditional early return above the call makes it unreachable")
            continue
        if not gate:
            reasons.append("the gate has no markRendererGone(…) at all -- the death signal "
                           "is dropped on the floor")
            continue
        if not re.search(r"\bready\s*=\s*false\b", gbody):
            reasons.append("markRendererGone keeps `ready` true -- every later awaitReady() "
                           "short-circuits on the stale flag")
            continue
        if "completeExceptionally(" not in gbody:
            reasons.append("markRendererGone does not fail the waiting deferred "
                           "(completeExceptionally) -- waiters burn the full budget on a "
                           "renderer that is already gone")
            continue
        return True, "", line_of(katex_code, "markRendererGone(")
    return False, "; ".join(sorted(set(reasons))), -1


def _crit_a6(katex_code: str):
    """`awaitReady()` 必须把 gate 的判决**回答出去**。

    缝（W101）：A4 只钉 `KatexReadyGate.await` 的内部锁/预算/极性，调用侧的
    `awaitReady()` 恒返回 true 完全不在视野里。判据 = 返回值不是常量、来源里
    确实有 `failure`、且没有把非空 failure 当成 ready 的极性反转。
    """
    span = _body(katex_code, "awaitReady")
    if span is None:
        return False, "awaitReady() not found -- re-point this guard", -1
    start, end, kind = span
    expr = _returned_expr(katex_code, start, end, kind)
    if not expr:
        return False, "awaitReady() has no return expression to judge", -1
    if const_bool(strip_parens(expr)) is not None:
        return False, ("awaitReady() returns the constant %r -- the gate's verdict is "
                       "dropped, so a renderer that never became ready is reported ready "
                       "and the caller burns the full render timeout" % (expr[:60],)), -1
    if not any(contains_ident(s, "failure") for s in value_sources(expr)):
        return False, ("awaitReady()'s answer does not come from the gate's `failure` "
                       "(%r) -- the verdict is computed and thrown away" % (expr[:70],)), -1
    if (re.search(r"\bfailure\s*(?:!==?|!is)\s*(?:null|ReadyFailure)", expr)
            or re.search(r"!\s*\(?\s*failure\b", expr)):
        return False, ("awaitReady() inverts the polarity (%r) -- a timeout or a dead "
                       "renderer is reported as ready" % (expr[:70],)), -1
    return True, "", line_of(katex_code, "private suspend fun awaitReady")


def _crit_a7(katex_code: str):
    """renderInternal 必须按 `awaitReady()` 的答案退出。

    W101 的 self_doubt：`if (false && !awaitReady())` 同样让 A4 全绿，却连正常
    冷启动都失败。判据 = 调用点必须落在一条**可行**且**会退出**的
    `if (!awaitReady())` 守卫里；裸调用（答案被丢弃）也算红。
    """
    span = _body(katex_code, "renderInternal")
    if span is None:
        return False, "renderInternal not found -- re-point this guard", -1
    start, end, _kind = span
    calls = [start + m.start() for m in
             re.finditer(r"(?<![\w.])awaitReady\s*\(\s*\)", katex_code[start:end])]
    if not calls:
        return False, ("renderInternal never consults awaitReady() -- an unready renderer "
                       "surfaces as a 4 s render timeout instead of a placeholder"), -1
    problems = []
    for pos in calls:
        blk = None
        for cond, t_start, t_end, _es, _ee in if_blocks(katex_code):
            cs = _cond_span(katex_code, t_start)
            if cs and cs[0] <= pos < cs[1]:
                blk = (cond, t_start, t_end)
                break
        if blk is None:
            problems.append("the awaitReady() answer is not used as an `if` condition "
                            "(it is called and the value dropped)")
            continue
        cond, t_start, t_end = blk
        norm = re.sub(r"\s+", "", strip_parens(cond))
        if not (norm.startswith("!awaitReady()")
                or norm in ("awaitReady()==false", "false==awaitReady()")):
            problems.append("the awaitReady() answer is tested as `%s` -- only "
                            "`if (!awaitReady())` means \"not ready -> fall back\""
                            % cond.strip()[:50])
            continue
        if not guard_feasible(cond):
            problems.append("the awaitReady() guard folds to a constant (`%s`) -- the "
                            "fallback branch never runs" % cond.strip()[:50])
            continue
        if not returns_exiting(katex_code, t_start, t_end):
            problems.append("the awaitReady() failure branch does not exit (no "
                            "return/throw) -- rendering continues on a WebView that never "
                            "became ready")
            continue
    if problems:
        return False, "; ".join(sorted(set(problems))), -1
    return True, "", line_of(katex_code, "awaitReady()")


def _crit_b5(store_code: str):
    """`rename(from, to)` 必须把别名写进 `aliases`。

    缝（W102）：B1-B4 只钉读路径（`resolveKey` / getter），写入侧从没被查过。
    别名不写 = 草稿 key 转正那一瞬，外部用旧 key 取 store 会新建一个空的，
    正在进行的流式回复当场断连。
    """
    span = _body(store_code, "rename")
    if span is None:
        return False, "ChatViewModelStore.rename(…) not found -- re-point this guard", -1
    start, end, _kind = span
    params = _params(store_code, "rename")
    if len(params) < 2:
        return False, "rename(…) no longer takes (from, to) -- re-point this guard", -1
    src, dst = params[0], params[1]
    hits = [start + m.start() for m in
            re.finditer(r"aliases\s*\[\s*" + re.escape(src) + r"\s*\]\s*=(?!=)",
                        store_code[start:end])]
    if not hits:
        return False, ("rename(%s, %s) moves the ViewModelStore but never records the alias "
                       "-- the draft key still held in activeSessionIdInternal resolves to a "
                       "fresh empty store the moment the first message lands"
                       % (src, dst)), -1
    problems = []
    for pos in hits:
        eq = store_code.find("=", pos)
        rhs = store_code[eq + 1:expr_end(store_code, eq + 1)]
        if not any(contains_ident(s, dst) for s in value_sources(rhs)):
            problems.append("the alias is recorded from %r instead of from %s"
                            % (rhs.strip()[:50], dst))
            continue
        if not path_feasible(store_code, pos):
            problems.append("the alias write sits in a dead branch")
            continue
        if _dead_after_exit(store_code, start, pos):
            problems.append("an unconditional early return above it makes the alias write "
                            "dead code")
            continue
        return True, "", line_of(store_code, "fun rename(")
    return False, "; ".join(sorted(set(problems))), -1


def _crit_b12(store_code: str):
    span = _body(store_code, "resolveKey")
    if span is None:
        return [(CRIT_B1, False, "resolveKey not found", -1),
                (CRIT_B2, False, "resolveKey not found", -1)]
    start, end, kind = span
    decl_idx = store_code.find("private fun resolveKey(")
    if decl_idx < 0:
        decl_idx = store_code.find("fun resolveKey(")
    decl_idx = max(decl_idx, 0)
    line_start = store_code.rfind("\n", 0, decl_idx) + 1
    own_line = store_code[line_start:decl_idx]
    anns = annotation_only_lines_above(store_code, decl_idx)
    annotated = ("@Synchronized" in own_line or any("@Synchronized" in a for a in anns))
    detail = ""
    if not annotated:
        stray = [s.strip() for s in store_code[:decl_idx].split("\n")[-6:] if "@Synchronized" in s]
        detail = ("no @Synchronized on resolveKey"
                  + (" — the annotation in the window above belongs to another "
                     "declaration: `%s`" % stray[-1] if stray else ""))
    out = [(CRIT_B1, annotated, detail,
            line_of(store_code, "private fun resolveKey("))]
    expr = _returned_expr(store_code, start, end, kind)
    sources = value_sources(expr) if expr else []
    if not any("aliases[sessionId]" in re.sub(r"\s+", "", s) for s in sources):
        out.append((CRIT_B2, False,
                    "resolveKey no longer RETURNS the `aliases[sessionId]` lookup "
                    "(it is computed and its value discarded: %r)"
                    % (expr[:70] if expr else ""), -1))
    else:
        out.append((CRIT_B2, True, "", -1))
    return out


def _getter_expr(store_code: str):
    """(expr, segment) of the `activeSessionId` getter, or None."""
    m = re.search(r"\bval\s+activeSessionId\s*:\s*String\?", store_code)
    if not m:
        return None
    g = re.search(r"get\s*\(\s*\)\s*=", store_code[m.end():])
    if not g:
        return None
    eq = m.end() + g.end()
    expr = store_code[eq:expr_end(store_code, eq)].strip()
    seg = store_code[m.start():eq + len(expr)]
    return expr, seg


def _crit_b34(store_code: str):
    got = _getter_expr(store_code)
    if got is None:
        return [(CRIT_B3, False, "activeSessionId getter not found", -1),
                (CRIT_B4, False, "activeSessionId getter not found", -1)]
    expr, seg = got
    out = [(CRIT_B3, "@Synchronized" in seg,
            "" if "@Synchronized" in seg else "getter not @Synchronized", -1)]
    sources = [re.sub(r"\s+", "", s) for s in value_sources(expr)] if expr else []
    unresolved = [s for s in sources if "resolveKey(" not in s]
    if not sources or unresolved:
        out.append((CRIT_B4, False,
                    "the getter publishes a value that never went through resolveKey "
                    "(%s) — a draft `__new__…` key reaches minis-config verbatim"
                    % (", ".join(repr(s[:60]) for s in (unresolved or ["<empty>"]))), -1))
    else:
        out.append((CRIT_B4, True, "", -1))
    return out


def analyse(katex_code: str, store_code: str):
    out = []
    out.append((CRIT_A1,) + _crit_a1(katex_code))
    out.append((CRIT_A2,) + _crit_a2(katex_code))
    out.append((CRIT_A3,) + _crit_a3(katex_code))
    out.append((CRIT_A4,) + _crit_a4(katex_code))
    out.append((CRIT_A5,) + _crit_a5(katex_code))
    out.append((CRIT_A6,) + _crit_a6(katex_code))
    out.append((CRIT_A7,) + _crit_a7(katex_code))
    out.extend(_crit_b12(store_code))
    out.extend(_crit_b34(store_code))
    out.append((CRIT_B5,) + _crit_b5(store_code))
    return out


def scan(root: Path) -> list:
    katex_path = root / KATEX
    store_path = root / STORE
    missing = [str(p) for p in (katex_path, store_path) if not p.is_file()]
    if missing:
        return [f"missing source file(s): {', '.join(missing)}"]
    try:
        katex_code = code_view(katex_path.read_text(encoding="utf-8"))
        store_code = code_view(store_path.read_text(encoding="utf-8"))
    except KotlinLexError as exc:
        return [f"kotlin parse failure ({exc}) — refusing to judge a tree the lexer "
                f"cannot read (fail-closed)"]
    violations = []
    for crit, ok, detail, line in analyse(katex_code, store_code):
        if not ok:
            where = f" (line ~{line})" if line > 0 else ""
            violations.append(f"{crit}{where}: {detail}")
    return violations


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


FIXED_KATEX = '''package p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class Pool {
    private val readyGate = KatexReadyGate()

    override fun onPageFinished() {
        readyGate.markPageFinished()
    }

    override fun onRendererGone(view: WebView?) {
        readyGate.markRendererGone(
            IllegalStateException("renderer process was killed by the system"),
        )
        if (webView === view) webView = null
    }

    private suspend fun renderInternal(): Int? {
        val wv = ensureWebView() ?: return null
        if (!awaitReady()) return null
        return runOnMainSync()
    }

    private suspend fun awaitReady(): Boolean {
        val failure = readyGate.await(RENDER_TIMEOUT_MS)
        if (failure != null) android.util.Log.w("katex", "never ready")
        return failure == null
    }
}

internal class KatexReadyGate {
    @Volatile
    private var ready = false
    private var waiter: CompletableDeferred<Unit>? = null
    private val lock = Any()

    fun markPageFinished() {
        val d = synchronized(lock) {
            ready = true
            waiter
        }
        d?.complete(Unit)
    }

    fun markRendererGone(cause: Throwable) {
        val d = synchronized(lock) {
            ready = false
            waiter.also { waiter = null }
        }
        d?.completeExceptionally(cause)
    }

    suspend fun await(timeoutMs: Long): Boolean? {
        val d = synchronized(lock) {
            if (ready) return null
            waiter ?: CompletableDeferred<Unit>().also { waiter = it }
        }
        return withTimeoutOrNull(timeoutMs) { d.await() } == null
    }
}
'''

FIXED_STORE = '''package p

object ChatViewModelStore {
    private val aliases = mutableMapOf<String, String>()
    private val stores = mutableMapOf<String, Any>()

    @Synchronized
    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        @Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        aliases[fromSessionId] = toSessionId
    }
}
'''


ARMS_SEEN: list = []


def selftest() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        def write_pair(sub: str, katex: str, store: str) -> Path:
            r = root / sub
            _write(r / KATEX, katex)
            _write(r / STORE, store)
            return r

        def rep(text: str, old: str, new: str, label: str) -> str:
            """Exact replacement; a miss must fail the selftest, never become a
            silent no-op arm (the arm would then assert on the un-mutated
            fixture and pass for the wrong reason)."""
            if text.count(old) != 1:
                sys.exit("MUTATION ANCHOR BROKEN (%s): expected exactly 1 occurrence, got %d"
                         % (label, text.count(old)))
            return text.replace(old, new, 1)

        def expect(sub_name: str, katex: str, store: str, needle: str, want_red=True):
            nonlocal ok
            ARMS_SEEN.append(sub_name)
            r = write_pair("w_" + sub_name.replace(" ", "_").replace("/", "_"), katex, store)
            v = scan(r)
            hit = any(needle in x for x in v)
            if want_red and not hit:
                print(f"[selftest W-{sub_name}] EXPECTED red ({needle!r}), got: {v!r}")
                ok = False
            elif not want_red and v:
                print(f"[selftest W-{sub_name}] EXPECTED GREEN, got: {v!r}")
                ok = False
            else:
                print(f"[selftest W-{sub_name}] {'red as expected' if want_red else 'green'}: OK")

        # ---- arm 0: fixed shapes green ----
        r = write_pair("ok", FIXED_KATEX, FIXED_STORE)
        v = scan(r)
        if v:
            print(f"[selftest 0] EXPECTED green on fixed shapes, got: {v!r}")
            ok = False
        else:
            print("[selftest 0] fixed shapes green: OK")

        # ---- arm A1: onPageFinished flips the flag directly (pre-fix shape) ----
        expect("A1 (direct flag flip)",
               rep(FIXED_KATEX, "readyGate.markPageFinished()", "isReady = true", "A1"),
               FIXED_STORE, "A1")

        # ---- A1b: the call is present but dead (`if (false)`, after a return) ----
        expect("A1b (call behind a constant-false guard)",
               rep(FIXED_KATEX, "readyGate.markPageFinished()",
                   "if (false) readyGate.markPageFinished()", "A1b"),
               FIXED_STORE, "A1")
        expect("A1c (call after an unconditional return)",
               rep(FIXED_KATEX, "        readyGate.markPageFinished()",
                   "        return\n        readyGate.markPageFinished()", "A1c"),
               FIXED_STORE, "A1")

        # ---- arm A3: split step (flag outside, waiter take outside) ----
        expect("A3 (split flag/waiter step)",
               rep(FIXED_KATEX,
                   "fun markPageFinished() {\n"
                   "        val d = synchronized(lock) {\n"
                   "            ready = true\n"
                   "            waiter\n"
                   "        }\n"
                   "        d?.complete(Unit)\n"
                   "    }",
                   "fun markPageFinished() {\n"
                   "        ready = true\n"
                   "        val d = waiter\n"
                   "        d?.complete(Unit)\n"
                   "    }", "A3"),
               FIXED_STORE, "A3")

        # ---- A3b/A3c: atomic step kept, completion made unreachable ----
        expect("A3b (run { return } before the completion)",
               rep(FIXED_KATEX, "        d?.complete(Unit)",
                   "        run { return }\n        d?.complete(Unit)", "A3b"),
               FIXED_STORE, "A3")
        expect("A3c (if (ready) return before the completion)",
               rep(FIXED_KATEX, "        d?.complete(Unit)",
                   "        if (ready) return\n        d?.complete(Unit)", "A3c"),
               FIXED_STORE, "A3")

        # ---- arm A4: unlocked check-then-act in await (the original defect) ----
        expect("A4 (unlocked check-then-act)",
               rep(FIXED_KATEX,
                   "val d = synchronized(lock) {\n"
                   "            if (ready) return null\n"
                   "            waiter ?: CompletableDeferred<Unit>().also { waiter = it }\n"
                   "        }",
                   "if (ready) return null\n"
                   "        val d = waiter ?: CompletableDeferred<Unit>().also { waiter = it }",
                   "A4"),
               FIXED_STORE, "A4")

        # ---- A4b/A4c: the locked deferred is awaited, but never successfully ----
        expect("A4b (zero budget)",
               rep(FIXED_KATEX, "withTimeoutOrNull(timeoutMs)", "withTimeoutOrNull(0L)", "A4b"),
               FIXED_STORE, "A4")
        expect("A4c (inverted timeout polarity)",
               rep(FIXED_KATEX,
                   "return withTimeoutOrNull(timeoutMs) { d.await() } == null",
                   "return withTimeoutOrNull(timeoutMs) { d.await() } != null", "A4c"),
               FIXED_STORE, "A4")

        # ---- arm B1: resolveKey unlocked (pre-fix shape) ----
        expect("B1 (unlocked resolveKey)",
               FIXED_KATEX,
               rep(FIXED_STORE, "    @Synchronized\n    private fun resolveKey",
                   "    private fun resolveKey", "B1"),
               "B1")

        # ---- B2: the lookup runs but its value is thrown away ----
        expect("B2 (lookup result discarded via let)",
               FIXED_KATEX,
               rep(FIXED_STORE, "aliases[sessionId] ?: sessionId",
                   "aliases[sessionId].let { sessionId }", "B2"),
               "B2")

        # ---- arm B3: getter unlocked ----
        expect("B3 (unlocked getter)",
               FIXED_KATEX,
               rep(FIXED_STORE, "@Synchronized get() = activeSessionIdInternal",
                   "get() = activeSessionIdInternal", "B3"),
               "B3")

        # ---- arm B4: getter stops resolving aliases (silent re-open) ----
        expect("B4 (non-resolving getter)",
               FIXED_KATEX,
               rep(FIXED_STORE,
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }",
                   "@Synchronized get() = activeSessionIdInternal", "B4"),
               "B4")

        # ---- B4b..B4d: the resolver is *called*, its value is not published ----
        expect("B4b (resolveKey inside .also)",
               FIXED_KATEX,
               rep(FIXED_STORE,
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }",
                   "@Synchronized get() = activeSessionIdInternal.also { it?.let { resolveKey(it) } }",
                   "W-B4"),
               "B4")
        expect("B4c (let block returns the raw receiver)",
               FIXED_KATEX,
               rep(FIXED_STORE,
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }",
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it); it }",
                   "B4c"),
               "B4")
        expect("B4d (elvis fallback publishes the raw receiver)",
               FIXED_KATEX,
               rep(FIXED_STORE,
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }",
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) } "
                   "?: \"http://\".let { activeSessionIdInternal }", "B4d"),
               "B4")

        # ---- A5/A6/A7/B5: the seam witnesses of r3-t05 ----
        # #100: the renderer-death signal never reaches the gate (the pool drops
        # it) -> `ready` stays true and the next render runs on a dead page.
        expect("A5 (renderer-death hook drops the gate signal)",
               rep(FIXED_KATEX,
                   "        readyGate.markRendererGone(\n"
                   "            IllegalStateException(\"renderer process was killed by the system\"),\n"
                   "        )",
                   "        // readyGate.markRendererGone(\n"
                   "        //     IllegalStateException(\"renderer process was killed by the system\"),\n"
                   "        // )", "A5"),
               FIXED_STORE, "A5")
        expect("A5b (markRendererGone keeps `ready` true)",
               rep(FIXED_KATEX, "            ready = false\n            waiter.also",
                   "            ready = true\n            waiter.also", "A5b"),
               FIXED_STORE, "A5")
        # #101: the consumer drops the gate's verdict.
        expect("A6 (awaitReady answers with a constant)",
               rep(FIXED_KATEX, "        return failure == null",
                   "        return true", "A6"),
               FIXED_STORE, "A6")
        expect("A6b (awaitReady inverts the polarity)",
               rep(FIXED_KATEX, "        return failure == null",
                   "        return failure != null", "A6"),
               FIXED_STORE, "A6")
        # the self_doubt of the report: the guard is present but dead.
        expect("A7 (awaitReady guard made constant-false)",
               rep(FIXED_KATEX, "if (!awaitReady()) return null",
                   "if (false && !awaitReady()) return null", "A7"),
               FIXED_STORE, "A7")
        expect("A7b (awaitReady answer discarded)",
               rep(FIXED_KATEX, "if (!awaitReady()) return null",
                   "awaitReady()", "A7"),
               FIXED_STORE, "A7")
        # #102: the write side of the alias table.
        expect("B5 (rename never records the alias)",
               FIXED_KATEX,
               rep(FIXED_STORE, "        aliases[fromSessionId] = toSessionId",
                   "        // aliases[fromSessionId] = toSessionId", "B5"),
               "B5")

        # ---- arm C: missing file must red (not silently green) ----
        r = root / "c"
        _write(r / KATEX, FIXED_KATEX)
        v = scan(r)
        if not v or "missing" not in v[0]:
            print(f"[selftest C] EXPECTED missing-file violation, got: {v!r}")
            ok = False
        else:
            print("[selftest C] missing store file red: OK")

        # ---- arm D: a tree the lexer cannot read is a violation (fail-closed) ----
        r = write_pair("d", FIXED_KATEX + '\nval broken = "unterminated\n', FIXED_STORE)
        v = scan(r)
        if not any("parse failure" in x for x in v):
            print(f"[selftest D] EXPECTED parse-failure violation, got: {v!r}")
            ok = False
        else:
            print("[selftest D] unparseable source red (fail-closed): OK")

        # ---- green arms: equivalent spellings must not be dragged into red ----
        expect("A3 (null-check completion spelled out)",
               rep(FIXED_KATEX, "        d?.complete(Unit)",
                   "        if (d != null) d.complete(Unit)", "W-A3-green"),
               FIXED_STORE, "", want_red=False)
        expect("A4 (budget coerced, still the caller's)",
               rep(FIXED_KATEX, "withTimeoutOrNull(timeoutMs)",
                   "withTimeoutOrNull(timeoutMs.coerceAtLeast(1L))", "W-A4-green"),
               FIXED_STORE, "", want_red=False)
        expect("B4 (resolved value via let)",
               FIXED_KATEX,
               rep(FIXED_STORE,
                   "@Synchronized get() = activeSessionIdInternal?.let { resolveKey(it) }",
                   "@Synchronized get() = activeSessionIdInternal?.let { raw -> resolveKey(raw) }",
                   "W-B4-green"),
               "", want_red=False)
        expect("B2 (block body spelling)",
               FIXED_KATEX,
               rep(FIXED_STORE,
                   "        aliases[sessionId] ?: sessionId\n",
                   "        val hit = aliases[sessionId]\n        return hit ?: sessionId\n",
                   "W-B2-green"),
               "", want_red=False)

    if ok:
        print(SELFTEST_MARKER)
        print("KATEX_READY_RACE_SELFTEST_ARMS=%d" % len(ARMS_SEEN))
        return 0
    print("KATEX_READY_RACE_SELFTEST=FAIL")
    return 1


def main(argv) -> int:
    root = None
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--selftest":
            return selftest()
        if arg == "--root":
            i += 1
            if i >= len(argv):
                print("usage: --root needs a value", file=sys.stderr)
                return 2
            root = Path(argv[i])
        elif not arg.startswith("--"):
            root = Path(arg)
        i += 1
    if root is None:
        root = Path.cwd()
    if not root.is_dir():
        print(f"repo root not found: {root}", file=sys.stderr)
        return 2

    violations = scan(root)
    if violations:
        print("[katex-ready-race] VIOLATIONS FOUND:")
        for v in violations:
            print(f"  FAIL: {v}")
        print(
            "Fix: keep onPageFinished/waiter coordination inside KatexReadyGate's "
            "single synchronized block, and the alias read path "
            "(resolveKey + activeSessionId getter) under ChatViewModelStore's monitor."
        )
        return 1
    print("[katex-ready-race] clean: gate coordination atomic + alias read path locked")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
