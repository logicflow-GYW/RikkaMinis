# FIX-R3-t09 — loop_persist_slice_check + loop_tool_status_transition_check

> 第三轮（dual-gate bypass）门见证收口 · 支线 t09
> 分支 `fix/r3-loop-persist`，基线 `main` @ `f0f14d46d`

## 1. 门

| 门 | 文件 | 行数（前 → 后） | 见证 |
|---|---|---|---|
| `loop_persist_slice_check` | `scripts/scan/loop_persist_slice_check.py` | 840 → 1091（+273/−6） | #121 #122 #123 |
| `loop_tool_status_transition_check` | `scripts/scan/loop_tool_status_transition_check.py` | 950 → 1139（+219/−1） | #124 #125 #126 |

改动面只有这两个门脚本：生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 零触碰、门 112 / 夹具 344 不变（`git diff --numstat` 见 §5.1）。

## 2. 轴

六条见证全部落在同一族失效上：**判据锚「某物存在」，不锚「效果达成」或「决定真的发生」**，具体分三轴。

| 轴 | 见证 | 旧判据为什么瞎 |
|---|---|---|
| **dual-gate seam**（缝上缺陷） | #121 #122 #124 #125 | 两门各自守着自己那一半，缝上没人守：<br>#121 门只审「游标推进在 success guard 内」，不审「既有行被刷新」→ 删掉 refresh 调用后 `assistantDbId` 仍非空、游标照推、门全绿；<br>#122 门只审切片起点（`buildTurnParts` 实参），不审「落库的内容容器被填过」→ 传 `emptyList()` 时 host 侧 `if (resultParts.isEmpty()) return null` 静默跳过落库；<br>#124 门只审「进入 RUNNING」（T2/T5）与「拒绝要发布」（T1/T8），不审「从 RUNNING 出来」→ Pass 3 把 success 映射回 RUNNING，状态机永不终结；<br>#125 T1 把「CRITICAL 分支必须发布」钉在**那一条具名分支**上，兄弟拒绝路径（preflight）删掉发布后无人发现 |
| **symmetric axis**（守卫只应用在一侧） | #123 #126 | #123 门用 `turnStartBlockIndex` 审了 rollback 与可见性两处**使用**，没审**初始化右值**：`= 0` 时 rollback 会擦掉之前所有轮次的块；#126 门审了调用点（T3）与兄弟策略函数体（T5/T6），没审 `sequentialQueueCueIndices` **自己的函数体**：`= emptyList()` 让等待提示永远到不了卡片 |
| **parser-death / family-hole** | 各轴的反例臂 | 追加的判据全部走共享原语（`function_body` / `if_blocks` / `when_branches` / `call_args` / `expr_end` / `const_bool` / `enclosing_conditions`），解析失败 fail-closed 报红，不回退成原文子串扫描 |

## 3. 见证重放表

重放方式：在**工作树**上手工构造等价最小编辑（`probe/t09-w*.patch`），跑该见证 `gates_involved` 的每一道门，记录 rc，还原后校验 sha256（`probe/t09_replay.py`）。

| # | 类型 | 补丁实质 | pre rc（LP / LTS） | pre 判决 | post rc（LP / LTS） | post 判决 | 红在哪条判据 |
|---|---|---|---|---|---|---|---|
| 121 | dual-gate | 删 `host.updatePersistedAssistantTurn(earlyId, turnParts, blockMeta)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT | L3993 `the if (earlyId != null) branch returns the early row without calling updatePersistedAssistantTurn(…)` |
| 122 | dual-gate | `persistToolResultMessage(resultParts, …)` → `(emptyList(), …)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT | L4016 `the tool-result persist is handed 'emptyList()' …` |
| 123 | symmetric | `turnStartBlockIndex` 初始化 `= loopState.allToolBlocks.size` → `= 0` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT | L812 `the per-turn baseline is initialised to '0' …` |
| 124 | dual-gate | Pass 3 `result.success -> SUCCESS` → `RUNNING` | 0 / 0 | **BLIND_CONFIRMED** | 0 / **1** | CAUGHT | L3924 `a finished tool is mapped back to ToolBlockStatus.RUNNING …`（附 L3923 `no result.success -> …SUCCESS branch`） |
| 125 | dual-gate | 删 preflight 拒绝分支的 `withContext(Main){ updateAssistantMessage(…) }` | 0 / 0 | **BLIND_CONFIRMED** | 0 / **1** | CAUGHT | L3685 `a Pass-1 refusal marks the block FAILED but never publishes it before its terminator` |
| 126 | symmetric | `sequentialQueueCueIndices` 体 → `emptyList()` | 0 / 0 | **BLIND_CONFIRMED** | 0 / **1** | CAUGHT | L315 `sequentialQueueCueIndices no longer decides which calls are queued — its body `emptyList()` has no branching if …` |

- 6/6 BLIND_CONFIRMED（无 CAUGHT、无 NOT_REPRODUCIBLE、无假见证），restore 6/6 `sha256` 一致（`probe/t09-pre.json` / `probe/t09-post.json` 的 `restored: true`），事后 `git status --porcelain` 只剩两个门脚本。
- **dual-gate 归属说明**：#121/#122 修在 `loop_persist`（持久化内容与游标），`loop_tool_status` 不管这条缝；#124/#125 修在 `loop_tool_status`（状态机与发布），`loop_persist` 不管。两门都红做不到，也不该做——把状态映射塞进持久化门会把职责搅在一起，报告在此明确归属。**每条缝都有且只有一门负责，没有缝落在两门之间没人管。**

## 4. 修法

### 4.1 `loop_persist_slice_check` 新增 R11 / R12 / R13

| 规则 | 旧（锚存在） | 新（锚效果/决定） |
|---|---|---|
| **R11** 行路由 | 只查 `if (assistantDbId != null) { 游标推进 }` | 从树派生 `val assistantDbId = if (<early> != null) {…} else {…}`：①then 分支必须调用 `updatePersistedAssistantTurn(<early>, <slice>, …)`（实参 0 必须等于条件里的 early 名，实参 1 必须是可跟的标识符）；②then 分支**求值**必须等于 early 名（`tail_expr`，否则 dbMessageId 会锚错行）；③else 分支必须调用 `persistAssistantTurn(…)`；④**没有 if 路由** = 每轮插新行 → 也红 |
| **R12** 工具结果落库 | 只查切片起点 `buildTurnParts(allToolBlocks, unpersistedBlockStart)` | `persistToolResultMessage(<args>)` 的实参 0 必须是一个**在本调用之前被填过**的容器（`+=` / `.add(` / `.addAll(` 至少一次，且最后一次填充之后没有 `clear()` / 重新赋空）；`emptyList()` / `listOf()` 等常量空集合直接点名；非标识符形态 fail-closed |
| **R13** 每轮基准 | R2/R3 只审 `turnStartBlockIndex` 的**使用** | 每处 `val/var turnStartBlockIndex = …` 的右值必须恰为 `loopState.allToolBlocks.size`；声明消失 → 红（fail-closed） |

配套修了本门自己的一个既有缺陷：`_receiver_of` 原来把**整个文件前缀** squash 成一行再取尾标识符，跨行时得到 `sizeloopState`（多语句夹具因此完全逃过 R8）。现在只取紧邻的接收者链，并支持 `(loopState).x` 的括号包裹形态。这条是被新增夹具当场逼出来的（见 §5.3 自伤 1）。

### 4.2 `loop_tool_status_transition_check` 新增 T7 / T8 / T9

| 规则 | 旧（锚存在） | 新（锚效果/决定） |
|---|---|---|
| **T7** Pass-3 终态 | 没有任何判据读 `finalStatus` | 解析 `val finalStatus = when { … }`：①每个分支值必须是 `ToolBlockStatus` 常量（fail-closed）；②**任何分支映射回 RUNNING/PENDING 即红**（状态机没有出口）；③必须有 `success → SUCCESS`、`timedOut → TIMEOUT`、`else → FAILED` 三条；④分支条件恒假（`!true -> …` / `false -> …`）即红 |
| **T8** 拒绝必须发布 | T1 只钉 `if (precheck.level == Level.CRITICAL) {` 这一个**具名**分支 | 从树派生：Pass-1 区域里**每一处** `ToolBlockStatus.FAILED` 写入，向上找「最内层、且在该写入之后会终止（continue/break/return/throw）的 if」＝它的拒绝分支；该分支内必须有**在终止符之前**、且不被常量假守卫包住的 `host.updateAssistantMessage(`。兄弟拒绝路径新增/删空都逃不掉 |
| **T9** 提示下标策略体 | T3 只查调用点与字面量，T5/T6 只查兄弟策略函数体 | `sequentialQueueCueIndices` 的函数体必须**决定**：必须有分支（`policy_if`）、条件必须读参数并做比较、条件不能恒真/恒假、两分支值不能相同、且**恰一支是空集合、另一支从参数派生范围**（`(1 until pendingCount).toList()`） |

### 4.3 自证臂（只增不减）

| 门 | 前 | 后 | 新增臂 |
|---|---|---|---|
| `loop_persist_slice_check` | 21 violating + 3 clean = 24 | **27 violating + 3 clean = 30** | `early-refresh-dropped`（#121）、`early-refresh-wrong-row`（R11 实参身份）、`tool-result-empty-container`（#122）、`tool-result-never-filled`（R12 家族洞）、`per-turn-baseline-zero`（#123）、`per-turn-baseline-stale`（R13 错误但像样的种子） |
| `loop_tool_status_transition_check` | 24 | **30** | `r3_pass3_success_running`（#124）、`r3_pass3_success_dead`（T7 恒假分支）、`r3_preflight_push_gone`（#125）、`r3_preflight_push_after_continue`（T8 顺序：发布在终止符之后不算）、`r3_cue_indices_body_empty`（#126）、`r3_cue_indices_both_empty`（T9 分支同值） |

稳定收尾标记（供主会话注册）：
- `LOOP_PERSIST_SLICE_SELFTEST=PASS (27 violating + 3 clean arms)`
- `LOOP_TOOL_STATUS_SELFTEST=PASS (30 arms)`

`SELFTEST=PASS loop_persist_slice_check (6 violating fixtures red, compliant fixture green)` 这一 legacy 行**原样保留**（`test_scan.py:2695` 逐字断言它，且它仍然为真——原始六条夹具全部仍红）。

## 5. 四层证据

### 5.1 改动面（冻结面零触碰）

```
$ git diff --numstat
273  6  scripts/scan/loop_persist_slice_check.py
219  1  scripts/scan/loop_tool_status_transition_check.py
$ git status --porcelain          # 只有上面两个文件（报告另行提交）
 M scripts/scan/loop_persist_slice_check.py
 M scripts/scan/loop_tool_status_transition_check.py
$ git diff --name-only -- scripts/scan/scan.sh scripts/scan/test_scan.py \
      scripts/scan/_kotlin_lexer.py scripts/scan/_kotlin_ast.py \
      scripts/scan/_kotlin_valueflow.py 'src/**'
(empty)
```

### 5.2 L1 本门

```
$ python3 scripts/scan/loop_persist_slice_check.py --selftest | tail -2
violating fixtures red: 27 / 27 ; clean fixtures: 3
LOOP_PERSIST_SLICE_SELFTEST=PASS (27 violating + 3 clean arms)
$ python3 scripts/scan/loop_persist_slice_check.py .
PASS loop_persist_slice_check (…)            rc=0

$ python3 scripts/scan/loop_tool_status_transition_check.py --selftest | tail -2
LOOP_TOOL_STATUS_SELFTEST=PASS (30 arms)
$ python3 scripts/scan/loop_tool_status_transition_check.py .
  [OK] T1 … T9 (9 criteria)   PASS loop_tool_status_transition (…)   rc=0
```

### 5.3 L2 邻门

语义相邻门（同扫 `AgentLoopEngine.kt` 的五门）逐个 rc=0：
`compact_pin_truth_check` / `router_lock_check` / `sysinfo_state_thread_check` / `truncated_call_guard_check` / `turn_tail_finalize_order` → 5/5 rc=0。

**本波自伤 2 条（都被自己的夹具当场抓到，可复用）**：
1. `_receiver_of` 的整前缀 squash：新增 `val turnStartBlockIndex = …` 后，`tainted-advance` 与 `paren-receiver` 两条老夹具同时变绿——跨行把上一行粘成了 `sizeloopState`。修法 = 只取紧邻接收者链 + 剥括号包裹。**教训：新夹具的前置行会改变「按前缀切片」型实现的行为**，加 fixture 前置代码后必须整跑旧臂。
2. `when_branches` 的切片起点必须是 `when` 关键字而不是 `{`：传 `{…}` 切片时 `WHEN_RE` 一条分支都匹配不到，T7 对真树直接误红。**教训：共享解析器按关键字驱动，切片要给关键字。**

### 5.4 L3 全量（逐门独立进程；`scan.sh` 整份与 `test_scan.py` 整跑在沙箱被杀，见 §5.5）

```
$ # 109 个 *_check.py / *_guard.py，逐门独立进程（nice），--root 回退形态
gates 109  logged 109  missing 0   FAIL 0
```
日志 `probe/t09-scan.log`（109 行，全 `OK` / `OKroot`；`calendar_events_nullable_predicate_check` 与 `tts_voice_and_sanitizer_check` 用 `--root .` 形态）。

### 5.5 沙箱事实（本波第 N 次同族，如实登记）

- `bash/sh scan.sh` 整份、`test_scan.py` 整跑（`nice python3 … > log`，0 行输出后进程消失）在本窗口被沙箱杀掉（exit −1，历史 S28 `Errno 38 / Function not implemented` 同族）。已改用「逐门独立进程 + 落盘日志」的健康路径完成 L3；`ALL 344` 夹具读数交分支 CI（CI job 原生跑 `scan.sh` + `test_scan.py`），本报告不拿「门红」污名环境失败。
- 典型症状：一次 35 门连跑被杀、一次 15 门连跑被杀、一次 4 门（`97..103` 之后）被杀；同一批门分批 10–15 门则全部 rc=0 ⇒ 环境层，不是判据。

### 5.6 L2' 修后反攻（post-fix）

见 §3 的 post 列：6/6 CAUGHT、restore 6/6、每条红在自己的判据上（`probe/t09-post.json` 的 `fails` 字段逐条贴了门自己的报错行）。

## 6. CI

- 分支 `fix/r3-loop-persist`，HEAD（门提交）`__HEAD__`
- run `__RUN__` / job `__JOB__` / attempt `__ATTEMPT__` → **`__CONCLUSION__`**
- `head_sha` 三方核对：本地 `git rev-parse HEAD` = `__HEAD__`，API `/git/ref/heads/fix/r3-loop-persist` = `__REMOTE__`，run.head_sha = `__CIHEAD__`（逐字符一致 `__SAME__`）
- 关键日志行：`Summary: 112 passed, 0 failed` 恰 __SUM__ 条 · `ALL 344 CASES PASS` 恰 __ALL__ 条 · `^ e: ` __ERR__ 条 · 门头 112 个互异 · 唯一 skipped = Publish（main 门控）· `LOOP_PERSIST_SLICE_SELFTEST=PASS` / `LOOP_TOOL_STATUS_SELFTEST=PASS` 各 __MARK__ 次
- 说明：`test_scan.py` 只回显 ✅ 行，门自己的 `--selftest` stdout 不进 CI 日志；新臂的见证由本报告 §4.3 承载（同族已登记为 P2 残留）。

## 7. 残留（每条三件：触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | T7 只认 `val finalStatus = when { … }`（subjectless）。`.copy(toolStatus = …)` 之外的形态（`when (result)` / `when (result.success)`）会解析不出分支值 → 误红 | `finalStatus` 被重构成 subject-when | 在 `when_branches` 的 `subject` 上分支：subject 形态走「分支 cond 是 `is/else` + 值仍是 ToolBlockStatus」判据 | 当前是**误报面**（fail-closed 红，不放行）；真树只有一种形态；改它要先扩共享 `when_branches` 的语义（冻结面） |
| R2 | T8 只把 `ToolBlockStatus.FAILED` 当拒绝写入；若拒绝分支用 `TIMEOUT`/`PENDING` 表达 | 新增拒绝分支不用 FAILED | 把写入枚举扩成「同一分支内 `copy(toolStatus = X)` 的 X ∉ {RUNNING}」并逐个判发布 | 现网三条拒绝路径全用 FAILED；扩枚举会与 T2（Pass-1 不得出现 RUNNING）产生交叉判定，需一并设计 |
| R3 | T8 的「发布」只认 `host.updateAssistantMessage(`；若改用别的 host 方法发布 | 新增发布 API | 用「host. 上的 public 方法名集合」派生发布面，或显式白名单 + fail-closed | 现网只有这一个发布入口；白名单会过期（与「拼写白名单」同族） |
| R4 | R11 只跟一跳绑定（`val earlyId = earlyAssistantDbId`）；`earlyAssistantDbId?.let { … }` 形态 → cond 不匹配 → 误红 | early 名改成安全调用形态 | cond 解析扩 `\?\.let` 形态：绑定名 = `it` | 误报面；真树是一跳 `val` |
| R5 | R12 的 `_filled_container` 按名字在同一文件内找填充，不跨函数（helper 填充 → 误红） | `resultParts` 改由 helper 填充 | 用 `value_sources` 做一跳回绑（`val parts = buildX()` → 追 `parts` 的填充） | 现网填充点都在同函数；跨函数值流需要给 `_kotlin_valueflow` 扩局部绑定解析（已知缺口，本波又一次撞到） |
| R6 | R13 只认 `val/var turnStartBlockIndex =` 声明形态；`by` 委托 / 属性包装 → 找不到声明 → 误红 | 基准点改成委托属性 | 认 `by` 声明 + 取 delegate 初始化器 | 误报面；R7（同类）已拒绝 accessor 形态，方向一致 |
| R7 | T9 的 `policy_if` 只解析 if/else 二分支；改成 `when` → 误红 | 策略体改 `when (pendingCount)` | 并行加 `when_branches` 分支判定（同一判据的两种拼写） | 误报面；T5/T6 已有同一限制（`policy_if` 共用） |
| R8 | 新臂计数不进 CI 日志（`test_scan` 只断言 marker 子串） | 需要 CI 侧看见 30 臂 | 让门在 `--selftest` 通过时把臂数写进 marker（已做）+ 主会话在 `test_scan.py` 注册 `(30 arms)` 的更严断言 | `test_scan.py` 是冻结面，注册归主会话 |
| R9 | `#124` 的跨门双保险未做（`loop_persist` 不读终态映射） | 出现「状态被写进 turnParts 后又被持久化门忽略」的第三类缝 | 在 `loop_persist` 加「turnParts 里的 tool 块状态必须终态」判据 | 职责边界：持久化门审内容与游标，状态机门审跃迁；跨域耦合会把两门变成一门 |

## 8. 复现命令

```sh
cd <worktree>            # /var/minis/shared/work/meta-audit-v5/round3b/wt/t09

# 1) 见证重放（pre / post 同一脚本，自动应用→跑两门→还原）
python3 probe/t09_replay.py --out probe/t09-pre.json
python3 probe/t09_replay.py --out probe/t09-post.json
python3 -c "import json;[print(r['pid'],r['verdict'],r['gate_rc'],r['restored']) for r in json.load(open('probe/t09-post.json'))]"

# 2) 两门自测 + 真树
python3 scripts/scan/loop_persist_slice_check.py --selftest | tail -2
python3 scripts/scan/loop_persist_slice_check.py .
python3 scripts/scan/loop_tool_status_transition_check.py --selftest | tail -2
python3 scripts/scan/loop_tool_status_transition_check.py .

# 3) 全量（逐门独立进程；scan.sh 整份在沙箱会被杀）
sh probe/t09_slice.sh 1 15     # 直到 109；结果落 probe/t09-scan.log
python3 -c "
gates=[l.strip().split('/')[-1] for l in open('probe/t09-gates.txt') if l.strip()]
log=[l.split()[-1] for l in open('probe/t09-scan.log') if l.strip()]
print('missing', [g for g in gates if g not in log])"

# 4) CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --branch fix/r3-loop-persist
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/r3-loop-persist
```
