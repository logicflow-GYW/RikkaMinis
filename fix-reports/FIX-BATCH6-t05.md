# FIX-BATCH6-t05 — `tts_voice_and_sanitizer_check` / `katex_ready_race_check` 判据加固

支线：`fix/b6-tts-katex`（基线 `base/batch5-shared` @ `e145ec961` = main `6ac77c6b2` + 共享值流原语）
门提交：`c367faf6f`；本报告单独提交。
改动面：**仅 2 个门脚本**（`scripts/scan/tts_voice_and_sanitizer_check.py`、
`scripts/scan/katex_ready_race_check.py`）+ 本报告。生产 `.kt` **0 行**、
`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` **零触碰**、门 112 / 夹具 344 不变。

---

## ① 门与轴

外部见证 13 条（tts 7 / katex 6），全部经主会话机械复证；本支逐条重打复证，结论一致。

| # | 门 | 见证 | 轴 | 被攻判据 |
|---|----|------|----|----------|
| 1 | tts | 06-1 | dataflow | `Rule B`：`sanitize(` 子串 + 顺序 + `if (speakable.isBlank())` 字面 |
| 2 | tts | 06-2 | dataflow | `Rule A`：`resolveTargetLocale(explicitLanguage` 子串 |
| 3 | tts | 06-3 | anchor | `resolve_default_ok`：只抽括号内默认 token，不看 `)` 之后 |
| 4 | tts | 06-4 | scope | `check_error_table`：自写 `function_body` 从 `{` 切片（丢 `= when (0)` 的 subject） |
| 5 | tts | 06-5 | family-hole | `check_quick_test_clip` C1：`guard_before` 只看 is_cancel_read |
| 6 | tts | 06-6 | parser-death | `strip_comments` 不识别 `"""` + 条件赋值只看 `line.strip().startswith` |
| 7 | tts | 06-7 | dataflow | （对照，**原 CAUGHT**）`ARM_RESULT_RE` 吃掉 `.let` |
| 8 | katex | 09-1 | family-hole | `CRIT_A1`：`'readyGate.markPageFinished()' not in opf` |
| 9 | katex | 09-2 | order | `CRIT_A3`：`conditional_context` 对 `run { return }` 闭环后返回 None |
| 10 | katex | 09-3 | dataflow | `CRIT_A4`：`withTimeoutOrNull(...)` 的实参**完全没读** |
| 11 | katex | 09-4 | dataflow | `CRIT_B2`：`'aliases[sessionId]' in resolveKey` 纯子串 |
| 12 | katex | 09-5 | family-hole | `CRIT_B4`：`in_discarded_lambda` 只认 `.also/.apply` 块头 |
| 13 | katex | 09-6 | parser-death | `strip_comments` 两行正则（`"http://"` 后的整行被当注释抹掉） |

轴分布：dataflow 5 · family-hole 4 · parser-death 2 · scope 2 · anchor 1 · order 1。

**两门共症**：判据锚「某个 token 被写出」，缺陷动「这个 token 的**值**被求值／传递／消费」。
12 条失明见证里没有一条删掉被检查的 token——它们全都让 token 的效果消失。

---

## ② 见证原文（补丁 + 为何门是绿的）

### 06-1（dataflow）`TextToSpeechManager.kt`
```kotlin
find:     val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
replace:  val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext)).let { text }
```
门绿原因：Rule B 只比 `sanitize_at < engine_at` 与 `'if (speakable.isBlank()) return false' in speak`；
从不验证 `speakable` 的终值是否真的是 `sanitize()` 的返回值。`.let { text }` 让 sanitize 白跑。

### 06-2（dataflow）`TextToSpeechManager.kt`
```kotlin
find:     applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable))
replace:  TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable)
          applyLanguage(TtsLanguagePolicy.resolveTargetLocale(null, speakable))
```
门绿原因：Rule A 是子串存在性（第一行裸调用满足），第二行传 `null` 不触发任何禁止项。

### 06-3（anchor）`SpeakOffloadHandler.kt`
```kotlin
find:     rate = (rate ?: DEFAULT).toFloat(),
replace:  rate = (rate ?: DEFAULT).toFloat() * 0f,
```
门绿原因：`resolve_default_ok` 用 `\(\s*flag\s*\?:\s*([^)\s]+)\s*\)` 抽到 `DEFAULT` → 查到
`const val DEFAULT = 1.0` 即 True，**完全不看右括号之后**是否接了 `* 0f` / `.let { 0f }`。

### 06-4（scope）`SystemSpeechRecognitionEngine.kt`
```kotlin
find:     internal fun mapError(code: Int): RecognitionError = when (code) {
replace:  internal fun mapError(code: Int): RecognitionError = when (0) {
```
门绿原因：自写 `function_body(code, 'fun mapError(')` 从 `src.find('{', start)` 起切到行首 `}`，
`= when (0)` 这段**位于 `{` 之前**，整个不在切片内；`when_arms` 只看分支表 → 10/11/12/13 全「正确」。

### 06-5（family-hole）`QuickTestSheet.kt`
```kotlin
find:     if (!SynthClipPolicy.maySynthesize(cancelled.get())) {
replace:  if (false && !SynthClipPolicy.maySynthesize(cancelled.get())) {
```
门绿原因：`guard_before` 抽到条件后只问 `is_cancel_read`（正则命中 `cancelled.get` 即真），
对 `false &&`、常量假、极性反转一律不看。

### 06-6（parser-death）`SpeakOffloadHandler.kt`
```kotlin
find:     tts.speechRate = tuning.rate
replace:  val raw = """\"//"""; if (args.getDouble("rate") != null) tts.speechRate = tuning.rate
```
门绿原因**双重**：①`strip_comments` 不识别 `"""`，第 2 个 `"` 开后一串到 `args.getDouble("` 的第 1 个
`"` 才关 → `if (` 在它眼里位于字符串内；②条件赋值只判 `stripped.startswith(('if ','if(' …))`，同一行前面有
`val raw = …;` 时 `startswith` 立即为假。

### 06-7（对照，原 CAUGHT）`SystemSpeechRecognitionEngine.kt`
`13 -> RecognitionError.LANGUAGE_UNSUPPORTED.let { RecognitionError.UNKNOWN }` 原判据能抓（`ARM_RESULT_RE`
把 `.let` 吸进标识符，最后一段变 `let`）。本支改写后**必须继续抓**——已作为 selftest 臂固化。

### 09-1（family-hole）`KatexWebViewPool.kt`
`if (false) readyGate.markPageFinished()` —— CRIT_A1 只查子串在不在 `onPageFinished` 体里。

### 09-2（order）`KatexWebViewPool.kt`
在原子步之后插 `run { return }` —— `conditional_context` 只看外层块头，`run { return }` 已闭合，
返回 None → 完成点判绿（而它其实永远到不了，刚取走的 waiter 永不唤醒）。

### 09-3（dataflow）`KatexWebViewPool.kt`
`withTimeoutOrNull(timeoutMs)` → `withTimeoutOrNull(0L)` —— 门用 `m.group(1)` 抓了预算却**从不读它**。

### 09-4（dataflow）`ChatViewModelStore.kt`
`aliases[sessionId] ?: sessionId` → `aliases[sessionId].let { sessionId }` —— CRIT_B2 只查
`'aliases[sessionId]' in rk`，查询发生、答案被丢。

### 09-5（family-hole）`ChatViewModelStore.kt`
`?.let { resolveKey(it) }` → `?.let { resolveKey(it); it }` —— `in_discarded_lambda` 只认块头以
`.also` / `.apply` 结尾，`.let` 直接放行（此时发布的是未解析的草稿键）。

### 09-6（parser-death）`ChatViewModelStore.kt`
`…?.let { resolveKey(it) } ?: "http://".let { activeSessionIdInternal }` —— 两行正则剥注释器把
`"http://"` 之后的整行真代码抹成注释，任何破坏性覆写都隐形。

---

## ③ 判据改造（旧形态 → 新形态）

### tts_voice_and_sanitizer_check（+7 门 → 32 臂）

先按要求**删掉全部自写词法/结构件**：`strip_comments`、`mask_strings`、`brace_map`、
`enclosing_blocks`、`matching_paren`、`statement_end`、`guard_before`、自写 `function_body`、
`split_top_level`、`when_arms`、`arm_result`、`resolve_default_ok` 的括号正则。
底层一律走共享层：`_kotlin_lexer.{code_view,literal_view}`、
`_kotlin_ast.{function_body,if_blocks,match_bracket,split_top,strip_parens,when_branches,stmt_spans,expr_end,const_value,enclosing_conditions,tail_expr}`、
`_kotlin_valueflow.{value_sources,path_feasible,brace_depth,guard_feasible,contains_ident}`。

| 判据 | 旧形态（可被绕过） | 新形态（效果导向） |
|---|---|---|
| Rule B（06-1） | `sanitize(` 子串 + 顺序 + `isBlank` 字面存在 | 从 `tts?.speak` **首个实参**回溯 provenance：`value_sources` + 一跳局部 `val` 绑定，走不回 `sanitize(` 即红；`isBlank` 守卫的 subject 同样必须溯源（raw 参数即红） |
| Rule A（06-2） | `resolveTargetLocale(explicitLanguage` 子串 | ①`applyLanguage(<arg>)` 的实参必须派生自 `resolveTargetLocale(...)`；②该调用的**首实参**必须 `contains_ident(explicitLanguage)`；③`resolveTargetLocale` 结果被丢弃单列违规 |
| A-07 默认值（06-3） | 括号内 token + `const val D = 1.0` | **整值表达式**：剥掉纯强制转换（`.toFloat()/.toDouble()/.toInt()/.toLong()`）后必须 `fullmatch` `flag|getDouble("flag") ?: TOKEN`；TOKEN 折叠必须 1.0；`* 0f`、`.let{}`、任何尾随运算 → 「mangled」 |
| A-07 赋值（06-6 第二半） | `line.strip().startswith(('if ',…)) or '?.let' in stripped` | 每个字段**恰一次**写入（0 或 ≥2 都红）；条件化由 `enclosing_conditions`（含 `when`/裸 `if`）+ 语句头 `?.` + `path_feasible` 三条独立判据认定 |
| A-06 错误表（06-4 / 06-7） | 自写 `function_body` 从 `{` 切片 + 自写 `when_arms` | 共享 `when_branches` + 显式取 `when (<subject>)` 的括号内容：subject 必须派生自形参且非常量；`expr` 体的**表尾**（`}` 之后到表达式结束）必须为空（`.let{…}` 吃掉返回值即红）；臂结果按 `value_sources` 全来源解析 |
| 词法（06-6 第一半） | 自写 `strip_comments`（不识别 `"""`） | `code_view()` / `literal_view()`；`KotlinLexError` → `[tts-files]` 违规（fail-closed），绝不回退裸文本 |
| 文件缺失 | 静默跳过 | `[tts-files] … not found` |

### katex_ready_race_check（+6 门 → 23 臂）

删掉自写 `strip_comments`（两行正则）、`find_block`（`src.find('{', start)`）、
`enclosing_heads`、`conditional_context`、`in_discarded_lambda`、自写 `function_body`。

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| A1（09-1） | 子串在 `onPageFinished` 体内 | 命中点必须 `path_feasible`（常量折叠死分支）**且**前面没有「无条件退出」语句（裸 `return`/`throw`、`run { return }` 这类非局部返回；`if (ready) return` 由局部常量环境折叠成恒真守卫） |
| A3（09-2） | `conditional_context(...) is None` 即绿 | 同步块要求 `ready = true` + `tail_expr == waiter`；完成点：不得被非空判条件包围（`d != null` 豁免）、`path_feasible`、前面无无条件退出 |
| A4（09-3） | `withTimeoutOrNull.*` 只读 lambda | 实参必须 `contains_ident(timeoutMs)`、常量必须 > 0、比较极性必须 `== null`（`!= null` 红） |
| B2（09-4） | `'aliases[sessionId]' in resolveKey` | `resolveKey` 的**返回值**（表达式体或块体 `return`）经 `value_sources` 必须含 `aliases[sessionId]` |
| B4（09-5/09-6） | `in_discarded_lambda`（块头 `.also/.apply`） | getter 表达式的**每个** `value_sources` 都必须含 `resolveKey(`：`?.let { resolveKey(it); it }` → tail 是 `it` → 红；`?: "http://".let{…}` 右支不含 → 红；`.also{}` → receiver 不含 → 红 |
| 词法/文件 | 正则抹注释、缺文件静默 | `code_view()`；`KotlinLexError` 或文件缺失 → 违规（fail-closed） |

**装置自伤记录（同类第 3 次，已固化）**：`match_bracket` 返回**闭括号自身下标**，
`_following_lambda(text, pos)` 首版从 `pos` 起扫，永远看不到 `{` → `withTimeoutOrNull` 臂集体假红。
修法 = 从 `pos + 1` 起扫。教训：共享原语的下标语义（闭括号 vs 开括号）必须当场用小 fixture 打印验证。

---

## ④ 四层证据

### L1 门自证（`--selftest`，CI 里只断言收尾标记，故臂数在此承载）

- `tts_voice_and_sanitizer_check`：**32 条 PASS / 0 FAIL**，收尾
  `tts_voice_and_sanitizer_check selftest: TTS-VOICE GATE ALL ARMS MATCH`
  - 原有 22 臂（pre-fix / compliant / rule B / rule A / rule A-handler / rule C / exemption /
    A-06 ×5 / A-07 ×2 / clip ×7）全部保留并通过；
  - 新增 9 臂：06-1、06-1b、06-2、06-3、06-4、06-4b、06-5、06-6 + 1 条 green 等价拼写
    （`?: 1.0` 内联，不再走 resolver）；
  - 新增 fail-closed 臂：源文件不可词法解析 → 期望恰好 `{tts-files}`。
- `katex_ready_race_check`：**23 条 OK / 0 EXPECTED 失配**，收尾
  `KATEX_READY_RACE_SELFTEST=PASS`
  - 原有 12 臂（fixed / A1 / A3 / A4 / B1 / B3 / B4 / 缺文件 / 4 条 green）保留；
  - 新增 11 臂：A1b（`if (false)`）、A1c（早退后）、A3b（`run { return }`）、A3c（`if (ready) return`）、
    A4b（零预算）、A4c（极性反转）、B2（`.let { sessionId }`）、B4b（`.also`）、B4c（`let{…; it}`）、
    B4d（`?: "http://".let{…}`）、fail-closed（不可解析）；
  - 变异锚点断言：每处 `rep()` 要求**恰好命中 1 次**，否则 `sys.exit`——防止「补丁没打上、
    臂却在未变异夹具上通过」的假臂。

### L2 反攻（硬验收，官方驱动 `verify_batch5.py --mode full`）

```
tts_voice_and_sanitizer_check.py  → SUMMARY: {'CAUGHT': 7}    (rc=1, exact, 7/7)
katex_ready_race_check.py         → SUMMARY: {'CAUGHT': 6}    (rc=1, exact, 6/6)
```
- 13/13 见证重打后门**全部变红**，且报错指向对应判据；每次跑完驱动自行 restore +
  `sha256` 校验（`restore=True`），主克隆 `git status` 全程干净。
- 06-7 是原 CAUGHT 对照臂 → 改写后**仍然 CAUGHT**（无回归）。
- 驱动形态：tts 门属 `ROOT_STYLE`（只吃 `--root`），katex 门吃位置参数——按驱动内置分支分流，
  未自造跑法。

### L3 真树 + 邻门 + 冻结注册项

- 两门真树：`tts…: PASS`（rc=0）、`[katex-ready-race] clean …`（rc=0）。
- 邻门 8/8 rc=0：`chat_session_id_binding`、`chat_ui_interaction`、`katex_bridge_arity`、
  `open_url_broker_scheme`、`provider_remember_key`、`provider_rpc_symmetry`、
  `skill_restore_session_cleanup`、`sysinfo_state_thread`（按「引用同一批生产文件」筛出的邻域）。
- `test_scan.py` 冻结未改，直接跑两门所属注册组：
  `test_da1016_gates` OK（`tts_voice_and_sanitizer_check selftest has teeth` + `real tree green`）、
  `test_da1019_gates` OK（`katex_ready_race_check selftest has teeth` + `real tree green`）。

### L4 CI

见 ⑤。

---

## ⑤ CI

| 项 | 值 |
|---|---|
| run | `40253155427` / **#2700** |
| job | `121349231336`（`build`），attempt 1 |
| head_sha | `c367faf6f36a5bdaff964c44a783e08b61ceef49`（与本地 HEAD 逐字符一致） |
| 结论 | **success**（26 步，唯一 skipped = `Publish to Releases`＝main 门控，非 success 步骤 0 个） |
| 时间 | 01:13:29Z → 01:28:35Z（约 15 min） |
| 日志 | 1,486,333 B |

日志逐条核对（判读陷阱复核）：

- `Summary: 112 passed, 0 failed` **恰 1** 次；`ALL 344 CASES PASS` **恰 1** 次；
- 门头 `[N/112]` 编号去重 **112** 个（无重复、无缺号）；
- Kotlin 编译错误行 `^ e: ` **0** 条；
- 含 `FAILED` 的 6 行**全部**是 `… PASSED` 用例名子串（`slot denied produces FAILED…`、
  `reason PERSISTENCE_FAILED…`、`phase FAILED…`、`terminal FAILED…`、`reason EXECUTION_FAILED…`、
  `the fixed tail still reaches a legal FAILED terminal`），无真实失败；
- 两门自证在 CI 里有见证：`✅ tts_voice_and_sanitizer_check selftest has teeth` ×1（标记
  `TTS-VOICE GATE ALL ARMS MATCH` ×1）、`✅ katex_ready_race_check selftest has teeth` ×1（标记
  `KATEX_READY_RACE_SELFTEST=PASS` ×1）——即两门的新判据在 CI 的同一棵树上跑过。

**停止节点**：分支 CI 绿即本轮结束，**未合并 main**（门 `.py` 有验证面，合并起手权在用户）。

---

## ⑥ 残留（全部 P2，登记不修）

`R1` **`_derives` 只跟一跳局部 `val`、最多 3 跳**（tts）
- 触发条件：把 `VoiceTextSanitizer.sanitize(...)` 包进同文件成员函数（`clean(text)`）后再喂给 `tts?.speak`。
- 触发后方案：`_derives` 增加「同文件成员函数一跳」——按名字取 `function_body`，注入 `return` 表达式继续走。
- 搁置理由：当前两处调用点都是直连；走不通时的方向是**红**（fail-closed），不会静默放行。

`R2` **取消守卫的极性未判**（tts，06-5 同条见证的另一半）
- 触发条件：把 `if (!maySynthesize(cancelled.get()))` 的 `!` 去掉（守卫变成"未取消才返回"）。
- 触发后方案：要求守卫条件根是 `may*` 谓词的取反，或让 `SynthClipPolicy` 同时暴露 `mustDrop*` 供两处守卫共用。
- 搁置理由：判「取反」会绑死谓词命名（合法重写 `mustDropOnCallback(...)` 会误红），
  与「不锚拼写」冲突；需要先有一个非拼写锚的语义判定（谓词返回值语义表），成本超出本支半径。

`R3` **守卫支配用「花括号深度相等 + 早于目标」近似**（tts）
- 触发条件：守卫与目标分别落在同一 `if/else` 的两个兄弟分支里（同深度）。
- 触发后方案：改用「最近公共祖先块 + 分支区间」判定支配。
- 搁置理由：共享层无块树；本仓当前形态（守卫在回调体顶层）不需要，且误判方向尚不明确。

`R4` **默认值搜索覆盖整个 handler 文件**（tts）
- 触发条件：curious——文件里残留一处死代码 `rate = (rate ?: 1.0).toFloat()`，同时真路径改成 `(rate ?: 0.5)`。
- 触发后方案：从 `tts.speechRate` 的右值反向追到 resolver 的具名实参（一跳绑定）再判。
- 搁置理由：需要跨成员调用图；且「整文件至少有一处正确默认」比旧的「括号里 token 对」已严格得多。

`R5` **只统计 `cmdSpeak` 内的写入**（tts）
- 触发条件：新增另一个 offload 命令也写 `speechRate` 而不写全三个字段。
- 触发后方案：按「所有写 `tts.speechX` 的成员」聚合统计。
- 搁置理由：本仓只有 `cmdSpeak` 一处；扩面需要新的判定口径（其他命令是否也该置全量）。

`R6` **错误表只读数字标签**（tts）
- 触发条件：表改成符号标签（`SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> …`）。
- 触发后方案：符号名经 `SpeechRecognizer` 常量表解析成数值（或用 `SYMBOLS` 白名单直接比名）。
- 搁置理由：当前表是数字标签；改符号时判据会**红**（要求覆盖 10..13），不会静默放行。

`R7` **`_when_of` 取成员内第一个 `when`**（tts）
- 触发条件：`mapError` 里出现第二个 `when`（如嵌套 fallback）且表在后者。
- 触发后方案：取「覆盖全部 10..13 标签」的那个 when，或按 `subject` 派生关系选。
- 搁置理由：单 `when` 是当前形态；多 when 时若第一个不含标签 → 红。

`R8` **共享层缺口（交主会话，本支未改共享模块）**
- `_kotlin_valueflow.enclosing_conditions` 不建模**作用域 lambda**（`?.let { … }` / `run { … }`），
  因此「某语句被 `?.let` 条件化」只能在门内用语句头 `?.` 近似（本支 A-07 就这么做的）。
  建议：`enclosing_conditions` 增加 lambda-arm 条件（`name ?.let`）。
- `_kotlin_ast.function_body` 对块体只返回花括号内区间，**表达式体的头部**（`= when (subject) {`
  的 subject 段）在返回区间之外，本支用 `when_branches` + 显式括号取 subject 绕过。
  建议：返回值增加 `head_start`（`=` 之后到 body 之前）。
- `match_bracket` 返回**闭括号下标**语义未在 docstring 显式声明（本支因此写错过一次）。

`R9` **katex A3 的 null 检查豁免只认裸形态**（katex）
- 触发条件：`if (d != null && ready) d.complete(Unit)`——复合条件被当作「有条件完成」判红。
- 触发后方案：按顶层 `&&` 拆分，只要含一个 null 判据且其余子条件恒真即豁免。
- 搁置理由：误判方向是**红**（安全），且当前实现就是裸 `if (d != null)`。

`R10` **B4 要求 getter 的每个来源都走 `resolveKey`**（katex）
- 触发条件：写 `activeSessionIdInternal?.let { resolveKey(it) } ?: "unknown"`（合法兜底字面量）→ 红。
- 触发后方案：把「字面量/常量来源」与「键来源」分开，仅要求键来源走 resolver。
- 搁置理由：当前 getter 无字面量兜底；有的话先看是否是有意设计再决定放宽。

`R11` **`_has_unconditional_exit` 把任意裸 `return` 视为非局部返回**（katex）
- 触发条件：匿名 `object : X() { override fun f() { return } }` 出现在被扫描语句内
  （`_strip_local_decls` 只剥具名 `fun/class/object` 声明）。
- 触发后方案：把匿名 `object :` 也纳入剥离（`object\s*:` 分支）。
- 搁置理由：本仓 `markPageFinished`/`onPageFinished` 无匿名对象；误判方向为红。

`R12` **A4 的极性判据是「同一顶层语句内」**（katex）
- 触发条件：`val t = withTimeoutOrNull(timeoutMs) { … }; if (t == null) …`（跨语句）→ 红。
- 触发后方案：允许把结果绑定到局部 `val` 后，在**后续语句**里判极性。
- 搁置理由：当前单语句形态；跨语句属等价重写，改动量小但会引入新的一跳绑定跟踪，留观。

---

## ⑦ `test_scan.py` 注册项（本支未改，交主会话核对）

两门**在基线里已注册**，冻结文件保持原样：

| 注册组 | 条目 | 收尾标记（未变） |
|---|---|---|
| `DA1016_GATES` | `("tts_voice_and_sanitizer_check", "TTS-VOICE GATE ALL ARMS MATCH", ("--root", REPO_ROOT))` | 未改 |
| `DA1019_GATES` | `("katex_ready_race_check", "KATEX_READY_RACE_SELFTEST=PASS", (), (REPO_ROOT,))` | 未改 |

本支实测两组均 OK（`selftest has teeth` + `real tree green`）。**不欠注册债**。

臂数变更（CI 日志只能见证收尾标记，臂数由本报告承载，供主会话登记）：

- `tts_voice_and_sanitizer_check`：**22 → 32 臂**（+9 见证臂 +1 fail-closed 臂）。
- `katex_ready_race_check`：**12 → 23 臂**（+10 见证臂 +1 fail-closed 臂；A1b/A1c/A3b/A3c/A4b/A4c/B2/B4b/B4c/B4d/fail-closed）。

---

## ⑧ 复现命令

```bash
cd <repo>                                   # 工作树根（含 scripts/scan/）

# 门自证（两门的全部负向臂）
python3 scripts/scan/tts_voice_and_sanitizer_check.py --selftest
python3 scripts/scan/katex_ready_race_check.py --selftest

# 真树
python3 scripts/scan/tts_voice_and_sanitizer_check.py --root .
python3 scripts/scan/katex_ready_race_check.py .

# 反攻（官方驱动，逐门；tts 门只吃 --root，驱动内置分流）
python3 verify_batch5.py --witnesses witnesses-B.json --repo <repo> --mode full \
        --only tts_voice_and_sanitizer_check.py --out /tmp/tts-reverify.json
python3 verify_batch5.py --witnesses witnesses-B.json --repo <repo> --mode full \
        --only katex_ready_race_check.py        --out /tmp/katex-reverify.json

# 冻结注册项（test_scan.py 不改，直接跑所属组）
cd scripts/scan && python3 -c "
import sys; sys.argv=['test_scan.py','<repo>']; sys.path.insert(0,'.')
import test_scan as t
t.test_da1016_gates(); t.test_da1019_gates()"
```
