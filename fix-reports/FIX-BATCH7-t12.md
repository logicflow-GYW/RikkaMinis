# FIX-BATCH7-t12 — webview_holder_url_source / chat_menu_prefs_listener 判据加固

分支 `fix/b7-webviewholder-chatmenu`，基线 `base/batch7-shared`（`e145ec961` = main `6ac77c6b2`
+ 第五批共享原语 `_kotlin_valueflow`）。

改动面 = **2 个门脚本**（+828/−437），生产 `.kt` **0 行**、`scan.sh` / `test_scan.py` /
`_kotlin_{lexer,ast,valueflow}.py` 零触碰，门 112 / 夹具 344 不变。

---

## 1. 门与轴

| 门 | 见证 | 轴分布 |
|---|---|---|
| `scripts/scan/webview_holder_url_source_check.py` | 6（06-1..06-6，全为**实锤失明**） | dataflow 3 · anchor 1 · family-hole 2 |
| `scripts/scan/chat_menu_prefs_listener_check.py` | 6（06-1..06-5 实锤失明 + 06-6 已抓对照） | family-hole 3 · dataflow 1 · parser-death 1 · 对照 1 |

两门共症与第五/六批同族，但落点更靠「值」与「可达」：判据锚的是
**token 出现在某处**（调用名、常量名、`+=`、`key(` 里的名字、前缀里的 `window`），
缺陷动的是 **token 的效果**（值被换成回退臂 / 被 `.let` 换回旧状态 / 被 `+= 0`
抵消 / 守卫被 `|| true` 恒真 / `with(receiver)` 里裸写看不见 / `'"'` 字符字面量把
整行抹白）。

---

## 2. 见证原文（file / find → replace / 门为何绿）

### 2.1 webview_holder_url_source_check.py

| # | 轴 | file | find → replace（要点） | 门为何绿 |
|---|---|---|---|---|
| 06-1 | dataflow | `ui/preview/WebViewHolder.kt:226` | `currentUrl = fromAssetLoaderUrl(url, assetRootDir) ?: url` → 同一行插 `?.takeIf { false }` | W1 只 `find("fromAssetLoaderUrl(")` + 判是否在丢弃型 lambda 里 + 查 `url`/`assetRootDir`/`?:` 三个 token 在 RHS 里出现。`?.takeIf { false }` 既不是丢弃型 lambda，三个 token 也都在 → 绿。而真实求值是 `null ?: url` = **裸 URL**，映射结果被架空 |
| 06-2 | anchor | `WebViewHolder.kt:300` | `webView.loadUrl(loadUrl)`（第一处）→ `webView.loadUrl(currentUrl)` | W2 只判「文件里有 `val loadUrl = toAssetLoaderUrl(` 且 `loadUrl` 在 `loadUrl(` 附近」——映射仍算得出来，但加载点传的是裸 `currentUrl` |
| 06-3 | family-hole | `WebViewHolder.kt` `toggleDesktopMode` else 臂 | 追加 `with(webView.settings) { useWideViewPort = 1 == 2; loadWithOverviewMode = 1 == 2 }` | W5a 只认字面 `= false`；W5f 只匹配带前导点号的 `.field = …`——裸写（`with` / `apply` 接收者作用域）整段隐身，`1 == 2` 也不折叠 |
| 06-4 | dataflow | `ui/preview/WebPreviewBottomSheet.kt:212` | `onOpenExternal = { openExternalFromSheet(context, holder.currentUrl) }` → 包一层 `if (false) …` | W4a 只判「文件里出现 `openExternalFromSheet` + 读 `holder.currentUrl`」，不看调用点是否可达 —— 恒死分支里的调用照样计数，UI 上点按钮 100% 无响应 |
| 06-5 | family-hole | `ui/components/ImageGalleryViewer.kt` | `window?.let { WindowInsetsControllerCompat(it, view) }` → `window?.takeIf { false }?.let { … }` | W6e 只用正则看 `WindowInsetsControllerCompat(` 之前的**前缀**里有没有 window 局部名。`window?.takeIf { false }` 里 `window` 在，但链首被恒拒过滤器过滤成 `null` → 沉浸式状态栏失效（dv14-B1 ISSUE-02 回归） |
| 06-6 | dataflow | `ui/preview/WebPreviewBottomSheet.kt:250` | `key(holder) {` → `key(holder.let { 0 }) {` | W7 只判 `key(...)` 的**参数文本**里出现过 `holder`。`.let { 0 }` 让 Compose 拿到的键恒为 `0` → 换 holder 时不重建 `AndroidView`，屏幕上挂着已 `destroy()` 的旧 WebView（dv17-3 B-03 回归） |

### 2.2 chat_menu_prefs_listener_check.py

| # | 轴 | file | find → replace（要点） | 门为何绿 |
|---|---|---|---|---|
| 06-1 | family-hole | `ui/settings/ChatMenuSettingsScreen.kt` | 两个 `key == CONST` 分支包成 `(false && (… \|\| …))` | arm1 只判「`DisposableEffect` 块内出现过 `CONST_A` / `CONST_B`」——`false &&` 短路不影响名字出现 |
| 06-2 | dataflow | 同上 | `footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)` → 尾插 `.let { footerOrder }`（attachOrder 同） | `result_consumed()` 只看调用点**左侧**语句头有没有 `footerOrder =`——赋值号在，`.let` 尾巴把值换回旧列表，UI 保持陈旧，随后拖拽会把陈旧顺序写回磁盘覆盖外部恢复 |
| 06-3 | family-hole | 同上 | `if (draggingEntry == null) {` → `if (draggingEntry == null \|\| true) {` | `guarded_reads()` 只 `find("draggingEntry == null")` 再配平花括号，从不读整条条件 → 恒真守卫全绿，异步通知会在长按拖拽中途重排列表导致手势跳变 |
| 06-4 | family-hole | `agent/SoulStore.kt` `refreshCache` | `_writeVersion.value += 1` → `+= 0` | `bumps_write_version()` 第一行见 `+=` 即无条件放行，从不读操作数单位元 |
| 06-5 | parser-death | `ui/settings/SoulSettingsScreen.kt` | 效果体首行插 `val _q1 = '"'; if (loaded) return@LaunchedEffect; val _q2 = '"'` | 自写 `strip_comments_and_strings()` 不认字符字面量 `'"'`，第一处 `"` 开启字符串态、第二处才闭合，把中间的 `if (loaded) return@LaunchedEffect` 涂白 → arm7 的早退正则搜不到 |
| 06-6 | 对照（已抓，必须仍红） | `SoulStore.kt` | `_writeVersion.value = _writeVersion.value.also { }` | `_NOOP_SELF_RE` 已覆盖 `.also { }` 自赋值，门输出：`arm6: SoulStore.refreshCache(…) no longer bumps _writeVersion` |

---

## 3. 判据改造（旧形态 → 新形态）

### 3.1 webview_holder_url_source_check.py

**所有自写词法/结构代码删除**：`strip_comments()`（字符串里的 `}` 会截断函数体）、
裸花括号计数 `slice_balanced_body()` / `in_discarded_lambda()`、`enclosing_heads()`。
结构分析一律走共享原语：`code_view` / `literal_view`（等长视图，可回投原文）、
`match_bracket`（括号配平）、`split_top`（顶层操作符）、`const_bool` / `const_value`
（常量折叠）、`if_blocks`（if/else 区间）、`stmt_spans`、`expr_end`、`path_feasible`
（路径可达）、`value_sources`（丢弃型/值型作用域的值流）、`is_single_assignment`、
`call_args`、`contains_ident`；解析失败一律 fail-closed。

新增两个本地语义层（都建在共享原语之上，不自带词法器）：

- `provably_null(expr)`：折叠「值被架空」的三类形态 —— 恒拒过滤器
  （`takeIf { false }` / `takeUnless { true }`）、恒死分支（`if (1 == 2) a else null`）、
  值被换成 `null`（`?.let { null }`）、`as`/`as?` 强转（`(null as Window?)` 同 `null`）。
- `value_forms(expr)`：elvis 拆分时**左臂被证成恒 null 就不算候选值**（`?: url` 的
  回退臂才是发布出去的东西），叶子交给共享 `value_sources` 处理丢弃型
  （`also`/`apply`/`onEach` 只留接收者）与值型（`let`/`run` 取 lambda 尾表达式）作用域；
  `x.let { it }` 这类恒等包装按接收者还原。

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| W1 | RHS 里出现 `fromAssetLoaderUrl(` + 三个 token + 不在丢弃型 lambda 里 | `value_forms(RHS)` 里必须**真的还有**携带 mapper 调用的形态；`?.takeIf { false } ?: url` 折成 `['url']` → 红 |
| W2 | 文件里有 `val loadUrl = toAssetLoaderUrl(…)` 且 `loadUrl` 出现在 `loadUrl(` 附近 | `function_body(startIfNeeded)` 的**每个** `webView.loadUrl(…)` 实参必须经 `value_forms` 等于映射局部（且该局部 `is_single_assignment`）；传裸 `currentUrl` → 红 |
| W3d | `"file.name" in holder`（code 视图） | 回投 `literal_view`（`${file.name}` 在字符串模板里，code 视图会抹掉） |
| W4a | 文件里出现 `openExternalFromSheet` + `holder.currentUrl` | 实参恰为 `(context, holder.currentUrl)` **且** 调用点 `path_feasible` —— 恒死分支里的调用不算 |
| W4b | code 视图找 `holder.currentUrl.startsWith("file://")` | 改 `literal_view`（`"file://"` 是字面量，code 视图抹成空格 → 旧写法在新视图下会假红） |
| W5a | 只认字面 `= false` | `viewport_writes()` 统一收写入：**点号形态与接收者作用域裸写都算**；值 `const_bool` 为 `False` 即红（`false` / `1 == 2` / `!true` 同罪），并要求写入点可达 |
| W5b–W5e | 同前 | 保留；W5e 改用同一 `writes` 集合（不再单独跑一条正则） |
| W5f | 只匹配带前导点号的 `.field = <policy>.<field>` | 同上统一写入集合，`with(webView.settings) { field = … }` 里的裸写一并判定 |
| W6e | `WindowInsetsControllerCompat(` 之前的**前缀**里有 window 局部名 | 取整条 `val <ctl> = <expr>` 的 RHS（`expr_end`），切掉尾部 `?.let {` 开括号头 → 得到**接收者链**：必须 `contains_ident(window 局部)` 且 `provably_null(接收者)` 为假 |
| W7a–W7d | `key(...)` 参数文本里出现要求 token | 每个参数经 `value_forms` 规约，要求 token 出现在**值的形式**里，且值不得是常量 —— `key(holder.let { 0 })` 折成 `['0']` → 红；`key(holder.let { it })` 折回 `['holder']` → 绿 |
| 结构 | `slice_balanced_body` + 裸计数 | `effect_body()` / `key_wrapped_blocks()` 全部用 `match_bracket` 从 `(`/`{` 配平 |

自测：**16 臂**（原 14 组 + 6 条见证臂 + 3 条对照臂：恒等 `.let { it }`、合法
`takeIf { it.isNotBlank() }`、父括号 elvis）。收尾行保留
`WEBVIEW_HOLDER_URL_SOURCE_SELFTEST=PASS`，臂数单独一行
`WEBVIEW_HOLDER_URL_SOURCE_SELFTEST_ARMS=16`。

### 3.2 chat_menu_prefs_listener_check.py

**自写 `strip_comments_and_strings()` 删除**（不认 `'"'` 字符字面量 = 本门 06-5 的
parser-death 根因），改用 `code_view` / `literal_view` 双视图（等长、偏移可回投）：
结构判定在 code 上做，读字面量内容（raw key 名、`"logging_prefs"`）回投 literal。

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| arm1 谓词 | `DisposableEffect` 块内出现过 `CONST_A` / `CONST_B` | 先用 `if_blocks` 定位**守卫分支体（含 prefsTick / order 重读）所在的那条 if**，取它的整条条件，再用新增 `prune_bool()` 折叠：`false && X → false`（恒假分支剪掉）、`true \|\| X → true`、`!常量` 折叠、括号剥除；剪枝后骨架里必须仍能命中两个固定键（`contains_ident` 词边界，或 literal 里的原始 key 串）。`… \|\| (false && (key == A \|\| key == B))` → 骨架 `key != null && key.startsWith(…)` → 红 |
| arm2 值 | 调用点**左侧**语句头有 `target =` | 新增 `_carries()`（`value_sources` 规约 + 一跳局部绑定表 `_locals_map`）：`target = <rhs>` 的 **rhs 值**必须仍携带 `read` → `settingsPinOrder(prefs).let { footerOrder }` 折成 `['footerOrder']` → 红；`.let { it }` 按接收者还原 → 绿；`val o = read(…); target = o.filter{}` 仍绿（一跳委托） |
| arm3 守卫 | `find("draggingEntry == null")` + 配平花括号 | `if_blocks` 找条件含 `draggingEntry` 且含 `null` 的那条 if，用 `const_bool` 折叠整条条件：**恒真（`\|\| true`，守卫失效）或恒假（`&& false`，分支永不执行）都判红**；恒 None 才继续判「三条 read 都在 then 体内且值被消费」 |
| arm5/arm6 计数 | 见 `+=` / `-=` / `*=` / `/=` 即无条件放行 | 读复合赋值的**操作数**并 `const_value` 折叠：`+= 0` / `-= 0` / `*= 1` / `/= 1` 是单位元（StateFlow 不发射）→ 不算 bump；未知操作数保守放行；`+= 2` 仍绿 |
| arm7 早退 | 只认 `return\s*@LaunchedEffect` | 词法交给共享词法器（`'"'` 不再能抹白），早退扫描放宽到任意 `\breturn\b` 出现在 `SoulStore.load(` 之前（裸 `return`、`?: return` 同族），并打印命中片段便于排障 |
| arm4/arm8 | 自写 strip 视图 | `views()` 双视图；arm8 的 `"logging_prefs"` 字面量检查回投 literal |
| 锚点 | `DisposableEffect\b[^\n{]*\{` + 裸计数 | `DisposableEffect(` + `match_bracket` 取 trailing lambda |

自测：**26 臂**（原 18 + 5 条见证臂 + 3 条对照臂）。保留
`CHAT_MENU_PREFS_LISTENER_SELFTEST=PASS`，臂数单独一行
`CHAT_MENU_PREFS_LISTENER_SELFTEST_ARMS=26`。

---

## 4. 四层证据

**L1 本门（真树 + 自测）**

```
python3 scripts/scan/webview_holder_url_source_check.py .      → rc=0, Summary: 25 passed, 0 failed
python3 scripts/scan/webview_holder_url_source_check.py --selftest → rc=0, ..._SELFTEST=PASS
python3 scripts/scan/chat_menu_prefs_listener_check.py .       → rc=0, OK (7 sites checked)
python3 scripts/scan/chat_menu_prefs_listener_check.py --selftest → rc=0, ..._SELFTEST=PASS
```

**L2 反攻（硬验收）**：用任务书指定的驱动 `verify_batch5.py --mode full --only <gate>`，
逐门 6 条，`find` 命中数必须为 1，跑完按 sha256 还原。

| 门 | 基线（改前） | 改后 | restore |
|---|---|---|---|
| webview_holder_url_source | 6 × `BLIND_CONFIRMED`（rc=0） | **6 × `CAUGHT`（rc=1）** | 6/6 True |
| chat_menu_prefs_listener | 5 × `BLIND_CONFIRMED` + 06-6 `CAUGHT` | **6 × `CAUGHT`（rc=1）** | 6/6 True |

**归因（每条红在应当红的判据上，不是顺带红）**

| # | 红的判据 | 门原始输出片段 |
|---|---|---|
| wv 06-1 | W1 | `RHS = 'fromAssetLoaderUrl(url, assetRootDir)?.takeIf { false } ?: url'; value forms = ['url'] — the mapper's result never reaches the published value` |
| wv 06-2 | W2 | `a load site passes 'currentUrl' instead of the mapped local 'loadUrl'` |
| wv 06-3 | W5a + W5f ×2 | `constant-false write 'loadWithOverviewMode = 1 == 2' … flips the mobile viewport off` / `assigned '1 == 2'` |
| wv 06-4 | W4a | `the call is spelled correctly but sits on an infeasible path (dead branch: if (false) / && false / 1 == 2)` |
| wv 06-5 | W6e | `` `controller` is built from `window?.takeIf { false }` — that receiver chain is provably null `` |
| wv 06-6 | W7a | `key(holder.let { 0 }) resolves to ['0'] — the key value is a constant` |
| cm 06-1 | arm1 | `listener predicate does not match the fixed key(s) … predicate skeleton after folding constant branches: key != null && key.startsWith( )` |
| cm 06-2 | arm2（+arm3） | `calls the order re-read(s) settingsPinOrder(, resolveAttachOrder( but discards the result` |
| cm 06-3 | arm3 | `the order re-reads are not inside the draggingEntry == null guard` |
| cm 06-4 | arm6 | `arm6: SoulStore.refreshCache(…) no longer bumps _writeVersion` |
| cm 06-5 | arm7 | `returns before re-reading the file ("return@LaunchedEffect; val _q2 = ' '")` |
| cm 06-6 | arm6（对照，仍红） | 同上 |

`git status --porcelain src/` 全程为空（12 次 patch/restore 后无残留）。
原始读数：`probe/t12-{baseline,post}-{wv,cm}.json`。

**L3 邻门（语义相邻 10 道，全 rc=0）**：`webapp_asset_url_cleanup_check` ·
`file_uri_parse_guard_check` · `file_preview_url_encode_check` ·
`filepreview_print_bounded_check` · `katex_ready_race_check` ·
`browser_tab_exec_serial_check` · `open_url_broker_scheme_check` ·
`deeplink_settings_routes_check` · `modeluse_proto_single_source_check` ·
`marker_boundary_check`。

**L4 全量**

```
bash scripts/scan/scan.sh      → 112 passed, 0 failed（本窗口一次跑通，未触发 S28）
python3 scripts/scan/test_scan.py → 被 stall-guard 杀（本机已知），改分块驱动
  probe/t12_testscan_chunks.py（12 chunk × 4 函数）
  → 340 pass / 4 fail；4 条落在 test_da1016_gates(3) 与 test_auto_approve_gates(1)，
    与本支改动无交集，单进程单跑两个函数 = pass+=18 / pass+=2，fail+=0 → 环境抖动，
    合计 340 + 4 = 344 = ALL 344 CASES PASS 的基线数
```

---

## 5. CI

- **run** `40270861785` / **#2720** / job `121403160581` / attempt **1** / `conclusion = success`
  （04:47:26Z → 05:06:36Z，19m10s；workflow_dispatch）
- **head_sha 三方逐字符一致**：本地 `git rev-parse HEAD` = API `run.head_sha` =
  `3786594819f606a6bcfc3ac0aec71c4834f35417`（门提交 `378659481`；报告提交在 CI 之后，
  `git diff --name-only 378659481..HEAD -- . ':(exclude)fix-reports'` = 0）
- **26 步，唯一非 success = step 23 `Publish to Releases` skipped**（main 门控，预期）
- **日志** `probe/t12-ci-job.log`，1,486,633 B，裸 NUL 0 字节
- 读数逐条核过：
  - `Summary: 112 passed, 0 failed` 恰 **1** 次
  - `ALL 344 CASES PASS` 恰 **1** 次
  - 裸 ` e: ` 行 **0**
  - 含 `FAILED` 的 **6** 行逐条确认全是 **PASSED** 用例名子串（末尾均为 `PASSED`）
  - 门头 `[N/112]` 编号互异 **112** 个
  - `WEBVIEW_HOLDER_URL_SOURCE_SELFTEST=PASS` / `CHAT_MENU_PREFS_LISTENER_SELFTEST=PASS`
    各恰 **1** 行 ⇒ **新臂在 CI 里有见证**；同门真树行（`GATE=GREEN webview_holder_url_source`
    与 chat 的 `both key constants are non-private`）各 1 行 ⇒ 新判据在 CI 真树上没误伤
  - `*_SELFTEST_ARMS=` 在 CI 日志 **0 命中**（未注册进 `test_scan.py`，见 R9）

---

## 6. 残留（均 P2：登记不修，各带触发条件 / 触发方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `provably_null` 只折**常量**谓词：`takeIf { it.length > 999 }` 这类运行时恒假过滤器不折 | 有人用可静态判定的值域（如 `it < 0` 对非负量）绕 W1/W6e | 接共享的整数/非空值域分析（`guard_feasible` 的 domains 参数） | 值域要逐调用点人工给，误红风险高于收益 |
| R2 | `prune_bool` 对未知标识符保守放行：`flag && (key == A \|\| key == B)` 只在 `flag` 恒假时才该红，现状判绿 | 出现以外部布尔短路谓词的形态 | 接入常量传播（`val flag = false`）后再剪 | 需要局部常量表，收益窄 |
| R3 | arm1 只看标识符是否出现在剪枝后的骨架里，**不判比较运算符**：`key != CONST_A` 也算命中 | 谓词写成 `key != A && key != B` | 命中处回看前 4 字符，`!=` 不计入 | 需要同时处理 `!(key != A)` 的等价反转，否则引入假红；单条修复易复发，留到有第二个样本时一起做 |
| R4 | `_carries` 的一跳局部委托按「**提及**即派生」判：`target = wrap(resolvedOrder)` 里 `wrap` 吞值也判绿 | 出现吃列表返回陈旧值的包装函数 | 换成完整 dataflow（只认实参位置） | 本仓无此形态，且 fail-open 方向不引入假红 |
| R5 | arm3 只认块体形态 `if (draggingEntry == null) { … }`；早退形态 `if (draggingEntry != null) return` 判红 | 真把守卫重构成早退 | 加早退形态识别（`returns_exiting`） | 本仓无此写法；提前实现等于给未发生的事建装置 |
| R6 | arm7 把 load 之前**任意** `return` 当早退，嵌套 lambda 里的 `return@run` 会误红 | 效果体里出现 `run { … return@run }` | 用 `returns_exiting` 区分裸 return / 带标签 | 现真树无此形态，等到第一次误红再改（避免提前装置） |
| R7 | 共享 `expr_end` 不在顶层 `;` 截断（连续两条赋值会并成一条） | 有人用分号写多语句 | 修 `_kotlin_valueflow.expr_end`（共享层，需全批回归） | 本门在 `viewport_writes` 内自行按 `;` 切分，已免疫；改共享层要动 24 门，不在本支半径内 |
| R8 | W2 依赖函数名 `startIfNeeded`，W5f 依赖 `val x = mobileViewportSettings()` 形态，重构即 fail-closed 红 | 函数改名 / 政策值经 helper 中转 | 从树派生站点（walk 调用点）而非钉名字 | 现在的红是**有信息的红**（报错文本写明"re-point this guard"），不是静默放行 |
| R9 | 两门的 `*_SELFTEST_ARMS=` 行是**手写值**且未注册进 `test_scan.py`：删臂不会让计数自己变红，CI 日志里也没有这条读数 | 有人删掉自测臂 | ①改成运行期计数（`expect()` 加计数器）②主会话在 `test_scan.py` 各加一行断言（见 §7） | 冻结面（`test_scan.py`）本支禁改；改成运行期计数会变动已提交的 CI 树（本支已 dispatch），留到并集收口时一并做 |
| R10 | webview W7 的 key 值只判「携带 token 且非常量」，不判「随数据源**变化**」：`key(holder.hashCode())` 这类派生值不判红 | 出现只依赖部分字段的 key | 判 key 实参是否覆盖全部数据源字段 | 需要知道数据源字段集，属于设计层知识 |

---

## 7. `test_scan.py` 注册项（交主会话统一注册）

两门**已在册**（`scripts/scan/test_scan.py:2771` / `:2808`，位于 `test_da1018_gates`），
注册的标记串保持不变（本支把臂数打印成**独立一行**，不破坏既有断言）：

```python
    ("webview_holder_url_source_check", "WEBVIEW_HOLDER_URL_SOURCE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("chat_menu_prefs_listener_check", "CHAT_MENU_PREFS_LISTENER_SELFTEST=PASS", (), (REPO_ROOT,)),
```

建议**新增**（主会话并集收口时統一加，本支未动冻结面）：

```python
    ("webview_holder_url_source_check", "WEBVIEW_HOLDER_URL_SOURCE_SELFTEST_ARMS=16", (), (REPO_ROOT,)),
    ("chat_menu_prefs_listener_check", "CHAT_MENU_PREFS_LISTENER_SELFTEST_ARMS=26", (), (REPO_ROOT,)),
```

臂数：webview 自测本波新增 **9 条断言组**（6 条见证 + 3 条对照）；chat 新增 **8 条**
（5 条见证 + 3 条对照）。两门的 `*_SELFTEST_ARMS=` 行是**手写声明值**（16 / 26），
不是运行期计数 —— 这一点已登记在 R9（手写值删臂后不会自己变红）。

---

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t12

# 本门
python3 scripts/scan/webview_holder_url_source_check.py . ; echo rc=$?
python3 scripts/scan/webview_holder_url_source_check.py --selftest ; echo rc=$?
python3 scripts/scan/chat_menu_prefs_listener_check.py . ; echo rc=$?
python3 scripts/scan/chat_menu_prefs_listener_check.py --selftest ; echo rc=$?

# 反攻（12 条见证，逐门）
cd /var/minis/shared/work/meta-audit-v5/batch7
python3 verify_batch5.py --witnesses witnesses-B.json --repo wt7/t12 --mode full \
  --only webview_holder_url_source_check.py --out probe/t12-post-wv.json
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt7/t12 --mode full \
  --only chat_menu_prefs_listener_check.py --out probe/t12-post-cm.json

# 全量
cd wt7/t12 && bash scripts/scan/scan.sh
python3 /var/minis/shared/work/meta-audit-v5/batch7/probe/t12_testscan_chunks.py .
git status --porcelain src/    # 必须为空
```
