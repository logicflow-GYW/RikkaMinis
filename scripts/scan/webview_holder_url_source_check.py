#!/usr/bin/env python3
"""deep-audit-1020 FIX-D (ISSUE-04, P1) — WebViewHolder currentUrl source gate.

Pins the contract introduced by branch fix/da1020-webview-holder-url for
ui/preview/WebViewHolder.kt: the `currentUrl` published by `onPageStarted` must
be mapped back from the WebView's internal asset-loader origin
(`https://appassets.androidplatform.net/<name>`) to the `file://` URL the host
constructed the holder with, via the single-sourced pure mapping pair
(`toAssetLoaderUrl` / `fromAssetLoaderUrl`).

Bug context: `onPageStarted` used to assign the WebView-reported URL directly
(`currentUrl = url`). For local HTML that URL is the asset-loader origin, which
silently broke three consumers that key off the user-visible URL:
  * openExternalFromSheet's FileProvider branch (gated on startsWith("file://"))
    degraded to a meaningless ACTION_VIEW of an internal URL;
  * the pin-to-home entry point (same gate) disappeared;
  * ChatScreen's holder-reuse check (existing.currentUrl != url) compared
    appassets vs file:// and destroyed+rebuilt the holder on every repeat tap.

The gate enforces:
  W1  the `currentUrl = …` assignment in onPageStarted goes through
      `fromAssetLoaderUrl(url, assetRootDir) ?: url` (slice assertion — a
      direct `currentUrl = url` revert is RED);
  W2  the load path uses the same single source
      (`toAssetLoaderUrl(currentUrl) ?: currentUrl`);
  W3  single source: the mapping pair is defined exactly once, the origin host
      constant is declared exactly once, the legacy inline `file.name`
      derivation is gone, and the percent-decoder is the existing
      ui.chat.decodeUriPath (no third implementation);
  W4  the downstream consumers still read `holder.currentUrl`
      (WebPreviewBottomSheet.kt) — guards against "fixing downstream" instead
      of the root cause.

deep-audit-v14 FIX-B1 (ISSUE-01 + ISSUE-02) adds two criteria groups:
  W5  mobile-mode viewport policy in toggleDesktopMode: the mobile `else`
      branch must keep `useWideViewPort` / `loadWithOverviewMode` ON — the
      exact polarity of the init defaults (:127-128). A `= false` revert in
      the function body is RED; deleting the restore is RED (the body must
      consume `mobileViewportSettings()`); the policy itself must stay
      single-sourced and pinned to `true` / `true`.
  W6  gallery window contract (ui/components/ImageGalleryViewer.kt): the
      OUTER `DisposableEffect(Unit)` (host composition, before the Dialog)
      must take its window from the HOST Activity (`view.context as? Activity`)
      — `findDialogWindow(view)` on the host view walks a parent chain with no
      DialogWindowProvider and returned null on every entry (silent no-op);
      the INNER dialog effect must keep `findDialogWindow(dialogView)` and
      apply `galleryDialogWindowFlags()` (FLAG_LAYOUT_NO_LIMITS |
      FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS — without the latter the dialog
      leaves a grey band under the status bar).

Usage: python3 webview_holder_url_source_check.py [ROOT]   (ROOT = repo root)
       python3 webview_holder_url_source_check.py --selftest
Exit:  0 = gate green, 1 = violations found (or selftest failures).
"""
import os
import re
import sys

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import (
    const_bool,
    const_value,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
)
from _kotlin_valueflow import (
    call_args,
    code_view,
    contains_ident,
    expr_end,
    is_single_assignment,
    literal_view,
    path_feasible,
    scope_calls,
    value_sources,
)

HOLDER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebViewHolder.kt"
SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewBottomSheet.kt"
GALLERY_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/components/ImageGalleryViewer.kt"
# dv17-3 (B-03): the other three WebView preview hosts.
FULLSCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewFullscreenScreen.kt"
FILEPREVIEW_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"
KATEX_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/KaTeXView.kt"

ANDROIDVIEW_CALL_RE = re.compile(r"\bAndroidView\s*\(")
KEY_CALL_RE = re.compile(r"\bkey\s*\(")

ASSIGN_RE = re.compile(r"^\s*currentUrl\s*=\s*(.+?)\s*$")


# ---------------------------------------------------------------------------
# 共享原语封装（禁止自写词法器：所有结构分析都走 _kotlin_lexer / _kotlin_ast /
# _kotlin_valueflow，位置等长可回投原文，解析失败一律 fail-closed）
# ---------------------------------------------------------------------------


def code_of(src):
    """mask_code 视图；解析失败返回 None（调用方报违规，不回退原文扫描）。"""
    try:
        return code_view(src)
    except KotlinLexError:
        return None


def _tail_stmt(body):
    spans = stmt_spans(body)
    return body[spans[-1][0]:spans[-1][1]].strip() if spans else body.strip()


def strip_casts(expr):
    """剥掉尾部的 `as T` / `as? T`（`(null as Window?)` 与 `null` 同值）。"""
    e = strip_parens(expr.strip())
    for _ in range(4):
        m = re.search(r"\s+as\s*\??\s*[A-Za-z_][\w.]*(?:\s*<[^<>]*>)?\s*$", e)
        if not m:
            break
        e = strip_parens(e[:m.start()])
    return e


def provably_null(expr, depth=0):
    """表达式是否**不可能**产生非 null 值。

    折叠三类"值被架空"的形态，它们都让映射/守卫的产物变成 null 而拼写齐全：
      * 恒拒过滤器：`x?.takeIf { false }` / `x?.takeUnless { true }`
      * 恒死分支：`if (1 == 2) a else null`
      * 值被换成 null：`x?.let { null }`
    """
    if depth > 6:
        return False
    e = strip_casts(expr)
    if not e:
        return False
    if e == "null":
        return True
    parts = split_top(e, ("?:",))
    if len(parts) == 2:
        return (provably_null(e[parts[0][0]:parts[0][1]], depth + 1)
                and provably_null(e[parts[1][0]:parts[1][1]], depth + 1))
    m = re.match(r"if\s*\(", e)
    if m:
        close = match_bracket(e, m.end() - 1)
        if close > 0:
            b = const_bool(e[m.end():close])
            rest = e[close + 1:]
            k = rest.find("else")
            if k >= 0 and b is not None:
                return provably_null(rest[k + 4:] if b is False else rest[:k], depth + 1)
    sc = [s for s in scope_calls(e) if not e[s[3]:].strip()]
    if sc:
        name, start, open_pos, end = sc[-1]
        if name in ("also", "apply"):
            return provably_null(e[:start], depth + 1)
        if name in ("let", "run", "with"):
            return provably_null(_tail_stmt(e[open_pos + 1:end - 1]), depth + 1)
        if name == "takeIf":
            if const_bool(e[open_pos + 1:end - 1]) is False:
                return True
            return provably_null(e[:start], depth + 1)
        if name == "takeUnless":
            if const_bool(e[open_pos + 1:end - 1]) is True:
                return True
            return provably_null(e[:start], depth + 1)
    return False


def value_forms(expr, depth=0):
    """该位置**真正可能成为值**的所有形式（保守：多算不漏算）。

    区别于点名"某调用出现过"：elvis 左臂被证成恒 null 时不算数（`?: url` 的
    回退臂才是发布出去的东西），丢弃型作用域只留接收者，值型作用域 LAMBDA
    尾表达式才是结果 —— 后两者交给共享的 `value_sources`。
    """
    if depth > 6:
        return [expr]
    e = strip_parens(expr.strip())
    if not e:
        return [expr]
    parts = split_top(e, ("?:",))
    if len(parts) == 2:
        left = e[parts[0][0]:parts[0][1]]
        right = e[parts[1][0]:parts[1][1]]
        out = []
        if not provably_null(left):
            out += value_forms(left, depth + 1)
        return out + value_forms(right, depth + 1)
    for name, start, open_pos, end in scope_calls(e):
        if not e[end:].strip() and name in ("let", "run", "with"):
            if e[open_pos + 1:end - 1].strip() in ("it", "this"):
                return value_forms(e[:start], depth + 1)
    forms = [f for f in value_sources(e) if f.strip()]
    return forms or [e]


def call_site_args(text, pattern):
    """`pattern` 每个调用的实参文本列表：[(pos, [arg_text, ...])]。"""
    out = []
    for m in re.finditer(pattern, text):
        open_pos = m.end() - 1
        if open_pos < 0 or text[open_pos] != "(":
            continue
        out.append((m.start(),
                    [text[a:b].strip() for a, b in call_args(text, open_pos)]))
    return out


def effect_body(code, marker):
    """`marker(...) { … }` 的体（trailing lambda），配平失败 None。"""
    idx = code.find(marker)
    if idx < 0:
        return None
    open_paren = code.find("(", idx)
    if open_paren < 0:
        return None
    close_paren = match_bracket(code, open_paren)
    if close_paren < 0:
        return None
    k = close_paren + 1
    while k < len(code) and code[k] in " \t\r\n":
        k += 1
    if k >= len(code) or code[k] != "{":
        return None
    close = match_bracket(code, k)
    return code[k + 1:close] if close > 0 else None


def function_text(code, name):
    """函数体文本（block / expr 体都支持），找不到 None。"""
    fb = function_body(code, name)
    return code[fb[0]:fb[1]] if fb else None


def w1_published_rhs(rhs):
    """(ok, detail)：onPageStarted 发布的 RHS 是否真的携带反向映射结果。"""
    forms = value_forms(rhs)
    carries = any("fromAssetLoaderUrl(" in f for f in forms)
    tokens = "url" in rhs and "assetRootDir" in rhs and "?:" in rhs
    detail = "RHS = %r; value forms = %r" % (rhs, forms)
    if not tokens:
        detail += " — the mapper call / webview url / assetRootDir / elvis fallback is missing"
    elif not carries:
        detail += (" — the mapper's result never reaches the published value: it is "
                   "called inside a discarding scope (.also/.apply), swallowed by a "
                   "value-replacing scope (.let { other }), or filtered out by a "
                   "provably-rejecting predicate so the `?:` fallback always wins")
    return (tokens and carries), detail


def w2_load_path(holder):
    """(ok, detail)：startIfNeeded 里每个 webView.loadUrl(…) 是否消费映射后的值。"""
    body = function_text(holder, "startIfNeeded")
    if body is None:
        return False, "startIfNeeded(…) not found (the load path moved — re-point this guard)"
    mapped, mapped_rhs = None, ""
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*", body):
        rhs = body[m.end():expr_end(body, m.end())]
        if any("toAssetLoaderUrl(" in f for f in value_forms(rhs)):
            mapped, mapped_rhs = m.group(1), rhs.strip()
            break
    if mapped is None:
        return False, ("no local bound to toAssetLoaderUrl(…) inside startIfNeeded — the "
                       "forward mapping is no longer computed on the load path")
    if not is_single_assignment(body, mapped):
        return False, ("`%s` is re-assigned / shadowed inside startIfNeeded, so the value "
                       "the load site consumes is not provably the mapped one" % mapped)
    sites = call_site_args(body, r"\.\s*loadUrl\s*\(")
    if not sites:
        return False, "no webView.loadUrl(…) call inside startIfNeeded (re-point this guard)"
    bad = []
    for _pos, args in sites:
        arg = ", ".join(args).strip()
        forms = value_forms(arg)
        if not any(f.strip() == mapped or "toAssetLoaderUrl(" in f for f in forms):
            bad.append(arg)
    if bad:
        return False, ("a load site passes %r instead of the mapped local `%s` (= %r) — "
                       "loading the raw file:// URL is refused by the WebView "
                       "(allowFileAccess = false)"
                       % (bad[0], mapped, mapped_rhs))
    return True, "%d load site(s) all consume `%s`" % (len(sites), mapped)


def w4a_external_call(sheet):
    """(ok, detail)：openExternalFromSheet(context, holder.currentUrl) 有活调用点。"""
    required = ["context", "holder.currentUrl"]
    matching = [(p, a) for p, a in call_site_args(sheet, r"\bopenExternalFromSheet\s*\(")
                if a == required]
    live = [p for p, _a in matching if path_feasible(sheet, p)]
    if live:
        return True, "%d reachable openExternalFromSheet(context, holder.currentUrl) site(s)" % len(live)
    if matching:
        return False, ("the call is spelled correctly but sits on an infeasible path "
                       "(dead branch: if (false) / && false / 1 == 2) — the toolbar "
                       "button does nothing")
    return False, ("no openExternalFromSheet(context, holder.currentUrl) call — downstream "
                   "must keep reading the single source")


_VIEWPORT_FIELDS = ("useWideViewPort", "loadWithOverviewMode")
UA_FIELD = "userAgentString"
FILE_STRIP = 'removePrefix("file://")'


def asset_root_problem(holder, holder_lit):
    """W8 — the root the loader serves is a REAL directory derived from the file URL.

    [r3 01-1] `assetRootDir` feeds `fromAssetLoaderUrl(url, assetRootDir)` — the
    input half of the mapping pair this gate exists for. The witness built it
    from the un-stripped URL (`File("file:///…").parentFile` → `file:/…`), so
    the InternalStoragePathHandler root names no directory and every reverse
    mapping returns null; W1/W2/W3 all stay green because the pair itself is
    untouched.
    """
    sites = []
    for m in re.finditer(r"\bassetRootDir\s*(?::[^=\n]*)?=(?!=)", holder):
        end = expr_end(holder, m.end())
        rhs = holder_lit[m.end():end].strip()
        stmts = [rhs]
        for ident in sorted(set(re.findall(r"[A-Za-z_]\w*", rhs))):
            b = re.search(r"\b(?:val|var)\s+" + re.escape(ident) + r"\s*(?::[^=\n]*)?=\s*",
                          holder)
            if b:
                stmts.append(holder_lit[b.end():expr_end(holder, b.end())].strip())
        sites.append(" ".join(stmts))
    if not sites:
        return "no `assetRootDir = …` binding in the holder (fail-closed)"
    if not any("File(" in s for s in sites):
        return ("the asset root is not derived from a File(…) of the initial URL — the "
                "reverse mapping's root half is unanchored (fail-closed)")
    bad = [s for s in sites if "File(" in s and FILE_STRIP not in s]
    if bad:
        return ("the root the loader serves resolves from %r — the `file://` prefix must be "
                "stripped BEFORE File(…), otherwise the root is `file:/…` (no such "
                "directory) and every `fromAssetLoaderUrl` returns null" % bad[0][:90])
    return ""


def w4c_fullscreen_external_call(fullscreen):
    """W4c — the fullscreen "open in external browser" entry reads the same source.

    [r3 02-3] W4a only polices `WebPreviewBottomSheet.kt`. The fullscreen host is
    the symmetric half of the same button, and `holder.webView.url` there is the
    asset-loader origin (`https://appassets.androidplatform.net/…`) — a virtual
    host only this app's interceptor resolves, so the external browser gets a
    URL that cannot load.
    """
    sites = call_site_args(fullscreen, r"\bopenExternalFromSheet\s*\(")
    if not sites:
        return False, ("no openExternalFromSheet(…) in WebPreviewFullscreenScreen.kt "
                       "(fail-closed)")
    bad = []
    for pos, args in sites:
        if len(args) < 2:
            bad.append(", ".join(args) or "<no args>")
            continue
        arg = args[1]
        if "webView.url" in arg or "webView.getUrl" in arg or "webView.originalUrl" in arg:
            bad.append(arg)
        elif not contains_ident(arg, "currentUrl"):
            bad.append(arg)
        elif not path_feasible(fullscreen, pos):
            bad.append(arg + " (dead path)")
    if bad:
        return False, ("the fullscreen entry passes %r — the WebView-reported URL is the "
                       "asset-loader origin, which no external browser can resolve; it must "
                       "read holder.currentUrl" % bad[0])
    return True, "%d fullscreen call site(s) read holder.currentUrl" % len(sites)


def first_param_name(code, fn_name):
    """Name of the first parameter of `fun <fn_name>(…)`, or ''."""
    m = re.search(r"\bfun\s+(?:[\w.]+\.)?" + re.escape(fn_name) + r"\s*\(", code)
    if not m:
        return ""
    spans = call_args(code, m.end() - 1)
    if not spans:
        return ""
    a, b = spans[0]
    name = code[a:b].split(":")[0].strip()
    return name if re.fullmatch(r"[A-Za-z_]\w*", name) else ""


def _field_rhs(text, field):
    """[(pos, rhs)] for every `field = …` write in `text`."""
    out = []
    for m in re.finditer(r"(?<![\w`])" + field + r"\s*=(?!=)", text):
        rhs = text[m.end():expr_end(text, m.end())]
        semi = rhs.find(";")
        if semi >= 0:
            rhs = rhs[:semi]
        out.append((m.start(), rhs.strip()))
    return out


def to_asset_name_problem(holder):
    """W3f — the forward mapping's encoded segment comes from the file URL.

    [r3 02-1] The witness set `val name = ""` inside `toAssetLoaderUrl`: the
    mapping pair, the origin constant, the call sites W2 grades and the loader
    import all stay intact, but every file URL now maps to `null`, so
    `toAssetLoaderUrl(x) ?: currentUrl` always takes the fallback and the
    asset-loader origin is never used (relative fetch/module loads then fail on
    the opaque `file://` origin).
    """
    body = function_text(holder, "toAssetLoaderUrl")
    if body is None:
        return "toAssetLoaderUrl(…) not found (fail-closed)"
    param = first_param_name(holder, "toAssetLoaderUrl")
    if not param:
        return "toAssetLoaderUrl(…) has no parameter to derive the name from (fail-closed)"
    enc = list(re.finditer(r"\bencodePathSegment\s*\(", body))
    if len(enc) != 1:
        return ("toAssetLoaderUrl must encode exactly one path segment (found %d) — the "
                "loader URL carries the file name" % len(enc))
    spans = call_args(body, enc[0].end() - 1)
    if not spans:
        return "encodePathSegment(…) has no readable argument (fail-closed)"
    arg = "".join(body[a:b] for a, b in spans).strip()
    if re.fullmatch(r"[A-Za-z_]\w*", arg):
        b = re.search(r"\b(?:val|var)\s+" + re.escape(arg) + r"\s*(?::[^=\n]*)?=\s*", body)
        if b:
            arg = body[b.end():expr_end(body, b.end())].strip()
    forms = value_forms(arg)
    carries = [f for f in forms
               if contains_ident(f, param) and "substringAfterLast" in f]
    if not carries:
        return ("the encoded segment resolves to %r — it must be the last path segment of "
                "`%s`; a constant name makes the forward mapping encode nothing, so "
                "`?: currentUrl` always wins and the asset-loader origin is never used"
                % (forms[:2], param))
    return ""


def viewport_writes(text):
    """[(pos, field, rhs)]：对两个视口字段的所有写入。

    覆盖点号形态（`webView.settings.useWideViewPort = …`）**与**接收者作用域里的
    裸写（`with(webView.settings) { useWideViewPort = … }` / `apply { … }`）——
    只认前导点号会让 `with` 整段隐身。
    """
    out = []
    for field in _VIEWPORT_FIELDS:
        for m in re.finditer(r"(?<![\w`])" + field + r"\s*=(?!=)", text):
            rhs = text[m.end():expr_end(text, m.end())]
            # 共享 expr_end 不在顶层 `;` 截断（R 项已登记）：这里自己切，避免把
            # 下一条语句卷进 RHS 让值判定看到不属于自己的文本。
            semi = rhs.find(";")
            if semi >= 0:
                rhs = rhs[:semi]
            out.append((m.start(), field, rhs.strip()))
    out.sort()
    return out


def controller_receiver(outer, win_name):
    """(ok, detail)：外层效果里构建 WindowInsetsControllerCompat 的接收者链。"""
    for m in re.finditer(r"\bval\s+([A-Za-z_]\w*)\s*=\s*", outer):
        rhs = outer[m.end():expr_end(outer, m.end())]
        ctor = rhs.find("WindowInsetsControllerCompat")
        if ctor < 0:
            continue
        receiver = rhs[:ctor]
        opener = re.search(r"\?*\s*\.\s*(?:let|run|map)\s*\{?\s*$", receiver)
        if opener:
            receiver = receiver[:opener.start()]
        receiver = strip_parens(receiver.strip())
        detail = "`%s` is built from `%s` (window local `%s`)" % (
            m.group(1), " ".join(receiver.split())[:90], win_name)
        if provably_null(receiver):
            return False, (detail + " — that receiver chain is provably null (always-rejecting "
                                     "filter / always-dead branch / null cast), so the "
                                     "controller never hides the system bars")
        if not contains_ident(receiver, win_name):
            return False, detail + " — that is not the host-activity window"
        return True, detail
    return False, "no `val <controller> = …WindowInsetsControllerCompat(…)` binding in the outer effect"


def key_wrapped_blocks(code, callee="AndroidView"):
    """[(args_text, body_text)]：trailing lambda 里调用 `callee(` 的每个 `key(...)`。"""
    out = []
    for m in KEY_CALL_RE.finditer(code):
        open_paren = m.end() - 1
        close_paren = match_bracket(code, open_paren)
        if close_paren < 0:
            continue
        k = close_paren + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        close = match_bracket(code, k)
        if close < 0:
            continue
        body = code[k:close + 1]
        if not ANDROIDVIEW_CALL_RE.search(body):
            continue
        out.append((code[open_paren + 1:close_paren], body))
    return out


def androidview_key_criterion(label, code, required_tokens, site):
    """判定：每个 `AndroidView(` 调用点都必须落在 `key(...)` 块里，且 key 的**值**
    携带数据源（不只是参数文本里出现过这个名字）。

    `key(holder.let { 0 })` 保留了 `holder` 这个 token，但 Compose 拿到的键恒为
    `0`，节点永不重建 —— 判据必须看值，不看拼写。
    """
    masked = code_of(code)
    if masked is None:
        return (label, False, "KotlinLexError in %s (fail-closed)" % site, None)
    total = len(ANDROIDVIEW_CALL_RE.findall(masked))
    blocks = key_wrapped_blocks(masked)
    wrapped = sum(len(ANDROIDVIEW_CALL_RE.findall(b)) for _a, b in blocks)
    bad = []
    for args, _body in blocks:
        forms = []
        for a, b in split_top(args, (",",)):
            forms += value_forms(args[a:b])
        missing = [t for t in required_tokens
                   if not any(re.search(r"\b" + re.escape(t) + r"\b", f) for f in forms)]
        consts = [f for f in forms if const_value(f) is not None]
        if missing or consts:
            bad.append((args.strip(), missing, forms, consts))
    ok = total >= 1 and wrapped == total and not bad
    if bad:
        args, missing, forms, consts = bad[0]
        detail = ("%s: key(%s) resolves to %r" % (site, args, forms))
        if consts:
            detail += (" — the key value is a constant, so the AndroidView node is "
                       "never re-created when the data source changes")
        else:
            detail += " — the key value does not carry %r" % (missing,)
    else:
        detail = ("%s: %d AndroidView site(s), %d outside key(...); key(...) values carry %s"
                  % (site, total, total - wrapped, list(required_tokens)))
    return (label, ok, detail, None)


def analyse(holder_code, sheet_code, gallery_code=None, filepreview_code=None,
            katex_code=None, fullscreen_code=None):
    """Return [(criterion, ok, detail, line_no_or_None)]."""
    holder = code_of(holder_code)
    sheet = code_of(sheet_code)
    if holder is None or sheet is None:
        return [("Kotlin source parse", False,
                 "KotlinLexError — the lexer refused the source (fail-closed)", None)]

    results = []

    # --- W1: the onPageStarted assignment publishes the reverse-mapped value ---
    holder_lines = holder.splitlines()
    assigns = [(i, m.group(1)) for i, l in enumerate(holder_lines)
               if (m := ASSIGN_RE.match(l))]
    if len(assigns) != 1:
        results.append(("W1 currentUrl assignment present and unique",
                        False,
                        "expected exactly one `currentUrl = …` in code, found %d"
                        % len(assigns), None))
    else:
        line_no, rhs = assigns[0]
        ok, detail = w1_published_rhs(rhs)
        results.append(("W1 onPageStarted publishes via fromAssetLoaderUrl(url, assetRootDir)",
                        ok, detail, line_no + 1))

    # --- W2: the load path consumes the forward-mapped value ---
    ok2, d2 = w2_load_path(holder)
    results.append(("W2 load path uses toAssetLoaderUrl(currentUrl) ?: currentUrl",
                    ok2, d2, None))

    # --- W3: single source for the mapping pair / origin / decode ---
    n_to = len(re.findall(r"\bfun\s+toAssetLoaderUrl\(", holder))
    n_from = len(re.findall(r"\bfun\s+fromAssetLoaderUrl\(", holder))
    results.append(("W3a toAssetLoaderUrl defined exactly once", n_to == 1,
                    "found %d definitions" % n_to, None))
    results.append(("W3b fromAssetLoaderUrl defined exactly once", n_from == 1,
                    "found %d definitions" % n_from, None))
    n_host = len(re.findall(r"\bconst\s+val\s+ASSET_LOADER_HOST\b", holder))
    results.append(("W3c origin host constant declared exactly once", n_host == 1,
                    "found %d declarations" % n_host, None))
    try:
        legacy = "file.name" in literal_view(holder_code)
    except KotlinLexError:
        legacy = True
    results.append(("W3d legacy inline file.name derivation removed", not legacy,
                    "file.name still present in code", None))
    n_decode = len(re.findall(r"import\s+com\.rikkaminis\.app\.ui\.chat\.decodeUriPath", holder))
    results.append(("W3e percent-decode reuses ui.chat.decodeUriPath", n_decode == 1,
                    "found %d imports" % n_decode, None))
    problem3f = to_asset_name_problem(holder)
    results.append(("W3f toAssetLoaderUrl encodes the file URL's own name segment",
                    problem3f == "", problem3f or "the encoded segment comes from the URL", None))
    problem8 = asset_root_problem(holder, literal_view(holder_code))
    results.append(("W8 asset root is a real directory derived from the file:// URL",
                    problem8 == "", problem8 or "assetRootDir = parent of the stripped path", None))

    # --- W4: downstream still reads the single source, on a live path ---
    ok_ext, d_ext = w4a_external_call(sheet)
    results.append(("W4a openExternalFromSheet still reads holder.currentUrl", ok_ext,
                    d_ext, None))
    try:
        ok_pin = 'holder.currentUrl.startsWith("file://")' in literal_view(sheet_code)
    except KotlinLexError:
        ok_pin = False
    results.append(("W4b pin entry still gates on holder.currentUrl", ok_pin,
                    "downstream must keep reading the single source", None))
    if fullscreen_code is not None:
        fs = code_of(fullscreen_code)
        if fs is None:
            results.append(("W4c fullscreen openExternalFromSheet reads holder.currentUrl",
                            False,
                            "KotlinLexError in WebPreviewFullscreenScreen.kt (fail-closed)",
                            None))
        else:
            ok_fs, d_fs = w4c_fullscreen_external_call(fs)
            results.append(("W4c fullscreen openExternalFromSheet reads holder.currentUrl",
                            ok_fs, d_fs, None))

    # --- W5: mobile-mode viewport policy in toggleDesktopMode (dv14-B1) ---
    if "fun toggleDesktopMode(" in holder:
        body = function_text(holder, "toggleDesktopMode")
        if body is None:
            results.append(("W5 toggleDesktopMode body sliceable", False,
                            "toggleDesktopMode body not found / unterminated", None))
        else:
            writes = viewport_writes(body)
            off = [w for w in writes
                   if const_bool(w[2]) is False and path_feasible(body, w[0])]
            results.append((
                "W5a toggleDesktopMode never turns the mobile viewport off", not off,
                ("constant-false write `%s = %s` in the body flips the mobile viewport off "
                 "(spelling-independent: `false`, `1 == 2`, `!true` all fold here)"
                 % (off[0][1], off[0][2])) if off else
                "no provably-false viewport write in the body", None))
            n_policy = len(re.findall(r"\bfun\s+mobileViewportSettings\(", holder))
            results.append(("W5b mobileViewportSettings defined exactly once", n_policy == 1,
                            "found %d definitions" % n_policy, None))
            pin_win = window_after(holder, "fun mobileViewportSettings", 300)
            pinned = (pin_win is not None
                      and "useWideViewPort = true" in pin_win
                      and "loadWithOverviewMode = true" in pin_win)
            results.append(("W5c policy stays pinned to useWideViewPort/overview = true", pinned,
                            "policy body must keep both flags ON (window after the "
                            "definition, %s)" % ("missing" if pin_win is None else pin_win[:120]),
                            None))
            consumes = "mobileViewportSettings()" in body
            results.append(("W5d mobile else branch consumes mobileViewportSettings()", consumes,
                            "deleting the restore is RED (body must go through the "
                            "single-sourced policy)", None))
            neg = [w for w in writes
                   if w[2].lstrip().startswith("!") and path_feasible(body, w[0])]
            results.append((
                "W5e toggleDesktopMode assigns the policy without negation", not neg,
                ("negated assignment (%r) inverts the mobile viewport again"
                 % (neg[0][2] if neg else "")), None))

            mobile_branch = None
            for cond, _ts, _te, e_start, e_end in if_blocks(body):
                if e_start is not None and "desktopMode" in cond:
                    mobile_branch = body[e_start:e_end]
                    break
            if mobile_branch is not None:
                policy_local = re.search(
                    r"val\s+([A-Za-z_]\w*)\s*=\s*mobileViewportSettings\s*\(\s*\)", body)
                recv = [re.escape(policy_local.group(1))] if policy_local else []
                recv.append(r"mobileViewportSettings\s*\(\s*\)")
                recv_re = "(?:" + "|".join(recv) + ")"
                mwrites = viewport_writes(mobile_branch)
                for field in _VIEWPORT_FIELDS:
                    assigned = [w[2] for w in mwrites if w[1] == field]
                    bad = [a for a in assigned
                           if not re.fullmatch(r"!?\s*\(?\s*" + recv_re
                                               + r"\s*\.\s*" + field + r"\s*\)?", a)]
                    results.append((
                        "W5f mobile branch copies the policy's own %s field" % field,
                        not bad,
                        "assigned %r -- the mobile branch must copy the policy field "
                        "(a constant / a derived predicate such as "
                        "`mobile.initialScale > 0` flips the viewport off because "
                        "initialScale is 0)" % (bad[0] if bad else ""), None))

                # [r3 02-2] The mobile branch must actually SWITCH BACK: the UA
                # value and the initial scale are the two settings the desktop
                # branch sets and the viewport fields do not cover. The witness
                # left every viewport token intact and kept writing DESKTOP_UA +
                # a fixed scale here, so the cache-clear + reload below
                # re-requested the desktop page at 100% zoom.
                desktop_branch = None
                for cond, ts, te, e_start2, e_end2 in if_blocks(body):
                    if e_start2 is not None and "desktopMode" in cond:
                        desktop_branch = body[ts:te]
                        break
                mob_ua = [rhs for _p, rhs in _field_rhs(mobile_branch, UA_FIELD)]
                desk_ua = [rhs for _p, rhs in _field_rhs(desktop_branch or "", UA_FIELD)]
                bad_ua = []
                if desk_ua and not mob_ua:
                    bad_ua.append("<no mobile UA write>")
                for rhs in mob_ua:
                    if desk_ua and rhs.strip() == desk_ua[0].strip():
                        bad_ua.append(rhs)
                    elif const_value(rhs) is not None:
                        bad_ua.append(rhs)
                results.append((
                    "W5g mobile branch switches the UA back off the desktop value",
                    not bad_ua,
                    ("the mobile branch writes %s while the desktop branch writes %r — "
                     "switching back to mobile then clearCache()+reload()s with the "
                     "desktop user agent, so the server keeps serving the desktop page"
                     % (bad_ua[0] if bad_ua else "", desk_ua[0] if desk_ua else ""))
                    if bad_ua else
                    "mobile_ua=%r desktop_ua=%r" % (mob_ua, desk_ua), None))

                bad_scale = []
                for m in re.finditer(r"\bsetInitialScale\s*\(", mobile_branch):
                    spans = call_args(mobile_branch, m.end() - 1)
                    arg = ", ".join(mobile_branch[a:b] for a, b in spans).strip()
                    if const_value(arg) is not None:
                        bad_scale.append(arg)
                    elif policy_local and not contains_ident(arg, policy_local.group(1)):
                        bad_scale.append(arg)
                results.append((
                    "W5h mobile branch restores the policy's initial scale",
                    not bad_scale,
                    ("setInitialScale(%s) is a fixed number — the mobile scale must come "
                     "from the single-sourced policy, otherwise the page stays locked at "
                     "that zoom after switching back"
                     % (bad_scale[0] if bad_scale else "")), None))

    # --- W6: gallery window contract (dv14-B1) ---
    if gallery_code is not None:
        gallery = code_of(gallery_code)
        if gallery is None:
            results.append(("W6 gallery source parse", False,
                            "KotlinLexError in ImageGalleryViewer.kt (fail-closed)", None))
            gallery = None
    if gallery_code is not None and gallery is not None:
        outer = effect_body(gallery, "DisposableEffect(Unit)")
        if outer is None:
            results.append(("W6 gallery outer DisposableEffect(Unit) sliceable", False,
                            "outer effect not found / unterminated", None))
        else:
            ok_host = "(view.context as? android.app.Activity)?.window" in outer
            results.append(("W6a gallery outer effect takes the HOST Activity window", ok_host,
                            "the window must come from view.context as Activity, "
                            "not from a DialogWindowProvider walk", None))
            ok_no_walk = "findDialogWindow(view)" not in outer
            results.append(("W6b outer effect must not call findDialogWindow(view)", ok_no_walk,
                            "findDialogWindow(view) on the host view walks a chain "
                            "with no DialogWindowProvider (always null)", None))
            win_local = re.search(
                r"\bval\s+([A-Za-z_]\w*)\s*=\s*[^\n]*as\?\s+android\.app\.Activity[^\n]*",
                outer)
            if win_local is None:
                results.append(("W6e gallery outer effect builds the controller from that window",
                                False,
                                "the host-activity window is not bound to a local", None))
            else:
                ok_ctl, ctl_detail = controller_receiver(outer, win_local.group(1))
                results.append(("W6e gallery outer effect builds the controller from that window",
                                ok_ctl, ctl_detail, None))
        inner = effect_body(gallery, "DisposableEffect(dialogView)")
        if inner is None:
            results.append(("W6 gallery inner DisposableEffect(dialogView) sliceable", False,
                            "inner effect not found / unterminated", None))
        else:
            ok_flags_call = "galleryDialogWindowFlags()" in inner
            results.append(("W6c inner dialog effect applies galleryDialogWindowFlags()",
                            ok_flags_call,
                            "the dialog window must get FLAG_LAYOUT_NO_LIMITS | "
                            "FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS", None))
        n_flags = len(re.findall(r"\bfun\s+galleryDialogWindowFlags\(", gallery))
        flags_win = window_after(gallery, "fun galleryDialogWindowFlags", 300)
        ok_flags_def = (n_flags == 1 and flags_win is not None
                        and "FLAG_LAYOUT_NO_LIMITS" in flags_win
                        and "FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS" in flags_win)
        results.append(("W6d galleryDialogWindowFlags defined once with both flags", ok_flags_def,
                        "found %d definitions; both flag constants must stay in the "
                        "composition" % n_flags, None))

    # --- W7: preview WebView hosts must key the AndroidView on its data source (dv17-3) ---
    if fullscreen_code is not None:
        results.append(androidview_key_criterion(
            "W7a WebPreviewBottomSheet AndroidView is keyed on the holder",
            sheet_code, ("holder",), "WebPreviewBottomSheet.kt"))
        results.append(androidview_key_criterion(
            "W7b WebPreviewFullscreenScreen AndroidView is keyed on the holder",
            fullscreen_code, ("holder",), "WebPreviewFullscreenScreen.kt"))
    if filepreview_code is not None:
        results.append(androidview_key_criterion(
            "W7c FilePreviewScreen HtmlPreview keys AndroidView on (item.file, rendererEpoch)",
            filepreview_code, ("item.file", "rendererEpoch"), "FilePreviewScreen.kt"))
    if katex_code is not None:
        results.append(androidview_key_criterion(
            "W7d KaTeXView keys AndroidView on (cacheKey, rendererEpoch)",
            katex_code, ("cacheKey", "rendererEpoch"), "KaTeXView.kt"))
    return results


def window_after(code, marker, size):
    """Bounded text window starting at `marker` (first match) — for pinning
    expression-bodied declarations whose body has no braces to slice."""
    idx = code.find(marker)
    if idx < 0:
        return None
    return code[idx:idx + size]

# ---------------------------------------------------------------- selftest ---
GOOD_HOLDER = """package probe
class H(private val initialUrl: String) {
    var currentUrl: String = "file:///d/a.html"
    private val assetParent = java.io.File(initialUrl.removePrefix("file://")).parentFile
    private val assetRootDir: String? = assetParent?.absolutePath
    fun onPageStarted(url: String) {
        currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url
    }
    fun startIfNeeded() {
        val loadUrl = toAssetLoaderUrl(currentUrl) ?: currentUrl
        webView.loadUrl(loadUrl)
        webView.post { webView.loadUrl(loadUrl) }
    }
    internal const val ASSET_LOADER_HOST = "appassets.androidplatform.net"
}
internal fun toAssetLoaderUrl(fileUrl: String): String? {
    if (!fileUrl.startsWith(FILE_URL_PREFIX)) return null
    val name = fileUrl.removePrefix(FILE_URL_PREFIX).substringAfterLast('/')
    if (name.isEmpty()) return null
    return ASSET_LOADER_PREFIX + encodePathSegment(name)
}
internal fun fromAssetLoaderUrl(assetUrl: String, rootDir: String?): String? = null
import com.rikkaminis.app.ui.chat.decodeUriPath
"""

# [r3 01-1] the loader root is built from the un-stripped URL (W8 red, rest OK)
ROOT_UNSTRIPPED_HOLDER = GOOD_HOLDER.replace(
    'java.io.File(initialUrl.removePrefix("file://")).parentFile',
    "java.io.File(initialUrl).parentFile")
assert ROOT_UNSTRIPPED_HOLDER != GOOD_HOLDER

# [r3 02-1] the forward mapping encodes a constant name (W3f red, rest OK)
NAME_CONSTANT_HOLDER = GOOD_HOLDER.replace(
    "val name = fileUrl.removePrefix(FILE_URL_PREFIX).substringAfterLast('/')",
    'val name = ""')
assert NAME_CONSTANT_HOLDER != GOOD_HOLDER

# [r3 02-3] the fullscreen external-open entry reads the WebView-reported URL
W4_FULLSCREEN_GOOD = """package probe
onOpenExternal = { openExternalFromSheet(context, holder.currentUrl) }
"""
W4_FULLSCREEN_BAD = """package probe
onOpenExternal = { openExternalFromSheet(context, holder.webView.url ?: "") }
"""

BAD_HOLDER = GOOD_HOLDER.replace(
    "currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url",
    "currentUrl = url")
assert BAD_HOLDER != GOOD_HOLDER

LEGACY_HOLDER = """package probe
class H {
    var currentUrl: String = "file:///d/a.html"
    fun onPageStarted(url: String) {
        currentUrl = "https://$ASSET_LOADER_HOST/${file.name}"
    }
    fun startIfNeeded() {
        val loadUrl = toAssetLoaderUrl(currentUrl) ?: currentUrl
        webView.loadUrl(loadUrl)
        webView.post { webView.loadUrl(loadUrl) }
    }
}
internal const val ASSET_LOADER_HOST = "appassets.androidplatform.net"
internal fun toAssetLoaderUrl(fileUrl: String): String? = null
internal fun fromAssetLoaderUrl(assetUrl: String, rootDir: String?): String? = null
"""

GOOD_SHEET = """package probe
onOpenExternal = { openExternalFromSheet(context, holder.currentUrl) }
onPinToHome = if (holder.currentUrl.startsWith("file://")) x else null
"""

BAD_SHEET = """package probe
onOpenExternal = { openExternalFromSheet(context, holder.url) }
onPinToHome = if (holder.isLocalFile) x else null
"""

# ---- dv14-B1 fixtures: W5 (toggleDesktopMode viewport policy) -------------

DESKTOP_GOOD_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        if (desktopMode) {
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        } else {
            val mobile = mobileViewportSettings()
            webView.settings.useWideViewPort = mobile.useWideViewPort
            webView.settings.loadWithOverviewMode = mobile.loadWithOverviewMode
            webView.setInitialScale(mobile.initialScale)
        }
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

DESKTOP_FALSE_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        if (desktopMode) {
            webView.settings.useWideViewPort = true
        } else {
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
        }
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

DESKTOP_POLICY_FLIPPED_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        val mobile = mobileViewportSettings()
        webView.settings.useWideViewPort = mobile.useWideViewPort
        webView.settings.loadWithOverviewMode = mobile.loadWithOverviewMode
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = false, loadWithOverviewMode = false, initialScale = 0)
"""

DESKTOP_NO_RESTORE_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        if (desktopMode) {
            webView.settings.useWideViewPort = true
        } else {
            webView.settings.userAgentString = mobileUserAgent
        }
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

DESKTOP_NEGATED_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        val mobile = mobileViewportSettings()
        webView.settings.useWideViewPort = !mobile.useWideViewPort
        webView.settings.loadWithOverviewMode = !mobile.loadWithOverviewMode
        webView.setInitialScale(mobile.initialScale)
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

# [r3 02-2] the mobile branch keeps the desktop UA and a fixed scale — both
# settings the desktop branch owns and the viewport fields do not cover.
DESKTOP_UA_REVERT_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        if (desktopMode) {
            webView.settings.userAgentString = DESKTOP_UA
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        } else {
            webView.settings.userAgentString = DESKTOP_UA
            val mobile = mobileViewportSettings()
            webView.settings.useWideViewPort = mobile.useWideViewPort
            webView.settings.loadWithOverviewMode = mobile.loadWithOverviewMode
            webView.setInitialScale(100)
        }
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

# control: the same two writes, derived from the mobile source
DESKTOP_UA_CLEAN_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        if (desktopMode) {
            webView.settings.userAgentString = DESKTOP_UA
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        } else {
            webView.settings.userAgentString = mobileUserAgent
            val mobile = mobileViewportSettings()
            webView.settings.useWideViewPort = mobile.useWideViewPort
            webView.settings.loadWithOverviewMode = mobile.loadWithOverviewMode
            webView.setInitialScale(mobile.initialScale)
        }
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

# ---- dv14-B1 fixtures: W6 (gallery window contract) ------------------------

GALLERY_GOOD = """package probe
val view = LocalView.current
DisposableEffect(Unit) {
    val window = (view.context as? android.app.Activity)?.window
    val controller = window?.let { WindowInsetsControllerCompat(it, view) }
    controller?.hide(WindowInsetsCompat.Type.systemBars())
    onDispose { }
}
Dialog {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val win = findDialogWindow(dialogView)
        win?.let { w -> w.addFlags(galleryDialogWindowFlags()) }
        onDispose { }
    }
}
internal fun galleryDialogWindowFlags(): Int =
    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
"""

GALLERY_WALKER_REVERT = GALLERY_GOOD.replace(
    "val window = (view.context as? android.app.Activity)?.window",
    "val window = findDialogWindow(view)")

GALLERY_FLAGS_MISSING = GALLERY_GOOD.replace(
    "w.addFlags(galleryDialogWindowFlags())", "w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)").replace(
    """internal fun galleryDialogWindowFlags(): Int =
    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
""", "")


# ---- dv17-3 fixtures: W7 (AndroidView data-source keys) --------------------

W7_GOOD_SHEET = GOOD_SHEET + """
@Composable
fun Sheet(holder: WebViewHolder) {
    Box(modifier = Modifier.fillMaxSize()) {
        key(holder) {
        AndroidView(factory = { holder.webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
"""

# the reported shape: node with neither key(…) nor update
W7_BAD_SHEET = GOOD_SHEET + """
@Composable
fun Sheet(holder: WebViewHolder) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.webView }, modifier = Modifier.fillMaxSize())
    }
}
"""

W7_GOOD_FULLSCREEN = GOOD_SHEET + """
@Composable
fun Fullscreen(holder: WebViewHolder) {
    Box(modifier = Modifier.fillMaxSize()) {
        key(holder) {
        AndroidView(factory = { holder.webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
"""

W7_BAD_FULLSCREEN = GOOD_SHEET + """
@Composable
fun Fullscreen(holder: WebViewHolder) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.webView }, modifier = Modifier.fillMaxSize())
    }
}
"""

W7_GOOD_PREVIEW = """@Composable
private fun HtmlPreview(item: FileItem) {
    var rendererEpoch by remember(item.file) { mutableStateOf(0) }
    key(item.file, rendererEpoch) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : SafeWebViewClient() {
                    override fun onRendererGone(view: WebView?) {
                        rendererEpoch += 1
                    }
                }
            }
        },
    )
    }
}
"""

# the reported shape: bare rendererEpoch key (resets 0→0 on a file change)
W7_BAD_PREVIEW = W7_GOOD_PREVIEW.replace("key(item.file, rendererEpoch) {", "key(rendererEpoch) {")

# key present, but the renderer-death rebuild was dropped from it
W7_PREVIEW_NO_EPOCH = W7_GOOD_PREVIEW.replace("key(item.file, rendererEpoch) {", "key(item.file) {")

W7_GOOD_KATEX = """@Composable
private fun KaTeXRenderView(latex: String, displayMode: Boolean) {
    val cacheKey = KaTeXRendererCache.cacheKey(latex, displayMode, false)
    var rendererEpoch by remember(cacheKey) { mutableStateOf(0) }
    Box(modifier = Modifier.widthIn(min = 20.dp)) {
        key(cacheKey, rendererEpoch) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        if (dead) rendererEpoch += 1
                    }
                },
            )
        }
    }
}
"""

W7_BAD_KATEX = W7_GOOD_KATEX.replace("key(cacheKey, rendererEpoch) {", "key(rendererEpoch) {")

W7_KATEX_NO_EPOCH = W7_GOOD_KATEX.replace("key(cacheKey, rendererEpoch) {", "key(cacheKey) {")


# ---- batch7-t12 witnesses: 值被架空 / 解析器死亡 / 死分支 ----------------
# 06-1: 反向映射的产物被恒拒过滤器架空，`?: url` 的回退臂永远是答案
W1_TAKEIF_HOLDER = GOOD_HOLDER.replace(
    "currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url",
    "currentUrl = fromAssetLoaderUrl(url, assetRootDir)?.takeIf { false } ?: url")

# 06-2: 映射结果算出来了，加载点却传裸 currentUrl
W2_RAW_URL_HOLDER = GOOD_HOLDER.replace(
    "        webView.loadUrl(loadUrl)\n        webView.post",
    "        webView.loadUrl(currentUrl)\n        webView.post")

# 06-3: 视口策略字段在 with(receiver) 作用域里裸写成常量（无前导点号）
DESKTOP_WITH_SCOPE_HOLDER = GOOD_HOLDER + """
class DesktopHolder {
    fun toggleDesktopMode() {
        webView.settings.userAgentString = desktopUserAgent
        if (desktopMode) {
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        } else {
            val mobile = mobileViewportSettings()
            with(webView.settings) {
                useWideViewPort = 1 == 2
                loadWithOverviewMode = 1 == 2
            }
            webView.setInitialScale(mobile.initialScale)
        }
    }
}
internal fun mobileViewportSettings(): MobileViewportSettings =
    MobileViewportSettings(useWideViewPort = true, loadWithOverviewMode = true, initialScale = 0)
"""

# 06-4: 外部打开调用被搬进恒死分支
SHEET_DEAD_CALL = GOOD_SHEET.replace(
    "onOpenExternal = { openExternalFromSheet(context, holder.currentUrl) }",
    "onOpenExternal = { if (false) openExternalFromSheet(context, holder.currentUrl) }")

# 06-5: window 仍是链首接收者，但被恒拒过滤器过滤成 null
GALLERY_TAKEIF_REJECT = GALLERY_GOOD.replace(
    "val controller = window?.let {",
    "val controller = window?.takeIf { false }?.let {")

# 06-6: key(...) 参数文本里留着 holder，值恒为 0
W7_LET_KEY_SHEET = W7_GOOD_SHEET.replace("key(holder) {", "key(holder.let { 0 }) {")


def selftest():
    failures = []

    def expect(cond, what):
        if not cond:
            failures.append(what)

    # good fixtures: everything green
    good = analyse(GOOD_HOLDER, GOOD_SHEET)
    expect(len(good) == 11, "good fixture should produce 11 criteria, got %d" % len(good))
    for crit, ok, detail, line in good:
        expect(ok, "good fixture must pass %s (%s)" % (crit, detail))

    # bad fixture 1: direct-assign revert (the bug) — W1 must be RED, rest OK
    bad1 = analyse(BAD_HOLDER, GOOD_SHEET)
    d1 = dict((c, ok) for c, ok, _, _ in bad1)
    for c, ok in d1.items():
        if c.startswith("W1"):
            expect(not ok, "bad1 %s must be RED" % c)
        else:
            expect(ok, "bad1 %s must stay OK" % c)

    # bad fixture 2: legacy inline derivation + no mapper call — W1 + W3d red
    bad2 = analyse(LEGACY_HOLDER, GOOD_SHEET)
    d2 = dict((c, ok) for c, ok, _, _ in bad2)
    expect(not d2["W1 onPageStarted publishes via fromAssetLoaderUrl(url, assetRootDir)"],
           "bad2 W1 must be RED")
    expect(d2["W3c origin host constant declared exactly once"],
           "bad2 W3c must stay OK (const declared once in this snippet)")
    expect(not d2["W3d legacy inline file.name derivation removed"],
           "bad2 W3d must be RED (file.name present)")

    # bad fixture 3: downstream rewritten — W4 must be RED, holder side OK
    bad3 = analyse(GOOD_HOLDER, BAD_SHEET)
    d3 = dict((c, ok) for c, ok, _, _ in bad3)
    for c, ok in d3.items():
        if c.startswith("W4"):
            expect(not ok, "bad3 %s must be RED" % c)
        else:
            expect(ok, "bad3 %s must stay OK" % c)

    # ---- dv14-B1: W5 fixtures ----
    # good: mobile branch keeps the viewport on via the single-sourced policy
    dgood = analyse(DESKTOP_GOOD_HOLDER, GOOD_SHEET)
    for c, ok, detail, _ in dgood:
        if c.startswith("W5"):
            expect(ok, "desktop good fixture must pass %s (%s)" % (c, detail))

    # bad: literal false revert in the else branch — W5a RED; W5b/W5c stay OK
    # (W5d is allowed RED: this fixture also drops the policy consumption,
    # which W5d legitimately catches on its own)
    dfalse = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_FALSE_HOLDER, GOOD_SHEET))
    for c, ok in dfalse.items():
        if c == "W5a toggleDesktopMode never turns the mobile viewport off":
            expect(not ok, "desktop false fixture: %s must be RED" % c)
        elif c.startswith("W5") and "W5d" not in c and not c.startswith("W5f"):
            expect(ok, "desktop false fixture: %s must stay OK" % c)

    # bad: policy values flipped to false — W5c RED, wiring still OK
    dflip = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_POLICY_FLIPPED_HOLDER, GOOD_SHEET))
    for c, ok in dflip.items():
        if c == "W5c policy stays pinned to useWideViewPort/overview = true":
            expect(not ok, "policy flipped fixture: %s must be RED" % c)
        elif c.startswith("W5"):
            expect(ok, "policy flipped fixture: %s must stay OK" % c)

    # bad: restore deleted from the else branch — W5d RED
    dnorest = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_NO_RESTORE_HOLDER, GOOD_SHEET))
    for c, ok in dnorest.items():
        if c == "W5d mobile else branch consumes mobileViewportSettings()":
            expect(not ok, "no-restore fixture: %s must be RED" % c)
        elif c.startswith("W5"):
            expect(ok, "no-restore fixture: %s must stay OK" % c)

    # plain holder (no toggleDesktopMode) must produce NO W5 criteria
    expect(not any(c.startswith("W5") for c, _, _, _ in analyse(GOOD_HOLDER, GOOD_SHEET)),
           "holder without toggleDesktopMode must not emit W5 criteria")

    # ---- r3 round-3b witness arms (01-1 / 02-1 / 02-2 / 02-3) -----------
    dclean = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_UA_CLEAN_HOLDER, GOOD_SHEET))
    for c, ok in dclean.items():
        if c.startswith("W5"):
            expect(ok, "ua-clean fixture must pass %s" % c)

    drevert = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_UA_REVERT_HOLDER, GOOD_SHEET))
    reds = sorted(c for c, ok in drevert.items() if not ok)
    expect(reds == ["W5g mobile branch switches the UA back off the desktop value",
                    "W5h mobile branch restores the policy's initial scale"],
           "r3-02-2 ua-revert: exactly W5g+W5h must be RED, got %s" % reds)

    d61 = dict((c, ok) for c, ok, _, _ in analyse(ROOT_UNSTRIPPED_HOLDER, GOOD_SHEET))
    reds = sorted(c for c, ok in d61.items() if not ok)
    expect(reds == ["W8 asset root is a real directory derived from the file:// URL"],
           "r3-01-1 root-unstripped: exactly W8 must be RED, got %s" % reds)

    d64 = dict((c, ok) for c, ok, _, _ in analyse(NAME_CONSTANT_HOLDER, GOOD_SHEET))
    reds = sorted(c for c, ok in d64.items() if not ok)
    expect(reds == ["W3f toAssetLoaderUrl encodes the file URL's own name segment"],
           "r3-02-1 name-constant: exactly W3f must be RED, got %s" % reds)

    ok_fs, d_fs = w4c_fullscreen_external_call(code_of(W4_FULLSCREEN_GOOD))
    expect(ok_fs, "r3-02-3 fullscreen-currentUrl must pass W4c (%s)" % d_fs)
    ok_fs, d_fs = w4c_fullscreen_external_call(code_of(W4_FULLSCREEN_BAD))
    expect(not ok_fs and "webView.url" in d_fs,
           "r3-02-3 fullscreen-webView.url must be RED on W4c (%s)" % d_fs)

    # bad: policy consumption negated — only W5e RED (the policy itself is
    # still correct, so W5a/W5c/W5d must stay green: this is the arm that
    # exists precisely because nothing else catches a polarity negation)
    dneg = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_NEGATED_HOLDER, GOOD_SHEET))
    for c, ok in dneg.items():
        if c == "W5e toggleDesktopMode assigns the policy without negation":
            expect(not ok, "negated fixture: %s must be RED" % c)
        elif c.startswith("W5"):
            expect(ok, "negated fixture: %s must stay OK" % c)

    # ---- dv14-B1: W6 fixtures ----
    dgal = analyse(GOOD_HOLDER, GOOD_SHEET, GALLERY_GOOD)
    w6 = [(c, ok) for c, ok, _, _ in dgal if c.startswith("W6")]
    expect(len(w6) == 5, "gallery fixture should emit 5 W6 criteria, got %d" % len(w6))
    for c, ok in w6:
        expect(ok, "gallery good fixture must pass %s" % c)

    dwalk = dict((c, ok) for c, ok, _, _ in analyse(GOOD_HOLDER, GOOD_SHEET, GALLERY_WALKER_REVERT))
    for c, ok in dwalk.items():
        if c in ("W6a gallery outer effect takes the HOST Activity window",
                 "W6b outer effect must not call findDialogWindow(view)",
                 "W6e gallery outer effect builds the controller from that window"):
            expect(not ok, "walker revert fixture: %s must be RED" % c)
        elif c.startswith("W6"):
            expect(ok, "walker revert fixture: %s must stay OK" % c)

    dflags = dict((c, ok) for c, ok, _, _ in analyse(GOOD_HOLDER, GOOD_SHEET, GALLERY_FLAGS_MISSING))
    for c, ok in dflags.items():
        if c in ("W6c inner dialog effect applies galleryDialogWindowFlags()",
                 "W6d galleryDialogWindowFlags defined once with both flags"):
            expect(not ok, "flags missing fixture: %s must be RED" % c)
        elif c.startswith("W6"):
            expect(ok, "flags missing fixture: %s must stay OK" % c)

    # ---- dv17-3: W7 fixtures (AndroidView data-source keys) ----
    def w7(sheet_fx, preview_fx, katex_fx, fullscreen_fx):
        return dict((c, ok) for c, ok, _, _ in analyse(
            GOOD_HOLDER, sheet_fx, GALLERY_GOOD,
            filepreview_code=preview_fx, katex_code=katex_fx,
            fullscreen_code=fullscreen_fx))

    good7 = w7(W7_GOOD_SHEET, W7_GOOD_PREVIEW, W7_GOOD_KATEX, W7_GOOD_FULLSCREEN)
    w7_names = [c for c in good7 if c.startswith("W7")]
    expect(len(w7_names) == 4,
           "W7 fixture set should emit 4 criteria, got %d" % len(w7_names))
    for c in w7_names:
        expect(good7[c], "W7 good fixture must pass %s (%s)" % (c, good7[c] and "" or ""))

    # a holder without both extra code params must not emit W7 at all
    expect(not any(c.startswith("W7") for c, _, _, _ in analyse(GOOD_HOLDER, GOOD_SHEET)),
           "analyse without the preview files must not emit W7 criteria")

    # each revert reds exactly its OWN criterion (and disturbs nothing else)
    w7_cases = [
        (W7_BAD_SHEET, W7_GOOD_PREVIEW, W7_GOOD_KATEX, W7_GOOD_FULLSCREEN, "W7a"),
        (W7_GOOD_SHEET, W7_GOOD_PREVIEW, W7_GOOD_KATEX, W7_BAD_FULLSCREEN, "W7b"),
        (W7_GOOD_SHEET, W7_BAD_PREVIEW, W7_GOOD_KATEX, W7_GOOD_FULLSCREEN, "W7c"),
        (W7_GOOD_SHEET, W7_GOOD_PREVIEW, W7_BAD_KATEX, W7_GOOD_FULLSCREEN, "W7d"),
        (W7_GOOD_SHEET, W7_PREVIEW_NO_EPOCH, W7_GOOD_KATEX, W7_GOOD_FULLSCREEN, "W7c"),
        (W7_GOOD_SHEET, W7_GOOD_PREVIEW, W7_KATEX_NO_EPOCH, W7_GOOD_FULLSCREEN, "W7d"),
    ]
    for sheet_fx, preview_fx, katex_fx, fullscreen_fx, red_label in w7_cases:
        d = w7(sheet_fx, preview_fx, katex_fx, fullscreen_fx)
        reds = sorted(c for c in d if c.startswith("W7") and not d[c])
        expect(len(reds) == 1 and reds[0].startswith(red_label),
               "W7 revert fixture must red exactly %s, got %s" % (red_label, reds))
        others = sorted(c for c, ok in d.items() if not c.startswith("W7") and not ok)
        expect(not others, "W7 fixture must not disturb other criteria: %s" % others)

    # ---- wide-1024 witnesses ---------------------------------------------
    # Each fixture is the auditor's patch verbatim: the gate used to stay green
    # on it. It must now red EXACTLY the criterion that owns it.
    W1_ALSO_HOLDER = GOOD_HOLDER.replace(
        "currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url",
        "currentUrl = url.also { fromAssetLoaderUrl(url, assetRootDir) }")
    d = dict((c, ok) for c, ok, _, _ in analyse(W1_ALSO_HOLDER, GOOD_SHEET))
    for c, ok in d.items():
        if c.startswith("W1"):
            expect(not ok, "wide-1024 W1 witness: %s must be RED" % c)
        else:
            expect(ok, "wide-1024 W1 witness must not disturb %s" % c)

    # equivalent spelling that must stay GREEN: parenthesised elvis
    paren = GOOD_HOLDER.replace(
        "currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url",
        "currentUrl = (fromAssetLoaderUrl(url, assetRootDir) ?: url)")
    d = dict((c, ok) for c, ok, _, _ in analyse(paren, GOOD_SHEET))
    for c, ok in d.items():
        if c.startswith("W1"):
            expect(ok, "parenthesised elvis must stay GREEN on %s" % c)

    DESKTOP_DERIVED_HOLDER = DESKTOP_GOOD_HOLDER.replace(
        "webView.settings.useWideViewPort = mobile.useWideViewPort",
        "webView.settings.useWideViewPort = mobile.initialScale > 0").replace(
        "webView.settings.loadWithOverviewMode = mobile.loadWithOverviewMode",
        "webView.settings.loadWithOverviewMode = mobile.initialScale > 0")
    d = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_DERIVED_HOLDER, GOOD_SHEET))
    for c, ok in d.items():
        if c.startswith("W5f"):
            expect(not ok, "wide-1024 W5 witness: %s must be RED" % c)
        elif c.startswith("W5"):
            expect(ok, "wide-1024 W5 witness must not disturb %s" % c)

    GALLERY_NULL_CONTROLLER = GALLERY_GOOD.replace(
        "val controller = window?.let { WindowInsetsControllerCompat(it, view) }",
        "val controller = (null as android.view.Window?)?.let { "
        "WindowInsetsControllerCompat(it, view) }")
    d = dict((c, ok) for c, ok, _, _ in analyse(GOOD_HOLDER, GOOD_SHEET,
                                               GALLERY_NULL_CONTROLLER))
    for c, ok in d.items():
        if c == "W6e gallery outer effect builds the controller from that window":
            expect(not ok, "wide-1024 W6 witness: %s must be RED" % c)
        elif c.startswith("W6"):
            expect(ok, "wide-1024 W6 witness must not disturb %s" % c)

    # ---- batch7-t12 witnesses ------------------------------------------
    d = dict((c, ok) for c, ok, _, _ in analyse(W1_TAKEIF_HOLDER, GOOD_SHEET))
    reds = sorted(c for c in d if c.startswith("W1") and not d[c])
    expect(len(reds) == 1, "t12-06-1: exactly W1 must be RED, got %s" % reds)
    expect(not [c for c, ok in d.items() if not c.startswith("W1") and not ok],
           "t12-06-1 must not disturb other criteria")

    d = dict((c, ok) for c, ok, _, _ in analyse(W2_RAW_URL_HOLDER, GOOD_SHEET))
    reds = sorted(c for c in d if c.startswith("W2") and not d[c])
    expect(reds == ["W2 load path uses toAssetLoaderUrl(currentUrl) ?: currentUrl"],
           "t12-06-2: exactly W2 must be RED, got %s" % reds)
    expect(not [c for c, ok in d.items() if not c.startswith("W2") and not ok],
           "t12-06-2 must not disturb other criteria")

    d = dict((c, ok) for c, ok, _, _ in analyse(DESKTOP_WITH_SCOPE_HOLDER, GOOD_SHEET))
    reds = sorted(c for c in d if c.startswith("W5") and not d[c])
    expect("W5a toggleDesktopMode never turns the mobile viewport off" in reds,
           "t12-06-3: W5a must be RED (with(receiver) scope hides the write), got %s" % reds)
    expect(any(c.startswith("W5f") for c in reds),
           "t12-06-3: a W5f arm must be RED, got %s" % reds)
    expect(not [c for c, ok in d.items() if not c.startswith("W5") and not ok],
           "t12-06-3 must not disturb non-W5 criteria")

    d = dict((c, ok) for c, ok, _, _ in analyse(GOOD_HOLDER, SHEET_DEAD_CALL))
    reds = sorted(c for c in d if not d[c])
    expect(reds == ["W4a openExternalFromSheet still reads holder.currentUrl"],
           "t12-06-4: exactly W4a must be RED, got %s" % reds)

    d = dict((c, ok) for c, ok, _, _ in analyse(GOOD_HOLDER, GOOD_SHEET,
                                               GALLERY_TAKEIF_REJECT))
    reds = sorted(c for c in d if c.startswith("W6") and not d[c])
    expect(reds == ["W6e gallery outer effect builds the controller from that window"],
           "t12-06-5: exactly W6e must be RED, got %s" % reds)
    expect(not [c for c, ok in d.items() if not c.startswith("W6") and not ok],
           "t12-06-5 must not disturb non-W6 criteria")

    d = w7(W7_LET_KEY_SHEET, W7_GOOD_PREVIEW, W7_GOOD_KATEX, W7_GOOD_FULLSCREEN)
    reds = sorted(c for c in d if c.startswith("W7") and not d[c])
    expect(len(reds) == 1 and reds[0].startswith("W7a"),
           "t12-06-6: exactly W7a must be RED, got %s" % reds)

    # 对照臂：`.let { it }` 这种值不变的写法必须仍然 GREEN（不误伤）
    id_key_sheet = W7_GOOD_SHEET.replace("key(holder) {", "key(holder.let { it }) {")
    d = w7(id_key_sheet, W7_GOOD_PREVIEW, W7_GOOD_KATEX, W7_GOOD_FULLSCREEN)
    expect(all(d[c] for c in d if c.startswith("W7")),
           "t12-06-6 control: key(holder.let { it }) must stay GREEN, reds=%s"
           % sorted(c for c in d if c.startswith("W7") and not d[c]))

    # 对照臂：合法的恒真过滤器（`takeIf { it.isNotBlank() }`）必须仍然 GREEN
    blank_filter = GOOD_HOLDER.replace(
        "currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url",
        "currentUrl = fromAssetLoaderUrl(url, assetRootDir)?.takeIf { it.isNotBlank() } ?: url")
    d = dict((c, ok) for c, ok, _, _ in analyse(blank_filter, GOOD_SHEET))
    expect(d["W1 onPageStarted publishes via fromAssetLoaderUrl(url, assetRootDir)"],
           "t12-06-1 control: a real predicate must stay GREEN")

    if failures:
        print("SELFTEST FAIL:")
        for f in failures:
            print("  - %s" % f)
        return 1
    print("selftest: good=GREEN, direct-assign revert=RED, legacy inline=RED, "
          "downstream rewrite=RED, desktop viewport false revert=RED, "
          "policy flip=RED, restore delete=RED, gallery walker revert=RED, "
          "gallery flags missing=RED, androidview key removed=RED (4 sites), "
          "androidview rendererEpoch dropped=RED, mapper discarded via .also=RED, "
          "viewport derived from initialScale=RED, controller built from null window=RED; "
          "batch7-t12: 6 witnesses RED + 2 controls GREEN")
    print("WEBVIEW_HOLDER_URL_SOURCE_SELFTEST_ARMS=%d" % 57)
    return 0


def main(argv):
    if "--selftest" in argv:
        rc = selftest()
        if rc == 0:
            print("WEBVIEW_HOLDER_URL_SOURCE_SELFTEST=PASS")
        return rc

    args = [a for a in argv[1:] if a != "--selftest"]
    root = args[0] if args else "."
    paths = []
    for rel in (HOLDER_REL, SHEET_REL, GALLERY_REL, FULLSCREEN_REL,
                FILEPREVIEW_REL, KATEX_REL):
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            print("FAIL required file missing: %s" % rel)
            return 1
        paths.append(p)

    holder_code = open(paths[0], encoding="utf-8").read()
    sheet_code = open(paths[1], encoding="utf-8").read()
    gallery_code = open(paths[2], encoding="utf-8").read()
    fullscreen_code = open(paths[3], encoding="utf-8").read()
    filepreview_code = open(paths[4], encoding="utf-8").read()
    katex_code = open(paths[5], encoding="utf-8").read()
    results = analyse(holder_code, sheet_code, gallery_code,
                      filepreview_code=filepreview_code, katex_code=katex_code,
                      fullscreen_code=fullscreen_code)

    failed = [(c, d, l) for c, ok, d, l in results if not ok]
    for crit, ok, detail, line in results:
        if ok:
            print("  [OK] %s" % crit)
        else:
            print("  [FAIL] %s%s — %s" % (crit, " (line %s)" % line if line else "", detail))

    total = len(results)
    print("Summary: %d passed, %d failed" % (total - len(failed), len(failed)))
    if failed:
        print("webview holder url source gate: RED — the single-source contract is violated")
        return 1
    print("GATE=GREEN webview_holder_url_source")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
