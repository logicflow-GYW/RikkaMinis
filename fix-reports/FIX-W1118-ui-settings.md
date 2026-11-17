# FIX-W1118-ui-settings · T7 修复报告（WAVE-1118 D4 + D10 除 F-05）

- **分支**：`fix/w1118-ui-settings`（worktree `/var/minis/shared/work/incr1118/wt7`）
- **基线**：`c85131ad`（= 当前 main，已含 FIX-1117B 五支）
- **代码提交**：`6dc2b947`（7 文件 / +676 −22，含 3 个新测试文件 + 1 个新生产文件）
- **allowlist 实际触碰**：`ui/settings/**`（生产 3 改 1 增）+ `src/test/.../ui/settings/**`（3 增）
- **未触碰**：`scripts/scan/**`、`ui/chat/**`、`ui/markdown/**`、`service/**`、`share/**`、`sandbox/**`、`config/**`、`ui/sandbox/**`、`res/**`

---

## 0. 一句话结论

12 条里 **修 4 条**（D4 F-01 / F-02 / F-05 / F-07），**跨域移交 4 条**（D4 F-03 / F-04，D10 F-01 / F-02），**登记不修 4 条**（D4 F-06，D10 F-03 / F-04 / F-06）。

**必须请主会话裁决的一件事**：任务书「本轮重点」第 3、4 条（D10 F-01 rootfs 并发修复、D10 F-02 PdfPageRenderer 泄漏）**落在 `ui/sandbox/`**，而本支硬约束把 `ui/sandbox/**` 排除在 allowlist 之外。两条我判 **P1**，且都带现成补丁草图（见 §4），但本支一行未动 —— 需要另开一支或放宽 allowlist。

---

## 1. 判级表（本支全部条目）

| ID | 报告给的级别 | 我的判级 | 结论 | 去向 |
|---|---|---|---|---|
| **D4 F-01** 生图端点分段按钮未清 `imageEndpointResolved` | High | **P1** | **修** | 本支 `6dc2b947` |
| **D4 F-02** `oauthBusy` 缺 `finally` 复位 | High / P2 | **P1**（机制重判，见 §3） | **修** | 本支 |
| D4 F-03 `providers` 摘要输出 camelCase token | Medium / P1 | P1 | 未修（跨域） | §4 指针（`config/ConfigBuiltins.kt`） |
| D4 F-04 `fontScaleField` 未 `PrefsWriteBus.publish` | Medium / P1 | **P2** | 未修（跨域） | §4 指针 |
| **D4 F-05** `exportLauncher.launch` 裸调 | Medium / P2 | **P2**（机制重判） | **顺路修**（同屏同函数区） | 本支 |
| D4 F-06 Skills/Storage 六处 busy flag 无 `finally` | Medium / P2 | **P2**（机制重判：异常＝崩溃，非卡死） | 未修（登记） | §3 三件套 |
| **D4 F-07** `.minisbak` 忙碌静默吞提示 | Low / P1 | **P2**（触发面窄） | **顺路修**（同函数） | 本支 |
| D10 F-01 `repairRootfs` 异步窗口未置 `isProcessing` + 无并发守卫 | High / P2 | **P1** | 未修（**跨域：`ui/sandbox/`**） | §4 指针（重点项，见 §0） |
| D10 F-02 `PdfPageRenderer.open` 覆盖关闭标志 / `render` 异常不释放 | High / P2 | **P1** | 未修（**跨域：`ui/sandbox/`**） | §4 指针（重点项） |
| D10 F-03 `WebViewHolder.destroy` 在 `parent == null` 时跳过 `unbindPendingListeners` | Medium / P2 | **P3**（重判） | 未修（登记） | §3 |
| D10 F-04 `QuickTestSheet` 的 `isPlaying` 无 `data` 键 | Low / P3 | **P3** | 未修（登记） | §3 |
| D10 F-05 `MarkdownText.InlineContent` remember 键缺 `style` | Low / P1 | P3 | 未修（跨域：`ui/markdown/` 禁碰） | §4 指针 |
| D10 F-06 `TerminalScreen.collectAsStateEffect` 无 `this` 键 | Low / P3 | **P3**（首帧后自愈） | 未修（登记） | §3 |

判级口径：按 POLICY「谁、在什么操作下会遇到」重判，不照抄报告 severity。

---

## 2. 修的逐条证据

装置：`/var/minis/shared/work/incr1118/t7/jvm/run.sh`
（`pre` 臂把 BASE `c85131ad` 的三个文件替换进 scratch 树，`post` 臂跑工作树；行为臂用 `extract`-free 方式——生产函数原文由 `sed` 从生产文件取，载体用 harness 副本，**不手抄逻辑**）。

### 2.1 D4 F-01 · 生图端点 picker 清缓存（P1）

**这改变什么用户行为？** 用户在 provider 详情把生图端点从「Auto」强制改成「Images API / Chat Completions」再切回「Auto」后，**不再**沿用旧的探测缓存：切回 Auto 会重新走探测（而不是按旧缓存直接路由），footer 也不会再把旧结果显示成"已解析"。

**先证（真码）**：`ProviderConnectionScreen.kt:357/368/379` 三个分支都只 `copy(imageEndpointMode = …)`；而 `ProvidersCollection.kt:381-386` 的注释原文写着「forcing a non-auto mode clears the cached probe result — the same decision the update face **and the picker UI** make」，`ProvidersCollection` 在 `411-415` 真的这么做，`ProviderRepository.updateInstance:1128-1134` 也会在 base URL 变化时清 —— **三方约定，picker 这一方没做**。消费者：`ModelUseProtocol.kt:506-509` 的 `auto && imageEndpointResolved != null -> imageEndpointResolved`（不重探）。

**修法**：新增 `ui/settings/ProviderImageEndpointSelection.kt` —— `withImageEndpointMode(instance, mode)` + 纯判据 `resolvedAfterForcingImageEndpointMode(requested, cached)`（`auto` 保留缓存，强制模式清空）。三个分支统一改走它，picker 不再有私有写面。

**测试**：`ProviderImageEndpointSelectionTest`（5 条）
- `forcingImagesGenerationsClearsACachedChatCompletions` / `forcingChatCompletionsClearsACachedImagesGenerations`（正向）
- `switchingBackToAutoKeepsTheCache`（**过度修复的负控**：清 auto 也要翻红）
- `forcingAModeWithNoCacheLeavesItNull`、`theInstanceIsCopiedNotMutated`（载体纪律）
- `everyPickerBranchGoesThroughTheSharedHelper`（形状臂：文件里 `copy(imageEndpointMode` 出现 **0** 次、`withImageEndpointMode(currentInstance()` 出现 **3** 次）

**修复前后**：
- 行为臂 `pre`（picker 只写 mode 的旧语义）：`Tests run: 4, Failures: 2` —— 恰好两条强制模式臂翻红，`switchingBackToAutoKeepsTheCache` 不红。
- 行为臂 `post`：`OK (4 tests)`。
- 变异 **M2**（把 `resolvedAfterForcingImageEndpointMode` 改成恒 `null` ＝过度清理）：`Tests run: 4, Failures: 1`，唯一红的是 `switchingBackToAutoKeepsTheCache` —— 单臂精确。

### 2.2 D4 F-02 · `oauthBusy` 生命周界（P1，机制重判）

**先证（真码）**：`MCPIntegrationsScreen.kt:776-794`：`oauthBusy = true` → `oauthScope.launch { withContext(IO){ MCPOAuthStore.setClientSecret(...) }; MCPOAuthController(context).authorize(...); oauthBusy = false }`。守门在 `734` 行（`enabled = !oauthBusy && oauthSecretReady`）。

**机制重判（重要，报告说错了一半）**：报告写「异常后按钮永久锁死」。真码里 `rememberCoroutineScope().launch` 的未捕获异常会冒到线程未捕获处理器——**进程崩溃**，不是留下一个卡死的标志（本仓无全局 `CoroutineExceptionHandler`：全树 grep 只在注释里出现过一次）。所以真实后果是 **crash**（Keystore 不可用、设备无浏览器导致 Custom Tab `launchUrl` 抛异常），而不是「按钮禁用」。「卡死」只在**协程挂住不返回**时成立，而那一条 `MCPOAuthController` 已经用 `withTimeoutOrNull(5min)`（`MCPOAuthController.kt:92`）堵过了，其注释里也记着当年正是这个形状。

**所以修法比草图多一步**：不只是 `finally`，而是「catch → 记日志 + 用 throwable 自带的 message 回填 `errorText`（和 `Result.Failed` 分支同一口径）」，`CancellationException` 原样重抛（关掉表单不算授权失败），`finally` 独占唯一的复位点。

**测试**：`McpOAuthBusyLifecycleTest`（6 条，形状级，同 `RestorePayloadBusyGateTest` 的既有先例）
- `theClaimIsSynchronous`（`oauthBusy = true` 在 `oauthScope.launch {` 之前）
- `theResetLivesOnlyInTheFinally`（全文件 `oauthBusy = false` **恰好 1 次**，且落在 `finally` 块体内）
- `theAuthorizeCallSitsInsideTheTry`（`setClientSecret` 与 `.authorize(` 都在受护块内）
- `cancellationIsRethrownNotSwallowed`、`aThrownFailureIsLoggedAndSurfaced`、`theResultHandlingRunsOnlyWhenThereIsAResult`

**修复前后 / 变异**：
- `pre` 臂：`Tests run: 6, Failures: 5`（唯一不红的是 `theClaimIsSynchronous` —— 旧代码本来就在 launch 前同步 claim，这条臂是"结构未变"的锚）。
- `post` 臂：`OK (6 tests)`。
- 变异 **M3**（把 `finally` 拆回「try 之后一行 `oauthBusy = false`」＝pre 形状）：`Tests run: 6, Failures: 1`，只红 `theResetLivesOnlyInTheFinally`。

### 2.3 D4 F-07 · `.minisbak` 忙碌拒绝要出声（P2，顺路修）

**先证**：同一个 picker 回调里，JSON 分支走 `claimRestorePayload()`（`521-539`，会 `errorMessage = backup_err_busy`），`.minisbak` 分支 `convertAndRestore` 在 `552` 行 `if (operationBusy || minisbakConverting) return@convertAndRestore` —— 静默。

**修法**：改用同一个共享判据 `canStartRestorePayload(operationBusy, minisbakConverting)`（`SettingsWriteGates.kt:104`）并说同一句话（`R.string.backup_err_busy`），两个分支从此不可能再漂。

**测试**：`BackupLaunchAndMinisbakGuardTest` 的 2 条臂（`theMinisbakEntryUsesTheSharedPredicate`、`theSilentRefusalShapeIsGone` 把旧字面量钉成"必须不存在"）。

**前后 / 变异**：`pre` 臂 4/4 红；`post` 绿；变异 **M4**（只把 minisbak 守卫退回旧形状）：`Tests run: 4, Failures: 2` —— 恰好这两条红，F-05 的两条臂保持绿（**两个发现的可分性**得到验证）。

### 2.4 D4 F-05 · SAF 启动失败释放忙碌锁（P2，顺路修）

**先证**：`runExport` 在 `1341` 行同步 claim，`1455` 行裸调 `exportLauncher.launch(...)`；`launch` 抛异常时结果回调永不触发，claim 无人释放。

**机制重判**：这里 `launch` 在 `onClick` 主线程同步调用，抛异常同样会**崩溃**而不是留锁 —— 但"用户点了导出、应用直接没了"和"报一句话"是两种体验，成本 8 行，且与 F-07 同一次编辑，按「顺路成本≈0」捎带。

**修法**：`try/catch` → `errorMessage = String.format(errWriteFmt, t.message ?: errUnknown)`，并**通过共享释放点 `releaseRestorePayload()` 降锁**——这是刻意选择：`BackupFetchBusyGateTest.onlyTheFetchPathGainedAGateSite` 全文件钉死了 `operationBusy = true` **8** 次 / `operationBusy = false` **11** 次，手写一个新的 release 点会让「有人手动降别人家的锁」这条判据失明。`releaseRestorePayload` 的 KDoc 已补上这第三个用途。

---

## 3. 未修项（登记）· 三件套草案

### 3.1 D4 F-06 · Skills / Storage 六处 busy flag 无 `finally`（P2）

- **重判依据**：这些标志都在 `rememberCoroutineScope()` 里 `launch`，与所在 composable **同寿命**。异常逃逸＝进程崩溃（无全局 handler），取消＝composable 已离开、标志随之消失。报告写的「永久停留在忙碌/骨架屏」在当前树里不可达：**可观察的后果是崩溃，不是卡死**。所以修法不是"补 finally"而是"要不要把一次 IO 异常变成一句提示"，这是产品决策，不该由本支顺手拍。
- **触发条件**：真机日志出现 `skillRepository.exportSkillToZip` / `StorageManagementScreen` 扫描块的未捕获异常栈（`FATAL EXCEPTION` + 上述文件名），或用户报「点导出就闪退 / 存储页一进就闪退」。
- **方案**：在**一处**建 `runBusyAction(flagSet, block)` 之类的统一外壳（catch → `errorMessage`；finally → 复位），六个站点逐一改走它；不逐点手抄 try/finally（六份手抄就是下一个同族复发点）。Storage 的 `reload` 扫描块还要区分「取消」与「失败」，因为它跑在 `LaunchedEffect` 里。
- **搁置理由**：触发需要 IO 异常（磁盘满 / 权限 / SQLite 错误），当前无用户报告也无日志证据 → 按修复门 P2 不修；而"补 finally 而不 catch"对真实后果（崩溃）零改善，属于典型的形式修复。

### 3.2 D10 F-03 · `WebViewHolder.destroy` 跳过一次 `unbindPendingListeners`（P3）

- **重判依据**：`releasePlan(attached = false)` 确实不含 `DETACH`（`WebViewReleaseLatch.kt:105-119`），因此 `unbindPendingListeners(wv)` 在 `parent == null` 时不执行。但被注册的 listener 与 `WebView` 之间是**局部闭环**（listener → holder → wv → listener），没有任何 live root 持有已 destroy 的 wv ⇒ GC 可回收，不构成泄漏；`startIfNeeded()` 之外也没有第二次 attach 会把回调唤醒。
- **触发条件**：将来出现「destroy 后复用同一个 WebView 实例」或「holder 被丢进单例缓存」的改动（届时同一 listener 会在新 attach 上重复触发）。
- **方案**：把 `unbindPendingListeners(wv)` 移出 `ReleaseStep.DETACH` 分支、放到 `releasePlan` 循环之前（与 `rebuildAfterRendererGone` 的 580 行对齐），1 行。
- **搁置理由**：无可观察后果（P3），且 `destroy()` 有 CAS 守卫只跑一次；改它只换来形状一致，不是修复。

### 3.3 D10 F-04 · `QuickTestSheet.AudioReplyContent.isPlaying` 无 `data` 键（P3）

- **触发条件**：同一组件槽位在**播放中**被替换成新的 audio `data`（现在只有 `QuickTestSheet.runAll` 的 Running↔AudioReply 切换路径，且它重建节点）。
- **方案**：`remember(data) { mutableStateOf(false) }`（与同函数内 `isReady`/`writeFailed` 对齐）。
- **搁置理由**：纯图标状态错位、切走即复位；一行改动换来的是形状一致（P3 不修）。

### 3.4 D10 F-06 · `TerminalScreen.collectAsStateEffect` 的 `remember` 无 `this` 键（P3）

- **触发条件**：`launchSingleTop` 换 `sessionId` 复用 `TerminalScreen` 的那一帧。
- **方案**：`remember(this) { mutableStateOf(value) }`（与 `LaunchedEffect(this)` 同源）。
- **搁置理由**：`StateFlow.collect` 首个 emit 就是当前值，同一帧内即自愈；且 `termuxSession == null` 的守卫挡住了错误 attach。P3。

### 3.5 顺带落实的两条 D4「未确认」（不是缺陷，但报告里挂着）

1. **`backup_prefs.chat_window_days` 不在 `ConfigBuiltins` 注册（INV-14）→ 判定：不参与备份/导出**。`AutoBackupManager` / `MirrorStore` 用的是同名 prefs 但各管各的 key，`ConfigBackup` 全树无 `backup_prefs` 引用 ⇒ 换机恢复后「聊天窗口天数」回默认 90。后果 = 一个设置项不随备份走（P3）。若将来要修：把 key 迁进 `ConfigBuiltins`（`config/` 域，跨域）。
2. **外部改 `appearance_prefs.theme_mode` 的实时生效（INV-15）→ 判定：成立，不是缺陷**。`MainActivity.kt:535-541` 自己注册 `OnSharedPreferenceChangeListener` 并在 `KEY_THEME_MODE` 变化时更新 `themeMode`。`AppearanceScreen` 不调 `onThemeChanged` 是**正确的**（再调一次＝二次重建）。

---

## 4. 跨域指针（本支按约束未动，附现成补丁）

### 4.1 D10 F-01 · `ui/sandbox/RootfsManagementViewModel.kt`（P1，任务书重点项）

`repairRootfs` 在 `withContext(IO){ verifyIntegrity() }` **之后**才置 `isProcessing = true`（`434-453`），三入口（`352` install / `388` resetRootfs / `434` repairRootfs）都没有 `if (_uiState.value.isProcessing) return`。UI 的 `enabled = !state.isProcessing`（`RootfsManagementScreen.kt:187/202/217`）说明"禁止并发"是产品意图；`RootfsManagementViewModel.kt:64-66` 的注释还记着同类事故。**后果**：连点 Repair 会让 `autoRepair()` 与 `reset()` 并发写同一个 `rootfsDir`。
补丁形状（报告 §F-01 草图可直接用）：三个入口首行加并发守卫；`repairRootfs` 在进入 `launch` 前同步置位 `isProcessing = true`（状态文案同 install/reset），健康分支返回前复位。

### 4.2 D10 F-02 · `ui/sandbox/FilePreviewScreen.kt`（P1，任务书重点项）

`PdfPageRenderer.open` 在 `mutex.withLock` 内 `closed.set(false)` 覆盖了 `close()` 置的真值、且之后不再读 `closed`（`732-742`）；`render` 的 `if (closed.get()) closeLocked()` 在正常返回路径而非 `finally`（`760`）。`close()` 的 KDoc（`765-767`）明写"持锁方在完成时代为释放" —— 契约有，实现漏两处。**后果**：反复快速开关大 PDF 泄漏 fd + 原生内存（进程级）。
补丁形状：`open` 的 `closed.set(false)` 改成 `if (closed.get()) return@withLock 0`，打开句柄后再复核一次；`render` 的收尾包进 `finally`。

### 4.3 D4 F-03 · `config/ConfigBuiltins.kt:1623-1624`（P1）

`providers` 摘要输出 `inst.imageEndpointMode.name`（camelCase），而 `ProvidersCollection.imageEndpointModeFromToken` 只认 `auto|images_generations|chat_completions` ⇒ `minis-config get providers` 读出来的值**回写不进去**（`InvalidValue`）。补丁：改用 `imageEndpointModeToToken(...)`（同文件已导出，`ProvidersCollection` 内部就在用）。同族提醒：`imageEndpointResolved` 那一行同样是 `.name`。

### 4.4 D4 F-04 · `config/ConfigBuiltins.kt:1475`（P2）

`fontScaleField` 写完 `prefs.edit().putInt(key, level).apply()` 后没有 `PrefsWriteBus.publish(prefs, key)`。`config/fields/PrefsFields.kt` 的每个 `PrefsField` 都发；这个 `ClosureField` 是唯一的例外。补丁：加一行 `PrefsWriteBus.publish(prefs, key)`，并跑一次 `prefs_write_bus_*` 门。

### 4.5 D10 F-05 · `ui/markdown/MarkdownText.kt:617`（P3，且 `ui/markdown/` 禁碰）

`remember(cleaned, color, linkListener)` 少了 `style` / `inlineCodeBg` / `inlineCodeColor` / `linkColor`。首帧之后切换主题或动态改字号会命中旧 `AnnotatedString`。

---

## 5. 本地验证与已知缺口

**跑过（施工期窄验证，未跑全量）**
- 装置 `t7/jvm/run.sh`：`post`（shape 4 套 / behaviour）全绿；`pre`（BASE 树）按预期红。
- 脚手架套件：`McpOAuthBusyLifecycleTest` 6、`BackupLaunchAndMinisbakGuardTest` 4、**既有** `BackupFetchBusyGateTest` 7、`RestorePayloadBusyGateTest` 10 —— 后两套在 `pre` 与 `post` 双臂都绿（证明我没动它们的契约，也不需要动）。
- 相关静态门（单独跑，禁全量）：`backup_streaming_export_check`（arm1..arm11 green）、`mcp_oauth_lifecycle_check`（PASS）、`provider_rpc_symmetry_check`（C1..C19 ALL GREEN，含 C15 collection image keys）、`ime_inset_guard`（2/2）、`provider_remember_key_check`（70 PASS）。
- 语法级编译（`kotlinc` + stdlib，滤掉未解析引用）：四个文件无 `expecting/unexpected/syntax` 类错误；括号/花括号配平 0/0。

**缺口（如实记）**
- 三个 Compose 屏**无法在沙箱编译**（缺 AndroidX），行为级只能靠形状臂 —— 这就是为什么 F-02/F-05/F-07 的测试是"源码形状"而非行为；真行为验证交给 CI 的 `unit-tests` + 真机（见下）。
- 真机未验（本支不入 main，真机清单由主会话并集后发）。

**真机建议（并集后一起跑）**
1. provider 详情：Auto → Images API → Auto，看 footer 是否重新探测（不再直接显示旧解析）。
2. MCP 表单：装一台**没有浏览器**的设备/禁用浏览器，点 Authorize → 应弹错误而非闪退；且按钮可再点。
3. 备份页：导入一个 `.minisbak` 时后台正在备份 → 应出现"备份或恢复正在进行中"而不是毫无反应。
4. 备份页：正常导出/导入全链路无回归（`operationBusy` 计数未变）。

## 6. CI

- run **#3214**（dispatch @ `6dc2b947`）：结果见本文件末尾追加段与对话回报。

---

## 7. CI 结果（追加）

**run #3214 / `44021628845` @ `6dc2b94730df2d41ce1269267dbafc37b26026d3`（与本地代码 tip 逐字符一致）**

| job | 结论 | 证据 |
|---|---|---|
| `scan-gate` | **success** | `Summary: 115 passed, 0 failed`；日志明确 `scanners moved in this push (or the diff was unreadable) — running the fixture self-test` + **`ALL 354 CASES PASS`**（本批**真跑了**夹具，不是被跳过）；我碰过的五个门逐个 `selftest has teeth` + `real tree green`（`backup_streaming_export_check` / `mcp_oauth_lifecycle_check` / `provider_remember_key_check` / `provider_rpc_symmetry_check` / `ime_inset_guard`） |
| `unit-tests` | **success** | **9375 PASSED / 0 真实 FAILED**（`grep " FAILED" \| grep -v PASSED` 为空；4 条命中全是名字里带 FAILED 的用例）；16 条新用例**逐条 PASSED**：`ProviderImageEndpointSelectionTest` 6、`McpOAuthBusyLifecycleTest` 6、`BackupLaunchAndMinisbakGuardTest` 4；既有 `BackupFetchBusyGateTest` / `RestorePayloadBusyGateTest` 亦 PASSED |
| `lint` | success | — |
| `build-apk` | success | 23 步全绿 |
| `publish` | success（job） | `Publish to Releases` **skipped** —— main 门控，分支不发布（预期） |

**停止节点达成**：分支 CI 绿 + `head_sha` 与分支 tip 逐字符一致。**未合并 main**（合并起手权在主会话）。

**本批与 CI sha 的关系**：本报告 commit 在 `6dc2b947` 之后单独提交，`git diff 6dc2b947..HEAD -- . ':!fix-reports'` 必须为空（非报告差异为零）。
