#!/usr/bin/env python3
"""
Gate: FIX-D (deep-audit-1024 ISSUE-04) — one convergence point for a MCP
server's OAuth credentials and in-guest bridge file, run by every write path.

ISSUE-04 was three drifts of the same invariant:

  crack 1  `MCPIntegrationsScreen` switched an authorized server to STDIO (or
           cleared its OAuth block) and called only `setClientSecret(ctx, id,
           null)` — which removes ONLY `secret::<id>`. `tokens::<id>` and the
           bridge file `/var/minis/mcp-servers/oauth/<id>.json` (a LIVE bearer
           token, plaintext, readable by any guest shell) survived forever, and
           the STDIO form hides the Sign Out button, so no UI path could ever
           clear them.
  crack 2  editing an authorized server re-saved the endpoints without
           re-materializing the bridge, so the guest CLI refreshed against the
           old token_endpoint / client_id / client_secret / resource.
  crack 3  `MCPRepository.importJSON` overwrote a same-id entry last-write-wins
           and left the old OAuth state in place.

The fix converges all three in the DATA layer (`MCPRepository.add/update/
importJSON` → `MCPOAuthStore.reconcileForServer`), because the UI is only one of
the write paths (paste-import and backup-restore are others, and any future
entry point must inherit the rule).

Checks (all on comment/string-masked code, so a comment cannot satisfy them):

  S1  MCPOAuthStore.kt defines `reconcileForServer(` whose body calls BOTH
      `purge(` and `materializeBridgeFile(` — the two outcomes must stay inside
      one function, or "reconcile" degenerates into half a cleanup again.
  S2  the pure `decideReconcileAction(` body still maps something to PURGE
      (dropping the purge outcome is the silent way to re-open crack 1).
  S3  no function may remove only `secret::<id>` and count as a cleanup: every
      function whose body removes the secret key must also remove the tokens key
      and delete the bridge file. `setClientSecret` (a setter, not a cleanup) is
      the single explicit exemption — adding another one is a deliberate edit
      here, not a silent pass.
  S4  every function that removes the tokens key must also delete the bridge
      file (a token with no bridge-file delete = plaintext credentials left in
      the guest).
  R1  MCPRepository.add( body calls the convergence point.
  R2  MCPRepository.update( body calls the convergence point.
  R3  MCPRepository.importJSON( body calls the convergence point (or purge( —
      an imported OAuth-less entry has nothing to materialize).
  R4  when the repository routes through a private helper, that helper's body
      must call `MCPOAuthStore.reconcileForServer(` — the indirection has to
      land somewhere.
  R5  the repository must not use `setClientSecret(`: a secret-only verb is not
      a complete cleanup, and purging alone on every save would instead wipe a
      live token (both are the same mistake seen from two sides).

Deliberate tightening vs the ISSUE-04 write-up: `add`/`update` are NOT allowed
to satisfy R1/R2 with a bare `purge(` — purging on every save would destroy the
credentials of a correctly authorized server, i.e. crack 1's mirror image.

[da1029-E] Two UI-side criteria were added to the same gate, because they pin
the same invariant from the other end — "the sheet edits what is on disk":

  U1  the edit-sheet call site in `MCPIntegrationsScreen` must hand
      `MCPAddSheet` the LIVE entry (resolved from the listing with
      `servers.find { … }` and an elvis fallback) instead of the
      `editServer` snapshot captured at tap time. With the snapshot, a write
      that landed while the sheet was open (a reconcile from `add`/`update`,
      a paste-import, `reloadFromDisk`) was both displayed stale and then
      rolled back by Save — the reconcile this gate defends was undone by the
      UI. The elvis fallback is required so an entry deleted meanwhile does
      not silently turn the edit sheet into the add flow.

  [dv14-B3] U1 was found pinning the DEFECT as the only legal shape: the elvis
  keeps the edit sheet open when the entry was deleted externally (reloadFrom
  Disk / paste-import land while the sheet is open), and Save then stores
  setClientSecret under a deleted id — orphan credentials against the
  MCPRepository.kt invariant "credentials exist iff the saved config is a
  configured URL-transport OAuth server" — while update() returns false and is
  silently dropped. The elvis is now ALLOWED but no longer sufficient on its
  own: the call site must also CLOSE the sheet when the listing no longer
  contains the captured id, via a `LaunchedEffect` keyed on the listing whose
  body checks `servers.none { … }` and sets `editServer = null` (keyed on the
  listing so a change re-launches with the fresh value; state is mutated from
  the effect, never inline in composition). Both legal shapes pass:
  elvis + close effect (transitional), or no elvis + close effect.
  U2  every `var … by remember` in `MCPFormTab` whose initializer mentions
      `editServer` must be keyed on that entry, so a changed entry re-seeds
      the field states instead of leaving the old values on screen (1027-E
      ModelGroupDetailScreen / 1028-C EnvVarFormSheet are the same defect).

U2 is enumerated, not counted by hand: a hand-written "16 fields" list is
exactly what drifts. The check walks every `remember` in the function body and
reports the un-keyed ones by line. Keyed-on-the-entry is required rather than
any key: `remember(nonce)` would satisfy "has a key" while still never
re-seeding.

U3 ([dv14-B3]) — the form must not store credentials under a deleted id: every
function body in the UI file that calls `setClientSecret(` (both the Authorize
and the Save click handlers) must also check the listing still contains the id
(`servers.value.none { it.id == … }` guard), refusing instead of aiming at a
deleted id. A save refused at the guard writes no secret, calls no
`add`/`update`, and never reaches `onDone()`.

[da1034-A1] Two more criteria extend the same credential invariant to the
backup surface — the artifact archive is NOT gated by `includeSecrets`:

  A1  `ArtifactBackupScope` must keep a deny-set of credential directory names
      (literal `setOf("oauth")`) AND actually consult it while walking, at
      every level. The MCP OAuth bridge (`mcp-servers/oauth/<server>.json` —
      access_token / refresh_token / client_secret in plaintext, plus a
      pending `<server>.secret` handoff) lives under the mcp artifact root, so
      without this the daily auto-backup and every WebDAV copy of it shipped
      live bearer tokens. Anchored on the declaration + the `child.name in …`
      skip so a comment cannot satisfy it and a renaming cannot silently
      unhook it.
  A2  `ConfigBackup.import`'s artifact stage must run `stripMcpOAuthEntries(`
      BEFORE `ArtifactArchiver.extractZipByPrefix(`, the predicate
      (`isMcpOAuthEntryName`) must pin both the "mcp" and "oauth" segments,
      and the drop must be reported through `skipped`. A restore of an archive
      written by a pre-fix build (or crafted by hand) would otherwise
      overwrite this device's LIVE token bridge — fixing the exporter cannot
      protect archives that already exist.

[dv28-a5 A-05] Two more criteria extend the SAME artifact stage to a second
credential-free file that was written twice. Stage 7 merges the backup's
`mcpServers` into this device's list (`MCPRepository.importJSON` keeps the
rows the backup does not carry, then saves); Stage 9 then restored
`mcp/servers.json` out of the archive VERBATIM, replacing the merged file with
the backup's list. Every server the target device had and the backup did not
was erased on disk — the in-memory merge hid it until the next cold start
re-read the file (`ConfigBackup` only reloads `skillRepo`).

  C-ST9  `import` must not copy `mcp/servers.json` when Stage 7 merged the
      list. Derived, not enumerated: the operands of the `if (…)` that gates
      the Stage 7 merge call are the operands the skip has to be derived from,
      so the filter call carries a third argument — an identifier computed in
      `import` from at least two of them. `true` (or a flag that ignores the
      repository) is not a decision: an unconditional drop loses the list from
      an archive-only backup (no `mcpServers` section), an unconditional copy
      erases the merged one. The predicate must pin the ONE entry name
      ("mcp" + "servers.json", segments collapsed) and the filter must consult
      it only behind that switch; the `mcp` prefix must stay an extraction
      destination so the rest of the MCP data still restores.
  C-ST9b Two legal shapes, whitelisted so the criterion cannot lock one
      implementation: (a) re-import after extraction and call
      `reloadFromDisk()` after it, or (b) skip the entry and say so — a
      `skipped` literal naming the file and the stage that restored it.
      A comment cannot satisfy either (strings are masked for the structural
      half; the literal half reads the scoped source, and the arm that only
      comments the drop out is still red on C-ST9).

[dv25-A1] The PARSE side of the same credential invariant (deep-audit-1045
A-01). The in-guest CLI writes camelCase oauth keys (`clientId`,
`authorizationEndpoint`, `tokenEndpoint`, `redirectURI` — main.py), this app
writes snake_case. Reading snake_case only left every field blank for a
CLI-written entry ⇒ `isConfigured` false ⇒ `MCPRepository.save()` — which runs
on the load of any entry without `createdAt`, and the CLI writes none — dropped
the `oauth` block outright, so the config evaporated from servers.json on the
next reload from disk or backup import (both go through the same `fromJson`).
The CLI's key set is DERIVED from main.py, so a new guest-side key turns this
gate red instead of silently evaporating.

  C1  every key the guest CLI writes is readable here: either spelled the same
      way, or mentioned by a key table (`listOf(<snake_case>, <camelCase>)`).
  C2  key tables are ordered primary-then-alias — snake_case first. A table
      whose only entry is the camelCase spelling (alias promoted to primary)
      makes the value this app wrote unreadable.
  C3  an aliased table must be consulted by `fromJson` — a fallback nobody
      reads is a fallback on paper.
  C4  `toJson` writes only snake_case keys: the disk/backup format must not
      fork just because the read side learned a second spelling.
  C5  `fromJson` keeps its "no endpoints = noise" null path (every property
      `isConfigured` requires is blank-checked).

[dv26-A2] The BRIDGE side of the same credential invariant, in the direction
nobody implemented (deep-audit-1046 S-02). `MCPOAuthStore` and
`/var/minis/mcp-servers/oauth/<server>.json` are two copies of one credential:
the host writes the bridge, and the in-guest CLI rewrites it on every refresh
(`transport/http.py::_oauth_refresh → _save_oauth_tokens`) with a new
access_token / expires_at and — under refresh-token rotation — a new
refresh_token. Nothing read it back, so `REFRESH_BRIDGE` (every save of any
other field of that server) re-materialized the PRE-refresh snapshot; the guest
then presented a rotated-away refresh_token and got `invalid_grant`.

  C1  the function that materializes the bridge must, through its call
      closure, READ the bridge file (a helper, not a comment) and PERSIST what
      it read (`setTokens(`) — reading without persisting just moves the
      regression one save later.
  C2  the promotion must ORDER the bridge expiry against the stored copy (an
      older bridge is the shape of "the app authorized after the guest's last
      refresh" — promoting it is a rollback) and compare the tokens themselves
      (a same-second refresh has an identical expiry). The `>=` vs `>` boundary
      is behaviour and lives in `MCPOAuthBridgeMergeTest`, not here.
  C3  the bridge is a token carrier, not a configuration source: the reader must
      not read `client_id` / `token_endpoint` / `client_secret` / `resource`
      (derived as "what materializeBridgeFile writes, minus the token triple").

  A damaged / older / blank bridge promotes NOTHING — clearing valid host
  tokens on a corrupt guest file would be the inverse bug, and a worse one.

All are fail-closed: sources that cannot be read are a violation.

Usage: python3 scripts/scan/mcp_oauth_lifecycle_check.py [repo_root]
       python3 scripts/scan/mcp_oauth_lifecycle_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_value, enclosing_conditions, function_body, if_blocks, match_bracket,
    split_top, tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args, contains_ident, guard_feasible, path_feasible, value_sources,
)

STORE_REL = "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthStore.kt"
REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/MCPRepository.kt"
UI_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt"

SELFTEST_MARK = "MCP_OAUTH_LIFECYCLE_SELFTEST=PASS"

# [da1029-E] UI-side anchors.
UI_FORM_FUN = "MCPFormTab"
UI_LIST_HOLDER = "servers"
UI_ENTRY_PARAM = "editServer"
UI_KEY_TOKENS = (UI_ENTRY_PARAM, "liveServer")
UI_LIVE_PASS = "editServer = "
UI_RESOLVE_PARAM = "MCPAddSheet"

# [da1034-A1] artifact-backup surface.
SCOPE_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ArtifactBackupScope.kt"
CONFIG_BACKUP_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"
OAUTH_DENY_LITERAL = "oauth"
OAUTH_ENTRY_PREDICATE = "isMcpOAuthEntryName"
OAUTH_STRIP_HELPER = "stripMcpOAuthEntries"
OAUTH_EXTRACT_CALL = "ArtifactArchiver.extractZipByPrefix("
OAUTH_SKIP_REPORT = "oauth bridge entries"

# [dv20-a1 A-01] The artifact (output-asset) face of the same credential
# invariant: a credentials-free export must not archive servers.json verbatim.
ARTIFACT_ARCHIVER_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ArtifactArchiver.kt"
ARTIFACT_WRITE_CALL = "ArtifactArchiver.writeEntries("
MCP_ARTIFACT_REDACTOR = "redactMcpArtifactEntry"
MCP_SECRET_STRIP = "stripExportedMcpSecrets"
DENIED_SET_RE = re.compile(
    r'val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*setOf\(\s*"' + OAUTH_DENY_LITERAL + r'"\s*\)'
)

# [dv25-A1] The PARSE side of the same credential invariant. The in-guest CLI
# (`minis-mcp-cli add --oauth-*`) writes camelCase keys while this app writes
# snake_case. Reading snake_case only made every field blank for a CLI-written
# entry ⇒ isConfigured false ⇒ MCPRepository.save() (it runs on the load of any
# entry without `createdAt`, and the CLI writes none) dropped the whole `oauth`
# object. Both entry points are the same parse: CLI add → reloadFromDisk, and
# backup import → importJSON.
#
# The CLI's key set is DERIVED from main.py rather than listed here, so a new
# key on the guest side turns this gate red instead of silently evaporating.
CONFIG_REL = "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthConfig.kt"
CLI_REL = "src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/main.py"
KEY_TABLE_RE = re.compile(r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*listOf\(([^)]*)\)")
KEY_LITERAL_RE = re.compile(r'"([^"]*)"')
CLI_OAUTH_DICT_RE = re.compile(r"\boauth\s*=\s*\{")
CLI_OAUTH_ITEM_RE = re.compile(r'"([A-Za-z_][A-Za-z0-9_]*)"\s*:')
CLI_OAUTH_ITEM_SET_RE = re.compile(r'\boauth\[\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*\]')
TOJSON_WRITE_RE = re.compile(r'\bput\(\s*"([A-Za-z_][A-Za-z0-9_]*)"')
CONFIGURED_PROP_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\.isNotBlank\(\)")

CONVERGE_DECL = "reconcileForServer("
DECIDE_DECL = "decideReconcileAction("
PURGE_OUTCOME = "PURGE"
PURGE_CALL = "purge("
MATERIALIZE_CALL = "materializeBridgeFile("
SECRET_REMOVE = "remove(secretKey("
TOKENS_REMOVE = "remove(tokensKey("
BRIDGE_DELETE = "deleteBridgeFile("
SECRET_ONLY_VERB = "setClientSecret("
# [dv27-A3] the `.secret` handoff: the in-guest CLI cannot write the encrypted
# store, so it drops `<server>.secret` next to the token bridge and expects the
# HOST to import it. Before this, only the edit sheet ever imported it, so
# every other reader saw the stale stored value — and `signOut` DELETED the
# file, destroying the only copy of a client secret that cannot be re-derived.
SECRET_IMPORT_CALL = "importPendingClientSecret("
REPO_FULL_CALL = "MCPOAuthStore.reconcileForServer("
REPO_HELPER_CALL = "reconcileOAuth("
REPO_HELPER_DECL = "reconcileOAuth"
WRITE_PATHS = ("add", "update", "importJSON")
IMPORT_ALSO_OK = ("MCPOAuthStore.purge(",)

# Functions allowed to touch ONE credential key on its own, with a reason.
HALF_CLEANUP_EXEMPT = {
    "setClientSecret": "setter: writes or clears the secret field only, never a cleanup entry point",
}

FUN_DECL_RE = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")

# [dv26-A2] deep-audit-1046 S-02 — the guest -> host half of the token bridge.
# The store and `/var/minis/mcp-servers/oauth/<server>.json` are two copies of
# one credential; the in-guest CLI rewrites its copy on every refresh and
# nothing read it back, so a config save materialized the pre-refresh snapshot
# over it (with a rotated-away refresh_token, i.e. invalid_grant).
MERGE_REL = ("src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/"
             "MCPOAuthBridgeMerge.kt")
BRIDGE_READ_CALL = "readText("
BRIDGE_WRITE_CALL = "writeText("
BRIDGE_PATH_MARKERS = ("bridgeFile(", "bridgeDir(")
BRIDGE_TOKEN_KEYS = ("access_token", "refresh_token", "expires_at")
PREFS_TOKEN_READER = "tokens"
PUT_KEY_RE = re.compile(r'\bput\(\s*"([A-Za-z_][A-Za-z0-9_]*)"')
DATA_CLASS_RE = re.compile(r"\bdata\s+class\s+([A-Za-z_]\w*)\s*\(")
CALL_RE = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
COMPARISON_RE = re.compile(r"([A-Za-z_][\w.]*)\s*(>=|<=|!=|==|>|<)\s*([A-Za-z_][\w.]*)")
LOCAL_VAL_RE = re.compile(r"\bval\s+([A-Za-z_]\w*)\s*=\s*([^\n]*)")
ORDERING_OPS = (">=", "<=", ">", "<")
INEQUALITY_OPS = ("!=", "==")


# [batch5-t11] 词法一律走共享原语 `_kotlin_lexer`。本门曾经自写 `mask` /
# `mask_comments`：字符串里的 `}` 能提前闭合函数体、注释里的写法能骗过判据、
# 嵌套块注释遇到第一个 `*/` 就退出。`mask` 这个名字保留为结构视图别名，
# 免得上百个调用点重命名（实现来自共享模块，解析失败抛 KotlinLexError）。
mask = mask_code

def line_of(code, index):
    return code.count("\n", 0, index) + 1


def body_at(code, sig_start):
    """Balanced `{...}` body for a declaration starting at [sig_start].

    Returns None when the declaration has an expression body (no brace before
    the next `fun`) — such a function holds no removals anyway.
    """
    lp = code.find("(", sig_start)
    if lp < 0:
        return None
    depth = 0
    rp = -1
    for j in range(lp, len(code)):
        if code[j] == "(":
            depth += 1
        elif code[j] == ")":
            depth -= 1
            if depth == 0:
                rp = j
                break
    if rp < 0:
        return None
    brace = code.find("{", rp)
    if brace < 0:
        return None
    # An expression-bodied declaration (`= ...`) would otherwise hand back the
    # NEXT function's body.
    if "fun " in code[rp:brace]:
        return None
    depth = 0
    j = brace
    while j < len(code):
        c = code[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return code[brace:j + 1]
        j += 1
    return None


def body_of(code, name):
    """Body of the first `fun <name>(` declaration in [code], or None."""
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    return body_at(code, m.start())


def functions(code):
    """[(name, body, line)] for every `fun <name>(` declaration."""
    out = []
    for m in FUN_DECL_RE.finditer(code):
        out.append((m.group(1), body_at(code, m.start()), line_of(code, m.start())))
    return out


PLAY = "the convergence point"


def _first_pos(body, needle):
    """Index of [needle] in [body], or -1."""
    i = body.find(needle)
    return i


def _live_matches(text, needle, domains=None):
    """Start offsets of [needle] that sit on a REACHABLE path.

    [batch5-t11] 本门所有"某调用存在/某调用在前"的判据都走这一个入口：
    `if (false) purge(...)`、`if (1 == 2) purge(...)`、`!true && ...` 折叠后
    都是同一件事（调用永不执行），只是拼写不同。死分支里的匹配不算数。
    """
    return [m.start() for m in re.finditer(re.escape(needle), text)
            if path_feasible(text, m.start(), domains)]


def _blank_literal_arg(text, open_idx):
    """调用的最后一个实参是不是空字符串字面量。

    `importPendingClientSecret(context, "")` 会把 server 名交给
    `validateServerName` 直接拒掉 —— 调用等于没发生，但调用名还在，
    正是"锚拼写、不锚效果"的破口。
    """
    args = call_args(text, open_idx)
    if not args:
        return False
    s, e = args[-1]
    return text[s:e].strip() in ('""', "''")


def _value_consumed(text, start, close):
    """调用结果是否被使用（return / 赋值 / 实参 / 取值链）。裸语句调用不算。"""
    head = text[:start].rstrip()
    if re.search(r"(?:\breturn|\bthrow|=(?!=)|\?:|\(|,|->|&&|\|\|)$", head):
        return True
    tail = text[close + 1:].lstrip()
    return tail.startswith(("?.", ".", "?:", "!!", ")")) or tail.startswith(",")


def _import_sites(body, require_value=False):
    """可达路径上、指向真实 server、且（按需）结果被消费的导入调用位置。"""
    out = []
    for m in re.finditer(re.escape(SECRET_IMPORT_CALL), body):
        pos = m.start()
        if not path_feasible(body, pos):
            continue
        open_idx = m.end() - 1
        close = match_bracket(body, open_idx)
        if close < 0:
            continue
        if _blank_literal_arg(body, open_idx):
            continue
        if require_value and not _value_consumed(body, pos, close):
            continue
        out.append(pos)
    return out


def _imports_pending_secret(code, name, hops=1, require_value=False):
    """True when `fun <name>` (or a callee ONE hop down) ACTUALLY imports the
    handoff. Derived from the call graph + control flow + value flow, not from
    the spelling of the body: the import may sit in a helper the function
    delegates to, and it must not sit behind a dead guard, aim at an empty
    server name, or throw its result away."""
    bodies = {n: b for n, b, _l in functions(code) if b is not None}
    body = bodies.get(name)
    if body is None:
        return False
    if _import_sites(body, require_value):
        return True
    if hops <= 0:
        return False
    for m in CALL_RE.finditer(body):
        callee = m.group(1)
        if callee == name:
            continue
        cb = bodies.get(callee)
        if cb is not None and _import_sites(cb):
            return True
    return False


def check_secret_handoff(code):
    """[dv27-A3] C5/C6 — the `.secret` handoff must have exactly one importer
    and must never be lost.

    The in-guest CLI cannot write the encrypted store, so it drops
    `<server>.secret` next to the token bridge and expects the HOST to import
    it. Two defects shared that file:

      * only the edit sheet ever imported it, so `reconcileForServer` wrote
        the STALE stored secret back into the bridge file and the token
        exchange omitted `client_secret` (a confidential client answers 401);
      * `signOut` deleted the bridge file — and with it the only copy of a
        client secret that had never been imported and cannot be re-derived.

    C5 pins the import into the single exit point (`clientSecret`) and
    requires `signOut` to import BEFORE it deletes the bridge file.
    C6 pins the opposite for `purge`: deleting a server must NOT write the
    secret back into the encrypted store (that would resurrect an orphan
    credential for a server the user just removed).
    """
    v = []
    bodies = {n: b for n, b, _l in functions(code) if b is not None}

    # --- C5a: the single exit point imports the pending handoff -------------
    if "clientSecret" not in bodies:
        v.append(
            "MCPOAuthStore.kt: no `fun clientSecret(` — the single exit point "
            "for a server's client secret is gone; every reader would fall "
            "back to the stored value and ignore the CLI's `.secret` handoff "
            "(ROT: update this gate if it was renamed)"
        )
    elif not _imports_pending_secret(code, "clientSecret", require_value=True):
        v.append(
            "MCPOAuthStore.kt: `clientSecret` does not import the pending "
            "`.secret` handoff — the in-guest CLI seeds a client secret into "
            "`<server>.secret` because it cannot write the encrypted store, "
            "and only the host can move it in. A reader that skips the import "
            "sees the STALE stored value (or none), so the token exchange "
            "omits `client_secret` and a confidential client answers 401"
        )

    # --- C5b: signOut imports before it deletes the bridge file -------------
    signout = bodies.get("signOut")
    if signout is None:
        v.append(
            "MCPOAuthStore.kt: no `fun signOut(` — the sign-out path is gone "
            "(ROT: update this gate if it was renamed)"
        )
    else:
        imp = _import_sites(signout)
        dele = _live_matches(signout, BRIDGE_DELETE)
        if not imp:
            v.append(
                "MCPOAuthStore.kt: `signOut` does not import the pending "
                "`.secret` handoff — it deletes the bridge file, and with it "
                "the only copy of a client secret that was never imported and "
                "cannot be re-derived. `signOut` promises to KEEP the client "
                "secret; a credential loss is not a cleanup gap"
            )
        elif dele and min(imp) > min(dele):
            v.append(
                "MCPOAuthStore.kt: `signOut` deletes the bridge file BEFORE it "
                "imports the pending `.secret` handoff — the handoff file is "
                "gone by the time the import runs, so the client secret is "
                "lost (the import must happen first)"
            )

    # --- C6: purge must not resurrect the secret ---------------------------
    if "purge" in bodies:
        if _imports_pending_secret(code, "purge"):
            v.append(
                "MCPOAuthStore.kt: `purge` imports the pending `.secret` "
                "handoff — deleting a server must REMOVE its credentials, not "
                "write them into the encrypted store. The import would leave "
                "an orphan secret for a server the user just removed"
            )
    return v


def check_store(code):
    v = []
    converge = body_of(code, "reconcileForServer")
    if converge is None:
        v.append(
            "MCPOAuthStore.kt: no `fun reconcileForServer(` with a body — the "
            "single convergence point for config changes is gone (ROT: update "
            "this gate if it was renamed)"
        )
    else:
        # [batch5-t11] "calls purge(" is not the invariant — "purge RUNS" is.
        # `if (false) purge(context, server)` keeps the spelling, drops the
        # cleanup, and the STDIO-switched server keeps its tokens + plaintext
        # bridge file forever (ISSUE-04 crack 1). Same judgement for the
        # materialize half, so neither arm can be satisfied by a dead call.
        purge_at = _live_matches(converge, PURGE_CALL)
        if not purge_at:
            v.append(
                "MCPOAuthStore.kt: reconcileForServer does not call `%s` on a "
                "reachable path — a server that is no longer a configured "
                "URL-transport OAuth server must not keep tokens/secret/bridge "
                "(a call behind a dead guard such as `if (false)` never runs)"
                % PURGE_CALL
            )
        if not _live_matches(converge, MATERIALIZE_CALL):
            v.append(
                "MCPOAuthStore.kt: reconcileForServer does not call `%s` on a "
                "reachable path — an authorized server whose endpoints changed "
                "keeps a bridge file that points at the OLD token endpoint"
                % MATERIALIZE_CALL
            )
    decide = body_of(code, "decideReconcileAction")
    if decide is None:
        v.append(
            "MCPOAuthStore.kt: no `fun decideReconcileAction(` with a body — "
            "the tested decision function is gone"
        )
    elif PURGE_OUTCOME not in decide:
        v.append(
            "MCPOAuthStore.kt: decideReconcileAction never returns %s — nothing "
            "purges any more (the half-cleanup state is unreachable but so is "
            "the cleanup)" % PURGE_OUTCOME
        )

    for name, body, line in functions(code):
        if body is None:
            continue
        if name in HALF_CLEANUP_EXEMPT:
            continue
        if SECRET_REMOVE in body and (TOKENS_REMOVE not in body or BRIDGE_DELETE not in body):
            v.append(
                "MCPOAuthStore.kt:%d: `%s` removes the client secret without "
                "removing the tokens key and the bridge file — removing only "
                "`secret::<id>` is not a cleanup (ISSUE-04 crack 1). If this "
                "really is a plain setter, add it to HALF_CLEANUP_EXEMPT "
                "deliberately." % (line, name)
            )
        if TOKENS_REMOVE in body and BRIDGE_DELETE not in body:
            v.append(
                "MCPOAuthStore.kt:%d: `%s` forgets tokens but leaves the bridge "
                "file — the guest keeps a plaintext bearer token it can no "
                "longer refresh" % (line, name)
            )
    return v


def bridge_readers(code):
    """{name: body} of every function that reads a FILE under the bridge dir.

    Derived, not listed: a body that calls `readText(` on something the bridge
    path helpers built. The prefs reader (`tokens(`) pulls a string out of
    EncryptedSharedPreferences and therefore never qualifies — which is what
    keeps the closure in C1 from being trivially satisfied.
    """
    out = {}
    for name, body, _line in functions(code):
        if body is None or name == PREFS_TOKEN_READER:
            continue
        # A WRITER is not a reader: `materializeBridgeFile` contains
        # `readText(tmp)` in its rename fallback (it copies the half-written
        # temp file), and counting that as "the bridge is read back" would make
        # this gate pass on the pre-fix tree — the exact shape it exists to
        # catch.
        if BRIDGE_WRITE_CALL in body:
            continue
        if BRIDGE_READ_CALL in body and any(m in body for m in BRIDGE_PATH_MARKERS):
            out[name] = body
    return out

def call_closure(code, starts):
    """Names reachable from [starts] through calls to functions declared here."""
    bodies = {n: b for n, b, _l in functions(code) if b is not None}
    seen = set()
    stack = [n for n in starts if n in bodies]
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        seen.add(name)
        for m in CALL_RE.finditer(bodies[name]):
            callee = m.group(1)
            if callee in bodies and callee not in seen:
                stack.append(callee)
    return seen

def _secret_handoff_readers(code_scoped):
    """Names of functions that read the `.secret` HANDOFF file.

    The `.secret` file carries the client secret the in-guest CLI could not
    put into the encrypted store; the token bridge is the sibling `<id>.json`.
    Both live under the bridge dir and both are read with `readText(`, so the
    structural view cannot tell them apart — the string-preserving view can.
    Without this separation the handoff reader looks like a token-bridge
    reader, and the promotion criteria (C1-C3) would judge the WRONG file.
    """
    out = set()
    for name, body, _line in functions(code_scoped):
        if body is None:
            continue
        if ".secret" in body:
            out.add(name)
    return out


def _if_guard_around(body, pos):
    """(condition, consequent) of the innermost `if (...)` containing [pos]."""
    best = None
    for m in re.finditer(r"\bif\s*\(", body):
        if m.start() > pos:
            break
        close = _paren_end(body, m.end() - 1)
        if close < 0 or close < pos:
            continue
        best = (m.end() - 1, close)
    if best is None:
        return None, None
    open_idx, close = best
    k = close + 1
    while k < len(body) and body[k] in " \t\r\n":
        k += 1
    if k < len(body) and body[k] == "{":
        block = brace_block(body, k)
        consequent = block if block else body[k:]
    else:
        eol = body.find("\n", k)
        consequent = body[k:eol if eol >= 0 else len(body)]
    return body[open_idx + 1:close], consequent


def _ordering_direction(cond, consequent, bridge_left, op):
    """(ok, why) — the expiry guard must stop the OLDER copy, never the newer.

    [wide-1024 C2] The criterion only required that SOME ordering operator
    appears between a bridge field and the stored copy. Flipping the shipped
    `if (bridgeSeconds < storedSeconds) return null` into `>=` keeps a
    comparison in the body while the promotion now returns early for every
    bridge the guest just refreshed: the host keeps its superseded token and
    the guest's next refresh is `invalid_grant`. So the DIRECTION is judged
    against the guard's shape: an early exit must be taken on the older side, a
    positive guard must admit the newer side. Anything else (no `if`, or an
    ordering that matches neither shape) is fail-closed red.
    """
    if cond is None:
        return False, ("the expiry ordering is not inside an `if` guard — its "
                       "direction cannot be read (fail-closed)")
    newer = op in (">", ">=") if bridge_left else op in ("<", "<=")
    older = op in ("<", "<=") if bridge_left else op in (">", ">=")
    early_exit = re.search(r"^\s*(?:\{\s*)?return\b", consequent or "") is not None
    if early_exit and not older:
        return False, ("the ordering guard exits early on the NEWER side — every "
                       "bridge the in-guest CLI just refreshed is discarded and the "
                       "host refreshes the superseded token (invalid_grant)")
    if not early_exit and not newer:
        return False, ("the ordering guard admits the OLDER bridge — a copy left "
                       "over from before the host authorized rolls the token back")
    return True, ""


TOKEN_FIELD_PATTERNS = (
    ("access_token", re.compile(r"([A-Za-z_]\w*)\s*=\s*\w+\.optString\(\s*\"access_token\"")),
    ("refresh_token", re.compile(r"([A-Za-z_]\w*)\s*=\s*\w+\.optString\(\s*\"refresh_token\"")),
    ("expires_at", re.compile(r"([A-Za-z_]\w*)\s*=\s*\w+\.optLong\(\s*\"expires_at\"")),
)


def _derive_token_fields(readers_scoped):
    """{bridge key: snapshot field name} 从桥接读取器派生（本门不拼字段名）。"""
    derived = {}
    for key, pat in TOKEN_FIELD_PATTERNS:
        for rbody in readers_scoped.values():
            m = pat.search(rbody)
            if m:
                derived[key] = m.group(1)
                break
    return derived


def _payload_feeds_from_promotion(body, persisted, token_fields):
    """(ok, why) —— 桥接载荷的 token 字段是否由"提升后的令牌"供值。

    C1 原本只要求"读了桥接 + 持久化了提升"。把
    `BridgePayload(accessToken = t.accessToken, …)` 改回
    `stored.accessToken` 同时满足这两条，却把刚提升的新令牌换回被它取代的
    旧值写进桥接文件：沙箱里的 CLI 立刻拿到过期 token，refresh_token 轮换后
    直接 invalid_grant。判据锚"载荷的值从哪来"，不锚"promote 被调用过"。
    """
    ident = None
    for fname in sorted(persisted):
        for m in re.finditer(r"(?<![\w.])" + re.escape(fname) + r"\s*\(", body):
            vm = re.search(r"\bval\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*$",
                           body[:m.start()])
            if vm:
                ident = vm.group(1)
                break
        if ident:
            break
    m_payload = re.search(r"\bBridgePayload\s*\(", body)
    if not m_payload:
        return False, ("builds no `BridgePayload(` — the materialized payload cannot "
                       "be traced back to the promotion (re-anchor this criterion)")
    args = call_args(body, m_payload.end() - 1)
    named = {}
    for s, e in args:
        piece = body[s:e]
        nm = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", piece)
        if nm:
            named[nm.group(1)] = piece[nm.end():].strip()
    if not named:
        return False, ("builds `BridgePayload(` positionally — the token fields "
                       "cannot be traced (re-anchor this criterion)")
    if ident is None:
        return False, ("calls the promotion but never binds its result, so the "
                       "materialized payload cannot be sourced from it")
    fields = [f for f in token_fields.values() if f in named]
    if not fields:
        return False, ("names none of the promoted token fields on the payload — "
                       "the bridge is rewritten from something else")
    for f in fields:
        if not any(contains_ident(s, ident) for s in value_sources(named[f])):
            return False, ("materializes `%s` from a value that is not the promoted "
                           "token (`%s`) — the guest gets the superseded copy back "
                           "(invalid_grant after a refresh-token rotation)"
                           % (f, ident))
    return True, ""


def check_bridge_promotion(store, store_scoped, merge):
    """[dv26-A2] C1-C3 — the guest's refreshed token must survive a config save.

    [store] is comment/string-masked (structure only); [store_scoped] keeps
    string literal CONTENTS (the bridge key names are the cross-language
    contract, so they are anchored); [merge] is the Android-free policy file
    that holds the decision.

    C1  the path that materializes the bridge must READ it first and PERSIST
        what it read. Two thresholds, both needed: reading without persisting
        only moves the regression one save later (the next save reads the
        superseded triple back), and persisting without reading is a no-op.
        Derived through the call graph, so a helper that hides the read still
        counts, and a read that is only mentioned in a comment does not.
    C2  the promotion must ORDER the bridge against the stored copy (an older
        bridge must never win: that is the shape of "the app authorized after
        the guest's last refresh") AND compare the tokens themselves (a
        same-second refresh has an identical expiry, so ordering alone drops
        it). This is not a ritual: each guard is one of the two ways the
        promotion silently stops working.
    C3  the bridge is a token carrier, not a configuration source: the reader
        must not touch `client_id` / `token_endpoint` / `client_secret` /
        `resource` (derived from what `materializeBridgeFile` writes, minus the
        token triple). A stale or hand-edited bridge able to redirect a refresh
        to another endpoint is a worse bug than the one this fixes.

    Deliberately NOT pinned here: whether the ordering comparison is `>=` or
    `>` at the boundary. That is behaviour, and `MCPOAuthBridgeMergeTest`
    drives the same-second case with literals.

    All are fail-closed: when the sources cannot be read, or the anchors moved,
    that is a violation rather than a silent pass.
    """
    v = []

    def red(criterion, message):
        v.append("[dv26-A2] %s %s" % (criterion, message))

    materializers = [(n, ln) for n, b, ln in functions(store)
                     if b is not None and MATERIALIZE_CALL in b]
    if not materializers:
        red("C1", "no function calls `%s` — the bridge is never written, so this "
                  "criterion has lost its subject (ROT: re-point this gate)"
            % MATERIALIZE_CALL)
        return v
    readers = bridge_readers(store)
    # Same walk on the string-preserving view: the bridge KEY NAMES (and the
    # constructor arguments they feed) are the cross-language contract, and the
    # masked view blanks exactly those literals.
    readers_scoped = bridge_readers(store_scoped)
    # [dv27-A3] The `.secret` HANDOFF file lives under the same bridge dir and
    # is read with the same `readText(`, so the structural walk sees it as a
    # token-bridge reader. It is not one: it carries the client secret, not the
    # token triple. Judging it under C1-C3 would read the wrong file (and made
    # the "no bridge reader" fixture report a spurious C2), so the handoff
    # reader is removed from the token-bridge set.
    handoff = _secret_handoff_readers(store_scoped)
    readers = {n: b for n, b in readers.items() if n not in handoff}
    readers_scoped = {n: b for n, b in readers_scoped.items() if n not in handoff}
    if not readers:
        red("C1", "no function reads the bridge file (`%s` under the bridge dir) "
                  "— the in-guest CLI's refreshed token can be overwritten by the "
                  "host's pre-refresh copy again, and a rotated refresh_token "
                  "comes back as invalid_grant" % BRIDGE_READ_CALL)
        return v

    bodies = {n: b for n, b, _l in functions(store) if b is not None}
    for name, line in materializers:
        reach = call_closure(store, [name])
        hit = reach & set(readers)
        if not hit:
            red("C1", "`%s` (line %d) materializes the bridge without reading it "
                      "through any helper — the guest's refreshed token is "
                      "overwritten by the stored snapshot on every config save"
                % (name, line))
            continue
        persisted = [f for f in reach
                     if "setTokens(" in bodies.get(f, "")
                     and (f in readers or call_closure(store, [f]) & set(readers))]
        if not persisted:
            red("C1", "`%s` (line %d) reads the bridge but never persists the "
                      "promotion (`setTokens(`) — the promotion is lost on the "
                      "next save, which reads the superseded triple back" % (name, line))
        else:
            feeds, why = _payload_feeds_from_promotion(
                bodies.get(name, ""), persisted, _derive_token_fields(readers_scoped))
            if not feeds:
                red("C1", "`%s` (line %d) %s" % (name, line, why))

    # -- C3: the reader must not lift configuration out of the guest file ----
    payload_body = body_of(store_scoped, MATERIALIZE_CALL.rstrip("("))
    if payload_body is None:
        red("C3", "`%s` has no body in the store — the bridge payload contract "
                  "cannot be derived, so 'tokens travel guest -> host, endpoints "
                  "do not' cannot be checked (ROT: re-point this gate)"
            % MATERIALIZE_CALL.rstrip("("))
    else:
        keys = []
        for m in PUT_KEY_RE.finditer(payload_body):
            if m.group(1) not in keys:
                keys.append(m.group(1))
        missing = [k for k in BRIDGE_TOKEN_KEYS if k not in keys]
        auth_keys = [k for k in keys if k not in BRIDGE_TOKEN_KEYS]
        if missing:
            red("C3", "the bridge payload no longer carries %s — the token triple "
                      "is the only thing this contract moves in either direction "
                      "(ROT: update this gate)" % ", ".join(missing))
        if not auth_keys:
            red("C3", "the bridge payload carries no authorization field — the "
                      "host-authoritative half of the contract is gone (ROT)")
        for name, body in readers_scoped.items():
            for key in auth_keys:
                if '"%s"' % key in body:
                    red("C3", "`%s` reads '%s' from the bridge file — %s stay "
                              "authoritative on the host side (the saved config + "
                              "the encrypted secret); a stale or hand-edited bridge "
                              "must not be able to redirect a refresh"
                        % (name, key, "/".join(auth_keys)))

    # -- C2: the guards that make the promotion survive the boundary cases --
    merge_classes = set(DATA_CLASS_RE.findall(merge))
    bridge_types = set()
    for body in readers_scoped.values():
        for cls in merge_classes:
            if re.search(r"\b" + re.escape(cls) + r"\s*\(", body):
                bridge_types.add(cls)
    if not bridge_types:
        red("C2", "the bridge reader builds no snapshot type declared in %s — the "
                  "decision has no input type to check (ROT: re-point this gate)"
            % MERGE_REL)
        return v
    decision = None
    for m in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(([^)]*)\)", merge):
        for cls in bridge_types:
            pm = re.search(r"([A-Za-z_]\w*)\s*:\s*" + re.escape(cls) + r"\b", m.group(2))
            if pm:
                body = body_at(merge, m.start())
                if body:
                    decision = (m.group(1), pm.group(1), body)
                    break
        if decision:
            break
    if decision is None:
        red("C2", "no function in %s decides on a bridge snapshot — the promotion "
                  "rule and its guards are gone (ROT: re-point this gate)" % MERGE_REL)
        return v
    fname, param, body = decision
    # Field names are DERIVED from the reader (which key of the guest file maps
    # to which constructor argument), never spelled by this gate. Then a local
    # assigned from one of those fields is resolved back to its bridge field, so
    # `val a = bridge.accessToken.trim()` followed by `a != stored.accessToken`
    # still counts as "the access token is compared".
    derived = _derive_token_fields(readers_scoped)
    if len(derived) != 3:
        red("C2", "cannot derive the token / expiry field names from the bridge "
                  "reader — the comparisons below have nothing to be checked "
                  "against (ROT: re-point this gate)")
        return v

    alias_fields = {}
    for nm, rhs in LOCAL_VAL_RE.findall(body):
        for key, field in derived.items():
            if re.search(r"\b" + re.escape(param) + r"\." + re.escape(field) + r"\b", rhs):
                alias_fields[nm] = key

    def bridge_field(expr):
        """The bridge field name this expression came from, or None."""
        head = expr.split(".")[0]
        if head == param:
            rest = expr.split(".")[1] if "." in expr else ""
            for key, field in derived.items():
                if rest == field:
                    return key
            return None
        return alias_fields.get(head)

    def other_side_is_not_bridge(expr):
        head = expr.split(".")[0]
        return head != param and head not in alias_fields

    ordering = False
    direction_bad = ""
    compared = set()
    for m in COMPARISON_RE.finditer(body):
        left, op, right = m.group(1), m.group(2), m.group(3)
        for a, b in ((left, right), (right, left)):
            field = bridge_field(a)
            if field is None or not other_side_is_not_bridge(b):
                continue
            if op in ORDERING_OPS:
                ordering = True
                if field == "expires_at" and not direction_bad:
                    cond, consequent = _if_guard_around(body, m.start())
                    ok_dir, why = _ordering_direction(
                        cond, consequent, a == left, op)
                    if not ok_dir:
                        direction_bad = why
            elif op in INEQUALITY_OPS:
                compared.add(field)
    if not ordering:
        red("C2", "`%s` never orders the bridge expiry against the stored copy — a "
                  "bridge left over from BEFORE the host authorized would promote "
                  "the token it already replaced (a rollback, not a refresh)"
            % fname)
    elif direction_bad:
        red("C2", "`%s` %s" % (fname, direction_bad))
    if "access_token" not in compared:
        red("C2", "`%s` never compares the bridge access token against the stored "
                  "one — a same-second refresh has an identical expiry, so ordering "
                  "alone silently stops promoting it" % fname)
    if "refresh_token" not in compared:
        red("C2", "`%s` never compares the bridge refresh token against the stored "
                  "one — under refresh-token rotation the superseded token stays in "
                  "the store and the guest's next refresh is invalid_grant" % fname)
    return v

def _paren_end(text, open_idx):
    """Index of the ')' closing the '(' at [open_idx], or -1.

    [batch5-t11] 配平一律走共享 `match_bracket`（自写扫描循环是本轮被
    见证击穿的一类缺陷：`${}` 模板与字符串里的括号会让深度错位）。
    """
    return match_bracket(text, open_idx) if text[open_idx:open_idx + 1] == "(" else -1


def _paren_args(text, open_idx):
    """Argument text between the '(' at [open_idx] and its matching ')'."""
    close = _paren_end(text, open_idx)
    return None if close < 0 else text[open_idx + 1:close]


def _top_level_parts(args):
    """Split an argument list on top-level commas (shared splitter)."""
    return [args[a:b] for a, b in split_top(args, (",",))]


def _named_value(args, name):
    """Value text of the top-level named argument `name = …`, or None."""
    for part in _top_level_parts(args):
        m = re.match(r"\s*%s\s*=(?!=)\s*(.+?)\s*$" % re.escape(name), part, re.S)
        if m:
            return m.group(1).strip()
    return None


def _expression_body(code, name):
    """Expression body of `fun <name>(…) = …` (None for block bodies)."""
    m = re.search(r"\bfun\s+%s\s*\(" % re.escape(name), code)
    if not m:
        return None
    close = _paren_end(code, m.end() - 1)
    if close < 0:
        return None
    k = close + 1
    while k < len(code) and code[k] in " \t\r\n":
        k += 1
    if k < len(code) and code[k] == ":":
        while k < len(code) and code[k] not in "={":
            k += 1
    if k >= len(code) or code[k] != "=":
        return None
    stops = []
    blank = code.find("\n\n", k)
    if blank >= 0:
        stops.append(blank)
    nxt = re.search(r"\n\s*(?:@\w+\s*)*(?:private |internal |public |override )?fun\s", code[k:])
    if nxt:
        stops.append(k + nxt.start())
    end = min(stops) if stops else len(code)
    return code[k + 1:end]


# ── [wide-1024 R4] the transport decision must be the shared predicate ────
TRANSPORT_ARG = "urlTransport"
_BOOL_LITERAL_RE = re.compile(r"^(?:true|false|Boolean\.(?:TRUE|FALSE))$")


def _transport_decision(code, value):
    """(ok, note): the `urlTransport =` argument must be DERIVED, never a literal.

    [wide-1024 R4] The gate only proved the write paths reach
    `MCPOAuthStore.reconcileForServer(` and that the helper body names it. What
    the helper PASSES was never read, so `urlTransport = true` — the shipped
    pre-fix shape — kept the gate green: a server switched from HTTP/SSE to
    STDIO is reported as a URL transport, `reconcileForServer` skips its PURGE
    branch, and `/var/minis/mcp-servers/oauth/<id>.json` keeps a plaintext
    bearer token forever. The argument must be decided by a predicate whose body
    reads BOTH facts the persisted transport depends on (`isStdio` and `url`).
    """
    if value is None:
        return False, "no `%s =` argument at the call site" % TRANSPORT_ARG
    v = value.strip()
    if _BOOL_LITERAL_RE.match(v):
        return False, ("a literal (`%s`) — the transport decision is hard-coded, so a "
                       "server that became STDIO keeps its live token" % v)
    if re.match(r"^[A-Za-z_]\w*$", v):
        m = re.search(r"\bval\s+%s\b[^=\n]*=\s*([^\n]+)" % re.escape(v), code)
        if m is None:
            return False, "`%s` is not bound in this file (fail-closed)" % v
        v = m.group(1)
    for cm in CALL_RE.finditer(v):
        callee = cm.group(1)
        body = _expression_body(code, callee) or body_of(code, callee)
        if body and "isStdio" in body and ".url" in body:
            return True, "decided by `%s`" % callee
    return False, ("no predicate reading both `isStdio` and `url` decides `%s` — "
                   "the transport must be derived from the server, not restated here"
                   % v.strip()[:60])


def _reconcile_payload_problem(args):
    """[R3-t18 #28] `oauth` / `serverUrl` must describe the server being saved.

    C2 pinned `urlTransport` (the transport decision) but nothing looked at the
    two OTHER inputs that decision is made from. A witness replaced
    `oauth = server.oauth` with `oauth = null`: `reconcileForServer` reads that
    as "the user dropped the OAuth config" and immediately purges the client
    secret, the tokens and the guest bridge file — so every save of an
    authorized server silently destroys its own credentials, while the token
    looks reconciled and every existing arm stays green. The same holds for a
    literal `serverUrl`, which reads as an endpoint change.
    """
    oauth = _named_value(args, "oauth")
    partner = _named_value(args, "server")
    url = _named_value(args, "serverUrl")
    if oauth is None:
        return ("`oauth = …` is not passed — reconcileForServer cannot tell whether the "
                "server still carries an OAuth config, and a missing answer purges the "
                "live credentials")
    if partner is None or url is None:
        return ("`server` / `serverUrl` are not passed — the reconcile payload cannot be "
                "attributed to the server being saved (fail-closed)")
    m = re.match(r"([A-Za-z_]\w*)", partner.strip())
    host = m.group(1) if m else None
    for label, expr in (("oauth", oauth), ("serverUrl", url)):
        if re.fullmatch(r"null", expr.strip()) or const_value(expr.strip()) is not None:
            return ("`%s = %s` is a literal — reconcileForServer decides the server "
                    "cancelled its OAuth config / changed endpoint and PURGES the live "
                    "tokens and the guest bridge file on every save"
                    % (label, expr.strip()[:40]))
        srcs = value_sources(expr)
        if host is None or not any(contains_ident(s, host) for s in srcs):
            return ("`%s` is not derived from the server being saved (it reads `%s`) — "
                    "the reconcile decision is then made about a different server"
                    % (label, expr.strip()[:48]))
    return None


def check_repo(code):
    v = []
    for path in WRITE_PATHS:
        body = body_of(code, path)
        if body is None:
            v.append(
                "MCPRepository.kt: no `fun %s(` with a body — the write path "
                "this gate pins is gone (ROT: update this gate)" % path
            )
            continue
        accepted = (REPO_HELPER_CALL, REPO_FULL_CALL)
        if path == "importJSON":
            accepted = accepted + IMPORT_ALSO_OK
        if not any(tok in body for tok in accepted):
            v.append(
                "MCPRepository.kt: `%s` does not reach %s (%s) — the saved "
                "config and the stored OAuth state drift apart again"
                % (path, PLAY, " or ".join(accepted))
            )
    if REPO_HELPER_DECL in code:
        helper = body_of(code, REPO_HELPER_DECL)
        if helper is None or REPO_FULL_CALL not in helper:
            v.append(
                "MCPRepository.kt: the `%s` indirection does not call `%s` — "
                "the write paths look wired but nothing reconciles"
                % (REPO_HELPER_DECL, REPO_FULL_CALL)
            )
    for idx in range(len(code)):
        if not code.startswith(REPO_FULL_CALL, idx):
            continue
        args = _paren_args(code, idx + len(REPO_FULL_CALL) - 1)
        if args is None:
            v.append(
                "MCPRepository.kt:%d: `%s` could not be delimited — fail-closed"
                % (line_of(code, idx), REPO_FULL_CALL)
            )
            continue
        ok, note = _transport_decision(code, _named_value(args, TRANSPORT_ARG))
        if not ok:
            v.append(
                "MCPRepository.kt:%d: `%s%s` — %s. A STDIO/blank-transport server "
                "must be reconciled as NOT a URL transport, or its token bridge "
                "survives the switch (crack 1)"
                % (line_of(code, idx), REPO_FULL_CALL, TRANSPORT_ARG, note)
            )
        payload = _reconcile_payload_problem(args)
        if payload:
            v.append("MCPRepository.kt:%d: `%s` — %s"
                     % (line_of(code, idx), REPO_FULL_CALL, payload))
    if SECRET_ONLY_VERB in code:
        v.append(
            "MCPRepository.kt:%d: uses `%s` as credential handling — that verb "
            "touches the client secret only; a repository path must go through "
            "%s" % (line_of(code, code.find(SECRET_ONLY_VERB)), SECRET_ONLY_VERB, REPO_FULL_CALL)
        )
    return v


def camel_to_snake(name):
    """`clientId` → `client_id`, `redirectURI` → `redirect_uri`.

    Used to DERIVE which spelling pairs with which: the alias the guest CLI
    writes and the snake_case primary this app writes are two spellings of the
    same field, so neither has to be listed in this gate.
    """
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()

def brace_block(code, open_idx):
    """The `{...}` block starting at [open_idx] (index of the `{`), or None."""
    close = match_bracket(code, open_idx)
    return None if close < 0 else code[open_idx:close + 1]

def cli_oauth_keys(cli):
    """Keys the in-guest CLI writes into a server's `oauth` object, in order."""
    keys = []
    for m in CLI_OAUTH_DICT_RE.finditer(cli):
        block = brace_block(cli, m.end() - 1)
        if block is None:
            continue
        for item in CLI_OAUTH_ITEM_RE.finditer(block):
            if item.group(1) not in keys:
                keys.append(item.group(1))
    for m in CLI_OAUTH_ITEM_SET_RE.finditer(cli):
        if m.group(1) not in keys:
            keys.append(m.group(1))
    return keys

def check_config(code, cli):
    """[dv25-A1] The parse/write key contract of MCPOAuthConfig.

    C1  every key the guest CLI writes is readable here — either spelled the
        same way, or present as an alias whose snake_case primary is a key
        `toJson` writes (so the two ends describe the same field).
    C2  every key table is ordered primary-then-alias (snake_case first).
    C3  every aliased table is actually consumed by `fromJson` (a table the
        parser ignores is a fallback that exists only on paper).
    C4  `toJson` writes only snake_case keys — the on-disk / backup format must
        not fork because the read side learned a second spelling.
    C5  `fromJson` keeps its "no endpoints = noise" null path (every property
        `isConfigured` requires must be blank-checked before returning null).
    """
    v = []
    fromjson = body_of(code, "fromJson")
    tojson = body_of(code, "toJson")
    if fromjson is None:
        return ["MCPOAuthConfig.kt: no `fun fromJson(` with a body — the parse "
                "side of the oauth key contract is gone (ROT: update this gate)"]
    if tojson is None:
        return ["MCPOAuthConfig.kt: no `fun toJson(` with a body — the write "
                "side of the oauth key contract is gone (ROT: update this gate)"]

    written = []
    for m in TOJSON_WRITE_RE.finditer(tojson):
        if m.group(1) not in written:
            written.append(m.group(1))
    if not written:
        return ["MCPOAuthConfig.kt: `toJson` writes no keys — the write side of "
                "the oauth key contract cannot be checked (ROT: update this gate)"]

    cli_keys = cli_oauth_keys(cli)
    if not cli_keys:
        return ["cannot derive the in-guest CLI's oauth keys from %s — the alias "
                "contract has no source of truth (ROT: update this gate)" % CLI_REL]

    tables = []
    for m in KEY_TABLE_RE.finditer(code):
        tables.append((m.group(1), KEY_LITERAL_RE.findall(m.group(2))))

    # C2: direction. Runs before C1's coverage test so an alias promoted to
    # primary is reported as a direction defect (C2 owns it), not as an
    # unreadable key — one arm, one criterion.
    tabled = set()
    for name, keys in tables:
        tabled.update(keys)
        if len(keys) >= 2:
            if camel_to_snake(keys[1]) != keys[0]:
                v.append(
                    "MCPOAuthConfig.kt:%d: key table `%s` is not ordered "
                    "primary-then-alias ('%s' should be the snake_case primary of "
                    "'%s') — with the alias first, a CLI-written block wins over "
                    "the value this app saved"
                    % (line_of(code, code.find(name)), name, keys[0], keys[1])
                )
        elif keys and keys[0] != camel_to_snake(keys[0]) and camel_to_snake(keys[0]) in written:
            v.append(
                "MCPOAuthConfig.kt:%d: key table `%s` reads only '%s', the "
                "CLI's camelCase spelling, with no snake_case primary — the "
                "value this app wrote to disk would be ignored"
                % (line_of(code, code.find(name)), name, keys[0])
            )

    # C1: coverage. A CLI spelling with no home parses blank and takes the
    # whole oauth block down with it.
    for key in cli_keys:
        if key in written or key in tabled:
            continue
        v.append(
            "MCPOAuthConfig.kt: `fromJson` does not read '%s', the key the "
            "in-guest CLI writes for this field (%s) — the whole oauth block "
            "parses blank, isConfigured turns false and MCPRepository.save() "
            "drops it on the next reload" % (key, CLI_REL)
        )

    # C3: wiring. Derive the table names from the file, not from a list here.
    for name, keys in tables:
        if len(keys) < 2 or camel_to_snake(keys[1]) != keys[0]:
            continue
        if re.search(r"\b" + re.escape(name) + r"\b", fromjson) is None:
            v.append(
                "MCPOAuthConfig.kt:%d: key table `%s` is declared but `fromJson` "
                "never consults it — the alias exists only on paper"
                % (line_of(code, code.find(name)), name)
            )

    # C4: the write side stays snake_case (old configs and backups unchanged).
    for key in written:
        if key != key.lower():
            v.append(
                "MCPOAuthConfig.kt:%d: `toJson` writes the camelCase key '%s' — "
                "the disk/backup format must stay snake_case; only the read side "
                "accepts the CLI's spelling" % (line_of(code, tojson.find('"%s"' % key)), key)
            )

    # C5: the null path. Derive the required properties from `isConfigured`'s
    # own getter — scanning the whole file would also catch `it.isNotBlank()`
    # in `toJson`'s takeIf and invent a property named `it`.
    cfg_at = code.find("isConfigured")
    required = []
    if cfg_at >= 0:
        stop = code.find("\n\n", cfg_at)
        scope = code[cfg_at:min(stop, cfg_at + 400) if stop > 0 else cfg_at + 400]
        for m in CONFIGURED_PROP_RE.finditer(scope):
            if m.group(1) not in required:
                required.append(m.group(1))
    if not required:
        v.append(
            "MCPOAuthConfig.kt: cannot derive the required oauth properties from "
            "`isConfigured` — the null path below has nothing to be checked "
            "against (ROT: update this gate)"
        )
        return v
    for prop in required:
        if (prop + ".isBlank()") not in fromjson:
            v.append(
                "MCPOAuthConfig.kt: `fromJson` no longer treats a blank `%s` as "
                "unconfigured — an oauth block with no endpoints would be parsed "
                "as configuration" % prop
            )
    if required and "null" not in fromjson:
        v.append(
            "MCPOAuthConfig.kt: `fromJson` has no null path — an absent/empty "
            "oauth block must still parse as null"
        )
    return v

def balanced(code, start, open_ch, close_ch):
    """Index of the char closing the group opened at [start], or -1."""
    if code[start:start + 1] != open_ch:
        return -1
    return match_bracket(code, start)


def remember_sites(body):
    """[(offset, keys, expr)] for every `remember(...)`/`remember {...}`.

    The identifier must be followed by `(` or `{` — that keeps
    `rememberCoroutineScope(` / `rememberModalBottomSheetState(` out, and the
    calling code is passed already comment/string-masked.
    """
    out = []
    for m in re.finditer(r"\bremember\s*([({])", body):
        i = m.start()
        closer = m.group(1)
        if closer == "{":
            keys = ""
            end = balanced(body, m.end() - 1, "{", "}")
        else:
            kend = balanced(body, m.end() - 1, "(", ")")
            if kend < 0:
                continue
            keys = body[m.end():kend]
            brace = body.find("{", kend)
            end = balanced(body, brace, "{", "}") if brace >= 0 else kend
        if end < 0:
            end = len(body) - 1
        out.append((i, keys, body[i:end + 1]))
    return out


def check_ui(code):
    """[da1029-E] U1 + U2 — the edit sheet must edit the live entry.

    Returns (violations, keyed_field_count) so the real-repo run can report how
    many field states the enumerated check actually walked (a check that lost
    its subject must not read as green).
    """
    v = []
    m = re.search(r"\bfun\s+" + re.escape(UI_FORM_FUN) + r"\s*\(", code)
    if not m:
        v.append(
            "MCPIntegrationsScreen.kt: no `fun %s(` — the form whose "
            "field-state keys this check pins is gone (ROT: update this gate)"
            % UI_FORM_FUN
        )
        return v, 0
    body = body_at(code, m.start())
    body_off = code.find(body, m.start()) if body else -1
    if body is None or body_off < 0:
        v.append(
            "MCPIntegrationsScreen.kt: `%s` has no brace body — this check "
            "cannot see its field states (ROT: update this gate)" % UI_FORM_FUN
        )
        return v, 0
    enforced = 0
    for off, keys, expr in remember_sites(body):
        if UI_ENTRY_PARAM not in expr:
            continue
        line = line_of(code, body_off + off)
        if not any(tok in keys for tok in UI_KEY_TOKENS):
            v.append(
                "MCPIntegrationsScreen.kt:%d: a `remember` seeded from `%s` has "
                "no key on that entry — an entry changed behind the sheet keeps "
                "displaying (and Save writes back) the values it was opened with"
                % (line, UI_ENTRY_PARAM)
            )
        else:
            enforced += 1
    if enforced == 0:
        v.append(
            "MCPIntegrationsScreen.kt: not one keyed `remember` seeded from "
            "`%s` was found in `%s` — the field states moved or the enumerated "
            "check lost its subject (ROT: re-point it)" % (UI_ENTRY_PARAM, UI_FORM_FUN)
        )

    resolve = re.search(
        r"\bval\s+([A-Za-z_]\w*)\s*=\s*" + re.escape(UI_LIST_HOLDER) + r"\.find\s*\{([^}]*)\}",
        code,
    )
    if not resolve:
        v.append(
            "MCPIntegrationsScreen.kt: no `val <live> = %s.find { … }` — the "
            "sheet is handed the tap-time `%s` snapshot, so a write that landed "
            "while the sheet was open gets rolled back by Save"
            % (UI_LIST_HOLDER, UI_ENTRY_PARAM)
        )
        return v, enforced
    live_name = resolve.group(1)
    tail = code[resolve.end():resolve.end() + 40].lstrip()
    # [dv14-B3] close-on-disappear. The elvis alone used to be REQUIRED here —
    # that pinned the defect as the only legal shape (see docstring). Now the
    # elvis is allowed, but the call site must also CLOSE the sheet when the
    # listing no longer contains the captured id: a LaunchedEffect keyed on the
    # listing whose body checks `servers.none { … }` and sets `editServer =
    # null`.
    close = re.search(
        r"LaunchedEffect\([^)]*" + re.escape(UI_LIST_HOLDER) + r"[^)]*\)\s*\{"
        r"[^{}]*" + re.escape(UI_LIST_HOLDER) + r"\.none\s*\{[^{}]*\}"
        r"[^{}]*" + re.escape(UI_ENTRY_PARAM) + r"\s*=\s*null",
        code,
    )
    if close is None:
        if tail.startswith("?:"):
            v.append(
                "MCPIntegrationsScreen.kt: the elvis fallback keeps the edit "
                "sheet open after the entry was deleted externally, and no "
                "close-on-disappear effect was found — Save then stores "
                "setClientSecret under a deleted id (orphan credentials) while "
                "update() returns false and is silently dropped. Require a "
                "LaunchedEffect keyed on the listing whose body checks "
                "`servers.none { … }` and sets `editServer = null`"
            )
        else:
            v.append(
                "MCPIntegrationsScreen.kt: the live-entry lookup has no `?: ` "
                "fallback and no close-on-disappear effect — an entry deleted "
                "while the sheet is open would pass null to %s, silently "
                "turning the edit sheet into the add flow"
                % UI_RESOLVE_PARAM
            )
    if "%s%s," % (UI_LIVE_PASS, live_name) not in code:
        v.append(
            "MCPIntegrationsScreen.kt: `%s` is resolved but never passed as "
            "`%s%s` — the sheet still receives the snapshot"
            % (live_name, UI_LIVE_PASS, live_name)
        )

    # [dv14-B3] U3 — the form must not store credentials under a deleted id.
    # Per call site: every `setClientSecret(` in the form body (Authorize, Save)
    # must have a presence guard between its own click handler's start and the
    # call (`servers.value.none { … }`), refusing instead of aiming at a
    # deleted id. A body-level check is not enough — one handler's guard would
    # satisfy the other's removal (m2 caught that). Fail-closed the other way:
    # no call sites = nothing to orphan.
    guard_pat = re.escape(UI_LIST_HOLDER) + r"\.value\.none\s*\{"
    for occ in re.finditer(re.escape(SECRET_ONLY_VERB), body):
        span_start = body.rfind("onClick", 0, occ.start())
        if span_start < 0:
            span_start = 0
        window = body[span_start:occ.start()]
        found = None
        for gm in re.finditer(guard_pat, window):
            found = gm
        if found is None:
            v.append(
                "MCPIntegrationsScreen.kt:%d: the form stores credentials via "
                "`%s` but the click handler never checks the listing still "
                "contains the id (no `%s` before the call) — an entry deleted "
                "while the sheet was open gets orphan credentials, and "
                "update() returns false and is silently dropped"
                % (
                    line_of(code, body_off + occ.start()),
                    SECRET_ONLY_VERB,
                    UI_LIST_HOLDER + ".value.none {",
                )
            )
            break
        predicate = brace_block(window, found.end() - 1) if window[found.end() - 1] == "{" \
            else None
        id_expr = _secret_id_argument(body, occ.start())
        if not _guard_compares_captured_id(
                predicate[1:-1] if predicate else None, id_expr):
            v.append(
                "MCPIntegrationsScreen.kt:%d: the presence guard does not compare "
                "`it.id` with the id this handler writes under (`%s`) — a predicate "
                "that always (or never) matches either blocks every edit or lets a "
                "deleted id receive the credentials, silently"
                % (line_of(code, body_off + occ.start()), id_expr or "unknown")
            )
            break
    return v, enforced


def _secret_id_argument(body, verb_pos):
    """The id expression handed to the credential setter, or None."""
    paren = body.find("(", verb_pos)
    if paren < 0:
        return None
    args = _paren_args(body, paren)
    if args is None:
        return None
    parts = _top_level_parts(args)
    return parts[1].strip() if len(parts) > 1 else None


def _guard_compares_captured_id(predicate, id_expr):
    """True when the `none { … }` predicate compares the element id against
    [id_expr] — the id the handler is about to write credentials under.

    [wide-1024 U3] The criterion pinned the PREFIX `servers.value.none {` only,
    so any predicate satisfied it: `none { it.id == "" }` refuses every normal
    edit (the listing has no blank id), and `none { it.id != it.id }` is always
    false and lets the save through — a server deleted while the sheet was open
    gets its OAuth credentials written back under a dead id. The predicate must
    compare the element's id with the SAME expression the setter is keyed by.
    """
    if predicate is None or id_expr is None:
        return False
    m = re.match(r"\s*([A-Za-z_]\w*)\s*->", predicate)
    param = m.group(1) if m else "it"
    want = re.sub(r"\s+", "", id_expr)
    if not want or want == param or want.startswith(param + "."):
        return False
    norm = re.sub(r"\s+", "", predicate)
    return bool(
        ("%s.id==%s" % (param, want)) in norm
        or ("%s.id!=%s" % (param, want)) in norm
        or ("%s==%s.id" % (want, param)) in norm
        or ("%s!=%s.id" % (want, param)) in norm
    )


def _skip_sites(code, guard):
    """(live, dead) condition texts of the guards that skip on [guard].

    [batch5-t11] 判谓词，不判拼写。`false && x in DENY`、`(1 == 2) && x in DENY`、
    `!true && x in DENY` 都经共享常量折叠成"永不成立"，`if (dead) { if (x in
    DENY) continue }` 的外层死分支同样让内层不可达 —— 三种拼写同一种缺陷，
    所以走同一个 [guard_feasible] + [path_feasible] 判定，不再各自白名单。
    """
    live, dead = [], []
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(code):
        if guard not in cond:
            continue
        if "continue" not in code[t_start:t_end]:
            continue
        if guard_feasible(cond) and path_feasible(code, t_start):
            live.append(cond.strip())
        else:
            dead.append(cond.strip())
    return live, dead


def check_backup_scope(code):
    """[da1034-A1] A1 — the artifact walk must drop the MCP oauth bridge dir.

    [code] is comment-masked: the deny-set literal and the skip must both be
    real code, so a comment cannot satisfy them. The skip must also be able to
    RUN: a guard that constant-folds to false keeps the `child.name in …`
    spelling while the walk descends into `oauth/` anyway, and a credentials-free
    export then ships every live bearer token in the archive.
    """
    v = []
    m = DENIED_SET_RE.search(code)
    if not m:
        v.append(
            "ArtifactBackupScope.kt: no `<val> = setOf(\"" + OAUTH_DENY_LITERAL
            + "\")` deny-set — the MCP OAuth token bridge "
            "(mcp-servers/oauth/<server>.json holds LIVE access_token / refresh_token "
            "/ client_secret in plaintext) is selected into the artifact archive "
            "again, and from there into every auto-backup and WebDAV copy"
        )
        return v
    name = m.group(1)
    guard = "child.name in %s" % name
    at = code.find(guard)
    live, dead = _skip_sites(code, guard)
    if live:
        return v
    if at < 0:
        v.append(
            "ArtifactBackupScope.kt: %s is declared but the walk never skips on it "
            "(no `%s`) — the deny-set is inert" % (name, guard)
        )
    elif dead:
        v.append(
            "ArtifactBackupScope.kt: `%s` (line %d) is only consulted behind a guard "
            "that can never be true (`%s`) — the deny-set is inert, so "
            "mcp-servers/oauth/<server>.json (live access_token / refresh_token / "
            "client_secret in plaintext) rides into every archive"
            % (guard, line_of(code, at), dead[0])
        )
    else:
        v.append(
            "ArtifactBackupScope.kt: `%s` (line %d) is not a `continue` skip — the "
            "walk still descends into the credential directory"
            % (guard, line_of(code, at))
        )
    return v

def check_backup_import(scoped, masked):
    """[da1034-A1] A2 — Stage 9 must drop mcp/oauth entries before extraction.

    [scoped] keeps string literals (predicate body + report message); [masked]
    blanks them and is used for the structural call-site ordering.
    """
    v = []
    decl = re.search(r"\bfun\s+" + re.escape(OAUTH_ENTRY_PREDICATE) + r"\s*\(", scoped)
    if not decl:
        v.append(
            "ConfigBackup.kt: no `fun " + OAUTH_ENTRY_PREDICATE + "(` — a restore can "
            "no longer tell a bridge entry from a data file, and a pre-fix archive "
            "rewrites this device's live token bridge verbatim"
        )
    else:
        body = body_at(scoped, decl.start())
        if body is None or ('"mcp"') not in body or ('"' + OAUTH_DENY_LITERAL + '"') not in body:
            v.append(
                "ConfigBackup.kt: `" + OAUTH_ENTRY_PREDICATE + "` body must pin BOTH "
                "the \"mcp\" and \"" + OAUTH_DENY_LITERAL + "\" segments — a "
                "predicate that matches nothing (or everything) is not a guard"
            )
    imp = body_of(masked, "import")
    if imp is None:
        v.append("ConfigBackup.kt: no `fun import(` body to inspect")
    else:
        f = imp.find(OAUTH_STRIP_HELPER + "(")
        x = imp.find(OAUTH_EXTRACT_CALL)
        if f < 0:
            v.append(
                "ConfigBackup.kt: import() never calls `" + OAUTH_STRIP_HELPER + "(` — "
                "the artifact archive is extracted as-is"
            )
        elif x < 0:
            v.append(
                "ConfigBackup.kt: import() no longer calls `" + OAUTH_EXTRACT_CALL
                + "` — the artifact stage moved; re-anchor this gate"
            )
        elif f > x:
            v.append(
                "ConfigBackup.kt: `" + OAUTH_STRIP_HELPER + "(` runs AFTER `"
                + OAUTH_EXTRACT_CALL + "` (lines %d/%d relative to import) — extraction "
                "already wrote the live token bridge; the filter must come first"
                % (imp[:f].count("\n") + 1, imp[:x].count("\n") + 1)
            )
        # [da1034-A1] Wiring the filter is not enough — extraction must CONSUME
        # the filtered archive. Binding `filtered` and then extracting the raw
        # temp zip keeps every bridge entry in the stream; the two criteria
        # above are blind to the operand (caught by mutation m2 in
        # probe-a1/mut/mutations.log).
        if f >= 0 and x >= 0:
            # [dv28-a5 A-05] The call grew a third, named argument (the "Stage 7
            # already merged the server list" switch). The output is still the
            # SECOND positional/`dst`-named one, so tolerating exactly one
            # trailing `name = <expr>` keeps this criterion's teeth: the archive
            # that reaches the extraction is still pinned by name.
            m_strip = re.search(
                re.escape(OAUTH_STRIP_HELPER)
                + r"\s*\(\s*(?:[A-Za-z_]\w*\s*=\s*)?[\w.]+\s*,\s*"
                + r"(?:[A-Za-z_]\w*\s*=\s*)?([A-Za-z_]\w*)\s*"
                + r"(?:,\s*[A-Za-z_]\w*\s*=\s*[^,()]+\s*,?\s*)?\)",
                imp,
            )
            if m_strip is None:
                v.append(
                    "ConfigBackup.kt: `" + OAUTH_STRIP_HELPER + "(` call shape not understood "
                    "— cannot tell which archive reaches `" + OAUTH_EXTRACT_CALL + "`; bind "
                    "the output as `" + OAUTH_STRIP_HELPER + "(src, dst)` (fail-closed, "
                    "re-anchor this gate if the call was intentionally reshaped)"
                )
            else:
                dst = m_strip.group(1)
                if not re.search(
                    re.escape(OAUTH_EXTRACT_CALL) + r"\s*" + re.escape(dst) + r"\b",
                    imp[m_strip.end():],
                ):
                    v.append(
                        "ConfigBackup.kt: import() extracts something other than the filtered "
                        "archive — `" + OAUTH_EXTRACT_CALL + dst + ", …)` must be the "
                        "oauth-stripped zip; the raw temp zip still carries the mcp/oauth "
                        "bridge entries"
                    )
    imp_scoped = body_of(scoped, "import")
    if imp_scoped is not None and OAUTH_SKIP_REPORT not in imp_scoped:
        v.append(
            "ConfigBackup.kt: import() drops bridge entries without reporting it — "
            "\"" + OAUTH_SKIP_REPORT + "\" must reach `skipped`"
        )
    return v

def argument_text(code, call_start):
    """Parenthesised argument list of the call starting at [call_start]."""
    lp = code.find("(", call_start)
    if lp < 0:
        return ""
    close = match_bracket(code, lp)
    return code[lp:] if close < 0 else code[lp:close + 1]


# [dv28-a5 A-05] One file, two restore stages. Stage 7 merges the backup's
# server list into the live one (`MCPRepository.importJSON` keeps the rows the
# backup does not carry); Stage 9 then wrote `mcp/servers.json` out of the
# artifact archive verbatim, replacing the merged file with the backup's list.
# The target device's own servers disappeared from DISK — the in-memory merge
# hid it until the next cold start re-read servers.json.
STAGE7_MERGE_CALL = "mcpRepo.importJSON("
SERVER_LIST_ENTRY_LITERAL = "servers.json"
STAGE9_SKIP_DECL = "Stage 7 merge"
STANDALONE_RELOAD = "reloadFromDisk("
# Tokens that carry no decision — dropped before comparing the "which operands
# gate the merge" sets, so `mcpRepo != null` cannot be satisfied by `null`
# appearing on both sides.
KOTLIN_NOISE_TOKENS = frozenset((
    "true", "false", "null", "is", "as", "in", "if", "else", "return", "it",
    "this", "length", "size", "isEmpty", "isNotEmpty", "optJSONArray",
))


def _match_paren(code, lp):
    """Index of the `)` that closes the `(` at [lp], or -1."""
    return match_bracket(code, lp)


def _top_level_args(call_text):
    """Top-level arguments of a parenthesised call text, or []."""
    if not call_text.startswith("(") or not call_text.endswith(")"):
        return []
    inner = call_text[1:-1]
    return [inner[a:b].strip() for a, b in split_top(inner, (",",))]


def _enclosing_guard_identifiers(body, pos):
    """Identifiers of the innermost `if (…)` whose braces span [pos].

    Derived, never enumerated: whatever expression the Stage 7 merge is gated
    on is exactly the set of operands the Stage 9 skip has to be derived from.
    "Enclosing" means the condition closes before [pos] AND the `{…}` block that
    follows it still contains [pos] — a later `if` whose condition happens to end
    after [pos] gates nothing here.
    """
    best = set()
    for m in re.finditer(r"\bif\s*\(", body):
        lp = body.find("(", m.start())
        if lp < 0 or lp > pos:
            continue
        rp = _match_paren(body, lp)
        if rp < 0 or rp > pos:
            continue
        brace = body.find("{", rp)
        if brace < 0:
            continue
        block = brace_block(body, brace)
        if block is None:
            continue
        if not rp < brace < pos < brace + len(block):
            continue
        inner = body[lp + 1:rp]
        best = set(re.findall(r"[A-Za-z_]\w*", inner)) - KOTLIN_NOISE_TOKENS
    return best


def check_stage9_premerged(scoped, masked):
    """[dv28-a5 A-05] The server list must not be written by two stages.

    Derived axes (no line numbers, no member lists):
      * Stage 7's merge call exists in `import` AND is gated by an `if (…)` —
        the identifiers of that guard are the operands Stage 9 has to consult.
      * the pre-extraction filter call carries a THIRD argument whose value is
        an identifier computed inside `import` from (at least two of) those
        same operands — `true`, or a flag that ignores `mcpRepo`, is not a
        decision: dropping unconditionally loses the list from an archive-only
        backup, copying unconditionally erases the merged one.
      * a predicate in the same file drops that ONE entry name (segments
        collapsed), and the filter consults it only behind that switch.
      * the `mcp` prefix must remain an extraction destination (the other MCP
        data files still restore — exclude the file, not the prefix).
      * C-ST9b, two legal shapes, whitelisted: either a re-import + standalone
        `reloadFromDisk()` after extraction, or a `skipped` literal naming the
        file and the stage that restored it.
    """
    v = []
    imp = body_of(masked, "import")
    imp_scoped = body_of(scoped, "import")
    if imp is None or imp_scoped is None:
        return v  # [da1034-A1] A2 already reports the missing body
    merge_pos = imp.find(STAGE7_MERGE_CALL)
    extract_pos = imp.find(OAUTH_EXTRACT_CALL)
    strip_pos = imp.find(OAUTH_STRIP_HELPER)
    if merge_pos < 0 or extract_pos < 0 or strip_pos < 0:
        # No merge stage (or no artifact stage, or A2's "never filtered"):
        # there is no second writer to reason about, and A2 owns those arms.
        return v

    args = _top_level_args(argument_text(imp, strip_pos))
    if len(args) < 3:
        v.append(
            "ConfigBackup.kt: `" + OAUTH_STRIP_HELPER + "(` does not learn that Stage 7 "
            "already merged this backup's server list — the archive's `"
            + SERVER_LIST_ENTRY_LITERAL + "` is still written verbatim over the merged "
            "file, so every server this device has and the backup does not is erased "
            "(on disk; invisible until the next cold start re-reads it)")
        return v

    m_named = re.match(r"([A-Za-z_]\w*)\s*=\s*([\s\S]+)$", args[2])
    expr = (m_named.group(2) if m_named else args[2]).strip()
    guard_ids = _enclosing_guard_identifiers(imp, merge_pos)
    if expr in ("true", "false") or not re.fullmatch(r"[A-Za-z_]\w*", expr):
        v.append(
            "ConfigBackup.kt: the Stage 9 server-list switch is passed as `" + expr
            + "` — a literal is not a decision. An unconditional drop loses the list "
            "from an archive-only backup (no `mcpServers` section: Stage 7 never ran); "
            "an unconditional copy erases the merged one. Derive it from the operands "
            "that gate the Stage 7 merge"
            + (" (" + ", ".join(sorted(guard_ids)) + ")" if guard_ids else ""))
    else:
        decls = re.findall(
            r"\b(?:val|var)\s+" + re.escape(expr)
            + r"\s*(?::\s*[A-Za-z_.]\w*\s*)?=\s*([^\n]+)",
            imp,
        )
        if not decls:
            v.append(
                "ConfigBackup.kt: `" + expr + "` is never computed inside `import` — the "
                "Stage 9 skip cannot be traced back to whether Stage 7 merged the list")
        else:
            ids = set(re.findall(r"[A-Za-z_]\w*", "\n".join(decls))) - KOTLIN_NOISE_TOKENS
            if len(ids & guard_ids) < 2:
                v.append(
                    "ConfigBackup.kt: `" + expr + "` is not derived from the operands that "
                    "gate the Stage 7 merge"
                    + (" (" + ", ".join(sorted(guard_ids)) + ")" if guard_ids else "")
                    + " — need at least two of them (both the repository and the backup "
                    "section decide whether a merge happened)")

    helper = body_of(scoped, OAUTH_STRIP_HELPER)
    sig = re.search(r"\bfun\s+" + re.escape(OAUTH_STRIP_HELPER) + r"\s*\(", scoped)
    params = []
    if sig is not None:
        params = re.findall(r"([A-Za-z_]\w*)\s*:", argument_text(scoped, sig.start()))
    predicates = [
        (name, body, line) for (name, body, line) in functions(scoped)
        if body and ('"' + SERVER_LIST_ENTRY_LITERAL + '"') in body and '"mcp"' in body
    ]
    if not predicates:
        v.append(
            "ConfigBackup.kt: no predicate pins `mcp/" + SERVER_LIST_ENTRY_LITERAL
            + "` — nothing drops the entry Stage 7 already merged, so the verbatim "
            "copy overwrites the merged list")
    elif helper is not None:
        pname = predicates[0][0]
        if len(params) < 3:
            v.append(
                "ConfigBackup.kt: `" + OAUTH_STRIP_HELPER + "` takes no switch for the "
                "pre-merged server list — the drop would run unconditionally and an "
                "archive-only backup (no `mcpServers` section) would lose its list")
        elif not re.search(
            re.escape(params[2]) + r"[\s\S]{0,200}?\b" + re.escape(pname) + r"\s*\(", helper
        ):
            v.append(
                "ConfigBackup.kt: `" + OAUTH_STRIP_HELPER + "` calls `" + pname + "(` "
                "without the `" + params[2] + "` switch in the same condition — the "
                "server-list entry is dropped unconditionally")

    if '"mcp"' not in imp_scoped:
        v.append(
            "ConfigBackup.kt: the `mcp` prefix is no longer an extraction destination — "
            "excluding the whole prefix is not the fix: every other MCP data file the "
            "agent wrote under mcp/ is lost too. Exclude the ONE file, not the prefix")

    if imp.find(STANDALONE_RELOAD, extract_pos) >= 0 and imp.find(
        STAGE7_MERGE_CALL, extract_pos
    ) >= 0:
        # Legal shape B: merge again from the restored file + re-read disk. The
        # reload/import must come AFTER the extraction — an earlier one (Stage
        # 5's `skillRepo.reloadFromDisk()`) says nothing about the artifact stage.
        if imp.find(STANDALONE_RELOAD, extract_pos) < imp.find(STAGE7_MERGE_CALL, extract_pos):
            v.append(
                "ConfigBackup.kt: the post-extraction `" + STANDALONE_RELOAD + "()` runs "
                "BEFORE the re-import — it would publish the pre-extraction list")
    else:
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', imp_scoped)
        if not any(
            SERVER_LIST_ENTRY_LITERAL in s and STAGE9_SKIP_DECL in s for s in literals
        ):
            v.append(
                "ConfigBackup.kt: the skipped server-list copy is not declared — either "
                "re-import after extraction and call `" + STANDALONE_RELOAD + "()`, or "
                "report it through `skipped` with a literal naming the file and the stage "
                "that restored it (e.g. \"artifacts: " + SERVER_LIST_ENTRY_LITERAL
                + " restored via " + STAGE9_SKIP_DECL + " (skipped raw copy)\")")
    return v


def _redacted_return(body):
    """("ok"|"raw"|"unbound"|"no-return", detail) —— 返回值是否来自脱敏后的对象。

    [batch5-t11] `stripExportedMcpSecrets(obj)` 之后 `return bytes` 把内存里的
    脱敏结果整个丢掉：调用在、解析成功路径在、null 失败路径也在，只有 EFFECT
    不在。入参名从 `JSONObject(String(<ident>, …))` 派生、脱敏对象从
    `stripExportedMcpSecrets(<ident>)` 派生，两处都不许本门自己拼。
    """
    m_raw = re.search(r"JSONObject\s*\(\s*(?:String\s*\(\s*)?([A-Za-z_]\w*)", body)
    m_strip = re.search(re.escape(MCP_SECRET_STRIP) + r"\s*\(\s*([A-Za-z_]\w*)", body)
    if not m_raw or not m_strip:
        return "unbound", None
    raw, obj = m_raw.group(1), m_strip.group(1)
    if raw == obj:
        return "unbound", None
    m_try = re.search(r"\btry\s*\{", body)
    if not m_try:
        return "no-return", None
    open_idx = m_try.end() - 1
    close = match_bracket(body, open_idx)
    if close < 0:
        return "no-return", None
    tail = tail_expr(body[open_idx + 1:close])
    tail = re.sub(r"^return\b", "", tail).strip()
    if not tail:
        return "no-return", None
    srcs = value_sources(tail)
    if any(contains_ident(s, raw) for s in srcs):
        return "raw", raw
    if not any(contains_ident(s, obj) for s in srcs):
        return "unbound", obj
    return "ok", None


def check_artifact_redaction(scoped, masked, archiver_masked):
    """[dv20-a1 A-01] The artifact face of a credentials-free backup.

    Returns three violation groups, one per decision:
      r1 the pack loop reaches the redactor `redactMcpArtifactEntry`, gated on
         `!includeSecrets` so an explicit with-credentials export is untouched;
      r2 the redactor DELEGATES to Stage 5's `stripExportedMcpSecrets` (one rule
         for one credential shape) and returns null when the bytes cannot be
         parsed instead of writing them through;
      r3 `writeEntries` drops — never writes — an entry whose transform
         returned null, and reports the drop.

    Anchored on the decision (who redacts, which bytes are dropped), not on a
    call's spelling: the redactor may be passed inline or bound to a local
    first. [scoped] keeps string literals; [masked]/[archiver_masked] blank
    them for the structural work.
    """
    r1, r2, r3 = [], [], []

    # -- R1: the pack face reaches the redactor, gated on credentials-free.
    m_root = re.search(r"val\s+isMcpRoot\s*=", masked)
    if not m_root:
        r1.append(
            "ConfigBackup.kt: no `val isMcpRoot =` in the artifact pack loop — the "
            "mcp artifact root is no longer distinguished; re-anchor this gate"
        )
        return r1, r2, r3
    call = masked.find(ARTIFACT_WRITE_CALL, m_root.end())
    if call < 0:
        r1.append(
            "ConfigBackup.kt: the artifact pack loop never calls `"
            + ARTIFACT_WRITE_CALL + "` — re-anchor this gate"
        )
        return r1, r2, r3
    args = argument_text(masked, call)
    window = masked[m_root.end():call]
    reachable = MCP_ARTIFACT_REDACTOR in args
    if not reachable:
        for ident in set(re.findall(r"\b([A-Za-z_]\w*)\b", args)):
            if re.search(
                r"val\s+" + re.escape(ident) + r"\s*=[\s\S]{0,300}?"
                + re.escape(MCP_ARTIFACT_REDACTOR),
                window,
            ):
                reachable = True
                break
    if not reachable:
        r1.append(
            "ConfigBackup.kt: `" + ARTIFACT_WRITE_CALL + "` is handed no `"
            + MCP_ARTIFACT_REDACTOR + "(` — a credentials-free export archives "
            "mcp-servers/servers.json verbatim, so headers/env/--token= args/url "
            "query keys ride into the zip (and into every WebDAV copy of it)"
        )
    if not re.search(r"!\s*includeSecrets", window):
        r1.append(
            "ConfigBackup.kt: the artifact redactor is not gated on `!includeSecrets` "
            "— an explicit with-credentials export (includeSecrets=true) would be "
            "silently redacted too"
        )

    # -- R2: one rule, and it must fail closed.
    body = body_of(masked, MCP_ARTIFACT_REDACTOR)
    body_scoped = body_of(scoped, MCP_ARTIFACT_REDACTOR)
    if body is None:
        r2.append(
            "ConfigBackup.kt: no `fun " + MCP_ARTIFACT_REDACTOR + "(` — the pack face "
            "has no redactor to hand the archiver; re-anchor this gate"
        )
    else:
        if MCP_SECRET_STRIP + "(" not in body:
            r2.append(
                "ConfigBackup.kt: `" + MCP_ARTIFACT_REDACTOR + "` does not call `"
                + MCP_SECRET_STRIP + "(` — a second, divergent secret rule now sits "
                "next to Stage 5's, which is how the two faces drift apart"
            )
        if body_scoped is not None and ('"headers"' in body_scoped or '"env"' in body_scoped):
            r2.append(
                "ConfigBackup.kt: `" + MCP_ARTIFACT_REDACTOR + "` blanks secret values "
                "itself (it names `headers`/`env`) instead of delegating to "
                + MCP_SECRET_STRIP + " — the two faces will drift"
            )
        if not re.search(r"catch\s*\([^)]*\)\s*\{[^}]*\bnull\b", body):
            r2.append(
                "ConfigBackup.kt: `" + MCP_ARTIFACT_REDACTOR + "` has no parse-failure "
                "path returning null — unredactable bytes are written into a "
                "credentials-free archive verbatim; it must fail closed"
            )
        # [batch5-t11] 脱敏必须作用在"被返回的那份数据"上：`stripExportedMcpSecrets(obj)`
        # 之后 `return bytes` 保留了调用与 null 路径，却把脱敏结果丢掉 ——
        # 与"消毒后用了未消毒的那份"一字不差。
        prov, detail = _redacted_return(body)
        if prov == "raw":
            r2.append(
                "ConfigBackup.kt: `" + MCP_ARTIFACT_REDACTOR + "` returns the raw "
                "`" + str(detail) + "` after stripping the parsed object — the "
                "scrubbed copy is computed and thrown away, so the archive still "
                "carries headers/env/--token= args/url query keys"
            )
        elif prov == "unbound":
            r2.append(
                "ConfigBackup.kt: `" + MCP_ARTIFACT_REDACTOR + "` returns a value that "
                "is not derived from the object it stripped (%s) — re-anchor this "
                "criterion instead of leaving the encryption unverified" % (detail,)
            )
        elif prov == "no-return":
            r2.append(
                "ConfigBackup.kt: `" + MCP_ARTIFACT_REDACTOR + "` has no readable "
                "`try { … }` return — the redacted value cannot be traced "
                "(re-anchor this criterion)"
            )

    # -- R3: the archiver drops a rejected entry and says so.
    wb = body_of(archiver_masked, "writeEntries")
    if wb is None:
        r3.append(
            "ArtifactArchiver.kt: no `fun writeEntries(` — the archive face moved; "
            "re-anchor this gate"
        )
        return r1, r2, r3
    if "transform(" not in wb:
        r3.append(
            "ArtifactArchiver.kt: `writeEntries` never applies its transform — a caller "
            "that hands one is ignored and every entry is archived verbatim"
        )
        return r1, r2, r3
    m_res = re.search(r"val\s+(\w+)\s*=\s*transform\(\s*[^)]*\)", wb)
    if not m_res:
        r3.append(
            "ArtifactArchiver.kt: the transform result is not bound to a local "
            "(`val x = transform(...)`) — nothing can tell a rejected entry from an "
            "accepted one; bind it (fail-closed, re-anchor if intentionally reshaped)"
        )
        return r1, r2, r3
    ident = m_res.group(1)
    if not re.search(r"\b" + re.escape(ident) + r"\s*==\s*null", wb):
        r3.append(
            "ArtifactArchiver.kt: the transform result is never compared to null — a "
            "rejected entry is not dropped"
        )
        return r1, r2, r3
    null_pos = wb.find(ident + " == null")
    next_continue = wb.find("continue", null_pos)
    next_write = wb.find("putNextEntry", null_pos)
    if next_continue < 0 or (0 <= next_write < next_continue):
        r3.append(
            "ArtifactArchiver.kt: a null transform result does not `continue` before "
            "`putNextEntry` — the rejected entry is archived anyway; it must fail closed"
        )
    if "droppedSink" not in wb:
        r3.append(
            "ArtifactArchiver.kt: a dropped entry is not reported to `droppedSink` — the "
            "caller's file/skip accounting silently overcounts the archive"
        )
    return r1, r2, r3


# ── [R3-t18] witness #27: the credential-free export hand-off ─────────────
def check_export_secret_strip(scoped, masked):
    """The ONE production caller of `stripExportedMcpSecrets` must actually run.

    C7 pins the sanitiser's BODY (directly and through
    `redactMcpArtifactEntry`) and C8 pins the artifact archive — but nothing
    looked at the `mcpServers` hand-off in `buildSections`, the only place the
    *manual / auto* export strips credentials. A witness ANDed `&& false` into
    that single `if (!includeSecrets)` and every arm stayed green: a
    "without secrets" export — and every unattended auto backup, which is
    pushed to WebDAV with no prompt at all — then ships plaintext
    `Authorization: Bearer …` headers, env tokens and inline credentials.

    Judged on the EFFECT, not the call: reachable, gated on `!includeSecrets`,
    and scrubbing the very object that `mcpServers.put(…)` writes.
    """
    body = body_of(masked, "buildSections")
    if body is None:
        return ["ConfigBackup.kt: no `fun buildSections(` body — the export hand-off "
                "this criterion pins is gone (re-anchor it; a missing body must not "
                "read as green)"]
    calls = [m for m in re.finditer(re.escape(MCP_SECRET_STRIP) + r"\s*\(", body)]
    if not calls:
        return ["ConfigBackup.kt: buildSections never calls `" + MCP_SECRET_STRIP +
                "(` — a credentials-free export writes mcpRepo.exportServerJSON(…) "
                "verbatim, so every Authorization header / env token / inline "
                "credential rides into the manual export and into every unattended "
                "WebDAV auto backup"]
    v = []
    for m in calls:
        if not path_feasible(body, m.start()):
            v.append(
                "ConfigBackup.kt: the `" + MCP_SECRET_STRIP + "(` call in buildSections "
                "is DEAD-GUARDED (a constant condition can never hold) — the sanitiser "
                "exists, the export never runs it, and the \"without secrets\" payload "
                "carries the plaintext tokens anyway")
            continue
        conds = [c for _kind, c in enclosing_conditions(body, m.start())]
        if not any(re.search(r"!\s*includeSecrets|includeSecrets\s*==\s*false", c)
                   for c in conds):
            v.append(
                "ConfigBackup.kt: `" + MCP_SECRET_STRIP + "(` is not gated on "
                "`!includeSecrets` — the strip must run exactly on the "
                "credentials-free path (unguarded, an explicit with-secrets export is "
                "redacted too, which is the opposite defect)")
            continue
        args = call_args(body, m.end() - 1)
        if not args:
            v.append("ConfigBackup.kt: the `" + MCP_SECRET_STRIP +
                     "(` call cannot be delimited (fail-closed)")
            continue
        obj = body[args[0][0]:args[0][1]].strip()
        if not re.match(r"^[A-Za-z_]\w*$", obj):
            v.append("ConfigBackup.kt: `" + MCP_SECRET_STRIP + "(` scrubs `%s` instead "
                     "of the server entry — the sanitised copy is not the thing that "
                     "reaches mcpServers" % obj[:48])
            continue
        if not re.search(r"\bput\s*\(\s*" + re.escape(obj) + r"\s*\)", body):
            v.append("ConfigBackup.kt: `" + MCP_SECRET_STRIP + "(%s)` scrubs a copy, but a "
                     "different object is what `mcpServers.put(…)` writes — the "
                     "sanitised value is computed and thrown away" % obj)
    return v


# ── [R3-t18] witness #29: the save path must carry the form's OAuth config ─
FORM_BUILDER = "buildServerFromForm"
CONFIG_CTOR = "MCPServerConfig("


def check_form_oauth_persisted(code):
    """`buildServerFromForm()` must hand the real OAuth config to the repository.

    C4/C5 pin the field states (`remember(editServer)`) and the live-entry
    resolution; C1/C2 pin the repository's write paths. The construction in
    between — the only place the form's OAuth block becomes an
    `MCPServerConfig` — was nobody's: a witness replaced
    `oauth = currentOAuthConfig()` with `oauth = null`, so every server saved
    through the UI loses its OAuth config AND `reconcileOAuth` reads the null as
    "OAuth removed" and purges the tokens the user just authorized.
    """
    body = body_of(code, FORM_BUILDER)
    if body is None:
        return ["MCPIntegrationsScreen.kt: no `fun " + FORM_BUILDER + "(` — the save "
                "path this criterion pins is gone (re-anchor it; an absent builder must "
                "not read as green)"]
    ctors = [m.start() for m in re.finditer(re.escape(CONFIG_CTOR), body)]
    if not ctors:
        return ["MCPIntegrationsScreen.kt: `" + FORM_BUILDER + "(` builds no " +
                CONFIG_CTOR + " — the form no longer produces a server config "
                "(re-anchor this gate)"]
    v = []
    url_branch = 0
    for pos in ctors:
        lp = body.find("(", pos)
        args = call_args(body, lp)
        if not args:
            v.append("MCPIntegrationsScreen.kt: a " + CONFIG_CTOR + " call cannot be "
                     "delimited (fail-closed)")
            continue
        named = {}
        for a, b in args:
            seg = body[a:b]
            m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", seg)
            if m:
                named[m.group(1)] = seg[m.end():]
        if "url" not in named:
            continue  # the STDIO branch carries no OAuth config
        url_branch += 1
        oauth = named.get("oauth")
        if oauth is None:
            v.append(
                "MCPIntegrationsScreen.kt: the url-transport " + CONFIG_CTOR + " is built "
                "without `oauth = …` — every MCP server saved through the form loses its "
                "OAuth config, and reconcileOAuth then treats it as OAuth-free and purges "
                "the tokens the user just authorized")
            continue
        if re.fullmatch(r"null", oauth.strip()) or const_value(oauth.strip()) is not None:
            v.append(
                "MCPIntegrationsScreen.kt: `oauth = %s` — a literal, so the form's OAuth "
                "Client ID / endpoints / redirect URI never reach the repository, and the "
                "reconcile purges any live token on save" % oauth.strip()[:40])
            continue
        if not any("currentOAuthConfig" in s for s in value_sources(oauth)):
            v.append(
                "MCPIntegrationsScreen.kt: `oauth = %s` is not built from "
                "currentOAuthConfig() — the persisted config is detached from the fields "
                "the user edited" % oauth.strip()[:48])
    if not url_branch:
        v.append("MCPIntegrationsScreen.kt: `" + FORM_BUILDER + "(` has no url-transport "
                 "branch (no " + CONFIG_CTOR + " with `url =`) — re-anchor this criterion")
    return v


def read(root, rel):
    with open(os.path.join(root, rel), "r", encoding="utf-8") as fh:
        return fh.read()


def run(root):
    """Fail-closed wrapper: a source the shared lexer cannot parse leaves every
    criterion on it unjudgeable, which is a violation — never a silent pass."""
    try:
        return _run(root)
    except KotlinLexError as exc:
        print("FAIL mcp_oauth_lifecycle_check")
        print("  KotlinLexError: %s" % exc)
        print("  the source cannot be lexed, so no criterion on it can be judged "
              "(fail-closed; a source this gate cannot read must not read as green)")
        return 1


def _run(root):
    if not os.path.isdir(root):
        print("mcp_oauth_lifecycle_check: not a directory: %s" % root)
        return 2
    try:
        store = mask(read(root, STORE_REL))
        repo = mask(read(root, REPO_REL))
    except OSError as exc:
        print("mcp_oauth_lifecycle_check: cannot read sources: %s" % exc)
        return 2
    violations = check_store(store) + check_repo(repo)
    # [dv27-A3] the `.secret` handoff (client secret the in-guest CLI could
    # not put into the encrypted store).
    violations += check_secret_handoff(store)
    # [dv26-A2] the guest -> host half of the token bridge (fail-closed: an
    # unreadable policy file is a violation, not a skipped check).
    try:
        store_scoped = mask_comments(read(root, STORE_REL))
        merge = mask(read(root, MERGE_REL))
    except OSError as exc:
        violations.append(
            "[dv26-A2] cannot read the bridge-merge sources (%s) — a token the "
            "in-guest CLI refreshed can be overwritten by the host's pre-refresh "
            "copy again" % exc)
    else:
        violations += check_bridge_promotion(store, store_scoped, merge)
    # [dv25-A1] parse/write key contract (fail-closed: unreadable = violation).
    # Comments are masked, string literals are NOT — the criteria are anchored
    # on the key spellings themselves.
    try:
        config_raw = read(root, CONFIG_REL)
        cli_raw = read(root, CLI_REL)
    except OSError as exc:
        violations.append(
            "[dv25-A1] cannot read the oauth key-contract sources (%s) — a "
            "CLI-written oauth block can evaporate again" % exc
        )
    else:
        violations += check_config(mask_comments(config_raw), cli_raw)
    # [da1034-A1] artifact-backup surface (fail-closed: unreadable = violation).
    try:
        scope_raw = read(root, SCOPE_REL)
        backup_raw = read(root, CONFIG_BACKUP_REL)
        archiver_raw = read(root, ARTIFACT_ARCHIVER_REL)
    except OSError as exc:
        violations.append(
            "[da1034-A1] cannot read the artifact-backup sources (%s) — the oauth "
            "token bridge is no longer guarded on the backup path" % exc
        )
    else:
        violations += check_backup_scope(mask_comments(scope_raw))
        violations += check_backup_import(mask_comments(backup_raw), mask(backup_raw))
        # [dv28-a5 A-05] the same artifact stage must not write the file Stage 7
        # merged (the server list) a second time.
        violations += check_stage9_premerged(mask_comments(backup_raw), mask(backup_raw))
        v_pack, v_rule, v_arch = check_artifact_redaction(
            mask_comments(backup_raw), mask(backup_raw), mask(archiver_raw))
        violations += v_pack + v_rule + v_arch
        # [R3-t18 #27] the credentials-free export hand-off (buildSections).
        violations += check_export_secret_strip(
            mask_comments(backup_raw), mask(backup_raw))
    ui_path = os.path.join(root, UI_REL)
    ui_keyed = None
    if os.path.exists(ui_path):
        ui_v, ui_keyed = check_ui(mask(read(root, UI_REL)))
        violations += ui_v
        # [R3-t18 #29] the form's OAuth config must reach the repository.
        violations += check_form_oauth_persisted(mask(read(root, UI_REL)))
    else:
        # Drift-detector convention: gates in this suite may be run against a
        # mini tree holding only the files a fixture is about. The UI criteria
        # only apply where the screen exists; the CD check on the real repo
        # fails loudly if the file is deleted.
        print("note: %s absent — UI criteria (U1/U2) skipped" % UI_REL)
    if violations:
        print("FAIL mcp_oauth_lifecycle_check")
        for item in violations:
            print("  " + item)
        return 1
    note = "" if ui_keyed is None else "; %d form field state(s) keyed on the entry" % ui_keyed
    print(
        "PASS mcp_oauth_lifecycle_check (one reconcile point; add/update/import "
        "all wired; no secret-only cleanup path; edit sheet edits the live entry; "
        "oauth bridge never archived and never restored; mcp servers.json redacted "
        "on a credentials-free export; the server list Stage 7 merged is never "
        "re-copied over by the artifact stage; every CLI-written oauth key readable by "
        "fromJson with snake_case primary first and the disk format unchanged; "
        "a token the in-guest CLI refreshed is read back, promoted and persisted "
        "before the bridge is re-materialized%s)"
        % note
    )
    return 0


# --------------------------------------------------------------------------
# selftest: every criterion gets a fixture that trips exactly it
# --------------------------------------------------------------------------

GOOD_STORE = """
object MCPOAuthStore {
    private fun secretKey(server: String) = "secret::" + server
    private fun tokensKey(server: String) = "tokens::" + server

    fun setClientSecret(context: Context, server: String, secret: String?) {
        val p = prefs(context)
        if (secret.isNullOrEmpty()) {
            p.edit().remove(secretKey(server)).apply()
        } else {
            p.edit().putString(secretKey(server), secret).apply()
        }
    }

    fun clientSecret(context: Context, server: String): String? {
        importPendingClientSecret(context, server)?.let { return it }
        return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }
    }

    fun importPendingClientSecret(context: Context, server: String): String? {
        val file = File(bridgeDir(context), "$server.secret")
        if (!file.exists()) return null
        val secret = runCatching { file.readText().trim() }.getOrNull()
        if (secret.isNullOrEmpty()) return null
        setClientSecret(context, server, secret)
        runCatching { file.delete() }
        return secret
    }

    fun signOut(context: Context, server: String) {
        importPendingClientSecret(context, server)
        prefs(context).edit().remove(tokensKey(server)).apply()
        deleteBridgeFile(context, server)
    }

    fun purge(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
        deleteBridgeFile(context, server)
    }

    internal enum class ReconcileAction { PURGE, KEEP, REFRESH_BRIDGE }

    internal fun decideReconcileAction(
        urlTransport: Boolean,
        oauth: MCPOAuthConfig?,
        hasTokens: Boolean,
    ): ReconcileAction = when {
        !usableOAuth(urlTransport, oauth) -> ReconcileAction.PURGE
        hasTokens -> ReconcileAction.REFRESH_BRIDGE
        else -> ReconcileAction.KEEP
    }

    fun reconcileForServer(context: Context, server: String, urlTransport: Boolean, oauth: MCPOAuthConfig?, serverUrl: String?) {
        val stored = tokens(context, server)
        when (decideReconcileAction(urlTransport, oauth, stored != null)) {
            ReconcileAction.PURGE -> purge(context, server)
            ReconcileAction.KEEP -> Unit
            ReconcileAction.REFRESH_BRIDGE -> {
                val cfg = requireNotNull(oauth)
                val t = promoteFromBridge(context, server, requireNotNull(stored))
                materializeBridgeFile(context, server, BridgePayload(accessToken = t.accessToken, tokenEndpoint = cfg.tokenEndpoint))
            }
        }
    }

    private fun readBridgeTokenSnapshot(context: Context, server: String): BridgeTokenSnapshot? {
        return runCatching {
            val file = bridgeFile(context, server)
            if (!file.exists()) return@runCatching null
            val o = JSONObject(file.readText())
            BridgeTokenSnapshot(
                accessToken = o.optString("access_token", ""),
                refreshToken = o.optString("refresh_token", "").ifBlank { null },
                expiresAtSeconds = o.optLong("expires_at", 0L),
            )
        }.getOrNull()
    }

    private fun promoteFromBridge(context: Context, server: String, stored: StoredTokens): StoredTokens {
        val bridge = readBridgeTokenSnapshot(context, server) ?: return stored
        val promoted = promoteTokensFromBridge(
            StoredTokenSnapshot(stored.accessToken, stored.refreshToken, stored.expiresAtMs), bridge,
        ) ?: return stored
        val tokens = StoredTokens(promoted.accessToken, promoted.refreshToken, promoted.expiresAtMs)
        setTokens(context, server, tokens)
        return tokens
    }

    fun materializeBridgeFile(context: Context, server: String, payload: BridgePayload) {
        val json = JSONObject().apply {
            put("access_token", payload.accessToken)
            payload.refreshToken?.let { put("refresh_token", it) }
            put("expires_at", payload.expiresAtSeconds)
            put("token_endpoint", payload.tokenEndpoint)
            put("client_id", payload.clientId)
            payload.clientSecret?.let { put("client_secret", it) }
        }
    }

    fun deleteBridgeFile(context: Context, server: String) {
        runCatching { bridgeFile(context, server).delete() }
    }
}
"""

GOOD_REPO = """
class MCPRepository(private val context: Context) {
    fun add(server: MCPServerConfig): Boolean {
        if (server.id.isBlank()) return false
        _servers.value = _servers.value.filter { it.id != server.id } + server
        save()
        reconcileOAuth(server)
        return true
    }

    fun update(server: MCPServerConfig): Boolean {
        if (_servers.value.none { it.id == server.id }) return false
        _servers.value = _servers.value.map { if (it.id == server.id) server else it }
        save()
        reconcileOAuth(server)
        return true
    }

    fun importJSON(text: String): List<MCPServerConfig> {
        val parsed = parseImportRoot(JSONObject(text))
        if (parsed.isEmpty()) return emptyList()
        save()
        for (s in parsed) reconcileOAuth(s)
        return parsed
    }

    companion object {
        // [wide-1024 R4] the shared transport predicate — the reconcile call
        // must PASS this, not a literal (the real file has it in a companion).
        internal fun persistsAsUrlTransport(server: MCPServerConfig): Boolean =
            !server.isStdio && !server.url.isNullOrBlank()
    }

    private fun reconcileOAuth(server: MCPServerConfig) {
        runCatching {
            com.rikkaminis.app.mcp.oauth.MCPOAuthStore.reconcileForServer(
                context = context,
                server = server.id,
                urlTransport = persistsAsUrlTransport(server),
                oauth = server.oauth,
                serverUrl = server.url,
            )
        }.onFailure { Log.w(TAG, "OAuth reconcile failed") }
    }
}
"""

# crack 1, the exact pre-fix shape: a "cleanup" that only drops the secret
BAD_STORE_SECRET_ONLY = GOOD_STORE.replace(
    """    fun setClientSecret(context: Context, server: String, secret: String?) {""",
    """    fun clearOAuth(context: Context, server: String) {
        prefs(context).edit().remove(secretKey(server)).apply()
    }

    fun setClientSecret(context: Context, server: String, secret: String?) {""",
)

# purge outcome silently dropped from the decision
BAD_STORE_NO_PURGE = GOOD_STORE.replace(
    "        !usableOAuth(urlTransport, oauth) -> ReconcileAction.PURGE",
    "        !usableOAuth(urlTransport, oauth) -> ReconcileAction.KEEP",
)

# tokens forgotten, bridge file left in the guest
BAD_STORE_NO_BRIDGE_DELETE = GOOD_STORE.replace(
    """    fun signOut(context: Context, server: String) {
        importPendingClientSecret(context, server)
        prefs(context).edit().remove(tokensKey(server)).apply()
        deleteBridgeFile(context, server)
    }""",
    """    fun signOut(context: Context, server: String) {
        importPendingClientSecret(context, server)
        prefs(context).edit().remove(tokensKey(server)).apply()
    }""",
)

# ── [dv27-A3] `.secret` handoff arms ──────────────────────────────────────
# C5a: the single exit point no longer imports the pending handoff — the
# reader falls back to the stale stored value and the token exchange omits
# `client_secret` (a confidential client answers 401).
BAD_STORE_NO_SECRET_IMPORT = GOOD_STORE.replace(
    """    fun clientSecret(context: Context, server: String): String? {
        importPendingClientSecret(context, server)?.let { return it }
        return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }
    }""",
    """    fun clientSecret(context: Context, server: String): String? =
        prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }""",
)

# C5b: signOut deletes the bridge file BEFORE importing — the handoff file is
# gone by the time the import runs, so the client secret is lost.
BAD_STORE_SIGNOUT_ORDER = GOOD_STORE.replace(
    """    fun signOut(context: Context, server: String) {
        importPendingClientSecret(context, server)
        prefs(context).edit().remove(tokensKey(server)).apply()
        deleteBridgeFile(context, server)
    }""",
    """    fun signOut(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).apply()
        deleteBridgeFile(context, server)
        importPendingClientSecret(context, server)
    }""",
)

# C6: purge imports the handoff — deleting a server would write its secret
# back into the encrypted store, resurrecting an orphan credential.
BAD_STORE_PURGE_IMPORTS = GOOD_STORE.replace(
    """    fun purge(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
        deleteBridgeFile(context, server)
    }""",
    """    fun purge(context: Context, server: String) {
        importPendingClientSecret(context, server)
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
        deleteBridgeFile(context, server)
    }""",
)

# reconcile degenerated into purge-only (crack 2 re-opens)
BAD_STORE_NO_MATERIALIZE = GOOD_STORE.replace(
    "                materializeBridgeFile(context, server, BridgePayload(accessToken = t.accessToken, tokenEndpoint = cfg.tokenEndpoint))\n",
    "",
)

# ── [dv26-A2] guest → host token promotion fixtures ───────────────────────
# The merge decision (Android-free policy file). Two guards carry the rule: an
# expiry ORDERING against the stored copy, and a token INEQUALITY (a same-second
# refresh has an identical expiry), plus the refresh-token rotation half.
GOOD_MERGE = """
internal data class BridgeTokenSnapshot(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtSeconds: Long,
)

internal data class StoredTokenSnapshot(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

internal data class TokenPromotion(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

internal fun promoteTokensFromBridge(
    stored: StoredTokenSnapshot,
    bridge: BridgeTokenSnapshot?,
): TokenPromotion? {
    if (bridge == null) return null
    val bridgeAccess = bridge.accessToken.trim()
    if (bridgeAccess.isEmpty()) return null
    val bridgeRefresh = bridge.refreshToken?.trim()?.takeIf { it.isNotBlank() }
    val bridgeSeconds = if (bridge.expiresAtSeconds > 0) bridge.expiresAtSeconds else 0L
    val storedSeconds = if (stored.expiresAtMs > 0) stored.expiresAtMs / 1000 else 0L
    if (bridgeSeconds < storedSeconds) return null
    val replaced = bridgeAccess != stored.accessToken
    val rotated = bridgeRefresh != null && bridgeRefresh != stored.refreshToken
    if (!replaced && !rotated) return null
    return TokenPromotion(
        accessToken = bridgeAccess,
        refreshToken = bridgeRefresh ?: stored.refreshToken,
        expiresAtMs = bridgeSeconds * 1000,
    )
}
"""

# the pre-fix shape: the promotion is there but never orders against the host
# copy — an OLDER bridge would overwrite the token the app just authorized with
BAD_MERGE_NO_ORDERING = GOOD_MERGE.replace(
    "    if (bridgeSeconds < storedSeconds) return null\n",
    "",
)

# the same-second refresh: the ordering guard stays, the refresh comparison goes
BAD_MERGE_NO_REFRESH_CMP = GOOD_MERGE.replace(
    "    val rotated = bridgeRefresh != null && bridgeRefresh != stored.refreshToken\n",
    "    val rotated = false\n",
)

# ... and the same for the access-token comparison
BAD_MERGE_NO_ACCESS_CMP = GOOD_MERGE.replace(
    "    val replaced = bridgeAccess != stored.accessToken\n",
    "    val replaced = false\n",
)

# [wide-1024 C2] witness 2: the comparison survives, its DIRECTION is flipped —
# every bridge the guest just refreshed takes the early exit.
BAD_MERGE_FLIPPED_ORDERING = GOOD_MERGE.replace(
    "if (bridgeSeconds < storedSeconds) return null",
    "if (bridgeSeconds >= storedSeconds) return null",
)

# the baseline shape: reconcile materializes the prefs copy straight away
BAD_STORE_NO_PROMOTION = GOOD_STORE.replace(
    "                val t = promoteFromBridge(context, server, requireNotNull(stored))\n"
    "                materializeBridgeFile(context, server, BridgePayload(accessToken = t.accessToken, tokenEndpoint = cfg.tokenEndpoint))",
    "                materializeBridgeFile(context, server, BridgePayload(accessToken = stored!!.accessToken, tokenEndpoint = cfg.tokenEndpoint))",
)

# read but never persisted: the next save reads the superseded triple back and
# loses the promotion, so the bridge keeps flapping
BAD_STORE_UNPERSISTED = GOOD_STORE.replace(
    "        setTokens(context, server, tokens)\n        return tokens",
    "        return tokens",
)

# the authority leak: the reader parses an endpoint out of the guest file
BAD_STORE_READS_ENDPOINT = GOOD_STORE.replace(
    '                expiresAtSeconds = o.optLong("expires_at", 0L),',
    '                expiresAtSeconds = o.optLong("expires_at", 0L),\n'
    '                tokenEndpoint = o.optString("token_endpoint", ""),',
)

# not one function reads the file: the bridge is write-only again
BAD_STORE_NO_FILE_READ = GOOD_STORE.replace(
    "            val o = JSONObject(file.readText())",
    "            val o = JSONObject()",
)

BAD_REPO_ADD_UNWIRED = GOOD_REPO.replace("        reconcileOAuth(server)\n        return true\n    }\n\n    fun update(",
                                         "        return true\n    }\n\n    fun update(")
BAD_REPO_UPDATE_UNWIRED = GOOD_REPO.replace("""        _servers.value = _servers.value.map { if (it.id == server.id) server else it }
        save()
        reconcileOAuth(server)
        return true""", """        _servers.value = _servers.value.map { if (it.id == server.id) server else it }
        save()
        return true""")
BAD_REPO_IMPORT_UNWIRED = GOOD_REPO.replace("        for (s in parsed) reconcileOAuth(s)\n", "")
BAD_REPO_HELPER_DEAD = GOOD_REPO.replace(
    "            com.rikkaminis.app.mcp.oauth.MCPOAuthStore.reconcileForServer(",
    "            com.rikkaminis.app.mcp.oauth.MCPOAuthStore.clientSecret(",
)
BAD_REPO_SECRET_VERB = GOOD_REPO.replace(
    "    private fun reconcileOAuth(server: MCPServerConfig) {",
    """    private fun legacyClear(server: MCPServerConfig) {
        com.rikkaminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(context, server.id, null)
    }

    private fun reconcileOAuth(server: MCPServerConfig) {""",
)

# ── [da1029-E] UI fixtures ────────────────────────────────────────────────
GOOD_UI = """
@Composable
fun MCPIntegrationsScreen(mcpRepository: MCPRepository) {
    val servers by mcpRepository.servers.collectAsState()
    var editServer by remember { mutableStateOf<MCPRepository.MCPServerConfig?>(null) }
    if (editServer != null) {
        editServer?.let { captured ->
            val liveServer = servers.find { it.id == captured.id } ?: captured
            LaunchedEffect(captured.id, servers) {
                if (servers.none { it.id == captured.id }) editServer = null
            }
            MCPAddSheet(
                mcpRepository = mcpRepository,
                editServer = liveServer,
                onDismiss = { editServer = null },
                onRequestDelete = { deleteId = liveServer.id },
            )
        }
    }
}

@Composable
private fun MCPFormTab(editServer: MCPRepository.MCPServerConfig?) {
    var transport by remember(editServer) { mutableIntStateOf(if (editServer?.isStdio == true) 2 else 0) }
    var name by remember(editServer) { mutableStateOf(editServer?.id ?: "") }
    var oauthClientSecret by remember(editServer) {
        mutableStateOf(editServer?.id?.let { MCPOAuthStore.clientSecret(context, it) } ?: "")
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (isEdit && servers.value.none { it.id == server.id }) {
            AppLogger.warning("MCPSheet", "deleted while open; save refused")
            return
        }
        com.rikkaminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
            context, server.id, null,
        )
    }
}
"""

# one field left un-keyed (the "15 of 16" shape)
BAD_UI_UNKEYED = GOOD_UI.replace(
    '    var name by remember(editServer) { mutableStateOf(editServer?.id ?: "") }',
    '    var name by remember { mutableStateOf(editServer?.id ?: "") }',
)

# the pre-fix shape: the sheet gets the tap-time snapshot
BAD_UI_SNAPSHOT = GOOD_UI.replace(
    "            val liveServer = servers.find { it.id == captured.id } ?: captured\n",
    "",
).replace("editServer = liveServer,", "editServer = captured,")

# live value resolved but never passed through
BAD_UI_NOT_PASSED = GOOD_UI.replace("editServer = liveServer,", "editServer = captured,")

# [dv14-B3] the orphan-credential shape: elvis keeps the sheet in edit mode and
# the close-on-disappear effect is gone — Save writes setClientSecret under a
# deleted id while update() returns false, silently dropped
BAD_UI_ORPHAN = GOOD_UI.replace(
    """            LaunchedEffect(captured.id, servers) {
                if (servers.none { it.id == captured.id }) editServer = null
            }
""",
    "",
)

# resolved without the elvis fallback AND without the close effect (deleted
# entry ⇒ add flow AND orphan credentials)
BAD_UI_NO_FALLBACK = BAD_UI_ORPHAN.replace(
    "servers.find { it.id == captured.id } ?: captured",
    "servers.find { it.id == captured.id }",
)

# [dv14-B3] U3: the form stores credentials but never checks the listing still
# contains the id (the presence guard is gone)
BAD_UI_NO_REFUSE = GOOD_UI.replace(
    """        if (isEdit && servers.value.none { it.id == server.id }) {
            AppLogger.warning("MCPSheet", "deleted while open; save refused")
            return
        }
""",
    "",
)

# [wide-1024 U3] witness 3: the presence guard is there, the predicate is not
# about the captured id (refuses every edit, or lets a dead id through).
BAD_UI_HOLLOW_PREDICATE = GOOD_UI.replace(
    "servers.value.none { it.id == server.id }",
    'servers.value.none { it.id == "" }',
)

# the form is gone entirely (anchor drift)
BAD_UI_NO_FORM = GOOD_UI.replace("private fun MCPFormTab(", "private fun MCPFormBody(")

# the comment trap: docs describing the old shape / claiming the fix
COMMENTED_UI = GOOD_UI.replace(
    "private fun MCPFormTab(",
    """// the old shape was `var name by remember { mutableStateOf(editServer?.id ?: "") }`
private fun MCPFormTab(""",
)
LYING_UI = BAD_UI_UNKEYED.replace(
    "private fun MCPFormTab(",
    """// every field is now keyed on editServer — no un-keyed remember remains
private fun MCPFormTab(""",
)


# [wide-1024 R4] witness 1: the convergence call is made, but the transport is
# hard-coded — a server switched to STDIO is still reported as a URL transport
# and its plaintext token bridge survives the switch.
BAD_REPO_TRANSPORT_LITERAL = GOOD_REPO.replace(
    "urlTransport = persistsAsUrlTransport(server),",
    "urlTransport = true,",
)

# -- [da1034-A1] artifact-backup fixtures ------------------------------------

GOOD_SCOPE = """
object ArtifactBackupScope {
    private val EXCLUDED_DIRS = setOf("sandbox-env")
    private val DENIED_DIRS = setOf("oauth")

    private fun walk(dir: java.io.File, prefix: String, out: MutableList<String>) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                if (child.name in DENIED_DIRS) continue
                if (prefix.isEmpty() && child.name in EXCLUDED_DIRS) continue
                walk(child, prefix + "/" + child.name, out)
                continue
            }
            out.add(prefix + "/" + child.name)
        }
    }
}
"""
DENY_VAL = "    private val DENIED_DIRS = setOf(\"oauth\")\n"
DENY_GUARD = "                if (child.name in DENIED_DIRS) continue\n"
BAD_SCOPE_NO_DENY = GOOD_SCOPE.replace(DENY_VAL, "").replace(DENY_GUARD, "")
BAD_SCOPE_DEAD_DENY = GOOD_SCOPE.replace(DENY_GUARD, "")
BAD_SCOPE_COMMENT_ONLY = GOOD_SCOPE.replace(DENY_VAL, "// DENIED_DIRS was here\n").replace(DENY_GUARD, "")

GOOD_BACKUP = """
object ConfigBackup {
    suspend fun import(tmp: java.io.File, artifactRoots: List<java.io.File>?, skipped: ArrayList<String>, root: JSONObject, mcpRepo: MCPRepository?) {
        val mcpArr = root.optJSONArray("mcpServers")
        val serverListMergedInStage7 = mcpRepo != null && mcpArr != null && mcpArr.length() > 0
        if (mcpArr != null && mcpRepo != null) {
            mcpRepo.importJSON(mcpArr.toString())
        }
        val destByPrefix = HashMap<String, java.io.File>()
        for (root in artifactRoots!!) {
            val prefix = if (root.name == "mcp-servers") "mcp" else "shared"
            destByPrefix[prefix] = root
        }
        val filtered = java.io.File.createTempFile("artifact-restore-", ".zip")
        try {
            val filterResult = stripMcpOAuthEntries(
                tmp,
                filtered,
                dropPrestagedServerList = serverListMergedInStage7,
            )
            if (filterResult.oauthDropped > 0) {
                skipped.add("artifacts: skipped ${filterResult.oauthDropped} oauth bridge entries")
            }
            if (filterResult.prestagedDropped > 0) {
                skipped.add("artifacts: servers.json restored via Stage 7 merge (skipped raw copy)")
            }
            artifactFilesImported = ArtifactArchiver.extractZipByPrefix(filtered, destByPrefix)
        } finally {
            runCatching { filtered.delete() }
        }
    }

    internal fun stripMcpOAuthEntries(
        src: java.io.File,
        dst: java.io.File,
        dropPrestagedServerList: Boolean = false,
    ): Int {
        var dropped = 0
        if (isMcpOAuthEntryName(src.name)) dropped++
        if (dropPrestagedServerList && isMcpServerListEntryName(src.name)) dropped++
        return dropped
    }

    internal fun isMcpOAuthEntryName(name: String): Boolean {
        val segs = ArrayList<String>(4)
        for (seg in name.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            segs.add(seg)
        }
        return segs.size >= 2 && segs[0] == "mcp" && segs[1] == "oauth"
    }

    suspend fun buildSections(artifactRoots: java.io.File?, includeSecrets: Boolean, mcpRepo: MCPRepository?) {
        val mcpServers = JSONArray()
        for (server in mcpRepo.servers.value) {
            val raw = mcpRepo.exportServerJSON(server) ?: continue
            val obj = JSONObject(raw)
            if (!includeSecrets) stripExportedMcpSecrets(obj)
            mcpServers.put(obj)
        }
    }

    internal fun stripExportedMcpSecrets(obj: JSONObject) {
        obj.remove("headers")
    }

    internal fun isMcpServerListEntryName(name: String): Boolean {
        val segs = ArrayList<String>(4)
        for (seg in name.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            segs.add(seg)
        }
        return segs.size == 2 && segs[0] == "mcp" && segs[1] == "servers.json"
    }
}
"""
WIRED = """            val filterResult = stripMcpOAuthEntries(
                tmp,
                filtered,
                dropPrestagedServerList = serverListMergedInStage7,
            )
            if (filterResult.oauthDropped > 0) {
                skipped.add("artifacts: skipped ${filterResult.oauthDropped} oauth bridge entries")
            }
            if (filterResult.prestagedDropped > 0) {
                skipped.add("artifacts: servers.json restored via Stage 7 merge (skipped raw copy)")
            }
            artifactFilesImported = ArtifactArchiver.extractZipByPrefix(filtered, destByPrefix)"""
BAD_BACKUP_NO_WIRING = GOOD_BACKUP.replace(WIRED, "            artifactFilesImported = ArtifactArchiver.extractZipByPrefix(tmp, destByPrefix)")
BAD_BACKUP_AFTER_EXTRACT = GOOD_BACKUP.replace(
    WIRED,
    """            artifactFilesImported = ArtifactArchiver.extractZipByPrefix(filtered, destByPrefix)
            val filterResult = stripMcpOAuthEntries(
                tmp,
                filtered,
                dropPrestagedServerList = serverListMergedInStage7,
            )
            if (filterResult.oauthDropped > 0) {
                skipped.add("artifacts: skipped ${filterResult.oauthDropped} oauth bridge entries")
            }
            if (filterResult.prestagedDropped > 0) {
                skipped.add("artifacts: servers.json restored via Stage 7 merge (skipped raw copy)")
            }""")
BAD_BACKUP_HOLLOW_PREDICATE = GOOD_BACKUP.replace(
    'segs[0] == "mcp" && segs[1] == "oauth"', 'segs[0] == "mcp" && segs[1] == "nope"')
BAD_BACKUP_NO_REPORT = GOOD_BACKUP.replace(
    '            if (filterResult.oauthDropped > 0) {\n'
    '                skipped.add("artifacts: skipped ${filterResult.oauthDropped} oauth bridge entries")\n'
    '            }\n', "")
# [da1034-A1] 变异 m2 的形状：filter 仍在、顺序也对，但抽取吃的是原始 tmp。
BAD_BACKUP_EXTRACT_RAW = GOOD_BACKUP.replace(
    "ArtifactArchiver.extractZipByPrefix(filtered, destByPrefix)",
    "ArtifactArchiver.extractZipByPrefix(tmp, destByPrefix)")

# -- [dv28-a5 A-05] pre-merged server list fixtures --------------------------

STAGE9_PREDICATE_BRANCH = (
    "        if (dropPrestagedServerList && isMcpServerListEntryName(src.name)) dropped++\n")
# the drop is gone entirely (a restore overwrites what Stage 7 merged)
# ── [R3-t18 #27] the credentials-free export hand-off (buildSections) ─────
BAD_BACKUP_STRIP_DEAD = GOOD_BACKUP.replace(
    "            if (!includeSecrets) stripExportedMcpSecrets(obj)",
    "            if (!includeSecrets && false) stripExportedMcpSecrets(obj)")
BAD_BACKUP_STRIP_ABSENT = GOOD_BACKUP.replace(
    "            if (!includeSecrets) stripExportedMcpSecrets(obj)\n", "")
BAD_BACKUP_STRIP_COPY = GOOD_BACKUP.replace(
    "stripExportedMcpSecrets(obj)", "stripExportedMcpSecrets(obj.copy())")
BAD_BACKUP_STRIP_UNGATED = GOOD_BACKUP.replace(
    "            if (!includeSecrets) stripExportedMcpSecrets(obj)",
    "            stripExportedMcpSecrets(obj)")

# ── [R3-t18 #28] the reconcile payload (oauth / serverUrl) ────────────────
BAD_REPO_OAUTH_NULL = GOOD_REPO.replace("oauth = server.oauth,", "oauth = null,")
BAD_REPO_URL_LITERAL = GOOD_REPO.replace("serverUrl = server.url,", 'serverUrl = "",')

# ── [R3-t18 #29] the form's OAuth config must reach the repository ────────
GOOD_FORM = """
class Screen {
    fun buildServerFromForm(): MCPServerConfig? {
        return if (isUrlTransport) {
            MCPServerConfig(
                id = name.trim(),
                url = url.trim(),
                oauth = currentOAuthConfig(),
            )
        } else {
            MCPServerConfig(
                id = name.trim(),
                command = command.trim(),
            )
        }
    }
}
"""
BAD_FORM_OAUTH_NULL = GOOD_FORM.replace("oauth = currentOAuthConfig(),", "oauth = null,")
BAD_FORM_OAUTH_DROPPED = GOOD_FORM.replace("                oauth = currentOAuthConfig(),\n", "")
BAD_FORM_OAUTH_DETACHED = GOOD_FORM.replace(
    "oauth = currentOAuthConfig(),", "oauth = rememberedLastSaveOauth,")


BAD_STAGE9_NO_DROP = GOOD_BACKUP.replace(STAGE9_PREDICATE_BRANCH, "")
# the skip is only DESCRIBED — a comment cannot satisfy a criterion
BAD_STAGE9_COMMENT_ONLY = GOOD_BACKUP.replace(
    STAGE9_PREDICATE_BRANCH,
    "        // mcp/servers.json is skipped here — Stage 7 already merged it\n")
# the switch is a literal: unconditional drop loses an archive-only backup's list
BAD_STAGE9_UNCONDITIONAL = GOOD_BACKUP.replace(
    "dropPrestagedServerList = serverListMergedInStage7,",
    "dropPrestagedServerList = true,")
# the flag exists but ignores whether a merge happened
BAD_STAGE9_NO_OPERAND = GOOD_BACKUP.replace(
    "val serverListMergedInStage7 = mcpRepo != null && mcpArr != null && mcpArr.length() > 0",
    "val serverListMergedInStage7 = true")
# the predicate pins a name that is not the server list
BAD_STAGE9_HOLLOW_PREDICATE = GOOD_BACKUP.replace(
    'segs.size == 2 && segs[0] == "mcp" && segs[1] == "servers.json"',
    'segs.size == 2 && segs[0] == "mcp" && segs[1] == "other.json"')
# "fix" by never routing the mcp prefix: every other MCP data file is lost too
BAD_STAGE9_WHOLE_PREFIX = GOOD_BACKUP.replace(
    'val prefix = if (root.name == "mcp-servers") "mcp" else "shared"',
    'val prefix = "shared"')
# nothing tells the user the entry was skipped
BAD_STAGE9_NO_DECL = GOOD_BACKUP.replace(
    '            if (filterResult.prestagedDropped > 0) {\n'
    '                skipped.add("artifacts: servers.json restored via Stage 7 merge (skipped raw copy)")\n'
    '            }\n', "")

# -- [dv20-a1 A-01] artifact-face fixtures -----------------------------------

GOOD_PACK = """
object ConfigBackup {
    suspend fun buildSections(artifactRoots: List<java.io.File>?, includeSecrets: Boolean) {
        if (artifactRoots != null && artifactRoots.isNotEmpty()) {
            for (root in artifactRoots) {
                val isMcpRoot = root.name == "mcp-servers"
                val prefix = if (isMcpRoot) "mcp" else "shared"
                val transform =
                    if (isMcpRoot && !includeSecrets) ::redactMcpArtifactEntry else null
                val dropped = ArrayList<String>()
                totalBytes += ArtifactArchiver.writeEntries(
                    zos, root, sel.files, prefix + "/", transform, dropped,
                )
                fileCount += sel.files.size - dropped.size
                artifactSkipped += dropped.size
            }
        }
    }

    internal fun redactMcpArtifactEntry(relPath: String, bytes: ByteArray): ByteArray? {
        if (relPath != "servers.json") return bytes
        return try {
            val obj = org.json.JSONObject(String(bytes))
            stripExportedMcpSecrets(obj)
            obj.toString(2).toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    internal fun stripExportedMcpSecrets(obj: org.json.JSONObject) { }
}
"""

BAD_PACK_NO_REDACTOR = GOOD_PACK.replace(
    "if (isMcpRoot && !includeSecrets) ::redactMcpArtifactEntry else null",
    "null")
BAD_PACK_UNGATED = GOOD_PACK.replace(
    "if (isMcpRoot && !includeSecrets) ::redactMcpArtifactEntry else null",
    "if (isMcpRoot) ::redactMcpArtifactEntry else null")
BAD_PACK_NO_STRIP = GOOD_PACK.replace("            stripExportedMcpSecrets(obj)\n", "")
BAD_PACK_INLINE_RULE = GOOD_PACK.replace(
    "            stripExportedMcpSecrets(obj)\n",
    '            val section = obj.optJSONObject("headers")\n'
    '            if (section != null) for (k in section.keys()) section.put(k, "")\n')
BAD_PACK_NO_FAILCLOSED = GOOD_PACK.replace(
    "        } catch (t: Throwable) {\n            null\n        }",
    "        } catch (t: Throwable) {\n            bytes\n        }")
BAD_PACK_NO_ROOT = GOOD_PACK.replace('val isMcpRoot = root.name == "mcp-servers"', 'val isMcp = 1')
# [batch5-t11] 脱敏调用在、null 路径在，只有返回值换成了未脱敏的原始入参。
BAD_PACK_RETURNS_RAW = GOOD_PACK.replace(
    "            obj.toString(2).toByteArray()",
    "            bytes",
)

GOOD_ARCHIVER = """
object ArtifactArchiver {
    fun writeEntries(
        zos: java.util.zip.ZipOutputStream,
        root: java.io.File,
        files: List<ArtifactBackupScope.SelectedFile>,
        entryPrefix: String = "",
        transform: ((String, ByteArray) -> ByteArray?)? = null,
        droppedSink: MutableList<String>? = null,
    ): Long {
        var totalBytes = 0L
        for (f in files) {
            val src = java.io.File(root, f.relPath)
            if (!src.isFile) continue
            var bytes = src.readBytes()
            if (transform != null) {
                val transformed = transform(f.relPath, bytes)
                if (transformed == null) {
                    droppedSink?.add(f.relPath)
                    continue
                }
                bytes = transformed
            }
            zos.putNextEntry(java.util.zip.ZipEntry(entryPrefix + f.relPath))
            zos.write(bytes)
            zos.closeEntry()
            totalBytes += bytes.size
        }
        return totalBytes
    }
}
"""

ARCHIVER_DROP = """                if (transformed == null) {
                    droppedSink?.add(f.relPath)
                    continue
                }
"""
BAD_ARCHIVER_NO_NULL_CHECK = GOOD_ARCHIVER.replace(ARCHIVER_DROP, "")
BAD_ARCHIVER_NO_SINK = GOOD_ARCHIVER.replace("                    droppedSink?.add(f.relPath)\n", "")
BAD_ARCHIVER_NO_TRANSFORM = GOOD_ARCHIVER.replace(
    """            if (transform != null) {
                val transformed = transform(f.relPath, bytes)
                if (transformed == null) {
                    droppedSink?.add(f.relPath)
                    continue
                }
                bytes = transformed
            }
""", "")
BAD_ARCHIVER_NO_FUN = GOOD_ARCHIVER.replace("fun writeEntries(", "fun writeEntriesX(")


# [dv25-A1] fixtures for check_config: MCPOAuthConfig's parse/write key
# contract. GOOD mirrors the shipped shape; each BAD trips exactly one
# criterion group.
GOOD_CLI = '''
        oauth = {
            "mode": "static",
            "clientId": oauth_client_id,
            "authorizationEndpoint": oauth_auth_endpoint,
            "tokenEndpoint": oauth_token_endpoint,
        }
        if oauth_scopes:
            oauth["scopes"] = oauth_scopes
        if oauth_redirect:
            oauth["redirectURI"] = oauth_redirect
'''

CLI_WITH_EXTRA_KEY = GOOD_CLI + '''
        if oauth_device_code:
            oauth["deviceAuthorizationEndpoint"] = oauth_device_code
'''

GOOD_CONFIG = """
data class MCPOAuthConfig(
    val mode: String = "static",
    val clientId: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val scopes: String? = null,
    val redirectUri: String? = null,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            authorizationEndpoint.isNotBlank() &&
            tokenEndpoint.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("client_id", clientId)
        put("authorization_endpoint", authorizationEndpoint)
        put("token_endpoint", tokenEndpoint)
        scopes?.takeIf { it.isNotBlank() }?.let { put("scopes", it) }
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirect_uri", it) }
    }

    companion object {
        private val CLIENT_ID_KEYS = listOf("client_id", "clientId")
        private val AUTH_ENDPOINT_KEYS = listOf("authorization_endpoint", "authorizationEndpoint")
        private val TOKEN_ENDPOINT_KEYS = listOf("token_endpoint", "tokenEndpoint")
        private val REDIRECT_URI_KEYS = listOf("redirect_uri", "redirectURI")

        private fun JSONObject.firstNonBlank(keys: List<String>): String =
            keys.firstNotNullOfOrNull { key -> optString(key, "").ifBlank { null } } ?: ""

        fun fromJson(obj: JSONObject?): MCPOAuthConfig? {
            if (obj == null) return null
            val config = MCPOAuthConfig(
                mode = obj.optString("mode", "static").ifBlank { "static" },
                clientId = obj.firstNonBlank(CLIENT_ID_KEYS),
                authorizationEndpoint = obj.firstNonBlank(AUTH_ENDPOINT_KEYS),
                tokenEndpoint = obj.firstNonBlank(TOKEN_ENDPOINT_KEYS),
                scopes = obj.optString("scopes", "").ifBlank { null },
                redirectUri = obj.firstNonBlank(REDIRECT_URI_KEYS).ifBlank { null },
            )
            return if (config.clientId.isBlank() &&
                config.authorizationEndpoint.isBlank() &&
                config.tokenEndpoint.isBlank()
            ) {
                null
            } else {
                config
            }
        }
    }
}
"""

# C1 — the alias reads are gone (the shipped-but-blank state).
BAD_CONFIG_NO_ALIAS = GOOD_CONFIG.replace(
    'listOf("client_id", "clientId")', 'listOf("client_id")').replace(
    'listOf("authorization_endpoint", "authorizationEndpoint")', 'listOf("authorization_endpoint")').replace(
    'listOf("token_endpoint", "tokenEndpoint")', 'listOf("token_endpoint")').replace(
    'listOf("redirect_uri", "redirectURI")', 'listOf("redirect_uri")')

# C1 — the pre-fix file, verbatim in shape: no tables at all, snake_case
# literals only (the four CLI-written fields parse blank).
BAD_CONFIG_BASELINE = """
data class MCPOAuthConfig(
    val mode: String = "static",
    val clientId: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val scopes: String? = null,
    val redirectUri: String? = null,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            authorizationEndpoint.isNotBlank() &&
            tokenEndpoint.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("client_id", clientId)
        put("authorization_endpoint", authorizationEndpoint)
        put("token_endpoint", tokenEndpoint)
        scopes?.takeIf { it.isNotBlank() }?.let { put("scopes", it) }
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirect_uri", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject?): MCPOAuthConfig? {
            if (obj == null) return null
            val config = MCPOAuthConfig(
                mode = obj.optString("mode", "static").ifBlank { "static" },
                clientId = obj.optString("client_id", ""),
                authorizationEndpoint = obj.optString("authorization_endpoint", ""),
                tokenEndpoint = obj.optString("token_endpoint", ""),
                scopes = obj.optString("scopes", "").ifBlank { null },
                redirectUri = obj.optString("redirect_uri", "").ifBlank { null },
            )
            return if (config.clientId.isBlank() &&
                config.authorizationEndpoint.isBlank() &&
                config.tokenEndpoint.isBlank()
            ) null else config
        }
    }
}
"""

# C1 — a mention of the CLI spelling inside a COMMENT cannot satisfy the check.
BAD_CONFIG_COMMENT_ONLY = BAD_CONFIG_BASELINE.replace(
    "    companion object {",
    "    // The in-guest CLI spells these clientId / authorizationEndpoint /\n"
    "    // tokenEndpoint — honoured the same way as parseStartupTimeout does.\n"
    "    companion object {")

# C2 — alias promoted to primary (camelCase only, no snake_case fallback).
BAD_CONFIG_ALIAS_FIRST = GOOD_CONFIG.replace(
    'listOf("client_id", "clientId")', 'listOf("clientId")').replace(
    'listOf("authorization_endpoint", "authorizationEndpoint")', 'listOf("authorizationEndpoint")').replace(
    'listOf("token_endpoint", "tokenEndpoint")', 'listOf("tokenEndpoint")').replace(
    'listOf("redirect_uri", "redirectURI")', 'listOf("redirectURI")')

# C2 — table reversed: camelCase first, snake_case as the fallback.
BAD_CONFIG_TABLE_REVERSED = GOOD_CONFIG.replace(
    'listOf("client_id", "clientId")', 'listOf("clientId", "client_id")')

# C3 — the tables exist but fromJson reads literals: a fallback on paper only.
BAD_CONFIG_TABLE_UNWIRED = GOOD_CONFIG.replace(
    "clientId = obj.firstNonBlank(CLIENT_ID_KEYS)", 'clientId = obj.optString("client_id", "")')

# C4 — the read side learned a second spelling and the write side followed.
BAD_CONFIG_TOJSON_CAMEL = GOOD_CONFIG.replace(
    'put("client_id", clientId)', 'put("clientId", clientId)')

# C5 — the "no endpoints is noise" null path removed.
BAD_CONFIG_NO_NULL_GUARD = GOOD_CONFIG.replace(
    """            return if (config.clientId.isBlank() &&
                config.authorizationEndpoint.isBlank() &&
                config.tokenEndpoint.isBlank()
            ) {
                null
            } else {
                config
            }""",
    "            return config")

def selftest():
    failures = []

    def expect_clean(name, result):
        if result:
            failures.append("%s must be clean, got %s" % (name, result))

    def expect_trip(name, result, needle):
        if not any(needle in item for item in result):
            failures.append("%s did not trip '%s' (got %s)" % (name, needle, result))

    good_store = check_store(mask(GOOD_STORE))
    good_repo = check_repo(mask(GOOD_REPO))
    expect_clean("compliant store fixture", good_store)
    expect_clean("compliant repo fixture", good_repo)

    s_secret = check_store(mask(BAD_STORE_SECRET_ONLY))
    expect_trip("secret-only cleanup fixture", s_secret, "is not a cleanup")

    s_purge = check_store(mask(BAD_STORE_NO_PURGE))
    expect_trip("purge-outcome-dropped fixture", s_purge, "never returns PURGE")

    s_bridge = check_store(mask(BAD_STORE_NO_BRIDGE_DELETE))
    expect_trip("tokens-without-bridge-delete fixture", s_bridge, "leaves the bridge file")

    # [wide-1024 R4] the argument the convergence call passes
    r_transport = check_repo(mask(BAD_REPO_TRANSPORT_LITERAL))
    expect_trip("hard-coded transport fixture", r_transport, "a literal")
    for item in r_transport:
        if "a literal" not in item:
            failures.append("hard-coded transport fixture tripped something else: %s" % item)

    s_mat = check_store(mask(BAD_STORE_NO_MATERIALIZE))
    expect_trip("purge-only reconcile fixture", s_mat, "does not call `materializeBridgeFile(")

    # [dv27-A3] the `.secret` handoff arms (C5/C6).
    expect_clean("compliant secret-handoff fixture",
                 check_secret_handoff(mask(GOOD_STORE)))

    h_noimport = check_secret_handoff(mask(BAD_STORE_NO_SECRET_IMPORT))
    expect_trip("clientSecret without the handoff import", h_noimport,
                "does not import the pending")

    h_order = check_secret_handoff(mask(BAD_STORE_SIGNOUT_ORDER))
    expect_trip("signOut importing after the bridge delete", h_order,
                "BEFORE it imports")

    h_purge = check_secret_handoff(mask(BAD_STORE_PURGE_IMPORTS))
    expect_trip("purge importing the handoff", h_purge,
                "imports the pending `.secret` handoff")

    r_add = check_repo(mask(BAD_REPO_ADD_UNWIRED))
    expect_trip("unwired add fixture", r_add, "`add` does not reach")

    r_upd = check_repo(mask(BAD_REPO_UPDATE_UNWIRED))
    expect_trip("unwired update fixture", r_upd, "`update` does not reach")

    r_imp = check_repo(mask(BAD_REPO_IMPORT_UNWIRED))
    expect_trip("unwired importJSON fixture", r_imp, "`importJSON` does not reach")

    r_dead = check_repo(mask(BAD_REPO_HELPER_DEAD))
    expect_trip("dead-indirection fixture", r_dead, "does not call `MCPOAuthStore.reconcileForServer(")

    r_secret = check_repo(mask(BAD_REPO_SECRET_VERB))
    expect_trip("repository-secret-only-verb fixture", r_secret, "uses `setClientSecret(")

    # [da1034-A1] artifact-backup arms
    good_scope = check_backup_scope(mask_comments(GOOD_SCOPE))
    expect_clean("compliant artifact scope fixture", good_scope)

    b_nodeny = check_backup_scope(mask_comments(BAD_SCOPE_NO_DENY))
    expect_trip("scope without deny-set", b_nodeny, 'no `<val> = setOf("oauth")` deny-set')

    b_deadguard = check_backup_scope(mask_comments(GOOD_SCOPE.replace(
        "                if (child.name in DENIED_DIRS) continue",
        "                if (false && child.name in DENIED_DIRS) continue")))
    expect_trip("deny-set behind a dead guard", b_deadguard, "can never be true")

    b_dead = check_backup_scope(mask_comments(BAD_SCOPE_DEAD_DENY))
    expect_trip("scope with an inert deny-set", b_dead, "the walk never skips on it")

    b_comment = check_backup_scope(mask_comments(BAD_SCOPE_COMMENT_ONLY))
    expect_trip("scope whose deny-set is only a comment", b_comment,
                'no `<val> = setOf("oauth")` deny-set')

    good_backup = check_backup_import(mask_comments(GOOD_BACKUP), mask(GOOD_BACKUP))
    expect_clean("compliant backup import fixture", good_backup)

    b_nowire = check_backup_import(mask_comments(BAD_BACKUP_NO_WIRING), mask(BAD_BACKUP_NO_WIRING))
    expect_trip("import extracting the raw archive", b_nowire, "never calls `stripMcpOAuthEntries(`")

    b_after = check_backup_import(mask_comments(BAD_BACKUP_AFTER_EXTRACT), mask(BAD_BACKUP_AFTER_EXTRACT))
    expect_trip("filter running after extraction", b_after, "runs AFTER")

    b_hollow = check_backup_import(mask_comments(BAD_BACKUP_HOLLOW_PREDICATE), mask(BAD_BACKUP_HOLLOW_PREDICATE))
    expect_trip("predicate that matches nothing", b_hollow, "must pin BOTH")

    b_noreport = check_backup_import(mask_comments(BAD_BACKUP_NO_REPORT), mask(BAD_BACKUP_NO_REPORT))
    expect_trip("silent oauth drop", b_noreport, "must reach `skipped`")

    # [da1034-A1] 变异 m2 的形状：filter 在、顺序对，但抽取吃的是原始 tmp
    b_raw = check_backup_import(mask_comments(BAD_BACKUP_EXTRACT_RAW), mask(BAD_BACKUP_EXTRACT_RAW))
    expect_trip("extraction consuming the raw archive", b_raw,
                "extracts something other than the filtered archive")

    # [dv28-a5 A-05] the same Stage 9 pass must not re-write what Stage 7 merged
    def stage9(fixture):
        return check_stage9_premerged(mask_comments(fixture), mask(fixture))

    expect_clean("compliant pre-merged server-list fixture", stage9(GOOD_BACKUP))

    s9_nodrop = stage9(BAD_STAGE9_NO_DROP)
    expect_trip("filter that keeps the pre-merged server list", s9_nodrop,
                "without the `dropPrestagedServerList` switch")

    s9_comment = stage9(BAD_STAGE9_COMMENT_ONLY)
    expect_trip("skip that is only a comment", s9_comment,
                "without the `dropPrestagedServerList` switch")

    s9_uncond = stage9(BAD_STAGE9_UNCONDITIONAL)
    expect_trip("unconditional server-list drop", s9_uncond,
                "a literal is not a decision")

    s9_noop = stage9(BAD_STAGE9_NO_OPERAND)
    expect_trip("flag that ignores the merge operands", s9_noop,
                "is not derived from the operands")

    s9_hollow = stage9(BAD_STAGE9_HOLLOW_PREDICATE)
    expect_trip("predicate pinning the wrong file", s9_hollow, "no predicate pins")

    s9_prefix = stage9(BAD_STAGE9_WHOLE_PREFIX)
    expect_trip("whole mcp prefix dropped", s9_prefix,
                "no longer an extraction destination")

    s9_decl = stage9(BAD_STAGE9_NO_DECL)
    expect_trip("undeclared server-list skip", s9_decl, "not declared")

    # [da1029-E] UI arms
    good_ui, good_ui_keyed = check_ui(mask(GOOD_UI))
    expect_clean("compliant live-edit UI fixture", good_ui)

    u_unkeyed, _ = check_ui(mask(BAD_UI_UNKEYED))
    expect_trip("un-keyed field fixture", u_unkeyed, "has no key on that entry")

    u_snap, _ = check_ui(mask(BAD_UI_SNAPSHOT))
    expect_trip("tap-time snapshot fixture", u_snap, "the sheet is handed the tap-time")

    u_pass, _ = check_ui(mask(BAD_UI_NOT_PASSED))
    expect_trip("live value not passed fixture", u_pass, "is resolved but never passed")

    u_fall, _ = check_ui(mask(BAD_UI_NO_FALLBACK))
    expect_trip("no elvis fallback fixture", u_fall, "has no `?: ` fallback")

    u_form, _ = check_ui(mask(BAD_UI_NO_FORM))
    expect_trip("renamed form fixture", u_form, "field-state keys this check pins is gone")

    # [dv14-B3] close-on-disappear arms
    u_orphan, _ = check_ui(mask(BAD_UI_ORPHAN))
    expect_trip("orphan-credential fixture (elvis, no close effect)", u_orphan,
                "no close-on-disappear effect was found")

    u_orphan_noelvis, _ = check_ui(mask(BAD_UI_NO_FALLBACK))
    expect_trip("no-fallback fixture (no elvis, no close effect)", u_orphan_noelvis,
                "has no `?: ` fallback")

    # [wide-1024 U3] the guard exists, its predicate is not about the id
    u_hollow, _ = check_ui(mask(BAD_UI_HOLLOW_PREDICATE))
    expect_trip("hollow presence-predicate fixture", u_hollow, "does not compare")

    # [dv14-B3] U3 arms
    u_norefuse, _ = check_ui(mask(BAD_UI_NO_REFUSE))
    expect_trip("credential store without presence guard", u_norefuse,
                "never checks the listing still contains the id")

    expect_clean("comment describing the old shape",
                 check_ui(mask(COMMENTED_UI))[0])
    expect_trip("comment claiming a fix", check_ui(mask(LYING_UI))[0], "has no key on that entry")

    # masking arm: a compliant store whose *comment* mentions the old
    # secret-only cleanup, and a violating store whose comment claims it is
    # fixed, must be judged on the code only.
    commented = GOOD_STORE.replace(
        "    internal enum class ReconcileAction { PURGE, KEEP, REFRESH_BRIDGE }",
        "    // we used to only remove(secretKey(server)) here — now purged\n"
        "    internal enum class ReconcileAction { PURGE, KEEP, REFRESH_BRIDGE }",
    )
    expect_clean("comment mentioning the old cleanup", check_store(mask(commented)))
    lying = BAD_STORE_SECRET_ONLY.replace(
        "    fun clearOAuth(context: Context, server: String) {",
        "    // removed only the secret before; now it is a full purge\n"
        "    fun clearOAuth(context: Context, server: String) {",
    )
    expect_trip("comment claiming a fix", check_store(mask(lying)), "is not a cleanup")

    print("selftest compliant store        -> %d violation(s)" % len(good_store))
    print("selftest compliant repo         -> %d violation(s)" % len(good_repo))
    print("selftest secret-only cleanup    -> %d violation(s)" % len(s_secret))
    print("selftest purge outcome dropped  -> %d violation(s)" % len(s_purge))
    print("selftest tokens keep bridge     -> %d violation(s)" % len(s_bridge))
    print("selftest purge-only reconcile   -> %d violation(s)" % len(s_mat))
    print("selftest unwired add/update/imp -> %d/%d/%d violation(s)" % (len(r_add), len(r_upd), len(r_imp)))
    print("selftest dead indirection       -> %d violation(s)" % len(r_dead))
    print("selftest repo secret-only verb  -> %d violation(s)" % len(r_secret))
    print("selftest UI live-edit           -> %d violation(s), %d field state(s) keyed"
          % (len(good_ui), good_ui_keyed))
    print("selftest UI arms (unkeyed/snapshot/not-passed/no-fallback/renamed-form) -> %d/%d/%d/%d/%d violation(s)"
          % (len(u_unkeyed), len(u_snap), len(u_pass), len(u_fall), len(u_form)))
    print("selftest dv14-B3 arms (orphan/no-fallback-no-close/no-refuse-guard) -> %d/%d/%d violation(s)"
          % (len(u_orphan), len(u_orphan_noelvis), len(u_norefuse)))
    print("selftest oauth-backup arms (deny-set/dead-deny/comment-only/no-wiring/after-extract/hollow-pred/silent-drop/extract-raw) -> %d/%d/%d/%d/%d/%d/%d/%d violation(s)"
          % (len(b_nodeny), len(b_dead), len(b_comment), len(b_nowire), len(b_after),
             len(b_hollow), len(b_noreport), len(b_raw)))
    print("selftest dv28-a5 stage9 arms (no-drop/comment-only/unconditional/"
          "flag-ignores-guard/hollow-pred/whole-prefix/undeclared) -> "
          "%d/%d/%d/%d/%d/%d/%d violation(s)"
          % (len(s9_nodrop), len(s9_comment), len(s9_uncond), len(s9_noop),
             len(s9_hollow), len(s9_prefix), len(s9_decl)))

    # [dv20-a1 A-01] artifact-face arms: each fixture must red exactly one group
    def expect_only(name, groups, gi, needle):
        if not any(needle in x for x in groups[gi]):
            failures.append(
                "%s must trip group %d of 3 with '%s', got %s"
                % (name, gi + 1, needle, [len(g) for g in groups]))
            return
        for j, g in enumerate(groups):
            if j != gi and g:
                failures.append("%s left sibling group %d dirty: %s" % (name, j + 1, g))

    def art(fixture=None, archiver=None):
        p = fixture if fixture is not None else GOOD_PACK
        a = archiver if archiver is not None else GOOD_ARCHIVER
        return check_artifact_redaction(mask_comments(p), mask(p), mask(a))

    p_good, r_good, a_good = art()
    expect_clean("compliant credential-free pack fixture", p_good)
    expect_clean("compliant redactor fixture", r_good)
    expect_clean("compliant archiver fixture", a_good)

    expect_only("pack without a redactor fixture", art(BAD_PACK_NO_REDACTOR), 0, "is handed no")
    expect_only("ungated redactor fixture", art(BAD_PACK_UNGATED), 0, "not gated on")
    expect_only("pack anchor drift fixture", art(BAD_PACK_NO_ROOT), 0, "re-anchor this gate")
    expect_only("redactor without the Stage 5 strip fixture", art(BAD_PACK_NO_STRIP), 1,
                "does not call")
    expect_only("redactor with a second rule fixture", art(BAD_PACK_INLINE_RULE), 1,
                "blanks secret values itself")
    expect_only("redactor that cannot fail closed fixture", art(BAD_PACK_NO_FAILCLOSED), 1,
                "parse-failure path")
    expect_only("archiver that never drops fixture", art(archiver=BAD_ARCHIVER_NO_NULL_CHECK), 2,
                "never compared to null")
    expect_only("archiver that does not report a drop fixture", art(archiver=BAD_ARCHIVER_NO_SINK), 2,
                "droppedSink")
    expect_only("archiver that ignores its transform fixture",
                art(archiver=BAD_ARCHIVER_NO_TRANSFORM), 2, "never applies its transform")
    expect_only("redactor returning the unstripped bytes fixture",
                art(BAD_PACK_RETURNS_RAW), 1, "returns the raw")
    expect_only("archiver anchor drift fixture", art(archiver=BAD_ARCHIVER_NO_FUN), 2,
                "re-anchor this gate")

    print("selftest dv20-a1 artifact arms (no-redactor/ungated/pack-rot/no-strip/second-rule/"
          "no-fail-closed/drop-removed/no-sink/ignores-transform/archiver-rot) -> "
          "%d/%d/%d/%d/%d/%d/%d/%d/%d/%d violation(s)"
          % (len(art(BAD_PACK_NO_REDACTOR)[0]), len(art(BAD_PACK_UNGATED)[0]),
             len(art(BAD_PACK_NO_ROOT)[0]), len(art(BAD_PACK_NO_STRIP)[1]),
             len(art(BAD_PACK_INLINE_RULE)[1]), len(art(BAD_PACK_NO_FAILCLOSED)[1]),
             len(art(archiver=BAD_ARCHIVER_NO_NULL_CHECK)[2]),
             len(art(archiver=BAD_ARCHIVER_NO_SINK)[2]),
             len(art(archiver=BAD_ARCHIVER_NO_TRANSFORM)[2]),
             len(art(archiver=BAD_ARCHIVER_NO_FUN)[2])))

    # [batch5-t11] 脱敏结果必须是被返回的那份：返回值换回原始入参
    print("selftest batch5-t11 redactor arm (returns the unstripped bytes) -> "
          "%d violation(s)" % len(art(BAD_PACK_RETURNS_RAW)[1]))



    # [dv25-A1] parse/write key-contract arms: each fixture must trip exactly
    # one criterion group, so a green run proves the five checks all have teeth.
    N1 = "does not read"
    N2A = "not ordered primary-then-alias"
    N2B = "reads only"
    N3 = "never consults it"
    N4 = "camelCase key"
    N5 = "no longer treats a blank"

    def cfg_arm(label, code, needle, cli=GOOD_CLI):
        res = check_config(mask_comments(code), cli)
        expect_trip(label, res, needle)
        for item in res:
            if needle not in item:
                failures.append("%s tripped an unexpected criterion: %s" % (label, item))
        return len(res)

    cfg_clean = check_config(mask_comments(GOOD_CONFIG), GOOD_CLI)
    expect_clean("compliant MCPOAuthConfig fixture", cfg_clean)
    if cfg_clean:
        failures.append("compliant config fixture tripped: %s" % cfg_clean)

    a1 = cfg_arm("alias-reads-removed fixture", BAD_CONFIG_NO_ALIAS, N1)
    a2 = cfg_arm("baseline-snake-only fixture", BAD_CONFIG_BASELINE, N1)
    a3 = cfg_arm("comment-only-alias fixture", BAD_CONFIG_COMMENT_ONLY, N1)
    a4 = cfg_arm("alias-promoted-to-primary fixture", BAD_CONFIG_ALIAS_FIRST, N2B)
    a5 = cfg_arm("table-reversed fixture", BAD_CONFIG_TABLE_REVERSED, N2A)
    a6 = cfg_arm("table-declared-but-unused fixture", BAD_CONFIG_TABLE_UNWIRED, N3)
    a7 = cfg_arm("toJson-writes-camelCase fixture", BAD_CONFIG_TOJSON_CAMEL, N4)
    a8 = cfg_arm("null-path-removed fixture", BAD_CONFIG_NO_NULL_GUARD, N5)
    a9 = cfg_arm("new-CLI-key fixture", GOOD_CONFIG, N1, cli=CLI_WITH_EXTRA_KEY)

    # fail-closed: an unreadable CLI (no derived key set) must not pass silently.
    no_cli = check_config(mask_comments(GOOD_CONFIG), "")
    expect_trip("empty CLI source", no_cli, "cannot derive the in-guest CLI")
    no_from = check_config(mask_comments("class X { fun toJson(): JSONObject = JSONObject() }"), GOOD_CLI)
    expect_trip("fromJson gone", no_from, "no `fun fromJson(`")

    print("selftest dv25-A1 config arms (no-alias/baseline/comment-only/alias-first/"
          "reversed/unwired/tojson-camel/no-null/new-cli-key/no-cli/no-fun) -> "
          "%d/%d/%d/%d/%d/%d/%d/%d/%d/%d/%d violation(s)"
          % (a1, a2, a3, a4, a5, a6, a7, a8, a9, len(no_cli), len(no_from)))

    # [dv26-A2] bridge-promotion arms. Each fixture must trip EXACTLY one
    # criterion, so a green run proves all three have teeth and none of them is
    # a restatement of another (the C1 pair are its two thresholds: never
    # reading, and reading without persisting).
    def promo(store=GOOD_STORE, merge=GOOD_MERGE):
        # check_bridge_promotion wants the structural view AND the
        # string-literal-preserving view of the SAME source (the bridge key names
        # are the cross-language contract), so both come from mask_comments(store).
        return check_bridge_promotion(mask_comments(store), mask_comments(store), mask(merge))

    def expect_only_tag(name, res, tag):
        if not any(tag in item for item in res):
            failures.append("%s must trip %s (got %s)" % (name, tag, res))
            return
        for item in res:
            if tag not in item:
                failures.append("%s tripped an unexpected criterion: %s" % (name, item))

    good_promo = promo()
    expect_clean("compliant bridge-promotion fixture", good_promo)

    p_noread = promo(store=BAD_STORE_NO_PROMOTION)
    expect_only_tag("baseline materialize-without-read fixture", p_noread, "C1")

    p_nofile = promo(store=BAD_STORE_NO_FILE_READ)
    expect_only_tag("store that never reads the bridge file", p_nofile, "C1")

    p_nopersist = promo(store=BAD_STORE_UNPERSISTED)
    expect_only_tag("read-but-not-persisted fixture", p_nopersist, "C1")

    p_endpoint = promo(store=BAD_STORE_READS_ENDPOINT)
    expect_only_tag("reader lifting an endpoint fixture", p_endpoint, "C3")

    p_noorder = promo(merge=BAD_MERGE_NO_ORDERING)
    expect_only_tag("promotion without an ordering guard fixture", p_noorder, "C2")

    p_noacc = promo(merge=BAD_MERGE_NO_ACCESS_CMP)
    expect_only_tag("promotion without an access-token comparison fixture", p_noacc, "C2")

    p_norefresh = promo(merge=BAD_MERGE_NO_REFRESH_CMP)
    expect_only_tag("promotion without a refresh-token comparison fixture", p_norefresh, "C2")

    # ── [batch5-t11] 外部见证击穿的六条臂：拼写在、效果不在 ─────────────
    # A1 死守卫：PURGE 分支里的 `purge(` 永不执行 —— 删掉服务器/切 STDIO 后
    # tokens::<id> 与明文桥接文件永久残留（ISSUE-04 crack 1 的原样回归）。
    s_deadpurge = check_store(mask(GOOD_STORE.replace(
        "ReconcileAction.PURGE -> purge(context, server)",
        "ReconcileAction.PURGE -> if (false) purge(context, server)")))
    expect_trip("purge behind a dead guard", s_deadpurge, "on a reachable path")

    # C5a 结果被丢弃 / 空 server 名：调用名还在，导入从未发生。
    s_importdiscard = check_secret_handoff(mask(GOOD_STORE.replace(
        "        importPendingClientSecret(context, server)?.let { return it }\n"
        "        return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }",
        "        importPendingClientSecret(context, server)\n"
        "        return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }")))
    expect_trip("handoff import whose result is dropped", s_importdiscard,
                "does not import the pending")

    s_importdead = check_secret_handoff(mask(GOOD_STORE.replace(
        "importPendingClientSecret(context, server)?.let { return it }",
        'if (false) importPendingClientSecret(context, "")?.let { return it }')))
    expect_trip("handoff import behind a dead guard with an empty server", s_importdead,
                "does not import the pending")

    # C5b：死分支里的导入排在删除前，真正生效的导入排在删除后。
    s_orderdead = check_secret_handoff(mask(GOOD_STORE.replace(
        "    fun signOut(context: Context, server: String) {\n"
        "        importPendingClientSecret(context, server)\n"
        "        prefs(context).edit().remove(tokensKey(server)).apply()\n"
        "        deleteBridgeFile(context, server)\n",
        "    fun signOut(context: Context, server: String) {\n"
        "        if (false) importPendingClientSecret(context, server)\n"
        "        prefs(context).edit().remove(tokensKey(server)).apply()\n"
        "        deleteBridgeFile(context, server)\n"
        "        importPendingClientSecret(context, server)\n")))
    expect_trip("handoff import split around the bridge delete", s_orderdead,
                "BEFORE it imports")

    # C1 载荷：promote 被调用、结果也持久化了，但 materialize 的 accessToken
    # 仍取自被取代的 stored 快照 —— 沙箱里的 CLI 立刻拿到过期 token。
    p_payload = promo(store=GOOD_STORE.replace(
        "accessToken = t.accessToken", "accessToken = stored.accessToken"))
    expect_only_tag("payload materializing the superseded token", p_payload, "C1")
    expect_trip("payload materializing the superseded token", p_payload,
                "not the promoted token")

    # [wide-1024 C2] the flipped ordering: comparison present, direction wrong
    p_flipped = promo(merge=BAD_MERGE_FLIPPED_ORDERING)
    expect_only_tag("flipped ordering guard fixture", p_flipped, "C2")
    expect_trip("flipped ordering guard fixture", p_flipped, "exits early on the NEWER side")

    p_nomerge = promo(merge="")
    expect_only_tag("missing bridge-merge policy fixture", p_nomerge, "C2")

    print("selftest batch5-t11 arms (dead-purge/dropped-import/empty-server/"
          "import-order/payload-superseded/dead-deny-guard) -> "
          "%d/%d/%d/%d/%d/%d violation(s)"
          % (len(s_deadpurge), len(s_importdiscard), len(s_importdead),
             len(s_orderdead), len(p_payload), len(b_deadguard)))
    print("selftest dv26-A2 bridge-promotion arms (no-read/no-file-read/"
          "not-persisted/reader-endpoint/no-ordering/no-access-cmp/"
          "no-refresh-cmp/no-policy) -> "
          "%d/%d/%d/%d/%d/%d/%d/%d violation(s)"
          % (len(p_noread), len(p_nofile), len(p_nopersist), len(p_endpoint),
             len(p_noorder), len(p_noacc), len(p_norefresh), len(p_nomerge)))


    # ── [R3-t18] round-3 dual-gate witnesses (#27 export hand-off / #28
    # reconcile payload / #29 form OAuth plumbing) ─────────────────────────
    expect_clean("compliant export strip fixture",
                 check_export_secret_strip(mask_comments(GOOD_BACKUP), mask(GOOD_BACKUP)))
    b_strip_dead = check_export_secret_strip(
        mask_comments(BAD_BACKUP_STRIP_DEAD), mask(BAD_BACKUP_STRIP_DEAD))
    expect_trip("export strip behind a dead guard", b_strip_dead, "DEAD-GUARDED")
    b_strip_absent = check_export_secret_strip(
        mask_comments(BAD_BACKUP_STRIP_ABSENT), mask(BAD_BACKUP_STRIP_ABSENT))
    expect_trip("export strip call removed", b_strip_absent, "never calls")
    b_strip_copy = check_export_secret_strip(
        mask_comments(BAD_BACKUP_STRIP_COPY), mask(BAD_BACKUP_STRIP_COPY))
    expect_trip("export strip scrubs a throwaway copy", b_strip_copy,
                "instead of the server entry")
    b_strip_ungated = check_export_secret_strip(
        mask_comments(BAD_BACKUP_STRIP_UNGATED), mask(BAD_BACKUP_STRIP_UNGATED))
    expect_trip("export strip ungated", b_strip_ungated, "not gated on `!includeSecrets`")

    expect_clean("compliant reconcile payload fixture", check_repo(mask(GOOD_REPO)))
    r_oauthnull = check_repo(mask(BAD_REPO_OAUTH_NULL))
    expect_trip("reconcile with `oauth = null`", r_oauthnull, "PURGES the live tokens")
    for item in r_oauthnull:
        if "a literal" not in item:
            failures.append("oauth-null fixture tripped something else: %s" % item)
    r_urlliteral = check_repo(mask(BAD_REPO_URL_LITERAL))
    expect_trip("reconcile with a literal serverUrl", r_urlliteral, "PURGES the live tokens")

    expect_clean("compliant form builder fixture",
                 check_form_oauth_persisted(mask(GOOD_FORM)))
    f_null = check_form_oauth_persisted(mask(BAD_FORM_OAUTH_NULL))
    expect_trip("form stores a literal oauth", f_null, "a literal")
    f_dropped = check_form_oauth_persisted(mask(BAD_FORM_OAUTH_DROPPED))
    expect_trip("form omits the oauth argument", f_dropped, "without `oauth =")
    f_detached = check_form_oauth_persisted(mask(BAD_FORM_OAUTH_DETACHED))
    expect_trip("form persists a detached oauth value", f_detached, "currentOAuthConfig()")

    print("selftest r3-t18 arms (export-strip dead/absent/copy/ungated + "
          "reconcile oauth-null/url-literal + form null/dropped/detached) -> "
          "%d/%d/%d/%d + %d/%d + %d/%d/%d violation(s)"
          % (len(b_strip_dead), len(b_strip_absent), len(b_strip_copy),
             len(b_strip_ungated), len(r_oauthnull), len(r_urlliteral),
             len(f_null), len(f_dropped), len(f_detached)))

    if failures:
        for item in failures:
            print("SELFTEST FAIL " + item)
        return 1
    print(SELFTEST_MARK)
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
