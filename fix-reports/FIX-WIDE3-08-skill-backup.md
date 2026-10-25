# FIX-WIDE3-08 — skill/backup 四门判据加固（12/12 原见证反攻 CAUGHT）

- 分支：`fix/meta-wide3-08-skill-backup`
- 基线：`6b6c918fae6c338e3091d0d3870af3cad9e2b881`（main）
- 门改动提交：`892474eb`
- 范围：**只改 4 道门脚本**（`scripts/scan/*.py`），生产 `.kt` 零改动，`scan.sh` / `test_scan.py` / 其他门的脚本零触碰
- 见证来源：组 B（`/var/minis/shared/work/meta-audit-v5/reports/report-B.md`），12 条已机械复证（打上补丁后旧判据 rc=0）

## ① 门与判据

| 门 | 新判据（一句话） |
|---|---|
| `skill_import_offmain_check.py` | ① 一个调用是否在主线程跑，由**最内层**建立 dispatcher 的块决定（外层 `scope.launch` 不豁免内层 `withContext(Main)`）；② 调用点按 **receiver 身份**认（本文件内 `val x = <obj>` 别名同判）；③ 草稿 `LaunchedEffect` 的清空分支必须落在**活的** `<id> == null` 守卫内，且同一 effect 必须存在可达的 seed 赋值（只清空的 effect 判红） |
| `skill_file_save_result_check.py` | ① `val ok = withContext { … }` 的 `ok` 必须**从** `importFromContent` / `writeSkillFile` 的返回值派生（块尾常量 `true` 判红）；② `enabled = isLoaded` 必须**活**（`|| true` / `mutableStateOf(true)` 判红）；③ 保存成功分支按**语句级**判定，且括号/子串匹配对字符串字面量与注释掩码（字面量里的 `{` `}` 与 `"onBack()"` 不再冒充代码） |
| `backup_remap_rollup_check.py` | ① `when (path)` 分支右侧必须是**活的** `entryIdMap`（`if (false) entryIdMap else emptyMap()` 判红）；② `remapSessionBindingJson(…)` 的结果必须**被消费**（尾链 `.let { null }` 判红）；③ 判定前剥除 `/* … */` 块注释（注释掉的守卫不算守卫） |
| `downloads_atomic_update_check.py` | ① 快照绑定按**读通道**派生（公开只读属性 `downloads.value`、带类型注解的 `val x: T = …` 均识别），且 `update` lambda 实参必须被**真的消费**（`if (false) current else …` 判红）；② 花括号配平对块注释掩码（`apply { /* } */ … }` 不再提前闭合）；③ 写入口按**拼写族**覆盖 `_downloads.tryEmit(` / `.emit(` / `.getAndSet(` 与 `update`（CAS lambda） |

## ② 失效轴（12 条）

| 轴 | 条数 | 门 / 见证 |
|---|---|---|
| `scope` | 1 | skill_import arm1 |
| `dataflow` | 6 | skill_import arm8；skill_file_save arm2、arm6c；backup R1、B；downloads R3 |
| `parser-death` | 3 | skill_file_save arm3；backup C1-C3；downloads R4 |
| `spelling` | 2 | skill_import arm7；downloads R2 |

## ③ 逐条见证（criterion / 轴 / 旧判据为何瞎 / 新判据形态）

| # | 门 · criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|
| 1 | skill_import · `arm1` | scope | 旧栈对调用点问「`any(外层块)` 是 IO 吗」——`viewModelScope.launch { withContext(Dispatchers.Main) { call } }` 被外层 launch 拯救 | `_innermost_dispatch()` 由内向外找**第一个**建立 dispatcher 的块，`_is_io_dispatcher()` 判它；内层 Main 判红，无块可判则 fail-closed |
| 2 | skill_import · `arm8` | dataflow | 只查「effect 的 key 覆盖 remember 的 key」+「块体内出现过 `oauthClientSecret =`」；`if (true || sid == null)` 把清空变成无条件，key 与赋值文本全在 | reset 写点必须被**活条件**包住：回溯包住它的 `if` 条件 → `_tests_null_of(<id 表达式>)` 且 `_literal_decides()` 为假；同时要求 effect 存在**可达 seed**（非字面量赋值），resets 存在却无 id 绑定 → fail-closed |
| 3 | skill_import · `arm7` | spelling | 写死前缀 `re.escape("MCPOAuthStore.")`；`val store = …MCPOAuthStore` + `store.setClientSecret(…)` 不匹配 | `_alias_receivers()` 从本文件 `val/var x = <obj>` 绑定派生别名集合，`x.method(` 与 `<obj>.method(` 同判 |
| 4 | skill_file_save · `arm2` | dataflow | 只查块内**同时出现** `importFromContent(` 与 `writeSkillFile(`；块尾加一行 `true`，`ok` 恒真 | `_value_from_write()`：`val ok` 的右值必须是 write/import 调用链（含 `?:`/`when` 分支），或块尾表达式必须泄漏该调用值；常量/字面量收尾判红 |
| 5 | skill_file_save · `arm6c` | dataflow | `if "enabled = isLoaded" not in seg:` 纯子串；`enabled = isLoaded || true` 前缀仍在 | `_live_gate_flag()`：解析赋值右值，`_literal_decides()` 为真（`|| true`、裸 `true`）判红；旗标声明初始值若被改成常量 `true` 同样判红 |
| 6 | skill_file_save · `arm3` | parser-death | `strip_comments()` 原样保留字符串字面量内部文本 + 只看「`blk` 含子串」与「全文件 `isEditing = false` 出现 1 次」 | 掩码串（`mask_strings`）后再配平/切片；判定改成**语句级**：`onBack()` 出现在 `if (ok)` 之前且未被守卫 → 红；`if (ok)` 块内的字面量文本不再计数 |
| 7 | backup · `R1` | dataflow | `"entryIdMap" in 右侧子串`；`if (false) entryIdMap else emptyMap()` 仍含该子串 | `_live_value()`：解析 `->` 右侧表达式，剥 `if (…) A else B` / `?:` 后取活分支；活分支不是 `entryIdMap`（或整支被常量短路）判红 |
| 8 | backup · `B` | dataflow | 只查 `remapSessionBindingJson(` 出现 + 括号内有 `groupIdMap`/`entryIdMap` 实参；尾部 `.let { null }` 不影响 | `_drops_remap()`：解析调用右括号之后的后缀链（`.let/.run/.also/?:`），后缀把结果改写成 `null`/常量/未重映射原值 → 红；无后缀或被赋值/传参消费 → 绿 |
| 9 | backup · `C1-C3` | parser-death | `code_only()` 只剥 `//` 行注释，不剥 `/* … */`；`/* if (name == ROLLUP_FILE) return false */` 判绿 | `code_only()` 改走 `strip_kotlin_comments()`（块注释 + 行注释 + 字符串感知，保留换行与行号） |
| 10 | downloads · `R3` | dataflow | `SNAPSHOT_BIND` 只认 `_downloads.value`（无类型注解分支），且只判 `current` 是否在 lambda 内出现过（`if (false) current else …` 也算） | 读通道从**属性声明**派生（`_read_receivers`：`val downloads: StateFlow<…> = _downloads.asStateFlow()`），带类型注解的绑定同样识别；`current` 必须被**活路径**消费（`_live_text` 判 `if (false) current else …` 为死分支） |
| 11 | downloads · `R4` | parser-death | `_balanced()` 处理 `"""`/`"`/`'`/`//`，不处理 `/* … */`；`apply { /* } */ value = value.filter{…} }` 在注释里的 `}` 提前闭合 | `_mask()`：块注释内容置空但保留换行，`_balanced()`/`_strip_nested()` 在掩码串上配平；真正的 `value = value.filter{}` 落回 span 内判红 |
| 12 | downloads · `R2` | spelling | 只按 `.value =` / `.update {` 两种写入口计数；`_downloads.tryEmit(...)` / `.emit(...)` / `.getAndSet(...)` 全不认 | `EMIT_RE` 覆盖 `tryEmit(`/`emit(`/`getAndSet(`/`compareAndSet(` 家族；命中时按写入口判定（读通道 + 活值 + 原子性），`updateDownloadEntry` 的 `tryEmit(downloads.value.map{…})` 判红 |

## ④ 修法

1. **统一解析层**（各门自建，互不依赖）：`_mask` / `mask_strings`（字符串 + 块注释置空、保留换行与行号）、`_balanced` / `_match_paren`（配平在掩码串上做）、`_split_top`（顶层切分，括号深度 0 的换行算边界）。
2. **条件活性层**：`_lit_value` / `_truth` / `_literal_decides`（按 `&&`/`||` 顶层切分，**所有**操作数都检查字面量短路——原实现 `parts[1:]` 漏掉第一个操作数，`true || sid == null` 因此判活，本轮四门同时修掉）。
3. **数据流层**：`_alias_receivers`（receiver 别名）、`_read_receivers`（读通道从声明派生）、`_value_from_write`（返回值派生）、`_drops_remap`（尾链改写）、`_subject_writes` / `_id_expressions` / `_enclosing_if_conds`（写点 → 守卫）。
4. **负向臂**（任务纪律 §5「每条新判据自带负向臂」）：
   - skill_import：新增 `W308_FIXTURES` 4 条（arm1 嵌套 Main = RED / arm7 别名 = RED / arm8 死守卫 = RED / arm8 活守卫精度 = CLEAN）
   - skill_file_save：3 条（W1 块尾 `true`、W3 字面量冒充、W2 `|| true`）
   - backup_remap：3 条（M24 死值、M25 尾链丢弃、M26 块注释）
   - downloads：5 条（w3-alias-snap、w3-dead-param、w3-block-comment、w3-try-emit、w3-try-emit-clean）
   - **冻结标记串原样保留**（`SKILL_IMPORT_OFFMAIN_SELFTEST=PASS` / `SKILL_FILE_SAVE_RESULT_SELFTEST=PASS` / `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` / `SELFTEST=PASS (downloads_atomic_update: 4 fixtures)`），`test_scan.py` 的 `marker in out` 断言不受臂数变化影响。

## ⑤ 四层证据

### L1 本门真树 + selftest 臂

```sh
cd /var/minis/shared/work/meta-wide3/wt-08
for g in skill_import_offmain_check skill_file_save_result_check backup_remap_rollup_check downloads_atomic_update_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1; r1=$?
  python3 scripts/scan/$g.py --selftest . >/dev/null 2>&1; r2=$?
  echo "$g tree=$r1 selftest=$r2"
done
```
输出（原样）：
```
skill_import_offmain_check tree=0 selftest=0
skill_file_save_result_check tree=0 selftest=0
backup_remap_rollup_check tree=0 selftest=0
downloads_atomic_update_check tree=0 selftest=0
```
冻结标记串逐条核对（脚本 `.probe_markers.py`，跑完即删）：
```
skill_import_offmain_check       rc=0 marker=OK
skill_file_save_result_check     rc=0 marker=OK
backup_remap_rollup_check        rc=0 marker=OK
downloads_atomic_update_check    rc=0 marker=OK
FROZEN_MARKERS=PASS
```
新增负向臂命中形态（skill_import，原样）：
```
selftest wide3-08 arm1 nested Main dispatcher still runs on Main: RED
selftest wide3-08 arm7 alias receiver on Main                  : RED
selftest wide3-08 arm8 dead reset guard wipes the draft        : RED
selftest wide3-08 arm8 precision: live null guard stays legal  : CLEAN
```

### L2 外部见证反攻（12 条，修复后全部 CAUGHT）

```sh
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates skill_import_offmain_check.py,skill_file_save_result_check.py,backup_remap_rollup_check.py,downloads_atomic_update_check.py \
  --json /tmp/reattack-08-final.json
```
输出（原样）：
```
skill_import_offmain_check.py                 arm1                   CAUGHT       rc=1   scope
skill_import_offmain_check.py                 arm8                   CAUGHT       rc=1   dataflow
skill_import_offmain_check.py                 arm7                   CAUGHT       rc=1   spelling
skill_file_save_result_check.py               arm2                   CAUGHT       rc=1   dataflow
skill_file_save_result_check.py               arm6c (W3)             CAUGHT       rc=1   dataflow
skill_file_save_result_check.py               arm3                   CAUGHT       rc=1   parser-death
backup_remap_rollup_check.py                  R1 (A: remapDefaultsId CAUGHT       rc=1   dataflow
backup_remap_rollup_check.py                  B (importChatSections) CAUGHT       rc=1   dataflow
backup_remap_rollup_check.py                  C1-C3 (distilled memor CAUGHT       rc=1   parser-death
downloads_atomic_update_check.py              R3                     CAUGHT       rc=1   dataflow
downloads_atomic_update_check.py              R4                     CAUGHT       rc=1   parser-death
downloads_atomic_update_check.py              R2                     CAUGHT       rc=1   spelling

CAUGHT=12
RESULT: ALL CAUGHT
```
每条 `restored=true`（补丁恢复后逐字节复原，无反攻污染真实树）。

### L3 邻门（rc=0，共 6 道）

```
skill_restore_session_cleanup_check rc=0
skill_frontmatter_roundtrip_check   rc=0
backup_streaming_export_check       rc=0
compact_entry_pin_migration_check   rc=0
provider_import_id_roundtrip        clean (13 arms)
share_dir_prune                     rc=0（首次撞 S28 ENOSYS，重跑即绿）
```

### L4 全量

本地 `scan.sh` 在这个窗口被沙箱 S28（`python3: Function not implemented`）打断两次，读数 **27 passed / 85 failed 全是进程 spawn 失败**，不是门本身失败——属环境症状（12 支并发施工窗口），如实登记。**权威全量读数以本分支 CI 为准**（见 §6），CI 在干净环境跑同一套 `scan.sh` + `test_scan.py`。

## ⑥ CI run

- 分支：`fix/meta-wide3-08-skill-backup`
- dispatch：`sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --ref fix/meta-wide3-08-skill-backup`
- run id / 结论 / head_sha：见本报告提交后的回填段（`## CI 回填`）。

## ⑦ 残留（三件套）

1. **downloads：`.compareAndSet(expect, update)` 的别名单词形态**（当前 `EMIT_RE` 覆盖 `compareAndSet(` 本名，但不含 `compareAndSet` 经 `java.util.concurrent.atomic` 风格的两参读值语义分析）。触发条件：`BrowserTabPool` 出现 CAS 写法；触发后方案：扩 `EMIT_RE` 并加「两实参都要活」判据；搁置理由：全仓 0 处调用点。
2. **skill_import：跨函数/参数传入的 receiver 别名**（`_alias_receivers` 只扫本文件 `val/var x = <obj>`）。触发条件：出现 `fun f(store: MCPOAuthStore)` 形态；触发后方案：从调用图派生实参；搁置理由：当前全仓 0 处。
3. **skill_import arm8：非 `if` 守卫形态**（`when` / `run` / `takeIf` 包住的 reset）。触发条件：reset 从 `if` 迁到 `when` 分支；触发后方案：`_enclosing_if_conds` 扩展到 `when` 分支条件；搁置理由：当前形态仍是 `if`。
4. **backup：`_truth` 不解析三元/嵌套括号内的字面量短路**（只按顶层 `&&`/`||` 切分）。触发条件：出现 `if (cond) (false) else entryIdMap` 链；触发后方案：接 `_live_value` 递归；搁置理由：`_live_value` 已覆盖 `when` 分支主路径。
5. **skill_file_save：模板字符串 `${…}` 内的真实调用不可见**（掩码把插值内容一并置空）。触发条件：出现把调用写进 `"${…}"` 的代码；触发后方案：模板插值按代码段单独掩码；搁置理由：当前 0 处，且属罕见写法。
6. **环境：本地全量以 CI 替代**（S28 ENOSYS，12 支并发窗口）。触发条件：需要本地全量读数时；触发后方案：错峰（`delay`）+ `gate_run.sh --full` 持锁重试；搁置理由：CI 同套件读数为准。

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-08

# L1 本门 + selftest
for g in skill_import_offmain_check skill_file_save_result_check backup_remap_rollup_check downloads_atomic_update_check; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"
  python3 scripts/scan/$g.py --selftest . ; echo "$g selftest rc=$?"
done

# L2 12 条原见证反攻（期望 CAUGHT=12 / RESULT: ALL CAUGHT）
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates skill_import_offmain_check.py,skill_file_save_result_check.py,backup_remap_rollup_check.py,downloads_atomic_update_check.py \
  --json /tmp/reattack-08-final.json

# L3 邻门
for g in skill_restore_session_cleanup_check skill_frontmatter_roundtrip_check backup_streaming_export_check compact_entry_pin_migration_check provider_import_id_roundtrip share_dir_prune; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"
done

# L4 全量（持锁运行器；沙箱 ENOSYS 时它会重试）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
python3 scripts/scan/test_scan.py
```

## CI 回填（已核实）

- run：**#2653 / id `40161234052`**（`https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40161234052`）
- job：`121073043281`，**attempt 1 success**
- `head_sha` = `d3f0bfb6318f7d66a210ba69b7bff62d7edda8fb` —— 与本地 `git rev-parse HEAD` **逐字符一致**
- 26 步，唯一非 success = `Publish to Releases` **skipped**（main 门控，分支构建符合预期）
- 日志读数（1,480,876 B）逐条核过：
  - `║  Summary: 112 passed, 0 failed` **恰一条**（其余 8 条 Summary 为子套件：28/5/14/36/15/25/10 passed + `0 violation(s) found`，全部 0 failed）
  - `ALL 344 CASES PASS` **恰一条**
  - ` e: ` 命中 **0**（无 Kotlin 编译错）
  - 含 `FAILED` 的 6 行全部是 **PASSED 用例名子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED…`、`ChatAgentTraceObserverSchemaTest > reason PERSISTENCE_FAILED maps…`、`TerminalErrorSurfacedTest > … legal FAILED terminal` 等），无游离失败
  - 四个冻结 selftest 标记串在 CI 日志中**全部命中**：`SKILL_IMPORT_OFFMAIN_SELFTEST=PASS` / `SKILL_FILE_SAVE_RESULT_SELFTEST=PASS` / `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` / `SELFTEST=PASS (downloads_atomic_update: 4 fixtures)`

**结论**：`112 passed, 0 failed` + `ALL 344 CASES PASS` —— 与基线（`6b6c918f`）读数一致，四门加固未改变门总数与夹具总数，未引入回归。CI 绿 = 本次开发的确定性停止节点，停在分支，不合并 main。
