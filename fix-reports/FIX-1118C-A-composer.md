# FIX-1118C-A — composer 残留判据两处回咬

## 1 范围

| 项 | 值 |
|---|---|
| 分支 / worktree | `fix/composer-residue-1118c` / `/var/minis/shared/work/r1118c/composer` |
| 基线 | `97768739`（= 合并 1118B 的 main） |
| 触发 | 外部审计 `reports/r1118b/Fix_1118b_A_Composer_Report.md` 的 **A F-01**、**A F-02**（判级 P1） |
| 触碰文件 | `ComposerClearResiduePolicy.kt`、`ChatInputArea.kt`、`ComposerClearResiduePolicyTest.kt`、`ComposerClearResidueWiringTest.kt`、`ChatRefusedSendRestoreTest.kt`（核对后未改）、本报告 |
| 未触碰 | `scripts/scan/**`、`ChatPromptAndTools.kt`、`ProtectedZoneGuard.kt`、`ChatScreen.kt`、任何 i18n |

**这改变什么用户行为？** ①发送后 1.5 s 内，落在**空框**里的单次 4–11 字上屏，只有占已清正文 ≥50%（或本身 ≥12 字）才会被当回放丢弃 —— 联想词点击、滑行输入、短听写不再被静默吞掉；②拒发把正文交回输入框后，用户紧接着用拼音/日韩输入法组词，不再被「发送后 500 ms」窗口整次丢弃。

## 2 逐条先证结论

### A F-01 — **真**

`ComposerClearResiduePolicy.kt` 的 arm A（空框分支）实现为

```kotlin
if (boxEmpty) {
    val longEnough = incoming.length >= minReplayChars
    val phrase = incoming.length >= COMPOSER_SINGLE_EDIT_PHRASE_CHARS
    return if (longEnough && (phrase || isSuperhumanTypingRate(incoming.length, msSinceClear))) DROP else NONE
}
```

`isSuperhumanTypingRate` = `chars*1000 > elapsed*12`，于是 4–11 字的单次上屏在发送后 `0 .. len*1000/12 ms` 内一律 `DROP`：4 字到 334 ms、5 字到 417 ms、6 字到 500 ms、11 字到 917 ms；`len ≥ 12` 时整个 1500 ms 窗口都杀（短语臂）。**报告描述与代码一致。**

报告指出的覆盖缺口也核实成立：既有用例 `a single suggestion chip word that prefixes the body is not a residue` 用 `700L` / `800L`，对应速率 6 字/700 ms = 8.6 char/s、5 字/800 ms = 6.25 char/s，**恰好在 12 char/s 红线之下**，所以从未进入误杀带。

### A F-02 — **真**

`ChatInputArea.kt` 中 `lastSendTimeMs` 的全部出现（基线 `97768739`）：声明 `:242`、赋值 `:667`、`:1848`（两条发送路径）、读取 `:1888`（T217-2 前置守卫）。**没有任何一处复位**。

`commitComposerBody` 只清 `lastClearedText = null` / `lastClearedAtMs = 0L`，而拒发回填走 `restoreComposerAfterRefusal` → `ComposerCommand.ReplaceText(..., caretOverride = text.length)` → 该 lambda。于是回填之后 500 ms 内，任何 `tfv.composition != null` 的编辑（拼音/日韩组词）在 `:1888` 被 `return@BasicTextField` 整次丢弃。**报告描述与代码一致。**

## 3 修法

**F-01（3 处，全部在纯策略层）**
1. 新增常量 `COMPOSER_SINGLE_EDIT_MIN_COVERAGE_PERCENT = 50` 与纯函数 `coversEnoughOfClearedBody(incoming, clearedText)`：两侧先过 `stripImeTrailingNoise`，再判 `incomingNorm.length * 100 >= clearedNorm.length * 50`。用去噪后的长度，否则 IME 自己补的尾标点会让正文显得比一次编辑更长。
2. arm A 的判据由「短语 OR 速率」改为 **「短语 OR（占比 AND 速率）」**：

```kotlin
val covers = coversEnoughOfClearedBody(incoming, clearedText)
return if (longEnough && (phrase || (covers && isSuperhumanTypingRate(incoming.length, msSinceClear)))) DROP else NONE
```

  短语臂（≥12 字）**原样保留**，不设占比上限 —— 「一次编辑 12 字以上」本身已经是「输入法不可能一次提交」的证据。
3. KDoc 对齐实现：`hasReplayConverged` 的「为什么用整段不用比例」段落加一段显式区分 —— arm B 的收敛仍然要求**整段**，arm A 的占比只回答「这一次编辑到底够不够大」，两者不矛盾（上一轮的教训：KDoc 与实现不能各说各话）。

**F-02（2 处）**
1. `commitComposerBody` 内加 `lastSendTimeMs = 0L`，写在清指纹的同一行附近、`viewModel.setInputText(text)` 之前。理由（为何该复位）写在 lambda **外**的既有 A2 KDoc 里，不新开注释块 —— 形状门对 `commitComposerBody` 与 `stampClearedBody` 都量带距离的窗口（这一项已经踩过三次）。
2. 把 T217-2 的内联条件抽成纯函数 `isPostSendImeResidue(hasComposingRegion, msSinceSend, incomingEmpty)`（常量 `COMPOSER_POST_SEND_IME_WINDOW_MS = 500L`）。判据完全保留（含 `msSinceSend < window` 对倒时钟的行为），这样「窗口被 `commitComposerBody` 解除武装」这一半可以在 JVM 上被钉死，而不是靠源码形状猜。`onValueChange` 只改调用形态，逻辑等价。

**改动量**：`ComposerClearResiduePolicy.kt` +2 常量、+2 纯函数，arm A 判据加 1 行；`ChatInputArea.kt` +2 行赋值、守卫改调用（7 行）。

## 4 证据

### 本地 JVM 装置
`/var/minis/shared/work/r1118c/harness/run.sh`（`kotlinc -no-stdlib -cp jvm-libs/kotlin-stdlib-2.1.0.jar`，`nice -n 10`），**OK (65 tests)**，覆盖 `ComposerClearResiduePolicyTest` / `ComposerClearResidueWiringTest` / `ChatRefusedSendRestoreTest`。

`ChatInputArea.kt` 依赖 Compose，沙箱无 Android SDK 无法编译 —— 其改动的证据是 **KDoc 与形状断言 + 括号平衡核对（与基线逐字符对照，改动只多一对配平括号）**，真实编译验证交给 CI（见下）。

### 新增/修订用例
| 用例 | 断言（字面量） | 类别 |
|---|---|---|
| `a four character prefix of a long body is no longer dropped` | `"hell"` vs 42 字正文 → `NONE` | F-01 新增（**修订**原期望 `DROP`） |
| `a chip word covering under half the body is not a residue` | `"hello"` vs `"hello world"` @350 ms → `NONE` | F-01 新增 |
| `the whole body replayed at the same delay is still a residue` | `"hello world"` vs `"hello world"` @350 ms → `DROP` | F-01 新增 |
| `a short body replayed whole is still dropped` | `"hello"` vs `"hello"` @350 ms → `DROP` | F-01 新增 |
| `the coverage floor is half the body ignoring IME trailing noise` | 边界 5/11 假、9/11 真、5/10 真、6/13 假；常量 50 | F-01 新增 |
| `the boolean facade agrees with the verdict` | **修订**：`drop("hell")` 由真改假，并补 `drop(body)` 真 | F-01 修订 |
| `a restored body does not arm the post-send IME window` | 42 000 ms → 假；0/499 → 真；500 → 假；无组合 / 空提交 → 假；常量 500 | F-02 新增 |
| `a composer commit disarms the post-send IME window` | `commitComposerBody` 体内含 `lastSendTimeMs = 0L` 且在 `setInputText(text)` 之前；守卫以 `hasComposingRegion = tfv.composition != null` + `msSinceSend = now - lastSendTimeMs` 调用纯函数 | F-02 新增（形状） |

任务书要求的 5 条期望值逐条对上：`hello`/`hello world`@350 → `NONE`；`hello world`@350 → `DROP`；短正文整段 @350 → `DROP`；12 字以上前缀 @1200 → `DROP`（既有 `a single edit carrying a phrase is a residue even at a human-looking delay`，本轮未改且仍绿）；中段词 `"there"` vs `"I'll be there in a moment"` → `NONE`（既有 A3 用例，仍绿）。

### CI
- 分支 run：**TBD-待补**
- `head_sha` 与分支 tip 逐字符一致：**TBD-待补**
- 五 job 全 success：**TBD-待补**

## 5 变异负控

装置 `/var/minis/shared/work/r1118c/harness/mutate.py`：快照 → 变异 → 跑 `run.sh` → **`finally` 还原** → sha256 逐字节校验（不做 `git checkout --`）。

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | 删掉 arm A 的占比门（`(covers && rate)` → `rate`） | **RED-PRECISE** red=3 |
| M2 | 占比恒真（`coversEnoughOfClearedBody` 返回 `true`） | **RED-PRECISE** red=4 |
| M3 | 删掉 `lastSendTimeMs = 0L` | **RED-PRECISE** red=1 |
| M4 | T217-2 改回内联条件（绕过纯函数） | **RED-PRECISE** red=1 |
| RESTORE | 还原后四文件 sha256 与基线一致 | **OK** |

M2 的 red=4 比 M1 多一条 `the boolean facade agrees with the verdict`：占比恒真时 `drop("hell")` 翻转，正是本臂要证的副作用，不是精度问题。

## 6 未修与取舍

1. **短片段不再由 arm A 丢弃（已登记的取舍）**：4 字对 42 字正文、或正文尾部一段只被回放一次，在空框里不再 `DROP`，框里会先出现一小段残留；当回放**收敛到整段**时由 arm B 整框清掉（`DROP_AND_CLEAR`），1.5 s 窗口外则按原设计放手。代价是「短暂残留片段」，收益是不再吞联想词/滑行/听写 —— 与代码自身声明的优先级一致（吞一个键比留一段残文更糟）。原用例 `a four character prefix is still a residue` 因此改为期望 `NONE`，不是把断言改成能过的样子：覆盖它的能力整体迁移到 arm B。
2. **12 字以上的短语臂不设占比上限**：一次编辑 12+ 字本身就是「输入法不可能一次提交」的证据，再叠占比只会把长正文的整段回放放回去（任务书的明令）。
3. **`commitComposerBody` 复位发送窗口的连带效应**：发送后 500 ms 内如果发生一次由应用驱动的正文提交（`/` 命令、`@` 补全、分享注入、拒发回填），T217-2 窗口不再压制组合态编辑。内容判据（`composerResidueVerdict`）仍然独立运行，且这些路径写进来的都是用户自己的正文，因此代价可控；此条写入 KDoc 而非注释块。
4. **F-01 的上界（占比 ≥50%）没有再放宽**：≤1/4 等于不修（4 字正文里 3 字也能过），≥80% 会把 5 字正文的 4 字整段回放放回去。

## 7 文件清单

| 文件 | 变化 |
|---|---|
| `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ComposerClearResiduePolicy.kt` | +2 常量（覆盖率 50、发送后窗口 500）、+2 纯函数（`coversEnoughOfClearedBody`、`isPostSendImeResidue`），arm A 判据接入，KDoc 对齐 |
| `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt` | `commitComposerBody` 复位 `lastSendTimeMs`；T217-2 改调 `isPostSendImeResidue`；KDoc 追加 F-02 理由 |
| `src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResiduePolicyTest.kt` | +7 用例，修订 1 用例（四字前缀 / 门面） |
| `src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResidueWiringTest.kt` | +1 形状用例（F-02 接线） |
| `fix-reports/FIX-1118C-A-composer.md` | 本报告 |

## 8 复现步骤

```sh
# 本地 JVM 装置（65 用例，含新用例与变异负控）
sh /var/minis/shared/work/r1118c/harness/run.sh
nice -n 10 python3 /var/minis/shared/work/r1118c/harness/mutate.py

# CI
cd /var/minis/shared/work/r1118c/composer
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch
```

真机待验（本轮未验证，如实说明）：发送一条 5–11 字消息后立即点击联想词 / 滑行输入该消息的首词，输入框应**保留**该词（日志不再出现 `ComposerResidue … dropped a late IME replay`，或在更长正文的整段回放时仍出现）；发送后触发一次拒发回填，紧接着用拼音组词，输入应**保留**。
