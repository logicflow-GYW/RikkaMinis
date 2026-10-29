package com.rikkaminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that displays a persistent notification while agent sessions
 * are actively running. Shows session count, current tool name, and elapsed time.
 */
class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "agent_status"
        private const val CHANNEL_NAME = "Agent Status"
        private const val NOTIFICATION_ID = 9001

        // [T-bg-overlay phase 2 fix] Separate channel + notification id
        // for the SYSTEM_ALERT_WINDOW permission nudge so it can have a
        // higher importance than the ongoing FGS status row (which is
        // intentionally LOW so it doesn't make sound on every tool).
        private const val OVERLAY_NUDGE_CHANNEL_ID = "overlay_permission_nudge"
        private const val OVERLAY_NUDGE_NOTIFICATION_ID = 9002

        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"

        /**
         * [R4-U F-04] The live instance, if any. Set in [onCreate], cleared in
         * [onDestroy] — a companion-level reference (not a static Context) so the
         * service can be addressed without `startForegroundService`.
         */
        @Volatile
        private var liveInstance: AgentForegroundService? = null

        /**
         * Starts or updates the foreground service with current status.
         */
        fun startService(context: Context, sessionCount: Int, toolStatus: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
                putExtra(EXTRA_TOOL_STATUS, toolStatus)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * [R4-U F-04] Re-render the ongoing notification of an ALREADY RUNNING
         * instance.
         *
         * `startForegroundService()` is the operation that races an OEM
         * reclaim (5-second startForeground deadline); a `notify()` on a
         * notification the service already owns is not. Splitting the two is
         * what lets the status row stay truthful while the app is backgrounded
         * (previously `SessionActivityTracker.updateService()` refused both, so
         * the row froze at the moment the user pressed Home while tools kept
         * running).
         *
         * @return true when a running instance took the update; false when there
         *   is nothing to refresh (caller must not fall back to a start while
         *   invisible — that is the crash this guards).
         */
        fun refreshIfRunning(sessionCount: Int, toolStatus: String): Boolean {
            val instance = liveInstance ?: return false
            if (!instance.foregroundStarted) return false
            // Callers run on IO threads; the builder touches app resources and
            // the notification is owned by the main thread's service.
            instance.mainHandler.post {
                instance.applyStatusNotification(sessionCount, toolStatus)
            }
            return true
        }

        /**
         * [R4-U F-05] Whether a finished background turn could still show its
         * completion capsule — i.e. whether keeping the service alive for
         * [CompletionLinger.LINGER_MS] buys the user anything. Requires a live
         * foreground service, the overlay toggle ON and SYSTEM_ALERT_WINDOW
         * granted; otherwise the notification row would linger showing "Idle"
         * for no reason and the stop proceeds after the normal debounce.
         */
        fun completionLingerEligible(): Boolean {
            val instance = liveInstance ?: return false
            return instance.foregroundStarted &&
                instance.overlayEnabledNow &&
                instance.overlayPermissionNow
        }

        /**
         * Stops the foreground service.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var startTimeMs: Long = 0L

    /**
     * [R4-U F-04] Main-thread queue for notification refreshes requested by the
     * tracker from IO threads, plus the two flags that make a refresh safe:
     * [foregroundStarted] (the service owns the notification — nothing to update
     * before `startForeground`) and the overlay eligibility pair
     * ([overlayEnabledNow] / [overlayPermissionNow]) consulted by
     * [completionLingerEligible].
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var foregroundStarted = false

    @Volatile
    private var overlayEnabledNow = false

    @Volatile
    private var overlayPermissionNow = false
    /**
     * Partial wake lock acquired while the foreground service is alive.
     * Required because Android can put the CPU to sleep even with a
     * foreground service running — Doze can suspend non-FGS background
     * threads, and on some OEM ROMs (MIUI, EMUI, ColorOS) the CPU
     * throttles aggressively after screen-off. Without this lock, long
     * shell commands can stall mid-stream when the device sleeps.
     *
     * Held only while the service runs; released in [onDestroy] so we
     * never leak across orientation changes or process restarts.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * T-bg-overlay phase 2: floating tool-status overlay manager + its
     * collector scope. The overlay is *aspirational* — visible only when:
     *   1. User toggled `backgroundOverlayEnabled` ON, AND
     *   2. SYSTEM_ALERT_WINDOW is granted, AND
     *   3. App is currently backgrounded, AND
     *   4. A tool is in flight (isToolRunning), OR was recently in flight
     *      and we're in the 30-second linger window.
     * Falls back silently to Phase 1 notification when any precondition
     * fails. Bound to the service lifetime so onDestroy tears everything
     * down deterministically.
     */
    private var overlayController: ToolOverlayController? = null
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lingerJob: Job? = null

    // [T-android-overlay-completion-pending] X9: linger a completed-state
    // capsule after the busy edge, but ONLY when the turn ended while the
    // app was backgrounded (and the overlay was eligible to show). Scoping
    // the flag to that exact busy→idle edge is what keeps the old
    // pre-show-if-busy regression out: lastOutcome / lastReplyExcerpt
    // persist across turns in the tracker, so any rule that consults them
    // without an edge guard re-pops the capsule on every later home-screen
    // visit. The flag clears on user dismissal (tap-to-open / X) and on
    // foreground (the user saw the reply in-app).
    private var hasCompletionPending = false
    private var wasBusy = false

    /**
     * Safe mode means MinisApp.onCreate tripped over a crash burst and
     * skipped its lateinit repository init — see the onCreate comment.
     * [isInitSkipped] is the process-wide one-way latch MainActivity also
     * gates on: finishClose clears _safeMode while this process has still
     * skipped init, so a service recreated in that window must not trust
     * isSafeMode() alone. Read through one helper so the init-skip states
     * stay in one place and the two guards below stay spelled identically.
     */
    private fun isSafeMode(): Boolean =
        com.rikkaminis.app.crash.CrashFrequencyDetector.isSafeMode()

    private fun isInitSkipped(): Boolean =
        com.rikkaminis.app.crash.CrashFrequencyDetector.isInitSkipped()

    /**
     * True when MinisApp.onCreate took the database-downgrade early return
     * ([com.rikkaminis.app.data.db.DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE]).
     * Same init-skip shape as safe mode: every lateinit repository was never
     * built, so nothing in this service may touch them. Read the same way
     * MainActivity does.
     */
    private fun isDbGuidanceMode(): Boolean =
        (applicationContext as? MinisApp)?.dbVersionDecision ==
            com.rikkaminis.app.data.db.DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE

    override fun onCreate() {
        super.onCreate()
        // [R4-U F-04] Publish the instance so the tracker can refresh the
        // notification from the background without going through
        // startForegroundService. Cleared in onDestroy.
        liveInstance = this
        // [T-android-downgrade-compat] MinisApp.onCreate can bail out before
        // building the lateinit repositories in two states: safe mode (a
        // crash burst tripped CrashFrequencyDetector) and database guidance
        // (the on-disk schema is newer than this build). A sticky FG service
        // that was running before that spawn is still recreated by the
        // system, and reading MinisApp.backgroundSettingsRepository
        // from ToolOverlayController.<init> here would throw
        // UninitializedPropertyAccessException — writing a second crash log
        // from the very detection logic that meant to stop the crashing.
        // Skip the overlay observer and let onStartCommand satisfy the
        // FG-deadline + stopSelf.
        if (isSafeMode() || isInitSkipped() || isDbGuidanceMode()) {
            Log.w(
                TAG,
                "app init was skipped (safeMode=${isSafeMode()}, initSkipped=${isInitSkipped()}, dbGuidance=${isDbGuidanceMode()}) " +
                    "— skipping overlay/wake-lock bring-up",
            )
            createNotificationChannel()
            return
        }
        createNotificationChannel()
        startTimeMs = SystemClock.elapsedRealtime()
        // [RC4] The CPU wakelock is no longer tied to the service lifecycle.
        // It is held only while an active stream is in flight — driven by
        // SessionActivityTracker.activeSessions (see startOverlayObserver's
        // wakelock collector). A present-only FGS (user composing/reading,
        // no stream) keeps the stable foreground adj without pinning the CPU.
        startOverlayObserver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Safe-mode: system restarted us under START_STICKY (intent==null)
        // after a crash. Satisfy the 5-second startForeground deadline
        // with a stub notification, then unwind. The crash share dialog
        // owns the UX from here; running a background service in this
        // state would re-trip the lateinit access that brought us down.
        //
        // The database-guidance skip is the same degraded-mode shape: the
        // on-disk schema is newer than this build, MinisApp.onCreate bailed
        // before building the repositories, so an OEM restart of this service
        // would hit the same UninitializedPropertyAccessException. A sticky
        // service can be recreated in either state, hence the shared stub.
        if (isSafeMode() || isInitSkipped() || isDbGuidanceMode()) {
            try {
                val stub = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("RikkaMinis")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setOngoing(false)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        stub,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, stub)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "safe-mode stub startForeground failed: ${t.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0
        val toolStatus = intent?.getStringExtra(EXTRA_TOOL_STATUS) ?: "Idle"

        val notification = buildNotification(sessionCount, toolStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // [R4-U F-04] From here on the notification exists and may be refreshed
        // from the background (see refreshIfRunning).
        foregroundStarted = true

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Swipe-from-recents handler. Default Service behaviour on some OEM
     * builds is to silently end the service when the task is removed
     * even if it's a foreground service — we lose the streamJob, the
     * notification disappears, and the user thinks "Stop" was tapped.
     *
     * Re-anchor the service to its own intent and call startForeground
     * again. AOSP keeps it alive across task removal as long as at least
     * one active session is registered; if zero sessions remain (the
     * task removal raced a natural completion), [stopSelf] cleans up.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // T166: swiping from recents kills the Activity but the FG
        // service should survive iff a stream is still running. Pure
        // presence (user was reading a chat, then swiped away) is no
        // longer a reason to keep alive — they explicitly dismissed
        // the app, so clear presence here and re-evaluate.
        SessionActivityTracker.clearPresence()
        if (SessionActivityTracker.activeSessions.value.isEmpty()) {
            Log.d(TAG, "onTaskRemoved with no active sessions, stopping self")
            stopSelf()
            return
        }
        Log.d(TAG, "onTaskRemoved with ${SessionActivityTracker.activeSessions.value.size} active session(s) — keeping service alive")
        // Re-issue the foreground notification with current state so the
        // OS sees us as a "live" foreground service after the task tear-
        // down. Without this, OEM ROMs sometimes downgrade us to a plain
        // background service and reclaim within ~60 s.
        val notification = buildNotification(
            SessionActivityTracker.activeSessions.value.size,
            SessionActivityTracker.currentToolStatus.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // [T-android-overlay-landscape-width-rotation-drift] A Service receives
    // raw configuration changes regardless of any manifest configChanges
    // filter. Forward orientation flips / multi-window resizes to the
    // overlay controller so the WindowManager capsule (which is not
    // recreated on rotation) re-clamps itself back into the new screen
    // bounds and picks up the new capped width.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    override fun onDestroy() {
        // [R4-U F-04] Drop the instance reference first: a tracker refresh that
        // lands during teardown must find nothing rather than touch a dying
        // NotificationManager owner.
        foregroundStarted = false
        if (liveInstance === this) liveInstance = null
        // [RC4] The wakelock is normally driven by the active-stream collector
        // below; releaseWakeLock() here is a defensive final cleanup (it is
        // idempotent) so a service teardown can never leave a wakelock behind.
        releaseWakeLock()
        try {
            overlayController?.hide()
        } catch (_: Throwable) {}
        overlayController = null
        overlayScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * T-bg-overlay phase 2: collect the (foreground, toolName, toolStatus,
     * isToolRunning, toggle) tuple and reflect it into the overlay. We
     * combine() inside the service so the collector dies cleanly with
     * onDestroy and we never leak views across service restarts.
     *
     * Linger behaviour: when [SessionActivityTracker.isToolRunning] flips
     * false (tool finished), we don't hide immediately — Phase 2 spec
     * wants 30 s of "task ended" feedback while the app stays
     * backgrounded. Foreground transitions always hide instantly so the
     * overlay doesn't draw on top of the chat itself.
     */
    private fun startOverlayObserver() {
        val app = applicationContext as? MinisApp ?: return
        overlayController = ToolOverlayController(applicationContext).apply {
            // [T-android-overlay-reply-status-34599] Tap-to-open or X
            // dismissal clears the lingered completion state so the
            // observer's AND-gate stops re-showing the capsule on
            // subsequent emissions (e.g. a stale toolStatus flip).
            // [T-android-overlay-completion-pending] Also drop the
            // completion-pending linger — the user has either opened the
            // session or explicitly dismissed. No re-emission is needed:
            // the controller hides itself on the dismissal path, and the
            // cleared flag only matters on the NEXT applyOverlayState pass.
            onDismissByUser = {
                hasCompletionPending = false
                SessionActivityTracker.dismissOverlay()
            }
        }
        val backgroundRepo = app.backgroundSettingsRepository

        overlayScope.launch {
            combine(
                app.isAppForegroundFlow,
                SessionActivityTracker.currentToolName,
                SessionActivityTracker.currentToolStatus,
                SessionActivityTracker.isToolRunning,
                backgroundRepo.backgroundOverlayEnabled,
                SessionActivityTracker.lastToolOutcome,
                SessionActivityTracker.lastReplyExcerpt,
                SessionActivityTracker.currentSessionId,
                SessionActivityTracker.currentToolTitle,
                SessionActivityTracker.cameraSuppressActive,
                SessionActivityTracker.lastToolName,
                SessionActivityTracker.lastToolTitle,
                SessionActivityTracker.lastToolStatus,
                SessionActivityTracker.activeSessions,
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val activeSessions = values[13] as Set<String>
                OverlayState(
                    isForeground = values[0] as Boolean,
                    toolName = values[1] as String?,
                    toolStatus = values[2] as String,
                    isRunning = values[3] as Boolean,
                    enabled = values[4] as Boolean,
                    lastOutcome = values[5] as ToolOutcome,
                    lastReplyExcerpt = values[6] as String?,
                    currentSessionId = values[7] as String?,
                    toolTitle = values[8] as String?,
                    cameraSuppress = values[9] as Boolean,
                    lastToolName = values[10] as String?,
                    lastToolTitle = values[11] as String?,
                    lastToolStatus = values[12] as String?,
                    hasActiveStream = activeSessions.isNotEmpty(),
                )
            }.distinctUntilChanged().collect { state -> applyOverlayState(state) }
        }

        // [RC4] CPU wakelock is held ONLY while an active stream is in
        // flight. We take `activeSessions` empty -> non-empty as "stream
        // started" (acquire) and non-empty -> empty as "stream ended"
        // (release). A present-only FGS (composing/reading, no stream) does
        // NOT pin the CPU — it keeps the stable foreground adj via the
        // notification alone. collectLatest would race release-on-empty with
        // a re-acquire; a plain collect on the StateFlow gives us every
        // observed value so the acquire/release edge logic stays correct.
        overlayScope.launch {
            SessionActivityTracker.activeSessions.collect { active ->
                if (active.isNotEmpty()) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
            }
        }
    }

    private data class OverlayState(
        val isForeground: Boolean,
        val toolName: String?,
        val toolStatus: String,
        val isRunning: Boolean,
        val enabled: Boolean,
        val lastOutcome: ToolOutcome,
        val lastReplyExcerpt: String?,
        val currentSessionId: String?,
        val toolTitle: String?,
        val cameraSuppress: Boolean,
        val lastToolName: String?,
        val lastToolTitle: String?,
        val lastToolStatus: String?,
        // T-android-overlay-show-if-busy: at least one session has an
        // active streamJob (assistant generating reply). Together with
        // isRunning (tool executing) this is the only signal the overlay
        // uses to decide whether to surface — the previous "linger after
        // completion" semantics are dropped per spec.
        val hasActiveStream: Boolean,
    )

    private fun applyOverlayState(state: OverlayState) {
        val controller = overlayController ?: return
        val hasPerm = controller.hasOverlayPermission()
        // [R4-U F-05] Cached so the tracker can ask (synchronously, off the
        // collector) whether a completion capsule could actually be shown —
        // the 500 ms idle-stop debounce is shorter than a collector round trip,
        // so live-querying the toggle here would race and lose.
        overlayEnabledNow = state.enabled
        overlayPermissionNow = hasPerm

        // [T-android-overlay-show-if-busy] Overlay surfaces while the
        // agent is actively working — either an assistant streamJob is
        // mid-generation OR a tool is executing.
        // [T-android-overlay-completion-pending] X9 brings back a linger,
        // but edge-scoped: when busy flips false WHILE the overlay is
        // eligible and the app is backgrounded, the capsule stays in a
        // completed/replied rendering until the user taps it (open or X).
        // A turn that ends in the foreground sets nothing, so
        // backgrounding later does NOT re-pop — that edge guard is the
        // fix for the old regression where lastOutcome / lastReplyExcerpt
        // (which persist across turns) re-popped the capsule on every
        // home-screen visit.
        val isBusy = state.hasActiveStream || state.isRunning
        if (wasBusy && !isBusy && !state.isForeground && state.enabled &&
            hasPerm && !state.cameraSuppress
        ) {
            hasCompletionPending = true
        }
        wasBusy = isBusy
        val shouldShow = state.enabled && resolveOverlayIntent(
            wantShown = isBusy,
            permission = hasPerm,
            foreground = state.isForeground,
            camera = state.cameraSuppress,
            hasPending = hasCompletionPending,
        )
        Log.d(
            TAG,
            "applyOverlayState fg=${state.isForeground} enabled=${state.enabled} " +
                "perm=$hasPerm streaming=${state.hasActiveStream} toolRunning=${state.isRunning} " +
                "cameraSuppress=${state.cameraSuppress} completionPending=$hasCompletionPending " +
                "toolName=${state.toolName} toolTitle=${state.toolTitle} shouldShow=$shouldShow shown=${controller.isShown}",
        )
        // [T-bg-overlay phase 2 fix] Permission nudge — the user opted in
        // via the Settings toggle but Android still rejects our
        // SYSTEM_ALERT_WINDOW. Post a high-importance notification that
        // deep-links to the system overlay-permission screen.
        if (state.enabled && !hasPerm && isBusy && !state.isForeground) {
            maybePostOverlayPermissionNudge()
        }
        // Foreground / toggle-off / no-perm → hide immediately, cancel
        // any (legacy) linger timer. [T-android-overlay-foreground-hide]
        // We deliberately do NOT clear tracker state on a foreground
        // transition: the user only saw the chat, they didn't explicitly
        // dismiss the capsule. If they background the app again while a
        // tool is still running OR the post-completion linger hasn't been
        // explicitly cleared (X / tap-to-open route through
        // `onDismissByUser` → `dismissOverlay()`), the capsule should
        // reappear. Toggle-off / no-perm also leave tracker state alone
        // so a subsequent re-enable picks up where we left off.
        if (state.isForeground || !state.enabled || !hasPerm || state.cameraSuppress) {
            // [T-android-overlay-completion-pending] Foreground means the
            // user saw the reply in-app — drop the pending linger so a
            // later backgrounding doesn't re-pop a stale completion.
            // Toggle-off / no-perm / camera-suppress keep the flag, same
            // as they leave tracker state alone (see comment above): a
            // re-enable picks up where we left off.
            if (state.isForeground) hasCompletionPending = false
            lingerJob?.cancel()
            lingerJob = null
            // [T-android-overlay-intent-latch] Unconditional. Gating on
            // `isShown` dropped every flip that landed while show()'s
            // attach was still queued, and that attach then painted the
            // capsule over the chat / camera with no later frame to clear
            // it. hide() is idempotent and cheap when nothing is attached.
            controller.hide()
            return
        }

        if (shouldShow) {
            lingerJob?.cancel()
            lingerJob = null
            // [T-android-overlay-show-if-busy] When a tool is active the
            // tracker has live toolName/toolTitle/toolStatus; otherwise
            // (stream-only, no tool yet) those may be null/Idle, in which
            // case we fall back to the lastTool* snapshot from the most
            // recent tool of THIS turn so the capsule isn't a bare
            // spinner.
            val effectiveToolName = state.toolName ?: state.lastToolName
            val effectiveToolTitle = state.toolTitle ?: state.lastToolTitle
            val effectiveStatus = if (!state.toolStatus.equals("Idle", ignoreCase = true)) {
                state.toolStatus
            } else {
                state.lastToolStatus ?: state.toolStatus
            }
            if (isBusy) {
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = true,
                    outcome = ToolOutcome.Unknown,
                    replyExcerpt = null,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                )
            } else {
                // [T-android-overlay-completion-pending] Completion linger:
                // the turn ended while backgrounded. Render the controller's
                // existing completed state (outcome glyph + localized
                // completion word + reply excerpt row + visible X); it stays
                // until tap-to-open / X clears hasCompletionPending via
                // onDismissByUser, or a foreground transition does.
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = false,
                    outcome = state.lastOutcome,
                    replyExcerpt = state.lastReplyExcerpt,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                )
            }
            return
        }

        // Not busy AND no completion pending from this round — drop the
        // overlay if it's up. [T-android-overlay-completion-pending] With
        // the edge-scoped linger above, this branch now only fires when
        // the user already dismissed the completion (or none was pending,
        // e.g. the turn ended in foreground), so the old "task finished →
        // proactively hide" semantics still hold for those cases.
        // [T-android-overlay-intent-latch] Unconditional for the same
        // reason as the early-return branch above: the queued attach has
        // to be cancelled by intent, not by the not-yet-true `isShown`.
        controller.hide()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "minis:inference",
            ).apply {
                setReferenceCounted(false)
                // No timeout — release happens deterministically in onDestroy
                // when SessionActivityTracker reports zero active sessions.
                acquire()
            }
            Log.d(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK)")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bg_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bg_service_channel_description)
                setShowBadge(false)
            }
            // [T-bg-overlay phase 2 fix] Higher-importance channel for the
            // SYSTEM_ALERT_WINDOW permission nudge. IMPORTANCE_DEFAULT
            // gets a heads-up surface so the user actually sees that the
            // overlay they enabled needs one more grant.
            val nudgeChannel = NotificationChannel(
                OVERLAY_NUDGE_CHANNEL_ID,
                getString(R.string.bg_overlay_nudge_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.bg_overlay_nudge_channel_description)
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(nudgeChannel)
        }
    }

    /**
     * [T-bg-overlay phase 2 fix] Throttle for the SAW permission nudge.
     * `true` after the first emission this service lifetime so a single
     * agent loop that calls 10 tools doesn't post 10 identical
     * heads-up notifications. Reset on service destroy so a fresh
     * launch after the user has had a chance to think about it can
     * remind them again.
     */
    private var overlayNudgePosted: Boolean = false

    private fun maybePostOverlayPermissionNudge() {
        if (overlayNudgePosted) return
        overlayNudgePosted = true
        try {
            val mgrIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val pi = PendingIntent.getActivity(
                this,
                2,
                mgrIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(this, OVERLAY_NUDGE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.bg_overlay_nudge_title))
                .setContentText(getString(R.string.bg_overlay_nudge_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.bg_overlay_nudge_body)),
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(OVERLAY_NUDGE_NOTIFICATION_ID, notif)
            Log.d(TAG, "overlay permission nudge posted (SAW not granted but toggle is ON)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge failed: ${e.message}", e)
        }
    }

    /**
     * [R4-U F-04] Apply a tracker-driven status refresh to the notification this
     * service already owns. Main thread only ([refreshIfRunning] posts here).
     *
     * `buildNotification` reads the tracker's live tool name / running flag, so
     * this repaint is what keeps the row's title, text and indeterminate
     * progress in step with the agent while the app is backgrounded.
     */
    private fun applyStatusNotification(sessionCount: Int, toolStatus: String) {
        if (!foregroundStarted) return
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(sessionCount, toolStatus))
        } catch (t: Throwable) {
            Log.w(TAG, "background notification refresh failed: ${t.message}")
        }
    }

    private fun buildNotification(sessionCount: Int, toolStatus: String): Notification {
        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs
        val elapsedSeconds = (elapsedMs / 1000).toInt()
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

        val mainIntent = Intent(this, Class.forName("com.rikkaminis.app.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionLabel = resources.getQuantityString(
            R.plurals.bg_service_sessions, sessionCount, sessionCount,
        )

        // T-bg-overlay phase 1: enrich the ongoing notification.
        // Title:   "Minis is using <Tool>"  (or session-count summary when idle/between turns)
        // Text:    one-line "<sessionLabel> · <elapsed>" so the always-visible row stays compact
        // Progress: indeterminate while a tool is in flight (isToolRunning), hidden otherwise
        // The system Doze-friendly setOnlyAlertOnce keeps repeated rebuilds silent.
        //
        // Deliberately NOT expandable: no BigTextStyle, no action buttons.
        // The expanded body was identical to the collapsed one (zero
        // information gain) and the expand chevron it triggered only
        // existed to reveal the Stop action — both dropped per user
        // request. The row stays a single fixed-height line.
        val toolName = SessionActivityTracker.currentToolName.value
        val isToolRunning = SessionActivityTracker.isToolRunning.value

        val titleText = if (toolName != null) {
            toolDisplayLabel(toolName)
        } else {
            getString(R.string.bg_service_notification_title)
        }
        val collapsedText = getString(
            R.string.bg_service_notification_text, sessionLabel, toolStatus, timeString,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(toolSmallIconRes(toolName))
            // [T-notification-brand] Brand tint so the ongoing row reads as
            // "Minis" at a glance instead of a generic system service
            // notification. setColor tints the small icon + title. The large
            // app icon (setLargeIcon) was dropped per user request — the small
            // status-bar icon carries the identity, keeping the row compact.
            .setColor(ContextCompat.getColor(this, R.color.notification_brand_color))
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isToolRunning) {
            // Tools rarely report determinate progress (shell/browser/a11y
            // are open-ended). Always indeterminate while a tool is in
            // flight; explicitly drop progress when not, so the bar
            // disappears at idle/between-turn moments.
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * T-bg-overlay phase 1: human-readable label per tool, mirroring
     * `ChatScreen.kt:5974 toolTitleLabel` so the notification's title
     * matches what the in-app FloatingToolStatusBar shows. Falls back
     * to the raw tool name for unknowns rather than a generic string,
     * so the user still gets a hint about what's running.
     */
    // [fix/audit-b22 / T8-L4] Every other string in this notification comes
    // from R.string (7 locales); these labels were hardcoded English, so
    // non-English users saw a mixed-language notification title.
    private fun toolDisplayLabel(toolName: String): String = when (toolName) {
        "shell_execute" -> getString(R.string.notification_tool_shell)
        "file_read" -> getString(R.string.notification_tool_read_file)
        "file_write" -> getString(R.string.notification_tool_editor)
        "file_edit" -> getString(R.string.notification_tool_edit_file)
        "browser_use" -> getString(R.string.notification_tool_browser)
        "read_image" -> getString(R.string.notification_tool_read_image)
        "memory_write", "memory_get" -> getString(R.string.notification_tool_memory)
        "conversation_history" -> getString(R.string.notification_tool_history)
        "web_search" -> getString(R.string.notification_tool_search)
        else -> getString(R.string.notification_tool_generic, toolName)
    }

    /**
     * T-bg-overlay phase 1: pick a system small-icon hint per tool kind.
     * Notification small icons must be tintable monochrome — we use
     * built-in framework drawables instead of pulling in app icon
     * resources to avoid the Android < 24 "white square" fallback for
     * vector drawables. The default (`ic_menu_manage`) preserves the
     * pre-T pixel-identical look for idle / between-turn rebuilds.
     */
    private fun toolSmallIconRes(toolName: String?): Int = when (toolName) {
        "shell_execute" -> android.R.drawable.ic_menu_edit
        "file_read", "read_image" -> android.R.drawable.ic_menu_view
        "file_write", "file_edit" -> android.R.drawable.ic_menu_edit
        "browser_use" -> android.R.drawable.ic_menu_compass
        "memory_write", "memory_get" -> android.R.drawable.ic_menu_save
        "conversation_history" -> android.R.drawable.ic_menu_search
        "web_search" -> android.R.drawable.ic_menu_search
        else -> android.R.drawable.ic_menu_manage
    }
}
