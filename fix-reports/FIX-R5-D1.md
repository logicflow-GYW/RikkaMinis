# FIX-R5-D1：工具槽位与完成驻留（D-F-01 / D-F-04）

## 1 身份链

| 项 | 值 |
|---|---|
| 批次 | R5 修后复扫（2026-10-29） |
| 任务书 | `/var/minis/shared/work/r5-1029/tasks/TASK-R5-D1.md` |
| 来源报告 | `/var/minis/shared/work/r5-1029/reports/R5-D.md`；判级 `TRIAGE.md`（D-F-01/C-05 证实 P1；D-F-04/C-04） |
| 分支 | `fix/r5-d1-toolslot-linger` |
| 基线 | main `f3def6ce`（`wt-d1` = `git log -1` 逐字符一致） |
| 生产改动面 | `SessionActivityTracker.kt` / `ToolSlotRegistry.kt` / `CompletionLinger.kt`（**仅这三个**） |
| 禁改文件 | `AgentLoopEngine.kt`、`MinisApp.kt`、`scripts/scan/**`、`scan.sh`、`test_scan.py` —— **零触碰**（`git status` 可验） |
| 门基线 | 113 门 / 349 夹具，未动 |

## 2 缺陷与先证（按语义核，不按行号）

| ID | 声称 | 先证结论 | 证据（本树） |
|---|---|---|---|
| D-F-01① 键漂移漏减 | `toolSlotKey()` 按 `active.size==1` 切键 ⇒ 单会话启动计入 `"A"`、双会话结束走 `finish("")` 漏减 ⇒ depth 永久 +1 | **证实**（两个方向都在）。`toolSlotKey()`（`SessionActivityTracker.kt:118`）读**实时**活跃集；`clearToolRunning()`（`SessionActivityTracker.kt:792`）用的还是同一个派生器 ⇒ start 与 finish 可以落在两个不同键上。反向：双会话时 start 进共享桶、另一会话先结束后 `active.size==1` ⇒ finish 走真 id，桶内 depth 留 1 | 修改前变体跑新用例：`finishWithDriftedKeyStillClearsTheRunningSlot` / `finishWithRealIdClearsTheSharedBucket` 双红，`Slot(sessionId=A, depth=1)` 存活 |
| D-F-01③ 无锁 | `seqCounter` / `entries: LinkedHashMap` 并发读写 | **证实且可复现**：`ConcurrentModificationException` ×2（`representative` 的 `filter` 撞上正在 `put` 的 IO 协程），见 §5 变体臂 | 同上，`concurrentReadWriteDoesNotCorruptTheRegistry` 在改前树 2 次 CME |
| D-F-01② 显示不复位 | `publishToolSlot()` 在 `slot == null` 时不复位 `_currentToolStatus`/`_currentSessionId` | **证实**：`slot != null` 分支外没有任何写入；`statusForNotification()`（`SessionActivityTracker.kt:925`）在 activeCount>0 时直接送 `_currentToolStatus`，于是 A 结束、B 仍在跑时通知继续写 A 的工具名，胶囊 tap 目标也停在被结束的 A | 源码形状 + `_currentToolStatus.value =` 全部写入点清单（§4） |
| D-F-04① 驻留期通知僵死 | `!shouldRunService()` 只 `stopService()` 不刷通知 | **证实**：该分支上方的清空块（`setInactive` 的 `isEmpty()` 分支）把 `_isToolRunning=false`、`_currentToolName=null` 写进**内存流**，但没有人 notify ⇒ 已存在的 9001 行保持旧文案 + `setProgress(0,0,true)`（`buildNotification` 只在重建时读流） | `AgentForegroundService.kt:822`（`isToolRunning` 才 setProgress）+ `SessionActivityTracker.kt:865-883`（`updateService` 的 background 分支本来就会 `refreshIfRunning`，只有这条路径没有） |
| D-F-04② 关闭胶囊不提前收口 | `dismissOverlay()` 不清 `completionLingerUntilMs`、`MinisApp:990` 回前台也不清 | **证实**：`pendingIdleStop`（`SessionActivityTracker.kt:248`）只在 `remainingMs <= 0` 时停，`dismissOverlay()` 原 body 完全不碰该字段 ⇒ 用户关掉胶囊后 FGS 仍被"已无人需要的胶囊"钉满 30 s | `pendingIdleStop`（`:248-269`）、`deadlineOnTurnEnd` 四条件（`CompletionLinger.kt:53-58`） |

## 3 修法（阶梯）

**这改变什么用户行为**：工具结束之后，通知栏（以及胶囊）不再继续显示"某个工具正在运行 + 无限旋转"；用户手动关掉完成胶囊、或直接切回前台时，前台服务立刻按正常 500 ms 去抖收口，而不是为一个已经被关掉的气泡再活 30 秒。

| 项 | 停在第几级 / 为什么 | 实现 |
|---|---|---|
| D-F-01① 漏减 | 停在第 7 级（最小实现），因为第 2/3 级都不成立：仓库里没有"带归属的工具调用"抽象可复用，标准库也没有"键漂移补偿" | `ToolSlotRegistry.finish()` 增加回退：命名键**无条目或 depth==0** 时，若**恰好一个**条目在运行则扣它。恰好一个 = 安全栏（两个在跑时猜错会把别的会话的活杀掉，比留幻影更糟）。同时 `setActive()` 在**运行边界** drop 本会话条目 —— 预检拒绝的 start 永远等不到 finish，这是回退看不见的那一半，只能靠边界清理 |
| D-F-01③ 无锁 | 第 7 级（`synchronized(this)` 逐入口），不加新锁对象/不加 `@Volatile` 方案：临界区只有几个 map 操作 | `start`/`finish`/`drop`/`slot`/`clear`/`representative` 全部同锁 |
| D-F-01② 显示复位 | 第 3 级（复用已有纯类常量 `ToolSlotRegistry.IDLE_STATUS`） | `publishToolSlot()` 的 `else` 臂：复位状态行 + 把 `_currentSessionId` 对齐到"仍在活跃的会话"；`active` 为空时**故意不动** id（`setInactive` 为完成胶囊保留它就是设计） |
| D-F-04① 通知重绘 | 第 7 级：调既有 `AgentForegroundService.refreshIfRunning`（后台安全：已运行服务上是 `notify()`，不是 `startForegroundService()`） | `setInactive()` 在武装 linger 成功后、`stopService()` 之前刷一次，参数用 `sessionCountForNotification()` / `statusForNotification()`（与 `updateService()` 同源，不新造快照） |
| D-F-04② 提前收口 | 第 7 级 + 一个显式常量 | `dismissOverlay()`：`completionLingerUntilMs = CompletionLinger.CLEARED` + `if (!shouldRunService()) scheduleIdleStop()`（**不是** `stopServiceNow()`：必须留在 500 ms 去抖里，否则 draft→persisted 相邻帧的换 id 会变成 stop/start 竞态）；`maybeRefreshService()`：回前台且无活可干时同样清零 + 重排。`MinisApp` 已在 `onActivityResumed` 调 `setAppForeground(true)` + `maybeRefreshService()`，**无需改禁改文件** |
| `CompletionLinger` | 新增 `const val CLEARED = 0L` | 让"清零"有名字（原文里 4 处裸 `0L` 各自表达同一件事），并给"清零后必须立刻可停"一条可测判据 |

## 4 覆盖面（同族普查）

`find main -name '*.kt' | xargs grep -n` 得到的**全部**写入点，逐条判过：

- `_currentToolStatus.value =` 三处：`:134`（有代表条目，正常）、`:144`（**本次新增**，无代表条目复位）、`:545`（`setInactive` 活跃集清空时，与 `:144` 同值，无冲突）。
- `_isToolRunning.value =` 两处：`:132`（派生）、`:548`（清空），无第三写者。
- `_currentSessionId.value =` 四处：`:137`（有归属条目）、`:147`（**新增**对齐）、`:425`（`publishLastReply`，用户可见的新回复——有意优先）、`:494`（`setActive`）。
- `completionLingerUntilMs` 六处：声明（`CLEARED`）、`pendingIdleStop` 到期（`CLEARED`）、`maybeRefreshService`（新增）、`dismissOverlay`（新增）、`setActive`（`CLEARED`）、`setInactive`（唯一武装点）。**武装点仍然只有一处**，所以"谁该取消它"的答案集合是有限的。
- `publishToolSlot()` / `toolSlots.finish` / `toolSlots.representative` / `dismissOverlay()` 的 caller 全在 `SessionActivityTracker` 内（`dismissOverlay` 另有 `AgentForegroundService:415` 一处外部调用，签名未变）。
- `ToolSlotRegistry` 成员清单复核：`start`/`finish`/`drop`/`slot`/`clear`/`representative`，无 `entries` 外泄、无第二写者 —— 加锁面即全部入口。
- **刻意不动**：`setAbsent` / `clearPresence` 的 `stopService()` 不加取消（linger 只在**后台回合结束**武装，而回到前台必经 `maybeRefreshService` 取消 ⇒ 顺序不可达；见 R4）；`AgentForegroundService` 一行未改（任务允许但不需要）。

## 5 验证四层

1. **纯逻辑 JVM（绿）**：`ToolSlotRegistryTest` 11 / `CompletionLingerTest` 6 / `R5D1TrackerLingerWiringTest` 7 = **24 tests OK**（`java JUnitCore`，cwd = `src/android/app`，因为形状断言按模块相对路径读源码）。含任务点名的动态键漂移臂：`start("A") → 键变（active={A,B}→UNATTRIBUTED）→ finish("") → representative(active) == null`。
2. **变体臂（改前代码，必须有牙）**：用 `git show HEAD:<file>` 还原三份改前源码 → 同一套新用例 **11 跑 3 红**：`finishWithDriftedKeyStillClearsTheRunningSlot`（`Slot(A, depth=1)` 存活）、`finishWithRealIdClearsTheSharedBucket`（`Slot(, depth=1)` 存活）、`concurrentReadWriteDoesNotCorruptTheRegistry`（**CME ×2**）。⇒ 锁与回退都不是"看起来对"，是改前确实坏。
3. **接线形状 + 负控**：`R5D1TrackerLingerWiringTest` 7 条断言（`setInactive` 在 `deadlineOnTurnEnd` 与 `stopService()` **之间**调 `refreshIfRunning`；`dismissOverlay` 只写一次 `CLEARED` 且走 `if (!shouldRunService()) scheduleIdleStop()`；`maybeRefreshService` 认武装判据；`publishToolSlot` 复位在 `representative(` 之后；`setActive` 的 drop 在激活日志之前；registry 六个入口全 `synchronized(this)`），外加**负控**把四处修法逐个从源码串里删掉（linger refresh / CLEARED / idle reset / run-boundary drop）并确认同一套检查转红。期望值全部是字面量，无一条从生产常量推导。
4. **编译级 + 云端全量（run `40855980599`，success 21.5 min，`head_sha a50979566d7f` 与本地逐字符一致）**：Android 壳（`Handler`/`Looper`/`Context`/FGS）沙箱不可跑 ⇒ 靠 CI 的 `compileReleaseKotlin`；本地另做括号配平（`BALANCED`，手写 lexer 处理注释/字符串/字符字面量）+ 与改前树逐处 diff 复核。CI 读数：**27 步 0 非 success**（skipped 仅 `Move the rolling tag` + `Publish to Releases` = main 门控）· 门集 `Summary: 113 passed, 0 failed`（**基线 113 未变**）· 夹具 `ALL 349 CASES PASS` · `^ e: ` **0** · 真 `FAILED` 行 **0** · 日志 1,628,586 B / NUL **0** · 本支新用例在日志里**真跑并通过**（`ToolSlotRegistryTest` 11 条含 `finishWithDriftedKeyStillClearsTheRunningSlot` / `concurrentReadWriteDoesNotCorruptTheRegistry`、`CompletionLingerTest` 6 条含 `clearedDeadlineMeansStopNow`、`R5D1TrackerLingerWiringTest` 7 条含负控）。

跑法（可复现，全程 `nice -n 10`、单 JVM 串行）：

```
kotlinc -no-stdlib -nowarn -cp kotlin-stdlib-2.1.0.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar -d out \
  main/.../ToolSlotRegistry.kt main/.../CompletionLinger.kt \
  test/.../ToolSlotRegistryTest.kt test/.../CompletionLingerTest.kt test/.../R5D1TrackerLingerWiringTest.kt
cd src/android/app && java -cp out:jars org.junit.runner.JUnitCore \
  com.rikkaminis.app.service.{ToolSlotRegistryTest,CompletionLingerTest,R5D1TrackerLingerWiringTest}
```

## 6 残留（每条带触发条件 + 触发后方案）

- **R1 两个以上运行中条目 + 未命中键 = 不猜**（`finish` 回退的唯一安全栏）。触发条件：一次运行里两个会话同时跑工具且 finish 键都漂移。退出方式：让调用点走 5 参 `updateToolStatus`/`clearToolRunning(outcome, sessionId)` 带归属（provider-pack 改动，属 C 支）；在此之前由 `setInactive`/`setActive` 的 drop 兜底。
- **R2 跨会话错扣**（finish 命中"别人的"键：双会话时 A 完成、键落到共享桶，扣掉的可能是 B 的 depth）。这是**无归属信息**的固有上限，`AgentLoopEngine.kt` 禁改 ⇒ 本支只保证"总量平衡 + 显示最终归零"，不保证扣对了人。
- **R3 预检/循环守卫拒绝**：start 已计入、finish 永不到 ⇒ 本回合内通知可能多转一会儿。触发条件：`AgentLoopEngine` 在拒绝分支 continue 而不 `clearToolRunning`。现状由**运行边界**（`setActive`）与回合末（`setInactive`）双 drop 收敛到一个回合内；根治需在拒绝路径补一次 clear（禁改文件）。
- **R4 `setAbsent` / `clearPresence` 不取消 linger**。判为不可达顺序（linger 只在后台回合结束时武装；回到前台必经 `maybeRefreshService`）。触发条件：出现"前台状态下武装 linger"的新路径（例如未来把 `deadlineOnTurnEnd` 的 `appForeground` 条件放宽）⇒ 必须同步在这两处清零。
- **R5 多会话无工具时的 tap 目标**取 `active.lastOrNull()`（`Set` 的插入序，不是"最近激活"的契约）。触发条件：胶囊在多会话并存时需要精确路由。影响面：只是 id 选择，不影响运行态。
- **R6 真机未验**（本支全部证据 = JVM + 源码形状 + CI 编译级）：500 ms 去抖 × FGS notify 时序、HyperOS 前台服务收口、胶囊接管动画。触发条件：发版前真机走查一次"后台跑工具 → 结束 → 关胶囊"。

## 7 未验证边界

- 真机：Android FGS 通知重绘可见时机、`dismissOverlay` 与 `onDismissByUser` 的实际点击路径、胶囊与 `ToolOverlayController` 的交接（本支未改 overlay 控制器）。
- 并发：JVM 只覆盖 registry 的 4 线程 400 轮（读线程 1200 轮），不是真机 IO 协程调度；`SessionActivityTracker` 的 `synchronized(activeSessionsLock)` 与本支新锁的**交叉顺序**未做形式化证明（新锁是叶子锁，不嵌套调用外部锁）。
- 门：未跑全量（任务硬约束②），只逐条核过 `scripts/scan/*` 里是否引用本次涉改符号（`completionLingerUntilMs` / `toolSlots` / `publishToolSlot` / `toolSlotKey` / `IDLE_STATUS` = 0 命中）。

## 8 附：装置与产物

- worktree：`/var/minis/shared/work/r5-1029/wt-d1`（分支 `fix/r5-d1-toolslot-linger`）
- 装置：`/var/minis/shared/work/r5-1029/probe-d1/`
  - `out/`（改后编译产物）、`variant/ToolSlotRegistry.kt` + `variant/CompletionLinger.kt` + `variant/out/`（`git show HEAD:` 还原的**改前**变体）
  - `balance.py`（括号/注释/字符串感知的配平检查，本树 6 文件 `BALANCED`）
- 依赖：`/var/minis/shared/work/jvm-libs/{kotlin-stdlib-2.1.0,junit-4.13.2,hamcrest-core-1.3}.jar`
- 测试文件：`src/android/app/src/test/java/com/rikkaminis/app/service/{ToolSlotRegistryTest,CompletionLingerTest,R5D1TrackerLingerWiringTest}.kt`
- 生产文件：`src/android/app/src/main/java/com/rikkaminis/app/service/{SessionActivityTracker,ToolSlotRegistry,CompletionLinger}.kt`
