# FIX-INCR1117-T2 — 聊天 UI 三条（B-F01 / B-F03 / B-F05）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `949566ac`（worktree `git rev-parse HEAD` 起点实测一致） |
| 分支 / worktree | `fix/chat-ui-audit-1117` / `/var/minis/shared/work/incr1117/wt2` |
| 修复提交 | `eefada32`（7 文件 **+293 / −1**；本报告另起一个提交，非报告差异为空） |
| 分支尖端 / CI 绿 sha | `eefada322f14ab5002bdf02799fe5d56f885d258` |
| 三方 sha | 本地 `git rev-parse HEAD` == `git ls-remote --heads origin fix/chat-ui-audit-1117` == CI `run.head_sha`，三者逐字符一致（§5.2） |
| CI | run **#3197** / id `44003786836` / ref `fix/chat-ui-audit-1117`，5 job success |
| 影响面 | 生产 3 文件（`ChatScreen.kt` / `ChatViewModel.kt` / `ChatInputArea.kt`）+ 1 纯函数文件（`MoveToCapsuleGate.kt`）；测试 3 文件 |
| 未触碰 | `scripts/scan/**`、`scan.sh`、`test_scan.py`、`ui/markdown/**`、`data/**`、`debug/**`、任何 `.xml`/i18n（零 strings 改动）、`ComposerArcOcclusion` / `listBottomPaddingDp` 几何 |

## 2 缺陷与先证

三条全部**回源确认成立**（不是只看行号：机制链逐环对齐真码）。

### 2.1 B-F01 `isListAtBottom` 极矮视口恒 false —— 成立

`ChatScreen.kt:5584`（改前 `:5568`）原文：

```kotlin
val visualEnd = viewportEndOffsetPx - contentBottomInsetPx.coerceAtLeast(0)
val bottomEdge = lastVisibleOffsetPx + lastVisibleSizePx
val distance = visualEnd - bottomEdge
return distance >= 0 && distance <= thresholdPx
```

`contentBottomInsetPx` 由两个调用点传入 `info.afterContentPadding`（`ChatScreen.kt:1005`、`:1393`，全仓 2 处，`ChatBottomFollowTest` 已钉住这个计数）。当 `afterContentPadding > viewportEndOffset`（横屏/分屏 + IME + 多行草稿：composer 视效把列表底部内边距撑得比列表还高）时 `visualEnd < 0`，而任何可见行都满足 `bottomEdge >= 0`，于是 **`distance` 对每一行都为负** ⇒ `isNearBottom` 与拖拽停止判定同时恒 false ⇒ 发送后不再跟随回底。判定与几何两条链都对上了：padding 源头是 `listBottomPaddingDp`，与「tail 高度」不是同一个量（这正是已登记 P2）。

### 2.2 B-F03 编辑态不清草稿粘贴 chips —— 成立

`ChatViewModel.kt:3799`（`editMessage`，粘贴块 `:3849-3855`）改前：

```kotlin
val preEditPastes = _preEditPastes.value
if (preEditPastes == null) {
    _preEditPastes.value = _pastedTexts.value          // 只快照
} else if (_pastedTexts.value != preEditPastes) {
    _pastedTexts.value = preEditPastes                 // 重入分支正确
}
```

紧邻的附件分支是**替换**（`_attachments.value = restaged.map {...}`），粘贴分支只快照。随后 `stashPastedText(paste, inlineMarker = false)`（同一函数，改前 `:3886`）把被编辑消息的粘贴段**追加**在草稿原有 chips 之后；发送时 `expandPastedTextWithSplit`（`:1113`）把 `buffer.filter { !it.inlineMarker }` **全部** detached 块 `appendUnlinkedPastes` 拼进正文 ⇒ 用户未发送的草稿粘贴被写进**历史消息**。两个 chips 同屏 + 历史污染，两处症状同根。

### 2.3 B-F05 分享胶囊复活 —— 成立

`_hasInjectedShareContent` 全仓写点普查（`grep -rn "ShareInjected"`）：

| # | 位置 | 语义 |
|---|---|---|
| 1 | `ChatViewModel.kt:3206`（`clearChat`） | 清空会话 |
| 2 | `ChatViewModel.kt:4202`（`sendMessage`） | 发送消耗 |
| 3 | `ChatInputArea.kt:2462`（Move 到其它会话） | 移动消耗 |
| 4 | `ChatScreen.kt:342`、`:449` | 武装（share drain / Move 转入） |

清空分享内容**不在**其中：`removeAttachment` 与清空文本都不清标记，`MoveToCapsuleGate.shouldShow` 只用「草稿是否还有东西」把它**遮住**。于是同一会话里重新输入任意新文本 ⇒ `shouldShow` 重新为真 ⇒ 胶囊复活，指向与分享无关的草稿，且点下去真的会搬走那份草稿。

### 2.4 判级：三条 P1（沿用 TRIAGE，无升降）

「这改变什么用户行为？」（TRIAGE §三 原句，逐条对齐）：
- B-F01：极矮视口下「在底」不再恒 false，发送后仍跟随。
- B-F03：编辑历史消息不再把当前草稿的粘贴块拼进去。
- B-F05：分享注入的内容被清空后，重新输入不会让 Move 胶囊复活。

## 3 修法（阶梯第 6 级：最小实现，各一条判据）

**停在第 6 级，因为**：三条都不需要新 API / 新依赖 / 新层；B-F01 是多一个 `coerceAtLeast(0)`，B-F03 是同一 `if` 里多一行，B-F05 是一个纯函数 + 一个 `LaunchedEffect` + `enqueuePrompt` 一行。唯一「新增代码」是 B-F05 的判定函数——它必须抽出来，因为 Compose 在 JVM 单测里组不起来（同 `ChatBottomFollowTest` 的既有做法）。

### 3.1 B-F01：只钳判定，不动几何

```kotlin
val visualEnd = (viewportEndOffsetPx - contentBottomInsetPx.coerceAtLeast(0)).coerceAtLeast(0)
```

KDoc 里写明「这不是几何修复」，并指向 backlog §INCR-1116「B F-09 极矮视口下 padding 与 tail 不一致」（候选解是 `BoxWithConstraints` 用真实视口定 tail 高度）。`ComposerArcOcclusion` / `listBottomPaddingDp` 一个字没动。

### 3.2 B-F03：首次进入 = 快照 + 清空（与附件同契约）

```kotlin
if (preEditPastes == null) {
    _preEditPastes.value = _pastedTexts.value
    _pastedTexts.value = emptyList()     // ← 新增
} else if (_pastedTexts.value != preEditPastes) {
    _pastedTexts.value = preEditPastes   // 重入分支原样保留
}
```

清空（`:3852`）放在 `stashPastedText`（`:3893`）**之前**，所以被编辑消息自己那一段仍会重新 fold 出一个 chip；`cancelEdit()` 的还原路径（`_preEditPastes.value?.let { _pastedTexts.value = it }`）未改，快照仍能整块还原。

### 3.3 B-F05：把「武装已花掉」做成显式规则（带时序闩）

新增纯函数（`MoveToCapsuleGate.observeFrame`）：

```kotlin
fun observeFrame(shareInjected, draftText, attachmentCount, sawContent): ShareArmFrame {
    if (!shareInjected) return ShareArmFrame(sawContent = false, disarm = false)
    if (canMove(draftText, attachmentCount)) return ShareArmFrame(sawContent = true, disarm = false)
    return ShareArmFrame(sawContent = sawContent, disarm = sawContent)
}
```

**为什么不是任务书初写的 `shareInjected && !canMove`**：分享文本是经 composer 命令队列落地的（`ChatScreen.kt:334` → `ComposerCommand.AppendText`，`ChatInputArea` 侧异步消费），所以存在「已武装、草稿仍空」的帧——纯文本分享甚至会有整轮 composition 停在这个状态。判据若只看 `!canMove`，**每一条纯文本分享都会在文本落地前被清掉**。所以加了 `sawContent` 闩：只有「见过它注入的内容」之后清空，才算花掉。附件不需要闩（`addAttachmentFromStagedShare` 是 suspend 但同步完成，`markShareInjected()` 在其后调用）。

消费点：`ChatInputArea.kt:1262` 的 `LaunchedEffect(shareInjected, inputFieldValue.text, attachments.size)`，把闩写回 `remember { mutableStateOf(false) }`，命中 `frame.disarm` 时调 `viewModel.clearShareInjectedFlag()`。

另外 `enqueuePrompt` 把手上的草稿交给运行中的 agent loop ⇒ 视为已消费，一并清标记（`ChatViewModel.kt:3951`）。

## 4 覆盖面（同族 / 调用点普查计数）

| 家族 | 计数（改前） | 处置 |
|---|---|---|
| `contentBottomInsetPx = info.afterContentPadding` 调用点 | 2（`ChatScreen.kt:1005`/`:1394`） | 全部走共享 `isListAtBottom`，改一处即两处生效；`ChatBottomFollowTest` 原本就钉死计数=2，未变 |
| `isListAtBottom` 实现 | 1（声明 `:5555`，钳位 `:5584`） | 单一实现，未新增副本 |
| `_preEditPastes` 写点 | 6（`:3203` clearChat · `:3851` 快照 · `:3919`/`:3920` cancelEdit 还原+置空 · `:4169` 拒绝发送清理 · `:4277` 发送消耗） | 只在 `editMessage` 首入分支加一行；其余未动 |
| `_hasInjectedShareContent` 写点 | 5（见 §2.3 表） | 新增 2 个消费点（composer 帧判定、`enqueuePrompt`）；武装点未改 |
| `MoveToCapsuleGate.shouldShow/canMove` 调用点 | 3（composer 显示、composer 移动前置、`ChatScreen` 转入） | 未改语义，只多了一个新的判定函数；`shouldShow` 仍是「显示」的唯一判据 |
| 文案 / i18n key | 0 改动 | 本支零 strings 改动，未新增 key |

## 5 验证四层

### 5.1 本地窄验证（沙箱 JVM，`nice -n 10`，一次一个 JVM）

装置：`/var/minis/shared/work/incr1117/t2test/`（`extract.py` 把 `isListAtBottom` 连 KDoc 从 `ChatScreen.kt` 抽成 `gen/IsListAtBottom.kt`，与 `MoveToCapsuleGate.kt`、三个测试文件一次性 kotlinc 编译；Compose 依赖不进栈）。

| 套件 | 结果 |
|---|---|
| `ChatBottomFollowTest` | **OK (23 tests)** |
| `MoveToCapsuleGateTest` | **OK (17 tests)** |
| `PasteFoldWiringTest` | **OK (14 tests)** |

共 **54 tests**（改前 41 + 新增 13）。

### 5.2 变异负控（每条修复各一个，红的臂必须精确对应）

| # | 变异 | 结果 |
|---|---|---|
| A | 把 `visualEnd` 还原成未钳表达式（并重抽 `IsListAtBottom.kt`） | `ChatBottomFollowTest` **2 failures**：`the visible end is clamped at zero`（源形状）＋ `a viewport shorter than its bottom padding still reports the tail as bottom`（值级回放）——其余 21 条仍绿 |
| B | 删掉 `_pastedTexts.value = emptyList()` | `PasteFoldWiringTest` **1 failure**：`entering edit mode replaces the draft's chips instead of stacking on them` |
| C | 删掉 `if (frame.disarm) viewModel.clearShareInjectedFlag()` | `MoveToCapsuleGateTest` **1 failure**：`the composer spends the arm from the latch, not from a bare emptiness check` |
| D | 删掉 `enqueuePrompt` 里的清标记行 | `MoveToCapsuleGateTest` **1 failure**：`enqueueing the draft spends the arm too`（这正是「整文件 contains 会假绿」的负控：`sendMessage` 有同一行） |

变异后均按单文件快照还原（不用 `git checkout -- .`），还原后复跑三套全绿、`git diff --stat` 与 §1 一致。

### 5.3 机械项

括号配平：`ChatScreen.kt` 基线与改后 `{167/173, (365/365}` 完全一致（本次加的是表达式，未新增块）；`ChatInputArea.kt` 由 `{208/201}` 变 `{210/203}`（新增 `remember{}` 与 `LaunchedEffect{}` 各一对，双侧同增）；其余 5 文件 `{} () []` 全配平。粗扫器对两个含 `${}` 模板的大文件报「不平衡」是扫描器自身的已知偏差（基线同样报），已用基线对照证明差异为 0。

### 5.4 CI（run #3197 / id `44003786836` @ `eefada32`）

| job | 结论 | 关键标记 |
|---|---|---|
| `scan-gate` | success | `Summary: 115 passed, 0 failed`（`scan.sh` 全 115 门跑过）；日志另含 `scripts/scan/** untouched in this push — skipping the fixture self-test`——**本批未改门，夹具自检按设计跳过，故「CI 绿」不含夹具负控这一维**（如实记录，不写成「夹具全绿」） |
| `unit-tests` | success | **9311 PASSED / 0 FAILED**（`grep -c ' PASSED$'` / `' FAILED$'`），`BUILD SUCCESSFUL` |
| `lint` | success | — |
| `build-apk` | success | — |
| `publish` | success | **两步会被发布面弄脏的 step 均 skipped**：`Move the rolling tag android-latest to this commit`、`Publish to Releases`（main 门控仍生效）；执行成功的只有 artifact 下载/打入版本号/stage，未污染 release 资产 |

本支 13 条新测试在 CI 日志里逐条 `PASSED`（全部命中）：

```
ChatBottomFollowTest > an inset exactly equal to the viewport end keeps the tail row at bottom PASSED
ChatBottomFollowTest > a viewport shorter than its bottom padding still reports the tail as bottom PASSED
ChatBottomFollowTest > the clamp does not turn any over-long padding into at-bottom PASSED
ChatBottomFollowTest > the visible end is clamped at zero PASSED
MoveToCapsuleGateTest > an arm whose content has not landed yet is not spent PASSED
MoveToCapsuleGateTest > the injected content latches the arm PASSED
MoveToCapsuleGateTest > clearing a draft that was seen spends the arm PASSED
MoveToCapsuleGateTest > deleting the last shared attachment spends the arm PASSED
MoveToCapsuleGateTest > an unarmed share resets the latch and never disarms PASSED
MoveToCapsuleGateTest > the composer spends the arm from the latch, not from a bare emptiness check PASSED
MoveToCapsuleGateTest > enqueueing the draft spends the arm too PASSED
PasteFoldWiringTest > entering edit mode replaces the draft's chips instead of stacking on them PASSED
PasteFoldWiringTest > leaving edit mode puts the draft's chips back PASSED
```

## 6 残留（每条带触发条件 + 触发后方案）

| ID | 残留 | 为什么本支不修 | 触发条件 → 触发后方案 |
|---|---|---|---|
| R-T2-1 | `afterContentPadding` 超过视口高度**且超出量 > `thresholdPx`** 时，末行底边落在钳位端点上方更远处（`bottomEdge` 更负），判定仍会 false | 这是几何不一致（padding 与 tail 不是同一个量），任务书明令不在本支；钳位只把「恒 false」降为「超阈值才 false」 | 真机在横屏+IME 下复现仍不回底 → 走 backlog §INCR-1116「B F-09」，用 `BoxWithConstraints` 以真实视口裁 tail 高度 |
| R-T2-2 | B-F05 的 `sawContent` 闩是 `remember` 作用域：composer 被重建（会话切换重组合）而闩丢失时，「已落地的分享内容被清空」这一次清标记会漏 | 闩放 ViewModel 需要新状态字段（四处同步 + 清理点），触发面窄（必须在清空草稿的同时重建 composer） | 出现「清空分享内容后打字，胶囊又冒出来」的真机复现 → 把闩提升到 `ChatViewModel`（随 `_hasInjectedShareContent` 一起清） |
| R-T2-3 | B-F03 的 JVM 证据是**源形状**（`ChatViewModel` 在 JVM 无法构造：Android `Context`），`cancelEdit` 还原也是形状断言 | 与全仓既有口径一致（单测不能构造 Context，见 rollup「测试与装置」） | 想升级为行为证据 → 把「首入清空 + 取消还原」抽成纯函数（入参 = 快照/现值），按 `ChatBottomFollowTest` 的值级风格补用例 |
| R-T2-4 | 三条均**未经真机**：横屏+IME 跟随、编辑态 chip 还原、分享清空后不再复活 | CI 绿 ≠ 行为对；真机清单在批次收口统一跑 | 装机后按 §7 清单逐条验；任一不成立 → 回本支重开 |

## 7 未验证边界（真机清单）

1. 横屏 / 分屏 + IME 弹起 + 多行草稿：发一条消息，列表是否回到底（B-F01）。
2. 同一几何下向上拖一小段再松手，是否立刻脱离跟随（`ChatBottomGate` 的两个调用点共用判据）。
3. 先粘贴一大段（生成 chip）→ 长按历史消息「编辑」：输入框上方是否**只有**被编辑消息那一个 chip；发送后历史消息里**只有**被编辑消息的粘贴块、不含草稿那块（B-F03）。
4. 同上场景点「退出编辑」：草稿 chip 是否原样回来（`cancelEdit` 还原）。
5. 从系统分享注入一段文本 → 全选删除 → 输入任意新文本：Move 胶囊是否**不再出现**（B-F05）。
6. 只分享文本（无附件）时，胶囊是否在文本落地那一帧**正常出现**（闩的时序面，最容易被误伤）。
7. 分享注入后立刻点「Move to…」搬到另一会话，再回到原会话：胶囊不应残留（原有路径，回归确认）。

## 8 附装置路径

- worktree：`/var/minis/shared/work/incr1117/wt2`
- 本地 JVM 装置：`/var/minis/shared/work/incr1117/t2test/`
  - `extract.py`：从 `ChatScreen.kt` 抽取 `isListAtBottom`（连 KDoc、大括号配对）到 `gen/IsListAtBottom.kt`
  - `getlogs.py`：CI job 日志两跳下载（首跳取 `redirect_url`，二跳裸取带 UA）
  - `logs/`：`unit-tests-133774046488.log`（1.5 MB）、`scan-gate-133774046585.log`、`lint-133774046458.log`
  - 变异快照：`bak_vm.kt` / `bak_vm2.kt` / `bak_ia.kt`（变异 B/C/D 的单文件还原用）
- 审计来源：`/var/minis/shared/work/meta-audit/reports/incr1117/Incr_1117_B_Report.md`（B-F01/F03/F05）
- 判级与派发：`/var/minis/shared/work/incr1117/TRIAGE-incr1117.md`
