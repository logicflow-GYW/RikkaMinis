package com.rikkaminis.app.mcp.oauth

import android.content.Context
import com.rikkaminis.app.data.repository.MCPRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [da1024-D] deep-audit-1024 ISSUE-04 — MCP OAuth credential/bridge lifecycle.
 *
 * Two-layer coverage, deliberately (see the FIX-D report §5):
 *   - the DECISION is a pure function so it can be driven with literals here;
 *   - the WIRING (which functions call what, and that no path treats
 *     "removed only `secret::<id>`" as "cleaned up") is asserted on the source
 *     by scripts/scan/mcp_oauth_lifecycle_check.py, and the entry-point
 *     SIGNATURE is pinned here by reflection so a rename cannot satisfy one
 *     half while breaking the other.
 *
 * [MCPOAuthStore] itself cannot be exercised off-device: it needs
 * EncryptedSharedPreferences and the guest bind-mount directory behind
 * `/var/minis/mcp-servers/oauth`. No Context is faked here on purpose.
 */
class MCPOAuthLifecycleTest {

    private fun configured() = MCPOAuthConfig(
        clientId = "client-abc",
        authorizationEndpoint = "https://auth.example.com/authorize",
        tokenEndpoint = "https://auth.example.com/token",
    )

    /** Reachable through JSON import: fromJson keeps a partially filled block. */
    private fun partial() = MCPOAuthConfig(
        clientId = "client-abc",
        authorizationEndpoint = "",
        tokenEndpoint = "",
    )

    // -- decision matrix -----------------------------------------------------

    @Test
    fun `url transport without an oauth block purges`() {
        assertEquals(
            MCPOAuthStore.ReconcileAction.PURGE,
            MCPOAuthStore.decideReconcileAction(urlTransport = true, oauth = null, hasTokens = true),
        )
        assertEquals(
            MCPOAuthStore.ReconcileAction.PURGE,
            MCPOAuthStore.decideReconcileAction(urlTransport = true, oauth = null, hasTokens = false),
        )
    }

    @Test
    fun `stdio transport purges even with a fully configured oauth block`() {
        // THE regression: switch an authorized HTTP server to STDIO — tokens
        // and the plaintext bridge file must not survive it.
        assertEquals(
            MCPOAuthStore.ReconcileAction.PURGE,
            MCPOAuthStore.decideReconcileAction(urlTransport = false, oauth = configured(), hasTokens = true),
        )
    }

    @Test
    fun `partially configured oauth purges`() {
        assertEquals(
            MCPOAuthStore.ReconcileAction.PURGE,
            MCPOAuthStore.decideReconcileAction(urlTransport = true, oauth = partial(), hasTokens = true),
        )
    }

    @Test
    fun `authorized url transport oauth refreshes the bridge instead of purging`() {
        assertEquals(
            MCPOAuthStore.ReconcileAction.REFRESH_BRIDGE,
            MCPOAuthStore.decideReconcileAction(urlTransport = true, oauth = configured(), hasTokens = true),
        )
    }

    @Test
    fun `configured but not yet authorized keeps the pending client secret`() {
        // The edit form stores the secret BEFORE the browser round-trip, and
        // signOut() promises to keep it. Purging here would break both.
        assertEquals(
            MCPOAuthStore.ReconcileAction.KEEP,
            MCPOAuthStore.decideReconcileAction(urlTransport = true, oauth = configured(), hasTokens = false),
        )
    }

    @Test
    fun `usableOAuth only accepts a configured url transport`() {
        assertFalse(MCPOAuthStore.usableOAuth(urlTransport = false, oauth = configured()))
        assertFalse(MCPOAuthStore.usableOAuth(urlTransport = true, oauth = null))
        assertFalse(MCPOAuthStore.usableOAuth(urlTransport = true, oauth = partial()))
        assertTrue(MCPOAuthStore.usableOAuth(urlTransport = true, oauth = configured()))
    }

    // -- transport shape -----------------------------------------------------

    @Test
    fun `persistsAsUrlTransport mirrors the persisted entry branch order`() {
        assertTrue(
            MCPRepository.persistsAsUrlTransport(
                MCPRepository.MCPServerConfig(id = "http", url = "https://mcp.example.com/mcp")
            )
        )
        assertFalse(
            MCPRepository.persistsAsUrlTransport(
                MCPRepository.MCPServerConfig(id = "stdio", command = "npx", args = listOf("-y", "server"))
            )
        )
        // Both transports set: entryToJson writes the STDIO branch, so the guest
        // never sees a URL entry — it must not keep URL-transport credentials.
        assertFalse(
            MCPRepository.persistsAsUrlTransport(
                MCPRepository.MCPServerConfig(id = "both", command = "npx", url = "https://mcp.example.com/mcp")
            )
        )
        // No transport at all (a blank form entry).
        assertFalse(MCPRepository.persistsAsUrlTransport(MCPRepository.MCPServerConfig(id = "none")))
        assertFalse(MCPRepository.persistsAsUrlTransport(MCPRepository.MCPServerConfig(id = "blank", url = "")))
    }

    // -- entry point ---------------------------------------------------------

    @Test
    fun `convergence entry point keeps its signature`() {
        val method = MCPOAuthStore::class.java.getMethod(
            "reconcileForServer",
            Context::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            MCPOAuthConfig::class.java,
            String::class.java,
        )
        // Unit-returning store function = JVM void; the data layer calls it for
        // its side effects only.
        assertEquals(Void.TYPE, method.returnType)
        // Self-contained contract: the caller passes config facts, never
        // credentials (a secret/token parameter would let a caller hand a stale
        // copy — exactly what this fix removed).
        val paramTypes = method.parameterTypes.map { it.name }
        assertEquals(
            listOf(
                "android.content.Context",
                "java.lang.String",
                "boolean",
                "com.rikkaminis.app.mcp.oauth.MCPOAuthConfig",
                "java.lang.String",
            ),
            paramTypes,
        )
    }

    @Test
    fun `canonical resource for the bridge matches the authorize flow helper`() {
        // The bridge's `resource` must be the same RFC 8707 indicator the token
        // was issued for, or the guest refresh is rejected. Reconcile reuses the
        // authorize-flow helper rather than re-deriving it.
        assertEquals(
            "https://mcp.example.com/mcp",
            McpPkce.canonicalResourceUri("https://mcp.example.com/mcp/"),
        )
        assertEquals(
            "https://mcp.example.com/mcp",
            McpPkce.canonicalResourceUri("https://mcp.example.com/mcp#frag"),
        )
        assertNull(McpPkce.canonicalResourceUri("not a url"))
        assertNull(McpPkce.canonicalResourceUri(null))
    }

    // -- edit sheet wiring ([dv14-B3]) ---------------------------------------

    /**
     * The orphan-credential half lives in the edit sheet's composable body, so
     * it cannot be driven off-device (no Context is faked here on purpose, and
     * the store needs EncryptedSharedPreferences). Assert on the SOURCE of the
     * declaration that carries the decision instead — the same technique as
     * PhotosImportShapeTest / SessionPathResolutionTest, so CI runs it.
     */
    private val editSheet =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt"

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, editSheet).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readEditSheet(): String {
        val f = File(repoRoot(), editSheet)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    @Test
    fun `the open edit sheet closes itself when the entry disappears`() {
        val lines = readEditSheet().lines()
        val start = lines.indexOfFirst { it.contains("LaunchedEffect(captured.id, servers) {") }
        assertTrue("close-on-disappear effect missing", start >= 0)
        val effect = lines.subList(start, minOf(start + 4, lines.size)).joinToString("\n")
        assertTrue(
            "the effect must re-launch on the LISTING, not only on the id",
            effect.contains("LaunchedEffect(captured.id, servers) {"),
        )
        assertTrue(
            "the effect must test the live listing and clear the selection",
            effect.contains("if (servers.none { it.id == captured.id }) editServer = null"),
        )
    }

    @Test
    fun `a deleted entry keeps showing the captured entry instead of the add flow`() {
        assertTrue(
            "the tap-time fallback must survive: without it a deleted entry flips the sheet into the add flow",
            readEditSheet().contains("servers.find { it.id == captured.id } ?: captured"),
        )
    }

    @Test
    fun `no credential write is aimed at an id that left the listing`() {
        val src = readEditSheet()
        val callSites = Regex("MCPOAuthStore\\.setClientSecret\\(").findAll(src).map { it.range.first }.toList()
        assertEquals("the edit sheet is the only place that stores a client secret", 2, callSites.size)
        for (at in callSites) {
            val handlerStart = src.lastIndexOf("onClick = {", at)
            assertTrue("setClientSecret outside a button handler", handlerStart in 0 until at)
            val handler = src.substring(handlerStart, at)
            assertTrue(
                "the handler must refuse an id that is no longer listed",
                handler.contains("if (isEdit && mcpRepository.servers.value.none { it.id =="),
            )
            assertTrue("the refusal must abort the handler", handler.contains("return@MinisTextButton"))
            assertTrue("the refusal must be logged", handler.contains("AppLogger.warning("))
        }
    }

    @Test
    fun `the form fields stay keyed on the entry so a reopen re-seeds them`() {
        // [U2] 17 keyed field states — the orphan fix must not disturb them.
        // [meta-audit-1028 D4 F-04] adds three more, all keyed on purpose:
        //   oauthSecretLoad (tri-state PENDING/LOADED/UNREADABLE),
        //   oauthSecretEdited (the user-typed-intent bit), oauthAuthorizedSeed.
        // A reopen MUST re-seed them: a stale PENDING or a stale intent bit
        // carried across entries is exactly the failure the secret load gate
        // exists to prevent (see SettingsSecretWiringTest).
        assertEquals(20, Regex("remember\\(editServer\\)").findAll(readEditSheet()).count())
    }

    @Test
    fun `editServer is cleared only by the close effect or a dismissal callback`() {
        val src = readEditSheet()
        val occurrences = Regex("editServer\\s*=\\s*null").findAll(src).map { it.range.first }.toList()
        assertTrue("the sheet must have its dismissal paths", occurrences.size >= 4)
        for (at in occurrences) {
            // Passed as an argument to MCPAddSheet, not a mutation.
            if (Regex("^editServer\\s*=\\s*null\\s*,").containsMatchIn(src.substring(at))) continue
            val before = src.substring(maxOf(0, at - 600), at)
            val inEffect = before.contains("LaunchedEffect(captured.id, servers) {") &&
                !before.substringAfterLast("LaunchedEffect(captured.id, servers) {").contains("\n        }")
            val inDismissal = before.contains("onDismiss") || before.contains("onRequestDelete")
            assertTrue("editServer = null outside the close effect / a dismissal callback", inEffect || inDismissal)
        }
    }
}
