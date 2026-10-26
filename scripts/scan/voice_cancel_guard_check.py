#!/usr/bin/env python3
"""
Gate: FIX-E (deep-audit-1020 ISSUE-05) — voice-chain cancel/guard/fallback shapes.

A (VoiceProvider.kt executeRequest)
  The blocking HTTP read must be cancellable two ways: `executeOrCancel()`
  (cancellation rethrow + ensureActive + cancel-at-completion) and the
  prompt-interruption child watcher (`launch { awaitCancellation() }` whose
  completion hook cancels the Call while the read is still in flight — the
  one-arg invokeOnCompletion hook on the withContext job itself only fires at
  final state, sandbox-verified with kotlinx-coroutines 1.9.0). The bare
  `newCall(request).execute()` spelling must not come back, and the success
  path must dispose the watcher hook (zero cancellation on completion).

B (parseChatAsrTranscript)
  The chat-ASR parse must carry the empty-transcript guard
  (`takeIf { it.isNotEmpty() }` + Parse throw) and `transcribeChatBased` must
  route through it — otherwise a malformed chat response maps to
  text="" and the consumer reports NO_MATCH ("heard nothing") instead of a
  protocol error that advances the fail-over chain.

D (OpenRouterModelsApi.fetchModels)
  `executeOrCancel()` must sit INSIDE the try (CancellationException rethrow,
  Exception -> return@withContext emptyList()): outside, an IOException
  propagated out of fetchModels instead of returning the empty fallback.

E (XunfeiVoiceProvider.parseVoiceInputResponse)   [da1027 ISSUE-04]
  The empty-transcript guard must sit on the ASSEMBLED transcript
  (`sb.toString().takeIf { it.isNotEmpty() } ?: throw Parse(...)`), not only
  on the Base64 envelope: a decodable payload whose `ws` set carries no words
  assembled to "" and was still returned as a successful result. The consumer
  (ProviderSpeechRecognitionEngine.startListening) pins the candidate as
  `stickyEntryId` and reports NO_MATCH for it, so the empty result blocks
  fail-over to the remaining candidates — only a THROWN Parse advances the
  chain. Third recurrence of the guard-on-the-wrong-layer shape (0917 F-203
  base class / Doubao / Deepgram carried it; Xunfei only guarded the envelope).

Usage: python3 scripts/scan/voice_cancel_guard_check.py [repo_root]
       python3 scripts/scan/voice_cancel_guard_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""

"""Structural helpers — 全部走共享词法/AST/值流原语。

[B7 01-4 parser-death / family-hole] 旧版在这里自写了 `strip_comments`（只认行首
`//`，字符串保留）、`balanced_block`（裸花括号计数，字符串里的 `}` 就能截断块）、
`IF_RE` + `guards()`（只解析 `if (…)`，`when (false) { … }` 完全隐形）、
`dead_condition()`（死分支白名单只有 `false` / `!true` / 整数比较三形）。四样都被
见证逐一举穿。现在：

* 词法 = `_kotlin_lexer`（注释与字符串/字符字面量内部抹成空格，长度不变；解析
  失败抛 `KotlinLexError`，调用方一律 fail-closed）；
* 括号配平 = `_kotlin_ast.match_bracket`；
* 条件可行性 = `_kotlin_valueflow.guard_feasible`（常量折叠覆盖 `1 == 2`、
  `! true`、`0 > 1`、短路求值…，不再是拼写白名单）；
* if/when 结构 = `_kotlin_ast.if_blocks` / `when_branches` /
  `enclosing_conditions`。
"""
import os
import re
import sys

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    const_bool,
    const_value,
    enclosing_conditions,
    match_bracket,
    split_top,
    strip_parens,
    when_branches,
)
from _kotlin_valueflow import (
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
)

VP_REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProvider.kt"
OR_REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/openrouter/OpenRouterModelsApi.kt"
VENDORS_REL = ("src/android/app/src/main/java/com/rikkaminis/app/provider/voice/"
               "VoiceProviderVendors.kt")

# da1027 ISSUE-04: the guard must cover the ASSEMBLED transcript AND carry the
# Xunfei Parse message — either half alone was the exact shape of the bug
# (envelope-only guard) or of a silent contract change (message drift).
XUNFEI_TRANSCRIPT_GUARD = re.compile(
    r"sb\.toString\(\)\s*\.takeIf\s*\{\s*it\.isNotEmpty\(\)\s*\}\s*\?:\s*throw\s+"
    r"VoiceProviderException\.Parse\(\"Unexpected Xunfei ASR response format\"\)"
)

# [B7 01-3] 空集合兜底不止 `emptyList()` 一种拼写：`emptyList<LLMModel>()`、
# `listOf()`、`kotlin.collections.emptyList()` 是同一个"取消被伪装成没模型"。
EMPTY_FALLBACK_RE = re.compile(
    r"return@withContext\s+(?:[\w.]*\.)?(?:emptyList|emptySet|listOf|mutableListOf|arrayListOf)"
    r"\s*(?:<[^<>]*>)?\s*\("
)

# 值经过这些无损改写后仍然是解析器给出的那个转写文本；其余（take/drop/substring/
# replace/plus/…）都能把非空结果抹成空串。
SAFE_TAIL_RE = re.compile(r"\.\s*(?:trim|trimEnd|trimStart|toString|intern)\s*\(\s*\)\s*(?:!!)?\s*$")


def views(src):
    """(structural, literal)：等长双视图，位置可互投；词法失败抛 KotlinLexError。"""
    return code_view(src), literal_view(src)


def block_at(text, anchor):
    """anchor 之后第一个 `{` 起、`match_bracket` 配平的块体区间 (start, end)。

    `text` 必须是结构视图，所以字符串里的 `}` 再也不能提前闭合块（parser-death）。
    """
    i = text.find("{", anchor)
    if i < 0:
        return None
    close = match_bracket(text, i)
    return (i + 1, close) if close > 0 else None


def lambda_param(text, brace_pos):
    """`{ cause -> … }` 的形参名；无参 lambda 返回 None。"""
    blk = block_at(text, brace_pos)
    if blk is None:
        return None
    m = re.match(r"\s*(?:val\s+)?([A-Za-z_]\w*|_)\s*(?::[^-\n]{0,80})?->", text[blk[0]:blk[1]])
    return m.group(1) if m else None


def dead_guards(text, pos):
    """覆盖 pos 的条件里能证明"永不成立"的那些：[(kind, cond)]。

    两条来源：①`enclosing_conditions`（if 与 when 分支条件）过
    `guard_feasible`；②`when (常量) { … }` 的**主语** —— 只看分支条件的话，
    `when (false) { true -> call.cancel() }` 的分支条件 `true` 当然可行，死的
    是被选择的那条路。
    """
    dead = []
    for kind, cond in enclosing_conditions(text, pos):
        if guard_feasible(cond) is False:
            dead.append((kind, strip_parens(cond)))
    for subject, cond, b0, b1 in when_branches(text):
        if not (b0 <= pos < b1):
            continue
        subj = strip_parens(subject)
        if not subj:
            continue
        sv = const_value(subj)
        if sv is None:
            continue
        c = strip_parens(cond)
        if c == "else":
            continue
        if const_value(c) != sv:
            dead.append(("when", "%s → %s" % (subj, c)))
    return dead


def _is_null_test(cond, param):
    """条件是否恰好是 `<param> == null`（含 `===`、反写、外层括号）。"""
    c = strip_parens(cond).strip()
    c = strip_parens(c).strip()
    name = re.escape(param)
    return bool(re.fullmatch(r"(?:%s\s*===?\s*null|null\s*===?\s*%s)" % (name, name), c))


def cause_gated(text, pos, param, role="catch"):
    """守卫条件依赖本次完成/异常**自带的那个值**吗？

    `invokeOnCompletion { cause -> if (cause == null) call.cancel() }`：watchJob
    跑的是 `awaitCancellation()`，只会被取消结束，所以 `cause` 恒为
    CancellationException、`cause == null` 恒假 —— 这不是常量，但契约上是一条
    死守卫。同族：`catch (e: CancellationException) { if (e.message == …
    ) throw e }`。

    [R3 13-2 误伤] 角色必须分开：完成回调的形参只可能非 null，所以
    `if (cause != null) call.cancel()` / `if (cause is CancellationException)`
    是**活**守卫（旧版把任何引用形参的条件都当死守卫，误杀正当改写）；只有
    `cause == null` 这一类才死。异常捕获的形参则相反：`e.message == "…"`
    这类**内容**开关永远不等于标准取消消息，仍然是死守卫。
    """
    if not param or param == "_":
        return None
    for _kind, cond in enclosing_conditions(text, pos):
        if not contains_ident(cond, param):
            continue
        c = strip_parens(cond)
        if role == "hook":
            if _is_null_test(c, param):
                return c
            continue
        return c
    return None


def why_dead(text, pos, param=None, role="catch"):
    """位置不可达的原因（报错文案用）；None = 这条路是活的。"""
    g = cause_gated(text, pos, param, role) if param else None
    if g:
        return g
    d = dead_guards(text, pos)
    return d[0][1] if d else None


def _first_arg_expr(text, a, b):
    """把 `[a, b)` 的实参文本裁成一条表达式（顶层逗号处截断）。"""
    raw = text[a:b]
    spans = split_top(raw, (",",))
    return raw[spans[0][0]:spans[0][1]].strip() if spans else raw.strip()


# ── try / catch 结构（只用 match_bracket 配平，不做花括号计数） ──────────────
TRY_RE = re.compile(r"\btry\s*\{")
CATCH_HEAD_RE = re.compile(r"\bcatch\s*\(")
FINALLY_RE = re.compile(r"\bfinally\s*\{")
# CancellationException 在 JVM 上继承 IllegalStateException → RuntimeException →
# Exception；Throwable 更宽。能接住 CE 的 catch 类型只有这几族。
CE_CATCHING_TYPES = {
    "CancellationException",
    "IllegalStateException",
    "RuntimeException",
    "Exception",
    "Throwable",
}
# 阻塞读：响应体一旦开始读，取消只能靠 Call.cancel() 打断；把它塞进吞异常的
# catch 里等于把结构性取消做成"空目录返回"。
BLOCKING_READ_RE = re.compile(
    r"\bbody\s*(?:\?|!!)?\s*\.\s*(?:string|bytes|charStream|byteStream|source|"
    r"bufferedReader)\s*\("
)


def _simple_type(ctype):
    return strip_parens(ctype).replace(" ", "").rsplit(".", 1)[-1]


def try_blocks(text):
    """[(try_body_span, [(param, type, catch_body_span)], finally_span|None)]。"""
    out = []
    for m in TRY_RE.finditer(text):
        open_at = m.end() - 1
        close = match_bracket(text, open_at)
        if close < 0:
            continue
        catches = []
        fin = None
        i = close + 1
        n = len(text)
        while i < n:
            while i < n and text[i] in " \t\r\n":
                i += 1
            cm = CATCH_HEAD_RE.match(text, i)
            if cm:
                p_open = cm.end() - 1
                p_close = match_bracket(text, p_open)
                if p_close < 0:
                    break
                clause = text[p_open + 1:p_close]
                param, _sep, ctype = clause.partition(":")
                param = param.strip()
                ctype = (ctype or "*").strip()
                j = p_close + 1
                while j < n and text[j] in " \t\r\n":
                    j += 1
                if j >= n or text[j] != "{":
                    break
                c_close = match_bracket(text, j)
                if c_close < 0:
                    break
                catches.append((param, ctype, (j + 1, c_close)))
                i = c_close + 1
                continue
            fm = FINALLY_RE.match(text, i)
            if fm:
                f_open = fm.end() - 1
                f_close = match_bracket(text, f_open)
                if f_close > 0:
                    fin = (f_open + 1, f_close)
                break
            break
        out.append(((open_at + 1, close), catches, fin))
    return out


def swallowing_ce_handler(text, pos):
    """pos 处的 CancellationException 实际落到哪个子句；吞掉的返回 (type, param)。

    Kotlin 按子句顺序匹配：最先能接住 CE 的那个 catch 说了算。它的块里有
    可达的 `throw` ⇒ 取消照常上抛（安全）；否则取消被这个子句吃掉。
    """
    best = None
    for body, catches, _fin in try_blocks(text):
        a, b = body
        if a <= pos < b and (best is None or (b - a) < (best[0][1] - best[0][0])):
            best = ((a, b), catches)
    if best is None:
        return None
    for param, ctype, span in best[1]:
        if _simple_type(ctype) not in CE_CATCHING_TYPES:
            continue
        cbody = text[span[0]:span[1]]
        throws = [m.start() for m in re.finditer(r"\bthrow\b", cbody)]
        if any(path_feasible(cbody, p) for p in throws):
            return None
        return (_simple_type(ctype), param)
    return None


def finally_span(text, pos):
    """覆盖 pos 的 try 的 finally 块区间；该 try 没有 finally ⇒ None。"""
    best = None
    for body, _catches, fin in try_blocks(text):
        a, b = body
        if a <= pos < b and (best is None or (b - a) < (best[1] - best[0])):
            best = (a, b) if fin is None else fin
    return best


def watcher_binding(body):
    """`val <name> = launch { … awaitCancellation() … }` 的 <name>（派生，不写死）。"""
    for m in re.finditer(
        r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*launch\s*(?:\([^)]*\))?\s*\{", body
    ):
        open_at = m.end() - 1
        close = match_bracket(body, open_at)
        if close > 0 and "awaitCancellation" in body[open_at:close]:
            return m.group(1)
    return None


def response_text_args(text):
    """每个 `VoiceInputResponse(text = …)` 的实参：[(表达式, 表达式起点)]。"""
    out = []
    for m in re.finditer(r"VoiceInputResponse\s*\(", text):
        open_at = m.end() - 1
        if match_bracket(text, open_at) < 0:
            continue
        for a, b in call_args(text, open_at):
            nm = re.match(r"\s*text\s*=", text[a:b])
            if not nm:
                continue
            rhs_at = a + nm.end()
            end = min(b, expr_end(text, rhs_at))
            out.append((_first_arg_expr(text, rhs_at, end), rhs_at))
    return out


def asr_value_kind(expr, binds):
    """`text = <expr>` 到底还是不是解析器产出的那个值。

    ok      = 绑定本身，或解析调用本身（可叠 trim/toString 这类无损改写）
    literal = 常量（`""` 正是 da1020 ISSUE-05 B 的原始形状）
    other   = 其余任何写法：值被换掉了（`.take(0)`、`.let { null }`、拼接…）
    """
    e = strip_parens(expr)
    if not e:
        return "other"
    if const_value(e) is not None:
        return "literal"
    cur = e
    for _ in range(4):
        m = SAFE_TAIL_RE.search(cur)
        if not m:
            break
        cur = cur[:m.start()].strip()
    if re.fullmatch(r"[A-Za-z_]\w*", cur) and cur in binds:
        return "ok"
    m = re.match(r"parseChatAsrTranscript\s*\(", cur)
    if m and match_bracket(cur, m.end() - 1) == len(cur) - 1:
        return "ok"
    return "other"


def decl_bindings(text):
    """`val name = <expr>` → {name: (rhs_start, rhs_end)}（在结构视图上取）。"""
    out = {}
    for m in re.finditer(r"(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=(?!=)", text):
        rhs = m.end()
        out[m.group(1)] = (rhs, min(expr_end(text, rhs), _stmt_end(text, rhs)))
    return out


def _stmt_end(text, start):
    """语句终点：深度 0 的换行（续行除外）。"""
    from _kotlin_ast import _is_continuation
    depth = 0
    i, n = start, len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif ch == "\n" and depth == 0 and not _is_continuation(text, i):
            return i
        i += 1
    return n

def check_voiceprovider(src):
    v = []
    try:
        cv, lv = views(src)
    except KotlinLexError as exc:
        return ["VoiceProvider.kt: source could not be lexed (%s) — fail-closed "
                "(a string/char literal that cannot be closed hides the structure)" % exc]

    at = cv.find("suspend fun executeRequest")
    blk = block_at(cv, at) if at >= 0 else None
    if blk is None:
        v.append("VoiceProvider.kt: executeRequest declaration not found "
                 "(gate anchors moved — deliberate edit required)")
        return v
    body = cv[blk[0]:blk[1]]
    if "executeOrCancel()" not in body:
        v.append("VoiceProvider.kt: executeRequest no longer calls "
                 "executeOrCancel() (cancellation rethrow + ensureActive lost)")
    if ".execute()" in body:
        v.append("VoiceProvider.kt:%d: bare '.execute()' is back in "
                 "executeRequest (da1020 ISSUE-05: uncancellable blocking read)"
                 % (cv.count("\n", 0, cv.find(".execute()")) + 1))
    if "awaitCancellation" not in body:
        v.append("VoiceProvider.kt: prompt-interruption watcher missing "
                 "(launch { awaitCancellation() }) — the one-arg "
                 "invokeOnCompletion hook only fires at final state, so a "
                 "cancelled coroutine would hold the connection through the "
                 "whole 120s readTimeout")
    if "call.cancel()" not in body:
        v.append("VoiceProvider.kt: watcher hook no longer cancels the Call "
                 "(call.cancel() missing)")
    # [wide-1024 W1 + B7 01-1/01-4] 判据锚"这个钩子在**会走到的**路上真的取消了
    # Call"，不锚"某处拼了 call.cancel()"：
    #   `if (false) call.cancel()` / `when (false) { true -> … }` 走不到；
    #   `{ cause -> if (cause == null) call.cancel() }` 条件里没有常量，但 watchJob
    #   跑的是 awaitCancellation()，只可能被取消结束 ⇒ cause 恒非 null，恒假。
    hook = re.search(r"invokeOnCompletion\s*\{", body)
    if hook is None:
        v.append("VoiceProvider.kt: no `invokeOnCompletion { … }` watcher hook found in "
                 "executeRequest (fail-closed: the cancel path cannot be graded)")
    else:
        lam = block_at(body, hook.end() - 1)
        if lam is None:
            v.append("VoiceProvider.kt: the invokeOnCompletion hook body could not be parsed "
                     "(fail-closed)")
        else:
            lam_body = body[lam[0]:lam[1]]
            param = lambda_param(body, hook.end() - 1)
            cancels = [m.start() for m in re.finditer(r"call\.cancel\(\)", lam_body)]
            if not cancels:
                v.append("VoiceProvider.kt: the watcher hook does not cancel the Call "
                         "(call.cancel() missing from the invokeOnCompletion lambda)")
            else:
                reasons = [why_dead(lam_body, p, param, role="hook") for p in cancels]
                if all(r is not None for r in reasons):
                    v.append("VoiceProvider.kt: call.cancel() sits behind the dead guard "
                             "`if (%s)` — the hook fires and cancels nothing, so a cancelled "
                             "coroutine holds the blocking body read until the 120s "
                             "readTimeout (da1020 ISSUE-05 A)" % reasons[0])
    for m in re.finditer(r"watchDisposable\.dispose\(\)", body):
        why = why_dead(body, m.start())
        if why is not None:
            v.append("VoiceProvider.kt: watchDisposable.dispose() sits behind the dead guard "
                     "`if (%s)` — the success path keeps the hook attached and the following "
                     "watchJob.cancel() then fires it, so every completed request still cancels "
                     "its Call (contract: zero cancellation on completion; da1020 ISSUE-05 A)"
                     % why)
    if "watchDisposable.dispose()" not in body:
        v.append("VoiceProvider.kt: watcher hook is no longer disposed on the "
                 "success path (every completed request would cancel its Call)")

    # [R3 13-2 dual-gate seam] dispose() 只解绑完成回调，**不会**取消 watchJob。
    # `withContext(Dispatchers.IO)` 的结构化并发要等作用域里所有子 Job 结束，
    # 而 watchJob 跑的是 awaitCancellation()：少了这半条链，HTTP 明明 200 OK
    # 读完，函数仍在 withContext 出口永远挂起（每次语音请求 100% 卡死）。
    job = watcher_binding(body)
    if job is not None:
        exec_at = body.find("executeOrCancel()")
        cancels = [m.start() for m in re.finditer(
            r"\b%s\s*\.\s*cancel(?:AndJoin)?\s*\(" % re.escape(job), body)]
        live = [p for p in cancels if path_feasible(body, p)]
        fin = finally_span(body, exec_at) if exec_at >= 0 else None
        if not live:
            v.append("VoiceProvider.kt: the watcher job `%s` is never cancelled — dispose() "
                     "only detaches the completion hook, so structured concurrency keeps "
                     "withContext waiting on awaitCancellation() and every successful ASR/TTS "
                     "request hangs at the withContext exit (da1020 ISSUE-05 A)" % job)
        elif fin is not None and not any(fin[0] <= p < fin[1] for p in live):
            v.append("VoiceProvider.kt: the watcher job `%s` is only cancelled outside the "
                     "`finally` block of the guarded call — the normal-completion path still "
                     "waits on awaitCancellation() until the 120s readTimeout (da1020 "
                     "ISSUE-05 A)" % job)

    at = cv.find("fun parseChatAsrTranscript")
    blk = block_at(cv, at) if at >= 0 else None
    if blk is None:
        v.append("VoiceProvider.kt: parseChatAsrTranscript declaration not found "
                 "(the chat-ASR parse was inlined or renamed — deliberate edit)")
        return v
    parse_cv = cv[blk[0]:blk[1]]
    parse_lv = lv[blk[0]:blk[1]]
    if "takeIf { it.isNotEmpty() }" not in parse_cv:
        v.append("VoiceProvider.kt: parseChatAsrTranscript lost the "
                 "empty-transcript guard (takeIf) — malformed chat responses "
                 "map to text=\"\" and NO_MATCH instead of a protocol error")
    if 'throw VoiceProviderException.Parse("Empty ASR transcript in chat ASR response")' not in parse_lv:
        v.append("VoiceProvider.kt: parseChatAsrTranscript lost the "
                 "empty-transcript Parse throw")

    at = cv.find("suspend fun transcribeChatBased")
    blk = block_at(cv, at) if at >= 0 else None
    tcb = cv[blk[0]:blk[1]] if blk else None
    if tcb is None or "parseChatAsrTranscript(" not in tcb:
        v.append("VoiceProvider.kt: transcribeChatBased no longer routes "
                 "through parseChatAsrTranscript (a second inline parse would "
                 "re-open the empty-transcript hole)")
    else:
        # [B7 01-2 dataflow] 旧版只查"解析结果绑到了某个局部，而后文出现
        # `text = <那个局部>\b`"—— `text = parsed.take(0)` 靠单词边界命中，值被
        # 完全截断却仍是绿的。现在判**返回值本身**：它必须就是解析器产出的那个值。
        binds = decl_bindings(tcb)
        texts = response_text_args(tcb)
        if not texts:
            v.append("VoiceProvider.kt: transcribeChatBased no longer returns "
                     "VoiceInputResponse(text = …) — the ASR result has no landing place "
                     "(fail-closed)")
        for expr, _pos in texts:
            kind = asr_value_kind(expr, binds)
            if kind == "literal":
                v.append("VoiceProvider.kt: transcribeChatBased returns a hard-coded transcript "
                         "(text = %s) — the ASR result is thrown away, so the consumer reports "
                         "NO_MATCH (\"heard nothing\") for every input and fail-over never "
                         "advances" % expr[:40])
            elif kind != "ok":
                v.append("VoiceProvider.kt: the transcript handed to VoiceInputResponse is not "
                         "the ASR result any more (`%s`) — a transform after the parse "
                         "(take/drop/substring/replace/…) empties it, so every utterance maps to "
                         "NO_MATCH and fail-over never advances (da1020 ISSUE-05 B)" % expr[:40])
        # 解析调用要么是返回值的来源，要么本身就是返回值 —— 否则它就是白跑一趟。
        spans = [(pos, pos + len(e)) for e, pos in texts]
        parse_at = {m.start() for m in re.finditer(r"parseChatAsrTranscript\s*\(", tcb)}
        bound_at = set()
        for _name, (a, b) in binds.items():
            for mm in re.finditer(r"parseChatAsrTranscript\s*\(", tcb[a:b]):
                bound_at.add(a + mm.start())
        for p in sorted(parse_at):
            if any(s <= p < e for s, e in spans) or p in bound_at:
                continue
            v.append("VoiceProvider.kt: the parseChatAsrTranscript( … ) result is DISCARDED — it "
                     "must be the value of VoiceInputResponse(text = …); a bare call next to an "
                     "empty literal parses the transcript and hands back text=\"\" "
                     "(da1020 ISSUE-05 B)")
            break
        for name, (a, b) in binds.items():
            if "parseChatAsrTranscript" not in tcb[a:b]:
                continue
            if not any(contains_ident(e, name) for e, _ in texts):
                v.append("VoiceProvider.kt: the parseChatAsrTranscript( … ) result is DISCARDED — "
                         "`%s` never reaches VoiceInputResponse(text = …) (da1020 ISSUE-05 B)"
                         % name)
                break
    return v

def check_openrouter(src):
    v = []
    try:
        cv, _lv = views(src)
    except KotlinLexError as exc:
        return ["OpenRouterModelsApi.kt: source could not be lexed (%s) — fail-closed" % exc]
    i = cv.find("val response = try {")
    if i < 0:
        v.append("OpenRouterModelsApi.kt: response try envelope not found "
                 "(da1020 ISSUE-05: executeOrCancel must sit inside a try)")
        return v
    blk = block_at(cv, i + len("val response = try "))
    if blk is None:
        v.append("OpenRouterModelsApi.kt: the `try { … }` envelope could not be parsed "
                 "(fail-closed: the cancel/fallback split cannot be graded)")
        return v
    try_blk = cv[blk[0]:blk[1]]
    if "executeOrCancel()" not in try_blk:
        v.append("OpenRouterModelsApi.kt: executeOrCancel() is back OUTSIDE the "
                 "try (da1020 ISSUE-05: an IOException would propagate out of "
                 "fetchModels instead of returning the empty fallback)")
    rest = cv[blk[1]:]

    # ── CancellationException catch: a LIVE rethrow, never a fallback ─────
    ce = re.search(r"catch\s*\(\s*(\w+)\s*:\s*kotlinx\.coroutines\.CancellationException\s*\)\s*\{",
                   rest)
    if ce is None:
        v.append("OpenRouterModelsApi.kt: CancellationException catch missing "
                 "(a cancelled fetch must unwind, not fall back)")
    else:
        param = ce.group(1)
        cblk = block_at(rest, ce.end() - 1)
        if cblk is None:
            v.append("OpenRouterModelsApi.kt: the CancellationException catch body could not be "
                     "parsed (fail-closed)")
        else:
            cbody = rest[cblk[0]:cblk[1]]
            # [B7 01-3 family-hole] 重抛必须**无条件**：旧版只折字面量，
            # `if (e.message == "__never__") throw e` 靠异常自带的内容当开关 ——
            # 标准取消消息永远不等于它，等于把重抛做成了死码。
            rethrows = [m.start() for m in re.finditer(r"\bthrow\s+%s\b" % re.escape(param), cbody)]
            if not rethrows:
                v.append("OpenRouterModelsApi.kt: cancellation rethrow (throw %s) missing" % param)
            else:
                reasons = [why_dead(cbody, p, param) for p in rethrows]
                if all(r is not None for r in reasons):
                    v.append("OpenRouterModelsApi.kt: `throw %s` sits behind the dead guard "
                             "`if (%s)` — a cancelled fetch is swallowed instead of unwinding "
                             "(da1020 ISSUE-05 D)" % (param, reasons[0]))
            # `emptyList<LLMModel>()` / `listOf()` 是同一条兜底的不同拼写。
            if EMPTY_FALLBACK_RE.search(cbody):
                v.append("OpenRouterModelsApi.kt: the CancellationException catch returns the "
                         "empty fallback — a cancelled fetch masquerades as \"no models\" and the "
                         "caller's cancellation never propagates (da1020 ISSUE-05 D)")

    ex = re.search(r"catch\s*\(\s*\w+\s*:\s*Exception\s*\)\s*\{", rest)
    if ex is None:
        v.append("OpenRouterModelsApi.kt: Exception catch missing")
    else:
        eblk = block_at(rest, ex.end() - 1)
        if eblk is None or not EMPTY_FALLBACK_RE.search(rest[eblk[0]:eblk[1]]):
            v.append("OpenRouterModelsApi.kt: Exception catch must return the "
                     "empty fallback (return@withContext emptyList())")

    # ── [R3 13-1 dual-gate seam] 阻塞读的取消归属 ─────────────────────────
    # 已有判据只看 executeOrCancel() 有没有待在带 CE 重抛的 try 里；把
    # `response.body?.string()` 整体搬进下面那条 `catch (_: Exception) { return
    # emptyList() }`，两门都不管：开头（响应头已到）的取消在读完数 MB 响应体
    # 期间被 catch-all 吞掉，调用方的结构性取消被伪装成"OpenRouter 没模型"。
    # 判据取自 Kotlin 的匹配顺序：CE 由**最先能接住它**的 catch 子句决定归属，
    # 那个子句里没有可达的 throw ⇒ 取消被吞。
    for m in BLOCKING_READ_RE.finditer(cv):
        hit = swallowing_ce_handler(cv, m.start())
        if hit is not None:
            v.append("OpenRouterModelsApi.kt:%d: the blocking body read `%s` sits inside a "
                     "`catch (%s) { … }` with no reachable rethrow — CancellationException "
                     "raised while the body is being drained is swallowed, so a cancelled "
                     "fetch masquerades as \"no models\" and the caller's cancellation never "
                     "propagates (da1020 ISSUE-05 D)"
                     % (cv.count("\n", 0, m.start()) + 1, m.group(0).strip(), hit[0]))
    return v


def check_xunfei_transcript(src):
    """da1027 ISSUE-04 — the Xunfei guard must sit on the assembled transcript."""
    v = []
    try:
        cv, lv = views(src)
    except KotlinLexError as exc:
        return ["VoiceProviderVendors.kt: source could not be lexed (%s) — fail-closed" % exc]
    # Scope first: three vendors override parseVoiceInputResponse in this file,
    # so an unscoped anchor would silently grade Doubao's copy instead.
    cls = cv.find("class XunfeiVoiceProvider")
    if cls < 0:
        v.append("VoiceProviderVendors.kt: XunfeiVoiceProvider class not found "
                 "(gate anchors moved — deliberate edit required)")
        return v
    anchor = cv.find("fun parseVoiceInputResponse", cls)
    blk = block_at(cv, anchor) if anchor >= 0 else None
    body_cv = cv[blk[0]:blk[1]] if blk else None
    body_lv = lv[blk[0]:blk[1]] if blk else None
    if body_cv is None or '"ws"' not in body_lv:
        # No `ws` frame read ⇒ we are not looking at the Xunfei override (or it
        # was renamed/inlined). Fail closed rather than grade a sibling parser.
        v.append("VoiceProviderVendors.kt: XunfeiVoiceProvider.parseVoiceInputResponse "
                 "not found (its `ws` frame read is gone — gate anchors moved, "
                 "deliberate edit required)")
        return v
    if not XUNFEI_TRANSCRIPT_GUARD.search(body_lv):
        v.append("VoiceProviderVendors.kt: XunfeiVoiceProvider.parseVoiceInputResponse no "
                 "longer guards the ASSEMBLED transcript "
                 "(sb.toString().takeIf { it.isNotEmpty() } ?: throw "
                 "VoiceProviderException.Parse(\"Unexpected Xunfei ASR response format\")) "
                 "— da1027 ISSUE-04: an empty transcript is returned as a successful "
                 "result, pinning the candidate sticky + NO_MATCH and blocking fail-over")
    if "return VoiceInputResponse(text = sb.toString()" in body_lv:
        v.append("VoiceProviderVendors.kt: bare 'return VoiceInputResponse(text = "
                 "sb.toString()' is back in the Xunfei parser (da1027 ISSUE-04: the "
                 "envelope-only guard let an empty transcript through as success)")

    # [B7 01-6 dataflow] 守卫本身可以一字不改地被"喂饱再抹平"绕过：
    #   sb.append(" ")                            → 空帧变成 " "，takeIf 恒真
    #   return VoiceInputResponse(text = transcript.trim())  → 再抹回 ""
    # 所以判据要落在**数据流两端**：builder 只能被 ASR 词喂，返回值只能是 guard
    # 的绑定，且不得再做会丢内容的改写。
    for m in re.finditer(r"\bsb\s*\.\s*(?:append|appendLine|insert)\s*\(", body_cv):
        for a, b in call_args(body_cv, m.end() - 1):
            arg = strip_parens(body_lv[a:b])
            if const_value(arg) is not None:
                v.append("VoiceProviderVendors.kt: the Xunfei transcript builder is fed a literal "
                         "(`sb.append(%s)` in %s) — padding the builder makes "
                         "`takeIf { it.isNotEmpty() }` always hold, so a silent-frame ASR result "
                         "comes back as an empty-but-successful transcript (da1027 ISSUE-04, "
                         "dataflow)" % (arg[:20], "XunfeiVoiceProvider.parseVoiceInputResponse"))
                break
    binds = decl_bindings(body_cv)
    texts = response_text_args(body_cv)
    if not texts:
        v.append("VoiceProviderVendors.kt: the Xunfei parser no longer returns "
                 "VoiceInputResponse(text = …) — the guarded transcript has no landing place "
                 "(fail-closed)")
    for expr, _pos in texts:
        e = strip_parens(expr)
        if const_value(e) is not None:
            v.append("VoiceProviderVendors.kt: the Xunfei parser returns a hard-coded transcript "
                     "(text = %s) — da1027 ISSUE-04: an empty transcript is reported as success "
                     "and the consumer pins the candidate sticky + NO_MATCH" % expr[:40])
        elif not re.fullmatch(r"[A-Za-z_]\w*", e):
            v.append("VoiceProviderVendors.kt: the GUARDED transcript is rewritten before it is "
                     "returned (`%s`) — any lossy tail (trim/take/substring/…) can turn the "
                     "non-empty guarded text back into \"\", so the guard passes and the caller "
                     "still gets an empty success (da1027 ISSUE-04, dataflow)" % expr[:40])
        elif e not in binds:
            v.append("VoiceProviderVendors.kt: `%s` is returned as the transcript but is not a "
                     "binding in this parser — the value never went through the "
                     "assembled-transcript guard (fail-closed)" % e)
        else:
            a, b = binds[e]
            rhs = strip_parens(body_lv[a:b])
            if not XUNFEI_TRANSCRIPT_GUARD.match(rhs):
                v.append("VoiceProviderVendors.kt: `%s` is not bound to the assembled-transcript "
                         "guard — the returned value must be exactly "
                         "`sb.toString().takeIf { it.isNotEmpty() } ?: throw Parse(...)`, not a "
                         "wrapper around it (`%s`)" % (e, rhs[:40]))
    return v


def read(root, rel):
    with open(os.path.join(root, rel), "r", encoding="utf-8") as fh:
        return fh.read()


# ── [R3 13-3 symmetric seam] 空转写守卫必须覆盖**每一个**厂商解析器 ──────────
# 旧判据只认 Xunfei（da1027 的当事人），同文件对称的 Doubao / Deepgram 只写在
# 注释里「与 Xunfei 对齐」——见证把 Doubao 的 `?.takeIf { it.isNotEmpty() }`
# 删掉，`?: throw` 立刻变成死码，空转写以"成功"返回，候选被粘性钉死、永不
# fail-over，门照绿。
VENDOR_OVERRIDE_RE = re.compile(r"\boverride\s+fun\s+parseVoiceInputResponse\s*\(")
CLASS_DECL_RE = re.compile(r"\bclass\s+([A-Za-z_]\w*)")
# 真正拦得住空串的三种拼写（takeIf 判非空 / takeUnless 判空 / ifEmpty·ifBlank 兜底）
GUARD_OK_RE = re.compile(
    r"\btakeIf\s*\{[^{}]*\bisNot(?:Empty|Blank)\s*\(\s*\)"
    r"|\btakeUnless\s*\{[^{}]*\bis(?:Empty|Blank)\s*\(\s*\)"
    r"|\bif(?:Empty|Blank)\s*\{"
)


def named_args(text, open_paren_pos):
    """`f(a = x, b = y)` → {name: (rhs_start, rhs_end)}。"""
    out = {}
    for a, b in call_args(text, open_paren_pos):
        m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", text[a:b])
        if not m:
            continue
        rhs = a + m.end()
        out[m.group(1)] = (rhs, min(b, expr_end(text, rhs)))
    return out


def vendor_parsers(cv):
    """本文件每个 `parseVoiceInputResponse` 覆盖：[(所属类名, 函数体区间)]。"""
    out = []
    for m in VENDOR_OVERRIDE_RE.finditer(cv):
        cls = None
        for cm in CLASS_DECL_RE.finditer(cv[:m.start()]):
            cls = cm.group(1)
        p_close = match_bracket(cv, m.end() - 1)
        if p_close < 0:
            continue
        i = p_close + 1
        while i < len(cv) and cv[i] not in "{=":
            i += 1
        if i >= len(cv) or cv[i] != "{":
            continue
        close = match_bracket(cv, i)
        if close < 0:
            continue
        out.append((cls or "?", (i + 1, close)))
    return out


def check_vendor_transcripts(src):
    """每个厂商解析器返回的转写文本都必须带空串守卫 + 拒绝路径。"""
    v = []
    try:
        cv, lv = views(src)
    except KotlinLexError as exc:
        return ["VoiceProviderVendors.kt: source could not be lexed (%s) — fail-closed" % exc]
    parsers = vendor_parsers(cv)
    for cls, (a, b) in parsers:
        body_c = cv[a:b]
        body_l = lv[a:b]
        binds = decl_bindings(body_c)
        spans = []
        for m in re.finditer(r"VoiceInputResponse\s*\(", body_c):
            open_at = m.end() - 1
            if match_bracket(body_c, open_at) < 0:
                continue
            na = named_args(body_c, open_at)
            if "text" in na:
                spans.append(na["text"])
        for rs, re_ in spans:
            expr_c = strip_parens(body_c[rs:re_])
            expr_l = body_l[rs:re_]
            # 绑定的局部 → 回到它的右值（守卫通常写在声明那条链上）
            if re.fullmatch(r"[A-Za-z_]\w*", expr_c) and expr_c in binds:
                ba, bb = binds[expr_c]
                expr_c = strip_parens(body_c[ba:bb])
                expr_l = body_l[ba:bb]
            if const_value(expr_c) is not None:
                v.append("VoiceProviderVendors.kt: %s.parseVoiceInputResponse returns a "
                         "hard-coded transcript (text = %s) — every utterance is reported as "
                         "a successful empty transcription (fail-over never advances)"
                         % (cls, " ".join(expr_l.split())[:32]))
                continue
            if not GUARD_OK_RE.search(expr_l):
                v.append("VoiceProviderVendors.kt: %s.parseVoiceInputResponse returns an "
                         "UNGUARDED transcript (`%s`) — optString yields \"\" for a missing or "
                         "empty key, so the `?: throw` is dead code and an empty transcript is "
                         "reported as success: the consumer pins the candidate sticky + "
                         "NO_MATCH and the fail-over chain never advances (audit-0917 family / "
                         "da1027 ISSUE-04, symmetric axis)"
                         % (cls, " ".join(expr_l.split())[:60]))
                continue
            if not re.search(r"\bthrow\b", expr_l):
                v.append("VoiceProviderVendors.kt: %s.parseVoiceInputResponse guards the "
                         "transcript but never rejects it — the empty result still reaches the "
                         "consumer as success (a guard needs a `?: throw Parse(…)` / `ifEmpty "
                         "{ throw … }` payoff)" % cls)
    return v


def run(root):
    if not os.path.isdir(root):
        print("voice_cancel_guard_check: not a directory: %s" % root)
        return 2
    try:
        vp = read(root, VP_REL)
        ors = read(root, OR_REL)
    except OSError as exc:
        print("voice_cancel_guard_check: cannot read sources: %s" % exc)
        return 2
    violations = check_voiceprovider(vp) + check_openrouter(ors)
    vendors_path = os.path.join(root, VENDORS_REL)
    if os.path.exists(vendors_path):
        try:
            with open(vendors_path, "r", encoding="utf-8") as fh:
                vendors_src = fh.read()
        except OSError as exc:
            print("voice_cancel_guard_check: cannot read sources: %s" % exc)
            return 2
        violations += check_xunfei_transcript(vendors_src)
        violations += check_vendor_transcripts(vendors_src)
    if violations:
        print("FAIL voice_cancel_guard_check")
        for x in violations:
            print("  " + x)
        return 1
    print("PASS voice_cancel_guard_check (cancel watcher + empty-transcript "
          "guards [chat ASR + Xunfei transcript] + openrouter fallback envelope "
          "all wired)")
    return 0


# --------------------------------------------------------------------------
# selftest
# --------------------------------------------------------------------------

GOOD_VP = '''
class VoiceProvider {
    companion object {
        val httpClient: OkHttpClient = OkHttpClient()
        private const val TAG = "VoiceProvider"
    }

    suspend fun executeRequest(request: Request): ByteArray = withContext(Dispatchers.IO) {
        val call = httpClient.newCall(request)
        val watchJob = launch { awaitCancellation() }
        val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }
        try {
            call.executeOrCancel().use { response ->
                val body = response.body?.bytes()
                if (!response.isSuccessful) {
                    throw VoiceProviderException.Http(response.code, body)
                }
                body ?: ByteArray(0)
            }
        } finally {
            watchDisposable.dispose()
            watchJob.cancel()
        }
    }

    open suspend fun transcribeChatBased(request: VoiceInputRequest): VoiceInputResponse {
        val data = executeRequest(request.toRequest())
        return VoiceInputResponse(text = parseChatAsrTranscript(data))
    }
}

internal fun parseChatAsrTranscript(data: ByteArray): String {
    val obj = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        ?: throw VoiceProviderException.Parse("Undecodable chat ASR response")
    val text = obj.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw VoiceProviderException.Parse("Empty ASR transcript in chat ASR response")
    return text
}
'''

BAD_VP_BARE_EXECUTE = GOOD_VP.replace(
    "call.executeOrCancel().use { response ->", "call.execute().use { response ->")

BAD_VP_NO_WATCHER = GOOD_VP.replace(
    "        val watchJob = launch { awaitCancellation() }\n"
    "        val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }\n", "")

BAD_VP_NO_GUARD = GOOD_VP.replace(
    "        ?.trim()\n"
    "        ?.takeIf { it.isNotEmpty() }\n"
    "        ?: throw VoiceProviderException.Parse(\"Empty ASR transcript in chat ASR response\")\n"
    "    return text",
    "        ?.trim()\n"
    "        ?: \"\"\n"
    "    return text",
).replace("return VoiceInputResponse(text = parseChatAsrTranscript(data))",
          "return VoiceInputResponse(text = data.decodeToString())")

GOOD_OR = '''
object OpenRouterModelsApi {
    suspend fun fetchModels(apiKey: String): List<LLMModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
        val response = try {
            client.newCall(request).executeOrCancel()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        try {
            emptyList()
        } finally {
            response.close()
        }
    }
}
'''

# ── wide-1024 witnesses (each one used to pass this gate unchanged) ──────────

# W1/A: the cancel hook is dead-guarded — every required substring survives.
BAD_VP_DEAD_CANCEL = GOOD_VP.replace(
    "val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }",
    "val watchDisposable = watchJob.invokeOnCompletion { _ -> if (false) call.cancel() }")

# W2/B: the transcript is parsed and thrown away, an empty literal is returned.
BAD_VP_DISCARD_PARSE = GOOD_VP.replace(
    """        val data = executeRequest(request.toRequest())
        return VoiceInputResponse(text = parseChatAsrTranscript(data))""",
    """        val data = executeRequest(request.toRequest())
        if (false) parseChatAsrTranscript(data)
        return VoiceInputResponse(text = "")""")

# W3/D: cancellation swallowed by a dead rethrow + an empty fallback.
BAD_OR_DEAD_RETHROW = GOOD_OR.replace(
    """        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e""",
    """        } catch (e: kotlinx.coroutines.CancellationException) {
            if (false) throw e
            return@withContext emptyList()""")

BAD_OR_OUTSIDE_TRY = GOOD_OR.replace(
    """        val response = try {
            client.newCall(request).executeOrCancel()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext emptyList()
        }""",
    "        val response = client.newCall(request).executeOrCancel()",
)

BAD_OR_NO_EXEC_IN_TRY = GOOD_OR.replace(
    "client.newCall(request).executeOrCancel()",
    "client.newCall(request).execute()",
)


GOOD_VENDORS = '''
class XunfeiVoiceProvider(
    providerId: String,
    private val appId: String,
    apiKey: String,
    private val apiSecret: String,
) : VoiceProvider(providerId, "https://iat-api.xfyun.cn", apiKey) {

    override fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceProviderException.Parse("Unexpected Xunfei ASR response format")
        val b64Text = json.optJSONObject("payload")?.optJSONObject("result")?.optString("text")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected Xunfei ASR response format")
        val textJson = runCatching {
            JSONObject(String(Base64.decode(b64Text, Base64.DEFAULT), Charsets.UTF_8))
        }.getOrNull() ?: throw VoiceProviderException.Parse("Unexpected Xunfei ASR response format")

        val sb = StringBuilder()
        val ws = textJson.optJSONArray("ws") ?: JSONArray()
        for (i in 0 until ws.length()) {
            val cw = ws.optJSONObject(i)?.optJSONArray("cw") ?: continue
            for (j in 0 until cw.length()) {
                cw.optJSONObject(j)?.optString("w")?.let { sb.append(it) }
            }
        }
        val transcript = sb.toString().takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected Xunfei ASR response format")
        return VoiceInputResponse(text = transcript, language = request.language)
    }
}
'''

# Pre-fix shape: only the Base64 ENVELOPE is guarded, the assembled transcript
# is returned as-is (this is da1027 ISSUE-04 verbatim).
BAD_VENDORS_BARE = GOOD_VENDORS.replace(
    "        val transcript = sb.toString().takeIf { it.isNotEmpty() }\n"
    "            ?: throw VoiceProviderException.Parse(\"Unexpected Xunfei ASR response format\")\n"
    "        return VoiceInputResponse(text = transcript, language = request.language)",
    "        return VoiceInputResponse(text = sb.toString(), language = request.language)")

# Guard kept but its Parse message drifted: the fail-over contract is carried by
# `catch (e: Exception)` in the engine, so a wrong exception/message is a
# silent contract change, not a cosmetic edit.
BAD_VENDORS_MSG = GOOD_VENDORS.replace(
    "val transcript = sb.toString().takeIf { it.isNotEmpty() }\n"
    "            ?: throw VoiceProviderException.Parse(\"Unexpected Xunfei ASR response format\")",
    "val transcript = sb.toString().takeIf { it.isNotEmpty() }\n"
    "            ?: throw VoiceProviderException.Parse(\"Empty Xunfei transcript\")")

# Precision probe: a GUARDED sibling parser sits earlier in the file (exactly
# the real Doubao/Xunfei order), so an unscoped anchor would grade the decoy and
# report clean while the Xunfei path stayed broken.
BAD_VENDORS_DECOY = '''
class DoubaoVoiceProvider(providerId: String, baseUrl: String, apiKey: String?) :
    VoiceProvider(providerId, baseUrl, apiKey) {

    override fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val text = json?.optJSONObject("result")?.optString("text")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected Doubao v3 ASR response format")
        return VoiceInputResponse(text = text, language = request.language)
    }
}
''' + BAD_VENDORS_BARE


# ── B7 witnesses (each one passed this gate unchanged before the fix) ───────

# 01-1: 守卫条件取的是完成回调自带的 cause —— watchJob 跑 awaitCancellation()，
# 只可能被取消结束 ⇒ cause 恒非 null，`cause == null` 恒假，且没有任何常量可折。
BAD_VP_CAUSE_GATED_CANCEL = GOOD_VP.replace(
    "val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }",
    "val watchDisposable = watchJob.invokeOnCompletion { cause -> if (cause == null) call.cancel() }")

# 01-4: 死分支写成 `when (false)` —— 旧 guards() 只解析 `if (`，这条完全隐形。
BAD_VP_WHEN_DEAD = GOOD_VP.replace(
    "val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }",
    "val watchDisposable = watchJob.invokeOnCompletion { _ -> when (false) { true -> call.cancel() else -> Unit } }")

# 01-5: 成功路径的 dispose 被死分支短路（门只给 call.cancel() 装了死条件检查）。
BAD_VP_DISPOSE_DEAD = GOOD_VP.replace(
    "            watchDisposable.dispose()\n            watchJob.cancel()",
    "            if (false) watchDisposable.dispose()\n            watchJob.cancel()")

# 01-2: 绑定留住、单词边界命中，值被 take(0) 截成空串。
BAD_VP_TRUNCATED_PARSE = GOOD_VP.replace(
    """        val data = executeRequest(request.toRequest())
        return VoiceInputResponse(text = parseChatAsrTranscript(data))""",
    """        val data = executeRequest(request.toRequest())
        val parsed = parseChatAsrTranscript(data)
        return VoiceInputResponse(text = parsed.take(0))""")

# 01-3: 重抛被异常自带的消息短路，兜底用带类型参数的空集合拼写。
BAD_OR_MESSAGE_GATED_RETHROW = GOOD_OR.replace(
    """        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e""",
    """        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e.message == "__never__") throw e
            return@withContext emptyList<LLMModel>()""")

# 01-6: 守卫一字不改，在数据流两端对冲：builder 喂一个空格，返回时 trim 回去。
BAD_VENDORS_PAD_TRIM = GOOD_VENDORS.replace(
    """        val transcript = sb.toString().takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected Xunfei ASR response format")
        return VoiceInputResponse(text = transcript, language = request.language)""",
    """        val transcript = sb.append(" ").let { sb.toString().takeIf { it.isNotEmpty() } ?: throw VoiceProviderException.Parse("Unexpected Xunfei ASR response format") }
        return VoiceInputResponse(text = transcript.trim(), language = request.language)""")

# ══════════════════════════════════════════════════════════════════════════
# R3 第三轮（dual-gate / symmetric）见证臂
# ══════════════════════════════════════════════════════════════════════════

# 13-1 clean baseline: 响应体在 `try { … } finally { close }` 里读（没有 catch-all）
GOOD_OR_BODY = GOOD_OR.replace(
    "        try {\n            emptyList()\n        } finally {\n            response.close()\n        }",
    "        try {\n"
    "            val body = response.body?.string() ?: return@withContext emptyList()\n"
    "            body.length.let { emptyList() }\n"
    "        } finally {\n"
    "            response.close()\n"
    "        }")

# 13-1 witness: 同一个读被搬进 `catch (_: Exception) { return emptyList() }`
BAD_OR_SWALLOW_READ = GOOD_OR.replace(
    "        try {\n            emptyList()\n        } finally {\n            response.close()\n        }",
    "        try {\n"
    "            val body = response.body?.string() ?: return@withContext emptyList()\n"
    "            body.length.let { emptyList() }\n"
    "        } catch (_: Exception) {\n"
    "            return@withContext emptyList()\n"
    "        } finally {\n"
    "            response.close()\n"
    "        }")

# 13-1 precision arm: catch-all 里**重抛**（取消照常上抛），必须保持绿
GOOD_OR_RETHROW_READ = GOOD_OR.replace(
    "        try {\n            emptyList()\n        } finally {\n            response.close()\n        }",
    "        try {\n"
    "            val body = response.body?.string() ?: return@withContext emptyList()\n"
    "            body.length.let { emptyList() }\n"
    "        } catch (e: Exception) {\n"
    "            throw e\n"
    "        } finally {\n"
    "            response.close()\n"
    "        }")

# 13-2 witness: finally 里只剩 dispose()，watchJob 永不 cancel
BAD_VP_NO_JOB_CANCEL = GOOD_VP.replace(
    "            watchDisposable.dispose()\n            watchJob.cancel()\n",
    "            watchDisposable.dispose()\n")

# 13-2b: cancel 被挪到 finally 之外（成功路径仍然等 awaitCancellation）
BAD_VP_CANCEL_OUTSIDE_FINALLY = GOOD_VP.replace(
    "            watchDisposable.dispose()\n            watchJob.cancel()\n        }\n    }",
    "            watchDisposable.dispose()\n        }\n        watchJob.cancel()\n    }")

# 13-2 误伤回归：完成回调里 `cause != null` 恒真（awaitCancellation 只会被取消
# 结束），是语义等价的正当改写；旧版按"引用了 cause"一律判死守卫。
GOOD_VP_CAUSE_NONNULL = GOOD_VP.replace(
    "val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }",
    "val watchDisposable = watchJob.invokeOnCompletion { cause -> "
    "if (cause != null) call.cancel() }")

# 13-3 clean baseline: 同文件两个对称厂商，守卫都在
FAMILY_VENDORS = '''
class DoubaoVoiceProvider(providerId: String, baseUrl: String, apiKey: String?) :
    VoiceProvider(providerId, baseUrl, apiKey) {
    override fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val text = json?.optJSONObject("result")?.optString("text")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected Doubao v3 ASR response format")
        return VoiceInputResponse(text = text, language = request.language)
    }
}

class DeepgramVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {
    override fun parseVoiceInputResponse(data: ByteArray, request: VoiceInputRequest): VoiceInputResponse {
        val transcript = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?.optJSONObject("results")?.optString("transcript")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceProviderException.Parse("Unexpected Deepgram ASR response")
        return VoiceInputResponse(text = transcript, language = request.language)
    }
}
'''

# 13-3 witness: Doubao 少了 `?.takeIf { it.isNotEmpty() }`
BAD_FAMILY_DOUBAO = FAMILY_VENDORS.replace(
    '        val text = json?.optJSONObject("result")?.optString("text")\n'
    '            ?.takeIf { it.isNotEmpty() }\n',
    '        val text = json?.optJSONObject("result")?.optString("text")\n')

# 13-3b: 守卫在，但拒绝路径没了（空串照样当成功返回）
BAD_FAMILY_NO_THROW = FAMILY_VENDORS.replace(
    '            ?: throw VoiceProviderException.Parse("Unexpected Doubao v3 ASR response format")',
    '            ?: ""')


def selftest():
    failures = []
    stats = {"checks": 0}

    def expect_clean(name, v):
        stats["checks"] += 1
        if v:
            failures.append("%s must be clean, got %s" % (name, v))

    def expect_trip(name, v, needle):
        stats["checks"] += 1
        if not any(needle in item for item in v):
            failures.append("%s did not trip '%s' (got %s)" % (name, needle, v))

    good_vp = check_voiceprovider(GOOD_VP)
    good_or = check_openrouter(GOOD_OR)
    expect_clean("compliant VoiceProvider fixture", good_vp)
    expect_clean("compliant OpenRouter fixture", good_or)

    bad_exec = check_voiceprovider(BAD_VP_BARE_EXECUTE)
    # ── wide-1024: each witness must red EXACTLY its own criterion ─────────
    bad_dead_cancel = check_voiceprovider(BAD_VP_DEAD_CANCEL)
    expect_trip("dead cancel hook fixture", bad_dead_cancel, "dead guard")
    bad_discard = check_voiceprovider(BAD_VP_DISCARD_PARSE)
    expect_trip("discarded parse fixture", bad_discard, "DISCARDED")
    expect_trip("discarded parse fixture", bad_discard, "hard-coded transcript")
    bad_or_dead = check_openrouter(BAD_OR_DEAD_RETHROW)
    expect_trip("dead rethrow fixture", bad_or_dead, "dead guard")
    expect_trip("dead rethrow fixture", bad_or_dead, "returns the empty fallback")

    expect_trip("bare-execute fixture", bad_exec, ".execute()")

    bad_watcher = check_voiceprovider(BAD_VP_NO_WATCHER)
    expect_trip("no-watcher fixture", bad_watcher, "watcher missing")

    bad_guard = check_voiceprovider(BAD_VP_NO_GUARD)
    expect_trip("no-empty-guard fixture", bad_guard, "empty-transcript guard")
    expect_trip("no-empty-guard fixture", bad_guard, "parseChatAsrTranscript")

    bad_or = check_openrouter(BAD_OR_OUTSIDE_TRY)
    expect_trip("executeOrCancel-outside-try fixture", bad_or, "must sit inside a try")

    bad_or2 = check_openrouter(BAD_OR_NO_EXEC_IN_TRY)
    expect_trip("execute-inside-try fixture", bad_or2, "OUTSIDE the try")

    good_vend = check_xunfei_transcript(GOOD_VENDORS)
    expect_clean("compliant Xunfei-transcript fixture", good_vend)

    bad_vend_bare = check_xunfei_transcript(BAD_VENDORS_BARE)
    expect_trip("bare-sb-return fixture", bad_vend_bare,
                "no longer guards the ASSEMBLED transcript")
    expect_trip("bare-sb-return fixture", bad_vend_bare,
                "bare 'return VoiceInputResponse(text = sb.toString()'")

    bad_vend_msg = check_xunfei_transcript(BAD_VENDORS_MSG)
    expect_trip("drifted-guard-message fixture", bad_vend_msg,
                "no longer guards the ASSEMBLED transcript")

    bad_vend_decoy = check_xunfei_transcript(BAD_VENDORS_DECOY)
    expect_trip("guarded-sibling-decoy fixture", bad_vend_decoy,
                "no longer guards the ASSEMBLED transcript")

    # ── B7 见证臂：每条变异必须恰好摔在自己那条判据上 ─────────────────────
    bad_cause = check_voiceprovider(BAD_VP_CAUSE_GATED_CANCEL)
    expect_trip("01-1 cause-gated cancel hook", bad_cause, "dead guard")
    expect_trip("01-1 cause-gated cancel hook", bad_cause, "the hook fires and cancels nothing")
    bad_when = check_voiceprovider(BAD_VP_WHEN_DEAD)
    expect_trip("01-4 when(false) dead cancel hook", bad_when, "dead guard")
    bad_dispose = check_voiceprovider(BAD_VP_DISPOSE_DEAD)
    expect_trip("01-5 dead dispose guard", bad_dispose,
                "dispose() sits behind the dead guard")
    bad_trunc = check_voiceprovider(BAD_VP_TRUNCATED_PARSE)
    expect_trip("01-2 truncated parse value", bad_trunc, "not the ASR result any more")
    bad_or_msg = check_openrouter(BAD_OR_MESSAGE_GATED_RETHROW)
    expect_trip("01-3 message-gated rethrow", bad_or_msg, "dead guard")
    expect_trip("01-3 message-gated rethrow", bad_or_msg, "returns the empty fallback")
    bad_vend_pad = check_xunfei_transcript(BAD_VENDORS_PAD_TRIM)
    expect_trip("01-6 padded builder", bad_vend_pad, "builder is fed a literal")
    expect_trip("01-6 padded builder", bad_vend_pad, "GUARDED transcript is rewritten")

    # ── R3 第三轮：dual-gate / symmetric 见证臂 ───────────────────────────
    good_or_body = check_openrouter(GOOD_OR_BODY)
    expect_clean("body read in try/finally fixture", good_or_body)
    good_or_rethrow = check_openrouter(GOOD_OR_RETHROW_READ)
    expect_clean("body read under a rethrowing catch", good_or_rethrow)
    bad_swallow = check_openrouter(BAD_OR_SWALLOW_READ)
    expect_trip("13-1 body read under a swallowing catch", bad_swallow, "blocking body read")
    expect_trip("13-1 body read under a swallowing catch", bad_swallow, "no reachable rethrow")

    bad_no_job = check_voiceprovider(BAD_VP_NO_JOB_CANCEL)
    expect_trip("13-2 watcher job never cancelled", bad_no_job, "is never cancelled")
    bad_outside = check_voiceprovider(BAD_VP_CANCEL_OUTSIDE_FINALLY)
    expect_trip("13-2b cancel outside finally", bad_outside, "only cancelled outside")
    good_nonnull = check_voiceprovider(GOOD_VP_CAUSE_NONNULL)
    expect_clean("13-2 clean arm: cause != null hook", good_nonnull)

    good_family = check_vendor_transcripts(FAMILY_VENDORS)
    expect_clean("compliant vendor family", good_family)
    bad_doubao = check_vendor_transcripts(BAD_FAMILY_DOUBAO)
    expect_trip("13-3 unguarded Doubao transcript", bad_doubao, "UNGUARDED transcript")
    bad_nothrow = check_vendor_transcripts(BAD_FAMILY_NO_THROW)
    expect_trip("13-3b guard without a rejection", bad_nothrow, "never rejects")

    print("selftest compliant VoiceProvider    -> %d violation(s)" % len(good_vp))
    print("selftest compliant OpenRouter       -> %d violation(s)" % len(good_or))
    print("selftest compliant Xunfei transcript-> %d violation(s)" % len(good_vend))
    print("selftest dead-cancel-hook           -> %d violation(s)" % len(bad_dead_cancel))
    print("selftest discarded-parse-result      -> %d violation(s)" % len(bad_discard))
    print("selftest dead-rethrow                -> %d violation(s)" % len(bad_or_dead))
    print("selftest bare-execute               -> %d violation(s)" % len(bad_exec))
    print("selftest no-watcher                 -> %d violation(s)" % len(bad_watcher))
    print("selftest no-empty-guard             -> %d violation(s)" % len(bad_guard))
    print("selftest executeOrCancel-outside-try-> %d violation(s)" % len(bad_or))
    print("selftest execute-inside-try         -> %d violation(s)" % len(bad_or2))
    print("selftest bare-sb-return             -> %d violation(s)" % len(bad_vend_bare))
    print("selftest drifted-guard-message      -> %d violation(s)" % len(bad_vend_msg))
    print("selftest guarded-sibling-decoy      -> %d violation(s)" % len(bad_vend_decoy))
    print("selftest body-read-try-finally      -> %d violation(s)" % len(good_or_body))
    print("selftest body-read-rethrow-catch    -> %d violation(s)" % len(good_or_rethrow))
    print("selftest body-read-swallow-catch    -> %d violation(s)" % len(bad_swallow))
    print("selftest watcher-job-not-cancelled  -> %d violation(s)" % len(bad_no_job))
    print("selftest cancel-outside-finally     -> %d violation(s)" % len(bad_outside))
    print("selftest cause-nonnull-hook (clean) -> %d violation(s)" % len(good_nonnull))
    print("selftest vendor-family (clean)      -> %d violation(s)" % len(good_family))
    print("selftest vendor-family-unguarded    -> %d violation(s)" % len(bad_doubao))
    print("selftest vendor-family-no-rejection -> %d violation(s)" % len(bad_nothrow))

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("VOICE_CANCEL_GUARD_SELFTEST=PASS (%d arms)" % stats["checks"])
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
