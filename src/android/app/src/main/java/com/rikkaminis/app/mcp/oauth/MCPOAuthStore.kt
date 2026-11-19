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

    // [sweep1014-D4a] Server names are joined into file paths (bridge file,
    // secret handoff). [rv1014-C4] Path safety only requires rejecting
    // separators, traversal and control characters — spaces / CJK are legal
    // filenames (ext4/f2fs), and the app's own create form allows them: the
    // old allowlist silently severed OAuth for exactly those servers (bridge
    // never materialized, no UI hint, rename locked in edit mode). Denylist:
    // reject empty, overlong, '/', '\', '..', and control characters;
    // everything else passes verbatim — no encoding, because the in-guest CLI
    // joins the raw name too, so identity is the only cross-language-safe
    // mapping. Refuse-instead-of-guess — never sanitize by truncation.
    private val SERVER_NAME_BAD_RE = Regex("[/\\\\\\p{Cntrl}]")
    private const val SERVER_NAME_MAX = 128

    /** Returns [server] when it is a safe path segment, else null. */
    fun validateServerName(server: String): String? =
        if (server.isNotEmpty() &&
            server.length <= SERVER_NAME_MAX &&
            server != ".." &&
            !SERVER_NAME_BAD_RE.containsMatchIn(server)
        ) server else null

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

    /**
     * [dv27-A3] The single exit point for a server's client secret: a pending
     * `.secret` handoff file wins over the stored value, and the encrypted
     * store is the fallback.
     *
     * Converging the import here — rather than at each call site — is the whole
     * point. The handoff file is written by the in-guest CLI
     * (`minis-mcp-cli add --oauth-client-secret`), which cannot reach the
     * encrypted store; before this, only the edit sheet ever imported it, so
     * every other reader saw the stale stored value (or none at all):
     * [reconcileForServer] wrote the OLD secret back into the bridge file, and
     * [MCPOAuthController]'s token exchange omitted `client_secret`, which a
     * confidential client answers with 401. Both now inherit the handoff for
     * free because they read through here.
     */
    fun clientSecret(context: Context, server: String): String? {
        importPendingClientSecret(context, server)?.let { return it }
        return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }
    }

    /**
     * [T-mcp-secret-handoff-import] The in-guest CLI seeds a client secret via
     * a plain `.secret` file next to the token bridge (`<oauth-dir>/<name>.secret`,
     * chmod 600) because it cannot write the encrypted store. Import it into the
     * encrypted store and delete the file. Returns the imported secret, or null
     * when there is no pending handoff. Best-effort + idempotent: a failed
     * import leaves the file in place for the next attempt.
     */
    fun importPendingClientSecret(context: Context, server: String): String? {
        // [sweep1014-D4a] Refuse (null + log) rather than building a path from
        // an unsanitized name. This is called from Compose state init
        // (MCPIntegrationsScreen), where a throw would crash composition.
        if (validateServerName(server) == null) {
            AppLogger.warning(TAG, "refusing secret handoff import: invalid server name '$server'")
            return null
        }
        val file = File(bridgeDir(context), "$server.secret")
        if (!file.exists()) return null
        val secret = runCatching { file.readText().trim() }.getOrNull()
        // [audit-0917] Delete only after the secret actually landed in the
        // encrypted store. The old unconditional delete threw away the file on
        // a transient read error or a blank write, contradicting this method's
        // own "a failed import leaves the file in place for the next attempt"
        // contract — and the secret is unrecoverable once gone.
        if (secret.isNullOrEmpty()) return null
        setClientSecret(context, server, secret)
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
                refreshToken = o.optStringOrNull("refresh_token"),
                expiresAtMs = o.optLong("expires_at_ms", 0L),
            )
        }.onFailure { AppLogger.warning(TAG, "corrupt tokens for $server: ${it.message}") }
            .getOrNull()
    }

    fun isAuthorized(context: Context, server: String): Boolean =
        tokens(context, server)?.accessToken?.isNotEmpty() == true

    /** Forget issued tokens but keep the client secret (sign out, re-auth later). */
    fun signOut(context: Context, server: String) {
        // [dv27-A3] Delete the bridge file only AFTER any pending handoff has
        // landed in the encrypted store. `deleteBridgeFile` unconditionally
        // removes `<server>.secret`, and the CLI's only copy of a never-imported
        // client secret is that file — signing out before the sheet ever opened
        // the form destroyed a credential that cannot be re-derived (the user
        // would have to fetch it from the provider again). Credential loss, not
        // a cleanup gap: `signOut` promises "keep the client secret".
        importPendingClientSecret(context, server)
        prefs(context).edit().remove(tokensKey(server)).apply()
        deleteBridgeFile(context, server)
    }

    /** Forget everything for a server — tokens AND client secret (server deleted). */
    fun purge(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
        deleteBridgeFile(context, server)
    }

    // -- Config-change reconciliation -----------------------------------------
    //
    // [da1024-D] ISSUE-04 (deep-audit-1024): credential cleanup used to be a bag
    // of partial verbs that each caller had to remember to run in the right
    // combination. `setClientSecret(ctx, id, null)` removes ONLY `secret::<id>`,
    // so switching an authorized server to STDIO (or clearing its OAuth block)
    // left `tokens::<id>` plus the bridge file
    // `/var/minis/mcp-servers/oauth/<id>.json` — which holds a live bearer token
    // in plaintext for the in-guest CLI — behind permanently: the STDIO form
    // hides the OAuth section, so the Sign Out button that would have cleared
    // them no longer existed. Editing an authorized server had the opposite
    // drift: the config moved, the bridge metadata (token_endpoint / client_id /
    // client_secret / resource) did not, so the CLI refreshed against a dead
    // endpoint.
    //
    // The convergence point lives HERE, called from the data layer, not from
    // the UI: every save path (edit form, JSON import, backup restore, any
    // future entry point) goes through MCPRepository.add/update/importJSON, so
    // reconciling there enforces one invariant instead of one rule per caller:
    //
    //   stored credentials exist  ⇔  the persisted config says "fully configured
    //   OAuth over a URL transport", and the bridge file mirrors THAT config.
    //
    // Deliberately self-contained: callers pass the saved config's facts only —
    // the secret and the tokens are read back from this store, so no caller can
    // hand a stale copy or forget a field. Secrets never reach the log lines
    // below (server name only), and never enter servers.json (MCPOAuthConfig is
    // the non-secret half).

    /** What [reconcileForServer] must do for a server whose config just changed. */
    internal enum class ReconcileAction { PURGE, KEEP, REFRESH_BRIDGE }

    /**
     * Pure: a URL transport with a fully configured OAuth block. Anything else
     * can never authorize, so any credential it still holds belongs to a config
     * that no longer exists.
     */
    internal fun usableOAuth(urlTransport: Boolean, oauth: MCPOAuthConfig?): Boolean =
        urlTransport && oauth != null && oauth.isConfigured

    /**
     * Pure decision behind [reconcileForServer], split out so it is unit-testable
     * without a Context (the store itself needs EncryptedSharedPreferences + the
     * guest bind-mount).
     *
     * KEEP (configured but not authorized) is not an oversight: the edit form
     * writes the client secret *before* the browser round-trip, and [signOut]
     * promises "forget issued tokens but keep the client secret". Purging on
     * hasTokens == false would wipe a secret that a pending authorization still
     * needs, and would break that documented contract.
     */
    internal fun decideReconcileAction(
        urlTransport: Boolean,
        oauth: MCPOAuthConfig?,
        hasTokens: Boolean,
    ): ReconcileAction = when {
        !usableOAuth(urlTransport, oauth) -> ReconcileAction.PURGE
        hasTokens -> ReconcileAction.REFRESH_BRIDGE
        else -> ReconcileAction.KEEP
    }

    /**
     * Bring this server's stored credentials and in-guest bridge file in line
     * with the config that was just persisted. Idempotent, and safe to call for
     * every save of every server: a server with no OAuth state at all costs one
     * prefs read and no writes.
     *
     * Never throws for an unsafe server name — [purge] / [materializeBridgeFile]
     * refuse-and-log. Callers still wrap this in `runCatching` because it runs
     * inside a save, where a credential-store hiccup must not abort the save
     * that already reached servers.json.
     */
    fun reconcileForServer(
        context: Context,
        server: String,
        urlTransport: Boolean,
        oauth: MCPOAuthConfig?,
        serverUrl: String?,
    ) {
        val stored = tokens(context, server)
        when (decideReconcileAction(urlTransport, oauth, stored != null)) {
            ReconcileAction.PURGE -> {
                purge(context, server)
                AppLogger.info(
                    TAG,
                    "reconcile '$server': dropped OAuth state (no configured URL-transport OAuth)",
                )
            }

            ReconcileAction.KEEP -> Unit

            ReconcileAction.REFRESH_BRIDGE -> {
                // decideReconcileAction only returns REFRESH_BRIDGE for a usable
                // config with tokens on file. Assert the invariant instead of
                // using !! — a future refactor that breaks it must fail loudly
                // (and land in the caller's runCatching) rather than silently
                // materializing a bridge file with blank metadata.
                val cfg = requireNotNull(oauth) { "REFRESH_BRIDGE requires an oauth config" }
                // [dv26-A2] The guest CLI refreshes tokens on its own schedule
                // and writes the result to the bridge file only. Promote that
                // copy back BEFORE materializing, or this branch (which exists
                // to refresh the bridge from the saved config) hands the guest
                // its own pre-refresh snapshot back — with a rotated
                // refresh_token that is already dead on the provider side.
                val t = promoteFromBridge(
                    context,
                    server,
                    requireNotNull(stored) { "REFRESH_BRIDGE requires stored tokens" },
                )
                materializeBridgeFile(
                    context, server,
                    BridgePayload(
                        accessToken = t.accessToken,
                        refreshToken = t.refreshToken,
                        // The bridge expects SECONDS; StoredTokens keeps ms.
                        expiresAtSeconds = if (t.expiresAtMs > 0) t.expiresAtMs / 1000 else 0L,
                        tokenEndpoint = cfg.tokenEndpoint,
                        clientId = cfg.clientId,
                        // Read back from the store — the edit form has already
                        // written the new value by the time the save lands.
                        clientSecret = clientSecret(context, server),
                        resource = McpPkce.canonicalResourceUri(serverUrl),
                    ),
                )
                AppLogger.info(
                    TAG,
                    "reconcile '$server': re-materialized bridge metadata from the saved config",
                )
            }
        }
    }

    // -- Guest → host promotion of a refreshed token ([dv26-A2]) --------------
    //
    // deep-audit-1046 S-02. The bridge file is a SECOND copy of the same
    // credential and only the host → guest direction was implemented: the
    // in-guest `minis-mcp-cli` refreshes (`transport/http.py::_oauth_refresh →
    // _save_oauth_tokens`) and rewrites access_token / expires_at / — under
    // refresh-token rotation — refresh_token, and nothing ever read it back.
    // The next save of any other field of that server then materialized the
    // PRE-refresh snapshot over it, so the guest's next refresh presented a
    // rotated-away refresh token and died with `invalid_grant` (authorization
    // permanently lost). The promotion below runs before every materialize
    // driven by [reconcileForServer], so both trigger paths (a config save and
    // the reconciliation that follows it) see the guest's copy first.
    //
    // Scope is deliberately the token triple only (see [promoteTokensFromBridge]):
    // endpoints / client credentials stay authoritative on the host side, a
    // damaged or older bridge promotes nothing, and every failure is silent by
    // design — this runs inside a save and must not abort it.

    /**
     * Read the token triple the in-guest CLI left in the bridge file, or null
     * when the file is absent or unreadable. Best-effort: a corrupt bridge must
     * never throw here (the caller is inside a save), and never clears the
     * host's tokens.
     */
    private fun readBridgeTokenSnapshot(context: Context, server: String): BridgeTokenSnapshot? {
        val safe = validateServerName(server) ?: return null
        return runCatching {
            val file = bridgeFile(context, safe)
            if (!file.exists()) return@runCatching null
            val o = JSONObject(file.readText())
            BridgeTokenSnapshot(
                accessToken = o.optString("access_token", ""),
                refreshToken = o.optStringOrNull("refresh_token"),
                expiresAtSeconds = o.optLong("expires_at", 0L),
            )
        }.onFailure {
            AppLogger.warning(TAG, "unreadable OAuth bridge for '$server': ${it.message}")
        }.getOrNull()
    }

    /**
     * Promote a guest-refreshed token into the store and hand back the tokens
     * the caller must materialize. Returns [stored] unchanged when the bridge
     * has nothing newer — that is the common case (the host is the fresher
     * side), so it costs one file read and no writes.
     */
    private fun promoteFromBridge(
        context: Context,
        server: String,
        stored: StoredTokens,
    ): StoredTokens {
        val bridge = readBridgeTokenSnapshot(context, server) ?: return stored
        val promoted = promoteTokensFromBridge(
            StoredTokenSnapshot(
                accessToken = stored.accessToken,
                refreshToken = stored.refreshToken,
                expiresAtMs = stored.expiresAtMs,
            ),
            bridge,
        ) ?: return stored
        val tokens = StoredTokens(
            accessToken = promoted.accessToken,
            refreshToken = promoted.refreshToken,
            expiresAtMs = promoted.expiresAtMs,
        )
        // Persist first: the bridge is materialized from the returned value by
        // the caller, but the encrypted store must not keep the superseded
        // triple — the next save would read it back and lose the promotion.
        setTokens(context, server, tokens)
        AppLogger.info(
            TAG,
            "reconcile '$server': promoted the in-guest refreshed token into the store",
        )
        return tokens
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

    private fun bridgeFile(context: Context, server: String): File {
        // [sweep1014-D4a] Refuse-instead-of-guess: an unsafe name never
        // reaches the path join. materializeBridgeFile catches this and logs
        // a clear warning; deleteBridgeFile's runCatching no-ops (nothing was
        // ever written under an invalid name).
        val safe = validateServerName(server)
            ?: throw IllegalArgumentException(
                "server name is not a safe path segment (no '/', '\\', control chars, not '..'): '$server'")
        return File(bridgeDir(context), "$safe.json")
    }

    /** Write [payload] to the in-guest token bridge (atomic, 0600, best-effort). */
    fun materializeBridgeFile(context: Context, server: String, payload: BridgePayload) {
        // [rv1014-C3] Validate once up front so the failure-cleanup below can
        // use the SAME validated name — the catch branch was the only place
        // still joining the raw (possibly unsafe) name into a path (D4a
        // covered the main path but missed its own cleanup).
        val safe = validateServerName(server)
        try {
            if (safe == null) {
                throw IllegalArgumentException(
                    "server name is not a safe path segment (no '/', '\\', control chars, not '..'): '$server'")
            }
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
            val tmp = File(dir, "$safe.json.tmp")
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
            // [audit-0917] tmp holds live bearer tokens — never leave it behind
            // on a failure (e.g. the rename fallback's readText/writeText threw).
            // [rv1014-C3] clean up under the validated name; safe==null means
            // nothing was ever created (throw precedes tmp), cleanup no-ops.
            runCatching { safe?.let { File(bridgeDir(context), "$it.json.tmp").delete() } }
            AppLogger.warning(TAG, "failed to materialize OAuth bridge for '$server': ${t.message}")
        }
    }

    /** Remove the in-guest bridge file (sign out / delete / purge). */
    fun deleteBridgeFile(context: Context, server: String) {
        runCatching {
            // [sweep1014-D4a] Invalid name → nothing was ever written under
            // it; refuse (skip) rather than build a path.
            val safe = validateServerName(server) ?: return
            bridgeFile(context, safe).delete()
            File(bridgeDir(context), "$safe.json.tmp").delete()
            File(bridgeDir(context), "$safe.secret").delete()
        }
    }
}
