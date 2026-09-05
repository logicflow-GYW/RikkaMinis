package com.openminis.app.data

import com.openminis.app.data.model.CustomBodyField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-provider-extra-body] Merge user-authored chat body fields into the request
 * body AFTER the provider has finished building it (RikkaHub parity).
 *
 * Semantics:
 *  • User keys WIN over provider defaults (same-name replace, recursive merge
 *    when both sides are objects).
 *  • Dotted paths ("generationConfig.temperature") create intermediate objects
 *    so Gemini-style nested bodies are reachable without writing whole JSON.
 *  • Invalid JSON in a field is silently dropped (an escape hatch that cannot
 *    parse must not wipe the request).
 *  • [forceModel] is restored AFTER the merge — a user-authored "model" key
 *    must never change which model the router is billing/streaming against.
 */
fun JSONObject.mergeCustomBody(
    fields: List<CustomBodyField>,
    json: Json,
    forceModel: String? = null,
) {
    if (fields.isEmpty()) {
        if (forceModel != null) put("model", forceModel)
        return
    }
    for (field in fields) {
        val key = field.key.trim()
        if (key.isEmpty()) continue
        val parsed = runCatching { json.parseToJsonElement(field.valueJson) }.getOrNull()
            ?: continue
        val newValue = parsed.toJSONValue() ?: continue

        val segments = key.split('.')
        if (segments.size > 1) {
            var cursor = this
            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                val next = cursor.optJSONObject(seg)
                if (next != null) cursor = next
                else {
                    val created = JSONObject()
                    cursor.put(seg, created)
                    cursor = created
                }
            }
            val leaf = segments.last()
            val existing = cursor.opt(leaf)
            if (existing is JSONObject && newValue is JSONObject) {
                mergeJSONObjects(existing, newValue)
            } else {
                cursor.put(leaf, newValue)
            }
        } else {
            val existing = opt(key)
            if (existing is JSONObject && newValue is JSONObject) {
                mergeJSONObjects(existing, newValue)
            } else {
                put(key, newValue)
            }
        }
    }
    if (forceModel != null) put("model", forceModel)
}

// ---- kotlinx → org.json converters ----

private fun JsonElement.toJSONValue(): Any? = when (this) {
    is JsonObject -> toJSONObject()
    is JsonArray -> toJSONArray()
    is JsonPrimitive -> toJSONPrimitive()
    JsonNull -> null
}

private fun JsonObject.toJSONObject(): JSONObject {
    val out = JSONObject()
    for ((k, v) in this) {
        val converted = v.toJSONValue()
        if (converted != null) out.put(k, converted)
    }
    return out
}

private fun JsonArray.toJSONArray(): JSONArray {
    val out = JSONArray()
    for (v in this) out.put(v.toJSONValue())
    return out
}

private fun JsonPrimitive.toJSONPrimitive(): Any = when {
    isString -> content
    content == "true" -> true
    content == "false" -> false
    else -> content.toDoubleOrNull() ?: content.toLongOrNull() ?: content
}

private fun mergeJSONObjects(target: JSONObject, source: JSONObject) {
    for (k in source.keys()) {
        val srcV = source.opt(k)
        val dstV = target.opt(k)
        if (srcV is JSONObject && dstV is JSONObject) mergeJSONObjects(dstV, srcV)
        else target.put(k, srcV)
    }
}
