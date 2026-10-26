#!/usr/bin/env python3
"""[da1017-D1] Gate: never filter CalendarContract.Events on a NULL-able column.

Mechanism the gate protects (measured on-device 2026-10-16, Android 15):
the Calendar provider stores recurring events as RRULE + DURATION and leaves
`Events.DTEND` NULL — it rejects both together (CalendarProvider2.validateEventData:
"Cannot have both DTEND and DURATION in an event"). A provider query whose
selection compares DTEND (`dtend < ?`, `dtend > ?`, `dtend = ?`) is therefore
never true for those rows, and the caller silently reports the *opposite* of the
truth (freebusy answered "free" for a window holding a weekly occurrence).

Rules (all four live in this one script and share its discovery helpers; they
are all "a calendar/photos decision must not silently answer something the
caller did not ask for"):
  A. No SQL predicate (a string literal that couples an Events.DTEND reference
     with a comparison operator and a bind placeholder) may exist anywhere in
     src/android/app/src/main/java, unless the same literal guards NULL
     (`IS NULL`). Predicates must be built on CalendarContract.Instances
     (BEGIN/END), which the provider expands for recurring rows.
  B. The `freebusy` code path of the calendar offload handler must read the
     instances window (Instances.CONTENT_URI + the shared selection helper) and
     must not fall back to Events.

[dv14-A3] additions — two contracts the offload date/permission surface kept
breaking, on the files this batch owns:
  D. `day-window-refusal` — the pure day-window resolver (discovered by SHAPE:
     returns `PhotoRange` / `Pair<Long, Long>` and takes an injected parse
     callback) must carry the raw `--days` string and REFUSE a supplied-but-
     unreadable value (`toIntOrNull() == null`). `getInt("days")` answers null
     both for "flag absent" and for "`--days 7d`": Photos then answered the
     ENTIRE library and Calendar silently narrowed to the last 24 hours. Judged
     on PhotosOffloadHandler.kt and CalendarOffloadHandler.kt only.
  E. `write-permission-parity` — a subcommand entry point (takes
     `args: OffloadArgs`) that writes the calendar provider must gate on BOTH
     `hasRead()` and `hasWrite()` and must request both permissions (every
     calendar write reads the provider first, so WRITE alone asks the user for
     one permission and then fails on another); read-only entry points must not
     ask for WRITE at all.

Same discipline as rules A/B: every criterion is fail-closed (remove the
machinery and the rule goes red rather than silent) and every selftest arm
asserts its OWN rule code, so an arm that trips a neighbour cannot look green.

Run from the repo root: `python3 scripts/scan/calendar_events_nullable_predicate_check.py`
exit 0 = PASS, exit 1 = FAIL (violations printed as path:line).

`--selftest` builds violating + compliant fixtures and asserts the gate's own
teeth (FIX-1015 lesson: a gate that cannot fail is worse than no gate).
"""
import argparse
import os
import re
import shutil
import sys
import tempfile

# [B7-t13] 结构分析一律走共享原语（自写的 strip_comments / 花括号计数已删除）。
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import const_bool, function_body, if_blocks, match_bracket, strip_parens
from _kotlin_valueflow import (call_args, code_view, expr_end, guard_feasible, path_feasible,
                               value_sources)

MAIN_ROOT = os.path.join("src", "android", "app", "src", "main", "java")
HANDLER_REL = os.path.join(
    MAIN_ROOT, "com", "rikkaminis", "app", "sandbox", "offload", "CalendarOffloadHandler.kt"
)
PHOTOS_REL = os.path.join(
    MAIN_ROOT, "com", "rikkaminis", "app", "sandbox", "offload", "PhotosOffloadHandler.kt"
)

# String literals: single-line "..." (no newline) and """...""" blocks.
LITERAL_RE = re.compile(r'"""(?:.|\n)*?"""|"(?:[^"\\\n]|\\.)*"')
COMPARISON_RE = re.compile(r"[<>=!]+\s*[?$]")
NULL_GUARD_RE = re.compile(r"IS\s+NULL", re.IGNORECASE)
# Comments are stripped before the rule-C read-path check: the gate must judge
# the decision point, not prose that merely names it (the handler legitimately
# explains IN a comment why the read path does NOT refuse).
# [B7-t13] 注释剥离改走共享词法器：只剥注释、字符串内容原样保留、长度严格
# 不变（位置可回投原文）。自写正则对 `/*` 出现在字符串里（如 "video/*"）会
# 吞掉后面整段代码。
def strip_comments(src):
    return mask_comments(src)


DTEND_TOKEN_RE = re.compile(r"dtend", re.IGNORECASE)
SQL_BIND_CMP_RE = re.compile(r"[<>=!]+\s*\?")


def literal_groups(view):
    """[(offset, joined_text)] —— 把 `+` 拼接的字面量还原成一条字符串。

    Kotlin 里 `("dtend > " + "?")` 与 `"dtend > ?"` 是同一个值。原判据逐个
    `LITERAL_RE` 匹配，要求同一个字面量里三项齐全，于是把谓词拆成两段就
    整段躲过（[B7-t13] 见证 05-1）。这里先按 `+` 语义合并，再交给规则判。
    """
    spans = [(m.start(), m.end()) for m in LITERAL_RE.finditer(view)]
    groups = []
    cur = []
    for start, end in spans:
        if cur:
            gap = view[cur[-1][1]:start]
            if not re.fullmatch(r"\s*\+\s*", gap):
                groups.append(cur)
                cur = []
        cur.append((start, end))
    if cur:
        groups.append(cur)
    def body_of(tok):
        if tok.startswith('"""'):
            return tok[3:-3] if len(tok) >= 6 else ""
        return tok[1:-1] if len(tok) >= 2 else ""

    return [(g[0][0], "".join(body_of(view[a:b]) for a, b in g)) for g in groups]

RULE_A_CODE = 'predicate-on-nullable-column'
RULE_B_CODE = 'freebusy-reads-instances'
RULE_C_CODE = 'explicit-calendar-selector'
RULE_D_CODE = 'day-window-refusal'
RULE_E_CODE = 'write-permission-parity'
RULE_F_CODE = 'alarm-boundary-symmetry'

# [dv14-A3] Shape-based discovery of `fun` declarations, so the new rules can be
# written against a *decision* instead of a helper's spelling: rename the pure
# core and the rule still finds it.
FUN_RE = re.compile(
    r"^(?P<indent>[ ]*)(?:private |internal |public |protected |override |final |inline |suspend )*"
    r"fun\s+(?P<name>\w+)\s*\("
)
# The two window types the offload date cores return (Photos / Calendar).
# `PhotoRange` carries a refusal case, `Pair<Long, Long>?` uses null for it.
DAYS_WINDOW_RET_RE = re.compile(r"^(?:PhotoRange|Pair<Long, Long>)\??$")
# A function-type parameter (`parse: (String) -> Long?`) — the injected parser of
# a *pure* date core. It is what tells the decision core apart from the thin
# member that merely forwards the parsed args.
INJECTED_PARSER_RE = re.compile(r"->")
DAY_PARAM_RE = re.compile(r"\bdays\w*\s*:\s*String\?")
DAY_RAW_READ_RE = re.compile(r"\bdays\w*\s*=\s*args\.get\(\s*\"days\"\s*\)")
UNREADABLE_DAYS_GUARD_RE = re.compile(r"toIntOrNull\(\)\s*==\s*null")
REFUSAL_RE = re.compile(r"return null|Invalid\(")
ARGS_PARAM_RE = re.compile(r"\bargs\s*:\s*OffloadArgs\b")
WRITE_CALL_RE = re.compile(r"contentResolver\.(?:insert|update|delete)\(")
QUERY_CALL_RE = re.compile(r"contentResolver\.query\(")
# Ends a member's slice: the next declaration at the same or a smaller indent.
# (A plain `fun` test is not enough — a TOP-LEVEL function is followed by an
# indented class declaration, and a slice that ignores it swallows the file.)
DECL_BREAK_RE = re.compile(
    r"^(?:/\*\*|@|(?:private|internal|public|protected|override|final|inline|suspend|abstract|open|sealed|data|enum|annotation) )*"
    r"(?:fun|class|object|interface|val|var|companion)\b"
)


def fun_decls(src):
    """Every `fun` in [src] as a dict: name / indent / sig / ret / body / line.

    The body runs to the next declaration at the same or a smaller indent, so a
    member's slice never swallows its siblings. Discovery only — no name lists.
    """
    lines = src.split("\n")
    decls = []
    i = 0
    while i < len(lines):
        m = FUN_RE.match(lines[i])
        if not m:
            i += 1
            continue
        indent = len(m.group("indent"))
        sig = lines[i]
        depth = sig.count("(") - sig.count(")")
        j = i
        while depth > 0 and j + 1 < len(lines):
            j += 1
            sig += "\n" + lines[j]
            depth += lines[j].count("(") - lines[j].count(")")
        ret_m = re.search(r"\)\s*:\s*([^={\n]+)", sig)
        body_lines = []
        k = j + 1
        while k < len(lines):
            line = lines[k]
            stripped = line.strip()
            if stripped and (len(line) - len(line.lstrip(" "))) <= indent and DECL_BREAK_RE.match(stripped):
                break
            body_lines.append(line)
            k += 1
        decls.append({
            "name": m.group("name"),
            "indent": indent,
            "sig": sig,
            "ret": (ret_m.group(1).strip() if ret_m else ""),
            "body": "\n".join(body_lines),
            "line": i + 1,
        })
        i = k
    return decls


# ── [B7-t13] 共享结构与值流辅助 ────────────────────────────────────────────
FUN_DECL2_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(")


def code_of(src):
    """结构视图 —— 共享词法器（注释 + 字面量内容抹成空格，长度不变）。"""
    return code_view(src)


def lit_of(src):
    """字面量视图 —— 共享 `mask_comments`（只剥注释；读 SQL / 参数名 / key 用）。"""
    return strip_comments(src)


def all_fun_regions(code):
    """[{name, head, start, body_start, end}] —— 每个 `fun`（块体与表达式体都算）。

    `function_body` 只给第一个同名函数且不给签名，判据需要"签名 + 绝对位置"
    （`args: OffloadArgs`、返回类型、行号），因此在共享 `match_bracket` /
    `expr_end` 之上做这层发现 —— 括号配平仍然只有一份实现。
    """
    out = []
    for m in FUN_DECL2_RE.finditer(code):
        p_close = match_bracket(code, m.end() - 1)
        if p_close < 0:
            continue
        i = p_close + 1
        while i < len(code) and code[i] not in "={":
            if code[i] == "\n":
                i = -1
                break
            i += 1
        if i < 0 or i >= len(code):
            continue
        end = match_bracket(code, i) if code[i] == "{" else expr_end(code, i + 1)
        if end < 0:
            continue
        out.append({"name": m.group(1), "head": code[m.start():i],
                    "start": m.start(), "body_start": i + 1, "end": end})
    return out


def _line(text, offset):
    """1-based 行号（offset 必须落在同一个视图里，共享词法器长度不变）。"""
    return text.count("\n", 0, max(0, offset)) + 1


def ret_of(head):
    """签名里的返回类型（`) : T` 的 T）；没有则空串。"""
    m = re.search(r"\)\s*:\s*([^={\n]+)$", head.strip())
    return m.group(1).strip() if m else ""


def _short(text, limit=96):
    t = " ".join(str(text).split())
    return t if len(t) <= limit else t[:limit] + "…"


DECL_RE = re.compile(r"\b(?:val|var)\s+(\([^()]*\)|[A-Za-z_]\w*)\s*(?::[^=\n]*?)?=(?!=)")


def _binding_rhs(code, lit, name, before):
    """name 在 before 之前最后一次绑定的右值（含解构声明 `val (a, b) = …`）。"""
    best = None
    for m in DECL_RE.finditer(code, 0, before):
        lhs = m.group(1)
        if lhs.startswith("("):
            names = [x.split(":")[0].strip() for x in lhs[1:-1].split(",")]
        else:
            names = [lhs]
        if name in names:
            best = m
    if best is None:
        return None
    s = best.end()
    return lit[s:expr_end(code, s)].strip()


def _all_sources(code, lit, seg_start, seg_end, depth=0):
    """表达式**真正求值**的所有来源；局部标识符沿一跳绑定继续解析。

    [B7-t13] DATAFLOW 轴：判据原本只问"某个子串在不在函数体里"，于是
    `val _unused = Instances.CONTENT_URI` + `query(eventsUri, …)` 全绿 ——
    Instances 在，查询不在。这里把调用的实参回溯到取值来源：解构声明的每一
    项取同一条右值，`?.let { … }` 取 lambda 尾表达式（`value_sources`）。
    """
    expr = lit[seg_start:seg_end]
    out = []
    for src_expr in value_sources(expr):
        s = src_expr.strip()
        m = re.fullmatch(r"([A-Za-z_]\w*)", s)
        if m and depth < 2:
            rhs = _binding_rhs(code, lit, m.group(1), seg_start)
            if rhs:
                out.extend(x.strip() for x in value_sources(rhs))
                continue
        out.append(s)
    return out


def _satisfied_bodies(code, start, end):
    """[(pos, lambda_body)] for every `satisfied = { … }` inside [start, end)."""
    out = []
    for m in re.finditer(r"\bsatisfied\s*=\s*\{", code[start:end]):
        pos = start + m.start()
        brace = start + m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            continue
        out.append((pos, code[brace + 1:close]))
    return out


def rule_d(src):
    """[dv14-A3] ISSUE-05 — a supplied-but-unreadable `--days` must be REFUSED.

    [B7-t13] FAMILY-HOLE 轴：判据原本只做**子串存在性**检查 —— 看见
    `toIntOrNull() == null` 与 `return null` 就绿。给条件补一个 `&& false`
    （受检子串一个不少，拒绝分支成了死代码）后，`android-calendar list --days 7d`
    静默降级成默认 24 小时窗口，门全绿。现在用共享 `if_blocks` + `guard_feasible`
    判定条件**是否可行**、用 `path_feasible` 判定拒绝**是否可达**：恒假守卫、
    被死分支包住的 return 都不再算数。
    """
    code = code_of(src)
    lit = lit_of(src)
    # 没提 `--days` 的文件没有这个面（rule A/B 的最小夹具刻意不含它）。
    if '"days"' not in lit:
        return []
    problems = []
    regions = [r for r in all_fun_regions(code)
               if DAYS_WINDOW_RET_RE.match(ret_of(r["head"])) and "->" in r["head"]]
    if not regions:
        return ["no pure day-window resolver found (a function returning PhotoRange / Pair<Long, Long> with an "
                "injected parse callback) — the --days decision has no core to hold the refusal"]
    if not DAY_RAW_READ_RE.search(lit):
        problems.append("the handler never reads the RAW --days string (args.get(\"days\")) — getInt() alone "
                        "cannot tell 'flag absent' from 'supplied but unreadable'")
    for name in sorted({r["name"] for r in regions}):
        members = [r for r in regions if r["name"] == name]
        with_param = [r for r in members if DAY_PARAM_RE.search(r["head"])]
        decl_line = _line(code, members[0]["start"])
        if not with_param:
            problems.append(f"{name} (line {decl_line}) takes no raw `days…: String?` parameter — an unreadable "
                            "--days stays indistinguishable from an absent one")
            continue
        guarded = False
        refusal_missing = False
        for r in with_param:
            body = code[r["body_start"]:r["end"]]
            for cond, t_start, t_end, _es, _ee in if_blocks(body):
                if not UNREADABLE_DAYS_GUARD_RE.search(cond):
                    continue
                where = _line(code, r["body_start"] + t_start)
                if not guard_feasible(cond):
                    problems.append(
                        f"{name} (line {where}) caps the unreadable --days guard with an always-false term "
                        f"({_short(cond, 70)}) — the refusal is dead code, so `--days 7d` degrades silently "
                        "to the default window")
                    guarded = True
                    continue
                seg = body[t_start:t_end]
                m_ref = re.search(r"\breturn\b(?!\s*@)", seg)
                if m_ref and path_feasible(body, t_start + m_ref.start()):
                    guarded = True
                else:
                    refusal_missing = True
                    problems.append(f"{name} (line {where}) detects an unreadable --days but the branch never "
                                    "returns a refusal (null / Invalid)")
        if not guarded and not refusal_missing:
            problems.append(f"{name} (line {decl_line}) has no `toIntOrNull() == null` guard — a non-integer "
                            "--days is not refused")
    return problems


def rule_e(src):
    """[dv14-A3] ISSUE-06 — the write paths need READ as well as WRITE.

    [B7-t13] FAMILY-HOLE 轴：判据原本用 `satisfied\\s*=\\s*\\{[^}]*hasRead\\(\\)`
    找子串 —— 花括号内出现 `hasRead()` / `hasWrite()` 就绿，于是
    `satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) || true }`
    让 `ensurePermission` 恒返回"已授权"：没有权限时不再申请，直接
    `contentResolver.delete` 抛 SecurityException 并误报成 provider_blocked。
    现在取出 lambda 的**整个表达式体**做常量折叠（`const_bool` 折 `x || true`），
    并把 `hasRead`/`hasWrite` 的检查放在同一个 lambda 体里判。
    """
    code = code_of(src)
    problems = []
    for r in all_fun_regions(code):
        if not ARGS_PARAM_RE.search(r["head"]):
            continue
        body = code[r["body_start"]:r["end"]]
        line = _line(code, r["start"])
        if WRITE_CALL_RE.search(body):
            lambdas = _satisfied_bodies(code, r["body_start"], r["end"])
            if not lambdas:
                problems.append(f"{r['name']} (line {line}) writes the calendar provider but has no "
                                "`satisfied = { … }` permission predicate")
            for pos, lam in lambdas:
                where = _line(code, pos)
                if const_bool(lam) is True:
                    problems.append(
                        f"{r['name']} (line {where}) `satisfied` predicate is constantly TRUE "
                        f"({_short(lam, 80)}) — ensurePermission answers 'granted' on every call, so a "
                        "missing calendar permission surfaces later as a provider SecurityException "
                        "instead of a permission request")
                    continue
                if not re.search(r"hasRead\s*\(", lam) or not re.search(r"hasWrite\s*\(", lam):
                    problems.append(f"{r['name']} (line {where}) `satisfied` predicate does not check BOTH "
                                    "hasRead() and hasWrite()")
            if "READ_CALENDAR" not in body or "WRITE_CALENDAR" not in body:
                problems.append(f"{r['name']} (line {line}) requests a permission set that does not match "
                                "what it decides on (both READ_CALENDAR and WRITE_CALENDAR must be requested)")
        elif QUERY_CALL_RE.search(body):
            if "WRITE_CALENDAR" in body or "needsWrite = true" in body:
                problems.append(f"{r['name']} (line {line}) is a read-only subcommand but asks for / gates on "
                                "WRITE — the read paths must stay minimum-permission")
    return problems


def iter_kotlin_files(root):
    for dirpath, _dirnames, filenames in os.walk(os.path.join(root, MAIN_ROOT)):
        for name in sorted(filenames):
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def line_of(src, index):
    return src.count("\n", 0, index) + 1


def rule_a(src):
    """Predicates shaped on a NULL-able DTEND column (any spelling).

    [B7-t13] SPELLING + parser-death 轴：判据原本逐个 `LITERAL_RE` 匹配，要求
    **同一个**字面量里同时出现 `CalendarContract.Events.DTEND`、比较算子与
    `?`。于是

        val (selection, selArgs) = instancesWindowSelection(startMs, endMs)
            .let { ("dtend > " + "?") to arrayOf(startMs.toString()) }

    三个面一起破：常量引用换成 SQL 物理列名（`dtend` 与常量的值完全等价）、
    算子与占位符被拆进两个字面量、谓词本身藏在 `.let` 里。判据改成先把
    「`+` 拼接的字面量组」还原成一条字符串（Kotlin 的常量拼接语义），再对
    还原后的字符串做大小写无关的列名 / 比较 / 绑定占位符判定。
    """
    hits = []
    for offset, text in literal_groups(strip_comments(src)):
        if not DTEND_TOKEN_RE.search(text):
            continue
        if NULL_GUARD_RE.search(text):
            continue
        if not SQL_BIND_CMP_RE.search(text):
            continue
        hits.append((line_of(src, offset), text.strip()[:120]))
    return hits


def member_slice(src, marker):
    """Lines of the member declared at [marker], up to the next sibling member."""
    lines = src.split("\n")
    start = next((i for i, l in enumerate(lines) if marker in l), None)
    if start is None:
        return None
    for i in range(start + 1, len(lines)):
        line = lines[i]
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()
        if (
            0 < indent <= 4
            and stripped
            and not stripped.startswith("//")
            and not stripped.startswith("*")
            and re.match(r"^(private|internal|public|protected|fun|val|var|@)", stripped)
        ):
            return "\n".join(lines[start:i])
    return "\n".join(lines[start:])


def brace_slice(src, marker):
    """`src` from [marker] to the `}` that balances the first `{` after it.

    [B7-t13] 配平改走共享 `match_bracket`（自写的深度计数遇到字符串里的花括号
    就错位；且原实现的 `find("{")` 可能越过参数默认值直接咬到后面的块）。
    """
    at = src.find(marker)
    if at < 0:
        return None
    open_at = src.find("{", at)
    if open_at < 0:
        return None
    close = match_bracket(src, open_at)
    if close < 0:
        return None
    return src[at:close + 1]


INSTANCES_URI_RE = re.compile(r"CalendarContract\s*\.\s*Instances\s*\.\s*CONTENT_URI")
EVENTS_URI_RE = re.compile(r"CalendarContract\s*\.\s*Events\s*\.\s*CONTENT_URI|com\.android\.calendar/events")


def rule_b(src):
    """The freebusy path must READ the instances window, not merely mention it.

    [B7-t13] DATAFLOW 轴：判据原本是两条子串存在性 —— 函数体里有
    `CalendarContract.Instances.CONTENT_URI`、没有
    `CalendarContract.Events.CONTENT_URI`。于是

        val _unused = CalendarContract.Instances.CONTENT_URI
        val eventsUri = android.net.Uri.parse("content://com.android.calendar/events")
        context.contentResolver.query(eventsUri, projection, selection, selArgs, sort)

    两条都满足：Instances 在（没人用），Events 只以硬编码字符串形式出现。
    查询实际落回未展开 RRULE 的 Events 表 → freebusy 对所有周期性日程漏报。
    现在判 query() 的**实参值流**：uri 必须派生自 Instances.CONTENT_URI（且不得
    是 Events 表 / 硬编码 events 路径），selection 必须派生自
    `instancesWindowSelection(…)`（被 `.let { … }` 换掉同样算换掉）。
    """
    code = code_of(src)
    lit = lit_of(src)
    fb = function_body(code, "doFreebusy")
    if fb is None:
        return []
    body_start, body_end, _kind = fb
    problems = []
    queries = [m for m in re.finditer(r"contentResolver\s*\.\s*query\s*\(", code)
               if body_start <= m.start() < body_end]
    if not queries:
        problems.append("freebusy never queries the provider — the instances read path is gone")
    for m in queries:
        args = call_args(code, m.end() - 1)
        if len(args) < 3:
            problems.append("freebusy query() call carries fewer than 3 arguments — uri/selection cannot be verified")
            continue
        uri_src = _all_sources(code, lit, *args[0])
        if not any(INSTANCES_URI_RE.search(s) for s in uri_src):
            problems.append(
                "freebusy queries a uri whose value does not derive from "
                "CalendarContract.Instances.CONTENT_URI (got %r) — an unused Instances reference "
                "elsewhere in the method does not make the query read instances" % _short(" ".join(uri_src)))
        if any(EVENTS_URI_RE.search(s) for s in uri_src):
            problems.append("freebusy falls back to the Events table (got %r)" % _short(" ".join(uri_src)))
        sel_src = _all_sources(code, lit, *args[2])
        if not any("instancesWindowSelection(" in s for s in sel_src):
            problems.append(
                "the selection handed to query() does not derive from instancesWindowSelection(…) "
                "(got %r) — the shared window predicate was replaced or rewritten" % _short(" ".join(sel_src)))
    body_lit = lit[body_start:body_end]
    if "CalendarContract.Events.CONTENT_URI" in body_lit:
        problems.append("freebusy queries CalendarContract.Events.CONTENT_URI")
    if "CalendarContract.Events.DTEND" in body_lit:
        problems.append("freebusy filters on CalendarContract.Events.DTEND (NULL for recurring events)")
    return problems


def rule_c(src):
    """[da1034-A4] ISSUE-07 — a WRITE that NAMES a calendar must refuse, not
    silently write somewhere else.

    `--calendar <name>` / `--calendar-id <id>` used to fall through to the
    auto-pick heuristic when it matched no writable calendar, so `create` wrote
    into a calendar the caller never named and `update` MOVED an existing event
    into the auto-picked one. Scoped to the handler file (like rule_b) and gated
    on the write surface being present, so the older minimal freebusy fixtures
    stay unaffected.

    Every criterion is anchored on a decision point rather than a spelling, and
    each is fail-closed: rename the machinery away and the rule goes red instead
    of going silent.
    """
    if "fun doCreate(" not in src and "fun doUpdate(" not in src:
        return []
    problems = []
    if "resolveExplicitCalendar(" not in src:
        problems.append("resolveExplicitCalendar() is gone — the explicit-selector decision has no single source")
    if not re.search(r"CalendarSelection\.Unmatched\s*->\s*return CalendarTarget\.Refused\(", src):
        problems.append("no branch turns CalendarSelection.Unmatched into a refusal — a selector miss is"
                        " either undecided or falls through to the auto-pick (`-> Unit` also lands here)")
    if "CalendarTarget.Refused(" not in src:
        problems.append("CalendarTarget.Refused( is never constructed — a selector miss does not become a refusal")
    if "calendar_not_found" not in src:
        problems.append("no calendar_not_found payload — the refusal is not structured")
    if "pickWritableCalendar()" in src:
        problems.append("pickWritableCalendar() is called with no calendars — the old silent auto-pick fallback")
    if "falling back to auto-pick" in src:
        problems.append("the 'falling back to auto-pick' log line is back")
    for marker, resolver in (("private fun doCreate(", "requireCalendarTarget("),
                             ("private fun doUpdate(", "optionalCalendarTarget(")):
        body = member_slice(src, marker)
        if body is None:
            problems.append(f"{marker[:-1]} not found — the write surface moved")
        elif resolver not in body:
            problems.append(f"{marker[:-1]} does not route through {resolver}")
    listing = member_slice(src, "private fun doList(")
    if listing is not None:
        listing = strip_comments(listing)
        if ("calendar_not_found" in listing or "CalendarTarget.Refused(" in listing
                or "CalendarSelection.Unmatched" in listing):
            problems.append("doList refuses an unmatched --calendar — on the read path a miss is a"
                            " legitimate empty answer, not a typo to refuse")
    # [dv15-A2] A-03: "the flag was not written" and "the flag was written but is
    # not an id" must be DIFFERENT answers. The pre-fix shape answered the first
    # with a raw existence test and the second with getLong(), so
    # `--calendar-id work` read as "written" (update therefore treated the
    # selector as explicit) while parsing to null (create saw no selector at
    # all) — and BOTH paths silently used, or MOVED the event to, the auto-picked
    # calendar with exit 0.
    # Every criterion is anchored on that decision, not on a spelling: the raw
    # string must be read, parsed as a NULLABLE number, and its unparseable case
    # must carry a refusal of its own that reaches the write path. The two names
    # this rule shares with the production code (resolveCalendarSelector() /
    # CalendarSelector.Unreadable) follow the same convention as
    # resolveExplicitCalendar() above — they are written into the handler AND
    # into this gate, so renaming one without the other is a deliberate gate
    # edit rather than a silent pass.
    # Judged on CODE, not prose (same reason strip_comments is used for the
    # read-path check above): the handler legitimately explains the old shape in
    # a comment, and a gate that trips on its own documentation pushes the next
    # reader to delete the explanation instead of keeping it.
    code = strip_comments(src)
    if not re.search(r'get\(\s*"calendar-id"\s*\)', code):
        problems.append("the raw --calendar-id string is never read — getLong() alone cannot tell"
                        " 'not written' from 'written but not a number'")
    if "toLongOrNull()" not in code:
        problems.append("no nullable parse of the raw --calendar-id — an unparseable value collapses"
                        " into the same null as 'not written'")
    if "calendar_bad_selector" not in code:
        problems.append("no calendar_bad_selector payload — a written-but-unparseable selector has no"
                        " refusal of its own (refuse it, do not guess a display name)")
    target = member_slice(src, "private fun requireCalendarTarget(")
    if target is None:
        problems.append("requireCalendarTarget not found — the write-path selector decision moved")
    elif not re.search(r"CalendarSelector\.Unreadable[\s\S]{0,240}?return CalendarTarget\.Refused\(",
                       strip_comments(target)):
        problems.append("an unreadable --calendar-id does not reach a refusal on the write path —"
                        " it falls through to the auto-pick")
    if re.search(r'get\(\s*"calendar-id"\s*\)\s*==\s*null', code):
        problems.append("the flag's existence is tested with `== null` on the raw string — a"
                        " non-numeric --calendar-id reads as 'not written' again")
    # [B7-t13] DATAFLOW / family-hole：`pickWritableCalendar()` 的无参拼写禁令
    # 只挡住一种写法，真正的契约是「显式选择器没命中 ⇒ 写入必须被拒绝」：
    #   is CalendarTarget.Refused -> pickWritableCalendar(listWritableCalendars()) ?: return target.result
    # 保留了 requireCalendarTarget( 调用（存在性检查满足），也保留了带参的
    # `pickWritableCalendar(`（拼写禁令满足），却把拒绝分支换成「静默挑一个
    # 日历继续写」—— `create --calendar WorkCal`（设备上无此日历）于是写进
    # 用户的默认日历并返回 exit 0。判据锚在**决策分支本身**：两个写入入口里
    # 每个 `CalendarTarget.Refused` 分支的全部内容必须恰好是一条
    # `return <target>.result`（无 `?:`、无调用、无别的值）。
    code_struct = code_of(src)
    for verb in ("doCreate", "doUpdate"):
        reg = next((r for r in all_fun_regions(code_struct) if r["name"] == verb), None)
        if reg is None:
            continue
        body = code_struct[reg["body_start"]:reg["end"]]
        branches = list(REFUSED_BRANCH_RE.finditer(body))
        if not branches:
            problems.append(f"{verb} has no `is CalendarTarget.Refused ->` branch — an explicit selector "
                            "that matches nothing has no refusal of its own")
            continue
        for bm in branches:
            stmt = strip_parens(body[bm.end():expr_end(body, bm.end())].strip())
            if not UNCONDITIONAL_REFUSAL_RE.fullmatch(stmt):
                problems.append(
                    f"{verb} (line {_line(code_struct, reg['body_start'] + bm.start())}) answers a refused "
                    f"calendar selector with `{_short(stmt, 80)}` instead of an unconditional "
                    "`return <target>.result` — a selector miss silently falls back to the auto-picked calendar")
        if "pickWritableCalendar(" in body:
            problems.append(f"{verb} picks a writable calendar itself — the auto-pick belongs to the "
                            "no-selector case only (requireCalendarTarget)")
    return problems

REFUSED_BRANCH_RE = re.compile(r"\bis\s+CalendarTarget\.Refused\s*->")
UNCONDITIONAL_REFUSAL_RE = re.compile(r"return\s+(?:[A-Za-z_]\w*\s*\.\s*)*result")

ALARM_ARG_RE = re.compile(r'args\s*\.\s*get\(\s*"alarm"\s*\)')


def rule_f(src):
    """[dv18-A A-04] `--alarm` is classified ONCE, from the raw string.

    [B7-t13] DATAFLOW 轴：判据原本只问"函数体里有没有 `resolveAlarmSelector(`
    调用、有没有 `AlarmSelector.Unreadable -> return …` 分支"。调用点把入参
    中和掉就全绿：

        resolveAlarmSelector(args.get("alarm")?.let { null })

    分类器、拒绝分支、`getInt("alarm")` 禁令全部保留，而 `-5`（非法）与
    `15`（合法）都变成"没写 --alarm"——create 报 exit 0 且提醒静默丢失。
    现在判的是**实参活性**：传给分类器的表达式，其求值来源必须真读
    `args.get("alarm")`（`?.let { null }` / `takeIf { false }` / 字面量都不算）。
    """
    code = code_of(src)
    lit = lit_of(src)
    if "fun doCreate(" not in src and "fun doUpdate(" not in src:
        return []
    problems = []
    if "resolveAlarmSelector(" not in lit:
        problems.append("resolveAlarmSelector() is gone — the --alarm decision has no single "
                        "source (the pre-fix per-writer getInt(\"alarm\") read again)")
    else:
        reg = next((r for r in all_fun_regions(code) if r["name"] == "resolveAlarmSelector"), None)
        classifier = code[reg["body_start"]:reg["end"]] if reg else ""
        if not reg or "toIntOrNull()" not in classifier or ">= 0" not in classifier:
            problems.append("resolveAlarmSelector() no longer classifies (nullable parse + the >= 0 bound "
                            "must live in the classifier, not per writer)")
    for verb in ("doCreate", "doUpdate"):
        reg = next((r for r in all_fun_regions(code) if r["name"] == verb), None)
        if reg is None:
            continue
        body = code[reg["body_start"]:reg["end"]]
        body_lit = lit[reg["body_start"]:reg["end"]]
        calls = [m for m in re.finditer(r"resolveAlarmSelector\s*\(", code)
                 if reg["body_start"] <= m.start() < reg["end"]]
        if not calls:
            problems.append(f"{verb} does not decide --alarm through resolveAlarmSelector() — the two "
                            "writers answer \"was the flag written?\" separately")
        for m in calls:
            args = call_args(code, m.end() - 1)
            where = _line(code, m.start())
            if len(args) != 1:
                problems.append(f"{verb} (line {where}) calls resolveAlarmSelector with {len(args)} "
                                "argument(s) — the classifier takes the raw --alarm string")
                continue
            sources = _all_sources(code, lit, *args[0])
            if not any(ALARM_ARG_RE.search(s) for s in sources):
                problems.append(
                    f"{verb} (line {where}) neutralises the --alarm argument: "
                    f"`resolveAlarmSelector({_short(lit[args[0][0]:args[0][1]], 60)})` never evaluates to "
                    "args.get(\"alarm\") — a supplied `-5` reads as absent and a legal `15` is dropped, "
                    "both with exit 0")
        if 'getInt("alarm")' in body_lit:
            problems.append(f"{verb} re-parses --alarm with getInt(\"alarm\") — a supplied but unusable "
                            "value reads as 'not written' again")
        if not re.search(r"AlarmSelector\.Unreadable\s*->\s*return\s+NativeOffloadResult\(\s*2\s*,\s*"
                         r"alarmRefusalMessage\(", body):
            problems.append(f"{verb} does not turn AlarmSelector.Unreadable into an exit-2 refusal via "
                            "alarmRefusalMessage — a bad --alarm is ignored again")
    return problems


def run(root, verbose=True):
    failures = []
    for path in iter_kotlin_files(root):
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            src = fh.read()
        rel = os.path.relpath(path, root)
        for lineno, snippet in rule_a(src):
            failures.append(f"{rel}:{lineno}: [{RULE_A_CODE}] Events.DTEND compared without a NULL guard: {snippet}")
        if os.path.normpath(rel) == os.path.normpath(HANDLER_REL):
            for problem in rule_b(src):
                failures.append(f"{rel}: [{RULE_B_CODE}] freebusy path: {problem}")
            for problem in rule_c(src):
                failures.append(f"{rel}: [{RULE_C_CODE}] {problem}")
            for problem in rule_d(src):
                failures.append(f"{rel}: [{RULE_D_CODE}] {problem}")
            for problem in rule_e(src):
                failures.append(f"{rel}: [{RULE_E_CODE}] {problem}")
            for problem in rule_f(src):
                failures.append(f"{rel}: [{RULE_F_CODE}] {problem}")
        elif os.path.normpath(rel) == os.path.normpath(PHOTOS_REL):
            # [dv14-A3] rule D is the same contract on the photos side; rule E
            # is calendar-only (permissions parity), rule A/B/C never applied here.
            for problem in rule_d(src):
                failures.append(f"{rel}: [{RULE_D_CODE}] {problem}")
    if verbose:
        for line in failures:
            print(line)
    return failures


# ── selftest ────────────────────────────────────────────────────────────────

VIOLATING_HANDLER = '''
package x

class CalendarOffloadHandler {
    private fun doFreebusy() {
        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
        val selection = "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, null, null)
    }
}
'''

COMPLIANT_HANDLER = '''
package x

class CalendarOffloadHandler {
    private fun doFreebusy() {
        val projection = freebusyInstanceProjection()
        val (selection, selArgs) = instancesWindowSelection(startMs, endMs)
        context.contentResolver.query(CalendarContract.Instances.CONTENT_URI, projection, selection, selArgs, sort)
    }
}
'''

NULL_GUARDED_HANDLER = '''
package x

class OtherHandler {
    private fun doFreebusy() {
        val selection = "${CalendarContract.Events.DTEND} IS NULL OR ${CalendarContract.Events.DTEND} > ?"
        val write = arrayOf(CalendarContract.Events.DTEND)
        val values = ContentValues()
        values.put(CalendarContract.Events.DTEND, 1L)
    }
}
'''

OTHER_SLUG_HANDLER = '''
package x

class OtherHandler {
    private fun doFreebusy() {
        val selection = "${CalendarContract.Events.DTEND} > ?"
    }
}
'''

COMPLIANT_WRITER = '''
package x

class OtherHandler {
    fun update() {
        val values = ContentValues()
        values.put(CalendarContract.Events.DTEND, 1L)
        val projection = arrayOf(CalendarContract.Events.DTEND)
    }
}
'''

# [da1034-A4] ISSUE-07 fixtures — a write that names a calendar must refuse
# instead of falling through to the auto-pick heuristic.
GREEN_SELECTOR_HANDLER = '''
package x

internal sealed class CalendarSelection {
    data class Matched(val id: Long) : CalendarSelection()
    object Unmatched : CalendarSelection()
}

internal fun resolveExplicitCalendar(cals: List<CalendarRef>, calendarId: Long?, calendarName: String?): CalendarSelection? {
    if (calendarId == null && calendarName == null) return null
    if (calendarId != null) {
        return if (cals.any { it.id == calendarId }) CalendarSelection.Matched(calendarId) else CalendarSelection.Unmatched
    }
    return CalendarSelection.Unmatched
}

internal sealed class CalendarSelector {
    object Absent : CalendarSelector()
    data class ById(val id: Long) : CalendarSelector()
    data class ByName(val name: String) : CalendarSelector()
    data class Unreadable(val raw: String) : CalendarSelector()
}

internal fun resolveCalendarSelector(rawId: String?, rawName: String?): CalendarSelector {
    if (rawId != null) {
        val id = rawId.toLongOrNull()
        return if (id != null) CalendarSelector.ById(id) else CalendarSelector.Unreadable(rawId)
    }
    if (rawName != null) return CalendarSelector.ByName(rawName)
    return CalendarSelector.Absent
}

class CalendarOffloadHandler {
    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        val calendarId = when (val target = requireCalendarTarget(args)) {
            is CalendarTarget.Id -> target.value
            is CalendarTarget.Refused -> return target.result
        }
        return NativeOffloadResult(0, calendarId.toString())
    }

    private fun doUpdate(args: OffloadArgs): NativeOffloadResult {
        when (val target = optionalCalendarTarget(args)) {
            null -> Unit
            is CalendarTarget.Id -> values.put(CalendarContract.Events.CALENDAR_ID, target.value)
            is CalendarTarget.Refused -> return target.result
        }
        return NativeOffloadResult(0, "ok")
    }

    private fun doList(args: OffloadArgs): NativeOffloadResult {
        val calFilter = args.get("calendar")
        return NativeOffloadResult(0, calFilter ?: "")
    }

    private fun requireCalendarTarget(args: OffloadArgs): CalendarTarget {
        val selector = resolveCalendarSelector(args.get("calendar-id"), args.get("calendar"))
        if (selector is CalendarSelector.Unreadable) {
            return CalendarTarget.Refused(unreadableSelectorError(selector, args))
        }
        val id = (selector as? CalendarSelector.ById)?.id
        val name = (selector as? CalendarSelector.ByName)?.name
        when (val sel = resolveExplicitCalendar(refs, id, name)) {
            is CalendarSelection.Matched -> return CalendarTarget.Id(sel.id)
            is CalendarSelection.Unmatched -> return CalendarTarget.Refused(calendarNotFoundError(args))
            null -> Unit
        }
        return CalendarTarget.Id(pickWritableCalendar(cals) ?: 0L)
    }

    private fun optionalCalendarTarget(args: OffloadArgs): CalendarTarget? {
        if (resolveCalendarSelector(args.get("calendar-id"), args.get("calendar")) is CalendarSelector.Absent) return null
        return requireCalendarTarget(args)
    }

    private fun unreadableSelectorError(selector: CalendarSelector.Unreadable, args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(2, OffloadOutput.formatBody(JSONObject().put("error", "calendar_bad_selector").toString(), args))

    private fun calendarNotFoundError(args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(1, OffloadOutput.formatBody(JSONObject().put("error", "calendar_not_found").toString(), args))

    private fun pickWritableCalendar(cals: JSONArray): Long? = null

    private sealed class CalendarTarget {
        data class Id(val value: Long) : CalendarTarget()
        data class Refused(val result: NativeOffloadResult) : CalendarTarget()
    }
}
'''

SELECTOR_FALLBACK_HANDLER = GREEN_SELECTOR_HANDLER.replace(
    '''        val calendarId = when (val target = requireCalendarTarget(args)) {
            is CalendarTarget.Id -> target.value
            is CalendarTarget.Refused -> return target.result
        }''',
    '        val calendarId = pickWritableCalendar()')

SELECTOR_UNHANDLED_HANDLER = GREEN_SELECTOR_HANDLER.replace(
    "            is CalendarSelection.Unmatched -> return CalendarTarget.Refused(calendarNotFoundError(args))",
    "            is CalendarSelection.Unmatched -> Unit")

SELECTOR_FILTER_REFUSES_HANDLER = GREEN_SELECTOR_HANDLER.replace(
    '        val calFilter = args.get("calendar")',
    '        val calFilter = args.get("calendar") ?: "calendar_not_found"')

SELECTOR_RENAMED_PAYLOAD_HANDLER = GREEN_SELECTOR_HANDLER.replace(
    '"calendar_not_found"', '"not_found"')

SELECTOR_RENAMED_DECISION_HANDLER = GREEN_SELECTOR_HANDLER.replace(
    "resolveExplicitCalendar(", "resolveCalendarSelector(")

# [dv15-A2] A-03 arms — one per new criterion in rule C. Each restores exactly
# ONE of the ways the pre-fix shape answered "was the flag written?" from the
# parsed value instead of from the raw string.
SELECTOR_NO_RAW_READ = GREEN_SELECTOR_HANDLER.replace(
    'args.get("calendar-id")', 'args.getLong("calendar-id")')

SELECTOR_NO_NULLABLE_PARSE = GREEN_SELECTOR_HANDLER.replace(
    "toLongOrNull()", "toLong()")

SELECTOR_NO_BAD_SELECTOR_PAYLOAD = GREEN_SELECTOR_HANDLER.replace(
    '"calendar_bad_selector"', '"calendar_not_found"')

SELECTOR_UNREADABLE_NOT_REFUSED = GREEN_SELECTOR_HANDLER.replace(
    '''        if (selector is CalendarSelector.Unreadable) {
            return CalendarTarget.Refused(unreadableSelectorError(selector, args))
        }
''', "")

SELECTOR_EXISTENCE_TEST = GREEN_SELECTOR_HANDLER.replace(
    'if (resolveCalendarSelector(args.get("calendar-id"), args.get("calendar")) is CalendarSelector.Absent) return null',
    'if (args.get("calendar-id") == null && args.get("calendar") == null) return null')


# [dv14-A3] rules D + E fixtures. A synthetic handler carrying: a pure
# day-window core with the raw-string guard, its `args`-forwarding member, one
# write entry point that gates on read+write, and one read-only entry point.
GREEN_DAYS_PERM_HANDLER = '''
package x

internal fun resolveRangeFixture(
    nowMs: Long,
    daysRaw: Int?,
    daysText: String? = null,
    parse: (String, Long, TimeZone) -> Long?,
): Pair<Long, Long>? {
    if (daysText != null && daysText.isNotBlank() && daysText.toIntOrNull() == null) {
        return null
    }
    return null
}

class CalendarOffloadHandler {
    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? =
        resolveRangeFixture(
            nowMs = 0L,
            daysRaw = args.getInt("days"),
            daysText = args.get("days"),
        ) { s, now, z -> null }

    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) },
            args = args,
        )?.let { return it }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return NativeOffloadResult(0, uri.toString())
    }

    private fun doList(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            args = args,
        )?.let { return it }
        context.contentResolver.query(CalendarContract.Instances.CONTENT_URI, projection, null, null, null)
        return NativeOffloadResult(0, "ok")
    }
}
'''

DAYS_GUARD_REMOVED = GREEN_DAYS_PERM_HANDLER.replace(
    '''    if (daysText != null && daysText.isNotBlank() && daysText.toIntOrNull() == null) {
        return null
    }
''', "")

DAYS_PARAM_REMOVED = GREEN_DAYS_PERM_HANDLER.replace(
    "    daysText: String? = null,\n", "")

DAYS_WIRING_REMOVED = GREEN_DAYS_PERM_HANDLER.replace(
    '            daysText = args.get("days"),\n', "")

DAYS_NO_CORE = GREEN_DAYS_PERM_HANDLER.replace(
    '''internal fun resolveRangeFixture(
    nowMs: Long,
    daysRaw: Int?,
    daysText: String? = null,
    parse: (String, Long, TimeZone) -> Long?,
): Pair<Long, Long>? {
    if (daysText != null && daysText.isNotBlank() && daysText.toIntOrNull() == null) {
        return null
    }
    return null
}

''', "")

# The pre-fix write shape: both permissions requested, only WRITE decided on.
PERM_WRITE_ONLY = GREEN_DAYS_PERM_HANDLER.replace(
    "satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) }",
    "satisfied = { hasWrite() }")

# Decision and request drift apart: gated on both, but READ is never requested.
PERM_REQUEST_DRIFT = GREEN_DAYS_PERM_HANDLER.replace(
    "permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),",
    "permissions = listOf(Manifest.permission.WRITE_CALENDAR),")

PERM_NO_GATE = GREEN_DAYS_PERM_HANDLER.replace(
    '''        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) },
            args = args,
        )?.let { return it }
''', "")

PERM_READ_PATH_ASKS_WRITE = GREEN_DAYS_PERM_HANDLER.replace(
    '''            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },''',
    '''            permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            satisfied = { hasRead() },''')

# [dv18-A A-04] rule F fixtures — the `--alarm` boundary machinery. The
# classifier is the single source both writers must answer "was --alarm
# written, and is it usable?" from; the control keeps both writers on it.
ALARM_CLASSIFIER = '''internal fun resolveAlarmSelector(raw: String?): AlarmSelector {
    if (raw == null) return AlarmSelector.Absent
    val minutes = raw.toIntOrNull()
    return if (minutes != null && minutes >= 0) AlarmSelector.Minutes(minutes) else AlarmSelector.Unreadable(raw)
}
'''

ALARM_WRITERS = '''
class CalendarOffloadHandler {
    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        val alarm = when (val selector = resolveAlarmSelector(args.get("alarm"))) {
            is AlarmSelector.Unreadable -> return NativeOffloadResult(2, alarmRefusalMessage("create", selector.raw))
            else -> selector
        }
        return insertReminder(args, (alarm as? AlarmSelector.Minutes)?.value)
    }

    private fun doUpdate(args: OffloadArgs): NativeOffloadResult {
        val alarm = when (val selector = resolveAlarmSelector(args.get("alarm"))) {
            is AlarmSelector.Unreadable -> return NativeOffloadResult(2, alarmRefusalMessage("update", selector.raw))
            else -> selector
        }
        return replaceReminder(args, (alarm as? AlarmSelector.Minutes)?.value)
    }
}
'''

GREEN_ALARM_HANDLER = ALARM_CLASSIFIER + ALARM_WRITERS

ALARM_CLASSIFIER_GONE = ALARM_WRITERS

ALARM_CLASSIFIER_NO_CLASSIFY = ALARM_CLASSIFIER.replace(
    '''    if (raw == null) return AlarmSelector.Absent
    val minutes = raw.toIntOrNull()
    return if (minutes != null && minutes >= 0) AlarmSelector.Minutes(minutes) else AlarmSelector.Unreadable(raw)''',
    '    return AlarmSelector.Absent') + ALARM_WRITERS

ALARM_UPDATE_NO_REFUSAL = ALARM_CLASSIFIER + '''
class CalendarOffloadHandler {
    private fun doUpdate(args: OffloadArgs): NativeOffloadResult {
        val alarm = when (val selector = resolveAlarmSelector(args.get("alarm"))) {
            else -> selector
        }
        return replaceReminder(args, (alarm as? AlarmSelector.Minutes)?.value)
    }
}
'''

ALARM_CREATE_REPARSE = ALARM_CLASSIFIER + '''
class CalendarOffloadHandler {
    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        val legacy = args.getInt("alarm")
        val alarm = when (val selector = resolveAlarmSelector(args.get("alarm"))) {
            is AlarmSelector.Unreadable -> return NativeOffloadResult(2, alarmRefusalMessage("create", selector.raw))
            else -> selector
        }
        return insertReminder(args, (alarm as? AlarmSelector.Minutes)?.value ?: legacy)
    }
}
'''

def write_fixture(root, name, body, handler_rel=True):
    rel = HANDLER_REL if handler_rel else os.path.join(MAIN_ROOT, "com", "rikkaminis", "app", "other", name)
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body)



# ── [B7-t13] 第七批见证夹具（每条 = 外部攻击方的最小补丁，门在修复前全绿）───
# 05-1: 谓词换成 SQL 物理列名 + 拆字面量拼接，且外层保留 instancesWindowSelection(。
DTEND_LOWERCASE_SPLIT = '''
package x

class OtherHandler {
    private fun doFreebusy() {
        val (selection, selArgs) = instancesWindowSelection(startMs, endMs).let { ("dtend > " + "?") to arrayOf(startMs.toString()) }
    }
}
'''

# 05-2: 真正的查询落回 Events 表（硬编码 uri），Instances 只留一个没人用的引用。
EVENTS_URI_DIVERSION = COMPLIANT_HANDLER.replace(
    "        context.contentResolver.query(CalendarContract.Instances.CONTENT_URI, projection, selection, selArgs, sort)",
    "        val _unused = CalendarContract.Instances.CONTENT_URI\n"
    "        val eventsUri = android.net.Uri.parse(\"content://com.android.calendar/events\")\n"
    "        context.contentResolver.query(eventsUri, projection, selection, selArgs, sort)",
)

# 05-1（rule B 半边）：instancesWindowSelection( 还在，返回值被 .let 换成了 dtend 谓词。
SELECTION_REWRITTEN = COMPLIANT_HANDLER.replace(
    "        val (selection, selArgs) = instancesWindowSelection(startMs, endMs)",
    "        val (selection, selArgs) = instancesWindowSelection(startMs, endMs).let { (\"dtend > \" + \"?\") to arrayOf(startMs.toString()) }",
)

# 05-3: 拒绝分支改成"静默挑一个日历继续写"，调用形式带参（骗过无参拼写禁令）。
SELECTOR_REFUSAL_FALLS_BACK = GREEN_SELECTOR_HANDLER.replace(
    "            is CalendarTarget.Refused -> return target.result\n        }\n        return NativeOffloadResult(0, calendarId.toString())",
    "            is CalendarTarget.Refused -> pickWritableCalendar(listWritableCalendars()) ?: return target.result\n        }\n        return NativeOffloadResult(0, calendarId.toString())",
)

# 05-4: 受检子串一个不少，只给守卫补一个恒假项 —— 拒绝分支变死代码。
DAYS_GUARD_DEAD = GREEN_DAYS_PERM_HANDLER.replace(
    "daysText.toIntOrNull() == null) {",
    "daysText.toIntOrNull() == null && false) {",
)

# 05-5: satisfied 里两个权限调用都在，整体被 `|| true` 折成恒真。
PERM_SATISFIED_ALWAYS_TRUE = GREEN_DAYS_PERM_HANDLER.replace(
    "satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) },",
    "satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) || true },",
)

# 05-6: 分类器与拒绝分支原样保留，入参在调用点被中和成 null。
ALARM_ARG_NEUTRALISED = GREEN_ALARM_HANDLER.replace(
    'resolveAlarmSelector(args.get("alarm"))',
    'resolveAlarmSelector(args.get("alarm")?.let { null })',
)

def selftest():
    cases = [
        ("violating handler (events predicate + events query)", VIOLATING_HANDLER, True, 1),
        ("compliant handler (instances)", COMPLIANT_HANDLER, True, 0),
        ("null-guarded predicate + write-only DTEND", NULL_GUARDED_HANDLER, False, 0),
        ("events predicate in another file", OTHER_SLUG_HANDLER, False, 1),
        ("write-only DTEND in another file", COMPLIANT_WRITER, False, 0),
    ]
    ok = True
    for label, body, handler_rel, expect_failures in cases:
        root = tempfile.mkdtemp(prefix="calgate-")
        try:
            write_fixture(root, "CalendarOffloadHandler.kt", body, handler_rel=handler_rel)
            failures = run(root, verbose=False)
            passed = (len(failures) > 0) == (expect_failures > 0)
            print(f"[{'PASS' if passed else 'FAIL'}] selftest: {label} "
                  f"(expected {'red' if expect_failures else 'green'}, got {len(failures)} violation(s))")
            for f in failures:
                print(f"        {f}")
            ok = ok and passed
        finally:
            shutil.rmtree(root, ignore_errors=True)
    # [da1034-A4] rule C — one control plus one arm per criterion. Each arm
    # asserts rule C specifically (not merely "something went red"): an arm that
    # trips rule A/B instead would leave rule C toothless and still look green.
    selector_cases = [
        ("selector machinery intact (rule C control)", GREEN_SELECTOR_HANDLER, False),
        ("a write falls back to the auto-pick", SELECTOR_FALLBACK_HANDLER, True),
        ("the selector miss stops being handled", SELECTOR_UNHANDLED_HANDLER, True),
        ("the read path (list filter) starts refusing", SELECTOR_FILTER_REFUSES_HANDLER, True),
        ("the refusal payload loses its name", SELECTOR_RENAMED_PAYLOAD_HANDLER, True),
        ("the selector decision loses its single source", SELECTOR_RENAMED_DECISION_HANDLER, True),
        ("the raw --calendar-id string stops being read", SELECTOR_NO_RAW_READ, True),
        ("the raw selector is parsed without a nullable parse", SELECTOR_NO_NULLABLE_PARSE, True),
        ("the written-but-unparseable selector loses its payload", SELECTOR_NO_BAD_SELECTOR_PAYLOAD, True),
        ("an unreadable selector stops being refused on the write path", SELECTOR_UNREADABLE_NOT_REFUSED, True),
        ("the flag's existence is tested on the raw string again", SELECTOR_EXISTENCE_TEST, True),
        ("a refused selector falls back to the auto-pick (parameterised call)", SELECTOR_REFUSAL_FALLS_BACK, True),
    ]
    for label, body, expect_red in selector_cases:
        root = tempfile.mkdtemp(prefix="calgate-c-")
        try:
            write_fixture(root, "CalendarOffloadHandler.kt", body, handler_rel=True)
            failures = run(root, verbose=False)
            hit = [f for f in failures if RULE_C_CODE in f]
            passed = (len(hit) > 0) == expect_red
            print(f"[{'PASS' if passed else 'FAIL'}] selftest: {label} "
                  f"(rule C expected {'red' if expect_red else 'green'}, got {len(hit)} rule-C violation(s))")
            for f in hit:
                print(f"        {f}")
            ok = ok and passed
        finally:
            shutil.rmtree(root, ignore_errors=True)
    ok = ok and passed
    # [B7-t13] rules A + B — 第七批的两个数据流/拼写见证：每条 arm 只断言
    # 自己那条规则（撞到邻规则 = 该条判据仍无牙，必须判 FAIL）。
    for label, body, rule, handler_rel, expect_red in (
        ("a split lowercase dtend predicate (rule A spelling)", DTEND_LOWERCASE_SPLIT, RULE_A_CODE, False, True),
        ("the freebusy query diverts to the Events uri", EVENTS_URI_DIVERSION, RULE_B_CODE, True, True),
        ("the window selection is rewritten away", SELECTION_REWRITTEN, RULE_B_CODE, True, True),
    ):
        root = tempfile.mkdtemp(prefix="calgate-ab-")
        try:
            write_fixture(root, "CalendarOffloadHandler.kt", body, handler_rel=handler_rel)
            failures = run(root, verbose=False)
            hit = [f for f in failures if rule in f]
            passed = (len(hit) > 0) == expect_red
            print(f"[{'PASS' if passed else 'FAIL'}] selftest: {label} "
                  f"({rule} expected {'red' if expect_red else 'green'}, got {len(hit)} violation(s))")
            for f in hit:
                print(f"        {f}")
            ok = ok and passed
        finally:
            shutil.rmtree(root, ignore_errors=True)
    # [dv14-A3] rules D + E — one control plus one arm per criterion, each arm
    # asserting its OWN rule code (an arm that trips another rule instead would
    # leave D/E toothless and still look green).
    for label, body, rule, expect_red in (
        ("days+permissions intact (rule D/E control)", GREEN_DAYS_PERM_HANDLER, None, False),
        ("the unreadable-days guard is gone", DAYS_GUARD_REMOVED, RULE_D_CODE, True),
        ("the raw days parameter is gone", DAYS_PARAM_REMOVED, RULE_D_CODE, True),
        ("the raw days string is never read", DAYS_WIRING_REMOVED, RULE_D_CODE, True),
        ("the day-window core is gone", DAYS_NO_CORE, RULE_D_CODE, True),
        ("a write gates on hasWrite() alone", PERM_WRITE_ONLY, RULE_E_CODE, True),
        ("a write requests a permission set it does not decide on", PERM_REQUEST_DRIFT, RULE_E_CODE, True),
        ("a write entry point has no permission gate", PERM_NO_GATE, RULE_E_CODE, True),
        ("a read-only subcommand asks for WRITE", PERM_READ_PATH_ASKS_WRITE, RULE_E_CODE, True),
        ("the days guard is capped by an always-false term", DAYS_GUARD_DEAD, RULE_D_CODE, True),
        ("the satisfied predicate folds to true", PERM_SATISFIED_ALWAYS_TRUE, RULE_E_CODE, True),
    ):
        root = tempfile.mkdtemp(prefix="calgate-de-")
        try:
            write_fixture(root, "CalendarOffloadHandler.kt", body, handler_rel=True)
            failures = run(root, verbose=False)
            if rule is None:
                hit = [f for f in failures if RULE_D_CODE in f or RULE_E_CODE in f]
                passed = not expect_red and not hit
            else:
                hit = [f for f in failures if rule in f]
                passed = (len(hit) > 0) == expect_red
            print(f"[{'PASS' if passed else 'FAIL'}] selftest: {label} "
                  f"({'rule D/E' if rule is None else rule} expected {'red' if expect_red else 'green'}, "
                  f"got {len(hit)} violation(s))")
            for f in hit:
                print(f"        {f}")
            ok = ok and passed
        finally:
            shutil.rmtree(root, ignore_errors=True)
    # [dv18-A A-04] rule F — one control plus one arm per criterion, each arm
    # asserting its OWN rule code (same discipline as the D/E block above).
    for label, body, rule, expect_red in (
        ("alarm machinery intact (rule F control)", GREEN_ALARM_HANDLER, None, False),
        ("the shared --alarm classifier is gone", ALARM_CLASSIFIER_GONE, RULE_F_CODE, True),
        ("the classifier stops classifying", ALARM_CLASSIFIER_NO_CLASSIFY, RULE_F_CODE, True),
        ("a writer ignores an unusable --alarm", ALARM_UPDATE_NO_REFUSAL, RULE_F_CODE, True),
        ("a writer re-parses --alarm on its own", ALARM_CREATE_REPARSE, RULE_F_CODE, True),
        ("the --alarm argument is neutralised at the call site", ALARM_ARG_NEUTRALISED, RULE_F_CODE, True),
    ):
        root = tempfile.mkdtemp(prefix="calgate-f-")
        try:
            write_fixture(root, "CalendarOffloadHandler.kt", body, handler_rel=True)
            failures = run(root, verbose=False)
            if rule is None:
                hit = [f for f in failures if RULE_F_CODE in f]
                passed = not expect_red and not hit
            else:
                hit = [f for f in failures if rule in f]
                passed = (len(hit) > 0) == expect_red
            print(f"[{'PASS' if passed else 'FAIL'}] selftest: {label} "
                  f"({'rule F' if rule is None else rule} expected {'red' if expect_red else 'green'}, "
                  f"got {len(hit)} violation(s))")
            for f in hit:
                print(f"        {f}")
            ok = ok and passed
        finally:
            shutil.rmtree(root, ignore_errors=True)
    print("selftest: " + ("ALL ARMS MATCH" if ok else "FAILED"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    failures = run(args.root)
    if failures:
        print(f"calendar_events_nullable_predicate_check: FAIL ({len(failures)} violation(s))")
        return 1
    print("calendar_events_nullable_predicate_check: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
