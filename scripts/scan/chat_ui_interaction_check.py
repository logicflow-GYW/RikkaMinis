#!/usr/bin/env python3
"""
Gate: FIX-4 (deep-audit-1037, dv16 wave) — chat UI interaction wiring. Three
defect families, one gate (plus the dv19-B-01 family, E):

B-01  SlashCommand.isMcp was written but never read: an MCP row tap fell into
      executeSlashCommand's action dispatch, whose unrecognized-id arm returns
      "" — WIPING the composer instead of filling it. ChatInputArea also gated
      the installed-row divider + IME restore on a bare isSkill test.
B-02  selectionTableActions was wired into MinisMarkdownTextToolbar only — the
      main-chat MinisSelectionToolbarHost (the long-press toolbar chat users
      actually see) never surfaced Copy Table / Copy Table Image. The table
      registry was keyed by the bare messageId: same-message tables overwrote
      each other and a table leaving composition dropped its sibling's entry.
B-06  The swipe-up release branch re-derived eligibility inside an already
      hasText-guarded block (a dead branch) and read the debounced VM string,
      so attachment-only drags went to the keyboard and a just-typed prefix
      could be lost on release. One shared predicate read at call time now
      drives all three decision sites, and the threshold reads through
      rememberUpdatedState so ChatTuning adjustments reach the gesture.

Checks run on comment-stripped, string-blanked text, so prose in a KDoc or a
log literal cannot satisfy them:

  ChatModels.kt
    A1  every boolean field of the SlashCommand data class has >= 1 read point
        (`.field` in a condition across ChatSlashTokenExt + ChatInputArea).
        Fail-closed: a written-but-never-read boolean = the B-01 class again.
  ChatSlashTokenExt.kt
    A2  executeSlashCommand's body contains an `if (cmd.isMcp)` branch routed
        through the shared fill helper.
  ChatInputArea.kt
    A3  isMcp is read in a condition (divider / IME restore), not just defined.
  MinisTextKitGesture.kt
    B1  the file calls selectionTableActions() (the main-chat wiring).
  MinisMarkdownTextToolbar.kt
    B2  the file still calls selectionTableActions() — that path is a
        legitimate consumer; deleting it is a regression, not a cleanup.
  MinisTextKitSelection.kt
    B3  the table registry carries a table-level key: rememberTableActions /
        forgetTableActions each take an Int table dimension beyond messageId,
        and the map declaration is two-level.
    B4  selectionTableActions still guards on singleMessageId() (cross-message
        selections expose no table actions).
  StreamingMarkdownText.kt
    B5  the publisher passes the table index (3-arg rememberTableActions call)
        and sources it from the shard allocator.
  ChatInputArea.kt (swipe block, delimited by the pointerInput lambda)
    C1  exactly one shared send-eligibility predicate (`: () -> Boolean = {`)
        whose body reads the field's own TextFieldValue (never the debounced
        VM string) + the attachment list.
    C2  the predicate is used >= 3 times (the three decision sites).
    C3  the nested dead-branch shape is banned: `if (X && Y)` followed within
        4 lines by `val _ = Y || ...` — an eligibility re-derivation inside a
        branch that already implies it. Detected on the SHAPE (variable names
        are captured from the enclosing condition), not on names.
    C4  the swipe threshold reads through rememberUpdatedState (a plain val
        capture freezes the first composition's px forever), and the gesture
        reads its .value.
    C5  performSendOrEnqueue() still reached from the gesture (streaming
        enqueue preserved).
    C6  the empty-input keyboard shortcut (!inputFocused) survived.

  ChatInputArea.kt (slash menu, dv17-4 / dv22-B4 — the D family)
    D-memo  fail-closed anchors: the `val slashMenuPayload` holder + a
            produceState/LaunchedEffect producer whose body calls
            `viewModel.filteredSlashCommands()`.
    D1  the producer must not key on `thinkingLevel` (0 reads in the body).
    D2  the producer keys carry the session scope + both repository lists.
    D3  every `viewModel.filteredSlashCommands()` call sits INSIDE the
            producer (a composition-synchronous compute pays the query
            burst on Main) and inside a showSlashMenu-gated region.
    D-IO the producer wraps the call in `withContext(Dispatchers.IO)` —
            produceState's producer inherits the composition context
            (Main); without the hop the async shape is cosmetic.
    D4  the producer keys carry BOTH repositories'
            sessionOverridesRevision (the override table is not mirrored
            by .skills/.servers — without the channel an open menu goes
            stale after a session toggle, B-04).
  SkillRepository.kt + MCPRepository.kt (OPTIONAL sibling reads)
    D5  every session-override mutator (setSessionOverride /
            clearSessionOverrides / renameSessionOverrides) bumps the
            published sessionOverridesRevision — a write with no
            invalidation is the B-04 staleness itself.
  ChatViewModelSlashExt.kt (OPTIONAL sibling read)
    D6  filteredSlashCommands()'s body has no per-item
            isEnabledForSession( call — the batched reader is the only
            shape that keeps the query count constant.

  ChatInputArea.kt (composer decisions, dv19 wave B-01 — the E family)
    E1  the content flag of `val <showStop> = isStreaming && !<flag>` must,
        transitively over `val <name> = …` (<= 4 hops), reach the field's live
        value (or the shared C1 predicate) and must never reach the debounced
        VM string.
    E2  the three sites that act on / leave the composer (slash dismiss, slash
        command, cross-session transfer) must pass the field's live value. All
        three must exist: a missing site is fail-closed, since a decision that
        moved out of the gate's view must not read as "clean".
    E3  each of those three sites must drop the pending IME-burst flush before
        it acts on the composer — inline, or through a shared `() -> Unit`
        helper whose body cancels imeBurstJob (both shapes are legal: the
        decision is "cancelled before the write", not the spelling).

Boundary (documented, not a bug): C4 pins the rememberUpdatedState decision —
a refactor that swaps it for a state-holder class must rename the criterion.
C3's 4-line window is the static approximation of "inside the guarded block";
a dead branch more than 4 lines below its guard needs the window widened.
E1 follows identifiers through `val <name> = …` initialisers, so it requires
that flag (and the values it reads) to stay one-line `val`s — a multi-line
initialiser reads as "not reached" (fail-closed, never a silent pass).
E-family scope: the four decisions named in deep-audit-1039 B-01. A NEW
decision that reads the debounced string needs its own site criterion here —
the family does not blanket-ban `inputText` (the debounce machinery and the
field mirroring legitimately read it).
D-family boundaries (dv22-B4): the producer's argument list is split at the
first top-level `{` — an arg-list lambda (a shape the producer does not use)
would read as unbalanced and fail closed. D3's guard window is 1600 chars (the
async producer sits between the guard and the call). The three files behind
D5/D6 are OPTIONAL reads — sibling fixtures (test_scan's minimal trees) do
not ship them; their absence skips D5/D6 rather than failing, and when they
ARE present the criteria are fail-closed like every other.

Usage: python3 scripts/scan/chat_ui_interaction_check.py [repo_root]
       python3 scripts/scan/chat_ui_interaction_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import shutil
import sys
import tempfile

CHAT = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat"
MODELS_REL = CHAT + "/ChatModels.kt"
TOKEN_REL = CHAT + "/ChatSlashTokenExt.kt"
INPUT_REL = CHAT + "/ChatInputArea.kt"
# [dv23-B3] ChatScreen is an OPTIONAL read: the minimal fixture trees in
# test_scan.py never ship it, exactly like the D5/D6 sibling sources.
SCREEN_REL = CHAT + "/ChatScreen.kt"
# [dv24/B-05] the ViewModel half of the composer handoff: the two entrances that
# used to resolve the composer text themselves. OPTIONAL read for the same
# reason as ChatScreen.kt.
VM_REL = CHAT + "/ChatViewModel.kt"
GESTURE_REL = CHAT + "/MinisTextKitGesture.kt"
SELECTION_REL = CHAT + "/MinisTextKitSelection.kt"
STREAM_REL = CHAT + "/StreamingMarkdownText.kt"
MDTOOLBAR_REL = CHAT + "/MinisMarkdownTextToolbar.kt"

FILL_HELPER = "fillComposerWithSlashTitle("
PRED_RE = re.compile(r"val (\w+):\s*\(\)\s*->\s*Boolean\s*=\s*\{")
IF_AND_RE = re.compile(r"if\s*\((\w+)\s*&&\s*(\w+)")


# ── scanners (same provenance as the other gates: comment/string aware) ──────

def strip_comments(text):
    """Blank out comment content, keeping everything else, with a scanner that
    understands Kotlin string templates (a `//` inside a URL literal or a
    quote nested inside a `${…}` desyncs any naive scanner)."""
    out = []
    stack = [["code", 0]]
    i = 0
    n = len(text)
    while i < n:
        st = stack[-1]
        if st[0] == "code":
            if text.startswith('"""', i):
                stack.append(["raw", 0]); i += 3; continue
            c = text[i]
            if c == '"':
                stack.append(["str", 0]); out.append(c); i += 1; continue
            if text.startswith("//", i):
                while i < n and text[i] != "\n":
                    i += 1
                continue
            if text.startswith("/*", i):
                end = text.find("*/", i + 2)
                i = n if end < 0 else end + 2
                continue
            if c == "{":
                st[1] += 1
            elif c == "}":
                if st[1] == 0 and len(stack) > 1:
                    stack.pop(); out.append(c); i += 1; continue
                st[1] -= 1
            out.append(c); i += 1; continue
        if st[0] == "str":
            c = text[i]
            if c == "\\":
                out.append(c)
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2; continue
            if text.startswith("${", i):
                stack.append(["code", 0]); out.append("${"); i += 2; continue
            if c == '"':
                stack.pop(); out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if text.startswith('"""', i):
            stack.pop(); out.append('"""'); i += 3; continue
        out.append(text[i]); i += 1
    return "".join(out)


def strip_strings(code):
    """Blank the contents of string literals in comment-free code, so a whole-
    file call-site scan is never driven by a log literal."""
    out = []
    stack = [["code", 0]]
    i = 0
    n = len(code)
    while i < n:
        st = stack[-1]
        if st[0] == "code":
            if code.startswith('"""', i):
                stack.append(["raw", 0]); out.append("   "); i += 3; continue
            c = code[i]
            if c == '"':
                stack.append(["str", 0]); out.append(c); i += 1; continue
            if len(stack) > 1 and c == "{":
                st[1] += 1
            elif len(stack) > 1 and c == "}":
                if st[1] == 0:
                    stack.pop(); out.append(c); i += 1; continue
                st[1] -= 1
            out.append(c); i += 1; continue
        if st[0] == "str":
            c = code[i]
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if code.startswith("${", i):
                stack.append(["code", 0]); out.append("${"); i += 2; continue
            if c == '"':
                stack.pop(); out.append(c); i += 1; continue
            out.append(c if c == "\n" else " "); i += 1; continue
        if code.startswith('"""', i):
            stack.pop(); out.append('"""'); i += 3; continue
        out.append(code[i] if code[i] == "\n" else " "); i += 1
    return "".join(out)


def declaration_slice(text, marker):
    """Return the `{ ... }` body of the declaration containing `marker`
    (template/string aware). Returns "" when absent."""
    idx = text.find(marker)
    if idx == -1:
        return ""
    start = text.find("{", idx)
    if start == -1:
        return ""
    depth = 0
    tdepth = 0
    tstack = []
    in_str = False
    in_raw = False
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False; i += 3; continue
            i += 1; continue
        if in_str:
            if c == "\\":
                i += 2; continue
            if text.startswith("${", i):
                tstack.append(tdepth); tdepth = 0; in_str = False; i += 2; continue
            if c == '"':
                in_str = False; i += 1; continue
            i += 1; continue
        if text.startswith('"""', i):
            in_raw = True; i += 3; continue
        if c == '"':
            in_str = True; i += 1; continue
        if c == "{":
            if tstack:
                tdepth += 1
            else:
                depth += 1
        elif c == "}":
            if tstack:
                if tdepth == 0:
                    tdepth = tstack.pop(); in_str = True
                else:
                    tdepth -= 1
            else:
                depth -= 1
                if depth == 0:
                    return text[start:i + 1]
        i += 1
    return text[start:]


def signature_args(text, marker):
    """Return the argument list of the `marker(...)` call/declaration nearest
    the marker (no nested parens in the targets)."""
    idx = text.find(marker)
    if idx == -1:
        return None
    op = text.find("(", idx)
    cl = text.find(")", op)
    if op == -1 or cl == -1:
        return None
    return text[op + 1:cl]


def signature_args_balanced(text, marker):
    """Argument list of `marker(...)`, paren-MATCHED, None when unbalanced.

    `signature_args` stops at the first `)` — which is fine while every
    parameter is a bare `val x: T`, and catastrophic the moment one carries a
    call-shaped default (`val tag: String = String()`): everything after it
    falls out of the scanned slice. External witness A1 declared
    `val tag: String = String()` after `isSkill` and appended an unread
    `isUnreadFlag` boolean — `isSkill` kept the field list non-empty, so the
    fail-closed check did not fire and the new field was never judged.
    """
    idx = text.find(marker)
    if idx == -1:
        return None
    op = text.find("(", idx)
    if op == -1:
        return None
    cl = _balanced_paren(text, op)
    if cl is None:
        return None
    return text[op + 1:cl]


def read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()

# ── D: slash-menu load wiring (dv17-4 / dv22-B4) ──────────────────────────────
#
# History: dv17-4 (deep-audit-1038 B-05) gated the compute on showSlashMenu and
# pinned the session + repository-list keys. dv22-B4 (deep-audit-1042 B-04)
# found two residues the sync shape could not fix, so this section now binds
# the DECISIONS:
#   D1  the producer must not key on `thinkingLevel` (0 reads in the body);
#   D2  the producer keys must carry the session scope
#       (currentSessionId/activeSessionId) + the two repository lists the
#       per-session filter walks (`.skills` / `.servers`);
#   D3  the menu DATA must be loaded asynchronously: `viewModel.filteredSlash
#       Commands()` has to sit INSIDE the produceState/LaunchedEffect producer
#       and inside a showSlashMenu-gated region. A composition-synchronous
#       compute (the old `remember { … }` shape) runs the query burst on Main
#       on every keystroke with the menu open — exactly what dv17-4's gate
#       used to pin as the FIXED shape, so the gate had to move with the code
#       (gate + code changed together, dv22-B4);
#   D-IO the producer must cross to `withContext(Dispatchers.IO)` BEFORE the
#       call — produceState's producer inherits the composition context
#       (Main), so an unwrapped producer is the same main-thread burst with
#       extra steps (1041-B3);
#   D4  the producer keys must carry BOTH repositories' sessionOverrides
#       Revision — the session-override table is not mirrored by `.skills` /
#       `.servers`, so without this channel an open menu stays stale after a
#       toggle in SessionSkillsSheet / SessionMcpsSheet (B-04);
#   D5  every override-table mutator (setSessionOverride /
#       clearSessionOverrides / renameSessionOverrides) must bump the
#       published `sessionOverridesRevision` — a write with no invalidation
#       is the B-04 staleness itself;
#   D6  the slash ext's `filteredSlashCommands()` body must not call the
#       PER-ITEM `isEnabledForSession(` — one query per row is N+M queries
#       per producer run; the batched `sessionEnablementMap` keeps the count
#       constant.
#
# The three files behind D5/D6 are OPTIONAL reads: sibling fixtures
# (test_scan.py's minimal trees) do not ship them, and their absence must not
# fail-closed the A/B/C/E criteria that live in the chat sources. When the
# files ARE present the D5/D6 criteria are fail-closed like every other.

SLASH_PAYLOAD_DECL = "val slashMenuPayload"
SLASH_MEMO_CALL = "viewModel.filteredSlashCommands()"
PRODUCER_RE = re.compile(r"\b(produceState|LaunchedEffect)\s*\(")
IO_HOP = "withContext(Dispatchers.IO)"
SLASH_EXT_REL = CHAT + "/ChatViewModelSlashExt.kt"
SKILL_REPO_REL = ("src/android/app/src/main/java/com/rikkaminis/app/data/"
                  "repository/SkillRepository.kt")
MCP_REPO_REL = ("src/android/app/src/main/java/com/rikkaminis/app/data/"
                "repository/MCPRepository.kt")
SESSION_MUTATORS = ("fun setSessionOverride(", "fun clearSessionOverrides(",
                    "fun renameSessionOverrides(")
# A guard is a CONDITION on showSlashMenu — `if (…showSlashMenu…)`, a negated
# `!showSlashMenu`, or a conjunction `showSlashMenu && …`. A bare
# `val showSlashMenu by …collectAsState()` subscription is NOT a guard.
SLASH_GUARD_RE = re.compile(
    r"if\s*\(\s*!?\s*showSlashMenu\b|!\s*showSlashMenu\b|showSlashMenu\s*&&")
# The producer between the guard and the call carries the key list + the IO
# hop; 1600 chars covers it (the dv17-4 window of 800 predates the async
# producer and truncated the guard's reach).
SLASH_GUARD_WINDOW = 1600

def _balanced_paren(text, op):
    """Index of the `)` matching the `(` at `op`; None when unbalanced. The memo
    keys contain nested calls (`collectAsState()`), so a first-`)` scan would
    truncate the key list."""
    depth = 0
    for i in range(op, len(text)):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
    return None

def _split_producer(text, op, close):
    """Split a call into (args, trailing_lambda_body, body_end). Kotlin's
    trailing lambda lives AFTER the argument list's closing paren
    (`f(a, b) { … }`), so the args are inside `op..close` and the body starts
    at the next `{`. body_end is the index of the lambda's closing brace (the
    exclusive end of "inside the producer"). (args, None, -1) when there is no
    trailing lambda, (None, None, -1) when braces are unbalanced — both fail
    closed."""
    args = text[op + 1:close]
    j = close + 1
    while j < len(text) and text[j] in " \t\r\n":
        j += 1
    if j >= len(text) or text[j] != "{":
        return args, None, -1
    depth = 0
    for i in range(j, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return args, text[j + 1:i], i
    return None, None, -1

def slash_memo_findings(inp_c):
    """D1-D4 + D3/D-IO on the comment-stripped ChatInputArea."""
    problems = []
    decl = inp_c.find(SLASH_PAYLOAD_DECL)
    if decl == -1:
        return [("D-memo", "%s not found — fail-closed (the slash menu's "
                 "async payload holder shape drifted?)" % SLASH_PAYLOAD_DECL)]
    m = PRODUCER_RE.search(inp_c[decl:])
    if m is None:
        return [("D-memo", "no produceState(/LaunchedEffect( after %s — "
                 "fail-closed (the menu load is composition-synchronous "
                 "again?)" % SLASH_PAYLOAD_DECL)]
    op = decl + m.end() - 1
    close = _balanced_paren(inp_c, op)
    if close is None:
        return [("D-memo", "unbalanced producer argument list — fail-closed")]
    args, body, body_end = _split_producer(inp_c, op, close)
    if args is None:
        return [("D-memo", "the producer's trailing lambda is unbalanced — "
                 "fail-closed")]
    if body is None:
        return [("D-memo", "the producer declares no trailing lambda — "
                 "fail-closed (where does the load run?)")]

    if re.search(r"\.thinkingLevel\b", args):
        problems.append(("D1", "the slash-menu producer keys on thinkingLevel — "
                         "that source has 0 reads in the body (the /thinking row "
                         "left availableSlashCommands), so every thinking-notch "
                         "tap buys a needless recompute + a fresh query burst"))
    if not re.search(r"\b(currentSessionId|activeSessionId)\b", args):
        problems.append(("D2", "the slash-menu producer keys carry no session "
                         "scope (currentSessionId/activeSessionId) — an open "
                         "menu keeps showing the previous session's per-session "
                         "skill/MCP enablement after ensureSession() lands"))
    for live, label in ((r"\.skills\b", "skill"), (r"\.servers\b", "MCP server")):
        if not re.search(live, args):
            problems.append(("D2", "the slash-menu producer keys carry no %s "
                             "list — an entry toggled for this session (or added "
                             "from disk) never refreshes an open menu" % label))
    for chan, label in (
        (r"skillRepository\?\.sessionOverridesRevision", "skill"),
        (r"mcpRepository\?\.sessionOverridesRevision", "MCP server"),
    ):
        if not re.search(chan, args):
            problems.append(("D4", "the slash-menu producer keys carry no %s "
                             "sessionOverridesRevision — the session-override "
                             "table is not mirrored by the repository list flows, "
                             "so an open menu stays stale after a session toggle "
                             "(B-04: a setSessionOverride write never re-emitted "
                             "anything the keys could see)" % label))

    if SLASH_MEMO_CALL not in body:
        problems.append(("D-memo", "the producer body never calls %s — "
                         "fail-closed (who builds the list?)" % SLASH_MEMO_CALL))
    elif IO_HOP not in body.split(SLASH_MEMO_CALL)[0]:
        problems.append(("D-IO", "the producer calls %s without a preceding %s — "
                         "produceState's producer inherits the composition "
                         "context (Main), so the query burst stays on the main "
                         "thread (1041-B3: produceState is NOT an IO context)"
                         % (SLASH_MEMO_CALL, IO_HOP)))
    for m2 in re.finditer(re.escape(SLASH_MEMO_CALL), inp_c):
        if not (op < m2.start() < body_end):
            problems.append(("D3", "%s runs OUTSIDE the producer — a "
                     "composition-synchronous compute pays the query burst on "
                     "Main on every keystroke with the menu open" % SLASH_MEMO_CALL))
        else:
            window = inp_c[max(0, m2.start() - SLASH_GUARD_WINDOW):m2.start()]
            if not SLASH_GUARD_RE.search(window):
                problems.append(("D3", "%s runs ungated — the list is only "
                     "rendered under showSlashMenu, so a producer that runs "
                     "with the menu closed still pays the query burst"
                     % SLASH_MEMO_CALL))
    return problems

def session_override_channel_findings(skillrepo_c, mcprepo_c):
    """D5 on the two repository files (optional reads — see the section note)."""
    problems = []
    for label, src in (("SkillRepository", skillrepo_c),
                       ("MCPRepository", mcprepo_c)):
        if src is None:
            continue
        if not re.search(r"val sessionOverridesRevision\b", src):
            problems.append(("D5", "%s publishes no `sessionOverridesRevision` "
                             "flow — the session-override table has no "
                             "invalidation channel for async consumers (B-04)"
                             % label))
            continue
        for mut in SESSION_MUTATORS:
            body = declaration_slice(src, mut)
            if not body or not re.search(
                    r"_sessionOverridesRevision\.value\s*=", body):
                problems.append(("D5", "%s.%s writes the session-override "
                                 "table without bumping the revision — the "
                                 "table moved but no consumer can ever see it "
                                 "(the B-04 staleness)" % (label, mut.split("(")[0].replace("fun ", ""))))
    return problems

def slash_ext_batch_findings(slashext_c):
    """D6 on ChatViewModelSlashExt.kt (optional read — see the section note)."""
    if slashext_c is None:
        return []
    body = declaration_slice(slashext_c, "fun ChatViewModel.filteredSlashCommands(")
    if not body:
        return [("D6", "filteredSlashCommands() not found in "
                 "ChatViewModelSlashExt.kt — fail-closed")]
    if "isEnabledForSession(" in body:
        return [("D6", "filteredSlashCommands() still calls the per-item "
                 "isEnabledForSession( — one query per row is N+M queries "
                 "per producer run; the batched sessionEnablementMap is the "
                 "only shape that keeps the query count constant")]
    return []

# ── E: composer decisions read the LIVE field value (dv19-B1) ────────────────
#
# B-01 (dv19 wave): the composer keeps two copies of the draft — the field's own
# TextFieldValue (`inputFieldValue`, written FIRST on every onValueChange, i.e.
# exactly what the user sees) and the ViewModel string, which a >8-char IME
# burst (dictation / large paste) lags by up to 150 ms (the debounce flush). The
# dv16-B fix routed the swipe gesture to a shared predicate over the live value
# but left four DECISIONS reading the debounced string:
#
#   the 3-state button's content flag  → inside a burst the button renders a red
#       STOP over a live stream (the tap calls cancelStream and kills the run the
#       user meant to enqueue) and the idle Send stays dimmed over a composer the
#       user can see is full;
#   the slash-menu dismiss argument     → dismissSlashMenu() echoes its argument
#       back as the composer value ⇒ the just-dictated burst is cleared;
#   the slash-command argument          → same seam (its value is currently
#       ignored in favour of savedInputBeforeSlash, but the call site is the
#       documented "current input" reader and the next reader would inherit it);
#   the cross-session transfer argument → the move stashes an empty string and
#       leaves the 150 ms job alive on ChatScreen's scope, so the snapshot lands
#       on the session the user just left (inputText is per-ViewModel).
#
# Criteria — every one bound to a DECISION POINT (a captured variable name or a
# captured argument expression), never to a whole-line spelling:
#   E1  the content flag of `val <showStop> = isStreaming && !<flag>` must,
#       transitively over `val <name> = …` (<= 4 hops), reach the field's live
#       value or the shared eligibility predicate — and must NOT reach the
#       debounced string.
#   E2  the three call arguments must BE the live field read. All three sites
#       must exist (fail-closed: a missing site = the decision moved).
#   E3  each of the three sites must drop the pending burst flush before it acts
#       on the composer — inline (`imeBurstJob?.cancel()`), or through a shared
#       `() -> Unit` helper whose body cancels the job. Both shapes are legal:
#       the decision is "cancelled before the write", not the spelling.
DEBOUNCED_RE = re.compile(r"\binputText\b")
VAL_DECL_RE = re.compile(r"\bval (\w+)\s*=\s*([^\n]*)")
PRED_DECL_RE = re.compile(r"\bval (\w+):\s*\(\)\s*->\s*Boolean\s*=\s*\{")
UNIT_LAMBDA_RE = re.compile(r"\bval (\w+):\s*\(\)\s*->\s*Unit\s*=\s*\{")
FLAG_USE_RE = re.compile(r"val (\w+)\s*=\s*isStreaming\s*&&\s*!(\w+)")
CANCEL_RE = re.compile(r"imeBurstJob\s*\?\.\s*cancel\(\)")
PENDING_RE = re.compile(r"(?<![\w])PendingTransfer\(")
DISMISS_RE = re.compile(r"dismissSlashMenu\(")
EXECUTE_RE = re.compile(r"executeSlashCommand\(")


def brace_block_at(text, idx):
    """The innermost `{ … }` slice containing [idx] — the block a statement sits
    in. Offsets come from the comment-stripped text, so a prose brace in a KDoc
    cannot land the slice on the wrong block."""
    depth = 0
    start = -1
    for i in range(idx):
        c = text[i]
        if c == "{":
            depth += 1
            start = i
        elif c == "}":
            depth -= 1
            if depth <= 0:
                start = -1
    if start == -1:
        return ""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return text[start:]


def call_arg_spans(text, open_idx):
    """Top-level argument slices of the call whose `(` sits at [open_idx]."""
    close = _balanced_paren(text, open_idx)
    if close is None:
        return None
    inner = text[open_idx + 1:close]
    spans = []
    depth = 0
    cur = 0
    for i, c in enumerate(inner):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            spans.append(inner[cur:i])
            cur = i + 1
    spans.append(inner[cur:])
    return [s.strip() for s in spans]


def composer_live_source_findings(inp_c):
    problems = []

    # ── E1: the 3-state button's content decision ───────────────────────────
    decls = {m.group(1): m.group(2) for m in VAL_DECL_RE.finditer(inp_c)}
    pred_m = PRED_DECL_RE.search(inp_c)
    pred = pred_m.group(1) if pred_m else None
    flag_m = FLAG_USE_RE.search(inp_c)
    if flag_m is None:
        problems.append(("E1", "the 3-state button's content decision "
                         "(`val <showStop> = isStreaming && !<flag>`) not found — "
                         "fail-closed"))
    else:
        flag = flag_m.group(2)
        if flag not in decls:
            problems.append(("E1", "the button's content flag `%s` has no "
                             "`val %s = …` declaration — fail-closed (keep that flag a "
                             "one-line val so the decision stays readable)" % (flag, flag)))
        else:
            seen = set()
            frontier = [flag]
            hops = 0
            live = False
            debounced = False
            while frontier and hops <= 4:
                nxt = []
                for name in frontier:
                    if name in seen:
                        continue
                    seen.add(name)
                    init = decls.get(name)
                    if init is None:
                        continue
                    if "inputFieldValue" in init:
                        live = True
                    if DEBOUNCED_RE.search(init):
                        debounced = True
                    if pred and re.search(r"\b%s\s*\(" % re.escape(pred), init):
                        live = True
                    for ident in re.findall(r"[A-Za-z_]\w*", init):
                        if ident in decls and ident not in seen:
                            nxt.append(ident)
                frontier = nxt
                hops += 1
            if debounced:
                problems.append(("E1", "the button's content decision reads the "
                                 "DEBOUNCED ViewModel string (inputText) via %s — inside a "
                                 "dictation/paste burst the button renders a red STOP over "
                                 "a live stream (the tap cancels the run the user meant to "
                                 "enqueue) and the idle Send stays dimmed over a full "
                                 "composer" % sorted(seen)))
            if not live:
                problems.append(("E1", "the button's content decision (closure %s) never "
                                 "reaches the field's live value or the shared eligibility "
                                 "predicate — it must share that single source" % sorted(seen)))

    # ── E2/E3: the three sites that leave / act on the composer ─────────────
    sites = []
    for cid, label, rx in (("E2-dismiss", "dismiss", DISMISS_RE),
                           ("E2-execute", "execute", EXECUTE_RE),
                           ("E2-transfer", "transfer", PENDING_RE)):
        m = rx.search(inp_c)
        if m is None:
            problems.append((cid, "the %s site (%s…) is missing — fail-closed (these three "
                             "decisions must be visible to the gate)" % (label, rx.pattern)))
            continue
        sites.append((cid, label, m.end() - 1, call_arg_spans(inp_c, m.end() - 1)))

    for cid, label, open_idx, args in sites:
        if args is None:
            problems.append((cid, "unbalanced argument list — fail-closed"))
            continue
        if cid == "E2-execute":
            arg = args[-1] if len(args) >= 2 else None
        elif cid == "E2-transfer":
            arg = None
            for a in args:
                am = re.match(r"inputText\s*=\s*(.*)$", a, re.S)
                if am:
                    arg = am.group(1).strip()
            if arg is None:
                problems.append((cid, "no `inputText = …` named argument — fail-closed"))
                continue
        else:
            arg = args[0]
        if DEBOUNCED_RE.search(arg):
            problems.append((cid, "the %s site passes the DEBOUNCED ViewModel string "
                             "(inputText) to the composer — the 150 ms burst window makes "
                             "it stale (empty right after dictation); it must pass the "
                             "field's own TextFieldValue" % label))
        elif "inputFieldValue" not in arg:
            problems.append((cid, "the %s site passes `%s`, not the field's live value "
                             "(inputFieldValue.text)" % (label, arg[:60])))

    # ── E3: drop the pending burst flush before acting on the composer ──────
    helper = None
    for hm in UNIT_LAMBDA_RE.finditer(inp_c):
        if CANCEL_RE.search(brace_block_at(inp_c, hm.end())):
            helper = hm.group(1)
            break
    inline_any = False
    for cid, label, open_idx, _args in sites:
        block = brace_block_at(inp_c, open_idx)
        ok = bool(helper and re.search(r"\b%s\s*\(\)" % re.escape(helper), block))
        if not ok:
            inline_any = inline_any or bool(CANCEL_RE.search(block))
            ok = bool(CANCEL_RE.search(block))
        if not ok:
            problems.append(("E3", "the %s site does not drop the pending IME-burst flush "
                             "before it acts on the composer: cancel the job (or call the "
                             "shared cancel helper) so the 150 ms snapshot cannot commit "
                             "over the value this site just wrote — after a cross-session "
                             "transfer it would land on the session the user left" % label))
    if helper is None and not inline_any:
        problems.append(("E3", "no pending-burst cancel found anywhere — fail-closed "
                         "(either a `() -> Unit` helper whose body cancels imeBurstJob, or "
                         "an inline cancel at each site)"))
    return problems


# ── G: the composer's text belongs to the composer (dv23-B3) ────────────────

SCREEN_CMD_RE = re.compile(r"ComposerCommand\s*\.\s*(\w+)")
REQUEST_CMD_RE = re.compile(r"requestComposerCommand\s*\(")
SHOW_SLASH_RE = re.compile(r"showSlashMenuOverInput\s*\(")


def _statement_at(text, idx):
    """The single source line around [idx] (comment-stripped text)."""
    start = text.rfind("\n", 0, idx) + 1
    end = text.find("\n", idx)
    return text[start:end if end >= 0 else len(text)]


def composer_screen_handoff_findings(screen_c, inp_c):
    """ChatScreen must not resolve or clear the composer's text itself.

    The composer's single writer is ChatInputArea: it alone holds the live
    field value (`inputFieldValue.text`) and the 150 ms IME-burst job. The
    slash menu's Popup sets `focusable = false` / `dismissOnClickOutside =
    false`, so every outside tap, the system back press and the toolbar `/`
    button reach ChatScreen — and the pre-dv23-B3 code resolved all of them
    against `viewModel.inputText.value`, the DEBOUNCED snapshot, which rolled a
    fresh dictation/paste back to a stale string; the clear-chat confirm wrote
    `setInputText("")`, an equal write MutableStateFlow drops, so the field
    kept the draft it was supposed to wipe.

    Intents travel to the composer instead (ComposerCommand). ChatScreen.kt is
    an OPTIONAL read on purpose — the minimal fixture trees in test_scan.py do
    not ship it.
    """
    problems = []
    if screen_c is None:
        return problems

    # ── G5: the screen never reads or writes the debounced composer string ──
    # [dv24/B-05] G2 banned only the empty-string write; every other write and
    # every read of `viewModel.inputText` used the 150 ms-debounced snapshot.
    # Five entrances were still doing it: the two share/transfer appends (built
    # the new text from the snapshot and wrote it back), the message-edit
    # prefill (an equal write, dropped by the StateFlow, then overwritten by the
    # live burst) and the 'Context Full' restore. A screen has no business
    # owning the composer body at all — it raises intents.
    for m in SET_INPUT_RE.finditer(screen_c):
        stmt = _statement_at(screen_c, m.start())
        if CLEAR_WRITE_RE.search(stmt):
            continue        # G2 owns the empty-string clear (dedicated criterion)
        problems.append(("G5a", "ChatScreen writes the composer body itself (%s) — raise a "
                         "ComposerCommand instead: the write lands on the debounced copy "
                         "(or is dropped outright when it equals it), and the composer's "
                         "live field then keeps the stale text"
                         % stmt.strip()[:90]))
    for m in DEBOUNCED_READ_RE.finditer(screen_c):
        problems.append(("G5b", "ChatScreen reads `viewModel.inputText` (%s) — that is the "
                         "150 ms-debounced snapshot, not what the user sees; a dictation or "
                         "paste inside the window is overwritten by the string read here"
                         % _statement_at(screen_c, m.start()).strip()[:90]))

    # ── G1: the screen never resolves slash-menu text itself ────────────────
    for label, rx in (("dismissSlashMenu", DISMISS_RE),
                      ("showSlashMenuOverInput", SHOW_SLASH_RE)):
        for m in rx.finditer(screen_c):
            problems.append(("G1", "ChatScreen resolves `%s` (%s) — the composer owns the "
                             "live text; raise a ComposerCommand intent instead. Computing "
                             "\"/ <text>\" from `viewModel.inputText` rolls a dictation/paste "
                             "that already landed in the field back to the 150 ms-debounced "
                             "snapshot (and an equal write is dropped by the StateFlow, "
                             "leaving a stray \"/ \" prefix behind)"
                             % (label, _statement_at(screen_c, m.start()).strip()[:90])))

    # ── G2: the screen never clears the composer behind the funnel's back ───
    for m in CLEAR_WRITE_RE.finditer(screen_c):
        problems.append(("G2", "ChatScreen writes the empty string itself (%s) — an equal "
                         "write is dropped by the StateFlow whenever the VM string is "
                         "already \"\", so the field keeps the residual draft; route "
                         "ComposerCommand.Clear so the three-write funnel runs"
                         % _statement_at(screen_c, m.start()).strip()[:90]))

    # ── G3: every intent the screen constructs reaches the channel ──────────
    constructed = {}
    for m in SCREEN_CMD_RE.finditer(screen_c):
        stmt = _statement_at(screen_c, m.start())
        constructed.setdefault(m.group(1), []).append(bool(REQUEST_CMD_RE.search(stmt)))
    for name in sorted(constructed):
        if not all(constructed[name]):
            problems.append(("G3", "`ComposerCommand.%s` is constructed in ChatScreen but "
                             "not handed to the composer channel on that statement — the "
                             "intent is dead" % name))
    if constructed and not re.search(r"\bcomposerCommand\b", inp_c):
        problems.append(("G3", "ChatScreen raises composer intents but ChatInputArea never "
                         "consumes the channel — nothing would ever execute them"))

    # ── G4: the composer executes them against its own live state ───────────
    if not re.search(r"\bcomposerCommand\b", inp_c):
        return problems
    collect_m = re.search(r"\bcomposerCommand\s*\.\s*collect\b", inp_c)
    if collect_m is None:
        problems.append(("G4", "`composerCommand` is referenced in ChatInputArea but never "
                         "collected — the channel has no consumer"))
        return problems
    ob = inp_c.find("{", collect_m.end())
    block = brace_block_at(inp_c, ob + 1) if ob >= 0 else ""
    if not block:
        problems.append(("G4", "the composerCommand collector has no block — fail-closed"))
        return problems
    if "inputFieldValue" not in block:
        problems.append(("G4", "the composerCommand collector acts without reading the "
                         "field's live value (`inputFieldValue`) — every intent would be "
                         "resolved against the debounced ViewModel string again"))
    helper = None
    for hm in UNIT_LAMBDA_RE.finditer(inp_c):
        if CANCEL_RE.search(brace_block_at(inp_c, hm.end())):
            helper = hm.group(1)
            break
    if not (BURST_DROP_RE.search(block)
            or (helper and re.search(r"\b%s\s*\(\)" % re.escape(helper), block))):
        problems.append(("G4", "the composerCommand collector does not drop the pending "
                         "IME-burst flush — a live 150 ms snapshot commits over the value "
                         "the intent just wrote"))
    funnel = None
    for fm in FUNNEL_LAMBDA_RE.finditer(inp_c):
        fob = inp_c.find("{", fm.end() - 1)
        if fob < 0:
            continue
        if CLEAR_WRITE_RE.search(brace_block_at(inp_c, fob + 1)):
            funnel = fm.group(1)
            break
    if funnel is None:
        problems.append(("G4", "no composer-clear funnel to route ComposerCommand.Clear "
                         "into — fail-closed"))
    elif not re.search(r"\b%s\s*\(\)" % re.escape(funnel), block):
        problems.append(("G4", "the collector never reaches the clear funnel `%s` — the "
                         "Clear intent would bypass the three-write reset (empty string + "
                         "field + caret) and leave the draft on screen" % funnel))
    return problems

# ── G5-G7: every composer write is an intent (dv24-B05) ─────────────────────

SET_INPUT_RE = re.compile(r"\bsetInputText\s*\(")
DEBOUNCED_READ_RE = re.compile(r"\bviewModel\s*\.\s*inputText\b")
DISPATCH_CALL_RE = re.compile(r"\brequestComposerCommand\s*\(")
CMD_DECL_OBJECT_RE = re.compile(r"data\s+object\s+(\w+)\s*:\s*ComposerCommand")
CMD_DECL_CLASS_RE = re.compile(r"data\s+class\s+(\w+)\s*\(")
CMD_ARM_RE = re.compile(r"(?:is\s+)?ComposerCommand\s*\.\s*(\w+)\s*->")

def _brace_block(text, open_idx):
    """Text between the braces at/after [open_idx] (brace-matched)."""
    ob = text.find("{", open_idx)
    if ob < 0:
        return None
    depth = 0
    i = ob
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[ob + 1:i]
        i += 1
    return None

def _fun_body(code, marker):
    """Body of `fun <marker>(…)` — paren-matched signature, then the braces."""
    i = code.find(marker)
    if i < 0:
        return None
    j = code.find("(", i)
    if j < 0:
        return None
    depth = 0
    while j < len(code):
        ch = code[j]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return _brace_block(code, j)

def composer_intent_parity_findings(vm_c, inp_c, screen_c):
    """[dv24/B-05] The composer has ONE writer, and the intent set is closed.

    G6 — the two ViewModel-side entrances (`appendToInputText`,
    `dismissContextExhaustedDialog(restoreInput = true)`) must raise an intent
    instead of writing `setInputText` against the 150 ms-debounced copy. The
    append one read `_inputText.value` and appended onto a snapshot a dictation
    burst had already outdated; the restore one wrote a value that
    `MutableStateFlow` dropped whenever the composer already held it.
    G7 — every `ComposerCommand` subtype the ViewModel declares has exactly one
    arm in the composer's collector, and vice versa. Without this a new intent
    added on the ViewModel side (or an arm added for a deleted subtype) is
    invisible: the sealed interface makes the KOTLIN compiler demand the arm,
    but nothing makes a fixture/test tree stay in step, and a command that is
    raised and never collected is silently dropped (`tryEmit` reports nothing).
    """
    problems = []
    if vm_c is None:
        return problems

    # ── G6: the ViewModel-side entrances dispatch through the channel ───────
    for name in ("appendToInputText", "dismissContextExhaustedDialog"):
        body = _fun_body(vm_c, "fun %s(" % name)
        if body is None:
            problems.append(("G6", "`fun %s(` not found in ChatViewModel — fail-closed "
                             "(this entry point must stay visible to the gate)" % name))
            continue
        if not DISPATCH_CALL_RE.search(body):
            problems.append(("G6", "%s resolves/writes the composer text itself — it must "
                             "raise a ComposerCommand: the ViewModel copy lags the field by "
                             "the 150 ms IME debounce, and an equal write is dropped by "
                             "MutableStateFlow entirely" % name))
        elif SET_INPUT_RE.search(body):
            problems.append(("G6", "%s raises a ComposerCommand AND writes setInputText — "
                             "two writers for one text; the direct write is what the intent "
                             "was introduced to remove" % name))

    # ── G7: declared intents ↔ composer arms ────────────────────────────────
    decl_idx = vm_c.find("sealed interface ComposerCommand")
    if decl_idx < 0:
        problems.append(("G7", "no `sealed interface ComposerCommand` in ChatViewModel — "
                         "fail-closed (re-derive this criterion)"))
        return problems
    decls = _brace_block(vm_c, decl_idx)
    if decls is None:
        problems.append(("G7", "the ComposerCommand declaration has no body — fail-closed"))
        return problems
    declared = set(CMD_DECL_OBJECT_RE.findall(decls))
    # a data CLASS declaration only counts inside the sealed interface
    for m in CMD_DECL_CLASS_RE.finditer(decls):
        declared.add(m.group(1))
    if not declared:
        problems.append(("G7", "ComposerCommand declares no subtypes — fail-closed"))
        return problems

    collect_m = re.search(r"\bcomposerCommand\s*\.\s*collect\b", inp_c)
    if collect_m is None:
        problems.append(("G7", "ChatInputArea never collects `composerCommand` — every "
                         "declared intent (%s) would be dropped on emit"
                         % ", ".join(sorted(declared))))
        return problems
    arms_block = _brace_block(inp_c, collect_m.end())
    if arms_block is None:
        problems.append(("G7", "the composerCommand collector has no block — fail-closed"))
        return problems
    arms = set(CMD_ARM_RE.findall(arms_block))

    for name in sorted(declared - arms):
        problems.append(("G7", "`ComposerCommand.%s` is declared but the composer has no "
                         "arm for it — the intent is dead (raised by the screen, dropped "
                         "by the collector)" % name))
    for name in sorted(arms - declared):
        problems.append(("G7", "the composer has an arm for `ComposerCommand.%s`, which no "
                         "longer exists — the collector outran the declaration" % name))
    return problems


# ── H (dv25-B05): the Fast Mode toggle has ONE source ──────────────────────
#
# The flag is APP-LEVEL and persisted (FastModePrefs). The provider layer reads
# it per request through `FastModePrefs.isEnabled()`, but the chat screen read
# a ViewModel-LOCAL copy seeded once at construction
# (`MutableStateFlow(FastModePrefs.isEnabled())`). ViewModels live in
# ChatViewModelStore, so an already-open conversation kept rendering the flag
# as it was when that chat was first opened: the menu row and the nav ⚡ badge
# disagreed with the wire. The defect is the SECOND copy, not a missing sync.
#
# The criterion is DERIVED, not spelled: the process-level flow is whatever
# public `StateFlow<Boolean>` FastModePrefs exposes, and the ViewModel must
# source its own flow from THAT property. A rename on either side stays green;
# a second, locally-seeded copy goes red.
FASTMODE_REL = ("src/android/app/src/main/java/com/rikkaminis/app/"
                "data/FastModePrefs.kt")
FASTMODE_FLOW_RE = re.compile(
    r"^[ \t]*val\s+(\w+)\s*:\s*StateFlow<Boolean>", re.MULTILINE)
FASTMODE_SNAPSHOT_RE = re.compile(
    r"MutableStateFlow\s*\([^)]*FastModePrefs\s*\.\s*isEnabled\s*\(\)")


def fast_mode_single_source_findings(vm_c, fm_c):
    """H1-H3 (dv25-B05 / deep-audit-1045 B-05)."""
    problems = []
    if vm_c is None:
        return problems
    if fm_c is None:
        problems.append(("H2",
                         "FastModePrefs.kt cannot be read — fail-closed (the global "
                         "source of the Fast Mode flag is unknown)"))
        return problems

    flows = sorted(set(FASTMODE_FLOW_RE.findall(fm_c)))
    if not flows:
        problems.append(("H2",
                         "FastModePrefs exposes NO public `StateFlow<Boolean>` — the "
                         "chat screen has nothing global to read, so it will keep a "
                         "local copy that drifts"))
        return problems

    # H1 — no locally seeded snapshot of the global flag.
    if FASTMODE_SNAPSHOT_RE.search(vm_c):
        problems.append(("H1",
                         "ChatViewModel seeds a local MutableStateFlow from "
                         "`FastModePrefs.isEnabled()` — a CONSTRUCTION-TIME snapshot. "
                         "ViewModels live in ChatViewModelStore, so an already-open "
                         "conversation keeps rendering the flag as of the moment it was "
                         "first opened, while every request builder already uses the "
                         "new value: the screen and the wire disagree"))

    # H2 — the ViewModel's flow must be sourced from the global one.
    decl = re.search(
        r"val\s+fastModeEnabled\s*:\s*StateFlow<Boolean>\s*=\s*([^\n]+)",
        vm_c)
    if decl is None:
        problems.append(("H2",
                         "`val fastModeEnabled: StateFlow<Boolean>` not found in "
                         "ChatViewModel — fail-closed (the toggle must stay observable)"))
        return problems
    init = decl.group(1)
    if not any(re.search(r"FastModePrefs\s*\.\s*%s\b" % re.escape(f), init)
               for f in flows):
        problems.append(("H2",
                         "`fastModeEnabled` is not sourced from FastModePrefs' global "
                         "flow (%s) — it must be the SAME flow the provider layer and "
                         "every other screen read, not a second copy"
                         % " / ".join(flows)))
    return problems


# ── decision-point primitives ([wide3-07]) ──────────────────────────────────
#
# A branch's SPELLING is not its DECISION. Every dataflow witness of this wave
# kept the call and deleted the effect:
#   A2          `if (cmd.isMcp) { if (false) return fillComposerWithSlashTitle(…) }`
#   C1          `inputFieldValue.let { inputText.isNotEmpty() && (false || attachments.isEmpty()) }`
# Both kept every substring the old criteria searched for. The primitives below
# let a criterion ask "can this decide?" / "which value does it read?" instead.

def _split_top(expr, op):
    """Split [expr] on [op] at paren/bracket/brace depth 0."""
    parts, depth, last, i = [], 0, 0, 0
    while i < len(expr):
        c = expr[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and expr.startswith(op, i):
            parts.append(expr[last:i])
            last = i + len(op)
            i = last
            continue
        i += 1
    parts.append(expr[last:])
    return parts


def cond_truth(expr):
    """True/False when a LITERAL operand decides the condition, else None."""
    s = expr.strip()
    while s.startswith("(") and s.endswith(")") and _balanced_paren(s, 0) == len(s) - 1:
        s = s[1:-1].strip()
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!"):
        inner = cond_truth(s[1:])
        return None if inner is None else (not inner)
    ors = _split_top(s, "||")
    if len(ors) > 1:
        vals = [cond_truth(x) for x in ors]
        if any(v is True for v in vals):
            return True
        if vals and all(v is False for v in vals):
            return False
        return None
    ands = _split_top(s, "&&")
    if len(ands) > 1:
        vals = [cond_truth(x) for x in ands]
        if any(v is False for v in vals):
            return False
        if vals and all(v is True for v in vals):
            return True
        return None
    return None


def dead_guard_spans(text):
    """[(start, end)] of bodies whose controlling condition is literal-FALSE."""
    spans = []
    for m in re.finditer(r"\b(?:if|while)\s*\(", text):
        op = m.end() - 1
        cl = _balanced_paren(text, op)
        if cl is None or cond_truth(text[op + 1:cl]) is not False:
            continue
        k = cl + 1
        while k < len(text) and text[k] in " \t":
            k += 1
        if k < len(text) and text[k] == "{":
            e = _matching_brace_end(text, k)
            if e > k:
                spans.append((k, e))
        else:
            eol = text.find("\n", k)
            spans.append((k, eol if eol >= 0 else len(text)))
    return spans


def inside_spans(spans, pos):
    return any(a <= pos <= b for a, b in spans)


# ── the gate ─────────────────────────────────────────────────────────────────

# ---------------------------------------------------------------------------
# C-CLIP-1 (dv27 B-07): the borrowed clipboard is ALWAYS handed back
# ---------------------------------------------------------------------------
# `MinisMarkdownTextToolbar.withSelection` borrows the system clipboard to
# resolve a text selection (Compose exposes no "give me the selection" API).
# Its `finally` used to restore the snapshot only when there WAS one
# (`if (original != null)`), so a user whose clipboard was empty (or unreadable)
# kept the borrowed selection in the clipboard — visible to every other app and
# to the next paste.
#
# The criterion is behavioural, not spelling-based: the restore block must
# contain BOTH a "put the original clip back" action AND a "make the clipboard
# empty again" action, and the two must live in MUTUALLY EXCLUSIVE branches —
# otherwise the "clipboard was empty" case is still skipped, which is exactly
# the defect shape this criterion exists to catch.

CLIP_RESTORE_RE = re.compile(r"\.setPrimaryClip\s*\(")
CLIP_CLEAR_RE = re.compile(r"\.clearPrimaryClip\s*\(|newPlainText\s*\(")


def _matching_brace_end(text, open_idx):
    depth = 0
    i = open_idx
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _mutually_exclusive_regions(block):
    """Groups of mutually-exclusive branch spans found inside [block].

    A group is a list of (start, end) spans whose bodies cannot both run.
    Recognised shapes: `when (x) { A -> ... B -> ... }` arms, and
    `if (c) { ... } else { ... }`.
    """
    regions = []
    for m in re.finditer(r"\bwhen\s*\(", block):
        ob = block.find("{", m.end())
        if ob < 0:
            continue
        end = _matching_brace_end(block, ob)
        if end < 0:
            continue
        body = block[ob + 1:end]
        headers = [(h.start(), h.end()) for h in re.finditer(
            r"(?m)^[ \t]*(?:else\s*)?(?:[A-Za-z_][\w.]*[ \t]*)?->", body)]
        if len(headers) < 2:
            continue
        spans = []
        for i, (hs, _he) in enumerate(headers):
            nxt = headers[i + 1][0] if i + 1 < len(headers) else len(body)
            spans.append((ob + 1 + hs, ob + 1 + nxt))
        regions.append(spans)
    for m in re.finditer(r"\bif\s*\(", block):
        j = block.find("(", m.end())
        if j < 0:
            continue
        depth = 0
        k = j
        while k < len(block):
            if block[k] == "(":
                depth += 1
            elif block[k] == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        ob = block.find("{", k)
        if ob < 0:
            continue
        end = _matching_brace_end(block, ob)
        if end < 0:
            continue
        tail = block[end + 1:]
        stripped = tail.lstrip()
        if not stripped.startswith("else"):
            continue
        e_ob = end + 1 + (len(tail) - len(stripped))
        e_ob = block.find("{", e_ob)
        if e_ob < 0:
            continue
        e_end = _matching_brace_end(block, e_ob)
        if e_end < 0:
            continue
        regions.append([(ob + 1, end), (e_ob + 1, e_end)])
    return regions


def clipboard_restore_findings(mdtoolbar_c):
    """C-CLIP-1 — the borrowed clipboard is always handed back."""
    if "withSelection" not in mdtoolbar_c:
        return []  # ROT: this toolbar shape does not borrow the clipboard at all
    problems = []
    fi = mdtoolbar_c.find("finally")
    if fi < 0:
        return [("C-CLIP-1", "withSelection has no `finally` — the borrowed "
                 "clipboard is not handed back when the copy/read throws")]
    ob = mdtoolbar_c.find("{", fi)
    block = _brace_block(mdtoolbar_c, ob) if ob >= 0 else None
    if block is None:
        return [("C-CLIP-1", "cannot read the `finally` block of withSelection")]
    restore = CLIP_RESTORE_RE.search(block)
    clear = CLIP_CLEAR_RE.search(block)
    if not restore:
        problems.append(("C-CLIP-1", "the `finally` block never puts the original "
                         "clip back (no setPrimaryClip) — the borrowed selection "
                         "permanently replaces the user's clipboard"))
    if not clear:
        problems.append(("C-CLIP-1", "the `finally` block has no 'make it empty "
                         "again' action (clearPrimaryClip / empty-string write) — a "
                         "user whose clipboard was empty keeps our borrowed text"))
    if restore and clear:
        rp, cp = restore.start(), clear.start()
        separated = False
        for spans in _mutually_exclusive_regions(block):
            in_r = [i for i, (s, e) in enumerate(spans) if s <= rp < e]
            in_c = [i for i, (s, e) in enumerate(spans) if s <= cp < e]
            if in_r and in_c and in_r[0] != in_c[0]:
                separated = True
                break
        if not separated:
            problems.append(("C-CLIP-1", "the restore and the clear are not in "
                             "mutually exclusive branches — the 'clipboard was "
                             "empty' case is still skipped (the old "
                             "`if (original != null)` shape)"))
    return problems

def check_tree(root):
    """Returns (None, [problems]) when a target file is missing, else
    (path-of-first-problem, problems)."""
    problems = []

    def miss(rel):
        problems.append(("missing:" + rel, "target file missing — fail-closed"))
        return None

    models = read(root, MODELS_REL)
    token = read(root, TOKEN_REL)
    inp = read(root, INPUT_REL)
    gesture = read(root, GESTURE_REL)
    selection = read(root, SELECTION_REL)
    stream = read(root, STREAM_REL)
    mdtoolbar = read(root, MDTOOLBAR_REL)
    for rel, src in ((MODELS_REL, models), (TOKEN_REL, token), (INPUT_REL, inp),
                     (GESTURE_REL, gesture), (SELECTION_REL, selection),
                     (STREAM_REL, stream), (MDTOOLBAR_REL, mdtoolbar)):
        if src is None:
            miss(rel)
    if problems:
        return None, problems

    models_c = strip_strings(strip_comments(models))
    token_c = strip_strings(strip_comments(token))
    inp_c = strip_strings(strip_comments(inp))
    gesture_c = strip_strings(strip_comments(gesture))
    selection_c = strip_strings(strip_comments(selection))
    stream_c = strip_strings(strip_comments(stream))
    mdtoolbar_c = strip_strings(strip_comments(mdtoolbar))

    # ── C-CLIP-1: the borrowed clipboard is always handed back ────────────
    for cid, msg in clipboard_restore_findings(mdtoolbar_c):
        problems.append((cid, msg))

    # ── A1: every SlashCommand boolean field has >= 1 read point ────────────
    # SlashCommand is a primary-constructor-only data class (no body braces),
    # so the field list is captured from the parameter list (paren-matched) —
    # slicing from the first `{` would run into the NEXT declaration's body
    # and pick up its computed properties (isText lives on AssistantBlock).
    # Paren-MATCHED, not first-`)`: a default value containing a call
    # (`val tag: String = String()`) used to truncate the list, and every
    # boolean declared after it fell out of the scan while the earlier ones kept
    # the fail-closed check quiet (external witness A1).
    cmd_params = signature_args_balanced(models_c, "data class SlashCommand(")
    bool_fields = re.findall(r"val\s+(\w+)\s*:\s*Boolean", cmd_params or "")
    if not bool_fields:
        problems.append((MODELS_REL, "A1 SlashCommand declares no boolean fields — "
                         "fail-closed (descriptor shape drifted?)"))
    consumers = token_c + inp_c
    for field in bool_fields:
        reads = len(re.findall(r"\.%s\b" % re.escape(field), consumers))
        if reads == 0:
            problems.append((
                "A1:%s" % field,
                "A1 SlashCommand.%s is written but has 0 read points across the "
                "slash consumers (ChatSlashTokenExt + ChatInputArea) — the "
                "B-01 class: a written-but-never-read descriptor flag" % field,
            ))

    # ── A2: executeSlashCommand carries an isMcp branch ─────────────────────
    exec_slice = declaration_slice(token_c, "fun ChatViewModel.executeSlashCommand(")
    if not exec_slice:
        problems.append(("A2", "executeSlashCommand not found — fail-closed"))
    else:
        if FILL_HELPER not in exec_slice:
            problems.append(("A2", "executeSlashCommand must route the "
                             "typing-aid fill through the shared helper %s (single "
                             "source for the prepend/caret semantics)" % FILL_HELPER))
        # The helper call must be RETURNED FROM THE ISMcp BRANCH. A whole-slice
        # substring test is satisfied by the isSkill arm above it, and by a call
        # wrapped in `if (false) return …` — both leave an MCP tap falling into
        # the action dispatch, which returns "" and wipes the composer (the
        # external witness A2 shape).
        mcp_arm = re.search(r"if\s*\(\s*cmd\.isMcp\s*\)\s*\{", exec_slice)
        if mcp_arm is None:
            problems.append(("A2", "executeSlashCommand has no `if (cmd.isMcp) { … }` "
                             "branch — MCP row taps fall into the action dispatch and "
                             "wipe the composer (B-01)"))
        else:
            block = _brace_block(exec_slice, mcp_arm.end() - 1) or ""
            dead = dead_guard_spans(block)
            returns_fill = False
            for r in re.finditer(re.escape(FILL_HELPER), block):
                if inside_spans(dead, r.start()):
                    continue
                stmt_head = block[block.rfind("\n", 0, r.start()) + 1:r.start()]
                if re.match(r"^\s*return\s*$", stmt_head):
                    returns_fill = True
                    break
            if not returns_fill:
                problems.append(("A2", "the `if (cmd.isMcp)` branch does not RETURN the "
                                 "shared fill helper %s on its live path — a literal-decided "
                                 "`if (false) return …` (or a fill that only happens in the "
                                 "isSkill arm) keeps the spelling and loses the decision, so "
                                 "an MCP tap still falls through to the action dispatch and "
                                 "wipes the composer (B-01)" % FILL_HELPER))

    # ── A3: isMcp read in a ChatInputArea condition ─────────────────────────
    if len(re.findall(r"\.isMcp\b", inp_c)) == 0:
        problems.append(("A3", "ChatInputArea never reads isMcp — the "
                         "installed-row divider / IME restore stay skill-only and an "
                         "MCP tap drops the keyboard (B-01)"))

    # ── B1: main-chat toolbar wires the table actions ───────────────────────
    if "selectionTableActions()" not in gesture_c:
        problems.append(("B1", "MinisSelectionToolbarHost never calls "
                         "selectionTableActions() — the main-chat long-press toolbar "
                         "never surfaces Copy Table / Copy Table Image (B-02)"))

    # ── B2: the markdown-toolbar path stays a consumer ──────────────────────
    if "selectionTableActions()" not in mdtoolbar_c:
        problems.append(("B2", "MinisMarkdownTextToolbar no longer calls "
                         "selectionTableActions() — that path is a legitimate "
                         "consumer; deleting it is a regression"))

    # ── B3: table-level registry key ────────────────────────────────────────
    remember_args = signature_args(selection_c, "fun rememberTableActions(")
    forget_args = signature_args(selection_c, "fun forgetTableActions(")
    if remember_args is None or ": Int" not in remember_args or remember_args.count(",") < 2:
        problems.append(("B3", "rememberTableActions must take a table-"
                         "level key (an Int dimension beyond messageId) — a bare "
                         "messageId key lets same-message tables overwrite each "
                         "other and a dispose drop its sibling (B-02)"))
    if forget_args is None or ": Int" not in forget_args:
        problems.append(("B3", "forgetTableActions must take the table "
                         "key too — a bare messageId remove takes whatever is "
                         "stored, including a still-composed sibling table"))
    sel_slice = declaration_slice(selection_c, "fun selectionTableActions(")
    if sel_slice:
        if not re.search(r"(Map<Int|SnapshotStateMap<Int)", selection_c):
            problems.append(("B3", "the table registry declaration must be "
                             "two-level (an Int table dimension inside the per-message "
                             "map)"))
    else:
        problems.append(("B3", "selectionTableActions not found — fail-closed"))

    # ── B4: cross-message exclusion preserved ───────────────────────────────
    if sel_slice and "singleMessageId()" not in sel_slice:
        problems.append(("B4", "selectionTableActions must still guard on "
                         "singleMessageId() — a cross-message selection must not "
                         "expose single-table actions"))

    # ── B5: publisher passes the table index ────────────────────────────────
    pub_call = signature_args(stream_c, ".rememberTableActions(")
    if pub_call is None or pub_call.count(",") != 2:
        problems.append(("B5", "RenderTable must publish under the table key "
                         "(a 3-arg rememberTableActions call: messageId, tableIndex, "
                         "actions)"))
    if "LocalShardSubIndexAllocator.current" not in stream_c:
        problems.append(("B5", "RenderTable must source the table index from "
                         "the shard allocator (a stable per-slot identity within the "
                         "fragment)"))

    # ── H: the streaming block ledger (dv21-B4-B05) ─────────────────────────
    problems.extend(streaming_block_ledger_findings(stream_c))

    # ── swipe block: delimited by the pointerInput lambda ───────────────────
    g_idx = inp_c.find("pointerInput(Unit)")
    if g_idx == -1:
        problems.append(("swipe-block", "swipe gesture block not found (pointerInput(Unit)) "
                         "— fail-closed"))
        gesture_slice = ""
    else:
        gesture_slice = declaration_slice(inp_c, "pointerInput(Unit)")

    # ── C1/C2: one shared eligibility predicate, read at call time ──────────
    preds = PRED_RE.findall(inp_c)
    send_preds = [name for name in preds
                  if re.search(r"val %s:\s*\(\)\s*->\s*Boolean\s*=\s*\{([^\n]*)" % re.escape(name), inp_c)
                  and "inputFieldValue" in inp_c]
    # Narrow to the predicate whose body carries the field-value + attachments reads.
    body_match = re.search(r"val (\w+):\s*\(\)\s*->\s*Boolean\s*=\s*\{\s*inputFieldValue", inp_c)
    if body_match is None:
        problems.append(("C1", "no shared send-eligibility predicate found — the "
                         "three swipe decision sites must read one predicate "
                         "(`val <name>: () -> Boolean = { inputFieldValue.text.isNotBlank()"
                         " || attachments.isNotEmpty() }`), not re-derive it per site (B-06)"))
    else:
        name = body_match.group(1)
        # The body spans lines — capture from the opening brace to its MATCHING
        # close (a `find("}")` stops at the first inner brace, e.g. a `let { }`
        # wrapper, and then only part of the expression is judged).
        brace = inp_c.find("{", body_match.start())
        close = _matching_brace_end(inp_c, brace) if brace != -1 else -1
        pred_body = inp_c[brace + 1:close] if brace != -1 and close > brace else ""
        # Read the OPERANDS, not the tokens: the predicate must be an OR of
        # (field value non-blank) and (attachments non-empty), and neither
        # operand may be short-circuited by a literal. `inputFieldValue.let {
        # inputText.isNotEmpty() && (false || attachments.isEmpty()) }` keeps
        # the field-value spelling, reads the debounced string, inverts the
        # attachment polarity and deletes the decision — the external witness
        # C1 shape.
        if re.search(r"\binputText\b", pred_body):
            problems.append((INPUT_REL, "C1 the swipe predicate reads the debounced VM "
                             "string (inputText) — a just-typed prefix is invisible for "
                             "the debounce window; it must read the field's own "
                             "TextFieldValue"))
        literals = [x.strip() for x in _split_top(pred_body, "||")
                    if cond_truth(x) is not None]
        if literals:
            problems.append(("C1", "C1 the swipe predicate contains a literal-decided "
                             "operand (%s) — a `false || …` / `true && …` arm deletes the "
                             "decision while keeping the expression" % ", ".join(literals)))
        field_ok = re.search(
            r"inputFieldValue\s*\.\s*text(?:\s*\.\s*trim\(\))?\s*\."
            r"\s*(?:isNotBlank|isNotEmpty)\s*\(\s*\)", pred_body) is not None or \
            re.search(r"!\s*inputFieldValue\s*\.\s*text\s*\.\s*(?:isBlank|isEmpty)"
                      r"\s*\(\s*\)", pred_body) is not None
        attach_ok = re.search(
            r"\battachments\s*(?:\.\s*(?:isNotEmpty|any)\s*\(|\.\s*size\s*>\s*0)",
            pred_body) is not None or \
            re.search(r"!\s*attachments\s*\.\s*isEmpty\s*\(\s*\)", pred_body) is not None
        if not field_ok:
            problems.append((INPUT_REL, "C1 the swipe predicate never tests the field's "
                             "own TextFieldValue for non-blank text (`inputFieldValue.text."
                             "isNotBlank()`) — a wrapper, alias or debounced string stands "
                             "in for the live value (B-06)"))
        if not attach_ok:
            problems.append(("C1", "C1 the swipe predicate does not treat a NON-EMPTY "
                             "attachment list as sendable (`attachments.isNotEmpty()` / "
                             "`attachments.size > 0`) — attachment-only drags are rejected "
                             "(B-06)"))
        calls = len(re.findall(r"\b%s\(\)" % re.escape(name), inp_c))
        if calls < 3:
            problems.append(("C2", "C2 the shared predicate is used %d time(s) — the "
                             "three decision sites (drag hint / release-send / empty-input "
                             "keyboard) must all read it, or a per-site re-derivation "
                             "drifts back" % calls))

    # ── C3: nested dead-branch shape (shape-detected, not name-bound) ───────
    lines = gesture_slice.split("\n")
    for i, line in enumerate(lines):
        m = IF_AND_RE.search(line)
        if not m:
            continue
        second = m.group(2)
        window = [l for l in lines[i + 1:i + 5] if l.strip()]
        for l in window:
            if re.search(r"val\s+\w+\s*=\s*%s\b\s*(\(\))?\s*\|\|" % re.escape(second), l):
                problems.append(("C3", "dead branch: an eligibility value is "
                                 "re-derived from `%s` inside a block already guarded by "
                                 "`&& %s` — the inner test is tautological and routes "
                                 "attachment-only drags to the keyboard (B-06)" % (second, second)))
                break

    # ── C4: threshold via rememberUpdatedState ──────────────────────────────
    thr = re.search(r"val (\w+)\s*=\s*rememberUpdatedState\(\s*with\(LocalDensity\.current\)\s*\{\s*sendSwipeThresholdDp\.dp\.toPx\(\)", inp_c)
    if thr is None:
        problems.append(("C4", "the swipe threshold must read through "
                         "rememberUpdatedState — a plain val capture freezes the first "
                         "composition's px and a ChatTuning adjustment never reaches the "
                         "gesture"))
    else:
        thr_name = thr.group(1)
        if "%s.value" % thr_name not in gesture_slice:
            problems.append(("C4", "the gesture must read the threshold state "
                             "(%s.value), or the state holder is dead weight" % thr_name))

    # ── C5/C6: preserved behaviors inside the gesture ───────────────────────
    if gesture_slice:
        if "performSendOrEnqueue()" not in gesture_slice:
            problems.append(("C5", "the release branch must still route through "
                             "performSendOrEnqueue() — streaming drag-to-send enqueues "
                             "instead of being dropped"))
        if "!inputFocused" not in gesture_slice:
            problems.append(("C6", "the empty-input keyboard shortcut "
                             "(!inputFocused) must survive — swiping up with nothing to "
                             "send and no focus pops the keyboard"))

    # ── D1-D4 + D-IO: slash-menu async load wiring (dv17-4 / dv22-B4) ─────
    problems.extend(slash_memo_findings(inp_c))
    # ── D5/D6: override-table invalidation + batched read (dv22-B4). The
    # three files are OPTIONAL sibling reads: test_scan's minimal fixture
    # trees do not ship them, and their absence must not fail-closed the
    # A/B/C/E criteria that live in the chat sources.
    skillrepo = read(root, SKILL_REPO_REL)
    mcprepo = read(root, MCP_REPO_REL)
    slashext = read(root, SLASH_EXT_REL)
    problems.extend(session_override_channel_findings(
        strip_strings(strip_comments(skillrepo)) if skillrepo is not None else None,
        strip_strings(strip_comments(mcprepo)) if mcprepo is not None else None))
    problems.extend(slash_ext_batch_findings(
        strip_strings(strip_comments(slashext)) if slashext is not None else None))

    # ── E1-E3: composer decisions read the live field value (dv19-B1) ──────
    problems.extend(composer_live_source_findings(inp_c))
    problems.extend(composer_clear_funnel_findings(inp_c))
    problems.extend(composer_body_funnel_findings(inp_c))

    # ── G1-G4: ChatScreen routes composer intents, it does not resolve them ─
    # (dv23-B3) OPTIONAL read — the minimal fixture trees ship no ChatScreen.
    screen = read(root, SCREEN_REL)
    problems.extend(composer_screen_handoff_findings(
        strip_strings(strip_comments(screen)) if screen is not None else None, inp_c))
    # ── G5-G7: every composer write is an intent (dv24-B05) ────────────────
    # OPTIONAL read for the same reason as ChatScreen.kt.
    # NOTE: comment-stripped only (no strip_strings). ChatViewModel.kt contains
    # Kotlin CHAR literals that the shared string stripper mis-tracks ('"' inside
    # '...'), which blanks a long legitimate span — the marker below then reads
    # as "missing". A comment can still not satisfy any of these criteria, so
    # leaving string literals intact keeps the criteria honest here.
    vm = read(root, VM_REL)
    problems.extend(composer_intent_parity_findings(
        strip_comments(vm) if vm is not None else None,
        inp_c,
        strip_strings(strip_comments(screen)) if screen is not None else None))

    # ── H1-H3: the Fast Mode toggle has one source (dv25-B05) ──────────────
    # ChatViewModel.kt is read comment-stripped only (see the note above: the
    # shared string stripper mis-tracks its CHAR literals).
    fastmode = read(root, FASTMODE_REL)
    problems.extend(fast_mode_single_source_findings(
        strip_comments(vm) if vm is not None else None,
        strip_comments(fastmode) if fastmode is not None else None))

    return INPUT_REL, problems


# ── selftest fixtures ────────────────────────────────────────────────────────

MODELS_F = """package com.rikkaminis.app.ui.chat

@Immutable
data class SlashCommand(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val isSkill: Boolean = false,
    val isMcp: Boolean = false,
)
"""

TOKEN_F = """package com.rikkaminis.app.ui.chat

fun ChatViewModel.executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
    val saved = savedInputBeforeSlash
    if (cmd.isSkill) {
        resetSlashMenuState()
        return fillComposerWithSlashTitle(cmd, saved)
    }
    if (cmd.isMcp) {
        resetSlashMenuState()
        return fillComposerWithSlashTitle(cmd, saved)
    }
    when (cmd.id) {
        "memory" -> toggleMemoryEnabled()
        else -> Unit
    }
    return ""
}
"""

INPUT_F = """package com.rikkaminis.app.ui.chat

@Composable
fun ChatInputArea() {
    val swipeThresholdPxState = rememberUpdatedState(
        with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() },
    )
    val showSlashMenu by viewModel.showSlashMenu.collectAsState()
    val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (showSlashMenu) {
        produceState(
            initialValue = SlashMenuPayload(),
            viewModel.slashFilter.collectAsState().value,
            viewModel.memoryEnabled.collectAsState().value,
            viewModel.currentSessionId,
            viewModel.skillRepository?.skills?.collectAsState()?.value,
            viewModel.mcpRepository?.servers?.collectAsState()?.value,
            viewModel.skillRepository?.sessionOverridesRevision?.collectAsState()?.value,
            viewModel.mcpRepository?.sessionOverridesRevision?.collectAsState()?.value,
        ) {
            withContext(Dispatchers.IO) {
                value = SlashMenuPayload(
                    commands = viewModel.filteredSlashCommands(),
                    loaded = true,
                )
            }
        }
    } else {
        remember { mutableStateOf(SlashMenuPayload()) }
    }
    val filteredSlashCommands = slashMenuPayload.value.commands
    if (showSlashMenu && (filteredSlashCommands.isNotEmpty() || !slashMenuPayload.value.loaded)) {
        Text(filteredSlashCommands.size.toString())
        Popup(onDismissRequest = {
            cancelPendingImeBurst()
            commitComposerBody(viewModel.dismissSlashMenu(inputFieldValue.text), null)
        })
        Row {
            if (!isThinking) {
                it.clickable {
                    cancelPendingImeBurst()
                    commitComposerBody(viewModel.executeSlashCommand(cmd, inputFieldValue.text), null)
                }
            }
        }
    }
    val cancelPendingImeBurst: () -> Unit = {
        imeBurstJob?.cancel()
        imeBurstJob = null
        imeBurstBuffer = null
    }
    val clearComposerInput: () -> Unit = {
        cancelPendingImeBurst()
        viewModel.setInputText("")
        inputFieldValue = TextFieldValue("")
        lastTrueCaretEnd = 0
    }
    // [deep-audit-1049 B-06] the body funnel: both copies, burst dropped first
    val commitComposerBody: (String, Int?) -> Unit = { text, caret ->
        cancelPendingImeBurst()
        val at = (caret ?: lastTrueCaretEnd).coerceIn(0, text.length)
        viewModel.setInputText(text)
        inputFieldValue = TextFieldValue(text)
        lastTrueCaretEnd = at
    }
    val performSendOrEnqueue: () -> Unit = handler@{
        val toSend = inputFieldValue.text
        if (viewModel.tryExecuteInputAsSlashCommand(toSend)) {
            clearComposerInput()
            return@handler
        }
        clearComposerInput()
        viewModel.sendMessage(toSend)
    }
    Row {
        Surface(modifier = Modifier.clickable {
            viewModel.cancelEdit()
            clearComposerInput()
        }) { }
    }
    val canSwipeSendNow: () -> Boolean = {
        inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()
    }
    itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
        val isInstalledRow = cmd.isSkill || cmd.isMcp
        val prevIsInstalledRow = index > 0 &&
            (filteredSlashCommands[index - 1].isSkill ||
                filteredSlashCommands[index - 1].isMcp)
        if (isInstalledRow && index > 0 && !prevIsInstalledRow) {
            HorizontalDivider()
        }
        if (cmd.isSkill || cmd.isMcp) {
            inputFocusRequester.requestFocus()
        }
    }
    Box(modifier = Modifier
        .fillMaxWidth()
        .pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalDy = 0f
                var claimed = false
                verticalDrag(down.id) { change ->
                    if (totalDy < 0) {
                        if (canSwipeSendNow()) {
                            val newProgress = (-totalDy / swipeThresholdPxState.value).coerceIn(0f, 1f)
                            sendSwipeProgress = newProgress
                        } else if (sendSwipeProgress != 0f) {
                            sendSwipeProgress = 0f
                        }
                    }
                }
                val swipedUp = claimed && totalDy < 0
                if (swipedUp && canSwipeSendNow()) {
                    if (sendSwipeProgress >= swipeArmFraction) {
                        performSendOrEnqueue()
                    }
                    sendSwipeProgress = 0f
                } else if (swipedUp && !canSwipeSendNow() && !inputFocused) {
                    inputFocusRequester.requestFocus()
                    sendSwipeProgress = 0f
                } else {
                    sendSwipeProgress = 0f
                }
            }
        },
    ) {
        Column {
        }
    }
    val hasContent = canSwipeSendNow()
    val showStop = isStreaming && !hasContent
    if (showMoveSheet) {
        MoveToSessionSheet(onSelect = { targetId ->
            cancelPendingImeBurst()
            ChatViewModelStore.stashPendingTransfer(
                ChatViewModelStore.PendingTransfer(
                    inputText = inputFieldValue.text,
                    attachments = viewModel.attachments.value,
                ),
            )
            clearComposerInput()
        })
    }
}
"""

SLASHEXT_F = """package com.rikkaminis.app.ui.chat

internal fun ChatViewModel.filteredSlashCommands(): List<SlashCommand> {
    val sid = activeSessionId
    val skillEnablement = skillRepository?.sessionEnablementMap(
        sid, skillRepository.skills.value.map { it.id }) ?: emptyMap()
    val mcpEnablement = mcpRepository?.sessionEnablementMap(
        sid, mcpRepository.servers.value.map { it.id }) ?: emptyMap()
    val skillRows = skillRepository?.skills?.value
        ?.filter { skillEnablement[it.id] == true } ?: emptyList()
    val mcpRows = mcpRepository?.servers?.value
        ?.filter { mcpEnablement[it.id] == true } ?: emptyList()
    return skillRows + mcpRows
}
"""

SKILLREPO_F = """package com.rikkaminis.app.data.repository

class SkillRepository {
    private val _sessionOverridesRevision = MutableStateFlow(0L)
    val sessionOverridesRevision: StateFlow<Long> = _sessionOverridesRevision.asStateFlow()

    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        db.execSQL("INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)", arrayOf<Any>(sessionId, skillId, 1))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL("UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?", arrayOf<Any>(toReal, fromDraft))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }
}
"""

MCPREPO_F = """package com.rikkaminis.app.data.repository

class MCPRepository {
    private val _sessionOverridesRevision = MutableStateFlow(0L)
    val sessionOverridesRevision: StateFlow<Long> = _sessionOverridesRevision.asStateFlow()

    fun setSessionOverride(sessionId: String, mcpId: String, enabled: Boolean) {
        db.execSQL("INSERT OR REPLACE INTO mcp_session_overrides (session_id, mcp_id, is_enabled) VALUES (?, ?, ?)", arrayOf<Any>(sessionId, mcpId, 1))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE session_id=?", arrayOf(sessionId))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL("UPDATE OR REPLACE mcp_session_overrides SET session_id=? WHERE session_id=?", arrayOf<Any>(toReal, fromDraft))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }
}
"""

GESTURE_F = """package com.rikkaminis.app.ui.chat

@Composable
fun MinisSelectionToolbarHost(controller: SelectionController) {
    Row {
        MinisToolbarButton(label = "Copy") { controller.clearSelection() }
        val tableActions = controller.selectionTableActions()
        if (tableActions != null) {
            MinisToolbarButton(label = "Copy Table") { tableActions.copyTableMarkdown() }
            MinisToolbarButton(label = "Copy Table Image") { tableActions.copyTableImage() }
        }
    }
}
"""

SELECTION_F = """package com.rikkaminis.app.ui.chat

class SelectionController {
    class TableActions(
        val copyTableMarkdown: () -> Unit,
        val copyTableImage: () -> Unit,
    )
    private val tableActionsByMessage =
        androidx.compose.runtime.mutableStateMapOf<
            String,
            androidx.compose.runtime.snapshots.SnapshotStateMap<Int, TableActions>
        >()
    fun rememberTableActions(messageId: String, tableIndex: Int, actions: TableActions) {
        val perMessage = tableActionsByMessage.getOrPut(messageId) {
            androidx.compose.runtime.mutableStateMapOf()
        }
        perMessage[tableIndex] = actions
    }
    fun forgetTableActions(messageId: String, tableIndex: Int) {
        val perMessage = tableActionsByMessage[messageId] ?: return
        perMessage.remove(tableIndex)
        if (perMessage.isEmpty()) tableActionsByMessage.remove(messageId)
    }
    fun selectionTableActions(): TableActions? {
        val msgId = singleMessageId() ?: return null
        val perMessage = tableActionsByMessage[msgId] ?: return null
        for (endpoint in listOf(selection.value.start, selection.value.end)) {
            val tableIndex = tableIndexFromShard(endpoint.shard.shardId) ?: continue
            perMessage[tableIndex]?.let { return it }
        }
        return null
    }
}
"""

STREAM_F = """package com.rikkaminis.app.ui.chat

private const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000

internal object StreamingBlockLedger {
    data class Published(val blocks: List<MdBlock>, val source: String?)
    fun seed(blocks: List<MdBlock>?, raw: String): Published =
        Published(blocks.orEmpty(), if (blocks != null) raw else null)
    fun publish(computed: List<MdBlock>, raw: String): Published = Published(computed, raw)
    fun rendered(published: Published, seed: List<MdBlock>?): List<MdBlock> =
        if (seed != null && published.blocks !== seed) seed else published.blocks
    fun isConvergedWith(published: Published, raw: String, isStreaming: Boolean): Boolean =
        !isStreaming && published.source == raw
}

@Composable
private fun RenderTable(block: MdBlock.Table) {
    val selectionController = LocalMinisSelectionController.current
    val shardIdForTable = LocalShardId.current
    val messageId = shardIdForTable?.messageId
    val tableSlotAllocator = LocalShardSubIndexAllocator.current
    val tableIndex = remember { tableSlotAllocator?.next() ?: 0 }
    if (selectionController != null && messageId != null) {
        androidx.compose.runtime.DisposableEffect(selectionController, messageId, block.raw, tableIndex) {
            selectionController.rememberTableActions(messageId, tableIndex, actions)
            onDispose { selectionController.forgetTableActions(messageId, tableIndex) }
        }
    }
}

@Composable
private fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayContent = content
    val mdColors = currentMdColors()
    val warmSeed = remember(displayContent, isStreaming) {
        MarkdownParseCaches.cachedBlocks(displayContent)
    }
    var published by remember {
        mutableStateOf(StreamingBlockLedger.seed(warmSeed, displayContent))
    }
    val blocks = StreamingBlockLedger.rendered(published, warmSeed)
    LaunchedEffect(displayContent, isStreaming) {
        if (warmSeed != null) {
            published = StreamingBlockLedger.seed(warmSeed, displayContent)
            return@LaunchedEffect
        }
        if (StreamingBlockLedger.isConvergedWith(published, displayContent, isStreaming)) {
            if (displayContent.length >= COLD_PARSE_OFFMAIN_THRESHOLD_CHARS) {
                MarkdownParseCaches.putBlocks(displayContent, published.blocks)
            }
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(displayContent).also {
                MarkdownParseCaches.prewarm(it, mdColors)
                if (!isStreaming && displayContent.length >= COLD_PARSE_OFFMAIN_THRESHOLD_CHARS) {
                    MarkdownParseCaches.putBlocks(displayContent, it)
                }
            }
        }
        published = StreamingBlockLedger.publish(computed, displayContent)
    }
    Column(modifier = modifier) {
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block -> RenderBlock(block) }
    }
}
"""

MDTOOLBAR_F = """package com.rikkaminis.app.ui.chat

@Composable
fun MinisMarkdownTextToolbarHost(toolbar: Toolbar) {
    Row {
        ToolbarButton(label = "Copy") { toolbar.hide() }
        val tableActions = toolbar.selectionController?.selectionTableActions()
        if (tableActions != null) {
            ToolbarButton(label = "Copy Table") { tableActions.copyTableMarkdown() }
        }
    }
}

internal fun withSelection(sink: (String) -> Unit) {
    val cm = clipboard()
    val original = runCatching { cm.primaryClip }.getOrNull()
    try {
        onCopyRequested.invoke()
        val selection = runCatching {
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()?.trim().orEmpty()
        if (selection.isNotEmpty()) sink(selection)
    } finally {
        val snapshot = original
        runCatching {
            when (clipboardRestoreAction(
                hadOriginal = snapshot != null,
                atLeastQ = true,
            )) {
                ClipboardRestoreAction.RESTORE_ORIGINAL ->
                    snapshot?.let { cm.setPrimaryClip(it) }
                ClipboardRestoreAction.CLEAR_PLATFORM ->
                    cm.clearPrimaryClip()
                ClipboardRestoreAction.CLEAR_EMPTY_STRING ->
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                ClipboardRestoreAction.NOOP -> Unit
            }
        }
    }
}
"""


# ---------------------------------------------------------------------------
# F (dv20-B03): the composer clear is one funnel
# ---------------------------------------------------------------------------
CLEAR_WRITE_RE = re.compile(r"setInputText\s*\(\s*\"\"\s*\)")
FIELD_WRITE_RE = re.compile(r"\binputFieldValue\s*=")
CARET_RESET_RE = re.compile(r"\blastTrueCaretEnd\s*=\s*0\b")
BURST_DROP_RE = re.compile(
    r"cancelPendingImeBurst\s*\(\s*\)|imeBurstJob\s*\?\.\s*cancel\s*\(\)")
FUNNEL_LAMBDA_RE = re.compile(r"\bval\s+(\w+)\s*:\s*\(\)\s*->\s*Unit\s*=\s*\{")
FUNNEL_CONSUMERS = [
    (re.compile(r"\bviewModel\.sendMessage\s*\("), "send path"),
    (re.compile(r"\btryExecuteInputAsSlashCommand\s*\("), "slash-command branch"),
    (re.compile(r"\bstashPendingTransfer\s*\("), "cross-session move"),
    (re.compile(r"\bviewModel\.cancelEdit\s*\("), "exit-edit pill"),
]


def _innermost_brace_span(text, idx):
    """(open_idx, close_idx) of the innermost {...} containing [idx]."""
    depth = 0
    start = -1
    for i in range(idx):
        c = text[i]
        if c == "{":
            depth += 1
            start = i
        elif c == "}":
            depth -= 1
            if depth <= 0:
                start = -1
    if start == -1:
        return None
    d = 0
    for j in range(start, len(text)):
        if text[j] == "{":
            d += 1
        elif text[j] == "}":
            d -= 1
            if d == 0:
                return (start, j)
    return None


BODY_FUNNEL_RE = re.compile(
    r"\bval\s+(\w+)\s*:\s*\(\s*String\s*,\s*Int\??\s*\)\s*->\s*Unit\s*=\s*\{")
SLASH_OUTLET_RE = re.compile(
    r"\b(?:dismissSlashMenu|executeSlashCommand|showSlashMenuOverInput)\s*\(")
CARET_WRITE_RE = re.compile(r"\bsetInputText\s*\([^)]*caretOverride")


def composer_body_funnel_findings(inp_c):
    """[deep-audit-1049 B-06] Every slash / `@` body rewrite goes through the
    composer's own double-write funnel.

    The composer keeps the same text in two places: `inputFieldValue` (the
    BasicTextField's live value, written by onValueChange on every keystroke)
    and `viewModel.inputText` (debounced — a >8-char IME burst is buffered for
    150 ms). A slash / mention outlet that writes ONLY the ViewModel is therefore
    lossy in a way that is invisible until it is not:

      * inside the debounce window the field is AHEAD of the ViewModel, so
        `setInputText(dismissSlashMenu(field.text))` can compute a string equal
        to the ViewModel's current value — `MutableStateFlow` drops an equal
        write, the inputText→field mirror never re-runs, and the field keeps the
        slash prefix the user just dismissed;
      * a caret passed as `caretOverride` on such a write is consumed by an
        effect that may never run, leaving `_pendingCaret` to displace the
        caret on the *next* unrelated input.

    The funnel is the existing `commitComposerBody` (both copies + burst cancel);
    this criterion derives the outlets from the resolution calls they must make
    (`dismissSlashMenu` / `executeSlashCommand` / `showSlashMenuOverInput`) and
    from any caret-tagged write, so a NEW outlet is covered without editing the
    gate.
    """
    problems = []
    funnels = []
    for m in BODY_FUNNEL_RE.finditer(inp_c):
        # The lambda body starts AT the matched `{`; `_innermost_brace_span` is
        # exclusive of [idx] and would hand back the ENCLOSING block (the whole
        # composable), which makes every check below vacuously true.
        ob = m.end() - 1
        if inp_c[ob] != "{":
            ob = inp_c.find("{", m.end() - 1)
        cb = _matching_brace_end(inp_c, ob) if ob >= 0 else -1
        if cb > ob:
            funnels.append((m.group(1), (ob, cb)))
    if not funnels:
        return [("B06", "no composer body funnel (`val commitComposerBody: (String, Int?) -> Unit "
                         "= { … }`) — every slash/`@` rewrite would write one copy only "
                         "(fail-closed)")]
    name, (fob, fcb) = funnels[0]
    body = inp_c[fob: fcb + 1]
    for token, why in (
        (BURST_DROP_RE, "drops the pending IME burst (a buffered snapshot would "
                        "otherwise commit over the value it just wrote)"),
        ("setInputText(", "writes the ViewModel copy"),
        ("inputFieldValue =", "writes the field copy (the ViewModel write alone is "
                              "suppressed when the text is unchanged)"),
    ):
        hit = bool(token.search(body)) if hasattr(token, "search") else (token in body)
        if not hit:
            problems.append(("B06", "the composer body funnel `%s` does not %s" % (name, why)))

    def in_funnel(idx):
        return fob <= idx <= fcb

    outlets = 0
    for m in SLASH_OUTLET_RE.finditer(inp_c):
        if in_funnel(m.start()):
            continue
        outlets += 1
        span = _innermost_brace_span(inp_c, m.start())
        if span is None:
            problems.append(("B06", "%s() is called outside any block — cannot prove the "
                                    "rewrite goes through `%s`" % (m.group(0)[:-1],
                                                                   name)))
            continue
        block = inp_c[span[0]: span[1] + 1]
        if SET_INPUT_RE.search(block) and not re.search(r"\b%s\s*\(" % re.escape(name), block):
            problems.append(("B06", "a slash outlet writes `setInputText` directly instead of "
                                    "`%s` — an equal write is dropped by the StateFlow and the "
                                    "field keeps the pre-command text" % name))
        elif not re.search(r"\b%s\s*\(" % re.escape(name), block):
            problems.append(("B06", "a slash outlet does not route its rewrite through `%s` "
                                    "(the block never calls it)" % name))
    for m in CARET_WRITE_RE.finditer(inp_c):
        if in_funnel(m.start()):
            continue
        problems.append(("B06", "a caret-tagged `setInputText(…, caretOverride = …)` bypasses "
                                "`%s` — the override rides an effect that may not run, and "
                                "`_pendingCaret` then displaces the next caret" % name))
    if outlets == 0:
        problems.append(("B06", "no slash outlet found in the composer — the resolution calls "
                                "moved, re-point this criterion (fail-closed)"))
    return problems


def composer_clear_funnel_findings(inp_c):
    """Every composer-clear routes through one shared funnel (dv20-B03).

    The clear entrances used to commit an empty string on their own. That write
    is suppressed by the ViewModel's StateFlow (equal values do not emit), so
    the inputText mirror never re-runs and the composer keeps the text the user
    just sent. The funnel commits the empty string AND resets inputFieldValue +
    lastTrueCaretEnd, and drops the pending IME burst so a still-alive 150ms
    flush cannot resurrect the sent text.
    """
    problems = []
    clears = list(CLEAR_WRITE_RE.finditer(inp_c))
    if not clears:
        problems.append(("F", "no composer-clear write found in ChatInputArea.kt "
                              "-- the external-clear anchor moved, re-point this guard"))
        return problems
    funnels = []
    for m in FUNNEL_LAMBDA_RE.finditer(inp_c):
        ob = inp_c.find("{", m.end() - 1)
        if ob < 0:
            continue
        # ob+1: count the lambda's own opening brace, so the span IS the lambda
        # body (passing ob would return the enclosing block instead)
        span = _innermost_brace_span(inp_c, ob + 1)
        if span is None:
            continue
        b0, b1 = span
        if any(b0 <= c.start() < b1 for c in clears):
            funnels.append((m.group(1), b0, b1))
    if not funnels:
        problems.append(("F", "%d composer-clear write(s) commit the empty string on "
                              "their own with no shared clear funnel -- the field reset "
                              "drifts out of the entrances" % len(clears)))
        return problems
    if len(funnels) > 1:
        problems.append(("F", "%d separate composer-clear funnels (%s) -- two clear "
                              "definitions will drift apart, keep one"
                              % (len(funnels), ", ".join(f[0] for f in funnels))))
        return problems
    fname, f0, f1 = funnels[0]
    body = inp_c[f0:f1 + 1]
    if not FIELD_WRITE_RE.search(body):
        problems.append(("F1", "the clear funnel commits the empty string but never "
                               "resets inputFieldValue -- the VM write is suppressed "
                               "when the string is already empty, so the field keeps "
                               "the text that was just sent"))
    if not CARET_RESET_RE.search(body):
        problems.append(("F1", "the clear funnel never resets lastTrueCaretEnd -- a "
                               "stale caret index survives the clear and lands in the "
                               "next draft"))
    if not BURST_DROP_RE.search(body):
        problems.append(("F1", "the clear funnel does not drop the pending IME burst "
                               "-- a still-alive 150ms flush commits the snapshot "
                               "after the clear and resurrects the sent text"))
    for c in clears:
        if not (f0 <= c.start() <= f1):
            line = inp_c.count("\n", 0, c.start()) + 1
            problems.append(("F2", "a composer-clear at line %d commits the empty "
                                   "string outside the clear funnel -- the field reset "
                                   "is bypassed for this entrance" % line))
    for m in re.finditer(r"\b%s\s*\(\s*\)" % re.escape(fname), inp_c):
        if m.start() < f0:
            line = inp_c.count("\n", 0, m.start()) + 1
            problems.append(("F3", "%s is called at line %d before its declaration -- "
                                   "a local val lambda cannot be referenced before its "
                                   "declaration" % (fname, line)))
    # Direction + same-path rules, measured on the tree this guard was written
    # against. Block containment alone was too coarse for the send path: the
    # enclosing lambda also holds the slash-command branch's funnel call, so a
    # send that skipped the funnel still sat "inside a block that routes through
    # it" (found by the FIX-B3 mutation run, m5). What the defect actually breaks
    # is the *sequence on one path*, so each anchor is checked in its measured
    # direction and must not cross a block boundary or a return.
    FUNNEL_RULES = {
        "send path": ("before", 200),
        "cross-session move": ("after", 400),
        "exit-edit pill": ("after", 200),
    }
    BOUNDARY_RE = re.compile(r"[{}]|\breturn@")
    call_re = re.compile(r"\b%s\s*\(\s*\)" % re.escape(fname))
    calls = [m.start() for m in call_re.finditer(inp_c)]
    for anchor, label in FUNNEL_CONSUMERS:
        hits = list(anchor.finditer(inp_c))
        if not hits:
            problems.append(("F4", "the %s anchor moved -- re-point this guard" % label))
            continue
        for h in hits:
            if label == "slash-command branch":
                # the funnel must be the FIRST statement of the if-body
                p = inp_c.find("(", h.end() - 1)
                close = -1
                if p >= 0:
                    depth = 0
                    for j in range(p, min(len(inp_c), p + 2000)):
                        if inp_c[j] == "(":
                            depth += 1
                        elif inp_c[j] == ")":
                            depth -= 1
                            if depth == 0:
                                close = j
                                break
                if close < 0:
                    problems.append(("F4", "cannot locate the %s condition -- re-point"
                                           % label))
                    continue
                # the call sits inside an `if (...)` condition, so the run of
                # closing parens that ends the enclosing guard sits between the
                # call's `)` and the body's `{` -- skip it (measured shape:
                # `if (viewModel.tryExecuteInputAsSlashCommand(x)) {`)
                j = close + 1
                while j < len(inp_c) and inp_c[j] in ") \t\r\n":
                    j += 1
                if inp_c[j:j + 1] != "{":
                    problems.append(("F4", "the %s has no brace body -- re-point"
                                           % label))
                    continue
                ok = call_re.match(inp_c[j + 1:].lstrip()) is not None
                if not ok:
                    line = inp_c.count("\n", 0, h.start()) + 1
                    problems.append(("F4", "the %s at line %d acts on the composer "
                                           "without routing through %s as the first "
                                           "statement of its body -- the field reset is "
                                           "bypassed" % (label, line, fname)))
                continue
            direction, reach = FUNNEL_RULES[label]
            if direction == "before":
                prev = [c for c in calls if c < h.start()]
                ok = (bool(prev) and h.start() - prev[-1] < reach
                      and not BOUNDARY_RE.search(inp_c[prev[-1]:h.start()]))
            else:
                nxt = [c for c in calls if c > h.end()]
                ok = (bool(nxt) and nxt[0] - h.end() < reach
                      and not BOUNDARY_RE.search(inp_c[h.end():nxt[0]]))
            if not ok:
                line = inp_c.count("\n", 0, h.start()) + 1
                problems.append(("F4", "the %s at line %d acts on the composer without "
                                       "routing through %s on the same path -- the field "
                                       "reset is bypassed"
                                       % (label, line, fname)))
    return problems


def _guard_before_brace(text, ob):
    """The `if (...)` condition whose body opens at [ob], or None when the brace
    opens a bare block / lambda."""
    j = ob - 1
    while j >= 0 and text[j] in " \t\r\n":
        j -= 1
    if j < 0 or text[j] != ")":
        return None
    depth = 0
    k = j
    while k >= 0:
        if text[k] == ")":
            depth += 1
        elif text[k] == "(":
            depth -= 1
            if depth == 0:
                break
        k -= 1
    if k < 0:
        return None
    if not text[max(0, k - 8):k].rstrip().endswith("if"):
        return None
    return text[k + 1:j]


def _enclosing_condition_chain(text, idx, floor):
    """Every `if (...)` condition on the path from [idx] out to [floor].

    A deposit nested inside two conditions (stream ended AND long enough) is
    governed by BOTH — reading only the innermost one would report a missing
    `isStreaming` guard on a correctly guarded deposit, and reading only the
    outermost would let a source check sit on a branch that cannot reach it.
    Anchoring on enclosing BLOCKS (not on a backward scan for the token `if`)
    keeps a nested lambda's own parentheses out of the reading."""
    conds = []
    cur = idx
    while cur > floor:
        span = _innermost_brace_span(text, cur)
        if span is None or span[0] <= floor:
            break
        cond = _guard_before_brace(text, span[0])
        if cond is not None:
            conds.append(cond)
        cur = span[0] - 1
    return conds


BLOCK_STATE_PAIR_RE = re.compile(
    r"mutableStateOf\s*\(\s*[\w.]+\(\s*[^()]*?\b(displayContent|content)\b\s*\)")
BARE_LIST_STATE_RE = re.compile(r"mutableStateOf\s*<\s*List\s*<\s*MdBlock\s*>")
CACHE_PEEK_RE = re.compile(r"\.cachedBlocks\s*\(")


def streaming_block_ledger_findings(stream_c):
    """[dv21-B4-B05] The streaming body's block state must be a (blocks, source)
    ledger, and the frame where a stream CONVERGES must both re-enter the
    publishing effect and deposit through a source-checked guard.

    Two field-visible defects came out of the bare `List<MdBlock>` in an unkeyed
    `remember`:

      (a) a body REUSED for another message drew the outgoing message's blocks
          for one frame, then re-parsed a list the cache already had (the
          identity check `blocks === warmSeed` compared against a list it had
          thrown away, so a HIT was recorded as FROZEN_MISS);
      (b) the convergence frame never deposited — the deposit is gated on
          `!isStreaming`, a coroutine already running keeps the `true` it
          captured, and the effect's only key (`displayContent`) had already
          reached its final value — so every long streamed answer stayed out of
          blocksLru and was re-parsed on each scroll-back.

    Four decisions, not four spellings:

      H1  the cache-seeded `remember` is keyed on the streaming flag AS WELL AS
          the content (a converged frame must re-read a seed it skipped while
          live);
      H2  the block state carries the text it was parsed from (no bare
          `List<MdBlock>` state; the initializer pairs blocks with the content);
      H3  the publishing `LaunchedEffect` is keyed on the streaming flag too, or
          the convergence frame never runs at all;
      H4  a deposit whose list was NOT freshly parsed must consult that list's
          ledger in its guard — caching a prefix that a cancelled parse left
          behind is a durable truncated render, worse than a cold cache.

    H4 is deliberately keyed on "the guard names the state that owns the
    deposited list": a refactor that renames the ledger must keep that property
    (the guard consults the source), but is free to rename the helper.
    """
    problems = []

    # Scope: the ACTIVE renderer only. `MarkdownDocument` / `MarkdownBlockBody`
    # keep their own bare `List<MdBlock>` state and are RUNTIME-DEAD while
    # AGGREGATE_MESSAGE_ITEMS is true (the audit's own 失真 #1: this gate must
    # not demand a change there — the barrier is deliberate).
    body = declaration_slice(stream_c, "fun StreamingMarkdownTextBody(")
    body_off = stream_c.find(body)
    if not body:
        problems.append(("H", "`StreamingMarkdownTextBody` not found — the streaming "
                              "renderer this family pins moved, re-point this guard "
                              "(fail-closed)"))
        return problems

    seed_keys = None
    for m in re.finditer(r"\bremember\s*\(", body):
        op = m.end() - 1
        cl = _balanced_paren(body, op)
        if cl is None:
            continue
        window = body[cl:cl + 500]
        if not CACHE_PEEK_RE.search(window):
            continue
        seed_keys = body[op + 1:cl]
        break
    if seed_keys is None:
        problems.append(("H1", "no `remember { … cachedBlocks(…) }` seed found — the "
                               "warm-cache seed anchor moved, re-point this guard"))
    else:
        if "isStreaming" not in seed_keys:
            problems.append(("H1", "the warm-cache seed is not keyed on the streaming "
                                   "flag (keys: %r) — a stream that converges keeps the "
                                   "`null` seed cached from its live frames, so the "
                                   "final frame misses a cache that should hit" % seed_keys.strip()))
        if not re.search(r"\b(displayContent|content)\b", seed_keys):
            problems.append(("H1", "the warm-cache seed is not keyed on the content — "
                                   "a reused body would keep the previous message's seed"))

    # ── H2: the block state carries its source text ─────────────────────────
    if BARE_LIST_STATE_RE.search(body):
        problems.append(("H2", "the block state is still a bare `List<MdBlock>` — "
                               "without the text it was parsed from, a convergence "
                               "deposit cannot tell its own content from a cancelled "
                               "prefix, and a reused body cannot tell its own message "
                               "from the previous one"))
    elif not BLOCK_STATE_PAIR_RE.search(body):
        problems.append(("H2", "no block state pairs its blocks with the content it "
                               "was parsed from (expected `<ledger>(<blocks>, "
                               "<content>)` in the state initializer) — re-point this "
                               "guard if the pairing moved"))

    # ── H3/H4: the publishing effect re-enters, and every deposit is guarded ─
    effect_body = None
    effect_keys = None
    for m in re.finditer(r"\bLaunchedEffect\s*\(", body):
        op = m.end() - 1
        cl = _balanced_paren(body, op)
        if cl is None:
            continue
        tail = body[cl:cl + 3000]
        if ".putBlocks(" not in tail:
            continue
        effect_keys = body[op + 1:cl]
        body_start = body.find("{", cl)
        span = _innermost_brace_span(body, body_start + 1) if body_start >= 0 else None
        effect_body = body[body_start:span[1] + 1] if span else None
        effect_body_off = body_start
        break
    if effect_body is None:
        problems.append(("H3", "no publishing `LaunchedEffect` that reaches "
                               "putBlocks( — the deposit anchor moved, re-point this "
                               "guard"))
        return problems
    if "isStreaming" not in (effect_keys or ""):
        problems.append(("H3", "the publishing effect is not keyed on the streaming "
                               "flag (keys: %r) — `displayContent` reaches its final "
                               "value one throttle tick before the stream ends, so the "
                               "convergence frame never re-enters and nothing is ever "
                               "deposited" % (effect_keys or "").strip()))
    if not re.search(r"\b(displayContent|content)\b", effect_keys or ""):
        problems.append(("H3", "the publishing effect is not keyed on the content — "
                               "a reused body would keep the previous message's blocks"))

    floor = effect_body.find("{")
    for m in re.finditer(r"\.putBlocks\s*\(", effect_body):
        conds = _enclosing_condition_chain(effect_body, m.start(), floor)
        cond = " && ".join(conds)
        line = body_off + body.count("\n", 0, effect_body_off + m.start()) + 1
        if not conds:
            problems.append(("H4", "the deposit at line %d has no enclosing condition — "
                                   "it caches whatever is in hand, so an empty or "
                                   "stale block list can become the content's cached "
                                   "parse" % line))
            continue
        if "isStreaming" not in cond:
            problems.append(("H4", "the deposit at line %d is not gated on the stream "
                                   "having ended — caching a half-parsed prefix under "
                                   "the final content's key renders a truncated message "
                                   "from then on" % line))
            continue
        if not re.search(r"\b(displayContent|content)\b", cond):
            problems.append(("H4", "the deposit at line %d does not name the content it "
                                   "is being cached under" % line))
            continue
        # The deposited list: `putBlocks(<content>, <list>)`.
        op = effect_body.find("(", m.start())
        args = call_arg_spans(effect_body, op) or []
        if len(args) < 2:
            problems.append(("H4", "the deposit at line %d does not pass a block list "
                                   "— fail-closed" % line))
            continue
        deposited = args[1]
        if deposited in ("it", "computed", "parsed", "blocks"):
            continue  # a list parsed from the content in hand — safe by construction
        owner = re.match(r"\s*(\w+)", deposited)
        if owner is None or owner.group(1) not in cond:
            problems.append(("H4", "the deposit at line %d caches `%s`, a list this "
                                   "frame did not parse, but its guard never consults "
                                   "that list's source — a parse cancelled by a newer "
                                   "chunk leaves a prefix behind, and caching it is a "
                                   "durable truncated render" % (line, deposited.strip()[:40])))
    return problems


# [dv23-B3] The fixture tree's ChatScreen: every composer intent is ROUTED
# through the channel, and the screen never touches the composer's text.
SCREEN_F = """package com.rikkaminis.app.ui.chat

@Composable
fun ChatScreen(viewModel: ChatViewModel, onOpenSettings: () -> Unit) {
    MenuAction(
        onPick = { pref ->
            when (pref) {
                ChatMenuPrefs.SLASH_COMMANDS -> {
                    viewModel.requestComposerCommand(ComposerCommand.ToggleSlashMenu)
                }
                ChatMenuPrefs.EXPORT -> onOpenSettings()
            }
        },
    )
    ChatList(
        onTap = {
            if (slashMenuOpen) {
                viewModel.requestComposerCommand(ComposerCommand.DismissSlashMenu)
            }
        },
    )
    BackHandler(enabled = true) {
        when {
            slashMenuOpen -> {
                viewModel.requestComposerCommand(ComposerCommand.DismissSlashMenu)
            }
            else -> Unit
        }
    }
    ChatInputArea()
    ConfirmDialog(
        onConfirm = {
            viewModel.clearChat()
            viewModel.requestComposerCommand(ComposerCommand.Clear)
        },
    )
}
"""

# [dv23-B3] INPUT_F plus the composer-side consumer of the intent channel:
# the block that makes G3/G4 observable. Appended (not substituted) so the
# A-F criteria keep looking at exactly the same text they always did.
INPUT_G = INPUT_F + """
@Composable
private fun ComposerIntentHost(viewModel: ChatViewModel) {
    LaunchedEffect(Unit) {
        viewModel.composerCommand.collect { command ->
            when (command) {
                ComposerCommand.ToggleSlashMenu -> {
                    cancelPendingImeBurst()
                    commitComposerBody(viewModel.showSlashMenuOverInput(inputFieldValue.text), null)
                }
                ComposerCommand.DismissSlashMenu -> {
                    cancelPendingImeBurst()
                    commitComposerBody(viewModel.dismissSlashMenu(inputFieldValue.text), null)
                }
                ComposerCommand.Clear -> clearComposerInput()
            }
        }
    }
}
"""


# [dv24/B-05] The ViewModel half of the handoff: the intent set and the two
# entrances that used to resolve the composer text themselves.
VM_F = """package com.rikkaminis.app.ui.chat

class ChatViewModel {
    // [dv25-B05] the compliant shape: the flow IS the global one.
    val fastModeEnabled: StateFlow<Boolean> = com.rikkaminis.app.data.FastModePrefs.enabled

    fun appendToInputText(snippet: String) {
        val cleaned = snippet.trim()
        if (cleaned.isEmpty()) return
        requestComposerCommand(
            ComposerCommand.AppendText(text = cleaned, separator = " "),
        )
    }

    fun dismissContextExhaustedDialog(restoreInput: Boolean) {
        _showContextExhaustedDialog.value = false
        if (restoreInput) {
            requestComposerCommand(ComposerCommand.ReplaceText(text = pendingExhaustedText))
        }
    }

    fun requestComposerCommand(command: ComposerCommand) {
        _composerCommand.tryEmit(command)
    }
}

sealed interface ComposerCommand {
    data object ToggleSlashMenu : ComposerCommand
    data object DismissSlashMenu : ComposerCommand
    data object Clear : ComposerCommand
    data class ReplaceText(val text: String, val caretOverride: Int? = null) : ComposerCommand
    data class AppendText(
        val text: String,
        val separator: String = " ",
        val trimExistingEnd: Boolean = true,
        val trailingSpace: Boolean = true,
    ) : ComposerCommand
}
"""

# the composer-side arms for exactly those five intents (INPUT_G grew the two
# text arms with the same commit that declared them).
INPUT_H = INPUT_G.replace(
    """                ComposerCommand.Clear -> clearComposerInput()""",
    """                ComposerCommand.Clear -> clearComposerInput()
                is ComposerCommand.ReplaceText -> {
                    cancelPendingImeBurst()
                    commitComposerBody(command.text, composerCaretFor(command.text, command.caretOverride))
                }
                is ComposerCommand.AppendText -> {
                    val next = composeAppend(inputFieldValue.text, command.text)
                    commitComposerBody(next, next.length)
                }""")

# [dv25-B05] the global source of the Fast Mode flag.
FASTMODE_F = """package com.rikkaminis.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FastModePrefs {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
    }
}
"""

def fixtures(**overrides):
    files = {
        MODELS_REL: MODELS_F,
        TOKEN_REL: TOKEN_F,
        INPUT_REL: INPUT_F,
        GESTURE_REL: GESTURE_F,
        SELECTION_REL: SELECTION_F,
        STREAM_REL: STREAM_F,
        MDTOOLBAR_REL: MDTOOLBAR_F,
        # [dv22-B4] the D5/D6 sibling sources: present by default so the
        # selftest tree mirrors the REAL tree shape; every D5/D6 breaker
        # below mutates exactly one of them.
        SLASH_EXT_REL: SLASHEXT_F,
        SKILL_REPO_REL: SKILLREPO_F,
        MCP_REPO_REL: MCPREPO_F,
        # [dv25-B05] the global Fast Mode source.
        FASTMODE_REL: FASTMODE_F,
    }
    files.update(overrides)
    # An override of None removes the file from the tree entirely — the
    # "optional sibling read absent" fixture shape.
    return {k: v for k, v in files.items() if v is not None}


def selftest():
    failures = []
    # counted rather than hard-coded: the number drifted silently every time a
    # family grew (the message said 47 while the file ran more).
    arms = [0]

    def expect(name, files, want_violation_ids):
        arms[0] += 1
        root = tempfile.mkdtemp(prefix="chat-ui-gate-selftest-")
        try:
            for rel, content in files.items():
                path = os.path.join(root, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(content)
            _, problems = check_tree(root)
            got = {cid for cid, _ in problems}
            if want_violation_ids is None:
                if problems:
                    failures.append("%s: expected CLEAN, got %s" % (name, sorted(got)))
            else:
                missing = [v for v in want_violation_ids
                           if not any(v in cid for cid in got)]
                if missing:
                    failures.append("%s: expected %s, got %s" %
                                    (name, want_violation_ids, sorted(got)))
        finally:
            shutil.rmtree(root)

    def expect_exact(name, files, want_violation_ids):
        """Stricter than expect(): the violation-id SET must match exactly, so a
        fixture cannot pass by tripping an unrelated criterion (the "only this
        one turns red" proof each new criterion owes)."""
        arms[0] += 1
        root = tempfile.mkdtemp(prefix="chat-ui-gate-selftest-")
        try:
            for rel, content in files.items():
                path = os.path.join(root, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(content)
            _, problems = check_tree(root)
            got = {cid for cid, _ in problems}
            want = set(want_violation_ids)
            if got != want:
                failures.append("%s: expected exactly %s, got %s"
                                % (name, sorted(want), sorted(got)))
        finally:
            shutil.rmtree(root)

    # clean fixture → exit 0
    expect("clean", fixtures(), None)
    # ── H1-H3 (dv25-B05): the Fast Mode toggle has ONE source ──────────────
    # The H family reads ChatViewModel.kt, so these arms ship the ViewModel
    # fixture too (and the composer fixture that satisfies the G6/G7 criteria
    # it shares the tree with — otherwise the arm would trip an unrelated
    # criterion and the "only this one turns red" proof would be worthless).
    h_base = {VM_REL: VM_F, INPUT_REL: INPUT_H}
    # H1: the pre-fix construction-time snapshot of the global flag.
    expect("H1 local fast-mode snapshot", fixtures(**dict(h_base, **{
        VM_REL: VM_F.replace(
            "    val fastModeEnabled: StateFlow<Boolean> = com.rikkaminis.app.data.FastModePrefs.enabled\n",
            "    private val _fastModeEnabled = MutableStateFlow(com.rikkaminis.app.data.FastModePrefs.isEnabled())\n"
            "    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()\n"),
    })), ["H1"])
    # H2: a second, locally-owned copy of the same value.
    expect("H2 fast-mode not sourced from the global flow", fixtures(**dict(h_base, **{
        VM_REL: VM_F.replace(
            "    val fastModeEnabled: StateFlow<Boolean> = com.rikkaminis.app.data.FastModePrefs.enabled\n",
            "    private val _fastModeEnabled = MutableStateFlow(false)\n"
            "    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()\n"),
    })), ["H2"])
    # H2 fail-closed: the global object exposes no observable flow at all.
    expect("H2 no global fast-mode flow", fixtures(**dict(h_base, **{
        FASTMODE_REL: FASTMODE_F.replace(
            "    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()\n", ""),
    })), ["H2"])
    # A1: a written-but-never-read boolean flips red (the B-01 class, generalized)
    expect("A1 unread boolean", fixtures(**{
        MODELS_REL: MODELS_F.replace(
            "    val isMcp: Boolean = false,\n",
            "    val isMcp: Boolean = false,\n    val isZombie: Boolean = false,\n"),
    }), ["A1"])
    # A2: dropping the isMcp branch flips red
    expect("A2 no mcp branch", fixtures(**{
        TOKEN_REL: TOKEN_F.replace(
            "    if (cmd.isMcp) {\n        resetSlashMenuState()\n"
            "        return fillComposerWithSlashTitle(cmd, saved)\n    }\n", ""),
    }), ["A2"])
    # ── [wide3-07] the three external witnesses of this gate ───────────────
    # A1 parser-death: a call-shaped default (`String()`) truncates the
    # parameter list, so every boolean declared AFTER it escapes the scan while
    # `isSkill` keeps the fail-closed check quiet.
    expect_exact("A1 call-shaped default truncates the field list", fixtures(**{
        MODELS_REL: MODELS_F.replace(
            "    val isSkill: Boolean = false,\n",
            "    val isSkill: Boolean = false,\n"
            "    val tag: String = String(),\n"
            "    val isUnreadFlag: Boolean = false,\n"),
    }), ["A1:isUnreadFlag"])
    # A2 dataflow: the branch keeps the call, the decision is short-circuited.
    expect("A2 dead-guarded mcp return", fixtures(**{
        TOKEN_REL: TOKEN_F.replace(
            "    if (cmd.isMcp) {\n        resetSlashMenuState()\n"
            "        return fillComposerWithSlashTitle(cmd, saved)\n    }\n",
            "    if (cmd.isMcp) {\n"
            "        if (false) return fillComposerWithSlashTitle(cmd, saved)\n    }\n"),
    }), ["A2"])
    # C1 dataflow: the field-value spelling survives, the live read and the
    # attachment polarity and the OR decision all go away.
    expect("C1 wrapper reads the debounced string", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val canSwipeSendNow: () -> Boolean = {\n"
            "        inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()\n"
            "    }\n",
            "    val canSwipeSendNow: () -> Boolean = {\n"
            "        inputFieldValue.let { inputText.isNotEmpty() && "
            "(false || attachments.isEmpty()) }\n"
            "    }\n"),
    }), ["C1"])
    # B1: dropping the main-chat wiring flips red
    expect("B1 gesture call dropped", fixtures(**{
        GESTURE_REL: GESTURE_F.replace(
            "        val tableActions = controller.selectionTableActions()\n"
            "        if (tableActions != null) {\n"
            "            MinisToolbarButton(label = \"Copy Table\") { tableActions.copyTableMarkdown() }\n"
            "            MinisToolbarButton(label = \"Copy Table Image\") { tableActions.copyTableImage() }\n"
            "        }\n", ""),
    }), ["B1"])
    # ── C-CLIP-1 (dv27 B-07) ────────────────────────────────────────────
    # The borrowed clipboard must ALWAYS be handed back, including the case
    # where there was nothing to hand back. Each breaker below mutates exactly
    # one property of the restore block and must flip exactly C-CLIP-1.
    CLIP_GOOD = (
        "    } finally {\n"
        "        val snapshot = original\n"
        "        runCatching {\n"
        "            when (clipboardRestoreAction(\n"
        "                hadOriginal = snapshot != null,\n"
        "                atLeastQ = true,\n"
        "            )) {\n"
        "                ClipboardRestoreAction.RESTORE_ORIGINAL ->\n"
        "                    snapshot?.let { cm.setPrimaryClip(it) }\n"
        "                ClipboardRestoreAction.CLEAR_PLATFORM ->\n"
        "                    cm.clearPrimaryClip()\n"
        "                ClipboardRestoreAction.CLEAR_EMPTY_STRING ->\n"
        "                    cm.setPrimaryClip(ClipData.newPlainText(\"\", \"\"))\n"
        "                ClipboardRestoreAction.NOOP -> Unit\n"
        "            }\n"
        "        }\n"
        "    }\n"
    )
    # breaker 1: the old `if (original != null)` shape — the empty-clipboard
    # case is skipped again (restore + clear present but NOT exclusive).
    expect_exact("C-CLIP-1 old if(original != null) shape", fixtures(**{
        MDTOOLBAR_REL: MDTOOLBAR_F.replace(CLIP_GOOD,
            "    } finally {\n"
            "        if (original != null) runCatching { cm.setPrimaryClip(original) }\n"
            "    }\n"),
    }), ["C-CLIP-1"])
    # breaker 2: no clear action at all — the borrowed text stays in the
    # clipboard for a user who had an empty one.
    expect_exact("C-CLIP-1 clear action removed", fixtures(**{
        MDTOOLBAR_REL: MDTOOLBAR_F.replace(CLIP_GOOD,
            "    } finally {\n"
            "        val snapshot = original\n"
            "        runCatching {\n"
            "            when (clipboardRestoreAction(\n"
            "                hadOriginal = snapshot != null,\n"
            "                atLeastQ = true,\n"
            "            )) {\n"
            "                ClipboardRestoreAction.RESTORE_ORIGINAL ->\n"
            "                    snapshot?.let { cm.setPrimaryClip(it) }\n"
            "                ClipboardRestoreAction.CLEAR_PLATFORM -> Unit\n"
            "                ClipboardRestoreAction.CLEAR_EMPTY_STRING -> Unit\n"
            "                ClipboardRestoreAction.NOOP -> Unit\n"
            "            }\n"
            "        }\n"
            "    }\n"),
    }), ["C-CLIP-1"])
    # breaker 3: no restore action at all — the user's own clip is lost.
    expect_exact("C-CLIP-1 restore action removed", fixtures(**{
        MDTOOLBAR_REL: MDTOOLBAR_F.replace(CLIP_GOOD,
            "    } finally {\n"
            "        runCatching {\n"
            "            when (clipboardRestoreAction(\n"
            "                hadOriginal = snapshot != null,\n"
            "                atLeastQ = true,\n"
            "            )) {\n"
            "                ClipboardRestoreAction.RESTORE_ORIGINAL -> Unit\n"
            "                ClipboardRestoreAction.CLEAR_PLATFORM ->\n"
            "                    cm.clearPrimaryClip()\n"
            "                ClipboardRestoreAction.CLEAR_EMPTY_STRING -> Unit\n"
            "                ClipboardRestoreAction.NOOP -> Unit\n"
            "            }\n"
            "        }\n"
            "    }\n"),
    }), ["C-CLIP-1"])
    # breaker 4: the whole finally is gone — the borrow is never returned.
    expect_exact("C-CLIP-1 finally removed", fixtures(**{
        MDTOOLBAR_REL: MDTOOLBAR_F.replace(CLIP_GOOD, "    }\n"),
    }), ["C-CLIP-1"])

    # B2: deleting the markdown-toolbar consumer flips red
    expect("B2 mdtoolbar consumer dropped", fixtures(**{
        MDTOOLBAR_REL: MDTOOLBAR_F.replace(
            "        val tableActions = toolbar.selectionController?.selectionTableActions()\n"
            "        if (tableActions != null) {\n"
            "            ToolbarButton(label = \"Copy Table\") { tableActions.copyTableMarkdown() }\n"
            "        }\n", ""),
    }), ["B2"])
    # B3: reverting the key to a bare messageId flips red
    expect("B3 key reverted", fixtures(**{
        SELECTION_REL: SELECTION_F.replace(
            "    fun rememberTableActions(messageId: String, tableIndex: Int, actions: TableActions) {",
            "    fun rememberTableActions(messageId: String, actions: TableActions) {").replace(
            "    fun forgetTableActions(messageId: String, tableIndex: Int) {",
            "    fun forgetTableActions(messageId: String) {"),
    }), ["B3"])
    # B5: publishing without the table index flips red
    expect("B5 bare-messageId publish", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "selectionController.rememberTableActions(messageId, tableIndex, actions)",
            "selectionController.rememberTableActions(messageId, actions)"),
    }), ["B5"])
    # C3: restoring the dead-branch shape flips red
    expect("C3 dead branch", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "                if (swipedUp && canSwipeSendNow()) {\n"
            "                    if (sendSwipeProgress >= swipeArmFraction) {",
            "                if (swipedUp && canSwipeSendNow()) {\n"
            "                    val canSendNow = canSwipeSendNow() || attachments.isNotEmpty()\n"
            "                    if (sendSwipeProgress >= swipeArmFraction) {"),
    }), ["C3"])
    # C3 (baseline shape): the exact defect form — guard on a bare boolean whose
    # body re-derives eligibility from that boolean
    expect("C3 baseline dead branch", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val canSwipeSendNow: () -> Boolean = {\n"
            "        inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()\n"
            "    }\n", "")
            .replace(
            "                        if (canSwipeSendNow()) {",
            "                        val hasText = inputText.isNotBlank()\n"
            "                        if (hasText) {")
            .replace(
            "                if (swipedUp && canSwipeSendNow()) {\n"
            "                    if (sendSwipeProgress >= swipeArmFraction) {",
            "                val hasText = inputText.isNotBlank()\n"
            "                if (swipedUp && hasText) {\n"
            "                    val canSendNow = hasText || attachmentsCount > 0\n"
            "                    if (sendSwipeProgress >= swipeArmFraction) {")
            .replace(
            "                } else if (swipedUp && !canSwipeSendNow() && !inputFocused) {",
            "                } else if (swipedUp && !hasText && !inputFocused) {"),
    }), ["C1", "C3"])
    # C4: plain val capture of the threshold flips red
    expect("C4 frozen threshold", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val swipeThresholdPxState = rememberUpdatedState(\n"
            "        with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() },\n"
            "    )\n",
            "    val swipeThresholdPxState = with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() }\n"),
    }), ["C4"])
    # C5: dropping the enqueue route flips red
    expect("C5 enqueue dropped", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "                        performSendOrEnqueue()\n", ""),
    }), ["C5"])
    # C6: dropping the keyboard shortcut flips red
    expect("C6 keyboard shortcut dropped", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "                } else if (swipedUp && !canSwipeSendNow() && !inputFocused) {",
            "                } else if (swipedUp && !canSwipeSendNow()) {"),
    }), ["C6"])

    # ── D family (dv17-4 / dv22-B4) ─────────────────────────────────────────
    # D1: the dead thinkingLevel key is back
    expect("D1 dead thinkingLevel key", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            viewModel.memoryEnabled.collectAsState().value,\n",
            "            viewModel.memoryEnabled.collectAsState().value,\n"
            "            viewModel.thinkingLevel.collectAsState().value,\n"),
    }), ["D1"])
    # D2: session scope dropped from the producer keys
    expect("D2 session scope dropped", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            viewModel.currentSessionId,\n", ""),
    }), ["D2"])
    # D2: the two live repository lists dropped from the producer keys
    expect("D2 repository lists dropped", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            viewModel.skillRepository?.skills?.collectAsState()?.value,\n"
            "            viewModel.mcpRepository?.servers?.collectAsState()?.value,\n", ""),
    }), ["D2"])
    # D3: the guard condition removed (menu closed still launches the
    # producer and pays the query burst)
    expect("D3 ungated producer", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (showSlashMenu) {\n",
            "    val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (true) {\n"),
    }), ["D3"])
    # D-memo: the full revert to the dv17-4 synchronous remember shape — the
    # payload holder disappears, the gate must fail closed on the anchor
    # (not silently pass the old spelling)
    expect("D-memo full revert to the sync remember shape", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (showSlashMenu) {\n"
            "        produceState(\n"
            "            initialValue = SlashMenuPayload(),\n"
            "            viewModel.slashFilter.collectAsState().value,\n"
            "            viewModel.memoryEnabled.collectAsState().value,\n"
            "            viewModel.currentSessionId,\n"
            "            viewModel.skillRepository?.skills?.collectAsState()?.value,\n"
            "            viewModel.mcpRepository?.servers?.collectAsState()?.value,\n"
            "            viewModel.skillRepository?.sessionOverridesRevision?.collectAsState()?.value,\n"
            "            viewModel.mcpRepository?.sessionOverridesRevision?.collectAsState()?.value,\n"
            "        ) {\n"
            "            withContext(Dispatchers.IO) {\n"
            "                value = SlashMenuPayload(\n"
            "                    commands = viewModel.filteredSlashCommands(),\n"
            "                    loaded = true,\n"
            "                )\n"
            "            }\n"
            "        }\n"
            "    } else {\n"
            "        remember { mutableStateOf(SlashMenuPayload()) }\n"
            "    }\n"
            "    val filteredSlashCommands = slashMenuPayload.value.commands\n",
            "    val filteredSlashCommands = remember(\n"
            "        viewModel.slashFilter.collectAsState().value,\n"
            "        viewModel.memoryEnabled.collectAsState().value,\n"
            "        viewModel.currentSessionId,\n"
            "        viewModel.skillRepository?.skills?.collectAsState()?.value,\n"
            "        viewModel.mcpRepository?.servers?.collectAsState()?.value,\n"
            "    ) { viewModel.filteredSlashCommands() }\n"),
    }), ["D-memo"])
    # D-IO: the producer keeps everything but drops the IO hop — the load is
    # async in name only, the query burst stays on the composition context
    # (Main). expect_exact: ONLY this criterion may turn red.
    expect_exact("D-IO producer without withContext(Dispatchers.IO)", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            withContext(Dispatchers.IO) {\n"
            "                value = SlashMenuPayload(\n"
            "                    commands = viewModel.filteredSlashCommands(),\n"
            "                    loaded = true,\n"
            "                )\n"
            "            }\n",
            "            value = SlashMenuPayload(\n"
            "                commands = viewModel.filteredSlashCommands(),\n"
            "                loaded = true,\n"
            "            )\n"),
    }), ["D-IO"])
    # D4: one repository's override revision dropped from the keys — that
    # menu goes stale after a toggle in exactly that sheet
    expect_exact("D4 skill override revision key dropped", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            viewModel.skillRepository?.sessionOverridesRevision?.collectAsState()?.value,\n",
            ""),
    }), ["D4"])
    # D4: the MCP twin (proves BOTH channels are required, not just one)
    expect_exact("D4 mcp override revision key dropped", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            viewModel.mcpRepository?.sessionOverridesRevision?.collectAsState()?.value,\n",
            ""),
    }), ["D4"])
    # D5: one mutator writes the table without bumping the revision
    expect_exact("D5 setSessionOverride skips the revision bump", fixtures(**{
        SKILL_REPO_REL: SKILLREPO_F.replace(
            "        db.execSQL(\"INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)\", arrayOf<Any>(sessionId, skillId, 1))\n"
            "        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L\n",
            "        db.execSQL(\"INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)\", arrayOf<Any>(sessionId, skillId, 1))\n"),
    }), ["D5"])
    # D5: the revision flow never existed at all
    expect("D5 no revision flow at all", fixtures(**{
        SKILL_REPO_REL: SKILLREPO_F.replace(
            "    private val _sessionOverridesRevision = MutableStateFlow(0L)\n"
            "    val sessionOverridesRevision: StateFlow<Long> = _sessionOverridesRevision.asStateFlow()\n"
            "\n", ""),
    }), ["D5"])
    # D6: the ext body regresses to the per-item query shape
    expect_exact("D6 per-item isEnabledForSession in filteredSlashCommands", fixtures(**{
        SLASH_EXT_REL: SLASHEXT_F.replace(
            "    val skillEnablement = skillRepository?.sessionEnablementMap(\n"
            "        sid, skillRepository.skills.value.map { it.id }) ?: emptyMap()\n",
            "    val skillRows = skillRepository?.skills?.value\n"
            "        ?.filter { skillRepository.isEnabledForSession(it.id, sid) }\n"
            "        ?: emptyList()\n"),
    }), ["D6"])
    # D5/D6 optional-read boundary: sibling trees that do not ship the three
    # files must NOT fail-closed the D family (test_scan's minimal trees are
    # exactly this shape)
    expect("D5/D6 optional files absent", fixtures(**{
        SLASH_EXT_REL: None,
        SKILL_REPO_REL: None,
        MCP_REPO_REL: None,
    }), None)
    # precision: an early-exit guard is a legal shape (D3 must stay green)
    expect("D3 precision: early-exit guard accepted", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (showSlashMenu) {\n",
            "    if (!showSlashMenu) {\n"
            "        return@ChatInputArea\n"
            "    }\n"
            "    val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (true) {\n"),
    }), None)

    # ── E1-E3 (dv19-B1): composer decisions read the LIVE field value ────────
    # E1: a content flag that reads the debounced VM string flips red
    expect_exact("E1 debounced button flag", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val hasContent = canSwipeSendNow()\n",
            "    val hasContent = inputText.isNotBlank() || attachments.isNotEmpty()\n"),
    }), ["E1"])
    # E1 precision: reading the field's own value is an equally legal shape
    expect("E1 precision: direct live read", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val hasContent = canSwipeSendNow()\n",
            "    val hasContent = inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()\n"),
    }), None)
    # E2: each decision site must pass the live value -- one fixture per site
    expect_exact("E2 debounced dismiss arg", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "dismissSlashMenu(inputFieldValue.text)", "dismissSlashMenu(inputText)"),
    }), ["E2-dismiss"])
    expect_exact("E2 debounced execute arg", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "executeSlashCommand(cmd, inputFieldValue.text)", "executeSlashCommand(cmd, inputText)"),
    }), ["E2-execute"])
    expect_exact("E2 debounced transfer arg", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "inputText = inputFieldValue.text,", "inputText = inputText,"),
    }), ["E2-transfer"])
    # E2 fail-closed: a site that moved away is reported, never silently skipped
    expect_exact("E2 missing transfer site", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "ChatViewModelStore.PendingTransfer(", "ChatViewModelStore.MovedTransfer("),
    }), ["E2-transfer"])
    # E3: dropping the cancel at one site flips that site red
    expect_exact("E3 cancel dropped at the transfer site", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "        MoveToSessionSheet(onSelect = { targetId ->\n"
            "            cancelPendingImeBurst()\n",
            "        MoveToSessionSheet(onSelect = { targetId ->\n"),
    }), ["E3"])
    # E3 precision: inline cancels (no shared helper) are an equally legal shape
    expect_exact("E3 precision: inline cancels", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val cancelPendingImeBurst: () -> Unit = {\n"
            "        imeBurstJob?.cancel()\n"
            "        imeBurstJob = null\n"
            "        imeBurstBuffer = null\n"
            "    }\n", "").replace(
            "cancelPendingImeBurst()", "imeBurstJob?.cancel()"),
    }), [])
    # ── F1-F4: composer clear is one funnel (dv20-B03) ──────────────────────
    expect("F1 funnel loses the field reset", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '        inputFieldValue = TextFieldValue("")\n', ""),
    }), ["F1"])
    expect("F1 funnel loses the caret reset", fixtures(**{
        INPUT_REL: INPUT_F.replace('        lastTrueCaretEnd = 0\n', ""),
    }), ["F1"])
    expect("F1 funnel loses the burst drop", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '        cancelPendingImeBurst()\n        viewModel.setInputText("")',
            '        viewModel.setInputText("")'),
    }), ["F1"])
    expect("F2 clear outside the funnel", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '        viewModel.sendMessage(toSend)',
            '        viewModel.setInputText("")\n        viewModel.sendMessage(toSend)'),
    }), ["F2"])
    expect("F3 funnel called before its declaration", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "package com.rikkaminis.app.ui.chat\n",
            "package com.rikkaminis.app.ui.chat\n\n"
            "val earlyCaller: () -> Unit = { clearComposerInput() }\n"),
    }), ["F3"])
    expect("F4 pill routes around the funnel", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '            viewModel.cancelEdit()\n            clearComposerInput()',
            '            viewModel.cancelEdit()'),
    }), ["F4"])
    expect("F precision: comments mention the shapes", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val clearComposerInput: () -> Unit = {",
            "    // NEVER: setInputText(\"\") alone -- the VM write is suppressed\n"
            "    // when the string is already empty, so the field keeps the sent text\n"
            "    val clearComposerInput: () -> Unit = {"),
    }), [])
    expect("F4 send path skips the funnel", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '        clearComposerInput()\n        viewModel.sendMessage(toSend)',
            '        viewModel.sendMessage(toSend)'),
    }), ["F4"])
    expect("F4 slash branch skips the funnel", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '            clearComposerInput()\n            return@handler',
            '            return@handler'),
    }), ["F4"])
    expect("F4 move-to-session skips the funnel", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            '            clearComposerInput()\n        })',
            '        })'),
    }), ["F4"])

    # ── H family (B-05): the streaming body's ledger invariants ─────────────
    # H1: the seed forgets the streaming flag → the convergence frame keeps the
    #     `null` cached during the live frames.
    expect("H1 seed not keyed on isStreaming", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "    val warmSeed = remember(displayContent, isStreaming) {",
            "    val warmSeed = remember(displayContent) {"),
    }), ["H1"])
    # H2: the block state collapses back to a bare list.
    expect("H2 bare List<MdBlock> state", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "    var published by remember {\n"
            "        mutableStateOf(StreamingBlockLedger.seed(warmSeed, displayContent))\n"
            "    }\n"
            "    val blocks = StreamingBlockLedger.rendered(published, warmSeed)\n",
            "    var blocks by remember { mutableStateOf<List<MdBlock>>(warmSeed.orEmpty()) }\n"),
    }), ["H2"])
    # H3: the publishing effect drops the streaming flag from its keys → the
    #     convergence frame never re-enters.
    expect("H3 effect not keyed on isStreaming", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "    LaunchedEffect(displayContent, isStreaming) {",
            "    LaunchedEffect(displayContent) {"),
    }), ["H3"])
    # H4a: the convergence deposit loses its ledger guard.
    expect("H4 deposit guard never names the stream state", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "        if (StreamingBlockLedger.isConvergedWith(published, displayContent, isStreaming)) {",
            "        if (published.blocks.isNotEmpty()) {"),
    }), ["H4"])
    # H4b: the deposit caches a list this frame did not parse, without asking
    #      where that list came from.
    expect("H4 deposit of a foreign list", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "                MarkdownParseCaches.putBlocks(displayContent, published.blocks)",
            "                MarkdownParseCaches.putBlocks(displayContent, stalePrefix)"),
    }), ["H4"])
    # H precision: prose mentions the forbidden shapes.
    expect("H precision: comments mention the old shapes", fixtures(**{
        STREAM_REL: STREAM_F.replace(
            "internal object StreamingBlockLedger {",
            "// NEVER: var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }\n"
            "// NEVER: putBlocks(displayContent, published.blocks) outside isStreaming\n"
            "internal object StreamingBlockLedger {"),
    }), [])

    # ── G family (dv23-B3): the composer's text belongs to the composer ─────
    # G clean: every intent is routed, the screen never resolves it itself.
    # expect() (not expect_exact) because None means "zero problems", which is
    # itself the exact assertion: any single finding fails the arm.
    expect("G clean: intents routed, screen resolves nothing",
           fixtures(**{SCREEN_REL: SCREEN_F, INPUT_REL: INPUT_G}), None)
    # G1: the pre-fix shape — ChatScreen resolves the menu text from the
    #     DEBOUNCED ViewModel string.
    # The pre-fix fixture resolves the text AND writes/reads the debounced
    # string, so it reds G1 + G5a + G5b — exactly the three spellings the defect
    # had, not an unrelated criterion (G2 stays out: the write is not the
    # empty-string clear).
    expect_exact("G1 screen resolves the menu text", fixtures(**{
        SCREEN_REL: SCREEN_F.replace(
            "    MenuAction(",
            "    viewModel.setInputText(viewModel.dismissSlashMenu(viewModel.inputText.value))\n"
            "    MenuAction("),
        INPUT_REL: INPUT_G,
    }), ["G1", "G5a", "G5b"])
    # G2: the clear-chat confirm writes the empty string behind the funnel.
    expect_exact("G2 screen clears the composer itself", fixtures(**{
        SCREEN_REL: SCREEN_F.replace(
            "    MenuAction(",
            "    viewModel.setInputText(\"\")\n    MenuAction("),
        INPUT_REL: INPUT_G,
    }), ["G2"])
    # G3: the screen constructs an intent and drops it (no channel handoff).
    expect_exact("G3 intent constructed but never routed", fixtures(**{
        SCREEN_REL: SCREEN_F.replace(
            "viewModel.requestComposerCommand(ComposerCommand.ToggleSlashMenu)",
            "ComposerCommand.ToggleSlashMenu"),
        INPUT_REL: INPUT_G,
    }), ["G3"])
    # G4: the consumer drops the Clear arm — the funnel's three writes never run.
    expect_exact("G4 collector bypasses the clear funnel", fixtures(**{
        SCREEN_REL: SCREEN_F,
        INPUT_REL: INPUT_G.replace(
            "                ComposerCommand.Clear -> clearComposerInput()\n", ""),
    }), ["G4"])
    # ── G5-G7 (dv24-B05): every composer write is an intent ────────────────
    # G clean with the full intent set declared and consumed.
    expect("G5-G7 clean: intents declared, consumed and dispatched",
           fixtures(**{SCREEN_REL: SCREEN_F, INPUT_REL: INPUT_H, VM_REL: VM_F}), None)
    # G5a: the screen writes a non-empty body behind the composer's back (the
    #      message-edit / share / transfer shape).
    expect_exact("G5a screen writes the composer body", fixtures(**{
        SCREEN_REL: SCREEN_F.replace(
            "    MenuAction(",
            "    viewModel.setInputText(prefill, caretOverride = prefill.length)\n"
            "    MenuAction("),
        INPUT_REL: INPUT_H,
    }), ["G5a"])
    # G5b: the screen READS the debounced string (the append shape).
    expect_exact("G5b screen reads the debounced string", fixtures(**{
        SCREEN_REL: SCREEN_F.replace(
            "    MenuAction(",
            "    val merged = viewModel.inputText.value + sep + item.value\n"
            "    MenuAction("),
        INPUT_REL: INPUT_H,
    }), ["G5b"])
    # G6: the ViewModel entrance resolves the text itself again.
    expect_exact("G6 viewmodel writes the text itself", fixtures(**{
        SCREEN_REL: SCREEN_F,
        INPUT_REL: INPUT_H,
        VM_REL: VM_F.replace(
            """        requestComposerCommand(
            ComposerCommand.AppendText(text = cleaned, separator = " "),
        )""",
            """        setInputText(_inputText.value + " " + cleaned)"""),
    }), ["G6"])
    # G7a: an intent is declared but the composer has no arm for it.
    expect_exact("G7a declared intent has no composer arm", fixtures(**{
        SCREEN_REL: SCREEN_F,
        INPUT_REL: INPUT_G,
        VM_REL: VM_F,
    }), ["G7"])
    # G7b: the composer keeps an arm for a subtype that no longer exists.
    expect_exact("G7b composer arm outran the declaration", fixtures(**{
        SCREEN_REL: SCREEN_F,
        INPUT_REL: INPUT_H,
        VM_REL: VM_F.replace(
            "    data object Clear : ComposerCommand\n", ""),
    }), ["G7"])

    # ── B06 (deep-audit-1049): the composer body funnel ────────────────────
    # The pre-fix shape: a slash outlet writes the ViewModel copy only.
    expect_exact("B06 slash outlet writes setInputText directly", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "commitComposerBody(viewModel.dismissSlashMenu(inputFieldValue.text), null)",
            "viewModel.setInputText(viewModel.dismissSlashMenu(inputFieldValue.text))"),
    }), ["B06"])
    # The double-truth is only fixed if BOTH copies are written.
    expect_exact("B06 funnel drops the field write", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "        inputFieldValue = TextFieldValue(text)\n", ""),
    }), ["B06"])
    # …and only if the buffered burst cannot commit over the write.
    expect_exact("B06 funnel keeps the pending burst", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val commitComposerBody: (String, Int?) -> Unit = { text, caret ->\n"
            "        cancelPendingImeBurst()\n",
            "    val commitComposerBody: (String, Int?) -> Unit = { text, caret ->\n"),
    }), ["B06"])
    # A caret-tagged write outside the funnel leaves _pendingCaret dangling.
    expect_exact("B06 caret-tagged write bypasses the funnel", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "            commitComposerBody(viewModel.dismissSlashMenu(inputFieldValue.text), null)",
            "            viewModel.setInputText(viewModel.dismissSlashMenu(inputFieldValue.text), "
            "caretOverride = 3)"),
    }), ["B06"])
    # Fail-closed: no funnel declared at all.
    expect_exact("B06 funnel declaration gone", fixtures(**{
        INPUT_REL: INPUT_F.replace(
            "    val commitComposerBody: (String, Int?) -> Unit = { text, caret ->",
            "    val commitComposerBodyRemoved: (String, Int?) -> Unit = { text, caret ->"),
    }), ["B06"])

    if failures:
        print("❌ selftest failed:")
        for f in failures:
            print("   " + f)
        return 1
    print("CHAT_UI_INTERACTION_SELFTEST=PASS (%d fixtures: clean + %d violation/precision)"
          % (arms[0], arms[0] - 1))
    return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    root = os.path.abspath(args[0]) if args else os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", ".."))
    if "--selftest" in flags:
        return selftest()
    unknown = [f for f in flags if f != "--selftest"]
    if unknown:
        print("unknown option(s): %s" % " ".join(unknown))
        return 2
    path, problems = check_tree(root)
    if path is None or problems:
        print("chat_ui_interaction_check: %d violation(s) in %s" % (len(problems), path))
        for cid, msg in problems:
            print("  [%s] %s" % (cid, msg))
        return 1
    print("chat_ui_interaction_check: slash wiring + table toolbar + swipe parity — "
          "CHAT-UI-INTERACTION GATE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
