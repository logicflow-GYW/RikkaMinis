#!/usr/bin/env python3
"""[da1019-J / ISSUE-10] Offload CLI argv + relative-path gate.

Pins the two shapes this branch fixed, so neither can silently regress:

  1. ContactsOffloadHandler must dispatch on the PARSED positional list
     (`parseContactsCommand`), never on the raw argv: no `rawArgs[0]`, no
     `rawArgs.drop(1)` subcommand body args, no `args.firstOrNull()` id
     lookup, and the raw-argv `--max` scan is gone (replaced by
     `listMax(parsedArgs)`).
  2. ConfigOffloadHandler's `--file` resolver must take the guest cwd, do the
     relative join in the pure `resolveMaybeRelative` helper, still route the
     joined path through `PRootKernel.resolvePathForSession` (fail-closed),
     and ALL THREE call sites must pass `request.cwd`.

[da1020-C] additions (relative paths + tar prefix robustness):

  3. BrowserUseOffloadHandler must take the guest cwd and do the relative
     join for `--cookies-file` and `--paths` BEFORE the session-aware
     resolver sees the path (`resolveMaybeRelative` / `resolveMaybeRelativeAll`
     first, then `resolvePathForSession`): a relative token must never reach
     the resolver raw.
  4. FastioOffloadHandler's tar verdict (`isTarCommand`) and the expander
     (`normalizeTarShorts`) must both call the SAME `firstSubcommandToken`
     helper — the tar flag set and the short-option expansion can never
     drift apart again (they used to be two copies of `firstOrNull() ==
     "tar"`, which broke whenever a global output flag preceded the
     subcommand).

[da1026-B] additions (ISSUE-02 — the global boolean-flag set, and the
handler that forgot to declare its own):

  5. `OffloadOutput.OUTPUT_FLAGS` must recognize `help` / `h`. `OffloadArgs`
     merges that set into every handler's `booleanFlags`, so ONLY a name
     listed here (or declared by the handler itself) is exempt from the
     greedy `--key value` branch. `--help <sub>` is the same shape as
     `--compact list`: with `help` missing from the set, `--help list`
     parsed as `--help = "list"`, `hasFlag("h", "help")` stayed false and the
     handler fell through to its DEFAULT subcommand and ran it for real
     (calendar/clipboard/location/notification/device …). Fail-closed: no
     `val OUTPUT_FLAGS … = setOf(…)` declaration found → red.
  6. `SpeakOffloadHandler` must declare `--stop` / `--status` as boolean
     flags: both are read with `hasFlag`, and an undeclared flag is eaten as
     a `--key value` pair (`android-speak --stop now` stored "now" as the
     value of `--stop`, so the stop branch was never reached).

[da1033-D] addition (ISSUE-04 — the payload that reaches `formatBody` is JSON):

  7. `PhotosOffloadHandler` / `ContactsOffloadHandler` must never hand
     `OffloadOutput.formatBody` a *bare* string literal, and must never
     `return` one from a payload helper. `formatBody` only re-shapes JSON —
     prose passes straight through, so `android-photos near` (empty radius)
     and `android-contacts search|get|list` (empty result / no cursor) used to
     exit 0 with an English sentence and break every downstream `jq` /
     `json.loads`; `near` additionally returned a bare JSONArray when it was
     NOT empty, so even the top-level type flipped with the result. Accepted
     shapes: variables, expressions (`JSONObject()…toString()`,
     `arr.toString(2)`, a helper call) and literals that already carry `{` /
     `[`. Judged on code-only text, so a prose payload quoted in a comment
     cannot satisfy OR trip the rule. Two spellings are pinned because the
     Photos payload is *returned* by a String-returning helper and therefore
     arrives at the call site as a variable: N1 (first argument) and N2
     (`return <literal>`).

[da1034-A4] addition (ISSUE-08 — the same payload contract, in the rest of
   the offload surface; and N2 is now scoped to the *helper*):

  8. The payload-file list grows from two handlers to eight: `near`'s defect
     was not unique to Photos. Location's `geocode` printed an English
     sentence on an empty geocode (exit 0) and bare address lines otherwise —
     a top-level type that flipped with the result; its uncaught handler, the
     notification/clipboard success messages, Open's `Opened: <url>` and
     Fastio's `usage()` all handed prose to `formatBody`. Every one of them
     must now carry JSON.
  9. N2 judges the *helper*, not the whole file: a `return <literal>` only
     counts when the function is the one whose result reaches `formatBody`
     (`formatBody(nearPhotos(…))`). The whole-file reading demanded ten
     rewrites of `FastioOffloadHandler`'s reason-code pairs
     (`return "not_found" to "…"`), which are not payloads — a rule that
     judges spelling instead of the defect. N1 stays whole-file.

Comment-aware: judgements run on code-only text, so a comment quoting a
forbidden pattern cannot satisfy the gate (and deleting a pinned line cannot
be masked by prose).

Usage:
  python3 scripts/scan/offload_cli_args_check.py <repo_root>   # check the tree
  python3 scripts/scan/offload_cli_args_check.py <repo_root> --selftest
Exit 0 = pass, 1 = violation (or selftest failure).
[dv27-A5 A-07] addition (the exit code of a help-guarded early exit):

 13. Five handlers (`android-model-use`, `android-alarm`, `android-photos`,
     `android-speech`, `android-a11y`) guarded `--help` AND a bare call with
     one condition (`hasFlag("h", "help") || positional.isEmpty()`) and then
     decided the exit code on POSITIONAL emptiness. `OffloadArgs` folds
     `--help` / `-h` into flags, so `tool --help` leaves positional empty
     exactly like a bare `tool`: the `else 0` arm was dead, every `tool --help`
     exited 2 and `set -e` / `cmd --help && …` probe chains broke (the agent
     reads a help request as a failure). H1 pins that a help probe reaches
     exit 0 (a positional test cannot separate the two invocations); H2 keeps
     the bare-invocation contract at 2 wherever the exit code varies; H3 is
     fail-closed on the derivation itself (>= 15 sites, one decidable early
     exit per help-probing file). The accepted spellings are derived, not
     listed: a literal 0, a `const val … = 0` name, and `if (<token count>)
     2 else 0` (DebugOffloadHandler's shape) all pass.

[dv29-A3 A-05] addition (the page-size contract, over every surface):

 14. `1048-A06` gave `android-contacts` a page-size resolver that refuses
     `--max abc` / `--max 0` (exit 2) and pinned it as CN0-CN4 over the contacts
     source. The four siblings kept `getInt(...) ?: default` / `.coerceIn(1, ...)`,
     so the same three defects stayed reachable: calendar answered `--limit -5`
     with exit 0, an empty page and a "-5 of 0 total records" warning; photos
     answered `--limit 0` with one row; sessions answered `--limit 1000` with
     100 rows; notification answered `--max abc` with every notification. The
     contract now lives in ONE file (`OffloadPageSize.kt`), whose SURFACES
     registry names each surface's handler - the gate derives its scope from
     there, so a new paginating handler cannot escape (PG0), a registered
     handler may not read/default/clamp a page size itself (PG2, whitelist = the
     empty set), the resolver must keep all five refusals and answer the default
     last (PG3), a refusal must reach the caller as a usage error (PG4), and the
     legacy `-n` alias must stay refused (PG5).

Success marker on selftest: OFFLOAD_CLI_ARGS_SELFTEST=PASS

[dv19-A1] additions (the same defect family one layer up: a decision read from
   the WRONG text, or a guard that watches the wrong moment):

 10. `ContactsOffloadHandler.bindLegacyShortMax` (V1e/V1f/V1g) — the subcommand
     probe runs BEFORE the alias is bound, so `-n 10` is still an unbound
     single-dash token there. Probing the raw argv put "10" in `positional[0]`,
     `sub` came out "10" (neither list nor search), the helper returned the
     un-rewritten argv and the caller refused with `unknown subcommand '10'`
     (exit 2) — while the same alias AFTER the subcommand worked. Pinned as a
     decision: the list handed to the probe is not the caller's argv, the
     `--max` rewrite is built no later than the probe, and the probed list is
     the one the function returns.
 11. `SessionsOffloadHandler` (J21/J22) — `--wait` was missing from the declared
     boolean set although the KDoc documents it as `send`'s default mode, so
     `minis-sessions-cli --wait send --prompt "hi"` bound "send" as the VALUE of
     `--wait`, left `positional` empty and ran the default `list` subcommand at
     exit 0. J21 reads the declared SET (membership, not spelling: `wait` must
     be in it and the value option `--wait-timeout` must not), J22 requires the
     flag to have its own HELP_TEXT line.
 12. `FastioSearch.grepTree` (S1/S2/S3a/S3b) — the caller spends ONE match
     budget across several roots (`remaining -= outcome.matches.size`), so a
     later root is walked with `limit == 0`. The cap used to be watched only
     inside `grepFile`, i.e. at the first MATCHING line: a root of non-matching
     files was read end to end (up to 8 MiB per file) to list nothing, and the
     directories it walked through were still charged as visited. Pinned on the
     `grepTree` body: S1 the entry answers a non-positive budget as capped with
     NO walk, S2 `preVisitDirectory` consults the cap BEFORE charging, S3a/S3b
     `visitFile` decides after `grepFile` returned instead of continuing.
"""
import re
import sys
from pathlib import Path

# [batch6-t01] 共享词法/语法/值流原语（**唯一**实现）。本门不再自写词法器：
# 注释剥离走 `mask_comments`（嵌套块注释 / 原始字符串 / 字符字面量 / `${}`
# 插值全由状态机处理），结构分析走 `mask_code`（字符串内容抹空格、长度与
# 源码严格相等，位置可回投），常量折叠走 `const_bool`，可达性走
# `path_feasible`，退出判定走 `returns_exiting`。
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    enclosing_conditions,
    function_body,
    if_blocks,
    last_write,
    match_bracket,
    split_top,
    strip_parens,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    expr_end,
    literal_view,
    path_feasible,
    returns_exiting,
    value_sources,
)

CONTACTS = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ContactsOffloadHandler.kt"
CONFIG = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ConfigOffloadHandler.kt"
BROWSERUSE = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/BrowserUseOffloadHandler.kt"
FASTIO = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioOffloadHandler.kt"
OUTPUT = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OffloadOutput.kt"
SPEAK = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SpeakOffloadHandler.kt"
PHOTOS = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"
NOTIFICATION = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/NotificationOffloadHandler.kt"
# [dv15-A1 A-01/A-02] the shared parser every handler goes through.
ARGS = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OffloadArgs.kt"
# [dv19-A1 A-03] the grep/find walk engine lives beside the handler; the
# per-root match budget is enforced there, so the gate has to read it.
FASTIO_SEARCH = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioSearch.kt"
# [dv22-A6 A-06] clipboard clear — see check_clipboard_clear.
CLIPBOARD = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ClipboardOffloadHandler.kt"
# [dv25-A4 A-07] the player CLI: its payload used to be the manager's English
# sentence, so N1 accepted a variable and N2 (same-file helpers) could not see
# the cross-file call. Registered here so both rules judge it, and Z1-Z4 below
# police the provenance itself.
PLAYER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PlayerOffloadHandler.kt"

# [da1034-A4] ISSUE-08: every handler that ships a `formatBody` payload. Kept
# explicit rather than a glob over the offload dir: a glob would also drag in
# the exit-2 `"…\n$HELP"` strings (Shizuku, ModelUse …) that deliberately never
# touch formatBody, and `payload_findings` would flag them.
PAYLOAD_FILES = [
    PHOTOS,
    CONTACTS,
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/CalendarOffloadHandler.kt",
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/LocationOffloadHandler.kt",
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/NotificationOffloadHandler.kt",
    CLIPBOARD,
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt",
    FASTIO,
    PLAYER,
]

# (id, kind, needle) — kind: "req" exact substring / "forbid" exact substring /
# "req_re" / "forbid_re" required-or-forbidden regex / "count_re" (needle = (re, n))
CONTACTS_RULES = [
    ("C1", "req", "val cmd = parseContactsCommand(rawArgs)"),
    ("C2", "req", 'val needsWrite = cmd.sub == "delete"'),
    ("C3", "req", "when (val sub = cmd.sub)"),
    ("C4", "req", '"get" -> doGet(cmd.id, parsedArgs)'),
    ("C5", "req", '"delete" -> doDelete(cmd.id, parsedArgs)'),
    ("C6", "req", "sub = args.positional.getOrNull(0)"),
    ("C7", "req", "id = args.positional.getOrNull(1)?.toLongOrNull()"),
    ("C8", "req", 'booleanFlags = setOf("help", "h")'),
    ("C9", "req", "internal fun resolvePageSize(parsedArgs: OffloadArgs, default: Int = DEFAULT_LIST_MAX)"),
    ("C10", "forbid", "rawArgs[0]"),
    ("C11", "forbid", "rawArgs.drop(1)"),
    ("C12", "forbid", "args.firstOrNull()?.toLongOrNull()"),
    ("C13", "forbid", 'it == "--max" || it == "-n"'),
]

CONFIG_RULES = [
    ("K1", "req", "private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String?"),
    ("K2", "req", "internal fun resolveMaybeRelative(path: String, cwd: String?): String ="),
    ("K3", "req", 'if (!path.startsWith("/") && !cwd.isNullOrEmpty() && cwd.startsWith("/"))'),
    ("K4", "req", 'if (cwd.endsWith("/")) "$cwd$path" else "$cwd/$path"'),
    ("K5", "req", "PRootKernel.resolvePathForSession(sessionId, resolved, context)"),
    ("K6", "req", "val resolved = resolveMaybeRelative(linuxPath, cwd)"),
    ("K7", "count_re", (r"readLinuxPath\(fileArg, request\.sessionId, request\.cwd\)", 3)),
    ("K8", "forbid_re", r"readLinuxPath\(fileArg, request\.sessionId\)(?!,)"),
    # [dv15-A1 A-02] the short options this handler reads as values must be
    # DECLARED at its parser call, or `-f pinned` silently becomes flag + stray
    # positional (the filter is dropped and the unfiltered list is returned).
    ("K9", "req", 'valueShortOptions = setOf("f", "p", "s")'),
]

# [dv15-A1 A-01/A-02] the shared parser's two value-binding decisions.
#   A-01: a token starting with `-` was never a value, so `--lat -33.86` left
#         `lat` UNSET and the number became a stray flag — the query then ran
#         unconstrained (the caller's `getDouble("lat")` returned null).
#   A-02: a single-dash option was ALWAYS a flag, so `-f pinned` dropped
#         `pinned` into the positionals and the command behaved as if the
#         option had not been given.
# The needles pin the DECISION (the negative-number predicate, the declared
# branch that writes into `values`), not a spelling: a rewrite that keeps the
# behaviour passes, the old coarse branches fail closed.
ARGS_RULES = [
    ("A1", "req", "private fun valueTokenOrNull(token: String?): String? ="),
    ("A2", "req", 'token?.takeIf { !it.startsWith("-") || isNegativeNumberToken(it) }'),
    ("A3", "req", "private fun isNegativeNumberToken(token: String): Boolean ="),
    ("A4", "req", "token.length > 1 && token[0] == '-' && token[1].isDigit()"),
    ("A5", "req", "private val valueShortOptions: Set<String> = emptySet(),"),
    ("A6", "req", "if (name in valueShortOptions && name !in effectiveBooleanFlags && value != null) {"),
    ("A7", "req", "vs[name] = value"),
    ("A8", "forbid_re", r'a\.startsWith\("-"\) && a\.length > 1 -> fs\.add\('),
]

# [da1026-B] OffloadOutput.OUTPUT_FLAGS must cover the universal help flags;
# judged against code_only text, so a comment listing help/h cannot satisfy it.
# Fail-closed: the declaration itself must exist.
OUTPUT_FLAGS_RULES = [
    ("O1", "req", "val OUTPUT_FLAGS: Set<String> = setOf("),
    ("O2", "req_re", r'val OUTPUT_FLAGS: Set<String> = setOf\([^)]*"help"[^)]*\)'),
    ("O3", "req_re", r'val OUTPUT_FLAGS: Set<String> = setOf\([^)]*"h"[^)]*\)'),
]

# [da1026-B] SpeakOffloadHandler reads --stop/--status via hasFlag, so the
# OffloadArgs call must declare them boolean (the handler previously used the
# no-arg default `booleanFlags = emptySet()`).
SPEAK_RULES = [
    ("S1", "req_re", r'OffloadArgs\(request\.argv\.drop\(1\),\s*booleanFlags\s*=\s*setOf\([^)]*"stop"[^)]*\)'),
    ("S2", "req_re", r'booleanFlags\s*=\s*setOf\([^)]*"status"[^)]*\)'),
]

# [da1020-C] BrowserUse relative-path join: the guest cwd must reach
# buildInputJson, and BOTH path-carrying options must resolve through the
# pure join helpers BEFORE the session-aware resolver sees them.
BROWSERUSE_RULES = [
    ("B1", "req", "private fun buildInputJson(args: OffloadArgs, sessionId: String?, cwd: String?): JSONObject"),
    ("B2", "req", "internal fun resolveMaybeRelativeAll(paths: List<String>, cwd: String?): List<String>"),
    ("B3", "req", "val tokens = resolveMaybeRelativeAll("),
    ("B4", "req", 'obj.put("paths", JSONArray(tokens))'),
    ("B5", "req", "val joined = resolveMaybeRelative(cookiesFile, cwd)"),
    ("B6", "req", "PRootKernel.resolvePathForSession(sessionId, joined, app)"),
    ("B7", "forbid_re", r"resolvePathForSession\(sessionId, cookiesFile, app\)"),
    ("B8", "req", "buildInputJson(args, request.sessionId, request.cwd)"),
]

# [da1020-C] Fastio tar-prefix robustness: the tar verdict and the short-option
# expander must share ONE subcommand-token helper (never two copies of
# `firstOrNull() == "tar"`), and the flag set must stay scoped to `tar`.
FASTIO_RULES = [
    ("F1", "req", "internal fun firstSubcommandToken(rawArgv: List<String>): String?"),
    ("F2", "req", 'val isTarCommand = firstSubcommandToken(rawArgv) == "tar"'),
    ("F3", "req", 'if (firstSubcommandToken(argv) != "tar") return argv'),
    ("F4", "forbid_re", r'firstOrNull\(\) (!=|==) "tar"'),
    ("F5", "req", "internal fun fastioBooleanFlags(isTarCommand: Boolean): Set<String>"),
    ("F6", "req", "booleanFlags = fastioBooleanFlags(isTarCommand)"),
    ("F7", "req", "internal fun normalizeTarShorts(argv: List<String>): List<String>"),
    ("F8", "req", "internal fun normalizeLongOptionSpelling(argv: List<String>): List<String>"),
]


# [da1028-A] ISSUE-08 — DebugOffloadHandler's pass-through subcommands.
# The handler used to parse argv ONCE, greedily (`OffloadArgs` treats every
# `-`-prefixed token anywhere as one of ITS flags) and then hand the leftovers
# to `exec` / `shizuku` / `model-use`: `minis-debug exec ls -l` lost the `-l`
# because the top level had already eaten it. Contract: a second structural
# view (`splitArgv` → globals / subcommand / verbatim tail) feeds the
# pass-through branches, while output shaping for those branches reads the
# GLOBAL slice only, so a child's own `-q` cannot strip this command's
# envelope. The rules pin the wiring; the anchor-drift case (a missing needle)
# is itself a finding.
DEBUG_HANDLER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/DebugOffloadHandler.kt"
DEBUG_RULES = [
    ("D1", "req", "internal fun splitArgv(tokens: List<String>): DebugArgvSplit {"),
    ("D2", "req", "internal fun forwardedArgv("),
    ("D3", "req", "): List<String> = if (sub in PASSTHROUGH_SUBCOMMANDS) rawRest else args.positional.drop(1)"),
    ("D4", "req", 'private val PASSTHROUGH_SUBCOMMANDS = setOf("exec", "shizuku", "model-use", "modelUse")'),
    ("D5", "req", "val argv = splitArgv(raw)"),
    ("D6", "req", "val sub = argv.subcommand"),
    ("D7", "req", "buildRpcCall(sub, args, argv.rawRest)"),
    ("D8", "req", "private fun buildRpcCall(sub: String, args: OffloadArgs, rawRest: List<String>): RpcCall {"),
    ("D9", "req", "val rest = forwardedArgv(sub, args, rawRest)"),
    ("D10", "req", 'RpcCall("debug.shizuku.exec", buildExecForwarder(rest, args))'),
    ("D11", "req", 'RpcCall("debug.modelUse.exec", buildExecForwarder(rest, args))'),
    ("D12", "req", "execCommandLine(rest)"),
    ("D13", "req", "if (sub in PASSTHROUGH_SUBCOMMANDS) OffloadArgs(argv.globals, BOOLEAN_FLAGS) else args"),
    ("D14", "forbid_re", r"buildExecForwarder\(args\.positional"),
    ("D15", "forbid", "buildRpcCall(sub, args)"),
    ("D16", "forbid", "forwardedArgv(sub, args)"),
    ("D17", "forbid_re", r"buildExecForwarder\(OffloadArgs\("),
]

# [da1032-C] ISSUE-08 — SessionsOffloadHandler's date bounds.
# The three date-carrying subcommands (list / search / messages) each parsed
# `parseDate(args.get("start"))` on their own and answered `null` on a value
# the caller DID spell but the parser could not read — and `null` means "no
# filter", so a typo returned the WHOLE library. The fix routes every bound
# through ONE funnel (resolveDateBounds → sessionsDateBound → parseSessionsDate)
# that throws [InvalidDateArg] for a supplied-but-unreadable value while an
# ABSENT flag still resolves to null (no filter — legal). The unknown-command
# message is generated from SUBCOMMANDS so it can no longer drift out of sync
# with the dispatch table (`send` was missing from the hand-written list).
SESSIONS = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt"
SESSIONS_RULES = [
    ("J1", "req", "private fun resolveDateBounds(args: OffloadArgs): Pair<Long?, Long?>"),
    ("J2", "req", 'val startMs = sessionsDateBound("--start", args.get("start"))'),
    ("J3", "req", 'val endMs = sessionsDateBound("--end", args.get("end"))'),
    ("J4", "count_re", (r"val \(startMs, endMs\) = resolveDateBounds\(args\)", 3)),
    ("J5", "req", "internal fun sessionsDateBound(flag: String, raw: String?, parse: (String) -> Long?): Long?"),
    ("J6", "req", "internal fun parseSessionsDate(raw: String?): Long?"),
    ("J7", "req", "internal class InvalidDateArg(flag: String, raw: String)"),
    ("J8", "req", "catch (e: InvalidDateArg)"),
    ("J9", "req", 'private val SUBCOMMANDS = listOf("list", "search", "messages", "send")'),
    ("J10", "req", 'Valid: ${SUBCOMMANDS.joinToString(", ")}'),
    ("J11", "forbid", "Valid: list, search, messages."),
    ("J12", "forbid_re", r"parseDate\(args\.get\(\"start\"\)\)"),
    ("J13", "forbid_re", r"parseEndDate\(args\.get\(\"end\"\)\)"),
    ("J14", "req", '"send" -> cmdSend(args)'),
    ("J15", "count_re", (r"throw InvalidDateArg\(flag, raw\)", 1)),
    # [dv15-A1 A-08] both bounds readable but inside out: the query
    # (`lastActive >= start AND lastActive <= end`) can match nothing, and the
    # command used to answer exit 0 with {"count":0,"sessions":[]} — read by the
    # caller as "no history in that period". J17 pins the STRICT comparison
    # (`--start X --end X` stays legal: parseEndDate aligns the end to that
    # day's 23:59:59.999), J18 fails closed on the non-strict variant, J19 pins
    # the delegation (both raws travel with the bounds for the message) and J20
    # the refusal itself.
    ("J16", "req", "internal fun sessionsDateWindow("),
    ("J17", "count_re", (r"endMs < startMs", 1)),
    ("J18", "forbid_re", r"endMs <="),
    ("J19", "count_re", (r'return sessionsDateWindow\(startMs, endMs, args\.get\("start"\), args\.get\("end"\)\)', 1)),
    ("J20", "req", "throw InvertedDateWindow("),
    # [dv19-A1 A-02] `--wait` is documented (cmdSend's KDoc) as the DEFAULT mode
    # of `send`, so it is a spelling callers legitimately write. While `wait` was
    # missing from the declared boolean set, `--wait send --prompt "hi"` bound
    # "send" as the VALUE of `--wait`, `positional` came out empty and the
    # command ran the default `list` subcommand at exit 0 — a wrong answer that
    # looks like a successful call. J21 reads the declared SET (membership, so
    # reordering/reformatting cannot trip it) and stays fail-closed on the value
    # option: declaring `--wait-timeout` boolean would eat its `<sec>`. J22 makes
    # the flag discoverable — `--wait-timeout`'s line does not satisfy it.
    ("J21", "decl_re", (r"booleanFlags = setOf\(([^)]*)\)", ['"wait"'], ['"wait-timeout"'])),
    ("J22", "count_re", (r"(?m)^\s*--wait\s+\S", 1)),
]


def code_only(src: str) -> str:
    """Comments out, literals kept verbatim — the LITERAL channel.

    [batch6-t01] 实现换成共享 `mask_comments`（`literal_view`）：嵌套块注释、
    三引号原始字符串、字符字面量 `'"'`、`${…}` 模板插值都由同一个状态机处理，
    长度与源码严格相等（位置可回投）。本门此前自写的正则版
    （`re.sub(r"/\\*.*?\\*/") + 每行 split("//")`）在四类构造上系统性错位：
    嵌套注释提前收尾、`"video/*"` 开出假注释、`'"'` 吞掉后文。

    解析失败抛 `KotlinLexError`（fail-closed，由 `main()` 落成 GATE-FAIL）。

    结构分析（括号配平 / 语句切分 / 函数体）必须走 `code_view()`。
    """
    return mask_comments(src)

def code_view(src: str) -> str:
    """Structural channel: comments AND string/char contents blanked, length equal."""
    return mask_code(src)

LEX_RULE_ID = "LEX"

def _fail_closed(fn):
    """共享词法器解析失败 ⇒ 一条违规（fail-closed），**不许**回退原文扫描。

    [batch6-t01] 硬约束：`KotlinLexError` 不是"跳过这一处的理由"。一份读不出
    词法结构的源码不能被判成合格，所以整门在这一份源上落一条 LEX 违规，
    规则 id 固定，便于 `--selftest` 断言。
    """
    def wrapper(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except KotlinLexError as exc:  # pragma: no cover - exercised by selftest
            return [(LEX_RULE_ID, "unlexable source",
                     f"{fn.__name__}: {exc} — fail-closed (no fallback to raw text)")]
    wrapper.__name__ = getattr(fn, "__name__", "check")
    wrapper.__doc__ = fn.__doc__
    return wrapper


@_fail_closed
def check_text(contacts_src: str, config_src: str, browseruse_src: str = "", fastio_src: str = "") -> list:
    """Return the list of violated rule ids (empty = compliant)."""
    findings = []
    contacts = code_only(contacts_src)
    config = code_only(config_src)
    browseruse = code_only(browseruse_src)
    fastio = code_only(fastio_src)
    for rid, kind, needle in CONTACTS_RULES:
        if kind in ("req", "forbid"):
            hit = needle in contacts
        if kind == "req" and not hit:
            findings.append((rid, "missing", needle))
        if kind == "forbid" and hit:
            findings.append((rid, "present", needle))
    for rid, kind, needle in CONFIG_RULES:
        if kind in ("req", "forbid"):
            hit = needle in config
            if kind == "req" and not hit:
                findings.append((rid, "missing", needle))
            if kind == "forbid" and hit:
                findings.append((rid, "present", needle))
        elif kind == "count_re":
            pattern, want = needle
            got = len(re.findall(pattern, config))
            if got != want:
                findings.append((rid, f"count {got} != {want}", pattern))
        elif kind == "forbid_re":
            if re.search(needle, config):
                findings.append((rid, "present", needle))
    for rid, kind, needle in BROWSERUSE_RULES:
        if kind in ("req", "forbid"):
            hit = needle in browseruse
            if kind == "req" and not hit:
                findings.append((rid, "missing", needle))
            if kind == "forbid" and hit:
                findings.append((rid, "present", needle))
        elif kind == "forbid_re":
            if re.search(needle, browseruse):
                findings.append((rid, "present", needle))
    for rid, kind, needle in FASTIO_RULES:
        if kind in ("req", "forbid"):
            hit = needle in fastio
            if kind == "req" and not hit:
                findings.append((rid, "missing", needle))
            if kind == "forbid" and hit:
                findings.append((rid, "present", needle))
        elif kind == "forbid_re":
            if re.search(needle, fastio):
                findings.append((rid, "present", needle))
    return findings


@_fail_closed
def check_flag_set(output_src: str, speak_src: str) -> list:
    """[da1026-B] Global boolean-flag set + the handler that forgot to declare.

    Judged on code-only text (a comment quoting the wanted set cannot pass).
    Fail-closed: a missing `val OUTPUT_FLAGS ... = setOf(...)` declaration is a
    finding, never a silent skip.
    """
    findings = []
    output = code_only(output_src)
    speak = code_only(speak_src)
    declared_literal = re.search(r"val OUTPUT_FLAGS: Set<String> = setOf\(([^)]*)\)", output)
    declared = set(re.findall(r'"([^"]*)"', declared_literal.group(1))) if declared_literal else None
    for rid, kind, needle in OUTPUT_FLAGS_RULES:
        if kind == "req":
            if needle not in output:
                findings.append((rid, "missing", needle))
        elif kind == "req_re":
            if declared is None:
                # O1 already reports the missing declaration; do not add noise.
                continue
            want = "help" if rid == "O2" else "h"
            if want not in declared:
                findings.append((rid, "missing", f'"{want}" in OUTPUT_FLAGS'))
    for rid, kind, needle in SPEAK_RULES:
        if not re.search(needle, speak):
            findings.append((rid, "missing", needle))
    return findings


@_fail_closed
def check_debug(debug_src: str) -> list:
    """[da1028-A] ISSUE-08 rules, judged on comment-stripped text.

    Fail-closed by construction: every needle is a `req` (missing → finding) or
    a `forbid` (present → finding), so a renamed helper, a rebuilt greedy parse
    at a pass-through branch, or a whole-file rewrite all land as findings
    rather than as a silently empty rule set.
    """
    findings = []
    debug = code_only(debug_src)
    for rid, kind, needle in DEBUG_RULES:
        if kind == "req":
            if needle not in debug:
                findings.append((rid, "missing", needle))
        elif kind == "forbid":
            if needle in debug:
                findings.append((rid, "present", needle))
        elif kind == "forbid_re":
            if re.search(needle, debug):
                findings.append((rid, "present", needle))
    return findings


@_fail_closed
def check_args(args_src: str) -> list:
    """[dv15-A1 A-01/A-02] rules for the shared OffloadArgs parser, on
    comment-stripped text.

    Fail-closed by construction: every needle is a `req` (missing → finding), a
    `forbid` (present → finding), a `count_re`, or a `forbid_re`. A parser
    rewrite that drops the value-binding decisions lands as findings rather
    than as a silently empty rule set.
    """
    findings = []
    args = code_only(args_src)
    for rid, kind, needle in ARGS_RULES:
        if kind in ("req", "forbid"):
            hit = needle in args
            if kind == "req" and not hit:
                findings.append((rid, "missing", needle))
            if kind == "forbid" and hit:
                findings.append((rid, "present", needle))
        elif kind == "count_re":
            pattern, want = needle
            got = len(re.findall(pattern, args))
            if got != want:
                findings.append((rid, f"count {got} != {want}", pattern))
        elif kind == "forbid_re":
            if re.search(needle, args):
                findings.append((rid, "present", needle))
    findings += violations_dash_branch(args)
    return findings


# ── [dv16-A A-02] the top-level single-dash branch ──────────────────────────
# A8 above forbids ONE spelling of the unguarded branch (`… > 1 -> fs.add(`).
# It cannot see the guard being *removed* from the block-bodied branch this
# parser actually uses: a negative number is then read as a short option named
# after its digits (`-33.86` → flag `33.86`), the positionals shift by one, and
# `android-photos near -33.86 151.21` answers about another place (or refuses
# with "missing argument"). So the decision point itself is pinned: the branch
# condition must carry a negative-number exception AND the declared-flag escape
# that keeps a digit-spelled switch legal where a handler declares it
# (`android-shizuku-cli package list -3`, declared in NO_VALUE_FLAGS).
DASH_BRANCH_MARKER = 'a.startsWith("-") && a.length > 1'
DASH_BRANCH_NEGATIVE = "isNegativeNumberToken"
DASH_BRANCH_DECLARED = "effectiveBooleanFlags"


def dash_branch_condition(args_src):
    """Condition text of the top-level single-dash branch: from the marker up to
    the `->` that opens the branch body. Depth-aware, so a `->` inside a nested
    lambda cannot end it early, and blind to string literals, so the `"-"`
    argument cannot either. None when the branch is not there at all
    (fail-closed — a rename must fail this gate, not skip it)."""
    i = args_src.find(DASH_BRANCH_MARKER)
    if i < 0:
        return None
    depth = 0
    j = i
    while j < len(args_src):
        c = args_src[j]
        if c == '"':
            k = args_src.find('"', j + 1)
            j = len(args_src) if k < 0 else k + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "-" and args_src.startswith("->", j) and depth == 0:
            return args_src[i:j]
        j += 1
    return None


def violations_dash_branch(args_src):
    """The branch must decide by the DECLARED vocabulary, not by the token's
    shape — that is what keeps `-33.86` a value everywhere while `-3` stays a
    switch where a handler declared it."""
    findings = []
    cond = dash_branch_condition(args_src)
    if cond is None:
        findings.append(("A9", "single-dash branch missing", DASH_BRANCH_MARKER))
        return findings
    if DASH_BRANCH_NEGATIVE not in cond:
        findings.append(("A9", "no negative-number exception in the branch condition",
                         DASH_BRANCH_MARKER))
    if DASH_BRANCH_DECLARED not in cond:
        findings.append(("A9", "no declared-flag escape in the branch condition",
                         DASH_BRANCH_DECLARED))
    return findings


@_fail_closed
def check_sessions(sessions_src: str) -> list:
    """[da1032-C] ISSUE-08 rules for SessionsOffloadHandler, on comment-stripped text.

    Fail-closed by construction: every needle is a `req` (missing → finding), a
    `forbid` (present → finding), a `count_re`, or a `forbid_re`. A renamed
    funnel, a rebuilt per-command parse, or a whole-file rewrite all land as
    findings rather than as a silently empty rule set.
    """
    findings = []
    sessions = code_only(sessions_src)
    for rid, kind, needle in SESSIONS_RULES:
        if kind in ("req", "forbid"):
            hit = needle in sessions
            if kind == "req" and not hit:
                findings.append((rid, "missing", needle))
            if kind == "forbid" and hit:
                findings.append((rid, "present", needle))
        elif kind == "count_re":
            pattern, want = needle
            got = len(re.findall(pattern, sessions))
            if got != want:
                findings.append((rid, f"count {got} != {want}", pattern))
        elif kind == "decl_re":
            # [dv19-A1 A-02] judge a declared SET by membership, not spelling:
            # `needle = (re, names that must be in group(1), names that must not)`.
            pattern, must, must_not = needle
            m = re.search(pattern, sessions)
            if m is None:
                findings.append((rid, "missing", pattern))
            else:
                declared = m.group(1)
                for name in must:
                    if name not in declared:
                        findings.append((rid, f"undeclared {name}", pattern))
                for name in must_not:
                    if name in declared:
                        findings.append((rid, f"must not declare {name}", pattern))
        elif kind == "forbid_re":
            if re.search(needle, sessions):
                findings.append((rid, "present", needle))
    return findings


def describe(findings) -> str:
    return "\n".join(f"  ✗ {rid}: {kind} → {needle}" for rid, kind, needle in findings)


# ── [da1033-D] ISSUE-04: the payload handed to OffloadOutput.formatBody is JSON ──
#
# `OffloadOutput.formatBody` only *re-shapes* JSON — a plain sentence passes
# through untouched (OffloadOutput.kt:61-80, both the no-flag fast path and the
# parse-failure fallback). So `android-photos near` and `android-contacts
# search|get|list` used to exit 0 with an English sentence whenever the result
# was empty (the most common outcome), breaking every `jq` / `json.loads`
# downstream — and `near` returned a bare JSONArray when it was NOT empty, so
# even the top-level type flipped with the result.
#
# Decision point (deliberately NOT a call form): a *bare string literal* must
# never be the payload. Accepted, in any spelling: a variable, any expression
# (`JSONObject()…toString()`, `arr.toString(2)`, `photos.toString(2)`, a helper
# call), or a literal that already carries JSON delimiters (`{` / `[`, e.g. the
# `"""{"error":…}"""` form, or a template with an interpolated object).
#
# Two sites, because the defect has two spellings:
#   N1 — the first argument of every `formatBody(` call in the payload files.
#   N2 — every `return <literal>` inside a *payload helper*: a function whose
#        result is the first argument of a `formatBody(` call (the Photos
#        payload is produced by the String-returning `nearPhotos`, so at the
#        call site it is just a variable — N1 alone would be blind to it; this
#        gate exists because the *review* proposed N1 only).
#
# [da1034-A4] ISSUE-08 widened the file list to every handler that ships a
# formatBody payload, and that is what forced N2's scope to be the *helper*
# rather than the whole file: `FastioOffloadHandler` returns bare literals from
# ten non-payload helpers (`return "not_found" to "…"` — a reason-code Pair that
# later becomes the JSON envelope, not a payload), so the whole-file reading
# demanded ten rewrites of working code. N2 now judges the defect (a literal
# that reaches formatBody) instead of the spelling (any literal return).
_STR_LIT_RE = re.compile(r'"(?:[^"\\\n]|\\.)*"')
_RAW_LIT_RE = re.compile(r'"""(?:[^"]|"(?!""))*"""')


def _literal_at(src: str, i: int):
    """Source text of the Kotlin string literal starting at `i`, else None."""
    if src.startswith('"""', i):
        m = _RAW_LIT_RE.match(src, i)
        return m.group(0) if m else None
    if src[i] == '"':
        m = _STR_LIT_RE.match(src, i)
        return m.group(0) if m else None
    return None


def _mask_templates(lit: str) -> str:
    """Drop Kotlin `${…}` interpolations, leaving the literal's own text.

    Load-bearing: `${radiusKm}` contains a `{`, so a naive delimiter test would
    call `"No photos found within ${radiusKm}km."` JSON — the *Before* shape of
    this very defect. The delimiter has to come from the literal text.
    """
    out = []
    i = 0
    while i < len(lit):
        if lit[i] == "$" and i + 1 < len(lit) and lit[i + 1] == "{":
            depth = 0
            j = i + 1
            while j < len(lit):
                if lit[j] == "{":
                    depth += 1
                elif lit[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            i = j + 1  # skip the whole interpolation
            continue
        out.append(lit[i])
        i += 1
    return "".join(out)


def _is_json_literal(lit: str) -> bool:
    """A literal is JSON-shaped when its OWN text carries `{` / `[`.

    `"{\\"error\\":\\"$code\\"}"` → yes. `"No photos found within ${km}km."` →
    no (the brace is interpolation, not structure). A literal that is nothing
    but interpolation (`"${obj}"`) counts as a variable in a template coat and
    is accepted, same as passing `obj` directly.
    """
    core = _mask_templates(lit).strip().strip('"').strip()
    if core == "":
        return True  # pure interpolation — equivalent to passing the variable
    return "{" in core or "[" in core


def _payload_helper_bodies(code: str, name: str):
    """Body text of `fun <name>(…)`, or None (declared elsewhere / expression body).

    [batch6-t01] 体区间由共享 `function_body` 派生：参数表里的默认 lambda
    （`fun f(a: Int = { 1 })`）不再被当成函数体，表达式体也不会读到**下一个**
    函数的块。结构判定在 `mask_code` 视图上做（字符串里的 `}` 无法截断体），
    切片回投调用方给的文本（两视图严格等长，位置可互换）。
    """
    span = _fn_span(mask_code(code), name)
    if span is None:
        return None
    return code[span[0]:span[1]]

def _payload_helper_names(code: str) -> list:
    """Names of the functions whose result is fed to `formatBody(` — the N2
    scope. Sorted so fixture output is stable."""
    names = {m.group(1) for m in re.finditer(r"formatBody\(\s*([A-Za-z_]\w*)\s*\(", code)}
    return sorted(names)


_PAYLOAD_BIND_RE = re.compile(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?=(?!=)")

def _fun_spans(struct: str) -> list:
    """[(name, body_start, body_end)] — 每个 `fun` 声明自己的体区间（结构视图）。

    [batch6-t01] 用共享 `match_bracket` / `expr_end` 走参数表和体，重载/默认
    参数 lambda/表达式体都分开；不写第二个词法器。
    """
    out = []
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", struct):
        p_close = match_bracket(struct, m.end() - 1)
        if p_close < 0:
            continue
        i = p_close + 1
        while i < len(struct) and struct[i] not in "={":
            i += 1
        if i >= len(struct):
            continue
        if struct[i] == "{":
            j = match_bracket(struct, i)
            if j > i:
                out.append((m.group(1), i + 1, j))
        else:
            out.append((m.group(1), i + 1, expr_end(struct, i + 1)))
    return out

def _payload_binding(struct: str, name: str, pos: int):
    """pos 之前、**同一函数体内**最近的一次 `val name =`，返回右值区间或 None。"""
    f_start, f_end = 0, len(struct)
    for _n, b_start, b_end in _fun_spans(struct):
        if b_start <= pos <= b_end and b_start >= f_start:
            f_start, f_end = b_start, b_end
    last = None
    for m in _PAYLOAD_BIND_RE.finditer(struct, f_start, f_end):
        if m.group(1) == name and m.start() < pos:
            last = m
    if last is None:
        return None
    return last.end(), expr_end(struct, last.end())

def _payload_sources(expr: str, lit: str, struct: str, pos: int, depth: int = 0) -> list:
    """表达式的所有可能最终值：剥丢弃型作用域 + 展开同文件局部 val（≤3 跳）。

    [batch6-t01] N1 曾经对一切**变量**放行（`continue  # variable / expression
    / helper call → accepted`），只给 Z2 补了"跨文件 String 委托"，于是
    `val body = "android-contacts: no contact with id=$id"` + `formatBody(body, …)`
    落在 N1 / N2 / Z2 三者之间的真空带里。这里把"这个位置真正求值的是什么"
    补上：绑定的右值同样要被判，1 跳不够就跟着链走（上限 3 跳，超出保守放行）。
    """
    if depth > 3:
        return [expr]
    e = strip_parens(expr.strip())
    if not e:
        return [expr]
    if re.fullmatch(r"[A-Za-z_]\w*", e):
        span = _payload_binding(struct, e, pos)
        if span is None:
            return [e]
        return _payload_sources(lit[span[0]:span[1]], lit, struct, span[0], depth + 1)
    out = []
    for sub in value_sources(e):
        s2 = sub.strip()
        if s2 and s2 != e:
            out += _payload_sources(s2, lit, struct, pos, depth + 1)
        elif s2:
            out.append(s2)
    return out or [e]

def _lone_literal(expr: str):
    """表达式是否**只有一个**字符串字面量（拼接/运算一律不算），返回该字面量。"""
    e = strip_parens(expr.strip())
    if not e or e[0] != '"':
        return None
    lit = _literal_at(e, 0)
    if lit is None or e[len(lit):].strip():
        return None
    return lit

def payload_findings(label: str, src: str) -> list:
    """N1 / N2 for one payload file.

    [batch6-t01] 双视图：`code`（字面量通道）读载荷字符串本身，`struct`
    （结构通道）切实参与函数体。N1 现在既判直接字面量，也判**局部变量绑定**
    出来的字面量（同文件 `val body = "prose"`）。
    """
    findings = []
    code = code_only(src)
    struct = code_view(src)
    for m in re.finditer(r"formatBody\(", struct):
        args = call_args(struct, m.end() - 1)
        if not args:
            findings.append(("N1", "unreadable call", label))
            continue
        a, b = args[0]
        j = a
        while j < b and struct[j] in " \t\r\n":
            j += 1
        if j < b and struct[j] == '"':
            lit = _literal_at(code, j)
            if lit is None:
                findings.append(("N1", "unreadable literal", label))
            elif not _is_json_literal(lit):
                findings.append(("N1", "bare non-JSON payload literal", label + ": " + lit[:70]))
            continue
        for expr in _payload_sources(code[a:b], code, struct, a):
            lit = _lone_literal(expr)
            if lit is None or _is_json_literal(lit):
                continue
            findings.append(
                ("N1", "bare non-JSON payload via a local binding",
                 label + ": " + lit[:70]))
    for name in _payload_helper_names(code):
        body = _payload_helper_bodies(code, name)
        if body is None:
            continue
        for m in re.finditer(r"\breturn\s+(?=\")", body):
            lit = _literal_at(body, m.end())
            if lit is None:
                findings.append(("N2", "unreadable literal", f"{label}: {name}()"))
            elif not _is_json_literal(lit):
                findings.append(
                    ("N2", "bare non-JSON payload return", f"{label}: {name}() " + lit[:70]))
    return findings


@_fail_closed
def check_offload_json(*files) -> list:
    """[da1033-D] ISSUE-04 + [da1034-A4] ISSUE-08 rules for the payload files.

    [files] is (label, source) pairs; every one of them gets N1, and N2 where a
    payload helper exists.
    """
    out = []
    for label, src in files:
        out += payload_findings(label, src)
    return out

# ── [dv14-A1] deep-audit-v14 ISSUE-01 / ISSUE-02 / ISSUE-04: alias bindings ──
#
# One process-wide fact ties the three together: OffloadArgs binds NO value to a
# single-dash token (`-n` becomes a bare flag and its value lands in
# `positional`), and a `--key` is exempt from the greedy `--key value` branch
# ONLY when its name is in the declared boolean set. Every defect below is a
# name the code accepts with none of the value machinery behind it — so the
# call succeeds with wrong output (exit 0, `jq`-visible) instead of being
# refused. Each rule pins the DEFINITION (the function that owns the decision),
# never a call spelling:
#
#  V1 ContactsOffloadHandler — `doSearch` must resolve its page size through
#     `listMax` (the same resolver `doList` uses; `getInt("max", "n")` is the
#     dead alias spelling and must not come back), and the legacy `-n <N>` form
#     must be bound by `bindLegacyShortMax` BEFORE the parse, or its value takes
#     the query slot (`search -n 10 Alice` searched for the name "10").
#  V2 NotificationOffloadHandler — `handleSend` must decide
#     deferred-vs-immediate from the RAW `--after` string via `sendDisposition`:
#     `getInt("after")` collapses "spelled but unreadable" (`--after 5m`) into
#     "not spelled" and fires the notification immediately with exit 0, and
#     `scheduleDeferred`'s "--after or --at is required" refusal (unreachable
#     while the branch was gated on the parsed value) must stay live.
#  V3 FastioOffloadHandler — `firstSubcommandToken` must consult
#     `fastioBooleanFlags` (the same set the shared parse uses) while keeping
#     OUTPUT_FLAGS: knowing only OUTPUT_FLAGS, a leading `--force` / `--verbose`
#     / `--create` swallowed the `tar` subcommand as its value, so
#     `isTarCommand` came out false and the `-cf`/`-xf` expansion never ran.
#
# Fail-closed by construction: every arm reports its own id when its anchor is
# absent, so a renamed / restructured / deleted owner function lands as a
# finding instead of shrinking the rule set silently. Every needle is judged on
# comment-stripped text (a comment quoting the wanted shape cannot pass) and the
# bodies come from the same brace matcher the N2 arm uses — an expression body
# has no owner body to judge and is itself a finding.
@_fail_closed
def check_offload_cli_bindings(contacts_src: str, notification_src: str,
                               fastio_src: str) -> list:
    """[dv14-A1] the alias-binding rules (V1 contacts, V2 notification, V3 fastio)."""
    findings = []
    contacts = code_only(contacts_src)
    notif = code_only(notification_src)
    fastio = code_only(fastio_src)

    # V1 — the dead alias read + the resolver wiring.
    if 'getInt("max", "n")' in contacts:
        findings.append(
            ("V1a", "present", 'getInt("max", "n") — OffloadArgs never fills values["n"]'))
    do_search = _payload_helper_bodies(contacts, "doSearch")
    if do_search is None:
        findings.append(("V1b", "missing", "private fun doSearch(parsedArgs: OffloadArgs)"))
    elif "resolvePageSize(" not in do_search:
        findings.append(("V1b", "missing", "doSearch must resolve its page size through resolvePageSize("))
    parse_contacts = _payload_helper_bodies(contacts, "parseContactsCommand")
    if parse_contacts is None:
        findings.append(("V1c", "missing", "internal fun parseContactsCommand(argv: List<String>)"))
    elif "bindLegacyShortMax(argv)" not in parse_contacts:
        findings.append(
            ("V1c", "missing",
             "parseContactsCommand must bind the legacy -n alias (bindLegacyShortMax(argv))"))
    if _payload_helper_bodies(contacts, "bindLegacyShortMax") is None:
        findings.append(
            ("V1d", "missing", "internal fun bindLegacyShortMax(argv: List<String>): List<String>"))

    # V1e/V1f/V1g — [dv19-A1 A-01] the subcommand probe must read the REWRITTEN
    # list. The probe runs before the alias is bound, so `-n 10` is still an
    # unbound single-dash token there: judging the raw argv put "10" in
    # positional[0], `sub` came out "10" (neither list nor search), the helper
    # returned the un-rewritten argv and the caller refused with
    # `unknown subcommand '10'` (exit 2) — while the same alias AFTER the
    # subcommand worked. Pinned as the decision, not a spelling: the list handed
    # to the probe is not the caller's argv, the `--max` rewrite is built no
    # later than the probe, and the probed list is the one the function returns.
    bind = _payload_helper_bodies(contacts, "bindLegacyShortMax")
    if bind is not None:
        probe = re.search(r"OffloadArgs\(\s*([A-Za-z_]\w*)\s*[,)]", bind)
        if probe is None:
            findings.append(
                ("V1e", "missing",
                 "bindLegacyShortMax must probe the subcommand through OffloadArgs("))
        else:
            probed = probe.group(1)
            if probed == "argv":
                findings.append(
                    ("V1e", "present",
                     "the subcommand probe reads the raw (pre-rewrite) argv"))
            elif not re.search(r"\breturn\s+" + re.escape(probed) + r"\b", bind):
                findings.append(
                    ("V1g", "missing",
                     f"the probed list ({probed}) must be the list the helper returns"))
        max_at = bind.find('"--max"')
        if max_at < 0:
            findings.append(
                ("V1f", "missing", 'the `--max` rewrite of the `-n <N>` alias'))
        elif probe is not None and max_at > probe.start():
            findings.append(
                ("V1f", "present", "the `--max` rewrite runs AFTER the subcommand probe"))

    # V2 — the deferred decision, its owner function, and its refusal guard.
    if 'getInt("after")' in notif:
        findings.append(
            ("V2a", "present",
             'getInt("after") erases "spelled but unreadable" into "not spelled"'))
    handle_send = _payload_helper_bodies(notif, "handleSend")
    if handle_send is None:
        findings.append(("V2b", "missing", "private fun handleSend(sub: String, args: OffloadArgs)"))
    elif "sendDisposition(" not in handle_send:
        findings.append(("V2b", "missing", "handleSend must decide through sendDisposition("))
    if _payload_helper_bodies(notif, "sendDisposition") is None:
        findings.append(
            ("V2c", "missing",
             "internal fun sendDisposition(sub: String, rawAfter: String?, rawAt: String?): SendDisposition"))
    if "android-notification: --after or --at is required\\n" not in notif:
        findings.append(
            ("V2d", "missing", "scheduleDeferred's '--after or --at is required' refusal"))

    # V3 — the raw-argv scan must know the same boolean set the parse uses.
    scan = _payload_helper_bodies(fastio, "firstSubcommandToken")
    if scan is None:
        findings.append(("V3c", "missing", "internal fun firstSubcommandToken(rawArgv: List<String>)"))
    else:
        if "fastioBooleanFlags(" not in scan:
            findings.append(
                ("V3a", "missing", "firstSubcommandToken must consult fastioBooleanFlags("))
        if "OUTPUT_FLAGS" not in scan:
            findings.append(
                ("V3b", "missing",
                 "firstSubcommandToken must keep excluding OffloadOutput.OUTPUT_FLAGS"))
        # [dv18-A A-01b] the pre-scan must consume value-taking short options
        # from a declared set that includes `-C` (tar's --directory): a scan
        # that skips `-C /tmp` mis-reads `/tmp` as the subcommand, so the tar
        # verbs never get declared and `--directory` never expands.
        shorts_decl = re.search(r"SHORT_VALUE_OPTIONS\s*=\s*setOf\(([^)]*)\)", fastio)
        if shorts_decl is None or "-C" not in shorts_decl.group(1):
            findings.append(
                ("V3d", "missing",
                 "SHORT_VALUE_OPTIONS must be declared and include -C (tar --directory)"))
        if "SHORT_VALUE_OPTIONS" not in scan:
            findings.append(
                ("V3e", "missing", "firstSubcommandToken must consult SHORT_VALUE_OPTIONS"))
        if not re.search(r"i \+= if \(consumesValue\) 2 else 1", scan):
            findings.append(
                ("V3f", "missing",
                 "firstSubcommandToken must consume the value token (i += 2)"))
    return findings


# ── [dv19-A1 A-03] the match budget is spent per ROOT, not per tree ─────────
#
# `grep` (FastioOffloadHandler) carries ONE budget across several roots —
# `remaining -= outcome.matches.size` — and hands a later root `limit = 0`. The
# cap used to be watched in exactly one place (`grepFile`, i.e. at the first
# MATCHING line), so such a root was walked end to end: every non-matching file
# read (up to MAX_GREP_FILE_BYTES = 8 MiB each) to list nothing, and the
# directories it descended were still charged as visited (turning a
# matches_capped result into `truncated` too). Each rule pins one of the three
# moments a walk can spend that budget, on the `grepTree` body:
#   S1  the entry: a non-positive budget is answered as capped, with NO walk
#   S2  preVisitDirectory consults the cap BEFORE charging the directory
#   S3a/S3b visitFile decides after grepFile returned (the file that filled the
#       budget ends the walk) instead of continuing unconditionally
def _brace_block(text: str, marker: str):
    """`{…}` block whose opening brace is the first one after `marker`.

    [batch6-t01] 配平走共享 `match_bracket`（`text` 是 `code_view()` 结果），
    自写的裸括号计数器退役：字符串里的 `}` 曾让块体提前闭合（parser-death）。
    """
    i = text.find(marker)
    if i < 0:
        return None
    j = text.find("{", i)
    if j < 0:
        return None
    k = match_bracket(text, j)
    return text[j:k + 1] if k > j else None

def _paren_close(text: str, open_idx: int, opener: str = "(", closer: str = ")"):
    """Index of the [closer] matching text[open_idx], or -1 (fail-closed)."""
    if (opener, closer) != ("(", ")"):
        depth = 0
        for i in range(open_idx, len(text)):
            if text[i] == opener:
                depth += 1
            elif text[i] == closer:
                depth -= 1
                if depth == 0:
                    return i
        return -1
    return match_bracket(text, open_idx)

def _dead_condition(cond: str) -> bool:
    """Is this `if` condition constant-folded **false**?

    [batch6-t01] 旧实现是字面量白名单：行首锚 `^false` / `^!\\s*true`，再
    `any(part.strip() == "false" for part in c.split("&&"))`。于是同一族里所有
    等价拼写都穿门（实证见证）：
      `limit <= 0 && (1 == 2)`   ← `(1 == 2)` 不是裸 `false`
      `limit <= 0 && !true`      ← `!true` 不在 `&&` 切分后检查
      `(0 == 1) && !sendSucceeded(status)`
    一个判据被门内多个臂（S1 / S2 …）共用，缺口同族放大。改为共享 `const_bool`：
    Kotlin 优先级（`!` > 比较 > `&&` > `||`）、短路语义、括号与常量比较全折。
    """
    return const_bool(cond) is False

def _if_site(text: str, cond_re: str):
    """(condition, then-branch text) of the first `if (<cond matching cond_re>)`.

    [batch6-t01] 结构改走共享 `if_blocks`（brace / braceless 两种形态、else
    区间都给出），`text` 必须是 `code_view()` 结果。旧实现自带括号扫描 +
    裸花括号计数，字符串里的括号能让它读错分支。
    """
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(text):
        if re.search(cond_re, cond):
            return cond, text[t_start:t_end]
    return None

def _fn_span(struct: str, name: str):
    """(start, end) of `fun <name>`'s BLOCK body in the structural view, or None.

    [batch6-t01] 由共享 `function_body` 派生（参数表默认 lambda、返回类型、
    表达式体都能正确跨过）。表达式体没有 `return` 语句可判，按 None 处理
    （fail-closed 由调用方落成违规）。
    """
    span = function_body(struct, name)
    if span is None or span[2] != "block":
        return None
    return span[0], span[1]

def _grep_cap_producer(code: str) -> list:
    """[R3-t14 #152] the cap that grepTree terminates on must be SET, at the budget.

    Witness 15-2 deleted exactly one line from `grepFile`:
    `out.matchesCapped = true` at `if (out.matches.size >= limit)`. Every
    criterion in S1/S2/S3a stayed green because all three read the CONSUMER
    (`grepTree`), and the producer — the only place that can set the flag while
    files are being read — was nobody's business. Consequence: the walk never
    receives `FileVisitResult.TERMINATE`, keeps opening every remaining file
    (up to MAX_GREP_FILE_BYTES each) and reports `truncated: false` while
    holding `limit` matches.

    So the producer is judged on ITS OWN terms: the budget-exhausted guard must
    be readable, feasible, and its branch must set the flag and stop the read
    loop — with no later reset inside the function (a `= false` after it would
    re-open the same hole).
    """
    span = function_body(code, "grepFile")
    if span is None or span[2] != "block":
        return [("S4", "missing",
                 "private fun grepFile(…) — the budget producer cannot be read "
                 "(fail-closed: without it the cap grepTree waits for has no writer)")]
    body = code[span[0]:span[1]]
    site = None
    for cond, t_start, t_end, _es, _ee in if_blocks(body):
        if re.search(r"matches\s*\.\s*size\s*>=?\s*limit", cond):
            site = (cond, t_start, t_end)
            break
    if site is None:
        return [("S4", "missing",
                 "grepFile no longer tests the budget (`out.matches.size >= limit`) — "
                 "out.matchesCapped is then never set, so grepTree walks (and READS) the "
                 "whole tree and reports truncated: false")]
    cond, t_start, t_end = site
    if const_bool(cond) is False:
        return [("S4", "dead",
                 "the budget-exhausted branch is constant-folded false (`%s`) — "
                 "matchesCapped is never set, so the walk never terminates"
                 % " ".join(cond.split())[:60])]
    if not path_feasible(body, t_start):
        return [("S4", "unreachable",
                 "the budget-exhausted branch sits in a path that can never run — "
                 "matchesCapped is never set")]
    branch = body[t_start:t_end]
    if not re.search(r"matchesCapped\s*=\s*true", branch):
        return [("S4", "missing",
                 "the budget-exhausted branch returns WITHOUT setting out.matchesCapped — "
                 "every consumer in grepTree reads a flag that stays false: the walk runs "
                 "to the end of the tree and the payload says truncated: false")]
    if not re.search(r"\b(return|break)\b", branch):
        return [("S4", "missing",
                 "the budget-exhausted branch sets the cap and keeps reading the file — "
                 "it must stop the read loop")]
    writes = [(m.start(), m.group(0)) for m in
              re.finditer(r"matchesCapped\s*=\s*(?:true|false)", body)]
    if writes and writes[-1][1].split("=")[-1].strip() != "true":
        return [("S4", "reset",
                 "the cap is written back to false later in grepFile (`%s`) — the "
                 "budget decision is undone before the caller can observe it"
                 % writes[-1][1].strip())]
    return []


# ── [R3-t14 #151/#153] the two argv normalizers must DECIDE ─────────────────
#
# Both witnesses left every token F7/F8 pin untouched and gutted the FUNCTION
# BODY instead:
#
#   15-1 `normalizeLongOptionSpelling(argv) = argv` — the agent's POSIX `find`
#        spelling (`-name "*.kt" -type f -limit 50`) then reaches OffloadArgs
#        as the short flags -n/-a/-m/-e plus three stray positionals: `name`
#        and `type` are null, the filters are gone and the file patterns are
#        reported as missing directories.
#   15-3 `'C' -> Unit` inside `normalizeTarShorts` — `known` stays true, so the
#        token is "expanded" into nothing: `tar -xf a.tar -C /dest` drops the
#        target directory and unpacks into the cwd (and `/dest` becomes a
#        member pattern).
#
# Signature presence is not the decision. Both tables are DECLARED in the
# source (`SHORT_LONG_OPTIONS` / `SHORT_VALUE_OPTIONS`), so the criteria are
# derived from those declarations: what the rewrite must do to a member, and
# what an arm of the short-option table may do to a letter.
SHORT_LONG_SET = "SHORT_LONG_OPTIONS"
SHORT_VALUE_SET = "SHORT_VALUE_OPTIONS"
_DECL_RE = r"\b%s\s*=\s*setOf\(([^)]*)\)"
# A dash is ADDED to the token: `"-$it"`, `"--$it"`, `"-" + it`, `"--" + …`.
DASH_TEMPLATE_RE = re.compile(r'"(-+)\s*\$\s*\{?\s*([A-Za-z_]\w*)')
DASH_CONCAT_RE = re.compile(r'"(-+)"\s*\+')
DASH_REPLACE_RE = re.compile(r'replaceFirst\s*\(\s*"-"\s*,\s*"--"')
# `-f` is the ONE letter whose value must stay a positional (the archive): the
# expander maps it to nothing ON PURPOSE, and the handler reads the archive
# from the positional list. Any OTHER silent no-op drops a real option, which
# is 15-3. Adding a letter here is a deliberate edit to this gate.
NOOP_OK_LETTERS = {"f"}


def _declared_members(code: str, name: str):
    """String members of `val <name> = setOf(…)`, or None (fail-closed)."""
    m = re.search(_DECL_RE % re.escape(name), code)
    if m is None:
        return None
    return [x.strip().strip('"') for x in m.group(1).split(",") if x.strip()]


def _adds_dash(seg_lit: str, elem: str) -> bool:
    """Does this (literal-channel) expression yield a dash-PREFIXED [elem]?"""
    for m in DASH_TEMPLATE_RE.finditer(seg_lit):
        if m.group(2) == elem:
            return True
    if re.search(r"\b%s\b" % re.escape(elem), seg_lit) and (
            DASH_CONCAT_RE.search(seg_lit) or DASH_REPLACE_RE.search(seg_lit)):
        return True
    return False


def _handler_reads_key(lit_src: str, key: str) -> bool:
    """Is `--<key>` a value/flag the handler actually reads?"""
    return re.search(
        r'(?:get|getInt|getLong|getDouble|hasFlag|resolvePageSize)\s*\(\s*"%s"' % re.escape(key),
        lit_src) is not None


def _split_if_else(seg: str, seg_lit: str, t_start: int, t_end: int):
    """(then_lit, else_lit) of a braceless `if` whose then-branch may swallow the
    `else` (shared `if_blocks` stops a single-line branch only at a newline).

    The `else` is located at PAREN depth 0 of the then-text, on the CODE view
    (string contents are blanked there, so a prose `else` inside a literal
    cannot split it).
    """
    depth = 0
    i = t_start
    while i < t_end:
        c = seg[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and seg.startswith("else", i) and (
                i == t_start or not (seg[i - 1].isalnum() or seg[i - 1] == "_")) and (
                i + 4 >= t_end or not (seg[i + 4].isalnum() or seg[i + 4] == "_")):
            return seg_lit[t_start:i].strip(), seg_lit[i + 4:t_end].strip()
        i += 1
    return seg_lit[t_start:t_end].strip(), None


def _check_long_option_rewrite(code: str, lit: str) -> list:
    """[#151] `normalizeLongOptionSpelling` must rewrite its declared members."""
    span = function_body(code, "normalizeLongOptionSpelling")
    if span is None:
        return [("N0", "missing",
                 "internal fun normalizeLongOptionSpelling(argv: List<String>): List<String>")]
    start, end, kind = span
    body, body_lit = code[start:end], lit[start:end]
    members = _declared_members(lit, SHORT_LONG_SET)
    if not members or any(not x or not x.startswith("-") for x in members):
        return [("N0", "missing",
                 "%s = setOf(…) is not declared with single-dash members — the rewrite has "
                 "no table to consult (fail-closed)" % SHORT_LONG_SET)]
    m = re.search(r"\.\s*map(?:NotNull)?\s*\{", body)
    if m is None:
        return [("N1", "missing",
                 "the normalizer no longer maps the argv at all — every single-dash long "
                 "option (%s) reaches OffloadArgs as one short flag plus stray positionals"
                 % ", ".join(members))]
    lam_open = m.end() - 1
    lam_close = match_bracket(body, lam_open)
    if lam_close < 0:
        return [("N1", "missing", "the normalizer's map lambda cannot be balanced (fail-closed)")]
    lam = body[lam_open + 1:lam_close]
    lam_lit = body_lit[lam_open + 1:lam_close]
    em = re.match(r"\s*([A-Za-z_]\w*)\s*->", lam)
    elem = em.group(1) if em else "it"
    if kind == "block" and not re.search(r"\breturn\b", body[:m.start()]) \
            and body[lam_close + 1:].strip():
        return [("N1", "discarded",
                 "the rewritten list is computed and then dropped — the function's result "
                 "is not the mapped argv")]
    test = re.search(r"\b%s\s+in\s+%s\b" % (re.escape(elem), SHORT_LONG_SET), lam) \
        or re.search(r"%s\s*\.\s*contains\s*\(\s*%s\b" % (SHORT_LONG_SET, re.escape(elem)), lam)
    if test is None:
        return [("N2", "missing",
                 "the rewrite no longer consults %s — a member token is passed through "
                 "unchanged, which is exactly the pre-fix parse" % SHORT_LONG_SET)]
    site = None
    for cond, t_start, t_end, e_start, e_end in if_blocks(lam):
        if re.search(SHORT_LONG_SET, cond) and re.search(r"\b%s\b" % re.escape(elem), cond):
            site = (cond, t_start, t_end, e_start, e_end)
            break
    if site is None:
        return [("N2", "missing",
                 "the membership test is not the condition of an `if` the gate can read "
                 "(fail-closed) — a rewrite the gate cannot see is not a rewrite")]
    _cond, t_start, t_end, e_start, e_end = site
    if e_start is not None:
        then_lit, else_lit = lam_lit[t_start:t_end].strip(), lam_lit[e_start:e_end].strip()
    else:
        then_lit, else_lit = _split_if_else(lam, lam_lit, t_start, t_end)
    if not _adds_dash(then_lit, elem):
        return [("N2", "present",
                 "a member token is not rewritten to the `--` spelling (`%s`) — OffloadArgs "
                 "reads `-name` as the flags -n/-a/-m/-e and the following value as a "
                 "positional" % " ".join(then_lit.split())[:60])]
    if else_lit is None or else_lit != elem:
        return [("N3", "missing" if else_lit is None else "present",
                 "the pass-through arm must return the token itself — a non-member argument "
                 "would be rewritten or dropped (got `%s`)" % (else_lit or "")[:60])]
    return []


def _when_arms_with_labels(body: str, body_lit: str):
    """[(label, body_start, body_end)] of the first `when` — labels read from the
    LITERAL channel.

    Shared `when_branches` slices branch bodies correctly, but its label text
    comes from the CODE view, where a char literal's content is blanked: every
    `'c' -> …` arm would read as `' '`. The label is recovered at the same
    offsets from `literal_view` (both views are length-equal, so positions are
    interchangeable).
    """
    arms = when_branches(body)
    out = []
    prev = None
    for _subj, _cond, b_start, b_end in arms:
        arrow = body.rfind("->", 0 if prev is None else prev, b_start + 2)
        if arrow < 0:
            out.append((None, b_start, b_end))
            prev = b_end
            continue
        start = body.rfind("{", 0, arrow) if prev is None else prev
        out.append((body_lit[start + 1:arrow].strip(), b_start, b_end))
        prev = b_end
    return out


def _check_tar_short_table(code: str, lit: str, lit_src: str) -> list:
    """[#153] every arm of `normalizeTarShorts` must contribute or invalidate."""
    findings = []
    span = function_body(code, "normalizeTarShorts")
    if span is None or span[2] != "block":
        return [("T0", "missing",
                 "internal fun normalizeTarShorts(argv: List<String>): List<String> "
                 "(block body — fail-closed)")]
    body, body_lit = code[span[0]:span[1]], lit[span[0]:span[1]]
    declared_value = _declared_members(lit, SHORT_VALUE_SET)
    if declared_value is None:
        return [("T0", "missing",
                 "%s = setOf(…) is not declared — the expander no longer models "
                 "value-taking short options (fail-closed)" % SHORT_VALUE_SET)]
    arms = _when_arms_with_labels(body, body_lit)
    if not arms:
        return [("T0", "missing",
                 "normalizeTarShorts has no `when (ch)` arm table — no short option is "
                 "expanded at all")]
    table = {}
    else_arm = None
    for label, b_start, b_end in arms:
        label = (label or "").strip()
        arm_body = body[b_start:b_end]
        arm_lit = body_lit[b_start:b_end]
        produces = re.search(r"\.\s*add(?:All)?\s*\(", arm_body) is not None
        key = None
        km = re.search(r'\.\s*add\s*\(\s*"(--[A-Za-z][\w-]*)"', arm_lit)
        if km:
            key = km.group(1)
        invalidates = re.search(r"\bknown\s*=\s*false\b", arm_body) is not None
        letter = None
        lm = re.match(r"^'(\w)'$", label) or re.match(r'^"(\w)"$', label)
        if lm:
            letter = lm.group(1)
        entry = {"produces": produces, "key": key, "invalidates": invalidates,
                 "body": arm_body.strip()}
        if label == "else":
            else_arm = entry
        elif letter:
            table[letter] = entry
    # T2 — an unknown letter must invalidate the token (the caller reports it),
    # never be dropped while `known` stays true.
    if else_arm is None:
        findings.append(("T2", "missing",
                         "the short-option table has no `else` arm — an unknown letter "
                         "silently disappears from the argument list"))
    elif not else_arm["invalidates"]:
        findings.append(("T2", "present",
                         "the `else` arm does not invalidate the token (`known = false`) — "
                         "an unknown letter is accepted and dropped"))
    # T1 — a known letter may not silently no-op (15-3: `'C' -> Unit`).
    for letter in sorted(table):
        e = table[letter]
        if e["produces"] or e["invalidates"]:
            continue
        if letter in NOOP_OK_LETTERS:
            continue
        findings.append(
            ("T1", "present",
             "'%s' -> `%s` drops the option while `known` stays true — the letter is "
             "accepted and then contributes nothing, so the option's value/effect is lost "
             "silently" % (letter, e["body"][:40])))
    # T3 — a declared VALUE-taking short option must expand to a value-consuming
    # long form (that is the only thing that stops its value becoming a
    # positional).
    for member in declared_value:
        letter = member.lstrip("-")
        e = table.get(letter)
        if e is None:
            findings.append(("T3", "missing",
                             "%s lists `%s` as value-taking but the table has no arm for it"
                             % (SHORT_VALUE_SET, member)))
            continue
        if not e["produces"] or not e["key"]:
            findings.append(("T3", "present",
                             "`%s` is declared value-taking (%s) but its arm does not expand "
                             "to a `--` option — the value falls through as a positional"
                             % (letter, SHORT_VALUE_SET)))
            continue
        if not _handler_reads_key(lit_src, e["key"][2:]):
            findings.append(("T3", "missing",
                             "the arm emits `%s`, which no handler reads — the option is "
                             "accepted and then ignored" % e["key"]))
    # T4 — the expansions must replace the token, under the `known` decision.
    add_all = body.find("addAll(")
    if add_all < 0:
        findings.append(("T4", "missing",
                         "the expander never appends its expansions (`out.addAll(expanded)`) "
                         "— the token is dropped instead of expanded"))
    else:
        conds = [c for _k, c in enclosing_conditions(body, add_all)]
        if not conds or "known" not in conds[-1]:
            findings.append(("T4", "present",
                             "the expansions are appended without consulting `known` — an "
                             "unknown letter's partial expansion reaches the parser"))
    if re.search(r"\.\s*add\s*\(\s*token\s*\)", body) is None:
        findings.append(("T4", "missing",
                         "no pass-through arm re-adds the untouched token (`out.add(token)`) "
                         "— a token the expander cannot handle disappears"))
    # T5 — every emitted option must be one the tar surface parses.
    tar_opts = _declared_members(lit_src, "TAR_OPTIONS") or []
    for letter in sorted(table):
        key = table[letter]["key"]
        if not key:
            continue
        bare = key[2:]
        if bare in tar_opts or _handler_reads_key(lit_src, bare):
            continue
        findings.append(("T5", "missing",
                         "'%s' expands to `%s`, which is in neither TAR_OPTIONS nor any "
                         "handler read — the option is accepted and then ignored" % (letter, key)))
    return findings


@_fail_closed
def check_fastio_normalizers(fastio_src: str) -> list:
    """[R3-t14 #151/#153] the two argv normalizers, judged as decisions."""
    code = code_view(fastio_src)
    lit = literal_view(fastio_src)
    return (_check_long_option_rewrite(code, lit)
            + _check_tar_short_table(code, lit, lit))


@_fail_closed
def check_fastio_search(fastio_search_src: str) -> list:
    findings = []
    # [batch6-t01] 结构分析走 `mask_code`：以下判据只读标识符/标点，不读字符串
    # 内容，因此结构视图中"字符串已被抹平"对它们无影响，而括号配平从此可靠。
    src = code_view(fastio_search_src)
    tree = _payload_helper_bodies(src, "grepTree")
    if tree is None:
        findings.append(
            ("S1", "missing", "internal fun grepTree(…, limit: Int, out: GrepOutcome)"))
        return findings
    walk_at = tree.find("Files.walkFileTree(")
    if walk_at < 0:
        findings.append(("S1", "missing", "grepTree must walk with Files.walkFileTree("))
        return findings
    head = tree[:walk_at]
    if not re.search(r"limit\s*<=\s*0", head):
        findings.append(
            ("S1", "missing",
             "grepTree must short-circuit a non-positive budget (limit <= 0) before the walk"))
    elif "matchesCapped = true" not in head:
        findings.append(
            ("S1", "missing",
             "the non-positive-budget branch must answer out.matchesCapped = true"))
    else:
        # [wide-1024 S1] …and the guard must actually STOP the walk. The old rule
        # read two substrings out of the head (`limit <= 0`, `matchesCapped =
        # true`), both of which survive `if (limit <= 0 && false) { … }` — a
        # condition folded to false by the compiler, with the `return` deleted.
        # The budget is then spent walking and reading the whole tree.
        site = _if_site(head, r"limit\s*<=\s*0")
        if site is None:
            findings.append(
                ("S1", "missing",
                 "the non-positive-budget check is not an `if (…)` guard the gate can "
                 "read (fail-closed) — a guard it cannot see is not a guard"))
        else:
            cond, guard_body = site
            if _dead_condition(cond):
                findings.append(
                    ("S1", "dead",
                     "the non-positive-budget guard is constant-folded false (`%s`) — the "
                     "walk runs anyway and reads every file under the root" % cond.strip()))
            elif not re.search(r"\b(return|throw)\b", guard_body):
                findings.append(
                    ("S1", "missing",
                     "the non-positive-budget branch only sets matchesCapped and falls "
                     "through — it must return BEFORE Files.walkFileTree(, or limit = 0 "
                     "walks (and reads) the whole root"))

    pre = _brace_block(tree, "override fun preVisitDirectory")
    if pre is None:
        findings.append(("S2", "missing", "grepTree's preVisitDirectory override"))
    else:
        cap_at = pre.find("matchesCapped")
        charge_at = pre.find("charge(")
        if cap_at < 0:
            findings.append(
                ("S2", "missing", "preVisitDirectory must consult out.matchesCapped"))
        elif charge_at < 0 or cap_at > charge_at:
            findings.append(
                ("S2", "present",
                 "preVisitDirectory charges the directory before consulting the cap"))

    visit = _brace_block(tree, "override fun visitFile")
    if visit is None:
        findings.append(("S3a", "missing", "grepTree's visitFile override"))
    else:
        grep_at = visit.find("grepFile(")
        if grep_at < 0:
            findings.append(("S3a", "missing", "visitFile must walk files through grepFile("))
        else:
            tail = visit[grep_at:]
            if re.search(r"matchesCapped[\s\S]{0,160}?\bTERMINATE\b", tail) is None:
                findings.append(
                    ("S3a", "missing",
                     "visitFile must terminate once grepFile filled the budget"))
            if re.search(r"grepFile\([\s\S]{0,240}?return FileVisitResult\.CONTINUE", visit):
                findings.append(
                    ("S3b", "present",
                     "visitFile returns CONTINUE unconditionally after grepFile"))
    # [R3-t14 #152] …and the cap every one of those consumers waits on must be
    # SET where the budget actually runs out (inside grepFile).
    findings += _grep_cap_producer(src)
    return findings


@_fail_closed
def check_photos_album(photos_src: str) -> list:
    """[dv18-A A-03] the --album decision is ONE classification (Absent / ById /
    ByName / Unreadable): a non-numeric --id refuses (exit 2) instead of
    silently downgrading to the name branch, and a blank --name refuses
    instead of running a `LIKE '%%'` full-library scan. handleAlbums (the
    no-arg listing) stays untouched."""
    photos = code_only(photos_src)
    findings = []
    if "internal fun resolveAlbumSelector(" not in photos:
        findings.append(("P1", "missing", "the --album decision lost its single classifier"))
    if not re.search(r"AlbumSelector\.Unreadable\s*->\s*return NativeOffloadResult\(2,\s*albumSelectorRefusal\(",
                     photos):
        findings.append(("P2", "missing",
                         "a non-numeric --id must reach a refusal (albumSelectorRefusal), not the name branch"))
    resolver = _payload_helper_bodies(photos, "resolveAlbumSelector")
    if resolver is not None and ("toLongOrNull()" not in resolver or "isNotBlank()" not in resolver):
        findings.append(("P3", "missing",
                         "the classifier must nullable-parse --id and blank-check --name"))
    if "fun handleAlbums(" not in photos:
        findings.append(("P4", "missing", "handleAlbums (the no-arg listing) is gone"))
    return findings

# ── [dv22-A6 A-06] android-clipboard clear: an empty string is not a clear ──
#
# `doClear` used to write `setPrimaryClip(newPlainText("", ""))` and call it
# done: the clipboard still holds an item (itemCount 1, text/plain
# description), so `status` kept answering has_content = true for a clipboard
# `clear` had just reported as cleared = true. Its background guard was also
# the only one of the four (get/set/clear/status) missing `&& SDK_INT >= Q`,
# refusing pre-Q background clears that Android 8–9 actually permit.
# Four judgements, all derived from the tree:
#   L1  doClear keeps its owner body (a renamed / deleted owner is a red
#       gate, not a silently narrower rule set)
#   L2  the platform clear `clearPrimaryClip(` is reached from doClear
#   L3  every `!isAppForeground()` site keeps the `&& SDK_INT >= Q` half —
#       per-site derived, so a fifth subcommand is covered the day it lands
#       (no hand-written member list to go stale)
#   L4  doClear keeps BOTH write arms and they sit in different branches:
#       the empty-string arm is what keeps API 26–28 working (clearPrimaryClip
#       is not reliable there), and an unconditional clear-then-set would put
#       the empty item right back even on Q+
FOREGROUND_Q_RE = r"!isAppForeground\(\)\s*&&\s*Build\.VERSION\.SDK_INT\s*>=\s*Build\.VERSION_CODES\.Q"
BARE_FOREGROUND_RE = r"!isAppForeground\(\)(?!\s*&&\s*Build\.VERSION\.SDK_INT\s*>=\s*Build\.VERSION_CODES\.Q)"
SDK_Q_PREDICATE_RE = re.compile(
    r"Build\s*\.\s*VERSION\s*\.\s*SDK_INT\s*>=\s*Build\s*\.\s*VERSION_CODES\s*\.\s*Q")


def _is_sdk_q_decision(arg: str, struct: str) -> bool:
    """Is this argument **exactly** the API-29 predicate (or a local bound to it)?

    [wide-1024 L2] `clearAction(false)` compiles clean, keeps the platform call in
    the tree, and silently routes every Android 10+ device to the empty-string
    arm — a clear that leaves an item behind.

    [batch6-t01] 旧判据问的是「这段文本里**出现过** `SDK_INT >= …Q` 吗」。于是
    下列三种写法全被判成"就是那个谓词"，而 `clearAction` 收到的是恒假布尔：
        `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && false`
        `!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)`
        `val isQ = <谓词> && false` → `clearAction(isQ)`
    改为**值语义**：被求值的整个表达式（剥括号）必须 fullmatch 谓词本身；
    局部变量跟随一次赋值（`last_write`），其右值同样必须整个是谓词。
    多出来任何一元/二元运算、任何恒假拼接都判红（fail-closed）。
    """
    e = strip_parens(arg.strip())
    if not e:
        return False
    if SDK_Q_PREDICATE_RE.fullmatch(e):
        return True
    if not re.fullmatch(r"[A-Za-z_]\w*", e):
        return False
    last = last_write(struct, e)
    if last is None:
        return False
    return bool(SDK_Q_PREDICATE_RE.fullmatch(strip_parens(last[1].strip())))

@_fail_closed
def check_clipboard_clear(clipboard_src: str) -> list:
    """[dv22-A6 A-06] the L1–L4 rules above."""
    # [batch6-t01] 双视图：`body`（字面量通道）给 `ClipData.newPlainText("", "")`
    # 这类读字面量的判据；`body_c`（结构通道）给分支/实参结构判定。
    code = code_only(clipboard_src)
    struct = code_view(clipboard_src)
    findings = []
    span = _fn_span(struct, "doClear")
    if span is None:
        findings.append(("L1", "missing",
                         "doClear lost its owner body (renamed / inlined / deleted?)"))
        return findings
    body = code[span[0]:span[1]]
    body_c = struct[span[0]:span[1]]
    if "clearPrimaryClip(" not in body:
        findings.append(("L2", "missing",
                         "clear must reach the platform clearPrimaryClip() — an empty "
                         "string write leaves itemCount = 1 and status answers has_content = true"))
    else:
        # [wide-1024 L2] reaching the call in the SOURCE is not reaching it at
        # RUNTIME. `clearAction(false)` — a Boolean the pure function accepts
        # without a warning — sends every API 29+ device down the empty-string
        # arm: the platform clear is never called, an empty item is written
        # instead (system "copied" banner, hasPrimaryClip() still true).
        i = body_c.find("clearAction(")
        if i >= 0:
            arg = _first_call_arg(body_c, i + len("clearAction")).strip()
            if not _is_sdk_q_decision(arg, struct):
                findings.append((
                    "L2", "unreachable",
                    "clearAction() is fed `%s` instead of "
                    "`Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` — on API 29+ the "
                    "platform clearPrimaryClip() is never reached and `clear` writes an "
                    "empty item (itemCount stays 1)" % (arg or "<missing>")))
    if re.search(BARE_FOREGROUND_RE, struct):
        findings.append(("L3", "regressed",
                         "an isAppForeground() guard dropped its `&& SDK_INT >= Q` half "
                         "(pre-Q background clipboard access is legal; refusing it breaks clear)"))
    if not re.search(FOREGROUND_Q_RE, body_c):
        findings.append(("L3", "missing",
                         "doClear's background guard must be the exact get/set/status shape"))
    if 'setPrimaryClip(ClipData.newPlainText("", ""))' not in body:
        findings.append(("L4", "missing",
                         "the API 26–28 empty-string fallback is gone — clearPrimaryClip() "
                         "is not reliable there"))
    else:
        platform_at = body.find("clearPrimaryClip(")
        if platform_at >= 0:
            fallback_at = body.find('ClipData.newPlainText("", "")')
            between = body[platform_at:fallback_at] if fallback_at > platform_at \
                else body[fallback_at:platform_at]
            if "else" not in between and "->" not in between:
                findings.append(("L4", "unbranched",
                                 "the two clear writes must sit in different branches — "
                                 "unconditional clear-then-set puts the empty item back on Q+"))
    return findings


# ── [deep-audit-1044 A-04] the `send` subcommand's status → exit-code contract ──
#
# `ChatMutationMethods.prompt` does NOT throw when the send is dropped — it
# answers with a structured `status` ("Error" + the reason in `responseText`),
# and with "Timeout" when the wait elapsed. `cmdSend` used to catch exceptions
# only and then `emit(...)` unconditionally, and `emit()` hard-codes `ok: true`
# with exit code 0 — so every refusal (no provider, compacting in flight,
# context exhausted, wait timeout) left the CLI as a SUCCESS whose
# `response_text` carried a machine reason in the place an assistant reply
# belongs. A shell caller could not tell a delivered prompt from one that
# never left the device.
#
# The rule binds the DECISION POINTS, not one spelling:
#   S1 — the status must be READ from the RPC result (`optString("status")`).
#   S2 — the success set must be consulted (`sendSucceeded(`): only
#        "Completed" / "Running" are successes; every other status (including
#        an unknown or empty one) is a refusal.
#   S3 — a refusal must go through the file's existing `errorEnvelope` funnel
#        (no second envelope shape).
#   S4 — a refusal must pick its exit code through `sendExitFor(` (nonzero).
#   S5 — the refusal branch must be REACHED BEFORE `emit("send"…)`: the success
#        payload may only be emitted on the success path.
#   S6 — the success set must be exactly {"Completed", "Running"} (two
#        comparisons against the status) — a set that also accepts "" or an
#        unknown status re-opens the defect.
#   S7 — the exit code must be the file's failure code (1), not 0.
#
# Judged on comment-stripped text, so a status literal quoted in a comment
# cannot satisfy OR trip the rule.
_SEND_STATUS_RE = re.compile(r'optString\(\s*"status"\s*\)')


GREEN_SESSIONS_SEND = """
class SessionsOffloadHandler {
    private fun cmdSend(args: OffloadArgs): NativeOffloadResult {
        val result = runBlocking {
            com.rikkaminis.app.debug.ChatMutationMethods.prompt(context, params)
        }
        val status = result.optString("status")
        val data = JSONObject()
            .put("session_id", result.optString("sessionId"))
            .put("status", status)
        if (result.has("responseText")) data.put("response_text", result.optString("responseText"))
        if (!sendSucceeded(status)) {
            val detail = JSONObject()
                .put("session_id", data.optString("session_id"))
                .put("status", status)
            val err = errorEnvelope(
                "send",
                sendErrorCode(status),
                sendErrorReason(status, data.optString("response_text")),
                detail,
            )
            return NativeOffloadResult(
                sendExitFor(status),
                OffloadOutput.formatBody(err.toString(2), args) + "\\n",
            )
        }
        emit("send", data, args)
    }

    private fun errorEnvelope(
        action: String,
        code: String,
        msg: String,
        detail: JSONObject? = null,
    ): JSONObject {
        val data = JSONObject().put("error", code).put("message", msg)
        if (detail != null) data.put("detail", detail)
        return JSONObject().put("ok", false).put("action", action).put("data", data)
    }

    private fun emit(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val envelope = JSONObject().put("ok", true).put("action", action).put("data", data)
        return NativeOffloadResult(0, OffloadOutput.formatBody(envelope.toString(2), args) + "\\n")
    }

    companion object {
        private const val EXIT_FAILURE = 1

        internal fun sendSucceeded(status: String): Boolean =
            status == "Completed" || status == "Running"

        internal fun sendExitFor(status: String): Int =
            if (sendSucceeded(status)) 0 else EXIT_FAILURE

        internal fun sendErrorCode(status: String): String =
            if (status == "Timeout") "SEND_TIMEOUT" else "SEND_FAILED"

        internal fun sendErrorReason(status: String, responseText: String?): String {
            val reason = responseText?.trim()
            if (!reason.isNullOrEmpty()) return reason
            return if (status == "Timeout") "timeout" else status.lowercase()
        }
    }
}
"""


def _cut_at_next_decl(region: str) -> str:
    """Trim a declaration window at the NEXT `fun`/`class`/`companion` keyword.

    A text window that runs past the helper's own declaration would swallow the
    NEXT helper's comparisons — `sendSucceeded` would then look like it
    compares three statuses. Cut at the next declaration so each window
    describes exactly one decision.
    """
    m = re.search(r"\b(fun|class|companion\s+object)\b", region[1:])
    return region[: m.start() + 1] if m else region


def _send_helper_region(code: str, name: str, span: int = 400) -> "str | None":
    """The declaration of `fun <name>(…)` plus [span] characters after it.

    A text window rather than a brace body on purpose: the decision helpers are
    EXPRESSION-bodied (`… : Boolean = status == "Completed" || …`) and have no
    braces of their own — a brace-hunting walk would read the NEXT function's
    body and pass or fail for the wrong reason.
    """
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if m is None:
        return None
    return code[m.start(): m.start() + span]


@_fail_closed
def check_sessions_send_status(sessions_src: str) -> list:
    """[deep-audit-1044 A-04] — the `send` status → exit-code contract."""
    # [batch6-t01] 双视图：`body`（字面量通道）给 `optString("status")` /
    # `"Completed"` / `emit("send"` 这类**读字面量**的判据；`body_c`（结构通道）
    # 给 if 决策与分支体判定 —— 字符串里的括号不再让分支读错。
    code = code_only(sessions_src)
    struct = code_view(sessions_src)
    findings = []
    span = _fn_span(struct, "cmdSend")
    if span is None:
        findings.append(("S1", "missing",
                         "cmdSend lost its owner body (renamed / inlined / deleted?)"))
        return findings
    body = code[span[0]:span[1]]
    body_c = struct[span[0]:span[1]]
    if not _SEND_STATUS_RE.search(body):
        findings.append(("S1", "missing",
                         "cmdSend must read the RPC status (`optString(\"status\")`) — "
                         "a dropped send is reported in the payload, not by an exception"))
    if "sendSucceeded(" not in body:
        findings.append(("S2", "missing",
                         "cmdSend must consult the success set (`sendSucceeded(…)`) — "
                         "only Completed/Running are deliveries"))
    else:
        # [wide-1024 S2] consulting is not deciding. The rule read the substring;
        # `if (false && !sendSucceeded(status))` keeps it, folds the condition to
        # false at compile time, and lets a dropped send fall through to
        # `emit("send", …)` at exit 0 — the caller believes the message landed.
        site = _if_site(body_c, r"sendSucceeded\s*\(")
        if site is None:
            findings.append(("S2", "missing",
                             "sendSucceeded(…) is called but no branch is decided by it — "
                             "the success payload would leave whatever the status says"))
        else:
            cond, guard_body = site
            if _dead_condition(cond):
                findings.append(("S2", "dead",
                                 "the success-set decision is constant-folded false (`%s`) — "
                                 "a refused send takes the success path and exits 0"
                                 % cond.strip()))
            elif not re.search(r"\b(return|throw)\b", guard_body):
                findings.append(("S2", "missing",
                                 "the refusal branch falls through instead of terminating — "
                                 "control reaches `emit(\"send\"…)` on a failed send"))
    if "errorEnvelope(" not in body:
        findings.append(("S3", "missing",
                         "a refusal must go through the existing `errorEnvelope(` funnel — "
                         "the file's single error shape"))
    else:
        # The FIRST `errorEnvelope(` in cmdSend belongs to the earlier ARGUMENT
        # guards (SUBAGENT_DISABLED / INVALID_ARGS) — the refusal's own call is
        # the one AFTER the success-set consultation. Requiring that one keeps
        # the rule from being satisfied by an unrelated guard.
        gate_at = body.find("sendSucceeded(")
        if gate_at >= 0 and "errorEnvelope(" not in body[gate_at:]:
            findings.append(("S3", "missing",
                             "the refusal branch must call `errorEnvelope(` AFTER the "
                             "success-set consultation — the earlier argument guards "
                             "do not count"))
    if "sendExitFor(" not in body:
        findings.append(("S4", "missing",
                         "a refusal must pick its exit code through `sendExitFor(…)` — "
                         "the CLI contract (nonzero = not delivered)"))
    emit_at = body.find('emit("send"')
    if emit_at < 0:
        findings.append(("S5", "missing",
                         "cmdSend no longer emits its success payload (`emit(\"send\"…`)"))
    else:
        # Ordering, anchored on the success-set consultation rather than on the
        # envelope helper's name: whatever the refusal is spelled, it has to be
        # decided before the payload leaves.
        gate_at = body.find("sendSucceeded(")
        if gate_at < 0 or gate_at > emit_at:
            findings.append(("S5", "present",
                             "the success decision must be reached BEFORE `emit(\"send\"…)` — "
                             "the success payload may only be emitted on the success path"))
    succ = _send_helper_region(code, "sendSucceeded")
    if succ is None:
        findings.append(("S6", "missing",
                         "the success set helper `sendSucceeded(…)` is gone — the "
                         "status → success decision must live in ONE place"))
    else:
        succ = _cut_at_next_decl(succ)
        if '"Completed"' not in succ or '"Running"' not in succ:
            findings.append(("S6", "missing",
                             "the success set must contain Completed and Running"))
        compares = len(re.findall(r"status\s*==", succ))
        if compares != 2:
            findings.append(("S6", f"count {compares} != 2",
                             "the success set must compare the status against exactly "
                             "two values — an extra accepted status re-opens the defect"))
    exit_helper = _send_helper_region(code, "sendExitFor")
    if exit_helper is None:
        findings.append(("S7", "missing",
                         "the exit-code helper `sendExitFor(…)` is gone"))
    else:
        exit_helper = _cut_at_next_decl(exit_helper)
        if "EXIT_FAILURE" not in exit_helper:
            findings.append(("S7", "missing",
                             "the exit code must come from the file's failure constant"))
    if not re.search(r"EXIT_FAILURE\s*=\s*1\b", code):
        findings.append(("S7", "missing",
                         "the failure exit code must be 1 (the file's other error "
                         "branches use 1)"))
    # S8 — the reason must NOT ride along in the failure payload under the
    # `response_text` key: that key is where an assistant's answer belongs, and
    # a caller reading it would still see a machine code in the answer slot.
    # The reason belongs in the envelope's `message`; the diagnostic fields
    # (session id / status / timeout flag) may stay in `detail`.
    detail_at = body.find("val detail")
    # The FIRST `errorEnvelope(` in cmdSend belongs to the earlier argument
    # guards (SUBAGENT_DISABLED / INVALID_ARGS) — the refusal's own call is the
    # one AFTER the detail payload is built.
    err_at = body.find("errorEnvelope(", detail_at) if detail_at >= 0 else -1
    if detail_at >= 0 and err_at > detail_at:
        detail_region = body[detail_at:err_at]
        if "response_text" in detail_region:
            findings.append(("S8", "present",
                             "the failure payload must not carry the reason under "
                             "`response_text` — that key is the assistant's answer "
                             "slot; use the envelope `message` instead"))
    return findings


# ── [dv28-A6 A-06] the android-contacts page-size contract ──────────────────
#
# One contract (`--max N` / its legacy `-n N` alias) had three gaps, all of the
# same shape: a spelled-but-unusable value was treated as "spelled nothing".
#
#   CN1  `--max abc` was read through `getInt`, which maps "unreadable" onto
#        "absent" — the handler answered the default (50 list / 20 search) at
#        exit 0, so the caller believed the requested page size took effect.
#   CN2  `-n` is bound to `--max` only for a NUMERIC value, so a surviving `-n`
#        flag means "the token after it is not a number" — and that token sat in
#        the positional list, where `searchQueryOf` reads positional[1]: the
#        invocation `search -n abc Alice` searched for "abc" and dropped
#        "Alice". Reading the alias value back out of the positional list (the
#        pre-dv14 spelling) is exactly what made that silent.
#   CN3  0 / negative reached `collectContactPage`, whose `rows.size < max` loop
#        exits at once: the handler answered an empty page `[]` at exit 0, which
#        is indistinguishable from "no contacts".
#
# Every anchor below is derived: the resolver is the function that takes a
# `default` parameter and reads the `max` CLI key (the key IS the contract); the
# outcome type, its variants, the upper-bound constant and the exit-2 producers
# all come from their declarations — so renaming one keeps the rules pointing at
# the same decision, and deleting one lands as a finding instead of a silently
# narrower rule set (fail-closed).
def _fn_spans(struct: str) -> list:
    """[(name, body_start, body_end)] for every `fun` — 共享原语实现的声明遍历。

    [batch6-t01] 参数表用 `match_bracket` 配平（默认参数里的 lambda 不再被当成
    体），表达式体用 `expr_end` 定界；两个自写的花括号/表达式扫描器
    （`_match_brace` / `_expr_body`）退役。
    """
    out = []
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", struct):
        p_close = match_bracket(struct, m.end() - 1)
        if p_close < 0:
            continue
        i = p_close + 1
        while i < len(struct) and struct[i] not in "{=":
            i += 1
        if i >= len(struct):
            continue
        if struct[i] == "{":
            j = match_bracket(struct, i)
            if j > i:
                out.append((m.group(1), i + 1, j))
        else:
            out.append((m.group(1), i + 1, expr_end(struct, i + 1)))
    return out

def _fn_bodies_with_sigs(code: str):
    """[(name, params, return_type, body)] for every `fun` in `code`.

    [batch6-t01] 体内实现改走共享原语（`function_body` 的同族实现见 `_fn_spans`）：
    体与签名都从 `mask_code` 视图派生，切片回投调用方的文本（等长视图）。
    """
    struct = mask_code(code)
    out = []
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", struct):
        p_close = match_bracket(struct, m.end() - 1)
        if p_close < 0:
            continue
        params = code[m.end():p_close]
        i = p_close + 1
        rm = re.match(r"\s*:\s*([\w.<>?, ]+)", struct[i:])
        ret = rm.group(1).strip() if rm else ""
        if rm:
            i += rm.end()
        while i < len(struct) and struct[i] not in "{=":
            i += 1
        if i >= len(struct):
            continue
        if struct[i] == "{":
            j = match_bracket(struct, i)
            body = code[i:j + 1] if j > i else ""
        else:
            body = code[i + 1:expr_end(struct, i + 1)]
        out.append((m.group(1), params, ret, body))
    return out

# ── [dv29-A3 A-05] the page-size contract, over EVERY registered surface ────
#
# [dv28-A6 A-06] closed this contract for `android-contacts` alone and pinned it
# with CN0-CN4, whose scope was the contacts source handed to the check. Four
# sibling paginating handlers kept the older shape and the same three defects
# stayed reachable from the release CLI (verified on-device, 2026-10-24,
# app 1.0.0+2597): `android-calendar list --limit -5` answered exit 0 with an
# empty page plus a "-5 of 0 total records" truncation warning; `--limit 0`
# answered ONE row (photos); `--limit 1000` answered 100 rows (sessions);
# `--max abc` answered every active notification.
#
# The contract now lives in ONE file (`OffloadPageSize.kt`) whose SURFACES
# registry names the handler that owns each surface. The gate's scope is DERIVED
# from that registry, not from a file list written here: a new paginating handler
# must be declared there, a declared surface missing from SURFACES is red, and a
# registered handler that reads a page size raw is red with no exemption list.
#
# PG0 fail-closed derivation · PG1 the handler routes through the resolver ·
# PG2 no raw page-size read survives in a registered handler (whitelist = the
# empty set) · PG3 the resolver keeps its refusals (raw read, bare flag,
# non-numeric, lower bound, upper bound, exactly one default answered last) ·
# PG4 a refusal is answered as a usage error, never as a default page ·
# PG5 the legacy `-n` alias is still refused instead of leaking into a query.

PAGE_OUTCOME = "PageSizeOutcome"

# Shapes that read a page size WITHOUT validating it. Ordered strongest first so
# a finding names the site it was read at.
# [batch6-t01] 分页读取的判据从"拼写"搬到"被读取的**键值**"：
#   旧形态是三条正则，要求 `getInt(` 后**紧跟**字面量 `"limit"` / `"max"`。
#   于是同一处读取换个写法就隐形——`val limitKey = "limit"; args.getInt(limitKey)`、
#   或把 `"limit"` 放进第二个实参（`getInt("day", "limit")`，OffloadArgs 的
#   vararg 重载）都不匹配。新形态：解析每个实参表达式**求值出的字符串**
#   （字面量 / 同文件局部 val 一点跳），再与门自己从 `PageSizeSurface.names`
#   派生出来的名字集比对；实参位置无关。
_PAGE_CLAMP_RE = re.compile(
    r'(?:limit|max)[^\n]{0,40}?\.coerce(?:In|AtLeast)\s*\(\s*1\s*[,)]')

_PAGE_READ_CALL_RE = re.compile(r"\b(getInt|get)\s*\(")

def _page_key_values(expr: str, lit: str, struct: str, pos: int, depth: int = 0) -> list:
    """表达式可能求值出的字符串键（无法确定 ⇒ 空列表：证明不了就不报）。"""
    if depth > 3:
        return []
    e = strip_parens(expr.strip())
    if not e:
        return []
    lit_expr = _lone_literal(e)
    if lit_expr is not None:
        inner = lit_expr[3:-3] if lit_expr.startswith('"""') else lit_expr[1:-1]
        if "$" in inner or "\\" in inner:
            return []
        return [inner]
    if re.fullmatch(r"[A-Za-z_]\w*", e):
        span = _payload_binding(struct, e, pos)
        if span is None:
            return []
        return _page_key_values(lit[span[0]:span[1]], lit, struct, span[0], depth + 1)
    return []

def _page_raw_reads(lit: str, struct: str, raw_for_line: str, names: set) -> list:
    """[(rule_snippet, why)] for every page-size read/write this handler does itself."""
    hits = []
    for m in _PAGE_READ_CALL_RE.finditer(struct):
        args = call_args(struct, m.end() - 1)
        if not args:
            continue
        keys = []
        for a, b in args:
            keys += _page_key_values(lit[a:b], lit, struct, a)
        hit = [k for k in keys if k in names]
        if not hit:
            continue
        if m.group(1) != "getInt":
            # `get(key)` only reads a page size when the value is parsed as an int
            line_end = struct.find("\n", m.end())
            line = struct[m.end():len(struct) if line_end < 0 else line_end]
            if "toIntOrNull" not in line:
                continue
        hits.append((
            raw_for_line[m.start():m.start() + 60].split("\n")[0].strip(),
            "`%s(%s)` reads the page size raw" % (m.group(1), hit[0]),
        ))
    for m in _PAGE_CLAMP_RE.finditer(struct):
        hits.append((m.group(0), "a clamped page size"))
    return hits

def _exit2_producers(code: str) -> set:
    """Names of helpers that answer the shared usage-error exit code."""
    return {
        name for name, _p, _r, body in _fn_bodies_with_sigs(code)
        if "NativeOffloadResult(2" in body
    }


def _page_line(raw: str, snippet: str = "") -> str:
    """`(line N)` for the first occurrence of `snippet`, or ''."""
    if not snippet:
        return ""
    idx = raw.find(snippet)
    if idx < 0:
        return ""
    return f" (line {raw[:idx].count(chr(10)) + 1})"


def _page_declared_surfaces(struct: str, code: str) -> list:
    """[{name, file, legacy, names}] for every `val X = PageSizeSurface(…)`.

    [batch6-t01] 实参块用共享 `match_bracket` 定界（结构视图），字符串取值读
    字面量视图 —— 位置等长可互换。`names` 是 **派生**的：PG2 用它判断"被读的
    键是不是本 surface 的分页键"，不再靠正则里写死 `"limit"|"max"`。
    """
    out = []
    for m in re.finditer(
        r"\bval\s+(\w+)\s*(?::\s*[\w.]+\s*)?=\s*PageSizeSurface\s*\(", struct
    ):
        open_idx = m.end() - 1
        k = match_bracket(struct, open_idx)
        if k < 0:
            continue
        block = code[open_idx + 1:k]
        file_m = re.search(r'handlerFile\s*=\s*"([^"]+)"', block)
        legacy_m = re.search(r"legacyShortMax\s*=\s*(true|false)", block)
        names_m = re.search(r"names\s*=\s*listOf\s*\(([^)]*)\)", block)
        names = set()
        if names_m:
            names = {t for t in re.findall(r'"([^"$\\]*)"', names_m.group(1)) if t}
        out.append({
            "name": m.group(1),
            "file": file_m.group(1) if file_m else "",
            "legacy": bool(legacy_m and legacy_m.group(1) == "true"),
            "names": names,
        })
    return out


def _page_registered(code: str):
    """The names listed in `val SURFACES = listOf(…)`, or None when absent."""
    m = re.search(r"\bval\s+SURFACES\s*(?::\s*[\w.<>]+\s*)?=\s*listOf\(([^)]*)\)", code)
    if not m:
        return None
    return [t.strip() for t in m.group(1).split(",") if t.strip()]


def _when_arms(struct: str, open_brace: int) -> list:
    """[(cond_text, body_start, body_end)] — `when` 块的顶层臂。

    [batch6-t01] 共享 `when_branches` 按顶层**换行**切臂，`LABEL ->\n body`
    形态会拿到空体（batch5-t06 报告 R6 已登记）。这里按顶层 `->` 切，臂体从
    箭头后到**下一个臂的条件行首**，多行臂也完整。仅用于本门 PG3 的"拒绝被
    消费"判据。
    """
    close = match_bracket(struct, open_brace)
    if close < 0:
        return []
    inner_start, inner_end = open_brace + 1, close
    arrows, depth, i = [], 0, inner_start
    while i < inner_end:
        ch = struct[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and struct.startswith("->", i):
            arrows.append(i)
            i += 2
            continue
        i += 1
    arms = []
    for idx, arrow in enumerate(arrows):
        cond_start = struct.rfind("\n", inner_start, arrow) + 1
        if cond_start < inner_start:
            cond_start = inner_start
        body_start = arrow + 2
        if idx + 1 < len(arrows):
            nxt = arrows[idx + 1]
            body_end = struct.rfind("\n", body_start, nxt) + 1
            if body_end <= body_start:
                body_end = nxt
        else:
            body_end = inner_end
        arms.append((struct[cond_start:arrow].strip(), body_start, body_end))
    return arms

def _resolve_consumption(struct: str, pos: int):
    """承载 `pos` 处 resolve 调用的 `when`，其 `Invalid` 臂怎么消费拒绝。

    'exit' = 在可达路径上 return/throw（用法错误）· 'face' = 构造拒绝值交回
    调用方 · None = 没消费（含"臂体被恒假守卫包住"与"根本没有 Invalid 臂"）。
    """
    for m in re.finditer(r"\bwhen\s*\(", struct):
        p_close = match_bracket(struct, m.end() - 1)
        if p_close < 0 or not (m.end() <= pos <= p_close):
            continue
        k = p_close + 1
        while k < len(struct) and struct[k] in " \t\r\n":
            k += 1
        if k >= len(struct) or struct[k] != "{":
            return None
        kind = None
        for cond, bs, be in _when_arms(struct, k):
            if not re.search(r"\bInvalid\b", cond):
                continue
            arm = struct[bs:be]
            if any(path_feasible(struct, bs + r.start())
                   for r in re.finditer(r"\b(?:return|throw)\b(?!@)", arm)):
                return "exit"
            if re.search(r"\b(?:Invalid|Refused)\s*\(", arm):
                kind = "face"
        return kind
    return None

def _invalid_arms(struct: str) -> list:
    """Every `when` arm whose condition names `Invalid` — the refusal consumers."""
    out = []
    for m in re.finditer(r"\bwhen\s*(\(|\{)", struct):
        if m.group(1) == "(":
            p_close = match_bracket(struct, m.end() - 1)
            if p_close < 0:
                continue
            k = p_close + 1
            while k < len(struct) and struct[k] in " \t\r\n":
                k += 1
        else:
            k = m.end() - 1
        if k >= len(struct) or struct[k] != "{":
            continue
        for cond, bs, be in _when_arms(struct, k):
            if re.search(r"\bInvalid\b", cond):
                out.append((cond, bs, be))
    return out

@_fail_closed
def check_offload_page_size(sources: dict) -> list:
    """PG0-PG5 — see the block comment above. `sources` maps file name → text."""
    findings = []
    # [batch6-t01] 双视图：`codes`（字面量通道）读 handlerFile / names / SURFACES
    # 这类字面量；`structs`（结构通道）做完括号与分支判定。
    codes = {name: code_only(src) for name, src in sources.items()}
    structs = {name: code_view(src) for name, src in sources.items()}

    # ── PG0: derive the resolver and its scope, fail-closed ────────────────
    resolver_files = [
        name for name, code in codes.items()
        if re.search(r"sealed class\s+" + PAGE_OUTCOME + r"\b", code)
        and re.search(r"\bfun\s+resolve\s*\(", code)
    ]
    if len(resolver_files) != 1:
        findings.append(
            ("PG0", f"{len(resolver_files)} page-size resolver(s)",
             "exactly one file must declare the shared resolver "
             f"(a sealed {PAGE_OUTCOME} plus `fun resolve`) — the gate derives "
             "every other rule from it"))
        return findings
    resolver_name = resolver_files[0]
    rcode = codes[resolver_name]
    rstruct = structs[resolver_name]
    raw_resolver = sources[resolver_name]

    bodies = [
        (params, body) for name, params, _ret, body in _fn_bodies_with_sigs(rcode)
        if name == "resolve" and body
    ]
    if len(bodies) != 1:
        findings.append(
            ("PG0", f"{len(bodies)} `resolve` bodies",
             "the resolver must be one function with a readable body"))
        return findings
    params, body = bodies[0]
    if "default" not in params:
        findings.append(
            ("PG0", "the resolver takes no default",
             "the default must arrive as a parameter, so one surface can carry "
             "two documented defaults"))

    # The refusals may live in a helper the resolver calls (`checked()` in the
    # shipped shape), so the teeth are judged over the CALL CLOSURE — a bounded
    # one-hop-per-round walk, not a brace guess.
    fns = {n: b for n, _p, _r, b in _fn_bodies_with_sigs(rcode) if b and n != "resolve"}
    closure = body
    included = set()
    for _round in range(4):
        added = False
        for fn_name, fn_body in fns.items():
            if fn_name in included:
                continue
            if re.search(r"\b" + re.escape(fn_name) + r"\s*\(", closure):
                closure += "\n" + fn_body
                included.add(fn_name)
                added = True
        if not added:
            break

    surfaces = _page_declared_surfaces(rstruct, rcode)
    if not surfaces:
        findings.append(
            ("PG0", "no declared surfaces",
             "the resolver file must declare every surface it serves "
             "(`val X = PageSizeSurface(handlerFile = …)`)"))
        return findings
    registered = _page_registered(rcode)
    if registered is None:
        findings.append(
            ("PG0", "no SURFACES registry",
             "`val SURFACES = listOf(…)` is what the gate's scope is derived "
             "from — without it a declared surface escapes every rule"))
        return findings
    declared_names = [s["name"] for s in surfaces]
    for name in declared_names:
        if name not in registered:
            findings.append(
                ("PG0", f"surface `{name}` is declared but not registered",
                 "add it to SURFACES: an unregistered surface is never checked"))
    for name in registered:
        if name not in declared_names:
            findings.append(
                ("PG0", f"SURFACES lists unknown `{name}`",
                 "the registry names a surface that does not exist"))

    owned = {}
    for surface in surfaces:
        if not surface["file"]:
            findings.append(
                ("PG0", f"surface `{surface['name']}` declares no handlerFile",
                 "the gate derives its scope from handlerFile — without it the "
                 "surface is checked nowhere"))
        elif surface["file"] not in sources:
            findings.append(
                ("PG0", f"surface `{surface['name']}` names a missing handler",
                 f"{surface['file']} is not part of the offload sources"))
        else:
            owned.setdefault(surface["file"], []).append(surface["name"])

    # ── PG3: the resolver keeps its refusals ──────────────────────────────
    if re.search(r"\bgetInt\s*\(", rcode):
        findings.append(
            ("PG3", "lossy integer read in the resolver",
             "the page size must be read as the RAW string: getInt maps "
             "'spelled but unreadable' onto 'not spelled'"))
    if "toIntOrNull" not in closure:
        findings.append(
            ("PG3", "no non-numeric refusal",
             "a spelled value that is not an integer must be refused, not "
             "answered with the default"))
    if not re.search(r"< 1\b|<=\s*0\b", closure):
        findings.append(
            ("PG3", "no positive lower bound",
             "zero/negative must be refused before it reaches a collector "
             "(a zero-row page reads as 'nothing there')"))
    if not re.search(r"\.max\s*!=\s*null|\.max\s*\?\.|<=\s*\w*\.?max", closure):
        findings.append(
            ("PG3", "the declared upper bound is never compared",
             "an out-of-range page must be refused, not clamped — a clamp "
             "reports success for a request the handler did not honour"))
    # The surface's OWN vocabulary must be consulted (a bare identifier — the
    # loop variable over `surface.names`); a literal `hasFlag("n")` is the
    # legacy-alias refusal and cannot satisfy this.
    if not re.search(r"\.hasFlag\s*\(\s*[A-Za-z_]\w*\s*\)", body):
        findings.append(
            ("PG3", "a bare page-size flag is not refused",
             "a spelling with no follower binds no value at all (OffloadArgs "
             "files it as a flag): its presence must be consulted or the "
             "invocation silently answers the default"))
    defaults = list(re.finditer(r"\b(?:Ok|Valid|OkValue)\s*\(\s*default\s*\)", body))
    if len(defaults) != 1:
        findings.append(
            ("PG3", f"{len(defaults)} default answers",
             "exactly one answer may use the default, and it must be the last "
             "path (reached only when nothing was spelled)"))
    else:
        refused = [
            m for m in re.finditer(r"\bInvalid\s*\(", body)
        ]
        if not refused:
            findings.append(
                ("PG3", "no refusal path",
                 "the resolver never constructs a refusal — a spelled-but-"
                 "unusable value would fall through to the default"))
        elif min(m.start() for m in refused) > defaults[0].start():
            findings.append(
                ("PG3", "default answered first",
                 "every refusal must be reached BEFORE the default answer"))

    # [batch6-t01] 新增臂：拒绝**被消费**。旧 PG3 只问"构造过 Invalid 吗 / 默认
    # 答案是不是最后一个"，于是
    #     is Invalid -> if (false) return parsed        ← 拒绝照旧落空
    # 全绿：`return` 在源码里存在，但被一个恒假守卫包住（编译器折叠）。
    # 判据推到"位置 + 可达性"：每个提到 `Invalid` 的 when 臂，体内必须有一条
    # **退出外层函数**的 `return`/`throw`，且该语句位于可达路径上。
    for cond, arm_start, arm_end in _invalid_arms(rstruct):
        live = False
        for m in re.finditer(r"\b(?:return|throw)\b(?!@)", rstruct[arm_start:arm_end]):
            if path_feasible(rstruct, arm_start + m.start()):
                live = True
                break
        if not live:
            findings.append(
                ("PG3", "the refused page size is not acted on",
                 "arm `%s` must terminate on a live path (a `return parsed` "
                 "behind `if (false)` is a dead guard): the refusal would fall "
                 "through to the default page" % _squash(cond)))

    variants = re.findall(
        r"(?:data\s+)?class\s+(\w+)\s*\([^)]*\)\s*:\s*" + PAGE_OUTCOME + r"\(",
        rcode,
    )
    if len(variants) < 2:
        findings.append(
            ("PG3", f"{len(variants)} outcome variant(s)",
             f"the {PAGE_OUTCOME} type needs at least an ok and a refused "
             "variant, so 'could not honour it' is expressible"))

    # ── PG5: the legacy `-n` alias still refuses ──────────────────────────
    legacy = [s for s in surfaces if s["legacy"]]
    if legacy:
        if not re.search(r'hasFlag\s*\(\s*"n"', body):
            findings.append(
                ("PG5", "the surviving `-n` is not refused",
                 "a `-n` that survived the alias rewrite carries no number — "
                 "its token used to take the query slot"))
        elif "legacyShortMax" not in body:
            findings.append(
                ("PG5", "the `-n` refusal is not gated by the surface",
                 "only a surface that rewrites `-n` may refuse it "
                 "(`legacyShortMax`) — otherwise a `-n` typo becomes a page-"
                 "size error on every other CLI"))
        for surface in legacy:
            hcode = codes.get(surface["file"], "")
            if hcode and "bindLegacyShortMax(" not in hcode:
                findings.append(
                    ("PG5", f"`{surface['file']}` stopped binding `-n`",
                     "the surface declares legacyShortMax, so its handler must "
                     "still rewrite `-n N` to `--max N` before the parse"))

    # ── PG1 / PG2 / PG4: every registered handler ────────────────────────
    for handler_file, surface_names in sorted(owned.items()):
        hcode = codes[handler_file]
        raw_handler = sources[handler_file]
        label = "/".join(surface_names)
        call = PAGE_OUTCOME + ".resolve("
        if call not in hcode:
            findings.append(
                ("PG1", f"`{handler_file}` does not resolve its page size ({label})",
                 f"a registered surface must route through {call}…) — otherwise "
                 "it keeps whatever it does today"))
        page_names = set()
        for surface in surfaces:
            if surface["file"] == handler_file:
                page_names |= surface["names"]
        hits = _page_raw_reads(codes[handler_file], structs[handler_file],
                               raw_handler, page_names)
        for snippet, what in hits:
            findings.append(
                ("PG2", f"`{handler_file}` reads its page size raw: {what}"
                        f"{_page_line(raw_handler, snippet)}",
                 "the whitelist is the EMPTY SET: a registered handler may "
                 "not read, default or clamp a page size itself"))
        # PG4 — a refusal must become a usage error, never a default page.
        #
        # [batch6-t01] 判据从"函数体里出现过 exit 2 / 构造过 Invalid"推到
        # **"这个 resolve 调用的拒绝被它自己消费"**：旧形态是 `re.search` 一次
        # 函数体，于是同一个函数里任何一处无关的 `NativeOffloadResult(2, …)`
        # （比如 calendar doList 的日期区间拒绝）就能替分页拒绝盖章。见证形态：
        #     val _unusedSize = when (val size = resolve(…)) {
        #         is Invalid -> if (false) return NativeOffloadResult(2, …) else 50
        #     }
        #     val limit = args.getInt(limitKey) ?: 50
        # 现在逐个调用点判：承载它的 `when` 里 `Invalid` 臂必须
        #   (a) 在**可达路径**上退出（return/throw），或
        #   (b) 把拒绝**交回调用方**（构造 Invalid/Refused 值 ⇒ face，由其调用
        #       方负责 exit 2）。两者都不成立 = 这次解析的拒绝落空。
        producers = _exit2_producers(hcode)
        exit2 = "|".join([r"NativeOffloadResult\(\s*2"]
                         + [re.escape(p) + r"\s*\(" for p in sorted(producers)])
        refusal_re = r"(?:" + exit2 + r"|throw\s+InvalidPageSize\s*\()"
        covered = 0
        faces = []
        hstruct = structs[handler_file]
        for fn, b_start, b_end in _fn_spans(hstruct):
            fn_body = hstruct[b_start:b_end]
            if call not in fn_body:
                continue
            covered += fn_body.count(call)
            for cm in re.finditer(re.escape(call), fn_body):
                kind = _resolve_consumption(hstruct, b_start + cm.start())
                if kind == "exit":
                    continue
                if kind == "face":
                    if fn not in faces:
                        faces.append(fn)
                    continue
                findings.append(
                    ("PG4", f"`{fn}` does not act on the page-size refusal in "
                            f"{handler_file}",
                     "the resolve call must be consumed by an `Invalid` arm that "
                     "either exits with a usage error on a live path or hands the "
                     "refusal back to its caller — a refusal behind `if (false)` "
                     "or an unrelated exit 2 elsewhere in the function is not an "
                     "answer"))
        if covered != hcode.count(call):
            findings.append(
                ("PG4", f"`{handler_file}` resolves a page size outside a function",
                 "every resolve call must sit in a function whose refusal path "
                 "is judgeable"))
        for face in faces:
            callers = [
                (fn, hstruct[b_start:b_end])
                for fn, b_start, b_end in _fn_spans(hstruct)
                if fn != face
                and re.search(r"(?<![\w.])" + re.escape(face) + r"\s*\(", hstruct[b_start:b_end])
            ]
            if not callers:
                findings.append(
                    ("PG4", f"`{face}` answers a refusal with nothing in {handler_file}",
                     "a resolver face that is never called cannot honour its "
                     "contract anywhere"))
            for fn, fn_body in callers:
                if not re.search(refusal_re, fn_body):
                    findings.append(
                        ("PG4", f"`{fn}` ignores the page-size refusal from `{face}`",
                         "a resolution the handler cannot honour must be a usage "
                         "error (exit 2), never a default page size"))
    return findings

# ── selftest: every rule must have a tooth, and the green arm must pass ──────

GREEN_CONTACTS = r"""
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgs = request.argv.drop(1)
        val cmd = parseContactsCommand(rawArgs)
        val parsedArgs = cmd.args
        val needsWrite = cmd.sub == "delete"
        when (val sub = cmd.sub) {
            "list" -> doList(parsedArgs)
            "get" -> doGet(cmd.id, parsedArgs)
            "delete" -> doDelete(cmd.id, parsedArgs)
        }
    }
    companion object {
        internal fun parseContactsCommand(argv: List<String>): ContactsCommand {
            val args = OffloadArgs(bindLegacyShortMax(argv), booleanFlags = setOf("help", "h"))
            return ContactsCommand(
                sub = args.positional.getOrNull(0),
                id = args.positional.getOrNull(1)?.toLongOrNull(),
                args = args,
            )
        }
        internal fun bindLegacyShortMax(argv: List<String>): List<String> {
            if ("-n" !in argv) return argv
            val rewritten = ArrayList<String>(argv.size)
            rewritten.add("--max")
            val sub = OffloadArgs(rewritten, booleanFlags = setOf("help", "h")).positional.getOrNull(0)
            if (sub != "list" && sub != "search") return argv
            return rewritten
        }
        internal fun doSearch(parsedArgs: OffloadArgs): NativeOffloadResult {
            val max = resolvePageSize(parsedArgs, 20)
            val query = searchQueryOf(parsedArgs.positional)
                ?: return NativeOffloadResult(2, "android-contacts search: missing <query>\n")
            return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), parsedArgs) + "\n")
        }
        internal fun resolvePageSize(parsedArgs: OffloadArgs, default: Int = DEFAULT_LIST_MAX): PageSize {
            parsedArgs.get("max")?.let { raw -> return pageSizeOf(raw, "--max") }
            if (parsedArgs.hasFlag("max")) {
                return PageSize.Invalid("--max expects an integer between 1 and $MAX_PAGE_SIZE")
            }
            if (parsedArgs.hasFlag("n")) {
                return PageSize.Invalid("-n expects an integer between 1 and $MAX_PAGE_SIZE")
            }
            return PageSize.Ok(default)
        }
    }

"""

# [dv29-A3 A-05] green fixtures for the SHARED page-size contract (PG0-PG5).
# The resolver is quoted in the shape the tree ships it (raw read, bare-flag
# refusal, surface-gated `-n`, one default answered last, a checked() helper
# with both bounds), plus one minimal registered handler per surface. Every arm
# below mutates exactly one of these files.
GREEN_PAGE_RESOLVER = """
internal sealed class PageSizeOutcome {
    data class Ok(val value: Int) : PageSizeOutcome()
    data class Invalid(val message: String) : PageSizeOutcome()
    companion object {
        fun resolve(args: OffloadArgs, surface: PageSizeSurface, default: Int = surface.default): PageSizeOutcome {
            var winner: Ok? = null
            for (name in surface.names) {
                val raw = args.get(name) ?: continue
                when (val parsed = checked(raw, surface, "--$name")) {
                    is Invalid -> return parsed
                    is Ok -> if (winner == null) winner = parsed
                }
            }
            for (name in surface.names) {
                if (args.hasFlag(name)) return Invalid("--$name expects ${surface.range}")
            }
            if (surface.legacyShortMax && args.hasFlag("n")) {
                return Invalid("-n is not a page size")
            }
            return winner ?: Ok(default)
        }
        private fun checked(raw: String, surface: PageSizeSurface, spelled: String): PageSizeOutcome {
            val value = raw.toIntOrNull()
                ?: return Invalid("$spelled expects ${surface.range}, got '$raw'")
            if (value < 1 || (surface.max != null && value > surface.max)) {
                return Invalid("$spelled expects ${surface.range}, got $value")
            }
            return Ok(value)
        }
    }
}
internal object OffloadPageSize {
    val CONTACTS = PageSizeSurface(
        handlerFile = "ContactsOffloadHandler.kt",
        names = listOf("max"),
        default = 50,
        max = 500,
        legacyShortMax = true,
    )
    val CALENDAR = PageSizeSurface(
        handlerFile = "CalendarOffloadHandler.kt",
        names = listOf("limit", "max"),
        default = 50,
        max = null,
    )
    val SURFACES = listOf(CONTACTS, CALENDAR)
}
"""

# The delegation shape: `resolvePageSize` hands the outcome back, both collectors
# turn it into exit 2 (PG4 judges the CALLERS of that face).
GREEN_PAGE_CONTACTS = """
    private fun doList(parsedArgs: OffloadArgs): NativeOffloadResult {
        val max = when (val size = resolvePageSize(parsedArgs)) {
            is PageSize.Ok -> size.value
            is PageSize.Invalid -> return pageSizeRefusal("list", size.message)
        }
        return page(max)
    }
    companion object {
        internal fun bindLegacyShortMax(argv: List<String>): List<String> = argv
        internal fun resolvePageSize(parsedArgs: OffloadArgs, default: Int = 50): PageSize =
            when (val outcome = PageSizeOutcome.resolve(parsedArgs, OffloadPageSize.CONTACTS, default)) {
                is PageSizeOutcome.Ok -> PageSize.Ok(outcome.value)
                is PageSizeOutcome.Invalid -> PageSize.Invalid(outcome.message)
            }
        private fun pageSizeRefusal(sub: String, message: String): NativeOffloadResult {
            return NativeOffloadResult(2, "android-contacts $sub: $message")
        }
    }
"""

# The direct shape: the collector answers the refusal itself.
GREEN_PAGE_CALENDAR = """
    private fun doList(args: OffloadArgs): NativeOffloadResult {
        val limit = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.CALENDAR)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                return NativeOffloadResult(2, "android-calendar list: ${size.message}")
        }
        return NativeOffloadResult(0, "$limit")
    }
"""

GREEN_PAGE_SOURCES = {
    "OffloadPageSize.kt": GREEN_PAGE_RESOLVER,
    "ContactsOffloadHandler.kt": GREEN_PAGE_CONTACTS,
    "CalendarOffloadHandler.kt": GREEN_PAGE_CALENDAR,
}

GREEN_CONFIG = """
"""

GREEN_CONFIG = """
    private fun cmdSet(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val fileArg = args.get("file")
        readLinuxPath(fileArg, request.sessionId, request.cwd) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS", "--file: could not read value")
        readLinuxPath(fileArg, request.sessionId, request.cwd) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS", "--file: could not read value")
        readLinuxPath(fileArg, request.sessionId, request.cwd) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS", "--file: could not read value")
    }
    private fun parseConfigArgs(argv: List<String>): OffloadArgs =
        OffloadArgs(argv, valueShortOptions = setOf("f", "p", "s"))
    private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String? {
        val resolved = resolveMaybeRelative(linuxPath, cwd)
        val hostFile: File = PRootKernel.resolvePathForSession(sessionId, resolved, context) ?: return null
        return try { hostFile.readText() } catch (_: Throwable) { null }
    }
    companion object {
        internal fun resolveMaybeRelative(path: String, cwd: String?): String =
            if (!path.startsWith("/") && !cwd.isNullOrEmpty() && cwd.startsWith("/")) {
                if (cwd.endsWith("/")) "$cwd$path" else "$cwd/$path"
            } else path
    }
"""

# [da1020-C] green fixtures for the two new arms — minimal but every pinned
# rule must be satisfiable from code (never from prose).
GREEN_BROWSERUSE = """
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val inputJson = buildInputJson(args, request.sessionId, request.cwd)
    }
    private fun buildInputJson(args: OffloadArgs, sessionId: String?, cwd: String?): JSONObject {
        args.get("paths")?.let { raw ->
            val tokens = resolveMaybeRelativeAll(rawTokens, cwd)
            if (tokens.isNotEmpty()) obj.put("paths", JSONArray(tokens))
        }
        val cookiesFile = args.get("cookies-file", "cookies_file")
        val cookiesRaw: String? = if (cookiesFile != null) {
            val joined = resolveMaybeRelative(cookiesFile, cwd)
            val host = PRootKernel.resolvePathForSession(sessionId, joined, app)
                ?: throw IllegalArgumentException("--cookies-file: cannot resolve path '$cookiesFile'")
            runCatching { host.readText() }.getOrNull()
                ?: throw IllegalArgumentException("--cookies-file: could not read '$cookiesFile'")
        } else {
            args.get("cookies")
        }
    }
    internal fun resolveMaybeRelativeAll(paths: List<String>, cwd: String?): List<String> =
        paths.map { resolveMaybeRelative(it, cwd) }
"""

GREEN_FASTIO = """
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgv = request.argv.drop(1)
        val isTarCommand = firstSubcommandToken(rawArgv) == "tar"
        val args = OffloadArgs(
            normalizeLongOptionSpelling(normalizeTarShorts(rawArgv)),
            booleanFlags = fastioBooleanFlags(isTarCommand),
        )
        val destRaw = args.get("directory") ?: request.cwd
    }
    companion object {
        internal fun firstSubcommandToken(rawArgv: List<String>): String? {
            val noValue = OffloadOutput.OUTPUT_FLAGS + fastioBooleanFlags(isTarCommand = true)
            var i = 0
            while (i < rawArgv.size) {
                val token = rawArgv[i]
                if (!token.startsWith("-")) return token
                val consumesValue = when {
                    token in SHORT_VALUE_OPTIONS -> true
                    token.substring(2) !in noValue -> true
                    else -> false
                }
                i += if (consumesValue) 2 else 1
            }
            return null
        }
        private val SHORT_VALUE_OPTIONS = setOf("-C")
        private val SHORT_LONG_OPTIONS = setOf(
            "-name", "-type", "-limit", "-max-entries", "-ignore-case",
        )
        private val TAR_OPTIONS = setOf(
            "c", "x", "create", "extract", "f", "file", "z", "gzip", "C", "directory",
            "v", "verbose", "force", "max-entries",
        )
        internal fun fastioBooleanFlags(isTarCommand: Boolean): Set<String> =
            if (isTarCommand) {
                setOf("recursive", "force", "ignore-case", "create", "extract", "gzip", "verbose")
            } else {
                setOf("recursive", "force", "ignore-case")
            }
        internal fun normalizeTarShorts(argv: List<String>): List<String> {
            if (firstSubcommandToken(argv) != "tar") return argv
            val out = ArrayList<String>(argv.size)
            for (token in argv) {
                if (token.length < 2 || token[0] != '-' || token.startsWith("--")) {
                    out.add(token)
                    continue
                }
                val expanded = ArrayList<String>(token.length)
                var known = true
                for (ch in token.substring(1)) {
                    when (ch) {
                        'c' -> expanded.add("--create")
                        'x' -> expanded.add("--extract")
                        'z' -> expanded.add("--gzip")
                        'v' -> expanded.add("--verbose")
                        'C' -> expanded.add("--directory")
                        'f' -> Unit
                        else -> known = false
                    }
                }
                if (known) out.addAll(expanded) else out.add(token)
            }
            return out
        }
        internal fun normalizeLongOptionSpelling(argv: List<String>): List<String> =
            argv.map { if (it in SHORT_LONG_OPTIONS) "-$it" else it }
    }
"""


# [da1026-B] green fixtures for the OUTPUT_FLAGS / speak arms. The declared set
# is written out literally (never `OffloadOutput.OUTPUT_FLAGS + ...`), so the
# fixture states the wanted contract instead of restating the code.
GREEN_ARGS = """
internal class OffloadArgs(
    argv: List<String>,
    booleanFlags: Set<String> = emptySet(),
    private val valueShortOptions: Set<String> = emptySet(),
) {
    init {
        val effectiveBooleanFlags = booleanFlags + OffloadOutput.OUTPUT_FLAGS
        val vs = mutableMapOf<String, String>()
        val value = valueTokenOrNull(argv.getOrNull(i + 1))
        if (name in valueShortOptions && name !in effectiveBooleanFlags && value != null) {
            vs[name] = value
        }
        when {
            a.startsWith("-") && a.length > 1 &&
                (!isNegativeNumberToken(a) || a.substring(1) in effectiveBooleanFlags) -> {
                fs.add(a.substring(1))
            }
            else -> pos.add(a)
        }
    }

    private fun valueTokenOrNull(token: String?): String? =
        token?.takeIf { !it.startsWith("-") || isNegativeNumberToken(it) }

    private fun isNegativeNumberToken(token: String): Boolean =
        token.length > 1 && token[0] == '-' && token[1].isDigit()
}
"""

GREEN_OUTPUT = """
internal object OffloadOutput {
    val OUTPUT_FLAGS: Set<String> = setOf("compact", "quiet", "q", "help", "h")
}
"""

GREEN_SPEAK = """
class SpeakOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("stop", "status"))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        if (args.hasFlag("stop")) return cmdStop(args)
        if (args.hasFlag("status")) return buildStatus(args)
    }
}
"""


GREEN_DEBUG = """
class DebugOffloadHandler {
    private val PASSTHROUGH_SUBCOMMANDS = setOf("exec", "shizuku", "model-use", "modelUse")

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(raw)
        val argv = splitArgv(raw)
        val sub = argv.subcommand
            ?: return NativeOffloadResult(2, HELP)
        val rpc: RpcCall = try {
            buildRpcCall(sub, args, argv.rawRest)
        } catch (e: BadArgsException) {
            return NativeOffloadResult(2, "minis-debug ${'$'}sub: ${'$'}{e.message}")
        }
        val outArgs =
            if (sub in PASSTHROUGH_SUBCOMMANDS) OffloadArgs(argv.globals, BOOLEAN_FLAGS) else args
        return NativeOffloadResult(0, OffloadOutput.formatBody("{}", outArgs))
    }

    private fun buildRpcCall(sub: String, args: OffloadArgs, rawRest: List<String>): RpcCall {
        val rest = forwardedArgv(sub, args, rawRest)
        return when (sub) {
            "exec" -> {
                if (rest.isEmpty()) throw BadArgsException("missing <command...>")
                RpcCall("debug.shellExecute", JSONObject().put("command", execCommandLine(rest)))
            }
            "shizuku" -> RpcCall("debug.shizuku.exec", buildExecForwarder(rest, args))
            "model-use", "modelUse" -> RpcCall("debug.modelUse.exec", buildExecForwarder(rest, args))
            else -> throw BadArgsException("unknown subcommand '${'$'}sub'")
        }
    }

    private fun buildExecForwarder(rest: List<String>, args: OffloadArgs): JSONObject = JSONObject()

    internal fun splitArgv(tokens: List<String>): DebugArgvSplit {
        val globals = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.startsWith("-")) break
            globals.add(t)
            if (t == "--args") {
                i += 1
                if (i < tokens.size) globals.add(tokens[i])
            }
            i += 1
        }
        if (i >= tokens.size) return DebugArgvSplit(globals, null, emptyList())
        val tail = tokens.subList(i + 1, tokens.size).toList()
        val rest = if (tail.firstOrNull() == "--") tail.drop(1) else tail
        return DebugArgvSplit(globals, tokens[i], rest)
    }

    internal fun forwardedArgv(
        sub: String,
        args: OffloadArgs,
        rawRest: List<String>,
    ): List<String> = if (sub in PASSTHROUGH_SUBCOMMANDS) rawRest else args.positional.drop(1)

    internal fun execCommandLine(argv: List<String>): String = argv.joinToString(" ")
}
"""


# [da1032-C] green fixture for the Sessions date-bound arms: ONE funnel
# (resolveDateBounds → sessionsDateBound), three call sites, a throwing guard
# for a supplied-but-unreadable bound, and an unknown-command message generated
# from the SUBCOMMANDS list (so it cannot drift from the dispatch table).
GREEN_SESSIONS = """
class SessionsOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("full", "async", "wait", "help", "h"),
        )
        return try {
            when (val sub = args.positional.getOrNull(0)) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                "send" -> cmdSend(args)
                else -> NativeOffloadResult(EXIT_INVALID_ARGS, "Unknown command '$sub'. Valid: ${SUBCOMMANDS.joinToString(", ")}. ")
            }
        } catch (e: InvalidDateArg) {
            NativeOffloadResult(EXIT_INVALID_ARGS, e.message)
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val (startMs, endMs) = resolveDateBounds(args)
        return NativeOffloadResult(0, "${startMs}..${endMs}")
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val (startMs, endMs) = resolveDateBounds(args)
        return NativeOffloadResult(0, "${startMs}..${endMs}")
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val (startMs, endMs) = resolveDateBounds(args)
        return NativeOffloadResult(0, "${startMs}..${endMs}")
    }

    private fun cmdSend(args: OffloadArgs): NativeOffloadResult = NativeOffloadResult(0, "")

    private fun resolveDateBounds(args: OffloadArgs): Pair<Long?, Long?> {
        val startMs = sessionsDateBound("--start", args.get("start")) { parseSessionsDate(it) }
        val endMs = sessionsDateBound("--end", args.get("end")) { parseEndDate(it) }
        return sessionsDateWindow(startMs, endMs, args.get("start"), args.get("end"))
    }

    private fun parseEndDate(raw: String?): Long? {
        val ms = parseSessionsDate(raw) ?: return null
        return ms
    }

    companion object {
        private val SUBCOMMANDS = listOf("list", "search", "messages", "send")
        private const val EXIT_INVALID_ARGS = 2
        private const val HELP_TEXT = \"\"\"
Usage: minis-sessions-cli <command> [options]

Commands:
  list                  List recent sessions
  send                  Send a prompt to a session

Options:
  --async               (send only) Return immediately with the session id
  --wait                (send only) Block until the reply completes (default; --async wins)
  --wait-timeout <sec>  (send only) Give up waiting after <sec> seconds
\"\"\"
    }
}

internal class InvalidDateArg(flag: String, raw: String) :
    IllegalArgumentException("sessions: invalid $flag '$raw'")

internal fun parseSessionsDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return null
}

internal class InvertedDateWindow(startRaw: String, endRaw: String) :
    IllegalArgumentException("--end '$endRaw' is before --start '$startRaw'")

internal fun sessionsDateWindow(
    startMs: Long?,
    endMs: Long?,
    startRaw: String?,
    endRaw: String?,
): Pair<Long?, Long?> {
    if (startMs != null && endMs != null && endMs < startMs) {
        throw InvertedDateWindow(startRaw.orEmpty(), endRaw.orEmpty())
    }
    return startMs to endMs
}

internal fun sessionsDateBound(flag: String, raw: String?, parse: (String) -> Long?): Long? {
    if (raw.isNullOrBlank()) return null
    return parse(raw) ?: throw InvalidDateArg(flag, raw)
}
"""


# [dv19-A1 A-03] green fixture for the grep walk engine: shape-faithful skeleton
# of `grepTree` after the fix — the entry answers a non-positive budget as capped
# without walking, `preVisitDirectory` consults the cap before charging, and
# `visitFile` decides on the cap after `grepFile` returned.
GREEN_FASTIO_SEARCH = """
internal fun grepTree(
    root: Path,
    rootGuestPath: String,
    regex: Regex,
    limit: Int,
    maxEntries: Long,
    out: GrepOutcome,
) {
    if (limit <= 0) {
        out.matchesCapped = true
        return
    }
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (out.matchesCapped) return FileVisitResult.TERMINATE
                    if (charge(out, maxEntries)) return FileVisitResult.TERMINATE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (charge(out, maxEntries)) return FileVisitResult.TERMINATE
                    if (out.matchesCapped) return FileVisitResult.TERMINATE
                    grepFile(file, guestPathFor(root, rootGuestPath, file), regex, limit, out)
                    return if (out.matchesCapped) {
                        FileVisitResult.TERMINATE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }
            },
        )
    } catch (_: IOException) {
        out.failures++
    }
}

internal fun findTree(
    root: Path,
    regex: Regex,
    limit: Int,
    out: FindOutcome,
) {
    out.matched++
}

private fun grepFile(file: Path, guestPath: String, regex: Regex, limit: Int, out: GrepOutcome) {
    val reader = BufferedReader(InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))
    var lineNumber = 0
    while (true) {
        val line = reader.readLine() ?: break
        lineNumber++
        if (!regex.containsMatchIn(line)) continue
        if (out.matches.size >= limit) {
            out.matchesCapped = true
            return
        }
        out.matches.add(GrepMatch(guestPath, lineNumber, line))
    }
}
"""

# [da1033-D] ISSUE-04 green fixtures for the payload-JSON arm. Synthetic but
# shape-faithful: every accepted spelling appears once (variable, JSON builder,
# helper call, raw JSON literal, expression-into-arrayOf, a prose literal that
# lives in a COMMENT) plus the two N2 spellings (`return data.toString(2)`).
# [dv14-A1] green fixture for the notification arm: the deferred decision is
# taken from the RAW `--after` string, and the "--after or --at is required"
# refusal stays reachable behind it.
GREEN_NOTIFICATION_HANDLE = r"""
    private fun handleSend(sub: String, args: OffloadArgs): NativeOffloadResult {
        val title = args.get("title") ?: return NativeOffloadResult(2, "android-notification: missing --title\n")
        val body = args.get("body") ?: ""
        when (val plan = sendDisposition(sub, args.get("after"), args.get("at"))) {
            is SendDisposition.Invalid -> return NativeOffloadResult(2, plan.message)
            is SendDisposition.Deferred -> return scheduleDeferred(title, body, plan.secs, plan.at, args)
            SendDisposition.Immediate -> Unit
        }
        return NativeOffloadResult(0, JSONObject().put("scheduled", "immediate").toString(2) + "\n")
    }

    private fun scheduleDeferred(
        title: String,
        body: String,
        afterSec: Int?,
        atStr: String?,
        args: OffloadArgs,
    ): NativeOffloadResult {
        return when {
            afterSec != null && afterSec <= 0 ->
                NativeOffloadResult(2, "android-notification: --after must be positive\n")
            atStr != null -> unreachable()
            else -> NativeOffloadResult(2, "android-notification: --after or --at is required\n")
        }
    }

    companion object {
        internal sealed class SendDisposition {
            class Deferred(val secs: Int?, val at: String?) : SendDisposition()
            class Invalid(val message: String) : SendDisposition()
            object Immediate : SendDisposition()
        }

        internal fun sendDisposition(sub: String, rawAfter: String?, rawAt: String?): SendDisposition {
            val secs = when (rawAfter) {
                null -> null
                else -> rawAfter.toIntOrNull() ?: return SendDisposition.Invalid("bad --after\n")
            }
            if (secs != null || rawAt != null) return SendDisposition.Deferred(secs, rawAt)
            if (sub == "schedule") return SendDisposition.Deferred(null, null)
            return SendDisposition.Immediate
        }
    }
"""

GREEN_PAYLOAD_PHOTOS = r"""
    private fun nearPhotos(lat: Double, lon: Double, radiusKm: Double, max: Int): String {
        val photos = JSONArray()
        // A prose payload quoted in a comment must not trip the gate:
        // formatBody("No photos found within 1km of (1.0, 2.0).", args)
        val data = JSONObject()
            .put("media", photos)
            .put("count", photos.length())
            .put("lat", lat)
            .put("lon", lon)
            .put("radius_km", radiusKm)
        if (photos.length() == 0) {
            val hint = if (scanned == scanLimit) "scanned the newest 500 photos; no matches in that window" else ""
            if (hint.isNotEmpty()) data.put("hint", hint)
        }
        return data.toString(2)
    }

    private fun handleNear(args: OffloadArgs): NativeOffloadResult {
        return NativeOffloadResult(0, OffloadOutput.formatBody(nearPhotos(1.0, 2.0, 1.0, 20), args) + "\n")
    }
"""

GREEN_PAYLOAD_CONTACTS = r"""
    private fun queryFailedBody(message: String): String =
        JSONObject().put("error", "query_failed").put("message", message).toString()


    private fun doList(parsedArgs: OffloadArgs): NativeOffloadResult {
        val cursor = context.contentResolver.query(uri, projection, null, null, sort)
            ?: return NativeOffloadResult(
                1,
                OffloadOutput.formatBody(
                    queryFailedBody("Failed to query contacts."),
                    parsedArgs,
                ) + "\n",
            )
        val listArr = JSONArray()
        return NativeOffloadResult(0, OffloadOutput.formatBody(listArr.toString(2), parsedArgs) + "\n")
    }

    private fun doSearch(parsedArgs: OffloadArgs): NativeOffloadResult {
        val searchArr = JSONArray()
        return NativeOffloadResult(0, OffloadOutput.formatBody(searchArr.toString(2), parsedArgs) + "\n")
    }

    private fun doGet(id: Long, parsedArgs: OffloadArgs): NativeOffloadResult {
        cursor.use {
            if (!it.moveToFirst()) {
                val body = JSONObject().put("error", "not_found").put("id", id)
                    .put("message", "No contact with id=$id.")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
            }
        }
        // Precision arm: an expression-built payload (the header/staging shape)
        // is not the literal this rule judges.
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(arrayOf("All contacts (N):").joinToString("\n"), parsedArgs) + "\n",
        )
    }

    private fun doStrict(parsedArgs: OffloadArgs): NativeOffloadResult {
        return NativeOffloadResult(0, OffloadOutput.formatBody(@Q3@{"media": [], "count": 0}@Q3@, parsedArgs) + "\n")
    }

    private fun doFromConstant(parsedArgs: OffloadArgs): NativeOffloadResult {
        return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, parsedArgs) + "\n")
    }

    private fun doTemplate(parsedArgs: OffloadArgs): NativeOffloadResult {
        val code = "x"
        val payload = JSONObject().put("a", 1)
        return NativeOffloadResult(
            1,
            OffloadOutput.formatBody("{\"error\":\"${code}\"}", parsedArgs) + "\n" +
                OffloadOutput.formatBody("${payload}", parsedArgs) + "\n",
        )
    }
""".replace("@Q3@", '"' * 3)

# [da1034-A4] ISSUE-08 — the six files the rule set grew to cover. One green
# fixture per shape family so every new arm has a clean control next to it.
GREEN_PAYLOAD_LOCATION = r"""
    private fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        return try {
            when (val sub = request.argv.getOrNull(1)) {
                "current" -> current(args)
                else -> NativeOffloadResult(2, OffloadOutput.formatBody(
                    JSONObject().put("error", "usage").toString(), args) + "\n")
            }
        } catch (e: Throwable) {
            // A prose payload quoted in a comment must not trip the gate:
            // formatBody("android-location: ${e.message ?: "unknown error"}", args)
            val body = JSONObject()
                .put("error", "uncaught")
                .put("message", e.message ?: "unknown error")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun reverseGeocodeJson(lat: Double, lon: Double, lines: List<String>): JSONObject {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        return JSONObject()
            .put("latitude", lat)
            .put("longitude", lon)
            .put("address", lines.joinToString(", "))
            .put("count", lines.size)
    }

    private fun geocode(lat: Double, lon: Double, args: OffloadArgs): NativeOffloadResult {
        val lines = listOf<String>()
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(reverseGeocodeJson(lat, lon, lines).toString(2), args) + "\n",
        )
    }
"""

GREEN_PAYLOAD_SIDE_EFFECTS = r"""
    private fun doSet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        val label = args.get("label") ?: "RikkaMinis"
        val setData = JSONObject()
            .put("copied", true)
            .put("characters", 5)
            .put("label", label)
        return NativeOffloadResult(0, OffloadOutput.formatBody(setData.toString(2), args) + "\n")
    }

    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        // formatBody("Clipboard cleared.", args) — a comment, not a payload
        val clearData = JSONObject().put("cleared", true)
        return NativeOffloadResult(0, OffloadOutput.formatBody(clearData.toString(2), args) + "\n")
    }

    private fun openUrl(url: String, args: OffloadArgs): NativeOffloadResult {
        val openData = JSONObject().put("opened", true).put("url", url)
        return NativeOffloadResult(0, OffloadOutput.formatBody(openData.toString(2), args) + "\n")
    }

    private fun clearAll(nm: NotificationManager, args: OffloadArgs): NativeOffloadResult {
        val notifyData = JSONObject().put("cleared", "all")
        return NativeOffloadResult(0, OffloadOutput.formatBody(notifyData.toString(2), args) + "\n")
    }
"""

GREEN_PAYLOAD_CALENDAR = r"""
    private fun doList(cals: List<CalendarRef>, args: OffloadArgs): NativeOffloadResult {
        val events = JSONArray()
        val data = JSONObject()
            .put("events", events)
            .put("count", events.length())
        // A prose payload quoted in a comment must not trip the gate:
        // formatBody("android-calendar: no writable calendar", args)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }
"""

GREEN_PAYLOAD_FASTIO_USAGE = r"""
    private fun usage(reason: String, args: OffloadArgs): String =
        OffloadOutput.formatBody(usageText(reason), args) + "\n"

    private fun usageText(reason: String): String = JSONObject()
        .put("error", "usage")
        .put("message", reason)
        .put("help", HELP)
        .toString()

    private fun banner(reason: String): String {
        // Not a formatBody argument ⇒ N2 must NOT judge it (precision control
        // for the scope narrowing).
        return "minis-fastio: " + reason
    }
"""

# [dv18-A A-03] green fixture for the photos --album classification arms. The
# refusal reachability is written as the real when-branch shape, so a mutation
# that restores the silent downgrade trips P2.
GREEN_ALBUM_PHOTOS = """
    private fun handleAlbum(args: OffloadArgs): NativeOffloadResult {
        when (val selector = resolveAlbumSelector(args.get("id"), args.get("name"))) {
            is AlbumSelector.ById -> return ok(listOf(selector.id))
            is AlbumSelector.ByName -> return ok(listOf(selector.name))
            is AlbumSelector.Unreadable -> return NativeOffloadResult(2, albumSelectorRefusal(selector))
            AlbumSelector.Absent -> return NativeOffloadResult(
                2,
                "android-photos album: --id <bucket_id> or --name <bucket_name> is required\\n",
            )
        }
    }

    private fun handleAlbums(args: OffloadArgs): NativeOffloadResult {
        return ok(emptyList())
    }

internal sealed class AlbumSelector {
    object Absent : AlbumSelector()
    data class ById(val id: Long) : AlbumSelector()
    data class ByName(val name: String) : AlbumSelector()
    data class Unreadable(val flag: String, val raw: String) : AlbumSelector()
}

internal fun resolveAlbumSelector(rawId: String?, rawName: String?): AlbumSelector {
    if (rawId != null) {
        val id = rawId.toLongOrNull()
        return if (id != null) AlbumSelector.ById(id) else AlbumSelector.Unreadable("--id", rawId)
    }
    if (rawName != null) {
        return if (rawName.isNotBlank()) AlbumSelector.ByName(rawName)
        else AlbumSelector.Unreadable("--name", rawName)
    }
    return AlbumSelector.Absent
}

internal fun albumSelectorRefusal(selector: AlbumSelector.Unreadable): String =
    "android-photos album: " + selector.flag + " '" + selector.raw + "' is not usable\\n"
"""

# [dv22-A6 A-06] the fixed clipboard clear shape, all four subcommand guards
# and both write arms. The red arms below are mutations of THIS text, so the
# expectations are hand-written from the L-table and never from a run.
GREEN_CLIPBOARD_CLEAR = """
    private fun doGet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
    }

    private fun doSet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
    }

    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        when (clearAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
            ClipboardClearAction.PlatformClear -> cm.clearPrimaryClip()
            ClipboardClearAction.EmptyStringFallback -> cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        val data = JSONObject().put("cleared", true)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\\n")
    }

    private fun doStatus(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
    }
"""

OFFLOAD_HANDLER_DIR = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload"
OFFLOAD_SOURCE_DIRS = (
    OFFLOAD_HANDLER_DIR,
    "src/android/app/src/main/java/com/rikkaminis/app/offload",
)

# Handler basename → why it still ships a prose payload from another file.
PAYLOAD_DELEGATE_EXEMPT = {
    "WeatherOffloadHandler.kt": (
        "registered P2 (backlog 1045-A07): WeatherManager.fetchWeather() renders a "
        "human weather report; the structured sibling fetchWeatherJson() already "
        "exists, so this is a straightforward follow-up — deliberately not fixed in "
        "the android-player branch."
    ),
}

_PCS_FUN_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(")
_PCS_STR_RE = re.compile(r'"(?:[^"\\\n]|\\.)*"')


def _pcs_strip(src: str) -> str:
    """Comments out, string literals kept verbatim."""
    out = []
    i, n = 0, len(src)
    while i < n:
        two = src[i:i + 2]
        if two == "//":
            while i < n and src[i] != "\n":
                i += 1
        elif two == "/*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i = min(i + 2, n)
        elif src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def _pcs_match(code: str, i: int, opener: str, closer: str) -> int:
    depth = 0
    while i < len(code):
        c = code[i]
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i
        elif c == '"':
            j = i + 1
            while j < len(code):
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"':
                    break
                j += 1
            i = j
        i += 1
    return len(code) - 1


def _pcs_decl_table(sources: dict) -> dict:
    """callee name → {(basename, declared return type)}."""
    table = {}
    for base, src in sources.items():
        code = _pcs_strip(src)
        for m in _PCS_FUN_RE.finditer(code):
            end = _pcs_match(code, m.end() - 1, "(", ")")
            tail = code[end + 1:end + 1 + 240]
            mm = re.match(r"\s*:\s*([A-Za-z_][\w.<>?, ]*)", tail)
            rt = mm.group(1).strip() if mm else ""
            table.setdefault(m.group(1), set()).add((base, rt))
    return table


def _pcs_first_arg(code: str, open_idx: int) -> str:
    i, depth, start = open_idx + 1, 0, open_idx + 1
    while i < len(code):
        c = code[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return code[start:i]
            depth -= 1
        elif c == "," and depth == 0:
            return code[start:i]
        elif c == '"':
            j = i + 1
            while j < len(code):
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"':
                    break
                j += 1
            i = j
        i += 1
    return code[start:]


def _pcs_init(code: str, after_eq: int) -> str:
    """Initializer text: balanced to the end of the statement."""
    i, depth = after_eq, 0
    while i < len(code):
        c = code[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return code[after_eq:i]
            depth -= 1
        elif c == '"':
            j = i + 1
            while j < len(code):
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"':
                    break
                j += 1
            i = j
        elif c == "\n" and depth == 0:
            return code[after_eq:i]
        i += 1
    return code[after_eq:]


def _pcs_bindings(code: str) -> dict:
    out = {}
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?=", code):
        out.setdefault(m.group(1), _pcs_init(code, m.end()))
    return out


def _pcs_calls(text: str) -> set:
    # raw strings first (prose in a `"""…"""` HELP block spells words like
    # "text [" and would otherwise read as calls), then ordinary literals
    stripped = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', text)
    stripped = _PCS_STR_RE.sub('""', stripped)
    return set(re.findall(r"\b(\w+)\s*\(", stripped))


def _pcs_value_text(text: str) -> str:
    """Value positions only: a nested `val x = …` initializer is an
    intermediate (its result is not the payload), and a `catch`/`finally` block
    is the failure path — neither may be attributed to the payload. Without
    this, `val result = try { … } catch { … }` charged every call in the catch
    branch (including `formatBody` itself) to the payload."""
    out = []
    i = 0
    while i < len(text):
        m = re.compile(r"\b(?:val|var)\s+\w+\s*(?::[^=\n]+)?=").search(text, i)
        k = re.compile(r"\b(?:catch|finally)\b").search(text, i)
        nxt = min([x.start() for x in (m, k) if x] or [len(text)])
        out.append(text[i:nxt])
        if nxt >= len(text):
            break
        head = text[nxt:]
        if m and m.start() == nxt:
            init = _pcs_init(text, m.end())
            i = m.end() + len(init)
        else:
            brace = text.find("{", nxt)
            i = len(text) if brace < 0 else _pcs_match(text, brace, "{", "}") + 1
    return "".join(out)


def _pcs_delegates(base: str, code: str, table: dict) -> list:
    """[(callee, return_type, declaring_file)] for the payload of every
    `formatBody(` call in this file, resolved one binding level down."""
    bind = _pcs_bindings(code)
    hits = []
    for m in re.finditer(r"formatBody\(", code):
        expr = _pcs_first_arg(code, m.end() - 1).strip()
        # the payload is <ident> or <ident>.<method>(…) — follow the receiver
        src_text = expr
        mm = re.fullmatch(r"([A-Za-z_]\w*)(?:\s*\.\s*\w+\s*\([^()]*\))?", expr)
        if mm and mm.group(1) in bind:
            src_text = bind[mm.group(1)]
        for callee in _pcs_calls(_pcs_value_text(src_text)):
            decls = table.get(callee, ())
            if not decls:
                continue
            if any(d == base for (d, _) in decls):
                continue  # a same-file declaration of that name wins
            for (d, rt) in decls:
                hits.append((callee, rt, d))
    return sorted(set(hits))


@_fail_closed
def check_payload_delegates(sources: dict, payload_files, exemptions=None) -> list:
    """Z1–Z4. `sources` maps basename → file text (both offload dirs)."""
    exemptions = PAYLOAD_DELEGATE_EXEMPT if exemptions is None else exemptions
    table = _pcs_decl_table(sources)
    derived = {}
    for base, src in sources.items():
        code = _pcs_strip(src)
        if "formatBody(" not in code:
            continue
        hits = _pcs_delegates(base, code, table)
        if hits:
            derived[base] = hits
    findings = []
    registered = {Path(p).name for p in payload_files}
    # Z4 (first: an empty derivation would make Z1/Z2 vacuous)
    if not derived:
        findings.append(("Z4", "payload delegates: the derivation found no file", "derived set is empty"))
    # Z1 — unregistered delegating handler
    for base in sorted(derived):
        if base in registered or base in exemptions:
            continue
        why = ", ".join("%s()→%s in %s" % h for h in derived[base])
        findings.append(("Z1", "formatBody payload delegated across files", "%s: %s" % (base, why)))
    # Z2 — a registered payload file must not take a String from another file
    for base in sorted(derived):
        if base not in registered:
            continue
        for (callee, rt, d) in derived[base]:
            if rt == "String":
                findings.append((
                    "Z2",
                    "payload file takes its payload from a String delegate",
                    "%s: %s()→String in %s (a sentence from another file)" % (base, callee, d),
                ))
    # Z3 — exemptions must still be needed
    for base, reason in exemptions.items():
        if not str(reason).strip():
            findings.append(("Z3", "exemption without a reason", base))
        if base not in sources:
            findings.append(("Z3", "exemption names a missing file", base))
        elif base not in derived:
            findings.append(("Z3", "exemption no longer needed", base))
    return findings

# [dv25-A4 A-07] payload provenance: a handler that assembles its body from a
# structured envelope (green) vs one that hands over another file's sentence.
GREEN_PLAYER_HANDLER = r"""
class PlayerOffloadHandler {
    fun handle(sub: String, args: OffloadArgs): NativeOffloadResult {
        val outcome = try {
            when (sub) {
                "play" -> MediaPlayerManager.play(args.positional[1])
                else -> PlayerOutcome.failure("unknown_subcommand", sub)
            }
        } catch (e: Throwable) {
            PlayerOutcome.failure(PlayerOutcome.ERROR_PLAYER_FAILED, e.message ?: "unknown")
        }
        val envelope = playerEnvelope(sub, outcome)
        return NativeOffloadResult(
            if (outcome.ok) 0 else 1,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }
}
"""

PROSE_PLAYER_HANDLER = r"""
class PlayerOffloadHandler {
    fun handle(sub: String, args: OffloadArgs): NativeOffloadResult {
        val result = try {
            when (sub) {
                "play" -> MediaPlayerManager.play(args.positional[1])
                else -> "Error: unknown subcommand"
            }
        } catch (e: Throwable) {
            "Error: ${e.message}"
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(result.trimEnd('\n'), args) + "\n")
    }
}
"""

GREEN_PLAYER_MANAGER = r"""
object MediaPlayerManager {
    fun play(id: String): PlayerOutcome = PlayerOutcome.ok(session = id)
}
"""

PROSE_PLAYER_MANAGER = r"""
object MediaPlayerManager {
    fun play(id: String): String = "Playing '$id'"
}
"""

GREEN_PLAYER_OUTCOME = r"""
internal fun playerEnvelope(action: String, outcome: PlayerOutcome): JSONObject {
    return JSONObject().put("ok", outcome.ok).put("action", action)
}
"""

GREEN_WEATHER_HANDLER = r"""
class WeatherOffloadHandler {
    fun handle(args: OffloadArgs): NativeOffloadResult {
        val text = WeatherManager.fetchWeather(1.0, 2.0)
        return NativeOffloadResult(0, OffloadOutput.formatBody(text.trimEnd('\n'), args) + "\n")
    }
}
"""

GREEN_WEATHER_MANAGER = r"""
object WeatherManager {
    fun fetchWeather(latitude: Double, longitude: Double): String = "weather"
}
"""

# ── [dv27-A5 A-07] a help-guarded early exit must answer 0 for `--help` ──────
#
# `OffloadArgs` folds `--help` / `-h` into `flags` (both are in
# `OffloadOutput.OUTPUT_FLAGS`), so `tool --help` leaves `positional` EMPTY —
# byte-for-byte the state a bare `tool` is in. A guard that merges the two
# invocations (`hasFlag("h", "help") || positional.isEmpty()`) and then decides
# the exit code on *positional emptiness* therefore answers 2 for `--help` as
# well: its `else 0` arm is dead code. Five handlers shipped that shape, so
# every `tool --help` exited 2 and `set -e` / `cmd --help && …` probe chains
# broke (the agent reads `--help` as a failure).
#
# Rules, derived from the decision point (never from a spelling or a file list):
#   H1 — a help probe must be able to reach exit 0. For every `if (<guard>)`
#        whose condition probes the help flag, the guarded early exit's code
#        expression must evaluate to 0 while the flag is present:
#          • a literal 0 (or a `const val … = 0` name)                     → ok
#          • `if (C) A else B` where C probes the help flag: A (the help
#            side) must be 0;
#          • `if (C) A else B` where C does NOT probe it: C must then be a
#            TOKEN-COUNT separator (`argv.size` / `rawArgs.isEmpty`). A
#            POSITIONAL test cannot separate the two invocations — `--help` is
#            a flag, so positional stays empty — which is exactly the defect.
#        Anything else is fail-closed red (an exit code nobody can decide is
#        not evidence that `--help` works).
#   H2 — the bare-invocation contract stays 2 wherever it is expressible: when
#        the exit code varies and the guard can also be reached without help,
#        the branch serving the bare call must be 2 (the pinned contract).
#        A merged guard answering a literal 0 is left alone: `android-contacts`
#        / `android-browser-use` deliberately print help at exit 0 for a bare
#        call, and because their separator is a token count the help case is
#        still reachable — they are not this family.
#   H3 — fail-closed on the derivation itself: the family must still be found
#        (>= 15 sites) and a file that probes help must yield a decidable early
#        exit, so a rename or an extracted helper cannot narrow the rule to
#        nothing and turn the gate into a false green.
#
# Judged on comment-stripped text, so a KDoc quoting the old shape cannot trip
# the rule (1046-F9) and a comment cannot satisfy it either.
_HELP_PROBE_RE = re.compile(r'hasFlag\(([^)]*)\)')
_HELP_NAME_RE = re.compile(r'"([^"]*)"')
_NUM_LIT_RE = re.compile(r'^\d+$')
_BARE_SEPARATOR_RE = re.compile(
    r'\b(?:argv|rawArgs)\s*\.\s*(?:size|isEmpty|count|length|isNotEmpty)\b')
_POSITIONAL_TEST_RE = re.compile(
    r'\bpositional\s*\.\s*(?:isEmpty|isNotEmpty|count|size)\b')
_CONST_INT_RE = re.compile(r'\bconst\s+val\s+([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(\d+)\b')


def _statement_ifs(code: str):
    r"""Indices of the `(` of every `if (` in STATEMENT position.

    A naive `\bif\s*\(` scan also matches the `if (…) 0 else 2` nested INSIDE
    a call argument (`NativeOffloadResult(if (help) 0 else 2, HELP)`), whose
    "guarded region" is then the tail of that same expression — the gate would
    judge the fix as if it were the guard. Only an `if` whose enclosing bracket
    is a block (or nothing) can guard a statement, so the scan tracks the
    bracket stack and skips string literals.
    """
    stack = []
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if c == '"':
            j = i + 1
            while j < n:
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c in "([{":
            stack.append(c)
            i += 1
            continue
        if c in ")]}":
            if stack:
                stack.pop()
            i += 1
            continue
        if code.startswith("if", i) and (i == 0 or not (code[i - 1].isalnum() or code[i - 1] in "_.")):
            if not stack or stack[-1] == "{":
                j = i + 2
                while j < n and code[j].isspace():
                    j += 1
                if j < n and code[j] == "(":
                    yield j
            i += 2
            continue
        i += 1


def _help_probe_span(cond: str):
    """(start, end) of the first `hasFlag(...)` call naming the help flag."""
    for m in _HELP_PROBE_RE.finditer(cond):
        if "help" in _HELP_NAME_RE.findall(m.group(1)):
            return (m.start(), m.end())
    return None


def _exit_code_value(expr: str, consts: dict):
    """Int value of a numeric literal or a `const val … = <int>` name, else None."""
    e = expr.strip()
    if _NUM_LIT_RE.match(e):
        return int(e)
    return consts.get(e)


def _guarded_region(text: str, j: int) -> str:
    """The statement / block guarded by an `if (…)` whose `)` ends at [j]."""
    n = len(text)
    k = j
    while k < n and text[k].isspace():
        k += 1
    if k >= n:
        return ""
    if text[k] == "{":
        end = _pcs_match(text, k, "{", "}")
        return text[k:end + 1] if end >= 0 else text[k:]
    # single-statement form (`if (…) return NativeOffloadResult(…)`) — the call
    # may sit on the next line, so walk to the first complete call.
    idx = text.find("NativeOffloadResult(", k, min(n, k + 1200))
    if idx < 0:
        return ""
    close = _pcs_match(text, idx + len("NativeOffloadResult"), "(", ")")
    if close < 0:
        return ""
    eol = text.find("\n", close)
    return text[k:(eol if eol >= 0 else n)]


def _first_call_arg(text: str, paren_at: int):
    """Text of the first top-level argument of the call whose `(` is [paren_at]."""
    depth = 0
    j = paren_at
    while j < len(text):
        c = text[j]
        if c == '"':                      # a literal's brackets and commas do not count
            j += 1
            while j < len(text):
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            j += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return text[paren_at + 1:j]
        elif c == "," and depth == 1:
            return text[paren_at + 1:j]
        j += 1
    return ""


def _split_top_level_else(rest: str):
    """Split `A else B` at its top-level `else`; (A, B) or (None, None)."""
    depth = 0
    i = 0
    while i < len(rest):
        c = rest[i]
        if c == '"':
            j = i + 1
            while j < len(rest):
                if rest[j] == "\\":
                    j += 2
                    continue
                if rest[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and rest.startswith("else", i) \
                and (i == 0 or rest[i - 1].isspace()) \
                and (i + 4 >= len(rest) or rest[i + 4].isspace()):
            return rest[:i].strip(), rest[i + 4:].strip()
        i += 1
    return None, None


def _squash(text: str, limit: int = 90) -> str:
    flat = " ".join(text.split())
    return flat if len(flat) <= limit else flat[:limit] + "…"


def _help_exit_findings(file: str, guard: str, expr: str, consts: dict) -> list:
    """H1/H2 verdict for one help-guarded early exit."""
    findings = []
    e = expr.strip()
    value = _exit_code_value(e, consts)
    if value is not None:
        if value != 0:
            findings.append(("H1", f"{file}: --help exits {value}",
                             _squash(e)))
        return findings
    if not e.startswith("if") or "(" not in e:
        findings.append(("H1", f"{file}: exit code is not decidable (fail-closed)",
                         _squash(e)))
        return findings
    op = e.index("(")
    cl = _pcs_match(e, op, "(", ")")
    cond = e[op + 1:cl]
    if_side, else_side = _split_top_level_else(e[cl + 1:])
    if if_side is None:
        findings.append(("H1", f"{file}: exit-code conditional has no else arm",
                         _squash(e)))
        return findings
    probe = _help_probe_span(cond)
    if probe is not None:
        # `!hasFlag(…)` flips which arm serves the help call.
        negated = cond.strip().startswith("!")
        help_side, bare_side = (else_side, if_side) if negated else (if_side, else_side)
    elif _BARE_SEPARATOR_RE.search(cond) and not _POSITIONAL_TEST_RE.search(cond):
        # A token count separates a bare call (true) from a help call (false).
        help_side, bare_side = else_side, if_side
    else:
        findings.append((
            "H1",
            f"{file}: exit code decided on positional emptiness — `--help` is a flag, "
            f"so positional is empty for it too and the other arm is dead",
            _squash(cond)))
        return findings
    help_value = _exit_code_value(help_side, consts)
    if help_value != 0:
        findings.append(("H1", f"{file}: the help arm of the exit code must be 0",
                         _squash(help_side)))
    outer_probe = _help_probe_span(guard)
    guard_rest = (guard[:outer_probe[0]] + guard[outer_probe[1]:]) if outer_probe else ""
    if "||" in guard_rest:
        if _exit_code_value(bare_side, consts) != 2:
            findings.append(("H2", f"{file}: a bare invocation must still exit 2",
                             _squash(bare_side)))
    return findings


HELP_EXIT_MIN_SITES = 15


@_fail_closed
def check_help_exit(sources: dict, min_sites: int = HELP_EXIT_MIN_SITES) -> list:
    """[dv27-A5 A-07] H1–H3 above, over every offload source file.

    [min_sites] is a parameter so the selftest can exercise the shape arms on a
    hand-written fixture (which naturally has fewer sites) while the tree run
    keeps the fail-closed floor.
    """
    findings = []
    consts = {}
    for src in sources.values():
        for m in _CONST_INT_RE.finditer(code_only(src)):
            consts[m.group(1)] = int(m.group(2))
    sites = 0
    for name in sorted(sources):
        code = code_only(sources[name])
        for op in _statement_ifs(code):
            cl = _pcs_match(code, op, "(", ")")
            cond = code[op + 1:cl]
            if _help_probe_span(cond) is None:
                continue
            region = _guarded_region(code, cl + 1)
            call = region.find("NativeOffloadResult(")
            if call < 0:
                findings.append((
                    "H3", f"{name}: help guard with no exit-code site (fail-closed)",
                    _squash(cond)))
                continue
            arg = _first_call_arg(region, region.index("(", call))
            sites += 1
            findings += _help_exit_findings(name, cond, arg, consts)
    if sites < min_sites:
        findings.append((
            "H3", f"the help/bare derivation found only {sites} early exits "
                  f"(< {min_sites}, fail-closed)",
            "the handlers were renamed or the guard shape changed — re-derive, "
            "do not delete the rule"))
    return findings


# [dv27-A5 A-07] Every ACCEPTED spelling of the help / bare-invocation exit
# code, one class each — the mutation arms above are diffs against this text.
GREEN_HELP_SHAPES = """
class ConditionalHelp {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        // `--help` is a flag, so positional is empty for it too.
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.hasFlag("h", "help")) 0 else 2, HELP)
        }
        return NativeOffloadResult(0, args.positional[0])
    }
}

class TokenSeparatorHelp {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (request.argv.size <= 1 || args.hasFlag("h", "help") && args.positional.isEmpty()) {
            return NativeOffloadResult(if (request.argv.size <= 1) 2 else 0, HELP)
        }
        return NativeOffloadResult(0, "list")
    }
}

class ConstantHelp {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(EXIT_OK, HELP)
        return NativeOffloadResult(EXIT_FAILURE, "nope")
    }
}

class LiteralHelp {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (rawArgs.isEmpty() || args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        return NativeOffloadResult(1, "nope")
    }
}

private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
"""


def selftest() -> int:
    # Each case mutates ONE arm and names the rules it must trip. The expected
    # sets are written by hand from the rule table (never derived from a run:
    # a derived expectation would follow the checker into being wrong).
    red_cases = [
        ("raw argv slot read",
         GREEN_CONTACTS.replace('val needsWrite = cmd.sub == "delete"',
                                'val needsWrite = rawArgs[0] == "delete"'),
         GREEN_CONFIG, {"C2", "C10"}),
        ("raw argv dispatch",
         GREEN_CONTACTS.replace("when (val sub = cmd.sub)", "when (val sub = rawArgs[0])"),
         GREEN_CONFIG, {"C3", "C10"}),
        ("raw tail passed to a body",
         GREEN_CONTACTS.replace('"list" -> doList(parsedArgs)',
                                '"list" -> doList(rawArgs.drop(1), parsedArgs)'),
         GREEN_CONFIG, {"C11"}),
        ("id off the raw list",
         GREEN_CONTACTS.replace("id = args.positional.getOrNull(1)?.toLongOrNull()",
                                "id = args.firstOrNull()?.toLongOrNull()"),
         GREEN_CONFIG, {"C7", "C12"}),
        ("help not declared boolean",
         GREEN_CONTACTS.replace('booleanFlags = setOf("help", "h")', "booleanFlags = emptySet()"),
         GREEN_CONFIG, {"C8"}),
        ("max resolver loses the parsed shape",
         GREEN_CONTACTS.replace(
             "internal fun resolvePageSize(parsedArgs: OffloadArgs, default: Int = DEFAULT_LIST_MAX)",
             "internal fun resolvePageSize(args: List<String>)"),
         GREEN_CONFIG, {"C9"}),
        ("sub off the raw list",
         GREEN_CONTACTS.replace("sub = args.positional.getOrNull(0)", "sub = rawArgs[0]"),
         GREEN_CONFIG, {"C6", "C10"}),
        ("a comment cannot mask a raw argv read",
         GREEN_CONTACTS.replace("val cmd = parseContactsCommand(rawArgs)",
                                "val cmd = parseContactsCommand(rawArgs)\n        val x = rawArgs[0]"),
         GREEN_CONFIG, {"C10"}),
        ("cwd-less --file call",
         GREEN_CONTACTS,
         GREEN_CONFIG.replace("readLinuxPath(fileArg, request.sessionId, request.cwd) ?: return errorResult(",
                              "readLinuxPath(fileArg, request.sessionId) ?: return errorResult(", 1),
         {"K7", "K8"}),
        ("resolver drops the cwd",
         GREEN_CONTACTS,
         GREEN_CONFIG.replace("private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String?",
                              "private fun readLinuxPath(linuxPath: String, sessionId: String?): String?", 1),
         {"K1"}),
        ("join helper renamed",
         GREEN_CONTACTS,
         GREEN_CONFIG.replace("internal fun resolveMaybeRelative(path: String, cwd: String?): String =",
                              "internal fun unusedHelper(): Int = 0"),
         {"K2"}),
        ("absolute-cwd guard dropped",
         GREEN_CONTACTS,
         GREEN_CONFIG.replace('if (!path.startsWith("/") && !cwd.isNullOrEmpty() && cwd.startsWith("/"))',
                              'if (!path.startsWith("/"))'),
         {"K3"}),
        ("session resolver bypassed",
         GREEN_CONTACTS,
         GREEN_CONFIG.replace("PRootKernel.resolvePathForSession(sessionId, resolved, context)",
                              "File(resolved)"),
         {"K5"}),
        # [da1020-C] new arms
        ("relative --cookies-file join dropped",
         GREEN_CONTACTS,
         GREEN_CONFIG,
         GREEN_BROWSERUSE.replace(
             "val joined = resolveMaybeRelative(cookiesFile, cwd)\n"
             "            val host = PRootKernel.resolvePathForSession(sessionId, joined, app)",
             "val host = PRootKernel.resolvePathForSession(sessionId, cookiesFile, app)"),
         GREEN_FASTIO, {"B5", "B6", "B7"}),
        ("--paths tokens reach the resolver unjoined",
         GREEN_CONTACTS, GREEN_CONFIG,
         GREEN_BROWSERUSE.replace("val tokens = resolveMaybeRelativeAll(rawTokens, cwd)",
                                  "val tokens = rawTokens"),
         GREEN_FASTIO, {"B3"}),
        ("buildInputJson loses the cwd parameter",
         GREEN_CONTACTS, GREEN_CONFIG,
         GREEN_BROWSERUSE.replace(
             "private fun buildInputJson(args: OffloadArgs, sessionId: String?, cwd: String?): JSONObject",
             "private fun buildInputJson(args: OffloadArgs, sessionId: String?): JSONObject").replace(
             "buildInputJson(args, request.sessionId, request.cwd)",
             "buildInputJson(args, request.sessionId)"),
         GREEN_FASTIO, {"B1", "B8"}),
        ("cwd-less call site only",
         GREEN_CONTACTS, GREEN_CONFIG,
         GREEN_BROWSERUSE.replace("buildInputJson(args, request.sessionId, request.cwd)",
                                  "buildInputJson(args, request.sessionId)"),
         GREEN_FASTIO, {"B8"}),
        ("a comment cannot mask a raw tar verdict",
         GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE,
         GREEN_FASTIO.replace("val isTarCommand = firstSubcommandToken(rawArgv) == \"tar\"",
                              "val isTarCommand = firstSubcommandToken(rawArgv) == \"tar\"\n"
                              "        val x = rawArgv.firstOrNull() == \"tar\""),
         {"F4"}),
        ("tar verdict reverted to the first raw token",
         GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE,
         GREEN_FASTIO.replace("val isTarCommand = firstSubcommandToken(rawArgv) == \"tar\"",
                              "val isTarCommand = rawArgv.firstOrNull() == \"tar\""),
         {"F2", "F4"}),
        ("expander guard reverted to the first raw token",
         GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE,
         GREEN_FASTIO.replace("if (firstSubcommandToken(argv) != \"tar\") return argv",
                              "if (argv.firstOrNull() != \"tar\") return argv"),
         {"F3", "F4"}),
        ("boolean-flag sets inlined back into the head",
         GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE,
         GREEN_FASTIO.replace("booleanFlags = fastioBooleanFlags(isTarCommand)",
                              'booleanFlags = if (isTarCommand) setOf("recursive", "force", "ignore-case") '
                              'else setOf("recursive", "force", "ignore-case")'),
         {"F6"}),
        ("flag-set resolver renamed",
         GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE,
         GREEN_FASTIO.replace("internal fun fastioBooleanFlags(isTarCommand: Boolean): Set<String>",
                              "internal fun unusedFlags(): Int = 0"),
         {"F5"}),
        ("expander made private again",
         GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE,
         GREEN_FASTIO.replace("internal fun normalizeTarShorts(argv: List<String>): List<String>",
                              "private fun normalizeTarShorts(argv: List<String>): List<String>"),
         {"F7"}),
    ]
    # [da1026-B] the flag-set arm: (label, output_src, speak_src, want).
    red_cases_flags = [
        ("OUTPUT_FLAGS reverted to the three output-shaping names",
         GREEN_OUTPUT.replace('setOf("compact", "quiet", "q", "help", "h")',
                              'setOf("compact", "quiet", "q")'),
         GREEN_SPEAK, {"O2", "O3"}),
        ("OUTPUT_FLAGS loses only help",
         GREEN_OUTPUT.replace('"q", "help", "h"', '"q", "h"'),
         GREEN_SPEAK, {"O2"}),
        ("OUTPUT_FLAGS loses only h",
         GREEN_OUTPUT.replace('"q", "help", "h"', '"q", "help"'),
         GREEN_SPEAK, {"O3"}),
        ("OUTPUT_FLAGS declaration removed entirely",
         GREEN_OUTPUT.replace("    val OUTPUT_FLAGS: Set<String> = setOf(\"compact\", \"quiet\", \"q\", \"help\", \"h\")\n", ""),
         GREEN_SPEAK, {"O1"}),
        ("a comment naming help/h cannot satisfy the declaration",
         GREEN_OUTPUT.replace(
             '    val OUTPUT_FLAGS: Set<String> = setOf("compact", "quiet", "q", "help", "h")',
             '    // setOf("compact", "quiet", "q", "help", "h")\n'
             '    val OUTPUT_FLAGS: Set<String> = setOf("compact", "quiet", "q")'),
         GREEN_SPEAK, {"O2", "O3"}),
        ("speak handler parses with the no-arg default again",
         GREEN_OUTPUT,
         GREEN_SPEAK.replace(', booleanFlags = setOf("stop", "status")', ""),
         {"S1", "S2"}),
        ("speak declares stop but not status",
         GREEN_OUTPUT,
         GREEN_SPEAK.replace('setOf("stop", "status")', 'setOf("stop")'),
         {"S2"}),
    ]
    failures = 0
    for label, output, speak, want in red_cases_flags:
        if output == GREEN_OUTPUT and speak == GREEN_SPEAK:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_flag_set(output, speak)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    for label, contacts, config, *extra in red_cases:
        if len(extra) == 1:
            browseruse, fastio, want = GREEN_BROWSERUSE, GREEN_FASTIO, extra[0]
        else:
            browseruse, fastio, want = extra
        if (contacts == GREEN_CONTACTS and config == GREEN_CONFIG
                and browseruse == GREEN_BROWSERUSE and fastio == GREEN_FASTIO):
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_text(contacts, config, browseruse, fastio)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [da1028-A] ISSUE-08 arms. Expected rule-id sets are written by hand from
    # DEBUG_RULES — never derived from a run.
    red_cases_debug = [
        ("forwardedArgv falls back to the greedy leftovers everywhere",
         GREEN_DEBUG.replace(
             "): List<String> = if (sub in PASSTHROUGH_SUBCOMMANDS) rawRest else args.positional.drop(1)",
             "): List<String> = args.positional.drop(1)"),
         {"D3"}),
        ("the rest slice is rebuilt from the greedy parse at the call site",
         GREEN_DEBUG.replace("val rest = forwardedArgv(sub, args, rawRest)",
                             "val rest = args.positional.drop(1)"),
         {"D9"}),
        ("the shizuku branch stops forwarding the verbatim tail",
         GREEN_DEBUG.replace('RpcCall("debug.shizuku.exec", buildExecForwarder(rest, args))',
                             'RpcCall("debug.shizuku.exec", buildExecForwarder(args.positional, args))'),
         {"D10", "D14"}),
        ("the model-use branch stops forwarding the verbatim tail",
         GREEN_DEBUG.replace('RpcCall("debug.modelUse.exec", buildExecForwarder(rest, args))',
                             'RpcCall("debug.modelUse.exec", buildExecForwarder(args.positional, args))'),
         {"D11", "D14"}),
        ("the rawRest never reaches buildRpcCall",
         GREEN_DEBUG.replace("buildRpcCall(sub, args, argv.rawRest)", "buildRpcCall(sub, args)"),
         {"D7", "D15"}),
        ("forwardedArgv is called without the verbatim tail",
         GREEN_DEBUG.replace("val rest = forwardedArgv(sub, args, rawRest)",
                             "val rest = forwardedArgv(sub, args)"),
         {"D9", "D16"}),
        ("the exec branch forwards the greedy leftovers",
         GREEN_DEBUG.replace("execCommandLine(rest)", "execCommandLine(args.positional)"),
         {"D12"}),
        ("output shaping reverts to the whole-argv view",
         GREEN_DEBUG.replace(
             "if (sub in PASSTHROUGH_SUBCOMMANDS) OffloadArgs(argv.globals, BOOLEAN_FLAGS) else args",
             "args"),
         {"D13"}),
        ("the structural argv view is dropped from the head",
         GREEN_DEBUG.replace("val argv = splitArgv(raw)",
                             "val argv = DebugArgvSplit(emptyList(), null, emptyList())"),
         {"D5"}),
        ("a fresh greedy parse is handed to the forwarder",
         GREEN_DEBUG.replace('RpcCall("debug.modelUse.exec", buildExecForwarder(rest, args))',
                             'RpcCall("debug.modelUse.exec", buildExecForwarder(OffloadArgs(raw).positional, args))'),
         {"D11", "D17"}),
    ]
    for label, debug_src, want in red_cases_debug:
        if debug_src == GREEN_DEBUG:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_debug(debug_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [da1032-C] each Sessions rule must be winnable by exactly the mutation it
    # pins — the funnel rename, the per-command parse coming back, the guard
    # losing its throw, the hand-written "Valid:" list, the SUBCOMMANDS drift.
    red_cases_sessions = [
        ("the funnel is renamed",
         GREEN_SESSIONS.replace(
             "private fun resolveDateBounds(args: OffloadArgs): Pair<Long?, Long?>",
             "private fun resolveBounds(args: OffloadArgs): Pair<Long?, Long?>"),
         {"J1"}),
        ("a subcommand skips the funnel",
         GREEN_SESSIONS.replace("val (startMs, endMs) = resolveDateBounds(args)",
                                "val (startMs, endMs) = resolveBounds(args)", 1),
         {"J4"}),
        ("--start is parsed outside the funnel",
         GREEN_SESSIONS.replace(
             'val startMs = sessionsDateBound("--start", args.get("start")) { parseSessionsDate(it) }',
             'val startMs = parseDate(args.get("start"))'),
         {"J2", "J12"}),
        ("--end is parsed outside the funnel",
         GREEN_SESSIONS.replace(
             'val endMs = sessionsDateBound("--end", args.get("end")) { parseEndDate(it) }',
             'val endMs = parseEndDate(args.get("end"))'),
         {"J3", "J13"}),
        ("sessionsDateBound is renamed",
         GREEN_SESSIONS.replace(
             "internal fun sessionsDateBound(flag: String, raw: String?, parse: (String) -> Long?): Long?",
             "internal fun bound(flag: String, raw: String?, parse: (String) -> Long?): Long?"),
         {"J5"}),
        ("parseSessionsDate is renamed",
         GREEN_SESSIONS.replace("internal fun parseSessionsDate(raw: String?): Long?",
                                "internal fun parseDate(raw: String?): Long?"),
         {"J6"}),
        ("the guard stops refusing",
         GREEN_SESSIONS.replace("return parse(raw) ?: throw InvalidDateArg(flag, raw)",
                                "return parse(raw)"),
         {"J15"}),
        ("the refusal carrier is renamed",
         GREEN_SESSIONS.replace("internal class InvalidDateArg(flag: String, raw: String) :",
                                "internal class BadDateArg(flag: String, raw: String) :"),
         {"J7"}),
        ("handle stops catching the refusal",
         GREEN_SESSIONS.replace("} catch (e: InvalidDateArg) {", "} catch (e: Exception) {"),
         {"J8"}),
        ("the declared list drops send",
         GREEN_SESSIONS.replace(
             'private val SUBCOMMANDS = listOf("list", "search", "messages", "send")',
             'private val SUBCOMMANDS = listOf("list", "search", "messages")'),
         {"J9"}),
        ("the Valid: list is hand-written again",
         GREEN_SESSIONS.replace('Valid: ${SUBCOMMANDS.joinToString(", ")}',
                                "Valid: list, search, messages."),
         {"J10", "J11"}),
        ("the dispatch table loses send",
         GREEN_SESSIONS.replace('"send" -> cmdSend(args)', '"send" -> doNothing(args)'),
         {"J14"}),
    ]
    for label, sessions_src, want in red_cases_sessions:
        if sessions_src == GREEN_SESSIONS:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_sessions(sessions_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [dv15-A1 A-01] K9 tooth — the config parser must be BUILT with the
    # value-taking short options; expectation written by hand from CONFIG_RULES.
    for label, config_src, want in [
        ("config value short options dropped",
         GREEN_CONFIG.replace('valueShortOptions = setOf("f", "p", "s")',
                              "valueShortOptions = emptySet()"),
         {"K9"}),
    ]:
        if config_src == GREEN_CONFIG:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_text(
            GREEN_CONTACTS, config_src, GREEN_BROWSERUSE, GREEN_FASTIO)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [dv15-A1 A-08] J16–J20 teeth — the inverted window must exist, be the ONLY
    # comparison (an `<=` would call an equal window inverted), be reached from
    # the shared bounds helper, and refuse through its own exception type.
    for label, sessions_src, want in [
        ("date window helper renamed",
         GREEN_SESSIONS.replace("internal fun sessionsDateWindow(",
                                "internal fun unusedWindow("),
         {"J16"}),
        ("inverted window compared with <=",
         GREEN_SESSIONS.replace("endMs < startMs", "endMs <= startMs"),
         {"J17", "J18"}),
        ("window refused with a foreign exception",
         GREEN_SESSIONS.replace(
             "throw InvertedDateWindow(startRaw.orEmpty(), endRaw.orEmpty())",
             'throw IllegalArgumentException("bad window")'),
         {"J20"}),
        ("bounds bypass the window",
         GREEN_SESSIONS.replace(
             'return sessionsDateWindow(startMs, endMs, args.get("start"), args.get("end"))',
             "return startMs to endMs"),
         {"J19"}),
        # [dv19-A1 A-02] `--wait` teeth: the declaration must carry it (and must
        # not swallow the value option), and the flag must be documented.
        ("--wait dropped from the boolean declaration",
         GREEN_SESSIONS.replace('setOf("full", "async", "wait", "help", "h")',
                                'setOf("full", "async", "help", "h")'),
         {"J21"}),
        ("the value option --wait-timeout declared boolean",
         GREEN_SESSIONS.replace('setOf("full", "async", "wait", "help", "h")',
                                'setOf("full", "async", "wait", "wait-timeout", "help", "h")'),
         {"J21"}),
        ("the --wait help line is dropped",
         GREEN_SESSIONS.replace(
             "  --wait                (send only) Block until the reply completes "
             "(default; --async wins)\n", ""),
         {"J22"}),
    ]:
        if sessions_src == GREEN_SESSIONS:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_sessions(sessions_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [deep-audit-1044 A-04] S1–S7 teeth — the send status → exit-code contract.
    # Expectations are written by hand from the S rule table, never derived from
    # a run. The pre-fix shape is the first arm: `cmdSend` catches exceptions and
    # then emits, so the status is never consulted (S2) and nothing returns early
    # with a nonzero code (S4); S1/S3 stay green because the payload builder does
    # read the status and the catch branch does call `errorEnvelope`.
    for label, send_src, want in [
        ("the status read is dropped",
         GREEN_SESSIONS_SEND.replace('val status = result.optString("status")',
                                    'val status = ""'),
         {"S1"}),
        # Consulting the set and placing it BEFORE the payload are one decision:
        # an `if (false)` stub keeps the payload unconditional, so both rules fire.
        ("the success set is never consulted",
         GREEN_SESSIONS_SEND.replace("if (!sendSucceeded(status)) {", "if (false) {"),
         {"S2", "S5"}),
        # [wide-1024] the same site, one token different: the call is still there,
        # the condition is folded false, the refusal never happens.
        ("the success check is dead-collapsed (`false && !sendSucceeded(status)`)",
         GREEN_SESSIONS_SEND.replace("if (!sendSucceeded(status)) {",
                                     "if (false && !sendSucceeded(status)) {"),
         {"S2"}),
        ("the refusal branch falls through instead of returning",
         GREEN_SESSIONS_SEND.replace(
             "            return NativeOffloadResult(\n                sendExitFor(status),",
             "            NativeOffloadResult(\n                sendExitFor(status),"),
         {"S2"}),
        ("the refusal skips the shared error funnel",
         GREEN_SESSIONS_SEND.replace('val err = errorEnvelope(', 'val err = errorShape('),
         {"S3"}),
        ("the refusal still exits 0",
         GREEN_SESSIONS_SEND.replace("sendExitFor(status),", "0,"),
         {"S4"}),
        ("emit runs before the refusal branch",
         GREEN_SESSIONS_SEND.replace("        if (!sendSucceeded(status)) {",
                                     '        emit("send", data, args)\n'
                                     "        if (!sendSucceeded(status)) {"),
         {"S5"}),
        ("an unknown status counts as a delivery",
         GREEN_SESSIONS_SEND.replace(
             'status == "Completed" || status == "Running"',
             'status == "Completed" || status == "Running" || status == ""'),
         {"S6"}),
        ("Completed is no longer a delivery",
         GREEN_SESSIONS_SEND.replace(
             'status == "Completed" || status == "Running"', 'status == "Running"'),
         {"S6"}),
        ("the refusal hard-codes its exit code",
         GREEN_SESSIONS_SEND.replace("sendExitFor(status),", "1,"),
         {"S4"}),
        ("the exit helper is deleted",
         GREEN_SESSIONS_SEND.replace(
             "        internal fun sendExitFor(status: String): Int =\n"
             "            if (sendSucceeded(status)) 0 else EXIT_FAILURE\n", ""),
         {"S7"}),
        ("the reason rides along under response_text",
         GREEN_SESSIONS_SEND.replace(
             '            val detail = JSONObject()\n'
             '                .put("session_id", data.optString("session_id"))\n'
             '                .put("status", status)\n',
             '            val detail = JSONObject()\n'
             '                .put("session_id", data.optString("session_id"))\n'
             '                .put("response_text", data.optString("response_text"))\n'
             '                .put("status", status)\n'),
         {"S8"}),
        ("the failure exit code becomes 0",
         GREEN_SESSIONS_SEND.replace("private const val EXIT_FAILURE = 1",
                                     "private const val EXIT_FAILURE = 0"),
         {"S7"}),
    ]:
        if send_src == GREEN_SESSIONS_SEND:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_sessions_send_status(send_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1

    # Expectations are written by hand from ARGS_RULES, never derived from a run.
    for label, args_src, want in [
        ("value token helper gone",
         GREEN_ARGS.replace("private fun valueTokenOrNull(token: String?): String? =",
                            "private fun unusedTokenHelper(token: String?): String? ="),
         {"A1"}),
        ("a flag is accepted as its own value",
         GREEN_ARGS.replace(
             'token?.takeIf { !it.startsWith("-") || isNegativeNumberToken(it) }', "token"),
         {"A2"}),
        ("negative-number guard gone",
         GREEN_ARGS.replace("private fun isNegativeNumberToken(token: String): Boolean =",
                            "private fun unusedNegativeGuard(token: String): Boolean ="),
         {"A3"}),
        ("digit look-ahead dropped",
         GREEN_ARGS.replace("token.length > 1 && token[0] == '-' && token[1].isDigit()",
                            "token.length > 1 && token[0] == '-'"),
         {"A4"}),
        ("value short options not declared",
         GREEN_ARGS.replace("private val valueShortOptions: Set<String> = emptySet(),",
                            "private val otherShortOptions: Set<String> = emptySet(),"),
         {"A5"}),
        ("boolean flags win over the value declaration",
         GREEN_ARGS.replace(
             "if (name in valueShortOptions && name !in effectiveBooleanFlags && value != null) {",
             "if (name in valueShortOptions && value != null) {"),
         {"A6"}),
        ("value lookup never recorded",
         GREEN_ARGS.replace("vs[name] = value", "vs.put(name, value)"),
         {"A7"}),
        ("dashed token joins the positionals",
         GREEN_ARGS + '\n    val probe = a.startsWith("-") && a.length > 1 -> fs.add(a)',
         {"A8"}),
        ("single-dash branch loses its negative-number exception",
         GREEN_ARGS.replace(
             'a.startsWith("-") && a.length > 1 &&\n'
             '                (!isNegativeNumberToken(a) || a.substring(1) in effectiveBooleanFlags) -> {',
             'a.startsWith("-") && a.length > 1 -> {'),
         {"A9"}),
        ("single-dash branch renamed away",
         GREEN_ARGS.replace('a.startsWith("-") && a.length > 1', 'a.startsWith("-") && a.size > 1'),
         {"A9"}),
    ]:
        if args_src == GREEN_ARGS:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_args(args_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [da1033-D] ISSUE-04 — each payload spelling has a tooth: the prose return
    # (N2), the prose first argument in a one-line / multi-line / raw-string
    # call (N1), and the unterminated literal (fail-closed, not a silent skip).
    red_cases_payload = [
        ("photos payload reverts to the English sentence",
         GREEN_PAYLOAD_PHOTOS.replace('return data.toString(2)',
                                      'return "No photos found within ${radiusKm}km of ($lat, $lon).$hint"'),
         GREEN_PAYLOAD_CONTACTS, {"N2"}),
        ("prose behind an interpolation is still prose",
         GREEN_PAYLOAD_PHOTOS,
         GREEN_PAYLOAD_CONTACTS.replace("formatBody(searchArr.toString(2), parsedArgs)",
                                        'formatBody("Failed to query contacts for ${name}.", parsedArgs)'),
         {"N1"}),
        ("the near call site inlines a prose literal",
         GREEN_PAYLOAD_PHOTOS.replace("formatBody(nearPhotos(1.0, 2.0, 1.0, 20), args)",
                                      'formatBody("No photos found within 1km of (1.0, 2.0).", args)'),
         GREEN_PAYLOAD_CONTACTS, {"N1"}),
        ("the raw-string prose spelling is caught too",
         GREEN_PAYLOAD_PHOTOS.replace("formatBody(nearPhotos(1.0, 2.0, 1.0, 20), args)",
                                      "formatBody(" + '"' * 3 + "No photos found within 1km." + '"' * 3 + ", args)"),
         GREEN_PAYLOAD_CONTACTS, {"N1"}),
        # [batch6-t01] 共享词法器把"未闭合字面量"判在**整份源**上：一条 LEX
        # 违规（fail-closed），而不是旧实现里"这一处跳过、其余照判"。语义更强：
        # 读不出结构的源码不是合格源码。
        ("an unterminated literal fails closed",
         GREEN_PAYLOAD_PHOTOS.replace("formatBody(nearPhotos(1.0, 2.0, 1.0, 20), args)",
                                      'formatBody("No photos found within 1km.\n, args)'),
         GREEN_PAYLOAD_CONTACTS, {"LEX"}),
        ("contacts no-match reverts to prose",
         GREEN_PAYLOAD_PHOTOS,
         GREEN_PAYLOAD_CONTACTS.replace("formatBody(searchArr.toString(2), parsedArgs)",
                                        'formatBody("No contacts found matching \'q\'.", parsedArgs)'),
         {"N1"}),
        ("contacts not-found reverts to the bare id sentence",
         GREEN_PAYLOAD_PHOTOS,
         GREEN_PAYLOAD_CONTACTS.replace("formatBody(body, parsedArgs)",
                                        'formatBody("android-contacts: no contact with id=$id", parsedArgs)'),
         {"N1"}),
        ("the multi-line query-failure call inlines the sentence",
         GREEN_PAYLOAD_PHOTOS,
         GREEN_PAYLOAD_CONTACTS.replace('queryFailedBody("Failed to query contacts.")',
                                        '"Failed to query contacts."'),
         {"N1"}),
    ]
    for label, photos_src, contacts_src, want in red_cases_payload:
        if photos_src == GREEN_PAYLOAD_PHOTOS and contacts_src == GREEN_PAYLOAD_CONTACTS:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_offload_json(
            ("PhotosOffloadHandler.kt", photos_src),
            ("ContactsOffloadHandler.kt", contacts_src))}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [da1034-A4] ISSUE-08 — the rule set grew from 2 files to 8, so the arms
    # below are the teeth for the six newly covered ones. One mutation per
    # spelling the real tree shipped (the bare sentence at the call site, the
    # sentence behind an interpolation, the prose return of a payload helper)
    # and one per file, each against its own green control above.
    red_cases_payload_a4 = [
        ("the location uncaught branch reverts to the sentence",
         GREEN_PAYLOAD_LOCATION.replace(
             "OffloadOutput.formatBody(body, args)",
             'OffloadOutput.formatBody("android-location: unknown error", args)'),
         {"N1"}),
        ("the reverse geocode reverts to the English sentence",
         GREEN_PAYLOAD_LOCATION.replace(
             "OffloadOutput.formatBody(reverseGeocodeJson(lat, lon, lines).toString(2), args)",
             'OffloadOutput.formatBody("No address found for ($lat, $lon).", args)'),
         {"N1"}),
        ("the reverse geocode helper returns prose",
         GREEN_PAYLOAD_LOCATION.replace('return JSONObject()\n            .put("latitude", lat)',
                                        'return "No address found for ($lat, $lon)."\n        // .put("latitude", lat)'),
         {"N2"}),
        ("the expression-built address lines are left alone (rule boundary)",
         GREEN_PAYLOAD_LOCATION.replace(
             "OffloadOutput.formatBody(reverseGeocodeJson(lat, lon, lines).toString(2), args)",
             'OffloadOutput.formatBody(lines.joinToString("\\n"), args)'),
         set()),
        ("the calendar list payload reverts to the prose sentence",
         GREEN_PAYLOAD_CALENDAR.replace(
             "OffloadOutput.formatBody(data.toString(2), args)",
             'OffloadOutput.formatBody("android-calendar: no writable calendar", args)'),
         {"N1"}),
        ("the clipboard set reverts to the prose sentence",
         GREEN_PAYLOAD_SIDE_EFFECTS.replace(
             "OffloadOutput.formatBody(setData.toString(2), args)",
             'OffloadOutput.formatBody("Copied 5 characters to clipboard.", args)'),
         {"N1"}),
        ("the clipboard clear reverts to the prose sentence",
         GREEN_PAYLOAD_SIDE_EFFECTS.replace(
             "OffloadOutput.formatBody(clearData.toString(2), args)",
             'OffloadOutput.formatBody("Clipboard cleared.", args)'),
         {"N1"}),
        ("the open success reverts to the Opened: sentence",
         GREEN_PAYLOAD_SIDE_EFFECTS.replace(
             "OffloadOutput.formatBody(openData.toString(2), args)",
             'OffloadOutput.formatBody("Opened: $url", args)'),
         {"N1"}),
        ("the notification clear reverts to the prose sentence",
         GREEN_PAYLOAD_SIDE_EFFECTS.replace(
             "OffloadOutput.formatBody(notifyData.toString(2), args)",
             'OffloadOutput.formatBody("All notifications cleared.", args)'),
         {"N1"}),
        ("a non-payload helper is handed to formatBody and its prose return is caught",
         GREEN_PAYLOAD_SIDE_EFFECTS.replace(
             "OffloadOutput.formatBody(openData.toString(2), args)",
             "OffloadOutput.formatBody(localSummary(), args)").replace(
             "    private fun openUrl",
             '    private fun localSummary(): String {\n'
             '        return "clipboard: ok"\n'
             '    }\n\n'
             "    private fun openUrl"),
         {"N2"}),
        ("the fastio usage reverts to the inline prose payload",
         GREEN_PAYLOAD_FASTIO_USAGE.replace(
             "OffloadOutput.formatBody(usageText(reason), args)",
             'OffloadOutput.formatBody("minis-fastio: " + reason + "\\n" + HELP, args)'),
         {"N1"}),
        ("the usage payload helper reverts to a prose return",
         GREEN_PAYLOAD_FASTIO_USAGE.replace(
             "    private fun usageText(reason: String): String = JSONObject()\n",
             '    private fun usageText(reason: String): String {\n'
             '        return "minis-fastio: " + reason\n'
             '    }\n\n'
             '    private fun unusedText(reason: String): String = JSONObject()\n'),
         {"N2"}),
    ]
    for label, src, want in red_cases_payload_a4:
        if src in (GREEN_PAYLOAD_LOCATION, GREEN_PAYLOAD_SIDE_EFFECTS,
                   GREEN_PAYLOAD_FASTIO_USAGE, GREEN_PAYLOAD_CALENDAR):
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_offload_json(("fixture.kt", src))}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [dv14-A1] one arm per spelling the three defects shipped (ISSUE-01/02/04),
    # each against its own green control: the dead single-dash alias read, the
    # resolver bypassed, the alias never bound, the parsed `--after` read, the
    # owner function renamed away, the refusal guard deleted, and the raw-argv
    # scan knowing too small a boolean set (both halves of it).
    red_cases_bindings = [
        ("contacts: doSearch reverts to the dead alias read",
         GREEN_CONTACTS.replace("val max = resolvePageSize(parsedArgs, 20)",
                                'val max = parsedArgs.getInt("max", "n") ?: 20'),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1a", "V1b"}),
        ("contacts: doSearch stops reusing the resolver",
         GREEN_CONTACTS.replace("val max = resolvePageSize(parsedArgs, 20)", "val max = 20"),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1b"}),
        ("contacts: the -n alias stops being bound before the parse",
         GREEN_CONTACTS.replace("OffloadArgs(bindLegacyShortMax(argv), booleanFlags",
                                "OffloadArgs(argv, booleanFlags"),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1c"}),
        ("contacts: the bind helper is renamed",
         GREEN_CONTACTS.replace(
             "internal fun bindLegacyShortMax(argv: List<String>): List<String> {",
             "internal fun unusedBinder(argv: List<String>): List<String> {"),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1d"}),
        ("notification: handleSend goes back to the parsed read",
         GREEN_CONTACTS,
         GREEN_NOTIFICATION_HANDLE.replace(
             "val plan = sendDisposition(sub, args.get(\"after\"), args.get(\"at\"))",
             'val plan = args.getInt("after")'),
         GREEN_FASTIO, {"V2a", "V2b"}),
        ("notification: handleSend stops consulting the disposition",
         GREEN_CONTACTS,
         GREEN_NOTIFICATION_HANDLE.replace(
             "val plan = sendDisposition(sub, args.get(\"after\"), args.get(\"at\"))",
             "val plan = SendDisposition.Immediate"),
         GREEN_FASTIO, {"V2b"}),
        ("notification: the disposition helper is renamed",
         GREEN_CONTACTS,
         GREEN_NOTIFICATION_HANDLE.replace("internal fun sendDisposition(sub: String,",
                                           "internal fun unusedDisposition(sub: String,"),
         GREEN_FASTIO, {"V2c"}),
        ("notification: the required-argument refusal is deleted",
         GREEN_CONTACTS,
         GREEN_NOTIFICATION_HANDLE.replace(
             "android-notification: --after or --at is required\\n",
             "android-notification: nothing to schedule\\n"),
         GREEN_FASTIO, {"V2d"}),
        ("fastio: the scan forgets the boolean flag set",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace(
             "val noValue = OffloadOutput.OUTPUT_FLAGS + fastioBooleanFlags(isTarCommand = true)",
             "val noValue = OffloadOutput.OUTPUT_FLAGS"),
         {"V3a"}),
        ("fastio: the scan forgets the global output flags",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace(
             "val noValue = OffloadOutput.OUTPUT_FLAGS + fastioBooleanFlags(isTarCommand = true)",
             "val noValue = fastioBooleanFlags(isTarCommand = true)"),
         {"V3b"}),
        ("fastio: the scan becomes an expression body (fail-closed)",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace(
             "internal fun firstSubcommandToken(rawArgv: List<String>): String? {",
             "internal fun firstSubcommandToken(rawArgv: List<String>): String? = rawArgv.firstOrNull {"),
         {"V3c"}),
        ("a comment cannot mask the boolean-flag reference",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace(
             "val noValue = OffloadOutput.OUTPUT_FLAGS + fastioBooleanFlags(isTarCommand = true)",
             "// val noValue = Output.OUTPUT_FLAGS + fastioBooleanFlags(true)"),
         {"V3a", "V3b"}),
        # [dv18-A A-01b] the value-short consumption arms — a pre-scan that
        # drops the declared set (or stops consuming values) mis-reads
        # `-C /tmp` and loses the tar verbs + `--directory` expansion.
        ("fastio: the value-short set is undeclared",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace('private val SHORT_VALUE_OPTIONS = setOf("-C")',
                              'private val UNUSED_SHORTS = setOf("-C")'),
         {"V3d"}),
        ("fastio: the scan stops consulting the value-short set",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace("token in SHORT_VALUE_OPTIONS -> true", "false -> true"),
         {"V3e"}),
        ("fastio: the scan stops consuming values",
         GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE,
         GREEN_FASTIO.replace("i += if (consumesValue) 2 else 1", "i += 1"),
         {"V3f"}),
        # [dv19-A1 A-01] the subcommand probe must read the REWRITTEN list: on the
        # raw argv `-n 10` is still unbound, so "10" took positional[0] and
        # `-n 10 list` refused with `unknown subcommand '10'` (exit 2).
        ("contacts: the subcommand probe reads the raw argv again",
         GREEN_CONTACTS.replace("OffloadArgs(rewritten, booleanFlags",
                                "OffloadArgs(argv, booleanFlags"),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1e"}),
        ("contacts: the --max rewrite moves behind the subcommand probe",
         GREEN_CONTACTS.replace(
             'val rewritten = ArrayList<String>(argv.size)\n            rewritten.add("--max")',
             'val rewritten = ArrayList<String>(argv.size)').replace(
             '            if (sub != "list" && sub != "search") return argv\n            return rewritten',
             '            if (sub != "list" && sub != "search") return argv\n'
             '            rewritten.add("--max")\n            return rewritten'),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1f"}),
        ("contacts: the probed list is built but not the one returned",
         GREEN_CONTACTS.replace("            return rewritten\n", "            return argv\n"),
         GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO, {"V1g"}),
    ]
    # [dv29-A3 A-05] the shared page-size contract teeth. Expected id sets are
    # written by hand from the PG table (never derived from a run): one arm per
    # way a refused page size can come back, plus the fail-closed derivations.
    def _page_mut(*edits, drop=()):
        out = {k: v for k, v in GREEN_PAGE_SOURCES.items() if k not in drop}
        for name, old, new in edits:
            assert old in out[name], f"page-size mutation missed {name}: {old[:40]}"
            out[name] = out[name].replace(old, new, 1)
        return out

    red_cases_page = [
        ("page size: the raw value is parsed with toInt (non-numeric collapses)",
         _page_mut(
             ("OffloadPageSize.kt",
              'val value = raw.toIntOrNull()\n'
              '                ?: return Invalid("$spelled expects ${surface.range}, got \'$raw\'")',
              'val value = raw.toInt()'),
         ),
         {"PG3"}),
        ("page size: a bare --max falls through to the default",
         _page_mut(
             ("OffloadPageSize.kt",
              'if (args.hasFlag(name)) return Invalid("--$name expects ${surface.range}")\n',
              ''),
         ),
         {"PG3"}),
        ("page size: the positive lower bound is dropped",
         _page_mut(("OffloadPageSize.kt", "value < 1 ||", "value < 0 ||")),
         {"PG3"}),
        ("page size: the declared upper bound is never compared",
         _page_mut(("OffloadPageSize.kt", "(surface.max != null && value > surface.max)", "false")),
         {"PG3"}),
        ("page size: a second path answers the default",
         _page_mut(
             ("OffloadPageSize.kt",
              "return winner ?: Ok(default)",
              "if (winner == null) return Ok(default)\n            return Ok(default)"),
         ),
         {"PG3"}),
        ("page size: a registered handler goes back to getInt ?: default",
         _page_mut(
             ("CalendarOffloadHandler.kt",
              'val limit = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.CALENDAR)) {\n'
              '            is PageSizeOutcome.Ok -> size.value\n'
              '            is PageSizeOutcome.Invalid ->\n'
              '                return NativeOffloadResult(2, "android-calendar list: ${size.message}")\n'
              '        }',
              'val limit = args.getInt("limit") ?: 50'),
         ),
         {"PG1", "PG2"}),
        ("page size: a registered handler clamps the value it resolved",
         _page_mut(
             ("CalendarOffloadHandler.kt",
              'return NativeOffloadResult(0, "$limit")',
              'val capped = limit.coerceIn(1, 100)\n'
              '        return NativeOffloadResult(0, "$capped")'),
         ),
         {"PG2"}),
        ("page size: a collector ignores the refusal its face returns",
         _page_mut(
             ("ContactsOffloadHandler.kt",
              'is PageSize.Invalid -> return pageSizeRefusal("list", size.message)',
              'is PageSize.Invalid -> 50'),
         ),
         {"PG4"}),
        ("page size: the surviving -n is refused on every surface, not just the alias one",
         _page_mut(
             ("OffloadPageSize.kt",
              'if (surface.legacyShortMax && args.hasFlag("n")) {\n'
              '                return Invalid("-n is not a page size")\n'
              '            }',
              'if (args.hasFlag("n")) {\n'
              '                return Invalid("-n is not a page size")\n'
              '            }'),
         ),
         {"PG5"}),
        ("page size: the surviving -n is not refused at all",
         _page_mut(
             ("OffloadPageSize.kt",
              'if (surface.legacyShortMax && args.hasFlag("n")) {\n'
              '                return Invalid("-n is not a page size")\n'
              '            }\n',
              ''),
         ),
         {"PG5"}),
        ("page size: a declared surface is missing from SURFACES",
         _page_mut(("OffloadPageSize.kt", "val SURFACES = listOf(CONTACTS, CALENDAR)",
                    "val SURFACES = listOf(CONTACTS)")),
         {"PG0"}),
        ("page size: a registered handler stops binding the legacy -n alias",
         _page_mut(
             ("ContactsOffloadHandler.kt",
              "internal fun bindLegacyShortMax(argv: List<String>): List<String> = argv",
              "internal fun bindShortMax(argv: List<String>): List<String> = argv"),
         ),
         {"PG5"}),
        ("page size: a registered surface names a handler that is not there",
         _page_mut(drop=("CalendarOffloadHandler.kt",)),
         {"PG0"}),
    ]
    for label, page_sources, want in red_cases_page:
        if page_sources == GREEN_PAGE_SOURCES:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_offload_page_size(page_sources)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # green control for the shared contract
    if _page_clean := check_offload_page_size(GREEN_PAGE_SOURCES):
        print(f"SELFTEST-FAIL: page-size green fixture is not clean: {describe(_page_clean)}")
        failures += 1

    for label, contacts_src, notif_src, fastio_src, want in red_cases_bindings:
        if contacts_src == GREEN_CONTACTS and notif_src == GREEN_NOTIFICATION_HANDLE \
                and fastio_src == GREEN_FASTIO:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_offload_cli_bindings(contacts_src, notif_src, fastio_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [dv18-A A-03] the photos --album classification arms. Expected sets are
    # hand-written from the P-table (never derived from a run).
    red_cases_album = [
        ("photos: the album classifier is gone",
         GREEN_ALBUM_PHOTOS.replace("internal fun resolveAlbumSelector(",
                                    "internal fun unusedSelector("),
         {"P1"}),
        ("photos: the refusal is dropped",
         GREEN_ALBUM_PHOTOS.replace(
             "return NativeOffloadResult(2, albumSelectorRefusal(selector))",
             "return ok(emptyList())"),
         {"P2"}),
        ("photos: the classifier stops classifying",
         GREEN_ALBUM_PHOTOS.replace("val id = rawId.toLongOrNull()", "val id = rawId.toLong()"),
         {"P3"}),
        ("photos: handleAlbums is gone",
         GREEN_ALBUM_PHOTOS.replace("fun handleAlbums(", "fun unusedAlbums("),
         {"P4"}),
    ]
    for label, photos_src, want in red_cases_album:
        if photos_src == GREEN_ALBUM_PHOTOS:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_photos_album(photos_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [dv22-A6 A-06] the clipboard clear arms. Expectations hand-written from
    # the L-table: one arm per way the fix can be undone, plus the baseline
    # (pre-fix) shape and the fail-closed arm for a lost owner function.
    CLEAR_WHEN_BLOCK = (
        "        when (clearAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {\n"
        "            ClipboardClearAction.PlatformClear -> cm.clearPrimaryClip()\n"
        "            ClipboardClearAction.EmptyStringFallback -> cm.setPrimaryClip(ClipData.newPlainText(\"\", \"\"))\n"
        "        }\n"
    )
    DO_CLEAR_GUARD = (
        "    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {\n"
        "        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {\n"
    )
    red_cases_clipboard = [
        ("clipboard: clear reverts to the empty-string write (baseline defect)",
         GREEN_CLIPBOARD_CLEAR.replace(CLEAR_WHEN_BLOCK,
                                       '        cm.setPrimaryClip(ClipData.newPlainText("", ""))\n'),
         {"L2"}),
        ("clipboard: doClear's guard drops the `&& SDK_INT >= Q` half (baseline defect)",
         GREEN_CLIPBOARD_CLEAR.replace(DO_CLEAR_GUARD,
                                       "    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {\n"
                                       "        if (!isAppForeground()) {\n"),
         {"L3"}),
        # [wide-1024] the platform call stays in the tree while the decision is
        # pinned to a literal: API 29+ never reaches clearPrimaryClip().
        ("clipboard: clearAction is fed a constant instead of the SDK predicate",
         GREEN_CLIPBOARD_CLEAR.replace(
             "clearAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)",
             "clearAction(false)"),
         {"L2"}),
        ("clipboard: always clearPrimaryClip — the pre-Q fallback is deleted",
         GREEN_CLIPBOARD_CLEAR.replace(CLEAR_WHEN_BLOCK, "        cm.clearPrimaryClip()\n"),
         {"L4"}),
        ("clipboard: both writes run unconditionally (clear-then-set puts the item back)",
         GREEN_CLIPBOARD_CLEAR.replace(
             CLEAR_WHEN_BLOCK,
             '        cm.clearPrimaryClip()\n'
             '        cm.setPrimaryClip(ClipData.newPlainText("", ""))\n'),
         {"L4"}),
        ("clipboard: the whole pre-fix shape",
         GREEN_CLIPBOARD_CLEAR
         .replace(CLEAR_WHEN_BLOCK, '        cm.setPrimaryClip(ClipData.newPlainText("", ""))\n')
         .replace(DO_CLEAR_GUARD,
                  "    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {\n"
                  "        if (!isAppForeground()) {\n"),
         {"L2", "L3"}),
        ("fail-closed: doClear lost its owner body",
         GREEN_CLIPBOARD_CLEAR.replace("private fun doClear(", "private fun unusedClear("),
         {"L1"}),
    ]
    for label, clipboard_src, want in red_cases_clipboard:
        if clipboard_src == GREEN_CLIPBOARD_CLEAR:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_clipboard_clear(clipboard_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # [dv19-A1 A-03] the grep budget teeth. Expectations hand-written from
    # check_fastio_search's S-table: one arm per moment a walk can spend the
    # caller's single cross-root budget, plus a fail-closed arm proving the rules
    # are not vacuous on a file that merely lost the walk.
    for label, search_src, want in [
        ("grep entry: a spent budget walks anyway",
         GREEN_FASTIO_SEARCH.replace(
             "    if (limit <= 0) {\n        out.matchesCapped = true\n        return\n    }\n", ""),
         {"S1"}),
        ("grep entry: the short circuit forgets to call it capped",
         GREEN_FASTIO_SEARCH.replace(
             "        out.matchesCapped = true\n        return\n", "        return\n"),
         {"S1"}),
        # [wide-1024] both substrings survive; the guard is dead / falls through.
        ("grep entry: the short circuit is dead-collapsed (`limit <= 0 && false`)",
         GREEN_FASTIO_SEARCH.replace(
             "    if (limit <= 0) {\n        out.matchesCapped = true\n        return\n    }",
             "    if (limit <= 0 && false) {\n        out.matchesCapped = true\n    }"),
         {"S1"}),
        ("grep entry: the guard sets the flag and falls into the walk",
         GREEN_FASTIO_SEARCH.replace(
             "        out.matchesCapped = true\n        return\n",
             "        out.matchesCapped = true\n"),
         {"S1"}),
        ("grep walk: a directory is entered after the budget is spent",
         GREEN_FASTIO_SEARCH.replace(
             "                    if (out.matchesCapped) return FileVisitResult.TERMINATE\n"
             "                    if (charge(out, maxEntries)) return FileVisitResult.TERMINATE",
             "                    if (charge(out, maxEntries)) return FileVisitResult.TERMINATE"),
         {"S2"}),
        ("grep walk: the file that filled the budget keeps walking",
         GREEN_FASTIO_SEARCH.replace(
             "                    return if (out.matchesCapped) {\n"
             "                        FileVisitResult.TERMINATE\n"
             "                    } else {\n"
             "                        FileVisitResult.CONTINUE\n"
             "                    }",
             "                    return FileVisitResult.CONTINUE"),
         {"S3a", "S3b"}),
        ("fail-closed: the grep engine lost its walk",
         GREEN_FASTIO_SEARCH.replace("Files.walkFileTree(", "unusedWalk("),
         {"S1"}),
    ]:
        if search_src == GREEN_FASTIO_SEARCH:
            print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
            failures += 1
            continue
        got = {rid for rid, _, _ in check_fastio_search(search_src)}
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    # the green arms must be fully clean, or every rule is vacuous
    clean = check_text(GREEN_CONTACTS, GREEN_CONFIG, GREEN_BROWSERUSE, GREEN_FASTIO)
    clean_flags = check_flag_set(GREEN_OUTPUT, GREEN_SPEAK)
    clean_debug = check_debug(GREEN_DEBUG)
    clean_sessions = check_sessions(GREEN_SESSIONS)
    clean_send_status = check_sessions_send_status(GREEN_SESSIONS_SEND)
    clean_args = check_args(GREEN_ARGS)
    clean_album = check_photos_album(GREEN_ALBUM_PHOTOS)
    clean_clipboard = check_clipboard_clear(GREEN_CLIPBOARD_CLEAR)
    clean_bindings = check_offload_cli_bindings(
        GREEN_CONTACTS, GREEN_NOTIFICATION_HANDLE, GREEN_FASTIO)
    clean_search = check_fastio_search(GREEN_FASTIO_SEARCH)
    # [R3-t14 #151/#153] the two argv normalizers, judged as decisions.
    clean_norm = check_fastio_normalizers(GREEN_FASTIO)
    clean_payload = check_offload_json(
        ("PhotosOffloadHandler.kt", GREEN_PAYLOAD_PHOTOS),
        ("ContactsOffloadHandler.kt", GREEN_PAYLOAD_CONTACTS),
        ("CalendarOffloadHandler.kt", GREEN_PAYLOAD_CALENDAR),
        ("LocationOffloadHandler.kt", GREEN_PAYLOAD_LOCATION),
        ("ClipboardOffloadHandler.kt", GREEN_PAYLOAD_SIDE_EFFECTS),
        ("NotificationOffloadHandler.kt", GREEN_PAYLOAD_SIDE_EFFECTS),
        ("OpenOffloadHandler.kt", GREEN_PAYLOAD_SIDE_EFFECTS),
        ("FastioOffloadHandler.kt", GREEN_PAYLOAD_FASTIO_USAGE),
    )
    # [dv25-A4 A-07] payload provenance across files (Z1-Z4). Expected sets are
    # written by hand from the rule table, never derived from the run.
    _prov_sources = {
        "PlayerOffloadHandler.kt": GREEN_PLAYER_HANDLER,
        "MediaPlayerManager.kt": GREEN_PLAYER_MANAGER,
        "PlayerOutcome.kt": GREEN_PLAYER_OUTCOME,
        "WeatherOffloadHandler.kt": GREEN_WEATHER_HANDLER,
        "WeatherManager.kt": GREEN_WEATHER_MANAGER,
    }
    _prov_prose = dict(_prov_sources)
    _prov_prose["PlayerOffloadHandler.kt"] = PROSE_PLAYER_HANDLER
    _prov_prose["MediaPlayerManager.kt"] = PROSE_PLAYER_MANAGER
    _prov_exempt = {"WeatherOffloadHandler.kt": "P2 registered, out of this branch"}
    clean_provenance = check_payload_delegates(_prov_sources, [PLAYER], _prov_exempt)
    if clean_provenance:
        print(f"SELFTEST-FAIL: provenance green arm is not clean: {describe(clean_provenance)}")
        failures += 1
    for label, _src, _payload, _exempt, _want in [
        ("provenance: a payload file keeps the manager's sentence",
         _prov_prose, [PLAYER], _prov_exempt, {"Z2"}),
        ("provenance: a delegating handler is missing from the whitelist",
         _prov_sources, [], _prov_exempt, {"Z1"}),
        ("provenance: the exemption list lost an entry",
         _prov_sources, [PLAYER], {}, {"Z1"}),
        ("provenance: an exemption outlived its defect",
         _prov_sources, [PLAYER],
         dict(_prov_exempt, **{"PhotosOffloadHandler.kt": "stale"}), {"Z3"}),
        ("provenance: an exemption carries no reason",
         _prov_sources, [PLAYER], {"WeatherOffloadHandler.kt": "   "}, {"Z3"}),
        ("provenance: the derivation lost the tree",
         {"Other.kt": "class Other { fun f(): Int = 1 }"}, [PLAYER], {}, {"Z4"}),
    ]:
        _got = {rid for rid, _, _ in check_payload_delegates(_src, _payload, _exempt)}
        if _got != _want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(_got)}, want {sorted(_want)}")
            failures += 1

    # [dv27-A5 A-07] help / bare-invocation exit-code arms. Expected rule-id
    # sets are written by hand from the H1/H2/H3 table, never derived from a
    # run; the fixture carries every accepted spelling at once (conditional on
    # the flag, literal 0, a `const val` name, and the token-count separator).
    def _help_ids(src, min_sites=1):
        return {rid for rid, _, _ in check_help_exit({"H.kt": src}, min_sites=min_sites)}

    for label, mutate, want in [
        ("help exit: the accepted spellings are clean", None, set()),
        ("help exit: decided on positional emptiness again (the defect)",
         lambda x: x.replace('if (args.hasFlag("h", "help")) 0 else 2',
                             'if (args.positional.isEmpty()) 2 else 0'), {"H1"}),
        ("help exit: the help arm answers a non-zero literal",
         lambda x: x.replace("NativeOffloadResult(0, HELP)",
                             "NativeOffloadResult(2, HELP)"), {"H1"}),
        ("help exit: the bare arm degraded to 0",
         lambda x: x.replace("0 else 2", "0 else 0"), {"H2"}),
        ("help exit: the bare arm degraded to 1",
         lambda x: x.replace("0 else 2", "0 else 1"), {"H2"}),
        ("help exit: the exit constant behind the help arm stops being 0",
         lambda x: x.replace("const val EXIT_OK = 0", "const val EXIT_OK = 2"), {"H1"}),
        ("help exit: an exit code nobody can decide (fail-closed)",
         lambda x: x.replace('if (args.hasFlag("h", "help")) 0 else 2',
                             "helpExitFor(args)"), {"H1"}),
        ("help exit: a help guard with no exit site",
         lambda x: x.replace('return NativeOffloadResult(0, HELP)',
                             "println(HELP)"), {"H3"}),
        ("help exit: the derivation finds fewer sites than the floor",
         None, {"H3"}),
    ]:
        if label.endswith("floor"):
            got = _help_ids(GREEN_HELP_SHAPES, min_sites=HELP_EXIT_MIN_SITES)
        else:
            mutated = GREEN_HELP_SHAPES if mutate is None else mutate(GREEN_HELP_SHAPES)
            if mutate is not None and mutated == GREEN_HELP_SHAPES:
                print(f"SELFTEST-FAIL: {label}: mutation did not apply to any arm")
                failures += 1
                continue
            got = _help_ids(mutated)
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
    if clean_help := check_help_exit({"H.kt": GREEN_HELP_SHAPES}, min_sites=1):
        print(f"SELFTEST-FAIL: help-exit green fixture is not clean: "
              f"{describe(clean_help)}")
        failures += 1

    # ── [batch6-t01] 第六批 6 条见证的牙 ─────────────────────────────────────
    # 每条见证都是"判据锚某物存在，缺陷动的是这个存在物的效果"。这里把每条
    # 固化成一条**等价拼写**的臂：门必须在自己该红的那条判据上红（期望集合手写
    # 自规则表，绝不由运行结果反推）。
    PROSE_BINDING = (
        'val body = JSONObject().put("error", "not_found").put("id", id)\n'
        '                    .put("message", "No contact with id=$id.")\n'
        '                    .toString()'
    )
    b6 = 0
    b6_cases = [
        ("batch6: fastio budget guard folds false via `(1 == 2)`",
         lambda: {r for r, _k, _d in check_fastio_search(
             GREEN_FASTIO_SEARCH.replace(
                 "if (limit <= 0) {", "if (limit <= 0 && (1 == 2)) {"))},
         {"S1"}),
        ("batch6: fastio budget guard folds false via `!true`",
         lambda: {r for r, _k, _d in check_fastio_search(
             GREEN_FASTIO_SEARCH.replace(
                 "if (limit <= 0) {", "if (limit <= 0 && !true) {"))},
         {"S1"}),
        ("batch6: the send success-set decision folds false via `(0 == 1)`",
         lambda: {r for r, _k, _d in check_sessions_send_status(
             GREEN_SESSIONS_SEND.replace(
                 "if (!sendSucceeded(status)) {",
                 "if ((0 == 1) && !sendSucceeded(status)) {"))},
         {"S2"}),
        ("batch6: clipboard's SDK-Q predicate is negated in place",
         lambda: {r for r, _k, _d in check_clipboard_clear(
             GREEN_CLIPBOARD_CLEAR.replace(
                 "clearAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)",
                 "clearAction(!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q))"))},
         {"L2"}),
        ("batch6: clipboard's SDK-Q predicate is bound to an always-false local",
         lambda: {r for r, _k, _d in check_clipboard_clear(
             GREEN_CLIPBOARD_CLEAR.replace(
                 "clearAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)",
                 "clearAction(isQ)").replace(
                 "    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {\n",
                 "    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {\n"
                 "        val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && false\n"))},
         {"L2"}),
        ("batch6: the payload literal is bound to a local first (N1 binding)",
         lambda: {r for r, _k, _d in check_offload_json(
             ("ContactsOffloadHandler.kt",
              GREEN_PAYLOAD_CONTACTS.replace(
                  PROSE_BINDING,
                  'val body = "android-contacts: no contact with id=$id"')))},
         {"N1"}),
        ("batch6: the page-size `Invalid` arm folds its refusal behind `if (false)`",
         lambda: {r for r, _k, _d in check_offload_page_size(_page_mut(
             ("OffloadPageSize.kt", "is Invalid -> return parsed",
              "is Invalid -> if (false) return parsed")))},
         {"PG3"}),
        ("batch6: the page-size key sits in another argument position",
         lambda: {r for r, _k, _d in check_offload_page_size(_page_mut(
             ("CalendarOffloadHandler.kt",
              'return NativeOffloadResult(0, "$limit")',
              'val spare = args.getInt("day", "limit")\n'
              '        return NativeOffloadResult(0, "$limit")')))},
         {"PG2"}),
        ("batch6: the page-size key is bound to a local val first",
         lambda: {r for r, _k, _d in check_offload_page_size(_page_mut(
             ("CalendarOffloadHandler.kt",
              'return NativeOffloadResult(0, "$limit")',
              'val limitKey = "limit"\n'
              '        val spare = args.getInt(limitKey)\n'
              '        return NativeOffloadResult(0, "$limit")')))},
         {"PG2"}),
        ("batch6: the page-size refusal dies while an unrelated exit 2 stays in the function",
         lambda: {r for r, _k, _d in check_offload_page_size(_page_mut(
             ("CalendarOffloadHandler.kt",
              '            is PageSizeOutcome.Invalid ->\n'
              '                return NativeOffloadResult(2, "android-calendar list: ${size.message}")',
              '            is PageSizeOutcome.Invalid ->\n'
              '                if (false) return NativeOffloadResult(2, "android-calendar list: ${size.message}") else 50')))},
         {"PG4"}),
    ]
    for label, run, want in b6_cases:
        got = run()
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
        else:
            b6 += 1
    # 精度臂：绑到变量但从没进 formatBody 的散文不是载荷（N1 只判"被消费的值"）
    if {r for r, _k, _d in check_offload_json(
            ("ContactsOffloadHandler.kt", GREEN_PAYLOAD_CONTACTS.replace(
                "    private fun doStrict(",
                '    private fun noteFile(): String {\n'
                '        val note = "no contacts matched"\n'
                '        return JSONObject().put("note", note).toString()\n'
                '    }\n\n'
                "    private fun doStrict(")))}:
        print("SELFTEST-FAIL: batch6: a prose local that never reaches formatBody tripped N1")
        failures += 1
    else:
        b6 += 1
    print(f"BATCH6_T01_ARMS={b6} checks passed")

    # ── [R3-t14] third-round witnesses #151 / #152 / #153 ───────────────────
    # All three kept EVERY token the pre-existing criteria pin and gutted the
    # function body instead: the long-option rewrite returned the argv
    # untouched (#151), the grep cap lost its only writer (#152), and one arm
    # of the tar short-option table became a silent no-op (#153). The arms
    # below mutate exactly that decision, one at a time.
    r3 = 0
    MAP = 'if (it in SHORT_LONG_OPTIONS) "-$it" else it'
    CAP = ('        if (out.matches.size >= limit) {\n'
           '            out.matchesCapped = true\n'
           '            return\n'
           '        }\n')
    r3_cases = [
        ("r3-15-1: the long-option rewrite returns the argv untouched",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace('argv.map { ' + MAP + ' }', "argv"))},
         {"N1"}),
        ("r3-15-1b: the member token is passed through unchanged (no dash added)",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace(MAP, "if (it in SHORT_LONG_OPTIONS) it else it"))},
         {"N2"}),
        ("r3-15-1c: the table is declared but no longer consulted",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace(MAP, 'if (it.isNotEmpty()) "-$it" else it'))},
         {"N2"}),
        ("r3-15-1d: the pass-through arm rewrites a non-member token too",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace(MAP, 'if (it in SHORT_LONG_OPTIONS) "-$it" else it.trim()'))},
         {"N3"}),
        ("r3-15-3: the -C arm silently no-ops (`'C' -> Unit`)",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace('\'C\' -> expanded.add("--directory")', "'C' -> Unit"))},
         {"T1", "T3"}),
        ("r3-15-3b: the -C arm is deleted outright",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace('                        \'C\' -> expanded.add("--directory")\n',
                                  ""))},
         {"T3"}),
        ("r3-15-3c: the -C arm expands to a key nothing parses",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace('\'C\' -> expanded.add("--directory")',
                                  '\'C\' -> expanded.add("--dir")'))},
         {"T3", "T5"}),
        ("r3-15-3d: an unknown option is dropped instead of invalidating the token",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace("else -> known = false", "else -> Unit"))},
         {"T2"}),
        ("r3-15-3e: a new letter joins the table as a no-op",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace("'f' -> Unit", "'q' -> Unit\n                        'f' -> Unit"))},
         {"T1"}),
        ("r3-15-3f: the expansions are appended regardless of `known`",
         lambda: {r for r, _k, _d in check_fastio_normalizers(
             GREEN_FASTIO.replace("if (known) out.addAll(expanded) else out.add(token)",
                                  "out.addAll(expanded)"))},
         {"T4"}),
        ("r3-15-2: the budget branch returns without setting the cap",
         lambda: {r for r, _k, _d in check_fastio_search(
             GREEN_FASTIO_SEARCH.replace(CAP,
                                         "        if (out.matches.size >= limit) {\n"
                                         "            return\n"
                                         "        }\n"))},
         {"S4"}),
        ("r3-15-2b: the cap is written back to false later in the file",
         lambda: {r for r, _k, _d in check_fastio_search(
             GREEN_FASTIO_SEARCH.replace(
                 "        out.matches.add(GrepMatch(guestPath, lineNumber, line))",
                 "        out.matchesCapped = false\n"
                 "        out.matches.add(GrepMatch(guestPath, lineNumber, line))"))},
         {"S4"}),
        ("r3-15-2c: the budget guard constant-folds false",
         lambda: {r for r, _k, _d in check_fastio_search(
             GREEN_FASTIO_SEARCH.replace("if (out.matches.size >= limit) {",
                                         "if (out.matches.size >= limit && false) {"))},
         {"S4"}),
    ]
    for label, run, want in r3_cases:
        got = run()
        if got != want:
            print(f"SELFTEST-FAIL: {label}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
        else:
            r3 += 1
    # precision arm: `-f` is the ONE deliberate no-op (the archive must stay a
    # positional), so dropping it must NOT be read as a silently-lost option.
    if check_fastio_normalizers(GREEN_FASTIO.replace("'f' -> Unit", "'f' -> Unit")):
        print("SELFTEST-FAIL: r3: the clean fixture tripped the normalizer arms")
        failures += 1
    else:
        r3 += 1
    print(f"R3_T14_ARMS={r3} checks passed")

    if clean or clean_flags or clean_debug or clean_sessions or clean_bindings \
            or clean_payload or clean_album or clean_search or clean_clipboard \
            or clean_send_status or clean_provenance or clean_help or clean_norm:
        green_findings = (clean + clean_flags + clean_debug + clean_sessions
                          + clean_bindings + clean_payload + clean_album + clean_search
                          + clean_clipboard + clean_send_status + clean_norm)
        print(f"SELFTEST-FAIL: green fixtures must produce no findings, "
              f"got {describe(green_findings)}")
        failures += 1
    if failures:
        print(f"SELFTEST: {failures} failing case(s)")
        return 1
    print("OFFLOAD_CLI_ARGS_SELFTEST=PASS")
    return 0


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    # Order-independent: gate_run.sh calls `python3 <gate> --selftest <repo>`,
    # test_scan.py calls `python3 <gate> --selftest`, humans call `... <repo>`.
    if "--selftest" in argv:
        return selftest()
    positional = [a for a in argv if not a.startswith("--")]
    if not positional:
        print(__doc__)
        return 2
    root = Path(positional[0])
    contacts_path = root / CONTACTS
    config_path = root / CONFIG
    browseruse_path = root / BROWSERUSE
    fastio_path = root / FASTIO
    output_path = root / OUTPUT
    speak_path = root / SPEAK
    debug_path = root / DEBUG_HANDLER
    sessions_path = root / SESSIONS
    notification_path = root / NOTIFICATION
    args_path = root / ARGS
    search_path = root / FASTIO_SEARCH
    if not contacts_path.is_file() or not config_path.is_file() \
            or not browseruse_path.is_file() or not fastio_path.is_file() \
            or not output_path.is_file() or not speak_path.is_file() \
            or not debug_path.is_file() or not sessions_path.is_file() \
            or not notification_path.is_file() or not args_path.is_file() \
            or not search_path.is_file():
        print(f"GATE-FAIL: handler source not found under {root}")
        return 1
    # fail-closed: a payload file that moves (or is renamed) must be a red
    # gate, not a silently narrower rule set.
    payload_files = []
    for rel in PAYLOAD_FILES:
        if not (root / rel).is_file():
            print(f"GATE-FAIL: payload source not found: {rel} under {root}")
            return 1
        payload_files.append((rel, (root / rel).read_text(encoding="utf-8")))
    # [batch6-t01] 共享词法器解析失败一律 fail-closed（KotlinLexError → 违规），
    # 不许回退成原文扫描：看不懂的源码不是合格的源码。
    try:
        findings = check_text(contacts_path.read_text(encoding="utf-8"),
                              config_path.read_text(encoding="utf-8"),
                              browseruse_path.read_text(encoding="utf-8"),
                              fastio_path.read_text(encoding="utf-8"))
        findings += check_flag_set(output_path.read_text(encoding="utf-8"),
                                   speak_path.read_text(encoding="utf-8"))
        findings += check_debug(debug_path.read_text(encoding="utf-8"))
        findings += check_sessions(sessions_path.read_text(encoding="utf-8"))
        findings += check_sessions_send_status(sessions_path.read_text(encoding="utf-8"))
        findings += check_args(args_path.read_text(encoding="utf-8"))
        findings += check_photos_album((root / PHOTOS).read_text(encoding="utf-8"))
        findings += check_offload_cli_bindings(contacts_path.read_text(encoding="utf-8"),
                                              notification_path.read_text(encoding="utf-8"),
                                              fastio_path.read_text(encoding="utf-8"))
        # [dv29-A3 A-05] the page-size contract runs over the whole offload dir —
        # its scope is the registry inside OffloadPageSize.kt, so it needs every
        # source (see check_offload_page_size below).
        findings += check_offload_json(*payload_files)
        findings += check_clipboard_clear((root / CLIPBOARD).read_text(encoding="utf-8"))
        findings += check_fastio_search(search_path.read_text(encoding="utf-8"))
        # [R3-t14 #151/#153] the two argv normalizers, judged as decisions
        findings += check_fastio_normalizers(fastio_path.read_text(encoding="utf-8"))
        # [dv25-A4 A-07] payload provenance ACROSS files (see check_payload_delegates)
        sources = {}
        for _dir in OFFLOAD_SOURCE_DIRS:
            _p = root / _dir
            if not _p.is_dir():
                print(f"GATE-FAIL: offload source dir not found: {_dir} under {root}")
                return 1
            for _f in sorted(_p.glob("*.kt")):
                sources[_f.name] = _f.read_text(encoding="utf-8")
        findings += check_offload_page_size(sources)
        findings += check_payload_delegates(sources, PAYLOAD_FILES)
        # [dv27-A5 A-07] the help / bare-invocation exit code, derived over the family
        findings += check_help_exit(sources)
    except KotlinLexError as exc:
        print(f"GATE-FAIL: the Kotlin source did not lex ({exc}) — fail-closed")
        return 1
    if findings:
        print("offload_cli_args_check: FAIL")
        print(describe(findings))
        return 1
    print("offload_cli_args_check: PASS (contacts dispatch parsed, config --file cwd-aware, "
          "browseruse paths joined, fastio tar verdict shared, "
          "OUTPUT_FLAGS covers help/h, speak declares stop/status, "
          "sessions date bounds funneled + refuse invalid, "
          "debug pass-through argv verbatim + global-only output shaping, "
          "9/9 payload files hand formatBody JSON (N1 whole-file, N2 payload helpers), "
          "cross-file payload delegates registered (Z1 derived members, Z2 no String "
          "delegate in a payload file, Z3 exemptions still needed, Z4 derivation alive), "
          "page size resolved by the ONE shared registry (refuse non-numeric / <=0 / "
          ">bound / bare flag, -n alias survives only as a refusal, every registered "
          "handler routes through it, its refusals reach the caller as exit 2), "
          "notification --after "
          "refuses an unreadable value, fastio subcommand scan knows its boolean flags "
          "and consumes -C, photos --album decision classified (non-numeric --id refuses), "
          "contacts subcommand probe reads the rewritten alias list, sessions declares "
          "async/wait/help/h and documents --wait, grep budget stops a spent root before "
          "any file is opened, clipboard clear reaches clearPrimaryClip behind the shared "
          "four-guard shape and keeps the pre-Q empty-string fallback, "
          "every help-guarded early exit answers 0 for --help while a bare invocation "
          "still answers 2 (H1/H2 derived over the family, H3 fail-closed on the "
          "derivation), batch6 witnesses folded (dead conditions const-folded, "
          "the SDK-Q decision value-checked, payload bindings resolved, page "
          "refusals live on their own call)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
