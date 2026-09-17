package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-preflight-enum-and-type] ToolJsonRepair's Strategy 2 (type coercion on
 * required fields) must repair scalars and leave containers alone.
 *
 * `{"path": 30}` for a string field is a common, genuinely repairable
 * deviation. `{"path": {"a": 1}}` is not: `toString()` turned it into the string
 * `{"a": 1}`, which then travelled on as a path-shaped value and failed much
 * later, far from the cause. Containers are now left untouched so preflight can
 * refuse them outright — see ToolSchemaValidationTest.
 *
 * This file also gives the repair step its first test coverage: it was changed
 * here, and a silently-widened coercion is exactly the kind of regression that
 * would otherwise only surface as a strange tool failure in the field.
 */
class ToolJsonRepairTest {

    private fun param(desc: String, type: String = "string") =
        AgentToolParam(type = type, description = desc)

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

    private val tools = listOf(fileEdit)

    /** rawTail is null: Strategy 1 only fires when args is empty. */
    private fun repair(args: JSONObject): List<String> =
        ToolJsonRepair.repair("file_edit", args, null, tools)

    private fun fullArgs(pathLiteral: String) =
        JSONObject("""{"path":$pathLiteral,"old_string":"x","new_string":"y"}""")

    // ── scalars are still repaired ──

    @Test
    fun `a number is still coerced for a string field`() {
        val args = fullArgs("30")
        val repairs = repair(args)
        assertEquals("30", args.optString("path"))
        assertTrue("got: $repairs", repairs.contains("type-coerce:path"))
    }

    @Test
    fun `a boolean is still coerced for a string field`() {
        val args = fullArgs("true")
        val repairs = repair(args)
        assertEquals("true", args.optString("path"))
        assertTrue("got: $repairs", repairs.contains("type-coerce:path"))
    }

    // ── containers are left for preflight to refuse ──

    @Test
    fun `an object is left untouched`() {
        val args = fullArgs("""{"a":1}""")
        val repairs = repair(args)
        assertTrue("expected no coercion, got: $repairs", repairs.isEmpty())
        assertTrue("path must stay an object", args.opt("path") is JSONObject)
    }

    @Test
    fun `an array is left untouched`() {
        val args = fullArgs("""["/a.txt"]""")
        val repairs = repair(args)
        assertTrue("expected no coercion, got: $repairs", repairs.isEmpty())
        assertTrue("path must stay an array", args.opt("path") is JSONArray)
    }

    // ── [fix/audit0917-b8] Strategy 3 (fuzzy field move) applies the SAME
    // scalar rule — it used to move a raw Number into a string field while the
    // correctly-spelled key got coerced, so the repair depended on a typo.

    @Test
    fun `a fuzzy-matched number is coerced like its correctly-spelled twin`() {
        val args = JSONObject("""{"pth":30,"old_string":"x","new_string":"y"}""")
        val repairs = repair(args)
        assertEquals("30", args.optString("path"))
        assertTrue("got: $repairs", repairs.contains("fuzzy:pth->path"))
    }

    @Test
    fun `a fuzzy-matched container is not moved at all`() {
        val args = JSONObject("""{"pth":{"a":1},"old_string":"x","new_string":"y"}""")
        val repairs = repair(args)
        assertTrue("expected no move, got: $repairs", repairs.isEmpty())
        assertTrue("path must stay absent", !args.has("path"))
    }

    @Test
    fun `a fuzzy-matched JSON null moves verbatim, not as the string null`() {
        val args = JSONObject("""{"pth":null,"old_string":"x","new_string":"y"}""")
        val repairs = repair(args)
        assertTrue("got: $repairs", repairs.contains("fuzzy:pth->path"))
        assertEquals(JSONObject.NULL, args.opt("path"))
    }
}
