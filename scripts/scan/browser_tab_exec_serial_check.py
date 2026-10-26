#!/usr/bin/env python3
"""[deep-audit-1016c FIX-C] Gate: every WebView-driving execute call inside
`runAcquiredAction` must run under a per-tab mutex.

ISSUE-03A — only `executeSerialized` took `lockForTab`, so the implicit-tab
entry path (`runAcquiredAction(input, implicitTab = true)`) executed
`tab.manager.execute(input)` with NO lock. Two overlap windows against an
explicit `executeSerialized(T)`:

1. the explicit path holds `lockForTab(T)` before its own `acquireTab` flips
   `inUse = true` (a Main-dispatcher gap in which the fan-out still sees T
   free and walks straight in);
2. `acquireTab`'s explicit-existing-id branch returns T WITHOUT checking
   `inUse`, so an explicit action walks onto a tab a tab-less action is
   already driving.

Fix shape (the one this gate pins): inside `runAcquiredAction`, the execute
call sits inside `if (implicitTab) { … lockForTab(tab.id).withLock { … } }`,
with the `else` arm being the explicit path (its caller already holds the
mutex — Kotlin's Mutex is not reentrant, so re-taking it there would
self-deadlock). Rule: within `runAcquiredAction`, every `tab.manager.execute(`
must sit inside the lexical span of `lockForTab(…).withLock { … }`, or inside
the `else` arm of the `if (implicitTab)` conditional (an explicit
`SERIAL-LOCK: caller-held` comment opts nothing out — the else arm is the
only sanctioned unlocked shape). `executeSerialized` must still take its
mutex.

Exit 0 = PASS, 1 = FAIL (prints file:line for each violation).
`--selftest` builds a violating fixture and a compliant fixture and asserts
the checker reds the first and greens the second (the gate proves its own
teeth).

Interface: `python3 scripts/scan/browser_tab_exec_serial_check.py [REPO_ROOT]`
(defaults to cwd; also accepts `--root ROOT`). Registration into
scripts/scan/scan.sh is the main session's job (this branch is forbidden from
touching scan.sh / test_scan.py).
"""
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code                      # noqa: E402
from _kotlin_ast import (match_bracket, split_top, strip_parens,        # noqa: E402
                         const_value, function_body, if_blocks, last_write)
from _kotlin_valueflow import contains_ident, value_sources             # noqa: E402

REL = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"

# ── 判据锚 = 决定点，不是拼写 ───────────────────────────────────────────────
# [B7-t05] 旧锚是字面量 `"tab.manager.execute("`，于是
#   `tab.manager.let { it.execute(input) }` / `tab.manager\n    .execute(input)` /
#   `tab.manager?.execute(input)` 三个等价写法全都不算「一次执行」。现在只锚
#   `.execute(` 这个调用本身：runAcquiredAction 里唯一的 WebView 驱动调用就是它，
#   拼写怎么拆都看得见。
EXEC_SITE = re.compile(r"\.\s*execute\s*\(")
LOCK_FN = "lockForTab"
LOCK_CALL = re.compile(r"\blockForTab\s*\(")
IMPLICIT_IDENT = "implicitTab"


def line_of(src: str, idx: int) -> int:
    return src.count("\n", 0, idx) + 1


def _code(src):
    """注释 + 字符串内容抹平的结构视图（长度不变，位置可回投原文）。

    [B7-t05] parser-death 轴：本门原先**没有**任何注释剥离，于是
    `// lockForTab(tab.id).withLock {` + `// }` 两行注释就充当了语法树上的
    锁块，中间的 execute 被判成「锁内」。解析失败一律 fail-closed。
    """
    try:
        return mask_code(src)
    except KotlinLexError:
        return None


def _params_of(fn_text):
    """函数形参名集合（跳过类型/默认值/修饰符）。"""
    m = re.search(r"\bfun\s+[A-Za-z_]\w*\s*\(", fn_text)
    if m is None:
        return set()
    close = match_bracket(fn_text, m.end() - 1)
    if close < 0:
        return set()
    names = set()
    for a, b in split_top(fn_text[m.end():close], (",",)):
        head = fn_text[m.end() + a: m.end() + b].split("=")[0].split(":")[0].strip()
        head = re.sub(r"@\w+\s+", "", head).split()[-1] if head.split() else ""
        if re.fullmatch(r"[A-Za-z_]\w*", head or ""):
            names.add(head)
    return names


def _bound_rhs(scope: str, name: str) -> str:
    """`val <name> = <rhs>` 的 rhs（无法解析 → ""）。"""
    lw = last_write(scope, name)
    return lw[1].strip() if lw and lw[1] else ""


def _per_tab_arg(arg: str, scope: str, params, depth: int = 0) -> bool:
    """`lockForTab(…)` 的实参是否**派生自被锁的那个 tab**。

    [B7-t05] dataflow 轴：旧判据只认函数名 `lockForTab`，实参整个被丢弃，
    于是 `lockForTab(-1)` 与 `lockForTab(tab.id)` 等价 —— 隐式路径锁在一个
    永远不是任何 tab 的槽位上，per-tab 互斥完全消失。
    """
    a = strip_parens(arg).strip()
    if not a:
        return False
    if const_value(a) is not None:
        return False                              # `lockForTab(-1)` / `lockForTab(0)`
    if re.search(r"\.\s*id\b", a):
        return True                               # `tab.id` / `tab!!.id`
    if re.fullmatch(r"[A-Za-z_]\w*", a):
        if a in params:
            return True                           # `lockForTab(tabId)`（入口形参）
        if depth < 2:
            rhs = _bound_rhs(scope, a)
            if rhs:
                return _per_tab_arg(rhs, scope, params, depth + 1)
        return False
    if any(contains_ident(a, p) for p in params):
        return True                               # `input.tabId` 之类的形参派生
    return False


def withlock_receivers(src: str):
    """Every `.withLock` in [src] reduced to (kind, name, expr, pos).

      ("call", callee, "(argcalls)", pos)  — `lockForTab(tabId).withLock` / `Mutex().withLock`
      ("ident", name, "", pos)             — `mutex.withLock`
      ("unknown", "", "", pos)             — receiver could not be resolved (fail-closed)

    `pos` is the index of `.withLock` in [src], so callers can compute the lock
    block's span and report a source line.

    [fix/meta-audit-m2-binding] the receiver of `withLock` is the contract, not
    the co-occurrence of two words somewhere in the body.
    """
    out = []
    for m in re.finditer(r"\.\s*withLock", src):
        wl = m.start()
        i = wl - 1
        while i >= 0 and src[i] in " \t\r\n":
            i -= 1
        if i < 0:
            out.append(("unknown", "", "", wl))
            continue
        if src[i] == ")":
            depth = 0
            j = i
            while j >= 0:
                if src[j] == ")":
                    depth += 1
                elif src[j] == "(":
                    depth -= 1
                    if depth == 0:
                        break
                j -= 1
            if j < 0:
                out.append(("unknown", "", "", wl))
                continue
            k = j - 1
            while k >= 0 and (src[k].isalnum() or src[k] in "_."):
                k -= 1
            out.append(("call", src[k + 1:j], src[j:i + 1], wl))
            continue
        if src[i].isalnum() or src[i] == "_":
            k = i
            while k >= 0 and (src[k].isalnum() or src[k] in "_."):
                k -= 1
            out.append(("ident", src[k + 1:i + 1], "", wl))
            continue
        out.append(("unknown", "", "", wl))
    return out


def _lock_call_arg(expr: str) -> str:
    """`(tab.id)` → `tab.id`（withlock_receivers 的 expr 只含括号组）。"""
    e = expr.strip()
    if e.startswith("(") and e.endswith(")"):
        return e[1:-1]
    return e


def receiver_failure(kind: str, name: str, expr: str, scope: str, params):
    """None = 接收者可证明是「被锁那个 tab 的 per-tab mutex」。

    否则返回 (code, detail)：
      ("factory",      "`Mutex.withLock`")      接收者不是 lockForTab(…)
      ("constant-slot","`lockForTab(-1).withLock`") 函数名对、实参是常量槽位
      ("not-bound",    "`<expr>`")              标识符绑定的 rhs 值不来自 lockForTab
      ("unbound",      "`<name>`")              作用域里找不到绑定
      ("unresolved",   "")                      接收者形态无法解析
    """
    if kind == "call":
        if not (name == LOCK_FN or name.endswith("." + LOCK_FN)):
            return ("factory", "`%s.withLock`" % name)
        arg = _lock_call_arg(expr)
        if not _per_tab_arg(arg, scope, params):
            return ("constant-slot", "`%s(%s).withLock`" % (name, arg.strip()))
        return None
    if kind == "ident":
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", name):
            return ("unresolved", "")
        rhs = _bound_rhs(scope, name.split(".")[-1])
        if not rhs:
            return ("unbound", "`%s`" % name)
        for s in value_sources(rhs):
            m = LOCK_CALL.search(s)
            if m is None:
                continue
            open_pos = m.end() - 1
            close = match_bracket(s, open_pos)
            if close < 0:
                return ("unresolved", "")
            arg = s[open_pos + 1:close]
            if not _per_tab_arg(arg, scope, params):
                return ("constant-slot", "`%s`" % rhs.strip())
            return None
        # 绑定的值不来自 lockForTab(…)：`val mutex = lockForTab(tabId).let { Mutex() }`
        # 也是这样被抓住的（值的来源是 lambda 尾巴，不是接收者）。
        return ("not-bound", "`%s`" % rhs.strip())
    return ("unresolved", "")


def _withlock_span(code: str, wl_pos: int):
    """`.withLock` 之后那个 `{ … }` 块的区间；配平失败 → None。"""
    i = code.find("{", wl_pos)
    if i < 0:
        return None
    close = match_bracket(code, i)
    return (i, close + 1) if close > 0 else None


def _receiver_message(where: str, line: int, fail, arm_label: str) -> str:
    code, detail = fail
    if code == "factory":
        return ("%s:%d: %s locks on `%s` — a constructor/factory result is not the per-tab "
                "mutex, so two concurrent actions still drive the same WebView "
                "(ISSUE-03A overlap window)" % (where, line, arm_label, detail.strip("`")))
    if code == "constant-slot":
        return ("%s:%d: %s locks on %s — the receiver name is lockForTab(…) but its argument "
                "is not derived from the acquired tab (a constant slot serializes against "
                "nobody), so the per-tab mutex is never acquired (ISSUE-03A overlap window)"
                % (where, line, arm_label, detail))
    if code == "not-bound":
        return ("%s:%d: %s locks on a variable whose value does not come from lockForTab(…) "
                "(%s) — the lock is taken on a throwaway instance, so concurrent actions "
                "still overlap (ISSUE-03A)" % (where, line, arm_label, detail))
    if code == "unbound":
        return ("%s:%d: %s locks on %s but no `val %s = …` binding exists in the function — "
                "the gate cannot prove the per-tab mutex is held (fail-closed)"
                % (where, line, arm_label, detail, detail.strip("`")))
    return ("%s:%d: %s locks on a receiver this gate cannot resolve — re-pin the gate rather "
            "than assume it is the per-tab mutex" % (where, line, arm_label))


def _implicit_arm(code: str, b_lo: int, b_hi: int, exec_sites):
    """`if (implicitTab) { … } else { … }` 的 (cond, t_s, t_e, e_s, e_e)。

    [B7-t05] 锚的是「条件里出现 implicitTab 这个决定点」，不是字面量
    `if (implicitTab)`：`if (implicitTab == true)` / `if (true == implicitTab)`
    是同一个决定的等价拼写，用它绕开锚点不算修好。
    """
    cands = [b for b in if_blocks(code)
             if contains_ident(b[0], IMPLICIT_IDENT)
             and b[1] >= b_lo and b[1] < b_hi]
    # 优先：有 else 的、且 then 支里就有执行点的那个（另有 `if (implicitTab == true)
    # return …` 这类提前返回的孪生 if，不带 else，不能冒充锁跨度）。
    for b in cands:
        cond, t_s, t_e, e_s, e_e = b
        if e_s is not None and any(t_s <= p < t_e for p in exec_sites):
            return b
    for b in cands:
        if b[3] is not None:
            return b
    return None


def check_source(src: str, label: str) -> list:
    """Returns a list of 'label:line: message' violations (empty = PASS)."""
    bad = []
    code = _code(src)
    if code is None:
        return ["%s:1: cannot lex the source (unbalanced quote/comment) — fail-closed: "
                "the lock spans cannot be located" % label]

    fm = re.search(r"\bfun\s+runAcquiredAction\s*\(", code)
    fb = function_body(code, "runAcquiredAction")
    if fm is None or fb is None:
        bad.append("%s:1: runAcquiredAction(…) not found — the serialization "
                   "contract moved? re-pin this gate" % label)
        return bad
    b_lo, b_hi, _kind = fb
    fn_text = code[fm.start():b_hi + 1]
    params = _params_of(fn_text)
    fn_line = line_of(code, fm.start())

    exec_sites = [m.start() for m in EXEC_SITE.finditer(code) if b_lo <= m.start() < b_hi]
    if not exec_sites:
        bad.append("%s:%d: runAcquiredAction no longer calls `<tab>.execute(` — the "
                   "WebView-driving call moved? re-pin this gate" % (label, fn_line))
        return bad

    lock_spans, else_spans = [], []
    arm = _implicit_arm(code, b_lo, b_hi, exec_sites)
    if arm is None:
        bad.append("%s:%d: the `if (implicitTab) … else …` conditional is gone — the "
                   "implicit path's execute has no sanctioned locked/unlocked shape "
                   "anymore (re-pin this gate)" % (label, fn_line))
    else:
        cond, t_s, t_e, e_s, e_e = arm
        cond_line = line_of(code, t_s)
        arm_text = code[t_s:t_e]
        arm_recv = withlock_receivers(arm_text)
        if not arm_recv:
            bad.append("%s:%d: the implicit arm takes no lock — it must acquire the "
                       "acquired tab's per-tab mutex (lockForTab(tab.id).withLock { … })"
                       % (label, cond_line))
        for kind, name, expr, wl in arm_recv:
            # 跨度先记（是否「包住执行点」与「接收者对不对」是两条判据，
            # 否则一个错接收者会重复报两条，把真正的原因埋掉）。
            span = _withlock_span(code, t_s + wl)
            if span is None:
                bad.append("%s:%d: the implicit arm's withLock block is unbalanced — "
                           "fail-closed" % (label, cond_line))
            else:
                lock_spans.append(span)
            fail = receiver_failure(kind, name, expr, fn_text, params)
            if fail is not None:
                bad.append(_receiver_message(label, cond_line, fail, "the implicit arm"))
        if e_s is None:
            bad.append("%s:%d: the if (implicitTab) conditional has no else arm — the "
                       "explicit path's execute must stay here (its caller already holds "
                       "the mutex)" % (label, cond_line))
        else:
            else_spans.append((e_s, e_e))

    live = [p for p in exec_sites if _feasible(code, p)]
    if not live:
        bad.append("%s:%d: every `<tab>.execute(` site in runAcquiredAction is provably "
                   "unreachable — the WebView is never driven (fail-closed)"
                   % (label, fn_line))
    for pos in live:
        if any(lo <= pos < hi for lo, hi in lock_spans + else_spans):
            continue
        bad.append(
            "%s:%d: `<tab>.execute(` outside a per-tab mutex scope (ISSUE-03A: the "
            "implicit path must wrap it in lockForTab(tab.id).withLock { … }; the "
            "explicit path's caller already holds the mutex)" % (label, line_of(code, pos))
        )

    # ── executeSerialized: 锁本身 + 锁跨度 ────────────────────────────────
    fm2 = re.search(r"\bfun\s+executeSerialized\s*\(", code)
    fb2 = function_body(code, "executeSerialized")
    if fm2 is None or fb2 is None:
        bad.append("%s:1: executeSerialized(…) not found — the serial entry moved? "
                   "re-pin this gate" % label)
        return bad
    s_lo, s_hi, _k2 = fb2
    s_text = code[fm2.start():s_hi + 1]
    ser_line = line_of(code, fm2.start())
    s_params = _params_of(s_text)
    recv = withlock_receivers(s_text)
    s_spans = []
    if not recv:
        bad.append("%s:%d: executeSerialized takes no lock at all — it must acquire "
                   "lockForTab(tabId).withLock" % (label, ser_line))
    for kind, name, expr, wl in recv:
        span = _withlock_span(code, fm2.start() + wl)
        if span is None:
            bad.append("%s:%d: executeSerialized's withLock block is unbalanced — "
                       "fail-closed" % (label, ser_line))
        else:
            s_spans.append(span)
        fail = receiver_failure(kind, name, expr, s_text, s_params)
        if fail is not None:
            bad.append(_receiver_message(label, ser_line, fail, "executeSerialized"))

    # [B7-t05] order 轴：`mutex.withLock { Unit }` 之后在锁外调用
    # runAcquiredAction 与「根本没锁」等价 —— 锁必须**包住**入口调用。
    calls = [m.start() for m in re.finditer(r"\brunAcquiredAction\s*\(", s_text)]
    if not calls:
        bad.append("%s:%d: executeSerialized no longer calls runAcquiredAction(…) — the "
                   "serialized execution entry disappeared? re-pin this gate"
                   % (label, ser_line))
    for p in calls:
        if any(lo <= fm2.start() + p < hi for lo, hi in s_spans):
            continue
        bad.append(
            "%s:%d: executeSerialized calls runAcquiredAction(…) OUTSIDE its "
            "withLock block — an empty lock block releases the mutex before the "
            "execute, so two concurrent actions on the same tab overlap "
            "(ISSUE-03A)" % (label, line_of(code, fm2.start() + p))
        )
    return bad


def _feasible(code: str, pos: int) -> bool:
    """pos 处是否可达（死分支里的 execute 不构成违约）。"""
    from _kotlin_ast import enclosing_conditions
    from _kotlin_valueflow import guard_feasible
    for _kind, cond in enclosing_conditions(code, pos):
        if not guard_feasible(cond):
            return False
    return True


def run(repo: str) -> int:
    path = os.path.join(repo, REL)
    if not os.path.exists(path):
        print("FAIL %s: not found (run from the repo root)" % path)
        return 1
    src = open(path, encoding="utf-8", errors="replace").read()
    bad = check_source(src, REL)
    if bad:
        for b in bad:
            print("FAIL " + b)
        return 1
    print("PASS browser_tab_exec_serial (runAcquiredAction's WebView-driving "
          "execute sits under a per-tab mutex)")
    return 0


GOOD = '''    private suspend fun runAcquiredAction(
        input: BrowserActionInput,
        implicitTab: Boolean,
        acquireTabId: Int? = null,
    ): BrowserActionResult {
        val tab = acquireTab(acquireTabId ?: input.tabId)
            ?: return BrowserActionResult.error("Failed to acquire browser tab")
        return try {
            val executed = if (implicitTab) {
                withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {
                    lockForTab(tab.id).withLock { tab.manager.execute(input) }
                }
            } else {
                tab.manager.execute(input)
            }
            val result = executed ?: return BrowserActionResult.error("busy")
            result
        } finally {
            tab.inUse = false
        }
    }

    private suspend fun executeSerialized(tabId: Int, input: BrowserActionInput): BrowserActionResult {
        val mutex = lockForTab(tabId)
        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {
            mutex.withLock {
                runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)
            }
        }
        return result ?: BrowserActionResult.error("busy")
    }
'''

# Pre-fix shape: the implicit path executes with no lock at all.
BAD_NO_LOCK = '''    private suspend fun runAcquiredAction(
        input: BrowserActionInput,
        implicitTab: Boolean,
        acquireTabId: Int? = null,
    ): BrowserActionResult {
        val tab = acquireTab(acquireTabId ?: input.tabId)
            ?: return BrowserActionResult.error("Failed to acquire browser tab")
        return try {
            val result = tab.manager.execute(input)
            result
        } finally {
            tab.inUse = false
        }
    }

    private suspend fun executeSerialized(tabId: Int, input: BrowserActionInput): BrowserActionResult {
        val mutex = lockForTab(tabId)
        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {
            mutex.withLock {
                runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)
            }
        }
        return result ?: BrowserActionResult.error("busy")
    }
'''

# Lock exists but on the wrong thing: inside the implicit arm but not wrapping
# the execute call.
BAD_WRONG_SPAN = '''    private suspend fun runAcquiredAction(
        input: BrowserActionInput,
        implicitTab: Boolean,
        acquireTabId: Int? = null,
    ): BrowserActionResult {
        val tab = acquireTab(acquireTabId ?: input.tabId)
            ?: return BrowserActionResult.error("Failed to acquire browser tab")
        return try {
            if (implicitTab) {
                lockForTab(tab.id).withLock { tab.lastActivityDate = Date() }
                tab.manager.execute(input)
            } else {
                tab.manager.execute(input)
            }
        } finally {
            tab.inUse = false
        }
    }

    private suspend fun executeSerialized(tabId: Int, input: BrowserActionInput): BrowserActionResult {
        val mutex = lockForTab(tabId)
        return mutex.withLock { runAcquiredAction(input, implicitTab = false, acquireTabId = tabId) }
    }
'''


# [fix/meta-audit-m2-binding] the falsifying witness, verbatim: `lockForTab(tabId)`
# stays in the function (bound to a now-dead `val mutex`) while `withLock` moves
# onto a throwaway `Mutex()`. Both magic substrings remain, so the old
# co-occurrence test stayed green — this arm pins the RECEIVER identity.
BAD_MUTEX_RECEIVER = GOOD.replace(
    "        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {\n"
    "            mutex.withLock {",
    "        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {\n"
    "            Mutex().withLock {")

# Same class of defect on the implicit arm: the lock is taken on a fresh
# instance instead of the acquired tab's mutex.
BAD_IMPLICIT_RECEIVER = GOOD.replace(
    "                    lockForTab(tab.id).withLock { tab.manager.execute(input) }",
    "                    Mutex().withLock { tab.manager.execute(input) }")

# Direct-chain form (no local binding) must stay accepted: `lockForTab(id).withLock`.
GOOD_DIRECT_CHAIN = GOOD.replace(
    "        val mutex = lockForTab(tabId)\n"
    "        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {\n"
    "            mutex.withLock {",
    "        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {\n"
    "            lockForTab(tabId).withLock {")

# Lock taken on a non-per-tab factory — same shape as `Mutex()`, different name.
BAD_OTHER_FACTORY = GOOD.replace("            mutex.withLock {",
                                 "            newMutex().withLock {")

# ── [B7-t05] 外部见证五条 + 对照臂一条（逐字复刻报告里的最小补丁）──────────

# 04-1 dataflow: 函数名仍是 lockForTab，实参是与 tab 无关的常量槽位。
BAD_CONST_SLOT = GOOD.replace(
    "                    lockForTab(tab.id).withLock { tab.manager.execute(input) }",
    "                    lockForTab(-1).withLock { tab.manager.execute(input) }")

# 04-2 order: 空锁块（瞬间进出）之后在锁外调用入口。
BAD_EMPTY_LOCK = GOOD.replace(
    "            mutex.withLock {\n"
    "                runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)\n"
    "            }",
    "            mutex.withLock { Unit }\n"
    "            runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)")

# 04-3 dataflow: 绑定仍以 lockForTab( 开头，值被 `.let { Mutex() }` 换掉。
BAD_BINDING_LET = GOOD.replace(
    "        val mutex = lockForTab(tabId)",
    "        val mutex = lockForTab(tabId).let { kotlinx.coroutines.sync.Mutex() }")

# 04-4 parser-death: 注释冒充锁块（Kotlin 编译器忽略注释，门不能当它是锁）。
BAD_COMMENTED_LOCK = GOOD.replace(
    "                    lockForTab(tab.id).withLock { tab.manager.execute(input) }",
    "                    // lockForTab(tab.id).withLock {\n"
    "                    run { tab.manager.execute(input) }\n"
    "                    // }")

# 04-5 spelling: 拆开 `tab.manager.execute(` 的写法 + `implicitTab == true` 绕锚。
BAD_CHAIN_EXEC = GOOD.replace(
    "            val executed = if (implicitTab) {",
    "            if (implicitTab == true) return tab.manager.let { it.execute(input) }\n"
    "            val executed = if (implicitTab) {")

# 04-6 对照臂（修前已被抓，修后必须仍被抓）：标识符接收者绑定到临时 Mutex。
BAD_LOCAL_LOCK = GOOD.replace(
    "        val mutex = lockForTab(tabId)",
    "        val mutex = lockForTab(tabId)\n"
    "        val localLock = kotlinx.coroutines.sync.Mutex()").replace(
    "            mutex.withLock {", "            localLock.withLock {")

# clean：`implicitTab == true` 是同一个决定的等价拼写，锁装对时必须仍绿
# （否则「锚等价拼写」就变成了「锚一种拼写」）。
GOOD_EQUIV_COND = GOOD.replace(
    "            val executed = if (implicitTab) {",
    "            val executed = if (implicitTab == true) {")


def selftest() -> int:
    failures = []
    good = check_source(GOOD, "good.kt")
    bad_nolock = check_source(BAD_NO_LOCK, "bad-nolock.kt")
    bad_span = check_source(BAD_WRONG_SPAN, "bad-span.kt")
    print("selftest good      fixture -> %d violation(s)" % len(good))
    print("selftest bad-nolock fixture -> %d violation(s)" % len(bad_nolock))
    print("selftest bad-span  fixture -> %d violation(s)" % len(bad_span))
    for v in bad_nolock + bad_span:
        print("   " + v)
    if good:
        failures.append("compliant fixture must be clean, got %s" % good)
    if not bad_nolock:
        failures.append("no-lock fixture must red")
    if not any("outside a per-tab mutex scope" in b for b in bad_nolock):
        failures.append("no-lock fixture did not trip the placement rule")
    if not bad_span:
        failures.append("wrong-span fixture must red")
    # executeSerialized stripped of its mutex must red too.
    stripped = GOOD.replace("val mutex = lockForTab(tabId)", "val mutex = kotlinx.coroutines.sync.Mutex()")
    if not check_source(stripped, "x.kt"):
        failures.append("stripped executeSerialized not detected")

    # ── [fix/meta-audit-m2-binding] receiver-identity arms ─────────────────
    arms = [
        # (name, source, expect_red, must_mention)
        ("witness: Mutex().withLock receiver", BAD_MUTEX_RECEIVER, True,
         "`Mutex.withLock`"),
        ("witness: implicit arm on Mutex()", BAD_IMPLICIT_RECEIVER, True,
         "`Mutex.withLock`"),
        ("direct chain lockForTab(id).withLock", GOOD_DIRECT_CHAIN, False, ""),
        ("non-per-tab factory receiver", BAD_OTHER_FACTORY, True,
         "`newMutex.withLock`"),
        # ── [B7-t05] 见证臂：每条都在修前是 BLIND，现在必须恰好红在自己的判据上
        ("witness 04-1 lockForTab(-1) constant slot", BAD_CONST_SLOT, True,
         "not derived from the acquired tab"),
        ("witness 04-2 empty lock block, call outside", BAD_EMPTY_LOCK, True,
         "OUTSIDE its withLock block"),
        ("witness 04-3 binding .let { Mutex() }", BAD_BINDING_LET, True,
         "does not come from lockForTab"),
        ("witness 04-4 commented-out lock braces", BAD_COMMENTED_LOCK, True,
         "the implicit arm takes no lock"),
        ("witness 04-5 tab.manager.let { it.execute }", BAD_CHAIN_EXEC, True,
         "outside a per-tab mutex scope"),
        ("control 04-6 ident bound to a fresh Mutex", BAD_LOCAL_LOCK, True,
         "does not come from lockForTab"),
        ("clean: `if (implicitTab == true)` equivalent spelling", GOOD_EQUIV_COND,
         False, ""),
    ]
    for name, src, expect_red, needle in arms:
        v = check_source(src, name.replace(" ", "-") + ".kt")
        if expect_red:
            if not v:
                failures.append("%s must red" % name)
            elif needle and not any(needle in b for b in v):
                failures.append("%s red but not on the receiver rule: %s" % (name, v))
        else:
            if v:
                failures.append("%s must stay clean, got %s" % (name, v))
        print("selftest %-38s -> %d violation(s)" % (name, len(v)))
        for b in v:
            print("   " + b)

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    # The closing marker is pinned verbatim in the frozen scripts/scan/test_scan.py
    # (DA1016_GATES); growing the arm count must not break that contract, so the
    # arm total is reported on its own line.
    print("SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures)")
    print("SELFTEST_ARMS=PASS (browser_tab_exec_serial: +4 receiver-identity arms)")
    print("BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS (15 arms: 3 shape fixtures + "
          "4 receiver-identity + 6 witness(04-1..04-6) + 1 equivalent-spelling clean arm "
          "+ 1 stripped-mutex arm)")
    return 0


def main() -> int:
    args = [a for a in sys.argv[1:]]
    if "--selftest" in args:
        return selftest()
    repo = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(repo)


if __name__ == "__main__":
    sys.exit(main())
