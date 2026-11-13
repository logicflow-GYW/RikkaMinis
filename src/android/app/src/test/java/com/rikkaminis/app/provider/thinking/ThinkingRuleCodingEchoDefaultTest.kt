package com.rikkaminis.app.provider.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [R8-B B-F05 ①] A rule whose `timing` cannot be read must express NO opinion.
 *
 * `decodeEcho` used to default a missing / misspelled / renamed `timing` to
 * `EVERY_TURN` — the most aggressive tier — so corrupt or imported JSON taught
 * the app to echo on EVERY turn for arbitrary gateways. That is fail-OPEN on a
 * field that decides what goes on the wire. The whole policy must decode to
 * null instead, leaving the legacy gates in charge (exactly as if the rule had
 * never declared an echo policy).
 *
 * Paired with [ReasoningEchoDeciderTest]'s new `EVERY_TURN` expectation: making
 * the tier honest without fixing the decode would make bad data noisier.
 */
class ThinkingRuleCodingEchoDefaultTest {

    @Test
    fun `a missing timing decodes to no policy at all`() {
        assertNull(ThinkingRuleCoding.decodeEcho("""{"fieldName":"x"}"""))
    }

    @Test
    fun `an unknown timing word decodes to no policy at all`() {
        assertNull(ThinkingRuleCoding.decodeEcho("""{"timing":"BOGUS"}"""))
    }

    @Test
    fun `a lowercased timing word is not silently accepted`() {
        // The enum name is the wire contract (`encodeEcho` writes `.name`).
        // Accepting `every_turn` here would make encode/decode asymmetric.
        assertNull(ThinkingRuleCoding.decodeEcho("""{"timing":"every_turn"}"""))
    }

    @Test
    fun `every real timing still decodes`() {
        for (timing in ReasoningEchoPolicy.Timing.entries) {
            val decoded = ThinkingRuleCoding.decodeEcho(
                """{"fieldName":"reasoning_content","timing":"${timing.name}"}""",
            )
            assertEquals("timing=${timing.name}", timing, decoded?.timing)
            assertEquals("reasoning_content", decoded?.fieldName)
        }
    }

    @Test
    fun `a declared field name survives the round trip`() {
        val decoded = ThinkingRuleCoding.decodeEcho(
            """{"fieldName":"reasoning_text","timing":"AFTER_TOOL_USE_ONLY"}""",
        )
        assertEquals("reasoning_text", decoded?.fieldName)
        assertEquals(ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY, decoded?.timing)
    }

    @Test
    fun `the field name still defaults when absent`() {
        val decoded = ThinkingRuleCoding.decodeEcho("""{"timing":"NEVER"}""")
        assertEquals("reasoning_content", decoded?.fieldName)
        assertEquals(ReasoningEchoPolicy.Timing.NEVER, decoded?.timing)
    }

    @Test
    fun `blank and malformed json stay null`() {
        assertNull(ThinkingRuleCoding.decodeEcho(null))
        assertNull(ThinkingRuleCoding.decodeEcho(""))
        assertNull(ThinkingRuleCoding.decodeEcho("not json"))
    }

    @Test
    fun `encoding still writes the enum name`() {
        val json = ThinkingRuleCoding.encodeEcho(
            ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.EVERY_TURN),
        )
        assertEquals("""{"fieldName":"reasoning_content","timing":"EVERY_TURN"}""", json)
    }
}
