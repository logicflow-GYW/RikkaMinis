package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1044 B-02] A group member id resolves through ONE source, so a
 * System voice member is never silently dropped.
 *
 * The System voice sentinels (`__builtin_system_speech__/system-asr-online`,
 * `…/system-asr-offline`, `…/system-tts`) are NEVER persisted in
 * `config.modelEntries` — they are synthesized on demand
 * (`SystemVoiceEntries.resolve`). A default Voice Input/Output group holds
 * ONLY those sentinels, so every reader that looked the member up in
 * `config.modelEntries` alone resolved them to null: the chat model picker
 * rendered the group as 「2 models (unlinked)」 with an empty member preview,
 * and the group's tap resolved no first entry — so the non-text-model
 * confirmation was skipped for exactly the groups that cannot drive a loop.
 *
 * The decision now lives in `SystemVoiceEntries.resolveIn` (persisted first,
 * System second). The arithmetic is not JVM-pinnable (the resolution needs the
 * `ModelEntry` data class), so this is a source-shape probe over the two
 * surfaces, plus a check that the routing contract
 * (`ProviderRepository.enabledMemberEntries` excluding the sentinels) is
 * untouched.
 */
class ChatModelPickerVoiceGroupTest {

    private val pickerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatModelPickerSheet.kt"
    private val screenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
    private val repoRel =
        "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt"

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun read(rel: String): String = repoFile(rel).readText()

    /**
     * Drops `//` and block comments so doc text cannot satisfy an arm.
     *
     * STRING-AWARE on purpose: ChatScreen.kt contains a wildcard mime literal
     * whose text is a slash followed by an asterisk — inside a string literal.
     * A naive stripper treats it as a comment opener and eats the rest of the
     * file (until the next comment terminator), which silently blanks every
     * later line and turns a source probe into a green nothing. The string is
     * kept whole here, and a comment marker inside it never starts a comment.
     */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' && text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) text.length else end + 3
                sb.append(text, i, stop)
                i = stop
            } else if (c == '"') {
                var j = i + 1
                while (j < text.length && text[j] != '"') {
                    if (text[j] == '\\') j++
                    j++
                }
                val stop = if (j < text.length) j + 1 else text.length
                sb.append(text, i, stop)
                i = stop
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("*/", i)
                i = if (end < 0) text.length else end + 2
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + 1)
        }
        return n
    }

    @Test
    fun `the picker resolves group members through the single source`() {
        val code = codeOnly(read(pickerRel))
        assertTrue(
            "the picker's member resolution must consult SystemVoiceEntries",
            code.contains("SystemVoiceEntries.resolveIn("),
        )
        assertEquals(
            "the picker must not resolve a member against config.modelEntries alone — " +
                "that is what dropped the System sentinels",
            0,
            count(code, "config.modelEntries.find { it.id == entryId }"),
        )
        assertEquals(
            "the picker must not count members with a persisted-only predicate",
            0,
            count(code, "config.modelEntries.any { it.id == entryId }"),
        )
    }

    @Test
    fun `the screen resolves a group member through the single source`() {
        val code = codeOnly(read(screenRel))
        assertTrue(
            "the screen's entry lookup must consult SystemVoiceEntries",
            code.contains("SystemVoiceEntries.resolveIn("),
        )
        assertEquals(
            "the screen must not resolve a member against config.modelEntries alone",
            0,
            count(code, "config.modelEntries.firstOrNull { it.id == entryId }"),
        )
    }

    @Test
    fun `an audio-input model with no declared output earns the audio label`() {
        // A System ASR sentinel declares an audio INPUT and no output modality
        // at all, so the three output checks missed it and a Voice Input group
        // was bound without the non-text confirmation.
        val code = codeOnly(read(screenRel))
        assertTrue(
            "the non-text label must consider the input modalities too",
            code.contains("model.inputModalities.normalizeModalities()"),
        )
    }

    @Test
    fun `the chat routing contract still excludes the system sentinels`() {
        // [deep-audit-1043 B-06] pinned contract: `enabledMemberEntries` answers
        // 「which members can serve a CHAT turn」, and the sentinels can never
        // produce a ProviderInstance or an API key — so they must stay OUT of
        // it. This branch must not have widened it.
        val code = codeOnly(read(repoRel))
        val at = code.indexOf("fun enabledMemberEntries(")
        assertTrue("enabledMemberEntries not found", at >= 0)
        val body = code.substring(at, minOf(code.length, at + 2500))
        assertTrue(
            "enabledMemberEntries must keep excluding the System sentinels",
            body.contains("isSystemEntryId") || body.contains("SystemVoiceEntries"),
        )
    }
}
