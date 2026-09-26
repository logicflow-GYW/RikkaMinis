package com.rikkaminis.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the ORDER inside the rotation wrapper's catch block: the spent
 * credential must be parked BEFORE the router is asked for a successor.
 *
 * Why it matters (and why this test exists): [GroupRouter.rotateCredential] walks
 * the health map and treats an unrecorded route as usable, so a failure that was
 * never recorded makes the walk offer that very slot back. With two keys that is
 * a straight bounce — A fails, rotate to B, B fails, rotate back to A — and the
 * sibling the user paid for never gets a second look. Recording only when
 * rotation was exhausted (the pre-fix order) left exactly that hole; fixing the
 * predicate without fixing the order would have made it reachable for the first
 * time.
 */
class CredentialRotationParkTest {

    private var now = 1_000_000L
    private fun router() = GroupRouter(clock = { now })

    @Test
    fun anUnrecordedFailureIsHandedStraightBackByTheWalk() {
        val router = router()
        // Negative control: nothing recorded for slot 1, so the walk from slot 0
        // offers slot 1 — the slot that already failed.
        assertEquals(
            "健康度盲走会把刚失败的槽位原地还给你（这就是 park 必须在前的原因）",
            1,
            router.rotateCredential("entry", fromIndex = 0, keyCount = 2),
        )
    }

    @Test
    fun parkingTheSpentCredentialFirstKeepsItOutOfTheNextWalk() {
        val router = router()
        router.recordResult(router.routeId("entry", 1), RouteOutcome.QuotaExhausted)
        assertNull(
            "已 park 的槽位不得再被选中（否则重试会再打一次死密钥）",
            router.rotateCredential("entry", fromIndex = 0, keyCount = 2),
        )
    }

    @Test
    fun aSpentCredentialDoesNotTakeItsSiblingDown() {
        val router = router()
        router.recordResult(router.routeId("entry", 1), RouteOutcome.QuotaExhausted)
        assertEquals(
            "被 park 的只是那把用尽的，走查要越过它找到还健康的兄弟槽",
            2,
            router.rotateCredential("entry", fromIndex = 0, keyCount = 3),
        )
        router.recordResult(router.routeId("entry", 2), RouteOutcome.QuotaExhausted)
        assertNull(
            "兄弟槽也用尽后必须收手（否则重试会再打一次死密钥）",
            router.rotateCredential("entry", fromIndex = 0, keyCount = 3),
        )
    }
}
