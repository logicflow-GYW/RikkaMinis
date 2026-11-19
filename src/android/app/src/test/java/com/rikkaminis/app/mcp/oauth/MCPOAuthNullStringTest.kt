package com.rikkaminis.app.mcp.oauth

import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R3-B F-17] `JSONObject.optString(key)` returns the four-character literal
 * `"null"` for a JSON null — it only falls back to its default when the key is
 * ABSENT. The package read every nullable string that way, so:
 *
 *  - `"scopes": null` became a scope literally named "null" (`&scope=null` on
 *    the authorization request);
 *  - `"redirect_uri": null` became a redirect URI that `URI(...)` parses with
 *    no host, failing authorization with a confusing loopback complaint;
 *  - `"refresh_token": null` was persisted as the token `"null"` into the
 *    encrypted store and the in-guest bridge, so the next refresh presented it
 *    and the server answered `invalid_grant`.
 *
 * The helper is [optStringOrNull]; the five sites the report names plus the
 * alias walk inside `firstNonBlank` (which covers client_id /
 * authorization_endpoint / token_endpoint / redirect_uri) and `mode` now use
 * it — a grep for the old shape is the wiring arm below.
 */
class MCPOAuthNullStringTest {

    // ── the helper ──────────────────────────────────────────────────────

    @Test
    fun `a JSON null reads as null not as the four character literal`() {
        val o = JSONObject("""{"k": null}""")
        assertTrue("the key is present and carries JSON null", o.isNull("k"))
        assertNull("a JSON null must not become the 4-char literal", o.optStringOrNull("k"))
    }

    /**
     * Why the helper branches on [JSONObject.isNull] instead of on the text
     * `optString` returns: on Android `optString(key, fallback)` coerces a
     * `JSONObject.NULL` through `JSON.toString(Object)` → `value.toString()`,
     * i.e. it answers the literal `"null"` and never reaches the fallback. The
     * Maven `org.json` this JVM suite runs returns the fallback instead, which
     * is exactly why the defect never shows up in a JVM test that reads
     * `optString` directly — and why the discriminator has to be `isNull`.
     */
    @Test
    fun `the JSON null marker is the literal the Android optString would return`() {
        assertEquals("null", JSONObject.NULL.toString())
        assertEquals(
            "a real string is untouched by the same path",
            "keep",
            (JSONObject("""{"k": "keep"}""").get("k") as String),
        )
    }

    @Test
    fun `an absent key reads as null`() {
        assertNull(JSONObject("""{}""").optStringOrNull("k"))
    }

    @Test
    fun `a blank value reads as null`() {
        assertNull(JSONObject("""{"k": ""}""").optStringOrNull("k"))
        assertNull(JSONObject("""{"k": "   "}""").optStringOrNull("k"))
    }

    @Test
    fun `a real value is preserved verbatim`() {
        assertEquals("keep", JSONObject("""{"k": "keep"}""").optStringOrNull("k"))
        assertEquals(
            "a value that merely CONTAINS null must survive",
            "nullify-me",
            JSONObject("""{"k": "nullify-me"}""").optStringOrNull("k"),
        )
    }

    /**
     * The platform-independent arm: a JSON null must win over the CALLER's
     * fallback, not over the platform's coercion of it. Android's optString
     * answers "null" here and the Maven jar answers "FB" — a helper that just
     * chains `optString(...).ifBlank { null }` therefore reads the fallback on
     * one platform and the literal on the other. Branching on isNull is what
     * makes both say null.
     */
    @Test
    fun `a JSON null beats a non-blank fallback`() {
        val o = JSONObject("""{"k": null}""")
        assertEquals(
            "the JVM jar answers the fallback (Android answers \"null\") — either " +
                "way it is not the caller's job to notice",
            "FB",
            o.optString("k", "FB"),
        )
        assertNull("a JSON null must beat the fallback", o.optStringOrNull("k", "FB"))
    }

    // ── the config parse ────────────────────────────────────────────────

    @Test
    fun `null scopes and redirect_uri parse as null`() {
        val config = MCPOAuthConfig.fromJson(
            JSONObject(
                """
                {"client_id": "cid",
                 "authorization_endpoint": "https://auth.example.com/authorize",
                 "token_endpoint": "https://auth.example.com/token",
                 "scopes": null,
                 "redirect_uri": null}
                """.trimIndent()
            )
        )
        assertNotNull("the block still has its endpoints — it is configuration", config)
        config!!
        assertNull("scopes must be null, not \"null\"", config.scopes)
        assertNull("redirect_uri must be null, not \"null\"", config.redirectUri)
        assertEquals("cli", "cid", config.clientId)
    }

    @Test
    fun `a null mode falls back to static`() {
        val config = MCPOAuthConfig.fromJson(
            JSONObject("""{"mode": null, "client_id": "cid"}""")
        )
        assertEquals("static", config!!.mode)
    }

    @Test
    fun `real scopes and redirect_uri survive the parse`() {
        val config = MCPOAuthConfig.fromJson(
            JSONObject(
                """
                {"client_id": "cid", "scopes": "openid email",
                 "redirect_uri": "http://127.0.0.1:54546/callback"}
                """.trimIndent()
            )
        )!!
        assertEquals("openid email", config.scopes)
        assertEquals("http://127.0.0.1:54546/callback", config.redirectUri)
    }

    @Test
    fun `a null client id does not become the literal null`() {
        val config = MCPOAuthConfig.fromJson(
            JSONObject(
                """
                {"client_id": null,
                 "authorization_endpoint": "https://auth.example.com/authorize",
                 "token_endpoint": "https://auth.example.com/token"}
                """.trimIndent()
            )
        )
        assertNotNull(config)
        assertEquals("", config!!.clientId)
        assertFalse(
            "a server whose client id is JSON null is NOT configured; the literal " +
                "\"null\" used to satisfy isConfigured",
            config.isConfigured,
        )
    }

    @Test
    fun `an oauth block of nothing but nulls is not configuration`() {
        assertNull(
            "every field null means there is no oauth configuration here",
            MCPOAuthConfig.fromJson(
                JSONObject(
                    """{"client_id": null, "authorization_endpoint": null, "token_endpoint": null}"""
                )
            ),
        )
    }

    // ── wiring: the same shape is gone from the other two files ─────────

    @Test
    fun `no bare optString-ifBlank read survives in the package`() {
        // Exactly one occurrence is allowed and it is the helper's own body:
        // every other read has to go through it, or a JSON null becomes the
        // token "null" there.
        val shape = Regex("""optString\([^)]*\)\.ifBlank""")
        val expected = mapOf(
            "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthConfig.kt" to 1,
            "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthController.kt" to 0,
            "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthStore.kt" to 0,
        )
        for ((rel, want) in expected) {
            assertEquals(
                "$rel has the wrong number of pre-fix-shaped nullable reads " +
                    "(only the helper itself may keep the shape)",
                want,
                shape.findAll(readRepoFile(rel)).count(),
            )
        }
    }

    @Test
    fun `the refresh token reads go through the helper`() {
        val controller = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthController.kt"
        )
        assertEquals(
            "both token-exchange sites must read refresh_token through the helper",
            2,
            Regex("""optStringOrNull\("refresh_token"\)""").findAll(controller).count(),
        )
        val store = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthStore.kt"
        )
        assertEquals(
            "both store sites (tokens() and the bridge snapshot) must use the helper",
            2,
            Regex("""optStringOrNull\("refresh_token"\)""").findAll(store).count(),
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}