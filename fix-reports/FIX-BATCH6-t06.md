# FIX-BATCH6-t06 — `deeplink_settings_routes_check` + `provider_type_registry_check`

支：`fix/b6-deeplink-protype` · 基线 `base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）
门提交：`92bc6ddbc`（2 个门脚本，生产 `.kt` **0 行**、`scan.sh` / `test_scan.py` / `_kotlin_*` **零触碰**、门 112 / 夹具 344 不变）

---

## ① 门与轴

| 门 | 见证 | 轴 |
|---|---|---|
| `scripts/scan/deeplink_settings_routes_check.py`（1394 → 1742 行） | 07-1 … 07-6（6 条） | anchor 1 · spelling 1 · dataflow 2 · completeness 1 · family-hole 1 |
| `scripts/scan/provider_type_registry_check.py`（676 → 806 行） | A08-1 … A08-6（6 条） | family-hole 3 · dataflow 1 · parser-death 2 |

两门共症（≈前几批同族，但这次落在「值/可达」而非「结构」）：

> 判据锚 **某个 token 存在**（`'mcp-servers' in dlh` / `seg(` 计数 / `listOf` 构造器 / `flatMap` 字面 / 行首枚举名），
> 缺陷动 **这个 token 的效果**（标签没被匹配 / id 在字面量外拼接 / 列表在求值期被清空 / 枚举流被 `take(0)` 截断 / 分支只活在块注释里 / 常量与别人共行）。

修法统一推到位流与可达性：**判标签是否被 `when` 匹配、判 key 的值是否依赖状态、判列表求值后是否还有 id、判调用是否在可达路径上、判枚举常量列表的完整解析**。

---

## ② 见证原文（12 条，file / find / replace / why_gate_green）

### 门 A：`deeplink_settings_routes_check.py`

**07-1（anchor）** `src/android/app/src/main/java/com/rikkaminis/app/deeplink/DeepLinkHandler.kt`
- find: `"mcp", "mcp-servers", "mcp_servers" -> SettingsDeepLink.Route(Routes.MCP)`
- replace: `"mcp", "mcp_servers" -> SettingsDeepLink.Route(Routes.MCP.also { "mcp-servers" })`
- why_gate_green: 判据只做 `if head not in dlh: fail(...)` —— 子串命中即算「白名单覆盖」，从不解析 `when (head)` 的分支标签列表。把 `mcp-servers` 从标签挪进一个死掉的 `.also { }` 体，磁盘上仍有那几个字节，门全绿，而 MCP 页签的往返链接照样落到 Settings 首页。

**07-2（spelling）** `src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt`
- find: `"model_entry/${seg(instanceId)}/${seg(entryId)}"`
- replace: `"model_entry/${seg(instanceId)}/" + entryId`
- why_gate_green: `defn.count("seg(") == 1 > 0` 通过（有一个编码兄弟就够），随后只在 `string_literals(defn)` 区间里找 `$` 插值孔 —— `+ entryId` 在双引号闭合之后，不在任何字面量区间内，`raw` 恒为空。

**07-3（dataflow）** `.../ui/settings/EnvironmentVariablesScreen.kt`
- find: `LaunchedEffect(pendingCreate) {`
- replace: `LaunchedEffect(pendingCreate.let { Unit }) {`
- why_gate_green: 判据是 `if any(re.search(rf"\b{v}\b", key) for v in collected)` —— key 文本里**出现了** `pendingCreate` 就算响应式。而运行期 key 的值是 `Unit`，效果与 `LaunchedEffect(Unit)` 逐字相同。

**07-4（dataflow）** 同文件
- find: `var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {`
- replace: `var keyText by remember(editEntry.let { 0 }, prefillKey.let { 0 }) {`
- why_gate_green: `ident_roots(keys)` 收集的是「文本里出现的标识符」（跳过 `.` 前缀与 `(` 后缀），两个 seed 都出现 ⇒ `missing = seeds - ident_roots(keys) == []`。而两个 key 的值都是常量 `0`，字段永不重播种。

**07-5（completeness）** `.../ui/chat/ChatScreen.kt`
- find: `LaunchedEffect(sessionId, pendingChatActionState) {`
- replace: `LaunchedEffect(Unit) {`
- why_gate_green: Criterion 8 仔细判了 EnvVar 的 key 反应性，**Criterion 9 只做了 3 条正则**（`collectAsState(` 存在 / `.value` 不存在 / `pendingHtmlPreview.collectAsState(` 存在），完全没有判 ChatScreen 的 `LaunchedEffect` key —— 同一批同族判据在隔壁漏了一整面。

**07-6（family-hole）** `.../ui/navigation/AppNavigation.kt`
- find: `try {\n                    context.startActivity(showAlarms)`
- replace: `try {\n                    if (false) context.startActivity(showAlarms)`
- why_gate_green: 判据是 `re.search(rf"startActivity\s*\(\s*{n}\s*\)", alarm) is not None` —— 只证明调用**被构造**，不判可达。`if (false)` 保住子串、丢掉发射。

### 门 B：`provider_type_registry_check.py`

**A08-1（family-hole）** `.../data/model/ProviderConfig.kt`
- find: `ProviderType.kimiCode -> listOf("moonshotai", "moonshotai-cn")`
- replace: 追加 `.filter { false }`
- why_gate_green: `EMPTY_MAPPING_RE` 只认「构造即空」（`listOf()` / `emptyList()`）。`listOf(…)` 括号里有非空字面量、`.filter { false }` 不在正则里 ⇒ 判非空。**同一个零映射，晚了一个求值阶段**。

**A08-2（dataflow）** `.../provider/ModelsDevApi.kt`
- find: `.map { key -> key to ids }` → replace: `.map { key -> key to ids.take(0) }`
- why_gate_green: 手臂 2e 只要求 `to` 右侧**出现** `\bids\b`，`ids.take(0)` 命中子串 ⇒ 通过；`val ids = type.modelsDevProviderIds` 活着但结果被丢弃。

**A08-3（family-hole）** 同文件
- find: `(listOf(type.displayName, type.name) + type.legacyProviderNames)\n            .distinct()`
- replace: `… .filter { it != type.displayName }`
- why_gate_green: 键派生只做「三个属性名是否出现在块内」的子串检查；`filter` 里 `displayName` 甚至出现两次 ⇒ 更绿。但 `"Google Gemini"` 这一整个键族从结果里消失。

**A08-4（family-hole）** 同文件
- find: `) + ProviderType.values().flatMap { type ->`
- replace: `) + ProviderType.values().take(0).flatMap { type ->`
- why_gate_green: `ENUM_ITER_RE` 仍匹配 `ProviderType.values()`，`derived_body` 仍能找到后面的 `.flatMap { … }` 块 —— 手臂 2a~2e 全绿，映射块一次都不执行。

**A08-5（parser-death）** `.../data/model/ProviderConfig.kt`
- find: `ProviderType.xAI -> listOf("xai")` + `ProviderType.kimiCode -> …`
- replace: 合并为 `ProviderType.xAI, ProviderType.kimiCode -> listOf("")`，并把两条原分支塞进 `/* … */`
- why_gate_green: 1) `code_only` 只剥 `//` 行注释，**保留块注释**；2) `branch_texts` 用 `BRANCH_RE` 只取箭头前**紧邻的一个**枚举名，且字典覆盖不累加；3) `EMPTY_MAPPING_RE` 对 `listOf("")` 不触发。⇒ 覆盖「完整」、非空、无 `else`，全绿。

**A08-6（parser-death）** 同文件
- find: `    kimiCode("Kimi Code");`
- replace: `    kimiCode("Kimi Code"), deepSeek("DeepSeek");`
- why_gate_green: `enum_constants` 逐行 `re.match(r"^\s*([A-Za-z_]\w*)\s*(?:\(|,|;|$)")`，同一行第二个常量完全不可见 ⇒ 枚举「全集」少一个成员，`when` 覆盖检查对 `deepSeek` 永不提问。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

共享原语全部走 `_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`；**自写的 `code_only`（行剥注释）、`string_literals`（手写引号扫描）、`mask_literals`、`paren_text`/`brace_text`/`_balanced`（手写括号走）、`when_blocks`（手写花括号配平）、`branch_texts`（箭头到箭头切片）、`derived_body`（正则找块）全部删除或改为共享实现**。

两门都引入 `views()`：`code_view`（注释 + 字面量内容全空、**等长**，偏移仍指向原文）+ `literal_view`（只空注释，用来读模板孔与发射字符串）；词法器抛 `KotlinLexError` ⇒ **fail-closed 记违规**（parser-death 一律判红，不再退化成原文匹配）。

### 门 A

| # | 旧形态 | 新形态 |
|---|---|---|
| 07-1 | `head not in dlh`（全文子串） | `dispatch_labels(code, lit, "resolveSettingsDeepLink", "head")`：在函数体内的 `when (head) { … }` 块上按 **深度 0 的 `->`** 切分支、回溯标签列（支持多行/多标签），只认 **match label**；函数或 dispatch 不见 ⇒ fail-closed 红；emitter 交叉检查同步改用标签集合 |
| 07-2 | `count("seg(") > 0` + 只扫字面量内的孔 | 判**路由值**：① 孔扫描改在 `italic literal_view` 上做（偏移与原图对齐）② 新增 `concat_operands()`：`+` 链中只要有字面量操作数，则每个非 `seg(...)`、根标识符是 String 形参的操作数都算 raw；经一跳局部时看其右值（含 `seg/enc/encode/URLEncoder.encode/.replace` 视为既定预编码例外） |
| 07-3 | key 文本含状态名 | `expr_reads_state(key, names)`：先折叠常量（`const_bool`/`const_value`/纯字面量），再沿 `scope_calls` 判「接收者被丢弃」（`x.let { Unit }` / `x.let { 0 }` ⇒ 不响应）；`x.let { it.id }` 仍算响应 |
| 07-4 | `seeds - ident_roots(keys)` | 逐 seed 要求**某个 key 分片的值依赖它**（`key_parts` 按顶层逗号切 + 同一个 `expr_reads_state`），并把折叠成常量的 key 写进报错文案 |
| 07-5 | 只 3 条正则 | 拿到 `collectAsState` 收集的变量名，对**每个消费**该状态的 `LaunchedEffect` 复用 07-3 的判据；无消费者 ⇒ fail-closed 红 |
| 07-6 | `startActivity(<n>)` 存在 | `live_launch()`：`path_feasible()` 判恒假守卫（`if (false)` / 矛盾条件）、再要求调用前无同深度或更浅的 `return`（含 `return@label`）——构造 ≠ 发射 |

### 门 B

| # | 旧形态 | 新形态 |
|---|---|---|
| A08-1 | `EMPTY_MAPPING_RE` 只看构造器 | `map_value_state(code, lit)` → `empty / blank / truncated / ok`：① 求值链上有 `take/drop/filter/filterNot/takeWhile/dropWhile` ⇒ `truncated` ② 零参构造器 ⇒ `empty` ③ 字面量视图里出现 `""` ⇒ `blank`（空串 id 匹配不到任何 models.dev 行） |
| A08-2 | `to` 右侧**含** `ids` | 右侧必须**恰为**绑定名或 `type.modelsDevProviderIds`（`strip_parens` 后全等），任何 transform 都判红 |
| A08-3 | 三个属性名出现在块内 | 键链上不许有削减算子（`TRUNC_RE`）+ 三个键源必须都在 —— **集合是集合，不是文本** |
| A08-4 | `ENUM_ITER_RE` + 找后续 `.flatMap` | `derivation()` 要求枚举迭代与映射算子**紧邻**（中间只允许空白），被拦时报出拦截算子名 |
| A08-5 | 行剥注释 + 箭头到箭头 + 字典覆盖 | `branch_map()`：`code_view`（块注释不再复活分支）+ 深度 0 箭头 + **每条分支的完整标签列**（`ProviderType.xAI, ProviderType.kimiCode ->` 两个都覆盖）+ 右值 span 而非文本 |
| A08-6 | 行首锚 `^\s*NAME(` | `enum_constants()`：`code_view` → 取 `;` 之前的常量区（成员声明在后）→ 顶层逗号切分 → 每个 chunk 取前导标识符。**同一行多个常量全部可见**，注释里的常量不可见 |

### 自证臂（selftest）

- 门 A：13 → **19 臂**（新增 Z1…Z6，与本支 6 条见证一一对应）
  Z1 标签降级为死值 · Z2 id 在字面量外拼接 · Z3 `pendingCreate.let { Unit }` · Z4 两个 remember key 折常量 · Z5 ChatScreen `LaunchedEffect(Unit)` · Z6 `if (false) startActivity`
- 门 B：11 → **17 臂**（新增 N1…N6）
  N1 求值期清空（`.filter { false }`）· N2 `key to ids.take(0)` · N3 键链 `.filter {}` · N4 `values().take(0).flatMap` · N5 块注释 + 合并空分支 · N6 同行第二常量
- 每个新臂都断言**恰好 1 条（N5/N6 为 2 条）**违规且命中**自己那一条判据的文案**（`expect(... needle)`），避免「撞到别的判据也算过」的假臂。

---

## ④ 四层证据

**L1 本门**
```
$ python3 scripts/scan/deeplink_settings_routes_check.py --selftest   → DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS（19 臂）
$ python3 scripts/scan/deeplink_settings_routes_check.py .            → rc=0
$ python3 scripts/scan/provider_type_registry_check.py --selftest     → PROVIDER_TYPE_REGISTRY_SELFTEST=PASS（17 臂）
$ python3 scripts/scan/provider_type_registry_check.py .              → rc=0（2 arms clean）
```

**L2 反攻（硬验收）** —— 官方驱动 `verify_batch5.py`，未自造跑法：
```
deeplink  : 6/6 CAUGHT，restored 6/6 True   → probe/t06-reattack-deeplink.json
provider  : 6/6 CAUGHT，restored 6/6 True   → probe/t06-reattack-provider.json
```
每条都红在**它自己那条判据**上（摘录）：

| 见证 | 命中判据 |
|---|---|
| 07-1 | `emitter head 'mcp-servers' is not a `when (head)` match label in resolveSettingsDeepLink …`（DLH + http.py 各 1） |
| 07-2 | `Routes.modelEntryDetail interpolates raw id(s) ['entryId'] into a path segment …` |
| 07-3 | `LaunchedEffect(pendingCreate.let { Unit }) at line 99 still consumes the pending create request …` |
| 07-4 | ``var keyText by remember(editEntry.let { 0 }, prefillKey.let { 0 })` is not keyed on editEntry, prefillKey …` |
| 07-5 | `LaunchedEffect(Unit) at line 794 consumes pendingChatAction but its key never re-evaluates it …` |
| 07-6 | `the OpenAlarmList branch builds the AlarmClock.ACTION_SHOW_ALARMS Intent but never hands it to startActivity() on a reachable path …` |
| A08-1 | `[arm 1e] maps ProviderType.kimiCode to a list that is emptied by a collection operator after construction (`.filter { …` |
| A08-2 | `[arm 2e] the derived table maps every key to `ids.take(0)` instead of the family's ids …` |
| A08-3 | `[arm 2d] the derived key chain runs through a truncation operator (`.filter {`) …` |
| A08-4 | `[arm 2b] the enum iteration is intercepted by `.take` before the mapping block …` |
| A08-5 | `[arm 1e] … maps ProviderType.xAI / kimiCode to BLANK provider id(s) (`listOf("")`)`（2 条） |
| A08-6 | `[arm 1d] `modelsDevProviderIds`/`legacyProviderNames` does not cover ProviderType.deepSeek …`（2 条） |

反攻后 `git status --porcelain src/` = **0 行**（restore 逐字节还原）。

**L3 邻门**（11 道，全 rc=0）
`deeplink_settings_routes_check` · `open_url_broker_scheme_check` · `provider_ua_parity_check` · `provider_models_cache_wiring_check` · `provider_write_rules_check` · `provider_rpc_symmetry_check` · `memory_gate_provider_fields_check` · `model_entry_override_parity_check` · `session_provider_enabled_check` · `provider_type_registry_check` · `i18n_check` · `webapp_asset_url_cleanup_check`

**L4 全量**
```
$ nice bash scripts/scan/scan.sh        → rc=0，║ Summary: 112 passed, 0 failed
$ test_scan.py（分块驱动）              → 46/46 函数，344 cases，0 failing
```
> test_scan 的**整跑**在本机被沙箱 S28 窗口打死三次（`OSError: [Errno 38] Function not implemented`，一次在 `exec_gate_release_check` 处整壳被回收，一次连 fork 都 ENOSYS）。按批次惯例改**逐函数独立进程 + 断点续跑**（`probe/t06_testscan_loop.py` + `probe/t06_one_testfn.py`，`sys.argv[1]` 显式传 repo root，防 `REPO_ROOT` 走样成假红），46 个函数全部 PASS，累计 **344 cases / 0 failing**，与 CI 的 `ALL 344 CASES PASS` 同口径。

---

## ⑤ CI

**分支 CI = 确定性停止节点，已达成；未合并 main。**

| 项 | 值 |
|---|---|
| run | `40255774919` / **#2705** / attempt **1** |
| job | `121357176905`（build）— **success** |
| head_sha | `92bc6ddbc6c01a435ccf767c1f2b6e25fa1ced2e` = 本地 `git rev-parse HEAD`（逐字符一致） |
| steps | 26 步，唯一非 success = **step 23 `Publish to Releases` skipped**（`ref == main` 门控，符合预期） |
| 日志 | 1,482,238 B（`/tmp/t06-ci-job-121357176905.log`） |

日志逐条判读（`probe/t06_judge_ci.py`）：

| 判据 | 读数 |
|---|---|
| `Summary: 112 passed, 0 failed`（带框线） | **恰 1 条** |
| `ALL 344 CASES PASS` | **恰 1 条** |
| `^ e: `（Kotlin 编译错误） | **0** |
| 门头 `[N/112]` 编号 | **112 个互异** |
| 含 `FAILED` 的行 | **6 条，全部是 PASSED 用例名子串**（`… produces FAILED without calling provider PASSED` / `reason PERSISTENCE_FAILED maps to persistence_failed PASSED` / `phase FAILED maps to Failed PASSED` / `terminal FAILED maps to Failed PASSED` / `reason EXECUTION_FAILED maps to all_fallbacks_exhausted PASSED` / `… still reaches a legal FAILED terminal PASSED`） |
| `DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS` | **恰 1 条**（Step 3 pre-build scan gate 里真跑，19 臂有见证） |
| `PROVIDER_TYPE_REGISTRY_SELFTEST=PASS` | **恰 1 条**（同上，17 臂有见证） |

**CI 后源码树差异**：`git diff --name-only 92bc6ddbc6c01a435ccf767c1f2b6e25fa1ced2e..HEAD -- . ':(exclude)fix-reports'` = **0 行**（CI 之后只提交本报告）。

---

## ⑥ 残留（P2，各带触发条件 / 方案 / 搁置理由）

**R1｜`expr_reads_state` 只跟一跳作用域链**
- 触发条件：出现 `x.let { it.let { 0 } }` 或 `val k = x.let { 0 }` 再 `remember(k)` 这类「两层以上丢弃」写法。
- 方案：把 `scope_calls` 的判定改成递归（每层都要求 tail 依赖形参，任一层的 tail 折成常量即判丢弃）。
- 搁置理由：当前两门覆盖的 4 个 key 站点都是一层；多一层递归要处理 `it` 绑定与 shadowing，装置比例不划算（P2 判定）。

**R2｜`live_launch` 的早退判定按花括号深度，不建模 lambda 归属**
- 触发条件：`run { return@run }; startActivity(x)` 这类「同深度标签 return 后仍有合法发射」的写法出现在 OpenAlarmList 分支。
- 方案：为 `return@label` 求最近的 `label` 宿主 lambda 区间，只在同一 lambda 内判死。
- 搁置理由：方向安全（多判红、不静默放行），且当前分支是 `try { startActivity } catch`，没有 lambda；真出现时按红排查成本 1 分钟。

**R3｜`concat_operands` 只跟一跳局部右值**
- 触发条件：`val a = entryId` 再 `val b = a` 再 `"x/" + b`。
- 方案：把一跳扩成不动点（沿 `last_write` 迭代到无法再展开）。
- 搁置理由：与 R1 同族、同装置；且模板孔扫描（不在 `+` 链内的直接拼接）仍覆盖最常见的形态。

**R4｜门 B 的 `blank` 只判「字面量里出现空串」，不判全部元素为空**
- 触发条件：`listOf("anthropic", "")` 这类「混入一个空 id」的分支（当前也会判红——偏严，方向安全）。
- 方案：解析 `listOf(...)` 的元素集合，仅在**全部**为空时判红。
- 搁置理由：现在偏严不偏松，且真实分支都是单元素；误红会立刻被 `--selftest` 的真树臂暴露。

**R5｜`TRUNC_RE` 对任何 `filter/take/drop` 一律判红（含合法用途）**
- 触发条件：有人真需要 `listOf(...).filter { it.isNotBlank() }` 或键链 `.filter { it.isNotEmpty() }`。
- 方案：为「规范化型算子」开白名单（`isNotBlank/isNotEmpty`），或判「算子后的集合是否仍可能包含三个键源」的抽象求值。
- 搁置理由：刻意 fail-closed（宁可红也不静默丢键族）；升级信号 = 第一次因它误红。

**R6｜门 A 的 `dispatch_labels` 只认「函数体内 `when (head) { … }`」这一种调度形状**
- 触发条件：resolver 被重构成 `head.toSettingsRoute()` 扩展函数 / 映射表 / `when` 无花括号单表达式。
- 方案：把标签来源扩成「同一 `when` 的任一投影」（表达式体 `when`、`mapOf` 映射表）。
- 搁置理由：现在找不到 dispatch 就 fail-closed 红（不会静默放行），届时红信息已指明要重锚判据。

**R7｜门 B 的 `enum_constants` 依赖「常量区以 `;` 结束」**
- 触发条件：有人删掉 `;` 让编译器推断（Kotlin 允许常量区后无 `;`，此时 `split_top(";")` 返回整块，chunk 里会混入 `val builtInModels` 等成员）。
- 方案：chunk 过滤器已排除含 `val/var/fun` 的 chunk（当前实现已挡），仍需补一条 `--selftest` 臂证明「无 `;` 也能只取常量」。
- 搁置理由：当前文件有 `;`；chunk 过滤是既有的兜底，升级信号 = 该文件去 `;` 的那次改动。

---

## ⑦ `test_scan.py` 注册项

两门在基线里**已有注册**，无需新增（本支未新建门）：

```python
("deeplink_settings_routes_check", "DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS", (), (REPO_ROOT,)),   # L2773
("provider_type_registry_check",  "PROVIDER_TYPE_REGISTRY_SELFTEST=PASS",  (), (REPO_ROOT,)),     # L2901
```

标记串格式**未改动**（task §1.6），新增臂计数写在本报告 §③/§④：门 A 13→19 臂、门 B 11→17 臂。两门的 `--selftest` 在 `test_scan.py` 的 `test_da1016/1021` 等函数里被真实调用并通过（见 §④ L4）。

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t06

# 本门
python3 scripts/scan/deeplink_settings_routes_check.py --selftest
python3 scripts/scan/deeplink_settings_routes_check.py .
python3 scripts/scan/provider_type_registry_check.py --selftest
python3 scripts/scan/provider_type_registry_check.py .

# 反攻（官方驱动）
python3 ../verify_batch5.py --witnesses ../witnesses-B.json --repo . --mode full \
    --only deeplink_settings_routes_check.py --out ../probe/t06-reattack-deeplink.json
python3 ../verify_batch5.py --witnesses ../witnesses-A.json --repo . --mode full \
    --only provider_type_registry_check.py --out ../probe/t06-reattack-provider.json

# 全量
nice bash scripts/scan/scan.sh
python3 ../probe/t06_testscan_loop.py "$PWD" 0 46      # 沙箱 S28 健康路径：逐函数独立进程 + 断点续跑
```
