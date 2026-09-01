package com.openminis.app

import android.app.LocaleManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.deeplink.DeepLinkHandler

import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.navigation.AppNavigation
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.navigation.safeNavigate
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.keepScreenAwakeEnabled
import com.openminis.app.ui.theme.MinisTheme

private const val KEY_CURRENT_CHAT_SESSION_ID = "minis.current_chat_session_id"
/** [P3-crash-recovery] 进程级持久化的 lastSessionId（独立 prefs 文件，非 savedInstanceState）。
 * native SIGABRT 不经过 Activity 生命周期，saved state 会丢；这个文件
 * 在每次进入 chat 路由时同步写入，作为崩溃后「重进即回到崩溃前会话」的可靠来源。 */
private const val PREF_CRASH_RECOVERY = "minis_crash_recovery"
private const val KEY_LAST_CHAT_SESSION_ID = "last_chat_session_id"

class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    /**
     * T166: id of the chat the user is currently inside, mirrored from
     * the nav back-stack so [onSaveInstanceState] can persist it across
     * process death. Restored value is fed into [AppNavigation] as the
     * initial deep-link so the user lands back where they were.
     */
    private var currentChatSessionId: String? = null

    /**
     * T166: id of the chat to re-open on first composition after a
     * process-death restart. Set in [onCreate] from the saved state
     * bundle, consumed exactly once by [AppNavigation] via the
     * synthesised deep-link.
     */
    private var restoredChatSessionId: String? = null

    /**
     * T293 / issue #10: tracks whether [onStart] has fired at least once, so
     * that the very first ON_START (the cold-start one — already covered by
     * [com.openminis.app.ui.navigation.AppNavigation]'s LaunchedEffect that
     * resolves [KEY_LAUNCH_SESSION]) is skipped here. Subsequent ON_STARTs
     * are background → foreground transitions, where the launch-session
     * preference must be honoured again so "New Chat on launch" actually
     * does what it says when the user resumes from the recents tray.
     */
    private var hasResumedFromBackground = false

    /**
     * T293 follow-up / launch-resume navigation fix: [onStart] fires while
     * the Activity (and therefore the current NavBackStackEntry) is still
     * STARTED — not RESUMED. `safeNavigate`'s RESUMED guard silently drops
     * the launch-session navigation in exactly that window, so "New Chat on
     * launch" did nothing when resuming from the recents tray (log evidence
     * 2026-09-01: "resume → mode=NewChat, navigating to …" logged, but the
     * destination never composed — no ChatScreen MOUNT for the new route).
     * Defer the actual navigate to the first [onResume] after [onStart].
     */
    private var pendingLaunchSessionRoute: String? = null

    /**
     * Used by the "go to settings" gate to open e.g. the app-details or
     * Notification Access settings page. We don't rely on the result —
     * [OffloadPermissionManager.requestSettingsGate] polls until the
     * permission actually changes (or times out).
     */
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>

    /**
     * State shuttled between the "Save to..." path of the safe-mode
     * crash-share dialog and the `crashSaveLauncher` registered in
     * [onCreate]. The lambda fires after the launcher's result callback
     * completes so the caller (which passes `finish()` for safe-mode)
     * can tear down the Activity once the file is on disk and the
     * follow-on ACTION_VIEW has been dispatched.
     */
    private var pendingCrashZip: java.io.File? = null
    private var pendingCrashSaveOnClosed: (() -> Unit)? = null

    // T-n01-andmenu-l10n: on Android 12 and below `LocaleManager.applicationLocales`
    // does not exist, so the saved language pick has no effect on framework
    // strings — including the labels of the system text-selection ActionMode
    // ("Cut" / "Copy" / "Paste"). Override the base Configuration so the
    // framework picks the right locale on those devices. No-op on 13+.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the crash-share "Save to..." launcher BEFORE the
        // safe-mode early-return below — ActivityResultLauncher must be
        // registered before STARTED, and the safe-mode path needs it.
        // The launcher pairs with `pendingCrashZip` to copy the zip into
        // whatever URI the user picked in the system file picker, then
        // opens that URI with ACTION_VIEW so they see it land.
        val crashSaveLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val targetUri = result.data?.data
            val zip = pendingCrashZip
            pendingCrashZip = null
            if (result.resultCode != android.app.Activity.RESULT_OK || targetUri == null || zip == null) {
                pendingCrashSaveOnClosed?.invoke()
                pendingCrashSaveOnClosed = null
                return@registerForActivityResult
            }
            try {
                contentResolver.openOutputStream(targetUri)?.use { out ->
                    java.io.FileInputStream(zip).use { input -> input.copyTo(out) }
                } ?: throw java.io.IOException("openOutputStream returned null")
                // Auto-open the saved file so the user sees where it
                // landed. ACTION_VIEW with the SAF URI hands control to
                // whatever app the system associates with .zip /
                // application/zip (file managers, archive viewers).
                try {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(targetUri, "application/zip")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(view)
                } catch (t: Throwable) {
                    android.util.Log.w("MainActivity", "ACTION_VIEW after save failed: ${t.message}")
                }
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "crash zip save failed: ${t.message}")
            } finally {
                pendingCrashSaveOnClosed?.invoke()
                pendingCrashSaveOnClosed = null
            }
        }

        // Safe-mode short-circuit: if CrashFrequencyDetector tripped in
        // MinisApp.onCreate (≥THRESHOLD recent crash files), the
        // Application skipped all heavy init — no DB, no repos, no
        // offload server. We must NOT call setContent() / ChatViewModel /
        // any code that touches MinisApp's lateinit deps; doing so would
        // throw UninitializedPropertyAccessException and overwrite the
        // very crash logs we're trying to ship.
        //
        // We gate on isSafeMode() OR isInitSkipped(). The latch matters
        // because finishClose() clears `_safeMode` (so the NEXT cold start
        // boots fresh), which momentarily makes isSafeMode() return false
        // while this exact process has still skipped init. If the activity
        // recreates in that window (config change while the dialog is up),
        // the isSafeMode()==false branch would happily setContent() and
        // throw on the still-uninitialized `lateinit chatRepository`. The
        // latch is one-way for the process, so it stays true for the whole
        // life of this process no matter how often _safeMode flips.
        //
        // Pop the share/dismiss dialog directly and finish() on close so
        // the user's next launch starts fresh. The dialog UI uses
        // AlertDialog (system-level) which doesn't touch MinisApp state.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode() ||
            com.openminis.app.crash.CrashFrequencyDetector.isInitSkipped()
        ) {
            android.util.Log.w("MainActivity", "safe-mode ON — showing crash share dialog and finishing")
            com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(
                activity = this,
                onClosed = { finish() },
                saveLauncher = { zip, onSaveDone ->
                    pendingCrashZip = zip
                    pendingCrashSaveOnClosed = onSaveDone
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, zip.name)
                    }
                    try {
                        crashSaveLauncher.launch(intent)
                    } catch (t: Throwable) {
                        android.util.Log.w("MainActivity", "CREATE_DOCUMENT launch failed: ${t.message}")
                        pendingCrashZip = null
                        pendingCrashSaveOnClosed = null
                        onSaveDone()
                    }
                },
            )
            return
        }

        // T166: if we were killed by LMK while the user was inside a
        // chat, restore the sessionId now so the synthesised deep-link
        // re-opens it before any composable is composed. ChatViewModel
        // rehydrates messages from SQLite via session id, so the user
        // lands in the same chat with the same history rendered.
        // Process death loses streamJob coroutine state — any in-flight
        // stream is treated as cancelled and the user can tap Retry.
        // Honour the crash-frequency force-home grace: when the previous
        // boot tripped the burst detector we don't want LMK-restore to
        // drop the user straight back into the chat that may have been
        // the crash trigger. AppNavigation also reads this flag for its
        // own launch-mode resolution; gating restoredChatSessionId here
        // covers the path where saved-state would override the
        // launch-mode dispatch entirely.
        restoredChatSessionId = savedInstanceState?.getString(KEY_CURRENT_CHAT_SESSION_ID)
            // [P3-crash-recovery] If the saved-state bundle is empty (native
            // SIGABRT doesn't write one), fall back to the process-level
            // last-session file written on every chat-route change. Either way,
            // honour the crash-frequency force-home grace so we don't drop the
            // user straight back into the chat that may have been the crash trigger.
            //
            // [fix/lang-switch-nav-jump] The process-level fallback must ONLY
            // fire on a true cold start (savedInstanceState == null, i.e. the
            // SIGABRT/LMK path where onSaveInstanceState never ran). On an
            // Activity recreation (configuration change — e.g. switching
            // language in Settings → Appearance) the bundle is non-null and is
            // the authoritative source: when the user is NOT inside a chat the
            // bundle carries no session id, and falling back to the stale
            // KEY_LAST_CHAT_SESSION_ID would yank them from Settings back into
            // the previous chat.
            ?: if (savedInstanceState == null) {
                getSharedPreferences(PREF_CRASH_RECOVERY, MODE_PRIVATE)
                    .getString(KEY_LAST_CHAT_SESSION_ID, null)
            } else {
                null
            }
            ?.takeUnless {
                com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(this)
            }

        settingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* polled by OffloadPermissionManager.requestSettingsGate */ }

        // Bridge: in-app "open settings" dialog. Used for already-denied
        // runtime permissions AND for special-access capabilities like
        // Notification Access.
        lifecycleScope.launch {
            OffloadPermissionManager.pendingSettingsGate
                .filterNotNull()
                .collect { gate ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(gate.title)
                        .setMessage(gate.message)
                        .setCancelable(false)
                        .setPositiveButton(gate.positiveLabel) { d, _ ->
                            d.dismiss()
                            val intent = Intent(gate.settingsAction).apply {
                                if (gate.requiresPackageUri) {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                settingsLauncher.launch(intent)
                            } catch (_: Throwable) {
                                // Some OEMs don't expose every settings panel.
                                // Fall through — the polling loop will time out.
                            }
                            OffloadPermissionManager.respondToSettingsGate(
                                OffloadPermissionManager.SettingsGateDecision.OPEN
                            )
                        }
                        .setNegativeButton(gate.negativeLabel) { d, _ ->
                            d.dismiss()
                            OffloadPermissionManager.respondToSettingsGate(
                                OffloadPermissionManager.SettingsGateDecision.CANCEL
                            )
                        }
                        .show()
                }
        }

        // Apply saved language before composing UI
        applySavedLanguage()

        // T51: ShareReceiverActivity re-launches MainActivity with the
        // `shared_content=true` extra after persisting a PendingShare to
        // share_prefs. Process it now so ShareCoordinator's in-memory
        // buffer is populated before any ChatScreen composes.
        if (intent?.getBooleanExtra("shared_content", false) == true) {
            com.openminis.app.share.ShareCoordinator.processPendingShare(this)
        }

        // Wire FLAG_KEEP_SCREEN_ON to (Appearance → Keep Screen Awake) AND
        // SessionActivityTracker.activeSessions. Lock held iff toggle is on
        // AND at least one session is currently running a task. Mirrors iOS
        // KeepScreenAwakeController (AIChatViewModel.swift:320), which holds
        // UIApplication.isIdleTimerDisabled under the same conditions.
        // Re-evaluates on every prefs change (the existing prefs listener
        // below covers the toggle) and on every activeSessions transition.
        lifecycleScope.launch {
            SessionActivityTracker.activeSessions.collect { active ->
                applyKeepScreenAwakeFlag(active.isNotEmpty())
            }
        }

        // Register for debug screenshot capture (debug builds only)
        if (BuildConfig.DEBUG) {
            com.openminis.app.debug.DebugRPCHandler.currentActivity = java.lang.ref.WeakReference(this)
        }

        val app = application as MinisApp

        // Parse deep link from launch intent. A real deep-link in the
        // launch intent always wins over a saved-state restore (the
        // user explicitly tapped a link). Otherwise, if we were killed
        // while inside a chat, synthesise an OpenSession deep-link so
        // the navigation stack lands on that chat instead of the
        // sessions list. T166.
        val explicitDeepLink = DeepLinkHandler.parse(intent?.data)
        val launchDeepLink = if (explicitDeepLink !is DeepLinkAction.Unknown) {
            explicitDeepLink
        } else {
            restoredChatSessionId
                ?.let { DeepLinkAction.OpenSession(it) }
                ?: DeepLinkAction.Unknown
        }
        // [fix/lang-switch-nav-jump] True when this onCreate is NOT a genuine
        // cold start: either an Activity recreation (configuration change —
        // e.g. language switch) or a process-death/LMK restore. Both arrive
        // with a non-null savedInstanceState and in BOTH cases the
        // NavController (rememberSaveable + NavControllerSaver) restores its
        // own back stack, so AppNavigation must NOT re-run the cold-start
        // launch-mode dispatch (which would jump the user back to a chat).
        //
        // NOTE: do NOT gate on isChangingConfigurations() — that flag is only
        // true on the OLD instance while it is being destroyed; in the new
        // instance's onCreate it is always false. Real-device logs confirmed
        // the previous `savedInstanceState != null && isChangingConfigurations`
        // gate never fired, letting the dispatcher run after a language
        // switch and yank the user into the draft chat.
        val hasSavedNavState = savedInstanceState != null

        setContent {
            val prefs = remember { getAppearancePrefs(this) }
            var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
            var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                    when (key) {
                        KEY_THEME_MODE -> themeMode = sp.getInt(KEY_THEME_MODE, 0)
                        KEY_FONT_APP_BASE -> appBaseLevel = sp.getInt(KEY_FONT_APP_BASE, 0)
                        KEY_KEEP_SCREEN_AWAKE -> applyKeepScreenAwakeFlag(
                            SessionActivityTracker.activeSessions.value.isNotEmpty()
                        )
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val fontScale = fontScaleForLevel(appBaseLevel)

            SideEffect {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    // T205: SystemBarStyle.light(...) auto-applies a ~30% black
                    // scrim behind the status bar on Android 13+ for icon
                    // legibility, which leaks through as a grey strip even
                    // when the activity paints its own surface color
                    // edge-to-edge. SystemBarStyle.auto with both scrim args
                    // TRANSPARENT lets the system pick light/dark icons by
                    // luminance but suppresses the auto-scrim, so the screen's
                    // own background color paints all the way to the top/bottom
                    // edges. Per-screen `isAppearanceLightStatusBars` overrides
                    // (e.g. FilePreviewScreen DisposableEffect) still control
                    // icon contrast.
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }

            // [T3-theme-switch-nav-jump] Hoist the NavHost controller OUT of the
            // MinisTheme content lambda. Switching theme_mode (Settings →
            // Appearance) changes `darkTheme`, so MinisTheme re-invokes its
            // content lambda with a different colorScheme argument. A
            // rememberNavController() declared INSIDE that lambda sits inside
            // the theme subtree: on a full subtree recomposition after theme
            // change its remember slot can be invalidated, rebuilding the
            // NavHost from startDestination (always a chat route) — which
            // manifested as "tapping Light/Dark yanks the user back into a
            // chat". Hoisted to the setContent root it survives every theme
            // recomposition, so the back stack is untouched.
            val navController = rememberNavController().also { this.navController = it }

            MinisTheme(darkTheme = darkTheme, fontScale = fontScale) {

                // T166: drive `SessionActivityTracker.setPresent` /
                // `setAbsent` from the nav back-stack so the foreground
                // service runs the entire time the user is inside a
                // chat, not only while a stream is in flight. Without
                // this hook the process drops from adj=200 to adj=700
                // the moment Home is pressed and a Pixel 4a will
                // reclaim within a couple of minutes (see
                // docs/parity/android-keep-alive-audit.md).
                DisposableEffect(navController) {
                    val job = lifecycleScope.launch {
                        navController.currentBackStackEntryFlow.collect { entry ->
                            val isChatRoute = entry.destination.route == Routes.CHAT
                            val sid = entry.arguments?.getString("sessionId").takeIf { isChatRoute }
                            val previous = currentChatSessionId
                            if (sid != previous) {
                                if (previous != null) {
                                    SessionActivityTracker.setAbsent(previous)
                                }
                                if (sid != null) {
                                    SessionActivityTracker.setPresent(sid)
                                }
                                currentChatSessionId = sid
                                // [P3-crash-recovery] Persist the last-opened
                                // session id at the process level so a native
                                // crash (no saved-state bundle) can still land
                                // the user back in this chat on next launch.
                                if (sid != null) {
                                    getSharedPreferences(PREF_CRASH_RECOVERY, MODE_PRIVATE)
                                        .edit()
                                        .putString(KEY_LAST_CHAT_SESSION_ID, sid)
                                        .apply()
                                }
                            }
                        }
                    }
                    onDispose { job.cancel() }
                }

                AppNavigation(
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    envVarRepository = app.envVarRepository,
                    skillRepository = app.skillRepository,
                    mcpRepository = app.mcpRepository,
                    memoryRepository = app.memoryRepository,
                    navController = navController,
                    initialDeepLink = launchDeepLink,
                    isActivityRecreation = hasSavedNavState,
                )

                // T-config: root-level minis-config confirm dialog.
                // Bound to ConfigConfirmationGate.pending — the gate
                // fires whenever a CLI write is awaiting user OK. The
                // dialog is rendered on top of any active screen, so
                // it works regardless of where the user is when the
                // agent triggers a change. Mirrors iOS MinisApp.swift
                // root-level `.sheet(item: gate.pending)`.
                com.openminis.app.ui.settings.ConfigConfirmDialogHost()
            }
        }
    }

    /**
     * T166: persist the current chat sessionId so an LMK kill while in
     * chat restarts back to the same session (see `restoredChatSessionId`
     * in [onCreate]). Called by the OS in the same lifecycle phase that
     * Activity Recreation uses, so a configuration change also goes
     * through this path — which is exactly what we want; the state is
     * cheap and re-read is idempotent.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentChatSessionId?.let {
            outState.putString(KEY_CURRENT_CHAT_SESSION_ID, it)
        }
    }

    /**
     * T293 / issue #10: re-honour the "Launch Session" preference when the
     * app comes back to the foreground from the recents tray. Cold start
     * is already covered by [com.openminis.app.ui.navigation.AppNavigation]
     * resolving [KEY_LAUNCH_SESSION] in its `LaunchedEffect(Unit)` —
     * skipped here via [hasResumedFromBackground] so we don't double-fire
     * on the first start.
     *
     * Scoped to mode 2 (New Chat). Modes 0 (Auto) / 1 (Last Session) /
     * 3 (Home) are intentionally NOT re-evaluated on resume — bouncing
     * the user out of whatever screen they were on every time they
     * returned from the recents tray would be jarring. "New Chat" is the
     * only mode whose semantics genuinely demand a fresh chat on every
     * activation (cold or warm) — that's exactly the user expectation
     * the issue is reporting on.
     */
    override fun onStart() {
        super.onStart()
        if (!hasResumedFromBackground) {
            hasResumedFromBackground = true
            return
        }
        val mode = getAppearancePrefs(this).getInt(KEY_LAUNCH_SESSION, 0)
        if (mode != 2) return
        val nav = navController ?: return
        val newRoute = Routes.chat("__new__${java.util.UUID.randomUUID()}")
        AppLogger.info("LaunchSession", "resume → mode=NewChat, deferring navigate to onResume (lifecycle=$lifecycle.currentState)")
        pendingLaunchSessionRoute = newRoute
    }

    override fun onResume() {
        super.onResume()
        // [launch-resume navigation fix] See [pendingLaunchSessionRoute]: the
        // navigate deferred from onStart lands here, where the Activity and
        // the NavBackStackEntry are at RESUMED so the navigation actually
        // composes the destination. Exactly-once — the pending route is
        // consumed whether or not the navigate succeeds.
        val pendingRoute = pendingLaunchSessionRoute ?: return
        pendingLaunchSessionRoute = null
        val nav = navController ?: return
        AppLogger.info("LaunchSession", "onResume executing deferred navigate to $pendingRoute")
        nav.safeNavigate(pendingRoute) {
            popUpTo(nav.graph.startDestinationId) { inclusive = true }
        }
    }

    /**
     * T166: when the Activity is genuinely torn down (not just paused),
     * release any presence we held. Pause / Home / lock-screen do NOT
     * trigger this — those are exactly the cases the FG service exists
     * to bias OOM through.
     */
    override fun onDestroy() {
        currentChatSessionId?.let { SessionActivityTracker.setAbsent(it) }
        currentChatSessionId = null
        super.onDestroy()
    }

    /**
     * Apply (or release) the activity window's `FLAG_KEEP_SCREEN_ON` based on
     * the user's "Keep Screen Awake" toggle and whether any session has an
     * active task right now. Idempotent — flipping with the same desired
     * state is a no-op at the WindowManager level.
     */
    private fun applyKeepScreenAwakeFlag(hasActiveSession: Boolean) {
        val want = keepScreenAwakeEnabled(this) && hasActiveSession
        if (want) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.info("KeepScreenAwake", "screen-on lock acquired (active sessions present)")
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.info(
                "KeepScreenAwake",
                "screen-on lock released (toggle=${keepScreenAwakeEnabled(this)}, active=$hasActiveSession)",
            )
        }
    }

    private fun applySavedLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val code = getSharedPreferences(PREF_APPEARANCE, MODE_PRIVATE).getString(KEY_LANGUAGE, "") ?: ""
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        val desired = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        if (localeManager.applicationLocales.toLanguageTags() != desired.toLanguageTags()) {
            localeManager.applicationLocales = desired
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // T51: warm-start share — ShareReceiverActivity re-launches with
        // FLAG_ACTIVITY_CLEAR_TOP, which delivers the new intent here when
        // MainActivity is already alive. Process the buffered share before
        // touching the deep-link path so the share coordinator sees it.
        if (intent.getBooleanExtra("shared_content", false)) {
            com.openminis.app.share.ShareCoordinator.processPendingShare(this)
        }
        handleDeepLink(intent.data)
    }

    private fun handleDeepLink(uri: Uri?) {
        val action = DeepLinkHandler.parse(uri)
        val nav = navController ?: return
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                nav.navigate(Routes.terminal(action.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                // T-double-chat-fix (secondary): mirror AppNavigation's
                // OpenSession options so a runtime deep-link (notification /
                // shortcut / onNewIntent) can't stack a duplicate chat on
                // top of the same chat that's already showing.
                nav.navigate(Routes.chat(action.sessionId)) {
                    popUpTo(nav.graph.startDestinationId) {
                        inclusive = true
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(action.key, action.value, action.note)
                nav.navigate(Routes.ENV_VARS)
            }
            // T183: any settings screen reachable by route string.
            is DeepLinkAction.OpenSettingsScreen -> {
                nav.navigate(action.route)
            }
            is DeepLinkAction.OpenPermissionSettings -> {
                nav.navigate(Routes.PERMISSIONS)
            }
            is DeepLinkAction.OpenHtmlPreview -> {
                DeepLinkCoordinator.setPendingHtmlPreview(
                    action.sessionId,
                    action.resourcePath,
                    action.title,
                )
                nav.navigate(Routes.chat(action.sessionId))
            }
            // App-icon quick actions (mirrors iOS QuickActionRouter). All
            // both open a fresh draft chat; camera additionally seeds
            // DeepLinkCoordinator.pendingChatAction so ChatScreen auto-fires
            // the camera on first compose. (Voice shortcut removed with the
            // mic button.)
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewCameraChat -> {
                when (action) {
                    is DeepLinkAction.NewCameraChat -> DeepLinkCoordinator
                        .setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
                    else -> {}
                }
                val newRoute = Routes.chat("__new__${java.util.UUID.randomUUID()}")
                nav.navigate(newRoute) {
                    popUpTo(nav.graph.startDestinationId) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is DeepLinkAction.OpenAlarmList -> {
                // T297: minis://views/alarm now opens the system Clock app
                // directly via AlarmClock.ACTION_SHOW_ALARMS — the in-app
                // AlarmListScreen was a one-button passthrough that did the
                // exact same thing. The android-alarm tool envelope still
                // emits this view_url so existing chat cards keep working.
                val showAlarms = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(showAlarms)
                } catch (_: android.content.ActivityNotFoundException) {
                    AppLogger.warning("DeepLink", "OpenAlarmList: no Clock app handles SHOW_ALARMS")
                }
            }
            else -> {}
        }
    }
}
