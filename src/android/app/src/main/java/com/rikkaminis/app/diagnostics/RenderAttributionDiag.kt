package com.rikkaminis.app.diagnostics

import com.rikkaminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * [diag/render-attribution] T3 attribution counters for the two 2026-09-20
 * regressions. Diagnostics only — no behaviour is changed by this file, and
 * the whole object is meant to be DELETED once the root causes are fixed
 * (see the T3 report §removal for the exact steps).
 *
 * Q1 — `[Perf][LongCtx] step=lazyColumn.firstItem.compose` fires every
 *      250-350ms (250-400ms each) with no attribution. ChatScreen feeds
 *      the newest row's type bucket / message prefix / char count /
 *      streaming flag into the existing step() extra AND into a per-type
 *      census emitted every [FLUSH_EVERY] events (same cadence idea as
 *      [RenderPathCensus]): `byType=mdblock:n=..,tot=..ms,max=..ms`.
 *
 * Q2 — the streaming auto-follow goes silent. `followState.isFollowing` is
 *      a required gate; once false the only rescue is the FAB (whose
 *      request is swallowed by decideBottomScroll's focus rule — T1's
 *      fix). Every `followReducer` dispatch at the ChatScreen call sites
 *      is reported here with before/after mode + the live gate inputs; the
 *      first `FOLLOWING->DETACHED` edge names the culprit event.
 *
 * Q3 — the SIMPLE_FOLLOW effect runs but declines to scroll. Each decline
 *      reports its gate name so the histogram says which gate owns the
 *      silence.
 *
 * Cost discipline (this repo has a precedent of per-frame logging becoming
 * the jank — see the isNearBottom `if (false)` log block): Q1's hot path is
 * a few array increments with no allocation and no lock; Q2/Q3 emit at most
 * one line per second per channel and are silent outside that window. All
 * human-readable strings are built after the throttle window admits the
 * event.
 *
 * ponytail: per-type buckets are a fixed-size IntArray indexed by a
 * caller-supplied [ItemType] ordinal rather than a map keyed by class name |
 * ceiling: a new FlatChatItem subtype not added to [ItemType] must reuse an
 * existing bucket (the ChatScreen mapping is exhaustive over the sealed
 * class, so a new subtype surfaces as a compile error there, not silently
 * here) | upgrade trigger: the census line disappears from the log, or a
 * fix lands and this file is deleted.
 */
object RenderAttributionDiag {

    private const val CATEGORY = "RenderDiag"

    /** Q1 census cadence (events per window). */
    internal const val FLUSH_EVERY = 50

    /** Minimum gap between two non-flip Q2 lines; Q3 shares the same value. */
    private const val LINE_MIN_GAP_MS = 1_000L

    /**
     * Throttle window in ms. Test seam, same spirit as `PerfLongCtx.sinkForTest`:
     * JVM tests set it to 0 (nothing suppressed) or a huge value (everything
     * after the first suppressed) to make window behaviour deterministic
     * without sleeping.
     */
    internal var minGapMs: Long = LINE_MIN_GAP_MS

    /**
     * Fixed buckets — one per [FlatChatItem] subtype in the aggregate
     * pipeline. Kept as ordinals so the hot path never touches a map or a
     * string.
     */
    enum class ItemType(val label: String) {
        USER("user"),
        HEADER("header"),
        MDBLOCK("mdblock"),
        THINKING("thinking"),
        TOOLRUN("toolrun"),
        INFO("info"),
        TYPING("typing"),
        ERROR("error"),
        MSGITEM("msgitem"),
        LEGACY("legacy"),
    }

    private val typeCount = IntArray(ItemType.entries.size)
    private val typeTotalMs = LongArray(ItemType.entries.size)
    private val typeMaxMs = LongArray(ItemType.entries.size)
    private val typeMaxChars = IntArray(ItemType.entries.size)
    private val typeStreaming = IntArray(ItemType.entries.size)
    private var windowEvents = 0
    private var lastComposeNs = 0L
    private var lastComposeSession = ""
    private var probeAnnounced = false

    private val dispatchLines = AtomicLong(0)
    private var lastDispatchMs = 0L

    /** Test seam, same shape as `PerfLongCtx.sinkForTest`. */
    internal var sinkForTest: ((String) -> Unit)? = null

    private fun emit(line: String) {
        sinkForTest?.let {
            it(line)
            return
        }
        AppLogger.info(CATEGORY, line)
    }

    /**
     * Q1 hot path, called from the newest-row compose SideEffect. Returns
     * the elapsed-ms it charged to the aggregate so tests can assert the
     * charge; production callers ignore it. Zero allocation.
     */
    fun recordNewestCompose(
        sessionId: String,
        type: ItemType,
        streaming: Boolean,
        chars: Int,
    ): Long {
        val now = System.nanoTime()
        val elapsedMs = if (sessionId == lastComposeSession && lastComposeNs != 0L) {
            (now - lastComposeNs) / 1_000_000L
        } else {
            -1L
        }
        lastComposeNs = now
        lastComposeSession = sessionId
        val i = type.ordinal
        typeCount[i]++
        if (elapsedMs >= 0L) {
            typeTotalMs[i] += elapsedMs
            if (elapsedMs > typeMaxMs[i]) typeMaxMs[i] = elapsedMs
        }
        if (streaming) typeStreaming[i]++
        if (chars > typeMaxChars[i]) typeMaxChars[i] = chars
        windowEvents++
        if (windowEvents >= FLUSH_EVERY) emitComposeCensus()
        return elapsedMs
    }

    /** One line per [FLUSH_EVERY] newest-row composes, then the window resets. */
    @Synchronized
    private fun emitComposeCensus() {
        if (windowEvents == 0) return
        val sb = StringBuilder(224)
        sb.append("[RenderDiag] compose-census window=").append(windowEvents).append(" session=")
            .append(SessionIdAliases.resolve(lastComposeSession))
            .append(" byType=")
        var any = false
        for (i in typeCount.indices) {
            if (typeCount[i] == 0) continue
            any = true
            sb.append(ItemType.entries[i].label)
                .append(":n=").append(typeCount[i])
                .append(",tot=").append(typeTotalMs[i])
                .append("ms,max=").append(typeMaxMs[i])
                .append("ms,maxChars=").append(typeMaxChars[i])
                .append(",streaming=").append(typeStreaming[i])
                .append(' ')
        }
        if (!any) sb.append("none ")
        emit(sb.toString())
        java.util.Arrays.fill(typeCount, 0)
        java.util.Arrays.fill(typeTotalMs, 0)
        java.util.Arrays.fill(typeMaxMs, 0)
        java.util.Arrays.fill(typeMaxChars, 0)
        java.util.Arrays.fill(typeStreaming, 0)
        windowEvents = 0
    }

    /**
     * Q2 — one line per `followReducer` dispatch. Mode flips are ALWAYS
     * reported (they are the answer to "who turned follow off"); identical
     * no-flip dispatches coalesce to at most one line per second.
     */
    @Synchronized
    fun recordFollowDispatch(
        sessionId: String,
        event: String,
        modeBefore: String,
        modeAfter: String,
        isStreaming: Boolean,
        isScrollInProgress: Boolean,
        isUserDragging: Boolean,
        sentinelVisible: Boolean,
        totalItems: Int,
        canScrollForward: Boolean,
    ) {
        val n = dispatchLines.incrementAndGet()
        val now = System.nanoTime() / 1_000_000L
        val flipped = modeBefore != modeAfter
        if (!flipped && lastDispatchMs != 0L && now - lastDispatchMs < minGapMs) return
        lastDispatchMs = now
        emit(
            "[RenderDiag] follow-dispatch #" + n +
                " session=" + SessionIdAliases.resolve(sessionId) +
                " event=" + event +
                " mode=" + modeBefore + "->" + modeAfter +
                " isStreaming=" + isStreaming +
                " isScrollInProgress=" + isScrollInProgress +
                " isUserDragging=" + isUserDragging +
                " sentinelVisible=" + sentinelVisible +
                " totalItems=" + totalItems +
                " canScrollForward=" + canScrollForward,
        )
    }

    /**
     * Q3 — the SIMPLE_FOLLOW effect (or one of the two auxiliary dispatch
     * paths) reached a decision point and declined to scroll. [reason] must
     * be one of the fixed tokens below; an unknown token still logs (into
     * the shared "other" slot) so a typo can never make a defect invisible.
     *
     * Throttling is PER REASON (1s), not global: a healthy turn emits
     * `clamp-not-released` about once a second, and a global window would
     * hide exactly the `not-following` line this probe exists to find.
     */
    @Synchronized
    fun recordFollowDecline(
        sessionId: String,
        reason: String,
        isStreaming: Boolean,
        isScrollInProgress: Boolean,
        isUserDragging: Boolean,
        sentinelVisible: Boolean,
        totalItems: Int,
        canScrollForward: Boolean,
        focusPending: Boolean,
    ) {
        val i = reasonIndex(reason)
        val now = System.nanoTime() / 1_000_000L
        if (reasonLastMs[i] != 0L && now - reasonLastMs[i] < minGapMs) {
            reasonDropped[i]++
            return
        }
        val dropped = reasonDropped[i]
        reasonDropped[i] = 0
        reasonLastMs[i] = now
        emit(
            "[RenderDiag] follow-decline reason=" + reason +
                " session=" + SessionIdAliases.resolve(sessionId) +
                " isStreaming=" + isStreaming +
                " isScrollInProgress=" + isScrollInProgress +
                " isUserDragging=" + isUserDragging +
                " sentinelVisible=" + sentinelVisible +
                " totalItems=" + totalItems +
                " canScrollForward=" + canScrollForward +
                " focusPending=" + focusPending +
                " suppressed=" + dropped,
        )
    }

    /** Fixed decline vocabulary; index [REASONS].size is the "other" slot. */
    private val REASONS = arrayOf(
        "scroll-in-progress",
        "not-streaming",
        "user-dragging",
        "not-following",
        "empty-list",
        "clamp-not-released",
        "no-target",
        "forceScroll-detached",
        "bottom-request-skip",
    )
    private val reasonLastMs = LongArray(REASONS.size + 1)
    private val reasonDropped = IntArray(REASONS.size + 1)

    private fun reasonIndex(reason: String): Int {
        for (i in REASONS.indices) if (REASONS[i] == reason) return i
        return REASONS.size
    }

    /** One-time liveness marker so a silent probe is distinguishable from a dead one. */
    @Synchronized
    fun markProbeLive() {
        if (probeAnnounced) return
        probeAnnounced = true
        emit("[RenderDiag] probe live (Q1 compose census + Q2 follow dispatch + Q3 follow decline)")
    }

    /** Test seam: drop all counters between cases. */
    @Synchronized
    fun resetForTest() {
        java.util.Arrays.fill(typeCount, 0)
        java.util.Arrays.fill(typeTotalMs, 0)
        java.util.Arrays.fill(typeMaxMs, 0)
        java.util.Arrays.fill(typeMaxChars, 0)
        java.util.Arrays.fill(typeStreaming, 0)
        windowEvents = 0
        lastComposeNs = 0L
        lastComposeSession = ""
        probeAnnounced = false
        lastDispatchMs = 0L
        dispatchLines.set(0)
        java.util.Arrays.fill(reasonLastMs, 0L)
        java.util.Arrays.fill(reasonDropped, 0)
    }

    /** Test seam: per-type counts in [ItemType] order. */
    @Synchronized
    fun countSnapshotForTest(): IntArray = typeCount.copyOf()
}
