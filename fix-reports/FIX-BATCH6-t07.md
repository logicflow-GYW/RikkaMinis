# FIX-BATCH6-t07 — modeluse_proto_single_source + thinking_rule_editor_preserve 判据效果化

## 1. 门与轴

- **支线**：`fix/b6-modeluse-thinkrule`（wt6/t07），基线 `base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）。
- **门提交**：`ae0cdce9c`（2 文件 +1237/−719）。**生产 `.kt` 0 行**（`git status src/` = 0）、`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` **零触碰**、门数 112 / 夹具 344 不变。
- **两门 13 条见证（12 BLIND + 1 CAUGHT 对照）→ 修后 13/13 CAUGHT（rc=1，exact）**，restore 13/13 True。
- 轴分布：modeluse 6 = dataflow 2 / family-hole 2 / parser-death 2（+1 对照 dataflow）；thinking_rule 6 = family-hole 3 / dataflow 1 / completeness 1 / parser-death 1。
- **共症（本支提炼）**：判据锚「某个 token 存在」，缺陷动「这个 token 的效果」——调用还在但传的是别的值（10-1）、调用还在但结果被 `|| true` 短路（10-2）、绑定还在但派生跑到了下一行（10-3）、读取还在但值被 `.let { 4096 }` / `.take(0)` 换掉（10-4/10-5）、字符串里的 `"/*"` 伪造块注释吞掉后半段代码（10-6）、锚还在但在死分支/块注释里（A05-2/A05-6）、链上多一环把保留字段抹掉（A05-1）、清单只算单向差（A05-5）。

## 2. 见证原文（file / find → replace / why_gate_green）

### thinking_rule_editor_preserve_check.py（6 条，witnesses-A）

| # | file | find → replace（摘要） | why_gate_green |
|---|---|---|---|
| A05-1 | ThinkingRuleEditPreserve.kt | `seed.copy(kind/scope/wireFormat/label)` 完整保留，追加链式 `.copy(id = …, reasoningEcho = null)` | `call_arg_lists(code_only(builder), "seed.copy(")` 只解析以字面量 `seed.copy(` 开头的那一个列表，链上的第二个 `.copy(` 不在参数列表里 |
| A05-2 | ThinkingRuleEditor.kt | `onCheckedChange = { sendOffValue = it; formatChanged = true }` → `{ sendOffValue = it; if (false) formatChanged = true }` | `dead_guarded` 只搜 handler 头之前的行首前缀，不搜处理器体内部；体内 `formatChanged = true` 满足 `FLAG_ASSIGNMENT in handler` |
| A05-3 | ThinkingRuleEditor.kt | `onClick = { choice = c; formatChanged = true; … }` → `…; formatChanged = !true; …` | `FLAG_WRITE_RE = (true\|false)` 只匹配字面量；`!true` 不可见，倒数第二次的 `true` 被当成最后一次写入 |
| A05-4 | ThinkingRuleEditor.kt | `fun buildRule() = buildEditedThinkingRule(…)` → 块体 `val _unused = buildEditedThinkingRule(…); return seed.copy(wireFormat = buildWireFormat())` | `check_anchors` 只查子串；`buildRule()` 改块体后没有任何判据看它 return 什么，`seed.copy(` 不触发 `BARE_CTOR_RE` |
| A05-5 | ThinkingRuleEditPreserve.kt | representable 清单加 `DeepSeekRelay` / `QwenRootOnly`（`from` 不可达） | `drift = from_types - rep_types` 只算单向差；反方向（清单声称可代表但 from 造不出）不可见 |
| A05-6 | ThinkingRuleEditor.kt | `onSave(buildRule())` → `onSave(seed.copy(wireFormat = buildWireFormat())) /* onSave(buildRule()) */` | `code_only` 只切 `//`，不剥 `/* … */`；且 Arm 1 从不检查 confirmButton 传给 onSave 的是什么 |

### modeluse_proto_single_source_check.py（7 条，witnesses-B）

| # | file | find → replace（摘要） | why_gate_green |
|---|---|---|---|
| 10-1 | ModelExecutionService.kt | `parseCustomEndpointPath(inputJson)?.let { path -> provider.absoluteEndpointOverride = path` → `JSONObject(inputJson).optString("endpoint").takeIf { … }?.let { path -> … }` | 判据只查 `'ModelUseProtocol.parseCustomEndpointPath(' in src`（被未使用的私有委托满足）+ `is_delegate_def`（同样满足）；从未检查委托有没有 live caller、写入值从哪来 |
| 10-2 | ModelUseOffloadHandler.kt | `inst != null && providerMatches(…)` → `inst != null && (providerMatches(…) \|\| true)` | `PROVIDER_MATCHER not in body` 只查子串出现在函数体里，不查返回值如何参与过滤 |
| 10-3 | ModelUseOffloadHandler.kt | `val effectiveModel = …effectiveModel(entry)` → 追加续行 `.let { entry.model }` | 别名绑定 `rest.find("\n")` 在首个换行截断，续行上的 `.let` 不在绑定里 |
| 10-4 | ModelExecutionService.kt | `val maxTokens = req.optInt("max_tokens", 4096)` → `….let { 4096 }` | C1 只查 `read_keys` 包含 `max_tokens`；C2b 只比两个调用点的命名参数名集合；`_post_process_override` 只对 thinkingLevel 跑 |
| 10-5 | ModelExecutionService.kt | `thinkingLevelFromWire(req.optString("thinking_level", ""))` → `….take(0))` | `_post_process_override` 只查 `.let`/`.run`/`.takeIf` 块；`.take(0)` 不匹配 `VALUE_COMBINATOR_RE`，且 `LEVEL_READ_RE` 仍匹配到 read 文本 |
| 10-6 | ModelExecutionService.kt | 委托体 `ModelUseProtocol.parseCustomEndpointPath(inputJson)` → `…(inputJson + "/*") ?: JSONObject(inputJson).optString("endpoint").removePrefix("*/")` | 自写 `code_only` 无引号状态机：`"/*"` 里的 `/*` 开启"块注释"，把 `") ?: JSONObject(inputJson).optString("e…` 整段吞白，解析器死亡 |
| 10-7（对照，原 CAUGHT） | ModelExecutionService.kt | `thinkingLevel = thinkingLevel` → `= req.optString(…).let { it; ThinkingLevel.OFF.let { lv -> lv } }` | 修前即被捕获；本支保持其红 |

## 3. 判据改造（旧形态 → 新形态，逐条）

**共性**：自写词法器/括号计数/字面白名单全部删除，底层走共享 `_kotlin_lexer`（`code_view` 注释+字符串等长掩码 / `literal_view` 注释掩码）+ `_kotlin_ast`（`match_bracket` / `split_top` / `const_value` / `const_bool` / `function_body` / `stmt_spans`）+ `_kotlin_valueflow`（`value_sources` / `call_args` / `expr_end` / `path_feasible` / `contains_ident`）。判据从「token 存在」改锚「token 的效果」：

| 见证 | 旧判据（为何瞎） | 新判据（效果化） |
|---|---|---|
| A05-1 | 只解析字面量 `seed.copy(` 开头的一个参数列表；`named_arg_keys` 跳过无 `=` 的参数 | `copy_arg_lists` 沿 `seed.copy(` 收集**每一环**链式 `.copy(`（`match_bracket` 定界）；具名参数命中 `COPY_FORBIDDEN_FIELDS` 判红；**位置参数**按索引报（`copy(uuid(), …)` 无法证明不命中 id/reasoningEcho） |
| A05-2 | `dead_guarded` 只看 handler 头之前的行首前缀；写没写在体内不区分 | `last_live_flag_write`：只有 `path_feasible(code, pos)` 的写入才算「记录到用户改动」；死分支里的写入单独报（"N handler(s) sit in an UNREACHABLE branch"） |
| A05-3 | `FLAG_WRITE_RE = (true\|false)` 只认字面量 | 捕获整个右值（`FLAG_WRITE_RE = \bformatChanged\s*=(?!=)`），`flag_value` 经共享 `const_bool` 折叠；最后一次**可达**写入折叠不为 True 判红 |
| A05-4 | 锚子串存在即可 | `check_save_binding` 两半：① `onSave(<arg>)` 实参必须恰为 `buildRule()`（`call_args` + `re.fullmatch`）；② `buildRule()` 的体（`function_body`，expr/block 两种形态）必须 **return** `buildEditedThinkingRule(...)` 调用（`_is_call_to` 整表达式 + `_bare_returns`）——绑定后丢弃 + 返回原始 seed 判红 |
| A05-5 | `from_types - rep_types` 单向差 | `from_types ^ rep_types` 双向对称差，违规文案分别列出 only-in-from / only-in-representable |
| A05-6 | 自写 `code_only` 只切 `//` | 全部结构读走共享 `code_view`（块注释+字符串等长掩码，位置可回投）；`onSave` 实参判据使块注释里的真调用不再算数 |
| 10-1 | `'ModelUseProtocol.parseCustomEndpointPath(' in src` + `is_delegate_def` 三行窗口 | ①`check_delegate_used`：每个私有 delegate 必须有 ≥1 个 **LIVE** caller（`path_feasible` 过滤死代码，声明自身不算）；②`check_endpoint_provenance`：`absoluteEndpointOverride =` 的写入值必须绑定自 `parseCustomEndpointPath(…)?.let { name ->`（收集绑定名）或即该调用本身 |
| 10-2 | `PROVIDER_MATCHER not in body` 子串存在 | 谓词语句经 `_absorbed` 折叠：`const_value` 折叠 + `&&`/`||` 逐操作数递归（含**块内**），任一操作数折叠成常量即判「不是过滤器」 |
| 10-3 | 绑定在首个换行截断 | `expr_end` 取整个表达式（续行 `.let { … }` 在绑定内）；`_alias_discards` 用共享 `value_sources` 判「最终值是否还派生自 enrich 调用」，call 定界失败 fail-closed |
| 10-4 | C1 只查 key 被读；C2b 只比参数名集合 | **C1b**：对每个请求键的 `val/var` 绑定跑 `_value_replaced`（右值经 `expr_end` 取整）——三条失守形态：lossy 成员调用（`take`/`drop`/`substring`/`replace`…）、组合子块忽略接收者或块尾常量、值流断链（`value_sources` 无来源仍派生自 read） |
| 10-5 | `_post_process_override` 只查 `.let/.run/.takeIf` 块 | `_value_replaced` 规则 1（lossy 成员调用 `.take(0)`）+ 规则 2（组合子块）+ 规则 3（值流）；C2 的 `_traces_to_level` 绑定用 `expr_end` 取整、允许一跳 helper |
| 10-6 | 自写 `code_only` 无引号状态机 | 删光；delegate 体/尾部经共享投影 + `match_bracket` 定界，`"/*"` 字面量不再伪造块注释（解析器死亡被构造性消灭） |
| 10-7（对照） | — | 保持红：`LEVEL_CONST_RE` 在 `.let { … }` 块内 → `_value_replaced` 规则 2 命中 |

## 4. 四层证据

- **L1 selftest**：`MODELUSE_PROTO_SELFTEST=PASS` + `MODELUSE_PROVIDER_MATCHER_SELFTEST=PASS (4 fixtures: 3 violations caught, 1 fail-closed)`（臂数 18→**26**）；`THINKING_RULE_EDITOR_PRESERVE_SELFTEST=PASS`（臂数 10→**17**）。新增臂覆盖每条见证（死委托/端点来源/短路/字符串锚/续行派生/解析器死亡/max_tokens 常量替换/take(0) 截断/copy 链/位置 copy/死分支写入/取负/return 旁路/清单反向漂移/Save 旁路），clean 臂全绿。
- **L2 真树**：两门 rc=0（`modeluse_proto_single_source_check: clean`、`thinking_rule_editor_preserve_check: OK (5 arms, 0 violations)`）。
- **L3 邻门 + 全量**：邻门 8 道 rc=0（offload_cli_args / modeluse_proto / provider_type_registry / thinking_rule_editor_preserve / openminis_compat_field_parity / provider_rpc_symmetry / offload_media_b64_wire / memory_ui_wiring）；`probe/loop_gates.py` 逐门串行 **110/110 GREEN（318s）**；`test_scan.py` 46 组夹具 **344 pass / 0 fail**（`probe/testscan_chunks.py` 逐函数驱动，计数读自模块 `PASS/FAIL` 计数器并去重）。
- **L4 反攻（restore 校验）**：`verify_batch5.py --mode full` → thinking_rule **6/6 CAUGHT（rc=1, exact）**、modeluse **7/7 CAUGHT（rc=1, exact）**（对照 10-7 保持红）；`git status --porcelain src/` = 0、`git diff --name-only <绿sha>..HEAD -- . ':(exclude)fix-reports'` = 0（restore 后 sha 与门提交逐字节一致）。输出：`probe/t07-final-thinking.json` / `probe/t07-final-modeluse.json`。

## 5. CI run/job/head_sha（CI 出结论后回填）

- **run `40255372894` / #2703 / job `121355971417` / attempt 1 / event workflow_dispatch：success**（02:02:54Z 出结论，耗时 ~22 min）。
- **head_sha `ae0cdce9cd6e20e22964df2ee9124f322093cc55` 与本地 HEAD 逐字符一致**（三方：本地 HEAD = API run.head_sha = 推送 commit）。
- 26 步唯一非 success = step 23 Publish to Releases **skipped**（main 门控，预期）。
- 日志 `probe/ci/job-2703.log`（1,486,478 B，0 NUL，字节级核过）：
  - `Summary: 112 passed, 0 failed` 恰 1（:1019，带框线 ║）；
  - `ALL 344 CASES PASS` 恰 1；
  - Kotlin 编译错误 0、` e: ` 0；
  - 门头 `[N/112]` 互异 112 个；
  - 6 条含 FAILED 的行逐条核过：全是 PASSED 用例名子串（RealAgentAdapterSkeletonTest / ChatAgentTraceObserverSchemaTest ×4 / TerminalErrorSurfacedTest）；
  - 两门真树行各恰 1：`modeluse_proto_single_source_check: clean (dialect single-sourced in ModelUseProtocol)`（01:42:42）、`thinking_rule_editor_preserve_check: OK (5 arms, 0 violations)`（01:43:18）；
  - 两门注册标记各恰 1（test_scan 层 `✅ … selftest has teeth (MODELUSE_PROTO_SELFTEST=PASS)` / `(THINKING_RULE_EDITOR_PRESERVE_SELFTEST=PASS)`）。
- **已知限制（记账）**：`MODELUSE_PROVIDER_MATCHER_SELFTEST=PASS` 在 CI 日志 0 命中——该标记未注册进 test_scan（test_scan 只断言 `MODELUSE_PROTO_SELFTEST=PASS`），4 夹具 provider-matcher 臂在 CI 无独立见证，臂数由本报告 §4/§7 承载；同 B6-t04 R7 形态。

## 6. 残留 R1..R6（P2，每条带触发条件 / 触发方案 / 搁置理由）

- **R1 `_absorbed` 对嵌套块无条件下探**：块内任何折叠成常量的操作数都判「已定值」，一个合法 `x.filter { flag || true }`（真恒真但属刻意设计）会被红。触发条件：生产代码出现刻意恒真的嵌套谓词。方案：给 `_absorbed` 加 allowlist（标注 `ponytail:` 的常量臂豁免）。搁置理由：fail-closed 方向（红→人工看），当前真树 0 误报。
- **R2 `check_delegate_used` 只数 live caller，不校验调用实参**：一个 live 调用 + 旁边第二个 raw-read 站点仍绿。触发条件：同文件出现 `parseCustomEndpointPath(…)` 调用与并行的 `optString("endpoint")` 直读。方案：caller 文件内禁 delegate 底层 key 的直读（按 receiver 白名单放行 passthrough 路径）。搁置理由：本次见证只动了唯一调用点，扩展面无触发证据。
- **R3 `_value_replaced` 规则 2 只看组合子块最后一条语句**：多语句块体内先 return 常量的形态（`let { if (c) return@let 0; it }`）会漏。触发条件：生产出现多语句 let 块替代读值。方案：块内每个 `return@label` 都进 `_tail_stmt` 候选。搁置理由：本仓风格无此形态。
- **R4 `_traces_to_level` helper 跳数 = 1**：helper 再调 helper 的读不可达。触发条件：`thinking_level` 读经两层 helper。方案：循环展开到不动点。搁置理由：真树直读 + 一跳已覆盖。
- **R5 `check_endpoint_provenance` 只认 `let/also/run { name ->` 绑定形态**：`?.also { provider.x = it }`（receiver-it 直写）不在绑定名集合里。触发条件：改写为 also-直写风格。方案：补 `it` 形态（把 `it` 归入 bound 当 also/let 体只含赋值）。搁置理由：真树是 `let { path ->` 形态。
- **R6 C1b 只查 `val/var` 绑定**：非绑定语句里的读被后处理（如内联实参 `.take(0)`）只有 lossy 规则接得住，值流规则不覆盖。触发条件：生产出现非绑定式的读后处理。方案：把 C1b 的语句域扩到全部含读语句。搁置理由：扩大 net 需先验证真树零误报，本次 radius 不含。

**沙箱事实（已知，S28 同族）**：`test_scan.py` 整跑在本沙箱会被 `OSError: [Errno 38] Function not implemented` / stall-guard 杀掉（本支 3 次）；健康路径 = `probe/testscan_chunks.py` 逐函数子进程 + 切片 + 日志断点（已入表）。

## 7. test_scan.py 注册项（交主会话统一注册）

- **无需新增注册**：两门的标记串已注册且未变——`test_scan.py:2758 ("modeluse_proto_single_source_check", "MODELUSE_PROTO_SELFTEST=PASS", …)`、`:2881 ("thinking_rule_editor_preserve_check", "THINKING_RULE_EDITOR_PRESERVE_SELFTEST=PASS", …)`；CI 日志里两标记各恰 1 行即见证新臂真跑。
- 臂数读数（供主会话记账）：modeluse 26 臂（自测 18→26）+ provider matcher 4 夹具；thinking_rule 17 臂（10→17）。

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6
# L1 selftest + L2 真树
(cd wt6/t07 && python3 scripts/scan/modeluse_proto_single_source_check.py --selftest && \
            python3 scripts/scan/thinking_rule_editor_preserve_check.py --selftest && \
            python3 scripts/scan/modeluse_proto_single_source_check.py . && \
            python3 scripts/scan/thinking_rule_editor_preserve_check.py .)
# L4 反攻（patch→跑门→restore→sha 校验）
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt6/t07 --mode full \
  --only thinking_rule_editor_preserve_check.py --out probe/t07-final-thinking.json
python3 verify_batch5.py --witnesses witnesses-B.json --repo wt6/t07 --mode full \
  --only modeluse_proto_single_source_check.py --out probe/t07-final-modeluse.json
# L3 全量
(cd wt6/t07 && python3 -u probe/loop_gates.py . && python3 -u probe/testscan_chunks.py .)
# 改动面
git -C wt6/t07 diff --stat e145ec961..ae0cdce9c
git -C wt6/t07 status --porcelain src/
```
