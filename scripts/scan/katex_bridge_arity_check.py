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

IFACE_DEFAULT = "AndroidBridge"
ANNOTATION = "@JavascriptInterface"


def strip_comments(text):
    """Remove comments before any structural scan — QUOTE-AWARE.

    Prose apostrophes in comments ("the pool's cache", "Chromium's bridge")
    otherwise read as string delimiters and swallow real parentheses — which is
    how the first draft of this scanner silently mis-parsed `KaTeXView.kt` and
    reported a clean tree. After stripping, a `'` in Kotlin is a char literal
    and a `'` in JS is a real string delimiter, matching the scanner's rules.

    [wide3-06] The old version was three regexes, so a `"/*"` INSIDE a string
    literal started a block comment and ate the code up to the next `*/` — the
    witness hid a 2-arg call between two such literals. This scanner walks the
    text: inside a quoted span nothing is a comment, and comment bodies are
    blanked (newlines kept) so offsets stay stable.
    """
    out = []
    i, n = 0, len(text)
    quote = None
    while i < n:
        c = text[i]
        if quote is not None:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in "\"'`":
            quote = c
            out.append(c)
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            end = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:end]))
            i = end
            continue
        if text.startswith("<!--", i):
            j = text.find("-->", i + 3)
            end = n if j < 0 else j + 3
            out.append(" " * max(1, end - i))
            i = end
            continue
        out.append(c)
        i += 1
    return "".join(out)


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


def _object_body(kt_text, arg):
    """The brace block of the object denoted by a registration's first arg.

    Accepted shapes: an inline `object { … }` / `object : X() { … }`, an
    identifier bound to such an object (`val jsBridge = object { … }`), or a
    type/constructor reference (`JsBridge { … }`, `JsBridge(cb)`). Anything
    else returns None → the caller fails closed (a registration it cannot
    resolve must never silently drop out of the host set).
    """
    arg = arg.strip()
    if not arg:
        return None
    if re.match(r"object\b", arg):
        b = arg.find("{")
        if b < 0:
            return None
        end = match_brace(arg, b)
        return arg[b:end] if end > 0 else None
    m = re.fullmatch(r"[A-Za-z_]\w*", arg)
    if m:
        decl = re.search(
            r"\b(?:val|var)\s+" + re.escape(arg) + r"\s*(?::[^=\n]*)?=\s*object\b",
            kt_text,
        )
        if not decl:
            return None
        b = kt_text.find("{", decl.end())
        if b < 0:
            return None
        end = match_brace(kt_text, b)
        return kt_text[b:end] if end > 0 else None
    m = re.match(r"([A-Za-z_]\w*)\s*[({]", arg)
    if m:
        type_name = m.group(1)
        decl = re.search(
            r"\b(?:class|object)\s+" + re.escape(type_name) + r"\b", kt_text
        )
        if not decl:
            return None
        b = kt_text.find("{", decl.end())
        if b < 0:
            return None
        # A constructor arg list may sit between the name and the body.
        end = match_brace(kt_text, b)
        return kt_text[b:end] if end > 0 else None
    return None


def registrations(kt_text):
    """[(name, object_arg)] per `addJavascriptInterface(obj, "Name")` call.

    Fail-closed contract: when the call is present but the interface NAME is
    not a string literal, or the object expression cannot be resolved, the
    caller gets a `None` in the second slot and must report ROT.
    """
    out = []
    for m in re.finditer(r"addJavascriptInterface\s*\(", kt_text):
        open_idx = m.end() - 1
        end = match_paren(kt_text, open_idx)
        if end < 0:
            out.append((None, None))
            continue
        args = split_args(kt_text[open_idx + 1:end])
        if len(args) < 2:
            out.append((None, None))
            continue
        literals = re.findall(r"\"([^\"]*)\"", args[-1])
        if not literals:
            out.append((None, None))
            continue
        out.append((literals[-1], args[0]))
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
    """Statically-unreachable body: `if (true) return` / a bare leading return.

    [wide3-06 C2-dead] Arity agreement alone let `fun onRendered(…4 args…) { if
    (true) return }` pass while no formula was ever captured.
    """
    if not body:
        return False
    if re.match(r"\{\s*return\b", body):
        return True
    return bool(re.search(r"\bif\s*\(\s*true\s*\)", body))


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
                text = strip_comments(fh.read())
            if "addJavascriptInterface" not in text:
                continue
            rel = os.path.relpath(path, root)
            regs = registrations(text)
            if not regs:
                unparsed.append(rel)
                continue
            per_name = {}
            bad = False
            for iface, obj_arg in regs:
                if iface is None:
                    bad = True
                    continue
                body = _object_body(text, obj_arg)
                if body is None:
                    bad = True
                    continue
                decls = member_declarations(body)
                arities = {}
                for method, arity, mbody in decls:
                    arities.setdefault(method, set()).add(arity)
                    if _body_is_dead(mbody):
                        per_name.setdefault("__dead__", []).append((method, arity))
                per_name[iface] = arities
            if bad:
                unparsed.append(rel)
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
        print(f"{c} ROT: addJavascriptInterface present but no interface name could be parsed:")
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
            text = strip_comments(fh.read())
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
            for method, arity in per_name.get("__dead__", []):
                violations.append((rel, ANNOTATION, method, arity, host_file, "DEAD"))
    if calls_found == 0:
        print("❌ ROT: no JS-bridge call site found in assets (contract unchecked)")
        return 1

    if violations:
        print("")
        print(f"❌ KATEX BRIDGE ARITY: {len(violations)} mismatch(es)")
        for rel, iface, method, argc, host_file, arities in violations:
            if arities == "DEAD":
                print(f"   {host_file}: @JavascriptInterface {method}/{argc} never runs "
                      f"its body (statically unreachable) — the bridge call succeeds "
                      f"and renders nothing")
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

    if failures:
        print(f"KATEX_BRIDGE_ARITY_SELFTEST=FAIL ({failures} arms)")
        return 1
    print("KATEX_BRIDGE_ARITY_SELFTEST=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
