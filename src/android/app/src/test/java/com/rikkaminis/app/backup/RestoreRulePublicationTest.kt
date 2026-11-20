package com.rikkaminis.app.backup

import com.rikkaminis.app.provider.thinking.ThinkingRuleCoding
import com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production restore, DAO default bodies, and the resolver read used by the worker dispatcher. */
class RestoreRulePublicationTest {
    @Test
    fun `a late transaction return cannot publish its older rules after the next writer`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        ConfigBackup.import(store.repo, restoreDocument(providers = JSONArray().put(sourceProvider())))
        val owner = store.repo.instances.single().id
        store.dao.holdAfterCommitLabel = "A"
        val a = async(start = CoroutineStart.UNDISPATCHED) {
            store.repo.restoreThinkingRules(owner, JSONArray().put(ruleRow("same", "A")))
        }
        var enteredEarly = false
        val b = try {
            withTimeout(5_000) { store.dao.committed.await() }
            async(start = CoroutineStart.UNDISPATCHED) {
                store.repo.restoreThinkingRules(owner, JSONArray().put(ruleRow("same", "B")))
            }.also { enteredEarly = it.isCompleted }
        } finally {
            store.dao.releaseCommit.complete(Unit)
        }
        assertEquals(1, a.await().count)
        assertEquals(1, b.await().count)
        assertEquals("B", store.dao.rules.getValue("same").label)
        assertEquals(listOf("B"), workerRuleLabels(owner))
        assertFalse("B cannot take the DAO while A's committed rows are waiting to publish", enteredEarly)
    }

    @Test
    fun `cancel after commit still publishes before another rule writer`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        ConfigBackup.import(store.repo, restoreDocument(providers = JSONArray().put(sourceProvider())))
        val owner = store.repo.instances.single().id
        store.dao.holdAfterCommitLabel = "Committed"
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            store.repo.restoreThinkingRules(owner, JSONArray().put(ruleRow("r", "Committed")))
        }
        try {
            withTimeout(5_000) { store.dao.committed.await() }
            restore.cancel()
        } finally {
            store.dao.releaseCommit.complete(Unit)
        }
        restore.join()
        assertTrue(restore.isCancelled)
        assertEquals(listOf("Committed"), workerRuleLabels(owner))
    }

    @Test
    fun `reorder is a permutation and a failed write and reread leave the last publication`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        ConfigBackup.import(store.repo, restoreDocument(providers = JSONArray().put(sourceProvider())))
        val owner = store.repo.instances.single().id
        store.repo.restoreThinkingRules(owner, JSONArray().put(ruleRow("a", sort = 0)).put(ruleRow("b", sort = 1)))
        store.repo.reorderThinkingRules(owner, listOf("b", "b", "unknown"))
        assertEquals(listOf("b", "a"), workerRuleLabels(owner))
        assertEquals(listOf("b", "a"), store.dao.loadThinkingRules(owner).map { it.id })
        store.dao.failMutation = true
        store.dao.failRuleRead = true
        store.repo.deleteThinkingRule(owner, "b")
        assertEquals(listOf("b", "a"), workerRuleLabels(owner))
        store.dao.failRuleRead = false
        store.repo.removeInstance(owner)
        assertTrue(workerRuleLabels(owner).isEmpty())
        assertTrue(store.dao.loadThinkingRules(owner).isEmpty())
        assertEquals(0, store.repo.restoreThinkingRules(owner, JSONArray().put(ruleRow("stray"))).count)
        assertTrue(store.dao.loadThinkingRules(owner).isEmpty())
    }

    private fun workerRuleLabels(id: String): List<String> = ThinkingRuleResolver.customRulesFor(id)
        .map { ThinkingRuleCoding.encodeRuleJson(it).getString("label") }
}
