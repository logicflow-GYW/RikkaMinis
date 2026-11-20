package com.rikkaminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for the real approved-time array mutation used by ConfigBridge. */
class ConfigArrayBatchApplyTest {

    private data class AppliedReceipt(val old: String, val new: String)

    private fun runApproved(
        initial: List<String>,
        operations: List<Pair<ConfigArrayOperation, String>>,
        approved: Set<Int> = operations.indices.toSet(),
    ): Pair<List<String>, List<AppliedReceipt>> {
        var current: ConfigValue = ConfigValue.Arr(initial.map(ConfigValue::Str))
        val receipts = ArrayList<AppliedReceipt>()
        operations.forEachIndexed { index, (operation, element) ->
            if (index !in approved) return@forEachIndexed
            val mutation = applyConfigArrayOperation(
                readCurrent = { current },
                write = { current = it },
                operation = operation,
                operand = ConfigValue.Str(element),
                notFoundReason = "missing $element",
            )
            receipts += AppliedReceipt(
                old = mutation.previous.jsonString(),
                new = mutation.applied.jsonString(),
            )
        }
        val values = (current as ConfigValue.Arr).value.map { (it as ConfigValue.Str).value }
        return values to receipts
    }

    @Test
    fun twoAppendsApplyInOrderAndReceiptsDescribeEachActualWrite() {
        val (values, receipts) = runApproved(
            initial = listOf("base"),
            operations = listOf(
                ConfigArrayOperation.APPEND to "first",
                ConfigArrayOperation.APPEND to "second",
            ),
        )
        assertEquals(listOf("base", "first", "second"), values)
        assertEquals(
            listOf(
                AppliedReceipt("[\"base\"]", "[\"base\",\"first\"]"),
                AppliedReceipt("[\"base\",\"first\"]", "[\"base\",\"first\",\"second\"]"),
            ),
            receipts,
        )
    }

    @Test
    fun twoRemovesDoNotRestoreTheEarlierRemovedElement() {
        val (values, receipts) = runApproved(
            initial = listOf("first", "second", "keep"),
            operations = listOf(
                ConfigArrayOperation.REMOVE to "first",
                ConfigArrayOperation.REMOVE to "second",
            ),
        )
        assertEquals(listOf("keep"), values)
        assertEquals(
            listOf(
                AppliedReceipt("[\"first\",\"second\",\"keep\"]", "[\"second\",\"keep\"]"),
                AppliedReceipt("[\"second\",\"keep\"]", "[\"keep\"]"),
            ),
            receipts,
        )
    }

    @Test
    fun onlyFirstApprovedRowApplies() {
        val (values, receipts) = runApproved(
            initial = listOf("base"),
            operations = listOf(
                ConfigArrayOperation.APPEND to "first",
                ConfigArrayOperation.APPEND to "rejected",
            ),
            approved = setOf(0),
        )
        assertEquals(listOf("base", "first"), values)
        assertEquals(1, receipts.size)
        assertEquals("[\"base\",\"first\"]", receipts.single().new)
    }

    @Test
    fun onlySecondApprovedRowDoesNotInheritRejectedPreview() {
        val (values, receipts) = runApproved(
            initial = listOf("base"),
            operations = listOf(
                ConfigArrayOperation.APPEND to "rejected",
                ConfigArrayOperation.APPEND to "second",
            ),
            approved = setOf(1),
        )
        assertEquals(listOf("base", "second"), values)
        assertEquals(1, receipts.size)
        assertEquals("[\"base\",\"second\"]", receipts.single().new)
    }

    @Test
    fun allRejectedRowsLeaveValueAndReceiptsUntouched() {
        val (values, receipts) = runApproved(
            initial = listOf("base"),
            operations = listOf(
                ConfigArrayOperation.APPEND to "first",
                ConfigArrayOperation.REMOVE to "base",
            ),
            approved = emptySet(),
        )
        assertEquals(listOf("base"), values)
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun approvedMixedSequenceUsesEachPreviousApprovedResult() {
        val (values, receipts) = runApproved(
            initial = listOf("remove", "stay"),
            operations = listOf(
                ConfigArrayOperation.APPEND to "added",
                ConfigArrayOperation.REMOVE to "remove",
                ConfigArrayOperation.APPEND to "last",
            ),
        )
        assertEquals(listOf("stay", "added", "last"), values)
        assertEquals("[\"remove\",\"stay\",\"added\"]", receipts[1].old)
        assertEquals("[\"stay\",\"added\"]", receipts[1].new)
        assertEquals("[\"stay\",\"added\",\"last\"]", receipts[2].new)
    }

    @Test
    fun stateChangedWhileWaitingForApprovalIsReadAtApplyTime() {
        var current: ConfigValue = ConfigValue.Arr(listOf(ConfigValue.Str("base")))
        val preview = (current as ConfigValue.Arr).jsonString()
        current = ConfigValue.Arr(listOf(ConfigValue.Str("base"), ConfigValue.Str("external")))
        val mutation = applyConfigArrayOperation(
            readCurrent = { current },
            write = { current = it },
            operation = ConfigArrayOperation.APPEND,
            operand = ConfigValue.Str("approved"),
            notFoundReason = "missing",
        )
        assertEquals("[\"base\"]", preview)
        assertEquals("[\"base\",\"external\"]", mutation.previous.jsonString())
        assertEquals("[\"base\",\"external\",\"approved\"]", mutation.applied.jsonString())
        assertEquals(mutation.applied, current)
    }

    @Test
    fun removeUsesTheValueThatExistsWhenApprovalIsApplied() {
        var current: ConfigValue = ConfigValue.Arr(listOf(ConfigValue.Str("remove"), ConfigValue.Str("keep")))
        val preview = current
        current = ConfigValue.Arr(listOf(ConfigValue.Str("keep"), ConfigValue.Str("external")))
        val mutation = applyConfigArrayOperation(
            readCurrent = { current },
            write = { current = it },
            operation = ConfigArrayOperation.REMOVE,
            operand = ConfigValue.Str("keep"),
            notFoundReason = "missing",
        )
        assertEquals("[\"remove\",\"keep\"]", (preview as ConfigValue.Arr).jsonString())
        assertEquals("[\"keep\",\"external\"]", mutation.previous.jsonString())
        assertEquals("[\"external\"]", mutation.applied.jsonString())
    }

    @Test
    fun removeAlreadyAbsentAtApplyTimeFailsInsteadOfWritingOrReportingSuccess() {
        var writes = 0
        var current: ConfigValue = ConfigValue.Arr(listOf(ConfigValue.Str("other")))
        try {
            applyConfigArrayOperation(
                readCurrent = { current },
                write = { writes++; current = it },
                operation = ConfigArrayOperation.REMOVE,
                operand = ConfigValue.Str("removed while waiting"),
                notFoundReason = "element no longer exists",
            )
            throw AssertionError("missing approved remove must fail")
        } catch (e: ConfigArrayNotFound) {
            assertEquals("element no longer exists", e.message)
        }
        assertEquals(0, writes)
        assertEquals("[\"other\"]", (current as ConfigValue.Arr).jsonString())
    }
}
