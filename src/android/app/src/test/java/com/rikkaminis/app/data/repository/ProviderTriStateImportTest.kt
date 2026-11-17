package com.rikkaminis.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-D5 F-02] An explicit JSON `null` on a tri-state capability flag must
 * stay `null` ("unknown, inherit the catalog / heuristic"), not become `false`
 * ("definitely does not support reasoning").
 *
 * The defect lived in [ProviderRepository.parseImportedModelEntries]:
 * `if (m.has("supportsReasoning")) m.optBoolean(...) else null`. `has()` is
 * TRUE for a key whose value is `JSONObject.NULL`, and `optBoolean` then
 * coerces NULL to `false` — so an imported document (iOS export, hand-edited
 * backup) that spelled out `"supportsReasoning": null` permanently flipped the
 * flag to "not supported", which hides the Deep Thinking toggle in chat and
 * breaks `mergeImportInstanceJSON`'s `restored == local` idempotence check.
 *
 * Two halves are pinned here:
 *  1. the reader's contract ([optionalBooleanOrNull], pure);
 *  2. that the import path actually calls it — the production file must not
 *     contain the pre-fix `has(...)` test for either tri-state key. Without
 *     this half, reverting the call site would leave the suite green.
 */
class ProviderTriStateImportTest {

    // ── the reader ─────────────────────────────────────────────────────────

    @Test
    fun `absent key is unknown`() {
        assertNull(optionalBooleanOrNull(JSONObject("""{}"""), "supportsReasoning"))
    }

    @Test
    fun `explicit json null stays unknown`() {
        val obj = JSONObject("""{"supportsReasoning": null}""")
        // Pre-fix path: has()==true → optBoolean()==false.
        assertTrue(obj.has("supportsReasoning"))
        assertNull(optionalBooleanOrNull(obj, "supportsReasoning"))
    }

    @Test
    fun `true and false survive as themselves`() {
        assertEquals(true, optionalBooleanOrNull(JSONObject("""{"supportsReasoning": true}"""), "supportsReasoning"))
        assertEquals(false, optionalBooleanOrNull(JSONObject("""{"supportsReasoning": false}"""), "supportsReasoning"))
    }

    @Test
    fun `string null literal is not json null`() {
        // "null" as a STRING is a value the document author wrote, so it keeps
        // optBoolean's lenient coercion (false) instead of being read as unknown.
        assertEquals(false, optionalBooleanOrNull(JSONObject("""{"supportsReasoning": "null"}"""), "supportsReasoning"))
    }

    @Test
    fun `a present non boolean scalar keeps optBoolean's own answer`() {
        // The fix must change ONLY the explicit-null case. `optBoolean` (JVM
        // org.json and Android agree) answers false for a non-boolean scalar,
        // and a string "true" still reads as true.
        assertEquals(false, optionalBooleanOrNull(JSONObject("""{"supportsReasoning": 1}"""), "supportsReasoning"))
        assertEquals(true, optionalBooleanOrNull(JSONObject("""{"supportsReasoning": "true"}"""), "supportsReasoning"))
    }

    // ── the wiring ─────────────────────────────────────────────────────────

    @Test
    fun `import parser routes both tri-state flags through the shared reader`() {
        val src = readRepoFile(REPOSITORY)
        // base model
        assertTrue(
            "parseImportedModelEntries no longer reads supportsReasoning via optionalBooleanOrNull",
            src.contains("""optionalBooleanOrNull(m, "supportsReasoning")"""),
        )
        // overrides layer
        assertTrue(
            "overrides layer no longer reads supportsReasoning via optionalBooleanOrNull",
            src.contains("""optionalBooleanOrNull(overridesObj, "supportsReasoning")"""),
        )
        // The second tri-state flag of the same function keeps its inline test:
        // `provider_import_id_roundtrip_check` arm 3 anchors on that read being
        // present in the parse body, and the shape was already correct. Pinned
        // here so a future "unify the readers" pass does not silently drop the
        // null guard.
        assertTrue(
            "declaresNoEffortTiers lost its !isNull guard (an explicit null would import as false)",
            src.contains("""has("declaresNoEffortTiers") && !m.isNull("declaresNoEffortTiers")"""),
        )
        // and the defect shape itself is gone
        assertFalse(
            "has(\"supportsReasoning\") (true for explicit null) is back in the import path",
            src.contains("""has("supportsReasoning")"""),
        )
    }

    private fun readRepoFile(relative: String): String {
        // Same root discovery as MirrorPublicBaseWiringTest: Gradle sets
        // user.dir to the module directory, the JVM harness to the repo root;
        // walking up is the only form that works in both.
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val REPOSITORY = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt"
    }
}
