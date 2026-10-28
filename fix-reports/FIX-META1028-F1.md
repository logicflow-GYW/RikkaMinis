# FIX-META1028-F1 — B02 渲染族两条（F-07 + F-11）

分支 `fix/meta1028-f1-b02-render`，基线 main `ce5c5c5b`。
来源货架 B02（`/var/minis/mounts/背景包/reports/B02-20261028.md`，原文 `reports/Report__4_.md`）。
只覆盖 F-07 与 F-11；B02 其余条目已由波2 C3/C4/C5 修掉或登记，本支零触碰。

改动面（5 文件 = 生产 2 + 新测试 1 + 既有测试 1 + 本报告）：

```
src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt   +19
src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatToolDetailUI.kt        +58 −6
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ToolDetailAutoRevealTest.kt 新文件
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/InlineMathExtractionTest.kt +34
```

`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰；`rg -l 'ChatToolDetailUI|StreamingMarkdownText' scripts/` 命中的 5 个文件里 4 道门逐一跑过（第 5 个是 `test_scan.py` 本身，禁跑全量）：

| 门 | 改后读数 |
|---|---|
| `katex_bridge_arity_check.py` | rc=0（改前同样 rc=0，未被我推红） |
| `chat_ui_interaction_check.py` | `CHAT-UI-INTERACTION GATE OK` |
| `stale_closure_guard.py` | 三段全绿（**含「no long-lived effect reads a plain parameter」** —— 见 §3 的次生风险） |
| `file_uri_parse_guard_check.py` | `18 passed, 0 failed` / `GATE=GREEN` |
| `mediaplayer_release_guard.py` | OK |

---

## 1 背景：报告说的是什么，真码是什么

报告基线 `47c4bf4b`，本支基线 `ce5c5c5b`，行号全部漂移，以下按**函数语义**重新取证。

**F-07**（`collectInlineMathLatex` 与 `parseInline` 对行内代码的处理不对称）
报告声称同一行里 `` `$USER_DIR` `` + `$x + 1 > 0$` 会让收集器配出伪公式、漏掉真公式。
**成立**，且机制与报告一致。补一条报告没讲的精确后果：Compose 在 `inlineContent` 缺条目时**不崩、不空白**，而是回落到 `appendInlineContent` 的 alternateText —— 也就是用户看到的是字面 `$x + 1 > 0$`（公式退化成源码），同时 KaTeX 为一个屏幕上根本不存在的公式白渲染一张图。

**F-11**（工具详情页翻页继承状态 + 懒加载门未滚动即穿透）
两个半条**都成立**，但报告的两处表述需要校正：

1. 「切换工具保留旧偏移」成立。补一条报告没提的表现：新工具内容**比旧偏移短**时 `ScrollState` 会把 value 夹到新内容的 maxValue，所以那种情况下用户看到的是**新工具的底部**，不是中下部 —— 同一个缺陷的两种相貌。
2. 「`max in 1..600` 时恒 true」成立，但「立即把懒加载分批穿透」应精确为：**逐批穿透**，每批之后 `max` 长大，直到某一批后 `max > 600` 才停下或全部展开。也就是说短-中输出（≤ 约 600px 溢出）会一次性穿到底，中长输出会多穿几批才停 —— 都不是「等用户滚」，但严重度取决于 `max` 的增长速度。

---

## 2 先证（改前，全部是红读数）

装置在 `/var/minis/shared/work/meta-audit-1028/probe-f1/`，两条硬纪律：切片**逐字 verbatim**（`extract.py` / `reveal_extract.py` 回读源码断言 `body in source`，不通过就退出），期望值**写死字面量**。

### 2.1 F-07 直接证据：同一组断言跑改前源码

切片取 `git show HEAD:…/StreamingMarkdownText.kt`（改前），13 条断言：

```
FAIL A mix code+formula  got=[USER_DIR` 是否满足 ]  want=[x + 1 > 0]
PASS A2 formula then code / A3 code-only / A4 unclosed backtick / A5 plain formula
PASS B1..B8（既有套件的回归面：货币、转义 $、$$、\(\)、换行边界、多跨度、表格竖线伪迹）
FAILURES=1
```

改后同一组断言 `ALL_MATCH`（13/13）。

### 2.2 F-11 直接证据：旧谓词 vs 新谓词，同一组 12 条用例

`RevealProbe.kt` 里 `legacyShouldAutoRevealMore` 是改动前那行内联表达式的逐字复刻
（`max > 0 && max != Int.MAX_VALUE && value >= max - 600`），新谓词由 `reveal_extract.py` 从源码切片：

```
--- legacy predicate (pre-fix) ---
FAIL legacy overflow 1px, no scroll    value=0 max=1    got=true want=false
FAIL legacy overflow 100px, no scroll  value=0 max=100  got=true want=false
FAIL legacy overflow 600px, no scroll  value=0 max=600  got=true want=false
legacy mismatch count = 3
--- shouldAutoRevealMore ---
12/12 PASS → ALL_MATCH
```

### 2.3 真实单测类跑在改前源码上（最强的「红」）

不是复刻：把真实测试文件（`InlineMathExtractionTest.kt` + 新 `ToolDetailAutoRevealTest.kt`）**原样**编译到「改前切片 + 改后 UI 切片」上，用 JUnitCore 真跑：

```
FAILURE inline code dollars do not swallow the following formula :: expected:<[x + 1 > 0]> but was:<[USER_DIR` 是否满足 ]>
FAILURE two code spans around one formula :: expected:<[y]> but was:<[A` 与 `, y]>
TESTS=61 FAILURES=2
```

第二条失败还暴露了报告没写的一面：伪公式可以**不止一个**（`A` 与 `` ` `` 之间那一段也会被当成公式）。

### 2.4 F-11 第 1 半条为什么是「无键 remember」而不是别的原因

`rg 'rememberScrollState\(\)|outputScrollState' ChatToolDetailUI.kt`：`rememberScrollState()` 全文件**只有 1 处**（L243，`when` 之外，被 4 个分支的 `verticalScroll` 共用），没有第二处带键的；翻页只改 `currentIdx`（L1064/L1110），`block` 由 `currentIdx` 派生 ⇒ 状态实例与偏移都不会重置。`rg 'scrollTo|animateScroll'` 命中 0 ⇒ 该 state 从无程序化滚动，这是 §3 里「`value != 0` 可当作『用户滚过』」的前提。

`copyDone`（L159，无键）同因：它活在**所有工具共享的表头条**里，`LaunchedEffect(copyDone)` 的 2 秒复位窗口内翻页，✓ 会显示在下一个工具的复制按钮上。

---

## 3 修法（两处都是最小改法）

**F-07** —— 给 `collectInlineMathLatex` 加行内代码分支，与 `parseInline` / `appendRecursive` **共用同一个 `findInlineCodeClose`**（两侧原本就有这个 helper，只是收集器没用）：

```kotlin
if (c == '`') {
    val end = findInlineCodeClose(text, i + 1)
    if (end != -1) { i = end + 1; continue }
}
```

- 不抽新 helper：闭合判定本身就是共享的 `findInlineCodeClose`，再包一层只是多一跳（阶梯第 7 级）。
- 位置在 `\` 转义分支**之后**（`\`` 仍然按转义处理，与 `parseInline` 的分支顺序一致）、`$` 分支**之前**（代码段内的 `$` 不再进公式扫描）。
- 未闭合反引号**不**吞掉整行：`parseInline` 遇到无闭合时把反引号当字面量、`i++` 继续，所以这里也必须 fall through —— 这条写成了变异臂 A2（吞整行）的判据。

**F-11 第 1 半条** —— 两处 `remember` 加 `block.id` 键；`rememberScrollState()` 没有 key 参数，故显式构造：

```kotlin
var copyDone by remember(block.id) { mutableStateOf(false) }
val outputScrollState = remember(block.id) { androidx.compose.foundation.ScrollState(0) }
```

`block.id` 的稳定性是**查过**的：tool block 的 `id = toolUseId`（`AgentLoopEngine` 用 `none { it.id == toolUseId }` 去重），流式增长只换 content 不换 id，不会每帧重置。

**F-11 第 2 半条** —— 谓词抽成 `internal fun shouldAutoRevealMore(scrollValue, maxValue, nearBottomPx = LAZY_TOOL_NEAR_BOTTOM_PX)`，加「真的滚过」门：

```kotlin
if (maxValue <= 0 || maxValue == Int.MAX_VALUE) return false   // 内容不满一屏 / Compose 未测量
if (scrollValue <= 0) return false                             // 首帧 = 没滚过（全文件无程序化滚动）
return scrollValue >= maxValue - nearBottomPx
```

**改法自带的次生风险，已一并修掉**：`LazyRevealToolText` 里 `derivedStateOf` 的 `remember` 原本无键，而它捕获的是 `scrollState` **参数**。第 1 半条改成「每块新 ScrollState」之后，无键 remember 会一直读**上一个块**的实例（旧 value + 旧 max）。所以改成 `remember(scrollState) { derivedStateOf { … } }`，并写成形状判据（变异臂 A7）。这正是本仓「`stale_closure_guard`」门盯的那类问题，门在本树上仍然绿。

---

## 4 证据（改后）

| 项 | 读数 |
|---|---|
| F-07 切片断言（13 条） | `ALL_MATCH` |
| F-11 旧谓词 vs 新谓词（12 条） | legacy 3 mismatch（缺陷复现）；新谓词 12/12 PASS |
| **真实单测类 JUnitCore** | `TESTS=61 FAILURES=0`（`InlineMathExtractionTest` 全量 + 新增 5 例 + 新 `ToolDetailAutoRevealTest` 12 例 + `katexInlineTagFor`/`inlineMathSizeEm` 既有断言） |
| 源码形状判据 | **18 条全 OK**（`shape_check.py`；剥注释后再判，防止注释里的字样满足判据） |
| 变异臂 | **9/9 MATCH**（见下表） |
| 括号/引号配平 | 两个生产文件改前改后计数**完全相同**（`ChatToolDetailUI` (0,−1) / `StreamingMarkdownText` (1,7)，残差是既有注释里的符号） |
| 涉及门 | 4 道全绿（§0 表） |

变异臂（把缺陷塞回去，判据必须变红；不变红 = 判据是装饰）：

| 臂 | 塞回什么 | 谁红了 |
|---|---|---|
| A1 | 反引号分支短路 `if (false && c == '`')` | inline 探针（`A mix code+formula`） |
| A2 | 未闭合反引号吞整行 | inline 探针（`A4`）+ 形状判据 `backtick branch is guarded` |
| A3 | 只跳过反引号本身（`i += 1`） | inline 探针（`A mix code+formula`） |
| A4 | 去掉 `scrollValue <= 0` 门 | reveal 探针（3 条首帧用例）+ 形状判据 |
| A5 | `>=` 改成 `>`（阈值差 1px） | reveal 探针（`exactly at threshold`） |
| A6 | ScrollState 去掉 `block.id` 键 | 形状判据 `output scroll state is keyed` |
| A7 | derivedStateOf 去掉 `scrollState` 键 | 形状判据 `derivedStateOf remember keyed on scrollState` |
| A8 | 退回行内老算式 | 形状判据 `auto-reveal gate routed through the pure predicate` |
| A9 | copyDone 去掉 `block.id` 键 | 形状判据 `copy flag keyed on the block` |

---

## 5 CI

- 分支 `fix/meta1028-f1-b02-render`，基线 main `ce5c5c5b`
- 代码提交（= 绿 sha）`78970708a612a648aef435ad43729f961eaf45ae`
- dispatch `build-apk.yml` @ 该 ref（分支 push 不触发 CI，必须手动 dispatch）
- **run `40726609437` conclusion=success**，`head_sha` 与本地 tip **逐字符一致**，job `build` 21m24s（16:30:24Z → 16:51:48Z）
- 27 步唯一非 success = `Move the rolling tag android-latest to this commit` + `Publish to Releases`，两者均为 main 门控 **skipped**（分支上的正常形态）

判读日志 `logs-f1.zip`（686,907 B → 解压 3,152,125 B / **NUL 0** / 27 个文件）：

| 判据 | 读数 |
|---|---|
| `Summary: 113 passed, 0 failed` | ×2（分片各 1） |
| `ALL 349 CASES PASS` | ×2 |
| `^e: ` 编译错误 | **0** |
| 含 `FAILED` 的行 | 12 条，逐条确认**全是 PASSED 用例名的子串**（`phase FAILED maps to Failed PASSED` 等，判读陷阱老账） |
| `ToolDetailAutoRevealTest` | 12 个用例 ×2 分片全 PASSED |
| 新增 5 条 `InlineMathExtractionTest` 用例 | 各 ×2 PASSED |

停止节点达成：分支 CI 绿。**未合并 main**（合并起手权在用户）。


---

## 6 残留（登记，不修）

- **R1** `ui/markdown/MarkdownParser.kt::extractMath`（喂 `ui/markdown/MarkdownText.kt`，用于技能文档预览等面）是**同一族的不对称**：`$…$` 分支不跳行内代码段。与 F-07 同根但因渲染面不同（chat 走 `StreamingMarkdownText`），按半径纪律只登记。触发条件：技能文档 / 预览里出现「代码含 `$` + 同行公式」被渲染成字面公式。
- **R2** `remember(block.id) { ScrollState(0) }` 丢掉了 `rememberScrollState` 自带的 `rememberSaveable` 位置恢复：旋转屏幕 / 进程重建后回到顶部，而不是回到上次阅读位置。刻意取舍（翻页重置是本次要的行为，且这个 sheet 的阅读位置价值低于「每块从顶部开始」的确定性）。
- **R3** `revealed` 的键仍是 `bodyText`（未改）：两个不同工具 block 的正文**完全相同**时，翻页会继承已展开进度（无害，且正文相同本来就是同一段内容）。
- **R4** 未做真机验证：Compose 渲染路径在沙箱里只能靠「CI 编译 + 纯函数单测 + 源码形状」，触摸行为（滚动偏移是否真的重置、懒加载是否真的等到滚动）需要真机或仪器化测试确认。
- **R5** F-07 的第三个扫描站点（`appendRecursive`，强调内的行内解析）**本来就**有反引号分支 —— 已核实为「无需改」，不是遗漏。

---

## 7 复现

```sh
cd /var/minis/shared/work/meta-audit-1028/probe-f1

# 1) 切片 + 断言（F-07）：改前红 / 改后绿
git -C ../wt-f1 show HEAD:src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt > /tmp/pre.kt
python3 extract.py /tmp/pre.kt pre/Extracted.kt          # 逐字校验
cp pre/Extracted.kt InlineMathProbe.kt build-pre/ && (cd build-pre && kotlinc -cp $STDLIB Extracted.kt InlineMathProbe.kt -d out && java -cp out:$STDLIB InlineMathProbeKt)

# 2) 真实单测类（不是复刻）跑在改前 / 改后源码上
python3 junit_run.py /var/minis/shared/work/meta-audit-1028/wt-f1 junit-post   # TESTS=61 FAILURES=0

# 3) 形状判据 + 变异臂
python3 shape_check.py /var/minis/shared/work/meta-audit-1028/wt-f1            # 18 条全 OK
python3 arms.py /var/minis/shared/work/meta-audit-1028/wt-f1                   # 9/9 MATCH
```

（`$STDLIB=/var/minis/shared/work/jvm-libs/kotlin-stdlib-2.1.0.jar`；kotlinc 无 `-include-runtime` 可用——stdlib 不在 `-kotlin-home/lib` 里，必须显式 `-cp`。）

## 8 这改变什么用户行为

- **F-07**：聊天里「行内代码含 `$`」与「真公式」同行的回复，真公式**从字面 `$x+1>0$` 变回渲染出来的公式**；同时不再为一个屏幕上不存在的伪公式白渲染一张 KaTeX 图（每次重排/滚动都要重画的那个 WebView 任务）。**触发面**：任何讲 shell / 环境变量的 AI 回复（`` `$PATH` ``、`` `$USER_DIR` `` 几乎必现）。
- **F-11**：① 用 `|<` / `>|` 翻到下一个工具时，从**顶部**开始读，而不是停在上一个工具几千像素处（或新工具的底部）；② 输出只超出屏幕一点点时，「加载更多 / 全部加载」不再一到就自己穿完，滚到底才自动续；③ 点完复制后 2 秒内翻页，✓ 不会挂到下一个工具的按钮上。
