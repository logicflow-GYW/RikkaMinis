package com.rikkaminis.app

import com.rikkaminis.app.ui.navigation.LaunchSessionMode
import com.rikkaminis.app.ui.navigation.coldStartRestoreAllowed

/**
 * [R4-U F-01/F-02/F-09] Pure decisions behind `MainActivity`'s chat-route
 * listener and its cold-start restore.
 *
 * Android-free by construction (only the launch-mode constants are imported),
 * so the rules below are unit-testable straight against the production text
 * instead of through source-shape probes. `MainActivity` owns the Android half
 * ([CrashRecoveryStore] for the SharedPreferences file, the nav controller for
 * the back stack).
 */
internal object ChatRoutePresence {

    /**
     * Draft ids are minted as `__new__<uuid>` (`ComposerDraftStore.nextDraftId`,
     * the single source). Same prefix `SessionFileStore.DRAFT_ID_PREFIX` uses —
     * duplicated here because that one is file-private and this file must stay
     * importable from a JVM test without the storage layer's Android deps.
     */
    const val DRAFT_ID_PREFIX = "__new__"

    /**
     * `NavDestination.navigatorName` of a destination created by
     * `DialogNavigator` (`dialog(route = …)` in `AppNavigation`). Today the only
     * one is `Routes.FILE_PREVIEW`, but the decision is on the navigator kind so
     * the next dialog gets the same treatment without touching this file.
     */
    const val DIALOG_NAVIGATOR = "dialog"

    /**
     * [R4-U F-02] Mirror of `AppNavigation`'s AUTO window
     * (`autoThresholdMs = 15L * 60 * 1000`, a local literal inside the launch
     * dispatcher). Mirrored rather than shared because `AppNavigation` is not
     * this change's file; a restore synthesized here makes the dispatcher
     * return early on `hasDeepLink`, so this is the only place the window can
     * still be honoured on a cold start.
     */
    const val AUTO_RESUME_WINDOW_MS = 15L * 60 * 1000L

    /** True for a composer draft key (`__new__<uuid>`) — a chat with no row. */
    fun isDraftSessionId(sessionId: String?): Boolean =
        sessionId != null && sessionId.startsWith(DRAFT_ID_PREFIX)

    /**
     * [R4-U F-01] Whether [sessionId] may be written to the crash-recovery file.
     *
     * A draft key must never reach it: the row does not exist, so a native
     * crash / LMK restore would synthesize `OpenSession("__new__…")` and land
     * the user in a synthetic draft while the conversation they actually had is
     * elsewhere. Blank ids are equally useless as a restore target.
     */
    fun shouldPersistCrashRecovery(sessionId: String?): Boolean =
        !sessionId.isNullOrBlank() && !isDraftSessionId(sessionId)

    /**
     * [R4-U F-09] True when this nav emission comes from a DIALOG destination.
     *
     * A dialog is pushed ON TOP of the chat — the chat screen keeps rendering
     * underneath, so the emission is not "the user left the chat" and must be
     * resolved against the entry below it (`previousBackStackEntry`) instead.
     */
    fun isChatKeptAliveOverlay(navigatorName: String?): Boolean =
        navigatorName == DIALOG_NAVIGATOR

    /**
     * [R4-U F-02] The session id a TRUE cold start may restore, or null to hand
     * the decision back to `AppNavigation`'s launch-mode dispatcher.
     *
     * The synthesized restore deep link short-circuits the dispatcher
     * (`hasDeepLink`), so every rule the dispatcher would have applied has to be
     * applied here or it is silently skipped:
     *
     *  - the Launch Session preference ([coldStartRestoreAllowed]) — already
     *    honoured before this change;
     *  - AUTO's fresh-within-[AUTO_RESUME_WINDOW_MS] rule — the half that was
     *    missing: with AUTO selected, a record from three days ago reopened the
     *    old chat instead of starting a new conversation;
     *  - LAST_SESSION is unbounded on purpose ("always reopen the last chat").
     *
     * A record with no timestamp (`storedAtMs <= 0`, written by a build from
     * before this change) is treated as stale: the dispatcher then picks the
     * latest session or a fresh draft, which is the AUTO contract anyway.
     * A clock that jumped backwards (`storedAtMs > nowMs`) keeps the record —
     * treating it as stale would drop the restore the user expects, and the
     * only way to reach that state is a manual clock change.
     */
    fun decideColdStartRestore(
        launchMode: Int,
        storedSessionId: String?,
        storedAtMs: Long,
        nowMs: Long,
    ): String? {
        if (!coldStartRestoreAllowed(launchMode)) return null
        if (!shouldPersistCrashRecovery(storedSessionId)) return null
        if (launchMode == LaunchSessionMode.LAST_SESSION) return storedSessionId
        if (storedAtMs <= 0L) return null
        val ageMs = nowMs - storedAtMs
        if (ageMs > AUTO_RESUME_WINDOW_MS) return null
        return storedSessionId
    }
}
