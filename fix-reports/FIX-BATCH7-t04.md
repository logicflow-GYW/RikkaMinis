# FIX-BATCH7-t04 — provider_ua_parity + auto_approve_bypass：12 条见证全翻红

- 分支 `fix/b7-ua-autoapprove`（基线 `base/batch5-shared` @ `e145ec961`）
- 门提交 `579a99d86`（2 文件 +992/−488，**生产 `.kt` 0 行**）
- CI **#2712** / run `40265282871` / job `121386229450` / attempt 1 **success**
- 停止节点：分支 CI 绿 → 停分支，未合并 main

## ① 门与轴

| 门 | 见证 | 轴分布 | 判据 | 自测臂 |
|---|---|---|---|---|
| `provider_ua_parity_check.py` | 6（03-1…03-6） | dataflow 4 · order 1 · anchor 1 | A 工厂 UA 流 · B Gemini 覆盖站点 · C 工具计数派生 · V1/V2/V3 语音 · D1/D2a/D2b 单一密钥源 · D3 适配器 | 56 arms（本波 +6） |
| `auto_approve_bypass_check.py` | 6（02-1…02-6） | dataflow 2 · order 2 · spelling 1 · family-hole 1 · parser-death 1 | C1 策略咨询 · C2 单一确认点 · C3/C5 getLevel 返回值 · C4 注册表无自授权 | 14 arms / 12 violating（原 6 臂） |

两门同族失效：**判据锚「某个 token 存在」，见证动「该 token 的效果」**——把调用留着、把返回值/可达性/值流换掉即可过门。

## ② 见证原文（12 条，逐条 file / find / replace / why_gate_green）

### 03-1（dataflow，ISSUE-04a 工厂臂丢掉自定义 UA）
- file `src/android/app/src/main/java/com/rikkaminis/app/provider/ProviderFactory.kt`
- find `ProviderType.openRouter -> { … OpenAIProvider( … customUserAgent = instance.customUserAgent,`
- replace 同处改为 `customUserAgent = instance.customUserAgent.let { null },`
- why_gate_green 旧 `check_factory` 用 `UA_FLOW_RE` 取表达式后交 `ua_value_flows`，而后者第一句是 `if UA_SOURCE_TOKEN in expr: return True`（`UA_SOURCE_TOKEN = ".customUserAgent"`）——只要子串在，后面跟什么转换都算流通。

### 03-2（order，ISSUE-04b 二次覆盖抹掉构造器 UA）
- file `.../provider/gemini/GeminiProvider.kt`
- find `.applyUserAgentOverride(customUserAgent)\n            .build()`
- replace 中间插入 `.applyUserAgentOverride("")`（保留原调用，计数仍 ≥2）
- why_gate_green 旧判据只有「`NULL_OVERRIDE = .applyUserAgentOverride(null)` 不在源码」+「`.applyUserAgentOverride(customUserAgent)` 出现 ≥2 次」两点，空串既不是 `null` 也不减计数。

### 03-3（dataflow，ISSUE-04c 工具计数被恒置 0）
- file `.../provider/gemini/GeminiProvider.kt`
- find `private fun toolCallActivityChars(name: String, argsJson: String): Int =\n        name.length + argsJson.length`
- replace 函数体改为 `= 0`（累加行与 `geminiEofTerminal(...)` 接线全保留）
- why_gate_green 旧判据只查累加 token 存在、`geminiEofTerminal` 实参名、`shouldFlagEofTruncated` 里有 `toolChars > 0`，**从不看辅助函数的函数体**。

### 03-4（family-hole，ISSUE-05 V3 注入被死分支包裹）
- file `.../provider/voice/VoiceProviderFactory.kt`
- find `build(instance, rawApiKey).also { it?.customUserAgent = instance.customUserAgent }`
- replace `.also { if (false) it?.customUserAgent = instance.customUserAgent }`
- why_gate_green 旧判据 `INJECTION_RE.search(body)` 命中（赋值在块内），且遍历赋值时 RHS 确实流自 `instance.customUserAgent`；**从不判该语句是否可达**。

### 03-5（dataflow，ISSUE-06 D2b 委派被硬编码 Bearer 替换）
- file `.../provider/openai/OpenAIModelsApi.kt`
- find `.url(url)\n            .applyOpenAIKeyAuth(azureMode, apiKey)`
- replace `.url(url)\n            .header("Authorization", "Bearer $apiKey")`
- why_gate_green 旧 D2b 只查 `azureMode: Boolean = false` 声明 + `buildModelsRequest(...)`/`modelsCacheKey(...)` 的调用实参含 `azureMode`，**根本没查 `buildModelsRequest` 体内是否委托 `applyOpenAIKeyAuth`**；`header("Authorization", …)` 也不在 `API_KEY_DECISION_FORMS`（只认 `header("api-key"`），D2a 亦不报。

### 03-6（anchor，ISSUE-06 D3 azureMode 实参被 `&& false` 抹平）
- file `.../provider/ModelListProviderAdapters.kt`
- find `            azureMode = instance.azureMode,`
- replace `            azureMode = instance.azureMode && false,`
- why_gate_green 旧判据 `re.search(r"azureMode\s*=\s*([A-Za-z0-9_.]+)")` 遇空格/`&` 即止，恰好截出 `instance.azureMode`，白名单通过并 `forwarded_from_instance += 1`。

### 02-1（dataflow，C1 策略判定值被丢弃）
- file `.../config/ConfigBridge.kt`，`performWriteBatch`
- find `val autoSkip = !skipConfirmation &&\n            ConfigAutoApprovePolicy.shouldSkip(… )`
- replace 把 `shouldSkip(...)` 赋给未使用的 `val _ignoredPolicy`，`val autoSkip = !skipConfirmation`
- why_gate_green C1 只查 `"ConfigAutoApprovePolicy.shouldSkip" not in batch_body`——调用还在函数体里，绿。

### 02-2（spelling，C2 跨行链式调用逃过单行子串）
- file `.../debug/DebugRPCHandler.kt`
- find `                        skipConfirmation = true,`
- replace 改为跨行 `ConfigConfirmationGate\n    .requestConfirmation(PendingConfigChange(emptyList(), null)) != null,`
- why_gate_green C2 用精确子串 `"ConfigConfirmationGate.requestConfirmation("`，换行后该连续子串不存在，调用点计数仍为 1。

### 02-3（dataflow，C3/C5 尾部 `.let` 替换策略返回值）
- file `.../offload/OffloadPermissionManager.kt`
- find `return OffloadPermissionLevelPolicy.resolve( … )`
- replace 闭合括号后追加 `.let { PermissionLevel.BYPASS }`
- why_gate_green 旧判据 `head = level_body[statement_start(...):].lstrip()`，`if RE_RETURN_HEAD.match(head): continue`——语句只要以 `return` 开头就算「已消费」，不看表达式尾部。

### 02-4（order，C3/C5 提前返回使 resolve 变死码）
- file `.../offload/OffloadPermissionManager.kt`
- find `val stored = prefs.getString("level_$toolName", null)?.let { … }`
- replace 其后插入 `if (toolName.isNotEmpty()) return PermissionLevel.BYPASS`（原 `return …resolve(...)` 原封不动）
- why_gate_green 旧判据只查每个 `resolve(` 调用点所在语句是否以 `return` 开头，**不看其前方是否有恒真早退**。

### 02-5（parser-death，C4 用 raw string 里的 `"` 骗过自写 strip）
- file `.../config/ConfigBuiltins.kt`
- find `internal object ConfigBuiltins {`
- replace 插入 `private val stealthOpen = """ "/* """` + `val autoApproveBackdoor: Boolean get() = AutoApproveStore.isEnabled` + `private val stealthClose = """ */" """`
- why_gate_green 自写 `strip_kotlin_comments` 把每个 `"` 当单字符引号开关：raw string 内合法第 4 个 `"` 让它翻回「不在字符串」，紧随的 `/*` 开真块注释，把中间的 `autoApproveBackdoor` 定义整段抹成空格，C4 绿。

### 02-6（family-hole，C2 确认调用被套进 `if (false)`）
- file `.../config/ConfigBridge.kt`，`performWriteBatch` 的 `else` 分支
- find `else -> { val pending = …; ConfigConfirmationGate.requestConfirmation(pending) … }`
- replace `if (false) ConfigConfirmationGate.requestConfirmation(pending)`，随后直接 `ConfirmOutcome.Approved(resolvedItems)`
- why_gate_green C2 只数 `requestConfirmation(` 出现次数 == 1，不判这唯一一次是否执行、是否决定 `outcome`。

## ③ 判据改造（旧形态 → 新形态）

底层全部改走共享原语（`_kotlin_lexer` 的 `analyse/code_view/literal_view/match_bracket`、`_kotlin_ast` 的 `function_body/split_top`、`_kotlin_valueflow` 的 `value_sources/path_feasible/override_sites/expr_end/stmt_spans`）；自写 `strip_comments` / `strip_kotlin_comments` / `blank_strings` / 手写括号配平 / 单行正则白名单**全部删除**。

### provider_ua_parity_check.py

| 判据 | 旧形态 | 新形态（本波） |
|---|---|---|
| 04a 工厂 UA | `UA_FLOW_RE` 取式子串 + `".customUserAgent" in expr` | `value_sources(expr)` 必须**追溯到** `instance.customUserAgent`（`peel_discarding` 后的值），`.let { null }` / `.run { "" }` 一律判红 |
| 04b Gemini 覆盖站点 | 只查 `(null)` 不存在 + `(customUserAgent)` ≥2 次 | `override_sites(body)` 逐个站点查实参是否派生自构造器值（空串/字面量/null 全红），**全部站点**都要过 |
| 04c 工具计数 | 只查累加 token 与 `toolChars > 0` | `toolCallActivityChars` 必须有声明，且函数体**返回值派生自** `name`/`argsJson`（恒 0 红） |
| 05 V1 | 查 `executeRequest` 里有 `.applyUserAgentOverride(customUserAgent)` | 同左，但站点实参走值流（与 04b 共用 `override_sites`） |
| 05 V2 | （本轮新增严格化）同样只查 token | Xunfei WS 请求区间内每个 override 站点实参走值流 |
| 05 V3 | `INJECTION_RE` 命中 + RHS 流自 instance | `path_feasible` 判**可达**；可达赋值里必须有一条 RHS 流自 instance，且不得被后续裸赋 clobber |
| 06 D2b | 只查声明默认值 + 调用实参含 `azureMode` | `buildModelsRequest` 函数体必须**委托** `applyOpenAIKeyAuth`（可达），实参含 `azureMode` 与 `apiKey`，且形参未丢 |
| 06 D3 | `re.search(r"azureMode\s*=\s*([A-Za-z0-9_.]+)")` | `_named_arg` 取**完整右值**（到顶层逗号）+ `_azure_value` 三值判定（`instance` / `false` / 脏值红）：`instance.azureMode && false` 红 |
| D2a 扫描视图 | 自写 `strip_comments` | `literal_of`（字符串字面量保留）——`header("api-key"` 是字符串事实 |
| 适配器实参 | 手写括号配平 | `args_of`（解析失败 → `unbalanced` 红） |

### auto_approve_bypass_check.py

| 判据 | 旧形态 | 新形态（本波） |
|---|---|---|
| C1 策略咨询 | 子串「`shouldSkip` 在函数体内」 | `shouldSkip(...)` 的**判定值必须绑定**到 `autoSkip`（`value_sources` 溯源）；另查语句可达（死分支里的咨询红）、`autoSkip` 未被后置裸赋覆盖 |
| C2 单一确认点 | 精确子串计数 == 1 | 用 `call_args` 做**结构计数**（跨行、`Gate\n  .requestConfirmation(`、别名接收者都算）；同一处调用的**可达性**（`path_feasible`）与「决定 `outcome`」两点同时判 |
| C3/C5 getLevel | 语句首正则 `RE_RETURN_HEAD` | `stmt_spans` 语句级：`resolve(...)` 所在语句必须**就是**返回值（尾部 `.let{}` 替换红）；且函数体里第一条必退出语句不得在 `resolve` 之前（提前 `return BYPASS` 红） |
| C4 注册表 | 自写 `strip_kotlin_comments` | `literal_of`（共享词法器，raw string 内的 `"` 不再破坏状态机） |

## ④ 四层证据

**L0 共享原语**（基线自带，未改）：`KOTLIN_LEXER_SELFTEST=PASS (20 arms)` · `KOTLIN_AST_SELFTEST=PASS (32 arms)` · `KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)`。

**L1 门自测 + 真树**
- `PROVIDER_UA_PARITY_SELFTEST=PASS (56 arms)`（本波 +6 条新臂；每条新臂断言**命中自己的文案**，撞错判据 = FAIL）
- `AUTO_APPROVE_BYPASS_SELFTEST: PASS (14 arms, 12 violating)`（基线 6 臂 SELFTEST_TAGS 形态 → 本波 14 臂，逐臂断言文案）
- 真树：`PASS provider_ua_parity_check (...)` rc=0 · `AUTO_APPROVE_BYPASS_CHECK: OK (all 5 criteria hold)` rc=0

**L2 反攻（硬验收，现成驱动 `verify_batch5.py --mode full`）**

```
attempt verdict gate_rc restored
03-1..03-6  CAUGHT  1  True      (provider_ua_parity_check.py)
02-1..02-6  CAUGHT  1  True      (auto_approve_bypass_check.py)
```

12/12 `CAUGHT`，12/12 `restored=True`，事后 `git status --porcelain src/` 为空；每条红的文案都落在**该见证对应的判据**上（03-1「the instance override never arrives」· 03-2「does not carry the constructor override」· 03-3「folds to a constant」· 03-4「never-executed branch」· 03-5「no longer delegates to applyOpenAIKeyAuth」· 03-6「neither `instance.azureMode` nor the explicit `false`」· 02-1…02-6 各自命中文案）。原始输出 `batch7/probe/t04-{provider_ua_parity_check,auto_approve_bypass_check}.json`。

**L3 邻门**（18/18 rc=0，逐门独立进程）
- UA 侧 9：`provider_rpc_symmetry` · `provider_write_rules` · `provider_type_registry` · `provider_models_cache_wiring` · `provider_boundary_guard` · `voice_cancel_guard` · `speech_active_engine` · `gemini_base_version` · `anthropic_thinking_budget`
- auto_approve 侧 9：`offload_permission_reset_sync` · `offload_cli_args` · `offload_host_path` · `envvar_config_group` · `memory_ui_wiring` · `discarded_return_guard` · `four_way_sync` · `readonly_mount_write_guard` · `models_provider_field`

**L4 全量（本地 + CI 双侧）**
- 本地 `bash scripts/scan/scan.sh`：`Summary: 112 passed, 0 failed`（6m19s）
- 本地 `test_scan.py`：**46 个夹具函数全覆盖，344 cases / 0 fail**。整跑在本机必被沙箱 stall-guard 打死（父进程只等不打字），故用分块驱动（`probe/t04_testscan_chunks.py` 6 函数/进程 + `probe/t04_testscan_rest.py` 1 对/进程 + 超时与 ENOSYS 退避）：179（chunk1-5）+16+30+38+30+10+6+11+4+20 = **344**，逐段 `fail+=0`。
- 冻结面零触碰：`scan.sh` / `test_scan.py` / `_kotlin_*.py` 未改；门 112 / 夹具 344 不变；生产 `.kt` 0 行（`git diff --name-only e145ec961..HEAD` = 2 个门脚本）。

## ⑤ CI（#2712，回填实测）

| 项 | 读数 |
|---|---|
| run / job / attempt | `40265282871` / `121386229450` / **1** |
| conclusion | **success** |
| head_sha | `579a99d868172edab6e23cb429123a37c37b185b` = 本地 `git rev-parse HEAD` 逐字符一致 |
| 时长 | 2026-10-26T03:41:11Z → 03:56:46Z（15m35s） |
| 26 步 | 唯一非 success = step 23 `Publish to Releases`（skipped，main 门控） |
| 日志 | 1,486,551 B，**0 NUL** |
| `Summary: 112 passed, 0 failed` | 恰 **1** 次 |
| `ALL 344 CASES PASS` | 恰 **1** 次 |
| 门头 `[N/112]` | **112 个互异**编号，各 1 次 |
| `^ e: ` 编译错误 | **0** |
| `FAILED` 行 | 6 行，逐条确认**全是 `PASSED` 用例名的子串**，无真失败 |
| `PROVIDER_UA_PARITY_SELFTEST=PASS` | 恰 **1**（56 臂在 CI 有见证） |
| `AUTO_APPROVE_BYPASS_SELFTEST: PASS` | 恰 **1**（14 臂在 CI 有见证） |
| 真树行 | `PASS provider_ua_parity_check` 1 · `AUTO_APPROVE_BYPASS_CHECK: OK` 1 |

CI 绿后 `git diff --name-only 579a99d86..HEAD -- . ':(exclude)fix-reports'` = **0 行**（源码树与 CI 绿树等价，仅报告回填）。

## ⑥ 残留（P2，各带触发条件 / 触发方案 / 搁置理由）

- **R1 值流只追一跳局部**（`val ua = instance.customUserAgent; … ua.let { null }` 两跳抹除判不出）
  触发条件：出现「先绑局部再抹除」的真实写法（当前生产无）；触发方案：`ua_arg_carries` 里对 `_ident_values` 递归到 2–3 跳；搁置理由：共享层 `value_sources` 一跳是**契约**，改它会波及 24 门，须由主会话统一定；本波 fail-closed 方向安全（判红而非放过）。
- **R2 `toolCallActivityChars` 判据按函数名 + 形参名派生**
  触发条件：函数改名或形参改 `n/a`；触发方案：改成「被 `toolChars +=` 调用的那个函数」反向定位；搁置理由：当前签名是 ISSUE-04c 的文档锚点，改名属刻意编辑（判红合理）。
- **R3 Voice V2 区间取 `url(signedUrl)` → `.build()`**
  触发条件：WS 请求改用变量拼装（`.url(wsUrl)`）；触发方案：改按 `httpClient.newWebSocket(` 的整条语句定界；搁置理由：现仓唯一一处，判红即提醒同步修门。
- **R4 V3 只判「可达赋值里存在一条正确注入」**
  触发条件：`if (x) correct else clobber` 这类双路径写法；触发方案：按分支合并（两臂都必须正确）；搁置理由：现仓无此形态，且 `clobber` 检查已覆盖同块后续裸赋。
- **R5 D2b 只查委派存在 + 两参透传，不查 header 名**
  触发条件：`applyOpenAIKeyAuth` 内部被改坏（那是 auth 门的事）；触发方案：跨门联合判据（本门 D2a 已管单一来源）；搁置理由：门职责边界（radius 纪律）。
- **R6 C2「决定 outcome」用语句级近似**
  触发条件：把调用结果存变量后再丢弃（`val r = requestConfirmation(...); Approved(...)`）；触发方案：`value_sources` 追到 `outcome` 绑定；搁置理由：02-3/02-6 已覆盖两条主要形态，同族第 3 形态未出现。

## ⑦ `test_scan.py` 注册项（**已注册，标记串未变，本支无需改动**）

| 门 | 已有注册（行号） | 标记串 | 本波臂数 |
|---|---|---|---|
| `auto_approve_bypass_check` | :2409（`test_auto_approve_gates`） | `AUTO_APPROVE_BYPASS_SELFTEST: PASS` | 14 arms（输出尾部另带 `(14 arms, 12 violating)`，不改标记） |
| `provider_ua_parity_check` | :2761（`test_da1018_gates`） | `PROVIDER_UA_PARITY_SELFTEST=PASS` | 56 arms（输出尾部另带 `(56 arms)`） |

两处标记在两门新输出里**逐字符保留**（新臂数只追加在标记之后，避免冻结断言失效）。本地 `test_auto_approve_gates` / `test_da1018_gates` 已实跑绿，CI 日志两标记各 1 次即证。

## ⑧ 复现命令

```sh
# 门自测 + 真树（任一门）
python3 scripts/scan/provider_ua_parity_check.py --selftest   # 56 arms
python3 scripts/scan/provider_ua_parity_check.py .
python3 scripts/scan/auto_approve_bypass_check.py --selftest  # 14 arms
python3 scripts/scan/auto_approve_bypass_check.py .

# 见证反攻（现成驱动，禁自造跑法）
python3 <batch7>/verify_batch5.py --witnesses <batch7>/witnesses-A.json --repo . --mode full \
  --only provider_ua_parity_check.py --out probe/t04-provider_ua_parity_check.json
python3 <batch7>/verify_batch5.py --witnesses <batch7>/witnesses-B.json --repo . --mode full \
  --only auto_approve_bypass_check.py --out probe/t04-auto_approve_bypass_check.json

# 全量（本机须分块：整跑会被 stall-guard 打死）
bash scripts/scan/scan.sh                                    # 112 / 0
python3 <batch7>/probe/t04_testscan_chunks.py . 6            # 1-30
python3 <batch7>/probe/t04_testscan_rest.py . 30 2           # 30-45（ENOSYS 自退避）
```
