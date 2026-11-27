package com.rikkaminis.app.ui.settings

/** A drag is valid only while the live rule identities still match its snapshot. */
internal fun <T> movedThinkingRules(
    current: Pair<List<T>, List<String>>,
    dragIds: List<String>,
    fromIndex: Int,
    toIndex: Int,
): Pair<List<T>, List<String>>? {
    val (rules, ids) = current
    if (rules.size != ids.size || ids != dragIds || ids.toSet().size != ids.size) return null
    if (toIndex !in ids.indices) return null
    val id = ids.getOrNull(fromIndex) ?: return null
    val order = moveEntryByDelta(ids, id, toIndex - fromIndex) ?: return null
    val ruleById = ids.zip(rules).toMap()
    return order.map(ruleById::getValue) to order
}
