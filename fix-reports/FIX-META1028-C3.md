# FIX-META1028-C3 — 输入区四条（ChatInputArea.kt）

分支 `fix/meta1028-c3-input-area`，基线 main `bfb5b0e6`（报告基线 `47c4bf4b`，行号已漂移，逐条回真树核过）。
文件面：`ui/chat/ChatInputArea.kt`（+160/−26）+ `test/.../ComposerCommandHandoffTest.kt`（+249）。
**零触碰**：`scripts/scan/**`、`scan.sh`、`test_scan.py`、`ChatViewModel*.kt`、`ChatScreenUtils.kt`、`ComposerTextOps.kt`、其余测试类。

---

## 1. 先证（回真树读到的原文，不是采信报告）

四条都先按四环（入口 → 中间 → 出口）走了一遍，`rg` 清点写点/读点后再断言。

### F-01 漏斗旁路了光标意图（确认，high 成立）

- **写点 6 处**：`ChatViewModelSlashExt.kt:187`（`showSlashMenuOverInput` 置 1）、`:209`（`dismissSlashMenu` 置 saved.length）、
  `ChatSlashTokenExt.kt:132`（技能插入置 prefix.length）、`:197`；读点全库**只有 1 处**：
  `ChatInputArea.kt:396`（`LaunchedEffect(inputText)` 体内，且在 `if (inputFieldValue.text != inputText)` 里面）。
- **入口**：4 个斜杠出口 `:288/:293/:582/:684`（本树行号 `:315` 起那几处）全部 `commitComposerBody(next, null)`。
- **中间**：`commitComposerBody` 先 `viewModel.setInputText(text)` 再 `inputFieldValue = TextFieldValue(text, …)`，
  **两份都写**。于是 `LaunchedEffect(inputText)` 重跑时 `inputFieldValue.text != inputText` 恒 false ⇒ 读者永不执行。
- **出口**：`composerCommitCaret(null, lastTrueCaretEnd, len)` = `lastTrueCaretEnd`（斜杠前的旧光标）⇒
  `/ hello world` 光标停在 `11` 而非 `1`；`_pendingCaret` 滞留到下一次外部写入时劫持光标。
- 报告里的纯函数单测（`ComposerCommandHandoffTest:213`）只钉了 `composerCommitCaret(null,7,20)==7`，
  注释也还写着「斜杠出口没有 pendingCaret」——**这正是缺口本身**，不是反证。

### F-03 回车发送下的 Shift+Enter（确认，medium 成立）

`onKeyEvent`（`:1712` 起）在 Shift 分支返回 `false` 让 Compose 插 `'\n'`；`onValueChange` 的软键盘回车拦截
（`:1500-1528` 原样）只认「长度 +1 且换行数 +1 且光标前是 `'\n'`」——**硬件 Shift+Enter 插的那个 `'\n'` 与 IME 回车同形**，
于是当场 `performEnterSend()`。原注释「Shift+Enter never reaches the send path」是错的。

### F-04 清空输入不带走弹窗（确认，medium 成立）

`clearComposerInput` 原样只有四个写（burst cancel / `setInputText("")` / `inputFieldValue` / `lastTrueCaretEnd`）。
`setInputText("")` 是 StateFlow 写，**不是 `onValueChange`**，所以 `_showMentionMenu` / `_showSlashMenu` /
`savedInputBeforeSlash` 一个都不动。而 `DismissSlashMenu` → `dismissSlashMenu` 会回填 `savedInputBeforeSlash`
（`ChatViewModelSlashExt.kt:204-209`）⇒ 已发送的旧草稿能诈尸。发送类出口共 7 处全部走这个漏斗（`:298/:420/:432/:1390/:1406/:1753/:1999`），所以修漏斗一处即全覆盖。

### F-05 防抖的基准是滞后副本（确认，medium 成立）

`shouldDebounceImeBurst(oldText, newText)` 的契约首参是「上一次编辑器文本」（`ChatScreenUtils.kt:82-104`），
调用点（`:1567` 原样 `shouldDebounceImeBurst(inputText, tfv.text)`）却传了 150 ms 滞后的 VM 串。
两后果按报告原文复核成立：①粘贴后逐字输入，`tfv.text.length - inputText.length > 8` 恒成立 ⇒ 每次击键都被防抖；
②剪切/撤销回到 VM 串时 `if (inputText != tfv.text)` 为 false ⇒ 走 `else`（只刷 mention 状态），
`imeBurstJob` / `imeBurstBuffer` / `_pendingComposerText` 全部留存，`promoteDraftIfNeeded` 会把已删内容存成会话。

---

## 2. 修法逐条

| ID | 位置（本树） | 改法 |
|---|---|---|
| F-01 | `:288-293` + 新纯函数 `:2173` | `composerFunnelCaret(caret, pendingCaret = viewModel.consumePendingCaret(), lastTrueCaretEnd, length)`：**每次提交都抽干意图**（残留意图不得跨过一次 commit），显式 caller caret 优先于意图，其余走原 `composerCommitCaret` 的钳位逻辑。纯函数化是为了让优先级能上 JVM（漏斗本体要 composition）。 |
| F-03 | `:201`（标志）+ `:1513-1528`（拦截）+ `:1721-1733`（按键）+ 纯函数 `:2196` | `onKeyEvent` 在 KeyDown+Enter+Shift 时置 `shiftEnterNewlinePending = true` 并照旧返回 false；拦截块读该标志并**无条件清掉**（一次按键至多一次插入；标志活过自己的插入会吃掉下一次真软回车 = 丢一次发送），值为真则跳过发送。判定形状抽成 `isEnterSendEdit(oldText,newText,caretEnd)`。 |
| F-04 | `:233-252`（漏斗）+ `:323`（斜杠按钮臂） | 漏斗补 `dismissMentionMenu()` → `savedInputBeforeSlash = null` → `updateSlashMenuState("")`；**顺序有讲究**：先清 saved 再刷 slash 状态，否则 `updateSlashMenuState` 会走 over-content 分支把菜单再打开一次。斜杠按钮臂在解析前 `dismissMentionMenu()`（两个 picker 互斥，此前会叠在一起）。 |
| F-05 | `:1509`（取 oldText）+ `:1542`（守卫）+ `:1567`（基准）+ `:1621`/`:1639`（两 else） | `val previousFieldText = inputFieldValue.text` 在 `inputFieldValue = tfv` 之前取；守卫改 `if (inputText != tfv.text || imeBurstBuffer != null)`；基准改 `shouldDebounceImeBurst(previousFieldText, tfv.text)`；两个非防抖出口开头 `cancelPendingImeBurst()`（含 `publishPendingComposerText(null)`）。 |

**共享层判断**：四条都落在同一文件的两个既有漏斗（`commitComposerBody` / `clearComposerInput`）+
`onValueChange` 的一段，没有新增第二份判定；`composerCommitCaret` 原样保留（新函数在它之上叠一层优先级），
旧调用者（无 pending 语义的路径）行为不变。

---

## 3. 装置（为什么这些读数可信）

`ChatInputArea.kt` 是 2,100 行 Compose 文件、`ChatScreenUtils.kt` 拉进 `ChatMessage/FlatChatItem`，两者都编不进裸 JVM。
所以装置分三层，**被测的每一行都是生产原文**：

1. **纯函数层**：`probe-c3/extract.py` 从真源**逐字切**函数体（`composerCommitCaret` / `composerFunnelCaret` /
   `isEnterSendEdit` / `shouldDebounceImeBurst` / `shouldCommitImeBurst` / `IME_BURST_DELTA_THRESHOLD`），
   切片后断言「该切片在源文件里恰好出现 1 次」，否则直接退出。`ComposerTextOps.kt` 本身 Compose-free，
   整文件拷贝 + `cmp` 逐字节校验。
2. **真实测试类层**：把**真** `ComposerCommandHandoffTest.kt` 与上述切片一起 kotlinc 编译、JUnitCore 跑，
   `user.dir` = worktree 根 ⇒ 源码形状臂读的是真文件。
3. **形状层**：`probe-c3/shape.py` 用字符串感知的注释剥离器跑 32 条臂（17 条本支新增 + 15 条既有不变量回归），
   对**当前树**与 `git show bfb5b0e6:` 的**基线文件**各跑一遍。

期望值一律写字面量、不从被测常量推导（`IME_BURST_DELTA_THRESHOLD` 只在「等于 8」这一条断言里出现，其余全用具体长度）。

---

## 4. 验证读数

| 装置 | 读数 |
|---|---|
| `probe-c3/extract.py` | 5/5 切片逐字命中（`composerCommitCaret` 201 B / `composerFunnelCaret` 196 B / `isEnterSendEdit` 310 B / `IME_BURST_DELTA_THRESHOLD` 39 B / `shouldDebounceImeBurst` 175 B）；`ComposerTextOps.kt` `cmp` 一致 |
| `probe-c3/Probe.kt`（纯函数 17 断言） | `PROBE: 17 checks, 0 failure(s)` |
| 真实 `ComposerCommandHandoffTest` | **OK (29 tests)**（原 17 + 本支 12） |
| `ComposerSendSwipeTest` + `ChatSlashMcpWiringTest` + `B3RenderInputShapeTest` | **OK (28 tests)**（同文件邻居，回归） |
| `ComposerLiveSourceTest` | **OK (10 tests)**（burst / leaving-site 臂，最可能被波及） |
| `shape.py` on **after** | 32 arms，**0 failure** |
| `shape.py` on **before**（`bfb5b0e6`） | 32 arms 中 **17 条 flipped**（本支新增的每一条都红）；15 条既有不变量前后都 PASS |
| 基线编译新测试类 | `error: unresolved reference 'composerFunnelCaret'` ×6 / `'isEnterSendEdit'` ×2 ⇒ 新臂在基线上根本不存在 |
| 单门（允许范围内的那一门） | `chat_ui_interaction_check.py .` → `CHAT-UI-INTERACTION GATE OK`（rc=0） |

**"改变什么用户行为"一句话**：
① 斜杠/技能/`@` 插入后光标落在斜杠或命令末尾，而不是旧草稿中间；
② 开了「回车发送」时 Shift+Enter 真的换行（不再误发）；
③ 发送/取消后不会留下幽灵 `@`/`/` 浮层，已发送的草稿也不会被 `DismissSlashMenu` 塞回输入框；
④ 粘贴后继续打字不再每次卡 150 ms，剪切撤销撤销掉的内容不会被「新建会话」存成草稿。

---

## 5. 未验证 / 诚实边界

- **真机未验**：沙箱无 Android 运行时，四条都是「Compose 交互 + 键盘事件」形态，CI 只保证编译 + 单测 + 门。
  Shift+Enter 的两阶段联动（`onKeyEvent` → Compose 插 `\n` → `onValueChange`）只有源码形状断言，没有真机证据。
- **单粘贴一个 `'\n'` 仍会发送**：与软键盘回车同形，`onValueChange` 侧无法区分（报告 §F-03 括号里提到的那半条没修）。
  见 §6 R1。
- **`updateSlashMenuState("")` 依赖既有分支**：它靠「`text` 不以 `/` 开头 ⇒ 清 saved + 关菜单」这条既有路径；
  我在它之前显式清 `savedInputBeforeSlash`，两道路径都走一遍是刻意的 fail-closed。
- **未跑全量**：按任务书沙箱负载纪律禁跑 `scan.sh` / `test_scan.py` / gradle；上述 29+28+10 个 JVM 臂是逐类单跑，
  与 CI 全量不是同一装置（CI 仍是终裁）。

---

## 6. 残留（P2，登记用三件套；本支不修）

- **R1 单换行粘贴 = 发送**（触发：用户从别处复制一个换行符粘进输入框且开着回车发送）→ 方案：给 `onValueChange`
  的软回车判定加「最近 1 s 内是否有硬件按键事件」或改用 `InputConnection` 侧事件；搁置理由：无可靠信号，
  误判会吃掉真软回车（代价高于收益）。
- **R2 `shiftEnterNewlinePending` 是 composition 内局部状态**（触发：Shift+Enter 后焦点/重组在插入前丢失）→
  方案：改成 `rememberSaveable` 或 VM 侧单次意图；搁置理由：插入与按键同帧，当前不可达。
- **R3 `cancelPendingImeBurst()` 在「文本未变」分支是空转**（触发：每次纯光标移动）→ 方案：删掉该调用或改成断言；
  搁置理由：三行赋值成本 ≈ 0，保留作为「出口不留 live burst」的终端断言。
- **R4 F-04 只覆盖 `clearComposerInput` 漏斗**（触发：未来新增一条直接 `setInputText("")` 的清空路径）→
  方案：把「清文本必清 picker」提到 VM 侧单一函数；搁置理由：目前 7 个出口全走漏斗，门（B3 `noClearWriteBypassesTheFunnel`）
  已经钉住「空串提交恰好 1 处」。

---

## 7. 门与既有测试的影响面

- `chat_ui_interaction_check.py`（B06 / F1-F4 / G 族）：绿。B06 派生斜杠出口集合，本支只在同一臂内加一次
  `dismissMentionMenu()`，不改出口集合；F 族「空串提交恰好一处且在漏斗 400 字符内」仍成立。
- `B3RenderInputShapeTest.theClearFunnelCarriesAllThreeResets`：窗口 400 字符，四个原断言位次未动 ⇒ 绿（本地实跑）。
- `ComposerLiveSourceTest`：`imeBurstJob?.cancel()` 仍是唯一被「helper 识别」的锚点，新增的 `cancelPendingImeBurst()`
  调用不参与该识别 ⇒ 绿（本地实跑）。

---

## 8. 收口

### 8.1 CI（run `40679321812` / #2822，workflow_dispatch，11:21:30Z → 11:43:27Z）

- **conclusion = success**，`head_sha = c678e64e98848e9aa3f4e78c32840837a7edb3ea`，与本地绿提交**逐字符一致**。
- job 日志 `0_build.txt` 1,507,097 B，**NUL 0**；判读：
  - `║ Summary: 113 passed, 0 failed` **×1**（另 6 条 `Summary: N passed, 0 failed` 是分块小结，分母不同）
  - `ALL 349 CASES PASS` **×1**
  - `^ e: `（Kotlin 编译错误）**0**
  - `FAILURES!!` **0**；`Tests run:` 里无一条非 `Failures: 0`
  - 含 `FAILED` 的行 6 条，**逐条确认全是 PASSED 用例名的子串**（`… produces FAILED without calling provider PASSED`、`… phase FAILED maps to Failed PASSED` 等）
  - `ComposerCommandHandoffTest` 出现 29 次且全部带 PASSED（本支 29 例，与本地 `OK (29 tests)` 一致）
  - 检出日志里 `+c678e64e98848e9aa3f4e78c32840837a7edb3ea:refs/remotes/...` 证明跑的就是本支提交

### 8.2 状态

- 分支提交（源码 + 测试）：`c678e64e98848e9aa3f4e78c32840837a7edb3ea`（+409/−26，2 文件）
- 报告提交：本报告（`git diff --name-only c678e64e..HEAD -- . ':(exclude)fix-reports'` 为空）
- **停止节点达成：分支 CI 绿 + head_sha 逐字符一致，不合 main**（合并起手权在用户）
- 残留 R1–R4 见 §6，三件套齐（触发条件 / 触发后方案 / 搁置理由）；**未并入 backlog**——
  本批多个并行会话，避免共享 `backlog.md` 并发写冲突，留给主会话收口（与 1028B 三支同一处理） CI）
