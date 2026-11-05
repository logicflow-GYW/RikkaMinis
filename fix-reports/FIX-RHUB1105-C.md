# FIX-RHUB1105-C — Gemini 流式漏检 `promptFeedback.blockReason`（F-04）

## §1 缺陷与根因

**覆盖 ID**：F-04（P1，来源 `Rhub_Protocol_1105_Audit_Report.md` r6）

**基线**：`00dc003a3743110258b7cdf2e1feca9c53278f24`
**分支**：`fix/rhub1105-gemini-blockreason`（worktree `/var/minis/shared/work/meta-audit-1105b/gemini-blockreason`）

真码核实（行号取自 `00dc003a` 实测，与任务书一致）：

- **非流式 arm（正确）**：`GeminiProvider.kt:139-157` → `geminiGenerateContentVerdict(json)`（`:1061-1074`）→ 无 candidates 时读 `promptFeedback.blockReason` → `EmptyCandidates(blockReason)` → `throw LLMError.ProviderError(verdict.describe())`，消息含 `blocked: SAFETY`。
- **流式 arm（缺口）**：`rawStreamMessage` 的每帧解析处只处理 `json.optJSONObject("error")`（`:318-327`），**完全没有 `promptFeedback` 分支**。无 candidates 的帧直接滑落帧循环，流走到 EOF。

两条后果路径（都已在真机上由 `failOnSilentEmptyCompletion`（`LLMProvider.kt:224-248`）与 EOF 终局判据放大）：

1. **首帧即被拒**（`sawAnyOutput == false`）→ EOF 空 → `Finished(null, truncated = true)` → `failOnSilentEmptyCompletion` 改写成 `LLMError.TransientError("Server returned an empty response …")`。安全拒答被报成**瞬态网络故障** ⇒ ①app 无意义自动重试（必然再被拒）②`blockReason=SAFETY` 诊断信息彻底丢失。
2. **中途被拒**（已输出部分文本后，`sawAnyOutput == true`）→ `failOnSilentEmptyCompletion` **不拦** → 直接变成 `truncated = true` ⇒ 触发截断自动续写，续写请求同样被拒。

**先证结论：报告成立，未证伪。** 沙箱 JVM 夹具在**未修改**的基线代码上跑出与报告逐字一致的读数（见 §4）。

## §2 修法

一句话：**把「`promptFeedback.blockReason` → 拒答错误」抽成一个两臂共用的纯函数，流式 arm 在每帧上调用它，把拒答从「空响应 / 截断」改成 `ProviderError`（文案与非流式 arm 逐字相同）。**

三个新增/改动的点（全部在 `GeminiProvider.kt`，无新文件）：

1. `geminiPromptBlockReason(json): String?` —— 纯函数，读一帧的 `promptFeedback.blockReason`（空串归一为 null）。**非流式 arm 改为经它读取**，两臂因此不会在「什么算拒答」上漂移。
2. `geminiPromptBlockError(blockReason): LLMError.ProviderError` —— **唯一的拒答错误构造器**。文案取自 `EmptyCandidates(blockReason).describe()`（即非流式 arm 的既有文案，**逐字未改**），分类恒为 `ProviderError`，**永不** `TransientError`。
3. 流式 arm 帧循环内，紧邻 `json.optJSONObject("error")` 那段之后加一行：
   `geminiPromptBlockReason(json)?.let { throw geminiPromptBlockError(it) }`
   —— **每帧都查**（中途拒答同样终止），且**幂等**（抛出即结束流，无需标志位）。
4. 非流式 arm 的 `EmptyCandidates` 分支改为：有 `blockReason` 时经 `geminiPromptBlockError` 构造，无 `blockReason` 时保留原 `LLMError.ProviderError(verdict.describe())` 路径（**空体拒答的既有文案与分类不变**）。

**未动**：`geminiGenerateContentVerdict` 的公开契约（`:1048-1074` 的类型与 `describe()` 文案）、`geminiEofTerminal` 的既有判据、`scripts/scan/**`。

**这改变什么用户行为？** Gemini 拒答时，用户看到的错误从「服务端返回空响应（瞬态，可重试）」变成「Gemini 拒绝了该请求，blocked: SAFETY」——不重试、诊断可见；中途拒答不再伪装成「回答被截断」而触发续写风暴。

## §3 改动清单

| 文件 | 改动 | 行数 |
|---|---|---|
| `src/android/app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiProvider.kt` | 流式帧循环加拒答检查 + 两个共用纯函数 + 非流式 arm 改走共用构造器 | +67 / −5 |
| `src/android/app/src/test/java/com/rikkaminis/app/provider/gemini/GeminiStreamBlockReasonTest.kt` | **新增** 夹具（2 例：首帧拒答 / 中途拒答） | +160 / −0 |
| `fix-reports/FIX-RHUB1105-C.md` | 本报告 | — |

- `scripts/scan/**`：**零改动**（`git diff --name-only` 无该目录）。
- 生产 `.kt` 只碰 1 个文件（`GeminiProvider.kt`），是本支独占文件，与 FIX-B（Anthropic）文件集互斥。
- 未新增依赖、未新增文件到生产包、未改任何公开契约。

## §4 门判据与带牙证明

### 4.1 夹具（先红 → 绿 → 变异臂红）

夹具文件 `GeminiStreamBlockReasonTest.kt`，两条臂，期望值全部写**字面量**（`"SAFETY"`、`ProviderError`、非 `TransientError`、非 truncated），不从被测常量推导。

关键手法：**夹具跑的是仓库里真实的那份测试源码**，沙箱探针把**真实的** `GeminiProvider.kt`（连同真实 provider/data-model 闭包）编译进 JVM，用 MockWebServer 回放 SSE 帧 —— 不是复制品、不是 mock 替身。

- **基线红**（未改代码）：`PROBE: ran=2 failed=2`
  - arm 1 失败原文：`the refusal must be a ProviderError (NOT a TransientError), got: com.rikkaminis.app.data.model.LLMError$TransientError: Transient error: Server returned an empty response (connection dropped or upstream error)`
  - arm 2 失败原文：`the mid-stream refusal must surface as an LLMError, got: null`
  - ⇒ 与报告描述**逐字一致**（arm 1 = TransientError 空响应；arm 2 = 根本不抛错）。
- **修复后绿**：`PROBE: ran=40 failed=0`（40 = 2 条新夹具 + 38 条既有 Gemini 回归用例）。
- **变异臂红**（删掉流式 arm 那一行检查）：`PROBE: ran=40 failed=2`，两条 F-04 臂同时红（arm 1 退回 `TransientError`，arm 2 退回 `got: null`），既有 38 条仍绿。
- **还原后复绿**：`PROBE: ran=40 failed=0`。

⇒ 装置有牙：夹具确实在检验「流式 arm 有没有做拒答检查」，不是恒绿。

### 4.2 窄门（任务书允许范围）

| 门 | 结果 |
|---|---|
| `python3 scripts/scan/gemini_base_version_check.py` | **exit 0**（✅ 全部 normalizer 判据通过） |
| `python3 scripts/scan/provider_ua_parity_check.py` | **exit 0**（PASS） |
| `python3 scripts/scan/tool_result_image_branch_check.py` | **exit 0**（✅） |
| `python3 scripts/scan/truncated_call_guard_check.py` | **exit 0**（✅ 含「Gemini EOF terminal decision 走纯 verdict」判据） |

未跑全量 `scan.sh` / `test_scan.py` / `gradle`（按任务书 §4 负载纪律）。

**门冲突**：无。四道窄门均未因本次改动转红，未改任何门。

## §5 验证四层结果

| 层 | 证据 | 结果 |
|---|---|---|
| ① 语义先证 | 基线代码上夹具先红，读数与报告逐字一致 | ✅ 未证伪 |
| ② 本地窄验证 | 沙箱 JVM 探针（真实 provider 源码）：基线红 → 修复绿（40/40）→ 变异臂红（2/40）→ 还原绿 | ✅ |
| ③ 窄门 | 4 道 `scripts/scan/*_check.py` 全部 exit 0 | ✅ |
| ④ CI | 见 §8（CI run id + head_sha） | ⏳ |

沙箱探针位置：`/var/minis/shared/work/meta-audit-1105b/fixc-probe/`（`build.sh` / `run.sh` / `stubs/`）。**探针不入库**，仓库里只有 1 个生产文件 + 1 个测试文件 + 1 个报告。

探针的沙箱专用 stub（仅 `fixc-probe/stubs/`，**不复制进 worktree**）：`android.util.Log` / `android.util.Base64` / `android.os.Build` / `android.app.Application` / `android.graphics.Bitmap` / `androidx.compose.runtime.Stable` / `androidx.room` 注解 / `android.content.Context` / `BuildConfig` / `AppLogger` / `NetworkMonitor` / `ProviderHealthTraceListener` / `ImageBudget` / `AgentRuntimeLimitsPrefs` / `isOfficialGeminiBase`+`geminiBaseHost`（后者**逐字抄自真实 `GeminiModelsApi.kt:213-243`**，因为该文件会拖进 Room/ModelsDevApi）。被 stub 的全部处于**拒答路径之外**。

## §6 任务书失真

| 任务书说法 | 实测 | 处理 |
|---|---|---|
| 缺陷行号 `:318-327` / `:1061-1074` / `:139-157` | 与 `00dc003a` 实测**一致** | 无失真 |
| 「不要为此改 `geminiGenerateContentVerdict` 的公开契约」 | 已遵守：类型与 `describe()` 文案逐字未改；只把**内部读取**改为走共用 helper | 合规 |
| 「建议把 blockReason → 错误抽成私有纯函数」 | 已抽，但**未做成 `private`**：改为 `internal`，以便沙箱 JVM 探针与同包单测直接复用 | 有意偏离，记录在案 |
| 反事实夹具只给了首帧一条 | 任务书补充测试段要求「中途拦截」臂 | 两条臂都写了 |
| 未提「中途拒答」在 `sawAnyOutput == true` 时的路径 | 任务书 §2 已描述该后果 | 夹具 arm 2 专门覆盖（断言非 `truncated`） |

**与任务书 §2 的一处收紧**：任务书建议流式 arm 直接 `throw LLMError.ProviderError("Gemini blocked the prompt (blockReason=$blockReason)")`（**新文案**）。本实现**没有另起文案**，而是复用非流式 arm 的既有文案（`EmptyCandidates.describe()`），因为任务书同一节要求「文案与分类必须与非流式 arm 一致」，而「一致」在「复用同一构造器」下才是**机械可证**的（不是靠人眼比对两段字符串）。既有单测对非流式 arm 文案的断言因此不需要改动。

## §7 未做 / 残留（每条带触发条件）

1. **未改 `geminiEofTerminal` 的既有判据**（任务书明令）。触发条件：本缺陷修完后，若仍出现「EOF 无 candidates 但也无 `promptFeedback`」的帧仍走空响应路径 —— 该路径**是正确行为**（上游真的什么都没返回），不属于 F-04。
2. **未加 `promptFeedback` 之外的拒答形态**（如 `finishReason: SAFETY` / `finishReason: RECITATION`）。触发条件：真机日志出现 `finishReason=SAFETY` 且用户报「拒答没提示」时另立一条；本轮不猜（Gemini 的 `finishReason` 语义与 `blockReason` 不同源，盲加会误伤正常结束）。
3. **未覆盖非流式 arm 的「空体拒答」（无 `blockReason`）文案微调**。触发条件：无 —— 既有文案与分类保持原样。
4. **真机端到端未验**（本环境无 Gemini 原生 key 的真机拒答样本）。触发条件：新包装机后用户手动触发一次 Gemini 拒答，日志出现 `ProviderError` 且含 `blocked:` 字样即闭环。
5. **沙箱探针未入库**。触发条件：若后续有人需要复跑本夹具的本地红/绿，须重建 `fixc-probe/`（探针说明已在本节 §5 记录）。

## §8 身份链

- 分支：`fix/rhub1105-gemini-blockreason`
- 基线：`00dc003a3743110258b7cdf2e1feca9c53278f24`
- **修复提交**：`99e84a3cb8850cdc5a20bbf4a8adbc19fe7b6701`
- **CI run id**：`42027037214`（run_number **3000**，`workflow_dispatch`）
- **CI 结论**：`success` —— 四个 job 全 success：`build-apk` / `scan-gate` / `unit-tests` / `publish`
- **head_sha**：`99e84a3cb8850cdc5a20bbf4a8adbc19fe7b6701` —— 与本地 HEAD **逐字符一致**
- 远端分支 sha 核对：`git ls-remote --heads origin refs/heads/fix/rhub1105-gemini-blockreason` = `99e84a3cb8850cdc5a20bbf4a8adbc19fe7b6701`，与本地 HEAD 一致
- 停止节点：**CI success 且 head_sha 逐字符一致 = 停止节点，不合 main**。
