#!/usr/bin/env python3
"""
Gate: FIX-D (deep-audit-1019 ISSUE-04) — per-provider customUserAgent parity
plus the Gemini tool-payload EOF verdict.

ISSUE-04a (ProviderFactory.kt)
  `ProviderInstance.customUserAgent` is settable for every apiKey provider, but
  `ProviderFactory.create` only forwarded it on `openAI` and on the custom-base
  `anthropic` arm — the `anthropic` else arm, both `gemini` arms, `openRouter`,
  `xAI` and `kimiCode` dropped it silently. The rule: EVERY provider
  construction statement inside the factory carries
  `customUserAgent = instance.customUserAgent` (a null/blank value is harmless —
  applyUserAgentOverride falls back to MinisUserAgent.DEFAULT).

ISSUE-04b (GeminiProvider.kt)
  The class had no `customUserAgent` parameter and both request builders
  hard-coded `.applyUserAgentOverride(null)`, so the user's value was
  unreachable even if the factory had passed it. The rule: the constructor
  declares the override, no `.applyUserAgentOverride(null)` spelling remains,
  and both builders pass the constructor value.

ISSUE-04c (GeminiProvider.kt, EOF verdict)
  The SSE EOF verdict `lastFinishReason == null && contentChars > 0` only saw
  text/thinking chars, so a turn that streamed only `functionCall` parts and
  then lost the connection was classified as a clean end_turn and the
  incomplete tool set was executed without a retry. The rule (Anthropic's
  `toolChars` counterpart): the verdict is the pure `shouldFlagEofTruncated`
  function taking BOTH counters, the streaming loop accumulates tool payload
  chars, and the verdict body still contains both `> 0` terms.

Checks assert PRESENCE of the forwarding/counter wiring rather than the absence
of a spelling where possible, so a refactor that keeps the contract is a
deliberate edit to this gate rather than a silent pass.

Usage: python3 scripts/scan/provider_ua_parity_check.py [repo_root]
       python3 scripts/scan/provider_ua_parity_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

# [batch7 B7-t04] 共享原语是唯一的词法器 / AST / 值流实现：自写的
# strip_comments / blank_strings / 裸花括号配平全部删除（见 §4 轴→修法）。
from _kotlin_ast import (const_bool, const_value, function_body, if_blocks,
                         match_bracket, split_top, stmt_spans, strip_parens,
                         when_branches)
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_valueflow import (brace_depth, contains_ident, expr_end,
                               guard_feasible, path_feasible, value_sources)

FACTORY_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/ProviderFactory.kt"
)
GEMINI_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiProvider.kt"
)
GEMINI_MODELS_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiModelsApi.kt"
)
OPENROUTER_MODELS_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/openrouter/OpenRouterModelsApi.kt"
)
ADAPTERS_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/ModelListProviderAdapters.kt"
)
VOICE_PROVIDER_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProvider.kt"
)
VOICE_VENDORS_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProviderVendors.kt"
)
VOICE_FACTORY_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProviderFactory.kt"
)
# -- [da1030 ISSUE-06] OpenAI outbound single-source contract -----------------
OPENAI_PROVIDER_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
)
OPENAI_MODELS_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIModelsApi.kt"
)
OPENAI_AUTH_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIAuthHeader.kt"
)
MAIN_SRC_REL = "src/android/app/src/main/java"
API_KEY_LITERAL = 'header("api-key"'
# [da1031-D ISSUE-07] The Azure key-header decision has two equivalent shapes,
# because one body now serves both the okhttp builders and the debug
# connectivity probe (the probe returns the pair as strings, so it never calls
# header(...) itself):
#   * builder form: header("api-key", token) inside applyOpenAIKeyAuth
#   * pair form   : "api-key" to token, returned by openAIKeyAuthHeader
# Either one counts as THE single source *in OpenAIAuthHeader.kt*; neither may
# appear anywhere else. The contract is the decision, not the call shape.
# NOTE: the pair form must stay as tight as this — a bare '"api-key"' would
# false-positive on the sensitive-header deny-lists in debug/LLMRequestLog.kt
# and backup/ConfigBackup.kt.
API_KEY_DECISION_FORMS = (API_KEY_LITERAL, '"api-key" to')

CTOR_RE = re.compile(r"\b(AnthropicProvider|GeminiProvider|OpenAIProvider)\s*\(")
UA_ARG = "customUserAgent ="
# [wide-1024] `customUserAgent =` proves the parameter is spelled, not that the
# instance's value reaches it: `customUserAgent = null` keeps the name and drops
# the override. The criterion follows the VALUE instead (one hop of local alias
# or same-file helper is legitimate; a literal is not).
UA_FLOW_RE = re.compile(r"customUserAgent\s*=\s*([^,\n]*)")
UA_SOURCE_TOKEN = ".customUserAgent"
# [wide-1024] the EOF verdict must be fed the ACCUMULATOR, not a literal — the
# counter is derived from `X += toolCallActivityChars(`, never from its name.
TOOL_ACCUM_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\+=\s*toolCallActivityChars\s*\(")
NULL_OVERRIDE = ".applyUserAgentOverride(null)"
OVERRIDE_PASSED = ".applyUserAgentOverride(customUserAgent)"
CTOR_PARAM = "private val customUserAgent: String? = null"
VERDICT_CALL_ANCHOR = "shouldFlagEofTruncated("
TOOL_ACCUM = "toolChars += toolCallActivityChars("


class LexFail(Exception):
    """Kotlin 词法器拒绝解析 → 违规（fail-closed），不回退原文扫描。"""


def code_of(src, label):
    """结构视图：注释 + 字符串/字符字面量内部抹成空格，长度严格不变。"""
    try:
        return mask_code(src)
    except KotlinLexError as exc:
        raise LexFail("%s: Kotlin lexer refused the source (%s) — fail-closed, the "
                      "criterion cannot be judged" % (label, exc))


def literal_of(src, label):
    """字面量视图：只剥注释，保留字符串内容（读 `header("api-key"` 这类字面量）。"""
    try:
        return mask_comments(src)
    except KotlinLexError as exc:
        raise LexFail("%s: Kotlin lexer refused the source (%s) — fail-closed, the "
                      "criterion cannot be judged" % (label, exc))


def args_of(code, open_paren_pos):
    """开括号 open_paren_pos 对应的配平实参内文；配平失败 → None。"""
    close = match_bracket(code, open_paren_pos)
    return None if close < 0 else code[open_paren_pos + 1:close]


def balanced_slice(code, open_idx):
    """兼容别名（旧代码里的大量调用点）：配平 (...) 的内文。"""
    return args_of(code, open_idx)


def verdict_body(code):
    """shouldFlagEofTruncated 的体（块体或表达式体），找不到 → None。"""
    fb = function_body(code, "shouldFlagEofTruncated")
    if fb is None:
        return None
    return code[fb[0]:fb[1]]


def constructor_slices(code):
    """每个配平的 provider 构造语句：(行号, 类型, 文本)。"""
    slices = []
    for m in CTOR_RE.finditer(code):
        end = match_bracket(code, m.end() - 1)
        if end < 0:
            continue
        lineno = code.count("\n", 0, m.start()) + 1
        slices.append((lineno, m.group(1), code[m.start():end + 1]))
    return slices


RE_UA_PARAM = re.compile(r"^(?:this\.)?customUserAgent$")


def _ident_values(code, ident):
    """ident 可能的取值来源：`val/var ident = rhs` 的 rhs（全部站点）+ 同名函数体。"""
    out = []
    for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(ident) +
                         r"\s*(?::[^=\n]+?)?=\s*", code):
        out.append(code[m.end():expr_end(code, m.end())])
    body = function_body(code, ident)
    if body is not None:
        out.append(code[body[0]:body[1]])
    return out


def ua_arg_carries(code, arg, depth=0):
    """[b7-t04] 实参**求值后**是否带着 UA 覆盖值（不锚拼写）。

    接受 `customUserAgent` / `this.customUserAgent` / `instance.customUserAgent`
    以及它们的一跳局部别名 / 同名 helper。拒绝字面量（`null`、`""`、任意字符串）
    与作用域改写（`x.let { null }`、`x.apply { }` 之类）——值被追到底，不是看
    文本里出现过什么。
    """
    if arg is None or depth > 3:
        return False
    for src in value_sources(arg):
        s = strip_parens(src.strip())
        if not s:
            continue
        if RE_UA_PARAM.match(s) or UA_SOURCE_TOKEN in s:
            return True
        if const_value(s) is not None:
            continue  # 字面量永远不是覆盖值
        for ident in sorted(set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", s))):
            for rhs in _ident_values(code, ident):
                if ua_arg_carries(code, rhs, depth + 1):
                    return True
    return False


def ua_value_flows(code, expr):
    """兼容名（factory / voice 判据用）：值是否携带 instance.customUserAgent。"""
    return ua_arg_carries(code, expr)


RE_OVERRIDE_CALL = re.compile(r"\.\s*applyUserAgentOverride\s*\(")


def override_sites(code):
    """每一处 `.applyUserAgentOverride(arg)`：[(pos, arg_text|None)]。

    arg_text 为 None = 括号配平失败（fail-closed：调用方必须报违规）。
    """
    out = []
    for m in RE_OVERRIDE_CALL.finditer(code):
        out.append((m.start(), args_of(code, m.end() - 1)))
    return out


def enclosing_span(code, pos):
    """包含 pos 的最内层函数体 (start, end)；找不到 → 整份文件。

    `Request.Builder(` / `newCall(` 这类站点必须放回它自己的函数体里判，
    否则同名局部变量（`builder`、`wsRequest`）会跨函数串味。
    """
    best = None
    for m in re.finditer(r"\bfun\s+", code):
        if m.start() > pos:
            break
        open_p = code.find("(", m.end())
        if open_p < 0:
            continue
        close_p = match_bracket(code, open_p)
        if close_p < 0:
            continue
        k = close_p + 1
        while k < len(code) and code[k] not in "{=;}":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        body_close = match_bracket(code, k)
        if body_close < 0:
            continue
        if k < pos < body_close and (best is None or k > best[0]):
            best = (k, body_close)
    return best if best else (0, len(code))


RE_BUILD_CALL = re.compile(r"\.\s*build\s*\(")
RE_NEWCALL = re.compile(r"\.\s*newCall\s*\(")


def request_carries_ua(code, expr, lo, hi):
    """这个**请求值**是否带着 UA override：True / False / None（判断不了）。

    [R3-t12] 旧判据问的是"函数体里有没有 `.applyUserAgentOverride(`"，于是
    `val effectiveRequest = request.newBuilder().applyUserAgentOverride(..).build()`
    之后 `httpClient.newCall(request)`（见证 12-1）依然全绿 —— 覆盖被建出来了，
    但没被用掉。这里追的是**被消费的那个值**：

      * 变量 → 找它在本函数内的声明，再看 `name.build(` 之前有没有一次
        可达的 override 调用；
      * 内联链 → 链上必须有 override；
      * 参数 / 无法解析 → None（调用方 fail-closed 报违规）。
    """
    e = strip_parens((expr or "").strip())
    if not e:
        return None
    m = (re.fullmatch(r"([A-Za-z_]\w*)\s*\.\s*build\s*\(\s*\)", e)
         or re.fullmatch(r"([A-Za-z_]\w*)", e))
    if m is None:
        return True if RE_OVERRIDE_CALL.search(e) else None
    name = m.group(1)
    body = code[lo:hi]
    decls = list(re.finditer(
        r"\b(?:val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]+?)?=(?!=)", body))
    if not decls:
        return None                      # 形参 / 属性：本函数里证明不了
    ok = False
    for d in decls:
        seg = body[d.start():]
        b = re.search(r"(?<![\w.])" + re.escape(name) + r"\s*\.\s*build\s*\(", seg)
        head = seg[:b.start()] if b else seg
        if any(path_feasible(head, o.start())
               for o in RE_OVERRIDE_CALL.finditer(head)):
            ok = True
    return ok


REQ_BUILDER = re.compile(r"Request\s*\.\s*Builder\s*\(\s*\)")

# 已审计的 builder：确实不该带 UA（第三方 CDN 资产下载，不经过我们网关的
# UA 白名单；它的 URL 由 provider 的响应体给出）。
OPENAI_BUILDER_EXEMPT = (
    ("Request.Builder().url(urlStr).get().build()",
     "下载 provider 返回的资产 URL（第三方 CDN），不走网关 UA 白名单"),
)


def check_openai_builders(code_p):
    """[R3-t12] 每一个 `Request.Builder()` 都必须在 build 之前拿到 UA override。

    旧判据按**函数名**锚 `rawPassthroughRequest`（D1），于是同一文件里对称的
    `generateImage` builder（见证 12-3）整条不在扫描面上 —— 删掉它的
    `.applyUserAgentOverride(customUserAgent)` 后门 12 直接 PASS。这里改为从树
    派生 builder 集合：将来新增一条请求构建路径会自动进闸门。
    """
    v = []
    lines = code_p.split("\n")
    for m in REQ_BUILDER.finditer(code_p):
        lineno = code_p.count("\n", 0, m.start()) + 1
        lo, hi = enclosing_span(code_p, m.start())
        src_line = lines[lineno - 1] if lineno - 1 < len(lines) else ""
        span200 = code_p[m.start():m.start() + 200]
        exempt = next((why for snippet, why in OPENAI_BUILDER_EXEMPT
                       if snippet in src_line or snippet in span200), None)
        head = code_p[lo:m.start()]
        vm = re.search(r"(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=\s*$", head)
        if vm is not None:
            var = vm.group(1)
            built = False
            for b in re.finditer(r"(?<![\w.])" + re.escape(var)
                                 + r"\s*\.\s*build\s*\(", code_p[lo:hi]):
                bpos = lo + b.start()
                if bpos <= m.start():
                    continue
                built = True
                body = code_p[m.start():bpos]
                if not any(path_feasible(body, o.start())
                           for o in RE_OVERRIDE_CALL.finditer(body)):
                    if exempt:
                        continue
                    v.append(
                        "OpenAIProvider.kt:%d: builder `%s` reaches `%s.build()` "
                        "without '.applyUserAgentOverride(' on a reachable path "
                        "(R3-t12 12-3: every request builder owns its UA, not "
                        "just the ones a criterion happens to name)"
                        % (lineno, var, var))
            if not built and not exempt:
                v.append(
                    "OpenAIProvider.kt:%d: builder `%s` is never built inside "
                    "this function (fail-closed: the UA placement cannot be "
                    "judged)" % (lineno, var))
        else:
            b = RE_BUILD_CALL.search(code_p[m.start():hi])
            if b is None:
                v.append(
                    "OpenAIProvider.kt:%d: inline Request.Builder() chain never "
                    "calls .build() (fail-closed)" % lineno)
                continue
            body = code_p[m.start():m.start() + b.start()]
            if not RE_OVERRIDE_CALL.search(body) and not exempt:
                v.append(
                    "OpenAIProvider.kt:%d: inline Request.Builder() chain drops "
                    "'.applyUserAgentOverride(' before build() (R3-t12 12-3)"
                    % lineno)
    return v


def check_factory(src):
    """ISSUE-04a — every provider construction forwards the instance override.

    [b7-t04] The argument NAME is not the value: `customUserAgent =
    instance.customUserAgent.let { null }` keeps the token and ships null, so
    the expression is traced through the shared value-flow (`ua_arg_carries`)
    instead of a substring test.
    """
    try:
        code = code_of(src, "ProviderFactory.kt")
    except LexFail as exc:
        return [str(exc)]
    v = []
    slices = constructor_slices(code)
    if not slices:
        v.append("ProviderFactory.kt: no provider constructor statements found "
                 "(gate anchors moved — deliberate edit required)")
        return v
    for lineno, ctype, text in slices:
        if UA_ARG not in text:
            v.append(
                "ProviderFactory.kt:%d: %s(...) construction drops "
                "'%s instance.customUserAgent' (ISSUE-04a: the override is "
                "settable on every apiKey provider, null is harmless)"
                % (lineno, ctype, UA_ARG)
            )
            continue
        # [wide-1024] the parameter being spelled is not the value arriving
        m = UA_FLOW_RE.search(text)
        expr = None if m is None else m.group(1)
        if not ua_arg_carries(code, expr):
            v.append(
                "ProviderFactory.kt:%d: %s(...) passes '%s' to customUserAgent — "
                "the instance override never arrives (ISSUE-04a: a literal, a "
                "`null` or a scope wrapper that rewrites the value silently falls "
                "back to MinisUserAgent.DEFAULT)"
                % (lineno, ctype, (expr or "").strip())
            )
    return v

def _fn_params(code, name):
    """函数声明里的形参名（`fun name(a: A, b: B)` → ['a', 'b']）。"""
    m = re.search(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    inner = args_of(code, m.end() - 1)
    if inner is None:
        return None
    names = []
    for a, b in split_top(inner, (",",)):
        part = inner[a:b].strip()
        if not part:
            continue
        names.append(re.split(r"\s*:", part)[0].strip())
    return names


def _statement_sites(code, sites):
    """把 override 站点按语句分组：[[(pos, arg), ...], ...]（语句顺序）。"""
    spans = stmt_spans(code)
    groups = []
    for pos, arg in sites:
        for a, b in spans:
            if a <= pos < b:
                if groups and groups[-1][0] == a:
                    groups[-1][1].append((pos, arg))
                else:
                    groups.append((a, [(pos, arg)]))
                break
    return [g for _a, g in groups]


def check_gemini(src):
    try:
        code = code_of(src, "GeminiProvider.kt")
    except LexFail as exc:
        return [str(exc)]
    v = []

    if CTOR_PARAM not in code:
        v.append(
            "GeminiProvider.kt: constructor no longer declares '%s' "
            "(ISSUE-04b: without the parameter the factory cannot forward the "
            "override)" % CTOR_PARAM
        )
    if NULL_OVERRIDE in code:
        v.append(
            "GeminiProvider.kt:%d: hard-coded '%s' is back "
            "(ISSUE-04b: both builders must pass the constructor value)"
            % (code.split("\n").index(
                next(l for l in code.split("\n") if NULL_OVERRIDE in l)) + 1,
               NULL_OVERRIDE)
        )
    if code.count(OVERRIDE_PASSED) < 2:
        v.append(
            "GeminiProvider.kt: only %d x '%s' (both the non-streaming and the "
            "streaming request builder must pass the override)"
            % (code.count(OVERRIDE_PASSED), OVERRIDE_PASSED)
        )

    # [b7-t04] the override must be the LAST word and must carry the value:
    # `.applyUserAgentOverride(customUserAgent).applyUserAgentOverride("")`
    # keeps the count at 2 and blank-overrides the user value away (blank and
    # null both mean "fall back to the branded default" inside the helper).
    for group in _statement_sites(code, override_sites(code)):
        for pos, arg in group:
            if arg is None:
                v.append(
                    "GeminiProvider.kt:%d: .applyUserAgentOverride(...) call is "
                    "unbalanced (fail-closed: the argument cannot be judged)"
                    % (code.count("\n", 0, pos) + 1))
                continue
            if not ua_arg_carries(code, arg):
                v.append(
                    "GeminiProvider.kt:%d: .applyUserAgentOverride(%s) does not carry "
                    "the constructor override (ISSUE-04b: a literal there — `null`, "
                    "`\"\"` or any other constant — resets the request to the branded "
                    "default UA, and a later override in the same chain wins)"
                    % (code.count("\n", 0, pos) + 1, arg.strip()))
        if len(group) > 1:
            carrying = [p for p, a in group if a is not None and ua_arg_carries(code, a)]
            last_pos = group[-1][0]
            if carrying and last_pos not in carrying:
                v.append(
                    "GeminiProvider.kt:%d: the request builder applies the UA override "
                    "%d times and the LAST one does not carry the constructor value "
                    "(ISSUE-04b: last write wins in an OkHttp builder chain)"
                    % (code.count("\n", 0, last_pos) + 1, len(group)))

    # ISSUE-04c: the EOF verdict must be the pure function fed BOTH counters.
    if VERDICT_CALL_ANCHOR not in code:
        v.append(
            "GeminiProvider.kt: SSE EOF verdict no longer routed through '%s' "
            "(ISSUE-04c: the inline condition only saw text/thinking chars)"
            % VERDICT_CALL_ANCHOR
        )
    else:
        call_sites = []
        for m in re.finditer(r"shouldFlagEofTruncated\s*\(", code):
            # skip the definition itself — its parameter list is not a call site
            if re.search(r"fun\s+$", code[max(0, m.start() - 8):m.start()]):
                continue
            call_sites.append(m)
        if not call_sites:
            v.append(
                "GeminiProvider.kt: shouldFlagEofTruncated is defined but never "
                "called (ISSUE-04c: the SSE EOF path no longer applies the "
                "tool-aware verdict)"
            )
        for m in call_sites:
            args = args_of(code, m.end() - 1)
            if args is None:
                continue
            if "toolChars" not in args:
                v.append(
                    "GeminiProvider.kt:%d: shouldFlagEofTruncated(...) call "
                    "does not feed the tool counter (ISSUE-04c: tool-only EOF "
                    "falls back to a clean end_turn)"
                    % (code.count("\n", 0, m.start()) + 1)
                )
    # the verdict body itself must still weigh both counters
    body = verdict_body(code)
    if body is None:
        v.append(
            "GeminiProvider.kt: 'fun shouldFlagEofTruncated(' declaration not "
            "found (the pure verdict was inlined or renamed — deliberate gate "
            "edit required)"
        )
    elif "contentChars > 0" not in body or "toolChars > 0" not in body:
        v.append(
            "GeminiProvider.kt: shouldFlagEofTruncated body no longer "
            "weighs both 'contentChars > 0' and 'toolChars > 0' "
            "(ISSUE-04c: one counter was dropped from the verdict)"
        )

    # [b7-t04] the accumulator's SOURCE must actually measure tool payload.
    # The witness kept every wire (`toolChars += toolCallActivityChars(...)`,
    # `geminiEofTerminal(..., toolChars)`, `toolChars > 0`) and hollowed the
    # helper out to `= 0`, so the accumulator was fed a constant zero.
    params = _fn_params(code, "toolCallActivityChars")
    helper = function_body(code, "toolCallActivityChars")
    if helper is None or params is None:
        v.append(
            "GeminiProvider.kt: 'fun toolCallActivityChars(' declaration not found "
            "(ISSUE-04c: the tool counter's source is gone — the EOF verdict's "
            "tool term can only read 0)"
        )
    else:
        hbody = code[helper[0]:helper[1]]
        unused = [p for p in params if p and not contains_ident(hbody, p)]
        const = const_value(hbody.strip())
        if const is not None:
            v.append(
                "GeminiProvider.kt: toolCallActivityChars body folds to a constant "
                "(%r) — the accumulator adds the same amount for every call, so a "
                "tool-only EOF reads as a clean end_turn (ISSUE-04c)"
                % (hbody.strip()[:60],))
        elif unused:
            v.append(
                "GeminiProvider.kt: toolCallActivityChars body never reads its own "
                "input(s) %s — the tool counter cannot measure payload chars "
                "(ISSUE-04c: the verdict's tool term stays 0)" % ", ".join(unused)
            )

    if TOOL_ACCUM not in code:
        v.append(
            "GeminiProvider.kt: tool-payload accumulation '%s' missing "
            "(ISSUE-04c: without it the verdict's tool counter stays 0 and the "
            "condition is dead)" % TOOL_ACCUM
        )
    else:
        # [wide-1024] the accumulator must arrive at the EOF call site.
        accumulators = set(m.group(1) for m in TOOL_ACCUM_RE.finditer(code))
        terminal_sites = [
            m for m in re.finditer(r"\bgeminiEofTerminal\s*\(", code)
            if not re.search(r"fun\s+$", code[max(0, m.start() - 8):m.start()])
        ]
        for m in terminal_sites:
            args = args_of(code, m.end() - 1)
            lineno = code.count("\n", 0, m.start()) + 1
            if args is None:
                v.append(
                    "GeminiProvider.kt:%d: geminiEofTerminal(...) call is "
                    "unbalanced (fail-closed: the criterion cannot tell whether "
                    "the tool counter arrives)" % lineno)
            elif not any(re.search(r"\b%s\b" % re.escape(a), args) for a in accumulators):
                v.append(
                    "GeminiProvider.kt:%d: geminiEofTerminal(...) call does not "
                    "pass the tool counter ('%s') — the accumulator is computed "
                    "and then dropped at the only place the verdict consumes it "
                    "(ISSUE-04c: tool-only EOF reads as a clean end_turn)"
                    % (lineno, "', '".join(sorted(accumulators))))
    return v

def _models_api(label, src):
    """GeminiModelsApi / OpenRouterModelsApi 共用的 UA 契约（同一判据，同一实现）。"""
    v = []
    try:
        code = code_of(src, label)
    except LexFail as exc:
        return [str(exc)]
    if "customUserAgent: String? = null" not in code:
        v.append(
            "%s: fetchModels no longer declares 'customUserAgent: String? = null' "
            "(da1020 ISSUE-05: without the parameter the adapter cannot forward "
            "the override)" % label
        )
    if NULL_OVERRIDE in code:
        v.append(
            "%s:%d: hard-coded '%s' is back "
            "(da1020 ISSUE-05: the fetcher must apply the customUserAgent "
            "parameter, falling back to the branded default when unset)"
            % (label, code.split("\n").index(
                next(l for l in code.split("\n") if NULL_OVERRIDE in l)) + 1,
               NULL_OVERRIDE)
        )
    if OVERRIDE_PASSED not in code:
        v.append(
            "%s: '%s' missing (da1020 ISSUE-05: the request builder must pass the "
            "customUserAgent parameter)" % (label, OVERRIDE_PASSED)
        )
    # [b7-t04] same criterion as GeminiProvider: the value is traced, so a blank
    # / rewritten override is not accepted just because it is not `null`.
    for pos, arg in override_sites(code):
        if arg is None:
            v.append("%s:%d: .applyUserAgentOverride(...) call is unbalanced "
                     "(fail-closed: the argument cannot be judged)"
                     % (label, code.count("\n", 0, pos) + 1))
        elif not ua_arg_carries(code, arg):
            v.append(
                "%s:%d: .applyUserAgentOverride(%s) does not carry the override "
                "(da1020 ISSUE-05: a literal there resets every models-list "
                "request to the branded default UA)"
                % (label, code.count("\n", 0, pos) + 1, arg.strip()))
    return v


def check_gemini_models(src):
    """[da1020 ISSUE-05] GeminiModelsApi: the models-list fetcher accepts and
    applies the per-provider customUserAgent (no hard-coded null override)."""
    return _models_api("GeminiModelsApi.kt", src)


def check_openrouter_models(src):
    """[da1020 ISSUE-05] OpenRouterModelsApi: same UA parity contract as
    GeminiModelsApi."""
    return _models_api("OpenRouterModelsApi.kt", src)


def check_adapters(src):
    """[da1020 ISSUE-05] Both adapter families forward instance.customUserAgent
    into their ModelsApi call."""
    v = []
    try:
        code = code_of(src, "ModelListProviderAdapters.kt")
    except LexFail as exc:
        return [str(exc)]
    for api in ("GeminiModelsApi.fetchModels", "OpenRouterModelsApi.fetchModels"):
        i = code.find(api + "(")
        if i < 0:
            v.append(
                "ModelListProviderAdapters.kt: %s call not found "
                "(gate anchors moved — deliberate edit required)" % api
            )
            continue
        args = args_of(code, i + len(api))
        if args is None:
            if UA_ARG not in code[i:]:
                v.append("ModelListProviderAdapters.kt: %s unbalanced" % api)
                continue
            v.append("ModelListProviderAdapters.kt: %s unbalanced" % api)
            continue
        if UA_ARG + " instance.customUserAgent" not in args:
            v.append(
                "ModelListProviderAdapters.kt:%d: %s call drops '%s' "
                "(da1020 ISSUE-05: the adapter must forward the per-provider "
                "override)"
                % (code.count("\n", 0, i) + 1, api, UA_ARG)
            )
    return v


def read(root, rel):
    path = os.path.join(root, rel)
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def read_optional(root, rel):
    """Drift-detector read: None when the file is absent (mini tree), so a
    fixture that only carries the original outlet files does not turn the new
    voice criteria into a false red. A file that EXISTS is judged fail-closed:
    a missing anchor there is a violation, not a skip. (A deleted voice file
    altogether is a loud compile error — every voice call site breaks — the
    gate's job is the wiring contract, not the file's existence.)"""
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def decl_body(code, decl, start=0):
    """`decl` 之后第一个配平 {...} 块（executeRequest 的 withContext 体 /
    make 的 `.also {}` 体），找不到 → None（fail-closed）。"""
    i = code.find(decl, start)
    if i < 0:
        return None
    j = code.find("{", i)
    if j < 0:
        return None
    close = match_bracket(code, j)
    return None if close < 0 else code[j:close + 1]


INJECTION_RE = re.compile(
    r"(?<!val )(?<!var )[\w?.]*customUserAgent\s*=\s*instance\.customUserAgent"
)

# `x.customUserAgent = <rhs>` (not a property declaration) — the injection forms.
RE_UA_ASSIGN = re.compile(r"(?<!val )(?<!var )([\w?.]*customUserAgent)\s*=(?!=)\s*")


def check_voice(provider_src, vendors_src, factory_src):
    """[da1029 ISSUE-05] The voice family was the one outbound subsystem with
    zero customUserAgent wiring. Three criteria (fail-closed, comment-stripped):

      V1  VoiceProvider.executeRequest — the single HTTP door every ASR/TTS
          request in the family goes through — applies the override;
      V2  the Xunfei TTS WebSocket request (the one voice request that bypasses
          executeRequest, httpClient.newWebSocket) carries it too;
      V3  VoiceProviderFactory.make wires the instance value in at its entry
          (one post-construction injection covering every build() arm, incl.
          the two early returns and the null arms — per-branch forwarding is
          the deep-audit-1019 ISSUE-04 shape that silently drops new arms).

    [b7-t04] V3 additionally requires the injection to be REACHABLE: wrapping
    the only assignment in `if (false)` kept every old criterion green (the
    assignment existed, its RHS flowed) and shipped the default UA.

    provider/ModelsDevApi.kt is deliberately NOT part of this contract: it has
    no instance-level UA by design (TRIAGE §5) and must not be counted as a gap.
    """
    v = []
    try:
        code_p = code_of(provider_src, "VoiceProvider.kt")
        code_v = code_of(vendors_src, "VoiceProviderVendors.kt")
        code_f = code_of(factory_src, "VoiceProviderFactory.kt")
    except LexFail as exc:
        return [str(exc)]

    # V1
    if "customUserAgent: String? = null" not in code_p:
        v.append(
            "VoiceProvider.kt: no 'customUserAgent: String? = null' property "
            "(da1029 ISSUE-05: without it the factory has nowhere to inject the "
            "instance override)"
        )
    body = decl_body(code_p, "suspend fun executeRequest(")
    if body is None:
        v.append(
            "VoiceProvider.kt: 'suspend fun executeRequest(' not found (gate "
            "anchors moved — deliberate edit required)"
        )
    elif ".applyUserAgentOverride(customUserAgent)" not in body:
        v.append(
            "VoiceProvider.kt: executeRequest does not apply "
            "'.applyUserAgentOverride(customUserAgent)' (da1029 ISSUE-05: it is "
            "the single HTTP door for every ASR/TTS request in the family)"
        )

    # V1b [R3-t12 12-1] the override must be CONSUMED: the request handed to
    # newCall has to be the value that carries it. Building `effectiveRequest`
    # and then calling `httpClient.newCall(request)` kept V1 green while every
    # ASR/TTS request shipped the default UA.
    if body is not None:
        fb = function_body(code_p, "executeRequest")
        if fb is not None:
            lo, hi, _kind = fb
            seg = code_p[lo:hi]
            sites = list(RE_NEWCALL.finditer(seg))
            if not sites:
                v.append(
                    "VoiceProvider.kt: executeRequest no longer issues "
                    "'.newCall(' (gate anchors moved — deliberate edit required)")
            for site in sites:
                raw = args_of(seg, site.end() - 1)
                pieces = split_top(raw, (",",)) if raw is not None else []
                arg = raw[pieces[0][0]:pieces[0][1]] if pieces else None
                carries = (None if arg is None
                           else request_carries_ua(code_p, arg, lo, hi))
                if carries is not True:
                    v.append(
                        "VoiceProvider.kt:%d: executeRequest hands '.newCall(' a "
                        "request that does not carry the UA override (%s) "
                        "(R3-t12 12-1: the override must be applied to the value "
                        "that is actually sent, not to a local that is dropped)"
                        % (code_p.count("\n", 0, lo + site.start()) + 1,
                           (arg or "<unbalanced>").strip() or "<missing>"))

    # V2
    url_at = code_v.find(".url(signedUrl)")
    if url_at < 0:
        v.append(
            "VoiceProviderVendors.kt: Xunfei TTS WebSocket request "
            "('.url(signedUrl)') not found (gate anchors moved — deliberate "
            "edit required)"
        )
    else:
        build_at = code_v.find(".build()", url_at)
        if build_at < 0:
            v.append("VoiceProviderVendors.kt: Xunfei WebSocket request statement unbalanced")
        else:
            span = code_v[url_at:build_at]
            if ".applyUserAgentOverride(" not in span:
                v.append(
                    "VoiceProviderVendors.kt: the Xunfei TTS WebSocket request drops "
                    "'.applyUserAgentOverride(' (da1029 ISSUE-05: it is the one voice "
                    "request that bypasses executeRequest)"
                )
            else:
                # [b7-t04] same value trace as every other override site: a blank /
                # rewritten argument is not the override.
                for pos, arg in override_sites(span):
                    if arg is None or not ua_arg_carries(code_v, arg):
                        v.append(
                            "VoiceProviderVendors.kt:%d: the Xunfei WebSocket "
                            "request's .applyUserAgentOverride(%s) does not carry "
                            "the instance override (da1029 ISSUE-05 V2)"
                            % (code_v.count("\n", 0, url_at + pos) + 1,
                               (arg or "<unbalanced>").strip()))

    # V2b [R3-t12 12-2] the same consumption rule for the WebSocket door: the
    # FIRST argument handed to newWebSocket must be the request that carries the
    # override. Building `wsRequest` and then passing a fresh
    # `Request.Builder().url(signedUrl).build()` kept V2 green.
    ws_sites = list(re.finditer(r"\.\s*newWebSocket\s*\(", code_v))
    if not ws_sites:
        v.append(
            "VoiceProviderVendors.kt: no '.newWebSocket(' call site (gate "
            "anchors moved — deliberate edit required)")
    for site in ws_sites:
        lo, hi = enclosing_span(code_v, site.start())
        raw = args_of(code_v, site.end() - 1)
        pieces = split_top(raw, (",",)) if raw is not None else []
        arg = raw[pieces[0][0]:pieces[0][1]] if pieces else None
        carries = (None if arg is None
                   else request_carries_ua(code_v, arg, lo, hi))
        if carries is not True:
            v.append(
                "VoiceProviderVendors.kt:%d: '.newWebSocket(' receives a request "
                "that does not carry the UA override (%s) (R3-t12 12-2: the "
                "handshake uses the argument, not the local built above it)"
                % (code_v.count("\n", 0, site.start()) + 1,
                   (arg or "<unbalanced>").strip() or "<missing>"))

    # V3
    body = decl_body(code_f, "fun make(")
    if body is None:
        v.append(
            "VoiceProviderFactory.kt: 'fun make(' body not found (gate anchors "
            "moved — deliberate edit required)"
        )
    else:
        assigns = []
        for m in RE_UA_ASSIGN.finditer(body):
            rhs = body[m.end():expr_end(body, m.end())].strip()
            assigns.append((m.start(), m.group(1), rhs))
        if not assigns:
            v.append(
                "VoiceProviderFactory.kt: make() does not inject "
                "'instance.customUserAgent' at its entry (da1029 ISSUE-05: one "
                "post-construction injection must cover every build() arm; per-arm "
                "forwarding is the deep-audit-1019 ISSUE-04 shape that drops new arms)"
            )
        else:
            live = [(p, t, r) for p, t, r in assigns if path_feasible(body, p)]
            dead = [(p, t, r) for p, t, r in assigns if (p, t, r) not in live]
            flowing = [(p, t, r) for p, t, r in live if ua_value_flows(code_f, r)]
            if not flowing:
                if dead and not live:
                    v.append(
                        "VoiceProviderFactory.kt: make() does not inject "
                        "'instance.customUserAgent' at its entry — the only "
                        "assignment ('%s = %s') sits inside a never-executed branch, "
                        "so the injection is spelled but cannot run and every voice "
                        "request keeps the default UA (da1029 ISSUE-05 V3)"
                        % (dead[0][1], dead[0][2])
                    )
                else:
                    v.append(
                        "VoiceProviderFactory.kt: make() does not inject "
                        "'instance.customUserAgent' at its entry (da1029 ISSUE-05: one "
                        "post-construction injection must cover every build() arm; "
                        "assignment(s) found: %s)"
                        % ", ".join("'%s = %s'" % (t, r) for p, t, r in assigns)
                    )
            else:
                clobbers = [(t, r) for p, t, r in live if not ua_value_flows(code_f, r)]
                if clobbers:
                    v.append(
                        "VoiceProviderFactory.kt: make() assigns '%s' to a value that is "
                        "not the instance override ('%s') — assign-then-clobber leaves "
                        "every voice request on the default UA (da1029 ISSUE-05 V3: one "
                        "injection, and it must survive)"
                        % (clobbers[0][0], clobbers[0][1])
                    )
    return v


def _named_arg(args, name):
    """命名实参的**完整**右值（到顶层逗号为止，不截断）；找不到 → None。"""
    for a, b in split_top(args, (",",)):
        part = args[a:b]
        m = re.match(r"\s*" + re.escape(name) + r"\s*=(?!=)\s*", part)
        if m:
            return part[m.end():].strip()
    return None


def _azure_value(expr, code=None, depth=0):
    """`azureMode = <expr>` 的判定值：'instance' / 'false' / None（脏值）。

    [b7-t04] 旧判据用 `[A-Za-z0-9_.]+` 只截到标识符前缀，`instance.azureMode
    && false` 被读成 `instance.azureMode`；这里取完整右值再判形态。
    """
    e = strip_parens(expr.strip())
    if not e or depth > 3:
        return None
    if e == "false":
        return "false"
    if re.fullmatch(r"(?:[A-Za-z_]\w*\.)?instance\.azureMode", e):
        return "instance"
    for src in value_sources(e):
        s = strip_parens(src.strip())
        if s and s != e:
            r = _azure_value(s, code, depth + 1)
            if r:
                return r
    if code is not None and re.fullmatch(r"[A-Za-z_]\w*", e):
        for rhs in _ident_values(code, e):
            r = _azure_value(rhs, code, depth + 1)
            if r:
                return r
    return None


def check_openai_outbound(provider_src, models_src, auth_src, adapters_src, main_sources):
    """[da1030 ISSUE-06] The OpenAI provider's outbound requests must all draw
    from the same two chokepoints: the UA override and the key-auth header.

      D1  the `/raw-passthrough` builder (the ModelUse direct route) applies
          `.applyUserAgentOverride(...)` — and does so BEFORE `builder.build()`,
          the same last-wins placement as the chat/responses builders. It was
          the one OpenAI builder that never applied it.

      D2  the Azure key-header decision lives in exactly ONE place — the
          package-level helper(s) in OpenAIAuthHeader.kt — which the chat
          builders, `OpenAIModelsApi` and the debug connectivity probe all
          delegate to; two shapes count as that one source (`header("api-key",
          …)` on the builder side, `"api-key" to …` for the probe, which returns
          the header pair as strings and never builds a request), while a
          SECOND copy is refused in either shape. The
          models fetch used to hard-code `Authorization: Bearer`, so every
          Azure instance (which authenticates with `api-key`) 401'd, and the
          7-day cache row was keyed on URL+key so the two auth schemes shared
          one row. Both halves are asserted: the parameter is threaded, and the
          cache key carries the scheme.

      D3  every `OpenAIModelsApi.fetchModels(` call in the adapter file passes
          `azureMode =`, with a value that matches that arm's CHAT path — either
          `instance.azureMode` (the OpenAI arm, whose ProviderFactory branch
          forwards isAzure) or the explicit `false` (Kimi, whose chat branch
          never forwards it). Per-call-site forwarding is checked exhaustively
          (not just the first occurrence) because dropping the argument on the
          second arm is exactly the deep-audit-1019 ISSUE-04 shape: silent,
          arm-local, and invisible in a single-site grep. And at least one site
          must forward from the instance, else Azure silently loses its models
          list while still passing the per-site check.
    """
    v = []
    try:
        code_p = code_of(provider_src, "OpenAIProvider.kt")
        code_m = code_of(models_src, "OpenAIModelsApi.kt")
        code_a = code_of(adapters_src, "ModelListProviderAdapters.kt")
        # the api-key header decision is a STRING literal → literal view
        code_auth = literal_of(auth_src, "OpenAIAuthHeader.kt")
    except LexFail as exc:
        return [str(exc)]

    # -- D1 ------------------------------------------------------------------
    body = decl_body(code_p, "suspend fun rawPassthroughRequest(")
    if body is None or "builder.build()" not in body:
        v.append(
            "OpenAIProvider.kt: 'suspend fun rawPassthroughRequest(' body not "
            "found (gate anchors moved — deliberate edit required)"
        )
    else:
        at = body.find(".applyUserAgentOverride(")
        build_at = body.find("builder.build()")
        if at < 0:
            v.append(
                "OpenAIProvider.kt: rawPassthroughRequest never applies "
                "'.applyUserAgentOverride(' (da1030 ISSUE-06 裂缝A: the "
                "/raw-passthrough route is the one OpenAI builder that drops "
                "the per-provider UA, so ModelUse direct calls go out with the "
                "default UA)"
            )
        elif at > build_at:
            v.append(
                "OpenAIProvider.kt: rawPassthroughRequest applies the UA "
                "override AFTER 'builder.build()' (da1030 ISSUE-06 裂缝A: the "
                "override must sit in the builder chain, after extraHeaders/user "
                "headers and before build(), or it never reaches the request)"
            )

    # -- D4 [R3-t12 12-3]: every request builder owns its UA ----------------
    v.extend(check_openai_builders(code_p))

    # -- D2a: single source for the api-key header ---------------------------
    if "fun Request.Builder.applyOpenAIKeyAuth(" not in code_auth:
        v.append(
            "OpenAIAuthHeader.kt: 'fun Request.Builder.applyOpenAIKeyAuth(' not "
            "found (da1030 ISSUE-06 裂缝B: this is the single source the chat "
            "builders and the models fetcher both delegate to)"
        )
    if not any(form in code_auth for form in API_KEY_DECISION_FORMS):
        v.append(
            "OpenAIAuthHeader.kt: no Azure api-key decision (%s) present "
            "(da1030 ISSUE-06 裂缝B: the Azure branch must live here, not in a "
            "caller)" % " | ".join(API_KEY_DECISION_FORMS)
        )
    for rel, text in main_sources:
        if rel == OPENAI_AUTH_REL:
            continue
        try:
            _body = literal_of(text, rel)
        except LexFail as exc:
            v.append(str(exc))
            continue
        _hit = next((f for f in API_KEY_DECISION_FORMS if f in _body), None)
        if _hit is not None:
            v.append(
                "%s: hard-coded '%s' outside provider/openai/OpenAIAuthHeader.kt "
                "(da1030 ISSUE-06 裂缝B: the Azure key header has exactly one "
                "source; a second copy is how the models fetcher ended up "
                "Bearer-only)" % (rel, _hit)
            )

    # -- D2b: the models fetcher threads + applies azureMode -----------------
    if "azureMode: Boolean = false" not in code_m:
        v.append(
            "OpenAIModelsApi.kt: fetchModels no longer declares "
            "'azureMode: Boolean = false' (da1030 ISSUE-06 裂缝B: without the "
            "parameter the adapter cannot forward ProviderInstance.azureMode)"
        )
    for fn, why in (
        ("buildModelsRequest", "the request is built with the hard-coded Bearer "
                               "path again"),
        ("modelsCacheKey", "the cache key no longer separates the two auth "
                           "schemes, so an Azure and a Bearer instance share a row"),
    ):
        sites = [m for m in re.finditer(re.escape(fn + "("), code_m)
                 if not re.search(r"fun\s+$", code_m[max(0, m.start() - 8):m.start()])]
        if not sites:
            v.append(
                "OpenAIModelsApi.kt: no %s(...) call in fetchModels (da1030 "
                "ISSUE-06 裂缝B: %s)" % (fn, why)
            )
            continue
        for m in sites:
            args = balanced_slice(code_m, m.end() - 1)
            if args is None:
                v.append("OpenAIModelsApi.kt:%d: %s(...) unbalanced"
                         % (code_m.count("\n", 0, m.start()) + 1, fn))
            elif "azureMode" not in args:
                v.append(
                    "OpenAIModelsApi.kt:%d: %s(...) call does not pass "
                    "azureMode (da1030 ISSUE-06 裂缝B: %s)"
                    % (code_m.count("\n", 0, m.start()) + 1, fn, why)
                )

    # [b7-t04] threading the flag is not applying it: the builder helper must
    # DELEGATE the auth decision to applyOpenAIKeyAuth, with both of its own
    # parameters. The witness kept every call/parameter intact and replaced the
    # delegation inside `buildModelsRequest` with a hard-coded Bearer header, so
    # every Azure models-list fetch 401'd while D2a/D2b stayed green.
    builder = function_body(code_m, "buildModelsRequest")
    if builder is None:
        v.append(
            "OpenAIModelsApi.kt: 'fun buildModelsRequest(' declaration not found "
            "(da1030 ISSUE-06 裂缝B: the one place the models-list auth decision "
            "can live is gone)"
        )
    else:
        bstart, bend, _kind = builder
        bbody = code_m[bstart:bend]
        params = _fn_params(code_m, "buildModelsRequest") or []
        delegations = [m for m in re.finditer(r"\bapplyOpenAIKeyAuth\s*\(", bbody)
                       if path_feasible(bbody, m.start())]
        if not delegations:
            v.append(
                "OpenAIModelsApi.kt: buildModelsRequest no longer delegates to "
                "applyOpenAIKeyAuth (da1030 ISSUE-06 裂缝B: a hard-coded "
                "Authorization/Bearer there sends the wrong header for every "
                "Azure instance, and the models list 401s)"
            )
        for m in delegations:
            dargs = args_of(bbody, m.end() - 1)
            missing = [p for p in ("azureMode", "apiKey")
                       if dargs is None or not contains_ident(dargs, p)]
            if missing:
                v.append(
                    "OpenAIModelsApi.kt: buildModelsRequest delegates to "
                    "applyOpenAIKeyAuth without %s (fail-closed: the helper's own "
                    "parameters must reach the single auth source)"
                    % ", ".join(missing)
                )
        if params and [p for p in ("azureMode", "apiKey") if p not in params]:
            v.append(
                "OpenAIModelsApi.kt: buildModelsRequest lost its %s parameter "
                "(fail-closed: the auth decision cannot be threaded)"
                % ", ".join(p for p in ("azureMode", "apiKey") if p not in params)
            )

    # -- D3: every adapter call site forwards azureMode ----------------------
    api = "OpenAIModelsApi.fetchModels"
    sites = [m for m in re.finditer(re.escape(api + "("), code_a)
             if not re.search(r"fun\s+$", code_a[max(0, m.start() - 8):m.start()])]
    if not sites:
        v.append(
            "ModelListProviderAdapters.kt: %s call not found (gate anchors "
            "moved — deliberate edit required)" % api
        )
    forwarded_from_instance = 0
    for m in sites:
        args = args_of(code_a, m.end() - 1)
        if args is None:
            v.append("ModelListProviderAdapters.kt: %s unbalanced" % api)
            continue
        line = code_a.count("\n", 0, m.start()) + 1
        if UA_ARG not in args:
            v.append(
                "ModelListProviderAdapters.kt:%d: %s call drops '%s' "
                "(da1030 ISSUE-06: the models fetch still gets no UA)"
                % (line, api, UA_ARG)
            )
        if "azureMode" not in args:
            v.append(
                "ModelListProviderAdapters.kt:%d: %s call drops 'azureMode' "
                "(da1030 ISSUE-06 裂缝B: this arm's Azure instances still fetch "
                "the models list with Bearer auth)" % (line, api)
            )
            continue
        azure_expr = _named_arg(args, "azureMode")
        azure_value = None if azure_expr is None else _azure_value(azure_expr, code_a)
        if azure_expr is None or azure_value is None:
            v.append(
                "ModelListProviderAdapters.kt:%d: %s passes an azureMode value "
                "that is neither `instance.azureMode` nor the explicit `false` "
                "(%r) (da1030 ISSUE-06: the value must mirror that arm's chat "
                "path — a rewritten value such as `instance.azureMode && false` "
                "silently changes which header goes out)"
                % (line, api, (azure_expr or "").strip() or "<missing>")
            )
        elif azure_value == "instance":
            forwarded_from_instance += 1
    if sites and forwarded_from_instance == 0:
        v.append(
            "ModelListProviderAdapters.kt: no %s call forwards "
            "`azureMode = instance.azureMode` (da1030 ISSUE-06 裂缝B: Azure "
            "instances silently lose their models list while every call site "
            "still looks compliant)" % api
        )
    return v


def collect_main_sources(root):
    """Every .kt under the main source root as (repo-relative path, source).
    Feeds the D2a single-source sweep for the api-key header literal."""
    out = []
    base = os.path.join(root, MAIN_SRC_REL)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    out.append((rel, fh.read()))
            except OSError:
                continue
    return out

def run(root):
    if not os.path.isdir(root):
        print("provider_ua_parity_check: not a directory: %s" % root)
        return 2
    try:
        factory = read(root, FACTORY_REL)
        gemini = read(root, GEMINI_REL)
        gemini_models = read(root, GEMINI_MODELS_REL)
        openrouter_models = read(root, OPENROUTER_MODELS_REL)
        adapters = read(root, ADAPTERS_REL)
    except OSError as exc:
        print("provider_ua_parity_check: cannot read sources: %s" % exc)
        return 2
    violations = (check_factory(factory) + check_gemini(gemini)
                  + check_gemini_models(gemini_models)
                  + check_openrouter_models(openrouter_models)
                  + check_adapters(adapters))
    # [da1029 ISSUE-05] voice family — drift-detector read, judged fail-closed
    # when present (see read_optional).
    voice_provider = read_optional(root, VOICE_PROVIDER_REL)
    voice_vendors = read_optional(root, VOICE_VENDORS_REL)
    voice_factory = read_optional(root, VOICE_FACTORY_REL)
    if voice_provider is not None and voice_vendors is not None \
            and voice_factory is not None:
        violations += check_voice(voice_provider, voice_vendors, voice_factory)
    else:
        print("note: voice family sources not present in this tree — "
              "voice-UA criteria skipped (drift-detector semantics)")
    # [da1030 ISSUE-06] OpenAI outbound single-source contract (UA on the
    # passthrough builder + one api-key auth source + azureMode threaded end
    # to end). Fail-closed: a missing file is a violation, not a skip.
    openai_provider = read_optional(root, OPENAI_PROVIDER_REL)
    openai_models = read_optional(root, OPENAI_MODELS_REL)
    openai_auth = read_optional(root, OPENAI_AUTH_REL)
    missing = [rel for rel, src in (
        (OPENAI_PROVIDER_REL, openai_provider),
        (OPENAI_MODELS_REL, openai_models),
        (OPENAI_AUTH_REL, openai_auth),
    ) if src is None]
    if missing:
        violations.append(
            "provider/openai outbound sources missing: %s (da1030 ISSUE-06: the "
            "UA + api-key single-source contract cannot be judged)" % ", ".join(missing)
        )
    else:
        violations += check_openai_outbound(
            openai_provider, openai_models, openai_auth, adapters,
            collect_main_sources(root),
        )
    if violations:
        print("FAIL provider_ua_parity_check")
        for v in violations:
            print("  " + v)
        return 1
    print("PASS provider_ua_parity_check (UA forwarded on every provider arm, "
          "Gemini EOF verdict weighs tool payload, voice family carries the UA, "
          "OpenAI outbound shares one api-key source + one UA chokepoint)")
    return 0


# --------------------------------------------------------------------------
# selftest: every criterion gets its own violating fixture, plus compliant ones
# --------------------------------------------------------------------------

GOOD_FACTORY = """
object ProviderFactory {
    fun create(instance: ProviderInstance, apiKey: String, model: LLMModel): LLMProvider {
        val effectiveKey = apiKey
        return (when (instance.providerType) {
            ProviderType.anthropic -> {
                if (basePath != null) AnthropicProvider(effectiveKey, model, basePath, customUserAgent = instance.customUserAgent)
                else AnthropicProvider(effectiveKey, model, customUserAgent = instance.customUserAgent)
            }
            ProviderType.gemini -> {
                if (basePath != null) GeminiProvider(effectiveKey, model, basePath, customUserAgent = instance.customUserAgent)
                else GeminiProvider(effectiveKey, model, customUserAgent = instance.customUserAgent)
            }
            ProviderType.openAI -> {
                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = "https://api.openai.com/v1",
                    customUserAgent = instance.customUserAgent,
                )
            }
            ProviderType.openRouter -> {
                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    customUserAgent = instance.customUserAgent,
                )
            }
            else -> OpenAIProvider(
                apiKey = effectiveKey,
                model = model,
                basePath = "https://api.x.ai/v1",
                customUserAgent = instance.customUserAgent,
            )
        })
    }
}
"""

BAD_FACTORY_DROP = GOOD_FACTORY.replace(
    """                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    customUserAgent = instance.customUserAgent,
                )""",
    """                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                )""",
)

GOOD_GEMINI = """
class GeminiProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.gemini25Flash,
    private val basePath: String = GeminiBasePath.DEFAULT_BASE,
    private val customUserAgent: String? = null,
) : LLMProvider {
    fun send(): LLMResponse {
        val request = Request.Builder()
            .url(url)
            .applyUserAgentOverride(customUserAgent)
            .build()
        return LLMResponse("", "end_turn", null, emptyList())
    }

    fun stream(): Flow<LLMStreamChunk> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .applyUserAgentOverride(customUserAgent)
            .build()
        var contentChars = 0
        var toolChars = 0
        toolChars += toolCallActivityChars(fcName, fcArgs.toString())
        contentChars += media.data.size
        if (shouldFlagEofTruncated(lastFinishReason, contentChars, toolChars)) {
            send(LLMStreamChunk.Finished(null, truncated = true))
        } else {
            send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
        }
    }
}

internal fun shouldFlagEofTruncated(
    lastFinishReason: String?,
    contentChars: Int,
    toolChars: Int,
): Boolean = lastFinishReason == null && (contentChars > 0 || toolChars > 0)

internal fun toolCallActivityChars(name: String, argsJson: String): Int =
    name.length + argsJson.length
"""

BAD_GEMINI_NULL = GOOD_GEMINI.replace(
    ".applyUserAgentOverride(customUserAgent)\n            .build()\n        return LLMResponse",
    ".applyUserAgentOverride(null)\n            .build()\n        return LLMResponse",
)

BAD_GEMINI_NO_PARAM = GOOD_GEMINI.replace(
    "    private val customUserAgent: String? = null,\n", ""
).replace(".applyUserAgentOverride(customUserAgent)", ".applyUserAgentOverride(null)")

BAD_GEMINI_NO_TOOL_VERDICT = GOOD_GEMINI.replace(
    "): Boolean = lastFinishReason == null && (contentChars > 0 || toolChars > 0)",
    "): Boolean = lastFinishReason == null && contentChars > 0",
)

BAD_GEMINI_NO_ACCUM = GOOD_GEMINI.replace(
    "        toolChars += toolCallActivityChars(fcName, fcArgs.toString())\n", ""
)

BAD_GEMINI_NO_CALLSITE = GOOD_GEMINI.replace(
    "if (shouldFlagEofTruncated(lastFinishReason, contentChars, toolChars)) {",
    "if (lastFinishReason == null && contentChars > 0) {",
)

# [wide-1024] the helper shape the real GeminiProvider uses: the SSE flow calls
# `geminiEofTerminal(...)` and THAT feeds the pure verdict. The old criterion
# saw `toolChars` inside the helper's parameter list and stopped there.
GOOD_GEMINI_TERMINAL = GOOD_GEMINI.replace(
    """        if (shouldFlagEofTruncated(lastFinishReason, contentChars, toolChars)) {""",
    """        val terminal = geminiEofTerminal(lastFinishReason, contentChars, toolChars)
        if (terminal) {""",
).replace(
    """internal fun shouldFlagEofTruncated(""",
    """internal fun geminiEofTerminal(
    lastFinishReason: String?,
    contentChars: Int,
    toolChars: Int,
): Boolean =
    shouldFlagEofTruncated(lastFinishReason, contentChars, toolChars)

internal fun shouldFlagEofTruncated(""",
)

BAD_GEMINI_TERMINAL_ZERO = GOOD_GEMINI_TERMINAL.replace(
    "geminiEofTerminal(lastFinishReason, contentChars, toolChars)",
    "geminiEofTerminal(lastFinishReason, contentChars, 0)",
)

BAD_GEMINI_TERMINAL_UNBALANCED = GOOD_GEMINI_TERMINAL.replace(
    "geminiEofTerminal(lastFinishReason, contentChars, toolChars)",
    "geminiEofTerminal(lastFinishReason, contentChars, toolChars",
)

BAD_FACTORY_UA_NULL = GOOD_FACTORY.replace(
    """                    basePath = "https://openrouter.ai/api/v1",
                    customUserAgent = instance.customUserAgent,""",
    """                    basePath = "https://openrouter.ai/api/v1",
                    customUserAgent = null,""",
)

GOOD_FACTORY_UA_ALIAS = GOOD_FACTORY.replace(
    """        val effectiveKey = apiKey""",
    """        val effectiveKey = apiKey
        val ua = instance.customUserAgent""",
).replace(
    "customUserAgent = instance.customUserAgent",
    "customUserAgent = ua",
)

BAD_GEMINI_NO_VERDICT_FN = BAD_GEMINI_NO_CALLSITE.replace(
    """
internal fun shouldFlagEofTruncated(
    lastFinishReason: String?,
    contentChars: Int,
    toolChars: Int,
): Boolean = lastFinishReason == null && (contentChars > 0 || toolChars > 0)
""",
    "",
)


GOOD_GEMINI_MODELS = """
object GeminiModelsApi {
    suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
        builder.url("https://x/models?key=" + apiKey)
        builder.applyUserAgentOverride(customUserAgent)
        emptyList()
    }
}
"""

BAD_GEMINI_MODELS_NULL = GOOD_GEMINI_MODELS.replace(
    "builder.applyUserAgentOverride(customUserAgent)",
    "builder.applyUserAgentOverride(null)",
)

BAD_GEMINI_MODELS_NO_PARAM = GOOD_GEMINI_MODELS.replace(
    "        customUserAgent: String? = null,\n", ""
).replace(
    "builder.applyUserAgentOverride(customUserAgent)",
    "builder.applyUserAgentOverride(null)",
)

GOOD_OPENROUTER_MODELS = """
object OpenRouterModelsApi {
    suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .applyUserAgentOverride(customUserAgent)
            .build()
        emptyList()
    }
}
"""

BAD_OPENROUTER_MODELS_NULL = GOOD_OPENROUTER_MODELS.replace(
    ".applyUserAgentOverride(customUserAgent)",
    ".applyUserAgentOverride(null)",
)

GOOD_ADAPTERS = """
object Adapters {
    fun gemini(instance: ProviderInstance, apiKey: String, context: Context?): List<LLMModel> {
        return GeminiModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            context = context,
            customUserAgent = instance.customUserAgent,
        )
    }

    fun openrouter(instance: ProviderInstance, apiKey: String, context: Context?): List<LLMModel> {
        return OpenRouterModelsApi.fetchModels(
            apiKey,
            context = context,
            customUserAgent = instance.customUserAgent,
        )
    }
}
"""

BAD_ADAPTERS_DROP = GOOD_ADAPTERS.replace(
    """        return OpenRouterModelsApi.fetchModels(
            apiKey,
            context = context,
            customUserAgent = instance.customUserAgent,
        )""",
    """        return OpenRouterModelsApi.fetchModels(
            apiKey,
            context = context,
        )""",
)

# -- [da1029 ISSUE-05] voice family fixtures: each new criterion gets its own
#    "exactly broken by it" violating fixture plus a compliant one. -----------

GOOD_VOICE_PROVIDER = """
class VoiceProvider(
    val providerId: String,
    val baseURL: String,
    val apiKey: String? = null,
    var customUserAgent: String? = null,
) {
    suspend fun executeRequest(request: Request): ByteArray = withContext(Dispatchers.IO) {
        val effectiveRequest = request.newBuilder()
            .applyUserAgentOverride(customUserAgent)
            .build()
        val call = httpClient.newCall(effectiveRequest)
        emptyList<Byte>()
    }
}
"""

BAD_VOICE_PROVIDER_DROP = GOOD_VOICE_PROVIDER.replace(
    """        val effectiveRequest = request.newBuilder()
            .applyUserAgentOverride(customUserAgent)
            .build()
        val call = httpClient.newCall(effectiveRequest)""",
    """        val call = httpClient.newCall(request)""",
)

# [R3-t12 12-1] 见证原文：有效请求建出来了，但 newCall 收的是原始形参。
BAD_VOICE_PROVIDER_NEWCALL_DECOY = GOOD_VOICE_PROVIDER.replace(
    "        val call = httpClient.newCall(effectiveRequest)",
    "        val call = httpClient.newCall(request)",
)

BAD_VOICE_PROVIDER_NO_FN = GOOD_VOICE_PROVIDER.replace(
    "suspend fun executeRequest(", "suspend fun sendBytes("
)

GOOD_VOICE_VENDORS = """
class XunfeiVoiceProvider {
    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val signedUrl = buildSignedURL(host = "tts-api.xfyun.cn")
        return withTimeoutOrNull(TTS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val wsRequest = Request.Builder()
                    .url(signedUrl)
                    .applyUserAgentOverride(customUserAgent)
                    .build()
                val socket = httpClient.newWebSocket(wsRequest, listener)
            }
        }
    }
}
"""

BAD_VOICE_VENDORS_DROP = GOOD_VOICE_VENDORS.replace(
    """                val wsRequest = Request.Builder()
                    .url(signedUrl)
                    .applyUserAgentOverride(customUserAgent)
                    .build()""",
    """                val wsRequest = Request.Builder().url(signedUrl).build()""",
)

# [R3-t12 12-2] 见证原文：带覆盖的 wsRequest 还在，握手收的是新建裸 builder。
BAD_VOICE_VENDORS_WS_DECOY = GOOD_VOICE_VENDORS.replace(
    "val socket = httpClient.newWebSocket(wsRequest, listener)",
    "val socket = httpClient.newWebSocket(Request.Builder()"
    ".url(signedUrl).build(), listener)",
)

BAD_VOICE_VENDORS_ANCHOR_MOVED = GOOD_VOICE_VENDORS.replace(
    ".url(signedUrl)", ".url(signed)"
)

GOOD_VOICE_FACTORY = """
object VoiceProviderFactory {
    fun make(instance: ProviderInstance, rawApiKey: String?): VoiceProvider? =
        build(instance, rawApiKey).also { it?.customUserAgent = instance.customUserAgent }

    private fun build(instance: ProviderInstance, rawApiKey: String?): VoiceProvider? {
        return when (instance.providerType) {
            ProviderType.openAI -> VoiceProvider(instance.id, "https://x", rawApiKey)
            else -> null
        }
    }
}
"""

BAD_VOICE_FACTORY_DROP = GOOD_VOICE_FACTORY.replace(
    "        build(instance, rawApiKey).also { it?.customUserAgent = instance.customUserAgent }",
    "        build(instance, rawApiKey)",
)

BAD_VOICE_FACTORY_CLOBBER = GOOD_VOICE_FACTORY.replace(
    "build(instance, rawApiKey).also { it?.customUserAgent = instance.customUserAgent }",
    "build(instance, rawApiKey).also { it?.customUserAgent = instance.customUserAgent; it?.customUserAgent = null }",
)

BAD_VOICE_FACTORY_NULL = GOOD_VOICE_FACTORY.replace(
    "it?.customUserAgent = instance.customUserAgent",
    "it?.customUserAgent = null",
)

# -- [da1030 ISSUE-06] OpenAI outbound fixtures: D1 (passthrough UA), D2a
#    (single api-key source), D2b (azureMode threaded in the fetcher) and
#    D3 (every adapter call site forwards azureMode). ------------------------

GOOD_OPENAI_PROVIDER = """
class OpenAIProvider(
    private val apiKey: String,
    private val isAzure: Boolean = false,
    private val customUserAgent: String? = null,
) {
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        applyOpenAIKeyAuth(isAzure, token)

    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        val token = getToken()
        builder.applyKeyAuth(token)
        builder.header("Content-Type", "application/json")
        for ((k, v) in extraHeaders) builder.header(k, v)
        for ((k, v) in headers) builder.header(k, v)
        builder.applyUserAgentOverride(customUserAgent)

        val response = client.newCall(builder.build()).execute()
        response.body!!.bytes()
    }

    suspend fun generateImage(): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .applyKeyAuth(token)
        builder.applyUserAgentOverride(customUserAgent)
        val request = builder.build()
        client.newCall(request).execute().body!!.bytes()
    }
}
"""

BAD_OPENAI_PROVIDER_DROP = GOOD_OPENAI_PROVIDER.replace(
    "        builder.applyUserAgentOverride(customUserAgent)\n", ""
)

BAD_OPENAI_PROVIDER_AFTER_BUILD = GOOD_OPENAI_PROVIDER.replace(
    """        builder.applyUserAgentOverride(customUserAgent)

        val response = client.newCall(builder.build()).execute()""",
    """        val response = client.newCall(builder.build()).execute()
        val request = builder.applyUserAgentOverride(customUserAgent)""",
)

# [R3-t12 12-3] 对称轴见证：rawPassthroughRequest 的覆盖还在，同文件里
# generateImage 的 builder 丢掉了它 —— 旧判据只按函数名锚，看不到这一条。
BAD_OPENAI_PROVIDER_IMAGE_DROP = GOOD_OPENAI_PROVIDER.replace(
    """        builder.applyUserAgentOverride(customUserAgent)
        val request = builder.build()
        client.newCall(request).execute().body!!.bytes()""",
    """        val request = builder.build()
        client.newCall(request).execute().body!!.bytes()""",
)

BAD_OPENAI_PROVIDER_RENAMED = GOOD_OPENAI_PROVIDER.replace(
    "suspend fun rawPassthroughRequest(", "suspend fun rawPassthrough("
)

GOOD_OPENAI_AUTH = """
package com.rikkaminis.app.provider.openai

internal fun Request.Builder.applyOpenAIKeyAuth(
    azure: Boolean,
    token: String,
): Request.Builder =
    if (azure) header("api-key", token) else header("Authorization", "Bearer $token")
"""

BAD_OPENAI_AUTH_NO_FN = GOOD_OPENAI_AUTH.replace(
    "internal fun Request.Builder.applyOpenAIKeyAuth(", "internal fun Request.Builder.keyAuth("
)

# [da1031-D ISSUE-07] the pair shape of the same single source.
GOOD_OPENAI_AUTH_PAIR = """
package com.rikkaminis.app.provider.openai

internal fun openAIKeyAuthHeader(azure: Boolean, token: String): Pair<String, String> =
    if (azure) "api-key" to token else "Authorization" to "Bearer $token"

internal fun Request.Builder.applyOpenAIKeyAuth(
    azure: Boolean,
    token: String,
): Request.Builder =
    openAIKeyAuthHeader(azure, token).let { (name, value) -> header(name, value) }
"""

BAD_OPENAI_AUTH_PAIR_NO_FN = GOOD_OPENAI_AUTH_PAIR.replace(
    "internal fun Request.Builder.applyOpenAIKeyAuth(",
    "internal fun Request.Builder.keyAuth(",
)

GOOD_OTHER_SOURCES_PAIR = [
    ("src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIAuthHeader.kt",
     GOOD_OPENAI_AUTH_PAIR),
    # a sibling provider with a DIFFERENT key header must stay clean
    ("src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt",
     'builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")'),
    # a sensitive-header deny-list that merely contains the bare string
    # "api-key" is NOT a header decision and must stay clean
    ("src/android/app/src/main/java/com/rikkaminis/app/debug/LLMRequestLog.kt",
     'private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "api-key", "cookie")'),
]

BAD_OTHER_SOURCES_DUPLICATE_PAIR = GOOD_OTHER_SOURCES_PAIR + [
    ("src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIModelsApi.kt",
     'val pair = "api-key" to apiKey'),
]

GOOD_OPENAI_MODELS = """
object OpenAIModelsApi {
    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        azureMode: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = modelsCacheKey(baseURL, apiKey, azureMode)
        val url = buildURL(baseURL)
        val request = buildModelsRequest(url, apiKey, azureMode, customUserAgent)
        emptyList()
    }

    internal fun buildModelsRequest(
        url: String,
        apiKey: String,
        azureMode: Boolean,
        customUserAgent: String?,
    ): Request =
        Request.Builder()
            .url(url)
            .applyOpenAIKeyAuth(azureMode, apiKey)
            .applyUserAgentOverride(customUserAgent)
            .build()

    internal fun modelsCacheKey(baseURL: String?, apiKey: String, azureMode: Boolean): String =
        (baseURL ?: "") + "|" + apiKey + "|" + if (azureMode) "azure" else "bearer"
}
"""

BAD_OPENAI_MODELS_NO_PARAM = GOOD_OPENAI_MODELS.replace(
    "        azureMode: Boolean = false,\n", ""
)

BAD_OPENAI_MODELS_NO_BUILD_CALL = GOOD_OPENAI_MODELS.replace(
    "        val request = buildModelsRequest(url, apiKey, azureMode, customUserAgent)\n",
    "        val request = Request.Builder().url(url).build()\n",
)

BAD_OPENAI_MODELS_KEY_NOT_THREADED = GOOD_OPENAI_MODELS.replace(
    "buildModelsRequest(url, apiKey, azureMode, customUserAgent)",
    "buildModelsRequest(url, apiKey, false, customUserAgent)",
)

BAD_OPENAI_MODELS_CACHE_NOT_THREADED = GOOD_OPENAI_MODELS.replace(
    "modelsCacheKey(baseURL, apiKey, azureMode)",
    "modelsCacheKey(baseURL, apiKey, false)",
)

GOOD_OPENAI_ADAPTERS = """
object OpenAIAdapter_ok {
    fun a(instance: ProviderInstance, apiKey: String, context: Context?): List<LLMModel> {
        return OpenAIModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            context = context,
            customUserAgent = instance.customUserAgent,
            azureMode = instance.azureMode,
            forceRefresh = forceRefresh,
        )
    }

    fun b(instance: ProviderInstance, apiKey: String, context: Context?): List<LLMModel> {
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            context = context,
            customUserAgent = instance.customUserAgent,
            azureMode = false,
            forceRefresh = forceRefresh,
        )
    }
}
"""

BAD_OPENAI_ADAPTERS_DROP_SECOND = GOOD_OPENAI_ADAPTERS.replace(
    """            customUserAgent = instance.customUserAgent,
            azureMode = false,
            forceRefresh = forceRefresh,
        )
    }
}
""",
    """            customUserAgent = instance.customUserAgent,
            forceRefresh = forceRefresh,
        )
    }
}
""",
)

BAD_OPENAI_ADAPTERS_INVENTED_VALUE = GOOD_OPENAI_ADAPTERS.replace(
    "            azureMode = false,\n", "            azureMode = true,\n"
)

BAD_OPENAI_ADAPTERS_ALL_FALSE = GOOD_OPENAI_ADAPTERS.replace(
    "            azureMode = instance.azureMode,\n", "            azureMode = false,\n"
)

BAD_OPENAI_ADAPTERS_NO_SITE = """
object NoOpenAIArm {
    fun a(instance: ProviderInstance): List<LLMModel> = emptyList()
}
"""

GOOD_OTHER_SOURCES = [
    ("src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIAuthHeader.kt",
     GOOD_OPENAI_AUTH),
    # a sibling provider with a DIFFERENT key header must stay clean
    ("src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt",
     'builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")'),
]

BAD_OTHER_SOURCES_DUPLICATE = GOOD_OTHER_SOURCES + [
    ("src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIModelsApi.kt",
     'Request.Builder().url(url).header("api-key", apiKey).build()'),
]

# -- [batch7 B7-t04] the six externally-witnessed bypasses -------------------
# Each one kept the old criterion's symbol in place and changed what it DOES.

BAD_FACTORY_UA_LET_NULL = GOOD_FACTORY.replace(
    "customUserAgent = instance.customUserAgent,",
    "customUserAgent = instance.customUserAgent.let { null },",
)

BAD_GEMINI_DOUBLE_OVERRIDE = GOOD_GEMINI.replace(
    """            .applyUserAgentOverride(customUserAgent)
            .build()
        return LLMResponse""",
    """            .applyUserAgentOverride(customUserAgent)
            .applyUserAgentOverride("")
            .build()
        return LLMResponse""",
)

BAD_GEMINI_TOOL_CHARS_CONST = GOOD_GEMINI.replace(
    """internal fun toolCallActivityChars(name: String, argsJson: String): Int =
    name.length + argsJson.length""",
    "internal fun toolCallActivityChars(name: String, argsJson: String): Int = 0",
)

BAD_VOICE_FACTORY_DEAD_GUARD = GOOD_VOICE_FACTORY.replace(
    "build(instance, rawApiKey).also { it?.customUserAgent = instance.customUserAgent }",
    "build(instance, rawApiKey).also { if (false) it?.customUserAgent = instance.customUserAgent }",
)

BAD_OPENAI_MODELS_BEARER = GOOD_OPENAI_MODELS.replace(
    ".applyOpenAIKeyAuth(azureMode, apiKey)",
    '.header("Authorization", "Bearer $apiKey")',
)

BAD_OPENAI_ADAPTERS_AND_FALSE = GOOD_OPENAI_ADAPTERS.replace(
    "azureMode = instance.azureMode,",
    "azureMode = instance.azureMode && false,",
)


def selftest():
    failures = []
    arms = []

    def expect_clean(name, v):
        arms.append(name)
        if v:
            failures.append("%s must be clean, got %s" % (name, v))

    def expect_trip(name, v, needle):
        arms.append(name)
        if not any(needle in item for item in v):
            failures.append("%s did not trip '%s' (got %s)" % (name, needle, v))

    good_factory = check_factory(GOOD_FACTORY)
    good_gemini = check_gemini(GOOD_GEMINI)
    expect_clean("compliant factory fixture", good_factory)
    expect_clean("compliant gemini fixture", good_gemini)

    good_gm = check_gemini_models(GOOD_GEMINI_MODELS)
    good_orm = check_openrouter_models(GOOD_OPENROUTER_MODELS)
    good_ad = check_adapters(GOOD_ADAPTERS)
    expect_clean("compliant gemini-models fixture", good_gm)
    expect_clean("compliant openrouter-models fixture", good_orm)
    expect_clean("compliant adapters fixture", good_ad)

    bad_drop = check_factory(BAD_FACTORY_DROP)
    expect_trip("dropped-openRouter fixture (ISSUE-04a)", bad_drop, "drops")
    expect_trip("dropped-openRouter fixture (ISSUE-04a)", bad_drop, "OpenAIProvider")

    # [wide-1024] the argument name is not the value
    bad_null = check_factory(BAD_FACTORY_UA_NULL)
    expect_trip("null-UA-in-openRouter-arm fixture (ISSUE-04a)", bad_null,
                "the instance override never arrives")
    good_alias = check_factory(GOOD_FACTORY_UA_ALIAS)
    expect_clean("local-alias-UA fixture (ISSUE-04a)", good_alias)

    bad_null = check_gemini(BAD_GEMINI_NULL)
    expect_trip("hard-coded-null fixture (ISSUE-04b)", bad_null, "applyUserAgentOverride(null)")

    bad_noparam = check_gemini(BAD_GEMINI_NO_PARAM)
    expect_trip("no-constructor-param fixture (ISSUE-04b)", bad_noparam, "constructor no longer declares")
    expect_trip("no-constructor-param fixture (ISSUE-04b)", bad_noparam, "applyUserAgentOverride(null)")

    bad_verdict = check_gemini(BAD_GEMINI_NO_TOOL_VERDICT)
    expect_trip("verdict-drops-toolchar fixture (ISSUE-04c)", bad_verdict, "no longer weighs both")

    bad_accum = check_gemini(BAD_GEMINI_NO_ACCUM)
    expect_trip("no-tool-accumulation fixture (ISSUE-04c)", bad_accum, "tool-payload accumulation")

    bad_callsite = check_gemini(BAD_GEMINI_NO_CALLSITE)
    expect_trip("inline-verdict fixture (ISSUE-04c)", bad_callsite, "never called")

    # [wide-1024] the accumulator must arrive at the terminal call site
    good_term = check_gemini(GOOD_GEMINI_TERMINAL)
    expect_clean("gemini terminal-helper fixture", good_term)
    bad_term_zero = check_gemini(BAD_GEMINI_TERMINAL_ZERO)
    expect_trip("tool-counter-hardcoded-at-terminal fixture (ISSUE-04c)",
                bad_term_zero, "does not pass the tool counter")
    bad_term_unbal = check_gemini(BAD_GEMINI_TERMINAL_UNBALANCED)
    expect_trip("terminal-call-unbalanced fixture (fail-closed)", bad_term_unbal,
                "unbalanced (fail-closed")

    bad_nofn = check_gemini(BAD_GEMINI_NO_VERDICT_FN)
    expect_trip("verdict-fn-deleted fixture (ISSUE-04c)", bad_nofn, "no longer routed")
    expect_trip("verdict-fn-deleted fixture (ISSUE-04c)", bad_nofn, "declaration not found")

    bad_gm_null = check_gemini_models(BAD_GEMINI_MODELS_NULL)
    expect_trip("gemini-models hard-coded-null fixture", bad_gm_null, "applyUserAgentOverride(null)")

    bad_gm_noparam = check_gemini_models(BAD_GEMINI_MODELS_NO_PARAM)
    expect_trip("gemini-models no-param fixture", bad_gm_noparam, "no longer declares")

    bad_orm_null = check_openrouter_models(BAD_OPENROUTER_MODELS_NULL)
    expect_trip("openrouter-models hard-coded-null fixture", bad_orm_null, "applyUserAgentOverride(null)")

    bad_ad = check_adapters(BAD_ADAPTERS_DROP)
    expect_trip("adapters-drop fixture", bad_ad, "drops")
    expect_trip("adapters-drop fixture", bad_ad, "OpenRouterModelsApi")

    good_vp = check_voice(GOOD_VOICE_PROVIDER, GOOD_VOICE_VENDORS, GOOD_VOICE_FACTORY)
    expect_clean("compliant voice fixtures", good_vp)

    bad_vp_drop = check_voice(BAD_VOICE_PROVIDER_DROP, GOOD_VOICE_VENDORS, GOOD_VOICE_FACTORY)
    expect_trip("voice executeRequest-drop fixture (ISSUE-05 V1)", bad_vp_drop,
                "executeRequest does not apply")

    bad_vp_nofn = check_voice(BAD_VOICE_PROVIDER_NO_FN, GOOD_VOICE_VENDORS, GOOD_VOICE_FACTORY)
    expect_trip("voice executeRequest-renamed fixture (ISSUE-05 V1)", bad_vp_nofn,
                "executeRequest(' not found")

    bad_vv_drop = check_voice(GOOD_VOICE_PROVIDER, BAD_VOICE_VENDORS_DROP, GOOD_VOICE_FACTORY)
    expect_trip("xunfei WS-drop fixture (ISSUE-05 V2)", bad_vv_drop,
                "WebSocket request drops")

    bad_vv_moved = check_voice(GOOD_VOICE_PROVIDER, BAD_VOICE_VENDORS_ANCHOR_MOVED,
                               GOOD_VOICE_FACTORY)
    expect_trip("xunfei WS-anchor-moved fixture (ISSUE-05 V2)", bad_vv_moved,
                "not found")

    # [R3-t12] the three third-round witnesses: the override exists, the value
    # that is actually sent does not carry it (and the symmetric builder that
    # no function-named criterion looked at).
    bad_vp_decoy = check_voice(BAD_VOICE_PROVIDER_NEWCALL_DECOY, GOOD_VOICE_VENDORS,
                               GOOD_VOICE_FACTORY)
    expect_trip("voice newCall-decoy fixture (R3-12-1)", bad_vp_decoy,
                "does not carry the UA override")
    bad_vv_decoy = check_voice(GOOD_VOICE_PROVIDER, BAD_VOICE_VENDORS_WS_DECOY,
                               GOOD_VOICE_FACTORY)
    expect_trip("xunfei WS-decoy fixture (R3-12-2)", bad_vv_decoy,
                "does not carry the UA override")
    good_vp_decoy = check_voice(GOOD_VOICE_PROVIDER, GOOD_VOICE_VENDORS,
                                GOOD_VOICE_FACTORY)
    expect_clean("compliant voice fixtures (recheck)", good_vp_decoy)

    bad_vf_drop = check_voice(GOOD_VOICE_PROVIDER, GOOD_VOICE_VENDORS, BAD_VOICE_FACTORY_DROP)
    expect_trip("voice factory injection-drop fixture (ISSUE-05 V3)", bad_vf_drop,
                "does not inject")

    # [wide-1024] assign-then-clobber: the injection exists and is overwritten
    bad_vf_clobber = check_voice(GOOD_VOICE_PROVIDER, GOOD_VOICE_VENDORS,
                                 BAD_VOICE_FACTORY_CLOBBER)
    expect_trip("voice factory clobber fixture (ISSUE-05 V3)", bad_vf_clobber,
                "assign-then-clobber")

    bad_vf_null = check_voice(GOOD_VOICE_PROVIDER, GOOD_VOICE_VENDORS, BAD_VOICE_FACTORY_NULL)
    expect_trip("voice factory fake-null wiring fixture (ISSUE-05 V3)", bad_vf_null,
                "does not inject")

    print("selftest compliant voice family   -> %d violation(s)" % len(good_vp))
    print("selftest voice executeRequest-drop-> %d violation(s)" % len(bad_vp_drop))
    print("selftest voice executeRequest-ren -> %d violation(s)" % len(bad_vp_nofn))
    print("selftest xunfei WS-drop           -> %d violation(s)" % len(bad_vv_drop))
    print("selftest xunfei WS-anchor-moved   -> %d violation(s)" % len(bad_vv_moved))
    print("selftest voice factory drop       -> %d violation(s)" % len(bad_vf_drop))
    print("selftest voice factory null       -> %d violation(s)" % len(bad_vf_null))
    print("selftest voice factory clobber    -> %d violation(s)" % len(bad_vf_clobber))
    print("selftest gemini terminal-zero     -> %d violation(s)" % len(bad_term_zero))
    print("selftest factory UA null          -> %d violation(s)" % len(bad_null))

    # -- [da1030 ISSUE-06] OpenAI outbound arms ----------------------------
    def oai(provider=GOOD_OPENAI_PROVIDER, models=GOOD_OPENAI_MODELS,
            auth=GOOD_OPENAI_AUTH, adapters=GOOD_OPENAI_ADAPTERS,
            others=GOOD_OTHER_SOURCES):
        return check_openai_outbound(provider, models, auth, adapters, others)

    good_oai = oai()
    expect_clean("compliant openai-outbound fixtures", good_oai)
    # precision: the sibling Anthropic x-api-key line is a DIFFERENT header and
    # must not be counted as a second api-key source
    if any("AnthropicProvider.kt" in item for item in good_oai):
        failures.append("compliant openai fixtures: x-api-key flagged as api-key")
    print("selftest compliant openai-outbound-> %d violation(s)" % len(good_oai))

    def expect_single(name, v, needle):
        hits = [i for i in v if needle in i]
        if len(hits) != 1:
            failures.append("%s expected exactly 1 x '%s', got %s" % (name, needle, v))

    bad_p_drop = oai(provider=BAD_OPENAI_PROVIDER_DROP)
    expect_trip("passthrough-UA-drop fixture (D1)", bad_p_drop, "never applies")
    bad_p_after = oai(provider=BAD_OPENAI_PROVIDER_AFTER_BUILD)
    expect_trip("passthrough-UA-after-build fixture (D1)", bad_p_after,
                "AFTER 'builder.build()'")
    bad_img_drop = oai(provider=BAD_OPENAI_PROVIDER_IMAGE_DROP)
    expect_trip("generateImage-UA-drop fixture (R3-12-3)", bad_img_drop,
                "without '.applyUserAgentOverride('")
    expect_single("generateImage-UA-drop fixture (R3-12-3)", bad_img_drop,
                  "without '.applyUserAgentOverride('")
    bad_p_renamed = oai(provider=BAD_OPENAI_PROVIDER_RENAMED)
    expect_trip("passthrough-anchor-moved fixture (D1)", bad_p_renamed, "body not found")

    bad_auth = oai(auth=BAD_OPENAI_AUTH_NO_FN)
    expect_trip("auth-single-source-missing fixture (D2a)", bad_auth,
                "applyOpenAIKeyAuth(' not found")
    bad_dup = oai(others=BAD_OTHER_SOURCES_DUPLICATE)
    expect_trip("duplicate-api-key-literal fixture (D2a)", bad_dup, "hard-coded")

    # [da1031-D ISSUE-07] the pair shape is the SAME single source, not a leak
    good_oai_pair = oai(auth=GOOD_OPENAI_AUTH_PAIR, others=GOOD_OTHER_SOURCES_PAIR)
    expect_clean("compliant openai-outbound (pair form) fixtures", good_oai_pair)
    # precision: neither the sibling provider nor the deny-list counts as a copy
    if any("AnthropicProvider.kt" in item or "LLMRequestLog.kt" in item
           for item in good_oai_pair):
        failures.append("pair-form fixtures: sibling/deny-list line flagged as api-key")
    bad_auth_pair = oai(auth=BAD_OPENAI_AUTH_PAIR_NO_FN, others=GOOD_OTHER_SOURCES_PAIR)
    expect_trip("auth-single-source-missing (pair form, D2a)", bad_auth_pair,
                "applyOpenAIKeyAuth(' not found")
    bad_dup_pair = oai(auth=GOOD_OPENAI_AUTH_PAIR, others=BAD_OTHER_SOURCES_DUPLICATE_PAIR)
    expect_trip("duplicate-api-key-pair fixture (D2a)", bad_dup_pair, "hard-coded")
    expect_single("duplicate-api-key-pair fixture (D2a)", bad_dup_pair, "hard-coded")

    bad_m_param = oai(models=BAD_OPENAI_MODELS_NO_PARAM)
    expect_trip("models-no-azureMode-param fixture (D2b)", bad_m_param, "no longer declares")
    bad_m_build = oai(models=BAD_OPENAI_MODELS_NO_BUILD_CALL)
    expect_trip("models-no-build-call fixture (D2b)", bad_m_build,
                "no buildModelsRequest(...) call")
    bad_m_key = oai(models=BAD_OPENAI_MODELS_KEY_NOT_THREADED)
    expect_trip("models-key-not-threaded fixture (D2b)", bad_m_key, "does not pass azureMode")
    expect_single("models-key-not-threaded fixture (D2b)", bad_m_key, "does not pass azureMode")
    bad_m_cache = oai(models=BAD_OPENAI_MODELS_CACHE_NOT_THREADED)
    expect_trip("models-cache-not-threaded fixture (D2b)", bad_m_cache,
                "does not pass azureMode")
    expect_single("models-cache-not-threaded fixture (D2b)", bad_m_cache,
                  "does not pass azureMode")

    bad_ad2 = oai(adapters=BAD_OPENAI_ADAPTERS_DROP_SECOND)
    expect_trip("adapters-second-site-drop fixture (D3)", bad_ad2, "drops 'azureMode'")
    expect_single("adapters-second-site-drop fixture (D3)", bad_ad2, "drops 'azureMode'")
    bad_ad_invented = oai(adapters=BAD_OPENAI_ADAPTERS_INVENTED_VALUE)
    expect_trip("adapters-invented-value fixture (D3)", bad_ad_invented,
                "neither `instance.azureMode`")
    expect_single("adapters-invented-value fixture (D3)", bad_ad_invented,
                  "neither `instance.azureMode`")
    bad_ad_allfalse = oai(adapters=BAD_OPENAI_ADAPTERS_ALL_FALSE)
    expect_trip("adapters-all-false fixture (D3)", bad_ad_allfalse,
                "no OpenAIModelsApi.fetchModels call forwards")
    bad_ad_none = oai(adapters=BAD_OPENAI_ADAPTERS_NO_SITE)
    expect_trip("adapters-no-callsite fixture (D3)", bad_ad_none, "call not found")

    print("selftest voice newCall decoy     -> %d violation(s)" % len(bad_vp_decoy))
    print("selftest xunfei WS decoy         -> %d violation(s)" % len(bad_vv_decoy))
    print("selftest generateImage UA drop   -> %d violation(s)" % len(bad_img_drop))
    print("selftest openai passthrough drop -> %d violation(s)" % len(bad_p_drop))
    print("selftest openai passthrough order-> %d violation(s)" % len(bad_p_after))
    print("selftest openai auth single-src  -> %d violation(s)" % len(bad_auth))
    print("selftest openai api-key dup      -> %d violation(s)" % len(bad_dup))
    print("selftest api-key pair-form src   -> %d violation(s)" % len(good_oai_pair))
    print("selftest api-key pair-form dup   -> %d violation(s)" % len(bad_dup_pair))
    print("selftest api-key pair-form nofn  -> %d violation(s)" % len(bad_auth_pair))
    print("selftest openai models no-param  -> %d violation(s)" % len(bad_m_param))
    print("selftest openai models no-build  -> %d violation(s)" % len(bad_m_build))
    print("selftest openai models key thread-> %d violation(s)" % len(bad_m_key))
    print("selftest openai models cache thr -> %d violation(s)" % len(bad_m_cache))
    print("selftest openai adapters 2nd arm -> %d violation(s)" % len(bad_ad2))
    print("selftest openai adapters no-site -> %d violation(s)" % len(bad_ad_none))

    print("selftest compliant factory        -> %d violation(s)" % len(good_factory))
    print("selftest compliant gemini         -> %d violation(s)" % len(good_gemini))
    print("selftest dropped-openRouter       -> %d violation(s)" % len(bad_drop))
    print("selftest hard-coded-null          -> %d violation(s)" % len(bad_null))
    print("selftest no-constructor-param     -> %d violation(s)" % len(bad_noparam))
    print("selftest verdict-drops-toolchar   -> %d violation(s)" % len(bad_verdict))
    print("selftest no-tool-accumulation     -> %d violation(s)" % len(bad_accum))
    print("selftest inline-verdict           -> %d violation(s)" % len(bad_callsite))
    print("selftest verdict-fn-deleted       -> %d violation(s)" % len(bad_nofn))
    print("selftest gemini-models hard-null  -> %d violation(s)" % len(bad_gm_null))
    print("selftest gemini-models no-param   -> %d violation(s)" % len(bad_gm_noparam))
    print("selftest openrouter-models null   -> %d violation(s)" % len(bad_orm_null))
    print("selftest adapters-drop            -> %d violation(s)" % len(bad_ad))

    # -- [batch7 B7-t04] the six witnessed bypasses ------------------------
    bad_ua_let = check_factory(BAD_FACTORY_UA_LET_NULL)
    expect_trip("UA-let-null-in-factory-arm fixture (ISSUE-04a)", bad_ua_let,
                "the instance override never arrives")

    bad_double = check_gemini(BAD_GEMINI_DOUBLE_OVERRIDE)
    expect_trip("double-override-blank fixture (ISSUE-04b)", bad_double,
                "does not carry the constructor override")

    bad_toolc = check_gemini(BAD_GEMINI_TOOL_CHARS_CONST)
    expect_trip("tool-chars-helper-constant fixture (ISSUE-04c)", bad_toolc,
                "folds to a constant")

    bad_dead = check_voice(GOOD_VOICE_PROVIDER, GOOD_VOICE_VENDORS,
                           BAD_VOICE_FACTORY_DEAD_GUARD)
    expect_trip("voice factory dead-guard fixture (ISSUE-05 V3)", bad_dead,
                "never-executed branch")

    bad_bearer = check_openai_outbound(
        GOOD_OPENAI_PROVIDER, BAD_OPENAI_MODELS_BEARER, GOOD_OPENAI_AUTH_PAIR,
        GOOD_OPENAI_ADAPTERS, GOOD_OTHER_SOURCES_PAIR)
    expect_trip("models-auth-delegation-dropped fixture (da1030 D2b)", bad_bearer,
                "no longer delegates to applyOpenAIKeyAuth")

    bad_andfalse = check_openai_outbound(
        GOOD_OPENAI_PROVIDER, GOOD_OPENAI_MODELS, GOOD_OPENAI_AUTH_PAIR,
        BAD_OPENAI_ADAPTERS_AND_FALSE, GOOD_OTHER_SOURCES_PAIR)
    expect_trip("adapters-and-false fixture (da1030 D3)", bad_andfalse,
                "neither `instance.azureMode` nor the explicit `false`")

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("PROVIDER_UA_PARITY_SELFTEST=PASS (%d arms)" % len(arms))
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
