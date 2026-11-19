package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [FULLSCAN-1119 A / F-19] Behavioural pin for the `.minisbak` converter's
 * `supportsReasoning` passthrough.
 *
 * Runs the REAL [OpenMinisBackupCompat.convert] on a hand-built package (it is
 * Android-free, so this needs no Robolectric — same harness as
 * [R5B1CompatPinsTest]). The three source shapes must stay three distinct
 * outcomes: absent ⇒ key absent, explicit null ⇒ key absent (undeclared), a
 * real boolean ⇒ that boolean. The defect folded the middle case into `false`,
 * so a model the source never declared came back "explicitly not reasoning".
 */
class CompatSupportsReasoningNullTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun jsonEntry(o: JSONObject) = o.toString().toByteArray(StandardCharsets.UTF_8)

    /**
     * One provider instance + one model entry, with [model] spread into the
     * model object. `instances[].id` is what `convertProviders` matches the
     * entry's `providerInstanceId` against.
     */
    private fun convertWithModel(model: JSONObject): JSONObject {
        val providerConfig = JSONObject()
            .put(
                "instances",
                JSONArray().put(
                    JSONObject()
                        .put("id", "inst-1")
                        .put("providerType", "openai")
                        .put("label", "OpenAI")
                        .put("isEnabled", true),
                ),
            )
            .put(
                "modelEntries",
                JSONArray().put(
                    JSONObject()
                        .put("uuid", "entry-1")
                        .put("providerInstanceId", "inst-1")
                        .put("isCustom", true)
                        .put("model", model),
                ),
            )
        val pkg = zipOf(
            "manifest.json" to jsonEntry(JSONObject().put("created_at", "2026-10-01T08:30:00Z")),
            "data/provider_config.json" to jsonEntry(providerConfig),
        )
        return JSONObject(OpenMinisBackupCompat.convert(pkg))
    }

    /** The converted model object of the one entry (`models[]`, not
     *  `modelEntries[]` — see convertProviders' `obj.put("models", …)`). */
    private fun firstModel(doc: JSONObject): JSONObject {
        val providers = doc.getJSONArray("providers")
        assertTrue("expected one provider, got ${providers.length()}", providers.length() >= 1)
        val models = providers.getJSONObject(0).getJSONArray("models")
        assertEquals(1, models.length())
        return models.getJSONObject(0)
    }

    @Test
    fun explicitJsonNullStaysUndeclared() {
        val model = firstModel(
            convertWithModel(
                JSONObject().put("id", "m-1").put("displayName", "M1")
                    .put("supportsReasoning", JSONObject.NULL),
            )
        )
        assertFalse(
            "an explicit null must NOT become an explicit false — that flips the " +
                "reasoning switch off on the restored install",
            model.has("supportsReasoning"),
        )
    }

    @Test
    fun absentKeyStaysAbsent() {
        val model = firstModel(
            convertWithModel(JSONObject().put("id", "m-1").put("displayName", "M1"))
        )
        assertFalse(model.has("supportsReasoning"))
    }

    @Test
    fun realBooleanStillRoundTrips() {
        assertTrue(
            firstModel(
                convertWithModel(
                    JSONObject().put("id", "m-1").put("displayName", "M1")
                        .put("supportsReasoning", true),
                )
            ).getBoolean("supportsReasoning"),
        )
        assertFalse(
            firstModel(
                convertWithModel(
                    JSONObject().put("id", "m-1").put("displayName", "M1")
                        .put("supportsReasoning", false),
                )
            ).getBoolean("supportsReasoning"),
        )
    }
}
