package com.rikkaminis.app.sandbox.offload

import org.json.JSONObject

/**
 * Pure data model for a file-backed payload in the model-exec protocol.
 *
 * Instead of inlining media / big ToolResult as Base64 into the JSON
 * request or result, producer writes the blob to the run directory and
 * emits a [BytesFileRef] (relative path + mime + size + sha256). The
 * consumer validates the path is inside the run dir (via [RunFileGuard]),
 * reads it back bounded, and checks sha256 for end-to-end integrity.
 *
 * No Android dependencies — pure JVM + org.json (already on the classpath
 * for the offload package). JSON codec is hand-rolled via org.json so the
 * model stays free of kotlinx.serialization, matching the rest of this
 * package's convention.
 */
data class BytesFileRef(
    /** Path relative to the run directory root (e.g. `media/0.png`). */
    val relativePath: String,
    /** MIME type of the payload, or empty if unknown. */
    val mime: String,
    /** Exact byte length of the referenced file. */
    val size: Long,
    /** Lowercase hex SHA-256 of the file content (verify on read). */
    val sha256: String,
) {
    init {
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(!relativePath.startsWith('/')) { "relativePath must be relative, got: $relativePath" }
        require(size >= 0L) { "size must be >= 0" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", "file_ref")
        put("path", relativePath)
        put("mime", mime)
        put("size", size)
        put("sha256", sha256)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val KIND = "file_ref"

        /**
         * Parse a [BytesFileRef] from a JSON object. Returns null when the
         * object is not a file ref or required fields are missing/invalid.
         */
        fun fromJson(obj: JSONObject): BytesFileRef? {
            if (obj.optString("kind", "") != KIND) return null
            val path = obj.optString("path", "")
            if (path.isBlank() || path.startsWith('/')) return null
            val size = obj.optLong("size", -1L)
            if (size < 0L) return null
            val sha = obj.optString("sha256", "")
            if (sha.isBlank()) return null
            return BytesFileRef(
                relativePath = path,
                mime = obj.optString("mime", ""),
                size = size,
                sha256 = sha,
            )
        }

        /** Parse from an already-serialized JSON string. */
        fun fromJsonString(json: String): BytesFileRef? =
            try { fromJson(JSONObject(json)) } catch (_: Exception) { null }
    }
}