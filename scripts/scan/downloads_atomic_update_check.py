#!/usr/bin/env python3
"""[deep-audit-1016c FIX-C + meta-audit-1024 wide sweep] Gate: `_downloads` writes
must be atomic **and** the atomic API must actually be used atomically.

ISSUE-03C — the four write sites in BrowserTabPool.kt were non-atomic
read-modify-writes on a `MutableStateFlow`:

    _downloads.value = listOf(entry) + _downloads.value   (registerDownload)
    _downloads.value = _downloads.value.map { … }          (updateDownloadEntry)
    _downloads.value = _downloads.value.map { … }          (markDownloadsSeen)
    _downloads.value = _downloads.value.filter { … }       (clearFinishedDownloads)

Writers span three threads (URL-download listener on Main, the blob
download bridge on the WebView JavaBridge thread, download coroutines on IO),
so a lost update silently drops a download row from the panel or regresses a
progress/settle state. They now use `MutableStateFlow.update { }` (CAS loop).

Rules (BrowserTabPool.kt, where `_downloads` is private):

  R1  no read-modify-write: a write whose right-hand side mentions the current
      value again (`_downloads.value`, or the receiver's own `value`) →
      violation.  Covers the explicit form and the implicit-receiver forms
      routed through R4.
  R2  no full-replace write at all, unless the same line carries an explicit
      `ALLOWED-FULL-REPLACE` marker (a conscious opt-out for a write that
      genuinely cannot race, e.g. installing a value that does not depend on
      the current one).
  R3  [wide-1024 B-22] a `_downloads.update { … }` lambda must consume the
      current value the CAS loop hands it. Calling `update` is not the same
      as updating atomically: if the lambda ignores its parameter and maps a
      snapshot captured earlier (`val snap = _downloads.value` → `update {
      snap.map { … } }`), every CAS retry recomputes from the stale snapshot
      and the concurrent write is still lost.  Red when: the body reads
      `_downloads.value`; or it references a pre-captured `val x =
      _downloads.value` binding; or an explicit parameter is never used at the
      body's top level.
    R5  [wide3-08 R2] the shared-flow entrances are the same write:
      `_downloads.tryEmit(x)` / `.emit(x)` on a MutableStateFlow replace the
      value outright (no CAS), and `getAndSet(x)` does the same. Red when the
      handed-over value is derived from the current state, or when the write
      carries no `ALLOWED-FULL-REPLACE` marker.
  R6  [wide3-08 parser-death] every brace/paren scan is string- AND
      block-comment-aware (`_mask`): `/* } */` inside a block used to close a
      `_downloads.apply { … }` span early and drop the real write out of it.
      A reference that only sits in a dead half (`if (false) current else …`,
      `false && current…`) does not count as consuming the CAS parameter.

R4  [wide-1024 B-22] the write surface is the *receiver*, not the spelling
      `_downloads.value =`. A write routed through `apply` / `run` / `with`
      (bare `value =`) or `let` / `also` (`it.value =`) is the same mutation
      and must satisfy R1/R2.  Previously the gate only matched the literal
      `_downloads.value =`, so `_downloads.apply { value = … }` slipped past
      every rule.

Exit 0 = PASS, 1 = FAIL (prints file:line for each violation).
`--selftest` runs the fixtures below: the compliant one must stay clean and
every witness-shaped one must red the *matching* rule.

Interface: `python3 scripts/scan/downloads_atomic_update_check.py [REPO_ROOT]`
(defaults to cwd; also accepts `--root ROOT`). Registration into
scripts/scan/scan.sh is the main session's job (this branch is forbidden from
touching scan.sh / test_scan.py).
"""
import os
import re
import sys

# [wide4-09] 共享 Kotlin 词法器。本门上一轮自己写了一套 `_mask`：块注释只找第一个
# `*/`（Kotlin 的块注释可嵌套，`/* a /* b */ c */` 里的 c 会被当成活代码）、
# 字符字面量按字符串处理。外部见证各一击穿 —— 门自己再手写词法器 = 直接违规。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as _lex_analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool as _const_bool,
    function_body as _function_body,
    if_blocks as _if_blocks,
)
from _kotlin_valueflow import expr_end as _expr_end  # noqa: E402
from _kotlin_valueflow import path_feasible as _path_feasible  # noqa: E402

REL = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"

RECV = "_downloads"
# [wide4-09] 真树的原子写站点数下限（BrowserTabPool 当前 5 个 update { }）
BASELINE_UPDATE_SITES = 5
WRITE_RE = re.compile(r"_downloads\s*\.\s*value\s*=(?!=)")          # explicit
# [wide3-08 R2] the write surface is the RECEIVER, not one spelling of it.
# `MutableStateFlow` inherits `tryEmit` / `emit` from `MutableSharedFlow`; on a
# StateFlow both replace the current value outright, exactly like
# `_downloads.value = x` (the CAS loop is bypassed). `getAndSet` does the same.
EMIT_RE = re.compile(r"\b_downloads\s*\.\s*(tryEmit|emit|getAndSet)\s*\(")
RECV_BLOCK = re.compile(r"\b_downloads\s*\.\s*(apply|run|let|also)\s*\{")
WITH_BLOCK = re.compile(r"\bwith\s*\(\s*_downloads\s*\)\s*\{")
UPDATE_RE = re.compile(r"\b_downloads\s*\.\s*update\s*\{")
# CAS entrances that hand the lambda the current value (R3 applies to each).
CAS_LAMBDA_RE = re.compile(r"\b_downloads\s*\.\s*(?:update|updateAndGet|getAndUpdate)\s*\{")
VALUE_WRITE_THIS = re.compile(r"(?<![\w.])(?:this\s*\.\s*)?value\s*=(?!=)")  # apply/run/with
VALUE_WRITE_IT = re.compile(r"(?<![\w.])it\s*\.\s*value\s*=(?!=)")            # let/also
MARKER = "ALLOWED-FULL-REPLACE"

# ── [wide3-08 R3] read aliases ──────────────────────────────────────────────
# `val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()`
# exposes the SAME state under another name. A snapshot taken through it
# (`val stale = downloads.value`) is the same stale read as `_downloads.value`,
# so the criterion has to derive the alias set instead of spelling the private
# field. Derived (not hard-coded) so renaming the alias cannot disarm it.
READ_ALIAS_BIND = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*_downloads\s*\.\s*"
    r"(?:asStateFlow|asSharedFlow|asFlow)\s*\(")

def _read_receivers(src):
    """`_downloads` + 它的只读视图别名 + 这些别名的别名（传递闭包）。

    [wide4-09 尝试 2] `val flowAlias = downloads`（别名的别名）上一轮不在集合里，
    从它取快照（`val stale = flowAlias.value`）既不触发「重读状态」也不进快照表，
    CAS lambda 映射 stale 全绿。
    """
    names = list(_alias_names(src, RECV))
    changed = True
    while changed:
        changed = False
        for r in list(names):
            pat = (r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*%s\s*\.\s*"
                   r"(?:asStateFlow|asSharedFlow|asFlow)\s*\(" % re.escape(r))
            for m in re.finditer(pat, src):
                if m.group(1) not in names:
                    names.append(m.group(1))
                    changed = True
                for n in [_alias_names(src, m.group(1))]:
                    for x in n:
                        if x not in names:
                            names.append(x)
                            changed = True
        for r in list(names):
            for x in _alias_names(src, r):
                if x not in names:
                    names.append(x)
                    changed = True
    return names


def _reads_state(expr, receivers):
    """True when `expr` reads the CURRENT download state (raw `.value`)."""
    for r in receivers:
        if re.search(r"(?<![\w.])%s\s*\.\s*value\b" % re.escape(r), expr):
            return True
    return False


# ── source helpers ──────────────────────────────────────────────────────────
def _lex_views(src):
    """(ok, reason, views)；ok=False 时调用方必须 fail-closed。"""
    return _lex_analyse(src)


def _mask(src):
    """注释 + 字符串内容抹空（长度保持）。

    [wide4-09] 词法交给共享模块：Kotlin 块注释可嵌套（`/* a /* b */ c */`），
    手写版遇第一个 `*/` 就退出，于是注释里的 `value = …` 会被当成真写点、
    注释里的 `}` 会把块提前闭合。片段（已从 code 视图切出）重新解析失败时
    原样返回：整文件的解析失败已在 `check_source` 开头 fail-closed。
    """
    ok, _reason, views = _lex_analyse(src)
    return views["code"] if ok else src


def _alias_names(code, base):
    """`val|var x = <base>` 的传递闭包（别名链 alias-of-alias）。

    [wide4-09 尝试 2/4] 上一轮只认 `_downloads` 这一种拼写：`val flowAlias =
    downloads; flowAlias.value` 的快照、`val mut = _downloads; mut.value = …`
    的写点都不在判据的接收者集合里 —— 把状态搬到别名后面，全部门判绿。
    """
    names = [base]
    changed = True
    while changed:
        changed = False
        for n in list(names):
            pat = (r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*%s\b(?![\w.(])"
                   % re.escape(n))
            for m in re.finditer(pat, code):
                if m.group(1) not in names:
                    names.append(m.group(1))
                    changed = True
    return names


def _write_receivers(code):
    """可写的接收者集合：`_downloads` + 直接持有它的别名（`val mut = _downloads`）。"""
    return _alias_names(code, RECV)


def _alt(names):
    """正则用的接收者交替串（长的优先，避免前缀吃掉后缀）。"""
    return "|".join(re.escape(n) for n in sorted(names, key=len, reverse=True))


def _in_retry_loop(code, idx):
    """调用是否落在 `while` 重试环里（`compareAndSet` 唯一的原子用法）。

    两种合法形态：
      while (!_downloads.compareAndSet(_downloads.value, next)) { }
      while (true) { val prev = _downloads.value; if (compareAndSet(prev, next)) break }

    要求环体/条件里**重新读当前值**：只把 CAS 包在一个不重读的循环里仍然会丢更新。
    判不出来就按违规处理（fail-closed 的反面：宁可多报，不静默放过）。
    """
    lo = max(0, idx - 2000)
    seg = code[lo:idx + 1]
    for m in reversed(list(re.finditer(r"\bwhile\s*\(", seg))):
        po = lo + m.end() - 1
        pc = _match_paren(code, po)
        if pc < 0:
            continue
        if po <= idx <= pc:
            return True
        k = pc + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        span = _balanced(code, k)
        if span is None or not (span[0] <= idx < span[1]):
            continue
        body = code[span[0]:span[1]]
        if re.search(r"(?<![\w.])[A-Za-z_]\w*\s*\.\s*value\b", body):
            return True
    return False


def _consumes(rest, name):
    """CAS 参数是否被**返回值**消费（绑定量可传递：`val kept = current.filter{}; kept`）。

    [wide4-09 尝试 3] 上一轮判「body 里出现过这个标识符」，于是
        `_downloads.update { current -> val _ignore = current; emptyList() }`
    仍然算「用了参数」—— 拼写在、决定被删掉。现在只认**最后一条语句（返回值）**
    能追到参数（含经由 `val` 绑定的一跳传递）。
    """
    binds = {name}
    stmts = [s for s in _split_statements(rest) if s.strip()]
    if not stmts:
        return False
    for s in stmts[:-1]:
        m = re.match(r"\s*(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)(.*)$",
                     s, re.S)
        if m and any(re.search(r"\b%s\b" % re.escape(b), _live_text(m.group(2)))
                     for b in binds):
            binds.add(m.group(1))
    tail = _live_text(stmts[-1])
    return any(re.search(r"\b%s\b" % re.escape(b), tail) for b in binds)


def _match_paren(src, open_idx):
    """Index of the `)` matching the `(` at open_idx (-1 if none)."""
    mask = _mask(src)
    depth = 0
    for i in range(open_idx, len(mask)):
        if mask[i] == "(":
            depth += 1
        elif mask[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1

def _call_args(src, open_idx):
    """Text between the parens whose `(` sits at open_idx (None if unbalanced)."""
    end = _match_paren(src, open_idx)
    return None if end < 0 else src[open_idx + 1:end]

def _split_top(s, op):
    """Split at depth-0 occurrences of `op` (strings/comments masked)."""
    mask = _mask(s)
    parts, depth, start, i = [], 0, 0, 0
    while i < len(mask):
        c = mask[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and mask.startswith(op, i):
            parts.append(s[start:i])
            i += len(op)
            start = i
            continue
        i += 1
    parts.append(s[start:])
    return parts

def _unparen(p):
    p = p.strip()
    while p.startswith("(") and p.endswith(")") and _match_paren(p, 0) == len(p) - 1:
        p = p[1:-1].strip()
    return p

def _lit_value(part):
    """True/False when `part` is a bare boolean literal (optionally negated or
    parenthesised), None otherwise."""
    p = _unparen(part)
    neg = False
    while p.startswith("!"):
        neg = not neg
        p = _unparen(p[1:])
    if p == "true":
        v = True
    elif p == "false":
        v = False
    else:
        return None
    return (not v) if neg else v

def _truth(cond):
    """True/False when a condition is DECIDED by a literal, None otherwise.

    `if (true || sid == null)` is decided (always true): keeping the null test
    in the spelling while the outcome is fixed is exactly the wide3-08 shape.
    """
    c = _unparen(cond)
    if _lit_value(c) is not None:
        return _lit_value(c)
    for part in _split_top(c, "||"):
        if _lit_value(part) is True:
            return True
    for part in _split_top(c, "&&"):
        if _lit_value(part) is False:
            return False
    return None

def _split_else(rest):
    """`then` / `else` halves of an `if (...) A else B` expression."""
    mask = _mask(rest)
    depth = 0
    i = 0
    while i < len(mask):
        c = mask[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and re.match(r"\belse\b", mask[i:]):
            return rest[:i], rest[i + 4:]
        i += 1
    return rest, ""

def _live_text(expr):
    """The part of `expr` that can actually be evaluated at runtime.

    A reference that only lives in a dead half is not a use:
      `if (false) current else listOf(e) + stale` -> `current` is NOT consumed
      `false && current.map { ... }`              -> same
    This is the wide3-08 shape: keep the spelling of the use, delete the
    decision that reaches it.
    """
    e = expr.strip()
    m = re.match(r"if\s*\(", e)
    if m:
        mp = _match_paren(e, m.end() - 1)
        if mp < 0:
            return e
        cond = e[m.end():mp]
        then_part, else_part = _split_else(e[mp + 1:])
        tv = _truth(cond)
        if tv is True:
            return cond + " " + _live_text(then_part)
        if tv is False:
            return cond + " " + _live_text(else_part)
        return cond + " " + _live_text(then_part) + " " + _live_text(else_part)
    for op, dead in (("||", True), ("&&", False)):
        parts = _split_top(e, op)
        if len(parts) > 1:
            for i, part in enumerate(parts):
                if _lit_value(part) is dead:
                    # Kotlin short-circuits: everything AFTER this literal is
                    # never evaluated, so a reference there is not a use.
                    return " ".join(_live_text(p) for p in parts[:i + 1])
    return e

def _split_statements(body):
    """Top-level statements of a block body (`;` or depth-0 newline ends one)."""
    mask = _mask(body)
    parts, depth, start = [], 0, 0
    for i, c in enumerate(mask):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and (c == ";" or c == "\n"):
            parts.append(body[start:i])
            start = i + 1
    parts.append(body[start:])
    return parts

def _live_body(body):
    """`_live_text` over every top-level statement of a block body."""
    return "\n".join(_live_text(s) for s in _split_statements(body) if s.strip())

def _balanced(src, open_idx):
    """Return (body_start, body_end) of the `{ ... }` block opening at open_idx."""
    mask = _mask(src)
    depth = 0
    for i in range(open_idx, len(mask)):
        if mask[i] == "{":
            depth += 1
        elif mask[i] == "}":
            depth -= 1
            if depth == 0:
                return open_idx + 1, i
    return None

def _strip_nested(body):
    """Blank out nested `{ ... }` blocks (strings/comments too) so only the
    top-level statements remain. Length-preserving, so an offset found in the
    result is still valid in `body`."""
    mask = _mask(body)
    out = list(mask)
    i, n = 0, len(mask)
    while i < n:
        if mask[i] == "{":
            span = _balanced(body, i)
            if span is None:
                return None
            for k in range(i, span[1] + 1):
                if out[k] != "\n":
                    out[k] = " "
            i = span[1] + 1
            continue
        i += 1
    return "".join(out)

def _line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def _statement_tail(src, idx):
    """Text from idx to end of the statement (newline at depth 0 or `;`)."""
    depth = 0
    i = idx
    n = len(src)
    while i < n:
        ch = src[i]
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
            if depth < 0:
                break
        elif ch == ';' and depth == 0:
            break
        elif ch == '\n' and depth == 0:
            break
        i += 1
    return src[idx:i]


FUN_HEAD = re.compile(r"^\s{0,4}(?:private\s+|internal\s+|public\s+|protected\s+)?"
                      r"(?:suspend\s+)?fun\s", re.M)
SNAP_BIND = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*([^\n]*)")


def _snapshot_bindings(src, receivers=None):
    """`val x = <expr reading the download state>` bindings, with their
    enclosing-function span.

    The read set is derived (`_downloads` + every `asStateFlow()` alias), so
    `val snap = downloads.value` is the same stale read as
    `val snap = _downloads.value` (wide3-08 R3).

    Returns [(name, start, scope_end, bind_line)]. A write inside the span whose
    right-hand side mentions `name` is a read-modify-write dressed up as a plain
    assignment — the wide-1024 witness splits the `.filter {}` onto its own line and
    slaps the `ALLOWED-FULL-REPLACE` marker on the bare `= next` write.
    """
    if receivers is None:
        receivers = _read_receivers(src)
    out = []
    for m in SNAP_BIND.finditer(src):
        rhs = m.group(2)
        # [wide4-09] 读入口按派生集合判（含别名链），不再只查 `_downloads` 字面量
        if not _reads_state(rhs, receivers) and not any(
                re.search(r"(?<![\w.])%s\b" % re.escape(r), rhs) for r in receivers):
            continue
        nxt = FUN_HEAD.search(src, m.end())
        out.append((m.group(1), m.end(), nxt.start() if nxt else len(src),
                    _line_of(src, m.start())))
    return out


def _stale_ref(label, line_no, rhs, binds, offset):
    """R1b — the right-hand side re-uses a value snapshotted from `_downloads`."""
    for name, start, end, bind_line in binds:
        if start <= offset <= end and re.search(r"\b%s\b" % re.escape(name), rhs):
            return ("%s:%d: read-modify-write on _downloads through the snapshot `%s` "
                    "(taken from _downloads at L%d) — a marker cannot make a state-dependent "
                    "write safe; use update { } (wide-1024 B-22)"
                    % (label, line_no, name, bind_line))
    return None


# ── [R3-t04] 第三轮（dual-gate bypass）三个缝 ───────────────────────────────
# 三条见证的共同形态是「拼写还在，决定没了」——门判的是**语句存在**，不是
# **效果达成**，于是缝落在两条判据之间：
#
#   09-1 条目选择谓词：`_downloads.update` 的 lambda 里决定「哪一条被
#        transform」的那个条件谁都不判。`if (it.id == id)` 改成
#        `if (it.id == -1L)` 后 CAS 照样原子、CAS 参数照样被消费、update
#        站点数不变 —— 但没有任何条目能匹配，下载状态永不推进。
#   09-2 字节落盘：`startUrlDownload` 的 read 循环里 `out.write(buf, 0, n)`
#        被删，进度照样涨到 100%、条目照样 settle 成 COMPLETED（R1-R6 全绿），
#        磁盘上是 0 字节文件。
#   09-3 伴生横幅流：`_activeDownload` 的重定位谓词没判 —— 从 DOWNLOADING
#        换成 COMPLETED 后，横幅命中刚完成的条目而永久残留。
FUN_DECL_RE = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")
# 条目选择点：`if (…)` 之外还有集合侧的选择（filter / firstOrNull / find …）
SELECTOR_CALL = re.compile(
    r"(?<![\w.])(?:filter|filterNot|firstOrNull|first|find|singleOrNull|single|"
    r"any|none|indexOf)\s*(?=\{)")
READ_CALL = re.compile(r"(?<![\w.])([A-Za-z_]\w*)\s*\.\s*read\s*\(")
WRITE_CALL = re.compile(r"(?<![\w.])([A-Za-z_]\w*)\s*\.\s*write\s*\(")


def _func_body(code, name):
    try:
        b = _function_body(code, name)
    except Exception:  # noqa: BLE001 — 解析失败按找不到处理，调用方 fail-closed
        return None
    return (b[0], b[1]) if b else None


def _enclosing_fun(code, pos):
    """(name, body_start, body_end, paren_open) of the innermost fun containing pos."""
    best = None
    for m in FUN_DECL_RE.finditer(code):
        if m.start() > pos:
            break
        nb = _func_body(code, m.group(1))
        if nb and nb[0] <= pos < nb[1]:
            best = (m.group(1), nb[0], nb[1], m.end() - 1)
    return best


def _params_of(code, paren_open):
    """形参名列表（解析不出来返回 None → 调用方 fail-closed）。"""
    pc = _match_paren(code, paren_open)
    if pc < 0:
        return None
    inner = code[paren_open + 1:pc]
    out = []
    for seg in _split_top(inner, ","):
        seg = seg.strip()
        if not seg:
            continue
        name = seg.split(":")[0].strip()
        name = re.sub(r"^(?:vararg|crossinline|noinline|private|public|internal)\s+",
                      "", name).strip()
        if re.fullmatch(r"[A-Za-z_]\w*", name):
            out.append(name)
    return out


def _selector_conds(body):
    """块体内每个**条目选择点**的条件：(cond, start, end)。

    `if (…)` 与集合侧选择（filter/firstOrNull/find… 的 lambda 体）都算：决定
    「哪一条被更新」的是这两处，而不只是 map 的 lambda 本身。
    """
    out = []
    for cond, t_start, t_end, e_start, e_end in _if_blocks(body):
        out.append((cond, t_start, e_end if e_start is not None else t_end))
    for m in SELECTOR_CALL.finditer(body):
        span = _balanced(body, m.end())
        if span is None:
            continue
        out.append((body[span[0]:span[1]], span[0], span[1]))
    return out


def _key_idents(code, fn_body, params, callables):
    """调用方交进来的「键」：标量形参 + 由它们派生的局部别名（一跳）。"""
    keys = [p for p in params if p not in callables]
    if not keys:
        return []
    out = list(keys)
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)([^\n]*)",
                         fn_body):
        rhs = _mask(m.group(2))
        if any(re.search(r"(?<![\w.])%s(?![\w.])" % re.escape(k), rhs) for k in keys):
            if m.group(1) not in out:
                out.append(m.group(1))
    return out


def _selection_problem(label, line_no, code, body, body_abs):
    """R7 — 被 transform 的那一条必须由**调用方传入的键**选出来（09-1）。

    判据不看谓词的拼写，而是看它由什么决定：`it.id == id`（键来自形参）绿，
    `it.id == -1L`（纯字面量）红 —— 后者让「原子更新」变成「原子地什么都不做」。
    """
    fn = _enclosing_fun(code, body_abs)
    if fn is None:
        return None
    name, b_start, b_end, paren = fn
    params = _params_of(code, paren)
    if params is None:
        return ("%s:%d: cannot parse the parameter list of %s() — the entry-selection "
                "criterion refuses to judge, fail-closed (R3-t04)"
                % (label, line_no, name))
    callables = [p for p in params
                 if re.search(r"(?<![\w.])%s\s*\(" % re.escape(p), body)]
    if not callables:
        return None
    cm = None
    for p in callables:
        m = re.search(r"(?<![\w.])%s\s*\(" % re.escape(p), body)
        if m and (cm is None or m.start() < cm.start()):
            cm = m
    keys = _key_idents(code, code[b_start:b_end], params, callables)
    selectors = _selector_conds(body)

    def keyed(cond):
        # 常量折叠成真/假的条件不是「选择」，是把选择抹平（wide3-08 同族）
        if _const_bool(cond) is not None:
            return False
        live = _mask(cond)
        return any(re.search(r"(?<![\w.])%s(?![\w.])" % re.escape(k), live) for k in keys)

    if not selectors:
        return ("%s:%d: the download update applies %s(...) to EVERY entry — there is no "
                "entry-selection predicate at all, so one caller settles every download "
                "(R3-t04)" % (label, line_no, callables[0]))
    enclosing = [s for s in selectors if s[1] <= cm.start() < s[2]]
    if enclosing:
        cond = max(enclosing, key=lambda t: t[1])[0]
        if keyed(cond):
            return None
        return ("%s:%d: the entry that gets transformed is selected by `%s` — that predicate "
                "is not decided by any key the caller handed in (%s), so no entry can ever "
                "match and the download state never advances (R3-t04)"
                % (label, line_no, " ".join(_mask(cond).split())[:70],
                   ", ".join(keys) or "none"))
    before = [s for s in selectors if s[2] <= cm.start()]
    if any(keyed(s[0]) for s in before):
        return None
    return ("%s:%d: %s(...) is applied with no caller-keyed selection before it — the "
            "transform reaches entries the caller never named (R3-t04)"
            % (label, line_no, callables[0]))


def _stream_copy_problem(code, label):
    """R9 — 流式下载必须真的把读到的字节写到目标文件（09-2）。"""
    span = _func_body(code, "startUrlDownload")
    if span is None:
        return ["%s: cannot locate startUrlDownload() — the streamed download path moved, "
                "the byte-copy criterion cannot judge, fail-closed (R3-t04)" % label]
    body = code[span[0]:span[1]]
    loops = []
    for m in re.finditer(r"\bwhile\s*\(", body):
        pc = _match_paren(body, m.end() - 1)
        if pc < 0:
            continue
        k = pc + 1
        while k < len(body) and body[k] in " \t\r\n":
            k += 1
        if k >= len(body) or body[k] != "{":
            continue
        sp = _balanced(body, k)
        if sp is not None and READ_CALL.search(body[sp[0]:sp[1]]):
            loops.append(sp)
    if not loops:
        return ["%s:%d: the streamed download has no byte-copy loop (`read(...)`) — a "
                "download that never reads the body cannot produce the file it reports as "
                "COMPLETED, fail-closed (R3-t04)" % (label, _line_of(code, span[0]))]
    problems = []
    for s0, s1 in loops:
        chunk = body[s0:s1]
        rm = READ_CALL.search(chunk)
        args = _call_args(chunk, chunk.find("(", rm.start()))
        buf = None
        if args is not None:
            first = args.split(",")[0].strip()
            if re.fullmatch(r"[A-Za-z_]\w*", first):
                buf = first
        count = None
        for mm in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)",
                              chunk[:rm.start()]):
            count = mm.group(1)
        where = "%s:%d" % (label, _line_of(code, span[0] + s0))
        if buf is None or count is None:
            problems.append(
                "%s: the byte-copy loop does not expose a (buffer, count) pair — the "
                "write-back criterion cannot judge, fail-closed (R3-t04)" % where)
            continue
        wrote = None
        for w in WRITE_CALL.finditer(chunk):
            wargs = _call_args(chunk, chunk.find("(", w.start()))
            if wargs is None:
                continue
            if not (re.search(r"(?<![\w.])%s(?![\w.])" % re.escape(buf), wargs)
                    and re.search(r"(?<![\w.])%s(?![\w.])" % re.escape(count), wargs)):
                continue
            if not _path_feasible(body, s0 + w.start()):
                continue
            wrote = w.start()
            break
        if wrote is None:
            problems.append(
                "%s: the byte-copy loop reads into `%s` and counts `%s` but never writes "
                "them to the destination stream — progress reaches 100%% and the entry "
                "settles COMPLETED while the file on disk stays empty (R3-t04)"
                % (where, buf, count))
            continue
        prog = [p for p in (m.start() for m in
                            re.finditer(r"\bupdateDownloadProgress\s*\(", chunk))
                if _path_feasible(body, s0 + p)]
        if prog and min(prog) < wrote:
            problems.append(
                "%s: the progress update runs BEFORE the bytes are written — the reported "
                "percentage counts bytes that never reached the file (R3-t04)" % where)
    return problems


def _banner_selector_problem(code, label):
    """R10 — 顶部横幅的重定位谓词必须是**在飞**成员（09-3）。

    「在飞成员」从树里派生：`registerDownload` 给新条目的 `state =` 就是它。
    同一条下载状态机里，`settleDownload` 重算横幅时拿它做过滤 —— 换成任何
    终态成员都会让横幅命中刚完成的条目而永久残留。
    """
    em = re.search(r"enum\s+class\s+DownloadState\s*\{([^}]*)\}", code)
    if em is None:
        return []
    members = re.findall(r"[A-Za-z_]\w*", em.group(1))
    reg = _func_body(code, "registerDownload")
    if reg is None:
        return ["%s: DownloadState is declared but registerDownload() is gone — the "
                "in-flight member cannot be derived, fail-closed (R3-t04)" % label]
    st = re.search(r"\bstate\s*=\s*DownloadState\s*\.\s*([A-Za-z_]\w*)",
                   code[reg[0]:reg[1]])
    if st is None or st.group(1) not in members:
        return ["%s: registerDownload() no longer assigns the new entry's state — the "
                "in-flight member cannot be derived, fail-closed (R3-t04)" % label]
    inflight = st.group(1)
    settle = _func_body(code, "settleDownload")
    if settle is None:
        return ["%s: settleDownload() is gone — re-point the banner criterion, "
                "fail-closed (R3-t04)" % label]
    sbody = code[settle[0]:settle[1]]
    writes = list(re.finditer(r"\b_activeDownload\s*\.\s*value\s*=(?!=)", sbody))
    if not writes:
        return ["%s: settleDownload() no longer recomputes the download banner — the "
                "banner would never clear, fail-closed (R3-t04)" % label]
    problems = []
    for w in writes:
        rhs = sbody[w.end():_expr_end(sbody, w.end())]
        sel = re.search(r"\b(?:firstOrNull|first|find|singleOrNull|single)\s*\{([^}]*)\}", rhs)
        if sel is None:
            continue
        for ref in re.findall(r"DownloadState\s*\.\s*([A-Za-z_]\w*)", sel.group(1)):
            if ref != inflight:
                problems.append(
                    "%s:%d: the download banner is re-selected on DownloadState.%s, but the "
                    "in-flight member registerDownload() assigns is DownloadState.%s — a "
                    "settled entry then matches the banner selector and the progress banner "
                    "never clears (R3-t04)"
                    % (label, _line_of(code, settle[0] + w.start()), ref, inflight))
    return problems


# ── the rules ───────────────────────────────────────────────────────────────
def _judge_write(label, line_no, rhs, line, form, receivers):
    """R1/R2 for one write site; `form` names the syntactic shape for messages.

    The right-hand side is judged on masked text (`_mask`), so a comment that
    merely says "value" cannot fake a read-modify-write, and a string literal
    that spells `value` cannot hide one.
    """
    an = _mask(rhs)
    if form is not None and form.startswith("emit:"):
        api = form.split(":", 1)[1]
        if _reads_state(an, receivers):
            return ("%s:%d: `_downloads.%s(` receives a value derived from the current "
                    "state - on a MutableStateFlow that is a non-atomic read-modify-write "
                    "(the CAS loop is bypassed); use _downloads.update { ... } "
                    "(wide3-08 R2, ISSUE-03C)" % (label, line_no, api))
        if MARKER in line:
            return None
        return ("%s:%d: `_downloads.%s(...)` replaces the current value outside the CAS "
                "loop (equivalent to `_downloads.value = ...`); use update { ... } or opt "
                "out with an `%s` marker (wide3-08 R2)" % (label, line_no, api, MARKER))
    if form is None:
        if _reads_state(an, receivers) or RECV in an \
                or re.search(r"(?<![\w.])value\b", an):
            return "%s:%d: read-modify-write on _downloads via %s (lost update risk) — " \
                   "use _downloads.update { … } (ISSUE-03C)" % (label, line_no, "explicit write")
        if MARKER in line:
            return None
        return "%s:%d: _downloads.value = … write without an `%s` marker — " \
               "use update { } or opt out explicitly (ISSUE-03C)" % (label, line_no, MARKER)
    # implicit-receiver write: RHS mentioning the receiver's own `value` is RMW
    if _reads_state(an, receivers) or RECV in an \
            or re.search(r"(?<![\w.])value\b", an):
        return "%s:%d: read-modify-write on _downloads through `%s` (hidden receiver) — " \
               "use _downloads.update { … } (ISSUE-03C, wide-1024 B-22)" % (label, line_no, form)
    if MARKER in line:
        return None
    return "%s:%d: _downloads written through `%s` without an `%s` marker — the write " \
           "surface is the receiver, not the spelling (wide-1024 B-22)" % (label, line_no, form, MARKER)


def check_source(src: str, label: str) -> list:
    bad = []
    seen = set()
    # [wide4-09] 先过共享词法器：解析失败 = fail-closed（parser-death 轴），
    # 绝不退回原文。之后全部扫描走 code 视图（注释/字符串内容抹平、行号不变），
    # MARKER 仍从 raw 行取 —— 停用标记本来就该写在注释里。
    ok, reason, views = _lex_analyse(src)
    if not ok:
        return ["%s: cannot lex (%s) — the gate refuses to judge a broken lexer "
                "view, fail-closed (wide4-09 parser-death)" % (label, reason)]
    raw_lines = src.splitlines()
    src = views["code"]

    def raw_line(line_no):
        return raw_lines[line_no - 1] if 0 < line_no <= len(raw_lines) else ""

    # [wide4-09] 写入口集合从发布树派生：`_downloads` + 直接持有它的别名
    wrecv = _write_receivers(src)
    alt = _alt(wrecv)
    write_re = re.compile(r"\b(?:%s)\s*\.\s*value\s*=(?!=)" % alt)          # explicit
    emit_re = re.compile(r"\b(?:%s)\s*\.\s*(tryEmit|emit|getAndSet|compareAndSet)\s*\(" % alt)
    recv_block = re.compile(r"\b(?:%s)\s*\.\s*(apply|run|let|also)\s*\{" % alt)
    with_block = re.compile(r"\bwith\s*\(\s*(?:%s)\s*\)\s*\{" % alt)
    cas_lambda = re.compile(r"\b(?:%s)\s*\.\s*(?:update|updateAndGet|getAndUpdate)\s*\{" % alt)
    receivers = _read_receivers(src)
    binds = _snapshot_bindings(src, receivers)

    # R1 / R1b / R2 — explicit `<recv>.value = …`
    for m in write_re.finditer(src):
        line_no = _line_of(src, m.end())
        line = raw_line(line_no)
        rhs = _statement_tail(src, m.end())
        seen.add(line_no)
        v = _stale_ref(label, line_no, rhs, binds, m.end())
        if v:
            bad.append(v)
            continue
        v = _judge_write(label, line_no, rhs, line, None, receivers)
        if v:
            bad.append(v)

    # R5 [wide3-08 R2 + wide4-09 尝试 1] — the shared-flow entrances are writes too.
    # `MutableStateFlow` inherits `tryEmit` / `emit` from `MutableSharedFlow`;
    # on a StateFlow they replace the value outright (no CAS), so they are the
    # same non-atomic write as `_downloads.value = x` under another spelling.
    # `compareAndSet` 只在**重试环**里才是原子写（每次重试都重读当前值）；
    # 单发一次 `compareAndSet(读到的旧值, 新值)` 与 read-modify-write 同价。
    for m in emit_re.finditer(src):
        line_no = _line_of(src, m.start())
        if line_no in seen:
            continue
        seen.add(line_no)
        line = raw_line(line_no)
        if m.group(1) == "compareAndSet":
            if _in_retry_loop(src, m.start()):
                continue
            bad.append("%s:%d: single-shot `_downloads.compareAndSet(...)` without a retry "
                       "loop is the same non-atomic read-modify-write as `.value =` — use "
                       "`update { … }` (wide4-09 尝试 1)"
                       % (label, line_no))
            continue
        args = _call_args(src, m.end() - 1)
        if args is None:
            bad.append("%s:%d: unbalanced `_downloads.%s(` call - cannot decide, "
                       "fail-closed (wide3-08 R2 parser-death)"
                       % (label, line_no, m.group(1)))
            continue
        v = _judge_write(label, line_no, args, line, "emit:" + m.group(1), receivers)
        if v:
            bad.append(v)

    # R4 — implicit receiver: apply / run / with (bare `value =`), let / also (`it.value =`)
    for rx, kind in ((recv_block, "recv"), (with_block, "with")):
        for m in rx.finditer(src):
            brace = src.index("{", m.end() - 1)
            span = _balanced(src, brace)
            if span is None:
                bad.append("%s:%d: unbalanced `%s` block — cannot decide, fail-closed "
                           "(wide-1024 B-22 parser-death)" % (label, _line_of(src, m.start()), m.group(0).strip()))
                continue
            body = src[span[0]:span[1]]
            top = _strip_nested(body)
            if top is None:
                bad.append("%s:%d: unbalanced nested block inside `%s` — fail-closed "
                           "(wide-1024 B-22 parser-death)" % (label, _line_of(src, m.start()), m.group(0).strip()))
                continue
            form = m.group(1) if kind == "recv" else "with"
            rx_val = VALUE_WRITE_THIS if form in ("apply", "run", "with") else VALUE_WRITE_IT
            for w in rx_val.finditer(top):
                abs_idx = span[0] + w.start()
                line_no = _line_of(src, abs_idx)
                if line_no in seen:
                    continue
                line = raw_line(line_no)
                seen.add(line_no)
                rhs = _statement_tail(src, span[0] + w.end())
                v = _judge_write(label, line_no, rhs, line, form, receivers)
                if v:
                    bad.append(v)

    # R3 — the CAS lambda must consume the value the loop hands it.
    # "Consume" is judged on the LIVE half of the expression: keeping the
    # spelling `current` inside `if (false) current else stale` uses nothing
    # (wide3-08 R3).
    # [wide4-09 尝试 2/3] 消费判据升级：①只认**返回值（最后一条语句）**能追到
    # 参数（含 val 绑定一跳），`val _ignore = current; emptyList()` 判红；
    # ②无显式形参的 lambda 记作隐式 `it`，`update { listOf(entry) }`（丢了 it）
    # 判红。接收者集合用派生的别名闭包。
    snaps = [b[0] for b in binds]
    for m in cas_lambda.finditer(src):
        brace = src.index("{", m.end() - 1)
        span = _balanced(src, brace)
        if span is None:
            bad.append("%s:%d: unbalanced `_downloads.update {` block — cannot decide, "
                       "fail-closed (wide-1024 B-22 parser-death)" % (label, _line_of(src, m.start())))
            continue
        body = src[span[0]:span[1]]
        line_no = _line_of(src, m.start())
        top = _strip_nested(body)
        if top is None:
            bad.append("%s:%d: unbalanced nested block inside `_downloads.update {` — "
                       "fail-closed (wide-1024 B-22 parser-death)" % (label, line_no))
            continue
        live = _live_body(top)
        # reading the live state from inside the lambda re-enters the same snapshot
        if _reads_state(body, receivers) or re.search(r"_downloads\s*\.\s*value", body):
            bad.append("%s:%d: `_downloads.update {` lambda re-reads `_downloads.value` "
                       "(CAS result not consumed) — map the parameter instead "
                       "(wide-1024 B-22)" % (label, line_no))
            continue
        # a snapshot captured before the call and mapped inside defeats the CAS loop
        captured = [s for s in snaps if re.search(r"\b%s\b" % re.escape(s), live)]
        if captured:
            bad.append("%s:%d: `_downloads.update {` lambda maps the pre-captured snapshot %s "
                       "instead of the parameter — the CAS retry recomputes from stale state "
                       "(wide-1024 B-22)" % (label, line_no, ", ".join(sorted(set(captured)))))
            continue
        # 参数消费：显式形参或隐式 `it`，只认返回值（wide4-09 尝试 2/3）
        pm = re.match(r"\s*(?:\(([^)]*)\)|([A-Za-z_]\w*))\s*->", body)
        if pm:
            names = [n.strip() for n in (pm.group(1) or pm.group(2)).split(",") if n.strip()]
            rest = top[pm.end():]
        else:
            names = ["it"]
            rest = top
        rest = rest if rest is not None else ""
        if MARKER in body or not any(
                _consumes(rest, n) for n in names):
            shown = ", ".join(names)
            bad.append("%s:%d: `_downloads.update { %s -> … }` never uses %s in its return "
                       "expression (CAS result not consumed) — wide-1024 B-22 / wide4-09"
                       % (label, line_no, shown, "`%s`" % "`/`".join(names)))
            continue
        # [R3-t04 09-1] CAS 参数被消费只是「原子地算了一次」，还得看**选了谁**：
        # 选择谓词纯字面量 ⇒ 那条更新永远匹配不到条目。
        prob = _selection_problem(label, line_no, src, body, brace)
        if prob:
            bad.append(prob)

    # [R3-t04 09-2 / 09-3] 流式下载的字节落盘与横幅重定位。只对完整注册表
    # （BrowserTabPool 本体）判 —— 片段夹具没有这两处锚点；本体一旦缺锚点
    # 就是 fail-closed 红，不会静默跳过。
    if re.search(r"\bclass\s+BrowserTabPool\b", src):
        bad.extend(_stream_copy_problem(src, label))
        bad.extend(_banner_selector_problem(src, label))

    return bad


def run(repo: str) -> int:
    path = os.path.join(repo, REL)
    if not os.path.exists(path):
        print("FAIL %s: not found (run from the repo root)" % path)
        return 1
    src = open(path, encoding="utf-8", errors="replace").read()
    bad = check_source(src, REL)
    if bad:
        for b in bad:
            print("FAIL " + b)
        return 1
    # [wide4-09 尝试 1] 站点数下限：4 个真实写点必须都还是 `update { }`。
    # 上一轮 EMIT_RE 白名单漏了 `compareAndSet`，把某个 update 站点整个替换成
    # 单发 CAS 后，剩下的判据照样全绿（只丢了 1 个站点）。计数走 code 视图，
    # 注释里的 `_downloads.update { … }` 示例不再抬数。
    ok, reason, views = _lex_analyse(src)
    if not ok:
        print("FAIL %s: cannot lex (%s) — fail-closed (wide4-09 parser-death)" % (path, reason))
        return 1
    code = views["code"]
    n_update = len(re.findall(r"_downloads\.update\s*\{", code))
    if n_update < BASELINE_UPDATE_SITES:
        print("FAIL %s: only %d `_downloads.update {` site(s), baseline is %d — an atomic "
              "write entrance was replaced by a spelling the whitelist did not cover "
              "(wide4-09 尝试 1)" % (REL, n_update, BASELINE_UPDATE_SITES))
        return 1
    print("PASS downloads_atomic (%d _downloads.update{ } site(s), 0 non-atomic writes, "
          "0 stale-snapshot update lambdas)" % n_update)
    return 0


GOOD = '''    private fun registerDownload(dest: File): Long {
        _downloads.update { listOf(entry) + it }
        return id
    }

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    fun clearFinishedDownloads() {
        _downloads.value = emptyList() // ALLOWED-FULL-REPLACE: no current-state read
    }
'''

BAD_PREPEND = '''    private fun registerDownload(dest: File): Long {
        _downloads.value = listOf(entry) + _downloads.value
        return id
    }
'''

BAD_MAP = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.value = _downloads.value.map { if (it.id == id) transform(it) else it }
    }
'''

BAD_FILTER = '''    fun clearFinishedDownloads() {
        _downloads.value = _downloads.value.filter { it.state == DownloadState.DOWNLOADING }
    }
'''

# witness 1 (wide-1024 B-22, axis=spelling): hidden receiver
WIT_APPLY = '''    private fun registerDownload(dest: File): Long {
        _downloads.apply { value = listOf(entry) + value }
        return id
    }
'''

WIT_WITH = '''    fun clearFinishedDownloads() {
        with(_downloads) { value = value.filter { it.state == DownloadState.DOWNLOADING } }
    }
'''

# witness 2 (wide-1024 B-22, axis=dataflow): atomic API called, stale snapshot mapped
WIT_STALE = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        val snap = _downloads.value
        _downloads.update { snap.map { if (it.id == id) transform(it) else it } }
    }
'''

# witness 2b: explicit parameter never consumed
WIT_PARAM = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> cachedEntries.map { if (it.id == id) transform(it) else it } }
    }
'''

CLEAN_UPDATE = '''    private fun markSeen() {
        _downloads.update { current -> current.map { it.copy(seen = true) } }
    }
'''

# witness 3 (wide-1024 B-22, axis=dataflow): split the read onto its own line and put
# the opt-out marker on the bare assignment
WIT_SPLIT = '''    fun clearFinishedDownloads() {
        val next = _downloads.value.filter { it.state == DownloadState.DOWNLOADING }
        _downloads.value = next // ALLOWED-FULL-REPLACE
    }
'''


# ── [wide3-08] witnesses re-attacked from the external audit ────────────────
# R3 (axis=dataflow): the snapshot is taken through the PUBLIC alias
# (`downloads.value`, line 541 of the real file) and the CAS parameter is
# "used" only inside the dead half of the lambda.
W3_ALIAS_SNAP = '''    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private fun registerDownload(dest: File): Long {
        val staleSnap = downloads.value
        _downloads.update { current -> if (false) current else listOf(entry) + staleSnap }
        return id
    }
'''

# R3b (axis=dataflow): the parameter only appears in the dead half - the
# spelling of the use survives, the decision that reaches it does not.
W3_DEAD_PARAM = '''    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> if (false) current else cachedEntries.map { transform(it) } }
    }
'''

# R4 (axis=parser-death): a `/* } */` block comment closes the
# `_downloads.apply { ... }` span early, so the real read-modify-write drops
# out of the judged block.
W3_BLOCK_COMMENT = '''    fun clearFinishedDownloads() {
        _downloads.apply { /* } */ value = value.filter { it.state == DownloadState.DOWNLOADING } }
    }
'''

# R2 (axis=spelling): the MutableSharedFlow entrance instead of `.value =`,
# fed from the current state.
W3_TRY_EMIT = '''    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.tryEmit(downloads.value.map { if (it.id == id) transform(it) else it })
    }
'''

# R2b (axis=spelling, positive control): a tryEmit that does NOT derive from
# the current state is a conscious full replace -> marker required, and with
# the marker it is legal (so the arm is not simply "tryEmit is banned").
W3_TRY_EMIT_CLEAN = '''    fun resetDownloads() {
        _downloads.tryEmit(emptyList()) // ALLOWED-FULL-REPLACE: no current-state read
    }
'''

# ── [wide4-09] 本轮外部见证的四条新盲轴（family-hole ×3 + parser-death）──────
# 尝试 1：单发 `compareAndSet` —— R5 的名字白名单里没有它，于是「把 update 换掉」
# 这种最直白的改写照样门绿（站点数还少一个，也没人看）。
W4_CAS_SINGLE = '''    private fun clearFinishedDownloads() {
        _downloads.compareAndSet(downloads.value, downloads.value.filter { it.state == DownloadState.DOWNLOADING })
    }
'''

# 尝试 1b（精度对照）：真正的 CAS 重试环 —— 每次重试都重读当前值，是原子写。
W4_CAS_LOOP = '''    private fun pushEntry(entry: DownloadEntry) {
        while (true) {
            val prev = _downloads.value
            if (_downloads.compareAndSet(prev, listOf(entry) + prev)) break
        }
    }
'''

# 尝试 2：别名的别名 —— `val flowAlias = downloads`（只读视图再起别名）取出快照
# 交给 update。READ_ALIAS_BIND 只认一跳，这层别名不在接收者集合里。
W4_ALIAS2 = '''    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private fun registerDownload(dest: File): Long {
        val flowAlias = downloads
        val stale = flowAlias.value
        _downloads.update { listOf(entry) + stale }
        return id
    }
'''

# 尝试 3：CAS 参数只出现在一条被丢弃的绑定里 —— `_live_body` 判「任意语句提到」，
# 于是「拼写保留、决定删掉」的经典形态全绿。
W4_DEAD_BIND = '''    private fun markSeen() {
        _downloads.update { current -> val _ignore = current; emptyList() }
    }
'''

# 尝试 4：写点搬到别名后面 —— WRITE_RE / RECV_BLOCK / _read_receivers 写死
# `_downloads`，`val mut = _downloads; mut.value = …` 一个判据都碰不到。
W4_WRITE_ALIAS = '''    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    fun clearFinishedDownloads() {
        val mut = _downloads
        mut.value = downloads.value.filter { it.state == DownloadState.DOWNLOADING }
    }
'''

# 尝试 5：嵌套块注释里的 `}` —— Kotlin 把 `/* outer /* inner */ } */` 当一个完整
# 注释；手写 masker 停在第一个 `*/`，那个 `}` 就变成真括号，apply 块提前闭合，
# 真正的 non-atomic 写点落到被判区间之外。
W4_NESTED_COMMENT = '''    fun clearFinishedDownloads() {
        _downloads.apply {
            /* outer /* inner */ } */ value = value.filter { it.state == DownloadState.DOWNLOADING }
        }
    }
'''


# ── [R3-t04] 第三轮（dual-gate）三个缝的见证臂 ──────────────────────────────
# 完整注册表基座：新判据的锚点（enum / registerDownload / settleDownload /
# startUrlDownload）都必须在，`class BrowserTabPool` 才会启用 09-2 / 09-3。
FULL_BASE = '''class BrowserTabPool {
    enum class DownloadState { DOWNLOADING, COMPLETED, FAILED }
    data class DownloadEntry(val id: Long, val filename: String, val state: DownloadState, val bytesDone: Long, val totalBytes: Long)
    data class DownloadUiState(val filename: String, val progress: Float)

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())

    private fun registerDownload(dest: File, totalBytes: Long): Long {
        val entry = DownloadEntry(id = 1L, filename = dest.name, state = DownloadState.DOWNLOADING, bytesDone = 0L, totalBytes = totalBytes)
        _downloads.update { listOf(entry) + it }
        _activeDownload.value = DownloadUiState(dest.name, 0f)
        return 1L
    }

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    private fun settleDownload(id: Long, state: DownloadState) {
        updateDownloadEntry(id) { it.copy(state = state) }
        _activeDownload.value = _downloads.value
            .firstOrNull { it.state == DownloadState.DOWNLOADING }
            ?.let { DownloadUiState(it.filename, 1f) }
    }

    internal fun startUrlDownload(url: String) {
        val dest = File("download.bin")
        val id = registerDownload(dest, 1L)
        downloadScope.launch {
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        updateDownloadProgress(id, dest.name, copied, 1L)
                    }
                }
            }
            finishDownload(id, dest)
        }
    }
}
'''

# 09-1：选择谓词与键无关 —— 原子更新变成「原子地什么都不做」
W5_ID_LITERAL = FULL_BASE.replace("if (it.id == id)", "if (it.id == -1L)")

# 09-2：字节不落盘 —— 进度到 100%、条目 COMPLETED、文件 0 字节
W6_NO_WRITE = FULL_BASE.replace("                        out.write(buf, 0, n)\n", "")

# 09-2b（精度对照）：进度在读之后、写之前结算 —— 撒谎的百分比
W6_PROGRESS_EARLY = FULL_BASE.replace(
    "                        out.write(buf, 0, n)\n                        copied += n\n"
    "                        updateDownloadProgress(id, dest.name, copied, 1L)",
    "                        copied += n\n"
    "                        updateDownloadProgress(id, dest.name, copied, 1L)\n"
    "                        out.write(buf, 0, n)")

# 09-3：横幅重定位谓词换了成员 —— 横幅永久残留
W7_BANNER = FULL_BASE.replace(
    ".firstOrNull { it.state == DownloadState.DOWNLOADING }",
    ".firstOrNull { it.state == DownloadState.COMPLETED }")

# 09-3b（精度对照）：「在飞成员」是从 registerDownload 派生的，不是写死的
# DOWNLOADING —— 整族改名（枚举成员 + 赋值 + 横幅谓词）必须仍然绿。
W7_RENAME_INFLIGHT = (FULL_BASE
                      .replace("DownloadState { DOWNLOADING,", "DownloadState { PENDING,")
                      .replace("DownloadState.DOWNLOADING", "DownloadState.PENDING"))


def selftest() -> int:
    failures = []
    good = check_source(GOOD, "good.kt")
    cases = {
        "bad-prepend": (check_source(BAD_PREPEND, "bad-prepend.kt"), "read-modify-write"),
        "bad-map": (check_source(BAD_MAP, "bad-map.kt"), "read-modify-write"),
        "bad-filter": (check_source(BAD_FILTER, "bad-filter.kt"), "read-modify-write"),
        "wit-apply": (check_source(WIT_APPLY, "wit-apply.kt"), "hidden receiver"),
        "wit-with": (check_source(WIT_WITH, "wit-with.kt"), "hidden receiver"),
        "wit-stale": (check_source(WIT_STALE, "wit-stale.kt"), "pre-captured snapshot"),
        "wit-param": (check_source(WIT_PARAM, "wit-param.kt"), "never uses"),
        "wit-split": (check_source(WIT_SPLIT, "wit-split.kt"), "snapshot `next`"),
        "clean-update": (check_source(CLEAN_UPDATE, "clean-update.kt"), None),
        "w3-alias-snap": (check_source(W3_ALIAS_SNAP, "w3-alias-snap.kt"),
                          "pre-captured snapshot"),
        "w3-dead-param": (check_source(W3_DEAD_PARAM, "w3-dead-param.kt"),
                          "never uses"),
        "w3-block-comment": (check_source(W3_BLOCK_COMMENT, "w3-block-comment.kt"),
                             "hidden receiver"),
        "w3-try-emit": (check_source(W3_TRY_EMIT, "w3-try-emit.kt"),
                        "non-atomic read-modify-write"),
        "w3-try-emit-clean": (check_source(W3_TRY_EMIT_CLEAN, "w3-try-emit-clean.kt"), None),
        # [wide4-09]
        "w4-cas-single": (check_source(W4_CAS_SINGLE, "w4-cas-single.kt"),
                          "single-shot `_downloads.compareAndSet"),
        "w4-cas-loop": (check_source(W4_CAS_LOOP, "w4-cas-loop.kt"), None),
        "w4-alias2": (check_source(W4_ALIAS2, "w4-alias2.kt"), "pre-captured snapshot"),
        "w4-dead-bind": (check_source(W4_DEAD_BIND, "w4-dead-bind.kt"), "never uses"),
        "w4-write-alias": (check_source(W4_WRITE_ALIAS, "w4-write-alias.kt"),
                           "read-modify-write on _downloads"),
        "w4-nested-comment": (check_source(W4_NESTED_COMMENT, "w4-nested-comment.kt"),
                              "hidden receiver"),
        # [R3-t04] 第三轮双门缝（09-1 / 09-2 / 09-3）
        "r3-full-base": (check_source(FULL_BASE, "r3-full-base.kt"), None),
        "r3-id-literal": (check_source(W5_ID_LITERAL, "r3-id-literal.kt"),
                          "selected by"),
        "r3-no-write": (check_source(W6_NO_WRITE, "r3-no-write.kt"), "never writes"),
        "r3-progress-early": (check_source(W6_PROGRESS_EARLY, "r3-progress-early.kt"),
                              "BEFORE the bytes"),
        "r3-banner": (check_source(W7_BANNER, "r3-banner.kt"), "never clears"),
        "r3-rename-inflight": (check_source(W7_RENAME_INFLIGHT, "r3-rename.kt"), None),
    }
    print("selftest good fixture -> %d violation(s)" % len(good))
    for name, (hits, needle) in cases.items():
        print("selftest %-12s -> %d%s" % (name, len(hits), "" if needle is None else " (%s)" % needle))
    if good:
        failures.append("compliant fixture must be clean, got %s" % good)
    for name, (hits, needle) in cases.items():
        if needle is None:
            if hits:
                failures.append("%s fixture must stay clean, got %s" % (name, hits))
        elif not any(needle in h for h in hits):
            failures.append("%s fixture did not trip `%s` (got %s)" % (name, needle, hits))
    # R2 teeth: an unmarked full replace must red.
    unmarked = check_source("    _downloads.value = emptyList()\n", "x.kt")
    if not unmarked:
        failures.append("unmarked full-replace write not detected")
    # parser-death teeth: an unbalanced update block must red, not silently pass.
    broken = check_source("    _downloads.update { current -> current.map { it }\n", "y.kt")
    if not any("fail-closed" in h for h in broken):
        failures.append("unbalanced update block not reported (parser-death)")
    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    # The trailing count is a *frozen contract token*: scripts/scan/test_scan.py asserts
    # this exact string, and test_scan.py is a frozen surface for this sweep. Keep the
    # historical count; the real arm list is printed above, one line per arm.
    print("SELFTEST=PASS (downloads_atomic_update: 4 fixtures)")
    # [R3-t04] 第三轮缝臂的独立标记（test_scan.py 的冻结 token 保持上面那行原样）。
    print("DOWNLOAD_UPDATE_SEAM_SELFTEST=PASS (3 seams: 09-1 caller-keyed entry "
          "selection / 09-2 byte-copy write-back + progress order / 09-3 in-flight "
          "banner selector; +3 witness arms, +1 precision arm)")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    repo = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(repo)


if __name__ == "__main__":
    sys.exit(main())
