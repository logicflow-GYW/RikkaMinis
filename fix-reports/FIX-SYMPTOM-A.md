# FIX-SYMPTOM-A — 症状归因后的四条薄修（影子水线 / 文案 / 注释 / 计数器）

- 分支：`fix/symptom-a-diagnostics`（worktree `/var/minis/shared/work/witness-1027/wt-symptom-a`）
- 基线：main `569e77a3`
- 任务书：`/var/minis/shared/work/symptom-a/TASK-B.md`
- 上游取证：`/var/minis/shared/work/symptom-a/SYMPTOM-TRIAGE.md`（TASK-A，本支不重新取证）
- 装置：`/var/minis/shared/work/symptom-a/probe-b/`

---

## 1 结论

四条全部按"**不改生产行为**"落地：F-A1 只加观测，F-A2 只改日志/文案 + 补一条判定日志，F-A3 只改注释，F-A4 只改诊断判据字符串。没有一条改动会改变用户可见行为或控制流——这是刻意的：TASK-A 已经把三条症状的真实站位查清，**现在没有任何证据支持动行为**（S3 的可动作面是阈值，而阈值该由影子数据说话，不该由猜）。

| 项 | 做了什么 | 行为影响 |
|---|---|---|
| F-A1 | `MemoryPressureGate` 加 800MB **影子水线**（只观测，独立状态机，唯一挂接点在 1s 采样拍） | 无（多一行日志/每次穿越） |
| F-A2a | T7-D 拒绝文案改为"影子机 declined / 非控制" | 无（日志文案） |
| F-A2b | `outcomeKnown=false` 的**消费点**补一行元数据日志 | 无（日志） |
| F-A3 | 压缩锚点自愈的两处注释改成事实口径 + 升级触发条件 | 无（注释） |
| F-A4 | `messages-shape` 两个计数器改用真实落盘方言（camel + mediaRef） | 无（诊断计数） |

门基线：开工 `113 门 / 349 夹具`，收工 `113 门 / 349 夹具`（`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰）。

---

## 2 逐条

### 2.1 F-A1 — 内存门影子观测臂（P1 的可动作一半）

**先证**（引自 SYMPTOM-TRIAGE §6-3，本支复核了源码）：`ELEVATED_ANON_MB=450` / `CRITICAL_ANON_MB=1200` / `HYSTERESIS=150` / `CONFIRM_TICKS=2`；三天 anon 峰值 **1032 / 585 / 552MB**，`level=CRITICAL` **三天 0 次**。也就是说"回收 + 拒绝准入"这条硬分支在生产里**从未执行过**，而 worker 确实在 500–1032MB 区间被系统侧换掉过（10-28 11 次 / 64 run）。

**改了什么**（`service/MemoryPressureGate.kt` + `MinisApp.kt` 装配）：

- 新增 `OBSERVE_CRITICAL_ANON_MB = 800L`，KDoc 写清依据、写清**何时读这条日志**（"有人要动 CRITICAL_ANON_MB 的时候"）。
- 复用既有纯函数 `internalNextTier`，用**两份独立变量**（`shadowLevel` / `shadowConfirmingTicks`）推进一台影子机；`elevatedMb` 与 `criticalMb` 都取 800 ⇒ 影子机只有 NORMAL / CRITICAL 两态，一行日志 = 一次 800 穿越（450 那档已由真档 `tierListener` 记录，重复只会是噪声）。
- 唯一挂接点 `sampleAndNotify()`；只在**跃迁**（含降回）时回调 `shadowTierListener`，生产装配打到 tag `MemGateShadow`。
- 门槛值、`shouldRejectAfterReclaim`、`reclaimHook` / `pressureListener` / `tierListener` 语义、`SessionConcurrencyManager` **全部未动**，也没有新增配置项。

**为什么不做得更多**：不动 1200→800。改动阈值需要"降低后不会天天误拒"的**频率证据**，而这条证据现在不存在（CRITICAL 三天 0 次 ⇒ 连基线都测不到）。影子机就是去产这条证据的装置；先在真机上跑一天，再谈阈值。

### 2.2 F-A2a — 让影子机的拒绝说人话

**先证**：`ChatAgentTraceObserver.t7Reduce` 的 `Rejected` 分支只 `warn`，`state` 原样保留（`ChatAgentTraceObserver.kt:120-133`），`AgentRunReducer` 拒绝只是"影子状态机不肯落 SUCCEEDED"；实测 10-29 有 12 次（占当日 run 的 20%），每一次 run 都正常返回了。

**改了什么**：文案 `T7-D reducer REJECTED <event>: <reason>` → `T7-D shadow reducer declined <event> (non-controlling; no state or control-flow change): <reason>`，并保留 reducer 自己的 reason；两个引用旧文案的测试 KDoc 同步注明"该行已改名"。

**为什么不做得更多**：级别仍是 `warn`。注入的 logger 只有 `warn: (String, String) -> Unit` 一个槽位（`:55`），加 info 槽要牵动装配点与可能存在的构造计数断言 —— 为一个日志级别付这些边，装置比例不成立。**降到 info 登记不修**，理由写在注释里。

### 2.3 F-A2b — 给"结果不可信"留一条可回查的痕迹

**先证**：`outcomeKnown=false` 只在纯函数 `internalDecideShellRetryVerdict` 的两个出口产出（memoryRecycled / MustVerifyFirst+OutcomeUnknown），该分支**零日志**；下游唯一痕迹是影子机的 declined，它既指向不了命令、又读成故障。

**改了什么**：日志落在**消费点**（`verdict` 变 `CommandResult` 处），`if (!verdict.outcomeKnown)` 触发一行，带 `reason`（memory-recycled / outcome-unknown）、`exit`、`alive`、`truncated`、`attempt`、`safety`、`dispatched`、`cmdLen`。**命令内容与输出一个字都不打**。

**为什么不做得更多**：不把日志搬进纯函数（会破它的 JVM 可测性，且会在每条重试评估路径上重复打）；不去统一"非 shell 工具"的 unknown 结局（那是另一条链，见 §5 R4）。

### 2.4 F-A3 — 把"关键机制"改回它真实的样子（只改注释）

**先证**（本支自己核的行号，不照抄上游锚点）：

- 丢弃点 = `ChatTranscriptRebuild.kt:224`，形态是 `return@mapNotNull null` + 共享谓词 `userRowHasVisibleContent`（`:36-44`），判据是"正文/图片/附件三面全空"。
- 锚点 `lastCompactedMessageId` 通常落在 role=user 的 tool 载体行 ⇒ `uiIdxForDbId` 恒 -1（`:1110`）；自愈候选要过同一道门（`:1123` 要求 `healUiIdx >= 0`）⇒ `healedMarker=null` ⇒ 不写库 ⇒ 下次打开原样重来。
- 用户可见后果 = 0：divider / graying 已随 `[fix/silent-auto-compact]` 移除，摘要走 `effectiveAgentHistory` 独立解析（三天 1726 次切片、0 次降级）。

**改了什么**：`:1093` 段与 `:1205` 段两处注释改成上述事实，并写清**升级触发条件**（一旦有 UI 产物重新依赖 `insertIdx`，先修 `:1123` 的验收门）与**为什么不现在改那道门**（改成"锚在 historyDbIds 里"= 每次打开一次写库，锚仍不可见 ⇒ 写放大零收益）。

### 2.5 F-A4 — 两个计数器，两个病因

**先证**（比任务书多一步：去读**真实写入器**，而不是只看 `@SerialName`）：

- 工具：`buildAssistantTurnPartsJson` 写 `{"type":"toolUse"}`、`buildToolResultPartsJson` 写 `{"type":"toolResult"}`（`ChatTurnPartsJson.kt:60-64` / `:114-120`），`ContentPart.kt:46/:50` 的 `@SerialName` 同口径；旧判据只查 snake ⇒ `toolMessages` **恒 0**（10-29 52 行全 0，含 352 消息 / 538K 字符的会话）。
- 附件：`buildMediaRefPartJson` 写 `{"type":"mediaRef"}`（`ChatMessageJson.kt:35`），旧判据有 `"image"` / `"attachment"` 但没有 `"mediaRef"` ⇒ 所有非图片附件在计数里消失。**两条病因不同，注释也分开写。**

**改了什么**：两条判据抽成 `internal` 纯函数（`partsJsonHasToolParts` / `partsJsonHasAttachmentParts`），snake 保留为兼容臂（依据：备份导入接受两种方言，`ConfigBackup.kt:2971/:2988`）。

**为什么不做得更多**：不改 oversized 分支（它本来就是 camel，是对的）；不改判据为 JSON 解析（见 §5 R6）。

---

## 3 装置

全部在 `/var/minis/shared/work/symptom-a/probe-b/`，**不进仓库**。

| 项 | 内容 | 结果 |
|---|---|---|
| 真实单测 F-A1 | `MemoryPressureGateTest` 扩 5 例：穿越一次 / 800 以下静默 / 滞回 650 / **真档逐位不变（负控）** / 只从采样拍推进 | 21/21 PASS |
| 变异臂 F-A1 | `arms_gate.py` M1 影子共用真档状态 · M2 去置信拍 · M3 无滞回 · M4 挂进 `level()` · M5 elevated 仍取 450 | **5/5 MATCH**（每条臂都至少打红一条**新**用例） |
| 真实单测 F-A2a | `ChatAgentTraceObserverRejectionMessageTest` 2 例：文案三要素 + "拒绝不改影子状态" | 2/2 PASS |
| 真实单测 F-A2b | `UnknownOutcomeLogShapeTest` 3 例：落点在消费点且纯函数零 `Log.` / 闸门是 `!outcomeKnown` / 只带元数据 | 3/3 PASS |
| 变异臂 F-A2 | `arms_fa2.py` M6 文案回退 · M7 闸门取反 · M8 日志搬进纯函数 | **3/3 MATCH** |
| 真实单测 F-A4 | `PartsJsonShapeFlagsTest` 5 例：**用真实写入器**产出 partsJson 断言命中 + "旧 snake 判据命不中"的缺陷证据 + snake 兼容臂 | 5/5 PASS |
| 变异臂 F-A4 | `arms_fa4.py` A1 回退 snake-only · A2 丢 mediaRef · A3 丢 snake 兼容 | **3/3 MATCH** |
| 本机跑法 | `run_gate.sh`（真源直编）· `run_fa4.py`（切两处纯函数 + 桩 `AssistantBlock`，其余全真源） | — |

**★ 本轮踩到的探针教训（值得记住）**：`contentPartJson.encodeToString` **不是** parts_json 的生产者。第一版测试用它产出 JSON，结果输出里**根本没有 `type` 判别字段**（`[{"value":{...}}]`）—— `JsonContentPolymorphicSerializer` 在编码侧不写 discriminator。若照第一版做"契约测试"，钉住的是一个应用**从不写出的形状**。正解 = 去读真实写入器（`ChatTurnPartsJson.kt` / `ChatMessageJson.kt`），测试才对生产有意义。

---

## 4 CI 判读

**run #2931 / `40915799209`**（`workflow_dispatch`，attempt 1）：**success**，`16:52:18Z → 17:08:23Z` ≈ 16.1 min。
`head_sha` = **`d260e8e859e5a56ea6ff3e8c64f8f799cfbb32c2`**，与本地 HEAD **逐字符一致**。
27 步 = **25 success + 2 skipped**（唯一两个 skipped = `Move the rolling tag android-latest to this commit` / `Publish to Releases`，两者都带 main 门控，分支运行本就该跳过）；无其它非 success。

日志（zip 723,298 B → 解压 3,353,062 B）：

| 判据 | 读数 |
|---|---|
| `Summary: 113 passed, 0 failed` | 出现 2 次，内容与基线一致 |
| `ALL 349 CASES PASS` | 2 次 |
| 编译错 `^e: ` | **0** |
| 真 `FAILED` 行（排除 PASSED 子串） | **0** |
| NUL 字节 | **0**（逐文件 `b"\x00".count` 统计，不是空模式 grep） |
| 本批新增 10 条用例 | 每条 **2 PASSED / 0 FAILED**（`MemoryPressureGateTest` 新 5 例、`ChatAgentTraceObserverRejectionMessageTest` 2 例、`UnknownOutcomeLogShapeTest` 3 例、`PartsJsonShapeFlagsTest` 5 例全部在册） |

> 判读口径提醒：`| grep FAILED` 会命中 PASSED 子串，本表用的是"排除 PASSED 后为空"的判据；NUL 检查用 python 逐字节计数（`grep -c $'\000'` 在 BusyBox 下是空模式，会数出全部行数——本轮第一次就踩到了）。

---

## 5 残留（每条带触发条件）

| # | 残留 | 触发条件 |
|---|---|---|
| R1 | 影子水线只覆盖 anon 主指标；S3 的真因（系统侧回收）仍未被直接证实（无 lmkd 日志） | 下次真机 `grep MemGateShadow`：若一天内多次穿越 800 ⇒ 有资格谈降阈值；若整天 0 行 ⇒ "降档无用"这个假设也可以证伪 |
| R2 | T7-D 拒绝仍在 `warn` 通道（降 `info` 需新注入槽位） | 做日志级别治理时，与构造计数断言一并改 |
| R3 | 影子水线不做配置项（800 硬编码） | 需要现场调其它档位观测时（现在一次都没跑过，先别加开关） |
| R4 | `outcome-unknown` 日志只覆盖 shell 路径（`executeWithShellRetry`）；非 shell 工具的 `outcomeKnown=false` 不经过这里 | 出现"非 shell 工具的未知结局"统计需求时 |
| R5 | 压缩自愈验收门 `:1123` 未改（登记不修） | UI 重新引入依赖 `insertIdx` 的产物（divider / graying / "已折叠"标记）——**先修门再上产物** |
| R6 | `messages-shape` 计数器仍是"字符串包含"：若工具输出正文里引用了 `"type":"toolUse"`（例如模型在讨论本仓代码）会误计 | 该计数器被用来做**结论性统计**时，改成 JSON 解析 |
| R7 | 真机未验：本支证据 = CI 编译级 + JVM 级 + 源码形状 | 下次真机日志里 `grep MemGateShadow`（影子行）与 `grep "outcome unknown (reason="`（判定行）应各出现 |
| R8 | `ChatTranscriptRebuild` 丢弃 tool-only 用户行的行为本身没变（只把注释改成事实） | 若将来要让"折叠边界"重新可见，先按 R5 修门 |
