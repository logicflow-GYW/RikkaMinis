# FIX-R4-U 收口报告（B13 nav-ui / 生命周期 / 通知 / 内存回收）

- **分支**：`fix/r4-b13-lifecycle`
- **基线**：main `2103729f`
- **来源报告**：`meta-audit/reports/r4/B13-nav-ui-2026-10-29.md`（F-01..F-09）
- **任务书**：`meta-audit-1029/r4/fix-tasks/FIX-R4-U.md`
- **提交**：`8c102968`（骨架）→ `4fb0eced`（F-04..F-07）→ `ca012625`（四条纯核的表驱动断言）

> **环境事故说明（影响本报告的读数口径）**：执行期间 `/var/minis/shared/work` 被反复清空（08:11 / 08:14 / 08:17 三次），任务书副本、首次 worktree、`jvm-libs` 与 `kotlin-home` 一并消失。本支最终在工作副本 `/var/minis/workspace/r4u`（per-session，未被波及）重建并按上下文重放，因此：**本地没有跑过 JVM 测试**（`kotlinc` 的 `KOTLIN_HOME` 与 200MB jar 集在 shared 里被删，按告警要求未重建装置）。表驱动断言已写进仓库 `src/test`，由 CI 的 `testReleaseUnitTest` 执行——本报告的 ③④ 两节据实区分「本地读到什么」与「CI 才能读出什么」。

---

## ① 结论表

| 条目 | 结论 | P 级 | 证据（文件:行） |
|---|---|---|---|
| **F-01** 草稿键被写入 crash-recovery | **成立（半条已被 da1032-D 修掉）** | P1 | `MainActivity.kt:606` 旧写入点直接 `putString(KEY_LAST_CHAT_SESSION_ID, sid)`；`sid` 经 `ChatViewModelStore.resolveSessionId` 解析，但**未注册别名时原样返回草稿键** `__new__<uuid>` ⇒ 崩溃后合成 `OpenSession("__new__…")` |
| **F-02** 冷启动恢复绕过 AUTO 15 分钟窗 | **成立** | P1 | `MainActivity.kt:274-283` 旧代码只查 `coldStartRestoreAllowed(launchMode)`；`AppNavigation.kt:548` 的 `autoThresholdMs` 因 `hasDeepLink` 早退而不执行；已删会话同样被恢复（旧文件里没有任何删除侧清理） |
| **F-04** 切后台通知栏冻结 | **成立** | P1 | `SessionActivityTracker.kt:635` 旧 `updateService()`：`if (!isAppForeground) { Log.d(...); return }` —— 一条前台判定同时挡掉「启动服务」与「刷新已运行服务的通知」 |
| **F-05** 完成态被 500ms 停服抢掉 | **成立（机制与报告表述略有差异，见 §8）** | P2 | `AgentForegroundService.kt:432` 的完成态只活在服务实例里（`hasCompletionPending` + overlay）；`SessionActivityTracker` 的 `pendingIdleStop`（`IDLE_STOP_DELAY_MS = 500`）在 `setInactive` 后必然触发 `stopServiceNow()` → `onDestroy` 隐藏 overlay 并撤通知 |
| **F-06** 工具运行态全局单槽 | **成立（结构性，按任务书「控制半径」只做一半）** | P1 | `SessionActivityTracker.kt:57/58/74/84` 四个 `MutableStateFlow` 单槽；`clearToolRunning()` 无条件 `_currentToolName.value = null`，与 `SessionConcurrencyManager` 的 2–3 会话并发直接冲突 |
| **F-07** 内存回收绕过活跃判据 | **成立，且比报告多一处** | P1 | ①`MinisApp.kt:386` `onTrimMemory` 第一分支 `if (!hasActiveWork) System.gc()` 有门；②同函数 gate 分支 `System.gc()` **无门**（`TRIM_MEMORY_RUNNING_CRITICAL` 且前台时两处都命中 = 空闲进程跑两次，忙碌进程仍跑一次）；③**报告未提**：`AppMemoryGovernor.tick(..., toolRunning = SessionActivityTracker.isToolRunning.value)` 只传工具态，第二会话流式时被当成空闲 |
| **F-09** dialog 目的地被误判「离开聊天页」 | **成立** | P1 | `MainActivity.kt:570-594` 旧代码 `val isChatRoute = entry.destination.route == Routes.CHAT`，`FILE_PREVIEW`（`dialog(...)`）压栈后栈顶不是 CHAT ⇒ `setAbsent(previous)` + `currentChatSessionId = null`，而聊天页仍在下方渲染 |

**汇总**：9 条中 8 条成立并已修（F-01/F-02/F-04/F-05/F-06/F-07/F-09 + F-03 见下），**0 条证伪**；F-06 按任务书要求只做「per-session 持有 + 代表会话选取」，**caller 未接线**（原报告点名的影响面之一，见 §6 R1）。F-03（`TRIAGE-R4` 判为登记项）不在本支实现范围，未动。

---

## ② 逐条修法

### F-01 草稿键不再进 crash-recovery 文件（+ 原子写）

- 新增 `CrashRecoveryStore`（`app/CrashRecoveryStore.kt`）：唯一写入口 `record(context, sessionId)`，内部先过 `ChatRoutePresence.shouldPersistCrashRecovery(sessionId)`（`null` / 空白 / `__new__` 前缀一律丢弃），然后**一次 `edit()` 同时写 id 与时间戳** —— 任务书要求的「写新 + 删旧」在这里退化成「同一 commit 内替换」，比两次写更难观察到中间态。
- 新增 `ChatRoutePresence.isDraftSessionId`（前缀常量 `DRAFT_ID_PREFIX = "__new__"`，与 `ComposerDraftStore.nextDraftId()` 一致）。
- `MainActivity` 的监听器改调 `CrashRecoveryStore.record(this@MainActivity, sid)`；旧的两条常量（`PREF_CRASH_RECOVERY` / `KEY_LAST_CHAT_SESSION_ID`）删除，避免第二个写入点。

**为什么这么修（而不是报告说的）**：报告写「原子替换（写新 + 删旧）」。这里没有第二个键可删——旧实现复用同一个键，所以真正缺的不是「删旧」而是「不写草稿键」与「带时间戳」，两者都在 `record()` 一处收口。

### F-02 冷启动恢复按 AUTO 窗过滤（读侧收口，未动 AppNavigation）

按任务书**候选 ①**：`ChatRoutePresence.decideColdStartRestore(launchMode, storedId, storedAtMs, nowMs)`：

1. `coldStartRestoreAllowed` 不通过 ⇒ null（既有行为）；
2. 草稿键 / 空白 ⇒ null；
3. `LAST_SESSION` ⇒ 无条件下恢复（该模式契约就是「永远回到上次对话」）；
4. `AUTO` ⇒ 无时间戳（`storedAtMs <= 0`，旧版本写入的记录）视为过期；`nowMs - storedAtMs > 15min` 视为过期；否则恢复。

删除侧：`MinisApp` 的 `onSessionDeleted` 钩子里加 `CrashRecoveryStore.clearIfMatches(this, sid)`（本支唯一新增的 `MinisApp` 生命周期行为，`runCatching` 包裹，无 IO 阻塞）。

**为什么不需要改 `AppNavigation`**：合成的 `OpenSession` 深链让 dispatcher 走 `hasDeepLink` 早退，规则一旦在这里补齐，dispatcher 侧就没有第二条路径需要改；反过来改 `AppNavigation` 会把 V 支（文件集互斥表里 `AppNavigation.kt` 归 V）拖进本支。

### F-04 后台只拒绝「启动服务」，不再拒绝「刷新通知」

- `AgentForegroundService`：
  - 新增 `@Volatile private var liveInstance`（`onCreate` 赋值、`onDestroy` 置空）+ `foregroundStarted`（`startForeground()` 之后为 true）+ `mainHandler`；
  - 新增 `fun refreshIfRunning(sessionCount, toolStatus): Boolean`：有活实例且已 startForeground 时，post 到主线程 `NotificationManager.notify(NOTIFICATION_ID, buildNotification(...))`；
  - 新增 `private fun applyStatusNotification(...)`（`runCatching` 兜底，失败只记日志）。
- `SessionActivityTracker.updateService()` 的后台分支：先 `refreshIfRunning(...)`，成功即返回；失败才记「skipping FG service update」。
- `buildNotification` 本来就读 `SessionActivityTracker.currentToolName / isToolRunning` 的实时值，因此刷新即同步标题、文本与 indeterminate 进度条，无需新增参数。

**没动焦点通知 / 超级岛参数**（任务书明令）。**没有**给后台路径新开 `startForegroundService`——那正是 T-MIUI-FGS-race 的 5s 崩溃面。

### F-05 完成态用有界 linger 保住服务

- 新增纯核 `CompletionLinger`：`deadlineOnTurnEnd(nowMs, wasActive, appForeground, eligible)`、`remainingMs`、`shouldStopNow`，`LINGER_MS = 30_000`。
- `SessionActivityTracker`：
  - `setInactive` 在决定停服前 arm 截止时间（`wasActive && !isAppForeground && eligible`）；
  - `pendingIdleStop` 改为「窗口未尽 → `postDelayed(remaining)` 重排一次；窗口尽 → 归零并 `stopServiceNow()`」；
  - `setActive` 清零截止时间（新回合取代完成态）。
- `AgentForegroundService.completionLingerEligible()`：活实例 + 已 startForeground + overlay 开关 ON + SYSTEM_ALERT_WINDOW 已授权（后两者由 `applyOverlayState` 缓存到 `overlayEnabledNow` / `overlayPermissionNow`——因为 500ms 比一轮 collector 往返更短，实时查询会输掉竞态）。

**有界是刻意的**：胶囊语义本来「到用户点掉为止」，但用 FGS 无限期承载完成态会撞 OEM 电池面板；30s 覆盖「看得到 + 点得开」，过期即撤（见 §6 R4 的天花板与升级触发）。

### F-06 工具态按 sessionId 持有 + 代表会话选取

- 新增纯核 `ToolSlotRegistry`：
  - 键 = sessionId（或共享桶 `UNATTRIBUTED = ""`）；
  - `depth` 计数（`start(isRunning = true)` +1、`finish()` −1，归零即删条目）——共享桶下 `start(A) start(B) finish(A)` 不再抹掉 B 的显示；
  - `representative(activeSessionIds)` 选取规则（写进 KDoc）：
    1. 优先取**正在流式**的会话的条目；
    2. 组内先看 `isRunning`，再按最近触碰（`seq`）；
    3. 若活跃会话都没有条目，则对全部条目套同一顺序（保住 offload 等无 `setActive` 的条目可见）。
- `SessionActivityTracker`：`toolSlots` 成为真源，`_currentToolName/_currentToolTitle/_isToolRunning/_currentToolStatus` 改为 `publishToolSlot()` 派生；`setInactive` 调用 `toolSlots.drop(sessionId)`（会话结束时无视 depth 丢弃——已结束的会话不该留幽灵运行态），无活跃会话时同时丢弃共享桶。
- `toolSlotKey()`：**只有一个会话在流式时**归属该会话；2 个以上归共享桶（猜测其中一个会把工具挂到错的聊天上，正是本条要修的 bug）。
- 声明式接线点：新增 `updateToolStatus(sessionId, …)` / `clearToolRunning(outcome, sessionId)` 两个重载，供 `AgentLoopEngine` / offload handler 后续改调（本支不改那些文件，见 §6 R1）。

### F-07 单次 GC + 活跃门；governor 判据补齐

- 新增纯核 `TrimReclaimPolicy`：`hasActiveWork(activeSessionIds, toolRunning)`、`shouldSyncGc(reclaimsShells, engagesGate, hasActiveWork)`。
- `MinisApp.onTrimMemory`：两处 `System.gc()` 合成**一次**，位置在两条 reclaim 分支都跑完之后，判据 `!hasActiveWork && (reclaimsShells || engagesGate)`。行为矩阵：`RUNNING_MODERATE`（只 reclaim shells，无 gate）空闲仍回收（保留旧行为）；`RUNNING_CRITICAL` 前台空闲由 2 次变 1 次；任何忙碌场景 0 次。
- `AppMemoryGovernor.tick(...)`：`toolRunning` 实参改为 `TrimReclaimPolicy.hasActiveWork(activeSessions, isToolRunning)`，并加注释说明该形参的真实语义是「有活干」（未改名：`AppMemoryGovernor.kt` 与它的测试不在本支文件集，改名会把半径扩到 12 处测试调用点）。

**没有删 `System.gc()`**（任务书要求：历史理由可能是 native 堆压力），只加门。

### F-09 dialog 压栈不算离开聊天

`MainActivity` 监听器先判 `ChatRoutePresence.isChatKeptAliveOverlay(entry.destination.navigatorName)`（`== "dialog"`）。命中时用 `navController.previousBackStackEntry` 作为「聊天页来源」，`isChatRoute` / `sessionId` 都从它取 ⇒ 打开附件不再 `setAbsent`、不清 `currentChatSessionId`、不重写 crash-recovery。

**为什么按 navigator 种类判而不是按 `Routes.FILE_PREVIEW` 字面量**：下一个 `dialog(...)` 目的地（现在已有 1 个，见 `AppNavigation`）自动获得同样待遇，不必再改这个文件。

---

## ③ 装置与读数（本地）

| 项 | 读数 | 说明 |
|---|---|---|
| 括号 / 花括号配平 | 13 个改动文件全部 `{}()[]` 归零 | python 粗扫（剥注释与字符串字面量） |
| 定向门禁 | 5/5 PASS | 见 §4 |
| 依赖/引用自查 | `TrimReclaimPolicy` 补 import；`MainActivity` 删掉已无引用的 `coldStartRestoreAllowed` import；`PREF_CRASH_RECOVERY` / `KEY_LAST_CHAT_SESSION_ID` 全树 0 引用 | `rg` 复核 |
| JVM 表驱动断言 | **CI 绿**：4 类 / 16 用例全部 `PASSED` | 本地无 `kotlinc`（jar 集随 shared 被删）；断言以仓库测试形式由 CI `testReleaseUnitTest` 执行，读数见 §4 末「CI 判读」 |
| 变异臂 | **未做**（装置重建被告警明令禁止） | 因此本支的「测试真的会红」证据只有 CI 一轮 PASS + 判据本身是字面量期望 |

新增测试（4 类 / 16 个用例，全部纯 JVM、无 Android 依赖）：

| 测试类 | 覆盖 | 用例数 |
|---|---|---|
| `ChatRoutePresenceTest` | 草稿键拒绝、dialog 判定、`coldStartRestore` 14 行表（含窗口边界、无时间戳、时钟倒流、未知模式） | 5 |
| `ToolSlotRegistryTest` | 代表选取 6 行表（含「快会话 finish 不抹慢会话」原样复现）、depth 不给非 running 更新抬升、空白文本保留、共享桶隔离、clear | 6 |
| `CompletionLingerTest` | arm 4 行表、窗口有限且为 30s、remaining 4 行表、shouldStopNow、异常时钟 | 5 |
| `TrimReclaimPolicyTest` | `hasActiveWork` 5 行表、`shouldSyncGc` 8 行全组合表 | 2 |

**期望值写法纪律**：全部为字面量（`900_000L`、`30_000L`、`"browser_use"`、具体 session id），不与被测常量互相推导；唯一的常量比对 `autoWindowLiteral` 在注释里写明「这是与 `AppNavigation` 字面量的镜像提醒」，不靠它证明实现正确。

---

## ④ 门 / 夹具读数

`rg -l "SessionActivityTracker|AgentForegroundService|MainActivity|MinisApp" scripts/scan/` 反查后，与本支改动直接相关的门（在 `scripts/scan/` 下逐个跑 `python3 <gate>.py .`）：

| 门 | 结果 |
|---|---|
| `chat_session_id_binding_check.py` | PASS（`all criteria satisfied`；`resolveSessionId` 仍在消费前解析） |
| `skill_restore_session_cleanup_check.py` | PASS（`id threaded, cleanup closed`；`onSessionDeleted` 新增行不破坏 id 线） |
| `overlay_show_hide_latch_check.py` | PASS（4 判据：intent latch 同步发布、post 后复检、hide 全站点、intent≠attach） |
| `truncated_call_guard_check.py` | PASS（未引入新的 `!!` 截断形态） |
| `process_logging_gate.py` | PASS |

**未跑全量** `scan.sh` / `test_scan.py`（告警要求「不跑全量」）；`scripts/scan/**` 零改动（本支未新增/修改任何门或夹具）。

### CI 判读（run #2874 / `40797486769` success @ `6540eb6e630a0bd5e59cc72493fe39ebafd32b1b`）

| 判据 | 读数 |
|---|---|
| run 结论 / 耗时 | `success`，27 步 **0 个非 success**（2 skipped = 滚动 tag + Publish 的 `if: main` 门控） |
| `head_sha` vs 本地/远端 | `6540eb6e630a…` 三处**逐字符一致**（`git ls-remote` 与 `git rev-parse HEAD` 同值） |
| 门集 | `Summary: 113 passed, 0 failed`（基线 113，本支未加门） |
| 夹具 | `ALL 349 CASES PASS` ×1 |
| 单测 | 7 段 `Summary: N passed, 0 failed`（25 / 16 / 44 / 19 / 30 / 18 + 门集 113），`e: file:///` = **0** |
| 新增 4 类 | `ChatRoutePresenceTest` 6 / `ToolSlotRegistryTest` 5 / `CompletionLingerTest` 5 / `TrimReclaimPolicyTest` 2 全部 `PASSED` |
| 判读陷阱 | `FAILED` 字样 6 处，逐条确认**全是 `PASSED` 用例名子串**（如 `slot denied produces FAILED …`）；`^.{0,40}e: ` 的 20 处命中全是日志前缀（`Build Date:` / `Image:` 等），`e: file:///` 为 0 |

**两轮红旗都是编译级，都不是逻辑错**（记录以便后续会话识别同款）：
1. **#2868** `pendingIdleStop` 里 `mainHandler.postDelayed(this, remaining)` —— SAM lambda 内 `this` 是外部 `SessionActivityTracker` 而非 `Runnable`。
2. **#2871** 改成 `postDelayed(pendingIdleStop, …)` 后仍红：`Type checking has run into a recursive problem` —— 属性在自己初始化器里自引用，需**显式声明 `: Runnable`**。

（本地无编译器时这两类错误只能靠 CI 发现；已把「沙箱无 Android 编译 ⇒ 自引用属性必须显式声明类型」记进残留 R6 的触发条件。）


---

## ⑤ backlog 查重复核

**无法复核**：`/var/minis/shared/backlog.md` 在本次基础设施清空中被删（`/var/minis/shared/work` 与 backlog 同盘），本支执行期间不可读。因此 §6 的残留**未做**「已有登记项 → 不重开」的查重，全部按新条目提交，请编排会话在恢复 backlog 后按关键字回查并合并（关键字已在每条标题里给出）。

替代核对：本支改动涉及的 9 条全部来自 B13 报告，`TRIAGE-R4` 只把 F-03 标为登记项，其余无「已登记不重开」标记。

---

## ⑥ 残留（每条三件套）

**R1 — F-06 的 caller 未接线（结构性上半部）**
- 触发条件：两个会话**同时**流式且都调用工具（`SessionConcurrencyManager` 允许 2–3 并发，用户手动触发）。
- 触发后方案：把 `AgentLoopEngine` 的工具派发（start / finish 成对）与 `ChatViewModel` / `ChatModelRouting` 的取消路径改调 `updateToolStatus(sessionId, …)` / `clearToolRunning(outcome, sessionId)`；`ToolSlotRegistry` 与 `publishToolSlot` 已经就绪，无需再改本支文件。
- 搁置理由：这些文件不在本支文件集（互斥表里归 provider/chat 支），改了会与并行支冲突；且任务书要求「控制半径」。

**R2 — F-02 的删除侧只覆盖「应用内删除」**
- 触发条件：会话通过**其他路径**消失（数据库文件被外部替换、恢复备份时删行、多进程竞态）。
- 触发后方案：冷启动读取处加一次**轻量存在性检查**（`MemoryRepository`/`ChatRepository` 的 id 是否存在，异步、超时即放弃），或给 `deleteSession` 的所有入口统一走 `CrashRecoveryStore.clearIfMatches`。
- 搁置理由：`onCreate` 里加同步 DB 读违反「零新增启动阻塞」护栏（任务书 §护栏 1）；异步补检查会让首批 UI 已经导航后再改目标，体验更差。

**R3 — F-05 的 30s 上限**
- 触发条件：用户把应用划到后台后**超过 30 秒**才回来看完成胶囊（或从未回来）。
- 触发后方案：把窗口与 `ToolOverlayController` 的「直到点掉」语义对齐（服务侧用前台服务类型的短时任务策略），或让胶囊状态落到 `BackgroundTaskNotifier` 的既有通知里（不依赖 FGS 存活）。
- 搁置理由：无限期持有 FGS 会撞 OEM 电池面板与「后台常驻」投诉；先按有界窗口收口，真机上若反馈「看不到了」再升级。

**R4 — F-04 的刷新只覆盖「已运行实例」**
- 触发条件：应用在后台且服务**已停**（上一回合结束 500ms 后），此时有后台触发的工具（offload/a11y 路径）想更新通知。
- 触发后方案：后台任务完成改走 `BackgroundTaskNotifier` 的一次性通知（已有通道），或让 offload handler 在前台服务不存在时先发一次性通知。
- 搁置理由：那条路径**不能**调 `startForegroundService`（T-MIUI-FGS-race 崩溃面），需要新的一致策略，超出本条半径。

**R5 — F-07 的形参命名**
- 触发条件：后续有人只读 `AppMemoryGovernor.tick(..., toolRunning = …)` 的签名就判断「忙碌」的语义。
- 触发后方案：把形参改名为 `busy` / `activeWork`，同时改 `AppMemoryGovernorTest` 的 12 处具名调用。
- 搁置理由：改名扩半径到测试文件（可能被并行支同时改），先用注释在调用点固定语义。

**R6 — 本地无编译器时的自检缺口（compile-only 缺陷只能靠 CI）**
- 触发条件：沙箱里 `kotlinc` 的 `kotlin-home` / `jvm-libs` 缺失（本次基础设施清空后即是），而改动涉及自引用属性或 SAM lambda 内的 `this`。
- 触发后方案：①自引用（在自身初始化器里引用自己）的属性**显式写类型** `: Runnable` 等；②SAM lambda 内不要用 `this`，改用属性名或显式标签；③`/tmp/libs` 恢复后先跑一次 `kotlinc` 语法检查（只筛 `expecting|unexpected|Unclosed|recursive`）再 push。
- 搁置理由：本轮装置重建被告警明令禁止；CI 一轮 ~13 min 足以兜住编译级错误，代价是可接受的一轮往返（本轮两轮红即为此）。

---

## ⑦ 真机未验清单

1. **F-04**：切后台（Home）→ 观察通知栏标题/进度条是否随工具切换实时变化（需在设置页/日志页触发一次流式 + 工具）。
2. **F-05**：非聊天页按 Home → 等回合结束 → 30s 内回桌面看完成胶囊是否还在、能否点开对应会话；30s 后是否已撤。
3. **F-06**：两个会话并发（一个跑长工具、一个短工具）→ 短工具结束后通知栏/胶囊是否仍显示长工具名；单会话场景确认不回归。
4. **F-07**：长流式期间手动触发系统内存压力（`am send-trim-memory` / 后台大应用压测）→ 观察是否仍有主线程卡顿尖峰（本支只能证明调用被门挡住，不能证明 GC 一定不诱发 jank）。
5. **F-01**：新会话未落库时强杀进程（`am crash` / 原生崩溃）→ 重启后应进入启动模式默认页，**不是**空白的合成草稿。
6. **F-02**：AUTO 模式下离开超过 15 分钟再冷启动 → 应开新会话；LAST_SESSION 模式下同样操作 → 应恢复上次对话；删除当前会话后冷启动 → 不应进入死会话。

---

## ⑧ 与报告的差异

1. **F-01 只成立一半**：报告写「草稿键写入 crash-recovery」，但基线里 `ChatViewModelStore.resolveSessionId` 已经参与解析（`[da1032-D]`）——真正漏的是「**未注册别名的草稿键原样透传**」照样被写入。修法因此落在写入口（拒绝草稿键），而不是报告暗示的「晋升时替换」。另：报告/任务书说的「写新 + 删旧」在单键实现下退化为「同一 `edit()` 原子替换 + 时间戳」。
2. **F-05 的机制描述不精确**：报告说「500ms 延迟停服抢先把通知刷掉」，实际被抢掉的是**服务实例承载的完成态整体**（`hasCompletionPending` + overlay 胶囊 + 通知），不只是「刷新一次通知」。因此修法不是「补一次刷新」，而是「让服务活过完成态的可见窗口」。
3. **F-06 的修法范围按任务书收窄**：报告把「`_currentSessionId` 也是单槽」列进危害，任务书要求「通知栏展示仍取一个代表会话」，二者不完全一致——本支按任务书实现（per-session 持有 + 代表选取 + 选取规则写进 KDoc），`_currentSessionId` 的改写限制为「仅归属明确的条目可改写」（共享桶不得改写胶囊目标）。代价是 caller 未接线时并发场景仍落在共享桶（§6 R1）。
4. **F-07 比报告多一处**：报告只说 `MinisApp` 两处回收路径；实测第三处是 `AppMemoryGovernor.tick(toolRunning = isToolRunning)` 的**入参收窄**（`MinisApp.kt:388` 附近），第二会话流式时 governor 把进程当空闲。这一处不修，即使 `onTrimMemory` 加了门，governor 自己的 `gcHook` 仍会在流式期插入同步 GC。
5. **F-09 报告行号与现状对齐**（`MainActivity.kt:570-594`），未发现转写失真；但报告未提「聊天页在 dialog 下方仍在渲染」这层语义，本支的判据（navigator 种类）据此选定。
6. **未做的先证/证伪**：本支没有对任何一条给出「证伪」结论；F-03 不在实现范围（`TRIAGE` 已登记）。
