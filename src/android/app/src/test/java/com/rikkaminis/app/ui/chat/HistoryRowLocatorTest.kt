package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * [fix/replay-guard-cost-1123] The locator must be behaviorally identical to
 * the two expressions it replaces:
 *
 *     original.indices.singleOrNull { sameHistoryRow(message, original[it]) }
 *     messages.count { sameHistoryRow(message, it) }
 *
 * [legacySource]/[legacyCount] are a verbatim copy of [sameHistoryRow]'s
 * contract (kept here on purpose: the production helper is private, and an
 * equivalence test must not import the implementation it is testing).
 */
class HistoryRowLocatorTest {
    private fun msg(
        id: String?,
        role: LLMMessage.Role = LLMMessage.Role.ASSISTANT,
        text: String = "row",
    ) = LLMMessage(role, text, dbMessageId = id)

    private fun legacySameRow(a: LLMMessage?, b: LLMMessage?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a.dbMessageId != null -> a.dbMessageId == b.dbMessageId && a.role == b.role
        else -> a === b
    }

    private fun legacySource(original: List<LLMMessage>, message: LLMMessage): Int? =
        original.indices.singleOrNull { legacySameRow(message, original[it]) }

    private fun legacyCount(messages: List<LLMMessage>, message: LLMMessage): Int =
        messages.count { legacySameRow(message, it) }

    @Test
    fun uniqueIdResolvesItsIndexAndCount() {
        val a = msg("a")
        val b = msg("b", LLMMessage.Role.USER)
        val locator = HistoryRowLocator(listOf(a, b), listOf(a, b))
        assertEquals(0, locator.sourceIndexOf(a))
        assertEquals(1, locator.sourceIndexOf(b))
        assertEquals(1, locator.occurrenceCount(a))
        assertEquals(1, locator.occurrenceCount(b))
    }

    @Test
    fun duplicateIdIsAmbiguousExactlyLikeSingleOrNull() {
        val first = msg("dup")
        val second = msg("dup")
        val original = listOf(first, second)
        val locator = HistoryRowLocator(original, original)
        // singleOrNull contract: two candidates = null, never the first index.
        assertNull(locator.sourceIndexOf(first))
        assertNull(locator.sourceIndexOf(second))
        assertEquals(2, locator.occurrenceCount(first))
    }

    @Test
    fun sameIdDifferentRoleStaysDistinct() {
        val assistant = msg("shared", LLMMessage.Role.ASSISTANT)
        val user = msg("shared", LLMMessage.Role.USER)
        val original = listOf(assistant, user)
        val locator = HistoryRowLocator(original, original)
        assertEquals(0, locator.sourceIndexOf(assistant))
        assertEquals(1, locator.sourceIndexOf(user))
        assertEquals(1, locator.occurrenceCount(assistant))
    }

    @Test
    fun synthesizedRowsMatchByReferenceOnly() {
        val synthesized = msg(null)
        val sameContentCopy = msg(null)
        val original = listOf(synthesized, sameContentCopy)
        val locator = HistoryRowLocator(original, original)
        assertEquals(0, locator.sourceIndexOf(synthesized))
        assertNull(locator.sourceIndexOf(msg(null)))
        // The same object twice is also ambiguous, as the old singleOrNull was.
        val doubled = HistoryRowLocator(listOf(synthesized, synthesized), listOf(synthesized, synthesized))
        assertNull(doubled.sourceIndexOf(synthesized))
        assertEquals(2, doubled.occurrenceCount(synthesized))
    }

    @Test
    fun countsComeFromProjectedMessagesNotTheOriginal() {
        val a = msg("a")
        val original = listOf(a)
        val projected = listOf(a, a.copy())
        val locator = HistoryRowLocator(original, projected)
        // One original row, two projected copies: index resolves, count does not.
        assertEquals(0, locator.sourceIndexOf(a))
        assertEquals(2, locator.occurrenceCount(a))
    }

    @Test
    fun randomizedDifferentialAgainstLegacyExpression() {
        val random = Random(1123)
        val ids = listOf("a", "b", "c", "d", null)
        repeat(300) {
            val size = random.nextInt(0, 14)
            val original = ArrayList<LLMMessage>(size)
            for (i in 0 until size) {
                val role = if (random.nextBoolean()) LLMMessage.Role.ASSISTANT else LLMMessage.Role.USER
                original.add(LLMMessage(role, "t$i", dbMessageId = ids[random.nextInt(ids.size)]))
            }
            // Projected history: same rows, some element copies, some dropped.
            val projected = original.mapNotNull { row ->
                if (random.nextInt(6) == 0) null
                else if (random.nextInt(4) == 0) row.copy() else row
            }
            val locator = HistoryRowLocator(original, projected)
            for (candidate in original + projected + listOf(msg("a"), msg(null))) {
                assertEquals(
                    "source index of ${candidate.dbMessageId}/${candidate.role}",
                    legacySource(original, candidate),
                    locator.sourceIndexOf(candidate),
                )
                assertEquals(
                    "occurrence count of ${candidate.dbMessageId}/${candidate.role}",
                    legacyCount(projected, candidate),
                    locator.occurrenceCount(candidate),
                )
            }
        }
    }
}
