# FIX-FW-CHAT-CLEAR-TABS — 「清空聊天」后浏览状态复活

分支 `fix/fw-chat-clear-tabs` ｜ 基线 main `039c9ab2` ｜ 来源 backlog §META-1029R3 **R1 B09 F-05（P1）**
改动面：生产 2 文件（+84 / −12）、新增测试 1 文件 ｜ `scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰

---

## §1 取证（先核，不采信任务书行号）

### 1.1 `releaseAllTabs()` 的真实函数体（基线 `039c9ab2`，`browser/BrowserTabPool.kt:1717-1745`）

```kotlin
fun releaseAllTabs() {
    _tabs.value = _tabs.value.map { tab ->
        setTabInUse(tab, false)      // 只清「执行中」位
        tab.inUseGraceJob?.cancel()  // 只撤「复用保留」窗
        tab.inUseGraceJob = null
        tab.graceHeld = false
        tab                      // ← 关键：返回同一个 Tab 实例，列表内容不变
    }
    publishAgentBusy()
    saveState()                  // ← 把「仍存活」的标签列表写盘
}
```

- 它**不销毁**任何 tab：`destroyTab(` 在 `releaseAllTabs()` 体内 0 处（本支的判据 `releaseAllTabsKeepsItsTakeoverSemantics` 钉住这一点，含变异臂 M8）。
- 注释（`[fix/audit-b21 / T7-L3]`）明确写了：必须保留 live `Tab` 实例，否则飞行中的 action 释放路径会改到旧对象上。所以「保留标签」是**刻意设计**，不是疏漏——错的是把它用于「删除」。
- 体内 `saveState()` 在 `ChatViewModel.clearChat` 的文件删除**之前**执行，因此它自己不会复活文件；真正的复活来自**其后的每一次 save**（见 1.4）。

### 1.2 `clearChat()` 的先后（基线 `ui/chat/ChatViewModel.kt:2906`）

```
2968:  _browserTabPoolRef?.releaseAllTabs()          // 释放占用 + saveState()（写盘）
2969-2971: File(context.filesDir, "browser_tabs/$sid.json").delete()   // 删掉刚写的那份
```
删除**是**在 release 之后，顺序没错；问题在于删除之后**没有任何东西阻止下一次 save 重建文件**。

### 1.3 池的会话归属 —— 确认「同一个 sid 文件」而不是「别的会话里复活」

- 装配点：`browserTabPool`（`ChatViewModel.kt:1597`，`by lazy`）内 `BrowserTabPool(context).also { it.setSession(activeSessionId); …; _browserTabPoolRef = it }` → 池的 `sessionId` 与 `clearChat` 用的 `sid = activeSessionId` **同源**。
- 重指向：`ChatSessionEntryExt.kt:362` `_browserTabPoolRef?.setSession(session.id)`（draft → 正式会话晋升时）；`setSession` 内部 `loadSavedState()` 读的正是 `browser_tabs/<同一 sid>.json`。
- `saveState()`（基线 `:2159`）的落点：`File(context.filesDir, "browser_tabs")` + `"$sid.json"`，`sid = sessionId ?: return`。
⇒ 结论：`clearChat` 删的就是这个池**自己**将要重写的那个文件，不是别人的。

### 1.4 哪条真实用户动作会在 `clearChat` 之后触发 `saveState()`

`saveState()` 在池内有 **16 个真实调用点**（去注释后逐条数，任务书写「8+」偏保守），分属 16 个函数：
`runAcquiredAction / newTab / closeTab / handleNewWindow / handleCloseWindow / newTabFromUI / closeTabFromUI / selectOrCreateTabForURL / ensureTabForUI / releaseAllTabs / onTrimMemory / evictIdleTabs / setSessionViewport / resetSessionViewport / handleSetUserAgent / restoreSavedTabs`。

可执行路径（按「用户清空后最可能碰到的」排序）：

1. **浏览面板的「+」**（最短）：`BrowserSheet.kt:175` `IconButton(onClick = { scope.launch { tabPool.newTabFromUI() } })` → `newTabFromUI()`（基线 `:1572`）→ `createTab` → `_selectedTabId.value = tab.id` → **`saveState()`**。此刻 `_tabs` 仍是被「清空」的那几个 tab，`mergedTabUrls` 把它们全写进 `<sid>.json` ⇒ 下次打开会话原样恢复。
2. **任何 `browser_use` 工具调用**：`runAcquiredAction` 收尾 `updateTabs(); saveState()`（基线 `:1259`），并会**复用**这些本该消失的 tab（`acquireTab` 挑同任务 tab）。
3. **空闲驱逐**（无用户操作也会发生）：`evictionJob` 每 60s 一次（`IDLE_CHECK_INTERVAL_MS`），默认闲置 15 min（`DEFAULT_IDLE_TIMEOUT_MINUTES`）→ `evictIdleTabs()` 销毁全部 tab，`rememberEvictedUrl` 把 URL 记进 `savedURLs`，**`saveState()`**（基线 `:1932`）把 `savedURLs` 折进 JSON ⇒ 文件又回来。
4. **内存压力**：`onTrimMemory` 同一形状（基线 `:1932` 落盘）。
5. 修好之后（`_tabs` 为空）打开面板会走 `ChatViewModelUiStateExt.toggleBrowserSheet → ensureTabForUI()`（`:1327` 落盘）——此时写的是**空文档**，属正确行为，不会再恢复旧标签。

### 1.5 任务书未提、但同样必须清的第二半：`savedURLs`

`saveState()` 写盘用的是 `mergedTabUrls(_tabs.value.map { id to currentURL }, savedURLs)`（纯函数，`BrowserTabPool.kt` 文件末）。
`savedURLs` 是「本次会话里被 trim/evict 掉的标签 URL 备忘」，**与 `_tabs` 相互独立**。
⇒ 即使把 `_tabs` 清空，只要不清 `savedURLs`，下一次 save 仍会把被驱逐的 URL 写回文件，`restoreSavedTabs()` 在下次打开时把它们**重建为真 tab**。这条是本支的额外发现，任务书只点了「标签对象仍在 `_tabs` 里」。

### 1.6 与任务书描述的偏差（逐条）

| 任务书写法 | 源码事实 |
|---|---|
| 「8+ 个 saveState 调用点」 | **16 个**（每函数 1 个，含 `restoreSavedTabs`）|
| 暗示 release 的 `saveState()` 是复活原因 | 它发生在删除**之前**，被删除覆盖；复活来自**其后**的 save（1.4 的四条路径）|
| 只说「标签对象仍在 `_tabs` 里」 | 还有第二半 `savedURLs`（1.5），不清它照样复活 |
| 建议「`saveState()` 在 `_tabs` 为空时写空文档」 | **不能采用**，见 §2.2 |

其余描述（`releaseAllTabs` 只释放占用、清空后被后续 save 重写、takeover 依赖它保留标签）**全部与源码一致**。

---

## §2 修法

### 2.1 新增 `BrowserTabPool.discardAllTabs()`（`BrowserTabPool.kt:1772`）

```kotlin
fun discardAllTabs() {
    val live = _tabs.value
    suppressingTabPersistence {              // ← §2.2 的抑制窗
        if (live.isNotEmpty()) {
            val mutable = live.toMutableList()
            for (tab in live) destroyTab(mutable, tab)   // 复用唯一 teardown
        }
        _tabs.value = emptyList()            // 先落列表
        publishAgentBusy()                   // 再重推 busy（C10 要求 publish 在 list write 之后）
        _selectedTabId.value = 0
        savedURLs.clear()                    // 第二半（§1.5）
    }
    Log.i(TAG, "discardAllTabs(): destroyed ${live.size} tab(s)")
}
```

- **复用而非另写 teardown**：`destroyTab` 内部 `destroyManagerOnMain` 负责「Main 线程才 destroy WebView」（其 KDoc 记录了非 Main 调用会在 `catch` 里先锁死 `destroyed=true`、渲染进程泄漏 50–100 MB/tab），同时取消 grace job、清 `savedURLs[id]`/`tabLocks[id]`。另写一套必然与 trim/evict/dispose 漂移。
- **线程**：`destroyManagerOnMain` 是单点线程适配（Main 直接跑、非 Main 则 `Handler(mainLooper).post`），所以 `discardAllTabs()` 可在任意线程调用，列表记账与 `destroyTab` 本身线程无关（同 `dispose()` 的形状）。**未新增任何跨线程 WebView 调用**。
- **不在这里删文件**：文件归调用方（`clearChat`）删，与既有分工一致；清空后的内存状态保证「之后的 save 只能写空文档」。

### 2.2 抑制方案：**丢弃窗（try/finally latch）**，而不是「消费一次的标志」或「空列表写空文档」

三个候选，逐个论证：

**① 任务书选项 B「`saveState()` 在 `_tabs` 为空时写空文档」→ 否决。**
`saveState()` 的核心职责之一就是「把 `savedURLs` 持久化」——trim/evict 把**全部** tab 驱逐后 `_tabs` 就是空的，而按设计这些 URL 必须留下来供后续隐式 acquire 恢复（`onTrimMemory`/`evictIdleTabs` 的 KDoc 与 `dv14-B2` 的 `mergedTabUrls` 都是为这条存在的）。改判据会**全局砍掉**该特性，属于用一处修复换一个跨场景回归。

**② 任务书选项 A「`suppressNextSave` 标志，`saveState()` 入口消费一次」→ 也不采用。**
消费点不可控：若丢弃过程中恰好**没有**任何 `saveState()` 落地（今天的路径下确实可能有，例如 `_tabs` 本来就空、`destroyTab` 没有触发回调），标志就会留到**下一次** save 把用户的真实新建标签页吞掉——故障点在时间上远离原因、现象是「标签页没保存」，极难归因。这正是「消费一次的标志」的经典坑。

**③ 采用：`suppressingTabPersistence { … }` 窗口（`BrowserTabPool.kt:1810-1823`）**
```kotlin
@Volatile private var discardInProgress = false

private fun suppressingTabPersistence(block: () -> Unit) {
    discardInProgress = true
    try { block() } finally { discardInProgress = false }   // ← 结构性清位，不靠「下一次」清
}
```
- 作用域是**一次同步调用**：进入即真、`finally` 立即假，**不可能留下悬空标志**，因此不会吞掉任何后续 save。
- 覆盖的是「teardown 过程中的重入保存」：`destroyManagerOnMain → manager.destroy()` 会驱动 close-window 回调（`handleCloseWindow` 带 `saveState()`）与 in-flight action 的 release 路径（`runAcquiredAction → saveState()`），那一瞬间 `_tabs` 处于半清空状态，写盘会得到「一部分旧标签」的畸形快照。
- `@Volatile`：`discardAllTabs()` 可能在非 Main 线程被调用，而 `saveState()` 可以从 IO 线程进来读它。
- `saveState()` 的守卫（`:2249`）放在 `isDisposed` 之后、**任何磁盘访问之前**（判据 `theWipeRunsInsideAPersistenceSuppressionWindow` 钉住「守卫位置 < `browser_tabs` 出现位置」）。`isDisposed` 的显式守卫原样保留——`browser_tabs_lifecycle_check` C4f 就是钉它的，不动契约。

### 2.3 `clearChat` 换入口（`ChatViewModel.kt:2974`）

```kotlin
_browserTabPoolRef?.discardAllTabs()          // 原：releaseAllTabs()
runCatching { java.io.File(context.filesDir, "browser_tabs/$sid.json").delete() }
```
删除保持在后（若删除在前，丢弃过程里的任何 save 都能在删除之后重建文件）。

### 2.4 `releaseAllTabs()` 一个字未改

`git diff` 对 `releaseAllTabs` 体零改动；`BrowserSheet.kt:347` 的 takeover（`onTakeover = { tabPool.releaseAllTabs() }`）语义不变，且判据 `releaseAllTabsKeepsItsTakeoverSemantics` 反向钉住「不许给它加 `destroyTab` / 不许清空列表」。

---

## §3 证据

### 3.1 JVM 级（真跑的纯逻辑）

`BrowserTabPoolDiscardTest.a discarded pool leaves no tab url for the next save`
—— 直接跑生产纯函数 `mergedTabUrls`（`saveState()` 用它构造 `tabURLs`），**期望值全是写死的字面量**：

| 输入 | 期望（字面量） | 证明什么 |
|---|---|---|
| `([], [])` | `{}` | 两半都清 ⇒ 文件里没有任何 tabURLs |
| `([], {7: "https://evicted.example/"})` | `{7: "https://evicted.example/"}` | **负控**：只清 `_tabs` 不够，`savedURLs` 会被照写 ⇒ 这是 `savedURLs.clear()` 的必要性证据 |
| `([2 to ""], [])` | `{}` | about:blank 的 tab 不进文件 |
| `([1 to "https://live.example/"], [])` | `{1: "https://live.example/"}` | 正常存活的 tab 仍照常持久化（修复没有伤到常规路径）|

### 3.2 形状级（读源码，向上找仓库根）

新增 `src/android/app/src/test/java/com/rikkaminis/app/browser/BrowserTabPoolDiscardTest.kt`，5 个判据测试 + 1 个变异臂测试，共 **6 个测试方法**：

1. `clearChat discards instead of releasing` — `clearChat` 体内出现 `_browserTabPoolRef?.discardAllTabs()`、**不再出现** `.releaseAllTabs()`、且 `browser_tabs` 删除位置在 discard 之后。
2. `discardAllTabs destroys the pool through the single teardown funnel` — 体含 `destroyTab(`、`_tabs.value = emptyList()`、`publishAgentBusy()` 且**在 list write 之后**、`savedURLs.clear()`、`_selectedTabId.value = 0`，且**不含** `.destroy()`（不允许自写 teardown）。
3. `the wipe runs inside a persistence suppression window` — 窗口包住 teardown（`suppressingTabPersistence {` 位置 < `destroyTab(`）、latch `set` 在 `block()` 之前、`clear` 在 `block()` 之后、`finally` 存在、`@Volatile` 存在、`saveState()` 的守卫位置 < `browser_tabs`。
4. `releaseAllTabs keeps its takeover semantics` — release 体**不含** `destroyTab(`、**不含** `_tabs.value = emptyList()`，仍含 `publishAgentBusy()` 与 `saveState()`。
5. `a discarded pool leaves no tab url for the next save`（§3.1 的真逻辑表）。
6. `mutationsAreCaught`（§3.3）。

判据读源码时**先掩掉注释**（`codeOf()`）：本文件的 KDoc 会点名 `releaseAllTabs()`/`saveState()`/`destroy()`，不掩注释会既误绿（注释满足 `contains`）也误红（注释触犯 `!contains`）——首轮就真的被自己的注释打红了一次（`clearChat releases again`），据此补的掩码。

### 3.3 变异臂

**（a）测试内 8 条臂**（每条都「把缺陷塞回去」，要求对应判据变红）：M1 删除 `_tabs.value = emptyList()`、M2 删除 `savedURLs.clear()`、M3 把 publish 挪到 list write 之前、M4 latch 不 set、M5 latch 不清（去 `finally`）、M6 `saveState` 去掉 `if (discardInProgress) return`、M7 `clearChat` 退回 `releaseAllTabs()`、M8 给 `releaseAllTabs` 加 `destroyTab`。结果：**8/8 被抓住**（`caught == arms.size`）。

**（b）仓库外「修复整体撤掉」臂（`probe-a/revert-tree/`）**：把 `discardAllTabs()` 体退化成 `{ releaseAllTabs() }` + `clearChat` 退回 `releaseAllTabs()`，源码树同路径复制、`-Duser.dir` 指向该树，跑同一个测试类：

```
Tests run: 6,  Failures: 4
1) clearChat discards instead of releasing        → clearChat no longer discards …
2) mutationsAreCaught                             → mutation target missing in fun discardAllTabs(
3) discardAllTabs destroys … teardown funnel     → no longer destroys the tabs …
4) the wipe runs inside a suppression window      → no longer runs inside the … window
```
（余下 2 条在退化树上本就该绿：`releaseAllTabs` 语义判据与 `mergedTabUrls` 纯逻辑表——它们钉的是没被撤掉的东西。）**RED-AS-EXPECTED**。

### 3.4 门与本地检查

- `scripts/scan/browser_tabs_lifecycle_check.py .` —— 改动前 `OK`，改动后仍 **`OK`**（C1–C13 全绿；该门自动派生 `destroyTab(` 调用者并检查「list write 后必须重推 busy」，本支新函数在门内被覆盖并通过）。
- 另跑涉 `ChatViewModel` 的 10 道门：`provider_boundary_guard / models_provider_field_check / thinking_wiring_guard / sysinfo_state_thread_check / open_url_broker_scheme_check / skill_restore_session_cleanup_check / readonly_mount_write_guard / session_provider_enabled_check / memory_gate_provider_fields_check / chat_ui_interaction_check` —— 全绿。
- 括号/重名检查（掩字符串与注释后配平）：`BrowserTabPool.kt` OK、`ChatViewModel.kt` 仅报 2 对既有 `override`（`LoopHostAdapter` 覆写 `effectiveContextWindowTokens` / `buildTurnParts`，基线即存在）、测试文件 OK（首版报的 `stray ')'` 是检查器不认字符串模板里嵌套字面量所致，`kotlinc` 实编译通过即证伪）。
- 未跑全量 `scan.sh`（按任务书，主会话跑）；未改 `scripts/scan/**`、`scan.sh`、`test_scan.py`。

### 3.5 真实 JVM 执行记录

```
JUnit version 4.13.2
......
Time: 0.218
OK (6 tests)
```

---

## §4 CI 读数

**run `41003089942`**（workflow `build-apk.yml`，`workflow_dispatch`，branch `fix/fw-chat-clear-tabs`，attempt 1）
**conclusion `success`**，`2026-10-30T04:28:32Z → 04:46:18Z`（≈ **17.8 min**）

| 判据 | 读数 | 结论 |
|---|---|---|
| `head_sha` vs 本地 HEAD | `af2956de2aa7e1e6003863a355c39df9c81ffb20` vs `af2956de2aa7e1e6003863a355c39df9c81ffb20` | **逐字符一致**（不是「CI 绿的是别人分支」）|
| steps | 27 步 = **25 success + 2 skipped**（#23 `Move the rolling tag android-latest`、#24 `Publish to Releases`，均为 main 门控）| 唯一非 success 都是分支上**应该**跳过的 |
| 门（step 3 `Run pre-build scan gate`）| 分片 `Summary` 全 `0 failed`，含 **`Summary: 113 passed, 0 failed`**；**`ALL 349 CASES PASS` ×1**；`\bRED\b` 命中 **0**；` FAIL` 行 **0** | 门与夹具基线 113/349 未变 |
| 单测（step 15）| `BUILD SUCCESSFUL` 1 处；`PASSED` 行 **8,551**；`FAILURE:` **0**；真 `FAILED$` 行 **0** | 全量单测通过 |
| 编译错 | 整份 job 日志 `e: file` **0** | 无编译级问题 |
| 日志 | job 日志 1,681,133 B / **NUL 0** | 无截断坏字 |
| 本支新测试 | `BrowserTabPoolDiscardTest` 6/6 `PASSED`（逐条：`a discarded pool leaves no tab url for the next save` / `clearChat discards instead of releasing` / `discardAllTabs destroys the pool through the single teardown funnel` / `mutationsAreCaught` / `releaseAllTabs keeps its takeover semantics` / `the wipe runs inside a persistence suppression window`）| 新判据在 CI 上真跑且全绿 |

> 收口口径：本支在绿后追加了一个**只含本报告**的提交，判据是
> `git diff --name-only af2956de..HEAD` 只含 `fix-reports/FIX-FW-CHAT-CLEAR-TABS.md`（不是 sha 相等）。

---

## §5 残留（每条带触发条件）

| # | 残留 | 触发条件（什么时候必须回来处理） |
|---|---|---|
| R1 | **真机未验**：全部证据 = CI 编译级 + JVM 级 + 源码形状；未在真机点一次「清空聊天 → 开面板 → + → 重进会话」 | 任何一次真机走查该路径；或用户再报「清空后标签又回来了」 |
| R2 | **丢弃不取消飞行中的下载**：`downloadScope`/`downloadJobs` 不动（`clearChat` 明确保留 workspace 文件，下载产物属于文件）| 若产品决定「清空聊天也要中止下载」，改 `discardAllTabs` 内 cancel `downloadJobs`（要与 `dispose()` 的取消逻辑对齐） |
| R3 | **跨会话共享池不在本支范围**：`MinisApp.sharedBrowserTabPool` 走 `bindSessionContextOnly`（`isContextOnlySession` ⇒ `saveState` 本来就不写），`clearChat` 只清 per-VM 池 | 若将来 `clearChat` 需要作用到共享池（例如 `minis-browser-use` 为同一会话留了 tab），要另加「按 sid 丢弃」入口，且不能沿用 `discardAllTabs` 的「整池」语义 |
| R4 | **视图覆盖（viewport override）仍可能被后续 save 写回**：丢弃后若发生一次 save，文档只有 `sessionViewportWidth/Height`（`clearChat` 随后删文件，通常看不到；只有「删文件之后又 save」才留下） | 用户报「清空后浏览器还是按我上次的自定义视图打开」；届时在 `discardAllTabs` 里连 `_sessionViewportWidth/Height` 一起清（需同时改 `viewportForLoadedSession` 的既有契约） |
| R5 | **`weak callback` 期间 `saveState` 被抑制的窗口内若有「本该落盘」的写入**：窗口只覆盖 `discardAllTabs` 这一次同步调用，理论上该窗口内不存在「应保留的 tab 状态」（整个池都要丢） | 若将来 `discardAllTabs` 被扩用成「丢弃部分 tab」，抑制窗必须改成按 id 判定，否则会连同应保留的 tab 一起丢 |
| R6 | 判据 `codeOf()` 会截断含 `//` 的字符串字面量 | 若将来在 `discardAllTabs`/`clearChat` 体内出现含 `//` 的字符串（如裸 URL 字面量），相关判据可能误判，需改用真正的词法掩码 |
