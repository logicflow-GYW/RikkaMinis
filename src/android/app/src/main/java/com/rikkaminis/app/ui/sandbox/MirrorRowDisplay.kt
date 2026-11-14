package com.rikkaminis.app.ui.sandbox

/**
 * [RC-U05] Which mirror — if any — the category row may advertise as "fastest".
 *
 * The defect this replaces was a display lie, not a switching bug: the row
 * rendered the SELECTED mirror's name next to the FASTEST result's latency
 * badge, so "China USTC · 12ms" described two different mirrors at once. After
 * a speed test the row therefore read as "my mirror is the 12ms one" even when
 * the selected mirror had timed out.
 *
 * The auto-select guard is deliberately kept: [MirrorSpeedTestViewModel] must
 * not silently rewrite a mirror the user picked by hand (that is what
 * `hasPriorSelection` protects, and the first-boot detector relies on the same
 * rule). What changes is the promise: the row names the mirror the number
 * belongs to and offers an explicit one-tap switch.
 *
 * @param selectedId the currently selected mirror id for this category, or
 *        null when nothing is selected (official default).
 * @param fastest the first successful result of the last speed test.
 * @return the result to advertise, or null when there is nothing to advertise
 *         (no result / the fastest IS the selection).
 *
 * Pure (MirrorTestResult / MirrorEntry are dependency-free) so the JVM test
 * source set can drive it, same split as [autoSelectFastest].
 */
internal fun fastestSuggestion(
    selectedId: String?,
    fastest: MirrorTestResult?,
): MirrorTestResult? =
    fastest?.takeIf { it.latencyMs != null && it.mirror.id != selectedId }
