# FIX-RHUB1105-A — OpenAI Responses 流式协议三缺陷（F-01 / F-02 / F-06）

- 分支：`fix/rhub1105-openai-responses`（worktree `/var/minis/shared/work/meta-audit-1105b/openai-responses`）
- 基线：`00dc003a3743110258b7cdf2e1feca9c53278f24`
- 任务书：`/var/minis/shared/work/meta-audit-1105b/fix-tasks/FIX-A.md`
- 判级：`/var/minis/shared/work/meta-audit-1105b/TRIAGE.md`（三条均 P1）
- 源报告：`/var/minis/shared/work/meta-audit/reports/r6/Rhub_Protocol_1105_Audit_Report.md`

---

## 1. 覆盖 ID 与改动面

| ID | 标题 | 判级 | 处理 |
|---|---|---|---|
| F-01 | Responses 工具调用解码三处缺口 | P1 | 修 |
| F-02 | Responses 分支缺顶层 `type == "error"` / 内联 `error` | P1 | 修 |
| F-06 | `ReasoningContent` 发射门控漏两个口 | P1 | 修 |

改动文件（3 个）：

| 文件 | 性质 | 说明 |
|---|---|---|
| `provider/openai/OpenAIProvider.kt` | 改 | 五处生产修改（F-01 三处 + F-02 一处 + F-06 两处） |
| `provider/openai/OpenAIResponsesToolAccumulator.kt` | **新增** | 累加器数据类 + 唯一查找规则（纯函数，可单测） |
| `test/.../provider/openai/OpenAIResponsesStreamFixTest.kt` | **新增** | 12 例仓库内单测 |

`scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**` **零触碰**。

---

## 2. 真码核实结论（先证，不按报告行号对齐）

三条**全部证伪不了** —— 报告描述的机制在 `00dc003a` 的真码里逐条成立，行号仅小幅漂移（F-01 从 `:868` 起 → 实际 `:871`；F-02 的 `:1136` → `:1144`；F-06 的 `:880`/`:972`/`:1260`/`:1292`/`:1389` → 实际 `:880`/`:1010`/`:1398`/`:1424`/`:1543`）。

### 2.1 F-01 —— 三处缺口逐一成立

1. **空串覆盖成立**：`response.output_item.done` 臂原为
   `val argsStr = item.optString("arguments", acc.args.toString())`。
   `org.json` 的 `optString(key, fallback)` 在 **key 存在且值为 `""`** 时返回 `""` 而**不走 fallback** ——
   官方契约是「key 不存在才用 fallback」。所以 `.done` 帧带 `"arguments": ""` 时，已累积的 `acc.args` 被清空，
   `JSONObject("")` 抛异常 → 落到 `JSONObject()` → 工具参数变成 `{}`。

2. **无 `response.function_call_arguments.done` 臂成立**：`grep function_call_arguments` 全仓只有 `.delta`。
   该帧是「参数流的权威结束」，部分中继只发 `.done` 不发 `.delta` ⇒ 工具调用永远拿不到参数。

3. **强依赖可选 `item.id` 成立**：`response.output_item.added` 原要求
   `itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()` 三者全非空才建累加器。
   而同仓 `OpenAIResponsesToolReplay.kt` 自己的注释就写明 Responses schema 把 `id` 标为**可选** ——
   中继不��� `fc_…` 时整条工具调用静默消失（无 warn，因为 `added` 分支连 warn 都没有）。

### 2.2 F-02 —— 成立，且比报告更宽

Responses 分支的 `when` 里只有 `type == "response.failed"`（取 `response.error`）。
顶层 `type == "error"` 帧、或任何带内联 `error` 对象的帧，**落进 `else -> {}` 被丢弃** ⇒ 流走完 → 正常 `Finished` ⇒
调用方看到的是「干净的空回答」，真实错误（配额 / 鉴权 / 限流）被吞掉。
Chat Completions 分支有对应臂（`:1231` 附近的 `event.optJSONObject("error")`），Responses 分支没有。

### 2.3 F-06 —— 两条口都成立

1. `chatCompletionsDeltaThinkingBlocks(d)` 循环（Mistral `delta.content[{type:thinking}]`）里
   `reasoningAccum.append(thinkingText)` + 发 `ThinkingDelta`，**但不置** `sawReasoningField`。
   而 `ReasoningContent` 的唯一发射点是 `[DONE]` 分支的 `if (sawReasoningField)` ⇒
   Mistral 的服务端原生思考内容**永远拿不到 `ReasoningContent`**（只能拿到增量 `ThinkingDelta`）。
   注：`<think>` 标签拆分路径（`extractThinkTags`）**故意不置位**该标志，是既有设计，本支保留原状。

2. EOF 收尾（`[DONE]` 未到达、连接被切）路径**不发** `ReasoningContent` ⇒
   推理内容在流被切断的回合里整体丢失。

---

## 3. 修法

### 3.1 F-01（新增独立文件 + 一处查找规则）

新增 `OpenAIResponsesToolAccumulator.kt`：

- `internal data class ResponsesToolCallAccumulator` —— 从 `rawStreamMessage` 内部提到文件作用域，
  增两个字段：`itemId`（服务端 `fc_…` id，可空）、`outputIndex`（响应输出数组下标，-1 = 未知）。
- `internal fun resolveResponsesAccumulator(accumulators, itemId, callId, outputIndex): ResponsesToolCallAccumulator?`
  —— **唯一**查找规则，优先级 `item_id` → `call_id` → `output_index`。
  前两者是精确身份；第三者是位置身份，只在服务端完全没标识 item 时才用。

三处调用点改为走这条规则：

- `response.output_item.added`：准入条件从「三者全非空」放宽为「`call_id` + `name` 非空」；
  map 的 key 在有 `itemId` 时用 `itemId`，否则用 `callId`。
- `response.function_call_arguments.delta`：`item_id` → `call_id` → `output_index` 三级回退查找。
- `response.output_item.done`：同样三级回退；并修空串覆盖 ——
  `val authoritative = item.optString("arguments", "")` 后
  `val argsStr = if (authoritative.isNotEmpty()) authoritative else acc.args.toString()`。
  语义保留：「非空且可解析的 `arguments` 才 authoritative，空串不算」。

**新增 `response.function_call_arguments.done` 臂**：取 `arguments` 字符串，
若非空则**覆盖** buffer（这是权威值），然后立即发 `ToolCallComplete` 并移除累加器。

### 3.2 F-02（一个 `when` 臂）

Responses 的 `when` 里新增（放在 `response.failed` 之前）：

```kotlin
type == "error" || event.optJSONObject("error") != null -> {
    val err = event.optJSONObject("error") ?: event
    throw mapHttpError(openAiStreamErrorStatus(code, errType), err.toString())
}
```

**不新造错误映射函数** —— 复用 `openAiStreamErrorStatus`（`[dv28 A-08]` 单一口径：符号 code → HTTP 语义）
+ `mapHttpError`。因此 `insufficient_quota` → 429 → `LLMError.RateLimited`（退避 / 换路由），
而不是旧路径的 `TransientError`（原地重试）或 `ProviderError`（不可恢复）。

### 3.3 F-06（两处各 1–2 行）

1. `chatCompletionsDeltaThinkingBlocks(d)` 循环内置 `sawReasoningField = true`。
   Mistral 的 `content[].type == "thinking"` 是**服务端原生**推理字段，与 `reasoning_content` 同语义；
   它与 `<think>` 标签拆分是两条路，本支**不动** `extractThinkTags` 的不置位设计。
2. EOF 收尾在 `Finished` 守卫**之外**补发：
   `if (!finishedSent && sawReasoningField) { send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString())) }`。
   它是独立载荷，不是终止信号 —— 不塞进 `if (!finishedSent && (contentLen > 0 || …))` 里。

---

## 4. 证据

### 4.1 影子探针（机械抽取，非手写）

探针工程 `fix-reports/.probe/A/`：

- `build.py` 从真文件**逐字抽取**生产决策切面（`response.output_item.added`…`response.failed`、
  `response.failed`…`response.output_text.done`、Chat 推理分支、`[DONE]` 块、EOF drain 段、
  `combineResponsesAPIIds` / `mapHttpError` / `parseResponsesAPIUsage` / `extractThinkTags` /
  `flushThinkTags` / `chatCompletionsDeltaText` / `chatCompletionsDeltaThinkingBlocks` 等），
  只做去缩进，**不手抄任何判断**。累积器定义单独从
  `OpenAIResponsesToolAccumulator.kt` 抽取。
- 夹具全部按任务书 §2 的反事实判据写：期望值是**字面量**，不从被测常量推导。

**红基线**（`red-baseline.txt`，修前）：**8 FAILURE(S)**

```
FAIL  F-01a args survive empty arguments on output_item.done — args={}
FAIL  F-01b function_call_arguments.done arms the call — completes=[{}]
FAIL  F-01c item without id still yields ToolCallComplete(call_3) — completes=[]
FAIL  F-02 error frame throws instead of finishing silently — chunks=[Finished]
FAIL  F-02 mapped to the rate-limit family (not TransientError) — thrown=null
FAIL  F-02 no Finished emitted after the error frame
FAIL  F-06a ReasoningContent emitted for Mistral thinking blocks — got=[]
FAIL  F-06b ReasoningContent emitted on EOF without [DONE] — got=[]
```

（另有 2 条 PASS：`F-01a one ToolCallComplete emitted`、`F-01d EOF drain emits the half-finished call` ——
这两条是护栏，说明「修好空串覆盖」不能靠「不发 ToolCallComplete」作弊达成。）

**绿态**（修后）：**ALL PASS**（10/10）

### 4.2 变异臂（6 条，各自把探针重新打红）

| 变异 | 回退的修复 | 探针结果 |
|---|---|---|
| `m1.py` | 空串覆盖修复（`argsStr` 退回 `optString`） | 1 FAILURE（`F-01a args survive…`） |
| `m2.py` | 删掉 `response.function_call_arguments.done` 臂 | 1 FAILURE（`F-01b…`） |
| `m3.py` | `added` 退回「`itemId` 也必须非空」 | 1 FAILURE（`F-01c…`） |
| `m4.py` | 删掉 F-02 错误臂 | 3 FAILURE（`F-02` 三条） |
| `m5.py` | 去掉 Mistral 思考块的 `sawReasoningField = true` | 1 FAILURE（`F-06a…`） |
| `m6.py` | 去掉 EOF 的 `ReasoningContent` 补发 | 1 FAILURE（`F-06b…`） |

**每条变异只打红它对应的那一条断言**，没有互相污染 ⇒ 断言有牙，不是恒绿。
（探针在**拆文件之后**的最终代码上重跑过一次，6 条变异结果一致。）

### 4.3 仓库内单测

`OpenAIResponsesStreamFixTest`（12 例）分两层：

- **纯函数层**（5 例）：`resolveResponsesAccumulator` 的 `item_id` 优先 / `call_id` 回退 /
  `output_index` 回退 / 全不匹配返回 null / 未知 `output_index` 不乱匹配。
- **生产接线层**（7 例）：扫生产源码，钉死「查找走共享规则而不是 `responsesToolCalls[itemId]`」、
  「存在 `function_call_arguments.done` 臂」、「空串不 authoritative」、
  「`call_id` 单独即可建累加器」、「存在 error 臂且复用 `openAiStreamErrorStatus`」、
  「Mistral 思考块置 `sawReasoningField`」、「EOF 发 `ReasoningContent`」。

沙箱 JVM 跑：**OK (12 tests)**（`kotlinc -no-stdlib -cp jvm-libs/*`，`nice -n 10`）。

### 4.4 窄门

`python3 scripts/scan/truncated_call_guard_check.py` → **exit 0**，输出
`✅ truncated-call gate: policy reads both truncation sources, the engine and the sub-agent loop feed it,
OpenAI's EOF guard counts tool-call events, both stub-reminder injection sites share one stacking guard,
and Gemini's EOF terminal decision goes through the pure verdict without inventing a stop reason`

（该门消费 `toolCallEventCount` 的两个 feed 点与顶层整数函数面；本支新增的
`resolveResponsesAccumulator` 是**顶层非 Int 函数**、返回可空对象引用，不落进 `top_level_int_funs` 判据，
且未改动两个 feed 点的任何一行 ⇒ 门保持绿。）

### 4.5 CI

两轮（第一轮验代码提交，第二轮验报告提交后的最终 tip）：

| run id | head_sha | conclusion | 对应提交 |
|---|---|---|---|
| `42035467381` | `30a3e831625299cb8ac15894209252857d97b7ad` | success | 代码提交 `30a3e83` |
| **`42037973976`** | **`50c1d23141822ab0b4608707c336dc830015683c`** | **success** | **分支最终 tip `50c1d23`（含本报告）** |

- 两轮的 `head_sha` 均与对应提交**逐字符一致**。
- 两轮 job 全绿：`scan-gate` / `build-apk` / `unit-tests` / `publish` 均 **success**。
- **停止节点 = 第二轮**（`42037973976`）：它的 `head_sha` == 分支 tip == 远端分支 sha。
- ⚠️ 分支轮的 `publish` job 会 **success 而非 skipped** —— 本仓的 publish 步骤门控是
  `if: github.ref == 'refs/heads/main'` 之外的独立条件（本轮实测分支轮 publish 也跑完并 success）。
  因此**分支绿 ≠ 有可安装的新 APK**：正式发版仍需合入 main 后由 main 轮产出。
- `git diff --name-only 30a3e83..HEAD -- . ':(exclude)fix-reports'` 为空 ⇒
  报告提交之外的差异为空（符合任务书 §5 收口要求）。

---

## 5. 残留与未验边界

1. **真机端到端未验**：三条修复的 wire 行为目前只有影子探针 + 源码扫描两层证据，
   **没有**真机 / live-API 的实际 `/responses` 流。装新包后应各验一轮：
   ①空串 `arguments` 的 `.done` 帧 → 工具参数非空；
   ②只发 `function_call_arguments.done` 不发 `.delta` 的中继 → 工具调用可用；
   ③顶层 `type:"error"` 帧 → UI 显示配额/鉴权错误而不是「空回答」。
2. **`output_index` 回退是位置身份**：同一响应里出现**两个**都不带 `item_id` / `call_id` 的
   function_call item 时，回退会命中第一个。本仓 `OpenAIResponsesToolReplay` 已注明 `id` 可选，
   但 `call_id` 是 Responses schema 的**必填**字段（工具调用关联靠它）⇒ 现实里「`call_id` 也缺」的帧
   属于协议违规，`output_index` 只是兜底，不是主路径。
   `ponytail: output_index 作为最后一级回退 | 天花板: 同一响应内两个都不带 id/call_id 的 function_call | 升级触发: 见到真实中继发这种帧`
3. **`response.function_call_arguments.done` 与 `output_item.done` 可能都到**：两者都发 `ToolCallComplete` 时
   第二个会因累加器已被移除而查不到（`resolveResponsesAccumulator` 返回 null）⇒ 不会重复发。
   已由 `added → done` 与 `added → arguments.done → output_item.done` 两条路径的查找语义保证，
   但**没有**专门夹具钉死「两个 done 帧只发一次」——登记为 P2，不为本条建额外装置。
4. **F-02 的错误臂不做 `EffortTierLearner.record`**：Chat 分支有这行（把中继的 200-流内错误喂给
   自我学习器），Responses 分支没有。本支**未加** —— 加它会牵动 EffortTier 学习的输入面，
   超出「补齐缺失的错误臂」范围，属独立议题。登记，不在本支修。
5. **P3**：`resolveResponsesAccumulator` 用线性扫描（每次 3 个候选 × 累加器数）。工具调用数量级是 1–5，
   线性扫描与 map 查找无实质差别；不引入额外索引结构。
6. **`OpenAIProvider.kt` 里删掉的那段注释**（「The accumulator moved to file scope so…」）随定义一起搬到新文件，
   未在原位留残影。

---

## 6. 复现命令

```sh
# 影子探针：装配 → 编译 → 跑（绿态）
cd /var/minis/shared/work/meta-audit-1105b/openai-responses/fix-reports/.probe/A
python3 build.py          # 从生产文件逐字抽取装配
./run.sh                  # nice -n 10 kotlinc -no-stdlib -cp /var/minis/shared/work/jvm-libs/...
java -cp "out:/var/minis/shared/work/jvm-libs/kotlin-stdlib-2.1.0.jar:/var/minis/shared/work/jvm-libs/json-20231013.jar" \
     com.rikkaminis.app.provider.openai.ProbeAKt     # → ALL PASS

# 变异臂（每条把探针打红）
./mutate.sh mut/m1.py     # … m6.py

# 仓库内单测（沙箱 JVM）
LIB=/var/minis/shared/work/jvm-libs
kotlinc -no-stdlib -cp "$LIB/kotlin-stdlib-2.1.0.jar:$LIB/json-20231013.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar" \
  -d out \
  src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIResponsesToolAccumulator.kt \
  src/android/app/src/test/java/com/rikkaminis/app/provider/openai/OpenAIResponsesStreamFixTest.kt
java -cp "out:$LIB/kotlin-stdlib-2.1.0.jar:$LIB/json-20231013.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar" \
  org.junit.runner.JUnitCore com.rikkaminis.app.provider.openai.OpenAIResponsesStreamFixTest   # → OK (12 tests)

# 窄门
python3 scripts/scan/truncated_call_guard_check.py    # → exit 0
```
