# FIX-R3-t01 —— 跨门缝三件（file_uri_parse_guard / webview_holder_url_source / browser_destroy_wakeup）

- 支线：`fix/r3-fileuri-webview`（基线 `main` @ `f0f14d46d`，工作树 `round3b/wt/t01`）
- 见证来源：第三轮跨门缝攻击报告 §surface 2（9 条：#61-#69，其中 6 条 dual-gate、2 条 symmetric、1 条 dual-gate 单侧）
- 改动面：**3 个门脚本**，生产 `.kt` **0 行**、`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` **零触碰**
- 判据面：12 / 30 → **16 / 30**（file_uri 12→16 条）、webview_holder 23→30 条、browser_destroy 41→44 条

---

## 1. 三道门各自守什么，这次加固了什么

| 门 | 原有职责（不变） | 本次新增判据 |
|---|---|---|
| `file_uri_parse_guard_check` | `file://` / `minis://` 的 **解析契约**：前缀剥离、`%XX` 解码、`Uri.parse().path` 的单点例外、媒体分类单一来源 | **F2c** `minis://` 剥离必须经 `decodeUriPath`（`resolveMdMediaFile` 内逐站点）<br>**F3b** `toAssetLoaderUrl` 编码的段必须来自 `fileUrl` 实参（非常量、`substringAfterLast`）<br>**F4b** 快捷方式深层链接必须剥掉 `/var/minis` 根<br>**F6** holder 的 `assetRootDir` 必须由**已剥前缀**的 `file://` URL 构造 |
| `webview_holder_url_source_check` | `currentUrl` 的 **映射契约**：唯一来源、正反向映射成对、UA/viewport 策略单一来源、节点重建键 | **W3f** 正向映射编码的段必须来自 `fileUrl`（返回串必须内嵌该绑定）<br>**W8** 反向映射的 root 半边必须是**真实目录**（`File(剥前缀 URL).parentFile`）<br>**W4c** 全屏「外部浏览器打开」必须读 `holder.currentUrl`（W4a 只覆盖了 BottomSheet，对称半边空着）<br>**W5g** 移动分支必须把 UA **切回**非桌面值（与桌面分支的写入值不同、非常量）<br>**W5h** 移动分支的 `setInitialScale` 必须来自策略对象（非常量） |
| `browser_destroy_wakeup_check` | `destroyed` 生命周期契约：释放序列、副作用顺序、延迟块闸门、凭据/UA 写入的 latch | **onRelease 可达性**：`releasePlan`/`removeCallbacks`/`stopLoading`/`destroy` 必须落在活路径上（原来只有顺序判据）<br>**runnable 调度**：命名 Runnable 除 `.track(…)` 外必须真的被 `post(…)`/`postDelayed(…)` 排队<br>**C5 执行链**：`Handler(mainLooper).post { … }` 里调用的函数，必须在**执行时**重查 latch |

---

## 2. 失效轴归类（9 条见证）

| 轴 | 见证 | 形态 |
|---|---|---|
| **asymmetry（对称轴缺一侧）** | 01-2 / 02-3 | 同一契约在 A 处判据覆盖、B 处没有：`W4a`→`BottomSheet` 有、全屏没有；`F4` 只数 `Uri.parse().path` 的**出现次数**，不看这个可执行体（`WebPreviewShortcut`）到底拼出什么 |
| **empty-body / vacuous-mapping** | 01-1 / 02-1 | 映射对**两侧的函数都在**、调用点也都在，但一侧的输入被换成常量：`File(initialUrl)`、`name = ""`。判据锚「名字/调用点存在」⇒ 全绿 |
| **branch-local decode** | 01-3 | 同一个解码器在**主分支**用了、在**跨会话兜底分支**没用了；F2a 只钉 `removePrefix("file://")` 那一次调用 ⇒ 兜底分支的 `minis://` 剥离无人审 |
| **half-contract（注册≠排队）** | 03-2 | 命名 Runnable 的契约有两半：`.track(…)`（撤下）与 `post(…)`（排队）。判据只钉了前半 ⇒ 删掉后半，闸门变死代码、加载永不发生 |
| **text-complete / dead-branch** | 03-1 | 释放序列四个 token 齐、顺序对、`reachable()` 只用于 `post` 块 —— `onRelease` 本体没被可达性检查 ⇒ `if (false)` 包住整段仍然全绿 |
| **spelling-anchored latch** | 03-3 | `C1`/`C3` 只查**写 UA /  арминг凭据**的函数有没有 latch 守卫；`runJsNow` 两者都不写 ⇒ 删掉它的 `if (destroyed) return` 无人发现，而它正是 Looper 跳转后真正执行回调的那一跳 |

一句话概括：**12 条旧判据全部是「某 token / 某调用点存在」，9 条见证全部是「token 在、效果不在」。**

---

## 3. 见证重放（机械复证）

工具：`probe/replay.py`（逐条打补丁 → 按见证列出的每一道门跑 → `git checkout` 还原 → 逐文件 sha256 比对 → `git status -- src` 必须为空）。
记录：`probe/t01-pre.json` / `probe/t01-post.json`，补丁落盘 `probe/t01-w{61..69}.patch`。

### 3.1 修前（BLIND_CONFIRMED = 该条见证列出的每道门 rc 都为 0）

| # | 轴 | 最小补丁（生产文件） | 门 rc（修前） | 判决 |
|---|---|---|---|---|
| 61 | dual-gate | `WebViewHolder.kt` `File(initialUrl.removePrefix("file://"))` → `File(initialUrl)` | file_uri 0 · webview 0 | **BLIND_CONFIRMED** |
| 62 | dual-gate | `WebPreviewShortcut.kt` 去掉 `absPath.removePrefix("/var/minis")` | file_uri 0 · webapp_asset_url_cleanup 0 | **BLIND_CONFIRMED** |
| 63 | dual-gate | `StreamingMarkdownText.kt:1820` 兜底分支去掉 `decodeUriPath(…)` | file_uri 0 · mediaplayer_release_guard 0 | **BLIND_CONFIRMED** |
| 64 | dual-gate | `WebViewHolder.kt` `val name = fileUrl.…substringAfterLast('/')` → `val name = ""` | webview 0 · file_uri 0 | **BLIND_CONFIRMED** |
| 65 | dual-gate | `WebViewHolder.kt` 移动分支 `userAgentString = DESKTOP_UA` + `setInitialScale(100)` | webview 0 · browser_destroy 0 | **BLIND_CONFIRMED** |
| 66 | symmetric | `WebPreviewFullscreenScreen.kt:245` `holder.currentUrl` → `holder.webView.url ?: ""` | webview 0 | **BLIND_CONFIRMED** |
| 67 | dual-gate | `KaTeXView.kt:438` `for (step in releasePlan(…))` 前加 `if (false) ` | browser_destroy 0 · webview 0 | **BLIND_CONFIRMED** |
| 68 | dual-gate | `FilePreviewScreen.kt` 删掉 `post(initialLoad)` | browser_destroy 0 · file_preview_url_encode 0 | **BLIND_CONFIRMED** |
| 69 | symmetric | `BrowserUseManager.kt` 删掉 `runJsNow` 的 `if (destroyed) { … return }` | browser_destroy 0 | **BLIND_CONFIRMED** |

**9/9 BLIND_CONFIRMED · 还原 9/9 · 逐文件 sha256 前后相等 · `git status -- src` 全程为空。**

### 3.2 修后（CAUGHT = 见证列出的门里至少一道 rc=1，且红在新判据上）

| # | 变红的门与判据（逐字取自门输出） | rc |
|---|---|---|
| 61 | file_uri `F6 holder asset root is derived from the prefix-stripped file:// URL` · webview `W8 asset root is a real directory derived from the file:// URL` | 1 · 1 |
| 62 | file_uri `F4b WebPreviewShortcut strips the /var/minis root from the pinned path` | 1（见 §3.3） |
| 63 | file_uri `F2c every minis:// strip in resolveMdMediaFile is decoded by decodeUriPath` | 1（见 §3.3） |
| 64 | webview `W3f toAssetLoaderUrl encodes the file URL's own name segment` · file_uri `F3b toAssetLoaderUrl encodes a name derived from the file URL` | 1 · 1 |
| 65 | webview `W5g mobile branch switches the UA back off the desktop value` + `W5h mobile branch restores the policy's initial scale` | 1（见 §3.3） |
| 66 | webview `W4c fullscreen openExternalFromSheet reads holder.currentUrl` | 1 |
| 67 | browser_destroy `KaTeXView release path runs its teardown plan on a live path`（`releasePlan, removeCallbacks, stopLoading, destroy` 四处全不可达） | 1（见 §3.3） |
| 68 | browser_destroy `FilePreviewScreen deferred runnable initialLoad (line 530) is scheduled on the view queue` | 1（见 §3.3） |
| 69 | browser_destroy `the deferred main-thread hop runJsNow(…) re-checks the destroyed latch when it runs` | 1 |

**9/9 CAUGHT · 还原 9/9 · sha256 相等 · `git status -- src` 为空。**

### 3.3 双门见证里「只红一门」的四条 —— 归属说明（不是漏修）

任务书要求「两门都红，或明确说明该门不该管这条缝并指出由谁管」：

| # | 未红的门 | 为什么不该由它管 |
|---|---|---|
| 62 | `webapp_asset_url_cleanup_check` | 它的 arm8b 审的是 **`ShortcutPinner.pinOutcome` 的消费**（钉完的结果有没有被读），不是深层链接的**路径前缀**。补丁不动 pinOutcome ⇒ 它绿是正确行为。缝归 `file_uri_parse_guard`（F4 的例外清单正好锁着这个文件，F4b 补上「例外点必须真的剥根」）。 |
| 63 | `mediaplayer_release_guard` | 它审播放器/attach 生命周期，`resolveMdMediaFile` 的 URL 解码不在其判据面内（它甚至不扫该文件）。缝归 `file_uri_parse_guard`（F2 家族的唯一解码入口）。 |
| 65 | `browser_destroy_wakeup_check` | 它的 C3 只要求 `toggleDesktopMode` **顶部**有 `if (destroyed) … return` —— 补丁没碰生命周期，只换了两处**值**（UA 字符串、缩放数字）。值来源属于 webview_holder 的「UA/viewport 策略单一来源」面 ⇒ 由 W5g/W5h 承担是正确归属。 |
| 67 | `webview_holder_url_source_check` | 它的 W7d 审 **AndroidView 重建键**（`cacheKey × rendererEpoch`），补丁没动键；「释放计划能不能执行」是 browser_destroy 的 onRelease 面。**若强行让 W7d 管，会把两个无关契约绑在一起**（改释放逻辑就得动键判据），所以只在一侧加固并在本表登记。 |

---

## 4. 修法（判据从「拼写/存在」搬到「效果/可达/值流」）

全部走共享三件套（`_kotlin_lexer.mask_code` / `_kotlin_ast` / `_kotlin_valueflow`），未新增门内自写词法器或花括号计数（本波新增代码里 `match_bracket`/`call_args`/`expr_end`/`value_sources`/`const_value` 全部来自共享层；只在「双视图」处补了一个 4 行适配，见 §7 R6）。

### 4.1 `file_uri_parse_guard_check`（+4 判据，21→25 夹具）

1. **F2c** —— 作用域 = `resolveMdMediaFile` 本体。取**所有** `removePrefix("minis://")` 站点（字面视图，因为结构视图会把字符串内容抹平），取**所有** `decodeUriPath(…)` 的括号区间（结构视图，两视图等长），要求**每个**剥离站点都落在某个解码区间内。补丁只摘掉兜底分支那一次解码 ⇒ 该站点裸露 ⇒ 红。
2. **F3b** —— `toAssetLoaderUrl` 体内必须**恰好一次** `encodePathSegment(…)`；其参数若是裸标识符，回溯一跳 `val <id> = …`；对解析结果取 `value_sources`，要求**无常量形**且**含 `substringAfterLast`**（即"URL 的最后一段"）。`val name = ""` ⇒ 常量形 ⇒ 红；把名字换成 `"index.html"` 之类的常量同样红。
3. **F4b** —— 文件级（该文件本就在 F4 例外清单里）。扫 `.path(…)` 调用，取实参中的标识符，回溯各自的 `val` 绑定，要求**至少一个**绑定同时含 `/var/minis` 与剥离手法（`removePrefix` / `substringAfter` / `replaceFirst` / `replace(`）。补丁让 `resourcePath` 的绑定不再含根路径 ⇒ 无任何绑定满足 ⇒ 红。**不锚函数名**（实测真树里这个函数叫 `pin`，报告正文写的 `requestPinWebPreviewShortcut` 是臆造名——第一版判据因此一次 fail-closed，见 §7 R7）。
4. **F6** —— holder 内**每个** `File(…)`：只要实参提到 `initialUrl`，就必须含 `removePrefix("file://")`；一个 `File(<initial URL>)` 都找不到也算红（fail-closed）。补丁 `File(initialUrl)` ⇒ 红。

### 4.2 `webview_holder_url_source_check`（+5 判据，ARMS 真实断言数 57）

5. **W3f** —— 与 F3b 同缝不同面：先从 `fun toAssetLoaderUrl(` 的形参表取**第一个参数名**（不写死 `fileUrl`），要求 `encodePathSegment(…)` 的实参（回溯一跳绑定后）`value_sources` 里**有形态既含该参数名又含 `substringAfterLast`** —— 即「编码的段确实来自实参」。这两个断言一个盯「非常量」，一个盯「来自实参」，所以 `val name = ""` 与 `val name = "x.html"` 都会被至少一条抓住。
6. **W8** —— 扫 `assetRootDir` 的**赋值**（含声明式，正则允许 `: Type` 标注），对右值做一跳绑定回溯（`parent?.absolutePath` → `val parent/assetParent = …`），拼成一条链文本；链里出现 `File(` 就必须同时出现 `removePrefix("file://")`；一条 `File(` 都没有 = fail-closed。补丁 ⇒ 链里有 `File(initialUrl)` 无剥离 ⇒ 红。
7. **W4c** —— `WebPreviewFullscreenScreen.kt` 内**每个** `openExternalFromSheet(…)`：第二实参必须是 `currentUrl`（`contains_ident`），且不得是 `webView.url` / `webView.getUrl` / `webView.originalUrl`，并要过 `path_feasible`（死路径同样红）。**W4a 的判据函数一行未改**，W4c 是它的对称半边。
8. **W5g** —— 从 `if (desktopMode)` 的 then/else 两个分支分别取 `userAgentString =` 的写入值：移动分支的值**不得等于**桌面分支的值、**不得**是常量；桌面分支写了而移动分支**没写**也算红（那种形态等价于「切不回移动 UA」）。
9. **W5h** —— 移动分支内**每个** `setInitialScale(…)` 的实参：不得是常量（`const_value` 非 None），且必须引用策略局部（`mobileViewportSettings()` 的接收者名）。`setInitialScale(100)` ⇒ 红。

### 4.3 `browser_destroy_wakeup_check`（+3 判据，63→68 臂）

10. **onRelease 可达性** —— 对每个宿主（KaTeX / FilePreview / …）的 `onRelease` 体，对 `releasePlan` / `removeCallbacks` / `stopLoading` / `destroy` 四个位置逐个跑 `reachable()`（既有函数，基于 `if_blocks + guard_feasible`），未可达的具名列出。`if (false) for (…)` ⇒ 四个位置全部不可达 ⇒ 一条红。
    - **精度臂**：把条件换成可行条件 `if (wv.parent != null) for (…)` 必须保持全绿（防空转误伤）。
11. **runnable 调度** —— 对每个 `val <name> = Runnable { … }`：除既有 `.track(<name>)` 外，要求在全文里存在 `post(<name>…)` 或 `postDelayed(<name>…)`（用共享 `call_args` 取实参再 `contains_ident`，不只认字面拼写位置）。删掉 `post(initialLoad)` ⇒ 红。
12. **C5 执行链** —— 对 manager 内每个 `.<post|postDelayed> { … }`（Looper 跳转点），取块内被调用的、且**在同一文件有定义**的函数名（真实即 `runJsNow` / `setUserAgent`），对每个被调函数：执行点 = 体内**首个 WebView 触碰**，没有则**首个非关键字调用**（排除 `if/when/for/while/catch/return/throw/fun/try/else`，否则执行点会落在守卫的 `if` 上，见 §7 R5），要求 `latch_guard(body, 执行点)`。删掉 `runJsNow` 的守卫 ⇒ 红；一条 `post { }` 都找不到 ⇒ fail-closed 红。

---

## 5. 四层证据

| 层 | 判据 | 读数 |
|---|---|---|
| **L1 门自证（`--selftest`）** | 每条新判据都固化成臂；翻绿/翻红必须落在**它自己那条**判据上 | file_uri `FILE_URI_PARSE_GUARD_SELFTEST=PASS (25 fixtures)`（21→25，+4：`r3-01-1`/`r3-01-2`/`r3-01-3`/`r3-02-1`，每条断言"恰好某一条判据红"，红集合精确比对）<br>webview `WEBVIEW_HOLDER_URL_SOURCE_SELFTEST_ARMS=57` + `..._SELFTEST=PASS`（+4 臂：W5g+W5h 双红、W8 单红、W3f 单红、W4c 好坏夹具对；顺带把硬写的 `ARMS=%d % 16` 改成真实 `expect()` 计数 57，见 §7 R1）<br>browser_destroy `BROWSER_DESTROY_WAKEUP_SELFTEST=PASS (68 arms)`（63→68，+5：`r3-03-1` 单红 + 精度对照绿、`r3-03-2` 单红、`r3-03-3` 好/坏夹具对） |
| **L2 见证复攻** | 原见证逐条重放 | §3.2：9/9 CAUGHT，逐条红在预期判据；`probe/t01-post.json` 存原始 rc + 失败行 |
| **L3 负向夹具（`test_scan.py`）** | 三门在冻结夹具表里的注册项 | `<见 §5.1>` |
| **L4 全量门线** | 全部 `*_check.py` / `*_guard.py` + 两个非 glob 门 | **109/109 rc=0**（逐门独立进程，`probe/gate_sweep.sh`，分块跑以避开沙箱 `Errno 38`）＋ `process_logging_gate` rc=0 ＋ `turn_tail_finalize_order` rc=0；本支只改门脚本、生产 `.kt` 零改动，旧门读数不可能变（见 §7 R2 对 `scan.sh` 整跑失败的登记） |

### 5.1 `test_scan.py`

`<§5.1 待回填：ALL 344 CASES PASS 或交 CI>`

---

## 6. CI

`<§6 待回填：run id / job id / attempt / head_sha 三方一致 / 112 passed 0 failed / ALL 344 / 门头 112 互异 / ^ e: 0 / 唯一 skipped = Publish>`

---

## 7. 残留（P2，各带触发条件 / 触发后方案 / 搁置理由）

- **R1 臂数标记曾是硬编码字面量**。`webview_holder` 的 `SELFTEST_ARMS=%d % 16` 是写死的 16，新增 4 臂后仍打印 16（`test_scan` 只断言 `..._SELFTEST=PASS` 子串，所以没人发现）。本支改为真实 `expect()` 计数 57。**触发条件**：任一门的 arms 标记与实际断言数不符（CI 日志里 `_ARMS=` 与 `[OK]` 行数不等即命中）→ **触发后方案**：改成脚本内 `len(re.findall(r"\bexpect\(", selftest_body))` 或运行期计数器 → **搁置理由**：本波只需修正这一个门的错误标记，其余 11 门标记形态各异，统一改造属于独立重构，改判据的批里做会淹没 diff。
- **R2 `scan.sh` 整跑在本沙箱不可用**（`OSError: [Errno 38] Function not implemented`，第 N 次复现）。**触发条件**：本地需要「一次跑完全部门的聚合读数」→ **触发后方案**：`probe/gate_sweep.sh <root> <start> <end>` 分块（实测单块 ≤10 门稳定，25 门会被整壳杀）→ **搁置理由**：CI 本来就跑 `scan.sh`，聚合读数以 CI 日志为准；沙箱侧已有稳定替代路径。
- **R3 F3b/W3f 的两条判据语义重叠**（都是"编码段来自实参"）。本波有意保留：一个钉"非常量"，一个钉"内嵌实参名"，任一被绕过另一条仍红；但**新增第三种拼写**（如 `fileUrl.substringAfterLast('/').let(::encodePathSegment)`）会让两条同时失效。**触发条件**：真树里 `toAssetLoaderUrl` 出现 `::` 引用或 `buildString` 形态 → **触发后方案**：改走 `value_sources` 的 `value_forms` 全形态 + 参数名出现判定 → **搁置理由**：当前实现是单表达式，加形态覆盖属过度装置（装置比例纪律）。
- **R4 F4b 依赖 `val <id> = …` 绑定形态**。若深层链接改为内联表达式（`path("/$sessionId" + absPath.removePrefix(...))`），判据的第一支（实参本身含根路径 + 剥离手法）仍应命中，但**先 strip 到局部再拼**的重构若同时改绑定名，回溯仍成立；真正会失效的是"路径实参只由函数形参拼成"（那时属 fail-closed 红，不静默）。**触发条件**：门在真树上红且代码看起来正确 → **触发后方案**：放宽到「文件内任何 `.removePrefix("/var/minis")` 的出现位置必须支配 `.path(…)`」→ **搁置理由**：现在是 fail-closed 方向（宁可红不可静默绿），不构成静默失效。
- **R5 C5 的执行点选择依赖"非关键字调用"排除表**。多行 lambda 若首行是 `if (x) foo()`，执行点会落在 `foo(`（在守卫之后，语义正确）；但若跳转函数体**第一条**就是 `run { … }` 这类作用域函数，执行点会被算到 `run(`（仍在守卫之后）—— 方向安全。**触发条件**：`BrowserUseManager` 出现"先执行副作用再判 latch"的写法被漏判 → **触发后方案**：把执行点改为"首个 WebView 触碰 ∪ 首个非作用域函数调用"，作用域表从 `_kotlin_valueflow` 的 scope 列表派生 → **搁置理由**：当前只有一个跳转点族，加派生表属过度装置。
- **R6 双视图适配**（结构视图给跨度、字面视图给字符串）在三个门里各写了一小份。**触发条件**：第四个门需要同样的"跨视图取值" → **触发后方案**：上收为 `_kotlin_valueflow.value_at(code, lit, span)` → **搁置理由**：本波只有 3 处、各 3~5 行；上收要动共享层，而本支硬约束禁止改 `_kotlin_*`（共享层缺口应单独提案）。
- **R7 报告正文的函数名不可信**（`requestPinWebPreviewShortcut` 实为 `pin`；见 §4.1-3）。**触发条件**：后续批次任务书引用"某函数名" → **触发后方案**：判据一律按**结构特征**（调用点/实参/绑定）定位，禁止用 `fun <name>` 名字做锚；凡必须用名字的，先 `grep -n 'fun '` 核对 → **搁置理由**：本轮已按结构改造完毕，这条是给后续批次的输入纪律。
- **R8 F6/W8 只钉"前缀已剥"，不钉"剥完还解码"**。真实 `assetRootDir` 构造缺 `decodeUriPath`（含空格/中文的沙箱路径会残留 `%20`）。**触发条件**：沙箱目录名出现非 ASCII 且本地预览首次 404 → **触发后方案**：判据加"File 实参的剥前缀结果必须过 `decodeUriPath`" + 生产侧补解码（**这是缺陷，不是判据缺口**，需另开修复支）→ **搁置理由**：本支硬约束「只改门、不改生产代码」，且该路径当前未在生产中触发（用户沙箱目录为 ASCII）。

---

## 8. 复现命令

```sh
cd round3b/wt/t01

# L1 门自证
python3 scripts/scan/file_uri_parse_guard_check.py --selftest      # …_SELFTEST=PASS (25 fixtures)
python3 scripts/scan/webview_holder_url_source_check.py --selftest # …_ARMS=57 + …_SELFTEST=PASS
python3 scripts/scan/browser_destroy_wakeup_check.py --selftest    # …_SELFTEST=PASS (68 arms)

# 真树
for g in file_uri_parse_guard_check webview_holder_url_source_check browser_destroy_wakeup_check; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"      # 全部 rc=0
done

# L2 见证复攻（打补丁 → 跑门 → 还原 → 校验 src 洁净）
python3 probe/replay.py pre     # 期望 9/9 BLIND_CONFIRMED（在 main f0f14d46d 的门上跑）
python3 probe/replay.py post    # 期望 9/9 CAUGHT，逐条红在 §3.2 的判据上

# L4 全量门线（分块，单块 ≤10 门；沙箱整跑会被 Errno 38 打死）
sh probe/gate_sweep.sh . 1 12
sh probe/gate_sweep.sh . 13 23
# … 直到 109

# L3 负向夹具
python3 scripts/scan/test_scan.py | tail -3   # ALL 344 CASES PASS
```
