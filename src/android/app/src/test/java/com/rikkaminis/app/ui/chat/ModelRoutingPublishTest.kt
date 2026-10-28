package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [meta-audit-1028 F-05/F-06] Two publications that used to run ahead of the
 * facts they were publishing.
 *
 * F-06 (rotation): `rotateForNewTurn` wrote `currentModel` / `_modelName` /
 * `_providerName` / `_activeEntryId` and only THEN called
 * `ProviderFactory.create` — the one call in the sequence that can throw. A
 * throw left the top bar claiming member B while `currentProvider` still
 * answered with member A. The decision is executable, so it is a value
 * ([planTurnRotation]) and is tested behaviourally here.
 *
 * F-05 (new-chat default): `applyNewChatDefaultModel` published a serving entry
 * but never cleared `_selectedGroupId` / `_selectedGroupName` /
 * `pendingEntryOverride`. A resident view model therefore kept the PREVIOUS
 * group bound: the top bar and the picker highlighted it, `buildFallbackProviders`
 * chained through its members and `rotateForNewTurn` rotated onto them — for a
 * session that was not running under that group at all.
 *
 * F-05's half is a MISSING WRITE, and a missing write cannot be pinned by a
 * pure function (the function would simply not be called). It is pinned by
 * offset on the real source instead, with the pre-fix body as the negative
 * control — the same device `ChatProviderModelSourceTest` uses. Expected values
 * are hardcoded literals, never derived from the code under test.
 */
class ModelRoutingPublishTest {

    private val routing = "src/main/java/com/rikkaminis/app/ui/chat/ChatModelRouting.kt"
    private val lifecycle = "src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt"

    // ── F-06: the rotation decision, as a value ─────────────────────────────

    private fun staleState() = SessionModelSelection(
        modelId = "model-a",
        modelName = "Model A",
        providerName = "Relay A",
        activeEntryId = "entry-a",
        selectedGroupId = "group-1",
        selectedGroupName = "Everyday",
        pendingEntryOverride = null,
    )

    @Test
    fun aFailedCreationPublishesNothing() {
        val before = staleState()
        val plan = planTurnRotation(
            current = before,
            targetEntryId = "entry-b",
            targetModelId = "model-b",
            targetModelName = "Model B",
            targetProviderName = "Relay B",
            created = null,
            createError = "boom",
        )
        assertFalse("a rotation whose provider could not be built must not publish", plan.applied)
        assertNull("no provider may be handed to currentProvider on the failure path", plan.provider)
        assertEquals("the failure path must say why it declined", "boom", plan.reason)
        // Field by field — a plan that returns "current" while one field drifted
        // is exactly the defect this test exists for.
        assertEquals("model-a", plan.state.modelId)
        assertEquals("Model A", plan.state.modelName)
        assertEquals("Relay A", plan.state.providerName)
        assertEquals("entry-a", plan.state.activeEntryId)
        assertEquals("group-1", plan.state.selectedGroupId)
        assertEquals("Everyday", plan.state.selectedGroupName)
        assertNull(plan.state.pendingEntryOverride)
    }

    /**
     * A creation that produced nothing but did not throw is still a failure.
     * The pre-fix code assigned its result straight into `currentProvider`,
     * i.e. a silent null provider behind a top bar naming the new member.
     */
    @Test
    fun aCreationThatYieldedNothingIsNotSuccess() {
        val plan = planTurnRotation(
            current = staleState(),
            targetEntryId = "entry-b",
            targetModelId = "model-b",
            targetModelName = "Model B",
            targetProviderName = "Relay B",
            created = null,
            createError = null,
        )
        assertFalse(plan.applied)
        assertNull(plan.provider)
        assertTrue("a decline must always carry a reason", plan.reason != null && plan.reason!!.isNotBlank())
        assertEquals("entry-a", plan.state.activeEntryId)
        assertEquals("Model A", plan.state.modelName)
    }

    @Test
    fun aCreatedProviderTakesOverTheIdentityFieldsOnly() {
        val before = staleState()
        val plan = planTurnRotation(
            current = before,
            targetEntryId = "entry-b",
            targetModelId = "model-b",
            targetModelName = "Model B",
            targetProviderName = "Relay B",
            created = "provider-b",
            createError = null,
        )
        assertTrue(plan.applied)
        assertEquals("provider-b", plan.provider)
        assertNull(plan.reason)
        assertEquals("model-b", plan.state.modelId)
        assertEquals("Model B", plan.state.modelName)
        assertEquals("Relay B", plan.state.providerName)
        assertEquals("entry-b", plan.state.activeEntryId)
        // Rotation happens INSIDE a group: it must not leave group mode (that is
        // the new-chat / direct-entry publication's job, F-05).
        assertEquals("group-1", plan.state.selectedGroupId)
        assertEquals("Everyday", plan.state.selectedGroupName)
        assertNull(plan.state.pendingEntryOverride)
    }

    // ── F-06: the real call site creates first and publishes from the plan ──

    /** The real pre-fix body of the rotation tail: publish, then create. */
    private val preFixRotation = listOf(
        "internal fun ChatViewModel.rotateForNewTurn(label: String) {",
        "    val target = members.firstOrNull { it.id == targetId } ?: return",
        "    val effectiveModel = providerModelFor(target)",
        "    currentModel = effectiveModel",
        "    _modelName.value = effectiveModel.displayName",
        "    _providerName.value = instance.label.ifEmpty { effectiveModel.provider }",
        "    _activeEntryId.value = target.id",
        "    currentProvider = ProviderFactory.create(instance, apiKey, effectiveModel, context)",
        "}",
    ).joinToString("\n")

    /** Index of the first occurrence of [needle] at or after [from], or -1. */
    private fun idx(body: String, needle: String, from: Int = 0): Int = body.indexOf(needle, from)

    /**
     * The published fields a rotation writes, in the order the defect wrote
     * them. Kept as a list so the reader below stays one criterion.
     */
    private val publishedFields = listOf(
        "currentModel =",
        "_modelName.value =",
        "_providerName.value =",
        "_activeEntryId.value =",
        "currentProvider =",
    )

    /** null = the site is ordered correctly; otherwise the offending field. */
    private fun rotationPublishesBeforeCreate(body: String): String? {
        val create = idx(body, "ProviderFactory.create(")
        if (create < 0) return "ProviderFactory.create( — site missing (probe ROT)"
        for (field in publishedFields) {
            val at = idx(body, field)
            if (at >= 0 && at < create) return field
        }
        return null
    }

    @Test
    fun theNegativeControlRejectsThePreFixRotation() {
        assertEquals(
            "the pre-fix order (publish, then create) must be REJECTED by the same reader the " +
                "real tree is judged with — otherwise this probe has no teeth",
            "currentModel =",
            rotationPublishesBeforeCreate(preFixRotation),
        )
    }

    @Test
    fun rotationBuildsTheProviderBeforePublishingAnything() {
        val body = functionBody(stripComments(source(routing)), "internal fun ChatViewModel.rotateForNewTurn(")
        assertNull(
            "rotateForNewTurn publishes before `ProviderFactory.create` — a throw from there leaves " +
                "the top bar / picker / activeEntryId on a member that is not the one serving",
            rotationPublishesBeforeCreate(body),
        )
        val create = idx(body, "ProviderFactory.create(")
        val plan = idx(body, "planTurnRotation(")
        assertTrue("rotateForNewTurn must decide through planTurnRotation", plan >= 0)
        assertTrue("the plan is built from the creation outcome, so it comes after the call", plan > create)
        val guard = idx(body, "if (!plan.applied)")
        assertTrue("the plan's `applied` must gate every publish", guard > create && guard < idx(body, "currentModel ="))
        val provider = idx(body, "currentProvider = plan.provider")
        assertTrue("currentProvider must come from the plan (null on the failure path)", provider > guard)
    }

    // ── F-05: the real new-chat site clears the group state, after the decision ──

    private val preFixApply = """
        internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
            val decision = decideSessionModelResolution(entry, instance, apiKey)
            if (decision !is SessionModelResolution.Use) return false
            val effectiveModel = providerModelFor(entry)
            currentModel = effectiveModel
            _modelName.value = effectiveModel.displayName
            _activeEntryId.value = entry.id
            _providerName.value = decision.instance.label.ifEmpty { effectiveModel.provider }
            currentProvider = ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)
            return true
        }
    """.trimIndent()

    private val groupClears = listOf(
        "_selectedGroupId.value = null",
        "_selectedGroupName.value = \"\"",
        "pendingEntryOverride = null",
    )

    /**
     * null = every clear is present and after the decision; otherwise the
     * missing/misplaced one. "After the decision" matters: a clear that runs on
     * a REFUSED entry would leave the session in neither mode.
     */
    private fun missingGroupClear(body: String): String? {
        val decide = idx(body, "decideSessionModelResolution(")
        if (decide < 0) return "decideSessionModelResolution( — site missing (probe ROT)"
        for (clear in groupClears) {
            val at = idx(body, clear)
            if (at < 0) return clear
            if (at < decide) return "$clear (before the decision)"
        }
        return null
    }

    @Test
    fun theNegativeControlRejectsThePreFixNewChatPublish() {
        assertEquals(
            "the pre-fix body (a direct-entry publication that leaves the previous group bound) " +
                "must be REJECTED by the same reader the real tree is judged with",
            "_selectedGroupId.value = null",
            missingGroupClear(preFixApply),
        )
        // And the reader accepts the same body once each clear is present, so
        // the rejection above is about the missing writes and nothing else.
        val patched = preFixApply
            .replace(
                "    return true",
                "    _selectedGroupId.value = null\n" +
                    "    _selectedGroupName.value = \"\"\n" +
                    "    pendingEntryOverride = null\n" +
                    "    return true",
            )
        assertTrue(patched.contains("pendingEntryOverride = null"))
        assertNull(missingGroupClear(patched))
    }

    @Test
    fun theNewChatDefaultLeavesGroupMode() {
        val body = functionBody(stripComments(source(lifecycle)), "internal fun ChatViewModel.applyNewChatDefaultModel(")
        assertNull(
            "applyNewChatDefaultModel publishes a serving entry without clearing the group state — a " +
                "resident view model keeps the previous group bound, so the top bar highlights it and " +
                "rotateForNewTurn rotates onto its members",
            missingGroupClear(body),
        )
    }

    // ── helpers (same shape as ChatProviderModelSourceTest) ─────────────────

    /** Body of the `fun` whose signature starts with [signature], up to its closing `}`. */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("`$signature` not found — probe ROT", start >= 0)
        val end = code.indexOf("\n}", start)
        assertTrue("closing brace for `$signature` not found — probe ROT", end > start)
        return code.substring(start, end)
    }

    /** Comments out — KDoc is prose: it must neither satisfy nor trip a probe. */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            "\n".repeat(m.value.count { it == '\n' })
        }
        return noBlock.split("\n").joinToString("\n") { it.substringBefore("//") }
    }

    private fun source(rel: String): String = sourceFile(rel).readText()

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
