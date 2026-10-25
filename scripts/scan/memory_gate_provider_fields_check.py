#!/usr/bin/env python3
"""
Gate: FIX-G (deep-audit-1020 ISSUE-07) — the memory_rollup tool must carry
the same memoryEnabled gate as memory_write, and the provider `add` /
`minis-config get providers` paths must carry `customUserAgent`.

Two halves (TRIAGE ISSUE-07):

  A. `memory_rollup` executor gate. AgentTools.makeAgentTools drops the
     tool from the schema when memory is off, but the executor path had no
     gate — a tool_use block that still arrives (mid-turn /memory toggle,
     replayed block) reached MemoryRollupTool.execute and WROTE
     MEMORY-ROLLUP.md. memory_write had the gate; rollup didn't (asymmetric).
     The fix funnels both executors through the pure decision
     `memoryToolDisabledMessage(memoryEnabled)` with the shared
     `MEMORY_DISABLED_TOOL_TITLE`, and the ChatViewModel wrapper passes
     `_memoryEnabled.value` (covers both dispatch sites: main loop +
     sub-agent loop).

     isWrite NOTE (triage outcome, not a defect): the rollup record keeps
     `isWrite = false`. `MemoryToolRecord.isWrite` is a TOOL-KIND
     discriminator, not a "did this change the disk" flag — it routes
     SessionMemorySheet clicks (Write detail needs `writtenContent`, which
     rollup never sets → an empty page) and labels the row
     "memory_write"/"memory_get", and keys ChatViewModel revoke matching.
     Flipping rollup to `isWrite = true` would regress the UI; a proper fix
     is a third op kind (SessionMemorySheet/MemoryDetailScreens — outside
     this fix's file set).

  B. provider fields. `ProvidersCollection.add` dropped `customUserAgent`
     from the ProviderInstance it built (write field `customUserAgent(id)`
     and the export envelope both carry it), and the
     `minis-config get providers` summary (ConfigBuiltins) omitted it. The
     fix reads it via the pure `resolveAddCustomUserAgent` (same credential
     rule as the write field, shared via `customUserWritable` /
     `customUserAgentWritable`), passes it into the instance, and adds the
     field to the summary (empty override → Null = branded default).

This gate pins PRESENCE of the wiring (a legit refactor is a deliberate
edit to this gate rather than a silent pass):

  1. ChatToolExecutors: both memory executors route through
     `memoryToolDisabledMessage(memoryEnabled)`, the rollup signature takes
     `memoryEnabled: Boolean`, and the rollup record keeps `isWrite = false`
     (write executor keeps `isWrite = true`).
  2. ChatPromptAndTools: the rollup wrapper passes `_memoryEnabled.value`.
  3. ProvidersCollection: `add` resolves the payload UA
     (`resolveAddCustomUserAgent(`) and constructs the instance with
     `customUserAgent = customUserAgent,`; the write field consults the
     shared rule (`customUserAgentWritable(inst.credentialType)`).
  4. ConfigBuiltins: the providers summary linkedMapOf carries
     `"customUserAgent" to`.

[batch5-t07] PRESENCE was not enough — 6 external witnesses walked through
this gate (report-A §05, report-B). Each criterion now judges the EFFECT,
not the spelling, on the shared lexer/ast/valueflow primitives:

  * W1 `return@let` — "the rejection is returned" now requires a NON-LOCAL
    return (`return@let` leaves the lambda and the executor falls through to
    the write).
  * W2 helper scope — the shared decision `memoryToolDisabledMessage` is now
    scanned in its OWN body: with `memoryEnabled = false` the folded decision
    must not yield null (kills `if (true || memoryEnabled) null`).
  * W3 parser-death — all structure work runs on `mask_code` (string/char
    interiors blanked), so a string literal can no longer feed an anchor
    needle; the wrapper's second argument is resolved through
    `value_sources` and must be the live switch.
  * W4 argument dataflow — the resolver's first argument is judged by
    `value_sources` (discard scopes like `null.also { … }` peel back to
    `null`) plus a payload-read/cast-shape test.
  * W5 guard liveness — the OAuth guard's BODY must terminate (`throw` /
    non-local `return`); `Unit` no longer passes for a rejection.
  * W6 projection value — the summary must project the VALUE
    (`ConfigValue.Str(it)`), not a constant (`ConfigValue.Str("")`).

Usage: python3 scripts/scan/memory_gate_provider_fields_check.py [repo_root]
       python3 scripts/scan/memory_gate_provider_fields_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import (const_bool, function_body, if_blocks, is_pure_literal,
                         match_bracket, strip_parens, tail_expr)
from _kotlin_valueflow import (call_args, contains_ident, value_sources)

APP = "src/android/app/src/main/java/com/rikkaminis/app/"

EXECUTORS_REL = APP + "ui/chat/ChatToolExecutors.kt"
PROMPT_REL = APP + "ui/chat/ChatPromptAndTools.kt"
COLLECTION_REL = APP + "config/collections/ProvidersCollection.kt"
BUILTINS_REL = APP + "config/ConfigBuiltins.kt"

GATE_CALL = "memoryToolDisabledMessage(memoryEnabled)"
UA_CTOR = "customUserAgent = customUserAgent,"
UA_WRITER_RULE = "customUserAgentWritable(inst.credentialType)"
UA_SUMMARY = '"customUserAgent" to (inst.customUserAgent'
PAYLOAD_UA = 'obj["customUserAgent"]'

GATE_CALL_RE = re.compile(r"\bmemoryToolDisabledMessage\s*\(\s*memoryEnabled\s*\)")
LET_AFTER_RE = re.compile(r"\?\.\s*let\s*\{")
RET_RE = re.compile(r"\breturn\b(\s*@\s*[A-Za-z_]\w*)?")
GUARD_COND_RE = re.compile(
    r"!\s*customUserAgentWritable\s*\(\s*inst\.credentialType\s*\)")
PROJ_RE = re.compile(
    r'"customUserAgent"\s+to\s*\(\s*inst\.customUserAgent\s*\?\.\s*let\s*\{\s*'
    r'ConfigValue\.Str\s*\(')


# ── shared-primitive wrappers ───────────────────────────────────────────────

def _code_view(src):
    """Comment- AND literal-masked view (length-preserving)."""
    return mask_code(src)


def _literal_view(src):
    """Comment-masked view, string contents kept (length-preserving)."""
    return mask_comments(src)


def _fn(code, name):
    """(body_start, body_end, kind) of the first `fun name(`, or None."""
    return function_body(code, name)


def _fn_sig(code, name, bstart):
    """The declaration text (name … closing paren) that precedes [bstart]."""
    i = code.rfind(name + "(", max(0, bstart - 900), bstart)
    return code[i:bstart] if i >= 0 else ""


def _nonlocal_return(code, start, end):
    """A bare `return` inside the span (Kotlin non-local return semantics).

    `return@let` / `return@run` leave the lambda only and are NOT exits of the
    enclosing function — the W1 witness shape.
    """
    return any(not m.group(1) for m in RET_RE.finditer(code, start, end))


def _let_span(code, after):
    """Span (body_start, body_end) of the `?.let { … }` at/after [after]."""
    m = LET_AFTER_RE.search(code, after, min(len(code), after + 240))
    if m is None:
        return None
    brace = m.end() - 1
    close = match_bracket(code, brace)
    if close < 0:
        return None
    return (brace + 1, close)


def _branch_value(text):
    """The value a braceless/braceless-braced branch yields, as written."""
    t = text.strip()
    if not t:
        return ""
    if t.startswith("{"):
        close = match_bracket(t, 0)
        inner = t[1:close] if close > 0 else t[1:]
        return tail_expr(inner).strip()
    return t


def _nullish(value):
    v = strip_parens(value.strip())
    return v == "" or v == "null"


# ── 1. the executors ────────────────────────────────────────────────────────

def _executor_gate(code, rel, fname, label, out):
    """Gate call present, consumed by `?.let { … }`, and the block RETURNS."""
    span = _fn(code, fname)
    if span is None:
        out.append("%s: `%s` not found — gate ROT" % (rel, fname))
        return None, None
    bstart, bend, _kind = span
    body = code[bstart:bend]
    sig = _fn_sig(code, fname, bstart)
    m = GATE_CALL_RE.search(body)
    if m is None:
        out.append("%s: %s does not consult `%s` — a replayed / queued tool_use "
                   "reaches the write with memory off (ISSUE-07)"
                   % (rel, label, GATE_CALL))
        return body, sig
    let_span = _let_span(code, bstart + m.end())
    if let_span is None:
        out.append("%s: %s gate is not consumed by `?.let { … }` (missing or "
                   "unparseable) — the rejection cannot be judged (fail-closed)"
                   % (rel, label))
    elif not _nonlocal_return(code, let_span[0], let_span[1]):
        out.append("%s: %s gate BUILDS the rejection but only leaves the lambda "
                   "(`return@let` / a discarded expression) — the value is thrown "
                   "away and the tool runs anyway (ISSUE-07)" % (rel, label))
    return body, sig


def _helper_violations(code, lit, rel):
    """W2 — the shared decision must actually depend on `memoryEnabled`."""
    out = []
    span = _fn(code, "memoryToolDisabledMessage")
    if span is None:
        out.append("%s: the shared gate decision `memoryToolDisabledMessage(` is "
                   "gone — the two executors' rejection source cannot be judged "
                   "(gate ROT)" % rel)
        return out
    bstart, bend, _kind = span
    sig = _fn_sig(code, "memoryToolDisabledMessage", bstart)
    if not re.search(r"\bmemoryEnabled\s*:\s*Boolean\b", sig):
        out.append("%s: `memoryToolDisabledMessage` no longer takes "
                   "`memoryEnabled: Boolean` — the gate decision lost its input "
                   "(fail-closed)" % rel)
    body_c = code[bstart:bend]
    body_l = lit[bstart:bend]
    blocks = if_blocks(body_c)
    if not blocks:
        out.append("%s: `memoryToolDisabledMessage` has no conditional — the "
                   "decision cannot depend on `memoryEnabled` (fail-closed)" % rel)
        return out
    checked = False
    for cond, t_start, t_end, e_start, e_end in blocks:
        if not contains_ident(cond, "memoryEnabled"):
            continue
        checked = True
        folded = const_bool(strip_parens(re.sub(r"\bmemoryEnabled\b", "false", cond)))
        if folded is True:
            taken = _branch_value(body_l[t_start:t_end])
            if _nullish(taken):
                out.append("%s: with `memoryEnabled = false` the gate decision "
                           "`%s` is ALWAYS taken and yields %s — both memory tools "
                           "are admitted while the feature is switched off"
                           % (rel, " ".join(cond.split()), taken or "null"))
        elif folded is False:
            taken = _branch_value(body_l[e_start:e_end]) if e_start is not None else ""
            if _nullish(taken):
                out.append("%s: with `memoryEnabled = false` the gate decision "
                           "`%s` yields %s — the false path returns no rejection, so "
                           "every memory write is admitted"
                           % (rel, " ".join(cond.split()),
                              taken or "<nothing/null>"))
    if not checked:
        out.append("%s: `memoryToolDisabledMessage` never branches on "
                   "`memoryEnabled` — the decision is not a function of the switch "
                   "(fail-closed)" % rel)
    return out


# ── 2. the wrapper ─────────────────────────────────────────────────────────

def _wrapper_violations(code, rel):
    """W3 — the executor must receive the LIVE switch, not a constant."""
    out = []
    span = _fn(code, "executeMemoryRollupTool")
    if span is None:
        out.append("%s: the ChatViewModel memory_rollup wrapper is gone (gate ROT)"
                   % rel)
        return out
    bstart, bend, _kind = span
    body = code[bstart:bend]
    m = re.search(r"\bexecuteMemoryRollupTool\s*\(", body)
    if m is None:
        out.append("%s: the wrapper does not call `executeMemoryRollupTool(` "
                   "(gate ROT)" % rel)
        return out
    open_pos = bstart + m.end() - 1
    args = call_args(code, open_pos)
    if len(args) < 2:
        out.append("%s: the wrapper calls the executor with %d argument(s) — the "
                   "memory switch cannot reach the gate (fail-closed)"
                   % (rel, len(args)))
        return out
    a, b = args[1]
    given = code[a:b].strip()
    sources = value_sources(code[a:b])
    if not any(contains_ident(s, "_memoryEnabled") for s in sources):
        out.append("%s: the wrapper forwards `%s` as memoryEnabled — the live "
                   "switch `_memoryEnabled.value` is not what the executor receives, "
                   "so a session with memory off still writes MEMORY-ROLLUP.md"
                   % (rel, given))
    return out


# ── 3. ProvidersCollection ─────────────────────────────────────────────────

def _payload_expr(body_lit, first):
    """Resolve the UA argument to the expression that actually reads the payload."""
    expr = first.strip()
    if re.fullmatch(r"[A-Za-z_]\w*", expr):
        decl = re.search(
            r"\b(?:val|var)\s+" + re.escape(expr) + r"\b[^\n=]*=(.*)", body_lit)
        if decl is not None:
            return decl.group(1).strip()
    return expr


def _add_violations(code, lit, rel):
    """W4 — the resolver must be fed the PAYLOAD, through real dataflow."""
    out = []
    span = _fn(code, "add")
    if span is None:
        out.append("%s: `add(` not found — gate ROT" % rel)
        return out
    bstart, bend, _kind = span
    body_c = code[bstart:bend]
    body_l = lit[bstart:bend]
    if UA_CTOR not in body_c:
        out.append("%s: add ProviderInstance does not carry `%s` — `minis-config "
                   "add providers` loses the field the caller passed"
                   % (rel, UA_CTOR.strip()))
    m = re.search(r"\bresolveAddCustomUserAgent\s*\(", body_c)
    if m is None:
        out.append("%s: add does not resolve `resolveAddCustomUserAgent(` — the UA "
                   "write path has no payload reader (ISSUE-07)" % rel)
        return out
    args = call_args(code, bstart + m.end() - 1)
    if not args:
        out.append("%s: the `resolveAddCustomUserAgent(` call is unparseable — the "
                   "UA source cannot be judged (fail-closed)" % rel)
        return out
    a, b = args[0]
    first_l = lit[a:b]
    expr = _payload_expr(body_l, first_l)
    sources = value_sources(expr)
    alive = [s for s in sources if not is_pure_literal(s)]
    if not alive or not any(PAYLOAD_UA in s for s in alive):
        out.append("%s: add resolves the UA from `%s` — that expression does not "
                   "(really) read the payload `%s` (discard scopes such as "
                   "`null.also { … }` peel back to the constant), so every "
                   "`minis-config add providers` silently drops the field"
                   % (rel, " ".join(first_l.split()) or "nothing", PAYLOAD_UA))
        return out
    cast = re.search(r"\bas\??\s*([A-Za-z_][\w.]*)", expr)
    if cast is not None and cast.group(1) != "ConfigValue.Str":
        out.append("%s: the payload UA is read as `as? %s` — the field is a "
                   "`ConfigValue.Str`, so the mismatch resolves to null and the "
                   "value evaporates" % (rel, cast.group(1)))
    return out


def _writer_violations(code, rel):
    """W5 — the OAuth guard must TERMINATE, not just be spelled."""
    out = []
    span = _fn(code, "customUserAgent")
    if span is None:
        out.append("%s: the `customUserAgent(` write field is gone (gate ROT)" % rel)
        return out
    bstart, bend, _kind = span
    body = code[bstart:bend]
    found = False
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(body):
        if GUARD_COND_RE.fullmatch(" ".join(cond.split())) is None:
            continue
        found = True
        branch = body[t_start:t_end]
        if re.search(r"\bthrow\b", branch) is None and not _nonlocal_return(
                branch, 0, len(branch)):
            out.append("%s: the OAuth guard `if (!customUserAgentWritable(inst."
                       "credentialType))` keeps its condition but its body does not "
                       "terminate (`Unit` / a discarded expression) — execution falls "
                       "through to `mutate(...)` and overwrites the fixed OAuth client "
                       "UA" % rel)
    if not found:
        out.append("%s: the customUserAgent writer must guard with `if (!%s)` — "
                   "without the `!` the polarity is inverted (API-key instances "
                   "rejected, OAuth instances writable)" % (rel, UA_WRITER_RULE))
    return out


def _rule_violations(code, rel):
    out = []
    if "internal fun customUserAgentWritable(credentialType: ProviderCredential): Boolean" not in code:
        out.append("%s: the shared UA predicate `internal fun "
                   "customUserAgentWritable(credentialType: ProviderCredential): "
                   "Boolean` is gone — `add` and the write field no longer share one "
                   "credential rule (gate ROT)" % rel)
    return out


# ── 4. ConfigBuiltins summary ──────────────────────────────────────────────

def _summary_violations(code, lit, rel):
    """W6 — the projection must carry the VALUE, not a constant."""
    out = []
    i = lit.find('path = "providers",')
    if i < 0:
        out.append("%s: the `providers` summary field is gone (gate ROT)" % rel)
        return out
    j = lit.find('path = "models",', i + 1)
    stop = len(lit) if j < 0 else j
    if UA_SUMMARY not in lit[i:stop]:
        out.append("%s: the providers summary no longer projects "
                   "`%s` — `minis-config get providers` drops the field"
                   % (rel, UA_SUMMARY))
        return out
    m = PROJ_RE.search(lit, i, stop)
    if m is None:
        out.append("%s: the providers summary customUserAgent projection is not "
                   "`inst.customUserAgent?.let {{ ConfigValue.Str(<value>) }}` — the "
                   "value cannot be judged (fail-closed)" % rel)
        return out
    args = call_args(code, m.end() - 1)
    if not args:
        out.append("%s: the projected `ConfigValue.Str(` call is unparseable — the "
                   "projected value cannot be judged (fail-closed)" % rel)
        return out
    a, b = args[0]
    value = lit[a:b].strip()
    if is_pure_literal(value) or not (contains_ident(value, "it")
                                     or "inst.customUserAgent" in value):
        out.append("%s: the providers summary projects `ConfigValue.Str(%s)` — not "
                   "the instance's value, so `minis-config get providers` reports a "
                   "constant / empty customUserAgent for every instance that has one"
                   % (rel, value))
    return out


# ── driver ─────────────────────────────────────────────────────────────────

def _read(root, rel):
    with open(os.path.join(root, rel), "r", encoding="utf-8") as fh:
        return fh.read()


def _views(rel, root, read, violations):
    try:
        src = read(root, rel)
    except OSError as exc:
        violations.append("%s: unreadable (%s) — gate ROT" % (rel, exc))
        return None, None
    try:
        return _code_view(src), _literal_view(src)
    except KotlinLexError as exc:
        violations.append("%s: unparsable Kotlin (%s) — fail-closed "
                          "(no fallback to raw scanning)" % (rel, exc))
        return None, None


def check(root, _read=None):
    if _read is None:
        _read = globals()["_read"]
    violations = []

    code, lit = _views(EXECUTORS_REL, root, _read, violations)
    if code is not None:
        write_body, _ = _executor_gate(code, EXECUTORS_REL, "executeMemoryWriteTool",
                                      "memory_write executor", violations)
        roll_body, roll_sig = _executor_gate(code, EXECUTORS_REL,
                                             "executeMemoryRollupTool",
                                             "memory_rollup executor", violations)
        if write_body is not None and "isWrite = true" not in write_body:
            violations.append("%s: the memory_write record lost `isWrite = true` — "
                              "the write row is mislabelled" % EXECUTORS_REL)
        if roll_body is not None:
            if "isWrite = false" not in roll_body:
                violations.append("%s: the memory_rollup record must keep "
                                  "`isWrite = false` (tool-kind discriminator, see "
                                  "gate doc)" % EXECUTORS_REL)
            if not re.search(r"\bmemoryEnabled\s*:\s*Boolean\b", roll_sig or ""):
                violations.append("%s: the memory_rollup executor signature lost "
                                  "`memoryEnabled: Boolean` — the gate has no input"
                                  % EXECUTORS_REL)
        violations += _helper_violations(code, lit, EXECUTORS_REL)

    code, _lit = _views(PROMPT_REL, root, _read, violations)
    if code is not None:
        violations += _wrapper_violations(code, PROMPT_REL)

    code, lit = _views(COLLECTION_REL, root, _read, violations)
    if code is not None:
        violations += _add_violations(code, lit, COLLECTION_REL)
        violations += _writer_violations(code, COLLECTION_REL)
        violations += _rule_violations(code, COLLECTION_REL)

    code, lit = _views(BUILTINS_REL, root, _read, violations)
    if code is not None:
        violations += _summary_violations(code, lit, BUILTINS_REL)

    return violations


# ── selftest fixtures ────────────────────────────────────────────────────────

GOOD_HELPER = """internal fun memoryToolDisabledMessage(memoryEnabled: Boolean): String? =
    if (memoryEnabled) null
    else "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."

/** Shared toolTitle for a memory tool rejected by [memoryToolDisabledMessage]. */
internal const val MEMORY_DISABLED_TOOL_TITLE = "Memory (disabled)"
"""

GOOD_GATE = GOOD_HELPER + """
internal fun executeMemoryWriteTool(
    argsJson: String,
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = true))
    return ToolExecutionResult(result.output, result.success)
}

internal fun executeMemoryGetTool(
    argsJson: String,
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))
    return ToolExecutionResult(result.output, result.success)
}

internal fun executeMemoryRollupTool(
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))
    return ToolExecutionResult(result.output, result.success)
}

internal fun appendSubagentTurnText(substitutions: List<String>) { }
"""

BAD_ROLLUP_NO_GATE = GOOD_GATE.replace(
    """internal fun executeMemoryRollupTool(
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
""",
    """internal fun executeMemoryRollupTool(
    repo: com.rikkaminis.app.data.repository.MemoryRepository,
    memoryEnabled: Boolean,
    recordSink: (MemoryToolRecord) -> Unit,
): ToolExecutionResult {
""",
)

BAD_ROLLUP_ISWRITE_FLIP = GOOD_GATE.replace(
    """    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))
    return ToolExecutionResult(result.output, result.success)
}

internal fun appendSubagentTurnText(substitutions: List<String>) { }""",
    """    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = true))
    return ToolExecutionResult(result.output, result.success)
}

internal fun appendSubagentTurnText(substitutions: List<String>) { }""",
)

# W1 (dataflow): the rejection keeps the `return` spelling but exits the lambda.
BAD_ROLLUP_RETURN_LABEL = GOOD_GATE.replace(
    """    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))""",
    """    memoryToolDisabledMessage(memoryEnabled)?.let {
        return@let ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))""")

# W2 (scope): the shared decision is constant-true — it lives outside the
# executor slices the gate used to scan.
BAD_HELPER_ALWAYS_NULL = GOOD_GATE.replace(
    "    if (memoryEnabled) null\n",
    "    if (true || memoryEnabled) null\n", 1)

GOOD_WRAPPER = """internal fun ChatViewModel.executeMemoryRollupTool(): ToolExecutionResult {
    val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
    return com.rikkaminis.app.ui.chat.executeMemoryRollupTool(repo, _memoryEnabled.value) { record ->
        _memoryToolRecords.value = _memoryToolRecords.value + record
    }
}

internal fun ChatViewModel.updateAssistantMessage(id: String) { }
"""

BAD_WRAPPER_NO_VALUE = GOOD_WRAPPER.replace(
    "executeMemoryRollupTool(repo, _memoryEnabled.value)",
    "executeMemoryRollupTool(repo)",
)

# W3 (parser-death): a string literal carries the anchor, the real call is hard-coded.
BAD_WRAPPER_STRING_ANCHOR = GOOD_WRAPPER.replace(
    "    return com.rikkaminis.app.ui.chat.executeMemoryRollupTool(repo, _memoryEnabled.value) { record ->",
    '    val _anchor = "executeMemoryRollupTool(repo, _memoryEnabled.value)"\n'
    "    return com.rikkaminis.app.ui.chat.executeMemoryRollupTool(repo, true) { record ->")

GOOD_ADD = """override fun add(payload: ConfigValue): String {
    val azureMode = (obj["azureMode"] as? ConfigValue.Bool)?.value ?: false
    val customUserAgent = resolveAddCustomUserAgent(
        (obj["customUserAgent"] as? ConfigValue.Str)?.value,
        credentialType,
    )
    val instance = ProviderInstance(
        id = java.util.UUID.randomUUID().toString(),
        customUserAgent = customUserAgent,
    )
    repo.addInstance(instance)
    return instance.id
}

override fun remove(id: String) {
    throw ConfigError.PermissionDenied("remove is denied")
}
"""

BAD_ADD_NO_UA = GOOD_ADD.replace(
    """    val customUserAgent = resolveAddCustomUserAgent(
        (obj["customUserAgent"] as? ConfigValue.Str)?.value,
        credentialType,
    )
""",
    "",
).replace("customUserAgent = customUserAgent,\n", "")

# W4 (dataflow): the payload read is present but discarded by a scope function.
BAD_ADD_DISCARD_UA = GOOD_ADD.replace(
    '        (obj["customUserAgent"] as? ConfigValue.Str)?.value,',
    '        null.also { obj["customUserAgent"] },')

GOOD_WRITER = """private fun customUserAgent(id: String): ConfigField =
    ClosureField(
        path = "providers.$id.customUserAgent",
        writer = { v ->
            val inst = repo.instance(id) ?: throw ConfigError.InvalidValue("Provider not found")
            if (!customUserAgentWritable(inst.credentialType)) {
                throw ConfigError.InvalidValue(CUSTOM_USER_AGENT_OAUTH_MESSAGE)
            }
            mutate(id) { it.copy(customUserAgent = s.ifEmpty { null }) }
        },
    )

private fun apiKeyField(id: String): ConfigField =
    ClosureField(path = "providers.$id.apiKey")
"""

BAD_WRITER_INLINE_RULE = GOOD_WRITER.replace(
    "if (!customUserAgentWritable(inst.credentialType)) {",
    "if (inst.credentialType != ProviderCredential.apiKey) {",
)

# W5 (family-hole): the guard condition survives, the rejection body is a no-op.
BAD_WRITER_UNIT_BODY = GOOD_WRITER.replace(
    "                throw ConfigError.InvalidValue(CUSTOM_USER_AGENT_OAUTH_MESSAGE)",
    "                Unit")

GOOD_RULE = """// ─── [fix/da1020-memory-gate-provider-fields] Shared UA credential rule ──
internal const val CUSTOM_USER_AGENT_OAUTH_MESSAGE: String =
    "Custom User-Agent is only supported for third-party API-key providers."

internal fun customUserAgentWritable(credentialType: ProviderCredential): Boolean =
    credentialType == ProviderCredential.apiKey
"""

BAD_RULE_NO_PREDICATE = GOOD_RULE.replace(
    """
internal fun customUserAgentWritable(credentialType: ProviderCredential): Boolean =
    credentialType == ProviderCredential.apiKey
""",
    "",
)

GOOD_SUMMARY = """r.register(
    ReadOnlyField(
        path = "providers",
        reader = {
            ConfigValue.Arr(
                instances.map { inst ->
                    ConfigValue.Obj(
                        linkedMapOf(
                            "id" to ConfigValue.Str(inst.id),
                            "azureMode" to ConfigValue.Bool(inst.azureMode),
                            "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                        )
                    )
                }
            )
        },
    )
)

r.register(
    ReadOnlyField(
        path = "models",
        reader = { ConfigValue.Arr(emptyList()) },
    )
)
"""

BAD_SUMMARY_NO_UA = GOOD_SUMMARY.replace(
    '                            "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),\n',
    "",
)

# ── [wide-1024] witnesses: the knives that used to walk through this gate ──
# W1 (dataflow): the rollup rejection is BUILT and discarded — no `return`.
BAD_ROLLUP_NO_RETURN = GOOD_GATE.replace(
    """    memoryToolDisabledMessage(memoryEnabled)?.let {
        return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))""",
    """    memoryToolDisabledMessage(memoryEnabled)?.let {
        ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
    }
    recordSink(MemoryToolRecord(title = result.toolTitle, isWrite = false))""")

# W2 (dataflow): the resolver is fed a constant instead of the payload.
BAD_ADD_NULL_UA = GOOD_ADD.replace(
    '        (obj["customUserAgent"] as? ConfigValue.Str)?.value,',
    "        null,")

# W3 (dataflow): the OAuth guard keeps its two needles and loses its `!`.
BAD_WRITER_POLARITY = GOOD_WRITER.replace(
    "if (!customUserAgentWritable(inst.credentialType)) {",
    "if (customUserAgentWritable(inst.credentialType)) {")

# W4 (dataflow): the summary keeps the anchor and projects Null.
BAD_SUMMARY_NULL_VALUE = GOOD_SUMMARY.replace(
    '"customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),',
    '"customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Null } ?: ConfigValue.Null),')

# ── [batch5-t07] witnesses (report-A §05): each knife must land on its own ──
# W5 (dataflow): the projection keeps `ConfigValue.Str(` and passes a constant.
BAD_SUMMARY_EMPTY_VALUE = GOOD_SUMMARY.replace(
    '"customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),',
    '"customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str("") } ?: ConfigValue.Null),')

# W6 (dataflow): the payload is read through a deliberately wrong cast.
BAD_ADD_WRONG_CAST = GOOD_ADD.replace(
    '(obj["customUserAgent"] as? ConfigValue.Str)?.value,',
    '(obj["customUserAgent"] as? ConfigValue.Int)?.toString(),')


def _fixture(check_fn, executors, prompt, collection, builtins):
    def fake_read(root_unused, rel):
        if rel == EXECUTORS_REL:
            return executors
        if rel == PROMPT_REL:
            return prompt
        if rel == COLLECTION_REL:
            return collection
        if rel == BUILTINS_REL:
            return builtins
        raise AssertionError("unexpected rel %s" % rel)

    return check_fn(None, _read=fake_read)


SELFTEST_ARMS = [
    # (label, executors, prompt, collection, builtins, must_trip)
    ("compliant tree", GOOD_GATE, GOOD_WRAPPER, None, GOOD_SUMMARY, False),
    ("rollup w/o gate", BAD_ROLLUP_NO_GATE, GOOD_WRAPPER, None, GOOD_SUMMARY, True),
    ("rollup isWrite flipped", BAD_ROLLUP_ISWRITE_FLIP, GOOD_WRAPPER, None, GOOD_SUMMARY, True),
    ("rollup return@let", BAD_ROLLUP_RETURN_LABEL, GOOD_WRAPPER, None, GOOD_SUMMARY, True),
    ("rollup built-not-returned", BAD_ROLLUP_NO_RETURN, GOOD_WRAPPER, None, GOOD_SUMMARY, True),
    ("gate helper always null", BAD_HELPER_ALWAYS_NULL, GOOD_WRAPPER, None, GOOD_SUMMARY, True),
    ("wrapper w/o gate value", GOOD_GATE, BAD_WRAPPER_NO_VALUE, None, GOOD_SUMMARY, True),
    ("wrapper string anchor", GOOD_GATE, BAD_WRAPPER_STRING_ANCHOR, None, GOOD_SUMMARY, True),
    ("add w/o customUserAgent", GOOD_GATE, GOOD_WRAPPER, "NO_UA", GOOD_SUMMARY, True),
    ("add fed null", GOOD_GATE, GOOD_WRAPPER, "NULL_UA", GOOD_SUMMARY, True),
    ("add discard-scope UA", GOOD_GATE, GOOD_WRAPPER, "DISCARD_UA", GOOD_SUMMARY, True),
    ("add wrong cast", GOOD_GATE, GOOD_WRAPPER, "WRONG_CAST_UA", GOOD_SUMMARY, True),
    ("writer inline rule", GOOD_GATE, GOOD_WRAPPER, "INLINE_RULE", GOOD_SUMMARY, True),
    ("writer Unit body", GOOD_GATE, GOOD_WRAPPER, "UNIT_BODY", GOOD_SUMMARY, True),
    ("writer polarity flipped", GOOD_GATE, GOOD_WRAPPER, "POLARITY", GOOD_SUMMARY, True),
    ("shared rule missing", GOOD_GATE, GOOD_WRAPPER, "NO_RULE", GOOD_SUMMARY, True),
    ("summary w/o customUserAgent", GOOD_GATE, GOOD_WRAPPER, None, BAD_SUMMARY_NO_UA, True),
    ("summary projects Null", GOOD_GATE, GOOD_WRAPPER, None, BAD_SUMMARY_NULL_VALUE, True),
    ("summary projects empty Str", GOOD_GATE, GOOD_WRAPPER, None, BAD_SUMMARY_EMPTY_VALUE, True),
]

COLLECTION_VARIANTS = {
    None: GOOD_WRITER + "\n" + GOOD_RULE + "\n" + GOOD_ADD,
    "NO_UA": GOOD_WRITER + "\n" + GOOD_RULE + "\n" + BAD_ADD_NO_UA,
    "NULL_UA": GOOD_WRITER + "\n" + GOOD_RULE + "\n" + BAD_ADD_NULL_UA,
    "DISCARD_UA": GOOD_WRITER + "\n" + GOOD_RULE + "\n" + BAD_ADD_DISCARD_UA,
    "WRONG_CAST_UA": GOOD_WRITER + "\n" + GOOD_RULE + "\n" + BAD_ADD_WRONG_CAST,
    "INLINE_RULE": BAD_WRITER_INLINE_RULE + "\n" + GOOD_RULE + "\n" + GOOD_ADD,
    "UNIT_BODY": BAD_WRITER_UNIT_BODY + "\n" + GOOD_RULE + "\n" + GOOD_ADD,
    "POLARITY": BAD_WRITER_POLARITY + "\n" + GOOD_RULE + "\n" + GOOD_ADD,
    "NO_RULE": GOOD_WRITER + "\n" + BAD_RULE_NO_PREDICATE + "\n" + GOOD_ADD,
}


def selftest():
    failures = []
    for label, execs, prompt, coll_key, builtins, must_trip in SELFTEST_ARMS:
        coll = COLLECTION_VARIANTS[coll_key]
        violations = _fixture(check, execs, prompt, coll, builtins)
        tripped = bool(violations)
        if tripped == must_trip:
            print("  selftest %-28s PASS (%d violation(s))" % (label, len(violations)))
        else:
            print("  selftest %-28s FAIL (%d violation(s))" % (label, len(violations)))
            failures.append((label, violations, must_trip))
    if failures:
        for label, violations, must_trip in failures:
            print("--- %s (must_trip=%s)" % (label, must_trip))
            for v in violations:
                print("    " + v)
        print("MEMORY_GATE_PROVIDER_FIELDS_SELFTEST=FAIL")
        return 1
    print("MEMORY_GATE_PROVIDER_FIELDS_SELFTEST=PASS (%d arms)" % len(SELFTEST_ARMS))
    return 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    root = args[0] if args else os.getcwd()
    violations = check(root)
    if violations:
        print("FAIL: %d violation(s):" % len(violations))
        for v in violations:
            print("  " + v)
        return 1
    print("memory_gate_provider_fields_check: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
