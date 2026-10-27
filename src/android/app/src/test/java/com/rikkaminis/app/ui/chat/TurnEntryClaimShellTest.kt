package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv29-B01] Turn-entry claim / setup-shell closure (P1).
 *
 * The defect: the four retry / rerun entries (`retryLast`,
 * `rerunFromToolBlock`, `retryFromMessage`, `switchModelAndRerun`) launched
 * their setup work in a bare `viewModelScope.launch(Dispatchers.IO)`. That shell
 * was not stored anywhere, so `cancelStream()` (Stop / switching session / a
 * newer turn) reached only `streamJob` — which at that moment is still the
 * PREVIOUS turn's job (or null). The setup kept running and then started a real
 * stream for a turn the user had stopped. `switchModelAndRerun` additionally
 * claimed `_isStreaming = true` only after three DB round-trips, so during the
 * window every entry guard read "idle" and a second turn could claim too.
 *
 * The fix has three parts, each pinned here on the source shape:
 *  1. the setup shell is stored in `sendShellJob` (cancelStream cancels it);
 *  2. a claim-revocation comparison runs before the stream starts (a Stop can
 *     also land with no suspension left, and a newer claim supersedes us);
 *  3. the shared tail takes the caller's `(sendEpoch, claimToken)` as
 *     parameters instead of re-reading the fields after its own suspensions —
 *     a re-read compares the field with itself, a tautology that can never fire.
 *
 * Expectations are literals, never derived from the code under test (JVM 装置
 * 自指陷阱, 2026-09-26). Sequence is judged inside ONE function body only
 * (source-text-assertion lesson, 2026-10-01).
 */
class TurnEntryClaimShellTest {

    // ── 1. every turn entry holds its setup shell ────────────────────────

    @Test
    fun `every turn entry stores its setup shell in sendShellJob`() {
        val entries = listOf(
            Triple("ChatRetryOps.kt", "internal fun ChatViewModel.retryLast()", "retryLast"),
            Triple("ChatViewModel.kt", "fun rerunFromToolBlock(", "rerunFromToolBlock"),
            Triple("ChatViewModel.kt", "fun retryFromMessage(", "retryFromMessage"),
            Triple(
                "ChatModelRouting.kt",
                "internal fun ChatViewModel.switchModelAndRerun(",
                "switchModelAndRerun",
            ),
        )
        for ((file, marker, name) in entries) {
            val body = section(file, marker)
            // Negative control first: a truncated/mislocated slice must fail
            // loudly instead of making the assertion below vacuous.
            assertTrue(
                "$name: section did not resolve to the entry body",
                body.contains("_isStreaming.value = true"),
            )
            assertTrue(
                "$name must hold its setup work in sendShellJob — cancelStream() " +
                    "cancels stored job fields only, so a bare launch is unkillable " +
                    "and starts a stream after Stop",
                body.contains("sendShellJob = viewModelScope.launch(Dispatchers.IO) {"),
            )
        }
    }

    // ── 2. a revocation comparison guards every stream start ─────────────

    @Test
    fun `stream starts compare the caller's claim identity first`() {
        // 4th element: the local that carries the caller's claim epoch (the
        // switch path names it `switchEpoch`; both are captured before any
        // suspension, so either is a real comparison, not a re-read).
        val sites = listOf(
            Triple("ChatRetryOps.kt", "internal fun ChatViewModel.retryLast()", "retryLast sendEpoch"),
            Triple("ChatViewModel.kt", "fun sendMessage(", "sendMessage sendEpoch"),
            Triple("ChatViewModel.kt", "fun resume(", "resume sendEpoch"),
            Triple(
                "ChatModelRouting.kt",
                "internal fun ChatViewModel.switchModelAndRerun(",
                "switchModel switchEpoch",
            ),
        )
        for ((file, marker, tag) in sites) {
            val name = tag.substringBefore(' ')
            val epochVar = tag.substringAfter(' ')
            val body = section(file, marker)
            val site = body.indexOf("streamJob = launch(")
            if (site < 0) {
                // This entry restarts through the shared tail, which owns the
                // guard (pinned by the next test) — it must hand over the
                // identity it captured instead of letting the tail re-read it.
                assertTrue(
                    "$name: delegates its stream start, so it must pass $epochVar to the tail",
                    body.contains("runRerunStreamTail(provider, label, $epochVar, claimToken)"),
                )
                continue
            }
            val guard = body.indexOf("$epochVar != streamingClaimEpoch")
            assertTrue("$name: no `$epochVar != streamingClaimEpoch` comparison", guard >= 0)
            assertTrue(
                "$name: no `claimToken !== streamingClaimToken` comparison",
                body.contains("claimToken !== streamingClaimToken"),
            )
            val bailOut = body.indexOf("return@launch")
            assertTrue("$name: the comparison does not bail out", bailOut > guard)
            assertTrue(
                "$name: the revocation guard must run BEFORE the stream starts",
                guard < site,
            )
        }
    }

    // ── 3. the shared tail takes the identity, it does not re-read it ────

    @Test
    fun `runRerunStreamTail takes the caller's claim identity as parameters`() {
        val body = section(
            "ChatTurnPersistence.kt",
            "internal suspend fun ChatViewModel.runRerunStreamTail(",
        )
        assertTrue("tail: sendEpoch must be a parameter", body.contains("sendEpoch: Long,"))
        assertTrue("tail: claimToken must be a parameter", body.contains("claimToken: Any,"))
        // The tautology this fix removes: re-reading the field after the tail's
        // own suspensions and then comparing it with itself.
        assertTrue(
            "tail must not re-read the claim token (the comparison would be a tautology)",
            !body.contains("val claimToken = streamingClaimToken"),
        )
        assertTrue(
            "tail must not re-read the claim epoch",
            !body.contains("val launchEpoch = streamingClaimEpoch"),
        )
        val guard = body.indexOf("if (sendEpoch != streamingClaimEpoch || claimToken !== streamingClaimToken) {")
        val site = body.indexOf("streamJob = viewModelScope.launch(")
        assertTrue("tail: missing the revocation guard", guard >= 0)
        assertTrue("tail: the guard must run before the stream starts", site > guard)
        assertTrue("tail: the guard must return false (the caller clears the flag)",
            body.substring(guard).contains("return false"))
    }

    // ── 4. both callers hand their captured identity to the tail ─────────

    @Test
    fun `the tail is always called with the caller's captured identity`() {
        val callers = listOf(
            "ChatViewModel.kt" to "runRerunStreamTail(initialProvider, \"rerunFromToolBlock\", sendEpoch, claimToken)",
            "ChatViewModel.kt" to "runRerunStreamTail(provider, \"retryFromMessage\", sendEpoch, claimToken)",
            "ChatModelRouting.kt" to "runRerunStreamTail(provider, label, switchEpoch, claimToken)",
        )
        for ((file, call) in callers) {
            assertTrue("$file: missing call `$call`", source(file).contains(call))
        }
        // And no call site may fall back to the 2-argument (tautology) shape.
        for (file in listOf("ChatViewModel.kt", "ChatModelRouting.kt")) {
            val text = source(file)
            assertTrue("$file: 2-arg tail call would re-read the claim",
                !text.contains("runRerunStreamTail(initialProvider, \"rerunFromToolBlock\")"))
            assertTrue("$file: 2-arg tail call would re-read the claim",
                !text.contains("runRerunStreamTail(provider, \"retryFromMessage\")"))
            assertTrue("$file: 2-arg tail call would re-read the claim",
                !text.contains("runRerunStreamTail(provider, label)"))
        }
    }

    // ── 5. switchModelAndRerun: synchronous claim + released early exits ─

    @Test
    fun `switchModelAndRerun claims synchronously and can always release`() {
        val body = section(
            "ChatModelRouting.kt",
            "internal fun ChatViewModel.switchModelAndRerun(",
        )
        val claim = body.indexOf("_isStreaming.value = true")
        val setup = body.indexOf("sendShellJob = viewModelScope.launch(")
        assertTrue("switchModel: claim missing", claim >= 0)
        assertTrue("switchModel: setup shell missing", setup >= 0)
        assertTrue(
            "switchModel must publish the claim BEFORE the DB re-sync coroutine — " +
                "claiming inside it leaves every entry guard reading false for three " +
                "DB round-trips and gives Stop no handle",
            claim < setup,
        )
        // The predecessor's setup shell must die with the switch: a live send
        // shell would reach its own `streamJob = launch` under our claim.
        val cancelShell = body.indexOf("sendShellJob?.cancel()")
        assertTrue("switchModel: Phase 1 must cancel sendShellJob", cancelShell >= 0)
        assertTrue("switchModel: cancel must precede the new claim", cancelShell < claim)
        // The early `return@launch` on a failed re-sync must release the claim.
        val finallyBlock = body.substring(setup)
        assertTrue("switchModel: no finally around the setup", finallyBlock.contains("} finally {"))
        assertTrue(
            "switchModel: an early return would strand _isStreaming=true",
            finallyBlock.contains("_isStreaming.value = false"),
        )
        // The provider==null bail-out stays BEFORE the claim: returning after
        // claiming would strand the flag with no coroutine left to clear it.
        val providerBail = body.indexOf("no currentProvider after switch")
        assertTrue("switchModel: provider bail-out missing", providerBail >= 0)
        assertTrue("switchModel: provider bail-out must precede the claim", providerBail < claim)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun source(fileName: String): String =
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/$fileName")

    /**
     * The body of ONE function, from its declaration to the next member/`fun`
     * declaration. Sequence assertions must never span two functions, so the
     * slice is what keeps them honest.
     */
    private fun section(fileName: String, marker: String): String {
        val text = source(fileName)
        val start = text.indexOf(marker)
        assertTrue("$fileName: declaration not found: $marker", start >= 0)
        val rest = text.substring(start + marker.length)
        val cut = listOf(
            "\ninternal fun ", "\nfun ", "\n    fun ", "\n    internal fun ",
            "\n    private fun ", "\n    suspend fun ",
        ).map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, cut)
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir, so a single-level
     * parent lookup is not enough — CI caught this shape.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
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
