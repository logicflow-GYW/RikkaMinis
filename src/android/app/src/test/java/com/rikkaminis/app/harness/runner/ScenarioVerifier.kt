package com.rikkaminis.app.harness.runner

import com.rikkaminis.app.harness.contract.*

/**
 * 场景断言引擎：将 ScenarioReport 与 FaultScenario.expect 对比，
 * 将违反分类为五类之一（TERMINAL_STATE / BUDGET / RESOURCE / PERSISTENCE / SIDE_EFFECT）。
 */
object ScenarioVerifier {

    /**
     * 验证场景报告是否符合期望。
     * @return 违反列表（空 = 通过）。
     */
    fun verify(scenario: FaultScenario, report: ScenarioReport): List<HarnessViolation> {
        val violations = mutableListOf<HarnessViolation>()
        val expect = scenario.expect

        // 1. TERMINAL_STATE 检查
        if (report.terminal != expect.terminal) {
            violations += HarnessViolation(
                ViolationCategory.TERMINAL_STATE,
                "terminal=${report.terminal} expected=${expect.terminal}"
            )
        }

        // 2. TERMINAL_STATE: trace terminal event 计数
        if (report.traceTerminalEvents != expect.traceTerminalEvents) {
            violations += HarnessViolation(
                ViolationCategory.TERMINAL_STATE,
                "traceTerminalEvents=${report.traceTerminalEvents} expected=${expect.traceTerminalEvents}"
            )
        }

        // 3. BUDGET: provider attempts
        if (report.providerAttempts != expect.providerAttempts) {
            violations += HarnessViolation(
                ViolationCategory.BUDGET,
                "providerAttempts=${report.providerAttempts} expected=${expect.providerAttempts}"
            )
        }

        // 4. BUDGET: tool executions
        if (report.toolExecutions != expect.toolExecutions) {
            violations += HarnessViolation(
                ViolationCategory.BUDGET,
                "toolExecutions=${report.toolExecutions} expected=${expect.toolExecutions}"
            )
        }

        // 5. BUDGET: compact calls
        if (report.compactCalls != expect.compactCalls) {
            violations += HarnessViolation(
                ViolationCategory.BUDGET,
                "compactCalls=${report.compactCalls} expected=${expect.compactCalls}"
            )
        }

        // 6. BUDGET: 其他预算字段
        expect.budget.forEach { (key, value) ->
            val actual = when (key) {
                "provider_attempts_consumed" -> report.providerAttempts
                "tool_calls_consumed" -> report.toolExecutions
                "compact_calls_consumed" -> report.compactCalls
                else -> null
            }
            if (actual != null && actual != value) {
                violations += HarnessViolation(
                    ViolationCategory.BUDGET,
                    "budget.$key=${actual} expected=${value}"
                )
            }
        }

        // 7. RESOURCE: lease 是否释放
        if (expect.leasesReleased && report.leaseCount != 0) {
            violations += HarnessViolation(
                ViolationCategory.RESOURCE,
                "leaseCount=${report.leaseCount} expected=0 (leasesReleased=true)"
            )
        }

        // 8. PERSISTENCE
        if (report.persistenceMark != expect.persistence) {
            violations += HarnessViolation(
                ViolationCategory.PERSISTENCE,
                "persistence=${report.persistenceMark} expected=${expect.persistence}"
            )
        }

        // 9. SIDE_EFFECT: duplicate side effects
        if (report.duplicateSideEffects != expect.duplicateSideEffects) {
            violations += HarnessViolation(
                ViolationCategory.SIDE_EFFECT,
                "duplicateSideEffects=${report.duplicateSideEffects} expected=${expect.duplicateSideEffects}"
            )
        }

        // 10. Cooldown 记录
        if (report.cooldownRecords.size != expect.cooldownCount) {
            violations += HarnessViolation(
                ViolationCategory.BUDGET,
                "cooldownRecords=${report.cooldownRecords.size} expected=${expect.cooldownCount}"
            )
        }

        // 11. Provider cancellations
        if (report.providerCancellations != expect.providerCancellations) {
            violations += HarnessViolation(
                ViolationCategory.RESOURCE,
                "providerCancellations=${report.providerCancellations} expected=${expect.providerCancellations}"
            )
        }

        // 12. Tool cancellations
        if (report.toolCancellations != expect.toolCancellations) {
            violations += HarnessViolation(
                ViolationCategory.RESOURCE,
                "toolCancellations=${report.toolCancellations} expected=${expect.toolCancellations}"
            )
        }

        // 13. Spawn rejected
        if (report.spawnRejected != expect.spawnRejected) {
            violations += HarnessViolation(
                ViolationCategory.BUDGET,
                "spawnRejected=${report.spawnRejected} expected=${expect.spawnRejected}"
            )
        }

        // 14. History intact
        if (report.historyIntact != expect.historyIntact) {
            violations += HarnessViolation(
                ViolationCategory.PERSISTENCE,
                "historyIntact=${report.historyIntact} expected=${expect.historyIntact}"
            )
        }

        // 15. Recoverable
        if (report.recoverable != expect.recoverable) {
            violations += HarnessViolation(
                ViolationCategory.TERMINAL_STATE,
                "recoverable=${report.recoverable} expected=${expect.recoverable}"
            )
        }

        return violations
    }
}