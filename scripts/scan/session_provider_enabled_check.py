#!/usr/bin/env python3
"""
Gate: FIX-E (deep-audit-1019 ISSUE-05) — a provider the user disabled must
never be used at runtime, and a session-model resolution site must decide
BEFORE it writes provider state.

ChatSessionLifecycle.kt had three resolution sites that missed the app-wide
"disabled providers are skipped" contract (see ProviderRepository
.enabledMemberEntries / resolveProviderFromGroup, and 1019 TRIAGE ISSUE-05):

  1. loadSession "Priority 2" (stored model_id) — wrote currentModel /
     _modelName / _activeEntryId FIRST and only then looked the instance +
     credential up, so a disabled provider or a missing API key left the UI
     claiming "model ready" while currentProvider stayed null (state
     pollution + bypass of the disable switch).
  2. restoreFromBinding "entry" branch — a user pin on an entry whose provider
     was later disabled still created a provider and dispatched requests to
     it (bypass of the disable switch).
  3. applyNewChatDefaultModel — same order problem + `return true` even when
     the credential was missing (caller told "applied" while nothing was).

The fix funnels all three sites through one pure decision,
`decideSessionModelResolution(entry, instance, apiKey)`, and writes state only
on `SessionModelResolution.Use`.

This gate pins three things (presence-asserted, so a legit refactor is a
deliberate edit to this gate rather than a silent pass):

  A. The funnel exists and enforces the isEnabled gate:
     `!instance.isEnabled` + PROVIDER_DISABLED inside its body.
  B. Each of the three sites routes through the funnel (the decision call is
     present in the site's source segment).
  C. ORDER: within each site segment, the decision call must appear BEFORE
     any provider-state write (`currentModel =` / `_modelName.value =` /
     `_activeEntryId.value =` / `_providerName.value =` / `currentProvider =`).

Usage: python3 scripts/scan/session_provider_enabled_check.py [repo_root]
       python3 scripts/scan/session_provider_enabled_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys
from pathlib import Path

# [batch6-t08] 共享词法 / 语法 / 值流原语（**唯一**实现）。本门不再自写词法器：
# 注释与字符串内容剥离走 `mask_code` / `mask_comments`（嵌套块注释、三引号原始
# 字符串、字符字面量、`${}` 插值全由共享状态机处理，视图长度与源码**严格相等**
# → 位置可回投原文）；括号配平走 `match_bracket`；常量折叠走 `const_bool`；
# 可达性走 `path_feasible`；退出判定走 `returns_exiting`；值流走 `value_sources`。
# 解析失败一律 fail-closed（报违规，绝不回退成原文扫描）。
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    enclosing_conditions,
    function_body,
    if_blocks,
    is_pure_literal,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    brace_depth,
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    live_positions,
    literal_view,
    path_feasible,
    returns_exiting,
    value_sources,
)

LIFECYCLE_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt"
)
APP_DIR = "src/android/app/src/main/java/com/rikkaminis/app"
ROUTING_REL = APP_DIR + "/ui/chat/ChatModelRouting.kt"
PROMPT_TOOLS_REL = APP_DIR + "/ui/chat/ChatPromptAndTools.kt"
DEBUG_DIR_REL = APP_DIR + "/debug"

# [deep-audit-1044 B-03] Every `ProviderFactory.create(...)` in the chat layer
# must hand over the ENRICHED model. The raw arguments below are each the
# entry's `baseModel.withOverrides(overrides)` — that never touches models.dev,
# and `:modelservice` (which decides vision / effort tiers / output cap FROM
# that object) never initialises ModelsDevApi either.
RAW_MODEL_ARGS = ("entry.model", "targetEntry.model", "cachedProvider.model")
# The single sources that ARE allowed to answer "which model": the shared
# resolver on the view model, and models.dev itself.
ENRICH_CALLS = ("providerModelFor(", "ModelsDevApi.effectiveModel(")
FACTORY_CALL = "ProviderFactory.create("
# Fail-closed: the three files carried 8 call sites when dv24-B03 was written.
MIN_FACTORY_CALLS = 8

FUNNEL_DEF = "internal fun decideSessionModelResolution("
FUNNEL_ENABLED = "!instance.isEnabled"
FUNNEL_DISABLED_REASON = "UnusableModelReason.PROVIDER_DISABLED"
USE_TYPE = "SessionModelResolution.Use"

STATE_WRITES = (
    "currentModel =",
    "_modelName.value =",
    "_activeEntryId.value =",
    "_providerName.value =",
    "currentProvider =",
)

P2_START = "// Priority 2: fall back to stored model_id"
P2_END = "// Priority 3: fall back to default group"
BINDING_SIG = "internal fun ChatViewModel.restoreFromBinding("
ENTRY_BRANCH_START = '"entry" -> {'
ENTRY_BRANCH_END = "else -> false"
APPLY_SIG = "internal fun ChatViewModel.applyNewChatDefaultModel()"

# ── B-03: the `ProviderFactory.create` model argument ─────────────────────────
# Every create site in the chat layer must hand over the ENRICHED model.
_DECL_HEAD = re.compile(
    r"^[ \t]*(?:internal |private |public |override |suspend )*fun\s", re.M)


# ── shared lexical / structural / value-flow primitives ─────────────────────
# [batch6-t08] 本门不再自写词法器。注释与字符串内容剥离走共享 `mask_code` /
# `mask_comments`（嵌套块注释、三引号原始字符串、字符字面量、`${}` 插值全由共享
# 状态机处理，三个视图长度与源码**严格相等** → 位置可回投原文），括号配平走
# `match_bracket`，常量折叠走 `const_bool`，可达性走 `path_feasible`，退出判定走
# `returns_exiting`，值流走 `value_sources`。解析失败一律 fail-closed：报违规，
# 绝不回退成原文扫描（parser-death 轴）。
_KW = frozenset((
    "val", "var", "fun", "return", "if", "else", "when", "is", "as", "in",
    "it", "null", "true", "false", "this", "let", "run", "also", "apply",
    "onEach", "forEach", "with", "takeIf", "takeUnless", "first", "firstOrNull",
    "last", "lastOrNull", "get", "elementAt", "map", "mapNotNull", "filter",
))


def _views(text):
    """(code, literal) 等长双视图；解析失败 → (None, None)（调用方 fail-closed）。"""
    try:
        return code_view(text), literal_view(text)
    except KotlinLexError:
        return None, None


def _mask_dependent_sites(raw, code):
    """临界 token 的可见性是否依赖「字符串感知」这一词法细节（fail-closed）。

    [外部见证 03-7 的形状] `val tag = "http://x"; ProviderFactory.create(…)`：
    同一个 token，string-blind 读法（`line.split('//')[0]`，本门的旧实现）把
    后半行整段当注释吃掉，共享掩码器看得见。改用 `mask_code` 之后这个形状
    **本身**已经无害——但「这一行有两种读法」必须显式拒绝：两套读法给出不同的
    token 数，说明有个字符串里藏着 `//`，任何单一读法都不能替它背书。
    真树：8 个 create 站点各自独占一行 ⇒ 0 命中。
    """
    tokens = (FACTORY_CALL, FUNNEL_CALL, FUNNEL_DEF, BINDING_WRITE_CALL,
              "!instance.isEnabled")
    naive = "\n".join(line.split("//")[0] for line in raw.split("\n"))
    return [tok for tok in tokens if code.count(tok) != naive.count(tok)]


def _enclosing_source(code, pos):
    """从最近的 `fun` 声明到 pos —— 局部 `val` 绑定可能就在这里被解析。"""
    last = None
    for m in _DECL_HEAD.finditer(code, 0, pos):
        last = m
    return code[last.start():pos] if last else code[:pos]


def _all_sources_enriched(expr):
    """表达式的**所有可能值**是否都来自富化模型的单一来源。

    [batch6-t08 03-2] 「富化调用出现在文本里」不是问题本身：
    `providerModelFor(entry).let { entry.model }` 让调用留在文本里、真正求值的
    是裸 `entry.model`（`let`/`run` 取 lambda 尾表达式，`also`/`apply` 只取接收
    者，`?:` 两侧都算）。全部由共享 `value_sources` 折叠，本门不再自写剥离器。
    """
    sources = value_sources(expr)
    return bool(sources) and all(any(tok in s for tok in ENRICH_CALLS)
                                for s in sources)


def _assigned_from(window, name):
    """`val name = …` 的**值**是否来自富化模型（不是「文本里出现过调用」）。"""
    pat = re.compile(r"\b(?:val|var)\s+" + re.escape(name) + r"\b[^=\n]*=(?!=)")
    for m in pat.finditer(window):
        rhs = window[m.end():expr_end(window, m.end())]
        if _all_sources_enriched(rhs):
            return True
    return False


def _implies(cond, test):
    """`cond` 为真是否**必然**让 `test(叶)` 成立。

    析取：每个分支都必须成立（`a || b` 只有一边成立推不出结论）；
    合取：任一边成立即可（`a && b` 为真 ⇒ `a` 为真）。叶判定由调用方给。
    """
    c = strip_parens(cond)
    if not c:
        return False
    parts = split_top(c, ("||",))
    if len(parts) > 1:
        return all(_implies(c[a:b], test) for a, b in parts)
    parts = split_top(c, ("&&",))
    if len(parts) > 1:
        return any(_implies(c[a:b], test) for a, b in parts)
    return test(c)


def _use_leaf(part):
    """叶：「decision is SessionModelResolution.Use」（取反形态不算）。"""
    p = strip_parens(part)
    if p.startswith("!"):
        return False
    return re.search(r"(?<![\w!])\bis\s+" + re.escape(USE_TYPE), p) is not None


def _neg_use_leaf(part):
    """叶：「decision !is SessionModelResolution.Use」。"""
    p = strip_parens(part)
    if p.startswith("!"):
        return False
    return re.search(r"!\s*is\s+" + re.escape(USE_TYPE), p) is not None


def _positive_name_leaf(names):
    """叶：「解析答案是 yes」（`resolved` 这类名字，取反形态不算）。"""
    def leaf(part):
        p = strip_parens(part)
        if p.startswith("!"):
            return False
        for name in names:
            if not contains_ident(p, name):
                continue
            if re.search(r"!\s*" + re.escape(name) + r"\b", p):
                continue
            if re.search(re.escape(name) + r"\s*==\s*(?:false|null|0)\b", p):
                continue
            if re.search(re.escape(name) + r"\s*!=\s*true\b", p):
                continue
            return True
        return False
    return leaf


def _implies_at(code, pos, test):
    """pos 处是否被某个「蕴含 test」的**活**条件包住。

    `path_feasible` 先挡死分支（`if (false && decision is Use)` / `when { false -> }`），
    再看覆盖 pos 的每一个条件是否蕴含结论 —— `decision is Use || entry != null`
    这种「有一个析取臂什么也推不出」的条件不再放行（外部见证 03-3）。
    """
    if not path_feasible(code, pos):
        return False
    for _kind, cond in enclosing_conditions(code, pos):
        if _implies(cond, test):
            return True
    return False


def _exits_block(text, t_start, t_end):
    """块体（或单语句分支）内是否有**退出外层函数**的 return。

    `if_blocks` 给的 t_start 在 `{` **之前**，直接喂 `returns_exiting` 会把
    函数体自己的深度当成基准（`return` 在块内深一层 ⇒ 永远判不出）。这里先
    走到块体内部再取基准深度；`return@label` 由共享实现排除。
    """
    i = t_start
    while i < t_end and text[i] in " \t\r\n":
        i += 1
    inner = i + 1 if (i < t_end and text[i] == "{") else i
    if inner >= t_end:
        return False
    return returns_exiting(text, inner, t_end,
                           fn_depth=brace_depth(text, inner))


def _live_terminating_guard(text, pattern):
    """某个 `if` 的**条件**里出现 pattern，且该块体真的退出函数。

    pattern 在条件里匹配（不是体内）：`if (false && !instance.isEnabled)` 由
    `guard_feasible` 折成不可行 ⇒ 不是守卫；体内被掏空 ⇒ `_exits_block` 为假；
    守卫落在死分支里 ⇒ `path_feasible` 为假。三者都必须过，才算「这道闸门活着」。
    """
    rx = re.compile(pattern)
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(text):
        if not rx.search(cond):
            continue
        if not guard_feasible(cond):
            continue
        if not path_feasible(text, t_start):
            continue
        if _exits_block(text, t_start, t_end):
            return True
    return False


def _terminating_negative_guard(code, lo, hi):
    """[lo, hi) 之间是否有「`!is …Use` → return」的终止守卫。

    `if (decision !is …Use) { … return false }` 之后的语句才算「可用侧」。
    守卫条件必须是**蕴含**「不是 Use」的活条件：`false && …` 永不执行，
    `… || entry != null` 的另一个析取臂什么也推不出，都不构成许可。
    """
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(code):
        if not (lo <= t_start and t_end <= hi):
            continue
        if not guard_feasible(cond):
            continue
        if not _implies(cond, _neg_use_leaf):
            continue
        if _exits_block(code, t_start, t_end):
            return True
    return False


def _constant_use_guards(body):
    """条件被常量决定的 `if (… Use …)` —— Use 判定成了摆设（外部见证 03-7/W4）。

    `if (false && decision !is …Use) return`：守卫永不执行，拒绝分支形同删除；
    `if (true || decision !is …Use) return`：条件恒真，Use 判定根本没被求值。
    两者都由共享 `const_bool` 折叠出来（`false && x` / `true || x` 短路）。
    """
    out = []
    for cond, _ts, _te, _es, _ee in if_blocks(body):
        if USE_TYPE not in cond:
            continue
        if const_bool(cond) is None:
            continue
        out.append(cond.strip()[:90])
    return out


_ARRAY_PICK = re.compile(r"\b([A-Za-z_]\w*)\s*\[\s*\d+\s*\]")
_LAMBDA_PICKS = frozenset((
    "minBy", "minByOrNull", "maxBy", "maxByOrNull", "sortedBy",
    "sortedByDescending", "sortedWith", "take", "drop",
))
_POSITION_PICKS = frozenset((
    "first", "firstOrNull", "last", "lastOrNull", "get", "elementAt",
    "single", "singleOrNull", "head", "component1",
)) | _LAMBDA_PICKS


def _member_collections(body):
    """派生「装着一组成员」的集合名：右值直接/间接来自 `memberEntryIds`。

    不写死 `members`：见证 03-6 把 `group.memberEntryIds.first()` 换成
    `members.minByOrNull { 0 }`，接收者是映射后的局部变量。
    """
    names = {"memberEntryIds"}
    for _ in range(4):          # 不动点：链式派生（members -> usable -> …）
        grew = False
        for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\b[^=\n]*=(?!=)", body):
            name = m.group(1)
            if name in names:
                continue
            rhs = body[m.end():expr_end(body, m.end())]
            if any(contains_ident(rhs, n) for n in names):
                names.add(name)
                grew = True
        if not grew:
            break
    return names


def _positional_picks(body, receivers):
    """[(pos, text)] —— 在成员集合上按**位置**取成员（等于「谁排第一就用谁」）。

    位置选择 = 无谓词选择：`.first()` / `.get(0)` / `x[0]` / `.minBy { 常量 }`。
    `.firstOrNull { it.id == mid }`（真谓词）与 `.minByOrNull { it.priority }`
    （真选择器）不算——那是过滤/排序，不是「取头一个」。
    """
    out = []
    for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\.\s*("
                         + "|".join(sorted(_POSITION_PICKS)) + r")\s*([({])", body):
        recv, call, opener = m.group(1), m.group(2), m.group(3)
        if recv not in receivers:
            continue
        open_pos = m.end() - 1
        close = match_bracket(body, open_pos)
        if close < 0:
            out.append((m.start(), m.group(0).strip()))
            continue
        inner = body[open_pos + 1:close]
        if opener == "{":
            if is_pure_literal(inner):        # 常量选择器 = 位置
                out.append((m.start(), m.group(0).strip()))
            continue
        args = call_args(body, open_pos)
        if not args or all(is_pure_literal(body[a:b]) for a, b in args):
            out.append((m.start(), m.group(0).strip()))
    for m in _ARRAY_PICK.finditer(body):
        if m.group(1) in receivers:
            out.append((m.start(), m.group(0).strip()))
    return out


def check_factory_models(texts, min_calls=None):
    """Every `ProviderFactory.create` in the chat layer must be handed the
    ENRICHED model — judged on the VALUE, not on the spelling.

    Returns (violations, site_count).
    """
    min_calls = MIN_FACTORY_CALLS if min_calls is None else min_calls
    v, sites = [], 0
    for rel in sorted(texts):
        code, _lit = _views(texts[rel])
        if code is None:
            v.append("%s: cannot lex (unterminated string/comment) — fail-closed"
                     % rel)
            continue
        for tok in _mask_dependent_sites(texts[rel], code):
            v.append("%s: `%s` 的可见性依赖字符串感知词法（同一物理行上有字符串"
                     "含 `//`，string-blind 读法把这半行整段吃掉）—— 站点读法"
                     "不唯一，拒绝放行" % (rel, tok))
        for m in re.finditer(re.escape(FACTORY_CALL), code):
            sites += 1
            args = call_args(code, m.end() - 1)
            if len(args) < 3:
                v.append("%s: `ProviderFactory.create(` at offset %d has an "
                         "unparseable argument list — fail-closed"
                         % (rel, m.start()))
                continue
            model_arg = code[args[2][0]:args[2][1]].strip()
            if model_arg in RAW_MODEL_ARGS:
                v.append("%s: `ProviderFactory.create(..., %s, ...)` hands over "
                         "the RAW entry model — it must be resolved through %s"
                         % (rel, model_arg, " / ".join(ENRICH_CALLS)))
                continue
            if _all_sources_enriched(model_arg):
                continue
            if (re.fullmatch(r"[A-Za-z_]\w*", model_arg)
                    and _assigned_from(_enclosing_source(code, m.start()),
                                       model_arg)):
                continue
            v.append("%s: the model argument of `ProviderFactory.create(` is "
                     "`%s`, which is not resolved through %s"
                     % (rel, model_arg[:60], " / ".join(ENRICH_CALLS)))
    if sites < min_calls:
        v.append("only %d `%s` site(s) in the chat layer (expected at least %d) "
                 "— detection lost, ROT"
                 % (sites, FACTORY_CALL, min_calls))
    return v, sites


_STATE_WRITE_RE = re.compile(
    r"\b(?:currentModel|_modelName\.value|_activeEntryId\.value"
    r"|_providerName\.value|currentProvider)\s*=(?!=)")


def _segment(text, start_needle, end_needle):
    """[start_needle .. end_needle) 的偏移区间；任一锚点缺失 → None（fail-closed）。"""
    i = text.find(start_needle)
    if i < 0:
        return None
    j = text.find(end_needle, i + len(start_needle))
    if j < 0:
        return None
    return (i, j)


def _funnel_span(code):
    """漏斗函数体（`function_body`，括号配平派生）——自写花括号计数已删除。"""
    got = function_body(code, "decideSessionModelResolution")
    if not got:
        return None
    return code[got[0]:got[1]]


def check_source(text):
    """Return a list of violation strings for the lifecycle source."""
    v = []
    code, _lit = _views(text)
    if code is None:
        return ["ChatSessionLifecycle.kt cannot be lexed (unterminated string or "
                "block comment) — fail-closed"]

    # ── A. the funnel must gate, and the gate must actually run ───────────
    # [batch6-t08 03-1] 「注解里含 `!instance.isEnabled`」不是判据：条件被常量
    # 折死的 `if (false && !instance.isEnabled)` 里，禁用的 provider 从不被拒绝。
    funnel = _funnel_span(code)
    if funnel is None:
        v.append("funnel missing: decideSessionModelResolution not found ( ROT? )")
    else:
        if not _live_terminating_guard(funnel, r"!\s*instance\s*\.\s*isEnabled"):
            v.append("funnel does not gate on a REACHABLE, terminating "
                     "`!instance.isEnabled` early exit — a disabled provider is "
                     "never rejected")
        if not live_positions(funnel, r"UnusableModelReason\s*\.\s*PROVIDER_DISABLED"):
            v.append("funnel does not map a disabled provider to "
                     "UnusableModelReason.PROVIDER_DISABLED")
        if not _live_terminating_guard(funnel, r"apiKey\s*==\s*null"):
            v.append("funnel does not reject a missing credential on a reachable "
                     "`apiKey == null` early exit")
        if not live_positions(funnel, re.escape(USE_TYPE)):
            v.append("funnel does not produce SessionModelResolution.Use")

    # ── B. the three resolution sites must route through the funnel ──────
    site_segments = {}
    p2 = _segment(text, P2_START, P2_END)
    if p2 is None:
        v.append("loadSession Priority 2 segment not found (anchors drifted)")
    else:
        site_segments["loadSession-priority2"] = p2

    binding_head = text.find(BINDING_SIG)
    if binding_head < 0:
        v.append("restoreFromBinding signature not found (anchors drifted)")
    else:
        entry = _segment(text[binding_head:], ENTRY_BRANCH_START, ENTRY_BRANCH_END)
        if entry is None:
            v.append('restoreFromBinding "entry" branch not found (anchors drifted)')
        else:
            site_segments["restoreFromBinding-entry"] = (binding_head + entry[0],
                                                        binding_head + entry[1])

    apply_span = function_body(code, "applyNewChatDefaultModel")
    if not apply_span:
        v.append("applyNewChatDefaultModel signature not found (anchors drifted)")
    else:
        site_segments["applyNewChatDefaultModel"] = (apply_span[0], apply_span[1])

    # ── C. decide-then-write, and only on the live Use side ─────────────
    # [batch6-t08 03-3] 「分支头里出现了 Use」不是判据：`is Use || entry != null`
    # 的另一个析取臂什么也推不出。写入点必须被一个**蕴含** Use 的活条件包住，
    # 或者被 `if (decision !is …Use) return` 的终止守卫挡在外面。
    for site in sorted(site_segments):
        a, b = site_segments[site]
        seg = code[a:b]
        call_at = seg.find(FUNNEL_CALL)
        if call_at < 0:
            v.append("%s: does not route through %s — a DISABLED or key-less "
                     "provider becomes the session model anyway"
                     % (site, FUNNEL_CALL.rstrip("(")))
            continue
        abs_call = a + call_at
        for token in STATE_WRITES:
            w = seg.find(token)
            if 0 <= w < call_at:
                v.append("%s: `%s` runs before the decision — every state write "
                         "must come after (and inside the Use branch of) %s"
                         % (site, token, FUNNEL_CALL.rstrip("(")))
                break
        for wm in _STATE_WRITE_RE.finditer(seg):
            wp = a + wm.start()
            if wp < abs_call:
                continue
            if _implies_at(code, wp, _use_leaf):
                continue
            if _terminating_negative_guard(code, abs_call, wp):
                continue
            v.append("%s: `%s` runs after the decision but OUTSIDE its Use branch "
                     "(no live `if (decision !is …Use) return` between them) — the "
                     "UI can claim a model the funnel refused"
                     % (site, wm.group(0).strip()))
            break

    # ── D. applyNewChatDefaultModel must publish currentModel too ────────
    if apply_span:
        apply_seg = code[apply_span[0]:apply_span[1]]
        if "currentModel =" not in apply_seg:
            v.append("applyNewChatDefaultModel does not write `currentModel =` — a "
                     "new chat with no default group persists model_id=unknown "
                     "(B-04)")
    return v


def _read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def run(root):
    texts = {}
    for rel in (LIFECYCLE_REL, ROUTING_REL, PROMPT_TOOLS_REL):
        text = _read(root, rel)
        if text is None:
            print("session_provider_enabled_check: missing target %s" % rel)
            return 2
        texts[rel] = text
    for rel in sorted(texts):
        if _views(texts[rel])[0] is None:
            print("session_provider_enabled_check: %s cannot be lexed — fail-closed" % rel)
            return 1
    violations = check_source(texts[LIFECYCLE_REL])
    factory_v, sites = check_factory_models(texts)
    violations += factory_v
    # [deep-audit-1047 B-04] group selection / member resolution sync
    violations += check_group_selection(texts)
    # [dv29-A2] C-SEL-4: the debug/IPC binding write must use the same decision
    debug_texts = {}
    debug_dir = os.path.join(root, DEBUG_DIR_REL)
    if os.path.isdir(debug_dir):
        for name in sorted(os.listdir(debug_dir)):
            if not name.endswith(".kt"):
                continue
            with open(os.path.join(debug_dir, name), "r", encoding="utf-8") as f:
                debug_texts[DEBUG_DIR_REL + "/" + name] = f.read()
    if not debug_texts:
        violations.append(
            "missing target %s/*.kt — C-SEL-4 cannot be judged (fail-closed)"
            % DEBUG_DIR_REL)
    else:
        violations += check_binding_writes(debug_texts)
    if violations:
        for x in violations:
            print("session_provider_enabled_check: %s" % x)
        print("GATE=RED session_provider_enabled (%d violation(s))" % len(violations))
        return 1
    print("session_provider_enabled_check: %d ProviderFactory.create site(s) resolved "
          "through the enriched model" % sites)
    print("GATE=GREEN session_provider_enabled")
    return 0


# ── [deep-audit-1047 B-04] group selection / member resolution sync ────────
#
# Three disconnects, all in the two files that own "which member serves this
# session":
#  C-SEL-1  selectGroup / selectGroupEntry published the display state
#           (_selectedGroupId / _selectedGroupName) BEFORE asking whether the
#           group could serve, so a group resolving to nothing still flipped
#           the top bar and the picker highlight while provider / binding
#           stayed on the old one.
#  C-SEL-2  the same call wrote the RAW requested entry id into
#           pendingEntryOverride / the persisted `lastEntryId` while a comment
#           two lines below declared "_activeEntryId is the truth" — after a
#           fallback the session was bound to a member that had been skipped.
#  C-SEL-3  selectEntry re-derived "instance + key" inline (no disable gate),
#           and both group consumers filtered members by `isEnabled` only, so
#           a key-less member at the head of a group sank the whole group.
#
# Every name below is DERIVED from the tree, not hard-coded: the acceptable
# "usability filter" wrappers come from the signatures that take a
# List<ModelEntry> and consult the funnel, and the names that count as "the
# answer of a resolution" come from the assignments whose right-hand side
# calls one of the resolution entry points. Renaming a helper keeps the gate
# green; deleting it turns every consumer red.


CSEL_GROUP_WRITE = "_selectedGroup"
FUNNEL_CALL = "decideSessionModelResolution("
FUNNEL_USE = "SessionModelResolution.Use"
RESOLVE_CALL = "resolveProviderFromGroup("
ACTIVE_ENTRY = "_activeEntryId"
MEMBERS_CALL = "enabledMemberEntries("

# The three ways a "can this group/member serve?" answer is produced. A local
# name assigned from any of them is accepted as the guard of a group write.
_RESOLVE_SOURCES = (RESOLVE_CALL, "planGroupSelect(", FUNNEL_CALL)

_EMPTY_VALUES = ("null", '""')
_WRITE_RE = re.compile(r"_(?:selectedGroupId|selectedGroupName)\.value\s*=\s*(.*)$", re.M)
_LAST_ENTRY_RE = re.compile(r'lastEntryId\\{0,2}":\\{0,2}"\$([A-Za-z_][A-Za-z0-9_]*)')
_DECL_RE = re.compile(r"^[ \t]*(?:internal |private |public |override |suspend )*fun\s", re.M)
_ASSIGN_RE = re.compile(
    r"\b([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)\s*((?:[^\n=]|\([^\n)]*\)){0,400})")


def _fun_span(text, pos):
    """(start, end) of the top-level `fun` declaration containing `pos`."""
    starts = [m.start() for m in _DECL_RE.finditer(text)]
    prev = None
    nxt = None
    for s in starts:
        if s <= pos:
            prev = s
        elif nxt is None:
            nxt = s
    if prev is None:
        return None
    return (prev, nxt if nxt is not None else len(text))


def _usable_filters(texts):
    """Names of the functions that narrow a List<ModelEntry> through the funnel."""
    names = set()
    for text in texts.values():
        for m in re.finditer(r"\bfun\s+[^\n(]*?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)", text):
            if "List<ModelEntry>" not in m.group(2):
                continue
            body = text[m.end():m.end() + 1500]
            if FUNNEL_CALL in body:
                names.add(m.group(1))
    return names


def _is_resolver(header):
    """True when the enclosing function IS the resolution.

    `resolveProviderFromGroup` publishes the group name on its own success
    path — by the time that line runs it has already established that a member
    can serve, so requiring it to be guarded by a resolution would be
    circular. Same for the funnel itself.
    """
    return RESOLVE_CALL.rstrip("(") in header or FUNNEL_CALL.rstrip("(") in header


def _resolution_names(code):
    """局部名 → 右值调用了某个解析入口（`resolved` / `plan` 由此派生）。

    [batch6-t08 03-4] 「守卫条件里出现了这个名字」不等于「这个答案被采纳了」：
    `if (resolved || !resolved)` 恒真、`if (resolved && x)` 只在 x 时。判据改成
    「条件**蕴含**答案为 yes」（析取全成立 / 合取任一侧成立），取反形态不算。
    """
    names = set()
    for m in re.finditer(r"\bval\s+([A-Za-z_]\w*)\b[^=\n]*=(?!=)", code):
        rhs = code[m.end():expr_end(code, m.end())]
        if any(src in rhs for src in _RESOLVE_SOURCES):
            names.add(m.group(1))
    return names


def _traces_to_active(code, name, hops=3):
    """`name` 的**值**是否来自真正解析出来的 entry id（`_activeEntryId`）。

    [batch6-t08 03-5] `plan.effectiveEntryId.let { entryId }` 让解析链留在文本
    里、值却是入参 `entryId`：共享 `value_sources` 把 `let`/`run` 折叠到尾
    表达式后，来源里根本没有 `_activeEntryId`，递归也走不到它。
    """
    seen = set()

    def rec(n, h):
        if h <= 0 or n in seen:
            return False
        seen.add(n)
        m = re.search(r"\bval\s+" + re.escape(n) + r"\b[^=\n]*=(?!=)", code)
        if not m:
            return False
        rhs = code[m.end():expr_end(code, m.end())]
        sources = value_sources(rhs)
        if any(contains_ident(s, ACTIVE_ENTRY) for s in sources):
            return True
        for src in sources:
            for cand in sorted(set(re.findall(r"\b([A-Za-z_]\w{2,})\b", src))):
                if cand in _KW:
                    continue
                if rec(cand, h - 1):
                    return True
        return False

    return rec(name, hops)


def check_group_selection(texts):
    """C-SEL-1/2/3 across the chat layer. Returns a list of violations."""
    v = []
    filters = _usable_filters(texts)
    views = {}
    for rel, t in texts.items():
        code, lit = _views(t)
        if code is None:
            v.append("%s: cannot lex (unterminated string/comment) — fail-closed"
                     % rel)
            continue
        views[rel] = (code, lit)
    names = set()
    for code, _lit in views.values():
        names |= _resolution_names(code)

    for rel in sorted(views):
        code, lit = views[rel]
        # ── C-SEL-1: a group id may only be published once it resolved ─────
        for m in _WRITE_RE.finditer(code):
            rhs = m.group(1).strip()
            if rhs.startswith(_EMPTY_VALUES):
                # clearing the selection is the inverse operation — no gate.
                continue
            span = _fun_span(code, m.start())
            if span is None:
                v.append("%s group write outside any function (ROT?)" % rel)
                continue
            fun_start, _fun_end = span
            header = code[fun_start:code.find("\n", fun_start)]
            if _is_resolver(header):
                continue
            if not _implies_at(code, m.start(), _positive_name_leaf(names)):
                v.append(
                    "%s: `%s` publishes a group that nothing established as "
                    "servable — the branch that publishes it must have a "
                    "condition that IMPLIES the resolution said yes "
                    "(C-SEL-1)" % (rel, m.group(0).strip()))

        # ── C-SEL-2: one id per call — the persisted id is the resolved one ─
        # the binding JSON is a string literal, so this one runs on the
        # literal-preserving view (mask_code would blank the interpolation).
        for m in _LAST_ENTRY_RE.finditer(lit):
            name = m.group(1)
            span = _fun_span(code, m.start())
            if span is None:
                continue
            fun_start, fun_end = span
            body = code[fun_start:fun_end]
            if ACTIVE_ENTRY not in body or name.startswith(ACTIVE_ENTRY):
                continue
            if not _traces_to_active(code, name):
                v.append(
                    "%s: persisted `lastEntryId` uses `%s`, which does not come "
                    "from %s — the same call must write one id (C-SEL-2)"
                    % (rel, name, ACTIVE_ENTRY))

        # ── C-SEL-3: single decision point ─────────────────────────────────
        if rel.endswith("ChatModelRouting.kt"):
            sm = re.search(r"\bfun\s+ChatViewModel\.selectEntry\s*\(", code)
            if sm is None:
                v.append("%s: selectEntry not found (anchors drifted)" % rel)
            else:
                span = _fun_span(code, sm.start())
                body = code[span[0]:span[1]]
                if FUNNEL_CALL not in body:
                    v.append(
                        "%s: selectEntry does not route through %s — a "
                        "DISABLED instance carrying a key is activated anyway "
                        "(C-SEL-3)" % (rel, FUNNEL_CALL.rstrip("(")))

        for m in re.finditer(re.escape(MEMBERS_CALL), code):
            pre = code[max(0, m.start() - 80):m.start()]
            if not any((f + "(") in pre for f in filters):
                v.append(
                    "%s: `%s` hands the router members that were never checked "
                    "for a credential — a key-less head member sinks the whole "
                    "group (C-SEL-3)" % (rel, MEMBERS_CALL.rstrip("(")))
    return v


# ── selftest fixtures ────────────────────────────────────────────────────────

CSEL_ROUTING_GOOD = '''fun ChatViewModel.selectGroup(groupId: String) {
    groupRouter.clearHealth()
    val resolved = resolveProviderFromGroup(groupId)
    if (resolved) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name
        pendingEntryOverride = null
    }
}

fun ChatViewModel.selectGroupEntry(groupId: String, entryId: String) {
    groupRouter.clearHealth()
    val resolved = resolveProviderFromGroup(groupId, entryId)
    val plan = planGroupSelect(resolved = resolved, resolvedId = _activeEntryId.value, requestedId = entryId)
    if (plan.applyUi) {
        val resolvedEntryId = plan.effectiveEntryId ?: return
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name
        persistBinding(""{"type":"group","groupId":"$groupId","lastEntryId":"$resolvedEntryId"}"")
        providerRepository.lastUsedEntryId = resolvedEntryId
    }
}

fun ChatViewModel.selectEntry(entryId: String) {
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    val instance = providerRepository.instance(entry.providerInstanceId)
    val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
    val decision = decideSessionModelResolution(entry, instance, apiKey)
    if (decision !is SessionModelResolution.Use) return
    _selectedGroupId.value = null
    _selectedGroupName.value = ""
}

internal fun ChatViewModel.rotateForNewTurn(label: String) {
    val group = providerRepository.group(gid) ?: return
    val members = usableGroupMembers(providerRepository.enabledMemberEntries(group))
    val targetId = groupRouter.nextLoadBalanceMember(members)
}
'''

# ① the pre-fix shape: display state first, resolution afterwards.
CSEL_ROUTING_PRE = '''fun ChatViewModel.selectGroup(groupId: String) {
    _selectedGroupId.value = groupId
    groupRouter.clearHealth()
    val resolved = resolveProviderFromGroup(groupId)
    if (resolved) {
        pendingEntryOverride = null
    }
}

fun ChatViewModel.selectGroupEntry(groupId: String, entryId: String) {
    _selectedGroupId.value = groupId
    _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
    groupRouter.clearHealth()
    val resolved = resolveProviderFromGroup(groupId, entryId)
    if (resolved) {
        persistBinding(""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"")
        _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
    }
}

fun ChatViewModel.selectEntry(entryId: String) {
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return
    val apiKey = providerRepository.loadApiKey(instance.id) ?: return
    _selectedGroupId.value = null
}

internal fun ChatViewModel.rotateForNewTurn(label: String) {
    val group = providerRepository.group(gid) ?: return
    val members = providerRepository.enabledMemberEntries(group)
    val targetId = groupRouter.nextLoadBalanceMember(members)
}
'''

CSEL_LIFECYCLE_GOOD = '''internal fun ChatViewModel.usableGroupMembers(members: List<ModelEntry>): List<ModelEntry> =
    members.filter { member ->
        val instance = providerRepository.instance(member.providerInstanceId)
        decideSessionModelResolution(member, instance, null) is SessionModelResolution.Use
    }

internal fun ChatViewModel.resolveProviderFromGroup(groupId: String): Boolean {
    val group = providerRepository.group(groupId) ?: return false
    val enabledMembers = usableGroupMembers(providerRepository.enabledMemberEntries(group))
    if (enabledMembers.isEmpty()) return false
    return true
}

internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
    val resolved = resolveProviderFromGroup(groupId, lastEntryId)
    if (resolved) _selectedGroupId.value = groupId
    return resolved
}
'''

# ── [dv29-A2] C-SEL-4: a session-model BINDING write must make the same
# decision as the runtime gate ───────────────────────────────────────────────
#
# `session.primaryModel` (ConfigBuiltins) refuses a disabled or key-less
# instance and refuses a group with no usable member. The debug/IPC twin —
# `HeadlessChatRunner.applyModelOverride`, reached by `chat.model.select` and by
# every headless send that carries a model override — checked only
# `instance.isEnabled` on the entry branch and nothing at all on the group
# branch:
#   * a key-less instance wrote the binding, `restoreFromBinding` then refused it
#     (same funnel, different opinion), the resident VM fell back to the default
#     model, and the RPC still returned the refused model's display name — the
#     caller believes a turn ran on a model it never touched;
#   * the group branch wrote `memberEntryIds.first()` as `model_id`, which need
#     not be a member the runtime would ever pick.
# Both are the "two write entries, one decision" shape, so the judgement is
# derived, not spelled:
#   * WRITERS — functions that perform `updateSessionBinding(`;
#   * ENTRIES — functions that call a writer (so a new binding entry is covered
#     the day it is written);
#   * and each entry must reach `decideSessionModelResolution(` (in its own body
#     or within two hops) — the single decision this gate already pins elsewhere;
#   * plus a positional group-member pick (`memberEntryIds.first()` & friends) is
#     banned outright: it names a member nobody established as usable.
BINDING_WRITE_CALL = "updateSessionBinding("
FUNNEL_NAME = "decideSessionModelResolution"
def _all_funs(code):
    """(name, body) for every `fun` in [code], any indentation."""
    out = []
    for m in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", code):
        span = _fun_span(code, m.start())
        if span is None:
            continue
        out.append((m.group(1), code[span[0]:span[1]]))
    return out


def _funnel_reachable(body, index, hops=2):
    if FUNNEL_NAME + "(" in body:
        return True
    if hops <= 1:
        return False
    for m in re.finditer(r"\b(\w+)\s*\(", body):
        for nested in index.get(m.group(1), ()):
            if _funnel_reachable(nested, index, hops - 1):
                return True
    return False


def check_binding_writes(texts):
    """C-SEL-4 across `debug/*.kt`. Returns a list of violations."""
    code_by_rel = {}
    v = []
    for rel, t in texts.items():
        code, _lit = _views(t)
        if code is None:
            v.append("%s: cannot lex (unterminated string/comment) — fail-closed"
                     % rel)
            continue
        code_by_rel[rel] = code
    index = {}
    for code in code_by_rel.values():
        for name, body in _all_funs(code):
            index.setdefault(name, []).append(body)
    writers = {
        name
        for name, bodies in index.items()
        if any(BINDING_WRITE_CALL in body for body in bodies)
    }
    if not writers:
        return [
            "debug/*: no function performs `%s` — the binding write path cannot "
            "be judged (fail-closed)" % BINDING_WRITE_CALL.rstrip("(")
        ]
    # [wide-1024] Scope matters: the Use guard is a DECISION site, not only a
    # write-entry one — `selectModel` holds a guard and writes nothing, so an
    # entries-only sweep left it unjudged. Scan every function. [batch6-t08 03-7]
    # The cond is folded by the shared `const_bool`, so `false && …` and
    # `true || …` are both seen as "the Use test is decoration".
    for rel, code in sorted(code_by_rel.items()):
        for name, body in _all_funs(code):
            for cond in _constant_use_guards(body):
                v.append(
                    "%s: `%s` short-circuits the Use test with a constant "
                    "(`if (%s…`) — the funnel is called and its answer is "
                    "ignored (C-SEL-4)" % (rel, name, cond))
    entries = 0
    for rel, code in sorted(code_by_rel.items()):
        for name, body in _all_funs(code):
            if name in writers:
                continue
            if not any(writer + "(" in body for writer in writers):
                continue
            entries += 1
            if not _funnel_reachable(body, index):
                v.append(
                    "%s: `%s` writes the session model binding without routing "
                    "through %s — a disabled instance or a key-less one is "
                    "persisted, the resident VM then refuses the same binding and "
                    "falls back, and the caller is told the refused model was "
                    "used (C-SEL-4)" % (rel, name, FUNNEL_NAME))
            # [batch6-t08 03-6] The pick is judged on the RECEIVER, not on one
            # spelling: `members.minByOrNull { 0 }` picks by position exactly
            # like `memberEntryIds.first()` did, and `members` is a local
            # derived from `memberEntryIds` — the receiver set is derived from
            # the code, the pick set from "no predicate / constant selector".
            for _pos, text in _positional_picks(body, _member_collections(body)):
                v.append(
                    "%s: `%s` picks a group member by POSITION (`%s`) when it "
                    "writes `model_id` — that member need not be one the runtime "
                    "would ever pick; take the first USABLE member through %s "
                    "(C-SEL-4)" % (rel, name, text, FUNNEL_NAME))
    if entries == 0:
        v.append(
            "debug/*: no session-model binding entry found (nothing calls a "
            "`%s` writer) — the criterion is blind, refusing to pass "
            "(fail-closed)" % BINDING_WRITE_CALL.rstrip("("))
    return v


# ── selftest fixtures ────────────────────────────────────────────────────────

GOOD_FUNNEL = """internal fun decideSessionModelResolution(
    entry: ModelEntry,
    instance: ProviderInstance?,
    apiKey: String?,
): SessionModelResolution {
    if (instance == null) return SessionModelResolution.Unusable(UnusableModelReason.MISSING_INSTANCE)
    if (!instance.isEnabled) return SessionModelResolution.Unusable(UnusableModelReason.PROVIDER_DISABLED)
    if (apiKey == null) return SessionModelResolution.Unusable(UnusableModelReason.MISSING_API_KEY)
    return SessionModelResolution.Use(entry, instance, apiKey)
}
"""

GOOD_P2 = """// Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                val instance = providerRepository.instance(entry.providerInstanceId)
                val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
                val decision = decideSessionModelResolution(entry, instance, apiKey)
                if (decision is SessionModelResolution.Use) {
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                }
            }
        }

        // Priority 3: fall back to default group
"""

GOOD_ENTRY = """internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
        return try {
            when ("entry") {
            "entry" -> {
                val entry = providerRepository.config.value.modelEntries.first()
                val instance = providerRepository.instance(entry.providerInstanceId)
                val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
                val decision = decideSessionModelResolution(entry, instance, apiKey)
                if (decision !is SessionModelResolution.Use) return false
                currentModel = entry.model
                _modelName.value = entry.model.displayName
                true
            }
            else -> false
            }
        } catch (_: Exception) { false }
}
"""

GOOD_APPLY = """internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
    val entry = providerRepository.lastUsedVisibleEntry() ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId)
    val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
    val decision = decideSessionModelResolution(entry, instance, apiKey)
    if (decision !is SessionModelResolution.Use) return false
    currentModel = entry.model
    return true
}
"""

# site writes state BEFORE the decision -> must trip the order check
BAD_P2_ORDER = """// Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                currentModel = entry.model
                _activeEntryId.value = entry.id
                val instance = providerRepository.instance(entry.providerInstanceId)
                val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
                val decision = decideSessionModelResolution(entry, instance, apiKey)
                if (decision is SessionModelResolution.Use) {
                    _modelName.value = entry.model.displayName
                }
            }
        }

        // Priority 3: fall back to default group
"""

# entry branch bypasses the funnel entirely (the pre-fix shape) -> must trip
BAD_ENTRY_BYPASS = """internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
        return try {
            when ("entry") {
            "entry" -> {
                val entry = providerRepository.config.value.modelEntries.first()
                val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                val apiKey = providerRepository.loadApiKey(instance.id) ?: return false
                currentModel = entry.model
                true
            }
            else -> false
            }
        } catch (_: Exception) { false }
}
"""

# funnel lost the isEnabled gate -> must trip check A
BAD_FUNNEL_NO_ENABLED = """internal fun decideSessionModelResolution(
    entry: ModelEntry,
    instance: ProviderInstance?,
    apiKey: String?,
): SessionModelResolution {
    if (instance == null) return SessionModelResolution.Unusable(UnusableModelReason.MISSING_INSTANCE)
    if (apiKey == null) return SessionModelResolution.Unusable(UnusableModelReason.MISSING_API_KEY)
    return SessionModelResolution.Use(entry, instance, apiKey)
}
"""

# [deep-audit-1044 B-04] The apply site resolved a model but never published it.
BAD_APPLY_NO_CURRENT_MODEL = GOOD_APPLY.replace(
    "    currentModel = entry.model\n", "")

# [deep-audit-1044 B-03] `ProviderFactory.create` model arguments. Eight sites
# across the three chat files when B-03 was written.
FACTORY_OK = {
    "lc": """internal fun ChatViewModel.a() {
    val effectiveModel = providerModelFor(entry)
    currentProvider = ProviderFactory.create(instance, apiKey, effectiveModel, context)
    currentProvider = ProviderFactory.create(instance, apiKey, providerModelFor(targetEntry), context)
    currentProvider = ProviderFactory.create(instance, apiKey, ModelsDevApi.effectiveModel(entry), context)
}
""",
    "rt": """internal fun ChatViewModel.b() {
    val effectiveModel = providerModelFor(targetEntry)
    currentProvider = ProviderFactory.create(instance, apiKey, effectiveModel, context)
    currentProvider = ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
    currentProvider = ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
}
""",
    "pt": """internal fun ChatViewModel.c(): LLMProvider {
    return ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
}

internal fun ChatViewModel.d(): LLMProvider {
    return ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
}
""",
}

FACTORY_RAW = dict(FACTORY_OK)
FACTORY_RAW["lc"] = FACTORY_OK["lc"].replace(
    "apiKey, effectiveModel, context", "apiKey, entry.model, context", 1)

FACTORY_UNENRICHED = dict(FACTORY_OK)
FACTORY_UNENRICHED["lc"] = FACTORY_OK["lc"].replace(
    "apiKey, effectiveModel, context", "apiKey, entry.baseModel, context", 1)


# [dv29-A2] C-SEL-4 fixtures: the debug-side binding write. Faithful to the
# post-fix production shape (`applyModelOverride` -> `persistBinding`, decision
# in the entry, usable member via the shared helper).
BW_RUNNER_GOOD = """package com.rikkaminis.app.debug

internal object HeadlessChatRunner {
    private fun persistBinding(app: MinisApp, sessionId: String, bindingJson: String, modelId: String) {
        app.chatRepository.updateSessionBinding(sessionId, bindingJson, modelId)
    }

    suspend fun applyModelOverride(app: MinisApp, sessionId: String): String? {
        val cfg = app.providerRepository.config.value
        when (target) {
            is BindingTarget.Entry -> {
                val entry = cfg.modelEntries.firstOrNull { it.id == target.entryId } ?: return null
                val instance = app.providerRepository.instance(entry.providerInstanceId)
                val decision = decideSessionModelResolution(
                    entry, instance, instance?.let { app.providerRepository.loadApiKey(it.id) })
                if (decision !is SessionModelResolution.Use) return null
                persistBinding(app, sessionId, "", entry.baseModel.id)
                entry.model.displayName
            }
            is BindingTarget.Group -> {
                val members = group.memberEntryIds.mapNotNull { mid ->
                    cfg.modelEntries.firstOrNull { it.id == mid }
                }
                val usable = firstUsableGroupEntry(members)
                persistBinding(app, sessionId, "", usable.baseModel.id)
                group.name
            }
            else -> null
        }
    }

    internal fun firstUsableGroupEntry(members: List<ModelEntry>): ModelEntry? {
        return members.firstOrNull { member ->
            val instance = instanceOf(member.providerInstanceId)
            decideSessionModelResolution(
                member, instance, instance?.let { apiKeyOf(it.id) }) is SessionModelResolution.Use
        }
    }
}
"""

# The pre-fix shape: the entry branch checks `isEnabled` only, the group branch
# takes the head member by position.
BW_RUNNER_PRE = BW_RUNNER_GOOD.replace(
    """                val decision = decideSessionModelResolution(
                    entry, instance, instance?.let { app.providerRepository.loadApiKey(it.id) })
                if (decision !is SessionModelResolution.Use) return null""",
    """                if (instance == null || !instance.isEnabled) return null""",
).replace(
    """                val members = group.memberEntryIds.mapNotNull { mid ->
                    cfg.modelEntries.firstOrNull { it.id == mid }
                }
                val usable = firstUsableGroupEntry(members)""",
    """                val usable = group.memberEntryIds.firstOrNull()""",
)

# The write moved out from under any decision: no funnel reachable at all.
BW_RUNNER_NO_FUNNEL = BW_RUNNER_GOOD.replace(
    "decideSessionModelResolution(", "someOtherResolver(").replace(
    "                member, instance, instance?.let { apiKeyOf(it.id) }) is SessionModelResolution.Use",
    "                member, instance, null) is SessionModelResolution.Use")

# No writer at all -> the criterion must fail closed instead of passing blind.
BW_RUNNER_NO_WRITER = BW_RUNNER_GOOD.replace(
    "app.chatRepository.updateSessionBinding(sessionId, bindingJson, modelId)",
    "app.chatRepository.touchSession(sessionId)")


def _fixture(funnel, p2, entry, apply):
    return funnel + "\n" + p2 + "\n" + entry + "\n" + apply + "\n"


# ── [wide-1024] witnesses: the knives that used to walk through this gate ──
# W1 (order): the decision is taken first, and a state write still happens
# BEFORE (a sibling of) the branch that would license it.
W_P2_WRITE_BEFORE_BRANCH = GOOD_P2.replace(
    "                val decision = decideSessionModelResolution(entry, instance, apiKey)\n",
    "                val decision = decideSessionModelResolution(entry, instance, apiKey)\n"
    "                _activeEntryId.value = entry.id\n")

# W2 (dataflow): the enriched call is present and its result is discarded.
W_APPLY_DISCARDED_ENRICH = """internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
    val entry = providerRepository.lastUsedVisibleEntry() ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId)
    val apiKey = instance?.let { providerRepository.loadApiKey(it.id) }
    val decision = decideSessionModelResolution(entry, instance, apiKey)
    if (decision !is SessionModelResolution.Use) return false
    val effectiveModel = entry.model.also { providerModelFor(entry) }
    currentModel = effectiveModel
    currentProvider = ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)
    return true
}
"""

# W3 (dataflow): the resolved id is mentioned inside a discarded `.also` and
# the value stays the requested id.
W_CSEL2_DISCARDED_PLAN = CSEL_ROUTING_GOOD.replace(
    "        val resolvedEntryId = plan.effectiveEntryId ?: return",
    "        val resolvedEntryId = entryId.also { plan.effectiveEntryId }")

# W4 (order): the funnel guard keeps its name and loses its teeth.
W_CSEL4_DEAD_GUARD = BW_RUNNER_GOOD.replace(
    "                if (decision !is SessionModelResolution.Use) return null",
    "                if (false && decision !is SessionModelResolution.Use) return null")


# ── [batch6-t08] witnesses 03-4 / 03-5 / 03-6 / 03-7 ───────────────────────
# W5 (family-hole): the guard keeps the resolution name and is a tautology —
# `resolved || !resolved` is always true, so nothing was ever established.
W_CSEL1_TAUTOLOGY = CSEL_ROUTING_GOOD.replace(
    "    if (resolved) {\n        _selectedGroupId.value = groupId",
    "    if (resolved || !resolved) {\n        _selectedGroupId.value = groupId")

# W6 (dataflow): the resolved id is *mentioned* but `let` throws it away and
# the value collapses back to the requested id.
W_CSEL2_LET_REQUESTED = CSEL_ROUTING_GOOD.replace(
    "        val resolvedEntryId = plan.effectiveEntryId ?: return",
    "        val resolvedEntryId = plan.effectiveEntryId.let { entryId }")

# W7 (family-hole): the position pick moves onto a derived local and gains a
# constant selector — no `memberEntryIds.first(` spelling left anywhere.
W_CSEL4_DERIVED_PICK = BW_RUNNER_GOOD.replace(
    "                val usable = firstUsableGroupEntry(members)",
    "                val usable = firstUsableGroupEntry(members)\n"
    "                    ?: members.minByOrNull { 0 }")

# W8 (parser-death): the shape the pre-fix lexer could not read — a `//` inside
# a string literal, on the same physical line as an otherwise fine create site.
# The model argument stays ENRICHED, so the only possible finding is the
# mask-dependency one: the site's reading must not hinge on the lexer.
W_FACTORY_MASKED_LINE = dict(FACTORY_OK)
W_FACTORY_MASKED_LINE["lc"] = FACTORY_OK["lc"].replace(
    "    currentProvider = ProviderFactory.create(instance, apiKey, effectiveModel, context)",
    '    val tag = "http://x"; currentProvider = '
    "ProviderFactory.create(instance, apiKey, effectiveModel, context)")

def selftest():
    failures = []

    def expect(cond, what):
        if not cond:
            failures.append(what)

    good = check_source(_fixture(GOOD_FUNNEL, GOOD_P2, GOOD_ENTRY, GOOD_APPLY))
    expect(len(good) == 0, "compliant fixture reported %r" % (good,))

    bad_order = check_source(_fixture(GOOD_FUNNEL, BAD_P2_ORDER, GOOD_ENTRY, GOOD_APPLY))
    expect(
        any("before the decision" in x for x in bad_order),
        "order fixture produced no order violation",
    )

    bad_bypass = check_source(_fixture(GOOD_FUNNEL, GOOD_P2, BAD_ENTRY_BYPASS, GOOD_APPLY))
    expect(
        any("restoreFromBinding-entry" in x and "route through" in x for x in bad_bypass),
        "bypass fixture produced no funnel-routing violation",
    )

    bad_gate = check_source(_fixture(BAD_FUNNEL_NO_ENABLED, GOOD_P2, GOOD_ENTRY, GOOD_APPLY))
    expect(
        any("!instance.isEnabled" in x for x in bad_gate),
        "no-enabled-gate fixture produced no isEnabled violation",
    )

    bad_missing = check_source(GOOD_P2)
    expect(
        any("funnel missing" in x for x in bad_missing),
        "empty-source fixture did not fail closed",
    )

    no_current = check_source(
        _fixture(GOOD_FUNNEL, GOOD_P2, GOOD_ENTRY, BAD_APPLY_NO_CURRENT_MODEL))
    expect(
        any("applyNewChatDefaultModel" in x and "currentModel =" in x for x in no_current),
        "apply-without-currentModel fixture produced no B-04 violation",
    )

    def factory_of(name, texts, want, label, min_calls=None):
        v, _sites = check_factory_models(texts, min_calls)
        if len(v) != want:
            print("  [FAIL] factory %s: expected %d finding(s), got %d — %s"
                  % (name, want, len(v), v))
            return False
        print("  [OK]   factory %s (%s)" % (name, label))
        return True

    factory_results = [
        factory_of("enriched", FACTORY_OK, 0,
                   "every create site resolves through the single source"),
        factory_of("raw-entry", FACTORY_RAW, 1,
                   "one raw `entry.model` reds exactly that site"),
        factory_of("unenriched-ident", FACTORY_UNENRICHED, 1,
                   "an identifier not resolved from the source reds"),
        factory_of("rot", {"lc": FACTORY_OK["lc"]}, 1,
                   "too few call sites is ROT, not a silent pass"),
    ]

    print("selftest compliant tree      -> %d violation(s)" % len(good))
    print("selftest state-before-decision-> %d violation(s)" % len(bad_order))
    print("selftest bypassed entry branch-> %d violation(s)" % len(bad_bypass))
    print("selftest funnel w/o isEnabled-> %d violation(s)" % len(bad_gate))
    print("selftest funnel missing      -> %d violation(s)" % len(bad_missing))
    print("selftest apply w/o currentModel-> %d violation(s)" % len(no_current))
    print("selftest factory raw model    -> %d violation(s)"
          % len(check_factory_models(FACTORY_RAW)[0]))

    good_sel = check_group_selection(
        {ROUTING_REL: CSEL_ROUTING_GOOD, LIFECYCLE_REL: CSEL_LIFECYCLE_GOOD})
    expect(len(good_sel) == 0, "compliant group-selection fixture reported %r" % (good_sel,))

    pre_sel = check_group_selection(
        {ROUTING_REL: CSEL_ROUTING_PRE, LIFECYCLE_REL: CSEL_LIFECYCLE_GOOD})
    for crit in ("C-SEL-1", "C-SEL-2", "C-SEL-3"):
        expect(
            any(crit in x for x in pre_sel),
            "pre-fix group-selection fixture produced no %s violation" % crit,
        )
    print("selftest group selection     -> good=%d violation(s), pre=%d"
          % (len(good_sel), len(pre_sel)))

    # [dv29-A2] C-SEL-4: the debug binding write
    bw_good = check_binding_writes({DEBUG_DIR_REL + "/HeadlessChatRunner.kt": BW_RUNNER_GOOD})
    expect(len(bw_good) == 0, "C-SEL-4 compliant fixture reported %r" % (bw_good,))

    bw_pre = check_binding_writes({DEBUG_DIR_REL + "/HeadlessChatRunner.kt": BW_RUNNER_PRE})
    expect(
        any("without routing through" in x for x in bw_pre),
        "C-SEL-4 pre-fix fixture produced no funnel-routing violation",
    )
    expect(
        any("by POSITION" in x for x in bw_pre),
        "C-SEL-4 pre-fix fixture produced no positional-pick violation",
    )

    bw_no_funnel = check_binding_writes(
        {DEBUG_DIR_REL + "/HeadlessChatRunner.kt": BW_RUNNER_NO_FUNNEL})
    expect(
        any("without routing through" in x for x in bw_no_funnel),
        "C-SEL-4 no-funnel fixture produced no violation",
    )

    bw_no_writer = check_binding_writes(
        {DEBUG_DIR_REL + "/HeadlessChatRunner.kt": BW_RUNNER_NO_WRITER})
    expect(
        any("no function performs" in x for x in bw_no_writer),
        "C-SEL-4 no-writer fixture did not fail closed",
    )

    print("selftest C-SEL-4 binding write -> good=%d pre=%d nofunnel=%d nowriter=%d"
          % (len(bw_good), len(bw_pre), len(bw_no_funnel), len(bw_no_writer)))

    # ── [wide-1024] witness knives ────────────────────────────────────────
    w1 = check_source(_fixture(GOOD_FUNNEL, W_P2_WRITE_BEFORE_BRANCH, GOOD_ENTRY, GOOD_APPLY))
    expect(any("loadSession-priority2" in x and "OUTSIDE its Use branch" in x for x in w1),
           "W1: a state write before the Use branch produced no violation (%r)" % (w1,))

    w2, _sites = check_factory_models({"lc": W_APPLY_DISCARDED_ENRICH}, min_calls=1)
    expect(any("not resolved through" in x for x in w2),
           "W2: `entry.model.also { providerModelFor(entry) }` produced no violation (%r)" % (w2,))

    w3 = check_group_selection(
        {ROUTING_REL: W_CSEL2_DISCARDED_PLAN, LIFECYCLE_REL: CSEL_LIFECYCLE_GOOD})
    expect(any("C-SEL-2" in x for x in w3),
           "W3: `entryId.also { plan.effectiveEntryId }` produced no C-SEL-2 violation (%r)" % (w3,))

    w4 = check_binding_writes(
        {DEBUG_DIR_REL + "/HeadlessChatRunner.kt": W_CSEL4_DEAD_GUARD})
    expect(any("short-circuits the Use test with a constant" in x for x in w4),
           "W4: `if (false && … !is …Use)` produced no C-SEL-4 violation (%r)" % (w4,))

    print("selftest witness W1..W4      -> %d/%d/%d/%d violation(s)"
          % (len(w1), len(w2), len(w3), len(w4)))

    if failures or not all(factory_results):
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    # ── [batch6-t08] witness arms 03-4 / 03-5 / 03-6 / 03-7 ────────────────
    w5 = check_group_selection(
        {ROUTING_REL: W_CSEL1_TAUTOLOGY, LIFECYCLE_REL: CSEL_LIFECYCLE_GOOD})
    expect(any("C-SEL-1" in x for x in w5),
           "W5: `resolved || !resolved` produced no C-SEL-1 violation (%r)" % (w5,))

    w6 = check_group_selection(
        {ROUTING_REL: W_CSEL2_LET_REQUESTED, LIFECYCLE_REL: CSEL_LIFECYCLE_GOOD})
    expect(any("C-SEL-2" in x for x in w6),
           "W6: `plan.effectiveEntryId.let { entryId }` produced no C-SEL-2 "
           "violation (%r)" % (w6,))

    w7 = check_binding_writes({DEBUG_DIR_REL + "/HeadlessChatRunner.kt":
                               W_CSEL4_DERIVED_PICK})
    expect(any("by POSITION" in x for x in w7),
           "W7: `members.minByOrNull { 0 }` produced no positional-pick "
           "violation (%r)" % (w7,))

    w8, _w8_sites = check_factory_models(W_FACTORY_MASKED_LINE)
    expect(len(w8) == 1 and "字符串感知词法" in w8[0],
           "W8: an ENRICHED create site sharing a line with a `//`-in-string "
           "produced %r (expected exactly the mask-dependency finding)" % (w8,))
    expect(_w8_sites == 9, "W8 site count drifted: %r (want 9)" % (_w8_sites,))

    print("selftest witness W5..W8      -> %d/%d/%d/%d violation(s)"
          % (len(w5), len(w6), len(w7), len(w8)))

    print("SESSION_PROVIDER_ENABLED_SELFTEST=PASS (24 arms)")
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
