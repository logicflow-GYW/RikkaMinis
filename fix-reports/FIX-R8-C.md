# FIX-R8-C — 聊天 UI 写路径（C-F01 / C-F03 / C-F04 / C-F05 / C-F07）

## 1 覆盖 ID

| 项 | 值 |
|---|---|
| 来源报告 | `/var/minis/shared/work/meta-audit/reports/r8-gap1113/C-ui-chat.md` |
| 任务书 | `/var/minis/shared/work/r8-1114/fix-tasks/FIX-C.md` |
| 本支覆盖 | **C-F01 · C-F03 · C-F04 · C-F05 · C-F07**（5/5 全部动手；C-F07 端到端可达性被证伪，见 §2.5） |
| worktree | `/var/minis/shared/work/r8-1114/c-chat-ui` |
| 分支 | `fix/r8-1114-c-chat-ui` |
| 基线 | `c0391a2`（未 rebase、未 merge main） |
| 提交序列 | `36b5728` C-F01 · `763572c` C-F03 · `264e30d` C-F04 · `1eb4d66` C-F05 · `b194a79` C-F07 |
| 分支尖端 | `b194a798cf3adf7573872add897572fc1eb85af0` |
| 改动量 | 8 文件 **+202 / −18**（生产 5 · 测试 3；新增测试文件 3） |
| 禁碰项 | `scripts/scan/**`、`scan.sh`、`scan_blocks.sh`、`test_scan.py` **零触碰**；A/B 支独占文件零触碰 |

**改动文件清单**（`git diff --stat c0391a2..HEAD`）：

```
 ui/chat/ChatViewModelMentionExt.kt      | 16 +++++-
 ui/chat/MarkdownClipboard.kt            | 23 +++++++--
 ui/chat/ParallelToolBatchBlocks.kt      | 12 +++--
 ui/chat/PastedTextBuffer.kt             |  8 ++-
 ui/chat/ToolCallResiduePolicy.kt        | 60 ++++++++++++++++++----
 ui/chat/ParallelToolBatchBlocksTest.kt  | 41 +++++++++++++++
 ui/chat/ToolCallResiduePolicyTest.kt    | 60 ++++++++++++++++++++++
 + 新增 ChatViewModelMentionSelectTest.kt / MarkdownClipboardInlineTest.kt /
   PastedTextBufferMarkerTest.kt
```

---

## 2 逐条先证结论

### 2.1 C-F01 · P1 · 正文里的 `<` 吞掉整段 — **成立，且比报告多一层**

**先证（PRE 臂实测，基线 `c0391a2`）**：

```
a bare less-than in prose does not swallow the following call markup
  expected:<a [< 0 and later ]> but was:<a []>
a bare less-than in prose keeps the sentence when the residue has a closer
  expected:<when a [< 0 the rule holds.\n]> but was:<when a [\n<parameter name="command">ls</parameter>\n</invoke>]>
```

第二条是这条缺陷最完整的样子：**用户那句 "when a < 0 the rule holds." 整段消失，而工具参数 `<parameter name="command">ls</parameter>` 反而留在了气泡里**。机制与报告一致：`gt` 取「任意位置的下一个 `>`」，`nameValueOf` 在粘合体里找到后文标签自己的 `name="shell_execute"`，残留被判在**正文 `<`** 处，`stripResidue` 从该字符删到标签末尾。

**报告未点出的第二层（我实测补的）**：报告说「第 175 行用的是位置 `i` 处的反引号奇偶性」——实际更严重：旧实现算完 `gt` 后**直接 `i = gt + 1` 跳过了中间全部字符**，跳过的区间里的反引号直到下一轮才被计入，所以「正文 `<` 之后才开的围栏」永远算不进奇偶性。PRE 臂上这条独立成一条红：

```
a fenced example after a prose less-than stays untouched
  java.lang.AssertionError            (hasResidue 判 true —— 围栏内的示例被当成真调用)
```

**修法**：`gt` 的搜索改为**逐字符前进**，遇到 `>` 接受、遇到新的 `<` 或反引号即**放弃该候选**：

- 遇 `<`：把 `i` 移到那个 `<` 继续扫（正文 `<` 只是普通字符，真正的标签从那里开始）；
- 遇 `` ` ``：`backTicks++` 并把 `i` 移到反引号之后继续（**关键**：反引号必须在这里被消费，否则奇偶性会滞留，围栏示例会被误判为活调用）；
- 都没遇到就走到文本末尾 → `return null`。

**没有**改成「只有白名单标签名才删」——那会漏掉真残留（drift 名字如 `shellexecute` 正是要抓的）。名字解析规则（`neareastToolName`）一字未动。

**既有臂全绿（PRE 与 POST 都绿，只加不删）**：DSML 信封 6 例、反引号内标签 1 例、围栏内标签 1 例、嵌套/漂移名字、30k 扫描成本门 —— 全部保留且通过。

**用户可见变化**：模型回复里出现数学/逻辑小于号时，那句话不再被后文的工具调用复述吞掉。

---

### 2.2 C-F03 · P1（实为 latent）· 并发工具块按位置比对 — **成立，但当前不可达**

**先证（PRE 臂）**：

```
a call that inserts a block cannot roll a sibling's edit back
  expected:<[from-1]> but was:<[]>
a call that removes a block cannot roll a sibling's edit back
  expected:<[from-1]> but was:<[]>
```

机制确认：`baseline.getOrNull(i)` 按**位置**取基线，`indexById[edited.id]` 按 **id** 取写入槽位——同一函数里两套口径。任一副本插入/删除一个块后，后续块与错位的基线比较必然不等 ⇒ 未修改的块被当成「已改」⇒ 旧快照写回覆盖兄弟的真实修改。KDoc 第 54-57 行明确声称 "Blocks are matched by id, not by index"，**代码与自己的 KDoc 相反**（典型的注释声称 vs 实现相反）。

**可达性核查（我做了，结论是 latent）**：
- 唯一调用点 `AgentLoopEngine.kt:4282`；
- 并行分支只对 `ToolConcurrencyPolicy.PARALLEL_SAFE_TOOLS` 生效，该集合当前是 **`{file_read, read_image}`**（`tools/ToolConcurrencyPolicy.kt:47`）；
- 两者都**不往 `callBlocks` 增删块**（唯一的块写入是 `executeShellCommand` 的 `onBlockUpdate`，而 `shell_execute` 是 SERIAL_BARRIER）。

⇒ **当前生产路径触发不到**。但这是「假保护」族：KDoc 声称的不变量不存在，白名单一旦扩到写块工具就立刻变成真实丢失。修法与 KDoc 对齐，成本 4 行。**P 级按 P1 的「假保护」定性，但触发证据一栏如实写 latent**（按 GLOBAL 修复门纪律，这类本应登记；因与 KDoc 直接矛盾且同批任务书点名，就地修掉）。

**修法**：基线也走 `indexById`，比较与写入锚定同一个 id。

---

### 2.3 C-F04 · P1 · `@` 提及越界崩溃 — **成立，实测抛异常**

**先证（PRE 臂，真生产 `selectMention` + ViewModel 替身）**：

```
PRE_ARM_OBSERVED=java.lang.StringIndexOutOfBoundsException: begin 4, end 3, length 3
```

`anchor == currentText.length` 通过 `anchor > currentText.length` 守卫，随后 `substring(anchor + 1)` 越界。可达序列很普通：菜单在字段以 `@` 结尾时打开（anchor = 末位），文本随后缩短到恰好等于 anchor（退格 / 剪切 / 外部 ReplaceText），用户点行或按回车。

**修法（含任务书允许的折衷，如实说明）**：`ChatViewModel` 有 4800+ 行、需要 Context / 仓库 / 活跃协程作用域，**JVM 单测里造不出实例**（`viewModelScope` + `Dispatchers.Main` + Room 依赖）。按任务书 §C-F04 的第二选项，把守卫抽成纯函数：

```kotlin
internal fun mentionSpanIsValid(anchor: Int, textLength: Int): Boolean =
    anchor >= 0 && anchor < textLength      // 上界 EXCLUSIVE
```

`selectMention` 改为调用它。测试做**两件事**：
1. 边界真值表（`anchor == length` 拒绝、`length - 1` 接受、负数/越界拒绝）；
2. **源码形状断言**：`selectMention` 函数体内必须出现 `mentionSpanIsValid(anchor, currentText.length)`，且不得再出现 `anchor > currentText.length`。

第 2 条是必需的：**一个测过但没接线的守卫，正是本批要消灭的那类假保护**。

---

### 2.4 C-F05 · P1 · 复制代码时 `__init__` 被当粗体剥除 — **成立**

**先证（PRE 臂，真 `markdownToPlainText`）**：

```
a dunder name inside inline code survives the copy
  expected:<call [__init__] now> but was:<call [init] now>
an index-and-call expression inside inline code survives the copy
  expected:<use fn[[0](x)] here> but was:<use fn[0] here>
multiplication stars inside inline code survive the copy
  expected:<compute a[*b*]c> but was:<compute a[b]c>
a snake_case identifier inside inline code keeps its underscores
  expected:<set max[_output_]tokens> but was:<set max[output]tokens>
an image-looking sequence inside inline code survives
  expected:<literal [![alt](url)] text> but was:<literal [alt] text>
```

四类破坏全部复现（粗体下划线 / 链接正则 / 斜体星号 / 斜体下划线），且 `![alt](url)` 在代码里也被当图片剥掉。

**修法**：与同文件 `inlineToHtml` 同法——先用 `INLINE_CODE` 把 code span 抽成占位符（复用既有 `CODE_PLACEHOLDER` / `CODE_SPAN`），跑完全部内联正则后再**逐字还原**（沿用 `[audit-0917]` 的 `getOrNull` 守卫，正文里出现字面量 `CODE0CODE` 也不会抛异常或替换错）。

**附带取证（NUL 残留）**：占位符含 `\u0000`，所以专门跑了一次真实输出检查——

```
nul=false out=[call __init__ now]
nul=false out=[two a and b spans]
nul=false out=[very x_y indeed]
nul=false out=[CODE0CODE literal in prose]
nul=false out=[link and code and bold]
```

零残留，且字面量占位符原样保留。

**用户可见变化**：复制回复粘贴到终端/编辑器时，`__init__` / `max_output_tokens` / `fn[0](x)` 不再被破坏；`**bold**`、`[text](url)`、`~~strike~~` 仍照旧剥成纯文本。

---

### 2.5 C-F07 · P2 · 分离式粘贴 + 手写占位符 — **函数级成立，端到端被证伪**

**函数级先证（PRE 臂）**：

```
a detached block id does not make a hand-typed marker expandable
  expected:<[[Pasted#1]]> but was:<[]>
every id the guard accepts is one the expander resolves
  guard let #1 through but the expander skipped it expected:<[1]> but was:<[]>
```

口径确实不一致：`unexpandedPasteMarkers` 的 `known` 含**所有** buffer 条目 id，`expandPastePlaceholders` 只认 `inlineMarker == true`。

**★ 端到端可达性核查 —— 结论：报告描述的路径不成立（证伪）**

| 证据 | 内容 |
|---|---|
| `unexpandedPasteMarkers` 在生产代码的**全部**调用点 | `ChatViewModel.kt:997`（`rg` 全树仅此一处，另有定义处） |
| 该调用点上下文 | `adoptRestoredDraftPasteMarkers(restored)`，由 `init { }` 的草稿恢复分支调用（`:2813`），实参 `_pastedTexts.value` |
| 该时刻 buffer 状态 | `_pastedTexts` 在 `:941` 初始化为 `emptyList()`，`init` 在 `:2796` ⇒ **恢复时 buffer 恒为空** |
| 发送时真正的守卫 | `ChatInputArea.kt:487 / :1479` → `pasteSendBlockedBy` → `blockingPasteMarkers` → 读的是 `orphanPasteMarkers`（另一个字段，只由恢复路径填充） |

⇒ 报告写的「用户在空框粘贴长文本 → 手写 `[Pasted#1]` → 阻断门放行 → 发送时展开跳过」这条链**当前不成立**：那个守卫不读这个函数，而这个函数被调用的唯一时刻 buffer 是空的。

**仍然修了**（理由要写清）：KDoc 自述 "The marker literals in [text] that this session **cannot expand**"，而实现把不可展开的分离式条目算成 known —— **注释与实现相反**，且这正是「未来某个调用点（或分离式条目在 buffer 里存活）会把它变成静默数据丢失」的种子。修法 1 行（`known` 只取 `inlineMarker`），让 fail-closed 侧真正 fail-closed。

**P 级**：按任务书给的 P2 保持 P2，并在报告与提交说明里**显式写明「函数级不一致已修，端到端不可达」**，不冒充 P1 已闭环。

---

## 3 修法汇总（改了什么用户行为）

| 条 | 用户可见变化 | 性质 |
|---|---|---|
| C-F01 | 正文含 `a < 0` 的回复不再被后文的工具调用复述吞掉整句 | **P1 真修** |
| C-F03 | 无（当前白名单下不可达）；防的是白名单扩展后兄弟副本的真实修改被回滚 | latent 假保护 |
| C-F04 | 点选 `@` 文件时不再因 anchor 越界崩主线程 | **P1 真修** |
| C-F05 | 复制为纯文本时行内代码逐字保留（`__init__` / `snake_case` / `fn[0](x)` / `a*b*c`） | **P1 真修** |
| C-F07 | 无（端到端不可达）；防的是守卫与展开器口径分叉 | P2 契约对齐 |

**净代码量**：生产 +115 / −18，测试 +87 / 0（新增 3 个测试文件另计）。停在第 7 级（最小实现）：五条都在既有函数体内就地修，没有新增生产类、没有新依赖、没有配置项。唯一新增的顶层符号是 `mentionSpanIsValid`（任务书 §C-F04 明确允许的折衷）。

---

## 4 证据

### 4.1 装置（双臂：真生产源码 + 真仓库测试文件）

位置 `/var/minis/shared/work/r8-1114/c-jvm/`（worktree 之外，`probe/` 在 `.gitignore:141`，不污染提交）：

| 组成 | 内容 |
|---|---|
| 生产源 | **逐字真文件**，PRE 臂用 `git show c0391a2:<path>`，POST 臂用工作树 |
| 测试源 | **真仓库测试文件**（新增 3 个 + 扩写 2 个 + 既有 `PasteTextBufferTest` / `PasteFoldWiringTest`） |
| 桩 | 6 个最小桩（`Log` / `Uri` / `Context`+`ClipboardManager`+`ClipData` / `@Immutable` / `TextRange` / `TextFieldValue`）+ 2 个**镜像**（`AssistantBlock`/`ToolBlockStatus`、`FileMentionIndex.Entry`）+ `ChatViewModel` 测试替身 |
| 镜像守卫 | `mirror_check.py` 从真文件重新抽取声明逐字段对拍 → `MIRROR_CHECK=PASS`（11/11 字段、枚举逐项、Entry 5/5），防「桩太窄制造假绿」 |
| 编译 | 单次 `kotlinc 2.1.0`（stub + prod + test 一次传入，`sandbox-jvm-testing` §1），`nice -n 10` |
| 运行 | `JUnitCore`，classpath 显式含 `kotlin-stdlib` |

**为什么 PRE 臂少 5 例**：`ChatViewModelMentionSelectTest` 引用 `mentionSpanIsValid`（修复才引入的符号），对基线无法编译。C-F04 的基线证据改用**行为探针** `MentionBoundaryCrashProbe`（真 `selectMention` + ViewModel 替身，`-Darm=pre` 断言必须抛异常）。这是刻意的：让 PRE 臂证明「缺陷真的会发生」，而不是「符号不存在」。

### 4.2 双臂读数

| 臂 | 用例数 | 结果 | 说明 |
|---|---|---|---|
| **PRE**（基线 `c0391a2`） | 107 | **18 failed** | 18 条全部是本次新增断言；**既有臂 89 条全绿**（含 DSML 6 例、反引号/围栏内标签、嵌套标签、30k 成本门、折叠/展开全表、粘贴接线 11 例） |
| **POST**（工作树） | 112 | **OK (112 tests)** | 107 + `ChatViewModelMentionSelectTest` 的 5 例 |

PRE 臂 18 条红的分布（逐条可复跑）：C-F01 5 条 · C-F03 2 条 · C-F05 8 条 · C-F07 3 条 · C-F04 由探针的 `PRE_ARM_OBSERVED` 行取证。

### 4.3 变异负控（删掉修复，对应断言必须红）

`mutate.py` 逐条把修复还原成修复前形状，`mutate.sh` 编译并跑全量 112 例：

| 变异 | 还原成 | 变红用例数 | 变红的用例 |
|---|---|---|---|
| **m1** C-F01 | 接受第一个 `>`（去掉 `<`/反引号否决） | **5** | 5 条 `less-than` 相关用例 |
| **m2** C-F03 | `baseline.getOrNull(i)` | **2** | 插入/删除两条 |
| **m3** C-F04 | `anchor <= textLength` | **3** | 崩溃探针 + 边界 2 条 |
| **m4** C-F05 | 只去反引号、不占位 | **8** | 8 条行内代码用例 |
| **m5** C-F07 | `known` 含全部条目 | **3** | 3 条分离式用例 |

**零串扰**：每个变异只命中自己那条的用例，其余 100+ 例保持绿 —— 说明断言钉在各自的修复点上，不是「一起红」的松散断言。

### 4.4 次生问题专项核查

| 核查项 | 手法 | 结论 |
|---|---|---|
| C-F01 性能形状（热路径，每个 delta 一次扫描） | 60k 字符实测 | `codeHeavy60k` **10.4 ms/轮**、`proseLessThan60k` **6.2 ms/轮**（含 has+strip 两遍）；线性单遍，无回退成二次 |
| C-F01 扫描语义 | 既有 12 条臂 + 新增 6 条 | DSML 整段吞、围栏内跳过、反引号奇偶、嵌套/漂移名字全部保持 |
| C-F05 占位符泄漏 | 真实输出逐例查 `\u0000` | **0 残留**；字面量 `CODE0CODE` 原样保留（`getOrNull` 守卫） |
| C-F05 非代码路径 | 11 条回归断言 | `**bold**`/`[link](url)`/`![img](url)`/`*it*`/`_it_`/`~~s~~`/标题/列表/围栏全部照旧 |
| C-F03 语义（id 对齐） | 既有 6 条臂 | 顺序保留、contended block last-writer-wins、untouched 身份保持、压力测试 8×200 全绿 |
| C-F04 形状（双 KDoc） | diff 逐行走查 | 发现并修掉一处：新增 KDoc 曾与 `selectMention` 的 KDoc 相邻成双块，已合并并改写归属 |
| C-F07 语义（`known` 收窄） | 既有 `PasteTextBufferTest` 全表 + `PasteFoldWiringTest` 11 例 | 内联条目仍不报、未知 id 仍报、空 buffer 仍报、单遍展开/孤儿 id 全部不变 |
| 共享函数体改动面 | `git diff` hunk 逐段 | 只改 5 个函数体（`firstResidue`、`mergeBack`、`selectMention`、`stripInline`、`unexpandedPasteMarkers`）；`spanEnd`/`dsmlSpanEnd`/`nameValueOf`/`expandPastePlaceholders`/`appendUnlinkedPastes` **零改动** |
| 括号/注释/字符串配平 | 10 个改动文件逐文件扫描 | 全部 `BALANCED` / `closed`（`ChatViewModelMentionSelectTest` 的检查器误报由 `when` 分支 `'}' -> {` 的字符字面量引起，`kotlinc` 实编译通过即证伪） |

### 4.5 同族完备性（fix-completeness）

| 同族 | 枚举结果 | 处置 |
|---|---|---|
| 上界守卫写 `>` 而非 `>=` | `StreamingFade.kt:215` 的 `r.end > base.length` **不是同族**（`end` 是排他上界，`== length` 合法，闭区间语义） | 不改 |
| `updateMentionMenuState` 的 `substring(anchor + 1, safeCaret)` | anchor 必然 `< safeCaret`（由 `safeCaret = caret.coerceIn` 与循环退出条件保证），不可达 | 不改 |
| `baseline.getOrNull(i)` 按下标对齐 | 全仓仅 `ParallelToolBatchBlocks.kt:68` 一处（其余 `getOrNull` 是 `VersionCompare` 的逐位比较、`ChatUserMessageUI` 的并列数组，语义正确） | 已改唯一一处 |
| `stripInline` 的调用点 | 5 处（heading/quote/ul/ol/generic），全部经同一函数 ⇒ 改一处全覆盖 | 已改共享层 |
| `unexpandedPasteMarkers` 的消费者 | 1 处（`ChatViewModel:997`） | 已改，并在报告记录端到端不可达 |

---

## 5 CI

**分支 `fix/r8-1114-c-chat-ui` @ `b194a798cf3adf7573872add897572fc1eb85af0`**

- 推送：`gh_sync.sh push --branch fix/r8-1114-c-chat-ui` → OK（新分支）
- dispatch：`gh-actions-dispatch --workflow build-apk.yml --ref fix/r8-1114-c-chat-ui` → **204，run created 2026-11-13T21:19:03Z**
- run：**https://github.com/logicflow-GYW/RikkaMinis/actions/runs/43509589979**

**结论**：见 §5.1（收口时回填）。

### 5.1 CI 结论（收口回填）

<!-- CI_RESULT -->

---

## 6 未做 / 遗留（诚实声明）

1. **`ComposerLiveSourceTest` 未在本地双臂里跑**。它依赖 `ChatScreenUtils.kt`，后者引用 `FlatChatItem`（一个 sealed 层级），为它建镜像的收益低于成本。本支**没有改动**该测试的任何锚点（`selectMention(` 调用点拼写未变），CI 的 `testReleaseUnitTest` 会真跑它。**这是本地证据的缺口，不是「已验证」**。
2. **C-F07 端到端不可达**（§2.5）。修的是函数契约与 KDoc 的矛盾；如果将来 `unexpandedPasteMarkers` 被接到发送守卫上（或分离式条目在 buffer 里存活），这次修复才真正生效。
3. **C-F03 是 latent**（§2.2）。当前白名单 `{file_read, read_image}` 不写块。建议在 `ToolConcurrencyPolicy.PARALLEL_SAFE_TOOLS` 加注释：扩白名单前必须确认新工具不增删 `callBlocks`。
4. **C-F02（DSML 分支抢先截断）不在本支范围**。它在同一文件（`ToolCallResiduePolicy.kt`），任务书与 DISPATCH 都未分配给 C 支。报告里它是独立的 `[medium]` 发现，**未被本支修复**，需另行安排（若归 B 支或后续批，注意它与本支的 C-F01 改的是同一函数的不同分支，合并时无冲突但语义上应一起验证）。
5. **未做真机验证**。行为面最终判据是分支 CI（编译 + 全量单测 + 门）；C-F04 的崩溃路径已在 JVM 用真 `selectMention` 复现并验证修复，但真机 IME 时序未验。
6. **Gradle 单测本地跑不了**（沙箱无 Android SDK：`SDK location not found`）。本地证据全部来自 kotlinc + JUnit 双臂，Gradle 侧由 CI 承担。
