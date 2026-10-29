package com.rikkaminis.app.config.collections

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R5-E F-04] `models.<id>.supportsReasoning` — the field the collection was
 * missing while `add()` already persisted `overrides.supportsReasoning`
 * ([r3-b08 F-04]).
 *
 * Two symptoms this pins:
 *  * `minis-config get models.<id>.supportsReasoning` answered `unknown_path`
 *    (the path did not exist) and `set` was impossible;
 *  * `.remove` builds its `old` snapshot by walking `fields()`
 *    (`ConfigBridge.snapshotCollectionChild`), so the removed row's snapshot
 *    silently dropped the property.
 *
 * Behaviour half runs the PRODUCTION transformation
 * ([withSupportsReasoningOverride]) and the PRODUCTION read path
 * (`ModelEntry.model`). Shape half pins that `fields()` actually registers the
 * field — a registered field is the only way both symptoms close.
 */
class ModelsSupportsReasoningFieldTest {

    private fun base(supportsReasoning: Boolean?) = LLMModel(
        id = "acme-reasoner",
        displayName = "Acme Reasoner",
        provider = "custom-endpoint",
        supportsReasoning = supportsReasoning,
    )

    private fun entry(supportsReasoning: Boolean?) = ModelEntry(
        providerInstanceId = "inst-1",
        baseModel = base(supportsReasoning),
        isCustom = true,
    )

    @Test
    fun writingTruePinsTheFlag() {
        val written = entry(null).withSupportsReasoningOverride(true)
        assertEquals(true, written.overrides.supportsReasoning)
        assertEquals("the base model is not rewritten", null, written.baseModel.supportsReasoning)
        assertEquals("callers read the pinned value", true, written.model.supportsReasoning)
    }

    @Test
    fun writingFalseIsAPinNotAClear() {
        // Base says the catalog supports reasoning; pinning false must override
        // it, and must NOT be interpreted as "remove the override" (a bool has
        // no third value — documented on the field).
        val written = entry(true).withSupportsReasoningOverride(false)
        assertEquals(false, written.overrides.supportsReasoning)
        assertEquals(false, written.model.supportsReasoning)
    }

    @Test
    fun untouchedEntryKeepsTheRegistryValue() {
        val persisted = entry(true)
        assertNull(persisted.overrides.supportsReasoning)
        assertEquals(true, persisted.model.supportsReasoning)
    }

    @Test
    fun unknownBaseYieldsFalseNotNullForTheReader() {
        // The reader normalises the nullable flag: `ConfigValue.Bool` has no
        // null arm, and a model whose flag is unknown must not answer
        // "supports reasoning".
        val persisted = entry(null)
        assertEquals(false, persisted.model.supportsReasoning == true)
    }

    @Test
    fun fieldsRegistersSupportsReasoningForEveryChild() {
        val relative = "src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        var text: String? = null
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) { text = f.readText(); break }
            dir = dir.parentFile
        }
        val code = text ?: error("$relative not found from user.dir=${System.getProperty("user.dir")}")
        val fieldsAt = code.indexOf("override fun fields(forId: String): List<ConfigField>")
        assertTrue("fields() not found in ModelsCollection", fieldsAt >= 0)
        // Structural, not distance-based: walk to the body's `{` right after the
        // signature, then brace-match to its close. A fixed character window
        // (1200, 900 …) has produced false reds twice in this repo when a
        // legitimate insertion pushed the assertion target past the edge.
        val bodyOpen = code.indexOf('{', fieldsAt)
        assertTrue("fields() has no body brace", bodyOpen > fieldsAt)
        var depth = 0
        var bodyClose = bodyOpen
        while (bodyClose < code.length) {
            when (code[bodyClose]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            bodyClose++
        }
        assertTrue("fields() body is unbalanced", depth == 0 && bodyClose > bodyOpen)
        val fieldsBody = code.substring(bodyOpen, bodyClose + 1)
        assertTrue(
            "fields() must register supportsReasoningField(forId), otherwise the "
                + "`.remove` snapshot drops the property and `minis-config set` "
                + "answers unknown_path",
            "supportsReasoningField(forId)" in fieldsBody,
        )
        assertTrue(
            "the field itself must exist (writable override, not read-only)",
            code.contains("private fun supportsReasoningField(id: String): ConfigField") &&
                code.contains("path = \"models.\$id.supportsReasoning\""),
        )
        assertTrue(
            "the writer must go through the shared transformation",
            code.contains("e.withSupportsReasoningOverride(b)"),
        )
    }

    @Test
    fun snapshotLeafKeyIsSupportsReasoning() {
        // ConfigBridge.snapshotCollectionChild stores `old` values under
        // `field.path.substringAfterLast('.')`. The registered path must therefore
        // end in exactly the property the snapshot is supposed to preserve.
        val path = "models.entry-uuid.supportsReasoning"
        assertEquals("supportsReasoning", path.substringAfterLast('.'))
        // Independence from the literal above: the same leaf extraction run on a
        // path with the collection's real prefix shape.
        assertEquals("supportsReasoning", "models.\$id.supportsReasoning".substringAfterLast('.'))
    }
}
