package com.rikkaminis.app.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.R
import com.rikkaminis.app.accessibility.RestrictedSettingsManager
import com.rikkaminis.app.offload.ShizukuManager
import com.rikkaminis.app.power.PowerOptimizationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rikkaminis.app.ui.theme.ChatColors

/**
 * T323: surfaces OS-level permission states (Accessibility service,
 * etc.) the user must grant via external Settings flows. Each row
 * reports current status and routes the user to the relevant system
 * settings page; we re-poll while the screen is visible so coming
 * back from system Settings refreshes the row automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // [dv15-B2 / B-08] Shared classification (A11yStatus) — this screen used to
    // carry its own `inSettings || connected` copy of the decision that the
    // reachable screen forked away from.
    var a11yEnabled by remember { mutableStateOf(a11yStatusOf(context) == A11yStatus.READY) }
    // [T-android-a11y-miui-service-failure] "Service failed" degraded state:
    // the OEM ROM (MIUI etc.) reports the service as enabled in Settings but
    // has killed the process / revoked the binding, so getInstance() is null.
    // On such a device the canonical remediation is autostart-whitelist +
    // battery "no restrictions"; surface those (reusing PowerOptimizationManager)
    // only when the service is degraded AND the vendor is known to enforce it.
    var a11yDegraded by remember { mutableStateOf(false) }
    // [T-android-a11y-force-stop-recovery] Distinct from `a11yDegraded`: the
    // grant itself is gone from Settings.Secure (force-stop stripped it), which
    // is repairable by writing the setting back. `a11yDegraded` is the opposite
    // case — grant present, process killed — where writing would be pointless.
    var a11yRevoked by remember { mutableStateOf(false) }
    var shizukuReady by remember { mutableStateOf(false) }
    // [T-android-restricted-settings] Android 13+ flags installs whose
    // installer declared a local/downloaded-file package source and refuses to
    // arm the accessibility toggle until the user allows restricted settings.
    // Only meaningful while the service is still off — once it is enabled the
    // user has clearly already gotten past the gate.
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val status = a11yStatusOf(context)
            a11yEnabled = status == A11yStatus.READY
            a11yDegraded = status == A11yStatus.DEGRADED
            // Only claim "revoked" once the user has actually granted it at
            // least once — otherwise a first-run user who has never enabled the
            // service would be shown a "repair" prompt for something that was
            // never broken.
            a11yRevoked = status == A11yStatus.REVOKED
            shizukuReady = ShizukuManager.isReady()
            // Re-probed each tick so the section disappears by itself the
            // moment the user allows restricted settings in system Settings
            // and comes back.
            a11yRestricted = !a11yEnabled && RestrictedSettingsManager.isRestricted(context)
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                header = stringResource(R.string.system_permissions_section_a11y),
                footer = stringResource(R.string.system_permissions_a11y_footer),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Accessibility,
                    iconColor = ChatColors.success,
                    title = stringResource(R.string.system_permissions_a11y_row),
                    subtitle = if (a11yEnabled)
                        stringResource(R.string.system_permissions_a11y_enabled)
                    else
                        stringResource(R.string.system_permissions_a11y_disabled),
                    onClick = { openAccessibilitySettings(context) },
                    showDivider = false,
                )
            }

            // [T-android-restricted-settings] Shown ONLY when the OS has
            // actually flagged this install (appop ACCESS_RESTRICTED_SETTINGS
            // == MODE_ERRORED) and the service is still off — i.e. exactly the
            // state where tapping the row above leads to a dead, greyed-out
            // toggle. Without this the user sees "tap to enable in system
            // Settings", follows it, and hits a wall with no explanation.
            //
            // We cannot clear the flag ourselves: it is set on our own uid and
            // clearing needs the signature permission MANAGE_APP_OPS_MODES.
            // That is deliberate — the policy exists to stop a sideloaded app
            // from talking the user into granting it screen-reading power — so
            // the manual route is the primary affordance and stays visible even
            // when the Shizuku shortcut is offered.
            if (a11yRestricted) {
                SettingsSection(
                    header = stringResource(R.string.system_permissions_a11y_restricted_header),
                    footer = stringResource(R.string.system_permissions_a11y_restricted_footer),
                ) {
                    if (shizukuReady) {
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
                        // Lands on RikkaMinis' own App info page, where the
                        // "Allow restricted settings" item lives in the
                        // overflow menu. Reuses the existing helper, which
                        // already falls back when an OEM hides the page.
                        onClick = {
                            (context as? Activity)?.let {
                                PowerOptimizationManager.openAppDetailsSettings(it)
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            A11yRevokedRepairSection(revoked = a11yRevoked, shizukuReady = shizukuReady)

            A11yOemKeepAliveSection(degraded = a11yDegraded)
        }
    }
}
