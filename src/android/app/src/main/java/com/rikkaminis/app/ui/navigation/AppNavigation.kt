package com.rikkaminis.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rikkaminis.app.deeplink.DeepLinkAction
import com.rikkaminis.app.deeplink.DeepLinkCoordinator
import com.rikkaminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.rikkaminis.app.ui.settings.getAppearancePrefs
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.chat.ChatScreen
import com.rikkaminis.app.ui.settings.AddAgentLoopGroupsScreen
import com.rikkaminis.app.ui.settings.AddAgentLoopModelsScreen
import com.rikkaminis.app.ui.settings.AgentLoopModelsScreen
import com.rikkaminis.app.ui.settings.AddCustomModelScreen
import com.rikkaminis.app.ui.settings.BackgroundSettingsScreen
import com.rikkaminis.app.ui.settings.AddModelsToGroupScreen
import com.rikkaminis.app.ui.settings.AddProviderScreen
import com.rikkaminis.app.ui.settings.ModelEntryDetailScreen
import com.rikkaminis.app.ui.settings.ModelGroupDetailScreen
import com.rikkaminis.app.ui.settings.ModelGroupsScreen
import com.rikkaminis.app.ui.settings.ProviderConnectionScreen
import com.rikkaminis.app.ui.settings.ProviderDetailScreen
import com.rikkaminis.app.ui.settings.ProviderListScreen
import com.rikkaminis.app.ui.sandbox.FileBrowserScreen
import com.rikkaminis.app.ui.sandbox.FileBrowserViewModel
import com.rikkaminis.app.ui.sandbox.FileItem
import com.rikkaminis.app.ui.sandbox.FilePreviewScreen
import com.rikkaminis.app.ui.sandbox.RootfsManagementScreen
import com.rikkaminis.app.ui.settings.EnvironmentVariablesScreen
import com.rikkaminis.app.ui.settings.AppearanceScreen
import com.rikkaminis.app.ui.settings.ChatMenuSettingsScreen
import com.rikkaminis.app.ui.settings.SettingsScreen
import com.rikkaminis.app.ui.settings.SystemPermissionsScreen
import com.rikkaminis.app.ui.settings.SessionStorageDetailScreen
import com.rikkaminis.app.ui.settings.SkillDetailScreen
import com.rikkaminis.app.ui.settings.StorageManagementScreen
import com.rikkaminis.app.ui.settings.SkillFileViewerScreen
import com.rikkaminis.app.ui.settings.UsageStatsScreen
import com.rikkaminis.app.ui.settings.MinisSkillsBrowserScreen
import com.rikkaminis.app.ui.settings.MountDetailScreen
import com.rikkaminis.app.ui.settings.MountedFoldersScreen
import com.rikkaminis.app.ui.settings.SharedFolderDetailScreen
import com.rikkaminis.app.ui.settings.SharedFoldersScreen
import com.rikkaminis.app.ui.settings.SkillsManagementScreen
import com.rikkaminis.app.data.repository.EnvVarRepository
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.data.repository.SkillRepository
import com.rikkaminis.app.ui.settings.LogDetailScreen
import com.rikkaminis.app.ui.settings.LogManagementScreen
import com.rikkaminis.app.ui.settings.MemoryFileEditScreen
import com.rikkaminis.app.ui.settings.MemoryManagementScreen
import com.rikkaminis.app.ui.settings.OffloadPermissionScreen
import com.rikkaminis.app.ui.settings.ShizukuPermissionScreen
import com.rikkaminis.app.sandbox.RootfsManager
import com.rikkaminis.app.sandbox.TerminalSession
import com.rikkaminis.app.ui.terminal.TerminalScreen
import com.rikkaminis.app.ui.onboarding.OnboardingModelSelectionScreen

// T342: Material 3 motion easing curves. Compose-Material3 (1.3.x) ships
// `MotionScheme` only in 1.4-alpha; mirror the spec values directly so we
// don't take a dependency-bump tax just for two CubicBezierEasing instances.
// Source: m3.material.io/styles/motion/easing-and-duration/tokens-specs
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

/**
 * Cold-start placeholder detection.
 *
 * [AppNavigation] always mounts the NavHost at a throwaway "__new__" draft
 * chat (startDestination), then the launch-mode dispatcher immediately
 * re-navigates to the real target (last session / fresh draft / restored
 * session). That post-mount hop is an implementation detail of startup —
 * playing the M3 shared-axis slide for it reads as a bogus "enter the app"
 * gesture: the user taps the launcher icon and the whole screen slides left
 * into the chat instead of just being there.
 *
 * Real session ids are plain UUIDs (ChatRepository); only the draft
 * placeholder carries the "__new__" prefix, so the match is unambiguous.
 * The gate applies to the outgoing side (initialState) — on every cold-start
 * hop the placeholder is the entry being replaced. In-app navigations
 * (drawer session switch, New Chat from a real session, settings, …) have a
 * real chat on the outgoing side and keep the slide.
 */
private val NavBackStackEntry.isColdStartPlaceholder: Boolean
    get() = arguments?.getString("sessionId")?.startsWith("__new__") == true

object Routes {
    /**
     * [P0-0] `focusMessageId` is an OPTIONAL query arg: any caller that just
     * wants "open this session" keeps calling [chat] with one argument and
     * gets the exact pre-P0-0 behaviour (nav supplies null via defaultValue).
     * When present, ChatScreen scrolls to that message once and briefly
     * highlights it — the shared primitive behind search results, bookmarks,
     * translation and range export.
     *
     * Declared as a query param rather than a second path segment so
     * `destination.route` string comparisons elsewhere (e.g. MainActivity's
     * SessionActivityTracker hook, which matches on the route TEMPLATE) keep
     * working, and so all 20+ existing `Routes.chat(id)` call sites compile
     * untouched.
     */
    const val CHAT = "chat/{sessionId}?focusMessageId={focusMessageId}"
    const val SETTINGS = "settings"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    /** [T-provider-connection-screen] Connection/credential sub-page for a
     *  provider (API key, OAuth, custom base URL, format, image endpoint).
     *  Reached from the "API & Connection" row on ProviderDetailScreen. */
    const val PROVIDER_CONNECTION = "provider_connection/{instanceId}"
    /** [T-android-provider-voice] Read-only shadow Voice Service detail. */
    const val MODEL_GROUPS = "model_groups"
    const val MODEL_GROUP_DETAIL = "model_group/{groupId}"
    const val ADD_MODELS_TO_GROUP = "add_models_to_group/{groupId}"
    /** T185: picker that adds model *entries* to the agent-loop set. */
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    /** T185: picker that adds model *groups* to the agent-loop set. */
    const val ADD_GROUPS_TO_AGENT_LOOP = "add_groups_to_agent_loop"
    /** [model-groups-simplify] Agent Loop Models — standalone screen reached
     *  from the entry row on ModelGroupsScreen. */
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val FILE_BROWSER = "file_browser"
    const val FILE_PREVIEW = "file_preview"
    const val ENV_VARS = "env_vars"
    /** [feat/runtime-limits-panel] Runtime Limits page (agent runtime knobs). */
    const val RUNTIME_LIMITS = "runtime_limits"
    /** [feat/chat-tuning-panel] Chat Tuning page (reading / scrolling / composer knobs). */
    const val CHAT_TUNING = "chat_tuning"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"
    const val MINIS_SKILLS_BROWSER = "minis_skills_browser"

    fun skillDetail(skillId: String) = "skill/$skillId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String {
        // Path may contain `/`, which the nav library treats as a route
        // separator. URL-encode so subdirectory paths survive a round-trip.
        val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
        return "skill_file/$skillId/$encoded"
    }
    const val TERMINAL = "terminal?initCommand={initCommand}&sessionId={sessionId}"
    fun terminal(initCommand: String? = null, sessionId: String? = null): String {
        // URLEncoder follows application/x-www-form-urlencoded — spaces become `+`.
        // Nav library only %-decodes the route, so `+` would reach the screen literally.
        // Replace `+` with `%20` so Nav decodes it back to a space.
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            if (initCommand != null) add("initCommand=${enc(initCommand)}")
            if (sessionId != null) add("sessionId=${enc(sessionId)}")
        }
        return if (params.isEmpty()) "terminal" else "terminal?${params.joinToString("&")}"
    }
    /** Chat-files browser: opens FileBrowser rooted at /var/minis for the session. */
    const val CHAT_FILES = "chat_files/{sessionId}"
    fun chatFiles(sessionId: String) = "chat_files/$sessionId"
    const val MEMORY = "memory"
    /** [T-mcp-integration-android] MCP Integrations management screen. */
    const val MCP = "mcp"
    /** [T-soul-md] SOUL.md editor. */
    const val SOUL = "soul"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    const val PERMISSIONS = "permissions"
    /**
     * T322 / [T-android-privileged-backend]: Shizuku-protocol manager
     * walkthrough — handles both Shizuku and AXManager (they share the same
     * binder protocol + client SDK).
     */
    const val SHIZUKU = "shizuku"
    /** T323: System Permissions (Accessibility service status, etc.). */
    const val SYSTEM_PERMISSIONS = "system_permissions"
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    const val APPEARANCE = "appearance"
    const val CHAT_MENU = "appearance/chat_menu"
    const val BACKGROUND = "background"
    const val ONBOARDING_MODELS = "onboarding_models"
    /** T219-2: Mount external folders settings + detail. */
    const val MOUNTED_FOLDERS = "mounted_folders"
    const val MOUNTED_FOLDERS_DETAIL = "mounted_folders_detail/{mountId}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/$mountId"
    /** T235: Shared folders (Shared / Skills / Memory) — fixed list. */
    const val SHARED_FOLDERS = "shared_folders"
    const val BACKUP = "backup"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    /**
     * [P0-0] `focusMessageId` defaults to null so every existing single-arg
     * caller is unchanged. Encoded the same way as [terminal]: URLEncoder
     * emits `+` for spaces but Nav only %-decodes, so `+` is rewritten to
     * `%20`. Message ids are UUID-ish today, but encoding costs nothing and
     * protects against ids that ever carry reserved characters.
     */
    fun chat(sessionId: String, focusMessageId: String? = null): String {
        val base = "chat/$sessionId"
        if (focusMessageId.isNullOrBlank()) return base
        val enc = java.net.URLEncoder.encode(focusMessageId, "UTF-8").replace("+", "%20")
        return "$base?focusMessageId=$enc"
    }
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun providerConnection(instanceId: String) = "provider_connection/$instanceId"
    fun modelGroupDetail(groupId: String) = "model_group/$groupId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    // [T-android-model-entry-route-slash-crash] entryId is a composite key
    // "<instanceId>/<modelId>" (compositeEntryKey) — it CONTAINS a '/'. Left
    // raw, that slash splits the route into an extra path segment, so the
    // built route no longer matches the registered MODEL_ENTRY_DETAIL pattern
    // (model_entry/{instanceId}/{entryId}) and navigate() throws
    // IllegalArgumentException "destination … cannot be found" — a guaranteed
    // crash on tapping any model whose id carries a '/'. URL-encode it so the
    // slash becomes %2F (one segment); the receiver decodes it back.
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${android.net.Uri.encode(instanceId)}/${android.net.Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}

/** Holder for file preview navigation state (not serializable via nav args). */
internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}

@Composable
fun AppNavigation(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: com.rikkaminis.app.data.repository.MCPRepository? = null,
    memoryRepository: MemoryRepository? = null,
    navController: NavHostController = rememberNavController(),
    initialDeepLink: DeepLinkAction? = null,
    /**
     * True when the NavController already has saved state to restore — i.e.
     * this composition follows an Activity recreation (config change, e.g.
     * language switch) or a process-death restore. In both cases the
     * cold-start launch-mode dispatch must be skipped (see the
     * LaunchedEffect(Unit) below); it must only run on a genuine cold start.
     */
    isActivityRecreation: Boolean = false,
) {
    val context = LocalContext.current

    // T219-5: use the application-scoped singleton from MinisApp so UI
    // add/remove shares state with PRootKernel and the lifecycle re-probe
    // path. Pre-T219-5 this `remember { MountedFoldersStore(...) }` created
    // a SECOND independent instance — UI list updated but PRoot never
    // saw the change because PRootKernel.mountedFoldersStore pointed at
    // the application-scoped singleton in MinisApp.
    val mountedFoldersStore = remember {
        (context.applicationContext as com.rikkaminis.app.MinisApp).mountedFoldersStore
    }

    // Handle initial deep link after composition
    LaunchedEffect(initialDeepLink) {
        when (initialDeepLink) {
            is DeepLinkAction.OpenTerminal -> {
                navController.safeNavigate(Routes.terminal(initialDeepLink.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                // T-double-chat-fix: on process-death recreation NavController
                // auto-restores the single chat entry AND MainActivity
                // synthesizes an OpenSession deep-link from saved state. A
                // bare navigate() pushed a SECOND chat entry, forcing the
                // user to press back twice. launchSingleTop collapses the
                // duplicate; popUpTo(start) + saveState=true +
                // restoreState=true preserves chat-screen state across the
                // hop. When the synthesized deep-link is a no-op (chat
                // already on top) launchSingleTop short-circuits.
                navController.safeNavigate(Routes.chat(initialDeepLink.sessionId)) {
                    popUpTo(navController.graph.startDestinationId) {
                        inclusive = true
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(
                    initialDeepLink.key,
                    initialDeepLink.value,
                    initialDeepLink.note,
                )
                navController.safeNavigate(Routes.ENV_VARS)
            }
            // T183: any settings screen reachable by route string. The
            // parser already resolved the path → route mapping so we
            // just navigate.
            is DeepLinkAction.OpenSettingsScreen -> {
                navController.safeNavigate(initialDeepLink.route)
            }
            is DeepLinkAction.OpenPermissionSettings -> {
                navController.safeNavigate(Routes.PERMISSIONS)
            }
            // OpenHtmlPreview is handled by setting startDestination
            // (see below) so the NavHost mounts directly into the right
            // chat — no safeNavigate dance, no sessions-list flash.
            is DeepLinkAction.OpenHtmlPreview -> {}
            // App-icon quick actions: both open a fresh draft chat.
            // Camera additionally seeds DeepLinkCoordinator.pendingChatAction
            // (done up-front in startDestination block below so the seed
            // lands before ChatScreen's first compose).
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewCameraChat -> {
                // Navigation handled by startDestination = chat/<__new__…>
                // when the launch intent carries one of these actions.
                // Nothing to do here — see startDestination block below.
            }
            else -> {}
        }
    }

    // Resolve launch session preference once into a deferred navigation target.
    // T314: this LaunchedEffect fires the same frame the NavHost mounts, so
    // the SESSION_LIST start-destination's NavBackStackEntry is still in
    // STARTED state when we'd otherwise call navigate(). safeNavigate() —
    // designed to defang the back-then-tap race — early-returns whenever
    // the current entry isn't RESUMED, which silently dropped every
    // launch-session navigation and left the user on the home screen
    // regardless of mode 1 / 2 / 0. Wait for the start destination to
    // settle into RESUMED before navigating; for mode 3 (Home) we don't
    // need to navigate at all so we can skip the wait entirely.
    LaunchedEffect(Unit) {
        // [fix/lang-switch-nav-jump] On anything that is NOT a genuine cold
        // start (Activity recreation from a config change — e.g. switching
        // language in Settings → Appearance — or a process-death/LMK restore),
        // the NavController's own state restoration already rebuilds the back
        // stack to whatever screen the user was on. Re-running the
        // cold-start launch-mode dispatch here would yank them back into a
        // chat (per the "Launch Session" preference) the moment they tap a
        // language, overwriting the settings screen they were standing on.
        // The launch-mode dispatcher must only run on a genuine cold start.
        if (isActivityRecreation) return@LaunchedEffect
        val hasDeepLink = initialDeepLink != null && initialDeepLink !is DeepLinkAction.Unknown
        if (hasDeepLink) return@LaunchedEffect
        val hasPendingShare =
            com.rikkaminis.app.share.ShareCoordinator.bufferVersion.value > 0
        val rawMode = getAppearancePrefs(context).getInt(KEY_LAUNCH_SESSION, 0)
        // Hang-detector circuit breaker: if the previous launches racked up
        // ≥3 main-thread hangs, force mode = 3 (home) so we don't reopen
        // the session/chat that was hanging. The user clears this either
        // by completing a chat session quietly (HangDetector.markHealthyTick
        // on ChatScreen) or by tapping the manual reset in Settings.
        //
        // Crash-frequency circuit breaker: same effect, different trigger.
        // When CrashFrequencyDetector tripped on the previous boot it set
        // a 1-hour force-home grace window — within that window every
        // launch lands on Home regardless of the user's Launch Session
        // preference, even after they dismissed the share dialog. Avoids
        // re-entering the session that may have been the crash trigger.
        //
        // [T-android-larky-longsession-followup] Beacon-driven restart-count
        // gate: the previous cycle ended in crash_or_stall AND the rolling
        // count of consecutive crashed launches exceeds the threshold
        // (default 3). Catches the "process repeatedly killed re-entering
        // the same monster session" pattern that the existing
        // HangDetector breaker misses when hangs short-circuit before the
        // SharedPreferences counter increments. Resets automatically the
        // moment the user has ANY non-crash_or_stall cycle (clean_exit /
        // silent_kill / first_launch) — see LaunchCycleBeacon.lastRestartCount.
        val mode = if (
            com.rikkaminis.app.diagnostics.HangDetector.shouldForceHomeOnLaunch(context) ||
            com.rikkaminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(context) ||
            com.rikkaminis.app.diagnostics.LaunchCycleBeacon.shouldForceHomeOnLaunch()
        ) 3 else rawMode
        val autoThresholdMs = 15L * 60 * 1000
        val target: String? = when {
            // T185: if a system share is buffered, the fresh chat below is
            // where it lands. Mode 3 ("Safe") now also opens a new draft chat
            // (the stock session list is gone), so the share consumer always
            // mounts.
            hasPendingShare && mode == 3 -> Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
            mode == 1 -> chatRepository.dao.listSessions().firstOrNull()?.let { Routes.chat(it.id) }
            mode == 2 -> Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
            // [remove-session-list] Mode 3 was "Home" (the deleted session
            // list). It now means "Safe Start": always open a fresh draft
            // chat so the force-home/HangDetector breakers still avoid
            // re-entering a session that may have crashed, but never land
            // on a page that no longer exists.
            mode == 3 -> Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
            else -> {
                val latest = chatRepository.dao.listSessions().firstOrNull()
                val fresh = latest != null && System.currentTimeMillis() - latest.updatedAt < autoThresholdMs
                if (fresh) Routes.chat(latest!!.id) else Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
            }
        }
        if (target != null) {
            // T314: navigate directly without the safeNavigate guard.
            // safeNavigate exists to defang back-then-tap-settings races
            // where a popped destination is mid-tear-down — it requires
            // RESUMED to be safe. But this LaunchedEffect fires on the
            // first composition pass, when the start destination is
            // STARTED but not yet RESUMED. There's no race here: we're
            // the deterministic startup dispatcher, the start destination
            // hasn't even rendered, and we want to navigate to it BEFORE
            // it shows. Calling navigate() unconditionally produces the
            // intended cold-start dispatch (mode 1 → last session, mode 2
            // → new chat, mode 0 → auto). The start destination is always
            // a chat now (no SESSION_LIST), so we pop to it inclusively to
            // replace the placeholder draft with the real target.
            navController.navigate(target) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
        }
    }

    // [remove-session-list] The share fallback that used to watch for the
    // user sitting on the (deleted) session-list is gone: startDestination
    // is always a chat now, so a buffered share is always drained by a
    // ChatScreen's LaunchedEffect(shareBufferVersion). Consuming is
    // idempotent (one-shot consumeBuffer), so no double-inject.

    // Pinned-shortcut cold start: when launched via
     // `minis://session/<id>/<resource-path>`, set the pending HTML
     // preview synchronously and start NavHost directly at the matching
     // chat so ChatScreen's LaunchedEffect consumes the pending state on
     // first composition — no sessions-list flash, no launch-session
     // preference detour.
    val htmlShortcut = initialDeepLink as? DeepLinkAction.OpenHtmlPreview
    // App-icon quick action cold start: mount NavHost directly at a fresh
    // draft chat, seeding the pending action so ChatScreen consumes it on
    // its first LaunchedEffect tick. Mirrors the htmlShortcut path —
    // avoids a sessions-list flash and a duplicate back-stack entry.
    val quickActionStart: String? = when (initialDeepLink) {
        is DeepLinkAction.NewCameraChat -> {
            DeepLinkCoordinator.setPendingChatAction(
                DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
            )
            Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
        }
        is DeepLinkAction.NewChat -> Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
        else -> null
    }
    val startDestination = when {
        htmlShortcut != null -> {
            // Seed coordinator before NavHost composition so ChatScreen sees
            // the pending state on its very first LaunchedEffect tick.
            DeepLinkCoordinator.setPendingHtmlPreview(
                htmlShortcut.sessionId,
                htmlShortcut.resourcePath,
                htmlShortcut.title,
            )
            Routes.chat(htmlShortcut.sessionId)
        }
        quickActionStart != null -> quickActionStart
        // [remove-session-list] No stock session list: the conversation
        // drawer is the only session switcher, so cold-start heads straight
        // into a fresh draft chat (mirrors the New Chat launch mode).
        else -> Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // T153: paint the in-app theme color underneath every transition
        // frame. Without this the NavHost's transition surface is
        // transparent and the window background bleeds through during
        // enter/exit animations — on dark mode the base Light window
        // background flashes white between screens. Tying the host to
        // the active Material colorScheme also keeps the first frame
        // correct on cold start.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // T342: Material 3 motion — shared-axis X transition.
        // Spec (m3.material.io/styles/motion/transitions):
        //   - enter uses EmphasizedDecelerate (cubic-bezier 0.05, 0.7, 0.1, 1.0)
        //     so the new destination eases in confidently
        //   - exit uses EmphasizedAccelerate (cubic-bezier 0.3, 0.0, 0.8, 0.15)
        //     so the leaving destination clears out fast
        //   - both legs together feel like a single 300ms motion (200ms
        //     exit overlapping 300ms enter), short enough that a rapid second
        //     tap still lands on the next destination once safeNavigate's
        //     RESUMED guard releases
        //   - the small slide distance (~SlideDirection default ≈ container
        //     width ÷ N — Compose's slideIntoContainer already picks a
        //     subtle distance) plus fade reads as a single coordinated
        //     motion rather than a hard cut, matching Settings/Files in
        //     Material You system apps.
        // RESUMED guard via safeNavigate + the colorScheme.background
        // modifier above (T333) still defend against rapid-tap white
        // flashes; the slightly longer enter spec (300ms vs 220ms) is
        // covered by the same guard.
        enterTransition = {
            // Cold-start hop → instant, no directional motion (see
            // isColdStartPlaceholder). Everything else → M3 shared-axis X.
            if (initialState.isColdStartPlaceholder) {
                EnterTransition.None
            } else {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(300, easing = EmphasizedDecelerate),
                ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
            }
        },
        exitTransition = {
            if (initialState.isColdStartPlaceholder) {
                ExitTransition.None
            } else {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(200, easing = EmphasizedAccelerate),
                ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
            }
        },
        popEnterTransition = {
            if (initialState.isColdStartPlaceholder) {
                EnterTransition.None
            } else {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(300, easing = EmphasizedDecelerate),
                ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
            }
        },
        popExitTransition = {
            if (initialState.isColdStartPlaceholder) {
                ExitTransition.None
            } else {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(200, easing = EmphasizedAccelerate),
                ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
            }
        },
    ) {
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                // [P0-0] Optional focus target. nullable + defaultValue=null is
                // what makes the 20+ existing `Routes.chat(id)` navigations
                // resolve against this route unchanged.
                navArgument("focusMessageId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val focusMessageId = backStackEntry.arguments
                ?.getString("focusMessageId")
                ?.takeIf { it.isNotBlank() }
            ChatScreen(
                sessionId = sessionId,
                focusMessageId = focusMessageId,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
                onBack = { navController.safePopBackStack() },
                // [T-new-chat-menu-entry] Chat-menu "New Chat": same draft-id
                // funnel as the session list / NewChat deep link — a fresh
                // "__new__" route whose DB record is only created on first
                // send, so abandoning it leaves no empty session. popUpTo
                // removes the current chat from the stack (back → the stack
                // root) and a double-fire just replaces one unpersisted
                // draft with another instead of stacking two chats.
                onNewChat = {
                    navController.safeNavigate(Routes.chat(com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context))) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenTerminal = {
                    navController.safeNavigate(Routes.terminal(sessionId = sessionId))
                },
                onOpenTerminalWithCommand = { command ->
                    navController.safeNavigate(
                        Routes.terminal(initCommand = command, sessionId = sessionId),
                    )
                },
                onMoveToSession = { targetId ->
                    navController.safeNavigate(Routes.chat(targetId)) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                },
                onBrowseChatFiles = {
                    navController.safeNavigate(Routes.chatFiles(sessionId))
                },
                onPreviewAttachment = { item ->
                    FilePreviewHolder.currentItem = item
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                // Chat-history drawer: open another conversation (same funnel
                // as the session click) or jump to Settings.
                onOpenSession = { targetId ->
                    navController.safeNavigate(Routes.chat(targetId)) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenSettings = { navController.safeNavigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                // Settings is a top-level page: no onBack passed — system back
                // gesture / bottom nav handle returning to the chat.
                onProvidersClick = { navController.safeNavigate(Routes.PROVIDER_LIST) },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                onRootfsClick = { navController.safeNavigate(Routes.STORAGE) },
                onEnvVarsClick = { navController.safeNavigate(Routes.ENV_VARS) },
                onRuntimeLimitsClick = { navController.safeNavigate(Routes.RUNTIME_LIMITS) },
                onSkillsClick = { navController.safeNavigate(Routes.SKILLS) },
                onTerminalClick = { navController.safeNavigate(Routes.terminal()) },
                onMemoryClick = { navController.safeNavigate(Routes.MEMORY) },
                onMcpClick = { navController.safeNavigate(Routes.MCP) },
                onSoulClick = { navController.safeNavigate(Routes.SOUL) },
                onPermissionsClick = { navController.safeNavigate(Routes.PERMISSIONS) },
                onUsageClick = { navController.safeNavigate(Routes.USAGE_STATS) },
                onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
                onChatTuningClick = { navController.safeNavigate(Routes.CHAT_TUNING) },
                onBackgroundClick = { navController.safeNavigate(Routes.BACKGROUND) },
                onLogsClick = { navController.safeNavigate(Routes.LOGS) },
                onMountedFoldersClick = { navController.safeNavigate(Routes.MOUNTED_FOLDERS) },
                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
                onBackupClick = { navController.safeNavigate(Routes.BACKUP) },
            )
        }

        composable(Routes.SHARED_FOLDERS) {
            SharedFoldersScreen(
                onBack = { navController.safePopBackStack() },
                onFolderClick = { folderId ->
                    navController.safeNavigate(Routes.sharedFoldersDetail(folderId))
                },
            )
        }

        composable(Routes.BACKUP) {
            com.rikkaminis.app.ui.settings.BackupSettingsScreen(
                providerRepository = providerRepository,
                envVarRepository = envVarRepository,
                skillRepository = skillRepository,
                memoryRepository = memoryRepository,
                mcpRepository = mcpRepository,
                chatRepository = chatRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.SHARED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SharedFolderDetailScreen(
                folderId = folderId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val rootfs = RootfsManager.getInstance(ctx.applicationContext)
                    val hostPath = java.io.File(rootfs.rootfsDir, "var/minis/$folderId")
                    val label = when (folderId) {
                        "shared" -> ctx.getString(com.rikkaminis.app.R.string.shared_folder_name_shared)
                        "skills" -> ctx.getString(com.rikkaminis.app.R.string.shared_folder_name_skills)
                        "memory" -> ctx.getString(com.rikkaminis.app.R.string.shared_folder_name_memory)
                        else -> folderId
                    }
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = hostPath,
                        rootLabel = label,
                        // Route reads through PRoot bind mounts so the host
                        // dirs that back /var/minis/{shared,skills,memory}
                        // resolve, matching how chat-files browse works.
                        linuxRootPath = "/var/minis/$folderId",
                        appContext = ctx.applicationContext,
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.MOUNTED_FOLDERS) {
            MountedFoldersScreen(
                store = mountedFoldersStore,
                onBack = { navController.safePopBackStack() },
                onMountClick = { mountId ->
                    navController.safeNavigate(Routes.mountedFoldersDetail(mountId))
                },
            )
        }

        composable(
            route = Routes.MOUNTED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("mountId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val mountId = backStackEntry.arguments?.getString("mountId") ?: return@composable
            val context = androidx.compose.ui.platform.LocalContext.current
            MountDetailScreen(
                store = mountedFoldersStore,
                mountId = mountId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val entry = mountedFoldersStore.entries.value.firstOrNull { it.id == mountId }
                    val hostPath = entry?.resolvedHostPath
                    if (hostPath != null) {
                        FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                            rootPath = java.io.File(hostPath),
                            rootLabel = entry.name,
                        )
                        navController.safeNavigate(Routes.FILE_BROWSER)
                    } else {
                        // resolvedHostPath null = SAF tree from a non-externalstorage
                        // provider (cloud / Drive). Picker normally rejects these at
                        // add time, so this is a defensive fallback.
                        android.widget.Toast.makeText(
                            context,
                            "Mount path unavailable",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        composable(Routes.PROVIDER_LIST) {
            ProviderListScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
                onProviderClick = { instanceId ->
                    navController.safeNavigate(Routes.providerDetail(instanceId))
                },
            )
        }

        composable(Routes.ADD_PROVIDER) {
            AddProviderScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onSaved = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.PROVIDER_DETAIL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ProviderDetailScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onModelEntryClick = { entryId ->
                    navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))
                },
                onAddCustomModel = {
                    navController.safeNavigate(Routes.addCustomModel(instanceId))
                },
                onConnectionClick = {
                    navController.safeNavigate(Routes.providerConnection(instanceId))
                },
            )
        }

        composable(
            route = Routes.PROVIDER_CONNECTION,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val cInstanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ProviderConnectionScreen(
                instanceId = cInstanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.MODEL_GROUPS) {
            ModelGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onGroupClick = { groupId ->
                    navController.safeNavigate(Routes.modelGroupDetail(groupId))
                },
                onAgentLoopClick = {
                    navController.safeNavigate(Routes.AGENT_LOOP_MODELS)
                },
            )
        }

        composable(
            route = Routes.MODEL_GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            ModelGroupDetailScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddModels = {
                    navController.safeNavigate(Routes.addModelsToGroup(groupId))
                },
            )
        }

        composable(
            route = Routes.ADD_MODELS_TO_GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            AddModelsToGroupScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T185: full-screen picker for adding entries to the agent-loop
        // usable set (replaces the T182 ModalBottomSheet so the visual
        // matches AddModelsToGroupScreen — same shared
        // modelEntryPickerItems composable in ui/components/).
        composable(Routes.ADD_MODELS_TO_AGENT_LOOP) {
            AddAgentLoopModelsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T185: companion picker for adding model groups to the agent-loop
        // set. Simpler layout (no per-provider sectioning) but same
        // selection/confirm semantics as the entries picker.
        composable(Routes.ADD_GROUPS_TO_AGENT_LOOP) {
            AddAgentLoopGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // [model-groups-simplify] Agent Loop Models is a standalone screen
        // again (reached via the entry row on ModelGroupsScreen). Routes
        // AGENT_LOOP_MODELS now renders it; the old inline AgentLoopModelsSection
        // inside ModelGroupsScreen was extracted here.
        composable(Routes.AGENT_LOOP_MODELS) {
            AgentLoopModelsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddModelsTap = {
                    navController.safeNavigate(Routes.ADD_MODELS_TO_AGENT_LOOP)
                },
                onAddGroupsTap = {
                    navController.safeNavigate(Routes.ADD_GROUPS_TO_AGENT_LOOP)
                },
            )
        }

        composable(
            route = Routes.MODEL_ENTRY_DETAIL,
            arguments = listOf(
                navArgument("instanceId") { type = NavType.StringType },
                navArgument("entryId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            // [T-android-model-entry-route-slash-crash] Decode back the
            // %2F-encoded composite ids so ModelEntryDetailScreen can match
            // entry.id == "<instanceId>/<modelId>" (which carries a literal '/')
            // against the repo. Mirrors the encode in Routes.modelEntryDetail.
            val instanceId = backStackEntry.arguments?.getString("instanceId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            val entryId = backStackEntry.arguments?.getString("entryId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            ModelEntryDetailScreen(
                instanceId = instanceId,
                entryId = entryId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.ADD_CUSTOM_MODEL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            AddCustomModelScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.STORAGE) {
            StorageManagementScreen(
                chatRepository = chatRepository,
                onBack = { navController.safePopBackStack() },
                onRootfsClick = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
                onSessionClick = { sessionId ->
                    navController.safeNavigate(Routes.sessionStorageDetail(sessionId))
                },
            )
        }

        composable(
            route = Routes.SESSION_STORAGE_DETAIL,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            SessionStorageDetailScreen(
                sessionId = sessionId,
                chatDao = chatRepository.dao,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = { rootPath ->
                    // [T-android-copy-abs-path-fullpath] This browser is rooted at
                    // the per-session host dir (filesDir/minis-sessions/<sid>),
                    // whose immediate children (workspace/ attachments/ offloads/
                    // browser/) are exactly the PRoot /var/minis/* subdirs. The
                    // host listing already resolves correctly so we keep rootPath
                    // host-based (no linuxRootPath re-routing — that would redirect
                    // /var/minis to the global/empty placeholder dir). We only pass
                    // displayLinuxPrefix = "/var/minis" so "Copy Absolute Path"
                    // emits the agent-visible /var/minis/workspace/foo.py instead of
                    // the opaque /data/user/0/.../minis-sessions/<sid>/... host path.
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = java.io.File(rootPath),
                        rootLabel = "Session Files",
                        displayLinuxPrefix = "/var/minis",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.ROOTFS_MANAGEMENT) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RootfsManagementScreen(
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val rootfs = RootfsManager.getInstance(context.applicationContext)
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = rootfs.rootfsDir,
                        rootLabel = "/",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.FILE_BROWSER) {
            val vm = FilePreviewHolder.fileBrowserViewModel ?: return@composable
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        // Browse Chat Files (iOS parity: open FileBrowser rooted at the full
        // Linux root, focused on /var/minis. Matches AIChatView.swift L490:
        //   FileBrowserView(rootPath: dataPath, initialPath: dataPath/var/minis,
        //                   rootLabel: "/")
        // so the user can navigate up out of /var/minis into the broader rootfs.
        composable(
            route = Routes.CHAT_FILES,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val rootfs = RootfsManager.getInstance(context.applicationContext)
            val varMinis = java.io.File(rootfs.rootfsDir, "var/minis")
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val vm = remember(rootfs.rootfsDir.absolutePath, varMinis.absolutePath, sessionId) {
                FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    initialPath = varMinis.takeIf { it.exists() },
                    rootLabel = "/",
                    // T121: route directory listings through PRootKernel bind
                    // mounts so /var/minis/{skills,memory,shared} resolve to
                    // their backing host dirs (filesDir/minis-global/<subdir>).
                    // Without this the browser walks the rootfs tarball
                    // directly and shows the empty placeholder dirs that ship
                    // inside Alpine's var/minis/ — every subdir reads as
                    // "Empty folder" even though the agent has files there.
                    linuxRootPath = "/",
                    // T147: scope per-session subdirs (attachments / workspace
                    // / offloads / browser) to THIS chat's host dir even when
                    // another session was the last to boot a PRoot — that
                    // global bindMounts state is last-writer-wins and would
                    // otherwise hide the agent's generated files for the
                    // session the user is looking at.
                    sessionId = sessionId,
                    appContext = context.applicationContext,
                )
            }
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        // Rendered as a Dialog destination (not a `composable`) so the
        // underlying screen — typically ChatScreen — stays in the composition
        // while preview is open. With `composable()`, NavHost unmounts the
        // previous entry, which detaches the chat LazyColumn from layout;
        // when the user pops back, LazyListState re-anchors to (0, 0) and
        // the user loses their scroll position (plus a white-flash on the
        // first frame before the list remeasures). `dialog()` keeps the
        // back entry's composition alive — listState retains both
        // firstVisibleItemIndex/Offset and its layoutInfo cache, so the
        // chat paints its previous viewport on the first frame after pop.
        // `usePlatformDefaultWidth=false` lets the dialog fill the screen
        // edge-to-edge, matching the prior full-screen composable feel;
        // `decorFitsSystemWindows=false` lets FilePreviewScreen handle its
        // own insets exactly like before.
        dialog(
            route = Routes.FILE_PREVIEW,
            dialogProperties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val item = FilePreviewHolder.currentItem ?: return@dialog
            FilePreviewScreen(
                item = item,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ENV_VARS) {
            if (envVarRepository != null) {
                EnvironmentVariablesScreen(
                    envVarRepository = envVarRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }
        composable(Routes.RUNTIME_LIMITS) {
            com.rikkaminis.app.ui.settings.RuntimeLimitsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }
        composable(Routes.CHAT_TUNING) {
            com.rikkaminis.app.ui.settings.ChatTuningScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SKILLS) {
            if (skillRepository != null) {
                SkillsManagementScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onSkillClick = { skillId -> navController.safeNavigate(Routes.skillDetail(skillId)) },
                    onMinisSkillsClick = { navController.safeNavigate(Routes.MINIS_SKILLS_BROWSER) },
                )
            }
        }

        composable(
            route = Routes.SKILL_DETAIL,
            arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            if (skillRepository != null) {
                SkillDetailScreen(
                    skillId = skillId,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { id, relativePath ->
                        navController.safeNavigate(Routes.skillFile(id, relativePath))
                    },
                )
            }
        }

        composable(
            route = Routes.SKILL_FILE,
            arguments = listOf(
                navArgument("skillId") { type = NavType.StringType },
                navArgument("relativePath") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            val rawPath = backStackEntry.arguments?.getString("relativePath") ?: "SKILL.md"
            val relativePath = java.net.URLDecoder.decode(rawPath, "UTF-8")
            if (skillRepository != null) {
                SkillFileViewerScreen(
                    skillId = skillId,
                    relativePath = relativePath,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.MINIS_SKILLS_BROWSER) {
            if (skillRepository != null) {
                MinisSkillsBrowserScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(
            Routes.TERMINAL,
            arguments = listOf(
                androidx.navigation.navArgument("initCommand") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                androidx.navigation.navArgument("sessionId") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val initCommand = backStackEntry.arguments?.getString("initCommand")
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val session = remember { TerminalSession(context.applicationContext) }
            TerminalScreen(
                terminalSession = session,
                onBack = { navController.safePopBackStack() },
                initCommand = initCommand,
                sessionId = sessionId,
            )
        }

        composable(Routes.MEMORY) {
            if (memoryRepository != null) {
                MemoryManagementScreen(
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { fileName, isGlobal ->
                        navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                    },
                )
            }
        }

        // [T-mcp-integration-android] MCP Integrations management screen.
        composable(Routes.MCP) {
            if (mcpRepository != null) {
                com.rikkaminis.app.ui.settings.MCPIntegrationsScreen(
                    mcpRepository = mcpRepository,
                    onBack = { navController.safePopBackStack() },
                    envVarRepository = envVarRepository,
                )
            }
        }

        // [T-soul-md] SOUL.md editor.
        composable(Routes.SOUL) {
            com.rikkaminis.app.ui.settings.SoulSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.MEMORY_FILE_EDIT,
            arguments = listOf(
                navArgument("fileName") { type = NavType.StringType },
                navArgument("isGlobal") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            val isGlobal = backStackEntry.arguments?.getBoolean("isGlobal") ?: false
            if (memoryRepository != null) {
                MemoryFileEditScreen(
                    fileName = fileName,
                    isGlobal = isGlobal,
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.PERMISSIONS) {
            OffloadPermissionScreen(
                onBack = { navController.safePopBackStack() },
                // [T-android-privileged-backend] Single Shizuku-protocol screen
                // handles both Shizuku and AXManager managers; the old
                // multi-backend screen was retired in favour of a single
                // surface (the two managers share one binder slot, so a
                // multi-backend abstraction was misleading).
                onOpenPrivilegedBackend = { navController.safeNavigate(Routes.SHIZUKU) },
            )
        }

        composable(Routes.SHIZUKU) {
            ShizukuPermissionScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SYSTEM_PERMISSIONS) {
            SystemPermissionsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.USAGE_STATS) {
            UsageStatsScreen(
                chatDao = chatRepository.dao,
                providerConfig = providerRepository.config.value,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(
                onBack = { navController.safePopBackStack() },
                onChatMenuClick = { navController.safeNavigate(Routes.CHAT_MENU) },
            )
        }

        composable(Routes.CHAT_MENU) {
            ChatMenuSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.BACKGROUND) {
            BackgroundSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.LOGS) {
            LogManagementScreen(
                onBack = { navController.safePopBackStack() },
                onLogFileClick = { fileName ->
                    navController.safeNavigate(Routes.logDetail(fileName))
                },
            )
        }

        composable(
            route = Routes.LOG_DETAIL,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            LogDetailScreen(
                fileName = fileName,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ONBOARDING_MODELS) {
            OnboardingModelSelectionScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

    }
}
