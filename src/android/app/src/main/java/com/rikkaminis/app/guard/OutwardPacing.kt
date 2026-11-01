package com.rikkaminis.app.guard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Spacing of outward actions (send, submit, post, follow...) within one target app, so an
 * automated agent never fires machine-speed requests at an external service
 * (anti-risk-control). Semantics follow DeepseekHarness-Android's OutwardPacing:
 *
 *  - the first outward action for a key runs immediately (wait 0);
 *  - every later one waits the remainder of a random 8-20 s gap measured from the previous
 *    outward action's completion - the clock is passed in per call, so a slow tool call
 *    already eats into the gap;
 *  - the gap grows with the number of outward actions already recorded for the same key
 *    inside the last [WINDOW_MS] (lower bound +[STEP_LO_MS] ms, upper bound +[STEP_HI_MS] ms
 *    per earlier action) and never exceeds [MAX_GAP_MS];
 *  - this class only computes durations; [ActionGuard] performs the actual sleep through its
 *    injected sleeper before the tool runs and reports the wait to the model.
 *
 * Pure JVM: the random source is a constructor-injected `(lo, hi) -> Long` function with
 * INCLUSIVE bounds. Everything else is deterministic.
 */
class OutwardPacing(private val random: (Long, Long) -> Long) {

    private class Done(val key: String, val atMs: Long, val gapMs: Long)

    private val done = ArrayDeque<Done>()

    /** Milliseconds the next outward action for [key] must still wait at [nowMs]; 0 = run now. */
    @Synchronized
    fun waitBefore(key: String, nowMs: Long): Long {
        prune(nowMs)
        val last = done.lastOrNull { it.key == key } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, MAX_GAP_MS)
    }

    /** Records one completed outward action for [key] and picks the gap the NEXT one must keep. */
    @Synchronized
    fun noteDone(key: String, nowMs: Long) {
        prune(nowMs)
        val extra = done.count { it.key == key }.toLong()
        val lo = (FIRST_GAP_MS + extra * STEP_LO_MS).coerceAtMost(MAX_GAP_MS)
        val hi = (LAST_GAP_MS + extra * STEP_HI_MS).coerceAtMost(MAX_GAP_MS)
        done.addLast(Done(key, nowMs, if (lo >= hi) hi else random(lo, hi)))
    }

    /** Clears the history of one key (the session spoke: see [ActionGuard.onUserTurn]). */
    @Synchronized
    fun reset(key: String) {
        done.removeAll { it.key == key }
    }

    /** Clears everything (tests, process-wide resets). */
    @Synchronized
    fun resetAll() {
        done.clear()
    }

    private fun prune(nowMs: Long) {
        while (done.isNotEmpty() && nowMs - done.first().atMs > WINDOW_MS) done.removeFirst()
    }

    companion object {
        /** Random wait range (ms) before the second outward action of one key. */
        const val FIRST_GAP_MS = 8_000L
        const val LAST_GAP_MS = 20_000L

        /** Upper bound (ms) no matter how many outward actions piled up. */
        const val MAX_GAP_MS = 30_000L

        /** Per earlier outward action of the same key: lower bound grows by this, upper by [STEP_HI_MS]. */
        const val STEP_LO_MS = 500L
        const val STEP_HI_MS = 1_000L

        /** Only outward actions within this window (ms) raise the gap and count as "same app". */
        const val WINDOW_MS = 30 * 60_000L

        /**
         * The configurable outward-keyword table. A call is "outward" when any keyword appears
         * (case-insensitive substring) in the tool name or in the `text` argument - the
         * natural-language payload ui_action types into an app: send / submit / post / reply /
         * publish / comment / follow and their Chinese counterparts. Add entries here to
         * configure; keeping it a substring match is deliberate (covers "resend", "提交表单"...).
         */
        val KEYWORDS: List<String> = listOf(
            "send", "submit", "post", "reply", "publish", "comment", "follow",
            "发送", "提交", "发布", "评论", "回复", "关注", "打招呼", "投递",
        )

        /** Args fields (first present wins) whose string value names the target app for pacing. */
        val APP_KEYS = listOf("package", "packageName", "appId", "app", "targetApp")

        /** True when [tool] or its `text` argument matches the outward keyword table. */
        fun isOutward(tool: String, args: JsonObject): Boolean {
            val toolName = tool.lowercase()
            val text = (args["text"] as? JsonPrimitive)?.content?.lowercase().orEmpty()
            return KEYWORDS.any { toolName.contains(it) || text.contains(it) }
        }

        /** Target-app pacing key taken from the args, or null when the caller falls back to the session key. */
        fun appKey(args: JsonObject): String? =
            APP_KEYS.firstNotNullOfOrNull { key ->
                (args[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            }
    }
}
