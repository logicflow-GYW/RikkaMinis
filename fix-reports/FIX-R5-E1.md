# FIX-R5-E1 — OpenAI 生图下载校验 + 流内计数器 + 顶层图片挂载

- 分支：`fix/r5e-openai`（worktree `/var/minis/shared/work/witness-1027/wt-r5e-openai`）
- 基线：main `35509888`
- 任务书：`/var/minis/shared/work/r5-ef/fix-tasks/FIX-R5-E1.md`
- 判级台账：`/var/minis/shared/work/r5-ef/TRIAGE-R5-EF.md`
- 对应报告：`/var/minis/shared/work/meta-audit/reports/r5/R5-E-provider-protocol.md`（`F-05` / `C-01` / `F-06`）

---

## 1. 来源与判级

| 项 | 报告编号 | 本支判级 | 判级依据 |
|---|---|---|---|
| `/images/generations` 的 url 二次下载不校验状态码 | R5-E `F-05`（claim NEW） | **P1 修** | 可感知体验降级 + 误导性错误：403/404/500 的错误体被当成图片返回，界面裂图且**没有任何错误**。用户能碰到（签名 URL 过期是生图中最常见的失败形态） |
| SSE 活跃度计数器对数组型 `delta.content` 失真 | R5-E `C-01`（PARTIAL） | **P2 顺路** | 只影响一条诊断日志（T321 误报），不改控制流；同文件、同一段读取语义，顺路成本≈0 |
| 结构化 `contentParts` 分支漏挂顶层 `imageParts`（OpenAI 双臂） | R5-E `F-06`（claim NEW） | **P2 顺路** | 机制成立，但**无生产同传路径**（见 §2.3 的证伪部分）：两条生产通道各只用一个载体。属 latent，靠同文件顺路修 |

本支**只动** `provider/openai/OpenAIProvider.kt` + 新增一个测试文件；`scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**` 零触碰。

---

## 2. 逐条先证（按语义在真树上定位，不按报告行号对齐）

### 2.1 `E-F-05` —— **成立**（比报告描述更宽：错误体还会被 Content-Type 贴错标签）

真树定位：`parseImageGenerationsResult`（`OpenAIProvider.kt:1801` 起）的 `client.newCall(dlReq).execute().use { dlResp -> ... }` 内，改造前**只有两个判据**：

1. `dlResp.body?.contentLength()` 与 `MAX_GENERATED_IMAGE_BYTES` 比较（声明长度上限）；
2. `readBodyBounded(...)` 之后 `dlBytes != null && dlBytes.isNotEmpty()`。

全程**没有** `dlResp.isSuccessful` / `dlResp.code` 的任何读取。随后：

```kotlin
val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
```

而 `detectImageMime`（同文件 `:2834` 区域）的最后一条分支是 `else -> "image/png"`。

⇒ 判据成立：**非 2xx 的错误体一定会被封装成一个 `IMAGE` 附件**，且标签由 `hintMime`（xAI 的 `mime_type`，**最优先**）或 `Content-Type` 或魔数兜底决定 —— 也就是说，即使是 `text/html` 的错误页，只要响应里带了 `mime_type` 字段就仍然被标成图片。上层拿到的是「成功 + 一张图」，界面渲染裂图，日志里一行警告都没有（M7 假成功）。

同时确认：`b64_json` 分支不受此条影响（解码失败走自己的 `catch`，不加附件），但它同样不★报错 —— 归 §6 R2。

### 2.2 `E-C-01` —— **成立**（缺陷方向与报告一致，量化后更清楚）

真树定位：SSE 循环的 [T321] 活跃度块（`:985-1011`）与发射块（`:1268-1290`）。

改造前：

```kotlin
val cLen = delta.optString("content", "").length
val rcLen = delta.optString("reasoning_content", "").length
val rLen  = delta.optString("reasoning", "").length
...
contentLen += cLen
reasoningLen += rcLen + rLen
```

而 `JSONObject.optString` 对**数组**字段返回的是数组的 JSON 源码。用本支夹具实测（`R5E1WiringTest`，固定输入 = 10 字符思考块 + 3 字符正文）：

| 读法 | 值 |
|---|---|
| `delta.optString("content","").length`（旧） | **99** ← 整个数组源码 |
| `chatCompletionsDeltaText(delta).length`（新） | **3** ← 真正的可见正文 |
| 数组思考块长度（旧：不计） | 0 |
| 数组思考块长度（新：`chatCompletionsDeltaThinkingBlocks`） | **10** |

⇒ 触发条件（`ReasoningConsistency.missingReasoningContent(lastUsageJson, reasoningLen)`，`:1446`）在 Mistral Magistral 这类方言下必然命中：上游按思考 token 计费，而 `reasoningLen` 恒为 0 ⇒ `[T321] inconsistency: upstream billed N reasoning tokens but the stream carried 0 reasoning content` 每条健康响应都报一次。判据成立。

### 2.3 `E-F-06`（OpenAI 双臂）—— 机制**成立**，但报告建议的「直接追加」**会把常见路径改坏**

真树定位：`buildRequestBody`（Chat Completions，`:2214` 起）与 `buildResponsesAPIBody`（`:3261` 起）的 USER 分支。两处的 USER 分支各自 `val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()`（Chat）／`val imgParts = ...`（Responses），而请求级 `imageParts` 参数**只在 `contentParts.isEmpty()` 的 else 分支**被读（`attachTopLevelImages`）。⇒ 机制成立，且 Chat 侧还额外证明了「参数为何不可达」：**同名局部变量把参数遮蔽了**。

但「直接把请求级图片追加进 contentParts 数组」的补丁建议会引入一个更重的问题，本支用三条取证把它挡下：

1. **双载体是生产事实**：composer 把同一份字节同时写进 `contentParts(ImageData)`（`ChatViewModel.kt:3985`、`ChatStreamToolHelpers.kt:148`、`ChatQueueInterruption.kt:152,333`）与投递参数的 `imageParts`。
2. **另一侧本来就有守卫说明这件事**：`ModelExecutionDispatcher.buildRequestJson` 的 `[PERF-04]` 段写着「当最后一条 user 有 contentParts 时，providers 从 contentParts 读图并忽略该参数，所以再投 `image_parts` 是纯死重」，并**真的不投**（`if (imageParts.isNotEmpty() && !lastUserHasContentParts)`）。
3. ⇒ 如果按建议盲追加：**worker 路径每张聊天图上线两次**（一次来自 contentParts，一次来自新追加的请求级），token 与带宽翻倍，模型还可能把同一张图当两张不同图推理。

所以本支的修法是「**只追加这一轮还没有的图**」，判据按**内容**（`contentEquals`）而不是引用：worker 侧 contentParts 是从 JSON 解码出来的，两侧永不共享数组实例。

另：`model_use` 通道的 messages 没有 contentParts（`ModelUseOffloadHandler:274` 侧），走 legacy 分支，行为逐字节不变。

### 2.4 顺带核对（未改，登记）

- `truncated_call_guard_check` C3 钉住的那行 EOF 守卫（`if (!finishedSent && (contentLen > 0 || reasoningLen > 0 || toolCallEventCount > 0)) {`）**逐字未动**，形状探针 S10 专门钉它。
- `[T321]` 的**两条**告警文案与触发条件一字未改（S9 钉 `inconsistency` 与 `stream ended WITHOUT finish_reason` 两条字面量）。

---

## 3. 修法

### 3.1 `E-F-05`

`parseImageGenerationsResult`：

1. 每次调用新增 `var lastDownloadStatus: Int? = null`（按调用，不跨调用留存）。
2. `use { dlResp -> }` 内**先判状态码**：`GeneratedImageVerdict.downloadAccepted(dlResp.code)` 为假 ⇒ 记下 `dlResp.code` 并 `AppLogger.warning`（**含状态码 + 响应体前 200 字**，**不含 URL** —— 生图链多是签名对象 URL，query 里就是凭据），然后**不加附件**（inline lambda 内不能 `continue`，Kotlin < 2.2 的既有约束在注释里已写明，沿用）。
3. 出口守卫：`GeneratedImageVerdict.noUsableImageMessage(declaredItems = dataArray.length(), attachmentCount = attachments.size, lastDownloadStatus = ...)` 非空 ⇒ 抛**既有的** `LLMError.ProviderError`（不新造错误类型），消息里带上一跳 HTTP 码。

判据抽成纯对象 `GeneratedImageVerdict`（同文件尾），两个方法都无副作用、无 IO，专供 JVM 表驱动。

### 3.2 `E-C-01`

1. 新增 `chatCompletionsDeltaThinkingBlocks(delta): List<String>`（顶层 `internal`，与既有 `chatCompletionsDeltaText` 并列）——**返回块列表而不是拼接串**，以保住发射侧「每块一个 `ThinkingDelta`」的粒度。
2. 发射循环从手写双层 `for` 改为 `for (thinkingText in chatCompletionsDeltaThinkingBlocks(d))`（同一个发送体，行为等价）。
3. 计数器改为 `cLen = chatCompletionsDeltaText(delta).length`、`reasoningLen += rcLen + rLen + blockThinkingLen`。
4. 逐事件 debug 行（非两条 T321 告警）的触发条件加上 `blockThinkingLen`，保持「只有思考块的事件也会打印」这一既有观察面。

### 3.3 `E-F-06`

1. Chat 侧在进入消息循环前捕获 `val requestTurnImages = imageParts`（参数在同一函数内被 USER 分支遮蔽，必须先捕获才有名字可用）。
2. 两侧 USER 结构化分支新增：

```kotlin
val extraRequestImages =
    if (index == lastUserIndex) {
        TurnImageCarry.missingIndices(
            turnImages = <本轮自己的 ImageData>.map { it.data },
            requestImages = requestTurnImages.map { it.data },
        ).map { requestTurnImages[it] }
    } else emptyList()
```

3. 分支条件从「有图或有文本」扩为「有图或有文本或有待补的请求级图」（否则「只有请求级图 + contentParts 里只有别的东西」仍被跳过），并把待补图**并入该轮自己的遍历**：

```kotlin
val wireTurnParts =
    if (extraRequestImages.isEmpty()) msg.contentParts
    else msg.contentParts + extraRequestImages.map { AgentContentPart.ImageData(it.data, it.mimeType) }
for (part in wireTurnParts) { ... }        // 既有的 Text / ImageData 序列化分支，一行未改
```

**为什么不是「再调一次 helper」**：第一版就是这么写的（`OpenAIToolResultImages.chatUserContent(entries = extras…)`），CI run 1 被既有的两条**形状判据**当场打红 —— `OpenAIToolResultImagesTest` 数调用点（`chatUserContent` 必须 1 处、`responsesUserContent` 必须 2 处）。**门是对的**：图片序列化就该只有一个入口，多开一个入口正是「两套序列化会漂移」的起点。改成并入既有遍历后：零新调用点、零新编码器、零新格式，`extra` 图与 turn 图走**同一段** `ImageData` 分支（同样的 `compressUnderBudget`、同样的 `NO_WRAP`）。

顺带消掉一个我自己造的雷：第一版给新调用点传的 `encodeBase64 = { Base64.encodeToString(it, Base64.NO_WRAP) }` 在 JVM 单测里 **NPE**（`android.util.Base64` 由 `unitTests.isReturnDefaultValues` 兜底返回 `null`，而 lambda 的类型是非空 `String`；生产里既有内联写法因为平台类型不会炸，只是把 `null` 拼进 data-uri）。并入遍历后该 lambda 不存在，问题同源消失。

**这改变什么用户行为？** ①非 2xx 的图片下载不再变成裂图，改为一条带状态码的失败（P1，用户会看到错误而不是坏图）；②Mistral 类方言不再刷 T321 误报（日志可读性）；③（当前不可达）调用方在结构化末轮传顶层图时，图不再被静默丢弃，且双载体路径不会重复上线。

---

## 4. 证据与装置

装置全部在 `/var/minis/shared/work/r5e1-probe/`（**不进仓库**，仓库内只新增 CI 级契约测试）。

### 4.1 JVM 纯判据表 —— 16 例全绿

- `extract.py`：从**真源**逐字切出 4 个成员（`chatCompletionsDeltaText` / `chatCompletionsDeltaThinkingBlocks` / `TurnImageCarry` / `GeneratedImageVerdict`）到 `slice.kt`，并按名字+括号配平切割；切完再断言每段文本仍在源文件里（**不手抄**）。
- `stubs.kt`：逐字复制 `provider/JsonExt.kt` 的 `safeOptString`（4 行）。
- `R5E1WiringTest.kt`：16 例，**全部手写字面量**：状态码 200/204/199/301/403/404/500、消息文本、去重索引表、思考块列表、以及旧读法 99 字符 vs 新读法 3 字符的对照。
- 结果：`OK (16 tests)`。

### 4.2 形状探针 —— 10 判据，钉「真的被调用」

`shapes.py <provider.kt>`：S1 状态码门在 body 读取**之前**、S2 失败状态被记录、S3 出口守卫按 `dataArray.length()` 且抛既有错误类型、S4 计数走共享读取器、S5 发射侧无第二套读取器、S6/S7 两个 builder 的**合并遍历**（`val wireTurnParts =` + `msg.contentParts + extraRequestImages.map {`）与分支条件、S8 未遮蔽的捕获、S9 两条 T321 文案、S10 EOF 守卫行（被 `truncated_call_guard_check` C3 钉住的那行）。

### 4.3 变异臂 —— 14/14 MATCH

`arms.py`：9 条**文本级**臂（改真源副本 → `shapes.py` 必须点名对应判据）+ 5 条**JVM 级**臂（改切片 → 重编译 → 指定用例必须红）：

| 臂 | 塞回的缺陷 | 变红的是 |
|---|---|---|
| M1 | 去掉状态码门 | S1 |
| M3 | 计数器回 `optString` | S4 |
| M4 | 不计数组思考块长度 | S4 |
| M8 | 发射侧换回占位读取 | S5 |
| M9 | Chat 分支条件回退 | S6 |
| M10 | Responses 分支条件回退 | S7 |
| M11 | 去掉未遮蔽捕获 | S8 |
| M12 | 改 T321 文案 | S9 |
| M13 | 改 EOF 守卫行 | S10 |
| J1 | 3xx 也算成功 | `downloadAccepted is 2xx only` |
| J2 | 出口守卫恒 null | `all downloads failed throws with status` |
| J3 | 去掉内容去重 | `same bytes already in the turn are not re-sent` |
| J4 | 永不追加请求级图 | `distinct bytes are carried exactly once` |
| J5 | 思考块读取器恒空 | `thinking blocks extracted with literals` |

（J5 第一版臂把 `val array = ...` 整行替换，导致编译失败、判不出红 —— 改成「让 `type != "thinking"` 恒跳出」后仍绿才算数。**改臂要比改判据更小心**。）

### 4.4 CI 级契约测试（真 provider + MockWebServer）

新增 `src/android/app/src/test/java/com/rikkaminis/app/provider/openai/R5E1OpenAIImageContractTest.kt`，6 例：

- 403 下载 ⇒ `LLMError.ProviderError`，消息**逐字**等于 `Provider error: images/generations declared 1 image item(s) but none was usable: (last download HTTP 403)`；
- 500 下载 ⇒ 同样抛，消息含 `HTTP 500`；
- 2xx 下载 ⇒ 附件 1 张、`image/png`、10 字节（正向对照臂，防「一律不返回图」这种过度修复）；
- 末轮 `contentParts=Text` + 请求级图 ⇒ 请求体里 `text=1` + `image_url=1`；
- 双载体同字节（**不同数组实例**，模拟 worker 解码后的形态）⇒ `image_url=1`（不得翻倍）；
- 请求级图与本轮图不同 ⇒ `image_url=2`。

### 4.5 相关门脚本（12 道，全绿）

`tool_result_image_branch_check` · `truncated_call_guard_check` · `thinking_wiring_guard` · `provider_ua_parity_check` · `log_url_redaction_guard`（18 个审计点；本支新日志**刻意不带 URL**，只带状态码与 body 前缀）· `debug_leak_guard` · `offload_media_b64_wire_check` · `discarded_return_guard` · `loop_counter_reset_guard` · `process_logging_gate` · `provider_boundary_guard` · `modeluse_proto_single_source_check`。

未跑全量 `scan.sh` / `test_scan.py` / gradle（负载纪律）。

---

## 5. CI

**最终绿：run #2924 / `40890556458`**，`head_sha = 03e4459e17c9b549218328bb50be851884ced4b7`（与本地 HEAD 逐字符一致），**success 21.2 min**（`14:20:53Z → 14:42:03Z`，attempt 1），27 步 **25 success + 2 skipped**（`Move the rolling tag` + `Publish to Releases`，均 main 门控）。

日志判读（job 日志 723,328 B zip → 3,301,537 字符，NUL 0）：

| 判据 | 读数 |
|---|---|
| `Summary: 113 passed, 0 failed` | ✔（门 113，与基线一致；`scripts/scan/**` 本支零触碰） |
| `ALL 349 CASES PASS` | ✔（夹具 349，与基线一致） |
| `Summary: N passed, 0 failed` 其余分片 | 16/18/19/25/30/44 全部 0 failed；另有 2 条 `Summary: 0 violation(s) found`（i18n 检查，非测试分片） |
| `^ e: file` 编译错误 | 0 |
| 真 ` FAILED$` 行 | 0（12 条含 `FAILED` 的行全是 PASSED 用例名子串，逐条确认） |
| `R5E1OpenAIImageContractTest` 6 例 | 全 PASSED（两遍：release/debug 变体各跑一次） |
| `OpenAIToolResultImagesTest` 12 例 | 全 PASSED（含被我改回绿的两条形状判据） |

**run 1（`40886000685` @ `7e633f8e`）是红的，两个红都是判据在工作**：

1. `OpenAIToolResultImagesTest` 的两条 `countOf(...)` 形状判据（`chatUserContent == 1`、`responsesUserContent == 2`）打红 —— 我第一版为「追加请求级图」新开了一个 helper 调用点，把它们顶成 2/3。**门是对的**：图片序列化只该有一个入口。改实现不改门（并入既有遍历，见 §3.3）。
2. 我自己的两条契约测试 NPE：`android.util.Base64` 在 JVM 单测里由 `unitTests.isReturnDefaultValues` 兜底返回 `null`，而新调用点的 `encodeBase64` lambda 是非空 `String` 类型 ⇒ `encodeToString(...) must not be null`。并入既有遍历后该 lambda 消失（生产里既有的内联写法因为平台类型不会炸，只是会把 `null` 拼进 data-uri —— 见 §6 R7）。

⇒ 本支的「先证后修」不止对生产成立，对**我自己的补丁**也成立：run 1 证明第一版实现越过了既有形状契约。

---

## 6. 残留（每条带触发条件 + 方案）

| # | 残留 | 触发条件 | 方案 |
|---|---|---|---|
| R1 | **200 的错误页仍会被当图片**：`downloadAccepted` 只看状态码；`ctMime`/`detectImageMime` 仍会把 `text/html` 内容标成 `image/png`（带 `hintMime` 时更是直接用对方的标签） | 某中转用 `200 + 错误页` 响应图片 URL（软 404），或对象存储返回登录页 | 把 `Content-Type: text/*` 当**负面证据**，或在 `detectImageMime` 增加 `null`（无法识别）分支并由调用方跳过 + 警告。**需要先确认没有正常链路依赖「无魔数也算 png」**（例如某些 webp/avif 变体），所以本支不扩半径 |
| R2 | **`b64_json` 逐条解码失败仍无原因**：现在出口守卫会兜成一次异常，但「2 张里 1 张坏」只有一个总数，没有哪一条坏 | 上游返回非法 base64（截断/空串） | 在 `catch` 里补一条逐 item 警告（含 `data[<i>]`）。本支只做了出口守卫，是因为半径纪律 |
| R3 | **出口守卫的判据是 `data.length() > 0`**：若某 provider 的 `data` 里全是占位条目（只有 `revised_prompt`，无 `url`/`b64_json`），会抛错 | 出现「声明了 data 条目但根本没打算给图」的厂商 | 改成「有过下载尝试或有过 b64 尝试」计数（`attemptedItems`），而不是 `data.length()`。改了要同时修 §4.1 的三条表断言 |
| R4 | **F-06 的 Anthropic / Gemini 臂未修** | 调用方在 `contentParts` 非空时给这两家传顶层 `imageParts`（当前无此路径） | 复用 `TurnImageCarry.missingIndices`（判据与协议无关），各自套自己的图片编码器；本支受任务书文件面限制未动 |
| R5 | **Responses API 的 `response.reasoning_*` 计数未动** | —（确认无需改） | 它按事件类型累加字符串 `delta`，不存在「数组型 content」同款失真，记录备查 |
| R6 | **真机未验** | — | 本支证据 = JVM 纯判据 16 例 + 形状 10 判据 + 14 臂 + CI 编译/单测 + 12 道门；真机面建议在 E/F 波次合并后按「生图 → 断网/改 base 使下载 404」走一次 |
| R7 | **`android.util.Base64` 在 JVM 单测里返回 `null`**：既有内联写法（`val b64 = Base64.encodeToString(...)`）不会崩，但会把 `null` 拼进 `data:…;base64,null`；任何「真的跑图片序列化」的单测都拿不到真实字节（本支不新开调用点之后就绕开了，但既有 6 处仍如此） | 有人写第二个真跑生产图片路径的 JVM 测试时 | 把该文件 6 处换成 `java.util.Base64.getEncoder()`（同字母表、同 NO_WRAP），或抽一个 `internal fun encodeBase64NoWrap`。**需要一次全量 CI 复核**（6 处都属既有路径，半径纪律下本支不动） |

---

## 7. 复现

```sh
# 1) 纯判据（16 例）
cd /var/minis/shared/work/r5e1-probe
python3 extract.py
kotlinc stubs.kt slice.kt R5E1WiringTest.kt \
  -cp $L/kotlin-stdlib-2.1.0.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar:$L/json-20231013.jar -d out
java -cp out:$L/... org.junit.runner.JUnitCore probe.R5E1WiringTest     # OK (16 tests)

# 2) 形状 + 变异臂（14/14）
python3 shapes.py /var/minis/shared/work/witness-1027/wt-r5e-openai/src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt
python3 arms.py

# 3) 相关门
cd /var/minis/shared/work/witness-1027/wt-r5e-openai
python3 scripts/scan/tool_result_image_branch_check.py .
python3 scripts/scan/truncated_call_guard_check.py .
```

沙箱注意：`arms.py` 的工作目录不能放 `/tmp`（S28 `Function not implemented`），已改为 `r5e1-probe/arms-tmp`。

---

## 8. 变更面

```
 src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt | 274 + / 60 -
 src/android/app/src/test/java/com/rikkaminis/app/provider/openai/R5E1OpenAIImageContractTest.kt | 256 + / 0 -
```

- 生产改动集中在三处（下载分支 / 计数器与发射 / 两个 builder 的 USER 分支）+ 文件尾两个纯判据对象与一个纯读取函数；**没有新增任何 `OpenAIToolResultImages` 调用点**（既有形状判据计数 1 / 2 不变）。
- 下载分支的 diff 行数偏大是因为状态码门把原有块整体缩进了一层（**语义未变**，`readBodyBounded` 调用仍只有一处、体积上限与 chunked 处理逐字保留）。
- `scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**`：**零触碰**。
