# FIX-META1028-C2 — Stop 清理只提交「在飞那一轮」的文本，且每个保留气泡都可 Resume

**来源**：外部货架 B03 报告 F-02（`.high`，`ui/chat/ChatQueueInterruption.kt:686-737`），
meta-audit-1028 第二波 C2。
**基线**：main `bfb5b0e6`。
**分支**：`fix/meta1028-c2-queue-cancel`。
**改动面**：2 个生产文件（1 新增 + 1 修改）+ 1 个新增单测文件；`scripts/scan/**` 零触碰。

**这改变什么用户行为？**
1. 多轮工具任务中途点 Stop，聊天记录里不再出现「把前几轮文本又贴一遍」的重复助手回复，
   下一次请求的上下文里也不再带重复文本（旧行为是每一轮 Stop 都重复一次，轮次越多越重）。
2. 「第一轮只有工具调用、用户在第 2 轮思考间隙点 Stop」这个形态，现在会出「Resume（继续）」
   横幅并可继续；旧行为是三个分支全落空 —— 气泡留着、既不删也不可恢复，任务卡死。

---

## 1. 先证（回 `bfb5b0e6` 真树读到的原文，不采信登记行号）

### 1.1 四环路径

| 环 | 证据（基线树原文） |
|---|---|
| ① 谁触发 | 多步 Agent 任务（`bash` / `read_file` / `web_search` …）执行到第 2 轮及以后，用户在思考间隙或流式输出中点底部 Stop；也含通知栏 `onStop`（`ChatViewModel.kt:3922` 处 `setActive(..., onStop = { cancelStream() })`）。 |
| ② 怎么走到这段 | `cancelStream()`（`ChatViewModel.kt:4093`）→ `streamJob.cancel()`（4095）→ **`flushAllStreamingDeltas()`（4108）** 先把流式旁路灌回 canonical message → `handleUserCancelledCleanup()`（4166）。Cleanup 内部：先清 `isAwaitingModelResponse` / `isStreaming`，再 Case 1（`hadInflightTools`，有在飞工具就翻 CANCELLED + 持久化 tool_result 并 **return**，`ChatQueueInterruption.kt:655-680`），没有在飞工具才落到 Case 2 尾部（原 686-737）。 |
| ③ 数据变成什么 | 见 1.2：旧尾部把气泡的两个**跨轮累加器**当成「本轮文本」提交，且分支链有一个空格子。 |
| ④ 用户看到什么 | 形态 A：重新进入会话/继续对话时出现两段（多轮时更多）重复的助手回复，模型上下文被重复文本污染。形态 B：气泡停在 tool_result 尾巴上，底部不出 Resume 横幅，中断的多步任务无法继续。 |

### 1.2 为什么「跨轮」是机制事实，不是猜测

- 单气泡契约：`AgentLoopState.kt:52-57`「Normally a single message id for the whole agent loop
  (iOS-parity: multiple tool/text turns folded into one bubble)」，`allToolBlocks` = 「All tool blocks
  across the current bubble (reset on queue injection)」→ **块列表整轮累积**。
- 气泡正文也是跨轮：`AgentLoopState.kt:37` `accumulatedText` = 「Cross-turn accumulated visible text of
  the current bubble」；每个流式 flush 站点传的都是 `accumulatedText + turnTextSb`
  （`AgentLoopEngine.kt:1233` / `1353` / `1421` / `1488` …）→ **`ChatMessage.content` 是整气泡正文**。
- 旧代码把两者都收进来（基线 `ChatQueueInterruption.kt:686-694`）：
  `append(last.content)` + 遍历 `last.toolBlocks` 里所有 `kind == "text"`。即「整气泡正文 +
  整气泡所有文本块」——第一阶段就重复，多轮再叠一层。
- 轮与轮之间的 `agentHistory` 形状：每轮先 `agentHistory.add(ASSISTANT, content = turnText)`
  （`AgentLoopEngine.kt:2528`，只放**本轮**文本），工具跑完再 `agentHistory.add(USER, tool_result)`
  （`AgentLoopEngine.kt:4018`）→ 工具轮结束后 `historyEndsWithAssistant == false`，这正是旧分支
  ②的入口条件，也正是形态 B 的空格子所在。

### 1.3 「在飞那一轮」的边界为什么可以用「最后一个 tool_use 之后」

- 工具轮在**执行工具之前**就落库了：[fix/early-turn-persist]，`AgentLoopEngine.kt:3376`
  `earlyAssistantDbId = host.persistAssistantTurn(livePreviewParts, …)`，位置在「Execute all tool calls」
  （3397）之前；`livePreviewParts` 的切片从 `unpersistedBlockStart` 起算（3361）——即「本轮文本 +
  本轮 tool_use」这一整段在执行工具前已经 durable。
- 每轮开新的文本块：`currentTextBlockSb` 是 `turnLoop` 体内的局部变量（`AgentLoopEngine.kt:819`，
  循环体起点 656），轮首为 `null` → 首个文本 delta 必走 `else` 建新块（1296-1324）；monolithic
  provider 的「tool_calls 之后又来 content」被并回本轮 pre-tool 文本块并**插在该轮第一个工具块之前**
  （1315-1321）。⇒ 某轮文本块的位置恒在**该轮 tool_use 之前**。
- 所以 Stop 时刻「还没落库的文本」= 最后一个 `tool_use` 之后的文本块。这与循环侧
  `loopState.unpersistedBlockStart`（`AgentLoopState.kt:57-84`：「尚未落库的块起点」）在可达场景下等价，
  差别只在于后者是 `runAgentLoop` 内的局部状态，VM 层拿不到（见 §6 R3）。

### 1.4 为什么门与既有单测不拦

- `rg 'partialText|hasAnyToolUse|handleUserCancelledCleanup' scripts/ src/android/app/src/test/` 在基线树上
  **0 命中**（`scripts/scan/discarded_return_guard.py` / `stale_closure_guard.py` / `claim_release_guard.py`
  提到 `ChatQueueInterruption.kt`，但钉的是 `truncateBeforeEdit` / `injectQueuedPromptsAsNewTurn` /
  `drainQueuedPrompts` / `resumeQueueAfterCancel`，与本函数无关）。
- 报告 F-02「为什么门与单测不拦」的判断经复核成立：没有任何夹具构造「第 1 轮已完成 tool_use +
  第 2 轮点 Stop」的跨轮气泡。本支因此把决策抽成纯函数，新增 16 例直接钉住该形态。

---

## 2. 修法逐条

### 2.1 新增 `ui/chat/StopCleanupPlan.kt`（纯逻辑，无 Android 依赖）

```kotlin
internal fun unpersistedTurnText(blocks: List<AssistantBlock>): String   // 最后一个 tool_use 之后的文本块
internal data class StopCleanupPlan(dropPlaceholder, persistText, canResume)
internal fun planStopCleanup(blocks, historyEndsWithAssistant): StopCleanupPlan
```

决策表（穷尽，每个格子恰好落一支）：

| text | tool_use | history=assistant | 结果 |
|---|---|---|---|
| "" | 无 | 否 | **drop**（Case 0 占位气泡） |
| "" | 有 | 否 | keep + **resume**（形态 B 的修复） |
| "" | 任意 | 是 | keep + resume |
| 有 | 任意 | 否 | **提交 text** + resume |
| 有 | 任意 | 是 | keep + resume（已落库，再提交就是重复） |

KDoc 里写清了两个缺陷的机制（跨轮载体 / 空格子）与边界的来源（early-persist 的落库时序 +
每轮新建文本块），避免下一次改动把边界当成随意常量。

### 2.2 `ChatQueueInterruption.kt` 尾部改为问纯函数（净 −21/+37 行里全是这一段）

- 删掉 `buildString { append(last.content); for (b in last.toolBlocks) … }`（形态 A 的源头）；
- `val stopPlan = planStopCleanup(last.toolBlocks, historyEndsWithAssistant)`；
- `if (stopPlan.dropPlaceholder)` → 删占位返回（与旧 Case 0 同一动作）；
- `val partialText = stopPlan.persistText; if (partialText != null) { …原持久化代码逐字不动… }`；
- 收口改为 `if (stopPlan.canResume) _canResume.value = true`（删掉 `else if (historyEndsWithAssistant)`）；
- Case 1（在飞工具）**一字未动**，仍在尾部之前 `return`；单测里加了一条「Case 1 必须早于 planner」的
  源码契约断言，防将来重排。

### 2.3 幂等（任务书 §3 第三条）

同一 Stop 只追加一次：第一次走「提交」支后 `agentHistory` 以 ASSISTANT 结尾，第二次 Stop 同气泡落
「keep + resume」支、`persistText == null`。由 `one Stop appends once - the second Stop sees its own row`
一例钉住（两次调用，输入只差 `historyEndsWithAssistant`）。

### 2.4 阶梯停在哪一级

停在第 7 级（最小实现）：纯函数 + 一次委托，不新增 VM 状态、不改 `AgentLoopHost` 接口、不动 loop。
第 2 级（代码库里已有）查过：`buildTurnPartsPure` / `TurnVisibleContent` / `queueDrainShouldDeferForCompact`
是同族先例，`internal` 顶层跨文件在本仓 CI（K2）里是既有绿灯形态，故照抄该形态。

---

## 3. 装置（P1 级：真实单测 + 六臂变异 + 一条对照臂，不建新门）

**位置**：`/var/minis/shared/work/meta-audit-1028/probe-c2/`（探针，不进仓库）；
仓库内只留真单测 `src/android/app/src/test/java/com/rikkaminis/app/ui/chat/StopCleanupPlanTest.kt`（16 例）。

- 探针编的是**真源码**：`ChatModels.kt` + `InputAttachment.kt` + `StopCleanupPlan.kt` + 真测试文件，
  只给 4 个 Android/Compose 类型打桩（`androidx.compose.runtime.Immutable`、`android.net.Uri`、
  `androidx.compose.ui.graphics.vector.ImageVector`、`com.rikkaminis.app.data.model.ThinkingLevel`）。
  期望值全部是字面量（不信被测常量推导——自指陷阱）。
- 三场景（任务书 §4）+ 追加：单轮文本 Stop / 多轮工具 Stop / 纯工具首轮 Stop（形态 B）/
  双工具轮 / 三轮取最后一轮 / 同轮多文本块顺序与分隔 / 空文本块 / 历史已落库不重复 / 幂等 /
  纯 thinking 不提交 / 源码契约 ×2 / Case 1 早于 planner。

### 3.1 对照臂（同一 fixture，修复前后各算一次）

`probe-c2/LegacyCompare.kt` 里逐字复刻旧的 `buildString`，与 `unpersistedTurnText` 同输入对跑：

| 场景 | 旧（会写进 DB / agentHistory 的文本） | 新 |
|---|---|---|
| 两轮（第 1 轮文本+工具，第 2 轮文本） | `Let me check that.Checking now\nLet me check that.\nChecking now` | `Checking now` |
| 单轮纯文本 | `答案是四十二。\n答案是四十二。` | `答案是四十二。` |
| 第 1 轮纯工具，第 2 轮间隙 Stop | `""`（三支全 false → 不提交也不 resume） | `""` + `canResume=true` |

第一行就是形态 A 的现场：旧代码把第 1 轮文本贴了两次（content 里一次、文本块里一次）；
第二行说明**单轮** Stop 同样重复（不是只有多轮才坏）。

### 3.2 六臂变异负控（每条只改一行，只红钉它的用例）

| 臂 | 变异（还原成修复前/邻接形态） | 结果 |
|---|---|---|
| M1 | 边界 `indexOfLast` → `indexOfFirst` | 2 红：`the LAST tool_use defines the boundary` / `text after two completed tool rounds…` |
| M2 | 边界恒为 0（= 修复前全量收集） | 4 红：上述 2 条 + `multi-round tool stop commits only the round in flight` / `a tool round produced no text yet…` |
| M3 | 收口退回 `canResume = historyEndsWithAssistant`（形态 B 复原） | 2 红：两条 `pure-tool … resumable` |
| M4 | 提交支去掉 `!historyEndsWithAssistant`（重复提交） | 2 红：`history already ending on the assistant turn never commits twice` / 幂等那条 |
| M5 | 文本块分隔 `'\n'` → `' '` | 1 红：`several text blocks … keep order and separation` |
| M6 | drop 支去掉 `!hasAnyToolUse` | 2 红：两条 `pure-tool … resumable` |

变异后基线复跑 **16/16 OK**（`probe-c2/run.sh`），说明红是变异造成的、不是装置不稳。

---

## 4. 本地预检（并发批纪律：不跑全量 scan / test_scan / gradle）

- 真单测（JVM，真源码 + 桩）：`OK (16 tests)`，0.402s。
- 变异六臂：见 §3.2，六条全部「只红钉住它的用例」。
- 静态自检：三个文件 `<<<<<<<` 0 命中；`{} () []` 配平（`ChatQueueInterruption.kt` 72/72、205/205、5/5；
  新文件 8/8、17/17、1/1；测试 19/19、145/145、0/0）。
- 门面核查：`rg 'partialText|hasAnyToolUse|Content may be incomplete' scripts/` → 0 命中，本支不欠门的债；
  `scripts/scan/**` 零改动（`git status --porcelain` 只有 3 个文件）。
- 沙箱负载：全程 `nice -n 10`、单文件 kotlinc（不启 gradle）、单 JVM 串行；变异臂一次只跑一支
  （第一轮 shell 并发触发过一次 `Function not implemented`（S28），改为单臂单调用后全部正常）。
- 同族清扫（"有没有别的地方也要改"）：`buildTextOnlyAssistantPartsJson`（这条持久化载荷的唯一构造器）
  在真树上**只有一个调用点** = 本函数（`rg` 全仓 1 处定义 + 1 处调用）；`append(last.content)` 形态全仓 0 命中。
  另有 7 处 `filter { it.kind == "text" … }` 全在渲染层（`ChatFlatItems.kt` / `legacy/*`），按设计就该看整气泡，
  不在持久化面上。

### 4.1 未能本地验证的部分（诚实边界，见 §6）

`ChatQueueInterruption.kt` 依赖 `ChatViewModel` / Room / Compose，沙箱无法整体编译；本支对该文件的
证据是「改动面逐行 diff + 括号配平 + 源码契约单测 + 云端编译」。**编译通过与否由分支 CI 回答。**

---

## 5. 云端 CI

**run `40679989585` / attempt 1 / `build` job = success**，`head_sha 717d8479cac68cb56ee71cb2bb7764425801b014`
与本地 HEAD **逐字符一致**；11:26:39Z → 11:47:51Z（**21m15s**）。

判读（全部机械核对，不靠印象）：

| 判据 | 读数 |
|---|---|
| 步骤 | 25 步，唯一 2 个非 success = `Move the rolling tag android-latest` / `Publish to Releases`（**均为 main 门控 skip**，分支上就该跳） |
| 日志 | 归档 655,883 B（zip）→ 解压 3,006,799 字符，**NUL 0** |
| 静态门 | `║ Summary: 113 passed, 0 failed` ×2（日志双流各一次）；门头 `[n/113]` 共 226 行、**distinct 113** |
| 夹具 | `✅ ALL 349 CASES PASS` ×2 |
| Kotlin 编译 | ` e: ` **0 命中**（我改的那个 4000 行文件编译通过＝本地无法整体编译的缺口由此补上） |
| 新单测 | `StopCleanupPlanTest` **16 条全 PASSED**（`handleUserCancelledCleanup` 关键字 0 命中＝原来确实没有别的测试碰这段） |
| 假红排查 | 12 行含 `FAILED` 的日志**全部是 PASSED 用例名的子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED…` 等），逐行看过 |
| 其它 | `Summary: 25/16/44/19/30/18 passed, 0 failed`（夹具分组）+ `0 violation(s) found` |

**结论**：分支 CI 绿 = 确定性停止节点，到达。合并 main 的起手权在用户。

---

## 6. 诚实边界（未验 / 推测，逐条标）

- **U1 真机未验**：沙箱无 Android 运行时，「Resume 横幅真的出现」「重复气泡真的消失」只有编译 +
  单测 + 门 + 代码路径证据，无真机截图/日志。用户可验的判据：多轮工具任务中途 Stop → 记录里
  只有一段助手文本，且底部出现 Resume。
- **U2 报告行号**：报告给的是 `686-737`，基线树上该函数尾部确为 686-737 区间（Case 2 注释 682 起，
  函数结束 737），逐行对上；`persistedBlockCount` / `userBlockIndex` 两个名字在真树**不存在**
  （`rg` 0 命中），故边界按「最后一个 tool_use」实现（报告补丁建议里允许的第二种）。
- **R1（残留，已登记）**：ordered provider 在同一 assistant message 里「tool_use 之后又输出 text」
  时，该文本已被 early persist 落库，但「最后一个 tool_use 之后」的边界会把它再提交一次
  （形态 A 的窄化版，只重复这一段）。触发面窄：需要该 provider 形态 + 后续轮 Stop。
- **R2（残留，已登记）**：无工具的多轮续写（length-wall / EOF-stub 续写轮）在 `agentHistory` 以
  ASSISTANT 结尾时走「只置 resume、不提交」支 → 当前轮未落库的续写文本不进 DB/history。
  **与修复前一致（不是本支引入）**，属 deep-audit-1016c ISSUE-01 同一缝。
- **R3（结构性限制）**：`loopState.unpersistedBlockStart` 是 `runAgentLoop` 的局部状态
  （`AgentLoopState` 实例在引擎内部构造，`AgentLoopEngine.kt:606`），VM 层拿不到；本支用「最后一个
  tool_use」作 UI 镜像侧的等价边界。要彻底收掉 R1 需要 loop → host 回调（如 `onTurnPersisted(blockCount)`），
  成本跨 3-4 文件，按修复门纪律不随本 P0 捎带。
- **R4（既有行为，未改）**：`flushAllStreamingDeltas()` 丢弃 `st.pendingContent/pendingBlocks`
  （节流窗口内未发布的尾部），Stop 时最多丢一个节流窗口的文本。本支未动该函数。
- **R5**：Case 1（有在飞工具）路径未动；它与本修法的关系是「更早 return」，单测固定了顺序。
- **推测但未证**：旧代码在多轮场景下重复文本会让模型「被自己重复的措辞带偏」（报告 §④ 的
  「上下文污染」后果），本支只保证不再写入重复，不承诺对既有已污染会话做修复（无迁移）。

---

## 7. 新登记残留

按批次约定，本支不与其它并行会话抢写 `/var/minis/shared/backlog.md`，残留写在本节 + 三件套，
由主会话收口时并入（同 1028B 三支做法）。

- **R1**：Stop 边界对「同轮 tool_use 之后又出现文本」会重复提交一段。
  - 触发条件：ordered 形态 provider 在同一 assistant message 的 tool_use 之后继续输出 text，且用户在
    后续轮 Stop。
  - 触发后方案：把 loop 的 `unpersistedBlockStart` 通过 host 回调暴露给 VM（R3 的装置），边界直接用真值。
  - 搁置理由：需要改 `AgentLoopHost` 接口 + Host 实现 + Fake（3-4 文件），与本 P0 不同半径；
    当前形态的实际影响是「重复一段」，而原缺陷是「重复整气泡」。
- **R2**：无工具续写轮在 history 以 ASSISTANT 结尾时，未落库续写文本不提交（与修复前一致）。
  - 触发条件：length-wall / EOF-stub 续写进行中用户 Stop。
  - 触发后方案：同 R1（真值边界）；或让续写轮的 terminal persist 更早落库（ISSUE-01 族既有方案）。
  - 搁置理由：属 ISSUE-01 族，修复面在 loop 侧，已在既有登记里。
- **R4**：`flushAllStreamingDeltas` 丢弃 pending 尾块。
  - 触发条件：Stop 恰好落在节流窗口内（≤ ~200ms 的 delta）。
  - 触发后方案：把 `pendingContent/pendingBlocks` 一并 drain 进 canonical（改动在 `ChatStreamDelta.kt`）。
  - 搁置理由：影响是「尾部几十字丢在持久化里但屏幕上可见」，与本次两形态无关，且属另一文件面。

---

## 8. 收口状态

- 分支 `fix/meta1028-c2-queue-cancel`，基线 `bfb5b0e6`。
- 代码提交 `717d8479`（3 文件：+1 新生产文件 / +1 修改 / +1 新单测）。
- **停止节点已到达**：分支 CI `40679989585` success，head_sha 与 `717d8479` 逐字符一致。
  **未合并 main**，起手权在用户。
- 未验证项：U1（真机渲染/横幅）、R1/R2/R4（见 §6）；`ChatQueueInterruption.kt` 的整体编译由 CI 补上（§4.1）。

## CI 附录

- Run：https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40679989585 （attempt 1，`build` = success）
- 触发：`workflow_dispatch --workflow build-apk.yml --ref fix/meta1028-c2-queue-cancel`（分支 push 不触发 CI；
  本支有 `src/**` 真验证面，走全量构建而非廉价门）
- head_sha：`717d8479cac68cb56ee71cb2bb7764425801b014`（== 本地 HEAD）
- 日志取证脚本：`/var/minis/shared/work/meta-audit-1028/probe-c2/ci_check.py`（解压 zip 后逐条 grep；
  原始日志另存 `/tmp/c2-ci.log`）
- 本地探针：`/var/minis/shared/work/meta-audit-1028/probe-c2/`（`run.sh` 跑真单测 + 对照臂，
  `mk_mut.py` + `run_one.sh` 生成/运行六臂变异）
