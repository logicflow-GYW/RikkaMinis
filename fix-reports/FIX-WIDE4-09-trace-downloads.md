# FIX-WIDE4-09 — 两门判据加固（trace 标注契约 / downloads 原子写）

- **分支**：`fix/wide4-09-trace-downloads`
- **基线**：main `19e70dab`（wide3 并集收口）+ 共享原语 `dbab7a3e`
- **面**：`trace_step_tag_check.py` · `downloads_atomic_update_check.py`
- **见证**：10 条（trace 5 + downloads 5，来自 META-WIDE4 两份外部审计报告的 WIDE4-09 段）
- **生产代码**：**0 行改动**（只改上列两个门脚本；`scan.sh` / `test_scan.py` 冻结面零触碰）
- **门数 / 夹具**：112 / 344 不变（未新建门）

---

## ① 门与判据

| 门 | 守的契约 | 判据（前 → 后） |
|---|---|---|
| `trace_step_tag_check.py` | 事件标注器依赖的 trace 契约：4 个事件类型 + 4 个必填字段必须**在可达路径上、带着派生自事件的真实数据**写出；`write()`/`writeTerminalOnce()` 的行构建与 `beginRun()` 的三字段更新必须落在**同一把稳定实例锁**内 | 6 面 → 10 面（接线面 4 项 → 6 项，锁面 3 项 → 5 项） |
| `downloads_atomic_update_check.py` | 下载状态只能经 `_downloads.update { }` 原子改；共享流入口（tryEmit/emit/getAndSet/**compareAndSet**）不得绕过 CAS；CAS lambda 必须**把参数用进返回值** | R1/R1b/R2/R3/R4/R5 六条 → 同一组判据但锚点全部换成「派生集合 / 返回值 / 词法视图」 |

两门共用同一基线变量：`19e70dab` 的树 + 本支门改动。共享词法/语法原语（`_kotlin_lexer.py` / `_kotlin_ast.py`，`dbab7a3e`）**未改动**，只 import（任务书 §3.2 冻结）。

## ② 失效轴（本支两门）

| 轴 | 门 | 形态一句话 |
|---|---|---|
| family-hole（**新轴**，10 条见证里占 8 条） | 两门 | 判据的**枚举**不闭合：`provably_false` 只认裸 `false`（`!true` / `1 == 2` 全漏）；字段值判据只问「有没有标识符」（`sanitize("", ARGS_MAX_LENGTH)` 用常量顶替数据源）；锁判据只看调用点在块内，不看锁对象**是不是同一把**（`get() = Any()`）；写入口白名单漏了 `compareAndSet`；接收者集合写死 `_downloads`（别名一层就绕开） |
| dataflow（**判据锚「提到」而非「承重」**） | downloads R3 · trace 锁面 | `val _ignore = current; emptyList()` 里参数「提到过」就算消费；`if (true) return` 之后的三行赋值「在锁块内」就算更新 |
| parser-death | 两门 | 两门各有一套手写词法器：块注释遇第一个 `*/` 就退出（Kotlin 块注释可嵌套）、单引号字符字面量 `'"'` 被当字符串起始吞掉整段真代码 |

**一句话结论**：上一轮的判据几乎全部锚在「某个拼写在不在」上；本轮把每一条都换成「**那个拼写所代表的效果**是否真的达成」——可达性（恒假分支/早退）、派生性（值来自形参而不是常量）、身份（锁是同一把 / 接收者是同一个流）、消费（返回表达式追得到参数）。

## ③ 逐条见证（10/10）

### trace_step_tag_check

| # | 见证（外部审计报告原文形态） | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| T1 | `if (!true) put("args", sanitize(argsJson, ARGS_MAX_LENGTH))` | family-hole | `_const_val` 只认裸 `true`/`false`/`0`/`1`，逻辑非没折叠 ⇒ `provably_false` 返回 False ⇒ 字段算「可达」 | `_const_bool`（共享常量折叠：`!X` / `X && Y` / `X \|\| Y` 短路语义 / `A == B`）；`dead_guards_at` 用共享 `if_blocks`（covered then/else + braceless）与 `when_branches` |
| T2 | `put("args", sanitize("", ARGS_MAX_LENGTH))` | family-hole | `is_constant_expr` 只问「还有没有标识符」，函数名 `sanitize` + 常量名 `ARGS_MAX_LENGTH` 都算 ⇒ 数据源被换成空串仍绿 | `_value_failure()`：值必须**派生自形参/局部量**（全大写常量名、函数/方法名、`true/false/null/this` 都不算数据）；形参集合从发布树派生（`_fun_spans` + `_param_names`），不写死函数名 |
| T3 | `private val writeLock: Any get() = Any()` | family-hole | `LOCK_CALL` 只判「调用点在 `synchronized(writeLock)` 块内」⇒ 每次访问分配新 monitor，六条子判据全绿 | `_lock_field_problems()`：`writeLock` 必须声明为**稳定实例字段**（`val writeLock = Any()`，带 `get()` 或非常量初始化器一律红） |
| T4 | `beginRun` 的三行赋值包进 `if (false) { … }` | family-hole | 同一族：`lock_open < w < lock_end` 成立即算「更新了 run 上下文」 | 逐赋值点 `dead_guards_at(body, w)` + `_early_return_before()`（裸 `return` / `if (true) return` 也让后续语句不可达）；`buildLine` 在锁内的调用同样受这两条约束 |
| T5 | `/* outer /* inner */ put("args", …) */` | parser-death | `mask_comments` 遇第一个 `*/` 退出，被 Kotlin 丢弃的字段在掩码视图里成了活代码 | 词法一律走共享 `_kotlin_lexer.analyse`（嵌套深度计数 / 字符字面量 / 三引号 / 模板插值）；**解析失败 = fail-closed 报违规**，不退回原文。本门原有的第二套手写词法器 `strip_literals` 已删除 |

### downloads_atomic_update_check

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| D1 | `_downloads.compareAndSet(downloads.value, …)` 单发一次顶替 `update { }` | family-hole | `EMIT_RE` 的名字白名单是 `tryEmit\|emit\|getAndSet`，`compareAndSet` 不在里面 ⇒ 换成它后**一个判据都不跑**（连站点数都没人看） | 白名单补 `compareAndSet`；**仅当**落在 `while` 重试环里（条件内或环体内，且环内重读当前值）才算原子写，单发 CAS 与 `.value =` 同价判红；`run()` 另加**站点数下限** `BASELINE_UPDATE_SITES = 5`（走 code 视图计数，注释示例不再抬数） |
| D2 | `val flowAlias = downloads; val stale = flowAlias.value; _downloads.update { listOf(entry) + stale }` | family-hole | `READ_ALIAS_BIND` 只认一跳（`downloads = _downloads.asStateFlow()`）⇒ 别名的别名既不在接收者集合、也不进快照表 | `_read_receivers()` 取**传递闭包**（直接别名 → asStateFlow 别名 → 它们的别名，迭代到不动点）；接收者集合全程由 `_alt()` 生成正则交替串，不再写死 `_downloads` |
| D3 | `_downloads.update { current -> val _ignore = current; emptyList() }` | dataflow | `_live_body` 判「body 里出现过这个标识符」⇒ 拼写在、决定被删掉 | `_consumes()`：只认**最后一条语句（返回值）**能追到参数，允许经由 `val` 绑定一跳（`val kept = current.filter{…}; kept` 仍绿） |
| D4 | `val mut = _downloads; mut.value = downloads.value.filter { … }` | family-hole | `WRITE_RE` / `RECV_BLOCK` / `_read_receivers` 全部写死 `_downloads` 字面量 ⇒ 写点搬到别名后面即全部消失 | `_write_receivers()` = `_downloads` + 直接持有它的别名；写面 / 隐式接收者面 / CAS 面全部用派生的接收者集合构造正则 |
| D5 | `_downloads.apply { /* outer /* inner */ } */ value = value.filter { … } }` | parser-death | 手写 `_mask` 停在第一个 `*/` ⇒ 注释里的 `}` 变成真括号，apply 块提前闭合，真正的 non-atomic 写点落到被判区间之外 | `_mask` 换成共享词法器（块注释嵌套 / 字符字面量）；`check_source` 开头 fail-closed：解析失败直接报违规。扫描统一走 `code` 视图，停用标记（`ALLOWED-FULL-REPLACE`）仍从 **raw** 行取（标记本来就写在注释里） |

## ④ 判据改造细节（可直接复核的三条归一化规则）

1. **「可达」= 一切外层的分支条件都不恒假**，且其前无必然早退。
   `dead_guards_at(code, idx)` 由共享 `if_blocks` / `when_branches` 派生，恒假判断一律走 `_const_bool`；`covering_conditions` + `_early_return_before` 处理「条件恒真 ⇒ 必然早退」。
2. **「派生」= 值里至少有一个引用指向形参或局部量**（不是「有标识符」）。
   形参集合按函数 span 从发布树派生；顶层夹具（无包裹函数）退回旧判据，保证既有夹具不被误伤。
3. **「身份」= 锁对象与接收者都是同一个对象**（不是同一个名字）。
   锁：`val writeLock = Any()` 的声明形态；接收者：别名传递闭包生成的交替正则。

## ⑤ 自证臂（harness）

| 门 | 既有臂 | 本轮新增 | 合计 | 冻结标记串 |
|---|---|---|---|---|
| `trace_step_tag_check.py` | 牙1–牙8（缺字段 / 空 golden / 期望翻反 / buildLine 出锁 / beginRun 无锁 / 默认参数 lambda / 恒假分支 / 常量值） | w4牙9（`!true` + `1 == 2` 双形态）· w4牙10（`when(false)` 臂）· w4牙11（`sanitize("")` 掏空数据源）+ **牙11b 精度对照**（值真派生自形参必须绿）· w4牙12（`get() = Any()`）· w4牙13（赋值进死分支）· w4牙14（锁内 `if (true) return`）+ **牙14b 精度对照**（锁内字符字面量必须绿）· w4牙15（嵌套块注释） | 20 个断言臂 | `TRACE_STEP_TAG_SELFTEST=PASS`（未改） |
| `downloads_atomic_update_check.py` | 15 个夹具（good / bad-prepend / bad-map / bad-filter / wit-apply / wit-with / wit-stale / wit-param / wit-split / clean-update / w3-alias-snap / w3-dead-param / w3-block-comment / w3-try-emit / w3-try-emit-clean） | w4-cas-single（红）· **w4-cas-loop（CLEAN 精度对照）** · w4-alias2（红）· w4-dead-bind（红）· w4-write-alias（红）· w4-nested-comment（红） | 21 个夹具 | `SELFTEST=PASS (downloads_atomic_update: 4 fixtures)`（**冻结串一字未改**，臂数已从 4 增至 21 —— 标记是 `test_scan.py` 的子串断言，改字会红） |

## ⑥ 反攻（PRE = 修复前门 / POST = 新门）

装置：`probe/w4_09_apply.py` + `probe/w4_09_apply_dl.py`（只做字节级 apply/restore，**不 spawn 子进程**——S28 家族窗口下 `subprocess` 的 `waitpid` 会随机 ENOSYS），驱动 `probe/reattack_{trace,downloads}.sh`；每轮 = 开工 `git checkout --` heal → apply → 跑 **HEAD 版门**（PRE）→ 跑新门（POST）→ restore → sha256 与基线逐位比对。

| 见证 | PRE（旧门） | POST（新门） | 命中证据 | restore |
|---|---|---|---|---|
| T1 `!true` | rc=0（瞎） | rc=1 | `只出现在恒不执行的 if 分支里` | True |
| T2 `sanitize("")` | rc=0 | rc=1 | `字段 put("args")` 的值里除了函数调用与常量名没有任何数据引用 | True |
| T3 `get() = Any()` | rc=0 | rc=1 | `writeLock is not a stable instance field` | True |
| T4 赋值进死分支 | rc=0 | rc=1 | `beginRun assigns 'activeRunId' inside a dead branch` | True |
| T5 嵌套块注释 | rc=0 | rc=1 | `字段 put("args")` | True |
| D1 单发 CAS | rc=0 | rc=1 | `single-shot `_downloads.compareAndSet`` | True |
| D2 别名快照 | rc=0 | rc=1 | `pre-captured snapshot` | True |
| D3 死绑定 | rc=0 | rc=1 | `never uses` | True |
| D4 写别名 | rc=0 | rc=1 | `read-modify-write on _downloads` | True |
| D5 嵌套注释 | rc=0 | rc=1 | `hidden receiver` | True |

**10/10 CAUGHT，PRE 全 0（无假见证），restore 全 True**；三份生产文件 sha256 与基线一致（`AgentTraceRecorder.kt a907c856…`、`BrowserTabPool.kt 9153d495…`）。原始日志：`probe/out-reattack-{trace,downloads}.txt`。

## ⑦ 残留与登记（供主会话落 backlog）

| # | 形态 | 级别 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|---|
| R1 | `_lock_field_problems` 只认 `= Any()` / `= Object()` 初始化器；换成 `= ReentrantLock()` 之类会误红 | P2 | 真有人把 writeLock 换成其它类型 | 把初始化器白名单扩展成「非常量构造且不带 getter」，并补一条负向臂 | 当前实现是 `Any()`；判红方向安全（误红不静默） |
| R2 | `_value_failure` 只追「形参或同函数内 `val/var` 名」；跨函数派生（`put("args", helper(turn))` 且 helper 内部取数据）会误红 | P2 | 真出现跨函数取值再 put 的写法 | 加一跳调用图（同文件内 1 层）或允许「实参里出现形参」 | 当前 4 个字段全是直接派生，无此形态 |
| R3 | `_in_retry_loop` 判「while 环内含 `.value` 读」即认为合法 CAS 环，不校验 expected 是否来自该次读 | P2 | 出现「环里重读但 CAS 用环外旧值」的写法 | 追 expected 实参与环内读的绑定关系 | 真树无 CAS 用法；写错的 CAS 环编译器通常拦不住但人审能看见 |
| R4 | `BASELINE_UPDATE_SITES = 5` 是站点数下限；未来合法地减少 update 站点（合并两处）会红 | P2 | 站点数被合法下调 | 改成「每个站点都必须是 update」+ 白名单显式登记下调 | 站点数是本波唯一能抓住「整站被替换」的机械判据；下调需要人工确认 |
| R5 | `dead_guards_at` 的 `when` 臂只判「常量 subject vs 常量标签」；`when (x) { … }` 里的不可达臂不判 | P2 | 出现非常量 subject 的死臂 | 加数据流（x 的常量传播） | 与 T1 同族但需要值分析，装置比例不划算 |
| R6 | `scripts/scan/skill_restore_session_cleanup_check.py` 里仍有一套自己的 `strip_literals`（同类手写词法器） | P2 | 该门被外部见证打穿 | 迁到共享 `_kotlin_lexer` | 不在本支半径内（文件归其他支）；只登记 |
| R7 | `test_scan.py` 对 downloads 门断言的是**冻结串** `(downloads_atomic_update: 4 fixtures)`，与实际 21 个夹具不符 | P3 | 有人「修正」该串 | 同步改 `test_scan.py` 与门输出（冻结面变更需主会话统一做） | 冻结面本轮零触碰；串只是子串锚，不影响牙数 |

**无新增门**，因此 `scan.sh` / `test_scan.py` 无需注册新标记：两门的自证标记串都已存在且未改动（`TRACE_STEP_TAG_SELFTEST=PASS` / `SELFTEST=PASS (downloads_atomic_update: 4 fixtures)`）。

## ⑧ 验证证据（L1–L4）

- **L1 单门**：`trace_step_tag_check.py .` → `TRACE_STEP_TAG_CHECK: OK`（rc=0，4 类型 + 4 字段 + 两处锁归属 + golden 两个样本 + 35 条分类契约）；`--selftest` → `TRACE_STEP_TAG_SELFTEST=PASS`（20 臂）。`downloads_atomic_update_check.py .` → `PASS downloads_atomic (5 _downloads.update{ } site(s), 0 non-atomic writes, 0 stale-snapshot update lambdas)`；`--selftest` → `SELFTEST=PASS`（21 夹具，含 2 条 CLEAN 精度对照）。
- **L2 反攻**：§⑥，10/10 CAUGHT，PRE 全瞎，restore 全 True。
- **L3 冻结面**：`git diff --name-only` 只含上列两个门脚本（+ 报告）；`scan.sh` / `test_scan.py` / 共享原语 0 改动；`test_trace_step_tag()` 与 `test_da1016_gates()` 的既有夹具（含 frozen marker 断言、真树绿、空 golden、改名捕获）全绿。
- **L4 CI**：`fix/wide4-09-trace-downloads` 分支 dispatch → 见 ⑨ 回填（112 门 / 344 夹具 / ` e: ` 0 / 唯一 skipped = Publish）。
- **沙箱实测（S28 家族窗口，12 支并行）**：全量 `scan.sh` 在 1/112 处被 `[Errno 38] Function not implemented` 打断（读 `.kt` 源码失败，非门红），`/tmp` 的 cwd 标记文件也建不出来；两门单独跑与 selftest 全部正常。按 wide3-04 口径：**以 CI 单进程全量复跑为权威读数**。

## ⑨ CI

（回填：run id / job id / attempt / 结论 / 日志逐条核对）
