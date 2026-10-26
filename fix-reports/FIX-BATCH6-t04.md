# FIX-BATCH6-t04 — `webapp_asset_url_cleanup_check.py` + `i18n_check.py`

支线 `fix/b6-webasset-i18n` · 基线 `base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）· 门提交 `8f1fb1862`（报告提交随后）·
改动面 **2 个门脚本**：`i18n_check.py` +277/−99，`webapp_asset_url_cleanup_check.py` +531/−314（合计 +808/−413）。
**生产 `.kt` 0 行、`res/` 0 行**、`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰；门 112、`test_scan` 夹具 344 不变。

---

## ① 门与轴

| 门 | 见证 | 轴分布 | 反攻结果 | 生产改动 |
|---|---:|---|---|---|
| `webapp_asset_url_cleanup_check.py` | 6 | family-hole ×5（A02-1..5）· dataflow ×1（A02-6） | 修前 0/6 → **修后 6/6 CAUGHT** | 0 行 |
| `i18n_check.py` | 6 | family-hole ×2（A01-1/2）· order ×1（A01-3）· parser-death ×2（A01-4/5）· dataflow ×1（A01-6） | 修前 0/6 → **修后 6/6 CAUGHT** | 0 行 |

两门的病因是同一条：**判据锚「某个 token 存在」，缺陷动的是「这个 token 的效果」**。
十二个见证没有一个是「拼写不对」，全部是「形状对、位置对、名字对，但不生效」：

* 条件还在，但它是常量折叠后的假（`!true && …`、`when (false)`）；
* 守卫头还在，但守卫体被掏空（`{}`）；
* 调用还在，但它在死分支里／在 `return` 之后／在原始字符串里；
* 目标文件还在，但真正被使用的那份值不是它（`loadUrl("…" + file.name)`）；
* 实参位置还在，但传的是常量（`applyLanguageChange(context, prefs, "")`）。

---

## ② 见证原文（file / find / replace / 门为何绿）

### A02-1（family-hole · arm13 C-PIN）

* file：`src/android/app/src/main/java/com/rikkaminis/app/webapp/AddToHomeSheet.kt`
* find：`if (isPinning) return@Button`
* replace：`if (!true && isPinning) return@Button`
* 门为何绿：`_cond_dead_text` 把条件按 `&&`/`||` 切开，逐片与字符串 `"true"`/`"false"` 相等比较。`"!true"` 既不是 `"true"` 也不是 `"false"`，于是「死条件」判定放行 → `check_sheet_arm13` 认为守卫有效。
* 运行时后果：第一帧重组禁用按钮前的第二次点击进 `onClick`，`!true && isPinning` 恒假 → 早退不成立 → 第二个协程再次 `create(` + 两次 `ShortcutPinner.pin(`。

### A02-2（family-hole · arm14 C-CLOSE）

* file：同上
* find：`if (!pinShouldCommit(closed = sheetClosed, inFlight = isPinning)) { cleanupCopiedAttachment(); return@launch }`
* replace：`if (!pinShouldCommit(closed = sheetClosed, inFlight = isPinning)) { }`
* 门为何绿：旧判据只读 `if (` 的条件文本与它的偏移（`_paren_block`），**从不读后面那个块体**，因此「条件完全正确、块体是空 no-op」照样绿。
* 运行时后果：用户取消/下滑关闭后，IO 首帧完成，空 `if` 不 `return@launch`，继续 `webAppShortcutRepository.create(` 与 `ShortcutPinner.pin(`，把已取消的快捷方式写库并弹系统固定提示。

### A02-3（family-hole · arm9 解码线程）

* file：同上
* find：`value = withContext(Dispatchers.IO) { loadBitmapFromUri(context, galleryUri) }`
* replace：`if (false) { withContext(Dispatchers.IO) { loadBitmapFromUri(context, galleryUri) } }` + `value = android.graphics.BitmapFactory.decodeStream(context.contentResolver.openInputStream(galleryUri))`
* 门为何绿：`DECODE_CALLS = ("decodeFileBounded", "loadBitmapFromUri")` 是**拼写白名单**，且只问「是否在某个 `withContext(Dispatchers.IO) { }` 里」——不看那个 IO 块是否可达，也不看 `value =` 实际由谁产出。
* 运行时后果：相册选高分辨率图时 `produceState` 在 Composition Dispatcher 上同步 `decodeStream` 全量解码，弹窗掉帧甚至 OOM。

### A02-4（family-hole · arm10 mount 冷启动）

* file：`src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppPathResolver.kt`
* find：`refresh = { PRootKernel.applyMountedFoldersSnapshot(context) },`
* replace：`refresh = { if (false) PRootKernel.applyMountedFoldersSnapshot(context) },`
* 门为何绿：`check_mount_arm_snapshot_retry` 把可达函数体拼成一个字符串，然后 `"applyMountedFoldersSnapshot(" in closure` —— **包含即成立**，内容是不是死的从不过问。
* 运行时后果：桌面快捷方式冷启动挂载目录 WebApp 时快照还没加载，`refresh()` 被 `if (false)` 短路，重试再次落到 rootfs 占位目录，展示「源文件丢失」。

### A02-5（family-hole · arm3 附件清理）

* file：`AddToHomeSheet.kt`
* find：`onDispose { sheetClosed = true; cleanupCopiedAttachment() }`
* replace：`onDispose { sheetClosed = true; if (false) cleanupCopiedAttachment() }`
* 门为何绿：`check_sheet_arm3` 是纯子串包含（`"cleanupCopiedAttachment()" in _brace_block(onDispose)`），完全不看块内是否有死守卫。**同一形状的 arm4（pin 失败删行）也是 `"...delete(entity.id)" in code`**，`if (false)` 一样打穿。
* 运行时后果：复制到 `filesDir/minis-sessions/<sid>/attachments/<shortcutId>/` 的 HTML 副本与子目录永久残留。

### A02-6（dataflow · arm1 + arm2）

* file：`src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppActivity.kt`
* find：`val url = toAssetLoaderUrl("file://" + file.absolutePath)` … `webView.loadUrl(url)`
* replace：`val ignoredUrl = toAssetLoaderUrl(…)` + `val url = "https://" + ASSET_LOADER_HOST + "/" + file.name`
* 门为何绿：arm1 只禁 `"/${file.name}"` 这个**字面模板**（加号拼接看不见），arm2 只查 `URLEncoder`/`Uri.encode`，两者共同的兜底是 `"toAssetLoaderUrl(" in code` —— 与「它的返回值是喂给 `loadUrl` 的那个值」无关。
* 运行时后果：文件名含空格/中文/`#`/`?` 时 URL 未百分号编码，`WebViewAssetLoader` 匹配不上，白屏或 404。

### A01-1（family-hole · Phase4 活性）

* file：`src/android/app/src/main/java/com/rikkaminis/app/ui/settings/AppearanceScreen.kt`
* find：`applyLanguageChange(context, prefs, code)`（在 `if (code != selectedLanguage)` 内、`selectedLanguage = code` 之后）
* replace：`if (!true) applyLanguageChange(context, prefs, code)`
* 门为何绿：`_condition_is_dead` 只做 `cond.strip() == "false"` / `"false" in parts` / `"true" in parts` 三条例行判断，`"!true"` 一条都不命中。

### A01-2（family-hole · Phase4 活性）

* replace：`when (false) { true -> applyLanguageChange(context, prefs, code); else -> Unit }`
* 门为何绿：`dead_guard_spans` 只扫 `\b(if|while)\s*\(`，`when` 完全不在模型里。

### A01-3（order · Phase4 活性）

* replace：`selectedLanguage = code` 之后插 `if (true) return@OnSharedPreferenceChangeListener`，再 `applyLanguageChange(…)`
* 门为何绿：`if (true)` 的块本身不是「死块」，判据里也没有「同深度无条件退出之后不可达」的概念。

### A01-4（parser-death · Phase1/4 词法）

* replace：`val deadRaw = """" applyLanguageChange(context, prefs, code) """`
* 门为何绿：自写 `mask_kt_comments` 用单个 `"` 翻转 `state='str'/None`，四个连续引号翻两次回到「代码态」，字符串内容被当成真实代码。

### A01-5（parser-death · Phase1 资源定义）

* file：`src/android/app/src/main/res/values/strings.xml`
* find：`<string name="session_memory_title">Memories in Session</string>`
* replace：`<eat-comment name="session_memory_title"/>`（等价更狠形态：`<item type="id" name="…"/>`）
* 门为何绿：`KEY_RE = re.compile(r'name="([a-z0-9_]+)"')` 只抓裸 `name=`，不看外层标签是不是 `<string>`。

### A01-6（dataflow · Phase4 实参）

* replace：`applyLanguageChange(context, prefs, "")`
* 门为何绿：判据只问 `applyLanguageChange(` 是否出现在非死区间，**从不看实参**。
* 运行时后果：外部写入 `ja` 时监听器真的执行，把 `LocaleManager.applicationLocales` 重置为空（系统默认）并把 `KEY_LANGUAGE` 覆写回 `""` —— 比不调用更坏，是反向破坏。

---

## ③ 判据改造（旧形态 → 新形态）

底层一律换共享原语：`_kotlin_lexer.code_view`（长度不变的结构通道）、`_kotlin_ast.{match_bracket, if_blocks, const_bool}`、`_kotlin_valueflow.{literal_view, value_sources, guard_feasible, path_feasible, call_args, expr_end, contains_ident}`。
**删除的自写件**：`strip_comments`（webapp）、`mask_kt_comments`（i18n）、`_balanced_within`、`_cond_dead_text`、`_condition_is_dead`、`dead_guard_spans`、`_paren_close`/`_brace_close`、`_expr_end`、`_top_level_expr_after`、`DECODE_CALLS` 拼写元组。

| 臂 | 旧判据 | 新判据 |
|---|---|---|
| webapp arm13（A02-1） | `_cond_dead_text`：操作数与 `true`/`false` 字面比较 | `guard_feasible(cond)`（`!true && x` / `(false) && x` / `1 == 2` 全折）+ `path_feasible` 判守卫自身可达 |
| webapp arm13（A02-2 自疑） | 尾串 `re.match(r'\s*(?:\{\|return\b\|return@)', tail)` —— 见到 `{` 就成立 | 守卫必须**在分支体内退出**：`_exits_block(then_branch)`（`return@Button`/`return@x`/`throw`），空块不算 |
| webapp arm14（A02-2） | 只读 `if (` 的条件文本 + 偏移 | `_if_sites`（条件偏移 + then/else 区间）：守卫必须在 staging IO 之后、`create(` 之前，条件引用被 latch 的 `remember` 状态且可行，**且某个分支体真的退出协程** |
| webapp arm9（A02-3） | 拼写白名单 `("decodeFileBounded","loadBitmapFromUri")` +「在某个 IO 块内」 | `DECODE_CALL_RE` 解码家族（含 `BitmapFactory.decode*`）+ `enclosing_live_io`：IO 跳本身必须在可达路径上 |
| webapp arm3/arm4（A02-5） | 子串存在于 `onDispose` 块 / 全文件 | `_live_at(block, site)`：`path_feasible` + 本门补的表达式 `else` 缺口；arm4 同样走活性 |
| webapp arm1/arm2（A02-6） | 禁字面模板 `"/${file.name}"` + `"toAssetLoaderUrl(" in code` | `_load_url_bound_to_single_source`：每个 `loadUrl(x)` 的实参经 `value_sources` + **一跳局部 `val` 绑定**后必须求值到 `toAssetLoaderUrl(…)`（`_value_origins`） |
| webapp arm10（A02-4） | `"…(" in closure`（字符串包含） | `_has_live_call(closure_bodies, name)`：逐个可达函数体，调用点必须可达 |
| i18n Phase4（A01-1/2/3） | `_condition_is_dead` 三条例行 + 只扫 `if/while` + 无「退出后不可达」概念 | `_reachable_at = path_feasible && !_when_arm_unreachable && !_unreachable_after_exit`：常量折叠 + `when(subject)` 臂匹配 + 同块「无条件退出」之后的语句 |
| i18n Phase4（A01-4） | 自写 `mask_kt_comments` | `code_view`（`KotlinLexError` → 报违规，fail-closed） |
| i18n Phase1（A01-5） | `name="…"` | `defined_string_keys`：`<string name=…>` / `<item type="string" name=…>` 才算定义（`<eat-comment>`、`<item type="id">`、`<attr>` 明确不算） |
| i18n Phase4（A01-6） | 只查调用名 | 实参数据流：任一实参经 `value_sources` + 一跳局部绑定后必须含 preference 读取（`prefs.get*` / `get*Prefs(...).get*`） |

**精度（不许误红）**：每类新判据都配了 clean 对照臂（webapp 46 条 `selftest ok`，i18n 20 条 fixture）：
`if (false) …` 与活的调用并存仍绿、`Uri.encode(sessionId)` 仍绿、`when(subject)` 非常量不判死、`<item type="string">` 仍算定义、`applyLanguageChange(context, prefs, prefs.getString(…))` 仍绿。

**装置自伤与修法（可复用）**：i18n 首版 `_unreachable_after_exit` 用 `stmt_spans(text)` 扫整段 arm 文本，而 arm 体以 `KEY_LANGUAGE -> {` 开头 ⇒ 整段只有一条深度 0 语句，永远看不到内部语句（A01-3 假绿）。修法 = 先取 pos 的**最内层块**（`_innermost_block` + `match_bracket`），再在块内做语句扫描。

---

## ④ 四层证据

### L1 本门

```
$ python3 scripts/scan/webapp_asset_url_cleanup_check.py .
[webapp-asset-url] PASS all 15 arms (asset-url single source ×2, sheet cleanup ×2,
  fullscreen single source ×2, title seed + user-edit guard ×1, pin verdict ×2,
  decode off the composition thread ×1, sheet in-flight guard ×1,
  sheet close latch ×1, mount cold-start snapshot retry ×3)          rc=0

$ python3 scripts/scan/webapp_asset_url_cleanup_check.py --selftest
... 46 条 selftest ok（含新增 A02-1/1b/2/2b/3/3b/4/5/5b/6）
WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS
WEBAPP_ASSET_URL_CLEANUP_WITNESS_ARMS=PASS (10 arms: A02-1/1b/2/2b/3/3b/4/5/5b/6)

$ python3 scripts/scan/i18n_check.py .
✅ No orphan string keys (1582 code refs, 1589 definitions)
✅ every registered locale ships strings behind it (7: en, zh, zh-TW, ja, ko, de, ru)
✅ the language listener applies the stored language through the shared function
Done: ALL CLEAN ✅                                                    rc=0

$ python3 scripts/scan/i18n_check.py --selftest
I18N_CHECK_SELFTEST=PASS (20 fixtures, 0 mismatched)
```

新增自检臂全部是「变异红 + 真树绿」双证：每条臂都在夹具上红在**它宣称的那条判据**上（webapp 打印 `arm13 red=…` / `arm14 red=…` / `arms=arm9` 等，i18n 打印 `expect_in` 文案），clean 臂同时断言不误红。

失败方向也是判据（fail-closed 探针）：把一个含未闭合字符串的假树喂给 `evaluate_tree`，得到
`['kotlin-lex:unterminated_string_literal_at_offset_376']` —— 词法读不懂的树一律红，不会被当成"没问题"。

### L2 反攻（硬验收）

```
$ python3 …/verify_batch5.py --witnesses …/witnesses-A.json --repo . --mode full \
    --only webapp_asset_url_cleanup_check.py --out …/probe/t04-webapp-post.json
[1/6] A02-1 -> CAUGHT (rc=1, exact)     tail: FAIL arms=arm13
[2/6] A02-2 -> CAUGHT (rc=1, exact)     tail: FAIL arms=arm14
[3/6] A02-3 -> CAUGHT (rc=1, exact)     tail: FAIL arms=arm9
[4/6] A02-4 -> CAUGHT (rc=1, exact)     tail: FAIL arms=arm10
[5/6] A02-5 -> CAUGHT (rc=1, exact)     tail: FAIL arms=arm3
[6/6] A02-6 -> CAUGHT (rc=1, exact)     tail: FAIL arms=arm1,arm2
SUMMARY: {'CAUGHT': 6}      restored: True ×6

$ … --only i18n_check.py --out …/probe/t04-i18n-post.json
[1/6] A01-1 CAUGHT … [6/6] A01-6 CAUGHT      SUMMARY: {'CAUGHT': 6}   restored: True ×6
```

* 每条见证都红在**它攻击的那条判据**上（webapp 逐臂点名，i18n 六条均为 `PROBLEMS FOUND: 1` + 对应文案，见 `probe/t04-i18n-post.json`）。
* 反攻前后 `git status --porcelain src/` 为空（无变异残留）。
* 64 位 sha256 前后一致（驱动内置 `orig_sha` 校验，`restored=True` 即还原后逐字节相同）。

### L3 邻门（10 道，rc 全 0）

```
webview_holder_url_source_check         rc=0    file_preview_url_encode_check      rc=0
open_url_broker_scheme_check            rc=0    deeplink_settings_routes_check     rc=0
chat_ui_interaction_check               rc=0    prefs_listener_holder_guard        rc=0
tool_result_image_branch_check          rc=0    memory_ui_wiring_check             rc=0
four_way_sync_check                     rc=0    filepreview_print_bounded_check    rc=0
```

### L4 全量

```
$ bash scripts/scan/scan.sh
╔══════════════════════════════════════════════════╗
║  Summary: 112 passed, 0 failed                   ║
╚══════════════════════════════════════════════════╝        EXIT=0   (4m58s)
```

`test_scan.py`：单进程整跑在本机被 stall-guard 判「无输出且无 CPU 180s」杀掉（子进程期主进程空转），按既有手册改**逐 test 函数**跑（`probe/t04_testscan_chunks.py`，46 个函数一条一行，崩溃点可见）：

```
TOTAL: 344 pass / 0 fail        (46/46 函数全 OK，9m04s)
```

结论与 `ALL 344 CASES PASS` 等价（计数来自 `test_scan.PASS/FAIL` 计数器本身，非自造断言）。CI 侧仍是官方单进程跑法，读数见 ⑤。

---

## ⑤ CI

| 项 | 读数 |
|---|---|
| run | **#2697** / id `40231717901` / job `121284671516` / attempt **1** / `conclusion: success` |
| head_sha | `8f1fb1862aa4ea65c51f71e8ae4dd4670e291577`，与本地 `git rev-parse HEAD` **逐字符一致** |
| 步骤 | 26 步，唯一 `skipped` = `Publish to Releases`（main 门控；本支是分支），`non-success` = 0 |
| 日志 | `probe/ci/job-121284671516.log`，**1,486,378 B** |

判读（逐条机械核对，不看"绿了"这三个字）：

| 判据 | 读数 |
|---|---|
| `║  Summary: 112 passed, 0 failed`（带框线，权威形态） | **恰 1 条** |
| `ALL 344 CASES PASS` | **恰 1 条** |
| 门头 `━━━ [N/112] …` 编号 | **112 个互异** |
| ` e: `（Kotlin 编译错误标记） | **0** |
| 含 `FAILED` 的 6 行 | 全是 PASSED 用例名子串（`…produces FAILED without calling provider PASSED` 等） |
| 本支两门在 CI 内执行 | `Step 3 Run pre-build scan gate` = `sh scripts/scan/scan.sh` + `python3 scripts/scan/test_scan.py`，**该步 `success`** ⇒ 112/112 与 344/344 在 CI 上各有一份独立读数 |
| webapp 自检臂有见证 | 日志 `:1294` `✅ webapp_asset_url_cleanup_check selftest has teeth (WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS)` |
| i18n 自检臂有见证 | **无** —— `i18n_check` 未在 `test_scan.py` 注册（即 ⑥ R7 / ⑦ 待注册项），CI 只跑了它的真树判据 |

源码树与 CI 绿树一致：报告提交后 `git diff --name-only 8f1fb1862 HEAD -- . ':(exclude)fix-reports'` = **0 行**。

**停止节点**：分支 CI 绿即本支结束，**未合并 main**（起手权在用户/主会话）。

---

## ⑥ 残留 R1..R8（P2，登记不修，交主会话）

| 编号 | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | **共享 `_branch_extent` 吃掉同一行的 `else`**：`value = if (true) null else withContext(IO){decode()}` 在 `if_blocks` 里被当成「then 分支吞掉整个 else」，decode 看起来可达 | 下一个门要判「表达式形状的 if/else 分支活性」（A02-3 的自疑形态已在本门内遇到） | 在 `_kotlin_ast._branch_extent` 里把 depth-0 的 `else` 也当分支终点（本轮已在门内用 `_branch_end`/`_else_extent`/`_dead_spans` 补上，可直接搬进共享层） | 硬约束 #2：共享模块本轮不许改；本门内已自足 |
| R2 | **`enclosing_conditions` 不建模 `when (subject)` 的 subject**：`when (false) { true -> … }` 只回臂模式 `'true'`，被当成可达 | 任何门要判「when 分支可达性」（A01-2 即此形态） | `when_branches` 已返回 subject；在 `enclosing_conditions` 里对常量 subject 与常量臂模式做匹配，不匹配 → 判死 | 同上；i18n 门内 `_when_arm_unreachable` 已覆盖（仅常量 subject × 常量模式的窄面） |
| R3 | **值流只跟一跳局部 `val`**：`val code = readLanguage(prefs)` 这类间接读取不会被 i18n 的实参判据认作 preference 读取 | `AppearanceScreen` 把语言读取抽成 helper 函数时 | 用 `function_body` 对 helper 做一次体内检查（体内含 `prefs.get*` 即认） | 当前树是直接 `prefs.getString(key, "")`；抽 helper 属重构，未发生 |
| R4 | **arm2 仍有拼写锚**：`URLEncoder.encode(` / `Uri.encode(file.…` 两条直查 | 有人改用 `android.net.Uri.encode(path)` 之外的第三方编码器，且结果仍经 `loadUrl` | 把「第二个编码器」也做成家族正则（`\b\w*encode\w*\s*\(` 白名单式反向），或直接依赖 `loadUrl` 绑定（主判据已覆盖效果面） | 主判据（绑定）已挡住 A02-6 全部形态；拼写锚只作为「同源」提示，误红风险高于收益 |
| R5 | **arm9 解码家族是白名单**：`ImageDecoder.decodeBitmap(`、经变量持有的 `factory.decodeStream(` 未覆盖 | 有代码改用 API 28 的 `ImageDecoder` | 扩 `DECODE_CALL_RE` 分支（`ImageDecoder\s*\.\s*decode\w+`）+ 变量别名无法静态识别（登记为不可达，除非做类型推导） | 当前树只有 `decodeFileBounded` / `loadBitmapFromUri` / `BitmapFactory.decode*` 三种 |
| R6 | **`_if_sites` 用「两个迭代器 zip」对齐条件偏移与区间**：若 `if_blocks` 跳过某个括号不配平的 `if (`（`p_close < 0`），zip 会整体错位 | 树里出现括号不配平的 `if`（语法错误级），或 `if_blocks` 改变跳过策略 | 让 `if_blocks` 直接返回条件偏移（共享层加字段），门里删掉 zip | 错位方向的后果是「守卫判据找不到 → 臂变红」，即 fail-closed，不会放行缺陷 |
| R7 | **i18n 的 `--selftest` 在 `test_scan.py` 里没有断言**（`i18n_check` 只在 `test_real_repo` 里被跑真树），20 条 fixture 在 CI 里无见证 | 有人删掉 i18n 自检臂，CI 不会红 | 见 ⑦ 的注册建议（一行） | `test_scan.py` 本轮冻结 |
| R8 | **arm3 的 `onClick` 兜底只在「没有 `DisposableEffect(`」时启用**（保留原语义：没有 onDispose 时允许显式 Cancel 清理） | 有人同时保留 `DisposableEffect(` 与 onClick 清理，且 onDispose 里是死调用 | 需要区分「DisposableEffect 存在但其 onDispose 不含清理」→ 那时 onClick 兜底应启用 | 现有夹具与真树都不出现该组合；改判据会扩大误红面 |

---

## ⑦ `test_scan.py` 注册项（交主会话统一注册，本支不改冻结文件）

| 门 | 标记串 | 形态 | 现状 |
|---|---|---|---|
| `webapp_asset_url_cleanup_check` | `WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS` | `()` + `(REPO_ROOT,)` | **已注册**（`test_scan.py:2810`，本轮实测该夹具通过） |
| `webapp_asset_url_cleanup_check`（新增臂） | `WEBAPP_ASSET_URL_CLEANUP_WITNESS_ARMS=PASS` | 同上（可同名追加一条断言） | 建议新增：专盯 10 条见证臂的收尾行，防「臂被删而主标记仍在」 |
| `i18n_check` | `I18N_CHECK_SELFTEST=PASS` | `()` + `(REPO_ROOT,)` | **未注册**，建议加入 `DA_AUTO_APPROVE_GATES` 同族表：现在这 20 条 fixture 在 CI 里没有任何见证 |

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t04

# 共享原语自证（必须全绿再动手）
python3 scripts/scan/_kotlin_lexer.py --selftest          # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest            # 32 arms
python3 scripts/scan/_kotlin_valueflow.py                 # 26 arms

# L1 本门
python3 scripts/scan/webapp_asset_url_cleanup_check.py .
python3 scripts/scan/webapp_asset_url_cleanup_check.py --selftest
python3 scripts/scan/i18n_check.py .
python3 scripts/scan/i18n_check.py --selftest

# L2 反攻（6 条见证 ×2 门，必须 12/12 CAUGHT 且 restored 全 True）
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-A.json --repo . \
  --mode full --only webapp_asset_url_cleanup_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t04-webapp-post.json
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-A.json --repo . \
  --mode full --only i18n_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t04-i18n-post.json

# L3 邻门
for g in webview_holder_url_source_check file_preview_url_encode_check \
         open_url_broker_scheme_check deeplink_settings_routes_check \
         chat_ui_interaction_check prefs_listener_holder_guard \
         tool_result_image_branch_check memory_ui_wiring_check \
         four_way_sync_check filepreview_print_bounded_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1; echo "$g rc=$?"
done

# L4 全量
bash scripts/scan/scan.sh                       # 112 passed, 0 failed
python3 probe/t04_testscan_chunks.py            # 344 pass / 0 fail（逐函数跑，规避 stall-guard）

# 收尾体检
git status --porcelain src/                     # 必须为空
```

---

**一句话回报**：`webapp_asset_url_cleanup_check`（6 条 · family-hole×5 + dataflow×1）与 `i18n_check`（6 条 · family-hole×2 + order×1 + parser-death×2 + dataflow×1）判据从「token 存在」改到「效果达成」，反攻 **12/12 变红**（restored 12/12），生产 `.kt` 0 行，残留 8 条（P2，各带触发条件）。
