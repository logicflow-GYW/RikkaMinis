package com.rikkaminis.app.ui.components

import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One image in an [ImageGalleryViewer]. [model] is anything Coil's
 * `AsyncImage` accepts (Uri, File, String URL, etc.); [caption] is the
 * filename/alt-text shown in the bottom capsule, hidden when blank.
 */
data class ImageGalleryItem(
    val model: Any,
    val caption: String? = null,
)

/**
 * Fullscreen swipeable gallery — mirrors the iOS MessageImageGallery
 * (src/ios/Views/Chat/Media/MessageImageGallery.swift): HorizontalPager
 * over pinch-zoom/pan pages, immersive dialog chrome (system bars hidden,
 * tap toggles), bottom caption capsule, and Copy / Share / Save actions
 * bound to the currently visible page.
 *
 * Edge cases:
 *  - `items.size == 1` renders correctly (HorizontalPager with one page,
 *    no horizontal-swipe artefacts).
 *  - `startIndex` is coerced into bounds.
 *  - When a page is zoomed (`scale > 1f`), pan-pointer input consumes
 *    horizontal gestures, so the pager does not flip mid-zoom — matches
 *    iOS UIScrollView-blocks-TabView-swipe behavior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGalleryViewer(
    items: List<ImageGalleryItem>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        // Defensive: don't render an empty pager — just dismiss.
        // [fix/audit0917-b8] LaunchedEffect, not DisposableEffect: onDismiss
        // flips the caller's state (`previewUrl = null`-style), and running that
        // synchronously in a DisposableEffect executes it during the
        // composition/apply phase — mutating state that the enclosing
        // composition is still reading. LaunchedEffect posts it to the
        // composition's coroutine, after the frame settles.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    // [fix/audit0917-b8] Application scope, not rememberCoroutineScope: the
    // Save-to-album write (loadBitmap + MediaStore insert) was tied to the
    // composable, so dismissing the dialog mid-save cancelled the coroutine —
    // the MediaStore row could be half-written with no result toast, and the
    // `finally { saving = false }` never mattered because the composable was
    // gone. The work is idempotent and self-contained, so it belongs to the
    // app, not the screen.
    val scope = (context.applicationContext as? com.rikkaminis.app.MinisApp)?.applicationScope
        ?: rememberCoroutineScope()

    // Hide system bars on entry, restore on exit. Same pattern as the
    // single-image FullscreenImageViewer — see its comment block (T169)
    // for why we don't touch decorFitsSystemWindows on the activity
    // window.
    DisposableEffect(Unit) {
        // [fix/render-ui F-271] Was `(view.context as? android.app.Activity)?.window`,
        // which is always null here: `view` is the *dialog's* AndroidComposeView
        // and the dialog's context chain is ContextThemeWrapper(ContextThemeWrapper(…)),
        // never an Activity. The walker below is the working form.
        val window = findDialogWindow(view)
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val prevStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val prevNavBarColor = window?.navigationBarColor

        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            prevLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevLightNav?.let { controller.isAppearanceLightNavigationBars = it }
            @Suppress("DEPRECATION")
            prevStatusBarColor?.let { window.statusBarColor = it }
            @Suppress("DEPRECATION")
            prevNavBarColor?.let { window.navigationBarColor = it }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Apply immersive flags to the dialog's own window too.
        // [fix/render-ui F-271] `LocalView.current` is the dialog's own
        // AndroidComposeView; its parent IS the `DialogLayout`
        // (AbstractComposeView + DialogWindowProvider) whose `window` is the
        // dialog's PhoneWindow. The previous code passed `LocalView.current.parent`
        // to a walker that itself started at `root.parent` — stepping over the
        // only node that could ever match — and matched on `ctx is Activity`,
        // which the dialog chain (ContextThemeWrapper(ContextThemeWrapper(…)))
        // never contains. Both made this block a silent no-op.
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val win = findDialogWindow(dialogView)
            win?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val ctrl = WindowInsetsControllerCompat(w, w.decorView)
                ctrl.isAppearanceLightStatusBars = false
                ctrl.isAppearanceLightNavigationBars = false
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose { }
        }

        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, items.size - 1),
            pageCount = { items.size },
        )
        var showChrome by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // Pager is the bottom-most surface so per-page pointer input
            // (pinch / pan / double-tap) wins over the dialog's outer
            // click-to-dismiss when applicable.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Default behaviour: drag with at least 1 finger pages
                // unless the page composable consumes the gesture first
                // (we do that when zoomed, see below).
            ) { page ->
                GalleryPage(
                    item = items[page],
                    onTapChrome = { showChrome = !showChrome },
                )
            }

            // ── Close button ────────────────────────────────────────
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // ── Bottom caption + actions ────────────────────────────
            val currentItem = items.getOrNull(pagerState.currentPage) ?: items[0]
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Caption capsule — matches iOS GalleryPage caption
                        // (MessageImageGallery.swift L110-123). Hidden when
                        // the item has no caption (e.g. raw markdown image
                        // without alt text).
                        if (!currentItem.caption.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(14.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = currentItem.caption,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        val pillShape = RoundedCornerShape(32.dp)
                        Row(
                            modifier = Modifier
                                .background(color = Color(0xFF3A3A3C), shape = pillShape)
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.15f),
                                    shape = pillShape,
                                )
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ImageActionButton(
                                icon = Icons.Outlined.ContentCopy,
                                label = stringResource(R.string.image_action_copy),
                                onClick = { copyBitmapToClipboard(context, scope, currentItem.model) },
                            )
                            var sharing by remember { mutableStateOf(false) }
                            ImageActionButton(
                                icon = Icons.Outlined.Share,
                                label = stringResource(R.string.image_action_share),
                                onClick = onClick@{
                                    if (sharing) return@onClick
                                    sharing = true
                                    scope.launch {
                                        try {
                                            shareImage(context, currentItem.model)
                                        } finally {
                                            sharing = false
                                        }
                                    }
                                },
                            )
                            val savedToAlbumMsg = stringResource(R.string.image_saved_to_album_toast)
                            val saveFailedMsg = stringResource(R.string.image_save_failed_toast)
                            val loadFailedMsg = stringResource(R.string.image_load_failed_toast)
                            // T10-L4: same guard + failure reporting as the
                            // fullscreen viewer's Save button.
                            var saving by remember { mutableStateOf(false) }
                            ImageActionButton(
                                icon = Icons.Outlined.Download,
                                label = stringResource(R.string.image_action_save),
                                onClick = onClick@{
                                    if (saving) return@onClick
                                    saving = true
                                    scope.launch {
                                        try {
                                            val bmp = loadBitmap(context, currentItem.model)
                                            val msg = when {
                                                bmp == null -> loadFailedMsg
                                                saveToGallery(context, bmp) -> savedToAlbumMsg
                                                else -> saveFailedMsg
                                            }
                                            // [fix/render-ui F-270] `scope` is MinisApp.applicationScope
                                            // (SupervisorJob + Dispatchers.IO — no Looper). Toast.makeText
                                            // calls Looper.myLooper() and throws
                                            // "Can't toast on a thread that has not called Looper.prepare()"
                                            // when it is null, so this toast crashed the process on every
                                            // Save-to-album tap. Hop to Main, like every toast in
                                            // FullscreenImageViewer's copyBitmapToClipboard/shareImage.
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One page inside [ImageGalleryViewer]. Owns its own zoom/pan state so the
 * pager remembers per-page transform independently — flipping to the next
 * page resets the previous page's zoom when its composition leaves.
 */
@Composable
private fun GalleryPage(
    item: ImageGalleryItem,
    onTapChrome: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                // When zoomed, this pointerInput intercepts horizontal pan
                // so the parent pager doesn't change pages while the user
                // is panning around inside a magnified image. Mirrors iOS
                // UIScrollView naturally blocking the parent TabView swipe.
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = { onTapChrome() },
                    )
                },
        )
    }
}

/**
 * [fix/render-ui F-271] Find the hosting `Window` of a Compose `Dialog`.
 *
 * Compose renders dialog content inside an internal `DialogLayout`
 * (`AbstractComposeView`, `DialogWindowProvider`) which is the *direct parent*
 * of `LocalView.current`, and whose `window` is the dialog's PhoneWindow.
 *
 * Two things this walker must NOT do, both of which the previous version did:
 *  1. Match on `ctx is android.app.Activity`. AOSP `Dialog.java` wraps the
 *     caller's context in one or two `ContextThemeWrapper`s, so the dialog's
 *     view chain contains no Activity context at all — the predicate can never
 *     hit (verified by `exp_c4_a.kt`: `treeA_ctxwalker_null=true`).
 *  2. Start at `root.parent`. When the caller already passes the node *above*
 *     the provider, skipping one more level steps over the only match.
 *
 * Mirrors `InlineMediaPlayer.findDialogWindow` / `WebPreviewBottomSheet` /
 * `WebPreviewFullscreenScreen` — the three copies that got it right.
 */
private fun findDialogWindow(view: android.view.View): android.view.Window? {
    var p: android.view.ViewParent? = view.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}
