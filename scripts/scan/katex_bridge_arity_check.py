#!/usr/bin/env python3
"""
KaTeX JS-bridge arity guard — the asset HTML and its Kotlin hosts must agree.

Bug class (audit3 BUG-05, live for ~5 weeks): `assets/katex/katex-render.html`
calls `AndroidBridge.onRendered(w, h, error, token)` — four arguments — while
`KaTeXView.kt` registered a THREE-argument `onRendered`. Chromium resolves
`@JavascriptInterface` members by name AND argument count, so the call never
reached the host: it died as "Method not found" inside the page, the callback
threw out of its `requestAnimationFrame`, `renderedBitmap` stayed null forever
and each uncached display formula kept its WebView alive. The sibling host
(`KatexWebViewPool`) had been updated in the same commit — one side of a
two-sided contract, updated once. The lesson ("JS bridge changes must be
verified on both sides") was written into the memory log and still did not
prevent this, because nothing mechanical looked at both files.

Criterion: for every `AndroidBridge.<method>(...)` call site in
`src/android/app/src/main/assets/**/*.html`, let N be its top-level argument
count. Every Kotlin registration of that interface name (via
`addJavascriptInterface(<obj>, "<name>")`) must declare `@JavascriptInterface
fun <method>(...)` with exactly N parameters. Files are checked individually —
a union across files would hide exactly the bug above (the pool's 4-parameter
declaration masked the view file's 3-parameter one).

[wide3-06] Three blind spots the first version had, and what replaced them:
  * SCOPE (C2-scope): declarations were collected from the WHOLE .kt file, so
    a 4-arg `@JavascriptInterface fun onRendered` inside an unused nested class
    ("DeadBridge") satisfied the contract while the object actually passed to
    `addJavascriptInterface` declared only 3 args. The surface is now derived
    PER REGISTRATION, from the object expression itself (inline `object { }`,
    `val x = object { }`, or a `class X` constructor reference), and only
    MEMBER-level declarations count — a nested class cannot stand in.
  * DATAFLOW (C2-dead): arity was the only thing judged, so a method whose
    body starts `if (true) return` still "matched" while rendering never ran.
    A statically-unreachable body is now a violation.
  * PARSER (C2-parser): `strip_comments` was regex-based and NOT string-aware,
    so a `"/*"` string literal opened a "comment" that swallowed the real call
    site; the second call site in the page's `catch` block then kept the
    `calls_found > 0` rot guard satisfied. Comment stripping is now a
    quote-aware scanner, and an unparsable call site / registration fails
    CLOSED instead of being skipped.

Notes / limits:
  * Argument splitting tracks quotes and `()[]{}` nesting, not `<>`. Kotlin
    generics with a top-level comma would be miscounted — bridge signatures
    are primitives, keep them that way.
  * No escape hatch: the contract is purely mechanical (name + arity), and the
    rot guard below fails loudly if the asset tree or the registrations move.

Usage: python3 scripts/scan/katex_bridge_arity_check.py [repo_root]
       python3 scripts/scan/katex_bridge_arity_check.py --selftest
Exit:  0 = every call site has a matching declaration, 1 = mismatch (or rot)
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as kotlin_analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool, if_blocks, match_bracket, split_top, stmt_spans, when_branches,
)

IFACE_DEFAULT = "AndroidBridge"
ANNOTATION = "@JavascriptInterface"

# [wide4-02] JS/HTML comment stripping must know REGEX LITERALS.
# Witness (parser-death/4): `var _re = /\//;` — the last two characters of the
# regex literal are `//`, which the old scanner read as a line comment and ate
# the rest of the line, including the 2-arg `AndroidBridge.onRendered(...)` call
# the gate exists to check. The page's `catch` block kept `calls_found > 0`, so
# the rot guard stayed quiet and the gate went green on a bridge that no longer
# resolved arity.
_REGEX_PREV = set("=(,:[!&|?;{}%-*~^")
_REGEX_WORDS = ("return", "typeof", "case", "in", "of", "new", "delete",
                "void", "instanceof", "do", "else", "yield", "await")


def _blank_span(out, start, end):
    for k in range(start, end):
        if out[k] != "\n":
            out[k] = " "


def strip_js_comments(text):
    """Blank JS/HTML comments — string/template/regex-literal aware.

    Returns the stripped text or raises `ValueError` on an unterminated
    construct (the caller reports ROT: a page this scanner cannot tokenise must
    never be judged — silently returning the raw text is how parser-death
    witnesses pass).
    """
    out = list(text)
    i, n = 0, len(text)
    prev = ""          # last significant character outside comments
    word = ""          # last identifier word outside comments (regex context)

    def at_expr_start():
        if prev == "" or prev in _REGEX_PREV:
            return True
        return word in _REGEX_WORDS

    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            _blank_span(out, i, j)
            i = j
            continue
        if c == "/" and text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                raise ValueError(f"unterminated block comment at offset {i}")
            _blank_span(out, i, j + 2)
            i = j + 2
            continue
        if text.startswith("<!--", i):
            j = text.find("-->", i + 3)
            if j < 0:
                raise ValueError(f"unterminated HTML comment at offset {i}")
            _blank_span(out, i, j + 3)
            i = j + 3
            continue
        if c in "\"'`":
            j = i + 1
            while j < n:
                ch = text[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == c:
                    j += 1
                    break
                if c != "`" and ch == "\n":
                    raise ValueError(f"unterminated string literal at offset {i}")
                j += 1
            else:
                raise ValueError(f"unterminated string literal at offset {i}")
            prev, word = c, ""
            i = j
            continue
        if c == "/" and at_expr_start():
            # Try a regex literal; if no closing `/` on this line, fall back to
            # treating it as division (and keep scanning normally).
            j = i + 1
            in_class = False
            closed = -1
            while j < n:
                ch = text[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == "\n":
                    break
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    closed = j
                    break
                j += 1
            if closed > 0:
                prev, word = "/", ""
                i = closed + 1
                # flags
                while i < n and text[i].isalpha():
                    i += 1
                continue
        if c.isalnum() or c in "_$":
            j = i
            while j < n and (text[j].isalnum() or text[j] in "_$"):
                j += 1
            word = text[i:j]
            prev = text[j - 1]
            i = j
            continue
        if not c.isspace():
            prev, word = c, ""
        i += 1
    return "".join(out)


def kotlin_views(text):
    """(comments_view, code_view) from the shared lexer, or (None, None, reason).

    [wide4-02] The gate used to strip Kotlin comments with its own quote-aware
    scanner. That scanner did not know nested block comments, `\"\"\"` raw
    strings, `${...}` templates or backtick identifiers, so any of them could
    desynchronise the quote state and hide a declaration. The shared lexer
    (`_kotlin_lexer`) is the one implementation for the whole wave.
    """
    ok, reason, views = kotlin_analyse(text)
    if not ok:
        return None, None, reason
    return views["comments"], views["code"], None


def strip_comments(text):
    """Legacy name: Kotlin comment stripping is now the shared lexer.

    Kept as a thin alias so the module's public surface does not change for
    callers that only need the comment-masked view.
    """
    comments, _code, reason = kotlin_views(text)
    if comments is None:
        raise ValueError(reason)
    return comments


def match_paren(text, open_idx):
    """Index of the ')' matching the '(' at open_idx, or -1."""
    depth, quote, i = 0, None, open_idx
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'`":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def match_brace(text, open_idx):
    """Index just past the '}' matching '{' at open_idx, or -1."""
    if open_idx < 0 or open_idx >= len(text) or text[open_idx] != "{":
        return -1
    depth, quote, i = 0, None, open_idx
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'`":
            quote = ch
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def split_args(text):
    """Top-level comma split; returns [] for an empty list."""
    parts, depth, buf, quote, i = [], 0, "", None, 0
    while i < len(text):
        ch = text[i]
        if quote:
            buf += ch
            if ch == "\\" and i + 1 < len(text):
                buf += text[i + 1]
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'`":
            quote = ch
            buf += ch
        elif ch in "([{":
            depth += 1
            buf += ch
        elif ch in ")]}":
            depth -= 1
            buf += ch
        elif ch == "," and depth == 0:
            parts.append(buf)
            buf = ""
        else:
            buf += ch
        i += 1
    parts.append(buf)
    parts = [p.strip() for p in parts]
    return [] if parts == [""] else parts


def _depth_map(body):
    """Brace depth at every offset of [body] (quote-aware)."""
    depth, quote = 0, None
    out = [0] * len(body)
    i = 0
    while i < len(body):
        ch = body[i]
        if quote:
            out[i] = depth
            if ch == "\\" and i + 1 < len(body):
                out[i + 1] = depth
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'`":
            quote = ch
            out[i] = depth
            i += 1
            continue
        if ch == "{":
            depth += 1
            out[i] = depth
        elif ch == "}":
            out[i] = depth
            depth -= 1
        else:
            out[i] = depth
        i += 1
    return out


def _member_body(body, close_paren):
    """Source of the member's body starting at its parameter list's `)`.

    Brace body → the braces; expression body (`= …`) → the rest of the line
    (enough to judge "does it do anything"). Returns "" when it cannot be
    delimited (the caller treats that as unknown, never as green).
    """
    i = close_paren + 1
    while i < len(body):
        c = body[i]
        if c == "{":
            end = match_brace(body, i)
            return body[i:end] if end > 0 else ""
        if c == "=" and body[i + 1:i + 2] != "=":
            eol = body.find("\n", i)
            return body[i:len(body) if eol < 0 else eol]
        if c == "}":
            return ""
        if c == "\n":
            j = i + 1
            while j < len(body) and body[j] in " \t":
                j += 1
            if re.match(
                r"(?:@|fun|val|var|class|object|private|internal|public|protected|override)\b",
                body[j:j + 14],
            ):
                return ""
        i += 1
    return ""


def member_declarations(obj_body):
    """Declarations that form ONE interface object's callable surface.

    Returns [(method, arity, body)]. Only MEMBER-level declarations count
    (brace depth 1 inside the object body): a declaration nested inside a
    member's own `class`/`object` body is not reachable through the JS name
    (witness C2-scope put a 4-arg `onRendered` in a dead `class DeadBridge`
    inside the real object).
    """
    if not obj_body:
        return []
    depths = _depth_map(obj_body)
    sig = re.compile(r"\bfun\s+(\w+)\s*\(")
    out = []
    for ann in re.finditer(re.escape(ANNOTATION) + r"\b", obj_body):
        if depths[ann.start()] != 1:
            continue
        m = sig.search(obj_body, ann.end(), min(len(obj_body), ann.end() + 400))
        if not m or depths[m.start()] != 1:
            continue
        end = match_paren(obj_body, m.end() - 1)
        if end < 0:
            continue
        out.append((m.group(1), len(split_args(obj_body[m.end():end])),
                    _member_body(obj_body, end)))
    return out


def _decl_spans(code):
    """[(name, depth, body_start, body_end)] for every named class/object.

    `depth` is the brace depth at the declaration keyword. Used to pick THE
    declaration a registration refers to: the old `re.search` took the first
    textual match, so a same-named class parked inside an unrelated holder
    (`class UnusedHolder { class JsBridge { … 4 args … } }`) satisfied the
    arity contract while the class actually instantiated at the registration
    site declared three parameters (witness family-hole/3).
    """
    depths = _depth_map(code)
    out = []
    for m in re.finditer(r"\b(?:class|object)\s+([A-Za-z_]\w*)", code):
        b = code.find("{", m.end())
        if b < 0:
            continue
        end = match_brace(code, b)
        if end < 0:
            continue
        out.append((m.group(1), depths[m.start()], b, end))
    return out


def _enclosing_decl_depth(code, pos):
    """Brace depth of the innermost named class/object containing `pos` (-1)."""
    best = -1
    for _name, depth, b_start, b_end in _decl_spans(code):
        if b_start <= pos < b_end and depth > best:
            best = depth
    return best


def _lambda_body(lit):
    """Body of a `{ params -> body }` lambda literal (params stripped)."""
    lit = lit.strip()
    if not lit.startswith("{"):
        return None
    end = match_brace(lit, 0)
    if end < 0:
        return None
    inner = lit[1:end - 1]
    arrow = -1
    depth = 0
    k = 0
    while k < len(inner):
        ch = inner[k]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and inner.startswith("->", k):
            arrow = k
            break
        k += 1
    return inner[arrow + 2:] if arrow >= 0 else inner


def _call_lambdas(arg):
    """Lambda literals passed to a constructor/function call expression.

    `JsBridge { … }`, `JsBridge(cb) { … }` and `JsBridge({ … })` all yield the
    lambda body. Returns [] when the argument is not a call shape.
    """
    m = re.match(r"[A-Za-z_]\w*", arg)
    if not m:
        return []
    i = m.end()
    while i < len(arg) and arg[i] in " \t":
        i += 1
    lits = []
    if i < len(arg) and arg[i] == "(":
        end = match_paren(arg, i)
        if end < 0:
            return []
        base = i + 1
        for a, b in split_top(arg[base:end - 1]):
            piece = arg[base + a:base + b].strip()
            if piece.startswith("{"):
                lits.append(piece)
        i = end
    while i < len(arg) and arg[i] in " \t\r\n":
        i += 1
    if i < len(arg) and arg[i] == "{":
        lits.append(arg[i:])
    return lits


def _resolve_object(code, arg, pos):
    """(obj_body, callback_bodies, reason) for a registration's first argument.

    Accepted shapes: an inline `object { … }` / `object : X() { … }`, an
    identifier bound to such an object (`val jsBridge = object { … }`), or a
    type/constructor reference (`JsBridge { … }`, `JsBridge(cb)`). Anything
    else returns None with a reason → the caller fails closed (a registration
    it cannot resolve must never silently drop out of the host set).

    [wide4-02] Two blind spots closed here:
      * SHADOWING — the type is looked up among declarations that can actually
        be the one instantiated (top level, or a sibling member of the class
        that encloses the registration). Two candidates at that level are
        ambiguous and fail closed instead of silently picking the first.
      * CALLBACK — `JsBridge { _, _, _, _ -> }` keeps the class body intact
        (it still calls `onResult(…)`) and moves the behaviour into an empty
        constructor lambda, so the member body alone was never the whole
        surface. The lambdas passed at the registration site are returned and
        judged by the caller.
    """
    arg = arg.strip()
    if not arg:
        return None, [], "empty registration argument"
    if re.match(r"object\b", arg):
        b = arg.find("{")
        if b < 0:
            return None, [], "inline `object` without a body"
        end = match_brace(arg, b)
        if end < 0:
            return None, [], "inline `object` body is unbalanced"
        return arg[b:end], [], None

    if re.fullmatch(r"[A-Za-z_]\w*", arg):
        best = None
        pat = re.compile(r"\b(?:val|var)\s+" + re.escape(arg)
                         + r"\s*(?::[^=\n]*)?=\s*object\b")
        for m in pat.finditer(code):
            if m.start() < pos:
                best = m
        if best is None:
            return None, [], f"`{arg}` is not bound to an `object {{ }}` before the registration"
        b = code.find("{", best.end())
        if b < 0:
            return None, [], f"`{arg}` binding has no object body"
        end = match_brace(code, b)
        if end < 0:
            return None, [], f"`{arg}` object body is unbalanced"
        return code[b:end], [], None

    m = re.match(r"([A-Za-z_]\w*)\s*[({]", arg)
    if m:
        type_name = m.group(1)
        cands = [d for d in _decl_spans(code) if d[0] == type_name]
        env = _enclosing_decl_depth(code, pos)
        allowed = {0, env + 1}
        pick = [d for d in cands if d[1] in allowed]
        if len(pick) != 1:
            return None, [], (
                "cannot pick THE declaration of `%s` — %d candidate(s) at the "
                "registration's own scope level, %d in the file"
                % (type_name, len(pick), len(cands)))
        b_start, b_end = pick[0][2], pick[0][3]
        return code[b_start:b_end], _call_lambdas(arg), None
    return None, [], "registration argument is not an object/identifier/constructor"


def registrations(code, comments):
    """[dict(name, arg, pos, reason)] per `addJavascriptInterface(obj, "Name")`.

    `code` is the lexer's code view (strings masked, positions identical to the
    source); `comments` is its comment-masked view, used to read the interface
    NAME literal out of the same offsets. Fail-closed contract: when the call
    is present but the interface name is not a string literal, or the argument
    list cannot be delimited, `name` is None with a reason and the caller must
    report ROT.
    """
    out = []
    for m in re.finditer(r"addJavascriptInterface\s*\(", code):
        open_idx = m.end() - 1
        end = match_paren(code, open_idx)
        if end < 0:
            out.append({"name": None, "arg": None, "pos": m.start(),
                        "reason": "unbalanced addJavascriptInterface( … )"})
            continue
        base = open_idx + 1
        spans = split_top(code[base:end])
        if len(spans) < 2:
            out.append({"name": None, "arg": None, "pos": m.start(),
                        "reason": "addJavascriptInterface takes two arguments"})
            continue
        arg_text = code[base + spans[0][0]:base + spans[0][1]]
        iface_src = comments[base + spans[-1][0]:base + spans[-1][1]]
        lits = re.findall(r"\"([^\"]*)\"", iface_src)
        if not lits:
            out.append({"name": None, "arg": arg_text, "pos": m.start(),
                        "reason": "interface name is not a string literal"})
            continue
        out.append({"name": lits[-1], "arg": arg_text, "pos": m.start(),
                    "reason": None})
    return out


def js_call_sites(html_text, names):
    """[(iface, method, argc)] plus the call sites that could not be parsed."""
    out, unparsed = [], []
    if not names:
        return out, unparsed
    pattern = r"\b(" + "|".join(re.escape(n) for n in names) + r")\.(\w+)\s*\("
    for m in re.finditer(pattern, html_text):
        end = match_paren(html_text, m.end() - 1)
        if end < 0:
            unparsed.append(f"{m.group(1)}.{m.group(2)}")
            continue
        out.append((m.group(1), m.group(2), len(split_args(html_text[m.end():end]))))
    return out, unparsed


def _body_is_dead(body):
    """Statically-unreachable body: execution cannot reach the end of it.

    [wide3-06 C2-dead] Arity agreement alone let `fun onRendered(…4 args…) { if
    (true) return }` pass while no formula was ever captured.

    [wide4-02] The old test was a two-entry spelling blacklist (`^{ return` and
    a literal `if (true)`), so `if (!false) return`, `if (1 == 1) return` and
    `when { true -> return }` — all the same unconditional early exit — kept
    the gate green. Conditions are now CONSTANT-FOLDED through
    `_kotlin_ast.const_bool`, which is the wave's single folding implementation.
    """
    if not body:
        return False
    if re.match(r"\{\s*(?:return|throw)\b", body):
        return True
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(body):
        if const_bool(cond) is True and re.search(r"\b(?:return|throw)\b",
                                                  body[t_start:t_end]):
            return True
    for _subject, cond, b_start, b_end in when_branches(body):
        if const_bool(cond) is True and re.search(r"\b(?:return|throw)\b",
                                                  body[b_start:b_end]):
            return True
    return False


def run(root):
    """(exit_code) — prints the table and every finding."""
    assets = os.path.join(root, "src/android/app/src/main/assets")
    kotlin = os.path.join(root, "src/android/app/src/main/java")
    if not os.path.isdir(assets) or not os.path.isdir(kotlin):
        print(f"❌ ROT: expected {assets} and {kotlin} to exist")
        return 1

    # hosts[rel][iface] = {method: {arities}}
    hosts = {}
    unparsed = []
    for dirpath, _, files in os.walk(kotlin):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8", errors="replace") as fh:
                raw = fh.read()
            rel = os.path.relpath(path, root)
            comments, code, reason = kotlin_views(raw)
            if code is None:
                # [wide4-02 parser-death] a file this guard cannot tokenise must
                # never be judged: the old hand-written stripper desynchronised
                # on raw strings / templates and silently hid declarations.
                unparsed.append(f"{rel} (cannot tokenise: {reason})")
                continue
            if "addJavascriptInterface" not in code:
                continue
            regs = registrations(code, comments)
            if not regs:
                unparsed.append(rel)
                continue
            per_name = {}
            bad = []
            for reg in regs:
                if reg["name"] is None:
                    bad.append(reg["reason"])
                    continue
                body, callbacks, why = _resolve_object(code, reg["arg"], reg["pos"])
                if body is None:
                    bad.append(why)
                    continue
                arities = {}
                for method, arity, mbody in member_declarations(body):
                    arities.setdefault(method, set()).add(arity)
                    if _body_is_dead(mbody):
                        per_name.setdefault("__dead__", []).append(
                            (method, arity,
                             "never runs its body (statically unreachable)"))
                for lit in callbacks:
                    lbody = _lambda_body(lit)
                    if lbody is None:
                        bad.append("registration passes a lambda this guard cannot parse")
                    elif not lbody.strip():
                        # [wide4-02 family-hole/2] `JsBridge { _, _, _, _ -> }`
                        # is the whole behaviour: the class body still calls
                        # `onResult(...)`, so judging the member alone says
                        # "alive" while every render waits out its timeout.
                        per_name.setdefault("__dead__", []).append(
                            ("<callback>", None,
                             "registration callback is an empty lambda — the "
                             "bridge call succeeds and does nothing"))
                    elif _body_is_dead("{" + lbody + "}"):
                        per_name.setdefault("__dead__", []).append(
                            ("<callback>", None,
                             "registration callback never runs its body "
                             "(statically unreachable)"))
                per_name[reg["name"]] = arities
            if bad:
                unparsed.append(f"{rel} ({'; '.join(bad)})")
                continue
            hosts[rel] = per_name
    if unparsed:
        # A host that cannot be parsed must fail LOUDLY. This is not a
        # hypothetical: it was found by the guard's own mutation test — a
        # mangled registration made this file drop out of `hosts`, the other
        # host still supplied the interface name, and the guard went green on
        # a tree whose KaTeXView declaration no longer matched the page. That
        # is precisely the stale-host shape the guard exists to catch, so a
        # silent skip is the one failure mode it must never have.
        c = "❌"
        print(f"{c} ROT: a registration could not be resolved to a declaration:")
        for rel in unparsed:
            print(f"   {rel}")
        print("   Keep the interface name a string literal in the registration, and pass")
        print("   an object/identifier this guard can resolve; if it must be computed,")
        print("   teach this guard how — do not let it skip the file.")
        return 1
    if not hosts:
        print("❌ ROT: no addJavascriptInterface registration found anywhere")
        return 1

    pages = []
    for dirpath, _, files in os.walk(assets):
        for name in files:
            if name.endswith((".html", ".htm")):
                pages.append(os.path.join(dirpath, name))

    all_names = {n for per_file in hosts.values() for n in per_file if n != "__dead__"}
    calls_found = 0
    violations = []
    for path in sorted(pages):
        rel = os.path.relpath(path, root)
        with open(path, encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
        try:
            text = strip_js_comments(raw)
        except ValueError as exc:
            # [wide4-02 parser-death/4] fail closed: a page whose lexer state
            # cannot be settled is exactly where a hidden call site lives.
            print(f"❌ ROT: cannot tokenise {rel}: {exc}")
            return 1
        sites, unparsed_sites = js_call_sites(text, all_names)
        if unparsed_sites:
            # [wide3-06 C2-parser] an unbalanced call site used to be skipped,
            # leaving the page's OTHER call site to satisfy the rot guard.
            print(f"❌ ROT: unparsable JS-bridge call site(s) in {rel}: "
                  f"{', '.join(unparsed_sites)}")
            return 1
        for iface, method, argc in sites:
            calls_found += 1
            for host_file, per_name in sorted(hosts.items()):
                if iface not in per_name:
                    continue
                arities = per_name[iface].get(method)
                state = "OK" if arities and argc in arities else "MISMATCH"
                print(
                    f"  [{state}] {rel} → {iface}.{method}({argc} args)"
                    f" vs {host_file}: {sorted(arities) if arities else 'no declaration'}"
                )
                if state == "MISMATCH":
                    violations.append((rel, iface, method, argc, host_file, arities))
        for host_file, per_name in sorted(hosts.items()):
            for method, arity, why in per_name.get("__dead__", []):
                violations.append((rel, ANNOTATION, method, arity, host_file, why))
    if calls_found == 0:
        print("❌ ROT: no JS-bridge call site found in assets (contract unchecked)")
        return 1

    if violations:
        print("")
        print(f"❌ KATEX BRIDGE ARITY: {len(violations)} mismatch(es)")
        for rel, iface, method, argc, host_file, arities in violations:
            if isinstance(arities, str):
                print(f"   {host_file}: @JavascriptInterface {method} {arities} — "
                      f"the bridge call succeeds and renders nothing")
                continue
            print(f"   {rel} calls {iface}.{method} with {argc} args, but {host_file} declares {sorted(arities) if arities else 'nothing'}")
        print("   Chromium resolves @JavascriptInterface by name AND arity: the call dies")
        print("   as \"Method not found\" inside the page and the host never runs.")
        return 1
    print(f"✅ KaTeX bridge arity: {calls_found} call site(s) matched in every host")
    return 0


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    return run(root)


# --- selftest: one negative arm per witness axis -----------------------------

_SELFTEST_SHEET = """
package com.rikkaminis.app.ui.markdown
class KaTeXView {
    private fun mount(wv: WebView) {
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onRendered(width: Int, height: Int, error: String) {
                onRendered(width, height, error, TOKEN)
            }

            @JavascriptInterface
            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                post { capture(width, height, token) }
            }
        }, "AndroidBridge")
    }
}
"""

_SELFTEST_PAGE = """<html><script>
function renderMath(latex, token) {
  try {
    var rect = el.getBoundingClientRect();
    AndroidBridge.onRendered(Math.ceil(rect.width), Math.ceil(rect.height), '', token);
  } catch (e) {
    AndroidBridge.onRendered(0, 0, e.message || 'render error', token);
  }
}
</script></html>
"""


_SELFTEST_POOL = """package com.rikkaminis.app.ui.chat
class Pool {
    fun mount(wv: WebView) {
        wv.addJavascriptInterface(JsBridge { w, h, err, callbackToken ->
            pending.complete(Triple(w, h, err))
        }, "AndroidBridge")
    }

    private class JsBridge(private val onResult: (Int, Int, String, Long) -> Unit) {
        @JavascriptInterface
        fun onRendered(width: Int, height: Int, error: String, token: Long) {
            onResult(width, height, error, token)
        }
    }
}
"""


def _selftest_tree(tmp, sheet, page):
    os.makedirs(os.path.join(tmp, "src/android/app/src/main/java/k"), exist_ok=True)
    os.makedirs(os.path.join(tmp, "src/android/app/src/main/assets/a"), exist_ok=True)
    with open(os.path.join(tmp, "src/android/app/src/main/java/k/Host.kt"), "w",
              encoding="utf-8") as fh:
        fh.write(sheet)
    with open(os.path.join(tmp, "src/android/app/src/main/assets/a/page.html"), "w",
              encoding="utf-8") as fh:
        fh.write(page)
    return tmp


def selftest():
    import contextlib
    import io
    import shutil
    import tempfile

    failures = 0

    def expect(name, sheet, page, want_rc, want_text=None):
        nonlocal failures
        tmp = tempfile.mkdtemp(prefix="katex-gate-")
        try:
            _selftest_tree(tmp, sheet, page)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = run(tmp)
            out = buf.getvalue()
            ok = rc == want_rc and (want_text is None or want_text in out)
            if ok:
                print(f"  OK {name}: rc={rc}")
            else:
                print(f"  FAIL {name}: rc={rc} want {want_rc}; needle={want_text!r}\n{out}")
                failures += 1
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    expect("compliant two-arity host", _SELFTEST_SHEET, _SELFTEST_PAGE, 0)

    # C2-scope: the 4-arg declaration is renamed away from the object's surface
    # and "restored" inside a dead nested class; only the 3-arg one is real.
    scope = _SELFTEST_SHEET.replace(
        """            @JavascriptInterface
            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                post { capture(width, height, token) }
            }""",
        """            fun onRenderedDropped(width: Int, height: Int, error: String, token: Long) {
                class DeadBridge { @JavascriptInterface fun onRendered(w: Int, h: Int, e: String, t: Long) {} }
            }""",
    )
    expect("C2-scope a nested dead class cannot supply the arity",
           scope, _SELFTEST_PAGE, 1, "declares [3]")

    # C2-dead: the signature matches, the body short-circuits.
    dead = _SELFTEST_SHEET.replace(
        """            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                post { capture(width, height, token) }""",
        """            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                if (true) return
                post { capture(width, height, token) }""",
    )
    expect("C2-dead a statically-unreachable bridge body is a violation",
           dead, _SELFTEST_PAGE, 1, "statically unreachable")

    # C2-parser: a `"/*"` literal must not swallow the real call site. The
    # page's catch block keeps calls_found > 0, so only a live parse catches it.
    parser = _SELFTEST_PAGE.replace(
        """    AndroidBridge.onRendered(Math.ceil(rect.width), Math.ceil(rect.height), '', token);""",
        """    var _open = "/*";
    AndroidBridge.onRendered(Math.ceil(rect.width), Math.ceil(rect.height));
    var _close = "*/";""",
    )
    expect("C2-parser a string-literal comment marker cannot hide the call",
           _SELFTEST_SHEET, parser, 1, "2 args")

    # registration that cannot be resolved must fail closed (ROT), not skip.
    expect("C2-scope an unresolvable registration fails loudly",
           _SELFTEST_SHEET.replace('}, "AndroidBridge")', "}, name)"),
           _SELFTEST_PAGE, 1, "ROT")

    # ── [wide4-02] arms: the four shapes the external witnesses got through ──
    expect("w4-base class-constructed host with a live callback",
           _SELFTEST_POOL, _SELFTEST_PAGE, 0)

    # family-hole/1: the same unconditional early exit, spelled `!false`.
    dead2 = _SELFTEST_SHEET.replace(
        """            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                post { capture(width, height, token) }""",
        """            fun onRendered(width: Int, height: Int, error: String, token: Long) {
                if (!false) return
                post { capture(width, height, token) }""",
    )
    expect("w4-dead-guard `if (!false) return` is folded, not spell-matched",
           dead2, _SELFTEST_PAGE, 1, "statically unreachable")

    # family-hole/2: behaviour moved into an EMPTY constructor lambda.
    empty_cb = _SELFTEST_POOL.replace(
        """JsBridge { w, h, err, callbackToken ->
            pending.complete(Triple(w, h, err))
        }""", "JsBridge { _, _, _, _ -> }")
    expect("w4-callback an empty registration lambda is a violation",
           empty_cb, _SELFTEST_PAGE, 1, "empty lambda")

    # family-hole/3: a same-named class parked in an unrelated holder must not
    # satisfy the contract on behalf of the class actually instantiated.
    shadow = _SELFTEST_POOL.replace(
        """    private class JsBridge(private val onResult: (Int, Int, String, Long) -> Unit) {
        @JavascriptInterface
        fun onRendered(width: Int, height: Int, error: String, token: Long) {
            onResult(width, height, error, token)
        }
    }""",
        """    private class UnusedHolder { class JsBridge {
        @JavascriptInterface fun onRendered(a: Int, b: Int, c: String, d: Long) { val x = a + b }
    } }
    private class JsBridge(private val onResult: (Int, Int, String, Long) -> Unit) {
        @JavascriptInterface
        fun onRendered(width: Int, height: Int, error: String) { }
    }""")
    expect("w4-shadow a nested same-named class cannot stand in for the real one",
           shadow, _SELFTEST_PAGE, 1, "onRendered with 4 args")

    # parser-death/4: a JS REGEX literal `/\//` must not read as `//` and eat
    # the rest of the line — the page's catch block keeps the rot guard happy.
    regex_page = _SELFTEST_PAGE.replace(
        """    AndroidBridge.onRendered(Math.ceil(rect.width), Math.ceil(rect.height), '', token);""",
        """    var _re = /\\//; AndroidBridge.onRendered(Math.ceil(rect.width), Math.ceil(rect.height));""")
    expect("w4-regex a JS regex literal cannot hide the call site",
           _SELFTEST_SHEET, regex_page, 1, "2 args")

    if failures:
        print(f"KATEX_BRIDGE_ARITY_SELFTEST=FAIL ({failures} arms)")
        return 1
    print("KATEX_BRIDGE_ARITY_SELFTEST=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
