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

  The unlocked (implicit-tab) entry path takes the ACQUIRED tab's per-tab mutex around
the one call that drives the WebView, and `executeSerialized` takes it before
acquiring. The contract has a third leg the first two rounds never judged —
**which tab, and which mutex instance** ([R3-t03], third round of witness
attacks):

  * `acquireTab(…)` inside `runAcquiredAction` must be able to see the
    `acquireTabId` its caller handed down (W76: dropping it locks tab T while
    driving tab U, and the `implicitTab = false` arm then runs U with no lock);
  * every `runAcquiredAction(…)` call site must state `implicitTab` and that
    constant must equal "does this site hold a `withLock` block" (W77: the
    lock-free implicit dispatch claiming `false` tells the callee nobody needs
    to lock, so the WebView is driven unserialized);
  * `lockForTab(id)` must hand back the SHARED per-id mutex — derived from the
    `Map<…, Mutex>` container, memoized on the id parameter (W78: `= Mutex()`
    turns every lock site into a lock on a private throwaway instance).

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
                         const_bool, const_value, function_body, if_blocks,
                         last_write)
from _kotlin_valueflow import (call_args, contains_ident, expr_end,     # noqa: E402
                               value_sources)

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


# ── [R3-t03] 锁 ↔ 被执行的 tab ↔ 锁实例：三条缝（dual-gate 第三轮）───────
#
# 三条见证都让本门保持绿：W76 锁在 T 而 acquireTab 去拿别的 tab、W77 隐式入口
# 声称「调用方已持锁」而其实没人持、W78 lockForTab 每次返回全新 Mutex。它们
# 的共同点是**本门只看锁的写法，不看锁到底锁住了谁**。判据一律从树派生。

ACQUIRE_CALL = re.compile(r"\bacquireTab\s*\(")
RUN_ACQ_CALL = re.compile(r"\brunAcquiredAction\s*\(")
ACQUIRE_ID_PARAM = "acquireTabId"
MUTEX_MAP_DECL = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=[^\n]*<[^>\n]*\bMutex\b")


def _mutex_map_fields(code: str) -> list:
    """按 id 共享 Mutex 的容器字段（类型注解或构造器里带 `Map`，元素是 Mutex）。

    从树派生而不是写死 `tabLocks`：`ConcurrentHashMap<Int, Mutex>()` /
    `MutableMap<Int, Mutex> = mutableMapOf()` 都是同一个决定点。
    """
    return [m.group(1) for m in MUTEX_MAP_DECL.finditer(code) if "Map" in m.group(0)]

ATOMIC_MEMO = ("getOrPut", "computeIfAbsent", "putIfAbsent")
FRESH_MUTEX = re.compile(r"^(?:[A-Za-z_][\w.]*\.)?Mutex\s*$")


def _named_arg(code: str, arg_spans, name: str):
    """`name = …` 具名实参的表达式区间，或 None。"""
    for a, b in arg_spans:
        m = re.match(r"\s*" + re.escape(name) + r"\s*=(?!=)", code[a:b])
        if m:
            return (a + m.end(), b)
    return None


LOCK_BLOCK = re.compile(r"\.\s*withLock\s*(?:\([^()]*\)\s*)?\{")


def _locked_spans(code: str):
    """全文件每处 `.withLock { … }` 的块区间。

    只认真的跟了 lambda 的调用（`withLock {`），不认 `import ... .withLock`
    这类提及——import 行后面「第一个 `{`」是类体，会得到一个覆盖整个文件的
    假锁区间，把锁外调用判成锁内。
    """
    out = []
    for m in LOCK_BLOCK.finditer(code):
        open_pos = m.end() - 1
        close = match_bracket(code, open_pos)
        if close > 0:
            out.append((open_pos, close + 1))
    return out


def _acquire_identity(code: str, label: str, fn_lo: int, fn_hi: int, params) -> list:
    """W76 — `runAcquiredAction` 拿到的 tab 必须是调用方持锁的那个 tab。

    `executeSerialized` 先 `lockForTab(T)` 再把自己锁住的 T 作为
    `acquireTabId = T` 交下来；本函数若把该形参丢弃（`acquireTab(input.tabId)`），
    锁在 T 上、执行却落在另一个 tab 上，且因为 implicitTab=false 走无锁分支。
    判据按**值来源**：实参的求值必须能看见 `acquireTabId`。
    """
    hits = [m for m in ACQUIRE_CALL.finditer(code) if fn_lo <= m.start() < fn_hi]
    if not hits:
        return ["re-pin: runAcquiredAction no longer calls acquireTab( — the tab it "
                "drives cannot be tied to the mutex its caller holds"]
    if ACQUIRE_ID_PARAM not in params:
        return ["re-pin: runAcquiredAction has no `%s` parameter — the locked tab id "
                "is no longer handed to the acquirer, so the callee re-derives the tab "
                "from the action input and can lock one tab while driving another"
                % ACQUIRE_ID_PARAM]
    out = []
    for m in hits:
        args = call_args(code, m.end() - 1)
        sources = []
        for a, b in args:
            sources.extend(value_sources(code[a:b]))
        if not any(contains_ident(s, ACQUIRE_ID_PARAM) for s in sources):
            out.append(
                "%s:%d: `acquireTab(…)` ignores `%s` — the caller locked one tab and "
                "handed its id down, but the acquisition re-derives the target from the "
                "action input, so the executed tab is not the locked tab (and the "
                "implicitTab=false branch then runs it with no lock at all) (W76)"
                % (label, line_of(code, m.start()), ACQUIRE_ID_PARAM))
    return out


def _implicit_flag_sites(code: str) -> list:
    """W77 — `implicitTab` 的实参必须等于「调用方是否真的持锁」。

    锁的契约有两半，本门此前只判其中一半（锁块必须包住 execute）。另一半是
    **入口的自我声明**：`executeSerialized` 在 `withLock` 里调用并传 false，
    `execute` 的隐式分派在锁外调用并传 true。把锁外那一处改成 false，等于告诉
    被调方「有人持锁」——而没人持锁，execute 直接走无锁分支并发驱动同一个
    WebView。判据从树派生「调用点是否落在某个 withLock 块区间内」，再对着
    常量折叠后的 `implicitTab` 值判，两个方向都判（锁内传 true 会自死锁）。
    """
    spans = _locked_spans(code)
    out = []
    for m in RUN_ACQ_CALL.finditer(code):
        if re.search(r"\bfun\s+$", code[max(0, m.start() - 8):m.start()]):
            continue                                   # 定义行
        pos = m.start()
        line = line_of(code, pos)
        args = call_args(code, m.end() - 1)
        inside = any(lo <= pos < hi for lo, hi in spans)
        hold = "inside a withLock block" if inside else "with no withLock in scope"
        named = _named_arg(code, args, IMPLICIT_IDENT)
        if named is None:
            out.append(
                "runAcquiredAction(…) at line %d is called %s but does not pass an "
                "explicit `implicitTab = …` — whether the callee must take the tab "
                "mutex is left to a default, so the locked/unlocked contract is "
                "unstated (fail-closed)" % (line, hold))
            continue
        expr = code[named[0]:named[1]].strip()
        flag = const_bool(expr)
        if flag is None:
            out.append(
                "runAcquiredAction(…) at line %d passes `%s` (not a constant) as "
                "implicitTab — whether this call site holds the tab mutex must be "
                "decidable from the code (fail-closed)" % (line, expr[:40]))
            continue
        if inside and flag:
            out.append(
                "runAcquiredAction(…) at line %d passes implicitTab = true from INSIDE "
                "a withLock block — the caller already holds the tab mutex and Kotlin's "
                "Mutex is not reentrant, so the callee's own withLock self-deadlocks (W77)"
                % line)
        if (not inside) and (not flag):
            out.append(
                "runAcquiredAction(…) at line %d passes implicitTab = false while "
                "holding NO withLock — the caller does not hold the tab mutex, so the "
                "callee skips its own lock and `tab.manager.execute(` runs unserialized "
                "(ISSUE-03A overlap window) (W77)" % line)
        if inside:
            id_arg = _named_arg(code, args, ACQUIRE_ID_PARAM)
            if id_arg is None:
                out.append(
                    "runAcquiredAction(…) at line %d holds a per-tab mutex but does not "
                    "hand the locked tab id down (`%s = …`) — the callee re-derives the "
                    "tab from the action input and may drive a tab nobody locked "
                    "(fail-closed)" % (line, ACQUIRE_ID_PARAM))
            elif const_value(code[id_arg[0]:id_arg[1]]) is not None:
                out.append(
                    "runAcquiredAction(…) at line %d passes a constant as `%s` — the "
                    "locked tab id is not the id the callee acquires (fail-closed)"
                    % (line, ACQUIRE_ID_PARAM))
    return out


def _returned_exprs(code: str, fb) -> list:
    """函数 `fb` 的返回值表达式文本（块体的每个裸 return，或表达式体本身）。"""
    lo, hi, kind = fb
    body = code[lo:hi]
    if kind == "expr":
        return [body.strip()]
    exprs = []
    for m in re.finditer(r"\breturn\b(?!@)", body):
        end = expr_end(body, m.end())
        piece = body[m.end():end].strip()
        if piece:
            exprs.append(piece)
    if not exprs:
        spans = __import__("_kotlin_ast", fromlist=["stmt_spans"]).stmt_spans(body)
        if spans:
            exprs.append(body[spans[-1][0]:spans[-1][1]].strip())
    return exprs


def _lock_factory_identity(code: str) -> list:
    """W78 — `lockForTab(id)` 必须交回**按 id 共享**的那一个 Mutex。

    本门此前只判 `.withLock` 的接收者「叫 lockForTab」，从不看 lockForTab 自己
    返回什么。`= Mutex()` 于是让每处锁都锁在一个刚 new 出来的实例上：全池
    互斥彻底空转，而本门全绿。判据从树派生容器字段（`Map<…, Mutex>` 声明），
    再要求函数体真的按形参键读写它。
    """
    field = None
    fields = _mutex_map_fields(code)
    if fields:
        field = fields[0]
    if field is None:
        return ["re-pin: the pool no longer declares a `Map<…, Mutex>` of per-tab "
                "mutexes — `lockForTab` cannot be handing one out (fail-closed)"]
    fb = function_body(code, LOCK_FN)
    if fb is None:
        return ["re-pin: `fun lockForTab(` body not found — the per-tab mutex factory "
                "this gate pins is gone (fail-closed)"]
    body = code[fb[0]:fb[1]]
    key = _lock_fn_param(code, fb)
    if not key:
        return ["re-pin: `lockForTab`'s tab-id parameter could not be derived from its "
                "signature (fail-closed)"]
    if not contains_ident(body, field):
        return ["`lockForTab` never touches the per-tab mutex map `%s` — it hands out "
                "a mutex that no other call can ever see, so every lock site serializes "
                "against itself and concurrent actions on one tab still overlap (W78)"
                % field]
    atomic = any(
        re.search(r"\b%s\s*\.\s*%s\s*\(\s*%s\b"
                  % (re.escape(field), name, re.escape(key)), body)
        for name in ATOMIC_MEMO)
    stored = re.search(r"\b%s\s*\[\s*%s\s*\]\s*=(?!=)"
                       % (re.escape(field), re.escape(key)), body) is not None
    if not (atomic or stored):
        return ["`lockForTab` reads `%s` but never memoizes the instance it returns "
                "(no keyed getOrPut/computeIfAbsent/putIfAbsent and no `%s[%s] =` "
                "store) — a mutex that is not written back is a per-call instance, "
                "so the per-tab serialization is a shell (W78)" % (field, field, key)]
    for expr in _returned_exprs(code, fb):
        for src in value_sources(expr):
            naked = strip_parens(peel_expr_tail(src))
            if FRESH_MUTEX.match(naked) and not contains_ident(src, field):
                return ["`lockForTab` can return a freshly constructed `%s` — a new "
                        "Mutex per call is not the per-tab mutex, so two actions on the "
                        "same tab each lock their own instance and still overlap (W78)"
                        % naked]
    return []


def peel_expr_tail(expr: str) -> str:
    """`Mutex()` / `kotlinx.coroutines.sync.Mutex()` 这类构造的裸形态。"""
    e = expr.strip()
    m = re.match(r"^([A-Za-z_][\w.]*)\s*\(", e)
    if m:
        close = match_bracket(e, m.end() - 1)
        if close == len(e) - 1:
            return m.group(1)
    return e


def _lock_fn_param(code: str, fb):
    """`lockForTab` 的形参名（从签名派生，不写死 `id`）。"""
    lo = fb[0]
    head_start = code.rfind("fun ", 0, lo)
    if head_start < 0:
        return None
    open_paren = code.find("(", head_start)
    close = match_bracket(code, open_paren)
    if open_paren < 0 or close < 0:
        return None
    for a, b in split_top(code[open_paren + 1:close], (",",)):
        head = code[open_paren + 1 + a: open_paren + 1 + b].split("=")[0]
        name = head.split(":")[0].strip().split()[-1] if head.split(":")[0].strip() else ""
        if re.fullmatch(r"[A-Za-z_]\w*", name or ""):
            return name
    return None


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

    # ── [R3-t03] 锁到底锁住了谁：三条缝（W76/W77/W78）────────────────────
    for msg in _acquire_identity(code, label, b_lo, b_hi, params):
        bad.append(msg)
    for msg in _implicit_flag_sites(code):
        bad.append("%s: %s" % (label, msg))
    for msg in _lock_factory_identity(code):
        bad.append("%s: %s" % (label, msg))

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


GOOD = '''    private val tabLocks = ConcurrentHashMap<Int, Mutex>()

    private fun lockForTab(id: Int): Mutex = tabLocks.getOrPut(id) { Mutex() }

    suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        return runAcquiredAction(input, implicitTab = true)
    }

    private suspend fun runAcquiredAction(
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

# ── [R3-t03] 第三轮三条缝的逐字见证臂（修前全部 BLIND）──────────────────
LOCK_FN_DEF = "private fun lockForTab(id: Int): Mutex = tabLocks.getOrPut(id) { Mutex() }"

# W76: 丢弃调用方交下来的 locked tab id
BAD_W76_ACQUIRE = GOOD.replace("acquireTab(acquireTabId ?: input.tabId)",
                               "acquireTab(input.tabId)")

# W77: 锁外的隐式分派声称「调用方已持锁」
BAD_W77_IMPLICIT = GOOD.replace("runAcquiredAction(input, implicitTab = true)",
                                "runAcquiredAction(input, implicitTab = false)")

# W78: 每次调用都 new 一个 Mutex（全池互斥空转）
BAD_W78_FACTORY = GOOD.replace(LOCK_FN_DEF, "private fun lockForTab(id: Int): Mutex = Mutex()")

# W78 self-doubt: `tabLocks[id] ?: Mutex()` —— 键对了，但从不写回
BAD_W78_ELVIS = GOOD.replace(LOCK_FN_DEF,
                             "private fun lockForTab(id: Int): Mutex = tabLocks[id] ?: Mutex()")

# clean：显式 store 形态的 memo 化必须仍绿（证明判据不是只认 getOrPut 一种拼写）
GOOD_STORE_MEMO = GOOD.replace(LOCK_FN_DEF, """private fun lockForTab(id: Int): Mutex {
        val existing = tabLocks[id]
        if (existing != null) return existing
        val created = Mutex()
        tabLocks[id] = created
        return created
    }""")


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
        # ── [R3-t03] 第三轮 dual-gate / 对称轴见证（修前三条全 BLIND）
        ("witness W76 acquireTab ignores acquireTabId", BAD_W76_ACQUIRE, True,
         "ignores `acquireTabId`"),
        ("witness W77 implicit entry claims the caller holds the lock",
         BAD_W77_IMPLICIT, True, "passes implicitTab = false while holding NO withLock"),
        ("witness W78 lockForTab returns a fresh Mutex", BAD_W78_FACTORY, True,
         "never touches the per-tab mutex map"),
        ("witness W78s tabLocks[id] ?: Mutex() without a store", BAD_W78_ELVIS, True,
         "never memoizes the instance it returns"),
        ("clean: explicit store-form memoization", GOOD_STORE_MEMO, False, ""),
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
    print("BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS (20 arms: 3 shape fixtures + "
          "4 receiver-identity + 6 witness(04-1..04-6) + 1 equivalent-spelling clean arm "
          "+ 1 stripped-mutex arm + 5 third-round seam arms (W76 acquire identity, "
          "W77 implicit-flag identity, W78 factory, W78s no-store elvis, store-memo clean))")
    return 0


def main() -> int:
    args = [a for a in sys.argv[1:]]
    if "--selftest" in args:
        return selftest()
    repo = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(repo)


if __name__ == "__main__":
    sys.exit(main())
