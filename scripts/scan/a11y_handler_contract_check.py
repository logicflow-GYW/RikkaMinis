#!/usr/bin/env python3
"""CI scan gate — accessibility CLI contract (deep-audit-1017 FIX-G).

Two properties of the host-side a11y surface that neither the compiler nor a
device-free unit test can see, and that both failed silently in exactly this
shape before:

1. `MinisAccessibilityService.captureScreenshot` must hand its result over
   through `LateValueSlot`. The platform's `TakeScreenshotCallback` cannot be
   unregistered, so a callback that lands after the 5 s timeout used to still
   copy a full-screen bitmap (~10-15 MB) into a reference the caller had
   abandoned — one leaked screen-sized allocation per timed-out capture. The
   shape matters, not just the presence of a slot: the callback must ask whether
   the caller is still waiting BEFORE it allocates (a later check would only
   recycle, not avoid, the copy), the timeout branch must close the slot before
   returning, and a refused publish must leave the slot closed.

2. `AccessibilityOffloadHandler` must declare every flag it reads with
   `hasFlag(...)` in its `booleanFlags` set, and `tap text` must report the
   outcome it observed. An undeclared flag is parsed as `--key value`, so the
   search string was swallowed and every `tap text --contains X` died on
   "missing <text>"; and the tap outcome was discarded, so a node that refused
   ACTION_CLICK and a cancelled coordinate tap both answered `ok`.

3. [deep-audit-1025 ISSUE-03] `tap` / `input` / `scroll` must have exactly one
   verb list. Each group used to carry two — the verbs its dispatcher accepts and
   the verbs `statusForSubAction` could name — and all three pairs had drifted,
   so every a11y call fell through to the generic overlay label. The verbs and
   their labels now live together in `A11yStatusLabels`, keyed by the dispatched
   verb; this arm holds the two sets equal in BOTH directions (a dispatched verb
   with no label, and a label for a verb nothing dispatches, are both failures)
   and holds the status branches to rendering through that table instead of
   keeping a second copy.

4. [deep-audit-1030 ISSUE-07 裂缝A] "is our service listed as enabled?" must have
   ONE implementation. `A11yState.isServiceEnabled` kept its own
   `split(':')`/`equals(...)` matcher while `AccessibilityRecoveryManager
   .isRevokedIn` — the predicate the repair path uses — also accepted the
   framework's abbreviated `pkg/.Class` spelling. The settings screen could
   therefore report "disabled" for a service that was bound and healthy, and the
   master switch was consulted on only one of the two paths.

5. [deep-audit-1030 ISSUE-07 裂缝B] The repair-prompt slot must carry an owner
   identity and a lock. `continuation` is published by the waiting coroutine and
   taken by `respond` on the UI thread, while the timeout and cancellation paths
   tear it down; with no owner id a LATE teardown dismissed the prompt a newer
   waiter had just published, leaving that waiter parked for its full timeout.
   (In this build the a11y tools are serial, so two concurrent `awaitDecision`s
   are not reachable — the reachable interleave is `respond` vs. timeout /
   cancellation, which is what these arms pin.)

Losing any anchor FAILS loudly (exit 1) rather than passing silently — a probe
that cannot find its subject must not report success.

Usage:
    python3 scripts/scan/a11y_handler_contract_check.py <repo_root>
    python3 scripts/scan/a11y_handler_contract_check.py --selftest
"""

import os
import re
import sys

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    const_bool,
    const_value,
    match_bracket,
    split_top,
    strip_parens,
    when_branches,
    if_blocks,
)
from _kotlin_valueflow import (
    brace_depth,
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
    returns_exiting,
)

PKG = "src/android/app/src/main/java/com/rikkaminis/app"
SERVICE = f"{PKG}/accessibility/MinisAccessibilityService.kt"
HANDLER = f"{PKG}/sandbox/offload/AccessibilityOffloadHandler.kt"

# Hardware-calibrated budget (the CLI's screenshot deadline). Pinned: a refactor
# may move the code around it, never silently re-tune it.
SCREENSHOT_TIMEOUT = "timeoutMs: Long = 5_000"

# [deep-audit-1025 ISSUE-03] (dispatched group, its dispatch function, its
# status table). The dispatcher is the source of truth for which verbs exist;
# the table is the source of truth for what the capsule says; this gate holds
# them equal.
STATUS_GROUPS = (("tap", "tapSub", "TAP"), ("input", "inputSub", "INPUT"), ("scroll", "scrollSub", "SCROLL"))

MAP_ANCHOR = "val %s: Map<String, String> = mapOf("
MEMBER_STOPS = ("\n    private fun ", "\n    internal fun ", "\n    fun ", "\n}")


def views(raw):
    """(structural, literal) — 两个等长视图，位置可直接互投。

    * structural = `_kotlin_lexer.mask_code`：注释与字符串/字符字面量内部抹成
      空格。所有结构分析（括号配平、条件、函数体）都走这一路，因此字符串里的
      `}` / `"` / `//` 再也不能伪造锚点（parser-death）。
    * literal = `_kotlin_lexer.mask_comments`：只抹注释，字符串内容保留 ——
      `"visible-only"` 这类契约字面量要从这里读。

    词法失败抛 `KotlinLexError`，调用方一律 fail-closed 报违规，不回退原文扫描。
    """
    return code_view(raw), literal_view(raw)


def member_span(text, signature):
    """[signature] 到下一个成员声明（或类闭合括号）的区间；None = 锚点缺失。"""
    start = text.find(signature)
    if start < 0:
        return None
    rest = text[start + len(signature):]
    ends = [i for i in (rest.find(s) for s in MEMBER_STOPS) if i >= 0]
    if not ends:
        return None
    return (start, start + len(signature) + min(ends))


def exits_like(text, start, end):
    """[start, end) 里是否有真正退出外层函数的 `return` / `throw`。

    带块的分支要看块内那一层（`if (x) { return }` 的 return 深度比 if 语句深
    一层），否则 `returns_exiting` 会把它当成 lambda 内的裸 return 而漏判。
    """
    k = start
    while k < len(text) and text[k] in " \t\r\n":
        k += 1
    braced = k < len(text) and text[k] == "{"
    depth = brace_depth(text, k) + (1 if braced else 0)
    if returns_exiting(text, k, end, depth):
        return True
    for m in re.finditer(r"(?<![\w.])throw\b", text[k:end]):
        if brace_depth(text, k + m.start()) == depth:
            return True
    return False


def sole_call(expr, name):
    """[expr] 是否**就是**一次 `….name(...)` 调用（不带任何后缀运算）。

    `n.performAction(ACTION_CLICK) || true` 与 `parsed.take(0)` 都过不了这一关：
    它们的值不再是那个调用的返回值。
    """
    e = strip_parens(expr)
    m = re.search(re.escape(name) + r"\s*\(", e)
    if m is None:
        return False
    return match_bracket(e, m.end() - 1) == len(e) - 1


def guard_decided_by_literal(cond):
    """条件是否被一个常量操作数决定（`false &&` / `|| true` / `1 == 2` / `!true`）。

    与旧版的四元组白名单（`true/false/!true/!false`）不同：这里对每个顶层
    `&&` / `||` 操作数做**常量折叠**，所以任何等价拼写（`1 == 2`、`! true`、
    `0 > 1`、`"a" == "b"`）都折成常量，族洞不再存在。
    """
    c = strip_parens(cond)
    if not c:
        return False
    if const_value(c) is not None:
        return True
    for a, b in split_top(c, ("&&", "||")):
        if const_value(strip_parens(c[a:b])) is not None:
            return True
    return False


def branch_values(text, marker):
    """when 分支里条件含 [marker] 的那些：[(subject, cond, value_expr, pos)]。

    value_expr 是 `->` 之后那条表达式的完整文本（`expr_end` 定界，因此
    `read.value || true`、跨行的 `return err(...)` 都能整段取到）。
    """
    out = []
    for subject, cond, b0, b1 in when_branches(text):
        if marker not in cond:
            continue
        seg = text[b0:b1]
        k = 0
        while k < len(seg) and seg[k] in " \t\r\n":
            k += 1
        out.append((subject, cond, seg[k:expr_end(seg, k)].strip(), b0 + k))
    return out


def slice_body(text, anchor, stop_patterns=("    fun ", "    private fun ", "    internal fun ", "    @")):
    """Slice from `anchor` to the next member declaration (or `@` annotation) at
    member indentation. Returns None when the anchor is absent."""
    start = text.find(anchor)
    if start < 0:
        return None
    end = len(text)
    for i in range(start + len(anchor), len(text)):
        if text[i] != "\n":
            continue
        tail = text[i + 1:]
        if any(tail.startswith(p) for p in stop_patterns):
            end = i
            break
    return text[start:end]


def balanced_call(text, anchor):
    """Slice `anchor(...)` with paren balance. None when absent."""
    start = text.find(anchor)
    if start < 0:
        return None
    open_at = text.index("(", start)
    depth = 0
    for i in range(open_at, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


def scan_service(raw):
    """Findings for MinisAccessibilityService.kt (empty list = clean)."""
    try:
        text, _lv = views(raw)
    except KotlinLexError as exc:
        return [f"MinisAccessibilityService.kt could not be lexed ({exc}) — fail-closed"]
    bad = []
    body = slice_body(text, "fun captureScreenshot(")
    if body is None:
        return ["captureScreenshot() not found — the probe lost its subject"]
    if "resultRef" in body:
        bad.append("captureScreenshot still uses the abandoned resultRef hand-off "
                   "(a late callback parks a screen-sized bitmap nothing will recycle)")
    if "LateValueSlot(" not in body:
        bad.append("captureScreenshot does not hand the result over through LateValueSlot")
    copy = body.find(".copy(Bitmap.Config.ARGB_8888")
    # [B7 13-1 parser-death / family-hole] 判据锚"这个守卫真的活着"，不锚"某处出现过
    # isCallerWaiting() 这几个字"。旧版在 code_only() 上只涂行首注释，于是
    # `if (false) { // !slot.isCallerWaiting()` 把锚点藏在行尾注释里就过关。
    guard = -1
    for cond, t0, t1, _e0, _e1 in if_blocks(body):
        if "isCallerWaiting()" not in cond:
            continue
        c = strip_parens(cond)
        if const_bool(c) is not None:
            continue            # 常量条件 = 死守卫
        if not c.startswith("!"):
            continue            # 极性：等待者还在 → 提前放掉 buffer
        if not exits_like(body, t0, t1):
            continue            # 守卫体必须真的退出
        guard = t0
        break
    if guard < 0:
        bad.append("the caller-waiting check is not a LIVE `if (!slot.isCallerWaiting()) { … return }` "
                   "guard any more (missing, decided by a constant, inverted, or its body no longer "
                   "returns) — a late callback then allocates the whole-screen software copy the "
                   "guard exists to avoid")
    elif copy > 0 and guard > copy:
        bad.append("isCallerWaiting() is checked after the software copy is allocated "
                   "(the 10-15 MB allocation it exists to avoid has already happened)")

    timeout = body.find("if (!done.await(")
    if timeout < 0:
        bad.append("the await(timeout) branch is gone — cannot tell whether the slot is closed")
    else:
        closed = body.find("slot.exit(", timeout)
        returns = body.find("return ", timeout)
        if closed < 0 or (returns > 0 and closed > returns):
            bad.append("the timeout branch returns without closing the slot "
                       "(a frame that already landed stays parked)")
    if SCREENSHOT_TIMEOUT not in body:
        bad.append(f"the calibrated screenshot timeout is gone ({SCREENSHOT_TIMEOUT})")

    slot = slice_body(text, "internal class LateValueSlot", ("internal class ", "class ", "internal object "))
    if slot is None:
        bad.append("LateValueSlot is gone — nothing arbitrates the late callback")
        return bad
    publish = slice_body(slot, "fun publish(")
    if publish is None:
        bad.append("LateValueSlot.publish() is gone")
    else:
        if "getAndSet(" not in publish:
            bad.append("LateValueSlot.publish no longer swaps atomically "
                       "(the caller can be handed a value it already walked away from)")
        if "compareAndSet(" not in publish:
            bad.append("a refused publish no longer clears the slot "
                       "(the payload stays reachable in a slot nobody reads)")
    exit_fn = slice_body(slot, "fun exit(")
    if exit_fn is None:
        bad.append("LateValueSlot.exit() is gone")
    elif "onDiscard(" not in exit_fn:
        bad.append("LateValueSlot.exit no longer hands over what the caller did not take")
    return bad


def declared_flags(text):
    """The literal names of `booleanFlags = setOf(...)` (comment lines skipped)."""
    block = balanced_call(text, "booleanFlags = setOf")
    if block is None:
        return None
    body = "\n".join(l for l in block.split("\n") if not l.strip().startswith("//"))
    return set(re.findall(r'"([^"]+)"', body))


def value_flag_reads(text):
    """Keys the handler reads as a VALUE (`args.get*("key")`), with the one-token
    call body only — `args.get("h", "help")` style lists all land here.

    Note what this deliberately is not: `getBool()` reads `values` too, so a key
    read with getBool IS a value read here. That is the whole point — while
    `--visible-only` was declared boolean, `getBool("visible-only")` was exactly
    the read that always saw "not given"."""
    keys = set()
    for call in re.finditer(r'args\.get(?:Int|Long|Double|Bool)?\(([^)]*)\)', text):
        keys.update(re.findall(r'"([^"]+)"', call.group(1)))
    return keys


SUBJECT_KEY = "visible-only"


def scan_flag_value_split(raw):
    """[deep-audit-1038 A-02] A key declared in `booleanFlags` must not also be
    read as a VALUE. For a declared boolean the parser binds no value: the
    caller's spelling becomes a stray positional, the reader sees "not given",
    and a `?: <default>` folds the request into its own opposite — the one
    combination that cannot fail loudly."""
    try:
        _cv, text = views(raw)
    except KotlinLexError as exc:
        return [f"AccessibilityOffloadHandler.kt could not be lexed ({exc}) — fail-closed"]
    bad = []
    declared = declared_flags(text)
    if declared is None:
        return ["the handle() booleanFlags declaration is gone — cannot judge the value/flag split"]
    reads = value_flag_reads(text)
    if not reads:
        return ["no args.get*() value read found in the handler — the probe lost its subject"]
    conflict = sorted(declared & reads)
    if conflict:
        bad.append(f"key(s) declared boolean AND read as a value: {conflict} "
                   "(OffloadArgs binds no value for a declared boolean, so the caller's "
                   "spelling is dropped and the reader sees 'not given')")
    if SUBJECT_KEY not in reads:
        bad.append(f"--{SUBJECT_KEY} is no longer read as a value — the probe lost its subject")
    return bad


def scan_visible_only_read(raw):
    """[deep-audit-1038 A-02] `uiDump` must read `--visible-only` through the
    three-way classifier: absent/bare keeps the documented default, a spelled
    boolean is honoured, and garbage is REFUSED as a usage error instead of
    being folded into the default (which is what inverted the request)."""
    try:
        cv, lv = views(raw)
    except KotlinLexError as exc:
        return [f"AccessibilityOffloadHandler.kt could not be lexed ({exc}) — fail-closed"]
    bad = []
    span = member_span(lv, "private fun uiDump(")
    if span is None:
        return ["uiDump() not found — the probe lost its subject"]
    body, body_cv = lv[span[0]:span[1]], cv[span[0]:span[1]]
    if 'VisibleOnlyFlag.resolve(args.get("visible-only"))' not in body:
        bad.append("uiDump no longer reads --visible-only as a value through VisibleOnlyFlag.resolve")
    # [B7 13-2 dataflow] 判据锚"三分支的**值**"，不锚一行拼写。旧版查子串
    # `is VisibleOnlyFlag.Read.Value -> read.value`，于是 `read.value || true`
    # 靠前缀命中就过关，`--visible-only=false` 变成 no-op。
    subj = None
    sm = re.search(r"when\s*\(\s*val\s+(\w+)\s*=\s*VisibleOnlyFlag\.resolve\(", body)
    if sm:
        subj = sm.group(1)
    flag_var = None
    fm = re.search(r"val\s+(\w+)\s*=\s*when\s*\(\s*val\s+\w+\s*=\s*VisibleOnlyFlag\.resolve\(", body)
    if fm:
        flag_var = fm.group(1)
    armed = branch_values(body, "VisibleOnlyFlag.Read.Absent")
    valued = branch_values(body, "VisibleOnlyFlag.Read.Value")
    invalid = branch_values(body, "VisibleOnlyFlag.Read.Invalid")
    if not armed:
        bad.append("the absent/bare case is gone — cannot tell whether the documented default (true) survives")
    for _s, _c, expr, _pos in armed:
        if const_bool(expr) is not True:
            bad.append(f"the absent/bare case no longer yields the documented default (true) — it evaluates "
                       f"to `{expr[:40]}`, so a bare `--visible-only` no longer keeps the dump unfiltered")
    if not valued:
        bad.append("a spelled boolean is no longer honoured (the Read.Value branch is gone)")
    for _s, _c, expr, _pos in valued:
        want = f"{subj}.value" if subj else None
        if want is None or strip_parens(expr) != want:
            bad.append(f"the Read.Value branch does not yield the caller's spelling — it evaluates to "
                       f"`{expr[:40]}` instead of `{want or '<resolve-local>.value'}`, so a spelled "
                       f"`--visible-only` value can be overridden in place (dataflow)")
    # [wide-1024 dataflow] The flag is RESOLVED into a local; the walker is what
    # actually consumes it. `walkNode(…, true, …)` keeps the resolve call, the
    # three-way classifier and every other anchor intact while making
    # `--visible-only=false` a no-op.
    if flag_var is None:
        at = body.find("VisibleOnlyFlag.resolve(")
        if at >= 0:
            head = body[max(0, at - 200):at]
            names = re.findall(r"val\s+(\w+)\s*=", head)
            flag_var = names[-1] if names else None
    walks = call_args_of(body_cv, "walkNode")
    if not walks:
        bad.append("uiDump no longer walks the tree through walkNode() — the --visible-only "
                   "consumer cannot be read (fail-closed)")
    elif flag_var is None:
        bad.append("the --visible-only decision is not bound to a local any more — cannot "
                   "tell whether the walker consumes it (fail-closed)")
    else:
        for _at, pargs in walks:
            if len(pargs) < 7:
                bad.append("walkNode() lost its (registry, node, depth, maxDepth, visibleOnly, "
                           "compact, out) shape — re-anchor this criterion (fail-closed)")
                break
            passed = strip_parens(pargs[4].split("=")[-1])
            if passed != flag_var:
                bad.append(f"uiDump passes `{passed}` as walkNode's visibleOnly argument while "
                           f"the flag was resolved into `{flag_var}` — --visible-only is read "
                           f"and then ignored, so the caller's request has no effect")
                break
    if not invalid:
        bad.append("uiDump no longer refuses a malformed --visible-only")
    for _s, _c, expr, _pos in invalid:
        if not expr.startswith("return err("):
            bad.append("a malformed --visible-only no longer returns an error — it is folded into the default again")
        elif "${read.raw}" not in expr:
            bad.append("the refusal no longer echoes the caller's spelling")
        elif "exit = 2" not in expr:
            bad.append("the refusal is no longer a usage error (exit 2)")
    return bad


def scan_handler(raw):
    """Findings for AccessibilityOffloadHandler.kt (empty list = clean)."""
    try:
        cv, lv = views(raw)
    except KotlinLexError as exc:
        return [f"AccessibilityOffloadHandler.kt could not be lexed ({exc}) — fail-closed"]
    text = lv
    bad = []
    declared = declared_flags(text)
    if declared is None:
        bad.append("the handle() booleanFlags declaration is gone")
    else:
        used = set()
        for call in re.finditer(r'hasFlag\(([^)]*)\)', text):
            used.update(re.findall(r'"([^"]+)"', call.group(1)))
        undeclared = sorted(used - declared)
        if undeclared:
            bad.append(f"flag(s) read with hasFlag() but not declared boolean: {undeclared} "
                       "(OffloadArgs consumes the next token as their value)")
    bad += scan_flag_value_split(raw)
    bad += scan_visible_only_read(raw)

    body = slice_body(lv, "private fun tapText(")
    if body is None:
        bad.append("tapText() not found — the probe lost its subject")
        return bad
    # 结构面（字符串已抹空）上取实参，字面量面（字符串保留）上读契约串。
    span = (lv.find("private fun tapText("), lv.find("private fun tapText(") + len(body))
    body_cv = cv[span[0]:span[1]]
    plans = call_args_of(body_cv, "TapTextFlow.plan")
    if not plans:
        bad.append("tapText no longer decides through TapTextFlow.plan "
                   "(the performAction result is discarded again)")
    for _at, pargs in plans:
        # [wide-1024 dataflow] `TapTextFlow.plan(n.isClickable, true)` keeps the
        # seam and throws away the only observation it was built to carry: the
        # stage machine then reports CLICKED for a node that refused the click.
        # [B7 13-3] 只查 `"performAction(" in pargs[1]` 还不够：把调用留住再
        # `|| true` 恒真化，值同样被伪造 —— 实参必须**就是**那次调用的返回值。
        if len(pargs) < 2:
            bad.append("TapTextFlow.plan() lost its (isClickable, selfClickSucceeded) shape — "
                       "cannot tell whether the click outcome is observed (fail-closed)")
        elif not sole_call(pargs[1], "performAction"):
            bad.append(f"tapText feeds TapTextFlow.plan() a FABRICATED self-click outcome "
                       f"(`{pargs[1][:40]}`) — the ACTION_CLICK result must BE the value the "
                       f"stage machine consumes, not a constant or an absorbed/replaced form of it")
    for m in re.finditer(r"\bperformAction\s*\(", body_cv):
        line_start = body_cv.rfind("\n", 0, m.start()) + 1
        prefix = body_cv[line_start:m.start()].strip()
        if prefix == "" or re.fullmatch(r"(?:else|try|\}|\{)", prefix):
            bad.append("tapText performs ACTION_CLICK as a bare statement — its Boolean return "
                       "is discarded, so a refused click is indistinguishable from a landed one")
    if "TapTextFlow.fallbackSucceeded(" not in body_cv:
        bad.append("tapText no longer reads the coordinate tap's own outcome")
    if 'err(args, "ACTION_FAILED"' not in body:
        bad.append("tapText cannot report a failed tap — success is assumed again")
    if "if (n.isClickable) n.performAction(" in body_cv:
        bad.append("tapText discards performAction()'s return value again")
    return bad


def call_args_of(text, name):
    """[(offset, [top-level argument texts])] for every `name(...)` call.

    配平与切片走共享原语（`match_bracket` + `split_top`），因此字符串里的
    `)`（结构视图中已被抹空）与嵌套调用都不会把实参切错。"""
    out = []
    for m in re.finditer(re.escape(name) + r"\s*\(", text):
        open_at = m.end() - 1
        if match_bracket(text, open_at) < 0:
            continue
        spans = call_args(text, open_at)
        if not spans:
            continue
        out.append((m.start(), [text[a:b].strip() for a, b in spans]))
    return out


def member_body(text, signature):
    """Slice from [signature] to the next member declaration or the class's
    closing brace. None when either anchor is missing (fail-closed)."""
    span = member_span(text, signature)
    return text[span[0]:span[1]] if span else None



def dispatched_verbs(text, sub_fn):
    """The `"<verb>" ->` literals of a dispatch block. The `else ->` arm's message
    string does not match: its quote is followed by `)`, not `->`."""
    body = member_body(text, "private fun %s(" % sub_fn)
    if body is None:
        return None
    return {m.group(1) for m in re.finditer(r'"([a-z][a-z0-9-]*)"\s*->', body)}


def table_keys(text, table):
    """Keys of one `val <table>: Map<String, String> = mapOf(...)` literal, with
    paren balance so a following expression cannot leak in."""
    anchor = text.find(MAP_ANCHOR % table)
    if anchor < 0:
        return None
    open_at = text.find("(", anchor)
    depth = 0
    close = -1
    for i in range(open_at, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                close = i
                break
    if close < 0:
        return None
    body = "\n".join(
        l for l in text[open_at:close].split("\n") if not l.strip().startswith("//")
    )
    keys = {m.group(1) for m in re.finditer(r'"([^"]+)"\s+to\s+"[^"]*"', body)}
    return keys or None


def scan_status_tables(raw):
    """Findings for the a11y status labels vs the verbs each group dispatches."""
    try:
        _cv, text = views(raw)
    except KotlinLexError as exc:
        return [f"AccessibilityOffloadHandler.kt could not be lexed ({exc}) — fail-closed"]
    bad = []
    for group, sub_fn, table in STATUS_GROUPS:
        dispatched = dispatched_verbs(text, sub_fn)
        if dispatched is None or not dispatched:
            bad.append(f"{group}: the {sub_fn}() dispatch block is gone — cannot tell which verbs exist")
            continue
        # The status branch must render from the shared table, not from a second
        # hand-written verb list (that second list is what drifted).
        ref = re.search(
            r'"%s"\s*->\s*A11yStatusLabels\.labelFor\(\s*A11yStatusLabels\.(TAP|INPUT|SCROLL)\b' % group,
            text,
        )
        if ref is None:
            bad.append(f"{group}: statusForSubAction no longer renders through A11yStatusLabels.labelFor "
                       "(a second verb table is back — that is the shape that drifted)")
            continue
        named_table = ref.group(1)
        if named_table != table:
            bad.append(f"{group}: the status branch reads A11yStatusLabels.{named_table}, "
                       f"which is not the {table} table this group dispatches")
            continue
        named = table_keys(text, table)
        if named is None or not named:
            bad.append(f"{group}: A11yStatusLabels.{table} could not be read (probe lost its anchor)")
            continue
        for verb in sorted(dispatched - named):
            bad.append(f"{group}: dispatches '{verb}' but A11yStatusLabels.{table} has no label for it "
                       "(the overlay falls back to the generic text)")
        for verb in sorted(named - dispatched):
            bad.append(f"{group}: A11yStatusLabels.{table} names '{verb}' but nothing in {sub_fn}() dispatches it "
                       "(the capsule claims a verb the CLI cannot run)")
    return bad


# ── deep-audit-1030 ISSUE-07 ────────────────────────────────────────────────
# 4. ONE listing predicate. `A11yState.isServiceEnabled` used to carry its own
#    `split(':')`/`equals("<pkg>/<fq class>")` matcher while
#    `AccessibilityRecoveryManager.isRevokedIn` (the repair path) matched the
#    framework's abbreviated `pkg/.Class` form too. Same fact, two matchers, and
#    they drifted: the settings screen could report "disabled" for a service that
#    is bound and healthy. Both halves are asserted (local matcher gone; both
#    readers route through the shared predicate) — a revert of either one is red.
#
# 5. Prompt-slot identity. `continuation` is written by the waiter and read by
#    `respond` (UI thread); the teardown paths (timeout, cancellation) used to
#    clear it unconditionally, so a LATE teardown could dismiss the dialog a
#    newer waiter had just published — leaving that waiter parked for its whole
#    timeout. The token + lock shape is asserted here; see the gate's KDoc note
#    on why the concurrency itself is not reachable in this build.
# ────────────────────────────────────────────────────────────────────────────

A11Y_STATE = f"{PKG}/accessibility/A11yState.kt"
A11Y_RECOVERY = f"{PKG}/accessibility/AccessibilityRecoveryManager.kt"

# The removed local matcher, named so a revert reads as red.
LOCAL_MATCH_ANCHORS = ("split(':')", "equals(expected")
SHARED_JUDGE = "AccessibilityRecoveryManager.isRevokedIn("
MASTER_SWITCH = "A11yState.isMasterSwitchOn("
SLOT_DECL = "var continuation: Continuation<Decision>?"
SLOT_LOCK_DECL = "private val slotLock = Any()"
SLOT_RELEASE = "_pendingPrompt.value = null"
SLOT_RELEASE_WINDOW = 8
SLOT_STOPS = ("    suspend fun ", "    fun ", "    private fun ", "    internal fun ", "    @")


def scan_judge_single_source(state_raw, recovery_raw):
    """Findings for the listing predicate being single-sourced (empty = clean)."""
    bad = []
    try:
        state_cv, state_lv = views(state_raw)
        _rcv, recovery_lv = views(recovery_raw)
    except KotlinLexError as exc:
        return [f"accessibility listing sources could not be lexed ({exc}) — fail-closed"]
    body = slice_body(state_lv, "fun isServiceEnabled(")
    if body is None:
        bad.append("A11yState.isServiceEnabled() not found — the probe lost its subject")
    else:
        span = (state_lv.find("fun isServiceEnabled("),
                state_lv.find("fun isServiceEnabled(") + len(body))
        body_cv = state_cv[span[0]:span[1]]
        if SHARED_JUDGE not in body:
            bad.append("A11yState.isServiceEnabled no longer routes through "
                       "AccessibilityRecoveryManager.isRevokedIn — the listing predicate is "
                       "implemented a second time, which is exactly the shape that drifted")
        # [B7 13-4 family-hole] 判据锚"返回值的极性"，不锚"某个函数名出现过"。
        # isGrantRevoked 回答的是 "被吊销了吗"，isServiceEnabled 回答的是 "启用了吗"：
        # 少了 `!` 就是语义反转，而 `SHARED_JUDGE` 子串命中完全看不出来。
        polarity_ok = False
        for m in re.finditer(r"(?<![\w.])return\b", body_cv):
            e = strip_parens(body_cv[m.end():expr_end(body_cv, m.end())]).strip()
            if not contains_ident(e, "isRevokedIn"):
                continue
            if e.startswith("!") and sole_call(e[1:].strip(), "isRevokedIn"):
                polarity_ok = True
        if SHARED_JUDGE in body and not polarity_ok:
            bad.append("A11yState.isServiceEnabled does not RETURN the negation of "
                       "AccessibilityRecoveryManager.isRevokedIn — the predicate answers "
                       "`revoked`, so without the `!` (or with the value absorbed into `||`/`&&`) "
                       "a healthy, bound service reads as disabled and an ungranted one as enabled")
        for anchor in LOCAL_MATCH_ANCHORS:
            if anchor in body:
                bad.append(f"A11yState.isServiceEnabled carries a local entry matcher ({anchor!r}) "
                           "— the fully-qualified-only spelling misses `pkg/.Class`, so a healthy "
                           "service reads as disabled while the repair path says otherwise")
        if "isMasterSwitchOn(" not in body:
            bad.append("A11yState.isServiceEnabled no longer consults the accessibility master "
                       "switch (a service bound with accessibility globally off receives nothing)")

    recovery = recovery_lv
    body = slice_body(recovery, "fun isGrantRevoked(")
    if body is None:
        bad.append("AccessibilityRecoveryManager.isGrantRevoked() not found — the probe lost its subject")
    elif MASTER_SWITCH not in body:
        bad.append("isGrantRevoked does not short-circuit on A11yState.isMasterSwitchOn — with "
                   "accessibility globally off it reports a healthy grant while the service "
                   "receives no events (the drift this single-source rule exists to stop)")
    return bad


def release_guard_gaps(text):
    """Every release of the prompt slot must sit under a LIVE token comparison.

    Decomposition-independent on purpose: the family lesson (see
    `claim_release_guard`) is that a shared run-state flag loses its ownership
    guard the moment a SECOND release path is written without one, so the
    criterion is attached to the write itself, wherever it lives.

    `text` must be the structural view (comments/strings masked).

    [B7 13-5 family-hole] 旧版只用四元组字面量白名单（`true/false/!true/!false`）
    判"守卫是否被常量决定"，于是 `1 == 2 && promptToken != token` 整条守卫恒假却
    仍是绿的。现在每个顶层 `&&`/`||` 操作数都做常量折叠，并且要求守卫体**真的
    退出**（`if (promptToken != token) Unit` 保留了比较却什么也不拒绝）。
    """
    lines = text.split("\n")
    hits = [m.start() for m in re.finditer(re.escape(SLOT_RELEASE), text)]
    if not hits:
        return [f"the prompt slot has no release site (`{SLOT_RELEASE}` is gone) — the probe "
                "lost its subject"]
    bad = []
    for pos in hits:
        line_no = text.count("\n", 0, pos) + 1
        line_start = text.rfind("\n", 0, pos) + 1
        starts = [line_start]
        for _ in range(SLOT_RELEASE_WINDOW):
            k = text.rfind("\n", 0, starts[-1] - 1)
            if k < 0:
                break
            starts.append(k + 1)
        win_start = starts[-1]
        win = text[win_start:pos]
        here = pos - win_start
        candidates = []
        for cond, t0, t1, _e0, _e1 in if_blocks(win):
            if "promptToken" not in cond:
                continue
            if t1 > here:
                continue                      # 必须在该释放点之前结束
            if brace_depth(win, t0) != brace_depth(win, here):
                continue                      # 同块层：早退守卫，不是别的块里的
            candidates.append((t0, cond, t0, t1))
        if not candidates:
            bad.append(f"the slot release (`{SLOT_RELEASE}`, line {line_no}) is not "
                       "ownership-guarded — a late teardown can dismiss the prompt a NEWER "
                       "waiter is showing")
            continue
        t0, cond, b0, b1 = max(candidates, key=lambda c: c[0])
        if guard_decided_by_literal(cond):
            bad.append(f"the slot release's ownership guard is decided by a literal "
                       f"(`{cond.strip()}`, line {line_no}) — the token comparison can "
                       f"never reject a late teardown, so a stale watcher clears the slot a "
                       f"NEWER waiter owns")
            continue
        if not exits_like(win, b0, b1):
            bad.append(f"the slot release's ownership guard does not EXIT "
                       f"(`if ({cond.strip()})` at line {line_no}) — the comparison is there but "
                       f"nothing is rejected, so a stale teardown still clears the slot a NEWER "
                       f"waiter owns")
    return bad



def scan_slot_identity(recovery_raw):
    """Findings for the repair-prompt slot's identity + lock (empty = clean)."""
    try:
        text, _lv = views(recovery_raw)
    except KotlinLexError as exc:
        return [f"AccessibilityRecoveryManager.kt could not be lexed ({exc}) — fail-closed"]
    bad = []
    decl = next((l for l in text.split("\n") if SLOT_DECL in l), None)
    if decl is None:
        bad.append(f"the repair-prompt slot ({SLOT_DECL}) is gone — nothing arbitrates the dialog")
    elif "@Volatile" not in decl:
        bad.append("the prompt slot is no longer @Volatile — `respond` reads it on the UI thread "
                   "while the waiter writes it on the agent's")
    if SLOT_LOCK_DECL not in text:
        bad.append(f"the prompt slot has no lock ({SLOT_LOCK_DECL}) — publish / cleanup / respond "
                   "can interleave")

    await_body = slice_body(text, "private suspend fun awaitDecision(", SLOT_STOPS)
    if await_body is None:
        bad.append("awaitDecision() not found — the probe lost its subject")
    else:
        claim = "++promptToken" in await_body or "promptToken += 1" in await_body
        if not claim:
            bad.append("awaitDecision no longer claims a prompt token — with no owner id a late "
                       "teardown from an expired waiter can clear a NEWER waiter's prompt")
        elif "synchronized(slotLock) { ++promptToken }" not in await_body:
            bad.append("awaitDecision claims its token outside the lock")
        if claim and "val accepted = synchronized(slotLock) {" not in await_body:
            bad.append("awaitDecision publishes the slot without the lock — the publish can race "
                       "`respond` and a cleanup")
        if "invokeOnCancellation" not in await_body:
            bad.append("awaitDecision dropped invokeOnCancellation — a cancelled caller leaves the "
                       "dialog on screen and the slot owned by nobody")
        elif "clearSlotIfOwner(token)" not in await_body:
            bad.append("awaitDecision's teardown is not token-guarded (expected "
                       "`clearSlotIfOwner(token)`) — it must clear the slot only while it still "
                       "owns it")

    bad.extend(release_guard_gaps(text))

    respond_body = slice_body(text, "fun respond(", SLOT_STOPS)
    if respond_body is None:
        bad.append("respond() not found — the probe lost its subject")
    else:
        if "runCatching" not in respond_body:
            bad.append("respond resumes without runCatching — an already-cancelled continuation "
                       "throws straight through the UI callback")
        if "val taken = synchronized(slotLock) {" not in respond_body:
            bad.append("respond takes the slot outside the lock — it can interleave with a "
                       "cleanup and hand the decision to the wrong waiter")
    return bad


def selftest():
    """Negative controls: each section must reject its own mutated fixture and
    accept the clean one. A gate that cannot fail is not a gate."""
    clean_service = (
        "    fun captureScreenshot(displayId: Int = 0, timeoutMs: Long = 5_000): ShotResult {\n"
        "        val slot = LateValueSlot(timedOut) { late -> late.bitmap?.recycle() }\n"
        "        takeScreenshot(displayId, executor, object : TakeScreenshotCallback {\n"
        "            override fun onSuccess(screenshot: ScreenshotResult) {\n"
        "                if (!slot.isCallerWaiting()) return\n"
        "                val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)\n"
        "                slot.publish(ShotResult(sw))\n"
        "            }\n"
        "        })\n"
        "        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {\n"
        "            slot.exit(timedOut)\n"
        "            return timedOut\n"
        "        }\n"
        "        slot.exit(result)\n"
        "        return result\n"
        "    }\n"
        "\n"
        "    fun setNodeText(\n"
        "\n"
        "internal class LateValueSlot<T : Any>(private val fallback: T, private val onDiscard: (T) -> Unit) {\n"
        "    fun publish(value: T): Boolean {\n"
        "        val displaced = ref.getAndSet(value)\n"
        "        if (displaced == null) {\n"
        "            ref.compareAndSet(value, null)\n"
        "            onDiscard(value)\n"
        "            return false\n"
        "        }\n"
        "        return true\n"
        "    }\n"
        "    fun exit(taken: T?): T? {\n"
        "        val leftover = ref.getAndSet(null)\n"
        "        if (leftover != null && leftover !== taken) onDiscard(leftover)\n"
        "        return leftover\n"
        "    }\n"
        "}\n"
    )
    clean_handler = (
        "fun handle() {\n"
        "    val args = OffloadArgs(argv, booleanFlags = setOf(\"help\", \"contains\"))\n"
        "    if (args.hasFlag(\"help\")) return ok(args)\n"
        "    if (args.hasFlag(\"contains\")) return tapText(args)\n"
        "}\n"
        "\n"
        "    private fun tapText(args: OffloadArgs): NativeOffloadResult {\n"
        "        if (TapTextFlow.plan(n.isClickable, n.performAction(ACTION_CLICK)) == TapTextFlow.Stage.CLICKED) {\n"
        "            return ok(args, JSONObject())\n"
        "        }\n"
        "        val tapped = tapXYRaw(svc, r.centerX(), r.centerY(), args)\n"
        "        if (TapTextFlow.fallbackSucceeded(tapped.exitCode)) return tapped\n"
        "        return err(args, \"ACTION_FAILED\", \"no tap\")\n"
        "    }\n"
        "\n"
        "    private fun uiDump(args: OffloadArgs): NativeOffloadResult {\n"
        "        val depth = args.getInt(\"depth\") ?: 10\n"
        "        val visibleOnly = when (val read = VisibleOnlyFlag.resolve(args.get(\"visible-only\"))) {\n"
        "            VisibleOnlyFlag.Read.Absent -> true\n"
        "            is VisibleOnlyFlag.Read.Value -> read.value\n"
        "            is VisibleOnlyFlag.Read.Invalid -> return err(args, \"INVALID_ARGS\", \"bad ${read.raw}\", exit = 2)\n"
        "        }\n"
        "        for (root in svc.rootNodes()) {\n"
        "            walkNode(svc.nodeRegistry, root, 0, depth, visibleOnly, true, arr)\n"
        "        }\n"
        "        return ok(args, JSONObject())\n"
        "    }\n"
        "\n"
        "    private fun findByTextOrDesc() {}\n"
    )

    def _rows(entries):
        return "".join('        "%s" to "%s",\n' % (k, v) for k, v in entries)

    def _table(name, entries):
        return "    val %s: Map<String, String> = mapOf(\n%s    )\n\n" % (name, _rows(entries))

    def _dispatch(sub_fn, verbs, help_const, group):
        rows = "".join('            "%s" -> act(args)\n' % v for v in verbs)
        return (
            "    private fun %s(args: OffloadArgs): NativeOffloadResult {\n"
            "        return when (args.positional.getOrNull(1)) {\n"
            "            null   -> NativeOffloadResult(2, %s)\n"
            "%s"
            '            else   -> NativeOffloadResult(2, "$TOOL %s: unknown action\\n$%s")\n'
            "        }\n"
            "    }\n\n"
        ) % (sub_fn, help_const, rows, group, help_const)

    def status_fixture():
        """A miniature of the shipped shape: the three tables, the three dispatch
        blocks, and a statusForSubAction that renders from the tables."""
        return (
            "internal object A11yStatusLabels {\n"
            + _table("TAP", [("node", "a11y_cli: tap node %s"), ("xy", "a11y_cli: tap at (%s)"),
                             ("text", 'a11y_cli: tap \\"%s\\"'), ("id", "a11y_cli: tap #%s")])
            + _table("INPUT", [("text", "a11y_cli: input text"), ("clear", "a11y_cli: clearing input"),
                               ("key", "a11y_cli: input key %s")])
            + _table("SCROLL", [("node", "a11y_cli: scroll node %s"), ("xy", "a11y_cli: scroll at (%s)"),
                                ("to-text", 'a11y_cli: scrolling to \\"%s\\"')])
            + "}\n\n"
            + _dispatch("tapSub", ["node", "xy", "text", "id"], "TAP_HELP", "tap")
            + _dispatch("inputSub", ["text", "clear", "key"], "INPUT_HELP", "input")
            + _dispatch("scrollSub", ["node", "xy", "to-text"], "SCROLL_HELP", "scroll")
            + "    private fun statusForSubAction(sub: String, verb: String?, args: OffloadArgs): String {\n"
              "        val v = verb ?: \"\"\n"
              "        return when (sub) {\n"
              '            "tap" -> A11yStatusLabels.labelFor(A11yStatusLabels.TAP, v, null)\n'
              '                ?: "a11y_cli: tap"\n'
              '            "input" -> A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, v, null)\n'
              '                ?: "a11y_cli: input"\n'
              '            "scroll" -> A11yStatusLabels.labelFor(A11yStatusLabels.SCROLL, v, null)\n'
              '                ?: "a11y_cli: scroll"\n'
              "        }\n"
              "    }\n"
        )

    clean_status = status_fixture()

    # ── deep-audit-1030 ISSUE-07: one listing predicate, one owned slot ──
    clean_judge_state = (
        "object A11yState {\n"
        "    fun isServiceEnabled(context: Context): Boolean {\n"
        "        if (!isMasterSwitchOn(context)) return false\n"
        "        val enabled = Settings.Secure.getString(\n"
        "            context.contentResolver,\n"
        "            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,\n"
        "        ) ?: return false\n"
        "        return !AccessibilityRecoveryManager.isRevokedIn(\n"
        "            enabled,\n"
        "            context.packageName,\n"
        "            MinisAccessibilityService::class.java.name,\n"
        "        )\n"
        "    }\n"
        "\n"
        "    fun isMasterSwitchOn(context: Context): Boolean {\n"
        "        return true\n"
        "    }\n"
        "}\n"
    )
    clean_judge_recovery = (
        "object AccessibilityRecoveryManager {\n"
        "    fun isGrantRevoked(context: Context): Boolean {\n"
        "        if (A11yState.isMasterSwitchOn(context)) {\n"
        "            return isRevokedIn(\n"
        "                enabledValue = raw,\n"
        "                pkg = context.packageName,\n"
        "                serviceClass = MinisAccessibilityService::class.java.name,\n"
        "            )\n"
        "        } else {\n"
        "            return true\n"
        "        }\n"
        "    }\n"
        "\n"
        "    fun isRevokedIn(enabledValue: String?, pkg: String, serviceClass: String): Boolean = true\n"
        "}\n"
    )
    clean_slot = (
        "object AccessibilityRecoveryManager {\n"
        "    private val slotLock = Any()\n"
        "\n"
        "    @Volatile private var continuation: Continuation<Decision>? = null\n"
        "\n"
        "    private var promptToken: Long = 0L\n"
        "\n"
        "    private suspend fun awaitDecision(prompt: RepairPrompt): Decision {\n"
        "        val token = synchronized(slotLock) { ++promptToken }\n"
        "        val decision = withTimeoutOrNull(PROMPT_TIMEOUT_MS) {\n"
        "            suspendCancellableCoroutine<Decision> { cont ->\n"
        "                val accepted = synchronized(slotLock) {\n"
        "                    if (promptToken != token) {\n"
        "                        false\n"
        "                    } else {\n"
        "                        continuation = cont\n"
        "                        true\n"
        "                    }\n"
        "                }\n"
        "                if (!accepted) {\n"
        "                    runCatching { cont.resume(Decision.CANCEL) }\n"
        "                    return@suspendCancellableCoroutine\n"
        "                }\n"
        "                cont.invokeOnCancellation { clearSlotIfOwner(token) }\n"
        "            }\n"
        "        }\n"
        "        if (decision == null) {\n"
        "            clearSlotIfOwner(token)\n"
        "        }\n"
        "        return decision ?: Decision.CANCEL\n"
        "    }\n"
        "\n"
        "    private fun clearSlotIfOwner(token: Long) {\n"
        "        synchronized(slotLock) { clearSlotLocked(token) }\n"
        "    }\n"
        "\n"
        "    private fun clearSlotLocked(token: Long): Continuation<Decision>? {\n"
        "        if (promptToken != token) return null\n"
        "        val taken = continuation\n"
        "        continuation = null\n"
        "        _pendingPrompt.value = null\n"
        "        return taken\n"
        "    }\n"
        "\n"
        "    fun respond(decision: Decision) {\n"
        "        val taken = synchronized(slotLock) {\n"
        "            val token = ++promptToken\n"
        "            clearSlotLocked(token)\n"
        "        }\n"
        "        runCatching { taken?.resume(decision) }\n"
        "    }\n"
        "}\n"
    )

    cases = [
        ("service/clean", clean_service),
        ("service/resultRef is back", clean_service.replace(
            'val slot = LateValueSlot(timedOut) { late -> late.bitmap?.recycle() }',
            'val resultRef = AtomicReference(ShotResult(null, "TIMEOUT", ""))').replace(
            "slot.publish(ShotResult(sw))", "resultRef.set(ShotResult(sw))")),
        ("service/guard after the copy", clean_service.replace(
            "                if (!slot.isCallerWaiting()) return\n                val sw",
            "                val sw")),
        ("service/timeout does not close the slot", clean_service.replace("            slot.exit(timedOut)\n", "")),
        ("service/publish keeps the refused value", clean_service.replace(
            "            ref.compareAndSet(value, null)\n", "")),
        ("service/exit stops handing over", clean_service.replace(
            "        if (leftover != null && leftover !== taken) onDiscard(leftover)\n", "")),
        ("handler/clean", clean_handler),
        ("handler/undeclared contains", clean_handler.replace('"help", "contains"', '"help"')),
        ("handler/tapText assumes ok", clean_handler.replace(
            "        if (TapTextFlow.fallbackSucceeded(tapped.exitCode)) return tapped\n"
            "        return err(args, \"ACTION_FAILED\", \"no tap\")\n",
            "        return tapped\n")),
        # ── wide-1024 witness arms (each was green before this branch) ──
        # the seam survives, the observation it carries does not.
        ("handler/tapText fabricates the self-click outcome", clean_handler.replace(
            "TapTextFlow.plan(n.isClickable, n.performAction(ACTION_CLICK))",
            "TapTextFlow.plan(n.isClickable, true)")),
        # the flag is resolved correctly, the walker ignores it.
        ("handler/uiDump hardcodes the walker's visibleOnly", clean_handler.replace(
            "walkNode(svc.nodeRegistry, root, 0, depth, visibleOnly, true, arr)",
            "walkNode(svc.nodeRegistry, root, 0, depth, true, true, arr)")),
        ("handler/discarded performAction", clean_handler.replace(
            "        if (TapTextFlow.plan(n.isClickable, n.performAction(ACTION_CLICK)) == TapTextFlow.Stage.CLICKED) {\n"
            "            return ok(args, JSONObject())\n"
            "        }\n",
            "        if (n.isClickable) n.performAction(ACTION_CLICK)\n")),
        # ── --visible-only: a declared boolean read as a value (deep-audit-1038 A-02) ──
        ("handler/boolean flag is also read as a value", clean_handler.replace(
            'booleanFlags = setOf("help", "contains")',
            'booleanFlags = setOf("help", "contains", "visible-only")')),
        ("handler/--visible-only read disappears", clean_handler.replace(
            'args.get("visible-only")', 'args.get("mode")')),
        ("handler/uiDump drops the value seam", clean_handler.replace(
            'VisibleOnlyFlag.resolve(args.get("visible-only"))', 'args.getBool("visible-only")')),
        ("handler/uiDump folds a malformed value into the default", clean_handler.replace(
            'is VisibleOnlyFlag.Read.Invalid -> return err(args, "INVALID_ARGS", "bad ${read.raw}", exit = 2)',
            'is VisibleOnlyFlag.Read.Invalid -> true')),
        ("handler/uiDump reports the wrong exit code", clean_handler.replace(
            "exit = 2", "exit = 1")),
        ("handler/value-read/clean", clean_handler.replace(
            'args.getInt("depth") ?: 10', 'args.getInt("duration") ?: 10')),
        # ── status labels vs dispatched verbs (deep-audit-1025 ISSUE-03) ──
        ("status/clean", clean_status),
        # A dispatched verb with no label: the overlay falls back to generic text.
        ("status/tap xy dispatches, table cannot name it", clean_status.replace(
            '        "xy" to "a11y_cli: tap at (%s)",\n', "")),
        ("status/input key dispatches, table cannot name it", clean_status.replace(
            '        "key" to "a11y_cli: input key %s",\n', "")),
        ("status/scroll to-text dispatches, table cannot name it", clean_status.replace(
            '        "to-text" to "a11y_cli: scrolling to \\"%s\\"",\n', "")),
        # The reverse direction: the capsule claims a verb the CLI cannot run —
        # exactly the shipped bug (`forward`/`set`/`by-text`).
        ("status/table names a verb nothing dispatches", clean_status.replace(
            '        "to-text" to "a11y_cli: scrolling to \\"%s\\"",\n',
            '        "to-text" to "a11y_cli: scrolling to \\"%s\\"",\n'
            '        "forward" to "a11y_cli: scroll forward",\n')),
        ("status/table reads the wrong group's table", clean_status.replace(
            "            \"tap\" -> A11yStatusLabels.labelFor(A11yStatusLabels.TAP, v, null)\n",
            "            \"tap\" -> A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, v, null)\n")),
        ("status/second hand-written table is back", clean_status.replace(
            '            "tap" -> A11yStatusLabels.labelFor(A11yStatusLabels.TAP, v, null)\n',
            '            "tap" -> when (v) {\n'
            '                "by-text" -> "a11y_cli: tap \\"Login\\""\n'
            '                else -> "a11y_cli: tap"\n'
            "            }\n")),
        ("status/table literal lost", clean_status.replace(_table("SCROLL", [
            ("node", "a11y_cli: scroll node %s"), ("xy", "a11y_cli: scroll at (%s)"),
            ("to-text", 'a11y_cli: scrolling to \\"%s\\"')]), "")),
        # ── one listing predicate (deep-audit-1030 ISSUE-07 裂缝A) ──
        ("judge/clean", (clean_judge_state, clean_judge_recovery)),
        # The pre-fix shape: the screen keeps its own fully-qualified-only matcher.
        ("judge/state keeps its own entry matcher", (clean_judge_state.replace(
            "        return !AccessibilityRecoveryManager.isRevokedIn(\n",
            '        val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"\n'
            "        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }\n"), clean_judge_recovery)),
        ("judge/state drops the master switch", (clean_judge_state.replace(
            "        if (!isMasterSwitchOn(context)) return false\n", ""), clean_judge_recovery)),
        ("judge/arm drops the master-switch short circuit", (clean_judge_state, clean_judge_recovery.replace(
            "if (A11yState.isMasterSwitchOn(context)) {", "if (true) {"))),
        # ── prompt-slot identity + lock (deep-audit-1030 ISSUE-07 裂缝B) ──
        ("slot/clean", clean_slot),
        ("slot/loses @Volatile", clean_slot.replace(
            "@Volatile private var continuation", "private var continuation")),
        ("slot/teardown is unconditional again", clean_slot.replace(
            "        if (promptToken != token) return null\n", "")),
        ("slot/respond resumes without runCatching", clean_slot.replace(
            "runCatching { taken?.resume(decision) }", "taken?.resume(decision)")),
        ("slot/never claims a token", clean_slot.replace(
            "val token = synchronized(slotLock) { ++promptToken }", "val token = 0L")),
        ("slot/drops invokeOnCancellation", clean_slot.replace(
            "                cont.invokeOnCancellation { clearSlotIfOwner(token) }\n", "")),
        ("slot/publishes without the lock", clean_slot.replace(
            "                val accepted = synchronized(slotLock) {", "                val accepted = run {")),
        # ── wide-1024: the ownership comparison keeps its shape and loses its power ──
        ("slot/guard short-circuited by a literal", clean_slot.replace(
            "        if (promptToken != token) return null\n",
            "        if (false && promptToken != token) return null\n")),
        ("slot/respond outside the lock", clean_slot.replace(
            "        val taken = synchronized(slotLock) {", "        val taken = run {")),
    ]

    print("a11y_handler_contract_check --selftest")
    failures = 0
    status_failures = 0
    judge_failures = 0
    slot_failures = 0
    for name, fixture in cases:
        expect_clean = name.endswith("/clean")
        if name.startswith("service/"):
            bad = scan_service(fixture)
        elif name.startswith("status/"):
            bad = scan_status_tables(fixture)
        elif name.startswith("judge/"):
            bad = scan_judge_single_source(*fixture)
        elif name.startswith("slot/"):
            bad = scan_slot_identity(fixture)
        else:
            bad = scan_handler(fixture)
        ok = (not bad) if expect_clean else bool(bad)
        print(f"  {'✅' if ok else '❌'} {name} → {'clean' if not bad else '; '.join(bad)[:90]}")
        if not ok:
            failures += 1
            if name.startswith("status/"):
                status_failures += 1
            elif name.startswith("judge/"):
                judge_failures += 1
            elif name.startswith("slot/"):
                slot_failures += 1
    print(f"  {'✅ selftest passed' if not failures else f'❌ selftest failures: {failures}'}")
    # ── A-02: the conflict arm must isolate the offending key, not smear ──
    conflict = clean_handler.replace(
        'booleanFlags = setOf("help", "contains")',
        'booleanFlags = setOf("help", "contains", "visible-only")')
    found = scan_flag_value_split(conflict)
    a02_ok = len(found) == 1 and "visible-only" in found[0]
    print(f"  {'✅' if a02_ok else '❌'} conflict arm isolates --visible-only (exactly one finding)"
          if a02_ok else f"  ❌ conflict arm did not isolate --visible-only → {found}")
    if not a02_ok:
        failures += 1

    # ── B7 见证臂：每条变异都必须恰好摔在自己那条判据上 ────────────────────
    # (名字, fixture, 扫描函数, 期望出现在报错里的判据片段)
    witness_arms = [
        # 13-1: 锚点藏在行尾注释里（parse") 缺陷：旧 code_only 只涂行首注释。
        ("b7/service guard hidden in a trailing comment",
         clean_service.replace(
             "                if (!slot.isCallerWaiting()) return\n",
             "                if (false) { // !slot.isCallerWaiting()\n"),
         scan_service, "LIVE"),
        ("b7/service guard absorbed by a constant",
         clean_service.replace(
             "                if (!slot.isCallerWaiting()) return\n",
             "                if (!slot.isCallerWaiting() || true) return\n"),
         scan_service, "LIVE"),
        ("b7/service guard body no longer returns",
         clean_service.replace(
             "                if (!slot.isCallerWaiting()) return\n",
             "                if (!slot.isCallerWaiting()) { }\n"),
         scan_service, "LIVE"),
        # 13-2: 三分支的**值**被改写（`|| true`），拼写全对。
        ("b7/handler Read.Value absorbed",
         clean_handler.replace(
             "is VisibleOnlyFlag.Read.Value -> read.value",
             "is VisibleOnlyFlag.Read.Value -> read.value || true"),
         scan_handler, "Read.Value branch does not yield the caller's spelling"),
        ("b7/handler Absent default inverted",
         clean_handler.replace(
             "VisibleOnlyFlag.Read.Absent -> true",
             "VisibleOnlyFlag.Read.Absent -> false"),
         scan_handler, "documented default"),
        # 13-3: 调用还在，值被恒真化。
        ("b7/handler plan() outcome absorbed",
         clean_handler.replace(
             "TapTextFlow.plan(n.isClickable, n.performAction(ACTION_CLICK))",
             "TapTextFlow.plan(n.isClickable, n.performAction(ACTION_CLICK) || true)"),
         scan_handler, "FABRICATED self-click outcome"),
        # 13-4: 共享谓词还在，极性被反转。
        ("b7/judge polarity dropped",
         (clean_judge_state.replace(
             "        return !AccessibilityRecoveryManager.isRevokedIn(\n",
             "        return AccessibilityRecoveryManager.isRevokedIn(\n"), clean_judge_recovery),
         lambda t: scan_judge_single_source(*t), "does not RETURN the negation"),
        # 13-5 / 自疑：非白名单的恒假字面量、以及"比较还在但什么也不拒绝"。
        ("b7/slot guard decided by `1 == 2`",
         clean_slot.replace(
             "        if (promptToken != token) return null\n",
             "        if (1 == 2 && promptToken != token) return null\n"),
         scan_slot_identity, "decided by a literal"),
        ("b7/slot guard decided by `! true`",
         clean_slot.replace(
             "        if (promptToken != token) return null\n",
             "        if (! true && promptToken != token) return null\n"),
         scan_slot_identity, "decided by a literal"),
        ("b7/slot guard keeps the comparison but rejects nothing",
         clean_slot.replace(
             "        if (promptToken != token) return null\n",
             "        if (promptToken != token) Unit\n"),
         scan_slot_identity, "does not EXIT"),
    ]
    witness_failures = 0
    for name, fixture, fn, needle in witness_arms:
        got = fn(fixture)
        ok = any(needle in item for item in got)
        print(f"  {'✅' if ok else '❌'} {name} → {'; '.join(got)[:90] if got else 'CLEAN (arm is dead)'}")
        if not ok:
            witness_failures += 1
    failures += witness_failures

    print(f"A11Y_STATUS_SELFTEST={'PASS' if status_failures == 0 else 'FAIL'}")
    print(f"A11Y_JUDGE_SINGLE_SOURCE_SELFTEST={'PASS' if judge_failures == 0 else 'FAIL'}")
    print(f"A11Y_SLOT_IDENTITY_SELFTEST={'PASS' if slot_failures == 0 else 'FAIL'}")
    print(f"A11Y_VALUE_FLAG_SELFTEST={'PASS' if a02_ok else 'FAIL'}")
    print(f"A11Y_HANDLER_CONTRACT_SELFTEST={'PASS' if failures == 0 else 'FAIL'} "
          f"({len(cases) + len(witness_arms)} arms)")
    return 1 if failures else 0


def main():
    if "--selftest" in sys.argv:
        return selftest()

    root = sys.argv[1] if len(sys.argv) > 1 else "."
    print(f"Accessibility CLI contract check — {root}")
    total = 0
    for label, rel, scan in (
        ("MinisAccessibilityService (late screenshot reply)", SERVICE, scan_service),
        ("AccessibilityOffloadHandler (flags + tap outcome)", HANDLER, scan_handler),
        ("AccessibilityOffloadHandler (status verbs == dispatched verbs)", HANDLER, scan_status_tables),
        ("AccessibilityOffloadHandler (--visible-only reads as a value, not a declared boolean)",
         HANDLER, scan_flag_value_split),
        ("AccessibilityOffloadHandler (uiDump refuses a malformed --visible-only with exit 2)",
         HANDLER, scan_visible_only_read),
    ):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            print(f"❌ {rel}: missing")
            total += 1
            continue
        with open(path, encoding="utf-8") as f:
            bad = scan(f.read())
        if bad:
            print(f"❌ {label}")
            for b in bad:
                print(f"   - {b}")
            total += len(bad)
        else:
            print(f"✅ {label}")

    # Multi-file faces: the fact lives in one place, its readers in another.
    for label, rels, scan in (
        ("accessibility listing predicate (single source: A11yState ↔ recovery)",
         (A11Y_STATE, A11Y_RECOVERY), scan_judge_single_source),
        ("AccessibilityRecoveryManager (prompt slot: identity + lock)",
         (A11Y_RECOVERY,), scan_slot_identity),
    ):
        texts = []
        gap = False
        for rel in rels:
            path = os.path.join(root, rel)
            if not os.path.isfile(path):
                print(f"❌ {rel}: missing")
                total += 1
                gap = True
                break
            with open(path, encoding="utf-8") as f:
                texts.append(f.read())
        if gap:
            continue
        bad = scan(*texts)
        if bad:
            print(f"❌ {label}")
            for b in bad:
                print(f"   - {b}")
            total += len(bad)
        else:
            print(f"✅ {label}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
