package com.rikkaminis.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.accessibility.RestrictedSettingsManager
import com.rikkaminis.app.config.AutoApproveStore
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.offload.OffloadPermissionManager
import com.rikkaminis.app.offload.RuntimePermissionBatch
import com.rikkaminis.app.offload.ShizukuManager
import com.rikkaminis.app.power.PowerOptimizationManager
import com.rikkaminis.app.ui.components.MinisMenu
import com.rikkaminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rikkaminis.app.ui.theme.ChatColors

@Composable
fun OffloadPermissionScreen(
    onBack: () -> Unit,
    // [T-android-privileged-backend] Navigate to the multi-backend
    // (Shizuku + AXManager) screen from the privileged-backend integration row.
    onOpenPrivilegedBackend: () -> Unit = {},
) {
    val grouped = OffloadPermissionManager.toolRegistry
        .filter { it.showInSettings }
        .groupBy { it.category }
    // T336: Integrations category gets dedicated handcrafted SectionCards
    // (one per CLI) with system-layer state + action rows. Skip it from
    // the auto-rendered loop below.
    val autoCategories = grouped.entries
        .filter { it.key != OffloadPermissionManager.PermissionCategory.INTEGRATIONS }

    var showResetConfirm by remember { mutableStateOf(false) }

    val configEnabled by com.rikkaminis.app.config.MinisConfigPermissionStore.enabled.collectAsState()

    // [auto-approve] User's opt-in "免确认模式": routine config writes skip the
    // dialog, and ASK_ONCE tool permissions behave as BYPASS. All three flags
    // are UI-only (not reachable through minis-config).
    val autoApproveEnabled by AutoApproveStore.enabled.collectAsState()
    val autoApproveSensitive by AutoApproveStore.sensitiveEnabled.collectAsState()
    val autoApprovePrivileged by AutoApproveStore.privilegedToolsEnabled.collectAsState()
    // Turning the master switch ON is the one action with a blast radius the
    // user can't infer from a toggle, so it goes through an explicit dialog
    // that lists what stops being asked. Turning it OFF is one tap.
    var showAutoApproveConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // [auto-approve] One-shot batch grant for the runtime permissions the
    // android-* handlers check at call time. Android has no "remember my
    // answer" API for a batch, so this can only reduce the number of separate
    // moments the system asks — never eliminate the system dialog itself.
    //
    // The launcher's result map is not trusted on its own (several Android
    // versions return an empty map for already-granted entries); the toast
    // re-queries the live state, which is what actually matters.
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val all = RuntimePermissionBatch.permissionsFor(Build.VERSION.SDK_INT)
        val missing = all.count {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        val message = if (missing == 0) {
            context.getString(R.string.perm_runtime_batch_all_granted)
        } else {
            context.getString(R.string.perm_runtime_batch_partial, missing)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        AppLogger.info(
            "PermissionsScreen",
            "runtime permission batch finished: granted=${all.size - missing} missing=$missing",
        )
    }

    // [dv15-B2 / B-08] One classification drives all three a11y states (see
    // A11yStatus). "Ready" now means the service is actually bound, so a
    // listed-but-killed service reports DEGRADED instead of reporting
    // "enabled" and hiding both rescue cards below.
    var a11yEnabled by remember { mutableStateOf(a11yStatusOf(context) == A11yStatus.READY) }
    var a11yDegraded by remember { mutableStateOf(false) }
    var a11yRevoked by remember { mutableStateOf(false) }
    // [T-android-restricted-settings] Android 13+ refuses to arm the
    // accessibility toggle for installs flagged as restricted; surfaced below
    // so the "Open accessibility settings" action doesn't dead-end.
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Re-poll once a second so coming back from system Accessibility
        // settings flips the row without a manual refresh.
        while (true) {
            // [dv15-B2 / B-08] Was `isA11yServiceEnabled(context) || getInstance() != null`,
            // which reported a listed-but-killed service as enabled (the ROM kills
            // the process while Settings.Secure keeps the entry) — that hid the
            // settings entry point and the two rescue cards. The connected fallback
            // survives inside classifyA11yStatus.
            val status = a11yStatusOf(context)
            a11yEnabled = status == A11yStatus.READY
            a11yDegraded = status == A11yStatus.DEGRADED
            a11yRevoked = status == A11yStatus.REVOKED
            // Re-probed each tick so the section disappears by itself once the
            // user allows restricted settings and returns.
            a11yRestricted = !a11yEnabled && RestrictedSettingsManager.isRestricted(context)
            delay(1000)
        }
    }
    val shizukuSnap by ShizukuManager.snapshot.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.perm_title),
        onBack = onBack,
        actions = {
            MinisTextButton(onClick = { showResetConfirm = true }) {
                Text(stringResource(R.string.perm_reset_all))
            }
        },
    ) {
        // [auto-approve] Mode first: it changes how every row below behaves.
        SettingsSection(
            header = stringResource(R.string.perm_section_auto_approve),
            footer = stringResource(R.string.perm_auto_approve_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.perm_auto_approve_title),
                subtitle = stringResource(R.string.perm_auto_approve_desc),
                checked = autoApproveEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        // Enabling asks once, explicitly; disabling is instant
                        // and also clears the two sub-flags (see disableAll).
                        showAutoApproveConfirm = true
                    } else {
                        AutoApproveStore.disableAll()
                    }
                },
                showDivider = autoApproveEnabled,
            )
            if (autoApproveEnabled) {
                SettingsSwitchRow(
                    title = stringResource(R.string.perm_auto_approve_sensitive),
                    subtitle = stringResource(R.string.perm_auto_approve_sensitive_desc),
                    checked = autoApproveSensitive,
                    onCheckedChange = { AutoApproveStore.setSensitiveEnabled(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.perm_auto_approve_privileged),
                    subtitle = stringResource(R.string.perm_auto_approve_privileged_desc),
                    checked = autoApprovePrivileged,
                    onCheckedChange = { AutoApproveStore.setPrivilegedToolsEnabled(it) },
                    showDivider = false,
                )
            }
        }

        // [auto-approve] Platform-level counterpart: Android cannot be told
        // "always allow" in advance, so the best available reduction is asking
        // for every runtime permission in one sitting instead of one capability
        // at a time mid-task.
        SettingsSection(
            header = stringResource(R.string.perm_section_runtime_batch),
            footer = stringResource(R.string.perm_runtime_batch_desc),
        ) {
            SettingsRow(
                icon = Icons.Outlined.LockOpen,
                title = stringResource(R.string.perm_runtime_batch_title),
                subtitle = stringResource(R.string.perm_runtime_batch_sub),
                onClick = {
                    val all = RuntimePermissionBatch.permissionsFor(Build.VERSION.SDK_INT)
                    val notGranted = all.filter {
                        ContextCompat.checkSelfPermission(context, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (notGranted.isEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.perm_runtime_batch_all_granted),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        runtimePermissionLauncher.launch(notGranted.toTypedArray())
                    }
                },
                showDivider = false,
            )
        }

        // T-config: master switch for the minis-config CLI surface.
        SettingsSection(
            header = stringResource(R.string.perm_section_config_tool),
            footer = stringResource(R.string.perm_minis_config_desc),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.perm_allow_minis_config),
                checked = configEnabled,
                onCheckedChange = {
                    com.rikkaminis.app.config.MinisConfigPermissionStore.setEnabled(it)
                },
                showDivider = false,
            )
        }

        autoCategories.forEach { (category, tools) ->
            SettingsSection(header = stringResource(categoryHeaderRes(category))) {
                tools.forEachIndexed { idx, tool ->
                    PermissionRow(
                        tool = tool,
                        showDivider = idx < tools.size - 1,
                    )
                }
            }
        }

        // T336 / T345-2: dedicated SectionCard per integration CLI, rendered
        // after the Privacy/Media/System auto-categories so the information
        // hierarchy reads: configuration → privacy → system integrations.
        // Each card shows the CLI description, the tri-state agent gate, the
        // system-layer status, and (when system-layer is not satisfied) a
        // deeplink back to the OS settings page that fixes it.
        IntegrationSection(
            iconVector = Icons.Outlined.Accessibility,
            iconTint = ChatColors.success,
            sectionHeaderRes = R.string.perm_section_a11y,
            sectionFooterRes = R.string.perm_a11y_section_footer,
            toolName = "a11y_cli",
            descriptionRes = R.string.perm_a11y_cli_description,
            systemReady = a11yEnabled,
            systemStatusTitleRes =
                if (a11yEnabled) R.string.perm_a11y_system_ready
                else R.string.perm_a11y_system_disabled,
            systemActionTitleRes = R.string.perm_a11y_open_settings,
            onSystemAction = { openAccessibilitySettings(context) },
        )

        // [T-android-restricted-settings] Shown ONLY when the OS has actually
        // flagged this install (appop ACCESS_RESTRICTED_SETTINGS ==
        // MODE_ERRORED) and the service is still off. That is precisely the
        // state where the "Open accessibility settings" action right above
        // leads to a toggle the user cannot move: Android 13+ blocks the
        // accessibility toggle for packages installed by an installer that
        // declared PACKAGE_SOURCE_LOCAL_FILE / _DOWNLOADED_FILE, which is what
        // a browser or file manager opening a downloaded APK does. Without
        // this the user follows our own instruction into a dead end with no
        // explanation — the reported HyperOS / Android 14 case.
        //
        // We cannot clear the flag ourselves: it is set on our own uid and
        // clearing it needs the signature permission MANAGE_APP_OPS_MODES.
        // That is deliberate, since the policy exists to stop a sideloaded app
        // from talking the user into granting it screen-reading power. So the
        // manual route is the primary affordance and stays visible even when
        // the Shizuku one-tap shortcut is also offered.
        if (a11yRestricted) {
            SettingsSection(
                header = stringResource(R.string.system_permissions_a11y_restricted_header),
                footer = stringResource(R.string.system_permissions_a11y_restricted_footer),
            ) {
                if (shizukuSnap.state == ShizukuManager.State.READY) {
                    SettingsRow(
                        icon = Icons.Outlined.LockOpen,
                        iconColor = ChatColors.success,
                        title = stringResource(R.string.system_permissions_a11y_restricted_shizuku),
                        subtitle = when {
                            unrestricting ->
                                stringResource(R.string.system_permissions_a11y_restricted_working)
                            unrestrictFailed ->
                                stringResource(R.string.system_permissions_a11y_restricted_failed)
                            else ->
                                stringResource(R.string.system_permissions_a11y_restricted_shizuku_sub)
                        },
                        onClick = {
                            if (unrestricting) return@SettingsRow
                            unrestricting = true
                            unrestrictFailed = false
                            scope.launch {
                                val ok = RestrictedSettingsManager.clearWithShizuku(context)
                                unrestricting = false
                                unrestrictFailed = !ok
                                // On success the poll above clears
                                // a11yRestricted and this section vanishes.
                            }
                        },
                    )
                }
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.system_permissions_a11y_restricted_manual),
                    subtitle = stringResource(R.string.system_permissions_a11y_restricted_manual_sub),
                    // Lands on RikkaMinis' own App info page, where "Allow
                    // restricted settings" lives in the overflow menu.
                    onClick = { openAppDetailsSettings(context) },
                    showDivider = false,
                )
            }
        }

        // [dv15-B2 / B-08] The two self-rescue cards used to exist ONLY in
        // SystemPermissionsScreen, which has no navigation entry point — so a
        // user whose grant was force-stopped, or whose ROM killed the service,
        // reached this screen and found no way to repair it.
        A11yRevokedRepairSection(
            revoked = a11yRevoked,
            shizukuReady = shizukuSnap.state == ShizukuManager.State.READY,
        )
        A11yOemKeepAliveSection(degraded = a11yDegraded)

        IntegrationSection(
            iconVector = Icons.Outlined.Shield,
            iconTint = Color(0xFFAF52DE),
            // [T-android-privileged-backend] One section covers both Shizuku
            // and AXManager (they share the same binder slot + protocol);
            // toolName stays "shizuku_cli" so user authz is preserved.
            sectionHeaderRes = R.string.perm_section_privileged_backend,
            sectionFooterRes = R.string.perm_shizuku_section_footer,
            toolName = "shizuku_cli",
            descriptionRes = R.string.perm_privileged_cli_description,
            systemReady = ShizukuManager.isReady(),
            systemStatusTitleRes = shizukuSubtitleRes(shizukuSnap.state),
            systemActionTitleRes = shizukuActionTitleRes(shizukuSnap.state),
            // Open the unified Shizuku-protocol screen for setup actions.
            onSystemAction = onOpenPrivilegedBackend,
            // The status row itself opens the same page so users can revisit
            // the setup walkthrough even after the manager is already ready.
            onStatusRowClick = onOpenPrivilegedBackend,
        )

        Spacer(Modifier.height(16.dp))
    }

    if (showAutoApproveConfirm) {
        AlertDialog(
            onDismissRequest = { showAutoApproveConfirm = false },
            title = { Text(stringResource(R.string.perm_auto_approve_confirm_title)) },
            text = { Text(stringResource(R.string.perm_auto_approve_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showAutoApproveConfirm = false
                    AutoApproveStore.setEnabled(true)
                    AppLogger.info("PermissionsScreen", "auto-approve mode enabled by user")
                }) {
                    Text(stringResource(R.string.perm_auto_approve_confirm_on))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showAutoApproveConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.perm_reset_confirm_title)) },
            text = { Text(stringResource(R.string.perm_reset_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    OffloadPermissionManager.resetAll()
                    com.rikkaminis.app.config.MinisConfigPermissionStore.setEnabled(true)
                    AppLogger.info("PermissionsScreen", "user confirmed Reset All — all tool permissions cleared, minis-config switch reset to default")
                    showResetConfirm = false
                }) {
                    Text(stringResource(R.string.perm_reset_confirm))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * T336: composite SectionCard for a single integration CLI. Layout:
 *
 *   [icon]  CLI title
 *           CLI description (one-liner)
 *   ─────────
 *   Agent permission        [tri-state ▾]
 *   ─────────
 *   System authorization   [status text in color]
 *   ─────────
 *   (only when systemReady=false): [Open system settings →]
 *
 * The system-status row is read-only — the user goes to the OS settings
 * page to flip it. systemReady drives both the status row's color/icon
 * and the visibility of the action row below.
 */
@Composable
private fun IntegrationSection(
    iconVector: ImageVector,
    iconTint: Color,
    sectionHeaderRes: Int,
    sectionFooterRes: Int,
    toolName: String,
    descriptionRes: Int,
    systemReady: Boolean,
    systemStatusTitleRes: Int,
    systemActionTitleRes: Int,
    onSystemAction: () -> Unit,
    // [T-android-privileged-backend] When set, the system-status row becomes a
    // chevron-clickable entry to a dedicated detail screen (the multi-backend
    // Shizuku/AXManager page) — shown REGARDLESS of systemReady, so the user can
    // open it even when the backend is already authorized. Null keeps the
    // original read-only status row + conditional action behavior (a11y row).
    onStatusRowClick: (() -> Unit)? = null,
) {
    SettingsSection(
        header = stringResource(sectionHeaderRes),
        footer = stringResource(sectionFooterRes),
    ) {
        // Header row: CLI title + description, with a colored leading icon.
        SettingsRow(
            icon = iconVector,
            iconColor = iconTint,
            title = stringResource(toolTitleRes(toolName)),
            subtitle = stringResource(descriptionRes),
            showChevron = false,
        )

        // Agent tri-state policy.
        AgentPolicyRow(toolName = toolName, showDivider = true)

        // System-layer status. When [onStatusRowClick] is provided the row is a
        // navigation entry (chevron, always shown); otherwise it's read-only.
        SettingsRow(
            title = stringResource(R.string.perm_system_authorization),
            onClick = onStatusRowClick,
            showChevron = onStatusRowClick != null,
            showDivider = onStatusRowClick == null && !systemReady,
            trailing = {
                Text(
                    text = stringResource(systemStatusTitleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (systemReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            },
        )

        // Conditional action row: only for the read-only (a11y) variant, and
        // only when the system layer needs attention. The privileged-backend
        // variant routes everything through [onStatusRowClick] above instead.
        if (onStatusRowClick == null && !systemReady) {
            SettingsRow(
                title = stringResource(systemActionTitleRes),
                onClick = onSystemAction,
                showDivider = false,
            )
        }
    }
}

/**
 * Shared tri-state row. Reused by the dedicated IntegrationSection cards
 * AND by the auto-rendered category loop below — same dropdown menu,
 * same chip in trailing.
 */
@Composable
private fun AgentPolicyRow(
    toolName: String,
    showDivider: Boolean,
) {
    // [fix/da1024-H] The level lives in SharedPreferences and is written both by
    // this row's menu and by "Reset All" in the toolbar — neither is a Compose
    // state read, so a keyless `remember` froze the first-composition value and
    // Reset All left every chip on the old level until the screen was reopened.
    // Keying on the manager's revision re-reads getLevel() after every mutation;
    // currentLevel stays a local echo for this row's own selection.
    val revision by OffloadPermissionManager.revision.collectAsState()
    var currentLevel by remember(toolName, revision) { mutableStateOf(OffloadPermissionManager.getLevel(toolName)) }
    var expanded by remember { mutableStateOf(false) }

    Box {
        SettingsRow(
            title = stringResource(R.string.perm_agent_policy),
            onClick = { expanded = true },
            showChevron = false,
            showDivider = showDivider,
            trailing = {
                Text(
                    text = levelDisplayName(currentLevel),
                    style = MaterialTheme.typography.labelLarge,
                    color = levelColor(currentLevel),
                )
            },
        )
        // T336-followup: anchor the menu's right edge to the row's right
        // edge so it grows down-and-left from the trailing chip instead
        // of Material3's default down-and-right (which on a narrow phone
        // pushed the menu off the screen edge).
        MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true) {
            for (level in OffloadPermissionManager.PermissionLevel.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            levelDisplayName(level),
                            color = levelColor(level),
                        )
                    },
                    onClick = {
                        currentLevel = level
                        OffloadPermissionManager.setLevel(toolName, level)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    tool: OffloadPermissionManager.ToolPermissionInfo,
    showDivider: Boolean,
) {
    // [fix/da1024-H] Same refresh key as AgentPolicyRow — see the note there.
    val revision by OffloadPermissionManager.revision.collectAsState()
    var currentLevel by remember(tool.toolName, revision) { mutableStateOf(OffloadPermissionManager.getLevel(tool.toolName)) }
    var expanded by remember { mutableStateOf(false) }

    Box {
        SettingsRow(
            title = toolTitle(tool),
            subtitle = tool.toolName,
            onClick = { expanded = true },
            showChevron = false,
            showDivider = showDivider,
            trailing = {
                Text(
                    text = levelDisplayName(currentLevel),
                    style = MaterialTheme.typography.labelLarge,
                    color = levelColor(currentLevel),
                )
            },
        )
        // T336-followup: anchor the menu's right edge to the row's right
        // edge so it grows down-and-left from the trailing chip instead
        // of Material3's default down-and-right (which on a narrow phone
        // pushed the menu off the screen edge).
        MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true) {
            for (level in OffloadPermissionManager.PermissionLevel.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            levelDisplayName(level),
                            color = levelColor(level),
                        )
                    },
                    onClick = {
                        currentLevel = level
                        OffloadPermissionManager.setLevel(tool.toolName, level)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun categoryHeaderRes(category: OffloadPermissionManager.PermissionCategory): Int = when (category) {
    OffloadPermissionManager.PermissionCategory.PRIVACY -> R.string.perm_section_privacy
    OffloadPermissionManager.PermissionCategory.MEDIA -> R.string.perm_section_media
    OffloadPermissionManager.PermissionCategory.SYSTEM -> R.string.perm_section_system
    // INTEGRATIONS is rendered by IntegrationSection above; this branch is
    // unreachable through the auto-loop but kept exhaustive for `when`
    // exhaustiveness. Reuse the a11y header as a harmless fallback.
    OffloadPermissionManager.PermissionCategory.INTEGRATIONS -> R.string.perm_section_a11y
}

@Composable
private fun toolTitle(tool: OffloadPermissionManager.ToolPermissionInfo): String {
    val res = toolTitleRes(tool.toolName)
    if (res == 0) return tool.displayName
    return stringResource(res)
}

private fun toolTitleRes(toolName: String): Int = when (toolName) {
    "calendar" -> R.string.perm_tool_calendar
    "location" -> R.string.perm_tool_location
    "clipboard" -> R.string.perm_tool_clipboard
    "contacts" -> R.string.perm_tool_contacts
    "photos" -> R.string.perm_tool_photos
    "notification" -> R.string.perm_tool_notification
    "speech_recognition" -> R.string.perm_tool_speech_recognition
    "a11y_cli" -> R.string.perm_tool_a11y_cli
    "shizuku_cli" -> R.string.perm_tool_shizuku_cli
    else -> 0
}

@Composable
private fun levelDisplayName(level: OffloadPermissionManager.PermissionLevel): String = stringResource(
    when (level) {
        OffloadPermissionManager.PermissionLevel.BYPASS -> R.string.perm_level_bypass
        OffloadPermissionManager.PermissionLevel.ASK_ONCE -> R.string.perm_level_ask_once
        OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> R.string.perm_level_not_allowed
    },
)

@Composable
private fun levelColor(level: OffloadPermissionManager.PermissionLevel): Color = when (level) {
    OffloadPermissionManager.PermissionLevel.BYPASS -> MaterialTheme.colorScheme.primary
    OffloadPermissionManager.PermissionLevel.ASK_ONCE -> MaterialTheme.colorScheme.tertiary
    OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> MaterialTheme.colorScheme.error
}

// [T-android-restricted-settings] "Allow restricted settings" lives in the
// overflow menu of RikkaMinis' own App info page, so App info is the only route
// the OS offers for that gate. Reuses PowerOptimizationManager's helper, which
// already falls back to generic App info when an OEM hides the restricted page.
private fun openAppDetailsSettings(context: Context) {
    (context as? Activity)?.let { PowerOptimizationManager.openAppDetailsSettings(it) }
}

private fun shizukuSubtitleRes(state: ShizukuManager.State): Int = when (state) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
    ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_state_not_running
    ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_state_need_permission
    ShizukuManager.State.READY -> R.string.shizuku_state_ready
}

// T336: state-dependent action row title — different verbs per phase of
// the Shizuku setup funnel.
private fun shizukuActionTitleRes(state: ShizukuManager.State): Int = when (state) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_install_btn
    ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_open_btn
    ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_grant_btn
    ShizukuManager.State.READY -> 0  // never displayed (systemReady=true)
}
