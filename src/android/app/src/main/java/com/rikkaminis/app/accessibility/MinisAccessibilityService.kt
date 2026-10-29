package com.rikkaminis.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.rikkaminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * MinisAccessibilityService — host-side AccessibilityService backing the
 * `android-a11y-cli` offload command. The user must enable it manually
 * under Settings → Accessibility; we never start it programmatically.
 */

/**
 * Bounded FIFO ring with O(1) size bookkeeping.
 *
 * [sweep1014-P4b] The previous implementation drove eviction with
 * `ConcurrentLinkedQueue.size` — an O(N) full traversal, run twice per
 * accessibility event on the system main thread with a 1024-node ring (an
 * event storm paid N queue-node visits per event). An AtomicInteger counter
 * maintained on offer/poll keeps the same cap semantics at O(1) per event:
 * over capacity drops the OLDEST item (FIFO), snapshot order is unchanged.
 * Offer and eviction run on the service's main thread; the counter is atomic
 * anyway so `snapshot`/`size` from other threads always agree with the queue.
 * JVM-pure and generic so the cap behavior is unit-testable without Android.
 */
private class BoundedEventRing<T>(private val cap: Int) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val count = AtomicInteger(0)

    fun offer(item: T) {
        queue.offer(item)
        if (count.incrementAndGet() <= cap) return
        // Over capacity: drop the oldest. Each offer adds exactly one item, so
        // one poll per over-capacity offer restores the invariant; the loop is
        // defensive (e.g. against a future batched caller).
        while (count.get() > cap) {
            if (queue.poll() != null) count.decrementAndGet() else break
        }
    }

    fun snapshot(): List<T> = queue.toList()

    fun size(): Int = count.get()

    fun clear() {
        queue.clear()
        count.set(0)
    }
}

/**
 * [T-android-a11y-late-screenshot] Hand-off between a blocking caller that may
 * give up and a producer whose answer may arrive after it did.
 *
 * Two mechanisms, because they close different windows:
 *  - [isCallerWaiting] is a cheap peek the producer consults *before* it spends
 *    its payload (a full-screen bitmap copy is ~10–15 MB, and skipping that is
 *    the point);
 *  - the slot itself is the mutex: [exit] swaps `null` in, so a producer that
 *    already passed the peek still loses the race — its [publish] sees a null
 *    predecessor and discards its own payload instead of parking it where
 *    nothing will ever read (and therefore never recycle) it.
 *
 * Exactly one side owns any given payload: after [exit] the caller owns what it
 * took, [onDiscard] receives everything else, and a payload is never delivered
 * to both. [fallback] is the caller's own pre-made answer; it is never handed to
 * [onDiscard] (only real payloads are).
 *
 * Generic and Android-free so the arbitration itself is unit-testable.
 */
internal class LateValueSlot<T : Any>(private val fallback: T, private val onDiscard: (T) -> Unit) {
    private val ref = AtomicReference<T?>(fallback)

    /** True while the caller would still read a published value (it has not exited). */
    fun isCallerWaiting(): Boolean = ref.get() != null

    /** Current value, or `null` once the slot is closed. */
    fun current(): T? = ref.get()

    /**
     * Producer side. Returns true when the value was retained for the caller;
     * false when the caller had already exited — the value then goes to
     * [onDiscard] and must not be used again.
     *
     * A refused publish leaves the slot closed: the swap has already written the
     * value, so the sentinel goes back (CAS, so a concurrent publish of its own
     * value is not clobbered). Otherwise the payload would stay reachable in a
     * slot nobody reads — the very leak this class exists to prevent.
     */
    fun publish(value: T): Boolean {
        val displaced = ref.getAndSet(value)
        if (displaced == null) {
            ref.compareAndSet(value, null)
            onDiscard(value)
            return false
        }
        // A second publish displaces a payload that no longer has an owner.
        if (displaced !== fallback) onDiscard(displaced)
        return true
    }

    /**
     * Caller side, once it stops reading. [taken] is the value it keeps (the
     * caller's fallback when it gave up); anything else still in the slot is
     * discarded. Returns whatever the slot held.
     */
    fun exit(taken: T?): T? {
        val leftover = ref.getAndSet(null)
        if (leftover != null && leftover !== fallback && leftover !== taken) onDiscard(leftover)
        return leftover
    }
}

class MinisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MinisA11y"
        const val SERVICE_ID = "com.rikkaminis.app/.accessibility.MinisAccessibilityService"
        private const val EVENT_RING_CAP = 1024

        @Volatile
        private var _instance: MinisAccessibilityService? = null

        fun getInstance(): MinisAccessibilityService? = _instance
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
    )

    private val eventRing = BoundedEventRing<RecordedEvent>(EVENT_RING_CAP)
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        AppLogger.info(TAG, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // [T-android-a11y-force-stop-recovery] Latch "the grant has existed",
        // whichever route the user took (Settings toggle, Shizuku repair, or a
        // restore). This is the one moment we know the grant is real, and it
        // is what lets AccessibilityRecoveryManager tell "revoked after a
        // force-stop" apart from "never set up" — only the former should ever
        // raise a repair prompt.
        AccessibilityRecoveryManager.markGranted(this)
        // [T-android-a11y-miui-service-failure] Breadcrumb on (re)bind. On OEM
        // ROMs (MIUI etc.) the system kills + rebinds this service repeatedly,
        // surfacing "此服务出现故障"; a connect log lets us correlate a degraded
        // window in stall/diag logs with a kill/rebind. No self-restart logic —
        // remediation is the user whitelisting autostart + battery (see
        // SystemPermissionsScreen OEM guidance).
        AppLogger.info(TAG, "service connected (manufacturer=${android.os.Build.MANUFACTURER})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rec = RecordedEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis(),
        )
        eventRing.offer(rec)
        for (listener in eventListeners) {
            try { listener(rec) } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {
        AppLogger.info(TAG, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        AppLogger.info(TAG, "service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.snapshot()

    fun addEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun rootNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows ?: emptyList()) {
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        if (out.isEmpty()) {
            try { rootInActiveWindow?.let { out.add(it) } } catch (_: Throwable) {}
        }
        return out
    }

    fun windowInfos(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        try {
            for (w in windows ?: emptyList()) {
                out.add(mapOf(
                    "windowId" to w.id,
                    "type" to when (w.type) {
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                        else -> "other"
                    },
                    "title" to (w.title?.toString() ?: ""),
                    "focused" to w.isFocused,
                    "active" to w.isActive,
                ))
            }
        } catch (_: Throwable) {}
        return out
    }

    fun foregroundPackage(): Pair<String?, String?> {
        return try {
            val r = rootInActiveWindow
            (r?.packageName?.toString()) to (r?.className?.toString())
        } catch (_: Throwable) { null to null }
    }

    fun dispatchSimpleGesture(path: Path, startTime: Long, durationMs: Long, timeoutMs: Long = 5000): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
        }
        Handler(Looper.getMainLooper()).post {
            // [r3-B11-F10] A rejected dispatch fires NO callback at all, so the
            // await below used to block the full timeout and then report
            // failure for a gesture the system never started.
            if (!dispatchGesture(gesture, callback, null)) done.countDown()
        }
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && ok[0]
    }

    fun dispatchPinch(cx: Float, cy: Float, scale: Float, durationMs: Long): Boolean {
        // [r3-B11-F10] Geometry comes from [PinchTracks]: the old inline version
        // multiplied the offset by `scale` AND then swapped start/end for
        // `scale > 1f`, so a zoom-in request performed a zoom-out (fingers were
        // always travelling towards the centre), and a small `cx` produced
        // negative stroke coordinates, which GestureDescription rejects with
        // IllegalArgumentException.
        val screen = resources.displayMetrics
        val track = PinchTracks.forPinch(
            cx = cx,
            cy = cy,
            scale = scale,
            screenWidth = screen.widthPixels.toFloat(),
            screenHeight = screen.heightPixels.toFloat(),
        )
        val p0 = Path().apply {
            moveTo(track.leftStartX, track.startY); lineTo(track.leftEndX, track.endY)
        }
        val p1 = Path().apply {
            moveTo(track.rightStartX, track.startY); lineTo(track.rightEndX, track.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p0, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .build()
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        Handler(Looper.getMainLooper()).post {
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
                override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
            }
            // [r3-B11-F10] A rejected dispatch fires no callback; without this
            // the latch waited durationMs + 2 s for one that could never come.
            if (!dispatchGesture(gesture, callback, null)) done.countDown()
        }
        return done.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS) && ok[0]
    }

    /**
     * Result of [captureScreenshot]. On success [bitmap] is non-null and
     * caller is responsible for `bitmap.recycle()`.
     */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Synchronously captures a system-wide screenshot via
     * `AccessibilityService.takeScreenshot` (API 30+). Blocks the calling
     * thread up to [timeoutMs]. Caller must check `Build.VERSION.SDK_INT`
     * before invoking — annotated `@RequiresApi(R)`.
     *
     * [T-android-a11y-late-screenshot] The callback is delivered by the system
     * whenever it finishes, which can be *after* [timeoutMs] — the platform
     * gives no way to unregister it. Each such late callback used to copy a
     * full-screen bitmap (~10–15 MB) into a reference nobody would ever read
     * again, so every timed-out capture leaked one screen-sized allocation for
     * the life of the process. The hand-off now goes through [LateValueSlot]:
     * the callback checks [LateValueSlot.isCallerWaiting] before allocating and
     * publishes atomically, and this side closes the slot (recycling anything
     * already published) before it returns.
     *
     * Consequence, deliberately: a screenshot that lands after the deadline is
     * discarded rather than returned, so a timeout always reports TIMEOUT
     * instead of sometimes succeeding with a late frame.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val timedOut = ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        val slot = LateValueSlot(timedOut) { late -> late.bitmap?.recycle() }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var result: ShotResult
        try {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        if (!slot.isCallerWaiting()) {
                            // Caller already returned: release the buffer and skip
                            // the whole-screen copy that nobody owns.
                            try { hwBuffer.close() } catch (_: Throwable) {}
                            return
                        }
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try { hwBuffer.close() } catch (_: Throwable) {}
                        if (bmp == null) {
                            slot.publish(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            // Copy to a software bitmap so we can encode + scale freely.
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            slot.publish(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        slot.publish(ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    slot.publish(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLogger.warning(TAG, "takeScreenshot timed out after ${timeoutMs}ms")
                // Nobody will read the slot: reclaim whatever a late callback left.
                slot.exit(timedOut)
                return timedOut
            }
            result = slot.current() ?: timedOut
        } catch (t: Throwable) {
            slot.publish(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
            result = slot.current() ?: timedOut
        } finally {
            executor.shutdown()
        }
        // Hand-off complete: close the slot so a callback still in flight discards
        // its own bitmap instead of parking it here.
        slot.exit(result)
        return result
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

/**
 * [r3-B11-F10] Fingertip tracks for [MinisAccessibilityService.dispatchPinch].
 *
 * Two-finger pinch geometry, expressed as numbers only so it is JVM-testable
 * (the service itself cannot be constructed off-device).
 *
 * The contract callers expect: **`scale > 1` spreads the fingers apart (zoom
 * in), `scale < 1` brings them together (zoom out)**, with the spread growing
 * linearly from [INITIAL_OFFSET] to `INITIAL_OFFSET * scale` (floored at
 * [MIN_FINAL_OFFSET]).
 *
 * [r3-B11-F10] The service used to compute `finalOffset = initialOffset * scale`
 * and THEN swap the start/end pair when `scale > 1f` — a double inversion that
 * made every pinch (in or out) converge on the centre, i.e. `--scale 2.0` zoomed
 * OUT. It also built raw coordinates: with the common 720 px-wide screen and a
 * centre at 360 px, `cx - 400 = -40` made `StrokeDescription` throw
 * `IllegalArgumentException` instead of dispatching.
 */
internal data class PinchTrack(
    val leftStartX: Float,
    val leftEndX: Float,
    val rightStartX: Float,
    val rightEndX: Float,
    val startY: Float,
    val endY: Float,
)

internal object PinchTracks {

    /** Half-distance between the fingers when the gesture starts. */
    const val INITIAL_OFFSET: Float = 200f

    /**
     * Floor for the finishing distance: a stroke shorter than this is below
     * what the gesture pipeline reliably recognises as a two-finger pinch.
     */
    const val MIN_FINAL_OFFSET: Float = 20f

    /** Scale outside this range is clamped — beyond it the track is unusable
     *  (the fingers would start off-screen or collapse to a single point). */
    const val MIN_SCALE: Float = 0.05f
    const val MAX_SCALE: Float = 20f

    fun forPinch(
        cx: Float,
        cy: Float,
        scale: Float,
        screenWidth: Float,
        screenHeight: Float,
    ): PinchTrack {
        val clampedScale = if (scale.isNaN()) 1f else scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val initial = INITIAL_OFFSET
        val finalOffset = (initial * clampedScale).coerceAtLeast(MIN_FINAL_OFFSET)
        // Direction falls out of the arithmetic: finalOffset > initial means the
        // fingers move away from the centre. No branch, no second inversion.
        val y = cy.coerceIn(0f, screenHeight)
        return PinchTrack(
            leftStartX = (cx - initial).coerceIn(0f, screenWidth),
            leftEndX = (cx - finalOffset).coerceIn(0f, screenWidth),
            rightStartX = (cx + initial).coerceIn(0f, screenWidth),
            rightEndX = (cx + finalOffset).coerceIn(0f, screenWidth),
            startY = y,
            endY = y,
        )
    }
}
