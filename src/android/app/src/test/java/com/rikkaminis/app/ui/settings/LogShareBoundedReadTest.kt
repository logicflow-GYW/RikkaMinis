package com.rikkaminis.app.ui.settings

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [deep-audit-1041 B-07] The share fallback's text body must be read with an
 * upper bound, off the Main thread.
 *
 * Bug context: `shareLogFile`'s `FileProvider` failure arm ran
 * `file.readText().take(100_000)` — the whole file into one `String` and only
 * then trimmed. The KDoc a screen up in the same file records what a 33 MB day
 * of agent traffic did to that very pattern in the log viewer; on the share
 * path it was still live, reachable from a button whose `onClick` also did a
 * main-thread `File.exists()`.
 *
 * The reader is a real function and is exercised here; the "is the prep off
 * Main / is the bare read gone" half is the mechanical half of
 * `scripts/scan/filepreview_print_bounded_check.py`. Expected values are dead
 * literals, NOT derived from the production constants.
 */
class LogShareBoundedReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the default bound is still the 100k the fallback always claimed`() {
        assertEquals(100_000, LOG_SHARE_MAX_CHARS)
    }

    @Test
    fun `a file larger than the bound yields exactly the bound`() {
        val file = tmp.newFile("minis-2026-01-01.log")
        val body = "x".repeat(250_000)
        file.writeText(body)

        val text = readLogShareText(file)

        assertEquals("the fallback body must be capped", 100_000, text!!.length)
        assertEquals("and it must be the head of the file", body.take(100_000), text)
    }

    @Test
    fun `a file shorter than the bound is returned whole`() {
        val file = tmp.newFile("short.log")
        file.writeText("hello log\n")

        assertEquals("hello log\n", readLogShareText(file))
    }

    @Test
    fun `the bound counts characters, so a multi-byte tail is never mangled`() {
        // A byte-capped read would cut mid-codepoint and hand the chooser a
        // trailing U+FFFD; the old `.take(n)` was character-based.
        val file = tmp.newFile("cjk.log")
        file.writeText("中文日志行".repeat(50))

        assertEquals("中文日", readLogShareText(file, 3))
    }

    @Test
    fun `an unreadable file reports nothing to share, not an empty body`() {
        // Same outcome the old `catch (_: Exception) { return }` produced:
        // no chooser at all rather than a share of "".
        assertNull(readLogShareText(File(tmp.root, "does-not-exist.log")))
    }

    @Test
    fun `a bound of zero reads nothing instead of everything`() {
        val file = tmp.newFile("zero.log")
        file.writeText("x".repeat(1_000))

        assertEquals("", readLogShareText(file, 0))
    }

    /**
     * The shape half: no whole-file read may come back, and the fallback must
     * go through the streaming reader. Counts are dead literals (0), so a
     * re-introduced `.readText()` fails here as well as in the gate.
     */
    @Test
    fun `the share path keeps no whole-file read`() {
        val code = stripComments(readRepoFile(SCREEN))
        for (bare in listOf(".readText()", "bufferedReader(", ".readLines()", ".readBytes()")) {
            assertEquals("$bare must not appear in LogManagementScreen.kt", 0, occurrences(code, bare))
        }
        assertTrue(
            "the EXTRA_TEXT fallback must read through the bounded reader",
            code.contains("readLogShareText("),
        )
        assertTrue(
            "the bounded reader must stream from a FileInputStream",
            code.contains("FileInputStream("),
        )
        assertTrue(
            "the share prep must hop off the caller's thread",
            code.contains("withContext(Dispatchers.IO)"),
        )
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** Removes // and block comments (nesting-aware) while keeping string literals. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        nest--
                        i += 2
                    } else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        nest++
                        i += 2
                    } else {
                        i++
                    }
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        private const val SCREEN =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/LogManagementScreen.kt"
    }
}
