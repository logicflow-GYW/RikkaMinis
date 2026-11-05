# FIX-RHUB1105-B — Anthropic `content_block_start` 初始 `tool_use.input` 丢失（F-03）

- **分支**：`fix/rhub1105-anthropic-tool-input`
- **基线**：`00dc003a3743110258b7cdf2e1feca9c53278f24`（= main）
- **工单**：`fix-tasks/FIX-B.md`，覆盖 **F-03**（P1）
- **判定**：机制成立（先证），已修
- **停止节点**：分支 CI 绿（见 §5）。**未合并 main**（有 `.kt` 验证面 = 非纯文本例外，合并起手权在用户）。

---

## §1 缺陷与根因

**根因一句话**：Anthropic 流式解析在 `content_block_start` 只读了 `content_block.id` / `content_block.name`，
**从不读 `content_block.input`**；到了 `content_block_stop` 又只从 delta 累积的 `toolInputBuffer` 解析参数 ——
中转把非流式 `tool_use` 整块包成 `content_block_start`（`input` 已是完整 JSON 对象）且不发 `input_json_delta` 时，
buffer 为空 ⇒ `JSONObject("")` 抛异常 ⇒ 兜底成 `{}` ⇒ **所有带参工具的参数归零**，
下游 `preflightValidateToolCallImpl` 报空参数错误。

真码位置（基线 `00dc003a` 行号）：

| # | 位置 | 形态 |
|---|---|---|
| 1 | `AnthropicProvider.kt:402-419` `"content_block_start"` | `blockType == "tool_use"` 时只取 `id` / `name`，`toolInputBuffer.clear()`，**完全不读 `content_block.input`** |
| 2 | `:460-478` `"content_block_stop"` | `JSONObject(toolInputBuffer.toString())`，空串抛异常 → `{}` → `ToolCallComplete(args = {})` |
| 3 | `:544-558` EOF 孤儿刷出路径 | 同形（`toolInputBuffer` 为空 → `{}`） |

**触发路径**：one-api / new-api 类中转把上游**非流式** `tool_use` 块整块包成 `content_block_start`，
随后直接 `content_block_stop`，不发 `input_json_delta`。这是该类中转上的常见形态。

---

## §2 修法

**一句话**：把 `tool_use` 块的状态抽成 `AnthropicToolBlock`，
公告里自带的 `input` 暂存为**回退值**——delta buffer 优先，两者永不拼接；块级复位发生在**每个块头**上，
两个 `tool_use` 块连发时互不串参。

具体：

1. 新增 `provider/anthropic/AnthropicToolArgs.kt`（无 Android 依赖，纯 `org.json`）：
   - `AnthropicToolBlock`：块状态（`toolId` / `toolName` / `inputBuffer` / `initialInput`）。
   - `resolveAnthropicToolArgs(deltaBuffer, initialInput)`：判据表。
2. `content_block_start`：**每个块头**都调 `toolBlock.onBlockStart(contentBlock)`（块级复位），
   `tool_use` 分支里读 `toolBlock.toolId` / `toolName`。
3. `content_block_stop`：`toolBlock.resolveArgs()`（delta 优先，initial 回退）。
4. EOF 孤儿刷出：同一 `resolveArgs()`。

**为什么 initial 不 append 进 buffer**：网关既给 initial 又给 delta 时，拼接会产出坏 JSON。
**为什么复位放在每个块头而不是只有 `tool_use` 块头**：一个被放弃的工具块（先 `content_block_start(tool_use)`，
后跟一个 text 块头）若不复位，它的 initial 会漏给下一个块。变异臂 M2 抓到过这个洞（见 §5）。

**为什么 `tool_use` 守卫留在 provider 里**：`truncated_call_guard_check.py` 的
`anthropic-tool-start-feeds-counter` 判据要求 `"tool_use"` 字面量出现在 provider 的
`"content_block_start" ->` 臂内，且 `toolChars` 的写入在该守卫之内。
把守卫搬进 `AnthropicToolBlock` 会让这条门变红（本地实测过一次，见 §5.4）。
任务书禁改 `scripts/scan/**`，因此结构调整以门为准。

---

## §3 改动清单

```
src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt   | 改动
src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicToolArgs.kt   | 新增（纯逻辑）
src/android/app/src/test/java/com/rikkaminis/app/provider/AnthropicProviderTest.kt        | +15 例
```

`git diff --stat`（相对基线）：3 files, +331/−40。**`scripts/scan/**` 零触碰**。

新增 15 例（`AnthropicProviderTest.kt`）分三层：

| 层 | 用例 |
|---|---|
| wire 级（MockWebServer 真 SSE） | `tool use announced with complete input keeps its arguments` / `standard delta stream still takes its arguments from the deltas only` / `two tool blocks in a row do not leak the first block arguments into the second` / `orphan block cut before its stop event keeps its announced arguments` |
| 判据表 | `initial input is used when no delta arrived` / `delta buffer wins over initial input` / `empty buffer without initial input yields empty object` / `unparsable delta buffer degrades to empty object` / `empty initial input is not treated as arguments` |
| 块生命周期 | `block announced with complete input resolves to that input` / `block deltas win over the announced input` / `a second block does not inherit the first block arguments` / `a second block does not inherit the first block arguments without an explicit clear` / `a non tool_use block is not opened` / `a non tool block header drops the pending initial input` |

期望值全部写字面量（不从生产常量推导）——按「JVM 装置自指陷阱」纪律，常量改了期望跟着变会把变异伪装成 PASS。

---

## §4 门判据与带牙证明

本支只跑任务书点名的两个窄门（禁跑全量 `scan.sh` / `test_scan.py` / gradle）：

| 门 | 结果 | 备注 |
|---|---|---|
| `scripts/scan/truncated_call_guard_check.py` | ✅ GREEN | 见下方「门被改动逼红过一次」 |
| `scripts/scan/anthropic_thinking_budget_check.py` | ✅ GREEN（8 criteria） | 未触及 |

**门被改动逼红过一次（过程记录）**：第一版把 `"tool_use"` 守卫搬进 `AnthropicToolBlock.onBlockStart`，
门立刻红：

```
❌ truncated-call gate findings:
   - [anthropic-tool-start-feeds-counter] AnthropicProvider.kt:406 — the content_block_start arm
     no longer tests "tool_use" — the tool block announcement cannot feed 'toolChars' any more
```

按纪律（禁改门）**改代码而不是改门**：守卫搬回 provider 的 `content_block_start` 臂内，
块状态仍由 `AnthropicToolBlock` 持有 ⇒ 门复绿。这条改动的副作用是
`onBlockStart` 变成「返回是否 tool_use」的谓词 + 「总是复位」的状态机，
于是非 `tool_use` 块头也会复位 initial input（这正是 §5 变异臂 M2 抓到的洞）。

---

## §5 验证四层结果

### 5.1 先证（红）——旧代码确实丢参数

沙箱影子探针（`shared/work/meta-audit-1105b/probe-b/Probe.kt`，复刻旧解析器 vs 候选修法）：

```
OLD fixture1 command=''          ← 反事实夹具在旧行为下参数为空
NEW fixture1 command='ls'
NEW fixture2 command='pwd'       ← 标准 delta 形态不受影响
NEW fixture3 block0='ls' block1=''  ← 两块连发，第二块不复用第一块参数
MUTANT fixture1 command=''       ← 变异臂（去掉 initial 回退）重新变空
```

**证伪检查**：若旧代码本来就能读出 `ls`，本支就不该存在。实测 `OLD … command=''` ⇒ 缺陷成立。

### 5.2 修后（绿）——沙箱 JVM 单测

装置：`kotlinc -no-stdlib -cp /var/minis/shared/work/jvm-libs/{kotlin-stdlib-2.1.0,json-20231013,junit-4.13.2,hamcrest-core-1.3}.jar`
一次编译 `AnthropicToolArgs.kt` + 从**已合并后的** `AnthropicProviderTest.kt` 抽出的纯逻辑子集
（沙箱无 Android SDK / okhttp 跑不了 wire 级用例，那 4 例由 CI 承担）。

```
JUnit version 4.13.2
...........
OK (11 tests)
```

### 5.3 变异臂（三条，各自把对应判据打红）

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | `resolveAnthropicToolArgs` 去掉 initial 回退（`if (false)`） | `Tests run: 11, Failures: 3` ✅ 红 |
| M2 | `AnthropicToolBlock.onBlockStart` 去掉块级复位 | `Tests run: 11, Failures: 1` ✅ 红 |
| M3 | initial input 直接 append 进 delta buffer（拼坏 JSON 的形态） | `Tests run: 11, Failures: 2` ✅ 红 |

**M2 是本轮最有价值的一条**：它第一次跑时是**绿的**（`OK (11 tests)`）——
因为当时的复位只发生在 `tool_use` 分支里，一个「先 tool_use、后 text」的块序列能带着 initial 过关。
**装置自错先于代码怀疑**：先查装置（M2 改的是复位，不是判据），确认是代码的真实缺口，
于是把复位提到「每个块头」并补用例 `a non tool block header drops the pending initial input`，
再跑 M2 才红。这条「第一次绿 = 装置没牙 / 代码没洞，必须先判归属」的判据已复用过多次，本轮再次生效。

### 5.4 窄门

见 §4。

### 5.5 CI

- run id：**42025203735**
- head_sha：`43883ad4f9cc31650708571194d3a0eacc8f78ea`（与本地 HEAD 逐字符一致）
- 结论：见 §8「身份链」的最终读数。

---

## §6 任务书失真

| 任务书原文 | 实际 | 处理 |
|---|---|---|
| 「`content_block_start`（tool_use）时暂存 `initialToolInput`」 | 若只在 `tool_use` 分支里暂存 + 复位，**块级复位不完整**（见 §5.3 M2） | 复位提到**每个块头**；工具块守卫仍留在 provider（门要求） |
| 「`:544-558` EOF 孤儿刷出路径同步同样回退」 | 成立，行号略偏（基线实际 `:537-559`） | 已改 |
| 「独占文件：`AnthropicProvider.kt`、`AnthropicProviderTest.kt`、`AnthropicContentBlocksWireTest.kt`」 | 用了前两个；第三个是**请求体**线级测试，与本缺陷无关，未动 | 新增测试全部并入 `AnthropicProviderTest.kt`（避免新增测试文件带来的门/索引漂移） |
| 「禁碰 `scripts/scan/**`」 | 未碰 | ✅ |

---

## §7 未做 / 残留（每条带触发条件）

| # | 项 | 判级 | 触发条件 / 理由 |
|---|---|---|---|
| R-1 | wire 级 4 例只在 CI 跑（沙箱无 Android SDK / okhttp 环境） | P3 装置限制 | 沙箱 JVM 装置天然跑不了 `MockWebServer` + `android.util.Log`；CI 的 `unit-tests` job 是它们的唯一执行面 |
| R-2 | 非 Anthropic 协议的中转形态未覆盖（OpenAI 侧同类问题属 A 支 F-01） | P2 | 触发条件 = A 支完成后复核 OpenAI 侧是否还有「初始 arguments 丢失」同款 |
| R-3 | 真机端到端未验 | P3 | 触发条件 = 本支合 main 后装新包，在一个 one-api / new-api 类中转站上发一次带参工具调用 |
| R-4 | `AnthropicToolBlock` 未覆盖「input 非对象（数组 / 字符串）」的形态 | P2 | 触发条件 = 见到 `content_block.input` 是数组的网关样本；当前 `optJSONObject` 返回 null ⇒ 回退成 `{}`，与旧行为同形，不构成新回归 |

---

## §8 身份链

- 分支：`fix/rhub1105-anthropic-tool-input`
- 提交：`43883ad4f9cc31650708571194d3a0eacc8f78ea`
- CI run：**42025203735**（`build-apk.yml`，`workflow_dispatch`，ref = 本分支）
- head_sha 与本地 HEAD：逐字符一致
- 远端分支 sha（`git ls-remote`）：`43883ad4f9cc31650708571194d3a0eacc8f78ea` ✅
- **未合并 main**（停止节点 = 分支 CI 绿）

### 复现命令

```sh
cd /var/minis/shared/work/meta-audit-1105b/anthropic-tool-input

# 窄门
nice -n 10 python3 scripts/scan/truncated_call_guard_check.py
nice -n 10 python3 scripts/scan/anthropic_thinking_budget_check.py

# 沙箱 JVM 单测（纯逻辑子集）
cd /var/minis/shared/work/meta-audit-1105b/probe-b
nice -n 10 kotlinc -no-stdlib \
  -cp /var/minis/shared/work/jvm-libs/kotlin-stdlib-2.1.0.jar:/var/minis/shared/work/jvm-libs/json-20231013.jar:/var/minis/shared/work/jvm-libs/junit-4.13.2.jar:/var/minis/shared/work/jvm-libs/hamcrest-core-1.3.jar \
  AnthropicToolArgs.kt AnthropicToolArgsTest.kt -d out2
java -cp out2:/var/minis/shared/work/jvm-libs/kotlin-stdlib-2.1.0.jar:/var/minis/shared/work/jvm-libs/json-20231013.jar:/var/minis/shared/work/jvm-libs/junit-4.13.2.jar:/var/minis/shared/work/jvm-libs/hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore com.rikkaminis.app.provider.anthropic.AnthropicToolInputTest
```
