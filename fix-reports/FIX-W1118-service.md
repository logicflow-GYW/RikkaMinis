# FIX-W1118-service · D7 + R2 + R4 修复报告

- **分支**：`fix/w1118-service`（worktree `/var/minis/shared/work/incr1118/wt5`）
- **基线**：`c85131ad`（= 当前 main，已含 FIX-1117B 五支）
- **代码提交**：`11353c1c`
- **覆盖**：`Wave_1118_D7`（F-01…F-05）· `Wave_1118_R2`（F-01…F-05）· `Wave_1118_R4`（F-01…F-04），共 **14 条**
- **allowlist 纪律**：只改 `service/**`、`scheduled/**`、`browser/**` + 对应测试。**R2 的五条全部位于 `sandbox/offload/**`（本支禁碰）⇒ 全部转跨域指针**（见 §4）。未改 `scripts/scan/**`、`provider/**`、`sandbox/**`、`agent/**`、`ui/**`、`diagnostics/**`、`crash/**`。

---

## 1. 判级表（本支全部 14 条）

判级用 GLOBAL「修复门」（P0 必修 / P1 排期修 / P2 登记 / P3 永不动），**不照抄报告 severity**。

| ID | 报告判级 | 实判 | 结论 | 去向 |
|---|---|---|---|---|
| **D7 F-01** `BrowserTabPool.evictIdleTabs` 无锁读写 `savedURLs`/`_tabs.value` | high | **P1** | 已修 | `11353c1c` |
| **D7 F-02** `setActive` 未复位 `_lastToolOutcome` | high | **P1** | 已修 | `11353c1c` |
| **D7 F-03** `dispose()` 后仍可建 WebView | high | **P1** | 已修 | `11353c1c` |
| **D7 F-04** `version` 两套契约（导入 1.5→1 放行） | P1/D5 | **P2** | 已修（顺路成本≈0，同一函数） | `11353c1c` |
| **D7 F-05** `finalizeLocked` 异常只清内存锁 | probable | **P2** | 已修（同上） | `11353c1c` |
| **R2 F-01** `stopSelf(startId)` 实例计数器分裂 | concrete | **P2（本支不可达）** | 未修 | 跨域指针（`sandbox/offload/**`） |
| **R2 F-02** 迟到 `client.ack` 不触发 `selfReap` | concrete | **P2（本支不可达）** | 未修 | 跨域指针 |
| **R2 F-03** 看门狗无锁 `selfReap` | concrete | **P2（本支不可达）** | 未修 | 跨域指针 |
| **R2 F-04** `queuedRequests.decrementAndGet` 未在 finally | concrete | **P2（本支不可达）** | 未修 | 跨域指针（任务书「本轮重点 2」与 allowlist 冲突，以 allowlist 为准） |
| **R2 F-05** 非流式 `cancel` 不被读取 | concrete | **P2（本支不可达）** | 未修 | 跨域指针 |
| **R4 F-01** `_suspendedSessions` 永久残留 | high | **P1** | 已修 | `11353c1c` |
| **R4 F-02** 日志 `cap` 与 `controller.maxConcurrent` 口径分裂 | medium | **P2** | 已修（日志口径一行，同一函数） | `11353c1c` |
| **R4 F-03** `isAppForeground` 缺 `@Volatile` | medium | **P2** | 已修（一行注解） | `11353c1c` |
| **R4 F-04** 内存准入缺 EFFECT 回读（L2 开环） | low | **P2** | 未修（任务书指定「只登记」） | §3 三件套 |

**判 P1 的三条「谁、在什么操作下会遇到」**

- **D7 F-01**：多标签 + 内存告警（或进后台触发 trim → 内存钩子）时，`MinisApp` 的三个钩子在非 Main 线程调 `evictIdleTabs()`，同时 Agent 工具协程在 IO 线程 `saveState()` → 遍历 `LinkedHashMap` 可抛 CME，被 `saveState` 的 catch 吞掉 ⇒ **标签状态文件静默不写**（会话重开时标签位置丢失）；`_tabs.value` 读到旧快照再写回 ⇒ 新开标签从列表消失但 WebView 仍活（泄漏且关不掉）。
- **D7 F-02**：上一轮失败（工具错或流错）→ 用户不点掉胶囊直接发下一轮纯文本问答 → 切后台 → 完成胶囊显示**红 ✗ / Failed**。common path（每次「失败后接着问」都命中）。
- **D7 F-03**：3 个标签全被占用时 Agent 发第 4 个 tab-less 请求（进入 250ms×80 轮询等待）→ 用户删除/关闭该会话（`ChatViewModel.onCleared()` → `dispose()`）→ 轮询醒来 `createTab` 建新 WebView ⇒ **50–100 MB 渲染进程永久泄漏**（`dispose()` 是一次性终结方法，之后无人再销毁）。
- **R4 F-01**：并发槽位占满（cap=2）时同一会话连点两次发送 ⇒ 两个 run 排队 ⇒ 释放后 `_suspendedSessions` 残留 `["s1"]` ⇒ **该会话永远显示「等待中」**，`isSuspended()` 恒 true，`releaseSlot` 清不掉。

R2 五条为什么不判 P0：全部需要 `:modelservice` 进程内的并发/拒绝/超时组合，且本支按 allowlist 不得改动；它们的影响面（误杀在飞请求、进程不回收、假崩溃失败）在 `sandbox/offload` 域内判定，交主会话分派。

---

## 2. 修的逐条证据

本地 JVM 装置：`/var/minis/shared/work/incr1118/t5-jvm/`（`run.sh` 并发域 / `run-scheduled.sh` 调度域 / 形状域用 junit 直跑），stub 仅 `android.util.Log`、`android.content.{Context,SharedPreferences}`、`AppLogger`、`ScheduledTaskRunner.onTasksRemoved`（**生产逻辑文件全部用真文件**）。

### 2.1 R4 F-01 `_suspendedSessions` 残留（P1）

- **锁/不变量**：`_suspendedSessions` 的全部写入都在 `synchronized(this)` 内（`acquireSlot` 的 Queued 分支、`promoteLocked`、`cancelWaiter`、`cleanupIfStillWaiting`、`dropSuspended`）；不变量 = **`suspendedSessions` 是 sessionId 的集合视图**：id 在其中 ⇔ 该 session 至少有一个 `WAITING` run。
- **修法**：入队 `if (sessionId !in _suspendedSessions.value)` 去重；三处移除改走新 `dropSuspended()`（`filter { it != sessionId }`，删**全部**副本）。
- **先证（基线红）**：`SuspendedSessionsResidueTest` 在 `c85131ad` 树上 **3/3 红**：
  - `one session queued twice is still ONE suspended line expected:<[s1]> but was:<[s1, s1]>`
  - `cancellingEveryQueuedRunOfOneSessionClearsTheSuspendedLine` 同因
  - `mixedPromoteAndCancelOfTwoRunsOfOneSessionConvergesToEmpty`：`promoted run + cancelled run ⇒ nothing queued ⇒ not suspended` 断言失败
- **修后**：`OK (3 tests)`；同批跑仓库既有 `SessionConcurrencyManagerTest`（17 条）**20/20 绿**。
- **变异双向**：
  - m1 只回退入队去重 → 2 条臂红（`[s1, s1]`），精确对应；
  - m2 只把 `dropSuspended` 回退成 `- sessionId` → **绿**（如实记为**层叠冗余**：入队去重后列表不可能有重复项，故移除侧单独回退不复活缺陷；两侧都做是为了「任一侧回归都不复活」）。
  - 反向组合（去重 + 全量移除同时回退 = 基线）→ 3 红，见上。

### 2.2 R4 F-02 日志 `cap` 口径（P2，顺路）

- `queue` / `promote` 两处日志的 `cap=` 改用 `controller.maxConcurrent`（实际门控容量），不再读运行期可变的 `ConcurrencyPrefs` getter。
- **不修的部分**：`ConcurrencyPrefs` 的 KDoc 明写「Takes effect on the next process start（lazy controller / semaphores are sized at init, deliberately not resized at runtime）」⇒ 「改设置要重启才生效」是**刻意设计（P3）**，本支不改 slot 重建语义。
- **测试**：无独立测试（纯日志字符串；按「装置比例」纪律，不给 P2 日志口径建装置）。改动是同一函数内一行，随 2.1 的行为测试一起被编译/执行覆盖。

### 2.3 R4 F-03 `isAppForeground` 可见性（P2）

- `@Volatile private var isAppForeground`；写方 = Main 生命周期回调，读方 = 流结束所在线程（`setInactive → CompletionLinger.deadlineOnTurnEnd`）。
- **测试**：形状臂（见 2.9 `TrackerRunBoundaryOutcomeTest`：`isAppForeground is volatile` + 负控）。JMM 竞态无法在单线程 JUnit 里确定性复现，如实说明：这是**注解级修复**，证据是声明形状 + happens-before 推理，不是复现。

### 2.4 D7 F-02 `setActive` 复位 outcome（P1）

- 在清空 `_lastReplyExcerpt/_lastToolName/_lastToolTitle/_lastToolStatus` 的同一处补 `_lastToolOutcome.value = ToolOutcome.Unknown`。
- **为什么是它**：`setInactive` 只在 `if (wasError)` 写 outcome、`applyToolUpdate` 只在 `isRunning` 时写 ⇒ **纯文本轮次没有任何写入点**，胶囊读 `state.lastOutcome` 必然消费上一轮残值。
- **先证**：`TrackerRunBoundaryOutcomeTest` 在基线树 **4/4 红**（`setActive must reset _lastToolOutcome`、`isAppForeground is volatile`、两条负控因变异目标不存在而红）；修后 **4/4 绿**。
- **变异**：负控臂把 `setActive` body 里的那行删掉 → `resetsOutcome` 判定必须为 false（绿=通过）。

### 2.5 D7 F-04 `version` 单契约（P2）

- `importDocument` 改用与 `loadLocked` 同一个 `declaredVersionOf(document)`（`private` → `internal`，`INVALID_DECLARED_VERSION` 同步 `internal`），并对非法值给专门提示（不再让 plan 打印 "format v-1"）。
- **先证（机制）**：`JSONObject("""{"version":1.5}""").optInt("version", -1)` = `1` —— 即旧代码把 1.5 当成本 build 的 v1 放行；而同一份字节随后被 `loadLocked` 判为 unreadable ⇒ 「导入接受，读回拒收」。
- **测试**：`ScheduledTasksVersionContractTest` **6 条**：
  - 行为 3 条（1.5 / 1.0001 → INVALID；0 / -3 / "v2" / null / true → INVALID；1 / 2 / 2.0 → 声明值、缺失键 → null）；
  - 接线 1 条（`importDocument` 走 `declaredVersionOf(document)` 且不再出现 `optInt("version"`，**先剥注释**——本修复的注释本身就引用了旧的 `optInt`，不剥注释会假红）；
  - **负控 1 条**：把真实源码改回 `optInt` 版本 ⇒ 同一断言必须判红（实证通过）。
- **修后**：`OK (11 tests)`（含 F-05 的 5 条）。

### 2.6 D7 F-05 崩溃标记补偿（P2）

- 新增纯函数 `ScheduledTaskTransitions.clearRunningMarker(file, taskId)`（不命中即返回同一实例，调用方可判「无事可做」）+ store 包装 `ScheduledTasksStore.clearRunningMarker(context, taskId)`（`unreadable` 时拒绝，遵循所有其它读改写路径）。
- `finalizeLocked` 的 catch 在置 `runningTaskId = null` **之前**调用补偿；补偿自身失败只记 warning；**不吞 `CancellationException`**。
- **锁序（写清）**：`checkMutex`（调用方已持）→ store `lock`，与 `completeRun` 同向；`clearRunningMarker` 只取 store 互斥量，不与 `checkMutex` 反向嵌套 ⇒ 与 `onTasksRemoved` 无死锁可能。
- **为什么清标记而不是保留**：保留 ⇒ 下一次节拍走 `crashFailure` ⇒ `failStreak +1`（3 次熔断**禁用用户任务**）；清标记 ⇒ 只丢这一次的 `lastRunDate` 记账（与「失败的 run 不推进 lastRunDate、窗口内可重试」同形），可恢复。
- **测试**：`ScheduledTaskCrashMarkerTest` **5 条**：命中→标记清空且 `failStreak` 不动；他任务标记→返回同一实例；无标记→同一实例；**成对臂**（不清 ⇒ `crashFailure` 记 1 次失败 = 缺陷本体；清后 ⇒ `crashFailure` 无操作且 streak 仍 0）；接线 1 条 + **负控 1 条**（把 `clearRunningMarker(app, taskId)` 换成 `Unit` ⇒ 断言判红）。
- **修后**：与既有 `ScheduledTaskPolicyTest`/`ScheduledTasksCodecTest`/`ScheduledTasksRestorePlanTest`/`ScheduledTaskStorePolicyTest` 同批 **66/66 绿**。

### 2.7 D7 F-01 BrowserTabPool 线程（P1）

- `savedURLs`：`mutableMapOf`（LinkedHashMap）→ `ConcurrentHashMap`。理由写进字段 KDoc：三线程访问（内存钩子写、`saveState` 在 IO 线程遍历、`createTab`/`destroyTab` 在 Main），键序无语义（落盘是 JSON 对象，`mergedTabUrls` 复制传入顺序）。
- `evictIdleTabs()`：整体切到 Main（`Looper.myLooper() != Looper.getMainLooper()` → `Handler(Looper.getMainLooper()).post { evictIdleTabs() }` 并 return），并加 `if (isDisposed) return`。**锁序说明**：本类无 Mutex 保护 tab 列表；口径是「tab 列表的所有写者都在 Main」。改前只有 `manager.destroy()` 被 hop，列表读写留在调用方线程 ⇒ 与 Main 上的 `createTab`/`closeTab` 交错。
- `execute()` 的 follow-selected 分支：`val followTabs = _tabs.value` 一次快照，后续判定/取 max 都用它（旧写法三次读，中间被清空则 `!!` NPE）。
- **先证**：形状臂在基线树 10/10 红（含本条 3 条：`savedURLs must be thread-safe`、`evictIdleTabs must hop to Main`、`the snapshot must exist`）；修后 10/10 绿。CME / 丢更新本身是竞态，无法在无 WebView 的 JVM 上复现 ⇒ 证据是**结构（容器类型 + 单线程写口）+ 负控**，如实标注。

### 2.8 D7 F-03 dispose 后不再建 WebView（P1）

- `createTab`：`if (isDisposed) return null` 置于**建 `WebView` 之前**；
- `acquireTab`：入口 + 250ms 轮询循环内各一条 `if (isDisposed) return@withContext null`（共 2 处，形状臂按字面计数）；
- `handleNewWindow`：`if (isDisposed) return`（该路径自建 WebView，不经 `createTab`）；
- `onTrimMemory`：`if (isDisposed) return`；
- `ensureTabForUI(): Tab` → `Tab?`（`createTab` 现在可为 null；原 `!!` 是唯一假设非空的调用点）。**全部调用点**（`ui/browser/BrowserSheet.kt:131`、`ui/chat/ChatViewModelUiStateExt.kt:20,37`）都是语句位置、忽略返回值 ⇒ 类型放宽不影响 `ui/**`（未改 ui 文件）。

### 2.9 形状测试的两个文件

- `TrackerRunBoundaryOutcomeTest`（4 条）：F-02 与 R4 F-03 的接线 + 各自负控。
- `BrowserTabPoolDisposeLatchTest`（10 条）：F-01 三条（容器、Main hop、单快照）+ F-03 三条（createTab / acquireTab 双闸 / handleNewWindow）+ **五条负控**（逐个改回并重跑同一判定）。

两文件在**基线树**上 `Tests run: 14, Failures: 14`（9 条正向 + 5 条负控，负控在基线红是因为其变异目标尚不存在，属预期），修后 `OK (14 tests)`。

### 2.10 既有门锚点适配（本支触发的两条）

首轮 CI（run `44022836418` / #3216 @ `11353c1c`）在 `unit-tests` 抓到**两条既有门**因我的改动翻红 —— 不是产品缺陷，是门的锚点问题，逐条说明处置：

| 门 | 失败信息 | 原因 | 处置 |
|---|---|---|---|
| `ScheduledTaskDeleteReleaseShapeTest > declaredVersion_treatsNonPositiveAndNonNumericAsUnusable` | `private fun declaredVersionOf( not found` | 我把 `declaredVersionOf` 由 `private` 提为 `internal`（让 import 路径共用同一契约） | **改门锚**：锚点改为 `fun declaredVersionOf(`（**可见性不是决策**，修饰符一变门就失明）。断言内容（`INVALID_DECLARED_VERSION` / `value <= 0` / 缺失键返回 null）**一字未改**，同一批臂仍覆盖同一决策；另把该门里「导入侧读 `optInt("version",-1)`」的过时说明文字更新为「两侧共用 `declaredVersionOf`，由 `ScheduledTasksVersionContractTest` 钉住」 |
| `BrowserTeardownAndFailureSignalTest > tab teardown routes the manager destroy through the main thread` | `every manager.destroy() call in the pool must sit inside the helper (found 2 of 3)` | 我在 `evictIdleTabs` 的 KDoc 里写了 `tab.manager.destroy()` 这个字面量，门的**全文件计数**把它算成第 3 处 | **改注释，不改门**：门是对的（它要证明所有 manager teardown 都经 `destroyManagerOnMain`）。改门需「三件齐」，而这里成本更低且不削弱判据：注释改述为「only the manager teardown was hopped to Main」。改后全文件 `manager.destroy()` 计数回到 2（两处都在 helper 内） |

复跑（`user.dir` = `src/android/app`，与 CI 同）：`ScheduledTaskDeleteReleaseShapeTest` **9/9 绿**、`BrowserTabPoolDisposeLatchTest` **10/10 绿**。

可复用：**别在全文件计数的门旁边写被判计数的字面量**（注释也算）；把私有函数提可见性时，锚 `private fun xxx(` 的形状门必然失明 —— 锚要落在「决策」上，不落在修饰符上。

### 2.11 本支本地证据汇总

| 装置 | 基线 | 修后 |
|---|---|---|
| 并发域（新 3 + 既有 17） | 新臂 3/3 红 | **20/20 绿** |
| 调度域（新 11 + 既有 55） | 新臂机制+负控已证 | **66/66 绿** |
| 形状域（tracker 4 + browser 10） | **14/14 红** | **14/14 绿** |
| 变异 | — | m1（去重回退）2 红；m2（移除侧回退）绿=冗余层；负控 7 条全部按预期红 |

**未跑**：全量 `scan.sh` / `test_scan.py` / gradle（按本支纪律；夹具与全量由云端 CI 提供）。

---

## 3. 未修项的三件套草案

### R4 F-04 · 内存压力准入缺 EFFECT 回读（P2，任务书指定只登记）

- **触发条件**：`MemoryPressureGate.metricsReader()` 在 `reclaimAndWait()` / `delay(500)` 后仍报 CRITICAL（回收无效果），且此时有新会话要进槽。当前行为：无条件入槽（设计上「不拒绝准入，只争取时间」）。
- **方案**：回收后回读一次 `metricsReader()`，仍 CRITICAL 则再 `delay(500)` 一次（**不拒绝**，只多给一拍），并把回读值打进 `[memory-pressure]` 日志。改动点在 `SessionConcurrencyManager.acquireSlot` 的准入块，约 6 行 + 一条纯判定 `recheckStillCritical(levelAfterReclaim)`。
- **搁置理由**：L2 开环的「EFFECT 缺失」本身不影响正确性（准入从不拒绝），加回读会把一次准入变成 2–3 次 `/proc/self/status` 读取；需要先有「回收后仍 CRITICAL 的真实频率」证据（当前无生产日志支撑）。按「装置比例」不为此建 JVM 臂。

### D7 F-05 的同族残留（登记，本支不修）

- **症状**：`completeRun` 的 `writeLocked` **返回 false（不抛异常）**时，磁盘仍是「旧任务态 + running 标记」，下一次节拍同样会误记一次 `process-restart` 失败。本支修的是**异常**路径，没修「写失败但没抛」这条静默路径。
- **触发条件**：结算瞬间磁盘满 / IO 错误（`writeLocked` 的原子写失败）。
- **方案**：`completeRun` 返回 `Result` 时附带 `persisted: Boolean`（复用现成的 `mutateReporting` 形状），runner 在 `persisted == false` 时调用同一个 `clearRunningMarker`。
- **搁置理由**：改 `completeRun` 的返回类型会波及 `crashFailure` 与 `finalizeLocked` 的日志分支（`res.task?.failStreak`），顺路成本不≈0；本支已覆盖「异常」这一半，剩下的一半等真机出现 `mutation not persisted` 日志再升级。

### D7 F-05 顺带发现（登记，本支不改）

- `finalizeLocked` 的 `catch (t: Throwable)` 会**吞掉 `CancellationException`**（结算被取消时按「结算失败」处理并 return）。属既有行为、非本支引入；本支只保证**新加的补偿路径**不吞 CE。
- **触发条件**：`finalizeRun` 所在 scope 在结算期间被取消。
- **方案**：`catch (t: Throwable)` 首行 `if (t is CancellationException) throw t`。
- **搁置理由**：改动会让取消沿调用链上抛，需逐条核对 `onSessionSettled` 的所有调用方作用域语义（`scheduled/**` 之外还有 `MinisApp` 转发点），超出本支半径。

---

## 4. 跨域指针（本支禁碰，交主会话分派）

全部 `sandbox/offload/**`（**任务书「本轮重点 2/3」与 allowlist 冲突，以 allowlist 为准**）：

| ID | 文件 | 一句话 | 建议 |
|---|---|---|---|
| R2 F-01 | `sandbox/offload/ModelExecutionService.kt` | `stopSelf(startId)` 销毁实例但进程内线程仍在跑 → 实例计数器分裂，旧实例收尾 `killProcess` 误杀新请求 | P1 候选（误杀在飞请求 = 核心功能不可用），需 owner 锁复核 `activeRequests` 提升到 companion |
| R2 F-02 | 同上 `scheduleControlledDrain` | 迟到 `client.ack` 只释放 token 就 return，无人再 `selfReap()` → 进程常驻 | P1 候选（内存常驻，可达） |
| R2 F-03 | 同上 `abortOnStaleKeyCache` | 看门狗不持 `lifecycleLock`、只查 `activeRequests == 0` 就 `killProcess` | P1 候选（可抢在 `terminal.json` 前杀进程） |
| R2 F-04 | 同上 `queuedRequests` | `decrementAndGet()` 不在外层 `finally`，等槽异常即永久泄漏计数 → 后续请求排队不动 | P1 候选（**任务书本轮重点 2**） |
| R2 F-05 | `sandbox/offload/ModelExecutionDispatcher.kt` / `executeRun` | 非流式超时写 `cancel` 但 worker 侧从不读 → 无法中止、`cancel.ack` 永不出 | P2（成本是槽位与后端计费，非数据损坏） |

另：D7 报告 §4「未确认清单」四条（`hasCompletionPending` 跨轮次残留、`onDownloadEvent` 线程、`onSessionSettled` 覆盖度、`ConfigBackup` Stage 9 临时目录失败归因）证据不足，本支**不改也不登记**，交下一轮审计取证。

---

## 5. 收口

- 本地窄验证：见 §2.11（并发 20/20 · 调度 66/66 · 形状 14/14）。
- **分支 CI：`#3222` / run `44025135204` @ `d0e1fa6cd810755d71e29ce8fe6e4a001b862ef3`（= 本地 tip 逐字符一致）五 job 全 success**：
  - `unit-tests`：**9387 PASSED / 0 真失败**（日志里 4 条含 ` FAILED` 的行全是**测试名子串**：`slot denied produces FAILED without calling provider PASSED` 等；1 条 SKIPPED = 既有 `ComposerLiveSourceTest` 负控）。Gradle 只在失败时打印 `N tests completed, M failed`，本次日志无该行 + `BUILD SUCCESSFUL` 即「0 失败」的证据形态。
  - 本支 5 个新测试类的 **28 条用例逐条 PASSED**（`BrowserTabPoolDisposeLatchTest` 10 / `ScheduledTaskCrashMarkerTest` 6 / `ScheduledTasksVersionContractTest` 5 / `SuspendedSessionsResidueTest` 3 / `TrackerRunBoundaryOutcomeTest` 4；按类名 grep `…Test > …PASSED` 实测计数 = 28）。
  - `scan-gate`：`Summary: 115 passed, 0 failed` + 运行时 `##[notice] scanners moved in this push (or the diff was unreadable) — running the fixture self-test` + **`✅ ALL 354 CASES PASS`** ⇒ 这次**夹具是真跑的**（与「CI 绿 ≠ 夹具跑过」的旧口径区分：本条引的是运行时 notice 与夹具汇总行，不是脚本源码里的 echo）。
  - `publish`：job success 但两个真实发布步 **skipped**（`Move the rolling tag android-latest` / `Publish to Releases`）= main 门控生效，分支未污染 release 资产。
- **停止节点已到达**：分支 CI 绿 + head_sha 逐字符一致 ⇒ 本支结束，**不合 main**（并集合并由主会话执行）。
- **报告与代码 sha 的关系（如实说明一处偏差）**：本文件的**初稿**在 amend 时随代码提交一起进了 `d0e1fa6c`（`git add -A` 把它带上了）。因此：**CI 验证的 sha `d0e1fa6c` 里已含本报告初稿**，其后的报告提交只补了本节的 CI 实测数字与 §2.10 —— `git diff --name-only d0e1fa6c HEAD` = `fix-reports/FIX-W1118-service.md`（**非报告差异为空**，唯一满足的是「代码面」不受影响这一点，而不是「报告完全独立成提交」的字面形态）。下一批起：写报告前先 `git commit` 代码，避免 `git add -A` 把草稿带进代码提交。
