#!/usr/bin/env python3
'''Kotlin 轻量语法原语 —— 全仓门脚本共用的单一实现。

背景（wide4 波）：24 道门各自手写常量折叠 / 括号扫描 / 语句切分，各自都残缺
一角，外部见证靠这些缺角逐个击穿：
  - 7 套不同的 always_false，只认字面 false，漏 `!true` / `0 == 1` / `when (false)`
  - 语句边界只认 `{` `}` `;`，而 Kotlin 几乎不用分号 -> 相邻语句被并成一条
  - 表达式体函数（`fun f() = ...`）的 body 用 `\\n\\n` 切，空行后整段消失

本模块只提供**客观可判的原语**，判决语义留给各门自己写（避免做成四不像）。

调用约定：所有 text 参数必须是 `_kotlin_lexer.mask_code()` 视图（注释和字符串
内容已抹平、位置与原文等长），这样括号/关键字扫描不会被文本内容污染。

自证：python3 _kotlin_ast.py --selftest
'''
import re
import sys

import _kotlin_lexer as _lexer

__all__ = [
    'match_bracket', 'split_top', 'strip_parens',
    'const_value', 'const_bool', 'is_pure_literal',
    'stmt_spans', 'tail_expr', 'function_body',
    'if_blocks', 'when_branches', 'enclosing_conditions', 'last_write',
    'masked_view',
]

OPEN = {'(': ')', '[': ']', '{': '}'}
CLOSE = {')': '(', ']': '[', '}': '{'}
CONTINUATION = ('.', '?.', '?:', ':', '&&', '||', '+', '-', '*', '/', '%', '==', '!=',
                '<=', '>=', '=', '->', 'else', 'catch', 'finally', ')', ']', '}', ',')

IDENT = re.compile(r'\b[A-Za-z_][A-Za-z0-9_]*\b')

# 最近用过的 token 掩码视图：[(text_obj, masked)]。
# 用**身份**比较而不是相等比较（大文件算两次不等价），并持有 text 的强引用，
# 这样 id() 不会被复用。缓存 4 条够覆盖"同一函数里交替问 if_blocks / when_branches"。
_MASK_CACHE = []
_MASK_CACHE_MAX = 4


def masked_view(text):
    '''等长的「注释 + 字符串/字符字面量内容」掩码视图（`_kotlin_lexer` 单一来源）。

    A1 修 `_branch_extent` 吞单行 `else` 时，判断「这个 else 是不是真关键字」
    必须过词法器：`if (c) s = "else" else d()` / `if (c) s = "a // else" else
    d()` 里前一个 `else` 是字符串内容，裸 `startswith('else')` 会在此截断，
    比原缺陷更糟（then 分支被切在字符串中间，else 分支丢掉真正的 then 尾巴）。

    约定不变：**门脚本仍应传 `_kotlin_lexer.mask_code()` 视图**（本模块是原语，
    不替门决定失败策略）。这里再掩一次是为了让"传了原文"的调用方也不被字符串
    里的关键字骗；对已掩码的文本是幂等的。

    解析失败（未闭合字符串/块注释）返回原文：原语不抛异常，门侧按自己的
    fail-closed 策略处理（多数门本来就会先 `analyse()` 判 ok）。
    '''
    for cached, masked in _MASK_CACHE:
        if cached is text:
            return masked
    try:
        ok, _reason, views = _lexer.analyse(text)
    except Exception:  # noqa: BLE001 —— 词法器自身异常也不该让原语崩
        ok, views = False, None
    masked = views['code'] if ok else text
    if len(masked) != len(text):  # 词法器契约是等长；不等长一律退回原文
        masked = text
    _MASK_CACHE.insert(0, (text, masked))
    del _MASK_CACHE[_MASK_CACHE_MAX:]
    return masked


def match_bracket(text, i):
    '''i 指向开括号，返回配对闭括号的下标；失败 -1。'''
    if i >= len(text) or text[i] not in OPEN:
        return -1
    stack = []
    for k in range(i, len(text)):
        ch = text[k]
        if ch in OPEN:
            stack.append(ch)
        elif ch in CLOSE:
            if not stack or stack[-1] != CLOSE[ch]:
                return -1
            stack.pop()
            if not stack:
                return k
    return -1


def split_top(text, seps=(',',)):
    '''按顶层（不在任何括号内）的分隔符切分，返回 [(start, end)]。

    seps 是字符串序列（支持多字符，如 '&&'）。空片段被丢弃。
    '''
    out, depth, start = [], 0, 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in OPEN:
            depth += 1
            i += 1
            continue
        if ch in CLOSE:
            depth -= 1
            i += 1
            continue
        if depth == 0:
            hit = None
            for s in seps:
                if text.startswith(s, i):
                    hit = s
                    break
            if hit:
                if i > start and text[start:i].strip():
                    out.append((start, i))
                i += len(hit)
                start = i
                continue
        i += 1
    if n > start and text[start:].strip():
        out.append((start, n))
    return out


def strip_parens(expr):
    '''剥掉整体包裹的括号：(( true )) -> true'''
    e = expr.strip()
    while e.startswith('('):
        j = match_bracket(e, 0)
        if j != len(e) - 1:
            break
        e = e[1:-1].strip()
    return e


NUM_RE = re.compile(r'^(?:0[xX][0-9a-fA-F_]+|\d[\d_]*\.?[\d_]*(?:[eE][+-]?\d+)?)[fFdDlL]?$')
STR_RE = re.compile(r'^(?:"(?:[^"\\]|\\.)*"|\'\'\'(?s:.*)\'\'\')$')


def const_value(expr):
    '''常量折叠。返回 ('bool', b) / ('num', float) / ('str', s) / ('null', None) / None。

    覆盖：字面量、`!X`、`X && Y`、`X || Y`、`A == B`、`A != B`、`A === B`、
    `A !== B`、数值比较 `A < B` / `<=` / `>` / `>=`、括号。字符串的大小比较
    与 `==` 的自反形态不折叠（语义依赖 equals()/compareTo()，保守返回 None）。
    优先级严格按 Kotlin：`||` < `&&` < 比较 < `!`（先切最低优先级的运算符，
    叶子再递归），因此 `!a && false` 折成 False 而不是 `!(a && false)`。
    '''
    e = strip_parens(expr)
    if not e:
        return None
    if e == 'true':
        return ('bool', True)
    if e == 'false':
        return ('bool', False)
    if e == 'null':
        return ('null', None)
    for op in ('||', '&&'):
        parts = split_top(e, (op,))
        if len(parts) > 1:
            vals = [const_value(e[a:b]) for a, b in parts]
            bs = [v[1] for v in vals if v and v[0] == 'bool']
            unknown = any(v is None or v[0] != 'bool' for v in vals)
            # 短路语义：`false && x` 恒假、`true || x` 恒真，另一侧未知不影响结论
            if op == '&&':
                if any(b is False for b in bs):
                    return ('bool', False)
                return None if unknown else ('bool', all(bs))
            if any(b is True for b in bs):
                return ('bool', True)
            return None if unknown else ('bool', any(bs))
    for op in ('===', '!==', '==', '!=', '<=', '>=', '<', '>'):
        if '->' in e:
            break  # lambda / when 臂箭头：`>` 会被当运算符切出假两半，不折叠
        # 多字符运算符必须排在它的前缀之前：`1 <= 2` 若先用 '<' 切，会得到
        # `1` 与 `= 2` 两半（旧实现把 '==' 排在 '===' 前面，`a === b` 同样错切）。
        parts = split_top(e, (op,))
        if len(parts) != 2:
            continue
        lt = strip_parens(e[parts[0][0]:parts[0][1]]).strip()
        rt = strip_parens(e[parts[1][0]:parts[1][1]]).strip()
        # 自反：两侧是同一段表达式 ⇒ `===` 恒真、`!==` 恒假。必须排在常量
        # 判定**之前**（两侧都折不出常量时 `x === x` 才走得到）。
        # `==` / `!=` 不折：它们走 equals()/compareTo()，病态实现可以返回
        # false，只有 `===`（引用/值严格相等）才允许据此判死。
        if op in ('===', '!==') and lt and lt == rt:
            return ('bool', op == '===')
        a = const_value(lt)
        b = const_value(rt)
        if a is None or b is None:
            return None
        if op in ('==', '==='):
            return ('bool', a == b)
        if op in ('!=', '!=='):
            return ('bool', a != b)
        # 大小比较只折数值：字符串走的是 compareTo/Comparable，语义不在本模块
        # 的建模范围内，宁可返回 None（保守 = 不据此判死）。
        if a[0] != 'num' or b[0] != 'num':
            return None
        x, y = a[1], b[1]
        return ('bool', {'<': x < y, '<=': x <= y, '>': x > y, '>=': x >= y}[op])
    if e.startswith('!'):
        v = const_value(e[1:])
        if v and v[0] == 'bool':
            return ('bool', not v[1])
        return None
    if NUM_RE.match(e):
        try:
            return ('num', float(re.sub(r'[_fFdDlL]', '', e)))
        except ValueError:
            return None
    if STR_RE.match(e):
        return ('str', e.strip('"\''))
    return None


def const_bool(expr):
    v = const_value(expr)
    return v[1] if v and v[0] == 'bool' else None


def is_pure_literal(expr):
    '''表达式是否不含任何标识符（除 true/false/null）与调用。'''
    e = strip_parens(expr)
    if not e:
        return False
    if const_value(e) is not None:
        return True
    for m in IDENT.finditer(e):
        if m.group(0) not in ('true', 'false', 'null'):
            return False
    return '(' not in e or not re.search(r'[A-Za-z_]\w*\s*\(', e)


def _is_continuation(text, i, exclude=()):
    '''换行位置 i 之后的内容是否属于上一行（续行）。

    双向判：下一行行首是续行标记（`.` `?.` `&&` `else` …），或上一行行尾是
    运算符（`+` `&&` `=` `,` 等）—— 任一成立即续行。
    '''
    n = len(text)
    j = i
    while j < n and text[j] in ' \t\r\n':
        j += 1
    if j >= n:
        return False
    nxt = [c for c in CONTINUATION if c not in exclude]
    if any(text.startswith(c, j) for c in nxt):
        return True
    if text[j] in '.,?:+-*/%&|=\'\"':
        return True
    # 上一行行尾（跳过换行与缩进，i 为换行之后的第一个位置）
    k = i - 1
    while k >= 0 and text[k] in ' \t\r\n':
        k -= 1
    if k >= 0:
        prev = text[max(0, k - 1):k + 1]
        if any(prev.endswith(c) for c in ('+', '-', '*', '/', '%', '&&', '||', '=', ',', '->', '.', '?.')):
            return True
    return False


def stmt_spans(body):
    '''顶层语句区间。Kotlin 无分号：深度 0 的换行即边界（续行除外）。'''
    spans = []
    depth = 0
    start = 0
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]
        if ch in OPEN:
            depth += 1
        elif ch in CLOSE:
            depth -= 1
            if depth <= 0:
                depth = 0
        elif ch == ';' and depth == 0:
            if body[start:i].strip():
                spans.append((start, i))
            start = i + 1
        elif ch == '\n' and depth == 0:
            if body[start:i].strip() and not _is_continuation(body, i + 1):
                spans.append((start, i))
                start = i + 1
        i += 1
    if n > start and body[start:].strip():
        spans.append((start, n))
    return spans


def tail_expr(body):
    '''块的最后一条顶层语句文本（去空白）；空块返回 ""。'''
    sp = stmt_spans(body)
    return body[sp[-1][0]:sp[-1][1]].strip() if sp else ''


FUN_RE = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(')


def function_body(text, name):
    '''返回 (body_start, body_end, kind)，kind ∈ {'block', 'expr'}；找不到返回 None。

    默认参数 lambda、表达式体都能正确处理：体起点从参数表右括号之后派生。
    '''
    for m in FUN_RE.finditer(text):
        if m.group(1) != name:
            continue
        p_open = m.end() - 1
        p_close = match_bracket(text, p_open)
        if p_close < 0:
            return None
        i = p_close + 1
        n = len(text)
        # 跳过返回类型标注，直到 '=' 或 '{'
        while i < n and text[i] not in '={':
            if text[i] == '\n':
                return None
            i += 1
        if i >= n:
            return None
        if text[i] == '{':
            j = match_bracket(text, i)
            return (i + 1, j, 'block') if j > 0 else None
        # 表达式体：到深度 0 且非续行的换行为止
        j = i + 1
        depth = 0
        while j < n:
            ch = text[j]
            if ch in OPEN:
                depth += 1
            elif ch in CLOSE:
                depth -= 1
                if depth < 0:
                    return (i + 1, j, 'expr')
            elif ch == '\n' and depth == 0 and not _is_continuation(text, j + 1):
                return (i + 1, j, 'expr')
            j += 1
        return (i + 1, n, 'expr')
    return None


IF_RE = re.compile(r'\bif\s*\(')
IF_KW_RE = re.compile(r'if\s*\(')
_ELSE_RE = re.compile(r'else(?![\w`])')


def _skip_ws(text, i):
    '''跳过空白（含换行）后的位置；不改动 text。'''
    n = len(text)
    while i < n and text[i] in ' \t\r\n':
        i += 1
    return i


def _else_at(masked, i):
    '''masked 的 i 处是否恰是 `else` **关键字**（词边界 + 不在字符串/注释里）。'''
    if i < 0 or i >= len(masked):
        return False
    if i > 0 and (masked[i - 1].isalnum() or masked[i - 1] in '_`'):
        return False
    return _ELSE_RE.match(masked, i) is not None


def _if_stmt_end(text, masked, i):
    '''从 i 起的**完整** if 语句终点（含它自己的 else 链）。失败 -1。

    裸分支里嵌套的 if 会把 else 吸给最近的那个 if（dangling-else）：
    `if (a) if (b) x() else y()` 的 then 分支覆盖整条内层 if-else。若不让它
    吸收，外层的 then 区间只到 `x()`，随后那个 else 会被判成**外层**的 else，
    于是 `y()` 被挂上 `!(a)` —— 真值是 `a`，方向反了（会把可达代码判死）。
    '''
    n = len(text)
    m = IF_KW_RE.match(masked, i)
    if not m:
        return -1
    p_close = match_bracket(masked, m.end() - 1)
    if p_close < 0:
        return -1
    t_end = _branch_extent(text, p_close + 1)
    if t_end <= p_close:
        return -1
    k = _skip_ws(masked, t_end)
    if k >= n or not _else_at(masked, k):
        return t_end
    e_end = _branch_extent(text, k + 4)
    return e_end if e_end > k else t_end


def _branch_extent(text, i):
    '''从 i 起的一条「语句」区间终点：`{...}` 块，或单条语句（到深度 0 换行）。

    单行 `if (c) a else b` 的 then 分支在**同行**的 `else` 处结束（A1）：旧实现
    只在「深度 0 换行」处停，`else` 被吞进 then 区间；随后 `if_blocks` 从 `t_end`
    之后再也找不到 `else`（已跨过行尾），于是整条 else 分支对 `path_feasible`
    隐形 —— `if (true) ok() else dead()` 的 dead 被当成可达。

    「这个 else 是不是关键字」一律问 `_kotlin_lexer` 的掩码视图，所以
    `s = "else"` / `s = "a // else"` / 三引号原始字符串里的 else 字样都不会
    被当成分支。
    '''
    n = len(text)
    i = _skip_ws(text, i)
    if i >= n:
        return n
    masked = masked_view(text)
    if masked[i] == '{':
        j = match_bracket(masked, i)
        return j + 1 if j > 0 else n
    # 裸 if 语句：整个 if-else 链都属于本条分支（dangling-else 归最近的 if）
    if IF_KW_RE.match(masked, i):
        j = _if_stmt_end(text, masked, i)
        if j > 0:
            return j
    depth = 0
    j = i
    while j < n:
        ch = masked[j]
        if ch in OPEN:
            depth += 1
        elif ch in CLOSE:
            if depth == 0:
                return j
            depth -= 1
        elif depth == 0 and _else_at(masked, j):
            return j
        elif ch == '\n' and depth == 0 and not _is_continuation(
                masked, j + 1, exclude=('else', 'catch', 'finally')):
            return j
        j += 1
    return n


def if_blocks(text):
    '''所有 if 结构：[(cond, then_start, then_end, else_start, else_end)]。

    支持 brace-bodied 与 braceless（单语句）两种形态，单行 `if (c) a else b`
    也能定位到 else（A1）。结构扫描走掩码视图，`cond` 取原文（调用方按约定
    传 `mask_code` 视图时两者一致）。
    '''
    out = []
    masked = masked_view(text)
    for m in IF_RE.finditer(masked):
        p_open = m.end() - 1
        p_close = match_bracket(masked, p_open)
        if p_close < 0:
            continue
        cond = text[p_open + 1:p_close]
        t_start = p_close + 1
        t_end = _branch_extent(text, t_start)
        e_start = e_end = None
        k = _skip_ws(masked, t_end)
        if _else_at(masked, k):
            e_start = k + 4
            e_end = _branch_extent(text, e_start)
        out.append((cond, t_start, t_end, e_start, e_end))
    return out


WHEN_RE = re.compile(r'\bwhen\s*(\(|\{)')


def _top_arrow(seg):
    '''seg 中深度 0 的第一个 `->` 下标；无则 -1。'''
    depth = 0
    i, n = 0, len(seg)
    while i < n:
        ch = seg[i]
        if ch in OPEN:
            depth += 1
        elif ch in CLOSE:
            depth -= 1
        elif depth == 0 and seg.startswith('->', i):
            return i
        i += 1
    return -1


def when_branches(text):
    '''所有 when 分支：[(subject, cond_text, body_start, body_end)]。

    `body_end` 取**下一个分支的起点**（最后一个分支取 when 体末尾），所以块体
    分支 `cond -> { … }` 的整段都落在区间内。旧实现按顶层换行切段、把段尾当
    分支终点，块体在第一个换行处就被截断 —— `when` 块里的代码对
    `enclosing_conditions` 完全隐形（A3）。

    `cond_text` 保持源码原样，**不**合成 subject 比较：消费门普遍直接匹配臂
    文本（`"else"` / `in 1..5` / `is Foo` / `a, b`），在这里改写会污染它们的
    判据；需要判活时由 `enclosing_conditions` 合成。
    '''
    out = []
    masked = masked_view(text)
    for m in WHEN_RE.finditer(masked):
        if m.group(1) == '(':
            p_close = match_bracket(masked, m.end() - 1)
            if p_close < 0:
                continue
            subject = text[m.end():p_close]
            k = _skip_ws(masked, p_close + 1)
        else:
            subject = ''
            k = m.end() - 1
        if k >= len(masked) or masked[k] != '{':
            continue
        b_close = match_bracket(masked, k)
        if b_close < 0:
            continue
        body = text[k + 1:b_close]
        body_m = masked[k + 1:b_close]
        base = k + 1
        # 臂起点：顶层片段里第一个「深度 0 的 ->」且箭头前有条件文本。
        # 嵌套 when / 块体里的 -> 落在更深的括号里，所以不会误判成新臂。
        starts = []
        for a, b in split_top(body_m, ('\n',)):
            arrow = _top_arrow(body_m[a:b])
            if arrow < 0:
                continue
            if not body_m[a:a + arrow].strip():
                continue
            starts.append((a, arrow))
        for idx, (a, arrow) in enumerate(starts):
            cond = body[a:a + arrow].strip()
            b_start = base + a + arrow + 2
            b_end = base + (starts[idx + 1][0] if idx + 1 < len(starts) else len(body))
            out.append((subject, cond, b_start, b_end))
    return out


_WILD_ARM = re.compile(r'(?:else|in|!in|is|!is)(?![\w])')
_SIMPLE_OPERAND = re.compile(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*')
_SIGNED_NUM = re.compile(r'-?\d+(?:\.\d+)?')


def _simple_operand(s):
    '''能否当作 `(<a>) == (<b>)` 的操作数：字面量或（可带点的）标识符。'''
    s = strip_parens(s).strip()
    if not s:
        return False
    if _SIMPLE_OPERAND.fullmatch(s):
        return True
    if _SIGNED_NUM.fullmatch(s):
        return True
    v = const_value(s)
    return v is not None and v[0] in ('str', 'num', 'bool', 'null')


def _when_cond_text(subject, cond):
    '''when 臂的条件文本：字面量/标识符臂合成 `(subject) == (arm)`。

    只合成这两种形态：`else` / `in 1..5` / `!in …` / `is Foo` / `!is Foo` /
    多条件 `a, b` 一律原样返回 —— 合成对它们语法非法，而且会污染下游门对臂
    文本的匹配（多条件臂的"或"语义本轮不建模）。
    '''
    subj = strip_parens(subject).strip()
    c = strip_parens(cond).strip()
    if not subj or not c:
        return cond
    if _WILD_ARM.match(c):
        return cond
    if not (_simple_operand(subj) and _simple_operand(c)):
        return cond
    return '(%s) == (%s)' % (subj, c)


def enclosing_conditions(text, pos):
    '''覆盖 pos 的所有条件：[(kind, cond_text)]，kind ∈ {'if', 'when'}。

    `else` 分支的条件返回**括号化的取反** `!(<cond>)`，不是裸 `!<cond>`：裸前缀
    在复合条件上会重绑定优先级（`!a == null || true` 读作 `(!a) == null || true`），
    于是 `if (a == null || true) … else …` 的死 else 判活、`if (x == null && false)
    … else …` 的活 else 判死（后者是静默放行方向）。括号化后语义与源码一致，
    消费方（`path_feasible` / `guard_feasible` / 各门）无需再做本地补丁。

    `when` 臂带上 subject（A3）：旧实现只返回臂文本 `'true'`，`when (false)
    { true -> … }` 因此判活。字面量/标识符臂合成为 `(<subject>) == (<arm>)`，
    其余臂形态（`else` / `in …` / `is …` / 多条件）保持原文，见 `_when_cond_text`。
    '''
    out = []
    for cond, t_start, t_end, e_start, e_end in if_blocks(text):
        if t_start <= pos < t_end:
            out.append(('if', cond))
        elif e_start is not None and e_start <= pos < e_end:
            out.append(('if', '!(' + cond + ')'))
    for subject, cond, b_start, b_end in when_branches(text):
        if b_start <= pos < b_end:
            out.append(('when', _when_cond_text(subject, cond)))
    return out


def last_write(text, name):
    '''变量 name 的末次写：返回 (pos, rhs_text, is_decl)。rhs 取 `=` 之后到语句结尾。'''
    pat = re.compile(r'(?:\bval|\bvar)?\s*\b' + re.escape(name) + r'\s*(?::[^=\n]+?)?=(?!=)')
    last = None
    for m in pat.finditer(text):
        is_decl = bool(re.search(r'\b(?:val|var)\s+$', text[:m.start()] + ' ') or
                       re.search(r'\b(?:val|var)\s+' + re.escape(name) + r'\b', text[max(0, m.start() - 40):m.end()]))
        rhs_start = m.end()
        end = _branch_extent(text, rhs_start)
        last = (m.start(), text[rhs_start:end].strip(), is_decl)
    return last


ARMS = [
    ('match_paren', lambda A: A['match_bracket']('f(a, (b), c)', 1) == 11),
    ('match_brace_nested', lambda A: A['match_bracket']('{ a { b } c }', 0) == 12),
    ('match_unbalanced', lambda A: A['match_bracket']('{ a ', 0) == -1),
    ('split_top_commas', lambda A: len(A['split_top']('a, f(b, c), d')) == 3),
    ('split_top_ops', lambda A: len(A['split_top']('x && y || z', ('&&', '||'))) == 3),
    ('strip_parens', lambda A: A['strip_parens']('(( true ))') == 'true'),
    ('const_true', lambda A: A['const_bool']('true') is True),
    ('const_not_true', lambda A: A['const_bool']('!true') is False),
    ('const_num_eq', lambda A: A['const_bool']('1 == 2') is False),
    ('const_num_eq_true', lambda A: A['const_bool']('2 == 2') is True),
    ('const_and_literal', lambda A: A['const_bool']('false && guard') is False),
    ('const_or_literal_first', lambda A: A['const_bool']('true || guard') is True),
    ('const_ne_null_unknown', lambda A: A['const_bool']('sid != null') is None),
    ('const_str_eq', lambda A: A['const_bool']('"a" == "a"') is True),
    ('const_not_then_and_short_circuit', lambda A: A['const_bool']('!a && false') is False),
    ('const_not_true_and_false', lambda A: A['const_bool']('!true && false') is False),
    ('const_not_true_and_true', lambda A: A['const_bool']('!true && true') is False),
    ('const_not_paren_group', lambda A: A['const_bool']('!(true && false)') is True),
    ('const_or_after_not', lambda A: A['const_bool']('!false || x') is True),
    ('pure_literal_num', lambda A: A['is_pure_literal']('1') is True),
    ('pure_literal_ident', lambda A: A['is_pure_literal']('someVal') is False),
    ('stmt_spans_no_semicolon',
     lambda A: len(A['stmt_spans']('val a = 1\nval b = 2\n')) == 2),
    ('stmt_spans_continuation',
     lambda A: len(A['stmt_spans']('val a = 1 +\n    2\nval b = 3\n')) == 2),
    ('stmt_spans_block_one',
     lambda A: len(A['stmt_spans']('if (x) {\n  a()\n}\nb()\n')) == 2),
    ('tail_expr_last',
     lambda A: A['tail_expr']('val a = 1\nreturn f(a)\n') == 'return f(a)'),
    ('fun_body_block',
     lambda A: A['function_body']('fun f(a: Int = { 1 }): Int {\n  return 1\n}', 'f') == (28, 40, 'block')),
    ('fun_body_expr',
     lambda A: A['function_body']('fun g(): Int = 1 +\n  2\nfun h() {}', 'g')[2] == 'expr'),
    ('if_blocks_then', lambda A: A['if_blocks']('if (a) x()\nelse y()\n')[0][0] == 'a'),
    ('if_blocks_else_present', lambda A: A['if_blocks']('if (a) x()\nelse y()\n')[0][3] is not None),
    ('enclosing_if_for_braceless',
     lambda A: [c for k, c in A['enclosing_conditions']('if (a) if (b) x()\n', 15)] == ['a', 'b']),
    # else 侧必须是**括号化**取反：裸 `!` 在复合条件上重绑定优先级
    # （`!a == null || true` 恒真 → 死 else 判活；`!x == null && false` 恒假 →
    #   活 else 判死）。期望值是字面量，不从被测实现推导。
    ('enclosing_else_compound_parenthesised',
     lambda A: [c for k, c in A['enclosing_conditions'](
         'if (a == null || true) {\n  x()\n} else {\n  y()\n}\n',
         'if (a == null || true) {\n  x()\n} else {\n  y()\n}\n'.index('y()'))]
     == ['!(a == null || true)']),
    ('enclosing_else_simple_parenthesised',
     lambda A: [c for k, c in A['enclosing_conditions'](
         'if (a == null) {\n  x()\n} else {\n  y()\n}\n',
         'if (a == null) {\n  x()\n} else {\n  y()\n}\n'.index('y()'))]
     == ['!(a == null)']),
    ('when_branch_cond',
     lambda A: A['when_branches']('when (v) {\n  false -> 1\n  else -> 2\n}')[0][1] == 'false'),
    # ---- A1：单行 `if (c) a else b` 的 else 必须被定位到 ----
    # 旧实现只在深度 0 换行处停，else 被吞进 then 区间，`if_blocks` 随后在
    # t_end 之后（已跨过行尾）再也找不到 else → 整条 else 对 path_feasible 隐形。
    ('if_blocks_single_line_else_span',
     lambda A: (lambda t: t[A['if_blocks'](t)[0][3]:A['if_blocks'](t)[0][4]].strip())(
         'if (c) x() else y()\n') == 'y()'),
    ('enclosing_single_line_else_text',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'if (c) x() else y()\n', 'if (c) x() else y()\n'.index('y()'))] == ['!(c)']),
    # 脏臂：字符串里的 `else` 不是分支边界（裸 startswith('else') 会在字符串内部
    # 截断 then 分支，比原缺陷更糟）。期望值是字面量。
    ('enclosing_else_string_literal_not_keyword',
     lambda A: (lambda t: ([c for _k, c in A['enclosing_conditions'](t, t.index('d()'))] == ['!(c)']
                           and [c for _k, c in A['enclosing_conditions'](t, t.index('else'))] == ['c']))(
         'if (c) s = "else" else d()\n')),
    ('enclosing_else_comment_marker_in_string',
     lambda A: (lambda t: ([c for _k, c in A['enclosing_conditions'](t, t.index('d()'))] == ['!(c)']
                           and [c for _k, c in A['enclosing_conditions'](t, t.index('else'))] == ['c']))(
         'if (c) s = "a // else" else d()\n')),
    ('enclosing_else_raw_string_not_keyword',
     lambda A: (lambda t: [c for _k, c in A['enclosing_conditions'](t, t.index('d()'))] == ['!(c)'])(
         'if (c) s = """else""" else d()\n')),
    ('enclosing_else_after_line_comment',
     lambda A: (lambda t: t[A['if_blocks'](t)[0][3]:A['if_blocks'](t)[0][4]].strip())(
         'if (c) x() // note\nelse y()\n') == 'y()'),
    # dangling-else 归最近的 if：外层 then 覆盖整条内层 if-else，`y()` 的条件
    # 是 `a && !b`，不是 `!a`（后者方向反了，会把可达代码判死）。
    ('enclosing_dangling_else_binds_inner',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'if (a) if (b) x() else y()\n', 'if (a) if (b) x() else y()\n'.index('y()'))] == ['a', '!(b)']),
    # ---- A3：when 块体臂 + subject ----
    # 旧实现按顶层换行切段、用段尾当分支终点，块体在第一个换行处截断 →
    # 块内代码对 enclosing_conditions 完全隐形。
    ('when_block_arm_span_covers_block',
     lambda A: (lambda t: t[A['when_branches'](t)[0][2]:A['when_branches'](t)[0][3]].strip())(
         'when (x) {\n  true -> {\n    z()\n  }\n  else -> 2\n}\n') == '{\n    z()\n  }'),
    ('when_block_arm_encloses_inner_line',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'when (x) {\n  true -> {\n    z()\n  }\n  else -> 2\n}\n',
         'when (x) {\n  true -> {\n    z()\n  }\n  else -> 2\n}\n'.index('z()'))] == ['(x) == (true)']),
    ('when_const_subject_synthesised',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'when (false) {\n  true -> dead()\n}\n',
         'when (false) {\n  true -> dead()\n}\n'.index('dead()'))] == ['(false) == (true)']),
    # 非字面量臂必须原样保留：合成 `in 1..5` / `is Foo` / `1, 2` 会语法非法，
    # 还会污染下游门对臂文本的匹配（消费门普遍直接匹配这些文本）。
    ('when_wild_in_arm_kept_raw',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'when (x) {\n  in 1..5 -> a()\n  else -> b()\n}\n',
         'when (x) {\n  in 1..5 -> a()\n  else -> b()\n}\n'.index('a()'))] == ['in 1..5']),
    ('when_wild_is_arm_kept_raw',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'when (s) {\n  is Foo -> a()\n}\n', 'when (s) {\n  is Foo -> a()\n}\n'.index('a()'))] == ['is Foo']),
    ('when_multi_cond_arm_kept_raw',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'when (x) {\n  1, 2 -> a()\n}\n', 'when (x) {\n  1, 2 -> a()\n}\n'.index('a()'))] == ['1, 2']),
    ('when_ident_arm_synthesised',
     lambda A: [c for _k, c in A['enclosing_conditions'](
         'when (s) {\n  null -> b()\n}\n', 'when (s) {\n  null -> b()\n}\n'.index('b()'))] == ['(s) == (null)']),
    # ---- A4：const_value 数值比较 / 自反 ----
    ('const_num_lt', lambda A: A['const_bool']('1 < 2') is True),
    ('const_num_le_two_char_op', lambda A: A['const_bool']('2 <= 2') is True),
    ('const_num_gt_false', lambda A: A['const_bool']('3 > 4') is False),
    ('const_num_ge_two_char_op', lambda A: A['const_bool']('3 >= 3') is True),
    ('const_cmp_inside_and_folds', lambda A: A['const_bool']('1 > 2 && guard') is False),
    ('const_selfref_strict_eq', lambda A: A['const_bool']('x === x') is True),
    ('const_selfref_strict_ne', lambda A: A['const_bool']('x !== x') is False),
    # 保守：字符串顺序比较走 compareTo，`==` 自反走 equals —— 都不折叠。
    ('const_str_order_not_folded', lambda A: A['const_bool']('"a" < "b"') is None),
    ('const_eq_selfref_not_folded', lambda A: A['const_bool']('x == x') is None),
    ('last_write_second',
     lambda A: A['last_write']('var d = 1\nd = 2\n', 'd')[1] == '2'),
]


def selftest():
    ns = {k: globals()[k] for k in __all__}
    fails = []
    for name, fn in ARMS:
        try:
            if fn(ns) is not True:
                fails.append(name)
        except Exception as e:  # noqa: BLE001
            fails.append(f'{name} ({type(e).__name__}: {e})')
    if fails:
        for f in fails:
            print('  FAIL ' + f)
        print(f'KOTLIN_AST_SELFTEST: {len(fails)} failure(s) / {len(ARMS)} arms')
        return 1
    print(f'KOTLIN_AST_SELFTEST=PASS ({len(ARMS)} arms)')
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    print(__doc__)
