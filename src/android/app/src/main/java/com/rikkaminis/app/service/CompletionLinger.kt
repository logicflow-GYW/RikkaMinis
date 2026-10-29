package com.rikkaminis.app.service

/**
 * [R4-U F-05] Pure core of the "completion capsule must outlive the turn"
 * window.
 *
 * The bug: a turn that ends while the app is backgrounded sets
 * `hasCompletionPending` in [AgentForegroundService] so the floating capsule
 * can render the finished state (glyph + reply excerpt), but
 * [SessionActivityTracker.setInactive] immediately schedules the idle stop with
 * [SessionActivityTracker]'s 500 ms debounce. With no session left the service
 * is destroyed ~500 ms later and `onDestroy` hides the capsule — the completion
 * state is torn down before it is ever visible. Same shape when the user swiped
 * the task away (`onTaskRemoved` clears presence, so the next turn end has
 * nothing holding the service).
 *
 * The fix keeps the service alive for a bounded window after a background turn
 * end, and only when the capsule could actually show the completion
 * ([eligible] = overlay toggle on + permission granted). The window is
 * deliberately finite: an unresolved completion capsule must not pin a
 * foreground service forever (see the report's residual — the capsule's own
 * "until dismissed" semantics survive only inside this window).
 */
internal object CompletionLinger {

    /**
     * How long the service outlives a background turn end.
     *
     * 30 s matches the linger the overlay design already assumes for "task
     * ended" feedback (`ToolOverlayController` hide/linger window) and stays
     * inside the 1-minute range OEM battery dashboards treat as a normal
     * transition rather than a rogue foreground service.
     */
    const val LINGER_MS = 30_000L

    /**
     * Deadline that cancels an armed window (R5-D F-04②). `0` already means
     * "no linger" to [remainingMs] / [shouldStopNow]; the constant gives the
     * explicit-dismissal paths (capsule closed, app back in foreground) one
     * name instead of a bare literal, and is what the "cleared ⇒ stop now"
     * test pins.
     */
    const val CLEARED = 0L

    /**
     * Deadline for the idle stop, or 0 when no linger applies.
     *
     * Linger requires all four: this call actually ENDED a run ([wasActive]),
     * the app is invisible ([appForeground] = false — in the foreground the
     * user already saw the reply and the capsule hides immediately anyway),
     * the capsule is eligible ([eligible]), and the clock is sane.
     */
    fun deadlineOnTurnEnd(
        nowMs: Long,
        wasActive: Boolean,
        appForeground: Boolean,
        eligible: Boolean,
    ): Long {
        if (!wasActive || appForeground || !eligible) return 0L
        if (nowMs <= 0L) return 0L
        return nowMs + LINGER_MS
    }

    /** Milliseconds left in the window; 0 when expired or never armed. */
    fun remainingMs(deadlineMs: Long, nowMs: Long): Long {
        if (deadlineMs <= 0L) return 0L
        val left = deadlineMs - nowMs
        return if (left > 0L) left else 0L
    }

    /** True when the idle stop may actually tear the service down now. */
    fun shouldStopNow(deadlineMs: Long, nowMs: Long): Boolean =
        remainingMs(deadlineMs, nowMs) <= 0L
}
