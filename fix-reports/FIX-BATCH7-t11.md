# FIX-BATCH7-t11 — `a11y_handler_contract_check` + `voice_cancel_guard_check`

- 分支：`fix/b7-a11y-voicecancel`
- 基线：`base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`
  （= main `6ac77c6b2` + 共享原语 `scripts/scan/_kotlin_valueflow.py`）
- 门提交：`e90ca37f33e7efb4c261d8856f59211eedaf5a60`（2 文件 +781/−336，生产 `.kt` 0 行）
- 改动面：**2 个门脚本**（+781 / −336），生产 `.kt` **0 行**，
  `scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` **零触碰**，
  门数 **112** / `test_scan` 夹具 **344** 均不变。

---

## ① 门与轴

| 门 | 见证 | 失明轴 |
|---|---|---|
| `a11y_handler_contract_check.py` | 6（5 失明 + 1 已抓对照） | parser-death 1 · dataflow 2 · family-hole 3（含对照） |
| `voice_cancel_guard_check.py` | 6（6 失明） | family-hole 3 · dataflow 2 · parser-death 1 |

两门同族病因（与前几批一致，本波在「值」与「可达」两处再进一步）：

- **判据锚「某段拼写存在」**：`isCallerWaiting()`、`is VisibleOnlyFlag.Read.Value ->
  read.value`、`performAction(`、`isRevokedIn(`、`sb.toString().takeIf {
  it.isNotEmpty() }`、`call.cancel()`、`watchDisposable.dispose()`、
  `return@withContext emptyList()` —— 见证只要把这些子串**留着**、把它们的
  **效果**摘掉（常量吸收、极性反转、后缀截断、double-talk 包装、类型参数化），
  门就照绿。
- **自写词法器 / 自写死分支白名单**：`code_only()` 只涂行首注释（行尾 `//`
  里的锚点照样被 `find()` 命中）；`dead_condition()` / `literal_decides()` 只
  枚举 `false` / `!true` / 整数比较；`IF_RE` + `guards()` 只解析 `if (`，
  `when (false)` 完全隐形；`brace_block()` 裸花括号计数。

本波把这两类判据整体改成「**结构 + 可达 + 值流 + 效果**」，底层全部走共享
三件套（`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`），自写的
`code_only` / `strip_comments` / `brace_block` / `balanced_block` /
`balanced_call` / `IF_RE` / `guards` / `enclosing` / `paren_inner` /
`dead_condition` / `literal_decides` / `split_top_level` **全部删除**。

---

## ② 见证原文（file / find / replace / 为什么旧门是绿的）

### `a11y_handler_contract_check.py`

**13-1 · parser-death · S1「截图回调先问调用方是否还在等」**

- file `.../accessibility/MinisAccessibilityService.kt`
- find `if (!slot.isCallerWaiting()) {`
- replace `if (false) { // !slot.isCallerWaiting()`
- 旧门为何绿：`code_only()` 只在 `line.strip()` 以 `//` / `*` / `/*` 开头时涂白；
  该行以 `if (false) {` 开头 → 整行原样保留，`body.find("isCallerWaiting()")`
  命中行尾注释，且偏移早于 `.copy(Bitmap.Config.ARGB_8888` → 判绿。

**13-2 · dataflow · H2「uiDump 兑现 `--visible-only`」**

- file `.../sandbox/offload/AccessibilityOffloadHandler.kt`
- find `is VisibleOnlyFlag.Read.Value -> read.value`
- replace `is VisibleOnlyFlag.Read.Value -> read.value || true`
- 旧门为何绿：只查子串 `'is VisibleOnlyFlag.Read.Value -> read.value' in body`
  （无行尾锚定），`walkNode` 第 5 实参仍是 `visibleOnly` → 两处都"对"。

**13-3 · dataflow · H3「tapText 把 ACTION_CLICK 的真实结果交给 plan()」**

- find `TapTextFlow.plan(n.isClickable, n.performAction(AccessibilityNodeInfo.ACTION_CLICK))`
- replace `…ACTION_CLICK) || true)`
- 旧门为何绿：第 2 实参唯一判据是 `"performAction(" not in pargs[1]`。

**13-4 · family-hole · J1「isServiceEnabled 走共享谓词」**

- file `.../accessibility/A11yState.kt`
- find `return !AccessibilityRecoveryManager.isRevokedIn(`
- replace `return AccessibilityRecoveryManager.isRevokedIn(`
- 旧门为何绿：`SHARED_JUDGE = "AccessibilityRecoveryManager.isRevokedIn("` 只做
  `in body`；`Enabled` 与 `Revoked` 的极性差（`!`）完全没有判据。

**13-5 · family-hole · R1「slot 释放被所有权守卫保护」**

- file `.../accessibility/AccessibilityRecoveryManager.kt`
- find `if (promptToken != token) return null`
- replace `if (1 == 2 && promptToken != token) return null`
- 旧门为何绿：`literal_decides()` 把条件按 `&&`/`||` 切后只认四元组
  `("true","false","!true","!false")`，`"1 == 2"` 不在其中 → 返回 False。
- 审计方自疑（同为判据缺口）：`if (promptToken != token) Unit`（保留比较、
  不写 `return`）旧门也绿 —— 本波一并堵掉。

**13-6 · family-hole · 对照臂（修前 CAUGHT，修后必须仍 CAUGHT）**

- replace `if (!true && promptToken != token) return null`
- 旧门靠 `"!true"` 命中白名单抓住。

### `voice_cancel_guard_check.py`

**01-1 · family-hole · A2「watcher 钩子真的取消 Call」**

- file `.../provider/voice/VoiceProvider.kt`
- find `watchJob.invokeOnCompletion { _ -> call.cancel() }`
- replace `watchJob.invokeOnCompletion { cause -> if (cause == null) call.cancel() }`
- 旧门为何绿：`dead_condition()` 只认 `false` / `!true` / 整数比较；`cause ==
  null` 无常量可折 → 判"守卫存活"。而 `watchJob` 跑 `awaitCancellation()`，只会
  被取消结束 ⇒ `cause` 恒非 null。

**01-2 · dataflow · B「转写结果成为返回值」**

- find `val data = executeRequest(builder.build())\nreturn VoiceInputResponse(text = parseChatAsrTranscript(data))`
- replace 绑定 `val parsed = …` 后 `return VoiceInputResponse(text = parsed.take(0))`
- 旧门为何绿：`re.search(r"text\s*=\s*%s\b" % bound)` —— `.` 是非单词字符，
  `text = parsed.take(0)` 命中 `parsed\b`。

**01-3 · family-hole · D「取消异常必须原样上抛」**

- file `.../provider/openrouter/OpenRouterModelsApi.kt`
- find `catch (e: kotlinx.coroutines.CancellationException) { … throw e`
- replace `if (e.message == "__never__") throw e` + `return@withContext emptyList<LLMModel>()`
- 旧门为何绿：①`dead_condition("e.message == \"__never__\"")` 无常量；
  ②`re.search(r"return@withContext\s+emptyList\(\)")` 要求紧跟 `()`，被
  `<LLMModel>` 直接失配。

**01-4 · parser-death · A2「死分支不止 `if`」**

- find `val watchDisposable = watchJob.invokeOnCompletion { _ -> call.cancel() }`
- replace `… { _ -> when (false) { true -> call.cancel() else -> Unit } }`
- 旧门为何绿：`IF_RE = re.compile(r"(?<![\w.])if\s*\(")`，`guards()` 只扫 `if (…)`，
  `when (false)` 无守卫可查 → 循环不执行。

**01-5 · family-hole · A2/E「成功路径必须 dispose 钩子」**

- find `watchDisposable.dispose()\n            watchJob.cancel()`
- replace `if (false) watchDisposable.dispose()\n…`
- 旧门为何绿：`if "watchDisposable.dispose()" not in body` —— 只判存在，没装
  `call.cancel()` 那套死条件检查。

**01-6 · dataflow · E「空转写守卫覆盖 ASSEMBLED transcript」**

- file `.../provider/voice/VoiceProviderVendors.kt`
- find `val transcript = sb.toString().takeIf { it.isNotEmpty() } ?: throw …`
- replace `val transcript = sb.append(" ").let { sb.toString().takeIf { … } ?: throw … }`
  并 `return VoiceInputResponse(text = transcript.trim(), …)`
- 旧门为何绿：`XUNFEI_TRANSCRIPT_GUARD.search(body)` 在这段 `let { … }` 内部**精确
  命中**（守卫一字未改），返回侧也不含裸 `text = sb.toString()`。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### `a11y_handler_contract_check.py`

| # | 旧判据 | 新判据 |
|---|---|---|
| 13-1 | `body.find("isCallerWaiting()")`，注释只涂行首 | `if_blocks(cv)` 里找**活的**守卫：条件经 `const_bool` 不得为常量、`strip_parens` 后必须以 `!` 开头、`exits_like()` 证明守卫体真的 `return`/`throw`；再与 `.copy(Bitmap.Config.ARGB_8888` 比偏移。视图改用 `mask_code`（注释 + 字符串内部全抹，**不再有「行首」这个概念**） |
| 13-2 | 一行子串 `'is VisibleOnlyFlag.Read.Value -> read.value'` | `when_branches` + `expr_end` 取**分支的值**：`Read.Value` 分支必须恰为 `<when 主语>.value`（`strip_parens` 全等）；`Read.Absent` 分支 `const_bool` 必须为 `True`；`Read.Invalid` 分支必须 `return err(…, exit = 2)` 且回显 `${read.raw}`。`walkNode` 实参仍按局部名比对（保留 wide-1024 那一层） |
| 13-3 | `"performAction(" in pargs[1]` | `sole_call(pargs[1], "performAction")`：实参必须**就是**这次调用（`match_bracket` 配平后正好到表达式末尾），任何 `\|\| true` / 后缀 / 包装都判红 |
| 13-4 | `SHARED_JUDGE in body` | 找 `isServiceEnabled` 的每条 `return`：只要值里出现 `isRevokedIn`，就必须是 `!` + 恰好一个 `isRevokedIn(…)` 调用（`expr_end` 定界，`sole_call` 判断无吸收）。极性丢失 / `\|\|` 吸收 / `.let { … }` 尾巴都红 |
| 13-5 | `literal_decides()` 四元组白名单 | `guard_decided_by_literal()`：每个顶层 `&&`/`\|\|` 操作数走 `const_value` 常量折叠（`1 == 2`、`! true`、`0 > 1`、`"a" == "b"` 全折）；守卫必须**同块层、在该释放点之前、且真的退出**（`exits_like`，`if (…) Unit` 判红）。释放点位置改由文本偏移派生（不再按行索引） |

### `voice_cancel_guard_check.py`

| # | 旧判据 | 新判据 |
|---|---|---|
| 01-1 | `dead_condition()` 白名单 | `why_dead()` = `dead_guards()`（`guard_feasible` 折叠 + `when` 主语常量）+ `cause_gated()`（守卫条件不得引用 lambda 形参 —— 即不得以"完成原因/异常自带内容"当开关）。只有**存在至少一条活的 `call.cancel()`** 才算过 |
| 01-2 | `text = <绑定>\b` 单词边界 | `response_text_args()` 取 `text` 实参的**值**：`asr_value_kind()` 判 ok/literal/other —— 只允许「绑定本身 / 解析调用本身 / 无损改写（trim 族 + `!!`）」，`.take(0)` 一类后缀判红；解析结果被绑定却未落地 = DISCARDED（按 offset 判解析调用是否被返回值 span 或绑定覆盖） |
| 01-3 | `dead_condition` + `emptyList\(\)` | 重抛必须无条件（`cause_gated(…, 捕获变量)` + `dead_guards`）；空集合兜底用 `EMPTY_FALLBACK_RE`，覆盖 `emptyList` / `emptySet` / `listOf` / `mutableListOf` / `arrayListOf` + 任意类型参数 + `kotlin.collections.` 前缀 |
| 01-4 | `IF_RE` + `guards()` 只认 `if (` | 全走 `enclosing_conditions()`（if / when 分支）+ `when_branches()` 主语常量折叠：`when (false) { true -> … }` 的 `call.cancel()` 判死 |
| 01-5 | 只查 `dispose()` 子串存在 | `why_dead(body, dispose_pos)` 必须为 None（外加原有"必须存在"） |
| 01-6 | `XUNFEI_TRANSCRIPT_GUARD.search(body)` | ①builder 的每个 `sb.append/appendLine/insert` 实参不得是常量（在字面量视图上 `const_value` 判）；②返回值必须恰是某个绑定（`strip_parens` 全等标识符，任何 `.trim()` 后缀判红）；③该绑定的 RHS 必须**从头**匹配守卫（`XUNFEI_TRANSCRIPT_GUARD.match`，`sb.append(…).let { … }` 包装判红） |

**共用装置变更（两门）**

- `views(raw)` = `code_view`（结构）+ `literal_view`（字面量），两者**长度严格
  相等**，位置互投：结构分析在抹空字符串的视图上做，读 `"visible-only"` 这类
  契约字面量时切回字面量视图的同一区间。
- 词法失败（`KotlinLexError`）→ 直接报违规并退出 1，**不回退原文扫描**。
- `exits_like()`：修掉了"带块分支的 `return` 深度比 if 深一层"的坑（`returns_exiting`
  默认把守卫体里的 `return` 当 lambda 内裸 return）。
- 删掉自写 `call_args_of` 的括号扫描 / `split_top_level`，改用共享
  `match_bracket` + `split_top` + `call_args`；`expr_end` 的顶层逗号再切一刀
  （`text = x, language = y` 这类具名实参不会被整段卷进表达式）。

---

## ④ 四层证据

**L1 本门（真树 + 自测）**

```
python3 scripts/scan/a11y_handler_contract_check.py .        → rc=0（7 个面全绿）
python3 scripts/scan/a11y_handler_contract_check.py --selftest
  A11Y_STATUS_SELFTEST=PASS / A11Y_JUDGE_SINGLE_SOURCE_SELFTEST=PASS
  A11Y_SLOT_IDENTITY_SELFTEST=PASS / A11Y_VALUE_FLAG_SELFTEST=PASS
  A11Y_HANDLER_CONTRACT_SELFTEST=PASS (49 arms)
python3 scripts/scan/voice_cancel_guard_check.py .           → rc=0（PASS … all wired）
python3 scripts/scan/voice_cancel_guard_check.py --selftest  → VOICE_CANCEL_GUARD_SELFTEST=PASS (27 arms)
```

新臂（每条都在变异代码上"恰好红在自己那条判据上"，带 needle 断言，不是只看
"有没有红"）：

| 臂 | 期望判据片段 |
|---|---|
| b7/service guard hidden in a trailing comment | `LIVE` |
| b7/service guard absorbed by a constant | `LIVE` |
| b7/service guard body no longer returns | `LIVE` |
| b7/handler Read.Value absorbed | `Read.Value branch does not yield the caller's spelling` |
| b7/handler Absent default inverted | `documented default` |
| b7/handler plan() outcome absorbed | `FABRICATED self-click outcome` |
| b7/judge polarity dropped | `does not RETURN the negation` |
| b7/slot guard decided by `1 == 2` | `decided by a literal` |
| b7/slot guard decided by `! true` | `decided by a literal` |
| b7/slot guard keeps the comparison but rejects nothing | `does not EXIT` |
| 01-1 cause-gated cancel hook | `dead guard` + `the hook fires and cancels nothing` |
| 01-4 when(false) dead cancel hook | `dead guard` |
| 01-5 dead dispose guard | `dispose() sits behind the dead guard` |
| 01-2 truncated parse value | `not the ASR result any more` |
| 01-3 message-gated rethrow | `dead guard` + `returns the empty fallback` |
| 01-6 padded builder | `builder is fed a literal` + `GUARDED transcript is rewritten` |

**L2 反攻（硬验收）**

```
python3 verify_batch5.py --witnesses …/witnesses-A.json --repo . --mode full \
  --only a11y_handler_contract_check.py  --out probe/t11-a11y_handler_contract_check.json
  [1..6] 13-1…13-6 -> CAUGHT (rc=1, exact)      SUMMARY {'CAUGHT': 6}

python3 verify_batch5.py --witnesses …/witnesses-B.json --repo . --mode full \
  --only voice_cancel_guard_check.py     --out probe/t11-voice_cancel_guard_check.json
  [1..6] 01-1…01-6 -> CAUGHT (rc=1, exact)      SUMMARY {'CAUGHT': 6}
```

`restored` 字段 **12/12 True**；收工后 `git status --porcelain src/` 为**空**
（反攻脚本零残留）。修前修后对照：a11y 5 FALSIFIED + 1 CAUGHT → **6/6 CAUGHT**；
voice 6 FALSIFIED → **6/6 CAUGHT**。

**L3 邻门（8 道，全 rc=0）**

```
a11y_handler_contract_check          rc=0
speech_active_engine_check           rc=0
tts_voice_and_sanitizer_check        rc=0   （--root . 形态）
offload_cli_args_check               rc=0
offload_host_path_check              rc=0
offload_media_b64_wire_check         rc=0
claim_release_guard                  rc=0
calendar_events_nullable_predicate_check  rc=0（--root . 形态）
```

**L4 全量**

```
bash scripts/scan/scan.sh  → rc=0  「Summary: 112 passed, 0 failed」（0 个 ❌）
```

`test_scan.py`：**整跑必被 stall-guard 杀**（父进程空转等子进程 180s 无输出无
CPU，本波复现两次）。健康路径 = 分块驱动 `probe/t11_testscan_chunks.py`
（`sys.argv = ["test_scan.py", ROOT]` 必传，否则 `REPO_ROOT` 退化、出现 ~90 条
假红；每函数 `SIGALRM` 170s 守卫 + ledger 断点续跑 + ENOSYS 重试）：
**46/46 个 `test_*` 函数全部跑过，344 条用例**，其中 4 条 fail 全部落在窗口期
环境抖动的 3 个函数（`test_auto_approve_gates` / `test_da1016_gates` /
`test_da1018_gates`），**单进程复跑全部复绿**（2 + 18 + 14 pass / 0 fail）——
与 B7-t01 同款 S28 抖动形态。权威 `ALL 344 CASES PASS` 见 §5 CI 日志。

---

## ⑤ CI

- 分支 push → 手动 dispatch `build-apk.yml` @ `fix/b7-a11y-voicecancel`。
- run `40271136746` / **#2721** / job `121403996623` / attempt 1，**success**
  （created 2026-10-26T04:50:44Z，~22 min）。
- `run.head_sha = e90ca37f33e7efb4c261d8856f59211eedaf5a60`，与本地
  `git rev-parse HEAD` **逐字符一致**（防并发假绿）。
- 26 步，唯一非 success = step 23 `Publish to Releases` **skipped**（main 门控）。
- job 日志 1,486,557 B，0 个 NUL 字节，逐条判读：
  - `Summary: 112 passed, 0 failed` 恰 **1** 条；
  - `ALL 344 CASES PASS` 恰 **1** 条；
  - ` e: `（Kotlin 编译错误）= **0**；
  - 门头 `[N/112]` 编号互异 **112** 个；
  - 含 `FAILED` 的 6 行全部是 PASSED 用例名子串（`slot denied produces FAILED
    without calling provider PASSED`、`reason PERSISTENCE_FAILED maps to
    persistence_ PASSED`、`phase FAILED maps to Failed PASSED`、`terminal FAILED
    maps to Failed PASSED`、`reason EXECUTION_FAILED maps to all_fallbacks_
    PASSED`、`the fixed tail still reaches a legal FAILED terminal PASSED`）——无真失败；
  - test_scan 两门 `selftest has teeth` 行各恰 1：
    `a11y_handler_contract_check selftest has teeth (selftest passed)`、
    `voice_cancel_guard_check selftest has teeth (VOICE_CANCEL_GUARD_SELFTEST=PASS)`
    ⇒ 两门新自测逻辑在 CI 里真跑且全绿。
- 门自证臂数行（`A11Y_HANDLER_CONTRACT_SELFTEST=PASS (49 arms)` /
  `VOICE_CANCEL_GUARD_SELFTEST=PASS (27 arms)`）在 CI 日志 0 命中 —— 门自证
  stdout 不进 CI 日志（`test_scan` 只断言并回显标记），臂数由本报告 §1/§4
  承载；注册项见 §7（同 B6/B7 前几批 R 形态）。

---

## ⑥ 残留（P2，各带三件）

**R1 · `exits_like` 只认 `return` / `throw`，不认 `break`/`continue` 形态的"守卫"**
- 触发条件：有人把"早退守卫"改写成 `loop@ for` + `break@loop` 实现同一语义。
- 触发后方案：给 `exits_like` 增加 `break`/`continue` 的标签归属判定（要区分
  退出的是哪个循环），或直接在该门改用"守卫体末尾必须把控制流交给 caller"的
  显式白名单。
- 搁置理由：当前仓里所有守卫都是 `return null` 早退；为未出现过的写法扩判据
  属于装置超标（修复门纪律：P2 不建重装置）。

**R2 · `cause_gated` 用"条件里出现 lambda 形参"近似"依赖完成原因"**
- 触发条件：有人在钩子里写 `if (cause != null) log(cause); call.cancel()`（日志
  用法，cancel 本身无条件）。
- 触发后方案：只对**包住 `call.cancel()` 的那条 if/when 的 then 块**判形参依赖，
  并允许"日志型兄弟语句"（当前判据已是"存在至少一条活的 cancel 才放行"，只有当
  **所有** cancel 都被判死才红，故实际误伤面很小）。
- 搁置理由：现在的规则是 fail-closed（宁可让人写无条件 cancel），且要求"全部
  cancel 都被门挡死"才报，误伤不可达。

**R3 · `asr_value_kind` 的无损改写白名单只有 trim 族 + `toString` + `!!`**
- 触发条件：出现 `.removeSuffix("\n")`、`.replace(Regex("\\s+$"), "")` 这类仍然
  无损的规范化。
- 触发后方案：把白名单扩成"已知不改变非空性"的集合，或改成"必须证明不会把
  非空串变空"的形式化判定。
- 搁置理由：现仓只有 `parseChatAsrTranscript` 自带 trim，返回值直接是调用本身；
  为假想写法扩白名单会让判据更松。

**R4 · `when` 主语的常量判定只覆盖"分支常量不等于主语常量"这一形**
- 触发条件：`when (true) { x > 3 -> …; else -> … }` 这类"主语是常量但分支是
  谓词"的写法。
- 触发后方案：把 `dead_guards` 的主语分支扩成"主语常量 ⇒ 只保留能命中的分支
  （谓词分支视为不可判定，保守放行）"的完整语义。
- 搁置理由：本族见证全是 `when (false) { true -> … }` 的直白形；扩语义收益低。

**R5 · 讯飞 builder 只拦常量实参，不拦"由常量派生的变量"**
- 触发条件：`val pad = " "; sb.append(pad)`。
- 触发后方案：对 `sb.append` 实参跑 `value_sources` + 一跳局部 val 溯源（共享层
  已有原语），常量派生即红。
- 搁置理由：当前警方是"直接字面量"，而见证形态是直接字面量；跨变量溯源属
  装置升级，按修复门纪律登记待触。

**R6 · `response_text_args` 只取 `text = …` 具名实参的位置参数形态**
- 触发条件：有人改成位置参数 `VoiceInputResponse(transcript, request.language)`。
- 触发后方案：按位置取第 1 个实参（构造器签名派生），并保留具名形态。
- 搁置理由：`VoiceInputResponse` 目前所有调用点都用具名 `text =`；改位置参数会
  同时触发"无法读取 text 实参 → fail-closed"的红，不会静默放行。

**R7 · `a11y` 门仍用"行窗口 8 行"定位 slot 释放点所在块**
- 触发条件：`clearSlotLocked` 的守卫与释放之间插入 > 8 行无关代码。
- 触发后方案：改用 `function_body` + `stmt_spans` 取整个函数体做块级判定。
- 搁置理由：现函数体只有 4 行；窗口是旧判据的定位粒度遗留，扩成函数体级需要
  同时重写 `scan_slot_identity` 的其它锚点，收益不抵风险。

**R8 · 门自证臂数只进 stdout，`test_scan.py` 尚无对应断言（注册交主会话）**
- 触发条件：并集收口时若不加注册，`A11Y_HANDLER_CONTRACT_SELFTEST` /
  `VOICE_CANCEL_GUARD_SELFTEST` 的臂数在 CI 日志里无人见证（同 B6/B7 前几批）。
- 触发后方案：见 §7 的注册项（各一行）。
- 搁置理由：`test_scan.py` 在本批是冻结面，硬约束禁止本支修改。

---

## ⑦ `test_scan.py` 注册项（交主会话统一注册）

现有断言（本支**未改动**，且仍然通过）：

```python
("a11y_handler_contract_check", "selftest passed", (REPO_ROOT,)),
("voice_cancel_guard_check", "VOICE_CANCEL_GUARD_SELFTEST=PASS", (), (REPO_ROOT,)),
```

建议**新增**（本支门输出已带稳定标记 + 臂数，字符串前缀与旧断言兼容）：

```python
("a11y_handler_contract_check", "A11Y_HANDLER_CONTRACT_SELFTEST=PASS (49 arms)", (REPO_ROOT,)),
("voice_cancel_guard_check", "VOICE_CANCEL_GUARD_SELFTEST=PASS (27 arms)", (), (REPO_ROOT,)),
```

两门的臂数会随新臂增减，若不想频繁改断言，可退化为断言前缀
`A11Y_HANDLER_CONTRACT_SELFTEST=PASS` / `VOICE_CANCEL_GUARD_SELFTEST=PASS`
（后者已存在）。**门数 112 不变，`scan.sh` 无需重编号。**

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t11

# 共享原语自测（必须全绿才动手）
python3 scripts/scan/_kotlin_lexer.py --selftest        # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest          # 32 arms
python3 scripts/scan/_kotlin_valueflow.py               # 26 arms

# L1 本门
python3 scripts/scan/a11y_handler_contract_check.py .
python3 scripts/scan/a11y_handler_contract_check.py --selftest
python3 scripts/scan/voice_cancel_guard_check.py .
python3 scripts/scan/voice_cancel_guard_check.py --selftest

# L2 反攻（12 条见证必须全 CAUGHT 且 restored=True）
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-A.json \
  --repo . --mode full --only a11y_handler_contract_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch7/probe/t11-a11y_handler_contract_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-B.json \
  --repo . --mode full --only voice_cancel_guard_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch7/probe/t11-voice_cancel_guard_check.json

# L3 邻门（8 道）
for g in a11y_handler_contract_check speech_active_engine_check offload_cli_args_check \
         offload_host_path_check offload_media_b64_wire_check claim_release_guard; do
  python3 scripts/scan/$g.py . || echo "RED $g"
done
python3 scripts/scan/tts_voice_and_sanitizer_check.py --root .
python3 scripts/scan/calendar_events_nullable_predicate_check.py --root .

# L4 全量
bash scripts/scan/scan.sh                       # 112 passed, 0 failed
python3 /var/minis/shared/work/meta-audit-v5/batch7/probe/t11_testscan_chunks.py 8
                                                # 分块跑（整跑会被 stall-guard 杀）
git status --porcelain src/                     # 必须为空
```
