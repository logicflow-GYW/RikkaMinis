package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/offload-payload-stub] Pure-JVM tests for the disk-offload stub guard.
 *
 * The regression this pins: a context offload replaced a `file_write` payload
 * with a `[CONTEXT OFFLOADED] …` stub, the model then sent that stub back as
 * `content`, and the tool wrote ~150 bytes over a 3714-byte file while
 * reporting success.
 */
class OffloadedPayloadGuardTest {

    /** Verbatim from the field (2026-09-14, session 051f8698). */
    private val realStub =
        "[CONTEXT OFFLOADED] Content (~1103 tokens, 3936 bytes) saved to: " +
            "/var/minis/offloads/tools/file_write_0lBaMiNz5926.txt\n" +
            "Use file_read tool to retrieve if needed."

    private val stubPath = "/var/minis/offloads/tools/file_write_0lBaMiNz5926.txt"

    // ── asStub ─────────────────────────────────────────────────────────────

    @Test
    fun `field stub is detected and its offload path extracted`() {
        val stub = OffloadedPayloadGuard.asStub(realStub)
        assertNotNull(stub)
        assertEquals(stubPath, stub!!.offloadPath)
    }

    @Test
    fun `stub with no usable path still counts as a stub`() {
        // ContextOffload.offloadContent returns "" when the write fails, so a
        // stub can be minted with `saved to: ` followed by nothing. It is still
        // a pointer, and writing it is still corruption.
        val noPath = "[CONTEXT OFFLOADED] Content (~10 tokens, 40 bytes) saved to: \n" +
            "Use file_read tool to retrieve if needed."
        assertNotNull(OffloadedPayloadGuard.asStub(noPath))
        assertNull(OffloadedPayloadGuard.asStub(noPath)!!.offloadPath)
    }

    @Test
    fun `stub format change degrades to detected-but-pathless instead of undetected`() {
        // Detection is anchored on the prefix, NOT on the "saved to: " marker,
        // so a future format tweak cannot silently disable the guard.
        val drifted = "[CONTEXT OFFLOADED] Content (1.1k tokens) stored in some_file.txt"
        val stub = OffloadedPayloadGuard.asStub(drifted)
        assertNotNull(stub)
        assertNull(stub!!.offloadPath)
    }

    @Test
    fun `real content is not mistaken for a stub`() {
        assertNull(OffloadedPayloadGuard.asStub("package com.rikkaminis.app\n\nclass Foo"))
        assertNull(OffloadedPayloadGuard.asStub(""))
    }

    @Test
    fun `content merely mentioning the marker mid-text is left alone`() {
        // Anti-overreach: documentation and tests legitimately quote the marker.
        // Only a marker in the payload's own first position is a stub.
        val doc = "The stub prefix is [CONTEXT OFFLOADED] Content (~N tokens, M bytes) saved to: x"
        assertNull(OffloadedPayloadGuard.asStub(doc))
    }

    // ── decide ─────────────────────────────────────────────────────────────

    @Test
    fun `normal payload writes as-is and never consults the offload store`() {
        var recoverCalls = 0
        val action = OffloadedPayloadGuard.decide("hello", append = false) {
            recoverCalls++
            "SHOULD NOT BE USED"
        }
        assertEquals(OffloadedPayloadGuard.Action.WriteAsIs, action)
        assertEquals(0, recoverCalls)
    }

    @Test
    fun `resolvable stub on an overwrite heals from the offload file`() {
        // The store must hand back exactly the byte count the stub advertises:
        // that equality is what separates a real stub from a document that
        // merely looks like one (see the look-alike test below).
        val realBytes = "package x\n// the real payload".padEnd(3936, '.')
        val action = OffloadedPayloadGuard.decide(realStub, append = false) { path ->
            assertEquals(stubPath, path)
            realBytes
        }
        assertTrue(action is OffloadedPayloadGuard.Action.Heal)
        val heal = action as OffloadedPayloadGuard.Action.Heal
        assertEquals(realBytes, heal.content)
        assertEquals(stubPath, heal.from)
    }

    @Test
    fun `look-alike document is refused instead of being overwritten`() {
        // [audit-0914] Anti-overreach for the healing path. Content that opens
        // with a byte-accurate stub header — a doc quoting a stub, a fixture —
        // used to be healed, i.e. its body replaced by whatever file the quoted
        // path resolved to. Recovery returning a different size is the tell, and
        // the safe direction is refusal (the stub itself must never be written).
        val lookAlike = "[CONTEXT OFFLOADED] Content (~1103 tokens, 3936 bytes) saved to: " +
            "/var/minis/offloads/tools/notes.txt\nAnd here is the rest of my document."
        val action = OffloadedPayloadGuard.decide(lookAlike, append = false) { "unrelated bytes" }
        assertTrue(action is OffloadedPayloadGuard.Action.Refuse)
        val refuse = action as OffloadedPayloadGuard.Action.Refuse
        assertEquals(OffloadedPayloadGuard.RefusalReason.UNRECOVERABLE, refuse.reason)
        assertEquals("/var/minis/offloads/tools/notes.txt", refuse.offloadPath)
    }

    @Test
    fun `stub with a drifted header is refused rather than healed`() {
        // Prefix-only detection must still refuse: it cannot know the promised
        // size, and guessing is how content gets silently replaced.
        var recoverCalls = 0
        val drifted = "[CONTEXT OFFLOADED] Content (1.1k tokens) stored in some_file.txt"
        val action = OffloadedPayloadGuard.decide(drifted, append = false) {
            recoverCalls++
            "whatever"
        }
        assertTrue(action is OffloadedPayloadGuard.Action.Refuse)
        assertEquals(
            OffloadedPayloadGuard.RefusalReason.UNRECOVERABLE,
            (action as OffloadedPayloadGuard.Action.Refuse).reason,
        )
        assertEquals(0, recoverCalls)
    }

    @Test
    fun `stub on an append is refused rather than duplicating bytes`() {
        var recoverCalls = 0
        val action = OffloadedPayloadGuard.decide(realStub, append = true) {
            recoverCalls++
            "recovered"
        }
        assertTrue(action is OffloadedPayloadGuard.Action.Refuse)
        assertEquals(
            OffloadedPayloadGuard.RefusalReason.APPEND_STUB,
            (action as OffloadedPayloadGuard.Action.Refuse).reason,
        )
        // Healing an append could silently duplicate content the original call
        // already appended — so the offload store must not even be consulted.
        assertEquals(0, recoverCalls)
    }

    @Test
    fun `stub whose bytes are gone is refused, not written best-effort`() {
        // Offload dirs are per-session: a stub minted in another session will
        // not resolve here. That must be a refusal, never a partial write.
        val action = OffloadedPayloadGuard.decide(realStub, append = false) { null }
        assertTrue(action is OffloadedPayloadGuard.Action.Refuse)
        val refuse = action as OffloadedPayloadGuard.Action.Refuse
        assertEquals(OffloadedPayloadGuard.RefusalReason.UNRECOVERABLE, refuse.reason)
        assertEquals(stubPath, refuse.offloadPath)
    }

    @Test
    fun `pathless stub is refused without attempting recovery`() {
        var recoverCalls = 0
        val action = OffloadedPayloadGuard.decide(
            "[CONTEXT OFFLOADED] Content (~1 tokens, 4 bytes) saved to: \nretrieve if needed.",
            append = false,
        ) {
            recoverCalls++
            "nope"
        }
        assertTrue(action is OffloadedPayloadGuard.Action.Refuse)
        assertEquals(
            OffloadedPayloadGuard.RefusalReason.UNRECOVERABLE,
            (action as OffloadedPayloadGuard.Action.Refuse).reason,
        )
        assertEquals(0, recoverCalls)
    }

    // ── refusalMessage ─────────────────────────────────────────────────────

    @Test
    fun `refusal message names the offload path so the model can still fetch it`() {
        val refuse = OffloadedPayloadGuard.Action.Refuse(
            OffloadedPayloadGuard.RefusalReason.UNRECOVERABLE,
            stubPath,
        )
        val msg = OffloadedPayloadGuard.refusalMessage("/tmp/x.kt", refuse)
        assertTrue(msg.contains(stubPath))
        assertTrue(msg.contains("/tmp/x.kt"))
        assertFalse(msg.contains("duplicate"))
    }

    @Test
    fun `append refusal explains the duplication risk`() {
        val refuse = OffloadedPayloadGuard.Action.Refuse(
            OffloadedPayloadGuard.RefusalReason.APPEND_STUB,
            null,
        )
        val msg = OffloadedPayloadGuard.refusalMessage("/tmp/x.kt", refuse)
        assertTrue(msg.contains("duplicate"))
        assertTrue(msg.contains("not recoverable"))
    }
}
