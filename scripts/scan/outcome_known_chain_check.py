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
    decl_sites,
    expr_end,
    path_feasible,
)

COORD = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"
SHELL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatShellExecution.kt"
TOOLRES = "src/android/app/src/main/java/com/rikkaminis/app/tools/ToolExecutionResult.kt"
PROMPT = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"

CALL_ROOT = "src/android/app/src/main/java"
MAP_CALL_RE = re.compile(r"\.\s*toToolExecutionResult\s*\(")

CRIT_L0 = "L0 executeWithShellRetry stamps the retry decision's outcomeKnown"
CRIT_L1 = "L1 execute() re-wrap carries the retry verdict's outcomeKnown"
CRIT_L2 = "L2 ToolResultShell declares outcomeKnown and stamps it from the coordinator"
CRIT_L3 = "L3 ToolExecutionResult declares outcomeKnown (data-class equality included)"
CRIT_MAP = "L2->L3 the single mapping passes outcomeKnown"
CRIT_L4 = "L4 T7 ToolFinished reads resultKnown from the result object"
CRIT_L1B = "L1->L2 the coordinator's result carrier reaches ToolResultShell unmodified"
CRIT_L4B = "L4b executeTool hands the carrier's outcomeKnown back unmodified"


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
# seam criteria [R3-t10] — the round-3 witness family: a patch that makes both
# gates of a pair green by breaking the hop the pair straddles.
#   * the SOURCE of the value (L0) was never judged, only the L1 re-wrap, so
#     hardcoding `true` one function down left every downstream hop faithful
#     and therefore green;
#   * the local carrier between L1 and the L2 construction was not tracked, so
#     `result = result.copy(outcomeKnown = true)` one line before
#     `ToolResultShell(...)` reached the constructor with a clean spelling;
#   * the value executeTool RETURNS (after the trace hop) was not judged, so
#     `return result.copy(outcomeKnown = true)` split the trace from the value
#     the agent loop receives.
# All three are judged on the value flow (named argument -> expression ->
# source identifiers), never on a token count.
# --------------------------------------------------------------------------

DECIDE_CALL_RE = re.compile(r"\b\w*[Dd]ecide\w*\s*\(")


def _named_args(text, open_pos):
    """[(field, value_expr)] of the call at `open_pos` (shared bracket/top-split).

    The value expression stops at the top-level comma, so `outcomeKnown =
    result.outcomeKnown || true` is read whole and cannot pass as its own
    prefix.
    """
    close = match_bracket(text, open_pos)
    if close < 0:
        return None
    inner = text[open_pos + 1:close]
    out = []
    for a, b in split_top(inner, (",",)):
        seg = inner[a:b]
        m = re.match(r"\s*([A-Za-z_]\w*)\s*=\s*(?!=)", seg)
        if m:
            out.append((m.group(1), seg[m.end():].strip()))
    return out


def _calls(text, name):
    """[(start, open_pos, close_pos)] for every `name(` call (declaration skipped)."""
    out = []
    for m in re.finditer(r"\b%s\s*\(" % re.escape(name), text):
        prefix = text[max(0, m.start() - 60):m.start()]
        if re.search(r"\bclass\s*$", prefix):
            continue
        open_pos = m.end() - 1
        close = match_bracket(text, open_pos)
        if close < 0:
            out.append((m.start(), open_pos, -1))
            continue
        out.append((m.start(), open_pos, close))
    return out


def _line_of(text, pos):
    return text[:pos].count("\n") + 1


def _l0(coord):
    """L0: the retry source reads the decision's flag (not a constant).

    [R3-t10 07-3] Every hop below L0 is a faithful forwarder, so a hardcoded
    `outcomeKnown = true` in `executeWithShellRetry` makes the whole four-layer
    chain green while the value is already a lie at its origin.
    """
    fb = function_body(coord, "executeWithShellRetry")
    if fb is None:
        return False, "executeWithShellRetry body not found (fail-closed)", -1
    b_start, b_end, _kind = fb
    body = coord[b_start:b_end]
    sites = _calls(body, "CommandResult")
    if not sites:
        return False, "no CommandResult construction in executeWithShellRetry (fail-closed)", -1
    for pos, open_pos, close in sites:
        line = _line_of(coord, b_start + pos)
        if close < 0:
            return False, "CommandResult(...) could not be parsed (fail-closed)", line
        named = _named_args(body, open_pos)
        if named is None:
            return False, "CommandResult(...) arguments could not be parsed (fail-closed)", line
        vals = [v for f, v in named if f == "outcomeKnown"]
        if not vals:
            return (False,
                    "a CommandResult in executeWithShellRetry omits outcomeKnown — "
                    "the field defaults to known=true, so a withheld replay would "
                    "be reported as a known outcome", line)
        val = vals[0]
        if const_bool(val) is not None or not (contains_ident(val, "verdict")
                                               or DECIDE_CALL_RE.search(val)):
            return (False,
                    "the retry source hardcodes outcomeKnown = %r — it must be read "
                    "from the retry decision (`verdict.outcomeKnown`)" % val[:80], line)
        # [R3-t10 X1] the verdict is spelled but laundered one hop later
        # (`.let { true }`): read the verdict's VALUE, not its spelling.
        stamp = None
        vm = re.search(r"\bverdict\.outcomeKnown\b", val)
        if vm and not _is_passthrough(val, "verdict.outcomeKnown"):
            stamp = val
        if stamp is not None:
            return (False,
                    "the retry source wraps the verdict's flag (`%s`) — it must be "
                    "handed over verbatim (`verdict.outcomeKnown`)" % " ".join(stamp.split())[:80],
                    line)
    return True, "", -1


def _l1b(shell):
    """L1->L2: the carrier bound to the coordinator's return is not re-stamped.

    [R3-t10 07-1] The seam between the coordinator hop and the ToolResultShell
    construction: `result = result.copy(outcomeKnown = true)` keeps the L2
    argument spelling (`outcomeKnown = result.outcomeKnown`) compliant and
    replaces the value on the way.
    """
    fb = function_body(shell, "executeShellCommandEngine")
    if fb is None:
        return False, "executeShellCommandEngine body not found (fail-closed)", -1
    b_start, b_end, _kind = fb
    body = shell[b_start:b_end]
    m = re.search(r"(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=\s*"
                  r"ExecutionCoordinator\s*\.\s*execute\s*\(", body)
    if not m:
        return (False,
                "no local bound to `ExecutionCoordinator.execute(...)` in the shell "
                "engine (fail-closed: the carrier cannot be tracked)", -1)
    carrier = m.group(1)
    expected = "%s.outcomeKnown" % carrier
    for pos, kind in decl_sites(body, carrier):
        if kind == "shadow":
            continue  # a nested lambda's own binding is not this carrier
        eq = body.find("=", pos)
        if eq < 0:
            continue
        rhs = body[eq + 1:expr_end(body, eq + 1)]
        line = _line_of(shell, b_start + pos)
        if re.search(r"ExecutionCoordinator\s*\.\s*execute\s*\(", rhs):
            continue  # a fresh coordinator result carries its own verdict
        if contains_ident(rhs, "outcomeKnown") and not rhs_is_exact(rhs, "outcomeKnown", expected):
            return (False,
                    "the carrier `%s` is re-stamped on the way to ToolResultShell "
                    "(`%s`) — a fresh execution or an exact `%s` passthrough only"
                    % (carrier, " ".join(rhs.split())[:90], expected), line)
        if re.search(r"\bCommandResult\s*\(", rhs) and not rhs_is_exact(rhs, "outcomeKnown", expected):
            return (False,
                    "the carrier `%s` is rebuilt from a CommandResult without the "
                    "verdict's flag (`%s`) — the field then defaults to known=true"
                    % (carrier, " ".join(rhs.split())[:90]), line)
    return True, "", -1


def _is_passthrough(rhs, expected):
    """True when the RHS *is* the expected expression (a bare binding, no `field =`)."""
    norm = " ".join(rhs.split()).rstrip(";").strip()
    while norm.startswith("(") and norm.endswith(")") and match_bracket(norm, 0) == len(norm) - 1:
        norm = norm[1:-1].strip()
    return norm == expected


def _l4b(prompt):
    """L4b: what executeTool RETURNS still carries the result's own flag.

    [R3-t10 07-2] The L4 criterion reads the ToolFinished site; the value the
    agent loop receives leaves through the function's `return`s, one hop later.
    `return result.copy(outcomeKnown = true)` keeps the trace honest and hands
    the caller a lie.
    """
    fb = function_body(prompt, "executeTool")
    if fb is None:
        return False, "executeTool body not found (fail-closed)", -1
    b_start, b_end, _kind = fb
    body = prompt[b_start:b_end]
    carrier = None
    for cand in ("result",):
        if contains_ident(body, cand):
            carrier = cand
    if carrier is None:
        return False, "no executed-result carrier found in executeTool (fail-closed)", -1
    expected = "%s.outcomeKnown" % carrier
    rets = []
    for m in re.finditer(r"\breturn\b(?!@)", body):
        rets.append((m.start(), body[m.start():expr_end(body, m.end())].strip()))
    if not rets:
        return False, "no `return` in executeTool (fail-closed)", -1
    if not [txt for _p, txt in rets if contains_ident(txt, carrier)]:
        return (False,
                "no executeTool return hands back the executed result (fail-closed: "
                "the exit value cannot be tracked)", -1)
    for pos, txt in rets:
        if contains_ident(txt, "outcomeKnown") and not rhs_is_exact(txt, "outcomeKnown", expected):
            return (False,
                    "an executeTool return re-stamps the flag (`%s`) — the value the "
                    "agent loop receives must keep `%s`" % (" ".join(txt.split())[:90], expected),
                    _line_of(prompt, b_start + pos))
    # [R3-t10] one hop further: a local that re-stamps the flag and is then
    # handed back (`val out = result.copy(outcomeKnown = true); return out`).
    # Every local binding is judged the same way, so aliasing the stamp cannot
    # hide it — an alias of the field itself (`val k = result.outcomeKnown`) is
    # an exact passthrough and stays green.
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=(?!=)", body):
        rhs = body[m.end():expr_end(body, m.end())]
        if _is_passthrough(rhs, expected):
            continue  # `val k = result.outcomeKnown` — the field, verbatim
        if contains_ident(rhs, "outcomeKnown") and not rhs_is_exact(rhs, "outcomeKnown", expected):
            return (False,
                    "executeTool binds `%s` to a re-stamped flag (`%s`) — a local copy "
                    "of the outcome must keep `%s` so the exit value cannot be laundered"
                    % (m.group(1), " ".join(rhs.split())[:80], expected),
                    _line_of(prompt, b_start + m.start()))
    return True, "", -1


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
    ok, detail, line = _l0(coord)
    out.append((CRIT_L0, ok, detail, line))

    ok, detail, line = _l1(coord)
    out.append((CRIT_L1, ok, detail, line))

    shell = text[SHELL]
    ok, detail, line = _l2(shell)
    out.append((CRIT_L2, ok, detail, line))

    ok, detail, line = _l1b(shell)
    out.append((CRIT_L1B, ok, detail, line))

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

    ok, detail, line = _l4b(text[PROMPT])
    out.append((CRIT_L4B, ok, detail, line))
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
        fun executeShellCommandEngine(cmd: String, toolTitle: String, timedOut: Boolean): ToolResultShell {
            val result = ExecutionCoordinator.execute(cmd)
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
        fun executeTool(name: String): ToolExecutionResult {
            val result = dispatch(name)
            traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = result.outcomeKnown))
            val defectHint = defectRegistryHintFor(result.output)
            if (defectHint != null) return result.copy(output = result.output + defectHint)
            return result
        }
        fun executeShellCommand(): ToolExecutionResult {
            return result.toToolExecutionResult()
        }
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
# ── [R3-t10] the round-3 seam witnesses (report gates 07-1 .. 07-3): one patch
# makes the pair green because the broken hop is the one NEITHER gate owns. ──
# B15 (07-3): the SOURCE hardcodes the flag — every hop below it forwards
# faithfully, so the whole four-layer chain stays green while the value is
# already a lie at its origin.
BREAKERS[15] = (
    [(COORD, "outcomeKnown = verdict.outcomeKnown) to shell",
      "outcomeKnown = true) to shell")],
    CRIT_L0,
)
# B16 (07-1): the L1->L2 seam — the carrier is re-stamped between the
# coordinator's return and the ToolResultShell construction, so L2 still reads
# its compliant spelling off a variable that no longer holds the verdict.
BREAKERS[16] = (
    [(SHELL, "            val withReminder = result.output\n",
      "            result = result.copy(outcomeKnown = true)\n"
      "            val withReminder = result.output\n")],
    CRIT_L1B,
)
# B17 (07-2): the L4 EXIT re-stamps after the trace hop — the trace sees the
# verdict, the agent loop receives `true`.
BREAKERS[17] = (
    [(PROMPT,
      "            if (defectHint != null) return result.copy(output = result.output + defectHint)\n"
      "            return result\n",
      "            if (defectHint != null) return result.copy(output = result.output + defectHint, outcomeKnown = true)\n"
      "            return result.copy(outcomeKnown = true)\n")],
    CRIT_L4B,
)

# B18 (07-2, one hop further): the re-stamp is bound to a local first, so the
# `return` expression reads a clean identifier.
BREAKERS[18] = (
    [(PROMPT,
      "            return result\n",
      "            val handed = result.copy(outcomeKnown = true)\n            return handed\n")],
    CRIT_L4B,
)

# B19 (X1, L0 one hop later): the verdict is spelled but laundered —
# `verdict.outcomeKnown.let { true }`. Spelling checks stay green; the value is
# a constant.
BREAKERS[19] = (
    [(COORD, "outcomeKnown = verdict.outcomeKnown) to shell",
      "outcomeKnown = verdict.outcomeKnown.let { true }) to shell")],
    CRIT_L0,
)

# [R3-t10] tolerant arms: legitimate refactors of the SAME hops stay green.
# Without these a criterion can pass by forbidding every rewrite (the mistake
# the report's 誤伤 check demonstrated on the previous revision).
TOLERANT = [
    ("carrier copy that preserves the flag", [(SHELL,
      "            val withReminder = result.output\n",
      "            result = result.copy(output = result.output.trim())\n"
      "            val withReminder = result.output\n")]),
    ("exit copy that preserves the flag", [(PROMPT,
      "            return result\n",
      "            return result.copy(output = result.output)\n")]),
    ("exit value bound to a local then returned", [(PROMPT,
      "            return result\n",
      "            val handed = result\n            return handed\n")]),
    ("the flag read into a local from the result", [(PROMPT,
      "            val defectHint = defectRegistryHintFor(result.output)\n",
      "            val isOutcomeKnown = result.outcomeKnown\n"
      "            val defectHint = defectRegistryHintFor(result.output)\n")]),
]


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

    # [R3-t10] tolerant arms — the same hops rewritten legitimately stay green.
    for label, pairs in TOLERANT:
        mutated = dict(COMPLIANT)
        missing = [name for name, old, _ in pairs if old not in mutated[name]]
        if missing:
            failures.append("tolerant arm %r anchor missing in %s" % (label, missing))
            print("  [FAIL] tolerant arm %r: anchor not found" % label)
            continue
        for name, old, new in pairs:
            mutated[name] = mutated[name].replace(old, new, 1)
        res = analyse(mutated)
        red = [c for c, ok, _, _ in res if not ok]
        if red:
            failures.append("tolerant arm %r turned %s red" % (label, red))
            print("  [FAIL] tolerant arm %r -> red on %s (a legitimate rewrite)" % (label, red))
        else:
            print("  [OK] tolerant arm %r stays green" % label)
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
