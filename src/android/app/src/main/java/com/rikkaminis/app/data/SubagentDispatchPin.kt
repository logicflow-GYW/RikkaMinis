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
 */
object SubagentDispatchPin {

    private val depths = ConcurrentHashMap<String, Int>()

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

    /** Test/teardown hook: forget every session. */
    fun clear() = depths.clear()
}
