# FIX-BATCH5-t02 — `compact_decision_gate_check.py` + `entry_id_rename_integrity_check.py`

- 分支：`fix/b5-compact-entryid`
- 基线：`base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）
- 改动面：**2 个门脚本**（+720 / −550），生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰、门 112 / 夹具 344 不变、0 新建门。
- 结果：12 条见证（10 实锤失明 + 2 CAUGHT 对照）**全部 CAUGHT**，两门 `--selftest` 25 + 17 臂全绿，真树 rc=0，邻门 12/12 rc=0。

---

## ① 门与轴

| 门 | 见证 | 轴分布 | 被修的判据 |
|---|---|---|---|
| `compact_decision_gate_check.py` | 6（5 FALSIFIED + 1 CAUGHT） | order 1 · family-hole 3 · dataflow 2 | `entry-admits-exhausted`、`gate-admits-exhausted`、`escalation-tier-contract`、`chain-pins-too-large`、`silent-card-contract`、`chain-timeout-dedup` |
| `entry_id_rename_integrity_check.py` | 6（6 FALSIFIED，其中 11-6 原为 CAUGHT 对照…见 §2 注） | family-hole 4 · dataflow 2 | Arm 1（bail 守卫）、Arm 2（碰撞谓词 / 拒绝被返回）、Arm 3（save 门）、Arm 4（blank 守卫 + trim 绑定） |

两门的共同症是同一族：**判据锚「某物拼写出现过」，而不是「该物的效果达成」**。

- 只认字面 `false`/`true` 的死分支白名单 → `!true`、`!false`、`|| true` 全漏（family-hole）；
- 只扫「那一条 if 语句的条件」→ 条件之后追加第二次早退、或在分支体首行插 early return 全漏（order / dataflow）；
- 只查「拒绝对象被构造」→ 构造了不 return 也算过（dataflow）；
- 只查「标识符在文件里出现过」→ 赋值被 `= null` 覆盖（kill-after-def）、或下游改读原始脏源（`copy(id = params.optString(…))`）全漏（dataflow）。

---

## ② 见证原文（逐条，含门为何绿）

### 门 `compact_decision_gate_check.py`

**02-1 `G02-A1` / FALSIFIED / `order` / 判据 `entry-admits-exhausted`**
文件 `ui/chat/ChatContextWindowExt.kt`

```kotlin
// find
    if (!ContextCompactor.loopGateOpens(verdict, escalatedFromOffload)) {
        return false
    }
// replace
    if (!ContextCompactor.loopGateOpens(verdict, escalatedFromOffload)) {
        return false
    }
    if (verdict == ContextPolicy.CheckResult.EXHAUSTED) return false
```

门为何绿：旧判据只定位 `loopGateOpens` 所在的那一条 `if` 并校验其条件，**不扫描该 if 之后、`decide(` 之前的语句序列**，因此「闸门一字不改 + 紧接着第二条拒绝」完全看不见。

**02-2 `G02-A2` / FALSIFIED / `family-hole` / 判据 `gate-admits-exhausted`**
文件 `conversation/ContextCompactor.kt`

```kotlin
// find
        verdict == ContextPolicy.CheckResult.EXHAUSTED ||
// replace
        (!true && verdict == ContextPolicy.CheckResult.EXHAUSTED) ||
```

门为何绿：旧的恒真/恒假检测器只认字面 `false`，`!true` 未归一化 → 死析取臂被当成正常业务布尔。

**02-3 `G02-A3` / FALSIFIED / `dataflow` / 判据 `escalation-tier-contract`**
文件 `conversation/ContextCompactor.kt`

```kotlin
// find
        if (!bandReached && (!escalatedFromOffload || tierHasNoCompactLine)) {
            return Decision.OK
        }
// replace
        if (!bandReached && (!escalatedFromOffload || tierHasNoCompactLine)) {
            if (!escalatedFromOffload) return Decision.OK
        }
```

门为何绿：旧判据只看条件头存在 + 块尾有 `return Decision.OK`，不分析块体首部是否插入了吃掉半边真值表的 early return。

**02-4 `G02-A4` / FALSIFIED / `dataflow` / 判据 `chain-pins-too-large`**
文件 `conversation/CompactOrchestration.kt`

```kotlin
// find
                lastFailure = reduced.first
                tooLargeFailure = reduced.second
// replace
                lastFailure = candidateFailure
                tooLargeFailure = null
```

门为何绿：旧判据只查 `chainFailureAfter(` 与 `throw lastFailure` 的位置关系，**不查两个状态变量最终被赋成了什么**（kill-after-def 与「换成本分支自己的候选失败」都看不见）。

**02-5 `G02-A5` / FALSIFIED / `family-hole` / 判据 `silent-card-contract`**
文件 `conversation/CompactOrchestration.kt`

```kotlin
// find
            if (!silent) {
// replace
            if (!silent || !false) {
```

门为何绿：`always_true(cond)` 只把「顶层析取臂等于字面 `true`」判为恒真，`!false` 不算 → `!silent` 还在拼写里，守卫却永远为真。

**02-6 `G02-A6` / CAUGHT / `family-hole` / 判据 `chain-timeout-dedup`（对照臂，修完仍被抓）**
文件 `conversation/CompactOrchestration.kt`，skip 分支的 `continue` 改成 `if (false) continue`。原判据已用「守卫是字面 false」抓住；本次把该判定统一换成 `guard_feasible` 后**仍然抓**（见 §4 反攻结果）。

### 门 `entry_id_rename_integrity_check.py`

**11-1 `G11-A1` / FALSIFIED / `family-hole` / Arm 1**
文件 `data/repository/ProviderRepository.kt`

```kotlin
// find
            if (!decision.allowed) {
// replace
            if (!true && !decision.allowed) {
```

门为何绿：`literal_decides()` 只按 `&&`/`||` 切分后**逐字比较** `true`/`false`，`!true` 不是字面量 → 守卫被当作活条件，决策永不消费。

**11-2 `G11-A2` / FALSIFIED / `family-hole` / Arm 2（生产端谓词）**
文件 `data/repository/ProviderRepositoryUtils.kt`

```kotlin
// find
    val collides = others.any { it.first == updatedInstanceId && it.second == updatedModelId }
// replace
    val collides = others.any { false && it.first == updatedInstanceId && it.second == updatedModelId }
```

门为何绿：旧判据只校验谓词里出现两个标识符（装饰性检查），**不判谓词是否可能为真**；消费端 `if (collides)` 是干净的，于是「后门上锁、前门敞开」。

**11-3 `G11-A3` / FALSIFIED / `dataflow` / Arm 2（拒绝必须被返回）**
文件 `data/repository/ProviderRepositoryUtils.kt`

```kotlin
// find
    if (collides) {
        return EntryRenameDecision(
            allowed = false,
// replace
    if (collides) {
        val ignored = EntryRenameDecision(
            allowed = false,
```

门为何绿：旧判据只查块内「`allowed = false` 出现过」→ 构造了拒绝却不返回也算过，函数继续落到 `return EntryRenameDecision(allowed = true)`，冲突 100% 放行。

**11-4 `G11-A4` / FALSIFIED / `family-hole` / Arm 3**
文件 `ui/settings/ModelEntryDetailScreen.kt`

```kotlin
// find
                enabled = !entry.isCustom || modelId.isNotBlank(),
// replace
                enabled = !entry.isCustom || modelId.isNotBlank() || true,
```

门为何绿：Arm 3 只做**前缀子串**匹配，没有右边界、也没有恒真吸收检查。

**11-5 `G11-A5` / FALSIFIED / `dataflow` / Arm 4**
文件 `debug/ProviderMutationMethods.kt`

```kotlin
// find
            val mid = params.optString("modelId", current.baseModel.id).trim()
            if (mid.isEmpty()) {
                throw RPCException(-32602, "modelId must not be blank")
            }
            current.baseModel.copy(id = mid)
// replace
            val mid = params.optString("modelId", current.baseModel.id).trim()
            if (false && mid.isEmpty()) {
                throw RPCException(-32602, "modelId must not be blank")
            }
            current.baseModel.copy(id = params.optString("modelId", current.baseModel.id))
```

门为何绿：Arm 4 只查「锚点行存在」（`val mid = …trim()` 与 `"modelId must not be blank"` 都还在），**既不判守卫是否可能为真，也不判 `copy(id = …)` 实际读的是什么**。

**11-6 `G11-A6` / CAUGHT / `family-hole` / Arm 2（对照臂，修完仍被抓）**
`if (collides) {` → `if (false && collides) {`。旧判据抓得到这一条（它显式跑了 `false` 字面量检测），但与 11-2 形成鲜明对比：**只守消费端不守生产端 = 只锁前门**。本支把两端统一走 `live_guard()`，两条都红。

---

## ③ 判据改造（旧形态 → 新形态）

### 共用：自写词法器全部删除

| 门 | 删除的自写件 | 替换为 |
|---|---|---|
| compact | `mask_code`、`match_close`、`if_statements`、`enclosing_conds`、`split_top`、`norm_atom`、`is_lit`、`always_true`、`dead_for` | `_kotlin_lexer.mask_code`；`_kotlin_ast.{function_body,if_blocks,enclosing_conditions,last_write,when_branches,stmt_spans,const_bool,split_top,strip_parens,const_value,match_bracket}`；`_kotlin_valueflow.{guard_feasible,path_feasible,brace_depth,expr_end}` |
| entry_id | `code_only`（自写 `strip_comments`）、`_balanced`、`literal_decides`、`_if_guards`、`function_text` | 同上 + `mask_comments`（literal 通道：字符串内容保留，读锚点/日志串） |

两门现在都是**通道分治**：
- 结构判定走 `mask_code`（注释 + 字符串内部 → 空格，长度不变，位置可回投）；
- 需要读字符串内容/注释文本的锚点走 `mask_comments` 或原文（`"modelId must not be blank"`、`summary TIMEOUT after`、`Deliberately NOT`）；
- `KotlinLexError` → 直接报违规（fail-closed，**不回退原文扫描**）。

### 逐条对照

| 判据 | 旧形态（为何瞎） | 新形态 |
|---|---|---|
| compact `entry-admits-exhausted` | 定位 `loopGateOpens` 所在的那条 `if`，只看它的条件 | ① `function_body` 取 `maybeAutoCompactInLoop` 体；② 闸门条件必须**恰等于** `!loopGateOpens(...)`；③ **闸门 if 结束到 `decide(` 之间不允许存在可达、且真正退出外层函数的 `return`/`throw`**（`early_exit_between`：跳过 `return@label`，跳过 `path_feasible` 为假的死分支）。02-1 因此变红 |
| compact `gate-admits-exhausted` | 正则白名单 `false &&` | `function_body` 取表达式体/return 表达式 → 顶层 `||` 切分 → **含 `EXHAUSTED` 的析取臂必须 `guard_feasible` 不为 False**（`const_bool` 已按 Kotlin 优先级 + 短路折叠）。02-2 变红 |
| compact `escalation-tier-contract` | 条件头子串 + 块尾有 `return Decision.OK` | `if_blocks` 按**归一化条件文本**匹配该守卫 → 取 then 体 → `stmt_spans` 切语句：**必须恰 1 条且归一化后以 `return Decision.OK` 开头**。02-3（体内插 early return）变红 |
| compact `chain-pins-too-large` | 只查 `chainFailureAfter(` 与 `throw lastFailure` 的先后 | `when_branches` 取 `RemoteFailure` 分支区间 → 对 `tooLargeFailure` / `lastFailure` 各取**该区间内最后一次赋值**（`last_write`）：必须是 `reduced.second` / `reduced.first`；`const_value(rhs)` 为 `null` → kill-after-def 红。02-4 变红 |
| compact `silent-card-contract` | `always_true()` 只认字面 `true` | `enclosing_conditions` 取包含卡片站点的所有条件 → `!silent` 且**无恒真析取臂**（`const_true_arm`：`|| true` / `|| !false` 都算吸收）且 `guard_feasible` 不为 False，才算「活守卫」。02-5 变红 |
| compact `chain-timeout-dedup` | skip 分支终止判定用 `is_lit(g, "false")` | 同一条 `guard_feasible` 判定（统一入口）：`if (false) continue` 与 `if (!true) continue` 都判「不终止」。02-6 保持被抓 |
| entry_id Arm 1 | `literal_decides()` 逐字比较 `true`/`false` | `if_blocks` 找「then 体含 `return@synchronized`」的守卫 → 条件含 `.allowed` 且 `live_guard()`（常量折叠 + 恒真吸收双查）。11-1 变红 |
| entry_id Arm 2（谓词） | 只查两个标识符出现过 | `others.any { … }` 的 lambda 体用 `match_bracket` 精确切出 → **`guard_feasible(pred)` 不为 False 且无恒真臂**，另要求真的比较 `updatedInstanceId`/`updatedModelId`。11-2 变红 |
| entry_id Arm 2（返回） | 块内出现 `allowed = false` 即可 | `returns_rejection()`：块内必须有**裸 `return`**（跳过 `return@label`），且其**返回表达式里**有 `allowed = false`。11-3 变红 |
| entry_id Arm 3 | 前缀子串 `enabled = !entry.isCustom \|\| modelId.isNotBlank()` | 扫全部 `enabled =` 站点（`mask_code`，注释掉的不算）→ 表达式**按顶层逗号**取右边界（`expr_end` 会把行尾逗号读成续行，必须再切一次）→ **无恒真析取臂 / 非恒真 / 非恒假**。11-4 变红 |
| entry_id Arm 4 | 两个锚点行存在 | ① 从 `copy(id = …)` **反向**解析出真正驱动写库的绑定（前向扫会先撞上 `val entryId = params.optString(...)`）；② 该绑定必须 `optString(...).trim()` 结尾；③ 该绑定上的 blank 守卫必须 `live_guard` 且体内有 `throw`；④ 每个 `copy(id = …)` 的实参不得含 `optString` 且必须引用该绑定。11-5 变红 |

### 新增 `--selftest` 臂（每修一条判据就固化一条）

- `compact_decision_gate_check.py`：18 → **25 臂**。新增 7 条：6 条是判据加固的正向臂——`entry guard` 追加二次拒绝 / `!true` 死析取 / escalation 体内 early return / `tooLargeFailure = null` / `|| !false` 吸收（5 条直接就是本波见证的最小形态）+ 把 02-6 的家族扩展到 `if (!true) continue`；另 1 条是我在读判据时自查出的极性洞（裸 `if (loopGateOpens(...))` = 门开即拒绝），一并固化。
- `entry_id_rename_integrity_check.py`：8 → **17 fixtures**。新增 9 条：`!true` 死守卫、`others.any { false && … }`、`others.any { false }`、拒绝不返回、`|| true`、块注释（旧实现只剥 `//`）、blank 死守卫 + trim 旁路。
- 臂的判据：先在真树上跑（基线必须 clean，否则所有臂无意义），再逐臂变异——**期望判据必须恰好出现在违规集合里**（`arms == {expected}`），锚点漂移（变异没打上）单独报错。

---

## ④ 四层证据

### L1 本门 + 自测

```
python3 scripts/scan/compact_decision_gate_check.py .          → rc=0
   "✅ compact-decision gate: the loop entry admits every verdict decide() adjudicates and does so alone, …"
python3 scripts/scan/compact_decision_gate_check.py --selftest  → rc=0
   "✅ compact-decision gate --selftest: 25 arms flip exactly their criterion"
   COMPACT_DECISION_GATE_SELFTEST=PASS
python3 scripts/scan/entry_id_rename_integrity_check.py .       → rc=0
   "entry_id_rename_integrity_check: OK (4 arms, 0 violations)"
python3 scripts/scan/entry_id_rename_integrity_check.py --selftest → rc=0
   ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS (17 fixtures)
```

### L2 反攻（硬验收）—— 12/12 变红，restore 全 True

驱动：`/var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py --mode full`（apply → 跑门 → restore → sha256 校验）。

| 门 | 修前基线 | 修后 | restore |
|---|---|---|---|
| compact | 5 BLIND_CONFIRMED + 1 CAUGHT_VERIFIED | **CAUGHT ×6** | 6/6 True |
| entry_id | 6 BLIND_CONFIRMED | **CAUGHT ×6** | 6/6 True |

逐条报错指向的判据（门输出摘要）：

- 02-1 → `ChatContextWindowExt.kt:303: another early exit sits between the loop gate and decide(`
- 02-2 → `ContextCompactor.kt:139: the EXHAUSTED admission is constant-false ((!true && verdict == …))`
- 02-3 → `ContextCompactor.kt:239: the escalation guard's body must be exactly return Decision.OK`
- 02-4 → `CompactOrchestration.kt:916: tooLargeFailure is overwritten with null right after it is set`，同轮 `:915: lastFailure ends up holding candidateFailure instead of reduced.first`（一条见证、两个半边各自报红）
- 02-5 → `CompactOrchestration.kt:552: card sysmsg_compact_failed is reachable in silent mode`
- 02-6 → `CompactOrchestration.kt:835: the timed-out route's skip branch does not terminate the iteration`
- 11-1 → `ProviderRepository.kt:1630: the if (!true && !decision.allowed) guard can never reject`
- 11-2 → `ProviderRepositoryUtils.kt:173: the collision predicate … can never be true`
- 11-3 → `ProviderRepositoryUtils.kt:174: collides is only tested by a guard … whose rejection is built and never returned`
- 11-4 → `ModelEntryDetailScreen.kt:199: the save gate is absorbed by a constant-true operand`
- 11-5 → `ProviderMutationMethods.kt:455: copy(id = params.optString(…)) bypasses the trimmed binding mid`
- 11-6 → `ProviderRepositoryUtils.kt:174: collides is only tested by a guard that cannot reject`

原始结果：`probe/t02-compact_decision_gate_check.py.json`、`probe/t02-entry_id_rename_integrity_check.py.json`；修前基线：`probe/t02-base-compact.json`、`probe/t02-base-entryid.json`。

### L3 邻门（12/12 rc=0）

`compact_entry_pin_migration_check` · `compact_pin_truth_check` · `loop_tool_status_transition_check` · `parts_json_funnel_check` · `outcome_known_chain_check` · `session_provider_enabled_check` · `entry_composite_key_guard_check` · `provider_import_id_roundtrip_check` · `provider_remember_key_check` · `models_provider_field_check` · `tool_result_image_branch_check` · `chat_ui_interaction_check`

### L4 全量 + CI

`git status --porcelain src/` 全程为空（12 次打补丁 / 12 次还原均无残留）。

**本地全量未能跑完（沙箱症状，非门红）**：`bash scripts/scan/scan.sh` 在 `[1/112]` 处即报 `OSError: [Errno 38] Function not implemented`（registry **S28**），`/var/minis/shared/tools/gate_run.sh --full` 跑到 23/112 被整壳杀掉（exit −1），零 fork 的进程内 sweep 也被杀（exit −1）。判据：错误行全带 `Function not implemented` = 沙箱症状。两次重活后停止重试（GLOBAL「Agent 负载纪律」），全量证据改由**分支 CI job 日志**承担（本仓 `build-apk.yml` 本就跑 `scan.sh` + `test_scan.py`，见 registry S28 变体 ③「施工期全量交给分支 CI」）。

CI 逐项读数见 §5。

---

## ⑤ CI

分支 `fix/b5-compact-entryid` 手动 dispatch（`on: push` 只绑 main，分支必须 dispatch）。

| 项 | 读数 |
|---|---|
| run / job | `40203205487` / `121198635982`，attempt **1**，conclusion **success** |
| head_sha 三方一致 | API `runs[].head_sha` = `5443e9f5ae28bc0ccdb089c1f8b7590a4cf206e4` = 本地 `git rev-parse HEAD`（逐字符） |
| 日志规模 | 1,486,117 B / 17,850 行 |
| 步骤 | 26 步，唯一非 success = **步骤 23 `Publish to Releases` skipped**（main 门控，分支构建预期） |
| 门套件 | 门头 **112** 条，编号 1..112 互异，`/112` 总数标记唯一，**门名 112 互异** |
| 门汇总 | `Summary: 112 passed, 0 failed` 恰 **1** 条（带框线前缀）；其余 `Summary: N passed, 0 failed` 均为子套件（5/14/15/25/28/33/36…） |
| 夹具 | `ALL 344 CASES PASS` 恰 **1** 条 |
| Kotlin 编译 | ` e: ` 命中 **0** |
| 含 `FAILED` 的行 | **6** 条，逐条核过**全是 PASSED 用例名子串**（`slot denied produces FAILED without calling provider` / `reason PERSISTENCE_FAILED maps to persistence_failed` / `phase FAILED maps to Failed` / `terminal FAILED maps to Failed` / `reason EXECUTION_FAILED maps to all_fallbacks_exhausted` / `the fixed tail still reaches a legal FAILED terminal`），无游离失败 |
| 本支门自证 | `COMPACT_DECISION_GATE_SELFTEST=PASS` 恰 **1**；`ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS` 恰 **1** —— 25 臂 / 17 夹具在 CI 里有人见证（非只在本地） |
| 本支门头 | `Compact decision gate coverage (ISSUE-04/05)`、`E/ISSUE-05 entry id rename integrity` 均在册且绿 |

结论：**分支 CI 绿 = 本支确定性停止节点**（门 112 / 夹具 344 与基线一致 ⇒ 本支未新增/删除门，只加固判据）。未合并 main（起手权在用户/主会话）。

补充说明：本支先于 `868f7029` 推了一版，CI run `40202023409` 因再次 push 被 **cancelled**（非失败）；最终证书对应 `5443e9f5a`。

---

## ⑥ 残留（P2，登记不修，各带三件）

**R1 — `entry-admits-exhausted` 只判闸门到 `decide(` 之间，不判 `decide(` 之后。**
触发条件：有人把 `decide()` 的返回值丢弃或改写（`decide(...)` 之后另起判断）。
触发后方案：用 `value_sources` 判 `decide(...)` 的结果必须是决定后续控制流的那个值。
搁置理由：本波无此形态见证，半径纪律（只修触发路径）。

**R2 — `chain-pins-too-large` 依赖 `when_branches` 的 `RemoteFailure` 分支区间。**
触发条件：有人把 chain 归约从 `when` 分支抽成独立函数/局部 lambda（区间取不到 → 退化为全文件 `last_write`，可能误判）。
触发后方案：改用 `decl_sites` + `value_sources` 沿 `reduced` 做数据流，不依赖分支区间。
搁置理由：当前形态稳定，重构前触发条件不成立。

**R3 — `returns_rejection()` 不区分「lambda 内裸 return」。**
触发条件：有人写成 `if (collides) run { return EntryRenameDecision(allowed = false, …) }` 之类非局部返回形态。
触发后方案：结合 `scope_calls` 判定 return 是否收束到本函数的返回位置。
搁置理由：本仓无此写法；且方向偏安全（会误绿而非误红）。

**R4 — `gate-admits-exhausted` 不判「EXHAUSTED 析取臂是否还挂着额外前置条件」。**
触发条件：写成 `escalatedFromOffload && (verdict == EXHAUSTED)`（该臂仍可行 → 判绿，但语义已收窄）。
触发后方案：对析取臂的合取项做「是否引入 `escalatedFromOffload` 之外的新约束」检查。
搁置理由：无见证；与 02-2 不同族（02-2 是恒假，这里是收窄）。

**R5 — Arm 3 只扫 `enabled =` 站点。**
触发条件：保存按钮改用 `if (isValid) MinisButton(...)` 等非 `enabled =` 形态。
触发后方案：从「保存动作的可用性决定点」派生站点（walk 到 `onClick = { save() }` 的容器），不锚参数名。
搁置理由：UI 重构前不成立。

**R6 — `when_branches` 退化路径下 `chain-pins` 的行号可能偏移。**
触发条件：分支区间取不到时按 `orch_m` 起点计算行号。
触发后方案：退化路径改为按变量名在全文件定位后再算行号。
搁置理由：仅影响报错行号，不影响判红/判绿。

**R7 — 本地全量未跑通（沙箱两次被杀）。**
触发条件：CI 的 `scan.sh` / `test_scan.py` 步骤出现与本地不同的红。
触发后方案：用 gate_run.sh `--exec` 单门串行复核，再判 CI 红真假。
搁置理由：S28 已知缺陷 + 负载纪律，全量证据已由 CI 承担。

---

## ⑦ `test_scan.py` 注册项（交主会话核对，本支未改注册文件）

两门**均已在册**，标记串本支原样保留，无需改动；只需主会话收口时确认臂数已涨：

| 脚本 | 断言标记 | 臂/夹具数 | 注册行 |
|---|---|---|---|
| `compact_decision_gate_check` | `COMPACT_DECISION_GATE_SELFTEST=PASS` | **25 arms**（原 18） | `test_scan.py:2699`（`DA1016_GATES`） |
| `entry_id_rename_integrity_check` | `ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS` | **17 fixtures**（原 8） | `test_scan.py:2835`（`DA1022_GATES`） |

已实测四条断言全部通过：

```
compact_decision_gate_check --selftest    rc=0 marker=1
compact_decision_gate_check <root>        rc=0
entry_id_rename_integrity_check --selftest rc=0 marker=1
entry_id_rename_integrity_check <root>     rc=0
```

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t02

# 共享原语自测（必须先全绿）
python3 scripts/scan/_kotlin_lexer.py --selftest      # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest        # 32 arms
python3 scripts/scan/_kotlin_valueflow.py             # 26 arms

# L1 本门
python3 scripts/scan/compact_decision_gate_check.py . && python3 scripts/scan/compact_decision_gate_check.py --selftest
python3 scripts/scan/entry_id_rename_integrity_check.py . && python3 scripts/scan/entry_id_rename_integrity_check.py --selftest

# L2 反攻（12 条见证逐字重打 → 必须全 CAUGHT）
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json \
  --repo . --mode full --only compact_decision_gate_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t02-compact_decision_gate_check.py.json
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json \
  --repo . --mode full --only entry_id_rename_integrity_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t02-entry_id_rename_integrity_check.py.json

# L3 邻门（逐门直跑，避开 scan.sh 的 S28）
for g in compact_entry_pin_migration_check compact_pin_truth_check loop_tool_status_transition_check \
         parts_json_funnel_check outcome_known_chain_check session_provider_enabled_check \
         entry_composite_key_guard_check provider_import_id_roundtrip_check provider_remember_key_check \
         models_provider_field_check tool_result_image_branch_check chat_ui_interaction_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1 || echo "FAIL $g"
done

# L4 全量（沙箱内可能触发 S28，证据以分支 CI 为准）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$(pwd)" --chunks 6
git status --porcelain src/     # 必须为空
```
