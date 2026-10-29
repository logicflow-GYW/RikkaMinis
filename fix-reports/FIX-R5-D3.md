# FIX-R5-D3 — 卡顿自愈：看门狗正常心跳分支自己清零 hang count（D-F-03 · C-10，P1）

- 分支：`fix/r5-d3-hang-heal`（worktree `/var/minis/shared/work/r5-1029/wt-d3`）
- 基线：main `f3def6ce`（R5 修后复扫批，零漂移）
- 来源：`reports/R5-D.md` D-F-03（M9）／C-10（PARTIAL 的残留），判级 `TRIAGE.md` → **P1**
- 改动面：生产 **1** 文件（`diagnostics/HangDetector.kt` +61/−8）+ 测试 **1** 文件（`HangDetectorSelfHealTest.kt` +286）
- `scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰；门 113 / 夹具 349 不变（只跑了提到 `src/test` 的 3 道门点名确认，见 §4）

## 1. 一句话：这改变什么用户行为？

一次**已经自愈**的 3 秒卡顿不再让下次启动报警：不再出现失实的「上次异常退出」横幅，
也不再累积到 4 次后强制把用户从原会话拉回首页；顺带，明确闭合了
「卡顿两次后流式 Markdown 降级为纯文本、此后再也回不去」的那条路
（`_renderBreakerActive` 与计数同锁清零，自愈时一起释放）。

## 2. 先证（按语义复核真码，再动手）

报告给的行号与机制**核对成立**，且第二层后果比报告写的更重。机械取证：

| 判据 | 取证结果 |
|---|---|
| `markHealthyTick` 生产调用点 | `rg -n markHealthyTick -g '*.kt' src/android/app/src` → **只有** `ui/chat/ChatScreen.kt:385`（其余是 KDoc 引用） |
| 那个调用点的形态 | `LaunchedEffect(sessionId) { delay(10_000); HangDetector.markHealthyTick() }` —— 挂载后跑一次即退出，**不随会话内时间重跑** |
| `resetHangCount` 调用点 | 只有定义行 `HangDetector.kt:279`，**生产 0 调用点** |
| `watchLoop` 恢复路径 | `onHeartbeatLanded` 分支只写 `post-recovery` 样本 + 一条日志，**不调** `markHealthyTick` |
| 时序算术 | 卡顿在挂载后 `t_hang`，`recordHang` 记 `KEY_LAST_HANG_AT = t_hang`（且 `t_hang ≥ HANG_THRESHOLD_MS = 3s`）。tick 在 `t = 10s`：若 `t_hang ≤ 10s` 则 `now − lastHangAt = 10 − t_hang ≤ 7s < RESET_AFTER_QUIET_MS(10s)` ⇒ `markHealthyTick` 第二道早退；若 `t_hang > 10s` ⇒ 那次 tick 早已跑完，**再无调用** |
| 后果 | `KEY_HANG_COUNT` 在本进程内恒 `> 0` ⇒ 下次启动 `LaunchCycleBeacon.classifyPrevious` 读到 `unrecoveredHangCount > 0` + `stall-*.log` ⇒ `crash_or_stall`；`>= RENDER_DEGRADE_HANG_COUNT(2)` 时 `_renderBreakerActive` 也再无释放路径 |

**第二层（报告没写的）**：`markHealthyTick` 是同锁清零 `_renderBreakerActive` 的唯一入口，
所以「卡顿 → 渲染降级 → 永远降级」与计数不归零是**同一个缺口的两面**，不是两条缺陷。

结论：D-F-03 成立，P1 判级成立（可感知体验降级 + 误导性错误信息，非数据损坏）。

## 3. 修法（为什么不照抄报告的补丁建议）

报告的建议是「在 `scheduleHeartbeat`／`watchLoop` 的正常心跳分支里，距 `lastHangAt` 超过
`RESET_AFTER_QUIET_MS` 时调 `markHealthyTick()`」。方向对，但**照抄第一版就会引入一条新缺陷**，
我在实现时撞上了，记在这里：

### 3.1 不能无条件在健康分支调 `markHealthyTick()`

`watchLoop` 的第一个健康 tick 发生在 `start()` 之后 **500ms**，而
`shouldForceHomeOnLaunch` 由 `MainActivity.kt:335` / `AppNavigation.kt:553` 在**冷启动组合期**读取。
若健康分支无条件调 `markHealthyTick()`，一个「上次启动在卡顿上死掉」的进程会在自己启动后
半秒内把**上一进程遗留的计数**清零（`now − lastHangAt` 早已远超 10s），于是
`shouldForceHomeOnLaunch`（阈值 3）在首帧就可能读到 0 —— **静默废掉「前几次启动都在卡」这道闸**。
这是典型的「修一个问题、引入另一个问题」。

### 3.2 实际实现

1. **`watchLoop` 健康分支新增一行** `healQuietHang()`（`since < HANG_THRESHOLD_MS`，即「心跳已落地、
   主线程此刻是响应的」，位置在 `HANG` 分相之前、`continue` 之前）。
2. **新增 `healQuietHang()`**（私有，watchdog 线程）：
   `if (!hangCountedThisProcess.get()) return` → `markHealthyTick()` → `if (currentHangCount(ctx) == 0) hangCountedThisProcess.set(false)`。
   静默期规则、计数清零、`_renderBreakerActive` 翻转**全部留在 `markHealthyTick` 里**，因此仍然
   只在 `hangCounterLock` 同一临界区内发生（与 `recordHang` 的读改写互斥）——没有第二份静默期判据。
3. **新增进程内标记 `hangCountedThisProcess`**，在 `recordHang` 的 `synchronized(hangCounterLock)` 块内、
   随计数一起置位。语义：**自愈只清「本进程记下的」计数**；上一进程遗留的计数保持原样交给冷启动断路器
   （§3.1 的洞由此关闭）。标志在计数读回 0 后自行清除，健康心跳路径不产生 prefs 读。
4. **不动 `resetHangCount`**（任务书 P3）：手动重置的语义是「立即清」，自愈的语义是「静默期后清」，
   两者不同，合并会改掉设置页那个按钮的行为。
5. **不动 `ChatScreen`**（C4 独占）：自愈完全在 `HangDetector` 内闭环，`ChatScreen` 的那次 tick 保留为
   附加快路径（早退，零成本）。

修法一句话可复核：**卡顿后只要主线程连续响应满 10 秒，看门狗就自己把计数与降级开关清掉；
上一进程遗留的计数不在此列。**

## 4. 验证

| 装置 | 命令 | 结果 |
|---|---|---|
| 修前源树（pre）跑同一套判据 | `sh probe-d3/run.sh` 生成的 jar，cwd 指向 `git show HEAD:…/HangDetector.kt` 的解包目录 | **7 例 3 红**——三条接线判据全红（`assertHealIsWired` 等），三条 `crashVerdict` 判定 + 一条负控自检绿（判据本身没瞎） |
| 修后（post） | `sh probe-d3/run.sh` | **OK (7 tests)** |
| 编译级 | `sh probe-d3/compile_check.sh`（Android 桩：Context/SharedPreferences、Looper/Handler、Log、StateFlow、MinisApp、ContentDiag） | **COMPILE_CHECK=PASS** |
| 相关门点名 | `python3 scripts/scan/{dao_loadmessages_check,debug_leak_guard,legacy_pipeline_guard}.py .`（三门是仅有的提到 `src/test` 的门） | 全 `rc=0` |
| 云端 CI | build-apk run [`40853836266`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40853836266) | **success**（09:58:37 → 10:20:17，21.7 min，27 步；唯一非 success = 第 23「Move the rolling tag」+ 第 24「Publish to Releases」两处 skipped = 分支门控，符合预期）（详见 §6） |

判据清单（`HangDetectorSelfHealTest`，7 例）：

| 用例 | 钉什么 |
|---|---|
| `watchdog self-heals from its healthy heartbeat branch` | `healQuietHang()` 的**调用点**（不是定义）位于 `if (since < HANG_THRESHOLD_MS) {` 与 `if (phase == BackgroundFreezePolicy.Phase.HANG) {` 之间 |
| `the heal is the only in-file caller of the quiet-period reset` | `markHealthyTick()` 在本文件内恰 2 次（定义 + 自愈内调用），且调用落在 `healQuietHang` 的函数体内（括号配平判界） |
| `the process-local flag flips together with the counter under the lock` | `hangCountedThisProcess.set(true)` 恰 1 次，在 `recordHang` 内、在 `synchronized(hangCounterLock) {` 的括号深度 ≥ 1 处（剥掉行注释后逐字符数括号） |
| `a self-healed stall no longer reads as an abnormal exit` | `crashVerdict(emptyList(), ["stall-2026-10-29.log"], 0) == null` |
| `an unrecovered stall still reads as crash_or_stall` | 同参数 `unrecoveredHangCount = 1` → `"crash_or_stall (stall-2026-10-29.log)"` |
| `a real crash artefact stays abnormal even with a settled counter` | `["crash-2026-10-29.log"]` + count 0 → 仍判异常 |
| `the wire checks reject a source with the self-heal removed` | 负控三联：删自愈调用 / 删自愈里的清零 / 删标志写，**各自被对应判据拒绝**（并先断言变异真的发生了，否则报复「变异没生效」） |

期望值全部写死字面量（`"crash_or_stall (stall-2026-10-29.log)"` 等），不由生产常量推导。

沙箱装置（不入库，在 `shared/work/r5-1029/probe-d3/`）：`run.sh`（逐字切片 `crashVerdict` 做 shim →
真实测试文件 + shim 一起 kotlinc → JUnitCore，cwd = app 模块目录以解析 `src/main/...` 相对路径）、
`extract_verdict.py`（切片并回读断言「切出的文本在源文件里逐字存在」）、`compile_check.sh` + `stub/`、
`pre/`（修前源树副本）。

## 5. 负例矩阵（自愈不能变成「什么都清」）

| 负例 | 期望 | 为什么安全 |
|---|---|---|
| 上一进程遗留计数（本进程无 `recordHang`） | **不清** | 标志从未置位；冷启动断路器 `shouldForceHomeOnLaunch` 语义不变（§3.1） |
| 真 crash 文件 + count 0 | 仍 `crash_or_stall` | `crashVerdict` 先判 crash 分支 |
| 卡顿后未满 10s 就退出/被杀 | 计数保留 | `markHealthyTick` 的 `RESET_AFTER_QUIET_MS` 早退；这类周期**确实**没恢复满 10s，报异常是诚实的 |
| 卡顿期间（`since ≥ 3s`） | 不调自愈 | 自愈只在 `since < HANG_THRESHOLD_MS` 分支；主线程卡着时不存在「响应」 |
| 手动重置（设置页） | 仍立即清零 | `resetHangCount` 一行未改 |
| ChatScreen 入场 tick | 仍在 | 未改；与自愈并发时同锁，先到先清、后到早退 |

## 6. 云端 CI

- run [`40853836266`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40853836266)：**success**（09:58:37 → 10:20:17，21.7 min，27 步；唯一非 success = 第 23「Move the rolling tag」+ 第 24「Publish to Releases」两处 skipped = 分支门控，符合预期）
- `head_sha` = `e946ec0e446f409179c9a6f3e72c84f0eb367066`（与本地 `e946ec0e` 逐字符一致）
- 日志 1,627,001 B / NUL 0；`Summary: 113 passed, 0 failed` 恰 1 次、`ALL 349 CASES PASS` 恰 1 次、
  `^ e: `（Kotlin 编译错误行）0 条、真 `FAILED` 行 0 条（39 处 "skipped" 全是分支门控步骤名与 pass 叙述）
- 新用例在日志里逐条 `PASSED`：`watchdog self-heals from its healthy heartbeat branch`、
  `the heal is the only in-file caller of the quiet-period reset`、
  `the process-local flag flips together with the counter under the lock`、
  `a self-healed stall no longer reads as an abnormal exit`、
  `an unrecovered stall still reads as crash_or_stall`、
  `a real crash artefact stays abnormal even with a settled counter`、
  `the wire checks reject a source with the self-heal removed`

## 7. 残留（登记用，均不在本支半径内）

- **R1 上一进程遗留的计数仍只能靠 ChatScreen tick 清**（进聊天页满 10s）。属既有行为：本支刻意不扩
  到「启动后自愈」，因为那正是 §3.1 会关掉冷启动断路器的那个洞。触发条件：真机出现「上次启动卡过、
  重开后长时间停在首页也不恢复」的真实案例（当前无证据）。
- **R2 健康 tick 每 500ms 读一次进程内 `AtomicBoolean`**（标志置位期间才会读 prefs）。开销可忽略，
  但确实在 watchdog 线程上；若要进一步收敛，得把自愈节拍降到 1–5s（会拉长「静默期满 → 实际清零」
  的延迟，且收益只有几次内存读）——按装置比例原则不做。
- **R3 真机未验**：全部证据 = JVM 形状探针 + 纯函数判定 + Android 桩编译级 + 云端 CI 编译/单测级。
  真机上的实际时序（HyperOS 后台冻结与 watchLoop 线程调度的交错）没有取到证据。
- **R4 C-10 的其余三国**（`MemorySpikeRecorder` 共享 formatter、`RenderAttributionDiag` 无锁增量、
  `HangDetector` 锁内一致性）报告自述在 `f3def6ce` 已闭合，本支不重复取证。

## 8. 未触碰的边界

- `HangDetector.resetHangCount`（任务书定 P3，且与自愈语义不同）。
- `ui/chat/ChatScreen.kt`（C4 独占文件）：只读核对过 `:383-386` 的调用点，一行未改。
- `diagnostics/LaunchCycleBeacon.kt`（本支允许的第二文件，**判断为不需要改**）：它的判据
  `crashVerdict` 在「计数语义正确」的前提下本身是对的（`f3def6ce` 的 F-12 拆分已经做完）；其 KDoc
  写的「only `markHealthyTick` zeroes the counter」在修后依然成立，反而第一次真正成立。改它只会扩半径。
- `scripts/scan/**`、`scan.sh`、`test_scan.py`、`strings.xml`（无新用户可见文案）。

门 113 / 夹具 349 未变；分支 CI 绿 = 本支停止节点，未合 main。
