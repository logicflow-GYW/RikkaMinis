# FIX-R3-1119-A — chat 域四条（F-01 / F-02 / F-11 / F-12 全修）

- 支：`fix/r3-chat-1119`，基线 **`ecb03d92`**（main）
- 来源报告：`meta-audit/reports/fullscan1119r3/Rikkaminis_Fullscan_Report_Round3.md`（§F-01 / §F-02 / §F-11 / §F-12）
- 判级表：`meta-audit/fullscan1119r3/TRIAGE-R3.md`（本支占 R×L 前 3 席）
- 改动面：**9 文件 +1221 / −22**（生产 3：`ChatSessionLifecycle.kt` +136/−8、`AgentLoopEngine.kt` +127/−3、`ChatPromptAndTools.kt` +102/−9；测试 5：4 个新类共 590 行 + `FakeAgentLoopHost.kt` +20）
- **禁碰零触碰**：`scripts/scan/**`、`scan.sh`、`test_scan.py`、`ChatViewModel.kt`、`backup/**`、`sandbox/**`、`browser/**`、`data/**`、`mcp/**`、`tools/**`

---

## 1 结论速览

| ID | 判级 | 报告的机制 | 本支先证 | 修法 | L |
|---|---|---|---|---|---|
| F-01 | P0 | 锚点在 tool_result 行 ⇒ 被判孤儿 ⇒ 自愈把 marker 写回第 1 轮 | ✅ 成立，逐行核实（含 `compactedCount` 语义） | 判据扩等价类：**锚点仍被会话持有**即有效，与消息类型无关 | X 不可逆（Room 里的 marker 被覆写） |
| F-02 | P0 | `onBlockUpdate` 把可变 `ArrayList` 跨线程交给 Main | ✅ 成立，且**比报告多一处**：写线程自己的 `indexOfFirst` 也是 CME 源 | 写线程内索引式有界复制 + 交快照 | Y 崩溃（主线程） |
| F-11 | P1 | 预算耗尽后循环不中断空转；正常收口被覆写成失败 | ✅ 成立，两半都在 | 落库后 `break@turnLoop` + 退出判据改「是否被打断」 | Z 降级（烧 token + 假失败横幅） |
| F-12 | P1 | 续写纯空白被折叠 ⇒ 空回合恢复 `removeAt(last)` 删掉前序正文 | ✅ 成立，逐帧核对了 4 步链 | 折叠判据改成「无可见内容」（与空回合检测同谓词） | X 不可逆（历史正文被删） |

**四条无一证伪。** F-02 的机制范围比报告写的更大：除了消费者侧 CME，写线程自己在 `toolBlocks.indexOfFirst { … }` 上也持有 Kotlin 迭代器（`ArrayList.Itr` 检 `modCount`），命令结束瞬间的并发结构性修改会在**发布之前**就抛。修法把这一处一并消掉。

---

## 2 环境与基线

- worktree `/var/minis/shared/work/r3full/chat`，分支 `fix/r3-chat-1119`，基线 `ecb03d92`
- 代码提交 `ec6aafcd`（生产 + 测试）
- 装置在 worktree 外：`shared/work/r3full/jvm/`（`extract.py` / `run.sh` / `mutate.py` / `closure.py` / `stub/`）
- 沙箱无 Android SDK，故本地只跑**纯逻辑**；引擎级行为由 CI 的 Gradle 套件覆盖（见 §5 与 §6-R1）

---

## 3 逐条（先证后修）

### F-01 [P0｜L=X] 压缩锚点被当孤儿自愈，marker 被永久写回第 1 轮 — 成立

**真码链路（逐环核实）**

1. `CompactOrchestration.kt:405` 写 v2 marker：`compactedCount = toCompact.size`，而 `toCompact = history.subList(effectiveStartIdx, anchorIdx + 1)`（`:205`）—— 第 2 轮起 `effectiveStartIdx = prevAnchor + 1`，所以 `compactedCount` 是**本轮增量**，不是累计。
2. `ChatSessionLifecycle.kt:1222`（原 `:1174`）`uiIdxForDbId(lcmId)` 只在 UI 列表里找；`ChatTranscriptRebuild.kt:243` 用 `userRowHasVisibleContent` 把纯 tool_result 行 `@mapNotNull null` 掉 ⇒ **合法锚点返回 -1**。
3. 随后进自愈：`healAnchorByCreatedAt` 取 `candidates[compactedCount - 1]`。30 条会话、第 2 轮增量 6 ⇒ 落到第 6 条（第 1 轮折叠区内），且该行**大概率是可见的** ⇒ `healUiIdx >= 0` ⇒ `heal != null`。
4. `rewriteMarkerForHeal` + `chatRepository.dao.updateCompactMarker(...)`（`:1301`）**写库**。下一次加载 marker 锚回第 6 条，`effectiveAgentHistory` 的 v2 切片把第 7..30 条原文再发一遍。

**关键的语义更正**：文件里原有的 SYMPTOM-A 注释断言「heal 候选必须过 UI 门 ⇒ 什么都不会写库」。那句话只对「锚点本身不可见」成立；本缺陷恰恰是**候选可见、锚点不可见**，所以它从来没保护到这条路径。

**修法（等价类，不是白名单）**

新增纯函数 `CompactAnchorValidity.isAnchorStillHeld(anchorId, historyDbIds)`：

- 锚点 **非空且仍在 `historyDbIds` 里** ⇒ 有效（`shouldSelfHeal == false`）——**任意消息类型**：assistant 文本、user 文本、tool_result 载体、图片行、system 行；
- 锚点为空 / 已从会话消失（删除、截断）⇒ 孤儿，**自愈照常**（这条被单测单钉，防「修成删功能」）；
- `historyDbIds` 为空（没有已加载历史可证）⇒ 保持旧行为（fail-open 到自愈，自愈自身还有 `compactedCount <= 0` 的拒绝）。

`historyDbIds` 源自 `loaded.llmHistory`（`loadSession` 里构建，`buildLlmMessages` 逐行建、不过滤），所以它就是「这个会话还持有哪些消息」的权威集合，也正是 `effectiveAgentHistory` 解析锚点用的那份历史。

v1 `compactBefore` 分支（`firstKeptId`）用**同一个谓词**，同族一并收口。`insertIdx` 保留 UI 索引语义（它只喂一行日志）：UI 里有气泡就取该位置，没有就 0（「divider 在顶、不灰」——本来就不再渲染）。

### F-02 [P0｜R=A 日常｜Y 崩溃] `onBlockUpdate` 并发改 `ArrayList` — 成立，范围比报告更大

**报告的一处低估**：报告只点了「消费者在 Main 上 `toList()` 撞上后台 `add/removeAt/clear`」。实测读码后，**写线程自己在发布之前就已经在遍历共享列表**：

```kotlin
val idx = toolBlocks.indexOfFirst { it.id == toolId }   // Kotlin/Java 迭代器，检 modCount
if (idx >= 0) {
    toolBlocks[idx] = toolBlocks[idx].copy(content = displayContent)
    viewModelScope.launch(Dispatchers.Main) { updateAssistantMessage(..., toolBlocks) }
}
```

`indexOfFirst` 用的是 `ArrayList.Itr`（`checkForComodification`），命令结束瞬间循环协程的结构性修改会让它在**发布之前**抛；即便侥幸躲过，`toolBlocks[idx] = …` 也可能落到已移位的槽位、把别的块改写掉。

**全部读写点普查（`grep -n toolBlocks|allToolBlocks`）**

| 位置 | 线程 | 性质 |
|---|---|---|
| `ChatPromptAndTools.kt:771` `onBlockUpdate` | shell executor 的 IO 线程 | 写：`indexOfFirst` + 元素替换 + 发布 |
| `ChatPromptAndTools.kt:876+` `updateAssistantMessage` | Main | 读：`toolBlocks.toList()` 等 |
| `AgentLoopEngine.kt` 逐轮流式（`:1488/:1608/:1654/:1676/:1743/:1773/:1877/:2253/:2466`） | 循环协程（非 Main） | 写：`add`/元素替换 |
| `AgentLoopEngine.kt:2644` length-wall 裁剪 | 循环协程 | 写：`removeAt` |
| `AgentLoopEngine.kt:4625` 队列打断 | 循环协程 | 写：`clear()` |
| `AgentLoopEngine.kt:4352` 顺序派发实参 | 循环协程 | 把**同一个实例**交给工具执行器 |
| 并行批量（`:4268`） | 已隔离 | `ParallelToolBatchBlocks.forkForCall` + `mergeBack`，**无需改** |

**修法**

1. `applyShellBlockContentUpdate(toolBlocks, toolId, displayContent)`：定位/替换全部改成索引式 + 越界捕获（`IndexOutOfBoundsException` ⇒ 返回 `null` = 这一帧不发布），不再对共享列表取迭代器。
2. `snapshotToolBlocksForHandoff(toolBlocks)`：**只读一次 size**，索引复制，越界即停（收缩 ⇒ 更短但一致的快照；增长 ⇒ 不纳入，未落库的块不该被当作已定性渲染）。
3. 发布改为 `updateAssistantMessage(..., snapshot)`。`null` 时不发布（与旧行为一致：块不在就什么都不做，精灵图不会莫名刷新）。

> 为什么不用 `toList()`：它内部仍可能读到撕裂的 size/array 组合（`Arrays.copyOf` 与并发 `add` 的 grow 交错），并且**不能**消除写线程那一侧的迭代器 CME。索引式 + 越界捕获是唯一两侧都不抛的形状。

### F-11 [P1｜Z 降级] 预算耗尽后空转 + 正常收口被覆写成失败 — 成立

**机制（两半都在）**

- `ChatPromptAndTools.kt:292`（tool_calls）与 `:660`（shell_commands）耗尽时：`t7BudgetStopReason = "…"` + `t7Reduce(ProcessInterrupted)…` + 返回失败结果 —— **但它们管不到循环**。`turnLoop@` 从未读这个字段，模型继续被调用（reducer 已 FINALIZING），直到撞 `maxTurns`。
- 退出尾部（原 `:4693`）用的是 `else if (traceObserver.t7BudgetStopReason != null)`：只要曾经置过就 finalize 成预算失败。模型随后正常答完（`loopExitedNormally = true`）也照样被打成失败横幅 + `AgentTerminal.FAILED`。

**修法（两个方向各一条判据，同一个策略对象）**

`BudgetStopExitPolicy`（`AgentLoopEngine.kt`）：

| 判据 | 含义 |
|---|---|
| `shouldStopLoop(reason)` | 有原因 ⇒ 不再请求模型 |
| `shouldFinalizeBudgetStop(reason, loopExitedNormally)` | **`reason != null && !loopExitedNormally`** —— 只有「确实被打断」才收口成预算失败 |
| `effectiveBudgetStopReason(...)` | 正常收口时返回 `null`，所以 trace 的 terminal/reason/errorLabel 也不会被染成 FAILED |

接线：Pass 3 落库 + tool_result 进历史 + `turnEnd` 之后，`shouldStopLoop` 成立就 `break@turnLoop`（放在队列打断之前——对着已耗尽的预算再开一轮正是那个空转）；退出尾部与 `t7EndRun` 都改走上面两条。

> 最大保留：**deadline / turn_limit / provider_attempt_limit 的既有 break 一字未动**（`AgentLoopBudgetStopExitTest` 的 4 臂结构断言与 `budget_stop_break_guard.py` 的判据继续成立：本支没有新增任何 `t7BudgetStopReason = "…"` 赋值点）。

### F-12 [P1｜L=X] 续写空白误删整条已输出历史 — 成立

**四步链（逐帧核对）**

1. 续写轮返回 `"\n"` ⇒ `turnText` 非空 ⇒ `assistantParts = [Text("\n")]`；
2. `ContinuationHistoryMerge.mergeIntoPreviousAssistant` 旧守卫 `content.isEmpty() && contentParts.isEmpty()` 放行 ⇒ 折进前一条（正文 + "\n"），并删掉中间的 continuation reminder；
3. `TurnVisibleContent.isEmptyTurn(turnText, …)` 按 `isNotBlank()` 判「空回合」⇒ true；
4. 空回合恢复 / 截断重试执行 `host.agentHistory.removeAt(host.agentHistory.size - 1)` —— 删掉的是**那条被折叠过的前序正文**。

**修法**：`hasContinuationContent(incoming)` = 非空白文本 **或** 有渲染块（`Text` 非空白 / `ToolUse`）。它是与 `TurnVisibleContent.hasVisibleContent` 同一形状的谓词（折叠与恢复从此对「空回合」定义一致）。判空对**本次增量**判，而不是对合并后的整条判。

`mergeIntoPreviousAssistant` 的另外两道守卫（前一条必须是未落库的 ASSISTANT、中间必须是 reminder）一字未动。

---

## 4 装置与变异臂

装置在 `shared/work/r3full/jvm/`（**worktree 之外**，不会被 `git add -A` 扫走）：

| 文件 | 作用 |
|---|---|
| `extract.py` | 从**真实源码**逐字切片被测纯函数（`CompactAnchorValidity` / `healAnchorByCreatedAt` / `BudgetStopExitPolicy` / `EofStubReminderStackGuard` + `ContinuationHistoryMerge` / `snapshotToolBlocksForHandoff` / `applyShellBlockContentUpdate`）。每段断言非空，anchor 缺失即失败——空切片不会被当成通过 |
| `stub/` | 沙箱没有 Android SDK：`android.util.Log` / `android.os.{SystemClock,Handler,Looper}` / `android.content.{Context,SharedPreferences}` / `AppLogger` / `R`（由 `genr.py` 从闭包源码自动收集 id）/ `ThinkingLevel` / `MessageEntity` / `AssistantBlock` / `LLMUsage` |
| `run.sh` | kotlinc（`-no-stdlib`，`nice -n 10`）+ JUnitCore，跑 4 个测试类共 30 例 |
| `mutate.py` | 7 臂变异：改**真源码** → 跑 harness → 解析 JUnit 的失败**类**集合 → 要求「失败的类集合 == 预期的类集合」→ `finally` 按内存快照还原并校验 sha256 |
| `closure.py` | 引擎级 harness 的依赖闭包试算（**未用于最终验证**，见 §6-R1） |

**本地 30 例全绿**（`OK (30 tests)`）。**变异 7/7 RED-PRECISE，每臂只翻自己那一类**：

| 臂 | 塞回的缺陷 | 变红的类 | 变红的用例（节选） |
|---|---|---|---|
| M1 | `isAnchorStillHeld` 恒 false（= F-01 原样） | `CompactAnchorValidityTest` | 「锚点在 tool_result 上仍被持有」「第 2 轮不覆写 marker」… |
| M2 | `shouldSelfHeal` 恒 false（= 把功能删了） | `CompactAnchorValidityTest` | 「真孤儿仍自愈」「空/空白锚点走回旧路径」 |
| M3 | 折叠守卫退回 `isEmpty && contentParts.isEmpty` | `ContinuationBlankFoldTest` | 「空白续写不折叠且正文存活」「恢复只删空白那条」 |
| M4 | `shouldStopLoop` 恒 false | `BudgetStopExitPolicyTest` | 「fired gate 必须让循环停」 |
| M5 | `shouldFinalizeBudgetStop` 退回 `reason != null` | `BudgetStopExitPolicyTest` | 「正常收口保持正常退出」 |
| M6 | 快照函数直接返回入参 | `ToolBlockHandoffSnapshotTest` | 「交出的是另一个实例」「快照不随后续修改变化」 |
| M7 | 刷新不写块内容 | `ToolBlockHandoffSnapshotTest` | 「只重写自己的块并交出整表」 |

M2 是「修成删功能」的反向门：它证明本修的判据没有把自愈一起关掉。

---

## 5 门与本地验证

按纪律**没有**跑全量 `scan.sh` / `test_scan.py` / gradle，也没有改任何门与既有断言。逐门读了可能被本支锚点影响的扫描器：

| 门 | 是否受影响 | 依据 |
|---|---|---|
| `loop_tool_status_transition_check.py` | 否 | 锚点 `Pass-1 循环头 / PASS2 条件 / Pass-3 标记 / CRITICAL 守卫 / 顺序派发调用` 全部原样；新增 break 位于 Pass-3 之后、队列打断之前，不落在被切分的五个区域内 |
| `loop_persist_slice_check.py` / `turn_tail_finalize_order.py` | 否 | 前者按回滚路径标记判读；后者要求「最终 finalize 之后紧跟 break、其间无 continue」——本支把新 break **放在**该 finalize 之前，`updateAssistantMessage(assistantId, accumulatedText,false,…)` 仍恰好 1 处 |
| `truncated_call_guard_check.py` | 否 | 只读 provider 解析块 |
| `budget_stop_break_guard.py` | 否 | 判据 = 「`t7BudgetStopReason = "…"` 赋值点之后的第一个 break 若嵌套 ≥2 层循环须带 `@turnLoop`」。本支**没有新增赋值点**（4 处原样），新增的 `break@turnLoop` 不在任何赋值点的块内 |
| `session_provider_enabled_check.py` / `deeplink_settings_routes_check.py` 等引用 `ChatSessionLifecycle.kt` 的门 | 否 | 只看各自函数体（provider 解析 / 路由表），本支改的是 `applyCompactMarkerGraying` |

`AgentLoopBudgetStopExitTest`（既有，源码形状）在 CI 上继续成立：4 处赋值点、2 处嵌套 → 均 `break@turnLoop`，两处 for-body 深度 → 裸 `break`，字数与结构未变。

---

## 6 残留（三件套：症状 / 为什么不在本支做 / 复活条件）

- **R1 引擎级 F-11 行为测试只在 CI 上跑**。`AgentLoopBudgetStopSpinTest` 真实驱动 `runAgentLoop`（经 `FakeAgentLoopHost`），但本地沙箱无 Android SDK：全量闭包 ~300 个文件、需要 Compose/AndroidX 桩，试算到 245 个文件仍不足，按负载纪律止损。本地已用**纯策略**覆盖两个方向（M4/M5 精确变红），引擎接线的证据落在 CI 的 Gradle 套件。**复活条件**：沙箱有 Android SDK 或 CI 出现该测试失败。
- **R2 F-02 的残余竞态是「元素替换 vs 循环协程的 add/removeAt」**。本支把两侧的抛异常路径消掉（索引 + 越界捕获 + 快照），但 `toolBlocks[idx] = …` 在极端交错下仍可能写到移位后的槽位（旧代码同样存在，且更容易先抛）。正解 = 给该列表一把锁或换 `CopyOnWriteArrayList`，需要动 `AgentLoopState.kt` 的字段类型（本支禁碰）。**复活条件**：真机日志出现工具块内容错位（非崩溃）。
- **R3 F-11 的「shell_command_limit 降级收口」**：报告提到「若允许 shell_command_limit 降级收口则不在该维度提前钉死」。本支按任务书取「预算耗尽 ⇒ 跳出循环」的强语义，两个维度一致。**复活条件**：用户反馈 shell 预算耗尽时希望模型再写一段总结（此时需要把 shell 维度的 gate 从「钉死」改成「拒一次、允许一轮文本收口」）。
- **R4 既有注释里一句已过期的事实**：`ChatPromptAndTools.executeShellCommand` 上方的 `[deep-audit-1016 5a]` 注释说「只返回错误的话……run 会被记成正常完成」。修完仍然成立（因为我们照样置 reason），未改。
- **R5 真机未验**：四条的证据都是源码 + JVM 纯逻辑 + 变异臂 + CI 编译级。F-01 需要一次「工具密集会话 + ≥2 轮压缩 + 重开」的真机复现；F-12 需要长回复截断续写的真机；F-02 需要在快速多行输出（编译/测试）时不再崩主线程。

---

## 7 CI

- 分支 push 不触发（`build-apk.yml` 只认 main），已手动 `gh-actions-dispatch --workflow build-apk.yml --ref fix/r3-chat-1119`
- **run `#3340` / `44470150464`** @ `ec6aafcd3b15ea1a3ae923c4fca830f685e637c0`（**三源逐字符一致**：本地 `git rev-parse HEAD` = 远端 `refs/heads/fix/r3-chat-1119` = run `head_sha`），结论 **success**
- 五个 job 全 success：`lint` / `unit-tests` / `build-apk` / `scan-gate` / `publish`（publish 的滚 tag + Publish to Releases 两步 **skipped** = main 门控，符合预期）
- `unit-tests`（job `135377363847`，日志 1,653,699 B）：**9,856 行 PASSED / 0 真 FAILED**（`grep FAILED` 无一行非 PASSED），`BUILD SUCCESSFUL`；五个新测试类逐条 PASSED —— `CompactAnchorValidityTest` 8/8、`ContinuationBlankFoldTest` 8/8、`BudgetStopExitPolicyTest` 4/4、`ToolBlockHandoffSnapshotTest` 10/10、`AgentLoopBudgetStopSpinTest` 2/2
- `scan-gate`（job `135377363952`）：`Summary: 115 passed, 0 failed`，并明确 `scripts/scan/** untouched in this push — skipping the fixture self-test (~121s); scan.sh ran all 115 gates over this tree` —— **本支未动门，绿不含夹具自测证据**，符合机制
- 判读口径：逐条 grep 新测试名 + `PASSED`（不进「含 FAILED 字样」的误判陷阱：本日志里连一行含 `FAILED` 的非 PASSED 行都没有）

---

## 8 处置

- **停止节点已到**：`#3340` / `44470150464` @ `ec6aafcd…` 三源一致 + 五 job 全绿 + 新测试 32 条逐条 PASSED。
- **不合 main**，合并起手权在用户。
- 报告提交后 push，`git diff --name-only <绿 sha>..HEAD -- . ':(exclude)fix-reports'` 必须为空。
- 本批未改任何 `scripts/scan/**`、未动门判据、未改既有测试断言（`AgentLoopContinuationChainTest` 的「empty restarted turn is NOT folded」用例在新守卫下**天然继续成立**——`assistant("")` 内容为空、无块）。
