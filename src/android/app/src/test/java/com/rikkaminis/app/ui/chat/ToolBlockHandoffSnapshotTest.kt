package com.rikkaminis.app.ui.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.AbstractMutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r3-chat-1119 F-02] The tool-block UI side channel must cross threads as
 * an IMMUTABLE SNAPSHOT taken on the WRITING thread.
 *
 * `executeShellCommand` is handed the engine's live `MutableList<AssistantBlock>`
 * (`loopState.allToolBlocks`) and — before the fix — handed that same live
 * instance to a Main-thread coroutine on every shell output flush. The consumer
 * copies it (`toolBlocks.toList()` inside `updateAssistantMessage`) while the
 * shell executor's IO thread keeps mutating the same ArrayList (the next
 * `onBlockUpdate` rewrites a block, the turn tail appends, the length-wall trim
 * removes). Two threads on one ArrayList = `ConcurrentModificationException` on
 * the MAIN thread, i.e. a crash on every compile/test run that streams fast
 * multi-line output.
 *
 * The fix: snapshot inside the writer thread ([snapshotToolBlocksForHandoff]),
 * hand the snapshot over, and let the consumer walk only stable data.
 *
 * These tests drive the exact interleaving: a WRITER thread owns the live list
 * and publishes snapshots, a READER thread walks every snapshot it receives.
 */
class ToolBlockHandoffSnapshotTest {

    private fun block(id: String, content: String = "line 1") = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = content,
        toolName = "shell_execute",
    )

    private fun listOfBlocks(vararg ids: String): MutableList<AssistantBlock> =
        ids.map { block(it) }.toMutableList()

    // ── the properties the fix depends on ────────────────────────────────

    @Test
    fun `a snapshot does not follow later mutation of the live list`() {
        val live = listOfBlocks("b1", "b2")
        val snapshot = snapshotToolBlocksForHandoff(live)

        // The writer keeps working after the hand-off — exactly what the shell
        // executor does while the Main-thread coroutine renders.
        live[0] = live[0].copy(content = "rewritten")
        live.add(block("b3"))
        live.removeAt(0)
        live.clear()

        assertEquals("snapshot keeps what it had", 2, snapshot.size)
        assertEquals("b1", snapshot[0].id)
        assertEquals("b2", snapshot[1].id)
        assertEquals("the element the writer replaced is untouched in the snapshot", "line 1", snapshot[0].content)
    }

    @Test
    fun `the handed-off list is a different instance`() {
        val live = listOfBlocks("b1")
        val snapshot = snapshotToolBlocksForHandoff(live)
        assertTrue("the consumer must never receive the producer's mutable instance", snapshot !== live)
    }

    @Test
    fun `a hand-off keeps block order and content verbatim`() {
        val live = mutableListOf(
            block("t1", "first"),
            block("t2", "second"),
            block("t3", "third"),
        )
        val snapshot = snapshotToolBlocksForHandoff(live)
        assertEquals(listOf("t1", "t2", "t3"), snapshot.map { it.id })
        assertEquals(listOf("first", "second", "third"), snapshot.map { it.content })
    }

    @Test
    fun `an empty list stays empty`() {
        assertTrue(snapshotToolBlocksForHandoff(mutableListOf()).isEmpty())
    }

    // ── the writer-thread update (the block rewrite + hand-off) ──────────

    @Test
    fun `a flush rewrites only its own block and hands over the whole list`() {
        val live = mutableListOf(block("t1", "old 1"), block("t2", "old 2"), block("t3", "old 3"))
        val snapshot = applyShellBlockContentUpdate(live, toolId = "t2", displayContent = "streamed tail")

        assertEquals("the target block is rewritten in the live list", "streamed tail", live[1].content)
        assertEquals("other blocks untouched", "old 1", live[0].content)
        assertEquals("other blocks untouched", "old 3", live[2].content)
        assertEquals("the snapshot carries the order", listOf("t1", "t2", "t3"), snapshot?.map { it.id })
        assertEquals("the snapshot carries the NEW content", "streamed tail", snapshot!![1].content)
        assertTrue("the snapshot is not the live list", snapshot !== live)
    }

    @Test
    fun `a flush for a block that is gone publishes nothing`() {
        val live = mutableListOf(block("t1"))
        assertNull("no block → no publish (the old code also did nothing here)", applyShellBlockContentUpdate(live, "t2", "x"))
        assertEquals("the live list is untouched", "line 1", live[0].content)
    }

    @Test
    fun `a flush on an empty list publishes nothing`() {
        assertNull(applyShellBlockContentUpdate(mutableListOf(), "t1", "x"))
    }

    @Test
    fun `the update survives a list that shrinks mid-flight`() {
        // A cycling list whose `get` fails for indexes beyond a shrinking size
        // models the structural change the loop coroutine can make while the
        // IO thread walks the block list.
        val backing = mutableListOf(block("t1"), block("t2"))
        var calls = 0
        val shrinking = object : AbstractMutableList<AssistantBlock>() {
            override val size: Int get() = if (calls > 1) 1 else backing.size
            override fun get(index: Int): AssistantBlock {
                calls++
                if (index >= size) throw IndexOutOfBoundsException("shrunk")
                return backing[index]
            }
            override fun add(index: Int, element: AssistantBlock) = throw UnsupportedOperationException()
            override fun removeAt(index: Int): AssistantBlock = throw UnsupportedOperationException()
            override fun set(index: Int, element: AssistantBlock): AssistantBlock {
                backing[index] = element
                return element
            }
        }
        // Must not throw, and must not publish a block it could not read.
        val snapshot = applyShellBlockContentUpdate(shrinking, "t1", "chunk")
        assertTrue("either a consistent single-block snapshot or nothing", snapshot == null || snapshot.size <= 1)
    }

    // ── the interleaving the defect needs: writer mutates, reader walks ───

    @Test
    fun `a reader walking published snapshots never sees a ConcurrentModificationException`() {
        val live = listOfBlocks("b0")
        val published = AtomicReference<List<AssistantBlock>>(emptyList())
        val failed = AtomicReference<Throwable?>(null)
        val stop = AtomicBoolean(false)
        val readerDone = CountDownLatch(1)

        // WRITER: the shell executor's IO thread. It mutates the live list and
        // takes the snapshot itself (the production shape after the fix).
        val writer = Thread {
            var i = 1
            try {
                while (!stop.get()) {
                    live.add(block("w$i"))
                    if (live.size > 40) live.removeAt(0)
                    live[live.lastIndex] = live.last().copy(content = "chunk $i")
                    published.set(snapshotToolBlocksForHandoff(live))
                    i++
                }
            } catch (t: Throwable) {
                failed.compareAndSet(null, t)
            }
        }
        writer.start()

        // READER: the Main-thread coroutine. It only ever walks a snapshot.
        val reader = Thread {
            try {
                repeat(20_000) {
                    var total = 0
                    for (b in published.get()) total += b.id.length + b.content.length
                    if (total < 0) return@repeat
                }
            } catch (t: Throwable) {
                failed.compareAndSet(null, t)
            } finally {
                readerDone.countDown()
            }
        }
        reader.start()

        assertTrue("reader finished", readerDone.await(60, TimeUnit.SECONDS))
        stop.set(true)
        writer.join(30_000)

        assertNull("no ConcurrentModificationException (or any throwable): ${failed.get()}", failed.get())
    }

    /**
     * Negative control: the OLD shape — the consumer walks the LIVE mutable list
     * while the writer thread structurally modifies it — does throw. Without
     * this, the test above would pass even if the snapshot function returned the
     * live list, i.e. a fix with no teeth.
     */
    @Test
    fun `negative control - walking the live list under a concurrent writer throws`() {
        val live = mutableListOf(block("b0"))
        val stop = AtomicBoolean(false)
        val sawCme = AtomicBoolean(false)
        val readerDone = CountDownLatch(1)

        val writer = Thread {
            var i = 1
            try {
                while (!stop.get()) {
                    live.add(block("w$i"))
                    if (live.size > 40) live.removeAt(0)
                    i++
                }
            } catch (_: Throwable) {
            }
        }

        val reader = Thread {
            try {
                repeat(200_000) {
                    var total = 0
                    // Plain for-each over the LIVE list — the pre-fix consumer.
                    for (b in live) total += b.id.length
                    if (total < 0) return@repeat
                }
            } catch (_: java.util.ConcurrentModificationException) {
                sawCme.set(true)
            } catch (_: Throwable) {
            } finally {
                readerDone.countDown()
            }
        }
        writer.start()
        reader.start()
        readerDone.await(60, TimeUnit.SECONDS)
        stop.set(true)
        writer.join(30_000)

        assertTrue(
            "control: the live-list shape must be able to throw CME, otherwise the snapshot " +
                "test proves nothing",
            sawCme.get(),
        )
    }
}
