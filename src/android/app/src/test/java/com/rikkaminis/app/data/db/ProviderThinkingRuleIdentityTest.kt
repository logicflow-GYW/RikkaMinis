package com.rikkaminis.app.data.db

import com.rikkaminis.app.provider.thinking.ThinkingRule
import com.rikkaminis.app.provider.thinking.ThinkingRuleCoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SURGERY-W4 B02] Cross-owner rule identity — the pure resolver.
 *
 * `provider_thinking_rules` has the rule id as its ONLY primary key, so the
 * old `INSERT OR REPLACE` moved a still-live rule from another provider onto
 * the restored one whenever a restore carried an id another provider already
 * used. The restore must never take another provider's rule away.
 *
 * [resolveThinkingRuleIds] is the single source the DAO's
 * `replaceThinkingRules` calls, so this test pins the real production
 * decision rather than a replica. The id-minting function is injected, so the
 * expected re-minted ids are hardcoded literals (never derived from the code
 * under test).
 */
class ProviderThinkingRuleIdentityTest {

    private fun rule(label: String) = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = ThinkingRule.Scope.AllModels,
        wireFormat = null,
        label = label,
    )

    private fun entity(id: String, owner: String, label: String, sortOrder: Int = 0) =
        ThinkingRuleCoding.toEntity(rule(label), id, owner, sortOrder)

    private fun resolve(
        rows: List<ProviderThinkingRuleEntity>,
        occupied: Map<String, String>,
        instanceId: String,
    ): List<ProviderThinkingRuleEntity> {
        var counter = 0
        return resolveThinkingRuleIds(
            rows = rows,
            occupiedOwnerById = occupied,
            instanceId = instanceId,
            mintId = { "minted-${counter++}" },
        )
    }

    @Test
    fun `a rule id owned by another provider is re-minted, not taken over`() {
        val rows = listOf(entity("rule-r", "provider-q", "restored rule"))

        val written = resolve(rows, mapOf("rule-r" to "provider-p"), "provider-q")

        assertEquals(1, written.size)
        assertNotEquals("the restore must not keep an id another provider owns", "rule-r", written.single().id)
        assertEquals("minted-0", written.single().id)
        assertEquals("the content is preserved", "restored rule", written.single().label)
        assertEquals("the target owns it", "provider-q", written.single().providerInstanceId)
    }

    @Test
    fun `the same provider keeps its own rule ids`() {
        val rows = listOf(entity("rule-a", "provider-p", "A rule v2"))

        val written = resolve(rows, mapOf("rule-a" to "provider-p"), "provider-p")

        assertEquals("rule-a", written.single().id)
        assertEquals("A rule v2", written.single().label)
    }

    @Test
    fun `an unused id is kept`() {
        val rows = listOf(entity("brand-new", "provider-p", "new rule"))

        val written = resolve(rows, emptyMap(), "provider-p")

        assertEquals("brand-new", written.single().id)
    }

    @Test
    fun `duplicate ids inside one batch keep every rule`() {
        val rows = listOf(
            entity("dup", "provider-p", "first"),
            entity("dup", "provider-p", "second"),
        )

        val written = resolve(rows, emptyMap(), "provider-p")

        assertEquals(2, written.size)
        assertEquals("both labels survive", setOf("first", "second"), written.map { it.label }.toSet())
        assertEquals("the first occurrence keeps the id", "dup", written.first().id)
        assertEquals("the second occurrence is re-minted", "minted-0", written[1].id)
        assertEquals("the two rows have different ids", 2, written.map { it.id }.toSet().size)
    }

    @Test
    fun `several foreign ids are each re-minted`() {
        val rows = listOf(
            entity("rule-a", "provider-q", "A"),
            entity("rule-b", "provider-q", "B"),
        )

        val written = resolve(rows, mapOf("rule-a" to "provider-p", "rule-b" to "provider-r"), "provider-q")

        assertEquals(listOf("minted-0", "minted-1"), written.map { it.id })
        assertEquals(listOf("A", "B"), written.map { it.label })
    }

    @Test
    fun `a re-minted id never collides with an existing row`() {
        // The minted id collides with an occupied id on the first try.
        var first = true
        val written = resolveThinkingRuleIds(
            rows = listOf(entity("rule-r", "provider-q", "restored")),
            occupiedOwnerById = mapOf("rule-r" to "provider-p", "minted-0" to "provider-p"),
            instanceId = "provider-q",
            mintId = { if (first) { first = false; "minted-0" } else "minted-1" },
        )

        assertEquals("minted-1", written.single().id)
        assertTrue(written.single().id != "rule-r")
    }
}
