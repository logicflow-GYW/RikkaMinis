# FIX-IMAGE-CHAIN — 图像链四笔（B1 / B2 / B3 / B5）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `627f23f2e`（worktree `git rev-parse --short HEAD` 逐字符一致，开工 `git status --porcelain` 为空） |
| worktree | `/var/minis/shared/work/debt-1026/wt/imgchain` |
| 分支 | `fix/image-chain-oob` |
| 提交序列 | `db8c0b202` 生产修复 6 文件 +231/−87 与两个测试 2 文件 +283（同提交）→ 本报告（仅 `fix-reports/`） |
| 门 / 夹具总数 | **112 / 344 不变**（0 新建门；`scan.sh` / `test_scan.py` / `_kotlin_*` 零触碰） |
| 停止节点 | 分支 CI 绿 = 结束，未合 main |

**文件面**（超出任务书「只改 5 个生产文件」一条，理由见 §7.4）：`ChatStreamToolHelpers.kt`、`ChatPromptAndTools.kt`、`ImageBudget.kt`、`PhotosOffloadHandler.kt`、`MemoryRepository.kt` + `ChatViewModel.kt`（3 行注入点）+ 2 个新 JVM 测试文件。

## 2 B2 — `compressBytes` 丢弃 `compress` 返回值

### 2.1 修前证据

真树：`ImageBudget.kt` 旧 `:116` `out.compress(JPEG, q, baos)` 判决丢弃 → `baos.toByteArray()` 在编码失败时是 **`ByteArray(0)`**；下游 `compressUnderBudget` 判 `reencoded.size <= targetMaxBytes`，`0 <= target` 恒真 ⇒ 空图被采纳进请求 ⇒ provider 侧 HTTP 400 image-decode。代码自带 `record-ok: deep-audit-1048 A-02` 认罪注释，登记触发条件「下次 compression chain 改动」——本支 B3 即满足。

机械反证（`discarded_return_guard.py`，三条对照）：

| 树 | 门结果 | 含义 |
|---|---|---|
| `HEAD` 原样（带 `record-ok`） | `rc=0` | **门是瞎的**：豁免把缺陷挡在门外 |
| 同代码、仅删 `record-ok` | `rc=1`，两条：`[B2] compressBytes() drops the compress verdict` + `[B3] compressBytes() compresses and returns ByteArray, but nothing in the body can report a failed encode` | 判据确实作用于该缺陷 |
| 修复后（无豁免） | `rc=0` | 原生绿，不是靠豁免 |

### 2.2 修法

绑定判决 `val encoded = decoded.bitmap.compress(...)`，走仓库统一的失败谓词 `saveOutcome(streamOpened = true, compressed = encoded)`，false 分支 `return input`（兑现 KDoc 的「encode failure 返回原始字节」）+ 一行 `AppLogger.warning` 带 edge/q/字节数。`compressUnderBudget` 因此只会看到「真图」或「原图」。顺便把 `recycle()` 收进 `finally`：旧形状在 `compress` 抛异常时两个 bitmap 都不回收（泄漏）。

### 2.3 修后证据

`discarded_return_guard.py .` → `rc=0`；`--selftest` → `rc=0`，三段收尾标记全绿（含 `encode verdict section green (every compress verdict reaches a failure exit)`）。

## 3 B3 — 两处 `maxEdge * 2` 手写采样 + 一处无界读

### 3.1 修前证据

- `PhotosOffloadHandler.copyResized`：`openInputStream(src)?.use { it.readBytes() }` —— 无上限读整个资产（用户从相册选的多百 MB 文件在解码前就 OOM）；随后手写 `while (w / sample > maxEdge * 2 ...)` 采样 + `createScaledBitmap`。
- `ImageBudget.compressBytes`：同款循环。
- 机制（1014 波次已定，本支复核）：`maxEdge * 2` 窗口下 4000×3000 源算出的 `sample` 仍是 1 ⇒ **全尺寸解码 ≈ 48 MB ARGB_8888**，再复制到缩放位图（两份大矩形同时存在）。
- 单源已存在：`util/ImageDecoding.decodeBytesBounded(bytes, maxEdge)` —— 先 `probeBytes` 读头部，`ImageDecodeSizing` 按 `(2*maxEdge)^2` 定 `inSampleSize`，再 `shrinkToBound`；KDoc 明写与旧单趟缩放「pixel-identical」。

### 3.2 修法

两处手写循环全部删除，改委托 `ImageDecoding.decodeBytesBounded`（decode 失败返回 `null`，调用方各自保留自己的失败语义）。`copyResized` 的源读改 `readAtMost(input, MAX_RESIZE_SOURCE_BYTES)`，`MAX_RESIZE_SOURCE_BYTES = 64 MiB`（显式命名 + KDoc 说明「上限是针对 bitmap，不是先读进来的编码数组」）；超限抛 `IOException`，被 export 的 `catch (e: Throwable)` 收敛成 `export_failed` + message。`compress` 那半（1048 A-02 已修）**原样未动**。

### 3.3 修后证据

`offload_host_path_check.py .` → `rc=0`（R7 入口派生 + 消毒判据不因重写翻转）；`filepreview_print_bounded_check.py .` → `rc=0`；全量 `scan.sh` → 112/0（§6）。编译级由 CI 承担（`android.graphics`，JVM 不可测）。

## 4 B1 — 会话重载原图未缩放 + 预算淘汰不同步

### 4.1 修前证据

| 断点 | 真树 | 后果 |
|---|---|---|
| `ChatStreamToolHelpers.kt:108` | `val bytes = try { file.readBytes() }` | 重载把**原图**整份塞进 `imageParts` / `contentParts`（发送路径只发 `resizeImageBytes` 的 2000 边压缩版）⇒ 首次重载起，历史每张图按原分辨率进请求 |
| 消费方 | `OpenAIProvider:2277`、`:3246`、`GeminiProvider:515`、`AnthropicProvider:917` 均 `… + msg.imageParts` | `imageParts` 在**每一轮**都上线 ⇒ 原图是「每轮重发」，不只内存驻留 |
| `ChatPromptAndTools.applyRequestImageBudget` | 只 `msg.copy(contentParts = newParts)` | 被预算器「淘汰」的图仍从 `imageParts` 原样发出 ⇒ 淘汰对历史图**完全无效** |

**磁盘原图是刻意设计**（任务书要求先确认）：`ChatPromptAndTools.kt:1547-1551` 的 T209 KDoc 原文写明「resize image bytes for the LLM inference payload only — the full-resolution original is preserved on disk (mediaStore + uploads dir) so chat history fullscreen view, agent shell `cat`, and `read_image` all see the user's original picture, matching iOS」。**故只改「进 LLM 的字节」，磁盘原图与 `mediaRef` 一律不动**，并把这条写进新 KDoc（`toLLMMessage` 与 builder 的 `@param`）。

3 个 `toLLMMessage()` 调用点全部在 IO：`ChatModelRouting.kt:344`（`viewModelScope.launch(Dispatchers.IO)`）、`ChatViewModel.kt:3475`（`sendShellJob = launch(Dispatchers.IO)`）、`ChatQueueInterruption.kt:106` ← 调用方 `ChatViewModel.kt:3860`（同一个 `launch(Dispatchers.IO)`）。**无需额外 `withContext`**。

### 4.2 修法

- (a) 重载路径：`buildSingleLlmMessage` / `buildLlmMessagesFromParsed` 增末位默认参数 `resizeForInference: (ByteArray, String) -> ByteArray? = { _, _ -> null }`（默认 = 保持旧行为，既有测试按位置传参不受影响）；MediaRef 分支改成 `readRestoredImageBytes(file)`（`readAtMost` + `MAX_RESTORED_IMAGE_BYTES = 64 MiB`，null = 超限/读失败 ⇒ `continue`，不静默截断）→ `resizeForInference(raw, mime) ?: raw`，**同一个 ByteArray 实例**同时进 `imageParts` 与 `contentParts`（预算器按身份匹配，复制会让淘汰再次漏掉 `imageParts`）。
  生产实现在 `ChatViewModel` 注入：`resizeImageBytes(rawBytes, mimeType, maxEdge = 2000)` —— 与发送路径**同一个函数、同一个上限**，不复制语义。
- (b) 预算器：`applyRequestImageBudget` 在**真替换成功**时把 id 收进 `droppedHere`（`if (newParts[ref.partIdx] !== originalPart)`——`else` 分支保留原 part 时 contentParts 仍在发字节，此时不该同步删 `imageParts`），收尾 `msg.copy(contentParts = newParts, imageParts = filterElidedImageParts(msg.imageParts, droppedHere))`，其余字段不动。过滤函数按 `ImageBudget.ImagePartId.of(it.data)`（身份）而非内容比较，与 planner 同源。

### 4.3 `imageParts` 全消费点普查（`grep -rn "\.imageParts" src/android/app/src/main/java/`）

| 站点 | 角色 | 本次是否受影响 |
|---|---|---|
| `ChatStreamToolHelpers.kt`（MediaRef 分支） | **构造** | ✅ 修（缩放 + 有界读 + 双载体同实例） |
| `ChatQueueInterruption.kt:149,200,330,375` | 构造（编辑重发 / 截断后重建） | ✅ 间接受影响：都经 `prepared.imageParts` → `toLLMMessage()` 的注入链，无独立读取 |
| `ChatViewModel.kt:3880` | 构造（`prepared.imageParts`） | 同上，无独立读取 |
| `OpenAIProvider.kt:2277`（chat/completions） | 消费 | ✅ 修（淘汰后不再重发） |
| `OpenAIProvider.kt:3246`（responses） | 消费 | ✅ 同上 |
| `OpenAIProvider.kt:3074` | 消费（`hasWirePayload` 判定条件之一） | 受影响但**安全**：淘汰只替换/移除 image 载体，`contentParts` 里的 `Text` 占位仍使消息非空 ⇒ 不会造出「空 turn 被整体删掉」（`MessagePayloadFilter.kt:35` 的判据逐条复核） |
| `GeminiProvider.kt:515` | 消费 | ✅ 修 |
| `AnthropicProvider.kt:917` | 消费 | ✅ 修 |
| `AnthropicProvider.kt:921` / `GeminiProvider.kt:519` / `OpenAIProvider.kt:2281,3251` | 只读 `.size`（日志/指标） | 受影响：淘汰后计数变小 = 期望行为 |
| `LLMProvider.kt:258` | 只读 `.data.size`（估算） | 受影响：估算变小 = 期望行为（与真实发送一致） |
| `ContextEstimator.kt:90` | 只读（估算） | 同上 |
| `MessagePayloadFilter.kt:20` | KDoc 引用 | 无代码影响 |

> 结论：四个消费点（OpenAI 两条 wire 路径 + Gemini + Anthropic）全部被 (b) 覆盖；其余是估算/计数，方向正确。

### 4.4 修后证据（沙箱实跑，非复刻件）

`jvmtest` 探针（真实源文件 + 最小 stub，见 §8）：**`ChatImageRestoreTest` 8/8 通过**

| 臂 | 断言 |
|---|---|
| 有界读边界 | cap=8：恰好 8 字节接受、9 字节返回 null（不截断） |
| 上限是命名常量 | `MAX_RESTORED_IMAGE_BYTES == 64 MiB` |
| 注入生效 + 身份共享 | 16 字节源、resizer 返回 2 字节 ⇒ `imageParts[0].data === resized` 且 `contentParts` 的 `ImageData.data === resized`（同一实例） |
| resizer 弃权 | 返回 null ⇒ 原始 5 字节保留 |
| 不可读路径 | 目录冒充文件 ⇒ `imageParts` 与 `ImageData` 均为空（不整份内联） |
| 身份淘汰 | 删掉指定数组、保留另一个，`mimeType` 不变 |
| 同内容异实例 | 内容相同但不同数组**不被淘汰**（钉住「按身份而非 contentEquals」） |
| 空集快路径 | `dropped` 为空 / `parts` 为空 ⇒ 返回同一 List 实例 |

## 5 B5 — 系统提示词组装双倍读日报

### 5.1 修前证据

`ChatPromptAndTools.kt:1458-1459` 连调 `dailyLogSizeSummary()` 与 `largestDailyLogBytes()`，两者各自走一遍 `MemoryRepository.dailyLogSizes()`；而该 walk 读 `MEMORY-ROLLUP.md` 全文 + **每个候选日报全文**（[dv28-A3]：判「还有没有可提炼的东西」必须看内容，注释自认）+ 每条都跑一遍提炼分类正则 ⇒ **每次组装系统提示词把 memory 目录读两遍、正则扫两遍**。

### 5.2 修法

`MemoryRepository` 增 `dailyLogRollupMetrics(): DailyLogRollupMetrics`（`data class(summary: String?, largestBytes: Long)`，**一次** `dailyLogSizes()` 产出两项）；两个旧公开方法（`dailyLogSizeSummary` / `largestDailyLogBytes`，grep 确认仅本处调用，但按任务书保留）改为委托同一快照，输出逐字不变；`ChatPromptAndTools` 改一行取快照再解构。

### 5.3 修后证据（改前 / 改后打印对照，同一 fixture）

驱动 = `buildSystemPrompt()` 两种写法的**逐字复刻**：old = 两次独立调用；new = 一次快照。夹具 1（3 个日报 + rollup，其中 1 个已被提炼）：

```
--- before (two calls = two full walks) ---
summary=[largest daily log 2026-01-02.md (409 B), total 2 logs 618 B]
bytes=[409]
--- after (one snapshot) ---
summary=[largest daily log 2026-01-02.md (409 B), total 2 logs 618 B]
bytes=[409]
```

夹具 2（12 × 26.4 KB = 344 KB）/ 夹具 3（12 × 210.9 KB = 2.5 MB，接近真机 memory 目录量级）同样**逐字相同**，附计时（每个「组装一次」的均值，20 次迭代）：

| 夹具 | 修前 | 修后 | 备注 |
|---|---|---|---|
| 344 KB | 54.47 ms | 29.96 ms | 去掉第二次 walk |
| 2.5 MB | 102.87 ms | 60.44 ms | 剩余部分是单次 walk + 分类正则的固有成本 |

JVM 测试 `DailyLogRollupMetricsTest` 3/3 通过：①硬编码期望串 `largest daily log 2026-01-02.md (409 B), total 3 logs 727 B` / `409`，并断言两个旧访问器与该快照一致（期望值是字面量，不从被测代码推导——避开「常量变了期望跟着变」的自指陷阱）；②空目录 ⇒ `null` / `0`；③`MEMORY-ROLLUP.md` 已提及某日期 ⇒ 该日报退出候选集，largest 回落到另一条。

## 6 验证四层

| 层 | 内容 | 结果 |
|---|---|---|
| L1 门自测 | `discarded_return_guard --selftest`（三段收尾标记） | `rc=0` |
| L1' 真树 | `discarded_return_guard` / `filepreview_print_bounded_check` / `offload_host_path_check` / `tool_result_image_branch_check` / `offload_media_b64_wire_check` / `share_dir_prune_check` / `chat_ui_interaction_check` / `i18n_check` | 8/8 `rc=0` |
| L2 反攻 | B2 的三条对照（豁免态绿 / 去豁免红×2 / 修复后原生绿） | 见 §2.1 |
| L3 沙箱实跑 | `ChatImageRestoreTest` 8 臂 + `DailyLogRollupMetricsTest` 3 臂 + B5 A/B 三夹具 | 全绿 |
| L4 全量门 | `MINIS_SCAN_JOBS=1 sh scripts/scan/scan.sh` | **112 passed, 0 failed** |
| L5 分支 CI | run `40357191016` / #2759 / `db8c0b202` | 见 §6.1（回填） |

### 6.1 分支 CI（run `40357191016` / **#2759** / job `121692724376`，attempt 1）

| 读数 | 值 |
|---|---|
| 结论 | **success**，`db8c0b202`（与本地 `git rev-parse HEAD` 逐字符一致，三方一致） |
| 时间 | 2026-10-26T16:14:32Z → 16:38:33Z（24m01s） |
| 步骤 | 20 个 `success`，**唯一 `skipped` = `Publish to Releases`**（main 门控，符合预期） |
| 关键步骤 | `Run pre-build scan gate` success / `Run unit tests (full suite)` success / `Build APK with Gradle` success |
| 日志 | 1,494,489 B；**NUL 字节 0**；`Summary: 112 passed, 0 failed` 恰 1 次；`ALL 344 CASES PASS` 恰 1 次；`^ e: ` **0** 行；`Function not implemented` 0 |
| `FAILED` 行 | 6 行，**逐行确认全部是 PASSED 用例名子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED…PASSED` 等），无一真失败 |
| 本支新臂 | CI 日志逐个点名 **11/11 PASSED**：`ChatImageRestoreTest` 8 条 + `DailyLogRollupMetricsTest` 3 条（云侧编译的是真实工程与真实 `android.jar`，不是探针的 stub） |

**停止节点达成**：分支 CI 绿 = 本轮开发结束，未合 main。

## 7 残留（每条带触发条件 + 触发后方案 + 搁置理由）

**R1 — `PhotosOffloadHandler.copyResized` 的有界读没有门守护**（任务书 §2(a)）
触发条件：下次改动 `sandbox/**` 里任何 `contentResolver.openInputStream(...)` 读取。触发后方案：把 `filepreview_print_bounded_check` 的 `SAF_SCAN_DIRS` 从 `ui/**` 扩到 `sandbox/**`（当前该门只扫 `ui/` + `webapp/`，`:1113-1140`），并补一条「裸 `readBytes()` 不算有界」的臂。搁置理由：任务书明确「没有就登记，不要自造门」（装置比例），且本支已把该处收敛到共享 `readAtMost`。

**R2 — 其它 `imageParts` 消费点是否需要与预算器联动**
触发条件：新增 provider 序列化器，或有人改 `MessagePayloadFilter.hasWirePayload` 的判据。触发后方案：新 provider 必须同时读 `contentParts` 与 `imageParts`，并在 §4.3 的普查表里登记；`hasWirePayload` 的 `contentParts.isNotEmpty()` 依赖「淘汰只替换成 Text 占位」这一事实，若改成真删除 part，空 turn 会被静默丢掉。搁置理由：当前 4 个消费点已被 (b) 覆盖，`ChatQueueInterruption` / `ChatViewModel` 两处构造点共用注入链，没有独立读点。

**R3 — MediaStore 存原图的策略约束**
已按任务书写进 KDoc（`ChatStreamToolHelpers` builder 的 `@param resizeForInference` + `ChatViewModel.toLLMMessage`），今后改这条链路的人会读到「磁盘原图不动、只改 LLM 字节」。无需后续动作，仅登记位置。

**R4 — `loadRollupFragment()` 仍与快照分开再读一次 rollup**
触发条件：有人再优化系统提示词组装耗时。触发后方案：把 rollup 全文并入同一快照（`dailyLogSizes` 已经读过它，可顺带返回）。搁置理由：任务书半径只覆盖「两个尺寸访问器」，rollup 全文载入是 tail-有界读、且带 `MEMORY-ROLLUP.md` 缓存语义差异（`loadRollupFragment` 走 `readTailBounded`），合并会扩大改动面。

**R5 — 本地跑了一次全量 `scan.sh`（隐性偏离任务书 §5「本地不跑全量」）**
触发条件：无（一次性）。说明：为拿到 112/0 的独立读数，用 `MINIS_SCAN_JOBS=1` 串行跑约 3 分钟（`nice -n 15`）。`test_scan.py` 未在本地跑，344 夹具读数以 CI 为准。

**R6 — 越界改了 `ChatViewModel.kt`（任务书硬约束「只改 5 个生产文件」）**
触发条件：已发生，需主会话追认。改动内容 = 一个 3 行私有 helper（`inferenceImageResizer` 转发到既有的 `resizeImageBytes`）+ 两处调用点传参。必要性：`resizeImageBytes` 是 `ChatViewModel` 的扩展函数、`toLLMMessage()` / `buildLlmMessages()` 也定义在该类里，**不碰这个文件就无法在不复制缩放语义的前提下把 resizer 注入 builder**。替代方案（在 `ChatStreamToolHelpers` 里重写一份 resize）会违反同一语义单源。改动不触任何门 pin 的判据（112/0 已验）。

**R7 — 重载路径的缩放没有缓存，每次重建历史都要重新解一遍超大图**
触发条件：真机反馈「长会话发送变慢」且历史里有多张大图。触发后方案：按（附件相对路径 + `length()` + `lastModified()`）在内存做 LRU 缓存缩放后的字节（上限几条），`toLLMMessage()` 命中直接返回。搁置理由：改动面扩到缓存生命周期与失效（附件被删/被替换），且当前代价是**每次发送一次**（非每轮），换来的是每轮请求从「原图 base64」降到「2000 边 JPEG」，方向净赚；无实测慢反馈前不动。

## 8 附：装置与产物

- worktree：`/var/minis/shared/work/debt-1026/wt/imgchain`（分支 `fix/image-chain-oob`）
- 探针 A（图像链 JVM）：`debt-1026/u2-probe/` —— `main/` = 11 个**真实源文件**（`ChatStreamToolHelpers.kt`、`ImageBudget.kt`、`ImageBudgetLadder.kt`、`SafImportRead.kt`、`ImageDecoding.kt`、`ImageDecodeSizing.kt`、`SaveOutcome.kt`、`MessageEntity.kt`、`AgentContentPart.kt`、`LLMMessage.kt`、`LLMUsage.kt`、`ChatViewModelMessageParser.kt`、`ThinkTagScanning.kt`）+ `stubs/`（Room 注解、`AgentRuntimeLimitsPrefs`、`AppLogger`、`ChatSessionEntity`、两个 strip 函数）+ `main/FilterElided.kt`（**逐字提取**的 `filterElidedImageParts`，`extract_fn.py` 记录 `sha256=448aa116219c9676dccb615f6b6407991b73d9ff98853ec32eee689ed6b0f001 bytes=944 lines=20`，与 `ChatPromptAndTools.kt` 当前文本逐字节一致）；编译 `kotlinc -cp android.jar`，运行 `JUnitCore` → 8/8。
- 探针 B（B5 JVM）：`debt-1026/u2-probe-b/` —— 真实 `MemoryRepository.kt` + `MemoryRollupEngine.kt` + `android.util.Log`/`SoulStore`/`AgentRuntimeLimitsPrefs` stub → 3/3。
- A/B 对照：`debt-1026/u2-ab/{old,new}/`（`old/MemoryRepository.kt` 来自 `git show 627f23f2e:`）+ `run_ab.sh` + 三套夹具（`fixture-small` / `fixture-big` / `fixture-real`）。
- 门日志：`/tmp/scan-u2b.log`（112/0）、`/tmp/u2g-*.out`（8 个邻门）。
