#!/usr/bin/env python3
"""envvar_config_group_check — [da1025-E] EnvVarEntry.group unified config-face registration.

Fixes the 6th instance of the field-evap recurrence class (GH#68 -> pinned ->
1021 reasoning -> 1022 OpenMinis compat -> 1023-13 -> 1025): EnvVarEntry gained
a `group` field wired through UI / persistence / backup, but the minis-config
surface was never registered:

  1. EnvVarsCollection.fields(forId) must return groupField(forId)
     (path "envvars.<key>.group") — else `minis-config get envvars.<key>.group`
     -> ConfigError.UnknownPath.
  2. That groupField must actually register path "envvars.$key.group"
     (or the forId variant) — a field listed but pathless is still unreachable.
  3. ConfigBuiltins' `envvars` aggregate summary linkedMapOf must contain a
     "group" key — else `minis-config get envvars` hides the grouping label.

[da1026-H / ISSUE-08] The same recurrence class, one surface over — the two
per-group session defaults were write-only on the `groups` face:
  4. GroupsCollection.add must funnel the CLI `context_limit_tokens` through
     `applyContextLimitWrite` — the exact helper the `contextLimitField`
     writer uses. Without it a CLI-created group keeps `lastContextLimitTokens`
     null, so the first clearing write (CLI 0 / the UI switch) leaves nothing
     for the group screen's OFF->ON toggle to restore and the limit silently
     snaps back to the 128K default.
  5. ConfigBuiltins' `groups` aggregate summary block must expose
     "default_thinking_level" and "context_limit_tokens" — else
     `minis-config get groups` hides both, i.e. an agent can set them at add
     time but never read them back.
  6. That same block must keep its five legacy keys (extraction sanity).
  7. The sibling `providers` summary must keep its "customUserAgent" key
     ([fix/da1020]) — guards against editing the wrong block in that file.

All criteria are fail-closed: if the anchor function/block cannot be extracted
from the (comment-stripped) source, the criterion is RED, never silently
skipped.

[da1028-B / ISSUE-03] Four more criteria pin the two halves of the same
field-evap class on the *backup scope* face:
  8.  ConfigBackup.BACKED_UP_SCOPES must contain "envvars" — else
      `envvars.privacyMode` is dropped from every backup AND refused on the way
      back in ("outside backup scope"), so migrating devices lose the privacy
      switch silently.
  9.  ConfigBuiltins.registerBackground must register path
      "background.overlay" — the same switch exists in Settings → Background
      but `minis-config set background.overlay …` answered "unknown field".
  10. That registration must reference BackgroundSettingsRepository's
      KEY_BG_OVERLAY_ENABLED / DEFAULT_BG_OVERLAY_ENABLED constants and must NOT
      re-spell the raw key literal (the F-238/F-239 single-source rule).
  11. Reverse anchors: the eight legacy scopes are still in BACKED_UP_SCOPES
      and `envvars.privacyMode` is still registered — guards a "fix" that
      swaps something out instead of adding.

Criteria 8–11 only run when the caller supplies a ConfigBackup.kt path; the
legacy mini-tree fixtures (which hold only the three config files) leave the
parameter out and are judged by 1–7 exactly as before.

Usage:
    python3 envvar_config_group_check.py <repo_root>   # real-tree mode
    python3 envvar_config_group_check.py --selftest    # fixture mode

Selftest ends with the tooth marker ENVVAR_GROUP_SELFTEST=PASS (missing marker
= the gate itself is broken / gutted -> red).
"""

import os
import re
import shutil
import sys  # [dv20-a1 A-02] fixture re-anchor
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError  # noqa: E402,F401
from _kotlin_ast import (  # noqa: E402
    const_bool, enclosing_conditions, function_body, if_blocks, match_bracket,
    split_top, strip_parens, tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args, contains_ident, expr_end, guard_feasible, literal_view,
    path_feasible, peel_discarding, value_sources,
)

# --------------------------------------------------------------------------
# Comment stripping (a gate's own KDoc routinely quotes its anchors verbatim;
# match against comment-stripped text only).
# --------------------------------------------------------------------------






ENVVARS_REL = "EnvVarsCollection.kt"
BUILTINS_REL = "ConfigBuiltins.kt"
GROUPS_REL = "GroupsCollection.kt"
BACKUP_REL = "ConfigBackup.kt"

FIELDS_FIELD = "groupField"
LIST_BUILDER_RE = re.compile(r"\b(?:listOf|mutableListOf|arrayListOf|buildList)\s*\(")
RETURN_RE = re.compile(r"\breturn\b")

def _registers_field(expr, ident):
    """[expr] 是否把 [ident] 作为某个列表构造的顶层元素登记。

    [batch5-t11] 元素位置才是"登记"，出现位置不是：把 groupField 挪进
    `.also { }` 让列表构造仍与它同处一个表达式，`listOf(...)` 的元素其实没变，
    字段照样从配置面蒸发。
    """
    for m in LIST_BUILDER_RE.finditer(expr):
        open_idx = expr.find("(", m.start())
        for s, e in call_args(expr, open_idx):
            piece = expr[s:e]
            if any(contains_ident(v, ident) for v in value_sources(piece)):
                return True
    return False

def _return_sites(body):
    """[(pos, expr)] —— body 里每个 `return` 的位置与返回表达式。"""
    out = []
    for m in RETURN_RE.finditer(body):
        pos = m.start()
        out.append((pos, body[m.end():expr_end(body, m.end())].strip()))
    return out

def _return_guard(body, pos):
    """(guarded, constant_only) —— 这个 return 被"可能不走"的条件挡着吗。

    guarded=False 表示无条件返回（它会把后面的路径全部短路）；constant_only
    True 表示唯一的挡板是常量（`if (true)`），等于没挡。
    """
    conds = enclosing_conditions(body, pos)
    elvis = re.search(r"\?:$", body[:pos].rstrip()) is not None
    constant = bool(conds) and all(const_bool(c) is not None for _k, c in conds)
    return (bool(conds) or elvis), (constant and not elvis)


def check_fields_registration(envvars_src: str) -> str | None:
    """Criterion 1: **每一条可达 return** 都要把 groupField(forId) 登记进列表。

    [batch5-t11] 旧判据只问"fields 体内出现过 groupField(forId)"：把注册挪到
    `listOf(…).also { groupField(forId) }`（列表元素不含它）依旧绿；再加一条
    常量守卫 `if (true) return emptyList<ConfigField>().also { … }` 把真正的
    注册整条短路，也依旧绿——字段在两个形状里都从配置面蒸发。
    只有"未知 key 提前返回空表"这种**被 elvis/条件挡着**的返回才允许不登记。
    """
    text = literal_view(envvars_src)
    body = fn_body(text, "fields")
    if body is None:
        return "C1 EXTRACT_FAIL: fields(forId) body not found (fail-closed)"
    sites = [s for s in _return_sites(body) if path_feasible(body, s[0])]
    if not sites:
        return (
            "C1 RED: fields(forId) has no reachable `return` — the config face has no "
            "field list at all (fail-closed)"
        )
    if not any(_registers_field(expr, FIELDS_FIELD) for _p, expr in sites):
        return (
            "C1 RED: fields(forId) return list does not register groupField(forId) as a "
            "list element — `minis-config get envvars.<key>.group` answers UnknownPath"
        )
    for pos, expr in sites:
        if _registers_field(expr, FIELDS_FIELD):
            continue
        guarded, constant_only = _return_guard(body, pos)
        if guarded and not constant_only:
            continue
        line = text[:pos].count("\n") + 1
        return (
            "C1 RED: fields(forId) can return `%s` (line %d) without registering "
            "groupField(forId) — the registry entry is produced only on a path that "
            "never survives (an unconditional or constant-guarded bail-out), so the "
            "field still evaporates from the config face" % (expr[:60], line)
        )
    return None

def block_at(text, open_idx):
    """[open_idx] 处的 `{…}` 块；配不平返回 None（fail-closed）。"""
    close = match_bracket(text, open_idx)
    return None if close < 0 else text[open_idx:close + 1]

def paren_block(text, open_idx):
    """[open_idx] 处的 `(…)` 块；配不平返回 None（fail-closed）。"""
    close = match_bracket(text, open_idx)
    return None if close < 0 else text[open_idx:close + 1]

def fn_body(text, name):
    """函数体文本（块体或表达式体都由共享 `function_body` 派生）。"""
    span = function_body(text, name)
    if span is None:
        return None
    start, end, _kind = span
    return text[start:end]

def enclosing_block(text, pos, limit=40):
    """包含 [pos] 的最内层 `{…}` 内容（配平交给共享 match_bracket）。"""
    opens = [m.start() for m in re.finditer(r"\{", text[:pos])][-limit:]
    for i in reversed(opens):
        close = match_bracket(text, i)
        if close > pos:
            return text[i + 1:close]
    return None

def constant_predicate(pred):
    """谓词是否恒为常量（恒假恒真都算"没在判定"）。

    [batch5-t11] 替换原来的两条死分支正则白名单（`false &&` 家族 + `x || !x`）：
    常量折叠走共享 `const_bool`（`false && x` / `(1 == 2) && x` / `!true` 全覆盖），
    互补对在顶层切开后按规范化文本比较。判"能不能为真"，不判"长什么样"。
    """
    e = strip_parens(pred or "")
    if not e:
        return False
    if const_bool(e) is not None:
        return True
    for op in ("&&", "||"):
        parts = split_top(e, (op,))
        if len(parts) != 2:
            continue
        a = re.sub(r"\s+", "", e[parts[0][0]:parts[0][1]])
        b = re.sub(r"\s+", "", e[parts[1][0]:parts[1][1]])
        if a and b and (a == "!" + b or b == "!" + a):
            return True
    return False
def check_group_path(envvars_src: str) -> str | None:
    """Criterion 2: groupField registers path "envvars.$key.group" AND its reader
    YIELDS the stored group on a path it can actually return."""
    text = literal_view(envvars_src)
    body = fn_body(text, "groupField")
    if body is None:
        return "C2 EXTRACT_FAIL: groupField member not found (fail-closed)"
    if not re.search(r'path\s*=\s*"envvars\.\$(key|forId)\.group"', body):
        return 'C2 RED: groupField does not register path "envvars.$key.group" (or forId variant)'
    m = re.search(r"\breader\s*=\s*\{", body)
    if not m:
        return (
            "C2 EXTRACT_FAIL: groupField's `reader = { … }` lambda not found "
            "(fail-closed: the criterion pins what the reader yields)"
        )
    block = block_at(body, m.end() - 1)
    if block is None:
        return "C2 EXTRACT_FAIL: groupField's reader lambda never balances (fail-closed)"
    inner = block[1:-1]
    tail = re.sub(r"^return\b", "", tail_expr(inner)).strip()
    # [batch5-t11] 值必须来自 Str(<entry>.group)：`ConfigValue.Null.also {
    # ConfigValue.Str(e.group) }` 保留了全部字面量，返回的却是接收者（恒 Null）。
    sources = value_sources(tail)
    if not any("ConfigValue.Str(" in v and re.search(r"\bgroup\b", v) for v in sources):
        return (
            "C2 RED: groupField's reader never yields ConfigValue.Str(<entry>.group) on "
            "any path it can return — the group set through the writer (UI / `set`) is "
            "invisible on the single-path read `minis-config get envvars.<key>.group`"
        )
    if "repo." not in inner:
        return (
            "C2 RED: groupField's reader does not read the repository — the group must "
            "come from the entry (single source), not from a literal"
        )
    return None

def check_summary_group(builtins_src: str) -> str | None:
    """Criterion 3: the `envvars` aggregate summary block contains a "group" key."""
    text = literal_view(builtins_src)
    anchor = re.search(r'path\s*=\s*"envvars",', text)
    if not anchor:
        return "C3 EXTRACT_FAIL: envvars summary registration (path = \"envvars\",) not found (fail-closed)"
    # the enclosing r.register( ... ) block: back up to the nearest r.register(
    # opener before the anchor, then balance parens from there.
    reg_start = text.rfind("r.register(", 0, anchor.start())
    if reg_start < 0:
        return "C3 EXTRACT_FAIL: enclosing r.register( not found (fail-closed)"
    paren_idx = text.index("(", reg_start)
    block = paren_block(text, paren_idx)
    if block is None:
        return "C3 EXTRACT_FAIL: envvars summary block never balances (fail-closed)"
    if '"group" to ConfigValue.Str' not in block:
        return "C3 RED: envvars summary linkedMapOf lacks a \"group\" key"
    if '"key" to ConfigValue.Str' not in block:
        return "C3 RED: envvars summary block lost its \"key\" entry (extraction sanity)"
    return None


def registered_block(text: str, anchor_re: str) -> str | None:
    """The enclosing `r.register( ... )` block of the first `anchor_re` match.
    Speculative-free: returns None (fail-closed) when the anchor is absent, no
    `r.register(` precedes it, or the parenthesis block never balances."""
    anchor = re.search(anchor_re, text)
    if not anchor:
        return None
    reg_start = text.rfind("r.register(", 0, anchor.start())
    if reg_start < 0:
        return None
    paren_idx = text.index("(", reg_start)
    return paren_block(text, paren_idx)


def check_add_calls_context_limit_helper(groups_src: str) -> str | None:
    """Criterion 4: GroupsCollection.add funnels the CLI context limit through
    applyContextLimitWrite — with the PARSED value as the argument."""
    text = literal_view(groups_src)
    body = fn_body(text, "add")
    if body is None:
        return "C4 EXTRACT_FAIL: GroupsCollection.add body not found (fail-closed)"
    calls = list(re.finditer(r"applyContextLimitWrite\s*\(", body))
    if not calls:
        return (
            "C4 RED: GroupsCollection.add never calls applyContextLimitWrite — "
            "a CLI-created group loses its limit on the first clearing write"
        )
    # [batch5-t11] 调用在 ≠ 值在。`applyContextLimitWrite(g, 0.also { contextLimit })`
    # 让参数位置仍能匹配到标识符，真实求值却是 0：辅助函数收到"无限制"，
    # 组创建时给的 limit 静默丢失。
    parsed = re.search(
        r'val\s+(\w+)\s*=\s*\(obj\["context_limit_tokens"\]\s*as\?\s*ConfigValue\.Int\)',
        body)
    if parsed is None:
        return (
            'C4 EXTRACT_FAIL: add() no longer derives the limit from '
            'obj["context_limit_tokens"] (fail-closed: re-derive this criterion, '
            "never drop it)"
        )
    ident = parsed.group(1)
    for m in calls:
        args = call_args(body, m.end() - 1)
        if not args:
            return "C4 EXTRACT_FAIL: applyContextLimitWrite( has no readable arguments (fail-closed)"
        sources = []
        for s, e in args:
            sources.extend(value_sources(body[s:e]))
        if not any(contains_ident(v, ident) for v in sources):
            return (
                "C4 RED: applyContextLimitWrite is called with a limit that is not the "
                "parsed context_limit_tokens value — a CLI-created group silently loses "
                "the limit it was created with"
            )
    return None

def _summary_carrier(block: str) -> str | None:
    """summary 里逐组载体的标识符：从 `"name" to ConfigValue.Str(<ident>.name)` 派生。"""
    m = re.search(r'"name"\s+to\s+[\s\S]{0,80}?\b([A-Za-z_]\w*)\.name\b', block)
    return m.group(1) if m else None

def _entry_value(block: str, key: str) -> str | None:
    """summary 里 `"<key>" to <expr>` 的 <expr> 文本（按表达式边界取，不按行）。

    [batch5-t11] 行尾的逗号是 map 条目分隔符，会让共享 `expr_end` 判成续行、
    把后面几个 key 一起卷进来（那样任意一个兄弟键带载体就会让本键蒙混过关）。
    先按顶层逗号切，再取第一段。
    """
    m = re.search(r'"%s"\s+to\s+' % re.escape(key), block)
    if not m:
        return None
    start = m.end()
    raw = block[start:expr_end(block, start)]
    parts = split_top(raw, (",",))
    if not parts:
        return None
    return raw[parts[0][0]:parts[0][1]].strip()

def check_groups_summary_keys(builtins_src: str) -> str | None:
    """Criterion 5: the `groups` aggregate summary block exposes both per-group
    session defaults **with the group's value behind them**."""
    text = literal_view(builtins_src)
    block = registered_block(text, r'path\s*=\s*"groups",')
    if block is None:
        return 'C5 EXTRACT_FAIL: groups summary registration (path = "groups",) not found (fail-closed)'
    carrier = _summary_carrier(block)
    if carrier is None:
        return (
            "C5 EXTRACT_FAIL: the groups summary block names no per-group carrier "
            "(no `<ident>.<field>` value) — re-derive this criterion (fail-closed)"
        )
    for key, what in (("default_thinking_level", "defaultThinkingLevel"),
                      ("context_limit_tokens", "contextLimitTokens")):
        if '"%s" to' % key not in block:
            return 'C5 RED: groups summary block lacks a "%s" key' % key
        # [batch5-t11] 键在 ≠ 值在：`"default_thinking_level" to ConfigValue.Null`
        # 把键钉成常量，agent 设了也读不回。脱敏后的值必须真的来自该组的字段
        # （剥掉 also/apply 这类丢弃型作用域后再看，别被 lambda 里的引用骗过）。
        expr = _entry_value(block, key)
        if expr is None:
            return 'C5 EXTRACT_FAIL: the value of "%s" cannot be read (fail-closed)' % key
        if not contains_ident(peel_discarding(expr), carrier):
            return (
                "C5 RED: the groups summary's \"%s\" is not derived from the group's own "
                "value — `minis-config get groups` keeps answering no-override after an "
                "agent sets it, so %s never reaches the summary" % (key, what)
            )
    return None

def check_groups_summary_legacy_keys(builtins_src: str) -> str | None:
    """Criterion 6: the `groups` summary block still carries its five legacy
    keys — an extraction sanity check that also catches a block rewrite that
    drops them."""
    text = literal_view(builtins_src)
    block = registered_block(text, r'path\s*=\s*"groups",')
    if block is None:
        return 'C6 EXTRACT_FAIL: groups summary registration (path = "groups",) not found (fail-closed)'
    for key in ('"id" to', '"name" to', '"strategy" to', '"fallback_strategy" to', '"entries" to'):
        if key not in block:
            return f"C6 RED: groups summary block lost its {key} key"
    return None


def check_providers_summary_ua_key(builtins_src: str) -> str | None:
    """Criterion 7 (wrong-block guard, [fix/da1020]): the sibling `providers`
    summary must keep its customUserAgent key while the `groups` block above it
    is edited."""
    text = literal_view(builtins_src)
    block = registered_block(text, r'path\s*=\s*"providers",')
    if block is None:
        return 'C7 EXTRACT_FAIL: providers summary registration (path = "providers",) not found (fail-closed)'
    if '"customUserAgent" to' not in block:
        return "C7 RED: providers summary block lost its customUserAgent key"
    return None

# --------------------------------------------------------------------------
# [da1028-B / ISSUE-03] Criteria 8-11 — the backup-scope face of the same
# field-evap class.
# --------------------------------------------------------------------------

LEGACY_BACKED_UP_SCOPES = (
    "appearance", "chat", "background", "defaults",
    "soul", "memory", "logs", "runtime",
)

SCOPE_SET_RE = re.compile(r"val\s+BACKED_UP_SCOPES\s*=\s*setOf\(")

def backed_up_scopes(backup_src: str) -> set[str] | None:
    """BACKED_UP_SCOPES 的**有效**集合（setOf 之后套的 `-`/`+` 都在求值范围内）。

    [batch5-t11] 旧判据只取 `setOf(…)` 的括号内容：`setOf(…, "envvars") - "envvars"`
    里 `"envvars"` 照样命中，集合实际不含它，备份范围静默缩回、隐私开关既不备份
    也不允许恢复。这里的常量集合运算按求值语义做，None 表示锚点不在（fail-closed）。
    """
    text = literal_view(backup_src)
    m = SCOPE_SET_RE.search(text)
    if not m:
        return None
    open_idx = m.end() - 1
    close = match_bracket(text, open_idx)
    if close < 0:
        return None
    cur = set(re.findall(r'"([^"]*)"', text[open_idx + 1:close]))
    i = close + 1
    while True:
        op = re.match(r"\s*([-+])\s*", text[i:])
        if not op:
            break
        sign = op.group(1)
        i += op.end()
        m2 = re.match(r"setOf\s*\(", text[i:])
        if m2:
            o = i + m2.end() - 1
            c = match_bracket(text, o)
            if c < 0:
                return None
            vals = set(re.findall(r'"([^"]*)"', text[o + 1:c]))
            i = c + 1
        else:
            m3 = re.match(r'"([^"]*)"', text[i:])
            if not m3:
                return None
            vals = {m3.group(1)}
            i += m3.end()
        cur = cur - vals if sign == "-" else cur | vals
    return cur

def check_backup_scope_envvars(backup_src: str) -> str | None:
    """Criterion 8: BACKED_UP_SCOPES carries "envvars" **in the evaluated set**."""
    scopes = backed_up_scopes(backup_src)
    if scopes is None:
        return "C8 EXTRACT_FAIL: ConfigBackup.BACKED_UP_SCOPES setOf(…) not found (fail-closed)"
    if "envvars" not in scopes:
        return (
            'C8 RED: BACKED_UP_SCOPES lacks "envvars" — envvars.privacyMode is '
            "dropped from every backup and refused on restore (\"outside backup scope\")"
        )
    return None

def check_overlay_registration(builtins_src: str) -> str | None:
    """Criterion 9: registerBackground registers path "background.overlay"."""
    text = literal_view(builtins_src)
    body = fn_body(text, "registerBackground")
    if body is None:
        return (
            "C9 EXTRACT_FAIL: ConfigBuiltins.registerBackground(r: ConfigRegistry, context: Context) "
            "body not found (fail-closed)"
        )
    if 'path = "background.overlay"' not in body:
        return (
            'C9 RED: registerBackground does not register path "background.overlay" — '
            'the Settings → Background overlay switch is unreachable via minis-config '
            '("unknown field") and is in no backup scope'
        )
    return None

def check_overlay_single_source(builtins_src: str) -> str | None:
    """Criterion 10: the overlay registration references the repository's key +
    default constants and does not re-spell the raw key (F-238/F-239 rule: the
    registry and BackgroundSettingsRepository must name the key identically)."""
    text = literal_view(builtins_src)
    body = fn_body(text, "registerBackground")
    if body is None:
        return (
            "C10 EXTRACT_FAIL: ConfigBuiltins.registerBackground(r: ConfigRegistry, context: Context) "
            "body not found (fail-closed)"
        )
    for const in ("KEY_BG_OVERLAY_ENABLED", "DEFAULT_BG_OVERLAY_ENABLED"):
        if const not in body:
            return (
                f"C10 RED: registerBackground does not reference "
                f"BackgroundSettingsRepository.{const} — a re-typed key/default "
                f"drifts out of that companion silently"
            )
    if '"backgroundOverlayEnabled"' in body:
        return (
            'C10 RED: registerBackground spells the raw key literal '
            '"backgroundOverlayEnabled" instead of the repository constant'
        )
    return None

def check_reverse_anchors(backup_src: str, builtins_src: str) -> str | None:
    """Criterion 11 (reverse anchors): the eight legacy scopes are still in
    BACKED_UP_SCOPES, and `envvars.privacyMode` is still a registered field —
    a fix that swaps one out for the other is not a fix."""
    scopes = backed_up_scopes(backup_src)
    if scopes is None:
        return "C11 EXTRACT_FAIL: ConfigBackup.BACKED_UP_SCOPES setOf(…) not found (fail-closed)"
    for scope in LEGACY_BACKED_UP_SCOPES:
        if scope not in scopes:
            return f'C11 RED: BACKED_UP_SCOPES lost legacy scope "{scope}"'
    if not re.search(r'path\s*=\s*"envvars\.privacyMode"', literal_view(builtins_src)):
        return 'C11 RED: path = "envvars.privacyMode" is no longer registered in ConfigBuiltins'
    return None


def check_sentinel_remap(backup_src: str) -> str | None:
    """Criterion 12 [dv20-a1 A-02]: the Stage 2 group-member remap keeps the
    virtual System voice ids instead of counting them as dropped members."""
    text = literal_view(backup_src)
    drops = [m.start() for m in re.finditer(r"else\s+droppedMembers\+\+", text)]
    if not drops:
        return (
            "C12 EXTRACT_FAIL: ConfigBackup group-member remap "
            "(`else droppedMembers++`) not found (fail-closed)"
        )
    for pos in drops:
        window = text[max(0, pos - 400):pos]
        if "SystemVoiceEntries.isSystemEntryId" not in window:
            return (
                "C12 RED: the member remap drops a member whenever the entry map has "
                "no mapping — the virtual System voice ids are never in that map, so "
                "every voice-group member counts as a dropped member; the remap must "
                "consult SystemVoiceEntries.isSystemEntryId (single source)"
            )
        # [batch5-t11] …and the predicate must DECIDE. 旧的死分支判定是两条正则
        # 白名单（`false &&` / `x || !x`）：`(1 == 2) && isSystemEntryId(it)`
        # 一句话穿透。现在走共享常量折叠 + 顶层互补对，判"能不能为真"，
        # 并同时看 if/when 条件与所在 lambda 体两个通道。
        sites = [(kind, cond) for call in re.finditer(
            r"SystemVoiceEntries\.isSystemEntryId\s*\(", window)
            for kind, cond in enclosing_conditions(window, call.start())]
        sites += [("lambda", blk) for call in re.finditer(
            r"SystemVoiceEntries\.isSystemEntryId\s*\(", window)
            for blk in [enclosing_block(window, call.start())] if blk]
        for kind, cond in sites:
            if "SystemVoiceEntries.isSystemEntryId" not in cond:
                continue
            if not constant_predicate(cond):
                continue
            return (
                "C12 RED: the remap's isSystemEntryId predicate is constant "
                "(`false && …` / `(1 == 2) && …` / `… || true` / `x || !x`, seen in the "
                "enclosing %s) — it keeps the call and decides nothing, so every virtual "
                "System voice member is still counted as dropped" % kind
            )
    if "__builtin_system_speech__" in backup_src:
        return (
            "C12 RED: ConfigBackup spells the System sentinel id literal instead of "
            "going through SystemVoiceEntries — the id must have one home"
        )
    return None

def check_group_member_resolution(builtins_src: str) -> str | None:
    """Criterion 13 [dv20-a1 A-02]: the `groups` config summary resolves the
    virtual System voice entries instead of emitting a "missing" stub."""
    text = literal_view(builtins_src)
    if "SystemVoiceEntries.resolve(" not in text:
        return (
            "C13 RED: the `groups` config summary never calls "
            "SystemVoiceEntries.resolve — a virtual System voice member is rendered "
            "as a missing model row even though chat resolves it fine"
        )
    # [wide-1024 12-misc-b] Calling it is not resolving the member: the VALUE has
    # to be consumed. `entriesById[id].also { SystemVoiceEntries.resolve(id) }`
    # keeps the anchor token, throws the resolved entry away (the receiver is
    # what flows on) and still renders the member as missing. Two legitimate
    # shapes exist and both are accepted — an Elvis fallback (`a[id] ?: resolve(id)`)
    # and a chain on the result (`ConfigValue.Str(resolve(id)?.id ?: id)`).
    discarded = []
    for call in re.finditer(r"SystemVoiceEntries\.resolve\s*\(", text):
        head = text[: call.start()].rstrip()
        close = paren_block(text, call.end() - 1)
        if close is None:
            return (
                "C13 EXTRACT_FAIL: SystemVoiceEntries.resolve( never balances "
                "(fail-closed)"
            )
        tail = text[call.end() - 1 + len(close):].lstrip()
        consumed_in = head.endswith(("=", "?:", "(", ",", "return", "->"))
        consumed_out = tail[:1] in {"?", "!", ".", ",", ")"} or tail[:2] == "!!"
        if not (consumed_in or consumed_out):
            line = text[: call.start()].count("\n") + 1
            discarded.append(line)
    if discarded:
        return (
            "C13 RED: SystemVoiceEntries.resolve is called as a discarded statement "
            "(ConfigBuiltins.kt:%s) — the resolved virtual entry never reaches the "
            "member row, so a System voice member is still rendered as a missing model"
            % ", ".join(str(n) for n in discarded)
        )
    if "__builtin_system_speech__" in builtins_src:
        return (
            "C13 RED: ConfigBuiltins spells the System sentinel id literal instead of "
            "going through SystemVoiceEntries — the id must have one home"
        )
    return None


def check(envvars_path: str, builtins_path: str, groups_path: str,
          backup_path: str | None = None) -> list[str]:
    with open(envvars_path, encoding="utf-8") as f:
        ev = f.read()
    with open(builtins_path, encoding="utf-8") as f:
        cb = f.read()
    with open(groups_path, encoding="utf-8") as f:
        gr = f.read()
    failures = []
    for name, fn, src in (
        ("C1", check_fields_registration, ev),
        ("C2", check_group_path, ev),
        ("C3", check_summary_group, cb),
        ("C4", check_add_calls_context_limit_helper, gr),
        ("C5", check_groups_summary_keys, cb),
        ("C6", check_groups_summary_legacy_keys, cb),
        ("C7", check_providers_summary_ua_key, cb),
    ):
        try:
            r = fn(src)
        except KotlinLexError as exc:
            # [batch5-t11] 共享词法器解析失败 => 该源上的判据一律不可判 = 违规。
            r = "%s LEX_FAIL: %s (fail-closed: the source cannot be lexed)" % (name, exc)
        if r is not None:
            failures.append(r)

    # Criteria 8-11 (da1028-B). Judged only when the caller hands us the backup
    # source: the three-file mini fixtures predate this arm and keep their
    # one-criterion-per-fixture shape.
    if backup_path is not None:
        with open(backup_path, encoding="utf-8") as f:
            bk = f.read()
        for name, fn in (
            ("C8", lambda: check_backup_scope_envvars(bk)),
            ("C9", lambda: check_overlay_registration(cb)),
            ("C10", lambda: check_overlay_single_source(cb)),
            ("C11", lambda: check_reverse_anchors(bk, cb)),
            ("C12", lambda: check_sentinel_remap(bk)),
            ("C13", lambda: check_group_member_resolution(cb)),
        ):
            try:
                r = fn()
            except KotlinLexError as exc:
                r = "%s LEX_FAIL: %s (fail-closed: the source cannot be lexed)" % (name, exc)
            if r is not None:
                failures.append(r)
    return failures


def locate(root: str) -> tuple[str, str, str, str]:
    """Locate the four production files under repo_root. Fail-closed."""
    ev = cb = gr = bk = None
    for dirpath, _dirnames, filenames in os.walk(root):
        if "build" in dirpath.split(os.sep) or "test" in dirpath.split(os.sep):
            continue
        for fn in filenames:
            if fn == ENVVARS_REL and ev is None:
                ev = os.path.join(dirpath, fn)
            elif fn == BUILTINS_REL and cb is None and "config" in dirpath.split(os.sep):
                cb = os.path.join(dirpath, fn)
            elif fn == GROUPS_REL and gr is None and "config" in dirpath.split(os.sep):
                gr = os.path.join(dirpath, fn)
            elif fn == BACKUP_REL and bk is None and "backup" in dirpath.split(os.sep):
                bk = os.path.join(dirpath, fn)
    if ev is None:
        raise SystemExit("LOCATE_FAIL: EnvVarsCollection.kt not found under " + root)
    if cb is None:
        raise SystemExit("LOCATE_FAIL: ConfigBuiltins.kt not found under " + root)
    if gr is None:
        raise SystemExit("LOCATE_FAIL: GroupsCollection.kt not found under " + root)
    if bk is None:
        raise SystemExit("LOCATE_FAIL: ConfigBackup.kt not found under " + root)
    return ev, cb, gr, bk


# --------------------------------------------------------------------------
# Selftest fixtures. Each fixture is a minimal synthetic source pair crafted to
# have exactly one criterion broken (the "precisely broken by it" requirement).
# --------------------------------------------------------------------------

FIXTURE_ENVVARS_OK = '''package fake

class EnvVarsCollection(private val repo: Repo) : ConfigCollection {
    override fun fields(forId: String): List<ConfigField> {
        val entry = repo.entries.value.firstOrNull { it.key == forId } ?: return emptyList()
        return listOf(
            createdAtField(forId),
            noteField(forId),
            groupField(forId),
            HiddenField(
                path = "envvars.$forId.value",
                displayName = "Value",
            ),
        )
    }

    private fun createdAtField(key: String): ConfigField = ReadOnlyField(
        path = "envvars.$key.createdAt",
        displayName = "Created at",
        valueSchema = ConfigSchema.Str(),
        reader = { ConfigValue.Null },
    )

    private fun noteField(key: String): ConfigField = ClosureField(
        path = "envvars.$key.note",
        displayName = "Note",
        valueSchema = ConfigSchema.Str(maxLength = 500),
        reader = { ConfigValue.Null },
        writer = { v ->
            val e = repo.entries.value.firstOrNull { it.key == key }
                ?: throw ConfigError.UnknownPath("envvars.$key")
            val currentValue = repo.getValue(e.key) ?: ""
            repo.update(e.id, e.key, currentValue, v, e.group)
        },
    )

    private fun groupField(key: String): ConfigField = ClosureField(
        path = "envvars.$key.group",
        displayName = "Group",
        valueSchema = ConfigSchema.Str(maxLength = 200),
        reader = {
            val e = repo.entries.value.firstOrNull { it.key == key }
            if (e == null) ConfigValue.Null else ConfigValue.Str(e.group)
        },
        writer = { v ->
            val e = repo.entries.value.firstOrNull { it.key == key }
                ?: throw ConfigError.UnknownPath("envvars.$key")
            val currentValue = repo.getValue(e.key) ?: ""
            repo.update(e.id, e.key, currentValue, e.note, v)
        },
    )
}
'''

FIXTURE_GROUPS_OK = """package fake

class GroupsCollection(private val repo: Repo) : ConfigCollection {
    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")
        val contextLimit = (obj["context_limit_tokens"] as? ConfigValue.Int)?.value
            ?.takeIf { it > 0 }
        val g = ModelGroup(name = "g", contextLimitTokens = contextLimit)
        applyContextLimitWrite(g, contextLimit ?: 0)
        repo.addGroup(g)
        return g.id
    }

    private fun contextLimitField(id: String): ConfigField = ClosureField(
        path = "groups.$id.contextLimitTokens",
        displayName = "Context limit (tokens)",
        reader = { ConfigValue.Null },
        writer = { v ->
            val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
            mutate(id) { applyContextLimitWrite(it, i) }
        },
    )
}
"""

FIXTURE_BUILTINS_OK = '''package fake

object ConfigBuiltins {
    fun register(r: R) {
        r.register(
            ReadOnlyField(
                path = "envvars",
                displayName = "Environment variables (summary)",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        envVarRepo.entries.value.map { e ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "key" to ConfigValue.Str(e.key),
                                    "group" to ConfigValue.Str(e.group),
                                    "note" to ConfigValue.Str(e.note),
                                    "created_at" to ConfigValue.Str(isoFormatter.format(java.time.Instant.ofEpochMilli(e.createdAt))),
                                )
                            )
                        }
                    )
                },
            )
        )

        r.register(
            ReadOnlyField(
                path = "providers",
                displayName = "LLM provider instances (summary)",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        providerRepo.entries.value.map { inst ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(inst.id),
                                    "label" to ConfigValue.Str(inst.label),
                                    "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                )
                            )
                        }
                    )
                },
            )
        )

        r.register(
            ReadOnlyField(
                path = "groups",
                displayName = "Model groups (summary)",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        groupRepo.groups.value.map { g ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(g.id),
                                    "name" to ConfigValue.Str(g.name),
                                    "strategy" to ConfigValue.Str(g.strategy.name),
                                    "fallback_strategy" to ConfigValue.Str(g.fallbackStrategy.name),
                                    "default_thinking_level" to (g.defaultThinkingLevel?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                    "context_limit_tokens" to (g.contextLimitTokens?.let { ConfigValue.Int(it) } ?: ConfigValue.Null),
                                    "entries" to ConfigValue.Arr(g.memberEntryIds.map { memberId ->
                                        ConfigValue.Str(SystemVoiceEntries.resolve(memberId)?.id ?: memberId)
                                    }),
                                )
                            )
                        }
                    )
                },
            )
        )
    }
}
'''


FIXTURE_BACKUP_OK = '''package fake

private fun remapGroupMembers(
    entryIdMap: Map<String, String>,
    srcMembers: List<String>,
    out: MutableList<String>,
): Int {
    var droppedMembers = 0
    for (old in srcMembers) {
        val mapped = entryIdMap[old] ?: old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }
        if (mapped != null) out.add(mapped) else droppedMembers++
    }
    return droppedMembers
}

object ConfigBackup {
    internal val BACKED_UP_SCOPES = setOf(
        "appearance",
        "chat",
        "background",
        "defaults",
        "soul",
        "memory",
        "logs",
        "runtime",
        "envvars",
    )
}
'''

OVERLAY_FN_OK = '''    private fun registerBackground(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "background.overlay",
                displayName = "Background task overlay",
                prefs = prefs,
                key = com.rikkaminis.app.data.repository.BackgroundSettingsRepository
                    .KEY_BG_OVERLAY_ENABLED,
                defaultValue = com.rikkaminis.app.data.repository.BackgroundSettingsRepository
                    .DEFAULT_BG_OVERLAY_ENABLED,
            )
        )
    }

'''

PRIVACY_FN_OK = '''    private fun registerEnvVars(r: ConfigRegistry, context: Context) {
        r.register(
            ClosureField(
                path = "envvars.privacyMode",
                displayName = "Privacy Mode",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(true) },
                writer = { v -> Unit },
            )
        )
    }
'''

def inject_members(src: str, members: str) -> str:
    """Append members just before the object's closing brace (fixture sugar:
    the injected text stays inside `object ConfigBuiltins { … }`)."""
    i = src.rstrip().rfind("}")
    return src[:i] + "\n" + members + src[i:]

def full_fixture(builtins: str, backup: str) -> str:
    """A four-source mini tree: criteria 1-7 run on the config fixtures as
    before, 8-11 additionally judge ConfigBuiltins + ConfigBackup."""
    d = tempfile.mkdtemp(prefix="envvar_group_full_")
    os.makedirs(os.path.join(d, "fake", "config", "collections"))
    os.makedirs(os.path.join(d, "fake", "backup"))
    with open(os.path.join(d, "fake", "config", "collections", "EnvVarsCollection.kt"), "w", encoding="utf-8") as f:
        f.write(FIXTURE_ENVVARS_OK)
    with open(os.path.join(d, "fake", "config", "collections", "GroupsCollection.kt"), "w", encoding="utf-8") as f:
        f.write(FIXTURE_GROUPS_OK)
    with open(os.path.join(d, "fake", "config", "ConfigBuiltins.kt"), "w", encoding="utf-8") as f:
        f.write(builtins)
    with open(os.path.join(d, "fake", "backup", "ConfigBackup.kt"), "w", encoding="utf-8") as f:
        f.write(backup)
    return d

def full_check(d: str) -> list[str]:
    return check(
        os.path.join(d, "fake", "config", "collections", "EnvVarsCollection.kt"),
        os.path.join(d, "fake", "config", "ConfigBuiltins.kt"),
        os.path.join(d, "fake", "config", "collections", "GroupsCollection.kt"),
        os.path.join(d, "fake", "backup", "ConfigBackup.kt"),
    )

def fixture_dir() -> str:
    d = tempfile.mkdtemp(prefix="envvar_group_selftest_")
    os.makedirs(os.path.join(d, "fake", "config", "collections"))
    with open(os.path.join(d, "fake", "config", "collections", "EnvVarsCollection.kt"), "w", encoding="utf-8") as f:
        f.write(FIXTURE_ENVVARS_OK)
    with open(os.path.join(d, "fake", "config", "ConfigBuiltins.kt"), "w", encoding="utf-8") as f:
        f.write(FIXTURE_BUILTINS_OK)
    with open(os.path.join(d, "fake", "config", "collections", "GroupsCollection.kt"), "w", encoding="utf-8") as f:
        f.write(FIXTURE_GROUPS_OK)
    return d


def run_fixtures() -> bool:
    ok = True
    d2 = d3 = d4 = d5 = d6 = d7 = d8 = d9 = None

    def expect(label: str, failures: list[str], want: str | None) -> bool:
        if want is None:
            if failures:
                print(f"[SELFTEST] {label}: expected GREEN, got {failures}")
                return False
            print(f"[SELFTEST] {label}: GREEN as expected")
            return True
        if len(failures) != 1 or not failures[0].startswith(want):
            print(f"[SELFTEST] {label}: expected exactly one failure starting with {want!r}, got {failures}")
            return False
        print(f"[SELFTEST] {label}: red as expected ({failures[0]})")
        return True

    def groups_path(d: str) -> str:
        return os.path.join(d, "fake", "config", "collections", "GroupsCollection.kt")

    # FIX-1: complete fixture -> all green
    d = fixture_dir()
    try:
        ok &= expect(
            "FIX-1 complete",
            check(
                os.path.join(d, "fake", "config", "collections", "EnvVarsCollection.kt"),
                os.path.join(d, "fake", "config", "ConfigBuiltins.kt"),
                groups_path(d),
            ),
            None,
        )

        # FIX-2: fields list drops groupField -> exactly C1 red
        d2 = fixture_dir()
        p = os.path.join(d2, "fake", "config", "collections", "EnvVarsCollection.kt")
        src = open(p, encoding="utf-8").read().replace("            groupField(forId),\n", "")
        open(p, "w", encoding="utf-8").write(src)
        ok &= expect(
            "FIX-2 fields-missing",
            check(
                p,
                os.path.join(d2, "fake", "config", "ConfigBuiltins.kt"),
                groups_path(d2),
            ),
            "C1",
        )

        # FIX-3: groupField present in the list but its path is wrong -> exactly C2 red
        d3 = fixture_dir()
        p3 = os.path.join(d3, "fake", "config", "collections", "EnvVarsCollection.kt")
        src3 = open(p3, encoding="utf-8").read().replace('path = "envvars.$key.group",', 'path = "envvars.$key.tag",')
        open(p3, "w", encoding="utf-8").write(src3)
        ok &= expect(
            "FIX-3 path-missing",
            check(
                p3,
                os.path.join(d3, "fake", "config", "ConfigBuiltins.kt"),
                groups_path(d3),
            ),
            "C2",
        )

        # FIX-4: summary block drops the "group" key -> exactly C3 red
        d4 = fixture_dir()
        p4 = os.path.join(d4, "fake", "config", "ConfigBuiltins.kt")
        src4 = open(p4, encoding="utf-8").read().replace('                                    "group" to ConfigValue.Str(e.group),\n', "")
        open(p4, "w", encoding="utf-8").write(src4)
        ok &= expect(
            "FIX-4 summary-missing",
            check(
                os.path.join(d4, "fake", "config", "collections", "EnvVarsCollection.kt"),
                p4,
                groups_path(d4),
            ),
            "C3",
        )

        # FIX-5 (fail-closed control): truncated EnvVarsCollection (no fields
        # block at all) -> C1 must be EXTRACT_FAIL red, never silently green.
        d5 = fixture_dir()
        p5 = os.path.join(d5, "fake", "config", "collections", "EnvVarsCollection.kt")
        open(p5, "w", encoding="utf-8").write("package fake\n\nclass EnvVarsCollection {\n")
        fails5 = check(p5, os.path.join(d5, "fake", "config", "ConfigBuiltins.kt"), groups_path(d5))
        if not any(f.startswith("C1 EXTRACT_FAIL") for f in fails5):
            print(f"[SELFTEST] FIX-5 truncated: expected C1 EXTRACT_FAIL, got {fails5}")
            ok = False
        else:
            print("[SELFTEST] FIX-5 truncated: fail-closed as expected")

        # FIX-6 [da1026-H]: add drops the context-limit helper call -> exactly C4 red
        d6 = fixture_dir()
        p6 = groups_path(d6)
        src6 = open(p6, encoding="utf-8").read().replace(
            "        applyContextLimitWrite(g, contextLimit ?: 0)\n", ""
        )
        open(p6, "w", encoding="utf-8").write(src6)
        ok &= expect(
            "FIX-6 add-missing-helper",
            check(
                os.path.join(d6, "fake", "config", "collections", "EnvVarsCollection.kt"),
                os.path.join(d6, "fake", "config", "ConfigBuiltins.kt"),
                p6,
            ),
            "C4",
        )

        # FIX-7 [da1026-H]: groups summary drops context_limit_tokens -> exactly C5 red
        d7 = fixture_dir()
        p7 = os.path.join(d7, "fake", "config", "ConfigBuiltins.kt")
        src7 = open(p7, encoding="utf-8").read().replace(
            '                                    "context_limit_tokens" to (g.contextLimitTokens?.let { ConfigValue.Int(it) } ?: ConfigValue.Null),\n',
            "",
        )
        open(p7, "w", encoding="utf-8").write(src7)
        ok &= expect(
            "FIX-7 summary-missing-context-limit",
            check(
                os.path.join(d7, "fake", "config", "collections", "EnvVarsCollection.kt"),
                p7,
                groups_path(d7),
            ),
            "C5",
        )

        # FIX-8 [da1026-H]: groups summary drops default_thinking_level -> exactly C5 red
        d8 = fixture_dir()
        p8 = os.path.join(d8, "fake", "config", "ConfigBuiltins.kt")
        src8 = open(p8, encoding="utf-8").read().replace(
            '                                    "default_thinking_level" to (g.defaultThinkingLevel?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),\n',
            "",
        )
        open(p8, "w", encoding="utf-8").write(src8)
        ok &= expect(
            "FIX-8 summary-missing-thinking-level",
            check(
                os.path.join(d8, "fake", "config", "collections", "EnvVarsCollection.kt"),
                p8,
                groups_path(d8),
            ),
            "C5",
        )

        # FIX-9 [da1026-H] (fail-closed control): ConfigBuiltins with no groups
        # registration -> C5 must be EXTRACT_FAIL red, never silently green.
        d9 = fixture_dir()
        p9 = os.path.join(d9, "fake", "config", "ConfigBuiltins.kt")
        open(p9, "w", encoding="utf-8").write("package fake\n\nobject ConfigBuiltins {\n")
        fails9 = check(
            os.path.join(d9, "fake", "config", "collections", "EnvVarsCollection.kt"), p9, groups_path(d9)
        )
        if not any(f.startswith("C5 EXTRACT_FAIL") for f in fails9):
            print(f"[SELFTEST] FIX-9 no-groups-block: expected C5 EXTRACT_FAIL, got {fails9}")
            ok = False
        else:
            print("[SELFTEST] FIX-9 no-groups-block: fail-closed as expected")

        # FIX-10 [da1026-H] (wrong-block guard): the providers summary loses its
        # customUserAgent key -> exactly C7 red (the groups block stays intact).
        d10 = fixture_dir()
        p10 = os.path.join(d10, "fake", "config", "ConfigBuiltins.kt")
        src10 = open(p10, encoding="utf-8").read().replace(
            '                                    "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),\n',
            "",
        )
        open(p10, "w", encoding="utf-8").write(src10)
        ok &= expect(
            "FIX-10 providers-ua-missing",
            check(
                os.path.join(d10, "fake", "config", "collections", "EnvVarsCollection.kt"),
                p10,
                groups_path(d10),
            ),
            "C7",
        )
    finally:
        shutil.rmtree(d, ignore_errors=True)
        for dd in (d2, d3, d4, d5, d6, d7, d8, d9, d10):
            shutil.rmtree(dd, ignore_errors=True)

    # ── [da1028-B / ISSUE-03] criteria 8-11 ────────────────────────────────
    # Four-source trees: 1-7 judge the config fixtures as always, 8-11 judge
    # the backup scope list + the registerBackground block.

    def expect_prefixes(label: str, failures: list[str], wants: tuple) -> bool:
        """Precision control for arms where a shared precondition legitimately
        trips more than one criterion: the failure list must match `wants`
        exactly (same length, same order, same "Cx KIND" heads)."""
        def head(s: str) -> str:
            return (s.split(":", 1)[0] if ":" in s else s).strip()

        got = tuple(head(f) for f in failures)
        want = tuple(head(w) for w in wants)
        if got != want:
            print(f"[SELFTEST] {label}: expected {want}, got {failures}")
            return False
        print(f"[SELFTEST] {label}: red as expected ({'; '.join(failures)})")
        return True

    BUILTINS_OK4 = inject_members(FIXTURE_BUILTINS_OK, OVERLAY_FN_OK + PRIVACY_FN_OK)
    fds = []

    def full_arm(label: str, builtins: str, backup: str, wants) -> bool:
        d4 = full_fixture(builtins, backup)
        fds.append(d4)
        fails = full_check(d4)
        if not wants:
            if fails:
                print(f"[SELFTEST] {label}: expected GREEN, got {fails}")
                return False
            print(f"[SELFTEST] {label}: GREEN as expected")
            return True
        return expect_prefixes(label, fails, wants)

    ok &= full_arm("FIX-11 complete (all four sources)", BUILTINS_OK4, FIXTURE_BACKUP_OK, ())

    # FIX-12: whitelist drops "envvars" -> exactly C8
    ok &= full_arm(
        "FIX-12 backup-whitelist-no-envvars",
        BUILTINS_OK4,
        FIXTURE_BACKUP_OK.replace('        "envvars",\n', ""),
        ("C8 RED:",),
    )

    # FIX-13: overlay registered under the wrong path -> exactly C9 (the
    # constants are intact, so C10 stays green — arms must not blur together)
    ok &= full_arm(
        "FIX-13 overlay-wrong-path",
        BUILTINS_OK4.replace('path = "background.overlay"', 'path = "background.overlayEnabled"'),
        FIXTURE_BACKUP_OK,
        ("C9 RED:",),
    )

    # FIX-14: key re-spelled as the raw literal instead of the repository
    # constant -> exactly C10
    ok &= full_arm(
        "FIX-14 overlay-key-literal",
        BUILTINS_OK4.replace(
            "                key = com.rikkaminis.app.data.repository.BackgroundSettingsRepository\n"
            "                    .KEY_BG_OVERLAY_ENABLED,",
            '                key = "backgroundOverlayEnabled",',
        ),
        FIXTURE_BACKUP_OK,
        ("C10 RED:",),
    )

    # FIX-15 (fail-closed control): no registerBackground member at all ->
    # C9 and C10 both refuse to judge, never a silent green (the privacy
    # registration stays so this arm isolates the overlay precondition)
    ok &= full_arm(
        "FIX-15 overlay-registration-absent",
        inject_members(FIXTURE_BUILTINS_OK, PRIVACY_FN_OK),
        FIXTURE_BACKUP_OK,
        ("C9 EXTRACT_FAIL:", "C10 EXTRACT_FAIL:"),
    )

    # FIX-16 (fail-closed control): the scope set is gone -> C8 + C11 refuse
    # (the minimal source has no member remap either, so C12 refuses too)
    ok &= full_arm(
        "FIX-16 backup-anchor-absent",
        BUILTINS_OK4,
        "package fake\n\nobject ConfigBackup {\n}\n",
        ("C8 EXTRACT_FAIL:", "C11 EXTRACT_FAIL:", "C12 EXTRACT_FAIL:"),
    )

    # FIX-17 (reverse anchor): a legacy scope is swapped out -> exactly C11
    ok &= full_arm(
        "FIX-17 legacy-scope-swapped-out",
        BUILTINS_OK4,
        FIXTURE_BACKUP_OK.replace('        "runtime",\n', ""),
        ("C11 RED:",),
    )

    # FIX-18 (reverse anchor): the privacy toggle stops being registered ->
    # exactly C11
    ok &= full_arm(
        "FIX-18 privacyMode-unregistered",
        inject_members(FIXTURE_BUILTINS_OK, OVERLAY_FN_OK),
        FIXTURE_BACKUP_OK,
        ("C11 RED:",),
    )

    # ── [dv20-a1 A-02] sentinel-preservation arms ─────────────────────────
    SENTINEL_BACKUP_OK = '''
package fake.config

import com.rikkaminis.app.data.model.SystemVoiceEntries

object ConfigBackup {
    private fun remapMembers(entryIdMap: Map<String, String>, srcMembers: List<String>): Int {
        var droppedMembers = 0
        for (old in srcMembers) {
            val mapped = entryIdMap[old]
                ?: old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }
            if (mapped != null) members.add(mapped) else droppedMembers++
        }
        return droppedMembers
    }
}
'''

    SENTINEL_BUILTINS_OK = '''
package fake.config

import com.rikkaminis.app.data.model.SystemVoiceEntries

object ConfigBuiltins {
    fun groupsSummary(entriesById: Map<String, Any>): List<Any> {
        val entryIds = listOf("a")
        return entryIds.map { entryId ->
            val e = entriesById[entryId] ?: SystemVoiceEntries.resolve(entryId)
            if (e == null) listOf("entry_id", entryId, "missing") else listOf("entry_id", entryId)
        }
    }
}
'''

    def sentinel_arm(label: str, source: str, fn, want) -> bool:
        r = fn(source)
        got = [] if r is None else [r]
        if want is None:
            if got:
                print(f"[SELFTEST] {label}: expected GREEN, got {got}")
                return False
            print(f"[SELFTEST] {label}: GREEN as expected")
            return True
        if len(got) != 1 or not got[0].startswith(want):
            print(f"[SELFTEST] {label}: expected exactly one {want!r}, got {got}")
            return False
        print(f"[SELFTEST] {label}: red as expected ({got[0]})")
        return True

    ok &= sentinel_arm("FIX-19 sentinel-preserving remap", SENTINEL_BACKUP_OK,
                       check_sentinel_remap, None)
    ok &= sentinel_arm(
        "FIX-20 remap drops the sentinels",
        SENTINEL_BACKUP_OK.replace(
            "                ?: old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }\n", ""),
        check_sentinel_remap, "C12 RED:")
    ok &= sentinel_arm("FIX-21 remap anchor absent",
                       "package fake\n\nobject ConfigBackup {\n}\n",
                       check_sentinel_remap, "C12 EXTRACT_FAIL:")
    ok &= sentinel_arm("FIX-22 summary resolves the members", SENTINEL_BUILTINS_OK,
                       check_group_member_resolution, None)
    ok &= sentinel_arm(
        "FIX-23 summary emits the missing stub",
        SENTINEL_BUILTINS_OK.replace(" ?: SystemVoiceEntries.resolve(entryId)", ""),
        check_group_member_resolution, "C13 RED:")
    ok &= sentinel_arm(
        "FIX-24 sentinel literal re-spelled",
        SENTINEL_BUILTINS_OK.replace(
            "        val entryIds = listOf(\"a\")",
            "        val pinned = \"__builtin_system_speech__\"\n"
            "        val entryIds = listOf(\"a\")"),
        check_group_member_resolution, "C13 RED:")

    # ── [wide-1024 12-misc-b] witness arms ────────────────────────────────
    # Each one keeps every token the old criterion matched and drops the EFFECT.
    # FIX-25: the reader answers a constant (the path + writer stay intact).
    d25 = fixture_dir()
    p25 = os.path.join(d25, "fake", "config", "collections", "EnvVarsCollection.kt")
    src25 = open(p25, encoding="utf-8").read().replace(
        "        reader = {\n"
        "            val e = repo.entries.value.firstOrNull { it.key == key }\n"
        "            if (e == null) ConfigValue.Null else ConfigValue.Str(e.group)\n"
        "        },\n",
        "        reader = { ConfigValue.Null },\n",
    )
    open(p25, "w", encoding="utf-8").write(src25)
    ok &= expect(
        "FIX-25 groupField reader answers a constant",
        check(
            p25,
            os.path.join(d25, "fake", "config", "ConfigBuiltins.kt"),
            groups_path(d25),
        ),
        "C2 RED:",
    )

    # FIX-26: the helper is called, with a constant instead of the parsed value.
    d26 = fixture_dir()
    p26 = groups_path(d26)
    src26 = open(p26, encoding="utf-8").read().replace(
        "applyContextLimitWrite(g, contextLimit ?: 0)", "applyContextLimitWrite(g, 0)")
    open(p26, "w", encoding="utf-8").write(src26)
    ok &= expect(
        "FIX-26 context-limit helper fed a constant",
        check(
            os.path.join(d26, "fake", "config", "collections", "EnvVarsCollection.kt"),
            os.path.join(d26, "fake", "config", "ConfigBuiltins.kt"),
            p26,
        ),
        "C4 RED:",
    )

    # FIX-27: isSystemEntryId present but short-circuited to false.
    ok &= sentinel_arm(
        "FIX-27 sentinel predicate dead-guarded",
        SENTINEL_BACKUP_OK.replace(
            "old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }",
            "old.takeIf { false && SystemVoiceEntries.isSystemEntryId(it) }"),
        check_sentinel_remap, "C12 RED:")
    # FIX-27b: the tautology spelling of the same dead predicate.
    ok &= sentinel_arm(
        "FIX-27b sentinel predicate exhausted middle",
        SENTINEL_BACKUP_OK.replace(
            "old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }",
            "old.takeIf { SystemVoiceEntries.isSystemEntryId(it) || "
            "!SystemVoiceEntries.isSystemEntryId(it) }"),
        check_sentinel_remap, "C12 RED:")

    # FIX-28: resolve() called inside .also — the value is thrown away.
    ok &= sentinel_arm(
        "FIX-28 resolve result discarded",
        SENTINEL_BUILTINS_OK.replace(
            " ?: SystemVoiceEntries.resolve(entryId)",
            ".also { SystemVoiceEntries.resolve(entryId) }"),
        check_group_member_resolution, "C13 RED:")

    # ── [batch5-t11] 第五批外部见证臂：拼写在、效果不在 ──────────────────
    # FIX-29 (C1): 注册被挪进 `.also` —— 列表元素不含 groupField，字段从配置面蒸发。
    d29 = fixture_dir()
    p29 = os.path.join(d29, "fake", "config", "collections", "EnvVarsCollection.kt")
    src29 = (open(p29, encoding="utf-8").read()
             .replace("            groupField(forId),\n", "")
             .replace("        )\n    }\n\n    private fun createdAtField",
                      "        ).also { groupField(forId) }\n    }\n\n    private fun createdAtField"))
    open(p29, "w", encoding="utf-8").write(src29)
    ok &= expect(
        "FIX-29 groupField registered inside .also",
        check(p29, os.path.join(d29, "fake", "config", "ConfigBuiltins.kt"), groups_path(d29)),
        "C1 RED:",
    )

    # FIX-30 (C2): reader 的 else 分支把常量当接收者（Str 只活在丢弃的 lambda 里）。
    d30 = fixture_dir()
    p30 = os.path.join(d30, "fake", "config", "collections", "EnvVarsCollection.kt")
    src30 = open(p30, encoding="utf-8").read().replace(
        "            if (e == null) ConfigValue.Null else ConfigValue.Str(e.group)",
        "            if (e == null) ConfigValue.Null else ConfigValue.Null.also { ConfigValue.Str(e.group) }")
    open(p30, "w", encoding="utf-8").write(src30)
    ok &= expect(
        "FIX-30 groupField reader answers a constant receiver",
        check(p30, os.path.join(d30, "fake", "config", "ConfigBuiltins.kt"), groups_path(d30)),
        "C2 RED:",
    )

    # FIX-31 (C4): 实参是 `0.also { contextLimit }` —— 标识符在，值是无限制。
    d31 = fixture_dir()
    p31 = groups_path(d31)
    src31 = open(p31, encoding="utf-8").read().replace(
        "applyContextLimitWrite(g, contextLimit ?: 0)",
        "applyContextLimitWrite(g, 0.also { contextLimit })")
    open(p31, "w", encoding="utf-8").write(src31)
    ok &= expect(
        "FIX-31 context-limit helper fed a discarded constant",
        check(os.path.join(d31, "fake", "config", "collections", "EnvVarsCollection.kt"),
              os.path.join(d31, "fake", "config", "ConfigBuiltins.kt"), p31),
        "C4 RED:",
    )

    # FIX-32 (C5): 汇总键还在，值被钉成 ConfigValue.Null。
    ok &= sentinel_arm(
        "FIX-32 groups summary key pinned to a constant",
        FIXTURE_BUILTINS_OK.replace(
            '"default_thinking_level" to (g.defaultThinkingLevel?.let { '
            "ConfigValue.Str(it) } ?: ConfigValue.Null),",
            '"default_thinking_level" to ConfigValue.Null,'),
        check_groups_summary_keys, "C5 RED:")

    # FIX-33 (C8): `setOf(…, "envvars") - "envvars"` —— 字面量命中，集合实际不含它。
    ok &= full_arm(
        "FIX-33 backup scope subtracts envvars",
        BUILTINS_OK4,
        FIXTURE_BACKUP_OK.replace('        "envvars",\n    )', '        "envvars",\n    ) - "envvars"'),
        ("C8 RED:",),
    )

    # FIX-34 (C12): 谓词换成 `(1 == 2) && isSystemEntryId(it)`（非 false 字面量的死守卫）。
    ok &= sentinel_arm(
        "FIX-34 sentinel predicate folded by a numeric guard",
        SENTINEL_BACKUP_OK.replace(
            "old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }",
            "old.takeIf { (1 == 2) && SystemVoiceEntries.isSystemEntryId(it) }"),
        check_sentinel_remap, "C12 RED:")

    for dd in fds:
        shutil.rmtree(dd, ignore_errors=True)

    return ok


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        if run_fixtures():
            print("ENVVAR_GROUP_SELFTEST=PASS")
            return 0
        print("ENVVAR_GROUP_SELFTEST=FAIL")
        return 1

    roots = [a for a in sys.argv[1:] if not a.startswith("-")]
    if not roots:
        print("usage: envvar_config_group_check.py <repo_root> | --selftest")
        return 2
    ev, cb, gr, bk = locate(roots[0])
    failures = check(ev, cb, gr, bk)
    if failures:
        for f in failures:
            print(f)
        print(f"envvar_config_group_check: {len(failures)} violation(s)")
        return 1
    print("envvar_config_group_check: OK (13 criteria green)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
