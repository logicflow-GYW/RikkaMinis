package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.repository.MCPRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.DialogTextField
import com.rikkaminis.app.ui.components.MinisTextButton

/**
 * MCP Integrations management screen. Mirrors [SkillsManagementScreen]:
 * a section of server rows (toggle + name + transport summary + transport
 * icon), an add bottom-sheet with Form / Import-JSON tabs, and a delete
 * confirmation dialog. Strings are English-keyed in strings.xml.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPIntegrationsScreen(
    mcpRepository: MCPRepository,
    onBack: () -> Unit,
    // [T-mcp-env-var-picker-android] App env vars, for the STDIO env field's
    // "insert app var" picker ($$VAR references resolve at runtime in PRoot).
    // Null when the caller hasn't wired it — the picker affordance hides.
    envVarRepository: com.rikkaminis.app.data.repository.EnvVarRepository? = null,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-android-mcp-list-reload-on-appear] MCPRepository reads servers.json
    // only in init() (app launch). A server the agent writes via minis-mcp-cli
    // AFTER launch isn't reflected in the `servers` StateFlow until restart.
    // Re-read the file each time the screen appears (mirrors the Skills screen's
    // reloadFromDisk on entry) so CLI-added servers show without an app restart.
    // [fix/da1032-e] Mirrors the Skills screen fix: reloadFromDisk() (==
    // MCPRepository.load()) reads servers.json and may write it back for the
    // createdAt back-fill — disk I/O that used to run inline on Main every
    // time this screen appeared. Only the thread changes; the `_servers.value`
    // publish and the save() stay on IO, both thread-safe.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            mcpRepository.reloadFromDisk()
        }
    }

    // [deep-audit-1040 B-04] Row-level writes (toggle / delete) move off the
    // main thread; this scope is what they launch into.
    val scope = rememberCoroutineScope()

    var showAddSheet by remember { mutableStateOf(false) }
    // [T-mcp-review-fixes-android] FIX 1: tapping a row opens the form in EDIT
    // mode (matches iOS). null = the sheet is for adding a new server.
    var editServer by remember { mutableStateOf<MCPRepository.MCPServerConfig?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.mcp_title),
        onBack = null, // top-level page: rely on system back gesture / bottom nav
        actions = {
            IconButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mcp_add))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.mcp_section_servers),
            footer = stringResource(R.string.mcp_section_footer),
        ) {
            if (servers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Text(
                        stringResource(R.string.mcp_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.mcp_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                servers.forEachIndexed { index, server ->
                    val transportIcon = if (server.isStdio) Icons.Outlined.Terminal else Icons.Outlined.Language
                    SettingsRow(
                        title = server.id,
                        subtitle = server.transportSummary.takeIf { it.isNotBlank() },
                        showChevron = true,
                        showDivider = index < servers.size - 1,
                        // FIX 1: plain tap opens the edit form (was delete-confirm).
                        // Delete stays reachable from inside the edit sheet.
                        onClick = { editServer = server },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    transportIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = server.enabled,
                                    // [deep-audit-1040 B-04] setEnabled() writes
                                    // servers.json (tmp file + rename) and republishes
                                    // the StateFlow — disk I/O that used to run on
                                    // Main. The Switch stays controlled: the row
                                    // repaints from the `servers` flow.
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                mcpRepository.setEnabled(server.id, enabled)
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddSheet) {
        MCPAddSheet(
            mcpRepository = mcpRepository,
            envVarRepository = envVarRepository,
            editServer = null,
            onDismiss = { showAddSheet = false },
            onRequestDelete = {},
        )
    }

    // FIX 1: edit sheet — same form, pre-filled, with a Delete affordance.
    // [da1029-E] `editServer` is a snapshot captured at tap time, and
    // MCPFormTab seeds its whole field state from it. When the entry changes
    // behind the sheet's back (a repository write from an OAuth callback, a
    // paste-import, reloadFromDisk), the stale snapshot kept showing the old
    // values — and Save then wrote them back over the external change
    // (1027-E / 1028-C are the same defect in ModelGroupDetailScreen /
    // EnvVarFormSheet). Resolve the live entry from the listing by id and hand
    // THAT to the sheet below, so the keyed field states re-seed when the entry
    // really changed. The elvis fallback keeps the sheet in edit mode when the
    // entry was deleted meanwhile instead of silently becoming the add flow.
    editServer?.let { captured ->
        val liveServer = servers.find { it.id == captured.id } ?: captured
        // [fix/dv14-B3] The elvis above keeps the sheet in EDIT mode when the
        // entry was deleted externally while it was open (reloadFromDisk /
        // paste-import) — and Save then stores setClientSecret under a deleted
        // id (orphan credentials, against the MCPRepository.kt invariant
        // "credentials exist iff the saved config is a configured URL-transport
        // OAuth server") while update() returns false and is silently dropped.
        // Close the sheet instead. Keyed on (captured.id, servers) so a listing
        // change re-launches the effect with the FRESH listing (a launch-time
        // capture of `servers` goes stale the moment the listing changes);
        // state is mutated from the effect, never inline in composition.
        LaunchedEffect(captured.id, servers) {
            if (servers.none { it.id == captured.id }) editServer = null
        }
        MCPAddSheet(
            mcpRepository = mcpRepository,
            envVarRepository = envVarRepository,
            editServer = liveServer,
            onDismiss = { editServer = null },
            onRequestDelete = {
                editServer = null
                deleteId = liveServer.id
            },
        )
    }

    if (deleteId != null) {
        val id = deleteId!!
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.mcp_delete_title, id)) },
            text = { Text(stringResource(R.string.mcp_delete_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    // [deep-audit-1040 B-04] delete() runs a SQLite DELETE,
                    // rewrites servers.json and purges the OAuth store
                    // (EncryptedSharedPreferences + bridge file) — all disk.
                    // The dialog is dismissed first (main thread, immediate) so
                    // it can't flicker or absorb a second tap while the write
                    // is in flight.
                    deleteId = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            mcpRepository.delete(id)
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

// ─── Add Sheet (Form / Import tabs) ─────────────────────────────────────────

/**
 * Add/Edit bottom sheet. When [editServer] is null this is the add flow
 * (Form / Import-JSON tabs). When it's non-null the sheet is the edit flow:
 * Form only, pre-filled, with a Delete affordance ([onRequestDelete]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPAddSheet(
    mcpRepository: MCPRepository,
    envVarRepository: com.rikkaminis.app.data.repository.EnvVarRepository?,
    editServer: MCPRepository.MCPServerConfig?,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    // [T-android-mcp-sheet-ime-occlusion] GH#44: skipPartiallyExpanded so the
    // sheet opens full-height — at half-height the soft keyboard left no room
    // for the lower form fields at all.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    val isEdit = editServer != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // [T-android-mcp-sheet-ime-occlusion] GH#44: verticalScroll + imePadding
        // keep every field reachable while the keyboard is up — imePadding sits
        // after verticalScroll so the inset becomes scrollable bottom space the
        // content can scroll past, instead of the IME clipping the column.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (isEdit) R.string.mcp_edit else R.string.mcp_add),
                style = MaterialTheme.typography.titleMedium,
            )

            if (!isEdit) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text(stringResource(R.string.mcp_tab_form), modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text(stringResource(R.string.mcp_tab_import), modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (isEdit || selectedTab == 0) {
                MCPFormTab(
                    mcpRepository = mcpRepository,
                    envVarRepository = envVarRepository,
                    editServer = editServer,
                    onDone = onDismiss,
                    onRequestDelete = onRequestDelete,
                )
            } else {
                MCPImportTab(mcpRepository = mcpRepository, onDone = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPFormTab(
    mcpRepository: MCPRepository,
    envVarRepository: com.rikkaminis.app.data.repository.EnvVarRepository?,
    editServer: MCPRepository.MCPServerConfig?,
    onDone: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val context = LocalContext.current
    val isEdit = editServer != null
    // [T-mcp-server-export-android] Clipboard for "Copy JSON".
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // [T-mcp-env-var-picker-android] App env var keys for the STDIO env picker.
    val appEnvEntries by (envVarRepository?.entries
        ?.collectAsState() ?: remember { mutableStateOf(emptyList<com.rikkaminis.app.data.repository.EnvVarRepository.EnvVarEntry>()) })
    val appEnvKeys = remember(appEnvEntries) { appEnvEntries.map { it.key } }
    var showEnvVarPicker by remember { mutableStateOf(false) }
    // [T-mcp-http-headers-env-picker-android] Same $$VAR picker offered on the
    // HTTP/SSE Custom Headers and Server URL value fields.
    var showHeaderVarPicker by remember { mutableStateOf(false) }
    var showUrlVarPicker by remember { mutableStateOf(false) }
    // [T-mcp-review-fixes-android] FIX 4: transport is now 3-way —
    // 0 = HTTP, 1 = SSE, 2 = STDIO. HTTP and SSE both store as a plain `url`
    // entry (no separate storage field — round-trips identically to iOS); only
    // STDIO uses command/args/env. Pre-fill from editServer: STDIO if it has a
    // command, else HTTP (we can't distinguish a persisted SSE from HTTP since
    // both are url-only, so an SSE edit defaults to the HTTP affordance — the
    // saved entry is byte-identical either way).
    // [da1029-E] Every field below is seeded from [editServer] — and the caller
    // re-resolves that to the LIVE entry (see the MCPAddSheet call site). The
    // key is the resolved entry itself: `MCPServerConfig` is a data class, so
    // the key only changes when the entry's contents actually change, i.e. an
    // external write is picked up, while typing inside the sheet never
    // re-seeds it. (Keying on `editServer?.id` would miss same-id content
    // changes; keying on the whole object is the tightest available key.)
    // Known trade-off: `oauthClientSecret` / `oauthAuthorized` read the
    // encrypted MCPOAuthStore, so a store-only write (a `.secret` handoff
    // landing, a token refresh) does not change the key and is not re-seeded —
    // registering that would mean reading the store on every recomposition.
    var transport by remember(editServer) { mutableIntStateOf(if (editServer?.isStdio == true) 2 else 0) }
    var name by remember(editServer) { mutableStateOf(editServer?.id ?: "") }
    var note by remember(editServer) { mutableStateOf(editServer?.note ?: "") }
    var url by remember(editServer) { mutableStateOf(editServer?.url ?: "") }
    var headers by remember(editServer) { mutableStateOf(mapToLines(editServer?.headers, ": ")) }
    var command by remember(editServer) { mutableStateOf(editServer?.command ?: "") }
    var args by remember(editServer) { mutableStateOf(editServer?.args?.joinToString(" ") ?: "") }
    var env by remember(editServer) { mutableStateOf(mapToLines(editServer?.env, "=")) }
    // [T-mcp-startup-timeout] Optional STDIO startup/handshake timeout (seconds).
    // Kept as text so it round-trips; parsed to Int on save; blank = default 60s.
    var startupTimeout by remember(editServer) { mutableStateOf(editServer?.startupTimeoutSeconds?.toString() ?: "") }
    var errorText by remember { mutableStateOf<String?>(null) }

    // [T-android-mcp-oauth] Static-OAuth fields for a URL transport. Expanded
    // automatically when the edited server already has an oauth block. The
    // client secret is loaded from / saved to MCPOAuthStore (encrypted), never
    // shown pre-filled from JSON (it isn't stored there).
    val oauthScope = androidx.compose.runtime.rememberCoroutineScope()
    var oauthExpanded by remember(editServer) { mutableStateOf(editServer?.oauth != null) }
    var oauthClientId by remember(editServer) { mutableStateOf(editServer?.oauth?.clientId ?: "") }
    // [deep-audit-1040 B-04] `importPendingClientSecret` / `clientSecret`
    // open EncryptedSharedPreferences (Keystore) and may read-then-delete the
    // `.secret` handoff file; `isAuthorized` reads the encrypted token blob.
    // Seeding them inside `remember` ran that I/O on the composition thread on
    // EVERY re-seed (i.e. on every keystroke that changed the entry), which is
    // the jank this fix removes. Both now ride a keyed LaunchedEffect on IO and
    // publish into state; the initial value stays "" / false so the first frame
    // renders before (not during) the read.
    var oauthClientSecret by remember(editServer) { mutableStateOf("") }
    var oauthAuthorizedSeed by remember(editServer) { mutableStateOf(false) }
    // [meta-audit-1028 D4 F-04] The secret field is filled by a Keystore read (below),
    // and Save/Authorize used to be able to run before it landed: `ifBlank { null }` then
    // turned the still-empty box into a REMOVE of the stored secret. Two extra states fix
    // the attribution — [oauthSecretLoad] says whether the box's content came from disk,
    // [oauthSecretEdited] says whether the user touched it at all (an untouched box is not
    // an instruction to change anything).
    var oauthSecretLoad by remember(editServer) {
        mutableStateOf(
            if (editServer?.oauth == null) SecretLoadState.LOADED else SecretLoadState.PENDING,
        )
    }
    var oauthSecretEdited by remember(editServer) { mutableStateOf(false) }
    LaunchedEffect(editServer) {
        // `sid` stays a plain `editServer?.id` binding: the off-main gate reads it to
        // tell "reset the draft" (no server) from "seed it from the store".
        val sid = editServer?.id
        if (sid == null) {
            oauthClientSecret = ""
            oauthAuthorizedSeed = false
            oauthSecretLoad = SecretLoadState.LOADED
            return@LaunchedEffect
        }
        // Only a server that SAVED an oauth block has a stored secret whose loss
        // matters; the add-sheet's empty field is not a pending read.
        val gated = editServer?.oauth != null
        if (!gated) oauthSecretLoad = SecretLoadState.LOADED
        // [T-mcp-secret-handoff-import] If the in-guest CLI seeded a client
        // secret via the .secret handoff file, import it first (into the
        // encrypted store) so it pre-fills; otherwise read the stored secret.
        val secret = withContext(Dispatchers.IO) {
            runCatching {
                com.rikkaminis.app.mcp.oauth.MCPOAuthStore.importPendingClientSecret(context, sid)
                    ?: com.rikkaminis.app.mcp.oauth.MCPOAuthStore.clientSecret(context, sid)
            }
        }
        val authorized = withContext(Dispatchers.IO) {
            com.rikkaminis.app.mcp.oauth.MCPOAuthStore.isAuthorized(context, sid)
        }
        secret.fold(
            onSuccess = { stored ->
                // A listing reload re-runs this effect (`remember(editServer)` re-seeds
                // the field with it); text the user already typed wins over the read.
                if (!oauthSecretEdited) oauthClientSecret = stored ?: ""
                if (gated) oauthSecretLoad = SecretLoadState.LOADED
            },
            onFailure = {
                // Fail closed: the box shows "" while a secret still exists on disk, so
                // nothing may be written from it.
                if (gated) oauthSecretLoad = SecretLoadState.UNREADABLE
                AppLogger.warning(
                    "MCPSheet",
                    "could not read the OAuth client secret for \"$sid\"; save is disabled",
                )
            },
        )
        oauthAuthorizedSeed = authorized
    }
    var oauthAuthEndpoint by remember(editServer) { mutableStateOf(editServer?.oauth?.authorizationEndpoint ?: "") }
    var oauthTokenEndpoint by remember(editServer) { mutableStateOf(editServer?.oauth?.tokenEndpoint ?: "") }
    var oauthScopes by remember(editServer) { mutableStateOf(editServer?.oauth?.scopes ?: "") }
    // [T-mcp-redirect-uri-preserve] Custom redirect URI. Optional — blank means
    // the loopback default. Previously dropped on edit (the form neither read
    // nor rewrote it), so a JSON/CLI-supplied redirect_uri silently evaporated
    // on Save. Now round-tripped like the other OAuth fields.
    var oauthRedirectUri by remember(editServer) { mutableStateOf(editServer?.oauth?.redirectUri ?: "") }
    var oauthAuthorized by remember(oauthAuthorizedSeed) { mutableStateOf(oauthAuthorizedSeed) }
    var oauthBusy by remember { mutableStateOf(false) }
    // [meta-audit-1028 D4 F-04] Both buttons below write the secret; neither may run
    // while the box does not show what is on disk.
    val oauthSecretReady = oauthSecretLoad == SecretLoadState.LOADED

    fun currentOAuthConfig(): com.rikkaminis.app.mcp.oauth.MCPOAuthConfig? {
        val cfg = com.rikkaminis.app.mcp.oauth.MCPOAuthConfig(
            clientId = oauthClientId.trim(),
            authorizationEndpoint = oauthAuthEndpoint.trim(),
            tokenEndpoint = oauthTokenEndpoint.trim(),
            scopes = oauthScopes.trim().ifBlank { null },
            redirectUri = oauthRedirectUri.trim().ifBlank { null },
        )
        return cfg.takeIf { it.isConfigured }
    }
    // [T-mcp-env-var-delete-confirm-android] Drives the confirm dialog before
    // clearing a non-empty env field. (Empty field clears with no confirm.)
    var showClearEnvConfirm by remember { mutableStateOf(false) }

    // True for the url-based transports (HTTP / SSE).
    val isUrlTransport = transport != 2

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = transport == 0,
                onClick = { transport = 0; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
            ) { Text(stringResource(R.string.mcp_transport_http)) }
            SegmentedButton(
                selected = transport == 1,
                onClick = { transport = 1; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(1, 3),
            ) { Text(stringResource(R.string.mcp_transport_sse)) }
            SegmentedButton(
                selected = transport == 2,
                onClick = { transport = 2; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(2, 3),
            ) { Text(stringResource(R.string.mcp_transport_stdio)) }
        }

        FieldLabel(stringResource(R.string.mcp_form_name))
        DialogTextField(
            value = name,
            onValueChange = { name = it; errorText = null },
            placeholder = stringResource(R.string.mcp_form_name_placeholder),
            singleLine = true,
            // Editing keys by name/id; renaming would orphan the row, so lock it.
            enabled = !isEdit,
        )

        if (isUrlTransport) {
            // [T-mcp-http-headers-env-picker-android] Offer the same $$VAR App
            // env-var picker on the URL + Custom Headers value fields (runtime
            // expansion of $$VAR / $VAR in url/headers already works in the
            // shared CLI). Selecting a var appends `$$KEY` to the field so it
            // lands as a value (e.g. after `Authorization: ` in a header line).
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_url))
                if (envVarRepository != null) {
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { showUrlVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
            }
            DialogTextField(
                value = url,
                onValueChange = { url = it; errorText = null },
                placeholder = stringResource(R.string.mcp_form_url_placeholder),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_headers))
                if (envVarRepository != null) {
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { showHeaderVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
            }
            DialogTextField(
                value = headers,
                onValueChange = { headers = it },
                placeholder = stringResource(R.string.mcp_form_headers_placeholder),
                singleLine = false,
                modifier = Modifier.height(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            // [T-android-mcp-oauth] Static OAuth section (collapsible). When
            // configured, the server authorizes via PKCE and the issued bearer
            // token is attached at connect time (in-guest wiring is a later
            // phase; here the user can configure + complete the auth handshake).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { oauthExpanded = !oauthExpanded },
            ) {
                FieldLabel(stringResource(R.string.mcp_form_oauth_section))
                Spacer(Modifier.weight(1f))
                if (oauthAuthorized) {
                    Text(
                        stringResource(R.string.mcp_form_oauth_authorized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (oauthExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (oauthExpanded) {
                FieldLabel(stringResource(R.string.mcp_form_oauth_client_id))
                DialogTextField(
                    value = oauthClientId,
                    onValueChange = { oauthClientId = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_client_id_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_client_secret))
                DialogTextField(
                    value = oauthClientSecret,
                    onValueChange = {
                        oauthClientSecret = it
                        // [meta-audit-1028 D4 F-04] Typing is the only way to express
                        // "change this": an untouched field must never be written back,
                        // because it is also the shape a pending read leaves behind.
                        oauthSecretEdited = true
                    },
                    placeholder = if (oauthSecretLoad == SecretLoadState.PENDING) {
                        stringResource(R.string.common_loading)
                    } else {
                        stringResource(R.string.mcp_form_oauth_client_secret_placeholder)
                    },
                    singleLine = true,
                )
                if (oauthSecretLoad == SecretLoadState.UNREADABLE) {
                    Text(
                        stringResource(R.string.settings_secret_unreadable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FieldLabel(stringResource(R.string.mcp_form_oauth_auth_endpoint))
                DialogTextField(
                    value = oauthAuthEndpoint,
                    onValueChange = { oauthAuthEndpoint = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_auth_endpoint_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_token_endpoint))
                DialogTextField(
                    value = oauthTokenEndpoint,
                    onValueChange = { oauthTokenEndpoint = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_token_endpoint_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_scopes))
                DialogTextField(
                    value = oauthScopes,
                    onValueChange = { oauthScopes = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_scopes_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_redirect_uri))
                DialogTextField(
                    value = oauthRedirectUri,
                    onValueChange = { oauthRedirectUri = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_redirect_uri_placeholder),
                    singleLine = true,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (oauthAuthorized) {
                        MinisTextButton(
                            enabled = !oauthBusy && oauthSecretReady,
                            onClick = {
                                val sid = name.trim()
                                if (sid.isNotEmpty()) {
                                    // [deep-audit-1040 B-04] signOut() drops the
                                    // encrypted token row — Keystore-backed
                                    // SharedPreferences I/O. The UI flag flips
                                    // immediately (Main); the write follows on IO.
                                    oauthAuthorized = false
                                    oauthScope.launch {
                                        withContext(Dispatchers.IO) {
                                            com.rikkaminis.app.mcp.oauth.MCPOAuthStore.signOut(context, sid)
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(
                                stringResource(R.string.mcp_form_oauth_sign_out),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        enabled = !oauthBusy && oauthSecretReady,
                        onClick = {
                            val sid = name.trim()
                            val cfg = currentOAuthConfig()
                            when {
                                sid.isEmpty() ->
                                    errorText = context.getString(R.string.mcp_form_error_no_name)
                                url.isBlank() ->
                                    errorText = context.getString(R.string.mcp_form_error_no_transport)
                                cfg == null ->
                                    errorText = context.getString(R.string.mcp_form_oauth_incomplete)
                                else -> {
                                    // [fix/dv14-B3] Same orphan-credential guard as
                                    // the Save branch: the authorize flow stores the
                                    // client secret (and later tokens + the bridge
                                    // file) under `sid`; if the entry was deleted
                                    // externally while the sheet was open, that
                                    // writes credentials under an id that no longer
                                    // exists. Refuse instead of aiming at a deleted
                                    // id — the caller's close-on-disappear effect
                                    // dismisses the sheet.
                                    if (isEdit && mcpRepository.servers.value.none { it.id == sid }) {
                                        AppLogger.warning(
                                            "MCPSheet",
                                            "MCP server \"$sid\" was deleted while the edit sheet was open; authorize refused",
                                        )
                                        return@MinisTextButton
                                    }
                                    errorText = null
                                    val secretValue = oauthClientSecret
                                    // [meta-audit-1028 D4 F-04] Same three-way decision
                                    // as Save: authorizing is a second path that writes
                                    // the secret, so it must not turn "the box is empty
                                    // because the read has not landed" into a REMOVE of
                                    // the stored client secret. An untouched box means
                                    // the exchange can read whatever is already stored.
                                    val secretWrite = decideClientSecretWrite(
                                        oauthConfigured = true,
                                        state = oauthSecretLoad,
                                        editedByUser = oauthSecretEdited,
                                        text = secretValue,
                                    )
                                    oauthBusy = true
                                    oauthScope.launch {
                                        // [deep-audit-1040 B-04] Persist the secret
                                        // so the token exchange can read it;
                                        // harmless if the flow is cancelled. The
                                        // write opens EncryptedSharedPreferences
                                        // (Keystore), so it runs on IO — captured
                                        // above so nothing Compose-owned is read
                                        // off the main thread.
                                        withContext(Dispatchers.IO) {
                                            if (secretWrite != SecretWrite.LEAVE_UNTOUCHED) {
                                                com.rikkaminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
                                                    context, sid,
                                                    if (secretWrite == SecretWrite.CLEAR) null else secretValue.trim(),
                                                )
                                            }
                                        }
                                        val result = com.rikkaminis.app.mcp.oauth.MCPOAuthController(context)
                                            .authorize(sid, url.trim(), cfg)
                                        oauthBusy = false
                                        when (result) {
                                            is com.rikkaminis.app.mcp.oauth.MCPOAuthController.Result.Success -> {
                                                oauthAuthorized = true
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.mcp_form_oauth_authorized),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                            is com.rikkaminis.app.mcp.oauth.MCPOAuthController.Result.Cancelled -> {}
                                            is com.rikkaminis.app.mcp.oauth.MCPOAuthController.Result.Failed -> {
                                                errorText = result.message
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (oauthAuthorized) R.string.mcp_form_oauth_reauthorize
                                else R.string.mcp_form_oauth_authorize,
                            ),
                        )
                    }
                }
            }
        } else {
            FieldLabel(stringResource(R.string.mcp_form_command))
            DialogTextField(
                value = command,
                onValueChange = { command = it; errorText = null },
                placeholder = stringResource(R.string.mcp_form_command_placeholder),
                singleLine = true,
            )
            FieldLabel(stringResource(R.string.mcp_form_args))
            DialogTextField(
                value = args,
                onValueChange = { args = it },
                placeholder = stringResource(R.string.mcp_form_args_placeholder),
                singleLine = true,
            )
            // [T-mcp-env-var-picker-android] Env label + "insert app var"
            // affordance. Tapping opens a picker of App env var keys; selecting
            // one appends a `KEY=$$KEY` line so the value resolves at runtime
            // from the PRoot guest env (App env vars are injected there). The
            // affordance only shows when an EnvVarRepository was wired in.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_env))
                Spacer(Modifier.weight(1f))
                if (envVarRepository != null) {
                    MinisTextButton(
                        onClick = { showEnvVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
                // [T-mcp-env-var-delete-confirm-android] Explicit clear/delete
                // affordance. Non-empty → confirm dialog before wiping; empty →
                // clears immediately (no-op confirm). Disabled while blank so the
                // affordance reads as inert when there's nothing to delete.
                MinisTextButton(
                    onClick = {
                        if (env.isBlank()) env = "" else showClearEnvConfirm = true
                    },
                    enabled = env.isNotBlank(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp,
                    ),
                ) {
                    Text(
                        stringResource(R.string.mcp_form_env_clear),
                        color = if (env.isNotBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DialogTextField(
                value = env,
                onValueChange = { env = it },
                placeholder = stringResource(R.string.mcp_form_env_placeholder),
                singleLine = false,
                modifier = Modifier.height(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            FieldLabel(stringResource(R.string.mcp_form_startup_timeout))
            DialogTextField(
                value = startupTimeout,
                onValueChange = { startupTimeout = it.filter { c -> c.isDigit() } },
                placeholder = stringResource(R.string.mcp_form_startup_timeout_placeholder),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        }

        FieldLabel(stringResource(R.string.mcp_form_note))
        DialogTextField(
            value = note,
            onValueChange = { note = it },
            placeholder = stringResource(R.string.mcp_form_note_placeholder),
            singleLine = false,
            modifier = Modifier.height(64.dp),
        )

        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // [T-mcp-server-export-android] Build a MCPServerConfig from the CURRENT
        // form fields (so Copy/Share export reflects unsaved edits, same
        // construction Save uses). Returns null + sets errorText when invalid.
        // HTTP (0) and SSE (1) both persist as a url-only entry; STDIO (2)
        // persists command/args/env. createdAt preserved on edit.
        fun buildServerFromForm(): MCPRepository.MCPServerConfig? {
            if (name.isBlank()) {
                errorText = context.getString(R.string.mcp_form_error_no_name); return null
            }
            return if (isUrlTransport) {
                if (url.isBlank()) {
                    errorText = context.getString(R.string.mcp_form_error_no_transport); return null
                }
                MCPRepository.MCPServerConfig(
                    id = name.trim(),
                    note = note.trim().ifBlank { null },
                    enabled = editServer?.enabled ?: true,
                    url = url.trim(),
                    headers = parseKeyValueLines(headers, ':'),
                    // [T-android-mcp-oauth] Persist the non-secret OAuth config.
                    oauth = currentOAuthConfig(),
                    createdAt = editServer?.createdAt ?: System.currentTimeMillis(),
                )
            } else {
                if (command.isBlank()) {
                    errorText = context.getString(R.string.mcp_form_error_no_transport); return null
                }
                MCPRepository.MCPServerConfig(
                    id = name.trim(),
                    note = note.trim().ifBlank { null },
                    enabled = editServer?.enabled ?: true,
                    command = command.trim(),
                    args = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                    env = parseKeyValueLines(env, '='),
                    startupTimeoutSeconds = startupTimeout.trim().toIntOrNull(),
                    createdAt = editServer?.createdAt ?: System.currentTimeMillis(),
                )
            }
        }

        // [T-mcp-server-export-android] Export the current config as standard
        // mcpServers JSON — Copy to clipboard + Share intent. Builds from the
        // live form so it includes unsaved edits.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(mcpRepository.exportServerJSON(server)))
                android.widget.Toast.makeText(
                    context, context.getString(R.string.mcp_export_copied), android.widget.Toast.LENGTH_SHORT,
                ).show()
            }) { Text(stringResource(R.string.mcp_export_copy)) }
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                // exportServerJSON is pure in-memory JSON building; only the
                // staging write needs a coroutine (see shareMcpServerJson).
                val json = mcpRepository.exportServerJSON(server)
                oauthScope.launch {
                    // [deep-audit-1048 B-04] Surface a failed share instead of
                    // leaving the tap with no effect at all.
                    val launched = shareMcpServerJson(context, server.id, json)
                    if (!launched) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(
                                R.string.file_share_failed_toast,
                                "${server.id}-mcp.json",
                            ),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }) { Text(stringResource(R.string.mcp_export_share)) }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // FIX 1: Delete affordance lives inside the edit sheet so a plain
            // row tap no longer means delete, without losing the delete path.
            if (isEdit) {
                MinisTextButton(onClick = onRequestDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            MinisTextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                // [fix/dv14-B3] An entry deleted externally while the sheet was
                // open must not leave orphan credentials: setClientSecret is
                // keyed by server.id, and update() returns false when the id is
                // gone (silently dropped — the save lands nowhere). Refuse
                // instead: no secret write, no add, no onDone. The caller's
                // close-on-disappear effect dismisses the sheet.
                if (isEdit && mcpRepository.servers.value.none { it.id == server.id }) {
                    AppLogger.warning(
                        "MCPSheet",
                        "MCP server \"${server.id}\" was deleted while the edit sheet was open; save refused",
                    )
                    return@MinisTextButton
                }
                // [T-android-mcp-oauth] Store the client secret in the encrypted
                // store (keyed by server id) — it never goes into servers.json.
                // Cleared when the OAuth section is emptied.
                // [fix/audit0917-b8] Unconditional, not gated on isUrlTransport:
                // a server switched from HTTP/SSE to STDIO used to keep its
                // encrypted client secret forever (the STDIO form never shows
                // the OAuth section, so nothing could ever clear it). Passing
                // null on the STDIO path removes the entry.
                // [deep-audit-1040 B-04] Save touches Keystore-backed prefs
                // (setClientSecret), servers.json (update/add → save()) and the
                // OAuth reconcile (bridge file + chmod) — all disk. Capture the
                // Compose-owned values here (Main) and do the work on IO; the
                // navigation callback fires back on Main once it lands, so the
                // sheet never closes ahead of a failed write.
                val secretValue = oauthClientSecret
                // [meta-audit-1028 D4 F-04] What to do with the stored secret is a
                // THREE-way decision, not `ifBlank { null }`:
                //   • the box still shows a value the read never produced → touch nothing
                //     (that is the wipe this fixes: the read was in flight, the box said
                //     "" and `null` means REMOVE downstairs);
                //   • the user never edited the box → touch nothing (the stored value is
                //     still their answer; the `[fix/audit0917-b8]` clear-on-STDIO rule
                //     below is the exception, and it is a CONFIG fact, not a field one);
                //   • the user edited the box → their text wins, and an emptied box is an
                //     explicit clear.
                val secretWrite = decideClientSecretWrite(
                    oauthConfigured = isUrlTransport && server.oauth != null,
                    state = oauthSecretLoad,
                    editedByUser = oauthSecretEdited,
                    text = secretValue,
                )
                oauthScope.launch {
                    withContext(Dispatchers.IO) {
                        if (secretWrite != SecretWrite.LEAVE_UNTOUCHED) {
                            com.rikkaminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
                                context, server.id,
                                if (secretWrite == SecretWrite.CLEAR) null else secretValue.trim(),
                            )
                        }
                        if (isEdit) mcpRepository.update(server) else mcpRepository.add(server)
                    }
                    onDone()
                }
            }) { Text(stringResource(R.string.mcp_form_save)) }
        }
    }

    // [T-mcp-env-var-picker-android] App env var picker. Appends a
    // `KEY=$$KEY` line to the env editor (the $$KEY reference is saved verbatim
    // and expanded at runtime). Manual typing in the editor still works.
    if (showEnvVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                // [T-mcp-env-picker-insert-android] APPEND, don't replace
                // (reverses #719, aligns with iOS #728 8f90f2a8). Picking a var
                // adds a new KEY=$$KEY line so the user can pick MULTIPLE vars
                // and they accumulate (no dedup/overwrite). The env editor is a
                // multiline KEY=VALUE textarea, so a whole line is the natural
                // unit to append; a blank field seeds the first line.
                val line = "$key=$\$$key"
                env = if (env.isBlank()) line else env.trimEnd('\n') + "\n" + line
                showEnvVarPicker = false
            },
            onDismiss = { showEnvVarPicker = false },
        )
    }

    // [T-mcp-http-headers-env-picker-android] Headers / URL pickers. Unlike the
    // STDIO env textarea (whole KEY=VALUE lines), headers/url are values the
    // var reference drops INTO (e.g. `Authorization: $$KEY`, or `…?token=$$KEY`).
    // So we append just `$$KEY` to the end of the field — with a leading space
    // when the current text doesn't already end in whitespace, so it reads as a
    // value rather than fusing onto the preceding token. Manual editing intact.
    if (showHeaderVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                val ref = "$\$$key"
                headers = if (headers.isEmpty() || headers.last().isWhitespace()) {
                    headers + ref
                } else {
                    "$headers $ref"
                }
                showHeaderVarPicker = false
            },
            onDismiss = { showHeaderVarPicker = false },
        )
    }
    if (showUrlVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                val ref = "$\$$key"
                url = if (url.isEmpty() || url.last().isWhitespace()) url + ref else "$url$ref"
                errorText = null
                showUrlVarPicker = false
            },
            onDismiss = { showUrlVarPicker = false },
        )
    }

    // [T-mcp-env-var-delete-confirm-android] Confirm before wiping a non-empty
    // env field. Only reachable when env was non-blank at tap time.
    if (showClearEnvConfirm) {
        AlertDialog(
            onDismissRequest = { showClearEnvConfirm = false },
            title = { Text(stringResource(R.string.mcp_form_env_clear_confirm_title)) },
            text = { Text(stringResource(R.string.mcp_form_env_clear_confirm_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    env = ""
                    showClearEnvConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearEnvConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * [T-mcp-env-var-picker-android] Bottom sheet listing App environment variable
 * keys. Picking one inserts a `$$KEY` reference into the MCP server's env (via
 * the caller's [onPick]). Shows an empty-state hint when there are none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPEnvVarPickerSheet(
    keys: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.mcp_env_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (keys.isEmpty()) {
                Text(
                    stringResource(R.string.mcp_env_picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                for (key in keys) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(key) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$\$$key",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MCPImportTab(
    mcpRepository: MCPRepository,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val previewCount = remember(text) { if (text.isBlank()) 0 else mcpRepository.previewImport(text) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.mcp_import_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = text,
            onValueChange = { text = it; errorText = null },
            placeholder = stringResource(R.string.mcp_import_placeholder),
            singleLine = false,
            modifier = Modifier.height(200.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (text.isNotBlank()) {
            Text(
                stringResource(R.string.mcp_import_found, previewCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MinisTextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            // [deep-audit-1040 B-04] importJSON rewrites servers.json and
            // reconciles OAuth for every imported entry — disk + Keystore.
            // Launched from IO; errorText / onDone are touched back on Main.
            val importScope = rememberCoroutineScope()
            MinisTextButton(
                onClick = {
                    val payload = text
                    importScope.launch {
                        val imported = withContext(Dispatchers.IO) {
                            mcpRepository.importJSON(payload)
                        }
                        if (imported.isEmpty()) errorText = context.getString(R.string.mcp_import_none)
                        else onDone()
                    }
                },
                enabled = previewCount > 0,
            ) { Text(stringResource(R.string.mcp_import_submit)) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Parse multi-line "Key<sep> Value" text into a map. Lines without the
 * separator are skipped. Used for headers (`:`) and env (`=`).
 */
/**
 * Serialize a map back into multi-line "Key<joiner>Value" text for pre-filling
 * the edit form. Inverse of [parseKeyValueLines]. Empty/null → "".
 */
/**
 * [T-mcp-server-export-android] Share [json] (a single-server mcpServers
 * config) via ACTION_SEND. Mirrors ProviderDetailScreen.exportProviderInstance:
 * write to cacheDir/shared/<safeName>-mcp.json, expose via FileProvider
 * (${packageName}.fileprovider, configured for cache-path "shared/"), and
 * launch a chooser with type application/json.
 *
 * [deep-audit-1048 B-04] Returns whether the chooser was actually started —
 * every failure exit used to be a bare `return`, so a failed write or a missing
 * handler looked exactly like a successful share.
 */
private suspend fun shareMcpServerJson(
    context: android.content.Context,
    serverId: String,
    json: String,
): Boolean {
    val safeName = serverId.ifBlank { "mcp-server" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    // [da1024-G] mkdirs / prune / writeText are disk work — keep them off the
    // main thread. Returns false when the write fails, which the caller now
    // surfaces instead of silently skipping the share sheet.
    val file = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        // Same 24h-TTL pruner the cache/share/ writers use: nothing else ever
        // deletes cacheDir/shared/, so stale exports (and their credentials)
        // used to live for the life of the install.
        com.rikkaminis.app.ui.components.pruneShareDir(dir)
        val f = java.io.File(dir, "$safeName-mcp.json")
        runCatching { f.writeText(json) }.map { f }.getOrNull()
    } ?: return false
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
    } catch (_: Throwable) {
        return false
    }
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "$safeName-mcp.json")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, "Export MCP server").apply {
        // [deep-audit-1048 B-04] `context` here is the sheet's LocalContext; a
        // non-Activity context needs NEW_TASK or startActivity throws
        // AndroidRuntimeException (the sibling provider export already did this).
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // No share target installed: ActivityNotFoundException must not escape.
    return runCatching { context.startActivity(chooser) }.isSuccess
}

private fun mapToLines(map: Map<String, String>?, joiner: String): String {
    if (map.isNullOrEmpty()) return ""
    return map.entries.joinToString("\n") { "${it.key}$joiner${it.value}" }
}

private fun parseKeyValueLines(text: String, sep: Char): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val idx = trimmed.indexOf(sep)
        if (idx <= 0) continue
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
