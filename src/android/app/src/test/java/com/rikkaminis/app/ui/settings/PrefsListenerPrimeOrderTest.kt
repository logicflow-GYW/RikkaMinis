package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv17-B07] Prime before the cached read, inside the same listener.
 *
 * `compactModelEntryId()` returns the `@Volatile` snapshot (`AgentRuntimeLimitsPrefs.kt:510`);
 * the write that triggered the callback landed in SharedPreferences, but the
 * snapshot only catches up when some listener primes. SharedPreferencesImpl
 * dispatches the registered listeners from a WeakHashMap in unspecified order,
 * so this screen's listener can run BEFORE `AgentRuntimeLimitsPrefs`' own
 * self-sync listener — without its own prime the read publishes the pre-write
 * value, and because the screen's Save re-persists every knob it shows, the
 * external write would be silently rolled back. The prime must therefore sit
 * BEFORE the read in the listener body (the belt `RuntimeLimitsScreen` already
 * wears).
 *
 * The screen is a `@Composable` and `SharedPreferencesImpl` is real-Android
 * (no in-sandbox substitute), so this is a SHAPE test on the real shipped
 * source — the house pattern of `ProviderEntryKeyAndPinTest`. Every
 * expectation is a literal; the listener body is extracted by balanced braces
 * (not `indexOf` with a starting offset — that shape was the 1037-B false red)
 * so a mutation that deletes the prime line, moves it after the read, or
 * inlines the listener turns this red.
 */
class PrefsListenerPrimeOrderTest {

    private val screen =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelGroupsScreen.kt"

    private val limitsScreen =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/RuntimeLimitsScreen.kt"

    private val tuningScreen =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ChatTuningScreen.kt"

    @Test
    fun `the compact-pin listener primes before reading the cache (dv17-B07)`() {
        val src = readRepoFile(screen)
        val rest = src.substringAfter(
            "val listener = SharedPreferences.OnSharedPreferenceChangeListener",
        )
        val open = rest.indexOf('{')
        require(open >= 0) { "the listener lambda lost its body" }
        var depth = 0
        var close = -1
        for (j in open until rest.length) {
            if (rest[j] == '{') depth++
            if (rest[j] == '}') {
                depth--
                if (depth == 0) {
                    close = j
                    break
                }
            }
        }
        require(close >= 0) { "the listener lambda body is unbalanced" }
        val body = rest.substring(open, close + 1)
        val read = body.indexOf("AgentRuntimeLimitsPrefs.compactModelEntryId()")
        val prime = body.indexOf("AgentRuntimeLimitsPrefs.prime(")
        assertTrue("the listener must still re-read the cached pin", read >= 0)
        assertTrue(
            "the listener must prime the cache before the cached read (dv17-B07)",
            prime in 0 until read,
        )
    }

    /**
     * [dv22-B1] AOSP `SharedPreferencesImpl.apply()` notifies registered
     * listeners SYNCHRONOUSLY on the main thread (the callback runs inline
     * before `apply()` returns). `RuntimeLimitsScreen.Save` writes three
     * prefs files in one click, so the FIRST write's callback re-reads disk
     * values into all 40 state vars before the second and third write calls
     * evaluate their arguments. Two independent shape contracts close that:
     *
     *  1. the listener re-reads ONLY the control the changed key owns (a
     *     `when (key)` arm per control — the AppearanceScreen shape), never
     *     the whole group, so neither an external single-key write nor the
     *     screen's own Save can clobber the other 39 edit buffers;
     *  2. every value Save persists is captured into a local `pending...`
     *     val BEFORE the first write, so the write arguments are immune to
     *     the mid-Save listener reset.
     *
     * `ChatTuningScreen` is the same family: its listener used to re-read the
     * whole 7-buffer group under one `key in ALL_KEYS` membership test, so an
     * external single-key write cleared every unsaved edit at once.
     *
     * Shape tests with literal expectations, per the dv17-B07 note above; the
     * runtime behavior claim (synchronous notify) is AOSP
     * `SharedPreferencesImpl.notifyListeners`, guarded structurally by
     * `scripts/scan/prefs_listener_holder_guard.py` criteria C8/C8b/C9.
     */
    @Test
    fun `the runtime-limits listener re-reads only the key that changed (dv22-B1)`() {
        val body = listenerBody(readRepoFile(limitsScreen))
        assertTrue(
            "the listener must decide by key (dv22-B1)",
            Regex("""\bwhen\s*\(\s*key\s*\)\s*\{""").containsMatchIn(body),
        )
        val arm = Regex(
            """AgentRuntimeLimitsPrefs\.KEY_GUEST_TMP_MAX_AGE_MIN\s*->\s*\n\s*guestTmpMaxAgeMin\s*=\s*AgentRuntimeLimitsPrefs\.guestTmpMaxAgeMin\(\)""",
        ).find(body)
        assertTrue(
            "the guestTmpMaxAgeMin arm must re-read exactly that one knob",
            arm != null,
        )
        assertTrue(
            "no arm may re-read the whole group: the pre-fix listener reset every state on any key",
            !Regex(
                """\n\s*maxTurns\s*=\s*AgentRuntimeLimitsPrefs\.maxTurns\(\)\s*\n\s*maxProviderAttempts\s*=""",
            ).containsMatchIn(body),
        )
        val armCount = Regex("""->[ \t]*\n""").findAll(body).count() - 1 // minus the lambda header
        assertTrue(
            "all 40 controls must have their own arm ($armCount arms)",
            armCount >= 40,
        )
    }

    @Test
    fun `the runtime-limits Save captures every pending value before the first write (dv22-B1)`() {
        val onClick = balancedBlock(readRepoFile(limitsScreen), "onClick = {")
        val firstWrite = onClick.indexOf("SubagentPrefs.setEnabled(")
        assertTrue("the Save onClick must still start with the subagent write", firstWrite >= 0)
        for (capture in listOf(
            "val pendingSubagentEnabled = subagentEnabled",
            "val pendingMaxSessions = maxSessions",
            "val pendingMaxTurns = maxTurns",
            "val pendingGuestTmpMaxAgeMin = guestTmpMaxAgeMin",
            "val pendingHeavyGateTimeoutSec = heavyGateTimeoutSec",
        )) {
            val at = onClick.indexOf(capture)
            assertTrue(
                "$capture must be captured BEFORE the first write (dv22-B1)",
                at in 0 until firstWrite,
            )
        }
        assertTrue(
            "the batched save must take the snapshot, not the state vars",
            "maxTurns = pendingMaxTurns," in onClick,
        )
        assertTrue(
            "reading the state vars at write time is the mid-Save rollback",
            "maxTurns = maxTurns," !in onClick,
        )
    }

    @Test
    fun `the chat-tuning listener re-reads only the key that changed (dv22-B1)`() {
        val body = listenerBody(readRepoFile(tuningScreen))
        assertTrue(
            "the chat-tuning listener must decide by key (dv22-B1)",
            Regex("""\bwhen\s*\(\s*key\s*\)\s*\{""").containsMatchIn(body),
        )
        assertTrue(
            "the scrollNearBottomDp arm must re-read exactly that one buffer",
            Regex(
                """ChatTuningPrefs\.KEY_SCROLL_NEAR_BOTTOM_DP\s*->\s*\n\s*scrollNearBottomDp\s*=\s*ChatTuningPrefs\.scrollNearBottomDp\(context\)""",
            ).containsMatchIn(body),
        )
        assertTrue(
            "the whole-group re-read (`key in ALL_KEYS`) is the cleared-buffer defect",
            !Regex("""key\s+in\s+ChatTuningPrefs\.ALL_KEYS""").containsMatchIn(body),
        )
    }

    /** First `OnSharedPreferenceChangeListener` lambda body, balanced braces. */
    private fun listenerBody(src: String): String {
        val rest = src.substringAfter(
            "OnSharedPreferenceChangeListener",
        )
        return balancedBlock(rest, "{")
    }

    /** The balanced `{ ... }` block starting at/after [marker]. */
    private fun balancedBlock(src: String, marker: String): String {
        val open0 = src.indexOf(marker) + marker.indexOf('{')
        require(open0 >= 0) { "no block found for $marker" }
        var depth = 0
        var close = -1
        for (j in open0 until src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) {
                    close = j
                    break
                }
            }
        }
        require(close >= 0) { "the block for $marker is unbalanced" }
        return src.substring(open0, close + 1)
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
     */
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
}
