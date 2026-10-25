#!/usr/bin/env python3
"""
Guard: a persisted-task-set removal must release the runner's in-process
single-flight state — from OUTSIDE the store lock.

The regression this exists for (deep-audit-1016 B-7c, fixed 2026-10-16):
`ScheduledTasksStore.deleteTask` and `importDocument` cleared the persisted
`running` marker but never touched `ScheduledTaskRunner`'s in-process lock
(`runningTaskId` + `dispatched`). Deleting the task that was running right now
therefore left its id holding the single-flight gate until
`reapAbandonedDispatch`'s deadline+grace elapsed (~30 min for a default task),
during which every other due task degraded to `overlap` and never fired.

Two things have to hold, and both are silent when broken:

  1. PRESENCE — every function in `ScheduledTasksStore.kt` that drops tasks from
     the document must notify the runner. Deciding "drops tasks" textually:
     the body contains `filterNot { it.id ==` (drops by id) or
     `copy(running = null)` (the restore path).
  2. PLACEMENT — the notification must be a *top-level statement of that
     function*, never nested inside the store's `lock.withLock { … }` /
     `mutate(…) { … }` block. The notification takes the runner's `checkMutex`,
     which `checkAndFireToday` holds across a whole fire pass — and a fire pass
     calls back into the store (`markRunning`). Nesting the two locks inverts
     the order and deadlocks: the delete would wait for checkMutex while the
     fire pass waits for the store mutex.

Criterion (brace-depth based, no regex judgement calls): for each function
detected by rule 1, require exactly one `ScheduledTaskRunner.onTasksRemoved(`
call, at depth 1 of that function's body. Plus a file-wide rule: no
`ScheduledTaskRunner.` reference may appear while a `lock.withLock {` block is
open.

Usage: python3 scripts/scan/scheduled_store_release_guard.py <repo_root>
Exit:  0 = clean, 1 = violation(s) / ROT
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# [meta-audit wide4] One Kotlin lexer / one constant folder for every gate.
# The hand-rolled masker below used to be this gate's own private lexer, and it
# was the parser-death axis: a char literal `'"'` opened a phantom string that
# swallowed every real statement up to the next such literal, so a criterion
# judged a body that no longer existed in the file. The shared module handles
# nested block comments, char literals, raw strings, `${…}` templates and
# backtick identifiers; it reports parse failure instead of guessing.
from _kotlin_lexer import analyse as _lex_analyse  # noqa: E402
from _kotlin_ast import const_bool as _const_bool  # noqa: E402

STORE_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTasksStore.kt"
)

RELEASE_CALL = "ScheduledTaskRunner.onTasksRemoved("
FUN_RE = re.compile(r"^    (?:[a-z]+ )*fun (\w+) *\(", re.MULTILINE)
REMOVAL_RES = (
    re.compile(r"filterNot *\{ *it\.id *=="),
    re.compile(r"copy\(running *= *null\)"),
)
LOCK_RE = re.compile(r"\block\.withLock *\{")

# ── deep-audit-1030 ISSUE-08 ────────────────────────────────────────────────
# The editor used to save its WHOLE record, so any run that settled while the
# editor was open (up to the full 60 s prompt budget) was erased by the save —
# `ScheduledTask` carries run state (lastRunDate / lastResult / failStreak /
# suspendedByFuse) and `enabled` in the same record. The fix has two halves, and
# both are silent when broken: the store must route through the merge, and the
# merge must copy ONLY the editable spec fields.
#
# The subject of these criteria is "wherever an `upsertTask` exists" and "the
# edit screen", so a synthetic tree that carries neither reports SKIP instead of
# a violation (the real tree always carries both).
# ────────────────────────────────────────────────────────────────────────────

POLICY_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTaskPolicy.kt"
)
EDIT_SCREEN_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ScheduledTaskEditScreen.kt"
)

MERGE_FUN = "fun mergeUpsertTask("
MERGE_CALL = "mergeUpsertTask("

# Run state + enablement: owned by the runner / store, never writable by a save.
RUN_STATE_FIELDS = ("enabled", "lastRunDate", "lastResult", "failStreak", "suspendedByFuse")
# The editable spec, i.e. what a save IS allowed to write. Every one must be
# copied: a `copy()` with no arguments would make Save silently do nothing.
# Keep in step with ScheduledTask's spec fields.
SPEC_FIELDS = (
    "title", "prompt", "windowStart", "windowEnd",
    "maxTurns", "deadlineSec", "maxEstimatedTokens",
)
# The pre-fix shape: the whole incoming record assigned into the list.
WHOLE_RECORD_ASSIGN = re.compile(r"(?:it|tasks)\[idx\]\s*=\s*task\b")
NAMED_ARG_RE = re.compile(r"\b([A-Za-z_]\w*)\s*=(?!=)")

# ── deep-audit-1034 B-04 (S3) ───────────────────────────────────────────────
# The edit screen's two remaining silent states (reported 2026-10-20):
#   * record gone → the form kept its defaults with `original == null`: Save
#     could never enable (a dead button) while Delete stayed live on an id that
#     no longer exists. The screen said nothing about why.
#   * window inverted (start >= end, both halves parse) → the start field's
#     `isError = !startOk` left BOTH fields unmarked while `windowOk == false`
#     silently kept Save disabled.
# The criteria (S3) require: the id-keyed load raises `recordMissing` on the
# not-found path, the flag is a remembered `var`, canSave gates on it, an
# `if (recordMissing) { … return }` render gate keeps Save/Delete unreachable
# in the not-found screen, and the start field's isError binds the inverted
# window. The load spelling is an anchor — if it changes, ROT instead of
# passing vacuously.

# S2: the accepted spelling, pinned literally (whitespace-insensitive).
CAN_SAVE_RE = re.compile(r"val canSave\s*=")
CAN_SAVE_FORMS = ("(isNew||original!=null)", "(original!=null||isNew)")

# ── deep-audit-1034 B-04 ────────────────────────────────────────────────────
# Two more silent states of the edit screen (reported 2026-10-20):
#   * record gone → the form rendered its defaults with `original == null`, so
#     Save could never enable (a dead button) while Delete stayed live on an id
#     that no longer exists; the screen said nothing.
#   * window inverted (start >= end, both halves parse) → `isError = !startOk`
#     left BOTH fields unmarked while `windowOk == false` silently kept Save
#     disabled: a valid-looking window that never saves.
# The subject is the id-keyed load in ScheduledTaskEditScreen; the load spelling
# is an anchor — if it changes, ROT instead of passing vacuously.
# ────────────────────────────────────────────────────────────────────────────

MISSING_FLAG = "recordMissing"
MISSING_LOAD_RE = re.compile(r"firstOrNull *\{ *it\.id *== *taskId *\}")
MISSING_DECL_RE = re.compile(r"var +recordMissing +by +remember")
LOAD_EFFECT_HEAD = "LaunchedEffect(taskId)"
START_FIELD_ANCHOR = "R.string.scheduled_field_window_start"


def lambda_body(scan, head):
    """Body of the first trailing lambda attached to `head`, or None.

    `head` is e.g. `LaunchedEffect(taskId)`; the body is the `{ … }` that
    follows it, brace-matched. Input is strip_code output, so offsets line up
    with the raw file."""
    m = re.search(re.escape(head), scan)
    if m is None:
        return None
    j = m.end()
    while j < len(scan) and scan[j].isspace():
        j += 1
    if j >= len(scan) or scan[j] != "{":
        return None
    depth = 0
    for k in range(j, len(scan)):
        if scan[k] == "{":
            depth += 1
        elif scan[k] == "}":
            depth -= 1
            if depth == 0:
                return scan[j + 1:k]
    return None


def start_field_args(scan):
    """Args of the SectionTextField right after the `window_start` RowLabel, or
    None. The start field is where an inverted window must become visible."""
    m = re.search(re.escape(START_FIELD_ANCHOR), scan)
    if m is None:
        return None
    m2 = re.compile(r"SectionTextField *\(").search(scan, m.end())
    if m2 is None:
        return None
    i = m2.end() - 1
    depth = 0
    for k in range(i, len(scan)):
        if scan[k] == "(":
            depth += 1
        elif scan[k] == ")":
            depth -= 1
            if depth == 0:
                return scan[i + 1:k]
    return None


def scan_edit_screen_guards(screen_raw):
    """S3 — a gone record must not keep a live Delete on a dead id behind a Save
    that can never enable, and an inverted window must be visible on the form,
    not only as a silently disabled button."""
    scan = strip_code(screen_raw)
    if scan is None:
        return [_unlexable(EDIT_SCREEN_REL)]
    bad = []

    # Subject anchor: the id-keyed load. If the spelling changed, ROT loudly.
    if not MISSING_LOAD_RE.search(scan):
        return [
            "%s: no `firstOrNull { it.id == taskId }` load left in ScheduledTaskEditScreen — "
            "the subject of the not-found criteria is gone (update this gate's detection)"
            % EDIT_SCREEN_REL
        ]

    # (1) the not-found branch must surface the condition, not just drop it.
    effect = lambda_body(scan, LOAD_EFFECT_HEAD)
    if effect is None:
        bad.append(
            "%s: no `%s { … }` body parseable — the load effect changed shape "
            "(update this gate's detection)" % (EDIT_SCREEN_REL, LOAD_EFFECT_HEAD)
        )
    elif "recordMissing=true" not in re.sub(r"\s+", "", effect):
        bad.append(
            "%s: the edit-target load no longer raises `%s` on the not-found path — a deleted "
            "record renders the form's defaults again: Save locked for good "
            "(`original == null`) while Delete stays live on an id that no longer exists"
            % (EDIT_SCREEN_REL, MISSING_FLAG)
        )

    # (2) the flag must be a remembered `var` — the render gate below reads it.
    if not MISSING_DECL_RE.search(scan):
        bad.append(
            "%s: no `var %s by remember` — the not-found state is not remembered, so the "
            "render gate cannot hold across recompositions" % (EDIT_SCREEN_REL, MISSING_FLAG)
        )

    # (3) Save must not depend on the not-found screen staying put: the flag
    # gates canSave directly (belt to `original != null`'s braces).
    m = CAN_SAVE_RE.search(scan)
    if m is None:
        bad.append(
            "%s: no `val canSave =` — the save gate is missing entirely" % EDIT_SCREEN_REL
        )
    else:
        statement = scan[m.end():].split("\n\n")[0]
        if "!" + MISSING_FLAG not in re.sub(r"\s+", "", statement):
            bad.append(
                "%s: canSave does not gate on `%s` — the not-found render path would be the "
                "only thing keeping Save unreachable, and rendering paths get refactored away"
                % (EDIT_SCREEN_REL, MISSING_FLAG)
            )

    # (4) the not-found state must be rendered as an explicit screen with one
    # way out — no Save/Delete shell in that branch.
    gate = None
    for gm in re.finditer(r"if *\(%s *\)" % MISSING_FLAG, scan):
        j = gm.end()
        while j < len(scan) and scan[j] != "{":
            if not scan[j].isspace():
                break
            j += 1
        if j < len(scan) and scan[j] == "{":
            depth = 0
            for k in range(j, len(scan)):
                if scan[k] == "{":
                    depth += 1
                elif scan[k] == "}":
                    depth -= 1
                    if depth == 0:
                        body = scan[j + 1:k]
                        if "return" in re.sub(r"\s+", "", body):
                            gate = body
                        break
        if gate is not None:
            break
    if gate is None:
        bad.append(
            "%s: no `if (%s) { … return }` render gate — the not-found state is set but never "
            "rendered, so the dead Save / live Delete form is back"
            % (EDIT_SCREEN_REL, MISSING_FLAG)
        )
    else:
        normalized_gate = re.sub(r"\s+", "", gate)
        for excluded in ("MinisButton(", "SectionTextField("):
            if excluded in normalized_gate:
                bad.append(
                    "%s: the not-found screen renders a `%s` — only the explanation and one "
                    "way out belong there; Save/Delete must stay unreachable when the record "
                    "is gone" % (EDIT_SCREEN_REL, excluded.rstrip("("))
                )

    # (5) an inverted window must be visible on the form, not only as a
    # disabled Save: the start field's isError has to bind more than its own
    # parse.
    args = start_field_args(scan)
    if args is None:
        bad.append(
            "%s: the `%s` field could not be located — the inverted-window visibility "
            "criterion is blind (update this gate's detection)"
            % (EDIT_SCREEN_REL, START_FIELD_ANCHOR)
        )
    else:
        em = re.search(r"isError\s*=", args)
        if em is None:
            bad.append(
                "%s: the start field has no isError binding — a window whose halves parse but "
                "invert (start >= end) is invisible on the form while Save silently stays "
                "disabled" % EDIT_SCREEN_REL
            )
        else:
            normalized_arg = re.sub(r"\s+", "", args[em.end():].split(",")[0])
            if "windowInverted" not in normalized_arg and "windowOk" not in normalized_arg:
                bad.append(
                    "%s: the start field's isError only flags its own parse (`%s`) — an "
                    "inverted window (both halves parse, start >= end) leaves both fields "
                    "unmarked while windowOk == false silently disables Save"
                    % (EDIT_SCREEN_REL, normalized_arg or "!startOk")
                )
    return bad



def copy_arg_names(body):
    """Union of the named arguments of every `copy(` call inside [body].

    Union rather than "the second call": the merge body holds the append
    (`file.copy(tasks = …)`), the merge itself (`existing.copy(…)`) and the
    write-back (`file.copy(tasks = …)`) — the criterion is about what a save can
    express, so every copy in the function is in scope.
    """
    names = set()
    for m in re.finditer(re.escape("copy("), body):
        open_at = m.end() - 1
        depth = 0
        for i in range(open_at, len(body)):
            if body[i] == "(":
                depth += 1
            elif body[i] == ")":
                depth -= 1
                if depth == 0:
                    names |= {g.group(1) for g in NAMED_ARG_RE.finditer(body[open_at + 1:i])}
                    break
    return names


DISCARD_LAMBDAS = {
    "also", "let", "apply", "run", "map", "flatMap", "filter", "filterNot",
    "forEach", "onEach", "takeIf", "takeUnless", "ifEmpty", "getOrElse", "use",
    "mapNotNull", "sortedBy", "distinctBy", "repeat", "letOrNull",
}


def _enclosing_brace(text, pos):
    """Index of the `{` opening the block that contains [pos], or -1."""
    depth = 0
    for k in range(pos, -1, -1):
        ch = text[k]
        if ch == "}":
            depth += 1
        elif ch == "{":
            if depth == 0:
                return k
            depth -= 1
    return -1


def _lambda_head_name(text, brace):
    """Name of the function whose trailing lambda starts at [brace], or None."""
    j = brace - 1
    while j >= 0 and text[j] in " \t\r\n":
        j -= 1
    if j < 0:
        return None
    if text[j] == ")":
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
        head_end = k
    else:
        head_end = j + 1
    m = re.search(r"([A-Za-z_]\w*)\s*$", text[max(0, head_end - 80):head_end])
    return m.group(1) if m else None


def _result_discarded(body, pos):
    """True when the value produced at [pos] is swallowed by a receiver lambda.

    [meta-audit wide4 scheduled-S1] `mutate(context) { … }.also { … }` returns the
    RECEIVER, not the lambda's value — a merge call placed inside `.also { }`
    runs (or, behind `if (false)`, does not) and its result never reaches the
    store. Same for `.let { null }`, `.map { }`, `.forEach { }`: the call is
    present, the decision it embodies is thrown away.
    """
    brace = _enclosing_brace(body, pos)
    if brace < 0:
        return False
    name = _lambda_head_name(body, brace)
    if name not in DISCARD_LAMBDAS:
        return False
    head = body.rfind(name, 0, brace)
    if head < 0:
        return False
    before = body[:head].rstrip()
    return before.endswith(".") or before.endswith("?.")


def scan_merge_semantics(store_raw, merge_raw):
    """S1 — an editor save must not be able to speak about run state.

    None = not applicable (this tree has no `upsertTask`)."""
    scan = strip_code(store_raw)
    if scan is None:
        return [_unlexable(STORE_REL)]
    # Literal-decided branches are blanked BEFORE the routing check: keeping the
    # call's spelling while deleting its effect (`if (false) mergeUpsertTask(…)`)
    # is the same family as `!true && guard` — presence is not routing.
    scan = prune_dead_branches(scan)
    upsert = None
    for name, start, end, line in function_spans(scan):
        if name == "upsertTask":
            upsert = (scan[start:end], line)
    if upsert is None:
        return None
    body, line = upsert
    bad = []
    routed = [
        m.start() for m in re.finditer(re.escape(MERGE_CALL), body)
        if not _result_discarded(body, m.start())
    ]
    if not routed:
        if MERGE_CALL in body:
            bad.append(
                "%s:%d: [upsertTask] no longer routes through ScheduledTaskTransitions."
                "mergeUpsertTask — the call survives only inside a receiver lambda "
                "(`.also` / `.let` / `.map` …) or a literal-decided branch, where its "
                "result is discarded: a whole-record write lets a save erase any run "
                "that settled while the editor was open"
                % (STORE_REL, line)
            )
        else:
            bad.append(
                "%s:%d: [upsertTask] no longer routes through ScheduledTaskTransitions."
                "mergeUpsertTask — a whole-record write lets a save erase any run that "
                "settled while the editor was open (the run state lives in the same "
                "record as the spec)" % (STORE_REL, line)
            )
    if WHOLE_RECORD_ASSIGN.search(body):
        bad.append(
            "%s:%d: [upsertTask] writes the caller's whole record (`it[idx] = task`) — run state "
            "and `enabled` come from the editor's stale snapshot that way"
            % (STORE_REL, line)
        )
    if merge_raw is None:
        if MERGE_CALL in body:
            bad.append(
                "%s:%d: [upsertTask] calls the merge but no `%s` exists under src/ — the function "
                "that keeps run state is gone (the tree cannot compile as-is)"
                % (STORE_REL, line, MERGE_FUN.strip())
            )
        return bad

    scan_merge = strip_code(merge_raw)
    if scan_merge is None:
        return bad + [_unlexable("the `mergeUpsertTask` source")]
    merge_body = None
    for name, start, end, _line in function_spans(scan_merge):
        if name == "mergeUpsertTask":
            merge_body = scan_merge[start:end]
    if merge_body is None:
        bad.append("the merge function could not be parsed — the probe lost its subject")
        return bad
    args = copy_arg_names(merge_body)
    for field in RUN_STATE_FIELDS:
        if field in args:
            bad.append(
                "ScheduledTaskTransitions.mergeUpsertTask copies `%s` from the caller's record — "
                "that is the run state / enablement a save must never be able to overwrite "
                "(it belongs to the runner and the store)" % field
            )
    for field in SPEC_FIELDS:
        if field not in args:
            bad.append(
                "ScheduledTaskTransitions.mergeUpsertTask does not copy the editable field `%s` — "
                "an empty/short copy() makes Save silently drop that field (update this gate's "
                "SPEC_FIELDS when the editable spec changes)" % field
            )
    return bad


def scan_can_save_gate(screen_raw):
    """S2 — Save must wait for the stored record before it may write over it."""
    scan = strip_code(screen_raw)
    if scan is None:
        return [_unlexable(EDIT_SCREEN_REL)]
    m = CAN_SAVE_RE.search(scan)
    if m is None:
        return [
            "ScheduledTaskEditScreen has no `val canSave =` — the save gate is missing entirely "
            "(the probe must not pass vacuously)"
        ]
    statement = scan[m.end():].split("\n\n")[0]
    normalized = re.sub(r"\s+", "", statement)
    if not any(form in normalized for form in CAN_SAVE_FORMS):
        return [
            "ScheduledTaskEditScreen.canSave does not gate on the loaded snapshot: expected "
            + " or ".join(CAN_SAVE_FORMS)
            + " — without it a save issued while LaunchedEffect(taskId) is still loading writes "
              "the form's defaults over the stored spec"
        ]
    return []


def find_merge_source(root):
    """(rel_path, text) of the file declaring `fun mergeUpsertTask(`, or (None, None)."""
    src = os.path.join(root, "src")
    for dirpath, _dirs, files in os.walk(src):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
            except OSError:
                continue
            masked = strip_code(text)
            if masked is None or MERGE_FUN not in masked:
                continue
            return os.path.relpath(path, root), text
    return None, None


def read_optional(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()


# ── deep-audit-1046 S-11 ────────────────────────────────────────────────────
# `ScheduledTasksStore` is read-once by design (every accessor is a one-shot
# `load`), so a screen that rendered once rendered that document until it was
# left and re-entered. Two silent windows follow:
#
#   * the list never saw a background run settle (`lastRunDate` / `lastResult` /
#     `failStreak`) and never saw the fuse trip — a suspended task's Resume
#     button only appeared after an ON_RESUME round-trip;
#   * the editor never saw its own target disappear, and Save then appended the
#     vanished record straight back (`mergeUpsertTask`: `idx < 0 → append`) with
#     the run state the form had snapshotted — a deleted task resurrected.
#
# The fix is one change ticker (bumped after each landed write), two
# subscriptions, and an explicit allow-insert parameter for the save. Each of the
# four is silent when dropped, so each gets a criterion. Everything below is
# derived: the ticker's NAME comes from the store, the collected variable comes
# from the screen, the parameters come from the signature — no member lists.
# ────────────────────────────────────────────────────────────────────────────

LIST_SCREEN_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ScheduledTasksScreen.kt"
)
APPEND_RE = re.compile(r"\.tasks *\+ *task\b")
WRITE_CALL_RE = re.compile(r"AtomicFileWrite\.write *\(")
BUMP_TAIL_RE = re.compile(r"\. *value *=|\. *update *\{")


def _paren_args(scan, open_idx):
    """Text between the parenthesis at [open_idx] and its match, or None."""
    depth = 0
    for k in range(open_idx, len(scan)):
        if scan[k] == "(":
            depth += 1
        elif scan[k] == ")":
            depth -= 1
            if depth == 0:
                return scan[open_idx + 1:k]
    return None


def _call_args(scan, head_re):
    """(args, start_index_of_head) of the first [head_re] call, or (None, None)."""
    m = re.search(head_re, scan)
    if m is None:
        return None, None
    return _paren_args(scan, m.end() - 1), m.start()


def _brace_body(scan, idx):
    """Body of the `{ … }` that starts at/after [idx], brace-matched."""
    j = idx
    while j < len(scan) and scan[j] != "{":
        if not scan[j].isspace() and scan[j] != ")":
            break
        j += 1
    if j >= len(scan) or scan[j] != "{":
        return None
    depth = 0
    for k in range(j, len(scan)):
        if scan[k] == "{":
            depth += 1
        elif scan[k] == "}":
            depth -= 1
            if depth == 0:
                return scan[j + 1:k]
    return None


def _state_flow_name(store_raw):
    """(backing_field_name, exposed_property_name) of the store's change ticker."""
    scan = strip_code(store_raw)
    if scan is None:
        return None, None
    backing = re.search(r"private +val +_(\w+) *= *MutableStateFlow *[<(]", scan)
    if backing is None:
        return None, None
    exposed = re.search(r"(?<![.\w])val +(\w+) *: *StateFlow *<[^>]*> *= *_(\w+)", scan)
    if exposed is None:
        return backing.group(1), None
    # A `private val x: StateFlow<…>` is not an exposure — no screen can collect
    # it. The modifier sits on the same line, before the keyword.
    line_head = scan[scan.rfind("\n", 0, exposed.start()) + 1:exposed.start()]
    if re.search(r"\bprivate\b", line_head):
        return backing.group(1), None
    return backing.group(1), exposed.group(1)


def scan_revision_stream(store_raw):
    """C1 — the store publishes a change ticker, and it moves ONLY when a write
    actually landed.

    Two shapes are silent: no ticker at all (every screen reads once), and a
    ticker bumped before the write (a write that throws leaves the screens told
    to re-read a document that never changed — and a retry loop re-renders for
    nothing). Order is therefore part of the criterion, not a style note."""
    scan = strip_code(store_raw)
    if scan is None:
        return [_unlexable(STORE_REL)]
    writes = [m.start() for m in WRITE_CALL_RE.finditer(scan)]
    if not writes:
        # A store-shaped fixture that never writes (the removal-path fixtures in
        # test_scan are exactly this) has no write to report on — SKIP rather
        # than red, so the criterion stays about ordering/coverage and not about
        # the fixture's completeness.
        return [
            "SKIP: no `AtomicFileWrite.write(` in %s — the change-ticker criterion does "
            "not apply to a store that never writes" % STORE_REL
        ]
    backing, exposed = _state_flow_name(store_raw)
    if backing is None:
        return [
            "%s: no `private val _x = MutableStateFlow(` backing field — the store has no "
            "change stream, so every screen keeps rendering the document it read once: a "
            "background run settling and a fuse trip stay invisible until the screen is "
            "left and re-entered, and the editor never learns its target is gone"
            % STORE_REL
        ]
    if exposed is None or exposed != backing:
        return [
            "%s: the change ticker is not exposed as a StateFlow over its own backing field "
            "(`val <name>: StateFlow<…> = _%s`) — a private ticker no screen can collect is "
            "the same bug with more code" % (STORE_REL, backing)
        ]

    bump_res = (
        re.compile(r"(?<![.\w])_%s *\. *value *=" % backing),
        re.compile(r"(?<![.\w])_%s *\. *update *\{" % backing),
    )
    bumps = sorted({m.start() for r in bump_res for m in r.finditer(scan)})
    if not bumps:
        return [
            "%s: the change ticker (`_%s`) is never bumped — screens that key on it never "
            "reload, which is indistinguishable from having no ticker at all"
            % (STORE_REL, backing)
        ]

    bad = []
    spans = function_spans(scan)

    def enclosing(idx):
        for name, start, end, line in spans:
            if start <= idx < end:
                return name, start, end, line
        return None

    # (1) order: the bump must come after the write it reports on.
    for bump in bumps:
        span = enclosing(bump)
        if span is None:
            bad.append(
                "%s:%d: the ticker is bumped outside any store function — the bump cannot be "
                "tied to the write it is supposed to report"
                % (STORE_REL, line_of(scan, bump))
            )
            continue
        name, start, end, line = span
        inner_writes = [w for w in writes if start <= w < end]
        if not inner_writes:
            bad.append(
                "%s:%d: [%s] bumps the ticker but performs no write — a bump without a landed "
                "write tells every screen to re-read a document that cannot have changed"
                % (STORE_REL, line_of(scan, bump), name)
            )
            continue
        for w in inner_writes:
            if w > bump:
                bad.append(
                    "%s:%d: [%s] bumps the ticker BEFORE the write (write at line %d) — a "
                    "failed write then still tells every screen the document changed"
                    % (STORE_REL, line_of(scan, bump), name, line_of(scan, w))
                )

    # (2) the bump has to MOVE the counter. [meta-audit wide3 scheduled-C1]
    # The criterion used to be "there is a `_revision.value =` line inside the
    # write funnel AND a nextRevision(…) call carrying `wrote`" — two presence
    # checks that pass while `val nextRev = nextRevision(…)` is computed and
    # thrown away and the counter is assigned its OWN value. StateFlow is
    # distinctUntilChanged, so a self-assignment emits nothing: every screen
    # keeps rendering the stale document while the gate reports a live ticker.
    for bump in bumps:
        why = bump_stalls(scan, backing, bump)
        if why:
            bad.append(
                "%s:%d: %s" % (STORE_REL, line_of(scan, bump), why)
            )

    # (3) coverage: no write may escape the funnel (one bump per write path).
    for w in writes:
        span = enclosing(w)
        if span is None:
            continue
        name, start, end, line = span
        if not any(start <= b < end for b in bumps):
            bad.append(
                "%s:%d: [%s] writes the document without bumping the ticker — that write is "
                "invisible to every screen (bump in the write funnel, or per write method, "
                "but never nowhere)" % (STORE_REL, line_of(scan, w), name)
            )
    return bad


def _collect_var(scan, flow_name):
    """The variable a screen binds `…<flow>.collectAsState()` to, or None."""
    m = re.search(
        r"val +(\w+) +by +(?:[\w.]+\.)*%s *\. *collectAsState *\(" % re.escape(flow_name),
        scan,
    )
    return m.group(1) if m else None


def scan_list_screen_keys(store_raw, list_raw):
    """C2 — the list must reload when the store changes, without losing the
    ON_RESUME re-read that covers a returning screen."""
    _backing, flow = _state_flow_name(store_raw)
    if flow is None:
        return [
            "%s: the store exposes no StateFlow change ticker to key on" % STORE_REL
        ]
    scan = strip_code(list_raw)
    if scan is None:
        return [_unlexable(LIST_SCREEN_REL)]
    var = _collect_var(scan, flow)
    if var is None:
        return [
            "%s: does not collect `%s.%s` — the list keeps rendering the document it read "
            "first: a run settling (lastRunDate / lastResult / failStreak) and a fuse trip "
            "(suspendedByFuse → the Resume button) stay invisible while the screen is open"
            % (LIST_SCREEN_REL, "ScheduledTasksStore", flow)
        ]
    args, _ = _call_args(scan, r"produceState *<[^>]*> *\(")
    if args is None:
        return [
            "%s: no `produceState<…>(…)` load — the list's document source changed shape "
            "(update this gate's detection)" % LIST_SCREEN_REL
        ]
    keys = [a.strip() for a in args.split(",")]
    if var not in keys[1:]:
        return [
            "%s: `produceState` does not key on `%s` (keys: %s) — collecting the ticker and "
            "not keying on it reloads nothing"
            % (LIST_SCREEN_REL, var, ", ".join(k for k in keys[1:] if k) or "none")
        ]
    # The double safety: the ON_RESUME re-read must survive (returning from the
    # editor / the app switcher re-reads even if no write happened meanwhile).
    if "ON_RESUME" not in scan or not re.search(r"\breloadKey *\+\+", scan):
        return [
            "%s: the ON_RESUME re-read (`reloadKey++`) is gone — the ticker only fires on "
            "writes, and a screen returning from the editor or the app switcher must still "
            "re-read; the two are deliberate belts, keep both" % LIST_SCREEN_REL
        ]
    return []


FORM_TEXT_FIELDS = ("title", "prompt", "windowStart", "windowEnd")


def scan_edit_screen_revision(store_raw, edit_raw):
    """C3 — the editor follows the ticker: a vanished target becomes the
    not-found state, and a change never rewrites what the user is typing."""
    _backing, flow = _state_flow_name(store_raw)
    if flow is None:
        return ["%s: the store exposes no StateFlow change ticker to follow" % STORE_REL]
    scan = strip_code(edit_raw)
    if scan is None:
        return [_unlexable(EDIT_SCREEN_REL)]
    var = _collect_var(scan, flow)
    if var is None:
        return [
            "%s: does not collect `ScheduledTasksStore.%s` — the form read its record once, "
            "so a task deleted (or replaced away by a backup restore) while the editor is "
            "open stays editable, and Save appends it back" % (EDIT_SCREEN_REL, flow)
        ]
    watched = None
    for m in re.finditer(r"LaunchedEffect *\(", scan):
        open_idx = m.end() - 1
        args = _paren_args(scan, open_idx)
        if args is None:
            continue
        if var not in [a.strip() for a in args.split(",")]:
            continue
        body = _brace_body(scan, open_idx + len(args) + 1)
        if body is not None:
            watched = body
            break
    if watched is None:
        return [
            "%s: no `LaunchedEffect(… %s …)` — collecting the ticker without an effect keyed "
            "on it re-runs nothing" % (EDIT_SCREEN_REL, var)
        ]
    normalized = re.sub(r"\s+", "", watched)
    if "recordMissing=true" not in normalized:
        return [
            "%s: the change handler never raises `recordMissing` — the gone-target decision "
            "exists but no refresh path reaches it" % EDIT_SCREEN_REL
        ]
    if not (
        re.search(r"none *\{ *it\.id *==|firstOrNull *\{ *it\.id *==|shouldMarkMissing *\(", watched)
    ):
        return [
            "%s: the change handler never asks whether the target still exists (no "
            "`none { it.id ==` / `firstOrNull { it.id ==` / shouldMarkMissing) — it cannot "
            "tell a run settling from a deletion" % EDIT_SCREEN_REL
        ]
    for field in FORM_TEXT_FIELDS:
        if re.search(r"(?<![.\w])%s *= *(?!=)" % field, watched):
            return [
                "%s: the change handler assigns `%s` — a background run settling while the "
                "form is open would rewrite what the user is typing; only the RUN half of "
                "the snapshot may be refreshed" % (EDIT_SCREEN_REL, field)
            ]
    return []


def scan_merge_insert_guard(policy_raw):
    """C4 — appending an unknown id must be an explicit, opt-in decision.

    "Unknown id" has two meanings (a brand-new task vs an edit whose target was
    deleted) and only the caller can tell them apart, so the insert branch has to
    sit behind a parameter. Unconditional append is what resurrected deleted
    records together with the form's stale run state."""
    # Literal-decided branches are blanked first: a `if (false && !allowInsert)`
    # keeps the parameter AND the `return file` in the block (wide3 C4).
    masked_policy = strip_code(policy_raw)
    if masked_policy is None:
        return [_unlexable(POLICY_REL)]
    scan = prune_dead_branches(masked_policy)
    pre = []
    # The mirror case: a condition that folds to TRUE (`!allowInsert || true`)
    # keeps the refusal and the parameter, but the refusal is now unconditional —
    # the parameter still decides nothing, and an insert can never succeed.
    for gm in re.finditer(r"\bif\s*\(", scan):
        cp = _match_paren(scan, gm.end() - 1)
        if cp is None:
            continue
        cond = scan[gm.end():cp]
        if "allowInsert" not in cond:
            continue
        if fold_bool(cond) is True:
            pre.append(
                "%s:%d: the insert guard's condition (%s) is constant-true, so "
                "`allowInsert` never decides and every insert is refused"
                % (POLICY_REL, line_of(scan, gm.start()), " ".join(cond.split())))
    for name, start, end, line in function_spans(scan):
        body = scan[start:end]
        if not APPEND_RE.search(body):
            continue
        head_start = scan.rfind("fun ", 0, start)
        sig = scan[head_start:start]
        params = _paren_args(sig, sig.find("(")) if sig.find("(") >= 0 else None
        if params is None:
            return pre + [
                "%s:%d: [%s] the insert branch's signature could not be parsed (update this "
                "gate's detection)" % (POLICY_REL, line, name)
            ]
        boolean_params = [
            p.split(":")[0].strip()
            for p in params.split(",")
            if re.search(r": *Boolean\b", p) and p.split(":")[0].strip()
        ]
        if not boolean_params:
            return pre + [
                "%s:%d: [%s] appends an unknown id with no allow-insert parameter — an editor "
                "save for a task that was deleted meanwhile resurrects it, carrying the run "
                "state the form snapshotted (`enabled`, `lastRunDate`, a cleared fuse)"
                % (POLICY_REL, line, name)
            ]
        block = None
        for im in re.finditer(r"if *\( *idx *< *0 *\)", body):
            block = _brace_body(body, im.end()) or body[im.end():im.end() + 400]
            if block is not None:
                break
        if block is None:
            return pre + [
                "%s:%d: [%s] the `idx < 0` branch is unreadable — the insert decision cannot "
                "be checked (update this gate's detection)" % (POLICY_REL, line, name)
            ]
        allowed = [p for p in boolean_params if re.search(r"(?<![.\w])%s\b" % re.escape(p), block)]
        if not allowed:
            return pre + [
                "%s:%d: [%s] inserts an unknown id without consulting its allow-insert "
                "parameter (%s) — the parameter exists but guards nothing"
                % (POLICY_REL, line, name, ", ".join(boolean_params))
            ]
        if not re.search(r"return +file\b(?! *\.)", block):
            return pre + [
                "%s:%d: [%s] the `idx < 0` branch has no `return file` path — a refused insert "
                "must leave the document untouched (the same instance), or the store writes "
                "and bumps for nothing" % (POLICY_REL, line, name)
            ]
        return pre
    return pre + [
        "%s: no function appends `file.tasks + task` — the merge/insert site moved "
        "(update this gate's detection)" % POLICY_REL
    ]


def selftest_revision():
    """Negative controls for the deep-audit-1046 S-11 criteria."""
    clean_store = (
        "object ScheduledTasksStore {\n"
        "    private val _revision = MutableStateFlow(0L)\n"
        "    val revision: StateFlow<Long> = _revision.asStateFlow()\n"
        "    private fun writeLocked(ctx: Any, file: Any) {\n"
        "        val wrote = try {\n"
        "            AtomicFileWrite.write(fileFor(ctx), encode(file))\n"
        "            true\n"
        "        } catch (t: Throwable) {\n"
        "            false\n"
        "        }\n"
        "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision(_revision.value, wrote)\n"
        "    }\n"
        "}\n"
    )
    clean_list = (
        "fun ScheduledTasksScreen() {\n"
        "    var reloadKey by remember { mutableIntStateOf(0) }\n"
        "    val storeRevision by ScheduledTasksStore.revision.collectAsState()\n"
        "    val file by produceState<ScheduledTasksFile?>(null, context, reloadKey, storeRevision) {\n"
        "        value = ScheduledTasksStore.load(context)\n"
        "    }\n"
        "    DisposableEffect(lifecycleOwner) {\n"
        "        val observer = LifecycleEventObserver { _, event ->\n"
        "            if (event == Lifecycle.Event.ON_RESUME) reloadKey++\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    clean_edit = (
        "fun ScheduledTaskEditScreen(taskId: String?, onDone: () -> Unit) {\n"
        "    var title by remember { mutableStateOf(\"\") }\n"
        "    var recordMissing by remember { mutableStateOf(false) }\n"
        "    val storeRevision by ScheduledTasksStore.revision.collectAsState()\n"
        "    LaunchedEffect(taskId) {\n"
        "        title = loaded.title\n"
        "    }\n"
        "    LaunchedEffect(taskId, storeRevision) {\n"
        "        val fresh = ScheduledTasksStore.load(context).tasks.firstOrNull { it.id == taskId }\n"
        "        if (shouldMarkMissing(loadedOnce, stillExists = fresh != null)) {\n"
        "            recordMissing = true\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    clean_policy = (
        "object ScheduledTaskTransitions {\n"
        "    fun mergeUpsertTask(file: Any, task: Any, allowInsert: Boolean = true): Any {\n"
        "        val idx = file.tasks.indexOfFirst { it.id == task.id }\n"
        "        if (idx < 0) {\n"
        "            if (!allowInsert) return file\n"
        "            return file.copy(tasks = file.tasks + task)\n"
        "        }\n"
        "        return file\n"
        "    }\n"
        "}\n"
    )
    cases = [
        # [name, args, scanner, expected_violation_count]
        ["revision/clean", [clean_store], scan_revision_stream, 0],
        ["revision/no ticker at all", [
            clean_store.replace(
                "    private val _revision = MutableStateFlow(0L)\n"
                "    val revision: StateFlow<Long> = _revision.asStateFlow()\n", ""),
        ], scan_revision_stream, 1],
        ["revision/ticker never exposed", [
            clean_store.replace(
                "val revision: StateFlow<Long> = _revision.asStateFlow()",
                "private val revision: StateFlow<Long> = _revision.asStateFlow()"),
        ], scan_revision_stream, 1],
        ["revision/bumped before the write", [
            clean_store.replace(
                "        val wrote = try {\n",
                "        _revision.value = _revision.value + 1\n"
                "        val wrote = try {\n").replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n", ""),
        ], scan_revision_stream, 1],
        ["revision/write escapes the funnel", [
            clean_store.replace(
                "\n}\n",
                "\n    private fun otherWrite(ctx: Any, file: Any) {\n"
                "        AtomicFileWrite.write(fileFor(ctx), encode(file))\n"
                "    }\n}\n"),
        ], scan_revision_stream, 1],
        ["list/clean", [clean_store, clean_list], scan_list_screen_keys, 0],
        ["list/does not collect", [
            clean_store,
            clean_list.replace(
                "    val storeRevision by ScheduledTasksStore.revision.collectAsState()\n", ""),
        ], scan_list_screen_keys, 1],
        ["list/revision not a produceState key", [
            clean_store,
            clean_list.replace(
                "null, context, reloadKey, storeRevision", "null, context, reloadKey"),
        ], scan_list_screen_keys, 1],
        ["list/ON_RESUME re-read dropped", [
            clean_store,
            clean_list.replace(
                "if (event == Lifecycle.Event.ON_RESUME) reloadKey++",
                "if (event == null) Unit"),
        ], scan_list_screen_keys, 1],
        ["edit/clean", [clean_store, clean_edit], scan_edit_screen_revision, 0],
        ["edit/does not collect", [
            clean_store,
            clean_edit.replace(
                "    val storeRevision by ScheduledTasksStore.revision.collectAsState()\n", ""),
        ], scan_edit_screen_revision, 1],
        ["edit/no change handler", [
            clean_store,
            clean_edit.replace(
                "    LaunchedEffect(taskId, storeRevision) {\n"
                "        val fresh = ScheduledTasksStore.load(context)"
                ".tasks.firstOrNull { it.id == taskId }\n"
                "        if (shouldMarkMissing(loadedOnce, stillExists = fresh != null)) {\n"
                "            recordMissing = true\n"
                "        }\n"
                "    }\n", ""),
        ], scan_edit_screen_revision, 1],
        ["edit/handler never marks missing", [
            clean_store,
            clean_edit.replace(
                "            recordMissing = true\n        }\n    }\n}\n",
                "        }\n    }\n}\n"),
        ], scan_edit_screen_revision, 1],
        ["edit/handler does not ask whether the target exists", [
            clean_store,
            clean_edit.replace(
                "        val fresh = ScheduledTasksStore.load(context).tasks.firstOrNull"
                " { it.id == taskId }\n"
                "        if (shouldMarkMissing(loadedOnce, stillExists = fresh != null)) {\n"
                "            recordMissing = true\n"
                "        }\n",
                "        recordMissing = true\n"),
        ], scan_edit_screen_revision, 1],
        ["edit/handler rewrites the user's text", [
            clean_store,
            clean_edit.replace(
                "            recordMissing = true\n",
                "            recordMissing = true\n            title = fresh?.title ?: \"\"\n", 1),
        ], scan_edit_screen_revision, 1],
        # ── [meta-audit wide3 scheduled-C1] the bump must MOVE the counter ──
        ["revision/self-assigned (next revision discarded)", [
            clean_store.replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n",
                "        val nextRev = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n"
                "        _revision.value = _revision.value\n"),
        ], scan_revision_stream, 1],
        ["revision/assigned a constant", [
            clean_store.replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n",
                "        _revision.value = 0L\n"),
        ], scan_revision_stream, 1],
        ["revision/update ignores `it`", [
            clean_store.replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n",
                "        _revision.update { 5L }\n"),
        ], scan_revision_stream, 1],
        ["revision/incremented from the old value (green)", [
            clean_store.replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n",
                "        if (wrote) _revision.value = _revision.value + 1\n"),
        ], scan_revision_stream, 0],
        # ── [meta-audit wide4 scheduled-C1] the `=` tail must not accept any
        # expression that merely MENTIONS the backing field ──
        ["w4-revision/`+ 0L` never moves the ticker", [
            clean_store.replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n",
                "        _revision.value = _revision.value + 0L\n"),
        ], scan_revision_stream, 1],
        ["w4-revision/dead arm carries the only step", [
            clean_store.replace(
                "        _revision.value = ScheduledTaskRevisionPolicy.nextRevision("
                "_revision.value, wrote)\n",
                "        _revision.value = if (false) _revision.value + 1L "
                "else _revision.value\n"),
        ], scan_revision_stream, 1],
        ["merge/clean", [clean_policy], scan_merge_insert_guard, 0],
        # ── [meta-audit wide3 scheduled-C4] dead insert guard ──
        ["merge/insert guard short-circuited by `false &&`", [
            clean_policy.replace(
                "            if (!allowInsert) return file\n",
                "            if (false && !allowInsert) return file\n"),
        ], scan_merge_insert_guard, 1],
        ["merge/insert guard `|| true` never refuses", [
            clean_policy.replace(
                "            if (!allowInsert) return file\n",
                "            if (!allowInsert || true) return file\n"),
        ], scan_merge_insert_guard, 1],
        ["merge/unconditional append", [
            clean_policy.replace(
                "        if (idx < 0) {\n"
                "            if (!allowInsert) return file\n"
                "            return file.copy(tasks = file.tasks + task)\n"
                "        }\n",
                "        if (idx < 0) return file.copy(tasks = file.tasks + task)\n"),
        ], scan_merge_insert_guard, 1],
        ["merge/no allow-insert parameter", [
            clean_policy.replace(
                "task: Any, allowInsert: Boolean = true", "task: Any"),
        ], scan_merge_insert_guard, 1],
        ["merge/parameter guards nothing", [
            clean_policy.replace("            if (!allowInsert) return file\n", ""),
        ], scan_merge_insert_guard, 1],
        ["merge/refused insert still writes", [
            clean_policy.replace(
                "            if (!allowInsert) return file\n",
                "            if (!allowInsert) return file.copy(tasks = file.tasks)\n"),
        ], scan_merge_insert_guard, 1],
    ]
    print("  ── deep-audit-1046 S-11 (store change ticker) ──")
    failures = 0
    for name, args, scanner, expected in cases:
        bad = scanner(*args)
        ok = len(bad) == expected
        print(f"  {'✅' if ok else '❌'} {name} → {len(bad)} violation(s), expected {expected}"
              + ("" if ok else ": " + "; ".join(bad)[:150]))
        if not ok:
            failures += 1
    print(f"SCHEDULED_REVISION_SELFTEST={'PASS' if failures == 0 else 'FAIL'}")
    return failures


def selftest():
    """Negative controls for the deep-audit-1030 criteria: each must reject its
    own mutated fixture and accept the clean one."""
    clean_store = (
        "object ScheduledTasksStore {\n"
        "    suspend fun upsertTask(context: Any, task: Any): Any =\n"
        "        mutate(context) { file -> ScheduledTaskTransitions.mergeUpsertTask(file, task) }\n"
        "}\n"
    )
    clean_merge = (
        "object ScheduledTaskTransitions {\n"
        "    fun mergeUpsertTask(file: ScheduledTasksFile, task: ScheduledTask): ScheduledTasksFile {\n"
        "        val idx = file.tasks.indexOfFirst { it.id == task.id }\n"
        "        if (idx < 0) return file.copy(tasks = file.tasks + task)\n"
        "        val existing = file.tasks[idx]\n"
        "        val merged = existing.copy(\n"
        "            title = task.title,\n"
        "            prompt = task.prompt,\n"
        "            windowStart = task.windowStart,\n"
        "            windowEnd = task.windowEnd,\n"
        "            maxTurns = task.maxTurns,\n"
        "            deadlineSec = task.deadlineSec,\n"
        "            maxEstimatedTokens = task.maxEstimatedTokens,\n"
        "        )\n"
        "        return file.copy(tasks = file.tasks.toMutableList().also { it[idx] = merged })\n"
        "    }\n"
        "}\n"
    )
    old_store = (
        "object ScheduledTasksStore {\n"
        "    suspend fun upsertTask(context: Any, task: Any): Any =\n"
        "        mutate(context) { file ->\n"
        "            val idx = file.tasks.indexOfFirst { it.id == task.id }\n"
        "            val tasks = if (idx < 0) {\n"
        "                file.tasks + task\n"
        "            } else {\n"
        "                file.tasks.toMutableList().also { it[idx] = task }\n"
        "            }\n"
        "            file.copy(tasks = tasks)\n"
        "        }\n"
        "}\n"
    )
    clean_screen = (
        "fun ScheduledTaskEditScreen(taskId: String?, onDone: () -> Unit) {\n"
        "    val isNew = taskId == null\n"
        "    var original by remember { mutableStateOf<ScheduledTask?>(null) }\n"
        "    val canSave = prompt.isNotBlank() && windowOk && turnsOk && deadlineOk && tokensOk &&\n"
        "        (isNew || original != null)\n"
        "\n"
        "    if (showDeleteConfirm) {\n"
        "    }\n"
        "}\n"
    )
    # deep-audit-1034 B-04: the not-found state + inverted-window visibility.
    # Built as prefix / render-gate / suffix so a mutation can drop the gate
    # wholesale without carrying a copy of it around.
    edit_prefix = (
        "fun ScheduledTaskEditScreen(taskId: String?, onDone: () -> Unit) {\n"
        "    val isNew = taskId == null\n"
        "    var original by remember { mutableStateOf<ScheduledTask?>(null) }\n"
        "    var recordMissing by remember { mutableStateOf(false) }\n"
        "\n"
        "    if (!isNew) {\n"
        "        LaunchedEffect(taskId) {\n"
        "            val loaded = file.tasks.firstOrNull { it.id == taskId }\n"
        "            if (loaded == null) {\n"
        "                recordMissing = true\n"
        "            } else {\n"
        "                original = loaded\n"
        "                recordMissing = false\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "\n"
        "    val startOk = parse(windowStart) != null\n"
        "    val endOk = parse(windowEnd) != null\n"
        "    val windowOk = startOk && endOk && start < end\n"
        "    val windowInverted = startOk && endOk && !windowOk\n"
        "    val canSave = !recordMissing && prompt.isNotBlank() && windowOk && turnsOk && deadlineOk &&\n"
        "        tokensOk && (isNew || original != null)\n"
        "\n"
    )
    edit_gate = (
        "    if (recordMissing) {\n"
        "        SettingsScaffold(\n"
        "            title = stringResource(R.string.scheduled_edit_title),\n"
        "            navigation = {\n"
        "                MinisTextButton(onClick = onDone) {\n"
        "                    Text(stringResource(R.string.common_back))\n"
        "                }\n"
        "            },\n"
        "        ) {\n"
        "            SettingsSection {\n"
        "                SettingsCardBlock {\n"
        "                    Text(text = stringResource(R.string.scheduled_edit_missing_body))\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "        return\n"
        "    }\n"
        "\n"
    )
    edit_suffix = (
        "    SettingsSection {\n"
        "        RowLabel(text = stringResource(R.string.scheduled_field_window_start))\n"
        "        SectionTextField(\n"
        "            value = windowStart,\n"
        "            onValueChange = { windowStart = it },\n"
        "            isError = !startOk || windowInverted,\n"
        "            singleLine = true,\n"
        "        )\n"
        "    }\n"
        "}\n"
    )
    edit_screen = edit_prefix + edit_gate + edit_suffix

    cases = [
        ("store/clean", (clean_store, clean_merge), scan_merge_semantics),
        ("store/reverted to a whole-record write", (old_store, clean_merge), scan_merge_semantics),
        ("store/merge call dropped", (clean_store.replace(MERGE_CALL, "writeRecord("), clean_merge),
         scan_merge_semantics),
        ("merge/writes enabled back", (clean_store, clean_merge.replace(
            "            title = task.title,\n", "            title = task.title,\n            enabled = task.enabled,\n")),
         scan_merge_semantics),
        ("merge/writes lastRunDate back", (clean_store, clean_merge.replace(
            "            title = task.title,\n", "            title = task.title,\n            lastRunDate = task.lastRunDate,\n")),
         scan_merge_semantics),
        ("merge/copies nothing", (clean_store, clean_merge.replace(
            "            title = task.title,\n"
            "            prompt = task.prompt,\n"
            "            windowStart = task.windowStart,\n"
            "            windowEnd = task.windowEnd,\n"
            "            maxTurns = task.maxTurns,\n"
            "            deadlineSec = task.deadlineSec,\n"
            "            maxEstimatedTokens = task.maxEstimatedTokens,\n", "")),
         scan_merge_semantics),
        ("merge/function gone", (clean_store, None), scan_merge_semantics),
        ("screen/clean", clean_screen, scan_can_save_gate),
        ("screen/snapshot gate dropped", clean_screen.replace(
            "\n        (isNew || original != null)", ""), scan_can_save_gate),
        ("screen/no canSave at all", clean_screen.replace("val canSave", "val maySave"), scan_can_save_gate),
        ("editguard/clean", edit_screen, scan_edit_screen_guards),
        ("editguard/not-found branch dropped", edit_screen.replace(
            "                recordMissing = true\n", ""), scan_edit_screen_guards),
        ("editguard/flag not remembered", edit_screen.replace(
            "var recordMissing by remember", "var recordMissing ="), scan_edit_screen_guards),
        ("editguard/canSave guard dropped", edit_screen.replace(
            "!recordMissing && ", ""), scan_edit_screen_guards),
        ("editguard/not-found render removed", edit_prefix + edit_suffix, scan_edit_screen_guards),
        ("editguard/not-found screen keeps a save shell", edit_screen.replace(
            "                Text(text = stringResource(R.string.scheduled_edit_missing_body))\n",
            "                Text(text = stringResource(R.string.scheduled_edit_missing_body))\n"
            "                SectionTextField(value = prompt)\n"), scan_edit_screen_guards),
        ("editguard/start isError reverted to its own parse", edit_screen.replace(
            "isError = !startOk || windowInverted", "isError = !startOk"), scan_edit_screen_guards),
        ("editguard/subject load renamed", edit_screen.replace(
            "it.id == taskId", "it.id == otherId"), scan_edit_screen_guards),
    ]

    print("scheduled_store_release_guard --selftest")
    failures = 0
    merge_failures = 0
    cansave_failures = 0
    editguard_failures = 0

    # ── [meta-audit wide3 scheduled-PRESENCE] the release must carry the id(s) ──
    clean_rel_store = (
        "object ScheduledTasksStore {\n"
        "    suspend fun deleteTask(context: Any, taskId: String): Any {\n"
        "        val next = mutate(context) { file ->\n"
        "            file.copy(tasks = file.tasks.filterNot { it.id == taskId })\n"
        "        }\n"
        "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))\n"
        "        return next\n"
        "    }\n"
        "    suspend fun importDocument(context: Any, doc: Any) {\n"
        "        lock.withLock { writeLocked(context, doc.copy(running = null)) }\n"
        "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = null)\n"
        "    }\n"
        "}\n"
    )
    for nm, src, want in (
        ("release/clean (setOf(taskId) + null release-all)", clean_rel_store, 0),
        ("release/emptySet releases nothing",
         clean_rel_store.replace("setOf(taskId)", "emptySet()"), 1),
        ("release/empty listOf() releases nothing",
         clean_rel_store.replace("setOf(taskId)", "listOf()"), 1),
        ("release/constant set carries no removed id",
         clean_rel_store.replace("setOf(taskId)", 'setOf("a")'), 1),
        ("release/expression fed from a local id set (green)",
         clean_rel_store.replace(
             "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))\n",
             "        val gone = setOf(taskId)\n"
             "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = gone)\n"), 0),
        # ── [meta-audit wide4 scheduled-PRESENCE] the set may be emptied by a
        # chain or a literal-decided `if` while the id stays in the spelling ──
        ("w4-release/chained filter empties the removal set",
         clean_rel_store.replace(
             "setOf(taskId)", "setOf(taskId).filter { false }.toSet()"), 1),
        ("w4-release/.takeIf(false) empties the removal set",
         clean_rel_store.replace(
             "setOf(taskId)", "setOf(taskId).takeIf { false } ?: emptySet()"), 1),
        ("w4-release/literal-decided inline if picks the empty arm",
         clean_rel_store.replace(
             "setOf(taskId)", "if (false) setOf(taskId) else emptySet()"), 1),
        ("w4-release/id difference of two documents (green)",
         clean_rel_store.replace(
             "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))\n",
             "        val beforeIds = before.tasks.map { it.id }\n"
             "        val afterIds = next.tasks.map { it.id }\n"
             "        ScheduledTaskRunner.onTasksRemoved("
             "removedTaskIds = (beforeIds - afterIds).toSet())\n"), 0),
    ):
        masked_src = strip_code(src)
        assert masked_src is not None, "selftest fixture must lex"
        _fns, bad = check_removals(masked_src)
        ok = (len(bad) == want) and (not bad or any("removal set" in b for b in bad))
        print(f"  {'✅' if ok else '❌'} {nm} → {len(bad)} violation(s), expected {want}"
              + ("" if ok else ": " + "; ".join(bad)[:150]))
        if not ok:
            failures += 1
    # ── [meta-audit wide4] family-hole / parser-death / dataflow arms ──
    w4_clean_policy = (
        "object ScheduledTaskTransitions {\n"
        "    fun mergeUpsertTask(file: Any, task: Any, allowInsert: Boolean = true): Any {\n"
        "        val idx = file.tasks.indexOfFirst { it.id == task.id }\n"
        "        if (idx < 0) {\n"
        "            if (!allowInsert) return file\n"
        "            return file.copy(tasks = file.tasks + task)\n"
        "        }\n"
        "        return file\n"
        "    }\n"
        "}\n"
    )
    MERGE_LINE = ("        mutate(context) { file -> "
                  "ScheduledTaskTransitions.mergeUpsertTask(file, task) }")
    w4_store_live_merge = clean_store.replace(
        MERGE_LINE,
        "        mutate(context) { file -> file.copy(tasks = file.tasks.map { task }) }"
        ".also { ScheduledTaskTransitions.mergeUpsertTask(it, task) }")
    w4_store_dead_merge = clean_store.replace(
        MERGE_LINE,
        "        mutate(context) { file -> file.copy(tasks = file.tasks.map { task }) }"
        ".also { if (false) ScheduledTaskTransitions.mergeUpsertTask(it, task) }")
    w4_store_unlexable = clean_store.replace(
        "object ScheduledTasksStore {",
        'object ScheduledTasksStore {\n    private val broken = "unterminated\n')
    w4_merge_char_literal = clean_merge.replace(
        "        return file.copy(tasks = file.tasks.toMutableList().also { it[idx] = merged })\n",
        "        val _open = '\"'; val overwritten = merged.copy(enabled = task.enabled); "
        "val _close = '\"'\n"
        "        return file.copy(tasks = file.tasks.toMutableList().also "
        "{ it[idx] = overwritten })\n")
    w4_cases = [
        # (name, args, scanner, expected_violation_count, message fragment)
        ("w4-store/merge call swallowed by a live `.also` tail",
         (w4_store_live_merge, clean_merge), scan_merge_semantics, 1,
         "no longer routes through ScheduledTaskTransitions.mergeUpsertTask"),
        ("w4-store/merge call kept only behind a dead `if`",
         (w4_store_dead_merge, clean_merge), scan_merge_semantics, 1,
         "no longer routes through ScheduledTaskTransitions.mergeUpsertTask"),
        ("w4-store/store the lexer cannot parse is refused (fail-closed)",
         (w4_store_unlexable, clean_merge), scan_merge_semantics, 1,
         "cannot mask this file"),
        ("w4-lex/char literal no longer hides the spliced `copy(enabled=…)`",
         (clean_store, w4_merge_char_literal), scan_merge_semantics, 1,
         "copies `enabled`"),
        ("w4-merge/insert guard `!true &&` is a dead guard",
         (w4_clean_policy.replace(
             "            if (!allowInsert) return file\n",
             "            if (!true && !allowInsert) return file\n"),),
         scan_merge_insert_guard, 1, "allow-insert parameter"),
        ("w4-merge/insert guard `!false ||` is constant-true",
         (w4_clean_policy.replace(
             "            if (!allowInsert) return file\n",
             "            if (!false || !allowInsert) return file\n"),),
         scan_merge_insert_guard, 1, "never decides"),
        ("w4-merge/insert guard `0 == 1 &&` is a dead guard",
         (w4_clean_policy.replace(
             "            if (!allowInsert) return file\n",
             "            if (0 == 1 && !allowInsert) return file\n"),),
         scan_merge_insert_guard, 1, "allow-insert parameter"),
    ]
    for nm, args, scan_fn, want, frag in w4_cases:
        bad = scan_fn(*args)
        ok = len(bad) == want and (not frag or any(frag in b for b in bad))
        print(f"  {'✅' if ok else '❌'} {nm} → {len(bad)} violation(s), expected {want}"
              + ("" if ok else ": " + "; ".join(bad)[:150]))
        if not ok:
            failures += 1
            merge_failures += 1
    for name, fixture, scan in cases:
        args = fixture if isinstance(fixture, tuple) else (fixture,)
        bad = scan(*args)
        ok = bool(bad) if not name.endswith("/clean") else (not bad)
        print(f"  {'✅' if ok else '❌'} {name} → {'clean' if not bad else '; '.join(bad)[:90]}")
        if not ok:
            failures += 1
            if name.startswith("merge/") or name.startswith("store/"):
                merge_failures += 1
            elif name.startswith("editguard/"):
                editguard_failures += 1
            else:
                cansave_failures += 1
    print(f"  {'✅ selftest passed' if not failures else f'❌ selftest failures: {failures}'}")
    print(f"SCHEDULED_MERGE_SELFTEST={'PASS' if merge_failures == 0 else 'FAIL'}")
    print(f"SCHEDULED_CANSAVE_SELFTEST={'PASS' if cansave_failures == 0 else 'FAIL'}")
    print(f"SCHEDULED_EDITGUARD_SELFTEST={'PASS' if editguard_failures == 0 else 'FAIL'}")
    revision_failures = selftest_revision()
    return 1 if (failures or revision_failures) else 0


UNLEXABLE = (
    "%s: the shared Kotlin lexer cannot mask this file (%s) — the gate refuses to "
    "judge a half-masked text: a parser it does not steer is a body it cannot see, "
    "and every criterion below would be reading blanked-out code (fail-closed)"
)

_LEX_ERROR = [""]


def _unlexable(rel):
    return UNLEXABLE % (rel, _LEX_ERROR[0] or "unparsable")


def strip_code(src):
    """Comment/string mask of [src] from the shared Kotlin lexer.

    Offsets and newlines are preserved (every caller slices by index), comments
    and string/char CONTENTS are blanked. Returns None when the lexer cannot
    parse the text — callers MUST treat that as a violation, never as "nothing
    to see": silently returning the raw text (or an empty string) is exactly how
    the parser-death axis walked a broken file past the gate.

    [meta-audit wide4 scheduled-S1] This function used to be a hand-rolled
    state machine that knew `//`, `/* */`, `\"\"\"` and `"` — and nothing else.
    Two char literals `'"'` therefore opened and closed a phantom STRING that
    hid every statement between them, so `copy(enabled = task.enabled)` spliced
    in behind such a literal was invisible to `copy_arg_names` while the gate
    printed green. A masker is not a place to be clever; it is a place to have
    exactly one implementation.
    """
    ok, reason, views = _lex_analyse(src)
    if not ok:
        _LEX_ERROR[0] = reason
        return None
    return views["code"]



def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def _strip_outer_parens(expr):
    e = expr.strip()
    while len(e) >= 2 and e[0] == "(" and e[-1] == ")":
        depth, ok = 0, True
        for i, ch in enumerate(e):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0 and i != len(e) - 1:
                    ok = False
                    break
        if not ok:
            break
        e = e[1:-1].strip()
    return e


def _split_top(expr, op):
    parts, depth, cur, i = [], 0, [], 0
    while i < len(expr):
        ch = expr[i]
        if ch in "\"'":
            j = i + 1
            while j < len(expr) and expr[j] != ch:
                if expr[j] == "\\":
                    j += 1
                j += 1
            cur.append(expr[i:j + 1])
            i = j + 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if depth == 0 and expr.startswith(op, i):
            parts.append("".join(cur))
            cur = []
            i += len(op)
            continue
        cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def fold_bool(expr):
    """True / False / None for a Kotlin condition, folding literal operands.

    [meta-audit wide3 scheduled-C4] The insert guard used to be judged by
    substring presence inside the `idx < 0` branch. `if (false && !allowInsert)
    return file` keeps both `allowInsert` and `return file` in the branch while
    removing the decision, so an editor save for a task deleted meanwhile
    resurrected the record — the exact defect the criterion was written for.

    [meta-audit wide4 scheduled-C4] The local folder handled `||`, `&&`, parens
    and bare `true`/`false` — and NOT `!`, so `!true && !allowInsert` folded to
    None, the dead branch was never blanked and the guard read green.

    Leaves are folded by the shared `const_bool` (unary `!`, `0 == 1`, `1 == 1`,
    constant string equality), but the SPLIT stays here: the shared folder tries
    a leading `!` before it splits top-level operators, so `!true && !allowInsert`
    would be read as `!(true && !allowInsert)` — right operand precedence. `!`
    binds tighter than `&&` in Kotlin, so `&&` / `||` are split first and only
    the leaves go to the shared folder. (Registered in the report: the shared
    module is frozen for this wave.)
    """
    e = _strip_outer_parens(expr)
    if not e:
        return None
    ors = _split_top(e, "||")
    if len(ors) > 1:
        vals = [fold_bool(x) for x in ors]
        if any(v is True for v in vals):
            return True
        if all(v is False for v in vals):
            return False
        return None
    ands = _split_top(e, "&&")
    if len(ands) > 1:
        vals = [fold_bool(x) for x in ands]
        if any(v is False for v in vals):
            return False
        if all(v is True for v in vals):
            return True
        return None
    return _const_bool(e)


def _match_brace(scan, open_idx):
    depth = 0
    for k in range(open_idx, len(scan)):
        if scan[k] == "{":
            depth += 1
        elif scan[k] == "}":
            depth -= 1
            if depth == 0:
                return k
    return None


def _stmt_span(scan, i):
    n = len(scan)
    while i < n and scan[i] in " \t\r\n":
        i += 1
    if i >= n:
        return n
    if scan[i] == "{":
        end = _match_brace(scan, i)
        return (end + 1) if end is not None else n
    depth = 0
    while i < n:
        ch = scan[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                return i
        elif ch == "\n" and depth == 0:
            return i + 1
        i += 1
    return n


def prune_dead_branches(scan):
    """Blank literal-decided branches (offsets and line numbers preserved)."""
    out = scan
    IF_RE = re.compile(r"\bif\s*\(")
    for _round in range(50):
        changed = False
        for m in IF_RE.finditer(out):
            open_paren = m.end() - 1
            close_paren = _match_paren(out, open_paren)
            if close_paren is None:
                continue
            val = fold_bool(out[open_paren + 1:close_paren])
            if val is None:
                continue
            then_start = close_paren + 1
            while then_start < len(out) and out[then_start] in " \t\r\n":
                then_start += 1
            then_end = _stmt_span(out, then_start)
            k = then_end
            while k < len(out) and out[k] in " \t\r\n":
                k += 1
            if val is False:
                # Blank the HEADER as well as the dead branch. Leaving
                # `if (literal-false)` in place made the next round treat the
                # statement AFTER the (now empty) branch as the dead body, so one
                # dead guard ate its way down the rest of the function — which
                # silently deleted the very `return file.copy(tasks = …)` the
                # insert criterion has to look at.
                dead = [(m.start(), close_paren + 1), (then_start, then_end)]
                if out.startswith("else", k):
                    dead.append((k, k + 4))  # the live arm stays, the keyword goes
            elif out.startswith("else", k):
                dead = [(m.start(), close_paren + 1), (k, _stmt_span(out, k + 4))]
            else:
                continue
            for start_at, end_at in dead:
                seg = out[start_at:end_at]
                blanked = "".join(ch if ch == "\n" else " " for ch in seg)
                if seg != blanked:
                    out = out[:start_at] + blanked + out[end_at:]
                    changed = True
            if changed:
                break
        if not changed:
            break
    return out


def _match_paren(scan, open_idx):
    """Index of the `)` matching the `(` at [open_idx], or None."""
    depth = 0
    i, n = open_idx, len(scan)
    while i < n:
        ch = scan[i]
        if ch in "\"'":
            j = i + 1
            while j < n and scan[j] != ch and scan[j] != "\n":
                if scan[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


DECL_TAIL_RE = re.compile(
    r"(?:@\w+\s+)*"
    r"(?:(?:private|internal|public|protected|suspend|override|open|inline|operator|"
    r"tailrec|actual|expect|const|lateinit|abstract|fun)\b[\w\s]*)*"
    r"(?:fun|val|var|object|class|interface)\b"
)


def _expr_end(scan, start):
    """End of the expression starting at [start] (a new declaration or `}` ends it)."""
    i, n, depth = start, len(scan), 0
    while i < n:
        ch = scan[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                return i
        if depth == 0 and ch == "\n":
            k = i + 1
            while k < n and scan[k] in " \t":
                k += 1
            if k >= n or scan[k] == "}" or DECL_TAIL_RE.match(scan, k):
                return i
        i += 1
    return n


def _body_span(scan, m):
    """(start, end) of the body of the `fun` whose declaration matched at [m].

    [meta-audit wide3 scheduled-S1] The old version was
    `open_idx = scan.find("{", m.end())` — the first `{` after the function
    NAME. A default lambda parameter
    (`merger: (ScheduledTasksFile) -> ScheduledTasksFile = { f -> mergeUpsertTask(f, task, allowInsert) }`)
    sits inside the parameter list, so `upsertTask`'s "body" became the default
    argument: it still contained `mergeUpsertTask(`, the REAL body
    (`file.copy(tasks = file.tasks.map { … })`, a whole-record overwrite) was
    never read, and the merge criterion printed green. Walk the parameter list
    first, then take the body that follows it (block body, or the expression
    of an `=` body — `mutate(context) { … }` included).
    """
    open_paren = scan.find("(", m.end() - 1)
    if open_paren < 0:
        return None
    close_paren = _match_paren(scan, open_paren)
    if close_paren is None:
        return None
    n = len(scan)
    j = close_paren + 1
    while j < n:
        ch = scan[j]
        if ch == "{":
            depth = 0
            for k in range(j, n):
                if scan[k] == "{":
                    depth += 1
                elif scan[k] == "}":
                    depth -= 1
                    if depth == 0:
                        return j, k + 1
            return None
        if ch == "=" and scan[j:j + 2] != "==":
            end = _expr_end(scan, j)
            if end <= j + 1:
                return None
            return j, end
        if ch == ";":
            return None
        if ch == "\n":
            k = j + 1
            while k < n and scan[k] in " \t":
                k += 1
            if k >= n or scan[k] == "}" or DECL_TAIL_RE.match(scan, k):
                return None  # declaration-only member (interface / abstract)
            j = k
            continue
        j += 1
    return None


BUMP_RHS_RE = re.compile(r"_%s\s*\.\s*value\s*=(?!=)([^\n;]*)")


def bump_stalls(scan, backing, bump_idx):
    """None when the bump moves the counter forward, else the reason it does not."""
    text = scan[bump_idx:bump_idx + 400]
    m = re.match(r"_%s\s*\.\s*update\s*[({]([^})]*)" % re.escape(backing), text)
    if m:
        if re.search(r"\bit\b", m.group(1)):
            return None
        return ("_%s.update { … } does not derive the new value from `it` — the "
                "ticker is set to something independent of the old value, so a "
                "repeated bump stops emitting" % backing)
    m = re.match(r"_%s\s*\.\s*value\s*\+\+" % re.escape(backing), text)
    if m:
        return None
    m = re.match(r"_%s\s*\.\s*value\s*\+=\s*(\S+)" % re.escape(backing), text)
    if m:
        if m.group(1).strip() in ("0", "0L"):
            return ("_%s.value += 0 never changes the value — a StateFlow is "
                    "distinctUntilChanged, so no screen is notified" % backing)
        return None
    m = re.match(r"_%s\s*\.\s*value\s*=(?!=)\s*([^\n;]*)" % re.escape(backing), text)
    if not m:
        return None
    rhs = " ".join(m.group(1).split()).strip()
    if not rhs:
        return ("the ticker is assigned an unreadable expression — the guard cannot "
                "tell whether it moves")
    if rhs in ("_%s.value" % backing,):
        return ("_%s.value = _%s.value is a self-assignment — MutableStateFlow is "
                "distinctUntilChanged, so nothing is emitted and every screen keeps "
                "the stale document (the ticker exists but never moves)" % (backing, backing))
    if re.fullmatch(r"-?\d+L?|0L|true|false|null|\"\"", rhs):
        return ("the ticker is assigned the constant %s — it emits once and never "
                "again, so later changes are invisible" % rhs)
    # [meta-audit wide4 scheduled-C1] The old tail was
    #   if "_%s" in rhs: return None        # ← wide-open: `_revision.value + 0L`
    #   if nextRevision( in rhs: return None
    # so ANY expression that merely MENTIONED the backing field counted as a
    # bump, and `_revision.value = _revision.value + 0L` (or a dead
    # `if (false) … + 1L else _revision.value`) left the StateFlow emitting
    # nothing while the gate reported a live ticker. Replaced by a positive
    # whitelist: the RHS must either feed the current value INTO nextRevision(…)
    # or add a NON-ZERO step to it.
    ref = re.compile(r"_%s\s*\.\s*value\b" % re.escape(backing))
    call = re.search(r"\bnextRevision\s*\(", rhs)
    if call:
        open_at = rhs.find("(", call.start())
        args = _paren_args(rhs, open_at) if open_at >= 0 else None
        if args is not None and ref.search(args):
            return None
        return ("the ticker is assigned a nextRevision(…) call that never reads "
                "`_%s.value` — the counter cannot be shown to move" % backing)
    step = re.fullmatch(
        r"_%s\s*\.\s*value\s*([+-])\s*(\d+)L?" % re.escape(backing), rhs)
    if step:
        if int(step.group(2)) == 0:
            return ("_%s.value %s 0 never changes the value — a StateFlow is "
                    "distinctUntilChanged, so no screen is notified"
                    % (backing, step.group(1)))
        return None
    # RHS is a local: it only counts when that local holds nextRevision(…)
    ident = re.match(r"^([A-Za-z_]\w*)$", rhs)
    if ident:
        bound = re.search(
            r"\b(?:val|var)\s+" + re.escape(ident.group(1)) +
            r"\s*(?::[^=\n]+)?=\s*[^\n]*nextRevision\s*\(", scan)
        if bound:
            return None
        return ("the ticker is assigned `%s`, which does not hold the value of "
                "nextRevision(…) — the computed next revision may be discarded"
                % ident.group(1))
    return ("the assigned expression (`%s`) does not derive from the current ticker "
            "value — the guard cannot prove the counter moves" % rhs)


def function_spans(scan):
    """[(name, body_start_index, body_end_index_exclusive, start_line)] for each fun."""
    spans = []
    for m in FUN_RE.finditer(scan):
        span = _body_span(scan, m)
        if span is None:
            continue
        open_idx, end_idx = span
        spans.append((m.group(1), open_idx, end_idx, line_of(scan, m.start())))
    return spans


def depth_at(scan, start, idx):
    """Brace depth inside the function body at index `idx` (body brace = depth 1)."""
    return scan.count("{", start + 1, idx) - scan.count("}", start + 1, idx) + 1


RELEASE_ARG_BUILDERS = {
    "setOf", "listOf", "mutableSetOf", "mutableListOf", "emptySet", "emptyList",
    "hashSetOf", "linkedSetOf", "sortedSetOf", "removedTaskIds", "arrayListOf",
}
NULL_SET_SPELLINGS = {"null", "nullSet"}

# [meta-audit wide4 scheduled-PRESENCE] Chain calls that can hand back FEWER
# elements than they were given — i.e. that can turn `setOf(taskId)` into an
# empty set without ever looking like `emptySet()`. `toSet()` / `toList()` /
# `sorted()` are absent on purpose: they cannot drop ids.
EMPTYING_CHAINS = {
    "filter", "filterNot", "takeIf", "takeUnless", "take", "takeWhile", "drop",
    "dropWhile", "dropLast", "minus", "subtract", "intersect", "distinct",
    "distinctBy", "map", "mapNotNull", "mapNotNullTo", "let", "run", "also",
    "apply", "ifEmpty", "orEmpty", "remove", "removeAll", "retainAll",
}


def _paren_close(text, open_at):
    """Index of the `)` matching the `(` at [open_at], or None."""
    if open_at < 0 or open_at >= len(text) or text[open_at] != "(":
        return None
    depth = 0
    for k in range(open_at, len(text)):
        if text[k] == "(":
            depth += 1
        elif text[k] == ")":
            depth -= 1
            if depth == 0:
                return k
    return None


def check_release_argument(scan, body, call_off):
    """None if the `removedTaskIds` argument can actually release something, else
    a phrase describing why it cannot.

    [meta-audit wide3 scheduled-PRESENCE] The criterion used to be
    "`ScheduledTaskRunner.onTasksRemoved(` appears at body depth 1" — the CALL.
    The argument decides whether any lock is released, and `emptySet()` passes a
    call-shaped criterion while releasing nothing: `ScheduledTaskReleasePlan`
    sees an empty removed set, keeps `runningTaskId`, and the deleted task's run
    holds the single-flight gate until the reap deadline. There are exactly two
    meaningful spellings: `null` (the plan's "release everything" path, used by
    the restore) and an expression that carries the id(s) being removed.
    """
    open_at = body.find("(", call_off)
    if open_at < 0:
        return "the call's argument list could not be read"
    depth = 0
    close_at = None
    for k in range(open_at, len(body)):
        if body[k] == "(":
            depth += 1
        elif body[k] == ")":
            depth -= 1
            if depth == 0:
                close_at = k
                break
    if close_at is None:
        return "the call's argument list never closes"
    arg = body[open_at + 1:close_at].strip()
    if not arg:
        return "the call passes no removedTaskIds at all"
    if "=" in arg.split("/")[0] and arg.startswith("removedTaskIds"):
        arg = arg.split("=", 1)[1].strip()
    elif arg.count("=") == 1 and "(" not in arg.split("=")[0]:
        arg = arg.split("=", 1)[1].strip()
    if arg in NULL_SET_SPELLINGS:
        return None
    squashed = " ".join(arg.split())
    if re.fullmatch(
            r"(?:%s)\s*(?:<[^<>]*>)?\s*\(\s*\)"
            % "|".join(sorted(RELEASE_ARG_BUILDERS - {"removedTaskIds"})), squashed):
        return ("passes %s — an empty removal set releases nothing, which is exactly "
                "the deleted-task lock leak this criterion exists for" % squashed)
    # [meta-audit wide4 scheduled-PRESENCE] The set can be emptied by a CHAIN that
    # leaves the builder call and the id in the spelling:
    #   setOf(taskId).filter { false }.toSet()   /  .takeIf { false }  /  .minus(taskId)
    # The old criterion compared identifier sets, saw `taskId` in the argument and
    # in the body, and passed — while the runner received an empty set and kept
    # `runningTaskId`, so every other due task degraded to `overlap` for ~30 min.
    # A removal set is allowed to be a builder call, a local, or a difference of
    # two id collections; it is NOT allowed to be anything that can filter away
    # the ids it just took.
    for cm in re.finditer(r"\??\.\s*([A-Za-z_]\w*)\s*[({]", squashed):
        if cm.group(1) in EMPTYING_CHAINS:
            return ("passes %s — the `.%s` chain can empty the set while keeping the "
                    "removed id in the spelling, so the runner may be told about a "
                    "removal that carries nothing" % (squashed, cm.group(1)))
    # Same family, expression form: `if (false) setOf(taskId) else emptySet()`
    # keeps the id AND the builder in the argument; only the live arm matters.
    for im in re.finditer(r"\bif\s*\(", squashed):
        open_at = squashed.find("(", im.start())
        close_at = _paren_close(squashed, open_at)
        if close_at is None:
            continue
        cond = squashed[open_at + 1:close_at]
        if _const_bool(cond) is not None:
            return ("passes %s — the removal set is picked by a literal-decided "
                    "condition (`if (%s) …`), so one arm is dead code and the live "
                    "one may carry no removed id at all"
                    % (squashed, " ".join(cond.split())))
    idents = {
        t for t in re.findall(r"[A-Za-z_]\w*", squashed)
        if t not in RELEASE_ARG_BUILDERS
        and t not in {"it", "this", "true", "false", "null", "trim", "toSet", "toList"}
    }
    if not idents:
        return ("passes %s — it carries no identifier, so no removed id reaches the "
                "runner" % squashed)
    rest = body[:call_off] + body[close_at + 1:]
    body_idents = set(re.findall(r"[A-Za-z_]\w*", rest))
    carried = idents & body_idents
    if not carried:
        return ("passes %s — none of %s is produced by this function, so the guard "
                "cannot verify the removed ids travel (re-point this criterion if the "
                "ids now come from a helper)" % (squashed, ", ".join(sorted(idents))))
    return None


def check_removals(scan):
    """(removal_fns, findings) for every function that drops tasks from the doc."""
    removal_fns = []
    for name, start, end, line in function_spans(scan):
        body = scan[start:end]
        if any(r.search(body) for r in REMOVAL_RES):
            removal_fns.append((name, start, end, line))
    findings = []
    for name, start, end, line in removal_fns:
        body = scan[start:end]
        hits = [start + m.start() for m in re.finditer(re.escape(RELEASE_CALL), body)]
        if not hits:
            findings.append(
                "%s:%d: [%s] drops tasks from the persisted document but never calls "
                "%s — the runner's in-process single-flight lock survives the removal "
                "(B-7c)" % (STORE_REL, line, name, RELEASE_CALL.rstrip("("))
            )
            continue
        if len(hits) > 1:
            findings.append(
                "%s:%d: [%s] calls %s %d times — exactly one release per removal"
                % (STORE_REL, line, name, RELEASE_CALL.rstrip("("), len(hits))
            )
        for hit in hits:
            d = depth_at(scan, start, hit)
            if d != 1:
                findings.append(
                    "%s:%d: [%s] %s sits at brace depth %d (nested inside the store "
                    "lock) — LOCK ORDER: the release takes the runner's checkMutex, "
                    "which a fire pass holds while calling back into this store "
                    "(`markRunning`); nesting deadlocks. Call it after the lock."
                    % (STORE_REL, line_of(scan, hit), name, RELEASE_CALL.rstrip("("), d)
                )
            arg_bad = check_release_argument(scan, body, hit - start)
            if arg_bad:
                findings.append(
                    "%s:%d: [%s] %s — the runner is told about a removal set that "
                    "carries no removed id, so its single-flight state is never "
                    "released and every other due task degrades to `overlap` until "
                    "the reap deadline (B-7c returns)"
                    % (STORE_REL, line_of(scan, hit), name, arg_bad)
                )
    return removal_fns, findings


def main():
    if "--selftest" in sys.argv:
        return selftest()

    root = sys.argv[1] if len(sys.argv) > 1 else "."
    path = os.path.join(root, STORE_REL)
    findings = []
    rot = []

    if not os.path.isfile(path):
        print("ROT: %s not found — the gate cannot see the shape it guards." % STORE_REL)
        print("     If the store moved, teach this gate the new path.")
        return 1

    src = open(path, encoding="utf-8").read()
    scan = strip_code(src)
    if scan is None:
        print(_unlexable(STORE_REL))
        print("\n1 violation(s).")
        return 1
    spans = function_spans(scan)

    removal_fns, removal_findings = check_removals(scan)
    findings.extend(removal_findings)

    if not spans:
        rot.append("no `fun` at object-member indentation was parsed")
    if not removal_fns:
        rot.append(
            "no task-removal function was detected (expected deleteTask / "
            "importDocument shapes: `filterNot { it.id ==` or `copy(running = null)`)"
        )

    # File-wide: no runner reference while a store lock block is open.
    depth = 0
    lock_body_depth = None
    for lineno, raw in enumerate(scan.split("\n"), start=1):
        if lock_body_depth is None and LOCK_RE.search(raw):
            lock_body_depth = depth + 1
        if "ScheduledTaskRunner." in raw and lock_body_depth is not None:
            findings.append(
                "%s:%d: ScheduledTaskRunner referenced inside a `lock.withLock` block "
                "(opened at body depth %d) — LOCK ORDER (see "
                "ScheduledTaskRunner.onTasksRemoved)"
                % (STORE_REL, lineno, lock_body_depth)
            )
        depth += raw.count("{") - raw.count("}")
        if lock_body_depth is not None and depth < lock_body_depth:
            lock_body_depth = None

    # deep-audit-1030 ISSUE-08: an editor save must not be able to speak about
    # run state. These subjects live in companion files; on a synthetic tree
    # that carries neither, they report SKIP rather than a violation.
    merge_rel, merge_raw = find_merge_source(root)
    s1 = scan_merge_semantics(src, merge_raw)
    if s1 is None:
        print("SKIP: no `upsertTask` in %s — the merge criteria do not apply here" % STORE_REL)
    elif s1:
        findings.extend(s1)
    else:
        print("OK: ScheduledTasksStore.upsertTask routes through %s — a save copies the "
              "editable spec only; run state and `enabled` stay with the stored record"
              % (merge_rel or "the merge"))
    screen_raw = read_optional(root, EDIT_SCREEN_REL)
    if screen_raw is None:
        print("SKIP: %s not in this tree — the canSave / not-found criteria do not apply here"
              % EDIT_SCREEN_REL)
    else:
        s2 = scan_can_save_gate(screen_raw)
        if s2:
            findings.extend(s2)
        else:
            print("OK: ScheduledTaskEditScreen.canSave waits for the loaded snapshot "
                  "before an edit may be saved")
        s3 = scan_edit_screen_guards(screen_raw)
        if s3:
            findings.extend(s3)
        else:
            print("OK: ScheduledTaskEditScreen surfaces a gone record (explicit not-found "
                  "screen; Save and Delete unreachable there) and flags an inverted window "
                  "on the form rather than leaving Save silently disabled")

    if rot:
        print("ROT: %s" % STORE_REL)
        for r in rot:
            print("  - %s" % r)
        print("  The gate fails loudly instead of passing vacuously. If the store was")
        print("  refactored, update the detection in this script.")
        return 1

    # deep-audit-1046 S-11: the store's change ticker and the two screens that
    # must follow it, plus the explicit allow-insert guard on the merge.
    c1 = scan_revision_stream(src)
    list_raw = read_optional(root, LIST_SCREEN_REL)
    if list_raw is None:
        print("SKIP: %s not in this tree — the list-screen criteria do not apply here"
              % LIST_SCREEN_REL)
        c2 = ["SKIP: list screen missing"]
    else:
        c2 = scan_list_screen_keys(src, list_raw)
    edit_raw = read_optional(root, EDIT_SCREEN_REL)
    if edit_raw is None:
        print("SKIP: %s not in this tree — the edit-screen criteria do not apply here"
              % EDIT_SCREEN_REL)
        c3 = ["SKIP: edit screen missing"]
    else:
        c3 = scan_edit_screen_revision(src, edit_raw)
    merge_rel, merge_raw = find_merge_source(root)
    if merge_raw is None:
        print("SKIP: no `mergeUpsertTask(` anywhere — the insert-guard criteria do not apply")
        c4 = ["SKIP: merge source missing"]
    else:
        c4 = scan_merge_insert_guard(merge_raw)
    for name, bad, ok_msg in (
        ("C1 change ticker", c1,
         "the store exposes a StateFlow change ticker, bumped only after a landed write"),
        ("C2 list keys on it", c2,
         "the list collects the ticker, keys its produceState on it and keeps the "
         "ON_RESUME re-read"),
        ("C3 editor follows it", c3,
         "the editor follows the ticker: a vanished target raises recordMissing and a "
         "change never rewrites the form's text"),
        ("C4 insert is opt-in", c4,
         "mergeUpsertTask's unknown-id branch is guarded by its allow-insert parameter and "
         "a refusal leaves the document untouched"),
    ):
        if bad:
            for f in bad:
                if not f.startswith("SKIP"):
                    findings.append("[%s] %s" % (name, f))
        else:
            print("OK (%s): %s" % (name, ok_msg))

    if findings:
        for f in findings:
            print(f)
        print("\n%d violation(s)." % len(findings))
        return 1

    print(
        "OK: %d removal function(s) release the runner (%s) at top level."
        % (len(removal_fns), ", ".join(n for n, _, _, _ in removal_fns))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
