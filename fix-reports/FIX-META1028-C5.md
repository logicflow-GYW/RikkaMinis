# FIX-META1028-C5 — 工具详情 / 思考块 3+1 条（F-10 · F-12 · F-14 · F-13）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 | `bfb5b0e6`（`git rev-parse --short HEAD`，任务书指定） |
| 分支 | `fix/meta1028-c5-tooldetail` |
| 来源 | 货架报告 **B04 F-10 / F-12**（`ui/chat/ChatToolDetailUI.kt`）+ **F-14**（`ui/chat/ChatAssistantMessageUI.kt`）+ **F-13**（low，同文件顺路捎带） |
| 改动面 | 生产 2 文件 +299/−82（`ChatToolDetailUI.kt` +219/−65、`ChatAssistantMessageUI.kt` +80/−17）· 测试 1 新文件 356 行（35 例） |
| 冻结面 | `scripts/scan/**`、`scan.sh`、`test_scan.py`、`strings.xml`（8 语言）**零触碰**；门数 **113 不变**、夹具 **349 不变**（CI 日志实测，见 §5.3） |
| 装置 | `/var/minis/shared/work/meta-audit-1028/probe-c5/`（在仓库树外，不入提交）：`extract.py`（从生产源码逐字取纯函数）· `run.sh`（真 kotlinc + 真 JUnit）· `mut.py`（5 变异臂）· `shape.py`（10 条形状断言 + 基线负控） |

## 2 缺陷与先证

四条都先回树核对报告行号与机制链，再动手。报告行号**全部可对上，0 漂移**；分级见各小节。

### 2.1 F-10 — 卡片地址栏与地球按钮解析出不同 URL · **成立，但任务书给的一行修法语义不对**

基线形态（两处并存）：

- 顶栏地球按钮（`ChatToolDetailUI.kt:169-190`）：`block.browserURL → 自身 args.url → 前序块`，前序块**带** `if (prev.toolName != "browser_use") return null`。
- 卡片正文地址栏（同文件 `:631-648`）：同样的三级回退，**没有** toolName 过滤。

所以「正文取到前序 web_fetch / MCP 的 url」这一点成立。

**但任务书写的「L635 后补 `if (prev.toolName != "browser_use") return null`」并不能"停在前序非浏览器块上"**：`firstNotNullOfOrNull` 把 lambda 的 `null` 当作**不匹配、继续扫描**，不是 `break`。顶栏那段代码的真实语义因此是「跳过非 browser_use 块，继续往更早找最后一个 browser_use」，不是「遇到非浏览器块就放弃」。我第一版测试按任务书字面语义写（期望 `""`），probe 当场跑红——才发现这层。按真实语义定案：

- 保留的判据是「**只有 browser_use 块是候选**」（web_fetch 的 url 永不采用）；
- 且**跨过中间的非浏览器块继续往前找**是对的：web_fetch 不会让内置浏览器导航，屏幕上仍是上一个 browser_use 的页面。

**这改变什么用户行为？** 打开一个 click/screenshot 类 `browser_use` 详情页时，若前一个工具是 `web_fetch` / MCP，地址栏不再给出一个浏览器从未加载过的 URL；地址栏与右上地球按钮从此由同一个函数产出，不可能再互相矛盾。

判级：**P1**（常用路径，界面自相矛盾，无数据损失）。

### 2.2 F-12 — 大输出在主线程全量渲染 · **成立（两处形态不同）**

1. `shell_execute` 分支（`:345` 附近）：`block.content` 整串交给 `remember { linkifyUrls(...) }` + 单个 `Text`，且外层是 `verticalScroll`（不虚拟化）。几十 KB 的构建日志 = 一次性跑正则 + 一次性测量整串，每次打开详情页都在组合线程上做。
2. `file_edit` 分支（`:517-547`）：`oldStr.lines().forEach { Text(...) }` + `newStr.lines().forEach { Text(...) }`，**每行一个 Text 节点且无上限**。整文件重写（几千行）＝几千个节点同时组合，卡顿；再大就是 ANR 面。

**这改变什么用户行为？** 打开大 shell 输出 / 大 diff 的详情页不再卡（输出按 ~200 行首窗惰性显示，可"Load more / Load all"；diff 每侧最多渲染 300 行并显示「… N more lines not shown」）。代价（见 §7 R2）：超长 shell 输出首屏只给前一段，看尾部要多点一次或滚到底触发自动展开。

判级：**P1**（可感知卡顿，且该路径是"查看命令输出"这一常用入口）。

### 2.3 F-14 — 暂停跟随后思考块窗口仍在丢头 · **成立**

`:1245` 的 `displayContent = remember(charCount) { ... full.substring(charCount - windowSize) ... }`：窗口恒锚在尾部。`:1293` 的 `if (userScrolledAway) return@collect` 只挡住了 `scrollTo`，**没有挡住窗口头部的推进**——每个 token 都把开头再切掉一段、整串重建，用户正在读的几行随之上移出视口。即"暂停"只暂停了滚动，没暂停内容。

**这改变什么用户行为？** 思考块流式输出时用户上滑回看，正在读的内容不再自己往上滑走；回到尾部后自动恢复跟随。

判级：**P1**（长思考块 + 回看是两个都常见的行为）。

### 2.4 F-13 — 流式参数预览把字面 `\n` 换成真换行 · **成立**（low，顺路捎带）

`:1171-1181` 两处（闭合 / 未闭合分支各一处）多趟 `.replace` 链：`\\n → \n` 先跑，于是 JSON 里表示"字面反斜杠 + n"的 `\\n`（sed/awk 脚本、正则、Kotlin/Java 源码片段、Windows 路径）被换成真换行；`\\` 那一趟又吃掉另一侧的反斜杠。多趟替换无法区分「模型写出的转义」与「上一趟自己造出来的转义」。

**同族另一处 `ChatViewModelUtils.unescapePartialJsonString`（`:215`）不动**：本支半径只到 `ChatToolDetailUI.kt`，那处已按任务书要求登记（见 §7 R5 的触发条件）。

**这改变什么用户行为？** 流式生成中，含字面 `\n` / `\t` 的工具参数预览不再被折行/错位。

判级：**P2→顺手修**（显示错误、不丢数据），但同文件同一函数、成本极低，符合「顺路成本≈0 允许捎带」。

## 3 修法

**F-10（单一真源）** — 新增 `internal fun resolveBrowserUseUrl(block, toolBlocks)` + `internal fun parseToolArgUrl(args)`；顶栏与正文两处都改为调用它（顶栏保留 `isBrowserTool` 早退）。正文侧顺带把原来每次重组都重算的 `indexOfFirst` + JSON 解析包进 `remember(block.id, block.browserURL, block.toolArgs, toolBlocks)`。

**F-12** —
- shell 分支改走同文件既有的 `LazyRevealToolText(bodyText, color = success, scrollState, linkify = true, linkColor = 0xFF7CC4FF)`。给 `LazyRevealToolText` 增加可选 `linkColor`（默认仍 `0xFF0A84FF`），保住黑色终端卡上原本的浅蓝链接色。
- file_edit 分支改 `buildDiffPreview(text, maxLines = 300, maxCharsPerLine = 2000)` → `DiffPreview(lines, hiddenLines)`：行数封顶、单行封顶（超出加 `…`）、**行数统计用 `indexOf` 走一遍不分配 `List<String>`**（几 MB 单串不会被 `lines()` 切成一堆字符串），被截掉的行用 `DiffPreviewFooter` 明示。上限只作用于**渲染**，`block.toolArgs` 里的原文不动。

**F-14** — 新增 `frozenWindowStart: Int?` 状态（`remember(block.id)`）：`userScrolledAway` 置真时捕获当时的 `tailWindowStart`，回到底部（`gap <= 4`）时释放；窗口切成两个纯函数 `thinkingTailWindowStart(content, windowSize)`（含 200 字符换行吸附）与 `thinkingWindowSlice(content, start, windowSize, frozen)`（冻结态按 `windowSize` 封顶，避免每 token 继续变长）。

**F-13** — 新增 `internal fun unescapeJsonStringBody(raw)`：单趟字符扫描，`\` + 下一字符成对消费，只解 `n/t/"//\\` 这五个编码器会产出的序列；其余转义（`\r`、`\uXXXX`）与结尾落单的 `\`（半截流）**原样透传**，与旧链行为一致（见 §7 R6）。两处调用点都换成它。

## 4 验证（本地三源）

装置原则：**探针只编译真源码**——`extract.py` 按声明名从生产文件逐字取 11 个声明（`extractPartialJsonString` / `unescapeJsonStringBody` / `resolveBrowserUseUrl` / `parseToolArgUrl` / `DIFF_PREVIEW_*` / `DiffPreview` / `buildDiffPreview` / `THINKING_WINDOW_NEWLINE_SLACK` / `thinkingTailWindowStart` / `thinkingWindowSlice`），并断言取出的文本逐字存在于源文件；`Stubs.kt` 只桩 `AssistantBlock`（字段名/类型与 `ChatModels.kt:195-211` 一致）。

| 证据 | 读数 |
|---|---|
| 真 JUnit（`ChatToolDetailUiLogicTest`，35 例，跑在真 kotlinc + 真 org.json 20231013 上） | **OK (35 tests)** |
| 形状断言（`shape.py`，现树） | **10/10 PASS** |
| 形状断言**负控**（同一套断言跑 `git show HEAD:file`） | **0/10**（10 条全红 = 断言有牙） |
| 变异臂 | 5/5 变红且**只红钉住它的用例**（下表） |

变异臂明细（`mut.py`，每臂都是"还原成修复前的真实拼写"，不是删掉新加的调用）：

| 臂 | 变异 | 变红用例 |
|---|---|---|
| m1_old_unescape | `unescapeJsonStringBody` 换回原多趟 `.replace` 链 | 4 例：字面 `\n`、字面 `\t`、`\\n` 解码、org.json 往返 |
| m2_no_prev_guard | 删掉 `if (prev.toolName != "browser_use") return null` | 3 例：前序非浏览器 url 不被采用 / 唯一候选也不采用 / 中间块被跳过而不被采用 |
| m3_no_freeze | 冻结态的窗口长度不再封顶 | 2 例：冻结窗口有界 / 释放冻结回尾部 |
| m4_no_diff_cap | 删掉 `if (lines.size >= maxLines) break` | 2 例：封顶并报余量 / 巨体有界 |
| m5_no_snap | 去掉换行吸附 | 1 例：吸附到附近换行 |

**测试自身纠错（值得记一笔）**：F-10 与 F-14 各有 1 条断言第一版写错（把 `firstNotNullOfOrNull` 当 `break`；把冻结窗口写成"逐字节不变"而非"头部不变"）。两条都是 probe 跑红后回读生产代码更正的，**不是靠改期望值让测试变绿**——修正理由写进了用例注释与 §2.1/§3 的 KDoc。

## 5 形状断言（Compose 侧的唯一可达证据）

`probe-c5/shape.py` 按 `// ── 分支名` 切出 shell / file_edit / browser / header 四个区域，对"渲染面"做文本级判据（这类缺陷在 JVM 里够不到）：

1. 顶栏走共享函数 · 2. 正文走共享函数 · 3. 全文件只剩 **1** 处 `prev.browserURL?.takeIf`（基线 2 处）· 4. shell 分支出现 `LazyRevealToolText(` · 5. shell 分支不再有 `linkifyUrls(` · 6. file_edit 出现 `buildDiffPreview(oldStr/newStr)` · 7. file_edit 不再有 `oldStr/newStr.lines().forEach` · 8. 出现 `frozenWindowStart` · 9. 不再有 `remember(charCount)` · 10. 存在释放分支 `frozenWindowStart = null`。

**负控是这套证据成立的前提**：同一套断言在基线上 0/10——若只报"现树 10/10"，无法排除"这些字符串在修复前就已存在"。

### 5.3 CI 判读（远端）

run **`40679556550`** / #2823 / job `122786476654` / attempt 1 / **success**（11:23:22 → 11:44:09，~20m47s）。

- **head_sha `975d4b8d64b5514525156751871dcb1179ba2dcd`** 与本地 `git rev-parse 975d4b8d` **逐字符一致**（远端 `origin/…` 尖 = 报告提交 `45f21e85`，符合"绿 sha 之后只有报告"）。
- 27 步里**唯一非 success = 滚动 tag + Publish**（main 门控 skip，分支构建的预期形态）。
- 日志 27 文件 / 3,020,627 B，**NUL 0**；`Summary: 113 passed, 0 failed` 出现在 `3_Run pre-build scan gate` 与总日志各 1 次（同一次输出被两处收录，不是跑了两次）；`ALL 349 CASES PASS` 同理 1 次；`^ e: ` **0**；`Function not implemented` **0**。
- **新测试类 35 例在 CI 上逐条 PASSED**（`ChatToolDetailUiLogicTest` 出现 70 次 = 35 次类名行 + 35 次结果行，`PASSED` 70 / `FAILED` 0）。含 FAILED 且不含 PASSED 的行 = 0（无伪装成红的 PASSED 用例名）。
- 单测真跑：`gradlew testReleaseUnitTest` → `compileReleaseUnitTestKotlin` → `testReleaseUnitTest`（11:29:14 → 11:35:02）。

## 6 诚实边界（未验）

- **真机未验**：本支全部改动在 Compose 渲染层，沙箱无 Android 运行时、单文件 kotlinc 也无法编译 Compose。§5 只证明"代码形态变到了预期形态"，不证明"渲染确实不卡 / 确实不滑走"。**行为面的最终判据是分支 CI（编译 + 全量单测 + 门）+ 真机观察。**
- 卡顿的量级（原 ANR 门槛在哪一行数）未测：本支只消除"无上限"这一结构性原因，没有做帧时间对照。
- 生产两文件未在本地编译（无 Compose classpath），本地只编译了 11 个纯函数 + 测试。
- 沙箱并发窗口记录：本支首次 `kotlinc` 运行撞到 S28 族的 `Function not implemented`（`kotlin-compiler-2.1.0.jar` 读属性失败），错开重跑即恢复，**不是**装置缺陷（判据：失败行全带该字样）。

## 7 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `ChatComposerWidgets.kt:702` 有同款前序 url 回退，同样**没有** toolName 过滤 | 下次改 composer 工具条，或用户报告 composer 卡片地址与本体内不一致 | 复用 `resolveBrowserUseUrl`（需提升为跨文件共享 or 移到 util）；先清 Compose 依赖 | 不同文件，本支半径外；该处目前无"地址自相矛盾"的报告 |
| R2 | shell 输出超过首窗（~200 行 / 10 KB）时，**live 尾部要一次点击或滚到底**才出现 | 用户报告"长命令输出看不到最新几行" | 给 `LazyRevealToolText` 增 `anchor = tail`（`isLive` 时从尾部起算，显式"Load earlier"） | 任务书指定形态就是前缀惰性显示；尾部锚是另一个组件语义，必须真机验才敢改 |
| R3 | `DiffPreviewFooter` 用英文直串（未本地化） | 下次动 `strings.xml` 的批量多语言改动 | 加 `chat_tool_diff_hidden_lines` 到 8 个语言文件 | 单支改 8 个语言文件 = 跨面，会与并行会话撞车；与同文件既有 `"streaming…"` / `"Running..."` 直串一致 |
| R4 | F-14 冻结最多滞后一个 token（`LaunchedEffect` 在组合后运行）；程序性滚动（展开动画改变 `maxValue`）不触发冻结 | 真机观察仍见轻微漂移 | 在 `snapshotFlow` collector 内同步置位并在置位时记录内容长度 | 需真机复现才能判断是否值得加复杂度 |
| R5 | `ChatViewModelUtils.unescapePartialJsonString:215` 仍是多趟 `.replace` 链 | 下次改该文件，或报告流式预览里另一处出现字面转义被吃 | 换成同一份单趟实现（提升到 util） | 任务书明确"你只改本文件那处，另一处已登记 backlog" |
| R6 | `unescapeJsonStringBody` 仍不解 `\r` / `\uXXXX`（与旧行为一致） | 真实流里出现 `\r`（CRLF 文本内容）且用户看到字面 `\r` | 扩 escape 表 + 补单测（`\r`、`\u`） | 半径纪律：本支只修"字面转义被吃掉"，扩转义面是另一个判据变更 |
| R7 | F-12 的 300 行 / 2000 字符上限是经验值，无真机标定 | 真机仍见 file_edit 打开卡顿 | 按实测帧时间调参，或改单块 `LazyRevealToolText` | 无 Android 运行时，调参只能靠猜；结构性原因（无上限）已消除 |

## 8 收口状态

- 本地：35 例 JUnit 绿 · 5/5 变异臂变红且各自只红钉住的用例 · 形状断言现树 10/10、基线 0/10。
- 分支：`fix/meta1028-c5-tooldetail`；生产 + 测试一个提交 **`975d4b8d`**（= CI 绿 sha），报告一个提交 **`45f21e85`**。
- 收口核验：`git diff --name-only 975d4b8d..HEAD -- . ':(exclude)fix-reports'` = **空**；工作树脏 0。
- **停止节点达成：分支 CI 绿 + head_sha 逐字符一致**；**不合 main**（合并起手权在用户）。
