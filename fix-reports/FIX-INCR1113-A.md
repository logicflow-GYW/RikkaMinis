# INCR-1113 支 A — 模型元数据 / 导入 / 进程边界（5 条）

基线 `4402b69` · 分支 `fix/incr1113-meta` · 工单 `tasks/TASK-A.md`
覆盖：**F-01 / F-02 / F-04 / F-06 / F-09**
停止节点 = 分支 CI 绿且 `head_sha` 与分支 tip 逐字符一致（不合 main）

---

## 1 覆盖 ID 与判级

| ID | 判级 | 文件 | 一句话 |
|---|---|---|---|
| F-01 | P2 | `SelectionOnlyEntryRules.kt:121` + 测试 | 「`emptyList()` = 断言纯文本」是假契约，全链只有一种读法 |
| F-02 | P1 | `ProviderRepository.kt` + `ConfigBackup.kt:1394` | 追加路径按**位置**配对 `_entryIds`，过滤掉一行后全错位 |
| F-04 | P1 | `ModelMetadataPrecedence.kt:47` | 多级前缀只剥一段，`openrouter/anthropic/x` 仍 MISS |
| F-06 | P2 | `scripts/scan/gateway_context_metadata_check.py` | 门没牙：A6 拦不住局部常量转发；A1/A3/A5 只查字符串存在 |
| F-09 | P1 | `ProviderBoundary.kt:64` | API 26–27 上进程名恒 null ⇒ 运行期守卫对所有进程放行 |

---

## 2 逐条：先证 → 修法 → 证据

### F-01 · `emptyList()` 是沉默，不是断言

**先证（本支真树复核）**
- `LLMModel.kt:364` `normalizeModalities()` 末尾 `.takeIf { it.isNotEmpty() }` —— 空列表折回 `null`。
- 消费点全部经过它：`LLMModel.kt:229`（`directionalModalities`）、`:300`（`acceptsImageInput`）、
  `:432`（`imageInputDecision`）、`:505-506`（`capabilityPromptFragment`）。
- 反向证据：跨进程 `ModelExecutionService` 的 `jsonStrList(arr) = if (arr == null) emptyList() else …`
  ⇒ 「端点没报」解码成 `emptyList()`。若 `emptyList()` 真是「断言纯文本」，
  **每一个**跨进程模型都会变成 `DECLARED_TEXT_ONLY`。
- 生产侧产不出 `emptyList()`：`OpenAIModelsApi.kt:173`、`OpenRouterModelsApi.kt:117` 都
  `.normalizeModalities()`；`ModelsDevApi.parseArray` / `readModalitiesWithBitfieldFallback` 同样 `takeIf`。
  只有测试在构造它。

**修法**
1. `mergeCatalogRow`：`fresh.inputModalities?.takeIf { it.isNotEmpty() } ?: existing.inputModalities`
   （`outputModalities` 同）。
2. KDoc 重写为**一套**语义：`null` 与 `emptyList()` 都是「无意见」；唯一的肯定形式是非空列表。
3. 测试改名 `an empty modality list is silence, not an assertion`，断言 `changed == false` 且保留
   `["text","image"]`，并加 `assertEquals(null, emptyList<String>().normalizeModalities())` 把跨进程语义钉死。
4. `normalizeModalities` 补注释：调用方不得把 empty 当断言。

**证据**
- JVM 实测（沙箱 kotlinc 2.1.0）：`OK (16 tests)`。
- 变异负控：把 `takeIf { it.isNotEmpty() }` 去掉 → `1) an empty modality list is silence, not an assertion` **红**，恢复后绿。
- 用户行为变化：端点/跨进程返回空模态列表时，已选模型的能力不再被抹成 null（此前 UI 上 vision 开关会莫名关掉）。

### F-02 · 追加路径按原始下标配对 `_entryIds`

**先证**
- `mergeImportInstanceJSON` 用 `ImportedModelEntry.sourceIndex` 按**原始下标**配对（`:3586` 附近，正确）。
- 追加路径 `importInstanceJSON` 走 `parseImportedModelEntriesPlain`（`.map { it.entry }`，丢弃 sourceIndex），
  `ConfigBackup.kt:1394` 之后用 `orderedEntryIds(...)` 与 `srcEntryIds` **按位置**配对。
- `parseImportedModelEntries` 会 `continue` 掉 `isHidden && !isCustom` 的目录行 ⇒ 恢复**分表前**的备份
  （可见/隐藏交错）时，过滤后列表比 `_entryIds` 短，从第一条被跳过的行起全部错位。
- 触发条件：目标设备**没有**同 `(providerType, label)` 实例（全新安装 / 改过 label）⇒ 走追加路径。

**修法**
1. `importInstanceJSON` 加可选参数 `entryIdMapOut: MutableMap<String,String>? = null`，
   内部改用 `parseImportedModelEntries`（带 sourceIndex），写入后按 `_entryIds[sourceIndex]` 建映射。
   **保持原签名可调用**（4 个外部调用点 `ProviderDebugMethods` / `ShareReceiverActivity` / `ProviderListScreen`×2 不动）。
   > 为什么不做成新函数 `importInstanceJSONWithEntryMap`：`importInstanceJSON(` 这个名字是
   > `provider_import_id_roundtrip_check` 臂 6 的锚点（它 walk 这个函数体证明追加路径仍走
   > `readImportInstanceFields` 共享面）。把实现挪进新名字、留下空壳包装会让门扫到空壳。
   > 同一条教训已经写在 `parseImportedModelEntries` 的 KDoc 里（曾因改名让门报 22 条幻影违规）。
2. 纯逻辑抽到 `ProviderRepositoryUtils.kt`：`readSrcEntryIds(dict)` + `pairEntryIdsById(...)`
   （可 JVM 测，不拉 Android）。
3. `ConfigBackup` 追加路径改为 `importInstanceJSON(obj.toString(), entryIdMapOut = entryIdMap)`，
   删掉 `orderedEntryIds` + `minOf` 位置配对段。
4. 被过滤的行不进 map（组引用走既有 unmapped 告警），日志记 `skipped N filtered row(s) kept out of the id map`。
5. 删除已成死码的 `parseImportedModelEntriesPlain`（全仓 0 引用，`grep -rn` 确认）。

**证据**
- 新增 `ImportEntryIdPairingTest.kt`（9 例，纯逻辑）：JVM 实测 `OK (9 tests)`。
  含一条「本缺陷的正面证人」：同一输入下位置配对把 `new-visible` 错挂到 `id-hidden`，
  下标配对得到正确的 `id-visible → new-visible`。
- 邻门复跑：`provider_import_id_roundtrip_check` → `clean (13 arms)`；其 `--selftest` → `PASS`。
  `backup_remap_rollup_check` → `OK`。
- 用户行为变化：全新安装上恢复旧备份时，模型组成员 / AgentLoop 绑定 / 压缩模型不再挂到错模型。

### F-04 · 逐段剥离前缀

**先证**
- `registryLookupId` 只剥一段：`indexOf('/')` + `substring(slash+1)`。
  `openrouter/anthropic/claude-sonnet-4` → `anthropic/claude-sonnet-4`，`findDevModel` 只调一次，仍 MISS。
- `findDevModel` 的调用点确认唯一（`ModelsDevApi.kt:219`）。

**修法**
1. `registryLookupId` 改成循环逐段剥离，步数上限 `MAX_REGISTRY_ID_SEGMENTS = 16` 防呆。
   边界保持：前导 `/`、尾随 `/`、无斜杠、空串均原样返回。
2. KDoc 从「只剥第一段」改成「逐段剥离直到只剩末段」；`findDevModel` 的 KDoc 同步。
3. 测试：原 `only the first segment is removed` 改名 `a single-slash id keeps behaving as before`
   （`moonshotai/kimi-k3 → kimi-k3` 行为不变），新增 `a multi-level vendor prefix is peeled down to the bare id`。

**证据**
- JVM 实测 `ModelMetadataPrecedenceTest`：`OK (18 tests)`。
- 门 A3 新增「必须循环」判据，变异（把循环改回单剥）→ 红（见 F-06）。

**未修（登记）**：富化顺序（models.dev 值冒充端点权威）—— 见 §5 与 backlog `§INCR1113-F04`。

### F-06 · 门补牙

**先证（本支实跑，复现了工单给的两个洞）**
- 变异 1：把 `OpenAIModelsApi` 的 `knownReasoning` 表达式整段换成 `val knownReasoning = false`
  → 门仍 `OK (6 arms, 0 violations)`，rc=0。**洞确认**。
- 静态读码：`parseOpenAIModelEndpointMetadata` 解析 `supportsTools/Vision/Audio`，
  但 `OpenAIModelsApi` 只用 `displayName/contextWindow/maxOutputTokens/supportsReasoning` ——
  三个能力标志解析完即丢，而 A1 只查字符串在不在，所以全绿。
- `_selftest()` 只判 `rc != 0`，不校验是哪条臂红的。

**修法（门内改，0 新建门）**
1. **A6 补「局部常量转发」**：取 `knownReasoningFamily = (\w+)` 的 token；若为 `false` → 违规；
   若是标识符，回查同文件 `val <token> = <expr>`，`expr` 是 `true`/`false` 字面量 → 违规。
2. **A1 补「解析了就必须消费」**：`supportsVision` / `supportsAudio` 必须出现 `endpointMeta.<flag>` 的
   **使用**；`supportsTools` 进显式豁免表 `CONSUMPTION_EXEMPT`（理由：`LLMModel` 没有对应字段）。
3. **A3 补消费点**：`registryLookupId` 体内必须有 `while`（逐段剥离）；`findDevModel` 体内
   `val <bare> = registryLookupId(...)` 的 `<bare>` 必须真的当 `prov.models[<bare>]` 的 key 用。
4. **A5 补消费点**：`ModelEntryDetailScreen` 必须调 `buildModelEntryOverrides`，且**不得**直接调
   `resolveContextWindowOverride`（否则绕过 builder 的 guess-drop 规则）。
5. **`_selftest()` 校验臂名**：每条 mutation 声明 `expect_arm`，输出里必须出现该臂名，否则自检失败；
   另加「mutation 未改变文本」检测（夹具漂移即红）。
6. 未动 `GATEWAY_CONTEXT_METADATA_SELFTEST=PASS` 收尾标记（`test_scan.py:3037` 靠它），
   未动调用形态（`--selftest` / 位置参数 `root`）。

**生产侧配套（把 A1 变绿，而非豁免）**
- 新增 `mergeDeclaredModalities(declared, declaresVision, declaresAudio)`：
  `null`/`false` 不改动（本地网关对所有行都报 false，false 不可当权威）；`true` 补 `image`/`audio`；
  列表为 null 时从 `["text"]` 起算。
- `OpenAIModelsApi` 的 `inputModalities` 改走它。用户行为变化：端点只声明
  `supports_vision: true`、不发布 `architecture.input_modalities` 时（常见形态），模型现在是 vision 模型。

**证据**
- 夹具从「`val knownReasoning = false` 的违规形态」改成合规形态（真实 id 家族判定），
  否则合规夹具本身就违规。
- `--selftest`：14 条 mutation 全部 `✅ … caught` + `GATEWAY_CONTEXT_METADATA_SELFTEST=PASS`，rc=0。
- 真树：`OK (6 arms, 0 violations)`，rc=0。
- **真树变异负控（4 条，逐条命中对应臂名）**：
  - A6 局部常量 → `A6 reasoning: 'knownReasoning' is a hard-coded constant …`
  - A1 vision 未消费 → `A1 OpenAIModelsApi: 'supportsVision' is parsed but never consumed …`
  - A3 单剥 → `A3 precedence: 'registryLookupId' strips only ONE segment …`
  - A3 结果丢弃 → `A3 ModelsDevApi: 'registryLookupId''s result is never used as a registry key …`
  - A5 屏幕旁路 → `A5 detail screen: the context-window field no longer goes through 'buildModelEntryOverrides' …`
- `mergeDeclaredModalities` 6 例并入 `OpenAIModelEndpointMetadataTest`：`OK (27 tests)`。

### F-09 · API 26–27 的进程名回退

**先证**
- KDoc 声称 null = 「fail closed」，代码 `:82` 是 `if (processName == null) return null`（放行）。
- `currentProcessName()` 调 `Application.getProcessName()`（API 28），API 26–27 抛 `NoSuchMethodError`
  被 `runCatching` 吞成 null ⇒ 真机上 `enforce(...)` 对**所有**进程放行。
- 同族：`ModelExecutionService.kt:347` 回退成裸 `"modelservice"`（无包名前缀），与随后
  `readProcIdentity` 读出的 `com.rikkaminis.app:modelservice` 不一致。

**修法**
1. `currentProcessName()` → `readProcessName(getProcessName, readCmdline)`（两个源都注入，纯函数可测）。
   **只有抛异常才回退** `/proc/self/cmdline`；返回 null 不回退（`unitTests.isReturnDefaultValues = true`
   下 stub 是返回 null 而不是抛，回退会答成 "GradleWorkerMain"，把每个 provider 单测都判死）。
2. `readProcCmdlineName(readFile = …)`：读 `/proc/self/cmdline`，取首个 `\u0000` 之前。
3. `enforce` 的 null 分支**保留放行**（JVM 单测依赖它），KDoc 改成与代码一致的表述，删掉「fail closed」。
4. `ModelExecutionService` 改用同一 resolver，再退到 `procState.identity?.processName`，最后才 `"modelservice"`。

**证据**
- JVM 实测（同模块编译，含 android.app.Application 桩）：6 例全绿 ——
  getProcessName 抛 → 回退拿到带包名的名字；direct 命中 → 0 次 cmdline 读；
  两源皆失败 → null 且 `enforce(null) == null`；stub 返回 null → 0 次 cmdline 读；空 cmdline → null。
- 用户行为变化：API 26–27 设备上主进程直接发 provider 请求会被真正拒绝（此前静默放行）；
  worker 身份日志里的进程名与 `/proc` 读出的形态一致。

---

## 3 本地验证清单（本支实际跑过的）

| 项 | 命令 | 结果 |
|---|---|---|
| 本门自检 | `python3 scripts/scan/gateway_context_metadata_check.py --selftest` | `SELFTEST=PASS`（14 mutation） |
| 本门真树 | 同上 `.` | `OK (6 arms, 0 violations)` rc=0 |
| 本门真树变异 | 4 条（见 F-06） | 逐条命中对应臂名，rc=1 |
| 邻门 1 | `provider_import_id_roundtrip_check.py .` | `clean (13 arms)` |
| 邻门 1 自检 | `… --selftest` | `PASS` |
| 邻门 2 | `backup_remap_rollup_check.py .` | `OK` |
| 邻门 3 | `provider_boundary_guard.py .` | rc=0 |
| 邻门 4 | `model_entry_override_parity_check.py .` | `OK (8 arms, 0 violations)` |
| 邻门 5 | `i18n_check.py .` | `ALL CLEAN`，missing 0 |
| JVM F-01 | kotlinc + JUnitCore | `OK (16 tests)` |
| JVM F-01 变异 | 去掉 `takeIf` | 目标测试红，恢复绿 |
| JVM F-02 | 同上 | `OK (9 tests)` |
| JVM F-04 | 同上 | `OK (18 tests)` |
| JVM F-06 | `OpenAIModelEndpointMetadataTest` | `OK (27 tests)` |
| JVM F-09 | 同模块编译 | `OK (6 tests)` |

**没跑**：`scan.sh` / `test_scan.py` / `./gradlew test`（任务书禁跑全量，交云端 CI）。

---

## 4 CI

- run **3048** / id `43442347650`，`head_sha = eb9b5ba21a7c…`（与本地 HEAD 逐字符一致）
- 结论见 §6 补记。

---

## 5 残留与未修登记

| 项 | 处置 | 理由 |
|---|---|---|
| F-04 富化顺序（models.dev 值冒充端点权威） | **登记不修** → `backlog.md §INCR1113-F04` | 修法要跨四层加 provenance 字段或改 enrich 顺序；无用户可观察症状证据。已在 `mergeCatalogRow` 的 KDoc 加 `ponytail:` 三段（简化什么 / 天花板 / 升级触发） |
| `supportsTools` 解析后未消费 | **显式豁免**（门内 `CONSUMPTION_EXEMPT`） | `LLMModel` 没有 tools 能力字段；加字段是四处同步 + 迁移，超出本支半径 |
| `orderedEntryIds` 现在只剩导出侧调用 | 保留 | `SelectionOnlyContractTest` 断言它存在且不 filter（测试锚点，删会红） |
| `ModelExecutionService` 的 `"modelservice"` 字面量 | 保留为第三级兜底 | 前两级（getProcessName / /proc）都拿不到才用；此时 `readProcIdentity` 也是 UNKNOWN，没有更好的答案 |

---

## 6 复现步骤

```sh
# 分支
git checkout fix/incr1113-meta

# 本门
python3 scripts/scan/gateway_context_metadata_check.py --selftest
python3 scripts/scan/gateway_context_metadata_check.py .

# 复现 F-06 的两个旧洞（把门换回基线版本后跑）
git show 4402b69:scripts/scan/gateway_context_metadata_check.py > /tmp/old_gate.py
#   (a) 真树里把 knownReasoning 换成 val knownReasoning = false → /tmp/old_gate.py . → 仍 rc=0
#   (b) 把 declaresVision = endpointMeta.supportsVision 换成 null → 仍 rc=0
```

---

## 7 本轮踩到的两个坑（可复用）

1. **改门的夹具前先想「夹具自己合不合规」**。基线夹具里 `val knownReasoning = false` 正是
   A6 新判据要抓的形态；不先修夹具，`--selftest` 会在「干净夹具应通过」这一步就红。
2. **`_selftest()` 只判 `rc != 0` 是假绿**：A3 的「单剥」mutation 一度是**被 A2 的另一条臂**抓到的
   （rc=1 但红的是 A2），旧自检照样打印 `✅`。加 `expect_arm` 后立刻暴露。判据：
   **变异负控必须校验「是哪条臂红的」，不能只看退出码。**
