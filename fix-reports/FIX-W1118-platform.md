# FIX-W1118-T6 · platform（D11 平台外壳 + D9 F-06/F-07 + R6 崩溃自愈环）

## 0. 基线与交付边界

- **基线**：`c85131ad`（= main，已含 FIX-1117B 五支）。报告写的是 `e6a0276e`，差一个批次；逐条按**当前树**核过，D11/D9/R6 的这 17 条**没有被 1117B 修掉任何一条**（1117B 只动 `ui/chat` 的编辑态粘贴与分享胶囊武装、`MarkdownMath`、rootfs 守卫、arm9 门，与本支文件集无交集）。
- **分支**：`fix/w1118-platform`（worktree `incr1118/wt6`），tip `9a8f7472`（代码 `29b89684` + `524eaa3f` + `9a8f7472`，后两条只碰测试），报告单独提交。
- **CI**：**#3228 / run `44027578478` @ `9a8f74725b4a7e1ecb50177119378c917478b5d0`**（与本地 tip 逐字符一致）→ 五 job 全 success：
  - `unit-tests` `9399 PASSED / 0 FAILED`（BUILD SUCCESSFUL，9400 用例含 1 skipped；本支六个新测试类 **40 条全 PASSED**：ShareHostPolicyTest 10 / QuietHealPolicyTest 11 / StallSessionDialectTest 7 / FaultAttributionTest 7 / CrashPromptFailureTest 2 / MinisDocumentsProviderQueryDocumentTest 3；被改写的先例 `ShareStagedRaceWiringTest` 9 条全 PASSED，`HangDetectorSelfHealTest` 7 条仍 PASSED）；
  - `scan-gate` `115 passed, 0 failed` + 夹具自测 **`ALL 354 CASES PASS`**（本批未改 `scripts/scan/**`，日志却报 `scanners moved in this push (or the diff was unreadable)` → 夹具**真跑了**，所以"门有牙"这一维本批也有证据）；
  - `lint` / `build-apk` success；
  - `publish` 两步（`Move the rolling tag android-latest` / `Publish to Releases`）**skipped**（main 门控生效）。
- **改动面**：17 文件 `+1688 / −36`，其中
  - 生产代码 **10 文件**（8 改 + 2 新增纯策略文件 `share/ShareHostPolicy.kt`、`diagnostics/QuietHealPolicy.kt`）：`+437 / −34`；
  - 单测 **7 文件**（6 新增 `+1061`，1 处先例随重构改写 `+21 / −2`）。
- **allowlist 遵守**：只碰 `share/**`、`diagnostics/**`、`crash/**`、根目录 `MainActivity.kt`、`providers/MinisDocumentsProvider.kt` 及其测试。`scripts/scan/**`、`ui/**`、`service/**`、`sandbox/**`、`speech/**`、`mcp/**` **零触碰**（后两个是本支判级后交出的跨域指针，见 §4）。
- **未跑**：全量 `scan.sh` / `test_scan.py` / gradle（POLICY 禁）。本地只跑本支相关窄验证（见 §3）；全量证据取自本支 CI（§0 + §3.1）。
- **停止节点**：分支 CI 绿且 `head_sha` 与 tip 逐字符一致即停，**不合并 main**。

---

## 1. 判级表（17 条，逐条结论）

| ID | 判级 | 结论 | 去向 |
|---|---|---|---|
| **D11 F-01** `ShareReceiverActivity.importProviderJson` 读未初始化的 `lateinit providerRepository` | **P0**（崩溃） | **修** | 本支 |
| **D11 F-02** 分享提取/弹窗中途销毁不释放 `StagedInFlightFiles` → 文件与 500MB 预算永久泄漏 | **P1**（磁盘+配额，进程存活期不可恢复） | **修** | 本支 |
| **D11 F-03** `stop()` 后迟到 `onReadyForSpeech()` 把 `FINISHING` 逆转成 `RECORDING` | **P1**（录音已停仍显示录音中，可再次触发 stop） | 未修 | 跨域 → `speech/**` |
| **D11 F-04** `OAuthCallbackServer.start()/stop()` 竞态泄漏 `ServerSocket`；`MCPOAuthController` 字段被并发授权互杀 | **P1**（端口泄漏 + 并发授权互相打断） | 未修 | 跨域 → `mcp/oauth/**` |
| **D11 F-05** 分享热启动不导航，用户停在非聊天页时 30s 后静默丢失 | **P1**（内容丢失，用户可感知） | **修** | 本支 |
| **D11 F-06** = R6 F-02 弹窗失败分支清空 `pendingShareFiles` 并 `setSafeMode(false)` | **P2**（窄触发：弹窗构造抛异常；后果是丢提示+误通知订阅者） | **修**（同一 `catch` 块 2 行，成本≈0） | 本支 |
| **D11 F-07** `TextToSpeechManager.onInit` 未校验 `shutdown()` | **P2**（半死状态，错误日志噪声；`speak()` 有 `!isInitialized` 之外的 `tts == null` 分支） | 未修 | 跨域 → `speech/**` |
| **D11 F-08** `cancelRecording()` 未 `resetLevels()` | **P2**（波形残留一下，下次 start 覆盖） | 未修 | 跨域 → `speech/**` |
| **D11 F-09** `queryDocument` 回原始 `documentId` | **P2**（latent：provider 未在 Manifest 注册） | **修**（同一函数同一行，成本≈0） | 本支 |
| **D9 F-06** `markHealthyTick` 锁内不重检 `KEY_LAST_HANG_AT`（A8 check-then-act） | **P2**（窄竞态：需主线程刚从 hang 恢复且 tick 落在 `recordHang` 写入之后；后果是丢 1 次 hang + 渲染熔断被立刻拆掉） | **修**（锁内 3 行 + 纯策略） | 本支 |
| **D9 F-07** `LaunchCycleBeacon.readLastSessionId` 读 `session=` 死方言（有读者无生产者） | **P2**（诊断字段恒 `unknown`，无用户可见行为） | **修**（生产者补 1 行 + 读取端抽纯函数） | 本支 |
| **R6 F-01** `FaultAttribution` 零引用孤儿 + `"timeout"` 遮蔽 `"first chunk timeout"` | **P2**（latent：全仓 0 引用，接入即必错） | **修排序**；**不删不接**（见 §5.4） | 本支（排序）/ 登记（接入） |
| **R6 F-02** 同 D11 F-06 | — | 同上（一处修，两条关） | 本支 |
| **R6 F-03** `healQuietHang` 永久跳过跨进程遗留计数 → 强制回桌面 + 渲染降级无法自愈 | **P1**（体验降级且不可自行恢复：只能靠"再 hang 一次"解除） | **修** | 本支 |
| **R6 F-04** 崩溃/启动循环/强制回桌面三条熔断环缺 EFFECT 回读与 LEARN 回灌（L2 开环） | **P2**（架构级，无具体触发路径） | 未修 | 只登记（§4.1 三件套） |

**判级口径说明**：报告给的 High/medium 普遍偏高。F-01 升到 P0 是因为触发链可指认且**必然崩溃**（安全模式下系统直接拉起导出的 `ACTION_SEND` Activity），崩溃日志还会灌进正在收集的崩溃包；F-02/F-05/F-03(R6) 定 P1 是因为"常用操作能碰到"（分享大文件后返回、在设置页分享、上一次启动挂了三次）；其余 P2 的共同点是**需要一个具体形态才触发**（弹窗构造抛异常、A8 时序窗口、provider 注册后才可达、模块未接线）。

---

## 2. 逐条证据（修复前后 + 测试名 + 变异）

### 2.0 先证：pre-fix / post-fix 机械对照

脚本 `shared/work/incr1118/t6/pre_post.py`（把基线树 `29b89684~1` 的 8 个文件与修复后同文件逐条比对）：

| 事实 | pre | post |
|---|---|---|
| F-01 未守卫的 lateinit 读（`(applicationContext as? MinisApp)?.providerRepository`） | True | **False** |
| F-01 导入路径查安全模式 / init 闩锁 / 降级态 | False | **True** |
| F-02 无 `onDestroy` 释放 | True | **False** |
| F-02 有销毁释放路径 | False | **True** |
| F-05 无分享→聊天路由 | True | **False** |
| F-05 有分享→聊天路由 | False | **True** |
| D9 F-06 锁内只查 `count == 0` | True | **False** |
| D9 F-06 锁内重检静默窗 | False | **True** |
| R6 F-03 自愈对继承计数永久跳过 | True | **False** |
| R6 F-03 自愈按进程存活窗口放行 | False | **True** |
| D9 F-07 stall 文件无 `session=` 字段 | True | **False** |
| D9 F-07 stall 文件写 `session=` 字段 | False | **True** |
| D9 F-07 读取端正则存在（不变项） | True | True |
| D11 F-06 弹窗失败清空 burst / 关安全模式 | True | **False** |
| D11 F-06 弹窗失败保留 burst | False | **True** |
| R6 F-01 连接词表先于服务词表（阴影） | True | **False** |
| R6 F-01 服务词表先于连接词表 | False | **True** |
| D11 F-09 `queryDocument` 回原始 id | True | **False** |
| D11 F-09 `queryDocument` 回归一化 id | False | **True** |

`PAIRS OK`（19 对全部按要求翻转，不变项保持）。

### 2.1 D11 F-01 · P0 · 分享导入不再读未初始化的 `lateinit`

- **触发路径（可指认）**：连续崩溃 → `CrashFrequencyDetector.isSafeMode()` 为真 → `MinisApp.onCreate` 在 `providerRepository = ProviderRepository(this)` **之前** `return` → 用户从文件管理器把 Provider 导出 `.json` 分享到 RikkaMinis（系统直接拉起 `ShareReceiverActivity`，**不过 `MainActivity` 的三道守卫**）→ 点"导入" → `Dispatchers.IO` 上读 `providerRepository` → `UninitializedPropertyAccessException`，进程崩溃，并且这次崩溃又写进 `crash-*.log`，把安全模式的窗口继续往后推。降级态（`SHOW_NEWER_DB_GUIDANCE`）与 `_initSkipped` 闩锁是同一条路的另两个入口。
- **修复**：新增纯策略 `ShareHostPolicy.mayUseAppRepositories(isSafeMode, isInitSkipped, isNewerDbGuidance)`（三态全否才放行），`importProviderJson` 先问策略、再在 `try/catch` 里读字段（第二道保险：将来任何新的早退路径都退化成"导入失败"而不是崩溃）。失败落回既有附件流程，**文件不丢**，复用现有文案 `share_provider_json_import_failed`，不新增 i18n 键。
- **用户行为变化**：安全模式/降级态下分享 Provider JSON 点"导入" → 以前进程崩溃，现在提示导入失败并把文件当作附件注入聊天。
- **测试**：`share/ShareHostPolicyTest.kt` — `repositories are reachable only after a complete init`（4 组状态，含 `_initSkipped` 单独拦）、`provider import asks the gate before it reads the lateinit repository`（gate 在 `app.providerRepository` 之前、且是 read 的条件、且预修字面量已消失）。
- **变异**：`the wire probes reject the pre-fix sources` 把 `val repo = if (mayUseRepositories && app != null)` 改回 `(applicationContext as? …)?.providerRepository` + `if (true)` → 探针红（`import-guard`）。

### 2.2 D11 F-02 · P1 · 销毁时释放未交接的暂存登记

- **触发路径**：分享 100MB 文件 → `copyUriToStaging` 先 `StagedInFlightFiles.begin(name)` → 用户在拷贝未完成时按返回/切走，或弹窗期间 Activity 被系统销毁（进程存活）→ `lifecycleScope` 取消，`withContext(Main){ finishWithAttachmentFlow() }` 永不执行 → 该名字**永久**留在登记表里 → `cleanSharedFiles` 的 keep 集永远保护它（`fileName in inFlight`）→ 文件留在磁盘、字节数继续占 500MB 预算，直到进程死。
- **时序（哪个回调在哪个线程、销毁后到达）**：`onDestroy()` 在 **Main** 上执行，且 `lifecycleScope` 已在 `ON_DESTROY` 取消——抽取协程既不会 append item，也走不到它自己的 `end()`。因此释放必须由 `onDestroy` 自己做，且必须拿到"这个实例 claim 了哪些名字"。
- **修复**：每实例 `claimedNames` 记录 `begin(name)` 的调用点（流拷贝 + 长文本两条），失败臂同步移除；`onDestroy` 通过 `ShareHostPolicy.shouldReleaseStagedOnDestroy(handedOff, isChangingConfigurations, stateSavedForRestore)` 决定是否释放：
  - **已落地**的文件：`releaseStagedFiles` 删除磁盘文件 + `debitStagedBytes` + `end()`；
  - **仍在拷贝中**的名字（无 item 指向）：只 `end()` 不删（写入方仍持着 fd，此刻删会竞态；已落地的字节由下一次 `cleanSharedFiles` 的**绝对对账**收敛）。
- **第三个条件 `stateSavedForRestore`（报告没提，我认为必须加）**：报告建议只在"未交接 + 非旋转"时释放。但 `onSaveInstanceState` 存下的 `STATE_ITEMS` 正是**下一次重建要重新交接的同一批文件**（R5-A3 C-09① 的整条恢复路径），`onCreate` 的 KDoc 也写着"restored flow re-offers them"。Android 对**正在 finish** 的 Activity 跳过 `onSaveInstanceState`，所以"状态被保存了"就意味着"重建在即"——只按 `isChangingConfigurations` 判断会删掉重建要用的文件，把恢复路径变成幽灵附件。故：`handedOff || isChangingConfigurations || stateSavedForRestore` 三者任一成立都不释放；真正的放弃（返回键/任务移除）才立即释放。若保存后重建没来，泄漏被进程边界兜住（登记表随进程消失，文件退化成普通孤儿，下一次 clean 收走）。
- **用户行为变化**：分享中途退出不再留下不可回收的暂存文件与虚拟占用的 500MB 预算；旋转重建与系统销毁重建的分享内容仍然完整。
- **测试**：`share/ShareHostPolicyTest.kt` — `destroy releases staged files that were never handed off`（4 组：释放/已交接/旋转/已存状态）、`onDestroy releases only what this instance never handed off`（决策被调用、两个入参、release 调用、两个 claim 站点、交接与 discard 各自置位）。
- **变异**：同一负控测试删掉 `releaseUncommittedStagedFiles()` → `destroy-release` 红。

### 2.3 D11 F-05 · P1 · 分享热启动必须落到聊天

- **触发路径**：用户停在设置页 → 外部分享文本/图片 → `ShareReceiverActivity` 存 `PendingShare` 后 `launchMainActivity`（只带 `shared_content=true`，**无 data URI**）→ `MainActivity.onNewIntent` 把分享灌进 `ShareCoordinator.buffer`，随后 `handleDeepLink(null)` 什么都不做 → 只有 `ChatScreen` 的 `LaunchedEffect(shareBufferVersion)` 消费 buffer，而设置页上 `ChatScreen` 已离开组合 → 30s（`BUFFER_TTL_MS`）后过期，**无任何提示**。
- **修复**：`onNewIntent`/`onCreate` 各置一条 `pendingShareChatRoute`（Compose state），由 `setContent` 里的 `LaunchedEffect` 消费：先问 `ShareHostPolicy.shareNeedsChatRoute(topRoute, topIsChatKeptAliveOverlay, belowRoute, Routes.CHAT)`——
  - 顶端就是 chat → **不导航**（正在看的会话由 `ChatScreen` 自己消费，导航会把用户从会话里拽走）；
  - 顶端是 dialog（`FILE_PREVIEW`，`ChatRoutePresence.isChatKeptAliveOverlay`）且下面就是 chat → 也不导航（chat 仍在组合，照样消费）；
  - 其它（设置页、设置页上的 dialog、无 entry）→ 铸一个新草稿 `Routes.chat(ComposerDraftStore.nextDraftId(this))` 并 `navigate(launchSingleTop)`。
  冷启动真冷启动不必管：`AppNavigation` 的发射器每个分支都落在 chat 上，本 effect 看到顶端是 chat 就不动（不会双重导航）。
- **用户行为变化**：在任意非聊天页收到分享 → 直接进入一个草稿聊天并带上内容；在聊天里收到分享 → 仍注入当前会话，不受影响。
- **测试**：`share/ShareHostPolicyTest.kt` — 4 条纯决策（live chat / dialog over chat / full destination stacked / dialog not over chat）+ `warm-start share arms a chat route and navigates only when no chat is live`。
- **变异**：负控删掉 effect 里的 `pendingShareChatRoute.value = null` → `share-delivery` 红。

### 2.4 D11 F-06 / R6 F-02 · P2 · 弹窗失败不再反噬崩溃包

- **原代码与自己的注释互相矛盾**：`catch` 上方写着"leave the burst's dismissal clock untouched… the recreated Activity must still be able to prompt"，下面却 `pendingShareFiles = null` + `setSafeMode(false)`。
- **后果**：①重建后的 Activity 走"无待处理包"分支 → `onClosed()` = `finish()`，应用退出、崩溃日志此后再无机会被提供；②`setSafeMode(false)` 在 `_initSkipped` 仍为真（`lateinit` 仓库从未构建）时 `fireSafeModeCleared()`，等于向订阅者宣告一个不存在的恢复。
- **修复**：删掉那两行，保留 `promptInFlight = false` / `promptHost = null` / `onClosed?.invoke()`。安全模式留到 `finishClose()`（用户真正处理）或下一次冷启动重新判定。
- **用户行为变化**：弹窗构造失败（多数是 Activity 正在销毁）时不再静默丢弃崩溃包，也不会误报"已恢复"。
- **测试**：`crash/CrashPromptFailureTest.kt` — `a dialog failure keeps the burst and safe mode but releases ownership`（区域定位到 `dialog failed` → `onClosed?.invoke()`，断言两条禁令 + 三条保留）。
- **变异**：负控把两行插回去 → 探针红。

### 2.5 D11 F-09 · P2 · `queryDocument` 回归一化 id

- **事实**：`resolveDoc` / `flagsFor` 内部都归一化，只有写回 `COLUMN_DOCUMENT_ID` 的那一处用原始入参，导致 provider 报出一个**它自己的列表永远不会产生**的 id（客户端缓存键分裂）。latent：provider 未在 `AndroidManifest.xml` 注册。
- **修复**：`val cleanId = normalizeDocId(documentId)`，`resolveDoc(cleanId)` / `flagsFor(cleanId, file)` / `.add(COLUMN_DOCUMENT_ID, cleanId)`；`displayName` 判根改用 `cleanId.isEmpty()`（与 `isRootDocId` 同一判据）。
- **用户行为变化**：无（不可达）；注册后 SAF 客户端不会因拼写差异看到重复文档。
- **测试**：`providers/MinisDocumentsProviderQueryDocumentTest.kt` — `the document id is normalized like every other id decision`（字面量：`" /shared/a.txt/ "` → `shared/a.txt`）+ `queryDocument reports the normalized id`。
- **变异**：负控把 `.add(..., cleanId)` 改回 `documentId` → 探针红。

### 2.6 D9 F-06 · P2 · `markHealthyTick` 锁内重检静默窗

- **时序**（A8 check-then-act）：①主线程 `markHealthyTick` 锁外读到 `count=1`、上次 hang 20s 前 → 两道外层检查都过；②看门狗 `recordHang` 取锁写入 `count=2` / `last_hang_at=now` 并点亮渲染熔断；③主线程进锁后**只重检 `count == 0`**（为假）→ 把刚记上的计数清零、熔断拆掉。
- **修复**：锁内用 `QuietHealPolicy.mayResetOnHealthyTick(count, lastHangAt, now, quiet)` 对 count 与时间戳**一起**重判（时间戳 0 的历史记录仍可清，避免围死）。
- **用户行为变化**：无直接可见；不再出现"刚 hang 一次 → 熔断亮 0ms 就灭、计数丢失"。
- **测试**：`diagnostics/QuietHealPolicyTest.kt` — 4 条纯决策（0 计数 / 刚记录 / 超窗 / 边界 / 无时间戳）+ `markHealthyTick re-checks the quiet window inside the counter lock`（决策在锁内、在 `prefs.edit()` 之前、喂的是当前时间戳）。
- **变异**：负控把决策块换回 `if (prefs.getInt(KEY_HANG_COUNT, 0) == 0) return` → `locked-recheck` 红。

### 2.7 D9 F-07 · P2 · stall 文件补 `session=` 字段

- **事实**：`LaunchCycleBeacon.readLastSessionId` 从最新 `stall-*.log` 头 2KB 里 grep `session[=:]\s*([0-9a-fA-F-]{8,})`；`HangDetector.writeStallSample` 是唯一写者，从没写过这个字段（id 只进了 JankDiag 的 `println` → stdout/日报，而 beacon 不读日报）→ 冷启动日志里 `lastSessionId` 恒 `unknown`。这是"有读者无生产者"的死方言。
- **修复**：`ContentDiag.currentRenderSessionId()` 暴露当前渲染会话；`writeStallSample` 在**头行之后、25 帧栈之前**追加 `session=<id>`（放在帧前是为了留在读取端的 2KB 窗口内）。读取端的正则抽成 file-scope `internal fun parseStallSessionId()` 以便 JVM 直接钉住。
- **用户行为变化**：无（诊断字段）。下次 hang 的 stall 报告能说清"是哪个会话卡住的"。
- **测试**：`diagnostics/StallSessionDialectTest.kt` — 读取端 5 条（正常 / 无字段 / `:` / `renderSession=` 不算 / 短 id 不算，最后两条防"未来把 fingerprint 行抄进文件"）、生产者 1 条（字段存在 + 在 `thread: main` 之前）。
- **变异**：负控删掉生产者那一行 → 探针红。
- **附带口径修正**：`readLastSessionId` 的 KDoc 原来声称"HangDetector writes the session id"，改成如实描述（本次才真正成立）。

### 2.8 R6 F-01 · P2 · 服务端词表优先于连接词表

- **事实**：`CONNECTION_PATTERNS` 含 `"timeout"`，`SERVICE_PATTERNS` 含 `"first chunk timeout"`，而匹配顺序是 credentials → connection → service ⇒ 服务端词条**永远不可达**，"首包预算耗尽"（端点接了请求然后沉默）被报成用户网络/代理的问题。
- **修复**：把 service 检查提到 connection 之前（credentials 仍在最前——`"expired key"` 是 401 但用户该做的是换 key）；在 connection 词表上加"不许出现服务端能含的词"的契约注释。
- **为什么不删/不接**：见 §5.4。
- **测试**：`diagnostics/FaultAttributionTest.kt` — 5 条字面量分类（first chunk → SERVICE；HTTP 503 → SERVICE；`SocketTimeoutException: connect timed out` / `ConnectException` / DNS → CONNECTION；401+invalid api key → CREDENTIALS；null/空白/其它 → UNCLEAR）+ `the service list is consulted before the connection list`。
- **变异**：负控把两个 if 块换回旧顺序 → `order probe` 红。

### 2.9 R6 F-03 · P1 · 继承计数可以自愈了

- **触发路径**：前一进程累计 ≥3 次前台 hang → `hang_count ≥ 3` 落盘 → 进程退出 → 新进程 `start()` 用它把渲染熔断点亮，并让 `shouldForceHomeOnLaunch` 为真（每次启动被强制落在 Home/新草稿）→ `healQuietHang` 首行 `if (!hangCountedThisProcess.get()) return` **无条件**返回 ⇒ 只有当**本进程又 hang 一次**时计数才可能被清。用户看到的是"markdown 一直退化成纯文本 + 启动一直被改写"，而且没有任何自愈路径。
- **修复**：新增 `healPending`（"还有东西要清"）与 `startedAtMs`：
  - `start()` 若磁盘计数 > 0 → `healPending = true`（继承计数入场）；
  - `recordHang()` 在同锁内同时置 `hangCountedThisProcess` 与 `healPending`；
  - `healQuietHang` 用 `QuietHealPolicy.mayAttemptHeal(countedThisProcess, carriedOverCount, processAliveMs, quiet)`：本进程计过的立即放行；**继承的**要等进程存活 ≥ 一个静默窗（10s）——这晚于全部冷启动读取点（`shouldForceHomeOnLaunch` 在 launch 期被消费），并且要再通过 `markHealthyTick` 的"距上次 hang ≥ 10s"才能真的清；
  - 归零后两个 flag 都清掉，健康心跳重新回到零 prefs 读。
- **用户行为变化**：上一次启动因连续 hang 被"强制回桌面 + 渲染降级"的用户，只要这一次启动后主线程健康 10 秒，状态自动恢复；不再需要"再 hang 一次"或进设置手动重置（后者本来无生产调用方）。
- **测试**：`diagnostics/QuietHealPolicyTest.kt` — 4 条 gate 决策（本进程立即 / 继承 0ms 拦 / 继承 9999ms 拦 / 继承 10000ms 放行 / 无事可做零读）+ `start seeds the inherited-count heal and healQuietHang consumes the gate`。
- **变异**：负控把旧的 `if (!hangCountedThisProcess.get()) return` 插回去 → `heal-gate` 红；删掉 `start()` 的 seed → `heal-seed` 红。
- **既有闸门未被破坏**：`HangDetectorSelfHealTest` 钉的三条形状（heal 在 healthy 分支、`markHealthyTick()` 全文恰好 2 次、flag 写入在 `recordHang` 锁内且恰好 1 次）在镜像脚本里逐条复核仍成立。

---

## 3. 本地窄验证（无 Android SDK 能做的都做了）

| 手段 | 结果 |
|---|---|
| `kotlinc` 编译两个新纯策略文件 + `FaultAttribution` + `parseStallSessionId` | 通过（`-no-stdlib` + `kotlin-stdlib-2.1.0.jar`） |
| 纯逻辑 harness（39 条，逐条对应 JUnit 期望值，字面量写死） | `checks=39 failures=0` |
| shape 探针镜像（70 条，直接在真树上跑 Kotlin 探针的字符串逻辑） | `checks=70 failures=0` |
| pre/post 对照（19 对） | `PAIRS OK` |
| 括号/圆括号配平 + KDoc 链接陷阱扫描（16 文件） | 全部 0 失衡、0 命中 |
| 全量 `scan.sh` / `test_scan.py` / gradle | **未跑**（POLICY 禁）；全量证据来自本支 CI（§0） |
| 分支 CI（三轮） | 见 §3.1；**绿在第三轮**，前两轮各抓到一类本地镜像抓不到的问题 |

**为什么要镜像脚本**：6 个新测试类里有 4 个是"读真源码的形状探针"，它们的唯一失败模式是**字面量/缩进与源码不一致**——这种错只有在 CI 才暴露，一次 4 分钟。镜像脚本把同一批判据在沙箱里跑一遍，把一次 CI 往返换成 0.1 秒。

**已知未本地验证项**：生产文件（`ShareReceiverActivity` / `MainActivity` / `HangDetector` / …）的**编译**只有 CI 能证明（本地无 Android SDK）。已做的替代检查：import 存在性、括号配平、`internal` 可见性（同模块）、`return` 在 `synchronized` 内为非局部返回（既有代码同形）、智能转换条件补 `app != null`。

### 3.1 CI 三轮迭代（如实记录，三轮各抓到一类本地抓不到的错）

| 轮 | run | 结论 | 抓到什么 | 为什么本地没抓到 |
|---|---|---|---|---|
| 1 | `44024251845` #3220 @ `29b89684` | unit-tests **编译失败** | `StallSessionDialectTest.kt:78,102 Unresolved reference 'source'` —— 写文件时漏了 `source()` 辅助函数 | 本地镜像脚本自带自己的 `read()`，Java/Kotlin 层面的"这个类里没有这个方法"只有编译器能判 |
| 2 | `44026218557` #3224 @ `524eaa3f` | unit-tests 2 failed / 9400 | ① `ShareHostPolicyTest > the wire probes reject the pre-fix sources`：删掉 `releaseUncommittedStagedFiles()` 调用后探针**仍绿**——因为探针在 `onDestroy` 之后整段里搜这个名字，而**函数定义本身**就含它；② 先例 `ShareStagedRaceWiringTest > the provider-import discard path releases each consumed name` 红——我把 per-file 删除/释放循环抽进共享 `releaseStagedFiles()`，先例仍要求 `discardStagedAttachments` 体内出现 `f.delete()` | **镜像脚本复制了同一个盲点**（它按同样的"整段 contains"实现），所以两个拇指一起错；这正是"负控必须真跑"的价值——JUnit 的负控聚合断言把 ① 抓出来了 |
| 3 | `44027578478` #3228 @ `9a8f7472` | **五 job 全绿** | —（两条都修好：探针按 `onDestroy` 到 helper 定义之前切片；先例改为断言"discard 委托共享释放路径"并保留 `f.delete` / `end` / `debit` 三条意图断言） | — |

**可复用两条**：①**形状探针的作用域必须切成"调用点的函数体"，不能是"从某标记到文件尾"**——否则函数/常量的**定义**本身就会满足 `contains`，探针退化成恒真；②**改共享原语时，先例测试的锚会被移动**，这时正确动作是把先例锚到**新决策**（此处：discard 必须把已消费项交给共享释放路径，而共享路径必须删除+扣账+释放），并保留原意图的三条断言，而不是把代码改回旧形状去讨好旧测试。

---

## 4. 未修项 / 跨域指针

### 4.1 R6 F-04（L2 开环）— 三件套登记，不修

- **触发条件**：需要有人开始消费"熔断动作生效后是否真的脱离循环"这个信号（例如：强制回桌面后仍连续崩溃，或产品要求"熔断后自报效果"）；当前三条环的动作后只写结构化日志，没有任何回读。
- **方案**：给每条环加 EFFECT 回读——①崩溃环：`setSafeMode(true)` 后在下次启动读"是否仍有 `crash-*.log` 新增"；②启动循环环：`ForceHomeTrace.resolverMode` 强制模式 3 后，比对下一次 `LaunchCycleBeacon` 判定是否仍 `crash_or_stall`；③卡顿环：`_renderBreakerActive` 置位后，用 `watchLoop` 的同一 `since < 3000ms` 指标登记恢复次数。三者都要新增持久化字段（跨层：prefs 键 + 读取点 + 清理点）与 JVM 纯策略。
- **搁置理由**：三条环目前都**没有具体触发路径上的错**（报告自己的五边台账里 ACT 都成立，缺的是 EFFECT/LEARN）；按修复门属"架构级改进"，装置规模远超影响面（新增持久化字段 × 3 + 回读时机 × 3 + 各自的跨进程口径），且效果无法在沙箱里验证，只能等真机长跑。R6 F-03 已修掉其中**唯一一条会让用户卡住**的派生后果（继承计数不可自愈）。

### 4.2 R6 F-01 的"接上"（跨域）

- **触发条件**：真的要做"近期失败归因"UI 面板（`labelResKey` 的 4 个 `fault_layer_*` 键要进 7 语言 `strings.xml` + provider 失败面 UI）。
- **方案**：在失败卡片上读 `FaultAttribution.attribute(failureReason)`，4 个 key 落 7 语言；接入点建议 provider 流式失败横幅（与 `ProviderHealthTracker` 同屏）。
- **搁置理由**：跨域——需要 `ui/**` + `strings.xml` × 7，不属本支 allowlist；这也是 dev-history 里记录的"移植包 A 只做纯逻辑，UI 面板为后续独立项"的原议。

### 4.3 跨域指针（判级已给，建议由主会话分派到 speech/mcp 支）

| ID | 判级 | 一句话方案 | 目标文件 |
|---|---|---|---|
| D11 F-03 | P1 | `SpeechRecognitionManager.onReadyForSpeech()` 加 `if (_state.value == STARTING)` 守卫（或让 `ProviderSpeechRecognitionEngine` 的 `deliver` 对非 `onFinal` 回调也走 `captureDeliverySuperseded`） | `speech/SpeechRecognitionManager.kt`、`ProviderSpeechRecognitionEngine.kt` |
| D11 F-04 | P1 | `start()` 在后台线程内、**创建 socket 之后**再判 `running`（失败即 close）；`callbackServer` 改为每次授权独立实例并用 token 校验归属 | `mcp/oauth/OAuthCallbackServer.kt`、`MCPOAuthController.kt` |
| D11 F-07 | P2 | `onInit` 首行 `val engine = tts ?: return`，并用代次计数拒绝 `shutdown()` 之后的回调 | `speech/TextToSpeechManager.kt` |
| D11 F-08 | P2 | `cancelRecording()` 补 `resetLevels()` | `speech/SpeechRecognitionManager.kt` |

> 这四条我**只读代码取证、未改一行**：报告行号与当前树一致（`SpeechRecognitionManager.kt:255-257`、`OAuthCallbackServer.kt:49-78`、`TextToSpeechManager.kt:94-119`、`SpeechRecognitionManager.kt:306-311`），判级按触发路径重判（F-03/F-04 从 High/Medium 落到 P1，F-07/F-08 从 Medium/Low 落到 P2）。

---

## 5. 取舍与判读口径（供复核者）

1. **F-02 的第三个条件不是"加保险"，是修一个报告没看到的回归**：只按 `handedOff + isChangingConfigurations` 释放，会在"系统销毁但保留 saved state"时删掉重建要用的文件——那正是 R5-A3 C-09① 建的那条恢复路径。判据用 Android 的既有事实（finishing 的 Activity 不写 saved state），不引入新 API。
2. **F-05 只有在"屏幕上没有活着的 chat"时才导航**：新增 `topIsChatKeptAliveOverlay` 维度是为了不把用户从正在读的会话（或其上的 `FILE_PREVIEW` 对话框）里拽走。`ShareHostPolicy` 的纯函数让这条判据可测，`MainActivity` 只做接线。
3. **R6 F-03 的 10s 窗口是"晚于所有冷启动读取点"的保守选择**，不是新的魔法数：复用了既有的 `RESET_AFTER_QUIET_MS`，且 `markHealthyTick` 仍要再判一次"距上次 hang ≥ 10s"。
4. **R6 F-01 不删模块**：`FaultAttribution` 的"零引用"是真的（全仓 grep：只有定义与 dev-history 记录），但它是已登记的移植资产（dev-history 明写"UI 面板为登记的后续独立项"），删除等于把后续项的原料扔掉；而按修复门，"死码里的 latent 分类错误"只需在同一函数内 4 行修好顺序即可，接入时不会带坑上线。
5. **D9 F-06/F-07 按 P2 修**：两者都属"窄触发/诊断字段"，之所以仍动手，是因为成本落在**同一函数、同一行**（POLICY 的 P2 顺路例外），且 D9 F-06 的竞态会在"刚 hang 又被健康 tick 扫到"这个真实时序里吃掉一次计数。

---

## 6. 残留风险 / 真机清单建议

| 项 | 说明 |
|---|---|
| `MainActivity` 的"无 back stack entry"分支 | 理论上不会发生（NavHost 在同一组合上传入 graph）；真发生时只记一条 warning，buffer 照旧过期（= 修复前行为），不崩。 |
| F-02 的"在拷中只释放不删" | 若拷贝协程在 `onDestroy` 后仍完成写入并 credit，字节会暂时多计；下一次 `cleanSharedFiles` 以磁盘绝对值对账收敛。 |
| R6 F-03 的边界 | 若某次冷启动在 10s 内就被杀，继承计数保持（下次启动再看），不会误清。 |
| 需真机验证（无法在沙箱证明） | ①安全模式分享 Provider JSON：不崩、提示导入失败、文件入聊天（用崩溃两次进安全模式后 `am start -a SEND` 分享一个 provider 导出 json）；②设置页分享文本：直达草稿聊天并带上文本；聊天中分享：不跳页；③分享大图后立刻返回：`filesDir/share_extension/` 不残留、后续大文件分享不被 `RejectedCumulative` 拒；④旋转中途的分享弹窗：重建后仍能导入/附上（不能变成空附件）；⑤连续 hang 三次后重启：10s 内强制回桌面，之后 markdown 恢复（不再永久降级）。 |

---

## 7. 交付清单

- 分支 `fix/w1118-platform`，基线 `c85131ad`
- 代码 + 测试修提交 `29b89684` / `524eaa3f` / `9a8f7472`（17 文件，`+1688 / −36`）
- CI #3228 @ `9a8f7472` 五 job 全绿（`head_sha` 与 tip 逐字符一致）= 停止节点
- 报告提交（本文件）
- 复现脚本（沙箱内，不随代码入库）：`/var/minis/shared/work/incr1118/t6/{probe_mirror.py,pre_post.py}`、`{src/,out/}`（纯逻辑 harness）
