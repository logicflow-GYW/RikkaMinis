package com.rikkaminis.app.provider

import com.rikkaminis.app.provider.anthropic.AnthropicNonStreamVerdict
import com.rikkaminis.app.provider.anthropic.anthropicNonStreamVerdict
import com.rikkaminis.app.ui.chat.TruncatedToolCallPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-FW-NONSTREAM C1 + C2] The two pure decisions behind the non-streaming
 * terminal-signal fix, with no MockWebServer and no Android runtime:
 *
 * ## C1 — an Anthropic 2xx is not a success
 * `parseResponse` reads `content[].text` only. A `200 {"type":"error",…}` and a
 * body with neither `content` nor `stop_reason` both used to become
 * `LLMResponse("", null)` — an empty answer persisted as a completed turn.
 * [anthropicNonStreamVerdict] classifies the body BEFORE parsing it.
 *
 * ## C2 — the output ceiling
 * [nonStreamOutputCeilingCut] must recognise every spelling the three
 * non-streaming arms can produce (`length` / `max_tokens` / `max_output_tokens`
 * / relay variants, case- and `_`/`-`-insensitive) and nothing else, and it must
 * stay a DELEGATION to [TruncatedToolCallPolicy] so the two spellings can never
 * drift apart.
 *
 * Expected values are hand-written literals from the protocol documents and the
 * gates' status table — never read back out of the code under test (a mutated
 * constant must not move its own expectation).
 */
class NonStreamTerminalSignalTest {

    private fun verdict(json: String) = anthropicNonStreamVerdict(JSONObject(json))

    // -- C1: the error object -------------------------------------------------

    @Test
    fun `overloaded_error maps to the transient class the streaming arm uses`() {
        val v = verdict("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertEquals(AnthropicNonStreamVerdict.HttpError(529), v)
    }

    @Test
    fun `rate_limit_error maps to 429 so the caller retries with backoff`() {
        val v = verdict("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")
        assertEquals(AnthropicNonStreamVerdict.HttpError(429), v)
    }

    @Test
    fun `authentication_error maps to 401`() {
        val v = verdict("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals(AnthropicNonStreamVerdict.HttpError(401), v)
    }

    @Test
    fun `an unknown error type falls back to 400 like the streaming arm`() {
        val v = verdict("""{"type":"error","error":{"type":"some_new_error"}}""")
        assertEquals(AnthropicNonStreamVerdict.HttpError(400), v)
    }

    @Test
    fun `a relay numeric code wins over the type table`() {
        val v = verdict("""{"error":{"code":503,"type":"overloaded_error","message":"upstream"}}""")
        assertEquals(AnthropicNonStreamVerdict.HttpError(503), v)
    }

    @Test
    fun `a bare string error is a no-message body carrying its text`() {
        val v = verdict("""{"error":"upstream connect error or disconnect/reset before headers"}""")
        assertTrue("expected EmptyMessage, got $v", v is AnthropicNonStreamVerdict.EmptyMessage)
        v as AnthropicNonStreamVerdict.EmptyMessage
        assertTrue(
            "the relay's own words must survive into the message, got: ${v.describe()}",
            v.describe().contains("upstream connect error"),
        )
    }

    // -- C1: the no-message body ---------------------------------------------

    @Test
    fun `an empty object is not a completed turn`() {
        assertTrue(verdict("{}") is AnthropicNonStreamVerdict.EmptyMessage)
    }

    @Test
    fun `an empty content array without a stop reason is not a completed turn`() {
        val v = verdict("""{"content":[],"usage":{"input_tokens":0,"output_tokens":0}}""")
        assertTrue("expected EmptyMessage, got $v", v is AnthropicNonStreamVerdict.EmptyMessage)
    }

    @Test
    fun `the refusal message names the body it refused`() {
        val v = verdict("""{"type":"error","message":"relay truncated the payload"}""")
        v as AnthropicNonStreamVerdict.EmptyMessage
        val text = v.describe()
        assertTrue("must carry the error type, got: $text", text.contains("type: error"))
        assertTrue("must carry the server's detail, got: $text", text.contains("relay truncated the payload"))
        assertTrue("must say what it refused to do, got: $text", text.contains("no message content"))
    }

    // -- C1: bodies that MUST stay successes ----------------------------------

    @Test
    fun `a normal message is Ok`() {
        assertEquals(
            AnthropicNonStreamVerdict.Ok,
            verdict("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""),
        )
    }

    @Test
    fun `a pure tool_use turn is Ok — empty text is legitimate there`() {
        assertEquals(
            AnthropicNonStreamVerdict.Ok,
            verdict(
                """{"content":[{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}],""" +
                    """"stop_reason":"tool_use"}""",
            ),
        )
    }

    @Test
    fun `a thinking-only turn cut at the ceiling is Ok — C2 carries that signal`() {
        assertEquals(
            AnthropicNonStreamVerdict.Ok,
            verdict("""{"content":[{"type":"thinking","thinking":"…"}],"stop_reason":"max_tokens"}"""),
        )
    }

    @Test
    fun `an explicit JSON null error is the same as an absent one`() {
        assertEquals(
            AnthropicNonStreamVerdict.Ok,
            verdict("""{"error":null,"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""),
        )
    }

    @Test
    fun `an empty content array WITH a stop reason is left alone`() {
        assertEquals(
            AnthropicNonStreamVerdict.Ok,
            verdict("""{"content":[],"stop_reason":"end_turn"}"""),
        )
    }

    // -- C2: the ceiling spelling table --------------------------------------

    private val ceilingSpellings = listOf(
        "length",              // OpenAI Chat Completions
        "max_tokens",          // Anthropic Messages
        "MAX_TOKENS",          // Gemini generateContent, verbatim
        "max_output_tokens",   // OpenAI Responses
        "Max-Tokens",          // relay that re-joins the words
        "max_tokens_exceeded",
        "token_limit",
        "output_limit",
        "  max_tokens  ",      // surrounding whitespace
    )

    private val notCeiling = listOf(
        "stop",
        "end_turn",
        "tool_use",
        "tool_calls",
        "stop_sequence",
        "content_filter",
        "safety",
        "network_error",
        "",
        "   ",
    )

    @Test
    fun `every protocol ceiling spelling is a cut`() {
        for (r in ceilingSpellings) {
            assertTrue("expected a ceiling cut: [$r]", nonStreamOutputCeilingCut(r))
        }
    }

    @Test
    fun `a clean finish is never a cut`() {
        for (r in notCeiling) {
            assertFalse("must not be a ceiling cut: [$r]", nonStreamOutputCeilingCut(r))
        }
        assertFalse(nonStreamOutputCeilingCut(null))
    }

    @Test
    fun `the provider-side predicate cannot drift from the tool guard's table`() {
        // The point of delegating: one vocabulary, two consumers. If someone
        // re-implements a local set at the provider layer, this equality breaks
        // on whichever spelling they forgot.
        for (r in ceilingSpellings + notCeiling + listOf(null)) {
            assertEquals(
                "drift at [$r]",
                TruncatedToolCallPolicy.isTruncatedFinish(r),
                nonStreamOutputCeilingCut(r),
            )
        }
    }

    // -- C2: the log line ----------------------------------------------------

    @Test
    fun `the log line states provider, stop reason, size and emptiness`() {
        assertEquals(
            "non-streaming reply hit the output ceiling: provider=Anthropic " +
                "stopReason=max_tokens outputChars=0 emptyText=true",
            nonStreamCeilingCutLogLine("Anthropic", "max_tokens", 0),
        )
        assertEquals(
            "non-streaming reply hit the output ceiling: provider=OpenAI " +
                "stopReason=length outputChars=812 emptyText=false",
            nonStreamCeilingCutLogLine("OpenAI", "length", 812),
        )
    }

    @Test
    fun `the log line never claims a stop reason the provider did not send`() {
        assertTrue(
            nonStreamCeilingCutLogLine("Google", null, 5).contains("stopReason=null"),
        )
    }

    @Test
    fun `the log line is one line — it must not break a log grep`() {
        assertFalse(nonStreamCeilingCutLogLine("OpenAI", "length", 12).contains('\n'))
    }
}
