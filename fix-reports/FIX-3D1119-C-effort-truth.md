# FIX-3D1119-C-effort-truth — 学习结果的另一半：收窄读取与词界

分支 `fix/effort-truth-1119b`，基线 `ecb03d92`（= 当前 main），worktree
`/var/minis/shared/work/r3d1119/provider`。

## 1 范围

本轮覆盖报告 `4_Provider_Report.md` 的 **F-01 / F-02** 与 `5_Runtime_Report.md` 的
**F-01**。母题：上一轮 `fix/effort-key-1119`（endpoint 作用域 + CEILING 收窄）与
`fix/hang-attribution-1119`（状态码词界）**各只做了半边**——修的是同一判据的其余读取点，
不是新功能。

| 文件 | 性质 |
|---|---|
| `provider/thinking/EffortTierLearner.kt` | 新增 `learnedWithinOrNull`（「没学到」= null），`learnedWithin` 折成它 + declared |
| `provider/thinking/GatewayEffortTruth.kt` | `resolveTier` 的 learned 读取改走收窄的 `OrNull` 版 |
| `ui/chat/ChatViewModel.kt` | `withLearnedCeiling` 增加声明集形参并改读收窄版；两个调用点各传自己的声明 |
| `diagnostics/FaultAttribution.kt` | `STATUS_CODE_PATTERN` 左右边界扩成字母类，时长单位扩成等价类 |
| 4 个测试类 | 新增 16 条用例（见 §4） |

禁碰项（`scripts/scan/**`、`ProtectedZoneGuard.kt`、`MarkdownMathHeuristic.kt`、
`MemoryRepository.kt`、`SystemPromptInput.kt`、`SystemPromptSegmentOrderTest.kt`、
`ChatInputArea.kt`、`ComposerClearResidue*.kt`）**零改动**，见 §7 的 `git diff --stat`。

## 2 先证（逐条）

### 4 F-01（P1）`ChatViewModel.withLearnedCeiling` 未收窄 —— **成立**

`ChatViewModel.kt:2382`（修前）读 `EffortTierLearner.learned(scope, …)`，即**原始**
CEILING 前缀；wire 侧两个读点（`OpenAIProvider.kt:2854` / `:3250`）已改读
`learnedWithin(...)`。于是同一条目在两侧宽度不同：选择器把上限抬到前缀最高档，
wire 却按声明收窄。

探针实测（`provider-jvm/probe/Probe.kt`，对 HEAD 三个 thinking 文件编译）：

```
raw learned()               = [none, minimal, low, medium, high]
declared                    = [low, medium]
4 F-01 lowerCeiling(XHIGH)  = HIGH          ← 选择器给到 HIGH（未声明档）
4 F-01 folded via narrowed  = MEDIUM        ← 收窄后落在声明内
```

### 4 F-02（P1）`GatewayEffortTruth.resolveTier` 同病 —— **成立**

`GatewayEffortTruth.kt:200`（修前）`val tiers = EffortTierLearner.learned(scope, modelId)`。
同一条目被当作 clamp 集合直接喂 `clampEffort`，于是**网关点名拒过的中间档会重新发出**。
`api.senseaudio.cn` 的 `medium` 正是这条前缀的中段。

探针实测（同一份代码，`scope` 与 `resolveTier` 第 5 参同口径）：

```
declared                    = [low, high]
4 F-02 resolveTier(MEDIUM)  = medium        ← 修前：把 400 的那个值发出去
```

修后同一探针对修复树跑，两行分别变成 `low` / `MEDIUM`（§4）。

### 5 F-01（P2）`STATUS_CODE_PATTERN` 词界只做了一半 —— **成立**

修前 `(?<![\d.])(…)(?!\d)(?!\.\d)(?!\s*ms)`。左侧只排除数字/点 ⇒ `X500` 的 `X` 放行；
右侧只认 `ms` ⇒ `milliseconds` / `seconds` 放行。Python 复算（15 条样本）：

| 样本 | 修前命中 | 修后命中 | 期望 |
|---|---|---|---|
| `SSLHandshakeException: X500Principal mismatch` | **true** | false | false |
| `TLS failure: x500 mismatch` | **true** | false | false |
| `TLS failure: x503 mismatch` | **true** | false | false |
| `TLS failure: code 502cert` | **true** | false | false |
| `connect timed out after 500 milliseconds` | **true** | false | false |
| `read timed out after 504 seconds` | **true** | false | false |
| `HTTP 503` / `status=500 after 500 milliseconds` / `first chunk timeout: 504` / `" 500"` / `504` / `error code: 503` / `502 Bad Gateway` / `response: 400 (bad request)` | true | true | true |

`connect timed out after 500 ms` 两侧都不命中（原有右臂已覆盖），保留为回归对照。

## 3 修法

### 4 F-01 / 4 F-02 的共同根因：`learnedWithin` 把「没学到」伪装成 declared

`learnedWithin` 的第三个返回口径是「没学到 → 原样返回 declared」，这是为 wire 侧
`learned ?: declared` 逐字节不变而设计的。但凡调用方**在 learned 之后还有别的来源**，
这个折法会把它们全部短路：

- `resolveTier` 的第二顺位是**实测表** `tiersFor(host, modelId)`；
- `withLearnedCeiling` 的「没学到」意味着**原样返回目录天花板**。

所以两处都只能先读 `learned(` 判「有没有」，再读 `learnedWithin(` 判「有多宽」——
两个问题两次磁盘快照，规则还会分家。这正是任务书点名的陷阱。

**改法（一个函数，两个调用方各自退到自己的表）：**

```kotlin
// EffortTierLearner
fun learnedWithin(scope, modelId, declared) =
    learnedWithinOrNull(scope, modelId, declared) ?: declared

fun learnedWithinOrNull(scope, modelId, declared): List<String>? {
    val entry = entryFor(scope, modelId) ?: return null
    val tiers = tiersOf(entry, System.currentTimeMillis()) ?: return null
    return if (shapeOf(entry) == Shape.CEILING)
        ThinkingEffortLadder.learnedWithinDeclared(tiers, declared)
    else tiers
}
```

`learnedWithin` 变成一行委托，wire 侧两个调用点**一字未动**（仍是 `?: declared` 的语义，
只是由函数内部完成）。`resolveTier` 与 `withLearnedCeiling` 改读 `OrNull` 版，
`?: tiersFor(...)` / `?: return declared` 两条既有退路恢复可达。

### 4 F-01 的声明集从哪来

先 grep 全部 caller（`withLearnedCeiling(` 只有 `ChatViewModel.kt:2340` / `:2351` 两处），
再确认字段真名是 `LLMModel.reasoningEffortValues`（`data/model/LLMModel.kt:21`）——
`ModelEntry` 上没有该字段，声明在 `entry.model` / `ModelsDevApi.effectiveModel(entry)` 上。

两个调用点的声明**不是同一个对象**，这一点必须分开写：

- entry 分支 → `ModelsDevApi.effectiveModel(entry).reasoningEffortValues`。
  不能用 `entry.model`：它是 `baseModel.withOverrides(overrides)`，**从不经过 models.dev**，
  39 个内建行在这里全是 null，收窄会静默变成空操作（`ThinkingLevelCatalog` 的 dv23-A04
  已经为同一原因改过一次读取点）。
- no-entry 分支 → `model.reasoningEffortValues`，`liveEffectiveModel()` 已经是
  enrich + overrides 之后的模型，即 wire 看到的那一份。

### 5 F-01 的等价类（不新增判据分支）

```kotlin
Regex(
  "(?<![A-Za-z0-9._])(400|401|403|429|500|502|503|504)" +
  "(?![A-Za-z0-9._])" +
  "(?!\\s*(?:milliseconds|millisecond|millis|msec|ms|seconds|second|secs|sec|s)\\b)" +
  "(?!\\s*(?:毫秒|秒))",
)
```

左右各扩成「字母 + 数字 + 点 + 下划线」，右侧时长单位扩成等价类（英文长/短 + 中文），
**没有**新增「有单位就当超时」这类第二判据。`\b` 只挂在英文分支上，中文分支单独一条
（`\b` 在 CJK 边界不可靠）。按任务书要求：不扩测试面、不建新装置，只在
`FaultAttributionTest` 里补边界用例。

## 4 证据

### 本地 JVM 装置（`provider-jvm/run.sh`）

```
OK (129 tests)
```

编译的是真码 + 真测试类（`kotlinc` 单次调用，`nice -n 10`），五个测试类：
`EffortTierLearnerTest` / `ThinkingEffortLadderCeilingTest` / `GatewayEffortTruthTest` /
`EffortScopeDeclaredSetTest` / `FaultAttributionTest`。`user.dir` 设为
`src/android/app`（两个源码形状测试类用的是不同的相对前缀，只有模块目录能让两者都找到）。

新增 16 条用例：

| 类 | 条数 | 钉什么 |
|---|---|---|
| `EffortScopeDeclaredSetTest` | 4 | `learnedWithinOrNull`：无条目 → null（不是 declared）；过期条目 → null；CEILING/ENUM 两形状仍分派；`learnedWithin` == `OrNull ?: declared`（防止两条规则漂移） |
| `GatewayEffortTruthTest` | 4 | CEILING 被声明收窄后 clamp（`medium`→`low`）；无声明时保持原宽度；ENUM 不收窄；**无学习条目时实测表仍胜出**（本轮陷阱） |
| `ThinkingEffortLadderCeilingTest` | 4 | 真 learner + 真 `lowerCeiling` 组合：收窄后不得高于声明（含修前 `HIGH` 的对照臂）；ENUM 不收窄；无条目时无天花板可折；两个调用点各传自己的声明（形状断言） |
| `FaultAttributionTest` | 4 | 数字前有字母 / 数字后有字母 / 各时长单位 / 真状态码不受影响 + 形状断言扩到新边界 |

### 先证 / 修后对照（同一份探针，两个树）

`provider-jvm/probe-prefix.sh`（HEAD 三文件）vs `provider-jvm/probe-fixed.sh`（修复树）：

| 行 | 修前 | 修后 |
|---|---|---|
| `4 F-02 resolveTier(MEDIUM)` | `medium` | `low` |
| `4 F-01 lowerCeiling(XHIGH)`（raw 读，对照臂） | `HIGH` | `HIGH`（不变，说明对照臂有效） |
| `4 F-01 folded via narrowed` | `MEDIUM` | `MEDIUM` |
| `learnedWithin(declared)` | `[low, high]` | `[low, high]` |
| `no-entry resolveTier(XHIGH)` | `xhigh` | `xhigh`（实测表未被短路） |

## 5 变异负控

装置 `provider-jvm/mutate.py`：**10 臂，全部 RED-PRECISE**（`RESULT: ALL PRECISE`）。
每臂只写一个文件、跑一次装置、按字节快照还原并校验 sha256（`with` 写法，避免
`open(p,"w").write(read(p)...)` 先截断再求值——这个写法本轮真把四个源文件清零过一次，
已按记忆里的旧教训修掉）。

| 臂 | 变异 | 预期红 | 结果 |
|---|---|---|---|
| M1 | `resolveTier` 退回 `learned(` | 收窄失效 | RED-PRECISE（1 条） |
| M2 | `resolveTier` 改用 `learnedWithin(`（陷阱本身） | 实测表被短路 | RED-PRECISE（4 条，全是实测表优先级） |
| M3 | picker 退回 `learned(` | 形状断言 | RED-PRECISE（1 条） |
| M4 | 删掉 entry 分支的声明实参 | 形状断言 | RED-PRECISE（1 条） |
| M5 | `shape == CEILING \|\| shape == ENUM`（ENUM 也收窄） | ENUM 全部收窄 | RED-PRECISE（7 条） |
| M6 | `OrNull` 的 `return null` 改成 `return declared` | 陷阱复现 | RED-PRECISE（8 条） |
| M7 | 左边界退回 `[\d.]` | 左侧字母 | RED-PRECISE（2 条） |
| M10 | 右边界退回 `\d` + `\.\d` | 右侧字母 | RED-PRECISE（2 条） |
| M8 | 时长等价类退回 `ms` | 长单位 | RED-PRECISE（2 条） |
| M9 | 声明改读未 enrich 的 `entry.model` | 形状断言 | RED-PRECISE（1 条） |

**两处需要说明的「预期集扩」**：

- M2 的红是 4 条而不是 1 条：`learnedWithin` 的 `?: declared` 同时短路「实测表」和
  「调用方的没学到路径」，这几条用例钉的是**同一个决定**，不是连带伤害。
- M7 第一次跑时 `X500Principal` 那条**没红**：右侧字母类先把它挡住了，左臂单独变异
  不可见。这是真·覆盖缺口，修法是把用例按边界拆成两条（左侧样本 `x500 mismatch`、
  右侧样本 `code 502cert`），而不是放宽预期。拆完 M7/M10 各自只翻自己那条。

**M5 第一次是 COMPILE-FAILED**：我最初的变异把 `if/else` 整块替换成单表达式，
函数体失去 `return`。改成只扩条件（`|| shape == Shape.ENUM`）后是真·行为变异。

## 6 未修与取舍

- **4 F-02 的 `dealWith` 链本身没动**：`learnedWithin` 保留 `?: declared` 语义是刻意的，
  它服务的两个 wire 调用点只有一层来历。改的是「还有别的退路」的两个调用方，
  而不是让所有人适应新语义。
- **`OpenAIProvider.kt:2995` 的诊断日志仍读 `learned(`**：那是日志字段
  （`learned=…`），打印的是**学到的事实**而不是将要发送的集合，改成收窄版会让日志
  谎报「学到的更少」。保持原样，已在此登记。
- **5 F-01 的模块仍无生产消费者**：注释自承「no production consumer」。修它的理由是
  「本轮修复的另一半」，不是「有人会碰到」。按任务书要求未扩测试面、未建新装置。
- **`\b` 只挂英文单位**：中文 `秒`/`毫秒` 单独一条负向断言。`\b` 在 CJK 边界依赖
  Unicode 词边界实现，不可靠；两条并列比一条拼接更难被未来的「整理」合并掉。
- **未做的事**（任务书明确排除）：`KeyRoulette`、SSRF、provider 参数解析（已登记
  backlog G5）。

## 7 文件清单

```
 src/android/app/src/main/java/com/rikkaminis/app/diagnostics/FaultAttribution.kt      |  21 ++--
 src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/EffortTierLearner.kt |  28 ++++-
 src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/GatewayEffortTruth.kt |  16 ++-
 src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt             |  49 +++++++--
 src/android/app/src/test/java/com/rikkaminis/app/diagnostics/FaultAttributionTest.kt |  96 +++++++++++--
 .../provider/thinking/EffortScopeDeclaredSetTest.kt                                   |  55 ++++++++++
 .../provider/thinking/GatewayEffortTruthTest.kt                                       |  98 ++++++++++++++
 .../provider/thinking/ThinkingEffortLadderCeilingTest.kt                              | 113 +++++++++++++++++-
 8 files changed, 447 insertions(+), 29 deletions(-)
```

禁碰文件零命中（`git diff --stat` 只有上表 8 个）。

## 8 复现

```sh
# 本地窄验证（129 条）
sh /var/minis/shared/work/r3d1119/provider-jvm/run.sh

# 变异负控（10 臂，ALL PRECISE）
nice -n 10 python3 /var/minis/shared/work/r3d1119/provider-jvm/mutate.py

# 先证：修前 vs 修后（同一份探针，两个树）
sh /var/minis/shared/work/r3d1119/provider-jvm/probe-prefix.sh   # HEAD 三文件
sh /var/minis/shared/work/r3d1119/provider-jvm/probe-fixed.sh    # 修复树
```
