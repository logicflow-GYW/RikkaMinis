package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [fix/provider-order-1118] Pins the ONE provider ordering every surface renders.
 *
 * The defect was not a wrong comparator — it was three surfaces deriving their own
 * layout from the same stored order, so "the order I set in Settings" was simply not
 * what the model-group add sheet drew. These tests pin the derivation itself (pure list
 * algebra over a local row type, so no Compose / ProviderInstance in the compile unit)
 * and the wiring that makes every surface consume it.
 */
class ProviderOrderTest {

    private data class Row(val id: String, val pinned: Boolean, val type: String)

    private fun order(rows: List<Row>): List<String> =
        providerDisplayOrder(rows, pinned = { it.pinned }, typeKey = { it.type }).map { it.id }

    private fun blocks(rows: List<Row>): List<ProviderBlock<Row, String>> =
        providerBlocks(rows, pinned = { it.pinned }, typeKey = { it.type })

    private val flat = listOf(
        Row("openai-a", false, "openAI"),
        Row("gemini-b", false, "gemini"),
        Row("openai-c", false, "openAI"),
        Row("anthropic-d", false, "anthropic"),
    )

    @Test
    fun `favorites come first, in the order the stored list has them`() {
        val rows = listOf(
            Row("a", false, "openAI"),
            Row("p2", true, "gemini"),
            Row("b", false, "gemini"),
            Row("p1", true, "openAI"),
        )
        assertEquals(listOf("p2", "p1", "a", "b"), order(rows))
    }

    @Test
    fun `no favorites means no empty favorites block`() {
        assertTrue(blocks(flat).none { it.typeKey == null })
        assertEquals(listOf("openAI", "gemini", "anthropic"), blocks(flat).map { it.typeKey })
    }

    @Test
    fun `types keep first-appearance order and each block keeps flat order`() {
        // This is the shape Settings has always drawn. Alphabetical order here would
        // silently rearrange the user's blocks (anthropic before gemini before openAI).
        assertEquals(listOf("openai-a", "openai-c", "gemini-b", "anthropic-d"), order(flat))
        assertEquals(
            listOf(listOf("openai-a", "openai-c"), listOf("gemini-b"), listOf("anthropic-d")),
            blocks(flat).map { b -> b.items.map { it.id } },
        )
    }

    @Test
    fun `the flattened blocks and the display order are the same list`() {
        val rows = flat + Row("p", true, "anthropic")
        assertEquals(blocks(rows).flatMap { it.items.map { r -> r.id } }, order(rows))
    }

    @Test
    fun `no row is added, dropped or duplicated`() {
        val withFavorites = flat + Row("p1", true, "gemini") + Row("p2", true, "openAI")
        val out = order(withFavorites)
        assertEquals(withFavorites.size, out.size)
        assertEquals(withFavorites.map { it.id }.toSet(), out.toSet())
    }

    @Test
    fun `interleaved types do not merge`() {
        // A single row of an unseen type between two known ones keeps its own block:
        // the derivation must not collapse equal-type rows across a later type.
        val rows = listOf(
            Row("o1", false, "openAI"),
            Row("g1", false, "gemini"),
            Row("o2", false, "openAI"),
        )
        assertEquals(listOf("o1", "o2", "g1"), order(rows))
        assertFalse(blocks(rows).any { it.items.isEmpty() })
    }

    // ── wiring: every surface renders this one derivation ────────────────────────

    @Test
    fun `the provider list renders the shared blocks`() {
        val code = code(SETTINGS_LIST)
        assertTrue(
            "Settings → AI providers must derive its sections from providerBlocks",
            "providerBlocks(" in code,
        )
        assertTrue(
            "a second, private bucketing of instances would let this screen drift again",
            "groupBy { it.instance.providerType }" !in code,
        )
    }

    @Test
    fun `both pickers render the shared order`() {
        val picker = code(MODEL_ENTRY_PICKER)
        assertTrue(
            "the model-group / agent-loop add picker must order instances through the " +
                "shared function, otherwise pinned providers fall back to raw positions",
            "providerDisplayOrder(" in picker,
        )
        assertTrue(
            "the picker must ITERATE the ordered list — deriving it and then walking the " +
                "raw parameter would leave the order unchanged",
            "listOfNotNull(systemPair) + orderedInstances" in picker,
        )
        val chatPicker = code(CHAT_PICKER)
        assertTrue(
            "the chat model picker must use the same derivation",
            "providerDisplayOrder(" in chatPicker,
        )
    }

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Comments stripped so a KDoc mention can never satisfy a match. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i += 1
                }
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        sb.append(next)
                        i += 1
                    } else if (c == '"') {
                        inString = false
                    }
                }
                else -> when {
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    c == '/' && next == '/' -> {
                        inLine = true
                        i += 1
                    }
                    c == '/' && next == '*' -> {
                        inBlock = true
                        i += 1
                    }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private companion object {
        const val BASE = "src/android/app/src/main/java/com/rikkaminis/app/"
        const val SETTINGS_LIST = BASE + "ui/settings/ProviderListScreen.kt"
        const val MODEL_ENTRY_PICKER = BASE + "ui/components/ModelEntryPicker.kt"
        const val CHAT_PICKER = BASE + "ui/chat/ChatModelPickerSheet.kt"
    }
}
