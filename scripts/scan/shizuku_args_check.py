#!/usr/bin/env python3
"""
android-shizuku-cli argument-handling guard (deep-audit-1017 ISSUE-08).

Three defects live in `ShizukuOffloadHandler.kt`, and only some of them are
visible to a JVM test (`ShizukuCliArgsTest`):

  * what the helpers *produce* (encodeInputText / buildPackageClearCommand /
    clearScope) — behavioural, pinned by that test;
  * how the handler *calls* them and where it reads its arguments from — that
    wiring lives in a class that imports `android.content.Context`, so no unit
    test can reach it. It is pinned here instead, because a helper that is
    correct but unplugged is exactly the "wrapper is alive, path does not run
    through it" failure (deep-audit-1016 FIX-D G3b).

Criteria (all structural; the file must be the real handler):
  1. the handler builds its parser with the declared switch set
     (`OffloadArgs(rest, booleanFlags = NO_VALUE_FLAGS)`);
  2. `NO_VALUE_FLAGS` exists, covers `block`/`unblock`, and excludes
     `importance` (a value flag — declaring it would orphan its value);
  3. `encodeInputText` emits the platform's `%s` escape, not a bare `%`;
  4. `buildPackageClearCommand` asks `pm clear` for the package (per-package),
     and the handler contains no global `trim-caches` stand-in;
  5. `handleNotification` hands `notificationChannel` the **parsed** positional
     list (`args.positional.drop(1)`), not the raw argv tail;
  6. `notificationChannel` reads its package/channel from those positionals
     (`pos.getOrNull(1)`) and never from raw `rest`;
  7. `packageClear` reports the scope it actually cleared (`clearScope(...)`);
  8. every long option read through `hasFlag` is declared value-less;
  9. the group comes from the parsed positional (`splitShizukuArgv`), not
     `argv[0]`;
 10. group sub-commands read parsed positionals, not the raw `rest` tail;
 11. `exec` passthrough still joins the raw tail verbatim;
 12. the one two-meaning option (`--data`) is opted out per group;
 13. [deep-audit-1029 ISSUE-07] `exec`'s payload is a shell command, not an
     option list: the parser is fed only the CLI's own tokens
     (`split.execOptionTokens`) and the one option `exec` owns
     (`--timeout-ms N`) is lifted out of the payload by
     `stripExecTimeoutTokens`. Either half missing restores a shipped defect —
     the payload reaching the output shaper (`exec grep -q foo /p` did, via
     `-q` → `--quiet`) makes it drop the whole `data` object on a non-zero exit,
     and the un-lifted option is executed by the shell.
     [wide-1024] …and the lift must ACTUALLY happen: the helper's body is
     judged (loop + option literal + two accumulators + no early return), not
     its existence. `if (true) return tokens to tokens` used to stay green.

 14. [wide-1024] the netpolicy direction is judged inside the `arrayOf(...)`
     that is an ARGUMENT of the call. The old anchor took the first textual
     mention of the list name, so a dead `val x = arrayOf(…)` one line above
     the real `runProcess(...)` absorbed it.

 15. [wide-1024] "no fallback to root" is judged as a SPELLING CLASS: every
     `?:` whose fallback evaluates to "0" (`?: "0"`, `?: (0).toString()`,
     `?: 0.toString()`, `?: Integer.toString(0)`, `?: "$0"`). Pinning the one
     literal let a re-spelled root fallback through.

A renamed function is a HARD FAIL, never a skip (the gate must not go quiet
because the code moved).

Usage: python3 scripts/scan/shizuku_args_check.py [repo_root] [--selftest]
Exit:  0 = all criteria hold, 1 = at least one violation
"""
import os
import re
import sys
import tempfile

REL = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app",
    "sandbox", "offload", "ShizukuOffloadHandler.kt",
)


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    match_bracket,
    split_top,
    strip_parens,
    tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
)

SELFTEST_MARK = "SHIZUKU_ARGS_SELFTEST=PASS"


def views(src):
    """(code, literal) — both length preserving, so offsets map 1:1.

    [b6-t11 parser-death] This gate used to carry its own `strip_comments` and
    its own brace walker. Two consequences showed up as witnesses: a block
    comment that closed with a brace shifted every later anchor (the hand-rolled
    masker counted `}` NOT inside strings, but it was still a second lexicon),
    and every structural judgement operated on text where string contents were
    live, so `Regex(\"\"\"userId=(\\d+)\"\"\")` contributed parens to the bracket
    balance. The shared masker gives both projections off ONE scan:
    `code` blots comments and literal interiors (structure), `literal` blots
    comments only (anchor spelling).
    """
    return code_view(src), literal_view(src)


def line_of(src, needle):
    """1-based line of the first occurrence of `needle`, 0 when absent."""
    i = src.find(needle)
    return src.count("\n", 0, i) + 1 if i >= 0 else 0


DEF_RE = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\s*\.\s*)?([A-Za-z_]\w*)\s*\(")


def fn_span(cc, name):
    """(body_start, body_end) of `fun <name>(…)` in the code view."""
    fb = function_body(cc, name)
    return (None, None) if fb is None else (fb[0], fb[1])


def body_of(cc, lc, name):
    """(cc_body, lc_body, absolute_start) — offsets inside are relative."""
    start, end = fn_span(cc, name)
    if start is None:
        return None, None, 0
    return cc[start:end], lc[start:end], start


def fn_params(cc, name):
    for m in DEF_RE.finditer(cc):
        if m.group(1) != name:
            continue
        close = match_bracket(cc, m.end() - 1)
        if close < 0:
            return []
        return re.findall(r"([A-Za-z_]\w*)\s*:", cc[m.end():close])
    return []


def dead_condition_at(cc, pos):
    """The first enclosing `if` condition that cannot be true ('' when none)."""
    for kind, cond in enclosing_conditions(cc, pos):
        if kind != "if":
            continue
        if not guard_feasible(cond):
            return re.sub(r"\s+", " ", cond).strip()
    return ""


def dead_reason(cc, pos):
    """'' when reachable, else the dead condition text."""
    cond = dead_condition_at(cc, pos)
    return "dead condition `%s`" % cond[:80] if cond else ""


# ── option spellings ────────────────────────────────────────────────────
def _option_spellings(lc, option):
    """Every spelling that identifies [option]: the literal, plus each constant
    bound to it in this file. A named constant is the same decision written
    once — the real handler uses it, so a literal-only anchor would red the
    clean tree."""
    out = {'"%s"' % option}
    for m in re.finditer(
            r"\b(?:const\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*\"%s\""
            % re.escape(option), lc):
        out.add(m.group(1))
    return out


def _array_span(cc, i):
    """(start, end) of the innermost `arrayOf(...)` covering index [i]."""
    best = None
    for m in re.finditer(r"\barrayOf\s*\(", cc):
        op = m.end() - 1
        close = match_bracket(cc, op)
        if close < 0 or not (op < i < close):
            continue
        if best is None or m.start() > best[0]:
            best = (m.start(), m.end() - 1, close + 1)
    return None if best is None else (best[0], best[2])


def _blocks_of(cc):
    return re.finditer(r"\{", cc)


# ── constant folding of an `if` chain (value position) ──────────────────
def depth0_token(text, token, start=0):
    depth = 0
    i, n = start, len(text)
    while i < n:
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and text.startswith(token, i):
            after = i + len(token)
            if token[0].isalpha() and after < n and (text[after].isalnum() or text[after] == "_"):
                i += 1
                continue
            return i
        i += 1
    return -1


def split_if(text, start=0):
    """(cond, then_span, else_span|None) for an `if (...)` starting at [start].

    The shared `if_blocks` derives `else` from what FOLLOWS the then-branch
    extent; on an expression-position `if (c) a else b` the extent swallows the
    same-line `else`, so the direction slot would read as a dead single branch
    (registered as R1). `else` is located at bracket depth 0 here instead.
    """
    m = re.match(r"\s*if\s*\(", text[start:])
    if m is None:
        return None
    op = start + m.end() - 1
    close = match_bracket(text, op)
    if close < 0:
        return None
    cond = text[op + 1:close]
    k = depth0_token(text, "else", close + 1)
    if k < 0:
        return cond, (close + 1, len(text)), None
    return cond, (close + 1, k), (k + 4, len(text))


def _literal_branch(cc, lc, span):
    """(literal, reason) — the single string literal a direction branch yields."""
    seg_cc = cc[span[0]:span[1]]
    seg_lc = lc[span[0]:span[1]].strip()
    if seg_cc.strip().startswith("if"):
        return None, ("the direction slot must be a single `if (restrict) … else …`, "
                      "not a nested chain")
    if not re.match(r'^"(?:[^"\\]|\\.)*"$', seg_lc):
        return None, ("a direction branch yields `%s`, which is not a string literal"
                      % seg_lc[:40])
    return seg_lc[1:-1], ""


def _direction_verdict(cc, lc, start, end, want_then, want_else):
    """(ok, note) for one `arrayOf(...)` element that chooses a netpolicy list."""
    text_cc, text_lc = cc[start:end], lc[start:end]
    parts = split_if(text_cc)
    if parts is None:
        return False, "the direction slot is not an `if (…)` expression"
    cond, t_span, e_span = parts
    if not contains_ident(cond, "restrict"):
        return False, ("the direction is not chosen by the `restrict` flag (`%s`)"
                       % re.sub(r"\s+", " ", cond).strip()[:70])
    if const_bool(strip_parens(cond)) is not None:
        return False, ("the direction condition (`%s`) is a CONSTANT — one branch is "
                       "dead and the flag no longer selects anything"
                       % re.sub(r"\s+", " ", cond).strip()[:70])
    if e_span is None:
        return False, "the direction `if` has no `else` branch"
    then_lit, why = _literal_branch(text_cc, text_lc, t_span)
    if then_lit is None:
        return False, why
    else_lit, why = _literal_branch(text_cc, text_lc, e_span)
    if else_lit is None:
        return False, why
    if (then_lit, else_lit) != (want_then, want_else):
        return False, ("direction is (restrict -> \"%s\", allow -> \"%s\") but this list "
                       "requires (\"%s\", \"%s\")"
                       % (then_lit, else_lit, want_then, want_else))
    return True, "ok"


def _call_literals(cc, lc, list_name):
    """[(ok, note)] for every `arrayOf(...)` that is a CALL argument and names
    [list_name]: the direction is judged inside the array that is ARGUMENT of a
    call (a dead `val x = arrayOf(…)` one line above absorbs a first-mention
    anchor — wide-1024 criterion 14)."""
    out = []
    seen = set()
    for m in re.finditer(r'"%s"' % re.escape(list_name), lc):
        span = _array_span(cc, m.start())
        if span is None or span in seen:
            continue
        seen.add(span)
        j, k = span
        prefix = cc[max(0, j - 60):j]
        if re.search(r"[A-Za-z_][\w.]*\s*\(\s*$", prefix) is None:
            continue  # bound to a `val` — not executed by this handler
        op = cc.index("(", j)
        elems = call_args(cc, op)
        slot = [(a, b) for a, b in elems
                if contains_ident(cc[a:b], "restrict") and "if" in cc[a:b]]
        if not slot:
            out.append((False, "the array passed to the call does not contain an "
                               "`if (restrict) …` direction slot"))
            continue
        for a, b in slot:
            want = (("remove", "add") if list_name.endswith("-whitelist")
                    else ("add", "remove"))
            out.append(_direction_verdict(cc, lc, a, b, *want))
    return out


# ── criterion 13: the option must actually be lifted OUT ────────────────
def _strips_option_out(ccb, lcb, spellings, input_name):
    """True when [body] really lifts [option] out of its input list, judged on
    the body's DATA FLOW (witness A11-1):

      * the enumeration loop must be able to run (a `&& false` condition means
        nothing is lifted, and the option reaches the shell);
      * the ACCUMULATORS must only be written INSIDE that loop — a
        `payload.addAll(tokens)` before it (or a `clear(); addAll(tokens)` after
        it) puts the unfiltered input back after the filter;
      * the option must be compared inside the loop and both accumulators must
        be filled there; the returned pair must be those two accumulators.
    """
    if ccb is None:
        return False, "helper MISSING"
    if not any(sp in lcb for sp in spellings):
        return False, "the body never names the option (no %s)" % " / ".join(sorted(spellings))
    m = re.search(r"\b(while|for)\s*\(", ccb)
    if m is None:
        return False, "no loop over the tokens"
    op = m.end() - 1
    close = match_bracket(ccb, op)
    if close < 0:
        return False, "the loop header does not balance (fail-closed)"
    header = ccb[m.end():close]
    k = close + 1
    while k < len(ccb) and ccb[k] in " \t\r\n":
        k += 1
    if k >= len(ccb) or ccb[k] != "{":
        return False, "the loop has no braced body (fail-closed)"
    bend = match_bracket(ccb, k)
    if bend < 0:
        return False, "the loop body does not balance (fail-closed)"
    if m.group(1) == "while" and not guard_feasible(header):
        return False, ("the loop condition (`%s`) can never be true — nothing is lifted "
                       "and the option reaches the shell" % re.sub(r"\s+", " ", header)[:70])
    if not contains_ident(header, input_name):
        return False, ("the loop does not enumerate the input list `%s` (`%s`)"
                       % (input_name, re.sub(r"\s+", " ", header)[:70]))
    if re.search(r"\breturn\b", ccb[:m.start()]):
        return False, "returns before the loop (an early exit leaves the option in the payload)"
    ret = re.search(r"\breturn\s+([A-Za-z_]\w*)\s+to\s+([A-Za-z_]\w*)", ccb[bend:])
    if ret is None:
        return False, "no `return <payload> to <options>` after the loop"
    acc = [ret.group(1), ret.group(2)]
    if acc[0] == acc[1]:
        return False, "the pair returns the same list twice"
    loop_lc = lcb[k:bend]
    compared = False
    for sp in spellings:
        if re.search(r"(?:==|!=|startsWith|endsWith|contains|\bin\b)\s*%s|%s\s*(?:==|!=|\bin\b)"
                     % (re.escape(sp), re.escape(sp)), loop_lc):
            compared = True
            break
    if not compared:
        return False, ("the option is never COMPARED inside the loop — a mention that no "
                       "branch tests cannot route the token out of the payload")
    for name in acc:
        writes = [w for w in re.finditer(
            r"(?<![\w.])%s\s*\.\s*(?:add|addAll|clear|removeAll)\s*\(" % re.escape(name), ccb)]
        if not writes:
            return False, "the accumulator `%s` is never filled" % name
        outside = [w for w in writes if not (k <= w.start() < bend)]
        if outside:
            return False, ("`%s` is written OUTSIDE the enumeration loop (`%s`) — the "
                           "unfiltered input can bypass the lift"
                           % (name, re.sub(r"\s+", " ", ccb[outside[0].start():outside[0].start() + 40])))
    return True, "ok"


def _replace_chain(ccb, lcb):
    """[(a, b, pos)] for every `.replace(a, b)` in call order."""
    out = []
    for m in re.finditer(r"\.\s*replace\s*\(", ccb):
        args = call_args(ccb, m.end() - 1)
        if len(args) != 2:
            continue
        out.append((lcb[args[0][0]:args[0][1]].strip(),
                    lcb[args[1][0]:args[1][1]].strip(), m.start()))
    return out


def _encode_verdict(ccb, lcb):
    """criterion 3: the escape is produced AND survives the rest of the chain.

    Witness A11-4 kept both required `.replace` calls and appended
    `.replace("%s", "%")`: the produced token was rewritten one step later, so
    every space typed through `input text` became `%`. Presence of a step is
    not the step's effect — the chain is read as a sequence now.
    """
    if ccb is None:
        return False, "helper missing"
    chain = _replace_chain(ccb, lcb)
    if len(chain) != 2:
        return False, ("expected exactly 2 `.replace` steps, found %d" % len(chain))
    (a1, b1, _p1), (a2, b2, _p2) = chain
    if (a1, b1) != ('" "', '"%s"'):
        return False, "first step must map a space to the `%%s` escape (found %s -> %s)" % (a1, b1)
    if (a2, b2) != ('"\'"', '""'):
        return False, "second step must drop the single quote (found %s -> %s)" % (a2, b2)
    produced = []
    for a, b, _p in chain:
        if a in produced:
            return False, ("a later `.replace` rewrites `%s`, which an earlier step just "
                           "produced — the escape does not survive the chain" % a)
        produced.append(b)
    return True, "ok"


def _pkg_bindings(cc, lc, ccb):
    """criterion 6: every `val pkg = …` must read positional index 1.

    Witness A11-6 wrote `pos.getOrNull(999) ?: pos.firstOrNull() ?: return …`:
    the index is out of range (always null), the elvis then falls back to the
    SUB-COMMAND ("list") as the package, and the missing-argument error never
    fires. The criterion is therefore bound to the BINDING, not to the
    substring `pos.getOrNull(1)` anywhere in the function.
    """
    out = []
    if ccb is None:
        return [(False, "notificationChannel MISSING")]
    found = False
    for m in re.finditer(r"\bval\s+pkg\s*=", ccb):
        found = True
        start = m.end()
        end = expr_end(ccb, start)
        rhs = ccb[start:end]
        parts = split_top(rhs, ("?:",))
        lhs = ccb[start + parts[0][0]:start + parts[0][1]].strip() if parts else rhs.strip()
        if re.match(r"^pos\s*\.\s*getOrNull\(\s*1\s*\)$", lhs) is None:
            out.append((False, "`val pkg` reads `%s` — the package must come from "
                               "`pos.getOrNull(1)` (an out-of-range index silently "
                               "falls back to another token)" % lhs[:60]))
            continue
        for a, b in parts[1:]:
            fallback = ccb[start + a:start + b].strip()
            if not fallback.startswith("return"):
                out.append((False, "`val pkg` falls back to `%s` instead of refusing "
                                   "with `return errEnvelope(...)`" % fallback[:60]))
    if not found:
        out.append((False, "notificationChannel has no `val pkg = …` binding at all"))
    return out


def _uid_fallbacks(ccb):
    """criterion 15: every elvis on a `pkgUid(...)` value must REFUSE.

    The old criterion enumerated spellings that evaluate to "0"
    (`?: "0"`, `?: (0).toString()`, `?: Integer.toString(0)`, …). A blacklist
    of zero-spellings is unbounded — `?: (1 - 1).toString()` (witness A11-3)
    walks straight past it. The decision point is "does the fallback refuse?",
    so the elvis right-hand side is read and must start with `return`.
    """
    out = []
    for m in re.finditer(r"(?<![\w.])pkgUid\s*\(", ccb):
        end = expr_end(ccb, m.end() - 1)
        span = ccb[m.start():end]
        for em in re.finditer(r"\?:", span):
            rhs = span[em.end():].lstrip()
            if not rhs.startswith("return"):
                out.append((False, "a `pkgUid(...)` value falls back to `%s` instead of "
                                   "refusing (an unresolvable uid must never become a "
                                   "usable one)" % rhs[:50]))
                break
    return out


def analyse(src):
    """-> list of (criterion, ok, line, note).

    `line` is the 1-based anchor line in the handler (0 = the anchor is gone,
    which is reported as a FAIL rather than a skip; COMMON §4 wants violations
    cited as file:line).
    """
    try:
        cc, lc = views(src)
    except KotlinLexError as exc:
        return [("0 the handler must lex", False, 0,
                 "the file does not lex (%s) — fail-closed: a handler this gate "
                 "cannot read must never pass" % exc)]

    results = []
    flags = re.search(r"val\s+NO_VALUE_FLAGS\s*=\s*setOf\(([^)]*)\)", lc)
    names = set(re.findall(r'"([^"]+)"', flags.group(1))) if flags else set()

    # [dv15-A1 A-02] the value-taking shorts travel with BOTH construction sites
    switches = "booleanFlags = noValueFlagsFor(group)"
    shorts = "valueShortOptions = SHIZUKU_VALUE_SHORT_OPTIONS"
    naked = [
        lc[:m.start()].count("\n") + 1
        for m in re.finditer(r"OffloadArgs\(", lc)
        if "valueShortOptions =" not in lc[m.start():m.start() + 200]
    ]
    pre_scan = re.search(
        r"firstPositionalIndex\(\s*argv[^)]*SHIZUKU_VALUE_SHORT_OPTIONS", lc)
    results.append((
        "1 parser receives declared switches (per group); every OffloadArgs threads value shorts",
        lc.count(switches) == 2 and lc.count(shorts) == 2
        and not naked and pre_scan is not None
        and "valueShortOptions = valueShortOptions" in lc,
        line_of(src, "split.withoutGroup"),
        "expected 2x `%s` and 2x `%s`; naked constructions=%s; pre-scan passes the set=%s"
        % (switches, shorts, naked or "none", "yes" if pre_scan else "NO"),
    ))
    results.append((
        "2 NO_VALUE_FLAGS covers switches, excludes value flags",
        flags is not None and {"block", "unblock"} <= names and "importance" not in names,
        line_of(src, "val NO_VALUE_FLAGS"),
        "declared=%s" % (sorted(names) if flags else "MISSING"),
    ))

    enc_cc, enc_lc, _ = body_of(cc, lc, "encodeInputText")
    ok3, why3 = _encode_verdict(enc_cc, enc_lc)
    results.append((
        "3 input text uses the platform %s escape",
        ok3, line_of(src, "internal fun encodeInputText("),
        "helper " + ("missing" if enc_cc is None else why3),
    ))

    cmd_cc, cmd_lc, cmd_off = body_of(cc, lc, "buildPackageClearCommand")
    if cmd_cc is None:
        ok4, why4 = False, "helper missing"
    else:
        pos = cmd_lc.find('cmd.add("--cache-only")')
        if pos < 0:
            ok4, why4 = False, "never adds `--cache-only` to the pm clear argv"
        else:
            why4 = dead_reason(cc, cmd_off + pos)
            guards = [c for kind, c in enclosing_conditions(cc, cmd_off + pos)
                      if kind == "if"]
            if why4:
                ok4 = False
            elif not any(contains_ident(c, "cacheOnly") for c in guards):
                ok4, why4 = False, ("the `--cache-only` flag is not guarded by the "
                                    "`cacheOnly` argument — a cache-only request would "
                                    "clear user data or vice versa")
            else:
                ok4 = ('"pm", "clear"' in cmd_lc and "trim-caches" not in lc)
                if not ok4:
                    why4 = "the per-package `pm clear` form is gone (global trim-caches stand-in?)"
    results.append((
        "4 package clear is per-package (no global trim-caches stand-in)",
        ok4, line_of(src, 'val cmd = mutableListOf("pm", "clear")'),
        "helper " + ("missing" if cmd_cc is None else why4)
        + ", trim-caches hits=%d" % lc.count("trim-caches"),
    ))

    notify_cc, notify_lc, notify_off = body_of(cc, lc, "handleNotification")
    notify = notify_lc or ""
    wired = "notificationChannel(args.positional.drop(1), args)" in notify
    wire_dead = dead_reason(cc, notify_off + notify.find("notificationChannel(")) if wired else ""
    results.append((
        "5 channel sub-command wired to parsed positionals",
        wired and not wire_dead,
        line_of(src, "notificationChannel("),
        ("handleNotification body" if notify else "handleNotification MISSING")
        + ("" if not wire_dead else " — the call sits under a %s" % wire_dead),
    ))

    chan_cc, chan_lc, _chan_off = body_of(cc, lc, "notificationChannel")
    pkg_binds = _pkg_bindings(cc, lc, chan_cc)
    raw_rest = "rest.getOrNull(" in (chan_lc or "")
    results.append((
        "6 notificationChannel reads positionals, not raw argv",
        chan_cc is not None and not raw_rest and all(ok for ok, _ in pkg_binds),
        line_of(src, "private fun notificationChannel("),
        "helper " + ("missing" if chan_cc is None else
                     ("reads raw `rest`" if raw_rest else
                      "; ".join(why for ok, why in pkg_binds if not ok) or "ok")),
    ))

    clear_cc, clear_lc, clear_off = body_of(cc, lc, "packageClear")
    clear_ok = clear_cc is not None and "clearScope(cacheOnly)" in (clear_lc or "") \
        and "buildPackageClearCommand(" in (clear_lc or "")
    clear_dead = dead_reason(cc, clear_off + (clear_lc or "").find("clearScope(cacheOnly)")) \
        if "clearScope(cacheOnly)" in (clear_lc or "") else ""
    results.append((
        "7 package clear payload reports the scope it actually cleared",
        clear_ok and not clear_dead,
        line_of(src, "private fun packageClear("),
        "helper " + ("missing" if clear_cc is None else
                     (clear_dead or "present")),
    ))

    # ── deep-audit-1020 ISSUE-02: declaration coverage + argv shaping ──────
    used_names = set()
    for m in re.finditer(r'hasFlag\(((?:"[^"]*"\s*,?\s*)+)\)', lc):
        for nm in re.findall(r'"([^"]+)"', m.group(1)):
            if len(nm) > 1:
                used_names.add(nm)
    undeclared = used_names - names - {"help"}
    results.append((
        "8 every hasFlag long name is declared value-less",
        flags is not None and not undeclared,
        line_of(src, "val NO_VALUE_FLAGS"),
        ("undeclared=%s (a switch that eats the next token)" % sorted(undeclared))
        if undeclared else "covered=%d names" % len(used_names),
    ))

    handle_cc, handle_lc, _ = body_of(cc, lc, "handle")
    handle_body = handle_lc or ""
    results.append((
        "9 group taken by position (splitShizukuArgv), not argv[0]",
        "splitShizukuArgv(argv)" in handle_body and "split.group" in handle_body
        and 'val rest = if (group == "exec") split.trailing else split.withoutGroup' in handle_body
        and "firstPositionalIndex(argv" in lc and "group = argv[index]" in lc
        and "group = argv[0]" not in lc,
        line_of(src, "splitShizukuArgv(argv)"),
        "handle body + splitter impl" + ("" if handle_body else " MISSING"),
    ))

    raw_sub = line_of(src, "val sub = rest.firstOrNull()")
    results.append((
        "10 group sub-commands read parsed positionals, not raw rest",
        raw_sub == 0,
        raw_sub if raw_sub else line_of(src, "private fun handlePackage("),
        "raw `val sub = rest.firstOrNull()` hits=%d" % lc.count("val sub = rest.firstOrNull()"),
    ))

    exec_cc, exec_lc, exec_off = body_of(cc, lc, "handleExec")
    exec_ok = exec_cc is not None and 'rest.joinToString(" ")' in (exec_lc or "")
    exec_dead = dead_reason(cc, exec_off + (exec_lc or "").find('rest.joinToString(" ")')) \
        if exec_ok else ""
    results.append((
        "11 exec passthrough joins the raw tail verbatim",
        exec_ok and not exec_dead,
        line_of(src, "private fun handleExec("),
        "handleExec " + ("missing" if exec_cc is None else
                         (exec_dead or 'present, must be `rest.joinToString(" ")`')),
    ))

    data_flag = 'hasFlag("data")' in lc
    data_value = re.search(r'args\.get\(\s*"data"', lc) is not None
    opt_out = 'NO_VALUE_FLAGS - "data"' in lc
    results.append((
        "12 --data read both ways is opted out per group",
        not (data_flag and data_value) or opt_out,
        line_of(src, 'NO_VALUE_FLAGS - "data"'),
        "hasFlag(data)=%s get(data)=%s opt-out=%s" % (data_flag, data_value, opt_out),
    ))

    # 13. the exec payload is a shell command, not an option list
    strip_cc, strip_lc, strip_off = body_of(cc, lc, "stripExecTimeoutTokens")
    split_cc, split_lc, _ = body_of(cc, lc, "splitShizukuArgv")
    strips_in_splitter = "stripExecTimeoutTokens(" in (split_lc or "")
    parser_uses_exec_tokens = "split.execOptionTokens" in handle_body
    strip_params = fn_params(cc, "stripExecTimeoutTokens")
    input_name = strip_params[0] if strip_params else "tokens"
    strips_out, strip_note = _strips_option_out(
        strip_cc, strip_lc, _option_spellings(lc, "--timeout-ms"), input_name)
    results.append((
        "13 exec trims its own options out of the shell payload",
        strips_out and strips_in_splitter and parser_uses_exec_tokens
        and "execOptionTokens = " in lc,
        line_of(src, "internal fun stripExecTimeoutTokens("),
        "helper-strips=%s (%s) splitter-calls-it=%s handle-feeds-execOptionTokens=%s slot=%s"
        % (
            strips_out, strip_note,
            strips_in_splitter,
            parser_uses_exec_tokens,
            "execOptionTokens = " in lc,
        ),
    ))

    # 14. netpolicy direction, judged on the ARGUMENT SLOT of the call
    wl = _call_literals(cc, lc, "restrict-background-whitelist")
    bl = _call_literals(cc, lc, "restrict-background-blacklist")
    wl_bad = [why for ok, why in wl if not ok]
    bl_bad = [why for ok, why in bl if not ok]
    results.append((
        "14 netpolicy list direction follows the restrict flag",
        bool(wl) and bool(bl) and not wl_bad and not bl_bad,
        line_of(src, "restrict-background-whitelist"),
        "live array arguments: whitelist=%d (%d wrong), blacklist=%d (%d wrong)%s"
        % (len(wl), len(wl_bad), len(bl), len(bl_bad),
           "" if not (wl_bad or bl_bad) else "; " + (wl_bad + bl_bad)[0]),
    ))

    # 15. an unresolvable uid must refuse, never fall back to a usable uid
    net_restrict_cc, net_restrict_lc, _ = body_of(cc, lc, "networkRestrict")
    net_stats_cc, net_stats_lc, _ = body_of(cc, lc, "networkStats")
    resolver_cc, resolver_lc, _ = body_of(cc, lc, "extractUidFromDumpsys")
    restrict_refuses = re.search(
        r"pkgUid\(pkg\)\s*\?:\s*return\s+errEnvelope\(", net_restrict_cc or "") is not None
    stats_refuses = re.search(
        r"pkgUid\(pkg\)\?\.toIntOrNull\(\)\s*\?:\s*return\s+errEnvelope\(",
        net_stats_cc or "") is not None
    resolver_nullable = re.search(
        r"internal fun extractUidFromDumpsys\([^)]*\)\s*:\s*String\?", lc) is not None
    # The resolver's contract is "null when unresolvable" — ANY elvis inside it
    # invents a uid. Spelling-free: the fallback does not have to be "0" to be
    # wrong, so the criterion is "there is no fallback", not "no zero spelling".
    resolver_no_fallback = resolver_cc is not None and "?:" not in resolver_cc
    uid_fb = _uid_fallbacks(net_restrict_cc or "") + _uid_fallbacks(net_stats_cc or "")
    results.append((
        "15 unresolvable uid refuses instead of aiming at root",
        restrict_refuses and stats_refuses and resolver_nullable
        and resolver_no_fallback and not uid_fb,
        line_of(src, "val uid = pkgUid(pkg)"),
        "restrict-refuses=%s stats-refuses=%s resolver-String?=%s resolver-has-fallback=%s%s"
        % (
            restrict_refuses, stats_refuses, resolver_nullable,
            not resolver_no_fallback,
            "" if not uid_fb else " (%s)" % uid_fb[0][1],
        ),
    ))

    # [dv15-A1 A-02] the value-taking shorts are declared ONCE
    short_set = re.search(r'internal val SHIZUKU_VALUE_SHORT_OPTIONS = setOf\(([^)]*)\)', lc)
    short_names = set(re.findall(r'"([^"]+)"', short_set.group(1))) if short_set else set()
    results.append((
        "16 short options that take a value are declared once",
        short_names == {"a", "d", "p", "c"},
        line_of(src, "SHIZUKU_VALUE_SHORT_OPTIONS"),
        "declared=%s" % (sorted(short_names) if short_set else "MISSING"),
    ))
    return results


COMPLIANT = '''package com.rikkaminis.app.sandbox.offload

internal val SHIZUKU_VALUE_SHORT_OPTIONS = setOf("a", "d", "p", "c")

class ShizukuOffloadHandler {
    private fun handle(request: Any) {
        val argv = listOf<String>()
        val split = splitShizukuArgv(argv)
        val group = split.group ?: return
        val args = if (group == "exec") {
            OffloadArgs(
                split.execOptionTokens,
                booleanFlags = noValueFlagsFor(group),
                valueShortOptions = SHIZUKU_VALUE_SHORT_OPTIONS,
            )
        } else {
            OffloadArgs(
                split.withoutGroup,
                booleanFlags = noValueFlagsFor(group),
                valueShortOptions = SHIZUKU_VALUE_SHORT_OPTIONS,
            )
        }
        val rest = if (group == "exec") split.trailing else split.withoutGroup
        val sub = args.positional.firstOrNull()
    }

    private fun handleExec(rest: List<String>, args: OffloadArgs): Int {
        val cmd = rest.joinToString(" ")
        return run(cmd)
    }

    private fun inputText(text: String, args: OffloadArgs): Int = run(encodeInputText(text))

    private fun packageClear(pkg: String?, args: OffloadArgs): Int {
        val cacheOnly = args.hasFlag("cache-only")
        val cmd = buildPackageClearCommand(pkg ?: "", cacheOnly, args.get("user"))
        return ok(cmd, clearScope(cacheOnly))
    }

    private fun netData(args: OffloadArgs): Int {
        if (args.hasFlag("data")) return run(args.get("user") ?: "")
        return 0
    }

    private fun actData(args: OffloadArgs): Int {
        return run(args.get("data", "d") ?: "")
    }

    private fun networkRestrict(pkg: String?, args: OffloadArgs, restrict: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "network restrict <pkg>", args)
        val uid = pkgUid(pkg)
            ?: return errEnvelope("OPERATION_FAILED", "network restrict: no uid for '$pkg'", args)
        if (args.hasFlag("wifi")) {
            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy",
                if (restrict) "remove" else "add", "restrict-background-whitelist", uid))
            return ok(r)
        }
        return run(arrayOf("cmd", "netpolicy",
            if (restrict) "add" else "remove", "restrict-background-blacklist", uid))
    }

    private fun networkStats(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        val uid = pkgUid(pkg)?.toIntOrNull()
            ?: return errEnvelope("OPERATION_FAILED", "network stats: no uid for '$pkg'", args)
        val (rx, tx) = parseNetstats("", uid)
        return ok(listOf(rx, tx))
    }

    private fun pkgUid(pkg: String): String? {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "package", pkg))
        return extractUidFromDumpsys(r.stdout)
    }

    private fun handleNotification(rest: List<String>, args: OffloadArgs): Int {
        return notificationChannel(args.positional.drop(1), args)
    }

    private fun notificationChannel(pos: List<String>, args: OffloadArgs): Int {
        val sub = pos.firstOrNull()
        val pkg = pos.getOrNull(1)
        return 0
    }

    companion object {
        internal val NO_VALUE_FLAGS = setOf("block", "unblock", "cache-only", "data", "all", "background", "wifi")
    }
}

internal fun noValueFlagsFor(group: String?): Set<String> =
    if (group == "activity") ShizukuOffloadHandler.NO_VALUE_FLAGS - "data"
    else ShizukuOffloadHandler.NO_VALUE_FLAGS

internal fun splitShizukuArgv(argv: List<String>): ShizukuArgv {
    val index = firstPositionalIndex(argv, ShizukuOffloadHandler.NO_VALUE_FLAGS, SHIZUKU_VALUE_SHORT_OPTIONS)
    if (index < 0) return ShizukuArgv(null, argv, emptyList(), execOptionTokens = argv)
    val group = argv[index]
    val leading = argv.take(index)
    val tail = argv.drop(index + 1)
    val (payload, execOptions) =
        if (group == EXEC_GROUP) stripExecTimeoutTokens(tail) else tail to emptyList()
    return ShizukuArgv(
        group = group,
        withoutGroup = argv.filterIndexed { i, _ -> i != index },
        trailing = payload,
        execOptionTokens = leading + execOptions,
    )
}

private const val EXEC_GROUP = "exec"

internal fun stripExecTimeoutTokens(tokens: List<String>): Pair<List<String>, List<String>> {
    val payload = mutableListOf<String>()
    val options = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val next = tokens.getOrNull(i + 1)
        when {
            token == "--timeout-ms" && next != null && isExecTimeoutValue(next) -> {
                options.add(token)
                options.add(next)
                i += 2
            }
            else -> {
                payload.add(token)
                i += 1
            }
        }
    }
    return payload to options
}

private fun isExecTimeoutValue(value: String): Boolean =
    value.isNotEmpty() && value.all { it in '0'..'9' }

internal fun firstPositionalIndex(
    argv: List<String>,
    booleanFlags: Set<String>,
    valueShortOptions: Set<String> = emptySet(),
): Int {
    val probe = OffloadArgs(argv, booleanFlags = booleanFlags, valueShortOptions = valueShortOptions)
    return if (probe.positional.isNotEmpty()) 0 else -1
}

internal fun extractUidFromDumpsys(dumpsysOutput: String): String? =
    Regex("""userId=(\\d+)""").find(dumpsysOutput)?.groupValues?.get(1)

internal fun encodeInputText(text: String): String =
    text.replace(" ", "%s").replace("'", "")

internal fun buildPackageClearCommand(pkg: String, cacheOnly: Boolean, user: String?): List<String> {
    val cmd = mutableListOf("pm", "clear")
    if (cacheOnly) cmd.add("--cache-only")
    if (user != null) cmd.addAll(listOf("--user", user))
    cmd.add(pkg)
    return cmd
}

internal fun clearScope(cacheOnly: Boolean): String = if (cacheOnly) "cache-only" else "data+cache"
'''

# criterion index (1-based) -> (mutation, expected broken criteria)
BREAKERS = {
    1: ("                split.withoutGroup,\n                booleanFlags = noValueFlagsFor(group),\n",
        "                split.withoutGroup,\n"),
    2: ('setOf("block", "unblock", "cache-only", "data", "all", "background", "wifi")',
        'setOf("block", "unblock", "cache-only", "data", "all", "background", "wifi", "importance")'),
    3: ('text.replace(" ", "%s")', "text.replace(' ', '%')"),
    4: ('cmd.add("--cache-only")', 'cmd.add("--cache-only") // kept'),  # replaced below
    5: ("notificationChannel(args.positional.drop(1), args)", "notificationChannel(rest.drop(1), args)"),
    6: ("pos.getOrNull(1)", "rest.getOrNull(1)"),
    7: ("clearScope(cacheOnly)", "clearScope(false)"),
    8: ('setOf("block", "unblock", "cache-only", "data", "all", "background", "wifi")',
        'setOf("block", "unblock", "data", "all", "background", "wifi")'),
    9: ("val group = split.group ?: return", "val group = argv[0]"),
    10: ("val sub = args.positional.firstOrNull()", "val sub = rest.firstOrNull()"),
    11: ('rest.joinToString(" ")', 'args.positional.joinToString(" ")'),
    12: ('NO_VALUE_FLAGS - "data"', 'NO_VALUE_FLAGS'),
    13: ("if (group == EXEC_GROUP) stripExecTimeoutTokens(tail) else tail to emptyList()",
         "if (group == EXEC_GROUP) tail to emptyList() else tail to emptyList()"),
    14: ('if (restrict) "remove" else "add", "restrict-background-whitelist", uid',
         'if (restrict) "add" else "remove", "restrict-background-whitelist", uid'),
    15: ("val uid = pkgUid(pkg)\n            ?: return errEnvelope",
         'val uid = pkgUid(pkg) ?: "0"\n            ?: return errEnvelope'),
}

# Criterion 13 bundles three anchors (helper present / splitter runs it / the
# parser is fed the isolated set). The main table can only flip one criterion
# per entry, so the other two anchors get their own arms here — otherwise a
# sub-condition could be dead text that no fixture proves live.
EXTRA_BREAKERS = [
    ("13b handle feeds the parser the whole argv again",
     "                split.execOptionTokens,\n",
     "                split.withoutGroup,\n", "13 "),
    ("13c the helper is gone (renamed)",
     "internal fun stripExecTimeoutTokens(tokens: List<String>): Pair<List<String>, List<String>> {",
     "internal fun stripExecTimeoutTokensRenamed(tokens: List<String>): "
     "Pair<List<String>, List<String>> {", "13 "),
    # Criterion 15 fuses the two callers and the resolver; each half gets its own
    # arm so no sub-condition can rot into dead text.
    ("15b the stats caller falls back instead of refusing",
     "val uid = pkgUid(pkg)?.toIntOrNull()\n            ?: return errEnvelope",
     'val uid = pkgUid(pkg)?.toIntOrNull() ?: -1\n            ?: return errEnvelope', "15 "),
    ("15c the resolver falls back to root's uid",
     r'Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)',
     r'Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1) ?: "0"', "15 "),
    # [dv18-A A-01a] criterion 1 was re-anchored to every OffloadArgs(
    # construction; the pre-scan half gets its own arms — a pre-scan that drops
    # the set (or a probe that stops threading it) mis-splits
    # `-p com.x package info` while the handle sites stay correct.
    ("1b the pre-scan call drops the value-short set",
     "firstPositionalIndex(argv, ShizukuOffloadHandler.NO_VALUE_FLAGS, SHIZUKU_VALUE_SHORT_OPTIONS)",
     "firstPositionalIndex(argv, ShizukuOffloadHandler.NO_VALUE_FLAGS)", "1 "),
    ("1c the pre-scan probe stops threading the value shorts",
     "val probe = OffloadArgs(argv, booleanFlags = booleanFlags, valueShortOptions = valueShortOptions)",
     "val probe = OffloadArgs(argv, booleanFlags = booleanFlags)", "1 "),
    # ── [wide-1024] the three witnesses this branch was handed ────────────
    # Criterion 14 (anchor axis): a DEAD arrayOf above the real call used to
    # absorb the first-mention anchor, so the direction actually executed by
    # `runProcess(` was never read. The live call below is inverted.
    ("14b a dead arrayOf absorbs the anchor and the live call is inverted",
     '            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy",\n'
     '                if (restrict) "remove" else "add", "restrict-background-whitelist", uid))',
     '            val _unused = arrayOf("cmd", "netpolicy", if (restrict) "remove" else "add", '
     '"restrict-background-whitelist", uid)\n'
     '            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy", '
     'if (restrict) "add" else "remove", "restrict-background-whitelist", uid))', "14 "),
    # Criterion 13 (dataflow axis): the helper exists and the splitter calls it,
    # but it short-circuits — the option stays in the shell payload.
    ("13d the helper short-circuits and hands the payload back unchanged",
     "internal fun stripExecTimeoutTokens(tokens: List<String>): "
     "Pair<List<String>, List<String>> {\n",
     "internal fun stripExecTimeoutTokens(tokens: List<String>): "
     "Pair<List<String>, List<String>> {\n    if (true) return tokens to tokens\n", "13 "),
    # Criterion 15 (spelling axis): the same root fallback, spelled as an
    # expression instead of the literal `"0"`.
    ("15d the resolver falls back to a re-spelled root uid",
     r'Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)',
     r'Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1) ?: (0).toString()', "15 "),
    # ── b6-t11 witnesses: each reproduced mechanically on the real handler
    # first (pre-fix the gate stayed green, post-fix it turns red) ───────────
    # A11-1 (dataflow): the loop is dead AND the payload was pre-filled with the
    # unfiltered input. Literal mentions all survive; the lift does not.
    ("a11-1 the loop can never run and the raw tokens are pre-filled",
     "    var i = 0\n    while (i < tokens.size) {",
     "    payload.addAll(tokens)\n    var i = 0\n    while (i < tokens.size && false) {", "13 "),
    # A11-1' (dataflow): a live loop, then the payload is rebuilt from the raw
    # input after it — the traffic the lift removed walks straight back in.
    ("a11-1b the payload is rebuilt from the raw tokens after the loop",
     "    return payload to options",
     "    payload.clear()\n    payload.addAll(tokens)\n    return payload to options", "13 "),
    # A11-2 (family-hole): every literal spelling survives; the selection is a
    # constant `if (true)` and the flag only reaches a nested branch.
    ("a11-2 the direction is chosen by a constant branch, not by the flag",
     'if (restrict) "remove" else "add", "restrict-background-whitelist", uid',
     'if (true) "add" else if (restrict) "remove" else "add", '
     '"restrict-background-whitelist", uid', "14 "),
    # A11-3 (spelling): the root fallback re-spelled as an arithmetic identity —
    # the old zero blacklist knew the literal and `(0).toString()` only.
    ("a11-3 the resolver falls back to `(1 - 1).toString()`",
     r'Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)',
     r'Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1) ?: (1 - 1).toString()', "15 "),
    # A11-4 (order): both required `.replace` steps are intact and a third step
    # rewrites the token the first one just produced.
    ("a11-4 a later `.replace` rewrites the `%s` an earlier step produced",
     'text.replace(" ", "%s").replace("\'", "")',
     'text.replace(" ", "%s").replace("\'", "").replace("%s", "%")', "3 "),
    # A11-5 (family-hole): the flag is still conditional and the condition is a
    # conjunction with `false` — `if (false)` was the only spelling the gate knew.
    ("a11-5 the --cache-only add sits under `cacheOnly && false`",
     "if (cacheOnly) cmd.add(\"--cache-only\")",
     "if (cacheOnly && false) cmd.add(\"--cache-only\")", "4 "),
    # A11-6 (dataflow): an out-of-range index, then the sub-command itself as
    # the package — `pos.getOrNull(1)` still appears nowhere anymore, and the
    # missing-argument refusal can never fire.
    ("a11-6 the package index is out of range with a second fallback",
     "val pkg = pos.getOrNull(1)",
     'val pkg = pos.getOrNull(999) ?: pos.firstOrNull() '
     '?: return errEnvelope("INVALID_ARGS", "pkg", args)', "6 "),
    ("a11-6b the package falls back to another token instead of refusing",
     "val pkg = pos.getOrNull(1)",
     "val pkg = pos.getOrNull(1) ?: pos.firstOrNull()", "6 "),
]
# criterion 4 has two halves; break the global-trim half explicitly
BREAKERS[4] = (
    'internal fun buildPackageClearCommand(pkg: String, cacheOnly: Boolean, user: String?): List<String> {\n'
    '    val cmd = mutableListOf("pm", "clear")',
    'internal fun buildPackageClearCommand(pkg: String, cacheOnly: Boolean, user: String?): List<String> {\n'
    '    val cmd = mutableListOf("pm", "trim-caches", "1G")',
)


# criterion 16: the declaration itself (a dropped letter = a dead short option)
BREAKERS[16] = ('internal val SHIZUKU_VALUE_SHORT_OPTIONS = setOf("a", "d", "p", "c")',
                'internal val SHIZUKU_VALUE_SHORT_OPTIONS = setOf("a", "d", "p")')

def selftest():
    print("── selftest: compliant fixture must be green, each breaker must go red ──")
    base = analyse(COMPLIANT)
    failures = []
    reds0 = [c for c, ok, _, _ in base if not ok]
    if reds0:
        failures.append("compliant fixture is not green: %s" % reds0)
        print("  [FAIL] compliant fixture red on: %s" % reds0)
    else:
        print("  [OK] compliant fixture green on all %d criteria" % len(base))

    names = [c for c, _, _, _ in base]
    for idx, (old, new) in BREAKERS.items():
        if idx > len(names):
            failures.append("criterion %d is gone from analyse()" % idx)
            print("  [FAIL] criterion %d: not evaluated (only %d criteria)" % (idx, len(names)))
            continue
        if old not in COMPLIANT:
            failures.append("breaker %d cannot apply (fixture text moved)" % idx)
            print("  [FAIL] breaker %d: anchor not found in fixture" % idx)
            continue
        mutated = COMPLIANT.replace(old, new, 1)
        res = analyse(mutated)
        red = [c for c, ok, _, _ in res if not ok]
        target = names[idx - 1]
        if target not in red:
            failures.append("breaker %d did not turn %r red (red=%s)" % (idx, target, red))
            print("  [FAIL] breaker %d -> %r stayed green (red=%s)" % (idx, target, red))
        else:
            print("  [OK] breaker %d -> red on %r%s" % (idx, target, "" if len(red) == 1 else " (+%d side)" % (len(red) - 1)))

    # Each arm names the criterion family it must flip (2nd element = prefix);
    # the table can only flip one criterion per entry, so sub-conditions of a
    # bundled criterion get their own arms here — otherwise they could rot into
    # dead text that no fixture proves live.
    for label, old, new, prefix in EXTRA_BREAKERS:
        target = next((c for c in names if c.startswith(prefix)), None)
        if target is None:
            failures.append("%s: criterion %r is gone from analyse()" % (label, prefix))
            print("  [FAIL] %s: criterion %r not evaluated" % (label, prefix))
            continue
        if old not in COMPLIANT:
            failures.append("%s: anchor not found in fixture" % label)
            print("  [FAIL] %s: anchor not found in fixture" % label)
            continue
        res = analyse(COMPLIANT.replace(old, new, 1))
        red = [c for c, ok, _, _ in res if not ok]
        if target in red:
            print("  [OK] breaker %s -> red on %r%s" % (label, target, "" if len(red) == 1 else " (+%d side)" % (len(red) - 1)))
        else:
            failures.append("breaker %s did not turn %r red (red=%s)" % (label, target, red))
            print("  [FAIL] breaker %s -> %r stayed green (red=%s)" % (label, target, red))
    if not failures:
        print("SHIZUKU_ARGS_SELFTEST=PASS criteria=%d breakers=%d extra=%d"
              % (len(base), len(BREAKERS), len(EXTRA_BREAKERS)))
    return failures


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "."
    if "--selftest" in sys.argv:
        fails = selftest()
        if fails:
            print("❌ selftest: the gate has no teeth (%d)" % len(fails))
            return 1
        print("✅ selftest: gate flips red on every criterion")
        return 0

    path = os.path.join(root, REL)
    print("━━━ shizuku_args_check ━━━")
    if not os.path.exists(path):
        print("  [ROT] %s not found — the handler moved; keep this gate honest" % REL)
        return 1
    with open(path, encoding="utf-8") as f:
        results = analyse(f.read())
    bad = []
    for crit, ok, line, note in results:
        where = "%s:%d" % (REL.replace(os.sep, "/"), line) if line else "%s:<anchor missing>" % REL
        print("  [%s] %s  (%s) @ %s" % ("OK" if ok else "FAIL", crit, note, where))
        if not ok:
            bad.append((crit, where))
    print("")
    if bad:
        print("❌ shizuku_args_check findings (%d):" % len(bad))
        for crit, where in bad:
            print("   - %s:%s" % (where, crit))
        return 1
    print("✅ android-shizuku-cli argument handling: argv parsing + call sites verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
