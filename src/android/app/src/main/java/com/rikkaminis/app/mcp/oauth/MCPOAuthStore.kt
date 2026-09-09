package com.rikkaminis.app.mcp.oauth

import android.content.Context
import android.content.SharedPreferences
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.util.EncryptedPrefsFactory
import org.json.JSONObject
import java.io.File

/**
 * [T-android-mcp-oauth] Secret storage for MCP OAuth: the client secret and the
 * issued access / refresh tokens, per server, in EncryptedSharedPreferences —
 * the Android analog of iOS keeping these in the Keychain (distinct from the
 * non-secret [MCPOAuthConfig] that lives in servers.json). All keys are
 * namespaced by server id.
 */
object MCPOAuthStore {

    private const val TAG = "MCPOAuthStore"
    private const val FILE = "mcp_oauth_secrets"

    /** Issued tokens for a server. [expiresAtMs] is 0 when the server gave no
     *  expires_in (treated as "never proactively refresh"). */
    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    )

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
                .also { prefsRef = it }
        }

    private fun secretKey(server: String) = "secret::$server"
    private fun tokensKey(server: String) = "tokens::$server"

    // -- client secret --

    fun setClientSecret(context: Context, server: String, secret: String?) {
        val p = prefs(context)
        if (secret.isNullOrEmpty()) {
            p.edit().remove(secretKey(server)).apply()
        } else {
            p.edit().putString(secretKey(server), secret).apply()
        }
    }

    fun clientSecret(context: Context, server: String): String? =
        prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }

    /**
     * [T-mcp-secret-handoff-import] The in-guest CLI seeds a client secret via
     * a plain `.secret` file next to the token bridge (`<oauth-dir>/<name>.secret`,
     * chmod 600) because it cannot write the encrypted store. Import it into the
     * encrypted store and delete the file. Returns the imported secret, or null
     * when there is no pending handoff. Best-effort + idempotent: a failed
     * import leaves the file in place for the next attempt.
     */
    fun importPendingClientSecret(context: Context, server: String): String? {
        val file = File(bridgeDir(context), "$server.secret")
        if (!file.exists()) return null
        val secret = runCatching { file.readText().trim() }.getOrNull()
        if (!secret.isNullOrEmpty()) {
            setClientSecret(context, server, secret)
        }
        runCatching { file.delete() }
        return secret
    }

    // -- tokens --

    fun setTokens(context: Context, server: String, tokens: StoredTokens) {
        val json = JSONObject().apply {
            put("access_token", tokens.accessToken)
            tokens.refreshToken?.let { put("refresh_token", it) }
            put("expires_at_ms", tokens.expiresAtMs)
        }
        prefs(context).edit().putString(tokensKey(server), json.toString()).apply()
    }

    fun tokens(context: Context, server: String): StoredTokens? {
        val raw = prefs(context).getString(tokensKey(server), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            StoredTokens(
                accessToken = o.getString("access_token"),
                refreshToken = o.optString("refresh_token", "").ifBlank { null },
                expiresAtMs = o.optLong("expires_at_ms", 0L),
            )
        }.onFailure { AppLogger.warning(TAG, "corrupt tokens for $server: ${it.message}") }
            .getOrNull()
    }

    fun isAuthorized(context: Context, server: String): Boolean =
        tokens(context, server)?.accessToken?.isNotEmpty() == true

    /** Forget issued tokens but keep the client secret (sign out, re-auth later). */
    fun signOut(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).apply()
        deleteBridgeFile(context, server)
    }

    /** Forget everything for a server — tokens AND client secret (server deleted). */
    fun purge(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
        deleteBridgeFile(context, server)
    }

    // -- In-guest token bridge (materialized for minis-mcp-cli) ---------------
    //
    // [T-mcp-oauth-bridge-materialize] The in-guest `minis-mcp-cli` reads OAuth
    // tokens ONLY from `/var/minis/mcp-servers/oauth/<server>.json` — it has no
    // access to this EncryptedSharedPreferences store. Without materializing
    // that bridge file, a server authorized in Settings would work in the UI
    // but fail with AUTH_REQUIRED the moment the agent invokes the CLI (the
    // "authorized but unusable" gap). This function writes the bridge file the
    // CLI expects (access_token / refresh_token / expires_at in SECONDS /
    // token_endpoint / client_id / client_secret / resource), chmod 0600, atomic
    // temp+rename so a concurrent CLI reader never sees a half-written file.
    // Best-effort: a failure here must not break the authorization flow that
    // already stored tokens in the encrypted store.

    /** Full bridge-file payload the CLI needs for connect + refresh. */
    data class BridgePayload(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtSeconds: Long,
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String?,
        val resource: String?,
    )

    /** Host dir backing the guest `/var/minis/mcp-servers` bind-mount. */
    fun bridgeDir(context: Context): File =
        File(File(context.filesDir, "minis-global/mcp-servers"), "oauth")

    private fun bridgeFile(context: Context, server: String): File =
        File(bridgeDir(context), "$server.json")

    /** Write [payload] to the in-guest token bridge (atomic, 0600, best-effort). */
    fun materializeBridgeFile(context: Context, server: String, payload: BridgePayload) {
        try {
            val dir = bridgeDir(context).apply { mkdirs() }
            val json = JSONObject().apply {
                put("access_token", payload.accessToken)
                payload.refreshToken?.takeIf { it.isNotBlank() }?.let { put("refresh_token", it) }
                put("expires_at", payload.expiresAtSeconds)
                put("token_endpoint", payload.tokenEndpoint)
                put("client_id", payload.clientId)
                payload.clientSecret?.takeIf { it.isNotBlank() }
                    ?.let { put("client_secret", it) }
                payload.resource?.takeIf { it.isNotBlank() }?.let { put("resource", it) }
            }
            val target = bridgeFile(context, server)
            val tmp = File(dir, "$server.json.tmp")
            tmp.writeText(json.toString())
            // 0600 (owner rw only) — the file holds live bearer tokens.
            runCatching { tmp.setReadable(false, false); tmp.setWritable(false, false) }
            runCatching { tmp.setReadable(true, true); tmp.setWritable(true, true) }
            if (!tmp.renameTo(target)) {
                // renameTo can fail across a bind-mount boundary; fall back to
                // copy + delete so the guest still gets a usable file.
                target.writeText(tmp.readText())
                runCatching {
                    target.setReadable(false, false); target.setWritable(false, false)
                    target.setReadable(true, true); target.setWritable(true, true)
                }
                tmp.delete()
            }
            AppLogger.info(TAG, "materialized OAuth bridge for '$server'")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "failed to materialize OAuth bridge for '$server': ${t.message}")
        }
    }

    /** Remove the in-guest bridge file (sign out / delete / purge). */
    fun deleteBridgeFile(context: Context, server: String) {
        runCatching {
            bridgeFile(context, server).delete()
            File(bridgeDir(context), "$server.json.tmp").delete()
            File(bridgeDir(context), "$server.secret").delete()
        }
    }
}
