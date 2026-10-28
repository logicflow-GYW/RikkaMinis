# FIX-R3-C：B09 ui/preview + ui/markdown 四条（P1 两条）

分支 `fix/r3-b09-uiprev` · 基线 `2103729f` · 来源货架报告 B09（`meta-audit/reports/r3/B09-browser-web-2026-10-29.md` F-07/F-08/F-12/F-13，F-03 登记）
绿 sha `7fd5923a` · CI run `40745104626`（#2861 success，`113 passed, 0 failed`）

> 报告命中率 7–64%：全部按真码重新取证，不按行号。三处与报告描述有差异，见 §6。

## 1. 四条总览

| ID | 判级 | 先证结论 | 修法 | 证据 |
|---|---|---|---|---|
| F-13 | **P1** | **成立**：`looksLikeMath` 先扫 `-+=<>` 再判首字符数字 ⇒ `$5 - $10` / `$5-$10` / `$5>$3` 被当公式吞掉两侧 `$` | 判据顺序调换：新增 `isCurrencyShaped`（首字符数字 + 无字母 + 无价格禁用字形）在扫运算符**之前**拒绝；span 内裸 `$` = 跨配对残留，一并拒绝 | 15 例表驱动：基线 3 红（sum of two prices / price range no space dash / price comparison）→ 修复后 15/15；既有 101 例回归全过 |
| F-12 | P2 | **成立**：`prepare()` 在 IO 线程、`release()` 在主线程，同 native 句柄并发 | 每 player 实例一个 `SerialMediaOps` 串行 worker（prepare/release 排队互斥）；`prepareFailed` 加入启用门，把"prepare 失败 ⇒ 按钮永久禁用 ⇒ 外部播放器兜底不可达"的死分支修活 | `SerialMediaOpsTest` 5/5（含 double shutdown 幂等）；CI 全量单测绿 |
| F-07 | P2 | **成立 ×2**：`removeCallbacks(null)` 是 AOSP 文档级 no-op；`OnAttachStateChangeListener` / `OnGlobalLayoutListener` 未解绑 | 走仓库既有 `releasePlan` 顺序：detach（解绑 listener）→ 保持 `webView.removeCallbacks(null)`（**ISSUE-08 C4 契约钉住的位置**，见 §6）→ latch 持有的 hop 按身份移除 → stopLoading/about:blank → `webView.destroy()`；100ms resize hop 与 attach/layout 后 `loadUrl` hop 全部 latch 守卫 + 身份跟踪 | 门 `browser_destroy_wakeup_check` 44 passed / 0 failed；`claim_release_guard` 的 ownership 判据同步补门（解绑前 `view !== webView` 直接 return） |
| F-08 | **P1** | **成立**：`rendererGone` 无人读 ⇒ 渲染进程死后预览永久空白、刷新无效 | holder 持有 `createWebView()` 工厂 + `webView` 改为 `mutableStateOf`；宿主 `key(holder, holder.webView)` + `WebPreviewRendererGone` overlay（读 `holder.rendererGone`）；重试走 `rebuildAfterRendererGone()`（新实例 + 新 latch + 重载） | 形状核查全绿：两宿主各有 `key(holder, holder.webView)` 与 overlay 调用；i18n `webpreview_renderer_gone` ×3 locale 一致；`MediaHostSourceKeyTest` 8/8 |
| F-03 | P2-latent | **成立但不可达**：`WEBAPP_PIN_ENTRY_ENABLED = false`（`WebPreviewBottomSheet.kt:86`）⇒ 按钮不渲染 | **不改代码**，仅登记（§6.2） | `git log -S "WEBAPP_PIN_ENTRY_ENABLED"` 只命中包名重命名 `e24ca02b`，无任何提交翻为 true |

## 2. F-13 —— 货币形态不再被吞成公式

### 2.1 先证（真码 `MarkdownParser.kt::looksLikeMath`）

原顺序：span 先过 `indexOfFirst { it in "+-<>=*/" }` 运算符判定，再看首字符。于是：

- `$5 - $10` → span `5 - ` 命中 `-` ⇒ 当公式，左右 `$` 被吞；
- `$5-$10`（无空格）→ 同上；`$5>$3` → 命中 `>`。

基线装置（`probe-r3c/outpre`，用 `git show 2103729f` 的 `MarkdownParser.PRE.kt` 原样编译）跑 15 例表驱动 = **3 红**，失败名与上述三形态一一对应。

### 2.2 修法（只动 `looksLikeMath` 的判据顺序）

- 新增 `isCurrencyShaped(content)`：**首字符数字** + **无字母** + **无价格禁用字形** ⇒ 货币形态，直接拒；
- 价格禁用字形集合 `NON_CURRENCY_MATH_GLYPHS = "= \\ ^ _ { } ∫ ∑ ∏ √"`：含这些即排除货币（`$2+2=4$`、`$\sqrt{2}$` 仍是数学）；`+ - < >` **故意不在集合里**——它们正是价格区间/比较的形态；
- span 内含裸 `$` ⇒ 跨配对残留（配对扫描跳过被拒 close 后的残余），同样拒绝。

半径：只改 `MarkdownParser.looksLikeMath`；`extractMath` 同款不对称**未扩**（§8 R1，backlog R5 既有登记）。

### 2.3 证据

`MarkdownCurrencyMathTest` 15 例：改后 15/15；既有的 `MarkdownParserNumberedListTest`(18) + `MarkdownParserBlockStructureTest`(73) + `MarkdownListMathWiringTest`(10) = 101/101 回归全过（本地 JVM，从仓库根跑——这仨是源码形状测试，cwd 不对会假红）。

## 3. F-12 —— 音频 prepare/release 串行化

### 3.1 先证

`MinisAudioBlock` 原逻辑：`prepare()` 在 `Dispatchers.IO`（`LaunchedEffect`），`DisposableEffect.onDispose` 在主线程直接 `release()`。Compose 侧取消协程**不能中断已进入 native `prepare()` 的线程** ⇒ 同一 native 句柄被 prepare/release 并发触碰（JNI crash / 未定义状态）。

附带发现（报告没提）：`prepare()` 抛异常时 `prepared` 永为 false，而 clickable 只看 `prepared` ⇒ "用外部播放器打开"兜底对**恰恰需要它的文件**（容器解码不了）永久不可达。

### 3.2 修法

- 新文件 `ui/markdown/SerialMediaOps.kt`：单线程 executor，`runPreparing { }`（同步执行 + 返回结果）/ `enqueue { }` / `shutdown()`（幂等）/ `awaitIdle()`（测试用）。语义 = **prepare 与 release 排队互斥，release 永不阻塞主线程**（不放锁在 UI 线程）。
- `MinisAudioBlock`：`serialOps = remember(player) { player?.let { SerialMediaOps() } }`（键在 player 实例，随文件换新）；`LaunchedEffect(player, resolvedMediaSourceKey(file))` 内 `withContext(Dispatchers.IO) { ops.runPreparing { setDataSource; prepare } }`；`DisposableEffect(player).onDispose` 里 `ops.enqueue { p.release() }` + `ops.shutdown()`。
- `prepareFailed` 状态 + 启用门 `enabled = file != null && (player == null || prepared || prepareFailed)`。

### 3.3 证据

`SerialMediaOpsTest` 5/5：排队顺序、runPreparing 结果透传、shutdown 后拒绝新任务、double shutdown 安全、awaitIdle。

## 4. F-07 —— destroy() 的有效清理与 listener 解绑

### 4.1 先证

- `webView.removeCallbacks(null)`：AOSP `View.removeCallbacks` 开头即 `if (action != null)` ⇒ null 形式是 no-op（门源码注释自己也这么写："a documented no-op (AOSP guards on `action != null`)"）。原注释声称"drains THIS view's run queue"是错的。
- `destroy()` 不过问 `pendingAttachListener` / `pendingLayoutListener` ⇒ 监听器挂在将死的 view 上。

### 4.2 修法（保持门钉的契约 + 补真实的防线）

`destroy()` 走仓库既有 `releasePlan(attached = ...)` 枚举顺序：

1. `DETACH` → `detach()` + `unbindPendingListeners(wv)`（解绑两类 listener）；
2. `DROP_DEFERRED` → 先 `webView.removeCallbacks(null)`（**门契约要求的位置与字面形式**，见 §6.1），再按身份移除 `releaseLatch.release()` 交回的 hop；
3. `STOP_LOADING` → `stopLoading()` + `loadUrl("about:blank")`；
4. `DESTROY` → `webView.destroy()`。

同时 `onPageFinished` 的 100ms resize hop、attach/layout 后的 `loadUrl` hop 全部改为命名 + latch 守卫（`releaseLatch.canRunDeferred() || destroyed`）+ 绑定到捕获的 `wv` 实例。

`unbindPendingListeners(view)` 开头加**所有权门** `if (view !== webView) return`——rebuild 传入的是旧实例，不能清掉新实例刚绑定的监听（`claim_release_guard` 的 [A] 家族判据，CI 首轮就是它红的）。

### 4.3 证据

- `browser_destroy_wakeup_check`：44 passed / 0 failed（改前 42/2）。
- `claim_release_guard`：绿（未加所有权门前 1 finding）。
- `katex_bridge_arity_check`、`mediaplayer_release_guard`、`stale_closure_guard` 等 14 道相关门本地全 rc=0；CI 全量 113/113。

## 5. F-08 —— renderer 死亡后的可达恢复

### 5.1 先证

`rendererGone` 只在 `onRendererGone` 里被置 true，**全仓无读取者**；`webView` 是 `val`，死渲染器无法替换 ⇒ 预览永久空白，刷新按钮也只是 reload 同一具尸体。

### 5.2 修法

- `WebViewHolder`：`createWebView()` 收成工厂；`var webView by mutableStateOf(createWebView()); private set`（发布即触发宿主重组）；新增 `rebuildAfterRendererGone()`：旧实例 unbind + removeCallbacks(身份) + stopLoading + destroy → 新 latch → `hasLoaded = false`、清 `rendererGone/pageFavicon/isLoading` → `webView = createWebView()` → `startIfNeeded()`。
- 两个宿主：`key(holder, holder.webView)`（换实例即重跑 `AndroidView` factory）；`if (holder.rendererGone) WebPreviewRendererGone(onRetry = { holder.rebuildAfterRendererGone() })`；overlay 定义在 `WebPreviewBottomSheet.kt`（internal，全屏屏复用）。
- i18n：`webpreview_renderer_gone` 在 `values/` + `values-zh/` + `values-zh-rTW/` 三处一致（任务书允许"必须新增则三处一致"）。

### 5.3 证据

形状核查（`grep`）：两宿主各 1 处 `key(holder, holder.webView)`、各 1 处 overlay 调用；`createWebView` 私有工厂 + `rebuildAfterRendererGone` 在 holder 内；i18n 3/3。`MediaHostSourceKeyTest` 8/8。

## 6. 报告证据 vs 真码（差异表）

| # | 报告说法 | 真码 | 处理 |
|---|---|---|---|
| 1 | F-07："`removeCallbacks(null)` 无效，应删" | **无效属实**（AOSP null 即 no-op），但仓库门 `browser_destroy_wakeup_check` 把它钉成 ISSUE-08 **C4 契约**：`detach` → `removeCallbacks(null)` → `webView.destroy()`，且必须字面出现 | **保留该行**（CI 必须绿；门代表已决契约），真实防线由**身份移除 + latch 守卫 + listener 解绑**承担；本报告与代码注释都写明"保留是刻意的" |
| 2 | F-12：只提"IO prepare vs 主线程 release 竞态" | 竞态属实；**另有**"prepare 失败 ⇒ 兜底点击永久禁用"死分支 | 两条一起修（`prepareFailed` 进启用门），报告没提第二条 |
| 3 | F-08：只提"rendererGone 无人读" | 属实；且 `webView` 是 `val`，**换实例本身不可能**——报告未指出第二半 | 修法含工厂化 + 发布式状态，宿主按 `(holder, webView)` 重挂 |
| 4 | F-13：只提"先扫运算符再判数字" | 属实；另发现 **span 内裸 `$` 的跨配对残留**（`$5-$10$` 情形）也需拒 | 一并处理；`extractMath` 同款不对称留 §8 R1 |
| 5 | F-03：深链路径缺陷 | `WEBAPP_PIN_ENTRY_ENABLED = false` ⇒ 不可达；`git log -S` 仅命中改名提交 `e24ca02b`，从未翻转 | 只登记，零代码 |

## 7. 验证

### 7.1 本地装置（`probe-r3c/`，全部逐字编译生产切片或用 shim 补依赖）

| 装置 | 结果 |
|---|---|
| `MarkdownCurrencyMathTest`（15 例，真实测试文件） | 15/15；基线（`MarkdownParser.PRE.kt`）3 红 |
| `SerialMediaOpsTest`（5 例） | 5/5 |
| 既有 `MarkdownParserNumberedList/BlockStructure` + `MarkdownListMathWiring` | 101/101（从仓库根跑） |
| `MediaHostSourceKeyTest`（形状，8 例） | 8/8（先 4→5 字面量后） |
| 语法级 kotlinc（5 个改动文件，parse-level） | 全 0 |

### 7.2 门禁（本地 14 道 + CI 全量）

本地：`webview_holder_url_source_check`、`browser_destroy_wakeup_check`(44/0)、`chat_ui_interaction_check`、`file_uri_parse_guard_check`、`webapp_asset_url_cleanup_check`、`chat_session_id_binding_check`、`session_sheet_id_binding_check`、`deeplink_settings_routes_check`、`katex_bridge_arity_check`、`mediaplayer_release_guard`、`claim_release_guard`、`i18n_check`、`browser_history_snapshot_check`、`stale_closure_guard` —— 全 rc=0。

CI：`Run pre-build scan gate` → `Summary: 113 passed, 0 failed`。

### 7.3 CI 三轮（前两轮失败如实记录）

| run | sha | 结果 | 原因 → 修 |
|---|---|---|---|
| `40741383122` #2857 | `d9486c99` | failure @ scan gate | `[21/113] Claim/release ownership guard` ⇒ `unbindPendingListeners` 补 `view !== webView` 所有权门（commit `44ab4134`） |
| `40742762469` #2859 | `44ab4134` | failure @ unit tests | `MediaHostSourceKeyTest` 钉的 keyed-remember 家族 4 处，`prepareFailed` 使其变 5 ⇒ 改 per-file 字面量 STREAM 4 / MARKDOWN 5 + 注释（commit `7fd5923a`，本地 8/8） |
| **`40745104626` #2861** | **`7fd5923a`** | **success** | 门禁 113/113；单测 `BUILD SUCCESSFUL`（4m46s，新测试 20 条 PASSED）；27 步唯一非 success = 23 rolling tag + 24 Publish（main 门控 skip）；总时长 15.3 min |

绿 sha `7fd5923a4cbcdafbe2d38eb0f3f1d8142d4d38e0` 与本地 HEAD **逐字符一致**。

## 8. 残留（登记，未修）

- **R1** `MarkdownParser.extractMath` 的同款不对称（技能文档预览面，非聊天主路径）——既有 backlog R5（B02 支同批登记），本支只改 `looksLikeMath`，**不扩半径**。
- **R2** 货币启发式的代价：`$2+2$`、`$5+3$` 这类"纯常量算式"（首字符数字、无字母/`=`）按任务书指定顺序会判成文本。这是"先排除金额形态"的必然取舍，写在此处备查。
- **R3** F-07 的 `removeCallbacks(null)` 保留属 **C4 契约合规**而非"它有效"（AOSP 仍是 no-op）；若未来门契约变更，真实防线（身份移除 + latch + 解绑）可独立成立。
- **R4** `rebuildAfterRendererGone()` 未加线程断言（假设主线程调用，与 Compose 宿主一致）；多线程调用需重新评审。
- **R5** `SerialMediaOps` 无超时：若 native `prepare()` 永不返回，其后的 `release()` 会一直排队（MediaPlayer 原生 API 无取消手段，只能靠"不阻塞主线程"兜底）。
- **R6** 真机未验：本支证据 = CI 编译级 + JVM/形状测试 + 门禁三重，无设备端实测。
