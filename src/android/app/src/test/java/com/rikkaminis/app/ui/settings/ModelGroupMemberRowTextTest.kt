package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [model-group-member-id] Settings → Model Group detail, the member row's
 * secondary line.
 *
 * Two layers:
 *  • the DECISIONS live in `ModelGroupMemberRowText` (Android-free) and are
 *    asserted directly — what the line says when the provider label or the id
 *    is missing, and that both halves are trimmed;
 *  • the WIRING is Compose, so it is asserted on the production text with line
 *    comments blanked out: the row must build the line through the helper, must
 *    not fade the whole row for a disabled provider any more, and must keep the
 *    red warning on its own line instead of appending it to the id.
 */
class ModelGroupMemberRowTextTest {

    private val settingsDir = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings"
    private val screenRel = "$settingsDir/ModelGroupDetailScreen.kt"
    private val textRel = "$settingsDir/ModelGroupMemberRowText.kt"

    private fun sourceFileOrNull(rel: String): File? {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        return null
    }

    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) " ".repeat(line.length) else line
    }

    private fun requireSource(rel: String): String =
        sourceFileOrNull(rel)?.let { codeOnly(it.readText()) }
            ?: throw IllegalStateException("cannot locate $rel — a source-shape arm must never skip")

    // ── decisions ────────────────────────────────────────────────────────────

    /** The whole point: provider and the exact model id on one line. */
    @Test
    fun `provider and model id share one line`() {
        assertEquals(
            "Anthropic · claude-3-5-sonnet-20241022",
            ModelGroupMemberRowText.secondaryLine("Anthropic", "claude-3-5-sonnet-20241022"),
        )
    }

    /**
     * A stale persisted member has no provider instance, so the label is empty.
     * The line must be just the id — a leading " · " would read as a render bug.
     */
    @Test
    fun `a missing provider leaves only the model id`() {
        assertEquals(
            "gpt-4o",
            ModelGroupMemberRowText.secondaryLine(null, "gpt-4o"),
        )
        assertEquals(
            "gpt-4o",
            ModelGroupMemberRowText.secondaryLine("   ", "gpt-4o"),
        )
    }

    /** Nothing better to say than the provider name; no dangling separator. */
    @Test
    fun `a missing model id leaves only the provider`() {
        assertEquals("OpenAI 直连", ModelGroupMemberRowText.secondaryLine("OpenAI 直连", ""))
    }

    /** Both missing → empty; the caller renders no secondary line at all. */
    @Test
    fun `both halves missing yields an empty line`() {
        assertEquals("", ModelGroupMemberRowText.secondaryLine(null, "  "))
    }

    /** Provider labels are user-typed; stray whitespace must not leak into the line. */
    @Test
    fun `both halves are trimmed`() {
        assertEquals(
            "OpenAI 直连 · gpt-4o",
            ModelGroupMemberRowText.secondaryLine("  OpenAI 直连  ", "  gpt-4o  "),
        )
    }

    // ── wiring ───────────────────────────────────────────────────────────────

    /** The row builds its line through the helper (one decision, one place). */
    @Test
    fun `the member row uses the shared secondary line`() {
        val code = requireSource(screenRel)
        assertTrue(
            "ModelGroupDetailScreen must build the member line through ModelGroupMemberRowText",
            code.contains("ModelGroupMemberRowText.secondaryLine("),
        )
        assertTrue(
            "the line must carry the model id, not just the provider label",
            code.contains("modelId = entry.model.id"),
        )
    }

    /**
     * A disabled provider used to fade the whole row to 40% — which also faded
     * the red warning and, now, the model id the row exists to show. The state
     * belongs to the red line, not to the row's opacity.
     */
    @Test
    fun `a disabled provider no longer fades the whole row`() {
        val code = requireSource(screenRel)
        assertTrue(
            "the member row must not fade itself with alpha for a disabled provider",
            !code.contains("Modifier.alpha(if (providerDisabled)"),
        )
    }

    /** The warning must be its own line — appended to the id it gets truncated. */
    @Test
    fun `the disabled warning is not appended to the id line`() {
        val code = requireSource(screenRel)
        assertTrue(
            "the old \" · Provider disabled\" suffix must be gone",
            !code.contains("\" · Provider disabled\""),
        )
        assertTrue(
            "the row must still say \"Provider disabled\" on its own line",
            code.contains("\"Provider disabled\""),
        )
    }

    /** Same single source for a System member's provider name as every other surface. */
    @Test
    fun `the provider label still comes from the shared presentation helper`() {
        val code = requireSource(screenRel)
        assertTrue(
            "the provider name must be resolved through SystemVoiceMemberPresentation",
            code.contains("SystemVoiceMemberPresentation.memberSubtitle("),
        )
    }

    /** The decision file must stay Android-free so the JVM arms above mean something. */
    @Test
    fun `the decision file stays android free`() {
        val code = requireSource(textRel)
        val imports = code.lines().filter { it.trimStart().startsWith("import ") }
        assertTrue(
            "ModelGroupMemberRowText must not import Android/Compose/R types (found: $imports)",
            imports.none { line ->
                line.contains("androidx.") || line.contains("android.") ||
                    line.contains("com.rikkaminis.app.R")
            },
        )
    }
}
