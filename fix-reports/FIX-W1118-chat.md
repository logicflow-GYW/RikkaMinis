# FIX-W1118-chat — 聊天域六条 P1：跨所有权交接处的用户内容丢失

- **分支**：`fix/w1118-chat`（worktree `/var/minis/shared/work/incr1118/wt1`）
- **基线**：`c85131ad`（= 当前 main，已含 FIX-1117B 五支）
- **代码 tip**：`8704c599`（`fix(chat): route the moved paste blocks through the composer's intent channel`）
  —— 代码分三次提交：`1274de37`（六条修复）+ `5b471b4e`（CI #3217 门禁反馈，§2.7）+
  `8704c599`（CI #3225 三条既有形状断言的反馈，§2.8）。后两次都**不改意图，只改形态**到了门/既有断言要求的样子
- **来源**：`shared/work/meta-audit/reports/wave1118/` 的 `Wave_1118_D1_Report.md`（F-01…F-08）、
  `Wave_1118_D2_Report.md`（F-01…F-06）、`Wave_1118_D10_Report.md` **仅 F-05**；
  纪律见 `shared/work/incr1118/fix/POLICY.md`
- **改动面**：6 个生产文件（+271/−16，三次提交合计）+ 4 个新测试类（29 用例）+ 1 处既有测试的
  拼写 pin 改为决策 pin ——
  `ChatViewModel.kt` / `ChatScreen.kt` / `ChatInputArea.kt` / `ChatQueueInterruption.kt` /
  `ChatViewModelStore.kt` / `MoveToCapsuleGate.kt`（仅 KDoc）
- **未跑**（按任务书硬约束）：全量 `scan.sh` / `test_scan.py` / gradle。编译证据交分支 CI。

---

## 1. 判级表（本支全部条目）

| ID | 报告 severity | 我方判级 | 结论 | 去向 |
|---|---|---|---|---|
| D1-F01 `t7BudgetStopReason` 跨 run 残留 | high | **证伪**（残留 P2） | `t7EndRun` 是三条退出路径的唯一收尾且最后一句就是 `t7BudgetStopReason = null` | 不修，登记 §4-a |
| D1-F02 退出编辑销毁草稿粘贴芯片 | high | **P1 修** | 顺序反转 + 芯片按「literal 还在不在」定形态 | §2.1 |
| D1-F03 重入 `editMessage` 把草稿芯片写回 | high | **已修复（1117B）** | 树里已是 `editPastesOnEnter(...)`（`ChatViewModel.kt:3854`），KDoc 与实现一致 | 不重报、不重修 |
| D1-F04 拒绝分支丢输入（压缩中 / 无 provider） | high | **P1 修** | 两分支补 `setInputText(text)`；分享武装移到 provider 门之后 | §2.2 |
| D1-F05 Retry/Rerun 跳底不同步 FollowState | medium | **P1 修** | 四入口派发 `FollowEvent.Retry` | §2.3 |
| D1-F06 `clearChat` 漏清粘贴芯片 | medium | **P1 修** | 补 `clearPastedText()` | §2.4 |
| D1-F07 Move-to 在编辑态未解编辑锁 | low | **P2 登记** | 触发需复合前置（源/目标同时处于编辑态且有分享武装），且修法涉及「搬哪份内容」的语义取舍 | 登记 §4-b |
| D1-F08 空表跳底传 `idx = 0` | low | **P2，本次顺路修** | 与 F-05 **同一行同一次编辑**，改用已有 `safeBottomScrollIndex` | §2.3 |
| D2-F01 `truncateBeforeEdit` 漏两处回退清理 | High | **P1 修** | 补齐，并加全包回退parity 断言 | §2.5 |
| D2-F02 `PendingTransfer` 不迁移粘贴块 | High | **P1 修** | 字段 + 两条纯规则 + 两端接线；路径 A 已被既有展开逻辑覆盖（见 §2.6） | §2.6 |
| D2-F03 取消清理的 DB 写未跟踪 | Medium | **P2 登记** | 竞态窗口在毫秒级，且修法要跨 3 个入口加 join，非「顺路」 | 登记 §4-c |
| D2-F04 记忆面板漏 `MEMORY-ROLLUP.md` | Medium | **P2 登记** | 是「看不全」不是「做错」，纯 UI 一致性；不属用户报告的回归 | 登记 §4-d |
| D2-F05 `promoteDraftIfNeeded` 失败留空会话 | Medium | **P2 登记** | 需 `appendMessage` 失败/取消，窄触发 | 登记 §4-e |
| D2-F06 记忆/历史 Sheet 用 `remember` | Low | **P2 登记** | 转屏丢未保存编辑；修法是 `rememberSaveable` 批量替换 | 登记 §4-f |
| D10-F05 `InlineContent` 的 remember 键漏 style/主题色 | P3 | **P2 登记** | 键确实漏（`remember(cleaned, color, linkListener)`），但两条触发面都要「composition 内换主题/字号」；见 §4-g 的判读 | 登记 §4-g |

**「谁、在什么操作下会遇到」**（P0/P1 必填）：

- **F-02**：草稿里贴过长文（折叠成芯片）→ 长按历史消息点「编辑」→ 直接点「退出编辑」胶囊。
  后果：附件回得来，粘贴芯片连同正文一起消失。粘贴越重要（报告/日志）损失越大。
- **F-04**：输入一整段/一次大粘贴 → 点发送时**恰好**在自动压缩中（`_isCompacting`），
  或 provider 尚未配置/被禁用。后果：整条消息被一条 inline 提示换掉。
- **F-05**：上滑读历史（→ DETACHED）→ 长按某条消息 Retry / 工具块 Rerun / 点行内错误重试。
  后果：视口跳到底但新答案不再跟随滚动，且 Down-FAB 一直挂在「已在底部」的列表上。
- **F-06**：composer 里留着折叠芯片 → Context-Full 弹窗点「Clear Chat」（**这条路径不走**
  `ComposerCommand.Clear`）。后果：清空后的聊天仍挂着旧芯片，下一条消息把上一个会话的材料带进去。
- **D2-F01**：会话触发过压缩（或上下文接近上限）→ 上滑到较早的用户消息改字后发送。
  后果一：被删轮次的摘要继续注入提示词（`loadSession` 的 createdAt 治愈还会把它重新锚到编辑后的行上）；
  后果二：编辑后的短会话仍按编辑前的 180K 读数被判 EXHAUSTED，被 Context-Full 弹窗挡住，或触发一次无意义压缩。
- **D2-F02**：贴一段长报告（detached 芯片）→ 打字写指令 → 点「Move to…」换会话。
  后果：目标的输入框只有指令，报告正文被静默丢弃（源侧紧接着清空缓冲，找不回来）。

---

## 2. 逐条证据（修复前后 + 测试名 + 变异）

### 2.0 装置（`shared/work/incr1118/t1/`）

| 文件 | 作用 |
|---|---|
| `extract.py` | 从**生产文件原文**切出 `PastedText` / `pastesAfterCancelEdit` / `pastesCarriedByMove` 组成编译模块——不是手抄副本，切片前先校验长度与 `companion object` |
| `run.sh` | `post` = 当前树；`pre` = 基线 `c85131ad`。**pre 臂把基线源码落到镜像目录再用 `-Duser.dir` 指过去**，否则 shape 测试读的还是工作树，等于没证 |
| `mutate.py` | 12 条变异臂，每条 = 精确字符串回退一处修复 → 跑对应测试类 → `cp` 快照还原（不用 `git checkout --`，那会连未提交的修复一起抹掉） |

**先证（pre 臂，基线源码）**

| 测试类 | 基线结果 |
|---|---|
| `ComposerClearWiringTest` | **5/5 红**（exit-edit 顺序、clearChat 芯片、truncateBeforeEdit 缺两处、顺序、parity） |
| `ChatRefusedSendRestoreTest` | **4/4 红**（通用「拒绝必须交还文本」+ 两条点名 + 分享武装顺序） |
| `RetryFollowReengageTest` | **3/7 红**（四入口无 dispatch、未用安全索引、仍有手写 `coerceAtLeast(0)`）；4 条 rule 层用例按设计本来就绿（纯 reducer，与树无关） |
| `ComposerPasteCarryTest` | 基线无法编译（两个纯函数在当时还不存在）——如实记为「纯规则是本轮新增」，其 shape 层证据由 pre 臂的三个类覆盖 |

**修复后（post 臂）**：`OK (13)` / `OK (4)` / `OK (7)` / `OK (5)`，共 **29 用例全绿**。

**变异（12 臂，每条只翻对应断言）**

```
M1  ChatInputArea.kt        RED -> exit-edit hands the draft chips back after the clear funnel
M2  ChatViewModel.kt        RED -> clearChat drops the paste chips
M3  ChatQueueInterruption.kt RED -> truncateBeforeEdit resets (marker/pressure) | AFTER rebuild | package parity
M4  ChatQueueInterruption.kt RED -> truncateBeforeEdit resets AFTER the history rebuild      (顺序臂)
M5  ChatViewModel.kt        RED -> compaction refusal restores | no early refusal drops text
M6  ChatViewModel.kt        RED -> missing provider restores | no early refusal drops text
M7  ChatViewModel.kt        RED -> the share arm is spent only after the provider gate
M8  ChatScreen.kt           RED -> every retry entry point dispatches FollowEvent Retry
M9  ChatScreen.kt           RED -> resolves index through the shared helper | no hand-rolled index
M10 ChatInputArea.kt        RED -> move-to stash fills pastedTexts from the live buffer
M11 ChatScreen.kt           RED -> move-to drain restores the blocks as detached chips
M12 ChatViewModel.kt        RED -> exit edit demotes an inline chip whose literal the clear removed
M13 ChatInputArea.kt        RED -> the composer restores the carried blocks as detached chips
```

M5 的第一次结果暴露了测试自身的漏洞（`no early refusal` 因「跳过含 `trimmed.isBlank()` 的窗口」
把隔了几句的压缩分支一起豁免了，只删 `setInputText` 仍绿）。已改成不豁免——空输入守卫的
`return` 与条件同行，本来就进不了扫描循环。**这是本支最值得记的一条：豁免条件比断言更容易瞎。**

另：`kotlinc` 语法级检查 6 个改动文件，`expecting/unclosed/unexpected tokens/syntax error` 命中 **0**
（其余 1344/2008/968/372/29/0 条全部是 Android/Compose classpath 缺失导致的 unresolved 噪声）。
真编译证据以分支 CI 为准。

---

### 2.1 D1-F02 · 退出编辑销毁草稿芯片（P1）

**先证**：`cancelEdit()` 把 `_preEditPastes` 写回 `_pastedTexts`（附件同理），紧接着
`clearComposerInput()` 调 `viewModel.clearPastedText()`（audit P1-C 的设计，发送/清空场景防芯片残留）——
两条各自合理，串起来就是「先恢复后抹除」。附件不受影响（`clearComposerInput` 不动 attachments），
所以症状是「附件回来了、粘贴芯片没了」。

**第二层（只改顺序并不够）**：能恢复的快照里，inline 芯片的 `[Pasted#N]` 字面量原本在**草稿正文**里，
而退出编辑时正文已经被编辑 prefill 覆盖、随即被清空。`expandPastePlaceholders` 只认字面量、
`appendUnlinkedPastes` 只收 detached、`unexpandedPasteMarkers` 也不会报警 —— 原样恢复 inline 会得到一个
**看得见、发送后内容蒸发** 的芯片。

**修法（三个动作，漏斗仍在最后）**

```
val draftPastes = viewModel.peekPreEditPastes()        // 先记住草稿芯片（只读）
viewModel.cancelEdit()                                 // 退出编辑：恢复附件 + 快照
clearComposerInput()                                   // 清输入框（漏斗，按设计丢缓冲）
viewModel.restoreDraftPastesAfterClear(draftPastes)    // 放回，且只以 detached 形态
```

- `pastesAfterCancelEdit(snapshot, liveText)`（纯函数）：**字面量还在 → 保持 inline；不在 → 降级 detached**。
  `cancelEdit()` 内部也用它（对「不清空正文就取消」的调用方保持旧形态）。
- `restoreDraftPastesAfterClear` 再跑一次同一规则（此时正文已是空串）并按 id 去重，保证不会与现存芯片重复。

**为什么不是「把 clearComposerInput 挪到 cancelEdit 之前」**：我第一版就是这么写的，分支 CI #3217 的
`chat_ui_interaction_check` 两条判据把它挡住了（详见 §2.7）。门的要求是对的 —— 这个漏斗是**唯一**重置
字段的地方，每个 composer 出口都必须经过它，且必须最后经过。所以正确解法是「把芯片带过漏斗」，不是颠倒顺序。

**测试**：`ComposerPasteCarryTest` 的 `exit edit *` 四条（保留 inline / 降级 detached / 一个都不丢 /
`[Pasted#1]` 不被 `[Pasted#10]` 误命中）+ `ComposerClearWiringTest.exit-edit hands the draft chips back
after the clear funnel`（记住 → 漏斗 → 放回，三段顺序）+ `ComposerPasteCarryTest.exit-edit restores
through the helper, not the raw snapshot`。

**变异**：M1（把放回挪到漏斗之前）→ 只翻顺序断言；M12（去掉降级）→ 两条降级断言红。

**用户行为变化**：退出编辑后，草稿里的折叠粘贴块仍然在（以 detached 芯片形态），发送时拼在正文之后；
附件、正文清空的行为与修复前一致（正文本来就不快照，见 §4-h）。

### 2.2 D1-F04 · 拒绝分支丢输入（P1）

**修法**（`ChatViewModel.sendMessage`）

- `_isCompacting` 分支：`appendSystemInfo(...)` 之后补 `setInputText(text)`。
- `initialProvider == null` 分支：补 `setInputText(text)`。
- `_hasInjectedShareContent.value = false` 从 provider 门**之前**移到**之后**：分享武装只该被
  「真的开始的发送」花掉；被拒的发送花掉它之后，「Move to…」胶囊就再也搬不动这条分享草稿了。

**为什么只还原文本就够**：`ChatInputArea` 在发之前已经把能展开的内联块展开进 `expandedToSend`，
所以 `text` 里没有悬空 marker，还原后不会触发 fail-closed 发送禁令。

**测试**：`ChatRefusedSendRestoreTest` 四条 —— 通用规则「`sendMessage` 在拿到 turn 之前的每个裸
`return` 前面必须有交还动作（`setInputText(text)` / `enqueuePrompt(` / `pendingExhaustedText =`）」，
外加两条点名断言与一条顺序断言（分享武装在 provider 门之后）。

**变异**：M5/M6（删各分支 `setInputText`）→ 点名断言与通用规则同时红；M7（把握手挪回门之前）→ 只翻顺序断言。

**用户行为变化**：压缩中/无 provider 时发不出去，但文字回到输入框（提示仍是 inline 提示，不改文案）。

### 2.3 D1-F05 + D1-F08 · 跳底不同步 FollowState / 空表索引（P1 + P2 顺路）

**修法**（`ChatScreen.kt` 五处：`RETRY-FROM-MSG` / `RERUN-FROM-TOOLRUN` / `INLINE-RETRY-LAST` /
`RERUN-FROM-AGG-TOOL` / `EDIT-MSG`）

- 前四处 = 「用户要求重跑一轮」= 新流开始 → 先 `followState = dispatchFollow(..., FollowEvent.Retry, ...)`
  再跳底（与 Resume 横幅的契约完全一致；reducer 只抬一个 `pendingBottomRequest`，不重复滚）。
- 五处索引统一走已有 `safeBottomScrollIndex(totalItemsCount)?.let { ... }`：
  空表时返回 null（不跳），而不是「跳到第 0 行」。

**测试**：`RetryFollowReengageTest` —— rule 层 3 条（DETACHED + Retry ⇒ FOLLOWING 且恰一个 RETRY 请求、
不涨 `rowRevision`、FOLLOWING 保持），shape 层 4 条（四入口在本回调内派发、五处用安全索引、
`ChatScreen` 里不再出现手写 `(totalItemsCount - 1).coerceAtLeast(0)`、Resume 的派发没被顺手删掉）。
shape 层用「回调锚点 → 滚动调用」的区段切片，避免用邻座回调的派发冒充。

**变异**：M8（删一处 dispatch）→ 只翻 dispatch 断言；M9（一处退回手写索引）→ 索引断言 + 手写检测同时红。

**用户行为变化**：读历史时点 Retry/Rerun，视口跳回底部后**会**继续跟随新答案，Down-FAB 随之消失。

### 2.4 D1-F06 · `clearChat` 漏清粘贴芯片（P1）

**修法**：`clearChat()` 在清 attachments 之后补 `clearPastedText()`（同时清掉 orphan id 预留）。
`pendingExhaustedText` 那部分**不重复加**：Context-Full 弹窗的 Clear 路径先调用
`dismissContextExhaustedDialog(restoreInput = false)`，那里已经清零；报告点名的另一半实际不存在。

**测试**：`ComposerClearWiringTest.clearChat drops the paste chips`（函数体按缩进切片，不用花括号计数——
这两个文件满是字符串模板，括号计数会静默切短）。

**变异**：M2 → 只翻该断言。

**用户行为变化**：清空会话后输入框里不再残留旧芯片，下一条消息不会带上被删会话的材料。

### 2.5 D2-F01 · `truncateBeforeEdit` 漏两处回退清理（P1）

**修法**：在 `agentHistory` 重建之后补 `dropCompactMarkerIfRewound(sid)` + `resetContextPressureState()`。
**顺序是有意义的**：前者靠 `agentHistory` 判断 marker 的锚点是否还活着，放在重建之前等于拿「已被删掉的行」
当活行，孤立 marker 永远不会被判为孤立（M4 单独验证这一条）。

顺带印证了既有注释：`ChatViewModel.kt` 的编辑-send 注释一直写着「edit 路径会先截断并在 shell 里调
`resetContextPressureState()`」——这句话此前是假的，现在才是真的。

**测试**：`ComposerClearWiringTest` 三条 —— 点名（两处都在）、顺序（都在重建之后）、
**全包 parity**：凡函数体同时含 `deleteMessagesAfter(` 与 `agentHistory.clear()` 者（= 回退签名），
必须同时含这两处清理。parity 扫描要求至少命中 4 条回退路径，防止切片漂移后「零命中 ⇒ 全绿」。

**变异**：M3（删两处）→ 三条全红；M4（挪到重建之前）→ 只翻顺序断言。

**用户行为变化**：编辑较早的消息并重发后，提示词里不再带被删轮次的摘要；也不会因为旧读数被 Context-Full 挡住或触发无意义压缩。

### 2.6 D2-F02 · Move-to 不迁移粘贴块（P1）

**先证路径 A 已被覆盖**：源侧 stash 时用的是 `viewModel.expandPastedText(...)`，它消费掉所有**能解析**的
inline 条目（`expandPastePlaceholders` 返回 consumed，`expandPastedTextWithSplit` 丢掉这些行），
所以「`[Pasted#1]` 字面量到了目标会话、缓冲却是空的」这条不再发生。报告把 A/B 并列为同一 finding，
实际只剩 **B**：detached 块（「贴报告 → 写指令」的常见草稿）和用户手删字面量后的 orphan 行留在源侧，
被紧随其后的 `clearComposerInput()` 永久销毁。

**修法**

- `PendingTransfer` 增 `pastedTexts: List<PastedText> = emptyList()`（默认值让旧调用点原样编译）。
- 纯函数 `pastesCarriedByMove(pastes, movedText)`：**字面量已在正文里的不搬**（不然目标会「屏上一份 + 发送时再拼一份」），
  其余一律转成 **detached** 携带（目标正文没有对应字面量，inline 形态在那边不可展开）。
- 源侧（`ChatInputArea` 的 Move-to 回调）传入 `pastesCarriedByMove(viewModel.pastedTexts.value, inputFieldValue.text)`。
  判断基准是**字段的实时文本**（不是展开后的串，也不是 `viewModel.inputText` 的 150 ms 防抖快照）：
  字面量还在屏幕上 ⇒ 它会被上面的展开带进正文，不该再搬一份。
- 目标侧走**意图通道**：`ChatScreen` 的 drain 只发 `ComposerCommand.RestoreDraftPastes(transfer.carriedPastes)`，
  composer 收到后调 `restoreDraftPastesAfterClear`（重新分配 id：`nextFreePasteId` 还会把屏上已有字面量计入
  used，源侧 id 不会串；形态固定为 detached）。
  这一条是被既有断言逼出来的正确形态：`PasteFoldWiringTest` 钉住「粘贴缓冲属于 composer，`ChatScreen` 不得出现
  `PastedText` / `pastedTexts` / `stashPastedText`」（该文件属另一个并行分支的归并安全属性），
  所以字段名也改成 `carriedPastes`，让规则仍可机械检查。
- `MoveToCapsuleGate` 只改 KDoc（说明「芯片会随合格草稿一起走」）；**判据本体不动**：
  「只有 detached 芯片」的草稿仍然不点亮胶囊（这是「胶囊是搬运动作」的取舍，改它会改变胶囊的供给面，
  登记为后续可选项而不是本支顺手改）。

**测试**：`ComposerPasteCarryTest` 四条纯规则（搬 detached / 字面量已在正文里就不搬 / 搬 orphan /
空草稿什么也不发明）+ 三条接线（`PendingTransfer` 有字段、源侧填 `pastesCarriedByMove`、目标侧用 `inlineMarker = false`）。

**变异**：M10（源侧不填）→ 只翻源侧接线；M11（目标侧按 inline 还原）→ 只翻目标侧接线；M12 见 §2.1。

**用户行为变化**：「Move to…」之后，目标的芯片行里出现同样的粘贴块，发送时拼在正文之后（与新建粘贴的顺序一致）。

### 2.7 分支 CI #3217 的门禁反馈（本支第一版修法被挡下）

`chat_ui_interaction_check`（114 绿 / 1 红）在 ChatInputArea 上给了两条：

```
[E2-transfer] the transfer site passes `movedText`, not the field's live value (inputFieldValue.text)
[F4] the exit-edit pill at line 2226 acts on the composer without routing through clearComposerInput
     on the same path -- the field reset is bypassed
```

两条都不是「门瞎了」，而是**门锚定的正是我改动的两个决定点**：

- **E2**：Move-to 的 `inputText =` 必须是字段的实时值表达式。我原来把它提成了 `movedText` 局部变量 ⇒ 判据失配。
  改回内联后，`pastesCarriedByMove` 也改成读 `inputFieldValue.text`（更正确：字面量还在屏幕上就不该重复搬）。
- **F4**：退出编辑胶囊必须**在锚点之后**经过 `clearComposerInput`（reach 200 字符，途中不许有块边界）。
  我第一版是把 `clearComposerInput()` 挪到 `cancelEdit()` **之前** ⇒ 方向相反。门依据的是「这个漏斗是字段的
  唯一重置点，每个出口最后都要过它」，我认同这个决定，所以改造实现而不是改造门：把芯片**带过**漏斗
  （§2.1 的 peek → cancel → funnel → restore 四步），并把 F4 的 `[F4] the cross-session move` 也算进距离预算
  （stash 调用里加了注释后，锚点到漏斗的距离一度 405 字符 > reach 400，缩回嵌套类短名 `PendingTransfer(...)`
  才落回 386 —— 这一条同样是门抓到的（第二次红），我第一次没算注释的长度预算）。

**处置后本地复跑**：`chat_ui_interaction_check` → `CHAT-UI-INTERACTION GATE OK`；
四个直接邻门（锁 `stale_closure_guard`（我加了引用 `followState`/`sessionId` 的 lambda）、
`discarded_return_guard`（`stashPastedText` 返回值丢弃）、`chat_session_id_binding_check`、
`claim_release_guard`（动了 `sendMessage` 的守卫区））全绿；harness 28 用例仍全绿；12 条变异臂仍各自翻红
（M1 的变异改成「把放回挪到漏斗之前」，因此断言名也随之改为 `exit-edit hands the draft chips back after
the clear funnel`）。

**教训（可复用）**：修一个「顺序」类 bug 之前，先跑本域的门，看它是否已经锚定了那个顺序——
锚定的话，正确修法通常是「让状态跨过那一步」而不是「颠倒那一步」。

### 2.8 单测 #3225 的三条既有断言（第二版修法被挡下）

`unit-tests` 红了三条**既有**测试，都不是新代码的 bug，而是新形态踩到了它们钉住的设计：

| 测试 | 断言 | 判定 | 处置 |
|---|---|---|---|
| `PasteFoldWiringTest`「ChatScreen is untouched by this feature」 | `ChatScreen.kt` 不得出现 `PastedText` / `pastedTexts` / `stashPastedText` | **对**（粘贴缓冲属于 composer；该文件归并行分支，零交集是合入安全属性） | drain 改发 `ComposerCommand.RestoreDraftPastes` 意图；`PendingTransfer` 字段改名 `carriedPastes` |
| `B3RenderInputShapeTest`「everyClearEntranceRoutesThroughTheFunnel」 | `stashPendingTransfer(` → 清空漏斗 < **400** 字符（从 token 起算，不是从括号起算） | **对**（这个漏斗必须在出口最后且够近） | 缓冲读取挪进 `viewModel.pastesForMove(composerText)`，调用点回到 384 |
| `PasteFoldWiringTest`「leaving edit mode puts the draft's chips back」 | 字面 `_preEditPastes.value?.let { _pastedTexts.value = it }` | **pin 落后于决策**（决策是「进入编辑清空 live 行 ⇒ 这条路径必须把快照还回来」，我仍还，只是经 `pastesAfterCancelEdit` 决定形态） | 把这个**拼写 pin 改成决策 pin**（`pastesAfterCancelEdit(` + `_pastedTexts.value =`），并在注释里写明为什么「清空 live 行」只有靠这条路径还回来才安全 |

第三条是本支**唯一**改动既有测试的一处：pin 的原意（快照必须回来）被完整保留，去掉的只是它对实现写法的耦合；
降级规则自身的负向由 M12 变异臂守着。

**处置后本地复跑**：`chat_ui_interaction_check` OK；五个邻门（`stale_closure_guard` /
`discarded_return_guard` / `chat_session_id_binding_check` / `claim_release_guard` /
`provider_remember_key_check`）全绿；harness **29 用例**全绿；**13 条**变异臂各自翻红（新增 M13：
删掉 composer 侧的还原调用 ⇒ 意图测试红）。

**教训（可复用）**：既有 shape 断言是「上一次决定的化石」。改实现前先 `grep` 相关 token 被谁钉住——
被钉住时，正确做法通常是**换通道**（意图 / 命令）而不是绕过断言；只有 pin 明显落后于决策时才动测试，
且必须保留原意 + 用变异臂守住新形态。

---

## 3. 未修项的三件套（触发条件 / 方案 / 搁置理由）

按 POLICY，P2 只在「同一函数、同一行、同一次编辑」才捎带；以下都**不是**同一次编辑，故只登记。

**§4-a D1-F01 残留**：`t7EndRun` 自己抛异常时（`t7Reduce`/`RunFinalized` 在 `runCatching` 之外）字段不会被清。
- 触发条件：`t7EndRun` 前半段抛异常且下一轮同 observer 正常结束（实测无日志证据；等效 OOM 级事故）
- 方案：`runAgentLoop` 入口 `traceObserver.t7BudgetStopReason = null` 一行（幂等、零成本）
- 搁置理由：当前无任何可达路径；加一行没有能翻红的测试（要构造 `t7EndRun` 抛异常的桩），属「装置比例不符」

**§4-b D1-F07 Move-to 与编辑态**：
- 触发条件：源会话在编辑态 + 分享武装（或目标会话停在编辑态）时执行 Move-to
- 方案：源侧在 stash **之前**判定；目标侧 drain 顺序必须是 `cancelEdit()` → `clearAttachments()` →
  `clearPastedText()` → 追加文本 → 加附件 → 还原携带的芯片（顺序错会把 pre-edit 快照一起送出去）
- 搁置理由：语义未定（「编辑态里点 Move-to」到底搬编辑内容还是搬草稿？），先别用实现替用户定规矩

**§4-c D2-F03 取消清理的 DB 写未跟踪**：
- 触发条件：点 Stop 之后在清理的 IO 落库（毫秒级）之前立刻点 Retry / 切模型重跑
- 方案：清理的 `viewModelScope.launch(Dispatchers.IO)` 登记成 job，`retryLast` / `switchModelAndRerun` /
  `sendMessage` 在 `loadMessages` 前 join
- 搁置理由：跨 3 个入口 + 需要可控延迟的假 repo 才能证；不是「顺路」

**§4-d D2-F04 记忆面板漏 `MEMORY-ROLLUP.md`**：
- 触发条件：用户打开 SessionMemorySheet 想核对「自动注入了什么」
- 方案：`AutoInjectedFiles` 增 `rollupMd`、`readAutoInjectedFiles` 读 `MEMORY-ROLLUP.md`、
  `buildAutoInjectedItems` 非空才追加（`SessionMemoryAutoItemsTest` 可扩）
- 搁置理由：纯展示一致性，不改注入行为；不属用户报告的回归

**§4-e D2-F05 `promoteDraftIfNeeded` 失败留空会话**：
- 触发条件：`ensureSession()` 之后 `appendMessage` 失败 / `viewModelScope` 被取消（纯附件草稿）
- 方案：`finally` 的 `!persisted` 分支在 `NonCancellable` 里删掉本次新建的空会话行
- 搁置理由：窄触发 + 需要「假 repo 在第 N 次 append 抛异常」的装置

**§4-f D2-F06 `remember` → `rememberSaveable`**：
- 触发条件：记忆/历史 Sheet 编辑中旋转屏幕
- 方案：`isEditing` / `editedState` / `searchQuery` 换 `rememberSaveable`；`editedWindow` / `editedFullBase`
  补 `remember(mode)` key（顺带解决退出编辑后仍持有数百 KB 字符串）
- 搁置理由：Compose 状态恢复只能用仪器测试（本支禁 gradle）

**§4-g D10-F05 `InlineContent` remember 键**：
- 触发条件：`color` 不变而 `style`/`MaterialTheme.colorScheme` 在**同一次 composition** 内变化
- 方案：`remember(cleaned, color, style, inlineCodeBg, inlineCodeColor, linkColor, linkListener)`
- 搁置理由：判读后降级 —— 主题/夜间模式在本 app 会重建 Activity（`AndroidManifest` 无 `configChanges`），
  字号设置只在 Appearance 屏写入（`KEY_FONT_MESSAGE` 的唯一写点在 `AppearanceScreen.kt:636/660`），
  从 composer 侧改不到；真正能触发的只有分屏/多窗口。属「窄触发」，不是本轮用户能碰到的

**§4-h（本轮新发现，不在原报告内）编辑模式会吞掉草稿正文**：
- 观察：`editMessage()` 只快照 `_preAttachments` / `_preEditPastes`，**不快照草稿正文**；退出编辑时正文被清空
- 触发条件：输入框有草稿正文 → 长按历史消息「编辑」→ 退出编辑
- 方案：加 `_preEditText` 快照（进入时存、`cancelEdit` 时 `ReplaceText` 还原），或进入编辑时把草稿正文
  并进 `_preEditPastes` 的同类快照
- 搁置理由：报告里没有这条（D1-INV-07/08 只覆盖附件与芯片）；语义要先确认（iOS 行为？是否允许编辑中保留草稿），
  且修法会与 F-02 的芯片恢复顺序相互影响，交主会话分派

**§4-i D2-F02 残留**：正文里带着「本会话解析不出的字面量」（例如进程重启后草稿残留的 `[Pasted#3]`）时，
Move-to 只搬文本、不搬块；目标会话会被 `unexpandedPasteMarkers` 的 fail-closed 拦住发送。
- 触发条件：源会话已经处于「发送被拦」状态时点 Move-to
- 方案：源侧在移动前把不可解析字面量删掉并告知用户（或干脆禁用 Move-to）
- 搁置理由：源侧本来就已经拦住发送，搬过去只是把「同一个拦截」换个会话；本轮不发明新语义

---

## 4. 未确认 / 未复核

1. **D1-F07、D2-F03、D2-F05** 只做了代码级可达性判读（fire-and-forget 的 `viewModelScope.launch` 无句柄；
   `cancelEdit` 不在 Move-to 两侧；`finally` 只恢复文本），**没有**装置证伪。按 P2 登记，不作为「已证」。
2. **D2-F03 的竞态窗口**无实测数据（未跑真机、未造延迟 repo），登记里写的是「毫秒级」，不是测量值。
3. 本支**未**跑全量 `scan.sh` / `test_scan.py` / gradle（任务书硬约束）；编译与测试类执行的真证据只在分支 CI。
   本地只跑了本门（`chat_ui_interaction_check`）+ 四个直接邻门 + 28 用例切片 harness。
   本地的 28 用例是**切片编译**（`ChatFollowController.kt` 原文件 + 两条纯函数原文 + `PastedText` 原文），
   与 CI 的完整 classpath 不同源，不能互相替代。
4. `D1-INV-12` 里点名的第六处 `FAB-UP`（`tracedScrollToItem("FAB-UP", 0, 0)`）**不是**跳底：它按设计跳第 0 行，
   未改。若将来有人把它也 `safeBottomScrollIndex` 化，是另一件事。

## 5. 跨域指针

无。本支全部改动落在 allowlist（`ui/chat/**` 生产码 + `ui/chat/**` 测试 + `fix-reports/`），
未触碰 `scripts/scan/**` / `scan.sh` / `test_scan.py`，也没有需要别的域接手的条目。

（§4-h 若要做，需要先确认「编辑态与草稿正文并存」的产品语义——这属于主会话/用户拍板，不是别的代码域。）
