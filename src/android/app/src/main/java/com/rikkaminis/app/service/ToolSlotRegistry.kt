package com.rikkaminis.app.service

/**
 * [R4-U F-06] Per-session running-tool registry — the pure core of
 * [SessionActivityTracker]'s tool state.
 *
 * The tracker used to hold ONE global slot (`_currentToolName` /
 * `_currentToolTitle` / `_isToolRunning`), but `SessionConcurrencyManager`
 * allows 2–3 sessions to stream at once. Two consequences, both reported:
 *
 *  - a fast session's tool finishing called `clearToolRunning()`, which nulled
 *    the name/title of whatever tool the SLOW session still had in flight —
 *    the notification fell back to a bare "%d tasks running" while a tool was
 *    demonstrably running;
 *  - `setActive()` of the second session reset the overlay's reply/tool
 *    snapshot of the first.
 *
 * This class keeps one entry per session key. What the notification / overlay
 * shows is derived by [representative] — see its KDoc for the selection rule.
 *
 * ## Depth, not a boolean
 *
 * A session can hold at most one tool at a time in the agent loop, but the
 * CURRENT call sites do not pass a session id yet (see the tracker's
 * `toolSlotKey`), so the concurrent case still lands in one shared
 * [UNATTRIBUTED] key. To keep that fallback honest the entry counts starts and
 * finishes: an interleaved `start(A) start(B) finish(A) finish(B)` must not
 * clear the display at `finish(A)`. Depth is what makes "still running" true
 * until the LAST finish — a boolean would have been wiped by the first.
 *
 * Plain Kotlin on purpose (no Android imports): the whole rule set below is
 * table-testable in the JVM suite.
 */
internal class ToolSlotRegistry {

    companion object {
        /**
         * Bucket for updates whose caller cannot name a session (every call
         * site today). Kept distinct from real session ids — which are uuids —
         * so a future wired caller's entry never merges into it.
         */
        const val UNATTRIBUTED = ""

        /** Displayed when no entry exists (mirrors the tracker's old default). */
        const val IDLE_STATUS = "Idle"
    }

    /** Immutable view of one session's tool state. */
    data class Slot(
        val sessionId: String,
        val toolName: String?,
        val toolTitle: String?,
        val status: String,
        val depth: Int,
        /** Monotonic touch counter; higher = more recent. Used only for ordering. */
        val seq: Long,
    ) {
        val isRunning: Boolean get() = depth > 0
    }

    private var seqCounter = 0L

    /**
     * Insertion order is irrelevant (selection uses [Slot.seq]); a plain
     * `LinkedHashMap` keeps iteration deterministic for tests and logs.
     */
    private val entries = LinkedHashMap<String, Slot>()

    /**
     * Records a status update for [sessionId].
     *
     * @param isRunning true when this update STARTS a tool (depth +1). A
     *   non-running update only refreshes the text — it must never raise the
     *   depth, otherwise a finished tool would look alive forever.
     * @param status the human-readable status line; blank keeps the previous one.
     * @return the touched entry, or null when a non-running update hit an
     *   unknown session (nothing to refresh — the caller decides whether that
     *   is worth creating an entry; today it is not, see [finish]).
     */
    fun start(
        sessionId: String,
        toolName: String?,
        toolTitle: String?,
        status: String,
        isRunning: Boolean,
    ): Slot {
        val previous = entries[sessionId]
        val next = Slot(
            sessionId = sessionId,
            toolName = toolName ?: previous?.toolName,
            toolTitle = toolTitle?.takeIf { it.isNotBlank() } ?: previous?.toolTitle,
            status = status.ifBlank { previous?.status ?: IDLE_STATUS },
            depth = (previous?.depth ?: 0) + if (isRunning) 1 else 0,
            seq = ++seqCounter,
        )
        entries[sessionId] = next
        return next
    }

    /**
     * Records that ONE tool of [sessionId] finished.
     *
     * The entry is dropped only when the depth reaches zero — with the
     * pre-snapshot already taken by the caller (it needs name/title/status for
     * the completion capsule), so the drop loses nothing.
     *
     * @return the entry that remains after this finish (null = nothing left).
     */
    fun finish(sessionId: String): Slot? {
        val previous = entries[sessionId] ?: return null
        val depth = previous.depth - 1
        if (depth <= 0) {
            entries.remove(sessionId)
            return null
        }
        val next = previous.copy(depth = depth, seq = ++seqCounter)
        entries[sessionId] = next
        return next
    }

    /**
     * Drops a session's entry outright (session ended, or the run was
     * cancelled). Depth is deliberately ignored: an ended session owns no
     * running tool, and keeping a phantom depth would pin the display to a
     * status nobody is producing any more.
     */
    fun drop(sessionId: String) {
        entries.remove(sessionId)
    }

    fun slot(sessionId: String): Slot? = entries[sessionId]

    fun clear() {
        entries.clear()
        seqCounter = 0L
    }

    /**
     * [R4-U F-06] The entry the notification / overlay should render, or null
     * when nothing is worth showing.
     *
     * Selection rule (deliberately simple and stated here instead of inferred
     * from call order):
     *
     *  1. Prefer entries whose session is currently STREAMING
     *     ([activeSessionIds]) — that is the work the user asked for; a stale
     *     entry from a finished turn must not shadow it.
     *  2. Within that group prefer RUNNING entries (depth > 0) over merely
     *     touched ones, then the most recent ([Slot.seq]).
     *  3. If no streaming session has an entry, fall back to the same
     *     running-then-recent order over ALL entries — this is what keeps the
     *     unattributed bucket visible for sessions the tracker has not seen
     *     through `setActive` (offload handlers, a search-index run).
     */
    fun representative(activeSessionIds: Set<String>): Slot? {
        if (entries.isEmpty()) return null
        val active = entries.values.filter { it.sessionId in activeSessionIds }
        val pool = active.ifEmpty { entries.values.toList() }
        return pool.sortedWith(
            compareByDescending<Slot> { it.isRunning }.thenByDescending { it.seq },
        ).firstOrNull()
    }
}
