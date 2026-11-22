package com.rikkaminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the service decision point, not just the pure lifecycle predicate.
 * The companion JVM probe compiles the service's verbatim finalizer/reap/drain
 * methods and exercises both completion orders with selfReap as a counted leaf.
 * Source lookup fails closed in both module and repository working directories.
 */
class WorkerFinalizerReapDecisionTest {
    private fun source(): String {
        val relative = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val file = File(directory, relative)
            if (file.isFile) return file.readText()
            directory = directory.parentFile
        }
        error("Cannot locate ModelExecutionService source")
    }

    private fun body(name: String, next: String): String {
        val text = source()
        val start = text.indexOf("private fun $name(")
        assertTrue("Missing service method $name", start >= 0)
        val end = text.indexOf(next, start + 1)
        assertTrue("Missing end of service method $name", end > start)
        // These methods have no nested block comments or comment-like strings.
        return text.substring(start, end)
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    @Test fun completion_samples_current_generation_for_both_reap_paths() {
        val finish = body("finishRequestLocked", "private fun maybeSelfReapLocked(")
        val sample = Regex("val\\s+(\\w+)\\s*=\\s*requestGeneration\\.get\\(\\)")
            .findAll(finish).toList()
        assertEquals("One current locked completion generation sample", 1, sample.size)
        val name = sample.single().groupValues[1]
        val calls = Regex("maybeSelfReapLocked\\(dir,\\s*next,\\s*quiescence,\\s*runId,\\s*(\\w+)\\)")
            .findAll(finish).toList()
        assertEquals("Missing-directory and normal-terminal branches", 2, calls.size)
        calls.forEach { assertEquals("Never pass the run's historical admission generation", name, it.groupValues[1]) }
        assertTrue(finish.indexOf(sample.single().value) < calls.first().range.first)
    }

    @Test fun admission_and_completion_keep_the_same_lifecycle_lock() {
        val text = source()
        assertTrue(Regex("synchronized\\(lifecycleLock\\)\\s*\\{\\s*activeRequests\\.incrementAndGet\\(\\)[\\s\\S]*?requestGeneration\\.incrementAndGet\\(\\)").containsMatchIn(text))
        assertTrue(Regex("synchronized\\(lifecycleLock\\)\\s*\\{\\s*lifecycleState\\s*=\\s*finishRequestLocked\\(dir,\\s*generation\\)\\s*\\}").containsMatchIn(text))
    }

    @Test fun finalizer_keeps_per_run_terminal_and_counter_order() {
        val finish = body("finishRequestLocked", "private fun maybeSelfReapLocked(")
        val decrement = finish.indexOf("activeRequests.decrementAndGet()")
        val missing = finish.indexOf("val dirMissing = !dir.isDirectory")
        val state = finish.indexOf("ModelExecutionMailbox.writeState(")
        val terminal = finish.indexOf("ModelExecutionRunDir.writeTerminal(dir)")
        val reap = finish.lastIndexOf("maybeSelfReapLocked(")
        assertTrue(decrement >= 0 && decrement < missing)
        assertTrue(state > missing && terminal > state && reap > terminal)
    }

    @Test fun reap_still_checks_snapshot_current_work_and_terminal() {
        val reap = body("maybeSelfReapLocked", "private fun generationMatches(")
        assertTrue(reap.contains("capturedGeneration != requestGeneration.get()"))
        assertTrue(reap.contains("ModelExecutionLifecycle.shouldKill(state, quiescence)"))
        assertTrue(reap.contains("activeRequests.get() == 0"))
        assertTrue(reap.contains("queuedRequests.get() == 0"))
        assertTrue(reap.contains("pendingAckTokens.isEmpty()"))
        assertTrue(reap.contains("!generationStale"))
        val terminal = reap.indexOf("if (!ModelExecutionRunDir.terminalPresent(dir))")
        val abort = reap.indexOf("return", terminal)
        val kill = reap.indexOf("selfReap()")
        assertTrue(terminal >= 0 && abort > terminal && kill > abort)
    }

    @Test fun delayed_drain_keeps_its_scheduled_generation_veto() {
        val drain = body("scheduleControlledDrain", "private class ModelExecutionCancelledException")
        val sample = drain.indexOf("val genAtSchedule = requestGeneration.get()")
        val lock = drain.indexOf("synchronized(lifecycleLock)")
        val release = drain.indexOf("runId?.let { releaseAckToken(it) }", lock)
        val veto = drain.indexOf("if (requestGeneration.get() != genAtSchedule)", lock)
        val abort = drain.indexOf("return@synchronized", veto)
        val kill = drain.indexOf("selfReap()")
        assertTrue(sample >= 0 && lock > sample && release > lock)
        assertTrue(veto > release && abort > veto && kill > abort)
    }
}
