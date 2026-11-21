package com.rikkaminis.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnthropicReplayPublicProjectionTest {
    private fun project(raw: String) = AnthropicReplayPublicProjection.partsJson(raw)

    @Test fun removesWholePrivatePartsAndKeepsPublicOrder() {
        val raw = """[{"type":"text","value":"你好 🌍"},{"type":"anthropicReplayV1","value":{"signature":"SIG_SYNTH_D05","data":"DATA_SYNTH_D05"}},{"type":"toolUse","value":{"input":"{}","toolUseId":"c"}},{"type":"toolResult","value":{"output":"done","toolUseId":"c"}}]"""
        val out = project(raw)
        assertFalse(out.contains("SIG_SYNTH_D05"))
        assertFalse(out.contains("DATA_SYNTH_D05"))
        val parts = JSONArray(out)
        assertEquals(listOf("text", "toolUse", "toolResult"), (0 until parts.length()).map { parts.getJSONObject(it).getString("type") })
        assertEquals("你好 🌍", parts.getJSONObject(0).getString("value"))
        assertEquals(out, project(out))
    }

    @Test fun ordinaryUnknownPartsAndQuotedExamplesAreUntouched() {
        val sample = """{"type":"anthropicReplayV1","value":{"data":"just an example"}}"""
        val raw = JSONArray().put(JSONObject().put("type", "text").put("value", sample))
            .put(JSONObject().put("type", "futurePart").put("value", "αντίο"))
            .put(JSONObject().put("type", "ImageData").put("data", "public-image"))
            .put(JSONObject().put("type", "anthropicReplayV10").put("value", "ordinary"))
            .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("input", sample))).toString()
        assertEquals(raw, project(raw))
        assertEquals("[]", project("[]"))
        assertEquals("plain anthropicReplayV1", project("plain anthropicReplayV1"))
    }

    @Test fun malformedPrivateRowsNeverUseRawFallback() {
        val bad = listOf(
            """[{"type":"anthropicReplayV1","value":{"signature":"SIG_SYNTH_D05","data":"DATA_SYNTH_D05"}""",
            """[{"\u0074ype":"anthropicReplay\u00561","value":{"data":"DATA_SYNTH_D05"}""",
            """[{'type':'anthropicReplayV1','value':{'signature':'SIG_SYNTH_D05'}""",
            """[{type:anthropicReplayV1,value:{data:DATA_SYNTH_D05}""",
            """[] {"type":"anthropicReplayV1","data":"DATA_SYNTH_D05"}""",
            """[{"type" /* accepted by Android */ : /* comment */ "anthropicReplayV1","data":"DATA_SYNTH_D05"""",
            """[{"type"=>"anthropicReplayV1";"data":"DATA_SYNTH_D05"""",
        )
        for (raw in bad) {
            val out = project(raw)
            assertEquals("[]", out)
            assertFalse(out.contains("SIG_SYNTH_D05"))
            assertFalse(out.contains("DATA_SYNTH_D05"))
        }
    }

    @Test fun malformedOrdinaryRowsKeepTheirReadableFallback() {
        val rows = listOf("not json", "[{\"type\":\"text\",\"value\":\"hello", "", "[broken", "prefix anthropicReplayV1", """[{"type":"anthropicReplayV10","value":"bad""")
        for (raw in rows) assertEquals(raw, project(raw))
        val quoted = """[{"type":"text","value":"quoted \"type\":\"anthropicReplayV1\""""
        assertEquals(quoted, project(quoted))
    }

    @Test fun escapedPrivateTypeIsRemovedWithoutChangingPublicValues() {
        val raw = """[{"type":"text","value":"e\u0301 / é / 日本語"},{"type":"anthropicReplay\u00561","value":{"data":"DATA_SYNTH_D05"}}]"""
        val out = JSONArray(project(raw))
        assertEquals(1, out.length())
        assertEquals("e\u0301 / é / 日本語", out.getJSONObject(0).getString("value"))
    }

    @Test fun nearRowCapRetainsPublicPayloadAndBoundsOversizedOrDeepInput() {
        val publicText = "日".repeat(160_000)
        val raw = JSONArray().put(JSONObject().put("type", "text").put("value", publicText))
            .put(JSONObject().put("type", "anthropicReplayV1").put("value", JSONObject().put("data", "DATA_SYNTH_D05"))).toString()
        assertTrue(raw.toByteArray(Charsets.UTF_8).size in 480_000..500_000)
        assertEquals(publicText, JSONArray(project(raw)).getJSONObject(0).getString("value"))
        assertEquals("[]", project("[".repeat(100) + "]".repeat(100)))
        assertEquals("[]", project("x".repeat(1_000_001)))
    }

    @Test fun projectionHelperDescriptionDoesNotHoldInputs() {
        project("""[{"type":"anthropicReplayV1","value":{"data":"DATA_SYNTH_D05"}}]""")
        assertFalse(AnthropicReplayPublicProjection.toString().contains("DATA_SYNTH_D05"))
    }
}
