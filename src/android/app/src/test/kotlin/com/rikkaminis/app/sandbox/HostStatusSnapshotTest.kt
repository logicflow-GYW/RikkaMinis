package com.rikkaminis.app.sandbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM-pure tests for the host status snapshot renderer (feat/host-status-snapshot).
 *
 * Scope: [buildStatusJson] only — the collection/heartbeat half of
 * HostStatusSnapshot is Android-bound and is covered by the static wiring probe
 * plus CI, not here.
 *
 * The invariant these cases defend: a reading the host could not take must
 * serialize as `null`. A fabricated default (0 % battery, 0 bytes free,
 * "not charging") is worse than a hole, because guest scripts threshold on the
 * value and would act on a lie.
 */
class HostStatusSnapshotTest {

    private companion object {
        /** 2026-11-01T00:00:00.123Z — arbitrary, only used for round-trip. */
        const val NOW_MS = 1_793_491_200_123L
        val FULL = StatusInputs(
            batteryPct = 87,
            charging = true,
            batteryTempC = 31.2,
            storageFreeBytes = 12_345_678_901L,
            storageTotalBytes = 123_456_789_012L,
            network = "wifi",
            doze = false,
            interactive = true,
            uptimeMs = 987_654_321L,
        )
        val ALL_NULL = StatusInputs(
            batteryPct = null,
            charging = null,
            batteryTempC = null,
            storageFreeBytes = null,
            storageTotalBytes = null,
            network = null,
            doze = null,
            interactive = null,
            uptimeMs = 0L,
        )
    }

    private fun render(inputs: StatusInputs, nowMs: Long = NOW_MS): JSONObject =
        JSONObject(buildStatusJson(inputs, nowMs))

    /** Case 1 — every documented field present and carrying the collected value. */
    @Test
    fun fullSnapshotCarriesEveryDocumentedField() {
        val json = render(FULL)
        assertEquals(
            setOf("schema", "updated_at", "battery", "storage", "network", "doze", "interactive", "uptime_ms"),
            json.keySet(),
        )
        assertEquals(1, json.getInt("schema"))
        assertEquals(
            setOf("pct", "charging", "temp_c"),
            json.getJSONObject("battery").keySet(),
        )
        assertEquals(
            setOf("free_bytes", "total_bytes"),
            json.getJSONObject("storage").keySet(),
        )
        assertEquals(87, json.getJSONObject("battery").getInt("pct"))
        assertTrue(json.getJSONObject("battery").getBoolean("charging"))
        assertEquals(31.2, json.getJSONObject("battery").getDouble("temp_c"), 0.0001)
        assertEquals(12_345_678_901L, json.getJSONObject("storage").getLong("free_bytes"))
        assertEquals(123_456_789_012L, json.getJSONObject("storage").getLong("total_bytes"))
        assertEquals("wifi", json.getString("network"))
        assertFalse(json.getBoolean("doze"))
        assertTrue(json.getBoolean("interactive"))
        assertEquals(987_654_321L, json.getLong("uptime_ms"))
        assertEquals(NOW_MS, Instant.parse(json.getString("updated_at")).toEpochMilli())
    }

    /**
     * Case 2 — nothing readable: renders, and every unavailable field is `null`
     * (present-but-null, not absent). This is the arm the mutation test breaks
     * by defaulting `charging` to a non-null `false`.
     */
    @Test
    fun missingReadingsSerializeAsNullNotNullAsDefaults() {
        val json = render(ALL_NULL)
        for (key in listOf("network", "doze", "interactive")) {
            assertTrue("$key must be present", json.has(key))
            assertTrue("$key must be null", json.isNull(key))
        }
        val battery = json.getJSONObject("battery")
        val storage = json.getJSONObject("storage")
        for (key in listOf("pct", "charging", "temp_c")) {
            assertTrue("battery.$key must be present", battery.has(key))
            assertTrue("battery.$key must be null", battery.isNull(key))
        }
        for (key in listOf("free_bytes", "total_bytes")) {
            assertTrue("storage.$key must be present", storage.has(key))
            assertTrue("storage.$key must be null", storage.isNull(key))
        }
    }

    /** Case 3 — both ends of the percentage range survive the round-trip. */
    @Test
    fun batteryBoundsArePreserved() {
        val empty = render(FULL.copy(batteryPct = 0, charging = false))
        assertEquals(0, empty.getJSONObject("battery").getInt("pct"))
        assertFalse(empty.getJSONObject("battery").getBoolean("charging"))

        val full = render(FULL.copy(batteryPct = 100, charging = true))
        assertEquals(100, full.getJSONObject("battery").getInt("pct"))
    }

    /** Case 4 — 0/0 storage stays 0/0: no division, no clamp to a negative. */
    @Test
    fun zeroStorageStaysAtZero() {
        val json = render(FULL.copy(storageFreeBytes = 0L, storageTotalBytes = 0L))
        val storage = json.getJSONObject("storage")
        assertEquals(0L, storage.getLong("free_bytes"))
        assertEquals(0L, storage.getLong("total_bytes"))
        assertFalse(storage.isNull("free_bytes"))
        assertFalse(storage.isNull("total_bytes"))
    }

    /**
     * Case 5 — numeric formatting is locale-independent (a comma decimal
     * separator would make the file unparseable) and text values are not mangled.
     */
    @Test
    fun decimalPointIsAlwaysDotAndTextRoundTrips() {
        val raw = buildStatusJson(FULL.copy(batteryTempC = 31.25, network = "未知/é"), NOW_MS)
        assertTrue("decimal separator must be '.'", raw.contains("31.25"))
        assertFalse("no locale comma separator", raw.contains("31,25"))
        val json = JSONObject(raw)
        assertEquals(31.25, json.getJSONObject("battery").getDouble("temp_c"), 0.000001)
        assertEquals("未知/é", json.getString("network"))
    }

    /** Case 6 — the schema marker is a constant, not derived from the clock. */
    @Test
    fun schemaStaysOneAcrossRenders() {
        assertEquals(1, render(FULL).getInt("schema"))
        assertEquals(1, render(ALL_NULL, 0L).getInt("schema"))
        assertEquals(HOST_STATUS_SCHEMA, render(FULL).getInt("schema"))
    }
}
