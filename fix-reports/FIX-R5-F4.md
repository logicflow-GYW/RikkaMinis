# FIX-R5-F4：markdown 金额区间被吞（F-F-07, P1）

- 分支：`fix/r5f-markdown`
- 施工 worktree：`/var/minis/shared/work/witness-1027/wt-r5f-markdown`
- 基线：main `35509888`
- 绿 sha：`f4a9554b`
- CI run：`40885982453`
- 来源：R5-F 包 `F-07` / `C-06`（`meta-audit/reports/r5/R5-F-external-surface.md`）
- 判级台账：`r5-ef/TRIAGE-R5-EF.md`（B10 支）

---

## 1 这改变什么用户行为？

**改动前**：静态渲染路径里的 `$5k-$10k`、`$5M-$10M`、`$10/mo-$20/mo`、`$5B+$2B`、`US$5k-US$10k`，两侧 `$` 消失、首段金额连同连字符被交给 KaTeX 渲染成公式。

**改动后**：这些形态保持字面文本，两个 `$` 连同后缀金额原样显示；`$x-y$`、`$2n+1$`、`$2+2=4$`、`$c$` 等真公式照旧渲染为公式。

用户可见面 = `MarkdownText` 静态渲染路径。无新增字符串，i18n 零改动。

---

## 2 缺陷先证（按语义，不按报告行号）

### 2.1 成立性：修复前 JVM 实测为红

harness = 三个替身（`BuildConfig` / `android.util.Log` / 从 `MarkdownBlockModel.kt` 逐字切出的媒体 Dependence）+ 真 `MarkdownParser.kt` 原文 → 单次 `kotlinc` → JUnit 驱动真 `parseWithMath`。修复前 `MarkdownCurrencyRangeTest` **24 例跑出 9 红**，红的全部落在金额表：

| 输入 | 被吞进 span 的内容 |
|---|---|
| `cost $5k-$10k flat` | `5k-` |
| `range $5M-$10M ok` | `5M-` |
| `plan $10/mo-$20/mo here` | `10/mo-` |
| `seat $5k/mo-$10k/mo yearly` | `5k/mo-` |
| `raise $1.5M-$3M round` | `1.5M-` |
| `grow $5B+$2B total` | `5B+` |
| `band $50k-$100k wide` | `50k-` |
| `每月 $10/月-$20/月 pricing` | `10/月-` |
| `tier US$5k-US$10k flat` | `5k-US` |

机制与报告一致（`isCurrencyShaped` 遇任意字母即 `return false` → `looksLikeMath` 的运算符扫描在首字符数字守卫之前命中 `-`/`+`）。**报告 C-06 / F-07 成立。**

### 2.2 行号漂移（不影响结论）

真树里 `looksLikeMath` 在 `:679`（报告写 `:705`）、运算符扫描在 `:704-708`、首字符数字守卫在 `:723`（报告写 `:714`）；`isCurrencyShaped` 在 `:748-756`。上一次 “r3-c / B09 F-13” 的修补挪动了这些行的位置。**行号对不上，机制描述准确** —— 按语义取证即可。

### 2.3 报告的一处不准确：`$5x2$` 从来不是数学

任务书 §2 的反表要求 `$5x2$` 必须仍判数学。实测（也在 r3-c 写 Enter the 生产注释里逐字记着 “`$5x2 rows$`”）：

```
grep/, →  数字开头 + 无运算符 ⇒ looksLikeMath 的数字守卫 if (content[0].isDigit()) return false 早已拒绝
```

⇒ `$5x2$` 在**本支改动前后都是文本**。它不是回归条目，本支把它作为**现状记录**钉进测试（`digit-led product is text before and after`），避免以后有人照报告把它当成“一度是数学、现在被弄坏了”。

---

## 3 修法

在 `MarkdownParser.looksLikeMath` 中，于 `isCurrencyShaped` 之后、运算符扫描之前插一道 second cash net：`isCurrencyRangeShaped`（新 intern 函数 + 一个 `Regex` 常量）。生产面 = **+42 行，零删除，零签名变更**。

```
^[0-9][0-9.,]*\s*(?:[A-Za-z]{1,3})?(?:/[\p{L}\p{N}]{1,6})?\s*[-+<>]\s*(?:[A-Z]{1,3})?$
```

逐段含义（每段都是“价格会写、公式不会写”的东西）：

| 段 | 例子 | 判据 |
|---|---|---|
| `^[0-9][0-9.,]*` | `5` / `1.5` / `1,000` | 金额主体 |
| `(?:[A-Za-z]{1,3})?` | `k` `M` `B` `bn` | 数量级后缀 |
| `(?:/[\p{L}\p{N}]{1,6})?` | `/mo` `/user` `/月` | 单价单位（含 CJK） |
| `[-+<>]` | `-` `+` `<` `>` | 价格运算；`=` `^` `_` `{` `}` 刻意不在内 —— 与既有 `NON_CURRENCY_MATH_GLYPHS` 同一分界 |
| `(?:[A-Z]{1,3})?$` | `US` | `US$5k-US$10k` 配对时会把下一个金额的币种前缀一起吞进 span（`5k-US`） |

**为什么判在这一层而不动 `extractMath` / `findSingleDollar`**：被吞的第二个 `$` 是 `findSingleDollar` 按设计找到的下一个合法闭分隔符（其前不是空格）。改扫描顺序会同时影响 `$5 - $10` 这类既有用例；半径纪律要求只动“判定族”。

**CAPS 从句是最窄的一片**，理由写进 KDoc：`$5-x$` 必须仍判数学（小写变量），而现实世界的币种前缀是大写（`US$`、`HK$`）。选择文本作为失败姿态是有意的 —— 漏掉的公式还会带着自己的 `$…$` 露给用户，被吞的价格区间则永久删掉了那个 `$`。

`ponytail` 三段齐（简化什么 / 天花板 / 升级触发）。

---

## 4 正反例表（24 例，全部字面量写死在 `MarkdownCurrencyRangeTest`）

### 4.1 金额表（money → 文本，期望 span = `[]`，段落文本 = 输入原文）

| # | 输入 |
|---:|---|
| 1 | `cost $5k-$10k flat` |
| 2 | `range $5M-$10M ok` |
| 3 | `plan $10/mo-$20/mo here` |
| 4 | `seat $5k/mo-$10k/mo yearly` |
| 5 | `raise $1.5M-$3M round` |
| 6 | `grow $5B+$2B total` |
| 7 | `band $50k-$100k wide` |
| 8 | `每月 $10/月-$20/月 pricing` |
| 9 | `tier US$5k-US$10k flat` |
| 10 | `Price: $5k - $10k per seat` |

### 4.2 数学表（formula → 保留 span）

| # | 输入 | 期望 latex |
|---:|---|---|
| 11 | `diff $x-y$ ok` | `x-y` |
| 12 | `sum $a+b$ ok` | `a+b` |
| 13 | `var $c$ alone` | `c` |
| 14 | `eq $2+2=4$ ok` | `2+2=4` |
| 15 | `sum $1+1=2$ done` | `1+1=2` |
| 16 | `seq $2n+1$ here` | `2n+1` |
| 17 | `pow $2^{10}$ here` | `2^{10}` |
| 18 | `eq $E=mc^2$ ok` | `E=mc^2` |
| 19 | `frac $\frac{1}{2}$ x` | `\frac{1}{2}` |
| 20 | `expr $5-x$ ok` | `5-x` ← **最窄的一条：小写变量不得被 CAPS 从句吞掉** |
| 21 | `$$E=mc^2$$`（块级） | `E=mc^2`，`isBlock = true` |

### 4.3 两个方向都不许动的既有行为

| # | 输入 | 期望 | 说明 |
|---:|---|---|---|
| 22 | `it costs $5 and $10 total` | `[]` | r3-c 既有用例 |
| 23 | `rows $5x2$ here` | `[]` | 现状记录，见 §2.3 |
| 24 | `cmp $5 > 3$ here` | `[]` | `isCurrencyShaped` 已记录的价格优先 trade-off，本支未改 |
| 25 | `\$5k-$10k is odd` | `[]` | 转义 `$` 不是分隔符 |

---

## 5 验证

### 5.1 本地窄装置（禁止全量的前提下）

| 套件 | 结果 |
|---|---|
| `MarkdownCurrencyRangeTest`（新增 24 例） | **OK (24 tests)** —— 修复前 9 红 |
| `MarkdownCurrencyMathTest`（既有 15 例，r3-c 那一轮） | **OK (15 tests)** |
| `MarkdownParserBlockStructureTest` + `MarkdownParserNumberedListTest`（同一 parser 的其余 91 例） | **OK (91 tests)** |
| `python3 scripts/scan/file_uri_parse_guard_check.py .`（唯一引用本文件的门，单跑 6.4 s） | **Summary: 18 passed, 0 failed ／ GATE=GREEN** |

装置做法：`kotlinc` 单次编译 `MarkdownParser.kt` 原文 + `UriPathDecode.kt` 原文 + `MarkdownBlockModel.kt` 的媒体 Dependence **逐字切片**（不是手抄，防 Judge-head 漂移）+ 3 个替身，一个 JVM 跑 JUnit。`nice -n 10`，单 CPU 串行，遵守负载纪律 §4。

> **中途自伤一次**：先用手写 `ChatStub`（`mediaKindOfUrl` 恒返回 IMAGE）⇒ 既有媒体路由 6 例红。换成逐字切片后转绿。**教训：手写替身会丢真实分类逻辑 —— 能切就切，不要手抄。**

### 5.2 变异臂（4 条，每条必须打红它目标的那一条）

| 臂 | 变异 | 结果 |
|---|---|---|
| **A** 删掉 `isCurrencyRangeShaped` 调用 | 金额表 **9 例全红**（= 修复前状态） |
| **B** CAPS 从句放宽成任意字母 | 恰好 `$5-x$` 那 1 例红 |
| **C** 删掉尾部币种前缀从句 | 恰好 `US$5k-US$10k` 那 1 例红 |
| **D** 放宽成「数字开头 + 任意位置含 `-+<>`」 | **3 例自家电表红 + 2 例既有 `MarkdownCurrencyMathTest` 红**（`$1+1=2$` / `$2n+1$`） |

- 臂 A 证明新网真的在工作，不是装饰；
- 臂 B/C 证明两个从句各自有用且都不够宽；
- **臂 D 是“拒绝更宽松写法”的对照**：一个图省事的正则会当场吃掉既有测试钉住的真公式，所以必须拆成 4 段形状而不是一把抓。

### 5.3 CI（绿 = 已核）

- run **`40885982453`**，attempt 1，`success`，21.8 min（13:52:33Z → 14:14:46Z）
- 云端 `head_sha` = **`f4a9554b7cec72584b7b7d7b624248465e6f96e4`**，与本地 `git rev-parse f4a9554b` **逐字符一致**（三项核查第 2 项）
- 日志实测（1,657,176 B，NUL 0）：
  - 门：`Summary: 113 passed, 0 failed`，`GATE=GREEN file_uri_parse_guard`（本文件唯一相关门）
  - 夹具：`✅ ALL 349 CASES PASS`
  - 单测：`MarkdownCurrencyRangeTest` **24/24 PASSED**、`MarkdownCurrencyMathTest` **15/15 PASSED**（逐条 PASSED 行在日志里可数）
  - `e: file` 编译错 **0**、真 `FAILED` 结尾行 **0**
- **停止节点达成**：分支 CI 绿，**未合 main**（起手权在用户）

---

## 6 残留与登记（4 条，全部带触发条件）

- **R1 聊天流式路径的同款缺陷未修（P2，登记）**。`ui/chat/StreamingMarkdownText.kt` 有自己的顶层 `internal fun looksLikeMath`（`:3436`），调用方是 `parseInline`(:3059) / `appendRecursive`(:3149) / `collectInlineMathLatex`(:3389) 与 `MarkdownBlockModel`(:255 / :265)，**不在 `MarkdownParser` 的调用图里** —— 这道现金网从没到过它。用 `chat_side.py` 把该函数逐字切片后实测 5 个形状：`5-` / `5k-` / `10/mo-` / `5B+` / `5k-US` **全部返回 `true`**（仍判数学）。流式文本转静态后由本支代码接管，故最终态正确，但**流式增量与最终渲染会不一致、渲染可能跳变**。｜**触发**：用户报「流式时显示公式、完成后变回美元」，或有人动 `StreamingMarkdownText.kt`｜**方案**：把 currency 判定抽成共享纯函数，两条路都调｜**搁置**：文件集互斥纪律（该文件不归本支），且流式尾端会走静态路径。
- **R2 跨词素配对的下半世UM**（P2）。句子里出现第三个 `$` 时可能形成跨金额配对（内容里含裸 `$`），由 `looksLikeMath` 开头的 `content.indexOf('$') >= 0` 守卫挡下 —— 这是 r3-c 那一轮既有的行为，本支未扩大也未削弱它。｜**触发**：出现「内容里含 `$` 却被当成数学」的报告｜**方案**：同 R1 的共享化｜**搁置**：挡得住，边界不在这里。
- **R3 非 `/xxx` 形式的单位后缀**（P2）。`/unit` 从句限 1–6 个字母/数字，`5-per-seat-` 之类不匹配 ⇒ 仍判数学。｜**触发**：见到真实例外（目前零例）｜**方案**：给该从句加 `-per-token` 形态｜**搁置**：没见过真实例子，不为想象中的句子加宽阀门。
- **R4 真机未验（P1-观测）**。全部证据 = CI 编译级 + JVM 级；`MarkdownText` 组合体本身没在真机上目视确认过。｜**触发**：下次动 KaTeX 渲染或同一次 Search 装包含金额的要点｜**方案**：用一张含 `$5k-$10k` 的 skill 卡片目视真机｜**搁置**：本支唯一生产改动是纯 Kotlin 判定函数，可被 JVM 直接、完整验证 —— 装置比例合适。

---

## 7 纪律自查

- [x] 只改本 worktree；禁碰清单（`scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**`、`MarkdownText.kt`）**零触碰**（`git diff --stat` = 1 生产文件 + 1 新测试文件）
- [x] 期望值全为写死字面量，无一处从被测常量推导
- [x] 改共享函数前 `rg -n "looksLikeMath|isCurrencyShaped|extractMath"` 列全部 caller —— 发现 `ui/chat/` 另有同名实现，按文件集互斥纪律不动它，改登记 R1
- [x] 禁跑全量 `scan.sh` / `test_scan.py` / gradle；单文件 `kotlinc` + `nice -n 10`，同时刻一个 JVM
- [x] 「分支 CI 绿 + head_sha 逐字符一致 = 停止节点」，**不合 main**
- [x] 4 条变异臂逐条按预期打红目标

---

## 8 收口

```
$ git diff --name-only f4a9554b..HEAD -- . ':(exclude)fix-reports'
（空）
```

本报告在绿 sha `f4a9554b` 之后提交（仅新增 `fix-reports/FIX-R5-F4.md`），因此绿 sha..HEAD 的非报告差异为空 —— 与既有几批的判据形态一致（“sha 与 tip 不等是正常形态，判据是差异内容”）。

**装置清单**（`/var/minis/shared/work/r5-ef/probe-f4/`）：`run.sh`（编译 + 三组 JUnit）、`arms.py`（4 变异臂）、`chat_side.py`（chat 路径逐字切片探针，支撑 R1）、`flows.py`（调用图取证）、`arms.log`、`base/`（基线对照组 covid 树）。
