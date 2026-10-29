# FIX-R3-A — B08（provider 线）十项修复报告

分支：`fix/r3-b08-provider`　基线：`2103729f`　worktree：`wt-b08-provider`

---

## 1. 任务与来源

- 来源货架报告：`/var/minis/shared/work/meta-audit/reports/r3/B08-provider-wire-2026-10-29.md`
  （577 行 / 66 KB，F-01 … F-14 共 14 条）。
- 任务书：`fix-tasks/FIX-R3-A.md`（11 项：F-01..F-08、F-12、F-13 P1；F-09/F-10/F-11/F-14 P2）。
- 报告声明命中率 9–81%，因此**逐条按语义先证**后才动手：

| 条目 | 题面 | 先证结论 |
|---|---|---|
| F-01 | 非流式 OpenAI 解析 `content` 数组时丢/吐 JSON | **成立**（后果比报告轻一档：是"可见回复里出现 JSON 文本"，不是崩溃） |
| F-02 | Gemini 自定义 base 失败时回退内置 5 条 | **成立**（且真码还有 `?key=` 与 cacheKey 两处细节，报告未提） |
| F-03 | Gemini 流内 `{"error":…}` 无分支 | **成立**（后果链报告说得对：EOF 守卫→续写重试） |
| F-04 | `models.add` 的显式数值被 models.dev 覆盖 | **成立但报告细节两处不符**（见 §2.4） |
| F-05 | 非流式返回丢 toolCalls/思考/truncated | **成立（升级）**：`sendMessage`→`sendMessageClamped` 是 minis-model-use 主路径，`truncated` 被丢是"半截答案当成功" |
| F-06 | 官方 401/403 回退内置全集 | **成立**（OpenAI + Anthropic 两处；Gemini 早已有 FIX-1/F-196 守卫） |
| F-07 | Azure 模型列表 URL 把 `/models` 拼到 query 后 | **成立**（实测拼出 `…?api-version=…/v1/models`） |
| F-08 | Responses API effort 绕过实测/声明档位 | **成立（半条）**：档位钳制缺失成立；"自定义 ThinkingRule 不生效"成立但需另一套线格式，登记 |
| F-09 | 空 text 块使 Anthropic 400 | **收窄成立**：真码谓词已覆盖 audio/image（报告引的是旧版本），真缺口只有 `[Text("")]` |
| F-10 | ConfigBridge `catch(Throwable)` 只记日志 | **成立**（但报告列的 5 个 catch 分支/`break` 不存在，真码 4 分支） |
| F-11 | draft 写会话配置回 ok:true | **成立**（且 `SessionConfigWriteThrough` 自己的 KDoc 就写着"caller 必须被告知"） |
| F-12 | 豆包 TTS 非零 code 帧被吞 | **成立**（报告引的 `BufferedReader` 循环/`Doubao TTS returned empty audio` 消息在真树不存在，机制对） |
| F-13 | Anthropic/Gemini 严格要求 `data: ` | **成立**（OpenAI 早已放宽，正是同族第三次复发） |
| F-14 | RSS 探针估算漏 contentParts | **成立**（报告说"漏 text 长度/×2"不对：真码 text 已 ×2，漏的是 `contentParts`、请求级图、工具、生成媒体） |

**证伪/驳回：0 条**；**收窄：2 条（F-09、F-08 半条）**；**升级：1 条（F-05 由 P2 事实上升级处理）**。

---

## 2. 先证细节（真码原文 + 可指认链路）

### 2.1 F-01 `chatCompletionsDeltaText`
真码 `OpenAIProvider.kt` 的 delta 解析里：`d.optJSONArray("content")?.let { … }` 只处理
`type=="thinking"` 的块，紧接着 `delta?.safeOptString("content","")`。`JSONObject.optString`
对数组返回**整段数组 JSON**（`JSON.toString(obj)`），因此 Magistral 阵营的
`[{"type":"thinking",…},{"type":"text",…}]` 会把思考块 JSON 显示成正文，而真正的
`type:"text"` 内容一个字都不出。修法只加一个纯函数读取器，思考路径原样不动。

### 2.2 F-02 `isOfficialGeminiBase`
真码六处失败出口（网络异常、`body == null`、非 2xx 非鉴权、缺 `models` 数组、解析异常、
结果为空）全部 `return LLMModel.allGemini`。`ProviderRepository.refreshModels` 见到非空表
就"成功并替换行"，于是中转 404 会把用户已有模型换成 5 个 Google 型号（还会连带影响分组绑定）。

**设计意图侧证**：`ProviderRepository.kt:2610` 的 `[model-list-source]` 注释原文写着
"a missing or invalid key must leave the list empty (or untouched), never silently filled
from a stale directory"——把内置目录当"答案"填回去，正是这段注释禁止的行为。

### 2.3 F-03 `geminiStreamErrorStatus`
Gemini 的流循环只认 `candidates`；`{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}`
落进 `optJSONArray("candidates") == null → continue`，循环到 EOF。EOF 守卫
（`geminiEofTerminal`，deep-audit-1019 ISSUE-04）在 `contentChars > 0` 时判"截断"→
`Finished(truncated=true)` → 引擎做 **continuation retry**，而服务端已经拒绝过这个请求。
Anthropic 在 `[FIX-1 / F-195]` 里已经把同款用 `throw mapHttpError(...)` 修掉，Gemini 缺这一步。

### 2.4 F-04 两处报告失真（先说清再修）
1. 报告称 `add` 还处理 `inputModalities/outputModalities`——**真码没有**（`ModelEntry` 构造里
   只有 contextWindow/maxOutputTokens/supportsReasoning 三项从 payload 来）。
2. 报告称 `model.provider = canonicalModelProviderName(...)`——**真码是
   `instance.providerType.displayName`**，与该条缺陷无关。
成立的核心：这三项写在 `baseModel` 上，而 `ModelsDevApi.applyDevData` 优先 devModel 值
（`devModel.contextWindow ?: model.contextWindow`），所以 gpt-4o/claude-… 这类 id 在下一次
读取时被目录值覆盖，调用方却已收到 `ok:true`。

### 2.5 F-05 升级理由
`LLMProvider.sendMessage` → `sendMessageClamped` 是 `sandbox/offload/ModelExecutionService`
非流式路径（minis-model-use）的唯一入口，且它把 tools 传下去了。真实状态：
- `Finished.truncated` 未消费 → 半截回答带 `exit_code 0` 返回（**修**）；
- toolCalls / 思考增量无处承载（`LLMResponse` 只有 text/stopReason/usage/mediaAttachments）
  → 要动 `data/model/**`（本支文件集之外），**登记**（R-F05）。

### 2.6 F-06 / F-07
- F-06：`OpenAIModelsApi` 的 `if (!response.isSuccessful)` 直接 `return@withContext fallback`；
  `AnthropicModelsApi` 的"所有候选基址失败"同样 `LLMModel.allAnthropic`。两家都比 Gemini 宽。
- F-07：`buildURL` 是 `"$base/models"`，Azure 的 base 形如
  `https://res.openai.azure.com?api-version=2024-10-21`，且适配器传入的
  `effectiveBaseURL` 已在**整个字符串**尾部追加 `/v1`（含 query 之后），最终拼成
  `…/openai.azure.com?api-version=2024-10-21/v1/models`：`/v1/models` 落在 api-version 的**值里**。

### 2.7 F-08 半条
`buildResponsesAPIBody` 里 `mapThinkingLevelToResponsesEffort(...)?.let { clampEffortForModel(it) }`
只做 MiMo/Agnes 家族钳制；而同一文件的 chat 路径用 `EffortTierLearner.learned(host, model)`
（网关 400 实测枚举）+ `model.reasoningEffortValues` + `ThinkingEffortLadder.clampEffort`。
错误路径明明在 `EffortTierLearner.record(...)` 里学到了合法枚举，下一次请求却照旧发非法档位。
自定义 `ThinkingRule` 那半条：规则引擎产出的是 chat/completions 的字段形状
（`enable_thinking` / `thinking` 对象…），Responses body 是另一套线格式，硬套会发错字段——**登记**（R-F08）。

### 2.8 F-09 收窄
真码 `hasWirePayload` 已含 `audioParts` / `imageParts` / 请求级图（deep-audit-1045 A-03 修的），
报告引的 `contentParts.isNotEmpty() || content.isNotEmpty()` 是旧版。真正剩下的缝：
`contentParts = [Text("")]` 非空 → 通过 → Anthropic 收到 `{"type":"text","text":""}` →
`text content blocks must be non-empty` 400。生产侧产生者已找到：
`AgentLoopState.TerminalErrorPlacement.partsForTerminalError` 明确写 `listOf(Text(""))`
（空助手气泡行），因此"下一条消息起每次 400"是可指认链路。

### 2.9 F-10 / F-11
- F-10：真码四个 catch（`AlreadyExists` / `PermissionDenied` / `ConfigError` / `Throwable`），
  只有前三个写 `earlyError`；`Throwable` 仅 `AppLogger.error`。批量里抛 RuntimeException 时
  `applied.length()==0 && earlyError==null` → 落 `all_rejected`（"User rejected every row."）。
  报告说的 `catch (e: ConfigError.WriteFailed)` 与 `break` 在真树**不存在**（`ConfigError` 无
  `WriteFailed` 子类），按报告写会不编译。
- F-11：`onDraftWithoutRow` 只 `AppLogger.warning`，而 `SessionConfigWriteThrough` 的 KDoc 原文
  写着"the caller has to be told, because the write reported success"。

### 2.10 F-12 / F-13 / F-14
- F-12：`if (frame.optInt("code", -1) != 0) continue` 吞掉业务错误帧，最后只抛"no audio frames"。
- F-13：Anthropic `if (!l.startsWith("data: ")) continue`、Gemini 同款；SSE 规范里空格可选。
  这已是同族第三次（OpenAI 先修过一次，另两家各留一份）。
- F-14：真码 `approxInputBytes` 只看 `content`+`imageParts`+`audioParts`，但 T132 之后
  工具调用/工具结果/历史图都在 `contentParts`，`content` 在重活轮次是**空串**；
  另外 `streamMessage` 的 `onEach` 不数 `ToolCallComplete`/`MediaAttachment`。

---

## 3. 修法（按文件；阶梯停点）

| 文件 | 改动 | 阶梯停点 |
|---|---|---|
| `provider/openai/OpenAIProvider.kt` | 新增 `chatCompletionsDeltaText`（数组→text 块）；`sendMessageClamped` 收 `truncated` 并抛 `LLMError.TransientError`；新增 `clampResponsesEffort` 并在 Responses body 使用；删除已被共享实现取代的私有 `clampEffortForModel`；SSE 前缀改用共享读取器 | ⑦ 最小实现（复用已有 `ThinkingEffortLadder` / 已有 `extractThinkTags` 路径，不另造） |
| `provider/openai/OpenAIModelsApi.kt` | `buildModelsURL(baseURL, azureMode)`；`usesBuiltinCatalogOnFailure(isCustomBase, status)`；两函数下沉为顶层 internal | ② 代码库里已有 `azureUrl` 同款 split-rejoin 形状 |
| `provider/anthropic/AnthropicModelsApi.kt` | 末尾回退改用 `usesBuiltinCatalogOnFailure`（顶 layer） | ⑦ |
| `provider/anthropic/AnthropicProvider.kt` | SSE 前缀走 `sseDataPayload`；序列化跳过空 Text 块 | ⑤ 复用共享函数 |
| `provider/gemini/GeminiProvider.kt` | SSE 前缀走 `sseDataPayload`；新增行内 `error` 帧分支 + `geminiStreamErrorStatus` | ⑤/⑦ 对齐 Anthropic FIX-1/F-195 既有形状 |
| `provider/gemini/GeminiModelsApi.kt` | `isOfficialGeminiBase` + 六处失败出口改 `fallback` | ⑦ |
| `provider/MessagePayloadFilter.kt` | `hasWirePayload` 收紧为"至少一个非空载荷"；新增 `sseDataPayload` | ② 已有谓词扩展 |
| `provider/LLMProvider.kt` | 探针估算：`approxInputBytes(messages, turnImageParts)` 覆盖 `contentParts`/请求级图；`approxOutputBytes` 加媒体；`approxOutputChunkBytes` 覆盖工具完成帧/媒体（`ToolInputDelta` 不重复计） | ⑦ |
| `provider/ModelListProviderAdapters.kt` | Azure 传 raw `customBaseURL` | ① 一行 |
| `provider/voice/VoiceProviderVendors.kt` | `doubaoFrameError(code, message)` + 非零帧抛出 | ⑦ 对齐同文件讯飞抛错风格 |
| `config/ConfigBridge.kt` | `catch (Throwable)` 记 `write_failed` + reason | ⑦ |
| `config/ConfigBuiltins.kt` | `onDraftWithoutRow` 抛 `ConfigError.InvalidValue`（写失败语义，`ConfigError` 无 WriteFailed 子类） | ⑦ |
| `config/collections/ModelsCollection.kt` | add 时三项显式值同时写 `overrides` | ⑦ |

**刻意不做**（半径纪律）：
- F-10 的"部分成功被 ok:true 掩盖"是**所有**错误分支的既有语义（不只 Throwable），改判定
  = 动 minis-config 契约，超出本支半径 → 登记 R-F10。
- F-09 的 OpenAI 侧 `content:""`（有 tool_calls 时是合法形状）不动。

---

## 4. 装置（本地可复现）

`/var/minis/shared/work/meta-audit-1029/probe-a/`：

- `extract.py`：从 worktree **逐字切片** 10 个顶层声明（`body in source` 断言 + 注释/字符串
  屏蔽后的括号配平），编译的就是上线的代码，不是复刻。
- `run_jvm.py`：把**真实测试文件**（7 个新类）copy 进来，`kotlinc` + `JUnitCore` 直跑。
  读数：**TESTS=50 FAILURES=0**。
- `arms.py`：9 条变异臂（把修复前行为塞回去），全部 **MATCH**（红）：
  A1 content-array 3 红 / A2 no-ladder 3 / A3 azure-append 4 / A4 openai-auth 2 /
  A5 anthropic-auth 2 / A6 relay-official 2 / A7 status-map 1 / A8 strict-prefix 3 /
  A9 doubao-swallow 2。**ARMS=9 BAD=0**。
- `check_shape.py`：`MetaR3B08WiringTest` 的本地镜像（同一套断言、同样的去注释实现）
  **FAILS=0**，另有 14 个改动文件的括号配平逐文件校验。

CI 侧另外两个测试类（`ProviderRssEstimateTest`、`MetaR3B08WiringTest`）依赖应用内数据类型 /
源码形状，只在云端 `:app:testReleaseUnitTest` 里跑。

---

## 5. 触碰面与不变式

- 生产 12 文件 + 测试 10 文件（8 新增 / 1 扩展 / 1 复用），**`scripts/scan/**`、`scan.sh`、
  `test_scan.py`、`backup/**`、`ui/**` 零触碰**；门 113 / 夹具 349 不变。
- 新增顶层 internal 纯函数 6 个：`chatCompletionsDeltaText`、`clampResponsesEffort`、
  `buildModelsURL`、`usesBuiltinCatalogOnFailure`(×2 包)、`isOfficialGeminiBase`、
  `geminiStreamErrorStatus`、`sseDataPayload`、`doubaoFrameError`、`approxInputBytes` /
  `approxContentPartBytes` / `approxOutputBytes` / `approxOutputChunkBytes`。
- 行为面变化（对外可感）：
  1. 非流式（minis-model-use）截断时由"静默半截"变为 `model_use_failed`；
  2. `minis-config set models.<id>` 在写 draft 会话时由 ok:true 变 ok:false；
  3. 官方 401/403 刷新模型不再"成功并替换行"；
  4. Anthropic/Gemini 接受无空格 `data:`（只增不减）；
  5. 豆包 TTS 业务错误帧变为异常（原本返回半截音频）。

---

## 6. 残留登记（三件套齐）

| 编号 | 内容 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R-F05 | 非流式 `LLMResponse` 无处承载 toolCalls / 思考增量，tool-only 轮次仍返回 `""` | 有人在 `minis-model-use` 里对工具型模型做非流式调用并期待工具结果 | 给 `LLMResponse` 加 `toolCalls`/`reasoning` 字段（跨 4 层：模型 / 快照 / worker IPC / 消费方） | 需改 `data/model/**`，本支文件集之外 |
| R-F08 | Responses API 不应用自定义 `ThinkingRule`（规则引擎产出 chat/completions 字段形状） | 有人在 Responses 实例上配自定义思考规则并期望生效 | 给规则引擎加"目标线格式"维度，或为 Responses 写第二套映射 | 需要新抽象，超出单文件修复半径 |
| R-F09 | OpenAI 侧 `content:""`（仅空 Text + tool_calls）仍原样发送 | 严格中转拒绝空 content 字符串 | 只在 `textParts.all { it.isEmpty() }` 时省略 content 字段 | `content:""` + tool_calls 是 OpenAI 合法形状，无生产故障证据 |
| R-F10 | 批量写入部分失败仍整体 `ok:true`（缺 `partial` 语义，所有错误分支共有） | `minis-config set` 批量写入 + 至少一条失败 + 至少一条成功 | 给响应加 `applied`/`failed` 双列表并让 `ok` 为三态 | 动 minis-config 契约，需用户拍板 |
| R-F11 | `ConfigError` 无 `WriteFailed`，写入失败在线上报 `invalid_value` + reason | 消费方按 error code 分支处理 | 新增 `write_failed` 错误码并补 CLI 文档 | 新错误码是契约扩展 |
| R-F07 | Azure 未给 `api-version` 时列表仍会 400（本支只修拼装，不发明默认版本） | Azure 实例的 customBaseURL 不带 `?api-version=` | UI 侧为 Azure 提供默认 api-version 选择 | 默认版本会老化，属于产品决策 |
| R-F12 | 豆包 `code` 缺省语义从 -1（丢）改 0（收）——当前线上帧都带 `code` | 出现不带 `code` 的错误帧（会被当成数据帧） | 用 `message`/`data` 双缺判据收紧 | 无证据，且收紧会误伤数据帧 |
| R-F14 | 探针单位仍是"UTF-16 字符 ×2"近似 | 需要精确字节归因 RSS 峰 | 用真实 UTF-8 长度（`text.toByteArray().size`，有额外分配） | 探针是观测用，改单位会让历史读数不可比 |

---

## 7. 风险与未验证

1. **真机未验**：全部证据 = 编译级（CI）+ JVM 级（50 例）+ 源码形状级（38 判据）。
   F-01/F-03/F-06/F-07 都需要真实第三方端点才会触发，沙箱无法造。
2. **`sendMessageClamped` 抛错的行为变化**：非流式调用方（minis-model-use）从"半截文本 +
   exit 0"变为 `exit 1` + 原因。这是有意的诚实化，但对既有脚本是可见变化。
3. **`hasWirePayload` 收紧**：只影响"全是空 Text 的消息"，即空助手气泡；无文本但有
   audio/image/请求级图的消息仍然通过（已有测试守住）。
4. **豆包 code 缺省值变更**：若某天出现"不带 code 的错误帧"，会被当成数据帧收下（R-F12）。
5. **F-04 的 overrides 写入**：会让详情屏对这三项显示"已覆盖"标记（这是事实，不是回归）。
6. **既有测试夹具被修正**（`OpenAIProviderTest`，11 处）：这些用例断言的是**请求形状**
   （鉴权头 / temperature / max_tokens / 流式），夹具只发了 `content` 就 `[DONE]`。
   按既有截断判据（`truncated = !sawFinishReason`）那确实是一条被截断的流 —— 第一轮 CI
   （run `40739752564`，head_sha `bdff06a4`）正是被这 10 个用例打红，这本身是修复有效的
   证据；随后给夹具补 `finish_reason:"stop"`（测试文件自己的注释就写着"real OpenAI
   streams always carry finish_reason before [DONE]"），断言未改。

---

## 8. CI 读数

| 项 | 值 |
|---|---|
| 分支 CI | `#2858` / run `40742217153` / **success** / 18m52s（18:07:49Z → 18:25:41Z） |
| head_sha | `181b41e8cdb68cf3a6b1a1beb434dabb422e0a4c` —— 与本地 `git rev-parse HEAD` **逐字符一致** |
| 步骤清点 | 27 步，唯一非 success = **2 skipped**（`Move the rolling tag android-latest` + `Publish to Releases`，均 `if: main` 门控）⇒ 0 个失败步骤 |
| 日志体量 | 1,586,563 B / **NUL 0** |
| 门集 | `Summary: 113 passed, 0 failed` ×1（= 基线 113 道门，未触碰 `scripts/scan/**`） |
| 夹具 | `ALL 349 CASES PASS` ×1（= 基线 349） |
| 编译 | `^e: ` **0** 条 |
| 假 FAILED | 含 `FAILED` 的行 6 条，逐条核对**均为 `PASSED` 用例名子串**（真失败 0 条） |

新增测试类在云端逐类全绿：

| 类 | PASSED |
|---|---|
| `ChatCompletionsDeltaTextTest` | 7/7 |
| `ResponsesEffortClampTest` | 8/8 |
| `OpenAIModelsUrlAndFallbackTest` | 11/11 |
| `AnthropicModelsFallbackTest` | 5/5 |
| `GeminiModelsAndStreamErrorTest` | 8/8 |
| `SseDataPayloadTest` | 6/6 |
| `DoubaoFrameErrorTest` | 5/5 |
| `ProviderRssEstimateTest` | 13/13 |
| `MetaR3B08WiringTest`（形状接线） | 11/11 |
| `OpenAIProviderTest`（既有，首轮 10 红） | 24/24 |
| `MessagePayloadFilterTest`（既有 + F-09 用例） | 11/11 |

**首轮失败记录（有效证据，不是事故）**：run `40739752564` / `#2854` @ `bdff06a4` = **failure**，
唯一失败步骤是 `Run unit tests`，10 个 `OpenAIProviderTest` 用例报
`LLMError$TransientError: Stream ended before completion` —— 正是 F-05 的判据命中"只发 content
就 [DONE]"的旧夹具。修正见 §7.6。

**收口判据**：报告提交后 `git diff --name-only 181b41e8..HEAD -- . ':(exclude)fix-reports'` 为空。

