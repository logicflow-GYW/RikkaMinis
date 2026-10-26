# FIX-BATCH6-t11：`compact_entry_pin_migration_check.py` + `shizuku_args_check.py`

- 分支：`fix/b6-compactpin-shizuku`
- 门提交：`5311576e8`（2 文件 +1088/−549）
- 基线：`base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享 `_kotlin_valueflow`）
- 生产代码：**0 行**；`scripts/scan/scan.sh`、`scripts/scan/test_scan.py`、`scripts/scan/_kotlin_lexer.py`、`_kotlin_ast.py`、`_kotlin_valueflow.py` **零触碰**；未新建门（112 门 / 344 夹具不变）

---

## ① 门与轴

| 门 | 见证数 | 轴分布 |
|---|---|---|
| `compact_entry_pin_migration_check.py`（9 臂） | 6 | family-hole ×4（A12-1/2/4/5）· dataflow ×1（A12-3）· parser-death ×1（A12-6） |
| `shizuku_args_check.py`（16 判据） | 6 | dataflow ×2（A11-1/…）· family-hole ×3（A11-2/5/6）· spelling ×1（A11-3）· family-hole/order ×1（A11-4） |
| 合计 | **12** | 复证前 12/12 `BLIND_CONFIRMED` → 复攻后 12/12 `CAUGHT` |

两门的共同病因：**判据锚「某个 token / 某个调用 / 某个字面量存在」，缺陷动的是「这个存在物的效果」**。条件还在但恒假、调用还在但不可达、链还在但产物被改写、绑定还在但索引越界 —— 子串与拼写检查对这些一律看不见。

| 轴 | 本支的症状形态 | 修法总纲 |
|---|---|---|
| `family-hole` | 死守卫/恒假条件只认白名单字面量（`if (false)` / `0==1`） | 一律走 `const_bool`（短路语义）+ `guard_feasible` / `path_feasible`（位置可达性） |
| `dataflow` | 检查「标识符/调用存在」而非「结果被消费」 | `value_sources` 取真实求值来源、`call_args` 折叠链式实参、循环内外的写入位置区分 |
| `spelling` | 只认一种「零」拼写（`"0"` / `(0).toString()`） | 锚决定点不锚拼写：resolver 不得有任何 elvis；uid 的 elvis 必须 `return` |
| `order` | `find()` 取首个匹配即证明顺序 | 链式调用按调用顺序判「刚产生的 token 是否被后续步骤改写」 |
| `parser-death` | 自写 `code_only` 只剥 `//`，块注释内代码算活 | 改用共享 `mask_code` / `mask_comments` 双投影（长度严格不变，偏移 1:1） |

---

## ② 见证原文（file / find / replace / why_gate_green）

### A12-1（`family-hole`，Arm 4 · `check_helper_bodies` / `CONST_BODY_RE`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- find：`internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =\n    if (pinEntryId.isNotEmpty() && pinEntryId == oldKey && oldKey != newKey) newKey else null`
- replace：同上，条件前插 `false && `
- why_gate_green：`CONST_BODY_RE` 只拒绝 `if (false)` 独立括号形态，不拒绝 `if (false && ___)`；而形参读取检查（`pinEntryId`/`oldKey`/`newKey` 都出现）照样通过；脚本里明明有能匹配 `false\b` 的 `DEAD_COND_RE`，却只被 Arm 7 调用，Arm 4 从不调。

### A12-2（`family-hole`，Arm 7 · `check_clear_applier` / `DEAD_COND_RE`）

- find：`        if (decideStaleRefCleanup(lastUsedEntryId, removedEntryIds)) lastUsedEntryId = null`
- replace：条件换成 `(2 == 3) && decideStaleRefCleanup(...)`
- why_gate_green：`DEAD_COND_RE` 逐字写死 `0 != 0` / `1 == 0` / `0 == 1` 三对常量，`(2 == 3)` 不匹配。

### A12-3（`dataflow`，Arm 6 · `check_stale_entry_ids`）

- find：`val staleEntryIds = instanceEntries\n .filter { … }\n .filter { … }\n .map { it.id }`
- replace：链尾追加 `.take(0)`
- why_gate_green：判据只查「不是 `emptyList()/listOf()`」「含 `instanceEntries`」「含 `.filter`/`.map`」三项，`.take(0)` 一项都不影响。

### A12-4（`family-hole`，Arm 9 · `check_body` 子串）

- find：`if (voiceInputOverrideEntryId == oldKey) voiceInputOverrideEntryId = newUuid`（输出侧同形）
- replace：两行都改成 `if (false && …)`
- why_gate_green：Arm 9 只做 `if a not in body` 子串匹配，`false && ` 插在锚点之前，锚点原文仍在。

### A12-5（`family-hole`，Arm 8 · `run_ui_checks`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ModelGroupsScreen.kt`
- find：`        pinPrefs.registerOnSharedPreferenceChangeListener(listener)`
- replace：`        if (false) pinPrefs.registerOnSharedPreferenceChangeListener(listener)`
- why_gate_green：`run_ui_checks` 只查 `register…` / `unregister…` / `.compactModelEntryId()` 三个子串与先后顺序，从不看可达性。

### A12-6（`parser-death`，Arm 2 · `code_only` 块注释盲区）

- find：`        if (decideCompactPinCleanup(AgentCompact…())) {\n            AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")\n        }`（`removeEntry` 内）
- replace：整段用 `/* … */` 包住
- why_gate_green：自写 `code_only` 只有 `re.sub(r"//[^\n]*", "", text)`，**连块注释都没剥**，被注释掉的代码在扫描文本里仍然存在。

### A11-1（`dataflow`，Crit 13 · `_strips_option_out`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ShizukuOffloadHandler.kt`
- find：`    while (i < tokens.size) {\n        val token = tokens[i]`
- replace：`    payload.addAll(tokens)\n    while (i < tokens.size && false) {\n        val token = tokens[i]`
- why_gate_green：只查「有 `while (`/`for (`」「循环体内有 `--timeout` 比较 + 跳过」「循环体内有 `payload.add(`」三件事；条件恒假与循环外预填都不在判据里。（补充分支：循环后 `payload.clear(); payload.addAll(tokens)` 同样穿透。）

### A11-2（`family-hole`，Crit 14 · arrayOf 方向槽）

- find：`arrayOf("cmd", "netpolicy",\n                if (restrict) "remove" else "add", "restrict-background-whitelist", uid)`
- replace：方向槽换成 `if (true) "add" else if (restrict) "remove" else "add"`
- why_gate_green：判据是 `re.search(r'if\s*\(\s*restrict\s*\)\s*"remove"\s*else\s*"add"', arr)` 的子串命中——`else` 后面紧跟的正是这一整段，正则一击即中。

### A11-3（`spelling`，Crit 15 · `_zero_fallback` / `_ZERO_SPELLINGS`）

- find：`internal fun extractUidFromDumpsys(dumpsysOutput: String): String? =\n    Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)`
- replace：链尾追加 `?: (1 - 1).toString()`
- why_gate_green：`_ZERO_SPELLINGS` 是固定集合 + `^"0+"$` / `^0+[lL]?$` 正则；`(1 - 1).toString()` 不在集合里。判据本质是「零的拼写黑名单」，而不是「解析失败必须拒绝」。

### A11-4（`family-hole`，Crit 3 · `encodeInputText`）

- find：`text.replace(" ", "%s").replace("'", "")`
- replace：链尾追加 `.replace("%s", "%")`
- why_gate_green：只查两个 `.replace` 子串存在；刚产生的 `%s` 被下一步改写，判据完全不知情。

### A11-5（`family-hole`，Crit 4 · `--cache-only`）

- find：`    if (cacheOnly) cmd.add("--cache-only")`
- replace：`    if (cacheOnly && false) cmd.add("--cache-only")`
- why_gate_green：只查函数体内出现 `cacheOnly` 与 `--cache-only`，不看守卫活性。

### A11-6（`family-hole`，Crit 6 · `notificationChannel` 索引）

- find：`                val pkg = pos.getOrNull(1) ?: return errEnvelope("INVALID_ARGS", "notification channel list <pkg>", args)`
- replace：`                val pkg = pos.getOrNull(999) ?: pos.firstOrNull() ?: return errEnvelope(…)`
- why_gate_green：判据只查「函数体里出现 `pos.getOrNull(1)`」与「不出现 `rest.getOrNull(`」；越界索引 + 二级回退两者都不违反（`pkg` 变成字符串 `"list"`，缺参错误永不触发）。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### `compact_entry_pin_migration_check.py`

| # | 旧判据 | 新判据 |
|---|---|---|
| 全局 | 自写 `code_only`（只剥 `//`）· 自写 `_balanced` / `function_text` / `decl_body` / `_stmt_end` | 共享 `code_view`（`mask_code`：注释 + 字面量内部）+ `literal_view`（`mask_comments`）双投影；`function_body` 取体；`match_bracket` 配平；`KotlinLexError` → 违规（fail-closed） |
| A12-1 | `CONST_BODY_RE`（只认 `if (false)`）+ 形参名出现 | `fold_value(body)`：`const_bool` 折叠条件后**只折叠可达分支**，`if`/`when` 递归；整段只能产出常量即违规；形参用 `contains_ident` |
| A12-2 | `DEAD_COND_RE`（写死 `0 != 0` 等三对） | `check_clear_applier`：体内每个 `if` 走 `guard_feasible(cond)`；且每个被清理引用必须存在**可达**的 `= null` 写点（`path_feasible`） |
| A12-3 | `EMPTY_OR_CONST_COLL_RE` + `DERIVE_CALL_RE`（只查子串） | `zeroing_reason`：用 `call_args` / `match_bracket` 取链上每个调用的实参并折叠（`take(0)` / `filter { false }` / `filterNot { true }` / `takeWhile { false }`）；再加「必须派生自 `instanceEntries`」+ 每个 `value_sources` 分支必须产出派生集 |
| A12-4 | `check_body` 子串存在 | 新增 `check_live`：锚点位置走 `path_feasible`，任何包裹它的恒假条件即违规（Arm 1/2/3/5/6/9 全部统一调用） |
| A12-5 | `run_ui_checks` 子串 + 先后 | 同上 `check_live`（整文件域，`header=None`），注册/注销/刷新三处都判可达 |
| A12-6 | `code_only` | 统一 `code_view`（块注释、`"""…"""`、`${…}`、字符字面量全覆盖） |

新增辅助：`split_if`（同 `else` 的行内 `if` 表达式分支切分，共享 `if_blocks` 会把同行的 `else` 吞进 then）、`when_parts`（常量 subject 的 `when` 折叠）、`depth0_token`。

### `shizuku_args_check.py`

| # | 旧判据 | 新判据 |
|---|---|---|
| 全局 | 自写 `strip_comments` / `function_body` / `_array_span` 括号行走 / `_expr_at` | `views()` 双投影（一次扫描）：结构判定走 code 视图、锚点拼写走 literal 视图；`function_body` / `match_bracket` / `call_args` / `expr_end` / `split_top` 全部共享 |
| Crit 3 | 两个 `.replace` 子串存在 | `_encode_verdict`：按**调用顺序**读链，断言步数与两步的 (输入,产物) 对，并拒绝「后续步骤改写前面刚产出的 token」 |
| Crit 4 | `cacheOnly` + `--cache-only` 子串 | 定位 `cmd.add("--cache-only")` 的位置，`path_feasible` 判可达 + 取 `enclosing_conditions` 断言守卫读 `cacheOnly` |
| Crit 6 | `pos.getOrNull(1)` 存在 + `rest.getOrNull(` 不存在 | `_pkg_bindings`：绑到 `val pkg = <expr>`，左侧必须恰为 `pos.getOrNull(1)`，`?:` 右侧必须 `return …`（禁止任何其它回退） |
| Crit 13 | 有循环 + 循环内有比较 + 有 `payload.add(` | `_strips_option_out`：循环条件 `guard_feasible` + 必须枚举输入形参（形参名从声明派生，不写死 `tokens`）+ 选项必须在**循环体内被比较** + 两个累加器的每次 `.add/.addAll/.clear/.removeAll` 必须落在循环体内 + 循环前不得提前 `return` + 返回对必须是那两个累加器 |
| Crit 14 | `if (restrict) "remove" else "add"` 子串命中 | `_call_literals` + `_direction_verdict`：只判作为**调用实参**的 `arrayOf(...)`；方向槽必须是单个 `if (…)`，条件必须读 `restrict` 且**不可折叠为常量**，then/else 必须是字面量对且与列表语义匹配（白名单 remove/add，黑名单 add/remove） |
| Crit 15 | `_ZERO_SPELLINGS` 黑名单 + `_expr_at` | resolver **不得有任何 `?:`**（契约是「解析不到就 null」）；每个 `pkgUid(...)` 表达式的 `?:` 右侧必须以 `return` 开头（拒绝而非降级），仍保留两处 `?: return errEnvelope(` 的正向锚 |

`analyse()` 签名不变（`src -> [(criterion, ok, line, note)]`），selftest / main 不再预剥注释。

---

## ④ 四层证据

**L1 本门**

```
$ python3 scripts/scan/compact_entry_pin_migration_check.py .
compact_entry_pin_migration_check: OK (9 arms green)
$ python3 scripts/scan/compact_entry_pin_migration_check.py --selftest
COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS (26 arms)      # 原 17 + 见证 9

$ python3 scripts/scan/shizuku_args_check.py .
✅ android-shizuku-cli argument handling: argv parsing + call sites verified   # 16 判据全 OK
$ python3 scripts/scan/shizuku_args_check.py --selftest
SHIZUKU_ARGS_SELFTEST=PASS criteria=16 breakers=16 extra=17   # 原 9 extra + 见证 8
✅ selftest: gate flips red on every criterion
```

新增臂全部断言「摔在哪条判据上」（`contains=` 匹配违规文案），撞错判据 = 假臂 = FAIL。

**L2 反攻（硬验收，官方驱动）**

```
$ python3 …/verify_batch5.py --witnesses …/witnesses-A.json --repo . --mode full \
    --only compact_entry_pin_migration_check.py --out …/probe/t11-compact_entry_pin_migration_check.json
$ python3 …/verify_batch5.py --witnesses …/witnesses-A.json --repo . --mode full \
    --only shizuku_args_check.py --out …/probe/t11-shizuku_args_check.json
```

结果：**12/12 `CAUGHT`**（family-hole ×7 / dataflow ×3 / spelling ×1 / parser-death ×1），`restored` 12/12 `True`，跑完 `git status --porcelain src/` 为空。

**L3 邻门（8 道，均 rc=0）**

`compact_decision_gate_check` · `compact_pin_truth_check` · `deeplink_settings_routes_check` · `offload_cli_args_check` · `offload_host_path_check` · `offload_media_b64_wire_check` · `offload_permission_reset_sync_check` · `offload_relative_path_unify_check`

**L4 全量**

- 逐门循环（`probe/t11_scan_chunks.py`，一门一进程 + 台账）：**110 门 rc=0，0 失败**（含 `i18n_check` / `process_logging_gate` / `turn_tail_finalize_order`；`calendar_events` 与 `tts` 用 `--root .` 形态）。
- `probe/t11_testscan_chunks.py`（逐 `test_*` 函数、必须传 repo root）：已完成 **26 函数 / 134 用例 PASS / 0 FAIL**，其余函数撞上沙箱 `Errno 38` 窗口未能本地跑完。
- 因此 **112/0 + ALL 344 的权威读数取分支 CI job 日志**（CI 在同一棵树上跑 `scan.sh` + `test_scan.py`）：见 §⑤。这与本批其它支的处置一致（并发窗口内 `scan.sh` 整跑被 stall-guard / ENOSYS 杀，逐门循环 + CI 日志互补）。

---

## ⑤ CI

- run：**`40254894098`** / **#2702** / job **`121354532562`** / attempt **1** / **success**
- `head_sha` = **`5311576e877b7b8b55189cf8eabbeacd82831952`**，与本地 `git rev-parse HEAD` 逐字符一致
- 26 步：非 success 0 条，唯一 `skipped` = `Publish to Releases`（main 门控，符合预期）
- 日志 1,486,219 B，逐条核对：
  - `Summary: 112 passed, 0 failed` 恰 **1** 条
  - `ALL 344 CASES PASS` 恰 **1** 条
  - ` e: ` **0** 条
  - 含 `FAILED` 的 **6** 行全部是 PASSED 用例名的子串（`FAILED without calling provider PASSED` / `FAILED maps to persistence_failed PASSED` / `FAILED maps to Failed PASSED` ×2 / `FAILED maps to all_fallbacks_exhausted PASSED` / `FAILED terminal PASSED`）
  - `selftest has teeth` **85** 处；`[47/112] C/ISSUE-07 compact pin truth` 与 `COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS` 均在册

---

## ⑥ 残留（均 P2，带触发条件 / 触发后方案 / 搁置理由）

**R1 共享 `if_blocks` 的 then 区间会吞掉同一行的 `else`**（`if (c) a else b` 表达式形态）。
触发条件：某门需要判「表达式位置 if 的两条分支各自的值」时。触发后方案：把本支两处 `split_if`（`depth0_token` 找顶层 `else`）上提到 `_kotlin_ast`，让共享层统一。搁置理由：改共享层影响 111 门，必须整批回归，超出本支半径。

**R2 `enclosing_conditions` 不建模 `when(subject)`**（只回臂模式，恒假 subject 下的臂仍判活）。
触发条件：出现「`when (常量)` 包住写点」的见证。触发后方案：共享层加 subject 常量折叠（本支 `compact` 门已用局部 `when_parts` 处理表达式位置的 `when`，可直接搬）。搁置理由：同 R1，需整批回归。

**R3 `check_live` 只取锚点首个出现位置**。
触发条件：同一锚点在一个函数里出现多次且「先死一次、后活一次」。触发后方案：遍历全部出现位置，任一可达即算活。搁置理由：当前真树每锚点唯一（9 臂绿），改成全遍历会引入「死分支副本掩盖活点」的反向误判风险，需配套设计。

**R4 `check_stale_entry_ids` 的派生源 `instanceEntries` 是写死的**（找不到派生起点即 fail-closed 红）。
触发条件：重构把派生起点改名/换成辅助函数。触发后方案：从 `val staleEntryIds` 的接收者派生 source。搁置理由：fail-closed 方向安全（红而非静默绿），且本支半径只覆盖 A12-3。

**R5 shizuku Crit 14 要求方向槽恰为单个两态 `if (restrict) … else …`**。
触发条件：出现三态方向（如加 `"default"`）。触发后方案：把槽折叠成「值 → 条件」集合再与期望集合比对。搁置理由：现状两态，收紧方向 fail-closed。

**R6 shizuku `_pkg_bindings` 只认 `val pkg = …` 声明形态**，内联实参（`when` 分支直接 `runProcess(arrayOf(…, chan(pos.getOrNull(1)…)))`）会 fail-closed 红。
触发条件：重构去掉中间绑定。触发后方案：改为「凡进入 argv 的包名实参都必须派生自 `pos.getOrNull(1)`」。搁置理由：需要新的实参追踪，超出 A11-6 半径。

**R7 本地整跑不可行的装置事实**：`scan.sh` 整跑与 `test_scan.py` 整跑在并发窗口内被 stall-guard / `Errno 38` 杀（历史上已多次复现）。健康路径 = 逐门一进程 + 逐 `test_*` 一进程（且**必须传 repo root**，否则 `REPO_ROOT` 变成函数名，曾造出 91 条假 FAIL）。已写入 `probe/t11_scan_chunks.py` / `probe/t11_testscan_chunks.py` 头部。非门缺陷，仅装置经验。

**R8 shizuku 新增 8 条见证臂在 CI 日志里无独立见证**：`test_scan.py` 对本门只断言 `gate flips red on every criterion`，不回显 `extra=17` 计数。触发条件：需要 CI 侧见证臂数增长时。触发后方案：主会话在 `test_scan.py` 的该门元组补一条 `SHIZUKU_ARGS_SELFTEST=PASS criteria=16 breakers=16 extra=17` 断言。搁置理由：`test_scan.py` 属本批冻结面（硬约束 #2），须主会话统一注册。

---

## ⑦ `test_scan.py` 注册项（交主会话）

两门在基线里**均已注册**，本支未新增注册债：

| 门 | 位置 | 现断言 |
|---|---|---|
| `shizuku_args_check` | `test_scan.py:2645` | `("shizuku_args_check", "gate flips red on every criterion", (REPO_ROOT,))`，已满足 |
| `compact_entry_pin_migration_check` | `test_scan.py:2857` | `(…, "COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS", (), (REPO_ROOT,))`，本支 marker 现为 `…=PASS (26 arms)`，子串仍命中 |

**建议（不自行修改）**：

1. 把 compact 门的断言语义升级为带计数（防臂静默消失）：`"COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS (26 arms)"`。
2. 给 shizuku 门补一条计数断言：`"SHIZUKU_ARGS_SELFTEST=PASS criteria=16 breakers=16 extra=17"`（见 R8）。

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t11

# L1 本门
python3 scripts/scan/compact_entry_pin_migration_check.py .
python3 scripts/scan/compact_entry_pin_migration_check.py --selftest
python3 scripts/scan/shizuku_args_check.py .
python3 scripts/scan/shizuku_args_check.py --selftest

# L2 反攻（官方驱动，勿自造跑法）
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-A.json \
  --repo . --mode full --only compact_entry_pin_migration_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t11-compact_entry_pin_migration_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-A.json \
  --repo . --mode full --only shizuku_args_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t11-shizuku_args_check.json

# L3 邻门
for g in compact_decision_gate_check compact_pin_truth_check deeplink_settings_routes_check \
         offload_cli_args_check offload_host_path_check offload_media_b64_wire_check \
         offload_permission_reset_sync_check offload_relative_path_unify_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1; echo "rc=$? $g"
done

# L4 全量（沙箱并发窗口内的健康路径：一门一进程 + 逐 test_* 一进程）
python3 -u probe/t11_scan_chunks.py 0 111        # 断点续跑，台账 probe/t11-scan-ledger.txt
python3 -u probe/t11_testscan_chunks.py 0 12     # 台账 probe/t11-testscan-ledger.txt
# 权威读数取分支 CI job 日志：gh-actions-runs --branch fix/b6-compactpin-shizuku
```
