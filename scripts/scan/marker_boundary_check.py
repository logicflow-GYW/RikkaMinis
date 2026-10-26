#!/usr/bin/env python3
"""
Marker-boundary completion guard — `internalScanMarker` may only report a HIT
after the full `__MINIS_DONE_<marker>_EXIT_<n>__` has been parsed, and the
pending-marker window it withholds must be explicitly bounded.

Bug class ([deep-audit-1017] ISSUE-09, live since [fix/marker-chunk-boundary]):
the scan hit on the bare `__MINIS_DONE_<m>_EXIT_` PREFIX and took the exit code
from a regex that requires the closing `__`. When a 4096-char read boundary fell
between `_EXIT_` and its digits, the command completed with `exitCode = -1` —
the "shell died" code, on which ExecutionCoordinator rebuilds the shell and
RE-RUNS the command (non-idempotent side effects twice) — and every byte of the
marker line still in flight was dropped (`pendingCallback = null; tail = ""`).
The previous fix in the same file only taught the loop to carry a split PREFIX
(`[fix/marker-chunk-boundary]`), so the exit-code half of the same marker stayed
exposed. A pure-function regression here is silent: nothing crashes, the command
just reports the wrong result.

Criterion (mechanical, all four judged on the DECISION, never on a spelling):
  0. every `MarkerScanResult(…)` whose hit argument is not provably `false` —
     named `hit = …` OR the FIRST POSITIONAL argument — must sit inside an
     `if (<v> >= 0)` / `if (0 <= <v>)` guard that is REACHABLE; `<v>` must be
     declared `val <v> = internalParseMinisExitCode(…)` as its WHOLE
     initializer (nothing chained onto the call: no `.let { 0 }`, no
     `.coerceAtLeast(0)`, no `?: 0`), and the exitCode handed to the result
     must be that same `<v>`. Anything else means the reported completion is
     backed by a constant / a -1 / an unrelated variable.
  1. a `>= 0` guard must precede the first reachable hit;
  2. the bounded window: `internal const val MAX_PENDING_MARKER_CHARS = <n>`
     with 32 <= n <= 256 must be compared against the pending slice's own
     length (`<pending>.length <= CAP`, or `CAP >= <pending>.length`) — the
     window variable must itself be a `substring(…)` of the combined text —
     the comparison must be LIVE (a `&& false` tail, `!`, or a constant left
     operand folds the guard away) and must not be widened by `||`; the
     bounded branch must withhold that slice (`keptTail` derived from it and
     not a blank literal) and must not report a hit.

Batch-7 gate-blind witnesses (each one used to pass this gate unchanged):
  14-1 `val exitCode = internalParseMinisExitCode(…).let { 0 }` — the old
       declaration check stopped at the `(` and never looked at the value.
       Criterion 0 now requires the call to be the whole initializer.
  14-2 `if (0 <= MAX_PENDING_MARKER_CHARS)` — the old check only asked whether
       the cap NAME appeared next to SOME comparison operator. Criterion 2
       binds the left operand to the pending window and the direction to a
       real ceiling.
  14-3 `keptTail = ""` inside the bounded branch — the old check only proved
       the branch existed. Criterion 2 requires the withheld slice itself.
  14-4 `hit = !false` — the old loop was `if hit_expr != "true": continue`.
       Criterion 0 treats every hit that is not provably `false` as a hit.
  14-5 a dedented `}` closing the bounded `if`, with a bare
       `return MarkerScanResult(hit = true, …)` after it — the old
       `function_span()` cut the body at the first `\n}`, so the injected
       return sat outside the gate's view entirely. The body now comes from
       the shared `function_body()` (brace-balanced, string/comment-masked).

Usage: python3 scripts/scan/marker_boundary_check.py [repo_root]
       python3 scripts/scan/marker_boundary_check.py --selftest
Exit:  0 = guarded, 1 = violation (file:line printed)
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    path_feasible,
)

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/PersistentShell.kt"
FUNC = "internalScanMarker"
CAP_CONST = "MAX_PENDING_MARKER_CHARS"
CAP_DECL_RE = re.compile(r"internal const val %s\s*=\s*(\d+)" % CAP_CONST)
PARSE_HELPER = "internalParseMinisExitCode"
MIN_CAP = 32
MAX_CAP = 256

SITE_RE = re.compile(r"MarkerScanResult\s*\(")
NAMED_ARG = re.compile(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", re.S)
# `if (<v> >= 0)` and the equivalent `if (0 <= <v>)`
GUARD_LEFT = re.compile(r"^\s*([A-Za-z_]\w*)\s*>=\s*0\s*$")
GUARD_RIGHT = re.compile(r"^\s*0\s*<=\s*([A-Za-z_]\w*)\s*$")
# a window length: `<name>.length` / `<name>.size`
WINDOW_RE = re.compile(r"^([A-Za-z_]\w*)\s*\.\s*(?:length|size)$")


# ── shared-primitive helpers ────────────────────────────────────────────────
def _call_args(text, open_paren_pos):
    """[(start, end)] of the top-level arguments of the call at `(`."""
    close = match_bracket(text, open_paren_pos)
    if close < 0:
        return []
    base = open_paren_pos + 1
    return [(base + a, base + b) for a, b in split_top(text[base:close], (",",))]


def marker_sites(body):
    """Every `MarkerScanResult(…)` construction, named or positional.

    Resolved by the SHARED argument splitter, so a positional
    `MarkerScanResult(true, …, -1, …)` cannot dodge the hit/hit-code pairing
    and a multi-line construction cannot dodge it either.
    """
    sites = []
    for m in SITE_RE.finditer(body):
        spans = _call_args(body, m.end() - 1)
        if not spans:
            continue
        named, positional = {}, []
        for a, b in spans:
            txt = body[a:b].strip()
            nm = NAMED_ARG.match(txt)
            if nm:
                named[nm.group(1)] = nm.group(2).strip()
            else:
                positional.append(txt)
        hit = named.get("hit", positional[0] if positional else "")
        exit_expr = named.get("exitCode", positional[2] if len(positional) > 2 else "")
        kept = named.get("keptTail", positional[3] if len(positional) > 3 else "")
        sites.append({"pos": m.start(), "hit": hit.strip(),
                      "exit": exit_expr.strip(), "kept": kept.strip()})
    return sites


def guard_var(cond):
    """The variable an `if (<v> >= 0)` guard tests, or None."""
    c = strip_parens(cond)
    m = GUARD_LEFT.match(c) or GUARD_RIGHT.match(c)
    return m.group(1) if m else None


def parse_call_only(expr):
    """Is `expr` EXACTLY a call to internalParseMinisExitCode(…)?

    Nothing may be chained onto the call — `.let { 0 }`, `.coerceAtLeast(0)`
    and `?: 0` all keep the declaration prefix while replacing the parsed
    value with a constant (witness 14-1).
    """
    e = strip_parens(expr)
    m = re.match(re.escape(PARSE_HELPER) + r"\s*\(", e)
    if m is None:
        return False
    return match_bracket(e, m.end() - 1) == len(e) - 1


def parse_decl(body, name):
    """(start, keyword, initializer, init_start) of `val|var <name> = …`, or None."""
    m = re.search(r"(?m)^[ \t]*(val|var)\s+" + re.escape(name) + r"\s*=", body)
    if m is None:
        return None
    return (m.start(), m.group(1), body[m.end():expr_end(body, m.end())], m.end())


PARSE_BACKED = ("ok", "no-decl", "after", "var", "not-parse-helper", "not-parse-call")


def parse_backed(body, name, pos):
    """Is `name`, at `pos`, declared as the parsed marker exit code?"""
    decl = parse_decl(body, name)
    if decl is None:
        return "no-decl"
    if decl[0] > pos:
        return "after"
    if decl[1] != "val":
        return "var"
    if not re.match(re.escape(PARSE_HELPER) + r"\s*\(", strip_parens(decl[2])):
        return "not-parse-helper"
    if not parse_call_only(decl[2]):
        return "not-parse-call"
    return "ok"


def _window_var(body, name):
    """Is `name` a local bound to `…substring(…)` (the pending marker slice)?"""
    m = re.search(r"(?m)^[ \t]*(?:val|var)\s+" + re.escape(name) + r"\s*=", body)
    if m is None:
        return False
    init = body[m.end():expr_end(body, m.end())]
    return "substring(" in init


def _comparison(cond):
    """(op, lhs, rhs) of the top-level comparison, or (None, None, None)."""
    c = strip_parens(cond)
    for op in (">=", "<=", ">", "<"):
        parts = split_top(c, (op,))
        if len(parts) == 2:
            return op, c[parts[0][0]:parts[0][1]].strip(), c[parts[1][0]:parts[1][1]].strip()
    return None, None, None


def cap_comparison(cond):
    """(ok, window_var, message) — does `cond` actually cap the pending window?"""
    op, lhs, rhs = _comparison(cond)
    if op is None:
        return False, None, (
            "the %s guard is not a comparison — it cannot bound the pending window" % CAP_CONST
        )
    if rhs == CAP_CONST:
        m = WINDOW_RE.match(lhs)
        if m is None:
            return False, None, (
                "the %s bound compares `%s` — the LEFT operand must be the pending window's "
                "length (`<pending>.length <= %s`); a constant such as `0 <= %s` is always "
                "true, so a split-marker prefix accumulates without limit"
                % (CAP_CONST, lhs, CAP_CONST, CAP_CONST)
            )
        if op not in ("<=", "<"):
            return False, None, (
                "the %s bound uses `%s` — only `<=` / `<` bound the window; `%s` inverts it, "
                "so a short real split marker (length < %s) is flushed away and lost while a "
                "long prefix is withheld forever" % (CAP_CONST, op, op, CAP_CONST)
            )
        return True, m.group(1), ""
    if lhs == CAP_CONST:
        m = WINDOW_RE.match(rhs)
        if m is None:
            return False, None, (
                "the %s bound compares `%s` — the other operand must be the pending window's "
                "length (`%s >= <pending>.length`), not a constant" % (CAP_CONST, rhs, CAP_CONST)
            )
        if op not in (">=", ">"):
            return False, None, (
                "the %s bound uses `%s` — only `>=` / `>` bound the window when the cap is on "
                "the left; `%s` inverts the ceiling" % (CAP_CONST, op, op)
            )
        return True, m.group(1), ""
    return False, None, (
        "the %s guard never compares the cap to the pending window (`%s`)" % (CAP_CONST, cond.strip()[:70])
    )


# ── the criterion ───────────────────────────────────────────────────────────
def check_source(text):
    """Return [(line_or_None, message)] for one source text."""
    try:
        code = code_view(text)
    except KotlinLexError as exc:  # pragma: no cover - fail closed
        return [(None, "the Kotlin lexer rejected the file (%s) — fail closed" % exc)]
    span = function_body(code, FUNC)
    if span is None:
        return [(None, "`%s` not found — keep this gate honest (renamed or removed?)" % FUNC)]
    base, end, kind = span
    if kind != "block":
        return [(None, "`%s` has no block body — re-point this gate before trusting it" % FUNC)]
    body = code[base:end]
    body_line = code.count("\n", 0, base) + 1

    def line_of(pos):
        return code.count("\n", 0, base + pos) + 1

    findings = []
    sites = marker_sites(body)
    blocks = if_blocks(body)
    guard_blocks = [b for b in blocks if guard_var(b[0])]

        # ── 1. legacy ordering rule: a REAL exit-code guard must precede the first hit
    exit_guards = [b for b in blocks
                   if guard_var(b[0]) and parse_backed(body, guard_var(b[0]), b[1]) == "ok"]
    if not exit_guards:
        findings.append((body_line, "%s has no `if (<parsedExitCode> >= 0)` guard — a bare "
                         "marker prefix would report completion with exitCode -1 "
                         "(\"shell died\")" % FUNC))
    first_hit = next((s for s in sites if const_bool(s["hit"]) is not False
                      and path_feasible(body, s["pos"])), None)
    if first_hit is not None and exit_guards:
        guard_at = min(b[1] for b in exit_guards)
        if first_hit["pos"] < guard_at:
            findings.append((line_of(first_hit["pos"]),
                             "%s reports a hit before any exit-code check" % FUNC))

    # ── 0. every hit must be backed by the PARSED code, in every spelling
    for s in sites:
        if const_bool(s["hit"]) is False:
            continue  # provably not a hit
        pos = s["pos"]
        if not path_feasible(body, pos):
            continue  # unreachable: it cannot report anything
        # innermost covering `>= 0` guard first (source order: later == inner)
        covering = [b for b in blocks if b[1] <= pos < b[2] and guard_var(b[0])]
        covering.reverse()
        if not covering:
            findings.append((line_of(pos),
                             "%s reports a hit (hit = %s) outside any `if (<parsedExitCode> >= 0)` "
                             "guard — a split marker then completes the command with exitCode -1 "
                             "(\"shell died\")" % (FUNC, s["hit"] or "<positional>")))
            continue
        backed = next((b for b in covering
                       if parse_backed(body, guard_var(b[0]), pos) == "ok"), None)
        if backed is None:
            cond, _t0, _t1, _e0, _e1 = covering[0]
            var = guard_var(cond)
            why = parse_backed(body, var, pos)
            detail = {
                "no-decl": "no `val %s = %s(…)` declares it" % (var, PARSE_HELPER),
                "after": "`%s` is declared only AFTER the hit" % var,
                "var": "`%s` is a `var` — it can be reassigned between the parse and the hit"
                       % var,
                "not-parse-helper": "`%s` is declared as `%s`, not as `%s(…)`"
                                    % (var, " ".join(parse_decl(body, var)[2].split())[:60],
                                       PARSE_HELPER),
                "not-parse-call": "`%s` chains onto `%s(…)` (`.let { 0 }` / "
                                  "`.coerceAtLeast(0)` / `?: 0`), so the value the guard tests "
                                  "is not the parsed exit code"
                                  % (var, PARSE_HELPER),
            }[why]
            findings.append((line_of(pos),
                             "`%s` is not the parsed marker exit code: %s — a split marker then "
                             "reports completion with a made-up exit code (the 14-1 hole)"
                             % (var, detail)))
            if s["exit"] != var:
                findings.append((line_of(pos),
                                 "the hit hands exitCode = '%s' to MarkerScanResult while the "
                                 "guard validated '%s' — the value that decides completion must "
                                 "be the value that is reported"
                                 % (s["exit"] or "<missing>", var)))
            continue
        var = guard_var(backed[0])
        if s["exit"] != var:
            findings.append((line_of(pos),
                             "the hit hands exitCode = '%s' to MarkerScanResult while the guard "
                             "validated '%s' — the value that decides completion must be the "
                             "value that is reported" % (s["exit"] or "<missing>", var)))

    # ── 2. the cap must be a real, live, narrowing bound of the pending slice
    decl = CAP_DECL_RE.search(code)
    if decl is None:
        findings.append((None, "no `internal const val %s = <n>` — the pending marker window "
                         "may grow without bound" % CAP_CONST))
    else:
        value = int(decl.group(1))
        if not (MIN_CAP <= value <= MAX_CAP):
            findings.append((None, "%s = %d is outside %d..%d — a cap that large does not "
                             "bound anything" % (CAP_CONST, value, MIN_CAP, MAX_CAP)))
    cap_blocks = [b for b in blocks if CAP_CONST in b[0]]
    if not cap_blocks:
        findings.append((body_line, "%s does not use %s — the pending window is unbounded in "
                         "the hit path" % (FUNC, CAP_CONST)))
    for cond, t0, t1, _e0, _e1 in cap_blocks:
        line = line_of(t0)
        if len(split_top(strip_parens(cond), ("||",))) > 1:
            findings.append((line,
                             "the %s bound is weakened by a disjunction (`||`) — "
                             "`if (… <= %s || true)` can never withhold anything, so a "
                             "split-marker prefix accumulates without limit"
                             % (CAP_CONST, CAP_CONST)))
            continue
        if not guard_feasible(cond):
            findings.append((line,
                             "the %s bound is dead (`%s` constant-folds to false) — the "
                             "withholding branch never runs, so every split marker prefix is "
                             "flushed and the window is effectively unbounded"
                             % (CAP_CONST, " ".join(cond.split())[:70])))
            continue
        ok, pvar, why = cap_comparison(cond)
        if not ok:
            findings.append((line, why))
            continue
        if not _window_var(body, pvar):
            findings.append((line,
                             "`%s` bounds `%s`, but `%s` is not the pending marker slice "
                             "(`val %s = …substring(…)`) — the cap measures something that does "
                             "not grow with the withheld prefix" % (CAP_CONST, pvar, pvar, pvar)))
            continue
        held = [s for s in sites if t0 <= s["pos"] < t1]
        if not held:
            findings.append((line,
                             "the %s-bounded branch returns no MarkerScanResult — the pending "
                             "prefix is flushed as ordinary output and lost" % CAP_CONST))
            continue
        for s in held:
            if const_bool(s["hit"]) is not False:
                findings.append((line_of(s["pos"]),
                                 "the %s-bounded branch reports a hit (hit = %s) — the withheld "
                                 "prefix is the SPLIT marker, not a completion"
                                 % (CAP_CONST, s["hit"] or "<positional>")))
                continue
            kv = s["kept"]
            cv = const_value(kv) if kv else None
            if cv is not None and cv[0] == "str" and not cv[1]:
                findings.append((line_of(s["pos"]),
                                 "keptTail = '%s' — the withheld window is dropped, so the split "
                                 "marker line never completes in the next chunk and the "
                                 "completion marker is lost" % kv))
            elif not (contains_ident(kv, pvar) or "substring(" in kv):
                findings.append((line_of(s["pos"]),
                                 "keptTail = '%s' does not carry the pending slice `%s` — the "
                                 "bounded branch withholds something else, so the split marker "
                                 "cannot complete" % (kv or "<missing>", pvar)))
    return findings


# ── fixtures ────────────────────────────────────────────────────────────────
OK_FIXTURE = """
package p
internal const val MAX_PENDING_MARKER_CHARS = 64
internal fun internalScanMarker(tail: String, text: String, marker: String): MarkerScanResult {
    val idx = combined.indexOf(markerPattern)
    if (idx >= 0) {
        val exitCode = internalParseMinisExitCode(combined.substring(idx), marker)
        if (exitCode >= 0) {
            return MarkerScanResult(
                hit = true,
                beforeMarker = "",
                exitCode = exitCode,
                keptTail = "",
            )
        }
        val pending = combined.substring(idx)
        if (pending.length <= MAX_PENDING_MARKER_CHARS) {
            return MarkerScanResult(hit = false, beforeMarker = "", exitCode = -1, keptTail = pending)
        }
    }
    return MarkerScanResult(hit = false, beforeMarker = combined, exitCode = -1, keptTail = "")
}
"""

PRE_FIX_FIXTURE = OK_FIXTURE.replace(
    """        val exitCode = internalParseMinisExitCode(combined.substring(idx), marker)
        if (exitCode >= 0) {
""",
    "",
).replace(
    """            return MarkerScanResult(
                hit = true,
                beforeMarker = "",
                exitCode = exitCode,
                keptTail = "",
            )
""",
    "        return MarkerScanResult(hit = true, beforeMarker = \"\", exitCode = -1, keptTail = \"\")\n",
).replace("        if (pending.length <= MAX_PENDING_MARKER_CHARS) {\n", "").replace(
    "internal const val MAX_PENDING_MARKER_CHARS = 64\n", ""
)
assert check_source(OK_FIXTURE) == []
assert check_source(PRE_FIX_FIXTURE) != []

NO_CAP_FIXTURE = OK_FIXTURE.replace("internal const val MAX_PENDING_MARKER_CHARS = 64\n", "")
CAP_UNUSED_FIXTURE = OK_FIXTURE.replace(
    "if (pending.length <= MAX_PENDING_MARKER_CHARS) {", "if (pending.length <= 64) {"
)
CAP_TOO_BIG_FIXTURE = OK_FIXTURE.replace(
    "MAX_PENDING_MARKER_CHARS = 64", "MAX_PENDING_MARKER_CHARS = 100000"
)

# ── wide-1024 witnesses (each one used to pass this gate unchanged) ───────────

# W1: the guard tests a constant, the parse is smuggled into the result.
W1_CONST_GUARD_FIXTURE = OK_FIXTURE.replace(
    """        val exitCode = internalParseMinisExitCode(combined.substring(idx), marker)
        if (exitCode >= 0) {
            return MarkerScanResult(
                hit = true,
                beforeMarker = "",
                exitCode = exitCode,
                keptTail = "",
            )
        }""",
    """        val exitCode = 0
        if (exitCode >= 0) {
            return MarkerScanResult(
                hit = true,
                beforeMarker = "",
                exitCode = internalParseMinisExitCode(combined.substring(idx), marker),
                keptTail = "",
            )
        }""",
)

# W2: the cap comparison is short-circuited into a tautology.
W2_WEAKENED_CAP_FIXTURE = OK_FIXTURE.replace(
    "if (pending.length <= MAX_PENDING_MARKER_CHARS) {",
    "if (pending.length <= MAX_PENDING_MARKER_CHARS || true) {",
)

# W3: a positional hit after the guard block — no `hit = true` to grep for.
W3_POSITIONAL_HIT_FIXTURE = OK_FIXTURE.replace(
    """        val pending = combined.substring(idx)
        if (pending.length <= MAX_PENDING_MARKER_CHARS) {""",
    """        if (true) return MarkerScanResult(true, combined.substring(0, idx), -1, "")
        val pending = combined.substring(idx)
        if (pending.length <= MAX_PENDING_MARKER_CHARS) {""",
)

# ── batch-7 witnesses (14-1 … 14-5) ─────────────────────────────────────────
PARSE_LINE = "        val exitCode = internalParseMinisExitCode(combined.substring(idx), marker)\n"

# 14-1: the declaration prefix survives, the parsed value is replaced by a
# constant that a "looks like business code" chain hangs off the call.
B7_PARSE_LET_FIXTURE = OK_FIXTURE.replace(
    PARSE_LINE, PARSE_LINE.replace("marker)\n", "marker).let { 0 }\n"))
B7_PARSE_COERCE_FIXTURE = OK_FIXTURE.replace(
    PARSE_LINE, PARSE_LINE.replace("marker)\n", "marker).coerceAtLeast(0)\n"))
B7_PARSE_ELVIS_FIXTURE = OK_FIXTURE.replace(
    PARSE_LINE, PARSE_LINE.replace("marker)\n", "marker) ?: 0\n"))

# 14-2: the ceiling's left operand becomes a constant / the direction inverts.
B7_CAP_CONST_LHS_FIXTURE = OK_FIXTURE.replace(
    "if (pending.length <= MAX_PENDING_MARKER_CHARS) {",
    "if (0 <= MAX_PENDING_MARKER_CHARS) {",
)
B7_CAP_INVERTED_FIXTURE = OK_FIXTURE.replace(
    "if (pending.length <= MAX_PENDING_MARKER_CHARS) {",
    "if (pending.length >= MAX_PENDING_MARKER_CHARS) {",
)
B7_CAP_UNRELATED_FIXTURE = OK_FIXTURE.replace(
    "if (pending.length <= MAX_PENDING_MARKER_CHARS) {",
    "if (kept.length <= MAX_PENDING_MARKER_CHARS) {",
).replace("        val pending = combined.substring(idx)\n",
          "        val pending = combined.substring(idx)\n        val kept = tail\n")

# 14-3: the bounded branch exists and withholds nothing.
B7_TAIL_DROPPED_FIXTURE = OK_FIXTURE.replace(
    "return MarkerScanResult(hit = false, beforeMarker = \"\", exitCode = -1, keptTail = pending)",
    "return MarkerScanResult(hit = false, beforeMarker = \"\", exitCode = -1, keptTail = \"\")",
)

# 14-4: an equivalent spelling of the hit flag dodges the `hit = true` grep.
B7_HIT_ALGEBRA_FIXTURE = OK_FIXTURE.replace(
    "        val pending = combined.substring(idx)\n",
    "        if (1 == 1) return MarkerScanResult(hit = !false, beforeMarker = \"\", "
    "exitCode = -1, keptTail = \"\")\n        val pending = combined.substring(idx)\n",
)

# 14-5: a dedented `}` closes the bounded if; the injected hit sits after it,
# outside the slice the old `text.find("\\n}")` parser produced.
B7_TRUNCATED_BODY_FIXTURE = OK_FIXTURE.replace(
    """        val pending = combined.substring(idx)
        if (pending.length <= MAX_PENDING_MARKER_CHARS) {
            return MarkerScanResult(hit = false, beforeMarker = "", exitCode = -1, keptTail = pending)
        }""",
    """        val pending = combined.substring(idx)
        if (pending.length <= MAX_PENDING_MARKER_CHARS && false) {
            return MarkerScanResult(hit = false, beforeMarker = "", exitCode = -1, keptTail = pending)
}
        return MarkerScanResult(hit = true, beforeMarker = "", exitCode = -1, keptTail = "")""",
)

# ── clean arms: equivalent shapes that must stay green ──────────────────────
# the mirrored guard spelling
OK_GUARD_MIRRORED = OK_FIXTURE.replace("if (exitCode >= 0) {", "if (0 <= exitCode) {")
# the withheld slice rebuilt from the combined text instead of the local
OK_TAIL_SUBSTRING = OK_FIXTURE.replace("keptTail = pending)", "keptTail = combined.substring(idx))")
# a multi-line parse call is still "exactly the parse call"
OK_PARSE_MULTILINE = OK_FIXTURE.replace(
    "        val exitCode = internalParseMinisExitCode(combined.substring(idx), marker)\n",
    "        val exitCode = internalParseMinisExitCode(\n"
    "            combined.substring(idx),\n"
    "            marker,\n"
    "        )\n",
)
# the cap on the left, window on the right, is the same ceiling
OK_CAP_MIRRORED = OK_FIXTURE.replace(
    "if (pending.length <= MAX_PENDING_MARKER_CHARS) {",
    "if (MAX_PENDING_MARKER_CHARS >= pending.length) {",
)

ARMS = [
    ("compliant source is green", OK_FIXTURE, [], True),
    ("pre-fix shape is red", PRE_FIX_FIXTURE,
     ["no `if (<parsedExitCode> >= 0)` guard", "not the parsed marker exit code",
      "no `internal const val", "does not use"], False),
    ("missing cap declaration is red", NO_CAP_FIXTURE, ["no `internal const val"], False),
    ("unused cap constant is red", CAP_UNUSED_FIXTURE, ["does not use"], False),
    ("oversized cap is red", CAP_TOO_BIG_FIXTURE, ["outside 32..256"], False),
    ("W1 constant guard is red", W1_CONST_GUARD_FIXTURE,
     ["not the parsed marker exit code", "hands exitCode"], False),
    ("W2 weakened cap is red", W2_WEAKENED_CAP_FIXTURE,
     ["weakened by a disjunction"], False),
    ("W3 positional hit is red", W3_POSITIONAL_HIT_FIXTURE,
     ["not the parsed marker exit code", "hands exitCode = '-1'"], False),
    ("14-1 parse .let { 0 } is red", B7_PARSE_LET_FIXTURE,
     ["not the parsed marker exit code", "`.let { 0 }`"], False),
    ("14-1b parse .coerceAtLeast(0) is red", B7_PARSE_COERCE_FIXTURE,
     ["not the parsed marker exit code"], False),
    ("14-1c parse ?: 0 is red", B7_PARSE_ELVIS_FIXTURE,
     ["not the parsed marker exit code"], False),
    ("14-2 constant cap operand is red", B7_CAP_CONST_LHS_FIXTURE,
     ["LEFT operand must be the pending window"], False),
    ("14-2b inverted cap is red", B7_CAP_INVERTED_FIXTURE,
     ["only `<=` / `<` bound the window"], False),
    ("14-2c cap on an unrelated value is red", B7_CAP_UNRELATED_FIXTURE,
     ["is not the pending marker slice"], False),
    ("14-3 dropped withheld tail is red", B7_TAIL_DROPPED_FIXTURE,
     ["the withheld window is dropped"], False),
    ("14-4 hit = !false is red", B7_HIT_ALGEBRA_FIXTURE,
     ["not the parsed marker exit code", "hands exitCode"], False),
    ("14-5 dedented body truncation is red", B7_TRUNCATED_BODY_FIXTURE,
     ["not the parsed marker exit code", "constant-folds to false"], False),
    ("mirrored guard spelling stays green", OK_GUARD_MIRRORED, [], True),
    ("tail rebuilt from the combined text stays green", OK_TAIL_SUBSTRING, [], True),
    ("multi-line parse call stays green", OK_PARSE_MULTILINE, [], True),
    ("mirrored cap spelling stays green", OK_CAP_MIRRORED, [], True),
]


def selftest():
    bad = 0
    for name, text, needles, clean in ARMS:
        findings = check_source(text)
        messages = "\n".join(m for _, m in findings)
        missing = [n for n in needles if n not in messages]
        if clean:
            ok = not findings
        else:
            ok = bool(findings) and not missing
        if not ok:
            bad += 1
            print("  [FAIL] %s: %d findings, missing needles %s" % (name, len(findings), missing))
            for line, msg in findings:
                print("         %s" % msg)
        else:
            print("  [OK] %s (%d findings)" % (name, len(findings)))
    print("SELFTEST=%s (%d arms)" % ("PASS" if bad == 0 else "FAIL", len(ARMS)))
    print("MARKER_BOUNDARY_SELFTEST=%s (%d arms)"
          % ("PASS" if bad == 0 else "FAIL", len(ARMS)))
    return 1 if bad else 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    root = next((a for a in sys.argv[1:] if not a.startswith("-")), ".")
    path = os.path.join(root, TARGET)
    if not os.path.exists(path):
        print("❌ marker-boundary guard: %s not found (moved?)" % TARGET)
        return 1
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    findings = check_source(text)
    if findings:
        print("❌ marker-boundary guard findings:")
        for line, msg in findings:
            print("   - %s%s" % ("%s:%d " % (TARGET, line) if line else "", msg))
        return 1
    print("✅ marker-boundary guard: `%s` requires a parsed exit code before a hit, "
          "pending window capped by %s" % (FUNC, CAP_CONST))
    return 0


if __name__ == "__main__":
    sys.exit(main())
