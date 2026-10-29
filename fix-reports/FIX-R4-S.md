# FIX-R4-S · B12-b 崩溃 / 诊断面（6 条）

- 分支：`fix/r4-b12-diag`（基线 main `2103729f`）
- worktree：`/var/minis/shared/work/witness-1027/wt-r4-s`
- 改动面：**5 文件 +284/−80**（`crash/CrashFrequencyDetector.kt` · `diagnostics/{MemorySpikeRecorder,LaunchCycleBeacon,HangDetector,RenderAttributionDiag}.kt`），`scripts/scan/**` 零触碰
- 六笔提交：`de59fda4`(F-03) · `0c2a026c`(F-04) · `c40b77ec`(F-06) · `ccd7a3a3`(F-12) · `c6273ebd`(F-13) · `29bd728c`(F-15)，每条完成即 push

---

## 1. 条目清单与判级

| 条目 | 报告级别 | 本支判级 | 位置（本树实测） | 结论 |
|---|---|---|---|---|
| F-03 崩溃分享提示在 Activity 重建后丢失 + 应用自杀 | high | **P1** | `CrashFrequencyDetector.kt:339-417`（`maybeShowOnActivity`）· `:669-700`（`finishClose`） | 成立 |
| F-04 共享 SimpleDateFormat + finally 采样异常反噬 | medium | **P2** | `MemorySpikeRecorder.kt:122-129 / 236 / 247-266 / 268-287 / 500-537` | 成立（两半都成立） |
| F-06 保存崩溃包：主线程拷贝 / 失败无回滚 / 空包当成功 | medium | **P1（②）· P2（①③）** | `CrashFrequencyDetector.kt:920-975` | ② 成立并修；① 既有登记；③ 登记 |
| F-12 已自愈 stall 被判成异常退出 | medium | **P1** | `LaunchCycleBeacon.kt:254-292` + 新 `crashVerdict` | 成立（①）；② 登记 |
| F-13 render-breaker 与 hang 计数不在同一临界区 | low | **P2** | `HangDetector.kt:234-266 / 271-289 / 450-480` | 成立 |
| F-15 Q1 计数无同步（并发丢增量） | low | **P2** | `RenderAttributionDiag.kt:110-140` | 成立（StreamPerfMonitor 半边登记） |

判级依据用的是用户 2026-09-20 拍板的修复门：F-03/F-12 命中「用户可见的误导性体验 + 应用退出」，按 P1 修；F-04/F-06(①③)/F-13/F-15 是诊断面静默失真或窄触发，按 P2 处理（F-06② 因会在用户 Downloads 留垃圾文件，判 P1）。

## 2. 先证结论（本树重新定位，不采信报告行号）

**F-03 成立**。真码 `maybeShowOnActivity` 在**弹出对话框之前**做两件事：`pendingShareFiles = null`（L379 附近）+ `setDismissedAt(activity, now)`。`MainActivity` 在 manifest（L179-183）**没有** `android:configChanges`，转屏/折叠/主题切换会重建 Activity。重建后 `onCreate` 再次进入本函数：`pendingShareFiles` 已空 → 走 `onClosed?.invoke()` 分支，而 MainActivity 传的正是 `finish()`（`MainActivity.kt:205`）⇒ **提示丢失 + 应用退出**；且 dismissedAt 已在展示时落盘，本次 burst 的文件永远不再触发（1h 窗口靠 `mtime > dismissedAt` 过滤）。`MinisApp.onActivityResumed`（`MinisApp.kt:1035`）对同一 Activity 的第二次调用是今天的"免费"一次性保障——证据是它没有 onClosed 参数，所以当年靠"提前清空"掩盖了这条路径。

**F-04 成立（两半）**。`private val stamp = SimpleDateFormat(...)` 单个实例被三条线程路径共用（采样协程 / `measurePhase` 在被测代码所在线程 / `installFileSink` 闭包）；`installFileSink` 闭包里的 `day` / `file` 也是无保护可变状态。`measurePhase` / `measurePhaseSuspend` 的 finally 里 `write(formatEvent(clock(), …))`——`formatEvent` 在 `write` 的 try **之外**求值，故 finally 抛异常会顶掉 `block` 正在抛出的原始异常。补充：报告称有「ts==0 短路」，真树**不存在**该短路（`stampFormat` 直接格式化）。

**F-06 ② 成立**。`dispatchZippedSave` Q+ 分支 `resolver.insert()` 成功即建行，随后 `openOutputStream(...)` 返回 null 或 `copyTo` 中途 IOException，catch 只弹 toast 不回滚 ⇒ Downloads 留下 0 字节/截断的同名 `.zip`。①（同函数主线程做 MediaStore insert + 流拷贝）成立但属既有 backlog R3，按任务书不动；③（`packageZip` 在全部源文件读取失败时仍返回仅含 EOCD 的 zip）在另一函数，改动 >10 行，登记。

**F-12 ① 成立**。`classifyPrevious` 把 `crash-` / `native-crash-` / `stall-` 放进**同一个** filter，只要上一周期写过一条 stall 样本（`stall-<day>.log` 是 append-only，`lastModified` = 最后一次采样时刻）就返回 `crash_or_stall`。而 stall 样本只证明「发生过挂起」，不证明「进程异常结束」：`HangDetector.markHealthyTick` 在健康 10s 后会把计数归零，用户此后正常划掉应用 —— 判定却是异常退出 ⇒ Resume 横幅谎报 + `restartCount` 累加，连续四次正常重开被强制落到会话列表。**② 成立**（`writeStallSample` 从不写 `session=`，`readLastSessionId` 永远拿不到会话 id）；纯诊断字段，登记。

**F-13 成立**。`resetHangCount`（设置页手动重置）**完全无锁**地写 prefs 并翻转 `_renderBreakerActive.value = false`；`markHealthyTick` 的 breaker 置 false、`recordHang` 的 breaker 置 true 都在 `synchronized(hangCounterLock)` **之外**。reset 与 hang 并发可收敛到 `count=0` 且 `breaker=true`：主线程 markHealthyTick 对 `count==0` 早退，之后没有任何路径再清它 ⇒ 流式渲染永久降级成纯文本。报告引用的 `markHealthyTick(ctx)` / `healthyAccumMs` 在真树不存在（版本漂移），核心机制成立。

**F-15 成立**。`recordNewestCompose` 无 `@Synchronized` 直写 `typeCount/typeTotalMs/typeMaxMs/typeStreaming/typeMaxChars/windowEvents`，而 `emitComposeCensus` / `resetForTest` / `countSnapshotForTest` 都在对象监视器内读/清同一批数组 ⇒ 并发记录丢增量、census 可读到半应用更新。该文件 KDoc 的「no lock」是显式成本决策，因此修法必须同时改正这段口径，否则文档与实现相反。

## 3. 修法（每条的判据）

- **F-03** — burst 只在**用户真正收口**时才算处理完：
  - 新增 `promptHost: WeakReference<Activity>` + `promptInFlight`；`maybeShowOnActivity` 不再提前清空/落盘，改为「同一 Activity 重复 resume ⇒ 静默返回（不在弹窗后面 finish）；不同 Activity 实例（重建）⇒ 重新弹」。
  - `finishClose` 成为唯一收口点：清 `pendingShareFiles`、清归属、`setDismissedAt(now)`（context 取 dismissContext 或提示宿主），保留原有 24h suppress（仅显式 Dismiss 路径）。
  - 弹窗构造失败：清归属与 burst，但**不**落 dismissedAt（用户没看见，下次冷启动仍应提示）。
  - 判据：转屏后提示重新出现且应用不退出；用户 Dismiss/分享/保存后同一 burst 不再提示。
- **F-04** — 三处：
  - `stamp` 换成 `DateTimeFormatter`（不可变、线程安全；minSdk 26 原生 java.time，无 desugaring 依赖），输出格式逐字不变（`HH:mm:ss.SSS`，默认时区）。
  - `installFileSink` 的 `dayFmt/day/file` 解析→轮转→追加放同一把锁。
  - `measurePhase` / `measurePhaseSuspend` 的 finally 采样体包 `try/catch(_: Throwable)`：诊断失败不得替换被测路径的原始异常（`write` 自身已有 sink 级保护，这是补 finally 级）。
- **F-06②** — MediaStore 分支：insert 之后的任何失败先 `resolver.delete(target)` 再重抛（`runCatching` 包住回滚，回滚失败只记日志，不掩盖原异常）；API<29 的 `FileOutputStream` 路径同款删半截文件。判据：所有失败分支（空流 / 拷贝中途 IO / 异常）都不留可见残留。
- **F-12①** — 抽出纯函数 `crashVerdict(crashArtefacts, stallArtefacts, unrecoveredHangCount)`：崩溃文件=铁证；stall 只在 `hang_count > 0`（`markHealthyTick` 是唯一清零者，故 >0 = 挂起后没拿到 10s 健康期）时计入。prefs 读失败按 0（已自愈）——宁可漏报一次 stall，也不误报正常重开。KDoc 同步：`lastCycleWasCrash` 判读口径 + `silent_kill` 注释补齐「已自愈 stall 与后台杀同结论」。判据表见 §4（四条路径）。
- **F-13** — 三处写点的「计数 + breaker」放进同一临界区：`resetHangCount` 加 `hangCounterLock`；`markHealthyTick` 的 `value=false` 移入锁内；`recordHang` 的 trip（含 TRIPPED 日志，计数变量 `n`）移入自增块内。判据：任意 reset×hang 交错后 `breakerActive == (count>=2)`。
- **F-15** — `recordNewestCompose` 加 `@Synchronized`（对 `emitComposeCensus` 递归可重入），并把文件头「no lock」改写为「无分配 + 一次无争用锁，与它保护的数组写同量级」。

## 4. 验证读数

**JVM 契约探针**（`probe-s`：编译**真源码** + 8 个桩；before 臂 = 全树 `2103729f`，after 臂 = 本分支，同一套 harness）：

| 判据 | before（基线） | after（本支） |
|---|---|---|
| F-04 8 线程 × 400 次 `formatEvent/formatSample` 时间戳正确性 | **FAIL** — 1376 / 1596 / 1790 次串号（三次采样），异常 0 | **PASS** — 0 |
| F-04 采样侧异常不得替换被测异常 | **FAIL** — 原始 `IllegalStateException` 被采样侧 `UnsupportedOperationException` 顶掉 | **PASS** — 原始异常透出 |
| F-12 正常冷启动 → silent_kill | PASS | PASS |
| F-12 真崩溃文件 → crash_or_stall | PASS | PASS |
| F-12 **已自愈 stall（hang_count=0）→ silent_kill** | **FAIL** — 判成 `crash_or_stall (stall-…)` | **PASS** |
| F-12 未恢复 stall（hang_count=2）→ crash_or_stall | PASS | PASS |
| F-13 200 轮 reset×hang 竞态后 breaker 与计数一致 | **FAIL** — 21/200 违例（首个 `count=0 breakerActive=true`） | **PASS** — 0/200 |
| F-15 8 线程 × 500 = 4000 次 compose 全量入账 | **FAIL** — 计入 3953（丢失 47） | **PASS** — 4000 |

探针说明：`kotlinx.coroutines.flow.MutableStateFlow` 用桩实现并在 `value` 读写里加 2ms 延迟放大 F-13 的窗口——修复后该延迟只是串行化临界区，判据同样成立（不是靠延迟造出来的假绿：after 臂锁内赋值，任何交错都以最后一个持锁者为准）。

**静态门**（只跑与改动直接相关的 4 道，未跑全量）：

| 门 | 退出码 | 读数 |
|---|---|---|
| `share_dir_prune_check` | 0 | arm1–arm6 全绿（arm4 钉 `packageZip` 形状，未触碰） |
| `discarded_return_guard` | 0 | 四段全绿 |
| `liveness_static_guard` | 0 | method coverage / RARE freshness / call-site 全 clean |
| `rss_probe_lock_scope_check` | 0 | `GATE=GREEN` |

语法自查：`kotlinc` 逐文件过滤 `expecting|unexpected|syntax|Unclosed` = 0；括号配平 `{}` 172/172、`()` 467/467（`CrashFrequencyDetector`）。

## 5. CI 读数

- dispatch：`build-apk.yml` @ `fix/r4-b12-diag`，run created `2026-10-29T00:58:12Z`
- （待 CI 完成回填：run id / conclusion / head_sha 逐字符比对 / 27 步非 success 清单 / 门集与夹具读数）

## 6. 残留（三件套）

| # | 残留 | 触发条件 | 方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | F-03：提示宿主是**单槽** `promptHost` | 将来出现第二个可承载提示的 Activity（分屏 / 多窗口 / 新增 Activity）且两者同一进程并存 | 改成 `WeakHashMap<Activity, …>` 或迁移成 `DialogFragment`（随 Activity 生命周期重建） | 当前 `MainActivity` 是唯一 launcher（`singleTask`），单槽足够；提前改造会引入 `DialogFragment` 的依赖面 |
| R2 | F-06①：`dispatchZippedSave` 仍在 Main 作用域做 MediaStore insert + 流拷贝 | 崩溃包体积大（数十 MB）+ 低端机 | 搬进 `withContext(Dispatchers.IO)`，主线程只保留 chooser/toast | backlog 既有 R3 已登记；`resolver` 跨线程改动面大，且 `packageZip` 已承担主要 IO |
| R3 | F-06③：`packageZip` 在所有源文件遍历中读取失败时返回仅含 EOCD 的 zip，仍弹「保存成功」 | 崩溃文件被外部清理 / 权限丢失 | 加 `entriesWritten` 计数，为 0 时删 zip 返回 null（走既有失败分支） | 另一函数，改动 >10 行（本任务判据）；实际触发需要日志目录被并发清理 |
| R4 | F-12②：`readLastSessionId` 恒返回 `unknown` | 有人从 `launch-beacon.log` 的 Perd 行取会话 id 排查问题 | `writeStallSample` 头部写 `session=`，需把当前会话 id 从会话层传进 `HangDetector`（跨文件） | 纯诊断字段，无用户可见影响；跨文件传参属别的支的半径 |
| R5 | F-12①：prefs 读失败时按「已自愈」处理 | SharedPreferences 损坏 | 需要独立信号（如 beacon 里记录 stall 的"已恢复"标记）才能保守判定 | 诊断宽容优于误判用户（误判会弹横幅 + 强制回桌面），且崩溃侧仍有 `crash-*.log` 铁证 |
| R6 | F-13：`recordHang` 把 TRIPPED 日志写在锁内 | 日志 sink 写盘 >10ms | 锁内只置局部布尔，锁外打印 | `AppLogger` 已批量写，实测无感；留作观察项 |
| R7 | F-15 后半：`StreamPerfMonitor` 全部可变状态无同步 | 多线程调用其记录入口 | 同 F-15 手法加同步 | **不在本支文件集**（任务书按文件划分支），登记给后续支 |
| R8 | F-03：burst 存续期间进程被杀 | 提示未处理时进程被系统回收 | 无需修——未落 dismissedAt，下次冷启动会重新提示 | 这是修复后的预期行为 |

## 7. 与报告的差异

1. **F-03**：机制成立；报告行号 `:344` 实际是 `:339`（函数签名），关键语句在 `:379`（清空 + 落盘）。报告称「`activeSaveLauncher`」，真树是 `saveLauncher: (zip: File, onSaveDone: () -> Unit) -> Unit` 参数（`MainActivity` 的 `crashSaveLauncher`），语义一致但形态不同。报告未提 `MinisApp.onActivityResumed` 这个第二调用点——它是"同 Activity 重复 resume"必须静默返回的原因。
2. **F-04**：成立；报告称有「ts==0 短路」，本树**没有**（`stampFormat` 直接格式化）。`installFileSink` 的轮转在真树是就地 `renameTo`，不是 `rotateRing(...)` 调用（报告按另一版本写）。
3. **F-06**：②成立；③的形态是「遍历中逐个 `catch` 后仍返回 zip」，报告描述为"空 zip"，等价。①成立但属既有登记。
4. **F-12**：机制成立；报告描述为两个分支（`recentStall` + `maxByOrNull`），真树是同一 filter 的三前缀列表。②成立（`readLastSessionId` 只滤 `stall-` 且 stall 文件不含 `session=`）。
5. **F-13**：核心成立（`resetHangCount` 无锁 + 三处锁外翻转）；报告引用的 `markHealthyTick(ctx)`、`healthyAccumMs` 在真树不存在（真签名无参、无该字段），说明报告基于另一版本树。
6. **F-15**：核心成立（无同步 + KDoc 明写 no lock）；报告签名 `recordNewestCompose(ctx, blockType: String, …)` 与真树 `(sessionId, type: ItemType, streaming, chars)` 不同。报告把 `StreamPerfMonitor` 并入本条，但该文件不在本支文件集 → §6 R7。

## 8. 真机未验清单

- F-03：转屏/折叠重建后提示是否真的重新出现（需真机 + 制造一次崩溃 burst）；本支证据 = 源码 + 状态机推演，无 UI 级自动化。
- F-06②：MediaStore 回滚在真机上是否留空条目（需制造磁盘满 / 中断）。
- F-12：真实 MIUI 场景下"已自愈 stall + 划掉"是否不再弹横幅，以及 `hang_count` 在跨进程的真实取值时序。
- F-13：真机上 200 轮竞态不可复现，只有 JVM 双臂读数；实际降级概率取决于 watchdog 与主线程的交错频率。
- F-15：真机单线程 compose 路径下的锁开销（预期为无争用的 lock/unlock）。
