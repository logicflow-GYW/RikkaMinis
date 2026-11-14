package com.rikkaminis.app.deeplink

import android.net.Uri
import com.rikkaminis.app.ui.navigation.Routes

/**
 * Parses minis:// deep link URIs into navigation actions.
 *
 * Supported routes (matching iOS):
 *   minis://share                              → open share flow
 *   minis://views/alarm                        → open alarm list
 *   minis://open_terminal?init_command=...      → open terminal with command
 *   minis://session/<id>                        → open specific session
 *   minis://settings                            → Settings home
 *   minis://settings/providers                  → Provider list
 *   minis://settings/providers/<instanceId>     → Provider detail
 *   minis://settings/model-groups               → Model Groups (incl. Agent Loop section)
 *   minis://settings/model-groups/<groupId>     → Model Group detail
 *   minis://settings/usage                      → Token usage
 *   minis://settings/skills                     → Skills management
 *   minis://settings/memory                     → Memory management
 *   minis://settings/storage                    → Storage management
 *   minis://settings/mount-external             → Mount External Folders list
 *   minis://settings/mounts                     → alias for mount-external
 *   minis://settings/shared-folders             → Shared Folders list (T235)
 *   minis://settings/shared_folders             → alias for shared-folders
 *   minis://settings/logs                       → Log management
 *   minis://settings/appearance                 → Appearance
 *   minis://settings/background                 → Background settings
 *   minis://settings/permissions                → Permissions
 *   minis://settings/mcp, mcp-servers           → MCP Integrations (T-mcp-oauth-
 *                                                 deeplink: the authorize round-trip
 *                                                 emitted by minis-mcp-cli lands here;
 *                                                 the server-name segment is ignored —
 *                                                 the MCP screen is a list destination)
 *   minis://settings/soul                       → SOUL.md editor
 *   minis://settings/backup                     → Backup & restore
 *   minis://settings/scheduled-tasks            → Scheduled tasks
 *   minis://settings/runtime-limits             → Runtime Limits page
 *   minis://settings/chat-tuning                → Chat Tuning page
 *   minis://settings/environments[?create_key=...&create_value=...&create_note=...]
 *                                               → Environment variables
 *   minis://settings/rootfs                     → Rootfs management (mirror config lives here)
 *   minis://settings/mirrors                    → alias for rootfs (mirrors live inside Rootfs UI)
 *
 * (`minis://settings/about` was documented here once but no About
 * destination ever existed in the nav table — such paths land on
 * Settings home via the fallback below.)
 *
 * Unknown settings paths fall back to Settings home rather than
 * Unknown — matches iOS's "best-effort land somewhere reasonable"
 * behavior so an LLM-generated link can never strand the user.
 *
 * Resource-class URIs (`minis://workspace/...`, `minis://skills/...`,
 * etc.) are intentionally NOT handled here — they resolve to on-disk
 * files and go through `ChatLinkResolver` at the chat-view layer. This
 * parser only handles *navigation* targets that change the app's top-
 * level screen.
 */
sealed class DeepLinkAction {
    data object OpenShare : DeepLinkAction()
    data object OpenAlarmList : DeepLinkAction()
    data class OpenTerminal(val initCommand: String?) : DeepLinkAction()
    data class CreateEnvironmentVariable(val key: String, val value: String, val note: String) : DeepLinkAction()
    data object OpenPermissionSettings : DeepLinkAction()
    data class OpenSession(val sessionId: String) : DeepLinkAction()

    /**
     * App-icon long-press quick actions (mirrors iOS QuickActionRouter).
     *
     *  - [NewChat] — plain new draft chat
     *  - [NewCameraChat] — new chat + auto-launch camera attachment on first compose
     *
     * (NewVoiceChat was removed together with the in-composer mic button —
     * it had no landing UI left to trigger.)
     *
     * Encoded as `minis://action/<name>` — plain Intent.data, no custom
     * extras, matching the existing minis:// deep-link conventions.
     *
     * [chore/remove-app-shortcuts-1115] The Android app-icon long-press
     * surface (`res/xml/shortcuts.xml`) that produced these URIs is gone: the
     * launcher could never launch them (the static-shortcut `<intent>`
     * `targetPackage` stayed an unresolved resource reference, so the intent
     * carried `cmp=@2131559347/...` and resolved to nothing), and both
     * actions duplicated entry points that already exist inside the app
     * (cold start lands on a draft; the composer has the camera). The URI
     * handling is kept on purpose so externally authored deep links keep
     * working; nothing in-app emits them any more.
     */
    data object NewChat : DeepLinkAction()
    data object NewCameraChat : DeepLinkAction()

    /**
     * T183: any settings screen reachable by route string. Extends the
     * sealed family so consumers don't have to learn 14 new case types
     * — they `safeNavigate(action.route)` and the route table in
     * [com.rikkaminis.app.ui.navigation.Routes] does the rest. Routes
     * with arguments (provider detail, model-group detail) carry the
     * already-formatted route ("provider/<id>", "model_group/<id>").
     */
    data class OpenSettingsScreen(val route: String) : DeepLinkAction()

    /**
     * Launch the in-chat HTML preview, fullscreen, for a pinned home-screen
     * shortcut.
     *
     * Encoded as `minis://session/<sessionId>/<resource-path>` —
     * [sessionId] selects which chat to land in; [resourcePath] is the
     * resource path under `/var/minis/` (e.g. `/browser/snake.html`).
     * [title] is the cached page title at pin time, used as the fallback
     * while WebView re-reports its own.
     */
    data class OpenHtmlPreview(
        val sessionId: String,
        val resourcePath: String,
        val title: String,
    ) : DeepLinkAction()

    data object Unknown : DeepLinkAction()
}

/**
 * [da1020-F] Resolved target of a `minis://settings/<path>` link — the pure
 * decision shape behind [DeepLinkHandler.parseSettingsPath].
 */
internal sealed class SettingsDeepLink {
    /** A ready-to-navigate route from [Routes] (arguments already applied). */
    data class Route(val route: String) : SettingsDeepLink()

    /** `environments?create_key=…` — prefilled env-var create form. */
    data class EnvVarCreate(val key: String, val value: String, val note: String) : SettingsDeepLink()

    /** `permissions` keeps its dedicated dispatch branch. */
    data object PermissionSettings : SettingsDeepLink()
}

/**
 * [da1020-F] Pure routing table for `minis://settings/<path>`.
 *
 * Deliberately free of `android.net.Uri` (a stub on the JVM test VM) so the
 * whole table — including the percent-encoding contract of the [Routes]
 * builders — is unit-testable. Both inputs are **already decoded**, which is
 * what Android's `Uri.getPath()` / `Uri.getQueryParameter()` hand over; the
 * argument therefore reaches the `Routes.*` builders raw, and those builders
 * are the single place that re-encodes it (no double-encoding).
 *
 * Unknown paths land on [Routes.SETTINGS] rather than failing, so an
 * LLM-generated link can never strand the user (iOS parity) — this fallback is
 * deliberate and must not be turned into an error.
 */
internal fun resolveSettingsDeepLink(path: String, query: Map<String, String>): SettingsDeepLink {
    val segments = path.trimStart('/').split('/').filter { it.isNotEmpty() }
    val head = segments.firstOrNull() ?: return SettingsDeepLink.Route(Routes.SETTINGS)
    val arg = segments.getOrNull(1)?.takeIf { it.isNotBlank() }

    return when (head) {
        "providers" ->
            if (arg != null) SettingsDeepLink.Route(Routes.providerDetail(arg))
            else SettingsDeepLink.Route(Routes.PROVIDER_LIST)
        "model-groups", "model_groups" ->
            if (arg != null) SettingsDeepLink.Route(Routes.modelGroupDetail(arg))
            else SettingsDeepLink.Route(Routes.MODEL_GROUPS)
        "usage", "usage-stats", "usage_stats" ->
            SettingsDeepLink.Route(Routes.USAGE_STATS)
        "skills" -> SettingsDeepLink.Route(Routes.SKILLS)
        "memory" -> SettingsDeepLink.Route(Routes.MEMORY)
        "storage" -> SettingsDeepLink.Route(Routes.STORAGE)
        "mount-external", "mount_external", "mounts", "mounted-folders", "mounted_folders" ->
            SettingsDeepLink.Route(Routes.MOUNTED_FOLDERS)
        "shared-folders", "shared_folders" ->
            SettingsDeepLink.Route(Routes.SHARED_FOLDERS)
        // [da1020-F] `minis://settings/mcp-servers/<name>` is emitted by the
        // MCP OAuth flow itself (minis-mcp-cli `transport/http.py`
        // `_authorize_deeplink`: `"[Authorize](minis://settings/mcp-servers/%s)"`
        // with the server name percent-encoded). Until this branch existed the
        // link fell through to Settings home, so the authorize round-trip never
        // reached the MCP screen. Routes.MCP is a single list destination with
        // no argument slot, so the server-name segment is accepted and
        // **ignored** (appending it would build a route no destination matches
        // = the crash class fixed below). Landing on the list is recorded as a
        // residual: deep-linking to one server's edit sheet needs a pending
        // hand-off into MCPIntegrationsScreen (outside this change's blast
        // radius).
        "mcp", "mcp-servers", "mcp_servers" -> SettingsDeepLink.Route(Routes.MCP)
        "soul" -> SettingsDeepLink.Route(Routes.SOUL)
        "backup" -> SettingsDeepLink.Route(Routes.BACKUP)
        "scheduled-tasks", "scheduled_tasks" -> SettingsDeepLink.Route(Routes.SCHEDULED_TASKS)
        "runtime-limits", "runtime_limits" -> SettingsDeepLink.Route(Routes.RUNTIME_LIMITS)
        "chat-tuning", "chat_tuning" -> SettingsDeepLink.Route(Routes.CHAT_TUNING)
        "logs" -> SettingsDeepLink.Route(Routes.LOGS)
        "appearance" -> SettingsDeepLink.Route(Routes.APPEARANCE)
        "background" -> SettingsDeepLink.Route(Routes.BACKGROUND)
        "permissions" -> SettingsDeepLink.PermissionSettings
        // mirrors live as a section inside Rootfs management — no
        // standalone destination, so route both /mirrors and /rootfs
        // there. The user lands on the same screen; mirror config is
        // visible as the "Mirrors" section inside.
        "mirrors", "rootfs", "rootfs-management", "rootfs_management" ->
            SettingsDeepLink.Route(Routes.ROOTFS_MANAGEMENT)
        "environments" -> {
            // iOS parity (AIChatView.swift L1407-1411): only `create_key`
            // is required. Missing `create_value`/`create_note` default
            // to empty string so a link like
            //   minis://settings/environments?create_key=GH_TOKEN&create_value=
            // (where `create_value=` is present-but-blank) still opens
            // the prefilled form. Without create_key, plain navigation
            // to the Env Vars list.
            val key = query["create_key"]
            if (!key.isNullOrEmpty()) {
                SettingsDeepLink.EnvVarCreate(
                    key = key,
                    value = query["create_value"] ?: "",
                    note = query["create_note"] ?: "",
                )
            } else {
                SettingsDeepLink.Route(Routes.ENV_VARS)
            }
        }
        // Unknown path — land on Settings home rather than failing,
        // so the user can find what they wanted by browsing.
        else -> SettingsDeepLink.Route(Routes.SETTINGS)
    }
}

object DeepLinkHandler {
    fun parse(uri: Uri?): DeepLinkAction {
        if (uri == null || uri.scheme != "minis") return DeepLinkAction.Unknown
        val host = uri.host ?: return DeepLinkAction.Unknown
        val path = uri.path.orEmpty()

        return when (host) {
            "share" -> DeepLinkAction.OpenShare
            "views" -> when (path) {
                "/alarm" -> DeepLinkAction.OpenAlarmList
                else -> DeepLinkAction.Unknown
            }
            "open_terminal" -> DeepLinkAction.OpenTerminal(
                initCommand = sanitizeInitCommand(uri.getQueryParameter("init_command"))
            )
            // Quick-actions surface. The app-icon long-press shortcuts that
            // used to emit these were removed (see [NewChat]); the URIs stay
            // parseable for externally authored links. Path drives which
            // pending action ChatScreen consumes on first compose.
            "action" -> when (path.removePrefix("/")) {
                "new_chat" -> DeepLinkAction.NewChat
                // "voice_chat" removed with the mic button; a stale pinned
                // shortcut now resolves to Unknown (no-op) rather than a
                // dangling voice action.
                "camera_chat" -> DeepLinkAction.NewCameraChat
                else -> DeepLinkAction.Unknown
            }
            "settings" -> parseSettingsPath(uri)
            "session" -> {
                // minis://session/<sessionId>                → OpenSession
                // minis://session/<sessionId>/<resource-path> → OpenHtmlPreview
                val segments = path.removePrefix("/")
                    .split('/')
                    .filter { it.isNotEmpty() }
                val sid = segments.firstOrNull()
                when {
                    sid.isNullOrBlank() -> DeepLinkAction.Unknown
                    segments.size == 1 -> DeepLinkAction.OpenSession(sid)
                    // Reject any resource path that carries a traversal / path
                    // separator segment — refuse the whole link instead of
                    // degrading silently. Note Uri.getPath() already decodes
                    // percent-encoding, so an encoded `%2e%2e` arrives here as
                    // a literal `..` segment and is caught.
                    segments.drop(1).any { DeepLinkPathGuard.isUnsafeSegment(it) } ->
                        DeepLinkAction.Unknown
                    else -> {
                        val resourcePath = "/" + segments.drop(1).joinToString("/")
                        val title = uri.getQueryParameter("title").orEmpty()
                        DeepLinkAction.OpenHtmlPreview(sid, resourcePath, title)
                    }
                }
            }
            else -> DeepLinkAction.Unknown
        }
    }

    /**
     * T183: walk a `minis://settings/<path>` URI to the right NavHost
     * route. Two cases stay distinct:
     *
     *  - `environments?create_key=…` — keeps the dedicated
     *    [DeepLinkAction.CreateEnvironmentVariable] case because the
     *    target screen needs the parameters wired through
     *    `DeepLinkCoordinator.setPendingEnvVarCreate`.
     *  - `permissions` — keeps [DeepLinkAction.OpenPermissionSettings]
     *    so existing dispatch logic stays unchanged.
     *
     * Everything else funnels through [DeepLinkAction.OpenSettingsScreen]
     * carrying a route string from [Routes]. Unknown paths land on
     * Settings home rather than [DeepLinkAction.Unknown] so an LLM-
     * generated link can never strand the user; iOS does the same.
     *
     * [da1020-F] The routing table itself now lives in [resolveSettingsDeepLink]
     * — a pure function free of `android.net.Uri` (a stub on the JVM test
     * VM) so it stays unit-testable. This wrapper only adapts the URI into
     * that signature: Android's `Uri.getPath()` / `getQueryParameter()` hand
     * over **already-decoded** values, so the argument reaches the
     * `Routes.*` builders raw and they are the single place that re-encodes
     * it (no double-encoding).
     */
    private fun parseSettingsPath(uri: Uri): DeepLinkAction {
        val query = uri.queryParameterNames.orEmpty()
            .associateWith { uri.getQueryParameter(it).orEmpty() }
        return when (val resolved = resolveSettingsDeepLink(uri.path.orEmpty(), query)) {
            is SettingsDeepLink.Route -> DeepLinkAction.OpenSettingsScreen(resolved.route)
            is SettingsDeepLink.EnvVarCreate -> DeepLinkAction.CreateEnvironmentVariable(
                key = resolved.key,
                value = resolved.value,
                note = resolved.note,
            )
            SettingsDeepLink.PermissionSettings -> DeepLinkAction.OpenPermissionSettings
        }
    }

    /**
     * Strip C0 control characters (0x00–0x1F except 0x09 TAB) and
     * 0x7F (DEL) from a string that came from an external deep-link
     * parameter. These characters have terminal-level side effects
     * (CR → execute, LF → execute, ESC → CSI sequences, NUL → input
     * corruption, BEL → bell, etc.) and must never reach the PTY.
     *
     * This is defence-in-depth: the consumer ([TerminalScreen]) also
     * sanitizes before sending to the PTY.
     */
    private fun sanitizeInitCommand(text: String?): String? {
        if (text == null) return null
        val safe = text.filter { it != '\u0000' && (it >= ' ' || it == '\t') && it != '\u007f' }
        return safe.ifBlank { null }
    }
}
