#!/usr/bin/env python3
"""deep-audit-1018 ISSUE-03 (P2) — outcomeKnown propagation gate.

Pins the four-layer contract introduced by branch fix/da1018-offload-wiring:

    ExecutionCoordinator.CommandResult.outcomeKnown        (layer 1, verdict)
      -> ToolResultShell.outcomeKnown                      (layer 2, engine)
      -> ToolExecutionResult.outcomeKnown                  (layer 3, VM wrap)
      -> AgentRunEvent.ToolFinished.resultKnown            (layer 4, T7 trace)

Pre-fix defects pinned here:
  L1  ExecutionCoordinator.execute() re-wraps the retry verdict's CommandResult
      for output truncation and DROPPED outcomeKnown, so the SAME command's two
      exits disagreed (executeWithShellRetry stamps it; this wrap did not).
  L2  ToolResultShell had no outcomeKnown field at all.
  L3  ToolExecutionResult had no outcomeKnown field at all.
  L4  ChatPromptAndTools hardcoded `resultKnown = true` for EVERY tool, so a
      withheld shell replay reached the reducer as a known outcome and
      SUCCEED_WITH_UNKNOWN_TOOL could never fire on the shell path.

This gate is the wiring face (the reducer semantics are covered by
AgentRunReducerTest, the mapping by OutcomeKnownPropagationTest); it fails when
any single hop is re-inlined, hardcoded or dropped.

[B7-t01] The judgement was moved off "the token is written somewhere" and onto
"the value that leaves this hop is still the verdict's". Concretely:

  * structure comes from the shared lexer/AST (`code_view` / `match_bracket` /
    `function_body` / `const_bool` / `path_feasible`) — the self-written
    comment/string stripper and the `(?=\\n\\n|\\Z)` function slice are gone, so
    an empty line before `.copy(...)` no longer cuts the mapping short;
  * every hop is judged on its **chain tail** too: `<ctor>(… outcomeKnown = X …)`
    followed by `.copy(outcomeKnown = true)` is a hardcoded `true`;
  * L4 is judged on EVERY `ToolFinished(` site, and only on the reachable ones
    (`if (false) …` no longer hides a compliant call in front of a hardcoded
    one), with the argument required to END at `result.outcomeKnown`;
  * the L2->L3 mapping is judged on its **call sites** as well (derived by
    walking the source tree, not from a hardcoded file list), so the same
    override one hop away is red too.

Usage: python3 outcome_known_chain_check.py [ROOT]   (ROOT = repo root)
       python3 outcome_known_chain_check.py --root ROOT
       python3 outcome_known_chain_check.py --selftest
Exit:  0 = gate green, 1 = violations (or selftest failures).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    function_body,
    match_bracket,
    split_top,
)
from _kotlin_valueflow import (  # noqa: E402
    code_view,
    contains_ident,
    expr_end,
    path_feasible,
)

COORD = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"
SHELL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatShellExecution.kt"
TOOLRES = "src/android/app/src/main/java/com/rikkaminis/app/tools/ToolExecutionResult.kt"
PROMPT = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"

CALL_ROOT = "src/android/app/src/main/java"
MAP_CALL_RE = re.compile(r"\.\s*toToolExecutionResult\s*\(")

CRIT_L1 = "L1 execute() re-wrap carries the retry verdict's outcomeKnown"
CRIT_L2 = "L2 ToolResultShell declares outcomeKnown and stamps it from the coordinator"
CRIT_L3 = "L3 ToolExecutionResult declares outcomeKnown (data-class equality included)"
CRIT_MAP = "L2->L3 the single mapping passes outcomeKnown"
CRIT_L4 = "L4 T7 ToolFinished reads resultKnown from the result object"


# --------------------------------------------------------------------------
# structure helpers (all positions are on the shared code view, whose length
# equals the source length, so they can be replayed onto the literal text)
# --------------------------------------------------------------------------

def _view(text):
    """Shared lexer view: comments + literal interiors blanked, length kept."""
    return code_view(text)


def _ctor(text, name, anchor=""):
    """(open_paren, close_paren, args_slice) of the first `<name>(` CALL site.

    The declaration is skipped (`data class ToolResultShell(` has the same
    token), and the parentheses are matched by the shared `match_bracket`, so a
    multi-line argument list is one slice instead of a text window.
    """
    for m in re.finditer(r"\b%s\s*\(" % re.escape(name), text):
        prefix = text[max(0, m.start() - 60):m.start()]
        if re.search(r"\bclass\s*$", prefix):
            continue
        open_pos = m.end() - 1
        close = match_bracket(text, open_pos)
        if close < 0:
            continue
        args = text[open_pos:close + 1]
        if anchor and anchor not in args:
            continue
        return open_pos, close, args
    return None


def _chain_tail(text, close):
    """Everything chained onto the closing bracket, or "" when there is none.

    `CommandResult( … )\n            .copy(outcomeKnown = true)` is a call that
    keeps every token of the compliant form and hardcodes the value one line
    later — both on the same line and across (even blank) lines.
    """
    n = len(text)
    i = close + 1
    while i < n and text[i] in " \t\r\n":
        i += 1
    if i + 1 < n and text[i] == "?" and text[i + 1] == ".":
        i += 1
    if i >= n or text[i] != ".":
        return ""
    j = i + 1
    depth = 0
    while j < n:
        ch = text[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch == "\n" and depth == 0:
            k = j + 1
            while k < n and text[k] in " \t\r\n":
                k += 1
            if k < n and text[k] == ".":
                j = k
                continue
            break
        j += 1
    return text[close + 1:j]


def rhs_is_exact(text, field, value):
    """True when some `<field> = <value>` has NOTHING appended to `value`.

    [wide-1024 12-misc-b] A substring test ("outcomeKnown = result.outcomeKnown"
    in text) is satisfied by its own prefix, so `= result.outcomeKnown || true`
    passes — the field is then hardcoded `true` while every token the criterion
    looks for is still present. The value has to END at the assignment: only a
    `,` / `)` / newline may follow.
    """
    for m in re.finditer(r"\b%s\s*=\s*%s" % (re.escape(field), re.escape(value)), text):
        tail = text[m.end(): m.end() + 8].lstrip()
        if not tail or tail[0] in ",)":
            return True
    return False


def _or_absorbed(expr):
    """Is this boolean expression absorbed by a top-level `|| <const true>`?"""
    parts = split_top(expr, ("||",))
    if len(parts) < 2:
        return False
    return any(const_bool(expr[s:e]) is True for s, e in parts)


def _absorbed(text, pos):
    """Is the sub-expression at [pos] absorbed by an enclosing disjunction?

    [B7-t01] witness 11-3 keeps `outcomeKnown == other.outcomeKnown` verbatim
    and wraps it as `(… || true)`: the comparison is still spelled, but it can
    no longer change the result. Checks every enclosing paren group (innermost
    outward) and then the enclosing `return` statement, so the parenthesis-free
    `a && b || true` form is covered too.
    """
    stack = []
    for i in range(pos):
        ch = text[i]
        if ch == "(":
            stack.append(i)
        elif ch == ")" and stack:
            stack.pop()
    for open_pos in reversed(stack):
        close = match_bracket(text, open_pos)
        if close < 0 or close < pos:
            continue
        if _or_absorbed(text[open_pos + 1:close]):
            return True
    r = text.rfind("return", max(0, pos - 4000), pos)
    if r != -1:
        end = expr_end(text, r + len("return"))
        if r < pos < end and _or_absorbed(text[r + len("return"):end]):
            return True
    return False


# --------------------------------------------------------------------------
# one criterion per hop
# --------------------------------------------------------------------------

def _l1(coord):
    """L1: the truncation re-wrap carries the verdict's flag, unchanged after."""
    wrap = _ctor(coord, "CommandResult", "truncated = outputTruncated")
    if wrap is None:
        return False, "no execute() re-wrap with truncated = outputTruncated found", -1
    open_pos, close, args = wrap
    line = coord[:open_pos].count("\n") + 1
    exact = rhs_is_exact(args, "outcomeKnown", "result.outcomeKnown")
    tail = _chain_tail(coord, close)
    if not exact:
        return (False,
                "outcomeKnown dropped in the re-wrap: %r" % args[-160:], line)
    if contains_ident(tail, "outcomeKnown"):
        return (False,
                "the re-wrap's own chain overrides the verdict (`%s`) — the "
                "constructor argument is compliant one line before" % tail.strip()[:120],
                line)
    return True, "", -1


def _l2(shell):
    """L2: ToolResultShell declares the field and stamps it from the verdict."""
    decl_ok = False
    m = re.search(r"data class ToolResultShell\s*\(", shell)
    if m:
        d_open = m.end() - 1
        d_close = match_bracket(shell, d_open)
        decl_ok = d_close > 0 and "val outcomeKnown: Boolean" in shell[d_open:d_close + 1]
    site = _ctor(shell, "ToolResultShell", "")
    stamps = False
    over = False
    if site:
        _o, close, args = site
        stamps = rhs_is_exact(args, "outcomeKnown", "result.outcomeKnown")
        over = contains_ident(_chain_tail(shell, close), "outcomeKnown")
    if not site:
        return False, "no `ToolResultShell(` construction site found (fail-closed)", -1
    if decl_ok and stamps and not over:
        return True, "", -1
    return (False,
            "declared=%s stamped_from_coordinator=%s later_override=%s"
            % (decl_ok, stamps, over), -1)


def _l3(toolres):
    """L3: the field exists and both equals/hashCode actually use it."""
    has_field = bool(re.search(r"val outcomeKnown: Boolean", toolres))
    eq_pos = toolres.find("outcomeKnown == other.outcomeKnown")
    in_equals = eq_pos >= 0 and not _absorbed(toolres, eq_pos)
    in_hash = False
    h = toolres.find("outcomeKnown.hashCode()")
    if h >= 0:
        in_hash = not _absorbed(toolres, h)
    if has_field and in_equals and in_hash:
        return True, "", -1
    return (False,
            "field=%s equals_effective=%s hashCode_effective=%s"
            % (has_field, in_equals, in_hash), -1)


def _map_fn(shell):
    """Does the single mapping pass the value through, at definition AND tail?"""
    fb = function_body(shell, "toToolExecutionResult")
    if fb is None:
        return False, False, "`fun ToolResultShell.toToolExecutionResult(` not found (fail-closed)"
    b_start, b_end, _kind = fb
    body = shell[b_start:b_end]
    site = _ctor(body, "ToolExecutionResult", "")
    mapped = False
    overwritten = False
    if site:
        _o, close, args = site
        mapped = rhs_is_exact(args, "outcomeKnown", "outcomeKnown")
        overwritten = contains_ident(_chain_tail(body, close), "outcomeKnown")
    if not mapped:
        mapped = rhs_is_exact(body, "outcomeKnown", "outcomeKnown")
    if not overwritten:
        overwritten = bool(
            re.search(r"\.\s*(?:copy|also|apply|run)\s*[(\{][\s\S]*?outcomeKnown", body))
    return mapped, overwritten, ""


def _call_site_violations(sources):
    """Every `.toToolExecutionResult(…)` site must not re-stamp the value."""
    errs = []
    calls = 0
    for rel in sorted(sources):
        src = sources[rel]
        for m in MAP_CALL_RE.finditer(src):
            open_pos = m.end() - 1
            close = match_bracket(src, open_pos)
            if close < 0:
                errs.append("%s: `.toToolExecutionResult(…)` could not be parsed "
                            "(fail-closed)" % rel)
                continue
            calls += 1
            tail = _chain_tail(src, close)
            if contains_ident(tail, "outcomeKnown"):
                errs.append("%s: the caller overrides the mapped value (`%s`)"
                            % (rel, tail.strip()[:120]))
    if not calls:
        errs.append("no `.toToolExecutionResult(…)` call site in the source tree "
                    "(fail-closed: the mapping is not the only producer)")
    return errs


def _l4(prompt):
    """L4: EVERY reachable ToolFinished site reads the result's own flag."""
    sites = []
    for m in re.finditer(r"AgentRunEvent\s*\.\s*ToolFinished\s*\(", prompt):
        open_pos = m.end() - 1
        close = match_bracket(prompt, open_pos)
        args = prompt[open_pos:close + 1] if close > 0 else ""
        sites.append((m.start(), args))
    live = [(p, a) for p, a in sites if a and path_feasible(prompt, p)]
    bad = [a for _p, a in live
           if not rhs_is_exact(a, "resultKnown", "result.outcomeKnown")]
    if not live:
        return (False,
                "no REACHABLE `AgentRunEvent.ToolFinished(` site (%d spelled, all in "
                "dead branches) — the flag never reaches the reducer" % len(sites), -1)
    if bad:
        return (False,
                "reachable ToolFinished site(s) do not read the result's flag: %s"
                % " | ".join(a.strip()[:80] for a in bad), -1)
    return True, "", -1


def analyse(text, sources=None):
    """text = {rel: code-view source} for the four hops; sources = call sites."""
    if sources is None:
        sources = text
    out = []

    coord = text[COORD]
    ok, detail, line = _l1(coord)
    out.append((CRIT_L1, ok, detail, line))

    shell = text[SHELL]
    ok, detail, line = _l2(shell)
    out.append((CRIT_L2, ok, detail, line))

    ok, detail, line = _l3(text[TOOLRES])
    out.append((CRIT_L3, ok, detail, line))

    mapped, overwritten, detail = _map_fn(shell)
    site_errs = _call_site_violations(sources)
    ok = mapped and not overwritten and not site_errs
    out.append((CRIT_MAP, ok, "" if ok else
                "definition_passes=%s definition_overrides=%s %s"
                % (mapped, overwritten, " / ".join(site_errs) or detail), -1))

    ok, detail, line = _l4(text[PROMPT])
    out.append((CRIT_L4, ok, detail, line))
    return out


# --------------------------------------------------------------------------
# selftest fixtures
# --------------------------------------------------------------------------

COMPLIANT = {
    COORD: '''
        fun execute(cmd: String): CommandResult {
            val (result, shell) = executeWithShellRetry(cmd)
            val outputTruncated = false
            // [deep-audit-1018 ISSUE-03] carry the verdict's outcomeKnown
            CommandResult(
                output = output,
                exitCode = result.exitCode,
                durationMs = durationMs,
                truncated = outputTruncated,
                outcomeKnown = result.outcomeKnown,
            )
        }
        fun executeWithShellRetry(cmd: String): Pair<CommandResult, Shell> {
            return CommandResult(output, code, ms, truncated, outcomeKnown = verdict.outcomeKnown) to shell
        }
    ''',
    SHELL: '''
        fun run(cmd: String, toolTitle: String, timedOut: Boolean): ToolResultShell {
            val result = coordinator.execute(cmd)
            val withReminder = result.output
            return ToolResultShell(
                output = withReminder,
                success = result.exitCode == 0,
                toolTitle = toolTitle,
                timedOut = timedOut,
                // [deep-audit-1018 ISSUE-03] stamp from the coordinator verdict.
                outcomeKnown = result.outcomeKnown,
            )
        }
        internal fun ToolResultShell.toToolExecutionResult(): ToolExecutionResult =
            ToolExecutionResult(
                output = output,
                success = success,
                toolTitle = toolTitle,
                timedOut = timedOut,
                outcomeKnown = outcomeKnown,
            )
        internal data class ToolResultShell(
            val output: String,
            val success: Boolean,
            val toolTitle: String,
            val timedOut: Boolean,
            val outcomeKnown: Boolean = true,
        )
    ''',
    TOOLRES: '''
        data class ToolExecutionResult(
            val output: String,
            val success: Boolean,
            val outcomeKnown: Boolean = true,
        ) {
            override fun equals(other: Any?): Boolean {
                if (other !is ToolExecutionResult) return false
                return output == other.output && outcomeKnown == other.outcomeKnown
            }
            override fun hashCode(): Int {
                var result = output.hashCode()
                result = 31 * result + outcomeKnown.hashCode()
                return result
            }
        }
    ''',
    PROMPT: '''
        fun executeShellCommand(): ToolExecutionResult {
            return result.toToolExecutionResult()
        }
        traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = result.outcomeKnown))
    ''',
}

BREAKERS = {}
# ── [wide-1024 12-misc-b] witness breakers: every token the old criterion
# matched is still present; the VALUE is hardcoded one expression later.
# B6: the re-wrap keeps the assignment and ORs it to constant true.
BREAKERS[6] = (
    [(COORD, "                outcomeKnown = result.outcomeKnown,\n",
      "                outcomeKnown = result.outcomeKnown || true,\n")],
    CRIT_L1,
)
# B7: the mapping passes outcomeKnown and then .copy()s it to true.
BREAKERS[7] = (
    [(SHELL, "                outcomeKnown = outcomeKnown,\n            )\n",
      "                outcomeKnown = outcomeKnown,\n            ).copy(outcomeKnown = true)\n")],
    CRIT_MAP,
)
# B8: the excluded middle — `x || !x` is `true` without the literal.
BREAKERS[8] = (
    [(PROMPT, "resultKnown = result.outcomeKnown",
      "resultKnown = result.outcomeKnown || !result.outcomeKnown")],
    CRIT_L4,
)
# B1: the pre-fix shape — re-wrap drops outcomeKnown
BREAKERS[1] = (
    [(COORD, "                outcomeKnown = result.outcomeKnown,\n", "")],
    CRIT_L1,
)
# B2: ToolResultShell loses the field entirely
BREAKERS[2] = (
    [(SHELL, "            val outcomeKnown: Boolean = true,\n", "")],
    CRIT_L2,
)
# B3: ToolExecutionResult loses the field (and its equality participation)
BREAKERS[3] = (
    [
        (TOOLRES, "            val outcomeKnown: Boolean = true,\n", ""),
        (TOOLRES, " && outcomeKnown == other.outcomeKnown", ""),
        (TOOLRES, "        result = 31 * result + outcomeKnown.hashCode()\n", ""),
    ],
    CRIT_L3,
)
# B4: the mapping re-inlined without the field
BREAKERS[4] = (
    [(SHELL, "                outcomeKnown = outcomeKnown,\n", "")],
    CRIT_MAP,
)
# B5: the pre-fix literal
BREAKERS[5] = (
    [(PROMPT, "resultKnown = result.outcomeKnown", "resultKnown = true")],
    CRIT_L4,
)
# ── [B7-t01] the 24-gate witness set (report gates 11-1 .. 11-6) ───────────
# B9 (11-1): the L1 re-wrap keeps the compliant argument and `.copy`s the
# value to true — the family hole left open on the hop that B6 hardened.
BREAKERS[9] = (
    [(COORD, "                outcomeKnown = result.outcomeKnown,\n            )\n",
      "                outcomeKnown = result.outcomeKnown,\n            ).copy(outcomeKnown = true)\n")],
    CRIT_L1,
)
# B10 (11-2): same `|| true` shape as B6, one hop lower (L2 stamps on `in`).
BREAKERS[10] = (
    [(SHELL, "                outcomeKnown = result.outcomeKnown,\n",
      "                outcomeKnown = result.outcomeKnown || true,\n")],
    CRIT_L2,
)
# B11 (11-3): the comparison stays spelled but is absorbed by `|| true`.
BREAKERS[11] = (
    [(TOOLRES, "output == other.output && outcomeKnown == other.outcomeKnown",
      "output == other.output && (outcomeKnown == other.outcomeKnown || true)")],
    CRIT_L3,
)
# B12 (11-4): a blank line before `.copy(...)` used to cut the mapping slice
# short (`(?=\n\n|\Z)`); Kotlin accepts the chain across the blank line.
BREAKERS[12] = (
    [(SHELL, "                outcomeKnown = outcomeKnown,\n            )\n",
      "                outcomeKnown = outcomeKnown,\n            )\n\n            .copy(outcomeKnown = true)\n")],
    CRIT_MAP,
)
# B13 (11-5): a compliant call in a dead `if (false)` + a hardcoded `!false`
# call that `re.search` never looked at.
BREAKERS[13] = (
    [(PROMPT,
      "        traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = result.outcomeKnown))",
      "        if (false) traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = result.outcomeKnown))\n"
      "        traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = !false))")],
    CRIT_L4,
)
# B14 (11-6): the override moved to the CALL SITE (scope hole: the mapping's
# `.copy` check only looked at the definition file).
BREAKERS[14] = (
    [(PROMPT, "return result.toToolExecutionResult()",
      "return result.toToolExecutionResult().copy(outcomeKnown = true)")],
    CRIT_MAP,
)


def selftest():
    print("-- selftest: compliant fixture green, each breaker red on its own criterion --")
    failures = []
    base = analyse(COMPLIANT)
    reds0 = [c for c, ok, _, _ in base if not ok]
    if reds0:
        failures.append("compliant fixture is red on %s" % reds0)
        print("  [FAIL] compliant fixture red on: %s" % reds0)
    else:
        print("  [OK] compliant fixture green on all %d criteria" % len(base))

    for idx in sorted(BREAKERS):
        pairs, target = BREAKERS[idx]
        mutated = dict(COMPLIANT)
        missing = [name for name, old, _ in pairs if old not in mutated[name]]
        if missing:
            failures.append("breaker %d anchor missing in %s" % (idx, missing))
            print("  [FAIL] breaker %d: anchor not found in fixture" % idx)
            continue
        for name, old, new in pairs:
            mutated[name] = mutated[name].replace(old, new, 1)
        res = analyse(mutated)
        red = [c for c, ok, _, _ in res if not ok]
        if target not in red:
            failures.append("breaker %d did not turn %r red (red=%s)" % (idx, target, red))
            print("  [FAIL] breaker %d -> %r stayed green (red=%s)" % (idx, target, red))
        else:
            side = "" if len(red) == 1 else " (+%d side)" % (len(red) - 1)
            print("  [OK] breaker %d -> red on %r%s" % (idx, target, side))
    return failures


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def _derived_call_sources(root, texts):
    """Walk the published tree for `.toToolExecutionResult(` sites.

    [B7-t01] scope fix: the override can sit in ANY caller, so the site set is
    derived from the tree instead of a hardcoded file list. Files already
    loaded are reused; a file that fails to lex is reported fail-closed.
    """
    sources = dict(texts)
    errs = []
    base = os.path.join(root, CALL_ROOT)
    if not os.path.isdir(base):
        return sources, errs
    for dirpath, _dirs, files in os.walk(base):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            if rel in sources:
                continue
            try:
                raw = open(path, encoding="utf-8").read()
            except OSError:
                continue
            if ".toToolExecutionResult" not in raw:
                continue
            try:
                sources[rel] = code_view(raw)
            except KotlinLexError as exc:  # fail-closed: never fall back to raw
                errs.append("%s: KotlinLexError (%s) — call-site scan is blind" % (rel, exc))
    return sources, errs


def main():
    if "--selftest" in sys.argv:
        fails = selftest()
        if fails:
            print("SELFTEST FAIL (%d)" % len(fails))
            return 1
        print("OUTCOME_KNOWN_CHAIN_SELFTEST=PASS (%d fixtures)" % (1 + len(BREAKERS)))
        return 0

    args = [a for a in sys.argv[1:]]
    if "--root" in args:
        root = args[args.index("--root") + 1]
    else:
        root = args[0] if args else "."
    root = root or "."

    text = {}
    for rel in (COORD, SHELL, TOOLRES, PROMPT):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            print("FAIL %s not found under root %s" % (rel, root))
            return 1
        try:
            text[rel] = code_view(open(path, encoding="utf-8").read())
        except KotlinLexError as exc:
            print("FAIL %s: KotlinLexError (%s) — fail-closed, no raw-text fallback"
                  % (rel, exc))
            return 1

    sources, scan_errs = _derived_call_sources(root, text)
    results = analyse(text, sources)
    failed = [(c, d) for c, ok, d, _ in results if not ok]
    for c, ok, d, line in results:
        if ok:
            print("  [OK] %s" % c)
        else:
            print("  [FAIL] %s%s - %s" % (c, " (line %d)" % line if line > 0 else "", d))
    for e in scan_errs:
        print("  [FAIL] scope: %s" % e)
    print("outcome_known_chain: %d criteria green, %d failed"
          % (len(results) - len(failed), len(failed) + len(scan_errs)))
    if failed or scan_errs:
        print("outcome-known chain gate: RED - the propagation contract is violated")
        return 1
    print("GATE=GREEN outcome_known_chain")
    return 0


if __name__ == "__main__":
    sys.exit(main())
