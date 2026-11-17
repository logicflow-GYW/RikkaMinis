# FIX-INCR1117-T3 — 行内公式启发式把函数调用当成散文（B-F06）

分支 `fix/math-heuristic-callparen-1117`｜提交 `06bb64ba`｜基线 main `949566acbc91f165ab7eb6871d82cb825b856fe9`｜标签 `[incr1117-B F-06]`
来源：`/var/minis/shared/work/meta-audit/reports/incr1117/Incr_1117_B_Report.md` **B-F06**（判级 P1，上一轮 `[incr1116-B F-10]` 的次生回归）

---

## 1. 现象

带括号的三角函数/对数公式不再进入 KaTeX 分支，`$` 之间的内容按纯文本渲染：

```
$sin(theta) + cos(theta)$   →  行内公式消失（内容原样留在文本里）
$sin(alpha) + cos(x)$        →  同上
$log(err) + exp(x)$          →  同上
```

而 `$50 for 30-min or $100` 这种金额句**必须**保持散文——两条需求指向同一个计数器。

## 2. 根因

`looksLikeNaturalLanguage()`（`MarkdownMathHeuristic.kt:196-220`，改前）对每个 ASCII 字母 token 一律 `words++`，只是不让函数名去当「长词」：

```kotlin
words++
if (token.lowercase() !in MATH_FUNCTION_TOKENS && j - i >= 3) hasLongWord = true
```

`sin(theta) + cos(theta)` = `sin` / `theta` / `cos` / `theta` **四个词**，`theta`(5 字母) 命中长词 ⇒ `words >= 3 && hasLongWord` ⇒ 判成散文 ⇒ 不渲染。

上一轮 `[incr1116-B F-10]` 为了让 `50 for 30-min or ` 保持三词（`for`/`min`/`or`），把函数名**计入**词数；`min(…)` 与 `30-min` 在词法上被当成同一种东西，这就是回归的机制来源——修法与副作用共用同一个计数器，没有区分「函数调用」与「单位后缀」。

## 3. 规则（一句话）

> 一个 token 在 `MATH_FUNCTION_TOKENS` 里、且它后面（允许空白）紧跟 `(` 时，它是**函数调用**——公式的结构证据，**不计入 `words`，也不能当长词**；其余 token 行为不变（`30-min` 里的 `min` 没有括号，仍然计数）。

实现（`MarkdownMathHeuristic.kt:219-248`）：

```kotlin
val lower = token.lowercase()
var k = j
while (k < content.length && content[k].isWhitespace()) k++
val isFunctionCall = k < content.length && content[k] == '(' && lower in MATH_FUNCTION_TOKENS
if (!isFunctionCall) {
    words++
    if (lower !in MATH_FUNCTION_TOKENS && j - i >= NATURAL_LANGUAGE_MIN_WORD_LENGTH) hasLongWord = true
}
i = j
```

为什么不是「把函数名整类从词数里删掉」（那是上一轮的第一次尝试）：那会让 `50 for 30-min or ` 只剩两词 → 判成公式 → 两个 `$` 被吃掉。括号是唯一能把两者分开的词法证据。

KDoc 两处同步改写：token 表（「NOT excluded in general，CALL 形态例外」）与 prose guard 段（保留 `[incr1116-B F-10]` 的取舍说明，追加 `[incr1117-B F-06]` 说明为什么要这样补）。

## 4. 改动面

`git diff --stat 949566ac HEAD` = **2 文件 +89 / −18**：

| 文件 | 变化 | 内容 |
|---|---|---|
| `ui/markdown/MarkdownMathHeuristic.kt` | +54/−12 | 调用形态判定 + 两处 KDoc 改写（无新依赖、无新常量、`looksLikeMath` 入口契约不变） |
| `test/.../MarkdownMathHeuristicMoneyTest.kt` | +41/−0 | 5 条新 `@Test`（4 正 + 1 负控），**既有断言一条未改**（仅给既有用例补了一句 KDoc 说明调用形态例外，断言文本逐字未动） |

未碰：`scripts/scan/**`、其它 markdown 文件、UI 文件、调用点（`MarkdownParser` / 流式渲染均走同一个 `looksLikeMath`）。

## 5. 证据（全部可复跑）

装置：`/var/minis/shared/work/incr1117/jvm3/`（`run.sh` pre/post 双臂 + `mutate.sh` 两个变异 + `sweep/Sweep.kt` 差分扫描）。
生产方式编译**真实生产文件**（不改逻辑副本）；探针 `MathCallParenProbe.kt` 镜像 `MarkdownMathHeuristicMoneyTest` 的每一个启发式级臂（仓库测试还驱动 `collectInlineMathLatex` / `MarkdownParser.parseWithMath` —— 3.5k 行渲染器，沙箱编不了，那两条集成臂留在 CI，见 §6）。

### 5.1 改前基线（pre 臂，生产文件取 `949566ac`）

```
COMPILE=OK
Tests run: 10,  Failures: 3
1) aMixedFunctionCallExpressionIsMath
2) aFunctionCallWithAParenthesisedArgumentIsMath
3) aLogAndExpCallExpressionIsMath
```

**哪些已绿（改前）**：7 组——散文句（`50 for a 3-day pass or ` / `50 for 30-min or ` / `50 for 15-sec or `）、`log x + log y`/`sin x cos x`/`max a b`/`lim n to inf`/`O(n log n)`、大小写与 exact-token（`LOG`/`Max`/`maximum value here`）、`x - y`/`x+y`/`x^2`/`1+1=2`/`\frac{1}{2}`/`\pi r^2`/`\sum …`/`price`、7 条货币形（`5-`/`10+`/`5k-`/`10/mo-`/`5B+`/`5k-US`/`5 - 3`）、`sin (x) + cos (y)`、负控 `sin (the angle) of a circle`。
**哪些红**：正上三条（`test(alpha)` 那组）——与 B-F06 报告的形态一致。

注：`sin (x) + cos (y)` 与负控在改前**已经是绿**（`sin`/`cos` 不做长词，剩余 `x`,`y` 或 `the/angle/of/a/circle` 决定结果），它们不是红→绿，而是「改完仍必须绿」的回归臂；任务书把它们列在新增组，实测为改前即绿，如实记录。

### 5.2 改后（post 臂，工作树）

```
COMPILE=OK
OK (10 tests)
```

### 5.3 变异负控（精度）

| 变异 | 改动 | 结果 | 判读 |
|---|---|---|---|
| m1 | 删掉修复（`isFunctionCall = false && …`） | `Tests run: 10, Failures: 3` — 正是 5.1 那三条 | 三条新臂对修复**有牙**，且红集精确（不是粗网） |
| m2 | 把规则放宽成「任何函数 token 都算调用」（不要 `(`） | `Tests run: 10, Failures: 1` — `theReportedProseSentenceStaysText` | 货币/单位句那组对「放宽」有牙，证明规则没放过宽 |

### 5.4 差分扫描（无意外翻转）

45 条输入同时过 pre/post 两臂（`sw-pre.txt` vs `sw-post.txt`），`diff` 只出现 **3 行**：

```
-TEXT  <sin(theta) + cos(theta)>   +MATH
-TEXT  <sin(alpha) + cos(x)>       +MATH
-TEXT  <log(err) + exp(x)>         +MATH
```

其余 42 条逐字节相同，含 `the max(a, b) value is five`（TEXT）、`printf(a, b, c)`（TEXT，`printf` 不在 token 表仍计数）、`sin\t(x)`（MATH）、`det(A) = 0` / `mod(a, b) = 0` / `sin(2x) + cos(2x) = 1`（MATH）。

## 6. CI（停止节点）

- run **#3196 / `44003535782`** @ `06bb64ba4df36947ed798414448dfd1209ffa275`（与本地 HEAD、`origin/fix/math-heuristic-callparen-1117` 逐字符一致）
- 五 job 全 `success`：`scan-gate` / `build-apk` / `unit-tests` / `lint` / `publish`
- `unit-tests`：**9303 PASSED / 0 FAILED**（`grep -cE ' PASSED$'` = 9303，`' FAILED$'` = 0）
- 新测试名逐条搜到 `PASSED`（同一 job 日志）：
  ```
  MarkdownMathHeuristicMoneyTest > a function call with a parenthesised argument is math PASSED
  MarkdownMathHeuristicMoneyTest > a mixed function call expression is math PASSED
  MarkdownMathHeuristicMoneyTest > a log and exp call expression is math PASSED
  MarkdownMathHeuristicMoneyTest > whitespace before the call parenthesis still counts PASSED
  MarkdownMathHeuristicMoneyTest > a prose sentence with a call shaped head stays prose PASSED
  MarkdownMathHeuristicMoneyTest > a unit abbreviation is a word, not a function call PASSED
  ```
  （该类在日志中共 19 行，19 行全 PASSED）
- `scan-gate`：`Summary: 115 passed, 0 failed`；日志另有 `scripts/scan/** untouched in this push — skipping the fixture self-test` ⇒ **本批不含夹具跑过的证据**，如实标注（未改 `scripts/scan/**`，按 workflow 设计跳过）。

## 7. 边界与取舍

- **只认带括号的调用**：`sin x`（无括号）仍按 token 计词——它靠「无长词」是通过的，不需要额外规则；这样也避免把 `sec` 这类同时是单位缩写的词从散文判据里挖空。
- **不放宽到「任何函数名」**：m2 变异证明那会立刻复活 `[incr1116-B F-10]`（`50 for 30-min or ` 变公式）。
- **`sin (the angle) of a circle` 仍是散文**：跳过 `sin` 之后 `the/angle/of/a/circle` 五个长词照样触发判据，负控臂钉死这条。
- **括号内长词仍计数**（`theta`、`err`、`angle`）：只跳过函数名本身，没有把整段参数区吞掉——这是负控能站住的原因。
- 未新增常量/依赖，`NATURAL_LANGUAGE_MIN_WORDS=3`、`MIN_WORD_LENGTH=3` 未动。

## 8. 遗留

- 未合 main（按纪律与任务书 §5.4，分支 CI 绿即停止节点）。
- 真机未逐条验（本改动是纯字符串判定，`looksLikeMath` 两条入口共用同一实现，`MarkdownMathParityTest` 的差分仍由 CI 覆盖）。若要真机确认，发一条含 `$sin(theta)+cos(theta)$` 的消息看是否渲染成公式即可。
- 装置与日志留在 `/var/minis/shared/work/incr1117/jvm3/`（`arm-pre/junit.log`、`arm-post/junit.log`、`mut-m1|m2/junit.log`、`sw-pre.txt`、`sw-post.txt`），可复跑：`sh run.sh pre|post`、`sh mutate.sh m1|m2`。
