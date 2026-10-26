package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.provider.ImageBudget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * [debt-1026 B1] The session-RELOAD image path.
 *
 * Two defects lived here and neither is reachable from a Compose test:
 *
 *  1. `buildSingleLlmMessage` read the persisted original with a bare
 *     `file.readBytes()` and inlined those bytes into the model payload. The
 *     composer downscales what it sends (max edge 2000), so the FIRST reload
 *     re-sent every historical photo at full resolution — and providers inline
 *     `msg.imageParts` on every turn afterwards.
 *  2. `applyRequestImageBudget` filtered `contentParts` only, while all three
 *     provider serializers also emit `+ msg.imageParts`. An "elided" image
 *     therefore still went out at full size.
 *
 * The bounded read and the elision filter are pure JVM code, so they are pinned
 * here against the real production functions. The actual bitmap resize is
 * `android.graphics` and is exercised by CI only, which is why the resize is an
 * INJECTED lambda: the arm below proves the injection point, not the encoder.
 */
class ChatImageRestoreTest {

    private val dirs = mutableListOf<File>()

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("debt1026-${UUID.randomUUID().toString().take(8)}")
            .toFile()
            .also { dirs.add(it) }

    @After
    fun tearDown() {
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun entity() = MessageEntity(
        id = "m1", sessionId = "s1", role = "user", partsJson = "[]",
        createdAt = 1L, sortOrder = 1,
    )

    private fun mediaRef(rel: String, mime: String = "image/jpeg") = ParsedPart.MediaRef(
        relativePath = rel, mimeType = mime, originalFileName = rel, linuxPath = null,
    )

    // ── bounded read ───────────────────────────────────────────────

    @Test
    fun `restored image read accepts exactly the cap and refuses one byte more`() {
        val dir = tempDir()
        val exactly = File(dir, "exact.bin").also { it.writeBytes(ByteArray(8)) }
        val tooBig = File(dir, "big.bin").also { it.writeBytes(ByteArray(9)) }

        assertNotNull(readRestoredImageBytes(exactly, cap = 8L))
        assertEquals(8, readRestoredImageBytes(exactly, cap = 8L)!!.size)
        assertNull(readRestoredImageBytes(tooBig, cap = 8L))
    }

    @Test
    fun `production cap is a named ceiling, not an accident`() {
        // 64 MiB: above any real photo (a 200 MP JPEG is ~30 MB), far below the
        // "read the whole asset into the heap" OOM this used to be.
        assertEquals(64L * 1024 * 1024, MAX_RESTORED_IMAGE_BYTES)
    }

    // ── inference bytes come from the injected resizer ─────────────

    @Test
    fun `media ref inlines the resized bytes and shares one instance across both carriers`() {
        val dir = tempDir()
        val raw = ByteArray(16) { it.toByte() }
        File(dir, "img.jpg").writeBytes(raw)
        var seenRaw: ByteArray? = null
        var seenMime: String? = null
        val resized = byteArrayOf(9, 9)

        val out = buildSingleLlmMessage(
            entity(), "[]", listOf(mediaRef("img.jpg")), malformed = false, mediaBaseDir = dir,
            resizeForInference = { bytes, mime ->
                seenRaw = bytes
                seenMime = mime
                resized
            },
        )

        assertEquals(16, seenRaw!!.size)
        assertEquals("image/jpeg", seenMime)
        assertEquals(1, out.imageParts.size)
        assertSame(resized, out.imageParts[0].data)
        // The budget filter keys on ByteArray IDENTITY, so both carriers must
        // hold the very same array (a copy would make elision miss imageParts).
        val imageData = out.contentParts.filterIsInstance<AgentContentPart.ImageData>().single()
        assertSame(resized, imageData.data)
    }

    @Test
    fun `a resizer that declines leaves the original bytes in place`() {
        val dir = tempDir()
        val raw = ByteArray(5) { 7 }
        File(dir, "img.jpg").writeBytes(raw)

        val out = buildSingleLlmMessage(
            entity(), "[]", listOf(mediaRef("img.jpg")), malformed = false, mediaBaseDir = dir,
            resizeForInference = { _, _ -> null },
        )

        assertEquals(5, out.imageParts[0].data.size)
    }

    @Test
    fun `a media ref whose bytes cannot be read is skipped, not inlined whole`() {
        // The oversized case is the same `?: continue` (readRestoredImageBytes
        // answers null); exercised here with a cheap unreadable path instead of
        // materialising a 64 MiB fixture — the bound itself is pinned above.
        val dir = tempDir()
        File(dir, "damaged.jpg").mkdirs()

        val out = buildSingleLlmMessage(
            entity(), "[]", listOf(mediaRef("damaged.jpg")), malformed = false, mediaBaseDir = dir,
            resizeForInference = { _, _ -> null },
        )

        assertTrue(out.imageParts.isEmpty())
        assertTrue(out.contentParts.filterIsInstance<AgentContentPart.ImageData>().isEmpty())
    }

    // ── elision must reach imageParts ──────────────────────────────

    @Test
    fun `elided bytes leave imageParts by identity, untouched parts survive`() {
        val dropped = ByteArray(3) { 1 }
        val kept = ByteArray(3) { 2 }
        val parts = listOf(
            LLMMessage.ImagePart(dropped, "image/jpeg", linuxPath = "/var/minis/a.jpg"),
            LLMMessage.ImagePart(kept, "image/png"),
        )

        val out = filterElidedImageParts(parts, setOf(ImageBudget.ImagePartId.of(dropped)))

        assertEquals(1, out.size)
        assertSame(kept, out[0].data)
        assertEquals("image/png", out[0].mimeType)
    }

    @Test
    fun `content-equal but distinct arrays are not elided`() {
        // `planRequestBudget` identifies parts by System.identityHashCode, so a
        // same-content copy is a DIFFERENT part. Pinning this keeps the filter
        // aligned with the planner instead of drifting to contentEquals.
        val elided = ByteArray(4) { 5 }
        val twin = ByteArray(4) { 5 }
        val parts = listOf(LLMMessage.ImagePart(twin, "image/jpeg"))

        val out = filterElidedImageParts(parts, setOf(ImageBudget.ImagePartId.of(elided)))

        assertEquals(1, out.size)
        assertSame(twin, out[0].data)
    }

    @Test
    fun `no drops returns the same list instance`() {
        val parts = listOf(LLMMessage.ImagePart(ByteArray(2), "image/jpeg"))
        assertSame(parts, filterElidedImageParts(parts, emptySet()))
        val empty = emptyList<LLMMessage.ImagePart>()
        assertSame(empty, filterElidedImageParts(empty, setOf(ImageBudget.ImagePartId.of(ByteArray(1)))))
    }
}
