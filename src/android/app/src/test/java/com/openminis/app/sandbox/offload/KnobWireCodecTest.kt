package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.CustomBodyField
import com.openminis.app.data.model.CustomHeader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip oracle for the knob fields crossing the main-process →
 * :modelservice boundary (the wire-codec half of the four-way sync trap).
 *
 * Shape contract (kept in sync with ModelExecutionDispatcher.buildRequestJson):
 *   custom_headers:      [{ "name": String, "value": String }, ...]  (absent when empty)
 *   custom_body_fields:  [{ "key": String, "value_json": String }, ...] (absent when empty)
 *
 * Run in the sandbox:
 *   kotlinc KnobWireCodec.kt KnobWireCodecTest.kt ... -include-runtime -d out.jar
 *   java -cp ... org.junit.runner.JUnitCore com.openminis.app.sandbox.offload.KnobWireCodecTest
 */
class KnobWireCodecTest {

    @Test
    fun `headers round trip through the wire arrays`() {
        val wire = JSONArray().apply {
            put(JSONObject().put("name", "X-Foo").put("value", "bar"))
            put(JSONObject().put("name", "Authorization").put("value", "Bearer secret"))
        }
        val back = wire.parseKnobHeaders()
        assertEquals(2, back.size)
        assertEquals(CustomHeader("X-Foo", "bar"), back[0])
        assertEquals(CustomHeader("Authorization", "Bearer secret"), back[1])
    }

    @Test
    fun `body fields round trip through the wire arrays`() {
        val wire = JSONArray().apply {
            put(JSONObject().put("key", "generationConfig.temperature").put("value_json", "0.2"))
            put(JSONObject().put("key", "stop").put("value_json", """["a","b"]"""))
        }
        val back = wire.parseKnobBodyFields()
        assertEquals(2, back.size)
        assertEquals(CustomBodyField("generationConfig.temperature", "0.2"), back[0])
        assertEquals(CustomBodyField("stop", """["a","b"]"""), back[1])
    }

    @Test
    fun `blank names and malformed entries are dropped`() {
        val wire = JSONArray().apply {
            put(JSONObject().put("name", "  ").put("value", "x"))   // blank header name
            put(JSONObject())                                      // missing keys
            put("not-an-object")                                   // wrong type
            put(JSONObject().put("key", "").put("value_json", "1")) // blank body key
        }
        assertTrue(wire.parseKnobHeaders().isEmpty())
        assertTrue(wire.parseKnobBodyFields().isEmpty())
    }

    @Test
    fun `null array degrades to empty`() {
        val nullArr: JSONArray? = null
        val h: List<CustomHeader> = nullArr?.parseKnobHeaders() ?: emptyList()
        val b: List<CustomBodyField> = nullArr?.parseKnobBodyFields() ?: emptyList()
        assertEquals(emptyList<CustomHeader>(), h)
        assertEquals(emptyList<CustomBodyField>(), b)
    }

    @Test
    fun `dispatcher-shaped json decodes with identity`() {
        // Same shape ModelExecutionDispatcher.buildRequestJson emits.
        val request = JSONObject().apply {
            put("custom_headers", JSONArray().apply {
                put(JSONObject().put("name", "X-Title").put("value", "Minis"))
            })
            put("custom_body_fields", JSONArray().apply {
                put(JSONObject().put("key", "thinking").put("value_json", """{"budget":4096}"""))
            })
        }
        val headersArr = request.optJSONArray("custom_headers")
        val bodiesArr = request.optJSONArray("custom_body_fields")
        assertNotNull(headersArr)
        assertNotNull(bodiesArr)
        val headers = headersArr!!.parseKnobHeaders()
        val bodies = bodiesArr!!.parseKnobBodyFields()
        assertEquals(listOf(CustomHeader("X-Title", "Minis")), headers)
        assertEquals(listOf(CustomBodyField("thinking", """{"budget":4096}""")), bodies)
    }
}
