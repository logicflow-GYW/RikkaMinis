# FIX-BATCH7-t05 —— `share_dir_prune_check.py` + `browser_tab_exec_serial_check.py` 见证收口

- 支线：`fix/b7-shareprune-tabexec`（工作树 `batch7/wt7/t05`）
- 基线：`base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`
- 门提交：`95c7e9520d2407b7b47688020853f2f5dc48c580`（两文件 +899/−427）
- 改动面：**仅两个门脚本**。生产 `.kt` 0 行；`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰；门 112 / `test_scan` 夹具 344 不变。

---

## 1. 门与轴

| 门 | 见证 | 实锤失明 | 对照臂（修前已抓） | 轴 |
|---|---|---|---|---|
| `share_dir_prune_check.py` | 6 | 5（05-1..05-5） | 1（05-6） | anchor 1 / family-hole 2 / dataflow 2 |
| `browser_tab_exec_serial_check.py` | 6 | 5（04-1..04-5） | 1（04-6） | dataflow 2 / order 1 / parser-death 1 / spelling 1 |

两门共症同族：**判据锚在「某个 token / 某种拼写存在」，缺陷动的是「那个 token 的效果」**。逐门的具体落点：

- `share_dir_prune_check`：锚「函数名存在」（Pruner 的声明头）、「调用存在」（prune 调用在不在）、「偏移顺序」（io<dir<prune<copy）、「大写拼写」（TTL 常量名）、「前缀匹配」（`.isSuccess`）。缺陷分别动：函数体入口短路、恒假守卫、实参换目录、局部变量别名、`|| true`。
- `browser_tab_exec_serial_check`：锚「函数名 `lockForTab`」「`.withLock` 存在」「rhs 前缀 `lockForTab(`」「字面量 `tab.manager.execute(`」「字面量 `if (implicitTab)`」，且**完全没剥注释**。缺陷分别动：实参常量槽位、空锁块、`.let{}` 换值、注释冒充锁块、`.let { it.execute(…) }` 拆拼写。

## 2. 见证原文（12 条，逐条）

### 门：`scripts/scan/share_dir_prune_check.py`

#### 05-1 — dataflow / 判据「arm5 single `internal fun pruneShareDir(` definition in FullscreenImageViewer.kt」
- 目标：`src/android/app/src/main/java/com/rikkaminis/app/ui/components/FullscreenImageViewer.kt`
- find：`internal fun pruneShareDir(dir: File) {\n    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000`
- replace：同前 + 首行插入 `    if (true) return`
- 门为何绿：`check_arm5()` 对 definer 的全部检查只有 `elif "internal fun pruneShareDir(" not in definer:` —— 只验**声明头**，从不提取函数体，更没查 `listFiles()` / `lastModified() < cutoff` / `.delete()` 是否可达。

#### 05-2 — family-hole / 判据「arm2 stagedShareUri IO/prune ordering & target contract」
- 目标：`webapp/WebAppActivity.kt`
- find：`                com.rikkaminis.app.ui.components.pruneShareDir(shareDir)`
- replace：`                if (false) com.rikkaminis.app.ui.components.pruneShareDir(shareDir)`
- 门为何绿：`check_arm2()` 只算 `io_idx < dir_idx < prune_idx < copy_idx` 四个子串偏移并核对第 0 个实参 == `shareDir`，没有任何可达性判断 —— `if (false)` 包住的调用偏移不变，门全绿而 sweep 永不执行。

#### 05-3 — dataflow / 判据「arm3 cacheDir/shared writers must be suspend + IO + prune」
- 目标：`ui/settings/ProviderDetailScreen.kt`
- find：`        com.rikkaminis.app.ui.components.pruneShareDir(dir)`
- replace：`        com.rikkaminis.app.ui.components.pruneShareDir(java.io.File(context.cacheDir, "noop"))`
- 门为何绿：wide-1024 的 `VIOLATION_WRONG_PRUNE_DIR` 只在 `check_arm2()` 里加了「实参等于 dir_var」核验；`check_arm3()` 仍只写 `prune = fn.find("pruneShareDir(")` + `prune > write`，**从不读实参**。同一个错误目录横向搬一条就到没核验的兄弟臂。

#### 05-4 — family-hole / 判据「arm5 filesDir/externalFilesDir growers keep bounded TTL」
- 目标：`ui/chat/ChatScreenHelpers.kt`
- find：`    com.rikkaminis.app.ui.components.pruneShareDir(dir)`
- replace：`    val NEVER_PRUNE_TTL = Long.MAX_VALUE` + `    com.rikkaminis.app.ui.components.pruneShareDir(dir, maxAgeMs = NEVER_PRUNE_TTL)`
- 门为何绿：`prune_ttl_problem()` 对第 2 个实参只用 `PRUNE_TTL_CONST_RE.fullmatch(val)`（`[A-Z][A-Z0-9_]*$`）判**名字的拼写**，既不解析标识符绑定的值，也不验证它是 pruner 侧声明过的 TTL 常量。审计方原话：连新建变量都不用，导入 `MAX_VALUE` 直接传也能过。

#### 05-5 — dataflow / 判据「arm6 [C-SILENT] share starter derives its verdict from runCatching { startActivity(...) }」
- 目标：`ui/settings/ProviderDetailScreen.kt`
- find：`    return runCatching { context.startActivity(chooser) }.isSuccess`
- replace：`    return runCatching { context.startActivity(chooser) }.isSuccess || true`
- 门为何绿：`check_handoff_src()` 取 `runCatching { … }` 右括号后 80 字符的 `tail`，用 `VERDICT_CONSUME_RE.match(tail)` 判**前缀** —— `tail` 以 `.isSuccess || true` 开头，`re.match` 命中 `.isSuccess` 即收工，后面的 `|| true` 完全不看。刚读到的判定布尔被同一个布尔表达式抹成常量真。

#### 05-6 — family-hole / 对照臂（修前已抓，修后必须仍抓）
- 目标同上；replace 为 `pruneShareDir(dir, maxAgeMs = 999_999_999_999L)`
- 修前门输出：`the prune call overrides the TTL with '999_999_999_999L' — an unbounded TTL makes the sweep a no-op`

### 门：`scripts/scan/browser_tab_exec_serial_check.py`

#### 04-1 — dataflow / 判据「C1 runAcquiredAction implicitTab arm locks on lockForTab(...)」
- 目标：`browser/BrowserTabPool.kt`
- find：`                    lockForTab(tab.id).withLock { tab.manager.execute(input) }`
- replace：`                    lockForTab(-1).withLock { tab.manager.execute(input) }`
- 门为何绿：`withlock_receivers()` 费力切出了括号实参当三元组第 3 项 `expr`，但 `check_source()` 解包后**从未使用 `expr`**，`receiver_is_per_tab()` 只比 `name == "lockForTab"`。隐式路径锁在一个永远不是任何 tab 的槽位上，per-tab 互斥完全消失。

#### 04-2 — order / 判据「C2 executeSerialized acquires lockForTab(tabId).withLock」
- find（真实代码）：`mutex.withLock {` + 注释 + `runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)` + `}`
- replace：`mutex.withLock { Unit }` 后把 `runAcquiredAction(...)` 移到锁块外
- 门为何绿：`executeSerialized` 一侧只做 `recv = withlock_receivers(s_src)` + 接收者校验，**没算 `withLock` 的块跨度**，也没查 `runAcquiredAction(` 落在块内。同一文件里 `runAcquiredAction` 一侧写了 `lock_span[0] <= hit < lock_span[1]`，这一侧漏了。

#### 04-3 — dataflow / 判据「C2 executeSerialized acquires lockForTab(tabId).withLock」
- find：`        val mutex = lockForTab(tabId)`
- replace：`        val mutex = lockForTab(tabId).let { kotlinx.coroutines.sync.Mutex() }`
- 门为何绿：标识符接收者用 `r"\bmutex\s*(?::[^=]+)?=\s*lockForTab\s*\("` 判绑定，**匹配到 `lockForTab(` 的左括号即成功**，不看括号闭合后是否跟了 `.let { Mutex() }`。实际值是 lambda 的尾巴（新 Mutex），per-tab 互斥丢失。

#### 04-4 — parser-death / 判据同上（C1）
- find：`                    lockForTab(tab.id).withLock { tab.manager.execute(input) }`
- replace：`                    // lockForTab(tab.id).withLock {` + `                    run { tab.manager.execute(input) }` + `                    // }`
- 门为何绿：全文件**没有任何注释剥离**；`balanced_block()` 只跳过引号字符串。于是注释里的 `lockForTab(tab.id).withLock {` 被当成接收者，`// }` 的 `}` 被当成块闭合，中间的 execute 判成「锁内」。

#### 04-5 — spelling / 判据同上（C1）
- find：`            val executed = if (implicitTab) {`
- replace：前置 `            if (implicitTab == true) return tab.manager.let { it.execute(input) }`
- 门为何绿：执行点用 `re.finditer(re.escape("tab.manager.execute("))` 找 —— `.let { it.execute(input) }`（或 `tab.manager\n.execute(input)`）不匹配连续字面量，门只看到下方死代码里被锁包裹的那一处。

#### 04-6 — dataflow / 对照臂（修前已抓，修后必须仍抓）
- find：`val mutex = lockForTab(tabId)`；replace 增加 `val localLock = kotlinx.coroutines.sync.Mutex()` 并让 `localLock.withLock`
- 修前门输出：`executeSerialized locks on localLock.withLock but localLock is not bound to lockForTab(...)`

## 3. 判据改造（旧形态 → 新形态）

底层一律换成共享层（`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`）。删掉的自写件：`share` 门的 `strip_comments`（自写状态机）、`extract_braced_block`（裸花括号计数）、`split_call_args`（自写配平）、`_strip_comments_keep_offsets`、`_brace_end`（自写括号扫描）；`browser` 门的自写 `balanced_block` / `extract_fun` / 无注释剥离的全文扫描。新增 `sys.path.insert(0, dirname(__file__))` 后按名导入共享原语。

### share_dir_prune_check

| 见证 | 旧判据 | 新判据 |
|---|---|---|
| 05-1 | `"internal fun pruneShareDir(" in definer` | `pruner_body_problem()`：`function_body` 取体 → 必须含 `listFiles(` / `lastModified(` / `.delete(` → `first_guaranteed_exit()` 判入口无「必然早退」（`return`/`throw`，以及条件可折叠为真的 `if (…) return`）。签名在、调用点在、实现不可达 = 红 |
| 05-2 | 四个子串偏移 + 第 0 实参 == dir_var | `prune_target_problem()` 内先 `path_feasible(cv_fn, pos)`：`if (false)` 包住的调用不可达即红（同族守卫判定，全门所有臂共用） |
| 05-3 | arm3 只比 `prune > write` | arm3/arm4/arm5/arm2 统一走 `prune_target_problem(lit_fn, cv_fn, where, want_dir)`：实参解析到 `val` 绑定或内联构造，`File(…cacheDir, "<lit>")` 的字面量必须等于该臂声明的 staging 目录（`share` / `shared`）；解析不出 → fail-closed 红 |
| 05-4 | `PRUNE_TTL_CONST_RE.fullmatch(val)`（拼写白名单） | `_ttl_bounded()`：常量折叠（`const_value`）+ 一跳局部绑定解析 + `MAX_VALUE`/`MIN_VALUE` 家族 + 7 天物理上界；具名常量只认 pruner 文件里 `const val` **声明过**的名字（`pruner_constants()`） |
| 05-5 | `VERDICT_CONSUME_RE.match(tail)` 只看前缀 | 消费前缀之后取**整条表达式**（`expr_end`）→ `verdict_saturating_operand()`：按顶层 `||`/`&&` 切（`split_top`），任一操作数 `const_bool` 折叠成饱和值（`|| true` / `&& false`）即红；`|| <未知布尔>` 不报（精确度臂守） |
| 05-6 | 数值字面量被正则挡住 | 同一 `_ttl_bounded()`：字面量 1e12 > 7 天上界 → 仍红（对照臂保持） |

### browser_tab_exec_serial_check

| 见证 | 旧判据 | 新判据 |
|---|---|---|
| 04-1 | `name == "lockForTab"`（实参 `expr` 丢弃） | `_per_tab_arg()`：实参不得是常量（`const_value`）、须是 `X.id`、或形参、或一跳绑定到前两者；`lockForTab(-1)` 红 |
| 04-2 | `executeSerialized` 只查 `.withLock` 存在 | `_withlock_span()` 取锁块跨度，`executeSerialized` 里每个 `runAcquiredAction(` 必须落在某个 `withLock { … }` 内 |
| 04-3 | rhs 正则前缀 `lockForTab\s*\(` | 标识符接收者按**值的来源**判：`last_write` 取整条 rhs → `value_sources()` → 任一来源里必须真有 `lockForTab(<per-tab 实参>)`；`.let { Mutex() }` 的值是 lambda 尾巴 → 红 |
| 04-4 | 无注释剥离 | `check_source()` 一律在 `mask_code()` 上做结构分析（注释与字符串内容抹平、长度不变），`KotlinLexError` → fail-closed 红 |
| 04-5 | `re.escape("tab.manager.execute(")` | 执行点锚 `.execute(`（`tab.manager\n.execute(`、`?.execute(`、`tab.manager.let { it.execute( }` 同样可见）；隐式条件用 `if_blocks()` + `contains_ident(cond, "implicitTab")` 定位（`if (implicitTab == true)` 不再绕开锚点），并优先选「then 支里就有执行点**且带 else**」的那个 if（提前返回的孪生 if 不能冒充锁跨度） |
| 04-6 | 标识符绑定正则 | 同一 `value_sources()` 路径：绑定值是 `Mutex()` → 红（对照臂保持） |

自测臂（`--selftest`）：
- `share`：新增 8 条（05-1..05-6 各一条 + 2 条精确度臂：pruner 声明的具名 TTL 常量必须被**接受**、`|| 未知布尔` 必须**绿**）。
- `browser`：新增 7 条（04-1..04-5 + 对照臂 04-6 + `if (implicitTab == true)` 等价拼写必须仍绿）。

## 4. 四层证据

### L1 本门（真树 + 自测）

```
$ python3 scripts/scan/share_dir_prune_check.py .
  [handoff] 14 derived source(s) (3 named + 11), 7 chooser site(s), 17 startActivity site(s)
share_dir_prune_check: OK (arm1 + arm2 + arm3 + arm4 + arm5 + arm6 green)     rc=0

$ python3 scripts/scan/share_dir_prune_check.py --selftest
CRASH_SHARE_SELFTEST=PASS
ARM5_FILESDIR_PRUNE_SELFTEST=PASS
SHARE_HANDOFF_SELFTEST=PASS
SHARE_HANDOFF_DERIVED_SELFTEST=PASS
SHARE_DIR_PRUNE_WITNESS_ARMS=PASS (8 witness arms: …)
SHARE_DIR_PRUNE_SELFTEST=PASS                                              rc=0

$ python3 scripts/scan/browser_tab_exec_serial_check.py .
PASS browser_tab_exec_serial (…)                                           rc=0

$ python3 scripts/scan/browser_tab_exec_serial_check.py --selftest
SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures)          ← 冻结断言串，原样保留
SELFTEST_ARMS=PASS (browser_tab_exec_serial: +4 receiver-identity arms)   ← 原样保留
BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS (15 arms: 3 shape fixtures + 4 receiver-identity
  + 6 witness(04-1..04-6) + 1 equivalent-spelling clean arm + 1 stripped-mutex arm)  rc=0
```

### L2 反攻（硬验收，用任务书指定的驱动）

```
python3 batch7/verify_batch5.py --witnesses batch7/witnesses-A.json --repo . --mode full \
        --only share_dir_prune_check.py --out batch7/probe/t05-share_dir_prune_check.json
[1/6] 05-1 -> CAUGHT (rc=1, exact) …
SUMMARY: {'CAUGHT': 6}

… --only browser_tab_exec_serial_check.py --out batch7/probe/t05-browser_tab_exec_serial_check.json
[1/6] 04-1 -> CAUGHT (rc=1, exact) …
SUMMARY: {'CAUGHT': 6}
```

12/12 `CAUGHT`（修前 10 条 `BLIND_CONFIRMED` + 2 条对照臂也 CAUGHT），`restored=True` 12/12，事后 `git status --porcelain src/` 为空（无变异残留）。

### L3 邻门（语义相邻，逐门独立进程）

`browser_destroy_wakeup_check` / `browser_tabs_lifecycle_check` / `browser_history_snapshot_check` / `browser_cookie_ui_refresh_check` / `provider_ua_parity_check` / `filepreview_print_bounded_check` / `downloads_atomic_update_check` / `parts_json_funnel_check` / `a11y_handler_contract_check` / `offload_cli_args_check` —— **10/10 rc=0**。

### L4 全量（沙箱症状与替换路径）

- `bash scripts/scan/scan.sh`：本机并发窗口内被 stall-guard 杀（`exit 125`，无输出 180s）。
  **健康路径**（`probe/t05_gate_loop.py`，从 scan.sh 抽取调用形态、逐门独立进程、`shlex` 切 `--root "$ROOT"`）：
  **110/110 rc=0，464s**。余两步单独跑：`i18n_check.py .` → rc=0（`Done: ALL CLEAN`）。
- `python3 scripts/scan/test_scan.py .` 整跑：`OSError: [Errno 38] Function not implemented`（fork/exec 不可用窗口，S28）。
  **健康路径**（`probe/t05_testscan_loop.py`：只 import 不修改冻结文件、monkey-patch `run_scanner` 对 OSError 退避重试、逐 test 函数 + `SIGALRM` 170s 守卫 + 失败回滚计数器）：
  **46/46 函数，PASS=344 FAIL=0**（`SUMMARY: PASS=344 FAIL=0`，与 CI 的 `ALL 344 CASES PASS` 同一计数来源）。`test_real_repo` 因 170s 守卫过紧重试 2 次后成功（+20）。
- `git status --porcelain src/` 为空。

## 5. CI

- run：**#2716 / `40266866249`**（`workflow_dispatch`，`build-apk.yml`，ref `fix/b7-shareprune-tabexec`）
- job：`121391134070`（`build`），attempt **1**，`2026-10-26T04:01:08Z → 04:23:31Z`（22m23s）
- head_sha：`95c7e9520d2407b7b47688020853f2f5dc48c580` —— 与本地 `git rev-parse HEAD` **逐字符一致**
- conclusion：**success**；26 步，唯一非 success = step 23 `Publish to Releases` **skipped**（`if: github.ref == 'refs/heads/main'` 门控）
- 日志 1,486,067 B（0 个 NUL 字节），逐条判读：
  - `Summary: 112 passed, 0 failed` —— 恰 **1** 条
  - `ALL 344 CASES PASS` —— 恰 **1** 条
  - ` e: ` —— **0** 条（无编译错误行）
  - 含 `FAILED` 的行 **6** 条，逐条确认全是 `PASSED` 用例名子串（`… produces FAILED without calling provider PASSED` 等）
  - 门头 `[N/112]` 互异 **112** 条（1..112）
  - 本次改动的两门在 CI 真树上绿：`share_dir_prune_check: OK (arm1 + … + arm6 green)` 恰 1 条、`PASS browser_tab_exec_serial (…)` 恰 1 条
  - 已注册标记在 CI 里可见：`SHARE_DIR_PRUNE_SELFTEST` 1 条、`SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures)` 1 条
  - **新增标记不在 CI 日志里**（`SHARE_DIR_PRUNE_WITNESS_ARMS` 0 条、`BROWSER_TAB_EXEC_SERIAL_SELFTEST` 0 条）—— 门自证 stdout 不进 CI 日志是既知形态，注册项见 §7
- 注：#2715（run `40266697723`，sha `3cfd5b4bb`）是本支 amend 前的旧 run，已被 `cancelled`；本报告只认 #2716。

## 6. 残留（P2，均带三件）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_per_tab_arg` 的兜底 `any(contains_ident(a, p) for p in params)`：实参是「常量与 tab 形参混算」的表达式（如 `lockForTab(0 * input.tabId)`）会放过 | 真出现把常量与 tab 形参混算的 `lockForTab(…)` 实参 | 对实参做整数域分析（`guard_feasible` 的 domains） | 现无此写法；纯常量已被 `const_value` 挡住，混合式无法证明是缺陷 |
| R2 | `_params_of` 取参数写法的最后一个 token 作形参名，`vararg x: T` / 注解参数会误取 | 该门覆盖的函数出现 `vararg` 或参数注解 | 解析时剥 `vararg`/注解并取 `:` 前标识符 | 现状 `runAcquiredAction` / `executeSerialized` 都是 `name: Type` |
| R3 | `share` 门的顺序判定仍用 `fn.find("pruneShareDir(")`（首个匹配）：同一函数里出现第二个 prune 时只有第一处参与 io/dir/prune/write 顺序 | 某 writer 函数出现第二个 `pruneShareDir` 调用 | 用 `prune_calls()` 的全量位置参与顺序判定 | TTL 判定已是全量；现状每函数一处 |
| R4 | `want_dir` 在 `UI_WRITERS` 上写死 `"shared"` | 新增一个写 `cacheDir/share`（单数）的 writer 并登记进 `UI_WRITERS` | 把目录名做成 `UI_WRITERS` 的三元组元素 | 现有两个 writer 都是 `shared` |
| R5 | `prune_target_problem` 只取第 1 个实参，若改为命名实参 `pruneShareDir(dir = …)` 会解析失败 → fail-closed 红 | 有人把调用改成命名实参 | 剥 `name = ` 前缀后再解析 | 方向安全（不静默放行），现状全是位置实参 |
| R6 | `first_guaranteed_exit` 只认 `return`/`throw` 与「条件可折叠为真」的 if；`error()` / `require(false)` / `when (true)` 这类必然抛出不算 | pruner 改成用抛异常短路 | 加 `error(`/`require(`/`check(` 的必然抛出识别 | 现状实现只有 `return` |
| R7 | `browser` 门把 `runAcquiredAction` 内**所有** `.execute(` 都当 WebView 驱动调用 | 该函数将来调用别的 `.execute(`（如 `executor.execute`） | 按接收者链限定 `manager` | 当前该函数只有 `tab.manager.execute(` |
| R8 | `_withlock_span` 取 `.withLock` 之后第一个 `{`：`withLock({ … })`（lambda 作实参）形态会取到内层块 | 出现 `withLock({` 写法 | 若 `(` 紧邻 `withLock` 则先取 `(` 的配对 | 全仓无此形态 |
| R9 | TTL 上界 7 天是门内常量：业务若合法需要 30 天 TTL，必须先在 pruner 里声明具名常量才放行 | 出现 >7 天的合法 TTL 需求 | 上界改由 pruner 侧声明常量承载 | 现状没有任何 `maxAgeMs` 覆盖调用 |
| R10 | 共享原语小坑：`expr_end` 在「下一行以 `}`/`)` 开头」时把行尾空白并进表达式（本门用 `.strip()` 规避） | 需要「表达式终点严格等于语句终点」的判据 | 建议主会话在 `_kotlin_valueflow.expr_end` 排除 `}`/`)`/`]` 作续行标记 | 不影响本门判决（`split_top` 按深度切，尾随空白不改变操作数） |

## 7. `test_scan.py` 注册项（交主会话统一注册）

本支**未改** `test_scan.py`（冻结面）。两个既有注册行**原样有效**，不需要改：

```python
# DA1016_GATES（test_scan.py:2697）—— 形态 (script, marker, real_tree_argv)
("browser_tab_exec_serial_check", 'SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures)', (REPO_ROOT,)),
# DA1022_GATES（test_scan.py:2839）—— 形态 (script, marker, selftest_extra_argv, real_tree_argv)
("share_dir_prune_check", "SHARE_DIR_PRUNE_SELFTEST=PASS", (), (REPO_ROOT,)),
```

建议各追加一行，给本波新臂在 CI 里一个独立见证（现在它们只被 `--selftest` 覆盖，CI 日志里不可见）：

```python
# DA1016_GATES 追加
("browser_tab_exec_serial_check", "BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS", (REPO_ROOT,)),
#   → 15 臂：3 shape fixtures + 4 receiver-identity + 6 witness(04-1..04-6)
#           + 1 等价拼写 clean 臂 + 1 stripped-mutex

# DA1022_GATES 追加
("share_dir_prune_check", "SHARE_DIR_PRUNE_WITNESS_ARMS=PASS", (), (REPO_ROOT,)),
#   → 8 臂：05-1 pruner 短路 / 05-2 恒假守卫 / 05-3 arm3 换目录 / 05-4 TTL 局部别名 /
#           05-5 `|| true` / 05-6 TTL 魔数 / + 精确度两臂（具名常量须接受、`|| 未知布尔` 须绿）
```

（`SELFTEST_ARMS=PASS (… +4 receiver-identity arms)` 同样原样保留。）

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t05

# L1 本门
python3 scripts/scan/share_dir_prune_check.py .
python3 scripts/scan/share_dir_prune_check.py --selftest
python3 scripts/scan/browser_tab_exec_serial_check.py .
python3 scripts/scan/browser_tab_exec_serial_check.py --selftest

# L2 反攻（12 条见证必须全 CAUGHT 且 restored=True）
python3 ../verify_batch5.py --witnesses ../witnesses-A.json --repo . --mode full \
    --only share_dir_prune_check.py --out ../probe/t05-share_dir_prune_check.json
python3 ../verify_batch5.py --witnesses ../witnesses-A.json --repo . --mode full \
    --only browser_tab_exec_serial_check.py --out ../probe/t05-browser_tab_exec_serial_check.json
git status --porcelain src/          # 必须为空

# L3/L4（沙箱健康路径：逐门独立进程 + 逐 test 函数）
python3 ../probe/t05_gate_loop.py .          # 110/110
python3 ../probe/t05_testscan_loop.py .      # PASS=344 FAIL=0
python3 scripts/scan/i18n_check.py .
```
