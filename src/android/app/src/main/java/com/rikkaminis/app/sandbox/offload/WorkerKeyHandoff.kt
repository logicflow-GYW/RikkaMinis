package com.rikkaminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import com.rikkaminis.app.util.EncryptedPrefsFactory

/**
 * [T-worker-key-intent-handoff] Carries the CURRENT provider API key to the
 * `:modelservice` worker on the dispatch Intent, so a key replaced while the
 * worker is alive reaches the very next request without a process handoff.
 *
 * ## The bug this exists for
 * The worker resolves the key from `EncryptedSharedPreferences`, whose map is
 * cached PER PROCESS. A key saved by the main process after the worker was
 * born is therefore invisible inside the worker, and the HTTP call goes out
 * with the old (typically quota-exhausted) key. [WorkerKeyFreshness] detects
 * that and aborts the run so the client retries onto a fresh pid — but the
 * abort only frees the worker when `activeRequests == 0`, so under multi-session
 * load the poisoned worker stays alive while siblings keep streaming, rejecting
 * every request that arrives in that window. Those requests burn their whole
 * retry budget (`transientRetryDelaysSec`, 1+2+4+8+16s) and fail, while the
 * streams already in flight never notice — which is exactly the observed
 * "several agents stop, not all of them" shape.
 *
 * ## Why an Intent extra and not the request bundle
 * `request.json` is a FILE. Putting the key there would write plaintext to
 * disk, which this project forbids — KeyRoulette's KDoc spells the rule out
 * ("the plaintext never touches disk, matching ... the cross-process
 * request.json chain"). Intent extras travel over Binder: RAM only, never
 * persisted, and the service returns `START_NOT_STICKY`, so the system never
 * redelivers the start Intent with the key attached.
 *
 * ## Scope of the fix
 * A request that carries its own key is by definition fresher than the
 * worker's cached map, so the freshness abort must not fire for it (and must
 * not poison the process for its siblings). The abort stays as the fallback
 * for dispatches that cannot supply one — an older main process, or a caller
 * whose request bundle has no `instance_id` (the `minis-model-use` path).
 * Backward compatible in both directions: an old worker ignores the extra and
 * keeps the old behaviour, a new worker without the extra falls back to prefs.
 */
internal object WorkerKeyHandoff {

    /** Intent extra carrying the raw stored key (multi-key string verbatim). */
    const val EXTRA_API_KEY: String = "api_key_raw"

    /** Encrypted prefs file + slot shared with ProviderRepository.save/loadApiKey. */
    private const val PREFS_NAME = "provider_secrets"
    private const val KEY_PREFIX = "apikey_"

    /**
     * The key this request must use. A carried key wins outright and is passed
     * through VERBATIM — byte-for-byte what the prefs read would have returned,
     * so KeyRoulette's multi-key split and the missing-key check behave exactly
     * as before. [fromPrefs] is only consulted when the request carries none.
     */
    fun pickKey(carried: String?, fromPrefs: () -> String?): String {
        if (!carried.isNullOrBlank()) return carried
        return fromPrefs() ?: ""
    }

    /**
     * True when this request supplies its own key ⇒ the cross-process
     * freshness abort is unnecessary for it (and would poison the process).
     */
    fun carriesKey(carried: String?): Boolean = !carried.isNullOrBlank()

    /**
     * Read the CURRENT key for [instanceId] straight from the main process's
     * own prefs instance (the one `saveApiKey` just wrote).
     *
     * The id is threaded in explicitly rather than re-parsed out of the request
     * bundle on purpose: a chat bundle carries base64 images and runs into the
     * tens of MB, and an org.json parse costs ~50 ms per 2 MB plus a transient
     * object graph of the same order — per dispatch attempt, on the process
     * that also runs every session's UI. [ProviderExecutionGateway] is the only
     * main-process path that holds a [com.rikkaminis.app.data.model.ProviderInstance]
     * and therefore the single resolver.
     */
    fun keyFor(context: Context, instanceId: String): String {
        if (instanceId.isBlank()) return ""
        return runCatching {
            EncryptedPrefsFactory.safeCreate(context, PREFS_NAME)
                .getString(KEY_PREFIX + instanceId, null)
        }.getOrNull().orEmpty()
    }

    /**
     * Put a resolved key on the dispatch Intent. No-op when blank (OAuth-only /
     * keyless providers), leaving the worker on its fallback path. The only
     * writer of [EXTRA_API_KEY].
     *
     * The value is never logged: it is the plaintext secret.
     */
    fun attachKey(intent: Intent, apiKey: String) {
        if (apiKey.isNotBlank()) intent.putExtra(EXTRA_API_KEY, apiKey)
    }
}
