# FIX-R5-C2：续写落库 2 项（R5 修后复扫批 2026-10-29）

> 本报告由主会话补写（支会话在 CI 绿后、写报告前中止；代码与测试均为支会话所写，本报告按分支实改 + 提交记录 + CI 记录取证）。
> 支会话 id `df41282c` · 分支 `fix/r5-c2-continuation-persist` · 基线 main `f3def6ce`

## 1. 判定表

| 报告条目 | 先证结论 | 说明 |
|---|---|---|
| C-C-02①（错误形态续写绕过合并） | **成立** | 错误形态续写分支 `agentHistory += ASSISTANT` 追加第二个助手条目（dbMessageId 空），不折进未落库的续写条目；堆叠守卫只认 EOF-stub 提醒族，长度墙提醒残留其下 |
| C-C-02②（空文本终态丢续写块） | **成立** | 两个空回复终态写 `Text("")` 行后 break，丢弃此前 `continue` 分支已流入 `allToolBlocks` 的全部未落库段 |
| C-F-03（轮数/预算收尾丢续写 + 错误标错行） | **成立**（主会话独立证实） | `finalizeAtTurnLimit` / `finalizeBudgetStop` 全程不调 `persistAssistantTurn`；`updateLastAssistantError` = 「最后一条助手行」的横幅落到上一轮 |

同一根因（测试 KDoc 原文）：**no-tool 尾部把回合行延后写入（正确——续写的答案必须是同一行），而每一条不经该尾部的退出路径都欠未落库段一次写入且没写**。

## 2. 修法（分支 3 提交）

- `1be5537c` 核心修复：新增 `flushUnpersistedTurnRow`（`persistAssistantTurn` + `turnBlockMeta` + `markTurnPersisted`）作为统一落库出口；两个 finalize 前先按 `mustFlushBeforeFinalize(unpersistedBlockStart, blockCount)` 判定补写；两个空回复终态经 `TerminalErrorPlacement.partsForTerminalError` 携带未落库段写出行（空段也写空白行给横幅用）；错误形态续写折进同一助手条目（`ContinuationHistoryMerge`），注入点守卫 OR 上 `isLengthWallReminder`。
- `b65403d8`（round 1 修）：两道形状探针钉的是真契约不是障碍——`EofStubStackingGuardTest` 要求两个注入点**各自**在 `size-2`/`removeAt` 500 字符内调共享守卫 ⇒ 合成谓词把调用挪离了站点，改为「一族一谓词、在站点处 OR」；`TerminalErrorPlacementTest` 用字面量 `host.persistAssistantTurn(` 锚横幅 ⇒ 探针把共享 helper 计为 persist（其函数体就是该调用），保持探针测「横幅前本回合有行」而非拼写。
- `7433b21b`（round 2 修）：横幅锚定字面量回归；长度墙族判据单一来源化——注入点 OR `EofStubReminderStackGuard.isStaleEofStubReminder`（与 EOF-stub 分支共享，调用数维持 2/门）+ `ContinuationHistoryMerge.isLengthWallReminder`，分支内联的「cut off mid-sentence」字面量替换为同一谓词，无第三份拷贝。

## 3. 证据（两层，与本批其他支同构）

- **行为层（真循环 JVM）**：`R5ContinuationPersistTest`（454 行）用 `FakeAgentLoopHost` + `AgentRunBudgetCeilings` 驱动真 `AgentLoopEngine`：
  - `turnCeilingAfterContinuations_persistsTheUnpersistedSegments`（C-F-03）
  - `cleanCompletion_doesNotProduceAnExtraFlushRow`（正常完成不得多出 flush 行——防过度修复负例）
  - `errorShapedEmptyAfterRetry_keepsTheEarlierContinuationSegments` / `..._stillWritesTheBlankRowForTheBanner`（C-C-02② 两面）
  - `errorShapedContinuation_foldsIntoTheSameAssistantEntry`（C-C-02①）
- **判定层（表驱动）**：`flushPolicy_table`（哪条退出路径必须先补写）、`staleContinuationReminder_coversBothFamilies`（两族提醒判据）。
- **形状层**：`preFinalizeFlushRunsBeforeBothFinalizers` / `emptyReplyTerminalsCarryTheUnpersistedSliceIntoTheirBannerRow` / `errorShapedContinuationFoldsAndGuardsBothFamilies` 读源码断言（finalize 调用点前有 flush、空回复终态携带未落库段、注入点守卫两族齐全），并保留两道既有探针的**负控仍咬合**（长度墙横幅锚远离出口 flush；探针仍拒绝修复前顺序）。

## 4. CI

| 轮 | run | sha | 结论 | 说明 |
|---|---|---|---|---|
| 1 | `40856052731` (#2903) | `1be5537c` | failure | 两道形状探针（EofStubStackingGuard / TerminalErrorPlacement）抓到真契约违约 |
| 2 | `40859836487` (#2911) | `7433b21b` | **success** | head_sha 与分支 tip 逐字符一致（`7433b21bcf7f…`）；中间提交 `b65403d8` 与 tip 连续推送后一次 dispatch |

停止节点达成：分支 CI 绿 + 非报告差异 = 0（本报告提交在绿之后，属允许形态）。

## 5. 残留（登记不修，均带触发条件）

- R1：`flushUnpersistedTurnRow` 只覆盖「块级未落库段」；文本流（`accumulatedText` 中未 append 进块的部分）依赖既有 `_streamingById` 收尾语义——若出现「流式文本丢但块都在」，需扩 flush 判定。｜触发：用户报「重载后回复尾部缺一段文字」。
- R2：错误形态续写折进同一行的判定靠 `ContinuationHistoryMerge` 的角色/长度启发式，极端长回复（同轮多段几乎等长）可能折错行——与 C1 支的 `mergeSameRoleNeighbors` 同族，等真实案例再收紧。｜触发：用户报「错误续写后历史行序错乱」。
- R3：真机未验（全部证据 = CI 编译级 + JVM 行为级 + 源码形状级）。｜触发：下次装包走查续写/续跑场景。

## 6. 复现

```bash
cd /var/minis/shared/work/r5-1029/wt-c2
# JVM 行为+判定+形状三层同文件：
kotlinc -no-stdlib …（同批配方：-cp kotlin-stdlib:junit:hamcrest）
java -cp … org.junit.runner.JUnitCore com.rikkaminis.app.ui.chat.R5ContinuationPersistTest
```

## 7. 负控

- `cleanCompletion_doesNotProduceAnExtraFlushRow`：正常完成路径**不得**多写一行（把「统一落库出口」过度应用到正常尾部会立刻红）。
- 形状探针负控保留：删掉注入点 OR 的 `isLengthWallReminder` ⇒ `staleContinuationReminder_coversBothFamilies` 红；把 flush 挪到 finalize 之后 ⇒ `preFinalizeFlushRunsBeforeBothFinalizers` 红。

## 8. 支会话交付说明

支会话完成全部代码/测试/两轮 CI 判读，在写报告前中止（消息 458 条止于「CI 已绿」）。本报告按其三个提交 + 测试 KDoc + CI 记录补写，主会话核对过 diff 与运行记录；判级表里的「主会话独立证实」指 2026-10-29 主会话抽验（`finalizeAtTurnLimit`/`finalizeBudgetStop` 无 persist 调用，grep 仅注释命中）。
