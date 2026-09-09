package com.rikkaminis.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-mcp-import-unique-fallback] Coverage for the deterministic bare-entry
 * fallback id derivation: two distinct bare entries (pasted in separate
 * imports) must no longer collapse onto the single generic "imported-mcp" id
 * and silently overwrite each other.
 */
class MCPRepositoryDeriveNameTest {

    private fun derive(entry: JSONObject): String =
        MCPRepository.deriveFallbackName(entry)

    @Test
    fun `command basename is used, path components stripped`() {
        assertEquals("mcp-npx", derive(JSONObject().put("command", "npx")))
        assertEquals("mcp-mcp-proxy", derive(JSONObject().put("command", "/usr/local/bin/mcp-proxy")))
        assertEquals("mcp-uvx", derive(JSONObject().put("command", "uvx")))
    }

    @Test
    fun `url host is used when no command`() {
        assertEquals("mcp-example.com", derive(JSONObject().put("url", "https://example.com/api")))
        assertEquals("mcp-mcp.example.com", derive(JSONObject().put("url", "https://mcp.example.com")))
    }

    @Test
    fun `two different bare entries derive distinct ids`() {
        val a = derive(JSONObject().put("command", "npx"))
        val b = derive(JSONObject().put("command", "uvx"))
        assertEquals("mcp-npx", a)
        assertEquals("mcp-uvx", b)
        assertEquals(false, a == b)
    }

    @Test
    fun `command wins over url when both present`() {
        val entry = JSONObject().put("command", "npx").put("url", "https://example.com")
        assertEquals("mcp-npx", derive(entry))
    }

    @Test
    fun `fallback to generic when neither present`() {
        assertEquals("imported-mcp", derive(JSONObject().put("note", "x")))
        assertEquals("imported-mcp", derive(JSONObject()))
    }

    @Test
    fun `unparseable url host falls through to generic`() {
        assertEquals("imported-mcp", derive(JSONObject().put("url", "not a uri")))
    }
}
