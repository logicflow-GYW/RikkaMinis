#!/usr/bin/env python3
"""file_preview_url_encode_check — deep-audit-1022 FIX-H (backlog §1021-V) gate.

Pins the file:// URL encoding contract in ui/sandbox/FilePreviewScreen.kt
(device-verified 2026-10-17: a name containing `#` pasted raw into a
`file://` URL makes WebView parse the `#` as a fragment start, so
`a#b.html` loads as `a.html` → `net::ERR_FILE_NOT_FOUND`; space / `?` / `%` /
non-ASCII names break the same way):

  arm1: no raw path may be concatenated into a `file://` URL — the operand of
        `"file://" +` must BE the encoder's call, not a raw path and not the
        encoder's call with something appended after it.
  arm2: the encoder must EXIST, its body must really escape (`%XX` emission
        whose carrier is what the function returns) and the escaping must be
        REACHABLE on the way to every return — declaring
        `internal fun encodeFileUrlPath(` and returning early with an
        unescaped carrier is not an encoder.
  arm3: `fileUrlFor` must return exactly `"file://" + encodeFileUrlPath(<path>)`
        (no chain call / concat may rewrite the encoded value), and every
        `loadUrl(…)` site must CONSUME that value — a binding that is computed
        and then bypassed is the same defect with the count kept at 2.

[wide-1024 gate-witness audit] arms 2/3 were reachable three ways, all three
measured on the real tree before that change:

  * `encodeFileUrlPath` body prefixed with `if (true) return path` — the old
    criterion only matched the declaration head.
  * `fileUrlFor` rewritten to `"file://" + encodeFileUrlPath(file.absolutePath)
    .let { file.path }` — the old criterion counted the prefix substring, the
    value produced by the encoder was thrown away one hop later.
  * a load site rewritten to `fileUrlFor(item.file).let { "file://" +
    item.file.path }` — the old criterion counted `fileUrlFor(item.file)`
    occurrences, and the count stayed 2.

[batch6 gate-witness audit] six more shapes still passed at rc=0:

  * `val sb = StringBuilder(path); if (path.isNotEmpty()) return sb.toString()`
    — the carrier itself was returned before the loop, and arm2 only compared
    the return spelling with the parameter name;
  * `val safe = true || c in 'A'..'Z' || …` — the `else` emission survives
    textually but can never run (nobody asked whether it was reachable);
  * `encodeFileUrlPath(file.absolutePath).replace("%23", "#")` —
    `VALUE_DISCARD_RE` was a six-name whitelist of scope functions;
  * `encodeFileUrlPath("") + file.absolutePath` — the encoder is called with a
    constant and the real path is appended right after it;
  * the load site keeps `val targetUrl = fileUrlFor(item.file)` and calls
    `loadUrl(<raw url>)` — the count stayed 2, the value was never consumed;
  * `val q = '"' // " fileUrlFor(item.file)` — a char literal killed the
    hand-written comment stripper, so the commented-out call still counted.

The fix is a dataflow/scope criterion, not a spelling one, and it is built on
the shared primitives (`_kotlin_lexer` for views, `_kotlin_ast` for brackets /
constant folding, `_kotlin_valueflow` for evaluation sources): parsing
failures (KotlinLexError) are fail-closed violations, never silent passes.

Usage:
  python3 file_preview_url_encode_check.py <repo_root>   # real tree
  python3 file_preview_url_encode_check.py --selftest    # fixtures

Selftest markers: FILE_PREVIEW_URL_ENCODE_SELFTEST=PASS
                 FILE_PREVIEW_URL_ENCODE_SELFTEST_ARMS=<n>
"""

import operator
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_ast import (                                      # noqa: E402
    const_bool,
    const_value,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_lexer import analyse                              # noqa: E402
from _kotlin_valueflow import (                                # noqa: E402
    call_args,
    contains_ident,
    expr_end,
    is_single_assignment,
    path_feasible,
)

SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"

ENCODER_FN = "encodeFileUrlPath"
BUILDER_FN = "fileUrlFor"
BARE_TEMPLATE_RE = re.compile(r"file://\$\{[^}]*\.(?:absolutePath|canonicalPath|path)\}")
RAW_PREFIX_RE = re.compile(r'"file://"\s*\+')
LOAD_SITE_RE = re.compile(r"fileUrlFor\(item\.file\)")
LOAD_CALL_RE = re.compile(r"\bloadUrl\s*\(")
BINDING_RE = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*"
    r"(?::[^=\n]+?)?=\s*fileUrlFor\s*\(\s*item\.file\s*\)"
)
ESCAPE_EMIT_RE = re.compile(
    r"append\(\s*'%'\s*\)|append\(\s*\"%\"\s*\)|\+= *'%'|%02[Xx]|\bUri\.encode\b|\bURLEncoder\.encode\b"
)
ESCAPE_CARRIER_RE = re.compile(r"\b(\w+)\s*(?:\.\s*append\(\s*'%'\s*\)|\.\s*append\(\s*\"%\"\s*\)|\+= *'%')")


class LexFail(Exception):
    """KotlinLexError raised from a fixture/real tree read → fail-closed red."""


def _view(src, which, what):
    ok, reason, views = analyse(src)
    if not ok:
        raise LexFail("%s: %s — fail-closed (this criterion cannot read the file)"
                      % (what, reason))
    return views[which]


def read_source(path):
    """Comment-stripped view (string contents preserved, length unchanged)."""
    with open(path, encoding="utf-8", errors="replace") as f:
        src = f.read()
    return _view(src, "comments", SCREEN_REL)


def code_of(text):
    """Both channels blanked (structure channel); length is preserved."""
    return _view(text, "code", SCREEN_REL)


def _split(text, ops=("+",)):
    return [text[a:b] for a, b in split_top(text, ops)]


def _resolve_cond(view, cond):
    """`if (safe)` → the `safe` binding's right-hand side (up to 6 hops).

    `val safe = true || c in 'A'..'Z'` makes the else branch dead, but the
    condition text a brace-scanner sees is just `safe`."""
    c = strip_parens(cond.strip())
    for _ in range(6):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", c):
            break
        m = re.search(r"\b(?:val|var)\s+" + re.escape(c) + r"\s*"
                      r"(?::[^=\n]+?)?=\s*([^\n]*)", view)
        if not m:
            break
        c = strip_parens(m.group(1).strip())
    return c


def _dead_branch_reason(view, pos, what):
    """Why the branch holding [pos] can never run (or None when it can).

    Constant-folds every enclosing `if` (through one local binding) and asks
    whether the branch [pos] sits in is the impossible one — `if (true || safe)`
    with the emission in the `else` is dead even though the text is intact."""
    for cond, t_start, t_end, e_start, e_end in if_blocks(view):
        in_then = t_start <= pos < t_end
        in_else = e_start is not None and e_start <= pos < e_end
        if not (in_then or in_else):
            continue
        b = const_bool(_resolve_cond(view, cond))
        if b is None:
            continue
        if in_then and not b:
            return ("%s sits in the branch of `if (%s)` that can never run "
                    "(the condition is a constant)" % (what, cond.strip()))
        if in_else and b:
            return ("%s sits in the `else` of `if (%s)`, which can never run "
                    "(the condition is a constant)" % (what, cond.strip()))
    return None


def _encoder_operand(text):
    """The argument text when [text] is exactly `encodeFileUrlPath( … )`.

    None when the call is not there, cannot be balanced, or is followed by a
    chain/concat — `.replace(…)` / `.let { }` / `+ raw` all rewrite the value
    the encoder produced, which is the defect this arm exists to stop."""
    m = re.match(r"\s*encodeFileUrlPath\s*\(", text)
    if not m:
        return None
    open_pos = m.end() - 1
    close = match_bracket(text, open_pos)
    if close < 0:
        return None
    if text[close + 1:].strip():
        return None
    return text[open_pos + 1:close]


def _first_param(code, name):
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    close = match_bracket(code, m.end() - 1)
    if close < 0:
        return None
    inner = code[m.end():close]
    pm = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)", inner)
    return pm.group(1) if pm else None


# --- arm2c: the byte set the non-escaping branch accepts -------------------
#
# arm2 asks whether an escape EMISSION exists and is reachable. Witness 04-3
# (round 3, dual-gate) widened the *passthrough* arm instead —
# `safe = … || c == '#' || c == '?'` — so the emission stayed intact and
# reachable, every arm above stayed green, and `a#b.html` went into the URL as a
# fragment start again: the exact defect this gate exists for, restored without
# touching anything the gate looked at.
#
# The criterion is now the SET: the branch that appends a raw character may only
# accept RFC 3986 unreserved bytes plus `/` (the semantics the encoder's own KDoc
# pins). The branch predicate is followed through the `val` that names it and
# evaluated over the whole byte domain, so a whitelist (`c in 'A'..'Z' || …`) and
# the equivalent blacklist (`v < 0x21 || v > 0x7E`) are judged by what they LET
# THROUGH, not by how they are spelled. A predicate this module cannot fold is a
# violation (fail-closed), never a pass.

ENCODER_SAFE_BYTES = (
    set(range(ord("A"), ord("Z") + 1))
    | set(range(ord("a"), ord("z") + 1))
    | set(range(ord("0"), ord("9") + 1))
    | {ord(c) for c in "-._~/"}
)
_BYTE_DOMAIN = frozenset(range(256))
_CHAR_ESCAPES = {"n": 10, "t": 9, "r": 13, "b": 8, "f": 12, "0": 0,
                 "\\": 92, "'": 39, '"': 34}
_CMP_OPS = ("==", "!=", "<=", ">=", "<", ">")
_CMP_FLIP = {"==": "==", "!=": "!=", "<": ">", ">": "<", "<=": ">=", ">=": "<="}
_CMP_FN = {"==": operator.eq, "!=": operator.ne, "<": operator.lt,
           ">": operator.gt, "<=": operator.le, ">=": operator.ge}


def _char_code(text):
    """Code point of a Kotlin char literal (`'#'`, `'\\u00e9'`), else None."""
    m = re.fullmatch(r"'(\\u[0-9a-fA-F]{4}|\\.|[^'\\])'", text.strip(), re.S)
    if not m:
        return None
    body = m.group(1)
    if body.startswith("\\u"):
        return int(body[2:], 16)
    if body.startswith("\\"):
        return _CHAR_ESCAPES.get(body[1])
    return ord(body)


def _int_code(text):
    """Value of an integer literal (`0x21`, `1_000`), else None."""
    t = text.strip().replace("_", "")
    if re.fullmatch(r"0[xX][0-9a-fA-F]+", t):
        return int(t, 16)
    if re.fullmatch(r"\d+", t):
        return int(t)
    return None


def _compare_bytes(op, value):
    fn = _CMP_FN[op]
    return {b for b in _BYTE_DOMAIN if fn(b, value)}


def _cmp_split(t):
    """Top-level comparison: (left, op, right), or None."""
    depth = 0
    for i, ch in enumerate(t):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and ch in "=!<>":
            for op in _CMP_OPS:
                if t.startswith(op, i):
                    return t[:i], op, t[i + len(op):]
    return None


def _literal_code(text):
    code = _char_code(text)
    return code if code is not None else _int_code(text)


def _atom_bytes(t, ident):
    """Bytes accepted by one comparison atom over [ident], or None."""
    m = re.fullmatch(r"([A-Za-z_]\w*)\s+in\s+(.+?)\s*\.\.\s*(.+)", t, re.S)
    if m and m.group(1) == ident:
        lo = _literal_code(m.group(2))
        hi = _literal_code(m.group(3))
        if lo is None or hi is None:
            return None
        return {b for b in _BYTE_DOMAIN if lo <= b <= hi}
    parts = _cmp_split(t)
    if parts is None:
        return None
    left, op, right = (p.strip() for p in parts)
    if left == ident:
        val = _literal_code(right)
        return None if val is None else _compare_bytes(op, val)
    if right == ident:
        val = _literal_code(left)
        return None if val is None else _compare_bytes(_CMP_FLIP[op], val)
    return None


def _pred_bytes(t, ident):
    """Bytes accepted by a predicate over [ident] (`||` / `&&` / `!` / parens)."""
    t = t.strip()
    if not t:
        return None
    inner = strip_parens(t)
    if inner != t:
        return _pred_bytes(inner, ident)
    if t.startswith("!") and _cmp_split(t) is None:
        rest = _pred_bytes(t[1:], ident)
        return None if rest is None else set(_BYTE_DOMAIN) - rest
    parts = split_top(t, ("||",))
    if len(parts) > 1:
        out = set()
        for a, b in parts:
            got = _pred_bytes(t[a:b], ident)
            if got is None:
                return None
            out |= got
        return out
    parts = split_top(t, ("&&",))
    if len(parts) > 1:
        out = set(_BYTE_DOMAIN)
        for a, b in parts:
            got = _pred_bytes(t[a:b], ident)
            if got is None:
                return None
            out &= got
        return out
    if t == "true":
        return set(_BYTE_DOMAIN)
    if t == "false":
        return set()
    return _atom_bytes(t, ident)


def _resolve_predicate(text, cond, before, depth=0):
    """`if (safe)` → `val safe = …`, up to six hops. Keeps a leading `!`."""
    c = strip_parens(cond.strip())
    if depth > 5:
        return c
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", c):
        m = None
        for cand in re.finditer(r"\b(?:val|var)\s+" + re.escape(c) + r"\s*"
                                r"(?::[^=\n]+?)?=(?!=)", text[:before]):
            m = cand
        if m is None:
            return c
        rhs = text[m.end():expr_end(text, m.end())].strip()
        return _resolve_predicate(text, rhs, m.start(), depth + 1)
    rest = c
    neg = ""
    while rest.startswith("!"):
        neg += "!"
        rest = strip_parens(rest[1:])
    if neg and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", rest):
        return neg + _resolve_predicate(text, rest, before, depth + 1)
    return c


def _passthrough_problems(view, lit):
    """Violations for the bytes the non-escaping branch of an `if` lets through.

    `view` is the structure channel (bracket/`if` geometry), `lit` the
    comment-stripped channel (char literals readable); both are the same length,
    so a span found in one can be read from the other."""
    problems = []
    for cond, t0, t1, e0, e1 in if_blocks(view):
        then_emit = bool(ESCAPE_EMIT_RE.search(lit[t0:t1]))
        else_emit = e0 is not None and bool(ESCAPE_EMIT_RE.search(lit[e0:e1]))
        if then_emit == else_emit:
            continue
        if then_emit:
            if e0 is None:
                continue            # nothing survives raw: no passthrough branch
            span, negate = (e0, e1), True
        else:
            span, negate = (t0, t1), False
        if not re.search(r"\bappend\s*\(", view[span[0]:span[1]]):
            continue                # the branch appends nothing: no byte leaks
        pred = _resolve_predicate(lit, cond, span[0])
        if negate:
            pred = "!(" + pred + ")"
        pool = re.findall(
            r"[A-Za-z_]\w*",
            re.sub(r"'(?:\\.|[^'\\])'|\"(?:\\.|[^\"\\])*\"", "''", pred),
        )
        accepted = None
        for cand in pool:
            if cand in ("true", "false", "in"):
                continue
            got = _pred_bytes(pred, cand)
            if got is not None:
                accepted = got
                break
        if accepted is None:
            problems.append(
                "%s: the branch that appends the raw character is guarded by %r, which this "
                "criterion cannot read as a byte set — fail-closed: state the safe set as "
                "character comparisons (`c == '-'`, `c in 'A'..'Z'`), otherwise nobody can "
                "prove which bytes reach the file:// URL unescaped"
                % (ENCODER_FN, pred[:80])
            )
            continue
        leaked = sorted(accepted - ENCODER_SAFE_BYTES)
        if leaked:
            shown = ", ".join(
                "U+%04X '%s'" % (b, chr(b)) if 32 <= b < 127 else "U+%04X" % b
                for b in leaked[:8]
            )
            problems.append(
                "%s: the raw-append branch accepts %d byte(s) outside RFC 3986 unreserved + "
                "'/' (%s%s) — they reach the file:// URL unescaped, so `a#b.html` is parsed "
                "as a fragment again while the escape loop stays intact and reachable"
                % (ENCODER_FN, len(leaked), shown,
                   " …" if len(leaked) > 8 else "")
            )
    return problems


def check_encoder(code):
    """arm2 — the single encoder must exist AND really escape, on every path."""
    problems = []
    decls = len(re.findall(r"\bfun\s+" + ENCODER_FN + r"\s*\(", code))
    if decls != 1:
        return ["encoder `internal fun %s(` must be declared exactly once (found %d) — "
                "re-point this criterion, do not delete it" % (ENCODER_FN, decls)]
    fb = function_body(code, ENCODER_FN)
    if fb is None:
        return ["%s body cannot be located (fail-closed) — the encoder's body is what "
                "this arm measures" % ENCODER_FN]
    start, end, kind = fb
    body = code[start:end]
    if not body.strip():
        return ["%s has no body — the encoder cannot be an empty declaration" % ENCODER_FN]
    view = code_of(body)
    param = _first_param(code, ENCODER_FN)

    if not ESCAPE_EMIT_RE.search(body):
        problems.append(
            "%s never emits a percent escape (`'%%'` append / `%%02X` / "
            "Uri.encode / URLEncoder.encode) — a function that returns its input is "
            "not an encoder" % ENCODER_FN
        )

    # (a) the escaping must happen BEFORE the function can answer: a return that
    #     precedes the first emission hands back whatever the carrier holds at
    #     that point (`StringBuilder(path)` → the raw path).
    emit_sites = [m.start() for m in ESCAPE_EMIT_RE.finditer(body)]
    if emit_sites:
        first_emit = min(emit_sites)
        early = [m.start() for m in re.finditer(r"\breturn\b", view) if m.start() < first_emit]
        if early:
            problems.append(
                "%s returns before the escape loop runs (return at offset %d, first "
                "%%XX emission at %d) — the carrier is returned unescaped, so every "
                "`#`/`?`/space byte survives into the file:// URL"
                % (ENCODER_FN, early[0], first_emit)
            )
        # (b) the emission must be reachable: a constant-decided branch around it
        #     makes `else { sb.append('%') … }` dead code.
        dead = _dead_branch_reason(view, first_emit, "the %XX emission")
        if dead:
            problems.append("%s: %s" % (ENCODER_FN, dead))

    # (c) the returned value must carry the escapes.
    carriers = set(ESCAPE_CARRIER_RE.findall(body))
    returns = [m.group(1) for m in re.finditer(r"\breturn\b([^\n;}]*)", body)]
    expr_return = body.strip() if kind == "expr" else None
    exprs = list(returns) + ([expr_return] if expr_return else [])
    if param:
        for expr in exprs:
            if re.fullmatch(r"\s*%s\s*" % re.escape(param), expr):
                problems.append(
                    "%s returns its input unchanged (`return %s`) — every "
                    "`#`/`?`/space/`%%` byte survives into the file:// URL unescaped"
                    % (ENCODER_FN, param)
                )
                break
    if carriers:
        if not any(any(re.search(r"\b%s\b" % re.escape(c), e) for c in carriers) for e in exprs):
            problems.append(
                "%s builds an escaped buffer (%s) but never returns it — the "
                "escaped value must be what the function returns"
                % (ENCODER_FN, ", ".join(sorted(carriers)))
            )
    elif not re.search(r"\b(Uri\.encode|URLEncoder\.encode)\s*\(", " ".join(exprs)):
        problems.append(
            "%s returns a value that carries no escaping (fail-closed: no escape "
            "carrier found in any return expression)" % ENCODER_FN
        )
    # (d) dual-gate 04-3 — the symmetric half: what the passthrough branch lets
    #     through. A perfect, reachable emission is useless when `safe` admits a
    #     URI delimiter (`#` / `?`), because that byte never reaches the loop.
    problems.extend(_passthrough_problems(view, body))
    return problems


def check_raw_concats(code):
    """arm1 — the operand of `"file://" +` must BE the encoder's value."""
    problems = []
    view = code_of(code)
    for m in RAW_PREFIX_RE.finditer(code):
        start = m.end()
        if start >= len(view):
            continue
        stop = expr_end(view, start)
        operand = view[start:stop].strip()
        if not operand:
            problems.append("`\"file://\" +` is followed by nothing — unreadable "
                            "concatenation (fail-closed)")
            continue
        if _encoder_operand(operand) is not None:
            continue
        problems.append(
            "`\"file://\" +` must be followed by %s( … ) and nothing else — found %r "
            "(a raw path, or the encoder's value with something appended, turns `#` "
            "into a fragment)" % (ENCODER_FN, operand[:60])
        )
    return problems


def _result_expr(body, kind, view):
    """The expression the function hands back (expression body or last return)."""
    if kind == "expr":
        return body.strip()
    rets = list(re.finditer(r"\breturn\b", view))
    if not rets:
        return None
    start = rets[-1].end()
    stop = expr_end(view, start)
    return body[start:stop].strip()


def check_file_url_for(code):
    """arm3a — fileUrlFor must RETURN exactly the encoder's value for the path."""
    decls = len(re.findall(r"\bfun\s+" + BUILDER_FN + r"\s*\(", code))
    if decls != 1:
        return ["`private fun %s(` must be declared exactly once (found %d) — it is the "
                "single file:// URL builder this criterion pins" % (BUILDER_FN, decls)]
    fb = function_body(code, BUILDER_FN)
    if fb is None:
        return ["%s body cannot be located (fail-closed)" % BUILDER_FN]
    start, end, kind = fb
    body = code[start:end]
    view = code_of(body)
    expr = _result_expr(body, kind, view)
    if not expr:
        return ["%s has no return value — the builder must hand back "
                "`\"file://\" + %s(...)`" % (BUILDER_FN, ENCODER_FN)]
    eview = code_of(expr)
    p = expr.strip()
    m = eview.strip()
    if len(p) != len(m):
        return ["%s: the builder's return expression cannot be masked in place "
                "(fail-closed)" % BUILDER_FN]
    problems = []
    spans = split_top(m, ("+",))
    parts = [p[a:b].strip() for a, b in spans]
    masks = [m[a:b].strip() for a, b in spans]
    if len(parts) != 2:
        problems.append(
            "%s must return exactly `\"file://\" + %s(<path>)` — found %r (%d top-level "
            "`+` operand(s)): the encoded path must be the whole URL, not one term of it"
            % (BUILDER_FN, ENCODER_FN, p[:80], len(parts))
        )
    else:
        head, tail = parts[0], masks[1]
        if head.strip('"\'') != "file://":
            problems.append(
                "%s no longer starts the URL with the `file://` scheme — found %r"
                % (BUILDER_FN, head[:40])
            )
        arg = _encoder_operand(tail)
        if arg is None:
            problems.append(
                "%s: the URL operand must be exactly `%s( … )` — found %r. A chain call "
                "(`.replace(\"%%23\", \"#\")`) or a trailing concat rewrites the value the "
                "encoder produced" % (BUILDER_FN, ENCODER_FN, tail[:80])
            )
        else:
            param = _first_param(code, BUILDER_FN)
            if param and not contains_ident(arg, param):
                problems.append(
                    "%s: the encoder is called with %r instead of the file's path "
                    "(`%s.absolutePath`) — encoding a constant encodes nothing, the real "
                    "path reaches the URL unescaped"
                    % (BUILDER_FN, arg.strip()[:40], param)
                )
    return problems


def _enclosing_fn(view, pos):
    """Innermost `fun … { … }` region around [pos] in a code view, or None."""
    best = None
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?[A-Za-z_]\w*\s*\(", view):
        close = match_bracket(view, m.end() - 1)
        if close < 0:
            continue
        brace = view.find("{", close)
        if brace < 0:
            continue
        end = match_bracket(view, brace)
        if end < 0 or not (brace < pos < end):
            continue
        if best is None or brace > best[0]:
            best = (brace, end)
    return best


def check_load_sites(code):
    """arm3b — every load site must CONSUME the encoder's value."""
    problems = []
    view = code_of(code)
    loads = list(LOAD_SITE_RE.finditer(code))
    if len(loads) < 2:
        problems.append(
            "expected 2 load sites through %s(item.file) (preview + print), got %d"
            % (BUILDER_FN, len(loads))
        )
    # (a) dual-gate 04-1 — the site must be REACHABLE. Counting the call and
    #     reading its argument says nothing about whether it ever runs:
    #     `if (preRenderedHtml == null && false) { webView.loadUrl(fileUrlFor(…)) }`
    #     keeps the count at 2, keeps the argument clean, and loads nothing —
    #     every HTML print rendered a blank document.
    for m in loads:
        if not path_feasible(view, m.start()):
            problems.append(
                "the print/preview load site at line %d sits in a branch that can never "
                "run (its enclosing `if`/`when` folds to a constant) — the encoded URL is "
                "computed and never loaded"
                % (code.count("\n", 0, m.start()) + 1)
            )
    for lm in LOAD_CALL_RE.finditer(view):
        if not path_feasible(view, lm.start()):
            problems.append(
                "loadUrl(…) at line %d is unreachable (its enclosing condition folds to a "
                "constant) — the URL is assembled and thrown away"
                % (code.count("\n", 0, lm.start()) + 1)
            )
    # names bound to the encoder's value (`val targetUrl = fileUrlFor(item.file)`)
    bound = {}
    for m in BINDING_RE.finditer(view):
        bound[m.group(1)] = m.start()
    for name, pos in bound.items():
        # (b) dual-gate 04-2 — the binding must BE the value at the load site.
        #     `var targetUrl = fileUrlFor(item.file)` followed by
        #     `targetUrl = targetUrl.replace("%23", "#")` keeps name, spelling
        #     and consumption intact while handing the raw path to loadUrl.
        region = _enclosing_fn(view, pos)
        scope = view[region[0]:region[1]] if region else view
        if not is_single_assignment(scope, name):
            problems.append(
                "`%s` is bound to %s(item.file) and then reassigned or re-declared — the "
                "value the load site receives is whatever the later assignment put there, "
                "not necessarily the encoded URL" % (name, BUILDER_FN)
            )
        used = False
        for lm in LOAD_CALL_RE.finditer(view):
            for a, b in call_args(view, lm.end() - 1):
                if contains_ident(view[a:b], name):
                    used = True
                    break
            if used:
                break
        if not used:
            problems.append(
                "`%s = %s(item.file)` is computed but never handed to loadUrl( … ) — the "
                "load site uses another URL source, so the encoded value is dead and the "
                "page still loads the raw path" % (name, BUILDER_FN)
            )
    # ... and no load call may load a URL that does not come from the encoder.
    for lm in LOAD_CALL_RE.finditer(view):
        args = call_args(view, lm.end() - 1)
        if not args:
            continue
        if any(any(contains_ident(view[a:b], n) for n in bound) for a, b in args):
            continue
        if any(BUILDER_FN + "(" in view[a:b] for a, b in args):
            continue
        shown = ", ".join(view[a:b].strip()[:50] for a, b in args)
        problems.append(
            "loadUrl( %s ) does not load the %s( … ) value — a URL assembled at the call "
            "site bypasses the encoder entirely" % (shown, BUILDER_FN)
        )
    for m in loads:
        tail = code[m.end(): m.end() + 80]
        if re.match(r"\s*\.\s*(let|run|also|apply|takeIf|takeUnless)\b", tail) or \
                re.match(r"\s*\?:", tail):
            problems.append(
                "a load site rewrites %s(item.file) with %r — the printed/previewed URL "
                "must be the encoder's output, not a value swapped in behind it"
                % (BUILDER_FN, tail.strip()[:40])
            )
    return problems


def check(root):
    """Return a list of violation strings (empty = green)."""
    path = os.path.join(root, SCREEN_REL)
    if not os.path.exists(path):
        return ["%s not found" % SCREEN_REL]
    try:
        code = read_source(path)
    except LexFail as e:
        return [str(e)]
    violations = []
    if BARE_TEMPLATE_RE.search(code):
        violations.append("bare 'file://${…path}' template still present (pre-fix shape)")
    if '"file://" + file.absolutePath' in code:
        violations.append(
            "bare '\"file://\" + file.absolutePath' concatenation still present — "
            "must route through the encoder"
        )
    try:
        violations.extend(check_raw_concats(code))
        violations.extend(check_encoder(code))
        violations.extend(check_file_url_for(code))
        violations.extend(check_load_sites(code))
    except LexFail as e:
        violations.append(str(e))
    return violations


# --- compliant / violation fixture snippets (code-level, not comments) ---
# The compliant fixture exercises # / space+CJK names through the encoder —
# the three real-world trigger classes from backlog §1021-V — and carries a
# real escaping body, because arm2 now measures the body, not the head.

ENCODER_BLOCK = """internal fun encodeFileUrlPath(path: String): String {
    val sb = StringBuilder(path.length)
    for (b in path.toByteArray(Charsets.UTF_8)) {
        val v = b.toInt() and 0xFF
        val c = v.toChar()
        val safe = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'
        if (safe) {
            sb.append(c)
        } else {
            sb.append('%').append(Integer.toHexString(v shr 4)).append(Integer.toHexString(v and 0x0F))
        }
    }
    return sb.toString()
}"""

COMPLIANT = """package com.rikkaminis.app.ui.sandbox

""" + ENCODER_BLOCK + """

private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)

class Screen {
    private fun preview(item: Item) {
        val targetUrl = fileUrlFor(item.file)
        post { loadUrl(targetUrl) }
    }
    private fun printFile(context: Context, item: Item) {
        webView.loadUrl(fileUrlFor(item.file))
    }
}
"""

VIOLATION_BARE = COMPLIANT.replace(
    'val targetUrl = fileUrlFor(item.file)',
    'val targetUrl = "file://${item.file.absolutePath}"',
)

VIOLATION_NO_ENCODER = COMPLIANT.replace(
    'private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)',
    'private fun fileUrlFor(file: File): String = "file://" + file.absolutePath',
)

# wide-1024 arm2(a): declaration kept, body short-circuited at the first line.
VIOLATION_ENCODER_SHORT_CIRCUIT = COMPLIANT.replace(
    "    val sb = StringBuilder(path.length)",
    "    if (true) return path\n    val sb = StringBuilder(path.length)",
)

# wide-1024 arm2(b): body hollowed out to `= path` (declaration still matches).
VIOLATION_ENCODER_HOLLOW = COMPLIANT.replace(
    ENCODER_BLOCK,
    "internal fun encodeFileUrlPath(path: String): String = path",
)

# wide-1024 arm3(a): the encoder runs, then its value is thrown away.
VIOLATION_FILEURLFOR_DISCARD = COMPLIANT.replace(
    'private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)',
    'private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath).let { file.path }',
)

# wide-1024 arm3(b): the load-site count stays 2, the URL is swapped behind it.
VIOLATION_LOAD_SITE_DISCARD = COMPLIANT.replace(
    "val targetUrl = fileUrlFor(item.file)",
    'val targetUrl = fileUrlFor(item.file).let { "file://" + item.file.path }',
)

# --- batch6 (B6-t02) witnesses: each one passed this gate before the change ---

# 08-1: the carrier itself is returned before the loop ever escapes anything.
VIOLATION_ENCODER_EARLY_CARRIER = COMPLIANT.replace(
    "    val sb = StringBuilder(path.length)",
    "    val sb = StringBuilder(path)\n    if (path.isNotEmpty()) return sb.toString()",
)

# 08-2: `safe` is constant-true, so the `%XX` branch is unreachable dead code.
VIOLATION_ENCODER_SAFE_CONST = COMPLIANT.replace(
    """        val safe = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'""",
    """        val safe = true || c in 'A'..'Z'""",
)

# 08-3: the encoded value is rewritten one chain call later (not a scope fn).
VIOLATION_FILEURLFOR_REPLACE = COMPLIANT.replace(
    '"file://" + encodeFileUrlPath(file.absolutePath)',
    '"file://" + encodeFileUrlPath(file.absolutePath).replace("%23", "#").replace("%20", " ")',
)

# 08-4: the encoder is called with a constant, the real path appended after it.
VIOLATION_FILEURLFOR_EMPTY_ARG = COMPLIANT.replace(
    '"file://" + encodeFileUrlPath(file.absolutePath)',
    '"file://" + encodeFileUrlPath("") + file.absolutePath',
)

# 08-5: the encoded value is bound and then bypassed at the load site.
VIOLATION_LOAD_SITE_UNCONSUMED = COMPLIANT.replace(
    """        val targetUrl = fileUrlFor(item.file)
        post { loadUrl(targetUrl) }""",
    """        val targetUrl = fileUrlFor(item.file)
        post { loadUrl(item.file.toURI().toString().replace("%23", "#")) }""",
)

# 08-6: a char literal kills a hand-written comment stripper, so the
# commented-out call still counts as a load site.
VIOLATION_CHAR_LITERAL_SWALLOW = COMPLIANT.replace(
    """    private fun printFile(context: Context, item: Item) {
        webView.loadUrl(fileUrlFor(item.file))
    }""",
    """    private fun printFile(context: Context, item: Item) {
        val q = '"' // " fileUrlFor(item.file)
        webView.loadUrl("file:" + "//" + item.file.path)
    }""",
)


# --- round 3 (R3-t02, dual-gate bypass): each of these passed this gate ---

# 04-1: the print load site sits behind a constant-false guard, so the URL is
# computed (count intact, argument clean) and never loaded.
VIOLATION_LOAD_SITE_DEAD_BRANCH = COMPLIANT.replace(
    """    private fun printFile(context: Context, item: Item) {
        webView.loadUrl(fileUrlFor(item.file))
    }""",
    """    private fun printFile(context: Context, item: Item, preRenderedHtml: String? = null) {
        if (preRenderedHtml == null && false) {
            webView.loadUrl(fileUrlFor(item.file))
        } else {
            webView.loadDataWithBaseURL(null, preRenderedHtml ?: "", "text/html", "utf-8", null)
        }
    }""",
)

# 04-2: the binding is `var` and is overwritten with the raw path one line later.
VIOLATION_LOAD_SITE_REBOUND = COMPLIANT.replace(
    """        val targetUrl = fileUrlFor(item.file)
        post { loadUrl(targetUrl) }""",
    """        var targetUrl = fileUrlFor(item.file)
        targetUrl = targetUrl.replace("%23", "#")
        post { loadUrl(targetUrl) }""",
)

# 04-3: the `safe` set is widened with two URI delimiters — the escape loop stays
# intact and reachable, so only the byte set can tell.
VIOLATION_ENCODER_SAFE_WIDENED = COMPLIANT.replace(
    "            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'",
    "            c == '-' || c == '.' || c == '_' || c == '~' || c == '/' || c == '#' || c == '?'",
)

# The same whitelist written with range comparisons must stay green: the
# criterion judges the byte set, not the spelling.
COMPLIANT_ENCODER_RANGE_FORM = COMPLIANT.replace(
    """        val safe = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'""",
    """        val safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' ||
            c == '~' || c == '/'""",
)


def run_fixture(name, content, expect_ok, expect_msg=None):
    with tempfile.TemporaryDirectory() as tmp:
        os.makedirs(os.path.join(tmp, SCREEN_REL.rsplit("/", 1)[0]))
        with open(os.path.join(tmp, SCREEN_REL), "w") as f:
            f.write(content)
        v = check(tmp)
        ok = (not v) == expect_ok
        if ok and not expect_ok and expect_msg is not None:
            ok = any(expect_msg in item for item in v)
            if not ok:
                print("  [FAIL] fixture %s: expected a %r violation, got %s"
                      % (name, expect_msg, v))
                return False
        status = "PASS" if ok else "FAIL"
        print("  [%s] fixture %s: %s" % (status, name, v))
        return ok


def selftest():
    print("file_preview_url_encode_check selftest:")
    arms = [
        ("compliant (#/space/CJK through the encoder)", COMPLIANT, True, None),
        ("violation-bare-template", VIOLATION_BARE, False, "bare 'file://${"),
        ("violation-no-encoder", VIOLATION_NO_ENCODER, False,
         '"file://" + file.absolutePath'),
        ("violation-encoder-short-circuit", VIOLATION_ENCODER_SHORT_CIRCUIT, False,
         "returns its input unchanged"),
        ("violation-encoder-hollow", VIOLATION_ENCODER_HOLLOW, False,
         "never emits a percent escape"),
        ("violation-fileurlfor-discard", VIOLATION_FILEURLFOR_DISCARD, False,
         "must be exactly `encodeFileUrlPath( … )`"),
        ("violation-load-site-discard", VIOLATION_LOAD_SITE_DISCARD, False,
         "rewrites fileUrlFor(item.file)"),
        # batch6
        ("violation-encoder-early-carrier-return", VIOLATION_ENCODER_EARLY_CARRIER, False,
         "returns before the escape loop runs"),
        ("violation-encoder-safe-const-true", VIOLATION_ENCODER_SAFE_CONST, False,
         "can never run"),
        ("violation-fileurlfor-replace-restores-raw", VIOLATION_FILEURLFOR_REPLACE, False,
         "must be exactly `encodeFileUrlPath( … )`"),
        ("violation-fileurlfor-empty-arg", VIOLATION_FILEURLFOR_EMPTY_ARG, False,
         "must return exactly"),
        ("violation-load-site-unconsumed", VIOLATION_LOAD_SITE_UNCONSUMED, False,
         "computed but never handed to loadUrl"),
        ("violation-char-literal-swallows-comment", VIOLATION_CHAR_LITERAL_SWALLOW, False,
         "bypasses the encoder"),
        # round 3 (R3-t02)
        ("compliant encoder written with range comparisons", COMPLIANT_ENCODER_RANGE_FORM,
         True, None),
        ("violation-load-site-dead-branch", VIOLATION_LOAD_SITE_DEAD_BRANCH, False,
         "sits in a branch that can never run"),
        ("violation-load-site-rebound", VIOLATION_LOAD_SITE_REBOUND, False,
         "is bound to fileUrlFor(item.file) and then reassigned"),
        ("violation-encoder-safe-widened-with-uri-delimiters",
         VIOLATION_ENCODER_SAFE_WIDENED, False,
         "outside RFC 3986 unreserved"),
    ]
    ok = True
    for name, content, expect_ok, msg in arms:
        ok &= run_fixture(name, content, expect_ok, msg)
    print("FILE_PREVIEW_URL_ENCODE_SELFTEST=" + ("PASS" if ok else "FAIL"))
    print("FILE_PREVIEW_URL_ENCODE_SELFTEST_ARMS=%d" % len(arms))
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if len(sys.argv) < 2:
        print("usage: python3 file_preview_url_encode_check.py <repo_root>", file=sys.stderr)
        sys.exit(2)
    root = sys.argv[1]
    violations = check(root)
    if violations:
        for v in violations:
            print("FAIL:", v)
        sys.exit(1)
    print("file_preview_url_encode_check: OK (arm1 + arm2 + arm3 green)")


if __name__ == "__main__":
    main()
