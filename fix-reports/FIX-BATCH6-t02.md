# FIX-BATCH6-t02 —— provider_rpc_symmetry + file_preview_url_encode 两门见证加固

**支线**：`fix/b6-rpcsym-urienc`（worktree `batch6/wt6/t02`）
**基线**：`base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享值流原语 `_kotlin_valueflow.py`）
**改动面**：2 个门脚本（`+841 / −541`），**生产 `.kt` 0 行**、`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰，门 112 / 夹具 344 不变。
**外部见证**：report-A §07（provider，6 条）+ report-B §08（file_preview，6 条）= 12 条，全部 `FALSIFIED`，复证基线 `6ac77c6b2` 上全部 `BLIND_CONFIRMED`。

---

## 1. 门与轴

| 门 | 见证数 | 轴分布 | 一句话病因 |
|---|---|---|---|
| `scripts/scan/provider_rpc_symmetry_check.py`（3016 行，C1–C19） | 6 | family-hole ×3（C4/C18/C12）· dataflow ×2（C6/C19）· scope ×1（C10） | 判据锚「某调用/某 token **被写出**」，缺陷动的是「它**被求值/被传递**」 |
| `scripts/scan/file_preview_url_encode_check.py`（443 行，arm1/arm2/arm3） | 6 | dataflow ×3 · family-hole ×2 · parser-death ×1 | 判据锚「某拼写**出现**」，缺陷动的是「这个值的**去处**」（早退 / 改写 / 未消费）+ 自写词法器边界 |

两门同族：**词法/拼写通道代替值流通道**。6 条见证明明改的是「值的走向」（提前返回、常量折叠、链式改写、实参丢空、变量未消费、形参被忽略），旧判据却只问「那个 token 还在不在文本里」。

---

## 2. 见证原文（12 条，逐条 find/replace/why_gate_green）

### 2.1 provider_rpc_symmetry_check.py（report-A §07）

**A07-1 · family-hole · `ProvidersCollection.kt`（C4 守卫）**

```kotlin
// find
                if (!isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {
                    throw ConfigError.InvalidValue(CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
                }
// replace
                if (!true && !isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {
                    throw ConfigError.InvalidValue(CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
                }
```
`why_gate_green`：`_guard_branches` 调 `_constant_condition(cond)`，后者 `_split_top(c,'&&')` 得 `['!true', '!isCustomBaseURLWriteAllowed(...)']`，逐项只判 `v == 'false'`；`'!true' != 'false'` ⇒ 返回 `None`（非常量）⇒ 门认为守卫有效且有 `throw` ⇒ 放行。**`!true &&` 让整个守卫恒假，write 完全不受门控。**

**A07-2 · dataflow · `ProviderMutationMethods.kt`（C6 azureMode）**

```kotlin
// find    : val azureMode = params.optBoolean("azureMode", false)
// replace : val azureMode = params.optBoolean("azureMode", false) && false
```
`why_gate_green`：`_azure_value_flows` 只用正则确认「声明行里有 `params.optBoolean("azureMode"`」，再查「后续有无 `azureMode = true|false` 的二次赋值」；`&& false` 既满足首个正则、又不触发第二个 ⇒ 判数据流完好。**存进去的常量为假，azure 分支永远关。**

**A07-3 · dataflow · `ConfigBuiltins.kt`（C19 resident arm）**

```kotlin
// find
                            SessionConfigReadSource.ResidentViewModel ->
                                vm!!.requestedThinkingLevel.value
// replace
                            SessionConfigReadSource.ResidentViewModel ->
                                if (vm != null) ThinkingLevel.OFF else ThinkingLevel.OFF
```
`why_gate_green`：`_resident_arm` 取出 arm 文本后只查 `_reaches_token(…, ("requestedThinkingLevel","thinkingOverride","ThinkingLevel"))` 与「arm 是否精确等于常量黑名单」；`ThinkingLevel.OFF` 命中了 token 元组本身，而 `if (vm != null)` 里的 `vm` 又让 `_arm_derives_from_vm` 的「handle 是否出现在 arm 里」为真 ⇒ 绿灯。**两条分支都答常量，VM 只是被 null 判断「提了一嘴」。**

**A07-4 · family-hole · `ConfigBuiltins.kt`（C18 refresh）**

```kotlin
// find
                        persist = { chatRepo.dao.updateThinkingOverride(sid, level.name) },
                        refresh = { vm -> vm.setThinkingLevel(level) },
// replace
                        persist = { chatRepo.dao.updateThinkingOverride(sid, level.name) },
                        refresh = { vm -> if (false) vm.setThinkingLevel(level) },
```
`why_gate_green`：`check_session_write_through` 对 `refresh = { … }` 块只做 `any(t in branch for t in VM_REFRESH_TOKENS)` 子串检查 ⇒ `setThinkingLevel` 仍在文本里 ⇒ 绿灯。**Room 行写了，活着的会话永远不刷新（切回聊天仍是旧值）。**

**A07-5 · family-hole · `ProviderMutationMethods.kt`（C12 canonical face）**

```kotlin
// find    : updated = mutatedGroupCopy(updated) { applyContextLimitWrite(it, v) }
// replace : updated = mutatedGroupCopy(updated) { if (false) applyContextLimitWrite(it, v) }
```
`why_gate_green`：C12 只查 `"applyContextLimitWrite(" in groups_update` 子串，不判 lambda 体活性 ⇒ 绿灯。**canonical 写脸被跳过，clear/seed 语义回退到旧实现。**

**A07-6 · scope · `ProviderMutationMethods.kt`（C10 probe 形参）**

```kotlin
// find
internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = azureMode, token = key)
// replace
internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = false, token = key)
```
`why_gate_green`：C10 的检查只到「probe 是否**委派**给 `openAIKeyAuthHeader(…)`」+「probe 体内有无 `"api-key"` 字面量」；委派存在、字面量没有 ⇒ 绿灯。调用方 `instancesTest` 把 `instance.azureMode` 传进来（C6 也照旧绿），**probe 自己把它换成 `false`：fork 决策在值流背后被重做了一次。**

### 2.2 file_preview_url_encode_check.py（report-B §08）

**08-1 · dataflow · `FilePreviewScreen.kt`（arm2 编码器）**

```kotlin
// find
internal fun encodeFileUrlPath(path: String): String {
    val sb = StringBuilder(path.length)
// replace
internal fun encodeFileUrlPath(path: String): String {
    val sb = StringBuilder(path)
    if (path.isNotEmpty()) return sb.toString()
```
`why_gate_green`：`carriers={'sb'}`；`exprs=[' sb.toString()',' sb.toString()']`；没有一个 return 等于裸 `path`（旧判据满足），return 里含载体 `sb`（旧判据满足），死代码里的 `sb.append('%')` 仍在（旧判据满足）⇒ arm2 全绿。**`#`/`?`/空格全部原样进 URL。**

**08-2 · family-hole · `FilePreviewScreen.kt`（arm2 转义分支）**

```kotlin
// find
        val safe = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'
// replace
        val safe = true || c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '/'
```
`why_gate_green`：`check_encoder` 只验证「`ESCAPE_EMIT_RE` 命中」+「载体被 return」，不分析 `else` 分支可达性、不折叠 `safe` 的布尔表达式 ⇒ 绿灯。**`%XX` 分支成了死代码，编码器退化为恒等函数。**

**08-3 · family-hole · `FilePreviewScreen.kt`（arm3a fileUrlFor）**

```kotlin
// find    : private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)
// replace : private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath).replace("%23", "#").replace("%20", " ")
```
`why_gate_green`：`VALUE_DISCARD_RE` 是 6 个作用域函数（`let|run|also|apply|takeIf|takeUnless`）的白名单，`.replace(...)` 不在名单 ⇒ 绿灯。**编码完再把 `%23` 换回 `#`，等于没编码。**

**08-4 · dataflow · `FilePreviewScreen.kt`（arm3a 实参）**

```kotlin
// find    : private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath(file.absolutePath)
// replace : private fun fileUrlFor(file: File): String = "file://" + encodeFileUrlPath("") + file.absolutePath
```
`why_gate_green`：旧判据只查「`"file://" +` 后紧跟 `encodeFileUrlPath(`」+「整文件里 `encodeFileUrlPath(` 出现 1 次」+「右括号后不接 `.let/xxx` 或 `?:`」+「禁止精确子串 `"file://" + file.absolutePath`」；没有任何一处检查括号内实参、也没禁止右括号后接 `+ file.absolutePath` ⇒ 绿灯。**编码器被喂空串，真路径原样拼在后面。**

**08-5 · dataflow · `FilePreviewScreen.kt`（arm3b 消费）**

```kotlin
// find（预览加载点，节选）
                val targetUrl = fileUrlFor(item.file)
                // [deep-audit-1046 S-07] `post` parks the load …（4 行注释）
                val initialLoad = Runnable {
// replace
                val targetUrl = fileUrlFor(item.file)
                val initialLoad = Runnable {
                    if (!releaseLatch.canRunDeferred()) return@Runnable
                    loadUrl(item.file.toURI().toString().replace("%23", "#"))
                }
```
`why_gate_green`：`check_load_sites` 只数 `fileUrlFor(item.file)` 出现次数 ≥2 且右括号后不紧跟丢弃型尾巴；**从不追踪绑定 `targetUrl` 是否真被传给 `loadUrl(…)`** ⇒ 计数仍是 2 ⇒ 绿灯。**编码结果成了死变量，加载的是未编码 URL。**

**08-6 · parser-death · `FilePreviewScreen.kt`（arm3b + 词法器）**

```kotlin
// find
        if (preRenderedHtml == null) {
            webView.loadUrl(fileUrlFor(item.file))
        }
// replace
        if (preRenderedHtml == null) {
            val q = '"' // " fileUrlFor(item.file)
            webView.loadUrl("file:" + "//" + item.file.path)
        }
```
`why_gate_green`：自写 `strip_comments` 只认 `"` 开串，读到 `val q = '"'` 就 `in_str=True`，把紧随其后的 `//` 当字符串内容保留，再在注释里的第二个 `"` 处关串 ⇒ 注释后的 ` fileUrlFor(item.file)` 被当成真代码保留 ⇒ 计数依旧 2；同时打散 `"file://"` 字面量让 `RAW_PREFIX_RE` 失效 ⇒ 绿灯。**两个缺陷（未编码加载 + 解析器死亡）互相掩护。**

---

## 3. 判据改造（旧形态 → 新形态，逐条）

### 3.0 两门共同的底层迁移（硬约束第 1 条：删自写词法/括号扫描）

| 删掉 | 换成 | 旧形态为何瞎 |
|---|---|---|
| `strip_comments(src)`（两门各一份） | `_kotlin_lexer.analyse` 的三视图；注释视图保留字符串/字符内容、`code` 视图两者都抹空，**长度严格不变** | 只认 `"`；`val q = '"'` 直接反转字符串状态（08-6）；`"video/*"` 里的 `/*` 会开块注释 |
| `extract_function` / `decl_window` / `member_window` | `_kotlin_ast.function_body`（块体 + 表达式体都返回） | 裸花括号计数在表达式体函数（`fun f() = …`）上会吞掉后续声明或返回 None |
| `brace_block_after` / `paren_block_after` / `extract_apply_block` / `_match` | `_kotlin_ast.match_bracket` 的 3 行包装 | 同族裸计数 |
| `_strip_parens` / `_split_top` | `strip_parens` / `split_top`（+ 3 行 `_split_expr` 字符串适配，只为保住本门文案里的字符串列表） | 同族 |
| `_constant_condition` | `_kotlin_ast.const_bool`（优先级感知、短路、`!` 折叠、`when(false)`） | 只认字面 `'true'/'false'`（A07-1） |
| —— | **fail-closed**：`mask_source` 抛 `LexFail` → `check_tree` 捕获 → `fail_closed` + rc=1 | 自写剥注释失败时静默按半截体判绿 |

### 3.1 provider_rpc_symmetry_check.py（6 条）

| # | 轴 | 旧判据（瞎在哪） | 新判据 |
|---|---|---|---|
| C4 | family-hole | `_constant_condition(cond)` 只比字面 `'true'/'false'` | 守卫活性改用 `const_bool`：`!true`、`1 == 2`、`when(false)`、`x \|\| true` 全折叠；折出确定值 ⇒ 报「condition folds to false — guard is dead code」 |
| C6 | dataflow | 正则确认声明行里有 parse + 查二次赋常量 | 走 `value_sources`：解析 `azureMode` 初值**所有求值来源**（elvis / if-else 两侧 / `let` 体 / `also` 只取接收者），**任一来源能折成常量 ⇒ 整体判脏**（`&& false`、`.let { false }`、`if (…) false else false` 全中）；否则继续追一跳局部 val 与同文件 helper 找 parse |
| C10 | scope | 只查「是否委派」+「有无 `api-key` 字面量」 | 再查**形参是否被传递**：从 `probeAuthHeader` 参数表取首参，取委派调用的实参表（具名 `azure=`/`azureMode=` 或首个位置实参），该值必须**派生自该形参**（`contains_ident`），否则报「ignores its `azureMode` parameter」 |
| C12 | family-hole | `"applyContextLimitWrite(" in body` 子串 | `live_positions(code_view(body), r"applyContextLimitWrite\s*\(")`：死分支里的调用不算；仍保留原「未出现」文案，另加「present but unreachable」说明 |
| C18 | family-hole | `any(token in branch)` 子串 | `_live_refresh_call(branch)`：逐个 `VM_REFRESH_TOKENS` 走 `live_positions`/`path_feasible`，**可达**才算刷新；死分支 ⇒ 报「no reachable call … a dead branch around the refresh is the same silent drop」 |
| C19 | dataflow | handle 标识符**出现在 arm 里**就算读 | 走 `_arm_sources(arm)`：先按 arm 自己的顶层 `else` 切成两侧（补 `value_sources` 不认无花括号 `if (c) a else b` 的缺口），再交 `value_sources` 取求值来源；**条件不再计入来源**，至少一个来源必须 `contains_ident(handle)`。null 判断不再能冒充读 |

### 3.2 file_preview_url_encode_check.py（6 条）

| arm | 轴 | 旧判据（瞎在哪） | 新判据 |
|---|---|---|---|
| arm1 | dataflow | `"file://" +` 后 `re.match(encodeFileUrlPath\()` 就算过 | 用 `expr_end` 取**整个操作数**，要求它**恰为** `encodeFileUrlPath( … )`（右括号后必须空）⇒ `encoder("").+ 路径` 与 `.replace(...)` 尾巴都红 |
| arm2 | dataflow | 只要「有 `%XX` 发射」+「载体被 return」+「return≠裸形参」 | ①**每个 return 的字符偏移都必须晚于首个发射点**（早退返回未转义载体 ⇒ 红）；②发射点所在分支必须**可行**：逐层取包围它的 `if`，条件经一跳局部绑定解析后交 `const_bool`（`val safe = true \|\| …` ⇒ else 分支死 ⇒ 红）；③原载体/return 判据保留（双通道：转义睁眼用注释视图，结构用 code 视图） |
| arm3a | family-hole + dataflow | 计数 + 6 名白名单 + 禁一个精确子串 | 解析 `fileUrlFor` 的**结果表达式**：按顶层 `+` 切，必须恰 2 个操作数；首操作数字面量必须是 `file://`；第二操作数必须**恰为** `encodeFileUrlPath( … )`（无尾巴）；括号内实参必须**引用 builder 的形参**（`encodeFileUrlPath("")` ⇒ 红） |
| arm3b | dataflow | 计数 ≥2 + 禁立即 `.let` 尾巴 | ①收集 `val/var X = fileUrlFor(item.file)` 绑定，**每个绑定都必须作为某个 `loadUrl(…)` 的实参出现**（未消费 ⇒ 红）；②**每个 `loadUrl(…)` 的实参都必须派生自** `fileUrlFor(…)` 或某个绑定（`loadUrl(raw url)` ⇒ 红）；③保留「load 点后立即被 `.let/…` 或 `?:` 改写」的红 |

新增自测臂：provider `66 → 73`（F67–F72 + 对照臂），file_preview `7 → 13`；两门自测收尾各多打一行臂数（`PROVIDER_RPC_SYMMETRY_SELFTEST_ARMS=73` / `FILE_PREVIEW_URL_ENCODE_SELFTEST_ARMS=13`），**旧标记串逐字节未动**（`test_scan.py` 断言照旧成立）。

---

## 4. 四层证据

### L1 本门绿 + 自测臂

```
$ python3 scripts/scan/provider_rpc_symmetry_check.py .        → rc=0  ALL GREEN (C1…C19)
$ python3 scripts/scan/provider_rpc_symmetry_check.py --selftest → PROVIDER_RPC_SELFTEST=PASS / ARMS=73
$ python3 scripts/scan/file_preview_url_encode_check.py .      → rc=0  OK (arm1 + arm2 + arm3 green)
$ python3 scripts/scan/file_preview_url_encode_check.py --selftest → FILE_PREVIEW_URL_ENCODE_SELFTEST=PASS / ARMS=13
```
新臂**逐条断言「红在哪一条判据」**（消息子串），不是「只要有违规就行」——F67 断言 `folds to false`、F70/F71 要求 `only(detail,"c18"/"c12")`、F72 断言 `ignores its`、file_preview 每条臂断言专属消息（`returns before the escape loop runs` / `can never run` / `must be exactly` / `computed but never handed to loadUrl` / `bypasses the encoder`）。

### L2 反攻（硬验收）

```
$ python3 .../verify_batch5.py --witnesses .../witnesses-A.json --repo . --mode full \
    --only provider_rpc_symmetry_check.py --out .../probe/t02-provider_rpc_symmetry_check.json
SUMMARY: {'CAUGHT': 6}      # A07-1..A07-6 全部 CAUGHT (rc=1, exact)，restored 6/6 True

$ python3 .../verify_batch5.py --witnesses .../witnesses-B.json --repo . --mode full \
    --only file_preview_url_encode_check.py --out .../probe/t02-file_preview_url_encode_check.json
SUMMARY: {'CAUGHT': 6}      # 08-1..08-6 全部 CAUGHT (rc=1, exact)，restored 6/6 True
```
**12/12 从 `BLIND_CONFIRMED` 翻成 `CAUGHT`**（本波两门无原 CAUGHT 对照臂）。反攻后 `git status --porcelain src/` **为空**（无变异残留）。

### L3 邻门（10 道，共享生产文件 / 同族判据）

```
webview_holder_url_source_check   rc=0    filepreview_print_bounded_check    rc=0
browser_destroy_wakeup_check      rc=0    provider_write_rules_check         rc=0
collection_copy_on_mutate_check   rc=0    entry_id_rename_integrity_check    rc=0
entry_composite_key_guard_check   rc=0    auto_approve_bypass_check          rc=0
memory_gate_provider_fields_check rc=0    deeplink_settings_routes_check     rc=0
```
（选这 10 道的原因：前 3 道同读 `FilePreviewScreen.kt`，其余同读 `ProvidersCollection.kt` / `ProviderMutationMethods.kt` / `ConfigBuiltins.kt`，且都是值流/活性同族判据。）

### L4 全量（`scan.sh` 112 门 + `test_scan.py` 344 夹具）

沙箱并发窗口下 `bash scan.sh`（每门一子进程）会随机死在 spawn/wait（`Errno 38`，注册表 S28），故按任务书 §5.4 手册改**逐门独立进程 + 重试 + 断点续跑**驱动 `probe/scan_all.py`（它只重放 `scan.sh` 里的三种调用形态：`x.py "$ROOT"` / `x.py --root "$ROOT"` / `sh scripts/native/*.sh`；`scan.sh` 本身零改动）。

```
$ python3 probe/scan_all.py --ledger /tmp/t02-scan.done --out /tmp/t02-scan.log
[105/112] provider_rpc_symmetry_check                PASS (10s)
[112/112] auto_approve_bypass_check                   PASS (4s)
SUMMARY: 112 gates, 0 failed
```
（`scan.sh` 全跑一次在并发窗口里被 stall-guard / `Errno 38` 打断两次（停在 5/112、56/112），第三次前台续跑从断点补齐 → **112/112 全绿、0 failed**。`scan.sh` 与 `test_scan.py` 均未改动；CI 侧会按原样跑完整 `bash scan.sh`，那里的读数是权威。）

夹具套件：整跑 `python3 scripts/scan/test_scan.py` 在本窗口里同样被 stall-guard 打断（静默 3 分钟无输出），故改**逐 `test_*` 函数分块**驱动 `probe/testscan_chunks.py`（关键点：必须把 `sys.argv[1]` 设成 repo root，否则 `REPO_ROOT` 变成切片参数、全部用例假红——batch5 t12 的教训）：

```
$ for r in "0 8" "8 16" "16 24" "24 32" "32 40" "40 46"; do python3 probe/testscan_chunks.py --start $1 --end $2; done
SUMMARY: 8 functions, 69 cases, 0 failed
SUMMARY: 8 functions, 110 cases, 0 failed
SUMMARY: 8 functions, 31 cases, 0 failed
SUMMARY: 8 functions, 52 cases, 0 failed
SUMMARY: 8 functions, 53 cases, 0 failed
SUMMARY: 6 functions, 29 cases, 0 failed
→ 46 functions / 344 cases / 0 failed
```
（用例数直接取模块的 `PASS` 计数器增量，不是靠猜输出格式；`FAIL` 计数器全程未增。）

全量跑完后 `git status --porcelain src/` **为空**。

## 5. CI

| 项 | 值 |
|---|---|
| 分支 | `fix/b6-rpcsym-urienc` |
| run | **#2699** / id `40234206640` |
| job | `121292171805`（attempt **1**） |
| 结论 | **success** |
| head_sha | `956f27b0adf16bc09912dcbf000a549e1409a468` = 本地 `git rev-parse HEAD` **逐字符一致**（防并发假绿） |
| 步骤 | 26 步，唯一 `skipped` = `Publish to Releases`（main 门控），其余全 `success` |
| 日志 | 1,486,490 B |

日志判读（逐条核过，不只看 run 结论）：

```
$ grep -c "Summary: 112 passed, 0 failed"   → 1   （带框线那一条；另 8 条小 Summary 是子套件 5/10/14/15/25/28/36）
$ grep -c "ALL 344 CASES PASS"              → 1
$ grep -c " e: "                            → 0   （Kotlin 编译无错误行）
$ grep -o "\[[0-9]\+/112\]" | sort -u | wc -l → 112 （门头编号互异，无缺号/重号）
$ grep -n "FAILED"                          → 6 条，全是 **PASSED 用例名子串**（`… produces FAILED without calling provider PASSED` 等）
```
两门在 CI 里各自跑绿并留下自证痕迹：

```
━━━ [85/112] H/1021-V file preview url encode ━━━
file_preview_url_encode_check: OK (arm1 + arm2 + arm3 green)
━━━ [105/112] provider_rpc_symmetry_check ━━━
provider_rpc_symmetry: ALL GREEN (C1 … C19)
  ✅ file_preview_url_encode_check selftest has teeth (FILE_PREVIEW_URL_ENCODE_SELFTEST=PASS)
  ✅ file_preview_url_encode_check real tree green
  ✅ provider_rpc_symmetry_check selftest has teeth (PROVIDER_RPC_SELFTEST=PASS)
  ✅ provider_rpc_symmetry_check real tree green
```
⇒ 73 臂 / 13 臂在 CI 环境里真实执行过（`selftest has teeth` 是 test_scan 对标记串的断言）。

**已知限制（与 R6 同源）**：门自测的 stdout 不进 CI 日志（`SELFTEST_ARMS=` 在 CI 日志里 0 命中），臂**数量**只有本地可见；CI 侧能见证的是「自测跑过且通过」。源码树与 CI 绿树等价性：报告提交后 `git diff --name-only 956f27b0a..HEAD -- . ':(exclude)fix-reports'` = **0 行**。

**停止节点达成：停在分支上，未合并 main。**

---

## 6. 残留（R1..R6，均 P2，带触发条件 / 触发方案 / 搁置理由）

**R1 · `value_sources` 不认无花括号 `if (c) a else b`（共享层缺口）**
触发条件：任何门需要判「arm / 分支的值来源」且该 arm 写成单个表达式形式的 if/else。
触发方案：改 `_kotlin_valueflow.value_sources` 的 if 分支——不要求 remainder 以 `else` 开头，先在**深度 0** 定位 `\belse\b` 再切两侧（本支的 `_if_arm_parts` 就是这个算法的 12 行原型，可作为补丁蓝本）。
搁置理由：本波硬约束第 2 条「不修改共享模块」（`base/batch5-shared` 由 13 支共享），本支已在门内用 `_arm_sources` 绕过。

**R2 · `_if_arm_parts` 的 `else` 定位是「首个 `\belse\b`」**
触发条件：arm 的 then 侧内联 lambda 里出现 if/else（如 `if (c) run { if (d) x else y } else z`）。
触发方案：改用 `_kotlin_ast.if_blocks`/`enclosing_conditions` 取 arm 自身的 arm 区间，或按深度扫描。
搁置理由：当前真树与夹具都无此形态；且该形态一旦出现，切错 `else` 的方向是「多算来源」→ 可能漏报（正是本门要防的方向），故必须与真树新增臂同时处理。

**R3 · arm1 的操作数边界依赖 `expr_end` 的续行规则**
触发条件：`fileUrlFor` 被改写成多行拼接（`"file://" +` 换行后接 encoder）。
触发方案：改成对 `fileUrlFor` 结果表达式按顶层 `+` 切（arm3a 已有的算法），arm1 只作为跨函数点的粗网。
搁置理由：当前真树是单行；`expr_end` 遇 `+` 行尾会续行，方向是「操作数更长 ⇒ 更容易红」（fail-closed），不会静默放行。

**R4 · arm3b 要求**每个** `loadUrl(` 的实参都来自 encoder**
触发条件：`FilePreviewScreen.kt` 出现第二类 `loadUrl`（`about:blank` / `data:` / `javascript:`）。
触发方案：按 scheme 白名单（`about:|data:|javascript:`）豁免，只对 file/http 形态取严。
搁置理由：当前两处 load 点全是 file URL；误红方向是 fail-closed（门红有人看见），不静默。

**R5 · `_fn_params` 取首个同名声明**
触发条件：`probeAuthHeader` / `fileUrlFor` / `encodeFileUrlPath` 出现重载。
触发方案：按参数个数/类型名匹配，或对同名多声明 fail-closed。
搁置理由：当前三者各只有一个声明；重载出现时方向是「取错参数名 ⇒ 判红」（fail-closed）。

**R6 · `_bound_initialisers`（C6 用）仍按单行取右值 + 门自测臂数不进 CI 日志**
触发条件（前半）：`azureMode` 初值改成多行 `if (…) … else …`。触发方案：改用 `expr_end` 取完整右值再交 `value_sources`。
搁置理由：当前真树单行；截断方向是「来源更少 ⇒ 可能漏 parse ⇒ 判红」（fail-closed）。
触发条件（后半）：需要从 CI 日志断言臂数（防「臂被删而收尾标记仍在」）。触发方案：给两门补逐臂名标记（如 `F70-…=PASS`）并让 test_scan 断言，或让门把臂数写进 stderr（CI 侧 `Summary` 之外仍不落盘，需真改 test_scan —— 属冻结面）。
搁置理由：本波硬约束「`test_scan.py` 零触碰」；现有 `selftest has teeth` 已能防「门被掏空成 `sys.exit(0)`」，臂数漂移的风险等级是 P2。

---

## 7. `test_scan.py` 注册项

两门**已在冻结面注册，本支零改动**：

```
scripts/scan/test_scan.py:2840  ("file_preview_url_encode_check", "FILE_PREVIEW_URL_ENCODE_SELFTEST=PASS", (), (REPO_ROOT,)),
scripts/scan/test_scan.py:2936  ("provider_rpc_symmetry_check", "PROVIDER_RPC_SELFTEST=PASS", (), (REPO_ROOT,)),
```
旧标记串逐字节保留 ⇒ 注册债 0。新增臂数通过**独立行**暴露（`PROVIDER_RPC_SYMMETRY_SELFTEST_ARMS=73` / `FILE_PREVIEW_URL_ENCODE_SELFTEST_ARMS=13`），`test_scan` 目前不断言它们。
**建议（交主会话统一注册，本支不动冻结面）**：追加两条细粒度断言，防「臂被删而收尾标记仍在」：

```python
("file_preview_url_encode_check", "FILE_PREVIEW_URL_ENCODE_SELFTEST_ARMS=13", (), (REPO_ROOT,)),
("provider_rpc_symmetry_check", "PROVIDER_RPC_SYMMETRY_SELFTEST_ARMS=73", (), (REPO_ROOT,)),
```
（可选加强：file_preview 侧追加 `violation-load-site-unconsumed` / `violation-encoder-early-carrier-return` 的逐臂名断言。）

---

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t02

# L1 本门
python3 scripts/scan/provider_rpc_symmetry_check.py .            ; echo rc=$?
python3 scripts/scan/provider_rpc_symmetry_check.py --selftest   | tail -3
python3 scripts/scan/file_preview_url_encode_check.py .          ; echo rc=$?
python3 scripts/scan/file_preview_url_encode_check.py --selftest | tail -3

# L2 反攻（硬验收）
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-A.json --repo . --mode full \
  --only provider_rpc_symmetry_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t02-provider_rpc_symmetry_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-B.json --repo . --mode full \
  --only file_preview_url_encode_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t02-file_preview_url_encode_check.json
# 反攻后必须： git status --porcelain src/   → 空

# L3 邻门（清单见 §4）
for g in webview_holder_url_source_check filepreview_print_bounded_check \
         browser_destroy_wakeup_check provider_write_rules_check \
         collection_copy_on_mutate_check entry_id_rename_integrity_check \
         entry_composite_key_guard_check auto_approve_bypass_check \
         memory_gate_provider_fields_check deeplink_settings_routes_check; do
  python3 scripts/scan/$g.py . || echo "FAIL $g"
done

# L4 全量（沙箱并发窗口下的健康路径：逐门独立进程 + 重试 + 断点续跑）
python3 probe/scan_all.py --ledger /tmp/t02-scan.done --out /tmp/t02-scan.log
python3 scripts/scan/test_scan.py      # 期望 ALL 344 CASES PASS
```
