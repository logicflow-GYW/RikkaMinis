package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/chat-tuning-panel-b] Pins the ImageBudget descent ladder to the exact
 * pre-panel sequence at the default inputs, and checks the integer-factor
 * math (0.64×2000 must be 1280, not 1279).
 */
class ImageBudgetLadderTest {

    @Test
    fun `default inputs reproduce the pre-panel ladder exactly`() {
        // The literal LADDER from ImageBudget before the panel existed.
        assertEquals(
            listOf(
                2000 to 80,
                1600 to 75,
                1280 to 70,
                1024 to 65,
                896 to 55,
                768 to 50,
                640 to 45,
            ),
            ImageBudgetLadder.ladderFor(2000, 80),
        )
    }

    @Test
    fun `raised edge and quality scale the whole ladder`() {
        val l = ImageBudgetLadder.ladderFor(4000, 90)
        assertEquals(4000 to 90, l.first())
        assertEquals(3200 to 85, l[1])
        assertEquals(1280 to 55, l.last())
    }

    @Test
    fun `quality descends to a floor of 30`() {
        val l = ImageBudgetLadder.ladderFor(2000, 40)
        assertEquals(40, l.first().second)
        assertEquals(30, l.last().second)
    }

    @Test
    fun `ladder is monotonic in both axes`() {
        val l = ImageBudgetLadder.ladderFor(2000, 80)
        for (i in 1 until l.size) {
            assertTrue("edge must strictly shrink at step $i", l[i].first < l[i - 1].first)
            assertTrue("quality must never rise at step $i", l[i].second <= l[i - 1].second)
        }
    }
}