# RikkaMinis 性能基线（指标定义与待采样项）

> T0 交付物之三。基线：`9672e09e`（origin/main，2026-08-15）。
> T9 负责：采集真实基线 → 设定 P95/P99 阈值 → report-only 门禁 →（Harness 与基线通过后）enforced 门禁。
> T0 只定义指标口径，**不选择最终数字**。
> T9 交付物：`PerfBaselineCollector` / `PerfBaselineReport` / `MemoryPressureTracker` / `SyntheticWorkload`（2026-08-15）。

---

## 1. 指标分类

分四类：

1. **启动类**：冷启动到可交互；
2. **执行类**：一轮 Agent Run 的耗时分解（provider / tool / compact / finalize）；
3. **资源类**：内存、shell、会话槽位、并发；4. **可靠性类**：失败率、重试率、trace 完整性。

## 2. 指标定义

### 2.1 启动类

| 指标 | 定义 | 采样方式 |
|---|---|---|
| `cold_start_to_idle_ms` | 进程创建 → 首帧可交互（wm_on_idle） | logcat `am_proc_start` + `wm_on_idle`（已有实测经验：2026-08-12 实测 2.35s） |
| `config_load_ms` | `loadConfigSuspending` 总耗时（db + assemble + hash） | 现有 `ProviderPerf` 插桩（`ProviderRepository.kt`，实测 108ms @ 17 实例/1237 条目/4 组） |
| `first_turn_ttfb_ms` | 用户发送 → 首个响应 chunk | `PerfBaselineCollector.recordFirstToken()`（T9 新增） |

### 2.2 执行类（Agent Run）

| 指标 | 定义 |
|---|---|
| `run_duration_ms` | trace_start → trace_end 的单调时间跨度 |
| `provider_attempts_per_run` | 一次 run 的 provider attempt 数（含 retry/fallback） |
| `tool_calls_per_run` | 一次 run 的 tool call 数 |
| `tool_latency_p50/p95/p99` | 单次工具执行耗时分布 |
| `compact_duration_ms` | 单次 compact 耗时（现有 ContextCompactor + ChatViewModel 已埋点部分） |
| `finalize_duration_ms` | 收尾（持久化 + 资源释放）耗时 |
| `tokens_per_run` | 已知 token usage 时记录；未知必须记 unknown（蓝图 4.3） |

### 2.3 资源类

| 指标 | 定义 |
|---|---|
| `max_concurrent_active` | 并发 active run 峰值（≤5，T1 后由 lease 计数保证） |
| `shell_rss_mb` | PRoot 子进程 RSS（`PersistentShell.nativeRssMB`，高水位 256MB 触发回收） |
| `app_native_heap_mb` | app 进程 native heap（`Debug.getNativeHeapAllocatedSize`，高水位 120MB） |
| `heap_java_mb` | Java 堆占用（512MB 上限内） |
| `process_rss_mb` | 进程 RSS（`/proc/self/status VmRSS`，08-15 OOM 分析：NORMAL<280 / ELEVATED 280-319 / CRITICAL≥320） | `MemoryPressureTracker.check()`（T9 新增，带 Level 分类 + 监听器） |
| `thread_count` | 进程线程数（`/proc/self/status Threads`） | `MemoryPressureTracker.Snapshot.threadCount` |
| `leases_held_at_finalize` | 终态时仍持有的 lease 数（必须为 0） |

### 2.4 可靠性类

| 指标 | 定义 |
|---|---|
| `run_failure_rate` | Failed+Interrupted / 总 run |
| `retry_rate` | 触发 retry 的 attempt / 总 attempt |
| `fallback_rate` | 触发 fallback 的 run / 总 run |
| `rate_limited_rate` | 429/quota 触发的 run / 总 run |
| `trace_terminal_missing_rate` | 无 terminal event 的 run / 总 run（必须为 0） |
| `duplicate_side_effect_rate` | 检测到的重复副作用 / 总 run（必须为 0） |

## 3. 采样基础设施现状（T9 交付后）

已有：

- `ProviderPerf` 插桩（`ProviderRepository.kt`）：config 加载耗时；
- `AgentTraceRecorder`（`tools/AgentTraceRecorder.kt`）：每 run JSONL trace（`workspace/.traces/agent-<ts>.jsonl`）——T6 扩展后将成为执行类指标的主数据源；
- `StreamPerfMonitor`（`diagnostics/StreamPerfMonitor.kt`）：流式 turn 的渲染管道聚合（flatten 耗时、GC 压力、frozen hit rate）；
- `PerfLongCtx`（`diagnostics/PerfLongCtx.kt`）：会话 reentry 路径的耗时分解（loadSession、rowsCompose 里程碑）；
- logcat 收集经验（记忆沉淀）：`setsid sh -c 'logcat -b main -v time > /data/local/tmp/minis-boot.log &'` 后台收集器（注意 buffer 会被 SSE 日志冲掉，抓冷启动要用流式收集器）；
- `ExecutionCoordinator` 的 shell 回收日志（RSS 高水位）。

T9 新增：

- `PerfBaselineCollector`（`diagnostics/PerfBaselineCollector.kt`）：统一基线 JSONL 收集器，从现有插桩补缺 first-token latency、RSS、thread count、工具耗时、资源 lease 等指标；输出到 `filesDir/perf-baseline/` 目录；
- `PerfBaselineReport`（`diagnostics/PerfBaselineReport.kt`）：纯 JVM 聚合器，从 JSONL 基线文件计算 P50/P95/P99/mean/max，支持 delta 对比和 Markdown 报告；
- `MemoryPressureTracker`（`diagnostics/MemoryPressureTracker.kt`）：进程级 RSS 监控，NORMAL/ELEVATED/CRITICAL 三级分类，带 level-change 监听器（供 `MemoryPressureGate` 消费）；
- `SyntheticWorkload`（`diagnostics/SyntheticWorkload.kt`）：6 种可重复 workload 场景定义（COLD_START / SIMPLE_QA / TOOL_CHAIN / MULTI_SESSION / COMPACT_TRIGGER / MEMORY_PRESSURE），含采集脚本生成器。

缺失（T9 后续依赖 T7 的）：

- 统一的 run 级耗时聚合（trace 扩展后可从 JSONL 聚合，无需新埋点）；
- 门禁执行器（report-only → enforced 的开关）。

## 4. 基线采样协议（T9 执行，T0 只定义）

1. **设备**：Redmi Note 12 Turbo (marble)，Android 15 + HyperOS 3.0，用户环境（有代理变量，大传输可能被截断——采集时注意网络一致性）。
2. **场景集**（T9 定稿，`SyntheticWorkload.Scenario` 枚举，6 个场景）：
   - 冷启动 ×5（`COLD_START`）；
   - 简单问答（无工具）run ×20（`SIMPLE_QA`）；
   - 带工具链 run（≥3 工具）×10（`TOOL_CHAIN`）；
   - 高并发（5 会话并行）×5（`MULTI_SESSION`）；
   - compact 触发 run ×5（`COMPACT_TRIGGER`）；
   - 内存压力测试（多会话快速工具调用）×3（`MEMORY_PRESSURE`）。
3. **报告格式**：`PerfBaselineReport.aggregate()` 输出 Markdown 报告，每个指标给出 count / p50 / p95 / p99 / max / mean；标注采样的时间与 APK 版本（commit）。
4. **门禁策略**（蓝图 T9 原文）：
   - Phase 1：report-only——采集、存档、展示，不阻断合并；
   - Phase 2：依据 P95/P99 决定门禁阈值——只对**可证明的回归**开闸（如 run 级 P95 退化超阈值）；
   - 禁止：在无基线、无故障 Harness 通过前启用激进硬预算。

## 5. 与蓝图其它任务的接口

- T6 的 trace schema 必须包含本文件第 2.2/2.3 节所需字段（budget consume、state transition、resource acquire/release、terminal reason）——T9 才能直接从 trace 聚合执行类指标。
- T9 独占 `build.gradle.kts` 与 workflow 变更（CI 加性能 gate 也走 T9/T10）。
- 本文件第 4 节的基线报告在 T10 验收时作为"到达平衡点"的判据之一。

## 6. 验收

- 指标口径与蓝图 4.3 一致（token 未知必须记 unknown，不伪造）；
- 基线报告可重复生成（同一 APK、同一场景集、同一设备）；
- 不因性能采集改变生产行为（report-only 阶段）。
