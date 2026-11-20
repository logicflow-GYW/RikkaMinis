package com.rikkaminis.app.ui.settings

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupTaskOwnerTest {
    /** A navigation instance owns its scope, not the application task's claim. */
    private class Page {
        val owner = BackupTaskOwner.application
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fun leave() = scope.cancel()
    }

    @Test fun restoreSuspendedAcrossNavigationRejectsSecondRestoreAndAllowsRetry() = runBlocking {
        val first = Page()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val latch = CompletableDeferred<Unit>()
        val token = requireNotNull(first.owner.claim())
        val job = first.owner.launch(appScope, token) { latch.await() }
        try {
            first.leave()
            val second = Page()
            assertSame(first.owner, second.owner)
            assertNotNull(second.owner.state.value)
            assertNull("new composition must not admit another restore", second.owner.claim())
            latch.complete(Unit)
            job.join()
            assertNull(second.owner.state.value)
            val retry = requireNotNull(second.owner.claim())
            assertTrue(second.owner.release(retry))
            second.leave()
        } finally {
            appScope.cancel()
            job.join()
            first.owner.release(token)
        }
    }

    @Test fun uploadSuspendedAcrossNavigationRejectsRestore() = runBlocking {
        val first = Page()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uploadDone = CompletableDeferred<Unit>()
        val token = requireNotNull(first.owner.claim())
        val upload = first.owner.launch(appScope, token) { uploadDone.await() }
        try {
            first.leave()
            val second = Page()
            assertNull("upload and restore share the owner", second.owner.claim())
            uploadDone.complete(Unit)
            upload.join()
            assertNull(second.owner.state.value)
            second.leave()
        } finally {
            appScope.cancel()
            upload.join()
            first.owner.release(token)
        }
    }

    @Test fun failedTaskReleasesClaim() = runBlocking {
        val owner = BackupTaskOwner()
        val observed = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined +
            CoroutineExceptionHandler { _, t -> observed.complete(t) })
        val token = requireNotNull(owner.claim())
        owner.launch(scope, token) { error("controlled failure") }.join()
        assertEquals("controlled failure", observed.await().message)
        assertNull(owner.state.value)
        scope.cancel()
    }

    @Test fun cancellationReleasesClaim() = runBlocking {
        val owner = BackupTaskOwner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val token = requireNotNull(owner.claim())
        val job = owner.launch(scope, token) { CompletableDeferred<Unit>().await() }
        scope.cancel()
        job.join()
        assertNull(owner.state.value)
    }

    @Test fun alreadyCancelledScopeAlsoReleasesClaim() = runBlocking {
        val owner = BackupTaskOwner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        val token = requireNotNull(owner.claim())
        var entered = false
        owner.launch(scope, token) { entered = true }.join()
        assertFalse(entered)
        assertNull(owner.state.value)
    }

    @Test fun staleReleaseCannotClearNewClaim() {
        val owner = BackupTaskOwner()
        val old = requireNotNull(owner.claim())
        assertTrue(owner.release(old))
        val current = requireNotNull(owner.claim())
        assertFalse(owner.release(old))
        assertSame(current, owner.state.value)
        assertTrue(owner.release(current))
    }

    @Test fun handoffRetainsBusyAndRejectsOldPayloadCompletion() = runBlocking {
        val owner = BackupTaskOwner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = requireNotNull(owner.claim())
        var restored: BackupTaskOwner.Token? = null
        val done = CompletableDeferred<Unit>()
        val payload = owner.launch(scope, old) {
            restored = requireNotNull(owner.transfer(old))
            owner.launch(scope, requireNotNull(restored)) { done.await() }
        }
        payload.join()
        assertSame(restored, owner.state.value)
        assertNull(owner.claim())
        assertFalse(owner.release(old))
        assertNull(owner.transfer(old))
        done.complete(Unit)
        scope.cancel()
        assertNull(owner.state.value)
    }

    @Test fun claimIsAtomicUnderSimultaneousEntrants() {
        val owner = BackupTaskOwner()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts = (1..8).map {
                pool.submit<BackupTaskOwner.Token?> { start.await(); owner.claim() }
            }
            start.countDown()
            val tokens = attempts.mapNotNull { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, tokens.size)
            assertTrue(owner.release(tokens.single()))
        } finally { pool.shutdownNow() }
    }

    @Test fun pickerRequestAndSecretsSurvivePageReplacement() {
        val first = Page()
        val token = requireNotNull(first.owner.claim())
        try {
            first.owner.awaitExport(token, true)
            first.leave()
            val second = Page()
            val request = requireNotNull(second.owner.takeExport())
            assertSame(token, request.token)
            assertTrue(request.includeSecrets)
            assertNull(second.owner.takeExport())
            assertTrue(second.owner.release(request.token))
            second.leave()
        } finally { first.owner.release(token) }
    }

    @Test fun cancelledPickerDoesNotLeaveOrInventAClaim() {
        val owner = BackupTaskOwner()
        val token = requireNotNull(owner.claim())
        owner.awaitExport(token, false)
        val request = requireNotNull(owner.takeExport())
        assertFalse(request.includeSecrets)
        assertTrue(owner.release(request.token))
        assertNull(owner.state.value)
        assertNull(owner.takeExport())
    }
}

/** Real screen wiring is tested separately from the JVM owner scheduling probes. */
class BackupTaskOwnerWiringTest {
    private val code: String by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val source = File(dir, "app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt")
            if (source.isFile) return@lazy source.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            dir = dir.parentFile
        }
        error("screen source not found")
    }

    @Test fun screenObservesApplicationOwnerRatherThanRememberingItsOwnGate() {
        assertTrue(code.contains("val backupTaskOwner = BackupTaskOwner.application"))
        assertTrue(code.contains("val operationClaim by backupTaskOwner.state.collectAsState()"))
        assertTrue(code.contains("val operationBusy = operationClaim != null"))
        assertFalse(Regex("var\\s+operationBusy\\b").containsMatchIn(code))
        assertFalse(Regex("backupTaskOwner\\s*=\\s*remember").containsMatchIn(code))
        assertTrue(code.contains("val token = backupTaskOwner.claim()"))
    }

    @Test fun allOwnedEntrypointsPassTokensToTheCompletionGuard() {
        assertEquals(13, Regex("backupTaskOwner\\.launch\\(").findAll(code).count())
        assertEquals(6, Regex("backupTaskOwner\\.launch\\(application\\.applicationScope, token\\)")
            .findAll(code).count())
        assertFalse(Regex("operationBusy\\s*=\\s*(true|false)").containsMatchIn(code))
        assertFalse(code.contains("releaseRestorePayload()"))
        assertTrue(code.contains("job.invokeOnCompletion { release(token) }"))
    }

    @Test fun eachRestoreHandoffCarriesItsClaimRatherThanABooleanBypass() {
        assertTrue(code.contains("backupTaskOwner.transfer(preClaimed)"))
        assertFalse(code.contains("if (!preClaimed && operationBusy)"))
        assertEquals(6, Regex("restoreWithSnapshot\\([^\\n]*, token\\)").findAll(code).count())
        assertTrue(code.contains("convertAndRestore(bytes, null, token)"))
    }

    @Test fun safCallbackConsumesApplicationOwnedRequestAndKeepsSecretChoice() {
        assertTrue(code.contains("val request = backupTaskOwner.takeExport() ?: return@rememberLauncherForActivityResult"))
        assertTrue(code.contains("backupTaskOwner.awaitExport(token, withSecrets)"))
        assertTrue(code.contains("includeSecrets = request.includeSecrets"))
    }
}
