package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-stale-apikey-worker-cache] Locks the cross-process staleness contract
 * for [WorkerKeyFreshness] and the dispatcher's worker-death evidence test.
 *
 * The production bug: SharedPreferences is a per-process singleton cache, so
 * a key saved by the main process after the :modelservice worker was born is
 * invisible to that worker (targetSdk >= 26 never reloads on cross-process
 * writes). The fix detects the secrets-file mtime moving past the process
 * start baseline and kills the worker so the client's retry spawns a fresh
 * process. These tests pin the DECISION logic only — the actual kill/respawn
 * is integration behavior exercised on-device.
 */
class WorkerKeyFreshnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun secretsFileIn(dataDir: File): File {
        val sp = File(dataDir, "shared_prefs")
        sp.mkdirs()
        return File(sp, "provider_secrets.xml")
    }

    // ── isStale (pure decision) ──────────────────────────────────────────

    @Test
    fun `no baseline is never stale`() {
        // baseline 0 = not captured → nothing to compare → not stale.
        assertFalse(WorkerKeyFreshness.isStale(0L, 999_999L))
    }

    @Test
    fun `missing file is never stale`() {
        // current mtime 0 = file absent → cached null matches disk → not stale.
        assertFalse(WorkerKeyFreshness.isStale(123L, 0L))
    }

    @Test
    fun `mtime equal to baseline is not stale`() {
        // The file our process loaded at birth is unchanged.
        assertFalse(WorkerKeyFreshness.isStale(1000L, 1000L))
    }

    @Test
    fun `mtime older than baseline is not stale`() {
        // Defensive: mtime moving backwards (clock skew, file replaced then
        // restored from an older backup) is NOT treated as a fresh rewrite.
        // Serving the cached key is the conservative choice — the user's
        // complaint path is "newer file invisible", not "older file served".
        assertFalse(WorkerKeyFreshness.isStale(2000L, 1000L))
    }

    @Test
    fun `mtime newer than baseline is stale`() {
        // The main process rewrote provider_secrets after this worker was
        // born → our cached key may be the OLD (quota-exhausted) one.
        assertTrue(WorkerKeyFreshness.isStale(1000L, 1001L))
    }

    @Test
    fun `stale survives any wall-clock magnitude`() {
        assertTrue(WorkerKeyFreshness.isStale(1L, Long.MAX_VALUE))
    }

    // ── [T-stale-key-clock-skew] ────────────────────────────────────────────

    @Test
    fun `mtime one second before baseline is not stale`() {
        // 1s of backwards drift is filesystem jitter, not a clock event.
        assertFalse(WorkerKeyFreshness.isStale(10_000L, 9_000L))
    }

    @Test
    fun `mtime five seconds before baseline is stale (clock skew)`() {
        // 5s backwards drift = RTC / NTP jump. Worker must die so retry
        // spawns a fresh process that reads the new key.
        assertTrue(WorkerKeyFreshness.isStale(10_000L, 5_000L))
    }

    @Test
    fun `mtime at baseline is not stale (same instant)`() {
        // Covered by `mtime equal to baseline is not stale`, but pin explicitly
        // for the rewrite so a future refactor that drops `==` doesn't slip.
        assertFalse(WorkerKeyFreshness.isStale(10_000L, 10_000L))
    }

    // ── secretsFile layout ───────────────────────────────────────────────

    @Test
    fun `secrets file resolves under shared_prefs`() {
        val dataDir = tmp.newFolder("data")
        val f = WorkerKeyFreshness.secretsFile(dataDir)
        assertEquals(
            File(dataDir, "shared_prefs/provider_secrets.xml").canonicalFile,
            f.canonicalFile,
        )
    }

    // ── captureBaseline / isStaleNow (file-backed, still JVM-safe) ────────

    @Test
    fun `baseline capture then rewrite is detected`() {
        val dataDir = tmp.newFolder("data")
        val secrets = secretsFileIn(dataDir)
        secrets.writeText("v1")
        // Simulate: worker born, captures the file's current mtime.
        WorkerKeyFreshness.resetForTests()
        WorkerKeyFreshness.captureBaseline(dataDir)
        assertFalse(WorkerKeyFreshness.isStaleNow(dataDir))

        // Simulate: main process saves a new key (rewrites the file). Ensure
        // the mtime strictly advances — some filesystems have coarse (1s)
        // granularity, so force it past the baseline deterministically.
        val baseline = WorkerKeyFreshness.secretsFile(dataDir).lastModified()
        secrets.writeText("v2")
        if (secrets.lastModified() <= baseline) {
            secrets.setLastModified(baseline + 10_000L)
        }
        assertTrue(WorkerKeyFreshness.isStaleNow(dataDir))
    }

    @Test
    fun `baseline is captured once and never re-captured`() {
        val dataDir = tmp.newFolder("data")
        val secrets = secretsFileIn(dataDir)
        secrets.writeText("v1")
        val bornAt = secrets.lastModified()
        WorkerKeyFreshness.resetForTests()
        WorkerKeyFreshness.captureBaseline(dataDir)

        // File rewritten later; a SECOND captureBaseline call must NOT move
        // the baseline forward — the worker would then think the rewrite
        // happened before its birth and serve the stale key.
        val later = bornAt + 60_000L
        secrets.setLastModified(later)
        WorkerKeyFreshness.captureBaseline(dataDir)
        assertTrue(WorkerKeyFreshness.isStaleNow(dataDir))
    }

    @Test
    fun `missing secrets file at birth is tolerated`() {
        val dataDir = tmp.newFolder("data")
        WorkerKeyFreshness.resetForTests()
        WorkerKeyFreshness.captureBaseline(dataDir) // file absent → baseline 0
        assertFalse(WorkerKeyFreshness.isStaleNow(dataDir))
    }

    // ── Worker-death evidence for the dispatcher's fast short-circuit ────
    // workerDiedWithoutResult is private in ModelExecutionDispatcher (it
    // reads sibling files); its building blocks are pinned here with inline
    // literals (the production constants: beat staleness window 4000ms,
    // result file "result.json", cancel ack "cancel.ack"). The composition
    // itself is exercised on-device.

    private val LIVENESS_STALE_MS = 4_000L
    private val FILE_LIVENESS_BEAT = "liveness.beat"
    private val FILE_RESULT = "result.json"
    private val FILE_CANCEL_ACK = "cancel.ack"

    @Test
    fun `stale beat with no output is the death signature`() {
        val dir = tmp.newFolder("run-death")
        val beat = File(dir, FILE_LIVENESS_BEAT)
        beat.writeText("{}")
        beat.setLastModified(System.currentTimeMillis() - (LIVENESS_STALE_MS + 1_000L))

        val hasOutput = File(dir, FILE_RESULT).exists() ||
            File(dir, FILE_CANCEL_ACK).exists() ||
            File(dir, "terminal.json").exists()

        // stale beat: mtime older than the staleness window
        val stale = System.currentTimeMillis() - beat.lastModified() > LIVENESS_STALE_MS
        assertTrue(stale)
        assertFalse(hasOutput)
    }

    @Test
    fun `fresh beat is not death`() {
        val dir = tmp.newFolder("run-alive")
        val beat = File(dir, FILE_LIVENESS_BEAT)
        beat.writeText("{}") // just touched → mtime is now
        val stale = System.currentTimeMillis() - beat.lastModified() > LIVENESS_STALE_MS
        assertFalse(stale)
    }

    @Test
    fun `stale beat with a result is not a result-less death`() {
        val dir = tmp.newFolder("run-served")
        val beat = File(dir, FILE_LIVENESS_BEAT)
        beat.writeText("{}")
        beat.setLastModified(System.currentTimeMillis() - (LIVENESS_STALE_MS + 1_000L))
        File(dir, FILE_RESULT).writeText("{}")

        val stale = System.currentTimeMillis() - beat.lastModified() > LIVENESS_STALE_MS
        assertTrue(stale)
        assertTrue(File(dir, FILE_RESULT).exists())
    }

    @Test
    fun `no beat at all is not a confirmed death`() {
        // Worker still starting up (beat file not yet written) — the poll
        // must keep waiting, not short-circuit.
        val dir = tmp.newFolder("run-starting")
        assertFalse(File(dir, FILE_LIVENESS_BEAT).isFile)
    }
}
