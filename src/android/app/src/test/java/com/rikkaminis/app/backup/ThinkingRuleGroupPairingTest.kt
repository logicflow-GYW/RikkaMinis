package com.rikkaminis.app.backup

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic documents through the real Provider and rule stages. */
class ThinkingRuleGroupPairingTest {
    @Test
    fun `failed first provider keeps its original legacy slot`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        store.failNextSecret = true
        val report = ConfigBackup.import(
            store.repo, restoreDocument(
                providers = JSONArray().put(sourceProvider().put("apiKey", "fixture-only")).put(sourceProvider()),
                rules = JSONArray().put(sourceRules(rows = arrayOf(ruleRow("first"))))
                    .put(sourceRules(rows = arrayOf(ruleRow("second")))),
            ),
        )
        // The append of A happened, but its later credential write failed.
        // B must not inherit A's rules or merge into the partially created A.
        val owners = store.repo.instances.associate { it.label to it.id }
        assertEquals(setOf("Same", "Same (2)"), owners.keys)
        assertEquals(1, report.providersImported)
        assertEquals(1, report.thinkingRulesImported)
        assertEquals(mapOf("second" to owners.getValue("Same (2)")), store.dao.rules.mapValues { it.value.providerInstanceId })
        assertTrue(report.skipped.any { it.contains("source provider not restored") })
    }

    @Test
    fun `bad rules array consumes its known legacy slot without shifting the next group`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        val report = ConfigBackup.import(
            store.repo, restoreDocument(
                providers = JSONArray().put(sourceProvider()).put(sourceProvider()),
                rules = JSONArray().put(sourceRules().put("rules", "broken"))
                    .put(sourceRules(rows = arrayOf(ruleRow("second")))),
            ),
        )
        val owner = store.repo.instances.single { it.label == "Same (2)" }
        assertEquals(1, report.thinkingRulesImported)
        assertEquals(owner.id, store.dao.rules.getValue("second").providerInstanceId)
        assertTrue(report.skipped.any { it.contains("invalid rules array") })
    }

    @Test
    fun `surplus or sparse legacy groups with the same key are not guessed`() = runBlocking {
        for (groupCount in listOf(1, 3)) {
            val store = RestoreTestStore()
            store.repo.awaitConfigLoaded()
            val rules = JSONArray()
            repeat(groupCount) { rules.put(sourceRules(rows = arrayOf(ruleRow("rule-$it")))) }
            val report = ConfigBackup.import(
                store.repo, restoreDocument(
                    providers = JSONArray().put(sourceProvider()).put(sourceProvider()), rules = rules,
                ),
            )
            assertEquals(2, report.providersImported)
            assertEquals(0, report.thinkingRulesImported)
            assertTrue(store.dao.rules.isEmpty())
            assertEquals(groupCount, report.skipped.count { it.contains("rules not restored") })
        }
    }

    @Test
    fun `valid legacy documents keep unique labels and full duplicate label order`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        val report = ConfigBackup.import(
            store.repo, restoreDocument(
                providers = JSONArray().put(sourceProvider()).put(sourceProvider("Unique")).put(sourceProvider()),
                rules = JSONArray().put(sourceRules(rows = arrayOf(ruleRow("a"))))
                    .put(sourceRules("Unique", rows = arrayOf(ruleRow("u"))))
                    .put(sourceRules(rows = arrayOf(ruleRow("b")))),
            ),
        )
        val owners = store.repo.instances.associate { it.label to it.id }
        assertEquals(3, report.thinkingRulesImported)
        assertEquals(
            mapOf("a" to owners.getValue("Same"), "u" to owners.getValue("Unique"), "b" to owners.getValue("Same (2)")),
            store.dao.rules.mapValues { it.value.providerInstanceId },
        )
    }

    @Test
    fun `sparse explicit and mixed documents only apply known owners`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        val report = ConfigBackup.import(
            store.repo, restoreDocument(
                providers = JSONArray().put(sourceProvider(source = "a")).put(sourceProvider(source = "b")),
                rules = JSONArray().put(sourceRules(rows = arrayOf(ruleRow("unowned"))))
                    .put(sourceRules(source = "b", rows = arrayOf(ruleRow("belongs-to-b")))),
            ),
        )
        assertEquals(1, report.thinkingRulesImported)
        assertEquals(setOf("belongs-to-b"), store.dao.rules.keys)
        assertEquals(store.repo.instances.single { it.label == "Same (2)" }.id, store.dao.rules.getValue("belongs-to-b").providerInstanceId)
        assertTrue(report.skipped.any { it.contains("mixed with source-identified") })
    }

    @Test
    fun `duplicate source declarations including a failed one and duplicate groups are refused`() = runBlocking {
        for (duplicateProviders in listOf(true, false)) {
            val store = RestoreTestStore()
            store.repo.awaitConfigLoaded()
            val providers = JSONArray().put(sourceProvider(source = "src"))
            val rules = JSONArray().put(sourceRules(source = "src", rows = arrayOf(ruleRow("a"))))
            if (duplicateProviders) {
                providers.put(sourceProvider(source = "src").put("providerType", "no-such-type"))
            } else {
                rules.put(sourceRules(source = "src", rows = arrayOf(ruleRow("b"))))
            }
            val report = ConfigBackup.import(store.repo, restoreDocument(providers, rules))
            assertEquals(1, report.providersImported)
            assertEquals(0, report.thinkingRulesImported)
            assertTrue(store.dao.rules.isEmpty())
            assertTrue(report.skipped.any { it.contains("duplicated") })
        }
    }

    @Test
    fun `an unidentified malformed group cannot be hidden by a surplus legacy group`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        val report = ConfigBackup.import(
            store.repo, restoreDocument(
                providers = JSONArray().put(sourceProvider()),
                rules = JSONArray().put(JSONObject.NULL).put(sourceRules(rows = arrayOf(ruleRow("surplus")))),
            ),
        )
        assertEquals(0, report.thinkingRulesImported)
        assertTrue(store.dao.rules.isEmpty())
        assertTrue(report.skipped.any { it.contains("no identifiable type/label") })
    }

    @Test
    fun `both groups retain their own id reassignment notes`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        val report = ConfigBackup.import(
            store.repo, restoreDocument(
                providers = JSONArray().put(sourceProvider("Original")).put(sourceProvider("A")).put(sourceProvider("B")),
                rules = JSONArray().put(sourceRules("Original", rows = arrayOf(ruleRow("same-id"))))
                    .put(sourceRules("A", rows = arrayOf(ruleRow("same-id", "rule A"))))
                    .put(sourceRules("B", rows = arrayOf(ruleRow("same-id", "rule B")))),
            ),
        )
        assertEquals(3, report.thinkingRulesImported)
        assertEquals("same-id", store.dao.rules.getValue("same-id").label)
        assertEquals(setOf("same-id", "rule A", "rule B"), store.dao.rules.values.map { it.label }.toSet())
        assertEquals(2, report.skipped.count { it.contains("restored under new ids") })
        assertTrue(report.skipped.any { it.contains("\"A\"") && it.contains("new ids") })
        assertTrue(report.skipped.any { it.contains("\"B\"") && it.contains("new ids") })
    }
}
