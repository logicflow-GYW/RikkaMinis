package com.rikkaminis.app.ui.media

import com.rikkaminis.app.R
import com.rikkaminis.app.logging.AppLogger
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.roundToInt

// [rv1014-E4] Upper bound for the auto-sized inline video height — portrait
// videos shouldn't dominate the chat viewport. Density-independent successor
// of the old coerceIn(100, 800) px bound in the layoutParams write (which
// never took effect — see the setOnPreparedListener comment in
// InlineVideoPlayer).
private val MAX_INLINE_VIDEO_HEIGHT = 480.dp

/**
 * [dv19-B02 / dv19-B06] What identifies a media host's data source.
 *
 * Every native media host in this family keys BOTH its view (`key(...)` around
 * the `AndroidView`) and its playback/progress state (`remember(...)`) on one
 * of these values. When the two disagree, the view keeps rendering the old
 * media while its state has already reset — the stale-video / frozen-240dp /
 * un-hidden-MediaController family. They are plain functions here so the
 * choice is a testable decision instead of an inline expression that four
 * hosts can drift apart on:
 *
 *  - [inlineMediaSourceKey]: an inline video card is handed whatever the
 *    message carried — a host path OR a `content://` uri (VideoView accepts
 *    both) — so the string itself is the identity, no normalisation;
 *  - [resolvedMediaSourceKey]: the fullscreen dialog and the markdown audio
 *    renderers work off a resolved host [File], which is `null` until the
 *    async resolver lands. The `null` → path transition is what re-runs their
 *    `remember`/`LaunchedEffect` exactly once, so the key must stay the
 *    absolute path (not `File.name`, which collides across directories).
 */
internal fun inlineMediaSourceKey(filePath: String): String = filePath

internal fun resolvedMediaSourceKey(file: File?): String? = file?.absolutePath

/**
 * Inline audio player composable for embedding in chat messages.
 * Shows play/pause/stop controls and a progress bar.
 */
@Composable
fun InlineAudioPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // [dv19-B06] Transport/progress state carries the file key, exactly like
    // `error` / `prepared` / `pendingPlay` / `mediaPlayer` below. This
    // composable is reused across chat message slots, so the unkeyed
    // isPlaying / progress / duration / position kept the PREVIOUS file's
    // play state and timestamps on screen until the new player's first
    // callback landed (and an unkeyed isPlaying could even render the Stop
    // button for a file that was never started).
    var isPlaying by remember(filePath) { mutableStateOf(false) }
    var progress by remember(filePath) { mutableFloatStateOf(0f) }
    var duration by remember(filePath) { mutableStateOf("0:00") }
    var position by remember(filePath) { mutableStateOf("0:00") }
    // [da1033-B] Keyed on filePath like prepared/pendingPlay/mediaPlayer below:
    // this composable is reused across chat message slots, so an unkeyed error
    // kept the previous file's message and replaced the new player's whole
    // button area with stale "Audio error: …" text.
    var error by remember(filePath) { mutableStateOf<String?>(null) }
    // [sweep1014-B33] prepareAsync() means there is a short window where the
    // player is still Preparing and start() would throw. A tap landing in that
    // window is queued here and drained by the prepared listener, so a tap
    // still starts playback exactly like the old blocking prepare() did.
    var prepared by remember(filePath) { mutableStateOf(false) }
    var pendingPlay by remember(filePath) { mutableStateOf(false) }

    val mediaPlayer = remember(filePath) { MediaPlayer() }

    DisposableEffect(filePath) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                mediaPlayer.setDataSource(file.absolutePath)
            } else {
                // Try as content URI
                mediaPlayer.setDataSource(context, Uri.parse(filePath))
            }
            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                progress = 0f
                position = "0:00"
            }
            // [sweep1014-B33] Duration is only known once the player is
            // prepared, and preparing must not block the main thread: the old
            // synchronous prepare() stalled composition until the decoder had
            // opened the track (and an unreadable/corrupt source blocked the
            // UI thread for the whole timeout). prepareAsync() + this listener
            // keeps the very same observable behaviour, just off the UI thread.
            mediaPlayer.setOnPreparedListener { mp ->
                duration = formatDuration(mp.duration)
                prepared = true
                if (pendingPlay) {
                    pendingPlay = false
                    try {
                        mp.start()
                        isPlaying = true
                    } catch (_: Exception) {}
                }
            }
            // [audit-0909 T10-M6] Surface playback errors explicitly. Without
            // this the player sat in the Error state while the UI still showed
            // an enabled Play button (the ticker's try-catch only hid the
            // failure), and the next start()/pause() threw
            // IllegalStateException and crashed the app.
            mediaPlayer.setOnErrorListener { _, what, extra ->
                isPlaying = false
                error = "MediaPlayer error ($what/$extra)"
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            error = e.message
        }

        onDispose {
            try {
                // A player still Preparing (prepareAsync pending) can't be
                // stopped — release() alone is the terminating path; calling
                // stop() there throws IllegalStateException, which onDispose
                // would swallow but leave the player unreleased.
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
            } catch (_: Exception) {}
            try {
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            try {
                val pos = mediaPlayer.currentPosition
                val dur = mediaPlayer.duration.coerceAtLeast(1)
                progress = pos.toFloat() / dur
                position = formatDuration(pos)
            } catch (_: Exception) {}
            delay(250)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (error != null) {
            Text(
                "Audio error: $error",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        } else {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Play/Pause button
                    IconButton(
                        onClick = {
                            // [audit-0909 T10-M6] A player that hit an error
                            // (truncated / corrupt TTS output) is in the Error
                            // state — pause()/start() then throw
                            // IllegalStateException, which crashed the app
                            // because this callback had no guard.
                            // [sweep1014-B33] Still-Preparing taps are queued
                            // (see pendingPlay) rather than letting start()
                            // throw inside the (sub-second) prepare window.
                            if (!prepared) {
                                pendingPlay = true
                            } else {
                                try {
                                    if (isPlaying) {
                                        mediaPlayer.pause()
                                        isPlaying = false
                                    } else {
                                        mediaPlayer.start()
                                        isPlaying = true
                                    }
                                } catch (e: Exception) {
                                    isPlaying = false
                                    error = e.message ?: "Playback failed"
                                }
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(position, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(duration, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Stop button
                    if (isPlaying) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                // [da1033-B] Same guard as the play/pause button
                                // above. The native player can already sit in
                                // the Error state while this frame still renders
                                // Stop: isPlaying / error arrive through the
                                // main-thread callback queue, so this click can
                                // be handled first and seekTo()/pause() then
                                // throw IllegalStateException — which crashed
                                // the app because this callback had no guard.
                                // [sweep1014-B33] !prepared is the still-
                                // Preparing window (prepareAsync pending).
                                if (!prepared) return@IconButton
                                try {
                                    mediaPlayer.seekTo(0)
                                    mediaPlayer.pause()
                                    isPlaying = false
                                    progress = 0f
                                    position = "0:00"
                                } catch (e: Exception) {
                                    isPlaying = false
                                    error = e.message ?: "Playback failed"
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.browser_stop),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // File name
                val fileName = filePath.substringAfterLast("/")
                Text(
                    fileName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Inline video player composable. On Android without ExoPlayer,
 * this uses a VideoView via AndroidView.
 */
@Composable
fun InlineVideoPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // [sweep1014-B33] Hoisted out of the AndroidView factory so the release
    // hook can reach it. Before, the controller was created inline and there
    // was no release hook at all: scrolling the message out of view left the
    // VideoView playing off-screen and the controller's window attached
    // (WindowLeaked).
    val mediaController = remember(filePath) { android.widget.MediaController(context) }
    // [rv1014-E4] Video aspect ratio, learned at onPrepared from the player's
    // own videoWidth/videoHeight — intrinsic video dimensions, valid there
    // regardless of view measurement. Null until prepared → 240dp placeholder.
    var videoAspectRatio by remember(filePath) { mutableStateOf<Float?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // [dv19-B02] The host View is keyed on the data source. Without it the
        // AndroidView factory ran only for the first filePath: a reused slot
        // (FilePreviewScreen renders every attachment into the same
        // composition slot) kept playing the PREVIOUS video, `videoAspectRatio`
        // had already reset to null so the height stayed at the 240dp
        // placeholder, and the old VideoView held the old MediaController whose
        // window nobody hid — the very WindowLeaked shape the release hook
        // below was written for. mediaController and videoAspectRatio are keyed
        // on the same expression, so view and state now move together.
        key(inlineMediaSourceKey(filePath)) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        val file = File(filePath)
                        val uri = if (file.exists()) Uri.fromFile(file) else Uri.parse(filePath)
                        setVideoURI(uri)

                        // Add basic media controller
                        mediaController.setAnchorView(this)
                        setMediaController(mediaController)

                        setOnPreparedListener { mp ->
                            // Auto-size to video dimensions. [rv1014-E4] The previous
                            // fix computed the height inside a `layoutParams.apply{}` —
                            // Kotlin resolves that `width` to LayoutParams.width
                            // (MATCH_PARENT here), not the measured view width, and the
                            // fixed .height(240.dp) constraint makes Compose's measure
                            // policy coerce the child LP on every measure
                            // (AndroidViewHolder.obtainMeasureSpec: min == max →
                            // EXACTLY), so neither the old nor the new write ever
                            // changed the displayed height (always the 240dp
                            // placeholder). mp.videoWidth/videoHeight are the intrinsic
                            // video dimensions — valid right here, regardless of view
                            // measurement — so hand the ratio to Compose and let
                            // aspectRatio derive the height from the actually-measured
                            // width.
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                videoAspectRatio = mp.videoWidth.toFloat() / mp.videoHeight
                            }
                            // Auto-play and surface the controller so the user sees
                            // a live first frame instead of a black surface waiting
                            // for an extra tap.
                            start()
                            mediaController.show(0)
                        }
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.w("InlineVideoPlayer",
                                "VideoView error what=$what extra=$extra path=$filePath")
                            false
                        }
                    }
                },
                // [sweep1014-B33] Terminating path for the inline player: stop the
                // track and detach the controller window when the composable
                // leaves composition (scroll-out, session switch, navigation).
                onRelease = { view ->
                    mediaController.hide()
                    view.stopPlayback()
                },
                // [rv1014-E4] With the aspect ratio known, Compose derives the
                // height from the measured width (capped so portrait videos don't
                // dominate the viewport); before the player reports dimensions,
                // keep the 240dp placeholder the layout always showed.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        videoAspectRatio?.let { ratio ->
                            Modifier.aspectRatio(ratio)
                                .heightIn(max = MAX_INLINE_VIDEO_HEIGHT)
                        } ?: Modifier.height(240.dp)
                    ),
            )
        }
    }
}

private fun formatDuration(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/**
 * Fullscreen video player dialog. Matches iOS `MinisVideoFullscreenPlayer`:
 * - Black background, video centered via VideoView
 * - Top bar: close (X) + share action
 * - Bottom bar: play/pause + current time + slider + duration
 * - Tap the video to toggle controls (auto-hide after 3s)
 * - Drag down to dismiss with rubber-band feedback
 *
 * Uses AndroidView(VideoView) to avoid ExoPlayer dependency.
 */
@Composable
fun MinisFullscreenVideoPlayer(
    file: File,
    onDismiss: () -> Unit,
) {
    val hostView = LocalView.current
    // [dv17-b08-host-window-bars] The Dialog below owns its own window, but
    // while it covers the screen the HOST activity window still owns the
    // system-bar visibility/appearance the system falls back to (the inner
    // effect in FullscreenVideoContent only ever sees the Dialog's window via
    // findDialogWindow). Same save-hide-restore pair as the three sibling
    // components that got this right — ImageGalleryViewer, the WebPreview
    // fullscreen screen and the WebPreview sheet — so a swipe-revealed
    // transient bar paints over our black background instead of the host's
    // default chrome, and the host is handed back exactly as we found it.
    DisposableEffect(hostView) {
        val window = (hostView.context as? android.app.Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, hostView)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            // Dark-background immersive: white icons if the bars ever peek in.
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        FullscreenVideoContent(file = file, onDismiss = onDismiss)
    }
}

@Composable
private fun FullscreenVideoContent(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    // Immersive system bars for the lifetime of this player. Mirrors iOS's
    // fullScreenCover which hides the status bar. Restores the bars on dispose
    // so the chat view comes back with its normal chrome.
    DisposableEffect(view) {
        val window = findDialogWindow(view)
        var originalLightStatusBars: Boolean? = null
        var originalLightNavigationBars: Boolean? = null
        if (window != null) {
            // Draw behind the system bars so our black background reaches the
            // top/bottom edges; the controller then hides them.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            // Size the Dialog window to the full display so nothing from the
            // underlying Activity (top bar, message list) peeks through at the
            // top edge. Without MATCH_PARENT + layoutInDisplayCutoutMode the
            // Dialog respects system-bar insets and leaves a gap.
            try {
                val attrs = window.attributes
                attrs.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                attrs.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    attrs.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = attrs
                window.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                )
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                // [dv17-b08-dialog-flags] Tell the system THIS window paints
                // the system-bar backgrounds itself. Without the flag Android
                // ignores statusBarColor/navigationBarColor below and fills the
                // swipe-revealed bar region with its own opaque default — a
                // grey band under the status bar (same contract as
                // WebPreviewFullscreenScreen.kt / ImageGalleryViewer.kt).
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            } catch (_: Throwable) {}
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            originalLightStatusBars = controller.isAppearanceLightStatusBars
            originalLightNavigationBars = controller.isAppearanceLightNavigationBars
            // Dark-background immersive: white icons if they ever peek in.
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                originalLightStatusBars?.let { controller.isAppearanceLightStatusBars = it }
                originalLightNavigationBars?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }

    // [dv19-B02] Every one of these player states carries the same
    // data-source key as the AndroidView below. The dialog content can be
    // reused with a different `file` (a preview slot / deep link that swaps
    // the target while the player stays open), and the unkeyed states then
    // described the PREVIOUS file: the new VideoView was polled with a stale
    // isPlaying/durationMs/positionMs until its own onPrepared fired.
    val fileKey = resolvedMediaSourceKey(file)
    var videoView by remember(fileKey) { mutableStateOf<android.widget.VideoView?>(null) }
    var isPlaying by remember(fileKey) { mutableStateOf(true) }
    var durationMs by remember(fileKey) { mutableStateOf(0) }
    var positionMs by remember(fileKey) { mutableStateOf(0) }
    var scrubbing by remember(fileKey) { mutableStateOf(false) }
    var scrubTarget by remember(fileKey) { mutableFloatStateOf(0f) }
    var showControls by remember(fileKey) { mutableStateOf(true) }
    var dragOffsetPx by remember(fileKey) { mutableFloatStateOf(0f) }

    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    // Poll position while playing to drive the slider.
    LaunchedEffect(videoView, isPlaying, scrubbing) {
        while (isActive && videoView != null) {
            if (isPlaying && !scrubbing) {
                try {
                    val v = videoView!!
                    positionMs = v.currentPosition
                    if (durationMs <= 0) durationMs = v.duration.coerceAtLeast(0)
                } catch (_: Throwable) {}
            }
            delay(200)
        }
    }

    // Auto-hide controls after 3s while playing.
    LaunchedEffect(showControls, isPlaying, scrubbing) {
        if (showControls && isPlaying && !scrubbing) {
            delay(3000)
            showControls = false
        }
    }

    val backdropAlpha = (1f - (dragOffsetPx.coerceAtLeast(0f) / dismissThresholdPx) * 0.4f)
        .coerceIn(0.4f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dy ->
                        dragOffsetPx = if (dragOffsetPx + dy > 0f) dragOffsetPx + dy
                        else (dragOffsetPx + dy * 0.3f)
                    },
                    onDragEnd = {
                        if (dragOffsetPx > dismissThresholdPx * 0.5f) {
                            // [da1027-F] Dismissal is a terminating path, not a
                            // pause: release the track so the codec/handles do
                            // not outlive the dialog (pause() left them live).
                            try { videoView?.stopPlayback() } catch (_: Throwable) {}
                            onDismiss()
                        } else {
                            dragOffsetPx = 0f
                        }
                    },
                    onDragCancel = { dragOffsetPx = 0f },
                )
            },
    ) {
        // Video surface — offset with drag, toggle controls on tap.
        // [dv19-B02] Keyed on the data source: on a file change the old
        // VideoView is disposed (its onRelease stops the track and clears
        // videoView) and a fresh one is created for the new file instead of
        // the dialog continuing to play the old one.
        key(resolvedMediaSourceKey(file)) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { mp ->
                            durationMs = mp.duration.coerceAtLeast(0)
                            mp.isLooping = false
                            start()
                            isPlaying = true
                        }
                        setOnCompletionListener {
                            isPlaying = false
                            positionMs = durationMs
                            showControls = true
                        }
                        setOnErrorListener { _, _, _ ->
                            android.util.Log.w("FullscreenVideo", "VideoView error for ${file.absolutePath}")
                            true
                        }
                        videoView = this
                    }
                },
                // [da1027-F] Terminating path for the fullscreen player — covers
                // back-key/gesture/dialog dimiss, not just the close button. All
                // three exits funnel through `onDismiss()` -> the caller's holder
                // (`previewVideoFile` / `showPlayer`) goes null -> this composable
                // leaves composition -> the view is released here. Without it the
                // MediaPlayer and its hardware codec/AudioTrack handles stayed
                // registered after the dialog closed, so audio could keep playing
                // for a moment and the native player leaked until GC.
                onRelease = { view ->
                    try { view.stopPlayback() } catch (_: Throwable) {}
                    if (videoView === view) videoView = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showControls = !showControls },
            )
        }

        // Top bar: close (always visible when controls shown).
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bars are hidden, but the status bar can reappear via
                    // transient-swipe and the display cutout still occupies
                    // the top edge — offset past both so the close/share
                    // buttons never sit under the notch or status chrome.
                    .windowInsetsPadding(
                        WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    )
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControlButton(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = {
                        // [da1027-F] Close is a terminating path: release the
                        // player instead of pausing it (pause kept the codec
                        // and audio handles alive past the dialog).
                        try { videoView?.stopPlayback() } catch (_: Throwable) {}
                        onDismiss()
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = file.name,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                CircleControlButton(
                    icon = Icons.Default.Share,
                    contentDescription = stringResource(R.string.common_share),
                    onClick = { shareMediaFile(context, file, "video/*") },
                )
            }
        }

        // Bottom controls: play/pause, current time, slider, duration.
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val v = videoView ?: return@IconButton
                        try {
                            if (isPlaying) { v.pause(); isPlaying = false }
                            else {
                                // If at end, restart from 0.
                                if (durationMs in 1..(positionMs + 500)) {
                                    v.seekTo(0)
                                    positionMs = 0
                                }
                                v.start()
                                isPlaying = true
                            }
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
                Text(
                    text = formatDuration(if (scrubbing) scrubTarget.roundToInt() else positionMs),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = if (scrubbing) scrubTarget else positionMs.toFloat(),
                    onValueChange = { v ->
                        scrubbing = true
                        scrubTarget = v
                    },
                    onValueChangeFinished = {
                        val v = videoView
                        val target = scrubTarget.roundToInt()
                        if (v != null) {
                            try { v.seekTo(target) } catch (_: Throwable) {}
                            positionMs = target
                        }
                        scrubbing = false
                    },
                    valueRange = 0f..(durationMs.coerceAtLeast(1)).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatDuration(durationMs),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CircleControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Share a resolved media file via the system chooser (Save / Share / open in
 * another player). Uses FileProvider so the external handler gets a readable
 * content:// URI.
 */
/**
 * Walk the Compose view's parent chain to find the hosting `Window`. When
 * Compose renders inside a `Dialog`, the ComposeView is attached to an
 * internal `DialogWindowProvider` whose `window` is the Dialog's Window
 * (distinct from the host Activity's Window). Returns null if the search
 * terminates before hitting a provider — callers should no-op in that case.
 */
private fun findDialogWindow(view: android.view.View): android.view.Window? {
    var parent: Any? = view.parent
    while (parent != null) {
        if (parent is androidx.compose.ui.window.DialogWindowProvider) {
            return parent.window
        }
        parent = (parent as? android.view.View)?.parent
    }
    return null
}

private fun shareMediaFile(context: android.content.Context, file: File, mime: String) {
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (_: Throwable) {
        // [da1033-B] No `Uri.fromFile` fallback: a plain file:// URI handed to
        // another process raises FileUriExposedException (API 24+), and the
        // catch around startActivity() at the bottom swallowed it — the share
        // key just did nothing, with no log and no toast. Paths outside
        // res/xml/file_provider_paths.xml (e.g. an external mount) cannot be
        // shared directly, so tell the user instead of building a dead URI.
        // Copying the file into cacheDir/share first is deliberately NOT done:
        // videos here can be tens of MB and this runs on the click callback.
        AppLogger.warning("InlineMediaPlayer", "share: path not covered by FileProvider: $file")
        Toast.makeText(
            context,
            context.getString(R.string.file_share_failed_toast, file.name),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // [deep-audit-1049 B-03] Shared launcher. The hand-rolled version had the
    // flag right but an EMPTY catch — a device with no share target and a
    // successful share were indistinguishable on screen (the same defect the
    // FileProvider exit above documents).
    com.rikkaminis.app.util.ExternalIntent.share(context, intent, file.name) { t ->
        Toast.makeText(
            context,
            context.getString(R.string.file_share_failed_toast, t.message ?: ""),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
