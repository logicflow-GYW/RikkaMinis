package com.rikkaminis.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rikkaminis.app.diagnostics.SessionIdAliases
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that tracks two independent kinds of "session is alive":
 *
 *  - `activeSessions`: at least one [com.rikkaminis.app.ui.chat.ChatViewModel.streamJob]
 *    is in flight (LLM call, tool execution).
 *  - `presentSessions` (T166): the user is sitting on a chat screen
 *    with the composer mounted, regardless of whether a stream is
 *    running. Drives the FG service so backgrounding the chat for a
 *    minute doesn't drop the process to adj=700 and get it reclaimed.
 *
 * The foreground service runs whenever EITHER set is non-empty, so
 * the user holds a stable adj=200 across reading, composing, and
 * streaming alike. It stops only when both are empty (user has
 * navigated back to Sessions list AND no stream is in flight).
 */
object SessionActivityTracker {

    private const val TAG = "SessionTracker"

    private val _activeSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeSessions: StateFlow<Set<String>> = _activeSessions.asStateFlow()

    /**
     * T166: sessions the user is currently *present in* (composing /
     * reading), distinct from [activeSessions] which tracks streaming.
     * Drives the foreground service so the process stays at adj=200
     * the entire time the user is inside a chat — not just while the
     * stream is in flight. Without this, hitting Home from a chat
     * drops the process to adj=700 (LAST) and a Pixel 4a will reclaim
     * within minutes under any memory pressure, forcing a full
     * Activity rebuild on return.
     */
    private val _presentSessions = MutableStateFlow<Set<String>>(emptySet())
    val presentSessions: StateFlow<Set<String>> = _presentSessions.asStateFlow()

    private val _currentToolStatus = MutableStateFlow("Idle")
    val currentToolStatus: StateFlow<String> = _currentToolStatus.asStateFlow()

    /**
     * T-bg-overlay phase 1: tool name currently dispatched to the agent
     * (e.g. "shell_execute", "browser_use"). null when no tool is in
     * flight (idle, or between tool calls within a turn). The FGS
     * notification reads this to render a tool-specific icon + display
     * label without parsing [currentToolStatus]'s freeform string.
     */
    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    /**
     * [T-android-overlay-tool-title] Model-supplied `tool_title` for the
     * tool currently in flight (e.g. "Open Baidu home page", "Take screenshot of
     * current page"). Null when the model didn't supply one OR no tool is
     * running. The overlay capsule and notification prefer this over the
     * static per-tool label ("Browser", "Shell", …) so users see the
     * actual intent of the call rather than just the tool kind.
     *
     * Populated from the dispatch loop in [com.rikkaminis.app.ui.chat.ChatViewModel]
     * by reading the `tool_title` arg uniformly for ALL tools — so
     * browser_use (which has no per-tool status override) surfaces the
     * title alongside shell_execute and friends.
     */
    private val _currentToolTitle = MutableStateFlow<String?>(null)
    val currentToolTitle: StateFlow<String?> = _currentToolTitle.asStateFlow()

    /**
     * T-bg-overlay phase 1: true while a tool call is actively executing
     * (between dispatch and result). Drives the notification's
     * indeterminate progress bar so the user can tell at a glance whether
     * Minis is "between turns" (false → no progress) vs "doing something"
     * (true → spinning bar).
     */
    private val _isToolRunning = MutableStateFlow(false)
    val isToolRunning: StateFlow<Boolean> = _isToolRunning.asStateFlow()

    /**
     * [R4-U F-06] Per-session running-tool state (pure core,
     * [ToolSlotRegistry]). The four flows above are now DERIVED from it by
     * [publishToolSlot] instead of being the storage: two sessions streaming at
     * once used to share one slot, so the fast session's tool finishing blanked
     * the slow session's tool mid-execution.
     *
     * Radius note: no production call site passes a session id yet (they all go
     * through the 4-arg `updateToolStatus` / `clearToolRunning`), so
     * [toolSlotKey] attributes an update to a session only when exactly one is
     * streaming; the concurrent case lands in
     * [ToolSlotRegistry.UNATTRIBUTED], where the depth counter at least stops
     * the first `finish` from wiping the second tool's display. Wiring the
     * callers is a provider-pack change — see the report's residual R1.
     */
    private val toolSlots = ToolSlotRegistry()

    /**
     * [R4-U F-05] Idle-stop deadline extended so a background turn end can show
     * the completion capsule (pure core, [CompletionLinger]). 0 = no linger.
     */
    private var completionLingerUntilMs = 0L

    /**
     * [R4-U F-06] Which registry key an unattributed tool update belongs to.
     *
     * One streaming session ⇒ that session (correct by construction). Two or
     * more ⇒ the shared [ToolSlotRegistry.UNATTRIBUTED] bucket, because guessing
     * one of them would attribute a tool to the wrong chat — the very bug this
     * fix is about.
     */
    private fun toolSlotKey(): String {
        val active = _activeSessions.value
        return if (active.size == 1) active.first() else ToolSlotRegistry.UNATTRIBUTED
    }

    /**
     * [R4-U F-06] Recomputes the display flows from the registry's
     * representative entry. Called after every registry mutation so the four
     * public flows never drift from the per-session truth.
     */
    private fun publishToolSlot() {
        val slot = toolSlots.representative(_activeSessions.value)
        _currentToolName.value = slot?.toolName
        _currentToolTitle.value = slot?.toolTitle
        _isToolRunning.value = slot?.isRunning == true
        if (slot != null) {
            _currentToolStatus.value = slot.status
            // Only an attributed entry may retarget the capsule: the shared
            // bucket cannot say which chat the work belongs to.
            if (slot.sessionId.isNotBlank()) _currentSessionId.value = slot.sessionId
        }
    }

    /**
     * T-overlay-glyph-typed-outcome: typed outcome of the most recently
     * completed tool call. Replaces the old text-sniffing heuristic in
     * [ToolOverlayController] (which read stale "Running: foo" status
     * text and always inferred success). Set by [clearToolRunning];
     * defaults to [ToolOutcome.Unknown] until any tool finishes.
     */
    private val _lastToolOutcome = MutableStateFlow(ToolOutcome.Unknown)
    val lastToolOutcome: StateFlow<ToolOutcome> = _lastToolOutcome.asStateFlow()

    /**
     * Snapshot of the most recently completed tool's identity + status
     * line. Captured by [clearToolRunning] right before the live
     * [currentToolName] / [currentToolTitle] / [currentToolStatus] are
     * wiped, so the floating overlay can surface what the agent just did
     * after the run ends (e.g. "browser_use — Completed" + "Opened Google.com
     * in system Chrome") instead of a bare "Done". Cleared on
     * [dismissOverlay] and on [setActive] so a fresh run starts blank.
     */
    private val _lastToolName = MutableStateFlow<String?>(null)
    val lastToolName: StateFlow<String?> = _lastToolName.asStateFlow()

    private val _lastToolTitle = MutableStateFlow<String?>(null)
    val lastToolTitle: StateFlow<String?> = _lastToolTitle.asStateFlow()

    private val _lastToolStatus = MutableStateFlow<String?>(null)
    val lastToolStatus: StateFlow<String?> = _lastToolStatus.asStateFlow()

    /**
     * [T-android-overlay-reply-status-34599] Truncated excerpt of the
     * most recent assistant reply for the currently-tracked session.
     * Published by ChatViewModel via [publishLastReply] right before
     * [setInactive] so the overlay can show "what did Minis just say".
     * Null when no reply has been observed yet this session-cycle;
     * cleared when a fresh session goes active (so the previous
     * session's reply doesn't bleed into a newly-started turn).
     */
    private val _lastReplyExcerpt = MutableStateFlow<String?>(null)
    val lastReplyExcerpt: StateFlow<String?> = _lastReplyExcerpt.asStateFlow()

    /**
     * [T-android-overlay-reply-status-34599] Session ID associated with
     * [lastReplyExcerpt] and the current activity. Drives the
     * "tap-overlay → open chat" intent in [ToolOverlayController] by
     * synthesising a `minis://session/<id>` deep-link the existing
     * DeepLinkHandler already understands.
     */
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /** Max chars of the assistant reply we surface in the overlay. */
    private const val REPLY_EXCERPT_MAX = 72

    /**
     * [T-android-overlay-hide-camera] True while the user has launched the
     * system camera (ACTION_IMAGE_CAPTURE) from inside Minis and we're
     * waiting on the ActivityResult callback. The overlay observer in
     * [AgentForegroundService] gates `shouldShow` on this flag so the
     * floating capsule doesn't obstruct the camera viewfinder — Minis is
     * technically backgrounded during the capture (the camera Activity is
     * on top), which would otherwise satisfy the bg-only show rule from
     * #451. Cleared in the camera launcher's result callback (success,
     * cancel, or launch-failure) so a fresh bg event after the user
     * returns reactivates the overlay normally.
     */
    private val _cameraSuppressActive = MutableStateFlow(false)
    val cameraSuppressActive: StateFlow<Boolean> = _cameraSuppressActive.asStateFlow()

    fun setCameraSuppressActive(active: Boolean) {
        _cameraSuppressActive.value = active
    }

    private var appContext: Context? = null

    /**
     * Route replacement briefly removes the draft session before adding the
     * persisted session. Stopping an FGS in that gap and immediately starting
     * it again can leave an unsatisfied startForegroundService() record on
     * Android 16 (especially HyperOS), which crashes the process with
     * ForegroundServiceDidNotStartInTimeException even though the replacement
     * service instance called startForeground(). Debounce only the idle stop;
     * a new active/present session cancels it synchronously.
     */
    private const val IDLE_STOP_DELAY_MS = 500L
    private val mainHandler = Handler(Looper.getMainLooper())
    // Explicit `: Runnable` is required: the body re-arms itself via
    // `postDelayed(pendingIdleStop, …)`, and an inferred type makes the
    // compiler's type check recursive (CI #2871: "recursive problem").
    private val pendingIdleStop: Runnable = Runnable {
        if (shouldRunService()) {
            // A session came back mid-debounce: cancelPendingIdleStop() already
            // removed this callback, so reaching here means the state is fresh.
            return@Runnable
        }
        // [R4-U F-05] Do not tear the service down while a background turn's
        // completion capsule still needs it. Re-arm once for the remaining
        // window instead of stopping, so the linger is bounded and cannot pin
        // the foreground service forever.
        val remaining = CompletionLinger.remainingMs(
            completionLingerUntilMs,
            System.currentTimeMillis(),
        )
        if (remaining > 0L) {
            AppLogger.info(TAG, "idle stop deferred $remaining ms for completion capsule")
            mainHandler.postDelayed(pendingIdleStop, remaining)
            return@Runnable
        }
        completionLingerUntilMs = 0L
        stopServiceNow()
    }

    private fun cancelPendingIdleStop() {
        mainHandler.removeCallbacks(pendingIdleStop)
    }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(pendingIdleStop)
        mainHandler.postDelayed(pendingIdleStop, IDLE_STOP_DELAY_MS)
    }

    /**
     * T-MIUI-FGS-race: set by [MinisApp]'s lifecycle callbacks so the
     * tracker knows whether the user can see the app. When false, we
     * skip FG-service start/update calls (the 5-second
     * startForeground deadline is un-catchable from the app side, and
     * HyperOS aggressively reclaims background processes — any
     * startForegroundService call that races a kill will throw
     * ForegroundServiceDidNotStartInTimeException). The service can
     * still be alive from a pre-background session; we just don't
     * initiate new ones while the app is invisible.
     * Memory state (flows) always updates regardless of foreground
     * status — the user sees the correct state when they return.
     * Default true (first launch is always in the foreground).
     */
    private var isAppForeground: Boolean = true

    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
    }

    /**
     * Called when the app returns to the foreground. If sessions are
     * still active (streaming or presence), re-start the FG service
     * so it reflects the current state. No-op if nothing needs it.
     */
    fun maybeRefreshService() {
        if (isAppForeground && shouldRunService()) {
            startServiceIfNeeded()
        }
    }

    /**
     * The service should run iff at least one session is streaming OR
     * the user is currently present in at least one chat. Both inputs
     * are independently mutated, so we always recompute from the live
     * state flows rather than tracking a derived flag.
     */
    private fun shouldRunService(): Boolean =
        _activeSessions.value.isNotEmpty() || _presentSessions.value.isNotEmpty()

    /**
     * T50: per-session stream-cancel callbacks. Each ChatViewModel
     * registers its own [com.rikkaminis.app.ui.chat.ChatViewModel.cancelStream]
     * here when [setActive] is called and unregisters in [setInactive].
     *
     * No consumer since `c409e694` removed the foreground service's
     * notification "Stop" action — the registration side is deliberately
     * kept as the wiring point should a stop affordance return, so a future
     * audit should read this as a declared resume point, not as forgotten
     * dead state. Guarded by its own
     * `synchronized(streamCancellers)` block (independent of the lock-free
     * `_activeSessions` StateFlow); the canceller is registered under that
     * lock before [setActive] returns, so a caller never observes a session
     * as "active" without its canceller.
     */
    private val streamCancellers = mutableMapOf<String, () -> Unit>()

    /**
     * [fix/audit0917-b8] Guards the read-modify-write of [_activeSessions]
     * plus the `wasActive` decision in [setActive] / [setInactive]. Kept
     * separate from the `streamCancellers` monitor and the
     * [StreamErrorFlags] lock so the completion-listener fan-out below stays
     * outside any lock.
     */
    private val activeSessionsLock = Any()

    /**
     * T180-bg-notif: per-session "this RUN ended in error" flag. Drives the
     * success/error variant of the completion notification AND the
     * scheduled-task outcome ([com.rikkaminis.app.scheduled.ScheduledTaskRunner]
     * maps `isError` → RESULT_ERROR / RESULT_OK).
     *
     * Lifecycle — one flag per run, every edge owned by a named call:
     *  - **set** → [markStreamError] the moment a terminal failure reaches the
     *    error banner (`ChatViewModel.setInlineError`);
     *  - **clear** → [clearStreamError] when that same run recovers (inline
     *    error retracted by `clearInlineError`), plus the stale-flag drop in
     *    [setActive] so a leftover flag can never report the NEXT run;
     *  - **consume** → [setInactive] reads-and-removes it as `wasError`, which
     *    is the value the completion listener fans out.
     *
     * [fix/da1015-sched-errorprop] Before this, the only writers were
     * `catch (e: Exception)` blocks at the run boundaries — but
     * `AgentLoopEngine` swallows almost every model failure and returns
     * NORMALLY (`terminalErrorSurfaced` + fall-through to the break), so those
     * catches never ran: a failed run was reported as a clean completion,
     * `ScheduledTaskRunner` recorded RESULT_OK, `lastRunDate` got pinned and
     * `failStreak` reset ⇒ the task fuse could never trip.
     *
     * The store is [StreamErrorFlags] — plain Kotlin, no Android — so the
     * run-scoping rule is unit-testable directly.
     */
    private val pendingErrorFlag = StreamErrorFlags()

    /**
     * T180-bg-notif: completion listener. Wired in MinisApp.onCreate to
     * a [com.rikkaminis.app.notification.BackgroundTaskNotifier] so when
     * an agent loop ends while the app is backgrounded, the user gets a
     * tap-to-open-session notification — mirrors iOS
     * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
     *
     * Held as a single-slot setter rather than a list because there's
     * exactly one notifier per process. Keeping the tracker free of
     * direct ChatRepository / Notifier dependencies preserves its
     * "pure session-tracking" responsibility.
     */
    private var completionListener: ((sessionId: String, isError: Boolean) -> Unit)? = null

    fun setCompletionListener(listener: ((sessionId: String, isError: Boolean) -> Unit)?) {
        completionListener = listener
    }

    /**
     * [T-android-overlay-reply-status-34599] Push the most recent
     * assistant reply for [sessionId] into the overlay surface. The
     * text is collapsed to a single line and truncated to
     * [REPLY_EXCERPT_MAX] chars with an ellipsis when over budget so
     * the overlay capsule doesn't blow out horizontally. No-op when the
     * text is blank — we don't want to render an empty bubble that
     * looks like a UI bug.
     */
    fun publishLastReply(sessionId: String, fullText: String?) {
        val collapsed = fullText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() } ?: return
        val excerpt = if (collapsed.length > REPLY_EXCERPT_MAX) {
            collapsed.substring(0, REPLY_EXCERPT_MAX).trimEnd() + "…"
        } else {
            collapsed
        }
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = excerpt
    }

    /**
     * [T-android-overlay-reply-status-34599] User explicitly dismissed
     * the floating overlay (X button or tap-to-open-chat). Clears the
     * lingered reply state so the overlay observer in
     * [AgentForegroundService] flips its `shouldShow` predicate to
     * false and pulls the view down. The session activity itself stays
     * untouched — the agent loop continues; the user just chose to
     * stop being notified about it.
     */
    fun dismissOverlay() {
        _lastReplyExcerpt.value = null
        _lastToolOutcome.value = ToolOutcome.Unknown
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
    }

    /**
     * Initialize with application context. Must be called once at app startup.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Marks a session as active. Starts the foreground service if this is
     * the first active session. [onStop], when supplied, is the agent
     * loop's cancel callback — captured here so the notification's Stop
     * action can fan out to every running session.
     */
    fun setActive(sessionId: String, onStop: (() -> Unit)? = null) {
        // [fix/da1015-sched-errorprop] Run-scoped flag hygiene. The flag is
        // normally consumed by setInactive at the end of the failing run, but
        // a stale streamJob whose finally skipped setInactive (the
        // claim-token guard) leaves it behind — and it would then be reported
        // as the NEXT run's outcome (false "failed" → task fuse counts a
        // failure for a run that succeeded). A new run starts from a clean
        // slate; it re-flags itself if it fails.
        pendingErrorFlag.clear(sessionId)
        // [fix/audit0917-b8] Same lock as setInactive — activation and
        // deactivation of the same session can arrive from different threads
        // (stream job vs. UI teardown); a concurrent pair used to be able to
        // leave the id removed while the service had already been started.
        val wasIdle = synchronized(activeSessionsLock) {
            val was = !shouldRunService()
            _activeSessions.value = _activeSessions.value + sessionId
            was
        }
        if (onStop != null) {
            synchronized(streamCancellers) { streamCancellers[sessionId] = onStop }
        }
        // [T-android-overlay-reply-status-34599] Track which session is
        // driving the overlay so the tap-to-open intent lands in the
        // right chat. Clear the previous reply excerpt so the user
        // doesn't briefly see a stale reply attached to a fresh run.
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = null
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
        AppLogger.info(TAG, "Session activated: $sessionId (total: ${_activeSessions.value.size})")
        // [R4-U F-05] A new run supersedes any completion capsule window.
        completionLingerUntilMs = 0L

        if (wasIdle) {
            startServiceIfNeeded()
        } else {
            updateService()
        }
    }

    /**
     * Marks a session as inactive. Stops the foreground service if no
     * sessions remain active *and* the user is no longer present in any
     * chat.
     */
    fun setInactive(sessionId: String) {
        // [fix/audit0917-b8] Claim the transition atomically. The old body read
        // `wasActive` and then removed the id in two separate steps, so two
        // concurrent setInactive(sameId) calls could both observe true and
        // invoke completionListener twice (double "run completed" notification).
        // Membership check + removal now happen under one lock; only the caller
        // that actually removed the id is the transition owner.
        val wasActive = synchronized(activeSessionsLock) {
            val was = sessionId in _activeSessions.value
            _activeSessions.value = _activeSessions.value - sessionId
            was
        }
        synchronized(streamCancellers) { streamCancellers.remove(sessionId) }
        val wasError = pendingErrorFlag.consume(sessionId)
        AppLogger.info(TAG, "Session deactivated: $sessionId (total: ${_activeSessions.value.size})")

        // [R4-U F-06] The ended session owns no running tool any more.
        toolSlots.drop(sessionId)
        if (_activeSessions.value.isEmpty()) {
            // The shared bucket belongs to no session either — keeping it would
            // render "running <tool>" for a turn that already ended.
            toolSlots.drop(ToolSlotRegistry.UNATTRIBUTED)
            _currentToolStatus.value = ToolSlotRegistry.IDLE_STATUS
            _currentToolName.value = null
            _currentToolTitle.value = null
            _isToolRunning.value = false
            // [T-android-overlay-reply-status-34599] Preserve the last
            // tool outcome AND the last reply excerpt across stream
            // teardown so the overlay observer in
            // [AgentForegroundService] can flip into "completed" mode
            // and render ✓ / ✗ + the assistant's reply text. Cleared
            // when the user dismisses the overlay via [dismissOverlay]
            // or starts a fresh run via [setActive].
            // Tag the error outcome here if the stream finalized with
            // an error (markStreamError was called); otherwise keep
            // whatever the last tool reported (Success / Error / etc.)
            // so the glyph reflects the actual end state.
            if (wasError) {
                _lastToolOutcome.value = ToolOutcome.Error
            }
        }
        if (!shouldRunService()) {
            // [R4-U F-05] Arm the bounded completion window BEFORE the stop is
            // scheduled: the service has to outlive this turn end when the
            // capsule is the only place the user will see the result.
            completionLingerUntilMs = CompletionLinger.deadlineOnTurnEnd(
                nowMs = System.currentTimeMillis(),
                wasActive = wasActive,
                appForeground = isAppForeground,
                eligible = AgentForegroundService.completionLingerEligible(),
            )
            stopService()
        } else {
            // [R4-U F-06] Another session is still streaming: re-derive the
            // display from the remaining entries instead of leaving the ended
            // session's tool on screen.
            publishToolSlot()
            updateService()
        }
        // T180-bg-notif: fire the completion listener AFTER service state
        // is settled so the notifier sees a stable activeSessions count.
        // Skip if the session was never marked active (defensive — keeps
        // the "completed" semantic honest).
        if (wasActive) {
            completionListener?.invoke(sessionId, wasError)
        }
    }

    /**
     * Marks the session's CURRENT run as having ended in an error. Must be
     * invoked BEFORE [setInactive] (the flag is consumed there). If never
     * called, the completion listener fires with `isError=false`.
     *
     * [fix/da1015-sched-errorprop] Idempotent, and paired with
     * [clearStreamError]: terminal failures flag here, a same-run recovery
     * retracts in `clearInlineError`, and [setActive] drops any stale flag
     * when the next run starts.
     *
     * User cancellation is deliberately NOT an error: no `CancellationException`
     * handler calls this (they only log), so a stopped run still reports
     * `isError=false`.
     */
    fun markStreamError(sessionId: String) {
        if (pendingErrorFlag.mark(sessionId)) {
            AppLogger.info(TAG, "Stream error flagged: $sessionId")
        }
    }

    /**
     * Counterpart of [markStreamError] for a run that did NOT end in failure —
     * the terminal error was retracted because the run recovered (the inline
     * banner is cleared by `clearInlineError`), or a new run is starting and
     * the previous run's flag is stale.
     *
     * Fires no completion listener and does not change [setInactive]'s
     * semantics: a session with no flag still completes with `isError=false`,
     * exactly as before this method existed.
     */
    fun clearStreamError(sessionId: String) {
        if (pendingErrorFlag.clear(sessionId)) {
            AppLogger.info(TAG, "Stream error flag cleared: $sessionId")
        }
    }

    /**
     * T166: marks the user as present in a chat (composer is mounted,
     * messages list is rendering). Idempotent. Starts the foreground
     * service the first time presence is recorded so the process holds
     * at adj=200 across Home / app-switcher / lock-screen, even when no
     * stream is in flight.
     */
    fun setPresent(sessionId: String) {
        if (sessionId in _presentSessions.value) return
        val wasIdle = !shouldRunService()
        _presentSessions.value = _presentSessions.value + sessionId
        Log.d(TAG, "Presence set: ${SessionIdAliases.resolve(sessionId)} (present total: ${_presentSessions.value.size})")
        if (wasIdle) {
            startServiceIfNeeded()
        } else {
            updateService()
        }
    }

    /**
     * T166: counterpart of [setPresent]. Stops the foreground service
     * if both presence and streaming are now empty. Called from
     * MainActivity when the user leaves the chat route or the Activity
     * is paused.
     */
    fun setAbsent(sessionId: String) {
        if (sessionId !in _presentSessions.value) return
        _presentSessions.value = _presentSessions.value - sessionId
        Log.d(TAG, "Presence cleared: ${SessionIdAliases.resolve(sessionId)} (present total: ${_presentSessions.value.size})")
        if (!shouldRunService()) {
            stopService()
        } else {
            updateService()
        }
    }

    /**
     * T166: clear all presence markers. Called only when MainActivity
     * is destroyed for real (not just paused) — Home press / lock
     * screen MUST keep presence so the FG service keeps the process
     * pinned at adj=200 across the user's brief absence. Backgrounding
     * is the entire scenario this exists to protect against.
     */
    fun clearPresence() {
        if (_presentSessions.value.isEmpty()) return
        _presentSessions.value = emptySet()
        Log.d(TAG, "Presence cleared (all)")
        if (!shouldRunService()) {
            stopService()
        } else {
            updateService()
        }
    }

    /**
     * Returns whether a specific session is currently active.
     */
    fun isActive(sessionId: String): Boolean = sessionId in _activeSessions.value

    /**
     * Updates the current tool status displayed in the notification.
     * Legacy single-argument variant — leaves [currentToolName] and
     * [isToolRunning] untouched. New callers should prefer the overload
     * below so the notification can render a tool-specific icon and
     * progress indicator.
     */
    fun updateToolStatus(status: String) {
        // [R4-U F-06] Text-only update, routed through the registry so a future
        // per-session writer is not shadowed by a global status write.
        toolSlots.start(
            sessionId = toolSlotKey(),
            toolName = null,
            toolTitle = null,
            status = status,
            isRunning = false,
        )
        publishToolSlot()
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * T-bg-overlay phase 1: rich tool-status update. Pass [toolName] =
     * null + [isRunning] = false to clear (e.g. tool finished, between
     * turns). The FGS notification rebuilds when any of name / status /
     * running flag changes.
     */
    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean) {
        updateToolStatus(status, toolName, isRunning, toolTitle = null)
    }

    /**
     * [T-android-overlay-tool-title] Rich update that also carries the
     * model-supplied `tool_title`. When [toolTitle] is non-blank the
     * overlay label uses it directly (e.g. "Open Baidu home page") instead of the
     * static per-tool label ("Browser"). Pass null/blank to fall back to
     * the per-tool label (existing behavior).
     */
    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean, toolTitle: String?) {
        applyToolUpdate(toolSlotKey(), status, toolName, isRunning, toolTitle)
    }

    /**
     * [R4-U F-06] Session-attributed variant — the wiring point for callers that
     * know which chat they are running in (`AgentLoopEngine`'s tool dispatch,
     * the offload handlers). No production caller passes a session id yet, so
     * this is a declared resume point: switching a caller from the 4-arg
     * overload to this one immediately gives that tool its own registry entry
     * and takes the "fast session blanks the slow one" case out of the shared
     * bucket. Kept next to the overload instead of a separate object so the two
     * writers cannot drift.
     */
    fun updateToolStatus(
        sessionId: String,
        status: String,
        toolName: String?,
        isRunning: Boolean,
        toolTitle: String?,
    ) {
        if (sessionId.isBlank()) {
            applyToolUpdate(toolSlotKey(), status, toolName, isRunning, toolTitle)
            return
        }
        applyToolUpdate(sessionId, status, toolName, isRunning, toolTitle)
    }

    private fun applyToolUpdate(
        key: String,
        status: String,
        toolName: String?,
        isRunning: Boolean,
        toolTitle: String?,
    ) {
        toolSlots.start(key, toolName, toolTitle, status, isRunning)
        publishToolSlot()
        // [T-overlay-glyph-typed-outcome] Starting a new tool clears the
        // previous outcome so the overlay glyph (which reads
        // lastToolOutcome) does not leak the prior result into the new
        // tool's "running" state.
        if (isRunning) _lastToolOutcome.value = ToolOutcome.Unknown
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * T-bg-overlay phase 1: clear tool-running state without touching
     * [currentToolStatus]. Called when a tool block flips to
     * SUCCESS/FAILED/TIMEOUT/CANCELLED so the notification stops
     * showing an active progress bar.
     */
    fun clearToolRunning(outcome: ToolOutcome = ToolOutcome.Unknown) {
        // [R4-U F-06] Finish only this key's entry — the display is re-derived
        // from whatever remains. With several sessions streaming and no
        // attribution the shared bucket's depth decides, so one tool ending no
        // longer blanks a tool another session still runs.
        val key = toolSlotKey()
        val ending = toolSlots.slot(key)
        if (ending == null && !_isToolRunning.value && _currentToolName.value == null) return
        // Snapshot identity + status before wiping the live values so the
        // overlay's post-completion render has something concrete to show.
        // Only retain a status string when it's a real tool-status line
        // (not the "Idle" placeholder).
        _lastToolName.value = ending?.toolName ?: _currentToolName.value
        _lastToolTitle.value = ending?.toolTitle ?: _currentToolTitle.value
        _lastToolStatus.value = (ending?.status ?: _currentToolStatus.value)
            ?.takeIf {
                it.isNotBlank() && !it.equals(ToolSlotRegistry.IDLE_STATUS, ignoreCase = true)
            }
        toolSlots.finish(key)
        publishToolSlot()
        _lastToolOutcome.value = outcome
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * [R4-U F-06] Session-attributed finish — pairs with the 5-arg
     * [updateToolStatus]. Wiring a caller's start AND its finish together is
     * what keeps the per-session entries balanced; wiring only one side would
     * leave the shared bucket's depth stuck above zero.
     */
    fun clearToolRunning(outcome: ToolOutcome, sessionId: String) {
        if (sessionId.isBlank()) {
            clearToolRunning(outcome)
            return
        }
        val ending = toolSlots.slot(sessionId)
        _lastToolName.value = ending?.toolName ?: _currentToolName.value
        _lastToolTitle.value = ending?.toolTitle ?: _currentToolTitle.value
        _lastToolStatus.value = (ending?.status ?: _currentToolStatus.value)
            ?.takeIf {
                it.isNotBlank() && !it.equals(ToolSlotRegistry.IDLE_STATUS, ignoreCase = true)
            }
        toolSlots.finish(sessionId)
        publishToolSlot()
        _lastToolOutcome.value = outcome
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    private fun startServiceIfNeeded() {
        cancelPendingIdleStop()
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot start service")
            return
        }
        // T-MIUI-FGS-race: never initiate a foreground service while the
        // app is invisible. The app-side can't catch the system's
        // ForegroundServiceDidNotStartInTimeException, so the only way to
        // avoid it is to not start a FG service from the background at all.
        if (!isAppForeground) {
            Log.d(TAG, "skipping FG service start while app is backgrounded")
            return
        }
        AgentForegroundService.startService(
            context,
            sessionCountForNotification(),
            statusForNotification(),
        )
    }

    private fun updateService() {
        cancelPendingIdleStop()
        val context = appContext ?: return
        // T-MIUI-FGS-race: updating an already-running FG service from the
        // background is fine (it reuses the existing notification). But if
        // for some reason the service was torn down while backgrounded, a
        // from-background start here would race the kill. Skip when the
        // app is invisible — memory state still updates, and the service
        // will be refreshed on next foreground via [maybeRefreshService].
        if (!isAppForeground) {
            // [R4-U F-04] Two different operations were being refused with one
            // guard. `startForegroundService()` while invisible is the
            // T-MIUI-FGS-race crash and stays refused — but a notification on
            // an ALREADY RUNNING foreground service is a plain
            // NotificationManager.notify(): no start deadline, no OEM race, safe
            // from the background. Refusing it too is what froze the status row
            // (text + progress) at the exact moment the user left the app, while
            // tools kept running and the overlay kept updating.
            if (AgentForegroundService.refreshIfRunning(
                    sessionCountForNotification(),
                    statusForNotification(),
                )
            ) {
                Log.d(TAG, "FGS notification refreshed while app is backgrounded")
                return
            }
            Log.d(TAG, "skipping FG service update while app is backgrounded")
            return
        }
        AgentForegroundService.startService(
            context,
            sessionCountForNotification(),
            statusForNotification(),
        )
    }

    /**
     * Notification session count = streaming sessions if any, otherwise
     * presence count. The user sees "1 session — Streaming…" while a
     * turn runs, and "1 session — In session" while they're composing
     * but idle. Two-bucket display keeps the count honest without
     * double-counting a session that's both present and streaming.
     */
    private fun sessionCountForNotification(): Int =
        if (_activeSessions.value.isNotEmpty()) _activeSessions.value.size
        else _presentSessions.value.size

    /**
     * Tool status fallback: when nothing is streaming, expose "In session"
     * (or "Idle" if the user isn't even in a chat). The FG service is
     * still running because of presence, but the notification shouldn't
     * imply a tool is executing.
     *
     * T180-bg-notif: localize via context resources when the active set
     * is non-empty — the notification reads "1 task running" / "N tasks
     * running" instead of falling through to the per-tool English status.
     * Tool-specific status strings (e.g. "browser_use") still surface as
     * `_currentToolStatus.value` when set; otherwise we synthesize
     * "%d task(s) running".
     */
    private fun statusForNotification(): String {
        val ctx = appContext
        val activeCount = _activeSessions.value.size
        return when {
            activeCount > 0 -> {
                val tool = _currentToolStatus.value
                if (tool.isNotBlank() && tool != "Idle") {
                    tool
                } else if (ctx != null) {
                    if (activeCount == 1) {
                        ctx.getString(com.rikkaminis.app.R.string.notif_one_task_running)
                    } else {
                        ctx.getString(com.rikkaminis.app.R.string.notif_n_tasks_running, activeCount)
                    }
                } else {
                    if (activeCount == 1) "1 task running" else "$activeCount tasks running"
                }
            }
            _presentSessions.value.isNotEmpty() ->
                ctx?.getString(com.rikkaminis.app.R.string.notif_in_session) ?: "In session"
            else -> "Idle"
        }
    }

    private fun stopService() {
        // Do not stop synchronously. Compose/navigation can replace a draft
        // session id with its persisted id in adjacent frames; a synchronous
        // stop followed by startForegroundService() is the Android 16 crash
        // race documented above.
        scheduleIdleStop()
    }

    private fun stopServiceNow() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot stop service")
            return
        }
        AgentForegroundService.stopService(context)
        Log.d(TAG, "All sessions complete, service stopped after idle debounce")
    }
}

/**
 * [fix/da1015-sched-errorprop] Run-scoped "this session's stream ended in an
 * error" store — the pure core of [SessionActivityTracker.pendingErrorFlag].
 *
 * Extracted out of the tracker because the tracker is an Android singleton
 * (Context / Handler / Looper / Log), so its flag semantics could only be
 * pinned by source probes. This class is plain Kotlin: `mark` / `clear` /
 * `consume` under one lock, no Android imports — which makes the run-scoping
 * rule ("a flag never outlives the run that set it") unit-testable directly
 * instead of by assertion on text.
 *
 * Threading: marked from the agent-loop coroutine (IO) and consumed from
 * whichever thread the run's `finally` lands on — the same reason the
 * tracker wrapped its bare `mutableSetOf` in `synchronized`.
 */
internal class StreamErrorFlags {
    private val lock = Any()
    private val flagged = mutableSetOf<String>()

    /** @return true when THIS call set the flag (false = already flagged). */
    fun mark(sessionId: String): Boolean = synchronized(lock) { flagged.add(sessionId) }

    /** @return true when THIS call retracted a flag (false = nothing to clear). */
    fun clear(sessionId: String): Boolean = synchronized(lock) { flagged.remove(sessionId) }

    /** Consume-on-read for [SessionActivityTracker.setInactive]: return the
     *  flag AND drop it, so it can never be counted twice. */
    fun consume(sessionId: String): Boolean = clear(sessionId)
}
