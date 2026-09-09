package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.BudgetSnapshot
import org.json.JSONObject

/**
 * Pure T7 trace-budget JSON serializers extracted from ChatViewModel (FE-4
 * pure sweep, from a4369d3). Side-effect-free and JVM-testable: they turn a
 * budget/snapshot value into its trace JSON fragment. Behavior is
 * verbatim-identical to the former private methods.
 */

/**
 * Serialize [AgentExecutionBudget] into the trace_start `budget` JSON.
 * [AgentExecutionBudget.maxEstimatedTokens] omitted when null (do not fabricate
 * a precise value).
 */
fun t7InitialBudgetJson(budget: AgentExecutionBudget): String {
    val o = JSONObject()
    o.put("deadline_monotonic_ms", budget.deadlineMonotonicMs)
    o.put("max_turns", budget.maxTurns)
    o.put("max_provider_attempts", budget.maxProviderAttempts)
    o.put("max_tool_calls", budget.maxToolCalls)
    o.put("max_shell_commands", budget.maxShellCommands)
    o.put("max_compaction_calls", budget.maxCompactionCalls)
    o.put("max_concurrent_tools", budget.maxConcurrentTools)
    budget.maxEstimatedTokens?.let { o.put("max_estimated_tokens", it) }
    return o.toString()
}

/**
 * Serialize [BudgetSnapshot] into the trace_end `budget_final_snapshot` JSON
 * (schema: `*_consumed` used-amount fields). [BudgetSnapshot.estimatedTokensUsed]
 * omitted when null. Write failures are the caller's concern (non-fatal).
 */
fun t7BudgetSnapshotJson(snapshot: BudgetSnapshot): String {
    val o = JSONObject()
    o.put("turns_consumed", snapshot.turnsUsed)
    o.put("provider_attempts_consumed", snapshot.providerAttemptsUsed)
    o.put("tool_calls_consumed", snapshot.toolCallsUsed)
    o.put("shell_commands_consumed", snapshot.shellCommandsUsed)
    o.put("compaction_calls_consumed", snapshot.compactionCallsUsed)
    snapshot.estimatedTokensUsed?.let { o.put("estimated_tokens_consumed", it) }
    o.put("concurrent_tools_active", snapshot.concurrentToolsActive)
    o.put("is_expired", snapshot.isExpired)
    return o.toString()
}
