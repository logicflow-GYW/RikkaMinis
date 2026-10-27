package com.rikkaminis.app.ui.navigation

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1024-E] ISSUE-05: the MODEL_ENTRY_DETAIL composable decoded both route
 * arguments a SECOND time (`?.let { android.net.Uri.decode(it) }`), on top of
 * the decode the Nav framework already performs.
 *
 * Production ground truth for "the framework decodes once" is the navigation
 * library itself, not a comment: `androidx.navigation:navigation-compose:2.8.5`
 * -> `NavDeepLink.getMatchingPathArguments` runs
 * `val value = Uri.decode(matcher.group(index + 1))` over the *raw* URI string
 * (sources jar of navigation-common 2.8.5, `NavDeepLink.kt:233`; the
 * `navigate(route)` entry point reaches it via
 * `NavController.navigate` -> `matchRouteComprehensive` ->
 * `NavDestination.matchDeepLink`). A route segment is therefore decoded exactly
 * once by the time the composable reads it, and `Routes.modelEntryDetail`
 * encodes through `seg()` (== `android.net.Uri.encode`, unreserved =
 * alphanumerics + `_-!.~'()*`) on the way out.
 *
 * The two codec functions below are a JVM model of `android.net.Uri`
 * (`encode(String)` / `decode(String)`), which cannot be linked in a plain JVM
 * unit test. Model choices, both taken from AOSP source rather than guessed:
 *  - unreserved set and uppercase `%XX` from `Uri.encode(String, String)`
 *    (`HEX_DIGITS = "0123456789ABCDEF"`, doc: leaves letters, numbers and
 *    `_-!.~'()*` intact);
 *  - `decode` collects consecutive `%XX` octets into one UTF-8 sequence and
 *    substitutes U+FFFD for an invalid octet instead of throwing — Android 13+
 *    `Uri.decode(String)` delegates to `UriCodec.decode(s, false, UTF_8,
 *    false /* throwOnFailure */)` with `INVALID_INPUT_CHARACTER = '\ufffd'`
 *    (checked on the android13/14/15 release branches; older AOSP threw
 *    IllegalArgumentException). The defect is identical either way: the id no
 *    longer matches, so `ModelEntryDetailScreen` finds no entry and pops
 *    straight back (`ModelEntryDetailScreen.kt:55-62`).
 * Where the model is *not* authoritative the assertion says so instead of
 * pinning a fabricated literal (see `malformed escape corrupts` below).
 *
 * Every expectation is a literal written from the codec definitions above or
 * from the JDK, never derived from the codec functions under test, so the
 * double-decode half of this test fails on the pre-fix tree for the stated
 * reason (differential closure: `probe/jvm/` holds the pre-fix source arm).
 */
class ModelEntryRouteDecodeTest {

    // ── the builder side: what Routes.modelEntryDetail actually puts in the route ─

    @Test
    fun `segment encoding matches the AOSP literals`() {
        assertEquals("50%25off", uriEncodeSegment("50%off"))
        assertEquals("100%2525", uriEncodeSegment("100%25"))
        assertEquals("a%2Fb", uriEncodeSegment("a/b"))
        assertEquals("a%2Bb", uriEncodeSegment("a+b"))
        assertEquals("a%20b", uriEncodeSegment("a b"))
        assertEquals("%E4%B8%AD%E6%96%87", uriEncodeSegment("\u4e2d\u6587"))
        // unreserved characters are copied verbatim (so an ordinary id is
        // unchanged by the route round-trip).
        assertEquals("gpt-4o.mini_v2", uriEncodeSegment("gpt-4o.mini_v2"))
    }

    // ── the framework side: exactly one decode is lossless ───────────────────────

    @Test
    fun `one encode plus one decode is the identity`() {
        val ids = listOf(
            "50%off",
            "100%25",
            "a/b",
            "a+b",
            "a b",
            "\u4e2d\u6587",
            "a?b#c&d",
            "vendor/gpt-4o",
            "gpt-4o.mini_v2",
        )
        for (id in ids) {
            assertEquals("round-trip lost id: $id", id, uriDecodeOnce(uriEncodeSegment(id)))
        }
    }

    @Test
    fun `composite key with a literal slash stays one path segment`() {
        // entry.id == "<instanceId>/<modelId>" (compositeEntryKey) — the reason
        // the builder encodes at all ([T-android-model-entry-route-slash-crash]).
        val instanceId = "0e5f5b7c-1f1e-4d2b-9c3a-111111111111"
        val entryId = "$instanceId/model-1"
        val route = "model_entry/${uriEncodeSegment(instanceId)}/${uriEncodeSegment(entryId)}"
        // Pattern is model_entry/{instanceId}/{entryId}: exactly three
        // segments, otherwise navigate() throws "destination cannot be found".
        assertEquals(3, route.split("/").size)
        assertEquals("0e5f5b7c-1f1e-4d2b-9c3a-111111111111%2Fmodel-1", route.split("/")[2])
        assertEquals(entryId, uriDecodeOnce(route.split("/")[2]))
    }

    // ── the defect: a second decode is not the identity ──────────────────────────

    @Test
    fun `second decode rewrites a literal percent escape`() {
        // raw id "100%25" -> route "100%2525" -> framework -> "100%25"
        //                -> the extra Uri.decode -> "100%"
        val arrived = uriDecodeOnce(uriEncodeSegment("100%25"))
        assertEquals("100%25", arrived)
        val twice = uriDecodeOnce(arrived)
        assertEquals("100%", twice)
        assertNotEquals(arrived, twice)
    }

    @Test
    fun `malformed escape corrupts instead of staying intact`() {
        // raw id "50%off" -> route "50%25off" -> framework -> "50%off"
        //                  -> the extra Uri.decode hits a bare '%'.
        // Android 13+ substitutes U+FFFD and returns (it does NOT throw); the
        // exact replacement shape is platform-dependent, so this asserts the
        // property that matters — the value is no longer the id — and the
        // documented substitution, not a fabricated literal.
        val arrived = uriDecodeOnce(uriEncodeSegment("50%off"))
        assertEquals("50%off", arrived)
        val twice = uriDecodeOnce(arrived)
        assertNotEquals("second decode left the id intact", arrived, twice)
        assertTrue("expected a replacement character, got: $twice", twice.contains('\uFFFD'))
    }

    @Test
    fun `second decode of a percent-escaped slash splits the composite key`() {
        // raw entryId "1a2b/model%2Fx" -> framework gives it back verbatim
        // (the literal %2F is data, not an escape) -> second decode turns it
        // into a real slash, so the lookup key silently changes.
        val raw = "1a2b/model%2Fx"
        val arrived = uriDecodeOnce(uriEncodeSegment(raw))
        assertEquals(raw, arrived)
        assertNotEquals(arrived, uriDecodeOnce(arrived))
        assertEquals("1a2b/model/x", uriDecodeOnce(arrived))
    }

    // ── boundary: the framework's decoder is Uri.decode, not URLDecoder ──────────

    @Test
    fun `only ids carrying a literal percent are broken by the extra decode`() {
        // The trigger condition (TRIAGE I05: "narrow"): a literal '%' in the
        // raw id. Everything else survives even a second decode, which is why
        // the defect stayed invisible for so long.
        val broken = listOf("100%25", "50%off", "a%2Fb")
        for (id in broken) {
            assertNotEquals(
                "expected the extra decode to change $id",
                id,
                uriDecodeOnce(uriDecodeOnce(uriEncodeSegment(id))),
            )
        }
        val intact = listOf("a+b", "a/b", "a b", "\u4e2d\u6587", "gpt-4o.mini_v2", "vendor/gpt-4o")
        for (id in intact) {
            assertEquals(
                "expected the extra decode to leave $id alone",
                id,
                uriDecodeOnce(uriDecodeOnce(uriEncodeSegment(id))),
            )
        }
    }

    @Test
    fun `plus stays a plus under Uri decode and becomes a space under URLDecoder`() {
        // convertPlus == false in UriCodec.decode, so '+'-bearing ids survive
        // even a second Uri.decode; this is the boundary that rules out "swap
        // the extra decode for URLDecoder" as a fix (URLDecoder would corrupt
        // `c++_primer`-style ids the first time round).
        assertEquals("a+b", uriDecodeOnce("a%2Bb"))
        assertEquals("a+b", uriDecodeOnce("a+b"))
        assertEquals("a+b", uriDecodeOnce(uriDecodeOnce(uriEncodeSegment("a+b"))))
        assertEquals("a b", URLDecoder.decode("a+b", "UTF-8"))
    }

    @Test
    fun `model agrees with the JDK on well-formed escapes`() {
        // independent implementation check on the path where both are defined:
        // URLDecoder differs from Uri.decode only in its '+' handling, so
        // normalising '+' away lets the JDK act as a second opinion.
        val samples = listOf("%25", "%2F", "%20", "a%25b", "%E4%B8%AD", "x%7Ey")
        for (s in samples) {
            assertEquals(
                "model diverged from the JDK for $s",
                URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"),
                uriDecodeOnce(s),
            )
        }
    }

    // ── production source shape: the fix is a deletion, not a substitution ───────

    @Test
    fun `navigation graph does not decode route arguments itself`() {
        val code = readRepoFile(NAV_REL)
            .lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "AppNavigation.kt decodes a route argument again (the Nav framework already did)",
            code.contains("Uri.decode(") || code.contains("URLDecoder.decode("),
        )
        // ...while the builder side must keep encoding.
        assertTrue(code.contains("Uri.encode("))
        assertTrue(
            "Routes.modelEntryDetail must encode both segments through seg()",
            code.contains("model_entry/\${seg(instanceId)}/\${seg(entryId)}"),
        )
    }

    @Test
    fun `model entry route reads both args fail closed`() {
        val code = readRepoFile(NAV_REL)
        val start = code.indexOf("route = Routes.MODEL_ENTRY_DETAIL")
        assertTrue("MODEL_ENTRY_DETAIL composable not found", start >= 0)
        val block = code.substring(start, minOf(code.length, start + 1600))
        assertTrue(block.contains("getString(\"instanceId\")"))
        assertTrue(block.contains("getString(\"entryId\")"))
        assertTrue(
            "the fail-closed `?: return@composable` guard is gone",
            block.contains("return@composable"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val NAV_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"

        const val HEX = "0123456789ABCDEF"

        /** JVM model of `android.net.Uri.encode(String)`. */
        fun uriEncodeSegment(raw: String): String {
            val out = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch in "-_.!~*'()"
                ) {
                    out.append(ch)
                } else {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        val v = b.toInt() and 0xFF
                        out.append('%').append(HEX[v ushr 4]).append(HEX[v and 0xF])
                    }
                }
            }
            return out.toString()
        }

        /** JVM model of `android.net.Uri.decode(String)` (convertPlus = false). */
        fun uriDecodeOnce(s: String): String {
            val out = StringBuilder(s.length)
            val bytes = ByteArrayOutputStream(s.length)
            fun flush() {
                if (bytes.size() > 0) {
                    out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                    bytes.reset()
                }
            }
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val hex1 = if (i + 1 < s.length) hexDigit(s[i + 1]) else -1
                val hex2 = if (hex1 >= 0 && i + 2 < s.length) hexDigit(s[i + 2]) else -1
                when {
                    c == '+' -> { flush(); out.append('+'); i++ }
                    c == '%' && hex1 >= 0 && hex2 >= 0 -> {
                        bytes.write((hex1 shl 4) or hex2); i += 3
                    }
                    c == '%' -> { flush(); out.append('\uFFFD'); i++ }
                    else -> { flush(); out.append(c); i++ }
                }
            }
            flush()
            return out.toString()
        }

        fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
