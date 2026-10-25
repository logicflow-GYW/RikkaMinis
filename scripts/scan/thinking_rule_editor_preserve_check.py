#!/usr/bin/env python3
"""
Gate: thinking-rule editor preserve (deep-audit-1024 ISSUE-06,
branch fix/da1024-thinking-rule-editor-preserve).

ThinkingRuleEditorDialog's Save used to rebuild its subject from scratch and got
two things wrong (both reproduced against the pre-fix tree by
probe/jvm/PreFixSim.kt, output in probe/jvm/work/prefix_sim.txt):

  1. `buildRule()` used the four-argument positional `ThinkingRule(...)`
     constructor, so `reasoningEcho` (the vendor history-echo contract:
     mistral NEVER, deepseek-v4-relay AFTER_TOOL_USE_ONLY) and `id` were dropped
     on EVERY save — the same family as da1023 ISSUE-02's maxThinkingLevel wipe.
  2. the saved format was derived from the dropdown ALONE, through
     `FormatChoice.from`, whose `else` branch is REASONING_EFFORT. A rule whose
     format the dropdown cannot offer — DeepSeekRelay / QwenRootOnly (the two
     shapes `ThinkingRuleResolver` generates for a deepseek-v4 relay and for
     Qwen on a non-DashScope host) plus `null` ("no opinion") — was therefore
     rewritten by a save that changed nothing else. The OFF branch is what
     differs (explicit `thinking:{type:"disabled"}` / portable
     `enable_thinking:false` versus "omit"), and omitting an explicit disable
     leaves both families reasoning by default.

The fix moved the derivation into the pure `buildEditedThinkingRule()` /
`chooseEditedWireFormat()` / `isDropdownRepresentableWireFormat()`
(ThinkingRuleEditPreserve.kt) built on `seed.copy(...)`. This gate pins the
wiring in BOTH files, as text (the composable is not compilable in the sandbox
harness):

  Arm 1 — the dialog must route Save through `buildEditedThinkingRule(` and must
          NOT construct `ThinkingRule(` itself.
  Arm 2 — the builder must derive from `seed.copy(` (the only construction that
          carries the fields this dialog does not surface).
  Arm 3 — the format must be chosen through `chooseEditedWireFormat(`, fed the
          seed's own format and the "user changed it" flag; the decision itself
          must keep the `if (formatChanged || isDropdownRepresentable...)`
          shape, so the dropdown's `else` fallback cannot reach the wire.
  Arm 4 — every format control must set that flag: the dropdown's own onClick
          AND every per-format parameter handler inside the `when (choice)`
          block (≥5 of them). A parameter field the user edits belongs to the
          dropdown's choice, so honouring the flag there is what stops this fix
          from swapping one silent discard for another.
  Arm 5 — anti-drift: every type `FormatChoice.from` maps to a concrete choice
          must also be in `isDropdownRepresentableWireFormat`'s list. That
          divergence (a format the dropdown can DISPLAY but the save path does
          not treat as representable, or vice versa) is the root cause of
          defect 2, so it must not be left to review.

Usage: python3 scripts/scan/thinking_rule_editor_preserve_check.py [root]
       python3 scripts/scan/thinking_rule_editor_preserve_check.py --root <root>
       python3 scripts/scan/thinking_rule_editor_preserve_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys
import tempfile

APP = "src/android/app/src/main/java/com/rikkaminis/app"
EDITOR_REL = APP + "/ui/settings/ThinkingRuleEditor.kt"
PURE_REL = APP + "/ui/settings/ThinkingRuleEditPreserve.kt"

DIALOG_HEADER = "fun ThinkingRuleEditorDialog("
DIALOG_END = "\nenum class FormatChoice("
BUILDER_HEADER = "fun buildEditedThinkingRule("
CHOOSER_HEADER = "fun chooseEditedWireFormat("
REPRESENTABLE_HEADER = "fun isDropdownRepresentableWireFormat("
FROM_HEADER = "fun from(fmt: ThinkingWireFormat?): FormatChoice"

# Arm 1 — dialog route. Code anchors only (comments are stripped first).
ARM1_ANCHORS = ("buildEditedThinkingRule(", "seed = seed")
# Arm 2 — builder derivation shape.
ARM2_ANCHORS = ("seed.copy(", "kind = ThinkingRule.Kind.CUSTOM")
# Fields the dialog does NOT surface: naming either one inside `seed.copy(…)`
# re-drops it on every save (da1024 ISSUE-06 defect 1). `reasoningEcho` drives the
# vendor history-echo contract, `id` keys the persisted rule.
COPY_FORBIDDEN_FIELDS = ("id", "reasoningEcho")
SEED_COPY = "seed.copy("
# Arm 3 — guarded format choice.
ARM3_DIALOG_ANCHORS = (
    "chooseEditedWireFormat(",
    "seedWire = seed.wireFormat",
    "formatChanged = formatChanged",
)
ARM3_CHOOSER_ANCHOR = "if (formatChanged || isDropdownRepresentableWireFormat(seedWire)) edited else seedWire"

# Forbidden: the pre-fix positional construction. `ThinkingRule.Kind.CUSTOM`
# etc. are not matched (the regex needs `(` straight after the type name).
BARE_CTOR_RE = re.compile(r"\bThinkingRule\s*\(")

# Arm 4 — per-format `when (choice)` block and the flag every control sets.
PER_FORMAT_MARKER = "FormatChoice.BOOLEAN_TOGGLE, FormatChoice.EXTRA_BODY_TOGGLE"
PER_FORMAT_MIN_HANDLERS = 5
DROPDOWN_RE = re.compile(r"onClick\s*=\s*\{([^{}]*)\}")
FLAG_ASSIGNMENT = "formatChanged = true"
# Names of the handlers, by HEAD (the body is brace-matched). `[^{}]*` bodies
# dropped every handler that contains a nested `{ … }` — see handlers_in().
HANDLER_HEAD_RE = re.compile(r"on(?:ValueChange|CheckedChange)\s*=\s*\{")
# A handler parked under a literal-false guard is not a control the user can
# reach; counting it is how a real handler's missing flag got cancelled out.
DEAD_GUARD_RE = re.compile(r"if\s*\(\s*(?:false\b|true\s*\|\|)")
# Last write wins: a handler that sets the flag and then clears it discards the
# user's edit exactly like one that never set it.
FLAG_WRITE_RE = re.compile(r"\bformatChanged\s*=\s*(true|false)\b")

# Arm 5 — type names after `is ThinkingWireFormat.` in each function body.
FMT_TYPE_RE = re.compile(r"is\s+ThinkingWireFormat\.(\w+)")


def code_only(text):
    """Text with line comments (//…) removed — presence checks must not be
    satisfiable from prose in a comment."""
    return "\n".join(line.split("//")[0] for line in text.split("\n"))


def declaration_text(src, header):
    """The text of the declaration starting at `header`, up to the next blank
    line. Deliberately NOT a brace matcher: the chosen functions here use
    expression bodies (no `{`), and a brace scan would silently grab a block
    from further down the file. A declaration that grows an internal blank line
    truncates the region — which surfaces as a missing-anchor violation
    (fail-closed), never as a false pass."""
    i = src.find(header)
    if i < 0:
        return None
    j = src.find("\n\n", i)
    return src[i:j] if j > 0 else src[i:]


def brace_block(src, header):
    """The `{…}` block belonging to `header`, brace-matched (for the enum/class
    shaped regions)."""
    i = src.find(header)
    if i < 0:
        return None
    brace = src.find("{", i)
    if brace < 0:
        return None
    depth = 0
    for k in range(brace, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
    return None


def dialog_text(src):
    i = src.find(DIALOG_HEADER)
    if i < 0:
        return None
    j = src.find(DIALOG_END, i)
    if j < 0:
        return None
    return src[i:j]


def per_format_block(src):
    """The per-format `when (choice)` block: the one that renders
    VALUE handlers for the two path formats. Identified by CODE (its branch
    list), not by a comment — comments are stripped before this runs."""
    selected = []
    for m in re.finditer(r"when\s*\(\s*choice\s*\)\s*\{", src):
        block = brace_block(src[m.start():], "when (choice) {")
        if block and PER_FORMAT_MARKER in block:
            selected.append(block)
    if len(selected) != 1:
        return None
    return selected[0]


def check_anchors(src, rel, body, arm, anchors, what):
    out = []
    code = code_only(body)
    for anchor in anchors:
        if anchor not in code:
            out.append((rel, 0, arm,
                        "%s is missing required code anchor `%s`" % (what, anchor)))
    return out


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def call_arg_lists(src, call):
    """The argument-list text of every `call` in `src`, paren-matched."""
    out = []
    idx = 0
    while True:
        i = src.find(call, idx)
        if i < 0:
            return out
        j = i + len(call)
        depth = 1
        while j < len(src) and depth:
            if src[j] in "([{":
                depth += 1
            elif src[j] in ")]}":
                depth -= 1
            j += 1
        out.append(src[i + len(call):j - 1])
        idx = j


def split_args(arglist):
    """Split a Kotlin argument list on TOP-LEVEL commas (depth-aware)."""
    args, depth, cur = [], 0, ""
    for ch in arglist:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur)
    return args


def named_arg_keys(arglist):
    """The `name =` keys of a Kotlin argument list (positional args skipped)."""
    keys = []
    for a in split_args(arglist):
        if "=" not in a:
            continue
        key = a.split("=", 1)[0].strip()
        if re.fullmatch(r"[A-Za-z_]\w*", key):
            keys.append(key)
    return keys


def handlers_in(block):
    """(offset, body) for every on*Change lambda in `block`, brace-matched.

    `[^{}]*` silently dropped every handler that contains a nested `{ … }` —
    from the COUNT and from the flag check alike. Writing one control's body as
    `{ run { … } }` therefore removed it from the audit while the `>= 5` count
    was still satisfied by an unreachable `if (false) Switch(…)` elsewhere
    (witness Arm 4, parser-death).
    """
    out = []
    for m in HANDLER_HEAD_RE.finditer(block):
        brace = m.end() - 1
        depth = 0
        for k in range(brace, len(block)):
            if block[k] == "{":
                depth += 1
            elif block[k] == "}":
                depth -= 1
                if depth == 0:
                    out.append((m.start(), block[brace + 1:k]))
                    break
    return out


def dead_guarded(block, offset):
    """True when the handler at `offset` sits under a literal-false guard."""
    line_start = block.rfind("\n", 0, offset) + 1
    return bool(DEAD_GUARD_RE.search(block[line_start:offset]))


def last_flag_write(block):
    """The final `formatChanged = …` value in `block`, or None."""
    writes = FLAG_WRITE_RE.findall(block)
    return writes[-1] if writes else None


def run_checks(root, rels=None):
    rels = rels or {"editor": EDITOR_REL, "pure": PURE_REL}
    srcs = {k: read(rel, root) for k, rel in rels.items()}
    missing = [(rel, 0, "?", "file not found — gate ROT")
               for k, rel in rels.items() if srcs[k] is None]
    if missing:
        return missing
    editor = code_only(srcs["editor"])
    pure = code_only(srcs["pure"])
    checks = []

    # ---- Arm 1: the dialog routes Save through the pure builder ----
    dialog = dialog_text(editor)
    if dialog is None:
        checks.append((EDITOR_REL, 0, "1", "ThinkingRuleEditorDialog region not found — gate ROT"))
    else:
        checks += check_anchors(srcs["editor"], EDITOR_REL, dialog, "1",
                                ARM1_ANCHORS, "ThinkingRuleEditorDialog")
        if BARE_CTOR_RE.search(dialog):
            checks.append((EDITOR_REL, 0, "1",
                           "ThinkingRuleEditorDialog constructs `ThinkingRule(` itself — the "
                           "positional constructor drops reasoningEcho/id (da1024 ISSUE-06 defect 1); "
                           "route it through buildEditedThinkingRule( instead"))

    # ---- Arm 2: the builder derives from seed.copy(...) ----
    builder = declaration_text(pure, BUILDER_HEADER)
    if builder is None:
        checks.append((PURE_REL, 0, "2", "buildEditedThinkingRule( not found — gate ROT"))
    else:
        checks += check_anchors(srcs["pure"], PURE_REL, builder, "2",
                                ARM2_ANCHORS, "buildEditedThinkingRule")
        if BARE_CTOR_RE.search(builder):
            checks.append((PURE_REL, 0, "2",
                           "buildEditedThinkingRule builds `ThinkingRule(` instead of "
                           "`seed.copy(` — every field the dialog does not surface is wiped"))
        # `seed.copy(` only preserves what it does NOT name: an explicit
        # `id = …` / `reasoningEcho = null` in the argument list re-drops the very
        # fields arm 2 exists to protect (witness Arm 2, dataflow).
        copies = call_arg_lists(code_only(builder), SEED_COPY)
        if not copies:
            checks.append((PURE_REL, 0, "2",
                           "could not read the `seed.copy(` argument list — the "
                           "preserved-fields check cannot run (fail-closed)"))
        else:
            keys = set()
            for arglist in copies:
                keys.update(named_arg_keys(arglist))
            overridden = sorted(keys & set(COPY_FORBIDDEN_FIELDS))
            if overridden:
                checks.append((PURE_REL, 0, "2",
                               "buildEditedThinkingRule overrides %s inside `seed.copy(…)` — those "
                               "are exactly the fields this dialog does not surface, so naming them "
                               "(even to pass a re-derived value) re-opens da1024 ISSUE-06 defect 1: "
                               "the vendor history-echo contract and the rule identity die on Save"
                               % overridden))

    # ---- Arm 3: the format survives unless the user replaced it ----
    chooser = declaration_text(pure, CHOOSER_HEADER)
    if chooser is None:
        checks.append((PURE_REL, 0, "3", "chooseEditedWireFormat( not found — gate ROT"))
    else:
        if ARM3_CHOOSER_ANCHOR not in code_only(chooser):
            checks.append((PURE_REL, 0, "3",
                           "chooseEditedWireFormat must keep `%s`" % ARM3_CHOOSER_ANCHOR))
    if dialog is None:
        pass
    else:
        checks += check_anchors(srcs["editor"], EDITOR_REL, dialog, "3",
                                ARM3_DIALOG_ANCHORS, "ThinkingRuleEditorDialog")

    # ---- Arm 4: every format control records that the user changed it ----
    if dialog is None:
        pass
    else:
        dropdown_at = dialog.find("DropdownMenu(")
        if dropdown_at < 0:
            checks.append((EDITOR_REL, 0, "4", "DropdownMenu( not found in the dialog — gate ROT"))
        else:
            pick = None
            for m in DROPDOWN_RE.finditer(dialog, dropdown_at):
                pick = m.group(1)
                break
            if pick is None:
                checks.append((EDITOR_REL, 0, "4", "no dropdown onClick block found — gate ROT"))
            elif "choice = c" not in pick or FLAG_ASSIGNMENT not in pick:
                checks.append((EDITOR_REL, 0, "4",
                               "the format dropdown's onClick must set `%s` alongside the "
                               "selection, or an untouched save keeps a format the user did "
                               "replace" % FLAG_ASSIGNMENT))
            else:
                last = last_flag_write(pick)
                if last != "true":
                    checks.append((EDITOR_REL, 0, "4",
                                   "the format dropdown's onClick writes `formatChanged = %s` after "
                                   "`%s` — the last write wins, so the user's pick is discarded on "
                                   "Save exactly as if the flag were never set (witness Arm 4, order)"
                                   % (last, FLAG_ASSIGNMENT)))
        block = per_format_block(dialog)
        if block is None:
            checks.append((EDITOR_REL, 0, "4", "per-format `when (choice)` block not found — gate ROT"))
        else:
            found = handlers_in(block)
            handlers = [(off, body) for off, body in found if not dead_guarded(block, off)]
            dead = len(found) - len(handlers)
            if dead:
                checks.append((EDITOR_REL, 0, "4",
                               "%d on*Change handler(s) sit under a literal-false guard in the "
                               "per-format block — an unreachable control cannot satisfy the flag "
                               "requirement for a reachable one (witness Arm 4, parser-death)" % dead))
            if len(handlers) < PER_FORMAT_MIN_HANDLERS:
                checks.append((EDITOR_REL, 0, "4",
                               "per-format block has only %d on*Change handler(s), expected >= %d — "
                               "gate ROT or fields removed without updating the flag wiring"
                               % (len(handlers), PER_FORMAT_MIN_HANDLERS)))
            for _, h in handlers:
                if FLAG_ASSIGNMENT not in h:
                    checks.append((EDITOR_REL, 0, "4",
                                   "per-format field handler `%s…` does not set `%s`: editing a "
                                   "parameter of a preserved format would be silently discarded"
                                   % (h.strip()[:48], FLAG_ASSIGNMENT)))
                elif last_flag_write(h) != "true":
                    checks.append((EDITOR_REL, 0, "4",
                                   "per-format field handler `%s…` writes `formatChanged = %s` after "
                                   "`%s` — the user's edit is recorded and then erased in the same "
                                   "callback (last write wins)"
                                   % (h.strip()[:48], last_flag_write(h), FLAG_ASSIGNMENT)))

    # ---- Arm 5: FormatChoice.from's concrete branches stay representable ----
    from_body = declaration_text(editor, FROM_HEADER)
    rep_body = declaration_text(pure, REPRESENTABLE_HEADER)
    if from_body is None:
        checks.append((EDITOR_REL, 0, "5", "FormatChoice.from( not found — gate ROT"))
    elif rep_body is None:
        checks.append((PURE_REL, 0, "5", "isDropdownRepresentableWireFormat( not found — gate ROT"))
    else:
        from_types = set(FMT_TYPE_RE.findall(code_only(from_body)))
        rep_types = set(FMT_TYPE_RE.findall(code_only(rep_body)))
        if not from_types or not rep_types:
            checks.append((EDITOR_REL, 0, "5",
                           "could not read the format lists (from=%s, representable=%s) — gate ROT"
                           % (sorted(from_types), sorted(rep_types))))
        else:
            drift = sorted(from_types - rep_types)
            if drift:
                checks.append((EDITOR_REL, 0, "5",
                               "FormatChoice.from maps %s to a concrete choice but "
                               "isDropdownRepresentableWireFormat does not list them: the dropdown "
                               "would DISPLAY a format the save path does not preserve (or the "
                               "reverse) — the da1024 ISSUE-06 defect-2 root cause" % drift))

    return checks


# ----------------------------------------------------------------------
# Selftest fixtures — minimal Kotlin-shaped files exercising every arm.
# ----------------------------------------------------------------------
FIX_EDITOR_OK = """@Composable
fun ThinkingRuleEditorDialog(request: ThinkingRuleEditorRequest, onDismiss: () -> Unit, onSave: (ThinkingRule) -> Unit) {
    val seed = request.seed
    var choice by remember { mutableStateOf(FormatChoice.from(seed.wireFormat)) }
    var formatChanged by remember { mutableStateOf(false) }

    fun buildWireFormat(): ThinkingWireFormat = when (choice) {
        FormatChoice.OMIT -> ThinkingWireFormat.OmitEverything
        FormatChoice.REASONING_EFFORT -> ThinkingWireFormat.ReasoningEffort(null)
    }

    val preservesSeedFormat = !formatChanged && !isDropdownRepresentableWireFormat(seed.wireFormat)

    fun buildRule() = buildEditedThinkingRule(
        seed = seed,
        allModels = true,
        pattern = "",
        wireFormat = chooseEditedWireFormat(
            seedWire = seed.wireFormat,
            formatChanged = formatChanged,
            edited = buildWireFormat(),
        ),
        label = label,
    )

    Column {
        MinisTextButton(onClick = { formatMenuOpen = true }) { Text("pick") }
        DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
            FormatChoice.entries.forEach { c ->
                DropdownMenuItem(text = { Text("x") }, onClick = { choice = c; formatChanged = true; formatMenuOpen = false })
            }
        }
    }

    when (choice) {
        FormatChoice.REASONING_EFFORT -> {
            Switch(checked = sendOffValue, onCheckedChange = { sendOffValue = it; formatChanged = true })
            OutlinedTextField(value = offValue, onValueChange = { offValue = sanitizeSingleLineInput(it); formatChanged = true })
        }
        FormatChoice.BOOLEAN_TOGGLE, FormatChoice.EXTRA_BODY_TOGGLE -> {
            OutlinedTextField(value = path, onValueChange = { path = sanitizeSingleLineInput(it); formatChanged = true })
        }
        FormatChoice.CUSTOM_PATH -> {
            OutlinedTextField(value = path, onValueChange = { path = sanitizeSingleLineInput(it); formatChanged = true })
            CUSTOM_VALUE_TIERS.forEach { tier ->
                OutlinedTextField(
                    value = customValues[tier] ?: "",
                    onValueChange = {
                        customValues = customValues + (tier to sanitizeSingleLineInput(it))
                        formatChanged = true
                    },
                )
            }
        }
        else -> {}
    }

    MinisTextButton(onClick = { if (isValid) onSave(buildRule()) }) { Text("save") }
}

enum class FormatChoice(val titleRes: Int, val explanationRes: Int) {
    OMIT(1, 1),
    REASONING_EFFORT(1, 1),
    REASONING_EFFORT_NESTED(1, 1),
    BOOLEAN_TOGGLE(1, 1),
    EXTRA_BODY_TOGGLE(1, 1),
    DEEPSEEK_SIBLING(1, 1),
    QWEN_DUAL(1, 1),
    CUSTOM_PATH(1, 1);

    companion object {
        fun from(fmt: ThinkingWireFormat?): FormatChoice = when (fmt) {
            is ThinkingWireFormat.OmitEverything -> OMIT
            is ThinkingWireFormat.ReasoningEffort -> REASONING_EFFORT
            is ThinkingWireFormat.ReasoningEffortNested -> REASONING_EFFORT_NESTED
            is ThinkingWireFormat.BooleanToggle -> BOOLEAN_TOGGLE
            is ThinkingWireFormat.ExtraBodyToggle -> EXTRA_BODY_TOGGLE
            is ThinkingWireFormat.DeepSeekSibling -> DEEPSEEK_SIBLING
            is ThinkingWireFormat.QwenDual -> QWEN_DUAL
            is ThinkingWireFormat.CustomPath -> CUSTOM_PATH
            else -> REASONING_EFFORT
        }
    }
}
"""

FIX_PURE_OK = """internal fun buildEditedThinkingRule(
    seed: ThinkingRule,
    allModels: Boolean,
    pattern: String,
    wireFormat: ThinkingWireFormat?,
    label: String,
): ThinkingRule = seed.copy(
    kind = ThinkingRule.Kind.CUSTOM,
    scope = if (allModels) ThinkingRule.Scope.AllModels else ThinkingRule.Scope.ModelPattern(pattern.trim()),
    wireFormat = wireFormat,
    label = label.trim(),
)

internal fun isDropdownRepresentableWireFormat(fmt: ThinkingWireFormat?): Boolean = when (fmt) {
    is ThinkingWireFormat.OmitEverything,
    is ThinkingWireFormat.ReasoningEffort,
    is ThinkingWireFormat.ReasoningEffortNested,
    is ThinkingWireFormat.BooleanToggle,
    is ThinkingWireFormat.ExtraBodyToggle,
    is ThinkingWireFormat.DeepSeekSibling,
    is ThinkingWireFormat.QwenDual,
    is ThinkingWireFormat.CustomPath -> true
    else -> false
}

internal fun chooseEditedWireFormat(
    seedWire: ThinkingWireFormat?,
    formatChanged: Boolean,
    edited: ThinkingWireFormat,
): ThinkingWireFormat? =
    if (formatChanged || isDropdownRepresentableWireFormat(seedWire)) edited else seedWire
"""

# Arm 1 — the dialog builds a rule itself (a second construction site inside the
# dialog, which is what the pre-fix buildRule() was). The guarded route stays in
# place so this fixture trips arm 1 ONLY.
FIX_EDITOR_BARE_CTOR = FIX_EDITOR_OK.replace(
    """    Column {
        MinisTextButton(onClick = { formatMenuOpen = true }) { Text("pick") }""",
    """    val seedCopy = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = seed.scope,
        wireFormat = seed.wireFormat,
        label = seed.label,
    )

    Column {
        MinisTextButton(onClick = { formatMenuOpen = true }) { Text("pick") }""",
)

# Arm 2 — the builder goes back to the positional constructor.
FIX_PURE_BARE_CTOR = FIX_PURE_OK.replace(
    """): ThinkingRule = seed.copy(
    kind = ThinkingRule.Kind.CUSTOM,""",
    """): ThinkingRule = ThinkingRule(
    kind = ThinkingRule.Kind.CUSTOM,""",
)

# Arm 3 — the dialog stops consulting the seed's format.
FIX_EDITOR_UNGUARDED = FIX_EDITOR_OK.replace(
    """        wireFormat = chooseEditedWireFormat(
            seedWire = seed.wireFormat,
            formatChanged = formatChanged,
            edited = buildWireFormat(),
        ),""",
    """        wireFormat = buildWireFormat(),""",
)

# Arm 4a — the dropdown pick no longer records the change.
FIX_EDITOR_DROPDOWN_NO_FLAG = FIX_EDITOR_OK.replace(
    "onClick = { choice = c; formatChanged = true; formatMenuOpen = false }",
    "onClick = { choice = c; formatMenuOpen = false }",
)

# Arm 4b — one per-format field handler forgets the flag.
FIX_EDITOR_FIELD_NO_FLAG = FIX_EDITOR_OK.replace(
    "onValueChange = { offValue = sanitizeSingleLineInput(it); formatChanged = true }",
    "onValueChange = { offValue = sanitizeSingleLineInput(it) }",
)

# Arm 5 — `from` learns a type the save path does not call representable.
FIX_EDITOR_FROM_DRIFT = FIX_EDITOR_OK.replace(
    "            is ThinkingWireFormat.CustomPath -> CUSTOM_PATH",
    "            is ThinkingWireFormat.CustomPath -> CUSTOM_PATH\n"
    "            is ThinkingWireFormat.DeepSeekRelay -> REASONING_EFFORT",
)

# Arm 2 (wide3 witness) — `seed.copy(` still there, but the two fields the dialog
# cannot surface are named (re-derived / nulled) inside it.
FIX_PURE_COPY_OVERRIDE = FIX_PURE_OK.replace(
    """): ThinkingRule = seed.copy(
    kind = ThinkingRule.Kind.CUSTOM,""",
    """): ThinkingRule = seed.copy(
    id = java.util.UUID.randomUUID().toString(),
    reasoningEcho = null,
    kind = ThinkingRule.Kind.CUSTOM,""",
)

# Arm 4 (wide3 witness, order) — the dropdown records the change, then erases it.
FIX_EDITOR_DROPDOWN_FLAG_CLEARED = FIX_EDITOR_OK.replace(
    "onClick = { choice = c; formatChanged = true; formatMenuOpen = false }",
    "onClick = { choice = c; formatChanged = true; formatChanged = false; formatMenuOpen = false }",
)

# Arm 4 (wide3 witness, parser-death) — the reachable handler hides behind a
# nested `run { }` and loses the flag; an unreachable Switch keeps the count.
FIX_EDITOR_NESTED_HANDLER = FIX_EDITOR_OK.replace(
    """            OutlinedTextField(value = offValue, onValueChange = { offValue = sanitizeSingleLineInput(it); formatChanged = true })""",
    """            if (false) Switch(checked = false, onCheckedChange = { formatChanged = true })
            OutlinedTextField(value = offValue, onValueChange = { run { offValue = sanitizeSingleLineInput(it) } })""",
)

# (name, {rel-key: fixture}, expected-arm)
SELFTEST_CASES = [
    ("all-ok", {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_OK}, None),
    ("dialog-bare-ctor", {"editor": FIX_EDITOR_BARE_CTOR, "pure": FIX_PURE_OK}, "1"),
    ("builder-bare-ctor", {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_BARE_CTOR}, "2"),
    ("dialog-unguarded-format", {"editor": FIX_EDITOR_UNGUARDED, "pure": FIX_PURE_OK}, "3"),
    ("dropdown-no-flag", {"editor": FIX_EDITOR_DROPDOWN_NO_FLAG, "pure": FIX_PURE_OK}, "4"),
    ("field-handler-no-flag", {"editor": FIX_EDITOR_FIELD_NO_FLAG, "pure": FIX_PURE_OK}, "4"),
    ("from-list-drift", {"editor": FIX_EDITOR_FROM_DRIFT, "pure": FIX_PURE_OK}, "5"),
    # ── wide3 witness arms (external adversarial audit, 2026-10-25) ──
    ("copy-overrides-preserved",
     {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_COPY_OVERRIDE}, "2"),
    ("dropdown-flag-erased",
     {"editor": FIX_EDITOR_DROPDOWN_FLAG_CLEARED, "pure": FIX_PURE_OK}, "4"),
    ("nested-handler-hidden",
     {"editor": FIX_EDITOR_NESTED_HANDLER, "pure": FIX_PURE_OK}, "4"),
]


def selftest():
    rels = {"editor": "ThinkingRuleEditor.kt", "pure": "ThinkingRuleEditPreserve.kt"}
    ok = True
    for name, fixtures, expect_arm in SELFTEST_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            for k, rel in rels.items():
                path = os.path.join(tmp, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(fixtures[k])
            violations = run_checks(tmp, rels=rels)
            arms = {v[2] for v in violations}
            if expect_arm is None:
                if violations:
                    ok = False
                    print("  selftest %-22s FAIL: expected clean, got %s" % (name, violations))
                else:
                    print("  selftest %-22s PASS (clean)" % name)
            else:
                if arms == {expect_arm} and violations:
                    print("  selftest %-22s PASS (red on arm %s)" % (name, expect_arm))
                else:
                    ok = False
                    print("  selftest %-22s FAIL: expected red on arm %s, got arms %s"
                          % (name, expect_arm, sorted(arms)))
    print("THINKING_RULE_EDITOR_PRESERVE_SELFTEST=%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root_pos", nargs="?", help="repo root (positional)")
    ap.add_argument("--root", dest="root", help="repo root")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    root = args.root or args.root_pos
    if not root:
        print("usage: thinking_rule_editor_preserve_check.py [root] | --root <root> | --selftest")
        return 2
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        print("not a directory: %s" % root)
        return 2
    violations = run_checks(root)
    if violations:
        print("thinking_rule_editor_preserve_check: %d violation(s)" % len(violations))
        for rel, line, arm, msg in violations:
            print("  [%s] %s:%d %s" % (arm, rel, line, msg))
        return 1
    print("thinking_rule_editor_preserve_check: OK (5 arms, 0 violations)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
