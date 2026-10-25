# FIX-WIDE4-10 — debug_leak_guard / legacy_pipeline_guard 判据加固

- 分支：`fix/wide4-10-debugleak-legacy`，基线 `base/wide4-shared` = `dbab7a3e`（= main `19e70dab` + 共享 Kotlin 词法/语法原语）
- 改动面：**只改两个门脚本**（`scripts/scan/debug_leak_guard.py` +616/−…、`scripts/scan/legacy_pipeline_guard.py` +345/−…，合计 +713/−248）+ 本报告
- **生产代码 0 行改动**；`scan.sh` / `test_scan.py` / 共享模块 `_kotlin_lexer.py` / `_kotlin_ast.py` **零触碰**；门数 112、夹具 344 不变
- 输入：`meta-wide4/reports/report-A.md` / `report-B.md` 中本支的 5+6 条见证（A 面 debug_leak_guard 5 条 FALSIFIED；B 面 legacy_pipeline_guard 5 条 FALSIFIED + 1 条 CAUGHT_VERIFIED）

## §1 门与轴

| 门 | 见证 | 轴 | 旧判决 | 新判决 |
|---|---|---|---|---|
| `debug_leak_guard.py`（source mode） | D-W1 | guard-kind | FALSIFIED | CAUGHT |
| 同上 | D-W2 | scope + guard-kind（导入符号未限定名）+ reflection | FALSIFIED | CAUGHT |
| `debug_leak_guard.py`（api26 read contract） | D-W3 | dataflow（分支） | FALSIFIED | CAUGHT |
| 同上 | D-W4 | dataflow（消费） | FALSIFIED | CAUGHT |
| 同上 | D-W5 | parser-death（嵌套块注释 + 原始字符串） | FALSIFIED | CAUGHT |
| `legacy_pipeline_guard.py` | L-W1 | scope（行尾 legacy-ok 洗白顶层声明） | FALSIFIED | CAUGHT |
| 同上 | L-W2 | scope（多行豁免区吞掉声明行） | FALSIFIED | CAUGHT |
| 同上 | L-W3 | completeness（FROZEN 从未被读 + 修饰符跨行） | FALSIFIED | CAUGHT |
| 同上 | L-W4 | scope（`run { }` 初始化器伪造花括号深度） | FALSIFIED | CAUGHT |
| 同上 | L-W5 | parser-death（`"/*"` 字符串吞掉整段代码） | FALSIFIED | CAUGHT |
| 同上 | L-W6 | control（注释在顶层声明上方） | CAUGHT_VERIFIED | **CAUGHT（保持）** |

共同的失效形态：**判据锚「某个词出现过」而不是「该词产生了效果」**——`BuildConfig.DEBUG` 出现过即算守卫（不管是 `!DEBUG` 还是恒真析取）、`readFileSegment(` 出现过即算读、`legacy-ok:` 出现过即豁免（不管那行是不是新声明）、`FROZEN_LEGACY_SYMBOLS` 被声明过即算清单（从来没人读）。四条改造方向一致：**从树里派生事实 + 值级/数据流判定 + 解析不出来就 fail-closed**。

## §2 每条见证

### D-W1 守卫反向（guard-kind）

- **原补丁**：`MinisApp.kt` 把 `if (BuildConfig.DEBUG)` 改成 `if (!BuildConfig.DEBUG)` —— 调试服务器在 **release 构建里启动**，debug 里反而不启动。
- **旧判据为何瞎**：`debug_guarded.live()` = `bool(DEBUG_TEST_RE.search(cond)) and not LITERAL_SHORTCIRCUIT_RE.search(cond)`，即「条件里出现 `BuildConfig.DEBUG` 且没有裸 true/false」。`!BuildConfig.DEBUG` 两条都满足。
- **新判据**：`guard_decides(cond)` —— **正向**判定。顶层不允许 `||`（任一析取项可自行满足守卫）；顶层 `&&` 的某一合取项必须**恰好**是标志（`BuildConfig.DEBUG` / `BuildConfig.DEBUG == true`，允许 `com.rikkaminis.app.` 之类的包前缀）；只「提到」标志的合取项（`!DEBUG`、`DEBUG == false`、`f(DEBUG)`）不算证据；合取项折叠为常量 false 则守卫死。`live()` 整体被 `guard_decides()` 取代。
- **新臂**：`source: w4-inverted guard \`if (!BuildConfig.DEBUG)\` is caught (release-only server)`；配套控制臂 `source: a DECIDED guard (\`DEBUG && flags\`, package-qualified) stays clean`。

### D-W2 导入符号的未限定名 + 运行期拼接（scope + guard-kind）

- **原补丁**：`MinisApp.kt` 加 `import com.rikkaminis.app.debug.DebugServer`，并在类体里写 `private val w4Leaked: Any? = if (true) DebugServer(this) else null`；另一条只用 `Class.forName("com.rikkaminis.app.debug" + ".DebugServer")`。
- **旧判据为何瞎**：`TARGET_RE = com\.rikkaminis\.app\.debug\.` 要求**全限定且带尾点**。导入之后的裸 `DebugServer(...)` 没有包前缀；反射用的是「包名前缀字符串 + 类名」，两处都不匹配。且 allow-list 文件里的 import 行直接 `continue`，等于「导入即放行」。
- **新判据**：三种拼写归一到同一条 `report()` 判据（逃逸 → allow-list 符号 → 活守卫）：
  1. `TARGET_RE`（全限定）；
  2. `DEBUG_IMPORT_RE` 抽出的**导入符号名**，在 `struct`（字符串内容抹白）里以 `(?<![\w.$])name\b` 搜未限定引用——负向后视排除成员访问 `providerType.supportsCustomBaseURL`，成员访问不是「导入即用」；
  3. `TARGET_PREFIX_RE = com\.rikkaminis\.app\.debug(?![.\w])` —— 运行期拼接的包前缀（反射）。
  另外把导入符号的集合**从树里派生**：`debug_package_symbols()` 解析 `debug/` 包的全部顶层 class/object/interface，供 `import …debug.*` 通配导入展开（包不在树里 → fail-closed 违规）；导入行带 `debug-ok:` 视为对该符号的人工审计（同一文件内的使用点由该审计覆盖，语义等同 `UNGUARDED_SYMBOLS`，但由树派生而非硬编码表）。
- **新臂**：`source: w4-imported symbol used unqualified is the same reference`、`source: w4-runtime-built package prefix (reflection) is caught`。

### D-W3 辅助函数停在死分支（dataflow/branch）

- **原补丁**：`DebugRPCHandler.handleReadFile` 里 `val bytes = readFileSegment(...)` → `when (false) { true -> readFileSegment(...) else -> Files.readAllBytes(...) }`。
- **旧判据为何瞎**：`handleReadFile` 只要「体里出现 `readFileSegment(`」且「体里没有黑名单里的流式读」就绿。`Files.readAllBytes` 正是黑名单**没有**的名字（旧表只有 `inputStream|readBytes|readNBytes|skip`），而死分支判定 `DEAD_COND_RE` 只认 `false / !true / 0 == 1 / true || / false &&` 这几种拼写，`when (false)` 也不在它的视野里。
- **新判据**：`_dead_cond()` 改用共享常量折叠 `_const_bool`（`1 == 2`、`when (false)` 同族都能判）；新增 `_in_dead_when_arm()`，从 `when_branches()` 拿到「主语的常量值 vs 分支条件」，主语常量与分支条件常量不等即死（`else` / `is` / `in` / 多值分支永不判死）；新增 `UNBOUNDED_READ_RE`（`readBytes|readNBytes|readAllBytes|readText|readLines|forEachLine|bufferedReader|reader|copyTo|inputStream|skip`），体里的**活**无界读即红。
- **新臂**：`api26: w4-when(false) + Files.readAllBytes off the helper path is caught`。

### D-W4 辅助函数结果被链式丢弃（dataflow/consume）

- **原补丁**：`val bytes = readFileSegment(...).let { hostFile.bufferedReader().readText().toByteArray() }`。
- **旧判据为何瞎**：同上——只看「调用在不在」，不看「调用的值有没有被当成值用」。`.let` 之后的 `readText` 也不在旧黑名单里。
- **新判据**：`read_source_verdict(stmt)` —— 语句的**值**必须恰好是一次辅助调用：`_helper_calls()` 用 `match_bracket` 配平取调用跨度，前缀必须匹配 `^(?:return|(?:val|var)\s+name(?::type)?\s*=)$`、后缀必须为空；多次调用 / 调用不闭合 / 有尾链全部 fail-closed 红。`scan_api26_reads` 逐语句调用它。
- **新臂**：`api26: w4-helper result dropped by a \`.let\` chain is caught`；控制臂 `api26: w4-the read statement's value is exactly the bounded helper (control)`。

### D-W5 嵌套块注释 + 原始字符串（parser-death）

- **原补丁**：把读点包在 `.let { /* outer /* inner */ val _s = """ */ …readAllBytes… /* outer2 /* inner2 */ """ */ }` 里。
- **旧判据为何瞎**：门自带 `blank()` 字符状态机，`/*` 只记一层深度语义（内层 `/*` 不增加深度），第一个 `*/` 就把注释关掉，于是 `"""` 被当成**原始字符串开头**，把这之后到下一个 `"""` 之间的真实代码全部抹白——包括真正的无界读。
- **新判据**：删掉 `blank()` / `strip_comments()` / `code_view()`，统一走共享 `_kotlin_lexer.analyse()`：`views["comments"]`（注释抹白、字面量保留）用于「引用是否出现」，`views["code"]`（两者都抹白）用于结构判定；**词法失败 = 违规**（`_views()` 返回 reason，`scan_source` / `scan_api26_reads` 直接记一条 fail-closed 违规，绝不把它当「没什么可扫」）。
- **新臂**：`api26: w4-nested block comment + raw string cannot hide a stream read`。

### L-W1 行尾 `legacy-ok:` 洗白顶层声明（scope）

- **原补丁**：在 `ChatColdOpenPrewarm.kt` 顶层加 `internal val accidentalLegacyCheck: (FlatChatItem) -> Boolean = { it is FlatChatItem.AssistantMarkdownBlock } // legacy-ok: inline bypass`。
- **旧判据为何瞎**：`escape_regions()` 的非注释分支只要 `ESCAPE_RE.search(line)` 就 `regions.append((i, i))`，既不要求深度 > 0，也不看这一行是不是**新声明**——行尾注释等于万能豁免券。
- **新判据**：`escape_regions()` 重写，豁免必须满足 `use_site()`：① 该行在**函数体**内（`_blocks()` 给出的块类型链里有 `fun`）；② `_DECL_SITE_RE` 不匹配（不是声明头）且 `_MEMBER_DECL_RE` 不匹配（行内不引入 `val|var|fun|class|object|interface|typealias`）。顶层/类体成员/声明行一律不豁免。
- **新臂**：`w4-trailing legacy-ok cannot launder a top-level declaration (scope)`。

### L-W2 多行豁免区吞掉声明行（scope/region）

- **原补丁**：在既有豁免区里追加一行 `is … -> { val leaked = { x: FlatChatItem -> x is FlatChatItem.AssistantMarkdownBlock }; … }`。
- **旧判据为何瞎**：豁免区从标记后一行起，按「非空行 + 深度不降」向前吞，**整段**行都进 `exempt` 集合；区里出现的新声明不会被单独审查，而真正的新引用正好藏在声明里。
- **新判据**：区的**扩展**也走 `use_site()`——延伸过程中只要遇到会引入声明的行就 `break`（该行不豁免，回归正常扫描）。同时保留「空行断区」「深度下降断区」。
- **新臂**：`w4-a declaration inside a multi-line escape region is still judged (scope)`。

### L-W3 冻结清单从未被读（completeness）

- **原补丁**：在 `AudioWaveformView.kt` 追加 `internal\nfun newUntrackedLegacyHelper(): Int = 0`（修饰符与 `fun` 拆两行）；报告另给一版把 `internal class StableChatRowLedger {` 拆行。
- **旧判据为何瞎**：`FROZEN_LEGACY_SYMBOLS` 声明了 15 个名字，**全文件 0 处读取**。于是「冻结包不长大」这条规则从来没被执行过；再加上 `_DECL_RE` 逐行匹配 + `clean.split("\n")` 的逐行状态，跨行修饰符是另一条独立的静默面。
- **新判据**：`scan()` 做**双向完整性比较**：
  - 符号：`parsed` vs `FROZEN_LEGACY_SYMBOLS`，`missing` / `extra` 任一侧非空即红（只在「包文件集完整」时比较，夹具树按设计跳过）；
  - 文件集：`per_file` 键 vs `FROZEN_LEGACY_FILES`，冻结包多出文件即红；
  - `_legacy_scan()` 增加 `_MODIFIER_ONLY_RE` 的 pending 机制：修饰符独占一行时并入下一行再匹配，跨行写法不再让符号消失（否则「比较」本身也会被同一个拼写绕过）。
- **新臂**：`w4-new dead symbol in the frozen package is caught (completeness)`、`w4-a modifier split across two lines does not hide a new symbol (completeness)`、`w4-a frozen symbol that disappears is caught (completeness)`、`w4-a dead file added to the frozen package is caught (completeness)`、控制臂 `w4-frozen inventory matches the package (control)`（夹具用 `_frozen_fixture()` 生成，并 `assert` 夹具符号集合 == 常量，防止夹具与常量各自漂移）。

### L-W4 `run { }` 初始化器伪造深度（scope/depth）

- **原补丁**：顶层 `internal val leakedExtractor = run {` + `// legacy-ok:` + lambda 体（内含 `AssistantMarkdownBlock`）。
- **旧判据为何瞎**：豁免条件就是 `depths[start] > 0`——**裸花括号计数**。任何顶层属性初始化器（`run { }` / `lazy { }` / 列表字面量）都能把它伪造出来，于是顶层新声明被当成「块内使用点」豁免。
- **新判据**：同 L-W1 的 `use_site()`——`_blocks()` 从块头文本派生类型（`fun … {` 函数体 / `class|object|interface|companion … {` 类型体 / 其余 `other`），要求**存在一个包住该位置的 `fun` 块**。顶层 `run { }` 的块链里没有 `fun`，伪造失效；`fun f() = when (x) { … }` 这种表达式体函数仍然算函数体（块头含 `fun`），真实豁免点不受影响。
- **新臂**：`w4-\`run { }\` initializer cannot forge depth (scope)`。

### L-W5 `"/*"` 字符串吞掉整段代码（parser-death）

- **原补丁**：新增一个函数，`val open = "/*"` … 真实引用 … `val close = "*/" + ""`。
- **旧判据为何瞎**：`strip_comments()` 用 `BLOCK_COMMENT_RE = /\*.*?\*/`（`re.S`）裸正则——**不知道字符串字面量**。`"/*"` 起始、`"*/"` 结束之间的所有行被替换成空行，正好把真引用抹掉。（同族自伤在本仓历史上已复现过：`"video/*"` 抹白 67 行。）
- **新判据**：删掉 `BLOCK_COMMENT_RE` / `strip_comments` / `mask_code`，统一走共享词法器两个视图；词法失败记违规。
- **新臂**：`w4-a \`"/*"\` string cannot blank the code below it (parser-death)`。

### L-W6 控制臂（原本就 CAUGHT，必须保持）

- **原补丁**：注释行在**顶层声明上方**（`// legacy-ok:` + `internal val alreadyCaught = …`）。旧门就红（标记后直接跟声明 → 无区），新门仍红。反攻矩阵里它落在 `CAUGHT→CAUGHT`。

## §3 判据改动清单（函数名级）

`scripts/scan/debug_leak_guard.py`

| 删除 | 新增 | 说明 |
|---|---|---|
| `blank()` / `strip_comments()` / `code_view()` | `_views()` | 共享词法器两视图；词法失败 → 调用方记 fail-closed 违规 |
| `DEAD_COND_RE` | `_dead_cond()` | 值级常量折叠（`_kotlin_ast.const_bool`） |
| — | `_in_dead_when_arm()` | `when` 主语常量 vs 分支条件常量 |
| `DEBUG_TEST_RE` / `LITERAL_SHORTCIRCUIT_RE` / `live()` | `guard_decides()` / `_is_debug_flag()` / `_squash()` | 正向守卫判定，禁顶层 `||`，合取项必须恰为标志 |
| `STREAM_READ_RE` | `UNBOUNDED_READ_RE` | 补齐 `readAllBytes|readText|readLines|forEachLine|bufferedReader|reader|copyTo` |
| — | `_helper_calls()` / `read_source_verdict()` / `_READ_STMT_PREFIX_RE` | 读语句的**值**必须恰为辅助调用 |
| — | `debug_package_symbols()` / `_DEBUG_CLASS_RE` / `DEBUG_IMPORT_RE` / `TARGET_PREFIX_RE` / `report()` | 导入符号未限定名、通配导入展开、运行期拼接前缀 |

`scripts/scan/legacy_pipeline_guard.py`

| 删除 | 新增 | 说明 |
|---|---|---|
| `strip_comments()` / `BLOCK_COMMENT_RE` / `mask_code()` | `_views()` | 同上 |
| — | `_blocks()` / `_FUN_HEAD_RE` / `_TYPE_HEAD_RE` | 块类型派生（`fun` / `type` / `other`） |
| `escape_regions()` 旧体 | `escape_regions()` 重写 + `use_site()` / `_MEMBER_DECL_RE` | 豁免必须是函数体内的使用点；区扩展遇声明即断 |
| — | `FROZEN_LEGACY_FILES` / `_MODIFIER_ONLY_RE` | 文件集冻结 + 跨行修饰符 |
| `_legacy_scan()` 逐行状态 | 共享词法视图 + pending 修饰符 + 每文件 fail-closed | 声明清单可信度 = 比较的可信度 |

`scan()` 的完整性判据（符号双向缺失/新增、文件集新增、每文件「解析不出符号」fail-closed）是本支新增的**第三类轴：completeness**——前两波集中在 dataflow / scope / parser-death，这一波实证「写好了判据却从不执行」也是同族失效。

## §4 四层证据

**L1 门自证 + 真树**（`python3 scripts/scan/<gate>.py .` / `--selftest`）

| 门 | 真树 | 自证 |
|---|---|---|
| `debug_leak_guard.py` | rc=0（`no unaudited debug-package references` + `debug read path is minSdk-26 safe`） | `DEBUG_SOURCE_SELFTEST=PASS (5 fixtures)` / `DEBUG_API26_SELFTEST=PASS (11 fixtures)` |
| `legacy_pipeline_guard.py` | rc=0（`no new references to the dead path`） | `LEGACY_PIPELINE_SELFTEST=PASS (16 fixtures, 0 mismatched)` |

`test_scan.py` 里本支两门的既有夹具（`test_legacy` 3 例 / `test_debug_leak` 3 例）逐条 PASSED，未改动。

**L2 原见证反攻**（`probe/w4_one.py` + `probe/w4_lib.py`，判据 `gate` / `axis` / `old_rc` / `new_hits` / `restored`）

`probe/w4-reattack-debug.json`（5 条）：

| 见证 | 轴 | 旧门 | 新门 | 判决 | 还原 |
|---|---|---|---|---|---|
| D-W1 | guard-kind | rc=0 | 1 条 | BLIND→CAUGHT | True |
| D-W2 | scope+guard-kind | rc=0 | 1 条 | BLIND→CAUGHT | True |
| D-W3 | dataflow(branch) | rc=0 | 2 条 | BLIND→CAUGHT | True |
| D-W4 | dataflow(consume) | rc=0 | 3 条 | BLIND→CAUGHT | True |
| D-W5 | parser-death | rc=0 | 2 条 | BLIND→CAUGHT | True |

`probe/w4-witness-L-W*.json`（6 条）：L-W1 `0→1`、L-W2 `0→2`、L-W3 `0→1`、L-W4 `0→1`、L-W5 `0→1` —— 全部 BLIND→CAUGHT；L-W6 `1→1` CAUGHT→CAUGHT。**8+3 = 11/11 条 `restored=true`，还原后真树违规 0 条**。

**L3 邻门**（只读跑，rc=0）：`offload_host_path_check` · `log_url_redaction_guard` · `file_provider_paths_coverage_check` · `deeplink_settings_routes_check` · `chat_session_id_binding_check` · `process_logging_gate` · `discarded_return_guard` · `offload_cli_args_check` · `offload_relative_path_unify_check`（9 道）。

**L4 全量**：见 §5（本地全量在并发高峰被 S28 `OSError: [Errno 38]` 打断——`four_way_sync_check` 在 `load_kotlin_files` 里 `open()` 就 `Function not implemented`，`python3`/`grep`/`tail` 也随机 ENOSYS，属沙箱窗口不是门红；按任务书 §5.4「分块重跑，不要当成门红」处理，最终以 CI 单进程全量读数为准）。

## §5 CI

- **CI #2669 / run id `40180281460` / attempt 1 / job `121129678569` → conclusion `success`**
- **三方 sha 逐字符一致**：本地 HEAD = `ac5882a5caa963fbbe07eca9708e37ecdad0cc03` · `GET /branches/fix/wide4-10-debugleak-legacy` = 同上 · `run.head_sha` = 同上
- 步骤面：23 步 `success`，唯一 `skipped` = **Publish to Releases**（main 门控，本支是分支）；`Complete job` success
- 日志判读（1,481,048 B / 17,845 行，`probe/ci/job-121129678569.log`）：
  - `Summary: 112 passed, 0 failed` —— **总是 112 的那条恰一条**（另 7 条 28/5/14/36/15/25/10 是子套件摘要）
  - `ALL 344 CASES PASS` —— **恰一条**
  - ` e: `（Kotlin 编译错误）—— **0 行**
  - 门头 `━━━ [N/112] ━━━` —— 112 条，编号 112 个互异、totals 全为 112
  - 含 `FAILED` 的 6 行 —— **逐条确认全是 PASSED 用例名子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED…`、`ChatAgentTraceObserverSchemaTest > … FAILED maps to …` ×3、`TerminalErrorSurfacedTest > … legal FAILED terminal`）
  - 本支两门在册且绿：`[6/112] Legacy pipeline guard` → `✅ no new references to the dead path`；`[8/112] Debug-code release boundary (source)` → `✅ no unaudited debug-package references` + `✅ debug read path is minSdk-26 safe`；另有 apk 侧复核 `✅ Debug-leak guard (apk): 6 debug markers absent from 1 dex entries`
- 冻结面读数与基线一致：门 112 / 夹具 344 不变（本支未新建门、未改 `scan.sh` / `test_scan.py`）

## §6 残留

**R1 —— 导入行的 `debug-ok:` 是「符号级」而不是「使用点级」**

- 触发条件：某个 allow-list 之外的文件同时 `import` 两个 debug 符号、只对其中一个写 `debug-ok:`，且对另一个的使用不带活守卫。
- 触发后方案：让 `debug-ok:` 携带符号名（`debug-ok: <Symbol> …`），解析出的名字集合才进 `allowed_syms`；未点名的导入符号保持「使用点必须活守卫」。
- 搁置理由：现状只有两处导入（`ProviderWriteRules.kt` / `ProvidersCollection.kt`）且都是纯谓词 `supportsCustomBaseURL`，紧邻上游有一道「纯属性/纯谓词」的显式说明；把规则做细需要给两个文件各写一条点名单，装置比例不合算。

**R2 —— 运行期拼接只认整段包名前缀**

- 触发条件：出现 `"com.rikkaminis.app" + ".debug.DebugServer"`、`StringBuilder().append("com.rikkaminis").append(".app.debug…")` 或 `buildString { }` 形态的类名构造。
- 触发后方案：把 `TARGET_PREFIX_RE` 拆段（先匹配 `com.rikkaminis.app`，再判后续拼接里是否出现 `.debug`），或对「字符串字面量折叠 + 变量源追踪」做一跳。
- 搁置理由：全仓 `DebugServer`/`DebugServerBridge` 的取得方式目前只有直接引用与 `Class.forName` 整名前缀两种；拆段匹配会把大量无关字符串（`"com.rikkaminis.app.R"` 之类）拖进判据，假红成本高于收益。

**R3 —— 读语句的值判定只认单语句形态**

- 触发条件：`handleReadFile` 被重构成 `val bytes = try { readFileSegment(...) } catch (e: …) { ByteArray(0) }` 或 `if (cond) helper(...) else helper(...)`（辅助调用是两支唯一值）。
- 触发后方案：把 `read_source_verdict()` 升级成语句级 value-flow——`try/if/when` 块的**所有分支**都必须是同一辅助调用（或块本身是该语句的值）。
- 搁置理由：方向安全（当前实现会 fail-closed 红而不是静默放过），且真实 handler 就一句 `val bytes = readFileSegment(hostFile, offset, limit)`；等真出现这种重构再加。

**R4 —— `_blocks()` 的块类型是块头启发式**

- 触发条件：出现 `val f = fun() { … }` 这种匿名 `fun` 表达式初始化器（块头含 `fun`，会被判成函数体）。
- 触发后方案：块头先按 `=` 切分，只在 `=` 左侧出现 `fun` 时才判 `fun`；`=` 右侧的 `fun` 一律 `other`。
- 搁置理由：Kotlin 里匿名 `fun` 罕见，本仓 0 处；而误判方向是「把 other 当 fun」= 略宽，需要同时有新声明才能利用，已被 `_MEMBER_DECL_RE` 挡住第一层。

## §7 注册项（主会话并集收口用）

本支两门在 `test_scan.py` **没有** marker 断言的注册条目（只有 fixture 用例），新增标记串如下，登记时逐字复制：

```
| debug_leak_guard.py   | DEBUG_SOURCE_SELFTEST=PASS (5 fixtures)                  | 5  |
| debug_leak_guard.py   | DEBUG_API26_SELFTEST=PASS (11 fixtures)                  | 11 |
| legacy_pipeline_guard.py | LEGACY_PIPELINE_SELFTEST=PASS (16 fixtures, 0 mismatched) | 16 |
```

- **本支无冻结标记串**（两门原有标记未被任何脚本/CI 断言，`grep -rn "LEGACY_PIPELINE_SELFTEST\|DEBUG_API26_SELFTEST\|DEBUG_SOURCE_SELFTEST" scripts/ .github/` 只命中门自身）。臂数从 6→16（legacy）、7→11（api26）、新增 5（source），标记串拼写不变，只改数字。
- 注册方式（供并集收口参考，本支未改 `test_scan.py`）：

```python
DA_WIDE4_10_GATES = [
    ("debug_leak_guard", "DEBUG_SOURCE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("debug_leak_guard", "DEBUG_API26_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("legacy_pipeline_guard", "LEGACY_PIPELINE_SELFTEST=PASS", (), (REPO_ROOT,)),
]
```

## §8 复现

```sh
cd /var/minis/shared/work/meta-wide4/wt-10          # 分支 fix/wide4-10-debugleak-legacy
python3 scripts/scan/debug_leak_guard.py .           # rc=0
python3 scripts/scan/debug_leak_guard.py --self-test # 两个 SELFTEST=PASS
python3 scripts/scan/legacy_pipeline_guard.py .      # rc=0
python3 scripts/scan/legacy_pipeline_guard.py --selftest
python3 probe/w4_one.py D-W1                         # 单条见证：旧门 rc=0 / 新门 1 条 / restored=true
python3 probe/w4_reattack.py debug                   # 5 条见证成组（进程内旧门，无 fork）
```

反攻脚本会在 `finally` 里把文件写回并逐字节核对 sha256，写回失败时由驱动侧 `git checkout -- <file>` 兜底；`git status --porcelain` 应只剩两个门脚本与本报告。

**S28 沙箱实证（本支两次踩中，已闭环）**：并发高峰窗口内 `open()` / `os.fsync` / `subprocess` / 甚至 `grep`/`tail` 随机 `OSError: [Errno 38] Function not implemented`。第一次把工作树留在了变异态（`ChatColdOpenPrewarm.kt` 残留补丁），靠 `git checkout --` 还原后 sha256 复核确认。处置流程固化为：**开工先 `git status` 自检 → 变异前记 sha → 每轮结束 `git status` + sha 复核 → 写回失败立刻 git 还原**；反攻脚本改成「进程内加载新旧两门、零 fork」，把 fork 暴露面从 2×11 次降到 0。
