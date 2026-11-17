package com.rikkaminis.app.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Reference-counted bookkeeping for sessions that are currently dispatching a
 * sub-agent.
 *
 * Why this exists: a dispatched sub-agent is a REAL session, and every turn it
 * takes updates its own row. The dispatching session does not — it is blocked
 * waiting for the answer — so `ORDER BY updated_at DESC` pushes it below every
 * child it spawned, and a session that spawned three sub-agents falls off the
 * drawer while it is the one the user is actually working in.
 *
 * The fix is to pin the dispatcher for the duration of the dispatch and restore
 * its previous pin state when the last dispatch finishes:
 *  - reference counted, because one session can dispatch several sub-agents
 *    concurrently and the first `end` must not unpin a still-active dispatch;
 *  - in-memory only, because the pin is a view concern with a natural lifetime
 *    (the process that is running the dispatch). A crash cannot leave a
 *    permanent pin behind.
 *
 * [begin] returns the new depth so the caller can tell "I started it" (depth 1
 * → pin) from "someone else already did" (depth > 1 → leave it alone).
 *
 * The pin VALUE that was written lives here too, not in a local at the call
 * site: which dispatch writes it and which one finishes last are different
 * coroutines, so "the one that pinned restores" is not a safe assumption (see
 * [noteWrittenPin]).
 */
object SubagentDispatchPin {

    private val depths = ConcurrentHashMap<String, Int>()

    /**
     * sessionId → the `pinnedAt` value THIS dispatch window wrote.
     *
     * [INCR-1117 A-F02] This used to be a `var ourPin` local in
     * `executeSpawnAgentTool`, assigned only by the dispatch that saw depth 1.
     * With two overlapping dispatches on one parent the depth-1 dispatch holds
     * the value while the LAST `end` runs in the other one, where the local is
     * null — so the restore was skipped and the parent stayed pinned for good.
     * The value belongs to the window, not to whichever coroutine opened it.
     */
    private val writtenPins = ConcurrentHashMap<String, Long>()

    /** Register a dispatch starting for [sessionId]; returns the new depth. */
    fun begin(sessionId: String): Int =
        depths.merge(sessionId, 1, Int::plus) ?: 1

    /** Register a dispatch finishing for [sessionId]; returns the remaining depth. */
    fun end(sessionId: String): Int {
        val left = depths.compute(sessionId) { _, v ->
            if (v == null || v <= 1) null else v - 1
        }
        return left ?: 0
    }

    /** True while at least one dispatch for [sessionId] is running. */
    fun isDispatching(sessionId: String): Boolean = depths.containsKey(sessionId)

    /**
     * Remember the `pinnedAt` value written for [sessionId] during this window,
     * so the dispatch that finishes LAST can undo exactly that value — no
     * matter which one took the pin.
     */
    fun noteWrittenPin(sessionId: String, pinnedAt: Long) {
        writtenPins[sessionId] = pinnedAt
    }

    /**
     * Read and forget the value recorded by [noteWrittenPin]; null when this
     * window never pinned the session (it was already pinned, or the pin write
     * failed). Consuming rather than reading keeps the record from outliving
     * the window that wrote it.
     */
    fun takeWrittenPin(sessionId: String): Long? = writtenPins.remove(sessionId)

    /** Test/teardown hook: forget every session. */
    fun clear() {
        depths.clear()
        writtenPins.clear()
    }
}
