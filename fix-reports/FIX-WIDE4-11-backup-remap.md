# FIX-WIDE4-11 — `backup_remap_rollup_check.py`（5 条盲 + 1 条已挡，23 条新臂）

**分支** `fix/wide4-11-backup-remap`（基于本波前置提交 `dbab7a3e`：共享 Kotlin 词法/语法原语）
**改动面**：`scripts/scan/backup_remap_rollup_check.py` 一个文件（+339/−171）
**生产代码 0 行改动**，`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` 零触碰；
门数仍 112、夹具仍 344；无新建门。

---

## ① 门与判据

本门守四件事（`closed`/`remapped`/`packed+guarded`/`group run-config`），此前 12 条判据里
被外部审计打穿 5 条，轴线分成三类：

| id | 判据 | 旧形态 | 轴 |
|---|---|---|---|
| A | `check_remap_defaults`（`runtime.compactModelEntryId` → `entryIdMap`） | `"entryIdMap" in _live_value(value)` | family-hole |
| B | `check_import_chat_sections`（`modelBinding = remapSessionBindingJson(…)`） | `_drops_remap` 猜尾链是否丢值 | family-hole |
| D2 | `check_voice_group_remap`（两条 voice group 路径 → `groupIdMap`） | 与 A 同块却仍用子串 + 逐行切 `//` | family-hole |
| C3 | `check_delete_guard`（`deleteFile` 拒绝删 `ROLLUP_FILE`） | `ROLLUP_GUARD in code_only(body)` | family-hole + order |
| C3' | `code_only` / `strip_kotlin_comments` / `_mask` | 手写词法器（首个 `*/` 收尾） | parser-death |

## ② 失效轴（本支 5 条被证伪，归三类）

1. **family-hole（4 条：A / B / D2 / C3）** —— 上一轮把「子串存在性」升级成「针对上一批样本
   的单点形状黑名单」，判据仍然问「这段文字里出现过吗」。于是同族换一种拼写就穿：
   `entryIdMap.filter { false }` / `entryIdMap.let { emptyMap() }` / `if (1 == 2) …` /
   `.let { val _unused = it; null }` / `.takeIf { it == "never" }` / `if (false) { guard }`。
   **共同形态 = 保留拼写、删掉决定。**
2. **asymmetry（D2）** —— 同一个 `when (path)` 块里，A 臂已被升级成活性判据，D2 臂还是子串
   判据。攻击者只要挑没被修的那条臂。**同族缺口必须同一帧修完**，否则「修了 1 处漏第 2 处」。
3. **parser-death（1 条）** —— `strip_kotlin_comments` 在自己的 `/*` 扫描里找**第一个** `*/`，
   而 Kotlin 的块注释是**可嵌套**的：`/* outer /* inner */ <code> */` 会在 `inner */` 处收尾，
   把 `<code>` 交回「活代码」视图。同一段手写扫描器还不认识 `'"'`、`"""`、`${…}`、`` ` ``。

## ③ 逐条见证（官方 6 条，逐字 find/replace）

| # | criterion | 原判决 | 修复后 | 判据缺陷 | 新判据 |
|---|---|---|---|---|---|
| 1 | A | FALSIFIED | **CAUGHT** | `"entryIdMap" in …` 是**子串**判定，`.filter { false }` 名字全留 | 正向判定：常量折叠后的 LIVE 值**必须恰为一个标识符**（`_live_ident`） |
| 2 | B | FALSIFIED | **CAUGHT** | `_drops_remap` 黑名单猜尾链（找 `it`），`val _unused = it; null` 骗过它 | 正向判定：右值**必须就是**该调用的结果（`_is_exact_call`，配平 `)` 即右值末尾） |
| 3 | D2 | FALSIFIED | **CAUGHT** | 与 A 同块却仍是子串 + 逐行 `//` 切分 | 与 A **同一个 helper**（`_routes_to_map`），结构上无法再次分叉 |
| 4 | C3 | FALSIFIED | **CAUGHT** | 只问守卫「拼写出现过」，`if (false) { … }` 外层保留全部字符 | 结构判定：守卫必须是 `deleteFile` 的**顶层语句**（任何外层 `if`/lambda/`when` 臂都会让它变深）**且早于 `.delete()`** |
| 5 | C3' | FALSIFIED | **CAUGHT** | 手写剥注释在**嵌套**块注释处提前收尾 | 全部视图改由共享 `_kotlin_lexer` 提供；解析失败**fail-closed** |
| 6 | A（原 CAUGHT） | CAUGHT | **CAUGHT** | —（回归保护） | 同上 |

**额外覆盖（本支判断的同族形态，非官方见证）**：`!true` / `1 == 2` / `json == json`（恒真）/
`when { false -> … }`（`_live_value` 从不折叠 `when`）/ `entryIdMap.takeIf { false } ?: emptyMap()` /
`if (json != null) entryIdMap else emptyMap()`（**运行时**分支同样会交出空表）/
`.let { s.optString("modelBinding") }` / `?: null` / `if (true) { guard }` /
`'"'` 字符字面量 / `"""` 原始字符串把守卫吞掉。

## ④ 修法（阶梯停在第 2 级：复用仓内已有原语，不新建装置）

1. **`_live_ident(expr)`** —— 折叠后 `_unparen` + 去 `!!`/`as T`，**必须匹配 `^[A-Za-z_]\w*$`**
   才认路由。等价白名单而不是恒假黑名单：任何「拼写留着、决定删掉」的形态（链式/`when`/恒真/
   死分支）自动红。
2. **`_is_exact_call(rhs, call)`** —— `rhs` 必须以调用开头、配平 `)` 即末尾；尾随 `.let/.run/
   .also/.apply/.takeIf ?: if…else` 一律红。删掉 `CHAIN_LINK` / `_drops_remap` 整段黑名单。
3. **`_arm_value_for` / `_routes_to_map`** —— `when` 臂取值抽成一个 helper，A 与 D2 共用；
   pattern 位判定改成「`->` 与字面量同行」。
4. **`check_delete_guard`** —— `function_body` 派生的体区间 + `stmt_spans`（深度 0 换行边界），
   归一化空白后**逐字等于**守卫形态才算数；再校验守卫位置早于 `.delete()`。
5. **词法面**：`strip_kotlin_comments` → `mask_comments`、`code_only` → `mask_comments`、
   `_mask` → `mask_code`；`_truth` → 共享 `const_bool`（短路语义）。`check()` 开头对 5 个输入
   **预词法**，任一 `analyse() != ok` 直接报违规（不再退回原文判断）。
6. **22 条官方/同族见证 + 1 条 clean 臂固化成 `--selftest` 臂**（`w4-` 前缀，期望恰红自己那条判据；
   clean 臂 = `entryIdMap as Map<String,String>` 必须绿）。臂数 32 → **55**。

## ⑤ 四层证据

- **L1 本门**：真树 `python3 scripts/scan/backup_remap_rollup_check.py .` → `rc=0`
  （`OK (compact pin remapped, binding remapped, rollup packed + guarded, group run-config restored on same-name merge)`）；
  `--selftest` → `selftest: 55 arms matched (pristine green + 54 targeted mutations)` +
  `BACKUP_REMAP_ROLLUP_SELFTEST=PASS`（**冻结标记串逐字未动**）。
- **L2 反攻（最硬）**：
  - 官方 6 条逐字复攻 `probe/official.py` → **5/5 原 FALSIFIED 翻成 CAUGHT、1/1 原 CAUGHT 仍 CAUGHT、restore 全 True**。
  - 扩展反攻 `probe/reattack.py` → **25/25 OK**（20 条新见证 rc 0→1、3 条已挡见证仍 rc=1、2 条 clean 臂仍 rc=0），
    `restored=True` 全条，`probe/reattack-final.json` 留档。
- **L3 全量**：`probe/gate_run.sh` 逐门跑 **111/111 道门脚本 rc=0**（`probe/.done/` 逐门标记留档）。
  `test_scan.py` 因沙箱 S28（`waitpid`/`flock` 随机 `ENOSYS`）无法单进程跑完，改按函数切片
  （`probe/run_testscan_chunks.py`）覆盖 main 里全部 **46 个用例函数**：累计 **344 例 PASS / 0 FAIL**，
  其中 `test_real_repo`（全门真树）全绿、本门的冻结断言两条（`selftest has teeth` / `real tree green`）单独复跑亦绿。
  邻门 10/10 rc=0（`backup_streaming_export` / `chat_session_id_binding` / `memory_gate_provider_fields` /
  `memory_ui_wiring` / `provider_import_id_roundtrip` / `session_provider_enabled` / `session_sheet_id_binding` /
  `skill_import_offmain` / `skill_restore_session_cleanup` / `downloads_atomic_update`）。
- **L4 CI**：见 ⑥（CI 日志逐条核对：`Summary: 112 passed, 0 failed` 恰一条、`ALL 344` 恰一条、
  ` e: ` 0、唯一 skipped = Publish、本门标记串在册）。

## ⑥ CI run

- **run #2672** · id `40182098405` · job `build` id `121135110598` · **attempt 1** · **conclusion: success**
- `head_sha` = `19a27f58ad65`，与本地 HEAD **逐字符一致**（`MATCH=True`）
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
- 报告回填后 HEAD 与 CI 绿 sha 的关系：`git diff --name-only 19a27f58..HEAD` **只含 `fix-reports/FIX-WIDE4-11-backup-remap.md`**
  ⇒ 源码树与 CI 绿树逐字节等价。

## ⑦ 残留（每条带触发条件 / 触发后方案 / 搁置理由）

1. **R1 本地别名路由会误红** —— `_live_ident` 只认裸标识符：`val compactMap = entryIdMap` 后
   `-> compactMap` 判红。
   触发条件：有人在 `remapDefaultsIds` 里把映射先绑局部变量再用。
   触发后方案：`_live_ident` 允许**一跳同函数内别名**（在体区间内找 `val <name> = <ident>`）。
   搁置理由：当前是 fail-closed 方向（红而非静默绿），且本波 radius 纪律只修被打穿的轴。
2. **R2 调用被限定会误红** —— `_is_exact_call` 要求右值以 `remapSessionBindingJson(` 开头：
   写成 `ConfigBackup.remapSessionBindingJson(…)` 判红。
   触发条件：该函数被移到对象/伴侣或改名加限定。
   触发后方案：右值先剥可选接收者（`<ident>.` 前缀）再比。
   搁置理由：同上，误红方向。
3. **R3 守卫等价改写会误红** —— 判据锚「归一化后逐字等于 `if (name == ROLLUP_FILE) return false`」：
   `if (ROLLUP_FILE == name)` / `require(name != ROLLUP_FILE)` / `when (name) { ROLLUP_FILE -> return false }` 判红。
   触发条件：守卫被重构。
   触发后方案：改成「条件蕴含 `name != ROLLUP_FILE` ∧ 体含 `return false`」的结构判据（需 `enclosing_conditions`）。
   搁置理由：需要为等价形态建立一套条件语义，超出本支 radius。
4. **R4 删除点被抽取后 order 判据静默失效** —— 顺序判据锚字面 `.delete()`：
   删除改走 `removeFile(name)` 后 `delete_at = -1`，判据只剩「守卫存在」。
   触发条件：`deleteFile` 的删除动作被提取成 helper。
   触发后方案：把顺序判据锚到「调用任一删除 API 的语句」派生集合（`.delete()` / `.deleteOnExit()` /
   `removeFile(` / `Files.delete`），派生不出即 fail-closed。
   搁置理由：当前该函数一行即删除，属 P2 latent（可达性目前为零）。
5. **R5 预词法 fail-closed 的报错不带行号** —— 违规行号固定 0（`analyse` 只回 offset 文本）。
   触发条件：真出现一次词法失败，需要定位。
   触发后方案：把 `reason` 里的 `offset N` 转成 `line_of(src, N)`。
   搁置理由：纯可读性，不影响判决方向。
6. **R6 `_mask` 对不可闭合的切片整段抹白** —— 片段级（非整文件）切片若落在字面量中间，
   该片段被判为无结构（红）。
   触发条件：新判据在字面量内部切片。
   触发后方案：切片前用 `mask_strings` 先对齐边界。
   搁置理由：当前所有切片都在 `)`/`,`/`}` 深度 0 边界上（fail-closed 方向）。

## ⑧ 复现命令

```sh
# 门
python3 scripts/scan/backup_remap_rollup_check.py .
python3 scripts/scan/backup_remap_rollup_check.py --selftest .

# 官方 6 条见证逐字复攻（5 条 FALSIFIED 必须翻 CAUGHT，restore 必须 True）
python3 probe/official.py .

# 扩展反攻（20 新见证 + 3 已挡 + 2 clean）
python3 probe/reattack.py .

# 全量门（沙箱里 scan.sh 会被 S28 ENOSYS 打断，逐门跑等价集合）
sh probe/gate_run.sh . 1 111

# test_scan 夹具（单进程跑不完时切片跑，累计 344 例）
CHUNK_START=0 CHUNK_END=12 python3 probe/run_testscan_chunks.py
```

## ⑦-注册项（供主会话并集收口，逐字）

| 门脚本 | selftest 标记串（`--selftest` 收尾行，逐字） | 臂数 |
|---|---|---|
| `backup_remap_rollup_check.py` | `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` | 55 |

> **无需新增注册**：该门已在 `test_scan.py` 注册，标记串**一字未改**；臂数从 32 变 55，
> 但标记串不含臂数（`selftest: NN arms matched` 是独立行），故冻结面不需要改动。
> 若主会话希望臂数可见，可只在报告层记录，不要改标记串。
