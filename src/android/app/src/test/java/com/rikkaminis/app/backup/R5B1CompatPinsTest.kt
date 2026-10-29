package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [FIX-R5-B1] Pins for the three `.minisbak` compatibility findings.
 *
 * Self-contained on purpose: the whole class compiles and runs on a plain JVM
 * against the production [OpenMinisBackupCompat] (that file imports nothing
 * from Android), so the sandbox can run it before CI. The big
 * [OpenMinisBackupCompatTest] cannot be compiled that way — it also calls into
 * `ConfigBackup`, which is Android-bound.
 *
 * Every expected value is a literal (a millisecond epoch, a boolean, a decoded
 * binding); nothing is derived from the code under test.
 */
class R5B1CompatPinsTest {

    /** 2026-10-01T08:30:00Z — the stamp `plainManifest()` carries. */
    private val manifestStamp = 1790843400000L

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = java.util.zip.CRC32().apply { update(data) }.value
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun jsonEntry(o: JSONObject) = o.toString().toByteArray(StandardCharsets.UTF_8)
    private fun textEntry(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun manifest() = JSONObject().put("created_at", "2026-10-01T08:30:00Z")

    // ── [F-01] the document timestamp the importer orders memory against ──

    @Test
    fun convertedDocumentCarriesTheManifestCreatedAt() {
        // Before this fix the converted document had NO `createdAt`, so
        // `ConfigBackup.import` read 0 and Stage 6's memory guard refused every
        // memory file the device already had.
        val doc = JSONObject(
            OpenMinisBackupCompat.convert(zipOf("manifest.json" to jsonEntry(manifest()))),
        )
        assertEquals(manifestStamp, doc.getLong("createdAt"))
    }

    @Test
    fun convertedDocumentWithoutAManifestStampUsesConversionTime() {
        val before = System.currentTimeMillis()
        val doc = JSONObject(
            OpenMinisBackupCompat.convert(
                zipOf(
                    "manifest.json" to jsonEntry(
                        JSONObject().put("app", "OpenMinis").put("device_name", "iPhone"),
                    ),
                )
            ),
        )
        val after = System.currentTimeMillis()
        val createdAt = doc.getLong("createdAt")
        assertTrue("createdAt=$createdAt not in [$before,$after]", createdAt in before..after)
    }

    @Test
    fun manifestStampIsReadFromIsoAndEpochMilliShapes() {
        assertEquals(
            manifestStamp,
            manifestCreatedAtMillis(JSONObject().put("created_at", "2026-10-01T08:30:00Z"), 7L),
        )
        assertEquals(
            manifestStamp,
            manifestCreatedAtMillis(JSONObject().put("created_at", manifestStamp), 7L),
        )
        // Unusable ⇒ the caller's fallback, never a zero (Stage 6 fails closed
        // on 0, so handing it 0 by accident silently disables the restore).
        assertEquals(7L, manifestCreatedAtMillis(JSONObject(), 7L))
        assertEquals(7L, manifestCreatedAtMillis(JSONObject().put("created_at", 0L), 7L))
        assertEquals(7L, manifestCreatedAtMillis(JSONObject().put("created_at", ""), 7L))
    }

    // ── [C-08 ①] includesSecrets measured on values, not on key count ──

    @Test
    fun emptySecretsShellDoesNotClaimCredentials() {
        // `{"providers":[],"envVars":[]}` has TWO keys, so the old
        // `length() > 0` criterion claimed credentials on a package with none
        // and suppressed the "no API keys in this backup" line.
        val doc = JSONObject(
            OpenMinisBackupCompat.convert(
                zipOf(
                    "manifest.json" to jsonEntry(manifest()),
                    "secrets.json" to jsonEntry(
                        JSONObject().put("providers", JSONArray()).put("envVars", JSONArray()),
                    ),
                )
            ),
        )
        assertFalse(doc.getBoolean("includesSecrets"))
    }

    @Test
    fun secretsCarryCredentialsLooksAtValues() {
        assertFalse(secretsCarryCredentials(null))
        assertFalse(secretsCarryCredentials(JSONObject()))
        assertFalse(
            secretsCarryCredentials(
                JSONObject().put("providers", JSONArray()).put("envVars", JSONArray()),
            )
        )
        assertFalse(
            secretsCarryCredentials(
                JSONObject().put(
                    "providers",
                    JSONArray().put(JSONObject().put("instanceId", "i").put("apiKey", "")),
                ),
            )
        )
        assertTrue(
            secretsCarryCredentials(
                JSONObject().put(
                    "providers",
                    JSONArray().put(
                        JSONObject().put("instanceId", "i").put("oauthToken", "tok"),
                    ),
                ),
            )
        )
        assertTrue(
            secretsCarryCredentials(
                JSONObject().put(
                    "envVars",
                    JSONArray().put(JSONObject().put("name", "K").put("value", "c2VjcmV0")),
                ),
            )
        )
    }

    // ── [C-08 ②] a blank wrapper binding must not shadow the inner one ──

    @Test
    fun blankWrapperModelBindingFallsBackToTheInnerSessionBinding() {
        val session = JSONObject()
            .put("t", "SessionV2")
            .put("v", 1)
            .put(
                "d",
                JSONObject()
                    .put(
                        "session",
                        JSONObject()
                            .put("id", "sess-1")
                            .put("title", "周报")
                            .put("modelId", "claude-fable-5")
                            .put("modelBinding", "group:g7"),
                    )
                    .put("modelBinding", "   "),
            )
        val doc = JSONObject(
            OpenMinisBackupCompat.convert(
                zipOf(
                    "manifest.json" to jsonEntry(manifest()),
                    "data/sessions.jsonl" to textEntry(session.toString() + "\n"),
                )
            ),
        )
        val binding = JSONObject(
            doc.getJSONArray("chatSessions").getJSONObject(0).getString("modelBinding"),
        )
        assertEquals("group", binding.getString("type"))
        assertEquals("g7", binding.getString("groupId"))
    }
}
