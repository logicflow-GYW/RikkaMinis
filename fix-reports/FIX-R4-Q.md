# FIX-R4-Q — B01 chat-core 七条（AgentLoopEngine / ChatPromptAndTools / ChatScreen）

- **分支**：`fix/r4-b01-loop`（基线 main `2103729f`）
- **来源**：`meta-audit/reports/r4/B01-chat-core-2026-10-29.md` 的 F-01/F-06/F-08/F-09/F-11/F-12/F-14
- **判定汇总**：修 5 条（F-01、F-06、F-08、F-11 后半、F-12、F-14 = 6 个提交面），证伪/登记 2 条（F-09 两半、F-11 前半——后者修点在禁区）
- **硬约束遵守**：`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰；只跑相关门（§5）

---

## §1 逐条判定与修法

### F-01（high，P1）EOF 断流续写把半截答案落成两条 assistant 行 —— 成立，已修

**先证**：结构核验成立，但报告的「重进会话气泡分裂」这一半已被既有代码吸收——`ChatTranscriptRebuild.kt:235` 起把**连续 assistant 行合并显示**（`content = prev + "\n\n" + msg`），所以重载后看到的是一个中间带空行的气泡，不是两个。真正残留的危害是：
1. `retryLast`（ChatRetryOps）只回滚**最后一条** assistant 行——重试后用户得到「前半段 + 重试后的完整答案」拼在一起的混乱内容；
2. 两行让 `dbMessageId` 只回填到最后一行，前半段永远是悬空锚点。

**根因**：no-tool 回合尾的 `persistAssistantTurn`/游标推进/横幅冲写位于 EmptyTurnRecovery 与 EOF 续写 `continue` **之前**。续写分支继续往同一 `loopState.assistantId` 气泡流式写入，于是首段单独成行、终段再落一行。工具路径（`:3376` 早落盘 + `:3995` 终落盘）本来就把整条续写链写进**一行**（`unpersistedBlockStart` 切片的既定语义，`loop_persist_slice_check` R1-R10 的 KDoc 写明「五个 no-tool 续写分支不落盘地 continue」）——no-tool 尾是唯一违背该设计的位置。

**修法**（commit `0b9cc5ee`）：把落盘块（`turnParts` 构建 → `pendingInlineError` 消费 → `persistAssistantTurn` → 游标推进 → dbId 回填 → 横幅冲写）整体挪到全部 continue 之后、`turnEnd` 之前。门 `turn_tail_finalize_order`（finalize 后不得有 continue）与 `loop_persist_slice_check`（推进必须在可达成功守卫内）保持绿。

**连带收口（§116 已登记残留）**：落盘下移后，空回复提示与 EOF 放弃横幅改为 `pendingInlineError` 延迟冲写（与 length-wall 分支既有写法同款）——横幅落在本次真正落的那一行上，`deep-audit-1015 BUG-01b`「setInlineError 无法 defer（冲写点在 continue 之前）」的堵点不复存在。

### F-08（medium）续写段在内存历史里裂成多条 assistant + 残留续写提醒 —— 成立，已修

**先证**：error-shaped（`:2320` 区）、length-wall（`:2840` 区）、EOF stub（`:3150` 区）三条续写路径每轮各自 `host.agentHistory.add(ASSISTANT)`，EOF/wall 两族还会插入 `<system-reminder>` USER 回合。三段续写 = 三条 assistant（只有最后一条拿到 dbMessageId）+ 两条永久残留提醒；而 DB 只有终盘那一行。**与 F-01 必须一致修**（任务书明示），否则 DB 单行、内存多条，下次请求的转录与落库形态劈叉。

**修法**（同 commit）：新增纯函数 `ContinuationHistoryMerge.mergeIntoPreviousAssistant(history, incoming)`（AgentLoopEngine.kt 内，无 host 依赖）：
- 从尾部剥「续写提醒」（EOF stub `EofStubReminderStackGuard.REMINDER_MARKER` / length wall `LENGTH_WALL_REMINDER_MARKER`，单 Text part USER）；
- 剥完后若尾部是 `ASSISTANT` 且 `dbMessageId == null` → 折叠（content 相接、相邻 Text part 合并、reasoning 合并）并删提醒；否则不折叠。

防误折叠护栏（表驱动 7 例覆盖）：空 incoming 不折叠（`EmptyTurnRecovery.plan` 读 `history[size-2]`，折叠空回合会让它 nudge 到错误位置）；tool_result 尾/非 assistant 尾不折叠；已落库（dbMessageId≠null）不折叠。接线在 assistant 追加点一处（`:2528` 原 add），普通回合/独立 run 不受影响（`ordinaryTurns_areNotFoldedTogether` 回归例）。

### F-06（high）fallback 切换发布原始 entry 模型，压缩窗口静默失真 —— 成立，已修

**先证**：`buildFallbackProviders`（ChatModelRouting `:467`）用 `providerModelFor(entry)`（= `ModelsDevApi.effectiveModel` 富化）构造 `FallbackCandidate.provider`；两处 fallback（error 路径 `:2118`、content-filter 路径 `:2550`）却写 `host.updateCurrentModel(newEntry.model)`——`ModelEntry.model` 只带用户存储的字段。`updateCurrentModel` 原样赋值（ChatViewModel `:1563`），于是 fallback 后 `currentModel.contextWindow == null`，`CompactOrchestration.kt:681` 的窗口预算拿到 0/空天花板。

**修法**（commit `3388ac44`）：两处改写 `next.provider.model`（同一对象、已是富化模型）。零新符号——`models_provider_field_check` 钉着 `ModelsCollection` 内 `ModelsDevApi.effectiveModel(` 恰 1 次，加新调用点会翻红；不动该门。

### F-09（P2）附件清单失步 —— 两半各判：①不成立（设计如此）②成立但修点在禁区，登记

**①ImageBudget 丢弃后 metas 仍在清单**：机制成立（`:1848` 裁 `imageParts` 尾部、`:1850` 同步裁 `imageUris/imageNames/imageMediaRefPartsJson`，`metas` 不裁）→ XML 列出被丢弃的图。**但危害链不成立**：`<user-attached-files>` 的 KDoc 契约就是「uploads 目录清单」（凡成功落到 uploads 的附件都列一笔回目，模型可 `read_image`/`cat` 取回）——被预算丢弃的图**确实在** uploads；DB/UI/内存三层一致（三处同裁）；模型可自行取回内容。判定：这不是失步，是清单语义本来就不是「内联载荷清单」。不改。
**②非图片附件 `ref == null`**：`saveMediaStreamed` 失败时文件照落 uploads + 照进 `metas`（`:1840` `metas.add` 在 `if (ref != null)` 块外），但 `nonImageNames` 不加、无 mediaRef → 用户行只剩 XML 文本 → `userRowHasVisibleContent == false`（`ChatTranscriptRebuild:160` 气泡不渲染）→ 重载时行被 `ChatTranscriptRebuild:224` **整行丢弃**（消息蒸发）。成立，但修点 = 可见性判定层（ChatTranscriptRebuild / ChatViewModel 可见性谓词），均不在本支独占集；本文件内可做的两个改法（删 uploads 文件 / 往消息里塞可见注记）一个丢能力一个改产品语义 → 登记（§6 R-F09）。

### F-11（P1→按实判 medium+残留）手动重新生成标题 —— 前半（守卫 bug）修点在禁区，登记；后半（首败静默）已修

**前半**：`ChatSessionEntryExt.kt:53` `if (isDraft || realSessionId.isEmpty()) return`——本进程新建会话晋升后 `realSessionId` 非空但 `isDraft`（`sessionId.startsWith("__new__")`）**永真**（`realSessionId` 是独立 var，晋升不改写 `sessionId`），于是「重新生成标题」永久 no-op。修法是一行（删 `isDraft ||`），但该文件不在本支独占集，且 `SessionEditSheetBindingTest.kt:79` 钉死 `viewModel.regenerateSessionTitle()` 在 ChatScreen **恰 2 处调用点**（单一来源契约：第三处=第二套触发方式）——加第二入口/改名都翻红。→ 登记（§6 R-F11），给出精确一行修法。
**后半**（本支修，commit `7ff3440f`）：`regenerateSessionTitle` 清零 attempts 后调 `generateSessionTitleIfNeeded(force=true)`，但五个失败分支的回退门都是 `titleGenerationAttempts >= TITLE_MAX_ATTEMPTS`——手动首败 attempts=1<3，回退不触发、无 loading 反馈（这个函数的 KDoc 自己写着天花板就是「点了没反应感知」）。修法 = 失败终点谓词 `titleFailureIsFinal()`：达上限（自动路径语义不变）**或** `force && sessionTitleNeedsGenerationOf(_sessionTitle.value)`。manual 对已有真标题的会话不回退（needs-generation=false），防止好标题被首消息截断覆盖。

### F-12（low）FAB-UP / FOCUS-MESSAGE 不标记 userScrolledUp —— 成立，已修

`ChatFollowController.shouldCatchUpAfterOpen`（`:321`）`!armed || userEngaged → false`，但两处向上滚离底部的入口没设 `openCatchUpUserEngaged = true`：开场 2.5s 窗口内（搜索结果点进同一会话触发的 FOCUS-MESSAGE 跳转）收集器把视口拉回底部，跳转作废；FAB-UP 点击同理看似无效。修法 = 两处在发起滚动前标记（与既有 `InputHistorySheet` 跳转 `:4759` 同款）。`OpenCatchUpGuardTest` 增接线形状断言（两处滚动调用前 1200 字符内必须有标记）。

### F-14（medium）spawn_agent 绕过运行预算与技能计数 —— 成立，已修

主循环每次 provider 请求前查 deadline（`AgentLoopEngine:1201`）并 `t7ConsumeAndTrace(DIMENSION_PROVIDER_ATTEMPTS)` 消耗（`:1226`）；`executeSpawnAgentTool` 的 `streamProvider` 直连 `ProviderExecutionGateway.stream`，两条都绕过 → 失控子代理烧掉宿主预算从不计数的请求。修法（commit `f6919a4d`）：派发前同两条门，拒绝抛 `SubagentBudgetExhaustedException`（`runSubagentLoop` 的 catch 转成失败结果，派发方看到「预算拒绝」而非「provider 挂了」）。技能计数同族：派发本身 `recordSkillUse(skill.id)`（主路径只在读 SKILL.md 时记 → 派发 50 次/天的技能按「读过一次」排序）；`executeSubagentTool` 的 FileReadTool 臂补主臂同款计数。`SubagentBudgetGateTest` 钉「门在 gateway 调用之前」+ 单臂数（防第二份拷贝双计）。

## §2 与报告不一致的读数（报告不是工单）

| 点 | 报告说法 | 真码 |
|---|---|---|
| F-01 危害 | 「重进会话气泡分裂」 | 重建层已合并连续 assistant 行（`ChatTranscriptRebuild:235`）→ 是带空行的单气泡 + retryLast 只回滚尾行 |
| F-09① | 「模型以为所有图都在并去读取它们」= 失步 | XML 清单的文档化契约就是 uploads 目录清单；被丢图确实在盘上，模型可取回 → 刻意行为 |
| F-11 | 「入口 ChatScreen:2084」 | 入口守卫在 ChatSessionEntryExt.kt:53（`isDraft \|\|` 半边）；ChatScreen 只是调用点 |

## §3 证据读数

- **本地相关门（10 道，全绿）**：`turn_tail_finalize_order` / `loop_persist_slice_check` / `chat_session_id_binding_check` / `loop_tool_status_transition_check` / `compact_pin_truth_check` / `truncated_call_guard_check` / `budget_stop_break_guard` / `loop_counter_reset_guard` / `models_provider_field_check`（6 arms） / `router_lock_check` / `subagent_handback_check` / `trace_step_tag_check` / `prefs_listener_holder_guard` / `skill_frontmatter_roundtrip_check`（14 道含重复计数，0 红）。
- **语法自查**：AgentLoopEngine.kt / ChatPromptAndTools.kt / OpenCatchUpGuardTest.kt / SubagentBudgetGateTest.kt / AgentLoopContinuationChainTest.kt 过 kotlinc 语法类错误过滤（`expecting|unexpected|syntax|unclosed` = 0）。
- **JVM 探针未成**：Sandbox S28（子进程 `Function not implemented`，与本日共享目录清空事故叠加）+ /tmp 收割 + jvm-libs 丢失，三重环境事件后放弃本地全环路灯，改用「语法自查 + CI 真编译真运行 + 表驱动/形状测试进 CI」。**诚实标注：AgentLoopContinuationChainTest 的 4 个环路灯行为例 + 折叠判定表 7 例在提交时本地未运行过，绿红以 CI 为准。**
- **CI**：#2877（`0b9cc5ee`）failure @ `compileReleaseUnitTestKotlin`（我的新测试少传 `LLMMessage.content` 必填参数，唯一失败步骤）→ 修后重派。#2878（`<见 git log>`）结论见 CI 判读（下节）。

## §4 CI 判读（#2878 起）

（run id / head_sha 逐字符一致 / 27 步非 success 计数 / `Summary: 113 passed, 0 failed` ×1 / `ALL 349 CASES PASS` ×1 / `^ e: ` 0 / FAILED 行全为 PASSED 子串 —— 见会话收口读数）

## §5 纪律事故自报

本支在跑门循环时把 `scripts/scan/test_scan.py` 误纳入循环（名字含 "subagent" 命中）——**违反禁跑全量约束**；180s stall-guard 击杀，无完成读数、无写入。发现后立即改为逐门点名执行。

## §6 残留（全部登记级，带触发条件）

| # | 内容 | 修点 | 触发条件 / 方案 |
|---|---|---|---|
| R-F11 | `regenerateSessionTitle` 守卫 `isDraft \|\| realSessionId.isEmpty()` 的 `isDraft \|\|` 半边使本进程晋升会话永久 no-op | `ChatSessionEntryExt.kt:53`（不在本支独占集；`SessionEditSheetBindingTest` 钉单一来源恰 2 调用点） | 下次某支独占集含 ui/chat 附加文件时：删 `isDraft \|\|` + 在该测试加晋升态断言；或由 P 支（ChatViewModel 家族）捎带 |
| R-F09 | 非图片附件 store 失败（ref==null）→ 行只剩 XML 文本 → 气泡不渲染 + 重载整行蒸发 | `ChatTranscriptRebuild` 可见性判定 / ChatViewModel 发送装配（均不在本支独占集） | 下次动 ChatTranscriptRebuild 的支：`userRowHasVisibleContent`/重建谓词把「非空 uploads 清单」计为可见内容，或发送装配在 ref==null 时给可见回退注记（产品决策需用户拍板） |
| R-F01a | 续写链在 DB 已单行，但 `ChatTranscriptRebuild` 的连续 assistant 合并仍在（服务旧数据） | 同上 | 旧库行自然耗尽后可评估删除；现不动（兼容兜底 P3） |
| R-F08a | `ContinuationHistoryMerge` 折叠后，历史里同一 assistant 的 reasoningContent 跨段直连拼接（无分隔） | AgentLoopEngine.kt | 若真机观察到跨段 reasoning 阅读问题再加 `\n\n`；纯展示面，无数据危害 |
| R-F06a | `models_provider_field_check` 未覆盖「fallback 写点必须用富化模型」 | `scripts/scan/models_provider_field_check.py`（禁改） | 建议判据：AgentLoopEngine 内 `updateCurrentModel\(` 的每个实参必须是 `next.provider.model` 形（禁止 `newEntry.model`）——留给下次门扩容批次 |
| R-F14a | 预算门只覆盖 spawn_agent 的 streamProvider；`runSubagentLoop` 内部每轮的 DIMENSION_TURNS 未计 | ChatToolExecutors.kt（不在本支独占集） | 若观察到子代理多轮烧 turn 预算再扩；当前 PROVIDER_ATTEMPTS + deadline 已挡主害 |
| R-ENV | 本支经历两次环境事件：08:0x `/var/minis/shared` 整体清空（worktree/git 对象/装置全失，已从 GitHub 重克隆恢复）；恢复后 sandbox S28 子进程缺陷反复，本地 JVM 探针放弃 | — | 环境事件已入 memory（2026-10-29 日报）；S28 按既有注册表处置 |

## §7 改动面

`AgentLoopEngine.kt`（F-01 搬移 + F-08 折叠器 + 接线）、`ChatPromptAndTools.kt`（F-11 后半谓词 + F-14 三处）、`ChatScreen.kt`（F-12 两处标记）；测试新增 `AgentLoopContinuationChainTest`（10 例）、`SubagentBudgetGateTest`（2 例）、`OpenCatchUpGuardTest` +1 例；`FakeAgentLoopHost` +5 行（currentModels 记录）。生产 3 文件，全部在任务书独占集内。
