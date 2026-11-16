package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.ChatTuningPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [composer-glass-1115] The frosted-glass composer is a five-link chain, and
 * every link fails SILENTLY when it is unwired:
 *
 *   1. `rememberHazeState()` in ChatScreen — one state shared by both ends.
 *   2. `hazeSource(hazeState)` on the message list — the only thing that records
 *      pixels for the card to blur.
 *   3. `hazeEffect(state = …, style = …)` on the card, INSIDE its rounded clip.
 *   4. the list's drawing extent (`composerArcTail`) and its bottom padding must
 *      move together, or the last row slides behind the card.
 *   5. the Chat Tuning switch must gate the recording and the drawing extent: a
 *      switch that only greys out the card while the list still records is a
 *      per-frame cost for nothing.
 *
 * Break any link and the app still compiles, still runs and still renders a
 * card — it just isn't glass. That is exactly the class of defect this codebase
 * pins with source-shape tests instead of unit tests (same approach as
 * `ShareDirPruneWiringTest`): the real behaviour needs a device + a GPU, the
 * WIRING does not.
 *
 * [composer-glass-1116] This file used to carry "re-adding the arc clip is a
 * flat tint" as its load-bearing assertion. That premise was wrong — haze
 * records the source node's own subtree and clips at composite time — and the
 * clip is now what keeps the rows from being painted sharp beside and below the
 * card. See `the arc clip runs in both modes with the depth of the list tail`.
 */
class ComposerGlassWiringTest {

    private val screenSrc: String by lazy { readRepoFile("ChatScreen.kt") }

    private val composerSrc: String by lazy { readRepoFile("ChatInputArea.kt") }

    /** The switch's home since the round-3 move: Appearance → THEME section. */
    private val appearanceSrc: String by lazy { readRepoFile("AppearanceScreen.kt") }

    /** Where the two numbers are edited (and where the Save button now lives). */
    private val tuningSrc: String by lazy { readRepoFile("ChatTuningScreen.kt") }

    /** Whitespace-collapsed view: these chains wrap across lines by design. */
    private val screenFlat: String by lazy { screenSrc.replace(Regex("\\s+"), " ") }

    private val composerFlat: String by lazy { composerSrc.replace(Regex("\\s+"), " ") }

    private val appearanceFlat: String by lazy { appearanceSrc.replace(Regex("\\s+"), " ") }

    private val tuningFlat: String by lazy { tuningSrc.replace(Regex("\\s+"), " ") }

    @Test
    fun `the screen owns exactly one haze state and hands it to the composer`() {
        assertTrue(
            "the card samples the state the list records into — two states means the card blurs nothing",
            screenFlat.contains("val hazeState = rememberHazeState()"),
        )
        assertTrue(
            "the composer must receive that same state by name",
            screenFlat.contains("hazeState = hazeState,"),
        )
    }

    @Test
    fun `the message list records into that state, and only while the effect is on`() {
        assertTrue(
            "without hazeSource the recording is empty and the card has nothing to blur",
            screenFlat.contains("if (composerGlassOn) Modifier.hazeSource(hazeState) else Modifier"),
        )
    }

    @Test
    fun `the card draws the effect and keeps a flat fallback for API below 31`() {
        assertTrue(
            "the card is opaque without the effect",
            composerFlat.contains("hazeEffect(state = hazeState, style = composerGlassStyle)"),
        )
        assertTrue(
            "blur needs RenderEffect (Android 12+); the fallback keeps the card readable below it",
            composerFlat.contains("fallbackTint = HazeTint(inputBgColor)"),
        )
    }

    @Test
    fun `the effect sits inside the rounded clip and carries the hairline border`() {
        val clip = composerSrc.indexOf(".clip(RoundedCornerShape(ComposerCardCornerRadius))")
        val effect = composerSrc.indexOf(".hazeEffect(state = hazeState")
        assertTrue("card clip not found", clip >= 0)
        assertTrue("card effect not found", effect >= 0)
        assertTrue(
            "clip must come FIRST: applied after the effect it would not touch the drawn blur, " +
                "and the blur would square off the card's corners",
            clip < effect,
        )
        assertTrue(
            "without an edge the blur reads as a haze fading into the page, not as a surface",
            composerFlat.contains("border( 0.5.dp, ChatColors.inputBorder,"),
        )
    }

    /**
     * [composer-glass-1116] The arc clip is now in BOTH modes; only its depth
     * follows the tail.
     *
     * The pre-1116 shape asserted the opposite ("an unconditional clip would
     * make the effect look dead"). That was a guess about haze's recording
     * order, and it was wrong: `HazeSourceNode.draw` copies its own subtree
     * into a graphics layer (`contentLayer.record { drawContentSafely() }`) and
     * an ancestor's clip is applied later, at composite time — so the card
     * still samples the rows the outline hides. Re-adding the clip is what
     * stops those rows being painted sharp beside and below the card.
     *
     * What this test now guards: the outline must be the inverse of the tail
     * (same depth) and must be remembered, otherwise either the effect dies
     * (clip deeper than the tail) or the rows leak (no clip at all).
     */
    @Test
    fun `the arc clip runs in both modes with the depth of the list tail`() {
        assertTrue(
            "the list must be clipped unconditionally — glass ON still draws the whole composer " +
                "height past its viewport, and those rows may only be seen through the card",
            screenFlat.contains(".composerArcTail( overlap = if (composerGlassOn) composerHeightDp else ComposerArcOverlap, )")
                && screenFlat.contains(".clip(composerArcClip)"),
        )
        assertTrue(
            "the glass branch must build its outline with the SAME depth the tail just used",
            screenFlat.contains("else -> composerGlassArcClipShape(composerHeightDp)"),
        )
        assertTrue(
            "glass OFF must fall back to the shared 22dp instance",
            screenFlat.contains("!composerGlassOn -> composerArcClipShape"),
        )
        assertTrue(
            "the un-measured frame must clip NOTHING — the list was handed zero overlap, so any " +
                "band there would cut a viewport that does not overflow",
            screenFlat.contains("composerHeightPx <= 0 -> RectangleShape"),
        )
        assertTrue(
            "the outline must be remembered on the measured height — a fresh Shape per " +
                "recomposition invalidates the clip layer every frame, and a stale one keeps " +
                "the old depth after the composer grows",
            screenFlat.contains("val composerArcClip = remember(composerGlassOn, composerHeightPx)"),
        )
        assertFalse(
            "the conditional-clip branch must not come back: skipping the clip under glass is " +
                "what painted message rows straight through the card",
            screenFlat.contains("if (composerGlassOn) Modifier else Modifier.clip("),
        )
    }

    @Test
    fun `the measured composer height drives the drawing extent and the bottom padding together`() {
        assertTrue(
            "measurement is what makes the extent track a growing composer",
            screenFlat.contains("onSizeChanged { composerHeightPx = it.height }"),
        )
        assertTrue(
            "the list must be allowed to draw the full composer height under the card",
            screenFlat.contains("if (composerGlassOn) composerHeightDp else ComposerArcOverlap"),
        )
        assertTrue(
            "the viewport grew by composerHeightDp, so the clearance must grow by the same value — " +
                "otherwise the last row slides down behind the card. " +
                "[fix/follow-exit-inset-1116] the clearance is now ONE expression that feeds both " +
                "the content padding and the bottom verdict, so the two cannot drift apart",
            screenFlat.contains("val listBottomPaddingDp =") &&
                screenFlat.contains("(if (bottomReserve == 0.dp) 12.dp else bottomReserve) +") &&
                screenFlat.contains("bottom = listBottomPaddingDp,"),
        )
        assertTrue(
            "the same clearance must reach the bottom verdict, read back from the list " +
                "rather than mirrored into a second state",
            screenFlat.contains("contentBottomInsetPx = info.afterContentPadding,"),
        )
    }

    @Test
    fun `both knobs reach the card from Chat Tuning`() {
        assertTrue(
            "blur radius plumbing",
            screenFlat.contains("composerGlassBlurDp = tuning.composerGlassBlurDp,"),
        )
        assertTrue(
            "density plumbing",
            screenFlat.contains("composerGlassTintPct = tuning.composerGlassTintPct,"),
        )
        assertTrue(
            "the switch itself",
            screenFlat.contains("composerGlassEnabled = composerGlassOn,"),
        )
        assertTrue(
            "the composer must convert both numbers through the clamped helpers, not raw",
            composerFlat.contains("ComposerGlass.tintAlpha(composerGlassTintPct)"),
        )
        assertTrue(
            "the composer must convert both numbers through the clamped helpers, not raw",
            composerFlat.contains("ComposerGlass.blurDp(composerGlassBlurDp)"),
        )
    }

    @Test
    fun `the default is OFF, like rikkahub`() {
        assertEquals(
            "a default-on effect would bill every user for a per-frame list recording",
            false,
            ChatTuningPrefs.COMPOSER_GLASS_ENABLED_DEFAULT,
        )
        assertTrue(
            "the switch must exist in the tuning store the composer reads",
            screenFlat.contains("val composerGlassOn = tuning.composerGlassEnabled"),
        )
    }

    @Test
    fun `the master switch lives in the Appearance THEME section, not in the tuning page`() {
        assertTrue(
            "the switch belongs with the theme rows — same kind of decision, and the section already owns the palette",
            appearanceFlat.contains("ChatTuningPrefs.prefs(context).edit() .putBoolean(ChatTuningPrefs.KEY_COMPOSER_GLASS_ENABLED,"),
        )
        val themeSection = appearanceSrc.indexOf("R.string.appearance_section_theme")
        val launchSection = appearanceSrc.indexOf("R.string.appearance_section_launch")
        // lastIndexOf: the listener near the top names the key too; the WRITE is the
        // occurrence inside the theme section.
        val switchRow = appearanceSrc.lastIndexOf("ChatTuningPrefs.KEY_COMPOSER_GLASS_ENABLED")
        assertTrue("theme / launch section markers not found", themeSection >= 0 && launchSection > themeSection)
        assertTrue("the switch row must sit between the theme rows and the Launch Session section", switchRow > themeSection && switchRow < launchSection)
        assertFalse(
            "a second switch on the tuning page would let the two disagree about who owns the value",
            tuningFlat.contains("composerGlassEnabled = it"),
        )
        assertTrue(
            "the numbers stay on the tuning page, but the page must say where the switch went",
            tuningFlat.contains("R.string.chat_tuning_glass_footer"),
        )
        assertTrue(
            "…and the tuning page must NEVER write the switch key: live-apply writes exactly the knob being edited, and Appearance owns its only writer",
            !tuningSrc.contains("KEY_COMPOSER_GLASS_ENABLED"),
        )
        // Its own card, not a row inside the theme card: a Switch among three
        // radio rows reads as a fourth theme option.
        assertTrue(
            "the switch needs its own SettingsSection with a header and a footer",
            appearanceFlat.contains(
                "SettingsSection( header = stringResource(R.string.appearance_section_glass), " +
                    "footer = stringResource(R.string.appearance_glass_footer), ) {",
            ),
        )
        // Title only in the row; every explanation goes to the card footer.
        val rowStart = appearanceSrc.indexOf("title = stringResource(R.string.appearance_glass_title)")
        val rowEnd = appearanceSrc.indexOf("showDivider = false", rowStart)
        assertTrue("glass switch row not found", rowStart >= 0 && rowEnd > rowStart)
        assertFalse(
            "the switch row must stay a bare title — explanations belong to the card footer",
            appearanceSrc.substring(rowStart, rowEnd).contains("subtitle ="),
        )
    }

    @Test
    fun `the tuning page has no Save and every slider writes its own key on release`() {
        // [live-apply-1116] The screen used to edit a nine-field draft and
        // persist it from a top-bar Save. The decision now is "one commit =
        // one key", so this test anchors exactly that: no Save affordance at
        // all, and one single-key write per slider row.
        assertFalse(
            "the Save action is gone — a residual common_save would be a dead button",
            tuningSrc.contains("common_save"),
        )
        assertEquals(
            "no Save affordance may remain on the page",
            0,
            Regex("MinisTextButton\\(").findAll(tuningSrc).count(),
        )
        assertEquals(
            "every slider must persist on release, through the single-key writer",
            9,
            Regex(
                "onCommitFinished = \\{ ChatTuningPrefs\\.setInt\\(context, ChatTuningPrefs\\.KEY_",
            ).findAll(tuningSrc).count(),
        )
        assertTrue(
            "the page must state the rule where the user lands",
            tuningFlat.contains("LimitsSectionFooter(stringResource(R.string.settings_live_apply_hint))"),
        )
    }

    @Test
    fun `the top bar shares the state, extends the list upward and gates the glass on scroll`() {
        assertTrue(
            "the bar must sample the SAME recording as the card",
            screenFlat.contains("hazeEffect(state = hazeState, style = topBarGlassStyle)"),
        )
        assertTrue(
            "without the upward extension nothing is behind the bar — the glass would blur air",
            screenFlat.contains(".topBarTail(if (composerGlassOn) topBarHeightDp else 0.dp)"),
        )
        assertTrue(
            "the viewport grew upward by the bar height, so the top padding must grow by the same value or every row shifts up",
            screenFlat.contains("top = 4.dp + if (composerGlassOn) topBarHeightDp else 0.dp,"),
        )
        assertTrue(
            "at scroll 0 there is nothing to blur, so the glass fades in only once the list can scroll up",
            // [incr1116-B F-11] Match the GATE, not the string: `canScrollBackward`
            // already appears five times elsewhere in ChatScreen (other scroll
            // logic), so `contains(...)` stayed true even with this gate deleted
            // or hard-wired to `true`. The whole declaration is the anchor.
            screenFlat.contains(
                "val topBarScrolled by remember { derivedStateOf { listState.canScrollBackward } }",
            ),
        )
        assertTrue(
            "the fading layer must read that gate, not a constant",
            screenFlat.contains("targetValue = if (composerGlassOn && topBarScrolled) 1f else 0f,"),
        )
        assertTrue(
            "the bar's own density margin comes from the shared helper, not from a second knob",
            screenFlat.contains("ComposerGlass.topBarTintAlpha(tuning.composerGlassTintPct)"),
        )
        assertTrue(
            "glass on -> the bar paints nothing itself; the haze layer is its surface",
            screenFlat.contains("containerColor = if (composerGlassOn) Color.Transparent"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readRepoFile(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (base in listOf(
                "src/android/app/src/main/java/com/rikkaminis/app",
                "app/src/main/java/com/rikkaminis/app",
            )) {
                for (pkg in listOf("ui/chat", "ui/settings")) {
                    val hit = File(dir, "$base/$pkg/$name")
                    if (hit.isFile) return hit.readText()
                }
            }
            dir = dir.parentFile
        }
        error("$name not found from user.dir=${System.getProperty("user.dir")}")
    }
}
