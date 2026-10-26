# FIX-BATCH5-t12 — `provider_import_id_roundtrip` + `models_provider_field` 门见证加固

> 第五批门见证收口 · 支线 t12 · 分支 `fix/b5-provimport-modelsp` · 基线 `base/batch5-shared` @ `e145ec961`
> 生产 `.kt` **0 行改动**；`scan.sh` / `test_scan.py` / `_kotlin_*.py` 零触碰。

---

## ① 门与轴

| 门 | 见证数 | 轴分布 | 原判据失效形态 |
|---|---|---|---|
| `scripts/scan/provider_import_id_roundtrip_check.py` | 6（`G06-A1`..`A6`） | dataflow ×6 | 「token 在文件里出现」≠「这个值真的承重」：谓词恒假、写入侧被删、构造器实参换成 `null`、`updateEntry` 首参换成旧条目、`.copy(...)` 各字段改用原始配置、`runCatching` 后补 `getOrThrow()` 全部照绿 |
| `scripts/scan/models_provider_field_check.py` | 6（`G12-A1`..`A6`） | family-hole ×3（A1/A3/A6）+ dataflow ×3（A2/A4/A5） | 同上，另加两处「字面量当合法值」：`providerName = "Custom"` 被 `_names_family` 当成合法 family 名；`displayName → emptyList()` 因只查键名而放行；恒真短路 `if (1 == 1)` 因只认 `true`/`false` 字面量而漏判 |

合计 **12 条见证**，全部由外部模型以「能过门的最小补丁」构造，主会话已机械复证（打补丁 → 跑门 → 还原 → sha256 校验），12/12 打补丁后 `rc=0`（实锤失明）。

---

## ② 见证原文（file / find / replace / why_gate_green）

### G06-A1 · Arm 1 `check_merge_requery`（dataflow）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- **find**：
  ```
                  _config.value.modelEntries.firstOrNull {
                      it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
                  }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)
  ```
- **replace**：
  ```
                  _config.value.modelEntries.firstOrNull {
                      false
                  }?.id ?: compositeEntryKey("", "")
  ```
- **why_gate_green**：`check_merge_requery` 用 `_balanced` 取出 `firstOrNull { ... }` 块后，只检查后缀是否匹配 `^\s*\?\.\s*id\b`、以及 `else_code` 里是否含 `"?: compositeEntryKey("`；谓词内容与 `compositeEntryKey(...)` 的实参从不检查。
- **缺陷复现**：导入备份走新模型分支 → `entryIdMap` 全是常量 `"/"` → 阶段 2/3 把所有模型引用改写成不存在的 `"/"`，恢复后模型组与会话绑定全失效。

### G06-A2 · Arm 4 `check_convert_echo`（dataflow）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/backup/OpenMinisBackupCompat.kt`
- **find**：
  ```
              d.optString("reasoningEchoJson", "").takeIf { it.isNotEmpty() }
                  ?.let { r.put("reasoningEchoJson", it) }
  ```
- **replace**：
  ```
              d.optString("reasoningEchoJson", "").takeIf { false }
  ```
- **why_gate_green**：`check_convert_echo` 只有一行判据——`"reasoningEchoJson" not in code_only(body)` 就报错。读取锚点还在，写入侧被删也判绿。
- **缺陷复现**：iOS 备份导入后自定义思考规则丢 `reasoningEchoJson`，DeepSeek/Kimi 多轮工具调用丢推理回显。

### G06-A3 · Arm 3 / Arm 5 `check_field_completeness`（dataflow）
- **file**：同上 `ProviderRepository.kt`
- **find**：
  ```
                  reasoningEffortValues = reasoningEffortValues,
                  declaresNoEffortTiers = declaresNoEffortTiers,
  ```
- **replace**：
  ```
                  reasoningEffortValues = null,
                  declaresNoEffortTiers = null,
  ```
- **why_gate_green**：判据只在 `parse_code` 里找「含 `opt` 且含 `"<prop>"`」或「含 `has(` 且含属性名」的行；从不解析 `LLMModel(...)` 构造器的实参列表，因此解析出来的局部变量弃之不用、实参传 `null` 完全无告警。
- **缺陷复现**：导入的模型条目丢失 effort 分层声明，推理档位下拉退化为默认。

### G06-A4 · Arm 7 / Arm 9（dataflow）
- **file**：同上 `ProviderRepository.kt`
- **find**：
  ```
                  val updated = mergeImportedEntryOntoLocal(hit, entry)
                  if (updated == null) hit.id else (updateEntry(updated, stampUserModified = false) ?: hit.id)
  ```
- **replace**：
  ```
                  val updated = mergeImportedEntryOntoLocal(hit, entry)
                  if (updated == null) hit.id else (updateEntry(hit, stampUserModified = false) ?: hit.id)
  ```
- **why_gate_green**：Arm 7 只查 `"mergeImportedEntryOntoLocal(" in merge_code`；Arm 9 只用正则取 `updateEntry(...)` 括号内是否有 `stampUserModified = false`。**第一个位置参数是不是 `updated` 从不检查**。
- **缺陷复现**：命中已有条目的合并分支把「合并结果」丢掉，落库仍是旧条目 → 导入的字段被静默丢弃（只有 DB 行数与日志显示"成功"）。

### G06-A5 · Arm 8 `check_save_config_publishes_canonical`（dataflow）
- **file**：同上 `ProviderRepository.kt`
- **find**：
  ```
              val published = canonical.copy(
                  instances = canonical.instances.toMutableList(),
                  modelEntries = canonical.modelEntries.toMutableList(),
  ```
- **replace**：
  ```
              val published = canonical.copy(
                  instances = canonical.instances.toMutableList(),
                  modelEntries = config.modelEntries.toMutableList(),
  ```
- **why_gate_green**：判据找到 `val published = <rhs>` 后只用 `re.match(r"([A-Za-z_]\w*)", rhs)` 取 `rhs` 开头的标识符，等于 `canonical` 就通过；`.copy(...)` 内部各字段取自 `canonical` 还是 `config` 不查。
- **缺陷复现**：新增模型条目在 `saveConfig` 后 DB 存复合主键、内存 `_config.value` 却带随机临时 UUID → 重启前 UI 引用悬空。

### G06-A6 · Arm 10 `check_save_config_decouples_mirror_from_db`（dataflow）
- **file**：同上 `ProviderRepository.kt`
- **find**：
  ```
          dbWrite.onFailure { e ->
              android.util.Log.e(
                  "ProviderRepo",
                  "[ProviderStore] DB incremental sync failed (provider.db unavailable?); " +
                      "writing JSON mirror only — DB stale until next successful save: ${e.message}",
                  e,
              )
          }
  ```
- **replace**：同文本 + 末尾追加一行 `dbWrite.getOrThrow()`
- **why_gate_green**：判据只验证 ①`providerDao.syncAllIncremental` 在 `runCatching {}` 内 ②`prefs.edit()` 在其外且偏移更大。**两者之间是否 rethrow / 早退不查**。
- **缺陷复现**：DB 写失败（磁盘满 / SQLite 锁 / 迁移异常）时 `getOrThrow()` 把异常重抛，JSON 镜像永不落地 → 日志写着「writing JSON mirror only」，实际什么都没写。

### G12-A1 · Arm 1 `check_arm1`（family-hole）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt`
- **find**：`            provider = instance.providerType.displayName,`
- **replace**：`            provider = instance.providerType.displayName.takeIf { false } ?: "",`
- **why_gate_green**：`value_expression` 只剥离 `also|apply|onEach|forEach` 与 `let|run`；`takeIf { false } ?: ""` 原样返回整串，随后只做 `INSTANCE_IDENTITY` token 包含判定 ⇒ 锚点在、赋值为空串也绿。
- **缺陷复现**：模型列表页 provider 列全空，用户无法分辨模型来自哪个提供商。

### G12-A2 · Arm 2 `check_arm2`（dataflow）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/provider/ModelsDevApi.kt`
- **find**：
  ```
          "Google Gemini" to listOf("google", "google-vertex"),
          "xAI (Grok)" to listOf("xai"),
          "Kimi Code" to listOf("moonshotai", "moonshotai-cn"),
      ) + ProviderType.values().flatMap
  ```
- **replace**：三行改成 `to emptyList(),`，并把动态合并尾改成 `.filter { false }.flatMap {`
- **why_gate_green**：判据只检查 `providerKeyMap = mapOf(...)` 里键名字符串是否存在，`to` 右侧是否非空、动态合并尾是否恒假都不查。
- **缺陷复现**：模型发现接口认不出这三家的 provider key → 三家的模型列表永远拉不到（静默空列表）。

### G12-A3 · Arm 3 `check_arm3` & `_names_family`（family-hole）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/provider/ModelListProviderAdapters.kt`
- **find**：`            providerName = ProviderType.kimiCode.displayName,`
- **replace**：`            providerName = "Custom",`
- **why_gate_green**：`_names_family` 第一行就是 `if re.fullmatch(r'"[^"]*"', val.strip()): return None` —— **任何双引号字面量（含 `"Custom"`、`""`）都算合法 family 名**。
- **缺陷复现**：适配器把具体家族名换成 UI 兜底文案，用户看到的所有第三方模型都归到「Custom」下，按家族筛选/图标/计费提示全错。

### G12-A4 · Arm 4 `check_arm4`（dataflow）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIModelsApi.kt`
- **find**：
  ```
      internal fun resolveProviderName(isCustomBase: Boolean, providerName: String?): String =
          providerName ?: if (isCustomBase) "Custom" else "OpenAI"
  ```
- **replace**：
  ```
      internal fun resolveProviderName(isCustomBase: Boolean, providerName: String?): String =
          (if (isCustomBase) "Custom" else "OpenAI").also { providerName }
  ```
- **why_gate_green**：判据只检查 `isCustomBase` / `providerName` / `"Custom"` / `"OpenAI"` 四个子串在函数体里共存，没走 `value_expression` 检查 `providerName` 是否位于 `.also` 的**丢弃**位置。
- **缺陷复现**：调用方传进来的家族名被 `.also` 吞掉，永远返回兜底文案（与 A3 同族，但形态不同：A3 直接在源头写字面量，A4 把入参丢进丢弃作用域）。

### G12-A5 · Arm 5 `check_arm5_mc`（dataflow）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt`
- **find**：`                ConfigValue.Int(effectiveModel(e).maxOutputTokens ?: 0)`
- **replace**：`                ConfigValue.Int(0.also { effectiveModel(e).maxOutputTokens })`
- **why_gate_green**：判据只做字符串包含（`effectiveModel(e).contextWindow` / `.maxOutputTokens`）+ 「不含原始字段名」+ 「`ModelsDevApi.effectiveModel(` 调用计数 == 1」，从不判断被读取的值有没有真的进 `ConfigValue.*(...)` 的实参。
- **缺陷复现**：设置页显示的上下文窗口 / 最大输出 tokens 恒为 0（读是读了，读进丢弃作用域）。

### G12-A6 · Arm 6 `check_arm6_chat`（family-hole）
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt`
- **find**：
  ```
      internal fun liveEffectiveModel(): LLMModel? =
          _activeEntryId.value
  ```
- **replace**：
  ```
      internal fun liveEffectiveModel(): LLMModel? =
          if (1 == 1) currentModel else _activeEntryId.value
  ```
- **why_gate_green**：死守卫正则 `\b(?:if|while)\s*\(\s*!?\s*(?:true|false)\b` 只认 `true`/`false` 字面量；`1 == 1` 不含这两个单词 ⇒ 无命中，且 `else` 分支保留了原属性读取。
- **缺陷复现**：`liveEffectiveModel()` 恒返回聊天页当前模型，忽略模型组里选定的条目 → 模型组切换对实际推理不生效。

---

## ③ 判据改造（旧形态 → 新形态）

两门**共同的第一刀**：删掉各自的词法层，改走共享原语。

| 位置 | 旧（自写） | 新（共享） |
|---|---|---|
| 注释/字符串掩码 | `code_only()` / `strip_comments()` 自写状态机 | `_kotlin_lexer.mask_code` / `mask_comments` / `mask_strings`（严格等长，嵌套块注释、三引号、`${}` 模板、反引号标识符全覆盖） |
| 括号配对 / 语句边界 | `_balanced()` / `_brace_body()` / `re` 片段 | `_kotlin_ast.match_bracket` / `statement_span` / `function_body`（深度 0 换行即语句边界） |
| 常量折叠 | 「死分支正则白名单」`if\s*\(\s*!?\s*(true|false)\b` | `_kotlin_ast.const_bool` / `const_value`（短路语义 + Kotlin 优先级，`1 == 1`、`!true && false` 都能折） |
| 丢弃作用域剥离 | `.also` 正则剥离 | `_kotlin_valueflow.peel_discarding`（`also/apply/onEach/forEach/takeIf{false}/let` 全族） |
| 值来源判定 | 「token 在不在」 | `_kotlin_valueflow.value_sources` / `guard_feasible` / `path_feasible` / `decl_sites` / `live_positions` |

逐条判据改造：

### `provider_import_id_roundtrip_check.py`

| 臂 | 旧形态 | 新形态（本轮） |
|---|---|---|
| **Arm 1** merge 回查 | 后缀匹配 `?.id` + `else_code` 含 `?: compositeEntryKey(` | ①`guard_feasible()`：`firstOrNull { … }` 的谓词必须可达（`{ false }` / `{ 1 == 2 }` 判红）；②`compositeEntryKey(...)` 实参必须派生自 `existing.id` / `entry.baseModel.id`（字面量 `""` 判红）；③回查结果必须被 `?:` 消费 |
| **Arm 3/5** 字段完整性 | 行内含 `opt` + `"prop"`（或 `has(` + 属性名） | `_ctor_named_args()` + `_ctor_value_findings()`：`LLMModel(...)` 具名实参必须**派生自解析结果**（`decl_sites` 回溯到 `optString/optInt/optBoolean` 的声明右值）；实参为 `null` / 无关字面量判红 |
| **Arm 4** 备份转换回显 | `"reasoningEchoJson" in code_only(body)` | 读必须流进写：`value_sources` 定位 `optString("reasoningEchoJson"…)`，要求其值出现在 `r.put("reasoningEchoJson", …)` 的实参位置；`takeIf { false }` 后无写入判红 |
| **Arm 7** merge 命中分支 | `"mergeImportedEntryOntoLocal(" in merge_code` | 合并结果必须被**消费**：`decl_sites` 绑定 + `updateEntry(...)` 首位置实参必须是该绑定（`updateEntry(hit, …)` 判红） |
| **Arm 8** saveConfig 发布 | `val published = <rhs>` 的 `rhs` 首标识符 == `canonical` | `.copy(...)` 每个具名实参的取值根必须是 `canonical.*`（`config.modelEntries` 判红）+ 实参不得来自原始配置列表 |
| **Arm 9** updateEntry 标记 | 正则取括号内是否含 `stampUserModified = false` | `call_args()` 解析首个位置实参身份（承接 Arm 7 的数据流）+ 具名实参校验 |
| **Arm 10** 镜像解耦 | `syncAllIncremental` 在 `runCatching` 内 ∧ `prefs.edit()` 在外且偏移更大 | 追加 `returns_exiting()`：`runCatching` 结束到 `prefs.edit()` 之间出现 `getOrThrow()` / `throw` / `return` / `if (dbWrite.isFailure) return` 判红（镜像写必须真的可达） |

### `models_provider_field_check.py`

| 臂 | 旧形态 | 新形态（本轮） |
|---|---|---|
| **Arm 1** `ModelsCollection.add` 的 `provider =` | `value_expression` 剥离 `.also`，检查含 `INSTANCE_IDENTITY` token | `value_sources` 追到 `instance.providerType.displayName` + `guard_feasible`（`takeIf { false } ?: ""` 判红）+ `peel_discarding` |
| **Arm 2** `providerKeyMap` | 键名字符串存在性 | 键 → **非空列表**（`to emptyList()` 判红）+ 动态合并尾不得恒假（`.filter { false }` 判红） |
| **Arm 3** `_names_family` | 任何双引号字面量都算合法 | 字面量 family 名一律判红（`"Custom"` / `""`），只接受 `ProviderType.*.displayName` 之类**派生值**；不可解析 ⇒ fail-closed 红 |
| **Arm 4** `resolveProviderName` | 四子串共存 | 返回值必须**依赖** `providerName`：`peel_discarding` 后 `providerName` 出现在丢弃作用域 ⇒ 判红（`.also { providerName }` 红） |
| **Arm 5** `ConfigValue.*` 数值读者 | 字符串包含 + 调用计数 | `ConfigValue.Int/Long(...)` 的实参必须是 `effectiveModel(e).<field>` 的读取本身；读取位于丢弃作用域内判红；读取缺失/多余适配器调用 fail-closed |
| **Arm 6** `ChatViewModel.liveEffectiveModel` 可达性 | `DEAD_GUARD_RE` 只认 `true`/`false` | `_const_branch()`：`if (1 == 1) A else B` / `when (1) { 1 -> A }` 都折成死分支判红；常量折叠走共享 `const_bool` |

---

## ④ 四层证据

### L1 · 真树 + 自测（沙箱实跑）

```
$ python3 scripts/scan/provider_import_id_roundtrip_check.py .
PROVIDER_IMPORT_ID_ROUNDTRIP: clean (13 arms)         # rc=0

$ python3 scripts/scan/provider_import_id_roundtrip_check.py --selftest
selftest: compliant green / violating red arms 1-6 / read-dropped red arms 3+5 / … / agentloop-no-guard+guard-after+no-lock+read-before-lock+missing red arm 13
PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS            # rc=0

$ python3 scripts/scan/models_provider_field_check.py .
models_provider_field_check: OK (6 arms clean)        # rc=0

$ python3 scripts/scan/models_provider_field_check.py --selftest | grep -c '\[OK\]'
48                                                    # 含 g12-a1..a6 六条新臂
MODELS_PROVIDER_FIELD_SELFTEST=PASS                   # rc=0
```

新增臂（本支固化，共 12 条）：

- `provider`：`g06-a1`（恒假回查 + 字面量复合键）、`g06-a2`（写入侧被删）、`g06-a3`（构造器实参 `null`）、`g06-a4`（`updateEntry` 首参用旧条目）、`g06-a5`（`.copy` 字段取自 `config`）、`g06-a6`（`getOrThrow()` 重抛）
- `models`：`g12-a1`（`takeIf { false } ?: ""`）、`g12-a2`（`displayName → emptyList()` + `.filter { false }` 合并尾）、`g12-a3`（`providerName = "Custom"`）、`g12-a4`（`(if …).also { providerName }`）、`g12-a5`（`ConfigValue.Int(0.also { … })`）、`g12-a6`（`if (1 == 1)` 死分支）

### L2 · 机械反攻 12/12（硬验收）

用主会话驱动（不自造跑法）：

```
$ python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
    --witnesses …/witnesses-B.json --repo . --mode full --only <gate>
```

结果（`/tmp/full-provider_import_id_roundtrip_check.py.json`、`/tmp/full-models_provider_field_check.py.json`）：

| 门 | 见证数 | verdict | gate_rc | restored |
|---|---|---|---|---|
| `provider_import_id_roundtrip_check.py` | 6 | **CAUGHT ×6** | 1（红） | **True ×6** |
| `models_provider_field_check.py` | 6 | **CAUGHT ×6** | 1（红） | **True ×6** |

即：12 条原见证逐条重打，门全部变红且报错指向对应判据；还原后 `sha256` 校验通过（工作树零污染）。

### L3 · 全量门扫描 91/91

沙箱并发窗口下 `bash scripts/scan/scan.sh` 会被 ENOSYS 打断（本支记录了该环境事实），改用**每门一个子进程、带重试**的等价驱动 `probe/probe_sweep.py`：

- `probe/scan-results.txt`：**88 PASS**
- `calendar_events_nullable_predicate_check.py` / `tts_voice_and_sanitizer_check.py`：该驱动按位置参数调用报 usage 错（门只吃 `--root ROOT`），**权威复核均 PASS**：
  ```
  $ python3 scripts/scan/calendar_events_nullable_predicate_check.py --root .
  calendar_events_nullable_predicate_check: PASS
  $ python3 scripts/scan/tts_voice_and_sanitizer_check.py --root .
  tts_voice_and_sanitizer_check: PASS
  ```
- `xproc_boundary_check.py`（驱动里被 ENOSYS 打断的那一门）单独复核：`== cross-process boundary OK ==`，rc=0

⇒ **91/91 `*_check.py` 全绿**，本支两个门改动没有牵连任何邻门。

### L4 · `test_scan.py` 夹具套件

两门在 `scripts/scan/test_scan.py` 里**已注册**（`L2806` provider、`L2917` models，标记串见 ⑦），本支未触碰该冻结文件。

本地尝试被沙箱 ENOSYS 阻断（`subprocess.run` 的 `wait` 报 `[Errno 38] Function not implemented`，三次重试同结果），改由 CI 作为权威执行环境——CI 的 `build-apk.yml` 会跑 `test_scan.py` 全量（344 例），读数见 ⑤。

---

## ⑤ CI

- **workflow**：`build-apk.yml`（手动 dispatch，分支 push 不触发）
- **ref**：`fix/b5-provimport-modelsp`
- **本地 HEAD**：`9996fc32ba066379de3b8fdad38e2d6e515eb2cd`
- **run**：#2687 / id `40207849192`（`2026-10-25T15:37:51Z` 派发，`16:03Z` 结束）
- **job**：`121212538234`（`build`），**attempt 1**
- **head_sha**：`9996fc32ba066379de3b8fdad38e2d6e515eb2cd` —— 与本地 HEAD **逐字符一致**
- **结论**：**success**
- **步级读数**：26 步全部 `success`，**唯一 `skipped` = `Publish to Releases`**（`main` 门控，符合预期）
- **日志判读**（`probe/ci/job-121212538234.log`，1,486,400 B，逐项机械核对）：
  - `Summary: 112 passed, 0 failed` —— **带框线恰 1 条**（其余 `5/10/14/15/25/28/36 passed` 是子套件小摘要，无害）
  - `ALL 344 CASES PASS` —— **恰 1 条**（`test_scan.py` 夹具全量）
  - 门头 `[N/112]` —— **112 个互异编号**
  - ` e: `（Kotlin 编译错误特征）—— **0**
  - 含 `FAILED` 的行 —— **6 条，逐条确认全是 `PASSED` 用例名子串**（`slot denied produces FAILED`、`reason PERSISTENCE_FAILED maps to …`、`phase FAILED maps to Failed`、`terminal FAILED maps to Failed`、`reason EXECUTION_FAILED maps to …`、`legal FAILED terminal`）
  - 本支两门的自测标记在 CI 里可见：`PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS` ×1、`MODELS_PROVIDER_FIELD_SELFTEST=PASS` ×1（即新臂在 CI 环境同样执行）

---

## ⑥ 残留（R1..R6，各带三件）

- **R1 · `guard_feasible` 只折叠字面量与布尔运算**
  触发条件：生产代码出现以运行期常量（`BuildConfig.DEBUG`、`const val` 引用）为条件的守卫；该门会把可达分支判为"不可达"⇒ fail-closed 误红。
  触发后方案：把 `const_val` 常量索引接进 `guard_feasible`（共享层改，需主会话统一做）。
  搁置理由：当前 1,308 个 `.kt` 全仓验收零误红（共享层已过等长/解析验收），误报概率低于为此扩共享 API 的成本。

- **R2 · `models` Arm 2 的动态合并尾只认 `takeIf/takeUnless/filter/filterNot`**
  触发条件：有人把合并尾写成 `.drop(1)` / `.filterKeys { … }` / `.asSequence()` 之类不可判形态。
  触发后方案：扩 `_const_guard_kills` 的作用域族；或对该形态直接 fail-closed 红。
  搁置理由：当前生产代码只有 `filter { it != ProviderType.OPENAI_COMPATIBLE }` 一种形态，防空转的价值 < 误红风险。

- **R3 · `provider` Arm 5 的构造器实参派生只支持「具名实参 + 直接声明右值」一跳**
  触发条件：解析结果先经 `map{}/let{}/?:` 再进构造器（两层表达式）⇒ 判红（方向安全，但属误红）。
  触发后方案：`value_sources` 支持两跳（`let`/`run` 已 peel，补 `map`/`?:` 链）。
  搁置理由：现有 5 个 `parse*` 函数全是「解析 → 具名实参」直连；扩跳数会放宽判据，需先有真误红样本。

- **R4 · `models` Arm 3 现在把任何字面量 family 名判红**
  触发条件：新增 provider 家族若确实需要字面量展示名（如 OEM 定制品牌），会误红。
  触发后方案：加显式白名单常量（集中一处，可审计），而不是放宽 `_names_family`。
  搁置理由：本仓 `providerName` 全部派生自 `ProviderType.*.displayName`；字面量即缺陷形态（G12-A3 正是它）。

- **R5 · 本地 `test_scan.py` 未能在沙箱跑完**
  触发条件：沙箱 ENOSYS 窗口（`subprocess` 的 `wait`/`spawn` 报 `Function not implemented`）持续存在时，任何依赖子进程的本地验证都跑不完。
  触发后方案：沙箱恢复后本地复跑 `python3 scripts/scan/test_scan.py`，与 CI 读数互证。
  搁置理由：CI（真机 GitHub Runner）是同一套脚本的权威执行环境，且本支未触碰 `test_scan.py` / `scan.sh` 冻结面。

- **R6 · `test_scan.py` 本支新臂未见独立注册项**
  触发条件：`test_scan.py` 里两门的条目只断言 selftest 收尾标记，不逐条断言新臂名；若将来有人把某条新臂删掉而收尾标记仍在，夹具套件不会红。
  触发后方案：把 `g06-a1..a6` / `g12-a1..a6` 的臂名做成独立断言（见 ⑦ 建议条目）。
  搁置理由：`test_scan.py` 是本批冻结面（任务书 §2 明令不许改），注册与断言由主会话在并集阶段统一做。

---

## ⑦ `test_scan.py` 注册项（交主会话统一处理）

**现状**：两门已注册，标记串与参数形态如下（本支未改动）：

```python
("provider_import_id_roundtrip_check", "PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS", (), (REPO_ROOT,))   # L2806
("models_provider_field_check",       "MODELS_PROVIDER_FIELD_SELFTEST=PASS",            (), (REPO_ROOT,))   # L2917
```

**建议追加（可选，对应 R6）**——按新臂名做细粒度断言，防止「臂被删但收尾标记仍在」：

| 门 | 标记串 | 期望出现次数 | 说明 |
|---|---|---|---|
| `provider_import_id_roundtrip_check` | `g06-a1` … `g06-a6` | 各 ≥1 | 6 条 G06 见证臂 |
| `models_provider_field_check` | `g12-a1` … `g12-a6` | 各 ≥1 | 6 条 G12 见证臂 |

**臂数**：`provider_import_id_roundtrip_check.py` = **13 臂**（Arm 1..13，其中 Arm 1/3/4/5/7/8/9/10 判据本轮加固，新增 6 条 G06 见证 fixture）；`models_provider_field_check.py` = **6 判据臂**（Arm 1..6，新增 6 条 G12 见证 fixture，`--selftest` 共 48 条 `[OK]` 断言）。

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t12      # 基线 = base/batch5-shared @ e145ec961

# 共享原语自测（本支零改动，仅作为前置健康检查）
python3 scripts/scan/_kotlin_lexer.py --selftest       # KOTLIN_LEXER_SELFTEST=PASS (20 arms)
python3 scripts/scan/_kotlin_ast.py --selftest          # KOTLIN_AST_SELFTEST=PASS (32 arms)
python3 scripts/scan/_kotlin_valueflow.py --selftest    # KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)

# 两门真树 + 自测
python3 scripts/scan/provider_import_id_roundtrip_check.py .            # clean (13 arms), rc=0
python3 scripts/scan/provider_import_id_roundtrip_check.py --selftest   # SELFTEST=PASS
python3 scripts/scan/models_provider_field_check.py .                   # OK (6 arms clean), rc=0
python3 scripts/scan/models_provider_field_check.py --selftest          # SELFTEST=PASS（48 条 [OK]）

# 12 条见证机械反攻（必须 12/12 CAUGHT 且 restored=True）
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
    --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json \
    --repo . --mode full --only provider_import_id_roundtrip_check.py
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
    --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json \
    --repo . --mode full --only models_provider_field_check.py

# 全量门（沙箱被 ENOSYS 打断时的等价驱动；CI 里直接跑 scan.sh / test_scan.py）
python3 probe/probe_sweep.py    # START/COUNT 分批，结果落 probe/scan-results.txt
python3 scripts/scan/test_scan.py     # CI 权威执行（本地受沙箱 ENOSYS 限制）
```
