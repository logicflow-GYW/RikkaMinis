package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.routing.RouteOutcome
import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind
import com.rikkaminis.app.sandbox.offload.ModelStreamErrorException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two halves of the rotation ladder that made the multi-key feature
 * inert on real traffic while every unit test stayed green:
 *
 *  1. the failure SHAPE — the offloaded chat path only ever produces the wire
 *     shape (`ModelStreamErrorException` with a stamped `kind`, `cause = null`),
 *     never a typed [LLMError] instance, so the predicate has to read the kind;
 *  2. the DECISION — credential-scoped kind + zero chunks + attempts left +
 *     healthy sibling, and nothing else.
 */
class CredentialRotationLadderTest {

    private fun wire(kind: String) = ModelStreamErrorException(
        message = "worker reported $kind",
        hadChunks = false,
        kind = kind,
    )

    // ── shape: the wire kind is what arrives ────────────────────────────────

    @Test
    fun quotaStampedStreamErrorIsCredentialScoped() {
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            credentialScopedKind(wire(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED)),
        )
    }

    @Test
    fun rateLimitAndInvalidKeyStampsAreCredentialScoped() {
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_RATE_LIMITED,
            credentialScopedKind(wire(ChatStreamErrorPolicyKind.KIND_RATE_LIMITED)),
        )
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_INVALID_KEY,
            credentialScopedKind(wire(ChatStreamErrorPolicyKind.KIND_INVALID_KEY)),
        )
    }

    @Test
    fun endpointFaultsAreNotCredentialScoped() {
        assertNull(credentialScopedKind(wire(ChatStreamErrorPolicyKind.KIND_PROVIDER)))
        assertNull(credentialScopedKind(wire(ChatStreamErrorPolicyKind.KIND_NETWORK)))
    }

    @Test
    fun theKindIsFoundThroughWrappingCauses() {
        val wrapped = IllegalStateException("stream failed", wire(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED))
        assertEquals(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED, credentialScopedKind(wrapped))
    }

    @Test
    fun typedErrorsStillClassify() {
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            credentialScopedKind(LLMError.QuotaExhausted("insufficient_quota")),
        )
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_INVALID_KEY,
            credentialScopedKind(LLMError.InvalidApiKey("401")),
        )
        assertEquals(
            ChatStreamErrorPolicyKind.KIND_RATE_LIMITED,
            credentialScopedKind(LLMError.RateLimited(1_500L)),
        )
    }

    @Test
    fun unstampedFailuresAreNotCredentialScoped() {
        assertNull(credentialScopedKind(ModelStreamErrorException("died", hadChunks = false)))
        assertNull(credentialScopedKind(LLMError.ProviderError("500")))
        assertNull(credentialScopedKind(LLMError.NetworkError(java.io.IOException("reset"))))
    }

    // ── outcome mapping: parks under the verdict the kind implies ───────────

    @Test
    fun spentCredentialIsParkedAsQuotaExhaustedNotAsAServerFault() {
        val parked = credentialOutcomeFor(
            ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            wire(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED),
        )
        assertEquals(RouteOutcome.QuotaExhausted, parked)
        assertEquals(
            RouteOutcome.AuthError,
            credentialOutcomeFor(ChatStreamErrorPolicyKind.KIND_INVALID_KEY, null),
        )
        assertEquals(
            "typed Retry-After 要被带到结果里",
            RouteOutcome.RateLimited(2_000L),
            credentialOutcomeFor(ChatStreamErrorPolicyKind.KIND_RATE_LIMITED, LLMError.RateLimited(2_000L)),
        )
    }

    // ── decision table ─────────────────────────────────────────────────────

    @Test
    fun rotatesWhenACredentialIsSpentAndASiblingIsHealthy() {
        val decision = decideCredentialRotation(
            kind = ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            emittedContent = false,
            rotationsDone = 0,
            keyCount = 2,
        ) { 1 }
        assertEquals(RotationDecision.Rotate(1), decision)
    }

    @Test
    fun neverAsksForASiblingWhenTheFailureIsNotAboutTheCredential() {
        var asked = false
        val decision = decideCredentialRotation(
            kind = null,
            emittedContent = false,
            rotationsDone = 0,
            keyCount = 3,
        ) {
            asked = true
            1
        }
        assertEquals(RotationDecision.GiveUp, decision)
        assertFalse("非凭据类失败不得触发路由器的槽位走查（侧效应）", asked)
    }

    @Test
    fun neverRotatesAfterContentWasEmitted() {
        var asked = false
        val decision = decideCredentialRotation(
            kind = ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            emittedContent = true,
            rotationsDone = 0,
            keyCount = 3,
        ) {
            asked = true
            1
        }
        assertEquals(RotationDecision.GiveUp, decision)
        assertFalse("已经吐过内容就不能重发（会重复）", asked)
    }

    @Test
    fun oneShotPerCredentialPerTurn() {
        // 3 keys → at most 2 rotations inside a turn
        assertEquals(
            RotationDecision.Rotate(1),
            decideCredentialRotation(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED, false, 0, 3) { 1 },
        )
        assertEquals(
            RotationDecision.Rotate(2),
            decideCredentialRotation(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED, false, 1, 3) { 2 },
        )
        assertEquals(
            "第 3 次必须收手（健康度盲走会转回同一把死密钥）",
            RotationDecision.GiveUp,
            decideCredentialRotation(ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED, false, 2, 3) { 0 },
        )
    }

    @Test
    fun givesUpWhenNoSiblingIsHealthy() {
        val decision = decideCredentialRotation(
            kind = ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            emittedContent = false,
            rotationsDone = 0,
            keyCount = 4,
        ) { null }
        assertEquals(RotationDecision.GiveUp, decision)
    }

    @Test
    fun singleCredentialInstancesAreUntouched() {
        var asked = false
        val decision = decideCredentialRotation(
            kind = ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
            emittedContent = false,
            rotationsDone = 0,
            keyCount = 1,
        ) {
            asked = true
            0
        }
        assertEquals(RotationDecision.GiveUp, decision)
        assertTrue("单凭据实例必须与改动前逐字节等价：不 park、不写 sticky、不提示", !asked)
    }
}
