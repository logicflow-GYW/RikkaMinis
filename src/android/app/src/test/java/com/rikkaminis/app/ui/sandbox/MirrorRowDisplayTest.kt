package com.rikkaminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RC-U05] JVM test for [fastestSuggestion] — the decision that keeps a mirror
 * row's latency badge describing the mirror whose NAME sits beside it.
 *
 * Defect: the category row rendered `selectedMirror.name` + `fastestResult
 * .latencyMs`, two different mirrors in one line. After a speed test the row
 * read as "China USTC · 12ms" even when the selection had timed out and 12ms
 * belonged to a mirror the user had not chosen.
 *
 * Expectations are hard-coded literals, never derived from production values.
 */
class MirrorRowDisplayTest {

    private fun entry(id: String, category: MirrorCategory) = MirrorEntry(
        id = id,
        name = "name-$id",
        baseURL = "https://example.invalid/$id/",
        testURL = "https://example.invalid/$id/probe",
        category = category,
        region = "Test",
    )

    private fun result(id: String, category: MirrorCategory, latencyMs: Int?) =
        MirrorTestResult(entry(id, category), latencyMs, if (latencyMs == null) "HTTP 500" else null)

    @Test
    fun aDifferentFastestMirrorIsAdvertisedAsASuggestion() {
        val suggestion = fastestSuggestion(
            selectedId = "alpine.slow",
            fastest = result("alpine.fast", MirrorCategory.ALPINE, 12),
        )
        assertEquals("alpine.fast", suggestion?.mirror?.id)
        assertEquals(12, suggestion?.latencyMs)
    }

    @Test
    fun theAlreadySelectedMirrorIsNotAdvertisedAsASuggestion() {
        // No "Fastest: X [Use]" line for the mirror the user already uses —
        // that would be a second control for one state.
        assertNull(
            fastestSuggestion(
                selectedId = "alpine.fast",
                fastest = result("alpine.fast", MirrorCategory.ALPINE, 12),
            ),
        )
    }

    @Test
    fun nothingSelectedStillOffersTheFastest() {
        // Official default in use: there IS a suggestion worth showing, and the
        // row must name it (the badge alone is what misled the user before).
        val suggestion = fastestSuggestion(
            selectedId = null,
            fastest = result("pip.fast", MirrorCategory.PIP, 90),
        )
        assertEquals("pip.fast", suggestion?.mirror?.id)
    }

    @Test
    fun aFailedFastestIsNotASuggestion() {
        // `fastestResult` already filters to successes, but the decision must not
        // advertise a null latency if that ever loosens.
        assertNull(
            fastestSuggestion(
                selectedId = "npm.a",
                fastest = result("npm.b", MirrorCategory.NPM, null),
            ),
        )
    }

    @Test
    fun noMeasurementMeansNoSuggestion() {
        assertNull(fastestSuggestion(selectedId = "npm.a", fastest = null))
    }
}
