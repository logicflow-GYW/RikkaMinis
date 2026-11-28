package com.rikkaminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.theme.ChatColors
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Material3 1.3.2 SheetDefaults.kt:243–244, 434–435. Its SheetState cannot
// accept top anchors; Foundation's default uses half the range instead.
private val SheetMotion = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
private val SheetPositionThreshold = 56.dp
private val SheetVelocityThreshold = 125.dp

internal enum class TopSheetAnchor { Hidden, Expanded }

internal fun topSheetHasDismissed(
    settled: TopSheetAnchor,
    offset: Float,
    hiddenOffset: Float,
    entered: Boolean,
    closeRequested: Boolean,
): Boolean = settled == TopSheetAnchor.Hidden && offset == hiddenOffset && (entered || closeRequested)

internal fun topSheetReleaseTarget(
    offset: Float,
    velocity: Float,
    positionThreshold: Float,
    velocityThreshold: Float,
    releasedNormally: Boolean,
): TopSheetAnchor = when {
    !releasedNormally || offset >= 0f -> TopSheetAnchor.Expanded
    velocity <= -velocityThreshold || offset <= -positionThreshold -> TopSheetAnchor.Hidden
    else -> TopSheetAnchor.Expanded
}

/** Foundation reports both cancelled and stationary releases with zero velocity. */
internal class TopSheetTouch {
    var pressed = false
        private set
    var releasedNormally = false
        private set

    fun update(pressed: Boolean, normalUp: Boolean) {
        if (pressed || this.pressed) releasedNormally = !pressed && normalUp
        this.pressed = pressed
    }
}

/** The list owns direct movement first, except to return an already displaced sheet. */
internal class TopSheetListScroll(
    private val touch: TopSheetTouch,
    private val offset: () -> Float,
    val dragBy: (Float) -> Float,
    val release: (Float) -> Unit,
) : NestedScrollConnection {
    private var draggedSheet = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && touch.pressed && available.y > 0f && offset() < 0f) {
            Offset(0f, dragBy(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || !touch.pressed || available.y >= 0f) return Offset.Zero
        val moved = dragBy(available.y)
        draggedSheet = draggedSheet || moved < 0f
        return Offset(0f, moved)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val ownsRelease = draggedSheet && offset() < 0f
        draggedSheet = false
        if (!ownsRelease) return Velocity.Zero
        // Release a drag we actually moved, before LazyColumn flings. Inertial
        // frames and onPostFling cannot move or dismiss the sheet.
        release(available.y)
        return Velocity(0f, available.y)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopChatSheet(
    paneName: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = remember { AnchoredDraggableState(TopSheetAnchor.Hidden) }
    val scope = rememberCoroutineScope()
    val touch = remember { TopSheetTouch() }
    var entered by remember { mutableStateOf(false) }
    var closeRequested by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val requestClose: () -> Unit = { closeRequested = true }

    LaunchedEffect(state, closeRequested) {
        snapshotFlow { !state.offset.isNaN() }.first { it }
        if (closeRequested) {
            state.animateTo(TopSheetAnchor.Hidden, SheetMotion)
        } else {
            state.animateTo(TopSheetAnchor.Expanded, SheetMotion)
            entered = true
        }
    }
    LaunchedEffect(state) {
        snapshotFlow {
            topSheetHasDismissed(
                state.settledValue, state.offset, state.anchors.positionOf(TopSheetAnchor.Hidden),
                entered, closeRequested,
            )
        }.first { it }
        latestDismiss()
    }

    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val panelColor = ChatColors.background
        SideEffect {
            window?.let {
                // Only the scrim below should dim chat; the panel owns the status-bar background.
                it.setDimAmount(0f)
                WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars =
                    panelColor.luminance() > 0.5f
            }
        }
        val density = LocalDensity.current
        val listScroll = remember(state, density) {
            val position = with(density) { SheetPositionThreshold.toPx() }
            val velocity = with(density) { SheetVelocityThreshold.toPx() }
            TopSheetListScroll(
                touch = touch,
                offset = { state.offset },
                dragBy = { delta ->
                    if (entered && !closeRequested && !state.isAnimationRunning && !state.offset.isNaN()) {
                        state.dispatchRawDelta(delta)
                    } else 0f
                },
                release = { initialVelocity ->
                    if (entered && !closeRequested && !state.isAnimationRunning && state.requireOffset() < 0f) {
                        val target = topSheetReleaseTarget(
                            state.requireOffset(), initialVelocity, position, velocity, touch.releasedNormally,
                        )
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.animateTo(target, SheetMotion) }
                    }
                },
            )
        }
        // Same native draggable as ModalBottomSheet. Only the handle and the
        // scrim get it directly; the LazyColumn owns its own touch slop/drag.
        val directDrag = Modifier.draggable(
            state = rememberDraggableState { listScroll.dragBy(it) },
            orientation = Orientation.Vertical,
            enabled = entered && !closeRequested && !state.isAnimationRunning,
            onDragStopped = { listScroll.release(it) },
        )
        val scrimColor = BottomSheetDefaults.ScrimColor
        val closeLabel = stringResource(R.string.standard_sheet_close)
        Box(
            Modifier.fillMaxSize().imePadding().clipToBounds().semantics { isTraversalGroup = true }
                .pointerInput(touch) {
                    // Observe only. Neither consume nor decide a drag here.
                    // Initial pass sees real UP before a child consumes it;
                    // Compose's synthetic CANCEL has an already consumed UP.
                    awaitPointerEventScope {
                        while (true) {
                            val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                            val pressed = changes.any { it.pressed }
                            touch.update(pressed, !pressed && changes.any { it.changedToUp() })
                        }
                    }
                },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .drawBehind {
                        if (!state.offset.isNaN()) {
                            drawRect(scrimColor, alpha = state.progress(TopSheetAnchor.Hidden, TopSheetAnchor.Expanded))
                        }
                    }
                    .semantics {
                        contentDescription = closeLabel
                        traversalIndex = 1f
                    }
                    .then(directDrag)
                    .clickable(interactionSource = null, indication = null, onClick = requestClose),
            )
            // A native Surface blocks scrim hit-testing even in blank panel areas.
            Surface(
                modifier = Modifier.align(Alignment.TopCenter)
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .onSizeChanged { size ->
                        if (size.height > 0) {
                            state.updateAnchors(
                                DraggableAnchors {
                                    TopSheetAnchor.Hidden at -size.height.toFloat()
                                    TopSheetAnchor.Expanded at 0f
                                },
                                newTarget = if (state.isAnimationRunning) state.targetValue else state.settledValue,
                            )
                        }
                    }
                    .offset { IntOffset(0, if (state.offset.isNaN()) 0 else state.requireOffset().roundToInt()) }
                    .nestedScroll(listScroll)
                    .semantics { paneTitle = paneName; traversalIndex = 0f },
                shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp),
                color = panelColor,
            ) {
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
                ) {
                    Box(Modifier.weight(1f)) { content() }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(34.dp).then(directDrag)
                            .semantics(mergeDescendants = true) { dismiss(label = closeLabel) { requestClose(); true } },
                        contentAlignment = Alignment.Center,
                    ) {
                        CompactDragHandle()
                    }
                }
            }
        }
    }
}
