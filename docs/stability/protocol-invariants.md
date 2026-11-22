# 跨边界协议不变量（Protocol Invariants）

> 状态：2026-11-22 建立，随 `3383e0e0b` 合入 main。
> 适用场景：动 worker 生命周期、回合提交、取消、rootfs、恢复、调度、Provider 终态、导出/预览之前，**先读这一页**。

## 0. 为什么有这份文档

RikkaMinis 里最难复现的一类问题，不是"算错了"，而是**跨边界的动作没有合同**：

- worker 说完成了，主进程还没写库 → 用户看到的内容会消失；
- 用户点了停止，HTTP 请求还在跑 → 取消只是视觉上的；
- 内存里写成功了，Disk 上还在队列 → "恢复成功"是提前报的；
- 想知道"这个字段加完没加全"，只能靠通读全文。

这类问题在 UI 上看不出来，只能靠"每个边有唯一决定点 + 一个删掉就红的测试"来压住。
本页把 2026-11-20～22 那轮跨域整理（见 [surgery-final](2026-11-22-surgery-final.md)）
留下的判据固化成可执行条款，目的是让**未来的改动只需要过几个局部判据，而不需要重新理解全局**。

## 1. 五条取证判据

| 判据 | 怎么做 | 违反后的典型后果 |
|---|---|---|
| **累积窗口** | 审查范围 = 上个收口点 → HEAD 的**全部**改动。再审补丁时，范围必须同时包含被修的原代码和补丁本身。 | 只审 diff 会漏掉"补丁引出的次生 bug"（历史实证：一个 1118B 的补丁自身又出 8 条 P1）。 |
| **唯一决定点** | 每个跨边界行为只能有一个入口；新增路径必须经过它，不许旁路。 | "改了 6 处，漏了第 7 处"——加字段缺同步层同族已复发 6 次。 |
| **失败侧先红** | 先证明旧代码在目标用例上**变红**，再改；改完变绿。不复现就不改。 | 没有旧红的"修复"无法证明修的是真问题，可能是装置写错了。 |
| **精准负控（变异）** | 故意删掉/改掉一行，红的**臂数必须精确对应预期集合**：多红 = 测试粗网，少红 = 覆盖缺口。 | 测试只验字符串或"≥N 次"时，新增站点会让它静默失牙。 |
| **明示叶子** | 树外装置里的平台替身（transport / Room / clock / pid / Service）必须逐个列出来，不许隐式假装。 | 桩撒谎一次，假绿会一路穿透到 CI 和真机。 |

补一条**门的设计纪律**：判据锚「决定点」不锚拼写。改名/重构不应失明，注释与 KDoc 不应满足判据；锚 token 存在而值是常量、空块、调用后 return 的，都必须判红。

## 2. 十三条不变量

> "唯一入口"列是**你改代码时必须经过的那一个地方**；"症状"列是绕过去以后用户会看到什么。

### 2.1 回合与流

| # | 不变量 | 唯一入口 | 绕过的症状 | 钉住它的测试 |
|---|---|---|---|---|
| 1 | **UI 可见 ≠ 已提交**：assistant 行必须先写 plan、再插行、后 commit | `AgentLoopEngine.persistTranscriptRow` / `TranscriptCommit` | 屏幕上有的内容，重开聊天没有了 | `AgentLoopTranscriptPlanTest`、`TranscriptCommitRecoveryTest`、`TranscriptCommitHandleDurabilityTest` |
| 2 | **每个流有稳定身份**：session + bubble + attempt + rowIndex | `StreamOwnership` | 恢复时插错行 / 重复插入 | `StreamRecoveryOwnershipTest` |
| 3 | **恢复必须有界且诚实**：扫描有预算；超预算的 terminal 不删、也不许声称能恢复 | `StreamScan` / `PartialStreamRecoveryPolicy` | 超大回答永久留在 staging，用户看不到 | 同域测试（超预算闭环是**已知缺口**，见 §5） |

### 2.2 取消与生命周期

| # | 不变量 | 唯一入口 | 绕过的症状 | 钉住它的测试 |
|---|---|---|---|---|
| 4 | **取消必须在出站前同步判定**：检查点与真正发请求在同一个 run 内，不靠轮询 | `CallCancellation.CallDispatchGuard` + `withCallCancellation` | 点停止后第 2 个 POST、图片重试、下一跳下载仍发出 | `WorkerPredispatchCancellationTest`、`WorkerNonStreamingHttpCancellationTest`、`AnthropicNonStreamingCancellationTest` |
| 5 | **per-run 状态必须随 run 消失**：cancel lease / 规则快照不得用 ThreadLocal 或进程级字段承载 | CoroutineContext 元素 + `ModelExecutionCancellationRace` / `ModelExecutionSlotLease` | 并发兄弟请求互串取消 / 互串配置 | `ModelExecutionCancelPolicyTest`、`OffloadOwnershipRaceTest` |
| 6 | **boot 与 reset 是同一事务**：install 不能在事务内自锁 | `BootTransaction` | 首启与恢复/重置竞态，rootfs 被半写 | `BootTransactionTest` |
| 7 | **进程作用域的回收判据必须带实例身份**：`stopSelf` ≠ 进程死亡 | `ModelExecutionService` + companion 生命周期真值 | 旧 Service 实例杀掉同 PID 的新请求 | `WorkerProcessOwnershipTest`、`WorkerFinalizerReapDecisionTest` |
| 8 | **请求配置是不可变快照**：缺字段 = 显式空快照，不继承上一个请求 | `RequestThinkingRules` → `ProviderFactory.create` | A 请求应用了 B 的思考规则；wire 与 echo 不同代际 | `RequestThinkingRulesProviderTest`、`WorkerThinkingRulesRestoreTest` |

### 2.3 Provider 与协议终态

| # | 不变量 | 唯一入口 | 绕过的症状 | 钉住它的测试 |
|---|---|---|---|---|
| 9 | **三种截断不可混用**：`context_window` ≠ EOF ≠ `max_tokens` | `TruncatedToolCallPolicy` | 半截答案被当完整；不完整工具参数被 repair 后执行；context 截断误走 length-wall 重试 | `ContextWindowTerminalTest`、`ProviderTerminalCompatibilityTest`、`AgentLoopProtocolTerminalTest` |
| 10 | **私有回放数据只有一个出口**：native 块只走载体，公开面只走投影 | `AnthropicReplay` / `AnthropicReplayPublicProjection` / `AnthropicReplayIpc` | 签名/redacted 进日志、导出、预览；或回放时丢块导致 400 | `AnthropicNativeReplayWireTest`、`AnthropicReplayIpcVerticalTest`、`AnthropicReplayHistoryTest`、`ChatExporterPublicProjectionTest` |
| 11 | **prefill 是 per-request 能力**，不是全局开关 | `LLMProvider.supportsPrefillForRequest` | 4.6+ / thinking 请求发出官方不支持的尾消息 | `AnthropicPrefillRequestTest`、`AgentLoopPrefillRequestTest` |

### 2.4 恢复、调度与出口

| # | 不变量 | 唯一入口 | 绕过的症状 | 钉住它的测试 |
|---|---|---|---|---|
| 12 | **报成功 = 已落盘**：备份写入必须纳入本次 batch 的 capture 才有回执 | `ProviderRepository.ConfigPersistenceBarrier.capture` | 提示"恢复成功"，重启后默认模型组/语音组/Agent Loop 回到旧值 | `RestoreFieldsPersistenceTest`（6 字段 × 4 存储结果 = 48 场景） |
| 13 | **跨存储引用带来源身份；规则 id 在单事务内解析** | `ConfigBackup` 的 source identity + `ProviderConfigDao` 覆盖整表事务 | 规则绑到同 type+label 的另一个 Provider；UUID 跨 owner 搬移；并发读改写丢更新 | `ThinkingRuleGroupPairingTest`、`RestoreRulePublicationTest`、`RestorePersistenceTest` |

附：调度归属用 `ScheduledRunRef(taskId, sessionId)`，结算前先问归属（`ScheduledTaskPolicy`），
测试 `ScheduledTaskGenerationPolicyTest` / `ScheduledTaskLateSettlementTest`。
下载失败桥与成功桥共用 `BlobDownloadTokenSlot`（arm → claim，120s，同锁消费），
测试 `BlobDownloadBridgeAuthTest`。

## 3. 六域地图

改一块代码前，先确定它属于哪个域、哪个测试类守着它。

| 域 | 关键生产文件 | 守卫测试 | 历史证据 |
|---|---|---|---|
| **A 运行时 / 取消 / rootfs** | `sandbox/NativeOffload.kt`、`sandbox/PRootKernel.kt`、`sandbox/RootfsManager.kt`、`provider/CallCancellation.kt`、`offload/ModelExecutionCancelPolicy.kt` | `NativeOffloadCancelTest`、`OffloadOwnershipRaceTest`、`ModelExecutionCancelPolicyTest`、`BootTransactionTest` | surgery-final §三 |
| **B 回合提交 / Engine** | `ui/chat/AgentLoopEngine.kt`、`offload/TranscriptCommit(Handle).kt`、`StreamOwnership.kt`、`StreamScan.kt`、`PartialStreamRecovery(Policy).kt` | `AgentLoopTranscriptPlanTest`、`TranscriptCommitRecoveryTest`、`TranscriptCommitHandleDurabilityTest`、`StreamRecoveryOwnershipTest` | 同上 |
| **C 回放 / IPC** | `data/model/AnthropicReplay.kt`、`data/AnthropicReplayPublicProjection.kt`、`offload/AnthropicReplayIpc.kt`、`ui/chat/ChatAnthropicReplay.kt` | `AnthropicReplayCarrierTest`、`AnthropicReplayIpcVerticalTest`、`AnthropicReplayHistoryTest`、`AnthropicReplayQualificationTest`、`AnthropicReplayPublicProjectionTest` | 同上 |
| **D Provider / 协议** | `provider/anthropic/*`、`provider/gemini/GeminiProvider.kt`、`provider/openai/OpenAIProvider.kt`、`provider/thinking/ThinkingRuleResolver.kt`、`ui/chat/TruncatedToolCallPolicy.kt` | `AnthropicPrefillRequestTest`、`ProviderTerminalCompatibilityTest`、`AnthropicNativeReplayWireTest`、`RequestThinkingRulesProviderTest` | 同上 |
| **E 恢复 / 调度** | `backup/ConfigBackup.kt`、`data/db/ProviderConfigDao.kt`、`ui/settings/BackupSettingsScreen.kt`、`scheduled/ScheduledTask*.kt` | `RestoreFieldsPersistenceTest`、`RestoreRulePublicationTest`、`ThinkingRuleGroupPairingTest`、`ScheduledTask*Test` | 同上 |
| **F 公开出口 / 浏览器** | `data/AnthropicReplayPublicProjection.kt`、`share/ChatExporter.kt`、`data/repository/ChatRepository.kt`、`browser/BlobDownloadTokenSlot.kt`、`browser/BrowserUseManager.kt`、`debug/HeadlessChatRunner.kt` | `ChatExporterPublicProjectionTest`、`BlobDownloadBridgeAuthTest`、`ReplayPublicFallbackTest` | 同上 |

每个域的历史审查都按"完整累积 diff + 最终态关键控制流 + 符号调用点清单"做的；
当前状态与未验边界以 [surgery-final](2026-11-22-surgery-final.md) 为准，不以旧报告为准。

## 4. 维护纪律（这套东西的折旧条件）

这十几条不变量的价值，等于"未来改代码时不许为了过 CI 放宽容忍度"的纪律强度。具体：

1. **不许删负控、不许降下限。** 测试里的"≥N 次"型断言在新增站点后会静默失牙；正确做法是改成精确的逐站清单 + 每站删除/重复负控（例：`SessionActivityStreamErrorTest` 的 60 项）。
2. **改测试必须写清"原断言为什么失效"**，并在提交信息里给出证据，不允许"改了断言让它绿"。
3. **新增跨边界路径必须经过唯一入口。** 要绕，先在本文登记一个新不变量和它的测试。
4. **加字段 / 加终态 / 加 Provider 时**：先查 `src/android/app/src/test` 里对应域的测试是否已有"四面"断言（模型 / Entity / toSnapshot / toProviderConfig）；没有就补。
5. **新增用户可见文案**必须同时进 7 个 `values-*/strings.xml`（CI 有 key 集一致性检查）。
6. **报告不是知识遗产的家。** 结论进本页或 `ARCHITECTURE.md`；报告只作证据。

## 5. 已知缺口（写在这里，避免被当成已解决）

| 缺口 | 现状 | 触发条件 |
|---|---|---|
| 没有对应的静态门 | 这 13 条只有 JUnit 守卫，`scripts/scan` 里 **0 条**新门。测试可以被改松，门不行。 | 下次大改本域时，考虑把 3～4 条最高频的（提交顺序、取消同步、公开投影单出口）落成带 `--selftest` 的门 |
| 超 8 MiB terminal spool | reaper 保留、recovery 看不见 DONE ⇒ 不删也不恢复 | 单条回答 spool 超 8 MiB 且正常结束 |
| 真机验证不可重跑 | 进程死亡 / Room·ACK 竞争 / rootfs reset 是一次性 adb 操作，没有装置 | 下次要验同样边界 |
| 产品语义未拍板 | 快照"回滚"实际是 merge（UI 已改成实话）；跨源 redirect 保留 `api-key`；iframe 来源信任只到"未证可利用" | 用户提出精确回滚 / 收紧网络或 WebView 来源 |
| 四类存量 P3 | DEAD 棘轮、锁外准入判据、终态锁内文件 IO、锁外 poison 写 | 与公开基线同形，未扩半径 |
