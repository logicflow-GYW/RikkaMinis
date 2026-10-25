#!/usr/bin/env python3
"""Kotlin 词法投影 —— 全仓门脚本共用的单一掩码器。

背景（wide4 波）：24 道门各自手写 strip_comments / mask_code / blank，结果在
Kotlin 原生的四类词法构造上系统性失明，被外部见证逐个击穿：

  - 嵌套块注释 `/* outer /* inner */ still-comment */`（7 道门遇第一个 `*/` 就退出）
  - 单引号字符字面量 `'"'`（4 道门把它当成字符串起始，吞掉后面整段真代码）
  - 三引号原始字符串（triple-quote raw string），含结尾连续引号
  - 字符串模板 `${...}` 的嵌套插值（9 道门括号深度错位）

统一到一个状态机后，门只调用本模块，不再自己写正则。

三个视图（**严格等长**，位置可与原文一一对应，换行保留）：

  mask_comments(src) -> 注释抹成空格，字符串/代码原样
  mask_strings(src)  -> 字符串**内容**抹成空格（定界符保留），注释原样
  mask_code(src)     -> 注释 + 字符串内容都抹成空格

为什么等长很重要：门脚本大量用 `text[pos:pos+n]` / `re.finditer` 的位置，
非等长替换会让所有下游位置漂移。

解析失败（引号/块注释未闭合）不抛异常、不静默返回原文：`analyse()` 返回
ok=False + reason，门必须 **fail-closed** 报违规（parser-death 轴）。

自证：python3 _kotlin_lexer.py --selftest
"""
import sys

__all__ = ['analyse', 'mask_comments', 'mask_strings', 'mask_code', 'KotlinLexError']


class KotlinLexError(Exception):
    pass


def _blank(chars, start, end, keep_newline=True):
    for k in range(start, end):
        if keep_newline and chars[k] == '\n':
            continue
        chars[k] = ' '


def analyse(src):
    """扫描一次，返回 (ok, reason, views)。

    views = {'comments': str, 'strings': str, 'code': str}
      comments: 注释抹空格（字符串原样）
      strings : 字符串内容抹空格（注释原样）
      code    : 两者都抹
    """
    n = len(src)
    no_comments = list(src)   # 注释抹除
    no_strings = list(src)    # 字符串内容抹除
    no_code = list(src)       # 两者都抹

    i = 0
    while i < n:
        c = src[i]

        # ---- 行注释 ----
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            if j < 0:
                j = n
            _blank(no_comments, i, j)
            _blank(no_code, i, j)
            i = j
            continue

        # ---- 块注释（Kotlin 支持嵌套）----
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            depth = 0
            j = i
            while j < n:
                if src.startswith('/*', j):
                    depth += 1
                    j += 2
                elif src.startswith('*/', j):
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                else:
                    j += 1
            if depth != 0:
                return False, f'unterminated block comment at offset {i}', None
            _blank(no_comments, i, j)
            _blank(no_code, i, j)
            i = j
            continue

        # ---- 三引号原始字符串 ----
        if src.startswith('"""', i):
            j = i + 3
            end = -1
            while j < n:
                if src[j] == '"':
                    # 统计连续引号数，Kotlin 取最后 3 个作为定界符
                    k = j
                    while k < n and src[k] == '"':
                        k += 1
                    run = k - j
                    if run >= 3:
                        end = k              # 内容结束于 k-3，定界符占最后 3 个
                        break
                    j = k
                    continue
                j += 1
            if end < 0:
                return False, f'unterminated raw string at offset {i}', None
            content_end = end - 3
            _blank(no_strings, i + 3, content_end)
            _blank(no_code, i + 3, content_end)
            # 保留定界符：前 3 个引号 与 最后 3 个引号 已在 no_strings 中未抹
            i = end
            continue

        # ---- 普通字符串（含 ${...} 模板插值）----
        if c == '"':
            j = i + 1
            failed = False
            while j < n:
                ch = src[j]
                if ch == '\\':
                    j += 2
                    continue
                if ch == '"':
                    j += 1
                    break
                if ch == '$' and j + 1 < n and src[j + 1] == '{':
                    # 模板插值：整体视作字符串内容抹除；需正确跳过其内部
                    k = _skip_template(src, j + 1)
                    if k < 0:
                        failed = True
                        break
                    j = k
                    continue
                if ch == '\n':
                    # 普通字符串不允许裸换行 —— 未闭合
                    failed = True
                    break
                j += 1
            else:
                failed = True
            if failed or j > n:
                return False, f'unterminated string literal at offset {i}', None
            _blank(no_strings, i + 1, j - 1)
            _blank(no_code, i + 1, j - 1)
            i = j
            continue

        # ---- 反引号标识符（Kotlin 允许 `it's a name` 这种任意字符）----
        if c == '`':
            j = src.find('`', i + 1)
            if j < 0:
                return False, f'unterminated backtick identifier at offset {i}', None
            i = j + 1
            continue

        # ---- 字符字面量 ----
        if c == "'":
            j = i + 1
            failed = False
            while j < n:
                ch = src[j]
                if ch == '\\':
                    j += 2
                    continue
                if ch == "'":
                    j += 1
                    break
                if ch == '\n':
                    failed = True
                    break
                j += 1
            else:
                failed = True
            if failed or j > n:
                # 单引号在 Kotlin 里只可能是 char 字面量；未闭合 = 源码异常
                return False, f'unterminated char literal at offset {i}', None
            _blank(no_strings, i + 1, j - 1)
            _blank(no_code, i + 1, j - 1)
            i = j
            continue

        i += 1

    views = {'comments': ''.join(no_comments),
             'strings': ''.join(no_strings),
             'code': ''.join(no_code)}
    return True, '', views


def _skip_template(src, brace_pos):
    """从 `${` 的 `{` 起跳过配对的 `}`（内部可能有字符串/char/注释/嵌套模板）。

    返回 `}` 之后的位置；解析失败返回 -1。
    """
    n = len(src)
    depth = 0
    j = brace_pos
    while j < n:
        ch = src[j]
        if ch == '{':
            depth += 1
            j += 1
            continue
        if ch == '}':
            depth -= 1
            j += 1
            if depth == 0:
                return j
            continue
        if src.startswith('//', j):
            k = src.find('\n', j)
            j = n if k < 0 else k
            continue
        if src.startswith('/*', j):
            d = 0
            while j < n:
                if src.startswith('/*', j):
                    d += 1
                    j += 2
                elif src.startswith('*/', j):
                    d -= 1
                    j += 2
                    if d == 0:
                        break
                else:
                    j += 1
            if d != 0:
                return -1
            continue
        if src.startswith('"""', j):
            k = j + 3
            end = -1
            while k < n:
                if src[k] == '"':
                    m = k
                    while m < n and src[m] == '"':
                        m += 1
                    if m - k >= 3:
                        end = m
                        break
                    k = m
                    continue
                k += 1
            if end < 0:
                return -1
            j = end
            continue
        if ch == '"':
            k = j + 1
            while k < n:
                if src[k] == '\\':
                    k += 2
                    continue
                if src[k] == '"':
                    k += 1
                    break
                if src[k] == '$' and k + 1 < n and src[k + 1] == '{':
                    k2 = _skip_template(src, k + 1)
                    if k2 < 0:
                        return -1
                    k = k2
                    continue
                k += 1
            else:
                return -1
            j = k
            continue
        if ch == "'":
            k = j + 1
            while k < n:
                if src[k] == '\\':
                    k += 2
                    continue
                if src[k] == "'":
                    k += 1
                    break
                k += 1
            else:
                return -1
            j = k
            continue
        j += 1
    return -1


def _fallback(src):
    ok, reason, views = analyse(src)
    if ok:
        return views
    raise KotlinLexError(reason)


def mask_comments(src):
    return _fallback(src)['comments']


def mask_strings(src):
    return _fallback(src)['strings']


def mask_code(src):
    return _fallback(src)['code']


# --------------------------------------------------------------------------
# selftest：每条臂对应一个真实被见证击穿的词法构造
# --------------------------------------------------------------------------
ARMS = [
    # (name, src, view, expected_fragment, must_be_present)
    ('nested_block_comment_kept_masked', 'a\n/* x /* y */ STILL_COMMENT */\nb', 'comments', 'STILL_COMMENT', False),
    ('nested_block_comment_code_visible', 'a\n/* x /* y */ STILL_COMMENT */\nb', 'comments', '\nb', True),
    ('char_literal_double_quote', 'val c = \'"\'\nval after = LIVE()\n', 'code', 'LIVE', True),
    ('char_literal_escape', "val c = '\\''\nval after = LIVE2()\n", 'code', 'LIVE2', True),
    ('raw_string_body_masked', 'val s = """body /* not comment */"""\nval after = LIVE3()\n', 'code', 'LIVE3', True),
    ('raw_string_body_masked_content', 'val s = """BODY"""\n', 'code', 'BODY', False),
    ('raw_string_four_quotes', 'val s = """a""""\nval after = LIVE4()\n', 'code', 'LIVE4', True),
    ('template_nested_brace', 'val s = "x${ if (a) { "}" } else "{" }y"\nval after = LIVE5()\n', 'code', 'LIVE5', True),
    ('template_nested_quote', 'val s = "${f(")"}"\nval after = LIVE6()\n', 'code', 'LIVE6', True),
    ('backtick_ident_with_quote', 'fun `it\'s \"x\"`() {}\nval after = LIVE8()\n', 'code', 'LIVE8', True),
    ('backtick_ident_not_masked', 'fun `a b`() {}\n', 'code', '`a b`', True),
    ('line_comment_masked', 'val a = 1 // hidden\nval after = LIVE7()\n', 'code', 'hidden', False),
    ('line_comment_newline_kept', 'val a = 1 // hidden\nval after = LIVE7()\n', 'code', '\nval after', True),
    ('length_preserved_raw', 'val s = """abc"""\n', 'code', None, None),
    ('length_preserved_template', 'val s = "a${ b }c"\n', 'code', None, None),
    ('unterminated_string_fails', 'val s = "abc\n', 'code', 'FAIL', None),
    ('unterminated_block_comment_fails', '/* abc\n', 'code', 'FAIL', None),
    ('string_kept_after_comment_mask', 'val s = "KEEP"\n', 'comments', 'KEEP', True),
    ('string_masked_in_code_view', 'val s = "KEEP"\n', 'code', 'KEEP', False),
    ('comment_kept_in_strings_view', 'val a = 1 /* KEEPC */\n', 'strings', 'KEEPC', True),
]


def selftest():
    fails = []
    for arm in ARMS:
        name, src, view, frag, present = arm
        ok, reason, views = analyse(src)
        if name.startswith('unterminated'):
            if ok:
                fails.append(f'{name}: expected parse failure, got ok')
            continue
        if not ok:
            fails.append(f'{name}: unexpected parse failure {reason}')
            continue
        if name.startswith('length_preserved'):
            for k, v in views.items():
                if len(v) != len(src):
                    fails.append(f'{name}: view {k} length {len(v)} != {len(src)}')
            continue
        got = frag in views[view]
        if got != bool(present):
            fails.append(f'{name}: expected {frag!r} '
                         f'{"present" if present else "absent"} in {view} view')
    if fails:
        for f in fails:
            print('  FAIL ' + f)
        print(f'KOTLIN_LEXER_SELFTEST: {len(fails)} failure(s) / {len(ARMS)} arms')
        return 1
    print(f'KOTLIN_LEXER_SELFTEST=PASS ({len(ARMS)} arms)')
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    print(__doc__)
