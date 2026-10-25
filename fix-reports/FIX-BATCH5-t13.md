# FIX-BATCH5-t13 — `provider_remember_key_check` 六条见证加固

- 门：`scripts/scan/provider_remember_key_check.py`（第 7 道 / 112）
- 分支：`fix/b5-providerremember`，基线 `base/batch5-shared` @ `e145ec961`
- 门提交：`a4630e8497197764858ed1bcf9d1240e2a34ed1e`
- 改动面：仅本门文件（+339 / −299，生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / `_kotlin_*.py` 零触碰、门数 112 / 夹具 344 不变）

## §1 门与轴

| # | 见证 | 目标判据 | 轴 | 家族 |
|---|---|---|---|---|
| 07-1 | G07-A1 | `C16` (`check_envvar_screen`) | family-hole | 外部删除环境变量时自动关闭编辑弹窗 |
| 07-2 | G07-A2 | `C21` (`check_add_custom_model`) | dataflow | 保存自定义模型前二次校验父 Provider 存活 |
| 07-3 | G07-A3 | `C22` (`check_connection_write_path`) | dataflow | 写操作必须基于实时 `currentInstance()` |
| 07-4 | G07-A4 | `C28` (`check_provider_detail_live_state`) | dataflow | 待删模型在外部被删时关闭确认弹窗 |
| 07-5 | G07-A5 | `C30b` (`check_credential_live_source_list`) | dataflow | `apiKeyByInstanceId` 按实例 ID 消费 |
| 07-6 | G07-A6 | `C33` (`check_quick_test_execution`) | dataflow | 向执行网关传递富化后的 `effectiveModel` |

共 6 条见证：dataflow 5 · family-hole 1。共同病因：判据锚「名字/调用/键存在」，见证保留全部名字、抽掉决定本身。

## §2 见证原文（file / find / replace / 旧判据为何绿）

### 07-1 C16 — `EnvironmentVariablesScreen.kt`

```kotlin
find:
        LaunchedEffect(editEntryId, entries) {
            if (editTargetVanished(entries, editEntryId)) {
                editEntryId = null
            }
        }
replace:
        LaunchedEffect(editEntryId, entries) {
            if (false && editTargetVanished(entries, editEntryId)) {
                editEntryId = null
            }
        }
```

旧判据绿：`ENVVAR_CLOSE_EFFECT_RE` / 存在性正则只要求「effect 键 + 清空赋值 + 某个存在性拼写在 900 字符窗口内出现」；`false &&` 让守卫恒假，拼写全部在位。

### 07-2 C21 — `AddCustomModelScreen.kt`

```kotlin
find:      val parentStillThere = providerRepository.config.value.instances
               .any { it.id == instanceId }
           if (!parentStillThere) {
replace:   val parentStillThere = providerRepository.config.value.instances
               .any { it.id == instanceId }
           if (false && !parentStillThere) {
```

旧判据绿：只查「`parentStillThere` 片段存在 + 出现在写之前」；短路后守卫永不进入，`onBack()` 分支成为不可达装饰。

### 07-3 C22 — `ProviderConnectionScreen.kt`

```kotlin
find:      val live = currentInstance()
           val pending = pendingConnectionFieldWrite(customBaseURL, live.customBaseURL) ?: return
           providerRepository.updateInstance(live.copy(customBaseURL = pending))
replace:   providerRepository.updateInstance(instance.copy(customBaseURL = pending).also { live })
```

旧判据绿：在实参**文本**里搜 live 名 / `currentInstance(`；`.also` 是丢弃型作用域（返回接收者），文本里点名了 `live`，实际写入的是合成期快照 `instance`。

### 07-4 C28 — `ProviderDetailScreen.kt`

```kotlin
find:      internal fun entryDeleteTargetVanished(pendingId: String?, entries: List<ModelEntry>): Boolean =
               pendingId != null && liveEntryToDelete(pendingId, entries) == null
replace:   internal fun entryDeleteTargetVanished(pendingId: String?, entries: List<ModelEntry>): Boolean =
               false
```

旧判据绿：只要求 effect 的键、`entryToDeleteId = null`、以及 helper 名在 body 里出现；被调 helper 掏空成恒假后决定消失，弹窗永不清空。

### 07-5 C30b — `ProviderListScreen.kt`

```kotlin
find:      instances.map { instance ->
               val apiKey = apiKeyByInstanceId[instance.id]
replace:   instances.map { instance ->
               val apiKey = apiKeyByInstanceId[""]
```

旧判据绿：生产者 / 消费 / 键三项齐全；常量下标每行解析到同一个（空）条目，徽标全灭而拼写全在。

### 07-6 C33 — `QuickTestSheet.kt`

```kotlin
find:      ProviderExecutionGateway.generateImage(... model = effectiveModel ...)
replace:   ProviderExecutionGateway.generateImage(... model = effectiveModel.copy(outputModalities = null) ...)
```

旧判据绿：实参文本出现 `effectiveModel` 即放行；`.copy(outputModalities = null)` 把跨进程无法再富化的字段抹掉，worker 侧退化成旧行为。

## §3 判据改造（旧形态 → 新形态）

1. **C16**：名字窗口正则 → `_clear_guarded_by_vanished()`：`_iter_remember_raw` 定位 effect 区间；`enclosing_conditions` 取支配条件（含 braceless `if`）；`path_feasible` + `const_bool` 判短路（`false &&` / `when(false)` 均判不可达）；`_condition_tests_vanished` 要求条件本身或被调 helper **函数体**真的做存在性测试（`= false` 红）。helper 体不可解析 ⇒ fail-closed。
2. **C21**：片段顺序检查 → `_guard_blocks_the_write()`：`if_blocks` 定位候选守卫块；`guard_feasible` 判短路；divert（`return` / `onBack` / `return@launch`）必须真实终结写路径；守卫不可行 = 未守卫。
3. **C22**：实参文本搜 live 名 → `_live_write_arg()`：`peel_discarding` 先剥 `.also` / `.apply` / `.onEach` 等丢弃型作用域，再对结果跑 `kflow.value_sources`，只有**最终值来源**含 live 本地名或 `currentInstance(` 才算通过。
4. **C28**：键 + 名字 → `_clear_guarded_by_vanished()`（同 C16 机制，清空目标 `entryToDeleteId`）；helper 体恒假 / 短路 / 不可达 ⇒ 红。
5. **C30b**：三项齐全 → 追加「快照下标必须是可随行变化的表达式」：`_subscript_key`（共享 `match_bracket`）+ `kast.is_pure_literal`，常量键判红并说明「同一（空）条目解析给每一行」。
6. **C33**：富化名出现即放行 → 追加去富化判定：实参含 `.copy(` 且同段以 `null` 赋给 `*Modalit*` / `*capabilit*` 字段 ⇒ 红（「点名富化 ≠ 携带富化」）。

六条判据都在真树上保持 GREEN（新消息可见：C28 `gated on a live existence test (via helper entryDeleteTargetVanished())`、C33 `all 5 argument site(s)`）。

## §4 四层证据

**L1 门级**
- 真树：`python3 scripts/scan/provider_remember_key_check.py .` → `real tree: GREEN`，C16/C21/C22/C28/C30b/C33 全 PASS。
- 自测：`--selftest` → `PROVIDER_REMEMBER_SELFTEST=PASS (139 arms)`，`MISMATCH` = 0；clean 臂绿；六条新臂各**恰好红自己的判据**：
  `c16-envvar-dead-guard→C16` · `c21-custom-model-dead-guard→C21` · `c22-conn-also-live→C22` · `c28-detail-helper-constant→C28` · `c30-list-constant-subscript→C30b` · `c33-quick-test-enriched-copy-null→C33`。
- 夹具补全：`FIX_PD_LIVE_TAIL` 补上 `entryDeleteTargetVanished` 的 helper 本体（与真实源码同形），否则 C28 新判据在 clean 夹具上会 fail-closed 误红。

**L2 反攻（硬验收）**
- `verify_batch5.py --witnesses witnesses-B.json --mode full --only provider_remember_key_check.py`
- 结果：`SUMMARY: {'CAUGHT': 6}`，逐条 `CAUGHT (rc=1, exact)`；`restored` 6/6 `True`；`git status --porcelain src/` 空。

**L3 邻门**（10 门 rc=0）
`models_provider_field` · `provider_import_id_roundtrip` · `provider_models_cache_wiring` · `provider_rpc_symmetry` · `provider_type_registry` · `provider_ua_parity` · `provider_write_rules` · `model_entry_override_parity` · `session_provider_enabled` · `memory_gate_provider_fields`。
注：`provider_rpc_symmetry` 第一次 rc=1 是**自测残留污染**——被中断的自测把 `prk-selftest-*` 临时树留在工作树 `.tmp/`，门的全树扫描因此看到 5 份 `ProviderConnectionScreen.kt`；`rm -rf .tmp/prk-selftest-*` 后 rc=0（结论：删除残留，不改门）。

**L4 CI（分支，attempt 1）**
- run **#2689** / id `40208967048` / job `121215902770`（`build`）/ **success**，`2026-10-25T15:52:09Z` 起。
- 三方 sha 逐字符一致：本地 HEAD = 分支 ref = `run.head_sha` = `a4630e8497197764858ed1bcf9d1240e2a34ed1e`。
- 日志 27 文件 / 2,965,490 B。`0_build.txt`（1,482,434 B，作业级）与 `build_3_Run_pre-build_scan_gate.txt`（130,065 B，步骤级）**各含一次** `Summary: 112 passed, 0 failed`、一次 `ALL 344 CASES PASS`、112 个门头——两份日志并存，不是跑了两次。
- ` e: ` 计数 **0**；含 `FAILED` 且不含 `PASSED` 的行 **0** 条；本门自测标记不进 CI 日志（门自证 stdout 不落 CI），故 §7 交主会话注册。

## §5 CI 记录

| 项 | 值 |
|---|---|
| run | #2689（id `40208967048`） |
| job | `121215902770`（`build`） |
| attempt | 1 |
| conclusion | **success** |
| head_sha | `a4630e8497197764858ed1bcf9d1240e2a34ed1e` |
| 门 / 夹具 | 112 passed, 0 failed / ALL 344 CASES PASS |

## §6 残留（R1..R5）

- **R1 · C26 未加固（同门同族）**：`check_provider_detail_live_state` 的 C26 仍用实参文本搜 live 名 / `currentInstance(`，`.also { live }` 形状的 `updateInstance` / `exportProviderInstance` 仍可过。
  - 触发条件：下一轮扫描把 C26 判 BLIND，或 `ProviderDetailScreen.kt` 出现丢弃型作用域包裹的写。
  - 触发后方案：把 `_live_write_arg` 复用到 C26 的写目标循环，一把改掉两处。
  - 搁置理由：radius（任务书 §1.4 只修点名的六条判据）。
- **R2 · C33 去富化只认同段 `.copy(`**：经局部变量中转（`val m = effectiveModel.copy(outputModalities = null); model = m`）不红。
  - 触发条件：新见证或真实代码出现中转形状。
  - 触发后方案：对实参跑 `kflow.value_sources` 再判最终值是否仍携带富化字段。
  - 搁置理由：当前形状已被本臂堵住，中转形状未在真树出现。
- **R3 · C30b 常量键只认纯字面量**：`val k = ""; map[k]` 这类常量折叠不红。
  - 触发条件：新见证；触发后方案：`kast.const_value(key)` 判常量。
  - 搁置理由：无现成见证，避免过度判据误伤。
- **R4 · `statement_tail` / `_brace_blocks` 仍是本门局部实现**（二者不是词法器：前者按深度 0 换行切句、后者在 `code_of`（注释/字符串已掩码）上配对花括号）。
  - 触发条件：出现利用字符串/字符字面量的见证；触发后方案：迁移到 `kast` 对应原语。
  - 搁置理由：任务书未点名，且已运行在掩码后文本上。
- **R5 · 本地全量跑不完**：`bash scripts/scan/scan.sh` 与 `python3 scripts/scan/test_scan.py` 在本轮多次被沙箱并发窗口打断（`OSError: [Errno 38] Function not implemented`），逐门循环跑到约 30/111 后中断；全量证据改由 CI 提供（112/0 + ALL 344）。
  - 触发条件：沙箱空闲窗口；触发后方案：空闲时补跑本地全量；搁置理由：CI 已给等价证据，重复跑无信息增益。

## §7 `test_scan.py` 注册项（交主会话统一注册）

- 标记串：`PROVIDER_REMEMBER_SELFTEST=PASS (139 arms)`（宽松匹配可用 `PROVIDER_REMEMBER_SELFTEST=PASS`）
- 建议条目：`("provider_remember_key_check", "PROVIDER_REMEMBER_SELFTEST=PASS (139 arms)", (REPO_ROOT,))`
- 本批新增六臂：`c16-envvar-dead-guard` · `c21-custom-model-dead-guard` · `c22-conn-also-live` · `c28-detail-helper-constant` · `c30-list-constant-subscript` · `c33-quick-test-enriched-copy-null`

## §8 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t13
python3 scripts/scan/provider_remember_key_check.py .                    # 真树 GREEN
TMPDIR=$PWD/.tmp python3 scripts/scan/provider_remember_key_check.py --selftest
# → PROVIDER_REMEMBER_SELFTEST=PASS (139 arms)

python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json \
  --repo . --mode full --only provider_remember_key_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t13-provider_remember_key_check.json
# → SUMMARY: {'CAUGHT': 6}, restored 6/6 True

for g in models_provider_field provider_import_id_roundtrip provider_models_cache_wiring \
         provider_rpc_symmetry provider_type_registry provider_ua_parity provider_write_rules \
         model_entry_override_parity session_provider_enabled memory_gate_provider_fields; do
  python3 scripts/scan/${g}_check.py . >/dev/null || echo "RED $g"
done   # 全部 rc=0
```
