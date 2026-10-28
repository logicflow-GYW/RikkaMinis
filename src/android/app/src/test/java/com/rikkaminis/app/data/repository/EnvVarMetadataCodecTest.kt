package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-envvar-index-integrity] JVM tests for the `env-vars.json` decode decision.
 *
 * The defect these pin: the old `loadMetadata()` funnelled "file is corrupt"
 * and "user has no variables" into the same observable state (an empty list),
 * so a half-written index silently disabled sandbox env injection, the settings
 * list, prompt injection AND the privacy-mode redactor at once.
 *
 * The three outcomes must stay distinguishable:
 * missing (empty is true) / decoded (possibly partial) / corrupt (unknown).
 */
class EnvVarMetadataCodecTest {

    private fun decoded(text: String): EnvVarMetadataCodec.Result.Decoded {
        val result = EnvVarMetadataCodec.decode(text)
        assertTrue("expected Decoded, got $result", result is EnvVarMetadataCodec.Result.Decoded)
        return result as EnvVarMetadataCodec.Result.Decoded
    }

    @Test
    fun `missing file is Missing, not Corrupt and not an empty list`() {
        assertTrue(EnvVarMetadataCodec.decode(null) is EnvVarMetadataCodec.Result.Missing)
    }

    @Test
    fun `empty array decodes to no entries and no damage`() {
        val d = decoded("[]")
        assertEquals(0, d.entries.size)
        assertEquals(0, d.totalRows)
        assertEquals(0, d.damagedRows)
    }

    @Test
    fun `full row round-trips every field`() {
        val d = decoded(
            """[{"id":"id-1","key":"API_KEY","note":"cf token","group":"Cloudflare","createdAt":1700}]""",
        )
        assertEquals(1, d.entries.size)
        assertEquals(0, d.damagedRows)
        val e = d.entries[0]
        assertEquals("id-1", e.id)
        assertEquals("API_KEY", e.key)
        assertEquals("cf token", e.note)
        assertEquals("Cloudflare", e.group)
        assertEquals(1700L, e.createdAt)
    }

    @Test
    fun `absent optional fields fall back to the documented defaults`() {
        val d = decoded("""[{"key":"TOKEN_A"}]""")
        val e = d.entries.single()
        assertEquals("TOKEN_A", e.key)
        assertEquals("", e.note)
        assertEquals("", e.group)
        assertEquals(0L, e.createdAt)
        assertTrue("a missing id must be synthesised, not left blank", e.id.isNotEmpty())
    }

    @Test
    fun `truncated json is Corrupt, not an empty list`() {
        // The exact shape a truncating write leaves behind.
        assertTrue(EnvVarMetadataCodec.decode("""[{"id":"a","key":"API_KEY"}""").let {
            it is EnvVarMetadataCodec.Result.Corrupt
        })
    }

    @Test
    fun `empty file is Corrupt`() {
        // 0 bytes is what `writeText` leaves when the process dies before the
        // first byte lands — it is not "the user deleted everything".
        assertTrue(EnvVarMetadataCodec.decode("") is EnvVarMetadataCodec.Result.Corrupt)
    }

    @Test
    fun `json object instead of array is Corrupt`() {
        assertTrue(EnvVarMetadataCodec.decode("""{"key":"API_KEY"}""") is EnvVarMetadataCodec.Result.Corrupt)
    }

    @Test
    fun `non-object row is counted as damage and the good rows survive`() {
        val d = decoded("""[{"key":"GOOD_ONE"},"oops",{"key":"GOOD_TWO"}]""")
        assertEquals(listOf("GOOD_ONE", "GOOD_TWO"), d.entries.map { it.key })
        assertEquals(3, d.totalRows)
        assertEquals(1, d.damagedRows)
        assertTrue("reason should name the row", d.reason.contains("row 1"))
    }

    @Test
    fun `row without a key is damage rather than an unusable entry`() {
        // `getValue("")` can never return a value, and the redactor cannot match
        // against an empty key, so keeping such a row would only inflate counts.
        val d = decoded("""[{"id":"x"},{"key":"","id":"y"},{"key":"REAL_KEY"}]""")
        assertEquals(listOf("REAL_KEY"), d.entries.map { it.key })
        assertEquals(2, d.damagedRows)
    }

    @Test
    fun `unknown extra fields are tolerated`() {
        // Forward compatibility: a newer writer adding a field must not make the
        // whole index look corrupt on an older reader.
        val d = decoded("""[{"key":"A_KEY","futureField":{"n":1}}]""")
        assertEquals(1, d.entries.size)
        assertEquals(0, d.damagedRows)
    }
}
