# FIX-BATCH7-t13 — calendar_events_nullable_predicate / router_lock 两门判据加固

- **批次**：BATCH7（第七批 · 第二轮宽扫）· 支线 `t13`
- **分支**：`fix/b7-calendar-routerlock`（基线 `base/batch5-shared` @ `e145ec961`）
- **门提交**：`8c95fd6ca`（2 文件 +799/−265）
- **改动面**：`scripts/scan/calendar_events_nullable_predicate_check.py`、
  `scripts/scan/router_lock_check.py` —— **生产 `.kt` 0 行**，
  `scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰，
  门总数 112 / 夹具 344 不变。
- **结论**：12 条见证 → 修复后 **12/12 CAUGHT（rc=1 exact）、restore 12/12**，
  每条红线落在自己那条判据上（`probe/t13-attribution.json`）。

---

## 1. 输入与复证基线

外部攻击方（`witnesses-B.json`）对本支两门给出 12 条「能过门但重新引入缺陷」
最小补丁，声明基线为 `1319cce7`。本支在 `base/batch5-shared`（= main `6ac77c6b2`
+ 第四/五批共享原语）上施工，**同一批见证在新树上重跑复证**：

| 门 | 见证数 | 修前判决（门提交回退到基线后逐条重放） | 修后 |
|---|---|---|---|
| `calendar_events_nullable_predicate_check.py` | 6（05-1…05-6） | **6/6 BLIND_CONFIRMED**（rc=0, exact） | 6/6 CAUGHT |
| `router_lock_check.py` | 6（03-1…03-6） | **6/6 BLIND_CONFIRMED**（rc=0, exact） | 6/6 CAUGHT |

复证产物：`probe/t13-pre-calendar.json` / `probe/t13-pre-router.json`（修前，把门文件换回
`e145ec961` 版本后重放）、`probe/t13-{calendar_events_nullable_predicate,router_lock}_check.json`
（修后）、`probe/t13-attribution.json`（逐条归属）。修前 12/12 全 BLIND —— 攻击方的
每条补丁在基线上确实让门返回 rc=0。

---

## 2. 12 条见证与判决

### 2.1 calendar（rule A–F）

| # | 见证（最小补丁要点） | 旧判据为何瞎 | 修后归属判据 |
|---|---|---|---|
| 05-1 | `instancesWindowSelection(startMs, endMs).let { ("dtend > " + "?") to arrayOf(...) }` | rule A 逐字面量要求「`CalendarContract.Events.DTEND` + 比较 + `?`」三者同框；换成 SQL 物理列名 `dtend` 并把谓词拆进两个字面量就全躲过 | `predicate-on-nullable-column`（字面量组还原后判） |
| 05-2 | `val _unused = Instances.CONTENT_URI` + `Uri.parse("content://com.android.calendar/events")` + `query(eventsUri, …)` | rule B 是两条子串存在性：Instances 在（没人用）、`Events.CONTENT_URI` 不在（用的是硬编码字符串） | `freebusy-reads-instances`（query 实参值流） |
| 05-3 | `is CalendarTarget.Refused -> pickWritableCalendar(listWritableCalendars()) ?: return target.result` | rule C 只禁**无参** `pickWritableCalendar()` 拼写，并检查 `requireCalendarTarget(` 存在 | `write-refusal-is-unconditional`（拒绝分支整体内容） |
| 05-4 | `daysText.toIntOrNull() == null && false` | rule D 只看 `toIntOrNull() == null` 与 `return null` 子串存在 | `day-window-refusal`（`guard_feasible` + `path_feasible`） |
| 05-5 | `satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) \|\| true }` | rule E 用 `\{[^}]*hasRead\(\)` 找子串，两个权限调用都在就绿 | `write-path-requests-and-gates-both-permissions`（lambda 体常量折叠） |
| 05-6 | `resolveAlarmSelector(args.get("alarm")?.let { null })` | rule F 只要求「调用点存在 + 拒绝分支存在 + 无 `getInt("alarm")`」 | `alarm-decided-once-from-raw-string`（实参活性） |

### 2.2 router（03-1…03-6）

| # | 见证（最小补丁要点） | 旧判据为何瞎 | 修后归属判据 |
|---|---|---|---|
| 03-1 | `private val healthLock = Any()` 后插 `@JvmName(...)` 行，`get() = Any()` 落到第 3 行 | identity 判据只读声明行 + **紧邻的下一非空行**；注解行占住第 2 行，窗口当场关闭 | `healthLock is one stable monitor (backing-field shape)` |
| 03-2 | `synchronized(healthLock.let { Any() }) { health.clear() }` | span 归属只看 `"healthLock" in expr`：token 在，锁是每次新建的对象 | `healthLock is the monitor BY VALUE` |
| 03-3 | `synchronized(this) { finishPromotion(promotion) }`（resume 在辅助函数体内） | 旧判据是词法位置包含：`.resume(` 的偏移量永远不在块内 | `no lock-holding call reaches a resume site` |
| 03-4 | `@kotlin.jvm.Synchronized` | `@Synchronized\b` 不匹配限定名 | `no @Synchronized methods in SessionConcurrencyManager` |
| 03-5 | `synchronized(lock) { Unit }` + 读取搬到锁外 | 只问「函数体里出现过 `synchronized(lock)`」，空块也满足 | `store: get()/loadAll() body holds synchronized(lock)` |
| 03-6 | `list.remove(i); removed = true; break` | ledger 判据数 `return` 个数（恰好 1 个 = 扫完整个列表）；`break` 不改变计数 | `store: same-id ledger semantics …` |

---

## 3. 判据改造（形态对照）

共性仍是「锚拼写/存在 → 锚结构/可达/值流」，本波新增两个可复用形态：

| 形态 | 旧 | 新 |
|---|---|---|
| 字面量拼接 | 单个 `LITERAL_RE` 内三项同框 | `literal_groups()`：按 Kotlin `+` 语义把相邻字面量合并成一条字符串（**先去引号边界**，否则 `> ` 与 `?` 之间隔着两个引号）后再判 |
| 列名拼写 | 常量全限定名 `CalendarContract.Events.DTEND` | `dtend` 大小写无关（常量的值与 SQL 物理列名等价） |
| 调用的实参值流 | 「函数体里有没有某个子串」 | `_all_sources()`：`value_sources` + 解构声明 / 局部绑定回溯（`val (selection, selArgs) = …` 取同一条右值） |
| 决策分支 | 分支关键字存在 | 分支**全部内容**必须恰好是一条无条件 `return <x>.result`（`expr_end` 界定语句） |
| 守卫可行性 | 条件子串存在 | `if_blocks` + `guard_feasible`（`&& false` 折叠为恒假）+ `path_feasible`（拒绝可达）+ 裸 `return`/非 `return@` |
| 谓词常量折叠 | lambda 体内子串 | `const_bool(lambda_body)`：`x \|\| true` 折叠为 True 即红 |
| 实参活性 | 调用点存在 | 实参的 `value_sources` 必须真读 `args.get("alarm")`（`?.let { null }` / `takeIf { false }` 都不算） |
| 属性身份 | 声明行 + 下一行 | 属性**完整声明跨度**：连吞注解行与访问器行直到下一项声明 |
| 锁表达式身份 | token 共现 | `value_sources(lock_expr)` 必须求值为 monitor 本身 |
| 调用关系 | 词法位置包含 | `resumer_names()`：函数体含 `.resume(` 的、以及两跳内调用到它的函数；持锁调用它们 = 违规 |
| 锁块保护力 | 块存在 | 非空块 + 被保护读取**落在块内**（空块 / `{ Unit }` 不算） |
| 循环退出 | `return` 计数 | 禁循环体内 `break`（提前退出 = 没清干净） |

**共享原语接入**（任务书硬约束第 1 条）：两门自写的 `strip_comments` /
`code_only` / 花括号计数 / `brace_slice` / `block_span` / `fun_span` 全部删除，
一律改走 `_kotlin_lexer.{mask_code,mask_comments}` + `_kotlin_ast.{match_bracket,
if_blocks,const_bool,strip_parens,function_body}` + `_kotlin_valueflow.{code_view,
expr_end,value_sources,call_args,guard_feasible,path_feasible}`。
`KotlinLexError` 一律 fail-closed 报红（不回退原文扫描）。

> 本波直接兑现的教训：上一波 wide4 建的共享层在 24 门里 adoption=0，病因原样
> 复发 132 次。本支门内自写正则剥注释在 `"video/*"` 型字符串上会吞掉后续代码，
> 正是 parser-death 轴的入口。

---

## 4. 验证

**L1 自测（装置有牙且撞对判据）**

- calendar：`--selftest` → **37 臂全 PASS**（原 30；新增 6 条见证臂 + 1 条对照），
  收尾标记 `selftest: ALL ARMS MATCH`（冻结断言串，未改）。
- router：`--selftest` → `ROUTER_LOCK_SELFTEST=PASS (27 fixtures)` +
  `STORE_LOCK_CRITERIA=PASS (5 store criteria, 9 breakers)`（原 21 / 7）。
- 共享原语：`KOTLIN_LEXER_SELFTEST=PASS (20 arms)` /
  `KOTLIN_AST_SELFTEST=PASS (32 arms)` / `KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)`。

**L2 反攻（外部见证原样重放）**

```
[1/6] 05-1 -> CAUGHT (rc=1, exact)   [1/6] 03-1 -> CAUGHT (rc=1, exact)
[2/6] 05-2 -> CAUGHT (rc=1, exact)   [2/6] 03-2 -> CAUGHT (rc=1, exact)
[3/6] 05-3 -> CAUGHT (rc=1, exact)   [3/6] 03-3 -> CAUGHT (rc=1, exact)
[4/6] 05-4 -> CAUGHT (rc=1, exact)   [4/6] 03-4 -> CAUGHT (rc=1, exact)
[5/6] 05-5 -> CAUGHT (rc=1, exact)   [5/6] 03-5 -> CAUGHT (rc=1, exact)
[6/6] 05-6 -> CAUGHT (rc=1, exact)   [6/6] 03-6 -> CAUGHT (rc=1, exact)
```

restore 12/12 True，事后 `git status --porcelain src/` 为空。
逐条归属见 `probe/t13-attribution.json`（每条打印自己的违规行）：
05-1 红在 `predicate-on-nullable-column`、05-2/05-1(B) 红在
`freebusy-reads-instances`、05-3 红在 `write-refusal-is-unconditional`、
05-4 红在 `day-window-refusal`、05-5 红在
`write-path-requests-and-gates-both-permissions`、05-6 红在
`alarm-decided-once-from-raw-string`；03-1 healthlock identity、03-2 monitor
BY VALUE、03-3 lock-holding call、03-4 注解拼写、03-5 锁块保护力、03-6 ledger。

**L3 真树 + 邻门**

- 本门真树：calendar `PASS`（rc=0）、router `GATE=GREEN`（rc=0）。
- 邻门 8 道全 rc=0：`schedule_date_source` / `scheduled_store_release_guard` /
  `offload_permission_reset_sync` / `rss_probe_lock_scope` /
  `skill_restore_session_cleanup` / `auto_approve_bypass` / `parts_json_funnel` /
  `marker_boundary`。

**L4 全量（本地）**

- `scan.sh` 整跑在本沙箱被 stall（14/112 处进程消失）→ 健康路径：
  `probe/t05_gate_loop.py .` **逐门独立进程** → `110/110 rc=0, 0 red, 283s`；
  另跑 `i18n_check.py` → `ALL CLEAN`（合计 111 门，0 红）。
- `test_scan.py` 整跑 → **`✅ ALL 344 CASES PASS`**（0 ❌）。
- 全量读数最终以分支 CI 日志为准（见 §5）。

---

## 5. CI 读数

**run `40274349322` / #2723 / job `121413793062` / attempt 1 · success**（workflow_dispatch），
head_sha `8c95fd6ca` 与门提交**逐字符一致**。日志 1,486,628 B，NUL 0。26 步唯一
非 success = step 23 Publish to Releases **skipped**（main 门控，预期）。

| 判读项 | 读数 | 判定 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 | ✅ |
| `ALL 344 CASES PASS` | 恰 1 | ✅ |
| 门头 `[N/112]` | 112 个互异编号（1..112） | ✅ |
| `^ *e: ` 编译错误 | 0 | ✅ |
| 含 `FAILED` 的行 | 6 行，**全部以 PASSED 结尾**（用例名子串） | ✅ |
| calendar selftest 有牙 | `selftest has teeth (ALL ARMS MATCH)` 恰 1 | ✅ |
| calendar 真树 | `real tree green` 恰 1 | ✅ |
| router selftest 有牙 | `selftest has teeth (ROUTER_LOCK_SELFTEST=PASS)` 恰 1 | ✅ |
| router 真树 | `real tree green` 恰 1 | ✅ |
| `STORE_LOCK_CRITERIA=PASS` | CI 日志 0 命中 | 已知残留（门自证 stdout 不进 CI 日志，见 §6 R13；CI 可见的见证是上面两行 teeth） |

CI 后 `git diff --name-only 8c95fd6ca..HEAD -- . ':(exclude)fix-reports'` = **0**
（源码树与 CI 绿 sha 等价，后置提交纯报告）。

---

## 6. 残留（均 P2，各带触发条件 / 触发后方案 / 搁置理由）

- **R1 字面量组只还原 `+` 拼接**。
  触发条件：出现用 `buildString` / `String.format` / 模板内拼接构造 SQL 谓词的新代码。
  触发后方案：在 `literal_groups` 之上加 `String.format` 模板与 `buildString { append }` 的常量求值。
  搁置理由：全仓 `grep -c 'buildString'` 与 SQL 谓词无交集；当前形态 0 处。
- **R2 rule A 的误报面**。
  触发条件：某个字符串同时含 `dtend`、比较算子和 `?`（如日志模板 `"dtend > ?"`）。
  触发后方案：加「该字符串必须最终流向 `selection`/`where` 形参」的收敛判定。
  搁置理由：误报方向是**多报**（fail-closed），且全仓 0 处。
- **R3 `_all_sources` 只回绑一跳**。
  触发条件：`val a = b; val b = Instances…` 式两跳别名。
  触发后方案：把绑定回溯做成递归（带环检测）。
  搁置理由：判红方向（安全）；全仓 0 处两跳别名。
- **R4 query 接收者只认字面 `contentResolver.`**
  触发条件：`resolver.query(` / 别名接收者写 freebusy 查询。
  触发后方案：按 callee 名 `query(` 识别 + 用 `Cursor`/`projection` 特征排除非 provider 查询。
  搁置理由：本仓 freebusy 只有一处接收者形态；扩识别会带来误伤面。
- **R5 rule C 只识别 `when` 分支形态 `is CalendarTarget.Refused ->`**
  触发条件：把拒绝判断改写成 `if (target is CalendarTarget.Refused) { … }`。
  触发后方案：同判据复用 `if_blocks` 分支 + `requires` 内断言无条件 return。
  搁置理由：与 `when (val target = …)` 是同一结构族，改写属于重构不是绕过；当前 0 处。
- **R6 rule D 只认 `toIntOrNull() == null`**
  触发条件：改用 `parseIntOrNull` / `?.toInt()` / `runCatching { toInt() }`。
  触发后方案：把守卫识别扩成「解析结果为空/异常」三元判据。
  搁置理由：`resolveRangePure` 的注入回调已把解析集中，改解析器名会同时改调用点（可见）。
- **R7 rule E 只认具名实参 `satisfied = { … }`**
  触发条件：`ensurePermission(..., { hasRead() })` 位置实参形态。
  触发后方案：用 `call_args` 定位 ensurePermission 实参并按形参名映射。
  搁置理由：本仓 6 处调用全为具名；位置实参会在 review 中显形。
- **R8 rule F 的 `>= 0` 只判子串**
  触发条件：分类器保留 `>= 0` 文本但把它挪进死分支。
  触发后方案：对分类器体做 `if_blocks` + `path_feasible`。
  搁置理由：分类器只有 3 行；`resolveAlarmSelector` 的拒绝分支另有一条判据互锁。
- **R9 属性跨度只吞注解行 + 访问器行**
  触发条件：`val healthLock: Any; init { … }` 或 `field` 关键字形态。
  触发后方案：把 `init`/`field` 一并纳入跨度，并对 `field` 初始化做值判定。
  搁置理由：Kotlin 里 `field` 只在访问器内合法，与「有访问器即红」已互斥覆盖。
- **R10 `resumer_names` 闭包固定两跳**
  触发条件：三层间接（releaseSlot → helperA → helperB → finishPromotion）。
  触发后方案：改成不动点迭代（带访问集上限）。
  搁置理由：两跳已覆盖本文件全部实际结构；更深间接属重构（会同时改变锁叙事）。
- **R11 store 读取锚集合手写**
  触发条件：新增读取路径（不再经 `prefs.` / `loadAll(`）。
  触发后方案：从函数体的 value 来源派生「读」集合，而不是列锚。
  搁置理由：`ScheduledNotificationStore` 的读写面只有这两条。
- **R12 ledger 只禁 `break`**
  触发条件：`continue` + 计数跳过的变体（如 `if (x) continue` 掩盖残留行）。
  触发后方案：对循环体做「每个元素都被访问」的数据流判定。
  搁置理由：`continue` 不清除元素、不会漏删；实证形态是 `break`。
- **R13 门自证 stdout 不进 CI 日志**
  触发条件：需要从 CI 日志确认臂数（当前只能看到 `test_scan` 的 ✅ 行）。
  触发后方案：注册带臂数的 marker（`CALENDAR_NULLABLE_SELFTEST_ARMS=37` /
  `ROUTER_LOCK_SELFTEST=PASS (27 fixtures)` 已含在既有 marker 里，可直接沿用）。
  搁置理由：既有冻结断言（`ALL ARMS MATCH` / `ROUTER_LOCK_SELFTEST=PASS`）已能
  证明 selftest 真跑；臂数属增量信息。
- **R14 `literal_groups` 暂留门内**
  触发条件：第二道门也需要「按 `+` 还原字面量」。
  触发后方案：上收到 `_kotlin_ast`（本批硬约束禁止改共享层）。
  搁置理由：单独一门的局部需求，上收需同步改共享层验收面。

---

## 7. `test_scan.py` 注册状态

两门**早已在册**，本支未改冻结文件：

- `("calendar_events_nullable_predicate_check", "ALL ARMS MATCH", ("--root", REPO_ROOT))`（L2644）
- `("router_lock_check", "ROUTER_LOCK_SELFTEST=PASS", ("--root", REPO_ROOT))`（L2735）

两条冻结 marker 串**保持原样**（本支既未删除也未改写），新增臂被同一 marker
覆盖 ⇒ 无需主会话追加注册项。

建议（可选，交主会话）：若希望臂数进 CI 日志，可另加
`("calendar_events_nullable_predicate_check", "CALENDAR_NULLABLE_SELFTEST_ARMS=37", …)`
形态的独立注册项（该 marker 目前尚未打印，需在门内新增一行输出）。

---

## 8. 共享层使用事实与自伤记录

**共享层使用**：本支两门是第五批之后 **adoption 由 0 转正的又一批**——
`_kotlin_valueflow` 的 `value_sources` / `guard_feasible` / `path_feasible` /
`call_args` 全用上，`_kotlin_ast` 的 `match_bracket` / `if_blocks` / `const_bool` /
`strip_parens` / `function_body` 全用上。在真用中暴露的缺口（未改共享层，按硬约束登记）：

1. `function_body` 只返回**第一个**同名函数且不给签名 → 判据需要「签名 + 绝对位置」
   时必须在共享 `match_bracket`/`expr_end` 之上写 `all_fun_regions`（R14 同族）。
2. `value_sources` 不解析局部标识符绑定（`selection` 直接返回 `['selection']`）→
   门内补 `_binding_rhs`（解构声明支持）。**建议主会话评估上收**：这是所有
   「实参 → 取值来源」判据都要用的能力，本波两门各自实现了一份。

**自伤（本波 4 条，均已闭环）**

1. `literal_groups` 一开始把字面量**连引号**一起拼接，`("dtend > " + "?")` 变成
   `"dtend > ""?"`，`[<>=!]+\s*\?` 永远匹配不上 → 05-1 臂当场 FAIL（自证臂抓到）。
   规则：拼接前必须先剥字面量边界。
2. `guard_feasible` / `path_feasible` 一开始从 `_kotlin_ast` 导入（实际在
   `_kotlin_valueflow`），门直接 ImportError —— 共享原语职责边界要按模块查，不按印象。
3. `rule_f` 替换时把文件里紧邻的 `ALARM_ARG_RE` 常量切掉了（splice 以
   `def rule_f(src):` 为起点）→ NameError；属于「按函数边界 splice 会吞掉
   前置常量」的同族坑（第 2 次）。
4. `rule_c` 的新判据一开始只写了计划没落地，自证臂「refused selector falls back
   to auto-pick」当场 FAIL（0 violation）—— **臂先于实现**是正确的，它把
   「我以为改了」变成可判定的红。
