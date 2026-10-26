# FIX-PRIMITIVE-ROUND2 — 三原语第二轮（单行 else / value_sources 分叉 / when 块体与 subject / 数值比较）

| 项 | 值 |
|---|---|
| 分支 | `fix/primitive-ast-round2`（延续 `fix/primitive-else-negation` @ `1a4eff14d`，**不是基于 main**） |
| 提交 | `47f988cfa`（四条修复） → `626985db9`（`branch_value` 补正，CI #2760 红门所致） |
| 改动面 | 3 文件 +424/−55：`scripts/scan/_kotlin_ast.py`、`scripts/scan/_kotlin_valueflow.py`、`scripts/scan/auto_approve_bypass_check.py` |
| 生产 `.kt` | **0 行** |
| 自测臂 | ast 34 → **57**、valueflow 29 → **48**、lexer 20 → 20 |
| 门数 / 夹具 | 112 / 344 不变；`scan.sh`、`test_scan.py` **零触碰** |
| 停止节点 | 分支 CI 绿（head_sha 逐字符一致）→ 停。不合 main |

---

## §1 结论

外部报告点名的四条原语缺陷（A1-A4）**全部实锤并修复**：四条都是「判据锚在拼写/存在，而结构在真实代码形态上退化」的同一族问题。

- **A1**：`_branch_extent` 对单行 `if (c) a else b` 把 else 一起吞进 then 区间 → 5 道门自写 `split_inline_else` 绕过。真树 1168 处单行 if/else。
- **A2**：`value_sources` 的 if/else 分叉 **100% 死代码**（`re.match(r"else\b", rest)` 永假，`rest` 恒以 then 表达式开头），四份 fix-reports 已登记。
- **A3**：`when_branches` 块体臂区间截断在 `{`，`enclosing_conditions` 丢 `when(subject)` 主体。
- **A4**：`const_value` 缺 `< <= > >=` 折叠 → `guard_feasible` 顶层复合条件进不去。

一处**为修 A2 而引入的误红**在 CI 首跑当场被抓（#2760 `parts_json_funnel_check` [R6]），已在原语层收口（`branch_value` 剥块），没有靠给门加豁免绕过 —— 记入 §3.5 与 §8。

---

## §2 改动面

```
 scripts/scan/_kotlin_ast.py               | 349 ++++++++++++++++++++++++----
 scripts/scan/_kotlin_valueflow.py         | 123 ++++++++++-
 scripts/scan/auto_approve_bypass_check.py |   7 +-
 3 files changed, 424 insertions(+), 55 deletions(-)
```

`auto_approve_bypass_check.py` 的 7 行是**臂期望计数**同步（`checkperm_auto_approve_overlay` 1 → 2），理由见 §6.2 —— 这是本支唯一的白名单外改动，必须在收口时被看见。

---

## §3 逐条修法

### 3.1 A1 单行 else 吞体

**旧形态**：`_branch_extent` 从 `if (` 的条件闭括号起算 then 体，遇 `else` 关键字才停；单行形态下 then 体是「到行尾」，直接吃掉了同一行的 `else b`。

**修法**：then 体边界判定改为**语句级**，并引入 `_kotlin_lexer.mask_code` 做位置级屏蔽 —— 字符串与注释里的 `else` 不作关键字（`if (c) s = "else" else d()` 不会在字符串里截断）。这是对报告补丁的直接修正：外部报告给的补丁「遇 `else` 关键字即停」没有字面量感知。

**配套**：dangling else 归**内层** if（`if (a) if (b) x() else y()` 的 else 属于 `if (b)`），由 `_if_stmt_end` 按语句边界回溯。

**影响面**：`if_blocks` / `enclosing_conditions` 全线收口，5 道门自写的 `split_inline_else` 变成冗余（本轮不动，见 §7 R3）。

### 3.2 A2 value_sources if/else 分叉

**旧形态**：`rest = e[close+1:].strip()` 之后 `re.match(r"else\b", rest)`，而 `close` 是 then 体的右边界 —— 之后永远不可能以 `else` 开头，整段分叉不可达。

**修法**：改用 `if_blocks(e)` 拿 `(cond, t_start, t_end, e_start, e_end)` 四元组，`const_bool(cond)` 三值判定：`True` → 只返 then 侧、`False` → 只返 else 侧、未知 → 两侧来源合并。同时保留「接收者是函数调用时不拆」的既有语义（`f(if (c) a else b)` 整条返回）。

### 3.3 A3 when 块体臂与 subject

**旧形态**：块体臂 `true -> { z() }` 的区间截断在 `{`，臂内语句落不进条件；`enclosing_conditions` 只产出 `('when', arm_cond)`，丢掉 subject。

**修法**：块体臂区间覆盖整块；`enclosing_conditions` 对**字面量臂 / 标识符臂**合成 `(subject) == (arm)`（`when (false) { true -> dead() }` → `(false) == (true)`，可折叠成死分支）。`in` / `is` / 通配 `else` / 多条件臂（`1, 2 ->`）**原样保留不合成** —— 合成会污染下游对这些条件文本的匹配。

### 3.4 A4 数值比较折叠

**修法**：`const_value` 支持 `< <= > >=`（双字符运算符先于单字符匹配）；`->` 在场时不折叠 `>`（防 `x -> y` 被当大于号）；字符串比较保守返回 `None`（不做字典序）。**自反比较**（`x === x` / `x !== x`）的折叠必须在常量判定**之前**，否则永远走不到 —— 这是报告补丁的顺序缺陷，已按建议调整。`==` / `!=` 的自反不折叠（Kotlin `equals` 可被病态实现推翻），登记 §7 R2。

### 3.5 `branch_value` 补正（CI #2760 当场抓到的误红）

**红门与真站点**：`parts_json_funnel_check` [R6]

```
ConfigBackup.kt:2334
  reasoningContent = if (m.has("reasoningContent")) {
      ChatRepository.capReasoningContentForRow(...)
  } else {
      null
  }
```

**机制**：A2 修好后这里终于被拆成两条来源，但分支体**没剥 `{ }`** → else 侧交给消费端的文本是 `"{ null }"`，而门侧 NULLISH 白名单按 `"null"` / `"Unit"` 精确匹配 → 既不等于 null 也不含消毒调用 ⇒ **消毒过的真代码被判成「存了未消毒的载荷」**。这是典型的「为修一处死代码而引入别的问题」。

**修法**：原语层新增 `branch_value(part)` —— 分支体若整体是 `{ … }` 块，取其**尾表达式**（块的值就是尾表达式，与 `let` / `run` 的处理同族）；括号不配平或块后还有尾巴则原样返回。`value_sources` 的 if/else 两侧先过 `branch_value` 再走常量判定。

**为什么不在门侧加豁免**：门侧豁免会让这条判据对该站点永久失明（真出现未消毒载荷也报不出来），而病因是原语把「值形态」当成了「文本形态」。

---

## §4 验证

### 4.1 L1 三模块 selftest

```
KOTLIN_AST_SELFTEST=PASS (57 arms)          # 34 → 57（+23）
KOTLIN_VALUEFLOW_SELFTEST=PASS (48 arms)    # 29 → 48（+19）
KOTLIN_LEXER_SELFTEST=PASS (20 arms)        # 不变
```

本轮新增臂（原文与门职责一致，非自指断言）：

**AST（23 条）**：`if_blocks_single_line_else_span`、`enclosing_single_line_else_text`、`enclosing_else_string_literal_not_keyword`、`enclosing_else_comment_marker_in_string`、`enclosing_else_raw_string_not_keyword`、`enclosing_else_after_line_comment`、`enclosing_dangling_else_binds_inner`、`when_block_arm_span_covers_block`、`when_block_arm_encloses_inner_line`、`when_const_subject_synthesised`、`when_wild_in_arm_kept_raw`、`when_wild_is_arm_kept_raw`、`when_multi_cond_arm_kept_raw`、`when_ident_arm_synthesised`、`const_num_lt`、`const_num_le_two_char_op`、`const_num_gt_false`、`const_num_ge_two_char_op`、`const_cmp_inside_and_folds`、`const_selfref_strict_eq`、`const_selfref_strict_ne`、`const_str_order_not_folded`、`const_eq_selfref_not_folded`

**VALUEFLOW（19 条）**：`value_sources_if_else_both`、`_if_true_then_only`、`_if_false_else_only`、`_nested_if_else_chain`、`_call_wrapped_not_split`、`_null_branch_merged`、`_literal_branch_merged`、`_block_branch_split`、`_block_branch_mixed`、`_block_null_branch_merged`、`branch_value_peels_block_tail`、`branch_value_keeps_non_block`、`branch_value_keeps_trailing_text`、`path_feasible_single_line_else_dead`、`path_feasible_single_line_else_live`、`path_feasible_when_const_subject_dead`、`path_feasible_when_block_arm_dead`、`path_feasible_dangling_else_alive`、`guard_feasible_cmp_inside_and`

期望值全部写死字面量，不从被测常量推导。

### 4.2 L1b 变异负控（逐条，9 次）

方式：字节级备份 → 替换 → 跑 selftest → 按**字节**还原 → 复绿 + sha256 前后一致（**未用 `git checkout --`**）。

| # | 变异 | 触发红臂 | 还原后 |
|---|---|---|---|
| A1a | 单行 else 停点还原 | `if_blocks_single_line_else_span`、`enclosing_single_line_else_text`、`enclosing_else_string_literal_not_keyword`、`enclosing_else_comment_marker_in_string`、`enclosing_else_raw_string_not_keyword`、`enclosing_dangling_else_binds_inner` + vf 6 条 | 字节一致，复绿 |
| A1b | dangling else 不归内层 | `enclosing_dangling_else_binds_inner`、`value_sources_nested_if_else_chain` | 同上 |
| A2 | if/else 分叉还原（死代码） | `value_sources_if_else_both`、`_if_true_then_only`、`_if_false_else_only`、`_nested_if_else_chain` | 同上 |
| A2a | 块体分支不剥（`branch_value` 还原） | `value_sources_block_branch_split`、`_block_branch_mixed`、`_block_null_branch_merged`、`branch_value_peels_block_tail` | 同上 |
| A2b | 取消字面量分支合并 | `value_sources_null_branch_merged`、`_literal_branch_merged`、`_block_null_branch_merged` | 同上 |
| A3a | 块体臂区间截断 | `when_block_arm_span_covers_block`、`when_block_arm_encloses_inner_line`、`path_feasible_when_block_arm_dead` | 同上 |
| A3b | 不合成 subject | `when_const_subject_synthesised`、`when_ident_arm_synthesised`、`when_block_arm_encloses_inner_line`、`path_feasible_when_const_subject_dead`、`path_feasible_when_block_arm_dead` | 同上 |
| A4a | 比较折叠还原 | `const_num_lt`、`const_num_le_two_char_op`、`const_num_gt_false`、`const_num_ge_two_char_op`、`const_cmp_inside_and_folds`、`guard_feasible_cmp_inside_and` | 同上 |
| A4b | 自反比较顺序还原 | `const_selfref_strict_eq`、`const_selfref_strict_ne` | 同上 |

**判读**：每条变异只打红**本层新臂**（+ 其端到端下游臂），不误伤无关臂 ⇒ 臂与修法一一对应；A1 拆成两条变异正是为了证明「停点」与「dangling 归属」是两条独立约束。

### 4.3 L2 行为实证（`path_feasible` 六样本 + 形态变体）

```
死 else 单行(1==1 的 else):      (False, [('if', '!(1 == 1)')])
活 else 单行(条件未知):          (True,  [('if', '!(ready)')])
活 else 单行(1==2 的 else):      (True,  [('if', '!(1 == 2)')])
死 then 单行:                    (False, [('if', '1 == 2')])
when(false) 字面量臂:            (False, [('when', '(false) == (true)')])
when(false) 块体臂:              (False, [('when', '(false) == (true)')])
when(x) 变量 subject:            (True,  [('when', '(x) == (true)')])
1 > 2 && g:                      (False, [('if', '1 > 2 && g')])
dangling else inner:             (True,  [('if', 'a'), ('if', '!(b)')])
```

任务书要求的六项全部覆盖：死/活单行 else、`when (false) { true -> dead() }`、`1 > 2 && g`、块体臂内 dead、subject 为变量（变量 subject 正确判**活**，不误杀）。

`branch_value` 行为：

```
if (c) { a } else { b }              -> ['a', 'b']
if (c) { a } else b                  -> ['a', 'b']
if (has(x)) { cap(x) } else { null } -> ['if (has(x)) { cap(x) } else { null }']   # 字面量分支并回整条
{\n  first()\n  second()\n}          -> 'second()'
{ a } + b                            -> '{ a } + b'    # 块后带尾巴不剥
```

### 4.4 L3 邻门与全量对拍

**六邻门 + parts_json** 真树全 rc=0：`mirror_category_route_check`、`browser_destroy_wakeup_check`、`file_provider_paths_coverage_check`、`compact_decision_gate_check`、`offload_host_path_check`、`chat_ui_interaction_check`、`parts_json_funnel_check`（后者 live / snapshot 两侧均 `PASS`，[R1]-[R12] 逐条打印）。

**全量 110 门对拍**（改前快照 vs 改后，rc + 完整 stdout 逐字节）：109 IDENTICAL，**唯一 DIFF = `auto_approve_bypass_check`**，差异只有一行臂计数：

```
- AUTO_APPROVE_BYPASS_SELFTEST checkperm_auto_approve_overlay arm: OK (1 violation, red as designed)
+ AUTO_APPROVE_BYPASS_SELFTEST checkperm_auto_approve_overlay arm: OK (2 violation, red as designed)
```

原因见 §6.2，属**设计内变更**（A2 修好后该见证的第二条判据从不可达变可达）。

---

## §5 CI 读数

### 5.1 首次 dispatch：CI #2760（run `40358588611`）→ failure

`head_sha` = `47f988cfa`。红在扫描阶段，唯一红门 `parts_json_funnel_check`：

```
[57/112] parts_json_funnel [R6] backup import stores an uncapped payload — reasoningContent
reasoningContent = { null }
```

根因与修法见 §3.5。**这条失败是本轮最有价值的产出**：它证明 A2 的「拆分支」在没有把分支体归一成值形态时会把消毒过的真代码判成脏 —— 属于「修一处、引入一处」，且只有真树 + 全量 CI 才看得见（原语自测臂当时全绿）。

### 5.2 补正后 dispatch：CI #2761（run `40363436679`）→ **success**

| 项 | 读数 |
|---|---|
| conclusion / attempt | **success** / attempt 1 |
| `head_sha` | `626985db9f89181be9a3ddae90bee3d703ebe7f2` —— 与本地 `git rev-parse HEAD` **逐字符一致** |
| 时间 | 16:53:29Z → 17:20:00Z（26m31s） |
| job | `121715290558`（`build`），26 步，非 success 步 = 0，**唯一 skipped = `Publish to Releases`**（main 门控，符合预期） |
| 日志 | 1,492,221 B，NUL 0 |

机械判读（逐条核过，不是"看着是绿的"）：

- `║  Summary: 112 passed, 0 failed` **恰 1 条**（另有 6 条子套件 Summary：25/16/44/19/30/14 passed，全部 0 failed）
- `ALL 344 CASES PASS` **恰 1 条**
- 门头 `[N/112]` **112 条**，编号集合 = `1..112` 无缺无重，门名互异
- 顶格 `e: `（Kotlin 编译错）**0 条**
- 含 `FAILED` 的 6 行**全部是 PASSED 用例名子串**（`slot denied produces FAILED` / `reason PERSISTENCE_FAILED maps …` 等），无一条真失败
- `FAIL (rc=` **0 条**、`Function not implemented` **0 条**（本轮沙箱无 S28 风暴）
- `parts_json_funnel_check: PASS` 存在 ⇒ #2760 的误红确已收口

**未见证项**：`KOTLIN_AST_SELFTEST` / `KOTLIN_VALUEFLOW_SELFTEST` / `KOTLIN_LEXER_SELFTEST` 在 CI 日志里 **0 命中**（与 §6.1 一致）—— 42 条新臂的来源证明只在本报告与本地产物里，CI 侧不可见。这是登记项，不是失败。

---

## §6 test_scan 注册项与见证面

### 6.1 原语自测在 CI 里无见证（登记）

`grep -n "_kotlin_ast\|_kotlin_valueflow\|_kotlin_lexer" scripts/scan/test_scan.py` → **0 命中**。即：42 条新增臂（57−34 + 48−29 = 23+19）在 CI 日志里**没有任何一行见证**，只有各门自己的 selftest 标记。本支按硬约束**未动 `test_scan.py`**。

**建议（交主会话收口时决策，不要现在动）**：`test_scan.py` 增三条只断言前缀、**不断言臂数**的检查：

```python
("_kotlin_ast --selftest",     "KOTLIN_AST_SELFTEST=PASS",      (REPO_ROOT,)),
("_kotlin_valueflow --selftest", "KOTLIN_VALUEFLOW_SELFTEST=PASS", (REPO_ROOT,)),
("_kotlin_lexer --selftest",   "KOTLIN_LEXER_SELFTEST=PASS",    (REPO_ROOT,)),
```

断言臂数（`(57 arms)`）是自指脆弱断言：加减臂就要改门，属于「门 pin 自己」的反模式。

### 6.2 `auto_approve_bypass_check.py` 臂计数 1 → 2（白名单外改动，必须说明）

**改了什么**：`ARM_EXPECT` 里 `checkperm_auto_approve_overlay` 的期望违规数 `1 → 2`（附注释说明 A2 后 C6 第二判据可达）。

**为什么必须改**：该臂的见证代码是 `val level = if (AutoApproveStore.isEnabled) PermissionLevel.BYPASS else getLevel(toolName)`。旧 `value_sources` 不拆分支 → C6 的第二条判据（"分支直取 BYPASS 未经 getLevel"）永远无输入；A2 修好后它**真的看到了** bypass 分支，于是同一条见证上正确的违规数是 2（bypass 分支 + 丢弃 getLevel 结果）。若维持 1，门必须被反向削弱才能绿 —— 那是把「原语修好」当成回归。

**替代方案（未采用）**：把 `PermissionLevel.BYPASS` 当常量折叠从而并回整条表达式（`const_value` 需认识 dotted enum 常量）。不采用的理由：那会让整族「分支里写死的策略常量」重新隐身，与 A2 的目的相反。

**收口影响**：这是本支唯一修改的白名单外文件（7 行），门行为加强而非放松，请主会话在收口时确认。

---

## §7 残留（各带「触发条件 / 触发后方案 / 搁置理由」）

**R1 多条件 when 臂不拆臂**（`1, 2 -> a()`）：只保留原样条件文本 `1, 2`，不生成 `(s)==(1) || (s)==(2)`。
- 触发条件：某道门需要按臂内单个常量判死活（如「`when(x) { 1, 2 -> dead() }` 且 x 已知为 3」）。
- 触发后方案：按顶层逗号拆臂，逐臂合成 `(subject) == (arm)` 并 `||` 连接；`in`/`is` 臂继续原样保留。
- 搁置理由：当前真树无此形态的门需求，合成会改变下游对条件文本的匹配（污染风险 > 收益）。

**R2 `==` / `!=` 自反不折叠**（只折 `===` / `!==`）。
- 触发条件：真树出现依赖 `x == x` 常量性的判据（当前 0 处）。
- 触发后方案：加「equals 语义可信」标注位，由门显式声明后才折叠。
- 搁置理由：Kotlin `==` 走 `equals()`，病态实现可推翻自反性；错误折叠 = 静默假判（把活代码判成死）。

**R3 5 道门自写的 `split_inline_else` / 本地 `reachable()` 未收敛**（`mirror_category_route_check`、`browser_destroy_wakeup_check` 等）。
- 触发条件：这些门下次因原语改进需要同步修改时。
- 触发后方案：删本地实现，改调 `_kotlin_ast.if_blocks` / `_kotlin_valueflow.path_feasible`。
- 搁置理由：半径纪律（本轮只修原语），且它们当前与原语行为一致、不影响判据正确性。

**R4 单行 `if` 无 else 的其它形态**：`if (c) return` 后紧跟裸语句的边界判定未单独建臂。
- 触发条件：任一门报 braceless 体区间缺失/多吞（真树对拍当前 0 差异）。
- 触发后方案：扩 `_branch_extent` 的语句级边界判定并补臂。
- 搁置理由：无证据表明当前有误判，按「存在不构成修复义务」不预设装置。

**R5 原语自测不进 CI 见证面**：见 §6.1。

---

## §8 可复用手法

1. **「拆分支」前必须先把分支体归一成值形态。** 消费门对「空值」的判定常用精确白名单（`null` / `Unit`），`{ null }` 这种文本形态会被判成脏来源。教训：**修一处死代码，会唤醒一整族此前从未被执行过的判据** —— 必须假设它们全都第一次真的跑，并预跑真树（本轮靠 CI 才抓到，代价一轮 24 分钟）。

2. **变异负控要按「约束」拆分，不按「函数」拆分。** A1 拆成「停点」与「dangling 归属」两条变异，才证明它们是两条独立约束；合成一条会掩盖其中一条失效。

3. **外部补丁要按字面量感知重写，不要照抄。** 报告给的 A1 补丁「遇 `else` 即停」在 `if (c) s = "else" else d()` 上会在字符串里截断 —— 位置级屏蔽（`_kotlin_lexer.mask_code`）是唯一稳妥做法。

4. **门的臂期望值变更必须当成「判据加强」来审，不能当成「测试坏了」。** §6.2 的 1→2 若被当成噪声抹平，等于把刚修好的判据重新弄瞎。
