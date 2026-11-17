# FIX-W1118-tools — D8（agent 运行时与工具集）+ D9（调试 RPC / 会话编排 / 日志）

## 1 元信息

| 项 | 值 |
|---|---|
| 来源报告 | `Wave_1118_D8_Report.md`（F-01…F-07）· `Wave_1118_D9_Report.md`（F-01…F-09，其中 F-06/F-07 按任务书归 platform 支） |
| 工作树 / 分支 / 基线 | `/var/minis/shared/work/incr1118/wt4` · `fix/w1118-tools` · `c85131ad` |
| 生产提交 | `d258c791`（代码 + 测试，20 文件 +757/−42）；本文件单独提交在其后（`be64ebf0`） |
| 报告提交 | 本文件（单独提交；`git diff --name-only d258c791..HEAD` 只有本文件） |
| 改动面 | `tools/**`（含新增 `ToolArgsJson.kt`）、`agent/**`（不含 `agent/runtime/AgentExecutionBudget.kt`）、`debug/**`（含新增 `HeadlessTurnWait.kt`、`CaptureReclaim.kt`）、对应测试 |
| 禁碰面 | `scripts/scan/**`、`provider/**`、`sandbox/**`、`service/**`、`ui/**`、`diagnostics/**`、`crash/**` 全部零改动；未跑全量 `scan.sh`/`test_scan.py`/gradle（只跑 `debug_leak_guard` + 5 个相邻门，见 §5） |
| 停止节点 | 分支 CI 绿且 `head_sha` 与 tip 逐字符一致（**未合 main**） |

## 2 判级表（本支全部条目）

| ID | 报告 severity | 本文判级 | 结论 | 去向 |
|---|---|---|---|---|
| D8 F-01 `FileEditTool` 绕过 `AppendOnlyWriteGuard`/`WriteSnapshotPlan` | high | **P3** | **不修**（已在 `backlog §WRITE-GUARD-1116-REVIEW ②` 登记，触发条件未满足） | §4.1 |
| D8 F-02 `traceStart()` 不重置 run 上下文 | medium | **P2** | 不修（兼容入口零生产调用点） | §4.2 |
| D8 F-03 `JSONObject.NULL` → 字面量 `"null"` | high | **P1** | **已修** | §3.1 |
| D8 F-04 `app_guide` 漏注册 `ToolRetrySafetyRegistry` | medium | **P1** | **已修** | §3.2 |
| D8 F-05 `AgentExecutionBudget` 单标量 child 预留 | low | **P2** | 不修（**跨域**：该文件归 provider 支） | §4.5 / §6 |
| D8 F-06 `SoulStore.writeAtomically` 回退路径残留 `.tmp` | low→P1/P2 | **P2** | **已修**（同一函数同一处，顺路成本≈0） | §3.3 |
| D8 F-07 `FINALIZING` 丢弃迟到 `ToolFinished(resultKnown=false)` / 不记不可成功 | medium | **P2** | 不修（要调用方先犯错才可达） | §4.3 |
| D9 F-01 `retry`/`rerunFromToolBlock`/`compact` 不传播取消 | high | **P1** | **已修** | §3.4 |
| D9 F-02 `DebugServer.handleConnection` 的 `runBlocking` 断取消树 | high | **P2** | 不修（只覆盖 `stop()`；断连取消不因该改动成立） | §4.4 |
| D9 F-03 `compactAll` 调度前取消导致 `_isCompacting` 卡死 | high | **P1** | 不修（**跨域**：`conversation/` 不在 allowlist） | §6 |
| D9 F-04 `modelsUpdate` 用旧 `entryId` 取消选择 | high | **P1** | **已修** | §3.5 |
| D9 F-05 `providerFor` 未解析草稿别名 | medium | **P2** | 不修（报告口径需修正，见 §4.6） | §4.6 |
| D9 F-06 `HangDetector.markHealthyTick` 锁内未重检 | medium | **P2** | 不修（**跨域**：`diagnostics/`） | §6 |
| D9 F-07 `LaunchCycleBeacon` 读不存在的 `session=` 字段 | medium | **P2** | 不修（**跨域**：`diagnostics/`） | §6 |
| D9 F-08 `handleScreenshot` 缺 `finally` + `PixelCopy` 失败泄漏 `Bitmap` | medium | **P1** | **已修** | §3.6 |
| D9 F-09 `debug.modelUse.exec` 临时文件仅毫秒命名 | low | **P2** | 不修（非同一函数，不在半径内） | §4.7 |

判级口径：谁在什么操作下会遇到 —— F-03 是**每次**模型对可选参数发 `null`（schema 未标成 nullable 时很常见）；F-04 是 `app_guide` 的**任何一次**失败；D9 F-01 是子代理/调度器**按停止**或 RPC 连接断开的普通操作；D9 F-04 是调试面「改名 + 隐藏」同一次调用。其余条目的触发都需要第二重条件（另一处先出错、磁盘先满、同毫秒并发、`DebugServer.stop()` 恰在 RPC 在途），因此落 P2。

## 3 逐条证据（先证 → 修 → 变异）

本地装置：`/var/minis/shared/work/incr1118/t4jvm/run.sh`（`pre` = 基线 `c85131ad` 的字节或修复前表达式；`post` = 工作树；`mutate` = 把修复改回去）。编译的是**生产文件原文**（无 Android classpath 的文件用逐字切片，切片理由写在各自头注释），跑的测试是**仓库里的真实测试文件**，没有第二份逻辑副本。

| 组 | post | pre | mutate |
|---|---|---|---|
| args（D8 F-03） | OK (9 tests) | 3 红：`explicit null reads as the fallback` / `lines null is not a request for zero lines` / `explicit null overwrite_reason is refused as unjustified` | 同 pre（pre 臂就是把 reader 改回裸 `optString`/`has+optInt`） |
| registry（D8 F-04） | OK (2 tests) | 2 红：`app_guide is READ_ONLY` / `every exposed tool has a trusted safety level` | 2 红（删掉 `"app_guide"` 行） |
| soul（D8 F-06） | OK (2 tests) | 1 红：`a failing fallback write leaves no tmp sibling` | 同 pre（基线字节） |
| wait（D9 F-01） | OK (5 tests) | N/A（helper 是本批新增） | 2 红：`cancelling the caller stops the run exactly once` / `the caller still learns it was a stop` |
| unselect（D9 F-04） | OK (4 tests) | N/A（seam 是本批新增；基线 `:541 repo.unselectEntry(entryId)` 已核） | 2 红：`a rename plus removal unselects the persisted id` / `a removal of an unchanged id unselects that id` |
| capture（D9 F-08） | OK (3 tests) | N/A（helper 是本批新增；压缩一半由 `discarded_return_guard` 门 B2/B3 盯，见 §3.6） | 2 红：`releases the refused frame before the fallback…` / `a throwing fallback is not swallowed` |

### 3.1 D8 F-03 —— 参数里的显式 `null` 被当成值（P1）

**先证**：五处读取点全部核实（`FileWriteTool.kt:57` `overwrite_reason`、`ToolTitleResolver.kt:18` `tool_title`、`FileReadTool.kt:101` `lines`、`MemoryTools.kt:132` `content`、`AppGuideTool.kt:57` `query`），且同仓 `SubagentSkill.replyText`（`[INCR-1117 A-F01]`）已把「Android 的 `optString` 对 `JSONObject.NULL` 返回字面量 `"null"`」写成 KDoc 事实。单元测试跑的是 JSON-java（`org.json:json:20231013`，**它对 NULL 返回 fallback**），所以缺陷在 CI 上**结构性不可复现** —— 这正是它活到今天的原因，也是测试必须锚「reader 是否查 `isNull`」而不是锚「`optString` 返回什么」的原因。测试用 `AndroidLikeJson`（只覆写 `optString` 返回 `"null"`，absent 键仍回退）把 Android 口径固定下来，`isNull` 在两种实现里都是「null 或缺席」，所以修复与 wire 形态无关。

**改**：新增 `tools/ToolArgsJson.kt`（`optArgString` / `optArgIntOrNull`），五处改用它；`overwrite_reason: null` 现在真会触发 `OVERWRITE_NOT_JUSTIFIED`，`lines: null` 不再等于「返回 0 行」。

**用户行为变化**：模型对账本发 `overwrite_reason: null` 时会被拒（以前放行）；`file_read` 传 `lines: null` 会读到全文（以前返回空）；工具胶囊标题不再显示 `null`。

### 3.2 D8 F-04 —— `app_guide` 漏注册（P1）

**先证**：`AgentTools.makeAgentTools()` 的 12 个工具里只有 `app_guide` 不在 `ToolRetrySafetyRegistry.defaults`（`conversation_history` 早已按 `[U10]` 补过，是同一形态）。`RetryPolicy` 对 `UNKNOWN` 非 `NON_ZERO_EXIT` 失败给 `OutcomeUnknown` ⇒ 不可重试，并把 `hasOutcomeUnknownTool` 置位 ⇒ 该 Run 不能进 `SUCCEEDED`。

**改**：注册为 `READ_ONLY`。测试口径从「手工列名字」改成**从生产工厂派生**（`AgentTools.makeAgentTools().map { it.name }`），下一个新增工具漏注册会直接红；本地用 `registry_probe.py` 机械地从 `AgentTools.kt` 的 12 个 `add(...)` 反推名字喂给真实编译的注册表（Android classpath 不可得）。

`ToolConcurrencyPolicy.PARALLEL_SAFE_TOOLS` 未加 `app_guide`：只读但**不声明**并行安全是保守方向（串行只是慢），不属于缺陷，不改。

**用户行为变化**：`app_guide` 失败后可以重试，且不再让整轮 Run 无法成功收尾。

### 3.3 D8 F-06 —— `writeAtomically` 回退路径残留 `.tmp`（P2）

**先证**：基线 `if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }` —— 回退写抛异常时 `delete()` 被跳过；同文件 `[audit-0917]` 已在写入侧加了同样的清理，**唯独回退分支没有**（同族漏一处）。复现不靠 mock：把 target 指向**目录**，`renameTo` 因 EISDIR 返回 false、`writeText` 抛 `FileNotFoundException`，真实走到回退失败路径（基线臂实测残留 `SOUL.md.tmp`）。

**改**：`try { target.writeText(text) } finally { tmp.delete() }`。

**用户行为变化**：磁盘满/权限不足导致 SOUL.md 保存失败时，不再留一个永不被清理的 `SOUL.md.tmp`。数据侧无变化（target 保持旧内容）。

### 3.4 D9 F-01 —— 三个 `wait=true` 入口不传播取消（P1）

**先证**：`prompt()`（`:582`）有 `catch (ce: CancellationException) { NonCancellable { vm.cancelStream() }; throw ce }`；`retry`（`:717`）、`rerunFromToolBlock`（`:845`）、`compact`（`:975/993`）是同一段等待的复制，`grep CancellationException debug/HeadlessChatRunner.kt` 只有 `prompt` 的那一处。取消语义在 `[INCR-1117 A-F04]` 已定：**超时 ≠ 取消**（超时必须保留后台运行）。

**改**：抽出 `debug/HeadlessTurnWait.abandoningWaitStopsRun(stop, block)`，接入 `retry` / `rerunFromToolBlock` / `compact`（`compact` 的 2 秒宽限窗口 + 主等待共 4 个等待点）；`stop` 传 `vm::cancelStream`（它同时 `compactJob?.cancel()`，所以压缩路径也覆盖）。

**`prompt()` 保留手写副本（第一轮 CI 后回退）**：最初把 `prompt` 也收编进 helper，`SubagentBudgetGateTest.a cancelled caller stops the turn it started`（`[INCR-1117B A4]` 的源码形状断言，锚 `val finished = try {` → `withContext(NonCancellable)` → `vm.cancelStream()`）随即翻红。那条断言守的是「参考实现的取消规则还在」，而本批要修的是**三份漏抄**——把参考实现也搬走等于为了让新装置好看而去改老门的锚，收益为零。故恢复 `prompt` 原文，只在 KDoc 里点名「另外三个入口走 helper」。

**用户行为变化**：子代理/调度器/RPC 客户端在 `wait=true` 期间按停止或断开时，子会话的那一轮真的停下（不再继续跑工具、烧 token）；超时行为不变。

### 3.5 D9 F-04 —— 改名 + 隐藏时用旧 id 取消选择（P1）

**先证**：基线 `ProviderMutationMethods.kt:541` 确为 `repo.unselectEntry(entryId)`（`git show c85131ad:… | grep` 已核），而上两行的 `[da1023-C]` 注释明写「`updateEntry` 返回 canonical persisted id，报告 persisted 而不是 pre-persist copy」。改名 + `isHidden=true` 组合下 `unselectEntry` 匹配 0 行，却仍返回 `unselected: true`。

**改**：抽 `persistModelsUpdate(updated, unselectRequested, updateEntry, unselectEntry)`，生产调用点用 lambda 传入仓库两操作 —— 决策因此可在 JVM 上测（真 `ProviderRepository` 需要 Android `Context`）。

**形状约束（第一轮 CI 后调整）**：`entry_composite_key_guard_check` 的门臂 5 要求 `modelsUpdate` 函数体内出现 `repo.updateEntry(updated)` **且**该表达式的 elvis 右侧是 `throw`（`check_elvis_throws`）。把「写入 + 失败即抛」整体搬进 helper 会让门报 `gate ROT`（本支禁碰 `scripts/scan/**`）。因此失败臂留在调用点的 lambda 里：

```kotlin
updateEntry = {
    repo.updateEntry(updated)
        ?: throw RPCException(-32602, "Entry update rejected (blank or colliding model id): $entryId")
},
```

语义也更直白：「先落盘，或什么都不做就拒绝」。测试的拒绝臂相应改成「updateEntry 抛 ⇒ unselect 一次都没发生」。

**用户行为变化**：调试面「改名 + 隐藏」一次调用的模型真的从已选集合里移除（以前仍参与路由），响应也不再谎报。

### 3.6 D9 F-08 —— 调试截图两条泄漏路径（P1，debug 面）

**先证**：`DebugRPCHandler.handleScreenshot` 是 `compress(); recycle()` 直线代码，而同族 `DebugScreenshotRing.capture` 在 `[audit-0917]` 已改成 `try/finally` —— 又一处同族漏改（报告点名的正是这条）。两处 `PixelCopy` 失败分支都直接 `cont.resume(canvasCapture(...))`，被拒的那帧不回收。

**改**：`handleScreenshot` 的压缩段改成与 `DebugScreenshotRing.capture` 相同的 `try { compress } finally { recycle }`（两个文件现在逐字同形）；`PixelCopy` 失败分支抽到 `debug/CaptureReclaim.discardFailedCapture`，「先释放被拒的帧、再分配替代帧、释放失败不影响返回值」这一条由两个文件共用并由 JVM 测试盯。

**为什么压缩一半不做成 helper（第一轮 CI 后调整）**：`discarded_return_guard`（C-B2/C-B3）要求 `.compress(` 的 `record-ok:` 标记落在调用行或紧邻的连续注释块内（lookback 6 行）。把调用塞进 lambda 会让标记离调用 5 行以上，门当场翻红——而那个门盯的正是「编码判定被丢掉」这件本修复要防的事。所以这一半的回归护栏是**门**而不是单测（JVM 里没有 `Bitmap`，本来也写不出有意义的单测）。

**用户行为变化**：调试截图失败不再泄漏一帧全屏位图（压缩异常 / PixelCopy 被系统拒绝时）。

## 4 未修项三件套

### 4.1 D8 F-01 `file_edit` 不走护栏 —— P3，已有登记不重修

`backlog §WRITE-GUARD-1116-REVIEW ②`：`file_edit` 需要 **exact `old_string`**，要整份清空得先刻意把整个文件体当 `old_string`（六次历史事故形态全是 `file_write` 漏 `append`），已判「不在常用路径」。本次复核触发条件：**日志无 `file_edit` 造成台账/记忆缩水的痕迹**（`grep "Edited /var/minis/(shared/backlog|memory/)"` 零命中），台账现状 11,296 行完好 ⇒ 触发条件未满足，不重开。

另外记一条报告没提到的口径：把它按报告草案接入 `AppendOnlyWriteGuard.decide(append=false, overwrite=false, …)` 会让**任何**对这两类文件的 `file_edit` 一律被拒（`decide` 只看「是否 append / 是否 overwrite」），而 `file_edit` 本身就是定点替换、没有 `append` 通道 —— 这也是它被登记成「要设计一次」而非「补一行」的原因。

- 触发条件：日志出现 `file_edit` 造成 `backlog.md`/`memory/**` 显著缩水。
- 方案：只接 `WriteSnapshotPlan.shouldSnapshot(append = false, …)`（快照，不拦），并补一条「≥8 KiB 缩水一半必有 `.bak-*`」的结构断言。
- 搁置理由：现行触发面 = 模型刻意用整份文件当 `old_string`；快照方案本身还需要决定「`file_edit` 的 incoming 字节怎么算」，不是一行能落地的事。

### 4.2 D8 F-02 `traceStart()` 不重置 run 上下文 —— P2

证据：`grep -rn "traceStart(" --include=*.kt` 生产代码 **0 处调用**（全部在 `AgentTraceRecorderTest`），`AgentLoopEngine` 走 2.0 的 `beginRun`。「复用实例先 `beginRun/endRun` 再 `traceStart`」的串台因此在当前树里不可达。

- 触发条件：出现第 1 个生产 `traceStart()` 调用点（或 2.0 回退到 1.0 入口）。
- 方案：`traceStart` 内 `synchronized(writeLock) { activeRunId=""; activeSessionId=""; terminalWritten=false }`，并加一条 `beginRun→endRun→traceStart→traceEnd` 的双 `trace_end` 测试。
- 搁置理由：零调用点的兼容入口，改它现在换不到任何行为变化。

### 4.3 D8 F-07 `FINALIZING` 期迟到未知工具 / 不记不可成功 —— P2

证据：`RunFinalized` 的生产发射点只有 `ChatAgentTraceObserver.t7EndRun`，`terminal` 来自 `AgentLoopExitDecision` —— 取消/超时/中断的退出决定分别是 `CANCELLED`/`FAILED`，**不会**发 `SUCCEEDED`。要触发「取消后仍被接受为 SUCCEEDED」，需要退出决定本身先算错（二重故障）。同理，`FINALIZING` 丢弃迟到 `ToolFinished(false)` 只影响 trace 里的 `hasOutcomeUnknownTool` 标记，终态已是 CANCELLED。

- 触发条件：日志/trace 出现「`terminal_state=Succeeded` 而本轮有 `UserCancelled`/`DeadlineReached`」或 `auditEvidenceGaps` 报 outcome-unknown 缺失。
- 方案：`AgentRunState.terminationForced: Boolean` + `finalizeFrom` 拒 `SUCCEEDED`；`FINALIZING` 分支接受迟到的 `ToolFinished(resultKnown=false)` 并置 `hasOutcomeUnknownTool`。
- 搁置理由：状态机「防调用方传错参数」的加固，需先有调用方传错的证据；纯加状态位会改 `AgentRunState` 的快照形态（四处同步面）。

### 4.4 D9 F-02 `DebugServer.handleConnection` 的 `runBlocking` —— P2

证据：`handleConnection` 是同步函数（阻塞读 header/body），`runBlocking` 只是为了调 `suspend fun rpcHandler.handle`；`acceptJob?.cancel()` 因此到不了在途 RPC。**但**改成 `suspend fun` + 直接 `await` 只解决 `stop()` 这一半：客户端断开后没有任何人读 socket，断连取消仍然不成立（报告把两件事算成一件）。

- 触发条件：真机日志出现「DebugServer 已 stop，仍有 `chat.prompt(wait=true)` 在跑」（或 `debug` 面出现孤儿 run 的 token 消耗）。
- 方案：`handleConnection` 改 `suspend`、去掉 `runBlocking`（`catch (ce: CancellationException) { throw ce }` 要排在 `catch (e: Exception)` 之前）；断连取消另做（读循环或 `soTimeout` 探活）。
- 搁置理由：debug-only 面 + 需要 `stop()` 恰在 RPC 在途；改动本身不产生「客户端断开就停」的效果，容易被当成“已修”而误导。

### 4.5 D8 F-05 `AgentExecutionBudget` 单标量 child 预留 —— P2 + 跨域

文件 `agent/runtime/AgentExecutionBudget.kt` 按任务书归 **provider 支**（本支禁碰）。报告反证也承认「单 child 阻塞模式下调用方若严格传 `reserved - consumed` 不触发」。判 P2 并转交。

### 4.6 D9 F-05 `providerFor` 未解析草稿别名 —— P2（报告口径需修正）

复核后结论比报告轻：`ownerFor(sessionId)` **自己**会 `resolveKey`，所以 VM 始终落在 canonical `ViewModelStore` 上；`ViewModelProvider.get` 返回的是那一个已有 VM，`ChatViewModel.factory(sessionId=…)` 的参数只在 store 为空时才有意义（草稿晋升后不会）。真实影响只剩两件：① `providers` 缓存多一个 `__new__…` 键（`forget(realId)` 清不掉，持有一个 provider 对象）；② 若在**晋升前**就急解析，反而会把草稿的 VM 分裂成两个（界面此时用的还是草稿键）。因此不能照报告的 diff 直接改。

- 触发条件：`providers` 里同时出现 `__new__…` 与 canonical 两个键（日志 `HeadlessChatRunner` 缓存大小）或出现「同一会话两轮同时流式」。
- 方案：`providerFor` 只做**缓存键**归一（`providers[resolveSessionId(id)]`），`ChatViewModel.factory` 仍传调用方 id，并在 `forget` 里同时清别名。
- 搁置理由：当前不产生错误 VM（store 已经过 `resolveKey`），收益是缓存卫生。

### 4.7 D9 F-09 `debug.modelUse.exec` 临时文件命名 —— P2

- 触发条件：并发测试套件/两个客户端在同一毫秒各发一次带 `input` 的 `debug.modelUse.exec`（日志出现「input blob 内容不符」或「找不到临时文件」）。
- 方案：文件名加 `System.nanoTime()` 或原子序号（一行）。
- 搁置理由：与本次修复的文件同处一包但不同函数，不满足「顺路成本≈0」；触发需要同毫秒并发。

## 5 本地验证（只跑本支相关，未跑全量）

- `python3 scripts/scan/debug_leak_guard.py .` → `✅ source: no unaudited debug-package references` / `✅ debug read path is minSdk-26 safe`（改 `debug/**` 后按任务书要求跑）。
- 相邻门（按改动符号挑，非全量）：`tool_title_error_branch_check` OK(7 files)；`entry_id_rename_integrity_check` OK(4 arms)；`provider_write_rules_check` ALL GREEN；`outcome_known_chain_check` 8 criteria green；`readonly_mount_write_guard` 通过。
- 结构自检 `t4jvm/selfcheck.py`：13 个改动文件括号/字符串配平 + kotlinc 语法类诊断 0 条（Android classpath 缺失导致的 unresolved-reference 不计）。
- 未做：gradle 编译、单测全量、全量 `scan.sh`/`test_scan.py` —— 按「云端跑全量」纪律交 CI。

## 6 跨域指针（本支未碰，交主会话分派）

| 条目 | 文件 | 建议归属 | 摘要 |
|---|---|---|---|
| D8 F-05 | `agent/runtime/AgentExecutionBudget.kt` | provider 支（任务书已指定） | child token 预留无归属，`releaseChildBudget(初始额)` 会误扣并发 child |
| D9 F-03 | `conversation/CompactOrchestration.kt:214/271/575` | conversation 支 | `_isCompacting=true` 在 `launch` 之前置位，调度前取消 ⇒ `finally` 不跑 ⇒ 该 VM 永久无法压缩/发送；修法 `compactJob?.invokeOnCompletion { _isCompacting.value = false }` 或 `CoroutineStart.UNDISPATCHED` |
| D9 F-06 | `diagnostics/HangDetector.kt:264-280` | platform 支 | 锁内未重检 `KEY_LAST_HANG_AT`，刚记录的 hang 会被主线程立刻清零 |
| D9 F-07 | `diagnostics/LaunchCycleBeacon.kt:216-232` | platform 支 | 读 `stall-*.log` 里的 `session=`，但 `writeStallSample` 从不写该字段（D7 死方言），`lastSessionId` 恒 `unknown` |

## 6.5 第一轮 CI 的三处红（本身就是证据）

run `#3223`（`44025492994`，`head_sha 05995973`，第一版实现）三个 job 红，本轮全部修复后重跑；三条都不是「代码写错」，而是**改动了别人锚定的形状**：

| job | 报错 | 处理 |
|---|---|---|
| unit-tests | `SubagentBudgetGateTest > a cancelled caller stops the turn it started FAILED`（`the wait must be written so its cancellation is handled`） | `prompt()` 恢复手写 `try/catch`（见 §3.4） |
| scan-gate | `entry_composite_key_guard_check` 臂 5 `modelsUpdate RPC no longer resolves the write through repo.updateEntry(updated) — gate ROT` | 失败臂留在调用点 lambda 里（见 §3.5） |
| scan-gate | `discarded_return_guard` B2/B3：`handleScreenshot() drops the compress verdict`（`record-ok:` 与调用被 lambda 隔开 5 行以上） | 压缩段改回 `try/finally` + 紧邻标记（见 §3.6） |
| lint | `AppGuideTool.kt:60 SuspiciousIndentation`（我插入的两行注释第二行丢了缩进） | 注释重排 |

教训：本批三次返工全部来自「同一个决策有两个锚点，我只改了其中一个」——老测试锚 `prompt` 的形状、老门锚 `modelsUpdate` 的调用文本、编码门锚 `record-ok` 与调用的距离。**先查谁锚了这段，再决定要不要动它**，比事后读 CI 日志便宜。

## 7 与报告不一致之处（判读记录）

1. **D8 F-01 的修复草案不可直接落地**：`decide(append=false, overwrite=args.optBoolean("overwrite", false), …)` 会让所有对台账/记忆文件的 `file_edit` 变红（`file_edit` 没有 append 语义）。详见 §4.1。
2. **D8 F-03 在 CI 单测里不可复现**：CI 用 JSON-java（`optString` 对 NULL 返回 fallback），缺陷只在 Android 运行期成立。测试因此锚「reader 查 `isNull`」，并用 `AndroidLikeJson` 固定 Android 口径；不要因为「本地/CI 跑绿」认为缺陷不成立。
3. **D9 F-05 的严重度被高估**：VM store 已经过 `resolveKey`，不会产生第二个 VM；急解析反而会分裂草稿。见 §4.6。
4. **D9 F-02 的「断连取消」不随 `runBlocking` 修复成立**：`handleConnection` 读取期间没有任何代码感知对端关闭。见 §4.4。
5. **D9 F-08 的一半已在 `[audit-0917]` 修过**（`DebugScreenshotRing`），本支修的是另一个副本 + 两处 `PixelCopy` 失败分支。
