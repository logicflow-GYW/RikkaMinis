# FIX-FULLSCAN-1119-D — provider 预算 + 工具/UI 五条（F-10 / F-11 / F-13 / F-17 / F-18）

分支 `fix/provider-budget-1119`，基线 `ecb03d92`（= 当前 main），worktree
`/var/minis/shared/work/r3d1119f/provider`。

改 5 个生产文件 + 9 个测试（1 个既有文件追加 4 条用例，8 个新文件）。
**禁碰项零命中**：`provider/thinking/**` 一行未改（F-11 的钳位放在
`GeminiProvider.kt` 里、`ThinkingRuleResolver` 的族规则表只读不改），
`ChatViewModel.kt` / `scripts/scan/**` / `backup/**` / `offload/**` / `ui/chat/**` 一字未动。

## 1 逐条先证（读源码，不采信报告行号）

| ID | 先证结论 | 真树位置 |
|---|---|---|
| **F-13** | 成立。`requestedLines == null` 时 `isTail` 只影响 `skip`，落进 else 分支后 `skip = if (isTail) 0 else offset-1` → **0**，`limit = null` → 从头读到 `max_length`。header 的 `windowBounds` 也按 `totalLines` 算，宣称 `showing 1-N of N`。 | `FileReadTool.kt:123`（改前） |
| **F-18** | 成立，且有**两条**泄漏路径，报告只点了第一条：①`:97 Log.d(TAG, "Request: $requestLine")` 原文入日志；②`:135 URI("http://localhost${parts[1]}")` 抛 `URISyntaxException` 时**异常 message 里带整条 target**（含 `?code=&state=`），被 `:216` 的 catch-all `Log.w(TAG, "Error handling connection", e)` 写进日志。 | `OAuthCallbackServer.kt:97` / `:135` / `:216` |
| **F-10** | 成立。`thinkingBudget()` 只保证「低于 max_tokens」，`max_tokens <= 1024` 时算出 511 / 1023，仍满足旧守卫 `> 0 && < max`，于是**照发**——Anthropic 文档要求 `budget_tokens >= 1024`，必 400。 | `AnthropicProvider.kt:730`（改前） |
| **F-11** | 成立。`buildThinkingConfig` 的来源 `ThinkingRuleResolver.geminiThinkingConfig` 按**模型族**给值（2.5 Pro HIGH = 16384），与本次请求的 `maxOutputTokens` 无关；`maxTokens=1024` 时发出 `thinkingBudget=16384 > maxOutputTokens=1024`。 | `GeminiProvider.kt:711`（改前） |
| **F-17** | 成立。`val pfd = open(...)` 后立刻 `PdfRenderer(pfd)`，而 `descriptor = pfd` 在构造**之后**——构造抛异常（非 PDF / 加密文档 / renderer 服务不可用）时 fd 无人持有，每次尝试泄漏一个。 | `FilePreviewScreen.kt:735`（改前） |

## 2 修法（逐条给「停在第 N 级」）

| ID | 停在第 N 级 | 说明 |
|---|---|---|
| F-13 | **第 7 级 + 一处提取** | 新增 `DEFAULT_TAIL_LINES = 100` 与纯函数 `effectiveLineCount(direction, requestedLines)`：`tail` 无 `lines` → 100，`head` 保持 `null = 读到 max_length`。工程里**没有**先例常量可复用（`grep DEFAULT_TAIL` 全仓零命中），所以这是一个新数值而不是第二份。100 取 `tail -n 100` 的通行窗口。 |
| F-18 | **第 7 级** | 新增纯函数 `callbackRequestSummary(requestLine)`：`method + path + code/state/error 的 <absent|empty|len=N> + params 计数`。**只点名三个 OAuth 参数名**（白名单），其余键只计数不点名——回环端口可被任意 redirect 打到，回显攻击者选的 KEY 本身就是一种泄漏。第二条路径：`URI()` 换成窄 catch，只记 target 长度。 |
| F-10 | **第 7 级** | `AnthropicLegacyThinkingBudget`（同文件内的 `internal object`，契约的唯一家）给出 `[1024, max_tokens-1]` 窗口，不可满足时返回 null。`buildRequestBody` 里 `budgetTokens` 换成钳后的值，**旧的两侧守卫原样保留**（`> 0 && < cappedMaxTokens`）。为什么保留：`anthropic_thinking_budget_check.py` 的 C1/C2/C5 要求「决定点就在写入点周围的 if 里」，而 `scripts/scan/**` 本支禁碰——所以修法服从门的形状，钳位以纯函数形式嵌进守卫的实参，不搬走判据。 |
| F-11 | **第 7 级** | `clampGeminiThinkingBudget(config, wireMaxOutputTokens)`（同文件 top-level internal，纯 org.json）。钳位点选在 `config.put("thinkingConfig", …)` 那一行，且 `maxOutputTokens` 先落到一个具名值 `wireMaxOutputTokens`，两者读同一个数——这是 S-03 那条「一个值两个读者」教训的复用。输入对象**不改**（返回副本），避免族规则表跨请求被污染。 |
| F-17 | **第 7 级** | `closeOnOpenFailure(resource, open)`：失败臂 `runCatching { close() }` + 重新抛原异常；成功臂不动。**所有权先核过**：`PdfRenderer.close()` 自己关 `mInput`（AOSP `doClose`），`closeLocked()` 又关 `descriptor`，且 `ParcelFileDescriptor.close()` 幂等 ⇒ 成功路径的关闭时机不变，不存在双关风险。 |

## 3 改动清单

| 文件 | 性质 | 行数 |
|---|---|---|
| `mcp/oauth/OAuthCallbackServer.kt` | F-18：日志脱敏 + URI 窄 catch + `callbackRequestSummary` | +71/-2 |
| `provider/anthropic/AnthropicProvider.kt` | F-10：`AnthropicLegacyThinkingBudget` + 写入点改读钳后值 | +47/-3 |
| `provider/gemini/GeminiProvider.kt` | F-11：`wireMaxOutputTokens` 具名 + `clampGeminiThinkingBudget` | +51/-3 |
| `tools/FileReadTool.kt` | F-13：`DEFAULT_TAIL_LINES` / `effectiveLineCount` / 提取 `readFileText(File,…)` | +145/-48 |
| `ui/sandbox/FilePreviewScreen.kt` | F-17：`closeOnOpenFailure` + 构造点改造 | +31/-0 |
| `test/.../provider/AnthropicThinkingBudgetWireTest.kt` | 追加 4 条边界线上断言 | +46 |

测试文件（8 新增）：

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `provider/anthropic/AnthropicLegacyThinkingBudgetTest.kt` | 6 | 窗口两侧边界矩阵 + 「每个答案要么合法要么 absent」 |
| `provider/AnthropicLegacyThinkingBudgetWiringTest.kt` | 3 | 写入点读钳后值 / `budget_tokens` 单点 / 委托不重写 |
| `provider/gemini/GeminiThinkingBudgetClampTest.kt` | 8 | 压下 / 边界不动 / 小额度不动 / 0 / null / 3.x 等级形状 / 不改入参 |
| `provider/gemini/GeminiThinkingBudgetWiringTest.kt` | 2 | 钳位落在 `generationConfig` + 两者同读 `wireMaxOutputTokens` |
| `provider/gemini/GeminiThinkingBudgetWireTest.kt` | 4 | MockWebServer 真请求体（512/1024/2048/100000） |
| `tools/FileReadToolTailWindowTest.kt` | 7 | 真 200 行文件：tail 无 lines / head 无 lines / tail+lines / head+lines / 窗口大于文件 / 负值 / `effectiveLineCount` |
| `mcp/oauth/OAuthCallbackLogRedactionTest.kt` | 8 | 摘要脱敏（含 hostile key 不回显）+ 源码无第二条泄漏路径 |
| `ui/sandbox/FilePreviewPdfOpenFailureTest.kt` | 3 | 构造点必须走守卫 / 守卫关且重抛 / 成功路径不提前关 |

## 4 验证四层

**L1 本地装置（全绿）**

| 装置 | 内容 | 结果 |
|---|---|---|
| `provider-jvm/run-oauth.sh` | 真 socket 端到端（真 `OAuthCallbackServer` + 记录式 `android.util.Log` 桩）+ 仓库测试 + 既有 FD 回归测试 | **OK (16 tests)** |
| `provider-jvm/run-fileread.sh` | 真 `FileReadTool.readFileText` + 真 200 行临时文件 | **OK (7 tests)** |
| `provider-jvm/run-pure.sh` | 接线锚点（源码文本）+ **从 `FilePreviewScreen.kt` 运行时抽取**的守卫函数行为探针 | **OK (11 tests)** |
| `provider-jvm/run-wire.sh` | 真 `AnthropicProvider` / `GeminiProvider` + MockWebServer：`buildRequestBody` 实际发出的 JSON | **OK (29 tests)** |

`run-pure.sh` 里的守卫不是手抄——每次运行都把 `FilePreviewScreen.kt` 里那段原文抽出来编译，源码漂移会直接改变跑的东西（与仓库里 `FilePreviewPdfKeyAndSaveTest` 的源码锚点是互补关系：那边管「文本对」，这边管「抽出来的东西真能关 fd」）。

**L2 变异负控：12 臂全 RED-PRECISE**（`provider-jvm/mutate.py`，快照 + finally + sha256 还原，不用 `git checkout`）

| 臂 | 回退的缺陷形状 | 判红用例 |
|---|---|---|
| M1 | 日志打回原始 `requestLine` | 源码扫 + 端到端探针（4 条） |
| M2 | `URI()` 回退成无保护 | 源码扫 + 端到端（2 条） |
| M3 | 摘要里打 `params["code"]` 原值 | 摘要表 + 端到端（2 条） |
| M4 | `tail` 无 `lines` 落回头部 | 窗口表 + 真文件（2 条） |
| M5 | `head` 也吃 100 行默认 | 窗口表 + head 用例（2 条） |
| M6 | 去掉 1024 下限 | 值表 4 条 |
| M7 | 去掉 max_tokens 上限 | 值表 3 条 |
| M8 | 写入点回退成未钳值 | **线上** 2 条（真请求体里带了 511） |
| M9 | 不做 Gemini 钳位 | **线上** 3 条（16384 > 1024） |
| M10 | 钳位读原始 `maxTokens` 而非 `wireMaxOutputTokens` | 接线 2 条 |
| M11 | `PdfRenderer(pfd)` 裸构造 | 构造点锚点 |
| M12 | 守卫成功臂吞掉 close | 守卫行为 + 锚点 |

**L3 既有门（全部 GREEN，逐个跑，禁跑全量）**

`anthropic_thinking_budget_check.py`（8 条 C1–C5 + C-OAI-1/2/3，本支改动直接受影响的那道：
中途曾因我把 `budget_tokens` 写入搬出 `if` 而 RED，已按门的形状改回实现，现 GREEN）、
`log_url_redaction_guard.py`（18 站点）、`debug_leak_guard.py`、`file_preview_url_encode_check.py`、
`filepreview_print_bounded_check.py`、`tool_title_error_branch_check.py`、
`tool_result_image_branch_check.py`、`truncated_call_guard_check.py`、
`model_defaults_capability_source_check.py`、`xproc_boundary_check.py`、
`outcome_known_chain_check.py`、`gemini_base_version_check.py`、`provider_ua_parity_check.py`、
`mcp_oauth_lifecycle_check.py`、`process_logging_gate.py`。

**L4 CI**：见 §7（push + dispatch 后填）。

## 5 与门的关系（一处需要说明的服从）

`scripts/scan/anthropic_thinking_budget_check.py` 的 C2 要求 `budget_tokens` 的**写入点**被一个
`> 0 && < max` 的双侧 `if` 包住（fail-closed，且它的 KDoc 明写「契约换成别的形状必须**故意**
改这道门」）。本支禁碰 `scripts/scan/**`，所以 F-10 的钳位没有搬走判据，而是变成守卫的**实参**：

```kotlin
val budgetTokens = legalLegacyBudgetTokens(thinkingBudget(cappedMaxTokens, thinkingLevel), cappedMaxTokens)
if (budgetTokens > 0 && budgetTokens < cappedMaxTokens) { body.put("thinking", …) }
```

C1（同一个值两个读者）、C2（双侧守卫）、C5（temperature 挨着 budget_tokens）全部仍然成立且可读。
**如果将来有人想把这段搬进 helper，必须同时改这道门**，否则门会（正确地）红。

## 6 残留与失真（都登记，不修）

| # | 残留 | 判级 | 说明 |
|---|---|---|---|
| R1 | `MCPOAuthController.kt:123` 仍打 `state=${state?.take(8)}` | P2 | 本支禁碰该文件。它是 state **前 8 字符**，比整条泄漏窄，但仍是 CSRF 值的一段。与 F-18 同族，下一轮处理。 |
| R2 | `OAuthCallbackServer.kt:210` 仍打 `error=<value>` | P3（刻意） | `error` 是 RFC 6749 的服务端错误码（`access_denied` 等），不是凭据；且原注释写明「`error=` 与 `error=access_denied` 必须可区分」，删掉等于删排查能力。 |
| R3 | `DEFAULT_TAIL_LINES = 100` 是**新**数值 | P3 | 工程里无先例常量；100 与 `tail -n 100` 对齐。若日后 `head` 也要默认窗口，复用这个常量而不是再造一个。 |
| R4 | `MAX_LENGTH_HARD_CAP` 仍是 `execute` 里的局部 `val` | P3（pre-existing） | 属既有形状，本支不动。 |
| R5 | `filedPreview` 侧的证据只有「抽取后的函数行为」 | 已知边界 | `FilePreviewScreen.kt` 依赖 Compose，沙箱无法整体编译；运行时那一半用抽取文本 + 假 `Closeable` 补足，成功路径的关闭时机靠源码锚点（`descriptor = pfd` 仍在、无 `pfd.close()`）。 |
| R6 | 既有门 `log_url_redaction_guard.py` 对 F-18 的**原始形状失明** | 门缺口 | 把 `Log.d` 改回 `"Request: $requestLine"` 后该门仍 GREEN（实测），说明它判的是 URL 值流、不覆盖 HTTP 请求行。本支的覆盖靠自己的源码扫 + 端到端探针。**不修该门**（禁碰 `scripts/scan/**`），登记为下一轮候选。 |

## 7 身份链

- 基线：`ecb03d92`（worktree `provider` 的 HEAD = 本地 main，漂移 0）。
- 代码提交：`5ae0a28cebcf291b8a769dc24c40d9c82dc3bc88`；远端 `origin/fix/provider-budget-1119`
  与本地逐字符一致（push 后 `git ls-remote` 复查）。
- CI：**run 44456197204 / #? @ `5ae0a28c…`**，五个 job 全 success
  （lint / scan-gate / unit-tests / build-apk / publish）。head_sha 与分支 tip 逐字符相同。
  - unit-tests：**9869 PASSED 行 / 0 真 FAILED**；本支 8 个新测试类 + 既有
    `AnthropicThinkingBudgetWireTest`（现 11 条）逐条 PASSED 已核。
  - scan-gate：**115 passed, 0 failed**；日志有
    `scripts/scan/** untouched in this push — skipping the fixture self-test`
    （本支未动门，这份绿不含夹具证据）。
  - publish：`Move the rolling tag` / `Publish to Releases` 两步 skipped（main 门控），符合预期。
- 报告提交在绿之后单独一条（`80cc850f`）；`git diff 5ae0a28c..80cc850f` 只有本报告文件。
- 报告提交后又对最终 tip **再 dispatch 一次**做 head_sha 复核：**run 44458248635 @ `80cc850f…`**，
  五个 job（scan-gate / lint / build-apk / unit-tests / publish）全 success；
  unit-tests 同样 **9869 PASSED / 0 真 FAILED**，新用例逐条在列。
- **未合 main。**

## 8 自证成本与不确定项

- **沙箱侧编译代价**：`out-wire` 需要 50+ 个生产文件 + 12 个桩（`android.util.*`、`android.content.*`、
  `android.os.*`、`android.graphics.*`、`androidx.compose.runtime.Stable`、`BuildConfig`、
  `NetworkMonitor`/`ProviderHealthTraceListener`、`ModelsDevApi`、`ThinkingRuleCoding`、
  `isOfficialAnthropicHost`）。每个桩都写明「它替代什么、为什么不在被测路径上」，行为或忠实复制或**抛异常**，
  不会静默给出假答案。
- **不确定项**：F-11 的钳位阈值取「不超过 `maxOutputTokens`」，依据是报告与 Gemini 的 400 语义；
  本支没有可用真机/真 key 做线上验证，**这条是纸面语义 + MockWebServer 形状，不是线上实测**。
- **未做**：未合 main；未跑 gradle；未跑全量 `scan.sh` / `test_scan.py`。
