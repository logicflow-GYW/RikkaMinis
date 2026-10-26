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
# Last write wins: a handler that sets the flag and then clears it discards the
# user's edit exactly like one that never set it. [B6-t07 A05-3] The old regex
# only matched a literal `true|false`, so `formatChanged = !true` (or
# `= (1 == 2)`, or `= someFlag`) was invisible and the previous `true` was taken
# as the last write. The right-hand side is now captured whole and folded
# through the shared constant folder.
FLAG_WRITE_RE = re.compile(r"\bformatChanged\s*=(?!=)")

# Arm 5 — type names after `is ThinkingWireFormat.` in each function body.
FMT_TYPE_RE = re.compile(r"is\s+ThinkingWireFormat\.(\w+)")


# ── shared lexer / ast / valueflow (batch-6 hard constraint #1) ────────────────
# Every structural read goes through the shared projections: `code_view()` masks
# comments AND string/char contents (length-preserving, so positions map back to
# the source), `const_bool()` folds Kotlin booleans by real precedence, and
# `path_feasible()` decides whether a position is reachable at all.
#
# Why the old hand-rolled slicer had to go (witness A05-6): `line.split("//")[0]`
# removed line comments only, so an anchor parked in `/* … */` still read as live
# code — the Save button could hand `seed.copy(wireFormat = …)` to onSave while a
# commented-out `onSave(buildRule())` satisfied arm 1.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    function_body,
    match_bracket,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    expr_end,
    literal_view,
    path_feasible,
)


class Views:
    """Both shared projections of one file (equal length → positions agree)."""

    __slots__ = ("code", "lit")

    def __init__(self, text):
        self.code = code_view(text)    # comments + string/char contents → spaces
        self.lit = literal_view(text)  # comments → spaces, string contents kept


def dialog_span(code):
    """(start, end) of the dialog: the composable up to `enum class FormatChoice(`."""
    i = code.find(DIALOG_HEADER)
    if i < 0:
        return None
    j = code.find(DIALOG_END, i)
    if j < 0:
        return None
    return (i, j)


def per_format_span(code):
    """(body_start, body_end) of the per-format `when (choice)` block.

    Identified by CODE (its branch list), not by a comment.
    """
    selected = []
    for m in re.finditer(r"when\s*\(\s*choice\s*\)\s*\{", code):
        brace = m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            continue
        if PER_FORMAT_MARKER in code[brace + 1:close]:
            selected.append((brace + 1, close))
    if len(selected) != 1:
        return None
    return selected[0]


def check_anchors(rel, body_code, arm, anchors, what):
    out = []
    for anchor in anchors:
        if anchor not in body_code:
            out.append((rel, 0, arm,
                        "%s is missing required code anchor `%s`" % (what, anchor)))
    return out


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def call_sites(text, name, start=0, end=None):
    """Offsets of every `(` of a call `name(` in [start,end) (word-bounded)."""
    end = len(text) if end is None else end
    pat = re.compile(r"(?<![\w`])%s\s*\(" % re.escape(name))
    return [m.end() - 1 for m in pat.finditer(text, start, end)]


def copy_arg_lists(code, start, end):
    """Every `seed.copy(` argument list in [start,end) PLUS every `.copy(` chained
    directly onto it: [(site_pos, [(a, b)])].

    [B6-t07 A05-1] The old reader only resolved the list whose text started with
    the literal `seed.copy(`, so a chained second `.copy(id = …, reasoningEcho =
    null)` — same field-loss family, written one link further out — was invisible
    while the runtime still wiped both fields.
    """
    out, err = [], ""
    seed_re = re.compile(r"(?<![\w`])seed\s*\.\s*copy\s*\(")
    chain_re = re.compile(r"\.\s*copy\s*\(")
    for m in seed_re.finditer(code, start, end):
        open_pos = m.end() - 1
        close = match_bracket(code, open_pos)
        if close < 0:
            return [], "the `seed.copy(` argument list could not be delimited (fail-closed)"
        out.append((m.start(), call_args(code, open_pos)))
        k = close + 1
        while True:
            while k < len(code) and code[k] in " \t\r\n":
                k += 1
            cm = chain_re.match(code, k)
            if not cm:
                break
            o2 = cm.end() - 1
            c2 = match_bracket(code, o2)
            if c2 < 0:
                return out, "a chained `.copy(` could not be delimited (fail-closed)"
            out.append((k, call_args(code, o2)))
            k = c2 + 1
    return out, err


def arg_keys(code, arglist):
    """Named-argument keys of one argument list, plus positional sites.

    [B6-t07 A05-1] `named_arg_keys` skipped `if '=' not in a`, so a POSITIONAL
    `seed.copy(uuid(), …)` overwriting `id` read as an empty key set. Positional
    arguments to `copy(` cannot be proven not to hit the preserved fields, so
    they are reported by position.
    """
    keys, positional = [], []
    for idx, (a, b) in enumerate(arglist):
        arg = code[a:b].strip()
        m = re.match(r"([A-Za-z_]\w*)\s*=(?!=)", arg)
        if m:
            keys.append(m.group(1))
        else:
            positional.append((idx, arg))
    return keys, positional


def handlers_in(code, start, end):
    """(handlers, broken) — every on*Change lambda in [start,end).

    handlers: [(head_offset, body_start, body_end)]; broken: offsets whose braces
    could not be matched (fail-closed at the caller).
    """
    handlers, broken = [], []
    for m in HANDLER_HEAD_RE.finditer(code, start, end):
        brace = m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            broken.append(m.start())
            continue
        handlers.append((m.start(), brace + 1, close))
    return handlers, broken


def flag_writes(code, start, end):
    """[(pos, rhs_text)] of every assignment to `formatChanged` in [start,end)."""
    out = []
    for m in FLAG_WRITE_RE.finditer(code, start, end):
        k = m.end()
        stop = k
        while stop < end and code[stop] not in "\n;}":
            stop += 1
        out.append((m.start(), code[k:stop].strip()))
    return out


def last_live_flag_write(code, start, end):
    """The LAST REACHABLE `formatChanged = …` write in [start,end).

    [B6-t07 A05-2] A write parked inside `if (false) …` never executes, so it
    must not count as the control recording the user's change. Reachability is
    decided by the shared `path_feasible` (constant-folding, not a literal
    whitelist), which also covers the newline-spelled dead guard the old
    line-prefix regex missed.
    """
    live = [(pos, rhs) for pos, rhs in flag_writes(code, start, end)
            if path_feasible(code, pos)]
    return live[-1] if live else (None, None)


def flag_value(rhs):
    """True / False / None (not provable) for a flag write's right-hand side."""
    if not rhs:
        return None
    return const_bool(rhs)


def _is_call_to(expr, name):
    """True when [expr] IS exactly one call to `name(…)` (nothing before/after)."""
    e = expr.strip()
    m = re.match(r"(?:[A-Za-z_]\w*\.)*%s\s*\(" % re.escape(name), e)
    if not m:
        return False
    close = match_bracket(e, m.end() - 1)
    return close == len(e) - 1


def _bare_returns(body):
    """[(pos, returned_expr_text)] for every `return <expr>` in [body]."""
    out = []
    for m in re.finditer(r"\breturn\b", body):
        start = m.end()
        end = expr_end(body, start)
        out.append((m.start(), body[start:end].strip()))
    return out


def check_save_binding(code, dspan):
    """[A05-4 / A05-6] Save must persist what `buildRule()` RETURNS.

    Two halves, both derived from the code:
      * the button's `onSave(<arg>)` argument is the dialog's own `buildRule()` —
        a `seed.copy(...)`/constructor there bypasses the whole guarded path;
      * `buildRule()` returns the `buildEditedThinkingRule(...)` call — binding
        its value to a local and returning the raw seed means the guarded
        derivation is computed and thrown away.
    """
    out = []
    start, end = dspan
    save_sites = call_sites(code, "onSave", start, end)
    if not save_sites:
        out.append((EDITOR_REL, 0, "1",
                    "the dialog never calls onSave( — gate ROT (the Save button "
                    "disappeared)"))
    for open_pos in save_sites:
        arglist = call_args(code, open_pos)
        if len(arglist) != 1:
            out.append((EDITOR_REL, 0, "1",
                        "onSave( does not take exactly one argument — fail-closed"))
            continue
        a, b = arglist[0]
        arg = code[a:b].strip()
        if not re.fullmatch(r"buildRule\s*\(\s*\)", arg):
            out.append((EDITOR_REL, 0, "1",
                        "the Save button hands `%s` to onSave( instead of `buildRule()` — "
                        "the guarded derivation (buildEditedThinkingRule → "
                        "chooseEditedWireFormat) never reaches the wire, so the seed's id / "
                        "reasoningEcho and an unrepeatable format are rewritten again "
                        "(witness A05-6)" % arg[:60]))

    span = function_body(code, "buildRule")
    if span is None:
        out.append((EDITOR_REL, 0, "1", "buildRule() could not be delimited — gate ROT"))
        return out
    bstart, bend, kind = span
    body = code[bstart:bend]
    if kind == "expr":
        val = body.strip()
        if not _is_call_to(val, "buildEditedThinkingRule"):
            out.append((EDITOR_REL, 0, "1",
                        "buildRule() returns `%s` — Save must persist the value "
                        "`buildEditedThinkingRule(...)` RETURNS; a different object here is "
                        "the pre-fix shape with extra steps (witness A05-4)" % val[:60]))
    else:
        rets = _bare_returns(body)
        if not rets:
            out.append((EDITOR_REL, 0, "1",
                        "buildRule() has no `return` — fail-closed"))
        for _, text in rets:
            if not _is_call_to(text, "buildEditedThinkingRule"):
                out.append((EDITOR_REL, 0, "1",
                            "buildRule() returns `%s` — the guarded builder's value is "
                            "computed and DISCARDED, and the raw seed goes to onSave instead "
                            "(witness A05-4)" % text[:60]))
    return out


def check_copy_chain(code, start, end):
    """[A05-1] `id` / `reasoningEcho` must survive the WHOLE `seed.copy()` chain.

    `copy` only preserves what it does NOT name, so every link of the chain counts:
    the first `seed.copy(kind/scope/wireFormat/label)` survives when a chained
    `.copy(id = …, reasoningEcho = null)` is appended. Positional arguments are
    reported by position — `copy(uuid(), …)` cannot be proven not to hit `id`.
    """
    out = []
    lists, err = copy_arg_lists(code, start, end)
    if err:
        return [(PURE_REL, 0, "2",
                 "%s — the preserved-fields check cannot run" % err)]
    if not lists:
        return [(PURE_REL, 0, "2",
                 "could not read the `seed.copy(` argument list — the preserved-fields "
                 "check cannot run (fail-closed)")]
    overridden, positional = [], []
    for _pos, arglist in lists:
        keys, positional_here = arg_keys(code, arglist)
        overridden += [k for k in keys if k in COPY_FORBIDDEN_FIELDS]
        positional += positional_here
    if overridden:
        out.append((PURE_REL, 0, "2",
                    "buildEditedThinkingRule overrides %s inside the `seed.copy(…)` chain — "
                    "those are exactly the fields this dialog does not surface, so naming them "
                    "(even to pass a re-derived value) re-opens da1024 ISSUE-06 defect 1: the "
                    "vendor history-echo contract and the rule identity die on Save "
                    "(witness A05-1)" % sorted(set(overridden))))
    if positional:
        out.append((PURE_REL, 0, "2",
                    "the `seed.copy(…)` chain passes POSITIONAL argument(s) %s — a positional "
                    "argument cannot be proven not to overwrite `reasoningEcho` / `id`, which "
                    "are precisely the preserved fields (witness A05-1 self-note)"
                    % [idx for idx, _ in positional]))
    return out


def check_format_controls(code, dspan):
    """[A05-2 / A05-3] Every format control must RECORD the change, provably.

    The flag is read at Save time, so what matters is the last *reachable* write:
    a write inside `if (false) …` never runs (A05-2), and a write whose value is
    `!true` / `(1 == 2)` erases the one before it (A05-3). Both are decided by the
    shared constant folder / reachability instead of a literal whitelist.
    """
    out = []
    start, end = dspan
    dropdown_at = code.find("DropdownMenu(", start, end)
    if dropdown_at < 0:
        return [(EDITOR_REL, 0, "4", "DropdownMenu( not found in the dialog — gate ROT")]
    pick_re = re.compile(r"onClick\s*=\s*\{")
    pick = pick_re.search(code, dropdown_at, end)
    if pick is None:
        out.append((EDITOR_REL, 0, "4", "no dropdown onClick block found — gate ROT"))
    else:
        brace = pick.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            out.append((EDITOR_REL, 0, "4",
                        "the dropdown onClick block could not be delimited — fail-closed"))
        else:
            if not re.search(r"\bchoice\s*=(?!=)", code[brace + 1:close]):
                out.append((EDITOR_REL, 0, "4",
                            "the format dropdown's onClick does not assign `choice` — gate ROT "
                            "or the pick was rewired"))
            pos, rhs = last_live_flag_write(code, brace + 1, close)
            if pos is None:
                out.append((EDITOR_REL, 0, "4",
                            "the format dropdown's onClick never sets `%s` on a reachable path, "
                            "or an untouched save keeps a format the user did replace"
                            % FLAG_ASSIGNMENT))
            elif flag_value(rhs) is not True:
                out.append((EDITOR_REL, 0, "4",
                            "the format dropdown's onClick's last write is `formatChanged = %s` "
                            "(folded: %s) — the last write wins, so the user's pick is discarded "
                            "on Save exactly as if the flag were never set (witness A05-3)"
                            % (rhs[:40], flag_value(rhs))))

    span = per_format_span(code)
    if span is None:
        out.append((EDITOR_REL, 0, "4",
                    "per-format `when (choice)` block not found — gate ROT"))
        return out
    handlers, broken = handlers_in(code, span[0], span[1])
    for off in broken:
        out.append((EDITOR_REL, 0, "4",
                    "an on*Change handler at offset %d could not be delimited — fail-closed"
                    % off))
    live, dead = [], 0
    for off, bstart, bend in handlers:
        if not path_feasible(code, off):
            dead += 1
            continue
        live.append((off, bstart, bend))
    if dead:
        out.append((EDITOR_REL, 0, "4",
                    "%d on*Change handler(s) sit in an UNREACHABLE branch of the per-format "
                    "block — an unreachable control cannot satisfy the flag requirement for a "
                    "reachable one (witness A05-2)" % dead))
    if len(live) < PER_FORMAT_MIN_HANDLERS:
        out.append((EDITOR_REL, 0, "4",
                    "per-format block has only %d reachable on*Change handler(s), expected >= "
                    "%d — gate ROT or fields removed without updating the flag wiring"
                    % (len(live), PER_FORMAT_MIN_HANDLERS)))
    for _off, bstart, bend in live:
        pos, rhs = last_live_flag_write(code, bstart, bend)
        if pos is None:
            out.append((EDITOR_REL, 0, "4",
                        "per-format field handler `%s…` never sets `%s` on a reachable path: "
                        "editing a parameter of a preserved format would be silently discarded"
                        % (code[bstart:bend].strip()[:48], FLAG_ASSIGNMENT)))
        elif flag_value(rhs) is not True:
            out.append((EDITOR_REL, 0, "4",
                        "per-format field handler `%s…`'s last write is `formatChanged = %s` "
                        "(folded: %s) — the user's edit is recorded and then erased in the same "
                        "callback (last write wins)"
                        % (code[bstart:bend].strip()[:48], rhs[:32], flag_value(rhs))))
    return out


def check_format_lists(editor, pure):
    """[A05-5] `FormatChoice.from` and the representable list must agree BOTH ways.

    The old check only computed `from_types - rep_types`. The defect's own trigger
    is the other direction: adding a type to `isDropdownRepresentableWireFormat`
    that `from` cannot actually reach makes `chooseEditedWireFormat` believe the
    dropdown reproduces it, so an untouched save rewrites DeepSeekRelay /
    QwenRootOnly into `else -> REASONING_EFFORT`. The violation text already said
    "(or the reverse)" — the code did not.
    """
    out = []
    from_span = function_body(editor, "from")
    rep_span = function_body(pure, "isDropdownRepresentableWireFormat")
    if from_span is None:
        return [(EDITOR_REL, 0, "5", "FormatChoice.from( not found — gate ROT")]
    if rep_span is None:
        return [(PURE_REL, 0, "5", "isDropdownRepresentableWireFormat( not found — gate ROT")]
    from_types = set(FMT_TYPE_RE.findall(editor[from_span[0]:from_span[1]]))
    rep_types = set(FMT_TYPE_RE.findall(pure[rep_span[0]:rep_span[1]]))
    if not from_types or not rep_types:
        return [(EDITOR_REL, 0, "5",
                 "could not read the format lists (from=%s, representable=%s) — gate ROT"
                 % (sorted(from_types), sorted(rep_types)))]
    both_ways = sorted(from_types ^ rep_types)
    if both_ways:
        only_rep = sorted(rep_types - from_types)
        only_from = sorted(from_types - rep_types)
        out.append((EDITOR_REL, 0, "5",
                    "FormatChoice.from and isDropdownRepresentableWireFormat disagree on %s "
                    "(only in `from`: %s, only in the representable list: %s) — a format the "
                    "dropdown claims to represent but cannot build is rewritten into `else -> "
                    "REASONING_EFFORT` by an untouched save, and the reverse makes a "
                    "representable format un-saveable (witness A05-5)" % (both_ways, only_from,
                                                                          only_rep)))
    return out


def run_checks(root, rels=None):
    rels = rels or {"editor": EDITOR_REL, "pure": PURE_REL}
    srcs = {k: read(rel, root) for k, rel in rels.items()}
    missing = [(rel, 0, "?", "file not found — gate ROT")
               for k, rel in rels.items() if srcs[k] is None]
    if missing:
        return missing
    try:
        v = {k: Views(s) for k, s in srcs.items()}
    except KotlinLexError as exc:
        return [(rels["editor"], 0, "?", "the editor sources cannot be lexed (%s) — "
                 "fail-closed, no arm can be judged" % exc)]
    editor = v["editor"].code
    pure = v["pure"].code
    checks = []

    # ---- Arm 1: the dialog routes Save through the pure builder, and Save
    #      persists what `buildRule()` really returns ----
    dspan = dialog_span(editor)
    if dspan is None:
        checks.append((EDITOR_REL, 0, "1",
                       "ThinkingRuleEditorDialog region not found — gate ROT"))
    else:
        dialog = editor[dspan[0]:dspan[1]]
        checks += check_anchors(EDITOR_REL, dialog, "1", ARM1_ANCHORS,
                                "ThinkingRuleEditorDialog")
        if BARE_CTOR_RE.search(dialog):
            checks.append((EDITOR_REL, 0, "1",
                           "ThinkingRuleEditorDialog constructs `ThinkingRule(` itself — the "
                           "positional constructor drops reasoningEcho/id (da1024 ISSUE-06 "
                           "defect 1); route it through buildEditedThinkingRule( instead"))
        checks += check_save_binding(editor, dspan)

    # ---- Arm 2: the builder derives from seed.copy(...) ----
    bspan = function_body(pure, "buildEditedThinkingRule")
    if bspan is None:
        checks.append((PURE_REL, 0, "2", "buildEditedThinkingRule( not found — gate ROT"))
    else:
        builder = pure[bspan[0]:bspan[1]]
        checks += check_anchors(PURE_REL, builder, "2", ARM2_ANCHORS,
                                "buildEditedThinkingRule")
        if BARE_CTOR_RE.search(builder):
            checks.append((PURE_REL, 0, "2",
                           "buildEditedThinkingRule builds `ThinkingRule(` instead of "
                           "`seed.copy(` — every field the dialog does not surface is wiped"))
        # `seed.copy(` only preserves what it does NOT name — every link of the
        # chain counts (A05-1).
        checks += check_copy_chain(pure, bspan[0], bspan[1])

    # ---- Arm 3: the format survives unless the user replaced it ----
    cspan = function_body(pure, "chooseEditedWireFormat")
    if cspan is None:
        checks.append((PURE_REL, 0, "3", "chooseEditedWireFormat( not found — gate ROT"))
    elif ARM3_CHOOSER_ANCHOR not in pure[cspan[0]:cspan[1]]:
        checks.append((PURE_REL, 0, "3",
                       "chooseEditedWireFormat must keep `%s`" % ARM3_CHOOSER_ANCHOR))
    if dspan is not None:
        checks += check_anchors(EDITOR_REL, editor[dspan[0]:dspan[1]], "3",
                                ARM3_DIALOG_ANCHORS, "ThinkingRuleEditorDialog")

    # ---- Arm 4: every format control records that the user changed it ----
    if dspan is not None:
        checks += check_format_controls(editor, dspan)

    # ---- Arm 5: the two format lists agree in BOTH directions ----
    checks += check_format_lists(editor, pure)

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

# ── batch-6 witnesses (external adversarial audit, 2026-10-26) ──
# A05-1: the first `seed.copy(` stays intact and a chained `.copy(...)` wipes the
# two fields the dialog cannot surface.
FIX_PURE_CHAINED_COPY = FIX_PURE_OK.replace(
    """    label = label.trim(),
)""",
    """    label = label.trim(),
).copy(id = java.util.UUID.randomUUID().toString(), reasoningEcho = null)""",
)

# A05-1 self-note: the same wipe written POSITIONALLY — `named_arg_keys` used to
# skip any argument without `=`, so this read as an empty key set.
FIX_PURE_POSITIONAL_COPY = FIX_PURE_OK.replace(
    """    label = label.trim(),
)""",
    """    label = label.trim(),
).copy(java.util.UUID.randomUUID().toString(), ThinkingRule.Scope.AllModels, wireFormat)""",
)

# A05-2: the control still writes the flag, inside a literal-false guard, so the
# write is dead but `FLAG_ASSIGNMENT in handler` is satisfied.
FIX_EDITOR_DEAD_FLAG_IN_HANDLER = FIX_EDITOR_OK.replace(
    "onCheckedChange = { sendOffValue = it; formatChanged = true }",
    "onCheckedChange = { sendOffValue = it; if (false) formatChanged = true }",
)

# A05-3: the flag is recorded and then erased by a non-literal boolean the old
# `(true|false)` regex could not see.
FIX_EDITOR_FLAG_NEGATED = FIX_EDITOR_OK.replace(
    "onClick = { choice = c; formatChanged = true; formatMenuOpen = false }",
    "onClick = { choice = c; formatChanged = true; formatChanged = !true; formatMenuOpen = false }",
)

# A05-4: every anchor survives, the builder's value is bound to `_unused`, and
# the dialog returns the raw seed (format re-derived without the chooser).
FIX_EDITOR_BUILD_RULE_BYPASS = FIX_EDITOR_OK.replace(
    """    fun buildRule() = buildEditedThinkingRule(
        seed = seed,
        allModels = true,
        pattern = "",
        wireFormat = chooseEditedWireFormat(
            seedWire = seed.wireFormat,
            formatChanged = formatChanged,
            edited = buildWireFormat(),
        ),
        label = label,
    )""",
    """    fun buildRule(): ThinkingRule {
        val _unused = buildEditedThinkingRule(
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
        return seed.copy(wireFormat = buildWireFormat())
    }""",
)

# A05-5: the representable list grows a type `from` cannot reach — the opposite
# direction of the drift the old one-way set difference measured.
FIX_PURE_REP_DRIFT = FIX_PURE_OK.replace(
    """    is ThinkingWireFormat.QwenDual,
    is ThinkingWireFormat.CustomPath -> true""",
    """    is ThinkingWireFormat.QwenDual,
    is ThinkingWireFormat.DeepSeekRelay,
    is ThinkingWireFormat.QwenRootOnly,
    is ThinkingWireFormat.CustomPath -> true""",
)

# A05-6: Save bypasses buildRule(); the real call survives only inside a block
# comment, which the old `//`-only stripper could not remove.
FIX_EDITOR_SAVE_BYPASS = FIX_EDITOR_OK.replace(
    "onClick = { if (isValid) onSave(buildRule()) }",
    "onClick = { if (isValid) onSave(seed.copy(wireFormat = buildWireFormat())) /* onSave(buildRule()) */ }",
)

# (name, {rel-key: fixture}, expected-arm)
SELFTEST_CASES = [    ("all-ok", {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_OK}, None),
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
    # ── batch-6 witness arms (external adversarial audit, 2026-10-26) ──
    ("copy-chain-wipes-fields",
     {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_CHAINED_COPY}, "2"),
    ("copy-positional-wipes-id",
     {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_POSITIONAL_COPY}, "2"),
    ("dead-guard-inside-handler",
     {"editor": FIX_EDITOR_DEAD_FLAG_IN_HANDLER, "pure": FIX_PURE_OK}, "4"),
    ("flag-erased-by-negation",
     {"editor": FIX_EDITOR_FLAG_NEGATED, "pure": FIX_PURE_OK}, "4"),
    ("build-rule-return-bypass",
     {"editor": FIX_EDITOR_BUILD_RULE_BYPASS, "pure": FIX_PURE_OK}, "1"),
    ("representable-list-drift-reverse",
     {"editor": FIX_EDITOR_OK, "pure": FIX_PURE_REP_DRIFT}, "5"),
    ("save-bypasses-build-rule-in-block-comment",
     {"editor": FIX_EDITOR_SAVE_BYPASS, "pure": FIX_PURE_OK}, "1"),
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
