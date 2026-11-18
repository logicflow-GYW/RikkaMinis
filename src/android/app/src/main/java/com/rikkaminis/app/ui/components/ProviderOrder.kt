package com.rikkaminis.app.ui.components

/**
 * [fix/provider-order-1118] The ONE ordering every provider-list surface renders.
 *
 * ## The problem this exists to end
 *
 * `config.instances` has exactly one stored order (drag writes it, `toSnapshot` stores
 * `sortOrder = index`), but four surfaces each decided how to lay it out:
 *
 *  - Settings → AI providers: a Favorites block on top, then one block per
 *    `providerType` (`ProviderListScreen.groupedRows`).
 *  - Chat → model picker: Favorites on top, then the flat order
 *    (`ChatModelPickerSheet.orderedProviderSections`).
 *  - Settings → model group → add models / Agent Loop → add models: the flat order
 *    with **no Favorites handling at all** (`ModelEntryPicker.modelEntryPickerItems`).
 *
 * The last one is what users notice: any provider they reordered inside the Favorites
 * block (or newly pinned) appeared at its raw flat position there, i.e. "the order I
 * set is ignored in this one place".
 *
 * ## The contract
 *
 * [providerBlocks] is the single derivation: Favorites (in flat order) first — only when
 * non-empty — then one block per type in FIRST-APPEARANCE order, each block in flat
 * order. [providerDisplayOrder] is that same list flattened, for surfaces that render
 * one continuous list. Both are stable: equal input order ⇒ equal output, and no item is
 * added, dropped or duplicated.
 *
 * Generic over T on purpose: `ProviderInstance` lives in a file that drags Compose and
 * kotlinx.serialization into the compile unit, while this decision is pure list algebra
 * and must be testable on the JVM.
 */
internal data class ProviderBlock<T, K>(
    /** `null` = the Favorites block; otherwise the provider type itself. */
    val typeKey: K?,
    val items: List<T>,
)

internal fun <T, K> providerBlocks(
    items: List<T>,
    pinned: (T) -> Boolean,
    typeKey: (T) -> K,
): List<ProviderBlock<T, K>> {
    val favorites = items.filter(pinned)
    val rest = items.filter { !pinned(it) }
    val blocks = ArrayList<ProviderBlock<T, K>>(4)
    // An empty Favorites block would render as a header with nothing under it.
    if (favorites.isNotEmpty()) blocks.add(ProviderBlock(typeKey = null, items = favorites))
    // LinkedHashSet, not sorted(): the type order is "the order the user's list first
    // mentions it", which is what the settings screen has always shown.
    val typeOrder = LinkedHashSet<K>()
    rest.forEach { typeOrder.add(typeKey(it)) }
    typeOrder.forEach { key ->
        blocks.add(ProviderBlock(typeKey = key, items = rest.filter { typeKey(it) == key }))
    }
    return blocks
}

internal fun <T, K> providerDisplayOrder(
    items: List<T>,
    pinned: (T) -> Boolean,
    typeKey: (T) -> K,
): List<T> = providerBlocks(items, pinned, typeKey).flatMap { it.items }
