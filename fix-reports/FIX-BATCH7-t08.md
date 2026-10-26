# FIX-BATCH7-t08：`browser_destroy_wakeup_check.py` + `mirror_category_route_check.py` 判据加固

支线：`fix/b7-browserdestroy-mirrorroute`（工作树 `wt7/t08`）
基线：`base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享 `scripts/scan/_kotlin_valueflow.py`）
门提交：`381beee3643eddb5b65d88e8b90ac019e9b2d094`
改动面：2 个门脚本 **+985 / −461**；生产 `.kt` **0 行**；`scan.sh` / `test_scan.py` /
`_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` **零触碰**；门 112 / 夹具 344 不变。

硬约束落实（§1.1）：两门里自写的词法器与花括号体提取（`skip_string` / `skip_template` /
`strip_comments` / `code_only` / `brace_body` / `function_body` / `_balanced` /
`extract_braced`）**全部删除**，统一走共享 `mask_code` / `mask_comments` /
`function_body` / `match_bracket` / `call_args` / `expr_end` / `value_sources` /
`scope_calls` / `guard_feasible` / `last_write`。两门 `KotlinLexError` 一律 fail-closed
（报违规，不回退原文扫描）。

## 1. 门与轴

| 门 | 见证 | 修前 | 轴分布 | 修后 |
|---|---|---|---|---|
| `browser_destroy_wakeup_check.py` | 07-1…07-6（6 条） | 5 `FALSIFIED` + 1 `CAUGHT`（07-6 对照） | dataflow ×3 / order ×1 / family-hole ×2 | **6/6 `CAUGHT`** |
| `mirror_category_route_check.py` | 04-1…04-6（6 条） | 6 `FALSIFIED` | family-hole ×4 / dataflow ×1 / order ×1 | **6/6 `CAUGHT`** |

两门共症：**判据锚「某个拼写/某个调用存在」，缺陷动它的效果**——
取出的值被丢弃、守卫被 `&& false` 短路或极性反转、参数换常量、谓词恒真、
解析结果被 `.takeIf` 丢弃、`var` 被事后重赋。

## 2. 见证原文（file / find / replace / 门为何绿）

### 07-1 dataflow — M2 onRendererGone does not wake the async-JS waiter
文件：`browser/BrowserUseManager.kt`
find：
    asyncJsSlot.takeCurrent()?.complete(
        "{\"error\":\"WebView renderer process was killed before the async JS request completed\"}"
    )
replace：
    asyncJsSlot.takeCurrent()
门为何绿：`DESTROY_MUST` / `RENDERER_MUST` 为 async-JS 等待者定义的锚点只是子串
`asyncJsSlot.takeCurrent()`（navigation 等待者写的才是 `navigationDeferred?.complete(`），
`?.complete(…)` 被删掉仍满绿。
回归后果：渲染进程被杀后槽位被取空、deferred 被丢弃，在途 `executeJS`/`fetch`
既收不到回调也收不到崩溃通知，白等 30s/60s 再报「JS 执行超时」。

### 07-2 order — S1 AsyncJsSlot.takeCurrent() reads, clears, and returns
文件：`browser/AsyncJsSlot.kt`
find：
    fun takeCurrent(): T? {
        val current = entry ?: return null
        entry = null
        return current.value
    }
replace：把 `entry = null` 移到 `val current = entry ?: return null` **之前**。
门为何绿：`analyse_slot()` 分别独立测 `SLOT_READ_RE` / `SLOT_CLEAR_RE` / `SLOT_RETURN_RE`
三个布尔，**从不校验读偏移 < 清偏移**。
回归后果：`takeCurrent()` 恒返回 null，销毁时无法唤醒任何等待者。

### 07-3 family-hole — L1 KaTeXView deferred block stands down on release
文件：`ui/markdown/KaTeXView.kt`
find：`if (!releaseLatch.canRunDeferred()) return@Runnable`
replace：`if (false && !releaseLatch.canRunDeferred()) return@Runnable`
门为何绿：`deferred_body_guarded()` 对条件只做 `"canRunDeferred(" in cond or "isReleased" in cond`，
既不看极性也不折叠常量短路。
回归后果：延迟截图在已销毁 WebView 上画出全透明位图并写入 `KaTeXRendererCache`，
该公式在进程生命周期内永远空白。

### 07-4 dataflow — U1 URLDecoder.decode() without the '+' escape
文件：`browser/BrowserUseManager.kt`
find：`java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")`
replace：`payload.replace("+", "%2B").let { java.net.URLDecoder.decode(payload, "UTF-8") }`
门为何绿：`scan_lines()` 只拼 `i-2 … i+4` 的行窗口查 `'.replace("+", "%2B")' in window`，
不解析第一实参到底是什么。
回归后果：下载 `data:` 文本时字面量 `+` 被静默换成空格，文件数据损坏。

### 07-5 family-hole — C3 setUserAgent writes without the destroyed latch
文件：`browser/BrowserUseManager.kt`
find：`if (destroyed) {`（setUserAgent 入口，块内含 Log.w + return）
replace：`if (destroyed && false) {`
门为何绿：C1/C3 共用 `LATCH in body`（`"destroyed" in body` 裸子串）。
回归后果：标签页销毁后到达的 UA 切换 Runnable 继续写 `webView.settings.userAgentString`
并 `reload()`。

### 07-6 dataflow — S1 takeCurrent() no longer returns the taken value（对照，修前已抓）
文件：`browser/AsyncJsSlot.kt`
find/replace：`return current.value` → `return current.let { null }`
门为何绿（前）：`SLOT_RETURN_RE` 要求 `return <…>.value`，故被抓。**修后仍被抓**（新判据更强：
见 R6 的 `current.value.let { null }` 变体，修前可绕、修后判红）。

### 04-1 family-hole — arm2 rootfs_management onMirrorCategoryClick
文件：`ui/navigation/AppNavigation.kt`
replace：`if (false) navController.safeNavigate(Routes.mirrorCategoryDetail(cat.key))`
门为何绿：`navigates_with_route()` 只查 `builder + "(" in call_args(...)` 且不在
`.also`/`.apply` 中，不看外层死条件。
回归后果：点击任意镜像分类行彻底无反应（da1021-C 原始症状复发）。

### 04-2 dataflow — arm2 同上判据
replace：`Routes.mirrorCategoryDetail("")`（丢弃 lambda 入参 `cat.key`）
门为何绿：只查 `"Routes.mirrorCategoryDetail(" in call_args(...)`，不查实参是否派生自 `cat`。
回归后果：`fromKey("")` 返回 null → 目标页立即 `safePopBackStack()`，所有分类详情页闪退。

### 04-3 family-hole — arm1b detail destination category resolution
replace：在 `?.let { MirrorCategory.fromKey(it) }` 后追加 `?.takeIf { false }`
门为何绿：`in_discarded_lambda()` 只认 `.also`/`.apply` 块头；`takeIf` 丢弃值不被识别。
回归后果：解析成功的分类被丢弃 → 详情页恒走「category == null」回退分支。

### 04-4 family-hole — arm3 fromKey fail-closed and discriminates
文件：`ui/sandbox/MirrorCategory.kt`
replace：`entries.firstOrNull { it.key == key }` → `{ it.key == key || true }`
门为何绿：只 `re.search` 谓词里含 `== key`，不看 `|| true` 恒真。
回归后果：任意 key 都解析成第一个分类，详情页内容全错。

### 04-5 family-hole — arm1 screen_defined_and_called
replace：`if (category == null)` → `if (category == null || true)`
门为何绿：只查 `MirrorCategoryDetailScreen(category = category)` 调用存在与 `val category`
声明表达式，从不看包裹它的 if/else 条件。
回归后果：详情页永不渲染，进入即弹回。

### 04-6 order — arm1b detail destination category resolution
replace：`val category = …` 改 `var`，并在其后再写 `category = null`
门为何绿：声明正则显式允许 `var`，且 `statement_expr` 遇到下一行 `category = null`
（不以 `?.`/`.` 开头）立即 break，看不到重赋值。
回归后果：解析结果被事后抹平，详情页恒回退。

## 3. 判据改造（旧形态 → 新形态，逐条）

| 见证 | 旧判据（拼写/存在） | 新判据（效果/可达/值流） |
|---|---|---|
| 07-1 | 子串 `asyncJsSlot.takeCurrent()` | `take_wakes_waiter()`：take 必须可达，且其返回值在同一表达式内送达 `.complete(`（直连 `?.complete` 或经 `?.let { it.complete(...) }`）；裸 take 丢弃 deferred → 红 |
| 07-2 | 读/清/返三条正则各自为真 | 读偏移必须**早于**清偏移（`SLOT_READ_RE` min pos < `SLOT_CLEAR_RE` min pos） |
| 07-3 | 块首是 if + 提到 canRunDeferred/isReleased | 条件**可行**（`guard_feasible` 折叠 `false &&` / `!true` / 字面量比较）+ **极性正确**（否定 canRunDeferred 或正向 isReleased），且是块首语句 |
| 07-4 | 行窗口共现 `.replace("+", "%2B")` | `call_args` 取第一实参 → `value_sources`（含作用域块尾）必须派生自带转义 `.replace` 的值；转义作用在别的值上 → 红 |
| 07-5 | `"destroyed" in body` | `latch_guard()`：守卫条件提闩锁、可行、未取反、分支真退出外层函数（裸 return/throw，非 `return@label`），且位于写点之前；C1/C3 共用同一判据 |
| 07-6 | `return <…>.value` | `value_sources(identity_peel(expr))` 必须派生自所取条目：`current.let { null }` 与 `current.value.let { null }` 均红，`current.value.let { it }` 仍绿 |
| 04-1 | `safeNavigate(builder(...))` 存在 | 点击 lambda 内导航必须在**可达**路径（`reachable`：enclosing 条件全部可行，else 侧按 `!(cond)` 正确取反） |
| 04-2 | `Routes.mirrorCategoryDetail(` 出现 | 路由实参必须派生自回调形参（行 key）：`mirrorCategoryDetail("")` / 常量判红 |
| 04-3 | 不在 `.also`/`.apply` 块里 | `resolver_value_kept()`：沿作用域调用链逐层施值规则，`.takeIf { false }` / `.let { null }` / 任意丢弃型尾巴 → 解析值不存活判红 |
| 04-4 | 谓词含 `== key` | `predicate_fault()`：`\|\|` 臂恒真 / `&&` 臂不可满足 / 整体折叠恒真 → 谓词不可分辨判红 |
| 04-5 | DetailScreen 调用 + `val category` 存在 | 写入可达性：`reachable()` 判定渲染调用不在死条件里，`category == null \|\| true` 的 else 侧不可达 → 红 |
| 04-6 | 声明允许 val/var | 解析后 `last_write` 必须仍是解析结果：`var` + 事后 `category = null` 判红 |

两门自测臂：`browser_destroy_wakeup_check.py` **22 → 63 臂**；
`mirror_category_route_check.py` **→ 16 臂**。每个新臂都断言「**恰好**这条红线 + 命中本条文案」，
clean 臂守既有合法形态（`?.let { it.complete(…) }`、`current.value.let { it }`、
`isReleased` 正向守卫、`replace` 经 `.trim()` 传递）不被误伤。

## 4. 四层证据

**L1 门自测 + 真树**

    BROWSER_DESTROY_WAKEUP_SELFTEST=PASS (63 arms)
    Summary: 37 passed, 0 failed   → GATE=GREEN browser_destroy_wakeup
    MIRROR_CATEGORY_ROUTE_SELFTEST=PASS (16 arms)
    MIRROR_CATEGORY_ROUTE_CHECK=PASS

**L2 反攻（硬验收）**：`verify_batch5.py --only` 逐门重放 §3 补丁
（`probe/t08-post-browser.json` / `probe/t08-post-mirror.json`）：

    04-1..04-6 → CAUGHT restored=True rc=1   （6/6）
    07-1..07-6 → CAUGHT restored=True rc=1   （6/6）

每条都红在**它自己那条判据**上（逐条核对红线文案，非任意 rc≠0）；
`git status --porcelain src/` 空（无变异残留）。

**L3 回归**：邻门 5/5 `rc=0`（`browser_cookie_ui_refresh_check`、
`browser_tabs_lifecycle_check`、`claim_release_guard`、`deeplink_settings_routes_check`、
`mirror_vm_thread_check`）；逐门独立进程重放 `scan.sh` 全部 110 个门调用 + i18n =
**111 门 0 红**（395s）；`test_scan.py` 46 个测试函数逐函数（小批）全绿，其中
`test_da1019_gates`（含本门 selftest/真树断言）与 `test_da1021_gates` 定向先行通过、
`test_real_repo` 单独以 600s 守卫通过。

**L4 CI**：见 §5。

沙箱处置（已在 `sandbox-limits.md` 登记族的复用）：整跑 `scan.sh` 与整跑
`test_scan.py` 在本窗口会被 ENOSYS / stall-guard / native heap 512MB 守卫打死；
健康路径 = **逐门独立进程循环** + **test_scan 逐函数小批驱动**（每函数独立进程、
90s SIGALRM，`test_real_repo` 需 600s）。全量读数由 CI 承担。

## 5. CI

（CI 出结论后回填真实 run / job / head_sha 与日志读数。）

## 6. 残留（P2 登记，每条带三件）

- **R1** `take_wakes_waiter` 只在同一表达式窗口（`expr_end`）内找 `.complete(`。
  触发条件：后续把唤醒改写成两语句（`val d = asyncJsSlot.takeCurrent(); d?.complete(…)`）而门误红。
  触发后方案：把判据扩成「take 的返回值被 complete」需跨语句值流（value_sources + 声明绑定）。
  搁置理由：现仓 destroy/onRendererGone 两处均为单表达式链，且误判方向是 fail-closed（不静默放行）。
- **R2** `latch_guard` 要求守卫 then 分支真退出外层函数。
  触发条件：出现「先置标志位、再继续」的合法闩锁形态而被误红。
  触发后方案：把「标志位 + 后续早退」识别为等价守卫。
  搁置理由：本仓销毁闩锁全部是 `if (destroyed) { log; return }` 形态；fail-closed 方向安全。
- **R3** `deferred_body_guarded` 只看块首第一条语句。
  触发条件：守卫被合法地下移到第二条语句。
  触发后方案：改为「块首到第一个会执行副作用的语句之间出现守卫」。
  搁置理由：延迟截图块只有一行守卫，无合法下移场景。
- **R4** `iter_functions` 只建模花括号体函数；表达式体 `fun f() = …` 不建模。
  触发条件：C1/C3 目标函数被改成表达式体（门会因找不到函数体 fail-closed 红）。
  触发后方案：用 `function_body(kind="expr")` 支持表达式体后重接。
  搁置理由：现仓两个函数均为块体；方向 fail-closed。
- **R5** 共享 `_kotlin_valueflow.value_sources()` 对 `if/else` 不分叉（B5-t07 R1 同族）。
  触发条件：判据需要「then/else 两侧分别求值」时（本波门内已用 if_blocks + const_bool 绕过）。
  触发后方案：共享层补 if/else 分叉；本支按硬约束 #2 未改共享层，交主会话。
  搁置理由：绕过后不影响本门判据正确性。
- **R6** `identity_peel` 只剥 `let/run { it/this }`。
  触发条件：return 表达式里出现 `.also { }` 等其它身份包装（`value_sources` 已剥 also，暂不触发）。
  触发后方案：直接复用共享 `peel_discarding` 统一处理。
  搁置理由：当前无该形态，且误判方向靠 `.value` 子串兜底。
- **R7（漏判方向，需注意）** `mirror` 门 `reachable()` 只建模 **enclosing if/when**，
  **不建模前置早退**（`if (false) return` 之后紧跟的 `safeNavigate` 仍被判可达）。
  触发条件：出现「死守卫 + 早退」组合把导航短路（本波见证是包裹式 `if (false)` 调用，已覆盖）。
  触发后方案：`reachable()` 叠加语句级早退分析（`stmt_spans` 顺序 + 恒真条件 + returns_exiting）。
  搁置理由：现仓点击 lambda 无早退形态；此条是漏判方向，已在下方 R8 一并交主会话评估优先级。
- **R8（漏判方向）** `predicate_fault` 只折叠常量可判的恒真/不可满足；
  `it.key.length > -1` 这类「非法值域恒真」不识别。
  触发条件：出现非常量折叠的恒真谓词。
  触发后方案：接入 `int_domains` 类区间求交（B6 门族已有先例）。
  搁置理由：本波见证是 `|| true` 字面量型；扩面收益低于装置成本。
- **R9** `resolver_value_kept` 沿作用域链最多剥 8 跳。
  触发条件：解析链嵌套 >8 层。
  触发后方案：改为「穷尽链直到无 scope call」。
  搁置理由：真树链长 1–2 跳；超深链会 fail-closed 红，方向安全。
- **R10** `block_inner` 只切 lambda 首个参数头；嵌套 lambda 内写入按顶层语句建模。
  触发条件：解析写入被搬进嵌套 lambda（`onSuccess { … }` 内）。
  触发后方案：递归切 lambda 头后再取语句区间。
  搁置理由：现仓写入都在 screen 组合层，不在回调内。

## 7. `test_scan.py` 注册项（交主会话）

两门**均已在册**，本支无需新增注册：

- `("browser_destroy_wakeup_check", "BROWSER_DESTROY_WAKEUP_SELFTEST=PASS", (), (REPO_ROOT,))`（L2765）
- `("mirror_category_route_check", "MIRROR_CATEGORY_ROUTE_SELFTEST=PASS", (), (REPO_ROOT,))`（L2807）

本支输出保留标记前缀、把臂数写进后缀（`…=PASS (63 arms)` / `…=PASS (16 arms)`），
现有子串断言仍匹配（`test_da1019_gates` / `test_da1021_gates` 实测通过）。
可选加固（非必需）：把臂数也纳入断言（`"…SELFTEST=PASS (63 arms)"`），可防「臂被删空仍绿」；
本支不改冻结文件。

## 8. 复现命令

    cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t08
    python3 scripts/scan/browser_destroy_wakeup_check.py --selftest
    python3 scripts/scan/browser_destroy_wakeup_check.py .
    python3 scripts/scan/mirror_category_route_check.py --selftest
    python3 scripts/scan/mirror_category_route_check.py .
    python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
      --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-A.json \
      --repo . --mode full --only browser_destroy_wakeup_check.py
    python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
      --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-B.json \
      --repo . --mode full --only mirror_category_route_check.py
    # 全量（本窗口靠逐门/逐函数健康路径，全量读数以 CI 为准）
    python3 /tmp/loop_gates_b7t08.py        # 逐门 110 + i18n
    python3 /tmp/run_testscan_b7t08.py 0 30 # test_scan 逐函数（小批）
