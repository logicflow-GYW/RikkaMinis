# FIX-R3-B — B09 browser 核心九条（F-01/02/04/06/09/10/11/14 修，F-05 登记）

- 支：`fix/r3-b09-browser`，基线 **`2103729f`**（main）
- 来源报告：`meta-audit/reports/r3/B09-browser-web-2026-10-29.md`（**命中率 0–64%，本批最低**）
- 判级表：`meta-audit-1029/TRIAGE-R3.md`
- 改动面：**6 文件 +1078 / −43**（生产 3：`BrowserTabPool.kt` +261/−24、`BrowserUseManager.kt` +197/−14、`BrowserUseJS.kt` +9/−5；新测试 3：`BrowserTabHeldSplitTest` / `BrowserFetchStatusPolicyTest` / `BrowserTeardownAndFailureSignalTest`）
- `scripts/scan/**`、`scan.sh`、`test_scan.py`、`ui/**`、`backup/**`、`provider/**`、`conversation/**` **零触碰**

---

## 1 结论速览

| ID | 判级 | 报告命中 | 结论 | 修法 |
|---|---|---|---|---|
| F-01 | P1 | ✅ 机制成立，两处细节不准 | 已修 | `destroyManagerOnMain()` 收敛 WebView teardown 到主线程 |
| F-02 | P1 | ⚠️ 一半过期 + 真缺陷在后半 | 已修 | `rendererDead` 惰性闩 + navigate 短路 + JS/截图守闩 |
| F-04 | P1 | ✅ 核心成立，字段名过期 | 已修 | 拆 `Tab.graceHeld` / `isHeld`，`inUse` 语义收敛为「执行中」 |
| F-05 | P1 | ✅ 成立，一处细节证伪 | **登记**（修点在禁碰文件） | 需 `ui/chat/ChatViewModel.clearChat` 改调用 |
| F-06 | P2 | ✅ 成立 | 已修 | 主 Looper `postDelayed` + `runJsNow` 重查闩 |
| F-09 | P2 | ✅ 成立（一半登记） | 已修 | 2xx 门 + 有界 Location 追跳；非原子写入登记 |
| F-10 | P2 | ✅ 成立（签名过期） | 已修 | `onBlobDownloadFailed` 漏斗 + FAILED 面板条目 |
| F-11 | P1 | ✅ 两个方向都在，JS 侧细节证伪 | 已修 | 纯函数 `fetchHttpFailure` + 空体 2xx 成功 + `\r` 转义 |
| F-14 | P2 | ✅ 成立 | 已修 | `onReceivedHttpError`（主帧）记录 → 调用点传参 |

---

## 2 环境与基线

- worktree：`/var/minis/shared/work/meta-audit-1029/wt-b09-browser`
- 基线 `2103729f` = B02 渲染族合并后的 main；本支两提交：`408e21b9`（九条主体）+ `072d7180`（F-10 收口）
- 报告绝大多数行号与本树不符（报告基于 `47c4bf4b`），**全部按语义重新取证**；函数名也换过（`ImplicitLease`/`holdWindowAfter` 已不存在）

---

## 3 逐条（先证后修）

### F-01 [P1] 后台线程上的 `destroy()` → 渲染进程泄漏 — 成立

**真码**：`destroyTab()` 尾部 `tab.manager.destroy()`；而 `BrowserUseManager.destroy()` 先 `destroyed = true`，再在**单个 `try`** 里依次 `webView.stopLoading()` / `stopLoading`… `webView.loadUrl("about:blank")` / `removeAllViews()` / 换 `webViewClient` / `webView.destroy()`，任何一步抛异常都被同一个 `catch` 吞掉。

**触发链（真实存在）**：`MinisApp:739/765/785` 三个内存钩子直接调 `sharedBrowserTabPool.evictIdleTabs()`——`MemoryPressureGate.reclaimHook`、`OffloadRssProbe.governanceHook`、`AppMemoryGovernor.dropCachesHook`；其中 `OffloadRssProbe.record()` 由 **NativeOffload 的 socket 线程**调用（`sandbox/NativeOffload.kt:370`），`dispose()` 来自 `ViewModel.onCleared`。WebView 方法是 `checkThread` 的，非主线程必抛 ⇒ `destroyed` 已置位 ⇒ 之后无人重试 ⇒ 每个被收的标签泄漏 50–100MB。

**报告不准的两处**：① 报告没提 `destroyed = true` 已经提前锁死——这正是「永不重试」的关键，我在注释和测试里点明；② 报告说「`evictIdleTabs` 在 `onTrimMemory` 的后台线程」——`onTrimMemory` 本身是主线程回调，真正的后台来源是上面三个钩子。

**修法**：`destroyManagerOnMain(manager)` —— 在主线程内联，否则 `Handler(Looper.getMainLooper()).post { manager.destroy() }`；`destroyTab` 的列表簿记（移除 / `savedURLs` / `tabLocks`）仍**同步**，只有 WebView teardown 跨线程。

**为什么 hop 不放 `BrowserUseManager.destroy()`**：那个函数体被 `browser_destroy_wakeup_check` 的形状判据整体钉住（两个 waiter 唤醒、latch 可行且极性正确、`removeCallbacks(null)` 在 `webView.destroy()` 之前、detach 在回调排空前）。把 body 挪进 helper 会把这一整套判据一起搬走；线程规则放在唯一知道所有拆除触发点的池里更安全。

**证据**：臂 A3（改回 `tab.manager.destroy()`）→ `tab teardown routes the manager destroy through the main thread` 变红。

### F-02 [P1] 唤醒后的 `navigate()` 仍读死页面 → 悬到工具超时 — 成立（报告前半已过期）

**证伪部分**：报告说 `onRendererGone`「既不清 `_isLoading` 也不清 `navigationDeferred`」——当前树 GH#341 已做（`_isLoading=false`、`complete(FAILED, "WebView renderer process was killed by the system")`、`navigationDeferred=null`），async-JS waiter 也已由 1019 ISSUE-08 唤醒。

**真缺陷**：醒来之后 `navigate()` 无条件 `navigationDetails()` → `evaluateJavascript()`（`withContext(Main)` + 裸 `deferred.await()`，**无 timeout / 无闩**）。渲染进程死时 `evaluateJavascript` 的回调永不回调 ⇒ 悬到工具 deadline（~120s），然后报一个与事实无关的错误。`attachSnapshot` 那条路同理（`captureWebViewBitmap` 触碰死 WebView）。

**修法**：
1. `@Volatile rendererDead` 惰性闩，`onRendererGone` **最先**置位（不复用 `destroyed`——那会让真 `destroy()` early return，反而泄漏 WebView，已写进 KDoc）；
2. `webViewInert() = destroyed || rendererDead`；
3. `navigate()` 入口 `if (webViewInert()) return webViewGoneResult()`（区分「已销毁」与「渲染进程被杀」，可辨识）；
4. 元数据行 `details = if (webViewInert()) "  (no live WebView — …)" else navigationDetails()`；
5. `evaluateJavascript` 首部守闩，返回 bridge 自己的 reject 形状 `{"error": …}`（`evaluateAndReturn` / `evaluateJSAndParse` 会把它当错误返回，而不是当正文）；
6. `captureWebViewBitmap` 守闩返回 null。

**证据**：`navigate refuses a dead tab…` / `no JS wait or capture outlives the WebView` 两条（当前树绿）。

### F-04 [P1] `inUse` 一个标志兼表两义 → 默认标签页锁 UI 15s — 成立（字段名过期）

**报告过期的部分**：`ImplicitLease` / `holdWindowAfter` 在当前树**不存在**（已重构为 `Tab.inUseGraceJob` + `armImplicitGraceRelease`）。

**成立的核心**：`publishAgentBusy()` 就是 `_tabs.value.any { it.inUse }`；`armImplicitGraceRelease` 在 `finally` 里 `setTabInUse(tab, true)` 并起 15s 定时器 ⇒ **agent 已收工、BrowserSheet 的「Minis is browsing | Takeover」与前进/后退/刷新仍锁 15s**；Takeover 中途按下后，该 action 的 `finally` 又把 `inUse` 锁回。

**修法（关键取舍）**：`agentBusy` 的**承载式不动**——`browser_tabs_lifecycle_check` C9 与 `BrowserTabPoolBusyPublishTest` 都钉 `_tabs.value.any { it.inUse }`，它们防的是「写入点忘了发布」的陈旧标志，那个立场是对的。改的是 **`inUse` 的语义**：

- `Tab.inUse` = 「有 agent action 正在这个标签上执行」；
- 新增 `Tab.graceHeld` = 「执行结束后 15s 的复用保留」；
- `Tab.isHeld = tabHeld(inUse, graceHeld)`（纯函数，JVM 可测）——所有**只表示「别把这个标签交给别人」**的读取改走 `isHeld`：acquireTab 扇出 ×2、trim 两级、evict 受害者过滤（后两处同时抽出纯函数 `isIdleEvictionCandidate`）；
- `finally` **先** `setTabInUse(tab, false)` **再** re-arm 保留；
- 接管（`releaseAllTabs`）、再占用（`acquireTab`）、显式 tab_id 释放路径都**显式退掉**保留。

净效果：执行结束 → `inUse=false` → `agentBusy=false`（UI 立即解锁）；15s 保留仍在，所以并发 tab-less acquire 的扇出不退化（`T-browser-implicit-tab-inuse-until-load` 的行为是原样保留的）。

**证据**：本类 10 例；臂 A1（保留改回 `setTabInUse(tab,true)`）红、A2（busy 改读 `isHeld`）红。

### F-05 [P1] `releaseAllTabs()` 不销毁也不清盘 — 成立，但修点在禁碰文件 → 登记

**真码**：`releaseAllTabs()` = 逐个 `setTabInUse(false)` + `publishAgentBusy()` + `saveState()`（**反而会写盘**）；`saveState()` 对自有池写 `browser_tabs/<sid>.json`。真正的「清聊天就清浏览器」由 `ui/chat/ChatViewModel.clearChat` 在调用之后删文件完成。

**证伪一处**：报告说「15s grace job 里 `saveState()` 把刚删的文件写回」——当前树 grace job 只 `updateTabs()`，**不写盘**。真实的复活来源是**任何后续** `saveState()`（开 sheet / trim / evict / setSession / 下一次 action 的 finally）。

**为什么不修**：调用方是 `ui/chat/ChatViewModel.kt`，本支明令禁碰。最小方案（留给后续支）：池新增 `clearAllTabs()`（销毁 + `isDisposed`-style 抑制一次持久化），`clearChat` 改调它再删文件。→ §6 R1

### F-06 [P2] `view.postDelayed` 派发 resize — 成立

分离的池标签永远不会 attach ⇒ `View.post*` 把 runnable 停在 `HandlerActionQueue`（永不 drain）；destroy 路径的 `webView.removeCallbacks(null)` 对 null token 是**文档明确的空操作**（门 C4 又要求保留该调用）。于是这个 80ms 的 resize 要么在 `destroy()` 之后打到死渲染进程，要么被冲进下一次 attach 的新页面。

**修法**：`Handler(Looper.getMainLooper()).postDelayed({ runJsNow("post-scale-resize") { … } }, 80)`——主 Looper 一定 drain，`runJsNow` 在**执行时**重查 `destroyed` 闩（正是 `analyse_js_chokepoint` 那条判据要求的形状，改后门仍 `44 passed, 0 failed`）。

**证据**：臂 A6（改回 `view.postDelayed({`）→ `the post-scale resize does not use the view queue` 红。

### F-09 [P2] URL 下载不看状态码 — 成立（做了一半）

**真码**：`startUrlDownload` 取 `conn.inputStream` 直接落盘，**从不看 `responseCode`**；`instanceFollowRedirects = true` 只跟同协议跳转，跨协议（http→https）3xx 会把 stub body 当载荷 ⇒ 面板报「下载完成」，文件是错误页。

**修法**：不 2xx → `throw IOException("HTTP $code")`，走**既有**失败路径（删半截文件 + `settleDownload(FAILED)` + chat 通知）；跨协议/不自动跟的 3xx 用 `Location` 手追（`MAX_DOWNLOAD_REDIRECT_HOPS = 5`，逐跳重挂 UA 与 Cookie，相对 Location 按当前 URL 解析）；进度分母优先用**最终跳**的 `contentLengthLong`（监听器给的 `contentLength` 描述的是原始 URL）。

**未修**（§6 R2）：报告里的「主线程预计算 `uniqueFile` → 并发同名覆盖」「写入非原子」——需 `.part` + rename + 名唯一化，属独立改动。

**证据**：臂 A4（门改成 `100..299`）→ `url downloads gate on the status…` 红。

### F-10 [P2] blob/data 下载失败全静默 — 成立（签名过期）

**报告过期的部分**：报告写 `blobDownloadError(token, error)`；真码是 `blobDownloadError(error)`（该成员没有 token 参数），所以「用 token 校验」这条路不存在。

**真缺陷**：失败分支全是 `Log.w` + `return`，池侧完全不知道 ⇒ 用户点了下载，面板无条目、聊天无提示。

**修法**：`BrowserUseManager.onBlobDownloadFailed(filename?, reason)` 漏斗 `reportBlobFailure()`，接住 7 个分支——bridge token 失配 / 页面 JS 报错 / 超限 / 不安全文件名 / 畸形 data URL / 解码失败 / 标签已销毁；池侧 `reportDownloadFailure()` 产出 `DownloadState.FAILED` 条目（`destination = null`，面板已有 `?: return` 的 null 分支）并复用与流式失败**同一条** chat 通知文案；顺带把池侧「未绑定会话」的静默 drop 也接进同一漏斗。

**未修**（§6 R3）：单槽 token 的并发语义（第二个 blob 请求会顶掉第一个）——改成集合/队列是结构改动。

**证据**：`blob download failures reach the panel and the chat`（含「无裸 `rejected:`/`dropped:` 残留」判据）。

### F-11 [P1] `fetch` 两个方向都错 — 成立

**真码**：`status` 只被 `appendLine("  Status: $status")`，**从不判断** ⇒ 404/500 的 body 当成功载荷交给调用方；`data == null` 一律 `error("Fetch returned no body (status=$status)")` ⇒ 200/204 空体被当失败。

**证伪一处**：报告说 JS 侧有 `text.substring(0, 50000)` 分支——当前树 `BrowserUseJS.fetch` **一律** `arrayBuffer` + base64，没有 text 分支（顺带说明：这也不再有 50000 字符截断）。报告的 `\r` 未转义成立，已补（`\n` 转义了、`\r` 没转义 ⇒ 含 CR 的 URL 让整个 IIFE 变 SyntaxError）。

**修法**：纯函数 `fetchHttpFailure(status, url)`——2xx 与「0 = JS 没报」放行，其余返回 `"Fetch failed: HTTP n for <url> — the response body is the server's error page, not the resource you asked for"`；`data == null && status in 200..299` → 返回 `ByteArray(0)` 的**成功**（空资源是合法结果）；断言「状态门在交出 body 之前」。

**证据**：6 例本类 + 臂 A5（删掉状态门）→ `fetch checks the status before it hands the body over` 红。

### F-14 [P2] `detectChallenge` 的 `statusCode` 从无实参 — 成立

**真码**：`detectChallenge(statusCode = null, title, bodyText, currentUrl = null)` 的参数默认全 null；**全树唯一**的生产调用点 `BrowserTabPool.kt:1129` 只传 title/currentUrl（`rg detectChallenge src/android/app/src/main/` 只有 RiskControl 的定义 + 这一处调用）。所以 `429 → rate_limited` / `403 → access_denied` 这两条「快路径」是**死代码**——真出现裸 403 错误页时，页面文本里既没有 "cloudflare" 也没有 "captcha"，检测不出来。

**修法**：`onReceivedHttpError`（**仅主帧**——子资源 404 是页面家具）记录 `lastMainFrameHttpError`；`onPageStarted` 清零（否则上一页的 429 会一直节流一个加载正常的新页）；`destroy()` 清零；`BrowserTabPool` 调用点 `statusCode = tab.manager.lastMainFrameHttpError`。

**证伪一处**：报告说 `WebAppActivity` 也有一个「同样只传两个参数」的调用点 —— 全树 grep 里**不存在**该调用（`WebAppActivity` 根本不调 `detectChallenge`），所以「所有调用点都漏传」实际是「唯一调用点漏传」。

---

## 4 装置与变异臂

装置在 `meta-audit-1029/probe-b09b/`（**不落在仓库里**）：

| 文件 | 作用 |
|---|---|
| `balance.py` | Kotlin 括号/引号配平（屏蔽注释与字符串）——沙箱没有 Android SDK，这是唯一廉价的「改动没把文件写坏」检查 |
| `jvm_run.py` | 把本支三个测试类**原样**编译进 JVM：三个纯函数（`tabHeld` / `isIdleEvictionCandidate` / `fetchHttpFailure`）从真实文件**逐字切片**（脚本回读 `piece in src` 断言逐字一致），加 junit + `java.io.File` 即可；其余断言全部是对真实源码文本的判据 |
| `arms_one.py` | 单臂执行器：把一处缺陷塞回真源码 → 跑真实测试类 → 期望红 → 按变异前字节还原（`replace(bad, good)` 在「删一行」型变异上会写坏文件，已改为快照还原） |

**25 例全绿**（10 + 6 + 9）。**7 臂全 MATCH**：

| 臂 | 塞回的缺陷 | 变红的用例 |
|---|---|---|
| A1 | 保留改回 `setTabInUse(tab, true)` | `the grace hold is armed without touching inUse` |
| A2 | busy 改读 `isHeld` | `the busy signal still derives from inUse…` |
| A3 | `tab.manager.destroy()` 直调 | `tab teardown routes the manager destroy through the main thread` |
| A4 | 下载门 `100..299` | `url downloads gate on the status and chase redirects by hand` |
| A5 | 删掉 `fetchHttpFailure` 调用 | `fetch checks the status before it hands the body over` |
| A6 | 改回 `view.postDelayed({` | `the post-scale resize does not use the view queue` |
| A7 | 删掉 `statusCode = …` | `the risk detector is fed the main-frame http status` |

---

## 5 门与本地验证

改 `BrowserTabPool` / `BrowserUseManager` 前先 `rg -l` 扫了 `src/test/**` 的引用；本支**没有**改任何既有测试的断言（`BrowserTabPoolBusyPublishTest` 的字面量判据在我的修法下**天然继续成立**，这正是「保留承载式、改语义」的收益）。

按纪律只跑**受影响**的门（禁全量 `scan.sh` / `test_scan.py` / gradle）：

| 门 / 自测 | 结果 |
|---|---|
| `browser_tabs_lifecycle_check.py` | OK（C1–C13）→ `Summary: 44 passed, 0 failed` |
| `browser_destroy_wakeup_check.py` | `GATE=GREEN` |
| `browser_tab_exec_serial_check.py` | PASS |
| `downloads_atomic_update_check.py` | PASS（6 处 `_downloads.update{}`，0 非原子） |
| `claim_release_guard.py` | PASS |
| `browser_cookie_ui_refresh_check.py` | OK |
| `skill_restore_session_cleanup_check.py` | PASS |
| `browser_history_snapshot_check.py` | OK |
| `browser_tabs_lifecycle_check.py --selftest` | `BROWSER_TABS_SELFTEST=PASS` + `…RISK_NOTICE_AND_REMAP_SELFTEST=PASS` |
| `browser_destroy_wakeup_check.py --selftest` | `SELFTEST=PASS (68 arms)` |

---

## 6 残留（三件套：症状 / 不能在本支做的原因 / 复活条件）

- **R1（F-05）`releaseAllTabs()` 与 `clearChat` 的期望不一致**：清聊天后标签仍在内存里，且后续任意 `saveState()` 会把 `browser_tabs/<sid>.json` 写回磁盘。改点 = `ui/chat/ChatViewModel.clearChat`（本支禁碰）；最小方案 = 池新增 `clearAllTabs()`（销毁 + 抑制一次持久化），`clearChat` 调它后再删文件。**复活条件**：有支能碰 `ui/chat`。
- **R2（F-09）下载写入非原子 + 主线程预计算名**：`startUrlDownload` 直接写最终文件、`uniqueFile` 在主线程算出，同刻两下载同名会互相覆盖；崩溃/被杀留半截文件。正解 = `.part` + `rename` + 落盘后再唯一化。**复活条件**：与备份/快照那套 `writeText-non-atomic` 一起做（同族，属独立批次）。
- **R3（F-10）单槽 `blobDownloadActiveToken`**：并发两个 blob 请求时前一个被判「被替换」而失败（现在**有可见失败**了，不再静默）。正解 = token 集合/队列 + 每个 token 带自己的 filename。**复活条件**：真机日志出现该失败原因 ≥2 次。
- **R4（F-14）已删除**：报告的 `WebAppActivity` 调用点不存在（见 §3 F-14 的证伪），本支已把**唯一**调用点接通，无残留。
- **R5（F-02）渲染进程被杀后的标签永久废**：既有 ponytail 天花板（`onRendererGone` 的 KDoc 明写「不在这里销毁」）。本支只把「悬到工具超时」变成「快速、可辨识的失败」，**没有**改变「这一页要换新标签」的事实。**复活条件**：真机日志出现 `renderer gone` 后紧接 `navigate` 失败。
- **R6 真机未验**：全部证据 = 门（源码形状）+ JVM（纯函数与文本判据）+ CI（编译级）。主线程收敛、渲染死闩、下载重定向、状态码采集都需要真机/真站复现才算端到端；本支按纪律停在分支 CI 绿。

---

## 7 CI

- 分支 `fix/r3-b09-browser` push **不触发**（`build-apk.yml` 只认 main），手动 dispatch `build-apk.yml --ref fix/r3-b09-browser`
- **首轮 run `40740675546`（#2855）failure**：`:app:compileReleaseKotlin` 唯一真实 `e:` 行 = `BrowserUseManager.kt:1311:36 Unresolved reference 'webViewGoneResult'`（`navigate()` 的死标签短路调了它、定义漏写）。补定义 + 在 `BrowserTeardownAndFailureSignalTest` 里钉住「定义存在 / 两种原因可辨识」（提交 `ed859b0e`），并校准 `Tab.graceHeld` 的 KDoc 表述（构造参数参与生成式 equality，非 excluded）。
- **终局 run `40743896359`（#2856）success**，`head_sha = ed859b0e322ab3bd535f1f71aa624a5227b47434` 与本地 `git rev-parse HEAD` **逐字符一致**；job `123012466130`：18:18:40Z→18:33:10Z（14 min 30 s），**27 步 0 非 success，唯一 skipped = 滚动 tag + Publish（main 门控）**。
- 日志判读（1,574,854 B / NUL 0）：`^ e: ` **0**；含 `FAILED` 的 6 行**全是 PASSED 用例名子串**（RealAgentAdapterSkeletonTest / ChatAgentTraceObserverSchemaTest ×4 / TerminalErrorSurfacedTest）；`Summary: N passed, 0 failed` 7 行（25/16/44/19/30/18 + 门集 113）；`ALL 349 CASES PASS` ×1。
- 本支三个新测试类在 CI 上逐例 PASSED：`BrowserTabHeldSplitTest` 10/10、`BrowserFetchStatusPolicyTest` 6/6、`BrowserTeardownAndFailureSignalTest` 9/9（0 FAILED）。
- 相关门在 CI 真树上全绿：`browser_tabs_lifecycle_check` / `browser_destroy_wakeup_check` / `browser_tab_exec_serial_check` / `downloads_atomic_update_check` / `browser_cookie_ui_refresh_check` / `browser_history_snapshot_check`。

---

## 8 处置

- 收口 = **分支 CI 绿**（`head_sha` 与本地 HEAD 逐字符一致）即停，**不合 main**，合并起手权在用户。
- 报告提交后 push，`git diff --name-only <绿 sha>..HEAD -- . ':(exclude)fix-reports'` 必须为空。
- 本批未改任何 `scripts/scan/**`、未动门判据、未改既有测试断言。
