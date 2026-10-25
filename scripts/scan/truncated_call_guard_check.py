#!/usr/bin/env python3
"""Truncated-call guard wiring gate (deep-audit-1018 ISSUE-01 / ISSUE-12c).

## Why this gate exists

The truncated-tool-call guard ([TruncatedToolCallPolicy]) used to consult ONE
truncation source: the protocol finish_reason. A turn cut off by a network EOF
carries NO finish_reason at all — the providers surface that as
`LLMStreamChunk.Finished.truncated`, and OpenAIProvider's EOF detector gated
its Finished emission on text/reasoning only, so a tool-call-ONLY turn (zero
prose) never set the flag either. Net effect: the P0 failure class
"half-streamed JSON arguments dispatched to the executor" sailed through
every layer, and [ToolJsonRepair] Strategy 1 then CLOSED the cut-off object
into a syntactically valid, semantically wrong payload — a silent side effect
instead of a loud refusal. Four wiring points had to change together, and no
mechanical check would have noticed any one of them regressing:

  1. the policy must accept the stream-level flag (3rd parameter) and must
     actually read it in the verdict condition — a parameter that exists but
     is never consulted is indistinguishable from a regression;
  2. the engine dispatch site must FEED the flag (the KDoc of the original
     site admits finishReason was the only input);
  3. OpenAIProvider's EOF guard must count tool-call events (Anthropic's
     `toolChars` counterpart — same family, previously divergent);
  4. the sub-agent loop must refuse through the SAME policy (single source of
     truth) and must keep the tool_use/tool_result pairing;
  5. [ISSUE-12c] the error-shaped continuation must run the same stacking
     guard as its EOF-stub sibling through the shared
     [EofStubReminderStackGuard] — an inline second predicate would drift.

[deep-audit-1045 A-02] The sibling failure of the same family: the Gemini SSE
loop's EOF `else` arm used to send `Finished(lastFinishReason ?: "end_turn")`,
i.e. it INVENTED a stop reason on a fully empty EOF. `failOnSilentEmptyCompletion`
decides by `Finished.stopReason != null`, so the fabricated value made an empty
relay response read as a clean finish and no TransientError was ever raised.
The terminal decision now lives in the pure `geminiEofTerminal(...)`, which
passes an absent finishReason through as null (criterion 6/7 below).

Behaviour is covered by unit tests (TruncatedCallGuardTest /
EofStubStackingGuardTest / GeminiEofTerminalDecisionTest); this gate pins the
WIRING, because none of these sites can execute without the Android runtime.

## Escape hatch

`truncated-guard-ok: <reason>` on the line above a pinned expression.

Usage: python3 scripts/scan/truncated_call_guard_check.py [repo_root] [--selftest]
Exit:  0 = all wiring present, 1 = violation (file:line printed)
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse                      # noqa: E402
from _kotlin_ast import const_bool, if_blocks, stmt_spans  # noqa: E402

MAIN = "src/android/app/src/main/java/com/rikkaminis/app"
POLICY = MAIN + "/ui/chat/TruncatedToolCallPolicy.kt"
ENGINE = MAIN + "/ui/chat/AgentLoopEngine.kt"
OPENAI = MAIN + "/provider/openai/OpenAIProvider.kt"
ANTHROPIC = MAIN + "/provider/anthropic/AnthropicProvider.kt"
TOOL_EXECUTORS = MAIN + "/ui/chat/ChatToolExecutors.kt"
GEMINI = MAIN + "/provider/gemini/GeminiProvider.kt"
SIGNALS = MAIN + "/provider/openai/OpenAiStreamSignals.kt"
# [dv29 A-01] Anthropic's tool-lifecycle activity weights — the single home of
# "does this SSE event prove the turn produced tool-call progress".
ANTHROPIC_SIGNALS = MAIN + "/provider/anthropic/AnthropicStreamSignals.kt"

C1_SIGNATURE = ("fun rejectionReason(toolName: String, finishReason: String?, "
                "turnTruncated: Boolean = false): String?")
C1_CONDITION = "if (!ceilingDeclared && !turnTruncated) return null"
C1_PREFIX = "[TRUNCATED] Tool '$toolName' was NOT executed:"
C1_PREFIX_COUNT = 2  # ceiling source + EOF source; a third wording is a gate event

C2_ENGINE_CALL = "TruncatedToolCallPolicy.rejectionReason(name, turnFinishReason, turnTruncated)"

C3_OPENAI_GUARD = ("if (!finishedSent && (contentLen > 0 || reasoningLen > 0 "
                   "|| toolCallEventCount > 0)) {")
C3_ANTHROPIC_GUARD = "if (!sawClearFinish && (contentChars > 0 || toolChars > 0)) {"

C4_SUBAGENT_DECL = "internal suspend fun runSubagentLoop("
C4_POLICY_CALL = "TruncatedToolCallPolicy.rejectionReason("
C4_REFUSAL_GATE = "if (truncatedRefusal != null) {"
C4_PAIRING = "content = truncatedRefusal, isError = true"
C4_NO_SECOND_WORDING = '"[TRUNCATED]'

C5_SHARED_GUARD = "EofStubReminderStackGuard.isStaleEofStubReminder("
C5_GUARD_CALLS = 2
C5_OLD_INLINE = "val tailIsEofStubReminder ="

# [deep-audit-1045 A-02] Gemini's EOF terminal decision must be the pure
# function, and it must never fabricate a stop reason for an absent one.
C6_GEMINI_TERMINAL_CALL = "geminiEofTerminal(lastFinishReason, contentChars, toolChars)"
C6_GEMINI_SEND = "send(LLMStreamChunk.Finished(terminal.stopReason, truncated = terminal.truncated))"
C6_GEMINI_PURE_DECL = "internal fun geminiEofTerminal("
C6_GEMINI_PURE_ELSE = "GeminiEofTerminal(stopReason = lastFinishReason, truncated = false)"
C7_FABRICATED = "lastFinishReason ?:"

# [deep-audit-1048 A-01 / A-08] The two criteria below are DERIVED, not pinned
# by name: the counter variable is read out of the pinned EOF guard, the two
# streaming flavours are told apart by the branch that selects them, and the
# error-mapping function is discovered from the call sites. Renaming anything
# must not blind the gate.
C8_COUNTER_VISIBILITY = 2       # distinct feed sites of the guard's counter
C8_ERROR_STATUS_TOKEN = "429"   # the family the status derivation must produce
C8_ERROR_TRANSIENT_TOKEN = "500"

# [dv29 A-01] Anthropic's three tool-lifecycle arms must ALL feed the EOF
# guard's tool counter, and they must do it through the pure weights in
# AnthropicStreamSignals.kt. Nothing below is pinned by symbol name: the arms
# are located by their protocol labels, the counter is read out of the pinned
# EOF guard, and the weights are whatever pure Int functions that module
# declares. Renaming keeps the gate working; dropping a feed does not.
ANTHROPIC_LIFECYCLE_LABELS = [
    '"content_block_start" ->',
    '"content_block_delta" ->',
    '"content_block_stop" ->',
    '"message_delta" ->',   # terminator of the stop arm, not a judged arm
]
ANTHROPIC_TOOL_USE_TOKEN = '"tool_use"'

# Structural anchors for the selftest arms below. They name the SHAPE of the
# fix, so a rewrite of the fix must update them — that is deliberate: an arm
# that silently stops mutating anything would report a false green.
CHAT_FEED = "toolCallEventCount += chatCompletionsToolCallEventWeight(toolCalls.length())"
RESPONSES_FEED = """toolCallEventCount += responsesToolCallEventWeight(
                        eventType = type,
                        itemType = event.optJSONObject("item")?.optString("type"),
                        argsDeltaLen = event.optString("delta", "").length,
                    )"""
CHAT_INLINE_STATUS = """openAiStreamErrorStatus(
                                inlineError.optString("code", ""),
                                inlineError.optString("type", ""),
                            )"""
RESPONSES_STATUS_CALL = """mapHttpError(
                                openAiStreamErrorStatus(code, err?.optString("type", "") ?: ""),
                                err?.toString() ?: event.toString(),
                            )"""

# [wide4] Mutation anchors for the wide4 arms. Only the arms use them: every
# criterion above derives its shape from structure, so renaming product code
# keeps the gate working while these arms are updated deliberately.
SUBAGENT_CALL = ("                val truncatedRefusal = "
                 "TruncatedToolCallPolicy.rejectionReason(")
ENGINE_CONSUMER = "                if (truncatedRefusal != null) {"

ESCAPE = "truncated-guard-ok:"
SELFTEST_MARK = "TRUNCATED_CALL_GUARD_SELFTEST=PASS"

# [dv29 A-01] Mutators for the Anthropic feed arms. These are NOT criteria —
# they exist so the arms below can dirty the tree in the shape a regression
# would take. The criteria themselves derive everything from structure.
ANTHROPIC_START_ANCHOR = 'val contentBlock = event.optJSONObject("content_block")'
ANTHROPIC_START_FEED = ("toolChars += toolUseStartActivityChars("
                        "currentToolId!!, currentToolName!!)")
ANTHROPIC_STOP_FEED = ("toolChars += toolUseStopActivityChars("
                       "currentToolId!!, currentToolName!!)")
ANTHROPIC_DELTA_FEED = "toolChars += toolInputDeltaActivityChars(partial)"


LEX_FAILURES = []


def strip_kotlin_comments(text):
    """Blank out comments (newlines preserved, so line numbers stay valid).

    Prose must never satisfy a pinned-expression criterion — the KDoc of the fix
    itself quotes the defect's old line. [wide4] Delegated to the shared lexer:
    this scanner did not nest `/* a /* b */ c */` and did not know about char
    literals or raw strings, so a file could be rewritten to place real code
    inside what the gate believed was a comment.
    """
    ok, reason, views = analyse(text)
    if not ok:
        LEX_FAILURES.append(reason)
        return text
    return views["comments"]


def line_of(text, needle):
    idx = text.find(needle)
    return text.count("\n", 0, idx) + 1 if idx >= 0 else 0


# ─────────────────────────────────────────────────────────────────────────
# [wide3-04] Structural helpers shared by the four hardened criteria below.
#
# Every one of them exists because a criterion used to ask "does this text
# appear somewhere in the file?" — which a regression can satisfy while the
# effect is gone:
#   * C1  an early `return null` keyed on ONE truncation source disarms the other;
#   * C2/C4 the policy call's result is wrapped in `if (false) … else null`;
#   * C8/C10 the activity weight is fed a literal (`(0)`, `("", "")`).
# ─────────────────────────────────────────────────────────────────────────

def mask_code(text):
    """Blank comments AND string-literal contents; offsets and newlines are
    preserved, so structure parsing on the result keeps line numbers valid.

    [wide4] Delegated to the shared lexer. The local scanner flipped a single
    `quote` character on every quote it met and had no notion of a raw string
    (three quotes) at all, so a raw literal whose content is a `/*` opened a
    REAL-looking block comment inside the literal — the witness used that to
    hide a second `return null` from every criterion below. A lexing failure is
    recorded and turned into a fail-closed finding by `check()`.
    """
    ok, reason, views = analyse(text)
    if not ok:
        LEX_FAILURES.append(reason)
        return text
    return views["code"]


def match_paren(text, open_idx):
    """Index of the ')' matching the '(' at `open_idx`, or -1."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def split_top(text, sep=","):
    """Top-level split of `text` on `sep` (quote/bracket aware)."""
    spans, depth, start, i, n = [], 0, 0, 0, len(text)
    quote = ""
    while i < n:
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = ""
            i += 1
            continue
        if c in "\"'":
            quote = c
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == sep and depth == 0:
            spans.append((start, i))
            start = i + 1
        i += 1
    spans.append((start, n))
    return spans


def split_args(text):
    inner = text
    return [inner[a:b].strip() for a, b in split_top(inner, ",")]


_RET_TYPE_ONLY = re.compile(r"^\s*(:\s*[\w<>,\[\]\s.?:]+)?\s*$")


def function_body(code, decl):
    """(body_text, body_offset) for a `fun name(...)` with a BLOCK body.

    The body brace must follow the parameter list; a lambda default inside the
    params would otherwise be taken for the body (`block: J.() -> Unit = { … }`).
    """
    start = code.find(decl)
    if start < 0:
        return None, -1
    po = code.find("(", start)
    if po < 0:
        return None, -1
    pc = match_paren(code, po)
    if pc < 0:
        return None, -1
    ob = code.find("{", pc)
    if ob < 0 or not _RET_TYPE_ONLY.match(code[pc + 1:ob]):
        return None, -1
    depth = 0
    for i in range(ob, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[ob:i + 1], ob
    return None, -1


def if_bodies(code):
    """[(cond, body_start, body_end)] for every `if (...)` body."""
    out = []
    for m in re.finditer(r"\bif\s*\(", code):
        po = code.find("(", m.end() - 1)
        pc = match_paren(code, po)
        if pc < 0:
            continue
        cond = code[po + 1:pc]
        j = pc + 1
        while j < len(code) and code[j] in " \t\r\n":
            j += 1
        if j < len(code) and code[j] == "{":
            depth = 0
            k = j
            while k < len(code):
                if code[k] == "{":
                    depth += 1
                elif code[k] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                k += 1
            out.append((cond, j, k))
        else:
            k = j
            depth = 0
            while k < len(code) and not (code[k] == "\n" and depth == 0):
                if code[k] in "([{":
                    depth += 1
                elif code[k] in ")]}":
                    depth -= 1
                k += 1
            out.append((cond, j, k))
    return out


def is_constant_expr(text):
    """True when the expression holds no identifier/member reference at all —
    so its value cannot depend on the data it is supposed to describe."""
    t = re.sub(r'"[^"\n]*"', " ", text)
    t = re.sub(r"'[^'\n]*'", " ", t)
    t = re.sub(r"\b(?:true|false|null)\b", " ", t)
    t = re.sub(r"\b\d[\w.]*\b", " ", t)
    t = re.sub(r"[\s,=()]", "", t)
    return t == ""


def feed_calls(code, counter):
    """[(fn, args_text, pos)] for every `counter += fn(args)` / `counter = fn(args)`."""
    out = []
    for m in re.finditer(r"\b%s\b\s*\+?=(?!=)\s*([A-Za-z_]\w*)\s*\("
                         % re.escape(counter), code):
        po = m.end() - 1
        pc = match_paren(code, po)
        if pc < 0:
            out.append((m.group(1), None, m.start()))
            continue
        out.append((m.group(1), code[po + 1:pc], m.start()))
    return out


def refusal_branch_live(code, var):
    """(ok, why) — the `if (<var> != null)` branch that CONSUMES the verdict.

    [wide4] `operative_call` pins the PRODUCER (`val truncatedRefusal = policy(…)`).
    The wide4 witness left the producer byte-identical and inverted the CONSUMER
    instead: `if (false && truncatedRefusal != null) {` keeps the gate text, the
    pairing, the `continue` and the wording count, and skips every refused call.
    """
    target = normalize_cond(var + " != null")
    for cond, b0, b1, _e0, _e1 in if_blocks(code):
        if normalize_cond(cond) != target:
            continue
        if const_bool(cond) is False:
            return (False, "the refusal branch `if (%s)` is short-circuited by a "
                           "constant, so the verdict is never consumed" % cond.strip())
        if "continue" not in code[b0:b1]:
            return (False, "the refusal branch no longer skips the truncated call "
                           "(`continue` left the branch)")
        return True, ""
    return (False, "the refusal branch `if (%s != null)` is missing — the verdict is "
                   "computed and dropped" % var)


def flag_shadowed(code, names, before):
    """[(name, line)] for a same-name declaration of a stream flag before `before`.

    [wide4] `rargs[2] != "turnTruncated"` pinned the ARGUMENT'S SPELLING. The
    witness kept the spelling and re-declared the name one line earlier
    (`val turnTruncated = false`), so the call read a shadowing constant. A stream
    flag is declared ONCE — the `var` the stream callback writes; a second
    declaration before the read is, by Kotlin scoping, the value the read sees.
    """
    bad = []
    for name in names:
        decls = [m.start() for m in
                 re.finditer(r"\b(?:val|var)\s+%s\b" % re.escape(name), code)]
        decls = [d for d in decls if d < before]
        if len(decls) > 1:
            bad.append((name, code.count("\n", 0, decls[1]) + 1))
    return bad


def arg_is_branchy(args):
    """A weight argument may not carry a branch — `is_constant_expr` cannot see
    `if (false) x else 0` (it contains an identifier, so it reads as "derived")."""
    return bool(re.search(r"\b(?:if|when|else)\b|\?:", args))


def arg_is_zero_scaled(args):
    """`x * 0` / `0 * x` / `0.coerceAtMost(x)` — the identifier is present and
    decides nothing."""
    return bool(re.search(r"\*\s*0(?![\d.])|(?<!\w)0\.\w+\s*\(", args))


def dead_weight_arg(args):
    """True when a fed weight cannot observe the stream.

    Three shapes, all of which keep the call, the callee name and the counter:
    an empty argument list, a pure literal (`(0)`, `("", "")`), and a dead
    branch or zero multiplier wrapped around a real expression.
    """
    if args is None:
        return True
    return (is_constant_expr(args) or arg_is_branchy(args)
            or arg_is_zero_scaled(args))


def operative_call(code, var, callee):
    """(ok, why, line, args) — `val <var> = <callee>(…)` as the WHOLE initialiser.

    "The call appears in the function" is not the criterion: wrapping it in
    `if (false) … else null` keeps every character and drops the decision.
    """
    hits = [m for m in re.finditer(r"\bval\s+%s\s*=\s*" % re.escape(var), code)]
    if not hits:
        return False, "`val %s =` not found" % var, 0, []
    if len(hits) > 1:
        return (False, "`val %s =` assigned %d times — the refusal is no longer "
                       "single-source" % (var, len(hits)),
                code.count("\n", 0, hits[0].start()) + 1, [])
    m = hits[0]
    line = code.count("\n", 0, m.start()) + 1
    rhs = m.end()
    while rhs < len(code) and code[rhs] in " \t\r\n":
        rhs += 1
    if not code.startswith(callee, rhs):
        return (False, "`val %s` is not initialised by %s(…) — the verdict is wrapped "
                       "in a conditional/short-circuit, so the call's result is not what "
                       "the refusal branch reads" % (var, callee), line, [])
    po = rhs + len(callee)
    if po >= len(code) or code[po] != "(":
        return False, "%s is not called with an argument list" % callee, line, []
    pc = match_paren(code, po)
    if pc < 0:
        return False, "%s(…) argument list does not balance" % callee, line, []
    j = pc + 1
    while j < len(code) and code[j] in " \t":
        j += 1
    args = split_args(code[po + 1:pc])
    if j < len(code) and code[j] == "\r":
        j += 1
    if j < len(code) and code[j] == "\n":
        # A statement ends here — unless the next line continues the expression
        # with an operator, which is how `.let { }` / `?: null` are spelled when
        # they do not fit on one line.
        k = j + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k < len(code) and (code[k] == "." or code.startswith("?:", k)
                              or code[k] == "?"):
            return (False, "the verdict's value is continued on the next line (%s…) "
                           "instead of being stored — the chain drops the decision"
                           % code[k:k + 12].strip(), line, args)
        return True, "", line, args
    if j < len(code) and code[j] not in ";})":
        return (False, "the verdict is used as a sub-expression (…%s"
                       "%s) instead of being the value itself — a chain such as "
                       "`.let { }`, `?: null` or a branch drops the decision"
                       % (callee, code[pc:pc + 1]), line, args)
    return True, "", line, args


# [wide3-04] The two truncation sources the verdict must AND together. A
# `return null` that tests only one of them disarms the other at every call
# site: `if (!ceilingDeclared) return null` added after the real condition
# leaves an EOF-truncated turn (ceiling absent, stream cut) reading as "clean".
C1_CEILING_TOKENS = ("ceilingDeclared", "isTruncatedFinish", "finishReason")

# [wide4] The verdict condition, normalised. The wide3 rule asked "does the
# condition mention both sources", so the next witness wrote a tautology
# (`(turnTruncated || !turnTruncated)`) or a constant prefix (`!false &&`) that
# mentions both and decides nothing. The condition must BE the pinned AND of the
# two sources, in either operand order — anything else is a spelling the gate
# cannot see the value of.
C1_CONDITION_OK = (
    "!ceilingDeclared&&!turnTruncated",
    "!turnTruncated&&!ceilingDeclared",
)


def normalize_cond(cond):
    return re.sub(r"\s+", "", cond)


def policy_single_source_returns(policy_src):
    """[(condition, line)] for early `return null`s keyed on exactly one source."""
    code = mask_code(policy_src)
    body, off = function_body(code, "fun rejectionReason(")
    if body is None:
        return [("<rejectionReason body not located>", 0)]
    bad = []
    # [wide4] ONE `return null`, not "no single-source if". The wide4 witness
    # added `when { !ceilingDeclared -> return null }` after the real AND
    # condition: a `when` has no `if (` for `if_bodies` to see, and the ceiling
    # wording / prefix count stay untouched, so the old rule read green while
    # every EOF-truncated turn was again waved through.
    n_returns = len(re.findall(r"\breturn\s+null\b", body))
    if n_returns != 1:
        bad.append((
            "<%d return-null exits in rejectionReason (expected exactly 1)>"
            % n_returns,
            code.count("\n", 0, off) + 1,
        ))
    for cond, b0, b1 in if_bodies(body):
        if "return null" not in body[b0:b1]:
            continue
        if normalize_cond(cond) not in C1_CONDITION_OK:
            bad.append((cond.strip(), code.count("\n", 0, off + b0) + 1))
    return bad


def strip_kotlin_comments_keep(text):
    return strip_kotlin_comments(text)


_TOP_FUN = re.compile(r"internal fun (\w+)\s*\(")


def top_level_int_funs(text):
    """Every `internal fun NAME(` in the pure module, with the parameter list
    that follows it. Prose never counts — comments are stripped first."""
    code = strip_kotlin_comments(text)
    out = {}
    for m in _TOP_FUN.finditer(code):
        close = code.find(")", m.end())
        if close < 0:
            continue
        out[m.group(1)] = code[m.end():close]
    return out


_MAP_CALL = re.compile(r"mapHttpError\(\s*(?:(\w+)\s*\(|([^,\n]+))")


def map_http_first_args(text):
    """(callee, raw) for the first argument of every mapHttpError( call.
    callee is set only when the argument is itself a CALL — that is the shape
    the single-source criterion requires."""
    code = strip_kotlin_comments(text)
    out = []
    for m in _MAP_CALL.finditer(code):
        out.append((m.group(1), (m.group(2) or "").strip()))
    return out


def line_of_regex(text, pattern):
    m = re.search(pattern, text)
    return text.count("\n", 0, m.start()) + 1 if m else 0


def load(root):
    paths = [POLICY, ENGINE, OPENAI, ANTHROPIC, TOOL_EXECUTORS, GEMINI, SIGNALS,
             ANTHROPIC_SIGNALS]
    texts = []
    for rel in paths:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            print("❌ truncated-call gate: missing %s" % rel)
            return None
        with open(path, "r", encoding="utf-8") as f:
            texts.append(f.read())
    return tuple(texts)


def escaped_segment(text, needle):
    """True when the line directly above the pinned expression carries the
    escape hatch — deliberate decisions stay possible, but must be written."""
    idx = text.find(needle)
    while idx >= 0:
        line_start = text.rfind("\n", 0, idx)
        prev_end = text.rfind("\n", 0, line_start)
        if ESCAPE in text[prev_end + 1:line_start + 1]:
            return True
        idx = text.find(needle, idx + 1)
    return False


def anthropic_lifecycle_branches(code):
    """{label: body slice} for the three tool-lifecycle arms of the Anthropic
    SSE `when (eventType)`.

    Sliced by SIBLING PROTOCOL LABELS, never by brace pairing: the arms carry
    lambdas and strings that defeat naive brace matching, and a reformatted or
    reordered `when` must not blind the criteria. Comments must already be
    stripped by the caller (the fix's own KDoc names every label).
    """
    pos = [code.find(label) for label in ANTHROPIC_LIFECYCLE_LABELS]
    if any(p < 0 for p in pos):
        return None
    return {ANTHROPIC_LIFECYCLE_LABELS[i]: code[pos[i]:pos[i + 1]]
            for i in range(len(ANTHROPIC_LIFECYCLE_LABELS) - 1)}


def counter_writes(text, counter):
    """Every `counter += …` / `counter = …` site (never `==`)."""
    return [m.start() for m in
            re.finditer(r"\b%s\b\s*\+?=(?!=)" % re.escape(counter), text)]


def anthropic_tool_counter(anth):
    """The EOF guard's TOOL counter, derived rather than pinned.

    The guard compares two counters against zero; the tool one is the counter
    actually written inside a tool-lifecycle arm. Any surviving feed identifies
    it, so deleting ONE feed still lets the other two criteria be judged (the
    deleted one reports red) instead of collapsing into a name-not-found.
    """
    counters = []
    for name in re.findall(r"([A-Za-z_]\w*)\s*>\s*0\b", C3_ANTHROPIC_GUARD):
        if name not in counters:
            counters.append(name)
    branches = anthropic_lifecycle_branches(anth) or {}
    delta = branches.get('"content_block_delta" ->', "")
    inner = delta.find('"input_json_delta" ->')
    regions = []
    if inner >= 0:
        regions.append(delta[inner:])
    regions.append(branches.get('"content_block_stop" ->', ""))
    regions.append(branches.get('"content_block_start" ->', ""))
    for region in regions:
        for name in counters:
            if counter_writes(region, name):
                return name
    return counters[-1] if counters else None


def check(policy, engine, openai, anthropic, tool_executors, gemini, signals,
          anthropic_signals):
    """Return [(criterion, file, line, message)] — empty list means wired."""
    problems = []
    # [wide4] Lex the whole surface first and FAIL CLOSED. Every criterion below
    # reasons over the masked projections of these eight files; if one of them
    # does not tokenise, the mask is a fiction and a green verdict would be a
    # statement about text nobody read (the parser-death axis).
    del LEX_FAILURES[:]
    for rel, text in ((POLICY, policy), (ENGINE, engine), (OPENAI, openai),
                      (ANTHROPIC, anthropic), (TOOL_EXECUTORS, tool_executors),
                      (GEMINI, gemini), (SIGNALS, signals),
                      (ANTHROPIC_SIGNALS, anthropic_signals)):
        ok_lex, why_lex, _views = analyse(text)
        if not ok_lex:
            problems.append((
                "unparseable-kotlin", rel, 0,
                "the gate cannot tokenise this file (%s) — it refuses to judge a "
                "lexing surface it does not trust" % why_lex))
    if LEX_FAILURES:
        return problems

    def add(criterion, rel, text, needle, message):
        if not escaped_segment(text, needle):
            problems.append((criterion, rel, line_of(text, needle), message))

    # C1 — the policy knows the stream-level source AND acts on it.
    if C1_SIGNATURE not in policy:
        problems.append(("policy-flag-signature", POLICY,
                         line_of(policy, "fun rejectionReason("),
                         "rejectionReason lost its turnTruncated parameter"))
    elif C1_CONDITION not in policy:
        problems.append(("policy-flag-condition", POLICY,
                         line_of(policy, "fun rejectionReason("),
                         "turnTruncated exists but the verdict condition ignores it"))
    elif policy.count(C1_PREFIX) != C1_PREFIX_COUNT:
        problems.append(("policy-two-sources", POLICY,
                         line_of(policy, C1_PREFIX),
                         "expected %d refusal wordings (%s), found %d — a source lost its message"
                         % (C1_PREFIX_COUNT, C1_PREFIX, policy.count(C1_PREFIX))))
    else:
        # [wide3-04] …and no EARLY `return null` may key on a single source.
        # `if (!ceilingDeclared) return null` inserted after the real AND
        # condition leaves the ceiling wording and the prefix count untouched
        # while every EOF-truncated turn reads as clean again.
        for cond, cline in policy_single_source_returns(policy):
            problems.append((
                "policy-single-source-early-return", POLICY, cline,
                "`%s` decides on ONE truncation source — `return null` must happen "
                "exactly once, in the two-source AND (`!ceilingDeclared && "
                "!turnTruncated`). Any other null exit (a single-source `if`, a `when` "
                "arm, a duplicated guard) lets the other source stop guarding: a turn "
                "cut by EOF while ceilingDeclared is false reaches the executor with a "
                "partial argument object" % cond,
            ))

    # C2 — the engine dispatch site feeds the flag, and the verdict IS the value
    # the refusal branch reads.
    # [wide3-04] `engine.count(C2_ENGINE_CALL) != 1` was a substring count: wrapping
    # the same call in `if (false) … else null` keeps the text and drops the guard.
    eng_code = mask_code(engine)
    ok, why, eline, eargs = operative_call(
        eng_code, "truncatedRefusal", "TruncatedToolCallPolicy.rejectionReason")
    if not ok:
        problems.append(("engine-feeds-flag", ENGINE, eline or
                         line_of(engine, "truncated tool call REFUSED"),
                         "the engine tool-call guard: " + why))
    elif len(eargs) < 3 or eargs[2] != "turnTruncated":
        problems.append(("engine-feeds-flag", ENGINE, eline,
                         "the engine must pass the stream-level flag as the third "
                         "argument (turnTruncated), found (%s) — a literal there means "
                         "the EOF source never reaches the policy"
                         % ", ".join(eargs)))
    elif "turnFinishReason" not in eargs[1]:
        problems.append(("engine-feeds-flag", ENGINE, eline,
                         "the engine no longer passes the protocol finish reason (%s)"
                         % eargs[1]))
    elif (shadowed := flag_shadowed(eng_code, ("turnTruncated", "turnFinishReason"),
                                    eng_code.find("val truncatedRefusal ="))):
        problems.append((
            "engine-feeds-flag", ENGINE, shadowed[0][1],
            "turnTruncated is shadowed or overwritten with a constant — `%s` is "
            "declared again before the refusal call, so the policy reads the "
            "shadowing value, not the one the stream callback wrote" % shadowed[0][0]))
    else:
        # [wide4] …and the CONSUMER. The witness kept the producer byte-identical
        # and wrote `if (false && truncatedRefusal != null) {`: the call, the
        # wording, the pairing and the `continue` all survive, and every refused
        # call is dispatched anyway.
        live, why_live = refusal_branch_live(eng_code, "truncatedRefusal")
        if not live:
            problems.append((
                "engine-feeds-flag", ENGINE, eline,
                "the engine refusal branch (if (truncatedRefusal != null)) is missing "
                "or short-circuited — " + why_live))

    # C3 — OpenAI's EOF guard counts tool-call events; Anthropic keeps its own.
    if openai.count(C3_OPENAI_GUARD) != 1:
        problems.append(("openai-eof-counts-toolcalls", OPENAI,
                         line_of(openai, "[RC2-truncated-detection]"),
                         "EOF-without-Finished guard must contain: %s (found %d)"
                         % (C3_OPENAI_GUARD, openai.count(C3_OPENAI_GUARD))))
    if anthropic.count(C3_ANTHROPIC_GUARD) != 1:
        problems.append(("anthropic-sibling-pin", ANTHROPIC,
                         line_of(anthropic, "[RC2-truncated-detection]"),
                         "Anthropic EOF guard shape must keep counting toolChars"))

    # C4 — the sub-agent loop refuses through the shared policy, pairing intact.
    loop_start = tool_executors.find(C4_SUBAGENT_DECL)
    if loop_start < 0:
        problems.append(("subagent-loop-missing", TOOL_EXECUTORS, 0,
                         "runSubagentLoop not found"))
    else:
        # slice to the next top-level doc comment after the loop
        nxt = tool_executors.find("\n/**", loop_start)
        loop = tool_executors[loop_start:nxt if nxt > 0 else len(tool_executors)]
        # [wide3-04] Same axis as C2: the call must be the refusal value itself,
        # and it must carry the stream flag. `if (false) rejectionReason(…) else
        # null` keeps the call, the gate and the pairing, and disarms the loop.
        ok, why, rline, rargs = operative_call(
            mask_code(loop), "truncatedRefusal",
            "TruncatedToolCallPolicy.rejectionReason")
        if not ok:
            problems.append(("subagent-refusal-wired", TOOL_EXECUTORS,
                             line_of(tool_executors, C4_SUBAGENT_DECL),
                             "runSubagentLoop: " + why))
        elif len(rargs) < 3 or rargs[2] != "turnTruncated":
            problems.append(("subagent-refusal-wired", TOOL_EXECUTORS,
                             line_of(tool_executors, C4_SUBAGENT_DECL),
                             "runSubagentLoop must pass the stream-level flag as the third "
                             "argument (turnTruncated), found (%s)" % ", ".join(rargs)))
        elif (shadowed := flag_shadowed(mask_code(loop), ("turnTruncated",
                                                         "turnFinishReason"),
                                        mask_code(loop).find("val truncatedRefusal ="))):
            # [wide4] The name in the argument list is not the value: a same-name
            # `val turnTruncated = false` one line above makes the policy read a
            # constant while `rargs[2]` still says `turnTruncated`.
            problems.append((
                "subagent-refusal-wired", TOOL_EXECUTORS,
                line_of(tool_executors, C4_SUBAGENT_DECL),
                "turnTruncated is shadowed or overwritten with a constant — "
                "runSubagentLoop declares `%s` again before the refusal call, so the "
                "policy reads the shadowing value" % shadowed[0][0]))
        elif C4_REFUSAL_GATE not in loop:
            problems.append(("subagent-refusal-gate", TOOL_EXECUTORS,
                             line_of(tool_executors, C4_SUBAGENT_DECL),
                             "the refusal branch (if (truncatedRefusal != null)) is gone"))
        elif C4_PAIRING not in loop:
            problems.append(("subagent-refusal-pairing", TOOL_EXECUTORS,
                             line_of(tool_executors, C4_SUBAGENT_DECL),
                             "a refused call must still produce a tool_result (isError = true)"))
        elif C4_NO_SECOND_WORDING in loop:
            problems.append(("subagent-single-source", TOOL_EXECUTORS,
                             line_of(tool_executors, C4_SUBAGENT_DECL),
                             "runSubagentLoop must not hand-write a second [TRUNCATED] wording"))

    # C5 — both stub-reminder injection sites share one stacking guard.
    guard_calls = engine.count(C5_SHARED_GUARD)
    if guard_calls != C5_GUARD_CALLS:
        problems.append(("stack-guard-shared", ENGINE,
                         line_of(engine, "internal object EofStubReminderStackGuard"),
                         "expected %d shared guard call sites, found %d"
                         % (C5_GUARD_CALLS, guard_calls)))
    if C5_OLD_INLINE in engine:
        problems.append(("stack-guard-no-inline-twin", ENGINE,
                         line_of(engine, C5_OLD_INLINE),
                         "the EOF-stub family's second inline predicate is back — use the shared object"))

    # C6/C7 — Gemini's EOF terminal decision. Comments are stripped first: the
    # fix's own KDoc quotes the defect's old line, and prose must never satisfy
    # a pinned-expression criterion.
    code = strip_kotlin_comments(gemini)
    wired = (
        code.count(C6_GEMINI_TERMINAL_CALL) == 1
        and C6_GEMINI_SEND in code
        and C6_GEMINI_PURE_DECL in code
    )
    if not wired:
        problems.append(("gemini-eof-terminal-pure", GEMINI,
                         line_of(code, C6_GEMINI_PURE_DECL),
                         "the SSE EOF path must decide through the pure %s and emit %s "
                         "(found %d verdict call(s))"
                         % (C6_GEMINI_PURE_DECL, C6_GEMINI_SEND,
                            code.count(C6_GEMINI_TERMINAL_CALL))))
    if C6_GEMINI_PURE_DECL in code and (
            C6_GEMINI_PURE_ELSE not in code or C7_FABRICATED in code):
        # An absent finishReason must be passed through, never substituted:
        # failOnSilentEmptyCompletion decides by `Finished.stopReason != null`,
        # so a fabricated "end_turn" turns an empty relay response into a clean
        # finish. File-scope on purpose (the defect lived at the call site and
        # moves with it), anchored on the two variables involved — the
        # non-streaming `finishReason ?: "end_turn"` path uses a different
        # variable and is a separate (registered) decision.
        problems.append(("gemini-eof-passthrough", GEMINI,
                         line_of(code, C7_FABRICATED) if C7_FABRICATED in code
                         else line_of(code, C6_GEMINI_PURE_DECL),
                         "a fabricated stop reason for an absent finishReason is back "
                         "(%s) — it makes failOnSilentEmptyCompletion read an empty EOF as "
                         "a clean finish; the else arm must stay '%s'"
                         % (C7_FABRICATED, C6_GEMINI_PURE_ELSE)))


    # ------------------------------------------------------------------
    # C8/C9 — [dv28 A-01 / A-08] the OpenAI stream's two flavours must BOTH
    # be visible to the EOF guard, and every in-stream error must reach the
    # ONE status derivation.
    #
    # Both criteria are DERIVED from structure, never from a symbol name:
    #   * the counter is whatever identifier the pinned guard compares to 0;
    #   * the "weight" derivations are every pure Int function in the signals
    #     module MINUS the status derivation;
    #   * the status derivation is whatever function is passed as the first
    #     argument of a mapHttpError( call.
    # Renaming any of them keeps the gate working; deleting a wire-up does not.
    # ------------------------------------------------------------------
    ok_code = strip_kotlin_comments(openai)
    sig_code = strip_kotlin_comments(signals)
    escapable = ESCAPE in sig_code

    status_fns = sorted({callee for callee, _raw in map_http_first_args(openai)
                         if callee})
    declared = top_level_int_funs(signals)
    # A "weight" is a derivation the PROVIDER must call: a declared function that
    # the signals module does not itself call (an internal helper like the family
    # table is reachable only through the status derivation).
    weight_fns = sorted({
        name for name in declared
        if name not in status_fns
        and len(re.findall(r"\b%s\s*\(" % re.escape(name), sig_code)) <= 1
    })

    guard_counters = re.findall(r"([A-Za-z_]\w*)\s*>\s*0\b", C3_OPENAI_GUARD)
    feeds = {}
    for name in guard_counters:
        feeds[name] = re.findall(r"\b%s\s*\+=\s*([A-Za-z_]\w*)\s*\(" % name, ok_code)
    call_fed = {name: callees for name, callees in feeds.items() if callees}
    wired_counter = None
    for name, callees in sorted(call_fed.items()):
        if len(callees) >= C8_COUNTER_VISIBILITY and set(callees) <= set(declared):
            wired_counter = name
            break

    if not escapable:
        if wired_counter is None:
            problems.append((
                "openai-count-covers-both-flavours", OPENAI,
                line_of(openai, "[RC2-truncated-detection]"),
                "the EOF guard's counters (%s) are not fed from %d+ sites through the "
                "shared pure derivations in OpenAiStreamSignals — a flavour that stops "
                "feeding the counter silently loses the truncated-turn signal "
                "(found: %s)"
                % (", ".join(guard_counters), C8_COUNTER_VISIBILITY,
                   {k: v for k, v in feeds.items()})))
        else:
            callees = set(feeds[wired_counter])
            missing = sorted(set(weight_fns) - callees)
            extra = sorted(callees - set(weight_fns))
            if missing:
                problems.append((
                    "openai-count-covers-both-flavours", OPENAI,
                    line_of(openai, "[RC2-truncated-detection]"),
                    "these pure weight derivations are declared in the signals module but "
                    "never fed into '%s': %s — a flavour lost its wire-up"
                    % (wired_counter, ", ".join(missing))))
            if extra:
                problems.append((
                    "openai-count-covers-both-flavours", OPENAI,
                    line_of(openai, "[RC2-truncated-detection]"),
                    "'%s' is fed through functions that are not declared in "
                    "OpenAiStreamSignals (%s) — the derivation is no longer single-source"
                    % (wired_counter, ", ".join(extra))))
            # [wide3-04] …and the derivation must be FED data. The substring
            # arm above only asks whether the weight function is called; calling
            # it with a literal (`chatCompletionsToolCallEventWeight(0)`) keeps
            # the call, the name set and the guard count, and makes the counter
            # depend on nothing.
            ok_masked = mask_code(openai)
            for fn, fargs, fpos in feed_calls(ok_masked, wired_counter):
                if fargs is not None and not dead_weight_arg(fargs):
                    continue
                problems.append((
                    "openai-count-derived", OPENAI,
                    ok_masked.count("\n", 0, fpos) + 1,
                    "'%s' is fed through %s(%s) — a constant / dead-branch / "
                    "zero-scaled weight cannot observe the stream, so the "
                    "truncated-turn signal is silently lost"
                    % (wired_counter, fn, (fargs or "").strip() or "<empty>")))

        # C9 — one status derivation, reachable from both in-stream error arms.
        if len(status_fns) != 1:
            problems.append((
                "openai-stream-error-single-source", OPENAI,
                line_of(openai, "mapHttpError("),
                "expected exactly one status derivation passed to mapHttpError(, found %s — "
                "an in-stream error path is deciding its own family"
                % (status_fns or "none")))
        elif status_fns[0] not in declared:
            problems.append((
                "openai-stream-error-single-source", OPENAI,
                line_of(openai, "mapHttpError("),
                "%s is not a pure top-level function of the signals module" % status_fns[0]))
        else:
            fn = status_fns[0]
            body_start = sig_code.find("internal fun %s(" % fn)
            body = sig_code[body_start:]
            for token, why in ((C8_ERROR_STATUS_TOKEN, "the rate-limit family"),
                               (C8_ERROR_TRANSIENT_TOKEN, "the transient family")):
                if token not in body:
                    problems.append((
                        "openai-stream-error-single-source", SIGNALS,
                        line_of(signals, "internal fun %s(" % fn),
                        "%s does not map %s (%s)" % (fn, token, why)))
            uses = len(re.findall(r"\b%s\s*\(" % re.escape(fn), ok_code))
            if uses < 2:
                problems.append((
                    "openai-stream-error-single-source", OPENAI,
                    line_of(openai, "mapHttpError("),
                    "%s is referenced %d time(s) in the provider — both in-stream error "
                    "arms (chat-completions inline error AND response.failed) must use it"
                    % (fn, uses)))
            failed_arm = ok_code.find('"response.failed" ->')
            if failed_arm >= 0:
                arm = ok_code[failed_arm:failed_arm + 4000]
                close = arm.find("\n                        type ==")
                arm = arm[:close] if close > 0 else arm
                if fn not in arm:
                    problems.append((
                        "openai-stream-error-single-source", OPENAI,
                        line_of(openai, '"response.failed" ->'),
                        "the response.failed arm does not route through %s — a symbolic "
                        "rate-limit code must not be hand-classified into TransientError"
                        % fn))
            elif ok_code.count('"response.failed"') < 1:
                problems.append((
                    "openai-stream-error-single-source", OPENAI, 0,
                    "the Responses terminal-failure arm is gone"))

    # ------------------------------------------------------------------
    # C10 — [dv29 A-01] Anthropic's EOF guard counts tool-call activity, so
    # EVERY tool-lifecycle arm has to feed that counter. It used to be fed by
    # the argument deltas alone: a pure-tool turn (contentChars == 0) cut
    # between the `tool_use` block announcement and the first non-empty delta
    # therefore looked like a completely empty stream, the orphan flush sent
    # `ToolCallComplete(id, name, {})`, and `turnTruncated` stayed false so the
    # executor ran an argument-less call. The Gemini and OpenAI arms of this
    # same family already had this shape (C6/C8) — this is the sibling that was
    # only PINNED, never checked.
    #
    # All four criteria are derived: the arms come from the protocol labels,
    # the counter is read out of the pinned guard, the weights are whatever
    # pure Int functions the signals module declares. Renaming keeps the gate
    # working; dropping a feed, or feeding a non-tool block header, does not.
    # ------------------------------------------------------------------
    anth_code = strip_kotlin_comments(anthropic)
    anth_sig_code = strip_kotlin_comments(anthropic_signals)
    anth_escapable = ESCAPE in anth_sig_code
    if (not anth_escapable) and anth_code.count(C3_ANTHROPIC_GUARD) == 1:
        tool_counter = anthropic_tool_counter(anth_code)
        branches = anthropic_lifecycle_branches(anth_code)
        if branches is None:
            problems.append((
                "anthropic-tool-lifecycle-branches", ANTHROPIC,
                line_of(anth_code, '"content_block_start" ->'),
                "the SSE lifecycle arms (%s) cannot be located — a reordered or "
                "renamed `when (eventType)` would blind the feed criteria below"
                % ", ".join(ANTHROPIC_LIFECYCLE_LABELS)))
        elif tool_counter is not None:
            start = branches['"content_block_start" ->']
            stop = branches['"content_block_stop" ->']
            delta = branches['"content_block_delta" ->']

            # (a) the block announcement is already progress, but ONLY inside
            #     the tool_use guard: counting a text/thinking block header
            #     would steal the fully-empty case from
            #     failOnSilentEmptyCompletion.
            token_at = start.find(ANTHROPIC_TOOL_USE_TOKEN)
            if token_at < 0:
                problems.append((
                    "anthropic-tool-start-feeds-counter", ANTHROPIC,
                    line_of(anth_code, '"content_block_start" ->'),
                    "the content_block_start arm no longer tests %s — the tool block "
                    "announcement cannot feed '%s' any more"
                    % (ANTHROPIC_TOOL_USE_TOKEN, tool_counter)))
            else:
                close = start.find(")", token_at)
                body_open = start.find("{", close) if close >= 0 else -1
                writes = counter_writes(start, tool_counter)
                guarded = [w for w in writes
                           if body_open >= 0 and w > body_open
                           and "{" not in start[body_open + 1:w]
                           and "}" not in start[body_open + 1:w]]
                if not guarded:
                    problems.append((
                        "anthropic-tool-start-feeds-counter", ANTHROPIC,
                        line_of(anth_code, '"content_block_start" ->'),
                        "the tool_use block announcement must feed '%s' (%s) — a "
                        "pure-tool turn cut before its first argument delta would "
                        "otherwise read as an empty stream, and the orphan flush "
                        "would hand an argument-less call to the executor%s"
                        % (tool_counter, ", ".join(
                            ["%s += <weight>()" % tool_counter]),
                           "" if not writes else "; it is written OUTSIDE the "
                           "tool_use guard, so a text/thinking block header would "
                           "be counted as tool activity")))
                else:
                    # [wide3-04] …and the announced weight must carry the block's
                    # identity. `toolUseStartActivityChars("", "")` keeps the
                    # guarded write, the declared callee and the counter, and
                    # always contributes 0 — the tool_use signal is gone while
                    # every substring criterion stays green.
                    bad = [(fn, a) for fn, a, p in feed_calls(start, tool_counter)
                           if p in guarded and dead_weight_arg(a)]
                    if bad:
                        problems.append((
                            "anthropic-tool-start-feeds-counter", ANTHROPIC,
                            line_of(anth_code, '"content_block_start" ->'),
                            "the tool_use announcement feeds '%s' with a constant "
                            "weight (%s) — the announcement must carry the block's own "
                            "identity, otherwise a pure-tool turn cut before its first "
                            "argument delta still reads as an empty stream"
                            % (tool_counter, "; ".join(
                                "%s(%s)" % (f, (a or "").strip()) for f, a in bad))))

            # (b) the block completion is progress too: a zero-argument call
            #     that finished streaming still must be flagged when the stream
            #     dies before message_delta.
            if not counter_writes(stop, tool_counter):
                problems.append((
                    "anthropic-tool-stop-feeds-counter", ANTHROPIC,
                    line_of(anth_code, '"content_block_stop" ->'),
                    "the content_block_stop arm completes a tool call but never "
                    "feeds '%s' — a zero-argument call cut before message_delta "
                    "survives as a clean no-finish EOF" % tool_counter))
            elif (sfeeds := feed_calls(stop, tool_counter)) and all(
                    dead_weight_arg(a) for _f, a, _p in sfeeds):
                problems.append((
                    "anthropic-tool-stop-feeds-counter", ANTHROPIC,
                    line_of(anth_code, '"content_block_stop" ->'),
                    "the content_block_stop arm feeds '%s' with a constant weight — the "
                    "completed call contributes nothing, so a zero-argument call cut "
                    "before message_delta still survives as a clean no-finish EOF"
                    % tool_counter))

            # (c) regression guard on the one feed that always existed.
            if not counter_writes(delta, tool_counter):
                problems.append((
                    "anthropic-tool-delta-feeds-counter", ANTHROPIC,
                    line_of(anth_code, '"content_block_delta" ->'),
                    "the content_block_delta arm no longer feeds '%s' — the "
                    "pre-existing mid-argument truncation signal is gone"
                    % tool_counter))
            elif (dfeeds := feed_calls(delta, tool_counter)) and all(
                    dead_weight_arg(a) for _f, a, _p in dfeeds):
                problems.append((
                    "anthropic-tool-delta-feeds-counter", ANTHROPIC,
                    line_of(anth_code, '"content_block_delta" ->'),
                    "the content_block_delta arm feeds '%s' with a constant weight — the "
                    "mid-argument truncation signal no longer depends on the delta "
                    "length" % tool_counter))

            # (d) single source: every feed goes through a pure weight declared
            #     in the signals module, and every declared weight is used.
            declared = top_level_int_funs(anth_sig_code)
            callees = set(re.findall(
                r"\b%s\b\s*\+=\s*([A-Za-z_]\w*)\s*\(" % re.escape(tool_counter),
                anth_code))
            if not callees:
                problems.append((
                    "anthropic-tool-weights-single-source", ANTHROPIC,
                    line_of(anth_code, C3_ANTHROPIC_GUARD),
                    "'%s' is fed by raw arithmetic instead of the pure weights in "
                    "AnthropicStreamSignals — the decision table is then untestable "
                    "and each arm can drift" % tool_counter))
            else:
                missing = sorted(set(declared) - callees)
                extra = sorted(callees - set(declared))
                if missing:
                    problems.append((
                        "anthropic-tool-weights-single-source", ANTHROPIC,
                        line_of(anth_code, C3_ANTHROPIC_GUARD),
                        "these weights are declared in AnthropicStreamSignals but never "
                        "fed into '%s': %s — a lifecycle arm lost its wire-up"
                        % (tool_counter, ", ".join(missing))))
                if extra:
                    problems.append((
                        "anthropic-tool-weights-single-source", ANTHROPIC,
                        line_of(anth_code, C3_ANTHROPIC_GUARD),
                        "'%s' is fed through functions that AnthropicStreamSignals does "
                        "not declare (%s) — the derivation is no longer single-source"
                        % (tool_counter, ", ".join(extra))))

    return problems


def selftest(root):
    files = load(root)
    if files is None:
        return 1
    policy, engine, openai, anthropic, tool_executors, gemini, signals, \
        anthropic_signals = files

    def on(i, fn):
        # Positional mutator: load() returns 7 texts, and the arms must keep
        # addressing them by position so adding a file cannot silently shift
        # which mutation targets which file.
        def wrapper(*args):
            out = list(args)
            out[i] = fn(out[i])
            return tuple(out)
        return wrapper

    def strip_guard_from_error_branch(s):
        # delete the FIRST guard call (the error-shaped site) → count drops to 1
        i = s.find(C5_SHARED_GUARD)
        if i < 0:
            return s + "\n// sentinel-missing\n"
        return s[:i] + s[i + len(C5_SHARED_GUARD):]

    def drop_subagent_refusal(s):
        i = s.find(C4_REFUSAL_GATE)
        if i < 0:
            return s + "\n// sentinel-missing\n"
        return s.replace(C4_REFUSAL_GATE, "if (false) {", 1)

    arms = [
        ("policy loses the flag parameter", "policy-flag-signature",
         on(0, lambda s: s.replace(C1_SIGNATURE,
                                       "fun rejectionReason(toolName: String, finishReason: String?): String?", 1))),
        ("policy ignores the flag", "policy-flag-condition",
         on(0, lambda s: s.replace(C1_CONDITION, "if (!isTruncatedFinish(finishReason)) return null", 1))),
        ("engine passes a false constant", "engine-feeds-flag",
         on(1, lambda s: s.replace(C2_ENGINE_CALL,
                                       "TruncatedToolCallPolicy.rejectionReason(name, turnFinishReason, false)", 1))),
        ("openai eof guard forgets tool calls", "openai-eof-counts-toolcalls",
         on(2, lambda s: s.replace(C3_OPENAI_GUARD,
                                       "if (!finishedSent && (contentLen > 0 || reasoningLen > 0)) {", 1))),
        ("anthropic sibling guard regresses", "anthropic-sibling-pin",
         on(3, lambda s: s.replace(C3_ANTHROPIC_GUARD,
                                          "if (!sawClearFinish && (contentChars > 0)) {", 1))),
        ("subagent loop drops the refusal gate", "subagent-refusal-gate",
         on(4, drop_subagent_refusal)),
        ("subagent loop skips the policy", "subagent-refusal-wired",
         on(4, lambda s: s.replace(C4_POLICY_CALL, "TruncatedToolCallPolicy.isTruncatedFinish(", 1))),
        # [wide4] The arm must stay VALID Kotlin: the shared lexer now refuses to
        # judge a file it cannot tokenise, so an arm that splices an unterminated
        # string would exercise the fail-closed path instead of this criterion.
        ("subagent loop hand-writes the wording", "subagent-single-source",
         on(4, lambda s: s.replace(
             "log(\"[Subagent] turn=$turns truncated tool call REFUSED",
             "log(\"[TRUNCATED] \" + \"[Subagent] turn=$turns truncated tool call REFUSED",
             1))),
        ("subagent refusal breaks the pairing", "subagent-refusal-pairing",
         on(4, lambda s: s.replace(C4_PAIRING,
                                               "content = truncatedRefusal, isError = false", 1))),
        ("error-shaped branch loses the stacking guard", "stack-guard-shared",
         on(1, strip_guard_from_error_branch)),
        ("gemini EOF reverts to the fabricated end_turn",
         "gemini-eof-terminal-pure+gemini-eof-passthrough",
         on(5, lambda s: s.replace(C6_GEMINI_SEND,
                                       'send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))', 1))),
        ("gemini pure verdict invents a stop reason", "gemini-eof-passthrough",
         on(5, lambda s: s.replace(
             C6_GEMINI_PURE_ELSE,
             'GeminiEofTerminal(stopReason = lastFinishReason ?: "end_turn", truncated = false)', 1))),
        ("eof-stub branch back to an inline predicate", "stack-guard-no-inline-twin",
         on(1, lambda s: s.replace(
             "                        if (EofStubReminderStackGuard.isStaleEofStubReminder(prevTail)) {",
             "                        val tailIsEofStubReminder = prevTail != null &&\n"
             "                        if (EofStubReminderStackGuard.isStaleEofStubReminder(prevTail)) {", 1))),
        # ---- [dv28 A-01 / A-08] derived criteria arms -------------------
        ("responses arm stops feeding the EOF counter",
         "openai-count-covers-both-flavours",
         on(2, lambda s: s.replace(RESPONSES_FEED, "", 1))),
        ("chat arm stops feeding the EOF counter",
         "openai-count-covers-both-flavours",
         on(2, lambda s: s.replace(CHAT_FEED, "", 1))),
        ("chat inline error coerces the symbolic code to 0",
         "openai-stream-error-single-source",
         on(2, lambda s: s.replace(CHAT_INLINE_STATUS, "inlineError.optInt(\"code\", 0)", 1))),
        ("responses rate limit hand-classified as transient",
         "openai-stream-error-single-source",
         on(2, lambda s: s.replace(
             RESPONSES_STATUS_CALL,
             'if (code == "server_error" || code == "rate_limit_exceeded") {\n'
             '                                throw LLMError.TransientError("[$code] $message")\n'
             '                            }\n'
             '                            throw LLMError.ProviderError("[$code] $message")', 1))),
        # ---- [dv29 A-01] Anthropic tool-lifecycle feed arms --------------
        # Expectations are hand-written from the criterion table above: an arm
        # that silently stops mutating anything must show up as "expected X got
        # ()" instead of a false green.
        ("anthropic block announcement stops feeding the tool counter",
         "anthropic-tool-start-feeds-counter+anthropic-tool-weights-single-source",
         on(3, lambda s: s.replace(ANTHROPIC_START_FEED, "", 1))),
        ("anthropic block completion stops feeding the tool counter",
         "anthropic-tool-stop-feeds-counter+anthropic-tool-weights-single-source",
         on(3, lambda s: s.replace(ANTHROPIC_STOP_FEED, "", 1))),
        ("anthropic announcement feed moves outside the tool_use guard",
         "anthropic-tool-start-feeds-counter",
         on(3, lambda s: s.replace(
             ANTHROPIC_START_ANCHOR,
             'val contentBlock = event.optJSONObject("content_block")\n'
             '                        toolChars += toolUseStartActivityChars("", "")\n'
             '                        if (contentBlock?.safeOptString("type", "") == "tool_use") {',
             1).replace(ANTHROPIC_START_FEED + "\n", "", 1))),
        ("anthropic argument feed reverts to raw arithmetic",
         "anthropic-tool-weights-single-source",
         on(3, lambda s: s.replace(ANTHROPIC_DELTA_FEED,
                                        "toolChars += partial.length", 1))),
        # ---- [wide3-04] the four externally falsified shapes ----------------
        # Each of these keeps every character the old criteria looked for and
        # drops only the decision — so each expectation names exactly one arm.
        ("policy adds an early return keyed on the ceiling source alone",
         "policy-single-source-early-return",
         on(0, lambda s: s.replace(
             C1_CONDITION,
             C1_CONDITION + "\n        if (!ceilingDeclared) return null", 1))),
        ("sub-agent refusal variable is wrapped in if (false) … else null",
         "subagent-refusal-wired",
         on(4, lambda s: s.replace(
             "val truncatedRefusal = TruncatedToolCallPolicy.rejectionReason(\n"
             "                    call.name,\n"
             "                    turnFinishReason,\n"
             "                    turnTruncated,\n"
             "                )",
             "val truncatedRefusal = if (false) TruncatedToolCallPolicy.rejectionReason(\n"
             "                    call.name,\n"
             "                    turnFinishReason,\n"
             "                    turnTruncated,\n"
             "                ) else null", 1))),
        ("engine refusal variable is wrapped in if (false) … else null",
         "engine-feeds-flag",
         on(1, lambda s: s.replace(
             "val truncatedRefusal = " + C2_ENGINE_CALL,
             "val truncatedRefusal = if (false) " + C2_ENGINE_CALL + " else null", 1))),
        ("chat flavour feeds the EOF counter with a literal weight",
         "openai-count-derived",
         on(2, lambda s: s.replace(CHAT_FEED,
                                   "toolCallEventCount += "
                                   "chatCompletionsToolCallEventWeight(0)", 1))),
        ("anthropic announcement feeds a constant (empty) tool identity",
         "anthropic-tool-start-feeds-counter",
         on(3, lambda s: s.replace(ANTHROPIC_START_FEED,
                                   'toolChars += toolUseStartActivityChars("", "")', 1))),
        # ---- [wide4] the five externally falsified shapes --------------------
        # Each keeps the spelling, the wording and the pairing; each drops only
        # the decision, so each expectation names exactly one criterion.
        ("w4-policy adds a `when`-keyed single-source return",
         "policy-single-source-early-return",
         on(0, lambda s: s.replace(
             C1_CONDITION,
             C1_CONDITION + "\n        when { !ceilingDeclared -> return null }", 1))),
        ("w4-policy hides a second return null inside raw strings",
         "policy-single-source-early-return",
         on(0, lambda s: s.replace(
             C1_CONDITION,
             C1_CONDITION + "\n        val _open = " + '"' * 4 + "/*" + '"' * 3
             + "\n        if (!ceilingDeclared) return null"
             + "\n        val _close = " + '"' * 3 + "*/" + '"' * 4, 1))),
        ("w4-engine refusal branch short-circuited by a constant",
         "engine-feeds-flag",
         on(1, lambda s: s.replace(
             ENGINE_CONSUMER,
             "                if (false && truncatedRefusal != null) {", 1))),
        ("w4-subagent shadows the stream flag before the refusal call",
         "subagent-refusal-wired",
         on(4, lambda s: s.replace(
             SUBAGENT_CALL,
             "                val turnTruncated = false\n" + SUBAGENT_CALL, 1))),
        ("w4-openai feeds the EOF counter through a dead branch",
         "openai-count-derived",
         on(2, lambda s: s.replace(
             CHAT_FEED,
             "toolCallEventCount += chatCompletionsToolCallEventWeight("
             "if (false) toolCalls.length() else 0)", 1))),
        ("w4-openai feeds the EOF counter a zero-scaled weight",
         "openai-count-derived",
         on(2, lambda s: s.replace(
             CHAT_FEED,
             "toolCallEventCount += chatCompletionsToolCallEventWeight("
             "toolCalls.length() * 0)", 1))),
    ]

    failed = []
    for name, criterion, mutate in arms:
        mutated = mutate(*files)
        found = check(*mutated)
        fired = sorted(set(c for c, _f, _l, _m in found))
        expected = sorted(criterion.split("+"))
        if fired != expected:
            failed.append("%s: expected %s got %s" % (name, expected, fired))
    # clean tree must be fully green
    if check(*files):
        failed.append("clean tree unexpectedly flagged: %s" % (check(*files)[:2],))
    if failed:
        print("❌ truncated-call gate --selftest failures:")
        for line in failed:
            print("   - %s" % line)
        return 1
    print("✅ truncated-call gate --selftest: %d arms flip exactly their criterion" % len(arms))
    print(SELFTEST_MARK)
    return 0


def main():
    if "--selftest" in sys.argv:
        root = next((a for a in sys.argv[1:] if not a.startswith("-")), ".")
        return selftest(root)
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    files = load(root)
    if files is None:
        return 1
    problems = check(*files)
    if problems:
        print("❌ truncated-call gate findings:")
        for criterion, rel, line, msg in problems:
            print("   - [%s] %s:%d — %s" % (criterion, rel, line, msg))
        return 1
    print("✅ truncated-call gate: policy reads both truncation sources, the engine and the "
          "sub-agent loop feed it, OpenAI's EOF guard counts tool-call events, both "
          "stub-reminder injection sites share one stacking guard, and Gemini's EOF "
          "terminal decision goes through the pure verdict without inventing a stop reason")
    return 0


if __name__ == "__main__":
    sys.exit(main())
