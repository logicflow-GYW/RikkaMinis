package com.rikkaminis.app.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [WAVE-1118 D9-F01] "Cancelling the caller ends the turn" — for EVERY
 * `wait = true` entry, not just `chat.prompt`.
 *
 * `[INCR-1117 A-F04]` established the rule and implemented it in
 * [HeadlessChatRunner.prompt]: the turn streams on the session's own
 * `viewModelScope`, so dropping the wait alone leaves it running tools and
 * burning tokens for an answer nobody waits for. `retry` /
 * `rerunFromToolBlock` / `compact` were written as structural copies of that
 * wait and never got the `catch (ce: CancellationException)` half — so a
 * sub-agent's stop, or an RPC connection that went away, cancelled the caller
 * and left the child streaming.
 *
 * Kept out of [HeadlessChatRunner] and generic on purpose: the decision
 * ("cancel the caller ⇒ stop the run, then rethrow") has no Android,
 * ViewModel or RPC in it, so it is testable on the JVM. The production call
 * site passes `vm::cancelStream` as [stop].
 *
 * @param stop what "stop this session's work" means to the caller. Invoked
 *   under [NonCancellable] so the stop itself cannot be cut short, and never
 *   allowed to replace the caller's `CancellationException`.
 * @param block one bounded wait (`withTimeoutOrNull { … }`).
 */
internal suspend fun <T> abandoningWaitStopsRun(stop: () -> Unit, block: suspend () -> T): T =
    try {
        block()
    } catch (ce: CancellationException) {
        withContext(NonCancellable) { runCatching { stop() } }
        throw ce
    }
