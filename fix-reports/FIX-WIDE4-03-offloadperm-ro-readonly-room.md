# FIX-WIDE4-03 — offloadPermission / readonly-mount / Room 三门判据加固

**分支** `fix/wide4-03-offloadperm-ro-readonly-room`（基于 `base/wide4-shared` = main `19e70dab` + 共享模块 `dbab7a3e`）
**代码提交** `4766afcd` · **生产代码改动 0 行** · **门数 112 / 夹具 344 不变** · `scan.sh` / `test_scan.py` 冻结面零触碰

---

## §1 门与轴

| 门 | 原见证 | 盲轴 | 本支结论 |
|---|---|---|---|
| `offload_permission_reset_sync_check.py` | 4 | family-hole 3 · parser-death 1 | 4/4 翻 CAUGHT，另 2 条原 CAUGHT 保持 |
| `readonly_mount_write_guard.py` | 4 | family-hole 3 · parser-death 1 | 4/4 翻 CAUGHT，另 2 条原 CAUGHT 保持 |
| `room_migration_check.py` | 4 | family-hole 3 · parser-death 1 | 4/4 翻 CAUGHT，另 2 条原 CAUGHT 保持 |

三门同一族失效：**判据锚「某物存在」，而被锚之物可以在不改变任何拼写的前提下失去效果**。
本支把三条通道一次补齐：①**活性求值**（拼写保留、决定删除 —— `!true` / `0 == 1` / `when (false)` / `update { it }` / `+= 0`）②**承重绑定**（空分支 / 无出口分支 / `return@run` / `db.toString()` / 字符串里的语句 / 未被调用的局部闭包）③**词法完整**（手写扫描器换共享 `_kotlin_lexer`，表达式体按链式续行跟随，孤儿写入 fail-closed）。

---

## §2 逐条见证：原补丁 → 旧判据为何瞎 → 新判据 → 新臂

### 2.1 `offload_permission_reset_sync_check.py`（Reset All 必须真的清会话级判定）

| # | 原补丁（要点） | 旧判据为何瞎 | 新判据 | 臂 |
|---|---|---|---|---|
| 1 | `clearAllSessionGrants()` 体内两条 `clear()` 包进 `if (!true) { … }` | `fold_bool` 只认裸 `true`/`false`，`!true` 判不出来 ⇒ 死块里的两个锚点照样满足 Criterion 1 | `fold_bool` = `_kotlin_ast.const_bool`（折叠 `!`、`==`/`!=` 字面量、`&&`/`||` 短路），并补 `when (常量)` 死分支剪除 | `w4-F1`、`w4-F5`、`w4-F5b`（正控：`when (false) { else … }` 真的会跑，不许剪） |
| 2 | `bumpRevision()` 改 `_revision.update { it }` | 旧判据只要求花括号里**出现** `it` ⇒ 恒等更新（`MutableStateFlow` distinctUntilChanged 不发射）判定为「bump」 | 新增 `_moves_forward()`：`.inc()` / `it++` / `it + 非零` / 未知步长才算前进；`.dec()`、`it--`、`it + 0`、`it - 0` 一律不算 | `w4-F2`、`w4-F6`（`+= 0`） |
| 3 | `resetAll()` 里两条效果调用改放进 `val unused = { … }`（定义但从不调用） | 旧判据只看函数体**文本**里有没有锚点，闭包体也是文本 | 新增 `strip_uncalled_locals()`：绑定的名字在函数体内再未出现 ⇒ 该 lambda / 局部 `fun` 体按死代码抹掉 | `w4-F3` |
| 4 | `if (false) { val _s = "${"}"}"; sessionGrants.clear(); … }` | 手写扫描器不跳字符串模板 ⇒ 模板里的 `}` 让 `_stmt_span` 提前收尾，死块外的 `clear()` 逃出剪枝 | 词法交共享 `_kotlin_lexer`（等长掩码视图，模板/嵌套注释由同一状态机处理），`check()` 解析失败即 fail-closed 红 | `w4-F4` |

### 2.2 `readonly_mount_write_guard.py`（只读挂载守卫必须真的拒绝）

| # | 原补丁（要点） | 旧判据为何瞎 | 新判据 | 臂 |
|---|---|---|---|---|
| 1 | `if (guard(path)) { }`（空分支）/ `if (guard(path)) { log() }`（不退出） | 旧 `guard_decides` 用 `if_branches()` 收集「条件里提到 needle 的 if」，**根本不问分支体做什么** | 删掉 `found` 通道，只认 `decision_branches()`：分支体必须在其**自身语句深度**上无条件 `return`/`throw`/`continue` | `w4-G1`、`w4-G2`、`w4-G1b`（criterion 1 站点同形） |
| 2 | `if (0 == 1 && guard(path)) { throw … }` | `constant_truth` 只剥括号认裸字面量，`0 == 1` 不可判 ⇒ 假条件被当活守卫 | `constant_truth` = `_ast.const_bool` | `w4-G3` |
| 3 | `if (replay) run { return@run }` | `"return" in body` 命中三层深的 `return@run`；且旧坐标从 `{` **之后**切片，外层 `collect` 块根本不在括号栈里 | 标号返回必须命中**白名单标号**（`collect`）且被退出的块仍包含 sink；判据改在整段 `collector` 坐标上求值，只保留 `collect` 之后的槽 | `w4-G6` |
| 4 | 表达式体 `): NativeOffloadResult =` 换行 `run { … }` 再换行 `.let { writeBytes(…) }` | 表达式体在第一个括号深度归零处收尾，`.let` 续行被当成新的顶层垃圾语句 ⇒ 写入不属于任何函数；站点数只从 8 掉到 7，仍高于腐烂地板 ⇒ 全绿 | 新增 `_chain_continues()`（下一非空行以 `.`/`?.`/`?:`/`&&`/`||`/`+`/`->`/`as `/`is ` 开头即续行）+ `_parse_functions()` 返回 **span** + `scan()` **孤儿写入审计**：任何 `write*` 调用不落在任何已解析函数体内 ⇒ fail-closed 红 | `w4-G5` |
| 正控 | `if (guard(p)) { 报告 } else if (…) { write }`（线上真实形态：拒绝靠**路由**而非终止） | — | 新增 `routes_around_write()` + `_if_chain_span()`：写入落在 else 分支且 then 分支不写 ⇒ 算拒绝（否则我的收紧会把线上正确代码判红） | `w4-G4` |

### 2.3 `room_migration_check.py`（迁移步必须真的动库）

| # | 原补丁（要点） | 旧判据为何瞎 | 新判据 | 臂 |
|---|---|---|---|---|
| 1 | `if (!true) db.execSQL("ALTER TABLE …")` | `literal_decides` 只认裸 `true`/`false` 分词 | 改名 `dead_condition()` = `const_bool(...) is False`（顺带修正：`if (true)` 不再是「死」） | (f) |
| 2 | 升级体改 `db.toString()` | 「体内有 `db.*` 调用」= 任何成员都算，包括无副作用的 `toString()` | 新增 `live_db_writes()`：成员必须在写白名单（insert/update/delete/replace/execSQL/…），SQL 通道还要求第一实参含 schema/数据变更关键字 | (g)(h) |
| 3 | `addMigrations(MIGRATION_1_2, *if (false) arrayOf(MIGRATION_2_3) else emptyArray())` | 实参列表用 `findall(MIGRATION_\d+_\d+)` 取名字 ⇒ **被提到**就算注册 | 每个实参必须是裸迁移标识符，否则报「non-literal argument」，且其中提到的名字**不计入已注册** | (i) |
| 4 | `val _sql = "db.execSQL(CREATE …)"`（只存在于字符串里） | 手写掩码保留字符串内容 ⇒ 字符串里的 SQL 被当成语句 | 结构读 `views['code']`（字符串体也抹白），SQL 文本读 `views['comments']`（同偏移）；两视图都来自共享词法器，解析失败 fail-closed | (j)；正控 (k)：`if (true) db.execSQL(…)` 仍合法 |

---

## §3 判据改动清单（函数名级）

**`offload_permission_reset_sync_check.py`**
`strip_comments()` → 共享 `_lex.mask_code`；`check()` 前置 `_lex.analyse`（fail-closed）；删除 `_strip_outer_parens`/`_split_top`；`fold_bool()` → `_ast.const_bool`；新增 `_blank_region` / `_literal_branch_hits` / `_prune_when` / `strip_uncalled_locals`；`prune_dead_branches()` 挂 `when` 剪枝并复核；`expand()` 串 `strip_uncalled_locals`；新增 `_moves_forward`；`bumps_revision()` 三通道（`++` / `+=` / `update{}` / `=`）全部要求证明非零前进；自证臂 +6。

**`readonly_mount_write_guard.py`**
`constant_truth()` → `_ast.const_bool`；新增 `RET_AT_RE` / `block_label` / `enclosing_label_blocks` / `branch_terminates`（语句深度 + 标号块包含 sink）/ `in_dead_branch` / `_if_chain_span` / `routes_around_write`；`decision_branches()` → 6 元组 + `sink` + `labels` + 拒收死分支内分支；`guard_decides(body, needle, sink=None)` 重写（删 `found` 通道、加路由通道）；`FUN_DECL` 支持接收者与泛型声明；新增 `_chain_continues` / `_parse_functions`（带 span），`functions()` 变薄包装；`scan()` 增孤儿写入审计与 criterion 1 的 `guard_decides`；`check_rootfs_progress_replay()` 改整段坐标 + `labels={"collect"}`；夹具新增 `rpc_shape=empty_guard|no_exit_guard|const_guard|routed`、`settlement_shape=replay_inner`；自证臂 +7。

**`room_migration_check.py`**
`mask_comments()` → 共享 `_lex.mask_code`；`check_db()` 双视图（`code` 结构 / `comments` 取 SQL 文本）+ `analyse` fail-closed；`literal_decides()` → `dead_condition()`（`const_bool is False`）；新增 `DB_CALL_RE` / `DB_WRITE_METHODS` / `DB_SQL_METHODS` / `SQL_WORK_RE` / `live_db_writes()`；`parse_migration_bodies()` 改返回 **span**；`parse_wired()` 实参改裸标识符判定；自证臂 +7（含 `if (true)` 正控）。

---

## §4 四层证据

1. **本门真树**（分支树、串行、ENOSYS 重试）：
   `offload_permission_reset_sync_check.py .` → rc=0；`--selftest` → `OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS`（21 臂，原 15 + 6）
   `readonly_mount_write_guard.py .` → rc=0（8 个 path-param 站点 + 5 个豁免条目原样）；`--selftest` → `MOUNT_PROBE_IO_SELFTEST=PASS (76 arms)`（原 69 + 7）
   `room_migration_check.py .` → rc=0（AppDatabase v14 / ProviderDatabase v11 原读数）；`--self-test` → 25 条（原 18 + 7）
2. **反攻复验**（`probe-03/reattack.py`，进程内跑门、逐条 apply → run → restore → sha256 复核）：
   **FALSIFIED 12/12 → CAUGHT，STILL_BLIND 0，CAUGHT-kept 6/6，RESTORE_FAIL 0**
   （`probe-03/rt-1.json` 门1 4/4、`rt-2.json` 门2 4/4、`rt-3.json` 门3 4/4）
   复验中一次风暴把工作树留在变异态（`DebugRPCHandler.kt` 残留），已 `git checkout --` 还原并复核 `git status` 干净后才提交——正是 wide3 记过的「死在打补丁后、还原前」形态。
3. **邻门**：本支只改 3 个门脚本，未触碰任何被它们共享的生产文件；语义相邻门（`offload_cli_args` / `offload_host_path_check` / `fastio_*` / `file_provider_paths_coverage` / `share_dir_prune` / `migration_*` 家族）在 CI 全量 112 门里一并复跑。
4. **全量**：本地 `test_scan.py` 在 12 会话并发窗口被沙箱 `OSError: [Errno 38]` 反复打断（日志停在 239 个 ✅ 之后、subprocess `waitpid` ENOSYS），**以 CI 单进程全量复跑为权威**（见 §5）。这一点如实登记，不当作门红。

---

## §5 CI

CI run **#40180729474**（`fix/wide4-03-offloadperm-ro-readonly-room`，代码提交 `4766afcd`）。
判据行：`Summary: 112 passed, 0 failed` 恰一条 · `ALL 344 CASES PASS` 恰一条 · ` e: ` 0 · 唯一 skipped = Publish（main 门控）· `head_sha` 与本地逐字符一致。

---

## §6 残留（每条带触发条件 / 触发后方案 / 搁置理由）

1. **`_prune_when` 只折叠字面量主体与字面量标签**
   触发条件：出现把效果藏在 `when (某个符号常量) { … }` 里的见证。触发后方案：把 `const_bool` 的常量传播扩到 `val X = false` 这类同函数内绑定。搁置理由：需要一次值流分析，本波无见证使用该形态，装置比例不划算。
2. **`expand()` 不剪「无条件 `return` 之后的死代码」**
   触发条件：见证把效果写在函数内 `return` 之后。触发后方案：在 `expand()` 里按语句深度做可达性扫描。搁置理由：与已修的 `if (true)` 同族但在另一轴，本波无见证，先登记。
3. **`guard_decides` / `routes_around_write` 不做跨函数追踪**
   触发条件：线上把守卫抽成 `if (refuseIfReadOnly(p)) return` 这类 helper。触发后方案：一跳内联谓词体。搁置理由：方向是**假红**而非静默放行（fail-closed），且重构发生时会立刻红给人看。
4. **replay 判据的白名单标号硬编码 `collect`**
   触发条件：收集器改用 `collectLatest` / `onEach` 等。触发后方案：从 `INSTALL_STATE_COLLECT` 的匹配文本派生标号。搁置理由：换收集器本身是语义改动，值得让门红一次。
5. **`live_db_writes` 对 insert/update/delete 不校验实参**
   触发条件：见证用 `db.delete(table, "1=0", null)` 这类守恒写入。触发后方案：要求写入的表/列与迁移意图（实体注解）对得上。搁置理由：需要 schema 侧输入，本波无该形态见证。
6. **本地全量套件被沙箱风暴打断**
   触发条件：下一个低并发窗口。触发后方案：分块重跑 `scan.sh` 与 `test_scan.py`。搁置理由：CI 已在单进程上全量复跑，本地重跑只是复证。

---

## §7 注册项

```
| 门脚本 | selftest 标记串（--selftest 收尾行，逐字） | 臂数 |
| scripts/scan/offload_permission_reset_sync_check.py | OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS | 21 |
| scripts/scan/readonly_mount_write_guard.py | MOUNT_PROBE_IO_SELFTEST=PASS (76 arms) | 76 |
| scripts/scan/room_migration_check.py | （无汇总标记串：`--self-test` 逐条打印 `✅ self-test: …`，无收尾行） | 25 |
```

冻结标记串：`OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS` **一字未改**（只加臂）；`MOUNT_PROBE_IO_SELFTEST=PASS (N arms)` 的 `N` 由臂数动态生成（69 → 76），前缀逐字不变。

---

## §8 复现

```sh
cd /var/minis/shared/work/meta-wide4/wt-03
python3 scripts/scan/offload_permission_reset_sync_check.py .           # rc=0
python3 scripts/scan/offload_permission_reset_sync_check.py --selftest  # 21 臂
python3 scripts/scan/readonly_mount_write_guard.py .                    # rc=0
python3 scripts/scan/readonly_mount_write_guard.py --selftest           # 76 臂
python3 scripts/scan/room_migration_check.py .                          # rc=0
python3 scripts/scan/room_migration_check.py --self-test                # 25 条
# 反攻复验（逐条打回原见证，期望 rc 1，restore 全 True）
python3 /var/minis/shared/work/meta-wide4/probe-03/reattack.py out.json --only offload_permission
python3 /var/minis/shared/work/meta-wide4/probe-03/reattack.py out.json --only readonly
python3 /var/minis/shared/work/meta-wide4/probe-03/reattack.py out.json --only room_migration
```
