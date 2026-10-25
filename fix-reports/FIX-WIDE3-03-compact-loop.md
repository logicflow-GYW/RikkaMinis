# FIX-WIDE3-03 · compact-loop — 4 道门的「决定真的生效」加固

- 分支：`fix/meta-wide3-03-compact-loop`
- 基线：`6b6c918fae6c338e3091d0d3870af3cad9e2b881`（main）
- 改动面：**只有 4 个门脚本**（`scripts/scan/{compact_pin_truth_check,compact_decision_gate_check,loop_tool_status_transition_check,loop_counter_reset_guard}.py`）。生产 `.kt` 零改动、`scan.sh`/`test_scan.py` 零触碰、其他支的门零触碰。
- 外部见证：14 条，修复前 **14/14 STILL_BLIND（rc=0）**，修复后 **14/14 CAUGHT（rc=1）**。

---

## ① 门与判据

### 1. `compact_pin_truth_check.py`（4 条判据）

| 判据 | 断言（新） |
|---|---|
| `pin-dedup` | `extractPinnedUserMessages` 的**采集阶段**（`for (msg in messages)` 循环体内）每一条汇入结果的文本都必须经过**同消息去重载体**（`LinkedHashSet`/`HashSet`/`mutableSetOf`/`.toSet()`/`.distinct()`）。**载体必须被消费**（`texts.addAll(seen)` 或 add 直接打在集合上）；只在函数里声明一个 `seenInMessage` 不计数。循环外（上限裁剪 `recent.add(0, texts[i])`）不属于该判据的扫描面。 |
| `pin-both-sources` | 采集体内必须同时读 `msg.content` **和** `contentParts` 里 `AgentContentPart.Text` 的文本，**且该分支是活的**：该 `add` 的每一层 `if` 条件都不能被字面量短路（`false && …`）、`AgentContentPart.Text` 不能落在被 `false` 杀死的析取支里。 |
| `pin-collector-intact` |（新判据，接住 parser-death 见证）采集结果必须活到 `return`：体内必须有 `return`；最后一次采集之后不许出现 `texts.clear()/removeAll/retainAll`，也不许把结果变量重新赋成新的空容器。 |
| `single-truth-source` | `contentParts.any/all { … ToolResult … }` 的 lambda **按自己的花括号**取体，体内出现 `ToolResult` 且最近的外层 `fun` 不是 `isToolResultOnly` → 违规。多行 lambda 与一行写法同等判定。 |

### 2. `compact_decision_gate_check.py`（4 条判据）

| 判据 | 断言（新） |
|---|---|
| `entry-admits-exhausted` | 入口守卫**必须整体等于** `!ContextCompactor.loopGateOpens(...)`（归一化空白后逐字符比对）；多一个 `||`/`&&` 操作数就报红——多出来的操作数可以把 EXHAUSTED 在 `decide()` 之前再拒一次。 |
| `gate-admits-exhausted` | 在**代码投影**（注释/字符串已抹白）里读 `loopGateOpens` 的函数体，`EXHAUSTED` 必须出现在**活**的析取支里（`|| (false && verdict == EXHAUSTED)` 判红）。 |
| `chain-timeout-dedup` | 已超时路由的 skip 分支必须**终结本次迭代**：按花括号取块体，块内必须存在 `continue/break/return/throw`，且不能整体落在 `if (false)` 之下。只打印一行 "skip candidate" 然后照发 → 红。 |
| `silent-card-contract` | compactAll 的每张卡片必须被**包在**一个活的 `if (!silent)` 里（`enclosing` 判据，看代码投影）；`// if (!silent)`（注释掉）与「窗口里别处有一个 `if (!silent)`」（旧实现）都不算。`sysmsg_compact_anchor_id_missing` 这张保持不静音的卡片，必须在它之前出现书面保留判据。 |

### 3. `loop_tool_status_transition_check.py`（6 条判据，本次加固 T1/T3/T5/T6）

| 判据 | 断言（新） |
|---|---|
| `T1` | CRITICAL 拒绝分支的边界由**花括号配平**给出（不再用「第一行恰好是 `continue`」）；分支内必须有**活的**终结语句；UI 推送必须**至少有一次是活的**（被 `if (false)` 包住的推送不算发布）。 |
| `T2` | Pass-1 区域不得出现 `ToolBlockStatus.RUNNING`（不变）。 |
| `T3` | 串行 cue 必须由 `queueCueIndices` 驱动、`cueBlock.copy(...)` 发布、`PENDING` 守卫**是活的**（token 位于守卫**条件**里也要判；`false && …` 判红）、cue 字面量单点（不变 + 活性）。 |
| `T4` | cue → flip → execute 顺序（不变）。 |
| `T5` | 两个调用点都走 `statusAtDispatch(` **且** 该函数体必须真的对 `PENDING` 分支（体里出现 `PENDING`、`RUNNING` 与 `if|when`）——无条件 `= RUNNING` 会把 Pass 1 已判 FAILED 的块复活成转圈。 |
| `T6` | `contentAtDispatchFlip(` 的函数体必须引用**它的实参**并含分支（`= current` 直通 → cue 文本残留并被当成该工具的输出持久化）。 |

### 4. `loop_counter_reset_guard.py`（1 条判据，3 个面）

| 面 | 断言（新） |
|---|---|
| 候选识别 | `loopState.<name> = <字面量表达式>` 的右值按**表达式**读（`0 + 0`、`(0)`、一行两条语句都算重置）；「这句是 tool-call 语义」的声明来自**块级注释走读**：向上走过空行、兄弟重置与普通语句，遇到任何含 `{`/`}` 的行即止（插一条 `Unit` 不能把声明与语句脱钩）。 |
| 守卫有效性 | 正向守卫 `if (toolCalls.isNotEmpty())` / `if (!toolCalls.isEmpty())` / `.any()` / `size > 0`，且**条件不能被字面量短路**（`true || …`、`false && …`、`isEmpty() || isNotEmpty()` 都不算守卫）。 |
| fail-closed | 整棵树没有任何 `.kt` 提到 `toolCalls` → 扫描面为空 → 直接判红（不再静默 0 违规）。 |

---

## ② 失效轴

| 轴 | 条数 | 本支的形态 |
|---|---|---|
| `dataflow` | 8 | 调用/声明在，**决定没了**：去重集合声明但不消费；`false && part is Text`；`if (!gate ∥ verdict == EXHAUSTED)`；skip 分支只打印不 `continue`；`if (false) withContext { publish }`；`false && cueBlock.toolStatus != PENDING`；`statusAtDispatch = RUNNING`；`true || toolCalls.isNotEmpty()` |
| `parser-death` | 4 | 门自己的解析器被打死：函数体用列 0 `}` 结束 → 少看 `texts.clear()`；`// if (!silent)` 让卡片判据在原文里仍命中；CRITICAL 分支用「正则找 continue 行」被 `if (true) continue` 推过界；`= 0 + 0` 让重置正则整行不匹配 |
| `anchor` | 2 | 只认一行/一个子串：多行 `contentParts.any {` 的 lambda 体在下一行 → 看不到 `ToolResult`；注释块与语句之间插一条 `Unit` → 候选识别失效 |

---

## ③ 逐条见证（criterion / 轴 / 旧判据为何瞎 / 新判据形态）

| # | 门 | criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|---|
| 1 | compact_pin_truth | `pin-dedup` | dataflow | 只 `need` 了「函数体里出现过 `LinkedHashSet`/`distinct(`」——集合声明了但文本直接进结果列表也绿 | 采集阶段每个 `add/addAll` 的目标必须是去重载体，或 `addAll` 的实参引用载体；载体不存在且无列表级 `distinct()` → 红 |
| 2 | compact_pin_truth | `pin-both-sources` | dataflow | 只查 `msg.content` 与 `AgentContentPart.Text` 两个**子串**存在 | `contentParts` 循环体内每次 `add` 的每一层 `if` 条件都要活（`always_false` / 死在 `false` 支里 → 红）；且至少要有一条活的 add |
| 3 | compact_pin_truth | `single-truth-source` | anchor | 逐行扫：`contentParts.any {` 那行没 `ToolResult` 就放过（lambda 体在下一行） | 从 `{` 配平取 lambda 体再判词；外层 `fun` 由**向前最近的 `fun` 声明**给出 |
| 4 | compact_pin_truth | `pin-collector-found` | parser-death | `extract_function_body` 用「列 0 的 `}`」结束函数体，循环的 `}` 在列 0 时函数体被截断，`texts.clear()` 落在体外 | 函数体 = 形参右括号之后 `{` 的花括号配平；另加 `pin-collector-intact` 判「结果活到 return」 |
| 5 | compact_decision | `entry-admits-exhausted` | dataflow | 只 `need(loopGateOpens(…))`，调用后面追加 `∥ verdict == EXHAUSTED` 仍绿 | 归一化后守卫条件必须**逐字符等于** `!gate(...)` |
| 6 | compact_decision | `chain-timeout-dedup` | dataflow | 只判 `skip` 文案在 `started` 之前、`timedOutRoutes.add` 在 `onTimeout` 之后 | 取 skip 分支的花括号体，体里必须有活的终结语句 |
| 7 | compact_decision | `silent-card-contract` | parser-death | 在**原文**的 900 字符回看窗口里找 `if (!silent)` | `enclosing` 判据 + 代码投影：守卫必须真的包住卡片调用，条件必须测 `silent` 取反且非恒真 |
| 8 | loop_tool | `T1` | dataflow | 只判 `UI_PUSH in critical` | 每一次推送都要判活性，至少一次活；`if (false) withContext {}` → 红 |
| 9 | loop_tool | `T3` | dataflow | 只判 `PENDING in pass2_seq` | `PENDING` 必须出现在活的守卫条件里（token 在条件里也判；`false &&` → 红） |
| 10 | loop_tool | `T1` | parser-death | `CONTINUE_STMT.search` 找「恰好一行 `continue`」定分支末，`if (true) continue` 让分支边界滑到下一个拒绝分支（把别人的推送算进来） | CRITICAL 分支 = 花括号配平；终结语句与推送都在**本分支体内**判 |
| 11 | loop_tool | `T5` | dataflow | 只数 `statusAtDispatch(` 出现 ≥2 次 | 函数体必须对 `PENDING` 分支（`PENDING`+`RUNNING`+`if|when`）；`= RUNNING` 直给 → 红 |
| 12 | loop_counter | `C1_candidate_detect` | anchor | 注释块必须**紧邻**语句（向上只跳空行/兄弟重置），插 `Unit` 就脱钩 | 块级走读：走过空行/兄弟重置/普通语句，遇 `{`/`}` 行才止 |
| 13 | loop_counter | `C2_positive_guard` | dataflow | 只看条件里有没有 `toolCalls.isNotEmpty`/`!…isEmpty()` | 条件不得被字面量短路（`true ||`、`false &&`、`isEmpty ∥ isNotEmpty` 都不算守卫） |
| 14 | loop_counter | `C3_empty_guard_depth` | parser-death | 重置正则要求行尾 `= <纯数字>`，`0 + 0` 整行不匹配 → 非候选 → 绿 | 右值按字面量表达式匹配（`[\d_\s+\-*/()]+` 且含数字）；一行多条语句按 `;` 拆开逐条判 |

---

## ④ 修法（轴 → 手法）

所有 4 道门共用一套结构助手（为避免跨门共享依赖，各自内联一份；`scan.sh` 冻结、不引公共模块）：

- `mask_code(src)`：**偏移保持**的投影，把注释文本与字符串/字符字面量内容抹成空格（换行保留、行长不变、行号可用）。所有「这段代码决不决定 X」的判据读这份投影；`code.count(CUE_TEXT)` 这类字面量单点判据继续读「只抹注释」的投影。
- `match_close` / `if_statements` / `enclosing`：按括号深度定位，`if (...)` 的**语句体**统一走「到语句结束」——`if (false) withContext(…) { … }` 的块体属于该语句（旧的行末法会提前结束，正好让死守卫把推送藏起来）。
- `fun_body(masked, name)`：先找形参右括号，再找 `=` / `{`；块体按配平取，表达式体取到空行/`}`/下一条声明为止。**永不**用「第一个 `{`」（lambda 会冒充块体）、**永不**用列 0 `}`。
- `split_top` / `norm_atom` / `is_lit` / `always_false` / `always_true` / `dead_for`：条件常量性判定（含一层以上括号递进），统一吃 `if (false && …)` / `true || …` 这一族。

各门的具体改造：

- **pin**：采集面 = `for (msg in messages)` 循环体（把上限裁剪排除在去重判据之外，避免把 `recent.add(0, texts[i])` 误判）；新增 `pin-collector-intact`。
- **decision**：入口守卫改成「归一化后逐字符相等」；卡片守卫改成 `enclosing`；新增 skip 分支终结判据。
- **loop_tool**：CRITICAL 分支改花括号；T1/T3 加活性；T5/T6 改「按函数体认定」，T6 从签名派生形参名再核对体内引用（签名挪走 → fail-closed 红）。
- **loop_counter**：重置右值改表达式匹配 + 按 `;` 拆句；候选声明的块级走读；守卫条件常量性；扫描面为空判红。

---

## ⑤ 四层证据

### L1 本门真树绿 + 自证臂

```
$ for g in compact_pin_truth_check compact_decision_gate_check loop_tool_status_transition_check loop_counter_reset_guard; do python3 scripts/scan/$g.py . ; echo "$g rc=$?"; done
✅ compact-pin truth gate: the pin collector feeds a same-message de-dup carrier while still reading both text sources, the collected result survives to the return, and isToolResultOnly() is the only tool-result polarity test in the compaction domain
compact_pin_truth_check rc=0
✅ compact-decision gate: loop entry admits EXHAUSTED, escalation keeps the tier contract, the chain throws its 400 evidence, and every compactAll card is either silent-gated or carries a written keep-criterion
compact_decision_gate_check rc=0
PASS loop_tool_status_transition (Pass 1 decides, Pass 2 publishes, refusals reach the UI)
loop_tool_status_transition_check rc=0
✅ loop counter reset guard: 3 tool-call-claimed reset(s), all inside a tool-call guard
loop_counter_reset_guard rc=0

$ python3 scripts/scan/compact_pin_truth_check.py . --selftest
✅ compact-pin truth gate --selftest: 10 arms flip exactly their criterion
COMPACT_PIN_TRUTH_SELFTEST=PASS

$ python3 scripts/scan/compact_decision_gate_check.py . --selftest
✅ compact-decision gate --selftest: 18 arms flip exactly their criterion
COMPACT_DECISION_GATE_SELFTEST=PASS

$ python3 scripts/scan/loop_tool_status_transition_check.py --selftest
  [OK] pristine tree GREEN …（15 条 fixture + blanker 探针，全 OK）
LOOP_TOOL_STATUS_SELFTEST=PASS

$ python3 scripts/scan/loop_counter_reset_guard.py . --selftest
✅ loop counter reset guard --selftest: 8 arms match their expected verdict
LOOP_COUNTER_RESET_SELFTEST=PASS
```

臂数：pin 6→10（+4 见证臂）、decision 14→18（+4）、loop_tool 10→15（+5，另加 T6 直通臂）、loop_counter 0→8（新建自证入口，臂的失败分支写 `failed.append` 并返回 1）。

### L2 外部见证反攻（修复前 STILL_BLIND → 修复后 CAUGHT）

```
$ python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
    --gates compact_pin_truth_check.py,compact_decision_gate_check.py,loop_tool_status_transition_check.py,loop_counter_reset_guard.py \
    --json /tmp/reattack-03-after.json
compact_pin_truth_check.py   pin-dedup                 CAUGHT  rc=1  dataflow
compact_pin_truth_check.py   pin-both-sources          CAUGHT  rc=1  dataflow
compact_pin_truth_check.py   single-truth-source       CAUGHT  rc=1  anchor
compact_pin_truth_check.py   pin-collector-found       CAUGHT  rc=1  parser-death
compact_decision_gate_check.py entry-admits-exhausted  CAUGHT  rc=1  dataflow
compact_decision_gate_check.py chain-timeout-dedup     CAUGHT  rc=1  dataflow
compact_decision_gate_check.py silent-card-contract    CAUGHT  rc=1  parser-death
loop_tool_status_transition_check.py T1                CAUGHT  rc=1  dataflow
loop_tool_status_transition_check.py T3                CAUGHT  rc=1  dataflow
loop_tool_status_transition_check.py T1                CAUGHT  rc=1  parser-death
loop_tool_status_transition_check.py T5                CAUGHT  rc=1  dataflow
loop_counter_reset_guard.py  C1_candidate_detect       CAUGHT  rc=1  anchor
loop_counter_reset_guard.py  C2_positive_guard        CAUGHT  rc=1  dataflow
loop_counter_reset_guard.py  C3_empty_guard_depth     CAUGHT  rc=1  parser-death
CAUGHT=14
RESULT: ALL CAUGHT
```

修复前同一命令：`STILL_BLIND=14 / RESULT: INCOMPLETE`（每条 rc=0）。

**既有两条「已挡住」的对照仍有效**（手工打补丁 → 门红 → 恢复后 sha256 不变）：

```
$ python3 /tmp/ctrl.py
compact_decision_gate_check.py rc= 1 restore_ok= True
   - [silent-card-contract] …/CompactOrchestration.kt:552 card sysmsg_compact_failed …
loop_counter_reset_guard.py rc= 1 restore_ok= True
   statement: loopState.lengthWallContinues = 0
```

**冻结夹具（`test_scan.py` 不可改）逐条复验**：

```
$ python3 -c "import sys; sys.path.insert(0,'scripts/scan'); import test_scan as t; t.test_loop_counter_reset()"
  ✅ reset inside a toolCalls.isNotEmpty() guard passes (exit 0)
  ✅ reset at the isEmpty() header depth is caught (exit 1)
  ✅ finding names the exact file:line (10)
  ✅ finding explains the shared depth
  ✅ reset without a tool-call claim is out of remit (exit 0)
```

### L3 邻门

```
$ for g in compact_entry_pin_migration_check loop_persist_slice_check skill_restore_session_cleanup_check; do python3 scripts/scan/$g.py .; done
compact_entry_pin_migration_check: OK (9 arms green)                       rc=0
PASS loop_persist_slice_check (persistence slices start at loopState.u…)   rc=0
PASS skill_restore_session_cleanup_check (id threaded, cleanup closed)      rc=0
```

### L4 全量

（见 §6 CI 读数；本地全量读数在 CI 之前的一轮里补齐，两处都写在这里。）

---

## ⑥ CI run

- 分支：`fix/meta-wide3-03-compact-loop`
- HEAD：`<HEAD_SHA>`（门脚本 + 本报告）
- run：`<RUN_ID>`，`conclusion=success`，`head_sha` 与本地 `git rev-parse HEAD` 逐字符一致
- 关键行：`Summary: 112 passed, 0 failed`（恰一条）· `ALL 344 CASES PASS`（恰一条）· ` e: ` 0 条 · 唯一 skipped = Publish（main 门控）· 含 `FAILED` 的行逐条确认是 PASSED 用例名的子串

---

## ⑦ 残留（触发条件 / 触发后方案 / 搁置理由）

1. **`pin-dedup` 是结构判据，不是数据流追踪**
   - 触发条件：出现「先 `seen.add(x)` 再把 `seen` 的某个元素单独 `texts.add(seen.last())`」或把去重塞进 helper 的写法，且真的造成同消息重复。
   - 触发后方案：给采集面加一跳值流（绑定 `val t = seen.add(...)` / `texts.add(seen.last())` 时要求实参本身是 `seen` 或 `seen.toList()`）。
   - 搁置理由：本波 14 条见证里没有这条形态；再往上做值流追踪会把判据从「20 行结构检查」推到「半个 Kotlin 解释器」，装置比例不合（修复门 P2）。

2. **`mask_code` 的字符字面量只处理同行闭合**
   - 触发条件：源码里出现 `'\''` 这类转义单引号字符字面量，且它位于判据 token 之前（会让投影错位）。
   - 触发后方案：换成真正的 Kotlin 词法器（或用 `kotlinc -Xdump` 类工具），或把字符字面量当成「引号到引号」硬跳过。
   - 搁置理由：`grep -rn "'\\\\''" src/` 全树 0 命中；错位只会导致判据误红（fail-closed 方向），不会静默放行。

3. **入口守卫判据是拼写同一性检查，不是语义等价**
   - 触发条件：有人把入口改写成 `val ok = !loopGateOpens(v, e); if (!ok) return false` 或 `if (loopGateOpens(v, e).not())`。
   - 触发后方案：允许一跳本地绑定（`val x = !gate(...)` → 认 `if (!x)`），`not()` 归一到 `!`。
   - 搁置理由：现状是 fail-closed（正当重构会红，不会漏判）；本波见证是「加操作数」，已覆盖。

4. **卡片契约只认 `if` 包裹，不认早退 / `?:` 门控**
   - 触发条件：把 `if (!silent) { card() }` 改写成 `if (silent) skipCard() else card()` 或 `silent || card()`。
   - 触发后方案：接受「同一函数内支配该调用点的早退守卫」。
   - 搁置理由：判红方向安全（fail-closed），且现有 4 处卡片写法统一；改造需要支配关系分析，成本/收益不合。

5. **`T5`/`T6` 要求策略函数体自证，不接受委托**
   - 触发条件：把 `statusAtDispatch` 的判定抽到同文件 helper（`= flipIfPending(current)`）。
   - 触发后方案：允许一跳同文件委托（与 M5 的 `R11 countInto` 同形态），前提是被委托函数也按函数体认定。
   - 搁置理由：现状 fail-closed；抽 helper 时门会红一次，属可见代价。

6. **`T3` 只要求「串行区域内存在活的 PENDING 守卫」，未绑定到 cue 发布点**
   - 触发条件：串行区域里出现第二个 `PENDING` 测试（例如给 flip 也加一个），而 cue 发布的那个被删。
   - 触发后方案：把守卫与 `cueBlock.copy(...)` 的**支配关系**绑起来（发布点必须在守卫块内）。
   - 搁置理由：需要块内配对，改动面比本波见证大；且现有写法只有一个 PENDING 测试。

7. **`loop_counter_reset_guard` 只认 `loopState.<field> = <literal>`**
   - 触发条件：状态持有者重构为 `loopState.copy(lengthWallContinues = 0)`、`with(loopState) { … = 0 }`，或计数器改名。
   - 触发后方案：从状态类声明派生字段名集合，把 reset 判据扩成「字段写入的三种形态」。
   - 搁置理由：改名会让 fail-closed 的「扫描面为空」先红（不会静默）；copy 形态要等真发生。

8. **`loop_counter_reset_guard` 的 claim 走读遇任何 `{`/`}` 行即止（含 `} else {`）**
   - 触发条件：有人把带 claim 的注释放在一个 `if (…) {` **之上**，而重置在块内——此时注释属于那个 `if` 而非重置，判据不把它当候选。
   - 触发后方案：按「注释缩进 == 语句缩进」进一步区分块头注释与语句注释。
   - 搁置理由：这是**刻意**的边界（否则 804 行的 `lastContextTokens = 0` 会被 20 行外面的 KDoc 误判，实测假红一次已修）；误判方向是不会多抓，但也不会漏掉「claim 紧跟语句」的真实回归。

9. **`always_true` 只识别 `true` 显式析取与 `isEmpty() ∥ isNotEmpty()` 两条同义反复**
   - 触发条件：`if (toolCalls.size >= 0 && toolCalls.size > 0)` 之类恒真/恒假的其他写法。
   - 触发后方案：把条件交给一小段布尔常量折叠（或 `simpleeval` 风格的最小求值）。
   - 搁置理由：见证给的是 `true ||`；其余写法目前不存在，且不会造成漏判（那些写法本身仍会命中正常守卫路径）。

---

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-03

# L1 四门真树 + 自证
for g in compact_pin_truth_check compact_decision_gate_check loop_tool_status_transition_check loop_counter_reset_guard; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"
done
python3 scripts/scan/compact_pin_truth_check.py . --selftest
python3 scripts/scan/compact_decision_gate_check.py . --selftest
python3 scripts/scan/loop_tool_status_transition_check.py --selftest
python3 scripts/scan/loop_counter_reset_guard.py . --selftest

# L2 外部见证反攻（14 条；修复前 14/14 STILL_BLIND）
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates compact_pin_truth_check.py,compact_decision_gate_check.py,loop_tool_status_transition_check.py,loop_counter_reset_guard.py \
  --json /tmp/reattack-03.json

# 既有「已挡住」对照复验（手工打补丁 → 门红 → 恢复 sha256 不变）
python3 /tmp/ctrl.py   # 脚本内容见报告正文 L2 段

# 冻结夹具
python3 -c "import sys; sys.path.insert(0,'scripts/scan'); import test_scan as t; t.test_loop_counter_reset()"

# L4 全量（不要裸跑 scan.sh：PRoot 偶发 ENOSYS，另一支持锁时尤其明显）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"   # 期望 112 passed, 0 failed
python3 scripts/scan/test_scan.py                             # 期望 ALL 344 CASES PASS
```
