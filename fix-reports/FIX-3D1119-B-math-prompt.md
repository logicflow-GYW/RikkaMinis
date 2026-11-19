# FIX-3D1119-B：math 方括号 + 提示词真相两处

覆盖 **2 F-01（P1）**、**3 F-01（P1）**、**3 F-02（P1）**，同属「文本层判据/措辞真相」域。

## 1 范围与身份链

- 分支 `fix/math-prompt-1119b`，worktree `shared/work/r3d1119/chatprompt`，基线 `ecb03d92`。
- 代码提交 **`f7b671dc`**（报告单独提交，`git diff f7b671dc..HEAD` 只有本报告）。
- 触碰：`MarkdownMathHeuristic.kt`、`MarkdownMathProseScopeTest.kt`、`SystemPromptSegmentOrderTest.kt`、`MemoryRepository.kt`、`fix-reports/**`。`SKILL.md` **未改**（见 §3 / §6）。
- 硬约束遵守：未碰 `ChatInputArea.kt` / `ComposerClearResidue*.kt` / `ChatViewModel.kt` / `scripts/scan/**` / `ProtectedZoneGuard.kt` / `FaultAttribution.kt` / `GatewayEffortTruth.kt`。
- 未跑全量 `scan.sh` / `test_scan.py` / gradle。本地验证 = 单文件 `kotlinc` + JUnit，一次一个 JVM，`nice -n 10`。

## 2 逐条先证（回树实测，非报告转述）

**2 F-01** — 探针 `shared/work/r3d1119/chatprompt-jvm/Probe.kt` 直接调 `looksLikeMath`，修前结果：

| 输入 | 修前 | 期望 |
|---|---|---|
| `P([a, b) and c)` | **false** | true |
| `P(a and b)` | true | true |
| `f(x) = x^2 where x > 0` | false | text |
| `[1, 2) ∪ (3, 5]` | true | math |
| `hello and welcome` | false | text |
| `P([a,b])` | true | math |
| `P([a, b and c` | false | text（未闭合） |

机制与报告一致：`callGroupEnd` 只对 `(`/`)` 计数，`[a, b)` 里的 `)` 让外层 `(` 的 depth 提前归零，`and` 暴露给两个散文网。

**3 F-01** — 原断言是整节子串：`section.contains("提议")` / `section.contains("批准")`，而节标题 `## 淘汰（我主动提议，仍需用户批准）` 自身同时含两词。本轮把判据换成结构判据后，用变异 M3（删一条正文规则）、M4（把一条规则的「用户批准后再动手」改成「再动手」）实测**变红**——这正是报告要求的「删掉任意一条正文规则，测试变红吗」的机械验证（结果见 §5）。

**3 F-02** — `MemoryRepository.kt:439` 修前原文逐字：

```
Global memory (GLOBAL.md — user-owned; edit only with the user's approval). Treat these as background context, not standing instructions. If the user's latest message conflicts with or supersedes anything here ...
```

同请求的 `Authority` 段豁免 `a GLOBAL.md rule the user approved`，`memory-hygiene/SKILL.md` 写明已批准条目是**常驻指令**。上轮 `fix/prompt-memory-truth-1119` 只改了同一句前面的 `read-only`，漏了这句——同族漏改，本轮收口。

**证伪**：无。三条全部成立，无一条改判。

## 3 修法

**2 F-01** — `callGroupEnd` 换成共享计数器：`(` `[` `{` 为开符、`]` `}` 为闭符，组仍在「把计数器带回 0 的那个 `)`」处结束，闭符不会把计数器压到 0 以下（`f(x] and y)` 不会被 `]` 提前收口）。不配对的开符（`P([a, b and c`）不给豁免，仍返回 `-1`——fail-safe 方向与未闭合 `(` 一致。两个消费者（静态解析器与流式渲染）仍调用同一个函数，本轮没有引入第二份拷贝，`MarkdownMathParityTest` 锁定的「单一实现」性质未被破坏。

**3 F-01** — 先剥标题行，再对正文做结构判据：列表项数量下限 + 每条非空项必须含「提议」与「批准」+ 每条不得含静默删除措辞（`直接删` / `无需用户确认` / `不必等`）+ 全节否定句。标题自身的措辞另有单独断言（防标题把审批词改掉）。

**3 F-02** — 改**注入头**，不改 `SKILL.md`：

> `Entries the user approved here are standing instructions; the rest is background context.`

**这改变模型什么行为**：模型不再把用户已批准写入 `GLOBAL.md` 的条目当「仅供参考的背景」，而按常驻指令执行；从未被批准的内容仍只是背景数据。`supersede` 句原样保留——它防的是「过时记忆压过当前指令」，与「被批准条目的等级」是两件事，删掉它会让两个问题一起消失，属于用删功能消除矛盾。

**为什么不反过来弱化 `SKILL.md`**：`Authority` 段的豁免来源本身就是「user approved」，批准这个动作即构成授权；把 `SKILL.md` 的「常驻指令」降级，等于把用户已经批准过的执行性砍掉，是弱化规则来对齐措辞。矛盾在注入头，就在注入头解决，并在测试里反向钉住 `SKILL.md` 仍写着「常驻指令」。

## 4 证据

- 本地 math 装置（`chatprompt-jvm/run.sh`，5 个纯测试类）：**31 PASSED / 0 FAILED**，含新增 5 条方括号用例。
- 本地 prompt 装置（`chatprompt-jvm/run_prompt.sh`，`SystemPromptSegmentOrderTest`）：**19 PASSED / 0 FAILED**，含重写的 `theMemoryHygieneSkillCallsPruningAProposal` 与新增的 `theGlobalMemoryInjectionHeaderTreatsApprovedEntriesAsStandingInstructions`。
- 必测表（修后实测）：

| 输入 | 结果 |
|---|---|
| `P([a, b) and c)` | math ✓ |
| `P(a and b)` | math ✓ |
| `f(x) = x^2 where x > 0` | text ✓ |
| `[1, 2) ∪ (3, 5]` | math ✓ |
| `hello and welcome` | text ✓ |
| `P([a,b])` | math ✓ |
| `P([a, b and c` | text（未闭合，不给豁免）✓ |
| `f(x] and y)` | text（`]` 不提前收口，散文仍被读到）✓ |
| `f({x} + 1)` | math（`{`/`}` 计入嵌套）✓ |

- **字节预算**：被替换句 61 B → 89 B；注入头整体 302 B → 330 B，**+28 B/请求**（预算 ±120 B）。无顺手扩写，其余措辞一字未动。
- **分支 CI**：run **#3309**（db `44435423991`）@ `ec578b4c333288c32ec464513623388d1baf2094` = 分支 tip = 远端 tip（逐字符一致），五 job 全 success（build-apk / unit-tests / scan-gate / lint / publish）。
  - unit-tests：**9830 PASSED 行 / 0 真 FAILED**；本轮 7 条新/改写用例逐条 `PASSED` —— `SystemPromptSegmentOrderTest > theGlobalMemoryInjectionHeaderTreatsApprovedEntriesAsStandingInstructions`、`… > theMemoryHygieneSkillCallsPruningAProposal`，`MarkdownMathProseScopeTest > a call whose argument is a half open interval is math` / `prose outside a call keeps its text verdict under the bracket rule` / `a bracket closer cannot close a call group early` / `an unclosed bracket does not make a call group` / `a call with prose inside and no bracket is still math`。
  - scan-gate：`Summary: 115 passed, 0 failed`；日志含 `scripts/scan/** untouched in this push — skipping the fixture self-test` ⇒ 本支未动门，**绿不含夹具跑过的证据**（按机制跳过，不是失败）。
  - publish job success（tag/release 两步受 main 门控）。
- 本报告随后以**报告提交**更新（`CI 证据` 段），最终 tip 另做一次 head_sha 复核；`git diff f7b671dc..HEAD` 除 `fix-reports/**` 外为空。

## 5 变异负控（6 臂，全部 RED-PRECISE）

先 commit 本体 `f7b671dc`；装置 `chatprompt-jvm/mutate.py` 快照 → 变异 → 跑两条装置 → `finally` 还原 → sha256 逐字节核对（不使用 `git checkout --`）。

| 臂 | 动作 | 红用例 |
|---|---|---|
| M1 | 2 F-01 回退成只数 `(`/`)` | `a call whose argument is a half open interval is math`、`a bracket closer cannot close a call group early` |
| M2 | 2 F-01 半修：方括号只作闭符、不作开符 | `a call whose argument is a half open interval is math` |
| M3 | 3 F-01 删掉第 2 条淘汰规则 | `theMemoryHygieneSkillCallsPruningAProposal` |
| M4 | 3 F-01 把第 1 条的「用户批准后再动手」改成「再动手」 | `theMemoryHygieneSkillCallsPruningAProposal` |
| M5 | 3 F-02 注入头改回 `not standing instructions` | `theGlobalMemoryInjectionHeaderTreatsApprovedEntriesAsStandingInstructions` |
| M6 | 3 F-02 删掉 `defer to the user's latest message` 子句 | `theGlobalMemoryInjectionHeaderTreatsApprovedEntriesAsStandingInstructions` |

M1 翻两条是**文档化预期重叠**：两条用例都钉方括号规则（一条钉区间、一条钉闭符不提前收口），回退到只数圆括号时两条同时失效，其余 29 条 math 用例与 19 条 prompt 用例全绿。M2/M3/M4/M5/M6 各只翻对应用例，无跨域扩散。基线（未变异）两条装置全绿。

## 6 未修与取舍

- `MemoryRepository.kt:545` 的**日报**注入头仍写 `Treat them as background context, not standing instructions`——有意保留：日报是「过去任务的流水」，不是用户批准的常驻指令，两者不是同族；改它只会制造新的矛盾。登记，不动。
- `MemoryPromptOrder.kt` 零生产调用点（3 F-03）不在本支，backlog G3 F-07 已登记，未碰。
- `ComposerClearResidueWiringTest` 掩码器问题（2 F-02）在本支禁碰清单内，未动。
- `MarkdownMathParityTest` / `MarkdownMathHeuristicMoneyTest` 依赖 `StreamingMarkdownText.kt`（Compose），沙箱单文件编不了，**本地未跑**；parity 靠的是「两份解析器共用这一个实现」的文件级性质，本轮只改该实现本身、未新增第二份拷贝。这两条的覆盖由 CI 的 Android 单测承担。
- 未跑全量 `scan.sh` / `test_scan.py` / gradle（硬约束），故本轮不声称「门/夹具绿」。

## 7 文件清单

| 文件 | 改动 |
|---|---|
| `src/main/java/com/rikkaminis/app/ui/markdown/MarkdownMathHeuristic.kt` | `callGroupEnd` 认 `[`/`{` 开符、`]`/`}` 闭符 + KDoc |
| `src/test/java/com/rikkaminis/app/ui/markdown/MarkdownMathProseScopeTest.kt` | 新增 5 条方括号用例 + 文件 KDoc 补规则 |
| `src/test/java/com/rikkaminis/app/ui/chat/SystemPromptSegmentOrderTest.kt` | 重写淘汰断言（结构判据）、新增注入头断言、顶层常量 |
| `src/main/java/com/rikkaminis/app/data/repository/MemoryRepository.kt` | 注入头一句替换 + 说明注释 |
| `fix-reports/FIX-3D1119-B-math-prompt.md` | 本报告 |

## 8 复现

```sh
# 本地窄验证（单文件 kotlinc，一次一个 JVM，nice -n 10）
sh shared/work/r3d1119/chatprompt-jvm/run.sh          # math：31 PASSED / 0 FAILED
sh shared/work/r3d1119/chatprompt-jvm/run_prompt.sh   # prompt：19 PASSED / 0 FAILED
nice -n 10 python3 shared/work/r3d1119/chatprompt-jvm/mutate.py   # 6 臂 RED-PRECISE
```
