# FIX-BATCH7-t10 — schedule_date_source_check + overlay_show_hide_latch_check（12 条见证全翻红）

- 分支：`fix/b7-scheduledate-overlay`，基线 `base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）
- 改动面：**2 个门脚本**（`schedule_date_source_check.py` +1022/−631 量级、`overlay_show_hide_latch_check.py` +661/−330 量级，合计 `1052 insertions / 631 deletions`），**生产 `.kt` 0 行**，`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 零触碰（`git status --porcelain` 只有这两个文件）
- 反攻：官方驱动 `verify_batch5.py --mode full` → **12/12 CAUGHT（rc=1, exact），restored 12/12 True**，`git status --porcelain src/` 为空
- 真树：两门各自 rc=0；逐门全量 **111/111 绿**；`test_scan` 冻结夹具（DA1016 组 18 项 + DA1046 组 4 项）全绿
- 残留：9 条（§6，均 P2，各带触发条件/触发方案/搁置理由）

---

## 1. 门与轴

| 门 | 见证 | 轴分布 | 修前 | 修后 |
|---|---|---|---|---|
| `scripts/scan/schedule_date_source_check.py` | 14-1 … 14-6（6 条） | dataflow 2 · family-hole 3 · spelling 1 | 6/6 BLIND | 6/6 CAUGHT |
| `scripts/scan/overlay_show_hide_latch_check.py` | 09-1 … 09-6（6 条） | family-hole 4 · dataflow 2 | 6/6 BLIND | 6/6 CAUGHT |

两门同一族失效：**判据锚「token / 子串 / 位置存在」，缺陷动「该 token 求出的值 / 该分支是否可达 / 值流向谁」**。

| 轴 | 本波的具体形态 | 修法落点 |
|---|---|---|
| `dataflow` | 数据在**括号内部**被换掉（`.let { LocalDate.now() }`）、结果被**丢弃**（`.let { _ -> it }`）、守卫**实参**被常量替换（`.let { true }`）、局部别名（`val shouldHide = controller.isShown`） | `value_sources` / 后置链值流扫描 / `vf_call_args` 取实参 / 别名展开两跳 |
| `family-hole` | 守卫被**常量**判死（`&& false`、`&& 1 == 2`、`|| true`）、分支体**不终止**（`{ Unit }`）、return 落在**死分支**（`if (false) return`） | `const_bool` 逐操作数折叠 + `guard_feasible` + `path_feasible` + 「块必须真的 abort」 |
| `spelling` | 宽松模式写成表达式（`isLenient = !false`） | 对 RHS 做 `const_bool` 折叠后**取最后一次写**，不匹配字面量 |

---

## 2. 见证原文（file / find / replace / why_gate_green）

### 门 1 `schedule_date_source_check.py`

**14-1**（dataflow，`G1:scheduler:completeRun_routed_through_gateDateOnSettle [scheduler:…]`）
- file: `src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTaskRunner.kt`
- find: `ScheduledRunDating.gateDateOnSettle(scheduledDate, LocalDate.now().toString(), outcome),`
- replace: `ScheduledRunDating.gateDateOnSettle(scheduledDate.let { LocalDate.now().toString() }, LocalDate.now().toString(), outcome),`
- why_gate_green: 旧门 `strip_calls(gate, DATING_CALL)` 把整个 `gateDateOnSettle(⋯)` **连同括号内部**替换成空串，`CLOCK_IN_ARG` 只在剩余文本上搜 ⇒ 写进实参里的 `LocalDate.now()` 天然隐身；`gateDateOnSettle` 定义体未改，仍返回 `scheduledDate`。
- 后果：23:55 触发、00:06 结算的 run 把**明天**写进 `lastRunDate`，次日全天 `ALREADY_RAN`（ISSUE-09A 100% 回归）。

**14-2**（dataflow，`calendar:alignBareDateEnd_and_origin`）
- file: `…/sandbox/offload/CalendarOffloadHandler.kt`
- find: `?.let { alignBareDateEnd(it, endStr, zone) }`
- replace: `?.let { alignBareDateEnd(it, endStr, zone).let { _ -> it } }`
- why_gate_green: 旧门只断言 `alignBareDateEnd(` 存在 + 三个实参形态（`align_call_gaps`），**从不问返回值去了哪**。
- 后果：单日 `freebusy --start D --end D` 对齐结果被丢弃 → 零宽度窗口 → 空答案（ISSUE-09B）。

**14-3**（family-hole，同上判据）
- find: `if (DATE_ONLY.matches(raw.trim())) endOfLocalDay(parsedMs, zone) else parsedMs`
- replace: `if (DATE_ONLY.matches(raw.trim()) && false) endOfLocalDay(parsedMs, zone) else parsedMs`
- why_gate_green: 旧门只查 `"DATE_ONLY" in align` 子串；`&& false` 不在任何被检形态里。
- 后果：`alignBareDateEnd` 永远返回未对齐值，`list` 与 `freebusy` 两条路径同时瘫痪。

**14-4**（family-hole，`calendar:doCreate_createEndMs_refusal`）
- find: `if (rawEnd.isNullOrBlank()) return startMs + 60L * 60 * 1000`
- replace: `if (rawEnd.isNullOrBlank() || true) return startMs + 60L * 60 * 1000`
- why_gate_green: 旧门只查 `"isNullOrBlank" in cem` 与 `"60L * 60 * 1000" in cem` 两个子串——两个都还在。
- 后果：present-but-unparseable 与**合法多小时 --end** 一起被塞进 1 小时默认（ISSUE-04(A)）。

**14-5**（spelling，`parsers:strict_date_parser_isLenient_false`）
- find: `isLenient = false`（`parseDateInZone` 的 `apply{}` 内）
- replace: `isLenient = false\n                    isLenient = !false`
- why_gate_green: 旧门正则只捕获 `(true|false)` 字面量，`!false` 不进 `modes` 列表 ⇒ `relaxed` 空 ⇒ 判严格。
- 后果：`--start 2026-02-30` 被静默滚到 3 月 2 日并作答（dv16-A A-01）。

**14-6**（family-hole，`photos:resolvePhotoRange_and_handleList_invalid`）
- find: `is PhotoRange.Invalid ->\n                return NativeOffloadResult(2, "android-photos list: invalid ${r.flag} '${r.raw}'\n")`
- replace: `is PhotoRange.Invalid -> {\n                if (false) return NativeOffloadResult(2, …)\n                PhotoRange.Window(null, null)\n            }`
- why_gate_green: 旧门只查 `"PhotoRange.Invalid" in hl` 与 `"android-photos list: invalid" in hl` 两个子串——分支与文案都还在。
- 后果：非法 `--start` 降级为「无过滤」→ 以 exit 0 返回整库照片（ISSUE-04(B)）。

### 门 2 `overlay_show_hide_latch_check.py`

**09-1**（family-hole，`CRIT_C1`）
- file: `…/service/ToolOverlayController.kt`
- find: `        desiredShown = false\n`（`hide()` 入口）
- replace: `        desiredShown = false\n        this.desiredShown = 1 == 1\n`（同时覆盖 `1 == 1` 与 `this.` 两种拼写）
- why_gate_green: 旧门 `assign_positions(hide, intent, "true")` 是**精确字面量**正则 `(?<![.\w])name\s*=\s*true(?![\w.])`，`1 == 1` 不匹配，`this.` 前缀被负向后顾直接排除。
- 后果：`hide()` 刚发布 `false` 就被重新拉回 `true`，排队中的 `attach()` 照常贴屏且无后续状态帧清除（S-06 原样回归）。

**09-2**（family-hole，`CRIT_C2`）
- find: `            if (!shouldRunShowPost(desiredShown)) return@post\n`
- replace: `            if (!shouldRunShowPost(desiredShown) && 1 == 2) return@post\n`
- why_gate_green: 旧门 `literal_decides` 只枚举 `true/false/!true/!false` 四个拼写。
- 后果：早退永不执行，`hide()` 无法取消已排队的 attach。

**09-3**（dataflow，`CRIT_C2`）
- replace: `            if (!shouldRunShowPost(desiredShown.let { true })) return@post\n`
- why_gate_green: 旧门只要求 cond 里出现 `desiredShown` 且 `literal_decides` 为 False；整项不是字面量。
- 后果：守卫读到的是常量 `true`，`return@post` 恒不执行。

**09-4**（family-hole，`CRIT_C2`）
- replace: `            if (!shouldRunShowPost(desiredShown)) { Unit }\n`
- why_gate_green: 旧门 `tail.startswith("{") or tail.startswith("return")` —— 只要 `)` 后面是 `{` 就算早退，**不看块里有没有 return**。
- 后果：条件成立却落进空块，继续 `attach()`。

**09-5**（dataflow，`CRIT_C3`）
- file: `…/service/AgentForegroundService.kt`
- find: `            controller.hide()\n            return\n`
- replace: `            val shouldHide = controller.isShown\n            if (shouldHide) controller.hide()\n            return\n`
- why_gate_green: 旧门只在条件文本里搜 `isShown` 字面串，条件是 `shouldHide`。
- 后果：`isShown` 仍为 `false` 时跳过 `hide()`，排队 attach 存活。

**09-6**（family-hole，`CRIT_C3`）
- replace: `            controller.takeIf { it.isShown }?.let {\n                it.hide()\n            }\n            return\n`
- why_gate_green: 旧门只查 `hide()` **所在行**前缀的 `takeIf`（那行前缀是 `it.`），而块头扫描只认 `if/while/when` 的 `) {` 形态 ⇒ 跨行 `takeIf {…}?.let {` 两头落空。
- 后果：`takeIf` 返回 null 时整个 `hide()` 被跳过。

---

## 3. 判据改造（旧形态 → 新形态，逐条）

### 3.1 `schedule_date_source_check.py`

| # | 旧判据（失效形态） | 新判据（锚效果） |
|---|---|---|
| 14-1 | `DATING_CALL in gate` + `strip_calls` 后搜时钟 | 用共享 `call_args` 取约会调用**第 1 个实参**，`value_sources` 逐来源判：任一来包含时钟读 ⇒ 红（报「wall-clock read」）；否则必须派生自 `Dispatch(scheduledDate = …)` 绑定的标识符（`scheduled_day_idents` 派生，不是写死名字）。括号外的时钟读改为 `blank_spans(约会调用 span)` 后搜 |
| 14-2 | 「存在 `alignBareDateEnd(` 且实参形态对」 | `postfix_chain_keeps_value`：顺着调用闭括号之后的 `.let/.run/.also/…` 链走，`let/run` 的 lambda 尾巴若既不引用形参也不引用 `it`（`{ _ -> it }`、`{ ms }`）⇒ 红「discards its own result」 |
| 14-3 | `"DATE_ONLY" in align` 子串 | `function_body` 取 `alignBareDateEnd` 体 + `if_blocks` 找 then 里产 `endOfLocalDay(` 的那个分支 + `guard_feasible(cond)`：折成 False ⇒ 红「is DEAD」；找不到该分支 ⇒ 红「no longer returns the end-of-day value」 |
| 14-4 | `isNullOrBlank` / `60L * 60 * 1000` 两个子串 | 该分支守卫 `const_bool(cond) is True` ⇒ 红「ALWAYS taken」；另加「present 值必须到达解析器」：体内须有可达的 `parse(<raw 形参>)`（`const_bool` 之外走 `path_feasible` + `call_args`） |
| 14-5 | 正则 `(true\|false)` 字面量 | `leniency_writes()`：`isLenient =` / `setLenient(` 两种写形态，RHS 用 `expr_end` 取**完整表达式**后 `const_bool` 折叠；判**最后一次写**——折 True ⇒ 红（re-enables LENIENT），折不出（None）⇒ 红（fail-closed，拒绝猜） |
| 14-6 | `"PhotoRange.Invalid" in hl` + 文案子串 | 取 `when` 里 `is PhotoRange.Invalid ->` 的**臂体**（`match_bracket`/`expr_end`）：出现 `PhotoRange.Window(` ⇒ 红「UNFILTERED」；必须存在 `path_feasible` 的 `return`，且其表达式是 `NativeOffloadResult(` 且（literal_view）含 `invalid` ⇒ 否则红「never returns on a reachable path」/「refusal message is gone」 |

### 3.2 `overlay_show_hide_latch_check.py`

| # | 旧判据 | 新判据 |
|---|---|---|
| 14-1 | `assign_positions(body, name, "true")` 精确字面量；`(?<![.\w])` 排除 `this.` | `assignments()`：`(?<![\w.])(?:this\s*\.\s*)?<name>\s*=(?!=)` + `expr_end` 取完整 RHS + `const_bool` 折叠 ⇒ 写的是值不是拼写；show 折 False / hide 折 True 即红，折不出也红（fail-closed） |
| 14-2 | `literal_decides` 只认 4 个布尔拼写 | `constant_decides`：按 `\|\|` → `&&` 递归 `split_top`，逐操作数 `const_bool`；`&&` 有 False 臂 / `\|\|` 有 True 臂 / 整体可折 ⇒ 红 |
| 14-3 | cond 里出现 `desiredShown` 就算读到 | 两条腿：`wrapper_ignores_receiver`（`.let/.run` 的尾巴不派生自接收者）+ `guard_arg_constant`（`call_args` 逐实参 `value_sources` 全为常量）⇒ 红「no longer evaluates to」 |
| 14-4 | `tail.startswith("{")` 视为早退 | `tail_aborts`：then 体内必须有 `return/throw` 且 `path_feasible`（`if (false) return@post` 也红） |
| 14-5 | 只在条件文本搜 `isShown` | `expand_aliases`（两跳）：条件里的标识符反查前置 `val/var x = …` 并展开其 RHS 再判 |
| 14-6 | 同行 `prefix` 搜 `takeIf` + 只认 `if/while/when` 块头 | 「包含 pos 的所有花括号块」（`brace_blocks` = 共享 `match_bracket`）+ 每个块的 header 窗口内查找 `takeIf/takeUnless/filter/filterNot` 的 lambda 体 ⇒ 跨行 `takeIf {…}?.let {` 也被读成门控 |

**共用改造（两条硬约束）**
- 自写词法器/括号计数**全删**：`prepare()`（自带字符串/注释状态机）、`block_at()`、`paren_cond()`、`paren_cond_at()`、`enclosing_conds()`（旧 overlay 门前缀/块头扫描）、`member_slice()`、`call_args()`、`strip_calls()`（旧 schedule 门）——改为 `mask_code` / `match_bracket` / `function_body` / `if_blocks` / `call_args` / `expr_end` / `const_bool` / `split_top` / `strip_parens` / `tail_expr` / `value_sources` / `path_feasible` / `guard_feasible` / `contains_ident`。
- `KotlinLexError` fail-closed：两门都在 `check()`/`scan()` 里捕获并报违规（schedule 与 overlay 各带一条自测臂，断言「未闭合字符串」确实 raise/detected，不许回退原文扫描）。

---

## 4. 四层证据

### L1 本门自测 + 真树

```
$ python3 scripts/scan/schedule_date_source_check.py --selftest
SELFTEST=PASS (schedule-date-source: 2 files x 2 arms, teeth on every criterion)   # 冻结标记，原样保留
SCHEDULE_DATE_SOURCE_SELFTEST=PASS (29 arms)                                        # 新臂计数（见 §7）

$ python3 scripts/scan/schedule_date_source_check.py .
schedule-date-source: OK (scheduled day threaded; bare-date end aligned; DST-safe day math)   # rc=0

$ python3 scripts/scan/overlay_show_hide_latch_check.py --selftest
[selftest ok-arm] real tree parses clean: OK
… 18 条变异臂各只红自己那一条判据 + missing-file + lexer-fail-closed …
OVERLAY_SHOW_HIDE_LATCH_SELFTEST=PASS
OVERLAY_SHOW_HIDE_LATCH_SELFTEST_ARMS=21

$ python3 scripts/scan/overlay_show_hide_latch_check.py .
[overlay-show-hide-latch] OK: 4 criteria clean — …    # rc=0
```

臂数：schedule **29**（原 21 + 8 新）、overlay **21**（原 15 + 6 新）。每条新臂都断言「红在应当红的判据上」，并配套「不许误伤」的反向断言（例如 `align-result-discarded` 同时断言 `as the raw date` 规则**没有**误触发；`create-empty-raw` 的 raw-date 规则不被 chain 规则顶替）。

### L2 反攻（硬验收，官方驱动 `verify_batch5.py --mode full`）

```
[1/6] schedule_date_source_check.py :: 14-1 -> CAUGHT (rc=1, exact)
[2/6] schedule_date_source_check.py :: 14-2 -> CAUGHT (rc=1, exact)
[3/6] schedule_date_source_check.py :: 14-3 -> CAUGHT (rc=1, exact)
[4/6] schedule_date_source_check.py :: 14-4 -> CAUGHT (rc=1, exact)
[5/6] schedule_date_source_check.py :: 14-5 -> CAUGHT (rc=1, exact)
[6/6] schedule_date_source_check.py :: 14-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}      # probe/t10-schedule.json  restored 6/6 True, find_hits 1, resolve_how exact

[1/6] overlay_show_hide_latch_check.py :: 09-1 -> CAUGHT (rc=1, exact)
… 09-2 … 09-6 同 → CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}      # probe/t10-overlay.json   restored 6/6 True

$ git status --porcelain src/      # 空（变异零残留）
```

补充自造驱动（`probe/t10_witness.py`，仅作「修前确实 BLIND」的对照，不作为验收依据）：12 条补丁分别打到**旧门副本**（`git show HEAD:` 版本）与新门 ⇒ **12/12 `BLIND_CONFIRMED` → CAUGHT**，`probe/t10-witness.json`。

### L3 邻门（9 道，同源文件或同语义）

`calendar_events_nullable_predicate_check` · `discarded_return_guard` · `file_provider_paths_coverage_check` · `offload_cli_args_check` · `offload_host_path_check` · `prefs_listener_holder_guard` · `readonly_mount_write_guard` · `scheduled_store_release_guard` · `share_dir_prune_check` → **9/9 rc=0**。

### L4 全量与冻结面

- **逐门全量**：`probe/t10_sweep.py`（一门一进程，健康路径）→ **111/111 rc=0**（109 个 `*_check.py`/`*_guard.py` + `process_logging_gate` + `turn_tail_finalize_order`），`probe/t10-gatesweep.json`。
- `bash scripts/scan/scan.sh` 本窗口**未跑通**：父进程在等子进程时无输出、无 CPU，被 stall-guard 以 125 终止（S28 家族的环境层失败，非门红）。全量证据改由「逐门 111/111」+「分支 CI」承担。
- `test_scan.py`（冻结，未改）按组跑：
  - `test_da1016_gates` → **pass=18 fail=0**（含 `schedule_date_source_check selftest has teeth (SELFTEST=PASS (schedule-date-source: 2 files x 2 arms, teeth on every criterion))` + `real tree green`）
  - `test_da1046_gates` → **pass=4 fail=0**（含 `overlay_show_hide_latch_check selftest has teeth (OVERLAY_SHOW_HIDE_LATCH_SELFTEST=PASS)` + `real tree green`）
- 冻结面零触碰核验：`git status --porcelain` 只有本门两个脚本；`scan.sh`/`test_scan.py`/三个共享模块/生产 `.kt` 全未动；门数 112 / 夹具 344 不变。

---

## 5. CI

| 项 | 值 |
|---|---|
| run | `40270280984` / **#2718** |
| job | `121401414929`（`build`）· attempt **1** |
| 结论 | **success**（04:40:40Z → 05:00:09Z，19m34s） |
| head_sha | `626eee8bf2af56586f0a1b13b0346867200c2ff5`（= 本地门提交，逐字符一致，日志内亦命中） |
| 步骤 | 26 步；唯一非 success = **step 23 `Publish to Releases` skipped**（main 门控） |
| 日志 | 1,486,442 B，**NUL 0** |

逐条读数（`probe/ci/job.log`，全部机械计数）：

- `Summary: 112 passed, 0 failed` 恰 **1** 次（带框线那行）
- `ALL 344 CASES PASS` 恰 **1** 次
- 门头 `[N/112]` 共 **112** 条，序号 **112 互异**、显示名 **112 互异**（无重复步）
- ` e: ` 编译错误 **0**
- 含 `FAILED` 的行 **6** 条，逐条确认**全是 PASSED 用例名子串**（`produces FAILED without calling provider` 等）
- `SELFTEST=PASS (schedule-date-source …)` **1** 次 · `OVERLAY_SHOW_HIDE_LATCH_SELFTEST=PASS` **1** 次 ⇒ 两门冻结断言在 CI 里有见证
- 新臂计数标记 `SCHEDULE_DATE_SOURCE_SELFTEST=PASS (29 arms)` / `OVERLAY_SHOW_HIDE_LATCH_SELFTEST_ARMS=21` 在 CI 日志 **0 命中** —— 与预期一致：`test_scan` 只回显冻结标记而不回显门 stdout ⇒ 见 §6 **R8** 与 §7 的两条注册建议（本支不改冻结面）

CI 绿后 `git diff --name-only 626eee8bf..HEAD -- . ':(exclude)fix-reports'` = **0 行**（本支唯一后续提交是这份报告）。

---

## 6. 残留

| ID | 内容 | 触发条件（何时必须修） | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `CRIT_C2` 判不了**守卫被调函数体**被掏空（09-3 自疑：`shouldRunShowPost(desired) = true`）。门只读 controller + service 两个文件，该函数在 `ToolOverlayPolicy.kt` | 下一次见证点名第三个文件；或有人改 `shouldRunShowPost` 的签名/所在文件 | 把 `ToolOverlayPolicy.kt` 纳入 `scan()`（missing-file 语义、封 fail-closed 分支），断言守卫函数体内引用其形参、且不是常量返回 | 超出 §3 见证半径（见证只改调用点）；生产体是 `= desired`，当前无可触发路径（P2） |
| R2 | `align_call_gaps` 的「align 实参来自本作用域 parse」用的是 `\w*[Pp]arse\w*\(\s*<ident>` 正则：若写成 `val parsed = parse(endStr); align(parsed, endStr, zone)`（中介 val），candidate=`parsed` 不在任何 parse 调用的实参位置 ⇒ 误红 | 出现「先绑定再对齐」的中介 val 写法 | 用 `decl_sites` 反查 `val parsed = parse(…)` 一跳，或直接判「candidate 的初始化表达式含 parse 调用」 | 现状两处 align 调用都是直接内联（`it` / `parsed` 由 `parse(...) ?: return` 派生且带 parse 调用），无误红（P2，方向安全） |
| R3 | `postfix_chain_keeps_value` 对未知成员链（`.toString()`/`.plus(1)`/自定义扩展）一律判「保留接收者」 | 新增「返回常量」的扩展函数被用在 align 之后 | 从源码树派生「返回常量的扩展函数」名单，命中即红 | 仓库无此类扩展（P2） |
| R4 | `CRIT_C1` 对「折不出常量的 latch 写」判红（fail-closed） | latch 改成复合表达式写入（如 `desiredShown = wanted && hasPerm`） | 改判「写入值是否含 show/hide 语义常量」，或对意图集合建模 | 现状两处写都是字面量；红的方向要求人重新审，安全（P2） |
| R5 | `returned_expressions` 对 `= when { … }` 体只给出整个 `when`，不逐臂 | `gateDateOnSettle` 改成 `when` 表达式体 | 用 `when_branches` 逐臂取值再判 `scheduledDate`/时钟/settle | 现状单表达式（P2） |
| R6 | 14-6 的「told」腿要求返回表达式（literal_view）含 `invalid` 字面串 | 改拒绝文案（本地化/换词） | 从 `PhotoRange.Invalid` 的构造点与消息模板派生，不写死词 | 文案是固定 ASCII 片段，稳定（P2） |
| R7 | `leniency_writes` 的 `setLenient(` 分支用 `body.find("(", m.start())` 定位左括号 | 出现 `setLenient /*c*/ (false)` 之类把括号推远的写法（注释已被 mask 成空格，实际仍可取到；但若插入换行+其它括号会错位） | 用 `m.end()` 之前的最后一个 `(` 定位 | 4 个 parser 全是 `isLenient = false`（P2） |
| R8 | 新臂计数标记（`SCHEDULE_DATE_SOURCE_SELFTEST=PASS (29 arms)` / `OVERLAY_SHOW_HIDE_LATCH_SELFTEST_ARMS=21`）**未在 `test_scan.py` 注册**，CI 里只有冻结旧标记有见证 ⇒ 「臂被删掉」不能过门 | 主会话并集收口统一注册时 | 见 §7 两条注册建议 | `test_scan.py` 冻结不可改（P2，形态同 B6-t12 R8） |
| R9 | `expand_aliases` 是**文件级并集**近似（同名前置声明全部取并集），不是作用域解析 | 同一个标识符在不同函数里绑不同值，且其中一个 RHS 含 `isShown` | 把展开范围限定到包含 pos 的那个 `function_body` 内 | 真树零命中（C3 green），近似方向是「多报」而非漏报（P2） |

---

## 7. `test_scan.py` 注册项（交主会话统一注册，本支不改冻结面）

现有两条断言**已经在新代码下通过**（标记串原样保留），因此本支不需要改冻结面：

```python
("schedule_date_source_check",
 'SELFTEST=PASS (schedule-date-source: 2 files x 2 arms, teeth on every criterion)', (REPO_ROOT,)),
("overlay_show_hide_latch_check", "OVERLAY_SHOW_HIDE_LATCH_SELFTEST=PASS", (REPO_ROOT,)),
```

建议（并集收口时一次性改，属 R8）：

1. `schedule_date_source_check` 的标记文本已**过时**——本门现在覆盖 **3 个生产文件 + 4 个 strict parser、29 条臂**，不再是「2 files x 2 arms」。建议把断言串改为
   `'SELFTEST=PASS (schedule-date-source: 3 files + 4 parsers x 29 arms, teeth on every criterion)'`，
   或保留旧串另加一行断言 `SCHEDULE_DATE_SOURCE_SELFTEST=PASS (29 arms)`。
2. `overlay_show_hide_latch_check` 建议追加第二条断言：`OVERLAY_SHOW_HIDE_LATCH_SELFTEST_ARMS=21`。

两门门数不变（112），`scan.sh` 无需重编号。

---

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t10

# 共享原语自证（动手前）
python3 scripts/scan/_kotlin_lexer.py --selftest        # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest          # 32 arms
python3 scripts/scan/_kotlin_valueflow.py               # 26 arms

# L1 本门
python3 scripts/scan/schedule_date_source_check.py --selftest
python3 scripts/scan/schedule_date_source_check.py .
python3 scripts/scan/overlay_show_hide_latch_check.py --selftest
python3 scripts/scan/overlay_show_hide_latch_check.py .

# L2 反攻（官方驱动；restore 校验在 JSON 的 restored 字段）
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-B.json --repo . --mode full \
  --only schedule_date_source_check.py --out …/probe/t10-schedule.json
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-B.json --repo . --mode full \
  --only overlay_show_hide_latch_check.py --out …/probe/t10-overlay.json
git status --porcelain src/     # 必须为空

# 修前 BLIND 对照（自造驱动，仅对照用）
python3 probe/t10_witness.py

# L3 邻门
for g in calendar_events_nullable_predicate_check discarded_return_guard \
         file_provider_paths_coverage_check offload_cli_args_check offload_host_path_check \
         prefs_listener_holder_guard readonly_mount_write_guard \
         scheduled_store_release_guard share_dir_prune_check; do
  python3 scripts/scan/$g.py . || python3 scripts/scan/$g.py --root .; echo "$g rc=$?"
done

# L4 全量（逐门一进程，健康路径）
python3 probe/t10_sweep.py

# 冻结夹具（按组跑，绕开 stall-guard）
PYTHONPATH=scripts/scan python3 -c "
import sys; sys.argv=['test_scan.py','.']
sys.path.insert(0,'scripts/scan')
import test_scan as t; t.test_da1016_gates(); t.test_da1046_gates()
print('pass=%d fail=%d' % (t.PASS, t.FAIL))"
```
