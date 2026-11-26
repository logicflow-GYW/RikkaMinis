package com.rikkaminis.app.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * Window-space bounds of the chat list's visible area — the rectangle a reader
 * actually sees, used by the process-fold auto-collapse to answer "has this
 * segment left the top of the screen?".
 *
 * Deliberately NOT a `MutableState` and NOT a value CompositionLocal. The bounds
 * are written from `onGloballyPositioned` and read from the per-segment
 * `onGloballyPositioned` callbacks, i.e. layout phase to layout phase, so a
 * keyboard/inset change repaints nothing: a state-backed local would invalidate
 * every message row in the list on each change, and the geometry it carries is
 * needed only inside layout callbacks. A one-frame-stale value (the child's
 * callback can run before the list's own) is harmless here: the crossing only
 * has to be right by the time the scroll settles.
 */
class ChatViewportTracker {
    /** List bounds in window coordinates; null until the list is laid out. */
    var bounds: Rect? = null
}

internal val LocalChatViewportTracker = staticCompositionLocalOf<ChatViewportTracker?> { null }
