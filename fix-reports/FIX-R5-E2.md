# FIX-R5-E2 — Gemini 非流式假成功 + 官方端点判定（1 P1 + 2 顺路）

- 分支：`fix/r5e-gemini`（基线 main `35509888`）
- 生产改动：`GeminiProvider.kt` +177/−16、`GeminiModelsApi.kt` +49/−7（仅本支独占的两个文件）
- 测试：`GeminiNonStreamingVerdictTest.kt`（273 行，纯表）+ `GeminiNonStreamingWireTest.kt`（213 行，MockWebServer 真请求路径）
- 零触碰：`scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**`、OpenAI/Anthropic 任何文件

---

## §1 缺陷与根因（按语义回本树核实，不采信报告行号）

### E-C-02②（P1）—— 成立，机制与报告一致

修复前 `sendMessageClamped`（HEAD 版 `GeminiProvider.kt:133-163`）：

```
val json = JSONObject(responseBody)
val text = extractText(json)                 // candidates 缺失 ⇒ 返回 ""
val finishReason = extractFinishReason(json) // 缺失 ⇒ null
...
LLMResponse(text, finishReason ?: "end_turn", …)   // :163
```

核验三点，全部对上：
1. `grep -n 'optJSONObject("error")'` 在 HEAD 版只有 **1 处**（流式臂 `:312`），非流式臂**完全没有** error 对象检查；
2. `extractText` 对缺失/空 `candidates` 返回空串（`optJSONArray(...) ?: return ""`），不抛错；
3. `finishReason ?: "end_turn"` 把「没有任何候选」和「有候选但无 finishReason」当成同一件事。

后果链：`200 {"error":{"code":429,…}}`（反代/网关的常见形态，Google 自身在边缘也会这样答）或 `200` + 被安全策略拦掉的 `{"promptFeedback":{"blockReason":"SAFETY"}}` ⇒ 返回 `LLMResponse("", "end_turn")` ⇒ 上层把它当作**一次完整的空回答**入库，用户看到空气泡、没有错误、也没有重试。报告判 P1 成立。

报告给的「`finishReason ?: "end_turn"` 里还有别的合法情形」也成立：纯 `functionCall` 轮 text 就是空的，必须保持原语义（本次不动）。

### E-C-05（P2）—— ①②都成立，②是①的根因

- ①（HEAD `GeminiModelsApi.kt:128-135`）：`if (oauthCredential) return LLMModel.allGemini` —— 401/403 时只要这条实例走 OAuth，无条件回退到静态 Google 目录，**不问这个 base 是不是 Google**。
- ②（HEAD `:196-201`）：`isOfficialGeminiBase` 用 `contains("generativelanguage.googleapis.com")` 匹配**整串 URL**。

新取证（报告没写、由变异臂 A3 现场复现）：`https://relay.internal/generativelanguage.googleapis.com/v1beta` 在旧实现下返回 **true**。即一个把官方域名写进路径的中转，被判定为官方 ⇒ 它自己的 401/403（禁用的 key、WAF、额度）会被答以「刷新成功，五个 Google 模型」，`ProviderRepository` 随后覆盖用户已有模型行并剪掉分组归属。

### E-F-06-Gemini（P2）—— 机制成立，但**修法必须比报告的建议窄**

HEAD `buildRequestBody`（`:476` 起）：`if (msg.contentParts.isNotEmpty()) { … } else { val legacyImages = (if (index == lastUserIndex) imageParts else emptyList()) + msg.imageParts … }`。顶层 `imageParts` 和 `msg.imageParts` 都只在 `else`（contentParts 为空）读。contentParts 非空时该轮**一个图片都没发**。报告的四路取证对得上。

但报告的补丁建议（「在 contentParts 非空分支末尾无条件追加顶层 imageParts」）会**把聊天主路径的每张图发两遍**。取证：

- `ChatViewModel.kt:3985-4001`：同一份 `prepared.imageParts` 既写进 `userContentParts`（`AgentContentPart.ImageData`）又写进 `LLMMessage.imageParts` —— **一条图片列表、两个载体**；
- `AgentLoopEngine.kt:1391-1397`：streaming 调用的**请求级** `imageParts = emptyList()`。

⇒ chat 主路径真正靠 `ImageData` 上屏，请求级恒空；无条件补挂会让 `ImageData` 与请求级各发一次（双倍图片 token）。所以本支的判据是「**这一轮已经有 ImageData 就一个都不补**」（见 §2 第 4 条），并因此**在 chat 路径上完全 no-op**，只对「有 contentParts、但没有任何 ImageData」的调用方（今天的 `model_use`/未来调用方）生效。

---

## §2 修法（每条一句可复核）

1. **非流式判决**：新增纯函数 `geminiGenerateContentVerdict(json)` → `Ok | HttpError(status) | EmptyCandidates(blockReason)`；`sendMessageClamped` 在 `extractText` **之前**判：
   - `error` 对象 ⇒ `throw mapHttpError(geminiStreamErrorStatus(code, status), responseBody)` —— 与流式臂（`:312` 同款映射）共用同一张状态表，同一失败在两条路径上分类一致；
   - 无候选 ⇒ `throw LLMError.ProviderError(describe())`，消息里带 `blockReason`（有的话）。
   - 有候选（哪怕 text 为空、哪怕只有 `finishReason: "SAFETY"`）⇒ `Ok`，`finishReason ?: "end_turn"` 原样保留。
2. **为什么「无候选」选择抛错、不选 `finishReason = "error"`**（任务书要求二选一并给理由）：
   - 全仓除 `compactSummaryIsTruncated` 比 `"length"` 外，**没有任何消费方给 stop reason 语义**（`grep -n 'stopReason' … | grep '"'` 0 命中）；`text` 依然为空 ⇒ `finishReason="error"` 的响应还是会被当作一次空回答入库，正是要堵的症状；
   - 抛异常是唯一已经被上层渲染成「失败 + 重试」的通道（typed `LLMError`）；
   - 与流式臂行为对齐：流式对 error 帧抛 `mapHttpError`，对整条空流由 `failOnSilentEmptyCompletion` 抛 `TransientError`——都是抛。两条路径不能一个抛一个「成功返回」。
3. **官方端点判定**：`isOfficialGeminiBase` 改为解析主机名后**集合相等**（`{generativelanguage.googleapis.com, cloudcode-pa.googleapis.com}`），新增纯函数 `geminiBaseHost`：容忍缺 scheme（用户常粘 `host/v1beta`）、去尾部 DNS 点、小写化、**解析失败 ⇒ 非官方**（fail-closed：宁可保全用户已有模型行）。401/403 的 OAuth 兜底加前置 `&& isOfficialGeminiBase(baseUrl)`；非官方走原有 `emptyList()` 分支，日志补 `oauth=/official=` 两个字段方便真机定位。
4. **图片载体**：新增 `appendImageParts(parts, images, supportsImages)`（把 `else` 臂原有的 inlineData/§27a 占位组装抽出来，两臂共用）+ 纯函数 `geminiTurnExtraImages(isLastUserTurn, requestImages, messageImages, inlineImageCount)`：
   - `inlineImageCount > 0` ⇒ 返回空（**这一轮已用 ImageData 表示，补挂即重发**）；
   - 否则 = 请求级（仅最后一条 USER 轮）+ 该消息自己的 `msg.imageParts`（与本轮同轮，与 `else` 臂的 `LegacyMediaPlan.imageCount` 口径一致）。

---

## §3 改动清单

| 文件 | 改动 |
|---|---|
| `provider/gemini/GeminiProvider.kt` (+177/−16) | `sendMessageClamped` 判决接线；`appendImageParts` 抽取；contentParts 臂补载体；文件尾新增 `geminiTurnExtraImages` / `GeminiGenerateContentVerdict` / `geminiGenerateContentVerdict`（均 internal 纯函数，无 Android 依赖） |
| `provider/gemini/GeminiModelsApi.kt` (+49/−7) | 401/403 OAuth 兜底加官方门；日志字段；`isOfficialGeminiBase` 改主机名判定；新增 `geminiBaseHost` + `OFFICIAL_GEMINI_HOSTS` |
| `test/.../gemini/GeminiNonStreamingVerdictTest.kt` (新增 273) | 12 例：判决表 4 + 载体表 1 + 官方判定 4 + 接线形状 3 |
| `test/.../gemini/GeminiNonStreamingWireTest.kt` (新增 213) | 8 例：`200+error`/`200+403+error`/`profileFeedback`/空候选/纯工具轮 + 结构化轮补图/不重发/无视觉占位 |

**未新增字符串资源**（错误文案走 `LLMError.ProviderError` 的日志/错误卡片既有通道），无 i18n 改动。

---

## §4 判据与带牙证明（9 条变异臂，全部按预期转红）

臂脚本 `harness-b2/arms.py`：每条臂在**副本**（`arms/<name>/root`，含两个源文件的拷贝）里塞回一处缺陷，从副本重新切片、重新编译、跑同一份测试类。生产文件零修改。

| 臂 | 塞回的缺陷 | 变红的判据 |
|---|---|---|
| A1 | `geminiGenerateContentVerdict` 恒返回 `Ok` | 判决表 3 例（error 对象 / 无候选 / blockReason 文案） |
| A2 | 去掉 `optJSONObject("error")` 检查（锚点改为 `__never_present__`） | error 对象 in 200 体 |
| A3 | 恢复 HEAD 的 `contains` 整串匹配（**逐字等价于 HEAD `:196-201`**） | 「路径里带官方域名的中转仍是中转」 |
| A4 | 去掉缺 scheme 容忍（`withScheme = raw`） | 主机解析表 + 官方主机两例 |
| A5 | 载体表恒返回空（= 修复前的丢弃行为） | 结构化轮的载体表 |
| A6 | 去掉 `inlineImageCount > 0` 去重守卫（= 报告建议的无条件补挂） | 同一例（重发臂） |
| A7 | 把判决挪到 `extractText` 之后 | 「判决必须先于解析」 |
| A8 | `else` 臂改回自己内联的组装循环 | 「两臂共用一个组装」 |
| A9 | 401/403 改回 `if (oauthCredential)`（**逐字等价于 HEAD `:128`**） | OAuth 兜底必须过官方门 |

结果：`=== arms: 9 red: 9 problems: 0 ===`（`harness-b2/arms2.log`）。

**A2 首轮的假绿值得记一笔**：第一版锚点只写了 `json.optJSONObject("error")?.let { err ->`（4 空格缩进），结果命中的是**流式臂**那处（16 空格）——臂把错的地方改了，12 例照绿，脚本判 `GREEN (ROT!)`。改成两行锚点（含 `internal fun geminiGenerateContentVerdict(...)` 签名）后才真红。**教训：变异臂的锚点必须在所在函数体内唯一，否则「臂绿」只说明改错了地方。**

---

## §5 验证四层（本地能跑的都跑了；禁跑项一条没跑）

1. **本地 JVM 真跑**（`harness-b2/run.sh`）：`LLMMessage.kt`/`AgentContentPart.kt`/`JsonExt.kt` 用**真文件逐字**，生产函数由 `extract.py` 按声明名+括号配平**逐字切出**（不手抄），与测试类同编译同 JVM：
   ```
   --- compiled 21 classes, running JUnit ---
   OK (12 tests)   time 0.165s
   ```
   `LLMUsage` 用桩（真文件是 `@Serializable`，本地无 kotlinx-serialization jar；切片与测试都不读它的字段）。
2. **9/9 变异臂转红**（§4），含三条「逐字等价 HEAD」的臂（A2/A3/A9）。
3. **括号配平**（`harness-b2/balance.py`，跳过字符串/字符/注释）：4 个文件全 `ok`。
4. **CI（云端）**：见 §6。这两层互补——本地跑不了 `GeminiProvider` 整文件（Android + OkHttp + 20 余依赖），`GeminiNonStreamingWireTest` 的 8 例由 CI 承担；本地则承担「不依赖 Android 的判决/载体表」＋变异臂。

**负载纪律遵守情况**：未跑 `scan.sh`、未跑 `test_scan.py`、未跑 gradle；`kotlinc` 单次编译 ≤ 8 文件、`nice -n 10`、同一时刻一个 JVM（臂脚本串行）。

---

## §6 CI

- run：**#2919 / `40887205412`**，`fix/r5e-gemini`，**success**，attempt 1，`2026-10-29T14:00:21Z → 14:18:24Z`（18m03s，27 步）
- **head_sha `01ea80d01765002c0a5cbc786006734e375c69e3` 与本地 HEAD 逐字符一致**
- 日志读数（`/tmp/ci-b2/logs.zip`，逐 job 文件）：
  - scan gate 步骤 140,704 B / NUL 0：69 行内 6 个门族自打印 `Summary: N passed, 0 failed` 全部 0 failed、`ALL CLEAN ✅ [checked 10/10]`、`ALL PASS`、累计 `║ Summary: 113 passed, 0 failed`、`[N/113]` 互异 113 条、`FAILED` 行 **0**、夹具 `ALL 349 CASES PASS`
  - 单测步骤 1,225,068 B / NUL 0：`BUILD SUCCESSFUL in 5m 19s`、`BUILD FAILED` 0、`: error: ` **0**、Gemini 相关 warning 0、` PASSED` 8,382 行
  - 4 条含 `FAILED` 的行逐条确认全是用例名子串（`…produces FAILED without calling provider PASSED` 等）
  - **新用例 20 例全 PASSED**：`GeminiNonStreamingVerdictTest` 12/12、`GeminiNonStreamingWireTest` 8/8（含 `request images reach the wire on a structured contentParts turn`、`an already-inlined image is not sent twice`、`a blocked prompt never becomes an empty end_turn`）
- 分支上 `Publish` 按设计 skip（main 门控），无发布动作。

---

## §7 残留与登记（每条带触发条件）

| # | 残留 | 级别 | 触发条件（何时算复活） |
|---|---|---|---|
| R1 | `sendMessageClamped` 里 `JSONObject(responseBody)` 对**非 JSON 的 2xx**（WAF/门户拦截页）抛 `JSONException`，不是 typed `LLMError` ⇒ 上层落到 unknown 分支。**响亮失败，非静默**，故不并入本支 | P2 | 真机出现「未知错误」且日志里有 `JSONException` + `status=200` |
| R2 | 无候选一律 `ProviderError`（不可重试）；若某中转把「暂时性截断」也表现为空候选，用户需手动重试（流式臂同情形是 `TransientError` 可重试） | 观察 | 真机出现 `Gemini returned no candidates` 且立即重试成功 |
| R3 | 请求级图片只挂最后一条 USER 轮（与该文件 `else` 臂既有契约一致）；若聊天恢复请求级 `imageParts`（今天 `AgentLoopEngine:1397` 恒空），且最后一条 USER 消息恰是 `tool_result` 轮（同为 USER 角色），图片会挂到工具轮 | P2 | `rg "imageParts = " ui/chat/` 出现非 `emptyList()` 的生产写入点 |
| R4 | 「有 ImageData 的 contentParts 轮」不再发送 `msg.imageParts`（刻意的双载体去重）；若将来出现「只有 `msg.imageParts`、无 `ImageData`、且该轮 contentParts 非空但全部是 ToolResult」的新产物路径，图片会丢 | P2 | `rg "imageParts = " …/data/` 出现新的载体重建点（如导入/恢复路径写 `msg.imageParts` 而不写 ImageData） |
| R5 | `geminiBaseHost` 走 `java.net.URI.host`，对 `https://user@generativelanguage.googleapis.com/…` 判「官方」（URI 语义）；UI 目前不允许把凭据写进 base URL | P3 | 出现「base URL 含 userinfo」的配置方式 |
| R6 | `mapHttpError(status, responseBody)` 未透传 `Retry-After`（非流式响应头里可能有）；修复前该路径**根本不映射**，故非回归 | P2 | 真机 429 需要按服务端退避时间重试 |
| R7 | 真机未验：本支全部证据 = CI 编译级 + JVM 级 + 源码形状。`200+error`/空候选/中转 401 三种形态未在真机上构造 | P1 观测 | 任一 Gemini 实例真机出现空回答或模型列表被替换 |

---

## §8 身份链

| 环节 | 值 |
|---|---|
| 基线 | main `35509888`（`Merge branch 'integration/r5ad'`） |
| 分支 | `fix/r5e-gemini`（worktree `shared/work/witness-1027/wt-r5e-gemini`） |
| 代码提交 | `01ea80d0` fix(r5e-gemini): refuse a 2xx body that carries no candidate… |
| CI | run `40887205412` / #2919 success @ `01ea80d01765002c0a5cbc786006734e375c69e3` |
| 装置 | `shared/work/r5-ef/harness-b2/`（`extract.py` / `run.sh` / `arms.py` / `balance.py` / `arms2.log` / `states/`、`arms/` 不在生产树内） |
| 停止节点 | 分支 CI 绿 + head_sha 逐字符一致 ⇒ **结束，不合 main**（合并起手权在用户） |
