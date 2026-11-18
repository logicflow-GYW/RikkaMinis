# FIX-2D-1119-6 · MarkdownMathHeuristic 误判含括号参数的公式为自然语言

- 分支：`fix/markdown-math-1119`（基线 `b82b2963` = 本批前 main）
- 提交：`71685897`（实现 + 测试）
- 来源：`meta-audit/reports/r2d1119/6_Chat_Runtime_Report.md` **F-03（P1，concrete）**
- 判级台账：`meta-audit/r2d1119/TRIAGE.md`（6 F-03）

## 1 范围

只碰 `ui/markdown/MarkdownMathHeuristic.kt` + 该启发式的两个测试类（`MarkdownMathProseArgTest.kt`、新增 `MarkdownMathProseScopeTest.kt`）。未碰 `scripts/scan/**`、未碰任何 `strings.xml`、未碰其他支的域。

**这改变什么用户行为？** 一条 `$ … $` 片段，其**调用括号里的参数名**恰好是英语虚词（`at` / `not` / `for` / `in` …）时，现在会渲染成数学公式；改动前它被当成自然语言句子，原样以 `$…$` 文本显示（公式不渲染、两个 `$` 留在屏幕上）。反向行为也同时被钉住：散文虚词落在**括号外**的句子仍然是文本，价格句仍然不被 KaTeX 吞掉。

## 2 逐条先证

### F-03（P1）成立，且是三个可复现的误判

`looksLikeNaturalLanguage` 的判据 KDoc（`MarkdownMathHeuristic.kt`）声明了两道网：

- Net 1 = [PROSE_FUNCTION_WORDS]：**计数无关**的英语虚词网，为 `100 + min(fee) total ` 而加（`[incr1117-B F-11]`）；
- Net 2 = 词数 + 长词门，**已经**跳过整个调用组（`name` + 平衡 `( … )`，`[incr1117-B F-06]` / `[F-11]`）。

Net 1 的实现是 `containsProseFunctionWord(content)`，参数是**原文**；Net 2 的扫描循环自己跳过调用组。两道网的**豁免范围不一致**：Net 2 认为「括号里的参数是公式结构、不是词」，Net 1 却把括号里的参数当散文证据读。这就是 F-03 的根因——不是词表问题，是**范围不一致**。

修前装置实测（`shared/work/r1119/math-jvm/run.sh`，JUnit 逐条打印）：

| 样本 | 修前 `looksLikeMath` |
|---|---|
| `P(at = 0)` | `false`（误判，`at` 是参数名） |
| `Q(x) = 1 - P(not x)` | `false`（误判，`not` 是参数名） |
| `sum(x for x in S) > 0` | `false`（误判，`for`/`in` 是参数） |

三例的虚词**全部落在调用括号内**，公式本身有明确的数学符号（`=`、`>`）。修复前的装置里这三条正是唯一变红的用例，其余 22 条全绿——误判面与报告点名的范围一致。

### F-01（P3）不修

haze 采样假设已被真机证伪（用户手动验证玻璃能模糊内容），按台账 P3 永不动，本支未碰顶栏任何文件。

### F-02 / F-04（P2）不修

`MoveToCapsuleGate` KDoc 字段名漂移、`ChatRepository` 预览口径，均属其他支的域，本支未碰、未顺手修。

## 3 修法

**让 Net 1 的扫描范围等于 Net 2 的豁免范围**——一句话：散文虚词只读**括号外**的文本。

新增纯函数 `contentOutsideCallGroups(content): String`：把每个**调用组**（`name` + 紧跟的平衡 `( … )`，允许 `name (` 中间的空白，与 `[callGroupEnd]` 逐字一致）中的**参数部分**替换成一个空格，**保留调用名本身**。`callGroupEnd` 返回 `-1` 的非平衡输入不豁免（半截流式输入 `sin(x and` 的词仍然参与判定，fail-safe 方向与既有行为一致）。

`looksLikeNaturalLanguage` 的 Net 1 改为 `containsProseFunctionWord(contentOutsideCallGroups(content))`；Net 2 的扫描循环一字未改（它本来就自己跳调用组）。**没有扩大虚词表、没有放宽词数门、没有改任何常量。**

一个共享的「调用组」概念同时服务两道网，是这条修法的关键性质：Net 1 不可能豁免一个 Net 2 仍在读的区域，也不可能去读一个 Net 2 已豁免的区域。

**代价（写进 KDoc，不藏）**：`x + sin(the angle) + 3` 由文本变为公式。括号里的 `the` 不再能作为散文证据反对公式；一句想被拒的句子必须把散文写在调用**之外**（`x + sin(the angle) of a circle`、`100 + min(fee) total ` 仍然被拒）。这是 F-03 的同一判据从另一侧读：参数是参数。

既有测试 `MarkdownMathProseArgTest.a function word inside the parentheses is still prose evidence` 断言的正是这个被修正的模型，按任务书「该测试本身断言的是错的模型」处理：改名并重钉为 `a function word inside the parentheses is no longer prose evidence`（`math("x + sin(the angle) + 3")` + 保留 `text("x + sin(the angle) of a circle")` 这条负控），类 KDoc 的判据 2 同步改写。**其余既有断言一字未改。**

## 4 证据

### 本地 JVM 装置（`shared/work/r1119/math-jvm/run.sh`）

`kotlinc -no-stdlib -cp jvm-libs/kotlin-stdlib-2.1.0.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar`，`nice -n 10`，单个 JVM，编译失败即 `exit 2`（`kotlinc | tee` 在 ash 下退出码来自 tee，会让 java 拿上一次的 classes 跑出假红/假绿）。

修前：22 条 PASSED / **3 条 FAILED**（正是上表三例）。
修后：**22 条 PASSED / 0 条 FAILED**，逐条名单：

`MarkdownMathProseArgTest`（14）：a sum of three calls with multi-letter arguments is math · a definition whose head is an ordinary identifier is math · a call with several comma separated arguments is math · the already fixed two-call formula stays math · nested calls are math · an unbalanced call still renders instead of being called prose · a money sentence with a min call and a trailing function word is text · a unit abbreviation call does not rescue the sentence · the older money sentences stay text · **a function word inside the parentheses is no longer prose evidence** · currency shapes are still money · a word-free call expression is still math · two long words with no function word stay math · latex still wins over every prose signal

`MarkdownMathProseScopeTest`（8）：a probability whose argument name is a prose word is math · a complement probability with a prose word in the second call is math · a set builder sum whose arguments carry prose words is math · a function word outside the call group is still prose · a sentence with a call and prose outside the call stays text · formulas with no prose word keep their old verdict · an unbalanced call is still fail-safe for the prose net · a prose word inside a group that is not a call is still prose

### 分支 CI

- run **#3279** / `44363198351`，`head_sha` = `71685897d11351324b442f54ed38e7d850c0a3af`（实现提交）：**五 job 全 success**（lint / build-apk / unit-tests / scan-gate / publish）。
- unit-tests 日志 **9758 条 `PASSED` 行 / 0 条真失败**（4 条含 `FAILED` 字样的行全是用例名里带 FAILED 的 PASSED 用例）。本支用例逐条在列：
  - `MarkdownMathProseScopeTest`（8）：a probability whose argument name is a prose word is math · a complement probability with a prose word in the second call is math · a set builder sum whose arguments carry prose words is math · a function word outside the call group is still prose · a sentence with a call and prose outside the call stays text · formulas with no prose word keep their old verdict · an unbalanced call is still fail-safe for the prose net · a prose word inside a group that is not a call is still prose
  - `MarkdownMathProseArgTest`（14，含改名后的那一臂）：a function word inside the parentheses is no longer prose evidence · 其余 13 条原样
- scan-gate：`Summary: 115 passed, 0 failed`；日志同时有 `scripts/scan/** untouched in this push — skipping the fixture self-test`（本支未动门，绿不含夹具自测证据，符合机制）。
- publish：`Stage release asset` success，`Move the rolling tag android-latest` 与 `Publish to Releases` 两步 **skipped**（main 门控，分支不发版）。

> 报告提交会让分支 tip 前进，因此另对**最终 tip** 再 dispatch 一次并逐字符核对 `head_sha`——那次 run 的结论见会话回报（报告自身无法写出它自己的 run id：写进去会让 tip 再前进，循环不止）。

> 按停止节点：五 job 绿 + `head_sha` 与分支 tip 逐字符一致 ⇒ 本支**不合 main**，停在这里等放行。

## 5 变异负控（4 臂，全 RED-PRECISE）

装置 `shared/work/r1119/math-jvm/mutate.py`：变异前已 commit；恢复走**快照 + `finally` 还原 + sha256 校验**（不使用 `git checkout --`）；基线先跑一次确认 22 条全绿再变异。

| 臂 | 变异 | 变红用例 | 结果 |
|---|---|---|---|
| M1 | Net 1 回到原文扫描（修法回退） | 3 条 F-03 样本 + 重钉的 `a function word inside the parentheses is no longer prose evidence` | RED-PRECISE |
| M2 | 扫描范围被清空（网彻底失效） | `a function word outside the call group is still prose`、`a money sentence with a min call and a trailing function word is text`、`a unit abbreviation call does not rescue the sentence`、`the older money sentences stay text` | RED-PRECISE |
| M3 | 只挖掉第一个调用组（范围收得不够） | `a function word outside the call group is still prose`、`a money sentence with a min call and a trailing function word is text` | RED-PRECISE |
| M4 | 用**函数名表**代替调用形状决定豁免（`P`/`Q`/`sum` 不再豁免） | 3 条 F-03 样本（`sin`/`min` 在表内，其余臂不受影响） | RED-PRECISE |

变异臂的期望集是**判据推出来的**，不是跑出来再拟合：M2 只红在「Net 1 是唯一否决者」的句子（`10 min - 20 max or` 的 `or` 只有 2 个字母，永远当不了长词）；M3/M4 的差异恰好把「调用形状」与「函数名表」两种判据分开。`RESTORE-SHA: OK 3ab51fb18254e448…`，变异未烧进提交。

## 6 未修与取舍

- **F-01（P3）**：haze 采样假设，已被真机证伪，不动。
- **F-02 / F-04（P2）**：其他支的域，本支未碰；`ChatRepository` 预览口径（`probable`）登记不修。
- **`$a and b$` / `$p or q$` / `$f(x) = 1 if x > 0$` 仍然判为文本**：这些虚词在**任何**括号之外，属于 KDoc 已声明的「用词表换不吞价格句」的同一权衡，不是本条 F-03 的范围（F-03 点名的是**括号内**的参数被扫）。若要一并处理，需要改虚词网的判据本身（例如要求虚词两侧都是散文语境），那是另一个决定点，本支不做。
- **净代价**：`x + sin(the angle) + 3` 由文本变公式（见 §3）。已在 KDoc 与测试里显式钉住，不再隐藏。
- **同族观察（登记不修）**：`(a and b) is one case` 这类**没有 `name(` 头**的普通括号组，括号内的词仍然是散文证据（本支新用例 `a prose word inside a group that is not a call is still prose` 钉住）。把豁免扩到任意括号组会改变这个判据，属于新决定点。

## 7 文件清单

| 文件 | 改动 |
|---|---|
| `src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownMathHeuristic.kt` | +新增 `contentOutsideCallGroups`；Net 1 改扫括号外文本；3 处 KDoc 同步（虚词表范围与代价、`looksLikeNaturalLanguage` 范围、`containsProseFunctionWord` 入参语义） |
| `src/android/app/src/test/java/com/rikkaminis/app/ui/markdown/MarkdownMathProseScopeTest.kt` | 新增，8 条用例（3 修前误判/修后正确 + 5 回归，期望值全部写字面量） |
| `src/android/app/src/test/java/com/rikkaminis/app/ui/markdown/MarkdownMathProseArgTest.kt` | 1 条断言按修正后的模型重钉 + 类 KDoc 判据 2 改写；其余 13 条未动 |
| `fix-reports/FIX-2D-1119-6-math.md` | 本报告 |

装置在工作树之外：`shared/work/r1119/math-jvm/{run.sh,mutate.py,RunTests.java,out/}`，不会被 `git add -A` 扫走。

## 8 复现

```sh
# 修前误判（把 §3 的 Net 1 改回 containsProseFunctionWord(content) 后）：
sh /var/minis/shared/work/r1119/math-jvm/run.sh        # 22 PASSED / 3 FAILED（三条 F-03 样本）

# 修后：
sh /var/minis/shared/work/r1119/math-jvm/run.sh        # 22 PASSED / 0 FAILED
nice -n 10 python3 /var/minis/shared/work/r1119/math-jvm/mutate.py   # 4 臂全 RED-PRECISE + RESTORE-SHA OK

# 分支 CI（分支 push 不触发，必须手动 dispatch）：
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
  --repo logicflow-GYW/RikkaMinis --workflow build-apk.yml --ref fix/markdown-math-1119
```
