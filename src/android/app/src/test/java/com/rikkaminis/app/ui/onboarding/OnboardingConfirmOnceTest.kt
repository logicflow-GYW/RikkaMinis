package com.rikkaminis.app.ui.onboarding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-V F-13] The onboarding "finish" button must write the default model group
 * ONCE.
 *
 * `providerRepository.addGroup(ModelGroup(name = defaultGroupName))` mints a
 * fresh UUID per call, so a second invocation does not update the first group —
 * it adds a second, identically named one, and every later launch shows two
 * "Default Models" groups. `enabled = selected.isNotEmpty()` is not a guard:
 * the tap handler runs before recomposition can disable anything, and the nav
 * transition keeps the button alive for the frames in between (the same defect
 * class [deep-audit-1033 I05] fixed for the API-key save with `saving`).
 *
 * Both pickers had it (the model-selection screen and the first-run step), so
 * both are pinned here. The property that matters is ORDER: the flag must be
 * set before the non-idempotent write, otherwise a re-entrant tap still gets
 * through.
 *
 * Why a shape test rather than a behavioural one: the state is Compose
 * `mutableStateOf` inside a composable — the cheap, honest device for a
 * three-line re-entrancy flag is to pin the ordering in both files. What a
 * behavioural test would need (a fake repository + a composition) would cost
 * more than the defect it guards.
 */
class OnboardingConfirmOnceTest {

    private val modelSelection =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/onboarding/OnboardingModelSelectionScreen.kt"
    private val onboarding =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/onboarding/OnboardingScreen.kt"

    @Test
    fun `both pickers declare the one-shot flag and gate the handler on it`() {
        for (path in listOf(modelSelection, onboarding)) {
            val src = readRepoFile(path)
            assertTrue(
                "$path: the one-shot flag is gone — a double tap adds a second group",
                src.contains("var submitted by remember { mutableStateOf(false) }"),
            )
            assertTrue(
                "$path: the click handler must be gated on !submitted",
                src.contains("if (!submitted) {"),
            )
            assertTrue(
                "$path: the flag must also disable the button once fired",
                src.contains("enabled = selected.isNotEmpty() && !submitted"),
            )
        }
    }

    @Test
    fun `the flag is set before the non-idempotent write`() {
        for (path in listOf(modelSelection, onboarding)) {
            val src = readRepoFile(path)
            val flag = src.indexOf("submitted = true")
            val write = src.indexOf("providerRepository.addGroup(")
            assertTrue("$path: no addGroup write found", write > 0)
            assertTrue("$path: no `submitted = true` found", flag > 0)
            assertTrue(
                "$path: `submitted = true` must precede addGroup() — otherwise a re-entrant tap still writes",
                flag < write,
            )
        }
    }

    @Test
    fun `each picker has exactly one group-write site`() {
        // A second, unguarded addGroup() would silently re-introduce the
        // duplicate-group defect next to the guarded one.
        for (path in listOf(modelSelection, onboarding)) {
            val src = readRepoFile(path)
            assertEquals(
                "$path: more than one addGroup() call site",
                1,
                Regex(Regex.escape("providerRepository.addGroup(")).findAll(src).count(),
            )
        }
    }

    // ── helper ───────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
