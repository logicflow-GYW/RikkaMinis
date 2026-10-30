# FIX — §R5-EF F-F-07：`$ … $` 判据单源化（流式与静态两条渲染路径共用一份）

- **分支**：`fix/ff07-math-currency-unify`
- **基线**：`main` @ `039c9ab2`
- **来源**：backlog `§R5-EF P2 登记` 末条「★ F-F-07 的第二价格网没覆盖聊天流式路径」（2026-10-29 `fix/r5f-markdown` 施工取证）
- **判级**：P2（用户可见面 = 渲染跳变，非数据/崩溃）→ 按「有人在动这两个文件时顺手带走」的触发条件执行

## 1. 症状

同一句 `$ … $` 文本在两条路径上被分类两次：

| 路径 | 实现 | 时机 |
|---|---|---|
| 静态 | `MarkdownParser.looksLikeMath`（private） | 消息落定后解析 |
| 流式 | `StreamingMarkdownText.looksLikeMath`（internal top-level；`MarkdownBlockModel:255/265` 也用它） | 逐帧增量渲染 |

三轮货币修复只落在静态那份：

1. `[r3-c / B09 F-13]` 货币判据提到运算符扫描**之前**（否则 `5-` 先命中 `-` 就成数学）+ 内容残留 `$` 的配对残渣守卫；
2. `[r5-f / F-07]` 带单位/量级后缀的价格区间（`$5k-$10k`、`$10/mo-$20/mo`、`$5B+$2B`、`US$5k-US$10k`）。

流式那份仍是旧的粗糙实现（字形表先扫、`latex[0].isDigit()` 后才拒绝），于是 `$5k-$10k` **流式时渲染成 KaTeX，落定后跳回美元** —— 一句话两份文档。

## 2. 根因

不是判据写错，是**同一判据存在两份实现**，且三轮回填只到了一份。两份同名 `looksLikeMath` 本身就是分流发生的证据（backlog 原文即如此判定）。可复用的形状：**同一决策被两条时间线（未完成/已完成）各自实现时，修复必须落在共享层，否则时间线越长、漂移越大**。

## 3. 修法

新增 `ui/markdown/MarkdownMathHeuristic.kt`（`internal object`，纯字符串逻辑、零 Android 依赖）：

- `looksLikeMath(content)` —— 静态那份的完整判决顺序搬过来（`$` 残渣 → `isCurrencyShaped` → `isCurrencyRangeShaped` → 字形扫描 → 首字符数字 → 边缘空白 → 短标识符 → 长度）；
- `isCurrencyShaped` / `isCurrencyRangeShaped` + `NON_CURRENCY_MATH_GLYPHS` / `MATH_GLYPHS` / `MONEY_RANGE_CONTENT` 一并迁入（原文注释逐条保留，含 ponytail 天花板与升级触发）；
- 新增 `isCurrencyShaped` 的空串守卫（原先靠调用点保证非空，迁成对象方法后必须自持）。

两个调用点改为**一行委托**：

```kotlin
// MarkdownParser.kt
private fun looksLikeMath(content: String): Boolean = MarkdownMathHeuristic.looksLikeMath(content)

// StreamingMarkdownText.kt
internal fun looksLikeMath(latex: String): Boolean = MarkdownMathHeuristic.looksLikeMath(latex)
```

`MarkdownBlockModel` 经后者自动同源，无需改动。

**方向选择（诚实记录）**：统一到**静态那份（更严）**，而不是把静态放宽到流式的口径。理由：静态是最终态，用户读完看到的永远是它；流式若更宽，跳变方向是「先说公式后变钱」，反过来「先说钱后变公式」更糟（用户会以为模型写错）。所以流式变严 = 少渲染一次注定要回退的 KaTeX。

**已知取舍（沿袭原判据，未改）**：`$5/2$`、`$5 > 3$` 这类**数字开头的真公式**仍被判成钱（`isCurrencyShaped` 的无字母规则）。这是 `[r3-c]` 早已记录的刻意取舍（「漏判公式仍显示自己的 `$…$`，误判价格会静默删掉用户正在读的 `$`」），本次只统一口径、不动取向。

## 4. 验证

### 4.1 共享判据表（本地，真实代码编译）

`kotlinc MarkdownMathHeuristic.kt + 表驱动 main` → **PASS=26 FAIL=0**：5 种后缀价格形态（`5-`/`5k-`/`10/mo-`/`5B+`/`5k-US`）+ 纯数字/空串全 `false`；`x`/`n`/`pi`/`abc`/`x+y`/`1+1=2`/`x^2`/`\frac{1}{2}`/`\pi`/`2^{10}`/`5-x` 全 `true`；残渣串 `5 - $10 and` 为 `false`。

### 4.2 变异臂（RED-AS-EXPECTED，证明缺陷真的存在过）

从 `HEAD~` 取出修复前的流式 `looksLikeMath` 原文，单独编译跑同一张表：

```
old said MATH for 5/5: [5-, 5k-, 10/mo-, 5B+, 5k-US]
old kept math for 3/3
```

即：**旧代码把全部 5 种价格形态判成数学**（这就是流式跳变的机制），同时不误伤真公式。臂带自检——若哪天缺陷消失，臂自己会 `IllegalStateException` 报「arm did not reproduce the drift」。

### 4.3 结构契约（防第三次漂移）

`MarkdownMathSingleSourceShapeTest`（JVM，读源码）：

1. Heuristic 里 `object` + 两张字形表 + 价格正则 + `fun looksLikeMath(` 恰 1 次；
2. `MarkdownParser.looksLikeMath` 200 字符窗口内必须出现 `MarkdownMathHeuristic.looksLikeMath(content)`，且**不得**出现 `charArrayOf` / `for (` / `MONEY_RANGE_CONTENT`（即「委托而非决策」）；
3. `StreamingMarkdownText.looksLikeMath` 同上（`latex` 口径）+ 旧粗表字面量 `"=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ"` 不得回归；
4. 全生产源码里 `fun looksLikeMath(` / `isCurrencyShaped(` / `isCurrencyRangeShaped(` 的每一处声明，其 200 字符窗口必须含 `MarkdownMathHeuristic.`，否则判红（堵「明天又写第三份」）。

为什么用 JVM 形状测试而不是新 `scripts/scan` 门：门注册要改 `scan_blocks.sh` 的 113 个编号头 + `scan_parallel.py` 的 `/113` 正则 + `test_scan.py` 计数，把 112 个块全改一遍的爆炸半径远大于收益；JVM 测试在 CI 里跑两遍（`testReleaseUnitTest` 分片），成本为零。

### 4.4 差分测试（两入口同答案）

`MarkdownMathParityTest`：同一张表分别喂 `MarkdownParser.parseWithMath(input).mathSpans`（静态）与 `collectInlineMathLatex(input)`（流式），**各自对字面量断言 + 两者互等**。三源断言是刻意的——只做「两者相等」的话，「两边错得一样」也能通过，而那正是这个 drift 活下来的方式。

### 4.5 既有测试相容性

复核了会对流式侧下断言的用例：`InlineLatexTest`（`$x$`/`$pi$`/`\frac{a}{b}`/`$5, and $x+y$`）、`InlineMathExtractionTest`（`$5`/`$1,000`/`\$5`/`| 月付 | $20|$ **3** |`）、`InlineParenMathTest`（`\(x+y\)` 不经过本判据）。收紧只影响「数字开头 + 运算符」这类原先流式走宽判据的串，上述用例无一依赖该口径。

### 4.6 门禁

本地：`scan.sh --jobs 3` **113 passed, 0 failed**；`test_scan.py --jobs 3` **ALL 349 CASES PASS**。

### 4.7 CI

- **run `40998818444`** @ `5fb058095f32` **success 14 min 45 s**（03:41:51Z → 03:56:35Z），attempt 1，`workflow_dispatch`；`head_sha` 与本地 HEAD **逐字符一致**。
- 27 步 = **25 success + 2 skipped**（`Move the rolling tag` / `Publish to Releases`，均 main 门控，分支上必 skip）。
- 日志 3,363,987 B / **NUL 0**：`Summary: 113 passed, 0 failed` ×2、`ALL 349 CASES PASS` ×2、`^e: file` **0**、真 ` FAILED$` **0**。
- 9 条新用例两遍全 PASSED（`MarkdownMathParityTest` 5 + `MarkdownMathSingleSourceShapeTest` 4）。
- **首轮红（诚实记录）**：`40997128602` failure @8.4 min，唯一失败 = `MarkdownMathSingleSourceShapeTest > no third implementation exists in production sources`（`: Java.lang.AssertionError: repo layout changed`）。根因是**测试自己的 cwd 假设**：Gradle 单测的 `user.dir` 是 `app/` 模块目录而非仓库根，裸 `File("src/...")` 解析不到；同文件另三例走 `readRepoFile` 的向上查找所以是绿的。修 = 该用例改成同一个向上查找（`repoRoot` lazy，断言 `src/android/app` 存在），修完本地用 `cwd=src/android/app` 模拟 CI 复跑，`declarations seen=5, offenders=[]`。
  ⇒ **可复用**：JVM 里读源码的形状测试一律按 `user.dir` 向上找仓库根，**不要 `File("src/...")` 直接用**——本地从仓库根跑永远绿，CI 从模块目录跑必红，而且只在第一个这么写的用例上暴露。


## 5. 残留（带触发条件）

- **R1 `isTablePipeArtifact` 仍只在流式侧存在**（`StreamingMarkdownText.kt:3454`），静态侧靠 `isCurrencyShaped` 顺带挡住 `20|` 这类内容。同族风险（两条路径各一套判据）但当前**结论一致**（逐案核对 `20|`、`20 |`、奇偶竖线）。｜触发条件：出现「表格里的 `$…$` 在流式与落定后渲染不同」的报告 ⇒ 把该判据也移进 Heuristic。｜方案：迁入 Heuristic + 加进差分表。
- **R2 数字开头的真公式仍被当钱**（`$5/2$`、`$5 > 3$`）：沿袭 `[r3-c]` 的刻意取舍，见 §3。｜触发条件：用户报「公式没渲染，显示成 `$5/2$`」。｜方案：把「含 `/` 且两侧都是数字」判为数学（需要同时评估是否误伤 `$10/mo-$20/mo`，后者已由 `isCurrencyRangeShaped` 拦在前面）。
- **R3 真机未验**：全部证据 = 编译级（CI）+ JVM 级（判据表 + 差分表 + 结构契约）+ 变异臂。跳变本身是渲染时序现象，沙箱无 Compose 运行时。｜触发条件：真机聊一句含 `$5k-$10k` 的话。｜方案：观察流式与落定两态是否一致；`$5k-$10k` 现在两态都应保持美元原文。
- **R4 静态路径行为零变化**（判据原文搬迁，顺序与注释逐条保留），因此不存在「修了流式、改坏静态」的第二风险面；`MarkdownCurrencyMathTest` / `MarkdownCurrencyRangeTest` 在 CI 中照旧为静态路径的回归网。
