package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1038 FIX-1 / A-03] `android-photos album` reads two selectors that
 * mean different SQL. Reading them independently (`getLong("id")`, then
 * `get("name")`) made three inputs answer a question nobody asked:
 *
 *   - `--id abc --name Foo` → the non-number id was dropped and the *name*
 *     branch ran;
 *   - `--id abc` alone → "required", although the flag was supplied;
 *   - `--name ""` → `LIKE '%%'`, i.e. every photo in the library, exit 0.
 *
 * [resolveAlbumSelector] is the single decision; the matrix below pins the
 * classification, and the source shape below pins that `handleAlbum` really
 * routes through it (a classifier nothing calls would be a third way to lie).
 */
class PhotosAlbumSelectorTest {

    private val handler =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"

    // ── the classification matrix ───────────────────────────────────────────

    @Test
    fun `neither flag is absent`() {
        assertEquals(AlbumSelector.Absent, resolveAlbumSelector(null, null))
    }

    @Test
    fun `a numeric id is an exact bucket id`() {
        assertEquals(AlbumSelector.ById(7L), resolveAlbumSelector("7", null))
        // --id wins over --name, as documented.
        assertEquals(AlbumSelector.ById(7L), resolveAlbumSelector("7", "Foo"))
    }

    @Test
    fun `a non-numeric id is refused even when a name is present`() {
        val selector = resolveAlbumSelector("abc", "Foo")
        assertEquals(AlbumSelector.Unreadable("--id", "abc"), selector)
        // The old shape downgraded to the name branch here.
        assertFalse("a bad --id must not become a name search", selector is AlbumSelector.ByName)
    }

    @Test
    fun `a non-numeric id alone is refused instead of reporting a missing flag`() {
        assertEquals(AlbumSelector.Unreadable("--id", "abc"), resolveAlbumSelector("abc", null))
    }

    @Test
    fun `an id that overflows a Long is refused`() {
        assertEquals(
            AlbumSelector.Unreadable("--id", "9223372036854775808"),
            resolveAlbumSelector("9223372036854775808", null),
        )
    }

    @Test
    fun `a blank name is refused instead of matching the whole library`() {
        assertEquals(AlbumSelector.Unreadable("--name", ""), resolveAlbumSelector(null, ""))
        assertEquals(AlbumSelector.Unreadable("--name", "   "), resolveAlbumSelector(null, "   "))
    }

    @Test
    fun `a usable name is a containing match`() {
        assertEquals(AlbumSelector.ByName("Camera"), resolveAlbumSelector(null, "Camera"))
        // Only blank is refused: a padded name keeps its exact pattern.
        assertEquals(AlbumSelector.ByName(" Camera "), resolveAlbumSelector(null, " Camera "))
    }

    @Test
    fun `the refusal names the flag the caller typed`() {
        val idRefusal = albumSelectorRefusal(AlbumSelector.Unreadable("--id", "abc"))
        assertTrue(idRefusal, idRefusal.contains("--id"))
        assertTrue(idRefusal, idRefusal.contains("abc"))
        assertTrue(idRefusal, idRefusal.contains("numbers only"))

        val nameRefusal = albumSelectorRefusal(AlbumSelector.Unreadable("--name", " "))
        assertTrue(nameRefusal, nameRefusal.contains("--name"))
        assertTrue(nameRefusal, nameRefusal.contains("blank"))
    }

    // ── the handler routes through the classification ───────────────────────

    @Test
    fun `handleAlbum decides through the single classifier`() {
        val body = stripComments(braceSlice(readHandler(), "private fun handleAlbum("))
        assertTrue(body, body.contains("resolveAlbumSelector("))
        // The old two independent reads: an id parsed on its own...
        assertFalse("--id must not be parsed apart from the classification", body.contains("getLong(\"id\")"))
        // ...and an unreadable selector must reach a refusal, not fall through.
        assertTrue(body, Regex("""AlbumSelector\.Unreadable\s*->\s*return NativeOffloadResult\(2,""").containsMatchIn(body))
        // The unreadable case asks the shared refusal helper for its message.
        assertTrue(body, body.contains("albumSelectorRefusal("))
    }

    @Test
    fun `the LIKE pattern only exists under the name branch`() {
        val body = stripComments(braceSlice(readHandler(), "private fun handleAlbum("))
        assertEquals("exactly one name-match pattern", 1, Regex("""LIKE \?""").findAll(body).count())
        val byName = stripComments(braceSlice(body, "is AlbumSelector.ByName ->"))
        assertTrue(byName, byName.contains("LIKE ?"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readHandler(): String {
        val f = File(repoRoot(), handler)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, handler).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** `src` from the first `{` after [marker], balanced. */
    private fun braceSlice(src: String, marker: String): String {
        val at = src.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        val open = src.indexOf('{', at)
        assertTrue("no block after: $marker", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(at, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced block after: $marker")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = if (i + 2 > src.length) src.length else i + 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
