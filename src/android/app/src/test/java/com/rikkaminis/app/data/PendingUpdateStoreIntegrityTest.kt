package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/update-digest-verify] Two things that would otherwise only fail on a
 * device, days later, silently:
 *
 * 1. **Serialization round trip.** `publisherDigest` is the newest field of
 *    [PendingUpdateStore.PendingUpdate]; the "added a field, missed a layer"
 *    bug class (GH#68 image-endpoint, ProviderInstance.pinned) drops it on
 *    save or on load with nothing failing. The four layers are the data
 *    class, [PendingUpdateStore.encodePending], [PendingUpdateStore.decodePending]
 *    and [PendingUpdateStore.verify] — this test walks the middle two and
 *    feeds the fourth.
 *
 * 2. **Resume-time verification.** [PendingUpdateStore.verify] is the last
 *    gate before the installer intent; a cross-session file swap must fail it
 *    against BOTH the hash we recorded and the hash the publisher declared.
 *
 * Runs the real production functions with real files. Only SharedPreferences
 * (and therefore [PendingUpdateStore.init]) is out of reach — verify() and the
 * encode/decode pair never touch it.
 */
class PendingUpdateStoreIntegrityTest {

    private val shaA = "2cf4b9ade1716ea43ae118c1aae40f75e3949e51ee201d3199464606e5d08839"
    private val shaB = "0000000000000000000000000000000000000000000000000000000000000000"

    private fun writeTemp(content: String): File {
        val f = File.createTempFile("pending-update-test", ".apk")
        f.deleteOnExit()
        f.writeBytes(content.toByteArray())
        return f
    }

    private fun record(
        file: File,
        sha256: String?,
        publisherDigest: String?,
        size: Long = file.length(),
    ) = PendingUpdateStore.PendingUpdate(
        targetVersionName = "1.0.1",
        apkPath = file.absolutePath,
        apkSize = size,
        sha256 = sha256,
        downloadedAtMs = 1_700_000_000_000L,
        publisherDigest = publisherDigest,
    )

    // ── serialization round trip (four-way sync) ─────────────────────────

    @Test
    fun `every field survives encode then decode`() {
        val original = PendingUpdateStore.PendingUpdate(
            targetVersionName = "1.0.1",
            apkPath = "/data/user/0/com.rikkaminis.app/files/updates/minis-1.0.1.apk",
            apkSize = 14077427L,
            sha256 = shaA,
            downloadedAtMs = 1_700_000_000_000L,
            publisherDigest = shaB,
        )
        assertEquals(original, PendingUpdateStore.decodePending(PendingUpdateStore.encodePending(original)))
    }

    @Test
    fun `null hashes survive the round trip as null, not as the string null`() {
        val original = PendingUpdateStore.PendingUpdate(
            targetVersionName = "1.0.1",
            apkPath = "/tmp/x.apk",
            apkSize = 1L,
            sha256 = null,
            downloadedAtMs = 42L,
            publisherDigest = null,
        )
        val decoded = PendingUpdateStore.decodePending(PendingUpdateStore.encodePending(original))!!
        assertEquals(original, decoded)
        assertNull(decoded.sha256)
        assertNull(decoded.publisherDigest)
    }

    @Test
    fun `a record written before publisherDigest existed still decodes`() {
        // Exactly the JSON shape the previous release wrote (no
        // publisherDigest key at all). Loading it must not throw and must
        // land on the size/self-hash semantics it was written for.
        val legacy = """
            {"targetVersionName":"1.0.1","apkPath":"/tmp/legacy.apk","apkSize":10,
             "sha256":"$shaA","downloadedAtMs":1700000000000}
        """.trimIndent().replace("\n", "")
        val decoded = PendingUpdateStore.decodePending(legacy)!!
        assertEquals("1.0.1", decoded.targetVersionName)
        assertEquals(shaA, decoded.sha256)
        assertNull(decoded.publisherDigest)

        // ...and the explicit JSON null an older build may have written.
        val explicitNull = legacy.replaceFirst("\"downloadedAtMs\"", "\"publisherDigest\":null,\"downloadedAtMs\"")
        assertNull(PendingUpdateStore.decodePending(explicitNull)!!.publisherDigest)
    }

    @Test
    fun `malformed json decodes to null so the caller clears it`() {
        assertNull(PendingUpdateStore.decodePending(""))
        assertNull(PendingUpdateStore.decodePending("{"))
        assertNull(PendingUpdateStore.decodePending("not json at all"))
    }

    // ── verify(): the last gate before the installer ─────────────────────

    @Test
    fun `matching size hash and publisher digest verifies`() {
        val f = writeTemp("apk-bytes")
        val sha = PendingUpdateStore.sha256(f)
        assertNotNull(PendingUpdateStore.verify(record(f, sha, sha)))
    }

    @Test
    fun `publisher digest mismatch fails even when our own hash matched`() {
        // The scenario the field exists for: the file was swapped and the
        // pending record rewritten to match it. Our own hash agrees; the
        // publisher's does not.
        val f = writeTemp("apk-bytes")
        val sha = PendingUpdateStore.sha256(f)
        assertNull(PendingUpdateStore.verify(record(f, sha256 = sha, publisherDigest = shaB)))
    }

    @Test
    fun `matching publisher digest fails when our recorded hash disagrees`() {
        val f = writeTemp("apk-bytes")
        val sha = PendingUpdateStore.sha256(f)
        assertNull(PendingUpdateStore.verify(record(f, sha256 = shaB, publisherDigest = sha)))
    }

    @Test
    fun `a record with neither hash still checks the size`() {
        val f = writeTemp("apk-bytes")
        assertNotNull(PendingUpdateStore.verify(record(f, null, null)))
        assertNull(PendingUpdateStore.verify(record(f, null, null, size = f.length() + 1)))
    }

    @Test
    fun `missing file fails`() {
        val f = writeTemp("apk-bytes")
        val rec = record(f, null, null)
        assertTrue(f.delete())
        assertNull(PendingUpdateStore.verify(rec))
    }

    @Test
    fun `a tampered file fails the publisher digest on resume`() {
        val f = writeTemp("apk-bytes")
        val sha = PendingUpdateStore.sha256(f)
        val rec = record(f, sha256 = sha, publisherDigest = sha)
        // Same length, different content: only the digest can catch this.
        f.writeBytes("apk-BYTEZ".toByteArray())
        assertEquals(rec.apkSize, f.length())
        assertNull(PendingUpdateStore.verify(rec))
    }
}
