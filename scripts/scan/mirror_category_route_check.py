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
    guard_feasible,
    scope_calls,
    value_sources,
)

REL_NAV = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
PROD_KT_GLOB = "src/android/app/src/main/**/*.kt"
REL_CAT = "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorCategory.kt"

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
    if cat_path.exists():
        try:
            cat, _cat_lit = views(cat_path.read_text(encoding="utf-8"))
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
                cat = t
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
fun MirrorCategoryDetailScreen(
    category: MirrorCategory,
    onBack: () -> Unit,
) {
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
        ("src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorSettingsScreen.kt", FIXTURE_SCREEN),
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
        def expect_red(label: str, nav_text: str = None, cat_text: str = None, needle: str = ""):
            nonlocal ok
            global ARMS
            ARMS += 1
            write_fixture(root)
            if nav_text is not None:
                (root / REL_NAV).write_text(nav_text, encoding="utf-8")
            if cat_text is not None:
                (root / REL_CAT).write_text(cat_text, encoding="utf-8")
            VIOLATIONS.clear()
            check(root)
            if not any(needle in v for v in VIOLATIONS):
                ok = False
                print(f"SELFTEST {label} did not trip the gate (needle={needle!r}, got={VIOLATIONS})")
            else:
                print(f"SELFTEST {label} RED as expected")

        def expect_green(label: str, nav_text: str = None, cat_text: str = None):
            nonlocal ok
            global ARMS
            ARMS += 1
            write_fixture(root)
            if nav_text is not None:
                (root / REL_NAV).write_text(nav_text, encoding="utf-8")
            if cat_text is not None:
                (root / REL_CAT).write_text(cat_text, encoding="utf-8")
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
