package com.rikkaminis.app.ui.settings

/**
 * Pure drag-reorder math for [ChatMenuSettingsScreen]'s draggable rows.
 *
 * Extracted out of the composable's `detectDragGesturesAfterLongPress` handler
 * so the landing-slot decision — the part that has a wrong answer — is a
 * literal-in / literal-out function covered by a JVM unit test instead of a
 * gesture that can only be exercised on a device.
 *
 * ## Why a "wall" is needed at all
 *
 * The footer section renders `ChatMenuPrefs.settingsPinOrder(prefs)`: all keys
 * with `SETTINGS` appended last, because the settings row must stay reachable
 * even when it is unpinned. That row is composed with `draggable = false` — you
 * cannot grab it — but the drag handler is installed per row and only knew
 * about `order.lastIndex` (F-12). So dragging the entry directly above
 * `SETTINGS` one row down swapped it WITH `SETTINGS`:
 *
 *  * the settings list showed `SETTINGS` second-to-last while its hint still
 *    claimed the footer anchors it last;
 *  * `writePinOrder` persisted that order, so the stored order no longer
 *    matched what the settings screen renders;
 *  * the drawer re-anchors `SETTINGS` last at render time
 *    (`ChatMenuPrefs.resolvePinnedOrder` → `anchorSettingsLast`), so the drag
 *    had **no effect** on the thing the user was trying to reorder — the only
 *    visible change was the non-draggable row jumping.
 *
 * With [lockedKeys] the row above a locked entry clamps to its own index: the
 * committed order is exactly the order the list shows, so the "write the final
 * visible order" property holds by construction.
 */

/**
 * The index [currentIndex] may move to for a drag step of [delta] rows, or
 * [currentIndex] itself when the step is blocked by a locked row.
 *
 * A locked row is a **wall**, not a swappable neighbour: the walk toward
 * `currentIndex + delta` stops at the last reachable index before the first
 * locked one, so a step can never cross it (and therefore can never move it).
 * A locked row that is itself being dragged never moves either (defensive —
 * callers compose those rows with `draggable = false`).
 *
 * Returns [currentIndex] for an out-of-range index as well: the caller's
 * `targetIndex != currentIndex` check then simply does nothing, which is the
 * same outcome as a blocked step (no reorder, no write).
 */
internal fun chatMenuDragTargetIndex(
    order: List<String>,
    currentIndex: Int,
    delta: Int,
    lockedKeys: Set<String>,
): Int {
    if (currentIndex !in order.indices) return currentIndex
    if (order[currentIndex] in lockedKeys) return currentIndex

    val desired = (currentIndex + delta).coerceIn(0, order.lastIndex)
    if (desired == currentIndex) return currentIndex

    val step = if (desired > currentIndex) 1 else -1
    var reachable = currentIndex
    var probe = currentIndex
    while (probe != desired) {
        probe += step
        if (order[probe] in lockedKeys) return reachable
        reachable = probe
    }
    return reachable
}

/**
 * Order after swapping the rows at [from] and [to] (the live "cross the
 * half-row boundary and swap" behaviour the gesture implements).
 *
 * Returns [order] itself — the same instance — when the move is a no-op or an
 * index is out of range, so a caller can never write a reordered list for a
 * drag that did not land anywhere.
 */
internal fun chatMenuDragSwap(order: List<String>, from: Int, to: Int): List<String> {
    if (from == to || from !in order.indices || to !in order.indices) return order
    val next = order.toMutableList()
    val moved = next[from]
    next[from] = next[to]
    next[to] = moved
    return next
}
