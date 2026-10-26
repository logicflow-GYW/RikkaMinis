# FIX-BATCH6-t03：`filepreview_print_bounded_check.py` + `rss_probe_lock_scope_check.py`

- 工作树：`/var/minis/shared/work/meta-audit-v5/batch6/wt6/t03`
- 分支：`fix/b6-fileprint-rss`
- 基线：`e145ec961232c7898644346ff4bc0c3f70980369`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）
- 门提交：`c29f7b7051a663454d58081f623f17fbc7b1ce0f`
- 改动面：2 个门脚本（+1072 / −475），生产 `.kt` 0 行；`scan.sh` / `test_scan.py` / `_kotlin_*.py` 零触碰；门数 112 / 夹具 344 不变。

## 1. 门与轴

| 门 | 见证数 | 轴分布 | 自测臂 |
|---|---|---|---|
| `rss_probe_lock_scope_check.py` | 6（A10-1..A10-6） | family-hole ×3（A10-1/3/5）· dataflow ×1（A10-2）· parser-death ×2（A10-4/6） | 1 compliant + 4 breakers（保留原判据名）+ 8 witness arms |
| `filepreview_print_bounded_check.py` | 7（01-1..01-7） | dataflow ×2（01-1/5）· family-hole ×4（01-2/3/4/6）· parser-death ×1（01-7） | b05 既有臂 + 7 witness arms（T03 标记） |

两门共症与第五批同族：**判据锚「某拼写/某物存在」，缺陷动的是它的效果**——锁对象被解析后丢弃、有界读取的返回值被丢弃、键/守卫/上限只做子串匹配。修法统一把判据推到**位置、值流、可达性与身份**，底层全部改走共享原语（`mask_code` / `match_bracket` / `const_bool` / `path_feasible` / `value_sources` / `decl_span` / `live_positions`），自写 `strip_comments`、裸花括号计数、死分支字面量白名单全部退役，解析失败 fail-closed。

## 2. 见证原文（file / find / replace / 旧门为何绿）

### rss_probe_lock_scope_check.py

**A10-1（family-hole）** `OffloadRssProbe.kt`
- find: `val d = synchronized(st) { applyAndDecideLocked(st, deltaKb) }`
- replace: `val d = synchronized(Any()) { applyAndDecideLocked(st, deltaKb) }`
- 旧门为何绿：`analyse()` L203-204 写 `for _, block_body in rec_sync:`，`synchronized_blocks()` 解析出的 `lock_expr` 被下划线丢弃，从未校验锁对象。`synchronized(Any())` 每次调用新建监视器 = 零互斥。

**A10-2（dataflow）** `OffloadRssProbe.summary()`
- find: `val snapshot = byHandler.entries.map { (name, st) -> name to synchronized(st) { st.copy() } }`
- replace: `synchronized(this) {}` + `val snapshot = byHandler.entries.map { (name, st) -> name to st }`
- 旧门为何绿：只要求「至少有 1 个 synchronized 块」+ 块外正则 `\bst\s*\.\s*(samples|totalDeltaKb|...)\b`；`name to st` 不带点号，下游用解构名 `s.samples` 访问，正则不匹配 ⇒ 活体引用无锁读全绿。

**A10-3（family-hole）** `applyAndDecideLocked()`
- find: `if (govern) st.totalDeltaKb = 0L`
- replace: `if (govern && false) st.totalDeltaKb = 0L`
- 旧门为何绿：只做子串包含 `"totalDeltaKb = 0L" not in rec_sync_text`，不做条件活性分析 ⇒ `&& false` 的死分支照过。

**A10-4（parser-death）** `record()`
- find: `val d = synchronized(st) { applyAndDecideLocked(st, deltaKb) }`
- replace: 同上一行 + 追加 `st.totalDeltaKb = 0.toLong()`
- 旧门为何绿：块外写入对 `totalDeltaKb` 只枚举 `+=` 与精确拼写 `= 0L`；`= 0.toLong()` 既非 `+=` 也不是 `0L` 字面量，穿透。

**A10-5（family-hole）** `record()`
- find: `val d = synchronized(st) { applyAndDecideLocked(st, deltaKb) }`
- replace: `val d = synchronized(st) { if (false) applyAndDecideLocked(st, deltaKb) else RssDecision(0L, 0, 0L, 0L, false, false, false) }`
- 旧门为何绿：只查 `"applyAndDecideLocked" in rec_sync_text`，不判块内语句可达性 ⇒ 恒假短路照过。

**A10-6（parser-death）** `record()`
- find: `val d = synchronized(st) { applyAndDecideLocked(st, deltaKb) }`
- replace: 同上 + 追加 `val q = '"'; val u = "http://"; st.totalDeltaKb = 0L; st.leaked = true`
- 旧门为何绿：自写 `strip_comments` 用 `re.sub(r"//[^\n]*", "", src)` 且不跟踪字符串状态 ⇒ `"http://"` 里的 `//` 把行尾全部锁外写入抹白；`val q = '"'` 保持双引号奇偶，`function_body` 的花括号配平仍成立。

### filepreview_print_bounded_check.py

**01-1（dataflow）** `FilePreviewScreen.buildPrintHtml`
- find: `val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))`
- replace: `val ignored = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))` + `val raw = java.nio.file.Files.readString(item.file.toPath())`
- 旧门为何绿：只检查两个子串存在 + `.readBytes()` / `bufferedReader(` / `.readText()` / `.readLines()` 黑名单；`Files.readString` 不在名单、有界调用文本仍在体内 ⇒ 全绿，实际整文件进堆。

**01-2（family-hole）** `TextPreview`
- find: `truncated`/`error` 用 `by remember(item.file) { ... }`
- replace: 改为 `by remember(Unit) { ... }`（`content` 保留原键）
- 旧门为何绿：只查子串 `'remember {'`（无参）不存在 + `'by remember(item.file) {'` 存在；`remember(Unit) {` 两者都满足 ⇒ 槽位永不重置的缺陷穿透。

**01-3（family-hole）** `PdfPreview.produceState`
- find: `key2 = item.file,`
- replace: `key2 = item.file.parent,`
- 旧门为何绿：键引用正则 + `k.startswith('item.file.')` 前缀匹配；`parent` 是合法属性链 ⇒ 通过，但同目录切换 PDF 不重启。

**01-4（family-hole）** `onPageFinished` 守卫
- find: `if (printWebView !== view) { ... destroyPrintWebView(view); return }`
- replace: `if ((1 == 2) && printWebView !== view) { ... }`
- 旧门为何绿：`DEAD_GUARD_RE` 只认字面 `false &&` / `true ||` ⇒ `(1 == 2) &&` 恒假守卫照过，旧 WebView 重复弹打印框并泄漏。

**01-5（dataflow）** `LogManagementScreen` 调用点
- find: `val text = readLogShareText(file) ?: return@withContext null`
- replace: `val text = readLogShareText(file, maxChars = Int.MAX_VALUE) ?: return@withContext null`
- 旧门为何绿：`check_log_share` 只验函数定义体内 `FileInputStream(` + cap 令牌，完全不看调用点实参 ⇒ 上限被放大到 2 GiB。

**01-6（family-hole）** `SafImportRead`
- find: `val bytes = readAtMost(input, cap)`
- replace: `val bytes = readAtMost(input, minOf(cap + Long.MAX_VALUE / 2, Long.MAX_VALUE / 2))`
- 旧门为何绿：白名单正则 `r'minOf\(\s*cap\b[^)]*\)'`，`cap + Long.MAX_VALUE / 2` 在 `cap` 词边界后照样匹配 ⇒ 实际上限 4.6 EB。

**01-7（parser-death）** `buildPrintHtml`
- find: `private fun buildPrintHtml(item: FileItem): String {\n    val raw = decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES))`
- replace: 首行插入 `// decodeUtf8Capped(readCapped(item.file, MAX_TEXT_PREVIEW_BYTES)) }`，下一行写 `val raw = java.nio.file.Files.readString(item.file.toPath())`
- 旧门为何绿：`check(src)` 在 `strip_comments` 之前用原始 `src` 调 `grab_body`；注释里的 `}` 提前闭合切片，注释里的有界调用文本喂饱存在性检查，真实函数体被切在切片之外 ⇒ 无界读取隐形。

## 3. 判据改造（旧形态 → 新形态，逐条）

### rss_probe_lock_scope_check.py

| 见证 | 旧判据 | 新判据 |
|---|---|---|
| A10-1 | 解析出 lock_expr 后丢弃 | `lock_is_stable()`：锁对象必须是**类内派生的稳定锁**（类级 `private val x = Any()` / `Object()` / 稳定初始化器），`synchronized(Any())`、局部新建、非类级声明一律红；解不出 fail-closed |
| A10-2 | 「至少一个 synchronized 块」+ 块外 `st.<field>` 正则 | 快照必须是锁内 `copy()`（值流判定）+ **解构别名/ lambda 形参** 对快照字段的访问一律算块外读（`_bare_param_uses` / `_lambda_params` 派生绑定）；傀儡空锁 `synchronized(this) {}` 不构成快照 |
| A10-3 | 子串 `"totalDeltaKb = 0L"` 存在 | 归零写入必须在 `path_feasible` 可达分支上：守卫条件走 `const_bool` 折叠（`govern && false` / `1 == 2` / `!true` 全折），死分支内的归零判红 |
| A10-4 | 块外写入只认 `+=` 和 `= 0L` | 任意直接赋值 / 复合赋值 + **零值等价拼写**（`0L` / `0` / `0.toLong()`）全入判；写入位置与锁块用字符偏移比较（`state_writes` + `_field_writes`） |
| A10-5 | `rec_sync_text` 含调用子串 | 锁内调用必须 `path_feasible`（`if (false)` 短路、else 假值、`while(false)` 全折）+ 决策结果必须被 return / 值消费 |
| A10-6 | 自写 `strip_comments`（不认字符串） | 全部结构分析走共享 `mask_code`（注释 + 字符串/字符字面量内部抹空、长度不变）；`KotlinLexError` → 报违规，不回退原文 |

新增结构层：`mask_or_fail`、`fn_spans`（含重载）、`decl_span`、`synchronized_blocks`（`match_bracket` 配平）、`decision_calls`、`state_writes`、`value_is_zero`、`_map_regions`。真树 3 文件、25 判据 rc=0。

### filepreview_print_bounded_check.py

| 见证 | 旧判据 | 新判据 |
|---|---|---|
| 01-1 | `BOUNDED_READ`/`DECODE_WIRED` 子串 + 4 条黑名单 | `_bounded_value_used()`：有界读取的**值必须被绑定 + 消费 + 返回**（`val ignored` 诱饵判红）；全文件读取 API 家族 `WHOLE_FILE_READS`（`Files.readString` / `Files.readAllBytes` / `.readAllBytes(`）按名字族判红 |
| 01-2 | 无参 `remember {` 子串 + 单个带键记忆存在 | `_remember_violations()` 键集合判据：`TextPreview` 内**每个** remember 的键必须引用文件身份；常量键（`Unit`/`null`/`0`）与无键一律红 |
| 01-3 | 键引用正则 + `item.file.` 前缀 | `_is_file_identity_key()`：键必须落在 `FILE_IDENTITY_PROPS` 白名单（文件本身 / 路径类属性），`parent` / `name` / `length` 等非身份属性判红 |
| 01-4 | `DEAD_GUARD_RE` 字面 `false &&` / `true ||` | 守卫条件走 `const_bool` 折叠（`(1 == 2)` / `!true` / 嵌套恒假全折），且守卫分支必须**真终止**（`_branch_ends_control_flow` 按语句表判，`return@label` 不算） |
| 01-5 | 只验定义体 `FileInputStream(` + cap 令牌 | `check_log_share` 调用点实参溯源：`maxChars = <值>` 必须是常量且 ≤ `LOG_SHARE_CAP_SANITY_CHARS`（1_000_000），超大值 / `Int.MAX_VALUE` 判红；缺参按默认值判 |
| 01-6 | 白名单正则 `minOf\(\s*cap\b[^)]*\)` | `_cap_bound` / `_cap_violation`：cap 实参必须是 `cap` 本身或安全域内算术（字面量 / 常量声明查表 / `minOf`/`min` / 加减，`known_safe` 名），越界常量判红；`minOf(cap + Long.MAX_VALUE/2, ...)` 红 |
| 01-7 | `grab_body` 在未剥注释的原文上配平 | 所有结构分析改走 `mask_code` 视图；函数体用 `fn_spans` / `decl_body` 配平（`mask_code` 下注释与字符串内部为空），解析失败 fail-closed |

其他清理：死常量 `DEAD_GUARD_RE` / `CONTROL_FLOW_END_RE` / `KEYED_REMEMBER` / `BARE_REMEMBER` / `PDF_KEY_SOURCES` / `blank_literals` / `_brace_blocks` 删除；`_if_statements` 返回 `(cond, start, end)`；`_decl_text` / `_block_at` / `_split_args` / `_numeric_value` 新增。

## 4. 四层证据

### L1 本门
- `python3 scripts/scan/rss_probe_lock_scope_check.py --selftest` → `SELFTEST=PASS (5 fixtures)` + `RSS_PROBE_LOCK_SCOPE_SELFTEST=PASS (1 compliant + 4 breakers + 8 witnesses)`，0 FAIL。
- `python3 scripts/scan/filepreview_print_bounded_check.py --selftest` → `FILEPREVIEW_PRINT_BOUNDED_SELFTEST=PASS` + `FILEPREVIEW_PRINT_BOUNDED_T03_SELFTEST=PASS (7 witness arms: bounded-read value flow, remember key set, produceState key identity, satisfiable guard, cap call sites)`，0 FAIL。
- 真树：两门 `python3 scripts/scan/<gate> .` 均 rc=0（rss：`OffloadRssProbe.kt` / `ProviderRssProbe.kt` / `OffloadRssProbeTest` 三文件）。

### L2 反攻（硬验收，官方驱动）
```
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt6/t03 --mode full \
    --only rss_probe_lock_scope_check.py --out probe/t03-post-rss.json
python3 verify_batch5.py --witnesses witnesses-B.json --repo wt6/t03 --mode full \
    --only filepreview_print_bounded_check.py --out probe/t03-post-filepreview.json
```
- rss：`SUMMARY: {'CAUGHT': 6}`，`restored=True` ×6。
- filepreview：`[1/7]..[7/7] -> CAUGHT (rc=1, exact)`，`SUMMARY: {'CAUGHT': 7}`，`restored=True` ×7。
- 红点取证：`probe/t03_red_lines.py` —— 6 条 rss 见证的 FAIL 行分别落在锁身份 / 快照 copy / 归零活性 / 块外写入 / 锁内可达 / 词法视图对应的判据文案上。

### L3 基线对照（旧门 vs 本支新门）
`probe/t03_baseline_vs_branch.py`：同一 13 条见证在「基线旧门」上逐个跑、再在「本支新门」上跑：

```
branch totals: {'BLIND': 0, 'CAUGHT': 13}   restore=True ×13
```
逐条：01-1..01-7 `baseline=BLIND branch=CAUGHT`，A10-1..A10-6 同。

### L4 邻门与全量
- 邻门 7 道 rc=0：`file_uri_parse_guard_check` / `file_preview_url_encode_check` / `offload_host_path_check` / `provider_rpc_symmetry_check` / `i18n_check` / `process_logging_gate` / `parts_json_funnel_check`。
- 逐门等价全量：`probe/t03_scan_all.py`（逐门独立 subprocess + 断点续跑）→ **111/111 rc=0**（`probe/t03_scanall.done`；nonzero-gates: 0）。
- `scan.sh` 门数核对：`scan.sh` 共 112 个门头，其中 111 个是独立脚本调用（110 个 `*_check.py` + 1 个 `scripts/native/crash_fields_host_test.sh`），第 112 个（`[2/112] i18n consistency check`）是 `scan.sh` 内的联内 `python3 -c` 块（无独立脚本文件；仓库里 `i18n_check.py` 存在但 `scan.sh` 不调用它）。逐门驱动覆盖全部 111 个独立脚本门，联内项由 `scan.sh`/CI 覆盖 ⇒ 无门静默漏跑。
- `test_scan.py`：与本支两门直接相关的注册函数 `test_da1016_gates`（含 rss）、`test_da1020_gates`（含 filepreview）、`test_da1017_external_fixtures` 均 PASS；全量 344 用例改逐函数独立进程跑完 **46/46 函数、344 用例全 ✅ / 0 ❌**（`probe/t03_tsfn/*.log`，驱动 `probe/t03_tsfn_resume.py`；单进程整跑在沙箱并发窗口被杀，见 §6 R7）。
- `git status --porcelain src/` = 空（反攻无变异残留）。

## 5. CI

- 分支：`fix/b6-fileprint-rss`；dispatch `build-apk.yml --ref fix/b6-fileprint-rss`。
- **run #2698 / run id `40232108952` / job `121285841311` / attempt 1 → `success`**（created 2026-10-25T20:43:32Z，updated 2026-10-25T21:02:24Z）。
- `head_sha = c29f7b7051a663454d58081f623f17fbc7b1ce0f`，与本地 `git rev-parse HEAD` **逐字符一致**（防并发假绿）。
- 26 个步骤，唯一 `skipped` = `Publish to Releases`（main 门控，符合预期）。
- job 日志 `probe/ci/job-2698.log`（1,485,831 B）逐条判读：
  - 带框线 `Summary: 112 passed, 0 failed` 恰 **1** 条；
  - `ALL 344 CASES PASS` 恰 **1** 条；
  - 编译错误 ` e: ` 计数 **0**；
  - 门头 `[N/112]` **112 个互异**；
  - 含 `FAILED` 的 6 行逐条确认全是 **PASSED 用例名子串**（`slot denied produces FAILED without calling provider` / `reason PERSISTENCE_FAILED maps to persistence_failed` / `phase FAILED maps to Failed` / `terminal FAILED maps to Failed` / `reason EXECUTION_FAILED maps to all_fallbacks_exhausted` / `the fixed tail still reaches a legal FAILED terminal`）；
  - 两门自证在 CI 内真实执行：`rss_probe_lock_scope_check selftest has teeth` ×1、`filepreview_print_bounded_check selftest has teeth` ×1。
- **分支 CI 绿 = 本轮确定性停止节点，未合并 main。**

## 6. 残留（R1..R8，每条带触发条件 / 触发后方案 / 搁置理由）

- **R1（rss 锁对象）** 目前只认「类内派生的稳定锁」（类级 `val` + 稳定初始化器）。跨文件/注入式单例锁（构造器参数注入的 `val lock`）会 fail-closed 红。
  触发条件：真树出现注入式锁（`private val lock: Any = injectedLock`）。
  触发后方案：把注入的构造器参数 `val` 纳入稳定锁白名单（`decl_sites` 追到构造签名）。
  搁置理由：三处探针全是类内私有锁，无注入形态；fail-closed 方向安全（不会静默放行）。
- **R2（rss 别名追踪）** 只跟一跳：lambda 形参 + 直接 `val x = st` 别名。经集合元素/函数返回值的多跳别名未覆盖。
  触发条件：出现 `byHandler.values.forEach { val x = it; x.leaked = true }` 类形态。
  触发后方案：用 `value_sources` 做别名闭包（固定点迭代）。
  搁置理由：现有 3 个文件无此形态；误判方向为漏（需真见证驱动再扩，避免无谓误伤）。
- **R3（rss 模板插值）** `mask_code` 对反引号模板插值内的嵌套代码整体抹空（保守）；模板内写入不可见。
  触发条件：探针改动落在 `"${...}"` 模板里。
  触发后方案：复用 B5-t03 的「按片回贴」手法（取共享词法器 `_skip_template`，取不到即 fail-closed）。
  搁置理由：探针代码无模板写入；现有 6 条见证未涉及。
- **R4（filepreview 无界 API 家族）** `WHOLE_FILE_READS` 只列 `Files.readString` / `Files.readAllBytes` / `.readAllBytes(`；`RandomAccessFile` 手动读、`Scanner(file)` 等不在家族。
  触发条件：新见证引入新的整文件读取原语。
  触发后方案：家族表从「整文件读 API」派生 + 反查调用点（`live_positions`）。
  搁置理由：01-1 只要求覆盖 JDK NIO 家族；扩家族需先有向真书面证据。
- **R5（filepreview cap 数值分析）** `_numeric_value` 只处理字面量 / 常量声明 / 简单算术 / `minOf`/`min`；经函数调用返回的 cap（`capOf()`）fail-closed 红。
  触发条件：真树出现 helper 返回 cap。
  触发后方案：一跳内联（跟到 `fun capOf(): Long = ...` 的返回表达式）。
  搁置理由：现状全是常量/常量声明；fail-closed 方向安全。
- **R6（filepreview remember 键）** 键判据要求「键必须是文件身份引用」；组合键（`item.file to idx`）按包含判过；键来自计算属性（`item.key`）未覆盖。
  触发条件：新见证用 `item.key` / `item.file.name` 类非身份键（`name` 已在身份白名单外，会红；`item.key` 需看其值来源）。
  触发后方案：键来源追一跳 `val`（`value_sources`）。
  搁置理由：现有实现已挡住 `Unit` / 无参 / `parent`；更远形态无见证。
- **R7（本地全量 test_scan 的整跑形态）** 并发窗口内 `test_scan.py` 单进程整跑两次被 SIGKILL；改用**逐函数独立进程**驱动（`probe/t03_tsfn_resume.py`，可续跑）后本地跑完 **46/46 函数、344 用例全 ✅ / 0 ❌**（`probe/t03_tsfn/*.log`）。残留点 = 单进程整跑在该环境下仍不可用，必须分段；CI 同一棵树的整跑同样绿（`ALL 344 CASES PASS`）。
  触发条件：需要本地全量证据（离线/CI 不可用）。
  触发后方案：`probe/t03_tsfn_resume.py`（可续跑分段驱动，每函数独立进程 + 状态文件）。
  搁置理由：CI 每次 push 都在同一棵树跑 `scan.sh` 112 与 `test_scan` 344；本地读数不构成额外证据（环境限制，非门红）。
- **R8（scan.sh 112 门头 vs 111 独立脚本）** `[2/112] i18n consistency check` 是联内 `python3 -c` 块，无独立文件；`i18n_check.py` 存在但 `scan.sh` 未调用。
  触发条件：联内检查继续增长 / 需要逐门可视。
  触发后方案：把联内块抽成 `i18n_check.py` 调用（一行改动）。
  搁置理由：超出本支 radius（任务书禁止改 `scan.sh`）；现由 CI 全量覆盖。

## 7. `test_scan.py` 注册项（交主会话统一登记）

现有（无需改动，标记串保留）：
```python
# test_scan.py:2702  DA1016_GATES
("rss_probe_lock_scope_check", 'SELFTEST=PASS (5 fixtures)', (REPO_ROOT,)),
# test_scan.py:2775  DA1020_GATES
("filepreview_print_bounded_check", "FILEPREVIEW_PRINT_BOUNDED_SELFTEST=PASS", (), (REPO_ROOT,)),
```

建议追加（防「臂被删而旧标记仍在」）：
```python
("rss_probe_lock_scope_check",
 "RSS_PROBE_LOCK_SCOPE_SELFTEST=PASS (1 compliant + 4 breakers + 8 witnesses)", (REPO_ROOT,)),
("filepreview_print_bounded_check",
 "FILEPREVIEW_PRINT_BOUNDED_T03_SELFTEST=PASS (7 witness arms:", (), (REPO_ROOT,)),
```
臂数：rss 8 条 witness arms（+ 4 breakers + 1 compliant）；filepreview 7 条 witness arms（+ 既有 b05 臂）。

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t03

# 1) 共享原语自测
python3 scripts/scan/_kotlin_lexer.py --selftest         # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest           # 32 arms
python3 scripts/scan/_kotlin_valueflow.py                # 26 arms

# 2) 两门自测 + 真树
python3 scripts/scan/rss_probe_lock_scope_check.py --selftest
python3 scripts/scan/filepreview_print_bounded_check.py --selftest
python3 scripts/scan/rss_probe_lock_scope_check.py .
python3 scripts/scan/filepreview_print_bounded_check.py .

# 3) 官方反攻（硬验收）
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-A.json \
  --repo . --mode full --only rss_probe_lock_scope_check.py \
  --out probe/t03-post-rss.json
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-B.json \
  --repo . --mode full --only filepreview_print_bounded_check.py \
  --out probe/t03-post-filepreview.json

# 4) 基线对照（旧门 vs 新门）
python3 probe/t03_baseline_vs_branch.py

# 5) 逐门等价全量（沙箱并发窗口替代 scan.sh）
python3 probe/t03_scan_all.py          # 断点续跑，台账 probe/t03_scanall.done

# 6) test_scan 分段（可续跑）
python3 probe/t03_tsfn_resume.py
```
