package com.rikkaminis.app.sandbox.offload

import java.io.File

/**
 * Clock-skew grace window (ms) for the backward-rewrite detection in
 * [WorkerKeyFreshness.isStale]. Any backwards drift smaller than this is
 * normal filesystem jitter (mtime resolution / re-mount), not a clock event.
 */
private const val CLOCK_SKEW_GRACE_MS = 1_000L

/**
 * [T-stale-apikey-worker-cache] Cross-process staleness detection for the
 * `provider_secrets` EncryptedSharedPreferences file read by the
 * `:modelservice` worker.
 *
 * ## Why this exists
 * Android's `SharedPreferences` is a PER-PROCESS singleton cache keyed by
 * backing file (ContextImpl.sSharedPrefsCache). The main process writes a new
 * API key (`ProviderRepository.saveApiKey` → EncryptedSharedPreferences
 * editor `.commit()`); the worker process reads it via
 * `EncryptedPrefsFactory.safeCreate(this, "provider_secrets").getString(...)`.
 * Both wrap the same underlying `provider_secrets.xml`, but each process has
 * its OWN in-memory cache instance. With targetSdk >= 26 and no
 * MODE_MULTI_PROCESS, a cache hit on the worker NEVER re-reads the file
 * (ContextImpl only calls `startReloadIfChangedUnexpectedly()` for
 * MODE_MULTI_PROCESS / targetSdk < HONEYCOMB) — so a worker that already
 * loaded the prefs once keeps serving the OLD key until its process dies.
 *
 * The worker is designed to self-reap after each request, but it can legally
 * linger (ack timeout 15s + controlled drain 30s), and a request arriving
 * while it lingers REVIVES the same process. In that window a just-replaced
 * key is invisible: the user swaps a quota-exhausted key for a fresh one and
 * the very next message still fails with the OLD key — the exact
 * "must restart the app for a new key to take effect" report.
 *
 * ## Fix strategy (worker-death handoff)
 * When staleness is detected the worker does NOT try to decrypt the new key
 * itself — re-opening the prefs under a different file name would mint a
 * fresh Tink keyset that cannot decrypt the existing ciphertext (the keyset
 * blob is keyed by prefs file name), and re-using the cached
 * EncryptedSharedPreferences keeps returning the cached map. Instead the
 * worker ABORTS the request before any provider work and kills its own
 * process:
 *
 *   worker detects stale key → dies immediately (no result.json, no error
 *   line, no HTTP call) → client sees a 0-chunk worker death
 *   (DIED_BEFORE_READY family) → ChatViewModel classifies it as transient
 *   (`workerDiedZeroChunk` → isTransient) → auto-retry re-dispatches →
 *   startService spawns a NEW :modelservice process → the new process's
 *   first-ever prefs load sees the NEW key → request succeeds.
 *
 * This reuses the entire existing worker-death/retry machinery; the only new
 * behavior is "check freshness before reading the key, and die instead of
 * serving a stale key".
 *
 * ## Baseline semantics
 * [captureBaseline] records the secrets file's mtime once per process (the
 * first request this worker ever served). A rewrite by the main process
 * strictly AFTER that moment is the signal — it means the file changed during
 * this process's lifetime, so anything cached from before is suspect. The
 * baseline is NOT advanced after a stale-triggered death because the process
 * never serves another request (it dies); a revived process that somehow
 * survives (see call sites) re-captures on its next request only if the
 * baseline was never set — by design we err toward re-dying rather than
 * serving a key we know might be stale.
 *
 * Extracted as a pure object (File-based, no Android classes) so it is
 * trivially JVM-testable, following the FE-4 route-A pattern.
 *
 * ## Clock-skew backward rewrites [T-stale-key-clock-skew]
 * The original fix only caught FORWARD rewrites (currentMtime > baselineMs).
 * Android devices can move the system clock backwards on RTC drift / NTP
 * correction / daylight-saving transitions; in that window the
 * encryptedSharedPreferences mtime may land BELOW the worker's baseline, the
 * new key looks "unchanged", and the worker keeps serving the cached old
 * key. This fix is additive (not mutually exclusive) to the forward
 * detection: a backward mtime beyond a 1s grace is now also treated as
 * stale, so the worker dies and the retry path re-captures the baseline on
 * the next process birth (same worker-death handoff as the forward case).
 */
object WorkerKeyFreshness {

    /** Run-log / diagnostics marker for a stale-key-triggered abort. */
    const val STALE_KEY_CACHE = "stale_key_cache"

    private const val SECRETS_PREFS_NAME = "provider_secrets"

    /**
     * "This process has not captured a baseline yet." Distinct from every real
     * mtime, including [NO_SECRETS_FILE_MTIME] (R8-B B-F02).
     */
    private const val NOT_CAPTURED = -1L

    /**
     * The baseline for "the secrets file did not exist when this worker was
     * born". A real mtime is always > 0, so the two states stay distinguishable
     * — collapsing them is exactly the bug this constant exists to prevent.
     */
    private const val NO_SECRETS_FILE_MTIME = 0L

    /** Captured once per worker process; [NOT_CAPTURED] until then. */
    @Volatile
    private var baselineMtimeMs: Long = NOT_CAPTURED

    /**
     * Capture the prefs file mtime as the process-start baseline (idempotent).
     *
     * A MISSING file is captured as [NO_SECRETS_FILE_MTIME], not as "not
     * captured". The difference is the whole point (R8-B B-F02): a worker that
     * is born before the user has ever saved a key used to record 0, and
     * [isStale] treats a 0 baseline as "nothing to compare" — so the first save
     * (file appears, mtime = T1) looked unchanged, the baseline stayed 0
     * forever, and every later request kept serving the no-key cache. With the
     * explicit sentinel, "file appeared after birth" reads as
     * [NO_SECRETS_FILE_MTIME] → T1 = a forward rewrite = stale = die and let the
     * retry spawn a process that reads the new key.
     */
    fun captureBaseline(dataDir: File) {
        if (baselineMtimeMs == NOT_CAPTURED) {
            baselineMtimeMs = secretsFile(dataDir).lastModified()
        }
    }

    /**
     * Decide whether the provider_secrets file has been rewritten in a way
     * the worker must react to. Cases:
     *
     *   1. No baseline captured yet ([NOT_CAPTURED]) → NOT stale: nothing to
     *      compare against.
     *   2. Baseline captured while the file was ABSENT ([NO_SECRETS_FILE_MTIME])
     *      → stale as soon as the file exists. The first-ever save is a change
     *      this process must not miss (R8-B B-F02).
     *   3. Normal forward rewrite: currentMtime > baselineMs → STALE (the
     *      user's complaint path).
     *   4. OS clock skew / RTC drift: currentMtime is at least
     *      [CLOCK_SKEW_GRACE_MS] BELOW baselineMs → treat as STALE so the
     *      worker re-captures the baseline on next request and the retry
     *      spawns a fresh process that reads the new key on its first-ever
     *      prefs load.
     *   5. Same-time / small backwards drift (< grace) → NOT stale: the cached
     *      key matches disk, no need to die.
     *   6. A file that is GONE now (currentMtime == 0) while the baseline saw
     *      one → NOT stale: a deletion is not a new key, and dying would only
     *      re-read the same absence.
     *
     * The grace is conservative: 1s of backwards drift is normal filesystem
     * jitter (ext4 / f2fs mtime resolution, sdcardfs re-mount after reboot),
     * not an actual clock event.
     */
    fun isStale(baselineMs: Long, currentMtimeMs: Long): Boolean {
        if (baselineMs == NOT_CAPTURED) return false
        // Born before the file existed: its appearance IS the change.
        if (baselineMs == NO_SECRETS_FILE_MTIME) return currentMtimeMs > 0L
        // File gone now (deleted / not yet recreated): not a new key.
        if (currentMtimeMs == 0L) return false
        if (currentMtimeMs > baselineMs) return true
        return currentMtimeMs < baselineMs - CLOCK_SKEW_GRACE_MS
    }

    /** Staleness of the real secrets file for this dataDir. */
    fun isStaleNow(dataDir: File): Boolean =
        isStale(baselineMtimeMs, secretsFile(dataDir).lastModified())

    /** The canonical encrypted prefs XML file for the app's data dir. */
    fun secretsFile(dataDir: File): File =
        File(File(dataDir, "shared_prefs"), "$SECRETS_PREFS_NAME.xml")

    /** Test hook: reset the captured baseline. Production never calls this. */
    fun resetForTests() {
        baselineMtimeMs = NOT_CAPTURED
    }
}
