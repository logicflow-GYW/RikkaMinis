#!/usr/bin/env python3
"""Privacy-mode switch scope guard — RikkaMinis CI scan gate.

## Why this gate exists

2026-11-24: the tool-output redaction release (2026-11-23) masked the PERSISTED
copy of every tool result unconditionally, so a session reload handed the model
`[MINIS_REDACTED]` in place of the argument it had to execute. The device log
shows the consequence directly:

    /bin/sh: eval: line 892: [MINIS_REDACTED]: not found

Every shape gate stayed green: the store had a `DEFAULT_ENABLED`, the UI had a
switch, the redactor was wired. What was missing was a SCOPE agreement — some
sinks followed the switch and some did not. That is not a shape defect, so no
existing gate could see it.

## What it pins

The switch governs exactly the MODEL-FACING sinks. Each judgment is a decision
point, not a spelling:

  [P1] `EnvVarPrivacyStore.DEFAULT_ENABLED` is `false`. Masking rewrites what the
       model reads, so it must be an opt-in, never a silent default.

  [P2] The persisted tool-result payload (`buildToolResultPartsJson`) branches on
       `EnvVarPrivacyStore.isEnabled` and only calls the redactor inside the ON
       branch. A copy that is masked in both states is the exact bug above.

  [P3] The persisted tool_use payload (`buildAssistantTurnPartsJson`) does the
       same, through its local `view(...)` helper, AND its replay-eligibility
       argument short-circuits when the switch is OFF (`!redact || …`). Without
       the short-circuit, an OFF session drops capsules for rows it stored
       byte-identically.

  [P4] The context-offload file write branches on the switch. An offloaded file
       is re-read later by the agent, so it is a model-facing sink.

  [P5] The transcript-redaction map is filled only when the switch is ON, while
       the `SensitiveCommandPolicy` entry stays unconditional — that one is a
       different rule (credential-bearing commands never reach a durable row).

  [P6] Durable diagnostics stay unconditional: `redactForDiagnostics` must not
       consult the switch (a trace file is not the model payload, and turning
       the switch off must not turn a log into a secret store).

  [P7] The `privacyRedacted` flag means "the MODEL-facing projection changed
       real bytes". Folding in the diagnostics view or the args copy makes the
       flag true while the model read the real bytes.

  [P8] The history projection (`redactToolHistoryForModel`) re-projects tool
       ARGUMENTS too. Turning the switch ON after a row was saved OFF must not
       hand the model the arguments that row legitimately stored, nor keep a
       replay capsule whose bytes no longer match the request.

## Fail-closed

A file or symbol that cannot be read is REPORTED, never skipped: "I could not
find the branch" and "there is no branch" must not look the same.

## Escape hatch

`privacy-scope-ok: <reason>` on the line above a judged call site.

Usage:
  python3 scripts/scan/privacy_switch_scope_check.py <repo_root>
  python3 scripts/scan/privacy_switch_scope_check.py --selftest
Exit: 0 = every judged sink agrees on the switch, 1 = violation (file:line printed)
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_comments


def strip_comments(src):
    """Shared lexer; unlexable source degrades to blank text (judges then RED)."""
    try:
        return mask_comments(src)
    except KotlinLexError:
        return ""

SRC = "src/android/app/src/main/java"

STORE = SRC + "/com/rikkaminis/app/data/EnvVarPrivacyStore.kt"
REDACTOR = SRC + "/com/rikkaminis/app/data/EnvVarRedactor.kt"
PARTS = SRC + "/com/rikkaminis/app/ui/chat/ChatTurnPartsJson.kt"
CONTEXT = SRC + "/com/rikkaminis/app/ui/chat/ChatContextWindow.kt"
ENGINE = SRC + "/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
PROMPT = SRC + "/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
# [fix/privacy-reenable-tooluse-1124] The last model-facing projection: a
# session reload re-checks what the switch governs at request time.
HISTORY = SRC + "/com/rikkaminis/app/ui/chat/ChatToolPrivacy.kt"

EXEMPT = "privacy-scope-ok:"


def read(root, rel):
    path = os.path.join(root, rel)
    with open(path, encoding="utf-8") as f:
        return f.read()


def brace_span(src, open_at):
    """End index (inclusive) of the brace block starting at `open_at`."""
    depth = 0
    i = open_at
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def fn_body(src, marker):
    start = src.find(marker)
    if start < 0:
        return None, -1
    brace = src.find("{", start)
    if brace < 0:
        return None, -1
    end = brace_span(src, brace)
    if end < 0:
        return None, -1
    return src[brace:end + 1], brace


def line_of(src, offset):
    return src.count("\n", 0, offset) + 1


class Report(object):
    def __init__(self):
        self.problems = []
        self.notes = []

    def red(self, where, msg):
        self.problems.append("%s RED: %s" % (where, msg))

    def note(self, where, msg):
        self.notes.append("%s: %s" % (where, msg))


def judge_store(root, rep):
    src = strip_comments(read(root, STORE))
    m = re.search(r"DEFAULT_ENABLED\s*(?::\s*Boolean)?\s*=\s*(\w+)", src)
    if not m:
        rep.red(STORE, "DEFAULT_ENABLED not found — cannot prove the default")
        return
    if m.group(1) != "false":
        rep.red(STORE, "DEFAULT_ENABLED = %s (masking must be opt-in)" % m.group(1))


def judge_parts(root, rep):
    src = strip_comments(read(root, PARTS))

    # [P2] persisted tool-result payload
    body, at = fn_body(src, "internal fun buildToolResultPartsJson(")
    if body is None:
        rep.red(PARTS, "buildToolResultPartsJson not found")
    else:
        if "EnvVarPrivacyStore.isEnabled" not in body:
            rep.red(PARTS + ":%d" % line_of(src, at),
                    "persisted tool result does not consult the privacy switch")
        elif "redactWithSnapshot(result.content" in body:
            # The mask call must sit inside an ON branch that precedes it.
            mask_at = body.find("redactWithSnapshot(result.content")
            guard_at = body.find("EnvVarPrivacyStore.isEnabled")
            else_at = body.find("} else {", guard_at)
            if guard_at < 0 or mask_at < guard_at:
                rep.red(PARTS + ":%d" % line_of(src, at + mask_at),
                        "tool-result mask runs before/without the switch branch")
            elif else_at < 0 or not (mask_at < else_at):
                rep.red(PARTS + ":%d" % line_of(src, at + mask_at),
                        "tool-result mask is not inside the ON branch")

    # [P3] persisted tool_use payload + replay eligibility
    body, at = fn_body(src, "internal fun buildAssistantTurnPartsJson(")
    if body is None:
        rep.red(PARTS, "buildAssistantTurnPartsJson not found")
        return
    if "EnvVarPrivacyStore.isEnabled" not in body:
        rep.red(PARTS + ":%d" % line_of(src, at),
                "persisted tool_use payload does not consult the privacy switch")
    # The eligibility argument is the SECOND top-level argument of
    # appendAnthropicReplayPart(…, replay?.takeIf { … }).
    tail = body[body.find("appendAnthropicReplayPart("):] if "appendAnthropicReplayPart(" in body else ""
    if not tail:
        rep.red(PARTS + ":%d" % line_of(src, at),
                "appendAnthropicReplayPart not found — replay gate unreadable")
    elif not re.search(r"takeIf\s*\{[^}]*!\s*redact", tail):
        rep.red(PARTS + ":%d" % line_of(src, at + body.find("appendAnthropicReplayPart(")),
                "replay gate does not short-circuit when the switch is OFF")


def judge_context(root, rep):
    src = strip_comments(read(root, CONTEXT))
    body, at = fn_body(src, "internal fun ChatViewModel.offloadContextIfNeeded(")
    if body is None:
        rep.red(CONTEXT, "offloadContextIfNeeded not found")
        return
    if "ContextOffload.offloadContent(" not in body:
        rep.red(CONTEXT + ":%d" % line_of(src, at), "offloadContent call not found")
        return
    if "EnvVarPrivacyStore.isEnabled" not in body:
        rep.red(CONTEXT + ":%d" % line_of(src, at),
                "offload payload does not consult the privacy switch")


def judge_engine(root, rep):
    src = strip_comments(read(root, ENGINE))
    body, at = fn_body(src, "internal fun AgentLoopEngine.runAgentLoop(")
    if body is None:
        # The engine is a class; the loop lives in a member function.
        body, at = fn_body(src, "fun runAgentLoop(")
    if body is None:
        rep.red(ENGINE, "runAgentLoop not found")
        return
    if "transcriptRedactions[id] =" not in body:
        rep.red(ENGINE + ":%d" % line_of(src, at), "transcriptRedactions write not found")
        return
    # Every transcriptRedactions write must be either the sensitive-command rule
    # (unconditional by design) or gated on the switch.
    for m in re.finditer(r"transcriptRedactions\[id\]\s*=", body):
        line_start = body.rfind("\n", 0, m.start()) + 1
        line_end = body.find("\n", m.start())
        line = body[line_start:line_end if line_end > 0 else len(body)]
        if "SensitiveCommandPolicy.redactionPlaceholder" in line:
            continue
        # Walk back to the guarding `if` in the preceding lines.
        window = body[max(0, m.start() - 400):m.start()]
        if "EnvVarPrivacyStore.isEnabled" not in window:
            rep.red(ENGINE + ":%d" % line_of(src, at + m.start()),
                    "transcriptRedactions write is not gated on the privacy switch")
    if "SensitiveCommandPolicy.redactionPlaceholder" not in body:
        rep.red(ENGINE + ":%d" % line_of(src, at),
                "sensitive-command transcript rule disappeared (it is unconditional by design)")


def judge_prompt_flag(root, rep):
    src = strip_comments(read(root, PROMPT))
    body, at = fn_body(src, "internal suspend fun ChatViewModel.executeTool(")
    if body is None:
        rep.red(PROMPT, "executeTool not found")
        return
    for m in re.finditer(r"privacyRedacted\s*=", body):
        line_end = body.find("\n", m.start())
        line = body[m.start():line_end if line_end > 0 else len(body)]
        for bad in ("diagView.hits", "safeArgs != argsJson"):
            if bad in line:
                rep.red(PROMPT + ":%d" % line_of(src, at + m.start()),
                        "privacyRedacted folds in %s, so it is true while the model "
                        "read the real bytes" % bad)


def judge_diagnostics_unconditional(root, rep):
    src = strip_comments(read(root, REDACTOR))
    body, at = fn_body(src, "internal fun redactForDiagnostics(")
    if body is None:
        rep.red(REDACTOR, "redactForDiagnostics not found")
        return
    if "EnvVarPrivacyStore.isEnabled" in body:
        rep.red(REDACTOR + ":%d" % line_of(src, at),
                "redactForDiagnostics consults the switch — durable diagnostics must "
                "stay redacted in both states")


def judge_history(root, rep):
    """[P8] The reload projection must re-check tool ARGUMENTS, not only results."""
    src = strip_comments(read(root, HISTORY))
    body, at = fn_body(src, "internal fun redactToolHistoryForModel(")
    if body is None:
        rep.red(HISTORY, "redactToolHistoryForModel not found — the last model-facing "
                         "projection is unreadable, so no sink can be proven")
        return
    arm = body.find("is AgentContentPart.ToolUse ->")
    if arm < 0:
        rep.red(HISTORY + ":%d" % line_of(src, at),
                "history projection has no ToolUse arm — a row saved with the switch OFF "
                "reaches the model with its real arguments once the switch is turned ON")
        return
    # The arm must run the redactor over the arguments AND hand the model the
    # projected copy; a re-typed `part` alone would leave the bytes untouched.
    window = body[arm:arm + 1200]
    if "redactForModel(" not in window:
        rep.red(HISTORY + ":%d" % line_of(src, at + arm),
                "ToolUse arm does not project its arguments through the redactor")
    elif "copy(input" not in window:
        rep.red(HISTORY + ":%d" % line_of(src, at + arm),
                "ToolUse arm computes a projection but never builds the model copy")


JUDGES = (
    ("P1", judge_store),
    ("P2/P3", judge_parts),
    ("P4", judge_context),
    ("P5", judge_engine),
    ("P7", judge_prompt_flag),
    ("P6", judge_diagnostics_unconditional),
    ("P8", judge_history),
)


def run(root):
    rep = Report()
    for name, judge in JUDGES:
        try:
            judge(root, rep)
        except FileNotFoundError as e:
            rep.red(name, "unreadable source: %s" % e)
    return rep


# ── selftest: a real tree plus targeted mutations ─────────────────────────

SELFTEST_TREE = {
    STORE: """
object EnvVarPrivacyStore {
    @VisibleForTesting internal const val DEFAULT_ENABLED = false
}
""",
    PARTS: """
internal fun buildAssistantTurnPartsJson(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock>,
    anthropicReplay: AnthropicReplay? = null,
): String {
    val snap = com.rikkaminis.app.data.EnvVarRedactor.snapshot()
    val redact = com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled
    fun view(text: String): String =
        if (redact) com.rikkaminis.app.data.EnvVarRedactor.redactWithSnapshot(text, snap, null).text else text
    return appendAnthropicReplayPart(buildString {
        append(view("x"))
    }, anthropicReplay?.takeIf {
        !redact || parts.filterIsInstance<AgentContentPart.ToolUse>().all { tool -> true }
    })
}

internal fun buildToolResultPartsJson(results: List<AgentContentPart.ToolResult>): String {
    val snapshot = com.rikkaminis.app.data.EnvVarRedactor.snapshot()
    val redact = com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled
    return buildString {
        results.forEachIndexed { index, result ->
            val output = if (redact) {
                com.rikkaminis.app.data.EnvVarRedactor.redactWithSnapshot(result.content, snapshot, null).text
            } else {
                result.content
            }
            append(output)
        }
    }
}
""",
    CONTEXT: """
internal fun ChatViewModel.offloadContextIfNeeded(force: Boolean) {
    val payload = if (com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled) {
        com.rikkaminis.app.data.EnvVarRedactor.redactWithSnapshot(part.content, offloadSnapshot, null).text
    } else {
        part.content
    }
    ContextOffload.offloadContent(context, sid, payload)
}
""",
    ENGINE: """
internal class AgentLoopEngine {
    fun runAgentLoop() {
        if (name == "shell_execute") {
            if (SensitiveCommandPolicy.shouldRedactFromTranscript(command)) {
                transcriptRedactions[id] = SensitiveCommandPolicy.redactionPlaceholder(command)
            }
        }
        if (result.privacyRedacted && com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled) {
            transcriptRedactions[id] = com.rikkaminis.app.data.EnvVarRedactor.redactWithSnapshot(result.output, pass3Snapshot, null).text
        }
    }
}
""",
    PROMPT: """
internal suspend fun ChatViewModel.executeTool(name: String): ToolExecutionResult {
    val modelResult = result.copy(
        output = modelView.text,
        privacyRedacted = result.privacyRedacted || modelView.hits > 0,
    )
    return modelResult
}
""",
    REDACTOR: """
object EnvVarRedactor {
    internal fun redactForDiagnostics(output: String, onRedacted: ((String) -> Unit)? = null): SecretRedaction {
        return SecretRedactionRules.redact(output, values, onRedacted)
    }
}
""",
    HISTORY: """
internal fun redactToolHistoryForModel(messages: List<LLMMessage>): List<LLMMessage> {
    if (!EnvVarPrivacyStore.isEnabled) return messages
    val snapshot = EnvVarRedactor.snapshot()
    var changed = false
    val projected = messages.map { message ->
        val parts = message.contentParts.map { part ->
            when (part) {
                is AgentContentPart.ToolResult -> {
                    val redaction = EnvVarRedactor.redactForModel(part.content, snapshot)
                    if (redaction.text == part.content) part else part.copy(content = redaction.text)
                }
                is AgentContentPart.ToolUse -> {
                    val rawInput = part.input.toString()
                    val redaction = EnvVarRedactor.redactForModel(rawInput, snapshot)
                    if (redaction.text == rawInput) part
                    else part.copy(input = projectedToolInput(redaction.text))
                }
                else -> part
            }
        }
        if (parts == message.contentParts) message
        else { changed = true; message.copy(contentParts = parts) }
    }
    return if (changed) guardAnthropicReplayHistory(projected, messages) else messages
}
""",
}


def selftest():
    import shutil
    import tempfile

    failures = []

    def check(name, cond, detail=""):
        if not cond:
            failures.append(name + ((" — " + detail) if detail else ""))

    def write_tree(tree):
        d = tempfile.mkdtemp(prefix="privacy-scope-")
        for rel, text in tree.items():
            p = os.path.join(d, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)
        return d

    def mutate(tree, rel, old, new):
        t = dict(tree)
        if old not in t[rel]:
            raise AssertionError("mutation anchor missing in %s: %r" % (rel, old))
        t[rel] = t[rel].replace(old, new, 1)
        return t

    def rc(tree):
        d = write_tree(tree)
        try:
            rep = run(d)
            return (0 if not rep.problems else 1), rep.problems
        finally:
            shutil.rmtree(d, ignore_errors=True)

    # Gate-level lexer witnesses (measured, see the evidence probe): each shape
    # below made the PRE-refactor private lexer mis-read the file — a nested
    # block comment leaked its tail as code (decoy default true), and a `'"'`
    # char literal swallowed the real declaration. The shared lexer must keep
    # the gate green, AND the real default must still be readable: flipping it
    # to true inside the same decoyed file must still turn the gate red.
    for name, prefix in (
        ("nested block comment", "/* outer /* inner */ DEFAULT_ENABLED = true */\n"),
        ("double quote char", "val quote = '\"' // DEFAULT_ENABLED = true\n"),
    ):
        tree = dict(SELFTEST_TREE)
        tree[STORE] = prefix + tree[STORE]
        code, problems = rc(tree)
        check(name, code == 0, "; ".join(problems))
        still_readable = mutate(tree, STORE, "DEFAULT_ENABLED = false", "DEFAULT_ENABLED = true")
        code2, _ = rc(still_readable)
        check(name + " (real default still judged)", code2 == 1, "gate stayed green")
        if code == 0 and code2 == 1:
            print("  LEXER ARM OK: " + name)

    # Fail-closed: unlexable source must be RED, not silently compliant.
    broken = dict(SELFTEST_TREE)
    broken[STORE] = broken[STORE] + 'val s = "unterminated\n'
    code, problems = rc(broken)
    check("unlexable source is reported", code == 1, "gate stayed green")
    if code == 1:
        print("  LEXER ARM OK: unlexable source")

    # positive control: the fixed tree must be GREEN
    code, problems = rc(SELFTEST_TREE)
    check("fixed tree is green", code == 0, "; ".join(problems))

    arms = [
        ("P1 default back to true",
         mutate(SELFTEST_TREE, STORE, "DEFAULT_ENABLED = false", "DEFAULT_ENABLED = true")),
        ("P2 tool result masked unconditionally",
         mutate(SELFTEST_TREE, PARTS,
                """            val output = if (redact) {
                com.rikkaminis.app.data.EnvVarRedactor.redactWithSnapshot(result.content, snapshot, null).text
            } else {
                result.content
            }""",
                """            val output = com.rikkaminis.app.data.EnvVarRedactor.redactWithSnapshot(result.content, snapshot, null).text""")),
        ("P3 replay gate lost its short-circuit",
         mutate(SELFTEST_TREE, PARTS, "!redact || parts.filterIsInstance", "parts.filterIsInstance")),
        ("P4 offload write always masks",
         mutate(SELFTEST_TREE, CONTEXT,
                "val payload = if (com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled) {",
                "val payload = if (true) {")),
        ("P5 transcript copy ungated",
         mutate(SELFTEST_TREE, ENGINE,
                "if (result.privacyRedacted && com.rikkaminis.app.data.EnvVarPrivacyStore.isEnabled) {",
                "if (result.privacyRedacted) {")),
        ("P5b sensitive-command rule dropped",
         mutate(SELFTEST_TREE, ENGINE,
                "transcriptRedactions[id] = SensitiveCommandPolicy.redactionPlaceholder(command)",
                "// removed")),
        ("P7 flag folds in the diagnostics view",
         mutate(SELFTEST_TREE, PROMPT,
                "privacyRedacted = result.privacyRedacted || modelView.hits > 0,",
                "privacyRedacted = result.privacyRedacted || modelView.hits > 0 || diagView.hits > 0,")),
        ("P7b flag folds in the args copy",
         mutate(SELFTEST_TREE, PROMPT,
                "privacyRedacted = result.privacyRedacted || modelView.hits > 0,",
                "privacyRedacted = result.privacyRedacted || modelView.hits > 0 || safeArgs != argsJson,")),
        ("P8 history ToolUse arm dropped",
         mutate(SELFTEST_TREE, HISTORY,
                """                is AgentContentPart.ToolUse -> {
                    val rawInput = part.input.toString()
                    val redaction = EnvVarRedactor.redactForModel(rawInput, snapshot)
                    if (redaction.text == rawInput) part
                    else part.copy(input = projectedToolInput(redaction.text))
                }
""", "")),
        ("P8b history ToolUse arm keeps the raw object",
         mutate(SELFTEST_TREE, HISTORY,
                "if (redaction.text == rawInput) part\n                    else part.copy(input = projectedToolInput(redaction.text))",
                "part")),
        ("P6 diagnostics consult the switch",
         mutate(SELFTEST_TREE, REDACTOR,
                "        return SecretRedactionRules.redact(output, values, onRedacted)",
                "        if (!EnvVarPrivacyStore.isEnabled) return SecretRedaction(output, 0)\n"
                "        return SecretRedactionRules.redact(output, values, onRedacted)")),
    ]
    for name, tree in arms:
        code, problems = rc(tree)
        check(name + " -> RED", code == 1, "gate stayed green")
        if code == 1:
            print("  ARM OK: " + name)

    # fail-closed: a missing file must report, not pass
    d = write_tree(SELFTEST_TREE)
    try:
        os.remove(os.path.join(d, PARTS))
        rep = run(d)
        check("missing source is reported", bool(rep.problems))
    finally:
        shutil.rmtree(d, ignore_errors=True)

    if failures:
        for f in failures:
            print("SELFTEST ARM FAILED: " + f)
        print("SELFTEST=FAIL (%d)" % len(failures))
        return 1
    print("SELFTEST=PASS (%d checks: %d mutations + 2 lexer shapes x2 + unlexable + fail-closed)"
          % (len(arms) + 6, len(arms)))
    return 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    root = argv[1] if len(argv) > 1 else "."
    rep = run(root)
    for n in rep.notes:
        print("  [note] " + n)
    if rep.problems:
        for p in rep.problems:
            print(p)
        print("\nprivacy_switch_scope_check: %d violation(s)" % len(rep.problems))
        return 1
    print("privacy_switch_scope_check: OK (privacy switch governs every model-facing sink)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
