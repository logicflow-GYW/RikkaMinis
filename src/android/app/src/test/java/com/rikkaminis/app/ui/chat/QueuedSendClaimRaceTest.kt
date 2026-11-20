package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** R1-CHAT-01: send must arbitrate the SAME slot as a prepared queue drain. */
class QueuedSendClaimRaceTest {
    private class ClaimHost {
        val streaming = MutableStateFlow(false)
        var epoch = 0L
        var token: Any = Any()
        var handle: Job? = null
        val started = AtomicInteger()
        fun claim(owner: Any): Boolean = claimStreamingSlot(streaming) {
            token = owner
            epoch++
            streaming.value = true
        }
        fun publish(owner: Any, at: Long): Boolean = synchronized(streaming) {
            if (!streaming.value || at != epoch || owner !== token) return@synchronized false
            handle = Job()
            started.incrementAndGet()
            true
        }
        fun release(owner: Any, at: Long) = synchronized(streaming) {
            if (at == epoch && owner === token) streaming.value = false
        }
    }

    private fun await(latch: CountDownLatch) =
        assertTrue("controlled interleaving timed out", latch.await(5, TimeUnit.SECONDS))

    @Test fun `drain wins after send passed its advisory idle read`() {
        val vm = ClaimHost()
        val idle = CountDownLatch(1)
        val continueSend = CountDownLatch(1)
        val restored = mutableListOf<String>()
        val sendToken = Any()
        val sender = thread {
            assertFalse(vm.streaming.value)
            idle.countDown()
            await(continueSend)
            if (!vm.claim(sendToken)) restored += "draft with attachment"
            else vm.publish(sendToken, vm.epoch)
        }
        try {
            await(idle)
            val drainToken = Any()
            assertTrue(vm.claim(drainToken))
            assertTrue(vm.publish(drainToken, vm.epoch))
            val handle = vm.handle
            continueSend.countDown(); sender.join(5_000)
            assertFalse(sender.isAlive)
            assertEquals(1, vm.started.get())
            assertSame(handle, vm.handle)
            assertEquals(listOf("draft with attachment"), restored)
            handle!!.cancel(); assertTrue(vm.handle!!.isCancelled)
        } finally { continueSend.countDown(); sender.join(5_000) }
    }

    @Test fun `send wins and prepared drain leaves handle untouched`() {
        val vm = ClaimHost()
        val owner = Any()
        assertTrue(vm.claim(owner))
        assertTrue(vm.publish(owner, vm.epoch))
        val handle = vm.handle
        assertFalse(vm.claim(Any()))
        assertSame(handle, vm.handle)
        assertEquals(1, vm.started.get())
    }

    @Test fun `a revoked owner cannot publish or release the next claim`() {
        val vm = ClaimHost()
        val old = Any()
        assertTrue(vm.claim(old))
        val oldEpoch = vm.epoch
        vm.release(old, oldEpoch)
        val next = Any()
        assertTrue(vm.claim(next))
        assertTrue(vm.publish(next, vm.epoch))
        val handle = vm.handle
        assertFalse(vm.publish(old, oldEpoch))
        vm.release(old, oldEpoch)
        assertTrue(vm.streaming.value)
        assertSame(handle, vm.handle)
        assertEquals(1, vm.started.get())
    }

    @Test fun `stop without a newer epoch still revokes publication`() {
        val vm = ClaimHost()
        val owner = Any()
        assertTrue(vm.claim(owner))
        vm.streaming.value = false
        assertFalse(vm.publish(owner, vm.epoch))
        assertNull(vm.handle)
    }

    @Test fun `send and drain use same claim before run-state mutations`() {
        val send = source("ChatViewModel.kt", "fun sendMessage(")
        val drain = source("ChatQueueInterruption.kt", "internal fun ChatViewModel.resumeQueueAfterCancel()")
        val claim = send.indexOf("claimStreamingSlot(_isStreaming)")
        assertTrue(claim >= 0)
        for (mutation in listOf("rescueAttemptedForCompact = false", "_canResume.value = false", "rotateForNewTurn(\"send\")", "clearAttachments()", "agentHistory.add(")) {
            assertTrue("send mutates $mutation before claiming", send.indexOf(mutation) > claim)
        }
        val loser = block(send, "if (!claimed)")
        assertTrue(loser.contains("restoreComposerAfterRefusal(text)"))
        assertTrue(loser.contains("return"))
        assertFalse(loser.contains("clearAttachments()"))
        assertFalse(loser.contains("cancelStream()"))
        val main = drain.indexOf("withContext(Dispatchers.Main.immediate)")
        val read = drain.indexOf("systemPromptForSession()")
        val drainClaim = drain.indexOf("claimStreamingSlot(_isStreaming)")
        assertTrue("prompt read remains on IO before Main arbitration", read >= 0 && main > read)
        assertTrue("drain and synchronous same-family entries share Main arbitration", drainClaim > main)
        assertTrue("loser does not consume queued prompts", block(drain, "if (!claimed)").contains("return@withContext"))
    }

    @Test fun `send setup guard rejects revocation before publication`() {
        val body = source("ChatViewModel.kt", "fun sendMessage(")
        val shell = body.indexOf("sendShellJob = viewModelScope.launch(")
        val guardAt = body.indexOf("if (!isActive ||", shell)
        assertTrue(guardAt > shell)
        val guard = body.substring(guardAt, body.indexOf('{', guardAt))
        assertTrue(guard.contains("!_isStreaming.value"))
        assertTrue(guard.contains("sendEpoch != streamingClaimEpoch"))
        assertTrue(guard.contains("claimToken !== streamingClaimToken"))
        assertTrue(block(body, "if (!isActive ||").contains("return@launch"))
    }

    @Test fun `both final publications guard identity inside the monitor`() {
        for ((file, marker, epoch) in listOf(
            Triple("ChatViewModel.kt", "fun sendMessage(", "sendEpoch"),
            Triple("ChatViewModel.kt", "fun resume(", "sendEpoch"),
            Triple("ChatQueueInterruption.kt", "internal fun ChatViewModel.resumeQueueAfterCancel()", "launchEpoch"),
        )) {
            val body = source(file, marker)
            val publication = enclosingMonitor(body, body.indexOf("streamJob = launch("))
            val job = publication.indexOf("streamJob = launch(")
            val prefix = publication.substring(0, job)
            assertTrue(prefix.contains("!isActive"))
            assertTrue(prefix.contains("!_isStreaming.value"))
            assertTrue(prefix.contains("$epoch != streamingClaimEpoch"))
            assertTrue(prefix.contains("claimToken !== streamingClaimToken"))
            assertTrue(prefix.contains("return@synchronized"))
            assertTrue("publish a lazy handle before its body can finish", publication.contains("start = CoroutineStart.LAZY"))
            assertTrue(publication.indexOf("streamJob?.start()") > job)
        }
    }

    @Test fun `old send and drain release compare and clear under the claim monitor`() {
        for ((file, marker) in listOf(
            "ChatViewModel.kt" to "fun sendMessage(",
            "ChatQueueInterruption.kt" to "internal fun ChatViewModel.resumeQueueAfterCancel()",
        )) {
            val body = source(file, marker)
            val compare = body.indexOf("if (streamJob === coroutineContext[Job]")
            val release = enclosingMonitor(body, compare)
            assertTrue(release.contains("claimToken === streamingClaimToken"))
            assertTrue(release.contains("_isStreaming.value = false"))
        }
    }

    @Test fun `same-file retry rerun and resume claim before transcript or run-state writes`() {
        for ((marker, mutation) in listOf(
            "fun rerunFromToolBlock(" to "_canResume.value = false",
            "fun retryFromMessage(" to "_messages.value = retainedHead",
            "fun resume(" to "clearPersistedLastAssistantError()",
        )) {
            val body = source("ChatViewModel.kt", marker)
            val claim = body.indexOf("claimStreamingSlot(_isStreaming)")
            assertTrue("$marker participates in the common claim", claim >= 0)
            assertTrue("$marker mutates transcript before the claim", body.indexOf(mutation) > claim)
        }
    }

    private fun source(file: String, marker: String) = ChatSourceSlices.function(file, marker)
    private fun block(text: String, anchor: String): String {
        val at = text.indexOf(anchor)
        require(at >= 0) { "missing anchor $anchor" }
        val open = text.indexOf('{', at)
        require(open >= 0)
        var depth = 0
        var i = open
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> { i = text.indexOf('\n', i).let { if (it < 0) text.length else it }; continue }
                text.startsWith("/*", i) -> { i = text.indexOf("*/", i + 2).also { require(it >= 0) } + 2; continue }
                text.startsWith("\"\"\"", i) -> { i = text.indexOf("\"\"\"", i + 3).also { require(it >= 0) } + 3; continue }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i++]
                    while (i < text.length && text[i] != quote) { if (text[i] == '\\') i++; i++ }
                }
                text[i] == '{' -> depth++
                text[i] == '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
            i++
        }
        error("unclosed block $anchor")
    }
    private fun enclosingMonitor(body: String, point: Int): String {
        require(point >= 0) { "missing decision point" }
        val marker = "synchronized(_isStreaming)"
        var at = body.lastIndexOf(marker, point)
        while (at >= 0) {
            val region = block(body.substring(at), marker)
            val open = body.indexOf('{', at)
            if (point < open + region.length) return region
            at = body.lastIndexOf(marker, at - 1)
        }
        error("decision has no enclosing claim monitor")
    }
}
