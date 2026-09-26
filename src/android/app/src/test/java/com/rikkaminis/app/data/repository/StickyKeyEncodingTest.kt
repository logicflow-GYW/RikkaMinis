package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the sticky-credential persistence contract
 * (T-key-affinity) — the encode/decode seam between the process-local
 * [com.rikkaminis.app.data.routing.GroupRouter] memory and the SharedPreferences
 * StringSet it serializes to.
 *
 * The assertions pin the wire contract that makes the memory survive an app
 * restart without corrupting memory for colon-carrying entry ids:
 *
 *  1. Round-trip: encode → decode reproduces the same map (entry ids may
 *     carry colons; the split is on the LAST colon, not the first).
 *  2. Malformed entries are dropped, NOT fatal — a hand-edited prefs blob
 *     must never blow up the provider load.
 *  3. Negative / out-of-range indexes are dropped at decode time (no slot
 *     addresses below 0), and the use site re-validates against the live
 *     credential count.
 */
class StickyKeyEncodingTest {

    @Test
    fun roundTrip_preservesEntries() {
        val source = mapOf(
            "inst-a/model-x" to 0,
            "inst-b/model:y" to 2,
            "inst-c/model:z:variant" to 1,
        )
        val decoded = decodeStickyKeyEntries(encodeStickyKeyEntries(source))
        assertEquals(source, decoded)
    }

    @Test
    fun colonCarryingModelId_survivesTheSplit() {
        // The entry id is "{instanceId}/{modelId}" and the modelId may itself
        // contain colons (OpenRouter-style "model:variant"). A first-colon
        // split would silently corrupt memory for every colon-carrying model.
        val encoded = encodeStickyKeyEntries(mapOf("inst-a/model:variant" to 1))
        assertEquals(setOf("inst-a/model:variant:1"), encoded)
        val decoded = decodeStickyKeyEntries(encoded)
        assertEquals(mapOf("inst-a/model:variant" to 1), decoded)
    }

    @Test
    fun malformedEntriesAreDropped_notFatal() {
        val decoded = decodeStickyKeyEntries(
            setOf(
                "no-colon",          // missing separator
                ":3",                // empty entry id
                "inst-a/model:",     // empty index
                "inst-a/model:abc",  // non-numeric index
                "inst-a/model-x:2",  // well-formed
            ),
        )
        assertEquals(mapOf("inst-a/model-x" to 2), decoded)
    }

    @Test
    fun negativeIndexesAreDropped() {
        val decoded = decodeStickyKeyEntries(setOf("inst-a/model-x:-1"))
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun nullInputDecodesToEmptyMap() {
        assertTrue(decodeStickyKeyEntries(null).isEmpty())
    }

    @Test
    fun emptyMapEncodesToEmptySet() {
        assertTrue(encodeStickyKeyEntries(emptyMap()).isEmpty())
    }

    @Test
    fun decodeOfEmptySetIsEmptyMap() {
        assertTrue(decodeStickyKeyEntries(emptySet()).isEmpty())
    }

    @Test
    fun encodeDropsMalformedEntries() {
        val encoded = encodeStickyKeyEntries(
            mapOf(
                "" to 0,   // empty entry id is not a valid key
                "inst-a/model-x" to -3,  // negative index has no slot
                "inst-a/model-y" to 1,
            ),
        )
        assertEquals(setOf("inst-a/model-y:1"), encoded)
    }
}
