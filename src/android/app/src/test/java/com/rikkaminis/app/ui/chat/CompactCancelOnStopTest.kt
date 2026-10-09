package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [fix/compact-cancel-on-stop-1002]:
 *
 *  1. [queueDrainShouldDeferForCompact] — the queue drain defers ONLY while a
 *     LIVE compact exists; a stop-cancelled compact (job no longer active)
 *     must NOT stall a pending queue forever (its success kick never comes).
 *  2. Wiring source fragments: cancelStream actually kills compactJob +
 *     sendShellJob (in that order, after streamJob), the send shell and the
 *     compact launch actually store their jobs, and the compact marker
 *     insert rethrows CancellationException instead of swallowing it.
 *
 * Expected values are literal — never derived from the tested logic, so a
 * semantic flip fails these instead of following the mutation
 * (JVM 装置自指陷阱, 2026-09-26). Wiring is pinned on source fragments —
 * order of named fragments only, per the source-text-assertion lesson
 * (2026-10-01: judge sequence, never "A appears before B" across constructs).
 */
class CompactCancelOnStopTest {

    // ── deference predicate ─────────────────────────────────────────────

    @Test
    fun `defers only while a live compact is in flight`() {
        // Live compact → defer (draining would race the marker write).
        assertTrue(queueDrainShouldDeferForCompact(isCompacting = true, compactJobActive = true))
    }

    @Test
    fun `cancelled compact must not stall the drain`() {
        // The regression this fix prevents: stop cancelled the compact, the
        // stale _isCompacting flag is still true until the finally lands —
        // the drain must proceed because the success kick never comes.
        assertFalse(queueDrainShouldDeferForCompact(isCompacting = true, compactJobActive = false))
    }

    @Test
    fun `not compacting never defers`() {
        assertFalse(queueDrainShouldDeferForCompact(isCompacting = false, compactJobActive = true))
        assertFalse(queueDrainShouldDeferForCompact(isCompacting = false, compactJobActive = null))
    }

    @Test
    fun `unknown compact path keeps the conservative defer`() {
        // compactJob null = compact from an unknown path → keep the old
        // behavior (defer on the flag alone).
        assertTrue(queueDrainShouldDeferForCompact(isCompacting = true, compactJobActive = null))
    }

    // ── wiring: the cancel entries actually exist and are ordered ──────

    @Test
    fun `cancelStream kills stream compact and send shell in order`() {
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val cancelBody = vm.substringAfter("fun cancelStream() {")
        val atStream = cancelBody.indexOf("streamJob?.cancel()")
        val atCompact = cancelBody.indexOf("compactJob?.cancel()")
        val atShell = cancelBody.indexOf("sendShellJob?.cancel()")
        assertTrue("cancelStream missing streamJob cancel", atStream >= 0)
        assertTrue("cancelStream missing compactJob cancel", atCompact >= 0)
        assertTrue("cancelStream missing sendShellJob cancel", atShell >= 0)
        // Sequence: stream first (today's kill), then compact, then the shell.
        assertTrue(atStream < atCompact)
        assertTrue(atCompact < atShell)
    }

    @Test
    fun `sendMessage shell and compactAll store their jobs`() {
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        // The send shell: only the sendMessage launch (the one that awaits
        // the auto-compact before streamJob is assigned) stores the shell job.
        val shellAssign = vm.indexOf("sendShellJob = viewModelScope.launch(Dispatchers.IO)")
        assertTrue("sendMessage shell job not stored", shellAssign >= 0)
        // The shell assignment must precede the compact wait it guards.
        val awaitWait = vm.indexOf("awaitAutoCompactIfNeeded()")
        assertTrue(shellAssign < awaitWait)

        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        assertTrue(
            "compactAll job not stored",
            lifecycle.indexOf("compactJob = viewModelScope.launch(Dispatchers.IO)") >= 0,
        )
    }

    @Test
    fun `compact marker insert rethrows cancellation instead of swallowing it`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        // The old runCatching swallow must be gone; a try/catch that rethrows
        // CancellationException takes its place.
        assertFalse(
            "marker insert still swallows cancellation via runCatching",
            lifecycle.contains("runCatching { chatRepository.dao.insertCompactMarker"),
        )
        // Region-bounded: the marker insert's OWN try/catch must rethrow CE.
        // A file-global indexOf("catch (e: CancellationException)") is an
        // across-constructs order assertion — the raw-id verify rethrow
        // (fix/clearchat-compact-ce-1002) legitimately precedes the marker
        // insert in the same file and broke the old pin (CI run 36980256369
        // caught it). Same shape as the raw-id verify pin below.
        val markerRegion = lifecycle
            .substringAfter("val markerSaved = try {")
            .substringBefore("if (!markerSaved)")
        val rethrow = markerRegion.indexOf("chatRepository.dao.insertCompactMarker(marker)")
        assertTrue("marker insert call missing", rethrow >= 0)
        val rethrowCatch = markerRegion.indexOf("catch (e: CancellationException)")
        assertTrue("cancellation rethrow missing", rethrowCatch >= 0)
        // Sequence: the guarded call first, its rethrowing catch after —
        // i.e. the same try/catch region, not two unrelated constructs.
        assertTrue(rethrow < rethrowCatch)
        // The CE arm must actually rethrow, not swallow.
        val ceArm = markerRegion.substring(
            rethrowCatch,
            markerRegion.indexOf("catch (e: Exception)"),
        )
        assertTrue("CE arm does not rethrow", ceArm.contains("throw e"))
    }

    @Test
    fun `queue drain deference consumes the pure predicate`() {
        val qi = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt",
        )
        val deferCall = qi.indexOf("queueDrainShouldDeferForCompact(_isCompacting.value, compactJob?.isActive)")
        assertTrue("resumeQueueAfterCancel not wired to the predicate", deferCall >= 0)
        // The raw flag check must be gone from the defer branch.
        assertFalse(
            "defer branch still checks the raw flag",
            qi.contains("if (_isCompacting.value) {"),
        )
    }

    @Test
    fun `clearChat kills the compact even when no stream is active`() {
        // [fix/clearchat-compact-ce-1002] The old guard `if (_isStreaming.value)
        // cancelStream()` only killed the compact when a stream was alive; a
        // stream-less auto-compact survived the wipe and its commit block then
        // wrote marker + summary over the cleared session (old messages
        // "revived" as compacted history of the fresh chat).
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val body = vm
            .substringAfter("fun clearChat() {")
            .substringBefore("Share Injection")
        val guard = body.indexOf("if (_isStreaming.value) cancelStream()")
        val kill = body.indexOf("compactJob?.cancel()")
        assertTrue("clearChat missing the streaming guard", guard >= 0)
        assertTrue("clearChat missing compactJob cancel", kill >= 0)
        // The kill comes after the guard — this is an ordering assertion
        // only; source-text tests cannot detect nesting.
        assertTrue("compact kill must come after the streaming guard", guard < kill)
        // The stale summary must be reset alongside the marker: a fresh
        // compact on the new chat would otherwise merge the wiped
        // transcript's summary back in ("revived" old messages).
        assertTrue(
            "clearChat must reset the stale compact summary",
            body.contains("_compactSummary.value = null"),
        )
    }

    @Test
    fun `compact raw-id verify rethrows cancellation before the generic catch`() {
        // [fix/clearchat-compact-ce-1002] Same-family completion of
        // [fix/compact-cancel-on-stop-1002]: the raw-id DB verify is a suspend
        // point inside the cancellable compact launch. With stop now able to
        // cancel compactJob, a plain catch(Exception) would swallow the
        // CancellationException, log "verify failed", and fall back to the
        // in-memory anchor — continuing a cancelled compact. Kotlin catch
        // chains are ordered, so the CancellationException arm MUST precede
        // the generic arm (Exception would otherwise win and re-swallow).
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        val region = lifecycle
            .substringAfter("chatRepository.dao.loadMessages(sid).map { it.id }.toSet()")
            .substringBefore("verifiedAnchorIdx")
        val ce = region.indexOf("catch (e: CancellationException)")
        val generic = region.indexOf("catch (e: Exception)")
        assertTrue("raw-id verify missing CancellationException rethrow", ce >= 0)
        assertTrue("raw-id verify missing generic catch", generic >= 0)
        assertTrue("CE arm must precede the Exception arm or it gets swallowed", ce < generic)
        // The CE arm must actually rethrow, not swallow: a keep-the-catch-
        // order-but-drop-the-throw regression slips past the order asserts
        // alone (mutation B, review-clearchat-compact-ce-1002 — 10/10 green
        // with the swallow in place).
        val ceArm = region.substring(ce, generic)
        assertTrue("raw-id CE arm does not rethrow", ceArm.contains("throw e"))
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir (`.../src/android/app`),
     * so a single-level parent lookup is not enough — CI caught this shape.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
