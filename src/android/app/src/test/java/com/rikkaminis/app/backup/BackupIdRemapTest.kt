package com.rikkaminis.app.backup

import com.rikkaminis.app.config.ConfigValue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [FIX-E / ISSUE-09] Pins the id remapping a restore must perform.
 *
 * A backup taken on install A carries that install's group/entry uuids. Import
 * mints fresh uuids (Stages 1-2 build [groupIdMap] / [entryIdMap]) and every
 * persisted reference must be rewritten onto them. `remapDefaultsIds` used to
 * miss `runtime.compactModelEntryId` (the pinned compaction model — its
 * consumer silently follows the session chain when the pin cannot be resolved,
 * so the loss was invisible), and session `modelBinding` rows were written
 * through verbatim, so every restored session failed restoreFromBinding and
 * degraded to its stale modelId.
 *
 * Expectations are dead literals — never derived from the code under test.
 */
class BackupIdRemapTest {

    // ── remapDefaultsIds ───────────────────────────────────────────────────
    //
    // [FIX-P1A / RT-S5] The contract changed: an id the map cannot resolve is
    // NOT written through any more. It used to be, "so the field's own writer
    // reports it" — which was wrong twice over: group-typed writers THREW on it
    // (refusing a value the backup carried) and the agent-loop writers filtered
    // it and wrote the remainder, i.e. a second import silently CLEARED the
    // user's list. `value == null` now means "nothing usable, keep the local
    // value"; `unmapped` is what the import records in `skipped`.

    @Test
    fun remapDefaultsIds_rewritesCompactModelEntryId() {
        val out = remapDefaultsIds(
            path = "runtime.compactModelEntryId",
            value = ConfigValue.Str("11111111-1111-1111-1111-111111111111"),
            groupIdMap = emptyMap(),
            entryIdMap = mapOf("11111111-1111-1111-1111-111111111111" to "22222222-2222-2222-2222-222222222222"),
        )
        assertEquals(ConfigValue.Str("22222222-2222-2222-2222-222222222222"), out.value)
        assertEquals(emptyList<String>(), out.unmapped)
    }

    @Test
    fun remapDefaultsIds_unmappedIdIsNotWrittenAndIsReported() {
        // RT-S5: the referenced group is not in this backup ⇒ do not write the
        // source id, and hand the id back so the caller can say so.
        val out = remapDefaultsIds(
            path = "runtime.compactModelEntryId",
            value = ConfigValue.Str("00000000-0000-0000-0000-00000000000a"),
            groupIdMap = emptyMap(),
            entryIdMap = mapOf("ffffffff-ffff-ffff-ffff-ffffffffffff" to "22222222-2222-2222-2222-222222222222"),
        )
        assertNull(out.value)
        assertEquals(listOf("00000000-0000-0000-0000-00000000000a"), out.unmapped)
    }

    @Test
    fun remapDefaultsIds_emptyMapsStillResolveThisInstallsOwnIds() {
        // Same-install restore (no maps minted) must not rewrite anything —
        // but since [FIX-R3-D / F-02] it must also not PASS FOREIGN IDS
        // THROUGH. The old shortcut ("no maps ⇒ untouched") was how an older
        // backup with no `groups` section reached the field writers with the
        // SOURCE install's ids: they were filtered away there and the setting
        // was cleared. So the criterion is now "the id exists here": with this
        // install's ids supplied the value comes back as the same object
        // (nothing rewritten, nothing reported), and with a foreign id it is
        // reported instead of written.
        for (path in listOf(
            "defaults.primaryGroup",
            "defaults.subGroup",
            "defaults.voiceInputGroup",
            "defaults.voiceOutputGroup",
            "defaults.agentLoopGroups",
            "defaults.agentLoopEntries",
            "runtime.compactModelEntryId",
        )) {
            val id = "11111111-1111-1111-1111-111111111111"
            val isEntryField =
                path == "defaults.agentLoopEntries" || path == "runtime.compactModelEntryId"
            fun remap(value: ConfigValue, known: Boolean) = remapDefaultsIds(
                path = path,
                value = value,
                groupIdMap = emptyMap(),
                entryIdMap = emptyMap(),
                localGroupIds = if (isEntryField || !known) emptySet() else setOf(id),
                localEntryIds = if (isEntryField && known) setOf(id) else emptySet(),
            )

            val str = ConfigValue.Str(id)
            val strOut = remap(str, known = true)
            assertSame(str, strOut.value)
            assertEquals(emptyList<String>(), strOut.unmapped)

            val arr = ConfigValue.Arr(listOf(ConfigValue.Str(id), ConfigValue.Bool(true)))
            val arrOut = remap(arr, known = true)
            assertSame(arr, arrOut.value)
            assertEquals(emptyList<String>(), arrOut.unmapped)

            // …and the id NOT being here is reported, never written through.
            val unknownStr = remap(ConfigValue.Str(id), known = false)
            assertNull(unknownStr.value)
            assertEquals(listOf(id), unknownStr.unmapped)
            // For the array the non-id element survives (documented rule: drop
            // the unresolvable id, keep what is not a reference) — the point is
            // that the FOREIGN id is gone and reported.
            val unknownArr = remap(arr, known = false)
            assertEquals(ConfigValue.Arr(listOf(ConfigValue.Bool(true))), unknownArr.value)
            assertEquals(listOf(id), unknownArr.unmapped)
        }
    }

    @Test
    fun remapDefaultsIds_arrDropsUnmappedElementAndReportsIt() {
        val out = remapDefaultsIds(
            path = "defaults.agentLoopEntries",
            value = ConfigValue.Arr(
                listOf(
                    ConfigValue.Str("11111111-1111-1111-1111-111111111111"),
                    ConfigValue.Str("99999999-9999-9999-9999-999999999999"),
                    ConfigValue.Bool(false),
                )
            ),
            groupIdMap = emptyMap(),
            entryIdMap = mapOf("11111111-1111-1111-1111-111111111111" to "22222222-2222-2222-2222-222222222222"),
        )
        // Mapped element kept (with the new id), non-string element passes
        // through, unmapped element dropped — never carried as a dangling id.
        assertEquals(
            ConfigValue.Arr(
                listOf(
                    ConfigValue.Str("22222222-2222-2222-2222-222222222222"),
                    ConfigValue.Bool(false),
                )
            ),
            out.value,
        )
        assertEquals(listOf("99999999-9999-9999-9999-999999999999"), out.unmapped)
    }

    @Test
    fun remapDefaultsIds_fullyUnmappedArrayIsNotRewrittenToEmpty() {
        // The silent-wipe shape: writing the filtered remainder would CLEAR the
        // local list. Nothing survives ⇒ value must be null (skip the write).
        val out = remapDefaultsIds(
            path = "defaults.agentLoopGroups",
            value = ConfigValue.Arr(
                listOf(ConfigValue.Str("g-src"), ConfigValue.Str("g-src-2")),
            ),
            groupIdMap = mapOf("g-other" to "g-new"),
            entryIdMap = emptyMap(),
        )
        assertNull(out.value)
        assertEquals(listOf("g-src", "g-src-2"), out.unmapped)
    }

    @Test
    fun remapDefaultsIds_blankIdIsNotADanglingReference() {
        // "" means "nothing selected", not a reference that failed to resolve.
        val out = remapDefaultsIds(
            path = "defaults.primaryGroup",
            value = ConfigValue.Str(""),
            groupIdMap = mapOf("g-src" to "g-new"),
            entryIdMap = emptyMap(),
        )
        assertEquals(ConfigValue.Str(""), out.value)
        assertEquals(emptyList<String>(), out.unmapped)
    }

    @Test
    fun remapDefaultsIds_secondImportWithNoGroupsSectionKeepsLocalDefault() {
        // [FIX-R3-D / F-02] RT-S5 end-to-end shape, on the pure function: import
        // #1 minted the maps; import #2 is an older package with no `groups`
        // section, so the group map is empty. The id it carries is THIS
        // install's own (same-device re-import), so the local id list resolves
        // it verbatim — the field is written, the value is unchanged, and
        // nothing is claimed as remapped. The pre-fix shortcut (empty map ⇒
        // pass through) produced the same value here by NOT looking; the test
        // below is the case where the two disagree.
        val second = remapDefaultsIds(
            path = "defaults.primaryGroup",
            value = ConfigValue.Str("g-src"),
            groupIdMap = emptyMap(),
            entryIdMap = emptyMap(),
            localGroupIds = setOf("g-src"),
        )
        assertEquals(ConfigValue.Str("g-src"), second.value)
        assertEquals(emptyList<String>(), second.unmapped)
        // …a FOREIGN id with no map and no local match is refused instead of
        // written through (the writer would filter it and CLEAR the setting).
        val foreign = remapDefaultsIds(
            path = "defaults.primaryGroup",
            value = ConfigValue.Str("g-other-device"),
            groupIdMap = emptyMap(),
            entryIdMap = emptyMap(),
            localGroupIds = setOf("g-src"),
        )
        assertNull(foreign.value)
        assertEquals(listOf("g-other-device"), foreign.unmapped)
        // …and with a map that simply does not know the id, the field is
        // refused instead of written dangling.
        val dangling = remapDefaultsIds(
            path = "defaults.primaryGroup",
            value = ConfigValue.Str("g-src"),
            groupIdMap = mapOf("g-other" to "g-new"),
            entryIdMap = emptyMap(),
        )
        assertNull(dangling.value)
        assertEquals(listOf("g-src"), dangling.unmapped)
    }

    // ── [FIX-R3-D / F-02] the silent-wipe shape, end to end ──────────────

    /**
     * The exact world the report describes: an older package with no `groups`
     * section, restored onto an install whose agent-loop list is populated.
     * Before the fix the empty map meant "pass the source ids through", the
     * writer's `filter { it in localIds }` emptied the list, and the restore
     * reported success. The two halves are the production functions: the remap
     * here, and `localEntryIds` standing in for the writer's valid set — the
     * writer keeps only ids that exist, which is what "cleared" means.
     */
    @Test
    fun remapDefaultsIds_emptyMapCannotClearTheLocalAgentLoopList() {
        val localEntries = setOf("e-local-1", "e-local-2")

        val remapped = remapDefaultsIds(
            path = "defaults.agentLoopEntries",
            value = ConfigValue.Arr(
                listOf(ConfigValue.Str("e-src-1"), ConfigValue.Str("e-src-2")),
            ),
            groupIdMap = emptyMap(),
            entryIdMap = emptyMap(),
            localEntryIds = localEntries,
        )

        // Nothing survived ⇒ no write at all (and the ids are reported).
        assertNull(remapped.value)
        assertEquals(listOf("e-src-1", "e-src-2"), remapped.unmapped)

        // The pre-fix path, spelled out so the regression is unmistakable: had
        // the source ids been written, the writer's own filter would have kept
        // nothing.
        val whatTheWriterWouldKeep = listOf("e-src-1", "e-src-2").filter { it in localEntries }
        assertEquals(emptyList<String>(), whatTheWriterWouldKeep)
        // The local list therefore still holds both entries.
        assertEquals(2, localEntries.size)
    }

    @Test
    fun remapDefaultsIds_sameInstallIdsSurviveWhenTheDocumentHasNoSections() {
        // The other half of the same shape: same-device re-import. Every id is
        // already here, so the fields must be written unchanged rather than
        // reported as unmapped (that would be a fake "partial restore").
        val entries = remapDefaultsIds(
            path = "defaults.agentLoopEntries",
            value = ConfigValue.Arr(listOf(ConfigValue.Str("e-1"), ConfigValue.Str("e-2"))),
            groupIdMap = emptyMap(),
            entryIdMap = emptyMap(),
            localEntryIds = setOf("e-1", "e-2"),
        )
        assertEquals(
            ConfigValue.Arr(listOf(ConfigValue.Str("e-1"), ConfigValue.Str("e-2"))),
            entries.value,
        )
        assertEquals(emptyList<String>(), entries.unmapped)

        val pin = remapDefaultsIds(
            path = "runtime.compactModelEntryId",
            value = ConfigValue.Str("e-2"),
            groupIdMap = emptyMap(),
            entryIdMap = emptyMap(),
            localEntryIds = setOf("e-1", "e-2"),
        )
        assertEquals(ConfigValue.Str("e-2"), pin.value)
        assertEquals(emptyList<String>(), pin.unmapped)
    }

    @Test
    fun remapDefaultsIds_unknownPathReturnsInput() {
        val v = ConfigValue.Str("whatever")
        assertEquals(
            v,
            remapDefaultsIds("runtime.memoryInjectLines", v, mapOf("x" to "y"), mapOf("x" to "y")).value,
        )
    }

    // ── [da1031-A] voice group bindings ───────────────────────────────────

    @Test
    fun remapDefaultsIds_rewritesVoiceInputGroup() {        val out = remapDefaultsIds(
            path = "defaults.voiceInputGroup",
            value = ConfigValue.Str("aaaaaaaa-1111-1111-1111-111111111111"),
            groupIdMap = mapOf("aaaaaaaa-1111-1111-1111-111111111111" to "bbbbbbbb-2222-2222-2222-222222222222"),
            entryIdMap = emptyMap(),
        )
        assertEquals(ConfigValue.Str("bbbbbbbb-2222-2222-2222-222222222222"), out.value)
    }

    @Test
    fun remapDefaultsIds_rewritesVoiceOutputGroup() {
        val out = remapDefaultsIds(
            path = "defaults.voiceOutputGroup",
            value = ConfigValue.Str("cccccccc-1111-1111-1111-111111111111"),
            groupIdMap = mapOf("cccccccc-1111-1111-1111-111111111111" to "dddddddd-2222-2222-2222-222222222222"),
            entryIdMap = emptyMap(),
        )
        assertEquals(ConfigValue.Str("dddddddd-2222-2222-2222-222222222222"), out.value)
    }

    @Test
    fun remapDefaultsIds_voiceGroupMissingFromMapIsSkippedNotWritten() {
        // [FIX-P1A / RT-S5] Was "pass through, let the writer report unknown" —
        // the writer threw InvalidValue, so the restore refused a value the
        // backup carried and said nothing. Now: no write, and the id is named.
        val out = remapDefaultsIds(
            path = "defaults.voiceInputGroup",
            value = ConfigValue.Str("eeeeeeee-1111-1111-1111-111111111111"),
            groupIdMap = mapOf("ffffffff-ffff-ffff-ffff-ffffffffffff" to "g-new"),
            entryIdMap = emptyMap(),
        )
        assertNull(out.value)
        assertEquals(listOf("eeeeeeee-1111-1111-1111-111111111111"), out.unmapped)
    }

    @Test
    fun remapDefaultsIds_voiceGroupNeverRoutesThroughEntryMap() {
        // Discriminator: a group uuid that is a key in the ENTRY map must NOT
        // be rewritten through it. If someone moves the voice keys onto the
        // entryIdMap branch (or the bare `else` drops them entirely), this
        // turns the silent misroute into a red test. With no group map and no
        // local group of that id the field is REFUSED (null) — and the entry
        // map must not be consulted (had it been, the value would come back as
        // "e-new", which is the assertion this test would trip on).
        val v = ConfigValue.Str("eeeeeeee-1111-1111-1111-111111111111")
        val input = remapDefaultsIds(
            path = "defaults.voiceInputGroup",
            value = v,
            groupIdMap = emptyMap(),
            entryIdMap = mapOf("eeeeeeee-1111-1111-1111-111111111111" to "e-new"),
        )
        assertNull(input.value)
        assertEquals(listOf("eeeeeeee-1111-1111-1111-111111111111"), input.unmapped)
        val output = remapDefaultsIds(
            path = "defaults.voiceOutputGroup",
            value = v,
            groupIdMap = emptyMap(),
            entryIdMap = mapOf("eeeeeeee-1111-1111-1111-111111111111" to "e-new"),
        )
        assertNull(output.value)
        assertEquals(listOf("eeeeeeee-1111-1111-1111-111111111111"), output.unmapped)
        // …and the group map IS consulted (the positive half of the same
        // branch), including an id that exists locally.
        val mapped = remapDefaultsIds(
            path = "defaults.voiceInputGroup",
            value = v,
            groupIdMap = mapOf("eeeeeeee-1111-1111-1111-111111111111" to "g-new"),
            entryIdMap = emptyMap(),
        )
        assertEquals(ConfigValue.Str("g-new"), mapped.value)
        val local = remapDefaultsIds(
            path = "defaults.voiceInputGroup",
            value = v,
            groupIdMap = emptyMap(),
            entryIdMap = emptyMap(),
            localGroupIds = setOf("eeeeeeee-1111-1111-1111-111111111111"),
        )
        assertEquals(v, local.value)
    }

    // ── remapSessionBindingJson ────────────────────────────────────────────

    @Test
    fun remapSessionBindingJson_rewritesGroupBinding() {
        val out = remapSessionBindingJson(
            bindingJson = """{"type":"group","groupId":"g-old","lastEntryId":"e-old"}""",
            groupIdMap = mapOf("g-old" to "g-new"),
            entryIdMap = mapOf("e-old" to "e-new"),
        )
        val obj = JSONObject(out!!)
        assertEquals("group", obj.optString("type"))
        assertEquals("g-new", obj.optString("groupId"))
        assertEquals("e-new", obj.optString("lastEntryId"))
        assertEquals(3, obj.length())
    }

    @Test
    fun remapSessionBindingJson_rewritesEntryBinding() {
        val out = remapSessionBindingJson(
            bindingJson = """{"type":"entry","entryId":"e-old"}""",
            groupIdMap = emptyMap(),
            entryIdMap = mapOf("e-old" to "e-new"),
        )
        val obj = JSONObject(out!!)
        assertEquals("entry", obj.optString("type"))
        assertEquals("e-new", obj.optString("entryId"))
        assertEquals(2, obj.length())
    }

    @Test
    fun remapSessionBindingJson_unknownIdsKeepOriginalValues() {
        val out = remapSessionBindingJson(
            bindingJson = """{"type":"group","groupId":"g-keep","lastEntryId":"e-keep"}""",
            groupIdMap = mapOf("g-other" to "g-new"),
            entryIdMap = emptyMap(),
        )
        val obj = JSONObject(out!!)
        assertEquals("g-keep", obj.optString("groupId"))
        assertEquals("e-keep", obj.optString("lastEntryId"))
    }

    @Test
    fun remapSessionBindingJson_emptyMapsReturnIdenticalString() {
        // Same-install restore: byte-identical passthrough, not a re-serialize.
        val group = """{"type":"group","groupId":"g-old","lastEntryId":"e-old"}"""
        assertEquals(group, remapSessionBindingJson(group, emptyMap(), emptyMap()))
        assertNull(remapSessionBindingJson(null, mapOf("a" to "b"), mapOf("a" to "b")))
        assertEquals("", remapSessionBindingJson("", mapOf("a" to "b"), mapOf("a" to "b")))
    }

    @Test
    fun remapSessionBindingJson_malformedPayloadReturnedUnchanged() {
        val maps = mapOf("e-old" to "e-new")
        for (payload in listOf(
            "not json at all",
            """[1,2,3]""",
            """{"type":"future","entryId":"e-old"}""",
            """{"entryId":"e-old"}""",
        )) {
            assertEquals(payload, remapSessionBindingJson(payload, maps, maps))
        }
    }

    @Test
    fun remapSessionBindingJson_groupWithoutLastEntryIdStaysKeyless() {
        // ChatSessionEntryExt can persist {"type":"group","groupId":…} with no
        // lastEntryId — remapping must not conjure the key.
        val out = remapSessionBindingJson(
            bindingJson = """{"type":"group","groupId":"g-old"}""",
            groupIdMap = mapOf("g-old" to "g-new"),
            entryIdMap = emptyMap(),
        )
        val obj = JSONObject(out!!)
        assertEquals("g-new", obj.optString("groupId"))
        assertEquals(false, obj.has("lastEntryId"))
    }
}
