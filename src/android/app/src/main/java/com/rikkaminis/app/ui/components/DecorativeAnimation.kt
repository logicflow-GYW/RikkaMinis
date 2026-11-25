package com.rikkaminis.app.ui.components

import androidx.annotation.MainThread
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Decorative work only: isolate its invalidations and read tick.value inside
 * graphicsLayer/draw, never composition. Scroll/drag/content transitions keep
 * Compose's normal frame clock. Adapted from OpenMinis' DecorativeAnimation.
 */
fun Modifier.animationLayer(): Modifier = graphicsLayer()

/** One frame-clock job for all visible decorative subscribers; none when idle. */
object DecorativeClock {
    const val INTERVAL_NS = 33_333_333L
    private val tick = mutableLongStateOf(0L)
    val nanos: State<Long> get() = tick
    // This dispatcher supplies the Choreographer frame clock; no new thread.
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private var job: Job? = null
    private var readers = 0
    val isRunning: Boolean get() = job != null

    @MainThread
    fun acquire() {
        if (readers++ != 0) return
        job = scope.launch {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (decorativeFrameDue(last, now)) {
                        last = now
                        tick.longValue = now
                    }
                }
            }
        }
    }

    @MainThread
    fun release() {
        if (readers == 0) return
        readers--
        if (readers == 0) {
            job?.cancel()
            job = null
        }
    }
}

fun decorativeFrameDue(last: Long, now: Long): Boolean =
    last == 0L || now - last >= DecorativeClock.INTERVAL_NS

/** Idempotent per-reader lease: START/STOP/dispose never double-release. */
class DecorativeClockLease(private val acquire: () -> Unit, private val release: () -> Unit) {
    private var held = false

    fun setStarted(started: Boolean) {
        if (started == held) return
        held = started
        if (started) acquire() else release()
    }

    fun dispose() = setStarted(false)
}

/** Subscribe only while this reader is STARTED, and always release on disposal. */
@Composable
fun rememberDecorativeTick(): State<Long> {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val lease = DecorativeClockLease(DecorativeClock::acquire, DecorativeClock::release)
        val observer = LifecycleEventObserver { _, _ ->
            lease.setStarted(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        owner.lifecycle.addObserver(observer)
        lease.setStarted(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            lease.dispose()
        }
    }
    return DecorativeClock.nanos
}

/** Cyclic [0, 1) phase, including negative offsets and float rounding at wrap. */
fun decorativePhase(nanos: Long, periodMs: Int, offsetMs: Int = 0): Float {
    if (periodMs <= 0) return 0f
    val periodNs = periodMs * 1_000_000L
    // Reduce first so even large times/offsets cannot overflow the subtraction.
    val shifted = nanos % periodNs - (offsetMs * 1_000_000L) % periodNs
    val m = shifted % periodNs
    val pos = if (m < 0) m + periodNs else m
    val phase = pos.toFloat() / periodNs
    return if (phase >= 1f) 0f else phase
}

/** Reverse repeat shape: 0 -> 1 -> 0 over one cycle. */
fun decorativePingPong(phase: Float): Float {
    val p = phase - floor(phase)
    return if (p < 0.5f) p * 2f else (1f - p) * 2f
}

/**
 * Shared-clock, isolated indeterminate arc. Keep indeterminate progress semantics
 * when replacing a Material spinner; real determinate progress stays Material.
 */
@Composable
fun DecorativeSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    sweepFraction: Float = 0.75f,
    periodMs: Int = 900,
) {
    val tick = rememberDecorativeTick()
    Canvas(
        modifier = modifier
            .progressSemantics()
            .graphicsLayer { rotationZ = decorativePhase(tick.value, periodMs) * 360f },
    ) {
        val px = strokeWidth.toPx()
        if (px <= 0f || size.width <= px || size.height <= px) return@Canvas
        val inset = px / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f * sweepFraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - px, size.height - px),
            style = Stroke(width = px, cap = StrokeCap.Round),
        )
    }
}
