package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [T-model-exec-strict-enum] Pins the strict-parse contract of
 * ModelExecutionService.safeEnum without dragging in Android / Service.
 * The production function is private; we test via the internal
 * UnknownEnumValueException type and a same-signature copy of the
 * inline logic (inline functions can't be called cross-module from
 * tests without re-compilation, so we mirror the body).
 *
 * [T-thinking-off-omitted-key] Also pins the worker-side contract for an
 * omitted `thinking_level` key: the dispatcher intentionally skips the key
 * when ThinkingLevel.OFF, and the worker must decode that ABSENCE as OFF —
 * never route it through strictEnum (which would throw on the optString
 * "" placeholder and kill every thinking-off turn; regression pinned from
 * the 2026-09-02 "unknown t0 value: " user log).
 */
class ModelExecutionSafeEnumTest {

    // Mirror of the production safeEnum body for JVM testing.
    private inline fun <reified T : Enum<T>> strictEnum(name: String): T =
        try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (_: IllegalArgumentException) {
            throw UnknownEnumValueException(T::class.java.simpleName, name)
        }

    // Mirror of the production worker-side thinking_level decode from
    // ModelExecutionService.executeStreamingRun (the fixed branch).
    private fun decodeThinkingLevel(raw: String): com.openminis.app.data.model.ThinkingLevel =
        if (raw.isEmpty()) com.openminis.app.data.model.ThinkingLevel.OFF
        else strictEnum(raw)

    // Mirror of JSONObject.optString's missing-key behaviour (org.json):
    // an absent key yields "" — the precondition that made the old code
    // treat "key omitted" as "unknown enum value".
    private fun optStringPlaceholder(hasKey: Boolean): String = if (hasKey) "MEDIUM" else ""

    enum class Sample { A, B, C }

    @Test
    fun `known value parses`() {
        assertEquals(Sample.B, strictEnum<Sample>("B"))
    }

    @Test
    fun `unknown value throws UnknownEnumValueException`() {
        val ex = assertThrows(UnknownEnumValueException::class.java) {
            strictEnum<Sample>("D")
        }
        assertEquals("Sample", ex.enumClass)
        assertEquals("D", ex.unknownValue)
    }

    @Test
    fun `empty string throws UnknownEnumValueException`() {
        assertThrows(UnknownEnumValueException::class.java) {
            strictEnum<Sample>("")
        }
    }

    @Test
    fun `omitted thinking_level key decodes to OFF not a throw`() {
        // Dispatcher omits the key for OFF; worker's optString gives "".
        assertEquals(
            com.openminis.app.data.model.ThinkingLevel.OFF,
            decodeThinkingLevel(optStringPlaceholder(hasKey = false)),
        )
    }

    @Test
    fun `present thinking_level value still parses through strictEnum`() {
        assertEquals(
            com.openminis.app.data.model.ThinkingLevel.MEDIUM,
            decodeThinkingLevel(optStringPlaceholder(hasKey = true)),
        )
    }

    @Test
    fun `present but unknown thinking_level value still throws`() {
        // The strict cross-version contract must survive this fix: a NEWER
        // enum case serialized by a main-process-only build is still a hard
        // fail-fast, not a silent fallback to OFF.
        assertThrows(UnknownEnumValueException::class.java) {
            decodeThinkingLevel("SOME_FUTURE_CASE")
        }
    }
}
