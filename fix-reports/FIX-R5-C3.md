# FIX-R5-C3：编辑与工具门禁七条（C-C-04①② / C-C-05 / C-C-06 / C-C-07①② / C-C-09）

分支 `fix/r5-c3-edit-toolgates` · 基线 `f3def6ce` · 来源货架报告 R5-C（`r5-1029/reports/R5-C.md`）

> **口径先行**：用户给本支的硬约束 ③ 点了五个文件；任务书 §0 另外明确
> 「无主文件可动：`ChatRetryOps.kt`、`ChatModelRouting.kt`、`ChatQueueInterruption.kt`、
> `StopCleanupPlan.kt`」，且 §2 把 C-C-04② 指派给前两个文件（「两处补齐（无主文件，本支独占）」）。
> 本支按任务书执行，**生产文件共 7 个**（多出的两个正是 C-C-04② 的两处兄弟回滚路径）。
> 两个文件与其余 11 支文件集经 DISPATCH.md 核对两两无交集，并集不会冲突。

## 1. 七条总览

| ID | 先证结论 | 修法 | 证据 |
|---|---|---|---|
| C-C-09 | **成立**（阻断文案是解析契约的破坏者） | 阻断文案改为 `unknown tool '<name>' attempted N times`（`UNKNOWN_TOOL_RE_1` 捕获），阻断调用回灌 `record()` 后 streak 不再归零 | ToolLoopDetectorTest 新增"自身阻断文案往返"用例（真 message + 真 check 输出） |
| C-C-06 | **成立**（两个断窗） | 续写提醒写入推迟到拿到槽位之后（claim 复查 + `return@launch`）；cleanup 侧加 reminder-tail 探针，孤儿提醒行删掉 + `canResume` 恢复；文案单一来源 `CONTINUATION_REMINDER_TEXT` | StopCleanupPlanTest 3 例 + Wiring 2 例 |
| C-C-05 | **成立**（门读编辑前压力 → 挡缩短上下文的编辑；陈旧编辑链丢数据） | 编辑发送跳过三道上下文门；EXHAUSTED 早退分支清 `_editingMessageId`/`_preEditAttachments` | Wiring 2 例（三道门 guard + 早退清态窗口） |
| C-C-04① | **成立**（原地重写行 → marker 锚点存活 → 摘要描述已删内容） | 只改调用点：`rerunFromToolBlock` 子消息截断后按 `lastCompactedMessageId` 精确丢 marker + 摘要缓存；判定谓词 `compactMarkerAnchoredOnRow` 留在本文件（未动 ChatSessionLifecycle） | Wiring 1 例（guard 原文 + 调用点在定义之前） |
| C-C-04② | **成立**（两条兄弟回滚路径缺同一对清理） | `retryLast` / `switchModelAndRerun` 补 `dropCompactMarkerIfRewound` + `resetContextPressureState` | Wiring 1 例（两文件均断言调用在 rollback 之后） |
| C-C-07① | **成立**（子代理每次工具执行不计预算；被拒绝的 dispatch 也算 skill 使用） | `executeSubagentTool` 加 deadline + `t7ConsumeAndTrace(TOOL_CALLS)` 双门；`recordSkillUse` 移到 `isSubagent` gate 之后 | Wiring 1 例（gate 顺序 + 双拒绝对文案在 `runSubagentLoop` 之前） |
| C-C-07② | **成立**（provider 为 null 时 fallback 无条件覆盖好标题） | `titleFailureIsFinal` 移到 provider 解析之前；fallback 受其门控，else 分支留日志 | Wiring 1 例（谓词定义在 null 分支之前 + fallback 在 `if (!titleFailureIsFinal())` 内） |

## 2. 逐条先证与修法

### 2.1 C-C-09 —— unknown_tool_repeat 的阻断文案导致的自我解除

真码（`ToolLoopDetector.check`，`unknownStreak >= config.unknownToolThreshold` 分支）：

- 阻断文案：`"[LOOP BLOCKED] CRITICAL: attempted unavailable tool '$toolName' occupied N times…"`（旧）。
- 引擎阻断臂把 `precheck.message` 作为 `errorMessage` 回灌 `record()`（AgentLoopEngine 未改）。
- `record()` 用 `extractUnknownToolName(errorMessage)` 分类；两条正则：
  - `UNKNOWN_TOOL_RE_1 = unknown(?:\s+or\s+forbidden)?\s+tool[:\s]+["']?([a-zA-Z0-9_\-]+(\.[...])*)["']?`
  - `UNKNOWN_TOOL_RE_2 = tool\s+["']?([...])["']?\s+(?:not found|is not available)`
- `attempted unavailable tool 'x' …` 两条都不命中 ⇒ `unknownToolName = null` ⇒ `countUnknownStreakFromTail` 从尾条目即断 ⇒ **每次阻断把 streak 归零**，每 threshold+1 次幻觉只拦 1 次（default threshold = 10）。

修法：文案改成 `unknown tool '<name>' attempted N times`（保留"unknown tool '<name>'"就够 RE_1 捕获；`[:\s]+` 允许空格）；加注释写明这是 parser contract。

测试：`unknown_tool_repeat survives its own blocked message recorded back` —— 10 条真 `UnknownToolMessage.message` → CRITICAL → 把 `blocked.message` 回灌 `record()` → 再 check 仍 CRITICAL（两次）。旧文案下第二次 check 会回到 NONE。

### 2.2 C-C-06 —— 续写提醒的两个断窗

旧壳（`resume`）在 `acquireSlot` / 首包等待**之前**就落库 + `agentHistory.add(说明行)`：
1. 取消在 `acquireSlot` 期间（job 被取消）→ 上一轮助手正文重复落库（DB 里已有对应行）。
2. 取消在占位后首包前 → `planStopCleanup` 看到 history 尾是 USER（提醒行）⇒ `canResume = false`（Resume banner 消失）+ 孤儿提醒行留在 transcript。

修法（两层，互补）：
- **写侧**：提醒写入移进 `streamJob`，在 `SessionConcurrencyManager.acquireSlot(activeSessionId)` 之后、`SessionActivityTracker.setActive` 之前；写前做 claim 复查（`isActive` / `_isStreaming` / epoch / token），失败 `return@launch`（外层 `finally` 释放槽位）。Stop 在槽位排队期 = job 取消、提醒从未产生。
- **清侧（自愈）**：`planStopCleanup` 增加 `historyEndsWithContinuationReminder`（纯谓词 `isContinuationReminderTail(role, content)`，与写侧共用 `CONTINUATION_REMINDER_TEXT`）；命中且 `text.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant` → `StopCleanupPlan(dropPlaceholder = true, canResume = true, dropContinuationReminder = true)`；`ChatQueueInterruption` 的 placeholder 臂调 `dropContinuationReminderTail()`：内存 `removeAt(lastIndex)` + DB 仅在"该行仍是 DB 尾"时 `deleteMessagesAfter(sortOrder)`（否则保留 + 警告，拒绝部分删除）。

测试：`empty bubble stopped over the reminder drops both rows and stays resumable` / `empty bubble over a real user tail keeps the pre-existing non-resumable drop` / `reminder tail matcher accepts only the verbatim reminder written as a user turn`；Wiring：`resume writes the reminder only after the slot it waits on`（write 索引 > 该 write 之前最近的 acquireSlot；claim 日志在两者之间）、`stop cleanup removes the orphan reminder and keeps the bubble resumable`。

### 2.3 C-C-05 —— 上下文门在编辑路径上读的是编辑前压力

流程：`sendMessage` → 三道门（`checkContextBeforeSend` / `maybeTriggerAutoCompact` / `awaitAutoCompactIfNeeded`）→ 壳内才 `truncateBeforeEdit`。编辑一条长历史中段的消息时，门按**编辑前**的 token 计判断定 EXHAUSTED → 缩短上下文的编辑（唯一能救场的操作）被挡；且 `pendingExhaustedText` 缓存 + 编辑态未清 → 陈旧编辑链在用户下一次普通发送时截断历史（数据丢失）。

修法：`val editingSend = _editingMessageId.value != null`；三道门全部加 `!editingSend` 前缀（编辑路径截断后由壳内既有的 `resetContextPressureState()` 重算压力）；EXHAUSTED 早退分支清 `_editingMessageId` / `_preEditAttachments`（结构不变量：被拒的发送不能留下自己持有的编辑态；注释写明今天走不到，防门序回调）。

### 2.4 C-C-04① —— 子消息切片的锚点存活

`rerunFromToolBlock` 的子消息分支（`cutPartIdx > 0`）：`updateMessageParts` **原地重写**同一行（主键存活），只 `deleteMessagesAfter(row.sortOrder + 1)`。`dropCompactMarkerIfRewound` 的判据是「marker 锚点不在存活 id 里」，重写行仍存活 ⇒ 判无损 ⇒ 摘要继续描述被截掉的 tool_call 与本轮文本。

修法（只改调用点，判定函数未动）：新增纯谓词 `compactMarkerAnchoredOnRow(marker, rowId) = marker?.lastCompactedMessageId?.takeIf { it.isNotEmpty() } == rowId` + 助手 `dropCompactMarkerIfAnchoredOnRow(sid, truncatedRowId)`（仅锚点是该行时 `dao.deleteCompactMarkers(sid)` + 清 `_cachedLatestMarker` / `_compactSummary`）；在子消息分支 `dropCompactMarkerIfRewound` 之后调用。谓词层面的修法属 ChatSessionLifecycle（C1 文件），本支不碰。

### 2.5 C-C-04② —— 两条兄弟回滚路径

`retryLast`（ChatRetryOps）与 `switchModelAndRerun`（ChatModelRouting）都 `rollbackIncompleteTurn()` + `deleteMessagesAfter` 后重建历史，却不调 `dropCompactMarkerIfRewound` / `resetContextPressureState`（编辑路径与 rerunFromToolBlock 有）。修法：两处各补两条调用，位于回滚之后。

### 2.6 C-C-07① —— 子代理的工具执行逃逸预算

`executeSubagentTool` 在 `runSubagentLoop` 前无 deadline / TOOL_CALLS 门（主 `executeTool` 每个调用都过）；`recordSkillUse` 位于 `isSubagent` 检查之前 ⇒ 被拒的派发也算"最近使用"。修法：`traceObserver.activeRunBudget?.isExpired()` + `t7ConsumeAndTrace(DIMENSION_TOOL_CALLS) { it.consumeToolCall() }` 双门插在 `runSubagentLoop` 之前，拒绝返回显式 `ToolExecutionResult(…, false)`（不冒充执行成功 — 前者带错误信息，后者绕过代理上下文错误分支）；`recordSkillUse` 移到 `isSubagent` gate + 工具集构建之后。

### 2.7 C-C-07② —— provider 为 null 的 fallback 门控

`generateSessionTitle` 里 `if (provider == null)` 分支无条件 `applyFallbackTitleFromFirstMessage`（覆盖 `_sessionTitle` + DB），而 `titleFailureIsFinal()`（`attempts >= TITLE_MAX_ATTEMPTS || (force && needsGeneration)`）在解析之后才用 ⇒ 手动 regen 已起好标题 + 无 provider 时标题被截断首句覆盖。修法：谓词定义前移；fallback 包进 `if (!titleFailureIsFinal())`，else 只记日志。

## 3. 改动面

```
 agent/ToolLoopDetector.kt          |  15 +-
 ui/chat/ChatModelRouting.kt        |  10 ++
 ui/chat/ChatPromptAndTools.kt      |  84 ++++++---
 ui/chat/ChatQueueInterruption.kt   |  67 ++++++-
 ui/chat/ChatRetryOps.kt            |  11 ++
 ui/chat/ChatViewModel.kt           | 198 ++++++++++++++++-----
 ui/chat/StopCleanupPlan.kt         |  58 +++++-
 agent/ToolLoopDetectorTest.kt      |  37 ++++
 ui/chat/R5C3EditToolGatesWiringTest.kt (新) | 170 ++++++++++++++
 ui/chat/StopCleanupPlanTest.kt     |  32 ++++
 10 files changed, 612 insertions(+), 70 deletions(-)
```

生产 7 文件 / 测试 2 改 1 新。`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰；`AgentLoopEngine.kt`、`ChatSessionLifecycle.kt` 零触碰（硬约束 ①）。

## 4. 验证

| 判据 | 读数 |
|---|---|
| 真词法配平（仓库 `scripts/scan/_kotlin_lexer.mask_code`） | 7 生产 + 3 测试文件 braces=0 / parens=0 / 无 NUL（rc=0） |
| 新增用例 | ToolLoopDetectorTest +1（往返阻断文案）· StopCleanupPlanTest +3 · R5C3EditToolGatesWiringTest 8 例 |
| 变异臂（人工推演） | 旧文案 → 往返用例第二次 check 回 NONE（红）；去掉 `!editingSend` 前缀 → Wiring 断言红；`dropContinuationReminder` 不置位 → StopCleanupPlanTest 红；`recordSkillUse` 回移 → Wiring 顺序断言红 |
| 分支 CI | run `40857735306`（head_sha 见 §6） |

**未做（按硬约束 ② 禁跑全量）**：本地未跑 `scan.sh` / `test_scan.py` / gradle 全量；纯逻辑用例的 JVM 隔离运行未做（`AppLogger` 依赖 `android.util.Log`，ToolLoopDetector 无法脱离 android.jar 编译，CI 是唯一执行面）。

## 5. 残留与负决策

- **R1（负决策，登记）**：`record()` 对未知工具的识别依赖 `errorMessage` 文案 —— 本支修的是"我们的阻断文案保持可解析"，没有把结构化字段（如 `ToolLoopDetector` 显式收 `unknownToolName` 参数）作为契约。理由：那要求改 `record()` 签名 + AgentLoopEngine 调用点（硬约束 ① 禁改 AgentLoopEngine），触发条件 = 下次再因文案漂移导致 streak/计数失效。
- **R2（已登记，未动）**：C-C-04① 的谓词层修法在 ChatSessionLifecycle（"重写行 = 截断"的通用判据），本支只在 rerunFromToolBlock 调用点补齐；其余原地重写场景（如未来新增的 part 级编辑）需 C1 面统一承接。
- **R3（观察）**：`dropContinuationReminderTail` 的 DB 删除是"仅当仍是 DB 尾"才动；若 Stop 与另一写并发错序，保留孤儿行 + 警告（拒绝部分删除）。日志 `stop-cleanup: continuation reminder … is not the DB tail — kept` 可作真机观测点。
- **R4（真实机未验）**：C-C-05 的 EXHAUSTED 编辑链、C-C-06 的提醒窗口 Stop、C-C-07① 的子代理预算拒绝文案，均只有 CI 编译级 + JVM 级 + 源码形状证据；真机复现步骤见任务书 §4。

## 6. CI 判读

- run `40857735306` @ `2026-10-29T10:27:39Z`（dispatch `fix/r5-c3-edit-toolgates`）。
- 判据：`conclusion == success` 且 `head_sha` 与本地 `2ecacbef` 逐字符一致；日志 `Summary: … 0 failed`、`ALL … CASES PASS`、`^ e: ` 0、NUL 0。
- 报告提交（本文件）在绿之后 push，收口判据：`git diff --name-only <绿sha>..HEAD -- . ':(exclude)fix-reports'` 为空。

## 7. 登记

- backlog：R1 触发条件条目（record 签名结构化）、R2 谓词层修法归 C1、R3 真机观测点。
- 无新增门脚本需求（本支未改 `scripts/scan/**`）。

## 8. 方法反馈

- **"阻断文案是解析契约"这条值得进规则库**：同族形态 = 写侧产出的人类可读文案被读侧用正则解析，文案与解析器分两个文件、各自有测试 → 两侧单测全绿而链路失效（与 r3-B11-F04 的 `"foobar."` 尾点同族）。判据：凡是 `record()/classify()` 吃 `errorMessage/content` 字符串的地方，测试必须喂**真实生产函数的输出**，不能手写字符串。
- **纯决策核 + 接线断言的分工再次验证有效**：C-C-06 的决策（3 例纯函数）与接线（2 例顺序断言）分开钉；C-C-05/07 只有接线，用"索引顺序 + guard 原文"而非语义重述。
- **读侧"看似已修"的陷阱**：`ChatViewModel` 的注释里写着 `[FIX-R4-P F-05/F-07]`，代码也确实做了那两处，但**同一族**的 retryLast / switchModelAndRerun 没跟上（C-C-04②）——"修了共享函数的调用点之一"是 1028 批反复出现的同族复发形态，本支按 `rg -l <函数名>` 全列举才看见。
- **两次误编辑**（`rerunFromToolBlock` 头部、isSubagent 段）都靠 `git diff` 复核当场发现并恢复 —— 大批量 sed/编辑后必须逐文件 `git diff` 过一遍，不能只信 apply 成功。
