package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-T6 D9 F-07] The `stall-*.log` session dialect: one reader, and — until
 * this change — no producer.
 *
 * `LaunchCycleBeacon.readLastSessionId` opens the newest `stall-*.log`, reads
 * its first 2 KB and greps `session=<id>` to answer "which chat was on screen
 * when the previous run died?". `HangDetector.writeStallSample` is the only
 * writer of those files, and it never wrote a session: the id went to the
 * JankDiag `println` (stdout → daily log, which the beacon does not read), so
 * every reported `lastSessionId` was the literal "unknown" — a diagnostic field
 * that looked wired and answered nothing.
 *
 * Pinned here: the wire (the producer writes the field, before the 25 stack
 * frames so it stays inside the reader's window) and the dialect (reader and
 * producer agree on the exact spelling), each with a negative control.
 */
class StallSessionDialectTest {

    private val hangSource = "src/main/java/com/rikkaminis/app/diagnostics/HangDetector.kt"

    private val uuid = "3f5a1c88-9e2b-4d1a-b0f7-0c9a1b2c3d4e"

    // ─── the reader ─────────────────────────────────────────────────────────

    @Test
    fun `the beacon parses the session field the detector writes`() {
        // Verbatim header shape from writeStallSample, with the session line the
        // producer now emits.
        val head = "===== HANG @ 21:01:02.345 (duration ~3500ms) sample=mid-hang escalation=0 =====\n" +
            "session=$uuid\n" +
            "thread: main\n" +
            "  at com.rikkaminis.app.MainActivity.onCreate(MainActivity.kt:200)\n"
        assertEquals(uuid, parseStallSessionId(head))
    }

    @Test
    fun `a stall sample with no large message on screen has no session`() {
        val head = "===== HANG @ 21:01:02.345 (duration ~3500ms) sample=mid-hang escalation=0 =====\n" +
            "thread: main\n"
        assertNull(parseStallSessionId(head))
    }

    @Test
    fun `a colon separator is still accepted`() {
        assertEquals(uuid, parseStallSessionId("session: $uuid\n"))
    }

    @Test
    fun `the render fingerprint field is not mistaken for the session field`() {
        // ContentDiag.currentRenderLogFields() emits `renderSession=<id>` in the
        // JankDiag stdout line. The reader is case-sensitive on "session[=:]",
        // so a capital "S" inside another key must not satisfy it — otherwise a
        // future copy of that line into the file would silently report the
        // render marker as the chat.
        assertNull(parseStallSessionId("renderSession=$uuid\n"))
    }

    @Test
    fun `a truncated id is not accepted`() {
        // The regex requires 8+ hex/dash characters: a half-written line must
        // not produce a bogus session.
        assertNull(parseStallSessionId("session=3f5a\n"))
    }

    // ─── the producer ───────────────────────────────────────────────────────

    @Test
    fun `the stall writer emits the session field before the stack frames`() {
        assertProducerWritesSession(source())
    }

    private fun assertProducerWritesSession(body: String) {
        val writer = body.substringAfter("private fun writeStallSample(")
        val emit = writer.indexOf("ContentDiag.currentRenderSessionId()")
        assertTrue("writeStallSample must take the on-screen session id", emit >= 0)
        assertTrue(
            "the field must be written as `session=<id>` (the reader's dialect)",
            writer.contains("builder.append(\"session=\$it\\n\")"),
        )
        val threadLine = writer.indexOf("builder.append(\"thread: main\\n\")")
        assertTrue("the header must still open the sample", threadLine >= 0)
        assertTrue(
            "the session line must come BEFORE the 25 stack frames (thread@$threadLine, session@$emit) — " +
                "readLastSessionId only reads the first 2 KB",
            emit < threadLine,
        )
    }

    // ─── negative controls ──────────────────────────────────────────────────

    @Test
    fun `the producer probe rejects a writer without the session field`() {
        val real = source()
        val stripped = real.replace(
            "        ContentDiag.currentRenderSessionId()?.let { builder.append(\"session=\$it\\n\") }\n",
            "",
        )
        assertFalse("the mutation must really remove the producer line", stripped == real)
        val rejected = runCatching { assertProducerWritesSession(stripped) }.isFailure
        assertTrue("the probe must reject a writer that no longer emits the field", rejected)
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, hangSource)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$hangSource not found from user.dir")
    }
}
