#!/usr/bin/env python3
"""IME-inset guard — RikkaMinis CI scan gate.

## Why this gate exists

2026-09-20, user-reported: in the skill-file editor and the memory-file editor,
tapping the text field let the soft keyboard cover the content being edited.

Root cause (library sources, compose-bom 2025.09.00 → material3 1.3.2 /
foundation 1.9.1): the app is edge-to-edge (`MainActivity` enableEdgeToEdge +
manifest `adjustResize`). Under edge-to-edge `adjustResize` no longer resizes
the window for the IME, so Compose must consume `WindowInsets.ime` itself.

Host containers differ in whether they consume it:

  SettingsScaffold    → .imePadding() + verticalScroll   (SettingsComponents.kt)
  ModalBottomSheet    → contentWindowInsets = safeDrawing.only(Bottom),
                        and safeDrawing ⊇ ime             (SheetDefaults.kt:316)
  StandardChatSheet   → wraps ModalBottomSheet
  bare Scaffold       → contentWindowInsets = systemBars ONLY (Scaffold.kt:293)
  AlertDialog/Dialog  → independent Window, platform decides

So a text field hosted by a bare `Scaffold` has NOTHING shrinking it above the
keyboard. `BasicTextField` does scroll its own caret into view
(`CoreTextField.bringSelectionEndIntoView`), but its bounds extend behind the
IME, so the caret lands behind the keyboard. Silent: no crash, no failing test,
no log line — just a field you cannot see while typing.

This is not a one-off. The same class was fixed piecemeal at least four times
before anyone noticed the pattern: `BrowserSettingsSheet`, `MCPIntegrationsScreen`
(GH#44), `EnvironmentVariablesScreen`, `SettingsComponents` (T183) — and then
again in 2026-09-20 for the two user-reported editors plus four more hosts.
Per the change-ladder rule "same family recurring ≥2× must be fixed at the
shared layer", a mechanical gate is the fix for the recurrence; fixing the
sixth call site by hand is not.

## What this gate flags

For every text-input call site in production sources, find the innermost
enclosing composable. If that composable's body contains a bare `Scaffold(`,
does NOT contain a protecting host (`SettingsScaffold` / `ModalBottomSheet` /
`StandardChatSheet`), and has no `.imePadding()` anywhere in the body, flag it.

Escape hatch — TWO forms, both inline and therefore immune to line-number rot:

  `ime-ok: <reason>`     the site is intentionally unprotected (e.g. the host is
                         an AlertDialog with its own Window; the platform
                         pans/resizes to keep the focused field visible).
                         Put it in the composable, same or previous line.

  `ime-unreachable: <reason>`  the bare Scaffold and the text field are in
                         mutually exclusive branches (e.g. a `Scaffold` for one
                         state, a sheet for another), so the heuristic's
                         "same composable" grouping is a false positive.

Do NOT silence this with a line-numbered allow-list — those rot on the first
refactor. The escape reasons are greppable, reviewable and survive edits.

## Anchor discipline (wide4 hardening)

Every criterion below is anchored on the EFFECT the host ends up with, not on
the spelling of a fix — the wide4 adversarial round falsified the previous
"some string is present" forms with five minimal patches:

  * the `.imePadding()` must sit in an argument list of a host that can resize
    THIS field (the leaf call, or a layout container whose trailing block
    contains it). A padding handed to `Spacer`, or one that is a lambda's
    non-tail expression (`.let { if (1 == 2) it.imePadding() else it }`),
    changes nothing;
  * a hoisted `val x = Modifier…imePadding()` counts only when `x` is READ BY
    one of those `modifier =` slots — a second lexical mention is not a read;
  * `onBack = <value>` counts only when the value keeps the in-scope handler
    (`onBack`, or `{ onBack() }`). `null`, `{}` and discarding chains
    (`onBack.takeIf { false } ?: {}`) render a back arrow that does nothing;
  * literal-decided guards are dead, not protection: `if (false && g)`,
    `while (false)`, `when (false)`, `1 == 2`, `x == x`.

Source lexing is delegated to the shared `scripts/scan/_kotlin_lexer.py`
projection (one lexer for the whole wave); a file it cannot parse is reported
as a violation — fail-closed, because judging a broken view is how the
parser-death axis gets in.

## Known ceiling (read this before trusting a green run)

The judge is a STATIC HEURISTIC over "same composable", not a dataflow proof.
It cannot see:
  - new host containers (a future `BasicAlertDialog`, a hand-rolled `Window`,
    a `Popup`) — add them to PROTECTING only after verifying they consume IME;
  - runtime-only facts (a host that conditionally consumes IME).
It only prevents the SAME static shape from coming back. Real-device
verification stays mandatory for any new editor surface — the 2026-09-20 round
was found by the user, not by any scanner.

Usage: python3 scripts/scan/ime_inset_guard.py <repo_root>
Exit:  0 = clean, 1 = violation(s) found
"""
import os
import re
import shutil
import sys
import tempfile

# Shared Kotlin primitives (wide4): every gate in this wave projects the source
# through ONE lexer instead of carrying its own scanner — see the block comment
# above `strip_comments`.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
from _kotlin_lexer import analyse                      # noqa: E402
from _kotlin_ast import (                              # noqa: E402
    const_bool, if_blocks, split_top, strip_parens, when_branches,
)

SRC_ROOTS = [
    "src/android/app/src/main/java",
]

# 文本输入构件：直接的 Compose 输入框 + 本仓的共享包装
FIELD_RE = re.compile(
    r"(?<![\w.])("
    r"BasicTextField|OutlinedTextField|TextField|"
    r"DialogTextField|SectionTextField|MemoryFileEditorContent"
    r")\s*\("
)

# 裸 Scaffold —— 两种调用形态都要覆盖：`Scaffold(...) { }` 与尾随 lambda
# `Scaffold { }`（后者无括号；漏掉它会让 ApiKeyStep / ModelSelectionStep 这类
# 站点整体消失，判据假阴性 —— 实测踩过）。
BARE_SCAFFOLD_RE = re.compile(r"(?<![\w.])Scaffold\s*(?:\(|\{)")

# 已知会消费 IME 的宿主（每一条都要有库源码或本仓包装的依据，见模块 KDoc）
# 同样覆盖括号与尾随 lambda 两种形态。
PROTECTING_RE = re.compile(
    r"(?<![\w.])(SettingsScaffold|ModalBottomSheet|StandardChatSheet)\s*(?:\(|\{)"
)

IME_PADDING_RE = re.compile(r"\.imePadding\(\)")

FUN_RE = re.compile(
    r"^(\s*)(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:private\s+|internal\s+|public\s+|override\s+)?"
    r"fun\s+(?:<[^>]*>\s*)?([A-Za-z0-9_]+)\s*\("
)

ESCAPE_RE = re.compile(r"ime-(?:ok|unreachable)\s*:")

# ── comment / literal stripper (wide3-07 hardening) ──────────────────────────
#
# The old stripper processed ONE LINE AT A TIME:
#
#     for line in text.splitlines():
#         line = re.sub(r"/\*.*?\*/", "", line)      # only same-line blocks
#
# so a block comment that OPENED on one line and CLOSED on another (legal
# Kotlin, and exactly the edit someone makes when disabling a modifier) left
# its contents inside the "code" the criteria scan. External witness IME-HOST
# wrapped `.imePadding()` in a multi-line `/* … */` and this gate stayed
# green: the composable was reported as protected while the keyboard covered
# the field again.
#
# The `"*/*"` swallow that the line-at-a-time version was working around
# (SkillsManagementScreen.kt `launcher.launch("*/*")`) is handled properly
# now: the scanner is literal-aware, so a `/*` INSIDE a string can never open
# a comment. Literal CONTENTS are blanked as well, so structural scans
# (paren/brace matching, the 1040 B01 criteria) never see a `(` or `{` that
# only exists inside a string.
#
# Invariants kept from the old version — both measured, not decorative:
#   - line count is UNCHANGED (newlines preserved): findings are reported by
#     line number and the function spans are line based;
#   - a line comment carrying `ime-ok:` / `ime-unreachable:` survives, so an
#     escape hatch cannot be erased by its own stripper, and non-escape line
#     comments keep their `//` prefix (the escape window walk above the field
#     stops at the first non-blank line — blanking it would widen the window).

def strip_comments(text):
    """代码视图：注释与字符串内容抹成空格。返回 (lines, None) 或 (None, reason)。

    词法器换成了共享的 `_kotlin_lexer.analyse`——手上这一版用布尔 `in_block`
    标记块注释，遇到 Kotlin 的**嵌套块注释** `/* outer /* inner */ .imePadding() */`
    会在第一个 `*/` 就把扫描器放回「代码」态，仍被注释掉的 `.imePadding()` 于是
    算作保护（外部见证，parser-death 轴）。同族构造（单引号字符 `'"'`、三引号
    原始字符串、`${...}` 模板、反引号标识符）在同一波已经击穿另外几道门——自己
    写词法器就是「下一个词法特性上线即失明」，所以这里只调用共享投影。

    保留的旧不变量（都有实测理由）：
      - 行数不变（投影严格等长，命中按行号报）；
      - 带 `ime-ok:` / `ime-unreachable:` 的行注释原样保留 —— 逃生口不能被
        自己的词法器抹掉；
      - 解析失败**不静默**：返回 None，调用方一律 fail-closed 报违规（残缺视图
        读成「干净」正是 parser-death 轴的入口）。
    """
    ok, reason, views = analyse(text)
    if not ok:
        return None, reason
    out = list(views["code"])
    # 恢复逃生口行注释：`//` 在注释视图里被抹白才说明它是真注释（字符串里的
    # `"http://…"` 不会被抹）。代价：块注释里的 `ime-ok:` 也会被恢复成逃生口——
    # 与「加一条明文 ime-ok 就能豁免」等价，不构成新的绕过面（已记 §6）。
    for m in re.finditer(r"//[^\n]*", text):
        s = m.start()
        if views["comments"][s:s + 2] != "  ":
            continue
        if ESCAPE_RE.search(text[s:m.end()]):
            out[s:m.end()] = list(text[s:m.end()])
    return "".join(out).split("\n"), None

def function_spans(lines):
    """返回 [(name, start, end)] —— 用「下一个函数起点」当上一个的终点。

    刻意用这个粗糙口径：本仓 composable 极少嵌套定义（嵌套会让内层被外层
    吞掉，但那种情况下外层通常也含同样的宿主，判据仍成立）。KDoc 里记了
    这个天花板。
    """
    funcs = []
    for i, line in enumerate(lines):
        m = FUN_RE.match(line)
        if m:
            funcs.append([m.group(2), i, len(m.group(1))])
    for k in range(len(funcs)):
        funcs[k].append(funcs[k + 1][1] if k + 1 < len(funcs) else len(lines))
    return funcs


def line_offsets(lines):
    offs, pos = [], 0
    for line in lines:
        offs.append(pos)
        pos += len(line) + 1
    return offs


# ── structural primitives (paren/brace matched, literal-aware input) ─────────

def match_paren(text, open_idx):
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
    return None


def match_brace(text, open_idx):
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
    return None


def _split_top(expr, op):
    """Split `expr` on `op` at paren/bracket/brace depth 0."""
    parts, depth, last, i = [], 0, 0, 0
    while i < len(expr):
        c = expr[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and expr.startswith(op, i):
            parts.append(expr[last:i])
            last = i + len(op)
            i = last
            continue
        i += 1
    parts.append(expr[last:])
    return parts


def cond_truth(expr):
    """True / False 当布尔表达式被**字面量**决定，否则 None（未知）。

    折叠交给共享的 `_kotlin_ast.const_bool`：`!` / `&&` / `||` / 括号之外还覆盖
    **常量比较**（`1 == 2`、`0 == 1`、`"a" == "b"`）。手写版只认 `true`/`false`/
    `!`/`&&`/`||`，于是 `if (1 == 2) { … }` 里的死分支活着（外部见证，family-hole
    轴：保留拼写、删掉决定）。

    `x == x` 恒真 / `x != x` 恒假是同一族的身份折叠，一并判掉。
    """
    s = strip_parens(expr)
    v = const_bool(s)
    if v is not None:
        return v
    for op, verdict in (("==", True), ("!=", False)):
        parts = split_top(s, (op,))
        if len(parts) == 2:
            a = s[parts[0][0]:parts[0][1]].strip()
            b = s[parts[1][0]:parts[1][1]].strip()
            if a and a == b:
                return verdict
    return None


def dead_guard_spans(text):
    """[(start, end)] of the branches whose controlling condition is literal-decided.

    覆盖三类，全部从 `_kotlin_ast` 派生（不再用自己那版正则 + 布尔）：
      * `if (<恒假>)` 的 then 臂 / `if (<恒真>)` 的 else 臂（含 braceless 形态）；
      * `while (<恒假>)` 的体；
      * `when (<字面量>) { … }`——`when (false)` 的每个非 `else` 分支都不执行
        （旧版只认 `if (false)`，`when (false) { true -> IconButton(…) }` 保留了
        拼写、渲染出空导航槽）。
    """
    spans = []
    for cond, t_start, t_end, e_start, e_end in if_blocks(text):
        truth = cond_truth(cond)
        if truth is False:
            spans.append((t_start, t_end))
        elif truth is True and e_start is not None:
            spans.append((e_start, e_end))
    for m in re.finditer(r"\bwhile\s*\(", text):
        op = m.end() - 1
        cl = match_paren(text, op)
        if cl is None or cond_truth(text[op + 1:cl]) is not False:
            continue
        k = cl + 1
        while k < len(text) and text[k] in " \t":
            k += 1
        if k < len(text) and text[k] == "{":
            e = match_brace(text, k)
            if e is not None:
                spans.append((k, e))
        else:
            eol = text.find("\n", k)
            spans.append((k, eol if eol >= 0 else len(text)))
    for subject, cond, b_start, b_end in when_branches(text):
        branch = cond.strip()
        if branch in ("else", ""):
            continue
        subj = cond_truth(subject) if subject.strip() else None
        if subj is False:
            spans.append((b_start, b_end))          # `when (false)` — 无分支执行
            continue
        if cond_truth(branch) is False:
            spans.append((b_start, b_end))
    return spans


def _inside(spans, pos):
    return any(a <= pos <= b for a, b in spans)


def _split_args(text, start, end):
    """[(start, end)] spans of the TOP-LEVEL arguments in text[start:end]."""
    spans, depth, last = [], 0, start
    i = start
    while i < end:
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            spans.append((last, i))
            last = i + 1
        i += 1
    spans.append((last, end))
    return [(a, b) for a, b in spans if text[a:b].strip()]


def _named_arg(text, arg_start, arg_end):
    """(key, value_start, value_end) when the argument is a `key = value` form."""
    seg = text[arg_start:arg_end]
    m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?![=>])", seg)
    if m is None:
        return None
    return m.group(1), arg_start + m.end(), arg_end


def arg_value_by_key(text, start, end, key):
    """(raw value text, offset) of the top-level argument `key = …`, or (None, -1).

    Bracket-aware on purpose: `onBack = { nav.popBackStack() }` must be captured
    whole — the old `[^,\n)]+` regex stopped at the first `)` inside the lambda.
    """
    for a, b in _split_args(text, start, end):
        named = _named_arg(text, a, b)
        if named is not None and named[0] == key:
            return text[named[1]:named[2]], named[1]
    return None, -1


def enclosing_call(text, pos):
    """(callee, open_idx, close_idx) of the innermost call enclosing `pos`.

    `pos` must sit in a call's ARGUMENT LIST. When an unmatched `{` is met
    before an unmatched `(` the position lives inside a LAMBDA BODY, and a chain
    there does not reach the host: the lambda's RESULT decides what the receiver
    becomes (`.let { if (1 == 2) it.imePadding() else it }` returns the
    un-padded receiver — external witness, family-hole axis). The old version
    only tracked `(`/`)`, walked straight through the lambda and attributed the
    padding to the outer `DialogTextField`, i.e. it graded the SPELLING of the
    chain instead of what the host receives. Returns ('', -1, None) for the
    lambda case so the caller treats the chain as unattached.
    """
    paren = 0
    brace = 0
    for i in range(pos - 1, -1, -1):
        c = text[i]
        if c == ")":
            paren += 1
        elif c == "(":
            if paren == 0:
                if brace > 0:
                    return "", -1, None
                cl = match_paren(text, i)
                j = i - 1
                while j >= 0 and (text[j].isalnum() or text[j] == "_"):
                    j -= 1
                return text[j + 1:i], i, cl
            paren -= 1
        elif c == "}":
            brace += 1
        elif c == "{":
            if brace == 0:
                return "", -1, None
            brace -= 1
    return "", -1, None


def trailing_block(text, close_idx):
    """(open, close) of the trailing lambda block that follows a call."""
    if close_idx is None:
        return None
    k = close_idx + 1
    while k < len(text) and text[k] in " \t\r\n,":
        k += 1
    if k < len(text) and text[k] == "{":
        e = match_brace(text, k)
        if e is not None:
            return (k, e)
    return None


CONTAINER_CALLEES = frozenset({
    "Column", "Row", "Box", "Surface", "Card", "LazyColumn", "LazyRow",
    "LazyVerticalGrid", "LazyHorizontalGrid", "FlowRow", "FlowColumn",
    "Scaffold", "ModalBottomSheet", "StandardChatSheet", "BasicAlertDialog",
})
FIELD_CALLEES = frozenset({
    "BasicTextField", "OutlinedTextField", "TextField", "DialogTextField",
    "SectionTextField", "MemoryFileEditorContent",
})


def hoisted_modifier_name(text, pos):
    """Name of a `val x = Modifier…` declared before `pos`, else None."""
    seg = text[max(0, pos - 2000):pos]
    last = None
    for m in re.finditer(
            r"\bval\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*(?:[\w.]+\.)?Modifier\b", seg):
        last = m
    if last is None:
        return None
    if re.search(r"[;}]", seg[last.end():]):
        return None          # a statement boundary separates declaration and use
    return last.group(1)


def modifier_slot_hosts(body, field_pos, dead):
    """[(callee, value_start, value_end)] — `modifier = …` arguments that can
    actually resize the host of the field at `field_pos`.

    A slot qualifies when it belongs to
      * the text-field call itself (leaf-level editor), or
      * a layout CONTAINER whose trailing lambda block CONTAINS the field.
    Everything else (`Spacer`, a sibling, a non-host call) is someone else's
    modifier chain and cannot protect this field.
    """
    out = []
    for m in re.finditer(r"(?<![\w.])modifier\s*=", body):
        if _inside(dead, m.start()):
            continue
        callee, _op, cl = enclosing_call(body, m.start())
        if cl is None:
            continue
        if callee in CONTAINER_CALLEES:
            blk = trailing_block(body, cl)
            if blk is None or not (blk[0] <= field_pos <= blk[1]):
                continue
        elif callee not in FIELD_CALLEES:
            continue
        v_start = m.end()
        v_end = cl
        depth = 0
        i = v_start
        while i < cl:
            c = body[i]
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            elif c == "," and depth == 0:
                v_end = i
                break
            i += 1
        out.append((callee, v_start, v_end))
    return out


def hoisted_reaches_host(body, name, slots, dead):
    """True when `name` is READ BY a host's `modifier =` argument.

    Presence is not consumption (external witness, family-hole axis):
    `val unused = Modifier.imePadding()` followed by `val _ignore = unused`
    satisfied the old `len(re.findall(name)) >= 2` count while the host received
    a padding-free `Modifier`. The identifier must appear inside a slot that can
    resize the field, and not under a dead guard.
    """
    pat = re.compile(r"\b%s\b" % re.escape(name))
    for _callee, v_start, v_end in slots:
        for m in pat.finditer(body, v_start, v_end):
            if not _inside(dead, m.start()):
                return True
    return False


def live_ime_padding(body, field_pos):
    """True when `body` carries a `.imePadding()` that can actually protect the
    text field at `field_pos`.

    Anchor-on-effect, not on spelling (external witnesses IME-HOST / anchor /
    wide4 family-hole):
      * an argument list of a layout CONTAINER whose trailing lambda encloses the
        field (the documented fix shape), or of the text-field call itself;
      * a hoisted `val x = Modifier…imePadding()` that is READ BY one of those
        `modifier =` slots (a mere second mention is not consumption);
    and never inside a literal-decided guard / a discarded lambda result.
    """
    dead = dead_guard_spans(body)
    slots = modifier_slot_hosts(body, field_pos, dead)
    for m in re.finditer(r"\.imePadding\(\)", body):
        pos = m.start()
        if _inside(dead, pos):
            continue
        callee, _op, cl = enclosing_call(body, pos)
        if cl is not None:
            if callee in FIELD_CALLEES:
                return True
            if callee in CONTAINER_CALLEES:
                blk = trailing_block(body, cl)
                if blk is not None and blk[0] <= field_pos <= blk[1]:
                    return True
            continue        # attached to a non-host call — not this field's host
        name = hoisted_modifier_name(body, pos)
        if name and hoisted_reaches_host(body, name, slots, dead):
            return True
    return False


def project(path):
    """(code_text, None) for `path`, or (None, reason) when it cannot be lexed."""
    raw = open(path, encoding="utf-8", errors="replace").read()
    lines, reason = strip_comments(raw)
    if lines is None:
        return None, reason
    return "\n".join(lines), None


def scan_file(text, rel):
    lines = text.split("\n")
    offs = line_offsets(lines)
    funcs = function_spans(lines)
    findings = []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(("import", "*")):
            continue
        if not FIELD_RE.search(line):
            continue
        # 最内层（跨度最小）的封闭函数 = 该输入点所属的 composable
        encl = [f for f in funcs if f[1] <= i < f[3]]
        if not encl:
            continue
        name, start, _indent, end = min(encl, key=lambda f: f[3] - f[1])
        body_start = offs[start]
        body_end = offs[end - 1] + len(lines[end - 1])
        body = text[body_start:body_end]
        if not BARE_SCAFFOLD_RE.search(body):
            continue          # 宿主不是裸 Scaffold ⇒ 不在本门判据内
        if PROTECTING_RE.search(body):
            continue          # 同时有保护宿主 ⇒ 启发式判不准，放行
        if live_ime_padding(body, offs[i] - body_start):
            continue          # 已消费 IME（且挂在了真宿主上）⇒ 安全
        # 逃生口：向上连续回看注释/空行（多行理由注释很常见），
        # 以及函数签名头两行。
        window = list(lines[start:start + 2])
        j = i - 1
        while j >= 0 and (not lines[j].strip() or lines[j].lstrip().startswith("//")):
            window.append(lines[j])
            j -= 1
        window.append(lines[i])
        if any(ESCAPE_RE.search(l) for l in window):
            continue
        findings.append((i + 1, name, "IME-INSET"))
    return findings


# ─── [deep-audit-1040 B-01] SettingsScaffold must render a back affordance ───
#
# `SettingsScaffold` is the shared host this gate already owns (it is in the host
# table in the module KDoc above). Its `navigationSlot` used to honour ONLY the
# `navigation` parameter, so the ten call sites that passed just `onBack`
# (provider detail / connection, storage session, runtime limits, Shizuku and
# offload permissions, provider add, custom model add, chat tuning) rendered an
# EMPTY navigation slot — no back arrow at all, even though the parameter KDoc
# had always promised "the slot falls back to the back arrow iff onBack is set".
# The system back gesture still worked, which is exactly why it survived review:
# a user who does not know the gesture had no visible way out of the screen.
#
# Same family as the IME class above — a shared host whose contract and whose
# rendering disagreed, silently — so it is graded at the shared layer instead of
# one screen at a time.
#
# Graded:
#   B01-SLOT   inside the slot body: BOTH arms exist (`navigation != null` AND
#              `onBack != null`) and the onBack ARM renders a LIVE IconButton
#              wired to `onBack`. Overall-slot substring matching was not
#              enough: `onBack != null -> if (false) IconButton(onClick =
#              onBack)` keeps both spellings and renders nothing (external
#              witness B01-SLOT), so the render is looked for inside that arm
#              and must not sit under a literal-decided guard.
#   B01-CALLS  every production `SettingsScaffold(...)` call site is judged on
#              its ARGUMENT VALUE, not on the parameter's presence: a site that
#              passes `onBack = null` while its own screen received an `onBack`
#              handler (and supplies no `navigation` slot) drops the only way
#              out of a drill-down screen (external witness B01-CALLS). Sites
#              that legitimately pass null are top-level pages — they have no
#              `onBack` parameter of their own — so the value test stays quiet
#              there. The recorded floor is kept as a ROT guard: if the anchor
#              stops matching, the count collapses and the gate fails closed.
#
# Deliberately NOT graded: a top-level screen passing NEITHER parameter keeps an
# empty slot. That is the documented design (rely on the system back gesture /
# bottom navigation); flagging it would force a back arrow onto screens that are
# not drill-downs.
SCAFFOLD_REL = "com/rikkaminis/app/ui/settings/SettingsComponents.kt"
NAV_SLOT_DECL_RE = re.compile(r"\bval\s+navigationSlot\s*:")
NAV_ARM_RE = re.compile(r"\bnavigation\s*!=\s*null\s*->")
ONBACK_ARM_RE = re.compile(r"\bonBack\s*!=\s*null\s*->")
# The `onClick = …` VALUE is parsed with `_onclick_values` instead of this
# substring anchor: `onClick = onBack.takeIf { false } ?: {}` satisfies a
# `onBack\b` match while discarding the handler (external witness, wide4).
SCAFFOLD_CALL_RE = re.compile(r"(?<![\w.])SettingsScaffold\s*\(")
# Argument VALUES are parsed bracket-aware by `arg_value_by_key` — the old
# `[^,\n)]+` capture truncated any lambda containing a `)` and had to be paired
# with a separate `null|false` blacklist, which is exactly the spelling-not-value
# hole the wide4 witnesses walked through.
NAV_ARG_RE = re.compile(r"\bnavigation\s*=")
ARM_BOUNDARY_RE = re.compile(r"\b(?:navigation|onBack)\s*!=\s*null\s*->|\belse\s*->")
# 11 call sites pass a non-null `onBack` on 2026-10-25; the floor is two below
# that so a legitimate merge of screens does not false-alarm while regex rot
# (which would collapse the count) still does.
MIN_ONBACK_CALLSITES = 9


def balanced_args(text, open_idx):
    """Text inside the parenthesis at `open_idx`, or None when unbalanced.

    Comments are stripped before this runs, so a stray paren inside a string
    literal is the only way it can drift — the same ceiling `strip_comments`
    documents for the IME half of this gate.
    """
    cl = match_paren(text, open_idx)
    if cl is None:
        return None
    return text[open_idx + 1:cl]


def braced_block(text, open_idx):
    """Body of the brace block starting at `open_idx` (the `{`), exclusive."""
    e = match_brace(text, open_idx)
    if e is None:
        return None
    return text[open_idx + 1:e]


def _function_params(text, decl_off):
    op = text.find("(", decl_off)
    if op < 0:
        return ""
    cl = match_paren(text, op)
    if cl is None:
        return ""
    return text[op + 1:cl]


ONBACK_ONCLICK_OK = re.compile(r"^(?:onBack|\{\s*onBack\s*\(\s*\)\s*\})$")


def _onclick_values(text, open_idx, close_idx):
    """Raw `onClick = <value>` argument texts inside the call at `open_idx`."""
    out = []
    for a, b in _split_args(text, open_idx + 1, close_idx):
        named = _named_arg(text, a, b)
        if named is not None and named[0] == "onClick":
            out.append(text[named[1]:named[2]].strip())
    return out


def check_scaffold_navigation(text):
    """B01-* — see the block comment above. Returns [(criterion, ok, detail)]."""
    results = []
    m = NAV_SLOT_DECL_RE.search(text)
    if m is None:
        results.append(("B01-SLOT", False,
                        "no `val navigationSlot` in SettingsComponents.kt (fail-closed: the host "
                        "was reworked — re-derive this criterion)"))
        return results
    brace = text.find("{", m.end())
    slot = braced_block(text, brace) if brace >= 0 else None
    if slot is None:
        results.append(("B01-SLOT", False,
                        "`navigationSlot` has no brace body (fail-closed: re-derive)"))
        return results
    problems = []
    if not NAV_ARM_RE.search(slot):
        problems.append("the slot no longer honours the custom `navigation` slot")
    arm = ONBACK_ARM_RE.search(slot)
    if arm is None:
        problems.append("the slot has no `onBack != null` arm — every screen that passes only "
                        "`onBack` renders an empty navigation slot again")
    else:
        nxt = ARM_BOUNDARY_RE.search(slot, arm.end())
        arm_body = slot[arm.end():nxt.start() if nxt else len(slot)]
        dead = dead_guard_spans(slot)
        base = arm.end()
        live = []
        for r in re.finditer(r"IconButton\s*\(", arm_body):
            if _inside(dead, base + r.start()):
                continue
            op = base + r.end() - 1
            cl = match_paren(slot, op)
            if cl is None:
                continue
            for value in _onclick_values(slot, op, cl):
                if ONBACK_ONCLICK_OK.match(strip_parens(value)):
                    live.append(value)
                    break
        if not live:
            problems.append(
                "the `onBack` arm renders no LIVE `IconButton(onClick = onBack)` — a "
                "literal-short-circuited wrapper (`if (false) IconButton(…)`, `when (false)`), "
                "a deleted render, or a DISCARDING chain "
                "(`onClick = onBack.takeIf { false } ?: {}`) keeps the spelling and loses the "
                "back affordance")
    results.append(("B01-SLOT", not problems,
                    "the navigation slot honours the custom slot, and falls back to a back arrow "
                    "when only `onBack` is set" if not problems else "; ".join(problems)))
    return results


# ── the exemption registry (registry axis: 清单外默认拒绝，清单只做豁免) ────
#
# These settings pages DO receive an `onBack` handler (they are called from the
# settings root with one) and deliberately render an EMPTY navigation slot: they
# are top-level pages reached from the settings list, so the way out is the
# system back gesture / the bottom navigation — the same documented design the
# `SettingsScaffold` KDoc states for "a top-level screen passes neither
# parameter". The inline comments in those files say exactly that.
#
# The value test cannot tell them apart from a DRILL-DOWN screen that dropped
# its arrow (external witness B01-CALLS: `ChatTuningScreen` → `onBack = null`
# renders no back arrow and there is no bottom bar to escape with), so the
# exemption is an explicit registry keyed by (relative path, enclosing
# function). Everything NOT listed here must pass a non-null `onBack` or its own
# `navigation` slot. An entry that stops matching is reported as ROT: the screen
# changed shape and the exemption has to be re-derived, not silently inherited.
TOP_LEVEL_NULL_SITES = {
    ("com/rikkaminis/app/ui/settings/AppearanceScreen.kt", "AppearanceScreen"):
        "settings root page — bottom nav / system back",
    ("com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt", "BackupSettingsScreen"):
        "settings root page — bottom nav / system back",
    ("com/rikkaminis/app/ui/settings/EnvironmentVariablesScreen.kt", "EnvironmentVariablesScreen"):
        "settings root page — bottom nav / system back (inline comment in source)",
    ("com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt", "MCPIntegrationsScreen"):
        "settings root page — bottom nav / system back (inline comment in source)",
    ("com/rikkaminis/app/ui/settings/MemoryManagementScreen.kt", "MemoryManagementScreen"):
        "settings root page — bottom nav / system back",
    ("com/rikkaminis/app/ui/settings/ProviderListScreen.kt", "ProviderListScreen"):
        "settings root page — bottom nav / system back (inline comment in source)",
    ("com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt", "SkillsManagementScreen"):
        "settings root page — bottom nav / system back (inline comment in source)",
    ("com/rikkaminis/app/ui/settings/SoulSettingsScreen.kt", "SoulSettingsScreen"):
        "settings root page — bottom nav / system back (inline comment in source)",
    ("com/rikkaminis/app/ui/settings/UsageStatsScreen.kt", "UsageStatsScreen"):
        "settings root page — bottom nav / system back",
}


def onback_value_kind(value):
    """Classify the `onBack = …` argument VALUE (not its presence)."""
    v = strip_parens(value.strip())
    if not v:
        return "empty"
    if v == "null":
        return "null"
    if v == "false":
        return "false"
    if re.fullmatch(r"\{\s*\}", v):
        return "empty-lambda"
    return "value"


def _handler_params(params):
    """Names of handler parameters declared by a composable signature."""
    return [m.group(1) for m in re.finditer(r"\b(onBack\w*)\s*:", params)]


def audit_scaffold_callsites(text, rel):
    """B01-CALLS — per-site argument-VALUE judgment (presence is not wiring).

    Returns (violations, live_handlers, total_call_sites, matched_registry).
    """
    viols, non_null, total = [], 0, 0
    matched = set()
    lines = text.split("\n")
    offs = line_offsets(lines)
    funcs = function_spans(lines)
    for cm in SCAFFOLD_CALL_RE.finditer(text):
        # `fun SettingsScaffold(` / `override fun SettingsScaffold(` is the HOST
        # itself, not a call site: judging its parameter list as an argument list
        # would flag the declaration for "not passing onBack".
        if re.search(r"\bfun\s+$", text[max(0, cm.start() - 12):cm.start()]):
            continue
        total += 1
        op = cm.end() - 1
        args = balanced_args(text, op)
        line_no = text.count("\n", 0, cm.start()) + 1
        if args is None:
            viols.append(
                "B01-CALLS  %s:%d  unbalanced `SettingsScaffold(` argument list (fail-closed: "
                "re-derive this criterion)" % (rel, line_no))
            continue
        value, _voff = arg_value_by_key(args, 0, len(args), "onBack")
        kind = onback_value_kind(value) if value is not None else "absent"
        has_nav = arg_value_by_key(args, 0, len(args), "navigation")[0] is not None
        if kind not in ("null", "false", "absent"):
            non_null += 1
        idx = line_no - 1
        encl = [f for f in funcs if f[1] <= idx < f[3]]
        owner = min(encl, key=lambda x: x[3] - x[1])[0] if encl else "?"
        handlers = []
        for f in sorted(encl, key=lambda x: x[3] - x[1]):
            handlers.extend(_handler_params(_function_params(text, offs[f[1]])))
        if not handlers:
            continue       # no handler in scope -> the documented top-level shape
        key = (rel, owner)
        if kind in ("absent", "null", "false"):
            if has_nav:
                continue
            if key in TOP_LEVEL_NULL_SITES:
                matched.add(key)
                continue
            viols.append(
                "B01-CALLS  %s:%d  passes `onBack = null` (and no `navigation` slot) while this "
                "screen's own `onBack` handler is in scope — the back arrow disappears and a "
                "drill-down screen is left with no visible way out" % (rel, line_no))
            continue
        # A present, non-null value can still DISCARD the handler: `{}` renders
        # an arrow that does nothing (external witness B01-CALLS wide4), and so
        # does any expression that never mentions the in-scope handler.
        if kind in ("empty", "empty-lambda") or not any(
                re.search(r"\b%s\b" % re.escape(h), value) for h in handlers):
            viols.append(
                "B01-CALLS  %s:%d  discards in-scope `onBack` handler (passes `%s` instead of "
                "wiring `%s`) — the back arrow renders but the tap does nothing"
                % (rel, line_no, value.strip()[:48], handlers[0]))
    return viols, non_null, total, matched


def collect(root):
    """(ime violations, scaffold violations, scaffold file seen, live handlers)."""
    violations = []
    scaffold_violations = []
    scaffold_seen = False
    onback_calls = 0
    sites_total = 0
    seen_keys = set()
    for sr in SRC_ROOTS:
        for base, _dirs, files in os.walk(os.path.join(root, sr)):
            for fn in sorted(files):
                if not fn.endswith(".kt"):
                    continue
                path = os.path.join(base, fn)
                rel = os.path.relpath(path, os.path.join(root, sr)).replace(os.sep, "/")
                text, reason = project(path)
                if text is None:
                    # fail-closed: a file the shared lexer cannot parse must never
                    # read as "clean" (that is exactly how the parser-death axis
                    # got in: the stripper gave up and the gate judged the remains).
                    violations.append(
                        "%s  cannot lex the file (%s) — the gate refuses to judge on a "
                        "broken lexer view" % (rel, reason))
                    continue
                for line_no, fname, criterion in scan_file(text, rel):
                    violations.append("%s:%d  in %s()  [%s]" % (rel, line_no, fname, criterion))
                # [deep-audit-1040 B-01] the shared settings host's navigation
                # affordance (see the block comment above the criteria).
                if rel == SCAFFOLD_REL:
                    scaffold_seen = True
                    for cid, ok, detail in check_scaffold_navigation(text):
                        if not ok:
                            scaffold_violations.append("%s  %s" % (cid, detail))
                if SCAFFOLD_CALL_RE.search(text):
                    v, n, total, matched = audit_scaffold_callsites(text, rel)
                    scaffold_violations.extend(v)
                    onback_calls += n
                    sites_total += total
                    seen_keys |= matched

    # The call-site floor only applies when the host file is actually in the tree:
    # test_scan.py builds single-file fixture trees (no SettingsComponents.kt) and
    # a missing host is not this gate's business — an unconditional floor would
    # red every one of those exit-0 cases.
    # ROT check only at production scale: test_scan/selftest build one-file trees
    # where none of the registry entries can appear, and a missing entry there is
    # not evidence that the screen changed.
    if sites_total >= MIN_ONBACK_CALLSITES + 2:
        for key in sorted(TOP_LEVEL_NULL_SITES):
            if key in seen_keys:
                continue
            scaffold_violations.append(
                "B01-CALLS  registry entry %s:%s no longer matches an `onBack = null` site "
                "(ROT: the screen changed shape — re-derive the exemption instead of "
                "inheriting it, or delete the entry)" % key)

    if scaffold_seen and sites_total >= MIN_ONBACK_CALLSITES + 2 \
            and onback_calls < MIN_ONBACK_CALLSITES:
        scaffold_violations.append(
            "B01-CALLS  only %d production `SettingsScaffold(...)` call site(s) pass a non-null "
            "`onBack` (floor %d) — the anchor stopped matching (re-derive this criterion), or "
            "screens lost their back handler" % (onback_calls, MIN_ONBACK_CALLSITES))
    return violations, scaffold_violations, scaffold_seen, onback_calls


def report(violations, scaffold_violations, scaffold_seen, onback_calls):
    if scaffold_violations:
        print(f"❌ SettingsScaffold navigation guard: {len(scaffold_violations)} violation(s)")
        print("   The slot used to honour only the custom `navigation` parameter, so every")
        print("   screen passing just `onBack` rendered an empty navigation slot: no back")
        print("   arrow, and a user who does not know the system back gesture has no way out.")
        print("   Fix: keep both arms of the `when` — `navigation != null -> navigation()`")
        print("        and `onBack != null -> IconButton(onClick = onBack)`.")
        for v in scaffold_violations:
            print("     " + v)
        return 1

    if violations:
        print(f"❌ IME-inset guard: {len(violations)} violation(s)")
        print("   A text field is hosted by a bare Scaffold (contentWindowInsets =")
        print("   systemBars only) with no `.imePadding()` anywhere in the")
        print("   composable. Under edge-to-edge the keyboard covers the field and")
        print("   the caret scrolls behind it — silent, no crash, no test failure.")
        print("   Fix: add `.imePadding()` AFTER `.padding(padding)` on the host")
        print("        (host layer, not the leaf — leaves inside ModalBottomSheet")
        print("        would double up).")
        print("   Or, if intentional, mark it inline:")
        print("        `// ime-ok: <reason>`            (unprotected on purpose)")
        print("        `// ime-unreachable: <reason>`   (branches are exclusive)")
        for v in violations:
            print("     " + v)
        return 1
    print("✅ IME-inset guard: no unprotected text field in a bare-Scaffold host")
    if scaffold_seen:
        print(f"✅ SettingsScaffold navigation guard: {onback_calls} call site(s) pass `onBack`, "
              "and the slot falls back to a back arrow")
    return 0


# ── selftest ─────────────────────────────────────────────────────────────────
#
# Arms pin the four external witnesses of this wave (multi-line block comment,
# dead-guarded Spacer, dead-guarded back-arrow arm, discarded in-scope handler)
# plus the shapes that must stay green (real fix, leaf-level editor, hoisted
# modifier, escape hatch, top-level screen without an `onBack` parameter).

CLEAN_SCREEN = '''@Composable
private fun CleanScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            OutlinedTextField(value = "", onValueChange = {})
        }
    }
}
'''

SLOT_GOOD = '''@Composable
fun SettingsScaffold(title: String, onBack: (() -> Unit)?, navigation: (@Composable () -> Unit)?) {
    val navigationSlot: @Composable () -> Unit = {
        when {
            navigation != null -> navigation()
            onBack != null -> IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
        }
    }
    TopAppBar(navigationIcon = navigationSlot)
}
'''

CALLSITE_GOOD = '''@Composable
fun ChatTuningScreen(onBack: () -> Unit) {
    SettingsScaffold(
        title = "t",
        onBack = onBack,
    ) { }
}
'''


def _selftest_tree(files):
    root = tempfile.mkdtemp(prefix="ime-inset-selftest-")
    try:
        for rel, body in files.items():
            p = os.path.join(root, SRC_ROOTS[0], rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(body)
        return collect(root)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def selftest():
    pkg = "com/rikkaminis/app/ui"
    arms = []
    failures = []

    def arm(name, files, expect_ime, expect_scaffold, needle=None):
        v, sv, _seen, _n = _selftest_tree(files)
        ok = (len(v) == expect_ime) and (len(sv) == expect_scaffold)
        # `needle` pins WHICH criterion fired, so an arm cannot pass because a
        # different criterion happened to be red (a fixture that drifts must not
        # silently re-point an arm at somebody else's rule).
        if ok and needle is not None:
            ok = any(needle in msg for msg in (v + sv))
        arms.append((name, ok, "ime=%d/%d scaffold=%d/%d"
                     % (len(v), expect_ime, len(sv), expect_scaffold)))
        if not ok:
            failures.append("%s: %r %r" % (name, v, sv))

    # 1. the documented fix shape stays green
    arm("clean container padding", {pkg + "/Clean.kt": CLEAN_SCREEN}, 0, 0)

    # 2. IME-HOST parser-death: padding wrapped in a MULTI-LINE block comment
    multiline = CLEAN_SCREEN.replace(
        "                .imePadding(),\n",
        "                /* keyboard handling temporarily disabled\n"
        "                .imePadding()\n"
        "                */\n")
    arm("multi-line block comment blinds padding", {pkg + "/W1.kt": multiline}, 1, 0)

    # 3. IME-HOST anchor: padding moved into a literal-false guard
    dead_spacer = CLEAN_SCREEN.replace(
        "                .imePadding(),\n", "").replace(
        '            OutlinedTextField(value = "", onValueChange = {})',
        '            if (false) { Spacer(Modifier.imePadding()) }\n'
        '            OutlinedTextField(value = "", onValueChange = {})')
    arm("dead-guarded Spacer is not protection", {pkg + "/W2.kt": dead_spacer}, 1, 0)

    # 4. same anchor, no literal needed: a 0dp Spacer is not a host
    spacer_only = CLEAN_SCREEN.replace(
        "                .imePadding(),\n", "").replace(
        '            OutlinedTextField(value = "", onValueChange = {})',
        '            Spacer(Modifier.imePadding())\n'
        '            OutlinedTextField(value = "", onValueChange = {})')
    arm("Spacer-wrapped padding is not protection", {pkg + "/W3.kt": spacer_only}, 1, 0)

    # 5. leaf-level editor padding (real shape: SkillsManagementScreen) stays green
    leaf = CLEAN_SCREEN.replace(
        "                .imePadding(),\n", "").replace(
        '            OutlinedTextField(value = "", onValueChange = {})',
        '            OutlinedTextField(\n'
        '                value = "",\n'
        '                onValueChange = {},\n'
        '                modifier = Modifier.fillMaxSize().imePadding(),\n'
        '            )')
    arm("leaf-level BasicTextField padding stays green", {pkg + "/Leaf.kt": leaf}, 0, 0)

    # 6. hoisted modifier (real shape: SettingsComponents baseMod) stays green
    hoisted = CLEAN_SCREEN.replace(
        "        Column(\n"
        "            modifier = Modifier\n"
        "                .fillMaxSize()\n"
        "                .padding(padding)\n"
        "                .imePadding(),\n"
        "        ) {",
        "        val baseMod = Modifier\n"
        "            .fillMaxSize()\n"
        "            .padding(padding)\n"
        "            .imePadding()\n"
        "        Column(modifier = baseMod) {")
    arm("hoisted `val baseMod` stays green", {pkg + "/Hoist.kt": hoisted}, 0, 0)

    # 7. escape hatch survives its own stripper
    escaped = CLEAN_SCREEN.replace("                .imePadding(),\n", "").replace(
        '            OutlinedTextField(value = "", onValueChange = {})',
        '            // ime-ok: independent Window, platform pans the field\n'
        '            OutlinedTextField(value = "", onValueChange = {})')
    arm("ime-ok escape hatch stays green", {pkg + "/Esc.kt": escaped}, 0, 0)

    # 8. B01-SLOT: the real slot renders a live back arrow
    arm("B01 slot renders back arrow",
        {"com/rikkaminis/app/ui/settings/SettingsComponents.kt": SLOT_GOOD}, 0, 0)

    # 9. B01-SLOT witness: `onBack != null -> if (false) IconButton(onClick = onBack)`
    slot_dead = SLOT_GOOD.replace(
        "onBack != null -> IconButton(onClick = onBack) {",
        "onBack != null -> if (false) { IconButton(onClick = onBack) {").replace(
        "            }\n        }\n    }",
        "            } }\n        }\n    }")
    arm("B01 dead-guarded back arrow is caught",
        {"com/rikkaminis/app/ui/settings/SettingsComponents.kt": slot_dead}, 0, 1)

    # 10. B01-CALLS: a drill-down screen discards its own handler
    callsite_null = CALLSITE_GOOD.replace("onBack = onBack,", "onBack = null,")
    arm("B01 call site discards in-scope onBack", {pkg + "/Chat.kt": callsite_null}, 0, 1)

    # 11. B01-CALLS: the same screen wiring it through stays green
    arm("B01 call site passes the handler", {pkg + "/Chat.kt": CALLSITE_GOOD}, 0, 0)

    # 12. B01-CALLS: a REGISTERED top-level page (handler in scope, empty slot by
    #     design) stays green — the registry is the only exemption surface.
    registered = '''@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    SettingsScaffold(title = "t", onBack = null) { }
}
'''
    arm("B01 registered top-level null stays green",
        {"com/rikkaminis/app/ui/settings/AppearanceScreen.kt": registered}, 0, 0)

    # 13. B01-CALLS: a top-level page (no own onBack param) keeps its empty slot
    top_level = '''@Composable
fun MemoryManagementScreen() {
    SettingsScaffold(title = "t", onBack = null) { }
}
'''
    arm("B01 top-level null stays green", {pkg + "/Top.kt": top_level}, 0, 0)

    # ── wide4 arms: every FALSIFIED witness of this wave, pinned ────────────
    # Each one used to be a green gate over a real defect; the arm asserts the
    # gate now goes red on exactly that patch (and nothing else).

    # w4-1 (family-hole) hoisted modifier that is mentioned twice but never
    # handed to a host: `val dead = Modifier.imePadding()` + `val _ignore = dead`
    w4_hoist = CLEAN_SCREEN.replace(
        "        Column(\n",
        "        val deadMod = Modifier.imePadding()\n"
        "        val _ignore = deadMod\n"
        "        Column(\n").replace(
        "                .imePadding(),\n", "                ,\n")
    arm("w4 hoisted-but-unattached modifier is caught", {pkg + "/W4a.kt": w4_hoist}, 1, 0)

    # w4-2 (family-hole) a present, non-null onBack that DISCARDS the handler
    w4_calls = CALLSITE_GOOD.replace("onBack = onBack,", "onBack = {},")
    arm("w4 onBack = {} discards handler is caught", {pkg + "/W4b.kt": w4_calls}, 0, 1,
        needle="discards in-scope `onBack` handler")

    # w4-3 (family-hole) discarding onClick chain in the slot arm
    slot_chain = SLOT_GOOD.replace(
        "onBack != null -> IconButton(onClick = onBack) {",
        "onBack != null -> IconButton(onClick = onBack.takeIf { false } ?: {}) {")
    arm("w4 onClick = onBack.takeIf{false} ?: {} is caught",
        {"com/rikkaminis/app/ui/settings/SettingsComponents.kt": slot_chain}, 0, 1,
        needle="no LIVE `IconButton(onClick = onBack)`")

    # w4-4 (family-hole) padding inside a discarded lambda result: `.let { if (1 == 2) … }`
    w4_let = CLEAN_SCREEN.replace(
        "                .imePadding(),\n",
        "                .let { if (1 == 2) it.imePadding() else it },\n")
    arm("w4 padding in a discarded `.let` result is caught", {pkg + "/W4c.kt": w4_let}, 1, 0)

    # w4-5 (parser-death) NESTED block comment around the padding
    w4_nested = CLEAN_SCREEN.replace(
        "                .imePadding(),\n",
        "                /* outer /* inner */ .imePadding() */\n")
    arm("w4 nested block comment blinds padding", {pkg + "/W4d.kt": w4_nested}, 1, 0)

    # w4-6 (CAUGHT witness, kept honest): compound literal short-circuit
    w4_short = CLEAN_SCREEN.replace(
        "                .imePadding(),\n", "").replace(
        '            OutlinedTextField(value = "", onValueChange = {})',
        '            if (false && isEditing) { Box(Modifier.imePadding()) {} }\n'
        '            OutlinedTextField(value = "", onValueChange = {})')
    arm("w4 `false && x` dead guard stays caught", {pkg + "/W4e.kt": w4_short}, 1, 0)

    # w4-7 (registry axis) `when (false)` inside the slot arm renders nothing
    slot_when = SLOT_GOOD.replace(
        "onBack != null -> IconButton(onClick = onBack) {",
        "onBack != null -> when (false) { true -> IconButton(onClick = onBack) {").replace(
        "            }\n        }\n    }",
        "            } }\n        }\n    }")
    arm("w4 `when (false)` back arrow is caught",
        {"com/rikkaminis/app/ui/settings/SettingsComponents.kt": slot_when}, 0, 1,
        needle="no LIVE `IconButton(onClick = onBack)`")

    # w4-8 clean shapes that must stay green: the handler wired through a lambda
    calls_lambda = CALLSITE_GOOD.replace("onBack = onBack,", "onBack = { onBack() },")
    arm("w4 `onBack = { onBack() }` stays green", {pkg + "/W4f.kt": calls_lambda}, 0, 0)

    for name, ok, detail in arms:
        print("  SELFTEST %-44s %s (%s)" % (name, "OK" if ok else "FAIL", detail))
    if failures:
        print("SELFTEST FAILED (%d arms)" % len(failures))
        for f in failures:
            print("   " + f)
        return 1
    print("IME_INSET_GUARD_SELFTEST=PASS (%d arms)" % len(arms))
    return 0


def main():
    args = [a for a in sys.argv[1:]]
    if "--selftest" in args:
        return selftest()
    if "--root" in args:
        i = args.index("--root")
        args = args[i + 1:i + 2]
    args = [a for a in args if not a.startswith("--")]
    root = os.path.abspath(args[0] if args else ".")
    violations, scaffold_violations, scaffold_seen, onback_calls = collect(root)
    return report(violations, scaffold_violations, scaffold_seen, onback_calls)


if __name__ == "__main__":
    sys.exit(main())
