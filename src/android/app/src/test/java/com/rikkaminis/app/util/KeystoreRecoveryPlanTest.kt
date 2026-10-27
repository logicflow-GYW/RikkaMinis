package com.rikkaminis.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [F-1 / F-2 · T-android-keystore-aead-fail] JVM tests for the production
 * recovery decision, run against the real
 * [KeystoreRecoveryPlan] / [KeystorePrefsBackup] / [DegradeRegistry] files (no
 * copies, no re-implementation).
 *
 * What these arms are worth: `EncryptedPrefsFactory` is unreachable from a plain
 * JVM test (it needs `Context` + security-crypto), and the recovery decision has
 * exactly one property that matters — *it must not destroy on a first failure*.
 * That property is a value question (which steps run, in which order), so it is
 * asked here as values: expected action lists are written out as literals
 * rather than derived from [KeystoreRecoveryPlan.steps], otherwise a mutation
 * inside the script would move both sides and the arm would stay green.
 *
 * The factory-level wiring (degraded reuse across `safeCreate` calls, the
 * in-memory store's `commit()`) is exercised in the sandbox stub harness, and
 * [InMemorySharedPreferences] is asserted directly below.
 */
class KeystoreRecoveryPlanTest {

    private var tempDir: File? = null

    @After
    fun cleanUp() {
        tempDir?.deleteRecursively()
    }

    // ── F-1: retry before destroying ────────────────────────────────────────

    @Test
    fun firstAttemptSuccessTakesNoDestructiveAction() {
        val io = RecordingIo()
        val outcome = runKeystoreRecovery(attempt = { true }, io = io, sleep = {})

        assertEquals(
            listOf<KeystoreRecoveryAction>(KeystoreRecoveryAction.Retry(0L)),
            outcome.executed,
        )
        assertTrue(outcome.succeeded)
        assertFalse(outcome.degraded)
        assertEquals(1, outcome.attempts)
        assertEquals(0, outcome.drops)
        assertEquals(emptyList<String>(), io.calls)
    }

    @Test
    fun transientFailureIsAbsorbedBeforeAnythingIsDestroyed() {
        var calls = 0
        val io = RecordingIo()
        val outcome = runKeystoreRecovery(attempt = { ++calls == 2 }, io = io, sleep = {})

        // Second attempt wins: the old code had already deleted the XML here.
        assertEquals(2, outcome.attempts)
        assertEquals(0, outcome.drops)
        assertTrue(outcome.nonDestructive)
        assertFalse(outcome.degraded)
        assertEquals(emptyList<String>(), io.calls)
        assertEquals(
            listOf<KeystoreRecoveryAction>(
                KeystoreRecoveryAction.Retry(0L),
                KeystoreRecoveryAction.Retry(50L),
            ),
            outcome.executed,
        )
    }

    @Test
    fun retryDelaysAreTheDocumentedZeroFiftyOneFifty() {
        val slept = mutableListOf<Long>()
        runKeystoreRecovery(attempt = { false }, io = RecordingIo(), sleep = { slept += it })
        // 3 retry delays in the opening burst, then one after each drop.
        assertEquals(listOf(0L, 50L, 150L, 0L, 0L), slept)
    }

    @Test
    fun exhaustedRetriesWalkTheFullScript() {
        val io = RecordingIo()
        val outcome = runKeystoreRecovery(attempt = { false }, io = io, sleep = {})

        assertEquals(
            listOf<KeystoreRecoveryAction>(
                KeystoreRecoveryAction.Retry(0L),
                KeystoreRecoveryAction.Retry(50L),
                KeystoreRecoveryAction.Retry(150L),
                KeystoreRecoveryAction.BackupThenDrop(KeystoreRecoveryTarget.PrefsFile),
                KeystoreRecoveryAction.Retry(0L),
                KeystoreRecoveryAction.BackupThenDrop(KeystoreRecoveryTarget.SharedKeyset),
                KeystoreRecoveryAction.Retry(0L),
                KeystoreRecoveryAction.Fallback,
            ),
            outcome.executed,
        )
        assertEquals(5, outcome.attempts)
        assertEquals(2, outcome.drops)
        assertTrue(outcome.degraded)
    }

    @Test
    fun recoveryAfterTheSecondStageWinsInsteadOfFallingBack() {
        // Succeeds only on the 5th attempt = the one after the shared keyset reset.
        var calls = 0
        val outcome = runKeystoreRecovery(attempt = { ++calls == 5 }, io = RecordingIo(), sleep = {})

        assertTrue(outcome.succeeded)
        assertFalse(outcome.degraded)
        assertEquals(2, outcome.drops)
        assertEquals(KeystoreRecoveryAction.Retry(0L), outcome.executed.last())
    }

    // ── F-1: backup before drop, and backup failure never blocks recovery ──

    @Test
    fun backupAlwaysPrecedesTheDropItProtects() {
        val io = RecordingIo()
        runKeystoreRecovery(attempt = { false }, io = io, sleep = {})

        assertEquals(
            listOf(
                "backup:PrefsFile",
                "drop:PrefsFile",
                "backup:SharedKeyset",
                "drop:SharedKeyset",
            ),
            io.calls,
        )
        // Order assertion on the trace itself, not on the plan.
        io.calls.forEachIndexed { index, call ->
            if (call.startsWith("drop:")) {
                val target = call.removePrefix("drop:")
                val backupIndex = io.calls.indexOf("backup:$target")
                assertTrue("backup($target) must come before drop($target)", backupIndex in 0 until index)
            }
        }
    }

    @Test
    fun failedBackupStillDropsAndStillFallsBack() {
        val io = RecordingIo(backupSucceeds = false)
        val outcome = runKeystoreRecovery(attempt = { false }, io = io, sleep = {})

        assertEquals(2, outcome.drops)
        assertTrue(outcome.degraded)
        assertEquals(4, io.calls.size)
    }

    // ── F-1: rotating backups on the real filesystem ────────────────────────

    @Test
    fun fourBackupsLeaveTheThreeNewest() {
        val dir = newTempDir()
        val source = File(dir, "provider_secrets.xml").apply { writeText("ciphertext-v1") }

        KeystorePrefsBackup.backup(source, nowMillis = 1_000L)
        KeystorePrefsBackup.backup(source, nowMillis = 2_000L)
        val third = KeystorePrefsBackup.backup(source, nowMillis = 3_000L)
        val fourth = KeystorePrefsBackup.backup(source, nowMillis = 4_000L)

        assertEquals(4_000L, stampOf(fourth!!))
        assertEquals(3_000L, stampOf(third!!))
        val remaining = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "provider_secrets.xml",
                "provider_secrets.xml.recovered-2000",
                "provider_secrets.xml.recovered-3000",
                "provider_secrets.xml.recovered-4000",
            ),
            remaining,
        )
        assertEquals("ciphertext-v1", File(dir, "provider_secrets.xml.recovered-4000").readText())
    }

    @Test
    fun backupsInTheSameMillisecondDoNotOverwriteEachOther() {
        val dir = newTempDir()
        val source = File(dir, "env_vars.xml").apply { writeText("v1") }

        val first = KeystorePrefsBackup.backup(source, nowMillis = 7_000L)!!
        val second = KeystorePrefsBackup.backup(source, nowMillis = 7_000L)!!
        val third = KeystorePrefsBackup.backup(source, nowMillis = 7_000L)!!
        val fourth = KeystorePrefsBackup.backup(source, nowMillis = 7_000L)!!

        assertEquals("env_vars.xml.recovered-7000", first.name)
        assertEquals("env_vars.xml.recovered-7000-1", second.name)
        assertEquals("env_vars.xml.recovered-7000-2", third.name)
        assertEquals("env_vars.xml.recovered-7000-3", fourth.name)
        // Same epoch: the sequence number decides which one is "oldest".
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(third.exists())
        assertTrue(fourth.exists())
    }

    @Test
    fun backupOfAMissingFileIsNotAFileAndIsNotAnError() {
        val dir = newTempDir()
        assertNull(KeystorePrefsBackup.backup(File(dir, "never_written.xml")))
        assertEquals(emptyList<String>(), dir.listFiles()!!.map { it.name })
    }

    // ── F-2: one instance per store name, degradation is queryable ─────────

    @Test
    fun sameFileNameYieldsTheSameInstance() {
        val registry = DegradeRegistry<String>()
        var creations = 0

        val first = registry.resolve("provider_secrets") { creations++; "store-$creations" }
        val second = registry.resolve("provider_secrets") { creations++; "store-$creations" }
        val other = registry.resolve("env_vars") { creations++; "store-$creations" }

        assertSame(first, second)
        assertNotSame(first, other)
        assertEquals(2, creations)
        assertEquals("store-1", first)
        assertEquals("store-2", other)
    }

    @Test
    fun degradedSetNamesEveryMemoryOnlyStore() {
        val registry = DegradeRegistry<String>()
        registry.resolve("provider_secrets") { registry.markDegraded("provider_secrets"); "memory:provider_secrets" }

        assertTrue(registry.isDegraded("provider_secrets"))
        assertFalse(registry.isDegraded("env_vars"))
        assertEquals(setOf("provider_secrets"), registry.degradedStores())
        assertEquals("memory:provider_secrets", registry.peek("provider_secrets"))
    }

    // ── F-2: the in-memory store reports honestly ──────────────────────────

    @Test
    fun inMemoryStoreWritesInProcessButReportsNotPersisted() {
        val prefs = InMemorySharedPreferences()

        val committed = prefs.edit().putString("apikey_x", "sk-live").commit()

        assertFalse("commit() on the memory-only store must not claim a disk write", committed)
        // The write is visible to this process — that is what keeps the app usable.
        assertEquals("sk-live", prefs.getString("apikey_x", null))
    }

    @Test
    fun inMemoryStoreApplyDoesNotThrow() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("k", "v").apply()
        assertEquals("v", prefs.getString("k", null))
        prefs.edit().clear().apply()
        assertFalse(prefs.contains("k"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private class RecordingIo(private val backupSucceeds: Boolean = true) : KeystoreRecoveryIo {
        val calls = mutableListOf<String>()
        private val backedUp = mutableSetOf<KeystoreRecoveryTarget>()

        override fun backup(target: KeystoreRecoveryTarget): Boolean {
            calls += "backup:$target"
            if (backupSucceeds) backedUp += target
            return backupSucceeds
        }

        override fun drop(target: KeystoreRecoveryTarget) {
            if (backupSucceeds && target !in backedUp) {
                fail("drop($target) happened before its backup")
            }
            calls += "drop:$target"
        }
    }

    private fun newTempDir(): File = Files.createTempDirectory("keystore-backup").toFile().also { tempDir = it }

    private fun stampOf(file: File): Long =
        file.name.substringAfter(KeystorePrefsBackup.SUFFIX).substringBefore('-').toLong()
}
