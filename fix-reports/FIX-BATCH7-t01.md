# FIX-BATCH7-t01 — `chat_session_id_binding_check` / `outcome_known_chain_check` 判据加固

- 分支：`fix/b7-sessionbind-outcome`
- 基线：`base/batch7-shared`（main + 共享原语，`e145ec961`）
- 门提交：`926073cfc`（两文件，+795 / −379；生产 `.kt` **0 行**）
- 报告提交：本节所属提交
- 改动面：**2 个门脚本**。`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` / 任何生产 `.kt` / 任何其他门脚本：**零触碰**（`git diff --name-only base..HEAD` 只列出两文件 + 本报告）。

---

## ① 门与轴

| 门 | 判据数 | 本波见证 | 盲轴（改前） | 家族 |
|---|---|---|---|---|
| `chat_session_id_binding_check.py` | 19（C1..C19） | 10-1 .. 10-6 | dataflow ×3、family-hole ×2、结构可达 ×1 | 返回值消费 / 恒拒过滤 / 谓词退化 / 死守卫 |
| `outcome_known_chain_check.py` | 5（L1/L2/L3/MAP/L4） | 11-1 .. 11-6 | dataflow ×2、存在性 ×2、parser-death ×1、anchor ×1、scope ×1 | 链式覆写 / 常量短路 / 恒真比较 / 正则边界 / 首匹配锚 / 调用点逃逸 |

两门共症与第四/五/六批同族，但这一批的落点更靠后：**判据锚「token 被写出」，缺陷动「这个 token 产生的值被谁消费」**。差别在于——

- 第五批的修法是把「拼写」推到「效果」（`path_feasible` / `value_sources`）；
- 本批的 12 条见证里有 7 条**仍能让 `value_sources` 之前的子串判据全绿**，因为它们保留了合规写法作为表达式**前缀**（`.let {}` 尾巴、`|| true` 尾巴、`.copy()` 尾巴、`?.takeIf {}` 尾巴），或者把合规站点放在**首匹配/不可达**位置。所以本批的判据落点是：**「值」与「可达」**。

---

## ② 见证原文（12 条，报告 B 组 gate 10 / gate 11）

### gate 10 → `chat_session_id_binding_check.py`

**10-1（dataflow，C3/C4）** `ChatScreen.kt`
- find：`sessionId = viewModel.currentSessionId,`（`ChatInputArea` / `WebAppSource.ChatAttachment` 两处实参）
- replace：`sessionId = viewModel.currentSessionId.let { sessionId },`
- why_gate_green：`SCREEN_CALL_SPECS` 只做 `"sessionId = viewModel.currentSessionId" in block` —— 子串命中，C3/C4 绿。
- 真实后果：输入区拿到的仍是陈旧路由键 `sessionId`，附件写进 orphan `__new__<uuid>` 目录。

**10-2（dataflow，C8）** `ChatScreen.kt`
- find：`val activeSessionId = ChatViewModelStore.resolveSessionId(viewModel.currentSessionId)`
- replace：同上 + `.let { sessionId }`
- why_gate_green：`_active_session_binding_violations` 只判「rhs 不以 `sessionId` 开头」+「含 `RE_LIVE_ID_SOURCE`」；resolver 仍是表达式前缀，两条都满足。
- 真实后果：`pending.sessionId != activeSessionId` 立刻成立，刚生成的 HTML 预览被丢。

**10-3（family-hole，C13）** `HeadlessChatRunner.kt`
- find：`val owner = runCatching { ChatViewModelStore.ownerFor(sessionId) }.getOrNull() ?: return null`
- replace：`.getOrNull()?.takeIf { 1 == 2 } ?: return null`
- why_gate_green：`RE_CONST_FALSE_FILTER` 枚举了 `false | !true | 0 == 1` 三种字面量，`1 == 2` 不在其中。
- 真实后果：`owner` 恒 null，UI 创建的 ViewModel 永远取消不掉，`cancel` 恒报 false。

**10-4（family-hole，C14）** `HeadlessChatRunner.kt`
- find：`vm.sessionLoaded.first { it }`
- replace：`vm.sessionLoaded.first { it || true }`
- why_gate_green：老判据只问 `re.search(r"\bit\b", rest)` —— `it || true` 里 `it` 在。
- 真实后果：`StateFlow(false)` 初始值即满足，等待立即返回（`sessionReady != null` 而实际未加载），后续 `loadMessages` 与加载竞态。

**10-5（结构可达，C15）** `HeadlessChatRunner.kt`
- find：`if (!started) {\n    return@withContext droppedSendResult()\n}`
- replace：`if (!started) {\n    if (false) return@withContext droppedSendResult()\n}`
- why_gate_green：`_yields_dropped_error` 只查 `"return" in text` + 一跳 helper 里有 `"Error"` / `"send_dropped_before_stream"`，块体又非空。
- 真实后果：控制流落到 `loadMessages`，读回旧回复当成新答案（CLI 侧看不到 `send_dropped_before_stream`）。

**10-6（dataflow，C16）** `HeadlessChatRunner.kt`
- find：`existingBinding = app.chatRepository.dao.getSession(sessionId)?.modelBinding,`
- replace：尾部 + `?.takeIf { false },`
- why_gate_green：老判据只判「不以 `null|false|0` 开头」+「含 `modelBinding`」。
- 真实后果：已 pin 的会话被判为「无绑定」，被重置成新会话默认 provider。

### gate 11 → `outcome_known_chain_check.py`

**11-1（family-hole，L1）** `ExecutionCoordinator.kt`
- find：`CommandResult( … outcomeKnown = result.outcomeKnown,\n            )`
- replace：闭括号后接 `.copy(outcomeKnown = true)`
- why_gate_green：CRIT_L1 只对**构造实参**调 `rhs_is_exact`，从不看闭括号后的链（CRIT_MAP 有这条检查，L1 没有 → 族洞）。

**11-2（存在性，L2）** `ChatShellExecution.kt`
- find：`outcomeKnown = result.outcomeKnown,`（`ToolResultShell(` 构造）
- replace：`outcomeKnown = result.outcomeKnown || true,`
- why_gate_green：L2 用 `"outcomeKnown = result.outcomeKnown" in shell` —— 前缀子串。

**11-3（存在性，L3）** `ToolExecutionResult.kt`
- find：`timedOut == other.timedOut && outcomeKnown == other.outcomeKnown`
- replace：`… && (outcomeKnown == other.outcomeKnown || true)`
- why_gate_green：`in_equals = "…" in toolres` —— 比较项还在字面上。

**11-4（parser-death，MAP）** `ChatShellExecution.kt`
- find：映射函数 `… =\n    ToolExecutionResult(\n … outcomeKnown = outcomeKnown,\n    )`
- replace：`)\n\n    .copy(outcomeKnown = true)`（**空行**隔开）
- why_gate_green：`re.search(r"fun ToolResultShell\.toToolExecutionResult\(\)[\s\S]*?(?=\n\n|\Z)")` 在空行处截断，`.copy` 落在函数切片之外。

**11-5（anchor，L4）** `ChatPromptAndTools.kt`
- find：`traceObserver.t7Reduce(AgentRunEvent.ToolFinished(name, resultKnown = result.outcomeKnown))`
- replace：这一行包进 `if (false)`（不可达），下一行新增 `…resultKnown = !false`（真正执行）
- why_gate_green：`re.search` 只取**首个**匹配（不可达那条），而 `resultKnown = true` 的字面量正则匹配不到 `!false`。

**11-6（scope，MAP）** `ChatPromptAndTools.kt`
- find：`return result.toToolExecutionResult()`
- replace：`return result.toToolExecutionResult().copy(outcomeKnown = true)`
- why_gate_green：`.copy` 覆写检查只作用于**定义文件**，调用点不在射程内。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### 3.1 底层：自写词法器全部删除（parser-death 轴的病原体）

| 旧 | 新 |
|---|---|
| `strip_comments`（本门自写字符扫描） | `_kotlin_lexer.mask_comments`（长度不变，字面量内容保留） |
| `_skip_string_literal` + 各处 `while … code[i] in "'\""` 手写跳过 | 删除；结构分析统一走 `_cv()` = `_kotlin_valueflow.code_view`（注释与字面量内部一律抹白，长度严格等于原文，偏移可直接回投） |
| `_match_brace` / `_matched_paren` / `extract_braced_block` 各自手写配平 | `_kotlin_ast.match_bracket` 一层封装 |
| `_stmt_text` 手写「深度 0 换行/分号/闭括号」扫描 | 共享 `expr_end`（与 `stmt_spans` / `function_body` 同一套续行规则）+ 去尾分号 |
| `_call_args_at` 手写字面量跳过 | 共享 `call_args` + 切片 |
| `RE_CONST_FALSE_FILTER` 字面量白名单 | 删除，改 `_rejecting_filters()`（`const_bool` 折叠） |

解析失败**不回退原文扫描**：`_cv()` 捕获 `KotlinLexError` → 置 `_LEX_FAILS` → `check()` 末尾统一报 `structural view failed (fail-closed)` 违规。

### 3.2 `chat_session_id_binding_check.py`：六条判据

| # | 旧形态 | 新形态 | 见证 |
|---|---|---|---|
| C3/C4 | 调用块里 `required` 子串存在 | 取 `sessionId =` 的**实参值**（括号配平），`value_sources` 的每个来源归一化后必须**等于** `viewModel.currentSessionId` | 10-1 |
| C8 | rhs 不以 `sessionId` 开头 + 含 `RE_LIVE_ID_SOURCE` | `value_sources(rhs)` 逐来源判：裸 `sessionId` → 红；非 live-id 派生 → 红 | 10-2 |
| C13 | `RE_CONST_FALSE_FILTER` 三个字面量 | `_rejecting_filters()`：`takeIf/takeUnless/filter/filterNot` 的 lambda 体经 `const_bool` 折叠，方向为「恒拒」即红 | 10-3 |
| C14 | lambda 体里出现形参标识符 | 把形参代入 `true` / `false` 各折叠一次；两次结果相同 ⇒ 谓词不看值 ⇒ 红 | 10-4 |
| C15 | 块内有 `return` + helper 含两个字面量 | 块内 **可达**（`path_feasible`）的 return，且**该 return 语句**（+ 一跳 helper 声明）含 `"Error"` 与 `"send_dropped_before_stream"` | 10-5 |
| C16 | 不以 `null|false|0` 开头 + 含 `modelBinding` | `value_sources(row_val)` 逐来源：字面量 → 红；被恒拒过滤器抹掉 → 红；不来自行读 → 红 | 10-6 |

### 3.3 `outcome_known_chain_check.py`：五条判据 + 一个派生作用域

| # | 旧形态 | 新形态 | 见证 |
|---|---|---|---|
| L1 | 只看构造实参是否精确 | 构造实参精确 **且** 闭括号后的**链式尾巴**（`_chain_tail`，跨行/跨空行）不得再提 `outcomeKnown` | 11-1 |
| L2 | `"…result.outcomeKnown" in shell` | 构造站点实参 `rhs_is_exact`（值必须**收尾**在 `result.outcomeKnown`）+ 尾巴无覆写；声明体用 `match_bracket` 取形参表 | 11-2 |
| L3 | 比较项子串存在 | 比较项存在 **且** 不被外层析取吸收（`_absorbed`：逐层圆括号组 + 所在 return 语句，`_or_absorbed` 折 `||` 臂上的常量真） | 11-3 |
| MAP | `[\s\S]*?(?=\n\n|\Z)` 正则切片 | `function_body()` 取**完整**函数体（含空行后的 `.copy`）+ 尾巴检查 | 11-4 |
| MAP 作用域 | 只查定义文件 | 从源码树**派生**所有 `.toToolExecutionResult(` 调用点，逐点要求调用链不覆写 `outcomeKnown` | 11-6 |
| L4 | 首个匹配 + `resultKnown = true` 字面量 | **每个** `ToolFinished(` 站点：`path_feasible` 过滤不可达；剩下每个都要求实参 `rhs_is_exact(…, "result.outcomeKnown")`；一个可达站点都没有也红 | 11-5 |

### 3.4 自测臂

| 门 | 改前 | 改后 |
|---|---|---|
| chat_session | 81 条自测臂 | 91 条（+10 = 8 dirty / 2 precision），标记 `CHAT_SESSION_ID_BINDING_SELFTEST=PASS (10 B7 arms)` |
| outcome | 8 个 breaker | 15 个夹具（1 compliant + 14 breaker，含 11-1..11-6 六条新臂） |

precision 臂显式证明「加牙没有误伤」：`sessionId = viewModel.currentSessionId.also { log(it) }` 仍绿（真消费，允许）；`first { loaded -> loaded }`（命名参数）仍绿。

---

## ④ 四层证据

### L1 — 门自身：`--selftest` + 真树

```
$ python3 scripts/scan/chat_session_id_binding_check.py --selftest
… selftest: B7 witness arms = 10 (dirty 8 / clean 2)
CHAT_SESSION_ID_BINDING_SELFTEST=PASS (10 B7 arms)
$ python3 scripts/scan/chat_session_id_binding_check.py .
chat_session_id_binding_check: all criteria satisfied          → rc=0

$ python3 scripts/scan/outcome_known_chain_check.py --selftest
OUTCOME_KNOWN_CHAIN_SELFTEST=PASS (15 fixtures)
$ python3 scripts/scan/outcome_known_chain_check.py .
outcome_known_chain: 5 criteria green, 0 failed
GATE=GREEN outcome_known_chain                                  → rc=0
```

邻居（同生产文件的 14 道门，`for` 循环逐门单独进程）：**14/14 rc=0**
`chat_ui_interaction / deeplink_settings_routes / exec_gate_release / file_provider_paths_coverage / file_uri_parse_guard / filepreview_print_bounded / legacy_pipeline / memory_gate_provider_fields / parts_json_funnel / provider_remember_key / session_provider_enabled / session_sheet_id_binding / stale_closure / sysinfo_state_thread`。

全量：`bash scripts/scan/scan.sh` → **112 passed, 0 failed**（本地一次跑通，未触发 S28）。
`test_scan.py` 全量 46 个测试函数：**344 pass / 0 fail**（分块驱动 `probe/t01_testscan_chunks.py`，56 个 case 的 3 个函数在沙箱 ENOSYS 风暴下假红 —— `test_auto_approve_gates` / `test_da1016_gates` / `test_da1018_gates` —— 单跑复绿：`test_da1018_gates +14 pass +0 fail`、其余两函数亦 `+0 fail`；台账 `probe/t01-testscan-ledger.jsonl`）。

### L2 — 见证反攻（机械复证，`verify7.py --mode full`）

先把两道门 `git checkout --` 回基线，跑 pre（自证我这份树也能复现失明），再换回新门跑 post：

| 轮次 | 结果 | 产物 |
|---|---|---|
| pre（基线门） | `BLIND_CONFIRMED` 6/6（chat）+ 6/6（outcome） | `probe/t01-pre-chat.json` / `probe/t01-pre-outcome.json` |
| post（新门） | **`CAUGHT` 12/12，`gate_rc=1`，`resolve_how=exact`，`restored=True`×12** | `probe/t01-post-chat.json` / `probe/t01-post2-outcome.json` |

每条都红在**应当红的那条判据**上（红字原文摘录）：

```
10-1 → ChatScreen.kt: `ChatInputArea sessionId` must pass `sessionId = viewModel.currentSessionId`
        — the value it actually hands over is `viewModel.currentSessionId.let { sessionId }`
10-2 → ChatScreen.kt: `activeSessionId` is bound to the bare route arg `sessionId`
        (`ChatViewModelStore.resolveSessionId(viewModel.currentSessionId).let { sessionId }`)
10-3 → HeadlessChatRunner.kt: the store fallback is filtered by a constant-false predicate
        (`… .getOrNull()?.takeIf { 1 == 2 } ?: return null` → takeIf { 1 == 2 })
10-4 → HeadlessChatRunner.kt: the `sessionLoaded` wait in `prompt` uses a predicate that ignores
        the value it waits for (`it || true` …)
10-5 → HeadlessChatRunner.kt: prompt()'s `!started` guard does not return a structured error
        before the drain
10-6 → HeadlessChatRunner.kt: the row value is not what the decision is fed — `existingBinding`
        is bound to `… ?.modelBinding?.takeIf { false }`, whose value is filtered away by takeIf { false }
11-1 → [FAIL] L1 … the re-wrap's own chain overrides the verdict (`.copy(outcomeKnown = true)`)
11-2 → [FAIL] L2 … declared=True stamped_from_coordinator=False
11-3 → [FAIL] L3 … field=True equals_effective=False hashCode_effective=True
11-4 → [FAIL] MAP … definition_passes=True definition_overrides=True
11-5 → [FAIL] L4 … reachable ToolFinished site(s) do not read the result's flag: (name, resultKnown = !false)
11-6 → [FAIL] MAP … ChatPromptAndTools.kt: the caller overrides the mapped value (`.copy(outcomeKnown = true)`)
```

restore 校验：`restored=True` 12/12（驱动逐条比对 `orig_sha`）；反攻结束后 `git status --porcelain` 空，`src/` 零残留。

### L3 — 变异轮：每条新判据的「精确归因」

自测臂逐条断言**恰好一条**违规且文案命中该条判据（撞错判据 = 假臂 = FAIL）。本批特有的三个「同族兄弟」也各有臂：`takeIf { 1 == 2 }` 与 `takeUnless { !false }`（同一个 fold 的两侧）、`ChatInputArea` 与 `WebAppSource.ChatAttachment`（同一个 C3/C4 两站点）、`it || true`（fold 退化）与 `loaded -> loaded`（命名参数，必须仍绿）。

### L4 — CI

见 ⑤。

---

## ⑤ CI（回填）

| 项 | 值 |
|---|---|
| run | `40263141520` / **#2710**（workflow_dispatch，`build-apk.yml`，ref `fix/b7-sessionbind-outcome`） |
| job | `121379631014`（`build`） |
| attempt | **1** |
| 结论 | **success**（03:15:09Z → 03:37:22Z，22 min 13 s） |
| head_sha | `926073cfc0016097c67c9f59364ee42c1fd81978` —— 与本地门提交 `git rev-parse HEAD` **逐字符一致** |
| 26 步 | 唯一非 success = step 23 `Publish to Releases` **skipped**（main 门控，预期） |

分支 CI 日志（`/tmp/t01_ci.log`，1,486,181 B，0 个 NUL 字节，字节级核过）：

| 读数 | 结果 |
|---|---|
| `Summary: 112 passed, 0 failed` | **恰 1** 次 |
| `ALL 344 CASES PASS` | **恰 1** 次 |
| 门头 `[N/112]` | 112 次 / 112 个**互异**编号 |
| `^ e: `（Kotlin 编译错误行） | **0** |
| 含 `FAILED` 的行 | 6 行，逐条确认**全是 PASSED 用例名子串**（`… produces FAILED without calling provider PASSED` 等），无一条真失败 |
| `selftest has teeth` | 85 处 |
| `CHAT_SESSION_ID_BINDING_SELFTEST=PASS` / `OUTCOME_KNOWN_CHAIN_SELFTEST=PASS` | 各 **1** 行 ⇒ 两门自测在 CI 里真跑且通过 |
| `10 B7 arms` / `15 fixtures` | **0** —— 臂数**没进 CI 日志**（见 R9） |

诚实记账：CI 日志里的标记行是 **`test_scan` 对「期望标记」的回显**（`✅ … selftest has teeth (MARKER)`），不是门的 stdout；所以 `(10 B7 arms)` / `(15 fixtures)` 这两个后缀不出现在 CI 里。要让它出现，需把 `test_scan.py` 的期望标记串改成带后缀的形式（见 ⑦）。


---

## ⑥ 残留（P2，各带三件）

**R1｜`_rejecting_filters` 只折「可折叠为常量」的谓词**
- 触发条件：出现 `takeIf { it.hashCode() < 0 }` / `takeIf { someConst && false }` 这类**不可折叠**但恒拒的谓词。
- 触发后方案：给 `takeIf` 谓词加值域分析（整数区间 + null-ness 联合求交，复用 `guard_feasible` 的 domain 机制）。
- 搁置理由：与 10-3 的族洞不同族（族洞是「字面量枚举不全」，此条是「谓词语义不可判」），单条 P2 建值域装置 = 装置比例超标。

**R2｜C14 的谓词 fold 对含外部符号的表达式保守放行**
- 触发条件：`first { it || retryEnabled }` 这类其中一支是外部布尔。
- 触发后方案：把外部布尔按 `false` 展开一次（最短失败路径），两次都真即红。
- 搁置理由：现阶段方向安全（默认判绿 = 可能是漏，但不会误红合法等待），且真实代码无此形态。

**R3｜C3/C4 的 `value_sources` 只展开一跳 discard 链**
- 触发条件：`viewModel.currentSessionId.let { it }.let { x -> x }` 这类多层嵌套（`value_sources` 递归是否覆盖需实测）。
- 触发后方案：确认 `peel_discarding` 的递归深度，不足则给共享层提需求（本支不许改共享层）。
- 搁置理由：共享层能力边界，改它要主会话统一评估；当前形态是「更深一层才漏」。

**R4｜C8 只看 `activeSessionId` 的首层值来源**
- 触发条件：`val tmp = resolveSessionId(...); val activeSessionId = tmp.let { sessionId }`（经局部变量中转）。
- 触发后方案：`value_sources` 加一跳局部 val 绑定追踪。
- 搁置理由：需要「绑定表」装置，本门其余判据无此需求；且中转写法在 ChatScreen 中不存在。

**R5｜C15 的「可达」只折 if/when 的常量条件**
- 触发条件：`if (started.not()) return …` 之外的等价死写法（`path_feasible` 能折 `!x`/`false`/`when`，不能折自定义扩展）。
- 触发后方案：把 `started.not()` 归一到 `!started`（谓词归一），或引入「扩展函数黑名单」。
- 搁置理由：`path_feasible` 的既有能力边界（共享层），单条见证未要求。

**R6｜`_absorbed` 只查圆括号组 + 所在 return 语句**
- 触发条件：`val ok = (outcomeKnown == other.outcomeKnown || true)` 先把吸收结果**绑定**到局部变量，再 `return ok`。
- 触发后方案：`value_candidates` 追局部 val（与 R4 同装置）。
- 搁置理由：未在真实代码出现；加了要动共享层。

**R7｜L1 的链式尾巴只看「构造之后」的 `.链`**
- 触发条件：`val c = CommandResult(…); return c.copy(outcomeKnown = true)`（覆写搬到两跳之后）。
- 触发后方案：按局部变量名追 `copy(` 写点（`value_sources` + 绑定名）。
- 搁置理由：需要绑定表；且 L1 的返回值路径在生产代码是单表达式。

**R8｜派生调用点只 walk `src/android/app/src/main/java`**
- 触发条件：`src/test` 或未来新增 source set 里出现 `.toToolExecutionResult(` 覆写。
- 触发后方案：walk 仓库根下所有 `src/**/java`。
- 搁置理由：当前测试源码不消费该映射；扩范围会给每次门运行加 ~2 倍 IO。

**R9｜臂数不进 CI 日志（既知形态，本批实测确认）**
- 触发条件：需要「新臂在 CI 里被独立见证」时。
- 触发后方案：把 `test_scan.py` 里两条期望标记改成**带臂数后缀**的形式 —— `"CHAT_SESSION_ID_BINDING_SELFTEST=PASS (10 B7 arms)"` / `"OUTCOME_KNOWN_CHAIN_SELFTEST=PASS (15 fixtures)"`。`test_scan` 的断言是 `marker in out`（`test_scan.py:2419` / `:2658`），所以更长的标记会真正校验门输出里的臂数；代价是每次加减臂都要同步这一行。
- 搁置理由：本支不改 `test_scan.py`（硬约束 #2）。已实测确认 CI 日志里 `10 B7 arms` / `15 fixtures` **0 命中**，说明当前登记的短标记只证明「自测有牙」，不证明「臂数没被悄悄删掉」。

---

## ⑦ `test_scan.py` 注册项（交主会话统一注册）

两门**均已在册**，标记串保持既有前缀 —— 因此**本支不需要新增注册**，冻结面零改动：

| 门 | 冻结断言里的标记串 | 位置 |
|---|---|---|
| `chat_session_id_binding_check` | `CHAT_SESSION_ID_BINDING_SELFTEST=PASS` | `test_scan.py` 的 `CHAT_SESSION_ID_BINDING_SELFTEST` 断言 |
| `outcome_known_chain_check` | `OUTCOME_KNOWN_CHAIN_SELFTEST=PASS` | 同上 |

本支实际输出（后缀为臂数，不影响 `marker in out`）：

```
CHAT_SESSION_ID_BINDING_SELFTEST=PASS (10 B7 arms)
OUTCOME_KNOWN_CHAIN_SELFTEST=PASS (15 fixtures)
```

推荐主会话做的**唯一**一件事（可选、纯增量，见 R9）：把两条期望标记加上臂数后缀，让「臂被人删掉」也必须过门。本支不动 `test_scan.py`。

---

## ⑧ 复现命令

```bash
ROOT=/var/minis/shared/work/meta-audit-v5/batch7/wt7/t01   # 或任意 clone 的仓库根
cd "$ROOT"

# 门自身
python3 scripts/scan/chat_session_id_binding_check.py --selftest
python3 scripts/scan/chat_session_id_binding_check.py .
python3 scripts/scan/outcome_known_chain_check.py --selftest
python3 scripts/scan/outcome_known_chain_check.py .

# 全量（本地）
bash scripts/scan/scan.sh                       # 期望 112 passed, 0 failed
python3 scripts/scan/test_scan.py               # 期望 ALL 344 CASES PASS

# 见证反攻（12 条；pre/post 各一遍即可复现本报告的判决）
cd /var/minis/shared/work/meta-audit-v5/batch7
python3 verify7.py --witnesses witnesses-B.json --repo wt7/t01 --mode full \
        --only chat_session_id_binding_check.py --out probe/t01-post-chat.json
python3 verify7.py --witnesses witnesses-B.json --repo wt7/t01 --mode full \
        --only outcome_known_chain_check.py --out probe/t01-post-outcome.json
# pre 对照：先 git checkout -- 两道门再跑同一组命令（判决应为 BLIND_CONFIRMED）

# test_scan 分块（沙箱 ENOSYS 风暴下用）
python3 probe/t01_testscan_chunks.py 8
```
