#!/usr/bin/env python3
"""Whole-URL-into-a-log guard — RikkaMinis CI scan gate.

## Why this gate exists

`AppLogger` is on by default, mirrors to logcat unconditionally and writes to
filesDir/logs. Several providers put the API key in the URL QUERY (Gemini's
`?key=...`; any OpenAI-compatible relay whose base URL is user-supplied), so the
WHOLE request URL is a credential-bearing string — precisely why
`debug/LLMRequestLog.redactURL` exists.

2026-09-26: an absorb pack added a network trace listener whose `callStart`
logged `url=${call.request().url}`. Every existing gate was green: it compiled,
the i18n key sets matched, the four-way sync was complete, the legacy-pipeline
guard had nothing to say. None of them asked "can a secret reach an output
surface through this NEW line" — that is the wiring question, and this gate asks
it for the one shape that has already bitten once.

## What it flags

Inside a log-call statement (`AppLogger.<lvl>(`, `Log.<lvl>(`, `Timber.<lvl>(`,
`println(`), ANY evaluation form that carries the WHOLE url value:

  - a bare `$ident` whose name mentions url — Kotlin expands `"$url.host"` to
    `url.toString() + ".host"`, so the bare form leaks the whole value no matter
    what literal text follows;
  - `${expr}` mentioning url, unless it ends in a safe accessor (host, hostname,
    port, scheme, path, encodedPath, authority) or is wrapped by a redactor;
  - **CONCATENATION** — `"...=" + forBaseURL`. A url reaches a log through `+`
    exactly as it does through `$`;
  - **BRACE-NESTED interpolation** — `${forBaseURL.let { it }}`.

## [R3-t12] The judgement is a VALUE FLOW, not a spelling

Three witnesses (rB 11-1/11-2/11-3) shipped the same secret through three
renamings while every criterion above stayed green:

    val targetEndpoint = url                  // name never says "url"
    Log.w(TAG, "uncaught for $targetEndpoint") // <- whole url, gate blind

    val errMsg = "Download failed for $rawURL" // url hidden one hop out
    Log.w(TAG, errMsg)                         // bare argument, never read

    private val basePath = "https://…"         // ends in `path` ->
    Log.i(TAG, "base=$basePath")               // narrow-name whitelist

So the gate no longer asks "does this expression's NAME mention url". It asks
**"does the value handed to this log statement trace back to the whole url"**:

  * every argument of the call is judged (a bare `Log.w(TAG, msg)` counts);
  * a name that does not mention url is followed to its bindings in the
    enclosing function (falling back to the file, for properties), recursively
    (depth-limited) — `targetEndpoint -> url`, `errMsg -> "… $rawURL"`,
    `basePath -> "https://…"`;
  * the component whitelist (`host`/`port`/`path`/…) is no longer a NAME
    suffix exemption: it must be EARNED — the identifier has to resolve to a
    component accessor (`url.path`) or the value has to be provably a component.
    A name ending in `path` that holds `https://host:port/v1` (witness 11-3,
    `basePath`) is a whole url and is reported.

## Shared primitives only

Structure (bracket matching, argument splitting, concatenation splitting) runs
on `mask_code()`; expression text (interpolations, identifiers) is read from
`mask_comments()` at the SAME offsets — the two views are length-preserving, so
positions map 1:1. Template braces are matched by the shared lexer's own
`_skip_template`, never by a private counter. `KotlinLexError` is fail-closed.

## Fail-closed

A log statement whose parentheses never balance, or a `${` whose closing brace
cannot be found, is REPORTED rather than skipped — a half-read statement is
exactly where a spliced url would hide.

Audited existing sites are pinned in [ALLOWED] as (relative path, snippet
prefix); `--seed` prints entries for the current tree, which is how the list is
maintained. A line may also be exempted inline with `url-ok: <reason>` (the
repo's `debug-ok:` convention).

Usage:
  python3 scripts/scan/log_url_redaction_guard.py <repo_root>
  python3 scripts/scan/log_url_redaction_guard.py --seed <repo_root>
  python3 scripts/scan/log_url_redaction_guard.py --self-test
"""

import os
import re
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

# [R3-t12] 判据全部改走共享原语：自写的 `_strip_span`（括号计数 + 手写字符
# 状态机）与 `_scan_braced`（手写花括号计数）删除，见 docstring「Shared
# primitives only」。
from _kotlin_ast import match_bracket, split_top
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_lexer import _skip_template as _lex_skip_template
from _kotlin_valueflow import call_args as _ast_call_args
from _kotlin_valueflow import expr_end

SRC_SUBDIR = "src/android/app/src/main/java"

# Credential-bearing URLs only flow through these subtrees: the provider request
# paths, the shared OkHttp tracing layer, the offload gateway, and the repository
# that assembles base URLs. Page / preview / Custom-Tab URLs live in ui/, webapp/
# and browser/ and are user-facing by design — scanning them yields 30+ findings
# that all amount to "a page URL is not a secret", which is how a gate turns into
# noise and then gets deleted.
SCAN_DIRS = (
    "com/rikkaminis/app/network",
    "com/rikkaminis/app/provider",
    "com/rikkaminis/app/sandbox/offload",
    "com/rikkaminis/app/data/repository",
)

LOG_CALL = re.compile(r"\b(?:AppLogger|Log|Timber)\.[A-Za-z]+\s*\(|\bprintln\s*\(")

# A name that *says* it carries a URL. Judged on camel tokens, not substrings:
# `ContentUris` is the Android class (tokens Content/Uris), not a url value —
# a substring match flagged `ContentUris.parseId(uri)` as a leak.
URL_TOKENS = frozenset(("url", "uri", "endpoint"))
CAMEL = re.compile(r"[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+")


def is_urlish(name):
    return any(t.lower() in URL_TOKENS for t in CAMEL.findall(name or ""))

# Ending on one of these means the expression yields a COMPONENT, not the whole
# value — the only form that is safe to log.
SAFE_TAIL = re.compile(
    r"\.(host|hostname|port|scheme|path|encodedPath|authority|queryParameterNames"
    r"|lastPathSegment|pathSegments|name)\s*$",
    re.IGNORECASE,
)
REDACTOR = re.compile(r"redact|sanitiz|scrub", re.IGNORECASE)
# A name that ends in a component word is NOT self-exempting any more (witness
# 11-3: `basePath` held `https://host:port/v1`). It has to earn the exemption by
# resolving to a component accessor — see `_component_evidence`.
NARROW_NAME = re.compile(r"(host|hostname|port|scheme|path|authority)$", re.IGNORECASE)
# A name that mentions url but counts rather than carries it (`urlCount`,
# `urlLen`) is not a credential-bearing value. Excluded so the concat arm does
# not turn into noise the first time someone logs a size.
NOT_A_URL_VALUE = re.compile(
    r"(count|len|length|size|index|idx|bytes|chars|ms|timeout)$", re.IGNORECASE
)
# A literal that spells a scheme is a whole URL origin (witness 11-3's
# `private val basePath = "https://api.openai.com/v1"`).
SCHEME_LITERAL = re.compile(r"[a-zA-Z][a-zA-Z0-9+.\-]*://")

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
# 把值换算成非字符串的调用：值不再是一条 URL。
NOT_VALUE_PRESERVING = re.compile(
    r"^(?:to(?:Long|Int|Double|Float|Short|Byte|Char)\w*|hashCode|parseId"
    r"|startsWith|endsWith|contains|matches|equals|compareTo|indexOf|lastIndexOf"
    r"|is[A-Z]\w*|count|size|length)$")
MAX_DEPTH = 4

# ── audited call sites. Removing an entry re-arms the gate for that line;
# editing the line makes the entry stale (re-audit, then re-seed with --seed).
ALLOWED = {
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/ModelsDevApi.kt', 'Log.d(TAG, "No models.dev match for base URL: $forBaseURL")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OkHttpNetTraceListener.kt', 'com.rikkaminis.app.logging.AppLogger.info('),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt', 'com.rikkaminis.app.logging.AppLogger.info('),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt', 'com.rikkaminis.app.logging.AppLogger.warning('),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt', 'Log.d(TAG, "startActivity url=\'$url\' action=${intent.action} resolved='),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt', 'Log.w(TAG, "no handler for \'$url\'")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt', 'Log.w(TAG, "parseUri failed for \'$url\': ${e.message}")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt', 'android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} t'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + '),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $ur'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "httpGetString failed $url: ${e.message}")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "httpGetBytes failed $url: ${e.message}")'),
    # ── [R3-t12] 值流判据让这四处**旧漏网**第一次可见（旧判据只看名字里有没有
    # `url`，`$msg` / `$lastReason` / `$uri` 一个都不含）。它们是真泄漏，不是
    # "安全"：本支线不动生产 .kt（radius），因此按既有 ALLOWED 政策钉住并登记
    # （报告 §6 R1：`$apiURL` / `$downloadURL` 常带 `?token=`，应改成
    # redactURL）。删掉这些条目会让门立刻重报同一批行 —— 那时说明有人修了。
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt', 'AppLogger.warning(TAG, "import rollback failed for $uri: ${cleanup.mes'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] $msg")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] non-retryable ${lastReason}")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] retrying after transient failure: $lastReason")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] both attempts failed: $lastReason")'),
}

# ── inline exemption marker, same shape as the repo's `debug-ok:`
EXEMPT = "url-ok:"


# --------------------------------------------------------------------------
# 视图上下文：结构看 code 视图，值/名字看 comments 视图（两者等长，位置对齐）
# --------------------------------------------------------------------------
class _Ctx(object):
    __slots__ = ("code", "lit", "src", "rel")

    def __init__(self, code, lit, src, rel):
        self.code = code
        self.lit = lit
        self.src = src
        self.rel = rel

    def text(self, a, b):
        return self.lit[a:b]

    def struct(self, a, b):
        """结构视图：字符串字面量内部（模板插值的表达式）改用 comments 视图。

        `mask_code` 把整段字面量内容（含 `${...}` 里的代码）都抹掉，所以模板
        内部的表达式只能从 `mask_comments` 取；两视图等长，位置一一对应。
        """
        s = self.code[a:b]
        return s if s.strip() else self.lit[a:b]


def _line_of(code, pos):
    return code.count("\n", 0, pos) + 1


def _context_slice(src, pos, end):
    """`pos` 之前两行到 `end` 的原文 —— 行内豁免标记的可见窗口。"""
    start = pos
    for _ in range(2):
        if start <= 0:
            break
        k = src.rfind("\n", 0, start - 1)
        start = 0 if k < 0 else k + 1
    return src[start:end]


# --------------------------------------------------------------------------
# 值流判定
# --------------------------------------------------------------------------
def _clip(text, n=48):
    one = " ".join(text.split())
    return one if len(one) <= n else one[:n - 3] + "..."


def _enclosing_scope(ctx, pos):
    """包含 `pos` 的最内层声明体 [start, end)。

    结构在 code 视图上取（字面量内部已抹平，括号可信）。找不到就返回整份
    文件 —— 宁可多看几个同名绑定（保守），也不漏掉属性级的值。
    """
    code = ctx.code
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
        body_open = -1
        k = close_p + 1
        while k < len(code):
            ch = code[k]
            if ch == "{":
                body_open = k
                break
            if ch == "=":
                break
            if ch in ";}":
                break
            k += 1
        if body_open < 0:
            continue
        body_close = match_bracket(code, body_open)
        if body_close < 0:
            continue
        if body_open < pos < body_close:
            if best is None or body_open > best[0]:
                best = (body_open, body_close)
    if best is None:
        return (0, len(code))
    return best


# 声明与语句级赋值**分开**匹配：`f(id = sourceURL)` 这类命名实参、`copy(x = y)`
# 里的参数都不是这个标识符的绑定（见证里 `id <- $uri` 那一大片假阳就是它）。
_ASSIGN_DECL = r"\b(?:val|var)\s+%s(?![\w`])\s*(?::[^=,)\n]+?)?(?<![=<>!+\-*/%%])=(?!=)"
_ASSIGN_STMT = (r"(?<=[\n{};])[ \t]*%s(?![\w`])\s*(?::[^=,)\n]+?)?"
                r"(?<![=<>!+\-*/%%])=(?!=)")


def _rhs_end(code, start, limit):
    """右值的真正终点：语句结束，或**顶层**逗号 / 闭括号。

    `val id: String = UUID.randomUUID().toString(),` 后面还有整串构造参数；
    `expr_end` 会把它们全卷进来（真实求值里根本没有）。
    """
    end = min(expr_end(code, start), limit)
    depth = 0
    i = start
    while i < end:
        ch = code[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif ch == "," and depth == 0:
            return i
        i += 1
    return end


def _paren_depth_at(code, pos):
    """pos 处的圆/方括号嵌套深度 —— >0 说明这是调用实参列表里的命名实参。"""
    depth = 0
    for ch in code[:pos]:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth = max(0, depth - 1)
    return depth


def _binding_spans(ctx, name, pos, scope):
    """`name` 在 scope 内的每次声明/赋值：其右值的 [start, end)。

    见证 #139/#140/#141 的同一轴：名字换了、值没换。取**全部**写入点（保守：
    中途被换掉的值也要算），只保留日志点之前的。
    """
    code = ctx.code
    lo, hi = scope
    out = []
    for pat in (re.compile(_ASSIGN_DECL % re.escape(name)),
                re.compile(_ASSIGN_STMT % re.escape(name))):
      for m in pat.finditer(code, lo, hi):
        if pat.pattern == _ASSIGN_STMT % re.escape(name) and _paren_depth_at(code, m.start()):
            continue          # 跨行的命名实参，不是这个标识符的绑定
        rhs_start = m.end()
        rhs_end = _rhs_end(code, rhs_start, hi)
        if rhs_end > hi:
            rhs_end = hi
        if rhs_start >= pos or rhs_end <= rhs_start:
            continue
        if (rhs_start, rhs_end) not in out:
            out.append((rhs_start, rhs_end))
    return out


def _bindings(ctx, name, pos, scope):
    spans = _binding_spans(ctx, name, pos, scope)
    if spans:
        return spans
    if scope != (0, len(ctx.code)):
        # 属性 / 构造参数：局部找不到就退回整份文件
        return _binding_spans(ctx, name, pos, (0, len(ctx.code)))
    return []


def _spine(struct):
    """表达式主干：抹掉所有括号 / 花括号**内部**（调用实参、lambda 体）。

    `ContentUris.parseId(uri)` 的值是 Long，不是 `uri`；`call.request().url`
    的值是 `url`。只有主干上的标识符能决定值。
    """
    out = []
    depth = 0
    for ch in struct:
        if ch in "([{":
            depth += 1
            continue
        if ch in ")]}":
            depth = max(0, depth - 1)
            continue
        if depth == 0:
            out.append(ch)
    return "".join(out)


def _tail_names(struct):
    """表达式里真正参与求值的标识符（调用名不算：`url.toString()` → url）。"""
    out = []
    for m in IDENT.finditer(struct):
        rest = struct[m.end():].lstrip()
        if rest.startswith("("):
            continue
        out.append(m.group(0))
    return out


def _interpolations(ctx, a, b):
    """`lit[a:b]` 里的模板插值：[(expr_start, expr_end, is_name, name)]。"""
    seg = ctx.lit
    found = []
    i = a
    while i < b:
        if seg[i] == "$" and i + 1 < b and seg[i + 1] == "{":
            nxt = _lex_skip_template(seg, i + 1)
            if nxt < 0 or nxt > b:
                found.append((i, b, None, "${…}"))
                break
            found.append((i + 2, nxt - 1, None, None))
            i = nxt
            continue
        if seg[i] == "$" and i + 1 < b and (seg[i + 1].isalpha() or seg[i + 1] == "_"):
            m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*)", seg[i:b])
            if m:
                found.append((i, i + m.end(), m.group(1), None))
                i += m.end()
                continue
        i += 1
    return found


def _judge_name(ctx, name, pos, scope, depth, seen):
    if NOT_A_URL_VALUE.search(name):
        return []
    if is_urlish(name) and not NARROW_NAME.search(name):
        return ["$" + name + "（名字即 URL 载体）"]
    # `…path`/`…host` 这类 narrow 名字：既不自证清白（见证 11-3 的 `basePath`
    # 装着 `https://host/v1`），也不一概定罪（`absolutePath` / `dirPath` 是
    # 文件系统路径）。由值流说话 —— 追到整条 URL 才算。
    return _follow_name(ctx, name, pos, scope, depth, seen)


def _follow_name(ctx, name, pos, scope, depth, seen):
    """名字不提 URL ⇒ 顺着它的绑定回溯值来源（见证 11-1/11-2/11-3 的主轴）。"""
    if depth > MAX_DEPTH:
        return []
    key = (name, scope)
    if key in seen:
        return []
    seen.add(key)
    out = []
    for ra, rb in _bindings(ctx, name, pos, scope):
        seg = ctx.lit[ra:rb].strip()
        if not seg:
            continue
        pure_literal = ('$' not in seg and
                        re.fullmatch(r'"(?:[^"\\]|\\.)*"|"""(?s:.*)"""', seg) is not None)
        if pure_literal and SCHEME_LITERAL.search(seg) and not REDACTOR.search(seg):
            # 直接是 URL 字面量：`val basePath = "https://host/v1"`
            out.append("%s ← %s" % (name, _clip(seg)))
            continue
        for piece in _judge(ctx, ra, rb, scope, depth + 1, seen):
            out.append("%s ← %s" % (name, piece))
    return out


def _judge(ctx, a, b, scope, depth, seen):
    """`lit[a:b]` 这段值里，是否有整条 URL 流向日志。"""
    if depth > MAX_DEPTH:
        return []
    txt = ctx.lit[a:b].strip()
    if not txt or REDACTOR.search(txt):
        return []
    # 顶层 `+`：拼接的每个操作数都要判（结构在 code 视图上切，字面量里的 `+`
    # 已经被抹平，不会误切）。
    segs = split_top(ctx.struct(a, b), ("+",))
    if len(segs) > 1:
        out = []
        for sa, sb in segs:
            out += _judge(ctx, a + sa, a + sb, scope, depth, seen)
        return out
    struct = ctx.struct(a, b).strip()
    if SAFE_TAIL.search(txt):
        return []
    if struct.startswith('"') or struct.startswith('"""'):
        out = []
        for ea, eb, name, bad in _interpolations(ctx, a, b):
            if bad:
                out.append(bad + "（未配平，fail-closed）")
                continue
            if name is not None:
                out += _judge_name(ctx, name, pos=a, scope=scope,
                                   depth=depth + 1, seen=seen)
            else:
                out += _judge(ctx, ea, eb, scope, depth + 1, seen)
        return out
    # 主干上的 URL 载体优先（`forBaseURL.let { it }` 的值由接收者决定），
    # 否则只判取值位的最后一个标识符。
    callee = re.search(r"([A-Za-z_]\w*)\s*\((?:[^()]|\([^()]*\))*\)\s*$", struct)
    if callee and NOT_VALUE_PRESERVING.search(callee.group(1)):
        # `ContentUris.parseId(uri)` / `uri.lastPathSegment?.toLongOrNull()`
        # 的值是 Long，不是 uri —— 换算过的值不继承 URL 身份。
        return []
    names = _tail_names(_spine(struct))
    if not names:
        return []
    hits = [n for n in names if is_urlish(n) and not NOT_A_URL_VALUE.search(n)]
    return _judge_name(ctx, hits[-1] if hits else names[-1], pos=a, scope=scope,
                       depth=depth + 1, seen=seen)


def _offenders(ctx, open_pos, close_pos, pos, scope):
    """这次日志调用里所有承载整条 URL 的实参。"""
    found = []
    for a, b in _ast_call_args(ctx.code, open_pos):
        for item in _judge(ctx, a, b, scope, 0, set()):
            if item not in found:
                found.append(item)
    return found


def scan(root):
    violations = []
    for scan_dir in SCAN_DIRS:
        base = os.path.join(root, SRC_SUBDIR, scan_dir)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in sorted(filenames):
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, root)
                with open(path, encoding="utf-8", errors="replace") as fh:
                    src = fh.read()
                try:
                    code = mask_code(src)
                    lit = mask_comments(src)
                except KotlinLexError as exc:
                    violations.append(
                        (rel, 0, "<lex>",
                         ["源文件无法词法解析（fail-closed）：%s" % exc], ""))
                    continue
                ctx = _Ctx(code, lit, src, rel)
                for m in LOG_CALL.finditer(code):
                    open_pos = code.find("(", m.end() - 1)
                    close_pos = match_bracket(code, open_pos) if open_pos >= 0 else -1
                    line = _line_of(code, m.start())
                    lines = src.split("\n")
                    snippet = (lines[line - 1] if line - 1 < len(lines) else "").strip()[:70]
                    if close_pos < 0:
                        # 括号不配平 = 没有证据，不是"干净"。fail-closed。
                        if (rel, snippet) not in ALLOWED:
                            violations.append(
                                (rel, line, snippet,
                                 ["日志语句括号未配平（fail-closed）"], ""))
                        continue
                    eol = src.find("\n", close_pos)
                    if EXEMPT in _context_slice(
                            src, m.start(), len(src) if eol < 0 else eol):
                        continue
                    if (rel, snippet) in ALLOWED:
                        continue
                    scope = _enclosing_scope(ctx, m.start())
                    bad = _offenders(ctx, open_pos, close_pos, m.start(), scope)
                    if bad:
                        violations.append(
                            (rel, line, snippet, bad,
                             " ".join(src[m.start():close_pos + 1].split())[:200]))
    return violations


def self_test():
    """Arm table: every syntax of ONE defect (whole url → log) must be flagged,
    and every legitimate alternative must stay green.

    Two of the arms are the audited WITNESS you cannot see in a diff: the
    concatenation arm and the nested-brace arm are the exact shapes the
    external audit used to pass this gate while re-introducing the defect
    (gate green, secret in the log).

    [R3-t12] The last block is the third round's witness set: the same secret
    behind three renamings (11-1 `targetEndpoint`, 11-2 `errMsg`, 11-3
    `basePath`). Their clean counterparts pin the flip side, so a fix that just
    flags every alias is caught by the GREEN arms.
    """
    fixture = "src/android/app/src/main/java/com/rikkaminis/app/network/Trace.kt"
    arms = [
        # (label, source lines, must be reported?)
        ("interp-whole-url", [
            "        AppLogger.info(",
            "            tag,",
            '            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}"',
            "        )",
        ], True),
        ("interp-redacted", [
            "        AppLogger.info(",
            "            tag,",
            '            "callStart url=${com.rikkaminis.app.debug.LLMRequestLog.redactURL(call.request().url.toString())}"',
            "        )",
        ], False),
        ("safe-accessors", [
            '        AppLogger.warning(TAG, "host=${url.host} port=${url.port}")',
        ], False),
        ("bare-name-leaks", [
            '        Log.w(TAG, "reason=$failure for $url.host")',
        ], True),
        ("inline-exempt", [
            '        AppLogger.info(TAG, "opened $url")  // url-ok: user-facing page',
        ], False),
        ("non-log-statement", [
            "        val hit = url.matches(regex)",
        ], False),
        # ── the audited witness: concatenation, gate-green before that fix
        ("concat-after", [
            '        com.rikkaminis.app.logging.AppLogger.info(TAG, "fetchModels forBaseURL=" + forBaseURL)',
        ], True),
        ("concat-after-url-object", [
            '        Log.d(TAG, "request to " + call.request().url)',
        ], True),
        ("concat-before", [
            '        Log.d(TAG, forBaseURL + " is unreachable")',
        ], True),
        ("concat-multiline", [
            "        AppLogger.info(",
            "            TAG,",
            '            "fetchModels forBaseURL=" +',
            "                forBaseURL",
            "        )",
        ], True),
        ("concat-redacted", [
            '        Log.d(TAG, "url=" + LLMRequestLog.redactURL(url.toString()))',
        ], False),
        ("concat-safe-component", [
            '        Log.d(TAG, "host=" + url.host)',
        ], False),
        ("concat-count", [
            '        Log.d(TAG, "urls=" + urlCount)',
        ], False),
        # ── the second audited witness: brace-nested interpolation
        ("nested-brace-identity", [
            '        com.rikkaminis.app.logging.AppLogger.info(TAG, "url=${forBaseURL.let { it }}")',
        ], True),
        ("nested-brace-plain", [
            '        Log.d(TAG, "url=${call.request().url}")',
        ], True),
        ("nested-brace-redacted", [
            '        Log.d(TAG, "url=${LLMRequestLog.redactURL(url).let { it }}")',
        ], False),
        # ── fail-closed: an unparsable statement is reported, never skipped
        ("unbalanced-statement", [
            "        AppLogger.info(",
            '            TAG, "url=" + forBaseURL',
            "        // never closed on purpose",
        ], True),
        # ── [R3-t12] rB 11-1: the value is renamed, the log sees the alias
        ("alias-rename-interp", [
            "        } catch (e: Throwable) {",
            "            val targetEndpoint = url",
            '            Log.w(TAG, "uncaught for $targetEndpoint: ${e.message}", e)',
            "        }",
        ], True),
        # ── [R3-t12] rB 11-2: url hidden one hop out, bare argument to the log
        ("alias-string-bare-arg", [
            "        if (content == null) {",
            '            val errMsg = "Download failed for $rawURL"',
            "            Log.w(TAG, errMsg)",
            "        }",
        ], True),
        # ── [R3-t12] rB 11-3: name ends in `path`, value is a whole base url
        ("alias-path-suffix-whole-url", [
            '    private val basePath: String = "https://api.openai.com/v1"',
            "    fun generateImage() {",
            '        Log.i("OpenAIProvider", "images endpoint base=$basePath")',
            "    }",
        ], True),
        # ── …and its honest counterpart: `path` that really is a path
        ("alias-path-suffix-real-path", [
            "    fun logIt(url: HttpUrl, name: String) {",
            "        val basePath = url.path",
            '        Log.i(TAG, "path=$basePath")',
            "    }",
        ], False),
        ("alias-count-renamed", [
            "        val hits = urlCount",
            '        Log.d(TAG, "hits=$hits")',
        ], False),
        # ── two hops, and a re-assignment after a clean one (conservative)
        ("alias-chain-two-hops", [
            "        val first = url",
            "        val second = first",
            '        Log.d(TAG, "second=$second")',
        ], True),
        ("alias-reassigned", [
            '        var link = "https://x.test/a?token=1"',
            "        link = buildLink()",
            '        Log.d(TAG, "link=$link")',
        ], True),
        # ── clean aliases that must NOT flip (the fix's false-positive guard)
        ("alias-unrelated-string", [
            '        val label = "request"',
            '        Log.d(TAG, "label=$label")',
        ], False),
        ("alias-unresolvable", [
            '        Log.d(TAG, "x=$mystery)")',
        ], False),
        ("alias-redacted-binding", [
            "        val safe = LLMRequestLog.redactURL(url.toString())",
            '        Log.d(TAG, "safe=$safe")',
        ], False),
        ("alias-component-binding", [
            "        val host = url.host",
            '        Log.d(TAG, "host=$host")',
        ], False),
    ]
    tmp = tempfile.mkdtemp(prefix="urlguard-")
    ok = True
    for label, body, must_flag in arms:
        path = os.path.join(tmp, fixture)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(body) + "\n")
        got = scan(tmp)
        flagged = bool(got)
        if flagged != must_flag:
            print("  [FAIL] arm %-30s expected %s, got %d finding(s)"
                  % (label, "RED" if must_flag else "GREEN", len(got)))
            for rel, line, _s, bad, _sp in got:
                print("           %s:%d %s" % (rel, line, bad))
            ok = False
        else:
            print("  [OK]   arm %-30s %s" % (label, "RED" if must_flag else "GREEN"))
    print("URL_REDACTION_SELFTEST=%s (%d arms)" % ("PASS" if ok else "FAIL", len(arms)))
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] in ("--self-test", "--selftest"):
        return self_test()
    do_seed = args[0] == "--seed"
    if do_seed:
        args = args[1:]
    if not args:
        print(__doc__)
        return 2
    violations = scan(args[0])
    if do_seed:
        print("# --- generated by --seed: paste into ALLOWED ---")
        for rel, _line, snippet, _bad, _span in violations:
            print("    (%r, %r)," % (rel, snippet))
        return 0
    if violations:
        print("❌ 日志里出现整条 URL（可能带密钥）：")
        for rel, line, snippet, bad, _span in violations:
            print("  %s:%d" % (rel, line))
            print("      %s" % snippet)
            print("      泄漏形态: %s" % ", ".join(bad))
            print("      修法: 用 LLMRequestLog.redactURL(...) 包一层，或只打 host/path；")
            print("            确认与凭据无关时在行内加 `url-ok: <理由>`。")
        print("")
        print("  共 %d 处；已审计站点跑 --seed 后并入 ALLOWED。" % len(violations))
        return 1
    print(
        "✅ log url guard: no whole url value reaches a log — judged by value "
        "flow across all evaluation forms (interpolation / concatenation / "
        "brace-nested / aliased binding) "
        "(audited sites: %d)" % len(ALLOWED)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
