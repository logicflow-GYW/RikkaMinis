# FIX-BATCH5-t07 — 门见证第二轮：`model_entry_override_parity` × `memory_gate_provider_fields`

> 施工支：`B5-t07`（波次 `batch5`）· 报告 = 本文件 · 生产 `.kt` **0 行改动**
> 断言：两门原有判据**全部保留**，只在新判据上"加牙"；门 112 / 夹具 344 不变。

## 0. 身份与范围

| 项 | 值 |
|---|---|
| 基线 | `main @ e145ec961`（任务书 §0 指定；`scripts/scan/_kotlin_valueflow.py` 已在树内，26 臂自测绿） |
| 分支 | `fix/batch5-t07-modelentry-memorygate` |
| 改动文件 | `scripts/scan/model_entry_override_parity_check.py`、`scripts/scan/memory_gate_provider_fields_check.py`、本报告 |
| 不动 | `scan.sh` / `test_scan.py`（冻结）/ `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py`（共享层只调不改）/ 任何生产 `.kt` |
| 门 / 夹具 | 0 新建门 ⇒ 112 / 344 不变 |

**外部报告**：report-A §08（`model_entry_override_parity_check`，6 条）· report-B（`memory_gate_provider_fields_check`，6 条）⇒ **12 条见证，11 FALSIFIED（BLIND）+ 1 CAUGHT（对照）**。

## 1. 门与轴

| 门 | 见证 | 轴分布 |
|---|---|---|
| `model_entry_override_parity_check` | 6（5 BLIND + 1 对照 CAUGHT） | family-hole 4（3 BLIND + 1 对照）· dataflow 2 |
| `memory_gate_provider_fields_check` | 6（全 BLIND） | dataflow 3 · family-hole 1 · parser-death 1 · scope 1 |
| 合计 | **12** | **family-hole 5 · dataflow 5 · parser-death 1 · scope 1** |

两门同一族失效：**判据锚"某个拼写/某个存在"而不是"该物在多路径下的效果"**。本波把每条判据都改写成"从树派生的效果判定"，并**删光两门自写的词法器/括号切分**（`_skip_literal` / `code_only` / `strip_kotlin_comments` / `_close_of` / `_balanced` / `function_text` / `strip_comments` / `_let_block`），统一改调共享三件套（`_kotlin_lexer` 三视图 / `_kotlin_ast` 括号+条件+常量折叠 / `_kotlin_valueflow` 值流+可达性）。

## 2. 见证原文（file / find / replace / 门为何绿）

### 2.1 `model_entry_override_parity_check`（report-A §08）

**08-1 · Arm 1b（persist 调用可达性）· family-hole · FALSIFIED**
`file`: `ui/settings/ModelEntryDetailScreen.kt`
```
find:                     providerRepository.updateEntry(updated)
                          onBack()
replace:                  if (!true) providerRepository.updateEntry(updated)
                          onBack()
```
`why_gate_green`：Arm 1b 只比较 `updateEntry(X)` 的实参名与 `val updated = if (entry.isCustom)` 的绑定名（`updated == updated` ⇒ 绿），**从不判这次调用是否可达**。`if (!true)` 是 `if (false)` 的等价拼写，旧判据的正则只认字面 `false` / `0==1` / `1==0`。

**08-2 · Arm 2b（builder 体完整性）· family-hole · FALSIFIED**
`file`: `ui/settings/ModelEntryOverrideBuilder.kt`
```
find:     val baseInputs = normalizeModalityList(baseModel.inputModalities)
replace:  if (!false) return previous
          val baseInputs = normalizeModalityList(baseModel.inputModalities)
```
`why_gate_green`：`LITERAL_GUARD_RE` 只匹配 `if (true|false)` 与 `true &&/|| …`，`if (!false)` 两处形态都不匹配；`previous.copy(` 锚点仍在体内 ⇒ 绿。实际 `if (!false)` 恒真 ⇒ 每次 Save 立即返回旧值 = 静默 no-op。

**08-3 · Arm 3（toggleNames 合并循环）· family-hole · FALSIFIED**
`file`: `ui/settings/ModelEntryOverrideBuilder.kt`
```
find:     if (name.isNotEmpty() && name !in toggleNames) out.add(name)
replace:  if (false && name.isNotEmpty() && name !in toggleNames) out.add(name)
```
`why_gate_green`：Arm 3 只断言 `name !in toggleNames` / `normalizeModalityList(baseModel.…)` 这些**子串仍在体内**；`false &&` 前缀让条件恒假，但两个子串一个不少。

**08-4 · Arm 4b（funnel 返回值）· dataflow · FALSIFIED**
`file`: `data/repository/ProviderRepository.kt`
```
find:     private fun effectiveEntryModel(entry: ModelEntry): LLMModel = ModelsDevApi.effectiveModel(entry)
replace:  private fun effectiveEntryModel(entry: ModelEntry): LLMModel = ModelsDevApi.effectiveModel(entry).let { entry.model }
```
`why_gate_green`：旧判据对整个函数文本做 `re.search(r"\bModelsDevApi\.effectiveModel\s*\(")` —— 调用在，返回值是什么不问。`.let { entry.model }` 把富化结果丢掉、返回原模型 ⇒ 所有能力决策读未富化模型（39 个内置模型 modality 列表为 null ⇒ 语音输入列表清空）。

**08-5 · Arm 5（ASR resolvedModel）· dataflow · FALSIFIED**
`file`: `speech/ProviderSpeechRecognitionEngine.kt`
```
find:     resolvedModel = ModelsDevApi.effectiveModel(entry),
replace:  resolvedModel = effectiveModel(entry).let { entry.model },
```
`why_gate_green`：旧判据是 `re.match(r"^(?:[A-Za-z_][\w.]*\.)?effective\w*\s*\(", rhs)` —— 只匹配**表达式头部**，`.let { … }` 尾巴不在判据视野内。

**08-6 · Arm 4b 对照（同一臂，已抓）· family-hole · CAUGHT**
```
find:     … = ModelsDevApi.effectiveModel(entry)
replace:  … = if (false) ModelsDevApi.effectiveModel(entry) else entry.model
```
旧判据靠 `LITERAL_GUARD_RE` 抓到它 ⇒ **本波必须保持 CAUGHT**（改造后由"常量分支折叠 + 值来源"双路抓住）。

### 2.2 `memory_gate_provider_fields_check`（report-B）

**05-1 · 判据 1（rollup 早退）· dataflow · FALSIFIED**
`file`: `ui/chat/ChatToolExecutors.kt`
```
find:     … memoryToolDisabledMessage(memoryEnabled)?.let {
              return ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
replace:  … memoryToolDisabledMessage(memoryEnabled)?.let {
              return@let ToolExecutionResult(it, false, toolTitle = MEMORY_DISABLED_TOOL_TITLE)
```
`why_gate_green`：判据是 `elif "return" not in body` —— `return@let` **包含子串 `return`** ⇒ 绿。但 `return@let` 只离开 lambda，执行继续落盘 `MEMORY-ROLLUP.md`。

**05-2 · 判据 1（共享判据作用域）· scope · FALSIFIED**
`file`: `ui/chat/ChatToolExecutors.kt`
```
find:     if (memoryEnabled) null
replace:  if (true || memoryEnabled) null
```
`why_gate_green`：判据 1 的作用域是**两个执行器 + wrapper**，`memoryToolDisabledMessage` 的定义体在作用域外 ⇒ 恒空拒绝也照绿；而它恰是两条执行器唯一的拒绝源。

**05-3 · 判据 2（wrapper 转发）· parser-death · FALSIFIED**
`file`: `ui/chat/ChatPromptAndTools.kt`
```
find:     return com.rikkaminis.app.ui.chat.executeMemoryRollupTool(repo, _memoryEnabled.value) { record ->
replace:  val _anchor = "executeMemoryRollupTool(repo, _memoryEnabled.value)"
          return com.rikkaminis.app.ui.chat.executeMemoryRollupTool(repo, true) { record ->
```
`why_gate_green`：判据用 `_call_text(...)` 从**已剥注释但保留字符串**的视图取"调用文本"再找 `WRAPPER_CALL` 子串 —— 字符串字面量里写一遍调用就满足；真实调用转发的是常量 `true`。

**05-4 · 判据 3（add 读取 payload）· dataflow · FALSIFIED**
`file`: `config/collections/ProvidersCollection.kt`
```
find:     (obj["customUserAgent"] as? ConfigValue.Str)?.value,
replace:  null.also { obj["customUserAgent"] },
```
`why_gate_green`：判据只对**实参文本**做 `obj["customUserAgent"] in call_text` —— 子串在，但值流上 `null.also { … }` 的作用域函数丢弃接收者、返回 `null`。

**05-5 · 判据 4（OAuth 守卫）· family-hole · FALSIFIED**
`file`: `config/collections/ProvidersCollection.kt`
```
find:                 throw ConfigError.InvalidValue(CUSTOM_USER_AGENT_OAUTH_MESSAGE)
replace:              Unit
```
`why_gate_green`：判据只看"守卫条件形态 + 逃逸面字符串存在"，**不看块体是否终止**；`Unit` 让执行落到 `mutate(...)`，OAuth 实例的固定 UA 被覆盖（业务规则中逃逸面正是这条消息）。

**05-6 · 判据 5（summary 投影）· dataflow · FALSIFIED**
`file`: `config/ConfigBuiltins.kt`
```
find:     "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
replace:  "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str("") } ?: ConfigValue.Null),
```
`why_gate_green`：判据只要求 `UA_SUMMARY` 前缀（`"customUserAgent" to (inst.customUserAgent`）在册 ⇒ 投影里传常量也绿，`minis-config get providers` 对每个实例都报空 UA。

## 3. 判据改造（旧形态 → 新形态）

### 3.1 `model_entry_override_parity_check`

| 臂 | 旧形态（锚） | 新形态（效果） |
|---|---|---|
| 1b persist | 实参名 == 绑定名 | ①`path_feasible(code, pos)` 判 `updateEntry(...)` 是否可达（`if (false)` / `if (!true)` / `if (1 == 2)` / `if (false && …)` 一律由 `const_bool` 折叠为不可达）；②`guaranteed_exits(code, 0, first_persist)` 判"persist 之前有没有恒真守卫下的早退"。报行号改为**文件绝对行号**（原先是块内相对行号）。 |
| 2b builder | `LITERAL_GUARD_RE`（只认 `if (true|false)`）+ `BOOL_OP_LITERAL` | `guaranteed_exits(body)`：对体内每个**非标签** `return`/`throw` 求 `enclosing_conditions`，全部条件都折叠成 `true`（或无外层条件）即为"每条路径都退出"；**第一个必退出语句必须就是返回 `previous.copy(...)` 的那条**，否则红。`if (!false) return previous` / `if (1 == 1) return` 全部命中。 |
| 3 merge | `name !in toggleNames` 等子串在体内 | 对每个 `out.add(name)` 求 `enclosing_conditions` + `const_bool`：任一条件折叠为 `false` ⇒ 红（报出那个条件原文）；并额外要求**至少一处** `out.add(name)` 的守卫条件提到 `toggleNames`（防"锚在别处"）。 |
| 4b funnel | 全函数文本 `re.search(effectiveModel\()` | `value_candidates(返回值表达式)`：常量 `if` 分支先折叠（`if (false) A else B` ⇒ 只算 `B`），再 `value_sources` 求值来源（`.let { … }` 取 lambda 尾表达式、`.also{}` 丢弃接收者）；**候选值必须全部**是 `effective*` 调用形态。 |
| 5 ASR | `re.match` 表达式头部 | `expr_end` 取完整右值 → `value_candidates` → 全候选必须是 `effective*` 调用；表达式体/块体两种 `resolvedModel =` 都覆盖。 |
| 结构视图 | `code_only` / `strip_kotlin_comments` / `_balanced` / `_close_of` / `function_text` | 统一 `mask_code`（注释 + 字符串/字符内部一并抹白，长度等长）+ `match_bracket` + `expr_end`；解析失败 `_fatal` ⇒ 违规（fail-closed），不再回落裸文本。 |

### 3.2 `memory_gate_provider_fields_check`

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| W1 rollup 早退 | `"return" in body` | `_nonlocal_return()`：块内每个 `return` 都带 `@label` ⇒ 红（`return@let` / `return@run` 只离开 lambda）。`throw` 仍算终止。 |
| W1' 作用域 | 只扫两个执行器 + wrapper | 追加 `_helper_violations()`：`memoryToolDisabledMessage` **自己的函数体**要扫；把 `memoryEnabled` 代入 `false` 后折叠，若恒取分支的取值是 `null`（或 false 路径无取值）⇒ 红。`if (true \|\| memoryEnabled) null` 折叠为 `true` ⇒ 命中。 |
| W3 wrapper | "调用文本"子串（含字符串） | `mask_code` 视图 + `call_args` 取第二实参 + `value_sources` 求来源，必须含 `_memoryEnabled`（字符串字面量进不了视图，`true` 常量/纯字面量被 `value_sources` 拆出来）。 |
| W4 add 首参 | 实参文本含 `obj["customUserAgent"]` | `value_sources(实参)`（在保留字符串的视图上解析，位置与结构视图对齐）+ 去纯字面量 + `PAYLOAD_UA` 必须在**活来源**里（`null.also{…}` 剥成 `null` ⇒ 红）；另加 `as? X` 类型必须是 `ConfigValue.Str`（类型不匹配静默为 null 的另一形态）。 |
| W5 守卫 | 条件形态 + 逃逸面字符串 | `if_blocks` 取守卫 then 分支区间，要求块内出现 `throw` 或**非标签 return**；`Unit` ⇒ 红（釜底抽薪：条件写得再对，块体不终止就是放行）。 |
| W6 投影 | `UA_SUMMARY` 前缀在册 | `PROJ_RE` 精确到 `ConfigValue.Str(` 的实参（`call_args`），实参不得是纯字面量、且必须承载 `it` / `inst.customUserAgent` ⇒ `ConfigValue.Str("")` 红。 |

**共享层不改**：本波发现 `_kotlin_valueflow.value_sources` 对 `if (c) A else B` **不分叉**（`rest` 以 `then` 开头时永远匹配不到 `else`，整体返回全表达式）——本波在门内用 `if_blocks` + `const_bool` 先折叠分支再调 `value_sources` 绕过，**共享层的修法交由主会话**（见 §6 R1）。

## 4. 四层证据

### L1 — 本门真树 + 自测（有牙）
```
$ python3 scripts/scan/model_entry_override_parity_check.py .
model_entry_override_parity_check: OK (7 arms, 0 violations)          # rc=0
$ python3 scripts/scan/model_entry_override_parity_check.py --selftest
MODEL_ENTRY_OVERRIDE_SELFTEST=PASS (23 arms)                          # 18 → 23

$ python3 scripts/scan/memory_gate_provider_fields_check.py .
memory_gate_provider_fields_check: clean                              # rc=0
$ python3 scripts/scan/memory_gate_provider_fields_check.py --selftest
MEMORY_GATE_PROVIDER_FIELDS_SELFTEST=PASS (19 arms)                   # 12 → 19
```
新增臂（每臂 = 一条见证的负向夹具，断言"恰好红在该臂"）：`me-dead-guard-not-true` / `ob-early-return-not-false` / `ob-dead-merge-prefix` / `pr-funnel-discards-tail` / `asr-resolved-discards-tail` / `rollup return@let` / `gate helper always null` / `wrapper string anchor` / `add discard-scope UA` / `add wrong cast` / `writer Unit body` / `summary projects empty Str`。

### L2 — 邻门（12 道，逐门直跑，全 rc=0）
```
four_way_sync_check 0 · provider_type_registry_check 0 · models_provider_field_check 0
modeluse_proto_single_source_check 0 · provider_write_rules_check 0 · provider_rpc_symmetry_check 0
provider_boundary_guard 0 · provider_import_id_roundtrip_check 0 (13 arms) · memory_ui_wiring_check 0
collection_copy_on_mutate_check 0 · envvar_config_group_check 0 · (本波两门各 0)
```

### L3 — 反攻（原始见证逐条重放，`verify_batch5.py`）
```
verify_batch5.py --witnesses witnesses-A.json --only model_entry_override_parity_check.py
  6/6 CAUGHT   (5 原 BLIND → CAUGHT，1 原 CAUGHT 保持 CAUGHT)
verify_batch5.py --witnesses witnesses-B.json --only memory_gate_provider_fields_check.py
  6/6 CAUGHT   (6 原 BLIND → CAUGHT)
restore=True 12/12（工作树零残留；`git status --porcelain src/` 空）
```
命中臂与见证声明**逐条对齐**（08-1→arm 1、08-2→arm 2、08-3→arm 3、08-4→arm 4、08-5→arm 5、08-6→arm 4；05-1..05-6→各自判据），报错消息给出**新判据的证据**（例如 `updateEntry(...) is guarded by a condition that folds to false ('!true')`），不是泛化 FAIL。

### L4 — 全量（`scan.sh` 112 / `test_scan.py` ALL 344）
施工期本机 13 支会话并行，`bash scan.sh` 与 `gate_run.sh --full` 均被沙箱 ENOSYS 打死（registry S28：`OSError: [Errno 38] Function not implemented`，两次分别停在 16/112 与 24/112 门，`src/` 零残留）。按 S28 登记的纪律（2026-10-18 变体 ⑤：施工期本地只跑本门+邻门+变异负控，全量证据取分支 CI 日志），**L4 以分支 CI job 日志为准** —— 见 §5。

## 5. CI

- run / job / attempt：**待回填（CI 出结论后回填真实数字，不预写占位值）**
- head_sha：**待回填**
- 读数核对项：`Summary: 112 passed, 0 failed` 带框线恰 1 条 · 门头编号 112 互异 · `ALL 344 CASES PASS` 恰 1 条 · ` e: ` 0 行 · 含 `FAILED` 的行逐条确认是 PASSED 用例名子串 · 26 步 not_ok 仅 `Publish` skipped · head_sha 与本地 HEAD 逐字符一致 · 本支两门 selftest 收尾标记在日志中出现。

## 6. 残留（R1..R6）

**R1 —— 共享 `value_sources` 不折叠 `if/else`（升级点，非本门缺陷）**
- 触发条件：主会话统一下一轮收紧共享层，或第三处门需要 `if (c) A else B` 的值来源。
- 触发后方案：`_kotlin_valueflow.value_sources` 的 `if` 分叉改成 `if_blocks` + `const_bool` 折叠（本门 `value_candidates` 即参考实现，可整体上移）。
- 搁置理由：共享层冻结（本波硬约束 #2），门内 20 行局部实现已覆盖两门全部见证。

**R2 —— `guaranteed_exits` / `_nonlocal_return` 不区分"嵌套局部 fun"**
- 触发条件：`buildModelEntryOverrides` / Save 块里出现本地函数且其体内含 `return`。
- 触发后方案：给 `_kotlin_ast` 加 `local_fun_spans(text)`，退出收集时跳过落在局部函数区间内的 return。
- 搁置理由：当前两处作用域内零局部函数；Kotlin 语义上**裸 return 在 inline lambda 里确实非局部退出**，方向安全（宁可判红）。

**R3 —— `enclosing_conditions` 只建模 `if`/`when`**
- 触发条件：出现 `try/catch`、`while`、`?.let { }` 内联块包裹的 persist 站点。
- 触发后方案：`path_feasible` 扩 `catch`/循环区间（`when_branches` 已覆盖 `when`）。
- 搁置理由：本波 12 条见证的缺陷站点全在 `if`/`when` 下；其余形态当前不可达。

**R4 —— 门内 `_branch_value` 用保留字符串视图解析分支取值**
- 触发条件：分支取值的字符串里含裸括号（`"{"` / `")"`）。
- 触发后方案：`_kotlin_ast._branch_value` 上移到共享层，内部按 `mask_code` 定结构、按 `mask_comments` 取值。
- 搁置理由：两门作用域内分支字面量均为自然语言/修长文案，括号风险实测为零；失败方向是"多报"不是"漏报"。

**R5 —— 报行号粒度**
- `model_entry_override_parity_check` 的 Arm 2b/3 部分消息仍报 `:0`（函数级判定），Arm 1b 已改绝对行号。
- 触发条件：下一波若需要精确定位到行做自动修。
- 触发后方案：`check_builder_derives` 用 `exits[0]`（已有绝对位置）替换 0 行号。
- 搁置理由：修复者拿消息里的条件原文 + 函数名足以定位；不构成误判。

**R6 —— 本波两门在 `test_scan.py` 的标记断言仍是旧臂数**
- 现状：`test_scan.py` 断言的是子串 `MODEL_ENTRY_OVERRIDE_SELFTEST=PASS` / `MEMORY_GATE_PROVIDER_FIELDS_SELFTEST=PASS`（`marker in out`），臂数改为 `(23 arms)` / `(19 arms)` 后断言仍成立（**无需改冻结面**）。
- 触发条件：主会话收口时若希望"臂数变化必须显式改门"，可把标记串升级成带臂数。
- 触发后方案：`test_scan.py` 两行改为 `SELFTEST=PASS (23 arms)` / `(19 arms)`。
- 搁置理由：`test_scan.py` 本波冻结；且臂数已由门自己的 `--selftest` 输出可核对。

## 7. `test_scan.py` 注册项（交主会话统一处理）

本波**未新增门**，冻结面零触碰，已注册的两条标记断言无需改动即可通过（见 R6）：

| script | 标记串（现有断言，子串匹配） | 本波臂数 | 真树 argv |
|---|---|---|---|
| `model_entry_override_parity_check` | `MODEL_ENTRY_OVERRIDE_SELFTEST=PASS` | 18 → **23** | `(REPO_ROOT,)` |
| `memory_gate_provider_fields_check` | `MEMORY_GATE_PROVIDER_FIELDS_SELFTEST=PASS` | 12 → **19** | `(REPO_ROOT,)` |

可选（非必须）：若主会话要把"臂数"也钉进 CI，把上表两行标记串追加 ` (23 arms)` / ` (19 arms)`。

## 8. 复现

```sh
cd <repo>
# 本门真树 + 自测（有牙）
python3 scripts/scan/model_entry_override_parity_check.py .
python3 scripts/scan/model_entry_override_parity_check.py --selftest
python3 scripts/scan/memory_gate_provider_fields_check.py .
python3 scripts/scan/memory_gate_provider_fields_check.py --selftest

# 原始见证反攻（apply → 跑门 → restore → sha256 校验）
python3 <wave>/verify_batch5.py --witnesses <wave>/witnesses-A.json --repo . \
  --mode full --only model_entry_override_parity_check.py --out probe/t07-A.json
python3 <wave>/verify_batch5.py --witnesses <wave>/witnesses-B.json --repo . \
  --mode full --only memory_gate_provider_fields_check.py --out probe/t07-B.json

# 全量（并发施工期请走 gate_run.sh，避免 S28 假红）
sh /var/minis/shared/tools/gate_run.sh --full --repo .
```
