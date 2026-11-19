package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FULLSCAN-1119 A / F-01 + F-02] MCP credential carriers.
 *
 * Two defects, one file of rules:
 *
 *  * **F-02 (export)** — the credentials-free payload judged `args` with a
 *    full-match `--key=value` regex and only looked at a URL when it contained
 *    `?`. So `--token abc` / `--api-key abc def` and `https://user:pass@host/`
 *    rode into every unattended auto backup in the clear.
 *  * **F-01 (import)** — that same payload was then imported verbatim, so a
 *    restore over a same-id server replaced the live `Authorization` header /
 *    env token with `""`.
 *
 * Every expected value below is a hand-written literal (the fake credentials
 * are the task's own `"Bearer live"` / `"secret"`). Nothing is derived from the
 * code under test, so a mutated rule cannot move an assertion to stay green.
 */
class McpCredentialFieldsTest {

    // ── F-02: args equivalence classes ───────────────────────────────────

    @Test
    fun equalsFormIsBlankedButKeepsTheFlag() {
        assertEquals(
            listOf("--token="),
            McpCredentialFields.redactSecretArgs(listOf("--token=abc")),
        )
        assertEquals(
            listOf("-y", "server", "--api-key="),
            McpCredentialFields.redactSecretArgs(listOf("-y", "server", "--api-key=abc")),
        )
    }

    @Test
    fun spaceSeparatedFormIsBlanked() {
        assertEquals(
            listOf("--token", ""),
            McpCredentialFields.redactSecretArgs(listOf("--token", "abc")),
        )
        // The value ran to the next flag: blanking only the first token would
        // leave `def` in the backup.
        assertEquals(
            listOf("--token", "", "", "--port=8080"),
            McpCredentialFields.redactSecretArgs(listOf("--token", "abc", "def", "--port=8080")),
        )
        assertEquals(
            listOf("--password", ""),
            McpCredentialFields.redactSecretArgs(listOf("--password", "hunter2")),
        )
    }

    @Test
    fun benignFlagsAreUntouched() {
        // `--port 8080` is not a credential; `--tokenize` / `--tokenizer` are
        // not the `token` flag (a substring test would destroy them).
        assertEquals(
            listOf("--help"),
            McpCredentialFields.redactSecretArgs(listOf("--help")),
        )
        assertEquals(
            listOf("--port", "8080"),
            McpCredentialFields.redactSecretArgs(listOf("--port", "8080")),
        )
        assertEquals(
            listOf("--tokenize", "x", "--tokenizer", "y"),
            McpCredentialFields.redactSecretArgs(listOf("--tokenize", "x", "--tokenizer", "y")),
        )
        assertEquals(
            listOf("--token=", "--tokenize", "x"),
            McpCredentialFields.redactSecretArgs(listOf("--token=abc", "--tokenize", "x")),
        )
    }

    @Test
    fun redactionIsIdempotent() {
        val once = McpCredentialFields.redactSecretArgs(listOf("--token", "abc", "--api-key=x"))
        assertEquals(once, McpCredentialFields.redactSecretArgs(once))
    }

    // ── F-02: url carriers ───────────────────────────────────────────────

    @Test
    fun userinfoPasswordIsRedactedAndHostSurvives() {
        assertEquals(
            "https://[redacted]@host/path",
            McpCredentialFields.redactUrlCredentials("https://user:pass@host/path"),
        )
        assertEquals(
            "http://[redacted]@10.0.0.5:8080/sse",
            McpCredentialFields.redactUrlCredentials("http://admin:s3cret@10.0.0.5:8080/sse"),
        )
    }

    @Test
    fun queryValuesAreRedactedWithTheirLength() {
        assertEquals(
            "https://host/path?key=[redacted:6]&mode=fast",
            McpCredentialFields.redactUrlCredentials("https://host/path?key=secret&mode=fast"),
        )
        // Both carriers at once: the userinfo AND the query value.
        assertEquals(
            "https://[redacted]@host/sse?token=[redacted:3]&x=1",
            McpCredentialFields.redactUrlCredentials("https://u:p@host/sse?token=abc&x=1"),
        )
    }

    @Test
    fun urlWithoutCredentialsIsUntouched() {
        assertEquals(
            "https://host/path?a=1&b=2",
            McpCredentialFields.redactUrlCredentials("https://host/path?a=1&b=2"),
        )
        assertEquals(
            "https://host/path",
            McpCredentialFields.redactUrlCredentials("https://host/path"),
        )
    }

    // ── F-01: merge a redacted payload onto the live server ──────────────

    /** The live server on THIS device (the values the user typed). */
    private fun liveEntry() = JSONObject(
        """
        {
          "url": "https://mcp.example.com/sse?key=LIVEKEY",
          "headers": { "Authorization": "Bearer live", "X-Trace": "t-1" },
          "note": "live note",
          "enabled": true
        }
        """.trimIndent()
    )

    /** The same server as it comes out of a credentials-free export. */
    private fun redactedEntry() = JSONObject(
        """
        {
          "url": "https://mcp.example.com/sse?key=[redacted:7]",
          "headers": { "Authorization": "", "X-Trace": "" },
          "note": "backup note",
          "enabled": false
        }
        """.trimIndent()
    )

    private fun wrapper(entry: JSONObject, id: String = "srv"): JSONObject =
        JSONObject().put("mcpServers", JSONObject().put(id, entry))

    @Test
    fun headersFallBackToTheLiveValuesWhileShapeComesFromTheBackup() {
        val incoming = wrapper(redactedEntry())
        val local = wrapper(liveEntry())
        assertTrue(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, local))

        val merged = incoming.getJSONObject("mcpServers").getJSONObject("srv")
        assertEquals("Bearer live", merged.getJSONObject("headers").getString("Authorization"))
        assertEquals("t-1", merged.getJSONObject("headers").getString("X-Trace"))
        // Non-credential fields still come from the BACKUP — a restore is a
        // restore, only credential slots are protected.
        assertEquals("backup note", merged.getString("note"))
        assertFalse(merged.getBoolean("enabled"))
        // The redacted URL is replaced wholesale by the live one.
        assertEquals("https://mcp.example.com/sse?key=LIVEKEY", merged.getString("url"))
    }

    @Test
    fun envAndSpaceSeparatedArgsAreRestored() {
        val incoming = wrapper(
            JSONObject(
                """
                {
                  "command": "npx",
                  "args": ["-y", "some-server", "--token", "", "--port", "8080"],
                  "env": { "TOKEN": "", "LOG_LEVEL": "debug" }
                }
                """.trimIndent()
            )
        )
        val local = wrapper(
            JSONObject(
                """
                {
                  "command": "npx",
                  "args": ["-y", "some-server", "--token", "secret", "--port", "9999"],
                  "env": { "TOKEN": "secret", "LOG_LEVEL": "info" }
                }
                """.trimIndent()
            )
        )
        assertTrue(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, local))
        val merged = incoming.getJSONObject("mcpServers").getJSONObject("srv")

        assertEquals("secret", merged.getJSONObject("env").getString("TOKEN"))
        // Non-credential env value follows the BACKUP.
        assertEquals("debug", merged.getJSONObject("env").getString("LOG_LEVEL"))

        val args = merged.getJSONArray("args")
        assertEquals("--token", args.getString(2))
        assertEquals("secret", args.getString(3))
        // The non-credential flag keeps the BACKUP's value (9999, not the live
        // 8080) — only the credential slot falls back.
        assertEquals("--port", args.getString(4))
        assertEquals("8080", args.getString(5))
    }

    @Test
    fun aPayloadThatCarriesRealValuesIsNotOverwrittenByTheLiveOnes() {
        // includeSecrets=true direction: incoming already has real values, the
        // merge must leave them alone (the caller skips the merge entirely, but
        // the function itself must not clobber a real value either).
        val incoming = wrapper(
            JSONObject(
                """{"headers":{"Authorization":"Bearer from-backup"}}""".trimIndent()
            )
        )
        val local = wrapper(liveEntry())
        McpCredentialFields.mergeRedactedMcpServerPayload(incoming, local)
        assertEquals(
            "Bearer from-backup",
            incoming.getJSONObject("mcpServers").getJSONObject("srv")
                .getJSONObject("headers").getString("Authorization"),
        )
    }

    @Test
    fun mismatchedOrMissingShapesAreANoOp() {
        // Different id ⇒ not the same server; nothing to protect.
        val incoming = wrapper(redactedEntry(), id = "other")
        val local = wrapper(liveEntry(), id = "srv")
        assertFalse(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, local))
        assertEquals(
            "",
            incoming.getJSONObject("mcpServers").getJSONObject("other")
                .getJSONObject("headers").getString("Authorization"),
        )
        // Bare entry (no wrapper) is not the export shape.
        assertFalse(
            McpCredentialFields.mergeRedactedMcpServerPayload(JSONObject(), JSONObject())
        )
    }

    @Test
    fun placeholderRuleMatchesOnlyBlankOrMarkerValues() {
        assertTrue(McpCredentialFields.isPlaceholder(null))
        assertTrue(McpCredentialFields.isPlaceholder(""))
        assertTrue(McpCredentialFields.isPlaceholder("   "))
        assertTrue(McpCredentialFields.isPlaceholder("[redacted:7]"))
        assertTrue(McpCredentialFields.isPlaceholder("[redacted]"))
        assertFalse(McpCredentialFields.isPlaceholder("Bearer live"))
        assertFalse(McpCredentialFields.isPlaceholder("secret"))
        // A value that merely STARTS with the marker text is a real value.
        assertFalse(McpCredentialFields.isPlaceholder("[redacted:7]abc"))
    }
}
