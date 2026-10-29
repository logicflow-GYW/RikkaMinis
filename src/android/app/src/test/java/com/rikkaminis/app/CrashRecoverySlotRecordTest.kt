package com.rikkaminis.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.rikkaminis.app.config.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r5-d2 B-F-02] The crash-recovery slot vs. this app's draft lifecycle.
 *
 * Two gaps, both invisible while the store looked like a plain "last session
 * id" file:
 *
 *  1. **A draft entry kept the old record.** `record()` returned early for a
 *     composer draft key (`__new__<uuid>`), which is right — there is no row
 *     behind a draft, so it must never become a restore target — but "write
 *     nothing" left the record pointing at the conversation the user had just
 *     walked away from. A native crash / LMK restart then reopened that old
 *     chat while the user was actually typing in a brand-new one.
 *  2. **Promotion wrote nothing either.** The nav listener had already fired
 *     for this chat while its route argument was still a draft key, and
 *     `ChatViewModelStore.rename` only touches the alias table — so
 *     `currentBackStackEntryFlow` never emits the real id. `ensureSession()` is
 *     therefore the only place that can record it.
 *
 * Layer 1 below drives the REAL [CrashRecoveryStore] against an in-memory
 * `SharedPreferences` (`ContextWrapper(null)` + one override, the house pattern
 * from `SoulStoreWriteVersionTest`), so the behaviour under test is the shipped
 * one, not a mirror. Layer 2 pins the `ensureSession()` write site, which needs
 * a whole ViewModel + database and can only be read as source shape — every
 * shape assertion carries an in-test negative control, so a probe that has
 * quietly stopped looking fails loudly.
 */
class CrashRecoverySlotRecordTest {

    private class StubContext(
        private val prefs: FakeSharedPreferences,
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private fun ctx() = StubContext(FakeSharedPreferences())

    private val real = "6f97e418-7498-4426-9cb2-a2784dc97240"

    /** The exact shape `ComposerDraftStore.nextDraftId` mints. */
    private val draft = "__new__draft-0"

    // ── 1. behaviour of the shipped store ────────────────────────────────

    @Test
    fun `entering a composer draft drops the recorded chat`() {
        val c = ctx()
        CrashRecoveryStore.record(c, real, 1_000L)
        assertEquals(real, CrashRecoveryStore.sessionId(c))

        CrashRecoveryStore.record(c, draft, 2_000L)

        // The whole defect in one assertion: a crash now restores nothing,
        // instead of restoring the chat the user already left.
        assertNull(CrashRecoveryStore.sessionId(c))
        assertEquals(0L, CrashRecoveryStore.writtenAtMs(c))
    }

    @Test
    fun `a draft entered with no previous record stays empty`() {
        val c = ctx()
        CrashRecoveryStore.record(c, draft, 2_000L)
        assertNull(CrashRecoveryStore.sessionId(c))
        assertEquals(0L, CrashRecoveryStore.writtenAtMs(c))
    }

    @Test
    fun `a promoted chat after the draft is recorded with its own timestamp`() {
        val c = ctx()
        CrashRecoveryStore.record(c, real, 1_000L)
        CrashRecoveryStore.record(c, draft, 2_000L)
        val promoted = "0cda0f0e-0000-4000-8000-000000000001"

        CrashRecoveryStore.record(c, promoted, 3_000L)

        assertEquals(promoted, CrashRecoveryStore.sessionId(c))
        assertEquals(3_000L, CrashRecoveryStore.writtenAtMs(c))
    }

    @Test
    fun `a non-chat route never disturbs a live record`() {
        val c = ctx()
        CrashRecoveryStore.record(c, real, 5_000L)

        // Settings / list screens send a blank id through the same entry
        // point; clearing there would throw away a perfectly good restore
        // target every time the user opens a drawer.
        CrashRecoveryStore.record(c, "", 6_000L)
        CrashRecoveryStore.record(c, "   ", 7_000L)

        assertEquals(real, CrashRecoveryStore.sessionId(c))
        assertEquals(5_000L, CrashRecoveryStore.writtenAtMs(c))
    }

    @Test
    fun `slot write table`() {
        // Literals on purpose: a table derived from the predicates under test
        // would let a flipped branch drag the expectation down with it.
        assertEquals(CrashRecoveryStore.SlotWrite.WRITE, CrashRecoveryStore.slotWriteFor(real))
        assertEquals(CrashRecoveryStore.SlotWrite.CLEAR, CrashRecoveryStore.slotWriteFor(draft))
        assertEquals(CrashRecoveryStore.SlotWrite.IGNORE, CrashRecoveryStore.slotWriteFor(""))
        assertEquals(CrashRecoveryStore.SlotWrite.IGNORE, CrashRecoveryStore.slotWriteFor("   "))
        assertEquals(CrashRecoveryStore.SlotWrite.IGNORE, CrashRecoveryStore.slotWriteFor(null))
    }

    @Test
    fun `clearIfMatches keeps a record it does not own`() {
        val c = ctx()
        CrashRecoveryStore.record(c, "someone-else", 9_000L)

        CrashRecoveryStore.clearIfMatches(c, real)

        assertEquals("someone-else", CrashRecoveryStore.sessionId(c))
        assertEquals(9_000L, CrashRecoveryStore.writtenAtMs(c))
    }

    // ── 2. the promotion write site (source shape) ───────────────────────

    private val entryRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionEntryExt.kt"

    @Test
    fun `ensureSession records the promoted id and does it after the id exists`() {
        val code = stripCommentsAndLiterals(readRepoFile(entryRel))
        val body = fnBody(code, "fun ChatViewModel.ensureSession(")

        val idIdx = body.indexOf("realSessionId = session.id")
        val recIdx = body.indexOf("CrashRecoveryStore.record(")
        assertTrue("ensureSession must still assign realSessionId = session.id", idIdx >= 0)
        assertTrue("ensureSession must record the promoted session", recIdx >= 0)
        assertTrue(
            "the record must come AFTER the real id exists (recording the draft key is a no-op)",
            recIdx > idIdx,
        )
        val close = body.indexOf(')', recIdx)
        assertTrue("the record call must be closed", close > recIdx)
        assertTrue(
            "the record must persist the promoted id itself, not the draft route key",
            body.substring(recIdx, close).contains("session.id"),
        )
    }

    @Test
    fun `the promotion probe rejects a source that records too early or not at all`() {
        // Negative control: the same probe must fail on both mutations.
        val shipped = fnBody(
            stripCommentsAndLiterals(readRepoFile(entryRel)),
            "fun ChatViewModel.ensureSession(",
        )
        val early = "fun ChatViewModel.ensureSession() { " +
            "CrashRecoveryStore.record(context.applicationContext, session.id) " +
            "realSessionId = session.id }"
        val missing = "fun ChatViewModel.ensureSession() { realSessionId = session.id }"

        assertEquals(false, probeRecordsAfterId(early))
        assertEquals(false, probeRecordsAfterId(missing))
        assertEquals(true, probeRecordsAfterId(shipped))
    }

    private fun probeRecordsAfterId(body: String): Boolean {
        val idIdx = body.indexOf("realSessionId = session.id")
        val recIdx = body.indexOf("CrashRecoveryStore.record(")
        if (idIdx < 0 || recIdx < 0 || recIdx <= idIdx) return false
        val close = body.indexOf(')', recIdx)
        if (close < 0) return false
        return body.substring(recIdx, close).contains("session.id")
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun fnBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) throw AssertionError("marker not found: $marker")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("no body for: $marker")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced body for: $marker")
    }

    /** Blanks comments and string/char literals, preserving offsets. */
    private fun stripCommentsAndLiterals(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i + 1 < n) {
                        out.append("  ")
                        i += 2
                    }
                }
                src.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < n && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i + 2 < n) {
                        out.append("   ")
                        i += 3
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < n) {
                        if (src[i] == '\\') {
                            out.append("  ")
                            i += 2
                            continue
                        }
                        if (src[i] == quote) {
                            out.append(' ')
                            i++
                            break
                        }
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
