package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-preflight-enum-and-type] Preflight now consumes the `type` and `enum`
 * fields that [AgentToolParam] has always published into the model schema
 * (`AgentToolParam.toJson`) but that the validator never read.
 *
 * Both families guarded here are silent-divergence bugs, which this project
 * treats as worse than an error:
 *
 *  * an off-enum value was resolved by the tool's own fallback — memory_get's
 *    `scope` only takes the all-logs branch when it equals exactly "all"
 *    (MemoryRepository), so "ALL" searched dailies only while the answer still
 *    looked complete;
 *  * a scalar parameter handed an object/array was `toString()`-ed into its own
 *    JSON text by ToolJsonRepair, travelling on as a plausible-looking value
 *    that failed much later, far from the cause.
 *
 * The negative cases matter as much as the positive ones — a false positive
 * refuses a healthy call — so both directions are pinned.
 */
class ToolSchemaValidationTest {

    private fun param(
        desc: String,
        type: String = "string",
        enumValues: List<String>? = null,
    ) = AgentToolParam(type = type, description = desc, enumValues = enumValues)

    private val memoryGet = AgentToolDefinition(
        name = "memory_get",
        description = "Search memory",
        parameters = mapOf(
            "query" to param("Query"),
            "scope" to param("Scope", enumValues = listOf("daily", "all")),
        ),
        required = listOf("query"),
    )

    private val fileEdit = AgentToolDefinition(
        name = "file_edit",
        description = "Edit a file",
        parameters = mapOf(
            "path" to param("File path"),
            "old_string" to param("Text to replace"),
            "new_string" to param("Replacement"),
        ),
        required = listOf("path", "old_string", "new_string"),
    )

    private val shellExecute = AgentToolDefinition(
        name = "shell_execute",
        description = "Run a command",
        parameters = mapOf(
            "command" to param("Command"),
            "timeout" to param("Seconds", type = "integer"),
        ),
        required = listOf("command"),
    )

    private val tools = listOf(memoryGet, fileEdit, shellExecute)

    private fun validate(name: String, json: String): String? =
        ChatViewModel.preflightValidateToolCallImpl(name, JSONObject(json), tools)

    // ── enum membership ──

    @Test
    fun `an on-list enum value is accepted`() {
        assertNull(validate("memory_get", """{"query":"x","scope":"all"}"""))
        assertNull(validate("memory_get", """{"query":"x","scope":"daily"}"""))
    }

    @Test
    fun `an off-list enum value is refused`() {
        val err = validate("memory_get", """{"query":"x","scope":"ALL"}""")
        assertNotNull(err)
        assertTrue("got: $err", err!!.contains("scope"))
        assertTrue("got: $err", err!!.contains("daily"))
        assertTrue("got: $err", err!!.contains("all"))
    }

    @Test
    fun `an optional enum field is checked, not only required ones`() {
        // `scope` is NOT in the required list — gating this check on required
        // would leave the motivating case unguarded.
        assertNotNull(validate("memory_get", """{"query":"x","scope":"everything"}"""))
    }

    @Test
    fun `an absent or null enum field is not an error`() {
        assertNull(validate("memory_get", """{"query":"x"}"""))
        assertNull(validate("memory_get", """{"query":"x","scope":null}"""))
    }

    // ── structural type: scalar vs container ──

    @Test
    fun `an object handed to a scalar parameter is refused`() {
        val err = validate("file_edit", """{"path":{"a":1},"old_string":"x","new_string":"y"}""")
        assertNotNull(err)
        assertTrue("got: $err", err!!.contains("path"))
        assertTrue("got: $err", err!!.contains("object"))
    }

    @Test
    fun `an array handed to a scalar parameter is refused`() {
        val err = validate("file_edit", """{"path":["/a.txt"],"old_string":"x","new_string":"y"}""")
        assertNotNull(err)
        assertTrue("got: $err", err!!.contains("array"))
    }

    @Test
    fun `a scalar-only mismatch is still accepted`() {
        // Only containers are refused. `{"command": 30}` arrives here as a number
        // when coercion did not run, and a numeric field fed a numeral string
        // must keep working — org.json reports Integer/Long/Double/BigDecimal by
        // parse path, so policing those would refuse calls the repair step is
        // built to accept.
        assertNull(validate("shell_execute", """{"command":30}"""))
        assertNull(validate("shell_execute", """{"command":"ls","timeout":"30"}"""))
        assertNull(validate("shell_execute", """{"command":"ls","timeout":30}"""))
    }

    // ── [fix/tool-schema-required-empty-array] wire shape ──

    @Test
    fun `parameter-less tool still emits an empty required array on the wire`() {
        // 2026-09-22 field report: agentrouter.org's strict schema validator
        // 400s a schema whose `required` key is MISSING — it resolves the key
        // to null internally, then validates null against "array":
        //   Invalid schema for function 'memory_rollup': null is not of type "array"
        // memory_rollup is the only parameter-less tool, which is why every
        // other tool passed the same gateway. The empty array must be explicit.
        val rollup = AgentToolDefinition(
            name = "memory_rollup",
            description = "Distill stable rules from old daily logs.",
            parameters = emptyMap(),
        )
        val schema = rollup.toOpenAIJson()
            .getJSONObject("function")
            .getJSONObject("parameters")
        assertTrue(
            "parameters.required must exist and be an array — got: $schema",
            schema.has("required") && schema.get("required") is org.json.JSONArray,
        )
        assertTrue(
            "required must be empty for a parameter-less tool",
            schema.getJSONArray("required").length() == 0,
        )
    }

    @Test
    fun `required array lists the declared mandatory parameters`() {
        val schema = memoryGet.toOpenAIJson()
            .getJSONObject("function")
            .getJSONObject("parameters")
        val req = schema.getJSONArray("required")
        assertTrue("got: $req", req.length() == 1 && req.getString(0) == "query")
    }
}
