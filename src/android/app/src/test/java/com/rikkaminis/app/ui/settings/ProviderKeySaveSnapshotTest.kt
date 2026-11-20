package com.rikkaminis.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-RESCAN-1120 R6 F-04] The key editor is mutable while the encrypted
 * preferences write is suspended on IO. These tests hold the write so a stale
 * snapshot becomes observable. All keys are synthetic fixtures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderKeySaveSnapshotTest {

    @Test
    fun `save captures draft before IO and cancel cannot clear it`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val saver = ProviderKeySaver(this, io)
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        var draft = "captured-test-key"
        var editing = true
        var saved = ""

        saver.save(
            keyToSave = draft,
            write = { key ->
                writes += key
                release.await()
            },
            onSuccess = { key ->
                saved = key
                editing = false
            },
            onFailure = { error("the suspended test write must not fail") },
        )
        runCurrent()
        assertTrue("the IO transaction must hold the save gate", saver.isSaving.value)
        assertFalse("Cancel must be blocked during the save transaction", keySaveActionAllowed(saver.isSaving.value))
        if (keySaveActionAllowed(saver.isSaving.value)) {
            draft = ""
            editing = false
        }

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("captured-test-key"), writes)
        assertEquals("captured-test-key", saved)
        assertFalse("success closes the editor", editing)
        assertFalse(saver.isSaving.value)
    }

    @Test
    fun `success callback uses the persisted snapshot after draft changes`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val saver = ProviderKeySaver(this, io)
        val release = CompletableDeferred<Unit>()
        var draft = "persisted-test-key"
        var persisted = ""
        var displayed = ""

        saver.save(
            keyToSave = draft,
            write = {
                persisted = it
                release.await()
            },
            onSuccess = { displayed = it },
            onFailure = { error("the suspended test write must not fail") },
        )
        runCurrent()
        draft = "edited-after-save-click"
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("edited-after-save-click", draft)
        assertEquals("persisted-test-key", persisted)
        assertEquals(
            "successful回填必须使用点击时快照，而不是回填前的可变草稿",
            persisted,
            displayed,
        )
    }

    @Test
    fun `failure restores operation and keeps the draft`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val saver = ProviderKeySaver(this, io)
        val release = CompletableDeferred<Unit>()
        var draft = "retained-test-key"
        var editing = true
        var failure = false
        var success = false

        saver.save(
            keyToSave = draft,
            write = {
                release.await()
                error("synthetic write failure")
            },
            onSuccess = { success = true },
            onFailure = {
                failure = true
                editing = keySaveActionAllowed(saver.isSaving.value)
            },
        )
        runCurrent()
        assertTrue(saver.isSaving.value)
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(success)
        assertTrue(failure)
        assertTrue("failure restores the editor operation", editing)
        assertEquals("retained-test-key", draft)
        assertFalse("failure must release the save gate", saver.isSaving.value)
    }

    @Test
    fun `duplicate save is rejected until the active write finishes`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val saver = ProviderKeySaver(this, io)
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        saver.save(
            keyToSave = "first-test-snapshot",
            write = {
                writes += it
                release.await()
            },
            onSuccess = {},
            onFailure = { error("the suspended test write must not fail") },
        )
        runCurrent()
        saver.save(
            keyToSave = "second-test-snapshot",
            write = { writes += it },
            onSuccess = {},
            onFailure = { error("the rejected duplicate must not fail") },
        )
        runCurrent()

        assertEquals(listOf("first-test-snapshot"), writes)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first-test-snapshot"), writes)
        assertFalse(saver.isSaving.value)
    }
}

/** Source-wiring assertions run on the real screen, not on a test copy. */
class ProviderKeySaveSnapshotWiringTest {

    private fun source(): String {
        System.getProperty("keySaveSource")?.let { return java.io.File(it).readText() }
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        val rel = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ProviderConnectionScreen.kt"
        while (dir != null) {
            val file = java.io.File(dir, rel)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        error("$rel not found")
    }

    @Test
    fun `screen wiring keeps the save snapshot and all busy gates`() {
        val code = source()
        val saveAt = code.indexOf("onSave = {")
        val cancelAt = code.indexOf("onCancelEdit = {")
        val failureAt = code.indexOf("onFailure = {", saveAt)
        assertTrue("the connection screen lost its Save callback", saveAt >= 0)
        assertTrue("the connection screen lost its Cancel callback", cancelAt >= 0 && cancelAt < saveAt)
        assertTrue("the save callback lost its failure branch", failureAt > saveAt)

        val saveBody = code.substring(saveAt, failureAt)
        assertTrue("Save must capture the mutable editor before launching IO", saveBody.contains("val keyToSave = editKeyValue"))
        assertTrue("the write must receive the captured snapshot", saveBody.contains("keySaver.save(") && saveBody.contains("keyToSave = keyToSave"))
        assertFalse("the IO write must not re-read the mutable draft", saveBody.contains("saveApiKey(instanceId, editKeyValue)"))
        assertTrue("Save must be gated while another save is active", saveBody.contains("if (keySaveActionAllowed(keySaver.isSavingNow()))"))

        val cancelBody = code.substring(cancelAt, saveAt)
        assertTrue("Cancel must be a no-op while the save is suspended", cancelBody.contains("keySaveActionAllowed(keySaver.isSavingNow())"))
        assertTrue("typing must be gated while the save is suspended", code.contains("onEditValueChange = { value -> if (keySaveActionAllowed(keySaver.isSavingNow()))"))
        assertTrue("the editor must be replaced with progress while saving", code.contains("if (isSavingKey) {\n                    LinearProgressIndicator"))

        val successBody = code.substring(code.indexOf("onSuccess = {", saveAt), failureAt)
        assertTrue("successful回填必须使用已保存快照", successBody.contains("storedKey = savedKey"))
        assertFalse("successful回填不得重读可变草稿", successBody.contains("storedKey = editKeyValue"))
        assertTrue("failure must show a generic error without secret text", code.contains("R.string.file_save_failed_toast"))
        val providerLog = "Saved API key for " + '$' + "{instance.id}"
        assertTrue("the success log may identify the provider, not the secret", code.contains(providerLog))
        assertFalse("the API key must not be appended to the success log", code.contains("$providerLog:"))
    }
}
