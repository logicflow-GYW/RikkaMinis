#!/usr/bin/env python3
"""provider_remember_key_check.py — deep-audit-1025 ISSUE-06 [da1025-F]
                                        + deep-audit-1026 ISSUE-03 [da1026-C]
                                        + deep-audit-1027 ISSUE-02/08 [da1027-E]

Gates the Compose remember-key shape in the provider settings screens so
derived values always list their derivation sources as remember keys
(stale-snapshot guard), and so an async callback never reads a pre-refresh
snapshot.

Criteria (each fail-closed: missing anchor = RED, never silent skip):
  C1  ProviderListScreen  `val providerRows = remember(`   keys must contain
      both `instances` and `modelEntries` (refreshModels changes modelEntries
      without changing instances — missing key freezes modelCount at 0).
  C2  ProviderListScreen  `val reorderableRows = remember(` keys must contain
      `providerRows` (it derives from providerRows, not instances).
  C3  ManageProviderModelsSheet `val instance = remember(` keys must contain
      both `instanceId` and `config` (deleted provider must become null so
      the auto-dismiss guard fires).
  C4  ProviderListScreen  every `onTogglePinned` line: either no `remember(`
      at all, or its keys contain `pinned` (stale-captured pinned guard).
  C5  ProviderConnectionScreen every `rememberSaveable(` call: keyless is RED,
      and every `instance.<field>` it reads must appear in its own key list
      (with only `instance.id` as key, an external write to customBaseURL /
      customUserAgent left the stale text on screen and the focus-loss save
      wrote it back). Anchors: the file must keep one call keyed on
      `instance.customBaseURL` and one on `instance.customUserAgent` — if the
      screen is reworked so those anchors disappear, this gate goes RED on
      purpose and must be re-derived.
  C6  ModelEntryDetailScreen: zero keyless `remember { ... }` in the file
      (a keyless form freezes the first composition's values for the whole
      visit — external RPC / backup-restore edits stayed invisible and Save
      wrote the stale values back).
  C6c ModelEntryDetailScreen: any `remember(...)` whose call site reads a
      config-derived value (`entry.` / `baseModel.` / `overrides.` /
      `effectiveInput` / `effectiveOutput`) must key on `entry.id` or
      `entryId` — a key that never changes (e.g. the screen's own `entryId`
      argument) re-introduces the same freeze while looking keyed.
  C7  ProviderDetailScreen: the refresh toast must report a count read AFTER
      the refresh. C7a: the `refreshResultMessage(` call site exists and is
      downstream of the `refreshModels(` call. C7b: its arguments must not
      read the pre-refresh `allEntries` snapshot unless that value was
      re-assigned on the refresh path. C7c: the refresh path must contain a
      fresh `entriesFor(` read (or an assignment to a mutableStateOf-backed
      `allEntries`).
  C8  ModelGroupDetailScreen: every `remember(...)` whose initializer reads a
      `group.<field>` must key on that exact `group.<field>` (with only
      `group.id` as key an external write — minis-config / Debug RPC / backup
      restore — left the old text on screen and the name field's focus-loss
      save wrote it straight back). Anchor: at least one group-derived call
      must exist, else RED (fail-closed — re-derive if the screen is reworked).
  C9  MountDetailScreen: same shape for `entry.<field>` — `canSave` compares
      the edit buffer against the entry, so a field-keyed remember is what
      keeps an external write from inverting the Save button.
  C10 AppNavigation / UsageStatsScreen — the usage page must observe config
      changes. C10a: the `Routes.USAGE_STATS` route block must NOT pass
      `providerConfig = <...>.value` (a one-shot StateFlow snapshot) and must
      contain a `collectAsState()` read. C10b: the `LaunchedEffect(...)` whose
      body calls `computeUsageStats(` must key on both `range` and
      `providerConfig`.
  C11 ThinkingRulesSection [da1028-C] — the two derived values that decide
      what the section shows must be keyed on every source they are derived
      from. C11a: the `remember(...)` whose initializer calls
      `builtInThinkingRulesForDisplay(` must key on `customBaseURL`,
      `appendV1Suffix` (both feed `effectiveBaseURL` inside the repo call) and
      `modelEntries`. C11b: the `remember(...)` calling `firstModelId(` must key
      on `modelEntries`. C11c: the ProviderDetailScreen call site must forward
      `modelEntries = ...` — a section that is never handed the list cannot key
      on it at all. Anchors missing = RED.

  C12 ThinkingRulesSection [da1030-C ISSUE-05] — the custom-rule loader must
      also re-read after writes made OUTSIDE this screen (backup restore,
      repository load, worker restore, `minis-config`). `reloadKey` only moves
      for edits made on the screen itself, so the screen kept rendering the
      list it read when it opened and wrote it back on the next edit —
      reverting the external write. Two arms: (a) the file must subscribe to
      `customRulesRevision` via `collectAsState()`; (b) the `remember(...)`
      whose initializer calls `thinkingRulesWithIds(` must key on that
      revision (allowlist `rulesRevision` / `customRulesRevision`).
      Anchors missing = RED (re-derive rather than delete).

  C13 ThinkingRuleResolver [da1030-C ISSUE-05] — the revision C12 observes is
      only as good as its writers: `setAllCustomRules`, `setCustomRules` and
      `restoreCustomRulesFromJson` must EACH bump `_customRulesRevision`
      (a mutator that skips the bump re-opens the stale-screen hole for
      exactly that write path), and the file-level count of cache writes
      (`customRulesCache = …`) must not exceed the count of bumps — a body with
      TWO write paths (the null / non-null branches of the restore) needs a
      bump on each, which a per-body "≥1 bump" test alone cannot see. The
      `val customRulesRevision` declaration must exist. Fail-closed on a
      missing mutator body.

  C14 ThinkingRuleEditor [da1033-I07] — the dialog's nine form states are seeded
      from `seed`, so each `remember(...)` whose initializer reads a `seed.<field>`
      must carry a key that can tell one seed from another. Without it an external
      write (minis-config / Debug RPC / backup restore) left the dialog on the copy
      it opened with and Save wrote that copy back over the newer rule.
      Graded on the DECISION ("does this form state re-initialise when the rule it
      was seeded from changes"), not the spelling: `remember(seed)` and
      `remember(request.existingId, seed.wireFormat)` both count, a key that never
      moves with the seed (`remember(request.existingId)`) does not. Only the
      initializer counts as a read — counting the key list would be tautological.
      Fail-closed when no remember derives from `seed.` at all (editor reworked).

  C15 ThinkingRulesSection [da1033-I07] — the dialog must be handed the rule as it
      is NOW, not the snapshot `editorRequest` captured when the row was tapped.
      Two arms: (a) the call's `request` argument is seeded from the LIVE columns
      (`customRules` / `customIds`) and that derivation is selected by the captured
      `existingId` — inline, or through a local declared for it; handing over the
      raw captured request is what reverted the external write. (b) a
      `LaunchedEffect` closes the dialog when the resolution says the rule is gone
      (state must not be written during composition). New-rule paths (`isNew` / `existingId == null`) seed from
      the request itself and must NOT be flagged.

  C16 EnvironmentVariablesScreen [da1034-B4] — the Add/Edit sheet is opened for an
      ID (`editEntryId`), and the target is re-resolved from the LIVE list every
      composition. When the entry is deleted externally (`minis-config remove
      envvars.<KEY>` / backup restore) the lookup yields null while `editEntryId`
      stays non-null, so `EnvVarFormSheet` silently drops to new-variable semantics
      (`isEditing == false`) and Save creates a NEW entry under the key the user
      believes they are editing. The derivation must therefore be paired with a
      close effect: a `LaunchedEffect` keyed on BOTH `editEntryId` and the list it
      resolves against (`entries`), which resets `editEntryId` when the target is
      gone. Graded on the DECISION (does it close ONLY when the target vanished),
      not the spelling: an inline existence test (`entries.none { … }`,
      `indexOfFirst(…) < 0`) counts, and so does a call to a file-local helper
      whose own body carries such a test (the helper is the decision point, kept
      pure so a JVM test can run it for real) — the same "decision, not spelling"
      rule as C14/C15. Closing unconditionally does NOT count (that would dismiss
      the sheet on any recomposition), and neither does the neighbouring deep-link
      effect, which also assigns `editEntryId = null` but derives from the pending
      request. Fail-closed when the edit gate / the resolving derivation is gone.

  C17 WebDavConfigDialog [da1034-B4] — the four form states are seeded from
      `initial`, which the screen fills in ASYNCHRONOUSLY (`LaunchedEffect` →
      `webDavStore.load()`, first frame `null` — [deep-audit-1033 I08]). A keyless
      `remember` froze the boxes at the first composition's value, so opening the
      dialog in the null window showed four empty fields and Save wrote those
      empties back over the real config. Every `remember(...)` inside the dialog
      whose initializer reads `initial?.<field>` must key on `initial`; states that
      derive from nothing (`testing` / `testResult` / `confirmClearPassword`) stay
      keyless and are NOT graded. Same "initializer read" rule as C14 (counting the
      key list would be tautological). Fail-closed when the dialog is gone or no
      state derives from `initial.` any more (re-derive rather than delete).

  C20/C21 AddCustomModelScreen [da1039-B3 B-03] — the same母题 one level up:
      the READ half of a stale snapshot. `providerRepository.instance(id)` is a
      one-shot `_config.value.instances.find { … }` read, so an instance deleted
      elsewhere (minis-config RPC / Debug RPC / backup restore / another screen)
      never recomposed this page, its `instance == null` guard never fired, and
      Save persisted a ModelEntry whose `providerInstanceId` no longer exists
      (`addEntry` checks duplicates only). C20 = subscribe to
      `providerRepository.config.collectAsState()` and derive the instance from
      `config.instances` (the one-shot read must be gone). C21 = the save path
      must re-check the parent against the LIVE list before the `addEntry` call,
      because the delete can land between the click and the IO hop.

  C22/C23 ProviderConnectionScreen [da1039-B3 B-04] — the same母题 on the WRITE
      side. `instance` is the composition-time snapshot, so every
      `updateInstance(instance.copy(…))` pushed the whole record back: a
      focus-loss save that ran after a toggle (or after an external write)
      silently reverted the newer fields, and the first-frame
      `onFocusChanged(isFocused = false)` Compose delivers on mount made every
      visit write twice. C22 = a live resolver (`fun currentInstance()` reading
      `config.instances`) must exist and EVERY `providerRepository.updateInstance`
      must go through it, directly or via a local assigned from it (decision, not
      spelling — the snapshot arg is reported by line). C23 = both focus-loss
      savers must route through the pure `pendingConnectionFieldWrite` decision
      (normalise blank → null, skip when unchanged) and the base-URL saver must
      no longer rewrite `customUserAgent` in the same copy.

  C24/C25 the Quick Test target [da1039-B3 B-08] — graded on BOTH screens that
      host the shared `modelEntryPickerItems` (AddModelsToGroupScreen and the
      agent-loop add sheet), so the sibling that never wired the channel cannot
      stay silently different. C24 = the target is held as a `String?` id, the
      picker wiring stores `it.id` (identity, not the object tapped), and the
      sheet is hosted with an `onDismiss` that clears the same state. C25 = a
      `LaunchedEffect` keyed on BOTH the pending id and the list it resolves
      against, whose body calls the vanished test and clears the state; closing
      unconditionally does NOT count (same rule as C16). A frozen `ModelEntry`
      kept feeding "Run again" the config the row had at tap time, and a deleted
      model left the sheet open against a row that no longer exists.

  C30 ProviderListScreen [deep-audit-1041 B-01] — the credential LIVE SOURCE, the
      read half the C26..C29 family left open. The API key is NOT a
      `ProviderInstance` field (EncryptedSharedPreferences), so no config field
      carries it and no plain field key observes an external write; the signal is
      `config.revision`. C30a = no `remember` initializer may call `loadApiKey()`
      (blocking keystore I/O on the composition thread — the pre-fix shape). C30b
      = a producer effect keyed on the instance list AND a revision token, reading
      through an IO hop, whose result the rows derive from. Fail-closed when the
      credential read is gone from the file.

  C31 ProviderConnectionScreen [deep-audit-1041 B-01] — the same decision C29
      grades on ProviderDetailScreen, on the screen the earlier fix missed. A
      stale snapshot here rolls the credential BACK: pressing Edit seeds the
      editor from `storedKey`, so the next Save writes the old key over the one an
      external writer just landed. Fail-closed when the read is gone.

  C32 the model picker's section collapse [deep-audit-1041 B-04] — graded on BOTH
      the chat sheet and the shared `ModelEntryPicker`, so the private copy that
      drifted cannot stay silently different. `collapsedInstanceIds` seeds to
      every instance, so a section that ignores the query renders one summary row
      and hides the rest of the hits behind a chevron. Two accepted spellings: the
      conjunctive gate (`<searchBox>.isEmpty() && …contains(id)` — polarity
      graded: `… && isNotEmpty()` is a red) and a derived set yielding `emptySet()`
      while searching. Fail-closed when the render decision is gone.

Usage:
  python3 provider_remember_key_check.py <repo_root>   # real-tree mode
  python3 provider_remember_key_check.py --selftest    # fixture mode

Exit codes: 0 = all criteria green, 1 = any criterion red / anchor missing.
Selftest ends with the tamper marker PROVIDER_REMEMBER_SELFTEST=PASS only
when every fixture asserts exactly as expected (rc AND the failed-criteria
set — a fixture that reds for the wrong reason is a MISMATCH, not a pass).
"""

import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# [batch5] Shared Kotlin primitives. This gate used to carry its own lexer /
# brace scanner / pass-through peeler; the batch-5 sweep proved each of those
# copies blind in a different way (a `}` inside a string truncated a body, a
# dead `false &&` guard passed as a guard, `.also { live }` counted as the live
# value). Structure now goes through one shared implementation only.
import _kotlin_ast as kast                                              # noqa: E402
import _kotlin_lexer as klex                                            # noqa: E402
import _kotlin_valueflow as kflow                                       # noqa: E402
from _kotlin_ast import (                                              # noqa: E402
    const_bool,
    function_body,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments      # noqa: E402
from _kotlin_valueflow import (                                         # noqa: E402
    contains_ident,
    guard_feasible,
    path_feasible,
    peel_discarding,
    returns_exiting,
    value_sources,
)

REL_PROVIDER_LIST = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ProviderListScreen.kt"
REL_SHEET = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ManageProviderModelsSheet.kt"
REL_PROVIDER_DETAIL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ProviderDetailScreen.kt"
REL_CONNECTION = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ProviderConnectionScreen.kt"
REL_ENTRY_DETAIL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelEntryDetailScreen.kt"
# da1027-E
REL_GROUP_DETAIL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelGroupDetailScreen.kt"
REL_MOUNT_DETAIL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MountDetailScreen.kt"
REL_APP_NAV = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
REL_USAGE_STATS = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/UsageStatsScreen.kt"
# da1028-C
REL_THINKING_SECTION = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ThinkingRulesSection.kt"
# da1030-C
REL_THINKING_RESOLVER = "src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/ThinkingRuleResolver.kt"
# da1033-E (I06 / I07)
REL_THINKING_EDITOR = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ThinkingRuleEditor.kt"
# da1034-B4 (B-07 / B-08)
REL_ENVVAR_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/EnvironmentVariablesScreen.kt"
REL_BACKUP_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt"
# da1034-B4 (B-05 / B-06)
REL_ADD_MODELS = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/AddModelsToGroupScreen.kt"
REL_AGENT_LOOP_SHEETS = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/AgentLoopAddSheets.kt"
REL_MODEL_ENTRY_PICKER = "src/android/app/src/main/java/com/rikkaminis/app/ui/components/ModelEntryPicker.kt"
REL_MODEL_PICKER_SHEET = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatModelPickerSheet.kt"
# [dv22/B-03] the remaining capability-reading surfaces
REL_QUICK_TEST = "src/android/app/src/main/java/com/rikkaminis/app/ui/components/QuickTestSheet.kt"
REL_CHAT_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
# [deep-audit-1043 B-06] the group surfaces that resolve memberEntryIds for DISPLAY
REL_MODEL_GROUPS = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelGroupsScreen.kt"
REL_AGENT_LOOP_MODELS = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/AgentLoopModelsScreen.kt"
# da1039-B3 (B-03 / B-04 / B-08): the settings screens that read a config object
# once and then write through it.
REL_ADD_CUSTOM_MODEL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/AddCustomModelScreen.kt"

# ---- [da1030-C ISSUE-05] C12 / C13 ----
# The collected revision local may be named either way; both spellings are the
# same single source (`ThinkingRuleResolver.customRulesRevision`).
REVISION_KEY_TOKENS = ("rulesRevision", "customRulesRevision")
REVISION_SUB_RE = re.compile(r"customRulesRevision[\s\S]{0,160}?collectAsState\s*\(")
THINKING_LOADER_CALLEE = "thinkingRulesWithIds("
RESOLVER_MUTATORS = ("setAllCustomRules", "setCustomRules", "restoreCustomRulesFromJson")
RESOLVER_REVISION_DECL_RE = re.compile(r"\bval\s+customRulesRevision\b")
RESOLVER_BUMP_RE = re.compile(r"customRulesRevision\.value\s*\+=")
# C14 — the seed-derived form states of ThinkingRuleEditorDialog.
SEED_FIELD_RE = re.compile(r"\bseed\.[A-Za-z_][A-Za-z0-9_]*")

# C15 — live-seed resolution in ThinkingRulesSection.
DIALOG_CALLEE = "ThinkingRuleEditorDialog"  # no paren: find_call_site appends one
# `seed = <local>` inside the dialog call — the local must read the live columns.
HOISTED_SEED_RE = re.compile(r"\bseed\s*=\s*([A-Za-z_][A-Za-z0-9_]*)")
# (c) the close-on-vanished effect and the test that drives it.
CLOSE_EFFECT_RE = re.compile(
    r"LaunchedEffect\s*\([\s\S]{0,300}?\)[\s\S]{0,120}?\{[\s\S]{0,900}?editorRequest\s*=\s*null")
VANISHED_TEST_RE = re.compile(r"<\s*0|==\s*-1|!in\b|==\s*null|isEmpty\(\)|isNotEmpty\(\)")
# A cache WRITE (`customRulesCache = …`), distinct from the field declaration
# (`private var customRulesCache: Map<…> = …`) and from passing it as an arg.
CACHE_WRITE_RE = re.compile(r"\bcustomRulesCache\s*=\s*[A-Za-z_]")

# ---- [da1039-B3] C20..C25: the write-path half of the same母题 -------------
# C20/C21 — AddCustomModelScreen reads its PARENT INSTANCE. `providerRepository
# .instance(id)` is a one-shot snapshot (`_config.value.instances.find { … }`):
# the page never recomposed when the instance was removed externally, so the
# `instance == null` guard never fired and Save minted an orphan ModelEntry.
ADD_CUSTOM_SUBSCRIPTION_RE = re.compile(
    r"providerRepository\s*\.\s*config\s*\.\s*collectAsState\s*\(")
ADD_CUSTOM_DERIVE_RE = re.compile(
    r"\bconfig\s*\.\s*instances\s*\.\s*(?:find|firstOrNull)\s*\{")
ONE_SHOT_INSTANCE_RE = re.compile(r"\bproviderRepository\s*\.\s*instance\s*\(")
# the save path re-checks the parent against the LIVE list before it writes
ADD_CUSTOM_LIVE_GUARD_RE = re.compile(
    r"(?:config\s*\.\s*value\s*\.\s*instances|\.instances\b)[\s\S]{0,160}?"
    r"\.(?:any|none|indexOfFirst)\s*\{[\s\S]{0,200}?instanceId")
ADD_ENTRY_CALL_RE = re.compile(r"\bproviderRepository\s*\.\s*addEntry\s*\(")

# C22/C23 — ProviderConnectionScreen writes back the composition-time snapshot.
LIVE_RESOLVER_NAME = "currentInstance"
LIVE_RESOLVER_DECL_RE = lambda name: re.compile(r"\bfun\s+" + re.escape(name) + r"\s*\(\s*\)")
LIVE_RESOLVER_READ_RE = re.compile(
    r"config\s*\.\s*instances\s*\.\s*(?:find|firstOrNull)\s*\{")
LIVE_LOCAL_RE = lambda name: re.compile(
    r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*" + re.escape(name) + r"\(\s*\)")
UPDATE_INSTANCE_RE = re.compile(r"\bproviderRepository\s*\.\s*updateInstance\s*\(")
# the pure decision the two focus-loss savers route through
DIRTY_DECISION_NAME = "pendingConnectionFieldWrite"
DIRTY_DECISION_BODY_RE = re.compile(r"ifBlank\s*\{[\s\S]{0,80}?\}[\s\S]{0,120}?stored")
DIRTY_SKIP_WRITE_RE = re.compile(
    r"\?:\s*return\b|if\s*\([\s\S]{0,60}?==\s*null\s*\)\s*\{?\s*return\b")
SAVER_NAMES = ("saveBaseURLSettings", "saveUserAgentSetting")

# C24/C25 — the Quick Test target must survive an external write/delete.
QUICK_TEST_ID_RE = re.compile(
    r"\bvar\s+quickTestEntryId\b[\s\S]{0,160}?mutableStateOf\s*<\s*String\?\s*>")
QUICK_TEST_RESOLVER_NAME = "liveQuickTestEntry"
QUICK_TEST_VANISHED_NAME = "quickTestTargetVanished"
QUICK_TEST_WIRING_RE = re.compile(
    r"onQuickTest\s*=\s*\{[\s\S]{0,200}?quickTestEntryId\s*=\s*it\.id")
QUICK_TEST_SHEET_RE = re.compile(r"\bQuickTestSheet\s*\(")
QUICK_TEST_DISMISS_RE = re.compile(r"onDismiss\s*=\s*\{[\s\S]{0,80}?quickTestEntryId\s*=\s*null")

# ---- [deep-audit-1040 B-07] C26..C29: the same母题 on ProviderDetailScreen ---
# The screen derives `instance` per frame for RENDERING; reusing it for WRITES
# pushed the whole record back, so a write landing after an external edit
# (minis-config / Debug RPC / backup restore) silently reverted the newer
# fields. Same single source as ProviderConnectionScreen's `currentInstance()`
# ([da1039-B3 B-04]) — hence the same helper constants (LIVE_RESOLVER_NAME,
# LIVE_RESOLVER_READ_RE, LIVE_LOCAL_RE, UPDATE_INSTANCE_RE, _resolver_window).
PD_EXPORT_RE = re.compile(r"\bexportProviderInstance\s*\(")
# C27 — the pending delete target: hold identity, not the long-pressed object.
PD_DELETE_ID_RE = re.compile(
    r"\bvar\s+entryToDeleteId\b[\s\S]{0,200}?mutableStateOf\s*<\s*String\?\s*>")
PD_DELETE_OBJECT_RE = re.compile(
    r"\bvar\s+entryToDelete\b[\s\S]{0,200}?mutableStateOf\s*<\s*"
    r"(?:com\.[\w.]+\.)?ModelEntry\??\s*>")
PD_DELETE_RESOLVER_NAME = "liveEntryToDelete"
PD_DELETE_VANISHED_NAME = "entryDeleteTargetVanished"
PD_DELETE_WIRING_RE = re.compile(
    r"onLongClick\s*=\s*\{[\s\S]{0,120}?entryToDeleteId\s*=\s*entry\.id")
PD_DELETE_HOST_RE = re.compile(r"\bMinisAlertDialog\s*\(")
PD_DELETE_DISMISS_RE = re.compile(
    r"onDismissRequest\s*=\s*\{[\s\S]{0,80}?entryToDeleteId\s*=\s*null")
# C29 — the masked key must be re-read when the stored credential may have moved.
PD_STORED_KEY = "storedKey"
PD_REVISION_TOKEN_RE = re.compile(r"\brevision\b")
PD_LIVE_LIST_RE = re.compile(r"\b(?:allEntries|entries|modelEntries)\b")

# ---- [deep-audit-1041 B-01] C30 / C31: the credential LIVE SOURCE ----------
# The stored API key is not a `ProviderInstance` field — it lives in
# EncryptedSharedPreferences — so no part of the config object carries it and no
# plain field key can observe an external credential write. The available
# signal is the config revision (bumped by instancesUpdate RPC / provider import
# / backup restore), paired with the instance list.
#
# C30 — ProviderListScreen: the per-row credential snapshot must be produced by
# an effect keyed on (instance list, revision) that reads through an IO hop, and
# must NOT be read from a composition-phase `remember` initializer (the pre-fix
# shape ran blocking keystore I/O on the main thread once per key change).
# C31 — ProviderConnectionScreen: the masked-key state must be re-read by an
# effect keyed on (instance id, revision) — the same decision C29 grades on
# ProviderDetailScreen.
API_KEY_READ_RE = re.compile(r"\bloadApiKey\s*\(")
IO_HOP_RE = re.compile(
    r"withContext\s*\([^)]*Dispatchers\s*\.\s*(?:IO|Default)\b")
EFFECT_CALLS = ("LaunchedEffect", "produceState")

# ---- [deep-audit-1041 B-04] C32: the collapse read must consult the search --
# `collapsedInstanceIds` seeds to "all collapsed", so a picker that renders the
# collapse decision without consulting the search box hides every hit beyond the
# first one behind a chevron (and, on the sheet, the collapsed summary row is
# further biased towards the already-selected entry). Two accepted spellings:
# the conjunctive gate (`searchText.isEmpty() && …contains(id)`) — polarity
# graded — and the derived-set branch that yields `emptySet()` while searching
# (the shared ModelEntryPicker's spelling).
IS_COLLAPSED_DECL_RE = re.compile(r"\bval\s+isCollapsed\s*=\s*")
IDENT_NAME_RE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
SEARCH_EMPTY_CALL_RE = re.compile(
    r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*(?:isEmpty|isBlank)\s*\(\s*\)")

def _is_search_name(name):
    """A binding that holds the picker's query (`searchText`, `searchQuery`,
    `isSearching`, `query`) — matched on the name's meaning, not its spelling,
    so renaming the box does not silently open the criterion."""
    low = name.lower()
    return "search" in low or "query" in low

def search_empty_tests(text):
    """The `nothing typed yet` predicates in [text]
    (`<searchBox>.isEmpty()` / `.isBlank()`)."""
    return [m.group(1) for m in SEARCH_EMPTY_CALL_RE.finditer(text)
            if _is_search_name(m.group(1))]

def mentions_search(text):
    return any(_is_search_name(n) for n in IDENT_NAME_RE.findall(text))


# C6c — value families a remember initializer can derive from in
# ModelEntryDetailScreen. Reading any of them makes the call site
# config-derived, i.e. it must re-key when the entry identity changes.
DERIVED_SOURCES = ("entry.", "baseModel.", "overrides.", "effectiveInput", "effectiveOutput")
# Dotted paths rooted at the entry / base model / overrides, plus the two bare
# derived modality lists. Note `entry.id` (the identity component) is NOT a
# derivation source: an identity-only key never changes when the entry's
# content changes, so it cannot satisfy C6c on its own.
DERIVED_TOKEN_RE = re.compile(
    r"\b(?:entry|baseModel|overrides)\.[A-Za-z_][A-Za-z0-9_]*"
    r"|\b(?:effectiveInput|effectiveOutput)\b")
# C8 / C9 — the field families the two detail screens edit.
GROUP_FIELD_RE = re.compile(r"\bgroup\.[A-Za-z_][A-Za-z0-9_]*")
ENTRY_FIELD_RE = re.compile(r"\bentry\.[A-Za-z_][A-Za-z0-9_]*")

# ---- [da1034-B4] C16 / C17 ----
# C16 — the Add/Edit sheet of EnvironmentVariablesScreen: the edit gate, the
# derivation that can silently degrade to "new variable", and the close effect.
ENVVAR_EDIT_GATE_RE = re.compile(r"if\s*\(\s*showAddSheet\s*\|\|\s*editEntryId\s*!=\s*null\s*\)")
ENVVAR_EDITING_ANCHOR_RE = re.compile(r"editEntryId\?\.let\s*\{[^}]*entries\.find\s*\{")
ENVVAR_CLOSE_EFFECT_RE = re.compile(
    r"LaunchedEffect\s*\(([^)]*)\)\s*\{[\s\S]{0,900}?\beditEntryId\s*=\s*null")
# The existence test on the close path — inline, or inside a file-local helper the
# effect calls. `\< 0` / `== null` cover the index/lookup spellings the sibling
# criteria (C15b) already use; `.none` is the collection spelling.
VANISHED_EXISTS_RE = re.compile(
    r"\.none\s*[\({]|\.indexOfFirst\s*\(|\.indexOf\s*\(|<\s*0|==\s*-1|!in\b|==\s*null"
    r"|isEmpty\(\)|isNotEmpty\(\)")
IDENT_CALL_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")
# C17 — the WebDav dialog's states seeded from its `initial` argument.
INITIAL_FIELD_RE = re.compile(r"\binitial\?\.[A-Za-z_][A-Za-z0-9_]*")
WEBCONFIG_DIALOG = "WebDavConfigDialog"


def code_of(text):
    """Structure view for every criterion: comments AND string interiors blanked
    out, length preserved (positions project back onto the source). Raises
    KotlinLexError when the source cannot be lexed — callers must report that as
    a finding, never fall back to scanning raw text."""
    return mask_code(text)

def literal_of(text):
    """Literal view: comments blanked, string contents kept — only for anchors
    that genuinely live inside a string (XML / JSON names)."""
    return mask_comments(text)

def remember_keys(line):
    """Extract the key list from a `remember(...)` call on this line, or None
    if the line has no remember call."""
    m = re.search(r"remember\s*\(([^)]*)\)", line)
    if not m:
        return None
    return m.group(1)


def _iter_remember_raw(code, call="remember"):
    """Every `remember(...)` / `remember { ... }` call site in `code`.

    Returns a list of (line_no, keys_text, span, init_text). keys_text is None
    for the keyless `remember { ... }` form, otherwise the raw argument text
    between the parentheses (may span lines). span is the full statement text
    of the call site; init_text is the lambda body only (the derivation
    source). Balanced-paren / balanced-brace scans mean nested calls in the
    keys and multi-line lambdas are captured whole. `\\b` guards mean
    `rememberSaveable` / `rememberCoroutineScope` are not matched when
    scanning for `remember`, and the bare import line (which has no following
    `(` or `{`) is skipped rather than mis-read as an anchor. Callers must pass
    comment-stripped code."""
    out = []
    for m in re.finditer(r"\b" + call + r"\b", code):
        start = m.start()
        i = m.end()
        while i < len(code) and code[i] in " \t":
            i += 1
        keys = None
        brace = -1
        if i < len(code) and code[i] == "{":
            brace = i
        elif i < len(code) and code[i] == "(":
            close = match_bracket(code, i)          # [batch5] shared bracket scan
            if close < 0:
                continue
            keys = code[i + 1:close]
            k = close + 1
            while k < len(code) and code[k] in " \t":
                k += 1
            if k < len(code) and code[k] == "{":
                brace = k
        else:
            continue
        end = start
        if brace >= 0:
            close = match_bracket(code, brace)      # [batch5] shared bracket scan
            if close > 0:
                end = close + 1
        if end <= start:
            nl = code.find("\n", start)
            end = len(code) if nl < 0 else nl
        init = code[brace:end] if brace >= 0 else ""
        out.append((code.count("\n", 0, start) + 1, keys, code[start:end], init, start))
    return out

def iter_remember_calls_pos(code, call="remember"):
    """Same scan as [iter_remember_calls] plus the absolute offset of the call
    site — criteria that must ask WHERE a statement sits (is this clear inside a
    branch that can actually run?) need positions, not just text."""
    return _iter_remember_raw(code, call)

def iter_remember_calls(code, call="remember"):
    """Every remember call site: (line_no, keys_text, span, init_text)."""
    return [(ln, keys, span, init) for ln, keys, span, init, _pos in _iter_remember_raw(code, call)]



def derived_state_calls(code):
    """Every keyed derived-state carrier: `remember(keys) { … }` plus the two
    producer effects (`LaunchedEffect` / `produceState`).

    [deep-audit-1043 B-02] A criterion about "which sources this value is
    re-derived from" must not depend on WHICH carrier holds the derivation —
    the same decision moved from `remember` to a producer effect when the read
    became blocking I/O, and a gate anchored on the carrier would have to be
    rewritten (or would red the fix) every time. Callers get the union, tagged
    only by line number, and grade the key list exactly as before."""
    out = list(iter_remember_calls(code, "remember"))
    for effect in EFFECT_CALLS:
        out.extend(iter_remember_calls(code, effect))
    return sorted(out, key=lambda c: c[0])


def call_args(code, pos):
    """Arguments of the call whose `(` is the first one at or after [pos], or
    None when the parens do not balance. [batch5] shared match_bracket."""
    j = code.find("(", pos)
    if j < 0:
        return None
    close = match_bracket(code, j)
    return None if close < 0 else code[j + 1:close]

def find_call_site(code, name):
    """Index of the first `name(` that is a CALL, not a declaration
    (`private fun refreshResultMessage(`) and not a bare mention."""
    idx = -1
    while True:
        idx = code.find(name + "(", idx + 1)
        if idx < 0:
            return -1
        if "fun " not in code[max(0, idx - 40):idx]:
            return idx

def func_body(code, name):
    """Body text of `fun <name>(…)`, or None (fail-closed) when the declaration
    or its extent cannot be derived.

    [batch5] Shared `function_body` handles both the block form and the
    expression form (`fun f(…) = expr`); the local copy only knew the block
    form, so an expression-bodied decision helper was invisible to every arm
    that graded it."""
    span = function_body(code, name)
    if span is None:
        return None
    start, end, _kind = span
    return code[start:end]

# ---- [wide-1024] grade the VALUE, not the spelling ------------------------
# The wide sweep proved this family blind in one direction: every criterion here
# asked whether a NAMED thing is present (`'instances' in keys`, `'entriesFor('
# in the window, one `+= 1` in the body, `model = entry.model` NOT followed by
# a dot). Each witness kept the name and broke the flow — the key was dropped
# while the identifier stayed in the block, the fresh read moved into a
# discarded lambda, the bump was cancelled one line later, the raw carrier got
# a `.copy()` appended. So the helpers below answer "what VALUE does this
# expression produce", and the criteria grade that.
_STATE_DELEGATE_RE = re.compile(
    r"\b(?:remember\w*|mutableStateOf|collectAsState\w*|produceState|derivedStateOf)\b")


def state_carrier_names(code):
    """Identifiers holding a value that changes OUTSIDE the composition inputs:
    a delegated / `remember`-backed state, a `collectAsState()` flow read, or a
    value a producer effect assigns.

    `val instances = config.instances` is deliberately NOT one: it is a plain
    read of the config the caller already passed in (C1's `instances` /
    `modelEntries` rules grade those two by name)."""
    names = set()
    for m in VAL_DECL_RE.finditer(code):
        name = m.group(1)
        head = code[m.end():]
        if head.startswith("by"):
            if _STATE_DELEGATE_RE.search(head[2:head.find("\n") if "\n" in head else len(head)]):
                names.add(name)
            elif _STATE_DELEGATE_RE.search(statement_tail(code, m.end())):
                names.add(name)
            continue
        if _STATE_DELEGATE_RE.search(statement_tail(code, m.end())):
            names.add(name)
    for effect in EFFECT_CALLS:
        for _line_no, _keys, _span, init in iter_remember_calls(code, effect):
            for am in re.finditer(r"(?m)^[ \t]*([A-Za-z_]\w*)\s*=[^=]", init):
                names.add(am.group(1))
    return names


_SNAPSHOT_DECL_RE = re.compile(
    r"(?m)^[ \t]*(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*remember\w*\s*[({]")


def composition_snapshots(code):
    """Names bound to a PLAIN value computed during composition
    (`val x = remember(k) { … }`). A closure captures the value, so reading one
    AFTER an async call reads the PRE-call list — that is the stale-count defect
    C7 grades."""
    return set(_SNAPSHOT_DECL_RE.findall(code))


def local_value_decls(code):
    """name -> initializer / delegate expression for file-local `val` / `var`
    declarations. Functions are excluded on purpose: a callee's body is not a
    value the caller's argument carries."""
    out = {}
    for m in VAL_DECL_RE.finditer(code):
        out.setdefault(m.group(1), statement_tail(code, m.end()))
    return out


FRESH_ENTRIES_RE = re.compile(r"\b(?:entriesFor|entries|visibleEntries)\s*\(")


def expression_value_chain(expr, decls, depth=0, seen=None):
    """[expr] plus the initializer chain of the file-local names it uses, with
    pass-through scope functions removed at every level.

    A decision expressed through a local must be graded as one expression:
    `val total = allEntries.size` then `refreshResultMessage(result, total, ctx)`
    reads the snapshot just as surely as the inline spelling did."""
    out = [peel_discarding(expr or "")]
    if depth >= 3:
        return out
    seen = seen or set()
    for name in set(IDENT_RE.findall(expr or "")):
        if name in seen:
            continue
        sub = decls.get(name)
        if sub is None:
            continue
        out.extend(expression_value_chain(sub, decls, depth + 1, seen | {name}))
    return out


def check_provider_list(code):
    """C1, C2, C4. Returns list of (criterion_id, ok, detail)."""
    results = []
    lines = [ln for ln in code.split("\n")]

    # --- C1: providerRows remember keys ---
    # [wide-1024] Two grades now: the two config inputs by name, AND every
    # state-carrier the initializer actually reads. The sweep's witness dropped
    # `apiKeyByInstanceId` from the key list while the block still read it
    # (`val apiKey = apiKeyByInstanceId[instance.id]`), so the rows kept the
    # PRE-write credential snapshot forever: neither `instances` nor the entry
    # list moves when a key is written from ProviderDetailScreen / minis-config.
    c1_call = None
    for line_no, keys, span, init in iter_remember_calls(code, "remember"):
        decl = code.split("\n")[line_no - 1] if line_no - 1 < len(code.split("\n")) else ""
        if "val providerRows" in decl or "val providerRows" in span.split("\n")[0]:
            c1_call = (keys, init)
            break
    if c1_call is None:
        results.append(("C1", False, "anchor `val providerRows ... remember(` not found (fail-closed)"))
    else:
        keys, init = c1_call
        if keys is None:
            results.append(("C1", False, "providerRows line has no remember( call"))
        else:
            carriers = state_carrier_names(code)
            carriers.discard("providerRows")
            unkeyed = sorted(
                c for c in carriers
                if re.search(r"\b%s\b" % re.escape(c), init) and c not in keys)
            ok = ("instances" in keys and "modelEntries" in keys and not unkeyed)
            if unkeyed:
                results.append(("C1", False,
                                "keys=[%s] — the initializer reads the state carrier(s) %s "
                                "that are not keyed: the block re-derives only when the config "
                                "inputs move, so a value produced elsewhere (credential / "
                                "effect-published snapshot) stays frozen at its first read"
                                % (keys.strip(), ", ".join(unkeyed))))
            else:
                results.append(("C1", ok, "keys=[%s]" % keys.strip()))

    # --- C2: reorderableRows remember keys ---
    c2_line = None
    for ln in lines:
        if "val reorderableRows" in ln and "remember(" in ln:
            c2_line = ln
            break
    if c2_line is None:
        results.append(("C2", False, "anchor `val reorderableRows ... remember(` not found (fail-closed)"))
    else:
        keys = remember_keys(c2_line)
        if keys is None:
            results.append(("C2", False, "reorderableRows line has no remember( call"))
        else:
            ok = "providerRows" in keys
            results.append(("C2", ok, "keys=[%s]" % keys.strip()))

    # --- C4: every onTogglePinned line ---
    toggle_lines = [ln for ln in lines if "onTogglePinned" in ln and "=" in ln]
    if not toggle_lines:
        results.append(("C4", False, "no `onTogglePinned =` line found (fail-closed)"))
    else:
        bad = []
        for ln in toggle_lines:
            if "remember(" in ln:
                keys = remember_keys(ln)
                if keys is None or "pinned" not in keys:
                    bad.append(ln.strip()[:120])
        results.append(("C4", len(bad) == 0,
                        "all %d onTogglePinned lines ok" % len(toggle_lines) if not bad
                        else "stale remember key(s): %s" % " | ".join(bad)))
    return results


def check_sheet(code):
    """C3. Returns list of (criterion_id, ok, detail)."""
    results = []
    c3_line = None
    for ln in code.split("\n"):
        if re.search(r"val\s+instance\s*=\s*remember\(", ln):
            c3_line = ln
            break
    if c3_line is None:
        results.append(("C3", False, "anchor `val instance = remember(` not found (fail-closed)"))
    else:
        keys = remember_keys(c3_line)
        if keys is None:
            results.append(("C3", False, "instance line has no remember( call"))
        else:
            ok = "instanceId" in keys and "config" in keys
            results.append(("C3", ok, "keys=[%s]" % keys.strip()))
    return results


def check_provider_detail(code):
    """C7a/b/c — the refresh toast must report a count read AFTER the refresh.
    C11c [da1028-C] — the ThinkingRulesSection call site forwards the model
    entry list."""
    results = [check_thinking_rules_call_site(code)]
    rm_pos = find_call_site(code, "refreshModels")
    msg_pos = find_call_site(code, "refreshResultMessage")
    if rm_pos < 0:
        results.append(("C7", False, "anchor `refreshModels(` call not found (fail-closed)"))
        return results
    if msg_pos < 0:
        results.append(("C7", False, "anchor `refreshResultMessage(` call not found (fail-closed)"))
        return results
    results.append(("C7a", msg_pos > rm_pos,
                    "toast is downstream of the fetch (refreshModels@%d < refreshResultMessage@%d)"
                    % (rm_pos, msg_pos)))
    args = call_args(code, msg_pos) or ""
    path = code[rm_pos:msg_pos + len(args) + 2]
    # [wide-1024] Graded on the VALUE each toast argument carries, not on which
    # identifiers appear nearby. The sweep's witness kept both anchors —
    # `entriesFor(instance.id)` still sat in the window and `allEntries.size`
    # no longer ended the line — by writing
    #   val total = allEntries.size.also { providerRepository.entriesFor(instance.id) }
    # `.also` returns its receiver, so the toast still reported the pre-fetch
    # count while every substring check was satisfied.
    decls = local_value_decls(code)
    snapshots = composition_snapshots(code)
    carriers = state_carrier_names(code)
    reassigned = set(
        n for n in carriers
        if re.search(r"(?m)^[ \t]*%s\s*=[^=]" % re.escape(n), path))
    stale, fresh = [], []
    for _a, _b in split_top(args, (",",)):      # [batch5] shared top-level split
        arg = args[_a:_b].strip()
        if not arg:
            continue
        chain = expression_value_chain(arg, decls)
        value = " ‖ ".join(chain)
        dead = sorted(n for n in snapshots
                      if re.search(r"\b%s\b" % re.escape(n), value) and n not in reassigned)
        if dead:
            stale.append("%r still carries the pre-refresh snapshot %s"
                         % (arg, ", ".join(dead)))
        # (a) a re-read evaluated at the call site itself
        site_fresh = bool(FRESH_ENTRIES_RE.search(peel_discarding(arg)))
        # (b) a local bound ON the refresh path from a re-read
        if not site_fresh:
            for name in set(IDENT_RE.findall(arg)):
                for m in re.finditer(r"(?m)^[ \t]*(?:(?:val|var)\s+)?%s\s*(?::[^=\n]*)?=[^=]"
                                     % re.escape(name), path):
                    if FRESH_ENTRIES_RE.search(peel_discarding(statement_tail(path, m.end()))):
                        site_fresh = True
                        break
                if site_fresh:
                    break
        # (c) the accepted alternative: a state-backed list re-assigned on the path
        if not site_fresh:
            for name in set(IDENT_RE.findall(arg)):
                if name in reassigned and re.search(
                        r"(?m)^[ \t]*%s\s*=[^=]" % re.escape(name), path):
                    tail = statement_tail(path, re.search(
                        r"(?m)^[ \t]*%s\s*=[^=]" % re.escape(name), path).end())
                    if not re.search(r"\b%s\b" % re.escape(name), tail):
                        site_fresh = True
                        break
        if site_fresh and not dead:
            fresh.append(arg)
    results.append(("C7b", not stale,
                    "toast args carry no un-reassigned pre-refresh read" if not stale
                    else "; ".join(stale)))
    ok_fresh = bool(fresh)
    results.append(("C7c", ok_fresh,
                    "refresh path re-reads the entry count (%s)" % ", ".join(fresh) if ok_fresh
                    else "no argument of the toast reads a count produced ON the refresh path "
                         "(a re-read inside a discarded receiver scope — `.also` / `.apply` — or "
                         "a composition-time snapshot both count as stale)"))
    return results


def check_connection(code):
    """C5 / C5a — ProviderConnectionScreen rememberSaveable key completeness."""
    results = []
    calls = iter_remember_calls(code, "rememberSaveable")
    if not calls:
        results.append(("C5", False,
                        "no `rememberSaveable(` call found in ProviderConnectionScreen (fail-closed)"))
        results.append(("C5a", False,
                        "no rememberSaveable keyed on `instance.customBaseURL` / `instance.customUserAgent` (fail-closed)"))
        return results
    bad = []
    for line_no, keys, span, _ in calls:
        if keys is None:
            bad.append("L%d: keyless rememberSaveable { ... }" % line_no)
            continue
        for field in sorted(set(re.findall(r"\binstance\.[A-Za-z_][A-Za-z0-9_]*", span))):
            if field not in keys:
                bad.append("L%d: reads %s but keys=[%s]" % (line_no, field, " ".join(keys.split())))
    results.append(("C5", not bad,
                    "all %d rememberSaveable calls key every instance.<field> they read" % len(calls)
                    if not bad else "; ".join(bad)))
    for anchor in ("instance.customBaseURL", "instance.customUserAgent"):
        keyed = any(keys is not None and anchor in keys for _, keys, _, _ in calls)
        results.append(("C5a", keyed,
                        "keyed anchor `%s` present" % anchor if keyed else
                        "no rememberSaveable keyed on `%s` — anchor gone, re-derive this gate (fail-closed)"
                        % anchor))
    return results


def check_entry_detail(code):
    """C6 / C6c — ModelEntryDetailScreen remember key completeness."""
    results = []
    calls = iter_remember_calls(code, "remember")
    if not calls:
        results.append(("C6", False,
                        "no `remember(` call found in ModelEntryDetailScreen (fail-closed)"))
        return results
    keyless = [ln for ln, keys, _, _ in calls if keys is None]
    results.append(("C6", not keyless,
                    "0 keyless `remember {` in file" if not keyless
                    else "keyless remember at line(s): %s" % ", ".join(str(x) for x in keyless)))
    derived_bad = []
    for line_no, keys, _, init in calls:
        if keys is None:
            continue  # already counted by C6
        sources = sorted(set(DERIVED_TOKEN_RE.findall(init)))
        if not sources:
            continue  # not config-derived (e.g. a pure-UI sheet flag)
        missing = [tok for tok in sources if tok not in keys]
        if missing:
            derived_bad.append("L%d: initializer derives from %s but keys=[%s]"
                               % (line_no, ",".join(missing), " ".join(keys.split())))
    results.append(("C6c", not derived_bad,
                    "every config-derived remember keys on the exact sources it reads"
                    if not derived_bad else "; ".join(derived_bad)))
    return results


def check_field_keyed_remember(code, field_re, target, cid, screen):
    """C8 / C9 — every `remember(...)` whose initializer reads a
    `<target>.<field>` must key on that exact field.

    Only the initializer counts as a read: the key list itself mentioning the
    field is what we are testing for, so counting the whole statement would
    make the criterion tautological. Keyless (`remember { ... }`) counts as a
    miss whenever the initializer is field-derived.

    Fail-closed anchor: no field-derived remember call at all means the screen
    was reworked and this criterion can no longer see what it guards → RED
    (never a silent skip)."""
    results = []
    calls = iter_remember_calls(code, "remember")
    derived = 0
    bad = []
    for line_no, keys, _span, init in calls:
        sources = sorted(set(field_re.findall(init)))
        if not sources:
            continue
        derived += 1
        if keys is None:
            bad.append("L%d: keyless remember derives from %s" % (line_no, ",".join(sources)))
            continue
        missing = [tok for tok in sources if tok not in keys]
        if missing:
            bad.append("L%d: initializer derives from %s but keys=[%s]"
                       % (line_no, ",".join(missing), " ".join(keys.split())))
    if derived == 0:
        results.append((cid, False,
                        "no remember call in %s derives from %s.<field> (fail-closed: "
                        "screen reworked, re-derive this criterion)" % (screen, target)))
        return results
    results.append((cid, not bad,
                    "all %d %s-derived remember calls key the exact field they read"
                    % (derived, target) if not bad else "; ".join(bad)))
    return results


def check_group_detail(code):
    """C8 — ModelGroupDetailScreen session-defaults / name editors."""
    return check_field_keyed_remember(code, GROUP_FIELD_RE, "group", "C8",
                                      "ModelGroupDetailScreen")


def check_mount_detail(code):
    """C9 — MountDetailScreen userAllowWrite switch."""
    return check_field_keyed_remember(code, ENTRY_FIELD_RE, "entry", "C9",
                                      "MountDetailScreen")


USAGE_STATS_ANCHOR = "composable(Routes.USAGE_STATS)"
NEXT_COMPOSABLE_RE = re.compile(r"composable\(Routes\.")


def check_app_navigation(code):
    """C10a — the usage route must read the provider config reactively."""
    results = []
    pos = code.find(USAGE_STATS_ANCHOR)
    if pos < 0:
        results.append(("C10a", False,
                        "anchor `%s` not found (fail-closed)" % USAGE_STATS_ANCHOR))
        return results
    nxt = NEXT_COMPOSABLE_RE.search(code, pos + len(USAGE_STATS_ANCHOR))
    block = code[pos:nxt.start()] if nxt else code[pos:]
    if "UsageStatsScreen(" not in block:
        results.append(("C10a", False,
                        "anchor `%s` block does not call UsageStatsScreen( (fail-closed)"
                        % USAGE_STATS_ANCHOR))
        return results
    snapshot = re.search(r"providerConfig\s*=\s*[\w.]+\.value\b", block)
    reactive = "collectAsState" in block
    if snapshot is not None:
        results.append(("C10a", False,
                        "route passes a one-shot snapshot: `%s` — config updates never "
                        "reach UsageStatsScreen; use collectAsState()"
                        % " ".join(snapshot.group(0).split())))
        return results
    results.append(("C10a", reactive,
                    "route reads the config via collectAsState()" if reactive else
                    "no `collectAsState()` in the usage route block — the config is not "
                    "observed (fail-closed)"))
    return results


def check_usage_stats(code):
    """C10b — the stats LaunchedEffect must key on the config it consumes."""
    results = []
    hits = [(ln, keys, span, init)
            for ln, keys, span, init in iter_remember_calls(code, "LaunchedEffect")
            if "computeUsageStats(" in init]
    if not hits:
        results.append(("C10b", False,
                        "no LaunchedEffect(...) whose body calls computeUsageStats( "
                        "(fail-closed: anchor gone, re-derive this criterion)"))
        return results
    bad = []
    for line_no, keys, _span, _init in hits:
        if keys is None:
            bad.append("L%d: keyless LaunchedEffect" % line_no)
            continue
        missing = [tok for tok in ("range", "providerConfig") if tok not in keys]
        if missing:
            bad.append("L%d: keys=[%s] missing %s"
                       % (line_no, " ".join(keys.split()), ",".join(missing)))
    results.append(("C10b", not bad,
                    "all %d computeUsageStats effect(s) key on range + providerConfig" % len(hits)
                    if not bad else "; ".join(bad)))
    return results


def check_thinking_rules_call_site(code):
    """C11c — ProviderDetailScreen must hand the section its entry list."""
    pos = find_call_site(code, "ThinkingRulesSection")
    if pos < 0:
        results = ("C11c", False,
                   "anchor `ThinkingRulesSection(` call not found in ProviderDetailScreen "
                   "(fail-closed: section dropped or renamed — re-derive this criterion)")
    else:
        args = call_args(code, pos)
        if args is None:
            results = ("C11c", False,
                       "ThinkingRulesSection( has no balanced argument list (fail-closed)")
        elif re.search(r"\bmodelEntries\s*=", args):
            results = ("C11c", True, "call site forwards `modelEntries = ...`")
        else:
            results = ("C11c", False,
                       "ThinkingRulesSection( is not given `modelEntries` — the section's derived "
                       "keys can never observe a catalog refresh")
    return results

def check_thinking_rules(code):
    """C11a / C11b — ThinkingRulesSection derived remember keys.

    Only the initializer counts as a read (same rule as C8/C9): the key list
    mentioning a field is exactly what is under test, so grading the whole
    statement would be tautological. Keyless counts as a miss whenever the
    initializer is derived. Fail-closed: no remember whose initializer calls
    the anchor callee means the section was reworked → RED.

    [deep-audit-1043 B-02] The two built-in/preview derivations moved out of the
    composition (they are Room / config-load reads), so the keyed derivation now
    lives on a PRODUCER EFFECT (LaunchedEffect / produceState) rather than on a
    `remember` initializer. The DECISION under test is unchanged — "this value is
    re-derived whenever its sources move" — so the criterion accepts either
    carrier and grades the key list of whichever one performs the read. Anchoring
    on `remember` alone here would be a spelling test that reds the fix."""
    results = []
    targets = (
        ("C11a", "builtInThinkingRulesForDisplay(",
         ("customBaseURL", "appendV1Suffix", "modelEntries"), "built-in rule list"),
        ("C11b", "firstModelId(", ("modelEntries",), "resolution-preview model id"),
    )
    calls = derived_state_calls(code)
    for cid, callee, required, label in targets:
        hits = [c for c in calls if callee in c[3]]
        if not hits:
            results.append((cid, False,
                            "no `remember(...)`/producer effect whose initializer calls %s "
                            "(fail-closed: the %s call was reworked — re-derive this criterion)"
                            % (callee, label)))
            continue
        bad = []
        for line_no, keys, _span, _init in hits:
            if keys is None:
                bad.append("L%d: keyless derivation reads %s" % (line_no, callee))
                continue
            missing = [tok for tok in required if tok not in keys]
            if missing:
                bad.append("L%d: keys=[%s] missing %s"
                           % (line_no, " ".join(keys.split()), ",".join(missing)))
        results.append((cid, not bad,
                        "all %d %s derivation(s) key on %s"
                        % (len(hits), label, " + ".join(required))
                        if not bad else "; ".join(bad)))
    # C12 [da1030-C ISSUE-05] — the loader must observe EXTERNAL rule writes.
    # [deep-audit-1043 B-02] Same move as C11 (the loader is a Room read too), so
    # the arm now has THREE parts instead of two:
    #   (a) the section subscribes to `customRulesRevision` — without it a rule
    #       written outside this screen is invisible;
    #   (b) the custom-rule loader is keyed on (instance.id, reloadKey, revision);
    #   (c) the loader runs OFF the composition thread — an IO hop inside a
    #       producer effect, not a `remember` initializer. `thinkingRulesWithIds`
    #       is `= runBlocking { dao.load… }`, so (c) is what keeps a SQLite round
    #       trip out of the frame budget.
    # Arm (c) is the new one; (a)/(b) are the original contract, deliberately
    # preserved across the carrier change rather than re-derived.
    bad = []
    if not REVISION_SUB_RE.search(code):
        bad.append("no `customRulesRevision` → `collectAsState()` subscription (fail-closed: "
                   "the section cannot observe rules written outside this screen)")
    loader_hits = [c for c in calls if THINKING_LOADER_CALLEE in c[3]]
    if not loader_hits:
        bad.append("no `remember(...)`/producer effect whose initializer calls %s "
                   "(fail-closed: the loader was reworked — re-derive this criterion)"
                   % THINKING_LOADER_CALLEE)
    producers = []
    for line_no, keys, _span, init in loader_hits:
        if keys is None:
            bad.append("L%d: keyless derivation loads the custom rules" % line_no)
            continue
        if not any(tok in keys for tok in REVISION_KEY_TOKENS):
            bad.append("L%d: keys=[%s] missing the rules-revision key (%s)"
                       % (line_no, " ".join(keys.split()), " / ".join(REVISION_KEY_TOKENS)))
        if IO_HOP_RE.search(init):
            producers.append(line_no)
    composition = [c[0] for c in iter_remember_calls(code, "remember")
                   if THINKING_LOADER_CALLEE in c[3]]
    if composition:
        bad.append("the Room loader still runs in a `remember` initializer at line(s) %s — "
                   "blocking SQLite I/O on the composition thread"
                   % ", ".join(str(x) for x in composition))
    if loader_hits and not producers:
        bad.append("the loader is keyed correctly but never hops to an IO dispatcher — "
                   "the Room read would still run on the composition thread")
    results.append(("C12", not bad,
                    "section subscribes to customRulesRevision, keys the loader on it and "
                    "loads it off the composition thread (L%s)"
                    % ",".join(str(x) for x in producers) if not bad else "; ".join(bad)))
    return results

_REVISION_WRITE_RE = re.compile(
    r"customRulesRevision\s*\.\s*value\s*(\+\+|--|\+=|-=|\*=|/=|=(?!=))\s*([^\n;}]*)")


def _revision_writes(body):
    """[(deltas, problems)] for every write to `customRulesRevision.value` in
    [body]: positive literal increments add to deltas, everything else that can
    move the value (decrement, plain assignment, compound assignment with a
    non-literal operand) is a problem — it can cancel the bump the criterion is
    looking for."""
    deltas, problems = [], []
    for m in _REVISION_WRITE_RE.finditer(body):
        op, tail = m.group(1), m.group(2)
        literal = re.match(r"\s*(\d+)\s*$", tail)
        if op == "+=" and literal:
            deltas.append(int(literal.group(1)))
        elif op == "++":
            deltas.append(1)
        elif op in ("-=", "--"):
            problems.append("decrements it (`%s`)" % body[m.start():m.end()].strip())
        else:
            problems.append("rewrites it (`%s`, fail-closed: the net delta cannot be "
                            "proven positive)" % body[m.start():m.end()].strip())
    return deltas, problems


def check_thinking_resolver(code):
    """C13 [da1030-C ISSUE-05] — every custom-rule mutator bumps the revision.

    Two arms: (a) each named mutator body carries at least one bump, and
    (b) the file-level cache-write count does not exceed the bump count. Arm (b)
    is what catches a multi-path body (the restore's null / non-null branches)
    where one branch lost its bump but the body still looks fine to arm (a).

    Fail-closed per mutator: a missing body means the mutator was renamed or
    moved, so this criterion must be re-derived rather than silently pass."""
    results = []
    bad = []
    if not RESOLVER_REVISION_DECL_RE.search(code):
        bad.append("no `val customRulesRevision` declaration (fail-closed)")
    for name in RESOLVER_MUTATORS:
        body = func_body(code, name)
        if body is None:
            bad.append("%s body not found (fail-closed)" % name)
            continue
        if not RESOLVER_BUMP_RE.search(body):
            bad.append("%s does not bump customRulesRevision" % name)
            continue
        # [wide-1024] A bump that is cancelled is not a bump. The sweep's
        # witness appended `_customRulesRevision.value -= 1` after the `+= 1`:
        # the revision lands back on its old value, MutableStateFlow suppresses
        # the emission (equal value), and every collector — ChatScreen,
        # QuickTestSheet, ProviderDetailScreen — never learns the rule set
        # changed. Graded on the NET delta of every write in the body.
        deltas, problems = _revision_writes(body)
        if problems:
            bad.append("%s bumps customRulesRevision but also %s — the revision "
                       "value lands back where it started, StateFlow suppresses the "
                       "equal emission and no collector sees the rule update"
                       % (name, "; ".join(problems)))
        elif sum(deltas) <= 0:
            bad.append("%s's revision writes net to %+d (fail-closed): a non-positive "
                       "delta cannot notify a MutableStateFlow" % (name, sum(deltas)))
    writes = len(CACHE_WRITE_RE.findall(code))
    bumps = len(RESOLVER_BUMP_RE.findall(code))
    if bumps < writes:
        bad.append("customRulesCache is written %d time(s) but bumped %d time(s) — a mutation "
                   "path does not notify the UI" % (writes, bumps))
    results.append(("C13", not bad,
                    "all %d custom-rule mutators bump customRulesRevision "
                    "(%d cache writes / %d bumps)" % (len(RESOLVER_MUTATORS), writes, bumps)
                    if not bad else "; ".join(bad)))
    return results

def check_thinking_editor(code):
    """C14 [da1033-I07] — every seed-derived form state re-initialises with its seed.

    Graded on the decision point, not the spelling: a key of `seed` itself, of the
    exact `seed.<field>` the initializer reads, or of `request.seed`, all count; a key
    that cannot move when the seed changes (e.g. `request.existingId` alone) does not.
    Only the initializer counts as a read (same rule as C8/C9/C11) — counting the key
    list would make the criterion tautological. Keyless counts as a miss.

    Fail-closed: no seed-derived remember at all means the editor was reworked and
    this criterion can no longer see what it guards → RED."""
    results = []
    calls = iter_remember_calls(code, "remember")
    derived = 0
    bad = []
    for line_no, keys, _span, init in calls:
        sources = sorted(set(SEED_FIELD_RE.findall(init)))
        if not sources:
            continue
        derived += 1
        if keys is None:
            bad.append("L%d: keyless remember derives from %s" % (line_no, ",".join(sources)))
            continue
        tokens = [t.strip() for t in keys.split(",")]
        seed_keyed = any(t == "seed" or t.endswith(".seed") for t in tokens)
        if not seed_keyed and not all(src in keys for src in sources):
            bad.append("L%d: initializer derives from %s but keys=[%s] cannot tell one seed "
                       "from another" % (line_no, ",".join(sources), " ".join(keys.split())))
    if derived == 0:
        results.append(("C14", False,
                        "no remember call in ThinkingRuleEditor derives from `seed.` "
                        "(fail-closed: the dialog was reworked, re-derive this criterion)"))
        return results
    results.append(("C14", not bad,
                    "all %d seed-derived remember call(s) key on the seed they read" % derived
                    if not bad else "; ".join(bad)))
    return results

def _seed_assignment_text(code, name):
    """The text of `val <name> = …` up to the end of its statement.

    Multi-line initializers (an `if`/`when` expressing the seed) end at the first
    non-blank line indented no deeper than the declaration itself; a single-line one
    ends at its own newline. Returns None when the declaration is absent."""
    m = re.search(r"\bval\s+" + re.escape(name) + r"\s*=", code)
    if not m:
        return None
    line_start = code.rfind("\n", 0, m.start()) + 1
    indent = len(code[line_start:m.start() + len("val " + name)])
    base = code[line_start:].split("\n")[0]
    base_indent = len(base) - len(base.lstrip(" \t"))
    out = [base]
    for line in code[line_start:].split("\n")[1:]:
        if line.strip() and (len(line) - len(line.lstrip(" \t"))) <= base_indent:
            break
        out.append(line)
    return "\n".join(out)

def check_thinking_dialog_seed(code):
    """C15 [da1033-I07] — the editor dialog is handed the LIVE rule, not the snapshot.

    Two arms, both graded on the decision rather than the spelling:

      (a) the `request` handed to `ThinkingRuleEditorDialog(` is seeded from the
          live rule columns (`customRules` / `customIds`) AND that derivation reads
          the captured `existingId` — either inline in the argument, or through a
          local declared for the purpose. Handing over the raw captured request is
          what let Save write the opening snapshot back over an external edit.
      (b) a `LaunchedEffect` closes the dialog when the resolution says the rule is
          gone. Deleting the rule externally must not be undone by Save
          resurrecting it, and state must not be written during composition.

    New-rule paths are deliberately NOT graded: they carry `existingId == null` /
    `isNew` and seed from the request itself, so they cannot be "stale"."""
    results = []
    pos = find_call_site(code, DIALOG_CALLEE)
    if pos < 0:
        results.append(("C15a", False,
                        "anchor `ThinkingRuleEditorDialog(` call not found (fail-closed: "
                        "the dialog was dropped or renamed — re-derive this criterion)"))
        results.append(("C15b", False, "no dialog call site to close (fail-closed)"))
        return results

    args = call_args(code, pos)
    if args is None:
        results.append(("C15a", False,
                        "ThinkingRuleEditorDialog( has no balanced argument list (fail-closed)"))
    else:
        derivation = args
        note = "inline"
        if "customRules" not in args and "customIds" not in args:
            m = HOISTED_SEED_RE.search(args)
            seg = _seed_assignment_text(code, m.group(1)) if m else None
            if seg is not None:
                derivation = seg
                note = "via `val %s =`" % m.group(1)
        live = "customRules" in derivation or "customIds" in derivation
        by_id = "existingId" in derivation
        if live and by_id:
            detail = ("ThinkingRuleEditorDialog( is seeded from the live rules and "
                      "selected by the captured existingId (%s)" % note)
            ok = True
        elif not live:
            detail = ("the `request` handed to ThinkingRuleEditorDialog( does not read the "
                      "live `customRules` / `customIds` — it is the snapshot captured when "
                      "the row was tapped, so Save writes it back over any external edit")
            ok = False
        else:
            detail = ("the dialog is seeded from the live rules but the captured "
                      "`existingId` does not select among them — the dialog would edit "
                      "whichever rule happens to be first")
            ok = False
        results.append(("C15a", ok, detail))

    m = CLOSE_EFFECT_RE.search(code)
    if not m:
        results.append(("C15b", False,
                        "no LaunchedEffect assigning `editorRequest = null` — a rule "
                        "deleted while the dialog is open would be resurrected by Save"))
    elif not VANISHED_TEST_RE.search(m.group(0)):
        results.append(("C15b", False,
                        "the close effect does not test the resolved index for existence "
                        "(`< 0` / `== -1` / `!in`) — it would close the dialog "
                        "unconditionally"))
    else:
        results.append(("C15b", True,
                        "a LaunchedEffect closes the dialog when the live lookup says the "
                        "rule is gone"))
    return results


def _condition_tests_vanished(code, cond):
    """Does [cond] really ask "is the edit target still there"?

    Inline collection / index tests count, and so does a call to a file-local
    helper whose own body carries one — but only when that body is not a
    CONSTANT. [batch5] `entryDeleteTargetVanished(…) = false` keeps every name
    the old criterion looked for and decides nothing."""
    if VANISHED_EXISTS_RE.search(cond):
        return True, "inline existence test"
    for name in IDENT_CALL_RE.findall(cond):
        body = func_body(code, name)
        if body is None:
            continue
        if const_bool(body) is not None:
            continue  # `= false` / `= true`: a constant wears the name, not the test
        if VANISHED_EXISTS_RE.search(body):
            return True, "via helper %s()" % name
    return False, "no existence test"

def _clear_guarded_by_vanished(code, start, end, clear_name):
    """Is the `<clear_name> = null` inside [start, end) gated on a LIVE test?

    Returns (ok, why). Two things a spelling check cannot see:
      * the assignment must sit on a reachable path — `if (false && …)` is a dead
        branch, so the state is never cleared (batch-5 witness G07-A1);
      * the test has to govern THAT assignment and be evaluated for real
        (a constant helper is a name, not a decision).
    """
    seg = code[start:end]
    m = re.search(r"\b" + re.escape(clear_name) + r"\s*=\s*null", seg)
    if not m:
        return False, "no `%s = null` assignment" % clear_name
    pos = start + m.start()
    conds = [cond for _kind, cond in kast.enclosing_conditions(code, pos)]
    if not conds:
        return False, "closes unconditionally (nothing decides whether the target is gone)"
    if not path_feasible(code, pos):
        return False, ("the clear sits in a branch that can never run (constant-false "
                       "guard) — the state is never cleared")
    for cond in conds:
        tested, why = _condition_tests_vanished(code, cond)
        if tested:
            return True, why
    return False, "close path carries no existence test"

def check_envvar_screen(code):
    """C16 [da1034-B4] — the env-var edit sheet must close when its target vanishes.

    The sheet is opened for an ID; the target is re-resolved from the live list
    every composition. An external delete (`minis-config remove envvars.<KEY>` /
    backup restore) leaves `editEntryId` non-null while the lookup yields null, so
    the form sheet drops to new-variable semantics and Save creates a NEW entry
    under the key the user believes they are editing.

    Fail-closed arms: the edit gate line and the resolving derivation must both be
    present. The close effect must (a) key on BOTH `editEntryId` and `entries`
    (keying on the id alone would not re-run when the list loses the entry) and
    (b) close only when the target is gone (decision, not spelling — see
    _close_condition_tests_vanished)."""
    results = []
    if not ENVVAR_EDIT_GATE_RE.search(code):
        results.append(("C16", False,
                        "anchor `if (showAddSheet || editEntryId != null)` not found "
                        "(fail-closed: the sheet was reworked, re-derive this criterion)"))
        return results
    if not ENVVAR_EDITING_ANCHOR_RE.search(code):
        results.append(("C16", False,
                        "anchor `editEntryId?.let { … entries.find … }` not found (fail-closed: "
                        "the resolving derivation was reworked, re-derive this criterion)"))
        return results
    best = None
    ok = False
    for m in ENVVAR_CLOSE_EFFECT_RE.finditer(code):
        keys = m.group(1)
        if "editEntryId" not in keys or "entries" not in keys:
            best = best or ("no close effect keyed on (editEntryId, entries) — the "
                            "deep-link effect also writes `editEntryId = null` but derives "
                            "from the pending request")
            continue
        # [batch5 G07-A1] graded at the assignment's own position: `if (false && …)`
        # keeps the call and the helper while never closing the sheet.
        tested, why = _clear_guarded_by_vanished(code, m.start(), m.end(), "editEntryId")
        if tested:
            ok = True
            best = ("a LaunchedEffect keyed on (editEntryId, entries) closes the sheet "
                    "when the live lookup says the entry is gone (%s)" % why)
        else:
            best = "close effect does not test the edit target for existence (%s)" % why
        break
    results.append(("C16", ok, best or "no close effect for the edit sheet"))
    return results


def check_webdav_dialog(code):
    """C17 [da1034-B4] — every state seeded from the dialog's `initial` re-seeds with it.

    `initial` arrives asynchronously (the screen fills it in inside a LaunchedEffect,
    first frame null — [deep-audit-1033 I08]), so a keyless `remember` froze the four
    form fields at the first composition's value and Save wrote those empties back
    over the real config.

    Same "initializer read" rule as C14: only the initializer counts as a read.
    Fail-closed: the dialog body must exist and at least one remember must derive
    from `initial.` (dialog reworked → re-derive rather than silently pass)."""
    results = []
    body = func_body(code, WEBCONFIG_DIALOG)
    if body is None:
        results.append(("C17", False,
                        "no `fun WebDavConfigDialog(` body found (fail-closed: the dialog "
                        "was dropped or renamed, re-derive this criterion)"))
        return results
    derived = 0
    bad = []
    for line_no, keys, _span, init in iter_remember_calls(body, "remember"):
        sources = sorted(set(INITIAL_FIELD_RE.findall(init)))
        if not sources:
            continue  # derives from nothing (testing / testResult …) — not graded
        derived += 1
        if keys is None:
            bad.append("L%d: keyless remember derives from %s" % (line_no, ",".join(sources)))
            continue
        if "initial" not in keys:
            bad.append("L%d: initializer derives from %s but keys=[%s] cannot see an "
                       "async / restore update of the config"
                       % (line_no, ",".join(sources), " ".join(keys.split())))
    if derived == 0:
        results.append(("C17", False,
                        "no remember call in WebDavConfigDialog derives from `initial.` "
                        "(fail-closed: the dialog was reworked, re-derive this criterion)"))
        return results
    results.append(("C17", not bad,
                    "all %d initial-derived remember call(s) key on the config they were "
                    "seeded from" % derived if not bad else "; ".join(bad)))
    return results

# ---- [da1034-B4] C18 / C19 ----
# The picker's collapse state ("which provider sections are folded"). Both
# screens that render `modelEntryPickerItems` seeded their own
#   `val collapsedInstanceIds = remember(config) { mutableStateOf(
#        if (modalityFilter != null) emptySet() else config.instances.map { it.id }.toSet()) }`
# copy, keyed on the whole `ProviderConfig`. The config is a fresh object after
# ANY write (a Quick Test persisting the resolved image endpoint, refreshModels
# landing its result, a DTO → StateFlow re-emit), so an unrelated field change
# wiped the user's expand/collapse choice. C18 grades the two call sites, C19
# the shared single source that replaced them.
COLLAPSED_DECL_RE = re.compile(r"\b(?:val|var)\s+collapsedInstanceIds\s*(?::[^=\n]*)?(?:by|=)\s*")
COLLAPSED_SINGLE_SOURCE = "rememberCollapsedInstanceIds"
# [dv28/B-07] A collapse seed built from a FILTERED instance projection. The
# collapse set answers 「is this provider's section folded」 — a question about
# instance IDENTITY — so seeding it from a subset that excludes instances on a
# mutable display flag makes the state's remember key change when the user
# toggles that flag, folding/unfolding every section they had arranged. The
# picker's `config.instances.filter { it.isEnabled }` was exactly this: flipping
# any provider's enable switch re-seeded the whole set.
DISPLAY_FLAG_FILTER_RE = re.compile(
    r"\.\s*filter(?:Not)?\s*(?:\([^()\n]*\))?\s*\{[^{}]*\b(?:isEnabled|isHidden|pinned)\b[^{}]*\}")
# Derivation from the instance LIST. `.instances.size` / `.instances.count` are
# deliberately NOT identity — that is the same mistake as a size-only refresh
# key — while anything else rooted at `<x>.instances` (`.map`, `.filter`,
# `.toSet`, `.firstOrNull`) counts.
INSTANCES_DERIVATION_RE = re.compile(
    r"\.\s*instances\b(?!\s*\.\s*(?:size|count|isNotEmpty|isEmpty)\b)")
VAL_DECL_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?(?:by|=)\s*")
IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
IDENT_CALL_ONLY_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")


def brace_span(text, open_idx):
    """(open, end) of the balanced `{ … }` whose `{` is at [open_idx]."""
    depth, i, n = 1, open_idx + 1, len(text)
    while i < n and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return text[open_idx:i]


def paren_span(text, open_idx):
    """Index just past the `)` matching the `(` at [open_idx]."""
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def statement_tail(code, pos, limit=600):
    """Text of the expression starting at [pos] up to the end of the statement
    (a newline at zero paren/brace depth that is not a continuation)."""
    i = min(len(code), pos)
    n = min(len(code), pos + limit)
    while i < n and code[i] in " \t\r\n":
        i += 1
    pos = i
    depth = 0
    while i < n:
        c = code[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif c == "\n" and depth == 0:
            nxt = code[i + 1:].lstrip(" \t")
            if not nxt.startswith((".", "?", ":", "&&", "||", ")", "}")):
                break
        i += 1
    return code[pos:i]


def value_sources(code):
    """name -> initializer/body text for file-local `val`/`var` declarations and
    `fun`s, so a key can be resolved to the thing it is derived from."""
    decls = {}
    for m in VAL_DECL_RE.finditer(code):
        decls.setdefault(m.group(1), statement_tail(code, m.end()))
    for m in re.finditer(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", code):
        name = m.group(1)
        tail = code[m.end() - 1:]
        if not tail.startswith("("):
            continue
        end = paren_span(tail, 0)
        rest = tail[end:]
        eq = rest.find("=")
        brace = rest.find("{")
        if eq >= 0 and (brace < 0 or eq < brace):
            decls.setdefault(name, statement_tail(rest, eq + 1))
        elif brace >= 0:
            decls.setdefault(name, brace_span(rest, brace))
    return decls


def derives_from_instances(expr, decls, depth=0, seen=None):
    """Does [expr] derive from an instance LIST, directly or through the
    file-local values / helpers it names? Resolved by NAME, not by a hardcoded
    helper name, so renaming the single source does not silently open the gate
    (da1034-A2 lesson: grade the decision, not the spelling)."""
    if not expr:
        return False
    if INSTANCES_DERIVATION_RE.search(expr):
        return True
    if depth >= 4:
        return False
    seen = seen or set()
    for name in set(IDENT_RE.findall(expr)):
        if name in seen:
            continue
        sub = decls.get(name)
        if sub is None:
            continue
        if derives_from_instances(sub, decls, depth + 1, seen | {name}):
            return True
    return False


def derivation_sources(expr, decls, depth=0, seen=None):
    """[expr] plus the body of every file-local declaration it names, transitively
    — the whole chain a key resolves through. Used to ask a question about the
    SHAPE of the derivation («does anything in this chain discard instances?»)
    rather than about one spelling."""
    out = [expr]
    if depth >= 4:
        return out
    seen = seen or set()
    for name in set(IDENT_RE.findall(expr)):
        if name in seen:
            continue
        sub = decls.get(name)
        if sub is None:
            continue
        out.extend(derivation_sources(sub, decls, depth + 1, seen | {name}))
    return out

def check_collapsed_screen(code):
    """C18 [da1034-B4] — the picker collapse state survives a non-identity config write.

    Graded on the DECISION (does this state re-seed only when the instance set
    changes), not the spelling: delegating to the shared single source counts,
    an inline `remember(config.instances.map { … }.toSet())` counts, and so does
    any local the key list names that resolves back to `<x>.instances`. A
    derivation that FILTERS the instance list on a per-instance display flag
    (`isEnabled` / `isHidden` / `pinned`) does not count — it resolves back to
    `.instances` while still re-seeding on a runtime toggle (dv28/B-07).
    Fail-closed when the declaration is gone (screen reworked)."""
    results = []
    m = COLLAPSED_DECL_RE.search(code)
    if not m:
        results.append(("C18", False,
                        "no `val collapsedInstanceIds = …` declaration (fail-closed: the "
                        "screen was reworked — re-derive this criterion)"))
        return results
    head = code[m.end():].lstrip()
    if head.startswith(COLLAPSED_SINGLE_SOURCE + "("):
        results.append(("C18", True,
                        "seeds the collapse state through the shared single source "
                        "rememberCollapsedInstanceIds(…) instead of a private copy"))
        return results
    if not re.match(r"remember\s*[({]", head):
        results.append(("C18", False,
                        "collapsedInstanceIds is seeded by `%s…` — the gate grades a "
                        "remember(...) seed or the shared helper (fail-closed)"
                        % " ".join(head[:60].split())))
        return results
    _ln, keys, _span, _init = iter_remember_calls(head, "remember")[0]
    if keys is None:
        results.append(("C18", False,
                        "keyless `remember { … }`: the collapse state can never observe "
                        "a provider instance being added or removed"))
        return results
    flat = " ".join(keys.split())
    decls = value_sources(code)
    if not derives_from_instances(keys, decls):
        results.append(("C18", False,
                        "keys=[%s] derive from neither the instance list nor anything "
                        "derived from it — an unrelated config write (Quick Test "
                        "persisting imageEndpointResolved, refreshModels landing, a DTO → "
                        "StateFlow emit) resets every open section" % flat))
        return results
    # [dv28/B-07] …and the derivation must not THROW INSTANCES AWAY. A key built
    # from `.instances.filter { it.isEnabled }` derives from the instance list
    # (so the check above passes it) while being wrong for the same reason as a
    # whole-config key: the filtered flag is user-mutable at runtime, so toggling
    # it changes the key and re-seeds the collapse state. Graded on the shape of
    # the chain, not on a flag-name whitelist at one call site: the offender is a
    # `.filter`/`.filterNot` whose predicate reads a per-instance DISPLAY flag.
    offenders = [t for t in derivation_sources(keys, decls)
                 if INSTANCES_DERIVATION_RE.search(t) and DISPLAY_FLAG_FILTER_RE.search(t)]
    if offenders:
        results.append(("C18", False,
                        "keys=[%s] resolve through a FILTERED instance projection (`%s`) — "
                        "the key therefore changes whenever that per-instance display flag "
                        "is toggled, so flipping one provider's switch re-seeds the set and "
                        "folds (or unfolds) every section the user had arranged. The collapse "
                        "set is a question about instance IDENTITY: seed it from the whole id "
                        "set (the shared single source does exactly that)"
                        % (flat, " ".join(offenders[0].split())[:160])))
        return results
    results.append(("C18", True,
                    "keys=[%s] resolve back to the instance list, so a config re-emit "
                    "that changes no instance while the user is picking no longer "
                    "resets every open section" % flat))
    return results


def check_collapsed_helper(code):
    """C19 [da1034-B4] — the shared single source keys its state on the id set.

    The screen criterion (C18) accepts delegation, so the real decision lives
    here: the state-producing `remember` (the one whose lambda builds a
    `mutableStateOf`) must key on something derived from the instance list.
    Fail-closed when the helper, its state remember, or any `<x>.instances`
    derivation in the file is gone."""
    results = []
    body = func_body(code, COLLAPSED_SINGLE_SOURCE)
    if body is None:
        results.append(("C19", False,
                        "no `fun rememberCollapsedInstanceIds(` body found (fail-closed: "
                        "the shared single source was dropped or renamed — re-derive this "
                        "criterion)"))
        return results
    decls = value_sources(code)
    states = [(ln, keys) for ln, keys, _span, init in iter_remember_calls(body)
              if "mutableStateOf" in init]
    if not states:
        results.append(("C19", False,
                        "rememberCollapsedInstanceIds no longer produces its state through "
                        "`remember { mutableStateOf(…) }` (fail-closed: re-derive)"))
        return results
    bad = []
    for ln, keys in states:
        if keys is None:
            bad.append("L%d: keyless state — the collapse set is frozen at the first "
                       "composition" % ln)
            continue
        flat = " ".join(keys.split())
        if not derives_from_instances(keys, decls):
            bad.append("L%d: state keys=[%s] are not derivable from the instance list — a "
                       "config re-emit that adds/removes no instance resets the sections"
                       % (ln, flat))
    if not any(v and INSTANCES_DERIVATION_RE.search(v) for v in decls.values()):
        bad.append("nothing in this file derives from `<x>.instances` at all — the state is "
                   "not seeded from the instance set (fail-closed)")
    results.append(("C19", not bad,
                    "the shared collapse state re-seeds only with the instance-id set"
                    if not bad else "; ".join(bad)))
    return results

def _resolver_window(code, name, limit=220):
    """Text right after `fun <name>( )` up to the first blank line (where the
    next declaration starts), so a reverted body cannot borrow a
    `<x>.instances.find {` match from further down the file. None when the
    declaration itself is gone."""
    m = LIVE_RESOLVER_DECL_RE(name).search(code)
    if not m:
        return None
    w = code[m.end():m.end() + limit]
    cut = w.find("\n\n")
    return w if cut < 0 else w[:cut]


def _live_test_names(code):
    """File-local names bound to an existence test over the live instance list —
    `val parentStillThere = providerRepository.config.value.instances.any { … }`."""
    names = set()
    for m in re.finditer(r"\bval\s+([A-Za-z_]\w*)\s*=", code):
        tail = statement_tail(code, m.end())
        if ADD_CUSTOM_LIVE_GUARD_RE.search(tail):
            names.add(m.group(1))
    return names

_DIVERT_RE = re.compile(r"\b(?:return|throw)\b")

def _guard_blocks_the_write(code, call_pos, window=900):
    """[batch5 G07-A2] Is the write at [call_pos] preceded by a guard that can
    actually block it? Three things the old "exists in the window" search
    ignored:

      * the guard must be FEASIBLE — `if (false && !parentStillThere)` keeps the
        test and the call in the window while never blocking anything;
      * the guard must DIVERT control (return / throw) before the write — a
        guard that only logs does not stop an orphan ModelEntry;
      * the test must be over the LIVE instance list (inline, or through a
        file-local name bound to it).
    """
    live_names = _live_test_names(code)
    for cond, t_start, t_end, _es, _ee in kast.if_blocks(code):
        if t_end > call_pos or t_start < call_pos - window:
            continue
        tests_live = bool(ADD_CUSTOM_LIVE_GUARD_RE.search(cond)) or any(
            contains_ident(cond, n) for n in live_names)
        if not tests_live:
            continue
        if not guard_feasible(cond):
            continue  # dead guard: it never blocks the write
        if not _DIVERT_RE.search(code[t_start:t_end]):
            continue  # a guard that does not skip the write is not a guard
        line = code.count("\n", 0, t_start) + 1
        return True, ("a feasible guard at line %d re-resolves the parent and diverts "
                      "before the write" % line)
    return False, "no feasible guard diverts before the write"

def check_add_custom_model(code):
    """C20 / C21 [da1039-B3 B-03] — AddCustomModelScreen must read its parent
    instance reactively and re-check it against the live list before it writes.

    C20 grades the READ decision: subscribe to the config flow and derive the
    instance from `config.instances`. The one-shot `providerRepository.instance(id)`
    read is the defect (`_config.value` snapshot — an external delete never
    recomposes the page, so the `instance == null` guard never fires).
    C21 grades the WRITE decision: the `addEntry` call must be preceded by an
    existence test over the live instance list, because `addEntry` only checks
    for duplicates — a delete landing between the click and the IO hop would
    otherwise persist an entry pointing at a provider instance that is gone.
    Fail-closed when the save path has no `addEntry(` call at all."""
    results = []
    problems = []
    if not ADD_CUSTOM_SUBSCRIPTION_RE.search(code):
        problems.append("no `providerRepository.config.collectAsState()` — the instance is still read "
                        "as a one-shot snapshot")
    if not ADD_CUSTOM_DERIVE_RE.search(code):
        problems.append("the instance is not derived from `config.instances` (fail-closed: re-derive "
                        "this criterion if the screen was reworked)")
    if ONE_SHOT_INSTANCE_RE.search(code):
        problems.append("`providerRepository.instance(` is still called — that read does not subscribe")
    results.append(("C20", not problems,
                    "subscribes to the config flow and derives the parent instance from it"
                    if not problems else "; ".join(problems)))

    entry = ADD_ENTRY_CALL_RE.search(code)
    if not entry:
        results.append(("C21", False,
                        "no `providerRepository.addEntry(` call in the save path (fail-closed: "
                        "re-derive this criterion)"))
        return results
    guarded, why = _guard_blocks_the_write(code, entry.start())
    results.append(("C21", guarded,
                    ("the save path re-checks the parent against the live instance list "
                     "before it writes (%s)" % why) if guarded else
                    ("the write is not preceded by a live-instance existence test that "
                     "can block it (%s) — a delete landing between the click and the IO "
                     "hop still persists an orphan ModelEntry" % why)))
    return results


def _live_write_arg(args, live_locals):
    """Does the VALUE handed to this write come from the live resolver?

    [batch5 G07-A3] The old test looked for a live name anywhere in the argument
    TEXT — but `.also { … }` / `.apply { … }`／`onEach { … }` return their
    RECEIVER, so `instance.copy(…).also { live }` evaluates to the render-frame
    snapshot while reading as if it had resolved the live instance. Grade the
    value sources (shared value_sources / peel_discarding), not the spelling.
    Note: the file-local `value_sources(code)` is the declaration map for C30;
    the expression-level one lives in the shared module as `kflow.value_sources`."""
    for src in kflow.value_sources(peel_discarding(args or "")):
        cur = strip_parens(src.strip())
        if LIVE_RESOLVER_NAME + "(" in cur:
            return True
        if any(contains_ident(cur, v) for v in live_locals):
            return True
    return False


def check_connection_write_path(code):
    """C22 / C23 [da1039-B3 B-04] — ProviderConnectionScreen must write through
    the LIVE instance, and a blur that changed nothing must not write.

    C22 grades the write target: `instance` is the composition-time snapshot, so
    every `updateInstance(instance.copy(…))` pushed the whole record back — a
    focus-loss save running after a toggle (or after an external minis-config /
    RPC / backup-restore write) silently reverted the newer fields. The file must
    declare a live resolver (`fun currentInstance()` reading `config.instances`)
    and every write must go through it — directly or through a local assigned
    from it (decision, not spelling). Fail-closed when the resolver or the write
    calls are gone.
    C23 grades the skip-if-unchanged decision: Compose delivers
    `onFocusChanged(isFocused = false)` on the first frame after mounting, so a
    saver without a dirty test writes (and logs) on every visit. Both savers must
    route through the pure decision helper, and the base-URL saver must no longer
    rewrite `customUserAgent` in the same copy (cross-field write)."""
    results = []
    window = _resolver_window(code, LIVE_RESOLVER_NAME)
    reads_live = bool(window is not None and LIVE_RESOLVER_READ_RE.search(window))
    live_locals = set(LIVE_LOCAL_RE(LIVE_RESOLVER_NAME).findall(code))

    calls = list(UPDATE_INSTANCE_RE.finditer(code))
    if not calls:
        results.append(("C22", False,
                        "no `providerRepository.updateInstance(` call found (fail-closed: re-derive "
                        "this criterion)"))
    else:
        bad = []
        for m in calls:
            args = call_args(code, m.end() - 1) or ""
            if _live_write_arg(args, live_locals):
                continue
            bad.append("L%d: `%s` is the composition-time snapshot"
                       % (code.count("\n", 0, m.start()) + 1, " ".join(args.split())[:70]))
        if not reads_live:
            bad.insert(0, "no `fun %s()` resolving from `config.instances` (fail-closed: the live "
                          "resolver is gone — re-derive this criterion)" % LIVE_RESOLVER_NAME)
        results.append(("C22", not bad,
                        "all %d updateInstance writes go through %s(), which reads the live instance"
                        % (len(calls), LIVE_RESOLVER_NAME) if not bad else "; ".join(bad)))

    problems = []
    helper = func_body(code, DIRTY_DECISION_NAME)
    if helper is None or not DIRTY_DECISION_BODY_RE.search(helper):
        problems.append("no `%s(` decision that compares the normalised edit against the stored value "
                        "(fail-closed: an always-write helper re-opens the first-frame write)"
                        % DIRTY_DECISION_NAME)
    for name in SAVER_NAMES:
        body = func_body(code, name)
        if body is None:
            problems.append("no `fun %s(` body (fail-closed: re-derive this criterion)" % name)
            continue
        if DIRTY_DECISION_NAME not in body:
            problems.append("%s does not route through %s(" % (name, DIRTY_DECISION_NAME))
        elif not DIRTY_SKIP_WRITE_RE.search(body):
            problems.append("%s ignores the decision (no early return when the value did not change)"
                            % name)
    base_body = func_body(code, SAVER_NAMES[0])
    if base_body is not None and "customUserAgent" in base_body:
        problems.append("%s still writes customUserAgent in the same copy (a base-URL blur rewrites "
                        "an unrelated field)" % SAVER_NAMES[0])
    results.append(("C23", not problems,
                    "both focus-loss savers write only when the field changed, and the base-URL saver "
                    "no longer touches customUserAgent" if not problems else "; ".join(problems)))
    return results


def check_quick_test_target(code):
    """C24 / C25 [da1039-B3 B-08] — the Quick Test sheet must be opened for an
    ID resolved against the live entry list, and must close when that ID stops
    resolving.

    C24 grades the target decision: the state is a `String?` id, the picker
    wiring stores `it.id` (identity, not the object captured at tap time), the
    sheet is hosted, and its `onDismiss` resets the same state. A `ModelEntry`
    snapshot kept feeding "Run again" the config the row had when the lightning
    bolt was tapped.
    C25 grades the close-on-vanished decision: a `LaunchedEffect` keyed on BOTH
    the pending id and the list it resolves against, whose body calls the
    vanished test and clears the state (closing unconditionally does NOT count —
    that would dismiss the sheet on any recomposition). Both screens that host
    the shared picker are graded, so the sibling that never wired the channel
    cannot stay silently different."""
    results = []
    problems = []
    if not QUICK_TEST_ID_RE.search(code):
        problems.append("no `var quickTestEntryId` holding a String? id — the target is still the "
                        "ModelEntry captured when the row was tapped")
    if not QUICK_TEST_WIRING_RE.search(code):
        problems.append("no `onQuickTest = { … quickTestEntryId = it.id }` wiring — the row captures "
                        "the object instead of its identity, or the channel is missing")
    resolved = []
    list_ok = False
    for m in re.finditer(r"\b" + QUICK_TEST_RESOLVER_NAME + r"\s*\(", code):
        args = call_args(code, m.end() - 1) or ""
        if "quickTestEntryId" in args:
            resolved.append(m)
            if "modelEntries" in args:
                list_ok = True
    if not resolved:
        problems.append("`%s(` is never called with the pending id — the sheet keeps the tapped copy"
                        % QUICK_TEST_RESOLVER_NAME)
    elif not list_ok:
        problems.append("`%s(` does not resolve against `<x>.modelEntries`" % QUICK_TEST_RESOLVER_NAME)
    if not QUICK_TEST_SHEET_RE.search(code) or not QUICK_TEST_DISMISS_RE.search(code):
        problems.append("no `QuickTestSheet(` host whose onDismiss clears `quickTestEntryId`")
    results.append(("C24", not problems,
                    "the Quick Test target is held as an id, wired from the row identity and resolved "
                    "against the live entry list" if not problems else "; ".join(problems)))

    close = []
    for line_no, keys, _span, init in iter_remember_calls(code, "LaunchedEffect"):
        if QUICK_TEST_VANISHED_NAME not in init or "quickTestEntryId = null" not in init:
            continue
        flat = keys or ""
        if "quickTestEntryId" not in flat or "modelEntries" not in flat:
            continue
        close.append(line_no)
    results.append(("C25", bool(close),
                    "the sheet closes through a LaunchedEffect keyed on the pending id AND the live "
                    "entry list (L%s)" % ",".join(str(x) for x in close) if close else
                    "no `LaunchedEffect(<pending id>, <entry list>)` that clears the state when "
                    "`%s(` says the target is gone — a deleted model leaves the sheet open"
                    % QUICK_TEST_VANISHED_NAME))
    return results


def check_provider_detail_live_state(code):
    """C26 / C27 / C28 / C29 [deep-audit-1040 B-07] — ProviderDetailScreen must
    resolve its write targets and its pending delete against the LIVE config, and
    re-read the stored key when the config revision moves.

    C26 grades the write target: every `updateInstance(` / `exportProviderInstance(`
    call must go through the live resolver, directly or through a local assigned
    from it (decision, not spelling). Fail-closed when the resolver is gone or
    when no write call site can be found at all.
    C27 grades the pending delete target: the state holds a `String?` id, the row
    wiring stores `entry.id`, the dialog resolves that id against the live
    catalog, and dismissing clears the same state. The pre-fix `ModelEntry?`
    object is an explicit red — that shape is what fed the dialog a model that
    had already been deleted.
    C28 grades close-on-vanished: a `LaunchedEffect` keyed on the pending id AND
    the list it resolves against, whose body tests the vanished helper and clears
    the state. Closing unconditionally does NOT count (that dismisses on any
    recomposition).
    C29 grades the credential re-read: a `LaunchedEffect` keyed on the instance
    id AND a `revision` token, whose body assigns the masked-key state.
    """
    results = []

    # --- C26: every write goes through the live resolver --------------------
    window = _resolver_window(code, LIVE_RESOLVER_NAME)
    reads_live = bool(window is not None and LIVE_RESOLVER_READ_RE.search(window))
    live_locals = set(LIVE_LOCAL_RE(LIVE_RESOLVER_NAME).findall(code))
    calls = [m for m in list(UPDATE_INSTANCE_RE.finditer(code)) + list(PD_EXPORT_RE.finditer(code))
             if "fun " not in code[max(0, m.start() - 40):m.start()]]
    if not calls:
        results.append(("C26", False,
                        "no `updateInstance(` / `exportProviderInstance(` call found (fail-closed: "
                        "re-derive this criterion)"))
    else:
        bad = []
        for m in calls:
            args = call_args(code, m.end() - 1) or ""
            if LIVE_RESOLVER_NAME + "(" in args:
                continue
            if any(re.search(r"\b" + re.escape(v) + r"\b", args) for v in live_locals):
                continue
            bad.append("L%d: `%s` is the render-frame object"
                       % (code.count("\n", 0, m.start()) + 1, " ".join(args.split())[:70]))
        if not reads_live:
            bad.insert(0, "no `fun %s()` resolving from `config.instances` (fail-closed: the live "
                          "resolver is gone — re-derive this criterion)" % LIVE_RESOLVER_NAME)
        results.append(("C26", not bad,
                        "all %d write(s) go through %s(), which reads the live instance"
                        % (len(calls), LIVE_RESOLVER_NAME) if not bad else "; ".join(bad)))

    # --- C27: the pending delete target is an id, resolved live -------------
    problems = []
    if not PD_DELETE_ID_RE.search(code):
        problems.append("no `var entryToDeleteId` holding a String? id — the pending target is "
                        "still the ModelEntry captured when the row was long-pressed")
    if PD_DELETE_OBJECT_RE.search(code):
        problems.append("`var entryToDelete` still holds a `ModelEntry?` object")
    if not PD_DELETE_WIRING_RE.search(code):
        problems.append("no `onLongClick = { … entryToDeleteId = entry.id }` wiring — the row "
                        "stores something other than the entry identity")
    resolved = []
    list_ok = False
    for m in re.finditer(r"\b" + PD_DELETE_RESOLVER_NAME + r"\s*\(", code):
        args = call_args(code, m.end() - 1) or ""
        if "entryToDeleteId" in args:
            resolved.append(m)
            if PD_LIVE_LIST_RE.search(args):
                list_ok = True
    if not resolved:
        problems.append("`%s(` is never called with the pending id — the dialog keeps the "
                        "long-pressed copy" % PD_DELETE_RESOLVER_NAME)
    elif not list_ok:
        problems.append("`%s(` does not resolve against the live catalog" % PD_DELETE_RESOLVER_NAME)
    if not PD_DELETE_HOST_RE.search(code) or not PD_DELETE_DISMISS_RE.search(code):
        problems.append("no `MinisAlertDialog(` host whose onDismiss clears `entryToDeleteId`")
    results.append(("C27", not problems,
                    "the delete target is held as an id, wired from the row identity, resolved "
                    "against the live catalog and cleared on dismiss" if not problems
                    else "; ".join(problems)))

    # --- C28: the dialog closes when the target vanishes --------------------
    close, close_why, close_problems = [], "", []
    for line_no, keys, span, init, pos in _iter_remember_raw(code, "LaunchedEffect"):
        if PD_DELETE_VANISHED_NAME not in init or "entryToDeleteId = null" not in init:
            continue
        flat = keys or ""
        if "entryToDeleteId" not in flat or not PD_LIVE_LIST_RE.search(flat):
            continue
        # [batch5 G07-A4] The close must be a LIVE decision, not a decision-shaped
        # spelling: the assignment has to sit on a reachable path AND the vanished
        # helper's own body must still test for real — `entryDeleteTargetVanished(…) = false`
        # keeps every name this criterion used to look for and decides nothing
        # (the same family-hole C16 closed on the env-var sheet).
        ok, why = _clear_guarded_by_vanished(code, pos, pos + len(span), "entryToDeleteId")
        if ok:
            close.append(line_no)
            close_why = why
        else:
            close_problems.append("L%d: %s" % (line_no, why))
    if close:
        results.append(("C28", True,
                        "the dialog closes through a LaunchedEffect keyed on the pending id AND the "
                        "live catalog, gated on a live existence test (%s) (L%s)"
                        % (close_why, ",".join(str(x) for x in close))))
    elif close_problems:
        results.append(("C28", False,
                        "the close-on-vanished effect is decision-shaped but decides nothing — "
                        + "; ".join(close_problems)))
    else:
        results.append(("C28", False,
                        "no `LaunchedEffect(<pending id>, <catalog>)` that clears the state when "
                        "`%s(` says the target is gone — a model deleted externally leaves the dialog "
                        "naming a now-dead id" % PD_DELETE_VANISHED_NAME))

    # --- C29: the masked key re-reads when the revision moves ---------------
    subs = []
    for line_no, keys, _span, init in iter_remember_calls(code, "LaunchedEffect"):
        if PD_STORED_KEY not in init:
            continue
        flat = keys or ""
        if "instanceId" in flat and PD_REVISION_TOKEN_RE.search(flat):
            subs.append(line_no)
    results.append(("C29", bool(subs),
                    "the stored key is re-read by a LaunchedEffect keyed on the instance id AND "
                    "the config revision (L%s)" % ",".join(str(x) for x in subs) if subs else
                    "no `LaunchedEffect(instanceId, <x>.revision)` assigning `%s` — an external "
                    "key write leaves the previous key masked on screen" % PD_STORED_KEY))
    return results


def _subscript_key(text, open_pos):
    """Text of the key inside `map[…]` given the `[` index, or None."""
    close = match_bracket(text, open_pos)
    if close < 0:
        return None
    return text[open_pos + 1:close].strip()


def check_credential_live_source_list(code):
    """C30 [deep-audit-1041 B-01] — ProviderListScreen's credential snapshot.

    The rows carry an `apiKey` / `isConfigured` pair read from
    EncryptedSharedPreferences, i.e. from outside the config object, so the
    pre-fix `remember(instances, config.modelEntries) { … loadApiKey(id) … }`
    both (a) ran blocking keystore I/O on the composition thread and (b) never
    re-derived when an external write changed a key (neither `instances` nor
    `modelEntries` moves). Graded on the two decisions, not the spelling:

    - the read happens OFF the composition thread: no `remember` initializer may
      contain the credential read;
    - a producer effect exists whose KEYS resolve back to the instance list and
      carry a revision token, and whose body performs the read through an IO hop.

    Fail-closed when the credential read is gone from the file entirely (the
    screen was reworked — re-derive this criterion)."""
    results = []
    if not API_KEY_READ_RE.search(code):
        results.append(("C30", False,
                        "no `loadApiKey(` read in this file (fail-closed: the row credential "
                        "snapshot is gone — re-derive this criterion)"))
        return results

    composition = []
    for line_no, _keys, _span, init in iter_remember_calls(code, "remember"):
        if API_KEY_READ_RE.search(init):
            composition.append(line_no)
    results.append(("C30a", not composition,
                    "0 composition-phase credential reads (every loadApiKey sits in a "
                    "producer effect)" if not composition else
                    "loadApiKey() runs in a `remember` initializer at line(s) %s — blocking "
                    "keystore I/O on the composition thread"
                    % ", ".join(str(x) for x in composition)))

    decls = value_sources(code)
    producers = []
    targets = set()
    for effect in EFFECT_CALLS:
        for line_no, keys, _span, init in iter_remember_calls(code, effect):
            if not API_KEY_READ_RE.search(init):
                continue
            flat = keys or ""
            if not PD_REVISION_TOKEN_RE.search(flat):
                continue
            if not derives_from_instances(flat, decls):
                continue
            if not IO_HOP_RE.search(init):
                continue
            producers.append(line_no)
            for am in re.finditer(r"(?m)^[ \t]*([A-Za-z_]\w*)\s*=[^=]", init):
                targets.add(am.group(1))
    # [wide-1024] The producer existing is only half the decision: the snapshot
    # it publishes must be CONSUMED by a derivation that re-keys on it. The
    # sweep's witness kept the effect and the `apiKey…[id]` read inside the
    # providerRows block, but dropped the map from the remember keys — a
    # producer whose output is never a key is a snapshot nobody re-derives, and
    # the row badges / masked keys stay at their first value.
    consumers, unkeyed, constant_key = [], [], []
    for line_no, keys, _span, init in iter_remember_calls(code, "remember"):
        read = sorted(t for t in targets if re.search(r"\b%s\b" % re.escape(t), init))
        if not read:
            continue
        consumers.append(line_no)
        for t in read:
            if t not in (keys or ""):
                unkeyed.append("L%d reads %s but keys=[%s]"
                               % (line_no, t, " ".join((keys or "").split())))
            # [batch5 G07-A5] A read of the snapshot with a CONSTANT subscript
            # (`apiKeyByInstanceId[""]`) still names the map and still keys on it,
            # but resolves to the same (empty) entry for every row — every spelling
            # this criterion used to look for is intact while every badge is off.
            # The key must be an expression that can vary with the row identity.
            for sm in re.finditer(r"\b%s\s*\[" % re.escape(t), init):
                key = _subscript_key(init, sm.end() - 1)
                if key is not None and kast.is_pure_literal(key):
                    constant_key.append("L%d reads %s[%s] — a constant key resolves to the same "
                                        "entry for every row" % (line_no, t, key))
    ok = bool(producers) and bool(targets) and bool(consumers) and not unkeyed and not constant_key
    if not producers:
        detail = ("no producer effect keyed on (instance list, <x>.revision) that reads the "
                  "credentials through an IO hop — an external key write changes neither "
                  "`instances` nor `modelEntries`, so the row badges stay stale")
    elif not targets:
        detail = ("the credential producer's target cannot be read (fail-closed): no "
                  "`<name> = …` assignment inside the effect body — re-derive this criterion")
    elif not consumers:
        detail = ("the credential snapshot is produced but never consumed — an external key "
                  "write re-reads the credentials into a value no derivation depends on, so "
                  "the surface keeps showing the old key")
    elif unkeyed or constant_key:
        detail = ("the credential snapshot is consumed without being re-derived per row: %s — "
                  "the derivation either never re-runs when the producer republishes or reads "
                  "a key that cannot vary with the row identity"
                  % "; ".join(unkeyed + constant_key))
    else:
        detail = ("the credential snapshot is produced by an effect keyed on the instance "
                  "list + config revision, reading through an IO hop (L%s), and consumed by "
                  "a keyed derivation (L%s)"
                  % (",".join(str(x) for x in producers),
                     ",".join(str(x) for x in consumers)))
    results.append(("C30b", ok, detail))
    return results

def check_credential_live_source_connection(code):
    """C31 [deep-audit-1041 B-01] — ProviderConnectionScreen must re-read the
    stored key when the config revision moves (the same decision C29 grades on
    ProviderDetailScreen, whose fix this screen was missed by).

    On this screen a stale snapshot is worse than a stale badge: pressing Edit
    seeds the editor from `storedKey`, so the next Save writes the OLD key back
    over the credential an external writer had just landed. Fail-closed when the
    credential read is gone from the file entirely."""
    results = []
    if not API_KEY_READ_RE.search(code):
        results.append(("C31", False,
                        "no `loadApiKey(` read in this file (fail-closed: the stored-key "
                        "snapshot is gone — re-derive this criterion)"))
        return results
    subs = []
    for line_no, keys, _span, init in iter_remember_calls(code, "LaunchedEffect"):
        if PD_STORED_KEY not in init or not API_KEY_READ_RE.search(init):
            continue
        flat = keys or ""
        if "instanceId" in flat and PD_REVISION_TOKEN_RE.search(flat):
            subs.append(line_no)
    results.append(("C31", bool(subs),
                    "the stored key is re-read by a LaunchedEffect keyed on the instance id AND "
                    "the config revision (L%s)" % ",".join(str(x) for x in subs) if subs else
                    "no `LaunchedEffect(instanceId, <x>.revision)` assigning `%s` — an external "
                    "key write leaves the previous key on screen and Edit → Save writes it back "
                    "over the newer one" % PD_STORED_KEY))
    return results

def _resolve_expr(expr, decls, depth=0, seen=None):
    """[expr] joined with the file-local declaration tails of the names it uses,
    so a decision expressed through a local reads as one expression."""
    if not expr or depth >= 2:
        return expr or ""
    seen = seen or set()
    out = [expr]
    for name in set(IDENT_RE.findall(expr)):
        if name in seen:
            continue
        sub = decls.get(name)
        if sub is None:
            continue
        out.append(_resolve_expr(sub, decls, depth + 1, seen | {name}))
    return " ".join(out)

def check_picker_search_gating(code):
    """C32 [deep-audit-1041 B-04] — a provider section must not render collapsed
    while the picker's search box holds a query.

    `collapsedInstanceIds` seeds to the full instance set, so a section that
    matches the query renders its single collapsed summary row and hides every
    other hit behind a chevron. Two accepted spellings of the decision:

    - conjunctive gate: the collapse read is conjoined (`&&`) with a NO-QUERY
      test (`<searchBox>.isEmpty()` / `.isBlank()`). Polarity is graded: a
      `… && <searchBox>.isNotEmpty()` reads the query but inverts the guard and
      is a red.
    - derived set: the expression yields `emptySet()` for the searching branch
      (the shared ModelEntryPicker's spelling), which requires the query to
      appear in the resolved expression.

    Fail-closed when the render decision is gone (the picker was reworked —
    re-derive this criterion)."""
    results = []
    m = IS_COLLAPSED_DECL_RE.search(code)
    if not m:
        results.append(("C32", False,
                        "no `val isCollapsed = …` section decision (fail-closed: the picker "
                        "render loop was reworked — re-derive this criterion)"))
        return results
    expr = statement_tail(code, m.end())
    if not expr.strip():
        results.append(("C32", False,
                        "`val isCollapsed = ` carries no expression (fail-closed)"))
        return results
    if search_empty_tests(expr) and "&&" in expr:
        results.append(("C32", True,
                        "the collapse read is gated on the empty-query test: `%s`"
                        % " ".join(expr.split())[:110]))
        return results
    resolved = _resolve_expr(expr, value_sources(code))
    if "emptySet()" in resolved and mentions_search(resolved):
        results.append(("C32", True,
                        "the collapse decision branches on the query and yields emptySet() while "
                        "searching: `%s`" % " ".join(resolved.split())[:110]))
        return results
    results.append(("C32", False,
                    "the collapse read `%s` never consults the search box — sections stay "
                    "collapsed while searching, so every hit past the first one is hidden"
                    % " ".join(expr.split())[:110]))
    return results

# ------------------------------------------- capability single source [dv22] --
# Reads of "what can this model do" that must never come off a raw entry.model.
CAP_BAN_RES = (
    (re.compile(r"\.model\s*\.\s*(?:inputModalities|outputModalities)\b"),
     "modalities read straight off `.model`"),
    (re.compile(r"\.model\s*\?\.\s*(?:inputModalities|outputModalities)\b"),
     "modalities read straight off `?.model`"),
    (re.compile(r"modalityBadges\s*\(\s*[\w.]+\s*\.model\s*\)"),
     "modalityBadges on the raw model"),
    (re.compile(r"modalityFilter\s*\.\s*matches\s*\(\s*[\w.]+\s*\.model\s*\)"),
     "modalityFilter on the raw model"),
    (re.compile(r"\.model\s*\?\.\s*let\s*\(\s*::\s*nonTextLabelFor"),
     "non-text label on the raw model"),
)
# a local that captures a raw model, then reads capability off the LOCAL
CAP_LOCAL_RE = re.compile(r"\bval\s+(\w+)\s*=\s*[\w.]+\s*\.model\b")
CAP_READ_RE = re.compile(r"\b(?:inputModalities|outputModalities|modalityBadges|nonTextLabelFor)\b")
SINGLE_SOURCE_RE = re.compile(r"\b(?:effectiveModel|enrichModel)\s*\(")


def _member_block(code, start):
    """The statement that resolves the member id mentioned at `start`.

    Statements here come in two shapes and BOTH must be captured whole:
        for (x in group.memberEntryIds) { … }
        val names = group.memberEntryIds           <- fluent chain, the lambda
            .mapNotNull { x -> … }                    opens on the NEXT line
    so "walk to the next `{`" is not enough (a newline intervenes) and "stop at
    the first newline" is not either (it cuts the chain in half, and the whole
    point is to see whether the body resolves through the System source).

    Rule: from the mention, scan to the first line that is BOTH at bracket depth
    0 AND indented no deeper than the mention's own line. That ends the
    statement for a chain, a loop body, a `remember { … }` wrapper, or an
    assignment, while keeping nested lambdas and `withContext` blocks inside."""
    line_start = code.rfind("\n", 0, start) + 1
    indent = 0
    while line_start + indent < len(code) and code[line_start + indent] == " ":
        indent += 1

    depth = 0
    i = start
    while i < len(code):
        ch = code[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:                 # the enclosing block already closed
                return code[start:i]
        elif ch == "\n" and depth == 0:
            nxt = code[i + 1:]
            nxt_indent = len(nxt) - len(nxt.lstrip(" "))
            if not nxt.strip():
                i += 1
                continue
            if nxt_indent <= indent:      # a sibling statement starts here
                return code[start:i]
        i += 1
    return code[start:]


# Positive evidence that this site is about the MEMBER ITSELF (its identity,
# its name, its capability, whether it is usable) rather than one of its
# scalar attributes. Only the former can be wrong about a System voice entry:
# such an entry is a real member with a name and a modality, but it has no
# thinking level / context window, so a roll-up over those MUST skip it.
_MEMBER_DISPLAY_SIGNALS = (
    "SystemVoiceEntries.",      # already routed through the single source
    "displayName",
    "modalities",
    "Modality",
    "isEnabledGroupMember",
    "isKnownGroupMemberId",
    "INSTANCE_ID",
    ".count {",
    ".any {",
    # [dv24/B-02] the rest of the lookup spellings. The criterion's own docstring
    # already promised these ("a lookup (`find`/`firstOrNull`/`any`/`count`)"),
    # but only `any`/`count` were actually listed — so a site that resolved
    # through `find { }`, `firstOrNull { }`, `firstNotNullOfOrNull`,
    # `mapNotNull { }` or the `::entryById` method reference was graded as
    # "no resolution here" and skipped. The chat model picker was skipped on
    # exactly those spellings while resolving three ways to null.
    ".find {",
    ".firstOrNull {",
    ".firstNotNullOfOrNull",
    ".mapNotNull {",
    "::entryById",
)
# A numeric roll-up over a per-member scalar (max thinking level, token window …)
# is a different question and is out of scope by design.
_AGGREGATE_REDUCERS = (
    ".minByOrNull", ".maxByOrNull", ".minOf", ".maxOf", ".sumOf", ".average",
)
# A block that only copies / collects ids performs no resolution; nothing to grade.
_COLLECT_ONLY = re.compile(
    r"^\s*(?:for\s*\(\s*\w+\s+in\s+[\w.]*memberEntryIds\s*\)\s*\{\s*\}\s*"
    r"|[\w.]*memberEntryIds\s*\.\s*(?:toMutableList|toList|toSet|size|isEmpty|isNotEmpty|indices|forEachIndexed)\b)")


def _is_member_resolution_site(block):
    """Does this `memberEntryIds` block turn an id into something ABOUT the member?

    The criterion grades 「resolved through one source」, so it only applies where a
    resolution actually happens. A block that merely copies the id list
    (`memberEntryIds.toMutableList()`) or counts its length has no resolution to
    be wrong about, and grading it would force a meaningless SystemVoiceEntries
    import into files that never look a member up.

    Positive evidence only — a projection (`.model`), a lookup
    (`find`/`firstOrNull`/`any`/`count`), a null-test on one, or an existing
    single-source call. That keeps the criterion honest on the surfaces it is
    registered for, all of which resolve members for DISPLAY."""
    if _COLLECT_ONLY.match(block):
        return False
    # A numeric roll-up is out of scope EVEN when it is spelled with a lookup:
    # the reducers are checked FIRST, because `memberEntryIds.mapNotNull { … }
    # .minByOrNull { … }` now matches the `.mapNotNull {` display signal while
    # remaining, correctly, an aggregate that must skip System entries (they
    # have no thinking level / context window).
    if any(tok in block for tok in _AGGREGATE_REDUCERS):
        return False
    return any(tok in block for tok in _MEMBER_DISPLAY_SIGNALS)


def check_group_member_resolution(code):
    """C34 [deep-audit-1043 B-06] — a group's member ids are resolved through
    ONE source, so a System voice member is never silently dropped.

    The bug: every reader of `ModelGroup.memberEntryIds` re-derived
    "persisted entry, else the synthesized System entry". The readers that
    remembered the fallback worked; the ones that did not resolved a System
    member to null. A DEFAULT Voice Input/Output group contains ONLY System
    sentinels (that is what makes it work with zero configuration), so those
    readers rendered it as 「No usable models」 in red, with no modality badges,
    an empty member preview and a 「0 models」 count. Five such readers existed
    at once — the same decision re-derived five times, one of them wrong.

    Graded as the DECISION — "every member-resolution site consults the System
    source" — per site, not per file. Both accepted spellings qualify:
      - `SystemVoiceEntries.resolveIn(entries, id)`  (what the fix uses), and
      - `entries.find { it.id == id } ?: SystemVoiceEntries.resolve(id)`
        (what the already-correct readers do).
    A `memberEntryIds` block with no SystemVoiceEntries reference at all is the
    defect, whichever spelling the rest of the file uses.

    Fail-closed: a surface that no longer reads `memberEntryIds` means the
    structure was reworked — RED, re-derive the criterion."""
    if "memberEntryIds" not in code:
        return [("C34", False,
                 "this surface no longer reads `memberEntryIds` — fail-closed, "
                 "re-derive this criterion instead of letting it pass vacuously")]
    bad = []
    sites = 0
    skipped = 0
    pos = code.find("memberEntryIds")
    while pos >= 0:
        site_pos = pos
        block = _member_block(code, site_pos)
        pos = code.find("memberEntryIds", site_pos + 1)
        if not _is_member_resolution_site(block):
            skipped += 1
            continue
        sites += 1
        if "SystemVoiceEntries." not in block:
            bad.append(code.count("\n", 0, site_pos) + 1)
    if not sites:
        # Reads member ids, but only feeds a per-member scalar roll-up (a max
        # thinking level, a token window …). System voice entries are real
        # members but have no such attribute, so skipping them there is the
        # correct behaviour, not a dropped member.
        return [("C34", True,
                 "reads `memberEntryIds` but performs no member resolution "
                 "(%d aggregate-only site(s)) — nothing to grade" % skipped)]
    return [("C34", not bad,
             "%d member-resolution site(s), all consult the System single source "
             "(%d aggregate-only site(s) out of scope)"
             % (sites, skipped) if not bad else
             "member-resolution without the System fallback at line(s) %s — a System "
             "voice member resolves to null there, so a default Voice group renders "
             "empty / 「No usable models」" % ", ".join(str(x) for x in bad))]


# --- [dv25/B-04] C35 / C36: the ACTIVE entry, and the elvis that ate the if ---

# A lookup INTO the persisted list. Deliberately keyed on `modelEntries` only: a
# group member id may also be looked up in a local `entries` list already
# filtered for a provider section, and that shape is not this decision.
_PERSISTED_LOOKUP_RE = re.compile(
    r"\bmodelEntries\s*\.\s*"
    r"(?:find|findLast|first|firstOrNull|lastOrNull|filter|filterNotNull|singleOrNull|any|none)\b")
# …and the lookup is ID-KEYED (not, say, a per-provider filter).
_ID_KEY_RE = re.compile(r"\bit\.(?:id|uuid)\s*[!=]=")
_ELVIS_SWALLOW_RE = re.compile(r"\belse\s+null\s*\?:")

# Trailing tokens that make the next line part of this logical statement.
_CONT_TAIL = ("(", "{", ",", "=", "->", "?.", ".", "&&", "||", "?:", "+", "|", ":")
# Leading tokens that continue the previous line.
_CONT_HEAD = ("?:", ".", "?.", "->", "&&", "||", ")", "}", ",")


def _statement_around(code, pos):
    """Offsets (start, end) of the LOGICAL statement containing `pos`.

    [_member_block] walks FORWARD from a mention, which is enough when the
    mention opens the statement — and wrong for every shape where the token sits
    in the middle of a call:
    `pickerHeaderEntry(activeEntryId = activeEntryId, …) { … }` starts one line
    up, and a forward walk from the mention cuts the statement in half (the
    mention's own indent is deeper than the call's, so the very next line looks
    like a sibling) and hides the single-source call on the line below. So: walk
    BACK over continuation lines, then forward.

    A line continues the previous one when it starts with an operator / `.` /
    `)`, or the previous line ends with one, or the previous line leaves a
    bracket open. Comments are already stripped by [strip_comments]."""
    lines = code.split("\n")
    idx = code.count("\n", 0, pos)
    lo = idx
    while lo > 0:
        prev = lines[lo - 1].rstrip()
        cur = lines[lo].lstrip()
        if not prev.strip():
            break
        if (prev.endswith(_CONT_TAIL) or cur.startswith(_CONT_HEAD)
                or prev.count("(") + prev.count("[") + prev.count("{")
                > prev.count(")") + prev.count("]") + prev.count("}")):
            lo -= 1
            continue
        break
    hi = idx
    depth = 0
    while hi < len(lines):
        line = lines[hi]
        depth += (line.count("(") + line.count("[") + line.count("{")) \
            - (line.count(")") + line.count("]") + line.count("}"))
        if hi + 1 < len(lines):
            nxt = lines[hi + 1].lstrip()
            if depth > 0 or line.rstrip().endswith(_CONT_TAIL) or nxt.startswith(_CONT_HEAD):
                hi += 1
                continue
        break
    start = len("\n".join(lines[:lo])) + (1 if lo else 0)
    end = start + len("\n".join(lines[lo:hi + 1]))
    return start, end


def check_active_entry_single_source(code):
    """C35 [dv25/B-04] — the SELECTED group's active entry resolves through the
    same single source as one of its members.

    The bug: the picker header looked the active entry up in
    `config.modelEntries` alone, two lines above a `SystemVoiceEntries.resolveIn`
    for the very next member. A default Voice Input/Output group is built from
    System sentinels ONLY, so selecting one resolved its own active entry to
    null — the header fell back to 「N models」 / 「No linked models」 while the
    member rows below it, which did consult the System source, listed the very
    same entry.

    Graded as the DECISION 「an id-keyed lookup into the persisted list consults
    the System source」, per site, not per spelling: `resolveIn(…)` and
    `find { it.id == … } ?: SystemVoiceEntries.resolve(…)` both qualify, and a
    lookup handed to a helper as a lambda counts by the lambda's own body. Sites
    are DERIVED (every id-keyed persisted lookup on the surface), never listed.

    Fail-closed: a surface with neither a persisted lookup nor any
    SystemVoiceEntries reference was reworked and must be re-derived instead of
    passing vacuously."""
    if "SystemVoiceEntries." not in code and not _PERSISTED_LOOKUP_RE.search(code):
        return [("C35", False,
                 "no persisted entry lookup AND no System single source on this "
                 "surface — fail-closed, re-derive this criterion instead of "
                 "letting it pass vacuously")]
    bad = []
    sites = 0
    m = _PERSISTED_LOOKUP_RE.search(code)
    while m:
        start, end = _statement_around(code, m.start())
        stmt = code[start:end]
        m = _PERSISTED_LOOKUP_RE.search(code, end)
        if not _ID_KEY_RE.search(stmt):
            continue
        sites += 1
        if "SystemVoiceEntries." not in stmt:
            bad.append(code.count("\n", 0, start) + 1)
    if not sites:
        return [("C35", True,
                 "no persisted-only id lookup remains — every entry resolution on "
                 "this surface routes through the System single source")]
    return [("C35", not bad,
             "%d id-keyed persisted lookup(s), all consult the System single source"
             % sites if not bad else
             "id-keyed lookup into `modelEntries` without the System source at "
             "line(s) %s — a System voice entry (a default Voice group's ONLY "
             "member) resolves to null there" % ", ".join(str(x) for x in bad))]


def check_elvis_after_else_null(code):
    """C36 [dv25/B-04] — `… else null ?: fallback` binds the fallback to the else
    branch, not to the `if`.

    In Kotlin `?:` binds tighter than the branches of an `if` expression, so
    `if (c) { active } else null ?: firstMember` means
    `if (c) { active } else (null ?: firstMember)`. The `if` branch — the
    SELECTED case, the one whose fallback matters — gets nothing, and the
    fallback is dead code that only runs when `c` is already false (where it is
    exactly what `else null` would have produced anyway).

    Graded as the decision point, not a spelling: the guard is 「a bare `null`
    left operand of an elvis, in an else branch」, which has no correct use. A
    parenthesised `else (null ?: x)` still reds — same precedence surprise, more
    typing."""
    bad = [code.count("\n", 0, m.start()) + 1 for m in _ELVIS_SWALLOW_RE.finditer(code)]
    return [("C36", not bad,
             "no `else null ?:` precedence swallow" if not bad else
             "`else null ?:` at line(s) %s — the elvis attaches to the else branch "
             "alone, so the if branch has no fallback at all and the fallback "
             "below only ever runs when the if already failed"
             % ", ".join(str(x) for x in bad))]


def check_capability_single_source(code):
    """C33 [dv22/B-03] — capability is read from the effective model, one exit.

    The raw `entry.model` is what the user stored, and for every built-in model
    that is an entry with no modality declaration: enrichment from the models.dev
    registry happens on top, and the user's overrides on top of that. A surface
    that reads the raw field therefore shows "text only" for a vision model, and
    a surface that reads it through a local (`val m = entry.model; m.outputModalities`)
    appears routed while being wrong.

    Accepted single sources: `ModelsDevApi.effectiveModel(…)` (enrichment +
    overrides — the renderer's question), `ModelsDevApi.enrichModel(…)` (the
    registry layer under the overrides — the entry editor's question), or a
    file-local alias whose body calls one of them.
    """
    results = []
    bad = []
    for rx, what in CAP_BAN_RES:
        m = rx.search(code)
        if m:
            bad.append(what)
    for m in CAP_LOCAL_RE.finditer(code):
        local = re.escape(m.group(1))
        if (re.search(r"\b%s\s*\.\s*(?:inputModalities|outputModalities)\b" % local, code)
                or re.search(r"modalityBadges\s*\(\s*%s\s*\)" % local, code)
                or re.search(r"nonTextLabelFor\s*\(\s*%s\s*\)" % local, code)):
            bad.append("capability read off the local `%s` captured from `.model`" % m.group(1))
    if bad:
        results.append(("C33", False,
                        "%s — route through ModelsDevApi.effectiveModel(entry)" % "; ".join(sorted(set(bad)))))
        return results
    reads = len(CAP_READ_RE.findall(code))
    if reads and not SINGLE_SOURCE_RE.search(code):
        results.append(("C33", False,
                        "reads capability %d time(s) but never calls effectiveModel/enrichModel "
                        "(fail-closed: the raw entry shows no modalities)" % reads))
        return results
    results.append(("C33", True, "capability reads=%d, single source present" % reads))
    return results


# ---------------------------------- [dv24/B-01] quick test EXECUTION model --
# An argument that hands the RAW entry to the worker. `entry.model.id` and
# `entry.baseModel.id` are string IDS (the voice adapters take them as model /
# voice names) and must keep working — the hierarchy below rejects the model
# CARRIER (and any member of it except `.id`), never the `.id` string.
# [wide-1024] Any member access on the raw carrier EXCEPT the `.id` string is
# the same defect the bare carrier was: `entry.model.copy()` carries no
# modality overrides either. Hierarchy: raw carrier → banned; `<x>.model.id` /
# `<x>.baseModel.id` → legal (a String the voice adapter takes as its name).
_QUICK_TEST_RAW_CARRIER_RE = re.compile(
    r"\bentry\s*\.\s*(?:baseModel|model)\b(?!\s*\.\s*id\b)")
_QUICK_TEST_MODEL_ARG_RE = re.compile(r"(?<![.\w])(?:resolvedModel|model)\s*=(?!=)\s*")
_QUICK_TEST_STRING_ID_RE = re.compile(
    r"^[A-Za-z_]\w*\s*\.\s*(?:baseModel|model)\s*\.\s*id$")
_QUICK_TEST_ENRICHED_RE = re.compile(
    r"\b(?:effectiveModel|enrichedModel)\b"
    r"|ModelsDevApi\s*\.\s*(?:effectiveModel|enrichModel)\s*\("
    r"|providerModelFor\s*\(")
# [batch5 G07-A6] The enriched value must BE the value that crosses the process
# boundary. `effectiveModel.copy(outputModalities = null)` still names the
# enrichment (so the positive regex above matches) while handing the worker a
# model whose modality declaration was stripped — exactly the state the worker
# cannot re-derive (no ModelsDevApi.init there).
_QUICK_TEST_DEENRICH_RE = re.compile(r"\.\s*copy\s*\(")
_QUICK_TEST_NULL_FIELD_RE = re.compile(
    r"\b\w*(?:Modalit\w*|capabilit\w*)\s*=\s*null\b")


def _argument_expression(text, start):
    """Text of the argument starting at [start], up to the top-level comma /
    closing bracket. [batch5] shared bracket-aware boundary scan."""
    end = kflow.expr_end(text, start)
    seg = text[start:end]
    parts = split_top(seg, (",",))          # [batch5] shared top-level split
    return (seg[parts[0][0]:parts[0][1]] if parts else seg).strip()

def _perform_test_span(code):
    """(start, end) of `fun performTest(…)`, or None.

    [batch5] Shared `function_body`; the local copy only knew the brace form, so
    a body expressed as `= withContext(…) { … }` was graded from the signature
    paren onwards (its first `{` was the lambda's, not the body's)."""
    return function_body(code, "performTest")

def check_quick_test_execution(code):
    """C33 [dv24/B-01] — the quick test EXECUTES the model it advertised.

    `applicableKinds` already resolves through `ModelsDevApi.effectiveModel` (it
    has to: that is how the image / speech / transcription cards appear at all),
    and the three execution calls then handed the worker `entry.model` — the
    stored, un-enriched entry. Across the `:modelservice` boundary
    `ModelRequestJson.encodeModel` OMITS a null `outputModalities` and that
    process never calls `ModelsDevApi.init`, so the worker cannot enrich it
    back: Gemini image generation skipped `responseModalities: ["TEXT","IMAGE"]`
    ("No image returned") and the OpenAI-compatible providers were routed to
    `/v1/chat/completions` instead of `/v1/images/generations` (400/404).

    Graded on the DECISION (does `performTest` execute the effective model?),
    not on any single spelling: the raw carrier argument is banned and the
    enrichment must be visible in the same function. String-id arguments
    (`entry.model.id`, `entry.baseModel.id`) stay legal on purpose.

    Fail-closed: a file with no `performTest` means the structure was reworked —
    RED, re-derive the criterion."""
    span = _perform_test_span(code)
    body = code[span[0]:span[1]] if span else None
    if body is None:
        return [("C33", False,
                 "no `fun performTest(` in this surface — fail-closed, re-derive the "
                 "quick-test execution criterion instead of letting it pass vacuously")]
    # [wide-1024] The old ban was a negative lookahead on `model = entry.model`
    # and it had two holes: `entry.model.copy()` slipped past `(?!\s*\.)`, and
    # only the CHAT branch was ever graded by a POSITIVE rule — the image /
    # speech / embedding call sites were defended by "no banned spelling" alone.
    # Both halves are now graded per argument site inside `performTest`.
    bad = [code.count("\n", 0, code.find(body)) + body.count("\n", 0, m.start()) + 1
           for m in _QUICK_TEST_RAW_CARRIER_RE.finditer(body)]
    if bad:
        return [("C33", False,
                 "the quick test hands the worker/voice layer a RAW entry carrier at line(s) "
                 "%s — a `.copy()` / member read of `entry.model` is exactly as un-enriched as "
                 "the bare carrier, and the :modelservice worker cannot re-derive it (no "
                 "ModelsDevApi.init there), so image requests fall back to a chat call"
                 % ", ".join(str(x) for x in bad))]
    bad_args, sites = [], 0
    for m in _QUICK_TEST_MODEL_ARG_RE.finditer(body):
        expr = _argument_expression(body, m.end())
        sites += 1
        if _QUICK_TEST_STRING_ID_RE.match(expr):
            continue  # a String id the voice adapters take as model/voice name
        if _QUICK_TEST_DEENRICH_RE.search(expr) and _QUICK_TEST_NULL_FIELD_RE.search(expr):
            # [batch5 G07-A6] naming the enrichment is not carrying it: the copy
            # hands the worker the same un-enriched model the raw carrier did.
            bad_args.append("%r (enrichment stripped by a derived copy)" % expr[:70])
            continue
        if _QUICK_TEST_ENRICHED_RE.search(expr):
            continue
        bad_args.append("%r" % expr[:70])
    if not sites:
        return [("C33", False,
                 "`performTest` hands no `model = …` / `resolvedModel = …` argument to the "
                 "execution layer — fail-closed, re-derive this criterion instead of letting "
                 "it pass vacuously")]
    if bad_args:
        return [("C33", False,
                 "the quick test executes an argument that carries NO enrichment at "
                 "line(s) %s — the finished-image / voice request leaves this process "
                 "describing the stored entry, not the effective model the card advertised"
                 % ", ".join(bad_args))]
    if not re.search(r"ModelsDevApi\s*\.\s*effectiveModel\s*\(\s*entry\s*\)", body):
        return [("C33", False,
                 "`performTest` never resolves `ModelsDevApi.effectiveModel(entry)` — "
                 "whatever it hands the worker is the un-enriched entry")]
    return [("C33", True,
             "performTest executes the effective model at all %d argument site(s)" % sites)]

# ------------------- [dv27/B-03] System voice member presentation --------
# A System voice entry is a VIRTUAL member: it lives in
# `SystemVoiceIds.BUILTIN_PROVIDER_ID`, is never written to
# `config.modelEntries`, and has no ProviderInstance (the device IS its
# provider). A default Voice Input/Output group is made ENTIRELY of them, so
# every 「member that has no cloud provider behind it」 accident shows up on
# 100% of that screen's rows:
#   • a subtitle that degrades to "" (`instance?.label ?: ""`),
#   • a cost-tier menu whose write `updateEntry` silently drops,
#   • a remove dialog that falls back to the generic 「this model」 copy.
_WRITE_ENTRY_RE = re.compile(r"\bupdateEntr(?:y|ies)\s*\(")
_INSTANCE_LABEL_RE = re.compile(r"\?\s*\.\s*label\b")
_EMPTY_FALLBACK_RE = re.compile(r'\?\:\s*""')
# Any spelling of 「this member is (not) a System voice entry」. The prefix test,
# a decision helper that wraps it, and membership in the persisted list are the
# same decision answered three ways.
_SYSTEM_EXCLUSION_RE = re.compile(
    r"isSystemEntryId|canEditCostTier|\bisSystemEntry\b|"
    r"\b\w+\s+(?:in|!in)\s+[\w.]*modelEntries\b")
# The single sources a member label may come from: the System entry resolver /
# the presentation decisions that wrap it.
_SYSTEM_LABEL_SOURCE_RE = re.compile(
    r"SystemVoiceEntries\s*\.|SystemVoiceMemberPresentation\s*\.")
_FUN_DECL_RE = re.compile(
    r"(?m)^[ \t]*(?:@\w+(?:\([^)\n]*\))?[ \t]*\n[ \t]*)*"
    r"(?:private |internal |public |protected )?(?:suspend )?fun[ \t]+\w+")


def _brace_blocks(code):
    """[(open_idx, close_idx)] for every `{ … }` block. Lone `{`s are ignored."""
    blocks, stack = [], []
    for i, ch in enumerate(code):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            blocks.append((stack.pop(), i))
    return blocks


def _block_header(code, open_idx, limit=700, max_lines=8):
    """The text that INTRODUCES the block at [open_idx] — `if (…)`, `forEach { t ->` …

    Cut at the previous block / statement boundary, then keep only the last few
    non-blank lines: enough to see a condition written across several lines, and
    short enough that a guard three statements up cannot stand in for this one."""
    seg = code[max(0, open_idx - limit):open_idx]
    cut = max(seg.rfind("}"), seg.rfind("{"), seg.rfind(";"))
    if cut >= 0:
        seg = seg[cut + 1:]
    lines = [ln for ln in seg.split("\n") if ln.strip()]
    return "\n".join(lines[-max_lines:])


def _enclosing_headers(code, pos, blocks):
    """Headers of every block that CONTAINS [pos], innermost last."""
    return [_block_header(code, o) for (o, c) in blocks if o < pos < c]


def _enclosing_function(code, pos):
    """Source segment of the function declaration that contains [pos].

    Used to scope a criterion to 「surfaces that talk to provider instances」:
    `?.label` is also the spelling of a slider step's label (ContextLimitSlider),
    which has nothing to do with a ProviderInstance and must not be graded."""
    decls = [m.start() for m in _FUN_DECL_RE.finditer(code)]
    before = [s for s in decls if s < pos]
    if not before:
        return ""
    after = [s for s in decls if s > pos]
    return code[before[-1]:after[0] if after else len(code)]


def check_system_voice_cost_tier_write(code):
    """C-SV1 [dv27/B-03] — a member write is guarded against virtual members.

    `ProviderRepository.updateEntry(entry.copy(…))` resolves the entry by id
    inside `config.modelEntries` (`indexOfFirst == -1` → return). For a System
    voice entry that is a SILENT no-op: the menu closes, the tick does not move,
    nothing is reported. The cost-tier rows therefore may only be offered for a
    PERSISTED member — and the guard has to be a real enclosing condition, not a
    comment: the write sits 4 blocks deep (menu lambda → forEach → onClick →
    `if (currentTier != …)`), so a guard that is merely NEAR it does nothing.

    Graded as the decision, per write site: the call must sit inside an
    enclosing block whose header tests System identity — any spelling of the
    single source (`isSystemEntryId`, a decision helper, or `entry in
    …modelEntries`). A local flag used as that test must itself derive from
    `SystemVoiceEntries.isSystemEntryId` in this file: a name is not a derivation.

    Fail-closed: a cost-tier menu with no write left is a rework, not a pass."""
    sites = [m.start() for m in _WRITE_ENTRY_RE.finditer(code)]
    if not sites:
        if "CostTier.entries" in code:
            return [("C-SV1", False,
                     "the cost-tier menu is still rendered but no entry write "
                     "remains — fail-closed, re-derive this criterion")]
        return [("C-SV1", True, "no entry write on this surface — nothing to guard")]
    blocks = _brace_blocks(code)
    bad = []
    guarded_by_flag = False
    for pos in sites:
        headers = _enclosing_headers(code, pos, blocks)
        if any(_SYSTEM_EXCLUSION_RE.search(h) for h in headers):
            guarded_by_flag = guarded_by_flag or any(
                re.search(r"\bisSystemEntry\b", h) for h in headers)
        else:
            bad.append(code.count("\n", 0, pos) + 1)
    if bad:
        return [("C-SV1", False,
                 "entry write(s) at line(s) %s run unguarded for a System voice "
                 "member — `updateEntry` resolves the id inside "
                 "`config.modelEntries`, where a virtual entry is absent, so the "
                 "tap closes the menu and changes nothing"
                 % ", ".join(str(x) for x in bad))]
    if guarded_by_flag and not re.search(
            r"\bisSystemEntry\s*=\s*[^\n]*isSystemEntryId\s*\(", code):
        return [("C-SV1", False,
                 "the write is guarded by a bare `isSystemEntry` flag that this file "
                 "never derives from `SystemVoiceEntries.isSystemEntryId(…)` — the "
                 "guard's name is not the decision")]
    return [("C-SV1", True,
             "%d entry write(s), each inside a block guarded on System identity"
             % len(sites))]


def check_system_voice_member_label(code):
    """C-SV2 [dv27/B-03] — a member's provider label never degrades, and a
    surface that can show a System member labels it through the single source.

    Two assertions, both derived:

    (a) no `?: ""` ANYWHERE on the screen. A user-visible label that falls back
        to the empty string is the defect itself (`Text(instanceLabel ?: "")` left
        every row of a default Voice group with a blank secondary line) and has no
        correct use.

    (b) every provider-label read (`….?.label`) on a surface that lists group
        members must be in a statement that consults the System single source.
        The `instance` lookup is answerable only for a PERSISTED entry; for a
        System member there is no instance at all, so an elvis onto a constant is
        guaranteed to fire on 100% of a default Voice group's rows. Statement
        scope, not spelling: `SystemVoiceEntries.resolveIn`-derived labels and a
        presentation helper that wraps them both qualify."""
    results = []
    empties = [code.count("\n", 0, m.start()) + 1
               for m in _EMPTY_FALLBACK_RE.finditer(code)]
    results.append(("C-SV2a", not empties,
                    "no label falls back to the empty string" if not empties else
                    "label falls back to `\"\"` at line(s) %s — on a System voice "
                    "member that is EVERY row of a default Voice group"
                    % ", ".join(str(x) for x in empties)))
    if "memberEntryIds" not in code:
        results.append(("C-SV2b", True,
                        "no group member list on this surface — nothing to grade"))
        return results
    sites, bad = 0, []
    for m in _INSTANCE_LABEL_RE.finditer(code):
        if ".instances" not in _enclosing_function(code, m.start()):
            # Not a provider-instance surface (e.g. a slider step's `?.label`).
            continue
        start, _end = _statement_around(code, m.start())
        stmt = code[start:_end]
        sites += 1
        if not _SYSTEM_LABEL_SOURCE_RE.search(stmt):
            bad.append(code.count("\n", 0, start) + 1)
    if bad:
        results.append(("C-SV2b", False,
                        "provider-label read at line(s) %s never consults the System "
                        "single source — a System voice member has no provider "
                        "instance, so that label is null on every default Voice row"
                        % ", ".join(str(x) for x in bad)))
    elif not sites:
        results.append(("C-SV2b", False,
                        "the file lists group members but never reads a provider label "
                        "through the System single source (no `….?.label` statement consulting "
                        "SystemVoiceEntries / SystemVoiceMemberPresentation). Either the member "
                        "rows lost their provider label entirely, or the label is read behind an "
                        "`if (instance != null)` guard — which is exactly the shape that leaves "
                        "a System voice member (no instance: the device is its provider) with a "
                        "bare model id while the group detail screen shows 「System · <model>」"))
    else:
        results.append(("C-SV2b", True,
                        "%d provider-label read(s), all through the System single "
                        "source" % sites))
    return results


CHECKERS = (
    (REL_PROVIDER_LIST, check_provider_list),
    (REL_SHEET, check_sheet),
    (REL_PROVIDER_DETAIL, check_provider_detail),
    # [deep-audit-1040 B-07]
    (REL_PROVIDER_DETAIL, check_provider_detail_live_state),
    (REL_CONNECTION, check_connection),
    (REL_ENTRY_DETAIL, check_entry_detail),
    (REL_GROUP_DETAIL, check_group_detail),
    (REL_MOUNT_DETAIL, check_mount_detail),
    (REL_APP_NAV, check_app_navigation),
    (REL_USAGE_STATS, check_usage_stats),
    (REL_THINKING_SECTION, check_thinking_rules),
    (REL_THINKING_RESOLVER, check_thinking_resolver),
    (REL_THINKING_SECTION, check_thinking_dialog_seed),
    (REL_THINKING_EDITOR, check_thinking_editor),
    (REL_ENVVAR_SCREEN, check_envvar_screen),
    (REL_BACKUP_SCREEN, check_webdav_dialog),
    (REL_ADD_MODELS, check_collapsed_screen),
    (REL_AGENT_LOOP_SHEETS, check_collapsed_screen),
    # [dv28/B-07] both criteria already existed and were mounted only on the
    # two screens that were FIXED by dv15/dv27 — the chat model picker, which
    # renders the same list in the same shape, was never wired. This is the
    # mirror of dv27-B3's lesson: 「判据存在 ≠ 判据挂上」. The picker's own
    # collapse seed was filtered on `isEnabled` and its System members lost the
    # provider label, so both criteria go red on the baseline file.
    (REL_MODEL_PICKER_SHEET, check_collapsed_screen),
    (REL_MODEL_ENTRY_PICKER, check_collapsed_helper),
    # da1039-B3 (B-03 / B-04 / B-08)
    (REL_ADD_CUSTOM_MODEL, check_add_custom_model),
    (REL_CONNECTION, check_connection_write_path),
    (REL_ADD_MODELS, check_quick_test_target),
    (REL_AGENT_LOOP_SHEETS, check_quick_test_target),
    # deep-audit-1041 B-01 (credential live source) / B-04 (search gating)
    (REL_PROVIDER_LIST, check_credential_live_source_list),
    (REL_CONNECTION, check_credential_live_source_connection),
    (REL_MODEL_PICKER_SHEET, check_picker_search_gating),
    (REL_MODEL_ENTRY_PICKER, check_picker_search_gating),
    # dv22-B3 (capability single source)
    (REL_MODEL_ENTRY_PICKER, check_capability_single_source),
    (REL_MODEL_PICKER_SHEET, check_capability_single_source),
    (REL_PROVIDER_DETAIL, check_capability_single_source),
    (REL_ENTRY_DETAIL, check_capability_single_source),
    (REL_QUICK_TEST, check_capability_single_source),
    # dv24-B1 (C33) — the quick test must EXECUTE what it advertised
    (REL_QUICK_TEST, check_quick_test_execution),
    (REL_CHAT_SCREEN, check_capability_single_source),
    # dv23-B6 — the group surfaces render member modalities/names too
    (REL_MODEL_GROUPS, check_capability_single_source),
    # dv23-B6 (C34) — group members resolve through ONE source
    (REL_MODEL_GROUPS, check_group_member_resolution),
    (REL_AGENT_LOOP_MODELS, check_group_member_resolution),
    (REL_AGENT_LOOP_SHEETS, check_group_member_resolution),
    # dv24-B2 — the chat model picker + the screen's confirm-dialog resolver
    # were the two surfaces whose resolution spelling the old signal table did
    # not recognise (a default Voice Input/Output group is made ONLY of System
    # sentinels, so both rendered/counted nothing and skipped the non-text
    # confirmation).
    (REL_MODEL_PICKER_SHEET, check_group_member_resolution),
    # dv25-B4 (C35/C36) — this surface also resolves the SELECTED group's
    # ACTIVE entry (the header), and that lookup had drifted off the single
    # source; its `else null ?:` bound the fallback to the else branch alone.
    (REL_MODEL_PICKER_SHEET, check_active_entry_single_source),
    (REL_MODEL_PICKER_SHEET, check_elvis_after_else_null),
    (REL_CHAT_SCREEN, check_group_member_resolution),
    # dv27-B3 — the group detail screen resolves the same members the pickers
    # do, and C35/C36 were only ever mounted on the PICKER surface. 「The
    # criterion exists but was never wired to this file」 is exactly how three
    # System-voice faults survived on the screen the user actually opens.
    (REL_GROUP_DETAIL, check_active_entry_single_source),
    (REL_GROUP_DETAIL, check_elvis_after_else_null),
    (REL_GROUP_DETAIL, check_system_voice_cost_tier_write),
    (REL_GROUP_DETAIL, check_system_voice_member_label),
    # [dv28/B-07] the picker renders the very same member rows (its expanded
    # group section) and answered 「what is this member's provider」 with
    # `if (instance != null)` — never consulting the System source. Mounting the
    # criterion here is half the fix; the other half is RED on the baseline file.
    (REL_MODEL_PICKER_SHEET, check_system_voice_member_label),
)


def run_tree(root):
    results = []
    for rel, fn in CHECKERS:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            results.append(("FILE", False, "target file missing: %s (fail-closed)" % rel))
            continue
        with open(path, "r", encoding="utf-8") as f:
            results.extend(fn(code_of(f.read())))
    all_ok = all(ok for _, ok, _ in results)
    print("provider_remember_key_check — real tree: %s" % ("GREEN" if all_ok else "RED"))
    for cid, ok, detail in results:
        print("  [%s] %s — %s" % (cid, "PASS" if ok else "FAIL", detail))
    return 0 if all_ok else 1


# ---------------------------------------------------------------------------
# Selftest fixtures
# ---------------------------------------------------------------------------

FIX_PROVIDER_LIST_CLEAN = """package com.rikkaminis.app.ui.settings

val instances = config.instances
var apiKeyByInstanceId by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
LaunchedEffect(instances, config.revision) {
    apiKeyByInstanceId = withContext(Dispatchers.IO) {
        instances.associate { it.id to providerRepository.loadApiKey(it.id) }
    }
}
val providerRows: List<ProviderRowData> = remember(instances, config.modelEntries, apiKeyByInstanceId) {
    instances.map { instance -> instance to apiKeyByInstanceId[instance.id] }
}
val reorderableRows = remember(providerRows) { providerRows.distinctBy { it.instance.id } }
onTogglePinned = remember(row.instance.id, row.instance.pinned) {
    { providerRepository.setInstancePinned(row.instance.id, !row.instance.pinned) }
}
onTogglePinned = remember(row.instance.id, row.instance.pinned) {
    { providerRepository.setInstancePinned(row.instance.id, !row.instance.pinned) }
}
"""

FIX_PROVIDER_LIST_NO_MODEL_ENTRIES = FIX_PROVIDER_LIST_CLEAN.replace(
    "remember(instances, config.modelEntries, apiKeyByInstanceId)",
    "remember(instances, apiKeyByInstanceId)")

# --- [deep-audit-1041 B-01] C30 variants: the credential live source --------

# the pre-fix read: the credential is fetched inside the providerRows remember
# (composition phase) even though the producer effect is still there. Teethed on
# the composition-phase arm alone — C30b stays green, so this fixture pins C30a.
FIX_PROVIDER_LIST_SYNC_READ = FIX_PROVIDER_LIST_CLEAN.replace(
    """    instances.map { instance -> instance to apiKeyByInstanceId[instance.id] }""",
    """    instances.map { providerRepository.loadApiKey(it.id) }""")

# the baseline shape: the row snapshot is read inside the providerRows remember
# (composition thread, keyed on neither the credential nor the revision) and no
# producer effect exists at all — both arms red.
FIX_PROVIDER_LIST_PRE_BASELINE = FIX_PROVIDER_LIST_CLEAN.replace(
    """LaunchedEffect(instances, config.revision) {
    apiKeyByInstanceId = withContext(Dispatchers.IO) {
        instances.associate { it.id to providerRepository.loadApiKey(it.id) }
    }
}
""", "").replace(
    "remember(instances, config.modelEntries, apiKeyByInstanceId)",
    "remember(instances, config.modelEntries)").replace(
    "    instances.map { instance -> instance to apiKeyByInstanceId[instance.id] }",
    "    instances.map { providerRepository.loadApiKey(it.id) }")

# the effect still exists but is keyed on the instance list only: an external
# credential write moves `revision`, not `instances`.
FIX_PROVIDER_LIST_NO_REVISION_KEY = FIX_PROVIDER_LIST_CLEAN.replace(
    "LaunchedEffect(instances, config.revision)", "LaunchedEffect(instances)")

# the read is back on whatever thread the effect runs on.
FIX_PROVIDER_LIST_NO_IO_HOP = FIX_PROVIDER_LIST_CLEAN.replace(
    """    apiKeyByInstanceId = withContext(Dispatchers.IO) {
        instances.associate { it.id to providerRepository.loadApiKey(it.id) }
    }""",
    """    apiKeyByInstanceId = instances.associate { it.id to providerRepository.loadApiKey(it.id) }""")

# fail-closed arm: no credential read left in the file at all.
FIX_PROVIDER_LIST_NO_READ = FIX_PROVIDER_LIST_CLEAN.replace(
    """        instances.associate { it.id to providerRepository.loadApiKey(it.id) }""",
    """        instances.associate { it.id to null }""")

FIX_PROVIDER_LIST_NO_PROVIDER_ROWS_KEY = FIX_PROVIDER_LIST_CLEAN.replace(
    "remember(providerRows) { providerRows.distinctBy { it.instance.id } }",
    "remember(instances) { providerRows.distinctBy { it.instance.id } }")

FIX_PROVIDER_LIST_STALE_PINNED_KEY = FIX_PROVIDER_LIST_CLEAN.replace(
    "remember(row.instance.id, row.instance.pinned)",
    "remember(row.instance.id)")

FIX_PROVIDER_LIST_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

FIX_SHEET_CLEAN = """package com.rikkaminis.app.ui.settings

val instance = remember(instanceId, config) { config.instances.firstOrNull { it.id == instanceId } }
"""

FIX_SHEET_NO_CONFIG_KEY = FIX_SHEET_CLEAN.replace(
    "remember(instanceId, config)", "remember(instanceId)")

FIX_SHEET_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""


# --- da1026-C fixtures: the three screens the new criteria guard ----------

# [deep-audit-1040 B-07] The post-fix tail every ProviderDetailScreen fixture
# carries: a live write resolver, the id-held delete target with its
# close-on-vanished effect, and the revision-keyed stored-key re-read. Kept in
# one place so the C7/C11c fixtures and the C26..C29 fixtures can never drift
# apart on the shape the screen is supposed to have.
FIX_PD_LIVE_TAIL = """
fun currentInstance(): ProviderInstance =
    config.instances.find { it.id == instanceId } ?: instance
IconButton(onClick = {
    val live = currentInstance()
    providerRepository.updateInstance(live.copy(isEnabled = true))
    val exported = currentInstance()
    exportProviderInstance(context, providerRepository, exported)
})
var entryToDeleteId by remember { mutableStateOf<String?>(null) }
val entryToDelete = liveEntryToDelete(entryToDeleteId, allEntries)
LaunchedEffect(entryToDeleteId, allEntries) {
    if (entryDeleteTargetVanished(entryToDeleteId, allEntries)) entryToDeleteId = null
}
internal fun entryDeleteTargetVanished(pendingId: String?, entries: List<ModelEntry>): Boolean =
    pendingId != null && liveEntryToDelete(pendingId, entries) == null
LaunchedEffect(instanceId, config.revision) {
    storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
}
onLongClick = { entryToDeleteId = entry.id }
MinisAlertDialog(onDismissRequest = { entryToDeleteId = null })
"""

FIX_PROVIDER_DETAIL_CLEAN = """package com.rikkaminis.app.ui.settings

val allEntries = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }
IconButton(onClick = {
    isRefreshing = true
    scope.launch {
        val result = providerRepository.refreshModels(instance)
        val total = providerRepository.entriesFor(instance.id).size
        Toast.makeText(ctx, refreshResultMessage(result, total, ctx), Toast.LENGTH_SHORT).show()
    }
})
ThinkingRulesSection(instance = currentInstance, providerRepository = providerRepository, modelEntries = allEntries)
""" + FIX_PD_LIVE_TAIL

# the pre-fix shape: toast reads the snapshot captured before the fetch ran
FIX_PROVIDER_DETAIL_STALE = """package com.rikkaminis.app.ui.settings

val allEntries = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }
IconButton(onClick = {
    scope.launch {
        val result = providerRepository.refreshModels(instance)
        Toast.makeText(ctx, refreshResultMessage(result, allEntries.size, ctx), Toast.LENGTH_SHORT).show()
    }
})
ThinkingRulesSection(instance = currentInstance, providerRepository = providerRepository, modelEntries = allEntries)
""" + FIX_PD_LIVE_TAIL

# no snapshot read in the args, but also no fresh read: still a stale count
FIX_PROVIDER_DETAIL_NO_REREAD = """package com.rikkaminis.app.ui.settings

val allEntries = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }
IconButton(onClick = {
    scope.launch {
        val result = providerRepository.refreshModels(instance)
        val total = 0
        Toast.makeText(ctx, refreshResultMessage(result, total, ctx), Toast.LENGTH_SHORT).show()
    }
})
ThinkingRulesSection(instance = currentInstance, providerRepository = providerRepository, modelEntries = allEntries)
""" + FIX_PD_LIVE_TAIL

# the accepted alternative shape: state-backed list re-assigned on the refresh path
FIX_PROVIDER_DETAIL_STATE_BACKED = """package com.rikkaminis.app.ui.settings

var allEntries by remember(instanceId, config) { mutableStateOf(providerRepository.entriesFor(instanceId)) }
IconButton(onClick = {
    scope.launch {
        val result = providerRepository.refreshModels(instance)
        allEntries = providerRepository.entriesFor(instance.id)
        Toast.makeText(ctx, refreshResultMessage(result, allEntries.size, ctx), Toast.LENGTH_SHORT).show()
    }
})
ThinkingRulesSection(instance = currentInstance, providerRepository = providerRepository, modelEntries = allEntries)
""" + FIX_PD_LIVE_TAIL

# [da1028-C] C11c: the section is called without its entry list, so the keys
# inside it can never observe a catalog refresh.
FIX_PROVIDER_DETAIL_NO_SECTION_ARGS = FIX_PROVIDER_DETAIL_CLEAN.replace(
    ", modelEntries = allEntries", "")

FIX_PROVIDER_DETAIL_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# C26 — a write pushes the render-frame object back instead of the live one.
FIX_PROVIDER_DETAIL_SNAPSHOT_WRITE = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "    val live = currentInstance()\n"
    "    providerRepository.updateInstance(live.copy(isEnabled = true))",
    "    providerRepository.updateInstance(instance.copy(isEnabled = true))")

# C26 — the export path serialises the render-frame object.
FIX_PROVIDER_DETAIL_EXPORT_SNAPSHOT = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "    val exported = currentInstance()\n"
    "    exportProviderInstance(context, providerRepository, exported)",
    "    exportProviderInstance(context, providerRepository, instance)")

# C26 — the live resolver itself is gone (fail-closed arm).
FIX_PROVIDER_DETAIL_NO_RESOLVER = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "fun currentInstance(): ProviderInstance =", "fun helperNotTheResolver(): ProviderInstance =")

# C27 — the pre-fix shape: the state holds the long-pressed ModelEntry object.
FIX_PROVIDER_DETAIL_OBJECT_TARGET = (
    FIX_PROVIDER_DETAIL_CLEAN
    .replace("var entryToDeleteId by remember { mutableStateOf<String?>(null) }",
             "var entryToDelete by remember { "
             "mutableStateOf<com.rikkaminis.app.data.model.ModelEntry?>(null) }")
    .replace("onLongClick = { entryToDeleteId = entry.id }",
             "onLongClick = { entryToDelete = entry }")
    .replace("MinisAlertDialog(onDismissRequest = { entryToDeleteId = null })",
             "MinisAlertDialog(onDismissRequest = { entryToDelete = null })"))

# C27 — the row stores something other than the entry identity.
FIX_PROVIDER_DETAIL_NO_ROW_WIRING = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "onLongClick = { entryToDeleteId = entry.id }",
    "onLongClick = { pendingEntryLabel = entry.model.displayName }")

# C28 — the target can vanish and the dialog stays up.
FIX_PROVIDER_DETAIL_NO_CLOSE_EFFECT = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "LaunchedEffect(entryToDeleteId, allEntries) {\n"
    "    if (entryDeleteTargetVanished(entryToDeleteId, allEntries)) entryToDeleteId = null\n"
    "}\n", "")

# C28 — the effect closes on every recomposition instead of on the decision.
FIX_PROVIDER_DETAIL_UNCONDITIONAL_CLOSE = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "    if (entryDeleteTargetVanished(entryToDeleteId, allEntries)) entryToDeleteId = null",
    "    entryToDeleteId = null")

# C29 — the masked key is never re-read after an external credential write.
FIX_PROVIDER_DETAIL_KEY_NO_REVISION = FIX_PROVIDER_DETAIL_CLEAN.replace(
    "LaunchedEffect(instanceId, config.revision) {", "LaunchedEffect(instanceId) {")

FIX_CONNECTION_CLEAN = """package com.rikkaminis.app.ui.settings

fun pendingConnectionFieldWrite(edited: String, stored: String?): String? {
    val normalized = edited.ifBlank { null }
    return if (normalized == stored) null else normalized
}

fun ProviderConnectionScreen(instanceId: String, instance: ProviderInstance) {
    var storedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(instanceId, config.revision) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }
    var customBaseURL by rememberSaveable(instance.id, instance.customBaseURL) { mutableStateOf(instance.customBaseURL ?: "") }
    var customUserAgent by rememberSaveable(instance.id, instance.customUserAgent) { mutableStateOf(instance.customUserAgent ?: "") }

    fun currentInstance(): ProviderInstance =
        config.instances.find { it.id == instanceId } ?: instance

    fun saveBaseURLSettings() {
        val live = currentInstance()
        val pending = pendingConnectionFieldWrite(customBaseURL, live.customBaseURL) ?: return
        providerRepository.updateInstance(live.copy(customBaseURL = pending))
    }

    fun saveUserAgentSetting() {
        val live = currentInstance()
        val pending = pendingConnectionFieldWrite(customUserAgent, live.customUserAgent) ?: return
        providerRepository.updateInstance(live.copy(customUserAgent = pending))
    }

    SingleChoiceSegmentedButtonRow {
        SegmentedButton(onClick = { providerRepository.updateInstance(currentInstance().copy(useResponsesAPI = false)) })
    }
}
"""

FIX_CONNECTION_NO_BASEURL_KEY = FIX_CONNECTION_CLEAN.replace(
    "var customBaseURL by rememberSaveable(instance.id, instance.customBaseURL)",
    "var customBaseURL by rememberSaveable(instance.id)")

FIX_CONNECTION_NO_UA_KEY = FIX_CONNECTION_CLEAN.replace(
    "var customUserAgent by rememberSaveable(instance.id, instance.customUserAgent)",
    "var customUserAgent by rememberSaveable(instance.id)")

FIX_CONNECTION_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# --- [deep-audit-1041 B-01] C31 variants: the connection screen's key re-read

# the pre-fix key set: the credential is read once per instance id, so an
# external write (RPC / import / restore) leaves the old key on screen — and the
# next Edit → Save writes it back over the newer one.
FIX_CONNECTION_KEY_NO_REVISION = FIX_CONNECTION_CLEAN.replace(
    "LaunchedEffect(instanceId, config.revision)", "LaunchedEffect(instanceId)")

# fail-closed arm: no stored-key read left in the file at all.
FIX_CONNECTION_NO_KEY_READ = FIX_CONNECTION_CLEAN.replace(
    """    var storedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(instanceId, config.revision) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }
""", "")

# --- da1039-B3 B-04 variants: the write-path criteria (C22 / C23) ---------

# one write site still pushes the composition-time snapshot
FIX_CONNECTION_SNAPSHOT_WRITE = FIX_CONNECTION_CLEAN.replace(
    "providerRepository.updateInstance(currentInstance().copy(useResponsesAPI = false))",
    "providerRepository.updateInstance(instance.copy(useResponsesAPI = false))")

# the resolver is back to returning the snapshot it captured
FIX_CONNECTION_NO_RESOLVER = FIX_CONNECTION_CLEAN.replace(
    """    fun currentInstance(): ProviderInstance =
        config.instances.find { it.id == instanceId } ?: instance""",
    "    fun currentInstance(): ProviderInstance = instance")

# the helper exists but no longer compares anything (always writes)
FIX_CONNECTION_HELPER_ALWAYS_WRITE = FIX_CONNECTION_CLEAN.replace(
    """    val normalized = edited.ifBlank { null }
    return if (normalized == stored) null else normalized""",
    "    return edited.ifBlank { null }")

# the base-URL saver writes unconditionally again (no dirty decision)
FIX_CONNECTION_NO_DIRTY_GUARD = FIX_CONNECTION_CLEAN.replace(
    """        val live = currentInstance()
        val pending = pendingConnectionFieldWrite(customBaseURL, live.customBaseURL) ?: return
        providerRepository.updateInstance(live.copy(customBaseURL = pending))""",
    """        val live = currentInstance()
        providerRepository.updateInstance(live.copy(customBaseURL = customBaseURL.ifBlank { null }))""")

# the cross-field write comes back (a base-URL blur rewrites the UA field)
FIX_CONNECTION_CROSS_FIELD_WRITE = FIX_CONNECTION_CLEAN.replace(
    "providerRepository.updateInstance(live.copy(customBaseURL = pending))",
    """providerRepository.updateInstance(
            live.copy(customBaseURL = pending, customUserAgent = pending)
        )""")

# --- da1039-B3 B-03 fixtures: the parent-instance read (C20 / C21) --------

FIX_ADD_CUSTOM_MODEL_CLEAN = """package com.rikkaminis.app.ui.settings

fun AddCustomModelScreen(instanceId: String) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }

    if (instance == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    MinisButton(onClick = {
        val entry = ModelEntry(providerInstanceId = instanceId, baseModel = model, isCustom = true)
        scope.launch {
            val parentStillThere = providerRepository.config.value.instances.any { it.id == instanceId }
            if (!parentStillThere) {
                onBack()
                return@launch
            }
            withContext(Dispatchers.IO) { providerRepository.addEntry(entry) }
            onBack()
        }
    })
}
"""

# the pre-fix read: a one-shot snapshot that never recomposes on an external delete
FIX_ADD_CUSTOM_MODEL_SNAPSHOT = FIX_ADD_CUSTOM_MODEL_CLEAN.replace(
    """    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }""",
    "    val instance = providerRepository.instance(instanceId)")

# read is fixed, the write-time re-check is gone
FIX_ADD_CUSTOM_MODEL_NO_LIVE_GUARD = FIX_ADD_CUSTOM_MODEL_CLEAN.replace(
    """            val parentStillThere = providerRepository.config.value.instances.any { it.id == instanceId }
            if (!parentStillThere) {
                onBack()
                return@launch
            }
""",
    "")

FIX_ADD_CUSTOM_MODEL_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

FIX_ENTRY_DETAIL_CLEAN = """package com.rikkaminis.app.ui.settings

fun ModelEntryDetailScreen(instanceId: String, entryId: String) {
    var modelId by remember(entry.id, baseModel.id) { mutableStateOf(baseModel.id) }
    var displayName by remember(entry.id, baseModel.displayName, overrides.displayName) {
        mutableStateOf(overrides.displayName ?: baseModel.displayName)
    }
    var isHidden by remember(entry.id, entry.isHidden) { mutableStateOf(entry.isHidden) }
    var showQuickTest by remember(entry.id) { mutableStateOf(false) }
    var imageInput by remember(entry.id, effectiveInput) { mutableStateOf("image" in effectiveInput) }
}
"""

FIX_ENTRY_DETAIL_KEYLESS = FIX_ENTRY_DETAIL_CLEAN.replace(
    "var isHidden by remember(entry.id, entry.isHidden)",
    "var isHidden by remember")

# looks keyed, but the key is the screen's own argument: it never changes when
# the entry's content changes, so the freeze survives
FIX_ENTRY_DETAIL_WRONG_KEY = FIX_ENTRY_DETAIL_CLEAN.replace(
    "var isHidden by remember(entry.id, entry.isHidden)",
    "var isHidden by remember(entryId)")

FIX_ENTRY_DETAIL_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# --- da1027-E fixtures: the two detail screens + the usage route ----------

FIX_GROUP_DETAIL_CLEAN = """package com.rikkaminis.app.ui.settings

fun ModelGroupDetailScreen(groupId: String) {
    var name by remember(group.id, group.name) { mutableStateOf(group.name) }
    var strategy by remember(group.id, group.strategy) { mutableStateOf(group.strategy) }
    var fallbackStrategy by remember(group.id, group.fallbackStrategy) {
        mutableStateOf(group.fallbackStrategy)
    }
    var defaultThinkingLevel by remember(group.id, group.defaultThinkingLevel) {
        mutableStateOf(group.defaultThinkingLevel)
    }
    var contextLimitTokens by remember(group.id, group.contextLimitTokens) {
        mutableStateOf(group.contextLimitTokens)
    }
    var memberIds by remember(group.memberEntryIds) { mutableStateOf(group.memberEntryIds.toList()) }
    var entryToRemove by remember { mutableStateOf<String?>(null) }
    val groupMaxThinkingLevel = remember(group.memberEntryIds, config.modelEntries) {
        group.memberEntryIds
            .filterNot { SystemVoiceEntries.isSystemEntryId(it) }
            .mapNotNull { entryId -> SystemVoiceEntries.resolveIn(config.modelEntries, entryId) }
    }
    val entry = SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    val instance = config.instances.find { it.id == entry.providerInstanceId }
    val instanceLabel = SystemVoiceMemberPresentation.memberSubtitle(
        instanceLabel = instance?.label,
        isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        systemLabel = systemLabel,
    )
    val removingEntry = SystemVoiceEntries.resolveIn(config.modelEntries, removingId)
    val removingName = SystemVoiceMemberPresentation.removalDisplayName(
        entryName = removingEntry?.model?.displayName,
        fallback = removingFallback,
    )
    if (
        SystemVoiceMemberPresentation.canEditCostTier(
            isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        )
    ) {
        providerRepository.updateEntry(entry.copy(costTier = tier.value))
    }
}
"""

# the pre-fix shape: the editor is keyed on the identity only, so an external
# write to `name` left the old text on screen for the focus-loss save to undo
FIX_GROUP_DETAIL_NO_NAME_KEY = FIX_GROUP_DETAIL_CLEAN.replace(
    "remember(group.id, group.name) { mutableStateOf(group.name) }",
    "remember(group.id) { mutableStateOf(group.name) }")

# [dv27/B-03] the three faults this wave fixed, one fixture per criterion:
#   C35  — the remove dialog resolved the member id in `config.modelEntries` only
#   C-SV1 — the cost-tier write ran unguarded for a virtual System member
#   C-SV2a — a member label fell back to the empty string
#   C-SV2b — the subtitle read `instance?.label` with no System source at all
#   C-SV1 — a guard that tests a local flag nobody derives from the prefix test
_SUBTITLE_CALL = """    val instanceLabel = SystemVoiceMemberPresentation.memberSubtitle(
        instanceLabel = instance?.label,
        isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        systemLabel = systemLabel,
    )"""
_COST_TIER_GUARD = """    if (
        SystemVoiceMemberPresentation.canEditCostTier(
            isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        )
    ) {"""

FIX_GROUP_DETAIL_REMOVE_PERSISTED_ONLY = FIX_GROUP_DETAIL_CLEAN.replace(
    "    val removingEntry = SystemVoiceEntries.resolveIn(config.modelEntries, removingId)",
    "    val removingEntry = config.modelEntries.find { it.id == removingId }")

FIX_GROUP_DETAIL_COST_TIER_UNGUARDED = FIX_GROUP_DETAIL_CLEAN.replace(
    _COST_TIER_GUARD, "    if (tierSelectable) {")

FIX_GROUP_DETAIL_SUBTITLE_EMPTY = FIX_GROUP_DETAIL_CLEAN.replace(
    "    val removingName",
    '    val extraEmpty = otherLabel ?: ""\n    val removingName')

FIX_GROUP_DETAIL_SUBTITLE_NO_SYSTEM = FIX_GROUP_DETAIL_CLEAN.replace(
    _SUBTITLE_CALL,
    "    val instanceLabel = instance?.label ?: systemLabel")

FIX_GROUP_DETAIL_FLAG_NOT_DERIVED = FIX_GROUP_DETAIL_CLEAN.replace(
    "isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),",
    "isSystemEntry = systemMember,")

FIX_GROUP_DETAIL_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

FIX_MOUNT_DETAIL_CLEAN = """package com.rikkaminis.app.ui.settings

fun MountDetailScreen(mountId: String) {
    val entry = entries.firstOrNull { it.id == mountId }
    var allowWrite by remember(entry.id, entry.userAllowWrite) { mutableStateOf(entry.userAllowWrite) }
    var showUnmountConfirm by remember { mutableStateOf(false) }
    val canSave = allowWrite != entry.userAllowWrite
}
"""

FIX_MOUNT_DETAIL_NO_WRITE_KEY = FIX_MOUNT_DETAIL_CLEAN.replace(
    "remember(entry.id, entry.userAllowWrite)", "remember(entry.id)")

FIX_MOUNT_DETAIL_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

FIX_APPNAV_CLEAN = """package com.rikkaminis.app.ui.navigation

composable(Routes.USAGE_STATS) {
    val providerConfig by providerRepository.config.collectAsState()
    UsageStatsScreen(
        chatDao = chatRepository.dao,
        providerConfig = providerConfig,
        onBack = { navController.safePopBackStack() },
    )
}

composable(Routes.APPEARANCE) {
    AppearanceScreen(onBack = { navController.safePopBackStack() })
}
"""

# the pre-fix shape: a one-shot snapshot; the config flow never recomposes the
# route, so the screen keeps whatever config existed when it was first opened
FIX_APPNAV_SNAPSHOT = """package com.rikkaminis.app.ui.navigation

composable(Routes.USAGE_STATS) {
    UsageStatsScreen(
        chatDao = chatRepository.dao,
        providerConfig = providerRepository.config.value,
        onBack = { navController.safePopBackStack() },
    )
}

composable(Routes.APPEARANCE) {
    AppearanceScreen(onBack = { navController.safePopBackStack() })
}
"""

FIX_APPNAV_MISSING_ANCHOR = """package com.rikkaminis.app.ui.navigation

composable(Routes.APPEARANCE) {
    AppearanceScreen(onBack = { navController.safePopBackStack() })
}
"""

FIX_USAGE_STATS_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
fun UsageStatsScreen(chatDao: ChatDao, providerConfig: ProviderConfig? = null, onBack: () -> Unit) {
    var range by remember { mutableStateOf(UsageRange.ALL) }
    LaunchedEffect(range, providerConfig) {
        val (total, groups) = withContext(Dispatchers.Default) {
            computeUsageStats(range, chatDao, providerConfig)
        }
        grandTotal = total
    }
}
"""

FIX_USAGE_STATS_NO_CONFIG_KEY = FIX_USAGE_STATS_CLEAN.replace(
    "LaunchedEffect(range, providerConfig) {", "LaunchedEffect(range) {")

FIX_USAGE_STATS_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

@Composable
fun UsageStatsScreen(chatDao: ChatDao, providerConfig: ProviderConfig? = null) {
    var range by remember { mutableStateOf(UsageRange.ALL) }
}
"""

def _replace_exactly(src, old, new, count=1):
    """`src.replace` that REFUSES to no-op.

    A fixture built by mutating a clean sample is only a test while the mutation
    lands: when the sample is reworked the `old` string disappears, `.replace`
    returns the clean text unchanged, and the fixture quietly becomes "the clean
    sample must be green" — an arm that has stopped testing anything yet can
    never fail. Same class of self-injury as the probe that mutates a file path
    no longer present. Callers get an exception at import time instead."""
    if src.count(old) != count:
        raise AssertionError(
            "fixture drift: expected %d occurrence(s) of %r, found %d — the clean "
            "sample was reworked, re-derive this fixture" % (count, old, src.count(old)))
    return src.replace(old, new)


# --- da1028-C fixtures: ThinkingRulesSection derived keys ------------------

FIX_THINKING_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
fun ThinkingRulesSection(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    modelEntries: List<ModelEntry> = emptyList(),
) {
    var reloadKey by remember { mutableStateOf(0) }
    var defaultsExpanded by remember { mutableStateOf(false) }
    val rulesRevision by ThinkingRuleResolver.customRulesRevision.collectAsState()
    var customRulesAndIds by remember { mutableStateOf(emptyList<ThinkingRule>() to emptyList<String>()) }
    var rulesLoaded by remember(instance.id) { mutableStateOf(false) }
    LaunchedEffect(instance.id, reloadKey, rulesRevision) {
        customRulesAndIds = withContext(Dispatchers.IO) {
            providerRepository.thinkingRulesWithIds(instance.id)
        }
        rulesLoaded = true
    }
    var builtIns by remember { mutableStateOf(emptyList<ThinkingRule>()) }
    LaunchedEffect(instance.id, instance.customBaseURL, instance.appendV1Suffix, modelEntries, reloadKey) {
        builtIns = withContext(Dispatchers.IO) {
            providerRepository.builtInThinkingRulesForDisplay(instance.id)
        }
    }
    var sampleModelId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(instance.id, modelEntries, reloadKey) {
        sampleModelId = withContext(Dispatchers.IO) {
            providerRepository.firstModelId(instance.id)
        }
    }
    // da1033-I07: the dialog edits the LIVE rule, not the tapped snapshot.
    val editorLiveIndex = editorRequest?.existingId?.let { customIds.indexOf(it) } ?: -1
    LaunchedEffect(editorRequest?.existingId, editorLiveIndex) {
        val req = editorRequest
        if (req?.existingId != null && editorLiveIndex < 0) editorRequest = null
    }
    editorRequest?.let { req ->
        val liveSeed = if (req.existingId != null && !req.isNew && editorLiveIndex >= 0) {
            customRules[editorLiveIndex]
        } else {
            req.seed
        }
        ThinkingRuleEditorDialog(
            request = req.copy(seed = liveSeed),
            onDismiss = { editorRequest = null },
            onSave = { rule ->
                providerRepository.saveThinkingRule(instance.id, rule, id = req.existingId)
                editorRequest = null
                reloadKey++
            },
        )
    }
    // New-rule path: no live id to resolve — seeds itself.
    editorRequest = ThinkingRuleEditorRequest(
        existingId = null,
        seed = ThinkingRule(kind = ThinkingRule.Kind.CUSTOM, label = ""),
        isNew = true,
    )
    val customRules = customRulesAndIds.first
    val customIds = customRulesAndIds.second
}
"""

# The built-in / preview derivations moved onto producer effects with B-02, so
# these four fixtures mutate the EFFECT key list now. All four go through
# `_replace_exactly`: a silent no-op here would turn each arm into "the clean
# sample is green" and would never fail again (the C11a/C11b variant of it bit
# this very edit — selftest caught all four at once).
FIX_THINKING_NO_BASEURL_KEY = _replace_exactly(
    FIX_THINKING_CLEAN,
    "LaunchedEffect(instance.id, instance.customBaseURL, instance.appendV1Suffix, modelEntries, reloadKey) {",
    "LaunchedEffect(instance.id, instance.appendV1Suffix, modelEntries, reloadKey) {")

FIX_THINKING_BUILTINS_NO_MODEL_ENTRIES = _replace_exactly(
    FIX_THINKING_CLEAN,
    "LaunchedEffect(instance.id, instance.customBaseURL, instance.appendV1Suffix, modelEntries, reloadKey) {",
    "LaunchedEffect(instance.id, instance.customBaseURL, instance.appendV1Suffix, reloadKey) {")

FIX_THINKING_SAMPLE_NO_MODEL_ENTRIES = _replace_exactly(
    FIX_THINKING_CLEAN,
    "LaunchedEffect(instance.id, modelEntries, reloadKey) {",
    "LaunchedEffect(instance.id, reloadKey) {")

# Keyless is not reachable on a LaunchedEffect (its key list is the whole
# argument), so the "cannot tell its sources apart" arm is now the SAME key list
# with the source dropped — the effect still runs, but only once.
FIX_THINKING_SAMPLE_KEYLESS = _replace_exactly(
    FIX_THINKING_CLEAN,
    "LaunchedEffect(instance.id, modelEntries, reloadKey) {",
    "LaunchedEffect(Unit) {")

FIX_THINKING_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# Precision control: touching a remember this criterion does NOT own (a Room
# loader and two pure-UI flags, one keyless) must leave the gate green —
# otherwise "any keyless remember reds" would masquerade as coverage.
# da1033-I07 (C15) fixtures.

# The pre-fix dialog region: a snapshot request, no live lookup, no close effect.
FIX_THINKING_PREFIX_DIALOG = FIX_THINKING_CLEAN.replace(
    """    val editorLiveIndex = editorRequest?.existingId?.let { customIds.indexOf(it) } ?: -1
    LaunchedEffect(editorRequest?.existingId, editorLiveIndex) {
        val req = editorRequest
        if (req?.existingId != null && editorLiveIndex < 0) editorRequest = null
    }
    editorRequest?.let { req ->
        val liveSeed = if (req.existingId != null && !req.isNew && editorLiveIndex >= 0) {
            customRules[editorLiveIndex]
        } else {
            req.seed
        }
        ThinkingRuleEditorDialog(
            request = req.copy(seed = liveSeed),""",
    """    editorRequest?.let { req ->
        ThinkingRuleEditorDialog(
            request = req,""")

FIX_THINKING_DIALOG_STALE_REQUEST = FIX_THINKING_CLEAN.replace(
    "request = req.copy(seed = liveSeed),", "request = req,")

FIX_THINKING_NO_CLOSE_EFFECT = FIX_THINKING_CLEAN.replace(
    """    LaunchedEffect(editorRequest?.existingId, editorLiveIndex) {
        val req = editorRequest
        if (req?.existingId != null && editorLiveIndex < 0) editorRequest = null
    }
""", "")

# Seeded from the live list but NOT selected by the captured id: the dialog would
# edit whichever rule happens to be first.
FIX_THINKING_SEED_NOT_BY_ID = FIX_THINKING_CLEAN.replace(
    """        val liveSeed = if (req.existingId != null && !req.isNew && editorLiveIndex >= 0) {
            customRules[editorLiveIndex]
        } else {
            req.seed
        }""",
    "        val liveSeed = customRules.first()")

# Precision: the NEW-rule path seeds itself by design (no live id to resolve) —
# a criterion that graded every `ThinkingRuleEditorRequest(` construction would
# red here, so this fixture proves C15 only grades the dialog call site.
FIX_THINKING_NEW_RULE_SEED = FIX_THINKING_CLEAN.replace(
    'seed = ThinkingRule(kind = ThinkingRule.Kind.CUSTOM, label = ""),',
    'seed = builtInRule.copy(kind = ThinkingRule.Kind.CUSTOM, label = "Copy of " + builtInRule.label),')

FIX_THINKING_PRECISION_UNRELATED = FIX_THINKING_CLEAN.replace(
    "    var defaultsExpanded by remember { mutableStateOf(false) }",
    "    var defaultsExpanded by remember { mutableStateOf(false) }\n"
    "    var editorTick by remember { mutableStateOf(0) }",
)

# --- da1030-C ISSUE-05 fixtures: C12 / C13 --------------------------------

# --- deep-audit-1043 B-02: the two new arms of C12 --------------------------
# Arm (b) moved to the producer effect, so its negative fixture must move too —
# a `.replace` aimed at the old `remember(instance.id, reloadKey, rulesRevision)`
# would silently no-op (the string is gone) and the "missing revision key" arm
# would be green without testing anything. The assert below is what catches
# that class of drift.
FIX_THINKING_NO_REVISION_KEY = _replace_exactly(
    FIX_THINKING_CLEAN,
    "LaunchedEffect(instance.id, reloadKey, rulesRevision) {",
    "LaunchedEffect(instance.id, reloadKey) {")

FIX_THINKING_NO_SUBSCRIPTION = FIX_THINKING_CLEAN.replace(
    "    val rulesRevision by ThinkingRuleResolver.customRulesRevision.collectAsState()\n", "")

# Arm (c): back to the pre-fix carrier — keyed correctly, still blocking. Only
# C12 may move; C11a/C11b stay green because the other two effects are intact.
FIX_THINKING_SYNC_LOADER = _replace_exactly(
    FIX_THINKING_CLEAN,
    """    var customRulesAndIds by remember { mutableStateOf(emptyList<ThinkingRule>() to emptyList<String>()) }
    var rulesLoaded by remember(instance.id) { mutableStateOf(false) }
    LaunchedEffect(instance.id, reloadKey, rulesRevision) {
        customRulesAndIds = withContext(Dispatchers.IO) {
            providerRepository.thinkingRulesWithIds(instance.id)
        }
        rulesLoaded = true
    }""",
    """    val customRulesAndIds = remember(instance.id, reloadKey, rulesRevision) {
        providerRepository.thinkingRulesWithIds(instance.id)
    }
    val rulesLoaded = true""")

# Arm (c), second spelling: the effect exists and is keyed, but the read is
# inline. Catches "someone moved the call into the effect and forgot the hop".
FIX_THINKING_NO_IO_HOP = _replace_exactly(
    FIX_THINKING_CLEAN,
    """        customRulesAndIds = withContext(Dispatchers.IO) {
            providerRepository.thinkingRulesWithIds(instance.id)
        }""",
    "        customRulesAndIds = providerRepository.thinkingRulesWithIds(instance.id)")

FIX_THINKING_RESOLVER_CLEAN = """package com.rikkaminis.app.provider.thinking

object ThinkingRuleResolver {
    @Volatile
    private var customRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    private val _customRulesRevision = MutableStateFlow(0L)

    val customRulesRevision: StateFlow<Long> = _customRulesRevision.asStateFlow()

    @Synchronized
    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
        _customRulesRevision.value += 1
    }

    @Synchronized
    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = withInstanceRules(customRulesCache, instanceId, rules)
        _customRulesRevision.value += 1
    }

    @Synchronized
    fun restoreCustomRulesFromJson(instanceId: String, field: JSONArray?): Int {
        if (instanceId.isBlank()) return 0
        if (field == null) {
            customRulesCache = withInstanceRules(customRulesCache, instanceId, emptyList())
            _customRulesRevision.value += 1
            return 0
        }
        val rules = (0 until field.length()).mapNotNull { i ->
            val o = field.optJSONObject(i) ?: return@mapNotNull null
            ThinkingRuleCoding.decodeRuleJson(o)
        }
        customRulesCache = withInstanceRules(customRulesCache, instanceId, rules)
        _customRulesRevision.value += 1
        return rules.size
    }

    private fun withInstanceRules(
        cache: Map<String, List<ThinkingRule>>,
        instanceId: String,
        rules: List<ThinkingRule>,
    ): Map<String, List<ThinkingRule>> = cache.toMutableMap().apply {
        if (rules.isEmpty()) remove(instanceId) else put(instanceId, rules)
    }
}
"""

FIX_RESOLVER_NO_BUMP_SETALL = FIX_THINKING_RESOLVER_CLEAN.replace(
    """    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
        _customRulesRevision.value += 1
    }""",
    """    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
    }""",
)

FIX_RESOLVER_NO_BUMP_RESTORE = FIX_THINKING_RESOLVER_CLEAN.replace(
    """        customRulesCache = withInstanceRules(customRulesCache, instanceId, rules)
        _customRulesRevision.value += 1
        return rules.size""",
    """        customRulesCache = withInstanceRules(customRulesCache, instanceId, rules)
        return rules.size""",
)

FIX_RESOLVER_NO_DECL = FIX_THINKING_RESOLVER_CLEAN.replace(
    "    val customRulesRevision: StateFlow<Long> = _customRulesRevision.asStateFlow()\n", "")

# --- da1033-I07 (C14) fixtures: ThinkingRuleEditor seed-keyed form states ----

FIX_THINKING_EDITOR_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
fun ThinkingRuleEditorDialog(request: ThinkingRuleEditorRequest, onDismiss: () -> Unit) {
    val seed = request.seed
    var label by remember(seed) { mutableStateOf(seed.label) }
    var allModels by remember(seed) { mutableStateOf(seed.scope is ThinkingRule.Scope.AllModels) }
    var pattern by remember(seed) {
        mutableStateOf((seed.scope as? ThinkingRule.Scope.ModelPattern)?.pattern ?: "")
    }
    var choice by remember(seed) { mutableStateOf(FormatChoice.from(seed.wireFormat)) }
    var formatChanged by remember(seed) { mutableStateOf(false) }
    var sendOffValue by remember(seed) { mutableStateOf(formatOffValue(seed.wireFormat) != null) }
    var offValue by remember(seed) { mutableStateOf(formatOffValue(seed.wireFormat) ?: "") }
    var path by remember(seed) { mutableStateOf(formatPath(seed.wireFormat) ?: "") }
    var customValues by remember(seed) { mutableStateOf(customEditableValues(seed.wireFormat)) }
    var formatMenuOpen by remember { mutableStateOf(false) }
}
"""

FIX_THINKING_EDITOR_KEYLESS_LABEL = FIX_THINKING_EDITOR_CLEAN.replace(
    "var label by remember(seed) { mutableStateOf(seed.label) }",
    "var label by remember { mutableStateOf(seed.label) }")

FIX_THINKING_EDITOR_FOREIGN_KEY = FIX_THINKING_EDITOR_CLEAN.replace(
    "var offValue by remember(seed) { mutableStateOf(formatOffValue(seed.wireFormat) ?: \"\") }",
    "var offValue by remember(request.existingId) { mutableStateOf(formatOffValue(seed.wireFormat) ?: \"\") }")

# Precision: the decision point is "does the key move with the seed", not the
# spelling — the exact-field key and the two-key form must stay green.
FIX_THINKING_EDITOR_FIELD_KEYED = FIX_THINKING_EDITOR_CLEAN.replace(
    "var label by remember(seed) { mutableStateOf(seed.label) }",
    "var label by remember(request.existingId, seed.label) { mutableStateOf(seed.label) }").replace(
    "var customValues by remember(seed) { mutableStateOf(customEditableValues(seed.wireFormat)) }",
    "var customValues by remember(seed.wireFormat) { mutableStateOf(customEditableValues(seed.wireFormat)) }")

# Precision: a transient UI flag derives from nothing and stays keyless; the
# `formatChanged` latch derives from nothing either, so neither is graded here.
FIX_THINKING_EDITOR_KEYLESS_UI_FLAGS = FIX_THINKING_EDITOR_CLEAN.replace(
    "var formatChanged by remember(seed) { mutableStateOf(false) }",
    "var formatChanged by remember { mutableStateOf(false) }")

FIX_THINKING_EDITOR_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# --- da1034-B4 (C16) fixtures: the env-var edit sheet's disappearance recheck ---

# The real shape: the deep-link effect (which also writes `editEntryId = null`
# but derives from the pending request) sits NEXT TO the close effect, so a
# criterion that merely looked for "some effect nulling editEntryId" would be
# satisfied by the wrong one. The keyless UI flags here are deliberate: C16
# grades the close effect, not every remember in the file.
FIX_ENVVAR_CLEAN = """package com.rikkaminis.app.ui.settings

internal fun editTargetVanished(
    entries: List<EnvVarRepository.EnvVarEntry>,
    editEntryId: String?,
): Boolean {
    if (editEntryId == null) return false
    return entries.none { it.id == editEntryId }
}

@Composable
fun EnvironmentVariablesScreen(envVarRepository: EnvVarRepository, onBack: () -> Unit) {
    val entries by envVarRepository.entries.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editEntryId by remember { mutableStateOf<String?>(null) }
    var deleteEntryId by remember { mutableStateOf<String?>(null) }
    val visibleKeys = remember { mutableStateOf(setOf<String>()) }
    val pendingCreate by DeepLinkCoordinator.pendingEnvVarCreate.collectAsState()
    LaunchedEffect(pendingCreate) {
        pendingCreate?.let {
            editEntryId = null
            showAddSheet = true
            DeepLinkCoordinator.consumePendingEnvVarCreate()
        }
    }
    if (showAddSheet || editEntryId != null) {
        LaunchedEffect(editEntryId, entries) {
            if (editTargetVanished(entries, editEntryId)) {
                editEntryId = null
            }
        }
        val editing = editEntryId?.let { id -> entries.find { it.id == id } }
        EnvVarFormSheet(editEntry = editing, envVarRepository = envVarRepository)
    }
}
"""

# The pre-fix tree: the sheet is opened for an id and the target is resolved from
# the live list, but nothing closes it when that lookup stops resolving.
FIX_ENVVAR_PREFIX = FIX_ENVVAR_CLEAN.replace(
    """        LaunchedEffect(editEntryId, entries) {
            if (editTargetVanished(entries, editEntryId)) {
                editEntryId = null
            }
        }
""", "")

# Closes on every recomposition instead of only when the target is gone.
FIX_ENVVAR_UNCONDITIONAL_CLOSE = FIX_ENVVAR_CLEAN.replace(
    """        LaunchedEffect(editEntryId, entries) {
            if (editTargetVanished(entries, editEntryId)) {
                editEntryId = null
            }
        }""",
    """        LaunchedEffect(editEntryId, entries) {
            editEntryId = null
        }""")

# The lookup is against the list, so the effect must re-run when the list moves:
# keyed on the id alone it never notices the entry disappearing.
FIX_ENVVAR_NO_ENTRIES_KEY = FIX_ENVVAR_CLEAN.replace(
    "LaunchedEffect(editEntryId, entries) {", "LaunchedEffect(editEntryId) {")

FIX_ENVVAR_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# --- da1034-B4 (C17) fixtures: the WebDav dialog's initial-seeded states --------

FIX_BACKUP_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig?,
    httpClient: OkHttpClient,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var path by remember(initial) {
        mutableStateOf(initial?.path ?: WebDavConfig.DEFAULT_BACKUP_DIR)
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmClearPassword by remember { mutableStateOf(false) }
    val runTest: () -> Unit = runTest@{
        val cfg = WebDavConfig(
            url = url,
            username = username,
            password = password.ifBlank { initial?.password.orEmpty() },
            path = path,
        )
        if (cfg.url.isBlank() || cfg.username.isBlank()) {
            return@runTest
        }
        scope.launch { testing = true }
    }
}
"""

# The pre-fix shape: four keyless states frozen at the first composition's
# `initial` (which is still null while the async load runs).
FIX_BACKUP_KEYLESS = FIX_BACKUP_CLEAN.replace("remember(initial) {", "remember {")

# Half-fixed: the value boxes re-seed, the credential/path ones do not.
FIX_BACKUP_PARTIAL = FIX_BACKUP_CLEAN.replace(
    "var password by remember(initial) {", "var password by remember {").replace(
    "var path by remember(initial) {", "var path by remember {")

# Keyed, but on something that cannot move when `initial` moves.
FIX_BACKUP_FOREIGN_KEY = FIX_BACKUP_CLEAN.replace(
    "var url by remember(initial) {", "var url by remember(httpClient) {")

FIX_BACKUP_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# --- da1034-B4 fixtures: C18 (call sites) / C19 (shared single source) -----

FIX_ADD_MODELS_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
fun AddModelsToGroupScreen(modalityFilter: PickerModalityFilter? = null) {
    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )
    var quickTestEntryId by remember { mutableStateOf<String?>(null) }
    modelEntryPickerItems(
        onQuickTest = { if (!SystemVoiceEntries.isSystemEntryId(it.id)) quickTestEntryId = it.id },
    )
    val quickTestEntry = liveQuickTestEntry(quickTestEntryId, config.modelEntries)
    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }
    quickTestEntry?.let { entry ->
        QuickTestSheet(entry = entry, onDismiss = { quickTestEntryId = null })
    }
}
"""

FIX_AGENT_LOOP_SHEETS_CLEAN = """package com.rikkaminis.app.ui.settings

private fun memberNames(config: ProviderConfig): List<String> =
    config.groups.flatMap { group ->
        group.memberEntryIds.mapNotNull { id ->
            SystemVoiceEntries.resolveIn(config.modelEntries, id)?.model?.displayName
        }
    }

@Composable
fun AgentLoopGroupsSheet(config: ProviderConfig) {
    val collapsedInstanceIds = rememberCollapsedInstanceIds(config)
    var quickTestEntryId by remember { mutableStateOf<String?>(null) }
    modelEntryPickerItems(
        onQuickTest = { if (!SystemVoiceEntries.isSystemEntryId(it.id)) quickTestEntryId = it.id },
    )
    val quickTestEntry = liveQuickTestEntry(quickTestEntryId, config.modelEntries)
    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }
    quickTestEntry?.let { entry ->
        QuickTestSheet(entry = entry, onDismiss = { quickTestEntryId = null })
    }
}
"""

FIX_MODEL_GROUPS_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
fun ModelGroupsScreen(config: ProviderConfig) {
    config.groups.forEach { group ->
        GroupRow(
            names = memberNames(config, group),
            badges = modalityBadges(ModelsDevApi.effectiveModel(config.modelEntries.first())),
        )
    }
}

private fun memberNames(config: ProviderConfig, group: ModelGroup): List<String> =
    group.memberEntryIds.mapNotNull { id ->
        SystemVoiceEntries.resolveIn(config.modelEntries, id)?.model?.displayName
    }
"""

# C34 teeth: the defect spelling — resolution that never consults the System source
FIX_MODEL_GROUPS_UNRESOLVED = FIX_MODEL_GROUPS_CLEAN.replace(
    "SystemVoiceEntries.resolveIn(config.modelEntries, id)?.model?.displayName",
    "config.modelEntries.find { it.id == id }?.model?.displayName")

# C34 fail-closed: the group surface no longer reads member ids at all
FIX_MODEL_GROUPS_NO_MEMBER_READ = """package com.rikkaminis.app.ui.settings

@Composable
fun ModelGroupsScreen(config: ProviderConfig) {
    config.groups.forEach { group -> GroupRow(name = group.name) }
}
"""

# C34 must NOT grade a pure numeric aggregate: System entries have no thinking
# level, so excluding them from a max-thinking roll-up is CORRECT behaviour.
FIX_MODEL_GROUPS_AGGREGATE_ONLY = """package com.rikkaminis.app.ui.settings

internal fun groupMaxThinkingLevel(group: ModelGroup, entries: List<ModelEntry>): ThinkingLevel? =
    group.memberEntryIds
        .mapNotNull { id -> entries.find { it.id == id }?.effectiveMaxThinkingLevel }
        .minByOrNull { it.level }
"""

FIX_AGENT_LOOP_MODELS_CLEAN = """package com.rikkaminis.app.ui.settings

@Composable
fun AgentLoopModelsScreen(config: ProviderConfig) {
    val resolvedCount = remember(config) {
        config.groups.sumOf { group ->
            group.memberEntryIds.count { SystemVoiceEntries.isEnabledGroupMember(config.modelEntries, it) }
        }
    }
    Text("$resolvedCount models")
}
"""

FIX_MODEL_ENTRY_PICKER_CLEAN = """package com.rikkaminis.app.ui.components

internal fun providerInstanceIds(config: ProviderConfig): Set<String> =
    config.instances.map { it.id }.toSet()

internal fun collapsedInstanceIdsSeed(instanceIds: Set<String>, defaultExpanded: Boolean): Set<String> =
    if (defaultExpanded) emptySet() else instanceIds

@Composable
fun rememberCollapsedInstanceIds(
    config: ProviderConfig,
    defaultExpanded: Boolean = false,
): MutableState<Set<String>> {
    val instanceIds = remember(config) { providerInstanceIds(config) }
    return remember(instanceIds, defaultExpanded) {
        mutableStateOf(collapsedInstanceIdsSeed(instanceIds, defaultExpanded))
    }
}

@Composable
fun ModelEntryPickerInstanceSections(searchQuery: String, instanceIds: List<String>) {
    val isSearching = searchQuery.isNotBlank()
    val collapsedInstanceIds = rememberCollapsedInstanceIds(config)
    val effectiveCollapsed = if (isSearching) emptySet() else collapsedInstanceIds.value
    instanceIds.forEach { instanceId ->
        val isCollapsed = instanceId in effectiveCollapsed
        if (isCollapsed) renderCollapsedSummary(instanceId)
    }
}
"""

# --- [deep-audit-1041 B-04] C32: the search-gated collapse read ------------

FIX_MODEL_PICKER_SHEET_CLEAN = """package com.rikkaminis.app.ui.chat

// [dv24/B-02] C34 is registered on this surface: the picker renders a group's
// members (count, preview, expanded rows), so every resolution must consult the
// System source.
internal fun pickerGroupMembers(config: ProviderConfig, group: ModelGroup): List<ModelEntry> =
    group.memberEntryIds.mapNotNull { entryId ->
        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    }

// [dv25/B-04] C35 / C36 are registered on this surface too: the group HEADER
// resolves the selected group's ACTIVE entry, and the member fallback must hang
// off the `if`, not off its else branch.
internal fun pickerHeaderEntry(
    isSelected: Boolean,
    activeEntryId: String?,
    memberEntryIds: List<String>,
    resolve: (String) -> ModelEntry?,
): ModelEntry? {
    val fromActive = if (isSelected && activeEntryId != null) resolve(activeEntryId) else null
    return fromActive ?: memberEntryIds.firstNotNullOfOrNull { entryId ->
        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    }
}

internal fun pickerDisplayMembers(
    resolvedMembers: List<ModelEntry>,
    isSelected: Boolean,
    activeEntryId: String?,
    resolve: (String) -> ModelEntry?,
): List<ModelEntry> = resolvedMembers.ifEmpty {
    if (isSelected && activeEntryId != null) listOfNotNull(resolve(activeEntryId)) else emptyList()
}

internal fun ModelPickerSheetProviderSections(config: ProviderConfig) {
    var searchText by remember { mutableStateOf("") }
    // [dv28/B-07] the pre-fix copy was `remember(allInstanceIds)` with
    // allInstanceIds = config.instances.filter { it.isEnabled } — a key that
    // moves when the user toggles a provider's enable switch.
    var collapsedInstanceIds by rememberCollapsedInstanceIds(config)
    orderedProviderSections.forEachIndexed { sectionIndex, (instance, entries) ->
        val isCollapsed = searchText.isEmpty() && collapsedInstanceIds.contains(instance.id)
        if (isCollapsed) {
            renderCollapsedSummary(instance, entries)
        }
    }
}

// [dv28/B-07] the expanded group's member rows answer 「which provider serves
// this member」 through the shared presentation decision — a System voice member
// has no instance behind it, so `if (instance != null)` dropped the label.
internal fun pickerMemberSubtitle(entry: ModelEntry, config: ProviderConfig): String {
    val instance = config.instances.find { it.id == entry.providerInstanceId }
    return SystemVoiceMemberPresentation.memberSubtitle(
        instanceLabel = instance?.label?.takeUnless { it.isEmpty() }
            ?: instance?.providerType?.displayName,
        isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        systemLabel = "System",
    )
}
"""

# the pre-fix read: the collapse set decides on its own, so a matching section
# stays folded and shows only the (selected-biased) summary row.
FIX_MODEL_PICKER_SHEET_PRE = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "val isCollapsed = searchText.isEmpty() && collapsedInstanceIds.contains(instance.id)",
    "val isCollapsed = collapsedInstanceIds.contains(instance.id)")

# polarity inverted: the query is consulted, but the guard reads backwards —
# every section FOLDS while searching. Must be a red.
FIX_MODEL_PICKER_SHEET_INVERTED = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "searchText.isEmpty() &&", "searchText.isNotEmpty() &&")

# the shared component's spelling (derived set + emptySet()): accepted.
FIX_MODEL_PICKER_SHEET_DERIVED = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "        val isCollapsed = searchText.isEmpty() && collapsedInstanceIds.contains(instance.id)",
    """        val effectiveCollapsed =
            if (searchText.isEmpty()) collapsedInstanceIds else emptySet()
        val isCollapsed = instance.id in effectiveCollapsed""")

FIX_MODEL_PICKER_SHEET_MISSING_ANCHOR = """package com.rikkaminis.app.ui.chat

fun unrelated() {}
"""

# --- [dv25/B-04] C35: the ACTIVE entry must share the single source ----------

# the header looks the active entry up in the persisted list alone, so a System
# sentinel active entry (a default Voice group's only inhabitant) resolves null.
FIX_MODEL_PICKER_SHEET_ACTIVE_BARE = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "val fromActive = if (isSelected && activeEntryId != null) resolve(activeEntryId) else null",
    "val fromActive = if (isSelected && activeEntryId != null) "
    "config.modelEntries.find { it.id == activeEntryId } else null")

# the members resolve correctly but the ACTIVE fallback row does not — this is
# the `:786` shape verbatim: its own statement, so C34 (which grades the
# `memberEntryIds` blocks) is blind to it and C35 is the only witness.
FIX_MODEL_PICKER_SHEET_MEMBER_BARE = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "listOfNotNull(resolve(activeEntryId)) else emptyList()",
    "listOfNotNull(config.modelEntries.find { it.id == activeEntryId }) else emptyList()")

# --- [dv25/B-04] C36: the precedence swallow, verbatim -----------------------

# Both lookups correctly route through the System source, so ONLY the elvis
# placement is wrong — that is what makes this arm pin C36 by itself.
FIX_MODEL_PICKER_SHEET_ELVIS_SWALLOW = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    """    val fromActive = if (isSelected && activeEntryId != null) resolve(activeEntryId) else null
    return fromActive ?: memberEntryIds.firstNotNullOfOrNull { entryId ->
        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    }""",
    """    return if (isSelected && activeEntryId != null) {
        SystemVoiceEntries.resolveIn(config.modelEntries, activeEntryId)
    } else null ?: memberEntryIds.firstNotNullOfOrNull { entryId ->
        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    }""")

# the shared picker's own render decision, ungated (same defect, other file).
FIX_PICKER_RENDER_UNGATED = FIX_MODEL_ENTRY_PICKER_CLEAN.replace(
    "val isCollapsed = instanceId in effectiveCollapsed",
    "val isCollapsed = instanceId in collapsedInstanceIds.value")

# C18 — a screen that kept its own copy keyed on the whole config object.
FIX_ADD_MODELS_BARE_CONFIG_KEY = FIX_ADD_MODELS_CLEAN.replace(
    """    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )""",
    """    val collapsedInstanceIds = remember(config) {
        mutableStateOf(
            if (modalityFilter != null) emptySet()
            else config.instances.map { it.id }.toSet(),
        )
    }""")

FIX_ADD_MODELS_KEYLESS = FIX_ADD_MODELS_CLEAN.replace(
    """    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )""",
    """    val collapsedInstanceIds = remember {
        mutableStateOf(config.instances.map { it.id }.toSet())
    }""")

# a key that never moves with the instance list (only the voice flag)
FIX_ADD_MODELS_FLAG_ONLY_KEY = FIX_ADD_MODELS_CLEAN.replace(
    """    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )""",
    """    val collapsedInstanceIds = remember(modalityFilter != null) {
        mutableStateOf(config.instances.map { it.id }.toSet())
    }""")

FIX_ADD_MODELS_MISSING_ANCHOR = """package com.rikkaminis.app.ui.settings

fun unrelated() {}
"""

# the second call site, same defect (a per-file criterion must cover both)
FIX_AGENT_LOOP_BARE_CONFIG_KEY = FIX_AGENT_LOOP_SHEETS_CLEAN.replace(
    "val collapsedInstanceIds = rememberCollapsedInstanceIds(config)",
    """val collapsedInstanceIds = remember(config) {
        mutableStateOf(config.instances.map { it.id }.toSet())
    }""")

# an accepted alternative shape: the derivation stays inline at the call site
FIX_ADD_MODELS_INLINE_DERIVATION = FIX_ADD_MODELS_CLEAN.replace(
    """    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )""",
    """    val instanceIds = config.instances.map { it.id }.toSet()
    val collapsedInstanceIds = remember(instanceIds, modalityFilter != null) {
        mutableStateOf(collapsedInstanceIdsSeed(instanceIds, modalityFilter != null))
    }""")

# a call site that also holds an unrelated keyless remember must stay green
FIX_ADD_MODELS_UNRELATED_REMEMBER = FIX_ADD_MODELS_CLEAN.replace(
    """    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )""",
    """    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        defaultExpanded = modalityFilter != null,
    )
    val scratch = remember { mutableStateOf("") }
    val unrelated = remember(config) { config.modelEntries }""")

# C19 — the single source is keyed on the whole config instead of the id set.
FIX_PICKER_STATE_BARE_CONFIG_KEY = FIX_MODEL_ENTRY_PICKER_CLEAN.replace(
    "    return remember(instanceIds, defaultExpanded) {",
    "    return remember(config, defaultExpanded) {")
FIX_PICKER_STATE_KEYLESS = FIX_MODEL_ENTRY_PICKER_CLEAN.replace(
    "    return remember(instanceIds, defaultExpanded) {",
    "    return remember {")

# the id set is only counted — the same mistake as a size-only refresh key
FIX_PICKER_SIZE_ONLY_KEY = FIX_MODEL_ENTRY_PICKER_CLEAN.replace(
    "val instanceIds = remember(config) { providerInstanceIds(config) }",
    "val instanceIds = remember(config) { config.instances.size }")

FIX_PICKER_MISSING_HELPER = """package com.rikkaminis.app.ui.components

internal fun providerInstanceIds(config: ProviderConfig): Set<String> =
    config.instances.map { it.id }.toSet()
"""

# --- da1039-B3 B-08 variants: the Quick Test target (C24 / C25) -----------

# the pre-fix shape: the tapped ModelEntry object, no id, no close effect
FIX_ADD_MODELS_SNAPSHOT_TARGET = FIX_ADD_MODELS_CLEAN.replace(
    """    var quickTestEntryId by remember { mutableStateOf<String?>(null) }""",
    """    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }""").replace(
    "quickTestEntryId = it.id }", "quickTestEntry = it }").replace(
    """    val quickTestEntry = liveQuickTestEntry(quickTestEntryId, config.modelEntries)
    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }
""", "").replace(
    "onDismiss = { quickTestEntryId = null }", "onDismiss = { quickTestEntry = null }")

# the sibling keeps the shared picker but never wires the channel
FIX_AGENT_LOOP_NO_QUICK_TEST = FIX_AGENT_LOOP_SHEETS_CLEAN.replace(
    """    var quickTestEntryId by remember { mutableStateOf<String?>(null) }
    modelEntryPickerItems(
        onQuickTest = { if (!SystemVoiceEntries.isSystemEntryId(it.id)) quickTestEntryId = it.id },
    )
    val quickTestEntry = liveQuickTestEntry(quickTestEntryId, config.modelEntries)
    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }
    quickTestEntry?.let { entry ->
        QuickTestSheet(entry = entry, onDismiss = { quickTestEntryId = null })
    }
""",
    """    modelEntryPickerItems(
        emptyTextRes = R.string.agent_loop_section_no_available_models,
    )
""")

# no close effect at all: the sheet stays open on a deleted target
FIX_ADD_MODELS_NO_CLOSE_EFFECT = FIX_ADD_MODELS_CLEAN.replace(
    """    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }
""", "")

# closing unconditionally does NOT count (it would dismiss on any recomposition)
FIX_ADD_MODELS_UNCONDITIONAL_CLOSE = FIX_ADD_MODELS_CLEAN.replace(
    """    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }""",
    """    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        quickTestEntryId = null
    }""")

# keyed on the pending id only: a vanished target that still resolves against a
# stale list is never noticed
FIX_ADD_MODELS_NO_LIST_KEY = FIX_ADD_MODELS_CLEAN.replace(
    "LaunchedEffect(quickTestEntryId, config.modelEntries) {",
    "LaunchedEffect(quickTestEntryId) {")

# --- dv22-B3: capability single source (C33) --------------------------------
FIX_QUICK_TEST_CLEAN = """package com.rikkaminis.app.ui.components

fun applicableKinds(entry: ModelEntry): List<String> {
    val model = ModelsDevApi.effectiveModel(entry)
    val outputs = (model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }
    return outputs
}

private suspend fun performTest(entry: ModelEntry): String = withContext(Dispatchers.IO) {
    val effectiveModel = ModelsDevApi.effectiveModel(entry)
    val a = VoiceInputRequest(model = entry.baseModel.id, language = "en", resolvedModel = effectiveModel)
    // string-ids stay legal: the voice adapter takes the entry as its VOICE
    val b = VoiceOutputRequest(model = entry.model.id, voice = entry.model.id)
    val c = ProviderExecutionGateway.send(model = effectiveModel)
    val d = ProviderExecutionGateway.generateImage(model = effectiveModel)
    a + b + c + d
}
"""

FIX_QUICK_TEST_RAW_LOCAL = FIX_QUICK_TEST_CLEAN.replace(
    "val model = ModelsDevApi.effectiveModel(entry)",
    "val model = entry.model")

FIX_QUICK_TEST_RAW_CHAIN = FIX_QUICK_TEST_CLEAN.replace(
    "val outputs = (model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }",
    "val outputs = (entry.model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }")

# BOTH enrichment sites gone: the capability list AND the execution (the
# fixture used to strip only the list, which the execution resolution kept
# "single source present" — the arm had lost its teeth).
FIX_QUICK_TEST_NO_SOURCE = FIX_QUICK_TEST_CLEAN.replace(
    "ModelsDevApi.effectiveModel(entry)",
    "entry.baseModel")

# [dv24/B-01] the pre-fix execution spelling: one of the three calls hands the
# worker the raw entry again (the other two stay fixed — ONE bad site is enough
# to lose the image capability on that test kind).
FIX_QUICK_TEST_RAW_PERFORM_MODEL = FIX_QUICK_TEST_CLEAN.replace(
    "ProviderExecutionGateway.generateImage(model = effectiveModel)",
    "ProviderExecutionGateway.generateImage(model = entry.model)")

# [dv24/B-01] the resolution is gone but the arguments look right — the calls
# now reference an identifier nothing enriched.
FIX_QUICK_TEST_NO_PERFORM_ENRICH = FIX_QUICK_TEST_CLEAN.replace(
    """    val effectiveModel = ModelsDevApi.effectiveModel(entry)
    val a = """,
    """    val a = """)

FIX_CHAT_SCREEN_CLEAN = """package com.rikkaminis.app.ui.chat

// [dv24/B-02] C34 is registered on this surface too: the group tap resolves
// its first member to decide whether a non-text confirmation is owed.
internal fun firstGroupEntry(config: ProviderConfig, group: ModelGroup?): ModelEntry? =
    group?.memberEntryIds?.firstNotNullOfOrNull { entryId ->
        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    }

fun nonTextLabelFor(model: LLMModel): String? {
    val mods = model.outputModalities.normalizeModalities() ?: emptyList()
    return mods.firstOrNull()
}

fun usage(entry: ModelEntry?): String? =
    entry?.let { nonTextLabelFor(ModelsDevApi.effectiveModel(it)) }
"""

FIX_CHAT_SCREEN_RAW_LABEL = FIX_CHAT_SCREEN_CLEAN.replace(
    "entry?.let { nonTextLabelFor(ModelsDevApi.effectiveModel(it)) }",
    "entry?.model?.let(::nonTextLabelFor)")
# [dv24/B-02] both new C34 surfaces, pre-fix spelling (persisted-only lookup)
FIX_MODEL_PICKER_SHEET_NO_SYSTEM_FALLBACK = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "SystemVoiceEntries.resolveIn(config.modelEntries, entryId)",
    "config.modelEntries.find { it.id == entryId }")

FIX_CHAT_SCREEN_NO_SYSTEM_FALLBACK = FIX_CHAT_SCREEN_CLEAN.replace(
    "SystemVoiceEntries.resolveIn(config.modelEntries, entryId)",
    "config.modelEntries.find { it.id == entryId }")

# [dv24/B-02] the method-reference indirection: `::entryById` is a member
# resolution site even though the single source lives in another function, so
# the screen must not resolve the member id inline behind it.
FIX_CHAT_SCREEN_ENTRY_BY_ID_INDIRECTION = FIX_CHAT_SCREEN_CLEAN.replace(
    """    group?.memberEntryIds?.firstNotNullOfOrNull { entryId ->
        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
    }""",
    """    group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)""")



# --- [dv28/B-07] C18 + C-SV2b mounted on the chat model picker --------------

# The picker's private collapse copy: it DOES derive from the instance list, so
# the pre-dv28 C18 (「keys resolve back to instances」) let it through — the key
# was filtered on `isEnabled`, which is user-mutable, so the criterion had to be
# tightened to grade the SHAPE of the derivation. Only C18 may fire here.
FIX_MODEL_PICKER_SHEET_INLINE_FILTERED_KEY = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    "    var collapsedInstanceIds by rememberCollapsedInstanceIds(config)",
    """    val allInstanceIds = remember(config) {
        config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    }
    var collapsedInstanceIds by remember(allInstanceIds) {
        mutableStateOf(allInstanceIds)
    }""")

# The pre-fix member row: the label only exists when a persisted instance does,
# so a System voice member (the device IS its provider) shows a bare model id.
FIX_MODEL_PICKER_SHEET_MEMBER_LABEL_GUARDED = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    """    val instance = config.instances.find { it.id == entry.providerInstanceId }
    return SystemVoiceMemberPresentation.memberSubtitle(
        instanceLabel = instance?.label?.takeUnless { it.isEmpty() }
            ?: instance?.providerType?.displayName,
        isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        systemLabel = "System",
    )""",
    """    val instance = config.instances.find { it.id == entry.providerInstanceId }
    return if (instance != null) instance.label.ifEmpty { instance.providerType.displayName } else \"\"""")

# A site that DOES read `?.label` but never consults the System source: the
# criterion must catch this too (site present, decision absent).
FIX_MODEL_PICKER_SHEET_MEMBER_LABEL_BARE = FIX_MODEL_PICKER_SHEET_CLEAN.replace(
    """    return SystemVoiceMemberPresentation.memberSubtitle(
        instanceLabel = instance?.label?.takeUnless { it.isEmpty() }
            ?: instance?.providerType?.displayName,
        isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
        systemLabel = "System",
    )""",
    """    return instance?.label.orEmpty()""")

# --- [wide-1024] the values-not-spellings fixtures -------------------------
# Each one is a witness the external sweep used to walk through this gate: the
# named anchor stayed where the criterion looked, the VALUE stopped flowing.

# C1 + C30b: the credential snapshot is still read by the rows block, but it is
# no longer a remember key — an external key write re-reads the map into a
# value nothing re-derives from, so the badges keep the first snapshot forever.
FIX_PROVIDER_LIST_NO_CREDENTIAL_KEY = _replace_exactly(
    FIX_PROVIDER_LIST_CLEAN,
    "remember(instances, config.modelEntries, apiKeyByInstanceId)",
    "remember(instances, config.modelEntries)")

# C7b/C7c: the fresh read survives inside a receiver scope that DISCARDS it
# (`.also` returns its receiver), so the toast still reports the pre-fetch count.
FIX_PROVIDER_DETAIL_LET_THROUGH = _replace_exactly(
    FIX_PROVIDER_DETAIL_CLEAN,
    "        val total = providerRepository.entriesFor(instance.id).size",
    "        val total = allEntries.size.also { providerRepository.entriesFor(instance.id) }")

# C13: the bump is present and then cancelled in the same body — the revision
# lands on its old value, MutableStateFlow suppresses the equal emission.
FIX_RESOLVER_CANCELLED_BUMP = _replace_exactly(
    FIX_THINKING_RESOLVER_CLEAN,
    """    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = withInstanceRules(customRulesCache, instanceId, rules)
        _customRulesRevision.value += 1
    }""",
    """    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = withInstanceRules(customRulesCache, instanceId, rules)
        _customRulesRevision.value += 1
        _customRulesRevision.value -= 1
    }""")

# C33: the raw carrier grew a `.copy()` — the old lookahead only rejected the
# BARE `entry.model`, so an un-enriched copy walked through the image branch.
FIX_QUICK_TEST_RAW_CARRIER_COPY = _replace_exactly(
    FIX_QUICK_TEST_CLEAN,
    "ProviderExecutionGateway.generateImage(model = effectiveModel)",
    "ProviderExecutionGateway.generateImage(model = entry.model.copy())")

# --- batch5: the fifth sweep's witness shapes ------------------------------
# Each one walked through this gate because every NAME the criterion looked for
# was still there while the DECISION was gone.

# 07-1: the guard survives but is short-circuited by a constant — the sheet never
# closes when the variable being edited is deleted elsewhere.
FIX_ENVVAR_DEAD_GUARD = _replace_exactly(
    FIX_ENVVAR_CLEAN,
    "if (editTargetVanished(entries, editEntryId)) {",
    "if (false && editTargetVanished(entries, editEntryId)) {")

# 07-2: same shape on the parent re-check — the orphan ModelEntry write is reachable.
FIX_ADD_CUSTOM_MODEL_DEAD_GUARD = _replace_exactly(
    FIX_ADD_CUSTOM_MODEL_CLEAN,
    "if (!parentStillThere) {",
    "if (false && !parentStillThere) {")

# 07-3: the write argument NAMES the live resolver inside a discarding scope, so the
# snapshot is what actually reaches updateInstance.
FIX_CONNECTION_ALSO_LIVE = _replace_exactly(
    FIX_CONNECTION_CLEAN,
    "providerRepository.updateInstance(live.copy(customBaseURL = pending))",
    "providerRepository.updateInstance(instance.copy(customBaseURL = pending).also { live })")

# 07-4: every name of the close-on-vanished decision is intact; the helper body
# decides nothing (`= false`).
FIX_PROVIDER_DETAIL_HELPER_CONSTANT = _replace_exactly(
    FIX_PROVIDER_DETAIL_CLEAN,
    "pendingId != null && liveEntryToDelete(pendingId, entries) == null",
    "false")

# 07-5: the snapshot is still keyed and still read — with a constant subscript, so
# every row resolves the same (empty) entry.
FIX_PROVIDER_LIST_CONSTANT_KEY = _replace_exactly(
    FIX_PROVIDER_LIST_CLEAN,
    "instance to apiKeyByInstanceId[instance.id]",
    'instance to apiKeyByInstanceId[""]')

# 07-6: the enriched value is derived away — the copy nulls the very field the
# worker cannot re-derive.
FIX_QUICK_TEST_ENRICHED_COPY_NULL = _replace_exactly(
    FIX_QUICK_TEST_CLEAN,
    "ProviderExecutionGateway.generateImage(model = effectiveModel)",
    "ProviderExecutionGateway.generateImage(model = effectiveModel.copy(outputModalities = null))")

CLEAN_TREE = {
    REL_PROVIDER_LIST: FIX_PROVIDER_LIST_CLEAN,
    REL_SHEET: FIX_SHEET_CLEAN,
    REL_PROVIDER_DETAIL: FIX_PROVIDER_DETAIL_CLEAN,
    REL_CONNECTION: FIX_CONNECTION_CLEAN,
    REL_ENTRY_DETAIL: FIX_ENTRY_DETAIL_CLEAN,
    REL_GROUP_DETAIL: FIX_GROUP_DETAIL_CLEAN,
    REL_MOUNT_DETAIL: FIX_MOUNT_DETAIL_CLEAN,
    REL_APP_NAV: FIX_APPNAV_CLEAN,
    REL_USAGE_STATS: FIX_USAGE_STATS_CLEAN,
    REL_THINKING_SECTION: FIX_THINKING_CLEAN,
    REL_THINKING_RESOLVER: FIX_THINKING_RESOLVER_CLEAN,
    REL_THINKING_EDITOR: FIX_THINKING_EDITOR_CLEAN,
    REL_ENVVAR_SCREEN: FIX_ENVVAR_CLEAN,
    REL_BACKUP_SCREEN: FIX_BACKUP_CLEAN,
    REL_ADD_MODELS: FIX_ADD_MODELS_CLEAN,
    REL_AGENT_LOOP_SHEETS: FIX_AGENT_LOOP_SHEETS_CLEAN,
    REL_AGENT_LOOP_MODELS: FIX_AGENT_LOOP_MODELS_CLEAN,
    REL_MODEL_GROUPS: FIX_MODEL_GROUPS_CLEAN,
    REL_MODEL_ENTRY_PICKER: FIX_MODEL_ENTRY_PICKER_CLEAN,
    REL_MODEL_PICKER_SHEET: FIX_MODEL_PICKER_SHEET_CLEAN,
    REL_ADD_CUSTOM_MODEL: FIX_ADD_CUSTOM_MODEL_CLEAN,
    REL_QUICK_TEST: FIX_QUICK_TEST_CLEAN,
    REL_CHAT_SCREEN: FIX_CHAT_SCREEN_CLEAN,
}


def variant(rel, text):
    files = dict(CLEAN_TREE)
    files[rel] = text
    return files


def missing(rel):
    """Clean tree minus [rel] — the file-absent (fail-closed) arm."""
    files = dict(CLEAN_TREE)
    del files[rel]
    return files


def write_tree(tmpdir, files):
    for rel, text in files.items():
        d = os.path.join(tmpdir, os.path.dirname(rel))
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(tmpdir, rel), "w", encoding="utf-8") as f:
            f.write(text)


_ARM_COUNT = [0]


def run_case(name, expect_rc, files, failures, expect_fail=()):
    """Run the gate on a fixture tree. A red fixture must red for EXACTLY the
    criteria in expect_fail (no more, no less): a fixture that turns red for
    the wrong reason is a MISMATCH, not a pass — otherwise a criterion that
    quietly broke would still look "covered" by some unrelated redness."""
    _ARM_COUNT[0] += 1
    tmp = tempfile.mkdtemp(prefix="prk-selftest-")
    try:
        write_tree(tmp, files)
        # run the gate logic directly on the fixture tree (exit code capture
        # without a subprocess)
        results = []
        for rel, fn in CHECKERS:
            path = os.path.join(tmp, rel)
            if not os.path.isfile(path):
                results.append(("FILE", False, "missing"))
                continue
            with open(path, encoding="utf-8") as f:
                results.extend(fn(code_of(f.read())))
        rc = 0 if all(ok for _, ok, _ in results) else 1
        failed = set(cid for cid, ok, _ in results if not ok)
        ok = (rc == expect_rc) and (failed == set(expect_fail))
        if not ok:
            failures.append("%s: expected rc=%d failed=%s got rc=%d failed=%s :: %s" % (
                name, expect_rc, sorted(set(expect_fail)), rc, sorted(failed),
                "; ".join("%s %s" % (cid, "PASS" if o else "FAIL") for cid, o, _ in results)))
        print("  fixture %-38s expect_rc=%d rc=%d failed=%-14s %s" % (
            name, expect_rc, rc, ",".join(sorted(failed)) or "-", "OK" if ok else "MISMATCH"))
        return rc
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run_selftest():
    print("provider_remember_key_check — selftest")
    failures = []
    # clean tree must be green
    run_case("clean", 0, CLEAN_TREE, failures)
    # C1: removing modelEntries key must turn the tree red
    run_case("c1-no-modelEntries-key", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_NO_MODEL_ENTRIES), failures, ("C1",))
    # C2: reorderableRows keyed on instances instead of providerRows
    run_case("c2-reorderableRows-stale-key", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_NO_PROVIDER_ROWS_KEY), failures, ("C2",))
    # C3: sheet instance remember missing config
    run_case("c3-sheet-no-config-key", 1,
             variant(REL_SHEET, FIX_SHEET_NO_CONFIG_KEY), failures, ("C3",))
    # C4: onTogglePinned remember capturing stale pinned
    run_case("c4-stale-pinned-key", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_STALE_PINNED_KEY), failures, ("C4",))
    # fail-closed: missing anchors must be red, never silently green
    run_case("failclosed-missing-provider-anchors", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_MISSING_ANCHOR), failures,
             ("C1", "C2", "C4", "C30"))
    run_case("failclosed-missing-sheet-anchor", 1,
             variant(REL_SHEET, FIX_SHEET_MISSING_ANCHOR), failures, ("C3",))
    # comment must not satisfy criteria (KDoc quoting an anchor is not the anchor)
    # wide-1024: C30b joins the expectation — with the rows block commented out
    # the file genuinely has NO consumer of the credential snapshot, which the
    # new consumption arm grades red on its own.
    commented = FIX_PROVIDER_LIST_NO_MODEL_ENTRIES.replace(
        "val providerRows", "// val providerRows") + "\n// onTogglePinned = remember(instances) {}\n"
    run_case("comments-do-not-count", 1,
             variant(REL_PROVIDER_LIST, commented), failures, ("C1", "C30b"))
    # --- dv22-B3: C33 (capability single source) --------------------------
    run_case("c33-raw-local-model", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_RAW_LOCAL), failures, ("C33",))
    run_case("c33-raw-model-chain", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_RAW_CHAIN), failures, ("C33",))
    run_case("c33-no-single-source", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_NO_SOURCE), failures, ("C33",))
    run_case("c33-nontext-label-raw", 1,
             variant(REL_CHAT_SCREEN, FIX_CHAT_SCREEN_RAW_LABEL), failures, ("C33",))
    # --- dv23-B6: C34 (group members resolve through ONE source) ----------
    run_case("c34-member-unresolved", 1,
             variant(REL_MODEL_GROUPS, FIX_MODEL_GROUPS_UNRESOLVED), failures, ("C34",))
    run_case("c34-member-resolution-site-gone", 1,
             variant(REL_MODEL_GROUPS, FIX_MODEL_GROUPS_NO_MEMBER_READ), failures, ("C34",))
    run_case("c34-aggregate-only-out-of-scope", 0,
             variant(REL_MODEL_GROUPS, FIX_MODEL_GROUPS_AGGREGATE_ONLY), failures)
    # --- dv24-B2: the two surfaces whose resolution spelling was unrecognised --
    run_case("c34-picker-no-system-fallback", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_NO_SYSTEM_FALLBACK),
             failures, ("C34", "C35"))
    run_case("c34-screen-no-system-fallback", 1,
             variant(REL_CHAT_SCREEN, FIX_CHAT_SCREEN_NO_SYSTEM_FALLBACK), failures, ("C34",))
    run_case("c34-screen-entrybyid-indirection", 1,
             variant(REL_CHAT_SCREEN, FIX_CHAT_SCREEN_ENTRY_BY_ID_INDIRECTION),
             failures, ("C34",))
    # --- dv24-B1: the quick test must EXECUTE the effective model -------------
    run_case("c33-quick-test-raw-perform-model", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_RAW_PERFORM_MODEL), failures, ("C33",))
    run_case("c33-quick-test-no-perform-enrich", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_NO_PERFORM_ENRICH), failures, ("C33",))
    # --- wide-1024: the raw carrier with a member access is still the carrier
    run_case("c33-quick-test-raw-carrier-copy", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_RAW_CARRIER_COPY), failures, ("C33",))

    # --- batch5: the fifth sweep's witness shapes -------------------------
    # Every name the criterion used to look for is still in place; only the
    # decision is gone. Each arm must red on exactly its own criterion.
    run_case("c16-envvar-dead-guard", 1,
             variant(REL_ENVVAR_SCREEN, FIX_ENVVAR_DEAD_GUARD), failures, ("C16",))
    run_case("c21-custom-model-dead-guard", 1,
             variant(REL_ADD_CUSTOM_MODEL, FIX_ADD_CUSTOM_MODEL_DEAD_GUARD), failures, ("C21",))
    run_case("c22-conn-also-live", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_ALSO_LIVE), failures, ("C22",))
    run_case("c28-detail-helper-constant", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_HELPER_CONSTANT), failures, ("C28",))
    run_case("c30-list-constant-subscript", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_CONSTANT_KEY), failures, ("C30b",))
    run_case("c33-quick-test-enriched-copy-null", 1,
             variant(REL_QUICK_TEST, FIX_QUICK_TEST_ENRICHED_COPY_NULL), failures, ("C33",))

    # --- da1026-C: C5 (connection screen) ---------------------------------
    run_case("c5-conn-no-baseurl-key", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_NO_BASEURL_KEY), failures, ("C5", "C5a"))
    run_case("c5-conn-no-ua-key", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_NO_UA_KEY), failures, ("C5", "C5a"))
    run_case("failclosed-missing-conn-anchor", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_MISSING_ANCHOR), failures,
             ("C5", "C5a", "C22", "C23", "C31"))
    # --- da1039-B3: C20 / C21 (AddCustomModelScreen parent-instance read) --
    run_case("c20-custom-model-snapshot-read", 1,
             variant(REL_ADD_CUSTOM_MODEL, FIX_ADD_CUSTOM_MODEL_SNAPSHOT), failures, ("C20",))
    run_case("c21-custom-model-no-live-guard", 1,
             variant(REL_ADD_CUSTOM_MODEL, FIX_ADD_CUSTOM_MODEL_NO_LIVE_GUARD), failures, ("C21",))
    run_case("failclosed-missing-custom-model-anchor", 1,
             variant(REL_ADD_CUSTOM_MODEL, FIX_ADD_CUSTOM_MODEL_MISSING_ANCHOR), failures,
             ("C20", "C21"))
    # --- da1039-B3: C22 / C23 (connection write path) -----------------------
    run_case("c22-conn-snapshot-write", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_SNAPSHOT_WRITE), failures, ("C22",))
    run_case("c22-conn-no-live-resolver", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_NO_RESOLVER), failures, ("C22",))
    run_case("c23-conn-helper-always-write", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_HELPER_ALWAYS_WRITE), failures, ("C23",))
    run_case("c23-conn-no-dirty-guard", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_NO_DIRTY_GUARD), failures, ("C23",))
    run_case("c23-conn-cross-field-write", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_CROSS_FIELD_WRITE), failures, ("C23",))
    # --- da1026-C: C6 / C6c (entry detail screen) --------------------------
    run_case("c6-entry-keyless-remember", 1,
             variant(REL_ENTRY_DETAIL, FIX_ENTRY_DETAIL_KEYLESS), failures, ("C6",))
    # keyed on the screen's own argument: passes C6, must still be caught
    run_case("c6c-entry-wrong-key", 1,
             variant(REL_ENTRY_DETAIL, FIX_ENTRY_DETAIL_WRONG_KEY), failures, ("C6c",))
    run_case("failclosed-missing-entry-anchor", 1,
             variant(REL_ENTRY_DETAIL, FIX_ENTRY_DETAIL_MISSING_ANCHOR), failures, ("C6",))
    # --- wide-1024: the value, not the spelling ---------------------------
    run_case("c1-credential-key-dropped", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_NO_CREDENTIAL_KEY), failures,
             ("C1", "C30b"))
    run_case("c7bc-fresh-read-in-discarded-scope", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_LET_THROUGH), failures,
             ("C7b", "C7c"))
    run_case("c7b-detail-stale-snapshot", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_STALE), failures, ("C7b", "C7c"))
    run_case("c7c-detail-no-fresh-read", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_NO_REREAD), failures, ("C7c",))
    # accepted alternative shape (state-backed list re-assigned on the path)
    run_case("c7-detail-state-backed-variant", 0,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_STATE_BACKED), failures)
    run_case("failclosed-missing-detail-anchor", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_MISSING_ANCHOR), failures,
             ("C7", "C11c", "C26", "C27", "C28", "C29"))
    # --- da1027-E: C8 / C9 (field-keyed editors) ---------------------------
    run_case("c8-group-no-name-key", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_NO_NAME_KEY), failures, ("C8",))
    run_case("failclosed-missing-group-anchor", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_MISSING_ANCHOR), failures,
             ("C8", "C35"))
    # --- dv27-B3: C35 mounted on the group detail screen + C-SV1 / C-SV2 ----
    run_case("c35-group-remove-persisted-only", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_REMOVE_PERSISTED_ONLY),
             failures, ("C35",))
    run_case("csv1-cost-tier-unguarded", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_COST_TIER_UNGUARDED),
             failures, ("C-SV1",))
    run_case("csv1-guard-flag-not-derived", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_FLAG_NOT_DERIVED),
             failures, ("C-SV1",))
    run_case("csv2-subtitle-empty-fallback", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_SUBTITLE_EMPTY),
             failures, ("C-SV2a",))
    run_case("csv2-subtitle-no-system-source", 1,
             variant(REL_GROUP_DETAIL, FIX_GROUP_DETAIL_SUBTITLE_NO_SYSTEM),
             failures, ("C-SV2b",))
    run_case("c9-mount-no-write-key", 1,
             variant(REL_MOUNT_DETAIL, FIX_MOUNT_DETAIL_NO_WRITE_KEY), failures, ("C9",))
    run_case("failclosed-missing-mount-anchor", 1,
             variant(REL_MOUNT_DETAIL, FIX_MOUNT_DETAIL_MISSING_ANCHOR), failures, ("C9",))
    # --- da1027-E: C10a / C10b (usage page observes the config) ------------
    run_case("c10a-appnav-snapshot", 1,
             variant(REL_APP_NAV, FIX_APPNAV_SNAPSHOT), failures, ("C10a",))
    run_case("failclosed-missing-appnav-anchor", 1,
             variant(REL_APP_NAV, FIX_APPNAV_MISSING_ANCHOR), failures, ("C10a",))
    run_case("c10b-usage-effect-no-config-key", 1,
             variant(REL_USAGE_STATS, FIX_USAGE_STATS_NO_CONFIG_KEY), failures, ("C10b",))
    run_case("failclosed-missing-usage-anchor", 1,
             variant(REL_USAGE_STATS, FIX_USAGE_STATS_MISSING_ANCHOR), failures, ("C10b",))
    # --- da1028-C: C11a / C11b / C11c (ThinkingRulesSection derived keys) ---
    run_case("c11a-builtins-no-baseurl-key", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_NO_BASEURL_KEY), failures, ("C11a",))
    run_case("c11a-builtins-no-modelEntries-key", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_BUILTINS_NO_MODEL_ENTRIES), failures, ("C11a",))
    run_case("c11b-sample-no-modelEntries-key", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_SAMPLE_NO_MODEL_ENTRIES), failures, ("C11b",))
    run_case("c11b-sample-keyless-remember", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_SAMPLE_KEYLESS), failures, ("C11b",))
    # precision: a remember this criterion does not own (keyless UI flag + a
    # Room loader) must NOT turn the gate red
    run_case("c11-precision-unrelated-remember-green", 0,
             variant(REL_THINKING_SECTION, FIX_THINKING_PRECISION_UNRELATED), failures)
    run_case("failclosed-missing-thinking-anchor", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_MISSING_ANCHOR), failures,
             ("C11a", "C11b", "C12", "C15a", "C15b"))
    # --- da1030-C: C12 (external rule writes must re-key the loader) -------
    run_case("c12-loader-no-revision-key", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_NO_REVISION_KEY), failures, ("C12",))
    # deep-audit-1043 B-02 — arm (c): the loader must leave the composition thread.
    run_case("c12-sync-loader-in-remember", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_SYNC_LOADER), failures, ("C12",))
    run_case("c12-loader-without-io-hop", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_NO_IO_HOP), failures, ("C12",))
    run_case("c12-no-revision-subscription", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_NO_SUBSCRIPTION), failures, ("C12",))
    # --- da1030-C: C13 (every mutator bumps the revision) ------------------
    run_case("c13-setall-no-bump", 1,
             variant(REL_THINKING_RESOLVER, FIX_RESOLVER_NO_BUMP_SETALL), failures, ("C13",))
    run_case("c13-restore-no-bump", 1,
             variant(REL_THINKING_RESOLVER, FIX_RESOLVER_NO_BUMP_RESTORE), failures, ("C13",))
    run_case("c13-no-revision-declaration", 1,
             variant(REL_THINKING_RESOLVER, FIX_RESOLVER_NO_DECL), failures, ("C13",))
    # wide-1024: a bump that is cancelled one line later is not a bump
    run_case("c13-cancelled-bump", 1,
             variant(REL_THINKING_RESOLVER, FIX_RESOLVER_CANCELLED_BUMP), failures, ("C13",))
    run_case("failclosed-missing-thinking-resolver", 1,
             missing(REL_THINKING_RESOLVER), failures, ("FILE",))
    run_case("c11c-detail-call-site-undrilled", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_NO_SECTION_ARGS), failures, ("C11c",))
    # --- da1033-I07: C15 (the dialog is handed the LIVE rule) ---------------
    run_case("c15-prefix-dialog-snapshot", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_PREFIX_DIALOG), failures,
             ("C15a", "C15b"))
    run_case("c15-dialog-stale-request", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_DIALOG_STALE_REQUEST), failures, ("C15a",))
    run_case("c15-no-close-effect", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_NO_CLOSE_EFFECT), failures, ("C15b",))
    run_case("c15-seed-not-by-id", 1,
             variant(REL_THINKING_SECTION, FIX_THINKING_SEED_NOT_BY_ID), failures, ("C15a",))
    # precision: the new-rule path seeds itself and must NOT be flagged
    run_case("c15-new-rule-seed-precision", 0,
             variant(REL_THINKING_SECTION, FIX_THINKING_NEW_RULE_SEED), failures)
    # --- da1033-I07: C14 (ThinkingRuleEditor seed keys) --------------------
    run_case("c14-editor-keyless-label", 1,
             variant(REL_THINKING_EDITOR, FIX_THINKING_EDITOR_KEYLESS_LABEL), failures, ("C14",))
    run_case("c14-editor-foreign-key", 1,
             variant(REL_THINKING_EDITOR, FIX_THINKING_EDITOR_FOREIGN_KEY), failures, ("C14",))
    run_case("c14-editor-field-keyed-precision", 0,
             variant(REL_THINKING_EDITOR, FIX_THINKING_EDITOR_FIELD_KEYED), failures)
    run_case("c14-editor-keyless-ui-flags-precision", 0,
             variant(REL_THINKING_EDITOR, FIX_THINKING_EDITOR_KEYLESS_UI_FLAGS), failures)
    run_case("failclosed-missing-thinking-editor", 1,
             missing(REL_THINKING_EDITOR), failures, ("FILE",))
    run_case("failclosed-missing-editor-anchor", 1,
             variant(REL_THINKING_EDITOR, FIX_THINKING_EDITOR_MISSING_ANCHOR), failures, ("C14",))
    # --- da1034-B4: C16 (env-var edit target disappearance) -----------------
    run_case("c16-envvar-prefix-no-close-effect", 1,
             variant(REL_ENVVAR_SCREEN, FIX_ENVVAR_PREFIX), failures, ("C16",))
    run_case("c16-envvar-unconditional-close", 1,
             variant(REL_ENVVAR_SCREEN, FIX_ENVVAR_UNCONDITIONAL_CLOSE), failures, ("C16",))
    run_case("c16-envvar-close-missing-entries-key", 1,
             variant(REL_ENVVAR_SCREEN, FIX_ENVVAR_NO_ENTRIES_KEY), failures, ("C16",))
    run_case("failclosed-missing-envvar-anchor", 1,
             variant(REL_ENVVAR_SCREEN, FIX_ENVVAR_MISSING_ANCHOR), failures, ("C16",))
    # precision: the clean fixture carries the deep-link effect (which also writes
    # `editEntryId = null`) plus keyless UI flags and stays green — C16 grades the
    # close effect, not every effect / remember in the file.
    # --- da1034-B4: C17 (WebDav dialog seeds) -------------------------------
    run_case("c17-webdav-keyless-seeds", 1,
             variant(REL_BACKUP_SCREEN, FIX_BACKUP_KEYLESS), failures, ("C17",))
    run_case("c17-webdav-partial-keys", 1,
             variant(REL_BACKUP_SCREEN, FIX_BACKUP_PARTIAL), failures, ("C17",))
    run_case("c17-webdav-foreign-key", 1,
             variant(REL_BACKUP_SCREEN, FIX_BACKUP_FOREIGN_KEY), failures, ("C17",))
    run_case("failclosed-missing-backup-anchor", 1,
             variant(REL_BACKUP_SCREEN, FIX_BACKUP_MISSING_ANCHOR), failures, ("C17",))
    run_case("failclosed-missing-backup-file", 1,
             missing(REL_BACKUP_SCREEN), failures, ("FILE",))
    # --- da1034-B4: C18 / C19 (the picker collapse state) -------------------
    run_case("c18-add-models-bare-config-key", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_BARE_CONFIG_KEY), failures, ("C18",))
    run_case("c18-agent-loop-bare-config-key", 1,
             variant(REL_AGENT_LOOP_SHEETS, FIX_AGENT_LOOP_BARE_CONFIG_KEY), failures, ("C18",))
    run_case("c18-add-models-keyless", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_KEYLESS), failures, ("C18",))
    run_case("c18-add-models-flag-only-key", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_FLAG_ONLY_KEY), failures, ("C18",))
    run_case("c18-add-models-inline-derivation", 0,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_INLINE_DERIVATION), failures)
    run_case("c18-add-models-unrelated-remember", 0,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_UNRELATED_REMEMBER), failures)
    run_case("failclosed-missing-add-models-anchor", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_MISSING_ANCHOR), failures, ("C18", "C24", "C25"))
    # --- dv28/B-07: C18 + C-SV2b mounted on the chat model picker -----------
    # the picker's private copy derives from `.instances` but FILTERS it on a
    # user-mutable flag — the pre-dv28 criterion accepted exactly this shape.
    run_case("c18-picker-inline-filtered-key", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_INLINE_FILTERED_KEY),
             failures, ("C18",))
    # the member label exists only when a persisted instance does (PRE shape)
    run_case("csv2-picker-member-label-guarded", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_MEMBER_LABEL_GUARDED),
             failures, ("C-SV2b",))
    # a `?.label` read that never consults the System source
    run_case("csv2-picker-member-label-bare", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_MEMBER_LABEL_BARE),
             failures, ("C-SV2b",))
    # --- da1039-B3: C24 / C25 (Quick Test target) ---------------------------
    run_case("c24-c25-add-models-snapshot-target", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_SNAPSHOT_TARGET), failures, ("C24", "C25"))
    run_case("c24-c25-agent-loop-no-quick-test", 1,
             variant(REL_AGENT_LOOP_SHEETS, FIX_AGENT_LOOP_NO_QUICK_TEST), failures,
             ("C24", "C25"))
    run_case("c25-add-models-no-close-effect", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_NO_CLOSE_EFFECT), failures, ("C25",))
    run_case("c25-add-models-unconditional-close", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_UNCONDITIONAL_CLOSE), failures, ("C25",))
    run_case("c25-add-models-no-list-key", 1,
             variant(REL_ADD_MODELS, FIX_ADD_MODELS_NO_LIST_KEY), failures, ("C25",))
    run_case("c19-picker-state-bare-config-key", 1,
             variant(REL_MODEL_ENTRY_PICKER, FIX_PICKER_STATE_BARE_CONFIG_KEY), failures, ("C19",))
    run_case("c19-picker-state-keyless", 1,
             variant(REL_MODEL_ENTRY_PICKER, FIX_PICKER_STATE_KEYLESS), failures, ("C19",))
    run_case("c19-picker-size-only-key", 1,
             variant(REL_MODEL_ENTRY_PICKER, FIX_PICKER_SIZE_ONLY_KEY), failures, ("C19",))
    run_case("failclosed-missing-picker-helper", 1,
             variant(REL_MODEL_ENTRY_PICKER, FIX_PICKER_MISSING_HELPER), failures, ("C19", "C32"))
    run_case("failclosed-missing-picker-file", 1,
             missing(REL_MODEL_ENTRY_PICKER), failures, ("FILE",))
    # --- da1040-B1: C26..C29 (ProviderDetailScreen live resolution) --------
    run_case("c26-detail-snapshot-write", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_SNAPSHOT_WRITE), failures, ("C26",))
    run_case("c26-detail-export-snapshot", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_EXPORT_SNAPSHOT), failures, ("C26",))
    run_case("c26-detail-no-resolver", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_NO_RESOLVER), failures, ("C26",))
    run_case("c27-detail-object-delete-target", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_OBJECT_TARGET), failures, ("C27",))
    run_case("c27-detail-no-row-wiring", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_NO_ROW_WIRING), failures, ("C27",))
    run_case("c28-detail-no-close-effect", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_NO_CLOSE_EFFECT), failures, ("C28",))
    run_case("c28-detail-unconditional-close", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_UNCONDITIONAL_CLOSE), failures,
             ("C28",))
    run_case("c29-detail-key-without-revision", 1,
             variant(REL_PROVIDER_DETAIL, FIX_PROVIDER_DETAIL_KEY_NO_REVISION), failures, ("C29",))
    # --- da1041-B1: C30 / C31 (the credential live source) -----------------
    # wide-1024: C30b joins — the sync read leaves the producer's output
    # unconsumed (the screen reads keys at composition instead).
    run_case("c30-list-sync-read", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_SYNC_READ), failures,
             ("C30a", "C30b"))
    run_case("c30-list-pre-baseline", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_PRE_BASELINE), failures,
             ("C30a", "C30b"))
    run_case("c30-list-no-revision-key", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_NO_REVISION_KEY), failures, ("C30b",))
    run_case("c30-list-no-io-hop", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_NO_IO_HOP), failures, ("C30b",))
    run_case("failclosed-list-no-read", 1,
             variant(REL_PROVIDER_LIST, FIX_PROVIDER_LIST_NO_READ), failures, ("C30",))
    run_case("c31-conn-key-no-revision", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_KEY_NO_REVISION), failures, ("C31",))
    run_case("failclosed-conn-no-key-read", 1,
             variant(REL_CONNECTION, FIX_CONNECTION_NO_KEY_READ), failures, ("C31",))
    # --- da1041-B4: C32 (the collapse read must consult the search) --------
    run_case("c32-sheet-ungated", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_PRE), failures, ("C32",))
    run_case("c32-sheet-inverted-polarity", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_INVERTED), failures, ("C32",))
    run_case("c32-sheet-derived-set", 0,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_DERIVED), failures)
    # C34 is registered on the same surface, so a reworked file fails BOTH
    # criteria closed — that is the intended shape, not an extra red.
    # dv28/B-07: C18 is mounted on this surface too, so a reworked file reds it
    # fail-closed as well (C-SV2b stays green — no member list, nothing to grade).
    run_case("failclosed-sheet-missing-anchor", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_MISSING_ANCHOR), failures,
             ("C18", "C32", "C34", "C35"))
    # --- dv25-B4: C35 (active entry shares the single source) / C36 (elvis) ---
    run_case("c35-picker-active-bare-find", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_ACTIVE_BARE),
             failures, ("C35",))
    run_case("c35-picker-active-fallback-bare-find", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_MEMBER_BARE),
             failures, ("C35",))
    run_case("c36-picker-elvis-eats-the-if", 1,
             variant(REL_MODEL_PICKER_SHEET, FIX_MODEL_PICKER_SHEET_ELVIS_SWALLOW),
             failures, ("C36",))
    run_case("c32-picker-render-ungated", 1,
             variant(REL_MODEL_ENTRY_PICKER, FIX_PICKER_RENDER_UNGATED), failures, ("C32",))
    if failures:
        print("SELFTEST FAILURES:")
        for f in failures:
            print("  " + f)
        print("PROVIDER_REMEMBER_SELFTEST=FAIL")
        return 1
    print("PROVIDER_REMEMBER_SELFTEST=PASS (%d arms)" % _ARM_COUNT[0])
    return 0


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        sys.exit(run_selftest())
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(run_tree(sys.argv[1]))


if __name__ == "__main__":
    main()
