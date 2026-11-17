package com.rikkaminis.app.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * ## Window operations, not counts
 *
 * Callers must use [begin] and [end]; the count itself is
 * private. Each is ONE operation that covers the count AND the Room round trip
 * around it, serialized per session by a [Mutex]:
 *
 *  - [begin]: count up, and — on the first window — read `pinned_at`,
 *    write the pin and record the written value;
 *  - [end]: count down, and — on the last window — read `pinned_at`,
 *    undo the pin when it is still ours, and forget the record.
 *
 * [INCR-1117B A3-a] They are mutually exclusive because the pre-fix code
 * decremented the count FIRST and only then did two suspend (Room) calls with
 * no lock. A new window opened inside that suspension window saw `depth == 1`,
 * read the pin the OLD window was about to undo, concluded "the user already
 * pinned it", skipped pinning — and was then unpinned by the old window, which
 * left a dispatching session off the drawer. With the lock, "the window is
 * closed and `pinned_at` is back to its pre-dispatch value" is a single atomic
 * step, so a new window can only ever observe a state that is not being
 * restored.
 *
 * [INCR-1117B A3-b] Both operations run under [NonCancellable]. The pre-fix
 * open sequence was `runCatching { read }` / `runCatching { pin }` placed
 * BEFORE the caller's `try`: a stop that landed after the pin write but before
 * its continuation made `runCatching` swallow the CancellationException, so
 * `noteWrittenPin` never ran and the restore had nothing to undo — the parent
 * stayed pinned for good. Here the pin write and its bookkeeping cannot be
 * separated by a cancellation, and no CancellationException is caught: if the
 * window cannot be opened at all, nothing has been written either.
 *
 * The pin VALUE that was written lives here too, not in a local at the call
 * site: which dispatch pins and which one finishes last are different
 * coroutines, so "the one that pinned restores" is not a safe assumption (see
 * [writtenPins]).
 *
 * The names [begin] / [end] are not decoration: `scripts/scan/subagent_dispatch_check.py`
 * (D5) anchors "the pin is taken before the child runs and released inside a
 * `finally`" on the call-site tokens `SubagentDispatchPin.begin(` /
 * `SubagentDispatchPin.end(`, and the pre-fix call site used them. Keeping the
 * names keeps that gate honest without editing the gate.
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

    /**
     * One lock per session, held for the whole of a window operation.
     *
     * Not removed when a window closes: a coroutine already waiting on this
     * [Mutex] would then share it with nobody, and the next window would create
     * a second one — the mutual exclusion this exists for would be gone.
     * Bounded by "sessions that have dispatched once", i.e. the same lifetime
     * as [depths].
     */
    private val windowLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sessionId: String): Mutex =
        windowLocks.computeIfAbsent(sessionId) { Mutex() }

    /**
     * Open a dispatch window for [sessionId] and pin it when this is the first
     * window. Returns the new depth (1 = "this window pinned it").
     *
     * [readPinnedAt] and [pin] are the Room calls
     * (`getSession(id)?.pinnedAt` / `pinSession(id, true)`), injected so the
     * decision logic is plain JVM code.
     *
     * Never throws on cancellation: the caller's `try { … } finally { [end] } }`
     * opens AFTER this returns, so a cancellation escape here would leak both
     * the count and the pin.
     *
     * ponytail: the lock is held only across the two Room statements, not
     * across the child turn, so the uncancellable wait is bounded by one local
     * SQLite call. 天花板: if a `pinSession` ever hangs (contended DB writer,
     * stuck migration) the next window waits there too, uncancellably.
     * 升级触发: a `pinSession` watchdog/panic shows up in the logs.
     */
    suspend fun begin(
        sessionId: String,
        readPinnedAt: suspend (String) -> Long?,
        pin: suspend (String) -> Long?,
    ): Int = withContext(NonCancellable) {
        lockFor(sessionId).withLock {
            val depth = depths.merge(sessionId, 1, Int::plus) ?: 1
            // Only the first window pins, and only when nobody has pinned this
            // session already — a user pin must not be adopted as ours.
            if (depth == 1 && readPinnedAt(sessionId) == null) {
                pin(sessionId)?.let { writtenPins[sessionId] = it }
            }
            depth
        }
    }

    /**
     * Close a dispatch window for [sessionId]; the last one restores the pin it
     * took. Returns true when the window closed cleanly (or had nothing to
     * restore), false when the restore was attempted and failed — the caller
     * logs that, and the record is KEPT so a later window can still heal it.
     *
     * Call it from a `finally`: it is the only place the pin can be released.
     */
    suspend fun end(
        sessionId: String,
        readPinnedAt: suspend (String) -> Long?,
        unpin: suspend (String) -> Unit,
    ): Boolean = withContext(NonCancellable) {
        lockFor(sessionId).withLock {
            val left = depths.compute(sessionId) { _, v ->
                if (v == null || v <= 1) null else v - 1
            } ?: 0
            if (left != 0) return@withLock true

            val ourPin = writtenPins[sessionId] ?: return@withLock true
            // Undo only OUR pin: a manual pin taken meanwhile has a newer value
            // (or a null after an unpin) and must survive.
            val current = try {
                readPinnedAt(sessionId)
            } catch (_: Throwable) {
                return@withLock false
            }
            if (current == ourPin) {
                try {
                    unpin(sessionId)
                } catch (_: Throwable) {
                    return@withLock false
                }
            }
            writtenPins.remove(sessionId)
            true
        }
    }

    /** True while at least one dispatch for [sessionId] is running. */
    fun isDispatching(sessionId: String): Boolean = depths.containsKey(sessionId)

    /**
     * Test/teardown hook: forget every session's count and pin record.
     *
     * The per-session locks are deliberately NOT reset — see [windowLocks].
     */
    fun clear() {
        depths.clear()
        writtenPins.clear()
    }
}
