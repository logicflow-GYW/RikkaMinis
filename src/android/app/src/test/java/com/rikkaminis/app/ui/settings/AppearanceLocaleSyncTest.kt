package com.rikkaminis.app.ui.settings

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1034 FIX-B1] Production wiring for B-01 and B-02.
 *
 * Both defects are "two halves that must agree, wired in only one place":
 *
 *  * B-02 ([AppearanceScreen]'s language row): registering a locale in
 *    `res/xml/locales_config.xml` must track the `values-*` directories that
 *    actually ship (zh-TW shipped 1568 strings and was never registered; `fr`
 *    was registered with no `values-fr/` at all), and a language change must
 *    reach the *running process* — not only the Compose state — including when
 *    the pref is written from outside the screen (backup restore /
 *    `minis-config set appearance.language` / WebDAV restore).
 *  * B-01 ([com.rikkaminis.app.ui.onboarding.OnboardingScreen] step 1): the
 *    post-save model fetch is fire-and-forget, but it must not run on the
 *    step's composition scope — `saved` unlocks Next and the next tap disposes
 *    the step, cancelling the fetch and leaving the model picker empty.
 *
 * These are shape/wiring assertions on purpose: the behaviour needs a real
 * Activity + LocaleManager (API 33 branching, `recreate()`), and the sandbox
 * has no Android runtime. The mechanical half of both defects lives in
 * `scripts/scan/i18n_check.py`; this class pins the Kotlin side so a later
 * "quick fix" cannot re-inline the second copy or move the fetch back onto the
 * step's scope without turning a test red.
 *
 * Source-shape assertions read the repo files (Gradle sets `user.dir` to the
 * module dir; a sandbox JVM run may start anywhere under the repo, so the
 * lookup walks up).
 */
class AppearanceLocaleSyncTest {

    // ── B-02: registration ↔ the resource directories that ship ──────────

    @Test
    fun `zh-TW is registered and fr is not`() {
        val tags = registeredLocales()
        assertTrue(
            "values-zh-rTW ships 1568 translated keys but zh-TW is not registered, " +
                "so the system per-app language picker cannot offer it: $tags",
            tags.contains("zh-TW"),
        )
        assertFalse(
            "fr is registered but no values-fr/ directory ships, so picking French " +
                "falls back to the English default: $tags",
            tags.contains("fr"),
        )
    }

    @Test
    fun `every registered locale resolves to a shipped strings folder`() {
        val resDir = File(readRepoDir(), RES_DIR)
        val missing = registeredLocales().filter { tag ->
            val dir = File(resDir, resourceDirFor(tag))
            !dir.isDirectory || dir.listFiles()?.none { it.name.startsWith("strings") && it.name.endsWith(".xml") } != false
        }
        assertTrue(
            "locales_config.xml registers locales with no strings resources behind them " +
                "(the system picker would offer a language that renders as the default): $missing",
            missing.isEmpty(),
        )
    }

    // ── B-02: one apply function, reached from both writers ──────────────

    @Test
    fun `the prefs listener applies the language instead of only moving the checkmark`() {
        val branch = languageListenerBranch()
        assertTrue(
            "an external write to KEY_LANGUAGE (backup restore / minis-config / WebDAV) " +
                "must apply the language, not only update the Compose state:\n$branch",
            branch.contains("applyLanguageChange("),
        )
    }

    @Test
    fun `the chooser and the listener share the same apply function`() {
        val src = readRepoFile(APPEARANCE)
        val calls = Regex(Regex.escape("applyLanguageChange(context, prefs,")).findAll(src).count()
        assertTrue(
            "expected exactly two call sites of the shared apply function (language row + " +
                "prefs listener), found $calls; a second inline copy is the defect this pins",
            calls == 2,
        )
        assertTrue(
            "the language row must keep calling the shared apply function:\n" +
                declarationSlice(src, "fun AppearanceScreen("),
            declarationSlice(src, "fun AppearanceScreen(").contains("applyLanguageChange("),
        )
    }

    @Test
    fun `the stored language is written before the process locale is applied`() {
        val body = applyFunctionBody()
        val writeAt = body.indexOf("putString(KEY_LANGUAGE")
        val pushAt = body.indexOf("applicationLocales")
        val recreateAt = body.indexOf(".recreate()")
        assertTrue("applyLanguageChange must persist the pref:\n$body", writeAt >= 0)
        assertTrue("applyLanguageChange must push the live locale:\n$body", pushAt >= 0)
        assertTrue("applyLanguageChange must keep the pre-Tiramisu recreate branch:\n$body", recreateAt >= 0)
        assertTrue(
            "the pref must be written BEFORE the locale is applied / the Activity recreated " +
                "(on API < 33 recreate() re-reads the pref from attachBaseContext):\n$body",
            writeAt < pushAt && writeAt < recreateAt,
        )
        assertTrue(
            "the pre-Tiramisu path keeps its T-n01-andmenu-l10n rationale:\n$body",
            body.contains("T-n01-andmenu-l10n"),
        )
        assertFalse(
            "TIRAMISU must still be the gate for applicationLocales:\n$body",
            body.contains("applicationLocales") && !body.contains("VERSION_CODES.TIRAMISU"),
        )
    }

    @Test
    fun `the live locale is pushed from exactly one place in the file`() {
        val src = readRepoFile(APPEARANCE)
        val pushes = Regex("""applicationLocales\s*=""").findAll(src).count()
        assertTrue(
            "the live locale must be pushed from exactly one place, found $pushes " +
                "assignment(s) to applicationLocales",
            pushes == 1,
        )
    }

    // ── B-01: the onboarding model fetch outlives its step ───────────────

    @Test
    fun `the onboarding model refresh runs on the application scope`() {
        val src = readRepoFile(ONBOARDING)
        val body = declarationSlice(src, "private fun ApiKeyStep(")
        assertFalse(
            "the fire-and-forget refresh must not be launched on the step's composition " +
                "scope — Next disposes the step and cancels it:\n$body",
            Regex("""(?<![\w.])scope\.launch\s*\{[^}]*refreshModels""").containsMatchIn(body),
        )
        assertTrue(
            "the refresh must be launched on MinisApp's application scope:\n$body",
            Regex("""appScope\.applicationScope|\(appScope \?: scope\)\.launch""").containsMatchIn(body),
        )
        assertTrue(
            "a null app scope must be reported instead of silently degrading:\n$body",
            body.contains("AppLogger.warning("),
        )
    }

    @Test
    fun `the onboarding save still writes off the main thread behind the double-tap guard`() {
        val body = declarationSlice(readRepoFile(ONBOARDING), "private fun ApiKeyStep(")
        assertTrue(
            "the deep-audit-1033 I05 contract: addInstance + saveApiKey stay inside " +
                "withContext(Dispatchers.IO):\n$body",
            body.contains("withContext(Dispatchers.IO)"),
        )
        assertTrue("the double-tap guard must survive:\n$body", body.contains("!saving"))
        assertTrue("saved must still gate Next:\n$body", body.contains("enabled = apiKey.isNotBlank() && !saved && !saving"))
        assertTrue("the skip path must survive:\n$body", body.contains("onSkip"))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Locale tags declared by `res/xml/locales_config.xml`, in file order. */
    private fun registeredLocales(): List<String> =
        readRepoFile(LOCALES_CONFIG).lines().mapNotNull { line ->
            // Plain string scan, not a regex: a raw-string literal that ends in a
            // quote (`…"([^"]+)"` → `""""`) silently swallows the closing quote
            // into the pattern and matches nothing. That exact slip is why this
            // method is the only place the tags are parsed.
            if (!line.contains("<locale")) return@mapNotNull null
            val marker = "android:name=\""
            val at = line.indexOf(marker)
            if (at < 0) null else line.substring(at + marker.length).substringBefore('"')
        }

    /** `zh-TW` -> `values-zh-rTW`, `de` -> `values-de`, `en` -> the default `values/`. */
    private fun resourceDirFor(tag: String): String {
        val parts = tag.split("-")
        return if (parts.size == 1) {
            if (parts[0] == "en") "values" else "values-${parts[0]}"
        } else {
            "values-${parts[0]}-r${parts.drop(1).joinToString("-r")}"
        }
    }

    /** The `KEY_LANGUAGE -> …` arm of the prefs-change listener. */
    private fun languageListenerBranch(): String {
        val body = readRepoFile(APPEARANCE)
        val start = body.indexOf("KEY_LANGUAGE ->")
        assertTrue("the KEY_LANGUAGE listener arm is gone — nothing would refresh the language", start >= 0)
        // The arm runs to the closing of its `when` arm: the next same-or-shallower
        // indented `KEY_` label (the arms are alphabetical in this file) or the end
        // of the listener lambda.
        val rest = body.substring(start)
        val end = rest.indexOf("\n            }", 1).let { if (it < 0) rest.length else it }
        return rest.substring(0, end)
    }

    /** Body of the shared apply function; the extraction itself is part of the fix. */
    private fun applyFunctionBody(): String =
        declarationSlice(readRepoFile(APPEARANCE), "internal fun applyLanguageChange(")

    private fun readRepoDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, APPEARANCE_FROM_ROOT).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * Source lines of the declaration at [marker].
     *
     * Ends at the first non-blank line back at (or above) the declaration's own
     * indent **while both the parameter list's parens and the body's braces are
     * balanced** — the same rule as [com.rikkaminis.app.sandbox.SandboxPathGuardWiringTest],
     * because the simpler cuts stop inside a multi-line signature and hide the
     * very lines these assertions check.
     */
    private fun declarationSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val indent = lines[start].takeWhile { it == ' ' }.length
        var paren = 0
        var brace = 0
        var end = start
        var k = start
        while (k < lines.size) {
            val s = stripStrings(lines[k])
            paren += s.count { it == '(' } - s.count { it == ')' }
            brace += s.count { it == '{' } - s.count { it == '}' }
            end = k
            k++
            var peek = k
            while (peek < lines.size && lines[peek].isBlank()) peek++
            if (peek >= lines.size) { end = lines.size - 1; break }
            if (paren <= 0 && brace <= 0 && lines[peek].takeWhile { it == ' ' }.length <= indent) break
        }
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /** Drops quoted spans so braces inside string literals do not unbalance the count. */
    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"') quote = c
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        private const val ANDROID_MAIN =
            "src/android/app/src/main/java/com/rikkaminis/app"
        private const val APPEARANCE = "$ANDROID_MAIN/ui/settings/AppearanceScreen.kt"
        private const val ONBOARDING = "$ANDROID_MAIN/ui/onboarding/OnboardingScreen.kt"
        private const val LOCALES_CONFIG = "src/android/app/src/main/res/xml/locales_config.xml"
        private const val RES_DIR = "src/android/app/src/main/res"
        // Only used to locate the repo root from a possibly-arbitrary cwd.
        private const val APPEARANCE_FROM_ROOT = APPEARANCE
    }
}
