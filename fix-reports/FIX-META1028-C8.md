# FIX-META1028-C8 — 四文件薄修合支（F-07 / F-11 / F-12 / F-13）

- 分支 `fix/meta1028-c8-sheets-misc`，基线 `bfb5b0e6`（main）
- 来源：外部货架报告 B03 的 F-07 / F-11 / F-12 / F-13（报告基线 `47c4bf4b`）
- 改动面：4 个生产 `.kt` + 4 个新测试文件；`scripts/scan/**`、`scan.sh`、`test_scan.py`、其他任何生产文件**零触碰**
- 探针：`/var/minis/shared/work/meta-audit-1028/probe-c8/`（可复跑）

---

## 1. 结论

四条全部回真树机械核实成立，四条都修了，每条独立一个提交。判级：

| ID | 判级 | 一句判据 |
|---|---|---|
| F-07 | **P1** | 工具栏整条消失（无缩略图 / 无停止入口 / 无翻页）+ 上方 83 dp 空占位；触发是常用操作（编辑重发、重试、切会话） |
| F-11 | **P1** | 把输入"移动"进抽屉里看不见的幽灵会话 = 输入落到用户找不到的地方 |
| F-12 | **P1** | 系统行伪装成助手气泡；纯附件消息不可按文件名检索、且全部显示成同一个 `[...]` |
| F-13 | **P1** | 只在 `lastContextTokens == 0` 时生效（续接历史 / 会话中途换模型），但那条路径正是 offload/trim 唯一的压力读数，低估 = 两者都推迟 |

四条都不是"设计如此"，都是遗漏分支；四条都不是崩溃，但都属于"计数正常、界面正常、数据已不对"的静默形态（20 秒能说清的危害见各节）。

---

## 2. 先证（逐条，回本树 grep，不采信报告行号）

### F-07 `ChatComposerWidgets.kt`（FloatingToolStatusBar）
- `currentIndex` 是**无键** `remember { mutableStateOf(toolBlocks.lastIndex.coerceAtLeast(0)) }`；文件内 `currentIndex` 只有 3 处写点（`LaunchedEffect` 一处 + 两个 chevron）。
- `LaunchedEffect(lastIndex)` 体内**只有** `if (newestActive) currentIndex = lastIndex.coerceAtLeast(0)` ——只前进，从不因缩短回退。
- 取用点是 `toolBlocks.getOrNull(currentIndex) ?: return`（整条 composable 提前返回）。
- 报告说"上方留空白占位"：机制查实了，且比报告更强——
  `ChatScreen.kt:2949-2960` 的 `bottomReserve` 由 **`hasFloatingTools`**（另一个谓词）驱动，与 bar 内部是否早退无关，命中时额外预留 65 dp + 18 dp = **83 dp**。也就是说 bar 自己整条消失，而预留照旧 ⇒ 83 dp 纯空白，而不是"没有工具栏"。

### F-11 `MoveToSessionSheet.kt`
- 原过滤：`chatRepository.dao.listSessions().filter { it.id != currentSessionId }`（只有自我排除）。
- 真源 `ChatHistoryDrawer.kt:667` `internal fun isDrawerRowVisible(pinnedAt: Long?, messageCount: Int, lastMessage: String?): Boolean`，语义 `pinnedAt != null || messageCount > 0 || !lastMessage.isNullOrBlank()`（dv24-b3），抽屉在 `:136` 用它过滤。
- 报告说的 `messageCount` 字段在 `ChatSessionEntity` 上**不存在**（报告按签名"想当然"了）——真源是 `ChatDao.messageCountsPerSession()` 这个 Room Flow，抽屉侧 `ChatRepository:47` 转出 `observeMessageCountsPerSession()`。修法据此调整：sheet 打开时取一次快照（`first()`），不新增数据层 API。

### F-12 `InputHistorySheet.kt`
- 原过滤：`messages.filter { it.content.contains(searchQuery, ignoreCase = true) }`；渲染：`msg.content.trim().ifBlank { "[...]" }`。
- `[...]` 的字面量来源就是这一行**本地兜底**（不是 contentParts 摘要逻辑，报告的这一处猜测是错的：没有别的产生点）。
- `ChatMessage` 有 `attachmentNames: List<String>`（真字段，报告没瞎写）；纯附件消息 `content == ""` ⇒ 渲染成 `[...]`、且按文件名搜不到。
- `role == "system"` 真存在：`ChatViewModel:2323` 造系统行（压缩标记 / 模型切换分隔 / 注入备注），原过滤一条都没排除。

### F-13 `ChatContextWindow.kt:515`
- 原实现只遍历 `msg.contentParts`，`totalChars += partCharContribution(part)`。
- 调用方只有两处 fallback：`:201`（offload）与 `:483`（trim），条件都是 `lastContextTokens > 0` 才用真读数，**否则用本估算**。
- `lastContextTokens` 归零点查实：换模型/换上下文键时 `AgentLoopEngine` 把它重置为 0（`:903`、`:1015` 区段），续接历史冷启动也是 0 ⇒ 这两个场景下估算**就是**唯一的压力读数。
- 缺口两个，都被同一文件的权威估算器覆盖，而本估算器漏了：
  - `ContextEstimator.estimateMessageTokensFull` 明确"parts 为空则算 content，二者互斥不双算"；
  - `reasoningContent` 被它计入（interleaved-reasoning provider 每轮回传）。
  这就是"同仓两个估算器口径不一致"的实证，不是"要不要算"的偏好。

---

## 3. 修法（各自最小充分，停在哪一级）

| ID | 改动 | 停在哪一级 / 为什么 |
|---|---|---|
| F-07 | 新增两个纯函数 `floatingToolIndexAfterUpdate(currentIndex, lastIndex, newestActive)`（缩短即夹紧 / 活着的最新块仍然跟随 / 用户翻页仍尊重）与 `floatingToolSafeIndex`（读点夹紧，覆盖状态写入晚一帧的那一帧）；`LaunchedEffect` 与 `getOrNull` 都改走它们；两个 chevron 改用 `shownIndex`，写入 `shownIndex ± 1` | 第 7 级（最小实现）。没有新增 state、没有改 `remember` 键（改键会丢用户翻页位置，是另一个行为） |
| F-11 | 新增 `isMoveToTargetVisible(sessionId, currentSessionId, pinnedAt, messageCount, lastMessage)`，`= sessionId != currentSessionId && isDrawerRowVisible(...)`；sheet 改为 `observeMessageCountsPerSession().first()` 取快照后过滤 | 第 2 级（**已有**）。抽屉的谓词已存在且是契约真源，复制三个条件会漂移；复用只需 import |
| F-12 | `SYSTEM_ROLE` 常量 + `rows = messages.filter { it.role != SYSTEM_ROLE }`；`inputHistoryMatches(query, content, attachmentNames)`（内容或文件名，忽略大小写，空查询全通过）；`inputHistoryPreview(content, attachmentNames)`（文本优先，退化为文件名，真空才 `[...]`）；空态文案改按 `rows.isEmpty()` | 第 7 级。没有新增字符串资源——加 R.string 要同步 7+1 语言文件（跨层完整性），这里用文件名本身就是语言中立的，不值得 |
| F-13 | 新增 `messageCharContribution(msg)`（parts 非空走 parts，否则走 `content`；再加 `reasoningContent?.length`），`estimateHistoryTokens` 改为 `totalChars += messageCharContribution(msg)`，图像项仍在原循环 | 第 6/7 级（能一行的地方一行）。刻意**不**统一到 `ContextEstimator` 的 tokenizer：那是另一个口径变更（char/3.5 → BPE），不在本报告范围，且会改动用户可见的 offload 时点，属于独立决策 |

副作用面（逐条自查）：
- 两处新 `internal` 纯函数无外部调用方，不影响其他文件；
- F-11 的 `first()` 是**一次** Room 查询，sheet 生命周期内不订阅，无新增长驻流；
- F-12 的 `rows` 是 `remember(messages)`，与原 `filtered` 的重算次数同量级；
- F-13 不改变 `countPartTokens`/`partCharContribution` 的既有共享口径（只新增消息级项）。

---

## 4. 装置与读数（本支最硬的部分）

### 4.1 为什么不能整文件编译
四个生产文件都是 Compose 文件（`@Composable`、`androidx.*`），沙箱无 Android SDK/Compose 依赖，`kotlinc` 不能整文件编；`ChatContextWindow.kt` 的 `estimateHistoryTokens` 还是 `ChatViewModel` 的扩展（依赖 4800 行的 ViewModel 类）。

### 4.2 做法：逐字切片 + 跑**真**测试文件
`probe-c8/extract.py` 按声明形态（一行 `const val` / 块体 / 表达式体）把 10 个声明从 5 个真实文件里切出来（含 `ChatHistoryDrawer.isDrawerRowVisible` 这个只读复用件），每条切片回读源文件断言 `block in real_file_text`——**逐字相同**，不是复刻：

```
VERBATIM ChatComposerWidgets.kt  internal fun floatingToolIndexAfterUpdate(   …  sha256:…
VERBATIM ChatComposerWidgets.kt  internal fun floatingToolSafeIndex(         …  sha256:…
VERBATIM MoveToSessionSheet.kt   internal fun isMoveToTargetVisible(         …  sha256:…
VERBATIM ChatHistoryDrawer.kt    internal fun isDrawerRowVisible(            …  sha256:…
VERBATIM InputHistorySheet.kt    internal const val SYSTEM_ROLE / _EMPTY_PLACEHOLDER
VERBATIM InputHistorySheet.kt    internal fun inputHistoryMatches( / inputHistoryPreview(
VERBATIM ChatContextWindow.kt    internal fun messageCharContribution( / partCharContribution(
```

然后把切片与**真数据类**（`LLMMessage.kt` / `AgentContentPart.kt` / `LLMUsage.kt`，逐字编译）+ **真测试文件**（就是提交进仓库的那四个，逐字）一起编译，JUnit 直跑，cwd = 仓库根（形状断言读的是真实源码路径）。

### 4.3 读数
| 臂 | 含义 | Tests | Failures | 红的是谁 |
|---|---|---|---|---|
| **G0 修复后** | 本支最终树 | 43 | **0** | — |
| M0 基线源码 | 编译后的**修复版**测试跑在 `bfb5b0e6` 的**修复前源码树**上（只切 cwd，不重编） | 43 | 5 | 恰好 5 条形状钉（F-07×1 / F-11×1 / F-12×2 / F-13×1）；抽屉那条形状钉照旧绿（它本来就没改）⇒ 形状钉有牙，且不会误红无关文件 |
| M1 | `messageCharContribution` 还原成 parts-only（原始缺陷） | 43 | 4 | 全部在 `HistoryCharContributionTest`：content 计入 / reasoning 计入 / 二者相加 / parts 之上加 reasoning |
| M2 | 搜索退回 content-only + 预览退回 `ifBlank { "[...]" }` | 43 | 5 | 全部在 `InputHistoryFilterTest`：附件名匹配 / 附件行显示文件名 / 空白体回退 / 部分文件名可搜 / 空名字过滤 |
| M3 | 游标还原成"只前进"（原始缺陷） | 43 | 4 | 全部在 `FloatingToolIndexTest`：越界夹紧 / 越界一格 / 负值归一 / 空列表归零 |
| M4 | 谓词还原成只看 id | 43 | 4 | 全部在 `MoveToTargetVisibilityTest`：三条幽灵行 + 与抽屉一致性的网格臂 |

每个臂**只红钉它行为的用例**，没有跨臂串红 ⇒ 断言是各自独立的结论，不是"一改全红"。

### 4.4 测试设计上值得记的两点
1. **F-11 不做复制谓词**：网格臂里同时断言 `isMoveToTargetVisible(...)` 与抽屉自己的 `isDrawerRowVisible(...)` 相等，并断言抽屉源码仍含那三个条件。谁改了抽屉口径，这条臂或形状钉会红——这是"复用真源"能成立的前提（否则复用也只是巧合）。
2. **形状钉自带负控**（M0 臂）：只断言"新形态存在"是没牙的（新形态存在 ≠ 旧缺陷消失），所以每条都配 `assertFalse(<旧拼写>)`，并用修复前源码树整跑一次证明这些断言当时确实红。

---

## 5. 本地额外闸（Gradle 不可跑时的替代）
- 单文件语法闸：四个生产文件 `kotlinc` 单跑（忽略 unresolved reference，只看 `expecting` / `unexpected tokens` 类语法错）→ 0 语法错。`MoveToSessionSheet` 侧唯一 unresolved 是 `isDrawerRowVisible`（未把抽屉文件放进该次编译集，预期）。
- 未跑（按任务书 §4 硬约束）：`scan.sh`、`test_scan.py`、gradle 全量、`assembleDebug`。**本支的编译正确性最终由 CI 承担**，本地只覆盖"纯逻辑 + 形状 + 语法"。

---

## 6. 用户可见行为变化（动手前写的那一句）

- **F-07**：编辑重发 / 重试 / 切会话之后，工具栏**不再整条消失**——它停到最新一块工具调用上，翻页箭头与"N/M"计数与所显示的那一块一致；上方不再留 83 dp 空白。（空列表仍然隐藏，那是原有设计。）
- **F-11**：移动目标列表里**不再出现**抽屉里看不见的幽灵会话（无消息、无预览、未置顶）。这是**有意收紧**：同一批"空壳"抽屉也不给用户看，两个入口口径一致；被排除的会话本身并未被删除或改动。
- **F-12**：历史输入面板不再列出系统/压缩分隔行；纯附件消息显示的是文件名（`a.png, b.pdf`），并可按文件名搜索到；`[...]` 从此只表示"真的什么都没有"。
- **F-13**：续接历史或会话中途换模型后（`lastContextTokens == 0` 的窗口），压力读数**变大**（接近真实），offload 与 trim 会更早触发。用户能看到的是"该压缩的时候真的压缩了"，而不是原本可能的 provider 400 上下文超限。**注意这是估算口径变更，属于可感知行为变化，不是纯内部重构。**

---

## 7. 残留（登记不修，三件套齐）

| # | 残留 | 触发条件 | 触发后方案 | 为什么现在不修 |
|---|---|---|---|---|
| R1 | F-13 的**比例常数**仍是 `char / 3.5`，与 `ContextEstimator`（CJK 1:1、BPE）两套口径并存 | 用户报告"中文长会话到了上限还不压缩" | 把 fallback 分支直接换成 `ContextEstimator.estimateHistoryTokens`，删掉本估算器 | 口径统一会改动所有用户的 offload 时点（可感知），是独立决策，不该夹在一条 P1 薄修里 |
| R2 | F-07 修在"取用处夹紧"，`remember` 仍无键；列表**等长替换**（切到另一个会话且块数恰好相同）时游标保留旧位置 | 用户切会话后发现工具栏停在中间而不是最新一块 | `remember(lastSessionId)` 或把游标提升到 `ChatScreen` | 等长替换时停在旧页码属于"用户翻页"与"会话切换"不可区分，改键会丢翻页位置；先观察真实反馈 |
| R3 | F-11 的 `observeMessageCountsPerSession().first()` 是**一次性**快照，sheet 打开期间新建的消息不计 | sheet 常开 + 后台有消息写入（几乎不可达：sheet 是模态、且不跨重建） | 改 `collectAsState` 订阅 | 收益≈0，代价是新增长驻订阅；模态 sheet 生命周期短 |
| R4 | F-12 只按 `attachmentNames` 检索，未覆盖图片的 `imageUris` 文件名 | 用户按图片文件名搜历史输入 | 在 `ChatMessage` 侧加一个统一 `attachmentDisplayNames`（图 + 文件），两处共用 | 需要改 `ChatMessage`/序列化（数据层），不在本支允许触碰的文件面内 |
| R5 | F-07 的 `bottomReserve` 与 bar 是否渲染仍是两个独立谓词（`hasFloatingTools` vs 内部早退） | 未来任何让 bar 早退的改动 | 让 bar 把"我渲染了多少高度"回传给 `ChatScreen` | 本条修复后两者在所有已知输入上一致；为一个已消除的缺口加回传通道 = 装置超前于问题 |
| R6 | **形状钉的工作目录依赖**（本支实测踩到）：任何新增的"读源码做形状断言"测试，若假定 cwd = 仓库根，Gradle 下必红 | 下次有人新增形状钉时 | 默认照抄既有 30+ 个 `readRepoFile` 向上遍历先例；或在本仓加一个共享测试助手，消除这份复制 | 已在 `5945d8fa` 修掉本支的四处；剩下的是**仓级重复**，属"登记不修"（复制粘贴的先例在仓里已经存在且被广泛使用，抽公共助手会碰大量文件，超出本支白名单） |

---

## 8. 未验证 / 诚实边界

- **真机未验**：本支所有结论来自纯逻辑 JVM 跑 + 源码形状，没有一条来自真机。UI 上的实际观感（83 dp 空白是否真如推断、附件名预览在窄屏是否被截断成无信息）需要真机确认。
- **F-07 的因果链有一段是推断**：「bar 消失 + reserve 仍在」这一步是从 `ChatScreen.kt:2949-2960` 读出来的（`hasFloatingTools` 驱动），不是实测截图。
- **F-11 的"幽灵会话"来源**（/memory 或 /thinking 开关materialise 出的空会话）来自抽屉侧 KDoc 的既有描述，未在本支重新溯源；本支只保证"与抽屉同口径"这一条可验证的性质。
- **F-13 的量级**：报告说"thinking 模型多轮回传数万字符"，本支未做量化实测（需要一次真实 provider 会话日志）。修法本身与量级无关（漏项就是漏项）。
- **CI 只保证编译 + 单测 + 门**，不保证 UI 行为。

---

## 9. 收口

- 本地：43/43 绿 + 5 个负控臂（M0–M4）逐臂只红应红项；语法闸通过。
- 云端：run `40682587113` success，head_sha `5945d8fa28f9410a6e12213cdad6c016c08dc0c3` 与本地 HEAD 逐字符一致。

### 9.1 CI 附录

| run | head_sha | 结论 | 读数 |
|---|---|---|---|
| `40679992792` | `d84361ca…` | **failure** | 27 步中唯一非 success = `Run unit tests (full suite)`；`7370 tests completed, 6 failed`，6 条全在本次新增的 4 个测试类里，**同一个根因**：形状断言用相对路径 `src/android/app/src/main/…` 读源码，而 Gradle 跑 `:app:testReleaseUnitTest` 时 `user.dir` = `src/android/app` ⇒ `FileNotFoundException`（6 条 `FileNotFoundException`，非断言失败） |
| `40682587113` | `5945d8fa…` | **success** | head_sha 与本地 HEAD 逐字符一致；`git diff --name-only 5945d8fa HEAD -- . ':(exclude)fix-reports'` = **空** |

**第一次红的教训（值得留给所有形状钉）**：本仓的形状钉**不能**假定工作目录 = 仓库根。既有 30+ 个同类测试（`ScreenshotCaptureShapeTest` / `ConfigBackupEnvvarsAndOverlayTest` / `ChatBottomFollowTest` …）都自带一个 `readRepoFile(relative)`：**从 `user.dir` 向上遍历**，直到相对路径存在为止——同一份代码因此在 module dir、repo root、repo 任意子目录三种 cwd 下都成立。我第一版照抄了"仓库根相对路径"这个隐含前提，探针从仓库根跑永远是绿的，CI 立刻把它打红（6 条红全在形状钉，价值断言一条没红 ⇒ 本地绿 ≠ CI 绿，这是本支唯一一次真机外的"绿是假的"）。

修法（commit `5945d8fa`）：四个测试各加一个 `readRepoFile`（逐字照既有先例），形状断言全部改走它。修后本地**两种 cwd 各跑一遍**（repo root 与 `src/android/app`）都 43/43 绿，且基线臂 M0 在修复前源码树上仍红 5 条（形状钉依然有牙）。

**这算不算"CI 才暴露的缺陷"？** 算装置缺陷，不是生产缺陷：四条生产修复在两次 run 之间**一行未改**（`git diff d84361ca 5945d8fa -- src/android/app/src/main` = 空），红→绿全部由测试侧路径解析造成。这条也是本支的一条新登记项（见 R6）。

### 9.2 收口核对

- 三项核查：①`git diff --name-only bfb5b0e6 HEAD --stat` = 4 生产文件 + 4 测试文件 + 1 报告，`scripts/scan/**` / `scan.sh` / `test_scan.py` **零触碰**；②CI head_sha 与本地 HEAD 逐字符一致（`5945d8fa28f9410a6e12213cdad6c016c08dc0c3`）；③独立于 CI 的证据 = 本地 43/43 + 5 个负控臂逐臂只红应红项 + 语法闸。
- 停止节点达成：**分支 CI 绿**，停在 `fix/meta1028-c8-sheets-misc`，**不合 main**（合并起手权在用户）。
