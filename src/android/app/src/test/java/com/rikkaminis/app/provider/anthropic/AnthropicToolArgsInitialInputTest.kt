package com.rikkaminis.app.provider.anthropic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [R8-B B-F07] An EMPTY delta object is not "arguments arrived".
 *
 * A relay that wraps a non-streaming upstream announces the block with the real
 * `content_block.input` and then streams `input_json_delta` fragments anyway —
 * sometimes exactly `{}`. The old rule (`deltaBuffer.isEmpty()`) counted `"{}"`
 * as a delta, parsed it into an empty object and DISCARDED the announced input,
 * so the tool ran parameterless and was rejected by the preflight validator.
 *
 * The narrowness matters as much as the fix: an UNPARSABLE buffer must keep
 * degrading to `{}` (documented behaviour) so a truncated stream still reaches
 * JSON repair / preflight instead of being papered over with stale arguments.
 */
class AnthropicToolArgsInitialInputTest {

    private fun initial() = JSONObject().put("path", "/a")

    @Test
    fun `an empty delta object falls back to the announced input`() {
        val args = resolveAnthropicToolArgs("{}", initial())
        assertEquals("/a", args.optString("path"))
    }

    @Test
    fun `an empty delta object with whitespace falls back too`() {
        val args = resolveAnthropicToolArgs("{ }", initial())
        assertEquals("/a", args.optString("path"))
    }

    @Test
    fun `no delta at all still falls back`() {
        val args = resolveAnthropicToolArgs("", initial())
        assertEquals("/a", args.optString("path"))
    }

    @Test
    fun `real delta arguments still win`() {
        val args = resolveAnthropicToolArgs("""{"path":"/b"}""", initial())
        assertEquals("/b", args.optString("path"))
    }

    @Test
    fun `a non-empty delta that omits a field is still authoritative`() {
        // The delta branch wins because it carries something — do not merge the
        // announced input into it (two sources would produce invalid/duplicated JSON).
        val args = resolveAnthropicToolArgs("""{"other":1}""", initial())
        assertEquals(0, args.optInt("other") - 1)
        assertEquals("", args.optString("path"))
    }

    @Test
    fun `an unparsable buffer keeps degrading to empty and does not resurrect stale args`() {
        val args = resolveAnthropicToolArgs("""{"path":""", initial())
        assertEquals(0, args.length())
    }

    @Test
    fun `an empty delta object without an announcement is empty`() {
        assertEquals(0, resolveAnthropicToolArgs("{}", null).length())
    }

    @Test
    fun `the announced input is copied, not aliased`() {
        val announced = initial()
        val args = resolveAnthropicToolArgs("{}", announced)
        args.put("path", "/mutated")
        assertEquals("the block's initialInput must not be mutated by the resolve copy", "/a", announced.optString("path"))
    }
}
