package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/**
 * Invokes the real private Service methods on two receivers. CI's existing
 * returnDefaultValues Android jar makes killProcess a no-op; SELF_REAP in the
 * real per-run log observes the decision immediately before that platform leaf.
 * No copied lifecycle predicate or live Android Service is used here.
 */
class WorkerProcessOwnershipTest {
    private val type = ModelExecutionService::class.java
    private fun field(s: ModelExecutionService, name: String): Any {
        val f = type.getDeclaredField(name).apply { isAccessible = true }
        return f.get(if (Modifier.isStatic(f.modifiers)) null else s)
    }
    private fun state(s: ModelExecutionService, value: ModelExecutionWorkerState) {
        val f = type.getDeclaredField("lifecycleState").apply { isAccessible = true }
        f.set(if (Modifier.isStatic(f.modifiers)) null else s, value)
    }
    private fun active(s: ModelExecutionService) = field(s, "activeRequests") as AtomicInteger
    private fun queue(s: ModelExecutionService) = field(s, "queuedRequests") as AtomicInteger
    private fun generation(s: ModelExecutionService) = field(s, "requestGeneration") as AtomicLong
    @Suppress("UNCHECKED_CAST")
    private fun acks(s: ModelExecutionService) = field(s, "pendingAckTokens") as ConcurrentHashMap<String, AtomicBoolean>
    private fun lock(s: ModelExecutionService) = field(s, "lifecycleLock")
    private fun call(s: ModelExecutionService, name: String, types: Array<Class<*>>, vararg args: Any?) {
        type.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(s, *args)
    }
    // Admission is a receiver leaf: only the exact existing counter/state effects.
    // The separate full onStartCommand replay covers slot/rejection/platform delivery.
    private fun admit(s: ModelExecutionService): Long = synchronized(lock(s)) {
        active(s).incrementAndGet(); state(s, ModelExecutionWorkerState.ACTIVE)
        generation(s).incrementAndGet()
    }
    private fun finish(s: ModelExecutionService, d: File, g: Long) = synchronized(lock(s)) {
        call(s, "finishRequestLocked", arrayOf(File::class.java, java.lang.Long.TYPE), d, g)
    }
    private fun reaps(d: File): Int = File(d, ModelExecutionRunLog.FILE_NAME).let { f ->
        if (!f.isFile) 0 else f.readLines().count { org.json.JSONObject(it).optString("phase") == "SELF_REAP" }
    }
    private fun scenario(block: (ModelExecutionService, ModelExecutionService, File) -> Unit) {
        val first = ModelExecutionService(); val next = ModelExecutionService()
        val receivers = listOf(first, next)
        val root = Files.createTempDirectory("process-owner-").toFile()
        // Unit tests are serial in this module. Preserve every process-global
        // value we touch, so this test cannot poison other Service tests.
        val saved = receivers.distinctBy { lock(it) }.map { s ->
            val f = type.getDeclaredField("lifecycleState").apply { isAccessible = true }
            arrayOf(s, active(s).get(), queue(s).get(), generation(s).get(), acks(s).toMap(),
                f.get(if (Modifier.isStatic(f.modifiers)) null else s), field(s, "keyCachePoisoned"))
        }
        try {
            receivers.forEach { s -> active(s).set(0); queue(s).set(0); generation(s).set(0); acks(s).clear(); state(s, ModelExecutionWorkerState.ACTIVE) }
            block(first, next, root)
        } finally {
            saved.forEach { row ->
                val s = row[0] as ModelExecutionService
                active(s).set(row[1] as Int); queue(s).set(row[2] as Int); generation(s).set(row[3] as Long)
                acks(s).clear()
                @Suppress("UNCHECKED_CAST") val prior = row[4] as Map<String, AtomicBoolean>
                acks(s).putAll(prior); state(s, row[5] as ModelExecutionWorkerState)
                val poison = type.getDeclaredField("keyCachePoisoned").apply { isAccessible = true }
                poison.set(if (Modifier.isStatic(poison.modifiers)) null else s, row[6])
            }
            root.deleteRecursively()
        }
    }
    private fun dir(root: File, name: String) = File(root, "run-$name").apply { check(mkdir()) }
    private fun decide(s: ModelExecutionService, d: File, g: Long, flushed: Boolean = true) = synchronized(lock(s)) {
        call(s, "maybeSelfReapLocked", arrayOf(File::class.java, ModelExecutionWorkerState::class.java,
            ModelExecutionQuiescenceInput::class.java, String::class.java, java.lang.Long.TYPE),
            d, ModelExecutionWorkerState.STOPPING, ModelExecutionQuiescenceInput(0, 0, 0, flushed), d.name, g)
    }
    private fun daemon(s: ModelExecutionService, name: String, types: Array<Class<*>>, vararg args: Any?): Thread {
        val before = Thread.getAllStackTraces().keys
        call(s, name, types, *args)
        return Thread.getAllStackTraces().keys.single { it !in before && it.isDaemon }
    }
    private fun join(thread: Thread) { thread.join(35_000); assertFalse("Service daemon must settle", thread.isAlive) }

    @Test fun replacement_active_run_blocks_old_last_finisher_and_then_reaps_once() = scenario { old, next, root ->
        val a = dir(root, "A"); val b = dir(root, "B"); val f = dir(root, "F")
        val ga = admit(old); val gb = admit(old)
        finish(old, b, gb)
        val gf = admit(next)
        finish(old, a, ga)
        assertTrue(ModelExecutionRunDir.terminalPresent(a))
        assertEquals("Old receiver must not kill a PID with F active", 0, reaps(a))
        assertEquals(1, active(next).get())
        finish(next, f, gf)
        assertEquals(1, reaps(f)); assertEquals(0, active(next).get())
        assertEquals(0, queue(next).get()); assertTrue(acks(next).isEmpty())
    }

    @Test fun replacement_queue_blocks_old_snapshot_and_reaps_after_queue_drains() = scenario { old, next, root ->
        val a = dir(root, "A"); val f = dir(root, "F")
        val ga = admit(old); val gf = admit(next); queue(next).set(1)
        finish(old, a, ga)
        assertEquals(0, reaps(a)); assertEquals(1, queue(next).get())
        queue(next).decrementAndGet(); finish(next, f, gf)
        assertEquals(1, reaps(f)); assertEquals(0, active(next).get())
    }

    @Test fun replacement_ack_blocks_old_finisher_until_ack_owner_finishes() = scenario { old, next, root ->
        val a = dir(root, "A"); val f = dir(root, "F")
        val ga = admit(old); val gf = admit(next)
        acks(next)["F"] = AtomicBoolean(true)
        finish(old, a, ga)
        assertEquals(0, reaps(a)); assertEquals(1, acks(next).size)
        call(next, "releaseAckToken", arrayOf(String::class.java), "F")
        finish(next, f, gf)
        assertEquals(1, reaps(f)); assertTrue(acks(old).isEmpty())
    }

    @Test fun shared_idle_finisher_writes_terminal_without_reaping_other_ack() = scenario { old, next, root ->
        val a = dir(root, "A")
        val ga = admit(old); acks(next)["F"] = AtomicBoolean(true)
        finish(old, a, ga)
        assertTrue(ModelExecutionRunDir.terminalPresent(a)); assertEquals(0, reaps(a))
        assertEquals(0, active(next).get()); assertEquals(1, acks(old).size)
        call(next, "releaseAckToken", arrayOf(String::class.java), "F")
        assertTrue(acks(old).isEmpty())
    }

    @Test fun receivers_share_the_process_lock_counters_generation_and_slot_pool() = scenario { a, b, _ ->
        listOf("lifecycleLock", "activeRequests", "queuedRequests", "pendingAckTokens", "requestGeneration", "executionSlots").forEach {
            assertSame("Process-owned $it", field(a, it), field(b, it))
        }
    }

    @Test fun single_receiver_reverse_completion_still_reaps_exactly_once() = scenario { s, _, root ->
        val a = dir(root, "A"); val b = dir(root, "B")
        val ga = admit(s); val gb = admit(s)
        finish(s, b, gb); assertEquals(0, reaps(b))
        finish(s, a, ga); assertEquals(1, reaps(a)); assertEquals(0, active(s).get())
    }

    @Test fun missing_directory_does_not_forge_terminal_or_kill_new_receiver() = scenario { old, next, root ->
        val missing = File(root, "missing")
        val ga = admit(old); admit(next)
        finish(old, missing, ga)
        assertFalse(missing.exists()); assertEquals(1, active(next).get())
    }

    @Test fun missing_terminal_and_unflushed_snapshot_never_reap() = scenario { old, _, root ->
        val d = dir(root, "no-terminal")
        decide(old, d, generation(old).get())
        assertEquals(0, reaps(d))
        ModelExecutionRunDir.writeTerminal(d)
        decide(old, d, generation(old).get(), flushed = false)
        assertEquals(0, reaps(d))
    }

    @Test fun old_controlled_drain_retains_scheduled_generation_veto_across_receivers() = scenario { old, next, root ->
        val d = dir(root, "old-drain"); ModelExecutionRunDir.writeTerminal(d)
        acks(old)["old-drain"] = AtomicBoolean(true)
        val task: Thread
        synchronized(lock(old)) {
            task = daemon(old, "scheduleControlledDrain", arrayOf(File::class.java, String::class.java), d, "old-drain")
            admit(next)
        }
        join(task)
        assertEquals(0, reaps(d)); assertTrue(acks(old).isEmpty()); assertEquals(1, active(next).get())
    }

    @Test fun poison_watchdog_waits_for_new_receiver_before_process_reap() = scenario { old, next, root ->
        val f = dir(root, "F-poison")
        val gf = admit(next)
        val task = daemon(old, "abortOnStaleKeyCache", arrayOf(String::class.java, File::class.java), null, null)
        task.join(1200)
        val survivedWhileActive = task.isAlive
        finish(next, f, gf)
        join(task)
        assertTrue("Old poison watchdog must not exit by killing a new active receiver", survivedWhileActive)
        assertEquals(1, reaps(f)); assertEquals(0, active(next).get())
    }

    @Test fun poison_watchdog_waits_for_process_queue_even_without_active_count() = scenario { old, next, _ ->
        queue(next).set(1)
        val task = daemon(old, "abortOnStaleKeyCache", arrayOf(String::class.java, File::class.java), null, null)
        task.join(1200)
        val held = task.isAlive
        queue(next).set(0); join(task)
        assertTrue("Process queue blocks poison reap", held)
    }

    @Test fun poison_watchdog_waits_for_process_ack_even_without_active_count() = scenario { old, next, _ ->
        acks(next)["F"] = AtomicBoolean(true)
        val task = daemon(old, "abortOnStaleKeyCache", arrayOf(String::class.java, File::class.java), null, null)
        task.join(1200)
        val held = task.isAlive
        call(next, "releaseAckToken", arrayOf(String::class.java), "F"); join(task)
        assertTrue("Process ACK blocks poison reap", held)
    }

    @Test fun poison_watchdog_uses_lifecycle_lock_through_kill_decision() = scenario { old, _, _ ->
        val task: Thread
        val held: Boolean
        synchronized(lock(old)) {
            task = daemon(old, "abortOnStaleKeyCache", arrayOf(String::class.java, File::class.java), null, null)
            task.join(1200)
            held = task.isAlive
        }
        join(task)
        assertTrue("Watchdog cannot decide to kill while another admission holds the lock", held)
    }

    @Test fun single_receiver_ordered_completion_still_reaps_exactly_once() = scenario { s, _, root ->
        val a = dir(root, "A"); val b = dir(root, "B")
        val ga = admit(s); val gb = admit(s)
        finish(s, a, ga); assertEquals(0, reaps(a))
        finish(s, b, gb); assertEquals(1, reaps(b)); assertEquals(0, active(s).get())
    }
}
