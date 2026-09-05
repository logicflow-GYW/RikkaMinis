package com.openminis.app.data

import com.openminis.app.data.model.CustomBodyField
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomBodyMergeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `user scalar wins over default`() {
        val body = JSONObject().put("temperature", 0.5)
        body.mergeCustomBody(
            listOf(CustomBodyField("temperature", "0.9")),
            json,
        )
        assertEquals(0.9, body.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `nested object merges recursively`() {
        val body = JSONObject()
        val inner = JSONObject().put("enabled", true).put("level", "low")
        body.put("thinking", inner)
        body.mergeCustomBody(
            listOf(CustomBodyField("thinking", """{"budget": 4096}""")),
            json,
        )
        val t = body.getJSONObject("thinking")
        assertEquals(true, t.getBoolean("enabled"))
        assertEquals(4096, t.getInt("budget"))
    }

    @Test
    fun `deep dotted path creates intermediate objects`() {
        val body = JSONObject()
        body.mergeCustomBody(
            listOf(CustomBodyField("generationConfig.temperature", "0.2")),
            json,
        )
        assertEquals(0.2, body.getJSONObject("generationConfig").getDouble("temperature"), 0.0001)
    }

    @Test
    fun `invalid json value is ignored with trace`() {
        val body = JSONObject().put("max_tokens", 100)
        body.mergeCustomBody(
            listOf(CustomBodyField("max_tokens", "{not json")),
            json,
        )
        assertEquals(100, body.getInt("max_tokens"))
    }

    @Test
    fun `forceModel restores model after merge`() {
        val body = JSONObject().put("model", "gpt-4o")
        body.mergeCustomBody(
            listOf(CustomBodyField("model", "evil-model")),
            json,
            forceModel = "gpt-4o",
        )
        assertEquals("gpt-4o", body.getString("model"))
    }

    @Test
    fun `array values support`() {
        val body = JSONObject()
        body.mergeCustomBody(
            listOf(CustomBodyField("stop", """["a","b"]""")),
            json,
        )
        val arr = body.getJSONArray("stop")
        assertEquals(2, arr.length())
    }
}
