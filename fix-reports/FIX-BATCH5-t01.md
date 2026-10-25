# FIX-BATCH5-t01 — 门见证收口：`browser_tabs_lifecycle_check` + `dao_loadmessages_check`

- 批次：第五批门见证收口（batch5），支线 t01
- 分支：`fix/b5-tabs-dao`，基线 `e145ec961`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）
- 改动面：仅 `scripts/scan/browser_tabs_lifecycle_check.py`、`scripts/scan/dao_loadmessages_check.py`（+ 本报告）
- 生产 `.kt` 改动：**0 行**；`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 零触碰
- 结论：12 条见证全部加固，反攻 12/12 变红（原 2 条 CAUGHT 仍 CAUGHT），restore 12/12 True

## ① 门与轴

| 门 | 见证条数 | 轴分布（修前判决） | 修后 |
|---|---|---|---|
| `browser_tabs_lifecycle_check.py` | 6（G01-A1..A6） | family-hole ×4（A1/A3/A5）+ dataflow ×1（A2）+ parser-death ×1（A4）；其中 A6 原已 CAUGHT | 6/6 CAUGHT |
| `dao_loadmessages_check.py` | 6（G04-A1..A6） | parser-death ×2（A1/A4）+ spelling ×2（A2/A3）+ family-hole ×1（A5）+ dataflow ×1（A6，原已 CAUGHT） | 6/6 CAUGHT |

合计：**10 条 BLIND_CONFIRMED 被证实失明 + 2 条 CAUGHT 对照臂**；轴 = family-hole 5 · parser-death 3 · spelling 2 · dataflow 2。

两门病因同一族：**判据锚「某拼写/某子串/某标识符出现过」，而不是「该决定点的效果达成」**。`browser_tabs` 侧表现为「死分支只认白名单字面量 + 数据流不追右值来源」，`dao_loadmessages` 侧表现为「调用点用标识符链正则提取 + 接收者按名字白名单放行」。

## ② 见证原文（file / find / replace / 修前为何绿）

### G01-A1（family-hole，结构）— 恒假条件包裹早退守卫
- file: `src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt`
- find: `        if (isDisposed) return\n        val currentTabs = _tabs.value.toMutableList()`
- replace: `        if (!true) { if (isDisposed) return }\n        val currentTabs = _tabs.value.toMutableList()`
- 为何绿：`_is_dead_branch_condition` 只匹配 `\bfalse\b` / `0 == 1` / `1 == 0` / 同名变量 `x != x`，不识别 `!true`；且 `_find_live_guard` 只抓紧贴 `isDisposed` 的那一层 `if` 头，外层块包裹后内层守卫被当活守卫。

### G01-A2（dataflow，数据流）— 具名参数自引用退化
- file: 同上
- find: `                stamped.copy(\n                    text = text,`
- replace: `                stamped.copy(\n                    text = stamped.text,`
- 为何绿：`updateTab` 判据只验 `.copy(` 存在 + 未把 `id =`/关键标识覆写为常量，不追踪各具名参数右值的来源；`stamped.text` 是合法标识符但语义上是旧值回灌。

### G01-A3（family-hole，语义）— 布尔吸收元后缀投毒
- file: 同上
- find: `    private fun publishAgentBusy() {\n        _agentBusy.value = _tabs.value.any { it.inUse }\n    }`
- replace: `    private fun publishAgentBusy() {\n        _agentBusy.value = _tabs.value.any { it.inUse } && !true\n    }`
- 为何绿：只正则 `&&\s*false` / `\|\|\s*true` 与 `false` 字面量，未归一化 `!true` 这类一元布尔常量。

### G01-A4（parser-death，结构）— 局部作用域吞没早退
- file: 同上
- find: `        if (isContextOnlySession) return\n        val sid = sessionId ?: return`
- replace: `        run { if (isContextOnlySession) return@run }\n        val sid = sessionId ?: return`
- 为何绿：`_return_exits_function(fn_body, match_pos)` 收了 `match_pos` 却在**整段函数体**上做 `\breturn\b(?!@…)` 搜索，函数末尾本就有普通 `return` ⇒ 任何位置都能找到"退出"证据。

### G01-A5（family-hole，结构）— 恒假条件包裹清理调用
- file: 同上
- find: `        evictionScope.cancel()\n        downloadScope.cancel()`
- replace: `        evictionScope.cancel()\n        if (false) downloadScope.cancel()`
- 为何绿：C4e 只对 `dispose()` 体做 `downloadScope.cancel()` 子串存在性检查，不像 C4f 那样过 `_find_live_guard`/死分支判定 ⇒ 同一门内两臂能力不一致。

### G01-A6（family-hole，结构）— 恒假合取短路外层守卫（原已 CAUGHT）
- replace: `        if (false && !isDisposed) { if (isContextOnlySession) return }`
- CAUGHT 原文：`FAIL: browser_tabs_lifecycle_check\n  - C11: isContextOnlySession guard missing or dead in ensureInitialized`

### G04-A1（parser-death，数据流）— 括号包裹接收者
- file: `src/android/app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt`
- find: `        val all = repo.loadMessages(sessionId)`
- replace: `        val all = (repo.dao).loadMessages(sessionId)`
- 为何绿：调用点用标识符链 `<ident>.loadMessages\s*\(` 提取接收者，`(repo.dao).loadMessages` 的 `.` 前是 `)` ⇒ 调用点被跳过。

### G04-A2（spelling，数据流）— 伪装 Repository 命名的别名透传
- find: `        val all = repo.loadMessages(sessionId)`
- replace: `        val repoView = repo.dao.let { it }\n        val all = repoView.loadMessages(sessionId)`
- 为何绿：`.let { it }` 打断直接赋值形态，而 `repoView` 命中 `repo*` 命名白名单 ⇒ 被当安全 Repository 放行。

### G04-A3（spelling，数据流）— Lambda 形参伪装 Repository 名
- replace: `        val all = repo.dao.let { repoAlias -> repoAlias.loadMessages(sessionId) }`
- 为何绿：接收者名 `repoAlias` 以 `repo` 开头直接在白名单命中，覆盖外层 `.let` 作用域污染标记。

### G04-A4（parser-death，结构）— 可调用引用 `::` 调用
- replace: `        val all = (repo.dao::loadMessages)(sessionId)`
- 为何绿：全篇入口锚为 `\.loadMessages\s*\(` 或 `\bloadMessages\s*\(`；`::loadMessages)(` 中方法名后是 `)` ⇒ 扫不到任何调用事件。

### G04-A5（family-hole，结构）— 同 DAO 兄弟查询方法
- file: `src/android/app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt`
- replace: `            val page = dao.loadMessagesPage(sessionId, offset, limit)`
- 为何绿：方法名写死 `loadMessages`；同接口同返回类型的 `loadMessagesPage` 不在名录。

### G04-A6（dataflow，数据流）— 单跳局部别名（原已 CAUGHT）
- replace: `        val rawDao = repo.dao\n        val all = rawDao.loadMessages(sessionId)`
- CAUGHT 原文：`FAIL: dao_loadmessages_check (1 violation)\n  …direct or aliased dao.loadMessages call outside ChatRepository: rawDao.loadMessages…`

## ③ 判据改造（旧形态 → 新形态）

### `browser_tabs_lifecycle_check.py`（+602/−…，24 defs）

1. **死分支判定统一到共享常量折叠**（A1/A3/A5 的根）：删除本地手写 `_expr_end`，新增 `_guard_dead(cond)` —— 折叠顺序 = 共享 `guard_feasible`（`_kotlin_valueflow`）→ `_kotlin_ast` 的 `const_bool`（短路语义 + Kotlin 优先级）→ `_kotlin_lexer.mask_code` 取表达式（字符串/注释/模板已掩码）→ 最后兜底 `X != X` 自反同文本。`_dead_spans` / `_pos_dead` / `_stmt_tail` 一律走它，**同门所有臂同一套守卫判定**（旧形态：一臂调一臂不调 ⇒ A5 的 C4e 失明）。
2. **换行/块包裹不再中断守卫提取**：`_live_guard_positions` 递归收集包裹在死分支内的守卫位置，并沿**下一个可执行语句**向上回溯未闭合的 `if (` 头 ⇒ `if (!true) { if (isDisposed) return }` 的外层被识别为死分支，内层守卫不再计为活守卫。旧形态只抓紧邻一层。
3. **早退必须真退出**（A4）：`_structure` 里对每个守卫记录其**所在语句区间**（深度 0 换行切分，共享 `expr_end`），只有 `return` 在该区间内且不属 `return@label` 才算退出；整段函数体上的裸 `return` 不再算证据。旧形态见 ②。
4. **数据流跟右值来源**（A2）：`updateTab` 的 `.copy(...)` 判据改为用 `_result_text_args` 取每个具名参数的实参表达式，若右值是 `receiving.copy` 的前身（`stamped.<field>` / 同名字段回灌）则判红；旧形态只看「有没有 `.copy(`」。
5. **布尔吸收归一到常量折叠**（A3）：`publishAgentBusy` 的 RHS 若折叠为常量（或形如 `expr && constFalse` / `expr || constTrue`，含 `!true` 这类一元形式）判红。
6. **C9 改为行为派生**：`_flows_from` 从 `_tabs`/busy 值的**求值来源**出发（单文件、`depth<2` 局部中转）判定 `BrowserSheet` 是否订阅了响应式 flow 而非 non-reactive getter（新增臂 C9f 守此判据）。
7. 解析失败/无法定位一律 fail-closed（红），不用"找不到就不报"。

### `dao_loadmessages_check.py`（+870/−…，31 defs）

1. **调用点提取改成"表达式接收者 + 链解析"**（A1/A4 的根）：新增 `_receiver_expr`（从 `.` 左侧回溯解括号 `(...)`、解 `::` 可调用引用、解 `?.`/`!!`/`as T`）、`_chain_components`（拆链后逐段归类）、`_skip_ws_forward`。`(repo.dao).loadMessages(x)` 与 `(repo.dao::loadMessages)(x)` 现在都落进同一调用点模型。
2. **接收者类别由数据流决定，不再靠名字白名单**（A2/A3 的根）：新增 `_kind_of` / `_kind_from_type` / `_name_kind` / `_kind_of_name`，`Env._build` 对每个函数体建**作用域表**（`_extend_scope_span` 处理 `with (repo.dao) { … }` 跨度、`_scope_receiver_in` 处理嵌套 lambda 形参、`binding` 走共享 `value_sources` 把 `let { x -> }` / `.apply {}` / `.also {}` 的形参与实参绑定回真实来源）。`repoView = repo.dao.let { it }`、`let { repoAlias -> … }` 的接收者最终解析到 `ChatDao` ⇒ 判红。名字只在类型/数据流都无法解析时兜底，且兜底**不覆盖**已解析出 dao 的接收者。
3. **原始行方法集合从类型声明派生**（A5）：`_declared_methods` 扫 `*Dao` 类型声明（含 `interface`/`abstract`），`_raw_row_methods` 取返回类型含 `MessageEntity` 的方法集 ⇒ `loadMessagesPage` 这类兄弟方法自动入册。旧形态写死 `loadMessages`。
4. **清洗漏斗改成"调用图传递闭包"**：`_guarded_functions` + `_call_sites` 分类（raw 读 / 清洗调用 / 其它），迭代到不动点（4 轮）：凡「全部调用点都落在已受护函数内」的函数也视为受护 ⇒ 把清洗上提到 wrapper 的正当重构不会被误伤；`ChatRepository` 内的 raw 调用只要经过容错漏斗（`loadPageTolerant` 等）即放行。
5. **声明语句不算调用点**（`_is_declaration` / `_function_bodies` / `_enclosing_fn`），修掉「接口声明本身被判成 raw 调用」与「函数体边界吞掉后续声明」的老坑（表达式体函数 `fun f() = …` 会让朴素花括号扫描越界）。
6. 结构分析全部走共享 `mask_code` / `match_bracket` / `expr_end` / `value_sources`；解析失败 fail-closed。

**两门共同的形态迁移**：自写 `strip_comments` + 裸花括号计数 + 字面量白名单 ⇒ 共享 `_kotlin_lexer.mask_*`（等长三视图）+ `_kotlin_ast`（折叠/配对/语句区间）+ `_kotlin_valueflow`（求值来源/守卫可满足性/活性位置）。这正是本批"共享层 adoption=0"病根的处置。

## ④ 四层证据

**L1 本门**
- `python3 scripts/scan/dao_loadmessages_check.py .` → rc=0（`[dao-loadmessages] clean: 0 direct dao.loadMessages( in production`）
- `python3 scripts/scan/browser_tabs_lifecycle_check.py .` → rc=0
- `--selftest`：`DAO_LOADMESSAGES_SELFTEST=PASS (12 arms)`；`BROWSER_TABS_SELFTEST=PASS` + `BROWSER_TABS_RISK_NOTICE_AND_REMAP_SELFTEST=PASS`（65 条 OK 臂，含新增 C9f、A1/A3/A5/A4 各自回归臂）

**L2 反攻（硬验收）** —— 用现成驱动 `verify_batch5.py --mode full`，逐门跑：
- dao：`probe/t01-final-dao.json` → 6 条全部 `CAUGHT`（`gate_rc=1`），`restored=True` 6/6
- browser：`probe/t01-final-browser.json` → 6 条全部 `CAUGHT`（`gate_rc=1`），`restored=True` 6/6
- 摘要：`Counter({'CAUGHT': 6})` ×2；两条原 CAUGHT 臂（G01-A6 `C11: isContextOnlySession guard missing or dead`、G04-A6 `direct or aliased dao.loadMessages call outside ChatRepository`）仍 CAUGHT
- 跑完 `git status --porcelain src/` 为空（无变异残留）

**L3 邻门 9 道 rc=0**：`browser_history_snapshot_check` · `browser_cookie_ui_refresh_check` · `browser_destroy_wakeup_check` · `provider_ua_parity_check` · `file_uri_parse_guard_check` · `compact_decision_gate_check` · `entry_id_rename_integrity_check` · `four_way_sync_check` · `room_migration_check`
（另：`entry_composite_key_check` 在本树已无此文件，改用上列 9 道覆盖语义相邻面）

**L4 全量**
- `bash scripts/scan/scan.sh` → **112/112 门，RED 0，SCAN_EXIT=0**
- `python3 scripts/scan/test_scan.py` → 沙箱并发窗口下 `subprocess.wait` 抛 `OSError: [Errno 38] Function not implemented`（S28 已知缺陷，非本改动）；按任务书 §5.4 的手册改**逐函数循环**（每函数单独子进程 + checkpoint 断点续跑，工具 `probe/t01_one_testfn.py`、`probe/t01_loop_testscan.sh`）：**46 个 test 函数 / 344 case / 0 fail**（合计与 `ALL 344 CASES PASS` 等量），逐函数记录见 `/tmp/t01-ts-cases.txt`
- 另以逐门运行器 `probe/t01_gate_loop.py` 复核 110 道 `python3 <gate> <repo>` 形态的门（scan.sh 内注册），**110/110 rc=0**

## ⑤ CI

- run **#2692 / id `40212483269`** · job `121226512272` · attempt **1** · conclusion **success**
- `head_sha` = `8562a26821c9c7bd79193b844f61ccb1af33ce19`，与本地 `git rev-parse HEAD` **逐字符一致**
- 26 步全部 completed，唯一 `skipped` = `Publish to Releases`（main 门控），`not_success` 为空
- 日志 1,486,605 B 逐条判读：
  - `Summary: 112 passed, 0 failed` **恰 1 条**；`ALL 344 CASES PASS` **恰 1 条**；` e: ` **0**
  - 门头 `[N/112]` **112 条且编号互异**
  - 含 `FAILED` 的行 6 条，**全是 PASSED 用例名子串**（`…produces FAILED without calling provider PASSED`、`reason PERSISTENCE_FAILED maps…`、`phase FAILED maps to Failed PASSED`、`terminal FAILED maps to Failed PASSED`、`reason EXECUTION_FAILED maps…`、`the fixed tail still reaches a legal FAILED terminal PASSED`）
  - 两门自测标记均在日志出现各 1 次（`DAO_LOADMESSAGES_SELFTEST=PASS` / `BROWSER_TABS_SELFTEST=PASS`）⇒ 自测在 CI 里真跑，不是只在本地
- 报告提交（本文件）晚于 CI 绿树；源码树等价性以 `git diff --name-only 8562a2682 HEAD -- . ':(exclude)fix-reports'` = 0 行为准

## ⑥ 残留（本支不修，交主会话登记）

- **R1 `_guard_dead` 的自反同式判定按去括号后文本相等**：`x != x`、`a.b == a.b` 折叠为恒真/恒假；但带内部空白的等价拼写（`a .b != a.b`）文本不等 ⇒ 判为活分支。
  触发条件：恒假守卫写成同表达式但空白不一致；触发后方案：在 `_kotlin_ast.const_bool` 对操作数做规范化（去空白/去外层括号）后再比对；搁置理由：12 条见证与真树均无此形态，扩规范化会动共享模块（本支禁改）。
- **R2 `browser_tabs` 的决定点是清单式的**：C4e/C4f/C11/C9 分别点名 `dispose` / saveState / `ensureInitialized` / `BrowserSheet`；新增生命周期决定点（如宿主 `onPause` 钩子）默认放行（registry 轴的反向残余）。
  触发条件：`BrowserTabPool` 新增生命周期决定点；触发后方案：改为从 pool-owned scope 派生站点（凡 `evictionScope`/`downloadScope` 的 `launch`/`cancel` 必须被 latch 守卫覆盖）；搁置理由：派生化会 red 掉 4 处合法无守卫站点（驱逐 job 派生等），制造假红并超出本支半径。
- **R3 跨文件漏斗不认识**：`_guarded_functions` 在**单文件**内计算，`dao` 原始行读在 A 文件、清洗入口在 B 文件时会判红。
  触发条件：把清洗入口搬到别的文件；触发后方案：把 `_call_sites` 的不动点扩成全仓级（按 `ChatDao` 参照）；搁置理由：当前真树漏斗全在 `ChatRepository.kt` 内，跨文件是误报方向（fail-closed，不静默放行）。
- **R4 类型/命名兜底的边界**：`_kind_from_type` 认 `*Dao` / `*Repository` 命名，非约定命名的 DAO 类型（如 `interface MessageStore`）解析为 unknown ⇒ 判红。
  触发条件：引入非 `*Dao`/`*Repository` 命名的原始行 DAO；触发后方案：改为从 `*Entity` 返回类型反推 DAO（不依赖类型名）；搁置理由：同样只会误报不会漏报，且真树无此形态。
- **R5 `::` 引用传播未覆盖**：调用点已覆盖 `(dao::loadMessages)(x)`，但把 `dao::loadMessagesPage` 作为值传出到别处再调用，门看不到。
  触发条件：出现把原始行方法的 `::` 引用传出 `ChatRepository` 的代码；触发后方案：对 `raw_row_methods` 的 `::name` 引用在 `ChatRepository` 之外直接判红；搁置理由：真树零出现，加判据需回归全部 12 臂，性价比低于等触发。
- **R6 `_flows_from` / `value_sources` 只解 1 跳（`depth<2`）局部中转**：busy 值经 3+ 层中转会判红（fail-closed 误报方向）。
  触发条件：`BrowserSheet` 取 busy 值的中转层数增加；触发后方案：迭代到不动点（受 `value_sources` 单文件约束）；搁置理由：误报方向，且真树只有 1 层。

## ⑦ `test_scan.py` 注册项（交主会话统一处理；本支未改冻结面）

已存在、无需新增（真树已核对，行号为本支树内位置）：
- `("dao_loadmessages_check", "DAO_LOADMESSAGES_SELFTEST=PASS", ("--root", REPO_ROOT))` — test_scan.py:2733
- `("browser_tabs_lifecycle_check", "BROWSER_TABS_SELFTEST=PASS", (), (REPO_ROOT,))` — test_scan.py:2933

建议补注册（本支新加的第二组自测标记，目前无人见证）：
- `("browser_tabs_lifecycle_check", "BROWSER_TABS_RISK_NOTICE_AND_REMAP_SELFTEST=PASS", (), (REPO_ROOT,))`

臂数：dao **12 arms**（`--selftest` 末行自报）；browser **65 条 OK 臂**（两个 selftest 组，含新增 C9f / 死分支 / 自反式 / 局部跳出 4 类回归臂）。

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t01
# L1
python3 scripts/scan/dao_loadmessages_check.py .            # rc=0
python3 scripts/scan/browser_tabs_lifecycle_check.py .      # rc=0
python3 scripts/scan/dao_loadmessages_check.py --selftest    # DAO_LOADMESSAGES_SELFTEST=PASS (12 arms)
python3 scripts/scan/browser_tabs_lifecycle_check.py --selftest  # 两个 SELFTEST=PASS，65 OK 臂
# L2 反攻（硬验收）
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json --repo . --mode full --only dao_loadmessages_check.py --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t01-final-dao.json
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json --repo . --mode full --only browser_tabs_lifecycle_check.py --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t01-final-browser.json
git status --porcelain src/                                  # 必须为空
# L4 全量
bash scripts/scan/scan.sh                                    # 112/112，SCAN_EXIT=0
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t01_gate_loop.py "$PWD" /tmp/gates-t01.txt   # 逐门兜底 110/110 rc=0
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t01_one_testfn.py "$PWD" <test_fn>            # 逐函数兜底（沙箱 S28）
```
