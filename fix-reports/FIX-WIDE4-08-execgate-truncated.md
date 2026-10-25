# FIX-WIDE4-08 — exec_gate_release_check / truncated_call_guard_check 见证判据加固

- 基线：`base/wide4-shared` = `dbab7a3e`（`19e70dab` + 两个共享原语 `_kotlin_lexer.py` / `_kotlin_ast.py`）
- 分支：`fix/wide4-08-execgate-truncated`（工作树 `meta-wide4/wt-08`）
- 改动面：`scripts/scan/exec_gate_release_check.py`（+491/−84）· `scripts/scan/truncated_call_guard_check.py`（+314/−70）
- **生产代码 0 行改动**；`scan.sh` / `test_scan.py` 冻结面**零触碰**；门数仍 112、夹具仍 344
- 自证臂：exec gate 4 → **13**；truncated gate 26 → **32**（冻结标记串 `TRUNCATED_CALL_GUARD_SELFTEST=PASS` 一字未改，只加臂）

## §1 门与轴

| 门 | 见证数 | 盲轴分布 | 修复前机械复证 | 修复后反攻 |
|---|---|---|---|---|
| `exec_gate_release_check.py` | 6（5 盲 + 1 已挡） | family-hole ×4 · parser-death ×1（+1 已挡 family-hole） | 5 × `GATE_BLIND_CONFIRMED`（rc=0） | **6/6 CAUGHT**（rc=1，restore=True） |
| `truncated_call_guard_check.py` | 6（5 盲 + 1 已挡） | family-hole ×4 · parser-death ×1（+1 已挡 dataflow） | 5 × `GATE_BLIND_CONFIRMED`（rc=0） | **6/6 CAUGHT**（rc=1，restore=True） |

两门同一族失效，但**根因是两个不同的「存在 ≠ 生效」**：

- exec gate：判据锚的是**位置的包含关系**（`pos` 在 span 内 / `count(x)==1` / 常量折叠见字面量就算常量），所以外层的死条件、嵌套未求值的 lambda、`(1==2) &&` 这种「非常量外壳 + 常量结果」全部穿过去。
- truncated gate：判据锚的是**拼写的存在**（条件里出现过两个词 / 实参文本等于 `turnTruncated` / 返回 null 的 `if` 里有单源），所以 `when` 臂、同名遮蔽、`if (false) x else 0`、raw string 全部穿过去。

## §2 每条见证：原补丁 → 旧判据为何瞎 → 新判据 → 新臂名

### 2.1 `exec_gate_release_check.py`

| # | 原见证（判据 / 轴） | 原补丁 | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| A1 | `catch_blocks / enclosing_if / constant_truth` (G4) · family-hole | 回滚语句改成 `when (false) { true -> if (heavyGateHeld) heavyGate.release() else -> Unit }` | `enclosing_if()` 只返回**最内层** `if`；最内层 `if (heavyGateHeld)` 是真的未知条件 ⇒ 回滚算「活着」。外层 `when` 的 subject 常量根本没人看 | `covering_conditions()` 收集**全链**条件（if then/else 取反 + when 的 subject 与臂），`condition_dead()` 折叠：if 折叠条件本身；when 连 **subject** 一起折叠，臂常量与 subject 不匹配即死 | `w4-when-false rollback hides a live inner if` |
| A2 | `constant_truth (G5b)` · family-hole | 实参 `isOnMainThread = (1 == 2) && (Looper.myLooper() == Looper.getMainLooper())` | `constant_truth()` 只折叠**纯字面量**；表达式里出现 `Looper` 标识符就返回 `None`（「未知」）⇒ 恒假的实参被当成活决策 | 归一化后**正向白名单**：实参必须精确等于 caller-thread 比较本身（`Looper.myLooper()==Looper.getMainLooper()` 或对称写法），其它一律红 | `w4-dispatch arg derived from a constant comparison` |
| A3 | `lambda_after_call` (G2b) · family-hole | `val _unused = { heavyGate.tryAcquire(...) }; true`（acquire 仍在 lambda 内） | 判据是 `bs <= pos < be` 的**区间包含**：acquire 确实在 lambda 里，只是在一个没人调用的嵌套 lambda 里 | `lambda_tail_holds()`：acquire 必须是 `runInterruptible` 块的**自身结果**（深度 0 的尾语句），且块的实参必须归一化为 `Dispatchers.IO`（新增 `MARK_IO`） | `w4-acquire parked in an unevaluated nested lambda` / `w4-interruptible block on Dispatchers.Main` |
| A4 | 主线程回收分发 (G5/G5b) · family-hole | ①`DEFERRED -> postRecycleMemoryRecoveryNow()` + `INLINE -> recoveryScope.launch {…}`（两支动作互换）②`INLINE -> if (false) { launch } else postRecycleMemoryRecoveryNow()` | 判据只问「wrapper 文本里出现过 `recoveryScope.launch`」——`when` 的**哪一支**持有 launch 从来不看；死分支同理 | 按 `when_branches` 解析出 `RecycleDispatch.DEFERRED` / `INLINE` 两支并与动作**配对**（DEFERRED 必须以 `recoveryScope.launch` 开头且含同步函数；INLINE 必须有同步函数且不得出现 launch）；解析不出即 fail-closed | `w4-when arms swap their actions` / `w4-launch parked in the INLINE arm` |
| A5 | `mask / catch_blocks` (G4) · **parser-death** | 用 `val _open = '"'; if (true) throw t; val _close = '"'` 把「提前 rethrow」藏在假字符串里 | 门自带的手写扫描器不认单引号字符字面量：`'"'` 被当成字符串起点，吞掉中间整段真代码；嵌套块注释、raw string 同理 | 词法统一到共享 `_kotlin_lexer`（单引号 / 嵌套注释 / raw string / 模板插值）；解析失败 → `MARK_LEX` **fail-closed**（新增） | `w4-char literal '\"' no longer swallows the early throw` |
| A6 | `enclosing_if / constant_truth` (G4) · 已挡 | `if (false && heavyGateHeld) heavyGate.release()` | —（修复前就抓到） | 保持：全链条件折叠仍判死（外加「回滚缺失」） | 仍为 `CAUGHT` |

**新增判据 G5c**（A4 的补面，原门完全没有）：`recycleDispatchMode` 的 body 必须真在 `isOnMainThread` 上分支（不得常量短路、不得换成别的表达式），且 then/else 必须分别映射 `RecycleDispatch.DEFERRED` / `INLINE`。臂 `w4-decision function pinned to a constant`。

### 2.2 `truncated_call_guard_check.py`

| # | 原见证（判据 / 轴） | 原补丁 | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| B1 | `policy_single_source_returns (C1)` · family-hole | 在真条件后追加 `when { !ceilingDeclared -> return null }` | `if_bodies()` 只扫 `\bif\s*\(`；`when` 没有 `if (` ⇒ 该臂对判据不可见，而两种拒绝措辞与条件仍在 | ①`rejectionReason` 内 `return null` **必须恰好 1 处**；②该处条件归一化后必须 ∈ `{!ceilingDeclared&&!turnTruncated, 对称}`（堵 `when`、重言式 `x \|\| !x`、常量前缀 `!false &&`） | `w4-policy adds a 'when'-keyed single-source return` |
| B2 | `operative_call / engine-feeds-flag (C2)` · family-hole | 生产者一字不改，改**消费者**：`if (false && truncatedRefusal != null) {` | C2 只 `operative_call()` 校验生产者那一行 + 三个实参；`AgentLoopEngine` 里消费分支**存在与否、活不活**从没被检查（C4 至少还有 `C4_REFUSAL_GATE in loop` 的子串检查） | `refusal_branch_live()`：必须存在 `if (truncatedRefusal != null)` 分支、条件未被常量短路、分支体仍含 `continue`（跳过工具执行） | `w4-engine refusal branch short-circuited by a constant` |
| B3 | `operative_call / subagent-refusal-wired (C4)` · family-hole | 在调用前插一行 `val turnTruncated = false`（同名遮蔽） | `rargs[2] != "turnTruncated"` 锁的是**实参拼写**；拼写没变，变的是这个名字在当前作用域指向的值 | `flag_shadowed()`：`turnTruncated` / `turnFinishReason` 在读取点之前只允许**一次**声明（流回调写的那次）；第二次同名声明即红 | `w4-subagent shadows the stream flag before the refusal call` |
| B4 | `feed_calls / is_constant_expr (C8/C10)` · family-hole | `chatCompletionsToolCallEventWeight(if (false) toolCalls.length() else 0)` | `is_constant_expr()` 只剥字面量再看剩余，`iftoolCalls.lengthelse` 非空 ⇒ 判「非常量」，放行。`toolCalls.length() * 0` 同族 | `dead_weight_arg()` = 空 / 纯字面量 / **含分支 token**（`if`·`when`·`else`·`?:`）/ **零倍乘**（`x * 0`、`0.coerceAtMost(x)`）四者之一即红 | `w4-openai feeds the EOF counter through a dead branch` / `w4-openai feeds the EOF counter a zero-scaled weight` |
| B5 | `mask_code / policy_single_source_returns` · **parser-death** | `val _open = """"/*"""` + `if (!ceilingDeclared) return null` + `val _close = """*/""""` | 手写 `mask_code` 逐字符翻 `quote`，不认三引号 raw string：第 4 个引号即「闭合」，raw string 里的 `/*` 打开了一段**真实存在的**假块注释，把中间的 `return null` 抹成空格 | 词法统一到共享 `_kotlin_lexer`（raw string 按「末尾连续引号取最后 3 个作定界」）；八个目标文件先整体 `analyse()`，失败即 `unparseable-kotlin` fail-closed | `w4-policy hides a second return null inside raw strings` |
| B6 | `operative_call / subagent-refusal-wired (C4)` · 已挡 | 第 3 个实参写成 `false && turnTruncated` | —（修复前就抓到） | 保持：`rargs[2]` 不等于 `turnTruncated` 即红 | 仍为 `CAUGHT` |

**新增 fail-closed**：`check()` 开头对 8 个目标文件逐个 `analyse()`，任一失败 → 只报 `unparseable-kotlin` 并返回（不在自己不信的词法面上给绿）。副作用：一条历史臂（"subagent loop hand-writes the wording"）原先拼的是**非法 Kotlin**（未闭合字符串），现被 fail-closed 抢先命中，已把该臂改成合法的 `"...[TRUNCATED] " + "..."` 拼接，臂的语义与期望判据不变。

## §3 判据改动清单（函数名级）

`scripts/scan/exec_gate_release_check.py`

| 函数 | 变更 |
|---|---|
| `mask()` | 改为调用共享 `_kotlin_lexer.analyse()` 的 `code` 视图；失败记入 `LEX_FAILURES` 并返回原文（由 `main()` 转成 `MARK_LEX` 违规） |
| `covering_conditions(text, idx)` | **新增**：全链条件（`if` then/else 取反 + `when` 的 subject/臂），基于共享 `if_blocks` / `when_branches` |
| `condition_dead(entry)` | **新增**：if 折叠条件；when 折叠 subject + 臂（`else` 视为可取；臂常量与 subject 不匹配即死） |
| `interruptible_lambda(text, call_idx)` | **新增**：返回 `(实参文本, lambda 起, lambda 止)` |
| `lambda_tail_holds(body, needle)` | **新增**：目标调用必须是 lambda 内部**深度 0 的尾语句**，且形如一次调用表达式 |
| `main()` G2b/G3 | 用 `interruptible_lambda` + `lambda_tail_holds` 替换原来的「区间包含」；新增 `MARK_IO`（实参归一化必须 `Dispatchers.IO`）；global wrapper 同一判据 |
| `main()` G4 | 用 `covering_conditions`/`condition_dead` 替换 `enclosing_if` + `constant_truth` |
| `main()` G5b | 用 `normalize_expr` + `DISPATCH_ARG_OK` 白名单替换常量折叠 |
| `main()` G5 新增段 | when 两支与动作配对；`recycleDispatchMode` body 活性 + DEFERRED/INLINE 映射（G5c） |
| `selftest_fixture()` / `selftest()` | 新增 9 个形状 + 9 条臂（含已挡形状的回归臂） |

`scripts/scan/truncated_call_guard_check.py`

| 函数 | 变更 |
|---|---|
| `strip_kotlin_comments()` / `mask_code()` | 改为共享 `_kotlin_lexer` 的 `comments` / `code` 视图；失败记 `LEX_FAILURES` |
| `check()` 开头 | **新增** 8 文件整体词法体检，失败 → `unparseable-kotlin` 并 fail-closed |
| `policy_single_source_returns()` | **新增** 「`return null` 恰好一处」计数 + 条件归一化白名单 `C1_CONDITION_OK` |
| `refusal_branch_live(code, var)` | **新增**：消费分支必须存在 / 未被常量短路 / 分支体仍含 `continue` |
| `flag_shadowed(code, names, before)` | **新增**：流式标志在读取点前只允许一次声明 |
| `arg_is_branchy()` / `arg_is_zero_scaled()` / `dead_weight_arg()` | **新增**：权重实参的四类「恒零」形状 |
| C2 / C4 / C8 / C10 调用点 | 接上以上判据；C1/C2/C4 的失败文案对齐任务书要求的固定片段 |
| `selftest()` | 新增 6 条 `w4-` 臂；历史 "hand-writes the wording" 臂改成合法 Kotlin |

## §4 四层证据

**L1 本门（真树 + 自证臂）**

```
python3 scripts/scan/exec_gate_release_check.py      .  → rc=0
python3 scripts/scan/exec_gate_release_check.py      --selftest → EXEC_GATE_SELFTEST=PASS (13 arms)
python3 scripts/scan/truncated_call_guard_check.py   .  → rc=0
python3 scripts/scan/truncated_call_guard_check.py   --selftest
     → ✅ 32 arms flip exactly their criterion + TRUNCATED_CALL_GUARD_SELFTEST=PASS
```
自证臂要求的是「**恰好**翻自己的判据」：`fired != expected` 即失败，所以一条臂顺带翻别的判据会被抓出来。新增 9 + 6 条臂逐条在本轮都被看到翻红（含已挡形状的回归臂，防止加固过程中把旧牙磨掉）。

**L2 反攻复验（本支最重要的一层）**

装置 `meta-wide4/probe/reattack.py`：**分层驱动**——python 只做字节级 apply / restore / sha256 复核，**不 spawn 子进程**；门用 `runpy` 在同进程内跑（跑完即弃）。这是 S28 家族窗口（`waitpid`/`open` 随机 ENOSYS）下的健康路径。

```
cd /var/minis/shared/work/meta-wide4
python3 -u probe/reattack.py --gates exec_gate_release_check.py,truncated_call_guard_check.py \
        --json logs/reattack-08b.json
→ CAUGHT=12    RESULT: ALL CAUGHT      （STILL_BLIND=0 · RESTORE_FAIL=0 · FIND_NOT_FOUND=0）
```
- 10 条原 `GATE_BLIND_CONFIRMED` 见证：`rc=0 → rc=1`（真翻），且**翻红理由就是任务书指定的那条判据**（逐条核对 `logs/reattack-08.json` 的 finding 行）：
  - A1/A2/A4/A6 → `[exec-gate] decision short-circuited by a constant …`（`when (false) { true -> … }` / `(1 == 2) && …` / `if (false && heavyGateHeld)`）
  - A3 → `[exec-gate] blocking acquire … nested block/lambda`
  - A5 → `[exec-gate] heavy permit rollback missing: the catch rethrows BEFORE releasing the heavy permit`
  - B1/B5 → `[policy-single-source-early-return] … decides on ONE truncation source`
  - B2 → `[engine-feeds-flag] … the engine refusal branch (if (truncatedRefusal != null)) is missing or short-circuited`
  - B3 → `[subagent-refusal-wired] … turnTruncated is shadowed or overwritten with a constant`
  - B4 → `[openai-count-derived] … is fed through chatCompletionsToolCallEventWeight(…)`
- 2 条原 `CAUGHT_VERIFIED` 见证（A6 / B6）：仍 `rc=1`，没有因为改写判据而放走。
- 12/12 `restored=True`（sha256 逐字节一致），工作树反攻后 `git status --porcelain` 仅本支两文件。

**L3 邻门（只读）**：引用本支目标文件的 18 道门全部 `rc=0`——`anthropic_thinking_budget_check` · `browser_tabs_lifecycle_check` · `compact_pin_truth_check` · `debug_leak_guard` · `log_url_redaction_guard` · `loop_persist_slice_check` · `loop_tool_status_transition_check` · `marker_boundary_check` · `memory_gate_provider_fields_check` · `outcome_known_chain_check` · `provider_ua_parity_check` · `router_lock_check` · `subagent_handback_check` · `sysinfo_state_thread_check` · `thinking_wiring_guard` · `tool_result_image_branch_check` · `tool_title_error_branch_check` · `turn_tail_finalize_order`。
另：`test_scan.py::test_exec_gate` 的 8 条历史夹具**同进程**复跑全绿（clean / bare_acquire / no_interruptible / no_rollback / no_dispatch / no_settle / unwired / missing_file）。

**L4 全量（CI 权威）**：本地 `scan.sh` 与 `test_scan.py` 在本轮并发窗口内**无法跑完**——两轮均在 `python3: Function not implemented` / `OSError: [Errno 38]`（`waitpid`、`open`、甚至 `tail`）处中断，属已知沙箱缺陷（`shared/sandbox-limits.md` S28 家族），**不是门红**。全量读数以 CI 单进程复跑为准，见 §5。

## §5 CI

- 分支：`fix/wide4-08-execgate-truncated`
- 提交：`63e4f883`（判据加固）→ `395630e6`（文案对齐 + 臂复刻）→ 报告回填提交（纯报告）
- CI：**run #2666 / id `40179472268` / job `121127277347`**，attempt **1**，`conclusion=success`，`head_sha = 395630e677569473b6904ae2f252b22f54d36afb`（与本地 `git rev-parse HEAD` **逐字符一致**），日志 1,481,263 B / 17,848 行
- 判据行（逐条核日志）：

| 判据 | 读数 |
|---|---|
| `║  Summary: 112 passed, 0 failed` | **1** 条（其余 `Summary: N passed` 前缀是子套件：28 / 5 / 14 / 36 / 15 / 25 …） |
| `ALL 344 CASES PASS` | **1** 条 |
| ` e: ` | **0** 条 |
| 门头 `━━━ [N/112] … ━━━` | **112** 个、**互异**、编号 1..112 **连续无缺** |
| 26 步 | 唯一非 success 步 = `Publish to Releases`（**skipped**，main 门控） |
| 含 `FAILED` 的行 | 全部是**用例名子串**且行尾 `PASSED`（`FAILED` 结尾例外 = 0） |
| 本支两门 | `exec_gate_release_check` 真树 rc=0 行在册（`:1407`）；`truncated_call_guard_check selftest has teeth (TRUNCATED_CALL_GUARD_SELFTEST=PASS)`（`:1232`）+ real tree green（`:1233`） |
| `EXEC_GATE_SELFTEST` | **0** 命中 —— 该门尚未在 `test_scan.py` 注册标记断言（见 §7），非门红 |

报告回填提交后：`git diff --name-only 395630e6..HEAD -- . ':(exclude)fix-reports'` = **0 行**（源码树与 CI 绿树逐字节等价）。

## §6 残留（每条：触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `refusal_branch_live()` 要求引擎拒绝分支体里**直接**出现 `continue` | 有人把拒绝逻辑抽成 helper（分支体只剩一次调用） | 判据改成「分支体内出现 `continue` 或对结束本次迭代的 helper 的调用」，helper 按函数体认定 | 当前是内联实现；为尚不存在的重构写判据 = 装置超出影响面 |
| R2 | `flag_shadowed()` 在 `AgentLoopEngine` 上是**整文件**统计（`before` = 调用点位置） | 引擎里出现**另一个函数**的局部 `turnTruncated`/`turnFinishReason` | 把统计面收窄到「读取点所在的函数体」（需要跨函数体定位，成本高于当前收益） | 当前全文各恰好 1 处；收窄属于精度优化，不是安全缺口（多报只会红，不会漏） |
| R3 | exec gate 的 `when_branches` 要求每条臂落在**自己的顶层换行段**内 | 有人的 `when` 某支写成多行 | 自建按缩进的 arm 解析；当前行为是 fail-closed 红（`cannot identify … arms`），不会静默放行 | 两处使用点都是单行臂；fail-closed 已经是安全方向 |
| R4 | `arg_is_branchy()` 禁止权重实参出现 `if`/`when`/`else`/`?:` | 未来出现**合法的**条件权重（例如按事件类型加权） | 改为「实参的每个分支都必须引用数据源、且不能是常量」的语义判定 | 现有 5 个权重函数都是线性表达式；放宽会重开 B4 那个洞 |
| R5 | `lambda_tail_holds()` 要求目标调用是 lambda 的**最后**一条语句 | 包装块里加了「调用之后的收尾语句」（如日志） | 允许「尾语句是目标调用」或「尾语句块内以目标调用为唯一副作用」；需要先在设计上确认调用仍是最后副作用 | 当前两处包装体内只有一次调用；先保持严格 |
| R6 | `DISPATCH_ARG_OK` 只白名单两种拼写 | 实参改成 helper（`isOnMainThread = isCallerOnMainThread()`） | 白名单增补 helper 名，或在 helper 上进行一次「归一化到同一比较」的判据（同文件一跳委托） | 这一处刻意设计为「任何形态变更都是门事件」——4c 的失效正是靠更换形态 |
| R7 | `unparseable-kotlin` fail-closed 会**短路**同一次运行里的其它判据 | 目标文件出现真实语法错误/未闭合字面量 | 先修文件；词法错误存在时其它判据的读数没有意义 | 这是刻意的：在不信的词法面上给绿比红更危险 |
| R8 | 本地全量 `scan.sh` / `test_scan.py` 未能在本窗口跑完 | 沙箱并发窗口结束（12 支同跑）后 | 补跑一次本地全量（112 / 344），与 CI 互相印证 | 本轮 CI 单进程复跑已覆盖同一批门与夹具；本地复跑不改变结论，只增加证据密度 |
| R9 | 门的 `--selftest` stdout **不进 CI 日志**（除非在 `test_scan.py` 注册标记断言） | 主会话并集收口 | 按 §7 注册两条（exec gate 目前**未注册**） | 冻结面归主会话统一改；本支不碰 `test_scan.py` |

## §7 注册项（主会话并集收口时据此注册，逐字）

```
| 门脚本 | selftest 标记串（--selftest 的收尾行，逐字） | 臂数 |
|---|---|---|
| exec_gate_release_check | EXEC_GATE_SELFTEST=PASS | 13 |
| truncated_call_guard_check | TRUNCATED_CALL_GUARD_SELFTEST=PASS | 32 |
```

- `exec_gate_release_check` **当前未在 `test_scan.py` 注册 selftest 标记断言**（只有「真树 rc=0」与 8 条夹具），建议补：
  `("exec_gate_release_check", "EXEC_GATE_SELFTEST=PASS", (REPO_ROOT,))`
- `truncated_call_guard_check` 已注册 `TRUNCATED_CALL_GUARD_SELFTEST=PASS`（**冻结串，一字未改**），臂数 26 → 32，注册行无需改动。

## §8 复现步骤

```sh
cd /var/minis/shared/work/meta-wide4/wt-08   # 分支 fix/wide4-08-execgate-truncated

# 1) 本门 + 自证臂
python3 scripts/scan/exec_gate_release_check.py    .   ; echo rc=$?
python3 scripts/scan/exec_gate_release_check.py    --selftest | tail -1
python3 scripts/scan/truncated_call_guard_check.py .   ; echo rc=$?
python3 scripts/scan/truncated_call_guard_check.py --selftest | tail -2

# 2) 反攻（把外部见证逐条打回新判据；必须 ALL CAUGHT）
cd /var/minis/shared/work/meta-wide4
python3 -u probe/reattack.py --gates exec_gate_release_check.py,truncated_call_guard_check.py \
        --json logs/reattack-08b.json

# 3) 邻门（只读）
for g in anthropic_thinking_budget_check browser_tabs_lifecycle_check \
         compact_pin_truth_check debug_leak_guard log_url_redaction_guard \
         loop_persist_slice_check loop_tool_status_transition_check marker_boundary_check \
         memory_gate_provider_fields_check outcome_known_chain_check \
         provider_ua_parity_check router_lock_check subagent_handback_check \
         sysinfo_state_thread_check thinking_wiring_guard \
         tool_result_image_branch_check tool_title_error_branch_check \
         turn_tail_finalize_order; do
  python3 scripts/scan/${g}.py . >/dev/null 2>&1; echo "$g rc=$?"
done

# 4) 全量（CI 权威；并发高峰下沙箱会随机 ENOSYS，分块重跑）
sh scripts/scan/scan.sh
python3 scripts/scan/test_scan.py

# 5) CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
   --workflow build-apk.yml --ref fix/wide4-08-execgate-truncated
curl -s https://rikka-ci-bridge.logos7313.workers.dev/status/fix/wide4-08-execgate-truncated
```
