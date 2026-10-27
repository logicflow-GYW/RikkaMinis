package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.repository.MCPRepository
import com.rikkaminis.app.ui.settings.SettingsRow
import com.rikkaminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet showing all configured MCP servers with per-session
 * enable/disable toggles. Mirrors [SessionSkillsSheet]: [StandardChatSheet]
 * shell + shared [SettingsSection] / [SettingsRow] primitives. Each row shows
 * the server name + truncated note, with a Switch writing a session override
 * via [MCPRepository.setSessionOverride].
 */
@Composable
fun SessionMcpsSheet(
    sessionId: String,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-mcp-review-fixes-android] Key the seed on `servers` so the override map
    // re-derives when the live server list arrives. A keyless remember{} seeds
    // once from a possibly-empty first-composition list and then never re-runs,
    // leaving every toggle stuck at its default (the review's stale-override race).
    //
    // [audit-0917] Re-seed by MERGING, not by rebuilding: the previous
    // `remember(servers) { mutableStateMapOf(...) }` threw the whole map away
    // whenever the servers StateFlow emitted (any unrelated repo update), so a
    // toggle the user had just flipped reverted to the stored value until the
    // write landed — and if the emission arrived between flip and write, the
    // switch visibly snapped back. Existing keys are now preserved; only newly
    // appearing servers get seeded.
    // [da1022-b] Key the map identity on `sessionId`: when the draft session
    // is promoted the caller re-composes with the real session id, so a FRESH
    // map + re-run of the seeding effect below re-derive every toggle from the
    // real id's stored overrides (a keyless map would keep serving the
    // pre-promotion `__new__…` seeds, and the merge logic's `!in overrides`
    // guard would skip re-seeding same-id servers). Within a single session id
    // the [audit-0917] merge semantics are unchanged: servers-StateFlow
    // emissions MERGE into the live map instead of rebuilding it.
    val overrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    LaunchedEffect(servers, sessionId) {
        // [deep-audit-1040 B-04] Same shape as SessionSkillsSheet: the stored
        // per-session read is a SQLite query per server, so it moves to IO.
        // Which ids are missing is decided on Main (that reads the snapshot
        // map); the writes into it stay on Main too.
        //
        // [deep-audit-1045 B-06] The seeding write now goes through the SAME
        // helper SessionSkillsSheet uses instead of an inline loop of its own.
        // The inline loop decided `missing` on Main, awaited one SQLite read per
        // missing id on IO, and then wrote every result back UNCONDITIONALLY —
        // so a toggle the user flipped inside that window was overwritten with
        // the pre-toggle stored value: the switch snapped back and the choice
        // was lost until the user noticed. `missing` is a snapshot; the map is
        // live. [mergeSessionOverrides] re-decides on Main after the await
        // (`if (id !in overrides)`), which is the contract both sheets' comments
        // already claimed to share — the drift was that only one of them
        // actually called it. `?: false` keeps the lookup total: `seeds` covers
        // exactly the ids the merge asks about, so the fallback is unreachable.
        val liveIds = servers.map { it.id }
        val seeds = withContext(Dispatchers.IO) {
            liveIds.filter { it !in overrides }
                .associateWith { mcpRepository.isEnabledForSession(it, sessionId) }
        }
        mergeSessionOverrides(
            liveIds = liveIds,
            overrides = overrides,
            lookup = { seeds[it] ?: false },
        )
        // ids that vanished from the live list are dropped inside the helper, so
        // a later re-add re-seeds from storage instead of resurrecting a stale
        // toggle.
    }

    // [deep-audit-1040 B-04] Same shape as SessionSkillsSheet's row toggle:
    // the Switch flips from the local `overrides` map on Main (zero latency) and
    // the SQLite write follows on IO.
    val scope = rememberCoroutineScope()

    StandardChatSheet(
        title = stringResource(R.string.session_mcps_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (servers.isEmpty()) {
                EmptyMcpsCard()
            } else {
                SettingsSection(
                    footer = stringResource(R.string.session_mcps_footer),
                ) {
                    servers.forEachIndexed { index, server ->
                        val note = server.note?.trim().orEmpty()
                        val sub = note
                            .ifEmpty { server.transportSummary }
                            .let { if (it.length > mcpRepository.noteTruncationCap) it.substring(0, mcpRepository.noteTruncationCap) + "…" else it }
                        SettingsRow(
                            title = server.id,
                            subtitle = sub.takeIf { it.isNotBlank() },
                            showChevron = false,
                            showDivider = index < servers.size - 1,
                            trailing = {
                                Switch(
                                    checked = overrides[server.id] ?: server.enabled,
                                    onCheckedChange = { enabled ->
                                        overrides[server.id] = enabled
                                        val serverId = server.id
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                mcpRepository.setSessionOverride(sessionId, serverId, enabled)
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun EmptyMcpsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_mcps_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_mcps_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
