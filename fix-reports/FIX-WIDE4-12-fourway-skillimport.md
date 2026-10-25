# FIX-WIDE4-12 — four_way_sync_check / skill_import_offmain_check 判据加固

**基线 sha** = `19e70dab11bdd61984d07ded0e2f5393d65c7f94`（worktree `wt-12` 的父提交，`base/wide4-shared` = 基线 + 两个共享词法原语）
**分支** `fix/wide4-12-fourway-skillimport`
**范围** 2 个门脚本 + 本报告。**生产代码 0 行改动**，`scan.sh` / `test_scan.py` 冻结面零触碰，门数 112 / 夹具 344 不变。

---

## §1 门与轴

| 门 | 见证 | 轴 | 判决（修复前 → 修复后） |
|---|---|---|---|
| `four_way_sync_check.py` | w4-fw-1 | dataflow（死分支留标识符） | FALSIFIED → CAUGHT |
| 同上 | w4-fw-2 | family-hole（常量模板 + 调用链） | FALSIFIED → CAUGHT |
| 同上 | w4-fw-3 | completeness（实体自映射不判推导） | FALSIFIED → CAUGHT |
| 同上 | w4-fw-4 | dataflow（环境标识符 `idx * 0`） | FALSIFIED → CAUGHT |
| 同上 | w4-fw-5 | parser-death（嵌套块注释泄括号） | FALSIFIED → CAUGHT |
| 同上 | w4-fw-6 | dataflow（裸字面量） | CAUGHT → CAUGHT（保持） |
| `skill_import_offmain_check.py` | w4-si-1 | family-hole（具名实参 lambda） | FALSIFIED → CAUGHT |
| 同上 | w4-si-2 | dataflow（可变 / 复合 dispatcher 别名） | FALSIFIED → CAUGHT |
| 同上 | w4-si-3 | family-hole（作用域函数接收者） | FALSIFIED → CAUGHT |
| 同上 | w4-si-4 | family-hole（空值判据方向 + 常量比较） | FALSIFIED → CAUGHT |
| 同上 | w4-si-5 | parser-death（字符字面量脱同步） | FALSIFIED → CAUGHT |
| 同上 | w4-si-6 | family-hole（内层 Main 尾随 lambda） | CAUGHT → CAUGHT（保持） |

两门共 12 条见证：**10 FALSIFIED 全部翻红，2 CAUGHT 保持红**。

---

## §2 逐条见证：原补丁 → 旧判据为何瞎 → 新判据 → 新臂

### w4-fw-1（dataflow：死分支保留真值拼写）

原补丁（`data/db/ProviderConfigMapping.kt`，to-DB 站点）
```kotlin
pinned = if (false) (if (inst.pinned) 1 else 0) else 0,
```
**旧判据为何瞎**：`value_is_constant` 只做「抹掉字符串字面量 → 看还剩哪些标识符」。`if` / `else` 不在 `CONST_IDS` 黑名单里，`inst.pinned` 又确实出现在页面上，于是「有标识符 ⇒ 推导出来了」。字符串里的 `if (false) …` 分支是死代码，值恒为 `0`，但判据读的是**拼写**而不是**取值**。

**新判据**：`_is_const_expr` 先做规范化——`if (<字面量条件>) A else B` 只保留**活臂**（`_const_bool` 求条件、`_if_arms` 切分支），死臂的标识符不参与判定。`KOTLIN_KEYWORDS` 补齐了 `if/else/when/…`（旧的 `CONST_IDS` 只有 5 个词），控制流词不再冒充「值读取」。

**新臂**：`w4-fw-1 dead branch keeps the value literal`

### w4-fw-2（family-hole：常量模板 + 纯转换调用链）

原补丁
```kotlin
pinned = "${0}".toInt(),
```
**旧判据为何瞎**：`if "${" in value: return False` 是**拼命中的捷径**——只要出现模板插值就判「读源」。于是把常量塞进模板（`${0}`）再加一条 `toInt()` 链就完全绕过。这一跳同时也堵住了「模板 + 常量拼接」的整族形态。

**新判据**：`_skeleton()` 把字面量文本抹成空白、**只保留插值表达式本身**（`${expr}` → `(expr)`、`$name` → `name`），再对骨架做折叠：`_is_const_expr` 识别「常量接收者上的纯调用链」（`(0).toInt()`）为常量；`"fixed"` 这类纯文本骨架为空 ⇒ 常量；`"m-$sessionId"` 含 `sessionId` ⇒ 非常量。

**新臂**：`w4-fw-2 constant string template is a literal`；精度对照 `w4-fw-3 entity-self clean twin` 与真树 40+ 个模板字段全绿。

### w4-fw-3（completeness：实体自映射跳过了推导判据）

原补丁（`conversation/CompactOrchestration.kt`）
```kotlin
summary = "",            // 原为 summary = summary,
```
**旧判据为何瞎**：`run_checks` 对 `ENTITY_SELF_FAMILIES` 只做「列有没有被某个构造站点写过」（`entity_fields - site_args - dao`），写完就 `continue`——**chat 核心两张表（MessageEntity / ChatSessionEntity）加上四个 marker 家族从来没被判过「值有没有传过去」**。于是压缩摘要每次保存都被清成空串，而门报 ✅。

**新判据**：`entity_self_const_gaps()` 用**同一条正向判据**（`value_is_constant`）判实体自映射：某列在**每个**构造站点都出现、且每处都是字面量 ⇒ 违规。按设计就该是常量的列在白名单 `ENTITY_CONST_PINS` 里逐条登记理由（本树 5 条：CompactMarkerEntity 的 3 个 `null` 边界 + `version` 格式版本 + ProviderAgentLoopIdEntity 的 `kind` 判别列），白名单被打印成 `ℹ constant-by-design pin: …` 让 pin 本身成为审计对象。

**新臂**：`w4-fw-3 entity-self constant column`（红）/ `w4-fw-3 entity-self clean twin`（绿）

### w4-fw-4（dataflow：环境标识符冒充源对象）

原补丁
```kotlin
azureMode = idx * 0,
```
**旧判据为何瞎**：`idx` 是 `mapIndexed` 的下标，不属于源对象；旧判据把「任何不在黑名单里的标识符」都当作「推导自源」。乘一个 0 之后值是常量，但拼写里有 `idx`。

**新判据**：`_is_const_expr` 的算术分支做**湮灭折叠**：`*` 链中任一因子是字面量 `0` ⇒ 整个表达式是常量；其余四则/比较算子只要两侧都常量 ⇒ 常量。这样 `idx * 0`、`count * (1 - 1)`、`x == x` 一类的「保留标识符、删掉信息」形态都归为字面量。

**新臂**：`w4-fw-4 annihilated index is a literal`

### w4-fw-5（parser-death：嵌套块注释泄漏括号）

原补丁（`data/model/ProviderConfig.kt` 参数表尾部）
```kotlin
    var lastContextLimitTokens: Int? = null, /* outer /* inner */ ) */
    var unmappedGroupFlag: Boolean = false,
)
```
**旧判据为何瞎**：手写 `mask_comments` 用 `src.find("*/", i+2)` 找块注释结尾——**Kotlin 允许嵌套块注释**，于是内层 `*/` 提前收尾，注释里那个 `)` 被当作参数表的闭括号，`unmappedGroupFlag` 从此不在扫描面上。旧实现还会在找不到 `*/` 时**静默 `return src`（未掩码的原文）**，等于「词法失败 ⇒ 报自己干净」。

**新判据**：`mask_comments` 换成共享 `_kotlin_lexer.analyse()`（单级状态机：嵌套块注释、`'\"'` 字符字面量、三引号原始串、`${…}` 模板），并**失败即封口**：解析不成功 → 记 `PARSE_FAILURES` 且该文件内容当空处理，门直接红（`无法解析（…）—— 门拒绝在残缺词法上作判断 (fail-closed)`）。

**新臂**：`w4-fw-5 nested block comment paren`（红）/ `w4-fw-5b nested comment without a fake paren`（绿，防止「见到嵌套注释就红」的过拟合）/ `w4-fw-6 unlexable file is a violation`（未闭合块注释 ⇒ 红）

### w4-fw-6（CAUGHT：裸字面量，保持）

```kotlin
pinned = 0,
```
修复前后都红（`is a constant at every mapping site`）。它是本支的**标定臂**：证明新判据没有把「真·常量」一起放过，翻红的是被证伪的那 5 条，不是整门变钝。

### w4-si-1（family-hole：具名实参 lambda 不是派发块）

原补丁（`ui/settings/SkillsManagementScreen.kt`）
```kotlin
val result = withContext(context = Dispatchers.Main, block = {
    skillRepository.importFromContent(pasteContent)
})
```
**旧判据为何瞎**：`_trailing_dispatch_head` 只认「`{` 前面紧贴 `)`」的尾随 lambda。具名实参写法里 `{` 前面是 `= `，函数判为「不是派发块」，栈上的内层 Main 消失，调用继承了**外层**的 `Dispatchers.IO`——磁盘 IO 在 Main 上跑，门报 OK。

**新判据**：`_dispatch_block_context()` 统一回答「这个 `{` 是否切换线程、切到哪个」：
(a) 尾随 lambda `withContext(X) {`；
(b) `CoroutineScope(Dispatchers.Main).launch {`（dispatcher 在接收者里，旧实现漏的另一种拼写）；
(c) 裸 `launch {` / `withContext {` ⇒ **继承父上下文**（返回 None，符合 Kotlin 语义）；
(d) 具名实参 lambda `withContext(context = X, block = { … })` ⇒ 从实参表里取出 context/dispatcher 名参；未知 dispatcher 返回 `''`（**未知不等于 IO**，fail-closed）。
`_first_dispatch_arg()` 负责在实参表里跳过 lambda 实参、识别 `context =` / `dispatcher =` / 位置实参三种写法。

**新臂**：`w4-arm1 named-arg lambda still runs on Main`

### w4-si-2（dataflow：可变 / 复合 dispatcher 别名）

原补丁
```kotlin
var ioCtx: kotlin.coroutines.CoroutineContext = Dispatchers.IO
ioCtx = Dispatchers.Main
val result = withContext(ioCtx) { skillRepository.importFromContent(pasteContent) }
```
以及同族 `val ioCtx = Dispatchers.IO + Dispatchers.Main`（`+` 取右侧 pair，实际跑在 Main）。

**旧判据为何瞎**：别名分支是 `re.search(r"\b(?:val|var)\s+<name>\s*…=\s*Dispatchers\.IO\b")` —— **存在性正则**：接受 `var`、只看第一次声明、`Dispatchers.IO` 后面跟什么都不管。

**新判据**：`_alias_is_io()`：`var` 一律不认（可变绑定不是常量）；收集该名字**所有**写点（`val` 声明 + 裸赋值），取**最后一次写**并要求右侧**精确等于** `Dispatchers.IO`（允许 `.limitedParallelism(...)` 这类 IO 派生），`+` 复合表达式不再匹配。

**新臂**：`w4-arm2 reassigned var dispatcher is Main`（红）/ `w4-arm2 context-plus-Main runs on Main`（红）/ `w4-arm2 precision: val ioCtx = Dispatchers.IO stays legal`（绿）

### w4-si-3（family-hole：作用域函数接收者）

原补丁（`ui/settings/MCPIntegrationsScreen.kt`）
```kotlin
com.rikkaminis.app.mcp.oauth.MCPOAuthStore.let { it.signOut(context, sid) }
```
**旧判据为何瞎**：判据只认 `MCPOAuthStore.<method>(` 与 `val x = MCPOAuthStore` 两种拼写。作用域函数把对象交给 `it`（`.let` / `.also`）或 `this`（`.run` / `.apply` / `with(obj)`），调用点既不以对象名开头、也没有别名绑定 ⇒ 完全不可见。

**新判据**：`_scope_receiver_hits()` 从**接收者身份**出发：命中 `obj.let|also|run|apply {` 与 `with(obj) {`（含限定名 `com.….MCPOAuthStore`，旧 lookbehind 会把限定名整条挡掉），取块体，按语义定隐式接收者名（`.let/.also` → `it`，`.run/.apply/with` → `this`，或显式 lambda 参数 `s ->`），再把块内 `recv.method(` 与（隐式 this 时）裸 `method(` 一并交给同一条「方法是否做 IO × 是否在 IO 包裹内」判据。同时补进 `check_all_repo_calls`（同一接收者轴）。

**新臂**：`w4-arm3 scope receiver .let on Main`（红）/ `w4-arm3 precision: scope receiver inside IO stays legal`（绿）

### w4-si-4（family-hole：空值判据方向 + 常量比较守卫）

原补丁（`MCPIntegrationsScreen.kt` 草稿 reset）
```kotlin
if (sid != null) { … reset … ; return }     // 原为 if (sid == null)
```
同族：`if ((1 == 1) || sid == null) { … }`。

**旧判据为何瞎**：(a) `_tests_null_of` 的正则 `[=!]=\s*null` **两个方向都算通过**——`!= null` 时 reset 在「有 id」的臂上执行，等于每次编辑真实服务器都把 OAuth 草稿清空，而门认为「守卫在」；(b) `_literal_decides` 只认裸 `true` / `false`，`(1 == 1)` 这种**常量比较**是同一个「保留拼写、删掉决定」的换皮形态。

**新判据**：`_null_test_kind()` 返回 `'eq' | 'ne' | None`（含 `null == x` 反写形态），reset 只接受 `'eq'`（fail-closed：混写 `&&` / `||` 一律判 None）；`_const_truth()` 增加字面量比较求值与 `!`、同操作数比较（`x == x` 恒真），`_literal_decides` 因此能识别 `(1 == 1)`、`0 != 1` 一类死守卫。

**新臂**：`w4-arm4 inverted null guard wipes the draft`（红）/ `w4-arm4 constant-comparison guard is dead`（红）

### w4-si-5（parser-death：字符字面量脱同步）

原补丁
```kotlin
val _open = '"'; val _c = "/*"
com.rikkaminis.app.mcp.oauth.MCPOAuthStore.signOut(context, sid)
val _close = "*/" + '"'
```
**旧判据为何瞎**：手写 `strip_comments` 有 `"` 状态、**没有 `'` 状态**：字符字面量 `'"'` 里的引号被当成字符串开头，接着 `"/*"` 打开一个「块注释」，把中间那行真实调用整段吞掉，直到补丁里的 `*/` 才收尾。

**新判据**：`strip_comments` / `mask_strings` 交给共享 `_kotlin_lexer`（字符字面量、嵌套注释、原始串、模板全部按 Kotlin 规范处理），且解析失败经 `LEX_FAILURES` 汇总成违规（fail-closed），不再「掩码失败就用原文继续扫」。

**新臂**：`w4-arm5 char-literal desync does not hide the call`（红）/ `w4-arm6 unlexable file is a violation`（红）

### w4-si-6（CAUGHT：内层 Main 尾随 lambda，保持）

```kotlin
withContext(Dispatchers.IO) {
    withContext(Dispatchers.Main) { skillRepository.importFromContent(pasteContent) }
}
```
修复前后都红（`call site runs inside \`Dispatchers.Main\`, not withContext(Dispatchers.IO) - an enclosing IO wrap does not rescue an inner dispatcher switch`）。标定臂：证明新派发模型没有把「外层 IO」重新当成豁免。

---

## §3 判据改动清单（函数名级）

### `scripts/scan/four_way_sync_check.py`（+565 / −62）

| 函数 | 变化 |
|---|---|
| `mask_comments` | 改为共享 `_kotlin_lexer.analyse()`；解析失败记 `PARSE_FAILURES` 并返回空（fail-closed） |
| `KOTLIN_KEYWORDS`（新） | 控制流/声明关键字集，替换旧的 5 词 `CONST_IDS` 黑名单语义 |
| `_strip_outer` `_match_paren` `_match_brace_src` `_split_top`（新） | 括号配平 / 深度 0 切分（字符串先掩码，算子不会被字面量里的符号劈开） |
| `_skeleton`（新） | 抹字面量文本、保留插值表达式（`${e}` → `(e)`、`$n` → `n`） |
| `_const_bool`（新） | 字面量决策求值（`!`、`&&`/`||` 短路、同操作数比较、数字/字符串比较） |
| `_if_arms` `_when_is_const`（新） | `if (cond) A else B` 活臂选择；`when (<字面量>)` 分支选择 |
| `_is_const_expr`（新）/ `value_is_constant`（重写） | 正向折叠：死分支、常量模板、`* 0` 湮灭、纯转换调用链、关键字/调用名剔除，最后才回落到「还有没有剩余标识符」 |
| `entity_self_const_gaps`（新）/ `ENTITY_CONST_PINS`（新） | 实体自映射的常量列判据 + 5 条带理由的审计白名单 |
| `_selftest_tree`（新） | 任意 `{path: text}` 夹具跑门（实体自映射 / 词法失败臂用） |

### `scripts/scan/skill_import_offmain_check.py`（+515 / −104）

| 函数 | 变化 |
|---|---|
| `strip_comments` / `mask_strings` | 改为共享 `_kotlin_lexer`；失败记 `LEX_FAILURES` 并返回空（fail-closed） |
| `run_checks_map` | 汇总 `LEX_FAILURES`（去重）为违规 |
| `_dispatch_block_context`（新，取代 `_trailing_dispatch_head`） | 尾随 lambda / `CoroutineScope(X).launch` / 裸 `launch` 继承 / 具名实参 lambda 四种形态统一识别；未知 dispatcher = `''`（不等于 IO） |
| `_first_dispatch_arg` `_matching_open` `_enclosing_open_paren` `_dispatch_callee`（新） | 实参表解析（位置 / `context=` / `dispatcher=` / 跳过 lambda 实参） |
| `_innermost_dispatch` | 改用新派发模型（仍只取最内层） |
| `_is_io_dispatcher` / `_alias_is_io`（新） | 别名必须是不可变 `val`，且**最后一次写**精确等于 `Dispatchers.IO` |
| `_scope_receiver_hits`（新） | `.let/.also/.run/.apply/with(obj)` 的隐式接收者（含限定名、显式 lambda 参数） |
| `check_offmain_calls` / `check_all_repo_calls` | hits 统一为 `(pos, method)`，并入作用域接收者命中 |
| `_null_test_kind`（新）/ `_tests_null_of` | 方向化空值判据（`eq` / `ne` / None）；`check_seed_effect_keys` 只接受 `eq` |
| `_const_truth`（新）/ `_literal_decides` | 字面量比较求值（`(1 == 1)`、`x == x`、`!false`）也算死守卫 |

---

## §4 四层证据

### L1 本门（真树 + 自证带牙）

| 项 | 结果 |
|---|---|
| `python3 scripts/scan/four_way_sync_check.py .` | `rc=0`，`Done: ALL CLEAN ✅ [checked 10/10]`，`Coverage: derived 10 · checked 4 + entity-self 6 · unresolved 0` |
| `python3 scripts/scan/four_way_sync_check.py --selftest` | **14 臂全 PASS**（原 6 臂 + 8 新臂），收尾 `FOUR_WAY_SYNC_SELFTEST=PASS`（标记串未改） |
| `python3 scripts/scan/skill_import_offmain_check.py .` | `rc=0`，`OK …(15 arms: …)` |
| `python3 scripts/scan/skill_import_offmain_check.py --selftest` | 原臂全 PASS + **10 条 wide4 臂全 PASS**，收尾 `SKILL_IMPORT_OFFMAIN_SELFTEST=PASS`（标记串未改） |
| `test_scan.py` 中 three 组 four_way 夹具 | `test_four_way` / `test_four_way_derived` / `test_four_way_entity_self` 全 ✅（含 `derived non-hardcoded family caught`、`alias-normalized derived family green`、`orphan entity → UNRESOLVED`、`KNOWN_MISMATCH pin → WARNING`、positional 分支两例） |

### L2 反攻复验（把见证补丁重新打回真树）

驱动 `probe/reattack_inproc.py`（**进程内**跑门，不 fork；沙箱 S28 `Errno 38` 风暴下 fork 会死在 apply 与 restore 之间、把工作树留在变异态——本支被咬过 3 次，已改为字节级 + 读回校验 + 重试）。每条：apply → 门 → restore → **读回比对**。

| 见证 | rc 前 → 后 | 判据命中 | restore |
|---|---|---|---|
| w4-fw-1 | 0 → 1 | `is a constant at every mapping site` | ✅ |
| w4-fw-2 | 0 → 1 | 同上 | ✅ |
| w4-fw-3 | 0 → 1 | `is a constant at every constructor site` | ✅ |
| w4-fw-4 | 0 → 1 | `is a constant at every mapping site` | ✅ |
| w4-fw-5 | 0 → 1 | `unmappedGroupFlag` | ✅ |
| w4-fw-6 | 0 → 1 | 同上（保持） | ✅ |
| w4-si-1 | 0 → 1 | `runs inside` | ✅ |
| w4-si-2 | 0 → 1 | `runs inside` | ✅ |
| w4-si-3 | 0 → 1 | `runs on Main` | ✅ |
| w4-si-4 | 0 → 1 | `reset is not inside a live` | ✅ |
| w4-si-5 | 0 → 1 | `runs on Main` | ✅ |
| w4-si-6 | 0 → 1 | `runs inside`（保持） | ✅ |

**12/12 翻红/保持红，restore 12/12 True，主克隆零污染**（每条结束 `git status --porcelain` 只剩两个门脚本）。

### L3 邻门（只读跑，10 道全绿）

| 门 | rc |
|---|---|
| `log_url_redaction_guard.py` | 0（`audited sites: 13`） |
| `offload_cli_args_check.py` | 0 |
| `deeplink_settings_routes_check.py` | 0 |
| `file_provider_paths_coverage_check.py` | 0 |
| `share_dir_prune_check.py` | 0 |
| `browser_tabs_lifecycle_check.py` | 0 |
| `offload_host_path_check.py` | 0 |
| `mcp_oauth_lifecycle_check.py` | 0（语义最近邻：同判 MCP OAuth store 的调用面） |
| `thinking_wiring_guard.py` | 0 |
| `provider_boundary_guard.py` | 0 |


### L4 全量 + CI

| 项 | 结果 |
|---|---|
| `bash scripts/scan/scan.sh`（本地全量） | `rc=0`，`║  Summary: 112 passed, 0 failed` |
| `python3 scripts/scan/test_scan.py`（本地全量夹具） | `rc=0`，`✅ ALL 344 CASES PASS` |
| CI（权威读数） | run **`40180883131`** / job **`121131508294`** / attempt **1** / **success**，`head_sha = 575dea50…` 与本地 HEAD 逐字符一致 |

---

## §5 CI run 号与判据行

**run `40180883131`（#2671）/ job `121131508294` / attempt 1 / conclusion success / head_sha `575dea507921…` = 本地提交 `575dea50` 逐字符一致。**

日志判读（1,482,894 B / 17,862 行，`probe/ci/job.log`）：

| 判据 | 读数 |
|---|---|
| `Summary: 112 passed, 0 failed` | **恰 1 条** |
| `ALL 344 CASES PASS` | **恰 1 条** |
| 门头 `━━━ [N/112]` | **112 条，编号互异 112** |
| ` e: `（Kotlin 编译错误标记） | **0** |
| 含 `FAILED` 的行 | 6 条，**逐条确认全是 `PASSED` 用例名的子串**（`produces FAILED without…`、`reason PERSISTENCE_FAILED maps…`、`phase FAILED maps to Failed`、`terminal FAILED maps to Failed`、`reason EXECUTION_FAILED maps…`、`the fixed tail still reaches a legal FAILED terminal`） |
| 非 success 步骤 | 仅 `Publish to Releases` = **skipped**（main 门控，预期） |
| `SKILL_IMPORT_OFFMAIN_SELFTEST` | **1 次命中**（wide3 波已注册的标记断言） |
| `FOUR_WAY_SYNC_SELFTEST` | **0 次命中** — 证实 §7 第一行注册项仍未注册：本支新增的 8 条 four_way 臂在 CI 里**无人见证**，需主会话收口时补注册 |

---

## §6 残留（每条带触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `four_way` 的 `when (<字面量>)` 只支持 `else ->` 兜底分支与非分号分隔的行首 `->` 形态 | 外部审计给出 `when (false) { true -> x }`（**无 else**）或单行 `;` 分隔的见证 | 在 `_when_is_const` 里补「无 else ⇒ 落到 `when` 表达式自身的缺失分支（Kotlin 抛异常/返回 Unit）」判定，并把 `_split_top(body, ";")` 换成按 `->` 归并分支 | 现有 6 条见证无一使用该形态；多写一分支就多一处需自证的判据，装置比例过大 |
| R2 | `_is_const_expr` 的 `guard > 6` 递归上限：更深的嵌套常量表达式按「非常量」处理 | 出现 7 层以上嵌套（如 `if (false) { … if (false) { … } }`）却要求判常量的见证 | 把折叠改成迭代式（显式栈）并去掉深度上限，同时补一条 8 层嵌套的自证臂 | 方向安全（判非常量 = 不放行），且真实代码里不存在这种嵌套 |
| R3 | `four_way` 未判 `when` 分支里的活值（`when (x) { true -> 0 }` 与 `every site constant` 的交集） | 审计给出「`when` 单臂常量 + 其余臂由默认值填充」的见证 | 在 `entity_self_const_gaps` / `check_derivation` 里补 when 分支枚举 | 属于 w4-fw-3 家族的加强形态，当前见证已被 folds + 「每个站点」双条件挡住 |
| R4 | `_scope_receiver_hits` 只覆盖 `.let/.also/.run/.apply/with`；`takeIf/takeUnless` 与自定义内联作用域函数未覆盖 | 审计用 `obj.takeIf { it.signOut(...) }`（语义上不成立但拼写可达）或项目自定义 `withStore {}` 写见证 | 把作用域函数名集提为常量 `SCOPE_FUNCTIONS` 并按「接收者是否可作为 `it`/`this`」分类；自定义包装从声明处派生 | 项目内不存在自定义作用域包装；`takeIf` 块体语义是谓词，误报风险高于收益 |
| R5 | `_alias_is_io` 的「最后一次写」是**全文件**粒度，跨函数同名局部变量会互相干扰 | 同一文件里两个函数各有同名 `ioCtx`，其中一个为 Main | 改为按**函数体**分段（用 `_kotlin_ast.function_body` 取体范围）再取最后写 | 当前真树 0 处 dispatcher 别名绑定（`grep "= Dispatchers.IO"` 为空）；方向安全（更严 → 只可能误红，不会漏红） |
| R6 | `four_way` 的 `ENTITY_CONST_PINS` 是人工维护的理由白名单 | 新增实体自映射列且其值按设计为常量 | 把 pin 理由写进实体字段 KDoc，再由门解析 KDoc 代替白名单 | 白名单带理由且每次运行时打印（`ℹ constant-by-design pin`），pin 本身可审计；改 KDoc 解析是新装置 |
| R7 | `skill_import` 的具名实参派发只认 `context` / `dispatcher` / `newContext` 三个参数名 | 出现 `withContext(ctx = …)` 之类自定义参数名（Kotlin 标准库 API 签名固定，实际不可达） | 从 `withContext` 声明处派生参数名（无源码可派生时保持现白名单 + fail-closed） | 标准库签名固定；扩大白名单会削弱 fail-closed |
| R8 | `_literal_decides` 不识别 `0 == 0 && guard` 之外的算术常量（如 `2 > 1`） | 审计给出 `if (2 > 1 && sid == null)` 形态 | 在 `_const_truth` 补 `>` `<` `>=` `<=` 的数字比较求值 | 同 R1：无见证使用；且 `==`/`!=` 已覆盖「保留拼写」的主流形态 |
| R9 | 本支未在 `test_scan.py` 注册新夹具（冻结面） | 主会话并集收口 | 按 §7 注册两门的 selftest 标记断言 | 任务书 §4 明确冻结 `test_scan.py`，注册由主会话统一做 |

---

## §7 注册项（主会话并集收口时据此注册）

两门均**无新建门**，需为 `test_scan.py` 加「带牙标记断言」（arm 数只增不减，标记串一字未改）：

```
| 门脚本                           | selftest 标记串（--selftest 收尾行，逐字）   | 臂数 |
|----------------------------------|----------------------------------------------|------|
| four_way_sync_check.py           | FOUR_WAY_SYNC_SELFTEST=PASS                  | 14   |
| skill_import_offmain_check.py    | SKILL_IMPORT_OFFMAIN_SELFTEST=PASS           | 现有臂 + 10（wide4） |
```

对应注册形态（与既有 `DA####_GATES` 表一致）：

```python
("four_way_sync_check.py", "FOUR_WAY_SYNC_SELFTEST=PASS", (REPO_ROOT,)),
("skill_import_offmain_check.py", "SKILL_IMPORT_OFFMAIN_SELFTEST=PASS", (REPO_ROOT,)),
```

注：`skill_import_offmain_check.py` 的标记串在本仓已存在断言（wide3 波注册）；本支只新增 10 条臂，**不新增注册项**。`four_way_sync_check.py` 的标记串在 CI 日志里当前**无断言**（本支新增的 wide4 臂因此无人见证），需补上表第一行。

---

## §8 复现步骤

```bash
cd /var/minis/shared/work/meta-wide4/wt-12          # 分支 fix/wide4-12-fourway-skillimport

# L1 本门
python3 scripts/scan/four_way_sync_check.py .
python3 scripts/scan/four_way_sync_check.py --selftest       # 14 arms, 末尾 FOUR_WAY_SYNC_SELFTEST=PASS
python3 scripts/scan/skill_import_offmain_check.py .
python3 scripts/scan/skill_import_offmain_check.py --selftest  # 末尾 SKILL_IMPORT_OFFMAIN_SELFTEST=PASS

# L1 夹具
cd scripts/scan && python3 -c "import sys;sys.argv=['x'];import test_scan as t;\
t.test_four_way();t.test_four_way_derived();t.test_four_way_entity_self()"

# L2 反攻（进程内，逐条 apply → 门 → restore → 读回校验）
cd ../.. && python3 probe/reattack_inproc.py            # 12/12 CAUGHT, 见 probe/reattack.json
python3 probe/reattack_inproc.py w4-si-5                # 单条模式

# L4 全量（沙箱高峰会随机 Errno 38，分块重跑，别当门红）
bash scripts/scan/scan.sh
python3 scripts/scan/test_scan.py
```

**沙箱注意**：`Errno 38 (Function not implemented)` 是并发高峰期的已知缺陷（12 支并行时尤其重），不是门红；本支的反攻驱动因此改为**进程内**（不 fork）+ 写后读回校验 + 8 次退避重试，并在每次运行前后 `git status --porcelain` 自查是否残留补丁（本支被咬 3 次，均已 `git checkout --` 还原）。
