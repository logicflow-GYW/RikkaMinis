package com.rikkaminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R8-B B-F04 后半] A JSON `null` name must not become the literal name "null".
 *
 * `org.json`'s `optString(key)` returns **the string `"null"`** when the key is
 * present with a JSON null value (it only returns `""` for a missing key), and
 * `"null".isNotBlank()` is true — so `{"name":null,"display_name":"Hy4"}` used to
 * answer "null", the `display_name` fallback never fired, and the model picker
 * showed a model literally named `null`.
 *
 * Fixtures are the shapes real gateways emit (the local AI-Gateway writes
 * `display_name`; the OpenAI shape writes `name`).
 */
class OpenAIEndpointMetadataDisplayNameTest {

    @Test
    fun `an explicit null name falls through to display_name`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":null,"display_name":"X"}"""),
        )
        assertEquals("X", meta.displayName)
    }

    @Test
    fun `an empty name falls through to display_name`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":"","display_name":"X"}"""),
        )
        assertEquals("X", meta.displayName)
    }

    @Test
    fun `a blank name falls through to display_name`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":"   ","display_name":"X"}"""),
        )
        assertEquals("X", meta.displayName)
    }

    @Test
    fun `a real name still wins`() {
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m","name":"Y"}"""))
        assertEquals("Y", meta.displayName)
    }

    @Test
    fun `a real name still wins over display_name`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":"Y","display_name":"X"}"""),
        )
        assertEquals("Y", meta.displayName)
    }

    @Test
    fun `a null name with no fallback is null, not the word null`() {
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m","name":null}"""))
        assertNull(meta.displayName)
    }

    @Test
    fun `both names null stays null`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":null,"display_name":null}"""),
        )
        assertNull(meta.displayName)
    }

    @Test
    fun `a null display_name falls back to nothing rather than the word null`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","display_name":null}"""),
        )
        assertNull(meta.displayName)
    }

    // ── the discriminator (see the note in the parser) ──────────────────────
    //
    // MEASURED, and the reason these arms exist: the Maven `org.json` that JVM
    // unit tests link (`org.json:json:20231013`) does NOT reproduce the Android
    // quirk — `optString` on a JSON null returns "" there, so the pre-fix code
    // already falls through and every behaviour arm above passes on BOTH trees
    // (the pre-fix control arm stayed green, which is how this was found).
    // On Android the same call returns the literal "null" and the bug is real.
    // Behaviour therefore cannot pin this fix; the SHAPE can, and does.

    @Test
    fun `the parser reads both names through the null-safe reader`() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIModelEndpointMetadata.kt",
        )
        assertTrue(
            "name must be read through safeOptString (Android returns \"null\" otherwise)",
            src.contains("obj.safeOptString(\"name\")"),
        )
        assertTrue(
            "display_name must be read through safeOptString too",
            src.contains("obj.safeOptString(\"display_name\")"),
        )
        assertFalse(
            "a bare optString(\"name\") re-introduces the Android-only bug",
            src.contains("obj.optString(\"name\")"),
        )
        assertFalse(
            "a bare optString(\"display_name\") re-introduces the Android-only bug",
            src.contains("obj.optString(\"display_name\")"),
        )
    }

    @Test
    fun `the reader it relies on is the repo-wide one`() {
        // Not a second implementation: JsonExt.safeOptString documents exactly
        // this quirk, so a private copy would be the same defect twice.
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIModelEndpointMetadata.kt",
        )
        assertTrue(
            "must import the shared helper",
            src.contains("import com.rikkaminis.app.provider.safeOptString"),
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }
}
