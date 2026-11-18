# FIX-1118B-A — composer 残留状态机（A1–A6）

**分支** `fix/composer-residue-1118b`（基线 main `059d27e8`）
**生产** 2 文件（`ComposerClearResiduePolicy.kt`、`ChatInputArea.kt`）· **测试** 3 类 · **新建门** 0 · **碰 `scripts/scan/**`** 0

---

## 1 症状与判级

外部审计 A 报告（`Fix_1118_A_Report.md`，审计窗口 `dcea8ed9..8fdbbc86`）指出这套「三态判定 + 指纹保留 + 单一清空漏斗」状态机尚未闭合，其中两条**会直接吃掉用户正在输入的文字**：

| ID | 判级 | 一句话 |
|---|---|---|
| A1 | **P0** | 发送后 1.5 s 内续打同前缀内容（发 `hello world` 后打 `he`）被 `DROP_AND_CLEAR` 整段吃掉，且指纹不更新 → 窗口内反复被吃 |
| A2 | **P0** | 拒发回填后指纹仍是原文 → 1.5 s 内退格/补标点/IME 重复提交 ⇒ 抹掉刚还给用户的整段 |
| A3 | P1 | Arm A 的 4 字中段 `contains` 吞联想词 / 滑动输入 |
| A4 | P2 | 前长后短连发被误判为 `unwindingReplay` → 第二条指纹不更新、窗口被第一条吃掉 |
| A5 | P1 | `boxIsReplay` 用 `\|\|`，与上方「EVERYTHING in it」注释相反 |
| A6 | P1 | 四处形状断言删掉核心行为仍全绿（装置缺陷，正是 A1/A2 逃逸的原因） |

**报告里被证伪的一条**：A2 的触发链写了「退格到 `hello worl` 会命中片段」。主会话亲核 + 本支复核：`"hello worl"` 既非前缀（`hello world` 不以 `hello worl` 开头）、非后缀、非中段，`isFragmentOfClearedBody` 返回 false ⇒ 退格这一路径**不成立**。真正成立的是「补标点/空格」与「IME 重复提交同一段」——那两条被 `stripImeTrailingNoise` 归一后确实命中。修法与判级不变（A2 仍是 P0），但用例按成立的路径写，不照抄报告的退格子案例。

---

## 2 根因（逐条）

### A1 — Arm B 的判据本身是错的

`composerResidueVerdict` 的 Arm B 原判据是「框里已非空 且 框里的东西是正文片段 ⇒ `DROP_AND_CLEAR`」。而用户发送后接着打字，产生的**正是**这个形状：

```
t=0    发送 "hello world"  → lastClearedText = "hello world"
t=200  onValueChange "h"   → Arm A（4 字下限）放行，指纹保留
t=420  onValueChange "he"  → fieldText="h" 是片段、incoming="he" 是片段 ⇒ Arm B ⇒ 清空
```

内容层面「IME 回放」与「用户续打」**逐字节相同**，任何内容判据都分不开。所以报告的方向「加大门槛」治不了根：4 字下限加在 Arm B 上只会把误吞点往后推两个字符。

### A2 — 回填走的是同一个漏斗，但指纹没清

`clearComposerInput` 记指纹 → `sendMessage` 拒发 → `restoreComposerAfterRefusal(T)` → `ComposerCommand.ReplaceText` → `commitComposerBody(T, T.length)` 写回两处副本，**但没有清 `lastClearedText`**。于是框里是 `T`、指纹也是 `T`，窗口内用户一动（补标点/IME 结算）就 `DROP_AND_CLEAR`。

### A4 — `shouldRestampClearedBody` 只有状态判据，没有来源

「旧指纹在窗口内 且 新正文是它的片段 ⇒ 不重设」这条规则，对「守卫自己清残片」成立，对「用户连发第二条恰好是第一条前缀」不成立——后者必须重设，否则第二条继承第一条的指纹**和已经被第一条花掉的窗口**（时间戳不更新），它自己的回放在窗口外到达，直接漏网。

### A5 — 注释与实现相反

KDoc 写「only while EVERYTHING in it is a piece of the cleared body」，代码是 `field || vm`。`ComposerMirror` 的 `field = vm + 1ch` 现实下，`fieldText="hz"`（用户自己的）配 `vmText="hel"`（滞后的片段）就会授权抹掉 `"hz"`。

### A6 — 四处形状断言的共同病

不是「写错了断言」，是**断言的锚点落在被测量分支之外**：

1. `handBack` 只认 `ComposerCommand.ReplaceText(text = text`，不认 `caretOverride`；`restoreComposerAfterRefusal` 的函数体**完全没被测量**；
2. `source.substring(dropAndClear).contains("clearComposerInput()")` —— 切到文件尾，命中 400 行外的其它清空调用；
3. 只查 `stampClearedBody` 体里「包含 `shouldRestampClearedBody(`」，不查它是 `if` 条件、更不查答案被使用；
4. 「`lastClearedText = null` 之前 400 字符内有 `isFragmentOfClearedBody(`」——被 125 字符外的**观测日志块**满足。

---

## 3 修法

### A1 — 把 Arm B 从「片段即清」改成「收敛即清」（方向：收敛判据，不是加大门槛）

新增两个纯函数（`ComposerClearResiduePolicy.kt`）：

```kotlin
internal const val COMPOSER_REPLAY_MAX_TYPEABLE_CHARS_PER_SEC = 12
internal const val COMPOSER_SINGLE_EDIT_PHRASE_CHARS = 12

internal fun isSuperhumanTypingRate(chars: Int, msSinceClear: Long): Boolean {
    if (chars <= 0) return false
    val elapsed = msSinceClear.coerceAtLeast(0L)
    return chars.toLong() * 1000L > elapsed * COMPOSER_REPLAY_MAX_TYPEABLE_CHARS_PER_SEC
}

internal fun hasReplayConverged(incoming, clearedText, msSinceClear): Boolean {
    // 归一化后必须整段相等，且速率不可能是人手
    if (incomingNorm != clearedNorm) return false
    return isSuperhumanTypingRate(incomingNorm.length, msSinceClear)
}
```

- **Arm B**：仍要求「框里每一处非空副本都是正文片段」（A5 的合取），但**只有在 `hasReplayConverged` 成立时**才 `DROP_AND_CLEAR`；中间态放行。
- **Arm A**：保留 4 字下限，但加两条独立判据任一成立才 DROP——①单次编辑 ≥12 字（`COMPOSER_SINGLE_EDIT_PHRASE_CHARS`：没有任何输入法一次 commit 一个短语，只有 IME 从自己缓冲区重放才会）；②速率不可能是人手。

**取舍写进 KDoc 并测试钉死**：吞一个按键比留一小段残留更糟（代码自己的原则，`:216`）。所以

- 用户续打 `he`（t≈420 ms，2 字 ≈ 4.8 字/秒）→ **放行**；
- 逐字符回放把整段重建完（t≈100 ms，42 字）→ **整段清掉**；
- 中间态（框里攒着 `hell`）→ **放行**，代价是一段最多 1.5 s 的残留，且用户一旦打无关字符指纹立刻被清（`shouldKeepClearedStamp`）。

为什么不用「前缀且达到正文某比例」：5 字正文的「四分之三」是 4 字，人手半秒就能打完——比例判据恰好会吃掉本批要修的那个 case。整段重建 + 速率没有这个阈值问题。

### A2 — `commitComposerBody` 清指纹

在写两处副本之前 `lastClearedText = null; lastClearedAtMs = 0L`。理由写进 KDoc：这个漏斗写的是**用户自己的正文**（拒发回填 / 分享注入 / slash 切换），永远不是「某次清空删掉的正文的回放」。

已核对 `commitComposerBody` 的全部调用点（slash 三个出口、`ReplaceText`、`AppendText`、分享注入、消息编辑预填、拒发三处）：清指纹不会削弱「外部写入后 IME 重放」的防护——那类防护针对的是**清空后**的重放，而这里刚刚发生了外部写入，框不是空的。

### A3 — 中段匹配只属 Arm B

`isFragmentOfClearedBody(..., allowMiddleFragment: Boolean = true)`；`composerResidueVerdict` 里传 `allowMiddleFragment = !boxEmpty`。理由：IME 从缓冲区中段恢复时，框不会是空的；而空框里的 4 字中段命中更可能是用户自己的词（联想条、滑行输入）。

### A4 — `shouldRestampClearedBody` 加来源参数

```kotlin
internal fun shouldRestampClearedBody(
    clearedBody, previousStamp, msSinceStamp,
    windowMs = COMPOSER_CLEAR_RESIDUE_WINDOW_MS,
    guardClear: Boolean = false,      // 只有守卫自己的 DROP_AND_CLEAR 传 true
): Boolean {
    if (clearedBody.isEmpty()) return false
    if (!guardClear) return true      // 用户发送/主动清空：永远重设
    ...
}
```

接线：`ChatInputArea` 新增 `guardUnwindClear` 状态，守卫在 `clearComposerInput()` **之前**置 true，`stampClearedBody` 读取后立即复位并作为 `guardClear` 传入。选状态位而不是改漏斗签名，是因为 `clearComposerInput` 有 8 个调用点、且被两道形状门（`B3RenderInputShapeTest` 的 `clearComposerInput()` 计数、`chat_ui_interaction_check` 的单一漏斗判据）锚定。

### A5 — `||` → 合取（空副本视为「无矛盾」）

```kotlin
val fieldIsReplay = fieldText.isEmpty() || isFragmentOfClearedBody(fieldText, clearedText)
val vmIsReplay    = vmText.isEmpty()    || isFragmentOfClearedBody(vmText, clearedText)
if (!(fieldIsReplay && vmIsReplay)) return NONE
```

空副本「vacuously 一致」，所以 `ComposerMirror` 的 burst 滞后（VM 空、field 有字）仍能判为回放；非空副本必须自己证明是片段。

### A6 — 四处断言逐个换锚点

| # | 原断言 | 新断言 |
|---|---|---|
| ① | `handBack` 含 `ReplaceText(text = text` | 含 `ReplaceText(text = text, caretOverride = text.length`；**并新增一臂**：`restoreComposerAfterRefusal` 的函数体必须走 `requestComposerCommand(` + `ReplaceText(` + `caretOverride = text.length` |
| ② | `substring(dropAndClear).contains(...)` | `blockAt(source, branch)` **括号配对**取 `if (verdict == DROP_AND_CLEAR) { … }` 的块体，再查块内有 `clearComposerInput()` |
| ③ | `stampClearedBody` 体含 `shouldRestampClearedBody(` | ① 它是 `if` 条件；② 用 `parenthesisedAt` 取出**整个条件表达式**，要求首尾就是那次调用且不含 `\|\|` / `&&`（M12 证明「是 if 条件」本身仍可被 `if (helper(…) \|\| true)` 骗过）；③ 写点在该 `if` 块内 |
| ④ | 400 字符窗口内 `isFragmentOfClearedBody(` | 判据抽成纯函数 `shouldKeepClearedStamp(incoming, clearedText)`，形状臂只查 `onValueChange` 里「问了这个函数 + 传了 `tfv.text` 与 `lastClearedText`」，**决策本身由 JVM 用例钉** |

新增 `blockAt` / `parenthesisedAt` 两个配对辅助（跳过注释、字符串、raw string），所有臂都从「切到文件尾」改成「取真实块」。

---

## 4 这改变什么用户行为？

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 发 `hello world` 后 1.5 s 内打 `he` | 被抹掉，且窗口内反复被抹 | 正常输入 |
| 发长消息后 IME 逐字回放整段 | 第一次落地 1-3 字后就被清（若首字即 ≥4 字则整段清） | 中间态放行，整段重建完成时一次清掉 |
| 拒发（无 provider / 压缩中 / 流式中编辑）后补标点 | 整段回填内容被抹 | 正常输入 |
| 发 `hello world` 后 1.2 s 内再发 `hello` | 第二条不重设指纹 → 自己的回放漏网 | 第二条重设指纹 + 拿到完整窗口 |
| 长正文后 1.5 s 内点联想条上屏正文中段的一个词 | 被静默丢弃 | 正常输入 |
| VM 滞后一帧、框里是用户自己的 `hz` | 可能被连带抹掉 | 不抹 |

---

## 5 验证

### 5.1 本地 JVM 装置（`/var/minis/shared/work/r1118b/harness/`）

- `run.sh` —— 策略 54 条 + 接线 9 条：**OK (54 tests)**
- `run2.sh` —— `B3RenderInputShapeTest` + `ChatRefusedSendRestoreTest` + `ComposerMirrorWiringTest` + `ComposerClearWiringTest`：**OK (28 tests)**
- `chat_ui_interaction_check.py .` —— `CHAT-UI-INTERACTION GATE OK`
- `discarded_return_guard.py .` / `chat_session_id_binding_check.py .` —— 均绿

编译失败即 `exit 2`（不跑 JUnit），避免陈旧 classes 造成假绿。

### 5.2 变异负控（`mutate.py`，15 臂全 RED-PRECISE）

| 臂 | 重新引入的缺陷 | 变红的用例 |
|---|---|---|
| M1 | A1：Arm B 回到「片段即清」 | `typing a prefix of the sent message right after the send is never wiped` |
| M2 | A1：收敛函数恒真（速率判据失效） | 同上 |
| M3 | A3：中段规则对空框开放 | `a suggestion chip word from the middle of the body is not a residue` |
| M4 | A4：来源标记被忽略 | `a user send that happens to be a prefix of the previous message restamps` |
| M5 | A4：所有清空都当守卫清空 | 同上 |
| M6 | A5：`&&` 回退成 `\|\|` | `a lagging view model copy never authorises a wipe of the users own text` |
| M7 | A2：`commitComposerBody` 不清指纹 | `a composer commit clears the replay fingerprint` |
| M8 | A4 接线：守卫不置标记 | `the replay wipe tells the funnel why it is clearing` |
| M9 | A6-①：丢掉 `caretOverride` | `the restore helper hands the text back through the composer intent channel` |
| M10 | A6-①：`restoreComposerAfterRefusal` 退回裸 `setInputText` | 同上 |
| M11 | A6-②：`DROP_AND_CLEAR` 分支不清空 | `an accumulated replay is wiped through the clear funnel` |
| M12 | A6-③：`if (helper(…) \|\| true)` | `the clear funnel does not re-stamp an un-winding replay` |
| M13 | A6-④：指纹在任意非空编辑上被丢 | `the stamp survives an edit that is still a piece of the cleared body` |
| M14 | A1：Arm A 去掉短语判据 | `a single edit carrying a phrase is a residue even at a human-looking delay` |
| M15 | A1：Arm A 去掉速率判据 | `a four character prefix is still a residue` |

**M12 是本轮最有价值的一条**：第一次跑它时结果是 **BLIND** —— 我写的「它是 if 条件 + 写点在块内」两臂都被 `if (helper(…) || true)` 满足。这正是 A6 报的那个病（锚点落在被测量分支之外）在我自己的新门上复现。改法 = `parenthesisedAt` 取出整个条件表达式，要求它首尾就是那次调用。

### 5.3 修门时踩到的距离窗口（第三次）

把 A2 的说明写进 `commitComposerBody` 的 lambda **内部**，把 `consumePendingCaret()` / `composerFunnelCaret(` 挤出 `ComposerCommandHandoffTest` 的 700 字符窗口（实测偏移 175 → 882，窗口内 -1）。按既有教训（406 字符 / 400 字符两次），说明移到 lambda **外面**，窗口内锚点回到 175 / 260。

---

## 6 未修 / 登记

- **A7**（成员行 `Ellipsis` 尾截断）—— 不在本支范围，台账已登记 P2。
- **中间态残留**（Arm B 放行的窗口内，框里会短暂留着正文前缀）—— 刻意取舍，不是缺陷；`shouldKeepClearedStamp` 保证用户一旦输入无关字符指纹立刻失效，最多 1.5 s。
- **中段恢复的回放不收敛**——IME 从缓冲区中段恢复时不会重建整段，因此不会被 Arm B 清掉。刻意：那类回放留下的是短片段，比吞按键便宜。

## 7 文件清单

```
src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ComposerClearResiduePolicy.kt   (+ ~150)
src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt                (+ ~40)
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResiduePolicyTest.kt
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResidueWiringTest.kt
src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ChatRefusedSendRestoreTest.kt
fix-reports/FIX-1118B-A-composer.md
```

未触碰 `scripts/scan/**`、`ChatPromptAndTools.kt`、`ProtectedZoneGuard.kt`、`FastioOffloadHandler.kt`（B/C 两支独占）。

## 8 真机待验

1. 发一条长消息后立刻开始打同前缀内容 → 输入框正常累积，不被清空。
2. 长消息发送后让 IME 自行回放（第三方中文输入法、失焦收键盘）→ 中间态可能短暂出现，整段重建完成时被一次清掉，日志 `ComposerResidue` 出现 `dropped a late IME replay of the cleared body`。
3. 无 provider / 压缩中状态下点发送 → 文本回填后补标点、退格，内容不再消失。
