"""共享值流 / 控制流原语（第五批门见证收口，2026-10-25）。

为什么要有这个模块
------------------
第五批 24 道门里 132 条见证被证伪，病因高度集中（见 fix-reports 报告
§跨门共性）：

1. 各门自写 `strip_comments` / `brace_block_span` —— 字符串里的 `}` 就能
   提前闭合函数体切片（parser-death）。
2. 各门自写死分支正则白名单（`false` / `!true` / `0 > 1`）—— `if (1 == 2)`
   一招穿透（family-hole）。
3. **值流被丢弃型作用域函数改写**：`File(dir, raw + ".json").also { stamp }`
   表面含 `stamp`，真实求值是 `raw`（dataflow）。
4. **控制流被局部返回劫持**：`run { if (guard) return@run }` 不退出外层函数
   （order / dataflow）。

前两条由 `_kotlin_lexer` / `_kotlin_ast` 覆盖（本轮所有门必须改用，禁止再
自写词法器）；本模块补后两条，以及"路径可行性 / 单一赋值"这类共用判据。

使用约定
--------
* 所有结构分析（括号配平、语句切分、位置索引）都在 `code_view()` 上做：
  它把注释与字符串/字符字面量内部抹成空格、**长度与源码严格相等**，因此
  位置可以直接回投到原文。
* 需要读字符串内容（XML 路径名、JSON key）时用 `literal_view()`（只剥注释，
  保留字面量内容）。
* 解析失败一律 fail-closed：`code_view()` 抛 `KotlinLexError`；调用方捕获后
  报违规，**不要**回退成原文扫描。
"""

import re

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import (
    const_bool,
    const_value,
    enclosing_conditions,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
    tail_expr,
)

__all__ = [
    "KotlinLexError",
    "code_view",
    "literal_view",
    "brace_depth",
    "kw_depth",  # 见文档：不区分 lambda 与普通块时的深度
    "scope_calls",
    "peel_discarding",
    "value_sources",
    "branch_value",  # if/else 块体分支的值 = 尾表达式
    "contains_ident",
    "guard_feasible",
    "path_feasible",
    "returns_exiting",
    "decl_sites",
    "is_single_assignment",
    "live_positions",
    "call_args",
    "expr_end",
]

# --------------------------------------------------------------------------
# 视图
# --------------------------------------------------------------------------


def code_view(src):
    """结构通道：注释 + 字符串/字符字面量内部 → 空格，长度不变。"""
    return mask_code(src)


def literal_view(src):
    """字面量通道：只剥注释，字符串内容保留（读 XML 路径 / JSON key 用）。"""
    return mask_comments(src)


def brace_depth(text, pos):
    """pos 处的花括号深度（text 应为 code_view 结果）。"""
    seg = text[:max(0, pos)]
    return seg.count("{") - seg.count("}")


kw_depth = brace_depth  # 兼容别名：不区分 lambda 与普通块时的深度


# --------------------------------------------------------------------------
# 1. 作用域函数（值流）
# --------------------------------------------------------------------------

DISCARD_SCOPES = ("also", "apply", "onEach", "forEach")
# let / run 的值是 lambda 的尾表达式，不是接收者，必须单独处理
VALUE_SCOPES = ("let", "run", "with", "takeIf", "takeUnless")

_SCOPE_RE = re.compile(
    r"\.\s*(%s)\s*(?=[{(])" % "|".join(DISCARD_SCOPES + VALUE_SCOPES)
)
# 不带点号的 run { } / with(x) { }
_BARE_SCOPE_RE = re.compile(r"(?<![\w.])(run|with)\s*(?=[{(])")


def scope_calls(expr):
    """所有作用域函数调用：[(name, start, open_pos, end_exclusive)]。

    start 是 `.also` 里点的位置（裸 `run` 则是名字起点）；end_exclusive 是
    配平括号之后的位置。配平失败的一律丢弃（调用方应视为解析失败）。
    """
    out = []
    for m in _SCOPE_RE.finditer(expr):
        open_pos = m.end()
        close = match_bracket(expr, open_pos)
        if close < 0:
            continue
        out.append((m.group(1), m.start(), open_pos, close + 1))
    for m in _BARE_SCOPE_RE.finditer(expr):
        open_pos = m.end()
        close = match_bracket(expr, open_pos)
        if close < 0:
            continue
        out.append((m.group(1), m.start(), open_pos, close + 1))
    out.sort(key=lambda t: t[1])
    return out


def peel_discarding(expr, rounds=6):
    """剥掉丢弃型作用域调用（also/apply/onEach/forEach），保留接收者。

    `x(foo).also { stamp }` -> `x(foo)`：真实求值结果就是接收者，lambda 的
    返回值被丢弃。这正是 `File(dir, raw + ".json").also { stamp }` 这类
    "表面满足标识符存在性、实际用脏值" 的判据破口。
    """
    cur = expr
    for _ in range(rounds):
        spans = [s for s in scope_calls(cur) if s[0] in DISCARD_SCOPES]
        if not spans:
            break
        # 从右往左替换，避免偏移失效；只剥最内层（先剥 end 最大的）
        spans.sort(key=lambda t: t[3], reverse=True)
        changed = False
        for _name, start, _open, end in spans:
            # 只有 span 结束后没有继续链式尾巴时才整体剥
            cur = cur[:start] + cur[end:]
            changed = True
            break
        if not changed:
            break
    return cur


def branch_value(part):
    """分支体**值**的表达式：块体 `{ … }` 取尾表达式，裸表达式原样返回。

    `if (has(x)) { cap(x) } else { null }` 的 else 分支值是 `null`，不是
    `{ null }` —— 不剥块的话消费门的 NULLISH 白名单（`"null"` / `"Unit"`）
    匹配不上，消毒漏斗类判据会在真树上误红（CI #2760 实测）。
    """
    s = part.strip()
    if not s.startswith("{"):
        return s
    close = match_bracket(s, 0)
    if close != len(s) - 1:
        return s
    tail = tail_expr(s[1:close])
    return tail.strip() if tail else s


def value_sources(expr, depth=0):
    """表达式最终值的**所有可能来源**（保守：多算不漏算）。

    门要问的是 "这个位置真正求值的东西里还有没有脏值"，因此宁可返回多个
    候选：`a ?: b` 两个都要算，`if (c) a else b` 两个都要算，`let { body }`
    算 body 的尾表达式，`also { }` 只算接收者。
    """
    if depth > 6:
        return [expr]
    e = strip_parens(expr.strip())
    if not e:
        return [expr]

    # 顶层 elvis：两侧都可能成为结果
    parts = split_top(e, ("?:",))
    if len(parts) == 2:
        return (value_sources(e[parts[0][0]:parts[0][1]], depth + 1)
                + value_sources(e[parts[1][0]:parts[1][1]], depth + 1))

    # if / else 表达式（A2）：旧实现把 `)` 之后的整段当 then、再在开头
    # re.match(r"else\b") —— `)` 之后永远是 then 表达式，永远匹配不到，整个
    # 分叉 100% 死代码，`if (c) a else b` 原样返回整条表达式。改用 if_blocks
    # 定位 then/else 区间，再按常量条件三分支：真 → then、假 → else、
    # 未知 → 两侧都算（保守不漏脏来源）。
    if re.match(r"if\s*\(", e):
        blks = if_blocks(e)
        if blks:
            cond, t_start, t_end, e_start, e_end = blks[0]
            # else 段必须覆盖到表达式结尾：`if (c) a else b + z` 是一体的，
            # 尾部还带别的语句的形态不许在这里错切（宁可退回整条表达式）。
            if e_start is not None and e_end == len(e):
                then_part = branch_value(e[t_start:t_end])
                else_part = branch_value(e[e_start:e_end])
                b = const_bool(cond)
                if b is True:
                    return value_sources(then_part, depth + 1)
                if b is False:
                    return value_sources(else_part, depth + 1)
                # 条件未知时两侧都算，但**纯字面量分支不是数据来源**：把它并回
                # 整条表达式（`if (has(x)) cap(x) else null` 的 null 分支不是
                # "没走消毒的值"，拆出来会让 require_all 型判据误红真树）。
                if const_value(then_part) is None and const_value(else_part) is None:
                    return (value_sources(then_part, depth + 1)
                            + value_sources(else_part, depth + 1))

    # 丢弃型作用域：值是接收者
    if scope_calls(e) and any(s[0] in DISCARD_SCOPES for s in scope_calls(e)):
        return value_sources(peel_discarding(e), depth + 1)

    # 值型作用域（let/run/takeIf）：值是 lambda 尾表达式
    for name, start, open_pos, end in scope_calls(e):
        if name in ("let", "run", "with"):
            body = e[open_pos + 1:end - 1]
            tail = _tail_stmt(body)
            if tail:
                return value_sources(tail, depth + 1)
        if name in ("takeIf", "takeUnless"):
            # takeIf 命中时值是接收者，未命中是 null：两者都算
            return [e[:start]] + value_sources(e[end:], depth + 1) if e[end:].strip() else [e[:start]]
    return [e]


def _tail_stmt(body):
    """块体的尾表达式（最后一个非空语句）。"""
    from _kotlin_ast import stmt_spans

    spans = stmt_spans(body)
    if not spans:
        return body.strip()
    start, end = spans[-1]
    return body[start:end].strip()


def contains_ident(text, name):
    """词边界标识符匹配（避免 `stamp` 命中 `rawStamp` / `stamped`）。"""
    return re.search(r"(?<![\w`])" + re.escape(name) + r"(?![\w`])", text) is not None


# --------------------------------------------------------------------------
# 2. 守卫可行性与路径条件
# --------------------------------------------------------------------------

_INT_CMP = re.compile(r"^\s*(-?\d+)\s*(==|!=|<=|>=|<|>)\s*(-?\d+)\s*$")
_SYM_CMP = re.compile(r"^\s*([A-Za-z_]\w*)\s*(==|!=|<=|>=|<|>)\s*(-?\d+)\s*$")
_SYM_CMP_R = re.compile(r"^\s*(-?\d+)\s*(==|!=|<=|>=|<|>)\s*([A-Za-z_]\w*)\s*$")
_FLIP = {"==": "==", "!=": "!=", "<": ">", ">": "<", "<=": ">=", ">=": "<="}


def guard_feasible(cond, domains=None):
    """条件是否可能为真。`domains` = {name: (lo, hi)} 已知取值范围。

    - 纯常量（含算术比较、短路、括号、!）→ `_kotlin_ast.const_bool`
    - 符号比较（`n > 3`）+ 已知 domain → 区间求交
    - 其它 → True（无法证明不可行；门不得据此报违规）
    """
    c = strip_parens(cond)
    if not c:
        return True
    b = const_bool(c)
    if b is not None:
        return b
    m = _INT_CMP.match(c)
    if m:
        a, op, b2 = int(m.group(1)), m.group(2), int(m.group(3))
        return {"==": a == b2, "!=": a != b2, "<": a < b2, ">": a > b2,
                "<=": a <= b2, ">=": a >= b2}[op]
    if not domains:
        return True
    m = _SYM_CMP.match(c)
    if m:
        name, op, num = m.group(1), m.group(2), int(m.group(3))
    else:
        m = _SYM_CMP_R.match(c)
        if not m:
            return True
        num, op, name = int(m.group(1)), _FLIP[m.group(2)], m.group(3)
    if name not in domains:
        return True
    lo, hi = domains[name]
    if op == ">=":
        slo, shi = num, None
    elif op == ">":
        slo, shi = num + 1, None
    elif op == "<=":
        slo, shi = None, num
    elif op == "<":
        slo, shi = None, num - 1
    elif op == "==":
        slo, shi = num, num
    else:  # !=
        return not (lo is not None and hi is not None and lo == hi == num)
    nlo = max(x for x in (lo, slo) if x is not None) if (lo is not None or slo is not None) else None
    nhi = min(x for x in (hi, shi) if x is not None) if (hi is not None or shi is not None) else None
    return not (nlo is not None and nhi is not None and nlo > nhi)


def path_feasible(text, pos, domains=None):
    """pos 处的代码是否可达：覆盖它的所有条件同时可行。

    `text` 必须是 code_view 结果。用于"死分支里的违规不算数""活分支里的
    守卫才算数"这类判据。
    """
    for _kind, cond in enclosing_conditions(text, pos):
        if not guard_feasible(cond, domains):
            return False
    return True


# --------------------------------------------------------------------------
# 3. 控制流：return 是否真的退出外层函数
# --------------------------------------------------------------------------


def returns_exiting(text, start, end, fn_depth=None):
    """[start, end) 内是否有**退出外层函数**的 return。

    `return@run` / `return@let` 只跳出 lambda，不算。在 lambda 内部（相对
    `fn_depth` 更深的 `{` 里）出现的裸 `return` 也不是本文意义上的早退。
    `fn_depth` 缺省取 start 处的深度。
    """
    if fn_depth is None:
        fn_depth = brace_depth(text, start)
    for m in re.finditer(r"\breturn\b(@\s*[A-Za-z_]\w*)?", text[start:end]):
        if m.group(1):
            continue  # 带标签：只退 lambda
        pos = start + m.start()
        if brace_depth(text, pos) == fn_depth:
            return True
    return False


# --------------------------------------------------------------------------
# 4. 线性活性：声明 / 重赋值 / 遮蔽
# --------------------------------------------------------------------------


def decl_sites(text, name):
    """标识符 name 的声明/赋值点：[(pos, kind)]，kind ∈ {decl, assign, shadow}。

    - decl：`val/var name =`
    - assign：`name =`（且不是 ==）
    - shadow：在比首个声明更深的块里重新 `val/var name =`
    """
    out = []
    pat = re.compile(
        r"(?<![\w`])(?:(val|var)\s+)?(?P<n>" + re.escape(name) + r")(?![\w`])"
        r"\s*(?::[^=\n]+?)?=(?!=)"
    )
    first_depth = None
    for m in pat.finditer(text):
        kind = "decl" if m.group(1) else "assign"
        pos = m.start()
        depth = brace_depth(text, pos)
        if kind == "decl":
            if first_depth is None:
                first_depth = depth
            elif depth > first_depth:
                kind = "shadow"
        out.append((pos, kind))
    return out


def is_single_assignment(text, name):
    """恰好一次 `val` 声明、零重赋值、零遮蔽 —— 值不可能在中途被换掉。"""
    sites = decl_sites(text, name)
    if not sites:
        return False
    decls = [s for s in sites if s[1] == "decl"]
    return len(decls) == 1 and all(s[1] == "decl" for s in sites)


def live_positions(text, pattern, domains=None):
    """pattern 在**可达路径**上的所有匹配起点（死分支里的不算）。

    这是 order 类判据的正确取位方式：`if (false) guard()` 里的第一个匹配
    不能当作"守卫发生在前面"。
    """
    if isinstance(pattern, str):
        pattern = re.compile(pattern)
    return [m.start() for m in pattern.finditer(text)
            if path_feasible(text, m.start(), domains)]


# --------------------------------------------------------------------------
# 5. 调用实参
# --------------------------------------------------------------------------


def call_args(text, open_paren_pos):
    """取调用实参列表：[(start, end)]，相对 text。配平失败返回 []。"""
    close = match_bracket(text, open_paren_pos)
    if close < 0:
        return []
    inner = text[open_paren_pos + 1:close]
    base = open_paren_pos + 1
    return [(base + a, base + b) for a, b in split_top(inner, (",",))]


def expr_end(text, start):
    """从 start 起的表达式终点：深度 0 的换行且下一行不是续行。"""
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


# --------------------------------------------------------------------------
# selftest
# --------------------------------------------------------------------------

ARMS = [
    # (name, fn(src_or_args) -> bool)
    ("code_view_masks_string_brace",
     lambda V: "}" not in V["code_view"]('val s = "a } b"\n').replace("{", "")),
    ("code_view_length_preserved",
     lambda V: len(V["code_view"]('val s = "a${b}c" // x\n')) == len('val s = "a${b}c" // x\n')),
    ("literal_view_keeps_string",
     lambda V: "KEEP" in V["literal_view"]('val s = "KEEP"\n')),
    ("peel_also_receiver",
     lambda V: V["peel_discarding"]('File(dir, raw + ".json").also { stamp }') == 'File(dir, raw + ".json")'),
    ("peel_apply_receiver",
     lambda V: V["peel_discarding"]('x.apply { y() }.name') == 'x.name'),
    ("peel_nested_discard",
     lambda V: V["peel_discarding"]('a().also { b() }.onEach { c() }') == 'a()'),
    ("value_sources_also_only_receiver",
     lambda V: all("stamp" not in s for s in V["value_sources"]('(p + raw).also { stamp }'))),
    ("value_sources_let_body",
     lambda V: any("safe" in s for s in V["value_sources"]('dirty.let { safe }'))),
    ("value_sources_elvis_both",
     lambda V: len(V["value_sources"]('a ?: b')) == 2),
    ("value_sources_takeif_receiver",
     lambda V: "raw" in V["value_sources"]('raw.takeIf { it.isNotEmpty() } ?: fallback')[0]),
    ("contains_ident_boundary",
     lambda V: not V["contains_ident"]("rawStamp", "stamp")),
    ("contains_ident_hit",
     lambda V: V["contains_ident"]("File(dir, stamp + \".json\")", "stamp")),
    ("guard_feasible_false",
     lambda V: V["guard_feasible"]("1 == 2") is False),
    ("guard_feasible_not_true",
     lambda V: V["guard_feasible"]("!true") is False),
    ("guard_feasible_domain",
     lambda V: V["guard_feasible"]("n > 5", {"n": (0, 3)}) is False),
    ("guard_feasible_unknown",
     lambda V: V["guard_feasible"]("flag") is True),
    ("path_feasible_dead_branch",
     lambda V: V["path_feasible"](V["code_view"]("if (1 == 2) {\n  boom()\n}\n"),
                                 V["code_view"]("if (1 == 2) {\n  boom()\n}\n").index("boom")) is False),
    ("path_feasible_live_branch",
     lambda V: V["path_feasible"](V["code_view"]("if (ready) {\n  boom()\n}\n"),
                                 V["code_view"]("if (ready) {\n  boom()\n}\n").index("boom")) is True),
    # else 分支端到端（enclosing_conditions 的 `!(cond)` 括号化修复）：
    # `|| true` 的 else 是死分支（False），`&& false` 的 else 是活分支（True），
    # 简单条件 else 不回归（True）。期望值写死字面量。
    ("path_feasible_else_dead_or_true",
     lambda V: V["path_feasible"](
         V["code_view"]("if (a == null || true) {\n  ok()\n} else {\n  boom()\n}\n"),
         V["code_view"]("if (a == null || true) {\n  ok()\n} else {\n  boom()\n}\n").index("boom")) is False),
    ("path_feasible_else_live_and_false",
     lambda V: V["path_feasible"](
         V["code_view"]("if (x == null && false) {\n  ok()\n} else {\n  boom()\n}\n"),
         V["code_view"]("if (x == null && false) {\n  ok()\n} else {\n  boom()\n}\n").index("boom")) is True),
    ("path_feasible_else_simple_not_regressed",
     lambda V: V["path_feasible"](
         V["code_view"]("if (a == null) {\n  ok()\n} else {\n  boom()\n}\n"),
         V["code_view"]("if (a == null) {\n  ok()\n} else {\n  boom()\n}\n").index("boom")) is True),
    # ---- A2：value_sources 的 if/else 分叉（旧实现 100% 死代码）----
    # `)` 之后永远是 then 表达式，`re.match(r"else\b", rest)` 永不命中，于是
    # `if (c) a else b` 原样返回整条表达式、两侧来源都没被拆出来。
    ("value_sources_if_else_both",
     lambda V: V["value_sources"]("if (c) a else b") == ["a", "b"]),
    ("value_sources_if_true_then_only",
     lambda V: V["value_sources"]("if (true) a else b") == ["a"]),
    ("value_sources_if_false_else_only",
     lambda V: V["value_sources"]("if (false) a else b") == ["b"]),
    ("value_sources_nested_if_else_chain",
     lambda V: V["value_sources"]("if (c) a else if (d) b else z") == ["a", "b", "z"]),
    # 不是以 if 开头的表达式不许错切：`f(if (c) a else b)` 原样一条。
    ("value_sources_call_wrapped_not_split",
     lambda V: V["value_sources"]("f(if (c) a else b)") == ["f(if (c) a else b)"]),
    # 条件未知 + 有一侧是纯字面量 ⇒ 不拆：字面量分支不是「数据来源」，
    # 拆出来会把 require_all 型判据（"每条路径的值都必须经过消毒"）炸成误红。
    ("value_sources_null_branch_merged",
     lambda V: V["value_sources"]('if (has(x)) cap(x) else null')
     == ["if (has(x)) cap(x) else null"]),
    ("value_sources_literal_branch_merged",
     lambda V: V["value_sources"]("if (c) 1 else b") == ["if (c) 1 else b"]),
    # ---- 块体分支（CI #2760 实测红在 parts_json [R6]）----
    # `{ … }` 分支的值是尾表达式，不是带花括号的整段文本：不剥块的话门侧的
    # NULLISH 白名单（精确匹配 "null"/"Unit"）认不出 `{ null }`，消毒过的真
    # 代码会被 require_all 判据判成"存了未消毒的载荷"。期望值是字面量。
    ("value_sources_block_branch_split",
     lambda V: V["value_sources"]("if (c) { a } else { b }") == ["a", "b"]),
    ("value_sources_block_branch_mixed",
     lambda V: V["value_sources"]("if (c) { a } else b") == ["a", "b"]),
    ("value_sources_block_null_branch_merged",
     lambda V: V["value_sources"]("if (has(x)) { cap(x) } else { null }")
     == ["if (has(x)) { cap(x) } else { null }"]),
    ("branch_value_peels_block_tail",
     lambda V: V["branch_value"]("{\n  first()\n  second()\n}") == "second()"),
    ("branch_value_keeps_non_block",
     lambda V: V["branch_value"]("a ?: b") == "a ?: b"),
    ("branch_value_keeps_trailing_text",
     lambda V: V["branch_value"]("{ a } + b") == "{ a } + b"),
    # ---- A1 端到端：单行 if/else 的两条分支 ----
    ("path_feasible_single_line_else_dead",
     lambda V: V["path_feasible"](
         V["code_view"]("if (1 == 1) ok() else boom()\n"),
         V["code_view"]("if (1 == 1) ok() else boom()\n").index("boom")) is False),
    ("path_feasible_single_line_else_live",
     lambda V: V["path_feasible"](
         V["code_view"]("if (1 == 2) ok() else boom()\n"),
         V["code_view"]("if (1 == 2) ok() else boom()\n").index("boom")) is True),
    # ---- A3 端到端：when 的 subject 与块体臂 ----
    ("path_feasible_when_const_subject_dead",
     lambda V: V["path_feasible"](
         V["code_view"]("when (false) {\n  true -> dead()\n}\n"),
         V["code_view"]("when (false) {\n  true -> dead()\n}\n").index("dead()")) is False),
    ("path_feasible_when_block_arm_dead",
     lambda V: V["path_feasible"](
         V["code_view"]("when (false) {\n  true -> {\n    dead()\n  }\n}\n"),
         V["code_view"]("when (false) {\n  true -> {\n    dead()\n  }\n}\n").index("dead()")) is False),
    # dangling-else：`y()` 的真实条件是 `a && !b`，两种拼法都判活；外层条件
    # 若被错挂成 `!(a)` 则外层为「未知」，同样判活 —— 所以这条只看可达性，
    # 条件文本形态由 ast 侧的 enclosing_dangling_else_binds_inner 钉。
    ("path_feasible_dangling_else_alive",
     lambda V: V["path_feasible"](
         V["code_view"]("if (a) if (b) x() else y()\n"),
         V["code_view"]("if (a) if (b) x() else y()\n").index("y()")) is True),
    # ---- A4 端到端：复合条件里的数值比较 ----
    ("guard_feasible_cmp_inside_and",
     lambda V: V["guard_feasible"]("1 > 2 && g") is False),
    ("returns_exiting_plain",
     lambda V: V["returns_exiting"](V["code_view"]("fun f() {\n  if (x) return\n  y()\n}\n"),
                                    V["code_view"]("fun f() {\n  if (x) return\n  y()\n}\n").index("if"),
                                    V["code_view"]("fun f() {\n  if (x) return\n  y()\n}\n").index("y()")) is True),
    ("returns_labelled_not_exiting",
     lambda V: V["returns_exiting"](V["code_view"]("fun f() {\n  run { if (x) return@run }\n  y()\n}\n"),
                                    V["code_view"]("fun f() {\n  run { if (x) return@run }\n  y()\n}\n").index("run"),
                                    V["code_view"]("fun f() {\n  run { if (x) return@run }\n  y()\n}\n").index("y()")) is False),
    ("is_single_assignment_true",
     lambda V: V["is_single_assignment"](V["code_view"]("val a = safe()\nuse(a)\n"), "a") is True),
    ("is_single_assignment_reassigned",
     lambda V: V["is_single_assignment"](V["code_view"]("var a = safe()\na = dirty()\nuse(a)\n"), "a") is False),
    ("is_single_assignment_shadowed",
     lambda V: V["is_single_assignment"](V["code_view"]("val a = safe()\nrun {\n  val a = dirty()\n  use(a)\n}\n"), "a") is False),
    ("live_positions_skips_dead",
     lambda V: V["live_positions"](V["code_view"]("if (1 == 2) guard()\nwork()\nguard()\n"), r"guard\(\)") == [
         V["code_view"]("if (1 == 2) guard()\nwork()\nguard()\n").rindex("guard")]),
    ("brace_depth_simple",
     lambda V: V["brace_depth"](V["code_view"]("fun f() {\n  a()\n}\n"),
                                V["code_view"]("fun f() {\n  a()\n}\n").index("a()")) == 1),
    ("call_args_two",
     lambda V: len(V["call_args"]("File(dir, name)", 4)) == 2),
]


def selftest():
    import _kotlin_ast as A
    import _kotlin_valueflow as V
    ns = {"code_view": V.code_view, "literal_view": V.literal_view,
          "peel_discarding": V.peel_discarding, "value_sources": V.value_sources,
          "branch_value": V.branch_value,
          "contains_ident": V.contains_ident, "guard_feasible": V.guard_feasible,
          "path_feasible": V.path_feasible, "returns_exiting": V.returns_exiting,
          "is_single_assignment": V.is_single_assignment,
          "live_positions": V.live_positions, "brace_depth": V.brace_depth,
          "call_args": V.call_args}
    fails = []
    for name, fn in ARMS:
        try:
            if not fn(ns, **{}):
                fails.append(name)
        except Exception as exc:  # noqa: BLE001
            fails.append(f"{name}: {exc}")
    # 词法/语法原语的臂也要全绿（本模块依赖它们）
    if A.const_bool("!true && false") is not False:
        fails.append("ast.const_bool_precedence")
    if fails:
        for f in fails:
            print("SELFTEST FAIL:", f)
        return 1
    print(f"KOTLIN_VALUEFLOW_SELFTEST=PASS ({len(ARMS)} arms)")
    return 0


if __name__ == "__main__":
    raise SystemExit(selftest())
