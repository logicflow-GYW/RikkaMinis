package com.rikkaminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            // [fix/tool-schema-required-empty-array] Unconditional, not
            // present-only. 2026-09-22 field report: agentrouter.org's strict
            // schema validator 400s the ONE parameter-less tool (memory_rollup,
            // `parameters = emptyMap()`) with
            //   `Invalid schema for function 'memory_rollup': null is not of type "array"`
            // — the validator does `schema.get("required")` internally, treats a
            // MISSING key as null, then validates that null against "array".
            // llmhost.net and the other relays in daily traffic treat a missing
            // key as optional, which is why this only ever fired on one gateway.
            // An explicit empty array is valid JSON Schema and byte-identical in
            // meaning, so this is safe for every lenient validator too.
            put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
    }

    fun toGeminiJson(): JSONObject = JSONObject().apply {
        put("type", type.uppercase())
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
    }
}
