"""mirror_category_route: the mirror category row tap must open the detail
destination — and only with the row's own key.

Why this gate exists
--------------------
`MirrorCategoryDetailScreen` shipped with zero call sites: `MirrorSettingsScreen`
exposed `onMirrorCategoryClick`, `Routes.mirrorCategoryDetail(key)` and the
`MIRROR_CATEGORY_DETAIL` destination all existed, but nothing joined them, so a
row tap did nothing ([da1021-C]).

What it grades (batch-7 hardening)
----------------------------------
The original arms anchored on SPELLING ("the call appears somewhere in the
file"), and every one of those spellings is satisfied by a shape that does not
work. Batch 7 re-anchors each arm on EFFECT, through the shared
lexer/ast/valueflow primitives (`_kotlin_lexer` / `_kotlin_ast` /
`_kotlin_valueflow`) instead of a self-written comment stripper + brace scanner:

* the navigate call must sit on a LIVE path — `if (false) navigate(...)` is red;
* the route argument must be derived from the tapped row (`{ cat -> … cat }`),
  not a constant — a hard-coded key opens the wrong category for every row;
* the resolver's value must SURVIVE the chain it is written into — `?.takeIf {
  false }` / `?.let { null }` nullify it, `null.also { … }` discards it;
* the resolver predicate must DISCRIMINATE and be satisfiable — `it.key == key
  || true` resolves every key to the first entry, `&& false` resolves none;
* the detail screen call must be REACHABLE (an always-true null branch makes
  the `else` dead) and `category` must not be re-assigned after it is resolved
  (the later write wins, the screen never sees the resolved value).

Round-3 (dual-gate) hardening — the SEAMS between those anchors
---------------------------------------------------------------
Round 2 graded both ends of the chain and nothing in between, so a patch that
sat between them stayed green in both gates:

* `MirrorsSectionView` built every row with `MirrorCategoryRow(category =
  category, onClick = { onNavigate(<const>) })` — the row's identity never
  reached the tap, every row opened the same category, and neither gate looked
  at the argument. Now the tap's argument must derive from the row's own
  `category =` expression (one local binding hop is followed).
* `MirrorCategoryDetailScreen` read `vm.results[ALPINE]` /
  `MirrorCatalog.mirrors(ALPINE)` under a "Python pip" title. Now every
  category-scoped read in that function must derive from its `category`
  parameter.
* `fromKey` could invert its predicate (`it.key != key`) — the old regex listed
  `!=` next to `==` as a legal key comparison. Now the predicate is EVALUATED
  symbolically against the enum's own declared keys: each key must select its
  own entry, must not select any other, and an unknown key must select
  nothing.

Usage: python3 mirror_category_route_check.py [ROOT]   (ROOT = repo root, default ".")
       python3 mirror_category_route_check.py --selftest
Exit:  0 = gate green, 1 = violations (or selftest failure).
"""

import re
import sys
import tempfile
from pathlib import Path

from _kotlin_ast import (
    const_bool,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
)
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_valueflow import (
    call_args,
    contains_ident,
    expr_end,
    guard_feasible,
    scope_calls,
    value_sources,
)

REL_NAV = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
PROD_KT_GLOB = "src/android/app/src/main/**/*.kt"
REL_CAT = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorCategory.kt"
REL_SCREEN = ("src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/"
              "MirrorSettingsScreen.kt")

VIOLATIONS = []


def fail(path, msg):
    VIOLATIONS.append(f"{path}: {msg}")


# ---------------------------------------------------------------- views -----

def views(src):
    """(code, literal) — both length-equal to `src`, so positions are portable.

    `code` = comments + string interiors blanked (structure, feasibility);
    `literal` = only comments blanked (string contents, route patterns).
    Getting this pair wrong is how a criterion silently grades the wrong
    channel: a template hole such as `"x/${seg(id)}"` is invisible in `code`.
    """
    return mask_code(src), mask_comments(src)


# --------------------------------------------------------- block algebra ----

def call_args_text(text, open_paren_pos, index=0):
    """Text of argument `index` of the call whose '(' is at [open_paren_pos]."""
    spans = call_args(text, open_paren_pos)
    if len(spans) <= index:
        return None
    a, b = spans[index]
    return text[a:b]


def composable_span(nav, route_expr):
    """(start, end) of the `composable(<route_expr>, …) { … }` destination.

    The destination is the unit that must be wired, so every arm below grades a
    block rather than the whole file: `safeNavigate(Routes.mirrorCategoryDetail
    (…))` somewhere else in AppNavigation must not vouch for this screen.
    """
    for m in re.finditer(r"\bcomposable\s*\(", nav):
        args = call_args(nav, m.end() - 1)
        if not any(route_expr in nav[a:b] for a, b in args):
            continue
        close = match_bracket(nav, m.end() - 1)
        if close < 0:
            return None
        brace = nav.find("{", close)
        if brace < 0:
            return None
        end = match_bracket(nav, brace)
        if end < 0:
            return None
        return brace, end + 1
    return None


def block_inner(block_text):
    """(offset, inner) — a lambda/`{ … }` block without its braces and without
    its `params ->` head, so `stmt_spans` sees real top-level statements.

    Without this step every statement of a lambda lives at brace depth ≥ 1 and
    `stmt_spans` returns ONE span covering the whole block (that is how a
    re-assignment hides from a statement-level criterion).
    """
    t = block_text
    off = 0
    i = 0
    n = len(t)
    while i < n and t[i] in " \t\r\n":
        i += 1
    if i < n and t[i] == "{":
        close = match_bracket(t, i)
        t = t[i + 1:close] if close > 0 else t[i + 1:]
        off = i + 1
    m = re.match(r"\s*(?:[A-Za-z_]\w*\s*(?:,\s*[A-Za-z_]\w*\s*)*)?->", t)
    if m:
        off += m.end()
        t = t[m.end():]
    return off, t


def lambda_params(block_text):
    """Parameters of the tap lambda: `{ cat -> … }` -> ['cat']; `{ … }` -> ['it']."""
    m = re.match(r"\s*\{\s*([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s*->", block_text)
    if m:
        return [p.strip() for p in m.group(1).split(",")]
    return ["it"]


# ------------------------------------------------------------- dataflow ----

def in_discarded_lambda(text, idx):
    """True when the expression at [idx] sits in `.also` / `.apply` / `.onEach`
    / `.forEach`, whose VALUE is the receiver — a call there cannot change the
    value the surrounding expression publishes.
    """
    for name, start, _open, end in scope_calls(text):
        if start < idx < end and name in ("also", "apply", "onEach", "forEach"):
            return True
    return False


DISCARDING = ("also", "apply", "onEach", "forEach")
VALUE_BLOCKS = ("let", "run", "with")


def resolver_value_kept(expr):
    """Does the declaration's FINAL value still derive from the resolver call?

    Walks the scope-call chain from the outside in and applies each call's own
    value rule — the shot the original arm missed, because `value_sources()`
    stops at the first `.let` and never sees the `.takeIf { false }` welded onto
    its tail. Returns None when the value survives, else a short reason.
    """
    e = expr.strip()
    for _ in range(8):
        calls = scope_calls(e)
        if not calls:
            return None if "MirrorCategory.fromKey(" in e else "resolver-lost"
        name, start, open_pos, end = max(calls, key=lambda c: c[3])
        tail = e[end:].strip()
        if tail and not tail.startswith((".", "?.", "?:")):
            # a trailing operator/expression we do not model: fail-closed only
            # when the resolver has vanished entirely from the expression.
            return None if "MirrorCategory.fromKey(" in e else "resolver-lost"
        if name in DISCARDING:
            e = e[:start].strip()          # value == receiver
            continue
        if name in VALUE_BLOCKS:
            body = e[open_pos + 1:end - 1]
            spans = stmt_spans(body)
            if not spans:
                return "value-nullified"
            a, b = spans[-1]
            e = body[a:b].strip()
            if not e:
                return "value-nullified"
            continue
        if name in ("takeIf", "takeUnless"):
            body = e[open_pos + 1:end - 1].strip()
            cond = body if name == "takeIf" else f"!({body})"
            if not guard_feasible(cond):
                return f"value-nullified-by-{name}"
            e = e[:start].strip()          # feasible: value may be the receiver
            continue
        return None if "MirrorCategory.fromKey(" in e else "resolver-lost"
    return "resolver-lost"


# ---------------------------------------------------------- tap -> route ---

NAV_CALL_RE = re.compile(r"\b(?:safe)?[Nn]avigate\s*\(")
BUILDER = "Routes.mirrorCategoryDetail"


def derives_from_params(arg_text, params):
    """Is the route argument's value derived from the tapped row's parameter?"""
    if not arg_text or not arg_text.strip():
        return False
    return any(contains_ident(src, p) for src in value_sources(arg_text) for p in params)


def click_route(body, params):
    """(navigates, param_derived) for one `onMirrorCategoryClick` body.

    Two real spellings are accepted, both graded on flow rather than text:
        navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))
        val route = Routes.mirrorCategoryDetail(cat.key); …safeNavigate(route)
    """
    navs = [
        m for m in NAV_CALL_RE.finditer(body)
        if reachable(body, m.start()) and not in_discarded_lambda(body, m.start())
    ]
    hits, param_ok = [], []

    def grade(open_paren):
        arg = call_args_text(body, open_paren, 0)
        hits.append(True)
        param_ok.append(derives_from_params(arg, params))

    # spelling 1: the builder's value is handed straight to a live navigate
    for m in navs:
        for a, b in call_args(body, m.end() - 1):
            for bm in re.finditer(re.escape(BUILDER) + r"\s*\(", body[a:b]):
                grade(a + bm.end() - 1)

    # spelling 2: the route is kept in a local, then navigated
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*"
                         + re.escape(BUILDER) + r"\s*\(", body):
        if not reachable(body, m.start()):
            continue
        local = m.group(1)
        for n in navs:
            args = call_args(body, n.end() - 1)
            if any(contains_ident(body[a:b], local) for a, b in args):
                grade(m.end() - 1)

    return bool(hits), any(param_ok)


# -------------------------------------------------------- resolver predicate

def predicate_fault(pred):
    """Why a `firstOrNull { … }` predicate cannot discriminate, or None."""
    for a, b in split_top(pred, ("||",)):
        if const_bool(pred[a:b]) is True:
            return (f"the `||` arm `{pred[a:b].strip()}` is always true — the "
                    "predicate is a tautology, so every key resolves to the first entry")
    for a, b in split_top(pred, ("&&",)):
        if const_bool(pred[a:b]) is False:
            return (f"the `&&` arm `{pred[a:b].strip()}` is always false — the "
                    "predicate is unsatisfiable, so no key ever resolves")
    if const_bool(pred) is True:
        return ("the predicate folds to `true` — every key resolves to the first "
                "entry (only fail-closed by luck of the enum being non-empty)")
    return None


# ------------------------------------------------- rounded-3: row / detail ---

SAFE_IDENT = re.compile(r"[A-Za-z_]\w*")


def _ident_sources(code, expr):
    """`value_sources()` + ONE hop of local binding (`val x = <expr>`).

    A row may hand the callback a name it bound itself (`val rowCategory =
    category`); the bare source list would be `['rowCategory']` and a plain
    set comparison would red a legal row. Following one declaration hop keeps
    the criterion on the flow instead of on one spelling.
    """
    out = list(value_sources(expr))
    seen = set()
    for s in list(out):
        name = s.strip()
        if not SAFE_IDENT.fullmatch(name) or name in seen:
            continue
        seen.add(name)
        m = re.search(r"\b(?:val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]*)?=\s*",
                      code)
        if m:
            out.extend(value_sources(code[m.end():expr_end(code, m.end())]))
    return out


ROW_CALL_RE = re.compile(r"(?<!fun )\bMirrorCategoryRow\s*\(")


def row_tap_problems(code, path):
    """Every `MirrorCategoryRow(...)` must hand the tap ITS OWN category.

    The seam both round-2 anchors skipped: the destination lambda in
    AppNavigation resolves `cat.key` correctly and the detail screen receives a
    resolved `category`, but the row in between may pass a constant. Then every
    row opens the same category while both ends stay green.
    """
    out = []
    for m in ROW_CALL_RE.finditer(code):
        args = call_args(code, m.end() - 1)
        cat = click = None
        for a, b in args:
            seg = code[a:b]
            mm = re.match(r"\s*category\s*=\s*", seg)
            if mm and cat is None:
                cat = (a + mm.end(), b)
            mm = re.match(r"\s*onClick\s*=\s*", seg)
            if mm and click is None:
                click = (a + mm.end(), b)
        if cat is None or click is None:
            out.append(
                f"{path}: MirrorCategoryRow( is called without named `category =` / "
                "`onClick =` arguments, so the row's identity cannot be graded "
                "(fail-closed)")
            continue
        row_expr = code[cat[0]:cat[1]].strip()
        row_sources = [s.strip() for s in _ident_sources(code, row_expr) if s.strip()]
        lam = code[click[0]:click[1]]
        carried = False
        for cm in re.finditer(r"(?<![\w.])([A-Za-z_]\w*)\s*\(", lam):
            for a, b in call_args(lam, cm.end() - 1):
                for s in _ident_sources(code, lam[a:b]):
                    s = s.strip()
                    if not s:
                        continue
                    if any(s == t or contains_ident(s, t) or contains_ident(t, s)
                           for t in row_sources):
                        carried = True
        if not carried:
            out.append(
                f"{path}: the MirrorCategoryRow tap does not carry the row's own "
                f"category (`{row_expr[:50]}` never reaches the onClick callback's "
                "argument) — every row opens the same category")
    return out


DETAIL_FUN = "MirrorCategoryDetailScreen"


def detail_body_problems(code, path):
    """The detail screen must READ the category it was handed.

    Graded on the tree: every `results[…]` read and every `MirrorCatalog.xxx(…)`
    call inside the function body must derive from the function's own
    `category` parameter. A `MirrorCategory.ALPINE` read under a "Python pip"
    title shows and edits the wrong list while the route stays perfect.
    """
    fb = function_body(code, DETAIL_FUN)
    if fb is None:
        return []                      # arm 1 grades "defined AND called"
    body = code[fb[0]:fb[1]]
    sig = re.search(r"\bfun\s+" + DETAIL_FUN + r"\s*\(", code)
    params = []
    if sig:
        for a, b in call_args(code, sig.end() - 1):
            pm = re.match(r"\s*(?:val\s+)?([A-Za-z_]\w*)\s*:\s*([^=,]+)", code[a:b])
            if pm and "MirrorCategory" in pm.group(2):
                params.append(pm.group(1))
    if not params:
        params = ["category"]
    reads = []
    for m in re.finditer(r"\bresults\b", body):
        j = m.end()
        while j < len(body) and body[j] in " \t\r\n":
            j += 1
        if j < len(body) and body[j] == "[":
            close = match_bracket(body, j)
            if close > 0:
                reads.append((m.start(), body[j + 1:close], "results[...]"))
        elif body.startswith(".get(", j):
            close = match_bracket(body, j + 4)
            if close > 0:
                reads.append((m.start(), body[j + 5:close], "results.get(...)"))
    for m in re.finditer(r"\bMirrorCatalog\s*\.\s*([A-Za-z_]\w*)\s*\(", body):
        for a, b in call_args(body, m.end() - 1):
            reads.append((m.start(), body[a:b], f"MirrorCatalog.{m.group(1)}(...)"))
    if not reads:
        return [f"{path}: {DETAIL_FUN} never reads this category's results/mirrors "
                "— the screen cannot show what the route resolved (fail-closed)"]
    out = []
    for pos, expr, what in reads:
        sources = _ident_sources(code, expr)
        if not any(contains_ident(s, p) for s in sources for p in params):
            line = body[:pos].count("\n") + 1
            out.append(
                f"{path}: {DETAIL_FUN} reads `{what}` with `{expr.strip()[:50]}` instead "
                f"of its `{'/'.join(params)}` parameter (line ~{line}) — the screen "
                "shows and edits another category's mirrors")
    return out


# --------------------------------------------- fromKey predicate semantics --

UNKNOWN_KEY = "zz-not-a-real-category-key"


def enum_key_table(cat_lit):
    """[(ENTRY, key_literal)] in declaration order, read from the enum source."""
    out = []
    for m in re.finditer(
            r'(?m)^\s*([A-Z][A-Za-z0-9_]*)\s*\(\s*"([^"]*)"\s*[,)]', cat_lit):
        out.append((m.group(1), m.group(2)))
    return out


def _top_level_op(text, ops):
    """(lhs, op, rhs) at the first top-level occurrence of any op, else None."""
    depth = 0
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0:
            for op in ops:
                if text.startswith(op, i):
                    return text[:i], op, text[i + len(op):]
        i += 1
    return None


def _pred_side_value(side, entry_key, arg_key, entry_ident, key_prop, param):
    """The string a side of a comparison evaluates to, or None when unmodelled."""
    s = strip_parens(side.strip())
    if not s:
        return None
    if re.fullmatch(re.escape(entry_ident) + r"\s*\.\s*" + re.escape(key_prop), s):
        return entry_key
    if re.fullmatch(re.escape(param), s):
        return arg_key
    m = re.fullmatch(r'"([^"]*)"', s)
    if m:
        return m.group(1)
    m = re.fullmatch(r"(.+?)\s*\.\s*(lowercase|uppercase|trim)\s*\(\s*\)", s)
    if m:
        v = _pred_side_value(m.group(1), entry_key, arg_key, entry_ident, key_prop, param)
        if v is None:
            return None
        return {"lowercase": v.lower(), "uppercase": v.upper(),
                "trim": v.strip()}[m.group(2)]
    return None


def _eval_leaf(leaf, entry_key, arg_key, entry_ident, key_prop, param):
    t = leaf.strip()
    if not t:
        return None
    parts = _top_level_op(t, ("==", "!="))
    if parts and parts[1] in ("==", "!="):
        lv = _pred_side_value(parts[0], entry_key, arg_key, entry_ident, key_prop, param)
        rv = _pred_side_value(parts[2], entry_key, arg_key, entry_ident, key_prop, param)
        if lv is None or rv is None:
            return None
        return (lv == rv) if parts[1] == "==" else (lv != rv)
    m = re.search(r"\.\s*(equals|equalsIgnoreCase|contains|startsWith)\s*\(", t)
    if m and m.start() > 0:
        recv = _pred_side_value(t[:m.start()], entry_key, arg_key, entry_ident,
                                key_prop, param)
        args = call_args(t, m.end() - 1)
        if recv is None or not args:
            return None
        a0, b0 = args[0]
        argv = _pred_side_value(t[a0:b0], entry_key, arg_key, entry_ident, key_prop, param)
        if argv is None:
            return None
        rest = t[m.end():]
        ignore = (m.group(1) == "equalsIgnoreCase"
                  or ("ignoreCase" in rest and "true" in rest))
        if ignore:
            recv, argv = recv.lower(), argv.lower()
        name = m.group(1)
        if name in ("equals", "equalsIgnoreCase"):
            return recv == argv
        if name == "contains":
            return argv in recv
        return recv.startswith(argv)
    parts = _top_level_op(t, (" in ",))
    if parts:
        lv = _pred_side_value(parts[0], entry_key, arg_key, entry_ident, key_prop, param)
        rhs = parts[2].strip()
        m = re.match(r"(?:setOf|listOf|arrayOf|mutableSetOf)\s*(?:<[^>]*>)?\s*\(", rhs)
        if lv is None or not m:
            return None
        close = match_bracket(rhs, m.end() - 1)
        if close < 0:
            return None
        lits = [rhs[a:b] for a, b in call_args(rhs, m.end() - 1)]
        vals = [_pred_side_value(l, entry_key, arg_key, entry_ident, key_prop, param)
                for l in lits]
        if any(v is None for v in vals):
            return None
        return lv in vals
    return None


def eval_predicate(pred, entry_key, arg_key, entry_ident, key_prop, param):
    """True / False / None (unmodelled) for the predicate under one assignment."""
    e = strip_parens(pred.strip())
    if not e:
        return None
    parts = split_top(e, ("||",))
    if len(parts) > 1:
        vals = [eval_predicate(e[a:b], entry_key, arg_key, entry_ident, key_prop, param)
                for a, b in parts]
        if any(v is True for v in vals):
            return True
        return False if all(v is False for v in vals) else None
    parts = split_top(e, ("&&",))
    if len(parts) > 1:
        vals = [eval_predicate(e[a:b], entry_key, arg_key, entry_ident, key_prop, param)
                for a, b in parts]
        if any(v is False for v in vals):
            return False
        return True if all(v is True for v in vals) else None
    m = re.match(r"^!\s*(?!=)", e)
    if m:
        inner = eval_predicate(e[m.end():], entry_key, arg_key, entry_ident, key_prop, param)
        return None if inner is None else (not inner)
    return _eval_leaf(e, entry_key, arg_key, entry_ident, key_prop, param)


def fromkey_polarity_problem(cat_lit):
    """Evaluate `fromKey`'s predicate against the enum's own declared keys.

    The question is no longer "does the predicate mention the key" (which
    `!=` satisfied) but "does key K select the entry whose key IS K, and only
    that entry". Reversed polarity, a tautology, an unsatisfiable conjunction
    and a non-fail-closed unknown-key path all fall out of the same evaluation.
    """
    entries = enum_key_table(cat_lit)
    if len(entries) < 2:
        return None
    header = re.search(r"enum class\s+\w+\s*\(([^)]*)\)", cat_lit)
    key_prop = "key"
    if header:
        mm = re.search(r"\bval\s+([A-Za-z_]\w*)\s*:\s*String", header.group(1))
        if mm:
            key_prop = mm.group(1)
    sig = re.search(r"fun\s+fromKey\(\s*([A-Za-z_]\w*)\s*:", cat_lit)
    param = sig.group(1) if sig else "key"
    raw = re.search(r"firstOrNull\s*\{([^{}]*)\}", cat_lit)
    if not raw:
        return None
    pred = raw.group(1)
    head = re.match(r"\s*([A-Za-z_]\w*)\s*->", pred)
    entry_ident = "it"
    if head:
        entry_ident = head.group(1)
        pred = pred[head.end():]
    for entry, key in entries:
        if eval_predicate(pred, key, key, entry_ident, key_prop, param) is False:
            return (f"the predicate `{pred.strip()}` REJECTS `{entry}`'s own key "
                    f"\"{key}\" — a key is selected by an entry that does not match it "
                    "(reversed comparison polarity: `!=` in place of `==`)")
    for entry, key in entries:
        for other, okey in entries:
            if other == entry:
                continue
            if eval_predicate(pred, okey, key, entry_ident, key_prop, param) is True:
                return (f"the predicate `{pred.strip()}` lets key \"{key}\" select "
                        f"`{other}` as well — it does not discriminate by key")
    for entry, key in entries:
        if eval_predicate(pred, key, UNKNOWN_KEY, entry_ident, key_prop, param) is True:
            return ("an unknown key still selects an entry — the fail-closed contract "
                    "(unknown key -> null) is broken")
    return None


# ------------------------------------------------------------- the gate ----

def reachable(text, pos):
    """Is the code at [pos] reachable? Every enclosing condition must be
    feasible, and the `else` side is graded on `!(cond)` — parenthesised.

    `_kotlin_valueflow.path_feasible` prefixes a bare `!` onto the condition,
    which for a compound condition (`a == null || true`) rebinds as
    `(!a) == null || true` → always true → a dead `else` looks live. This gate
    cannot fix the shared primitive (frozen for this batch), so it negates
    correctly here and the gap is registered as a residual for the main session.
    """
    for cond, t_start, t_end, e_start, e_end in if_blocks(text):
        if t_start <= pos < t_end:
            if not guard_feasible(cond):
                return False
        elif e_start is not None and e_start <= pos < e_end:
            if not guard_feasible(f"!({cond})"):
                return False
    return True


def check(root: Path) -> None:
    # --- arm 1: MirrorCategoryDetailScreen defined AND called (>=2 in prod) ---
    hits = []
    for p in root.glob(PROD_KT_GLOB):
        try:
            lit = mask_comments(p.read_text(encoding="utf-8"))
        except KotlinLexError as exc:
            fail(str(p.relative_to(root)), f"does not lex ({exc}) — fail-closed")
            continue
        if "MirrorCategoryDetailScreen(" in lit:
            hits.append(str(p))
    if len(hits) < 2:
        fail("production sources", "MirrorCategoryDetailScreen( appears fewer than 2 times "
             f"(definition + navigation call) — found in {sorted(hits) or 'nothing'}")

    # --- arm 1c: the row tap carries the row's OWN category; the detail screen
    # --- reads the category it was handed (round-3 dual-gate seams) ----------
    for p in root.glob(PROD_KT_GLOB):
        try:
            code = mask_code(p.read_text(encoding="utf-8"))
        except KotlinLexError:
            continue                     # arm 1 already reported this file
        rel = str(p.relative_to(root))
        VIOLATIONS.extend(row_tap_problems(code, rel))
        VIOLATIONS.extend(detail_body_problems(code, rel))

    nav_path = root / REL_NAV
    if not nav_path.exists():
        fail(REL_NAV, "missing source file")
        return
    try:
        nav, nav_lit = views(nav_path.read_text(encoding="utf-8"))
    except KotlinLexError as exc:
        fail(REL_NAV, f"does not lex ({exc}) — fail-closed")
        return

    # --- arm 2: ROOTFS_MANAGEMENT wires the tap to a real navigation ---
    span = composable_span(nav, "Routes.ROOTFS_MANAGEMENT")
    if span is None:
        fail(REL_NAV, "ROOTFS_MANAGEMENT composable destination not found")
    else:
        rootfs = nav[span[0]:span[1]]
        m_click = re.search(r"onMirrorCategoryClick\s*=\s*\{", rootfs)
        if not m_click:
            fail(REL_NAV, "ROOTFS_MANAGEMENT composable does not pass onMirrorCategoryClick")
        else:
            brace = rootfs.find("{", m_click.start())
            end = match_bracket(rootfs, brace)
            click_body = rootfs[brace:end + 1] if end > 0 else rootfs[brace:]
            navigates, param_ok = click_route(click_body, lambda_params(click_body))
            if not navigates:
                fail(REL_NAV,
                     "onMirrorCategoryClick does not navigate via Routes.mirrorCategoryDetail "
                     "(the builder's value must reach safeNavigate/navigate on a live path — "
                     "building the route and dropping it, or building it inside a dead branch, "
                     "leaves the row tap dead)")
            elif not param_ok:
                fail(REL_NAV,
                     "onMirrorCategoryClick navigates with a route that does not carry the tapped "
                     "row's key (the builder argument must derive from the tap lambda's parameter "
                     "— a constant route opens the wrong category for every row)")

    # --- arm 1b: the detail destination resolves the route arg into `category` ---
    dspan = composable_span(nav, "Routes.MIRROR_CATEGORY_DETAIL")
    if dspan is None:
        fail(REL_NAV, "MIRROR_CATEGORY_DETAIL composable destination not found")
    else:
        detail = nav[dspan[0]:dspan[1]]
        screen_call = re.search(r"MirrorCategoryDetailScreen\s*\(", detail)
        if not screen_call:
            fail(REL_NAV, "MirrorCategoryDetailScreen is never called inside the "
                          "MIRROR_CATEGORY_DETAIL destination")
        else:
            bound = None
            mm = re.search(r"\bcategory\s*=\s*([A-Za-z_]\w*)\b",
                           call_args_text(detail, screen_call.end() - 1) or "")
            if mm:
                bound = mm.group(1)
            if bound is None:
                fail(REL_NAV,
                     "MirrorCategoryDetailScreen is not called with a `category = <ident>` argument")
            else:
                # reachability: an always-true null branch makes the screen dead
                if not reachable(detail, screen_call.start()):
                    fail(REL_NAV,
                         "the MirrorCategoryDetailScreen call is unreachable — the guard around it "
                         "is always taken (`if (category == null || true)` / an unsatisfiable "
                         "`else`), so the destination renders nothing for any key")
                inner_off, inner = block_inner(detail)
                inner_spans = stmt_spans(inner)
                writes = []
                for a, b in inner_spans:
                    mw = re.match(r"\s*(?:(val|var)\s+)?" + re.escape(bound)
                                  + r"\s*(?::[^=\n]+?)?=(?!=)", inner[a:b])
                    if mw:
                        writes.append((a, b, mw.end(), "decl" if mw.group(1) else "assign"))
                if not writes:
                    fail(REL_NAV, f"`{bound}` passed to MirrorCategoryDetailScreen has no local "
                                  "declaration in the destination (the screen would never receive "
                                  "a resolved category)")
                else:
                    a, b, rhs_off, kind = writes[0]
                    if kind != "decl":
                        fail(REL_NAV, f"`{bound}` is assigned but never declared as a local value "
                                      "in the destination")
                    if len(writes) > 1:
                        later = [w for w in writes[1:]]
                        if later:
                            fail(REL_NAV, f"`{bound}` is re-assigned after it is resolved "
                                          f"({len(writes)} writes, first at line "
                                          f"{inner[:a].count(chr(10)) + 1}) — the later write wins, "
                                          "so the detail screen never receives the resolved category")
                    rhs_code = inner[a + rhs_off:b]
                    base = dspan[0] + inner_off + a + rhs_off
                    rhs_lit = nav_lit[base:base + (b - a - rhs_off)]
                    if 'getString("categoryId")' not in rhs_lit:
                        fail(REL_NAV, f"`{bound}` is not read from the route argument "
                                      "(`getString(\"categoryId\")` missing) — the deep-link key "
                                      "never reaches the resolver")
                    pos = rhs_code.find("MirrorCategory.fromKey(")
                    if pos < 0:
                        fail(REL_NAV, f"`{bound}` never goes through MirrorCategory.fromKey — the "
                                      "route key is not resolved")
                    elif in_discarded_lambda(rhs_code, pos):
                        fail(REL_NAV, f"the MirrorCategory.fromKey(...) result is thrown away inside "
                                      f".also/.apply, so `{bound}` carries the receiver (null), not "
                                      "the resolved category — the detail screen becomes unreachable")
                    else:
                        reason = resolver_value_kept(rhs_code)
                        if reason:
                            fail(REL_NAV, f"the MirrorCategory.fromKey(...) result does not survive "
                                          f"the chain it is written into ({reason}) — `{bound}` ends "
                                          "up null, so the detail screen is unreachable")

    # --- arm 3: fromKey resolver exists, fail-closed AND discriminating ---
    cat_path = root / REL_CAT
    cat = ""
    cat_lit = ""
    if cat_path.exists():
        try:
            cat, cat_lit = views(cat_path.read_text(encoding="utf-8"))
        except KotlinLexError as exc:
            fail(REL_CAT, f"does not lex ({exc}) — fail-closed")
            return
    else:
        for p in root.glob(PROD_KT_GLOB):
            try:
                t = mask_comments(p.read_text(encoding="utf-8"))
            except KotlinLexError:
                continue
            if "fromKey(" in t and "MirrorCategory" in t:
                cat, cat_lit = mask_code(t), t
                break
    if not cat:
        fail(REL_CAT, "MirrorCategory source (with fromKey resolver) not found")
    else:
        if "fun fromKey(" not in cat:
            fail(REL_CAT, "MirrorCategory.fromKey resolver missing (route param cannot resolve)")
        else:
            m = re.search(r"fun fromKey\([^)]*\)([^=]*)=(.*)", cat)
            ret = m.group(0) if m else ""
            if "MirrorCategory?" not in ret:
                fail(REL_CAT, "fromKey must return MirrorCategory? (fail-closed on unknown key)")
            if "firstOrNull" not in cat:
                fail(REL_CAT, "fromKey must resolve via firstOrNull (unknown key -> null)")
            # fail-closed alone is not enough: the predicate must DISCRIMINATE by
            # the argument it was handed. A tautology (`firstOrNull { true }` /
            # `{ it.key == key || true }`) still returns null for an unknown key
            # as long as the enum is non-empty — and resolves every known key to
            # the first entry, so "pip"/"npm" would all open the ALPINE list.
            sig = re.search(r"fun fromKey\(\s*([A-Za-z_]\w*)\s*:", cat)
            param = sig.group(1) if sig else "key"
            pred = re.search(r"firstOrNull\s*\{([^{}]*)\}", cat)
            if not pred:
                fail(REL_CAT, "fromKey's firstOrNull predicate is not readable (fail-closed)")
            elif not re.search(
                    r"(?:==|!=|equals|contains|\bin\b)\s*[^;}]*\b" + re.escape(param) + r"\b"
                    r"|\b" + re.escape(param) + r"\b\s*(?:==|!=)", pred.group(1)):
                fail(REL_CAT, f"fromKey's predicate never compares against its `{param}` argument — "
                              "every key would resolve to the same entry (only fail-closed by luck "
                              "of the enum being non-empty)")
            else:
                fault = predicate_fault(pred.group(1).strip())
                if fault:
                    fail(REL_CAT, f"fromKey's predicate does not discriminate: {fault}")
                else:
                    polarity = fromkey_polarity_problem(cat_lit)
                    if polarity:
                        fail(REL_CAT,
                             "fromKey does not resolve the key it was handed: " + polarity)

    # --- arm 4: route pattern + builder encode through seg() ---
    # string contents live in the literal view; the code view has them blanked.
    if 'const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryId}"' not in nav_lit:
        fail(REL_NAV, "Routes.MIRROR_CATEGORY_DETAIL pattern missing")
    if 'fun mirrorCategoryDetail(categoryId: String) = "mirror_category/${seg(categoryId)}"' not in nav_lit:
        fail(REL_NAV, "Routes.mirrorCategoryDetail does not encode through seg()")


# ---------------- selftest fixtures (minimal synthetic repo) ----------------

FIXTURE_CAT = '''package com.rikkaminis.app.ui.sandbox

enum class MirrorCategory(
    val key: String,
    val displayName: String,
    val configPath: String,
) {
    ALPINE("alpine", "Alpine APK", "etc/apk/repositories"),
    PIP("pip", "Python pip", "etc/pip/pip.conf"),
    NPM("npm", "Node.js npm", "root/.npmrc");

    companion object {
        fun fromKey(key: String): MirrorCategory? =
            entries.firstOrNull { it.key == key }
    }
}
'''

FIXTURE_SCREEN = '''package com.rikkaminis.app.ui.sandbox

@Composable
fun MirrorsSectionView(onNavigate: (MirrorCategory) -> Unit) {
    for (category in MirrorCategory.entries) {
        MirrorCategoryRow(category = category, onClick = { onNavigate(category) })
    }
}

@Composable
private fun MirrorCategoryRow(category: MirrorCategory, onClick: () -> Unit) {
    Text(category.displayName)
}

@Composable
fun MirrorCategoryDetailScreen(
    category: MirrorCategory,
    onBack: () -> Unit,
) {
    val sortedResults = MirrorSpeedTestViewModel.results[category] ?: emptyList()
    val allForCategory = MirrorCatalog.mirrors(category)
    Text(category.displayName)
}
'''

FIXTURE_NAV = '''package com.rikkaminis.app.ui.navigation

import com.rikkaminis.app.ui.sandbox.MirrorCategory
import com.rikkaminis.app.ui.sandbox.MirrorCategoryDetailScreen

object Routes {
    internal fun seg(v: String): String = android.net.Uri.encode(v)
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryId}"
    fun mirrorCategoryDetail(categoryId: String) = "mirror_category/${seg(categoryId)}"
}

@Composable
fun AppNavigationRoot(navController: NavController) {
    composable(Routes.ROOTFS_MANAGEMENT) {
        RootfsManagementScreen(
            onMirrorCategoryClick = { cat ->
                navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))
            },
        )
    }
    composable(
        Routes.MIRROR_CATEGORY_DETAIL,
        arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val category = backStackEntry.arguments
            ?.getString("categoryId")
            ?.let { MirrorCategory.fromKey(it) }
        if (category == null) {
            LaunchedEffect(Unit) { navController.safePopBackStack() }
        } else {
            MirrorCategoryDetailScreen(category = category, onBack = { navController.safePopBackStack() })
        }
    }
}
'''


def write_fixture(root: Path) -> None:
    for rel, content in (
        (REL_CAT, FIXTURE_CAT),
        (REL_SCREEN, FIXTURE_SCREEN),
        (REL_NAV, FIXTURE_NAV),
    ):
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")


ARMS = 0


def selftest() -> None:
    global ARMS
    ok = True
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        # compliant fixture must pass
        write_fixture(root)
        VIOLATIONS.clear()
        check(root)
        ARMS += 1
        if VIOLATIONS:
            ok = False
            print(f"SELFTEST compliant fixture unexpectedly failed: {VIOLATIONS}")
        else:
            print("SELFTEST A (compliant fixture) GREEN")

        # fixture B: drop the navigation call (screen defined but never called) -> must fail
        write_fixture(root)
        (root / REL_NAV).write_text(
            FIXTURE_NAV.replace("Routes.mirrorCategoryDetail(cat.key)", "Routes.SETTINGS"),
            encoding="utf-8",
        )
        VIOLATIONS.clear()
        check(root)
        ARMS += 1
        if not any("onMirrorCategoryClick" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture B (drop onMirrorCategoryClick) did not trip the gate")
        else:
            print("SELFTEST B (drop onMirrorCategoryClick) RED as expected")

        # fixture C: fromKey not fail-closed (returns a default instead of null) -> must fail
        write_fixture(root)
        (root / REL_CAT).write_text(
            FIXTURE_CAT.replace(
                "fun fromKey(key: String): MirrorCategory? =\n            entries.firstOrNull { it.key == key }",
                "fun fromKey(key: String): MirrorCategory =\n            entries.firstOrNull { it.key == key } ?: MirrorCategory.ALPINE",
            ),
            encoding="utf-8",
        )
        VIOLATIONS.clear()
        check(root)
        ARMS += 1
        if not any("fail-closed" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture C (fromKey not fail-closed) did not trip the gate")
        else:
            print("SELFTEST C (fromKey not fail-closed) RED as expected")

        # fixture D: builder does not encode through seg() -> must fail
        write_fixture(root)
        (root / REL_NAV).write_text(
            FIXTURE_NAV.replace(
                'fun mirrorCategoryDetail(categoryId: String) = "mirror_category/${seg(categoryId)}"',
                'fun mirrorCategoryDetail(categoryId: String) = "mirror_category/$categoryId"',
            ),
            encoding="utf-8",
        )
        VIOLATIONS.clear()
        check(root)
        ARMS += 1
        if not any("seg()" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture D (raw mirrorCategoryDetail) did not trip the gate")
        else:
            print("SELFTEST D (raw mirrorCategoryDetail) RED as expected")

        # ---- witnesses: shapes that used to slip through -------------------
        def expect_red(label: str, nav_text: str = None, cat_text: str = None,
                       screen_text: str = None, needle: str = ""):
            nonlocal ok
            global ARMS
            ARMS += 1
            write_fixture(root)
            if nav_text is not None:
                (root / REL_NAV).write_text(nav_text, encoding="utf-8")
            if cat_text is not None:
                (root / REL_CAT).write_text(cat_text, encoding="utf-8")
            if screen_text is not None:
                (root / REL_SCREEN).write_text(screen_text, encoding="utf-8")
            VIOLATIONS.clear()
            check(root)
            if not any(needle in v for v in VIOLATIONS):
                ok = False
                print(f"SELFTEST {label} did not trip the gate (needle={needle!r}, got={VIOLATIONS})")
            else:
                print(f"SELFTEST {label} RED as expected")

        def expect_green(label: str, nav_text: str = None, cat_text: str = None,
                         screen_text: str = None):
            nonlocal ok
            global ARMS
            ARMS += 1
            write_fixture(root)
            if nav_text is not None:
                (root / REL_NAV).write_text(nav_text, encoding="utf-8")
            if cat_text is not None:
                (root / REL_CAT).write_text(cat_text, encoding="utf-8")
            if screen_text is not None:
                (root / REL_SCREEN).write_text(screen_text, encoding="utf-8")
            VIOLATIONS.clear()
            check(root)
            if VIOLATIONS:
                ok = False
                print(f"SELFTEST {label} must stay GREEN, got {VIOLATIONS}")
            else:
                print(f"SELFTEST {label} GREEN as expected")

        # W1 (axis dataflow): the resolver's value is dropped into `.also`, so
        # the published `category` is the receiver (null) and the detail
        # destination pops straight back.
        expect_red(
            "W1 (fromKey result dropped into .also)",
            nav_text=FIXTURE_NAV.replace(
                "        val category = backStackEntry.arguments\n"
                "            ?.getString(\"categoryId\")\n"
                "            ?.let { MirrorCategory.fromKey(it) }",
                "        val category: MirrorCategory? = null.also { MirrorCategory.fromKey(\"\") }",
            ),
            needle="thrown away inside .also/.apply",
        )

        # W2 (axis dataflow): fromKey keeps `firstOrNull` (and stays fail-closed
        # on an empty enum) but the predicate is a literal tautology, so every
        # category resolves to the first entry.
        expect_red(
            "W2 (fromKey predicate tautology)",
            cat_text=FIXTURE_CAT.replace("entries.firstOrNull { it.key == key }",
                                        "entries.firstOrNull { true }"),
            needle="never compares against its",
        )

        # W3 (axis dataflow): the tap builds the route and drops it — no
        # navigate call, so the row tap does nothing.
        expect_red(
            "W3 (route built then dropped, no navigate)",
            nav_text=FIXTURE_NAV.replace(
                "                navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))",
                "                Routes.mirrorCategoryDetail(cat.key)",
            ),
            needle="onMirrorCategoryClick does not navigate",
        )

        # W3b: the accepted alternative spelling (route kept in a local, then
        # navigated) must stay GREEN — the arm grades the dataflow, not one
        # spelling of it.
        expect_green(
            "W3b (route via a local, then navigate)",
            nav_text=FIXTURE_NAV.replace(
                "                navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))",
                "                val route = Routes.mirrorCategoryDetail(cat.key)\n"
                "                navController.safeNavigate(route)",
            ),
        )

        # ---- batch-7 witnesses ---------------------------------------------
        # 04-1: navigation inside a dead guard — the call exists, nothing runs.
        expect_red(
            "04-1 (navigate inside `if (false)`)",
            nav_text=FIXTURE_NAV.replace(
                "                navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))",
                "                if (false) navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))",
            ),
            needle="onMirrorCategoryClick does not navigate",
        )

        # 04-2: the route is hard-coded, so every row opens the same category.
        expect_red(
            "04-2 (route argument is a constant)",
            nav_text=FIXTURE_NAV.replace(
                "navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))",
                'navController.safeNavigate(Routes.mirrorCategoryDetail(""))',
            ),
            needle="does not carry the tapped row's key",
        )

        # 04-3: the resolved value is nullified after it is computed.
        expect_red(
            "04-3 (fromKey result nullified by `.takeIf { false }`)",
            nav_text=FIXTURE_NAV.replace(
                "            ?.let { MirrorCategory.fromKey(it) }",
                "            ?.let { MirrorCategory.fromKey(it) }\n            ?.takeIf { false }",
            ),
            needle="does not survive the chain",
        )

        # 04-3b: a FEASIBLE takeIf keeps the value — must stay green, otherwise
        # the arm is red on a legal spelling.
        expect_green(
            "04-3b (feasible `.takeIf { it != null }` stays green)",
            nav_text=FIXTURE_NAV.replace(
                "            ?.let { MirrorCategory.fromKey(it) }",
                "            ?.let { MirrorCategory.fromKey(it) }\n            ?.takeIf { it != null }",
            ),
        )

        # 04-4: the predicate compares against the key but OR-s a tautology.
        expect_red(
            "04-4 (fromKey predicate `|| true`)",
            cat_text=FIXTURE_CAT.replace("it.key == key }", "it.key == key || true }"),
            needle="tautology",
        )

        # 04-4b: `&& false` makes the predicate unsatisfiable.
        expect_red(
            "04-4b (fromKey predicate `&& false`)",
            cat_text=FIXTURE_CAT.replace("it.key == key }", "it.key == key && false }"),
            needle="unsatisfiable",
        )

        # 04-5: the null branch is always taken, so the screen call is dead.
        expect_red(
            "04-5 (detail guard `category == null || true`)",
            nav_text=FIXTURE_NAV.replace("if (category == null) {",
                                        "if (category == null || true) {"),
            needle="unreachable",
        )

        # 04-6: `category` is re-assigned after it is resolved; the later write
        # wins and the screen receives null.
        expect_red(
            "04-6 (`category` re-assigned after being resolved)",
            nav_text=FIXTURE_NAV.replace(
                "            ?.let { MirrorCategory.fromKey(it) }",
                "            ?.let { MirrorCategory.fromKey(it) }\n        var category = category\n        category = null",
            ),
            needle="re-assigned",
        )

        # ---- round-3 dual-gate witnesses --------------------------------
        # 15-1: the row hands the tap a constant instead of its own category,
        # so every row opens ALPINE while both ends of the chain stay perfect.
        expect_red(
            "R1 (row tap ignores the row's own category)",
            screen_text=FIXTURE_SCREEN.replace(
                "MirrorCategoryRow(category = category, onClick = { onNavigate(category) })",
                "MirrorCategoryRow(category = category, "
                "onClick = { onNavigate(MirrorCategory.ALPINE) })"),
            needle="does not carry the row's own category",
        )

        # R1b: the row builds but never invokes the category callback.
        expect_red(
            "R1b (row tap calls nothing)",
            screen_text=FIXTURE_SCREEN.replace(
                "onClick = { onNavigate(category) })", "onClick = { Unit })"),
            needle="does not carry the row's own category",
        )

        # R1c: a legal spelling — the row forwards through a local binding.
        expect_green(
            "R1c (row forwards through a local binding)",
            screen_text=FIXTURE_SCREEN.replace(
                "onClick = { onNavigate(category) })",
                "onClick = { val rowCategory = category; onNavigate(rowCategory) })"),
        )

        # 15-2: the detail screen reads ALPINE under a "Python pip" title.
        expect_red(
            "R2 (detail screen reads another category)",
            screen_text=FIXTURE_SCREEN.replace(
                "MirrorSpeedTestViewModel.results[category]",
                "MirrorSpeedTestViewModel.results[MirrorCategory.ALPINE]").replace(
                "MirrorCatalog.mirrors(category)",
                "MirrorCatalog.mirrors(MirrorCategory.ALPINE)"),
            needle="instead of its",
        )

        # R2b: same for a single read — the list must follow the parameter.
        expect_red(
            "R2b (detail screen mirrors another category)",
            screen_text=FIXTURE_SCREEN.replace(
                "MirrorCatalog.mirrors(category)",
                "MirrorCatalog.mirrors(MirrorCategory.PIP)"),
            needle="instead of its",
        )

        # 15-3: fromKey keeps `firstOrNull` (so it is still fail-closed) and
        # flips the comparison — every key resolves to a NON-matching entry.
        expect_red(
            "R3 (fromKey predicate `!=`)",
            cat_text=FIXTURE_CAT.replace("it.key == key }", "it.key != key }"),
            needle="REJECTS",
        )

        # R3b: a legal case-insensitive resolver must stay GREEN.
        expect_green(
            "R3b (fromKey `equals(key, ignoreCase = true)`)",
            cat_text=FIXTURE_CAT.replace(
                "it.key == key }", "it.key.equals(key, ignoreCase = true) }"),
        )

        # R3c: the entry parameter may be named — polarity still graded.
        expect_red(
            "R3c (named entry param, inverted)",
            cat_text=FIXTURE_CAT.replace(
                "entries.firstOrNull { it.key == key }",
                "entries.firstOrNull { c -> c.key != key }"),
            needle="REJECTS",
        )

    if not ok:
        sys.exit("MIRROR_CATEGORY_ROUTE_SELFTEST=FAIL")


def main() -> None:
    args = sys.argv[1:]
    if "--selftest" in args:
        selftest()
        print(f"MIRROR_CATEGORY_ROUTE_SELFTEST=PASS ({ARMS} arms)")
        return
    root = Path(args[0]) if args else Path.cwd()
    if not root.exists():
        sys.exit(f"FAIL: root {root} does not exist")
    check(root)
    if VIOLATIONS:
        print(f"FAIL: {len(VIOLATIONS)} violation(s)")
        for v in VIOLATIONS:
            print(f"  {v}")
        sys.exit(1)
    print("MIRROR_CATEGORY_ROUTE_CHECK=PASS")


if __name__ == "__main__":
    main()
