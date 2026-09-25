package com.rikkaminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .padding(top = (-offset).dp.coerceAtLeast(0.dp))
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    val infiniteTransition = rememberInfiniteTransition(label = "streamDots")
    Row {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sdot_$i",
            )
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.offset(y = offset.dp),
            )
        }
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

// [fix/typing-band-live] Fixed-height band the typing indicator occupies.
// The transcript-bottom band item (ChatScreen) and the in-message render
// sites both get a stable 36dp footprint, so the indicator appearing or its
// text changing never shifts the layout. Plain val: Kotlin const val
// rejects Dp.
internal val TYPING_BAND_HEIGHT = 36.dp

@Composable
internal fun TypingIndicator(queueWaitingAhead: Int = -1, awaitingNetworkSinceMs: Long = 0L) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (MinisApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by com.rikkaminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { com.rikkaminis.app.agent.SoulMetadata.DEFAULT.name }

    // [feat/provider-exec-concurrency] While this session's request waits for
    // a provider execution slot (another session holds the pool), show the
    // queue position instead of the plain thinking dots — the difference
    // between "the model is slow" and "you are queued" is exactly what users
    // could not tell during the serialized-mutex era.
    val queued = queueWaitingAhead > 0

    // [fix/zero-chunk-cancel] The second "not really thinking" state. The
    // queue case above distinguishes "you are queued" from "the model is slow";
    // this one distinguishes "the request is out and the network has gone
    // quiet" from "the model is thinking". On-device 2026-09-21 the two were
    // indistinguishable: a wedged proxy held a 255 KB request for 60 s and the
    // UI showed the same three dots the whole time, so the user could not tell
    // a stall from a long think (they discovered it only by switching proxies).
    //
    // Clock ticks only while the message is actually in the awaiting state, so
    // a finished message renders nothing extra and there is no background wake.
    // Starts counting at the first frame the awaiting flag is seen, which is
    // the moment the request was dispatched.
    var elapsedSec by remember(awaitingNetworkSinceMs) { mutableStateOf(0L) }
    LaunchedEffect(awaitingNetworkSinceMs) {
        if (awaitingNetworkSinceMs <= 0L) { elapsedSec = 0L; return@LaunchedEffect }
        while (true) {
            elapsedSec = (System.currentTimeMillis() - awaitingNetworkSinceMs) / 1000L
            delay(1000L)
        }
    }
    val waitingNetwork = awaitingNetworkSinceMs > 0L

    // [fix/typing-band-live] The content Row is wrapped in a fixed-height
    // band (bottom-aligned, matching the chat semantics of the bouncing dots
    // sitting on the baseline). Every render site therefore occupies the same
    // stable footprint whether the indicator is showing or the band is blank.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TYPING_BAND_HEIGHT),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = if (queued) {
                    stringResource(R.string.chat_queued_indicator, queueWaitingAhead)
                } else if (waitingNetwork) {
                    stringResource(R.string.chat_waiting_network, elapsedSec)
                } else {
                    stringResource(R.string.chat_typing_indicator, soulName)
                },
                fontSize = 15.sp,
                color = ChatColors.tertiaryText,
            )
            // Animated bouncing dots
            val dots = listOf(".", ".", ".")
            dots.forEachIndexed { index, dot ->
                val offsetY by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = -6f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(400, delayMillis = index * 150, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot_bounce_$index",
                )
                Text(
                    text = dot,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChatColors.tertiaryText,
                    modifier = Modifier.graphicsLayer { translationY = offsetY },
                )
            }
        }
    }
}
