# FIX-RHUB1105-D — F-05：跨进程流式 JSONL 把 `0` 缓存 token 坍缩成 `null`

## 1. 覆盖 ID

- **F-05**（P1，`TRIAGE.md` 判级"成立，转写质量中"）
- 来源：`/var/minis/shared/work/meta-audit/reports/r6/Rhub_Protocol_1105_Audit_Report.md`
- 独占文件：`sandbox/offload/ChatStreamJsonl.kt`、新增 `ChatStreamJsonlTest.kt`
- 基线：`00dc003a3743110258b7cdf2e1feca9c53278f24`
- 分支：`fix/rhub1105-chatstream-usage`

## 2. 真码核实结论

**机制成立**，报告转写有两处失真、机制本身无误：

| 项 | 报告 | 真码 |
|---|---|---|
| 键名 | `cr` / `cc` | `cache_read` / `cache_creation`（`ChatStreamJsonl.kt:60-61` / `:119-120`） |
| 解码目标类型 | `TokenUsage` | `LLMUsage`（`LLMUsage.kt`，字段 `Int?`） |

- encode（基线 `:57-63`）：`.put("cache_creation", chunk.usage.cacheCreationInputTokens ?: 0)` 与 `.put("cache_read", … ?: 0)` ⇒ **null 与 0 写成同一个整数**。
- decode（基线 `:115-123`）：`obj.optInt("cache_creation", 0).takeIf { it > 0 }` 与同形 `cache_read` ⇒ **所有 0 还原成 null**。
- 语义冲突对象核实：`src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt:1467-1493` 的 `parseUsage` 明确用 `json.has("cache_read_input_tokens")` 区分「字段缺失 = provider 不上报缓存 → null」与「字段存在且为 0 = 缓存已启用但本轮 0 命中 → 0」，KDoc 逐字写了 "`Presence (has) is the discriminator, not the value`"。
- 下游消费点核实：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTurnPartsJson.kt:99` 的 `buildUsageJson` 用 `usage.cacheReadInputTokens != null` 生成 `hasCacheRead` / `hasCacheCreation`；`:98` 的 KDoc 亦载明该布尔位含义。⇒ 这一跳抹平后，主进程这两个布尔位**恒为 false**。
- 生产路径核实：`LLMProvider.ProviderBoundary.enforce()` 禁止主进程直调，`:modelservice` 流式路径是默认路径，`ChatStreamOffloadHandler.kt:254` / `PartialStreamRecovery.kt:189` 都经 `ChatStreamJsonl.decode()`。⇒ 不是死角，是唯一生产路径。

**这改变什么用户行为？**（动手前判据）：在缓存统计面板上，一个「provider 开了缓存、但本轮 0 命中」的 turn 现在会被记为「缓存已启用、命中 0」（`hasCacheRead=true`，值 0），而不再被当成「该 provider 根本不上报缓存」（字段省略 → unknown）。这是展示语义的修正，不改变 token 计费数值本身（`buildUsageJson` 对 null 与 0 都写数值 0）。

**全文件 nullable 字段扫描结论**：`inputTokens` / `outputTokens` / `latestContextTokens` 均为 `Int`（无 null 语义，按要求不动）；`stopReason`、`errorLine.kind`、`errorKind` 是 `String?` 且已用 `ifEmpty { null }` / `putOpt` 对称处理，不属数值坍缩。**已扫全文件，仅 `cacheCreationInputTokens` / `cacheReadInputTokens` 两个 nullable 数值字段被坍缩**，无第二处。

## 3. 修法

一句话：**在共享编解码器里把「键是否存在」作为 null / 0 的判据，两侧对称改，不在任何调用点打补丁。**

- encode：`JSONObject().apply { … }`，两个 `cache_*` 改为 `chunk.usage.…?.let { put(key, it) }` —— null 时**省略键**（与 `json.has()` 天然对齐），0 时照常写出。
- decode：`if (obj.has(key) && !obj.isNull(key)) obj.optInt(key) else null` —— 存在性判定，兼容旧 worker 写出的显式 `JSONObject.NULL`（→ null）与恒写 0 的历史行（→ 0，即那些行本来就只支持这一种读法）。
- KDoc schema 同步标注两键为 OPTIONAL，避免下一次「过时注释诱发同款复发」（同日 /responses、Gemini 两例的同族）。

`grep -rln 'ChatStreamJsonl'` 结果：全仓引用共 13 处，**只有 `ChatStreamJsonl.kt` 一个 decoder**，无第二份复制的 `.takeIf { it > 0 }`。

## 4. 证据（红 → 绿 → 变异臂）

装置：**生产类直编，非影子探针**。沙箱 JVM 单次 `kotlinc` 同时编译真实的 `ChatStreamJsonl.kt` / `LLMUsage.kt` / `LLMStreamChunk.kt` / `OffloadWireBase64.kt` + 真实新测试；仅 `LLMMediaAttachment`（其真声明埋在 `LLMMessage.kt`，连带 `AgentContentPart` 等重链）用同包镜像桩，且 F-05 用例完全不走 `decode("media")` 分支。classpath = `jvm-libs/` 的 kotlin-stdlib 2.1.0 + json-20231013 + junit-4.13.2 + hamcrest + kotlinx-serialization-core（与 gradle `testImplementation` 同源）。全程 `nice -n 10`，未跑全量 `scan.sh` / `test_scan.py` / gradle。

| 阶段 | 结果 |
|---|---|
| **先证（未修代码）** | `Tests run: 5, Failures: 2` — `zeroCacheTokensSurviveCrossProcessRoundTrip` `expected:<0> but was:<null>`；`keyPresenceIsTheCacheEnabledDiscriminatorOnTheWire` `assertTrue` 失败（wire 上 `cache_read` 键缺失）。其余 3 条对称臂（null→null、4096→4096、显式 JSON null→null）**绿** ⇒ 装置有牙且不误伤。 |
| **修复后** | `OK (5 tests)`，`COMPILE=OK`，`JUNIT_EXIT=0`。 |
| **变异臂 A**（decode 改回 `.takeIf { it > 0 }`） | 重新红：`Tests run: 5, Failures: 1`，`zeroCacheTokensSurviveCrossProcessRoundTrip` `expected:<0> but was:<null>`。`RUN_EXIT=1`。 |
| **变异臂 B**（encode 改回 `?: 0`） | 重新红：`Tests run: 5, Failures: 2`，`absentCacheTokensStayAbsent` `expected null, but was:<0>` + `keyPresenceIsTheCacheEnabledDiscriminatorOnTheWire`。`RUN_EXIT=1`。 |
| **源码还原对拍** | 最终基线绿那次编译的 `out/src/ChatStreamJsonl.kt` 与 worktree 生产源 sha256 逐字节一致（`c48bde4b…`），测试文件同样一致（`6952929c…`）⇒ 绿的不是被变异过的副本。 |
| **窄边界门** | `python3 scripts/scan/xproc_boundary_check.py` `GATE_EXIT=0`，`== cross-process boundary OK ==`（未碰 LLMModel bundle，理论应保持绿，实测如此）。 |

测试 `ChatStreamJsonlTest` 5 例，期望值全部写字面量（`0` / `4096` / `512` / `120` / `30` / `150`），不从被测常量推导。除反事实臂外另有两条对称臂守住「改一边等于没修」：wire 键存在性断言（防「两端各自换一套编码仍丢信息」）+ 显式 `JSONObject.NULL` 兼容臂。

## 5. CI run id + head_sha

- CI run id：**42024407127**（`workflow_dispatch` @ 2026-11-05T12:06:54Z）
- head_sha：**`d46faf7adc0dd4f5a266a14bc5efea27093b7e60`**（= 本地 `git rev-parse HEAD` = `git ls-remote` 远端分支 sha，三方逐字符一致）
- 结论：**`success`**（`status=completed`，`updated_at=2026-11-05T12:17:22Z`）；四个 job 全 success：`scan-gate` / `unit-tests` / `build-apk` / `publish`。
- **unit-tests job 日志独立证据**（job `127234887397`，1,483,427 B）：`ChatStreamJsonlTest` **5 条 PASSED 明细**（12:12:46）：`nonZeroCacheTokensSurviveCrossProcessRoundTrip` / `zeroCacheTokensSurviveCrossProcessRoundTrip` / `keyPresenceIsTheCacheEnabledDiscriminatorOnTheWire` / `explicitJsonNullIsDecodedAsAbsent` / `absentCacheTokensStayAbsent`；`BUILD SUCCESSFUL` ×2（5m29s / 1m14s）、`e: file` 0、含 `FAILED` 的 6 行逐条确认全是 PASSED 用例名子串。⇒ 不只是「CI 编译过」，新测试确被 CI 执行。
- **publish job 判读**：整体 `success` 但第 6 步 `Move the rolling tag android-latest` 与第 7 步 `Publish to Releases` 均 **skipped**（`if: github.ref == 'refs/heads/main'` 门控）。⇒ 分支 CI 绿 **≠ 有新 APK**；真机端到端仍需合 main 后装新包，见第 7 节。
- 停止节点：**分支 CI 绿 = 结束**。**未合 main**（有 `.kt` 验证面，非纯文本例外，合并起手权在用户）。

## 6. 残留（登记，不顺手改）

1. **provider 侧同族 `.takeIf { it > 0 }`（不同触发面，按半径纪律不动）**：`OpenAIProvider.kt:2604-2605` / `:3565`（`cached_tokens` / `prompt_cache_hit_tokens`）、`GeminiProvider.kt:831`（`cachedContentTokenCount`）解析的是**上游原始响应**，不是本 codec 的 IPC 回放；其 0-vs-null 是否同样要保留取决于各家 API 语义（`AnthropicProvider` 已专门修过一次），本批无触发证据、不属 F-05 半径，登记不修。复活触发条件：若用户报「某 provider 缓存面板在 0 命中时显示 unknown」，先查这三处再立项。
2. **测试落点与任务书字面不一致（已在实现中修正，非缺陷）**：任务书写 `src/test/java/...`，本仓真实 JVM 单测 sourceSet 是 `src/android/app/src/test/java/...`（`src/android/app/build.gradle.kts` 的 `testImplementation` + 现有 200+ 个单测均在此）。按任务书字面落文件会导致 CI 根本不编译该测试 → 静默绿。已按仓库真实结构落地。
3. **KDoc 里 `{"t":"usage",…,"cache_creation":0,"cache_read":0,"ctx":0}` 示例行仍画着两键常在**：它是「缓存启用且 0 命中」的合法示例，下面已补 OPTIONAL 说明，未删以免丢掉这个样例。

## 7. 未验边界

- **真机端到端未取**：无本分支新包，未验证「Anthropic 系 provider 真开缓存、本轮 0 命中 → 主进程 `MessageMetadata.hasCacheRead=true`」这条完整链路。本轮证据止于 codec 层的 round-trip + wire 存在性 + CI 编译面。
- **旧 worker → 新主进程 / 新 worker → 旧主进程的双向混跑未做真机验证**：decode 侧对「恒写 0 的历史行」的新读法是 0（语义从 unknown 变为「启用且 0」），对显式 `JSONObject.NULL` 是 null，均有单测覆盖，但真实跨版本 IPC 只在装包后可验。
- **`ChatTurnPartsJson.buildUsageJson` 下游 UI 如何渲染 `hasCacheRead=true, 值=0`** 未在本支范围（未读 UI 渲染层），按 F-05 半径只保住信息不丢。
- 本轮未做「CI 上改坏断言看红」的变异臂（任务书禁跑全量 gradle/scan，且本地 JVM 装置已直编生产类 + 两条变异臂证有牙）；但 unit-tests job 日志里 `ChatStreamJsonlTest` 5 条 PASSED 明细已直接证明 CI 执行了新测试（不同于「只编译不执行」的静默洞），见第 5 节。

## 8. 复现命令

```sh
WT=/var/minis/shared/work/meta-audit-1105b/chatstream-usage
PROBE=/var/minis/shared/work/meta-audit-1105b/d-probe

# 基线（5/5 绿）/ 变异臂 A / 变异臂 B（各须重新红）
cd "$PROBE"
nice -n 10 sh run.sh "$WT" none      # → OK (5 tests), JUNIT_EXIT=0
nice -n 10 sh run.sh "$WT" takeif    # → Failures: 1, JUNIT_EXIT=1
nice -n 10 sh run.sh "$WT" coalesce  # → Failures: 2, JUNIT_EXIT=1

# 窄边界门
cd "$WT"
nice -n 10 python3 scripts/scan/xproc_boundary_check.py    # → cross-process boundary OK

# 三方 sha 对拍
echo "LOCAL=$(git rev-parse HEAD)"
git ls-remote --heads origin refs/heads/fix/rhub1105-chatstream-usage

# 非报告差异必须为空（<绿sha> = CI 验证的 d46faf7）
git diff --name-only d46faf7adc0dd4f5a266a14bc5efea27093b7e60..HEAD -- . ':(exclude)fix-reports'
```
