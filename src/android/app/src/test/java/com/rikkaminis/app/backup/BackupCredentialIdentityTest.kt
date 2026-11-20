package com.rikkaminis.app.backup

import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.data.repository.applyImportInstanceFields
import com.rikkaminis.app.data.repository.readImportInstanceFields
import com.rikkaminis.app.data.repository.shouldKeepProviderConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCredentialIdentityTest {
    private fun web(password: String = "LOCAL-KEY") = WebDavConfig("https://dav.example/root/alice", "alice", password, "old")
    private fun provider() = ProviderInstance(id = "local", label = "work", providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey, customBaseURL = "https://api.example/tenant", appendV1Suffix = true)
    private fun keep(payload: JSONObject, hasKey: Boolean = true): Boolean {
        val local = provider()
        return shouldKeepProviderConnection(local, applyImportInstanceFields(local, readImportInstanceFields(payload)), payload, hasKey)
    }
    @Test fun webdavSameServiceCanRestoreDirectoryWithoutMovingPassword() {
        val payload = JSONObject().put("url", "https://DAV.example:443/root/alice/").put("username", "alice").put("path", "new")
        val plan = ConfigBackup.resolveWebDavRestore(payload, web())
        assertEquals("LOCAL-KEY", plan.config!!.password); assertEquals("new", plan.config!!.path)
    }
    @Test fun webdavEndpointSchemeTenantOrAccountChangeKeepsWholeLocalConnection() {
        for ((url, user) in listOf("https://other.example/root/alice" to "alice", "http://dav.example/root/alice" to "alice",
            "https://dav.example/root/bob" to "alice", "https://dav.example/root/alice" to "bob")) {
            val plan = ConfigBackup.resolveWebDavRestore(JSONObject().put("url", url).put("username", user).put("path", "new"), web())
            assertEquals(web(), plan.config); assertTrue(plan.notice.orEmpty().contains("保留"))
        }
    }
    @Test fun webdavBackupCredentialAndFreshInstallKeepTheirExistingRules() {
        val payload = JSONObject().put("url", "https://other.example").put("username", "bob").put("password", "BACKUP")
        assertEquals("BACKUP", ConfigBackup.resolveWebDavRestore(payload, web()).config!!.password)
        payload.remove("password")
        assertEquals("https://other.example", ConfigBackup.resolveWebDavRestore(payload, null).config!!.url)
    }
    @Test fun providerComparesEffectiveEndpointNotRawSuffix() {
        assertFalse(keep(JSONObject().put("customBaseURL", "https://API.example:443/tenant/v1/").put("appendV1Suffix", false)))
    }
    @Test fun providerCrossServiceUnknownAndProtocolChangesKeepLocalConnection() {
        for (payload in listOf(JSONObject().put("customBaseURL", "https://other.example/tenant"),
            JSONObject().put("customBaseURL", "http://api.example/tenant"),
            JSONObject().put("customBaseURL", "https://api.example/other-tenant"),
            JSONObject().put("customBaseURL", "not a URL"),
            JSONObject().put("customBaseURL", "https://api.example/tenant").put("azureMode", true),
            JSONObject().put("customBaseURL", "https://api.example/tenant").put("credentialType", "oauth"), JSONObject())) {
            assertTrue(payload.toString(), keep(payload))
        }
    }
    @Test fun providerBackupKeyOrNoLocalKeyDoesNotBlockRestore() {
        assertFalse(keep(JSONObject().put("customBaseURL", "https://other.example").put("apiKey", "QkFDS1VQ")))
        assertFalse(keep(JSONObject().put("customBaseURL", "https://other.example"), hasKey = false))
    }
    private fun wrap(entry: JSONObject) = JSONObject().put("mcpServers", JSONObject().put("s", entry))
    private fun http(url: String, credential: String) = JSONObject().put("url", url)
        .put("headers", JSONObject().put("Authorization", credential)).put("note", "local")
    @Test fun mcpSameEndpointFillsHeaderAndRedactedQueryWithoutLosingBackupShape() {
        val incoming = wrap(http("https://mcp.example/tenant/sse?key=[redacted:3]", "").put("note", "backup"))
        assertTrue(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, wrap(http("https://mcp.example/tenant/sse?key=KEY", "LOCAL"))))
        val restored = incoming.getJSONObject("mcpServers").getJSONObject("s")
        assertEquals("LOCAL", restored.getJSONObject("headers").getString("Authorization")); assertEquals("backup", restored.getString("note"))
    }
    @Test fun mcpEndpointTransportAndTenantChangeKeepsWholeLocalEntry() {
        val local = http("https://mcp.example/tenant/sse", "LOCAL")
        for (entry in listOf(http("https://other.example/tenant/sse", ""), http("http://mcp.example/tenant/sse", ""),
            http("https://mcp.example/other/sse", ""), JSONObject().put("command", "npx").put("env", JSONObject().put("TOKEN", "")))) {
            // Transport changes may move the carrier too; preserve if any live
            // credential would otherwise be retained or refilled.
            entry.put("headers", JSONObject().put("Authorization", ""))
            val incoming = wrap(entry)
            assertFalse(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, wrap(local)))
            assertEquals(local.toString(), incoming.getJSONObject("mcpServers").getJSONObject("s").toString())
        }
    }
    @Test fun mcpStdioDifferentPackageOrEndpointCannotInheritLocalToken() {
        val local = JSONObject("""{"command":"npx","args":["-y","same-server","--endpoint","https://local"],"env":{"TOKEN":"LOCAL"}}""")
        for (args in listOf(listOf("-y", "other-server", "--endpoint", "https://local"),
            listOf("-y", "same-server", "--endpoint", "https://other"))) {
            val incoming = wrap(JSONObject().put("command", "npx").put("args", org.json.JSONArray(args)).put("env", JSONObject().put("TOKEN", "")))
            assertFalse(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, wrap(local)))
            assertEquals(local.toString(), incoming.getJSONObject("mcpServers").getJSONObject("s").toString())
        }
    }
    @Test fun mcpNoLocalSecretAndExplicitBackupCredentialDoNotNeedDestinationFallback() {
        val incoming = wrap(http("https://other.example/sse", "BACKUP"))
        assertTrue(McpCredentialFields.mergeRedactedMcpServerPayload(incoming, wrap(http("https://local.example/sse", "LOCAL"))))
        assertEquals("BACKUP", incoming.getJSONObject("mcpServers").getJSONObject("s").getJSONObject("headers").getString("Authorization"))
        val empty = wrap(http("https://other.example/sse", ""))
        assertTrue(McpCredentialFields.mergeRedactedMcpServerPayload(empty, wrap(http("https://local.example/sse", ""))))
    }
}
