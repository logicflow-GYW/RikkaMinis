# FIX-2D-1119-3 · composer：同帧重入竞态 / 守卫清空的焦点与指纹 / 形状门可被注释与死分支绕过

分支 `fix/composer-focus-1119` · 基线 `b82b2963` · 来源 `reports/r2d1119/1_Composer_Report.md`

## 0 范围

只改三个文件（生产 1 + 测试 2），零新增文件、零门脚本、零 `strings.xml`：

| 文件 | 改动 |
|---|---|
| `ui/chat/ComposerClearResiduePolicy.kt` | `composerResidueVerdict` 的 `boxEmpty` 判据 + arm A 的 `DROP`→`DROP_AND_CLEAR` 升级 + 三处 KDoc 与最终态对齐 |
| `ui/chat/ComposerClearResiduePolicyTest.kt` | +7 用例（F-04 六条、F-05 一条） |
| `ui/chat/ComposerClearResidueWiringTest.kt` | +4 用例、注释掩码视图 `code`、`depthAt`、`parenthesisedAt`/`blockAt` 跳过 `"""` |

`ChatInputArea.kt` **零改动**（`boxEmpty` 与判据都在策略文件里，调用点无需变）。

**用户行为变化**：一条「一句话长度」的 IME 重放在发送后 1.5 s 内落进**空框**、而 ViewModel 副本还没刷新时，从「漏过、留在框里」变成「丢弃 + 走清空漏斗」。用户在空框里打一个字、点一个联想词、滑一个词的行为不变（下面的 arm 逐条钉住）。

## 1 逐条先证

### F-04（P1）成立，且比报告描述的范围更大

**报告的可证伪条件**问的是「`setInputText` 能否同步刷新 `collectAsState()`」。逐项取证：

1. `ChatViewModel.setInputText`（`ChatViewModel.kt:895`）= `_inputText.value = value` + 草稿镜像。写 `MutableStateFlow` 是同步的，但**读侧**是 `ChatInputArea.kt:192` 的 `by viewModel.inputText.collectAsState()` —— Compose 的 `collectAsState` 在 `LaunchedEffect` 里 collect，值落到 `MutableState` 要等下一次 dispatch。所以**在同一个 `onValueChange` 回调里，`inputText` 是旧值**。
2. `inputFieldValue` 是 `mutableStateOf`（`ChatInputArea.kt:212`），`clearComposerInput` 里 `inputFieldValue = TextFieldValue("")`（`:353`）是同步写，同一个回调链里立刻可见。
3. 「同一帧内重入」不是理论：`shouldDebounceImeBurst` 的 150 ms 防抖存在的原因就是听写/第三方 IME 的 commit 会**每帧多个事件**到达；`ComposerMirror` 在 2026-11-18 的 14 次听写会话里记录到 `field = vm + 1ch`，即两条副本本来就常年错开一帧。

**修复前实测**（`composer-jvm/probe/Probe.kt`，44 字正文）：

```
A[vm empty,      box empty, 20ch prefix, 400ms] -> DROP            ← 期望
B[vm == 正文,    box empty, 20ch prefix, 400ms] -> NONE            ← 缺陷：应 DROP
C[vm == 20ch前缀, box empty, 20ch prefix, 400ms] -> NONE            ← 缺陷：应 DROP
D[vm == 正文,    box empty, 整段,        100ms] -> DROP_AND_CLEAR   ← arm B 兜住
E[vm == 无关文本, box empty, 整段,        100ms] -> NONE            ← 正确拒绝
F[vm == 正文,    box empty, 联想词 6ch,   400ms] -> NONE            ← 正确拒绝
```

B/C 就是报告说的「长前缀重放误入 Arm B 并因未收敛返回 NONE」——20 字短语（≥ `COMPOSER_SINGLE_EDIT_PHRASE_CHARS`）本该被 arm A 的短语门直接判 `DROP`。

**结论：成立，`probable` 可以升为 `concrete`。**

### F-05（P1）两半结论不同

- **焦点半：不修，且是必须不修。** 报告自己写了「用户正在输入时收键盘是体验倒退」，但更硬的理由在代码里：`COMPOSER_CLEAR_RESIDUE_WINDOW_MS` 的 KDoc 明说 `clearFocus` 会同步让 IME `finishComposingText()`，**那正是产生被重放内容的那次 commit 的机制**。在这个分支上 clearFocus 等于亲手再触发一次重放。加上 slash 命令、clear-chat 这些清空入口本来就刻意保留焦点给用户下一条消息。
- **时间戳半：不成立（惰性）。** `lastClearedAtMs` 的四个读点全部有非空守卫：`composerResidueVerdict` 在 `clearedText.isNullOrEmpty()` 处早退；`shouldRestampClearedBody` 要求 `previousStamp != null` 才看 `msSinceStamp`；两处日志也都在 `lastClearedText != null` 里面。所以「清了 `lastClearedText` 不清 `lastClearedAtMs`」等价于「0 ms 之前清了一个不存在的正文」——不会让任何后续编辑被误判。
- **报告漏掉的一半，我补上了**：报告只问「该不该 clearFocus」，没问「该不该清指纹」。**指纹必须留**——`shouldRestampClearedBody(guardClear = true)` 的设计目的就是保住它，好让后续碎片继续被识别；在分支里清掉会让重放的第二段碎片被当成用户第一个按键。这一点现在有负向臂钉住。

两条都由测试钉住（`the guard wipe changes neither focus nor the keyboard` / `the guard wipe keeps the cleared body stamp`），并把惰性写成行为用例（`a dropped stamp is never a residue even when its timestamp is still fresh`）。

### F-07（P1）成立，四条子项逐一确认

| 报告子项 | 现状 | 处理 |
|---|---|---|
| ①`indexOf` 不跳注释 | 全 9 个臂都是裸 `source.indexOf` | 全部改读注释掩码视图 `code` |
| ②块内 `contains` 不剔注释 | 同上 | 同上（`code` 里注释已是空格） |
| ③三处裸 `substring`/向后扫描 | 是（`:124`/`:275`/`:347` 的 700/200/400 字符窗口） | 锚点改读 `code`；窗口本身保留（它们读的是调用参数，`code` 已保证不会读到注释） |
| ④`parenthesisedAt` 漏 `"""` | 确认 | `blockAt`/`parenthesisedAt`/`depthAt` 统一走 `skipRawString` |
| ⑤无控制流可达性校验 | 确认 | 新增 `depthAt`：要求 wipe 调用与 `guardUnwindClear = true` 是**分支体的直接语句**（大括号深度 = 1） |

报告的触发链**实测复现过**：把 `guardUnwindClear = true; clearComposerInput()` 包进 `if (false) { … }`，修复前全绿；修复后 `the replay wipe is a direct statement of the branch body` 变红（变异 M4）。

## 2 修法

### F-04

```kotlin
// 旧
val boxEmpty = fieldText.isEmpty() && vmText.isEmpty()

// 新
val vmContradictsEmptyBox =
    vmText.isNotEmpty() && !isFragmentOfClearedBody(vmText, clearedText)
val boxEmpty = fieldText.isEmpty() && !vmContradictsEmptyBox
```

理由：副本为空 **或** 仍是本次清空正文的片段，都不是「有东西落进来了」的证据 —— arm B 早就把这种滞后当成无害（`vmIsReplay`）。只有副本装着**与正文无关**的文本才说明框不空，那时 arm B 才是对的臂。

配套：arm A 判出 residue 后，如果 ViewModel 副本还非空，判 `DROP_AND_CLEAR` 而不是 `DROP` —— 只丢这一条事件不够，那份过期副本才是镜像和持久化草稿槽读的东西，会在 150 ms 后把正文写回框里。字段本身没有东西要清，所以走同一个清空漏斗是无害的（`stampClearedBody` 因 `guardUnwindClear` 保留戳、`clearFocus` 未被调用）。

### F-05

零代码改动。两个决定（不清焦点、不清指纹）各配一条负向臂，惰性配一条行为用例。理由写进上面 §1 和用例 KDoc。

### F-07

- 新增 `private val code: String get() = maskComments(source)`：**同长度**掩码，`//` 与 `/* */` 换成空格（换行保留），字符串/原始字符串原样拷贝。同长度是关键 —— 所有既有偏移量继续有效，`blockAt`/`parenthesisedAt` 也能直接用。
- 所有锚点与 `contains` 改读 `code`。
- `blockAt`/`parenthesisedAt` 的字符串跳过统一到 `skipStringLiteral`/`skipRawString`（修 `"""` 漏跳）。
- 新增 `depthAt`，wipe 臂要求调用是分支体直接语句。
- 掩码器自己有一条臂（`the comment masker blanks comment text and keeps every offset`）：长度保持、注释消失、字符串里的 `//` 不动、偏移量不变、**不吞代码**。最后一条是实测加的——第一版掩码器漏了「原样拷贝字符串」，把 `"…"` 整段吞掉，三个臂同时红。

## 3 证据

### 本地 JVM（`shared/work/r1119/composer-jvm/run.sh`）

- `ComposerClearResiduePolicyTest` **58 run / 0 failed**
- `ComposerClearResidueWiringTest` **13 run / 0 failed**
- 既有用例**一字未改**（唯一的改动是把 `a replay is caught when only the view model holds the earlier fragments` 的注释补上「为什么这里是 DROP_AND_CLEAR」，断言未变）。

### 新增用例逐条 PASSED（CI unit-tests 日志，run #3280）

```
ComposerClearResiduePolicyTest > a stale view model copy does not route a phrase replay into the non-wiping arm PASSED
ComposerClearResiduePolicyTest > a lagging prefix copy does not route a phrase replay into the non-wiping arm PASSED
ComposerClearResiduePolicyTest > a whole body replayed against a stale view model copy is still wiped PASSED
ComposerClearResiduePolicyTest > a stale view model copy never makes a chip word look like a replay PASSED
ComposerClearResiduePolicyTest > an unrelated view model copy still keeps the box out of arm A PASSED
ComposerClearResiduePolicyTest > an empty view model copy drops the phrase without escalating to a wipe PASSED
ComposerClearResiduePolicyTest > a dropped stamp is never a residue even when its timestamp is still fresh PASSED
ComposerClearResidueWiringTest > the replay wipe is a direct statement of the branch body PASSED
ComposerClearResidueWiringTest > the guard wipe changes neither focus nor the keyboard PASSED
ComposerClearResidueWiringTest > the guard wipe keeps the cleared body stamp PASSED
ComposerClearResidueWiringTest > the comment masker blanks comment text and keeps every offset PASSED
```

（每条在日志里 `grep -c` 命中 1 次。）

### CI

**最终 tip `c714347b`（含报告）—— run #3287 / `44369272888`**，五 job 全 `success`，`head_sha = c714347bf730ce40a65cb52adf75677b34206703` 与本地/远端 tip 逐字符一致：

| job | 结论 |
|---|---|
| lint | success |
| build-apk | success |
| unit-tests | success（**9761 PASSED 行 / 0 FAILED 行**，11 条新用例逐条 `grep -cF` 命中 1） |
| scan-gate | success |
| publish | success（日志止于 `download-artifact` / `VC=$((220000000 + GITHUB_RUN_NUMBER))` 等前置步骤，**无 tag / release 步骤组** —— main 门控生效，分支不产出 release） |

**过程中的两次非本支失败，已归因**：
- run #3280 / `44364886822`（tip `316b6522`）**lint 失败**：`Could not resolve … org.hamcrest:hamcrest-integration:1.3 / com.squareup:javawriter:2.1.1` —— Maven 中央瞬时解析失败，同一 job 在 #3284（`1e863e42`）与 #3287 都是 success，且本支零改动 build 文件。
- run #3284 / #3286 显示 `cancelled`：后一次 dispatch 让 GitHub 取消了仍在排队的旧 run（同一 workflow + 同一 ref 的 concurrency 行为），不是测试失败。

**报告之外差异为空**：`git diff --name-only c714347b^ c714347b` 只有 `fix-reports/FIX-2D-1119-3-composer.md`；`git diff --stat b82b2963 HEAD` 只有上表四个文件。

## 4 变异负控（`composer-jvm/mutate.py`，快照 + finally 还原 + sha256）

| 臂 | 变异 | 期望红 | 实测 |
|---|---|---|---|
| M1 | `boxEmpty` 退回 `fieldText.isEmpty() && vmText.isEmpty()` | 两条 F-04 短语臂 | **RED-PRECISE**（2） |
| M2 | 删掉 arm A 的 `DROP→DROP_AND_CLEAR` 升级 | 四条 | **RED-PRECISE**（4） |
| M3 | 在分支上方插入报告给的劫持注释（含锚点原文 + 注释掉的调用） | 无（掩码视图应忽略） | **RED-PRECISE**（0） |
| M4 | 把 wipe 包进 `if (false) { … }` | `direct statement` | **RED-PRECISE**（1） |
| M5 | 守卫清空里插 `focusManager.clearFocus()` | `neither focus nor the keyboard` | **RED-PRECISE**（1） |
| M6 | 掩码器失效（`if (true) return text`） | 掩码器自身 + 被它保护的臂 | **RED-PRECISE**（2） |

`BASELINE-AFTER-RESTORE: OK`，三文件 sha256 前后一致。

两条**文档化**的非直觉期望，已在脚本里写理由：
- **M1 不期望「整段重放」臂红**：那条走的是 arm B 的收敛判据，与臂选择无关；它钉的是**结果**（过期副本下整段仍被清），不是臂选择。
- **M6 期望两条**：掩码失效后，`stampClearedBody` KDoc 里引用的 `COMPOSER_CLEAR_RESIDUE_WINDOW_MS` 会被当成「内联重实现」而误红 —— 这本身就是 F-07 要证明的失败模式。

## 5 未修与取舍

| ID | 级别 | 处理 |
|---|---|---|
| F-01 | P2 | 不修（已声明取舍：短正文整段重放在窗口后半段漏网） |
| F-02 | P2 | 不修（短消息 ≥50% 联想词被吞；与 IME 重放固有不可区分） |
| F-03 | P2 | 不修（arm B 只在整段重建时清；半截长重放返回 NONE，已声明 ceiling） |
| F-06 | P2 | 不修（`commitComposerBody` 8 处拼接可能把 arm A 放行的碎片当正文写回） |
| F-08 | P2 | **只列位置不改**（见下） |

**F-08 四处旧注释的位置**（按要求只登记）：

1. `ChatInputArea.kt:1945-1949` —— 仍写 `stampClearedBody`「靠状态识别 unwind」，而 A4 已要求 `guardUnwindClear = true` 才可能不重戳。
2. `ComposerClearResiduePolicy.kt:277-278` —— `shouldRestampClearedBody` KDoc 里的「Two conditions make that state recognizable」讲的是纯状态模型，A4 之后 `guardClear=false` 时根本不进那个状态测试（直接 `return true`）。
3. `ComposerClearResiduePolicy.kt:330-334` —— `isFragmentOfClearedBody` KDoc 写「Prefix and suffix stay unconditional — an edit that starts or ends the body exactly… is the replay shape」，1118C 之后空框下还要过占比 + 超速门，前缀/后缀本身不再是充分条件。
4. `ChatInputArea.kt:251` —— 仍写 `let isComposerClearResidue decide`，实际调用已换成 `composerResidueVerdict`（`isComposerClearResidue` 只剩测试在用）。

**我改动的两处 KDoc 不算 F-08**：`DROP_AND_CLEAR` 的枚举文档和 `composerResidueVerdict` 主 KDoc 的 arm A 段落，是因为**本轮 F-04 让它们变得不准确**（arm A 现在也能到 `DROP_AND_CLEAR`；空框定义不再是「两份都空」）。不同步就是我自己造新的 F-08。

**同族新发现（登记，不修）**：`ChatRefusedSendRestoreTest` 的 `braceBlockAt` 已正确跳过 `//`/`/* */`/`"""`/`"`，但它的锚点仍是裸 `indexOf`，也没有死分支校验 —— 与 F-07 同族、同目录、不同文件。本支按任务书只碰 composer 三个文件，登记在此。

## 6 文件清单

```
src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ComposerClearResiduePolicy.kt   (+38 / −8)
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResiduePolicyTest.kt (+93 / −1)
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResidueWiringTest.kt (+270 / −44)
fix-reports/FIX-2D-1119-3-composer.md
```

装置（不在仓库内）：`shared/work/r1119/composer-jvm/{run.sh,mutate.py,ci.py,cilog.py,probe/Probe.kt,RunTests.java}`。

## 7 复现

```sh
# 行为 + 形状门（本地 JVM，约 40 s）
sh /var/minis/shared/work/r1119/composer-jvm/run.sh

# 修复前后差分（探针，B/C 两行从 NONE 变 DROP_AND_CLEAR）
sh /var/minis/shared/work/r1119/composer-jvm/run.sh probe

# 变异负控（6 臂，约 4 min）
python3 /var/minis/shared/work/r1119/composer-jvm/mutate.py
```

真机复现原症状：第三方输入法（搜狗/讯飞/百度/语音）发送一条 12 字以上的消息，日志应出现
`ComposerResidue [fix/composer-post-send-residue] dropped a late IME replay of the cleared body`，
且输入框保持空；修复后同一场景不再出现「发送后输入框里还剩半句」。

## 8 残留风险

`boxEmpty` 现在把「副本仍是正文片段」当成空框，这**放宽**了 arm A 的入口：如果某个 IME 让 ViewModel 副本长期停在正文上（而不只是一帧），arm A 的短语门会持续生效到 1.5 s 窗口结束。这个方向的代价是「可能多丢一条短语长度的用户输入」，但只有「副本仍等于/包含刚清空的正文」这一种状态才成立，且窗口 1.5 s 封顶 —— 与 F-02/F-03 已登记的同类取舍一致。
