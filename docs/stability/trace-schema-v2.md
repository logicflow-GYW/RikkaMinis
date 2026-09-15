# RikkaMinis Trace 扩展 JSON Schema（v2）

> 编制于 T1-T5 并行施工期间，T0 基线 `e6f2be32`。
> **状态（2026-08-30）：本 schema 已实现。** `tools/AgentTraceRecorder.kt`
> （当前 ~699 行）已落地全部 v2 事件（`trace_schema_version` / `run_id` /
> `state_transition` / `budget_consume` / `budget_refuse` / `resource_acquire` /
> `resource_release` / `retry_decision` / `persistence_result` / `terminal_state`），
> 并保留对 1.0 旧记录的向后兼容解析。
> 向后兼容：所有已有字段保持原样，新字段为可选（optional）。

---

## 1. 版本与基础

```json
{
  "trace_schema_version": "2.0",
  "run_id": "uuid-string",
  "session_id": "uuid-string",
  "created_at_epoch_ms": 1234567890000,
  "duration_ms": 15000
}
```

- `trace_schema_version`：新增，当前 `"2.0"`。旧记录无此字段，解析时默认为 `"1.0"`。
- `run_id`：每次 Agent Run 的唯一 ID，与 sessionId 分离。
- `session_id`：会话 ID。
- `created_at_epoch_ms` / `duration_ms`：start timestamp + 总耗时。

## 2. 事件类型

### 仍保留的 1.0 事件（字段不变，仅新增可选字段）

| 事件 | 类型标识 | 说明 |
|---|---|---|
| trace_start | `"trace_start"` | Run 开始，新增 `schema_version` 和 `run_id` |
| turn_start | `"turn_start"` | 单轮开始 |
| tool_call | `"tool_call"` | 工具调用，新增 `side_effect_class` 和 `result_known` |
| tool_result | `"tool_result"` | 工具结果 |
| turn_end | `"turn_end"` | 单轮结束 |
| trace_end | `"trace_end"` | Run 结束，新增 `terminal_state` 和 `terminal_reason` |
| error | `"error"` | 错误事件 |

### 新增的 2.0 事件

| 事件 | 类型标识 | 必须记录字段 |
|---|---|---|
| state_transition | `"state_transition"` | `from`, `to`, `reason` |
| budget_consume | `"budget_consume"` | `dimension`, `consumed`, `remaining`, `total` |
| budget_refuse | `"budget_refuse"` | `dimension`, `requested`, `reason` |
| resource_acquire | `"resource_acquire"` | `resource_type`, `resource_id`, `lease_token` |
| resource_release | `"resource_release"` | `resource_type`, `resource_id`, `lease_token` |
| retry_decision | `"retry_decision"` | `operation_type`, `safety_level`, `outcome`, `reason` |
| persistence_result | `"persistence_result"` | `target`, `success`, `error_type` |

## 3. 详细字段定义

### 通用字段（所有事件共享）

```json
{
  "type": "event_type",
  "ts_monotonic_ms": 123456,
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

### trace_start

```json
{
  "type": "trace_start",
  "trace_schema_version": "2.0",
  "run_id": "uuid-string",
  "session_id": "uuid-string",
  "ts_monotonic_ms": 0,
  "created_at_epoch_ms": 1234567890000,
  "prompt_preview": "用户消息的前 300 字符（脱敏后）",
  "provider_count": 3,
  "tool_count": 8,
  "initial_budget": {
    "deadline_monotonic_ms": 300000,
    "max_turns": 200,
    "max_provider_attempts": 10,
    "max_tool_calls": 50,
    "max_shell_commands": 30,
    "max_compaction_calls": 5,
    "max_concurrent_tools": 4,
    "max_estimated_tokens": null
  }
}
```

### state_transition

```json
{
  "type": "state_transition",
  "ts_monotonic_ms": 1234,
  "from": "CallingModel",
  "to": "ExecutingTools",
  "reason": "tool_calls_requested",
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

状态枚举：`Idle` / `Preparing` / `CallingModel` / `ExecutingTools` / `Retrying` / `FallingBack` / `Compacting` / `Finalizing` / `Succeeded` / `Failed` / `Cancelled` / `Interrupted`

### budget_consume

```json
{
  "type": "budget_consume",
  "ts_monotonic_ms": 1234,
  "dimension": "provider_attempts",
  "consumed": 1,
  "remaining": 4,
  "total": 5,
  "is_retry": true,
  "is_fallback": false,
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`dimension` 枚举：`turns` / `provider_attempts` / `tool_calls` / `shell_commands` / `compaction_calls` / `concurrent_tools` / `estimated_tokens`

### budget_refuse

```json
{
  "type": "budget_refuse",
  "ts_monotonic_ms": 1234,
  "dimension": "provider_attempts",
  "requested": 1,
  "remaining": 0,
  "reason": "budget_exhausted",
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`reason` 枚举：`budget_exhausted` / `deadline_reached` / `child_quota_exceeded` / `not_reservable`

### resource_acquire

```json
{
  "type": "resource_acquire",
  "ts_monotonic_ms": 1234,
  "resource_type": "session_slot",
  "resource_id": "session-uuid",
  "lease_token": "lease-uuid",
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`resource_type` 枚举：`session_slot` / `shell` / `tool_slot` / `webview` / `temp_file`

### resource_release

```json
{
  "type": "resource_release",
  "ts_monotonic_ms": 5678,
  "resource_type": "session_slot",
  "resource_id": "session-uuid",
  "lease_token": "lease-uuid",
  "released_by": "finalize",
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`released_by` 枚举：`normal` / `cancel` / `finalize` / `error` / `timeout` / `recovery`

### retry_decision

```json
{
  "type": "retry_decision",
  "ts_monotonic_ms": 1234,
  "operation_type": "shell_execute",
  "operation_name": "apt-get install python3",
  "safety_level": "UNKNOWN",
  "outcome": "OutcomeUnknown",
  "reason": "shell_died_before_result",
  "attempt": 2,
  "max_attempts": 2,
  "will_retry": false,
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`safety_level` 枚举：`READ_ONLY` / `IDEMPOTENT_WRITE` / `NON_IDEMPOTENT_WRITE` / `UNKNOWN`
`outcome` 枚举：`SafeToRetry` / `MustVerifyFirst` / `OutcomeUnknown` / `DoNotRetry`

### persistence_result

```json
{
  "type": "persistence_result",
  "ts_monotonic_ms": 5678,
  "target": "message_db",
  "success": true,
  "error_type": null,
  "duration_ms": 45,
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`target` 枚举：`message_db` / `chat_session` / `trace_file` / `compact_marker` / `budget_snapshot`
`error_type`：`null`（成功时）或 `write_failure` / `constraint_violation` / `disk_full` / `unknown`

### trace_end

```json
{
  "type": "trace_end",
  "ts_monotonic_ms": 300000,
  "terminal_state": "Succeeded",
  "terminal_reason": "completed_normally",
  "duration_ms": 300000,
  "total_provider_attempts": 2,
  "total_tool_calls": 5,
  "total_shell_commands": 3,
  "total_compactions": 0,
  "budget_final_snapshot": {
    "turns_consumed": 3,
    "provider_attempts_consumed": 2,
    "tool_calls_consumed": 5,
    "shell_commands_consumed": 3,
    "compaction_calls_consumed": 0
  },
  "leases_remaining": 0,
  "run_id": "uuid-string",
  "session_id": "uuid-string"
}
```

`terminal_reason` 枚举：`completed_normally` / `all_fallbacks_exhausted` / `user_cancelled` / `deadline_reached` / `process_interrupted` / `persistence_failed` / `budget_exhausted` / `internal_error`

> **发射侧完备性（2026-09-15）**：schema 定义了 14 种 run 事件，运行循环实际发射
> 9 种。`PersistenceFailed` 此前**零发射点**（reducer / state / recovery policy
> 全链路都处理它，但没人发），导致持久化失败时 trace 与恢复决策层看不到——
> 同族于"加字段缺同步层"的接线缺口。已在运行路径持久化失败点补上发射，
> 使"定义-发射-消费"三条边都有主。
>
> **终止路径收敛（2026-09-15）**：`runAgentLoop` 的两个 catch
> （CancellationException → CANCELLED / Exception → FAILED）原先直接发
> `RunFinalized`，不经 FINALIZING 相位；只有 `user_stop` 和 `switch_model` 两条
> 取消路径会预发射 `UserCancelled`。其余取消原因与未预期异常会让 reducer 停在
> CALLING_MODEL/EXECUTING_TOOLS → `RunFinalized` 被拒（warn 日志 + 终态缺失）。
> 现改为按 terminal 预发射终止事件，reducer 容忍重复。

## 4. 兼容性规则

1. 解析时先读 `trace_schema_version`。无此字段 → 视为 `"1.0"`。
2. 1.0 的旧事件（trace_start/turn_start/tool_call/tool_result/turn_end/trace_end/error）的既有字段在 2.0 中**完全不变**。
3. 新字段全部 optional，旧解析器遇到未知字段静默忽略。
4. `run_id` 在 1.0 中不存在，2.0 中所有事件都带——但旧记录的 run_id 字段为空字符串时，解析器应自动填充为 trace_start 的 run_id（如果存在）。
5. 禁止写入 API key、token、完整 prompt、完整文件内容。

## 5. 完整示例 trace

```jsonl
{"type":"trace_start","trace_schema_version":"2.0","run_id":"r-001","session_id":"s-001","ts_monotonic_ms":0,"created_at_epoch_ms":1000000,"prompt_preview":"帮我查一下...","provider_count":2,"tool_count":5,"initial_budget":{"deadline_monotonic_ms":300000,"max_turns":200,"max_provider_attempts":10,"max_tool_calls":50,"max_shell_commands":30,"max_compaction_calls":5,"max_concurrent_tools":4,"max_estimated_tokens":null}}
{"type":"state_transition","from":"Idle","to":"Preparing","reason":"run_started","ts_monotonic_ms":10,"run_id":"r-001","session_id":"s-001"}
{"type":"state_transition","from":"Preparing","to":"CallingModel","reason":"budget_allocated","ts_monotonic_ms":50,"run_id":"r-001","session_id":"s-001"}
{"type":"budget_consume","dimension":"provider_attempts","consumed":1,"remaining":9,"total":10,"is_retry":false,"is_fallback":false,"ts_monotonic_ms":50,"run_id":"r-001","session_id":"s-001"}
{"type":"resource_acquire","resource_type":"session_slot","resource_id":"s-001","lease_token":"l-001","ts_monotonic_ms":5,"run_id":"r-001","session_id":"s-001"}
{"type":"trace_end","terminal_state":"Succeeded","terminal_reason":"completed_normally","duration_ms":15000,"total_provider_attempts":1,"total_tool_calls":0,"budget_final_snapshot":{"turns_consumed":1},"leases_remaining":0,"ts_monotonic_ms":15000,"run_id":"r-001","session_id":"s-001"}
{"type":"resource_release","resource_type":"session_slot","resource_id":"s-001","lease_token":"l-001","released_by":"finalize","ts_monotonic_ms":15000,"run_id":"r-001","session_id":"s-001"}
```