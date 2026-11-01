package com.rikkaminis.app.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.random.Random

/** Default randomness for the outward pacing: kotlin.random.Random, bounds inclusive. Kept on its own line so tests can swap [ActionGuard.randomSource]. */
internal val DefaultGuardRandom: (Long, Long) -> Long = { lo, hi -> Random.nextLong(lo, hi + 1) }

/** Default sleeper: really block the calling thread. Kept on its own line so tests can swap [ActionGuard.sleeper] and never actually sleep. */
internal val DefaultGuardSleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) }

/** Default clock: wall time in milliseconds. Kept on its own line so tests can swap [ActionGuard.clock] for a deterministic one. */
internal val DefaultGuardClock: () -> Long = { System.currentTimeMillis() }

/**
 * What the host must do with a guarded call, returned by [ActionGuard.beforeCall].
 */
sealed interface GuardVerdict {

    /** Run the tool now, nothing to tell the model. */
    data object Proceed : GuardVerdict

    /** Run the tool now and append [notice] to the tool result. */
    data class ProceedWithNotice(val notice: String) : GuardVerdict

    /**
     * The facade has ALREADY slept [millis] (through the injected sleeper, inside beforeCall, so
     * the waiting happened before execution). Run the tool now and surface [notice]
     * ("waited Ns...") with the result.
     */
    data class Wait(val millis: Long, val notice: String) : GuardVerdict

    /** Do NOT run the tool. Surface [reason] to the model as a `loop_guard` error result. */
    data class Refuse(val reason: String) : GuardVerdict
}

/**
 * Facade around per-session [LoopGuard]s (anti-loop) and one process-wide [OutwardPacing]
 * (anti-risk-control pacing of outward actions). All state lives in memory and dies with the
 * process - accepted semantics.
 *
 * The host calls, for every guarded tool call, in order:
 *
 *  1. [beforeCall] - the verdict before the tool runs. [GuardVerdict.Refuse] means the tool must
 *     NOT run; surface `reason` to the model as a `loop_guard` error. [GuardVerdict.Wait] means
 *     the sleep already happened; run the tool and show `notice`. [GuardVerdict.ProceedWithNotice]
 *     runs with an extra note; [GuardVerdict.Proceed] runs plain.
 *  2. the tool itself (unless refused).
 *  3. [afterCall] - records the outcome and returns reminder/stop text to append to the result
 *     (null = nothing to append). Do NOT call it after a [GuardVerdict.Refuse] (the tool never
 *     ran); it is ignored anyway while the session is stopped.
 *
 * ### Threshold table (per session; aligned with DeepseekHarness-Android)
 *
 * | Signal   | Reminders at | Stop at                      | Counts                                                    |
 * |----------|--------------|------------------------------|-----------------------------------------------------------|
 * | Repeat   | 3, 6         | 12 (refused BEFORE it runs)  | same action + same normalized target, last 16 calls       |
 * | NoEffect | 3, 6, 9      | 12 (stop text on the result) | caller reports ok but the screen did not change           |
 * | Reread   | 5, 8         | 12 (stop text on the result) | screen read returned an unchanged screen                  |
 * | Failure  | 2, 4         | 8  (stop text on the result) | caller reports the call failed                            |
 *
 * After a stop, read-only tools ([isRead]) still pass - with a notice - so the model can look
 * around and report to the user; everything else is refused. A stop caused by [Signal.Reread]
 * refuses reads too.
 *
 * ### State and clearing conditions
 *
 *  - One [LoopGuard] per session key ([sessionId], blank/null -> "default") in an in-memory
 *    [ConcurrentHashMap]; process death resets everything.
 *  - [onUserTurn] - the user spoke / a new round started: the session's whole loop-guard state
 *    (all four signals, the signature window, any stop) is cleared, and so is its session-scoped
 *    outward-pacing history. Pacing keys that name a target app survive the turn on purpose:
 *    they are shared across sessions by design.
 *  - Outward pacing ([OutwardPacing.KEYWORDS]): first outward action per key immediate, later
 *    ones wait the remainder of a random 8-20 s gap that grows with recent outward actions of
 *    the same key and caps at 30 s. The wait happens inside [beforeCall] via [sleeper] and is
 *    reported through [GuardVerdict.Wait].
 *
 * Thread-safety: all state is concurrent or synchronized. Randomness, sleeping and the clock are
 * injectable function values ([randomSource], [sleeper], [clock]) with their defaults declared on
 * their own lines above - tests swap them and never really sleep or depend on wall time.
 */
object ActionGuard {

    /** Random source for the outward pacing gaps; bounds inclusive. Swap in tests. */
    @Volatile
    var randomSource: (Long, Long) -> Long = DefaultGuardRandom

    /** Sleeps the outward-pacing wait inside [beforeCall]. Swap in tests to record instead of blocking. */
    @Volatile
    var sleeper: (Long) -> Unit = DefaultGuardSleeper

    /** Milliseconds clock used to credit time already spent between outward actions. Swap in tests. */
    @Volatile
    var clock: () -> Long = DefaultGuardClock

    /** Table of screen-reading tool names: excluded from the Repeat signature window (they repeat by nature; the unchanged case feeds [Signal.Reread]). Add names here to configure. */
    val READ_TOOLS: Set<String> = setOf("ui_read", "screen_read", "screen_capture", "screenshot", "ui_dump", "screen_wait")

    /** Screen coordinates are snapped to this grid (px) so nearby taps compare as the same target. */
    const val COORD_TOLERANCE_PX = 8

    private val IGNORED_ARG_KEYS = setOf("durationMs", "timeoutMs")
    private val COORD_SCALAR_KEYS = setOf("x", "y", "x1", "y1", "x2", "y2", "left", "top", "right", "bottom", "centerx", "centery")
    private val COORD_PAIR_KEYS = setOf("point", "coordinate", "coords", "position", "center", "offset")

    private val guards = ConcurrentHashMap<String, LoopGuard>()
    private val pacing = OutwardPacing { lo, hi -> randomSource(lo, hi) }

    /**
     * Pure-ish pre-call verdict: [GuardVerdict.Refuse] (loop guard stop / would-be 12th identical
     * call) wins over pacing, then a due outward-pacing wait is slept here and returned as
     * [GuardVerdict.Wait], otherwise proceed (with a notice when the session is already stopped
     * and the call is a permitted read).
     */
    fun beforeCall(sessionId: String?, tool: String, argsJson: String): GuardVerdict {
        val guard = guards.getOrPut(sessionKey(sessionId)) { LoopGuard() }
        val args = parseArgs(argsJson)
        val read = isRead(tool)
        guard.stopped?.let { stopped ->
            return if (read && stopped != Signal.Reread) {
                GuardVerdict.ProceedWithNotice(stoppedNotice(stopped))
            } else {
                GuardVerdict.Refuse(LoopGuard.message(stopped, stopped.stopAt, stop = true))
            }
        }
        if (!read) {
            val refused = guard.check(signature(tool, args))
            if (refused != null) {
                return GuardVerdict.Refuse(LoopGuard.message(refused, refused.stopAt, stop = true))
            }
        }
        if (OutwardPacing.isOutward(tool, args)) {
            val millis = pacing.waitBefore(pacingKey(sessionId, args), clock())
            if (millis > 0) {
                sleeper(millis)
                return GuardVerdict.Wait(millis, waitNotice(millis))
            }
        }
        return GuardVerdict.Proceed
    }

    /**
     * Records a finished call (call this ONLY when the tool actually ran). Returns the reminder /
     * stop text to append to the tool result, or null when nothing was reached.
     *
     * @param ok        whether the tool call succeeded.
     * @param noEffect  the caller judged the call had no visible effect (e.g. the screen
     *                  fingerprint taken around a ui_action did not change).
     * @param unchanged the screen the call saw was identical to before (meaningful for reads,
     *                  where it feeds [Signal.Reread]; on an action it is treated as noEffect).
     */
    fun afterCall(
        sessionId: String?,
        tool: String,
        argsJson: String,
        ok: Boolean,
        noEffect: Boolean = false,
        unchanged: Boolean = false,
    ): String? {
        val guard = guards.getOrPut(sessionKey(sessionId)) { LoopGuard() }
        if (guard.stopped != null) return null
        val args = parseArgs(argsJson)
        if (ok && OutwardPacing.isOutward(tool, args)) {
            pacing.noteDone(pacingKey(sessionId, args), clock())
        }
        val outcome = when {
            !ok -> Outcome.None
            isRead(tool) -> if (unchanged) Outcome.ReadUnchanged else Outcome.ReadChanged
            unchanged || noEffect -> Outcome.NoEffect
            else -> Outcome.Effective
        }
        val hit = guard.record(signature(tool, args), ok, outcome) ?: return null
        return LoopGuard.message(hit.first, hit.second, stop = hit.first.stopAt <= hit.second)
    }

    /**
     * The user spoke (new turn or steering): the session's loop-guard state and its
     * session-scoped pacing history are cleared. App-scoped pacing keys survive.
     */
    fun onUserTurn(sessionId: String?) {
        val key = sessionKey(sessionId)
        guards.remove(key)
        pacing.reset(key)
    }

    /** Clears every session's guard and all pacing history (tests, process-wide resets). */
    fun resetAll() {
        guards.clear()
        pacing.resetAll()
    }

    /** True when [tool] only looks at the screen and is therefore exempt from the Repeat window. */
    fun isRead(tool: String): Boolean = tool.lowercase() in READ_TOOLS

    /** True when [tool]/its `text` argument matches the outward keyword table (see [OutwardPacing.KEYWORDS]). */
    fun isOutward(tool: String, argsJson: String): Boolean = OutwardPacing.isOutward(tool, parseArgs(argsJson))

    /**
     * Normalized repeat-detection signature: tool name + sorted, normalized arguments, so it is
     * order-independent, ignores volatile keys and snaps coordinates to an 8 px grid. Null for
     * read tools (they are covered by the unchanged-reread signal instead).
     */
    fun signature(tool: String, argsJson: String): String? = signature(tool, parseArgs(argsJson))

    fun signature(tool: String, args: JsonObject): String? {
        if (isRead(tool)) return null
        val body = args.entries
            .filter { it.key !in IGNORED_ARG_KEYS }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${normalizeValue(it.key, it.value)}" }
        return "$tool($body)"
    }

    private fun sessionKey(sessionId: String?): String =
        sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: "default"

    private fun pacingKey(sessionId: String?, args: JsonObject): String =
        OutwardPacing.appKey(args) ?: sessionKey(sessionId)

    private fun parseArgs(argsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())

    private fun normalizeValue(key: String, value: JsonElement): String = when (value) {
        is JsonPrimitive -> {
            val number = value.content.toDoubleOrNull()
            if (number != null && key.lowercase() in COORD_SCALAR_KEYS) roundToGrid(number).toString() else value.toString()
        }
        is JsonArray ->
            if (key.lowercase() in COORD_PAIR_KEYS && value.size == 2) {
                value.joinToString(",") { el ->
                    val number = (el as? JsonPrimitive)?.content?.toDoubleOrNull()
                    if (number != null) roundToGrid(number).toString() else el.toString()
                }
            } else {
                value.joinToString(",") { normalizeValue("", it) }
            }
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${normalizeValue(it.key, it.value)}" }
    }

    /** Snaps a screen coordinate to a [COORD_TOLERANCE_PX] px grid so nearby taps compare equal. */
    private fun roundToGrid(value: Double): Long =
        (value / COORD_TOLERANCE_PX).roundToInt().toLong() * COORD_TOLERANCE_PX

    private fun waitNotice(millis: Long): String =
        "[Outward pacing] Waited ${millis / 1000}s before this outward action to keep a human-like pace toward the target app; run it now."

    private fun stoppedNotice(stopped: Signal): String =
        "[Loop guard] This session is already stopped (${stopped.name.lowercase()}). Reads still work: look at what you need, then report to the user instead of acting."
}
