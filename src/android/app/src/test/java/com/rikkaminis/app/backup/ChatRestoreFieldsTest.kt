package com.rikkaminis.app.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FULLSCAN-1119 A / F-20] Tri-state reads for the nullable columns Stage 8
 * restores.
 *
 * The defect was one shape three times — `if (has(key)) optX(key) else null` —
 * where `has()` is TRUE for an explicit JSON null and `optLong`/`optString`
 * then fold it to `0`/`""`. A restore of a backup that spelled the null out
 * (this app's own exporter writes `JSONObject.NULL` for an unpinned session)
 * came back pinned at the epoch, with an empty thinking override, and with an
 * empty-but-present reasoning blob.
 *
 * Literal expectations only: the point of the fix is the exact value on each of
 * the three shapes (absent / explicit null / real value), so a rule that folds
 * any two of them together must go red here.
 */
class ChatRestoreFieldsTest {

    // ── the rule itself ──────────────────────────────────────────────────

    @Test
    fun absentKeyIsNull() {
        val o = JSONObject().put("title", "t")
        assertNull(o.optLongOrNullIfPresent("pinnedAt"))
        assertNull(o.optStringOrNullIfPresent("thinkingOverride"))
    }

    @Test
    fun explicitJsonNullIsNull() {
        val o = JSONObject()
            .put("pinnedAt", JSONObject.NULL)
            .put("thinkingOverride", JSONObject.NULL)
        assertNull(o.optLongOrNullIfPresent("pinnedAt"))
        assertNull(o.optStringOrNullIfPresent("thinkingOverride"))
    }

    @Test
    fun realValuesSurvive() {
        val o = JSONObject()
            .put("pinnedAt", 1790843400000L)
            .put("thinkingOverride", "high")
        assertEquals(1790843400000L, o.optLongOrNullIfPresent("pinnedAt"))
        assertEquals("high", o.optStringOrNullIfPresent("thinkingOverride"))
    }

    @Test
    fun zeroAndEmptyStringAreValuesNotAbsences() {
        // A present 0 / "" is a WRITTEN value; folding it into null would break
        // the opposite direction (an explicit "pinned at epoch"/"empty
        // override" round-trips as itself).
        val o = JSONObject().put("pinnedAt", 0L).put("thinkingOverride", "")
        assertEquals(0L, o.optLongOrNullIfPresent("pinnedAt"))
        assertEquals("", o.optStringOrNullIfPresent("thinkingOverride"))
    }

    // ── the session scalars ──────────────────────────────────────────────

    @Test
    fun sessionScalarsFoldAbsentAndNullTogetherButKeepRealValues() {
        val absent = ChatRestoreFields.readSessionScalars(JSONObject().put("id", "s1"))
        assertNull(absent.pinnedAt)
        assertNull(absent.thinkingOverride)

        val explicitNull = ChatRestoreFields.readSessionScalars(
            JSONObject().put("id", "s1")
                .put("pinnedAt", JSONObject.NULL)
                .put("thinkingOverride", JSONObject.NULL),
        )
        assertNull(explicitNull.pinnedAt)
        assertNull(explicitNull.thinkingOverride)

        val real = ChatRestoreFields.readSessionScalars(
            JSONObject().put("id", "s1")
                .put("pinnedAt", 1790843400000L)
                .put("thinkingOverride", "medium"),
        )
        assertEquals(1790843400000L, real.pinnedAt)
        assertEquals("medium", real.thinkingOverride)
    }

    // ── the message blob ─────────────────────────────────────────────────

    @Test
    fun reasoningContentAbsentOrNullIsNoReasoning() {
        assertNull(ChatRestoreFields.readReasoningContent(JSONObject().put("id", "m1")))
        assertNull(
            ChatRestoreFields.readReasoningContent(
                JSONObject().put("id", "m1").put("reasoningContent", JSONObject.NULL),
            )
        )
    }

    @Test
    fun reasoningContentRealValueIsReturned() {
        assertEquals(
            "thinking…",
            ChatRestoreFields.readReasoningContent(
                JSONObject().put("id", "m1").put("reasoningContent", "thinking…"),
            ),
        )
    }
}
