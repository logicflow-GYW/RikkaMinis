# FIX-R3-t03 — 第三轮（dual-gate bypass）收口 · 浏览器 tab 三件套

分支 `fix/r3-browser-tabs`（基线 `main` @ `f0f14d46d`）· 只改 3 个门脚本 + 本报告 ·
生产 `.kt` 0 行 · `scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰 ·
门 112 / 夹具 344 不变。

---

## ① 门与轴

| 门 | 见证 | 类型 | 失效轴 | 新增判据 |
|---|---|---|---|---|
| `browser_tab_exec_serial_check.py` | #76 | dual-gate | spelling → 值流 | 交下来的 locked tab id 必须被 `acquireTab(…)` 看见（W76） |
| 同上 | #77 | dual-gate | 存在 → 位置/常量折叠 | `implicitTab` 常量必须等于「该站点是否真在 `withLock` 内」（W77） |
| 同上 | #78 | symmetric | 名字存在 → 效果 | `lockForTab(id)` 交回的必须是按 id **共享并写回**的那个 Mutex（W78） |
| `browser_tabs_lifecycle_check.py` | #79 | dual-gate | 调用存在 → 回调效果 | `withPath` 必须把重映射后的路径写回 `pathOf` 读的同一字段（C6b） |
| 同上 | #80 | dual-gate | 调用存在 → 可达性 | `loadSavedState` 必须**可达地**派发恢复入口（C6c） |
| 同上 | #81 | symmetric | 谓词存在 → 布尔极性 | 孤儿删除必须在「stem 不在活集合」时发生（C3b） |
| `browser_cookie_ui_refresh_check.py` | #82 | dual-gate | 单一来源 → 注入点派生 | 建 tab 的 `setUserAgent` 必须由池级 profile 派生、自定义 UA 实参不得为常量（W82） |
| 同上 | #83 | dual-gate | 调用存在 → 循环体效果 | 重放必须在循环内**可达地** apply + reload 每个 tab（W83） |
| 同上 | #84 | symmetric | 调用存在 → 字面量语义 | 单域删除的 cookie 串必须自带过期指令（W84） |

九条见证的共同失效形态：**token 全在、决定不在**。前两轮已把「某函数被调用」收紧到
「调用落在受保护区间 / 分支必须终结」，这一轮的三条缝都住在**两门之间的交界面**——
锁的持有声明（W77）、锁与被锁对象的身份（W76）、锁实例的身份（W78）、重映射回调的
写回（W79）、恢复派发的可达性（W80）、布尔极性（W81）、注入点的值来源（W82）、
循环体效果（W83）、字面量的过期语义（W84）。

---

## ② 见证原文（file / find / replace / why_gate_green）

逐条最小编辑已落成 `probe/t03-w<k>.patch`（`git diff` 原文），下面的 `find → replace`
与补丁内容逐字一致。**修前 9/9 全部 BLIND_CONFIRMED**（`probe/t03-pre.json`，rc 全 0，
还原后 sha256 与原始一致、`git status --porcelain` 干净）。

| # | 文件:行 | find → replace | 为什么旧判据全绿 |
|---|---|---|---|
| 76 | `BrowserTabPool.kt:968` | `acquireTab(acquireTabId ?: input.tabId)` → `acquireTab(input.tabId)` | 旧判据只看「锁块包住 `execute(`」「锁的接收者是 `lockForTab()`」；`acquireTab` 的**实参**从没被判过 |
| 77 | `BrowserTabPool.kt:898` | `runAcquiredAction(input, implicitTab = true)` → `implicitTab = false` | 旧判据把 `implicitTab` 当作 `runAcquiredAction` 内部实现；**调用点的自我声明**不在判据内 |
| 78 | `BrowserTabPool.kt:200` | `lockForTab(id) = tabLocks.getOrPut(id) { Mutex() }` → `= Mutex()` | 旧判据只认「`.withLock` 的接收者叫 `lockForTab`」，从不看 `lockForTab` 返回什么 |
| 79 | `BrowserTabPool.kt:647` | `withPath = { entry, path -> entry.copy(destination = path) }` → `{ entry, _ -> entry }` | C6 只数 `remapDownloadDestinations(` 与 `_downloads.update`，回调体是黑箱 |
| 80 | `BrowserTabPool.kt:2062` | `if (savedURLs.isNotEmpty())` → `if (false && savedURLs.isNotEmpty())` | C4f 判 `restoreSavedTabs` 内部的处置闩、C6 判「`loadSavedState()` 是 setSession 最后一步」，**中间那一步无人管** |
| 81 | `SessionFileStore.kt:473` | `stem !in liveSessionIds` → `stem in liveSessionIds` | C3 只问「函数体里有没有 `looksLikeSessionId(`」，布尔方向无人判 |
| 82 | `BrowserTabPool.kt:1259` | `manager.setUserAgent(userAgentProfile, customUserAgentString)` → `(UserAgentProfile.MOBILE_CHROME, null)` | V8 判「自定义 UA 有单一来源」，**建 tab 时的注入点**不判 |
| 83 | `BrowserTabPool.kt:1890` | 循环体内的 `applyViewport` + `reloadAndWait` 删空（循环保留） | V7 只判五个入口「调用」了 `applyViewportToAllTabs()` |
| 84 | `BrowserSettingsSheet.kt:481` | `"$it=; Max-Age=0; Path=/"` → `"$it=1; Path=/"` | F4 只判 `setCookie(` 出现 + 周边 flush/tick，**字面量里的过期语义**不判 |

修后反攻（`probe/t03-post.json`，9/9 CAUGHT，红在自己那条判据上，还原 sha256 一致）：

| # | 主责门 rc | 伙伴门 rc | 红在哪条判据 |
|---|---|---|---|
| 76 | exec_serial 1 | lifecycle 0 | `acquireTab(…)` ignores `acquireTabId` … (W76) |
| 77 | exec_serial 1 | cookie 0 | `passes implicitTab = false while holding NO withLock` (W77) |
| 78 | exec_serial 1 | — | `lockForTab` never touches the per-tab mutex map `tabLocks` (W78) |
| 79 | lifecycle 1 | downloads_atomic 0 | `withPath` 回调没把 `_` 写进 `destination` (C6b/W79) |
| 80 | lifecycle 1 | cookie 0 | 每个 `restoreSavedTabs(` 都在静态死路上 (C6c/W80) |
| 81 | lifecycle 1 | — | 删除发生在 `liveSessionIds` **正向**成员测试下 (C3b/W81) |
| 82 | cookie 1 | lifecycle 0 | `setUserAgent(UserAgentProfile.MOBILE_CHROME, …)` 不派生自池级 profile (W82) |
| 83 | cookie 1 | exec_serial 0 | 循环里从不 `applyViewport(` (W83) |
| 84 | cookie 1 | — | `setCookie(url, "$it=1; Path=/")` 无过期指令 (W84) |

**伙伴门为何不红（逐条归属判定）**：这九条缝都住在「主责门的判据面 × 另一门的判据面」
之间，而另一门的判据面**不覆盖这条缝的决定点**，把它算进去等于给该门加一条不属于它的
义务：

- #76 / #77 / #83 的伙伴门是 `browser_tabs_lifecycle_check` / `browser_cookie_ui_refresh_check`：
  它们判的是 tab 生命周期（创建/销毁/落盘/恢复）与 cookie/viewport 的**用户可见结果**，
  「锁锁住了谁」是 exec_serial 门独有的判据面（`lockForTab`、`withLock`、`execute(` 只在它的判据里）。
- #79 的伙伴门 `downloads_atomic_update_check`：它的 R3 判的是「`_downloads.update {}` 是
  否原子替换 + 快照是否过期」——回调内容属于**重映射语义**，归 lifecycle C6 面。
  本支不许改它（硬约束 #2），已登记为 §6 R6 交主会话评估是否需要在其侧补一条。
- #80 / #82 的伙伴门 `browser_cookie_ui_refresh_check`：V9 判「视口重放的五个入口是否都
  到达」、V8 判「自定义 UA 的存储单一来源」——被证伪的是 `loadSavedState` 的派发可达性
  与**建 tab 时的 UA 注入**，两处都不在 V7/V8/V9 的判据面上。
- 结论：9 条中 4 条（78/81/84 + 76/77 之一）本就单门；其余 5 条的伙伴门均属「不该管」，
  上面逐条给了理由。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### `browser_tab_exec_serial_check.py`（+332 / −? 行）

1. **W76 `_acquire_identity()`**：旧=不判；新=`function_body` 取 `runAcquiredAction` 体 →
   每处 `acquireTab(…)` 用 `call_args` 取实参 → `value_sources` 追来源 → 必须有一条来源
   含 `acquireTabId`。形参不存在 ⇒ 报红（re-pin 消息）；一处都没有 `acquireTab(` ⇒ 报红。
2. **W77 `_implicit_flag_sites()`**：新判据 = 「调用点是否落在某个 `withLock { }` 块区间内」
   ×「`implicitTab` 的常量折叠值」双向判：
   - 锁内 + `true` ⇒ 红（Kotlin `Mutex` 非重入 → 自死锁）；
   - 锁外 + `false` ⇒ 红（调用方没持锁，被调方跳锁 → `tab.manager.execute(` 并发）；
   - 非常量 ⇒ 红（契约必须可从代码判定，fail-closed）；
   - 锁内调用还必须把 locked tab id 交下去（`acquireTabId = …` 且非常量），否则「锁 A 执行 B」。
   块区间的取法修正了一处真实缺陷：`.withLock` 的正则也会匹配 `import kotlinx.coroutines.sync.withLock`，
   其后第一个 `{` 是类体 → 得到覆盖整个文件的假锁区间（W77 夹具有过这个假绿）。
   新实现只认 `.withLock\s*(\{|\()…{`，并用 `match_bracket` 取块界。
3. **W78 `_lock_factory_identity()`**：新判据从树派生 `Map<…, Mutex>` 容器字段
   （`ConcurrentHashMap<Int, Mutex>()` / `MutableMap<…> = mutableMapOf()` 同一决定点）→
   `function_body(code, 'lockForTab')` 取体 → 必须引用容器 + 用**形参**（从签名派生，不写死
   `id`）做键访问 + 必须原子 memo（`getOrPut`/`computeIfAbsent`/`putIfAbsent`）或显式写回
   （`field[key] =`）→ 最后对**返回值表达式**做「裸构造」判定（`Mutex()` → 红）。

### `browser_tabs_lifecycle_check.py`（+404 / −? 行）

4. **W79 `_remap_callback_effect()` / `_remap_callback_effect_in()`**：旧=只判调用存在；
   新=先找 `remapDownloadDestinations(` 的**调用点** → 用 `slice_body` 派生覆盖它的函数体
   （锚「供出回调的那个函数」而不是写死 `setSession`，把重映射抽成 helper 是正当重构）→
   `_lambda_parts` 解析 `withPath = { a, b -> … }` 的形参与体 → 字段名从 `pathOf` 的读点派生 →
   结果表达式（`_lambda_tail`）必须既读第二个形参、又把字段写回（`field = <含形参>`）。
5. **W80 `_restore_dispatch_effect()`**：新=恢复入口名**派生**（既读 `savedURLs` 又调
   `createTab(` 的那个成员）→ `loadSavedState` 体内对它的每处调用都要 `_pos_dead` 判可达 →
   一处都没有 ⇒ 红（「解析了 URL、重放了视口，一个 tab 都不重建」）。
6. **W81 `_orphan_delete_gate()`**：新=活集合形参名从签名派生（`Set<…>` 形参）→ 每个
   `file.delete()` 用 `enclosing_conditions` 取包住它的 if 条件 → 逐合取项归类
   （`!in live` / `!live.contains(` / `in live` / `live.contains(`，剥一层 `!` 认 else 分支）→
   出现正向成员测试 ⇒ 红（极性翻转）；完全没有极性判定 ⇒ 再看前面有没有
   `if (… in live) return/continue` 早退守卫（等价写法），都没有 ⇒ 红；函数体一处
   `delete()` 都没有 ⇒ 红。

### `browser_cookie_ui_refresh_check.py`（+472 / −? 行）

7. **W82 `check_tab_ua_injection()`**：新=派生每个 `BrowserUseManager(` 构造点 →
   `_member_body` 定位所在成员 → 构造绑定的变量名 + **构造时用的 profile 表达式** →
   该变量随后的 `setUserAgent(` 必须：可达（`_live_hits`，死路 ⇒ 红）、第一实参的
   **值来源**派生自同一个 profile 表达式（`_derives_from` 跟一跳局部别名，
   写死枚举成员/字面量 ⇒ 红）、第二实参不得是常量（`null`/`""` ⇒ 红）。
   完全没有 `setUserAgent` ⇒ 红。
8. **W83 `check_replay_effect()` / `_replay_effect()`**：新=`_pool_members` 取
   `applyViewportToAllTabs` 的体 → 必须存在 `for (x in …) {` 循环（无循环 ⇒ 红）→
   循环体内**可达地**在循环变量（或绑定到它的局部名）上有 `applyViewport(` 与
   `reload…(`（各自独立判，缺一即红）。
9. **W84 `_expiry_instruction()` / `_cookie_value_arg()`**：新=单域动作块内每处
   `setCookie(` 的第二个实参必须在**字面量层**自带过期指令（`Max-Age ≤ 0` 或过去的
   `Expires`），否则红。实参切分用引号感知的顶层逗号（`call_args` 会把
   `Expires=Thu, 01 Jan 1970` 从逗号切断 ⇒ 合法写法被误判，夹具臂当场抓到）。

---

## ④ 四层证据

### L1 本门：真树 + 自证全臂

| 门 | 真树 | `--selftest` | 臂数变化 | 收尾标记 |
|---|---|---|---|---|
| `browser_tab_exec_serial_check` | rc=0 | rc=0 | 15 → **20**（+W76 +W77 +W78 +W78s +store-memo clean） | `BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS (20 arms …)` |
| `browser_tabs_lifecycle_check` | rc=0 | rc=0（76 臂 OK） | 68 → **76**（+W79 ×3 +clean、+W80 ×2 +clean、+W81 ×2 +clean） | `BROWSER_TABS_SELFTEST=PASS` |
| `browser_cookie_ui_refresh_check` | rc=0 | rc=0 | 52 → **68**（+W82 ×6 +clean、+W83 ×5 +clean、+W84 ×2 +clean） | `BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS (68 arms)` |

带牙要点（新增臂都断言「摔在哪」）：脏臂断言**具体判据子串**（`ignores \`acquireTabId\``、
`never touches the per-tab mutex map`、`never memoizes the instance it returns`、
`without writing its own`、`statically-dead path`、`polarity is inverted`、
`does not derive from the pool's own profile`、`never calls \`tab.….applyViewport(\``、
`carries no expiry instruction`），撞错判据 = 假臂 = FAIL。

clean 臂（防「只认一种拼写」）：
- `if (implicitTab == true)`（等价条件拼写）
- 显式 store 形态的 memo 化（`val existing = tabLocks[id] … tabLocks[id] = created`）
- `withPath` 参数改名 + 多行 `copy(`（`dest` 而非 `path`）
- `if (savedURLs.isNotEmpty() && !isDisposed)`（多一个活条件）
- 孤儿删除的早退守卫写法（`if (… in live) return@forEach`）
- `!liveSessionIds.contains(stem)`（同一决定的另一种拼写）
- `val profileForTab = userAgentProfile` 一跳别名
- `val manager = tab.manager` 一跳别名（循环体的等价写法）

### L2 见证反攻（本节最硬的验收判据）

`probe/t03-pre.json` 9/9 `BLIND_CONFIRMED`（跑的是**基线门**：先把三个门 `git checkout --`
回 `f0f14d46d` 版本，跑完再把修好的版本拷回）→ `probe/t03-post.json` 9/9 `CAUGHT`，
逐条 `rc=1` 且红在**自己那条判据**上（`attribution` 字段存了原文），还原 9/9 sha256 一致、
`git status --porcelain` 全程为空。补丁原文：`probe/t03-w76..84.patch`。

### L3 邻门

| 门 | rc | 备注 |
|---|---|---|
| `browser_tab_exec_serial_check` | 0 | 本支 |
| `browser_tabs_lifecycle_check` | 0 | 本支 |
| `browser_cookie_ui_refresh_check` | 0 | 本支 |
| `downloads_atomic_update_check` | 0 | 同扫 `BrowserTabPool.kt`（W79 的伙伴门） |
| `browser_destroy_wakeup_check` | 0 | 同族（tab 销毁/唤醒） |
| `webview_holder_url_source_check` | 0 | 同族（WebView holder） |

### L4 全量

`python3 probe/t03_gate_loop.py`（逐门独立进程，109 门 + `i18n_check` /
`process_logging_gate` / `turn_tail_finalize_order` = 112）在本沙箱跑到中途被 shell
回收（exit −1，已知 S28 症状，非门红），故 **112/0 与 `ALL 344` 的读数由分支 CI 承担**
（见 §⑤）。本支已本地确认的三处：本支 3 门 + 3 个邻门 rc=0。

---

## ⑤ CI（分支 `fix/r3-browser-tabs`，门提交 `56e6c6429`）

`workflow_dispatch`（分支 push 不触发）→ run **`40308684655`** / **#2727** / job
`121524100328` / attempt 1 **success**（10:36:11Z → 10:56:15Z，20m04s），
`head_sha = 56e6c642985a3be1c3a1a2ac58116402212ec459` 与本支门提交**逐字符一致**。

| 核查项 | 读数 | 判定 |
|---|---|---|
| 步骤 | 26 步 + 3 个 Post，**唯一 `skipped` = step 23 `Publish to Releases`** | ✅ main 门控生效 |
| 日志 | 1,486,753 B / 裸 NUL 0 | ✅ 可机械检索 |
| 门总账 | `Summary: 112 passed, 0 failed` **恰 1 条**（其余 Summary 是子套件：10/15/16/25×2/37） | ✅ |
| 夹具 | `ALL 344 CASES PASS` **恰 1 条** | ✅ |
| 编译 | `^ e: ` **0 条** | ✅ |
| 门头 | `[N/112]` 门头 **112 个互异** | ✅ |
| `FAILED` 行 | 6 条，**全部**是 `PASSED` 用例名的子串（不含 PASSED 的 = 0） | ✅ 判读陷阱复核 |
| 本支三门在册 | `✅ browser_tab_exec_serial_check selftest has teeth (SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures))` + `real tree green`；`✅ browser_cookie_ui_refresh_check … (BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS)` + real tree green；`✅ browser_tabs_lifecycle_check … (BROWSER_TABS_SELFTEST=PASS)` + real tree green | ✅ 各恰 1 对 |
| **新判据真跑**（本轮独有证据） | 门真树 OK 行带着新判据标签：cookie 门 `… + tab-UA inheritance + viewport-replay effect + per-domain cookie expiry`；lifecycle 门 `+ C3b the orphan sweep deletes only non-live sessions (polarity) + C6b the remap callback writes the remapped path back + C6c loadSavedState actually dispatches the restore`；exec 门 `PASS browser_tab_exec_serial (…)` | ✅ 不是「只有本地跑过」 |

**已知限制（非本支引入）**：门自己的 `--selftest` stdout 不进 CI 日志 ⇒ 新增臂的**计数**
（20/76/68）只在本地与本报告里可见，CI 侧看到的是 `test_scan.py` 的 marker 断言（已注册
的三条）。这是 §6 R8 记录的既知口径。

---

## ⑥ 残留（P2，均带「触发条件 / 触发后方案 / 搁置理由」三件）

- **R1 `_lock_factory_identity` 只取第一个 `Map<…, Mutex>` 容器**
  - 触发条件：池里出现第二个 Mutex 容器字段（且 `lockForTab` 用另一个）
  - 触发后方案：改为「`lockForTab` 体内实际引用的容器」求交再判
  - 搁置理由：当前全仓仅 `tabLocks` 一个容器；再出现就是重构信号，届时门会以
    「never touches the per-tab mutex map `X`」明确报红，不会静默放行。
- **R2 W77 把「落在任意 `withLock` 块内」当作「持有 tab 互斥量」**
  - 触发条件：出现第二个保护**非 tab** 互斥量的 `withLock` 块，且块内调用
    `runAcquiredAction(…, implicitTab = false, …)`
  - 触发后方案：校验 `withLock` 的接收者是 `lockForTab(…)`（判据已有 `receiver_failure`，搬过来复用）
  - 搁置理由：当前全文件仅两处 `withLock`（都在 tab 锁上）；收紧会把「session 锁内再取
    tab 锁」的合法嵌套误红，装置比例不合适。
- **R3 W81 只覆盖 `if` 条件与 `if` 早退两种极性表达**
  - 触发条件：极性改由 `when` 分支或 `takeIf`/`filter` 链表达
  - 触发后方案：接 `when_branches` + 条件 lambda 的 `ifLike` 判定
  - 搁置理由：现有形态就是 if + 早退两类；换形态=重构，届时门以「not gated on the
    live-set at all」报红（不是静默放行），再按上面方案扩。
- **R4 W79 只认 `remapDownloadDestinations(` 调用点**
  - 触发条件：下载重映射换 API（C6 本身允许 `updateDownloadEntry(` / `_downloads.update`）
  - 触发后方案：在 `_orphan`… 即 `_remap_callback_effect` 里加新 API 的回调位置解析
  - 搁置理由：刻意 fail-closed（报 `ROT: re-pin it`），不是漏判——换 API 必须有人重新
    指认「哪个表达式是把新路径写回记录」。
- **R5 W84 只读字面量**
  - 触发条件：过期指令拼进变量（`val exp = if (…) "Max-Age=0" else ""`）
  - 触发后方案：`value_sources` 追一跳到字符串字面量再判
  - 搁置理由：当前字面量直接内联；拼进变量属于可读性下降的重构，届时红的是
    「carries no expiry instruction」（安全方向）。
- **R6 W82 只判 `setUserAgent` 的前两个实参**
  - 触发条件：`setUserAgent` 签名扩展（如第三参 accept-language）
  - 触发后方案：按形参名派生而不是按位置
  - 搁置理由：当前签名两参；`BrowserUseManager.setUserAgent` 在 `browser` 包内是
    单一实现，扩展时会牵动 `provider_ua_parity` 门，届时一并处理。
- **R7 W83 未验证循环集合就是「活标签列表」**
  - 触发条件：`applyViewportToAllTabs` 的 `for (tab in X)` 里 X 被换成空集合/常量
  - 触发后方案：从池的 `_tabs` 状态流字段派生集合表达式（`X` 必须引用它）
  - 搁置理由：换集合名=重构信号，且它不影响本轮被证伪的决定（「循环体内有没有效果」）；
    真要防「循环空转」，正解是判集合表达式来源，属下一轮口径。
- **R8 门自证 stdout 不进 CI 日志（既知，非本支引入）**
  - 触发条件：主会话需要从 CI 日志核对新臂数量
  - 触发后方案：由 `test_scan.py` 注册（见 §⑦），或给 `test_scan` 加 marker 断言
  - 搁置理由：`test_scan.py` 冻结面，本支不许改。

---

## ⑦ `test_scan.py` 注册项（交主会话并集收口）

`test_scan.py` 属冻结面，本支未改。建议注册三条（沿用既有 `SELFTEST=.PASS` 风格，
断言 marker 子串而非臂数，避免以后加减臂破断言）：

```python
("browser_tab_exec_serial_check", "BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS", (REPO_ROOT,)),
("browser_tabs_lifecycle_check", "BROWSER_TABS_SELFTEST=PASS", (REPO_ROOT,)),
("browser_cookie_ui_refresh_check", "BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS", (REPO_ROOT,)),
```

其中前两条的 `SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures)` 与
`BROWSER_TABS_SELFTEST=PASS` 已在册（本次保留原句不动，新臂计数写在**另一行**
`BROWSER_TAB_EXEC_SERIAL_SELFTEST=PASS (20 arms …)` 上），因此实际只需补
`browser_cookie_ui_refresh_check` 那条（V-系列标记已在册，本轮新增的 `[tab-ua]` /
`[replay-effect]` / W84 臂没有独立 marker）。

---

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t03

# L1 本门
for g in browser_tab_exec_serial_check browser_tabs_lifecycle_check browser_cookie_ui_refresh_check; do
  python3 scripts/scan/$g.py --selftest >/dev/null || echo "SELFTEST FAIL $g"
  python3 scripts/scan/$g.py . >/dev/null || echo "TREE FAIL $g"
done

# L2 见证反攻（修后：9/9 必须 CAUGHT 且 attribution 非空）
python3 probe/t03_replay.py post
# 修前基线复证（需要把三个门回退到 f0f14d46d 版本再跑）
git checkout -- scripts/scan/browser_tab_exec_serial_check.py \
                scripts/scan/browser_tabs_lifecycle_check.py \
                scripts/scan/browser_cookie_ui_refresh_check.py
python3 probe/t03_replay.py pre
cp probe/fixed-gates/*.py scripts/scan/          # 恢复本支改动

# L3 邻门
for g in downloads_atomic_update_check browser_destroy_wakeup_check webview_holder_url_source_check; do
  python3 scripts/scan/$g.py . >/dev/null || echo "NEIGHBOR RED $g"
done

# L4 全量（沙箱易被回收；读数以 CI 为准）
python3 probe/t03_gate_loop.py
```
