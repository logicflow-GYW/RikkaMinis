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

__all__ = [
    'match_bracket', 'split_top', 'strip_parens',
    'const_value', 'const_bool', 'is_pure_literal',
    'stmt_spans', 'tail_expr', 'function_body',
    'if_blocks', 'when_branches', 'enclosing_conditions', 'last_write',
]

OPEN = {'(': ')', '[': ']', '{': '}'}
CLOSE = {')': '(', ']': '[', '}': '{'}
CONTINUATION = ('.', '?.', '?:', ':', '&&', '||', '+', '-', '*', '/', '%', '==', '!=',
                '<=', '>=', '=', '->', 'else', 'catch', 'finally', ')', ']', '}', ',')

IDENT = re.compile(r'\b[A-Za-z_][A-Za-z0-9_]*\b')


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

    覆盖：字面量、`!X`、`X && Y`、`X || Y`、`A == B`、`A != B`、括号。
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
    for op in ('==', '!=', '===', '!=='):
        parts = split_top(e, (op,))
        if len(parts) == 2:
            a = const_value(e[parts[0][0]:parts[0][1]])
            b = const_value(e[parts[1][0]:parts[1][1]])
            if a is None or b is None:
                return None
            eq = a == b
            return ('bool', eq if op in ('==', '===') else not eq)
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


def _branch_extent(text, i):
    '''从 i 起的一条「语句」区间终点：`{...}` 块，或单条语句（到深度 0 换行）。'''
    n = len(text)
    while i < n and text[i] in ' \t\r\n':
        i += 1
    if i >= n:
        return n
    if text[i] == '{':
        j = match_bracket(text, i)
        return j + 1 if j > 0 else n
    depth = 0
    j = i
    while j < n:
        ch = text[j]
        if ch in OPEN:
            depth += 1
        elif ch in CLOSE:
            if depth == 0:
                return j
            depth -= 1
        elif ch == '\n' and depth == 0 and not _is_continuation(
                text, j + 1, exclude=('else', 'catch', 'finally')):
            return j
        j += 1
    return n


def if_blocks(text):
    '''所有 if 结构：[(cond, then_start, then_end, else_start, else_end)]。

    支持 brace-bodied 与 braceless（单语句）两种形态。
    '''
    out = []
    for m in IF_RE.finditer(text):
        p_open = m.end() - 1
        p_close = match_bracket(text, p_open)
        if p_close < 0:
            continue
        cond = text[p_open + 1:p_close]
        t_start = p_close + 1
        t_end = _branch_extent(text, t_start)
        e_start = e_end = None
        rest = text[t_end:t_end + 40]
        k = rest.find('else')
        if k >= 0 and not rest[:k].strip():
            e_start = t_end + k + 4
            e_end = _branch_extent(text, e_start)
        out.append((cond, t_start, t_end, e_start, e_end))
    return out


WHEN_RE = re.compile(r'\bwhen\s*(\(|\{)')


def when_branches(text):
    '''所有 when 分支：[(subject, cond_text, body_start, body_end)]。'''
    out = []
    for m in WHEN_RE.finditer(text):
        if m.group(1) == '(':
            p_close = match_bracket(text, m.end() - 1)
            if p_close < 0:
                continue
            subject = text[m.end():p_close]
            k = p_close + 1
            while k < len(text) and text[k] in ' \t\r\n':
                k += 1
        else:
            subject = ''
            k = m.end() - 1
        if k >= len(text) or text[k] != '{':
            continue
        b_close = match_bracket(text, k)
        if b_close < 0:
            continue
        body = text[k + 1:b_close]
        # 分支用顶层换行切（每个分支形如 `cond -> expr` 或多行块）
        pos = 0
        for a, b in split_top(body, ('\n',)):
            seg = body[a:b]
            if '->' not in seg:
                pos = b
                continue
            arrow = seg.index('->')
            cond = seg[:arrow].strip()
            # 分支体内缩进可能有多行，这里只取箭头后到下一个分支起点
            out.append((subject, cond, k + 1 + a + arrow + 2, k + 1 + b))
            pos = b
    return out


def enclosing_conditions(text, pos):
    '''覆盖 pos 的所有条件：[(kind, cond_text)]，kind ∈ {'if', 'when'}。'''
    out = []
    for cond, t_start, t_end, e_start, e_end in if_blocks(text):
        if t_start <= pos < t_end:
            out.append(('if', cond))
        elif e_start is not None and e_start <= pos < e_end:
            out.append(('if', '!' + cond))
    for subject, cond, b_start, b_end in when_branches(text):
        if b_start <= pos < b_end:
            out.append(('when', cond))
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
    ('when_branch_cond',
     lambda A: A['when_branches']('when (v) {\n  false -> 1\n  else -> 2\n}')[0][1] == 'false'),
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
