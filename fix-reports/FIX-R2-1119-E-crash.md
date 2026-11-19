# FIX-R2-1119-E — 崩溃族 2 条（R2 F-10 / F-11）

## 1 身份链

| 项 | 值 |
|---|---|
| 任务书 | `/var/minis/shared/work/meta-audit/fullscan1119r2/TASK-E-crash.md`（覆盖 R2 F-10 / R2 F-11） |
| 基线 main | `ecb03d92`（worktree `git rev-parse HEAD` 实测一致，零漂移） |
| worktree / 分支 | `/var/minis/shared/work/r3d1119r2/crash` / `fix/crash-loop-1119` |
| 改动文件 | 生产 2：`service/AgentForegroundService.kt`、`MainActivity.kt`；测试 2（新增）：`service/ForegroundStartPolicyTest.kt`、`DialogShowGateTest.kt` |
| 改动量 | 见 §5 的实际 `git diff --stat`（本报告随代码同批提交，数字以提交为准） |
| 停止节点 | **CI 绿 + head_sha 三方逐字符一致 = 停止节点，未合 main** |

## 2 缺陷与先证

### 2.1 R2 F-10（P0，R=A 日常 × L=Y 崩溃循环）—— **成立**

**报告定位与实树核对**：报告点 `AgentForegroundService.kt:325`。基线树实测：`startForeground` 在 `onStartCommand` 内位于 `:326`（Q+ 分支）与 `:332`（legacy 分支），方法尾部 `return START_STICKY` 在 `:338`，`startService()` 的 `context.startForegroundService(intent)` 在 `:71`，`onTaskRemoved` 的 `startForeground` 在 `:380`/`:386`。**行号 1 行漂移（`:325` vs `:326`），机制与结论无失真**。

**触发链逐环闭合**（读码确认，非转述）：

1. `build.gradle.kts:64` `targetSdk = 35`；`AndroidManifest.xml:315` 声明 `.service.AgentForegroundService`，`foregroundServiceType` 走 `mediaPlayback`。
2. `onStartCommand` 正常路径**无条件**调 `startForeground(...)`，无 try/catch，随后 `foregroundStarted = true`，`return START_STICKY`。
3. LMK 在长 agent 回合期间回收进程（**这是日常路径**，不是边界条件）→ 系统按 `START_STICKY` 在**后台**重建服务，`onStartCommand(intent = null, …)`。
4. `intent == null` 原代码不早退：`sessionCount = 0`、`toolStatus = "Idle"`，仍走完整 attach。Android 12+（API 31+）在应用处于后台时对 `startForeground()` 抛 `ForegroundServiceStartNotAllowedException`（`extends ServiceStartNotAllowedException extends IllegalStateException`）。
5. 未捕获 → 进程死亡 → 系统**再次**按 sticky 重建 → 回到第 3 步 = 崩溃循环。每轮还会喂 `CrashFrequencyDetector`，用户下次冷启直接进安全模式。
6. 同族另两处：`startService()`（`:71`）的 `startForegroundService` 同样会抛（`SessionActivityTracker` 的 `isAppForeground` 检查与调用不是原子的，存在竞态窗口）；`onTaskRemoved`（`:380`）的 `startForeground` 在任务被移除、窗口刚消失时同样无保护。

**为什么不能「catch 掉就完事」**：本服务守护的是**正在跑的 agent 回合**（状态行 + wakelock + 悬浮胶囊），不是常驻通知。进程被 LMK 杀死后 `SessionActivityTracker` 的内存集合按构造必为空 ⇒ sticky 重建**没有任何东西可显示**，此时强行 attach 是纯成本。因此"该做的事仍做"的正确边界是：**显式 app 启动照常起前台；系统重建只有在「用户在看 + 本进程仍有存活回合」时才 attach**（后者对应 OEM 只杀服务不杀进程的场景，那时行仍有意义）。

### 2.2 R2 F-11（P0，R=B 常见 × L=Y 崩溃）—— **成立**

**报告定位与实树核对**：`MainActivity.kt:392` 与 `:439`，两处**逐行吻合，零漂移**。改前原文各是一段 `lifecycleScope.launch { … .filterNotNull().collect { … .show() } }`：
- `:392-426` `OffloadPermissionManager.pendingSettingsGate`；
- `:439-486` `AccessibilityRecoveryManager.pendingPrompt`。

**触发链**：`lifecycleScope` 只在 `ON_DESTROY` 取消，而窗口 token 可能在 `ON_DESTROY` **之前**就已失效（finishing / 已在销毁路径上 / 旋转窗口期）。此时 `show()` 在无效 token 上被调用 → 主线程抛 `WindowManager.BadTokenException` → 进程死亡；同时等待中的 agent 回合卡到弹窗超时。

**「别静默丢」的落地方式**：两条 pending 都是 `MutableStateFlow`（`OffloadPermissionManager.kt:397-398`、`AccessibilityRecoveryManager.kt:104-105`），值一直保留到被应答或超时（`SETTINGS_GATE_TIMEOUT_MS = 120s`、`PROMPT_TIMEOUT_MS = 60s`）。因此改用 `repeatOnLifecycle(STARTED)` 后，**不能弹就不消费**——值留在流里，用户回到前台时 Collection 重新订阅即拿到它，提示自动重放。这既是"排队到下次前台"，也不需要新增通知通道。

**同族普查**（任务书 §3 要求登记、不改）：`MainActivity` 内其余 `lifecycleScope.launch`：
- `:514`（改前行号）`SessionActivityTracker.activeSessions.collect { applyKeepScreenAwakeFlag(...) }` —— 只读写窗口 flag、不弹窗，**不是本缺陷同构点**；未改。
- `:668` 附近 `DisposableEffect { lifecycleScope.launch { navController.currentBackStackEntryFlow.collect … } }` —— 有 `DisposableEffect` 自身的 `onDispose` 取消，且不弹窗；未改。
- `:215` 的 `.show()` 是 `Toast.show()`，非窗口 dialog；未改。

## 3 修法

### 3.1 F-10

新增纯决策核 `ForegroundStartPolicy`（与 `StreamErrorFlags` 同住 `SessionStateTracker.kt` 尾部的 house pattern，此处住 `AgentForegroundService.kt` 尾部——它只对本服务有意义，且 `Service` 无法在 JVM 里实例化）：

```
origin(intentPresent)                 -> APP_START | SYSTEM_RESTART
decide(origin, appForeground, hasLiveWork):
    APP_START                          -> ATTACH_STATUS
    SYSTEM_RESTART && fg && liveWork   -> ATTACH_STATUS
    else                               -> STUB_THEN_STOP
```

调用点：
1. `onStartCommand`：先跑既有的 safe-mode / init-skip / db-guidance 早退（不动，FGS-DEADLINE-1116 已覆盖），再按策略决定；`STUB_THEN_STOP` 走既有 `satisfyForegroundDeadlineThenStop()`（同一条满足 5 s 期限的 stub 出口，不新增第二条），`START_NOT_STICKY` 返回。
2. 真 attach 走新私有 `attachForegroundOrGiveUp(notification): Boolean`：`try { startForeground(...) } catch (t: Throwable) { Log.w(...); stopSelf(); false }`。调用点在**两个**地方：`onStartCommand` 与 `onTaskRemoved`（后者失败时同样走共享出口）。
3. 末尾返回改 `START_NOT_STICKY`：**sticky 是循环的燃料**。应用侧需要服务时自己会起（`SessionActivityTracker.startServiceIfNeeded`，前台路径），系统重启并不能替我们保留任何东西。
4. `startService()`（companion）包 try/catch：`startForegroundService` 在不允许时抛，吞掉并打日志，服务保持关闭，下一次前台转换会自动重建。

**为什么降级不是"删功能"**：`ATTACH_STATUS` 覆盖全部 app 启动与「前台 + 有存活回合」的重建；只有「后台重建」与「无东西可显示」这两类才 unwind——这两类原本的结果是**进程崩溃**，不是服务继续工作。

### 3.2 F-11

新增纯核 `DialogShowGate.shouldShow(isFinishing, isDestroyed, startedOrBeyond)`（住 `MainActivity.kt` 尾部，纯布尔入参、无 Android 类型，JVM 可直接测）。

**两处都改**（任务书硬约束 4）：
- `:392` settings gate 与 `:439` a11y prompt 均改为 `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { … } }`；
- collect 体首行先过 `DialogShowGate.shouldShow(...)`，不过则**只打日志 + `return@collect`**（不 respond、不消费，提示留在 StateFlow）；
- 弹窗改为 `.create()` 后显式 `dialog.show()` 并包 `try/catch (Throwable)`（`shouldShow` 与 `show()` 之间仍有 token 竞态窗口，这一层是最后防线）；
- 新增字段 `activePermissionDialog`，`onDestroy` 里 `dismiss()` 并清空引用，消掉 `WindowLeaked` 这条次生路径。

## 4 证据

### 4.1 本地窄验证（任务书允许的形态：无 gradle / 无全量 scan）

装置：`/var/minis/shared/work/r3d1119r2/crash-jvm/`（`extract.py` / `run.sh` / `run_existing.sh` / `mutate.py` / `RunTests.java`）。

- `extract.py` 从**两个生产文件里逐字切出**纯核（`internal object ForegroundStartPolicy` / `internal object DialogShowGate`）编译——跑的是出厂代码，不是会漂移的副本。
- `run.sh`：**26 passed, 0 failed**（新增两测试类全绿）。
- `run_existing.sh`（本次未改但会读这两个文件的既有形状探针）：`FgsDeadlineWiringTest` / `AppInitSkipGuardWiringTest` / `LaunchSessionRerouteShapeTest` / `MainActivitySessionIdResolutionTest` **28 passed, 0 failed**；`ShareHostPolicyTest` **10 passed, 0 failed**（两条 cwd 约定不同，分两段跑）。
- 编译失败即 `exit 2`（`kotlinc | tee` 在 ash 下退出码来自 tee，会静默跑旧 classes）。

**两面覆盖**（任务书硬约束 3）：

| 条目 | 「不崩」侧 | 「该做的事仍做」侧 |
|---|---|---|
| F-10 | `a background restart with no live work unwinds instead of attaching` / `… with a surviving turn still unwinds` / `a foreground restart with nothing left to show unwinds`；接线臂断言 `attachForegroundOrGiveUp` 内含 `catch (t: Throwable)` + `Log.w` + `stopSelf` | `an app start always attaches - the user asked for it`（4 组合全 ATTACH）/ `a foreground restart with a live turn keeps its row`；接线臂断言正常路径仍 `buildNotification(...)` 且以 `START_NOT_STICKY` 收尾 |
| F-11 | `a finishing activity never shows a dialog` / `a destroyed …` / `a stopped …` / `every single failure is enough to refuse`（真值表只有 1 行可弹）；接线臂断言两处 collect 均有 `return@collect` 且**不含** `respond…(CANCEL)` | `a resumed activity shows the dialog`；接线臂断言两个 flow 各自仍 `AlertDialog.Builder(...)` + `dialog.show()` |

### 4.2 变异负控：**13/13 RED-AS-EXPECTED**

`mutate.py`：快照字节 → 逐臂回退一处生产代码 → 重跑 → `finally` 从快照还原并 sha256 比对（`restored: … OK`，**不用 `git checkout`**——未入库文件上它会失败并把变异留在树里）。

| 臂 | 回退内容 | 打红的用例 |
|---|---|---|
| M1 | `return START_NOT_STICKY` → `START_STICKY` | service wiring（sticky restart re-armed） |
| M2 | `ForegroundStartPolicy.decide(` → `decideNothing(` | service wiring（no policy decision） |
| M3 | policy `else -> STUB_THEN_STOP` → `ATTACH_STATUS` | 3 条行为臂 + policy 控制臂 + wiring（5 条，同族重叠已文档化：同一决定） |
| M4 | `onStartCommand` 改回裸 attach | service wiring（startForeground outside） |
| M5 | `onTaskRemoved` 改回裸 attach | service wiring（onTaskRemoved） |
| M6 | catch 里 `Log.w` → `Log.d` | service wiring（refusal not logged） |
| M7 | `startService` 的 `try` → `run` | service wiring（startForegroundService unguarded） |
| M8 | 第一处 `repeatOnLifecycle` → 裸 launch | activity wiring（not scoped to repeatOnLifecycle） |
| M9 | 第一处 gate 整段 → `if (true)` | activity wiring（site[pendingSettingsGate]） |
| M10 | 第二处 gate 整段 → `if (true)`（**只修一处的回归**） | activity wiring（site[pendingPrompt]） |
| M11 | 延后分支里加 `respondToSettingsGate(CANCEL)` | activity wiring（prompt answered instead of kept pending） |
| M12 | `try { dialog.show() }` → `run { } finally { }` | activity wiring（show: unguarded） |
| M13 | `onDestroy` 不 dismiss | activity wiring（permission dialog not dismissed） |

**M9/M10 第一版是 RED-WRONG-ARM**：两个控制臂当时用活源码做手术，M9 mut 掉第一处后「第二处」控制臂先崩，红得不可归因。改成合成夹具（`gateFixture(guardedFirst, guardedSecond)`，同 `LaunchSessionRerouteShapeTest` 的合成 launcher 臂），两臂现在各只翻自己那条。

## 5 CI

| 项 | 值 |
|---|---|
| run | **#3326 / `44455380218`** |
| head_sha | `68883327fc23b834364fdd5954f6e6cded812790` |
| 三方 sha | 本地 `git rev-parse HEAD` == `git ls-remote origin` == `run.head_sha`：**三者逐字符一致** |
| 五 job | `build-apk` / `lint` / `scan-gate` / `unit-tests` / `publish` 全 **success** |
| unit-tests | **9850 行 PASSED / 0 真 FAILED**（日志里 4 行含 `FAILED` 字样的都是 `… PASSED` 的用例名：`slot denied produces FAILED without calling provider` 等）；新增 26 条逐条 PASSED 已核（`ForegroundStartPolicyTest` 14 条、`DialogShowGateTest` 12 条） |
| scan-gate | `Summary: 115 passed, 0 failed`；日志 **`scripts/scan/** untouched in this push — skipping the fixture self-test`** ⇒ 本支未动门，绿不含夹具证据（按机制跳过，不是跳过判据） |
| publish | job success，`tag` / `release` 两步 skipped（main 门控） |

**第二次 dispatch（报告提交后的 tip 复核）**：run **#3330 / `44457939700`** @ `a93000c27ccb73a3bbf5219b8ad8bbf10cc71bbb`，五 job 全 success；`git diff 68883327..a93000c2` **只有报告文件**（非报告差异为空）；unit-tests 仍 **9850 PASSED / 0 真 FAILED**、26 条新用例在列，scan-gate 仍 **115 passed / 0 failed** 且夹具按机制跳过。本地 / 远端 / `run.head_sha` 三方逐字符一致。

**收口**：run **#3337 / `44461112974`** @ `b2d9ef3bf65d296bf7ea4664efc5b881b9d8a75e`，五 job 全 success（`build-apk` / `scan-gate` / `lint` / `unit-tests` / `publish`）；本地 `git rev-parse HEAD` == `git ls-remote origin` == `run.head_sha` **三方逐字符一致**。`git diff 68883327..b2d9ef3b --name-only | grep -v fix-reports/` **为空** ⇒ 除报告外，被验证的树就是代码提交的树。**停止节点达成，未合 main。**

> 此后若还有纯报告提交（本文件自身的措辞修订），按项目惯例不再 dispatch —— 判据绑定的是**代码提交** `68883327`（run #3326）与非报告差异为空的并集，不是报告文件的 sha。

## 6 残余与边界

1. **`ToolExecutionService.kt:53` 同样 `return START_STICKY`** —— 不在本支覆盖（报告未点名、且它不在允许改动的文件集内）。已登记 backlog。
2. **`startService()` 的调用点仍有 TOCTOU**：`SessionActivityTracker.startServiceIfNeeded` 的 `isAppForeground` 检查与调用非原子。本支在**被调方**吞掉异常，异常不会再杀进程；但"该起没起"这个窗口无法在调用方之外闭合。已登记 backlog。
3. **F-11 的"转交"依赖 StateFlow 的保留语义**：若将来这两条 pending 换成 one-shot channel（`SharedFlow(replay=0)` / `Channel`），`return@collect` 会变成真丢弃。约束写在 `DialogShowGate` 的 KDoc 与 `deferring must not answer the prompt` 那一臂里（后者检查延后分支不含任何 `respond…`），但**换成 channel 后那道臂是看不出来的**（它只查 respond 不查流类型）。登记 backlog。
4. **未做真机验证**：本条的两类崩溃都需要「LMK 在长回合中杀进程」或「弹窗事件落在窗口销毁窗口期」，沙箱无法构造。CI 的 Robolectric 不可用（本模块无该依赖，既有探针文件头也写明这一点），故行为证据由纯核 JVM 测试 + 源码锚点承担。

## 7 范围纪律

- 只改 `AgentForegroundService.kt`、`MainActivity.kt` 与两个新测试文件；`scripts/scan/**`、`backup/**`、`sandbox/**`、`offload/**`、`provider/**`、`ui/chat/**`、`share/**`、`ui/sandbox/**`、`data/storage/**` **零触碰**。
- 未跑全量 `scan.sh` / `test_scan.py` / `gradle`。
- 两处纯核**没有新建生产文件**（第一版曾建 `ForegroundStartPolicy.kt` / `DialogShowGate.kt`，会越出"只许改这两个文件"的边界，已删除并改为同文件尾部）。
- `MainActivity` 内其它 `lifecycleScope.launch`（`:514` keep-screen-awake、`:668` nav back-stack）已核为**非同构**（不弹窗 / 有 `onDispose`），登记于 §2.2，未改。

## 8 复现

```sh
cd /var/minis/shared/work/r3d1119r2/crash-jvm
sh run.sh            # 新增测试 26/26
sh run_existing.sh   # 既有回归 38/38（两段 cwd）
python3 mutate.py    # 13/13 RED-AS-EXPECTED，末尾 restored ... OK
```
