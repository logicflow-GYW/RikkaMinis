package com.rikkaminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WAVE-1118 D8-F03] Tool arguments that arrive as an explicit JSON `null`.
 *
 * > `{"overwrite_reason": null}` is legal tool-call JSON ...
 *
 * The platform difference under test, stated once so it cannot be lost:
 * JSON-java's `optString(name, fallback)` returns the **fallback** for
 * `JSONObject.NULL`; Android's returns the literal `"null"`. On the device
 * (where the tool layer runs) every `isBlank()` guard downstream of a bare
 * `optString` was therefore dead code, and `overwrite_reason = "null"` walked
 * past `AppendOnlyWriteGuard`'s "say why" gate.
 *
 * `isNull` means "null or absent" in BOTH implementations — that is why the
 * reader side is the fix, and why [AndroidLikeJson] below is not a lie: it is
 * the documented Android answer, used to prove the reader does not depend on
 * whichever `optString` it happens to run against. Every expectation is a
 * literal.
 */
class ToolArgsJsonNullTest {

    /**
     * `org.json` as Android implements it, for the two readers that matter:
     * `optString` stringifies `JSONObject.NULL` to `"null"`, and `optInt`
     * already answers 0 for it (both implementations do).
     */
    private class AndroidLikeJson(private val delegate: JSONObject) : JSONObject() {
        override fun isNull(name: String): Boolean = delegate.isNull(name)
        override fun has(name: String): Boolean = delegate.has(name)
        override fun optString(name: String, fallback: String): String =
            // `isNull` is true for an absent key as well; Android's optString
            // answers the fallback there and the literal "null" only for a key
            // that is present with a null value.
            if (delegate.has(name) && delegate.isNull(name)) "null"
            else delegate.optString(name, fallback)
        override fun optString(name: String): String = optString(name, "")
        override fun optInt(name: String, fallback: Int): Int = delegate.optInt(name, fallback)
        override fun optInt(name: String): Int = delegate.optInt(name)
    }

    private fun androidLike(json: String) = AndroidLikeJson(JSONObject(json))

    // ── optArgString ──────────────────────────────────────────────────────

    @Test
    fun `an explicit null reads as the fallback, not as the string null`() {
        assertEquals("", androidLike("""{"overwrite_reason":null}""").optArgString("overwrite_reason", ""))
    }

    @Test
    fun `a bare optString on the same input is what the guard was reading`() {
        // Ground truth for the defect, kept as an assertion on the shape we
        // replaced: this is the value AppendOnlyWriteGuard used to receive.
        assertEquals("null", androidLike("""{"overwrite_reason":null}""").optString("overwrite_reason", ""))
    }

    @Test
    fun `an absent key still answers the fallback`() {
        assertEquals("all", JSONObject("""{}""").optArgString("scope", "all"))
        assertEquals("all", androidLike("""{}""").optArgString("scope", "all"))
    }

    @Test
    fun `a real value passes through unchanged under both implementations`() {
        assertEquals("all", androidLike("""{"scope":"all"}""").optArgString("scope", "x"))
        assertEquals("shared", androidLike("""{"scope":"shared"}""").optArgString("scope", "x"))
        assertEquals("0", androidLike("""{"scope":0}""").optArgString("scope", "x"))
    }

    @Test
    fun `an explicit null overwrite_reason is refused as unjustified`() {
        // End-to-end of the two real pieces: reader → guard decision. With the
        // bare optString this reason is "null" and the decision is Allow.
        val args = androidLike("""{"overwrite_reason":null}""")
        val decision = AppendOnlyWriteGuard.decide(
            path = "/var/minis/shared/backlog.md",
            append = false,
            overwrite = true,
            overwriteReason = args.optArgString("overwrite_reason", ""),
            exists = true,
            currentBytes = 2_000_000L,
            incomingBytes = 1_000L,
        )
        assertEquals(
            AppendOnlyWriteGuard.RefusalReason.OVERWRITE_NOT_JUSTIFIED,
            (decision as AppendOnlyWriteGuard.Decision.Refuse).reason,
        )
    }

    @Test
    fun `a written reason still gets through`() {
        val args = androidLike("""{"overwrite_reason":"rewriting the whole ledger by request"}""")
        assertEquals(
            AppendOnlyWriteGuard.Decision.Allow,
            AppendOnlyWriteGuard.decide(
                path = "/var/minis/shared/backlog.md",
                append = false,
                overwrite = true,
                overwriteReason = args.optArgString("overwrite_reason", ""),
                exists = true,
                currentBytes = 2_000_000L,
                incomingBytes = 1_000L,
            ),
        )
    }

    // ── optArgIntOrNull ───────────────────────────────────────────────────

    @Test
    fun `lines null is not a request for zero lines`() {
        // `has("lines")` is true for an explicit null, and optInt answers 0 —
        // file_read then returned "0 lines" for a caller that asked for the
        // whole file. Locally reproducible: this branch is identical in both
        // org.json implementations.
        assertNull(JSONObject("""{"lines":null}""").optArgIntOrNull("lines"))
        assertNull(androidLike("""{"lines":null}""").optArgIntOrNull("lines"))
    }

    @Test
    fun `an absent lines key is still no paging`() {
        assertNull(JSONObject("""{"path":"/a.md"}""").optArgIntOrNull("lines"))
    }

    @Test
    fun `a real line count survives`() {
        assertEquals(40, JSONObject("""{"lines":40}""").optArgIntOrNull("lines"))
        assertEquals(1, JSONObject("""{"lines":1}""").optArgIntOrNull("lines"))
        assertEquals(0, JSONObject("""{"lines":0}""").optArgIntOrNull("lines"))
    }
}
