package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/update-digest-verify] Pins the accept/refuse rules of the update
 * download check. Before this landed the only hash comparison in the update
 * flow was self-referential ([PendingUpdateStore.verify] re-hashing the file
 * it wrote), so a download that did not match the published asset installed
 * without complaint.
 *
 * Three groups: parsing (what counts as a digest we can check), judging
 * (which verdicts may install), and resume (when a pending record may answer
 * a download request without the network).
 */
class UpdateDigestTest {

    private val hex64 =
        "2cf4b9ade1716ea43ae118c1aae40f75e3949e51ee201d3199464606e5d08839"
    private val other64 =
        "0000000000000000000000000000000000000000000000000000000000000000"

    // ── parsePublisherDigest ─────────────────────────────────────────────

    @Test
    fun `absent or blank digest parses to null`() {
        assertNull(parsePublisherDigest(null))
        assertNull(parsePublisherDigest(""))
        assertNull(parsePublisherDigest("   "))
    }

    @Test
    fun `github shape parses to lowercase sha256`() {
        val d = parsePublisherDigest("sha256:$hex64")!!
        assertEquals("sha256", d.algorithm)
        assertEquals(hex64, d.hex)
        assertTrue(d.isSupported)
    }

    @Test
    fun `uppercase hex is normalized and still verifiable`() {
        val d = parsePublisherDigest("sha256:${hex64.uppercase()}")!!
        assertEquals(hex64, d.hex)
        assertTrue(d.isSupported)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val d = parsePublisherDigest("  sha256:$hex64  ")!!
        assertEquals("sha256", d.algorithm)
        assertEquals(hex64, d.hex)
    }

    @Test
    fun `bare 64 char hex is assumed sha256`() {
        // A bare hex carries no algorithm. Assuming sha256 is safe in the
        // failing direction: a wrong assumption makes the comparison fail, so
        // the download is refused rather than accepted.
        val d = parsePublisherDigest(hex64)!!
        assertEquals("sha256", d.algorithm)
        assertTrue(d.isSupported)
    }

    @Test
    fun `bare hex of the wrong length is unverifiable`() {
        assertNull(parsePublisherDigest(hex64.dropLast(1)))
        assertNull(parsePublisherDigest(hex64 + "a"))
    }

    @Test
    fun `non-hex payload is unverifiable`() {
        assertNull(parsePublisherDigest("sha256:nothex"))
        assertNull(parsePublisherDigest("sha256:"))
        assertNull(parsePublisherDigest("sha256:12 34"))
    }

    @Test
    fun `unsupported algorithm is parsed but not verifiable`() {
        val d = parsePublisherDigest("sha512:$hex64$hex64")!!
        assertEquals("sha512", d.algorithm)
        assertFalse(d.isSupported)
    }

    // ── judgeDownloadIntegrity ───────────────────────────────────────────

    @Test
    fun `matching digest verifies`() {
        val v = judgeDownloadIntegrity(
            declared = PublisherDigest("sha256", hex64),
            expectedSize = 100L,
            actualSize = 100L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.VERIFIED, v)
        assertTrue(v.isVerified)
        assertFalse(v.isFailure)
    }

    @Test
    fun `digest comparison ignores hex case`() {
        val v = judgeDownloadIntegrity(
            declared = PublisherDigest("sha256", hex64.uppercase()),
            expectedSize = 100L,
            actualSize = 100L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.VERIFIED, v)
    }

    @Test
    fun `one byte changed fails the digest check`() {
        val v = judgeDownloadIntegrity(
            declared = PublisherDigest("sha256", hex64),
            expectedSize = 100L,
            actualSize = 100L,
            actualSha256 = other64,
        )
        assertEquals(DownloadIntegrity.DIGEST_MISMATCH, v)
        assertTrue(v.isFailure)
        assertFalse(v.isVerified)
    }

    @Test
    fun `a matching digest outranks a stale size`() {
        // The asset was re-uploaded between check and download: the digest
        // vouches for the bytes, so the stale `size` must not veto them.
        val v = judgeDownloadIntegrity(
            declared = PublisherDigest("sha256", hex64),
            expectedSize = 999L,
            actualSize = 100L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.VERIFIED, v)
    }

    @Test
    fun `a declared digest we cannot compute still fails an obviously wrong download`() {
        val mismatch = judgeDownloadIntegrity(
            declared = PublisherDigest("sha512", hex64),
            expectedSize = 100L,
            actualSize = 101L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.SIZE_MISMATCH, mismatch)
        assertTrue(mismatch.isFailure)

        val sizeOk = judgeDownloadIntegrity(
            declared = PublisherDigest("sha512", hex64),
            expectedSize = 100L,
            actualSize = 100L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.UNSUPPORTED_ALGORITHM, sizeOk)
        assertFalse(sizeOk.isFailure)
        assertFalse(sizeOk.isVerified)
    }

    @Test
    fun `declared digest with no local hash is refused`() {
        // Hashing the file we just wrote failed — that is not a state to
        // install from, even though "size only" would have accepted it.
        val v = judgeDownloadIntegrity(
            declared = PublisherDigest("sha256", hex64),
            expectedSize = 100L,
            actualSize = 100L,
            actualSha256 = null,
        )
        assertEquals(DownloadIntegrity.HASH_FAILED, v)
        assertTrue(v.isFailure)
    }

    @Test
    fun `no digest falls back to the size check`() {
        val ok = judgeDownloadIntegrity(
            declared = null,
            expectedSize = 100L,
            actualSize = 100L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.SIZE_ONLY, ok)
        assertFalse(ok.isFailure)
        assertFalse(ok.isVerified)

        val bad = judgeDownloadIntegrity(
            declared = null,
            expectedSize = 100L,
            actualSize = 99L,
            actualSha256 = hex64,
        )
        assertEquals(DownloadIntegrity.SIZE_MISMATCH, bad)
        assertTrue(bad.isFailure)
    }

    @Test
    fun `unknown expected size cannot fail on size`() {
        // DebugRPC downloads by URL alone: size 0 means "not declared".
        val v = judgeDownloadIntegrity(
            declared = null,
            expectedSize = 0L,
            actualSize = 12345L,
            actualSha256 = null,
        )
        assertEquals(DownloadIntegrity.SIZE_ONLY, v)
        assertFalse(v.isFailure)
    }

    @Test
    fun `only three verdicts may ever install`() {
        // Guard rail against someone adding a verdict and forgetting to
        // classify it: every verdict must be either installable or a failure,
        // and exactly the three known installable ones are not verified.
        val installable = DownloadIntegrity.entries.filter { !it.isFailure }
        assertEquals(
            listOf(
                DownloadIntegrity.VERIFIED,
                DownloadIntegrity.SIZE_ONLY,
                DownloadIntegrity.UNSUPPORTED_ALGORITHM,
            ),
            installable,
        )
        assertEquals(
            listOf(DownloadIntegrity.VERIFIED),
            DownloadIntegrity.entries.filter { it.isVerified },
        )
    }

    @Test
    fun `a real published asset digest parses and verifies`() {
        // Cloned live from logicflow-GYW/RikkaMinis release android-latest on
        // 2026-09-14 — pins that GitHub's actual field shape stays parseable.
        val live = "sha256:2cf4b9ade1716ea43ae118c1aae40f75e3949e51ee201d3199464606e5d08839"
        val d = parsePublisherDigest(live)!!
        assertEquals(
            DownloadIntegrity.VERIFIED,
            judgeDownloadIntegrity(d, 14077427L, 14077427L, d.hex),
        )
        assertEquals(
            DownloadIntegrity.DIGEST_MISMATCH,
            judgeDownloadIntegrity(d, 14077427L, 14077427L, other64),
        )
    }

    // ── judgeResumePending ──────────────────────────────────────────────

    @Test
    fun `declared digest matching the recorded publisher digest resumes verified`() {
        val declared = parsePublisherDigest("sha256:$hex64")!!
        assertEquals(
            DownloadIntegrity.VERIFIED,
            judgeResumePending(hex64, other64, 14_000_000L, declared, 14_000_000L),
        )
    }

    @Test
    fun `declared digest matching our own re-hash resumes verified`() {
        // Record written by a download where the publisher declared no digest
        // but our sha256 was computed — the bytes still hash to what the
        // caller now declares, so the pending file IS the requested asset.
        val declared = parsePublisherDigest("sha256:$hex64")!!
        assertEquals(
            DownloadIntegrity.VERIFIED,
            judgeResumePending(null, hex64, 14_000_000L, declared, 14_000_000L),
        )
    }

    @Test
    fun `digest correlation is case-insensitive`() {
        // parsePublisherDigest lowercases its own side, so the recorded hex
        // is the one carrying mixed case here — ignoreCase is what matches it.
        val declared = parsePublisherDigest("sha256:$hex64")!!
        assertEquals(
            DownloadIntegrity.VERIFIED,
            judgeResumePending(hex64.uppercase(), null, 1L, declared, 0L),
        )
        assertEquals(
            DownloadIntegrity.VERIFIED,
            judgeResumePending(null, hex64.uppercase(), 1L, declared, 0L),
        )
    }

    @Test
    fun `declared digest that matches nothing refuses to resume`() {
        val declared = parsePublisherDigest("sha256:$hex64")!!
        assertNull(judgeResumePending(other64, null, 14_000_000L, declared, 14_000_000L))
        // Declared sha256 but the record carries no hashes at all: the bytes
        // were never checked against it — download afresh instead.
        assertNull(judgeResumePending(null, null, 14_000_000L, declared, 14_000_000L))
        // A different-size record with a matching digest would still resume
        // (digest decides alone, as in judgeDownloadIntegrity).
        assertEquals(
            DownloadIntegrity.VERIFIED,
            judgeResumePending(hex64, null, 1L, declared, 14_000_000L),
        )
    }

    @Test
    fun `unsupported declared algorithm degrades to the size rule`() {
        val sha512 = parsePublisherDigest("sha512:$hex64")!!
        assertTrue(!sha512.isSupported)
        assertEquals(
            DownloadIntegrity.SIZE_ONLY,
            judgeResumePending(hex64, null, 14_000_000L, sha512, 14_000_000L),
        )
        assertNull(judgeResumePending(hex64, null, 14_000_000L, sha512, 99L))
    }

    @Test
    fun `without a declared digest only a size match resumes`() {
        assertEquals(
            DownloadIntegrity.SIZE_ONLY,
            judgeResumePending(null, null, 14_000_000L, null, 14_000_000L),
        )
        assertNull(judgeResumePending(null, null, 14_000_000L, null, 99L))
        // Size unknown on the request side — nothing to correlate with.
        assertNull(judgeResumePending(hex64, hex64, 14_000_000L, null, 0L))
    }
}
