package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SURGERY-W4 B01] The thinking-rules section is SPARSE: one group per provider
 * that HAS rules. The importer used to pair a group with a provider by ARRAY
 * POSITION, so A(0 rules)/B(1 rule) restored B's rules onto A.
 *
 * The fix is identity: every exported group carries the source provider's
 * instance id (`_sourceInstanceId`), and the importer pairs by that id
 * through Stage 1's source→restored map. A legacy backup (no such id) still
 * restores when the (type, label) pair is unique — and is REFUSED with a
 * visible note when it is ambiguous, instead of guessing.
 *
 * The decision function under test is the PRODUCTION one
 * (ConfigBackup.kt's ThinkingRuleGroupPairing), not a replica.
 */
class ThinkingRuleGroupPairingTest {

    @Test
    fun `the group lands on the provider its source id names`() {
        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "src-b",
            restoredBySource = mapOf("src-a" to "rest-a", "src-b" to "rest-b"),
            idsForKey = listOf("rest-a", "rest-b"),
            lastIdForKey = "rest-b",
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Owner("rest-b"), d)
    }

    @Test
    fun `a sparse export does not shift rules onto the previous provider`() {
        // A has no rules at all, so it never appears in the rules section.
        val restoredBySource = mapOf("src-a" to "rest-a", "src-b" to "rest-b")
        val idsForKey = listOf("rest-a", "rest-b")

        val group = JSONObject().put("providerType", "openAI").put("providerLabel", "Same")
            .put("_sourceInstanceId", "src-b")

        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = group.optString("_sourceInstanceId", ""),
            restoredBySource = restoredBySource,
            idsForKey = idsForKey,
            lastIdForKey = "rest-b",
        )

        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Owner("rest-b"), d)
    }

    @Test
    fun `a legacy group with a unique label still restores`() {
        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "",
            restoredBySource = emptyMap(),
            idsForKey = listOf("rest-solo"),
            lastIdForKey = "rest-solo",
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Owner("rest-solo"), d)
    }

    @Test
    fun `a legacy group with duplicate labels is refused instead of guessed`() {
        // Two providers share the key, but the backup carries only ONE rule
        // group for them: the sparse section cannot say which provider owns it.
        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "",
            restoredBySource = emptyMap(),
            idsForKey = listOf("rest-a", "rest-b"),
            lastIdForKey = "rest-b",
            groupCountForKey = 1,
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Refused("ambiguous"), d)
    }

    @Test
    fun `a legacy backup with one group per provider stays position-safe`() {
        // Two providers share the key and the backup carries TWO groups — the
        // pre-annotation format, where the groups line up one to one.
        val first = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "",
            restoredBySource = emptyMap(),
            idsForKey = listOf("rest-a", "rest-b"),
            lastIdForKey = "rest-b",
            groupCountForKey = 2,
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Owner("rest-a"), first)
    }

    @Test
    fun `a legacy group whose provider is missing is refused`() {
        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "",
            restoredBySource = emptyMap(),
            idsForKey = emptyList(),
            lastIdForKey = null,
            groupCountForKey = 1,
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Refused("no-provider"), d)
    }

    @Test
    fun `a group whose source provider was not restored is refused`() {
        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "src-gone",
            restoredBySource = mapOf("src-a" to "rest-a"),
            idsForKey = listOf("rest-a"),
            lastIdForKey = "rest-a",
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Refused("unknown-source"), d)
    }

    @Test
    fun `a group with no restored provider at all is refused`() {
        val d = ConfigBackup.ThinkingRuleGroupPairing.decide(
            sourceInstanceId = "",
            restoredBySource = emptyMap(),
            idsForKey = emptyList(),
            lastIdForKey = null,
        )
        assertEquals(ConfigBackup.ThinkingRuleGroupPairing.Decision.Refused("no-provider"), d)
    }

    @Test
    fun `the refusal notes name the reason`() {
        assertTrue(ConfigBackup.ThinkingRuleGroupPairing.refusalNote("Same", "ambiguous").contains("source identity"))
        assertTrue(ConfigBackup.ThinkingRuleGroupPairing.refusalNote("Same", "unknown-source").contains("not restored"))
        assertTrue(ConfigBackup.ThinkingRuleGroupPairing.refusalNote("Same", "no-provider").contains("not restored"))
    }

    @Test
    fun `the exported group shape carries the source identity`() {
        // The shape the exporter writes: a group with providerType/label/rules
        // plus the source id. Older groups (without it) stay loadable.
        val group = JSONObject()
            .put("providerType", "openAI")
            .put("providerLabel", "Same")
            .put("_sourceInstanceId", "src-b")
            .put("rules", JSONArray().put(JSONObject().put("id", "r1").put("label", "rule")))

        assertEquals("src-b", group.optString("_sourceInstanceId", ""))
        assertEquals(1, group.getJSONArray("rules").length())
    }
}
