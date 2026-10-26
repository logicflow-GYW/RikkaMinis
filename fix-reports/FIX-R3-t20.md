# FIX-R3-t20 — 第三轮 B 波：三门判据从「名字/拼写」改到「值真的流到下一跳」

- 基线：`main` @ `f0f14d46d`
- 分支：`fix/r3-provider-persist`
- 工作树：`round3b/wt/t20`
- 生产 `.kt` 改动：**0 行**（本支只改门脚本 + 本报告）
- 冻结面：`scripts/scan/scan.sh`、`scripts/scan/test_scan.py`、`scripts/scan/_kotlin_*.py` **零触碰**
- 门数 / 夹具数：112 / 344 **不变**（不新增门，只加判据与自证臂）

## §1 门

| 门 | 角色 | 本次新增判据 |
|---|---|---|
| `provider_write_rules_check.py` | provider 写入面的 2x2 守卫矩阵 + 读取侧归一化 | W13 / W14 / W15 |
| `provider_models_cache_wiring_check.py` | 7 天磁盘缓存的端到端可达性 | 3 条（实参转发 / TTL 决策 / 返回值） |
| `compact_entry_pin_migration_check.py` | compact pin / 群组引用 / agent-loop 绑定的 prune 级联 | Arm 10 / 11 / 12 |

三门同扫 `ProviderConfig.kt`、`ProviderRepository.kt`、`ProvidersCollection.kt`、`ProviderModelsCache.kt`（本轮四条生产文件全部落在「两门以上共扫」的缝靶区）。

## §2 轴

三条见证走的是同一个盲区，但分三种轴：

- **dual-gate bypass（缝）**：一条补丁让两门各自的判据同时绿。w39/w40 落在 `provider_write_rules × xproc_boundary_check` 之间；w36/w12/w13 落在 `compact_entry_pin_migration × provider_models_cache_wiring` 之间。
- **symmetric（对称轴）**：守卫只应用在一侧。w41 = 2x2 矩阵（两个写入面 × 两个字段）× 第四象限缺失；w37 = TTL 判断式被折成恒真。
- **legacy-axis（老轴复现）**：w38 = 返回值被替换成常量空集合（与历史「读到了但不返回」同族）。

共性判据失效形态：**锚「某个名字出现过」而不是「这个值真的流到下一跳」**。
具体表现：判据只查 `compactModelEntryId = migrated` 这个拼写（不问 `migrated` 从哪来）、只查 `customUserAgentWritable(` 在别处存在（不问本写入面有没有）、只查 `fetchModels(` 末参是 `context`（不问另外两个实参是不是常量）、只查 `cache.load(` 被调用（不问 `load` 里 TTL 与返回值是否还是决策）。

## §3 见证重放表

修前（`probe/t20-pre.json`）**9/9 BLIND_CONFIRMED**，每条 restore=True、`git status` 全程为空；修后（`probe/t20-post.json`）**9/9 CAUGHT**，每条红在自己那条新判据上（归因见 §4 末列）。

| # | 类型 | 补丁（最小编辑） | 门 | 修前 rc | 修后 rc | 修后红的判据 |
|---|---|---|---|---|---|---|
| w39 | dual | `ProviderConfig.kt` `effectiveBaseURL`：`if (appendV1Suffix && …)` → `if (!appendV1Suffix && …)` | write_rules + xproc | 0 / 0 **BLIND** | 1 / 0 **CAUGHT** | W13「appends `/v1` when `appendV1Suffix` is FALSE」 |
| w40 | dual | 同上文件：`supportsCustomThinkingRules` getter 整块 → `get() = false` | write_rules + xproc | 0 / 0 **BLIND** | 1 / 0 **CAUGHT** | W14「returns the CONSTANT `false`」 |
| w41 | symmetric | `ProvidersCollection.kt` `customUserAgent` writer 删掉 `customUserAgentWritable` 守卫 | write_rules | 0 **BLIND** | 1 **CAUGHT** | W15「does not route through customUserAgentWritable(」 |
| w36 | dual | `ProviderRepository.kt` `fetchModels(instance, apiKey, isThirdParty, forceRefresh, context)` → `(instance, "", isThirdParty, true, context)` | models_cache + compact | 0 / 0 **BLIND** | 1 / 0 **CAUGHT** | cache「`apiKey` is the constant `""`」+「`forceRefresh` is the constant `true`」 |
| w37 | symmetric | `ProviderModelsCache.kt` TTL：`> ttlMs) return null` → `>= 0L) return null` | models_cache | 0 **BLIND** | 1 **CAUGHT** | cache「compares the entry age against the constant `0L`」 |
| w38 | legacy | 同文件：`return entry.models` → `return emptyList()` | models_cache | 0 **BLIND** | 1 **CAUGHT** | cache「`load` never returns the value it read」 |
| w12 | dual | `ProviderRepository.kt` `uuid = prior?.id ?: java.util.UUID.randomUUID()…` → `uuid = java.util.UUID.randomUUID()…` | compact + models_cache | 0 / 0 **BLIND** | 1 / 0 **CAUGHT** | compact Arm 10「prior entry id is not carried forward」 |
| w13 | dual | 同文件 `replaceEntries(instance.id, models)` → `replaceEntries(instance.id, emptyList())` | compact + models_cache | 0 / 0 **BLIND** | 1 / 1 **CAUGHT**（双门同红） | compact Arm 11「constant EMPTY list」＋ cache「fetch result `models` never reaches replaceEntries(」 |
| w14 | symmetric（**改造重放**） | 见下「漂移」说明：`)?.let { migrated -> AgentRuntimeLimitsPrefs.save(…= migrated)` → 把 lambda 参数改 `_unused`，块内 `val migrated = AgentRuntimeLimitsPrefs.compactModelEntryId()` | compact | 0 **BLIND** | 1 **CAUGHT** | compact Arm 12「written from `migrated`, which is bound to `…compactModelEntryId()`」 |

### w14 的漂移（诚实记录）

任务书描述的是 `ProviderRepository.kt:1322` 把 `setCompactModelEntryId(nextCompactPin)` 改成 `setCompactModelEntryId(currentPin)`。**该符号在本基线树不存在**（`grep -rn setCompactModelEntryId src/` = 0 命中；今天的写入形态是 `AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = migrated)`，行号 1674–1679）。按任务书「不许编造编辑」的要求，我：

1. 把**字面形态**标为 NOT_REPRODUCIBLE（函数不存在）；
2. 沿**同一条轴**在今天的数据流上重放等价缺陷——保留门钉住的全部三个锚（`decideCompactPinMigration(`、`compactModelEntryId()` 读取、`compactModelEntryId = migrated` 拼写），只把 `migrated` 的来源换成旧 pin。这正是见证原意：**写入者拿到的是旧值**。判为 BLIND_CONFIRMED，因此修判据（Arm 12）。

### 双门未同红的说明（缝的所有权）

| 见证 | 未红的门 | 为什么不归它 |
|---|---|---|
| w39 / w40 | `xproc_boundary_check` | 该门判的是**跨进程序列化面**（`ProviderConfig` 构造参数与 payload 字段是否一一对应）。计算属性 getter 的分支极性与能力答案不在序列化面上——它红了才叫越界。所有者 = `provider_write_rules` W13/W14。 |
| w36 | `compact_entry_pin_migration_check` | 该门判 pin 的存活链（prune 级联 / id 延续 / 交接）。`fetchModels` 的 `apiKey`、`forceRefresh` 两个实参根本不在这条链上。所有者 = `provider_models_cache_wiring_check`。 |
| w12 | `provider_models_cache_wiring_check` | entry id 延续完全发生在 `replaceEntries` 内部，是 compact 门 prune 级联的**前件**。缓存门判的是读写链路，不判 entry 身份。所有者 = compact Arm 10。 |
| w13 | —（两门都红） | compact 判「交接值不能是可证空的常量」，cache 判「取回的值必须被 store 消费」——同一补丁从两个不同侧面各咬一口。 |

## §4 修法

原则：**判据从「名字/拼写出现过」搬到「值/决策真的发生且流到下一跳」**；一律走共享原语（`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`），不新增自写词法器、不新增花括号计数。

### provider_write_rules_check

- **W13（appendV1Suffix 极性）**：`effective_base_span` 用共享 `match_bracket` 取 getter 跨度（替掉原先手写的深度计数）；在 **`literal_view`** 上定位 `/v1` 出现位置，映射到 **`code_view`** 的 `if_blocks` 分支跨度——两个视图等长，偏移可直接互用。判据：**构造后缀的那个分支，必须只在 `appendV1Suffix` 为真时可达**（then 分支含后缀且条件否定 → 红；else 分支含后缀且条件为肯定 → 红；条件提到 `appendV1Suffix` 但两个分支都不构造后缀 → 红）。
- **W14（能力 getter 必须是决策）**：`property_body(code, name)` 取 `get() =` / `get() { }` 的体；`effective_value` 折叠后 `const_bool` 非 None → 红（常量）；或体里不读 `providerType` / `credentialType` / `useResponsesAPI` / `customBaseURL` 任一 → 红（答案不可能依赖实例）。取不到体 → fail-closed 红。
- **W15（minis-config UA 写入面）**：`customUserAgent(id)` writer 内必须出现 `customUserAgentWritable(`，且该守卫的**某个可达分支在写入之前终止**（`throw`/`return`）。只存在拼写不够——`{ Unit }` 具名红色（自证臂 S29 专门守这条）。

### provider_models_cache_wiring_check

- **实参转发**：从**注册表声明**解析 `fetchModels` 形参名（`_registry_params`），按位置/具名映射生产调用点的实参（`_map_args`），对 `apiKey` / `forceRefresh` 做常量折叠：任何常量即红（`""` = 换取凭据丢失；`true` = 与丢 `context` 同等关死缓存读门）。
- **TTL 决策**：在 `load` 的 `if_blocks` 里找「then 分支有 `return` 且条件含 `savedAt`」的块，切出顶层比较，若「另一侧」折叠成**非正数字面量** → 红（结果与条目何时保存无关 = 每次读都过期）。允许正数字面量（内联 TTL 是合法写法）。
- **返回值**：找 `load` 内的 payload 局部（右值含 `READ_CALLS` 的 `val`），至少有一条**可达** `return` 通过 `value_sources` 命中它；全是 `null` / 空集合字面量 → 红（命中与未命中不可区分）。
- **交接**：`replaceEntries(` 的实参必须是 fetch 结果的派生（绑定右值含 `fetchModels(` 或实参直接含之），且不得是常量空集合 → 红。

### compact_entry_pin_migration_check

- **Arm 10（entry id 延续）**：`replaceEntries` 内每个 `ModelEntry(...)` 的 `uuid =` 实参，`value_sources` 必须命中 `prior`（或 `existingByModelId`）；同时要求 `val prior =` 的右值来自已存条目（`existing*` / `modelEntries`）。否则红：刷新会让存活模型改名，prune 级联把 pin 当「上游删模型」清掉。
- **Arm 11（交接）**：`refreshModels` 里 `replaceEntries(` 的第二个实参不得是可证空常量（`emptyList()`/`listOf()`/`emptySet()`…），且必须由 `fetchModels` 结果派生。否则红：一次成功刷新把实例条目连同 pin 全部删掉。
- **Arm 12（写者拿到的值）**：`AgentRuntimeLimitsPrefs.save(compactModelEntryId = X)` 的 `X` 必须是 `decideCompactPinMigration(...)` 的 scope-call 参数（`?.let { X -> … }`），且 lambda 内不得再绑定同名局部；直接表达式则必须含该调用。否则红：pin 停在旧 key 上——迁移等于没做。

### 自证臂

| 门 | 修前 | 修后 | 新增 |
|---|---|---|---|
| provider_write_rules | 24 `run_real/run_mutation` 臂（marker ARMS=30） | 28（ARMS=34） | S26（极性翻转）/ S27（getter→常量）/ S28（删 UA 守卫）/ S29（守卫无牙 `{ Unit }`） |
| models_cache | 15 判据 · 14 夹具 | 19 判据 · 18 夹具 | breaker 14（实参常量）/ 15（TTL 折零）/ 16（返回空集合）/ 17（交接空列表） |
| compact | 9 判据 · 27 臂 | 12 判据 · 30 臂 | r3-w12 / r3-w13 / r3-w14（每条只红自己那条臂，带 `contains` 断言防「撞错判据」） |

三个门的 `--selftest` marker 字符串一字未改（`test_scan.py` 冻结面按 marker 断言）。

## §5 四层证据

**L1 门自身**
- `provider_write_rules_check.py .` → rc=0，末行 `ALL GREEN (…W13 appendV1Suffix polarity, W14 capability getters decide, W15 minis-config UA writer guarded)`
- `provider_models_cache_wiring_check.py .` → rc=0，`Summary: 19 passed, 0 failed`
- `compact_entry_pin_migration_check.py .` → rc=0，`OK (12 arms green)`
- 三门 `--selftest` 全绿：`PROVIDER_WRITE_RULES_SELFTEST=PASS / ARMS=34`、`PROVIDER_MODELS_CACHE_WIRING_SELFTEST=PASS (18 fixtures)`、`COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS (29 arms)`；0 条 FAIL

**L2 见证反攻**（§3 表）：9/9 `BLIND_CONFIRMED → CAUGHT`，restore 9/9 True，`git status` 事后为空，每条红在自己那条判据上（`probe/t20_attr.py` 输出）

**L3 邻门 + 全门**（都在本树独立进程跑，生产文件共扫者全跑）
- 邻门 12/12 rc=0：`four_way_sync_check`、`provider_type_registry_check`、`xproc_boundary_check`、`deeplink_settings_routes_check`、`memory_gate_provider_fields_check`、`provider_rpc_symmetry_check`、`entry_composite_key_guard_check`、`entry_id_rename_integrity_check`、`log_url_redaction_guard`、`model_entry_override_parity_check`、`openminis_compat_field_parity_check`、`provider_import_id_roundtrip_check`
- 全门逐进程循环（`probe/t20_gate_loop.py`，整跑 `scan.sh` 在本沙箱常年 `Errno 38`，且本轮三次整跑被整壳杀）：**109/109 rc=0**（`probe/t20-gate-loop.json`，分块 0–38 / 38–70 / 70–109 完成）
- `test_scan.py` 相关夹具组逐组跑：`test_da1020_gates`（12 ✅ / 0 ❌）、`test_da1022_gates`、`test_da1023_gates`（compact 两行 ✅）、`test_da1026_arch_gates`（provider_write_rules 两行 ✅）——全量 344 例交给 CI

**L4 CI**：见 §6

## §6 CI

（待回填：push 后 dispatch `build-apk`，回填 run id / 编号 / head_sha 三方一致、`Summary: 112 passed, 0 failed` 恰一条、`ALL 344 CASES PASS` 恰一条、门头 112 互异、` e: ` 0、含 FAILED 的行全是 PASSED 用例名子串、唯一 skipped = Publish）

## §7 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

- **R1 共享层缺口：`value_sources` 不解析局部标识符绑定**。三条新判据各自补了 `_decl_rhs`（找 `val X =` 右值 + `expr_end`）才够用。触发条件：下一次改这三门任一/新增依赖「名字→值」的判据；触发后方案：把 `_decl_rhs` 上收为 `_kotlin_valueflow.decl_rhs` 并让 `value_sources` 默认解一跳绑定；搁置理由：改共享层会影响 112 门里所有调用点（本波硬约束 #2 禁止擅改），且已登记为跨支同族缺口（B7-t13 也提过）。
- **R2 W13 的 `/v1` 检出按「分支内任意出现」判**。若将来写成 `if (appendV1Suffix) base else if (base.endsWith("/v1")) base else "$base/v1"`，else-if 分支只做比较也可能被当「构造后缀」。触发条件：getter 被改成嵌套分支形态；触发后方案：要求命中位置处于引号区间内（`literal_view` 上做引号扫描），而不是整段分支文本；搁置理由：当前树是单层 `return if (…) … else …`，为不存在的形态建装置违反装置比例纪律。
- **R3 TTL 判据只否「非正常量阈值」**。`>= ttlMs` 与 `> ttlMs` 的差别（前者在边界上仍算过期，用户可见为偶发重取）不判；内联大字面量 TTL（`> 7L*24*3600*1000`）刻意放行。触发条件：出现 TTL 边界相关 bug；触发后方案：把阈值来源（构造参数 / 属性）与比较算子配对建模；搁置理由：当前形态 `> ttlMs` 唯一，扩判据没有触发路径。
- **R4 缓存门实参判据只做常量折叠**。`val k = ""; fetchModels(instance, k, …)` 这种一跳间接不跟（`value_sources` 不解析绑定 = R1 的同族）。触发条件：R1 上收后；触发后方案：改用 `value_sources` 展开后再折叠；搁置理由：同一根因，合并修比分散修便宜。
- **R5 compact Arm 11 要求交接值「文本上派生自 `fetchModels(`」**。若将来把 fetch 拆到另一个函数、由调用方传 list 进来，会 fail-closed 报红。触发条件：`refreshModels` 被拆函数；触发后方案：接受「绑定右值调用的是返回模型列表的函数」这类单跳间接；搁置理由：fail-closed 方向安全（报红不静默放行），且当前无此形态。
- **R6 compact Arm 12 只认 `?.let`/`?.also`/`?.run`/`?.apply` 的 scope-call 形态**。`when (val migrated = decideCompactPinMigration(…))` 或 `val migrated = decide… ?: return` 会被判红。触发条件：迁移块被重写成 `when`/`?:` 形态；触发后方案：把「scope-call 参数」推广到「`when` 主体绑定 / elvis 左值绑定」；搁置理由：当前形态唯一，扩判据属装置过度。
- **R7 W14 的能力属性集是写死的三个名字**。新增 `val supportsXxx: Boolean get()` 不在覆盖内。触发条件：新增同类能力属性；触发后方案：改为派生集合（扫 `val supports[A-Z]\w*\s*:\s*Boolean` + `get()`）；搁置理由：现在只有这三个，派生集合会在无 `get()` 的普通布尔字段上误报，需要再想判据。
- **R8 provider_write_rules 的 W1..W12 仍走门内自写 `read_code/_strip_comments`**。本次新判据故意只读 **raw → shared views**（自写 stripper 删注释会改变长度，偏移无法映射进 `literal_view`）。触发条件：下一次改 W1..W12；触发后方案：整体切到 `mask_code/literal_view` 并把注释保留为等长空格；搁置理由：整体替换会波及 12 条判据的全部站点定位，超出本轮半径（本轮只加三条判据）。
- **R9 `_EMPTY_HIT_RE` 只认字面空集合**。`models.take(0)`、`if (false) models else emptyList()` 不判（后者 const 折叠未对集合做）。触发条件：出现这类写法；触发后方案：把 `const_bool`/`const_value` 扩到集合字面量；搁置理由：无触发路径。

## §8 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t20

# 1) 三门真树 + 自检
for g in provider_write_rules_check provider_models_cache_wiring_check \
         compact_entry_pin_migration_check; do
  python3 scripts/scan/$g.py .            ; echo "real rc=$?"
  python3 scripts/scan/$g.py --selftest   ; echo "selftest rc=$?"
done

# 2) 见证重放（修前基线可 git stash 后跑同一命令）
python3 probe/build_spec.py                 # 从基线树重建 9 条最小编辑
python3 probe/t20_replay.py probe/t20-spec.json probe/t20-post.json
python3 probe/t20_attr.py                   # 每条红在哪条判据上

# 3) 全门逐进程循环（整跑 scan.sh 会 Errno 38）
python3 -u probe/t20_gate_loop.py 0 38
python3 -u probe/t20_gate_loop.py 38 70
python3 -u probe/t20_gate_loop.py 70 200

# 4) test_scan 相关夹具组（避免全量 15 分钟）
for fn in test_da1020_gates test_da1022_gates test_da1023_gates test_da1026_arch_gates; do
  nice -n 10 python3 -u -c "import sys; sys.argv=['x']; sys.path.insert(0,'scripts/scan'); \
    import test_scan as t; t.$fn()" 2>&1 | grep -E '❌|compact|provider_write_rules'
done
```

**产物**：`probe/t20-spec.json`、`probe/t20-pre.json`、`probe/t20-post.json`、`probe/t20-w*.patch`（9 个最小编辑）、`probe/t20-gate-loop.json`、`probe/t20_replay.py`、`probe/t20_attr.py`、`probe/t20_gate_loop.py`、`probe/build_spec.py`。
