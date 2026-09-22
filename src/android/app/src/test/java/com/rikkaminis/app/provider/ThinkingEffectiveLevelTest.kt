package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-effective-level] Exhaustive coverage of the ONE rule every
 * thinking-level consumer now derives from.
 *
 * Regression context (user report 2026-09-22: "调到最高，但会出现关了的情况").
 * Before this rule existed, three layers answered "what level is in force?"
 * independently and could disagree:
 *   • the navbar badge read the RAW user choice;
 *   • AgentLoopEngine re-applied `if (supportsReasoning) … else OFF`;
 *   • the provider layer clamped to the model ceiling.
 * A group rotation onto a non-reasoning member produced a badge claiming
 * "High" while the wire carried OFF, a level sheet with no row ticked, and a
 * picker whose taps were swallowed. These tests pin the single answer.
 *
 * Pure JVM — no Android, no provider, no state — so the whole
 * (requested × supportsReasoning × ceiling) space is enumerable here. That is
 * the point: the bug was only reachable through Compose rendering, which the
 * sandbox cannot compile, so the rule was extracted to be testable.
 */
class ThinkingEffectiveLevelTest {

    // Enum declaration order == intensity rank; keep in sync with ThinkingLevel.
    private val all = ThinkingLevel.entries.toList()

    // ── effectiveThinkingLevel ────────────────────────────────────────────

    @Test
    fun `non-reasoning model forces OFF regardless of the stored choice`() {
        for (requested in all) {
            for (ceiling in all) {
                assertEquals(
                    "requested=$requested ceiling=$ceiling",
                    ThinkingLevel.OFF,
                    effectiveThinkingLevel(requested, supportsReasoning = false, ceiling = ceiling),
                )
            }
        }
    }

    @Test
    fun `AUTO is never clamped`() {
        // AUTO = "let the vendor decide"; its appended rank (8) is an artifact
        // of the append-only enum rule, not an intensity.
        for (ceiling in all) {
            assertEquals(
                "ceiling=$ceiling",
                ThinkingLevel.AUTO,
                effectiveThinkingLevel(ThinkingLevel.AUTO, supportsReasoning = true, ceiling),
            )
        }
    }

    @Test
    fun `a request at or below the ceiling passes through unchanged`() {
        for (ceiling in all) {
            for (requested in all) {
                if (requested == ThinkingLevel.AUTO) continue
                if (requested.rank <= ceiling.rank) {
                    assertEquals(
                        "requested=$requested ceiling=$ceiling",
                        requested,
                        effectiveThinkingLevel(requested, supportsReasoning = true, ceiling),
                    )
                }
            }
        }
    }

    @Test
    fun `a request above the ceiling clamps down to exactly the ceiling`() {
        for (ceiling in all) {
            for (requested in all) {
                if (requested == ThinkingLevel.AUTO) continue
                if (requested.rank > ceiling.rank) {
                    assertEquals(
                        "requested=$requested ceiling=$ceiling",
                        ceiling,
                        effectiveThinkingLevel(requested, supportsReasoning = true, ceiling),
                    )
                }
            }
        }
    }

    /**
     * The invariant that makes the picker self-consistent. The picker only
     * renders when the model supports reasoning, and in that domain the
     * effective level is `min(requested, ceiling)` — therefore it is ALWAYS in
     * `availableLevels`, so exactly one capsule can match it and the row can
     * never render "entirely unselected" (the state the user read as "off").
     */
    @Test
    fun `effective level is always offerable by the picker`() {
        for (ceiling in all) {
            for (requested in all) {
                val effective = effectiveThinkingLevel(requested, supportsReasoning = true, ceiling)
                val offerable = availableLevels(ceiling)
                assertTrue(
                    "effective=$effective not offerable for ceiling=$ceiling (requested=$requested)",
                    effective == ThinkingLevel.OFF || effective in offerable,
                )
            }
        }
    }

    @Test
    fun `result is idempotent - re-deriving an effective level changes nothing`() {
        for (ceiling in all) {
            for (requested in all) {
                val once = effectiveThinkingLevel(requested, supportsReasoning = true, ceiling)
                val twice = effectiveThinkingLevel(once, supportsReasoning = true, ceiling)
                assertEquals("requested=$requested ceiling=$ceiling", once, twice)
            }
        }
    }

    // ── isCappedBy (drives the picker's orange up-arrow) ──────────────────

    @Test
    fun `AUTO never counts as capped even though its rank exceeds every ceiling`() {
        // The B2 bug: AUTO's rank is 8, the highest, so a naive rank compare
        // flagged it as "above the ceiling" and the picker drew the orange
        // up-arrow on AUTO *and* on a real tier — two capsules looking selected.
        for (ceiling in all) {
            assertFalse(
                "ceiling=$ceiling",
                isCappedBy(ThinkingLevel.AUTO, ceiling),
            )
        }
    }

    @Test
    fun `OFF never counts as capped`() {
        for (ceiling in all) {
            assertFalse("ceiling=$ceiling", isCappedBy(ThinkingLevel.OFF, ceiling))
        }
    }

    @Test
    fun `a real tier above the ceiling is capped, at or below is not`() {
        for (ceiling in all) {
            for (requested in all) {
                if (requested == ThinkingLevel.AUTO || requested == ThinkingLevel.OFF) continue
                val expected = requested.rank > ceiling.rank
                assertEquals(
                    "requested=$requested ceiling=$ceiling",
                    expected,
                    isCappedBy(requested, ceiling),
                )
            }
        }
    }

    /**
     * The cue and the clamp must agree: the picker draws the orange up-arrow
     * exactly when the stored choice is being silently lowered. If these two
     * ever disagree the UI either hides a real clamp or advertises a fake one.
     */
    @Test
    fun `capped cue agrees with an actual clamp`() {
        for (ceiling in all) {
            for (requested in all) {
                if (requested == ThinkingLevel.AUTO) continue
                val effective = effectiveThinkingLevel(requested, supportsReasoning = true, ceiling)
                val actuallyLowered = effective != requested
                assertEquals(
                    "requested=$requested ceiling=$ceiling effective=$effective",
                    actuallyLowered,
                    isCappedBy(requested, ceiling),
                )
            }
        }
    }

    // ── the reported regression, stated as a scenario ─────────────────────

    @Test
    fun `regression - group rotation onto a non-reasoning member shows OFF everywhere`() {
        // Group member A: reasons, ceiling HIGH. User picks HIGH.
        // Group member B: does NOT reason. Rotation lands on B.
        val stored = ThinkingLevel.HIGH
        val onB = effectiveThinkingLevel(stored, supportsReasoning = false, ceiling = ThinkingLevel.OFF)

        // Every surface must agree with the wire, not with the stored choice.
        assertEquals("wire", ThinkingLevel.OFF, onB)
        // The badge reads `thinkingLevel`, i.e. the effective level → "Off",
        // NOT the old lie of "High".
        assertEquals("badge", ThinkingLevel.OFF, onB)
        // The level sheet ticks OFF (`!currentLevel.isEnabled`) → exactly one row.
        assertFalse("sheet ticks OFF", onB.isEnabled)
        // And the picker is not even rendered for a non-reasoning model, so no
        // capsule can be tapped into a silent no-op.
        assertEquals(
            "picker domain",
            ThinkingLevel.OFF,
            effectiveThinkingLevel(stored, supportsReasoning = false, ceiling = ThinkingLevel.OFF),
        )
    }

    @Test
    fun `regression - rotation onto a lower-ceiling member shows the clamped level`() {
        // Member A ceiling MAX (user picked MAX), member B ceiling HIGH.
        val stored = ThinkingLevel.MAX
        val onB = effectiveThinkingLevel(stored, supportsReasoning = true, ceiling = ThinkingLevel.HIGH)

        // Was: raw MAX isn't in B's availableLevels → row rendered with nothing
        // highlighted, i.e. "thinking looks off".
        assertEquals("effective", ThinkingLevel.HIGH, onB)
        assertTrue("highlightable", onB in availableLevels(ThinkingLevel.HIGH))
        // The stored choice is still MAX, so the up-arrow cue is legitimate.
        assertTrue("cue shown", isCappedBy(stored, ThinkingLevel.HIGH))
    }

    @Test
    fun `regression - AUTO on a capped model highlights AUTO only`() {
        for (ceiling in all) {
            if (ceiling == ThinkingLevel.OFF) continue
            val effective = effectiveThinkingLevel(ThinkingLevel.AUTO, supportsReasoning = true, ceiling)
            assertEquals("ceiling=$ceiling", ThinkingLevel.AUTO, effective)
            // No clamp cue at all → exactly one highlighted capsule (AUTO).
            assertFalse("ceiling=$ceiling", isCappedBy(ThinkingLevel.AUTO, ceiling))
        }
    }

    // ── thinkingTapTarget (drives what a capsule tap DOES) ───────────────

    @Test
    fun `tap rule - the stored choice toggles off, any other capsule selects`() {
        for (requested in all) {
            for (level in all) {
                val expected = if (level == requested) ThinkingLevel.OFF else level
                assertEquals(
                    "level=$level requested=$requested",
                    expected,
                    thinkingTapTarget(level, requested),
                )
            }
        }
    }

    /**
     * The reported symptom, as an assertion. Stored MAX, the bound model caps
     * at HIGH → the picker draws the orange up-arrow on HIGH. Tapping HIGH asks
     * for "this model's maximum" and MUST select HIGH. Keying the tap off the
     * HIGHLIGHT (the branch's first attempt) sent it to OFF instead — the user
     * asked for the highest reachable tier and got thinking switched off.
     */
    @Test
    fun `regression - tapping the capped ceiling selects it instead of turning thinking off`() {
        val stored = ThinkingLevel.MAX
        val ceiling = ThinkingLevel.HIGH
        val current = effectiveThinkingLevel(stored, supportsReasoning = true, ceiling)
        val maxAvail = availableLevels(ceiling).last { it != ThinkingLevel.AUTO }

        // Preconditions of the clamped state the picker renders.
        assertEquals("highlight", ThinkingLevel.HIGH, current)
        assertEquals("orange capsule", ThinkingLevel.HIGH, maxAvail)
        assertTrue("cue shown", isCappedBy(stored, maxAvail))

        // The bug: the highlighted capsule is not the stored choice, so a tap
        // must NOT be read as "toggle off".
        assertEquals("tap selects the ceiling", ThinkingLevel.HIGH, thinkingTapTarget(maxAvail, stored))
        assertFalse("and not OFF", thinkingTapTarget(maxAvail, stored) == ThinkingLevel.OFF)
    }

    /** OFF must stay reachable from the picker even when the choice is capped. */
    @Test
    fun `OFF is reachable by a second tap after resolving a clamp`() {
        val stored = ThinkingLevel.MAX
        val ceiling = ThinkingLevel.HIGH
        val maxAvail = availableLevels(ceiling).last { it != ThinkingLevel.AUTO }

        val firstTap = thinkingTapTarget(maxAvail, stored)          // -> HIGH
        assertEquals(ThinkingLevel.HIGH, firstTap)
        // setThinkingLevel stores it verbatim (it is at the ceiling), so the
        // stored choice is now HIGH and a second tap reaches OFF.
        val secondTap = thinkingTapTarget(firstTap, firstTap)
        assertEquals(ThinkingLevel.OFF, secondTap)
    }

    /**
     * The unclamped convention is untouched: with `current == requested`, the
     * old `isHighlighted` rule and the new `requested` rule agree. This is why
     * the fix cannot change behaviour for the common case.
     */
    @Test
    fun `unclamped states behave identically under both tap rules`() {
        for (ceiling in all) {
            for (stored in all) {
                val current = effectiveThinkingLevel(stored, supportsReasoning = true, ceiling)
                if (current != stored) continue   // clamped — rules deliberately differ
                val maxAvail = availableLevels(ceiling).lastOrNull { it != ThinkingLevel.AUTO }
                val clamped = maxAvail != null && isCappedBy(stored, maxAvail)
                if (clamped) continue
                for (level in availableLevels(ceiling)) {
                    val highlighted = level == current
                    val oldRule = if (highlighted) ThinkingLevel.OFF else level
                    assertEquals(
                        "ceiling=$ceiling stored=$stored level=$level",
                        oldRule,
                        thinkingTapTarget(level, stored),
                    )
                }
            }
        }
    }

    /** Mirrors ChatViewModel.availableThinkingLevels. */
    private fun availableLevels(ceiling: ThinkingLevel): List<ThinkingLevel> =
        all.filter {
            it != ThinkingLevel.OFF &&
                (it == ThinkingLevel.AUTO || it.rank <= ceiling.rank)
        }
}
