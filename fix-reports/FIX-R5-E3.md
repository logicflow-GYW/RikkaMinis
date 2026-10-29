# FIX-R5-E3 —— Anthropic 官方端点识别 + 空内容块（2 P1 + 1 顺路）

- **分支** `fix/r5e-anthropic`（基线 main `35509888`），实现提交 `347a44c8`
- **来源** meta-audit-1029 R5 修后复扫批，报告 `reports/R5-E-provider-protocol.md`（R5-E）；判级见 `TRIAGE-R5-EF.md`
- **触碰面** 生产 2 文件（`provider/anthropic/AnthropicProvider.kt` +137/−19、`provider/anthropic/AnthropicModelsApi.kt` +60/−3）+ 3 个新测试类（`AnthropicOfficialHostTest` / `AnthropicContentBlocksWireTest` / `AnthropicImageCarrierWireTest`）。`scripts/scan/**`、`scan.sh`、`test_scan.py`、其它 provider 文件零触碰；i18n 零改动（无新文案，全部为线上载荷形状）；门 113 / 夹具 349 不变。
- **取证纪律** 按语义在本树重定位（`rg` 定义/全部 caller），不按报告行号。报告 `:848-853` / `:1063-1068` / `:804` 三处行号经核对与真树一致；`E-C-07③` 前提（`contentParts` 只剩 `Text("")` 且 `imageParts` 非空）经逐生产者排查判为**生产不可达**，按任务书降级登记（详见 §2.4）。

---

## §1 修复台账（每条一行）

| 条目 | 先证结论 | 修法 | 证据 |
|---|---|---|---|
| **E-F-07 + E-C-06①**（P1）| 成立，且是**必然而非边缘**：`effectiveBaseURL` 默认 `appendV1Suffix=true` ⇒ 用户填官方域名后到达 provider 的是 `https://api.anthropic.com/v1`，`basePath != "https://api.anthropic.com"` / `baseURL != null` 双双判成中转 ⇒ 模型列表与对话都发 `Authorization: Bearer`（官方只认 `x-api-key`），401 后因 `isCustomEndpoint=true` 直接回空列表 | 新增单源纯函数 `isOfficialAnthropicHost(url)`（解析 HOST，容忍无 scheme / 尾斜杠 / `/v1` / 大小写，null/空=官方）；`AnthropicProvider.isCustomEndpoint` 与 `AnthropicModelsApi` 的 `isCustomEndpoint`、**逐候选鉴权头选择**、`parseAndCache` 回退门控全部改由它派生 | `AnthropicOfficialHostTest`（官方 8 形 + 中转/仿冒 5 形 + `effectiveBaseURL → 判定` 真入参映射 2 例，期望值全为字面量） |
| **E-C-07①**（P1）| 成立（可达，非纯理论）：`ToolResult` 无条件把 `part.content` 包成 `type="text"` ⇒ 空文本落进 `tool_result.content`。生产可达两条：①恢复/导入的历史行 `output=""`（`ChatStreamToolHelpers` 把 parts_json 逐字还原，不做非空校验）；②`minis-model-use` 线协议的 `tool_result` 缺 `content` 键（`ModelExecutionService` 以 `optString` 读成 `""`）。实时执行器路径靠**per-tool「永不空」约定**（shell `(no output)`、file_read 头部行、read_image 元数据、file_write 提示）挡住——那是约定不是不变量 | 空 `part.content` 不产出 text 块；`tool_result.content` 数组若因此为空，用**单空格**载体（与 `GeminiProvider` 空工具结果同字面量），理由：①最小合法非空块 ②让每个 `tool_result` 的 `content` 恒存在（省略键虽合法，部分反代拒绝）③跨 provider 回放同一历史时字节可比 | harness「empty tool_result content becomes a single space」（`content[0].text == " "`，全消息 `emptyTextBlocks == 0`）；「with an image keeps only the image」；「text and image drops nothing」 |
| **E-C-07②**（P1）| 机制成立，**生产可达性极窄**（诚实登记）：空串内容行本来就会被 `hasWirePayload` 滤掉，只有「最后一条 user + 请求级图片」这一边缘能绕过过滤器抵达合并路径 | 同族一处顺手闭合：`collectContentBlocks` 的 `is String` 分支跳过空串；合并结果若全空则补一个单空格块（合并本身不可省，Anthropic 要求角色交替）；`else` 分支的字符串 `content` 也走 `ifEmpty { " " }` | harness「merged same-role turn carries no fabricated empty text block」；wire 测试同名用例（1 条合并消息、2 个非空 text 块） |
| **E-C-07③**（P1）| **生产不可达** ⇒ 按任务书降级登记，不为此造装置：该形状要求「`contentParts` 全为 `Text("")`」且「`imageParts` 非空」，而**所有** `imageParts` 生产者都是双载体（`ChatViewModel`、`ChatQueueInterruption`、`ChatStreamToolHelpers` 的 MediaRef 还原都同时写 `contentParts` 的 `ImageData`），一旦 `imageParts` 非空则 `contentParts` 必含 `ImageData` ⇒ 走不到「全空」；`ImageElisionPlan` 只会把 `ImageData` 换成非空占位文本，方向相反 | 不单独改码；但 **E-F-06 的修法在结构上封住它**（载体被消费 ⇒ `content: []` 不可产出），另设一条最终守卫：`contentArray` 为空时补单空格块 | harness「all-empty contentParts turn with a carrier still serialises a block」证明该形状现在产出 1 块（修前 0 块）；wire 测试「an all-empty turn never reaches the wire with an empty payload」 |
| **E-F-06（Anthropic 臂）**（P2 顺路）| 成立：`contentParts` 非空分支只遍历 `contentParts`，请求级 `imageParts` 与 `msg.imageParts` 只在 `else` 分支被读——而应用构造的每一轮都有 `contentParts`，所以两支载体实际永不生效 | `contentParts` 分支结束后追加载体块，**判据 = 该轮自身有没有产出 image block**（`emittedImage`：`ImageData` 的图片块与非 vision 占位文本都算），且**只取一个来源**（last user 转用请求级，否则用消息级），不沿用 `else` 分支的拼接写法 | harness 5 例（请求级/消息级各 1、三载体同字节只出 1 块、vision 模型出真 image 块、contentParts 两张图都留）；wire 测试同名 5 例 |

---

## §2 逐条先证与修法

### 2.1 E-F-07 + E-C-06①：官方/中转判定是 HOST 属性

真树三处派生点（`rg -n "isCustomEndpoint"`）：

| 位置 | 旧判据 | 受影响行为 |
|---|---|---|
| `AnthropicProvider.kt:97` | `basePath != "https://api.anthropic.com"` | 对话鉴权头（`:1398` `Bearer` vs `x-api-key`）、`shouldEchoInterleavedThinking()` |
| `AnthropicModelsApi.kt:47` | `baseURL != null` | 失败时分流（401/403 判据 + 是否回退内置目录） |
| `AnthropicModelsApi.kt:82` | `candidate != null` | 逐候选基 URL 的鉴权头 |

证：`ProviderInstance.effectiveBaseURL` = `customBaseURL.trim().trimEnd('/')`，`appendV1Suffix && !endsWith("/v1")` ⇒ 追加 `/v1`。用户填 `https://api.anthropic.com` ⇒ 到达 provider 的字符串是 `https://api.anthropic.com/v1` ⇒ 旧判据 `!=` 成立 ⇒ 判中转 ⇒ 官方端点收到 `Authorization: Bearer`（官方只要 `x-api-key`）⇒ 401；模型列表因 `isCustomEndpoint=true` 不拿内置目录，重试后空列表。**这不是边缘配置，是"把官方域名填进自定义框"的必然结果。**

修法：单源 `isOfficialAnthropicHost(url: String?)`（`java.net.URI` 解析 HOST，`equals(ignoreCase)`；无 scheme 时补 `https://` 再解析；解析失败判中转），三个派生点全改用它。逐候选头的选择改成 `candidate != null && !isOfficialAnthropicHost(candidate)`，这样"官方种子 + 爬升出来的官方父路径"都发 `x-api-key`，只有真中转才 Bearer。`parseAndCache` 的 `isCustomEndpoint` 形参未改名、`usesBuiltinCatalogOnFailure(isCustomEndpoint, lastFailureCode)` 调用字面量未动（既有形状门 `MetaR3B08WiringTest` 钉的就是这行）。

**这改变什么用户行为？** 手填官方域名的 Anthropic 实例：模型列表能真的拉到（不再 401 + 空列表）、对话鉴权改用官方要求的 `x-api-key`、interleaved thinking 不再被 echo 到官方端点（官方会因缺 `signature` 拒）。真中转实例行为零变化。OAuth 通道不受影响——`ProviderFactory` 已注明 OAuth 已被移除，不存在"官方 + OAuth 需要 Bearer"的路径（复核过，无此分支）。

### 2.2 E-C-07①：空文本块不落线

真树 `ToolResult` 臂原来无条件 `put("text", part.content)`。两处可达生产者见 §1；实时执行器靠约定挡住，不是不变量。修法两段：非空才产出 text 块；若整个 `tool_result.content` 数组空（无文本也无图），补单空格。选单空格而不是"省略 `content` 键"的三条理由已写进代码注释（最小合法块 / `content` 恒存在 / 跨 provider 字节可比）。

### 2.3 E-C-07②：合并路径的两处空值

`collectContentBlocks` 的 `is String` 分支原样包装 `content: ""` ⇒ `mergeConsecutiveSameRole` 把它变成空 text 块；若整轮全空则产出 `content: []`。修法：跳过空串 + 合并结果为空时补单空格块（合并不可省——Anthropic 要求角色交替，强行丢弃会破坏 tool_use/tool_result 相邻关系）。`else` 分支的字符串 `content` 同样走 `ifEmpty { " " }`。

### 2.4 E-C-07③：不可达 ⇒ 降级登记（并说明为何修法顺带封住）

要求形状：`contentParts` 非空但**全为** `Text("")`，同时 `msg.imageParts` 非空（靠它通过 `hasWirePayload`）。逐生产者核过：

- `ChatViewModel`：图片同时写 `contentParts` 的 `ImageData` 与 `imageParts`（双载体）；
- `ChatQueueInterruption`（两处）：同款双载体；
- `ChatStreamToolHelpers`（恢复）：`MediaRef` 分支既 `contentParts.add(ImageData)` 也 `imageParts.add(...)`；
- `ImageElisionPlan`：只会把 `ImageData` 换成**非空**占位文本并同步从 `imageParts` 移除，不会留下"无图但载体非空"；
- `ChatContextWindow` 的 offload stub：`ToolResult.content` 换成非空 stub，与 `imageParts` 无关。

⇒ `imageParts` 非空蕴含 `contentParts` 含 `ImageData`，两者互斥于"全空"。故不单独立项修，登记为**已被 F-06 修法结构性封住**（载体被消费后 `content: []` 不可产出），外加最终守卫兜底。

---

## §3 装置与证据

**JVM 切片 harness**（`/tmp/e3-harness`，不进仓库）：`extract_slice.py` 按声明名 + 括号配平从 `AnthropicProvider.kt` **逐字**切出 `buildMessages` / `mergeConsecutiveSameRole` / `collectContentBlocks`，只做四类必要替换（私有→可测、两个 computed 值接探针、`android.util.Log` → println、`Base64`/`ImageBudget` 换同名桩），其余行与真源逐字符相同（提取脚本自带逐行回查断言）。配套真源文件：`LLMMessage.kt` / `AgentContentPart.kt` / `AgentTypes.kt` / `RequestSanitizers.kt` / `StreamTimeouts.kt` / `MessagePayloadFilter.kt` / `LegacyMediaPlan.kt`。

**结果**：`PASS=12 FAIL=0`。

**变异臂**（`arms.py`：改生产源 → 重切片 → 重编译 → 重跑，随后从备份还原，绝不用 `git checkout`）：

| 臂 | 注入 | 结果 |
|---|---|---|
| A | 去掉「非空才产出 text 块」守卫（恢复无条件包装） | RED-AS-EXPECTED（`PASS=10 FAIL=2`，含「empty tool_result → 单空格」） |
| B | `if (!emittedImage)` → `if (false)`（关掉载体追加） | RED-AS-EXPECTED（`PASS=7 FAIL=5`，含「请求级图片到达 contentParts 轮」） |
| C | 单载体改为拼接（`else` 分支写法） | RED（`PASS=10 FAIL=2`：「all-empty contentParts turn with a carrier」得 2 块、vision 臂 3 块）——重复载体被判出 |

C 的期望断言名与实红断言不同（我按"三载体只出 1 块"预设，实际先在另外两条计数臂上红），但红因正确（都是重复载体），已在 §7 记录口径差异，不掩盖。

**CI 侧测试**（真执行生产代码，非切片）：
- `AnthropicOfficialHostTest`：官方 8 形 / 中转与仿冒 5 形 / `effectiveBaseURL → 判定` 真入参映射（含"旧判据会把它当中转"的显式断言，钉住报告点名的缺口）。
- `AnthropicContentBlocksWireTest`（MockWebServer，断言**序列化后的请求体**）：空 tool_result → 单空格；空文本 + 图 → 只有图占位；文本 + 图 → 两块；合并轮无空 text 块；全空轮不产出空载荷。
- `AnthropicImageCarrierWireTest`：请求级/消息级载体各到达一次；三载体同字节只出 1 块；两张不同图都在；非最后一轮的消息载体仍在。

---

## §4 残留与登记（每条带触发条件）

| # | 残留 | 级别 | 触发条件 / 升级信号 |
|---|---|---|---|
| R1 | 请求级 `imageParts` 仍只挂**最后一条 user**（既有契约未动）；非 last 轮的请求级图片依旧丢弃 | P3 | 出现"把历史轮图片塞进请求级参数"的调用方时 |
| R2 | `tool_result` 用单空格当载体 = 对"本该报错"的静默修复 | P3（刻意） | 若将来要区分"真空结果"与"未产出"，应改为结构化标记（需 wire 契约变更） |
| R3 | `isOfficialAnthropicHost` 按 HOST 相等判定，忽略 scheme 与 port：`http://api.anthropic.com:8443/v1` 判官方 | P2 | 用户把官方域名指向本机反代端口的配置出现时；那时需要"host+port+scheme 三元组"判据 |
| R4 | `E-C-07②` 无行为用例（空串内容行先被 `hasWirePayload` 滤掉，构造不出端到端形状），只有形状级保护 | P2 | 上游放开空内容行、或 `hasWirePayload` 判据放宽时，补一条端到端用例 |
| R5 | 真机未验（本支证据 = CI 单测 + 本地 JVM 切片 + 源码形状），无真实端点抓包 | P1（观测） | 拿到官方 key 时跑一次"手填官方域名 → 模型列表 → 对话带图"三步抓包 |
| R6 | `E-C-07③` 生产不可达但未删「全空 contentParts」这条潜在形态的最终守卫；守卫用单空格，掩盖未来上游 bug 的显式报错 | P3 | 若出现"内容全空"的线上日志，应改为带 tag 的告警而非静默补空格 |

---

## §5 半径与波及面

- **改动半径**：只有 3 个派生点 + `buildMessages` 的三个臂 + `collectContentBlocks` 一个分支 + `mergeConsecutiveSameRole` 一个守卫。
- **`isCustomEndpoint` 的其它消费者**：`shouldEchoInterleavedThinking()`（官方判定改正后，官方端点不再 echo 未签名 thinking —— 与官方要求一致）、对话鉴权头（`:1398`）。均已复核。
- **未触碰**：`ProviderFactory`（`basePath` 取值不变）、`ProviderRepository.refreshModels`（`effectiveBaseURL` 原样传入）、调试探针 `ProviderMutationMethods`（它固定发 `x-api-key`，是另一套设计，不属本支文件）、其它 provider。
- **形状门**：`MetaR3B08WiringTest` 钉的 `usesBuiltinCatalogOnFailure(isCustomEndpoint, lastFailureCode)` 字面量保持原样；`AnthropicModelsFallbackTest` 的布尔入参用例语义不变（`isCustomEndpoint` 仍是"是否中转"）。
- **i18n**：零改动。
- **门/夹具**：`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰 ⇒ 门 113 / 夹具 349 与基线一致（本支未跑全量，按要求）。

---

## §6 本地验证记录（禁全量下的窄验证）

| 项 | 命令 / 方式 | 结果 |
|---|---|---|
| 切片逐字性 | `extract_slice.py` 内置逐行回查（`>25` 字符的行必须能在真源中找到） | 只报 6 条**预期替换**行（探针开关 / 私有→internal / Log→println），无意外差异 |
| 行为断言 | `kotlinc`（单次，`nice -n 10`）+ `java harness.ProbeKt` | `PASS=12 FAIL=0` |
| 变异臂 | `arms.py`（3 臂，改源→重切片→重编译→重跑→还原） | A/B RED-AS-EXPECTED，C 红（重复载体），还原后复跑 `PASS=12 FAIL=0` |
| `effectiveBaseURL` 形状 | 真源核对 `ProviderConfig.kt:302-306`（trimEnd + 条件追加 `/v1`） | 与测试字面量一致 |
| 半径自检 | `git diff --stat` | 2 生产文件 +137/−19、+60/−3，3 新测试类 |

---

## §7 CI 记录

- 分支 `fix/r5e-anthropic`，实现提交 `347a44c8`，`gh_sync.sh push` OK；分支 push 不触发 CI ⇒ 手动 `gh-actions-dispatch --workflow build-apk.yml --ref fix/r5e-anthropic`（204，run 创建于 2026-10-29T14:04:52Z）。
- run **#2921**（id 40887943391，attempt 1）：**success**，创建/启动 14:04:52Z、完成 14:26:30Z（≈21.6 min）；job `build` 27 步，唯一非 success = 2 skipped（`Move the rolling tag android-latest to this commit` + `Publish to Releases`，均为 main 门控），非 skipped 失败步 0。
- head_sha `347a44c813c4a3c353e8c636b5019e0550e172a6` 与本地 HEAD **逐字符一致**（`git rev-parse HEAD` 对拍 = SHA-MATCH）。
- 日志判读（zip 27 文件 / 共 3,313,216 B）：NUL 0；`║ Summary: 113 passed, 0 failed` 在册；`✅ ALL 349 CASES PASS` 在册；`^ e: ` 0；真 `FAILED` 行 0（含 FAILED 的行 grep -v PASSED 后为空，均为 PASSED 用例名子串）；分片 Summary（25/16/44/19/30/18）两侧全 0 failed；三个新测试类 PASSED 提及 30 处（`AnthropicOfficialHostTest` / `AnthropicContentBlocksWireTest` / `AnthropicImageCarrierWireTest`）。
- 状态查询备注：`gh_sync.sh gh-actions-runs` 在 14:26Z 后返回空 remark（解析口径问题），改用零 token 桥 `rikka-ci-bridge.logos7313.workers.dev/status/<branch>` + 带重试的直查 API 双通道确认结论；urllib 直连两次 SSL EOF（代理偶发，5 次退避重试内恢复）。
- 口径差异记录（不掩盖）：变异臂 C 的预设期望断言与实际首红断言不同——变异的真实效果是"同一图片被追加两次"，先在 `all-empty contentParts turn with a carrier` 与 `vision model gets one real image block` 的计数上暴露，而不是我预设的"三载体"那一条；两者都是重复载体判据，故仍记为有效红。

---

## §8 交付与停止节点

- **停止节点**：分支 CI 绿 + `head_sha` 与本地 `HEAD` 逐字符一致即结束；**未合并 main**（合并起手权在用户）。
- 报告提交在绿之后，`<绿 sha>..HEAD` 的非 `fix-reports` 差异应为空（回填见下）。
- 判级兑现：P1 3 条（E-F-07/C-06① 合并计 1、C-07①、C-07② 修；C-07③ 降级登记）· P2 1 条（F-06 Anthropic 臂顺路修）· 残留 6 条（§4）。
- 复核项（供并集收口）：本支独占 `AnthropicProvider.kt` / `AnthropicModelsApi.kt`，与他支文件集无交集（`rg` 确认这两个文件在全仓只被本支改）。
