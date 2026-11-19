package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * [fullscan-1119 F-06] `switchModelAndRerun()`'s DB re-sync used a bare
 * `catch (e: Exception)` that turned cancellation into a switch failure.
 *
 * `CancellationException` IS an `Exception`, so pressing Stop (or switching
 * models twice quickly) showed the user "Model switch failed:
 * StandaloneCoroutine was cancelled" over a turn they had just stopped.
 * [rethrowCancellationOr] rethrows cancellation and routes every other failure
 * through the audited banner path.
 *
 * Both halves are pinned: a CE that gets swallowed fails the first test, and a
 * real IO failure that stops surfacing the banner fails the second.
 */
class ModelSwitchCancellationTest {

    @Test
    fun `cancellation propagates instead of surfacing a switch failure`() {
        var surfaced: String? = null
        try {
            rethrowCancellationOr(CancellationException("stopped")) {
                surfaced = "Model switch failed: ${it.message}"
            }
            fail("CancellationException was swallowed — Stop becomes a visible switch failure")
        } catch (_: CancellationException) {
            // expected: cancellation propagates
        }
        assertNull("no banner may be shown for a cancelled switch", surfaced)
    }

    @Test
    fun `a real IO failure still surfaces Model switch failed`() {
        var surfaced: String? = null
        rethrowCancellationOr(IOException("disk gone")) {
            surfaced = "Model switch failed: ${it.message}"
        }
        assertEquals("real failures must keep the audited banner", "Model switch failed: disk gone", surfaced)
    }

    @Test
    fun `the switch setup catch routes through the shared policy`() {
        val body = ChatSourceSlices.function(
            "ChatModelRouting.kt",
            "internal fun ChatViewModel.switchModelAndRerun(",
        )
        val catchAt = body.indexOf("switchModelAndRerun: DB re-sync failed")
        assertTrue("the DB re-sync failure log is missing", catchAt >= 0)
        val window = body.substring(maxOf(0, catchAt - 900), minOf(body.length, catchAt + 400))
        assertTrue(
            "the catch must route through rethrowCancellationOr",
            window.contains("rethrowCancellationOr(e)"),
        )
        val oldShape = "_error.value = \"Model switch failed: \$" + "{e.message}\""
        assertFalse(
            "the pre-fix arm used the raw exception, so a CE reached the banner",
            window.contains(oldShape),
        )
    }
}
