package com.rikkaminis.app.ui.settings

/**
 * Pure ordering primitives shared by the long-press reorder screens and their
 * accessibility "move up / move down" actions.
 *
 * Both paths must produce the same list: a drag that lands one slot away and
 * an accessibility step of one slot are the same user intent, and a screen
 * that computes them twice drifts (the accessibility path would silently
 * write a different order than the gesture). Kept free of Compose so a plain
 * JVM test can pin the boundary rules.
 */

/**
 * Move [id] by [delta] slots inside [order].
 *
 * Returns `null` when the move is impossible — unknown id, or a target
 * outside the list — so a caller can answer `false` to an accessibility
 * action without writing an unchanged order back to storage.
 */
internal fun moveEntryByDelta(order: List<String>, id: String, delta: Int): List<String>? {
    if (delta == 0) return null
    val from = order.indexOf(id)
    if (from < 0) return null
    val to = from + delta
    if (to !in order.indices) return null
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Re-order one section of a flat list while leaving every other element in
 * place.
 *
 * [allIds] is the flat persisted order (providers: one list bucketed by
 * Favorites / providerType), [memberIds] the dragged section's members in
 * rendered order, and [fromIdx]/[toIdx] the move inside that section. The
 * returned list keeps the relative order of every non-member exactly where it
 * was, which is what makes a same-section-only drag safe: the other sections
 * are derived by filtering this list, so shifting them would silently change
 * their own displayed order.
 *
 * Returns `null` for an out-of-range move.
 */
internal fun spliceSectionOrder(
    allIds: List<String>,
    memberIds: List<String>,
    fromIdx: Int,
    toIdx: Int,
): List<String>? {
    if (fromIdx !in memberIds.indices || toIdx !in memberIds.indices) return null
    if (fromIdx == toIdx) return null
    val members = memberIds.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
    val memberSet = memberIds.toSet()
    val out = ArrayList<String>(allIds.size)
    var pos = 0
    for (id in allIds) {
        if (id in memberSet) out.add(members[pos++]) else out.add(id)
    }
    // A section member missing from the flat list would leave the tail of
    // `members` unplaced; refuse rather than return a damaged order.
    return if (pos == members.size) out else null
}
