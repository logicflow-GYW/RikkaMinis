package com.rikkaminis.app.config

/**
 * Runtime availability of the chat action pool — the ONE place that decides
 * whether a pool entry can be shown right now, plus the two projections the UI
 * renders (top-right "..." menu, history-drawer footer).
 *
 * Two independent decisions decide whether an entry appears:
 *
 *  1. **The user's choice** ([ChatMenuPrefs.isVisible] / [ChatMenuPrefs.isPinned]) —
 *     may this entry be on my bar at all?
 *  2. **Runtime availability** ([ChatActionAvailability.isAvailable]) — can it do
 *     anything with the current session? Skills / MCPs / Memory need their
 *     backing repository; Fast Mode / Enhanced Cache need an active model that
 *     supports them.
 *
 * They are ANDed. Availability only filters the RENDER and never touches the
 * persisted choice, so a temporarily-unavailable entry comes back on its own.
 *
 * [chat-menu-model-toggles-1126] Why this file exists: Fast Mode / Enhanced
 * Cache used to be rendered by a separate branch that counted them outside the
 * pool, so they ignored decision 1 entirely — a user who hid every entry still
 * got a ⚡ button. Both decisions now live in one Android-free function pair so
 * the menu and the footer cannot drift apart, and so the whole render decision
 * is exercisable by JVM unit tests.
 */
data class ChatActionAvailability(
    val fastMode: Boolean,
    val enhancedCache: Boolean,
    val skills: Boolean,
    val mcps: Boolean,
    val memory: Boolean,
) {
    fun isAvailable(key: String): Boolean = when (key) {
        ChatMenuPrefs.FAST_MODE -> fastMode
        ChatMenuPrefs.ENHANCED_CACHE -> enhancedCache
        ChatMenuPrefs.SESSION_SKILLS -> skills
        ChatMenuPrefs.SESSION_MCPS -> mcps
        ChatMenuPrefs.SESSION_MEMORY -> memory
        else -> true
    }
}

/**
 * The entries the top-right "..." menu renders, in display order: the user's
 * visibility choice AND runtime availability, in one pass.
 */
fun resolveVisibleMenuEntries(
    order: List<String>,
    isVisible: (String) -> Boolean,
    availability: ChatActionAvailability,
): List<String> = order.filter { isVisible(it) && availability.isAvailable(it) }

/**
 * The entries the history-drawer footer renders, in display order. The pinned
 * order already carries the user's choice (only pinned keys are in it), so only
 * availability is applied here.
 */
fun resolveVisibleFooterEntries(
    pinnedOrder: List<String>,
    availability: ChatActionAvailability,
): List<String> = pinnedOrder.filter { availability.isAvailable(it) }
