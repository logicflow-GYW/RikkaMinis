# FIX-R4-P — B01-a 聊天核心（ChatViewModel 族）7 条

**支**：`fix/r4-b01-chatvm`（基线 `2103729f`，worktree `witness-1027/wt-r4-p`）
**范围**：`ui/chat/ChatViewModel.kt` · `ui/chat/ChatSessionLifecycle.kt` · 压缩 marker 数据层（读/删 API）· i18n 7 个 `values*` · 测试 2 文件
**未碰**：`AgentLoopEngine.kt` / `ChatPromptAndTools.kt` / `ChatScreen.kt` / `ChatRetryOps.kt`（Q 支）、`backup/**`、`scripts/scan/**`、`scan.sh`、`test_scan.py`

---

## ① 结论表

| # | 报告结论 | 本支核实 | 级 | 关键证据（文件:行） |
|---|---|---|---|---|
| F-02 | 清空会话后新会话首条消息仍被「Context Full」拦 | **成立** | P1 | `ChatViewModel.kt:2888 clearChat()` 全函数无 `_lastTurnContextTokens` 写；`ChatSessionLifecycle.kt:457` 只复位 `rescueAttemptedForCompact`；`ChatContextWindowExt.kt:107/181` 直接读该值判 EXHAUSTED |
| F-03 | 流式中点 Stop 把半截正文写回两遍 | **证伪**（1028 C2 已修） | 原 P0 → 证伪 | `ChatQueueInterruption.kt:704-733`：落库文本是 `stopPlan.persistText`（= `StopCleanupPlan.unpersistedTurnText` 的**本回合切片**），全函数不再读 `last.content`；`StopCleanupPlan.kt` 的 arm 选择已把「历史已以 assistant 结尾」做成幂等臂 |
| F-04 | 压缩后切片可出现「首条 ASSISTANT」「连续两条 USER」 | **成立（两条链都能构造）** | P1 | `ChatSessionLifecycle.kt:222` 先 peel 非 USER 头；边界守卫却把配对 `tool_use` assistant 拉进切片 ⇒ peel 掉它 ⇒ 孤儿 tool_result 被 `SanitizeAgentHistory.kt` 删除 ⇒ 头变 ASSISTANT。`ChatCompactionLogic.kt:510-521` else 分支把摘要作为独立 USER 追加在「tool_result-only USER」之后 ⇒ USER,USER |
| F-05 | Retry/Edit/Rerun 不清 `compact_markers` | **成立，且危害点与报告不同** | P1 | 三处截断：`ChatViewModel.kt:3455`(retry) / `:3270`(rerun) / `:3847`(edit→`ChatQueueInterruption.truncateBeforeEdit`)，均只 `deleteMessagesAfter`。**当前进程内 `effectiveAgentHistory` 因锚点失配安全降级（不注入）**；真危害是 `ChatSessionLifecycle.applyCompactMarkerGraying` 的 `createdAt` 自愈会**把孤儿标记重锚到幸存尾部并持久化**（`rewriteMarkerForHeal`），此后「已删内容的摘要」被永久注入 |
| F-07 | `resume()` 无条件把 `<system-reminder>` 当用户消息写库 | **成立（危害形态与报告不同）** | P1 | 原 `ChatViewModel.kt`（`resume()` 内，shell 之前）同步写 `agentHistory` + 异步落库。报告说「`_canResume` 永久 false / tool_use 悬空」不成立（`planStopCleanup` 的 canResume 臂会复位）；真实后果是 **Stop-during-resume 的重复写**：提醒先落库 ⇒ 清理时 `historyEndsWithAssistant=false` ⇒ 走落库臂 ⇒ 把已提交过的半截正文**再追加一遍**（并再写一条提醒），每次复利 |
| F-10 | `__new__<uuid>` 草稿会话永不显示 PAUSED 徽标 | **成立** | P1 | `ChatViewModel.kt:2637-2651`(原) `SessionBadgeStore.push(sessionId, …)`；`activeSessionId = realSessionId.ifEmpty { sessionId }` ⇒ 提升后 key 仍是草稿 id，而列表按真 id 读；`SessionBadgeReconcile` 只按 DB 尾形状推导，纯文本 Stop 的尾形状 `classify==NONE` ⇒ 无人补 |
| F-13 | 编辑态 + 流式中发送走排队分支提前 return | **部分成立**（组合状态可达；报告说的入口守卫不存在） | P1 | `editMessage()` **已有** `if (_isStreaming.value) return null`（报告称「没有守卫」= 证伪）；可达路径是「先进编辑态 → 排队 drain / `resume()` 在编辑期间拉起流」。`sendMessage` 抢占分支（原 `:3699`）确实既不截断也不消费 `_editingMessageId`（`=null` 在 `:3813` 之后） |

---

## ② 逐条修法

### F-02 上下文压力读数跨 transcript 存活（`ChatSessionLifecycle.kt` + `ChatViewModel.kt`）

新增 `ChatViewModel.resetContextPressureState()`（`ChatSessionLifecycle.kt`，紧邻 `effectiveAgentHistory`）：
`_lastTurnContextTokens.value = 0` + `contextGrowthTracker.reset()`（该 `reset()` 的 KDoc 写着「session switch / explicit reset」，全树 0 调用点）+ `lastAutoCompactAtMs = Long.MIN_VALUE` + `rescueAttemptedForCompact = false`。

调用点四处：
- `clearChat()`（清空后同一颗 `_lastTurnContextTokens` 会让新会话第一条消息重新撞 EXHAUSTED）；
- `loadSession()`（替换原来的 `rescueAttemptedForCompact = false` 单行——切会话时读数属于「刚离开的那个会话」）；
- `retryFromMessage()` / `rerunFromToolBlock()` / 编辑截断：回退后历史变短，旧读数是高估，会触发无意义的自动压缩。

**为什么不是报告说的「只把 token 置 0 并持久化」**：该值**不持久化**（`MutableStateFlow(0)`，无 prefs/DB 读写，`rg _lastTurnContextTokens` 只有 5 处内存访问），所以「重进会话」不需要迁移；而同族的 `contextGrowthTracker`（每轮增量估计）与 `lastAutoCompactAtMs`（最近一次自动压缩）同样是「上一份 transcript 的读数」，只清 token 会留下半个 bug。

### F-03 证伪（不改代码）

给出要求的调用图（`flushAllStreamingDeltas` / `flushStreamingDelta`）：

| 调用点 | 何时 |
|---|---|
| `ChatViewModel.kt:4207` | `cancelStream()` 开头（Stop 路径，F-03 的争议路径） |
| `ChatViewModel.kt:3850` | `sendMessage` 发新轮前扫孤儿 delta（仅 `_streamingById` 非空时） |
| `ChatRetryOps.kt:28` | `retryLast()` |
| `ChatModelRouting.kt:293` | `switchModelAndRerun()` |
| `ChatInlineErrorExt.kt:47` | 内联错误帧（保正文不倒退） |
| `ChatStreamDelta.kt:25` | `flushStreamingDelta(id)` 单条版本（模型路由/delta 收尾） |

关键判定：`flushAllStreamingDeltas()` 是**覆盖写**（`content = delta.content`，`delta` 是流式期间的权威累加器），不是 append；而 Stop 落库走 `planStopCleanup(...).persistText`，其输入是 `unpersistedTurnText(last.toolBlocks)` = **气泡最后一个 `tool_use` 之后的文本块**。`last.content` 在 1028 C2 之后**没有任何读者**（`ChatQueueInterruption.kt` 的 Case 2 注释逐字写着这条历史教训：「Collecting them re-committed every earlier round's text as a new assistant row on each multi-round Stop」）。因此「`<正文>\n<正文>`」在当前树上不可构造——`StopCleanupPlanTest` 三个 arm 已把「历史已以 assistant 结尾 ⇒ 不再落库」钉住。

### F-04 切片角色序列修复（`ChatSessionLifecycle.kt`）

新增纯函数 `normalizeSliceRoleAlternation(messages: MutableList<LLMMessage>)`：
1. 剥掉头部所有非 USER（`leading ASSISTANT`）；
2. 左到右合并同角色邻居（`consecutive USER` / `consecutive ASSISTANT`），合并 = `contentParts` 顺接 + `content` 以 `\n` 连接 + `reasoningContent` 取首个非空；content-only 的一侧先提升为 `Text` part（序列化读 `contentParts` 忽略 `content`，不提升就会静默吞字）。

三条返回路径统一收口：v2 尾路径（`sanitize → normalize`）、v1 `firstKeptMessageId` 路径、v1 `lastCompactedMessageId` 路径。**顺序在 sanitize 之后**是关键——报告点出的「首条 ASSISTANT」正是 sanitize 删掉孤儿 tool_result 之后才出现的。

**为什么合并而不是丢弃/插桥**：合并后的形状正是严格网关接受的形状（Anthropic 允许一条 user 消息含多个 text/tool_result part，且要求 `tool_result` 在头部——追加天然满足）；丢一条会丢内容，插一条假 assistant 会污染模型上下文。

### F-05 回退后修剪压缩标记（`ChatSessionLifecycle.kt` + `ChatViewModel.kt`）

新增两个纯/挂起函数：
- `compactMarkerIsOrphaned(anchorIds, liveDbIds)`：**锚点为空时返回 false（拒绝判断）**——「我不知道它锚在哪」不能读成「它已孤儿」；
- `ChatViewModel.dropCompactMarkerIfRewound(sid)`：锚点候选 = `lastCompactedMessageId` / `firstKeptMessageId` / `boundaryMessageId`；活集合 = `agentHistory` 的 `dbMessageId`；仅当**所有**锚点都不在活历史里才 `deleteCompactMarkers(sid)` + 清 `_cachedLatestMarker` / `_compactSummary`，并打一条 warning 日志（含 anchors/live 计数，便于真机复盘）。

三处调用：retry 重建后、rerun 重建后、`sendMessage` 的编辑截断后。选在这个时点而不是 `loadSession`，因为**危害是「自愈把标记写实」**：在加载之前删掉，自愈就无标记可救。

### F-07 续写提醒移入声明窗口（`ChatViewModel.kt`）

`resume()` 里 shell 之前的提醒写块整段删除，改到 `sendShellJob` 内、**声明撤销检查（`!isActive || !_isStreaming || epoch/token 不符 ⇒ return@launch`）之后**，并且：
- 用 `runCatching { chatRepository.appendMessage(...) }.getOrNull()` 接住 `MessageEntity`，把 `dbMessageId` 写进 `agentHistory` 的新条目（压缩边界按 id 解析，不接住就是一条「看不见」的真实行）；
- 落库失败只告警不阻断（提醒是辅助信息，不该让续写失败）——但还是**在流开始前**完成，保证下一轮请求能看到它。

效果：Stop-during-resume 时提醒不再落地，清理看到真实尾部（assistant）⇒ `planStopCleanup` 走「保留 + 可续写」臂，既不重复追加半截正文也不会把 Resume 按钮吃掉。

### F-10 徽标 key 改 `activeSessionId`（`ChatViewModel.kt`）

`canResume.collect` 内改用 `activeSessionId` 作 key；`remove` 时若 `sessionId != activeSessionId` 把**提升前的草稿 key 一并清掉**（否则那条陈旧 PAUSED 会永久留在持久化表里）。
前缀契约未动（`__new__` 仍是草稿判据，`ensureSession()` 提升逻辑一行未改），只改徽标的读/写 key。

### F-13 编辑态拒绝排队（`ChatViewModel.kt` + i18n）

在抢占分支最前面加编辑态判定：
```kotlin
if (_editingMessageId.value != null) {
    appendSystemInfo(context.getString(R.string.sysmsg_edit_wait_stream), iconKind = "info")
    setInputText(text)   // ChatInputArea 调用前已清空输入框，这里回填
    return
}
```
不排队、不截断、`_editingMessageId` 保持不变（用户点 × 或停止后再发即走正常编辑路径）。新增 `sysmsg_edit_wait_stream`，7 个 `values*` 目录齐全（`i18n_check.py` 绿）。

**为什么不是报告说的「先 cancelStream 再继续走编辑路径」**：`cancelStream()` 是**异步**的（它 cancel `streamJob`/`sendShellJob`/`compactJob`，清理协程另起），紧接着在同一帧继续 `truncateBeforeEdit` + 落库 + 起新流，会与旧流的 delta 落库/清理并发写同一会话（前批同类竞态的教训）。按任务书「编辑态下不允许排队」修，是最小且无竞态的形状。

---

## ③ 装置与读数

**逐字切片探针** `/var/minis/workspace/probe-r4p/`（`extract.py` 从真文件按标记切出 `normalizeSliceRoleAlternation` / `slicePartsOf` / `mergeSameRoleNeighbors` / `compactMarkerIsOrphaned`，块 1999 B，`sha256[:16]=60a1ad693e6c6c20`；`LLMMessage.kt` / `AgentContentPart.kt` 直接拷真文件；`LLMUsage` 用**声明过的空 stub**，因其真身带 `@Serializable` 需编译器插件）。

| 臂 | 结果 |
|---|---|
| 主探针（19 例：合法 3 / 剥离 2 / 剥离后合并 2 / 连续 USER 2 / content-only 3 / 连续 ASSISTANT 2 / 退化 1 / 标记决策 5） | **ALL CASES PASS** |
| A1 去掉头部剥离 | MATCH（`leading ASSISTANT peeled`、`peel then merge` 红，共 6 红） |
| A2 关掉同角色合并 | MATCH（3 条期望红，共 10 红） |
| A3 改成「任一锚点失配即删」 | MATCH（`one of several anchors alive` 红） |
| A4 去掉 `isNotEmpty()` 空锚点守卫 | MATCH（`refuse to judge` 红） |

**仓库契约测试** `src/android/app/src/test/.../ui/chat/CompactSliceRepairTest.kt`（8 例，同表）：本地 `kotlinc` + JUnit4 实跑 **OK (8 tests)**（同一切片 + 真模型文件，无逻辑副本）。

**F-03 的旁证**：读 `ChatQueueInterruption.kt:600-769` 全文 + `StopCleanupPlan.kt` 全文，确认落库只用 `persistText`。

---

## ④ 门 / 夹具读数

- 改动相关门（`rg -l "ChatViewModel|ChatSessionLifecycle|compact_markers|CompactMarker" scripts/scan/*.py`，24 道）：**24/24 rc=0**（`claim_release_guard` / `compact_decision_gate_check` / `compact_pin_truth_check` / `discarded_return_guard` / `stale_closure_guard` / `chat_session_id_binding_check` / `chat_ui_interaction_check` / `parts_json_funnel_check` / `four_way_sync_check` …）。
- i18n 相关门：`i18n_check.py` **rc=0**（7 个 `values*` 同步；`xml.dom.minidom` 全部解析通过）。
- Kotlin 语法自查：`kotlinc ChatViewModel.kt ChatSessionLifecycle.kt` 过滤 `expecting|unexpected token|Unclosed|syntax error` = **0 命中**（其余报错均为「未挂 Android 类路径」的 unresolved reference，预期）。
- **未跑**全量 `scan.sh` / `test_scan.py`（COMMON §0 硬约束）。

---

## ⑤ backlog 查重复核

**无法机械复核**：`/var/minis/shared/work` 在本次施工期间被清空（见 §3 事故注），`shared/backlog.md` 已随事故丢失，`scan_debt.py notes` 无从运行。按上下文记忆中的既有登记复核：
- F-03 类「Stop 重复落库」→ **已由 1028 C2 修并登记**（本支核实后未重开）；
- F-02 类「清空后状态残留」→ 记忆里 1028 的 clearChat 批次处理过 `_compactSummary` / `_cachedLatestMarker` / `_preEditAttachments`，**不含压力读数**，故本支不是重复登记；
- F-10 的 `__new__` 前缀契约（不可动）与本支改动方向一致：只改徽标 key。

---

## ⑥ 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | F-07 修复**不清理**事故前已产生的重复半截行 | 用户在修复版本之前多次 Stop-during-resume，历史里已有两条同文本 assistant | 一次性去重迁移（同 session 相邻两行 text 完全相同且时间差 < 5s 则删后者） | 无法与「用户真的重复提问」区分，误删是数据损坏；且新版本不再产生新样本 |
| R2 | F-13 回填走 `setInputText`，若处于输入框 clear 后的 late-commit 保护窗（150 ms）可能被 UI 侧丢弃 | 回填发生在 clear 后极短时间内（当前调用链是同一帧内的紧邻调用，正常不会触发） | 改走 `requestComposerCommand(ComposerCommand.ReplaceText)` 通道（需 ChatScreen/InputArea 侧消费，属 Q 支文件） | 跨支文件；且当前调用顺序下输入框 `LaunchedEffect(inputText)` 镜像必然执行 |
| R3 | `effectiveAgentHistory` 在锚点失配时仍降级为**全量历史**（不注入摘要） | 标记锚点丢失但未走本支三处回退路径（如外部 DB 变更 / 云端合并） | 在 `applyCompactMarkerGraying` 前做一次「标记可达性」校验并删标记 | 该降级是**安全方向**（多给信息而非少给），且新增校验要在加载主路径加一次扫描 |
| R4 | F-10 只在 `canResume` 变化时刷新徽标；修复前写入的草稿 key 陈旧项要等一次状态翻转才被清 | 用户在修复版本前留下 `__new__*` 徽标 | 冷启动时把非会话 id 的徽标键一次性剪除（`SessionBadgeStore` 侧） | 需要 store 侧知道「会话集合」，属另一文件域；陈旧项只影响一个不可点击的 key |
| R5 | `resetContextPressureState()` 在 `loadSession` 被调时若恰好有流在跑，会把当前轮的压力读数清零一次 | 流式期间重入 loadSession（`revertCompact` 后的 reload 等） | 加 `if (_isStreaming.value) return`（只清 token/growth） | 清零方向是**低估**（多给一次机会，不会拒发），加守卫反而让 `revertCompact` 后保留错读数 |
| R6 | 真机未验（见 §7） | — | — | — |

---

## ⑦ 真机未验清单

1. F-02：真机上「Context Full → Clear Chat → 立刻发消息」不再二次弹窗（需实际触发 EXHAUSTED 阈值）。
2. F-04：真实 Anthropic / 严格 OpenAI 网关在压缩后不再 400（需真 key + 触发一次压缩）。
3. F-05：Retry 编辑旧消息后重进会话，历史不再出现「已删内容的摘要」分界。
4. F-07：流式期间连点 Resume→Stop 多次，历史无重复半截行、Resume 按钮仍在。
5. F-10：新建对话 → 发一条 → Stop → 返回列表，该会话显示 PAUSED。
6. F-13：编辑态下（让排队 drain 拉起流）点发送，出现提示且输入框回填、横幅仍在。

---

## ⑧ 与报告的差异（含证伪记录）

1. **F-03 整体证伪**：报告描述的 `handleUserCancelledCleanup` 形状（`appendMessage(last.content)` 后再 append 每个 text block）是 **1028 C2 之前**的代码；当前落库只看 `planStopCleanup(...).persistText`（本回合切片），且「历史已以 assistant 结尾」是幂等臂。报告行号（`:4166`）在当前树落在 `handleUserCancelledCleanup()` 的**调用**处而非实现处。
2. **F-07 机制改写**：报告的「`_canResume` 永久 false / tool_use 悬空」不成立（`planStopCleanup` 的 canResume 臂会复位、sanitize 会补占位 tool_result）；真实危害是**重复落库复利**（提醒先落地 ⇒ 清理走落库臂 ⇒ 半截正文再追加一次）。修法方向（「移进声明窗口 + 接住返回 id」）与报告一致，理由不同。
3. **F-13 入口守卫证伪**：`editMessage()` 已有 `if (_isStreaming.value) return null`（`ChatViewModel.kt:3524` 起），报告称「没有守卫」是错的；可达路径是 drain/resume 在编辑期间拉起流。修法按任务书的「不允许排队」，而非报告的「cancel 后继续编辑路径」（`cancelStream()` 异步，同帧继续会与旧流并发写）。
4. **F-05 危害点改写**：报告说「已删对话的未来摘要继续注入」。在当前进程内，锚点失配是**安全降级（不注入）**；真正的持续危害来自 `loadSession` 的 `createdAt` 自愈把孤儿标记**重锚并持久化**。因此修法放在回退处（删标记）而不是注入处（改判定）。
5. **F-10 影响窗口收窄**：不是「永久无徽标」——用户重新从列表进入该会话时会新建 VM，`loadSession` 的 DB 尾检测会重算 canResume，徽标可恢复；缺陷窗口 = 当前进程 + 纯文本 Stop 的会话（DB 尾形状 `classify==NONE`，`SessionBadgeReconcile` 补不到）。
6. 行号漂移普遍（报告 `:2888/:4166/:3447/:4229/:2640/:3678` 与当前树对应位置差 0-30 行），已按语义定位。

---

## 附：施工期事故（影响但未阻断）

`/var/minis/shared/work` 在本次施工期间被清空两次（08:09 / 08:14，形态是「先出现 corrupt jarfile，随后整个目录不可见」，非 `rm` 形态；同期 app `silent_kill` 抖动 07:05/07:08/07:32/07:41）。第一棵 worktree 与两处未提交编辑随之丢失，已按主会话重建的 worktree 重做，并在每笔提交后立即 push（远端有 `ab710c6b` / `4a676800` / 395ab4b1 三笔）。探针与报告副本落在 `/var/minis/workspace/`（会话私有）与 `/var/minis/mounts/背景包/_shared/r4-rescue/`。
