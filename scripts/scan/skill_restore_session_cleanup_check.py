#!/usr/bin/env python3
"""
Gate: FIX-A (deep-audit-1021 ISSUE-01 + ISSUE-10) — skill restore id threading
+ cross-db session cleanup closure.

ISSUE-01 (SkillRepository.kt)
  `add()` used to recompute `slugify(name)` for its landing id even when the
  caller had ALREADY resolved the id through SkillRestorePolicy.resolveImportId
  (importFromContent's preferredId parameter, installBundledSkill's assets
  dirName). A renamed skill (pure-CJK name -> blank slug -> add returned null)
  was silently lost, or landed under a fresh slug while its backup payload and
  session_skill_overrides still pointed at the old id (dir split). The rule:
  `add()`'s first assignment resolves the id via
  SkillRestorePolicy.resolveImportId(preferredId, slugify(name)), and both
  production callers pass their resolved id through.

ISSUE-10 (ChatRepository.kt + MinisApp.kt)
  SkillRepository.clearSessionOverrides / MCPRepository.clearSessionOverrides
  existed with ZERO production callers — a deleted session's "Allow in this
  session" skill/mcp toggles leaked into every later session that reused the
  id. The rule: ChatRepository exposes an onSessionDeleted hook that EVERY
  function removing `sessions` rows reaches AFTER the row is gone (user delete,
  row-only delete and — since dv21-a2, deep-audit-1041 A-02 — the startup
  empty-session sweep `deleteEmptySessions`), and MinisApp injects a callback
  that reaps both repositories (with skill/mcp constructed BEFORE
  chatRepository so the callback references the real instances). The entry
  point set is DERIVED from the tree: the sweep was the entry point that a
  hard-coded two-entry list made invisible.

Checks assert PRESENCE of the routing rather than the absence of a spelling,
so a refactor that keeps the contract is a deliberate edit to this gate rather
than a silent pass.

I04 (SkillRepository.kt, deep-audit-1029)
  `recordSkillUse` computed its mapped list under `synchronized(addLock)` but
  published `_skills.value = next` AFTER the release — while the KDoc directly
  above claimed the update happened under the same lock as `add`. Two
  concurrent calls (parallel tool executions on Dispatchers.IO) both read the
  pre-publish snapshot, so memory drifted one behind the atomic SQL increment;
  a concurrent `add()` could be erased outright by the stale publish. The rule:
  every `_skills.value =` inside `recordSkillUse` sits inside the
  `synchronized(addLock)` block (criterion on lock ownership, not on the shape
  of the right-hand side).

I01/I02 (deep-audit-1030, FIX-A)
  `ensureSession()`'s `if (isDraft)` block already re-points the skill / mcp
  overrides and the BrowserTabPool onto the real session id, but had no hook
  for the pinned WebApp shortcuts: their `scope_context` / `source_session_id`
  were frozen at pin time (`__new__<uuid>`), and `migrateDraftResources` deletes
  the draft directory the resolver reads through — the launcher icon reports
  "Source Missing" forever. Symmetrically, MinisApp's `onSessionDeleted` hook
  reaps the skill / mcp overrides but left the session-list badge queue behind,
  so a deleted session's PAUSED badge re-surfaced on whatever session reused
  the id. Rules: the draft block calls `renameSessionShortcuts(`, and the
  `onSessionDeleted` lambda body calls `SessionBadgeStore.clear(`.

Usage: python3 scripts/scan/skill_restore_session_cleanup_check.py [repo_root]
       python3 scripts/scan/skill_restore_session_cleanup_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_ast import (
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (
    brace_depth,
    call_args,
    contains_ident,
    expr_end,
    guard_feasible,
    path_feasible,
    peel_discarding,
    value_sources,
)

SKILL_REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt"
CHAT_REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt"
MINIS_APP_REL = "src/android/app/src/main/java/com/rikkaminis/app/MinisApp.kt"
CHAT_ENTRY_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionEntryExt.kt"
DRAWER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatHistoryDrawer.kt"
CHAT_VM_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"
SESSION_DELETE_PLAN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/SessionDeletePlan.kt"

# [dv24-b3 / deep-audit-1044 B-06] Draft promotion must persist the BODY, not
# just a 50-char title prefix, and the drawer must treat a preview as content.
PROMOTE_DECL = "fun ChatViewModel.promoteDraftIfNeeded("
ENSURE_CALL = "ensureSession("
# The two single-source writers that associate text with a session durably.
# Both live in ChatRepository and both refresh `last_message` + `updated_at`.
DURABLE_WRITES = ("appendMessage(", "updateSessionPreview(")
# `_inputText.value = <constant>` — assigning a CONSTANT to the composer, i.e.
# "destroy the in-memory copy"; a variable (`= text`) keeps it.
#
# wide-1024: the old spelling (`= "` / `= '`) only knew the literal form, so
# `_inputText.value = String()` — same destruction, different spelling — was
# invisible. The class is "any expression whose value cannot depend on `text`":
# a string literal (masked to spaces by mask_keep_offsets), String(), the
# String.EMPTY constant, an emptied builder, or a literal `.trim()`.
# [dv25-B08 / deep-audit-1045 B-08] The promotion owns the session it creates.
# The exit sweep must respect that ownership, and the promotion must keep the
# draft slot recoverable when its durable write did not land.
CLEANUP_DECL = "fun cleanupIfEmptyOnExit()"
PROMOTE_OWNER_BIT = "promoteInFlight"
# A READ of the bit: an occurrence that is not an assignment target. The
# sweep must READ the bit (its value decides), so `promoteInFlight = false`
# is not a read — and a hard-coded false is exactly the pre-fix shape.
PROMOTE_OWNER_READ_RE = re.compile(r"(?<![.\w])promoteInFlight(?!\s*=)")
# The promotion must both CLAIM the bit and RELEASE it (a claim that is never
# released would freeze the sweep off for the rest of the session).
PROMOTE_OWNER_CLAIM_RE = re.compile(r"(?<![.\w])promoteInFlight\s*=\s*true")
PROMOTE_OWNER_RELEASE_RE = re.compile(r"(?<![.\w])promoteInFlight\s*=\s*false")
# The sweep's decision is routed through the shared pure predicate, so the
# four inputs (real id / streaming / attachments / promotion) are visible in
# one place instead of four hand-written early returns.
CLEANUP_PREDICATE = "shouldSkipCleanup("
# The slot must be released only AFTER the durable write landed, or restored
# when it did not. Either way the text must be recoverable.
DRAFT_SAVE = "ComposerDraftStore.saveText("
DRAFT_CLEAR = "ComposerDraftStore.clearDraft("

DRAWER_VISIBLE_DECL = "fun isDrawerRowVisible("
DRAWER_VISIBLE_CALL = "isDrawerRowVisible("
DRAWER_FILTER_RE = re.compile(r"sessions\s*\.\s*filter\s*\{[^}]*isDrawerRowVisible\(")
# Blankness test on the preview column (any of these is the "no content" arm).
PREVIEW_BLANK_CHECKS = ("isNullOrBlank", "isNotBlank", "isNullOrEmpty", "isNotEmpty")

ADD_DECL = "fun add("
ADD_SIG_END = "): Skill? {"
RESOLVE_CALL = "SkillRestorePolicy.resolveImportId("
RETURN_ADD = "return add("
PREFERRED_ID = "preferredId ="
BUNDLED_DECL = "private fun installBundledSkill(dirName: String) {"
BUNDLED_NEXT = "private fun isAdoptableBundledRow("
# [dv16-A05] sparse-clear arm
OVERRIDE_DECL = "fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean)"
OVERRIDE_NEXT = "fun clearSessionOverrides("
DELETE_OVERRIDE = "DELETE FROM session_skill_overrides WHERE session_id=? AND skill_id=?"
SHARED_DECISION = "shouldDeleteSessionSkillOverride("
GLOBAL_READ = "_skills.value.find { it.id == skillId }?.isEnabled"
DELETE_SESSION = "suspend fun deleteSession(id: String): SessionFileStore.DeleteResult? {"
DELETE_ROW_ONLY = "suspend fun deleteSessionRowOnly(id: String) {"
# [dv21-a2] Third entry point: the startup empty-session sweep.
DELETE_EMPTY = "suspend fun deleteEmptySessions("
INVOKE_HOOK = "onSessionDeleted?.invoke("
# [dv21-a2] A function whose body drops `sessions` rows IS a delete entry point.
# The gate derives the set from the tree instead of trusting this list, so a
# fourth entry point that skips the cleanup goes red.
SESSION_ROW_DELETES = ("dao.deleteSession(", "dao.deleteEmptySessions(")
FN_DECL_RE = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
CALL_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")
CHATREPO_NEW = "chatRepository = ChatRepository("
SKILL_NEW = "skillRepository = SkillRepository("
MCP_NEW = "mcpRepository = MCPRepository("


RECORD_DECL = "fun recordSkillUse("
RECORD_LOCK = "synchronized(addLock)"
SKILLS_ASSIGN = "_skills.value ="
SKILLS_STATE = "_skills.value"
# Every way a MutableStateFlow's value can be replaced. The old criterion knew
# only the `= ` setter, so `_skills.tryEmit(…)` — same effect on `.value`,
# different spelling — published outside the lock unnoticed.
SKILLS_WRITE_RE = re.compile(
    r"(?<![\w.`])_skills\s*\.\s*(?:value\s*=(?!=)|tryEmit\s*\(|"
    r"compareAndSet\s*\(|emit\s*\()")

# [da1030-A ISSUE-01]
ENSURE_DECL = "internal suspend fun ChatViewModel.ensureSession("
DRAFT_IF = "if (isDraft) {"
RENAME_SHORTCUTS = "renameSessionShortcuts("
ON_SESSION_DELETED = "onSessionDeleted = { sid ->"
BADGE_CLEAR = "SessionBadgeStore.clear("


def structure(src):
    """Structural view: comments + string/char literal bodies -> spaces.

    Provided by the shared lexer (`mask_code`): offsets are preserved exactly, so
    a position found here can be re-projected onto the raw source (needed by the
    arms that must read a SQL literal). Returns None when a literal/comment never
    terminates — callers report ROT / fail-closed instead of scanning raw text.
    """
    try:
        return mask_code(src)
    except KotlinLexError:
        return None


def body_of(code, name):
    """[decl_start, body_end) of `fun <name>(…)`, or (None, None).

    Delegates to the shared AST (`function_body`), which understands both block
    and expression bodies. `decl_start` is derived from the same match, so an
    overload cannot shift the slice.
    """
    m = re.search(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(",
                  code)
    got = function_body(code, name)
    if not m or not got:
        return (None, None)
    return (m.start(), got[1])


def check_record_skill_use_lock(src):
    """Arm 4 [da1029-C / I04]: recordSkillUse's StateFlow write-back must be
    INSIDE the `synchronized(addLock)` block, and nowhere outside it.

    The pre-fix shape computed `mapped` under the lock but published it after
    the release; the comment above the function claimed the opposite. Two
    concurrent calls then both read the pre-publish snapshot (one increment
    lost, memory drifts behind the DB) and a concurrent `add()` could be
    overwritten by the stale publish.

    Batch-7: the criterion is "every way the `_skills` StateFlow can be
    replaced" — the `value =` setter, `tryEmit(…)`, `compareAndSet(…)` and
    `emit(…)`. `tryEmit` on a MutableStateFlow always succeeds and lands in
    `.value` exactly like the setter, so judging the setter spelling alone left
    the whole publish-after-release window open for a one-word change.
    """
    v = []
    code = structure(src)
    if code is None:
        v.append("SkillRepository.kt: unterminated string/comment literal — cannot brace-match "
                 "recordSkillUse (ROT — update this gate)")
        return v
    start, end = body_of(code, "recordSkillUse")
    if start is None:
        v.append("SkillRepository.kt: '%s' not found (ROT — update this gate)" % RECORD_DECL)
        return v

    lock_at = code.find(RECORD_LOCK, start, end)
    if lock_at < 0:
        v.append("SkillRepository.kt: recordSkillUse has no '%s' — the read-modify-write "
                 "over _skills.value is back outside the add lock" % RECORD_LOCK)
        return v
    lock_open = code.find("{", lock_at)
    lock_end = match_bracket(code, lock_open) if lock_open >= 0 else -1
    if lock_open < 0 or lock_end < 0 or lock_end > end:
        v.append("SkillRepository.kt: recordSkillUse's synchronized block braces do not balance "
                 "(ROT — update this gate)")
        return v

    writes = [m.start() for m in SKILLS_WRITE_RE.finditer(code, start, end)]
    if not writes:
        v.append("SkillRepository.kt: recordSkillUse never publishes '%s' — the use_count bump "
                 "would never reach the in-memory mirror" % SKILLS_ASSIGN)
        return v
    inside = [p for p in writes if lock_open < p < lock_end]
    outside = [p for p in writes if not (lock_open < p < lock_end)]
    if not inside:
        v.append("SkillRepository.kt: recordSkillUse computes under the lock but publishes "
                 "'%s' only OUTSIDE it — a concurrent recordSkillUse loses one increment and a "
                 "concurrent add() can be overwritten by the stale publish" % SKILLS_ASSIGN)
    for p in outside:
        v.append("SkillRepository.kt: '%s' at body offset %d is out of the '%s' block — "
                 "write-back must share the lock with add() (tryEmit/compareAndSet replace "
                 "`.value` and are just as racy)"
                 % (re.sub(r"\s+", " ", code[p:p + 24]).strip(), p - start, RECORD_LOCK))

    # wide-1024 (dataflow): publishing UNDER the lock is not enough. The write
    # is derived from a read of the same StateFlow, and the DB bump is the
    # other half of the pair — leaving either outside the lock restores the
    # lost-increment race the da1029-C fix closed (two concurrent calls both
    # read the pre-bump snapshot; the DB says +2, memory says +1).
    write_starts = set(writes)
    for m in re.finditer(re.escape(SKILLS_STATE), code[start:end]):
        q = start + m.start()
        if q in write_starts or lock_open < q < lock_end:
            continue
        if any(w <= q < w + len(SKILLS_ASSIGN) for w in write_starts):
            continue
        v.append("SkillRepository.kt: recordSkillUse reads '%s' at body offset %d OUTSIDE the "
                 "'%s' block — the value the write is derived from must be read under the "
                 "same lock, otherwise two concurrent calls both read the pre-bump snapshot "
                 "and one increment is lost in memory" % (SKILLS_STATE, q - start, RECORD_LOCK))
    for m in re.finditer(r"\bdb\s*\.\s*execSQL\s*\(", code[start:end]):
        p = start + m.start()
        if not (lock_open < p < lock_end):
            v.append("SkillRepository.kt: recordSkillUse issues its use_count bump "
                     "(db.execSQL) at body offset %d OUTSIDE the '%s' block — the DB half "
                     "and the memory half of the read-modify-write must be one critical "
                     "section" % (p - start, RECORD_LOCK))
    return v


def slice_between(src, start_marker, end_marker, label, violations):
    start = src.find(start_marker)
    if start < 0:
        violations.append("%s: '%s' not found (ROT — update this gate)" % (label, start_marker))
        return None
    end = src.find(end_marker, start + len(start_marker))
    if end < 0:
        violations.append("%s: '%s' not found after %s (ROT — update this gate)"
                          % (label, end_marker, start_marker))
        return None
    return src[start:end]


def check_sparse_clear(src, code):
    """Arm 8 [dv16-A05]: setSessionOverride must sparse-clear.

    The decision lives in the shared pure function
    shouldDeleteSessionSkillOverride (the MCP twin's shape); a pinned row keeps
    the session stuck at the old value after the user toggles back.

    Batch-7: the branch must be REACHABLE. `if (shouldDelete(…) && false)` keeps
    every anchor this arm looked for — the call, the shared decision, the
    CURRENT-global read and the DELETE statement — while turning the whole
    sparse-clear into dead code, so every session toggle that matches the
    global default leaves a permanent hard-coded override row.
    """
    v = []
    if code is None:
        return ["SkillRepository.kt: unterminated string/comment literal — cannot read the "
                "setSessionOverride decision (ROT — update this gate)"]
    start = src.find(OVERRIDE_DECL)
    end = src.find(OVERRIDE_NEXT, start + 1) if start >= 0 else -1
    if start < 0 or end < 0:
        return ["SkillRepository.kt: setSessionOverride / clearSessionOverrides declaration "
                "not found (ROT — update this gate)"]
    body = src[start:end]
    if DELETE_OVERRIDE not in body:
        v.append(
            "SkillRepository.kt: setSessionOverride does not DELETE the override row when the "
            "value equals the global default — a pinned row keeps the session stuck at the old "
            "value after the user toggles back (sparse-clear is the MCP twin's shape)"
        )
    if SHARED_DECISION not in body:
        v.append(
            "SkillRepository.kt: setSessionOverride does not consult the shared decision "
            "shouldDeleteSessionSkillOverride — a second decision copy is back"
        )
    if GLOBAL_READ not in body:
        v.append(
            "SkillRepository.kt: setSessionOverride does not read the CURRENT global default "
            "before the branch — the row would be pinned even when it matches"
        )

    # The DELETE must sit in the branch whose guard is the shared decision, and
    # that guard must be able to be TRUE at runtime.
    guards = [(cond, ts, te) for cond, ts, te, _es, _ee in if_blocks(code)
              if start <= ts < end and DELETE_OVERRIDE in src[ts:te]]
    if not guards:
        v.append("SkillRepository.kt: the DELETE of the override row is not the body of an "
                 "`if` at all — the sparse-clear decision must gate the statement directly")
        return v
    for cond, ts, te in guards:
        if SHARED_DECISION not in cond:
            v.append("SkillRepository.kt: the DELETE branch is guarded by `%s`, not by the "
                     "shared decision shouldDeleteSessionSkillOverride — the sparse-clear "
                     "condition has drifted into a second copy"
                     % re.sub(r"\s+", " ", cond).strip()[:80])
        if not guard_feasible(cond):
            v.append("SkillRepository.kt: setSessionOverride's sparse-clear guard is DEAD "
                     "(`%s`) — the DELETE statement, the shared decision and the CURRENT-global "
                     "read all survive as anchors but can never run, so every toggle that "
                     "matches the global default now writes a pinned INSERT OR REPLACE row "
                     "(dv16-A05: the session stays stuck at the old value forever)"
                     % re.sub(r"\s+", " ", cond).strip()[:90])
        elif not path_feasible(code, src.index(DELETE_OVERRIDE, ts)):
            v.append("SkillRepository.kt: the DELETE of the override row is unreachable on the "
                     "sparse-clear path (dead enclosing branch)")
    return v


def check_skill_repo(src):
    v = []

    # Arm 4 [da1029-C / I04]: recordSkillUse's write-back shares add()'s lock.
    v.extend(check_record_skill_use_lock(src))

    # Arm 1b (dataflow, wide-1024 → batch-7): the resolved id must actually
    # REACH the policy — the argument must evaluate to `preferredId` itself.
    v.extend(check_resolve_callee_args(src))

    # Arm 8 [dv16-A05]: sparse-clear, now with guard feasibility.
    v.extend(check_sparse_clear(src, structure(src)))

    # Arm 1: add()'s first assignment must resolve the id through the policy.
    add_slice = slice_between(src, ADD_DECL, ADD_SIG_END, "SkillRepository.kt", v)
    if add_slice is not None:
        body = src[src.find(ADD_SIG_END, src.find(ADD_DECL)):]
        first_assign = body.find("val id =")
        if first_assign < 0:
            v.append("SkillRepository.kt: add() has no 'val id =' assignment (ROT — update this gate)")
        else:
            line_start = body.rfind("\n", 0, first_assign) + 1
            line_end = body.find("\n", first_assign)
            stmt = body[line_start:line_end]
            if RESOLVE_CALL not in stmt:
                v.append(
                    "SkillRepository.kt: add() first id assignment does not resolve "
                    "via '%s' (raw slugify recompute is back)" % RESOLVE_CALL
                )
            if "preferredId" not in stmt:
                v.append("SkillRepository.kt: add() id assignment does not consult preferredId")

    # Arm 2: importFromContent's `return add(` block must pass preferredId.
    ifc = slice_between(src, "fun importFromContent(", "fun skillMdPath", "SkillRepository.kt", v)
    if ifc is not None:
        ra = ifc.find(RETURN_ADD)
        if ra < 0:
            v.append("SkillRepository.kt: importFromContent has no '%s' (ROT — update this gate)"
                     % RETURN_ADD)
        else:
            block = ifc[ra:ifc.find(")\n", ra) if ifc.find(")\n", ra) > 0 else len(ifc)]
            if PREFERRED_ID not in block:
                v.append(
                    "SkillRepository.kt: importFromContent's return add() does not pass "
                    "'%s' — the resolved id is dropped on the new-install branch" % PREFERRED_ID
                )

    # Arm 3: installBundledSkill's add() must pass preferredId = dirName.
    bundled = slice_between(src, BUNDLED_DECL, BUNDLED_NEXT, "SkillRepository.kt", v)
    if bundled is not None:
        a = bundled.find("add(")
        if a < 0:
            v.append("SkillRepository.kt: installBundledSkill has no add() call "
                     "(ROT — update this gate)")
        else:
            block = bundled[a:bundled.find(")\n", a) if bundled.find(")\n", a) > 0 else len(bundled)]
            if PREFERRED_ID not in block:
                v.append(
                    "SkillRepository.kt: installBundledSkill's add() does not pass "
                    "'%s' — a bundled skill may land under a slug other than its "
                    "assets dirName (payload lives in <dirName>/)" % PREFERRED_ID
                )
    # Arm 2b [third adversarial round 04-2]: the replace-existing branch is
    # reachable and it really replaces — `false &&` used to satisfy Arm 2 alone.
    _code = structure(src)
    if _code is not None:
        v.extend(check_skill_replace_branch(_code, src))
    return v


def _inert_preferred_id(arg):
    """Does this argument still EVALUATE to the caller's `preferredId`?

    Only inert wrappers are accepted: parentheses, `!!` and a cast. Every other
    transformation (`?.take(0)`, `.let { null }`, `.substring(0, 0)`, a literal)
    changes the value the policy receives.
    """
    e = strip_parens(arg.strip())
    for _ in range(6):
        if e.endswith("!!"):
            e = strip_parens(e[:-2].strip())
            continue
        m = re.fullmatch(r"(.+?)\s+as\s+[A-Za-z_][\w.]*\s*\??", e, re.S)
        if m:
            e = strip_parens(m.group(1).strip())
            continue
        break
    return re.sub(r"\s+", "", e) == "preferredId"


def check_resolve_callee_args(src):
    """Arm 1b (dataflow, wide-1024 → batch-7): the id resolution must be fed
    `preferredId` ITSELF, not an expression that reduces to something else.

    Arm 1 proves add()'s `val id =` statement mentions the policy call and the
    `preferredId` identifier. `resolveImportId(preferredId.let { null }, …)`
    satisfied both while handing the policy a null — and the blacklist that
    followed (`null|false|!true|""`) could be stepped around with any other
    neutraliser that keeps the identifier: `preferredId?.take(0)` passes an
    empty string, `preferredId.substring(0, 0)` the same, all of which make
    `resolveImportId` fall back to `slugify(name)` (a renamed / pure-CJK skill
    is then lost, or lands under a fresh slug while its payload and overrides
    still point at the old one [fix/da1021-A]).

    Criterion: for EVERY resolveImportId call site in the file (both entry
    points) argument 0 must be an inert pass-through of `preferredId`.
    Unparseable argument lists fail closed.
    """
    v = []
    code = structure(src)
    if code is None:
        return ["SkillRepository.kt: unterminated string/comment literal — cannot read the "
                "resolveImportId arguments (ROT — update this gate)"]
    calls = list(re.finditer(re.escape(RESOLVE_CALL), code))
    if not calls:
        return v  # arm 1 already reports a missing call on the add() path
    for m in calls:
        op = m.end() - 1
        spans = call_args(code, op)
        if not spans:
            v.append("SkillRepository.kt: resolveImportId argument list does not balance at "
                     "offset %d (fail-closed)" % m.start())
            continue
        a, b = spans[0]
        raw = code[a:b]
        arg0 = re.sub(r"\s+", " ", raw).strip()
        if not contains_ident(raw, "preferredId"):
            v.append("SkillRepository.kt: resolveImportId is called without `preferredId` "
                     "(`%s`) — the caller's resolved id is dropped and a renamed skill lands "
                     "under slugify(name) again" % arg0[:80])
        elif not _inert_preferred_id(raw):
            v.append("SkillRepository.kt: resolveImportId's preferredId argument is "
                     "NEUTRALISED (`%s`) — the call and the identifier survive, but the value "
                     "is transformed (scope call / truncation / literal), so a renamed or "
                     "pure-CJK skill is lost or lands under a fresh slug (fail-closed)"
                     % arg0[:90])
    return v


def _params_of(code, decl_start):
    """Parameter names of the declaration starting at `decl_start`."""
    op = code.find("(", decl_start)
    if op < 0:
        return []
    cp = match_bracket(code, op)
    if cp < 0:
        return []
    names = []
    for a, b in split_top(code[op + 1:cp], (",",)):
        seg = code[op + 1 + a:op + 1 + b]
        m = re.match(r"\s*(?:@\w+\s+)*(?:val\s+|var\s+)?([A-Za-z_]\w*)\s*:", seg)
        if m:
            names.append(m.group(1))
    return names


def _session_id_names(code, start, end, params):
    """Identifiers in the body that plausibly carry the deleted session id.

    The function's parameters, plus locals / loop variables derived from a `dao.`
    call or from those parameters (the startup sweep forwards `id` from a
    candidate list it fetched through `dao.`).
    """
    allowed = set(params)
    for _ in range(3):
        grew = False
        for m in re.finditer(r"\b(?:val|var|for)\s*\(?\s*([A-Za-z_]\w*)", code[start:end]):
            name = m.group(1)
            rhs_start = start + m.end()
            rhs = code[rhs_start:expr_end(code, rhs_start)]
            if name in allowed:
                continue
            if "dao." in rhs or any(contains_ident(rhs, n) for n in allowed):
                allowed.add(name)
                grew = True
        if not grew:
            break
    return allowed


def _membership(cond, idents, coll):
    """True / False when `cond` states `ident ∈ coll` / `ident ∉ coll`, else None.

    Judged on the VALUE relationship, not the operator spelling: `in`, `!in`,
    `contains(…)`, `any { it == x }` and `none { it == x }` are the four forms
    this codebase uses. Anything else is unknown — and the caller treats an
    unknown guard as NO protection (fail-closed), because a guard it cannot read
    is a guard it cannot vouch for.
    """
    c = re.sub(r"\s+", "", strip_parens(cond.strip()))
    if not c:
        return None
    neg = False
    while c.startswith("!"):
        neg = not neg
        c = c[1:]
    while c.startswith("(") and c.endswith(")"):
        c = c[1:-1]
    ids = "(?:%s)" % "|".join(re.escape(n) for n in idents if n)
    col = re.escape(coll)
    if not ids or not coll:
        return None
    if re.fullmatch(r"%s!in%s" % (ids, col), c):
        return neg
    if re.fullmatch(r"%sin%s" % (ids, col), c):
        return not neg
    if re.fullmatch(r"%s\.contains\(%s\)" % (col, ids), c):
        return not neg
    m = re.fullmatch(r"%s\.(any|none)\{it(?:===?|!==?)%s\}" % (col, ids), c)
    if m:
        return (m.group(1) == "any") != neg
    return None


def check_survivor_polarity(code, src):
    """Arm 5 [third adversarial round 04-1, dual-gate with parts_json_funnel]:
    the survivor filter keeps the sessions that SURVIVED.

    `deleteEmptySessions` snapshots the candidate ids, deletes them, then asks
    which candidates still exist — the ones that got their first message in the
    window. The shipped filter is `if (id in survivors) continue`: skip the
    survivor, notify the really-deleted. Inverted to `!in` nothing reds: every
    LIVE session's listeners fire (`SkillRepository` / `MCPRepository` /
    `OffloadPermissionBroker` drop their overrides and grants on a session the
    user is chatting in) and every deleted session keeps its orphan rows
    forever.

    Two accepted protection shapes, both judged on the membership VALUE:
      (a) wrapped — `if (id !in survivors) { … onSessionDeleted?.invoke(id) … }`
      (b) guarded — `if (id in survivors) continue|return` before the hook.
    A guard whose membership cannot be read is not accepted as protection.
    """
    v = []
    start, end = body_of(code, "deleteEmptySessions")
    if start is None:
        return ["ChatRepository.kt: 'deleteEmptySessions' not found (ROT — update this gate)"]
    body = code[start:end]
    del_at = body.find("dao.deleteEmptySessions(")
    if del_at < 0:
        return v  # Arm 2 already reports the missing delete
    surv = None
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=\s*([^\n]+)", body):
        if m.start() < del_at:
            continue
        if re.search(r"\bdao\s*\.", m.group(2)):
            surv = m.group(1)
            break
    if surv is None:
        v.append("ChatRepository.kt: deleteEmptySessions no longer asks the DAO which candidates "
                 "SURVIVED the delete — every candidate gets 'deleted' notified, including the "
                 "ones that acquired their first message inside the window (their skills/mcp "
                 "overrides and offload grants are cleared on a live session)")
        return v
    hooks = [m.start() for m in re.finditer(re.escape(INVOKE_HOOK), body)]
    if not hooks:
        return v  # Arm 3 covers the missing hook
    loop = re.search(r"\bfor\s*\(\s*([A-Za-z_]\w*)\s+in\s+[A-Za-z_]\w*\s*\)", body)
    ids = [loop.group(1)] if loop else []
    if not ids:
        for pos in hooks:
            spans = call_args(body, pos + len(INVOKE_HOOK) - 1)
            if spans:
                a, b = spans[0]
                ids.extend(re.findall(r"[A-Za-z_]\w*", body[a:b]))
    if not ids:
        v.append("ChatRepository.kt: deleteEmptySessions hands the hook an argument it cannot "
                 "trace to a candidate id (fail-closed)")
        return v
    guards = []
    for cond, t_start, _t_end, _es, _ee in if_blocks(body):
        stmt = body[t_start:t_start + 40].lstrip()
        if re.match(r"(?:continue|return|break)\b", stmt):
            guards.append((cond, t_start))
    loops = []
    for m in re.finditer(r"\bfor\s*\([^)]*\)\s*\{", body):
        ob = m.end() - 1
        cb = match_bracket(body, ob)
        if cb > 0:
            loops.append((ob, cb))

    def same_loop(a, b):
        """Both positions sit inside the SAME loop body (or, without a `for`
        in sight, at the same brace depth — `.forEach { }` shape)."""
        for ob, cb in loops:
            if ob < a < cb and ob < b < cb:
                return True
        return not loops and brace_depth(body, a) == brace_depth(body, b)

    for pos in hooks:
        protected = False
        for _kind, cond in enclosing_conditions(body, pos):
            if _membership(cond, ids, surv) is False:
                protected = True
        for cond, g_start in guards:
            if g_start > pos or not same_loop(g_start, pos):
                continue
            mem = _membership(cond, ids, surv)
            if mem is True:
                protected = True
            elif mem is False:
                protected = True
                v.append(
                    "ChatRepository.kt: deleteEmptySessions skips the sessions that are NOT "
                    "survivors (`if (%s) continue|return`) — the polarity is inverted: the live "
                    "sessions in the window get their skills/mcp overrides and offload grants "
                    "cleared, and every really-deleted session's rows leak"
                    % re.sub(r"\s+", " ", cond.strip())[:60])
                break
        if not protected:
            v.append("ChatRepository.kt: deleteEmptySessions notifies '%s' without any survivor "
                     "filter — a candidate that acquired its first message inside the window is "
                     "reported as deleted" % INVOKE_HOOK)
    return v


def check_skill_replace_branch(code, src):
    """Arm 2b [third adversarial round 04-2]: the replace-existing branch is
    REACHABLE and it really replaces.

    Arm 2 proves the fall-through `return add(... preferredId)` still carries the
    id; it never looks at the branch that handles an EXISTING skill. `false &&`
    in front of that guard leaves the whole spelling intact and sends every
    re-import into `add()`, which refuses a duplicate id and returns null — so a
    backup restore / paste-update of an existing skill fails silently while the
    old content stays.
    """
    v = []
    start, end = body_of(code, "importFromContent")
    if start is None:
        return []
    body = code[start:end]
    add_at = body.find(RETURN_ADD)
    branches = []
    for cond, t_start, t_end, _es, _ee in if_blocks(body):
        if not contains_ident(cond, "id"):
            continue
        if not re.search(r"\b(?:any|contains|firstOrNull|find)\b", cond):
            continue
        if add_at >= 0 and t_start > add_at:
            continue
        branches.append((cond, t_start, t_end))
    if not branches:
        v.append("SkillRepository.kt: importFromContent no longer branches on an existing skill "
                 "with the resolved id — every import falls through to add(), which refuses a "
                 "duplicate id and returns null")
        return v
    for cond, t_start, t_end in branches:
        if not guard_feasible(cond):
            v.append(
                "SkillRepository.kt: importFromContent's replace-existing guard `%s` is "
                "constant-false — the branch can never run, so every restore / paste-update of "
                "an existing skill falls into add(), returns null and silently keeps the old "
                "content" % re.sub(r"\s+", " ", cond.strip())[:70])
            continue
        branch = body[t_start:t_end]
        # The branch must DO something. A branch that only returns is a paper
        # replace; `_skills.value = …`, a DB write or a delegated writer all
        # qualify (the real tree does all three).
        if not re.search(r"_skills\s*\.\s*value\s*=(?!=)", branch) and \
                not re.search(r"\b(?:db|dao)\s*\.\s*\w+\s*\(", branch) and \
                not re.search(r"\bwriteSkillMd\s*\(|\breplace\s*\(", branch) and \
                not re.search(r"=(?!=)", branch.replace("==", "")):
            v.append(
                "SkillRepository.kt: importFromContent's replace-existing branch mutates "
                "nothing — the in-memory mirror would keep serving the old body")
        rets = [branch[m.end():expr_end(branch, m.end())].strip()
                for m in re.finditer(r"\breturn\b(?!@)", branch)]
        if not rets:
            v.append("SkillRepository.kt: importFromContent's replace-existing branch returns "
                     "no value — a successful replace is reported to the caller as null")
        else:
            for r in rets:
                cv = const_value(r)
                if cv is not None and cv[0] == "null":
                    v.append("SkillRepository.kt: importFromContent's replace-existing branch "
                             "returns null — the replace reads as a failure to every caller")
                    break
    return v


def check_promote_persisted(code, src):
    """Arm 9b [third adversarial round 04-3, symmetric axis]: the success path
    must actually flip the flag the `finally` consults.

    `promoteDraftIfNeeded` guards its restore with `if (!persisted)` — the bit
    says "the durable write landed". Change the success path's `persisted = true`
    to `false` and every existing arm stays green (the declaration, the restore
    call and the clear-after-write ordering are untouched) while each promotion
    writes the already-persisted text back into the NEXT draft slot: the user's
    fresh New Chat opens pre-filled with the previous draft.
    """
    v = []
    start, end = body_of(code, "promoteDraftIfNeeded")
    if start is None:
        return []
    body = code[start:end]
    m = re.search(r"\bvar\s+([A-Za-z_]\w*)\s*(?::\s*Boolean\s*)?=\s*false\b", body)
    if m is None:
        return v  # no such flag: the restore-side judgment owns that shape
    flag = m.group(1)
    if not re.search(r"\bif\s*\(\s*!\s*" + re.escape(flag) + r"\s*\)", body):
        return v  # the flag is not consulted: another arm owns it
    writes = []
    for mm in re.finditer(r"(?<![\w.`])" + re.escape(flag) + r"\s*=(?!=)", body):
        if mm.start() < m.end():
            continue  # the declaration's own initialiser
        rhs = body[mm.end():expr_end(body, mm.end())].strip()
        writes.append((mm.start(), rhs))
    if not writes:
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded declares '%s = false' and consults "
                 "it in `finally`, but never flips it — every promotion restores the text it "
                 "just persisted" % flag)
        return v
    flips = [p for p, rhs in writes if const_bool(rhs) is True]
    false_write = None
    for p, rhs in writes:
        if const_bool(rhs) is False:
            false_write = (p, rhs)
            v.append(
                "ChatSessionEntryExt.kt: promoteDraftIfNeeded writes `%s = %s` on the promotion "
                "path — the durable write lands, `finally` still treats it as failed, and "
                "ComposerDraftStore gets the already-persisted text back: the next New Chat "
                "opens pre-filled with the previous draft" % (flag, rhs.strip()[:30]))
            break
    if false_write is not None:
        return v
    if not flips:
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded never sets '%s = true' on the "
                 "success path — the restore branch runs after every successful promotion"
                 % flag)
        return v
    durable = [body.find(t) for t in DURABLE_WRITES if body.find(t) >= 0]
    if durable and min(flips) < min(durable):
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded flips '%s' before the durable "
                 "write — a failed write is then reported as persisted and the text is lost"
                 % flag)
    return v


def check_chat_repo(src):
    """Arms 1–3 [fix/da1021-A, widened by dv21-a2]: EVERY `sessions`-row delete
    entry point runs the cross-db cleanup, and that cleanup is handed the id
    that was really deleted.

    The hook was originally wired into two of the three entry points; the
    startup empty-session sweep (`deleteEmptySessions`) skipped it, so a session
    the user opened, flipped a session-scoped toggle on and then abandoned kept
    its skills.db / mcp.db override rows, its badge and its offload grants for
    whatever session later reused the id.

    Batch-7: the hook call must carry the deleted id. `onSessionDeleted?.invoke("")`
    keeps the whole spelling this arm looked for while the cleanup runs against
    a fabricated id, so the real session's overrides, badge and grants survive
    (da1021 ISSUE-10 / da1030 I02). Criterion: the first argument must not be a
    constant, and must derive from the function's own parameters or from a
    `dao.`-derived local / loop variable.
    """
    v = []
    code = structure(src)
    if code is None:
        v.append("ChatRepository.kt: unterminated string/comment (ROT — update this gate)")
        return v
    funcs = []
    for m in FN_DECL_RE.finditer(code):
        got = function_body(code, m.group(1))
        if got:
            funcs.append((m.group(1), m.start(), got[1]))
    # The decision this gate is about: which functions drop `sessions` rows.
    row_deleters = sorted({name for name, s, e in funcs
                           if any(call in code[s:e] for call in SESSION_ROW_DELETES)})
    registered = []
    for decl, label in ((DELETE_SESSION, "deleteSession"),
                        (DELETE_ROW_ONLY, "deleteSessionRowOnly"),
                        (DELETE_EMPTY, "deleteEmptySessions")):
        m = re.search(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", decl)
        registered.append((decl, label, m.group(1) if m else label))
    names = {name for _, _, name in registered}
    # Arm 1: the registered declarations are still there (a rename is ROT, not
    # a silent pass).
    entry_spans = {}
    for decl, label, name in registered:
        off = code.find(decl)
        if off < 0:
            v.append("ChatRepository.kt: '%s' not found (ROT — update this gate)" % decl)
            continue
        got = function_body(code, name)
        if not got:
            v.append("ChatRepository.kt: %s body braces do not balance (ROT — update this gate)"
                     % label)
            continue
        entry_spans[label] = (off, got[1])
    # Arm 2: nothing removes `sessions` rows outside the registered set.
    for name in row_deleters:
        if name not in names:
            v.append(
                "ChatRepository.kt: %s removes `sessions` rows but is not a registered "
                "delete entry point — it must reach '%s' too (ROT — update this gate)"
                % (name, INVOKE_HOOK)
            )
    # Arm 3: every entry point reaches the hook (directly or via a helper whose
    # own body holds it), judged on the body, not on a whole-line spelling; and
    # every direct call hands over a real id, not a constant.
    helpers = {name: code[s:e] for name, s, e in funcs}
    for label, (off, end) in entry_spans.items():
        body = code[off:end]
        if INVOKE_HOOK not in body:
            delegated = [c for c in CALL_RE.findall(body) if INVOKE_HOOK in helpers.get(c, "")]
            if not delegated:
                v.append(
                    "ChatRepository.kt: %s does not invoke '%s' — cross-db "
                    "session cleanup is unwired" % (label, INVOKE_HOOK)
                )
    for name, s, e in funcs:
        allowed = _session_id_names(code, s, e, _params_of(code, s))
        for m in re.finditer(re.escape(INVOKE_HOOK), code[s:e]):
            pos = s + m.start()
            spans = call_args(code, pos + len(INVOKE_HOOK) - 1)
            if not spans:
                v.append("ChatRepository.kt: %s's onSessionDeleted argument list does not "
                         "balance (fail-closed)" % name)
                continue
            a, b = spans[0]
            arg = code[a:b]
            if const_value(strip_parens(arg.strip())) is not None:
                v.append(
                    "ChatRepository.kt: %s passes a CONSTANT to '%s' (`%s`) — the cleanup then "
                    "runs against a fabricated id, so the deleted session's skills/mcp override "
                    "rows, PAUSED badge and offload grants all survive the delete (da1021 "
                    "ISSUE-10 / da1030 I02)" % (name, INVOKE_HOOK, arg.strip()[:40])
                )
            elif not any(contains_ident(arg, n) for n in allowed):
                v.append(
                    "ChatRepository.kt: %s hands `%s` to '%s' — not an id derived from the "
                    "deleted row (parameters / dao-derived candidates), so the cross-db "
                    "cleanup targets the wrong session"
                    % (name, arg.strip()[:40], INVOKE_HOOK)
                )
    if INVOKE_HOOK not in src:
        v.append("ChatRepository.kt: onSessionDeleted hook declaration missing")
    # Arm 5 [third adversarial round 04-1]: the survivor filter's POLARITY.
    v.extend(check_survivor_polarity(code, src))
    return v


def check_minis_app(src):
    code = structure(src)
    v = []
    if code is None:
        return ["MinisApp.kt: unterminated string/comment literal (ROT — update this gate)"]
    if CHATREPO_NEW not in code:
        v.append("MinisApp.kt: '%s' not found (ROT — update this gate)" % CHATREPO_NEW)
        return v
    chat_line = code.find(CHATREPO_NEW)
    skill_line = code.find(SKILL_NEW)
    mcp_line = code.find(MCP_NEW)
    if skill_line < 0:
        v.append("MinisApp.kt: '%s' assignment not found" % SKILL_NEW)
    if mcp_line < 0:
        v.append("MinisApp.kt: '%s' assignment not found" % MCP_NEW)
    # Arm 5: skill/mcp must be constructed BEFORE chatRepository (the callback
    # must reference the real singletons, not a second copy built too late).
    if skill_line >= 0 and skill_line > chat_line:
        v.append("MinisApp.kt: skillRepository is constructed AFTER chatRepository — the "
                 "onSessionDeleted callback would reference an unwired instance")
    if mcp_line >= 0 and mcp_line > chat_line:
        v.append("MinisApp.kt: mcpRepository is constructed AFTER chatRepository — the "
                 "onSessionDeleted callback would reference an unwired instance")
    if "clearSessionOverrides(sid)" not in code:
        v.append("MinisApp.kt: onSessionDeleted callback does not call clearSessionOverrides")
    return v


def check_chat_entry(src):
    """Arm 6 [da1030-A / I01]: `ensureSession`'s draft-promotion block must
    re-point the pinned WebApp shortcuts onto the real session id.

    A shortcut pinned from a draft chat stores `__new__<uuid>` in both scope
    columns, and `migrateDraftResources` (called a few lines above) moves the
    session tree to the real id and deletes the draft directory. Every reader
    resolves `htmlPath` through `minis-sessions/<scope_context>/attachments`,
    so without this hop the pinned launcher icon points at a directory that no
    longer exists ("Source Missing") and nothing else ever rewrites the row.

    The criterion is scoped to the `if (isDraft)` block on purpose: a call
    placed outside it (e.g. before the guard) would fire for real sessions too
    and, on the draft path, would run after the promotion window.
    """
    v = []
    code = structure(src)
    if code is None:
        v.append("ChatSessionEntryExt.kt: unterminated string/comment literal — cannot "
                 "brace-match ensureSession (ROT — update this gate)")
        return v
    start, end = body_of(code, "ensureSession")
    if start is None:
        v.append("ChatSessionEntryExt.kt: '%s' not found (ROT — update this gate)" % ENSURE_DECL)
        return v
    body = code[start:end]

    draft_at = body.find(DRAFT_IF)
    if draft_at < 0:
        v.append("ChatSessionEntryExt.kt: ensureSession has no '%s' block (ROT — update this gate)"
                 % DRAFT_IF)
        return v
    draft_open = body.find("{", draft_at)
    draft_end = match_bracket(body, draft_open) if draft_open >= 0 else -1
    if draft_end < 0:
        v.append("ChatSessionEntryExt.kt: ensureSession's '%s' braces do not balance "
                 "(ROT — update this gate)" % DRAFT_IF)
        return v
    draft_body = body[draft_open:draft_end + 1]

    if RENAME_SHORTCUTS not in draft_body:
        v.append("ChatSessionEntryExt.kt: ensureSession's '%s' block does not call '%s' — a "
                 "shortcut pinned on the draft keeps scope_context = __new__* and resolves "
                 "under the draft directory migrateDraftResources just deleted "
                 "(launcher icon: \"Source Missing\")" % (DRAFT_IF, RENAME_SHORTCUTS))
    return v


def check_minis_app_badge(src):
    """Arm 7 [da1030-A / I01 裂缝B]: the `onSessionDeleted` lambda must drop the
    deleted session's badge queue as well.

    `SessionBadgeStore.clear` removes the id from the in-memory StateFlow AND
    from SharedPreferences; without the hook a deleted session's PAUSED badge
    survived the process and re-surfaced on whatever session later reused the
    id. The criterion is scoped to the lambda body: a stray call elsewhere in
    `onCreate` would not run on the delete path.
    """
    v = []
    code = structure(src)
    if code is None:
        v.append("MinisApp.kt: unterminated string/comment literal — cannot brace-match "
                 "onSessionDeleted (ROT — update this gate)")
        return v
    at = code.find(ON_SESSION_DELETED)
    if at < 0:
        v.append("MinisApp.kt: '%s' not found (ROT — update this gate)" % ON_SESSION_DELETED)
        return v
    open_brace = code.find("{", at)
    end = match_bracket(code, open_brace) if open_brace >= 0 else -1
    if end < 0:
        v.append("MinisApp.kt: onSessionDeleted lambda braces do not balance "
                 "(ROT — update this gate)")
        return v
    body = code[open_brace:end + 1]
    if BADGE_CLEAR not in body:
        v.append("MinisApp.kt: onSessionDeleted does not call '%s' — a deleted session's "
                 "badge queue (memory + SharedPreferences) survives the delete and "
                 "re-surfaces on whatever session reuses the id" % BADGE_CLEAR)
    return v


A05_SWEEP_CALL = "deleteEmptySessions("
A05_LIVE_SET = "activeSessionIds"
A05_IO = "Dispatchers.IO"


def _a05_io_regions(src):
    """Brace-balanced regions opened by an IO dispatch.

    The `)` + `.name`-hop + `{` walk is deliberate: `CoroutineScope(SupervisorJob()
    + Dispatchers.IO)` is a *property* initialiser with no block, and treating its
    far-away next `{` as a region would let an on-main sweep hide inside it.
    """
    regions = []
    token = "Dispatchers.IO"
    k = src.find(token)
    while k >= 0:
        tail = src[k + len(token):]
        j = 0
        while j < len(tail) and tail[j] in " \t\r\n":
            j += 1
        if j < len(tail) and tail[j] == ")":
            j += 1
            while True:
                while j < len(tail) and tail[j] in " \t\r\n":
                    j += 1
                if j < len(tail) and tail[j] == ".":
                    j += 1
                    while j < len(tail) and (tail[j].isalnum() or tail[j] == "_"):
                        j += 1
                    continue
                break
            if j < len(tail) and tail[j] == "{":
                brace = k + len(token) + j
                close = match_bracket(src, brace)
                if close > brace:
                    regions.append((brace, close))
        k = src.find(token, k + 1)
    return regions


def check_minis_app_sweep(src):
    """Arm 7 [dv20-A4-A05]: the startup empty-session sweep must be *wired*.

    `ChatRepository.deleteEmptySessions` and `ChatViewModelStore.activeSessionIds`
    both shipped with zero production callers, so the backstop that clears a
    session the user opened and closed without typing never ran — the empty rows
    accumulated in the drawer forever. Three decisions, in the order they can go
    wrong:

      1. the call exists at all (a helper with no caller is indistinguishable
         from a feature nobody built);
      2. it carries the live-session set — sweeping without it deletes the very
         session the user is in when they reopen the app;
      3. it runs off the main thread — it is a Room DELETE at cold start.
    """
    code = structure(src)
    v = []
    if code is None:
        return ["MinisApp.kt: unterminated string/comment literal (ROT — update this gate)"]
    off = code.find(A05_SWEEP_CALL)
    if off < 0:
        v.append("MinisApp.kt: no call to '%s' — the startup empty-session "
                 "backstop never runs" % A05_SWEEP_CALL)
        return v
    paren = code.find("(", off)
    end = match_bracket(code, paren) if paren >= 0 else -1
    args = code[paren:end + 1] if end > paren else code[paren:]
    if A05_LIVE_SET not in args:
        v.append("MinisApp.kt: '%s' is called without '%s()' — the sweep would "
                 "delete the sessions that are still live"
                 % (A05_SWEEP_CALL, A05_LIVE_SET))
    if not any(lo <= off <= hi for lo, hi in _a05_io_regions(code)):
        v.append("MinisApp.kt: '%s' is not inside a '%s' region — it is a Room "
                 "DELETE at cold start and must not run on the caller's thread"
                 % (A05_SWEEP_CALL, A05_IO))
    return v


def _local_bindings(code, start, end):
    """{name: rhs} for `val|var name = …` inside [start, end)."""
    out = {}
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=(?!=)", code[start:end]):
        rhs_start = start + m.end()
        out[m.group(1)] = code[rhs_start:expr_end(code, rhs_start)]
    return out


def _keeps_value(expr, name, locals_map, depth=0):
    """Does `expr` still carry the value of `name`?

    Uses the shared value-flow: elvis/if-else count both arms, `let`/`run` are
    judged on their tail expression, `also`/`apply` on their receiver. A bare
    identifier is resolved one hop into the enclosing function's local `val`s.
    """
    if depth > 3:
        return True
    for s in value_sources(expr):
        s2 = peel_discarding(s)
        if contains_ident(s2, name):
            return True
        key = re.sub(r"\s+", "", s2)
        if key in locals_map and _keeps_value(locals_map[key], name, locals_map, depth + 1):
            return True
        if contains_ident(s2, key) and key in locals_map:
            return True
    return False


def check_promote_draft(src):
    """Arm 9 [dv24-b3 / deep-audit-1044 B-06]: promoting a draft on "New Chat"
    must hand the typed body to a DURABLE home owned by the new session.

    The pre-fix function wrote `sessions.title = text.take(50).trim()` and
    nothing else, then blanked both in-memory copies. Consequences, in the order
    they bite: the list row has no `messages`, so `ChatHistoryDrawer`'s
    `pinnedAt / messageCounts` filter hides it; `cleanupIfEmptyOnExit` deletes it
    (and releases the VM) the moment the user opens and leaves that session;
    the startup sweep deletes it after 60 s. The text is gone 100% of the time.

    Two decisions, both anchored on the outcome rather than on a spelling:
      C1 — the captured text reaches `appendMessage(` / `updateSessionPreview(`
           AFTER `ensureSession()` (persisting under a sid that does not exist
           yet is a no-op);
      C2 — IF the composer copy is destroyed, that happens AFTER the durable
           write. Batch-7: "destroyed" is judged on the VALUE, not on a list of
           five spellings — any assignment to `_inputText.value` whose value no
           longer carries the captured `text` throws the only copy away, no
           matter how the empty string was built (`CharArray(0).concatToString()`
           included).
    """
    v = []
    code = structure(src)
    if code is None:
        v.append("ChatSessionEntryExt.kt: unterminated string/comment literal — cannot build "
                 "the offset-preserving view for the promotion ordering check "
                 "(ROT — update this gate)")
        return v
    start, end = body_of(code, "promoteDraftIfNeeded")
    if start is None:
        v.append("ChatSessionEntryExt.kt: '%s' not found (ROT — update this gate)"
                 % PROMOTE_DECL)
        return v
    body = code[start:end]

    ensure_at = body.find(ENSURE_CALL)
    wired = []
    for token in DURABLE_WRITES:
        k = body.find(token)
        while k >= 0:
            paren = body.find("(", k + len(token) - 1)
            spans = call_args(body, paren) if paren >= 0 else []
            args = body[spans[0][0]:spans[-1][1]] if spans else ""
            # The persisted payload must carry the captured text. `sid`-only
            # writes (a title, a binding) are exactly the pre-fix shape.
            if contains_ident(args, "text"):
                wired.append(k)
            k = body.find(token, k + 1)
    if not wired:
        v.append(
            "ChatSessionEntryExt.kt: promoteDraftIfNeeded never persists the captured draft text "
            "into the promoted session (no 'appendMessage(' / 'updateSessionPreview(' carrying "
            "`text`) — only a 50-char title would land, and a message-less row is hidden by the "
            "drawer, deleted by cleanupIfEmptyOnExit and swept at the next cold start"
        )
    elif ensure_at < 0:
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded does not call '%s' — the promoted "
                 "id it persists against cannot exist" % ENSURE_CALL)
    elif not any(k > ensure_at for k in wired):
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded persists the body BEFORE '%s' — "
                 "the write lands against a session id that does not exist yet and is "
                 "silently dropped" % ENSURE_CALL)

    locals_map = _local_bindings(code, start, end)
    destroyed = []
    for m in re.finditer(r"(?<![\w.`])_inputText\s*\.\s*value\s*=(?!=)", body):
        rhs_start = m.end()
        rhs = body[rhs_start:expr_end(body, rhs_start)]
        if not _keeps_value(rhs, "text", locals_map):
            destroyed.append(m.start())
    if destroyed and wired:
        first_durable = min(wired)
        for b in destroyed:
            if b < first_durable:
                v.append(
                    "ChatSessionEntryExt.kt: promoteDraftIfNeeded blanks the composer "
                    "BEFORE the durable body write — the assigned value no longer carries the "
                    "captured `text`, so a cancellation or DB failure in between leaves NO copy "
                    "of the draft text"
                )
                break
    # Arm 9b [third adversarial round 04-3]: the flag the `finally` consults must
    # really be flipped on the success path.
    v.extend(check_promote_persisted(code, src))
    return v


def check_promote_ownership(vm_src, entry_src):
    """Arm 11 [dv25-B08 / deep-audit-1045 B-08]: the promotion OWNS the session
    it creates, and the exit sweep must not reap it.

    The pre-fix sequence: `promoteDraftIfNeeded` freed the draft slot
    synchronously and then promoted asynchronously; `ensureSession()` inserted
    the row (so `realSessionId` became non-empty) while the first `messages` row
    was still several suspends away. Leaving the chat screen in that window
    fired `cleanupIfEmptyOnExit()`, whose IO coroutine read
    `messageCount(sid) == 0`, deleted the brand-new row and called
    `ChatViewModelStore.release(sid)` — which cancels the promotion's own
    `viewModelScope`. The composer copy was already dropped, so the user's typed
    body (and its attachments) were gone 100% of the time.

    Three criteria, each anchored on the DECISION rather than on a spelling:

      C3 — `cleanupIfEmptyOnExit` must consult the promotion's ownership bit.
           A session-ownership bit, NOT a grace period: the window has no
           bounded length (the write can be delayed by IO), and a timer would
           also skip rows the user really did leave empty.
      C4 — the sweep's decision must be routed through the shared pure
           predicate, so the four inputs live in ONE place. Four hand-written
           early returns are how the fourth input silently went missing once
           already (the pre-fix shape had exactly three).
      C5 — the promotion must keep the draft slot recoverable: the text is
           either durably persisted or written back to the draft slot. A
           promotion that drops the only copy without landing the write is the
           pre-fix shape.
    """
    v = []
    code_vm = structure(vm_src)
    code_entry = structure(entry_src)
    if code_vm is None or code_entry is None:
        v.append("ChatViewModel.kt / ChatSessionEntryExt.kt: unterminated string/comment literal "
                 "— cannot build the structural view for the promotion-ownership check "
                 "(ROT — update this gate)")
        return v

    cleanup_start, cleanup_end = body_of(code_vm, "cleanupIfEmptyOnExit")
    if cleanup_start is None:
        v.append("ChatViewModel.kt: '%s' not found (ROT — update this gate)" % CLEANUP_DECL)
        return v
    cleanup_body = code_vm[cleanup_start:cleanup_end]

    if CLEANUP_PREDICATE not in cleanup_body:
        v.append("ChatViewModel.kt: cleanupIfEmptyOnExit does not route its decision through "
                 "'%s' — the four reasons to spare a row (real id / streaming / attachments / a "
                 "promotion in flight) are four hand-written early returns, and the fourth one "
                 "went missing this way: the exit sweep then deletes the row a draft promotion "
                 "just created, before that promotion's first message lands" % CLEANUP_PREDICATE)
    if not PROMOTE_OWNER_READ_RE.search(cleanup_body):
        v.append("ChatViewModel.kt: cleanupIfEmptyOnExit never consults '%s' — a draft promotion "
                 "in flight owns the row it created, and deleting it here also releases the "
                 "ViewModel, cancelling the promotion that was about to fill it" % PROMOTE_OWNER_BIT)

    p_start, p_end = body_of(code_entry, "promoteDraftIfNeeded")
    if p_start is None:
        v.append("ChatSessionEntryExt.kt: '%s' not found (ROT — update this gate)" % PROMOTE_DECL)
        return v
    promote_body = code_entry[p_start:p_end]

    if not PROMOTE_OWNER_CLAIM_RE.search(promote_body):
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded never claims the session with "
                 "'%s' — without the ownership bit the exit sweep cannot tell a promotion-owned "
                 "row from an abandoned empty one, and the user's typed body is destroyed"
                 % PROMOTE_OWNER_BIT)
    if not PROMOTE_OWNER_RELEASE_RE.search(promote_body):
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded never releases '%s' — a claim that "
                 "is never handed back freezes the exit sweep off for the rest of the session, "
                 "and a genuinely empty row the user left would survive forever"
                 % PROMOTE_OWNER_BIT)
    if DRAFT_SAVE not in promote_body:
        v.append("ChatSessionEntryExt.kt: promoteDraftIfNeeded does not write the text back to "
                 "the draft slot ('%s') — a promotion whose durable write failed (or was "
                 "cancelled) leaves NO copy of the user's text anywhere" % DRAFT_SAVE)
    return v


def check_drawer_visibility(src):
    """Arm 10 [dv24-b3 / deep-audit-1044 B-06]: the drawer's visibility
    decision must treat a preview as content, without resurrecting ghost rows.

    A promoted draft's body lives in `last_message` while `messages` stays empty,
    so the old `pinnedAt != null || messageCounts > 0` filter hid exactly the
    sessions this fix rescues. The inverse hazard is the `P0-1-drawer-title-
    visibility` contract: `/memory` and `/thinking` toggle rows are message-less
    AND preview-less, so they must stay hidden.

    Criterion shape: the composable must ROUTE the filter through the extracted
    pure predicate (so the four cases are JVM-pinnable, see
    PromoteDraftVisibilityTest), and that predicate must consult all three
    inputs — dropping any one of them is a different bug (drop the pin term and
    pinned empties vanish; drop the count term and ordinary chats vanish; drop
    the preview term and this fix evaporates).
    """
    v = []
    code = structure(src)
    if code is None:
        v.append("ChatHistoryDrawer.kt: unterminated string/comment literal — cannot inspect the "
                 "visibility filter (ROT — update this gate)")
        return v
    if not DRAWER_FILTER_RE.search(code):
        v.append("ChatHistoryDrawer.kt: the session list filter does not route through '%s' — the "
                 "visibility decision is inline again (a promoted draft with a preview but no "
                 "messages row stays invisible)" % DRAWER_VISIBLE_CALL)
    decl_start, body_end = body_of(code, "isDrawerRowVisible")
    if decl_start is None:
        v.append("ChatHistoryDrawer.kt: '%s' not found (ROT — update this gate)"
                 % DRAWER_VISIBLE_DECL)
        return v
    # Look at the DECISION, not the parameter list: the parameters are named
    # after the three inputs, so a term dropped from the predicate body would
    # otherwise still be "mentioned" by its own parameter.
    paren = code.find("(", decl_start)
    close = match_bracket(code, paren) if paren >= 0 else -1
    decision = code[close + 1:body_end] if close > paren else code[decl_start:body_end]
    for term, label in (("pinnedAt", "the pinned reason"),
                        ("messageCount", "the has-messages reason"),
                        ("lastMessage", "the has-a-preview reason")):
        if term not in decision:
            v.append("ChatHistoryDrawer.kt: isDrawerRowVisible does not consult '%s' (%s) — "
                     "that row class silently disappears from the drawer" % (term, label))
    if not any(token in decision for token in PREVIEW_BLANK_CHECKS):
        v.append("ChatHistoryDrawer.kt: isDrawerRowVisible accepts any non-null preview — a "
                 "blank `last_message` would resurrect the ghost rows "
                 "(P0-1-drawer-title-visibility): test blankness (%s)"
                 % " / ".join(PREVIEW_BLANK_CHECKS))
    return v


# --------------------------------------------------------------------------
# drawer delete order
# --------------------------------------------------------------------------
#
#   [dv27-B4 / B-06] `deleteSessionAndCleanup` released the ChatViewModel AFTER
#   deleting the row + directory. Two windows opened: 1) the live streamJob /
#   BrowserTabPool recreated the tree just removed (ghost directory), and 2) the
#   cancelled stream's `finally` re-published a badge AFTER SessionBadgeStore.clear.
#
#   Batch-7: the plan's order is what runs at runtime, so the slice that feeds
#   the order criterion must include the postfix chain — `listOf(…).reversed()`
#   keeps the literal argument order (what the old slice read) while flipping
#   the executed one.
DELETE_FN_DECL = "fun deleteSessionAndCleanup("
PLAN_FN_LOOP_RE = re.compile(
    r"for\s*\(\s*\w+\s+in\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)\s*\)")
# Step constants may be written bare or qualified (`SessionDeleteStep.RELEASE_VM`);
# both shapes must resolve, else renaming the enum makes the gate blind.
ENUM_CONST_RE = re.compile(r"(?:[A-Za-z_]\w*\.)?([A-Z][A-Z0-9_]{2,})(?![.\w])")
WHEN_BRANCH_RE = re.compile(r"(?:[A-Za-z_]\w*\.)?([A-Z][A-Z0-9_]{2,})\s*->")
RELEASE_CALL_RE = re.compile(r"ChatViewModelStore\s*\.\s*release\s*\(")
DELETE_CALL_RE = re.compile(r"\.\s*deleteSession\s*\(")
BADGE_CLEAR_RE = re.compile(r"SessionBadgeStore\s*\.\s*clear\s*\(")
# Postfix calls that change the runtime ORDER of a returned list.
PLAN_REVERSING = ("reversed", "asReversed", "sortedByDescending", "sortedDescending")
PLAN_ORDER_PRESERVING = ("toList", "toMutableList", "toTypedArray", "asList", "orEmpty")


def _plan_constant_order(src, plan_name):
    """The ordered constants a plan function RETURNS (runtime order), or None.

    Resolution is two-hop on purpose: the name comes from the `for (step in
    <plan>())` loop in the drawer, so renaming the function (or any step) moves
    the gate with it. A postfix call that reorders the list is applied to the
    extracted order; an unknown postfix call fails closed (None) rather than
    guessing.
    """
    if not src:
        return None
    code = structure(src)
    if code is None:
        return None
    start, end = body_of(code, plan_name)
    if start is None:
        return None
    body = code[start:end]
    open_call = body.find("listOf(")
    if open_call < 0:
        return None
    close = match_bracket(body, open_call + len("listOf"))
    if close < 0:
        return None
    order = [m.group(1) for m in ENUM_CONST_RE.finditer(body[open_call:close])]
    tail = body[close + 1:]
    i = 0
    while True:
        while i < len(tail) and tail[i] in " \t\r\n":
            i += 1
        if i >= len(tail) or tail[i] != ".":
            break
        m = re.match(r"\.\s*([A-Za-z_]\w*)\s*\(", tail[i:])
        if not m:
            return None
        name = m.group(1)
        open_paren = i + m.end() - 1
        call_close = match_bracket(tail, open_paren)
        if call_close < 0:
            return None
        if name in PLAN_REVERSING:
            order = list(reversed(order))
        elif name not in PLAN_ORDER_PRESERVING:
            return None
        i = call_close + 1
    return order


def _branch_effects(fn_body):
    """{step constant: set of effects} derived from the `when (step)` branches.

    Effects are the three calls the plan is allowed to dispatch to. A branch
    that carries none of them is simply absent from the map (and the order
    criterion then reports the plan as unresolvable rather than guessing).

    The branch text is bounded by the `when` block's closing brace, not by the
    end of the function: the unconditional `finally` tail re-issues release and
    clear, so an unbounded last branch would inherit those effects and make the
    order criterion blind to a reordered plan.
    """
    marks = list(WHEN_BRANCH_RE.finditer(fn_body))
    if not marks:
        return {}
    when_at = fn_body.rfind("when", 0, marks[0].start())
    block_end = len(fn_body)
    if when_at >= 0:
        paren = fn_body.find("(", when_at)
        brace = fn_body.find("{", paren) if paren >= 0 else -1
        if brace >= 0:
            close = match_bracket(fn_body, brace)
            if close > brace:
                block_end = close
    out = {}
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else block_end
        branch = fn_body[m.end():min(end, block_end)]
        effects = set()
        if RELEASE_CALL_RE.search(branch):
            effects.add("release")
        if DELETE_CALL_RE.search(branch):
            effects.add("delete")
        if BADGE_CLEAR_RE.search(branch):
            effects.add("clear")
        if effects:
            out.setdefault(m.group(1), set()).update(effects)
    return out


def check_drawer_delete_order(drawer_src, plan_src=None):
    """C-DEL-1 + C-DEL-2 [dv27-B4 B-06].

    C-DEL-1  release strictly before delete (through the plan when one exists).
    C-DEL-2  the `finally` still replays release + badge clear, i.e. the fix did
             not trade the [fix/audit-0917-b9] "cleanup even on failure"
             contract for the new ordering.
    """
    v = []
    code = structure(drawer_src)
    if code is None:
        v.append("ChatHistoryDrawer.kt: unterminated string/comment literal — cannot inspect "
                 "the session-deletion order (ROT — update this gate)")
        return v
    fn_start, fn_end = body_of(code, "deleteSessionAndCleanup")
    if fn_start is None:
        v.append("ChatHistoryDrawer.kt: '%s' not found (ROT — update this gate)"
                 % DELETE_FN_DECL)
        return v
    fn_body = code[fn_start:fn_end]

    release_idx = delete_idx = None
    loop = PLAN_FN_LOOP_RE.search(fn_body)
    if loop:
        plan_name = loop.group(1)
        order = _plan_constant_order(plan_src, plan_name) or \
            _plan_constant_order(drawer_src, plan_name)
        if not order:
            v.append("ChatHistoryDrawer.kt: the delete path iterates '%s()' but no plan "
                     "function of that name returning an ordered list was found in the drawer "
                     "or in its sibling plan file (ROT — update this gate)" % plan_name)
            return v
        effects = _branch_effects(fn_body)
        for i, const in enumerate(order):
            eff = effects.get(const, set())
            if "release" in eff and release_idx is None:
                release_idx = i
            if "delete" in eff and delete_idx is None:
                delete_idx = i
        if release_idx is None or delete_idx is None:
            v.append("ChatHistoryDrawer.kt: the delete plan (%s) has no step that reaches "
                     "ChatViewModelStore.release( and no step that reaches a deleteSession( "
                     "call — the ordering contract cannot be verified (C-DEL-1)"
                     % " -> ".join(order))
            return v
    else:
        # Inline shape (no plan hop): fall back to statement order.
        rel = RELEASE_CALL_RE.search(fn_body)
        dele = DELETE_CALL_RE.search(fn_body)
        if rel is None or dele is None:
            v.append("ChatHistoryDrawer.kt: the delete path no longer calls both "
                     "ChatViewModelStore.release( and deleteSession( — ordering unverifiable "
                     "(C-DEL-1)")
            return v
        release_idx, delete_idx = rel.start(), dele.start()

    if release_idx > delete_idx:
        v.append("ChatHistoryDrawer.kt: the session is deleted BEFORE its ChatViewModel is "
                 "released (C-DEL-1) — the live streamJob / BrowserTabPool recreates "
                 "minis-sessions/<id>/… in the tree that was just removed, and the cancelled "
                 "stream's finally re-publishes the badge after the clear. Release first.")

    # C-DEL-2: the unconditional cleanup tail must survive the reordering.
    fin_at = fn_body.find("finally")
    if fin_at < 0:
        v.append("ChatHistoryDrawer.kt: the delete path has no 'finally' — a throw from any "
                 "step now leaves the ChatViewModel and the badge behind "
                 "([fix/audit-0917-b9] contract, C-DEL-2)")
    else:
        open_at = fn_body.find("{", fin_at)
        close_at = match_bracket(fn_body, open_at) if open_at >= 0 else -1
        tail = fn_body[open_at:close_at + 1] if close_at > open_at else ""
        missing = []
        if not RELEASE_CALL_RE.search(tail):
            missing.append("ChatViewModelStore.release(")
        if not BADGE_CLEAR_RE.search(tail):
            missing.append("SessionBadgeStore.clear(")
        if missing:
            v.append("ChatHistoryDrawer.kt: the 'finally' cleanup dropped %s — the "
                     "unconditional cleanup contract ([fix/audit-0917-b9]) is gone (C-DEL-2)"
                     % " / ".join(missing))
    return v

GOOD_APP_SWEEP = """
class MinisApp : Application() {
    override fun onCreate() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val swept = chatRepository.deleteEmptySessions(
                    com.rikkaminis.app.ui.chat.ChatViewModelStore.activeSessionIds(),
                )
            }
        }
    }
}
"""


# ── session-deletion step order (dv27-B4 / deep-audit-1047 B-06) ────────────
#
# C-DEL-1 (order): the FIRST `ChatViewModelStore.release(` in the delete path
#   must precede `deleteSession(`. The order may be delegated to one pure plan
#   function (that is the fix's whole point — a JVM test can pin it), so the
#   criterion resolves the plan the body iterates and reads ITS return order,
#   then maps each step to its effect through the `when (step)` branches. Both
#   the step names and the plan function's name are therefore free to change;
#   only the relation "release before delete" is asserted.
#
#   Deleting before releasing is not a style question: the resident
#   ChatViewModel's streamJob / BrowserTabPool keep writing
#   `minis-sessions/<id>/…` back into the directory that was just removed
#   (ghost directory), and the cancelled stream's own `finally` re-publishes a
#   badge AFTER SessionBadgeStore.clear(id) (deleted session lights up again).
DELETE_FN_DECL = "fun deleteSessionAndCleanup("
PLAN_FN_LOOP_RE = re.compile(
    r"for\s*\(\s*\w+\s+in\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)\s*\)")
# Step constants may be written bare or qualified (`SessionDeleteStep.RELEASE_VM`);
# both shapes must resolve, else renaming the enum makes the gate blind.
ENUM_CONST_RE = re.compile(r"(?:[A-Za-z_]\w*\.)?([A-Z][A-Z0-9_]{2,})(?![.\w])")
WHEN_BRANCH_RE = re.compile(r"(?:[A-Za-z_]\w*\.)?([A-Z][A-Z0-9_]{2,})\s*->")
RELEASE_CALL_RE = re.compile(r"ChatViewModelStore\s*\.\s*release\s*\(")
DELETE_CALL_RE = re.compile(r"\.\s*deleteSession\s*\(")
BADGE_CLEAR_RE = re.compile(r"SessionBadgeStore\s*\.\s*clear\s*\(")


BAD_APP_SWEEP_UNWIRED = GOOD_APP_SWEEP.replace(
    "chatRepository.deleteEmptySessions(", "chatRepository.countEmptySessions(")

BAD_APP_SWEEP_NO_LIVE_SET = GOOD_APP_SWEEP.replace(
    "                    com.rikkaminis.app.ui.chat.ChatViewModelStore.activeSessionIds(),\n", "")

BAD_APP_SWEEP_ON_MAIN = GOOD_APP_SWEEP.replace(
    "kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {",
    "kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {")


def read(root, rel):
    path = os.path.join(root, rel)
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def run(root):
    if not os.path.isdir(root):
        print("skill_restore_session_cleanup_check: not a directory: %s" % root)
        return 2
    try:
        skill = read(root, SKILL_REPO_REL)
        chat = read(root, CHAT_REPO_REL)
        app = read(root, MINIS_APP_REL)
        entry = read(root, CHAT_ENTRY_REL)
        drawer = read(root, DRAWER_REL)
        try:
            delete_plan = read(root, SESSION_DELETE_PLAN_REL)
        except OSError:
            delete_plan = None
        vm = read(root, CHAT_VM_REL)
    except OSError as exc:
        print("skill_restore_session_cleanup_check: cannot read sources: %s" % exc)
        return 2
    violations = (check_skill_repo(skill) + check_chat_repo(chat) + check_minis_app(app)
                  + check_chat_entry(entry) + check_minis_app_badge(app)
                  + check_minis_app_sweep(app)
                  + check_promote_draft(entry) + check_drawer_visibility(drawer)
                  + check_promote_ownership(vm, entry)
                  + check_drawer_delete_order(drawer, delete_plan))
    if violations:
        print("FAIL skill_restore_session_cleanup_check")
        for v in violations:
            print("  " + v)
        return 1
    print("PASS skill_restore_session_cleanup_check (id threaded, cleanup closed)")
    return 0


# --------------------------------------------------------------------------
# selftest: compliant fixtures must be clean, violating fixtures must trip
# --------------------------------------------------------------------------

GOOD_SKILL = """
internal class SkillRepository(private val context: Context) {
    fun add(
        name: String,
        description: String,
        body: String,
        frontmatter: String = "",
        preferredId: String? = null,
    ): Skill? {
        val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(name))
        if (id.isBlank()) return null
        return synchronized(addLock) { _skills.value.firstOrNull { it.id == id } }
    }

    fun importFromContent(
        content: String,
        preferredId: String? = null,
    ): Skill? {
        val parsed = parseSkillMd(content) ?: return null
        val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(parsed.name))
        if (id.isNotBlank() && _skills.value.any { it.id == id }) {
            return replace(id, parsed)
        }
        return add(
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            frontmatter = SubagentSkill.extractFrontmatterBlock(content),
            preferredId = id,
        )
    }

    fun skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"

    private fun installBundledSkill(dirName: String) {
        val parsed = parseSkillMd(readAsset(dirName)) ?: return
        if (existing != null) {
            update(existing)
        } else {
            add(
                name = parsed.name,
                body = parsed.body,
                frontmatter = "",
                preferredId = dirName,
            )
        }
    }

    private fun isAdoptableBundledRow(existing: Skill): Boolean = false

    // [dv16-A05] the compliant shape: sparse-clear when the value equals the
    // CURRENT global default, decided by the shared pure function.
    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        val globalEnabled = _skills.value.find { it.id == skillId }?.isEnabled
        if (shouldDeleteSessionSkillOverride(globalEnabled, enabled)) {
            db.execSQL(
                "DELETE FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
                arrayOf(sessionId, skillId)
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
                arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
            )
        }
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    fun recordSkillUse(skillId: String) {
        // The mirror must move under the same lock as add().
        synchronized(addLock) {
            val current = _skills.value.find { it.id == skillId } ?: return
            val bumped = current.copy(useCount = current.useCount + 1.0)
            db.execSQL(
                "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
                arrayOf<Any>(skillId)
            )
            _skills.value = _skills.value.map { if (it.id == skillId) bumped else it }
        }
    }
}
"""

BAD_SKILL_RAW_SLUG = GOOD_SKILL.replace(
    "val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(name))\n        if (id.isBlank()) return null",
    "val id = slugify(name)\n        if (id.isBlank()) return null",
)

BAD_SKILL_DROP_ID = GOOD_SKILL.replace(
    "            frontmatter = SubagentSkill.extractFrontmatterBlock(content),\n            preferredId = id,\n        )",
    "            frontmatter = SubagentSkill.extractFrontmatterBlock(content),\n        )",
)

BAD_SKILL_BUNDLED = GOOD_SKILL.replace("preferredId = dirName,", "")

# [third adversarial round 04-2] the replace-existing guard, neutralised by a
# constant: Arm 2 (the fall-through `return add(preferredId = id)`) still holds,
# and every re-import now falls into add(), which refuses the duplicate id.
BAD_SKILL_REPLACE_UNREACHABLE = GOOD_SKILL.replace(
    "if (id.isNotBlank() && _skills.value.any { it.id == id }) {",
    "if (false && id.isNotBlank() && _skills.value.any { it.id == id }) {")

# [da1029-C / I04] the pre-fix shape: `mapped` computed under the lock, then
# published after the release — exactly what the KDoc above it used to claim
# was already impossible.
BAD_SKILL_PUBLISH_OUTSIDE_LOCK = GOOD_SKILL.replace(
    """        synchronized(addLock) {
            val current = _skills.value.find { it.id == skillId } ?: return""",
    """        val next = synchronized(addLock) {
            val current = _skills.value.find { it.id == skillId } ?: return""",
).replace(
    """            _skills.value = _skills.value.map { if (it.id == skillId) bumped else it }
        }
    }
}""",
    """            _skills.value.map { if (it.id == skillId) bumped else it }
        }
        _skills.value = next
    }
}""",
)

# No lock at all around the read-modify-write.
BAD_SKILL_NO_LOCK = GOOD_SKILL.replace(
    """        synchronized(addLock) {
            val current = _skills.value.find { it.id == skillId } ?: return""",
    """        run {
            val current = _skills.value.find { it.id == skillId } ?: return""",
)

# Anchor itself gone -> fail-closed, not a silent skip.
BAD_SKILL_DROP_FUNCTION = GOOD_SKILL.replace(
    "fun recordSkillUse(", "fun recordSkillUseRenamed(")

# ── wide-1024 witnesses (mechanically reproduced on the real tree: pre-fix
# rc=0, post-fix rc=1) ────────────────────────────────────────────────────

# W1 (dataflow): the policy call and the `preferredId` mention both survive —
# the argument is neutralised to a constant, so the caller's resolved id is
# dropped exactly as before [fix/da1021-A].
BAD_SKILL_NEUTRALISED_ID = GOOD_SKILL.replace(
    "val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(name))",
    "val id = SkillRestorePolicy.resolveImportId(preferredId.let { null }, slugify(name))",
)

# W2 (dataflow): only the PUBLISH is locked; the read it derives from and the
# DB bump sit outside, so two concurrent calls both read the pre-bump snapshot.
BAD_SKILL_RMW_OUTSIDE_LOCK = GOOD_SKILL.replace(
    """        synchronized(addLock) {
            val current = _skills.value.find { it.id == skillId } ?: return
            val bumped = current.copy(useCount = current.useCount + 1.0)
            db.execSQL(
                "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
                arrayOf<Any>(skillId)
            )
            _skills.value = _skills.value.map { if (it.id == skillId) bumped else it }
        }""",
    """        val current = _skills.value.find { it.id == skillId } ?: return
        val bumped = current.copy(useCount = current.useCount + 1.0)
        db.execSQL(
            "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
            arrayOf<Any>(skillId)
        )
        synchronized(addLock) {
            _skills.value = _skills.value.map { if (it.id == skillId) bumped else it }
        }""",
)

# [dv16-A05] the pre-fix shape: INSERT OR REPLACE always — a toggled-back
# session keeps its pinned row forever (the MCP twin sparse-clears; this one
# didn't).
BAD_SKILL_PIN_OVERRIDE = GOOD_SKILL.replace(
    """        val globalEnabled = _skills.value.find { it.id == skillId }?.isEnabled
        if (shouldDeleteSessionSkillOverride(globalEnabled, enabled)) {
            db.execSQL(
                "DELETE FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
                arrayOf(sessionId, skillId)
            )
        } else {
            db.execSQL(""",
    """        db.execSQL(""",
)

GOOD_CHAT = """
class ChatRepository(
    internal val dao: ChatDao,
    private val sessionFiles: SessionFileStore? = null,
    val onSessionDeleted: ((String) -> Unit)? = null,
) {
    suspend fun deleteSession(id: String): SessionFileStore.DeleteResult? {
        dao.deleteMessages(id)
        dao.deleteSession(id)
        runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }
        return sessionFiles?.deleteSessionFiles(id)
    }

    suspend fun deleteSessionRowOnly(id: String) {
        dao.deleteMessages(id)
        dao.deleteSession(id)
        runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }
    }

    suspend fun deleteEmptySessions(
        activeIds: List<String> = emptyList(),
        graceMillis: Long = 60_000L,
    ): Int {
        val guarded = guardActiveIds(activeIds)
        val staleBefore = System.currentTimeMillis() - graceMillis
        val candidates = dao.selectEmptySessionIds(guarded, staleBefore)
        val deleted = dao.deleteEmptySessions(guarded, staleBefore)
        if (candidates.isEmpty()) return deleted
        val survivors = dao.existingSessionIds(candidates).toSet()
        for (id in candidates) {
            if (id in survivors) continue
            runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }
        }
        return deleted
    }
}
"""

# One line of the hook, exactly as the wired fixture spells it. Removing every
# occurrence models "the cleanup was never wired anywhere".
HOOK_LINE = "runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }\n"
# The sweep's copy sits one indent deeper (inside the per-id loop), so this key
# removes ONLY the sweep's notification.
SWEEP_HOOK_LINE = "            " + HOOK_LINE

BAD_CHAT_NO_HOOK = GOOD_CHAT.replace(HOOK_LINE, "")

# The dv21-a2 regression itself: a two-entry-point gate was blind to the sweep.
BAD_CHAT_SWEEP_NO_HOOK = GOOD_CHAT.replace(SWEEP_HOOK_LINE, "")

# [third adversarial round 04-1] the sweep's survivor filter, inverted: every
# LIVE session is notified as deleted and every really-deleted one is skipped.
BAD_CHAT_SURVIVOR_POLARITY = GOOD_CHAT.replace(
    "            if (id in survivors) continue\n",
    "            if (id !in survivors) continue\n")

# A FOURTH entry point that drops `sessions` rows without the cleanup: the gate
# must derive the entry set from the tree, not from a list someone forgot.
BAD_CHAT_UNREGISTERED_ENTRY = GOOD_CHAT.replace(
    "        return deleted\n    }\n}",
    "        return deleted\n    }\n\n"
    "    suspend fun deleteExpiredSessions(ids: List<String>) {\n"
    "        dao.deleteSession(ids.first())\n"
    "    }\n}",
)

# Delegation is legal: the entry points call one helper that owns the hook.
GOOD_CHAT_DELEGATED = GOOD_CHAT.replace(
    "            " + HOOK_LINE, "            notifySessionDeleted(id)\n",
).replace(
    "        " + HOOK_LINE, "        notifySessionDeleted(id)\n",
).replace(
    "    }\n}",
    "    }\n\n    private fun notifySessionDeleted(id: String) {\n"
    "        runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }\n"
    "    }\n}",
)


def _drop_helper_hook(src):
    """Keep the delegated shape but empty the helper — the delegation branch
    must not launder an unhooked helper."""
    return src.replace(
        "    private fun notifySessionDeleted(id: String) {\n"
        "        runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }\n"
        "    }\n",
        "    private fun notifySessionDeleted(id: String) {\n"
        "        log(id)\n"
        "    }\n",
    )


BAD_CHAT_HELPER_NO_HOOK = _drop_helper_hook(GOOD_CHAT_DELEGATED)

GOOD_APP = """
class MinisApp : Application() {
    lateinit var chatRepository: ChatRepository
    lateinit var skillRepository: SkillRepository
    lateinit var mcpRepository: MCPRepository

    override fun onCreate() {
        database = AppDatabase.getInstance(this)
        skillRepository = SkillRepository(this)
        mcpRepository = MCPRepository(this)
        chatRepository = ChatRepository(
            database.chatDao(),
            SessionFileStore(this),
            onSessionDeleted = { sid ->
                runCatching { skillRepository.clearSessionOverrides(sid) }
                runCatching { mcpRepository.clearSessionOverrides(sid) }
                runCatching { SessionBadgeStore.clear(sid) }
            },
        )
    }
}
"""

# [da1030-A / I01 裂缝B] the badge queue left behind by the pre-fix hook.
BAD_APP_NO_BADGE = GOOD_APP.replace(
    "                runCatching { SessionBadgeStore.clear(sid) }\n", "")

# Precision control: a call that exists, but NOT on the delete path — the
# criterion must be scoped to the lambda body, not to the file.
BAD_APP_BADGE_ELSEWHERE = GOOD_APP.replace(
    "                runCatching { SessionBadgeStore.clear(sid) }\n", "").replace(
    "    override fun onCreate() {\n",
    "    override fun onCreate() {\n        SessionBadgeStore.clear(\"unrelated\")\n")

# [da1030-A / I01] draft-promotion fixture. The `if (isDraft)` block carries
# the full hop set the production file has.
GOOD_ENTRY = """
internal suspend fun ChatViewModel.ensureSession(): String {
    if (realSessionId.isNotEmpty()) return realSessionId
    val session = chatRepository.createSession(modelId = modelId)
    realSessionId = session.id
    if (isDraft) {
        ChatViewModelStore.rename(sessionId, session.id)
        migrateDraftResources(fromDraft = sessionId, toReal = session.id)
        skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        runCatching {
            (context.applicationContext as? MinisApp)
                ?.webAppShortcutRepository
                ?.renameSessionShortcuts(fromDraft = sessionId, toReal = session.id)
        }
        _browserTabPoolRef?.setSession(session.id)
    }
    return realSessionId
}
"""

BAD_ENTRY_DROP_SHORTCUTS = GOOD_ENTRY.replace(
    """        runCatching {
            (context.applicationContext as? MinisApp)
                ?.webAppShortcutRepository
                ?.renameSessionShortcuts(fromDraft = sessionId, toReal = session.id)
        }
""", "")

# Precision control: the call exists but sits OUTSIDE the `if (isDraft)` guard
# (it would then also fire for already-real sessions, in the wrong window).
BAD_ENTRY_OUTSIDE_DRAFT = GOOD_ENTRY.replace(
    """        runCatching {
            (context.applicationContext as? MinisApp)
                ?.webAppShortcutRepository
                ?.renameSessionShortcuts(fromDraft = sessionId, toReal = session.id)
        }
""", "").replace(
    "    return realSessionId\n}",
    "    webAppShortcutRepository?.renameSessionShortcuts(sessionId, session.id)\n"
    "    return realSessionId\n}")

# [dv24-b3 / deep-audit-1044 B-06] draft-promotion fixtures. The compliant
# shape persists the body AFTER ensureSession and blanks the composer only
# after that write landed.
GOOD_PROMOTE = '''
fun ChatViewModel.promoteDraftIfNeeded(): Boolean {
    if (!isDraft || realSessionId.isNotEmpty()) return false
    val text = _inputText.value
    if (text.isBlank()) return false
    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)
    viewModelScope.launch {
        val sid = ensureSession()
        if (sid.isEmpty()) return@launch
        val title = text.take(50).trim()
        chatRepository.updateSessionTitleAndCategory(sid, title, null)
        _sessionTitle.value = title
        val promoted = chatRepository.appendMessage(sid, "user", buildUserPartsJson(text, emptyList()))
        _messages.value = _messages.value + ChatMessage(id = promoted.id, role = "user", content = text)
        _inputText.value = ""
    }
    return true
}
'''

PERSIST_TWO_LINES = (
    "        val promoted = chatRepository.appendMessage(sid, \"user\", buildUserPartsJson(text, emptyList()))\n"
    "        _messages.value = _messages.value + ChatMessage(id = promoted.id, role = \"user\", content = text)\n"
)

# The pre-fix shape: a 50-char title and nothing else.
BAD_PROMOTE_TITLE_ONLY = GOOD_PROMOTE.replace(PERSIST_TWO_LINES, "")

# Ordering hazard: the composer is blanked BEFORE the durable write, so a
# cancellation in between destroys the only copy.
BAD_PROMOTE_BLANK_FIRST = GOOD_PROMOTE.replace(
    "        _inputText.value = \"\"\n", "").replace(
    "    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)\n",
    "    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)\n"
    "    _inputText.value = \"\"\n")

# W3 (wide-1024, spelling→effect): the same destruction as BAD_PROMOTE_BLANK_FIRST
# spelled `String()` instead of `""` — the old `= ["']` regex could not see it.
BAD_PROMOTE_STRING_BLANK = GOOD_PROMOTE.replace(
    "    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)\n",
    "    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)\n"
    "    _inputText.value = String()\n")

# Persisting before the id exists is a no-op against an empty sid.
BAD_PROMOTE_PERSIST_BEFORE_ENSURE = GOOD_PROMOTE.replace(
    "    viewModelScope.launch {\n        val sid = ensureSession()",
    "    viewModelScope.launch {\n"
    "        chatRepository.appendMessage(realSessionId, \"user\", buildUserPartsJson(text, emptyList()))\n"
    "        val sid = ensureSession()").replace(
    PERSIST_TWO_LINES, "")

# [third adversarial round 04-3] the shipped `persisted` shape: the bit is
# declared false, flipped on the success path, and consulted in `finally`.
PROMOTE_PERSISTED_GOOD = '''
fun ChatViewModel.promoteDraftIfNeeded(): Boolean {
    if (!isDraft || realSessionId.isNotEmpty()) return false
    val text = _inputText.value
    if (text.isBlank()) return false
    promoteInFlight = true
    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, sessionId)
    viewModelScope.launch {
        var persisted = false
        try {
            val sid = ensureSession()
            if (sid.isEmpty()) return@launch
            val promoted = chatRepository.appendMessage(sid, "user", buildUserPartsJson(text, emptyList()))
            _messages.value = _messages.value + ChatMessage(id = promoted.id, role = "user", content = text)
            persisted = true
            _inputText.value = ""
        } finally {
            promoteInFlight = false
            if (!persisted) {
                com.rikkaminis.app.data.ComposerDraftStore.saveText(context, currentOwner, text)
            }
        }
    }
    return true
}
'''

# The witness: the success path writes `false`, so `finally` restores the text it
# just persisted. Every other anchor still holds.
BAD_PROMOTE_FLAG_NOT_FLIPPED = PROMOTE_PERSISTED_GOOD.replace(
    "            persisted = true\n", "            persisted = false\n")

# [dv25-B08 / deep-audit-1045 B-08] promotion-ownership fixtures.
GOOD_VM = """
class ChatViewModel {
    @Volatile
    internal var promoteInFlight: Boolean = false

    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (shouldSkipCleanup(
                hasRealId = sid.isNotEmpty(),
                streaming = _isStreaming.value,
                hasAttachments = _attachments.value.isNotEmpty(),
                promoteInFlight = promoteInFlight,
            )
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val count = chatRepository.messageCount(sid)
            if (count > 0) return@launch
            if (promoteInFlight) return@launch
            chatRepository.deleteSessionRowOnly(sid)
        }
    }
}
"""

# The pre-fix shape: three hand-written early returns, no ownership bit.
BAD_VM_NO_OWNER = GOOD_VM.replace(
    """        if (shouldSkipCleanup(
                hasRealId = sid.isNotEmpty(),
                streaming = _isStreaming.value,
                hasAttachments = _attachments.value.isNotEmpty(),
                promoteInFlight = promoteInFlight,
            )
        ) {
            return
        }
""",
    """        if (shouldSkipCleanup(
                hasRealId = sid.isNotEmpty(),
                streaming = _isStreaming.value,
                hasAttachments = _attachments.value.isNotEmpty(),
                promoteInFlight = false,
            )
        ) {
            return
        }
""").replace(
    "            if (promoteInFlight) return@launch\n", "")

# The ownership bit is consulted, but the decision is spread over hand-written
# early returns — the shape that lost the fourth input once already.
BAD_VM_NO_PREDICATE = GOOD_VM.replace(
    """        if (shouldSkipCleanup(
                hasRealId = sid.isNotEmpty(),
                streaming = _isStreaming.value,
                hasAttachments = _attachments.value.isNotEmpty(),
                promoteInFlight = promoteInFlight,
            )
        ) {
            return
        }
""",
    """        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        if (promoteInFlight) return
""")

# Both criteria gone: the hand-written returns AND no ownership bit.
BAD_VM_NO_OWNER_AT_ALL = GOOD_VM.replace(
    """        if (shouldSkipCleanup(
                hasRealId = sid.isNotEmpty(),
                streaming = _isStreaming.value,
                hasAttachments = _attachments.value.isNotEmpty(),
                promoteInFlight = promoteInFlight,
            )
        ) {
            return
        }
""",
    """        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
""").replace(
    "            if (promoteInFlight) return@launch\n", "")

GOOD_PROMOTE_OWNED = """
fun ChatViewModel.promoteDraftIfNeeded(): Boolean {
    if (!isDraft || realSessionId.isNotEmpty()) return false
    if (promoteInFlight) return false
    val text = composerTextForPromotion(_pendingComposerText.value, _inputText.value)
    if (text.isBlank()) return false
    promoteInFlight = true
    com.rikkaminis.app.data.ComposerDraftStore.clearDraft(context, draftId)
    viewModelScope.launch(Dispatchers.IO) {
        var persisted = false
        try {
            val sid = ensureSession()
            if (sid.isEmpty()) return@launch
            val promoted = chatRepository.appendMessage(sid, "user", buildUserPartsJson(text, prepared.mediaRefPartsJson))
            persisted = true
            _inputText.value = ""
        } finally {
            promoteInFlight = false
            if (!persisted) {
                com.rikkaminis.app.data.ComposerDraftStore.saveText(context, draftId, text)
            }
        }
    }
    return true
}
"""

# The promotion never claims the session.
BAD_PROMOTE_NO_OWNER = GOOD_PROMOTE_OWNED.replace(
    "    promoteInFlight = true\n", "")

# The claim is made but never released: the sweep would stay off for the rest
# of the session.
BAD_PROMOTE_NO_RELEASE = GOOD_PROMOTE_OWNED.replace(
    "            promoteInFlight = false\n", "")

# The promotion drops the only copy when its write did not land.
BAD_PROMOTE_NO_RESTORE = GOOD_PROMOTE_OWNED.replace(
    """            if (!persisted) {
                com.rikkaminis.app.data.ComposerDraftStore.saveText(context, draftId, text)
            }
""", "")

GOOD_DRAWER = '''
@Composable
fun ChatHistoryDrawer(chatRepository: ChatRepository) {
    val messageCounts by chatRepository.observeMessageCountsPerSession()
        .collectAsState(initial = emptyMap())
    val visibleSessions = remember(sessions, messageCounts) {
        sessions.filter { session ->
            isDrawerRowVisible(
                pinnedAt = session.pinnedAt,
                messageCount = messageCounts[session.id] ?: 0,
                lastMessage = session.lastMessage,
            )
        }
    }
}

internal fun isDrawerRowVisible(
    pinnedAt: Long?,
    messageCount: Int,
    lastMessage: String?,
): Boolean = pinnedAt != null || messageCount > 0 || !lastMessage.isNullOrBlank()
'''

# The pre-fix inline filter: no preview term at all, and no shared predicate.
BAD_DRAWER_INLINE_FILTER = GOOD_DRAWER.replace(
    """        sessions.filter { session ->
            isDrawerRowVisible(
                pinnedAt = session.pinnedAt,
                messageCount = messageCounts[session.id] ?: 0,
                lastMessage = session.lastMessage,
            )
        }""",
    "        sessions.filter { it.pinnedAt != null || (messageCounts[it.id] ?: 0) > 0 }",
)

# Dropping the has-messages term makes every ordinary chat vanish.
BAD_DRAWER_NO_COUNT = GOOD_DRAWER.replace(
    "): Boolean = pinnedAt != null || messageCount > 0 || !lastMessage.isNullOrBlank()",
    "): Boolean = pinnedAt != null || !lastMessage.isNullOrBlank()",
)

# Accepting any non-null preview resurrects the ghost rows.
BAD_DRAWER_ANY_NON_NULL = GOOD_DRAWER.replace(
    "!lastMessage.isNullOrBlank()",
    "lastMessage != null",
)

BAD_APP_ORDER = """
class MinisApp : Application() {
    lateinit var chatRepository: ChatRepository
    lateinit var skillRepository: SkillRepository
    lateinit var mcpRepository: MCPRepository

    override fun onCreate() {
        database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(
            database.chatDao(),
            SessionFileStore(this),
        )
        skillRepository = SkillRepository(this)
        mcpRepository = MCPRepository(this)
    }
}
"""


# ── batch-7 witnesses (mechanically reproduced on the real tree) ──────────
#
# Each one keeps every anchor the pre-batch arms looked for and moves only the
# VALUE / POSITION / REACHABILITY the arm now judges.

# W1 (08-1, dataflow): the policy call and the `preferredId` identifier both
# survive; the value is truncated to "", so the caller's resolved id is dropped
# exactly as with `preferredId.let { null }` — only the spelling is new.
BAD_SKILL_TAKE_ZERO = GOOD_SKILL.replace(
    "val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(name))",
    "val id = SkillRestorePolicy.resolveImportId(preferredId?.take(0), slugify(name))",
)

# W2 (08-2, dataflow): the publish moves out of the lock but the `= ` setter is
# no longer what publishes it — `tryEmit` lands in `.value` just the same.
BAD_SKILL_TRYEMIT_OUTSIDE_LOCK = GOOD_SKILL.replace(
    """            _skills.value = _skills.value.map { if (it.id == skillId) bumped else it }
        }
    }
}""",
    """            _skills.value = _skills.value.map { if (it.id == skillId) bumped else it }
        }
        _skills.tryEmit(emptyList())
    }
}""",
)

# W3 (08-3, family-hole): the shared decision, the CURRENT-global read and the
# DELETE all stay in place — the conjunction makes the branch unreachable, so
# every toggle that matches the global default pins a row forever.
BAD_SKILL_DEAD_OVERRIDE_GUARD = GOOD_SKILL.replace(
    "if (shouldDeleteSessionSkillOverride(globalEnabled, enabled)) {",
    "if (shouldDeleteSessionSkillOverride(globalEnabled, enabled) && false) {",
)

# W4 (08-4, dataflow): the hook is still invoked on the delete path — with a
# constant instead of the deleted id, so the cross-db cleanup targets nothing.
BAD_CHAT_CONST_HOOK = GOOD_CHAT.replace(
    """        dao.deleteSession(id)
        runCatching { onSessionDeleted?.invoke(id) }.onFailure { warn(it) }
        return sessionFiles?.deleteSessionFiles(id)""",
    """        dao.deleteSession(id)
        runCatching { onSessionDeleted?.invoke("") }.onFailure { warn(it) }
        return sessionFiles?.deleteSessionFiles(id)""",
)

# W5 (08-5, spelling→effect): the same destruction as `= ""`, built at runtime
# so no literal-shaped pattern can see it.
BAD_PROMOTE_CHARARRAY_BLANK = GOOD_PROMOTE.replace(
    """        val sid = ensureSession()
        if (sid.isEmpty()) return@launch""",
    """        val sid = ensureSession()
        if (sid.isEmpty()) return@launch
        _inputText.value = CharArray(0).concatToString()""",
)

# ── drawer delete plan fixtures (08-6 had no arm at all before this batch) ──
GOOD_DELETE_PLAN = '''
internal fun sessionDeletePlan(): List<SessionDeleteStep> = listOf(
    SessionDeleteStep.RELEASE_VM,
    SessionDeleteStep.DELETE_ROW,
    SessionDeleteStep.CLEAR_BADGE,
)
'''

BAD_DELETE_PLAN_REVERSED = GOOD_DELETE_PLAN.replace(
    "    SessionDeleteStep.CLEAR_BADGE,\n)", "    SessionDeleteStep.CLEAR_BADGE,\n).reversed()")

BAD_DELETE_PLAN_SWAPPED = GOOD_DELETE_PLAN.replace(
    """    SessionDeleteStep.RELEASE_VM,
    SessionDeleteStep.DELETE_ROW,""",
    """    SessionDeleteStep.DELETE_ROW,
    SessionDeleteStep.RELEASE_VM,""")

GOOD_DRAWER_DELETE = '''
internal fun deleteSessionAndCleanup(context: Context, id: String, scope: CoroutineScope) {
    scope.launch {
        try {
            for (step in sessionDeletePlan()) {
                when (step) {
                    SessionDeleteStep.RELEASE_VM -> ChatViewModelStore.release(id)
                    SessionDeleteStep.DELETE_ROW -> chatRepository.deleteSession(id)
                    SessionDeleteStep.CLEAR_BADGE -> SessionBadgeStore.clear(id)
                }
            }
        } catch (e: Throwable) {
            warn(e)
        } finally {
            ChatViewModelStore.release(id)
            SessionBadgeStore.clear(id)
        }
    }
}
'''

# The unconditional tail dropped: a throw now leaves the VM + badge behind.
BAD_DRAWER_NO_FINALLY = GOOD_DRAWER_DELETE.replace(
    """        } finally {
            ChatViewModelStore.release(id)
            SessionBadgeStore.clear(id)
        }""",
    """        }""")

_ARMS = []


def expect_trip(name, violations, needle, failures):
    _ARMS.append((name, needle))
    if not violations:
        failures.append("%s: no violation reported (gate is blind)" % name)
    elif not any(needle in v for v in violations):
        failures.append("%s: violations %r do not mention expected needle %r" % (name, violations, needle))


def selftest():
    failures = []

    good_skill = check_skill_repo(GOOD_SKILL)
    bad_slug = check_skill_repo(BAD_SKILL_RAW_SLUG)
    bad_drop = check_skill_repo(BAD_SKILL_DROP_ID)
    bad_bundled = check_skill_repo(BAD_SKILL_BUNDLED)
    bad_publish = check_skill_repo(BAD_SKILL_PUBLISH_OUTSIDE_LOCK)
    bad_nolock = check_skill_repo(BAD_SKILL_NO_LOCK)
    bad_nofunc = check_skill_repo(BAD_SKILL_DROP_FUNCTION)
    bad_pin = check_skill_repo(BAD_SKILL_PIN_OVERRIDE)

    # Precision control: a compliant fixture must be clean, or "everything is
    # red" would masquerade as teeth.
    if good_skill:
        failures.append("compliant skill fixture is not clean: %r" % good_skill)

    expect_trip("raw-slugify fixture (ISSUE-01)", bad_slug, "does not resolve", failures)
    expect_trip("raw-slugify fixture (ISSUE-01)", bad_slug, "does not consult preferredId", failures)
    expect_trip("dropped-id fixture (ISSUE-01)", bad_drop, "does not pass 'preferredId ='", failures)
    expect_trip("bundled-dirName fixture (ISSUE-01)", bad_bundled, "other than its", failures)
    # [da1029-C / I04]
    expect_trip("publish-outside-lock fixture (I04)", bad_publish, "publishes", failures)
    expect_trip("publish-outside-lock fixture (I04)", bad_publish, "out of the", failures)
    expect_trip("no-lock fixture (I04)", bad_nolock, "has no 'synchronized(addLock)'", failures)
    expect_trip("dropped-function fixture (I04)", bad_nofunc, "not found (ROT", failures)
    # [wide-1024] dataflow: every anchor of arm 1 holds, the value does not.
    bad_neutralised = check_skill_repo(BAD_SKILL_NEUTRALISED_ID)
    expect_trip("neutralised-id fixture (wide-1024 W1)", bad_neutralised, "NEUTRALISED", failures)
    if any("does not consult preferredId" in v or "does not resolve" in v
           for v in bad_neutralised):
        failures.append("neutralised-id fixture tripped an UNRELATED arm: %r" % bad_neutralised)
    bad_rmw = check_skill_repo(BAD_SKILL_RMW_OUTSIDE_LOCK)
    expect_trip("rmw-outside-lock fixture (wide-1024 W2)", bad_rmw, "OUTSIDE the", failures)
    if any("publishes" in v for v in bad_rmw):
        failures.append("rmw-outside-lock fixture tripped an UNRELATED arm: %r" % bad_rmw)
    # [third adversarial round 04-2] `false &&` on the replace-existing guard.
    bad_replace = check_skill_repo(BAD_SKILL_REPLACE_UNREACHABLE)
    expect_trip("replace-unreachable fixture (04-2)", bad_replace, "constant-false", failures)
    # [dv16-A05] the pinned-override pre-fix shape fires ONLY the new arm.
    expect_trip("pinned-override fixture (dv16-A05)", bad_pin, "does not DELETE the override row", failures)
    expect_trip("pinned-override fixture (dv16-A05)", bad_pin, "shouldDeleteSessionSkillOverride", failures)
    if any("does not resolve" in v or "publishes" in v or "has no 'synchronized" in v for v in bad_pin):
        failures.append("pinned-override fixture (dv16-A05) tripped an UNRELATED arm: %r" % bad_pin)

    good_chat = check_chat_repo(GOOD_CHAT)
    bad_chat = check_chat_repo(BAD_CHAT_NO_HOOK)
    expect_trip("unwired-hook fixture (ISSUE-10)", bad_chat, "unwired", failures)

    # [dv21-a2 / deep-audit-1041 A-02] the startup sweep as a THIRD entry point.
    # Precision control first: the wired fixture (now carrying the sweep) must be
    # clean, or "everything is red" would masquerade as teeth.
    if good_chat:
        failures.append("compliant chat fixture is not clean: %r" % good_chat)
    bad_sweep = check_chat_repo(BAD_CHAT_SWEEP_NO_HOOK)
    expect_trip("unhooked-sweep fixture (dv21-a2)", bad_sweep, "deleteEmptySessions", failures)
    expect_trip("unhooked-sweep fixture (dv21-a2)", bad_sweep, "unwired", failures)
    # The sweep fixture must fire ONLY the sweep arm — the other two entries are
    # still wired, so any extra violation means the gate is matching the wrong
    # body (e.g. back to the first-`}` slice).
    if len(bad_sweep) != 1:
        failures.append("unhooked-sweep fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_sweep), bad_sweep))

    # [third adversarial round 04-1, dual-gate with parts_json_funnel] the
    # sweep's survivor filter with its polarity inverted: the hook is wired, the
    # id is real, and the wrong sessions get notified.
    bad_polarity = check_chat_repo(BAD_CHAT_SURVIVOR_POLARITY)
    expect_trip("survivor-polarity fixture (04-1)", bad_polarity, "polarity is inverted", failures)
    if len(bad_polarity) != 1:
        failures.append("survivor-polarity fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_polarity), bad_polarity))

    # A fourth `sessions`-row delete entry point that nobody registered.
    bad_unregistered = check_chat_repo(BAD_CHAT_UNREGISTERED_ENTRY)
    expect_trip("unregistered-entry fixture (dv21-a2)", bad_unregistered, "not a registered",
                failures)

    # Delegation to one shared helper is a legitimate shape and must stay clean;
    # an EMPTY helper must not launder the missing cleanup.
    good_delegated = check_chat_repo(GOOD_CHAT_DELEGATED)
    if good_delegated:
        failures.append("delegating chat fixture is not clean: %r" % good_delegated)
    bad_helper = check_chat_repo(BAD_CHAT_HELPER_NO_HOOK)
    expect_trip("empty-helper fixture (dv21-a2)", bad_helper, "unwired", failures)

    good_app = check_minis_app(GOOD_APP)
    bad_app = check_minis_app(BAD_APP_ORDER)
    expect_trip("late-wiring fixture (ISSUE-10)", bad_app, "AFTER chatRepository", failures)

    # [dv20-A4-A05] the startup empty-session sweep. Precision control first: the
    # wired fixture must be clean, or "everything is red" would masquerade as teeth.
    good_sweep = check_minis_app_sweep(GOOD_APP_SWEEP)
    bad_sweep_unwired = check_minis_app_sweep(BAD_APP_SWEEP_UNWIRED)
    bad_sweep_no_live = check_minis_app_sweep(BAD_APP_SWEEP_NO_LIVE_SET)
    bad_sweep_on_main = check_minis_app_sweep(BAD_APP_SWEEP_ON_MAIN)
    if good_sweep:
        failures.append("compliant startup-sweep fixture is not clean: %r" % good_sweep)
    expect_trip("unwired startup sweep (dv20-A4-A05)", bad_sweep_unwired, "never runs", failures)
    expect_trip("sweep without the live-session set (dv20-A4-A05)", bad_sweep_no_live,
                "still live", failures)
    expect_trip("sweep off the IO region (dv20-A4-A05)", bad_sweep_on_main,
                "Room DELETE at cold start", failures)
    # The live-set arm must fire ONLY its own decision: the other two arms would
    # otherwise mask a regression that left the exclusion set unwired.
    if len(bad_sweep_no_live) != 1:
        failures.append("live-set fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_sweep_no_live), bad_sweep_no_live))

    # [da1030-A / I01 裂缝B]
    bad_badge = check_minis_app_badge(BAD_APP_NO_BADGE)
    bad_badge_elsewhere = check_minis_app_badge(BAD_APP_BADGE_ELSEWHERE)
    good_badge = check_minis_app_badge(GOOD_APP)
    if good_badge:
        failures.append("compliant MinisApp badge fixture is not clean: %r" % good_badge)
    expect_trip("no-badge-clear fixture (I01 裂缝B)", bad_badge, "does not call", failures)
    expect_trip("no-badge-clear fixture (I01 裂缝B)", bad_badge, "SessionBadgeStore.clear(",
                failures)
    expect_trip("badge-clear-outside-lambda fixture (I01 裂缝B)", bad_badge_elsewhere,
                "does not call", failures)

    # [da1030-A / I01] draft promotion re-points the WebApp shortcuts
    good_entry = check_chat_entry(GOOD_ENTRY)
    if good_entry:
        failures.append("compliant ChatSessionEntryExt fixture is not clean: %r" % good_entry)
    bad_entry = check_chat_entry(BAD_ENTRY_DROP_SHORTCUTS)
    bad_entry_outside = check_chat_entry(BAD_ENTRY_OUTSIDE_DRAFT)
    expect_trip("dropped-renameSessionShortcuts fixture (I01)", bad_entry,
                "renameSessionShortcuts(", failures)
    expect_trip("dropped-renameSessionShortcuts fixture (I01)", bad_entry,
                "Source Missing", failures)
    expect_trip("renameSessionShortcuts-outside-isDraft fixture (I01)", bad_entry_outside,
                "does not call", failures)

    print("selftest compliant skill repo    -> %d violation(s)" % len(good_skill))
    print("selftest raw-slugify skill repo  -> %d violation(s)" % len(bad_slug))
    print("selftest dropped-id skill repo   -> %d violation(s)" % len(bad_drop))
    print("selftest bundled-dirName repo    -> %d violation(s)" % len(bad_bundled))
    print("selftest publish-outside-lock    -> %d violation(s)" % len(bad_publish))
    print("selftest no-lock recordSkillUse  -> %d violation(s)" % len(bad_nolock))
    print("selftest recordSkillUse removed  -> %d violation(s)" % len(bad_nofunc))
    print("selftest pinned override         -> %d violation(s)" % len(bad_pin))
    print("selftest compliant chat repo     -> %d violation(s)" % len(good_chat))
    print("selftest unwired-hook chat repo  -> %d violation(s)" % len(bad_chat))
    print("selftest unhooked sweep          -> %d violation(s)" % len(bad_sweep))
    print("selftest unregistered entry      -> %d violation(s)" % len(bad_unregistered))
    print("selftest delegating chat repo    -> %d violation(s)" % len(good_delegated))
    print("selftest empty helper            -> %d violation(s)" % len(bad_helper))
    print("selftest compliant MinisApp      -> %d violation(s)" % len(good_app))
    print("selftest late-wiring MinisApp    -> %d violation(s)" % len(bad_app))
    print("selftest MinisApp badge kept     -> %d violation(s)" % len(bad_badge))
    print("selftest MinisApp badge elsewhere-> %d violation(s)" % len(bad_badge_elsewhere))
    print("selftest compliant entry ext     -> %d violation(s)" % len(good_entry))
    print("selftest dropped webapp re-point -> %d violation(s)" % len(bad_entry))
    print("selftest re-point outside draft  -> %d violation(s)" % len(bad_entry_outside))
    print("selftest compliant sweep wiring    -> %d violation(s)" % len(good_sweep))
    print("selftest unwired startup sweep     -> %d violation(s)" % len(bad_sweep_unwired))
    print("selftest sweep without live set    -> %d violation(s)" % len(bad_sweep_no_live))
    print("selftest sweep off the IO region   -> %d violation(s)" % len(bad_sweep_on_main))

    # [dv24-b3 / deep-audit-1044 B-06] draft promotion persists the BODY.
    good_promote = check_promote_draft(GOOD_PROMOTE)
    if good_promote:
        failures.append("compliant promoteDraftIfNeeded fixture is not clean: %r" % good_promote)
    bad_title_only = check_promote_draft(BAD_PROMOTE_TITLE_ONLY)
    expect_trip("title-only promotion (B-06)", bad_title_only, "never persists the captured draft text",
                failures)
    # The pre-fix shape must fire ONLY C1: with no durable write there is no
    # ordering to judge, so C2 stays quiet and the arms stay attributable.
    if len(bad_title_only) != 1:
        failures.append("title-only promotion fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_title_only), bad_title_only))
    bad_blank_first = check_promote_draft(BAD_PROMOTE_BLANK_FIRST)
    expect_trip("blank-before-persist promotion (B-06)", bad_blank_first, "BEFORE the durable body write",
                failures)
    if len(bad_blank_first) != 1:
        failures.append("blank-before-persist fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_blank_first), bad_blank_first))
    bad_string_blank = check_promote_draft(BAD_PROMOTE_STRING_BLANK)
    expect_trip("String()-blank promotion (wide-1024 W3)", bad_string_blank,
                "BEFORE the durable body write", failures)
    if len(bad_string_blank) != 1:
        failures.append("String()-blank fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_string_blank), bad_string_blank))
    bad_persist_early = check_promote_draft(BAD_PROMOTE_PERSIST_BEFORE_ENSURE)
    expect_trip("persist-before-ensure promotion (B-06)", bad_persist_early, "BEFORE 'ensureSession('",
                failures)

    # [third adversarial round 04-3, symmetric axis] the success path must flip
    # the flag its `finally` consults.
    good_flag = check_promote_persisted(structure(PROMOTE_PERSISTED_GOOD), PROMOTE_PERSISTED_GOOD)
    if good_flag:
        failures.append("compliant persisted-flag fixture is not clean: %r" % good_flag)
    bad_flag = check_promote_persisted(structure(BAD_PROMOTE_FLAG_NOT_FLIPPED),
                                       BAD_PROMOTE_FLAG_NOT_FLIPPED)
    expect_trip("flag-not-flipped promotion (04-3)", bad_flag, "writes `persisted = false`",
                failures)
    if len(bad_flag) != 1:
        failures.append("flag-not-flipped fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_flag), bad_flag))

    # [dv25-B08 / deep-audit-1045 B-08] the promotion owns its session.
    good_own = check_promote_ownership(GOOD_VM, GOOD_PROMOTE_OWNED)
    if good_own:
        failures.append("compliant promotion-ownership fixture is not clean: %r" % good_own)
    bad_no_owner = check_promote_ownership(BAD_VM_NO_OWNER, GOOD_PROMOTE_OWNED)
    expect_trip("sweep without the promotion ownership bit (B-08)", bad_no_owner,
                "never consults", failures)
    # Precision: the ownership arm must fire ONLY its own criterion, so a
    # regression in the predicate arm cannot hide behind it.
    if len(bad_no_owner) != 1:
        failures.append("no-ownership-bit fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_no_owner), bad_no_owner))
    bad_no_predicate = check_promote_ownership(BAD_VM_NO_PREDICATE, GOOD_PROMOTE_OWNED)
    expect_trip("sweep without the shared predicate (B-08)", bad_no_predicate,
                "does not route its decision", failures)
    if len(bad_no_predicate) != 1:
        failures.append("no-predicate fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_no_predicate), bad_no_predicate))
    bad_no_owner_at_all = check_promote_ownership(BAD_VM_NO_OWNER_AT_ALL, GOOD_PROMOTE_OWNED)
    expect_trip("sweep without any ownership guard (B-08)", bad_no_owner_at_all,
                "does not route its decision", failures)
    expect_trip("sweep without any ownership guard (B-08)", bad_no_owner_at_all,
                "never consults", failures)
    bad_promote_no_owner = check_promote_ownership(GOOD_VM, BAD_PROMOTE_NO_OWNER)
    expect_trip("promotion that never claims the session (B-08)", bad_promote_no_owner,
                "never claims the session", failures)
    bad_promote_no_release = check_promote_ownership(GOOD_VM, BAD_PROMOTE_NO_RELEASE)
    expect_trip("promotion that never releases the claim (B-08)", bad_promote_no_release,
                "never releases", failures)
    if len(bad_promote_no_release) != 1:
        failures.append("no-release promotion fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_promote_no_release), bad_promote_no_release))
    bad_promote_no_restore = check_promote_ownership(GOOD_VM, BAD_PROMOTE_NO_RESTORE)
    expect_trip("promotion that drops the only copy (B-08)", bad_promote_no_restore,
                "does not write the text back", failures)
    # Precision: the "no owner bit" arm must fire ONLY its own criterion — the
    # predicate is still routed, so a mask that trips both would hide a
    # regression in either one.
    if len(bad_promote_no_owner) != 1:
        failures.append("no-claim promotion fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_promote_no_owner), bad_promote_no_owner))

    print("selftest compliant promotion ownership -> %d violation(s)" % len(good_own))
    print("selftest sweep without ownership bit   -> %d violation(s)" % len(bad_no_owner))
    print("selftest sweep without shared predicate -> %d violation(s)" % len(bad_no_predicate))
    print("selftest sweep without any guard       -> %d violation(s)" % len(bad_no_owner_at_all))
    print("selftest promotion without claim        -> %d violation(s)" % len(bad_promote_no_owner))
    print("selftest promotion without release      -> %d violation(s)" % len(bad_promote_no_release))
    print("selftest promotion without restore      -> %d violation(s)" % len(bad_promote_no_restore))

    # [dv24-b3] drawer visibility treats a preview as content.
    good_drawer = check_drawer_visibility(GOOD_DRAWER)
    if good_drawer:
        failures.append("compliant drawer visibility fixture is not clean: %r" % good_drawer)
    bad_inline = check_drawer_visibility(BAD_DRAWER_INLINE_FILTER)
    expect_trip("inline drawer filter (B-06)", bad_inline, "does not route through", failures)
    bad_no_count = check_drawer_visibility(BAD_DRAWER_NO_COUNT)
    expect_trip("drawer predicate without messageCount (B-06)", bad_no_count, "does not consult 'messageCount'",
                failures)
    if len(bad_no_count) != 1:
        failures.append("no-count drawer fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_no_count), bad_no_count))
    bad_any_non_null = check_drawer_visibility(BAD_DRAWER_ANY_NON_NULL)
    expect_trip("drawer predicate accepting any non-null preview (B-06)", bad_any_non_null,
                "accepts any non-null preview", failures)
    if len(bad_any_non_null) != 1:
        failures.append("any-non-null drawer fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_any_non_null), bad_any_non_null))

    print("selftest compliant promote draft  -> %d violation(s)" % len(good_promote))
    print("selftest title-only promotion     -> %d violation(s)" % len(bad_title_only))
    print("selftest blank-before-persist     -> %d violation(s)" % len(bad_blank_first))
    print("selftest persist-before-ensure    -> %d violation(s)" % len(bad_persist_early))
    print("selftest compliant drawer filter  -> %d violation(s)" % len(good_drawer))
    print("selftest inline drawer filter      -> %d violation(s)" % len(bad_inline))
    print("selftest drawer without count      -> %d violation(s)" % len(bad_no_count))
    print("selftest drawer any-non-null       -> %d violation(s)" % len(bad_any_non_null))

    # ── batch-7 witnesses: the six shapes that walked through the pre-batch
    # arms. Each keeps the anchors, and moves only the value / position /
    # reachability the arm now judges.
    bad_take_zero = check_skill_repo(BAD_SKILL_TAKE_ZERO)
    expect_trip("08-1 preferredId?.take(0) (dataflow)", bad_take_zero, "NEUTRALISED", failures)
    if any("does not consult preferredId" in v or "does not resolve" in v
           for v in bad_take_zero):
        failures.append("08-1 tripped an UNRELATED arm: %r" % bad_take_zero)

    bad_tryemit = check_skill_repo(BAD_SKILL_TRYEMIT_OUTSIDE_LOCK)
    expect_trip("08-2 tryEmit outside the lock (dataflow)", bad_tryemit, "out of the", failures)
    # Precision: the `= ` setter is still inside the lock, so only the new
    # "any write form" arm may fire.
    if any("publishes" in v for v in bad_tryemit):
        failures.append("08-2 tripped the setter-only arm: %r" % bad_tryemit)

    bad_dead_guard = check_skill_repo(BAD_SKILL_DEAD_OVERRIDE_GUARD)
    expect_trip("08-3 dead sparse-clear guard (family-hole)", bad_dead_guard, "DEAD", failures)

    bad_const_hook = check_chat_repo(BAD_CHAT_CONST_HOOK)
    expect_trip("08-4 constant id into onSessionDeleted (dataflow)", bad_const_hook,
                "CONSTANT", failures)
    if len(bad_const_hook) != 1:
        failures.append("08-4 fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_const_hook), bad_const_hook))

    bad_chararray = check_promote_draft(BAD_PROMOTE_CHARARRAY_BLANK)
    expect_trip("08-5 CharArray(0) composer blank (spelling→effect)", bad_chararray,
                "BEFORE the durable body write", failures)
    if len(bad_chararray) != 1:
        failures.append("08-5 fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_chararray), bad_chararray))

    # 08-6 (order): the plan is what runs, so `listOf(…).reversed()` keeps the
    # literal argument order the old slice read while flipping the executed one.
    good_delete_order = check_drawer_delete_order(GOOD_DRAWER_DELETE, GOOD_DELETE_PLAN)
    if good_delete_order:
        failures.append("compliant drawer delete order is not clean: %r" % good_delete_order)
    bad_reversed = check_drawer_delete_order(GOOD_DRAWER_DELETE, BAD_DELETE_PLAN_REVERSED)
    expect_trip("08-6 plan reversed (order)", bad_reversed, "deleted BEFORE", failures)
    if len(bad_reversed) != 1:
        failures.append("08-6 reversed fixture tripped %d arms, expected exactly 1: %r"
                        % (len(bad_reversed), bad_reversed))
    # Control: the same defect spelled by hand in the literal order stays caught.
    bad_swapped = check_drawer_delete_order(GOOD_DRAWER_DELETE, BAD_DELETE_PLAN_SWAPPED)
    expect_trip("08-6 control: literal order swapped (order)", bad_swapped,
                "deleted BEFORE", failures)
    bad_no_finally = check_drawer_delete_order(BAD_DRAWER_NO_FINALLY, GOOD_DELETE_PLAN)
    expect_trip("C-DEL-2 unconditional tail dropped", bad_no_finally, "'finally'", failures)

    print("selftest 08-1 take(0) preferredId    -> %d violation(s)" % len(bad_take_zero))
    print("selftest 08-2 tryEmit outside lock   -> %d violation(s)" % len(bad_tryemit))
    print("selftest 08-3 dead sparse-clear      -> %d violation(s)" % len(bad_dead_guard))
    print("selftest 08-4 constant session id    -> %d violation(s)" % len(bad_const_hook))
    print("selftest 08-5 CharArray(0) blank     -> %d violation(s)" % len(bad_chararray))
    print("selftest 08-6 plan reversed          -> %d violation(s)" % len(bad_reversed))
    print("selftest 08-6 literal order swapped  -> %d violation(s)" % len(bad_swapped))
    print("selftest C-DEL-2 finally dropped     -> %d violation(s)" % len(bad_no_finally))

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("SKILL_RESTORE_SESSION_CLEANUP_SELFTEST=PASS")
    print("SKILL_RESTORE_SESSION_CLEANUP_SELFTEST_ARMS=%d" % len(set(_ARMS)))
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
