package com.rikkaminis.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The auto-pin that keeps a dispatching session on top while its children
 * update their own rows.
 *
 * [INCR-1117B A4] These are BEHAVIOUR tests: every one of them drives real
 * coroutines through [SubagentDispatchPin.begin] / [SubagentDispatchPin.end] and
 * asserts on what the fake `pinned_at` row ends up as. The pre-fix tests
 * asserted on the ORDER OF TOKENS in the call-site source, which stayed green
 * when the Room calls were moved out of the window (the very regression the
 * ordering was supposed to catch).
 *
 * Contracts covered:
 *  - the first window pins, the last one restores;
 *  - a window opened while the previous restore is still in flight must not be
 *    unpinned by that restore (A3-a);
 *  - a cancellation that lands between the pin write and its bookkeeping must
 *    still restore (A3-b);
 *  - a Room failure — or a cancellation escaping a Room call — while opening a
 *    window rolls the count back and is rethrown, so the window that could not
 *    be opened leaves nothing behind (F-01);
 *  - a pin the user takes during the window, or one they took before it,
 *    survives.
 */
class SubagentDispatchPinTest {

    @Before
    fun reset() = SubagentDispatchPin.clear()

    @Test
    fun `the first window pins and the last window restores`() = runBlocking {
        val repo = FakeRepo()
        val depth = open(SID, repo)
        assertEquals("the first window is the one that pins", 1, depth)
        assertEquals(100L, repo.value(SID))
        assertEquals(1, repo.pinWrites)

        close(SID, repo)
        assertNull("closing the only window restores the row", repo.value(SID))
        assertEquals(1, repo.unpinWrites)
        assertFalse(SubagentDispatchPin.isDispatching(SID))
    }

    @Test
    fun `A3-a a window opened while the previous restore is in flight pins again`() = runBlocking {
        // Pre-fix sequence: the count dropped to 0 FIRST, and only then did the
        // restore do its two suspend Room calls. A new window opened in that
        // gap read the pin the old window was about to undo, decided the user
        // had pinned it, and was left unpinned when the old restore landed.
        val repo = FakeRepo()
        val restoreParked = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        repo.readGate = { n ->
            if (n == 2) {                       // read #2 = the restore's read
                restoreParked.complete(Unit)
                releaseRestore.await()
            }
        }

        // Window 1 opens and closes; its restore parks on read #2.
        val first = launch {
            open(SID, repo)
            close(SID, repo)
        }
        withTimeout(5_000) { restoreParked.await() }

        repo.nextValue = 200L
        val second = launch { open(SID, repo) }  // window 2 opens here
        delay(150)                               // it does all it can while #1 is parked
        releaseRestore.complete(Unit)
        first.join()
        second.join()

        assertNotNull(
            "a dispatching session must be pinned — window 1's restore must not " +
                "undo window 2's decision",
            repo.value(SID),
        )
        assertEquals("window 2 had to pin again, not ride on the pin #1 undid", 200L, repo.value(SID))

        close(SID, repo)
        assertNull(repo.value(SID))
    }

    @Test
    fun `A3-b a cancel between the pin write and its bookkeeping still restores`() = runBlocking {
        // The row is pinned, the continuation has not returned yet, and the
        // user stops: the record must still exist so the restore can undo it.
        val repo = FakeRepo()
        val writeParked = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        repo.afterWriteGate = {
            writeParked.complete(Unit)
            releaseWrite.await()
        }

        val job = launch {
            open(SID, repo)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                close(SID, repo)
            }
        }
        withTimeout(5_000) { writeParked.await() }
        assertEquals("the pin write landed before the cancel", 100L, repo.value(SID))

        job.cancel()
        releaseWrite.complete(Unit)
        job.join()

        assertNull("a cancelled dispatch must not leave the parent pinned", repo.value(SID))
        assertFalse(SubagentDispatchPin.isDispatching(SID))
    }

    @Test
    fun `F-01 a Room failure while reading the current pin rolls the depth back`() = runBlocking {
        // Pre-fix: the count was taken BEFORE the two suspend Room calls and no
        // catch sat around them, so a Room failure left depth == 1 forever —
        // every later dispatch read "a window is in flight" and waited on, or
        // refused to take, a pin that would never close.
        val repo = FakeRepo()
        val boom = RuntimeException("disk full")
        repo.readThrows = boom

        val thrown = try {
            open(SID, repo)
            null
        } catch (t: Throwable) {
            t
        }
        assertThrown(boom, thrown)
        assertFalse(
            "a window that could not be opened must not leave a count behind",
            SubagentDispatchPin.isDispatching(SID),
        )
        assertEquals("nothing was written", 0, repo.pinWrites)

        repo.readThrows = null
        assertEquals("the next window is the FIRST window again", 1, open(SID, repo))
        assertEquals("so it takes the pin itself", 1, repo.pinWrites)
        assertEquals(100L, repo.value(SID))
    }

    @Test
    fun `F-01 a Room failure before the pin write rolls the depth back`() = runBlocking {
        val repo = FakeRepo()
        val boom = IllegalStateException("db closed")
        repo.pinThrows = boom

        val thrown = try {
            open(SID, repo)
            null
        } catch (t: Throwable) {
            t
        }
        assertThrown(boom, thrown)
        assertFalse(SubagentDispatchPin.isDispatching(SID))
        assertEquals(0, repo.pinWrites)

        repo.pinThrows = null
        assertEquals(1, open(SID, repo))
        assertEquals(1, repo.pinWrites)
        assertEquals(100L, repo.value(SID))
    }

    @Test
    fun `F-01 a cancellation escaping the pin write rolls the depth back and is rethrown`() = runBlocking {
        // The rollback must not swallow the cancellation: a caller that is
        // stopping has to still see its own cancellation.
        val repo = FakeRepo()
        val cancelled = CancellationException("dispatch cancelled")
        repo.pinThrowsAfterWrite = cancelled

        val thrown = try {
            open(SID, repo)
            null
        } catch (t: Throwable) {
            t
        }
        assertThrown(cancelled, thrown)
        assertFalse("the count is rolled back even for a cancellation", SubagentDispatchPin.isDispatching(SID))

        repo.pinThrowsAfterWrite = null
        assertEquals(1, open(SID, repo))
        assertEquals(1, repo.pinWrites)
    }

    @Test
    fun `F-01 a pin write that lands and then fails rolls the depth back`() = runBlocking {
        // The row write landed and the continuation failed: the count is still
        // given back, and the row is left alone because the value written is
        // unknown — blindly unpinning could undo a newer pin the user took.
        val repo = FakeRepo()
        val boom = RuntimeException("write landed, continuation failed")
        repo.pinThrowsAfterWrite = boom

        val thrown = try {
            open(SID, repo)
            null
        } catch (t: Throwable) {
            t
        }
        assertThrown(boom, thrown)
        assertFalse(SubagentDispatchPin.isDispatching(SID))
        assertEquals("the write landed before it failed", 100L, repo.value(SID))
        assertEquals(1, repo.pinWrites)

        repo.pinThrowsAfterWrite = null
        assertEquals(1, open(SID, repo))
        assertEquals("the row is read as an existing pin, not adopted as ours", 1, repo.pinWrites)
        // Registered residual (see the report): with no record of what was
        // written, the close cannot undo it — the row stays pinned.
        assertTrue(close(SID, repo))
        assertEquals(0, repo.unpinWrites)
        assertEquals(100L, repo.value(SID))
    }

    @Test
    fun `a manual pin taken during the window survives the restore`() = runBlocking {
        val repo = FakeRepo()
        open(SID, repo)
        repo.userPin(SID, 999L)                 // newer value = the user's pin
        close(SID, repo)
        assertEquals("we only undo the value we wrote", 999L, repo.value(SID))
        assertEquals(0, repo.unpinWrites)
    }

    @Test
    fun `a session the user already pinned is not adopted as ours`() = runBlocking {
        val repo = FakeRepo()
        repo.userPin(SID, 42L)
        open(SID, repo)
        close(SID, repo)
        assertEquals("nobody else's pin is rewritten or undone", 0, repo.pinWrites)
        assertEquals(0, repo.unpinWrites)
        assertEquals(42L, repo.value(SID))
    }

    @Test
    fun `overlapping dispatches pin once and restore once`() = runBlocking {
        val repo = FakeRepo()
        val aClosed = CompletableDeferred<Unit>()
        val bClosed = CompletableDeferred<Unit>()
        val a = launch {
            open(SID, repo)
            try { CompletableDeferred<Unit>().await() } finally {
                close(SID, repo)
                aClosed.complete(Unit)
            }
        }
        val b = launch {
            open(SID, repo)
            try { CompletableDeferred<Unit>().await() } finally {
                close(SID, repo)
                bClosed.complete(Unit)
            }
        }
        withTimeout(5_000) { while (repo.pinWrites == 0) delay(5) }
        assertEquals("the second window rides on the first one's pin", 1, repo.pinWrites)

        a.cancel()
        withTimeout(5_000) { aClosed.await() }
        assertEquals("one dispatch is still running, so the pin stays", 100L, repo.value(SID))
        assertEquals("and it must not restore yet", 0, repo.unpinWrites)

        b.cancel()
        withTimeout(5_000) { bClosed.await() }
        assertEquals("the last window restores exactly once", 1, repo.unpinWrites)
        assertNull(repo.value(SID))
        assertFalse(SubagentDispatchPin.isDispatching(SID))
    }

    @Test
    fun `sessions are counted independently`() = runBlocking {
        val repo = FakeRepo()
        open("a", repo)
        open("b", repo)
        close("b", repo)
        assertNull(repo.value("b"))
        assertEquals("closing b must not touch a", 100L, repo.value("a"))
        assertTrue(SubagentDispatchPin.isDispatching("a"))
        assertEquals(1, repo.unpinWrites)
        close("a", repo)
        assertNull(repo.value("a"))
        assertEquals(2, repo.unpinWrites)
    }

    @Test
    fun `clear forgets every session and leaves no restore behind`() = runBlocking {
        val repo = FakeRepo()
        open("a", repo)
        open("b", repo)
        SubagentDispatchPin.clear()
        assertFalse(SubagentDispatchPin.isDispatching("a"))
        assertFalse(SubagentDispatchPin.isDispatching("b"))
        // A close after teardown must be harmless, and must not unpin anything
        // it no longer has a record for.
        assertTrue(close("a", repo))
        assertEquals(0, repo.unpinWrites)
        assertEquals(100L, repo.value("a"))
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * The failure that escaped [SubagentDispatchPin.begin] is the SAME KIND with
     * the SAME message as the one the fake threw.
     *
     * Deliberately not `assertSame`: kotlinx.coroutines' stacktrace recovery
     * COPIES an exception that crosses a suspend boundary (the copy keeps the
     * class and the message, not the identity), so an identity assertion is
     * green or red depending on the coroutines version and the debug flag —
     * which is exactly what happened here: the local harness (coroutines
     * 1.8.1) kept the instance and CI did not. Identity was never the contract;
     * "the failure is not swallowed and not wrapped" is.
     */
    private fun assertThrown(expected: Throwable, actual: Throwable?) {
        assertNotNull("the failure must reach the caller, not be swallowed", actual)
        assertEquals("the failure keeps its class", expected.javaClass, actual!!.javaClass)
        assertEquals("the failure keeps its message", expected.message, actual.message)
    }

    private suspend fun open(sid: String, repo: FakeRepo): Int =
        SubagentDispatchPin.begin(
            sessionId = sid,
            readPinnedAt = { repo.read(it) },
            pin = { repo.pin(it, true) },
        )

    private suspend fun close(sid: String, repo: FakeRepo): Boolean =
        SubagentDispatchPin.end(
            sessionId = sid,
            readPinnedAt = { repo.read(it) },
            unpin = { repo.pin(it, false) },
        )

    /** Stand-in for `ChatRepository.getSession(...)?.pinnedAt` / `pinSession(...)`,
     *  with the two suspension windows a real Room call has. */
    private class FakeRepo {
        private val rows = HashMap<String, Long?>()
        var nextValue: Long = 100L
        var pinWrites = 0
        var unpinWrites = 0
        private var reads = 0
        var readGate: (suspend (Int) -> Unit)? = null
        var afterWriteGate: (suspend () -> Unit)? = null
        /** Room failure to inject: thrown by the read, before it returns. */
        var readThrows: Throwable? = null
        /** Room failure to inject: thrown by a pin write, before it lands. */
        var pinThrows: Throwable? = null
        /** Room failure to inject: the pin write lands, then this is thrown. */
        var pinThrowsAfterWrite: Throwable? = null

        fun value(sid: String): Long? = rows[sid]

        fun userPin(sid: String, value: Long) {
            rows[sid] = value
        }

        suspend fun read(sid: String): Long? {
            reads += 1
            readGate?.invoke(reads)
            readThrows?.let { throw it }
            return rows[sid]
        }

        suspend fun pin(sid: String, pinned: Boolean): Long? {
            val beforeWrite = pinThrows
            if (pinned && beforeWrite != null) throw beforeWrite
            val value = if (pinned) nextValue else null
            rows[sid] = value
            if (pinned) pinWrites += 1 else unpinWrites += 1
            afterWriteGate?.invoke()
            val afterWrite = pinThrowsAfterWrite
            if (pinned && afterWrite != null) throw afterWrite
            return value
        }
    }

    private companion object {
        const val SID = "parent"
    }
}
