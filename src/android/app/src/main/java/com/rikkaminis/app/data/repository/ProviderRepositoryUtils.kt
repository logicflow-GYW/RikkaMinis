package com.rikkaminis.app.data.repository

import org.json.JSONObject

/**
 * Pure utility functions extracted from [ProviderRepository] so they can be
 * JVM-unit-tested without Android dependencies.
 *
 * Each function's original location in [ProviderRepository] is noted.
 */

// Modality bitfield constants (private in ProviderRepository, replicated here
// so the extracted pure functions are self-contained).
private const val MODALITY_BIT_TEXT_IN = 1 shl 0
private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
private const val MODALITY_BIT_IMG_IN = 1 shl 2
private const val MODALITY_BIT_PDF_IN = 1 shl 3
private const val MODALITY_BIT_AUD_IN = 1 shl 4
private const val MODALITY_BIT_VID_IN = 1 shl 5
private const val MODALITY_BIT_IMG_OUT = 1 shl 6
private const val MODALITY_BIT_AUD_OUT = 1 shl 7
private const val MODALITY_BIT_VID_OUT = 1 shl 8

/**
 * (was ProviderRepository.hashJsonMirror)
 * SHA-256 hex digest of a string. Used to detect whether the JSON mirror
 * file on disk has changed.
 */
internal fun hashJsonMirror(str: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
    return buildString(digest.size * 2) {
        for (b in digest) {
            val v = b.toInt() and 0xFF
            append(Character.forDigit(v shr 4, 16))
            append(Character.forDigit(v and 0xF, 16))
        }
    }
}

/**
 * (was ProviderRepository.isSameCalendarDay)
 * Check whether two timestamps (millis) fall on the same calendar day.
 * Uses the system timezone.
 */
internal fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = aMs
    val aYear = cal.get(java.util.Calendar.YEAR)
    val aDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = bMs
    return aYear == cal.get(java.util.Calendar.YEAR)
        && aDay == cal.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * (was ProviderRepository.modalityBitfieldFromLists)
 * Encode input/output modality string lists into a bitfield.
 */
internal fun modalityBitfieldFromLists(
    inputs: List<String>?,
    outputs: List<String>?,
): Int {
    var bits = 0
    inputs?.forEach { raw ->
        when (raw.lowercase()) {
            "text" -> bits = bits or MODALITY_BIT_TEXT_IN
            "image" -> bits = bits or MODALITY_BIT_IMG_IN
            "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
            "audio" -> bits = bits or MODALITY_BIT_AUD_IN
            "video" -> bits = bits or MODALITY_BIT_VID_IN
        }
    }
    outputs?.forEach { raw ->
        when (raw.lowercase()) {
            "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
            "image" -> bits = bits or MODALITY_BIT_IMG_OUT
            "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
            "video" -> bits = bits or MODALITY_BIT_VID_OUT
        }
    }
    return bits
}

/**
 * (was ProviderRepository.modalityListsFromBitfield)
 * Decode a bitfield back into input/output modality string lists.
 * Returns null pair when the bitfield is 0 (no modality info).
 */
internal fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
    if (bits == 0) return null to null
    val inputs = buildList {
        if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
        if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
        if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
        if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
        if (bits and MODALITY_BIT_VID_IN != 0) add("video")
    }
    val outputs = buildList {
        if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
        if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
        if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
        if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
    }
    return inputs.ifEmpty { null } to outputs.ifEmpty { null }
}

/**
 * (was ProviderRepository.readModalitiesWithBitfieldFallback)
 * Read modality info from a JSON object.
 * Returns (inputs, outputs):
 *   - native `inputModalities` / `outputModalities` list keys take precedence
 *   - if neither list is present, decode iOS's `modalityOverride` bitfield
 *   - if neither shape is present, returns null pair
 */
internal fun readModalitiesWithBitfieldFallback(
    obj: JSONObject,
): Pair<List<String>?, List<String>?> {
    val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
    }
    val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
    }
    if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
    if (!obj.has("modalityOverride")) return null to null
    val bits = obj.optInt("modalityOverride", 0)
    return modalityListsFromBitfield(bits)
}