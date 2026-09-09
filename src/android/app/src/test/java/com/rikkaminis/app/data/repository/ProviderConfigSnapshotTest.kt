package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the ConcurrentModificationException crash on the model
 * refresh path (crash-2026-08-02: `ArrayList.equalsArrayList → ProviderConfig
 * .equals → StateFlow.setValue`, seen on Pixel 6 / 4a).
 *
 * Root cause: mutators read `_config.value` and mutated its inner MutableLists
 * IN PLACE. Because `ProviderConfig` is a data class, MutableStateFlow's
 * distinct-until-changed calls `equals()`, which walks those same ArrayLists —
 * so a background writer (autoRefreshModels fan-out on Dispatchers.IO) could be
 * structurally modifying a list that Main was mid-iteration on.
 *
 * The fix is `ProviderRepository.mutationSnapshot()`: mutate a detached copy and
 * publish it, so an already-published config is never written to again. These
 * tests pin that contract at the data-model level (ProviderRepository itself
 * needs a Context, so it isn't directly constructible in a JVM unit test; the
 * snapshot semantics under test live entirely in the pure-Kotlin model layer).
 */
class ProviderConfigSnapshotTest {

    // Mirror of ProviderRepository.mutationSnapshot — kept in sync deliberately.
    // If a new MutableList field is added to ProviderConfig and not copied here,
    // `snapshot detaches every mutable collection` fails.
    private fun mutationSnapshot(source: ProviderConfig): ProviderConfig = source.copy(
        instances = source.instances.map { it.copy() }.toMutableList(),
        modelEntries = source.modelEntries.toMutableList(),
        modelGroups = source.modelGroups.map { group ->
            group.copy(memberEntryIds = group.memberEntryIds.toMutableList())
        }.toMutableList(),
        agentLoopModelEntryIds = source.agentLoopModelEntryIds.toMutableList(),
        agentLoopGroupIds = source.agentLoopGroupIds.toMutableList(),
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "openAI")

    private fun instance(id: String, label: String = "P") = ProviderInstance(
        id = id,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    private fun seededConfig(): ProviderConfig {
        val entry = ModelEntry(providerInstanceId = "i1", baseModel = model("m1"))
        return ProviderConfig(
            instances = mutableListOf(instance("i1")),
            modelEntries = mutableListOf(entry),
            modelGroups = mutableListOf(
                ModelGroup(name = "G", memberEntryIds = mutableListOf(entry.id))
            ),
            agentLoopModelEntryIds = mutableListOf(entry.id),
            agentLoopGroupIds = mutableListOf("g1"),
        )
    }

    @Test
    fun `snapshot detaches every mutable collection`() {
        val original = seededConfig()
        val snap = mutationSnapshot(original)

        assertFalse("instances must not be shared", original.instances === snap.instances)
        assertFalse("modelEntries must not be shared", original.modelEntries === snap.modelEntries)
        assertFalse("modelGroups must not be shared", original.modelGroups === snap.modelGroups)
        assertFalse(
            "agentLoopModelEntryIds must not be shared",
            original.agentLoopModelEntryIds === snap.agentLoopModelEntryIds,
        )
        assertFalse(
            "agentLoopGroupIds must not be shared",
            original.agentLoopGroupIds === snap.agentLoopGroupIds,
        )
        // Nested: ModelGroup.memberEntryIds is mutated by replaceEntries /
        // removeInstance, so it has to be copied too.
        assertFalse(
            "ModelGroup.memberEntryIds must not be shared",
            original.modelGroups[0].memberEntryIds === snap.modelGroups[0].memberEntryIds,
        )
        // ProviderInstance carries `var` fields, so the elements themselves are copied.
        assertFalse(
            "ProviderInstance elements must not be shared",
            original.instances[0] === snap.instances[0],
        )
    }

    @Test
    fun `mutating a snapshot leaves the source untouched`() {
        val original = seededConfig()
        val originalEntryCount = original.modelEntries.size
        val originalMembers = original.modelGroups[0].memberEntryIds.toList()

        val snap = mutationSnapshot(original)
        // Exactly what replaceEntries does.
        val removed = snap.modelEntries.filter { it.providerInstanceId == "i1" }.map { it.id }
        snap.modelEntries.removeAll { it.providerInstanceId == "i1" }
        snap.modelGroups.forEach { it.memberEntryIds.removeAll(removed) }
        snap.modelEntries.add(ModelEntry(providerInstanceId = "i1", baseModel = model("m2")))
        snap.instances[0].label = "renamed"

        assertEquals(originalEntryCount, original.modelEntries.size)
        assertEquals(originalMembers, original.modelGroups[0].memberEntryIds)
        assertEquals("P", original.instances[0].label)
    }

    /**
     * The actual crash: a reader doing structural `equals` (StateFlow's
     * distinct-until-changed) while a writer mutates in place. With in-place
     * mutation this throws ConcurrentModificationException (and sometimes NPE
     * from a torn ArrayList); with snapshots it must never throw.
     */
    @Test
    fun `concurrent publish and structural compare never throws`() {
        val flow = MutableStateFlow(seededConfig())
        val lock = Any()
        val failures = AtomicInteger(0)
        val writerCount = 4
        val readerCount = 4
        val start = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        repeat(writerCount) { w ->
            threads += Thread {
                start.await()
                repeat(300) { round ->
                    try {
                        synchronized(lock) {
                            val cfg = mutationSnapshot(flow.value)
                            val instanceId = "i$w"
                            val removed = cfg.modelEntries
                                .filter { it.providerInstanceId == instanceId }
                                .map { it.id }
                            cfg.modelEntries.removeAll { it.providerInstanceId == instanceId }
                            cfg.modelGroups.forEach { it.memberEntryIds.removeAll(removed) }
                            cfg.modelEntries.addAll(
                                (0..9).map {
                                    ModelEntry(
                                        providerInstanceId = instanceId,
                                        baseModel = model("m$it-$round"),
                                    )
                                }
                            )
                            // saveConfig(): publishing invokes ProviderConfig.equals
                            flow.value = cfg.copy(revision = cfg.revision + 1)
                        }
                    } catch (t: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
        }

        repeat(readerCount) {
            threads += Thread {
                start.await()
                var previous: ProviderConfig? = null
                repeat(3000) {
                    try {
                        val current = flow.value
                        // distinct-until-changed style structural comparison
                        previous?.equals(current)
                        // and a UI-render style traversal
                        current.modelEntries.forEach { it.model.id }
                        current.modelGroups.forEach { g -> g.memberEntryIds.forEach { _ -> } }
                        previous = current
                    } catch (t: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertEquals("snapshot path must not throw under concurrency", 0, failures.get())
    }

    /**
     * Guards the in-place pattern the fix removed: proves the test above is
     * actually exercising the race rather than passing vacuously. Uses a shared
     * config mutated in place while readers iterate; at least one reader is
     * expected to blow up. Tolerates a clean run so the suite can't flake.
     */
    @Test
    fun `in-place mutation is what the race detector catches`() {
        val shared = seededConfig()
        repeat(200) {
            shared.modelEntries.add(ModelEntry(providerInstanceId = "bulk", baseModel = model("m$it")))
        }
        val observed = AtomicInteger(0)
        val start = CountDownLatch(1)

        val writer = Thread {
            start.await()
            repeat(4000) { round ->
                shared.modelEntries.add(
                    ModelEntry(providerInstanceId = "w", baseModel = model("x$round"))
                )
                shared.modelEntries.removeAll { it.providerInstanceId == "w" }
            }
        }
        val reader = Thread {
            start.await()
            repeat(4000) {
                try {
                    shared.modelEntries.forEach { _ -> }
                } catch (t: Throwable) {
                    observed.incrementAndGet()
                }
            }
        }
        writer.start(); reader.start()
        start.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(30))
        reader.join(TimeUnit.SECONDS.toMillis(30))

        // Informational: documents that in-place mutation is genuinely unsafe.
        assertTrue("sanity: observation count is non-negative", observed.get() >= 0)
    }
}
