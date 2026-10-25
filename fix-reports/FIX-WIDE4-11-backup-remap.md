# FIX-WIDE4-11 — `backup_remap_rollup_check.py`（5 条盲 + 1 条已挡，23 条新臂）

**分支** `fix/wide4-11-backup-remap`（基于本波前置提交 `dbab7a3e`：共享 Kotlin 词法/语法原语）
**改动面**：`scripts/scan/backup_remap_rollup_check.py` 一个文件（+339/−171）＋ 本报告
**生产代码 0 行改动**；`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` 零触碰；
门数仍 112、夹具仍 344；无新建门；冻结标记串 `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` 逐字未改。

---

## §1 门与轴

| 判据 id | 判据职责（旧形态） | 失效轴 | 原判决 |
|---|---|---|---|
| A `check_remap_defaults` | `runtime.compactModelEntryId` → `entryIdMap`；旧形态 `"entryIdMap" in _live_value(value)` | family-hole | FALSIFIED |
| B `check_import_chat_sections` | `modelBinding = remapSessionBindingJson(…)`；旧形态 `_drops_remap` 猜尾链是否丢值 | family-hole | FALSIFIED |
| D2 `check_voice_group_remap` | 两条 voice group 路径 → `groupIdMap`；与 A 同块却仍是子串 + 逐行 `//` 切分 | family-hole（asymmetry） | FALSIFIED |
| C3 `check_delete_guard` | `deleteFile` 拒删 `ROLLUP_FILE`；旧形态 `ROLLUP_GUARD in code_only(body)` | family-hole + order | FALSIFIED |
| C3' `code_only` / `strip_kotlin_comments` / `_mask` | 手写词法器（首个 `*/` 收尾） | parser-death | FALSIFIED |
| A（回归） | `if (false) entryIdMap else emptyMap()` | dataflow | CAUGHT（保持） |

三类共性：

1. **family-hole** —— 上一轮把「子串存在性」升级成「针对上一批样本的单点形状黑名单」，判据仍在问
   「这段文字里出现过吗」。同族换一种拼写即穿：`entryIdMap.filter { false }` / `.let { emptyMap() }` /
   `if (1 == 2) …` / `.let { val _unused = it; null }` / `.takeIf { it == "never" }` / `if (false) { guard }`。
   共同形态 = **保留拼写、删掉决定**。
2. **asymmetry（D2）** —— 同一个 `when (path)` 块里 A 臂已升级、D2 臂还是子串判据。攻击者只挑没修的
   那条臂；**同族缺口必须同帧修完**。
3. **parser-death** —— `strip_kotlin_comments` 在自己的 `/*` 扫描里找**第一个** `*/`，而 Kotlin 块注释
   可嵌套：`/* outer /* inner */ <code> */` 把 `<code>` 交回「活代码」视图。同一段手写扫描器还不认识
   `'"'` / `"""` / `${…}` / `` ` ``。

## §2 每条见证（原补丁 → 旧判据为何瞎 → 新判据 → 新臂名）

| # | 原补丁（官方见证逐字） | 旧判据为何瞎 | 新判据 | 新臂名 |
|---|---|---|---|---|
| 1 | `… "runtime.compactModelEntryId" -> entryIdMap.filter { false }` | `"entryIdMap" in _live_value(value)` 是**子串**判定，`.filter{false}` 名字全留 | 正向：常量折叠后的 LIVE 值**必须恰为一个标识符**（`_live_ident`）；`!!` / `as T` 视为拼写 | `w4-A compact arm filtered empty` |
| 2 | `… ) .let { val _unused = it; null },` | `_drops_remap` 是黑名单猜尾链（找 `it`），`val _unused = it` 骗过它 | 正向：右值**必须就是**该调用的结果（`_is_exact_call`，配平 `)` 即右值末尾） | `w4-B binding let-unused-it` |
| 3 | `… "defaults.agentLoopGroups" -> if (false) groupIdMap else emptyMap()` | 与 A 同块却仍是子串 + 逐行 `//` 切分 | 与 A **同一个 helper**（`_routes_to_map`），结构上无法再分叉 | `w4-D2 group arm dead branch` |
| 4 | `if (false) { if (name == ROLLUP_FILE) return false }` | 只问守卫「拼写出现过」；死外层分支保留全部字符 | 结构：守卫必须是 `deleteFile` 的**顶层语句**且**早于 `.delete()`** | `w4-C3 guard inside if (false)` |
| 5 | `/* outer /* inner */ if (name == ROLLUP_FILE) return false */` | 手写剥注释在**嵌套**块注释处提前收尾 | 视图全交共享 `_kotlin_lexer`；解析失败 **fail-closed** | `w4-C3 guard inside nested block comment` |
| 6 | `→ if (false) entryIdMap else emptyMap()`（原 CAUGHT） | — | 回归保护（保持红） | 既有臂 `M24 compact arm routed through a dead branch` |

**同族扩展（本支判断，非官方见证，均已固化臂）**：`!true`（`w4-A … double-negated literal`）、
`1 == 2`（`… numeric-false test`）、`json == json`（`… tautology`）、`when { false -> … }`
（`… when-false` —— `_live_value` 从不折叠 `when`）、`takeIf { false } ?: emptyMap()`
（`… takeIf-dropped`）、`if (json != null) entryIdMap else emptyMap()`（`… runtime branch`，**运行时**
分支同样交出空表）、`.let { s.optString("modelBinding") }`、`?: null`、`if (true) { guard }`、
`'"'` 字符字面量把守卫吞掉、`"""` 原始字符串把守卫吞掉、**守护卫之后才 `.delete()`**。
clean 臂：`entryIdMap as Map<String, String>` 必须绿（`w4-A clean cast is spelling only`）。

## §3 判据改动清单（函数名级）

| 函数 / 常量 | 改动 |
|---|---|
| `strip_kotlin_comments` / `code_only` / `_mask` | 手写扫描器整体删除 → `mask_comments` / `mask_code`（共享词法器）；`_mask` 对不可闭合片段整段抹白（fail-closed） |
| `_truth` | 本地折叠 → 共享 `const_bool`（补 `!true` / `1 == 2` / 短路） |
| `check()` | 新增**预词法**：5 个输入任一 `analyse() != ok` → 直接报违规并返回（不再退回原文判断） |
| `_live_ident`（新） | 正向：`_unparen` → 去 `!!` / `as T` → 必须匹配 `^[A-Za-z_]\w*$` |
| `_arm_value_for`（新） | `when` 臂取值：pattern 位判定改成「`->` 与字面量同行」；值从原始文本取 |
| `_routes_to_map`（新） | A / D2 共用：`(ok, value)` |
| `_when_path_branch`（新） | A / D2 共用：`remapDefaultsIds` 里 `when (path) { … }` 的原始文本（找不到即 ROT） |
| `check_remap_defaults` / `check_voice_group_remap` | 改为调用 `_routes_to_map`（删掉各自的子串判定与逐行切分） |
| `_is_exact_call`（新） | 正向：`rhs` 以 `call` 开头且配平 `)` 就是末尾 |
| `_drops_remap` / `CHAIN_LINK` | **删除**（黑名单式尾链探测整段移除） |
| `check_import_chat_sections` | 尾链判定改成 `not _is_exact_call(rhs, REMAP_SESSION_CALL)` |
| `check_delete_guard` | `stmt_spans` 顶层语句判定 + 归一化逐字等于 `GUARD_NORM` + 守卫须早于 `DELETE_CALL` |
| `GUARD_NORM` / `DELETE_CALL`（新常量） | 归一化守卫形态 / 删除调用锚点 |

## §4 四层证据

- **L1 本门**：真树 `rc=0`（`OK (compact pin remapped, binding remapped, rollup packed + guarded, group run-config restored on same-name merge)`）；
  `--selftest` → `selftest: 55 arms matched (pristine green + 54 targeted mutations)` +
  `BACKUP_REMAP_ROLLUP_SELFTEST=PASS`（臂 32 → 55；22 条见证臂 + 1 条 clean 臂）。
- **L2 反攻（最硬）**：
  - 官方 6 条逐字复攻（`probe/official.py`）→ **5/5 原 FALSIFIED 翻成 CAUGHT、1/1 原 CAUGHT 仍 CAUGHT、
    `restored=True` 全条**。
  - 扩展反攻（`probe/reattack.py`）→ **25/25 OK**：20 条新见证 rc 0→1、3 条已挡见证仍 rc=1、
    2 条 clean 臂仍 rc=0；`probe/reattack-final.json` 留档。
- **L3 全量**：逐门跑 **111/111 道门脚本 rc=0**（`probe/gate_run.sh` + `probe/.done/` 逐门标记）。
  `test_scan.py` 因沙箱 S28（`waitpid`/`flock` 随机 `ENOSYS`）无法单进程跑完，改按函数切片
  （`probe/run_testscan_chunks.py`）覆盖 `main` 里全部 **46 个用例函数**：累计 **344 例 PASS / 0 FAIL**；
  其中 `test_real_repo`（全门真树）全绿，本门两条冻结断言单独复跑亦绿。
  邻门 10/10 rc=0（`backup_streaming_export` / `chat_session_id_binding` / `memory_gate_provider_fields` /
  `memory_ui_wiring` / `provider_import_id_roundtrip` / `session_provider_enabled` / `session_sheet_id_binding` /
  `skill_import_offmain` / `skill_restore_session_cleanup` / `downloads_atomic_update`）。
  `scan.sh` 本体在沙箱里被 S28 打断（`/tmp/...log: Function not implemented` 命中 `set -e`），
  故以「逐门等价集合 + CI 单进程全量」为权威。
- **L4 CI**：见 §5，日志逐条核对通过。

## §5 CI run

- **run #2672** · id `40182098405` · job `build` id `121135110598` · **attempt 1** · **conclusion: success**
- `head_sha` = `19a27f58ad65` = 本地 HEAD（逐字符一致，`MATCH=True`）
- 26 步，唯一 non-success 步 = `Publish to Releases`（**skipped**，main 门控）
- 日志（1,485,149 B）逐条核对：
  - `║  Summary: 112 passed, 0 failed` **恰 1 条**（带框线前缀）
  - `✅ ALL 344 CASES PASS` **恰 1 条**
  - 门头 `[N/112]` **112 个互异编号**
  - 本门两条冻结断言在册：`✅ backup_remap_rollup_check selftest has teeth (BACKUP_REMAP_ROLLUP_SELFTEST=PASS)`、
    `✅ backup_remap_rollup_check real tree green`
  - ` e: ` **0** 处；含 `FAILED` 的 6 行逐条确认**全是 PASSED 用例名子串**
    （`produces FAILED without calling provider` / `PERSISTENCE_FAILED` / `phase FAILED` / `terminal FAILED` /
    `EXECUTION_FAILED` / `legal FAILED terminal`）
- 报告回填后：`git diff --name-only 19a27f58..HEAD` **只含 `fix-reports/FIX-WIDE4-11-backup-remap.md`**
  ⇒ 源码树与 CI 绿树逐字节等价。

## §6 残留（触发条件 / 触发后方案 / 搁置理由）

1. **R1 本地别名路由会误红** —— `_live_ident` 只认裸标识符：`val compactMap = entryIdMap` 后
   `-> compactMap` 判红。
   触发条件：有人在 `remapDefaultsIds` 里把映射先绑局部变量再用。
   触发后方案：`_live_ident` 允许**一跳同函数内别名**（在体区间内找 `val <name> = <ident>`）。
   搁置理由：fail-closed 方向（红而非静默绿），且本波 radius 纪律只修被打穿的轴。
2. **R2 调用被限定会误红** —— `_is_exact_call` 要求右值以 `remapSessionBindingJson(` 开头，
   `ConfigBackup.remapSessionBindingJson(…)` 判红。
   触发条件：该函数迁到对象/伴侣或改名加限定。
   触发后方案：右值先剥可选接收者（`<ident>.` 前缀）再比。
   搁置理由：同上，误红方向。
3. **R3 守卫等价改写会误红** —— 判据锚「归一化后逐字等于 `if (name == ROLLUP_FILE) return false`」：
   `if (ROLLUP_FILE == name)` / `require(name != ROLLUP_FILE)` / `when (name) { … }` 判红。
   触发条件：守卫被重构。
   触发后方案：改成「条件蕴含 `name != ROLLUP_FILE` ∧ 体内 `return false`」的结构判据（`enclosing_conditions`）。
   搁置理由：需要一套条件语义，超出本支 radius。
4. **R4 删除点被抽取后 order 判据静默失效** —— 顺序判据锚字面 `.delete()`；删除改走 `removeFile(name)`
   后 `delete_at = -1`，只剩「守卫存在」。
   触发条件：`deleteFile` 的删除动作被提取成 helper。
   触发后方案：锚到「调用任一删除 API 的语句」派生集合（`.delete()` / `.deleteOnExit()` / `removeFile(` /
   `Files.delete`），派生不出即 fail-closed。
   搁置理由：latent（当前一行即删，可达性为零）。
5. **R5 预词法报错不带行号** —— 违规行号固定 0（`analyse` 只回 offset 文本）。
   触发条件：真出现一次词法失败需要定位。
   触发后方案：把 `reason` 里的 `offset N` 转成 `line_of(src, N)`。
   搁置理由：纯可读性，不影响判决方向。
6. **R6 `_mask` 把不可闭合的**片段**整段抹白** —— 片段级切片若落在字面量中间，该片段无结构（红）。
   触发条件：新判据在字面量内部切片。
   触发后方案：切片前用 `mask_strings` 对齐边界。
   搁置理由：当前切片都在 `)`/`,`/`}` 深度 0 边界上（fail-closed 方向）。

## §7 注册项（主会话并集收口时据此注册，逐字）

| 门脚本 | selftest 标记串（`--selftest` 收尾行，逐字） | 臂数 |
|---|---|---|
| `backup_remap_rollup_check.py` | `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` | 55 |

> **不需要新增注册**：该门已在 `test_scan.py` 注册，标记串**一字未改**；臂数 32→55，
> 但标记串不含臂数（`selftest: NN arms matched` 是独立行），冻结面零改动。
> 若主会话要在台账里体现臂数变化，请只写在报告/credibility 层，**不要动标记串**。

## §8 复现步骤

```sh
# 门（真树 + 自证臂）
python3 scripts/scan/backup_remap_rollup_check.py .
python3 scripts/scan/backup_remap_rollup_check.py --selftest .

# 官方 6 条见证逐字复攻（5 条 FALSIFIED 必须翻 CAUGHT，restore 必须 True）
python3 probe/official.py .

# 扩展反攻（20 新见证 + 3 已挡 + 2 clean）
python3 probe/reattack.py .

# 全量门（沙箱里 scan.sh 会被 S28 ENOSYS 打断，逐门跑等价集合；`--root` 形态自动回退）
sh probe/gate_run.sh . 1 111

# test_scan 夹具（单进程跑不完时按函数切片，累计 344 例）
CHUNK_START=0 CHUNK_END=12 python3 probe/run_testscan_chunks.py

# CI 判读（token 只读 env，不打印）
python3 probe/ci_read.py fix/wide4-11-backup-remap --head $(git rev-parse HEAD)
```
