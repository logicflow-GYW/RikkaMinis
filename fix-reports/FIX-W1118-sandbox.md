# FIX-W1118-sandbox — WAVE-1118 D3（沙箱域）+ R3（沙箱回收环）

- **分支**：`fix/w1118-sandbox`（worktree `shared/work/incr1118/wt2`）
- **基线**：`c85131ad`（= 当前 main，已含 FIX-1117B 五支）
- **判级口径**：`fix/POLICY.md` 的修复门（P0/P1/P2/P3），**不照抄报告 severity**
- **改动范围**：`sandbox/**` + 对应测试；未触碰 `scripts/scan/**`、`service/**`、`agent/**`、`provider/**`、`ui/**`
- **本域证据形态**：真权限/真进程不可用于 CI，故证据取「生产文件编译出的真实代码 + 真实协程/真实文件系统/真实 fork 进程」三条独立通道，而非「CI 绿」

---

## 1. 判级表（本支全部条目）

| ID | 一句话 | 报告 severity | **本支判级** | 结论 | 去向 |
|---|---|---|---|---|---|
| D3-F02 | 流式 `finally` 的清理在取消时被跳过 → 无 `client.ack`、目录不回收、worker 空等 45s | high | **P1**（用户每次点「停止」必现；可感知的资源/延迟降级） | **已修** | 本支 |
| D3-F03 | 非流式轮询被取消时跳过 `writeCancel` + 清理 → worker 继续跑（计费）+ 无法分类的孤儿目录 | high | **P1**（取消一次长生成必现；多余计费 + 孤儿） | **已修** | 本支 |
| D3-F01 | `buildSessionBindMounts` 只读冷缓存 `resolvedHostPath`，与实际挂载快照漂移 → agent shell 看不见已挂载的外部文件夹 | high | **P2**（触发面 = 持久化 JSON 早于该字段的挂载条目；无错误提示的静默分裂视图） | **已修**（见 §2.3「为什么修 P2」） | 本支 |
| D3-F04 | `execute()` 4 条早退 + 2 条取消路径漏 `MemorySpikeRecorder.onCommandEndIfPending` | medium | **P2**（只污染内存归因日志，无用户行为变化） | **不修** | §3 登记 |
| D3-F05 | `BrowserUseOffloadHandler` 用 `File(dir, fetchedFileName)` 写下载文件，无穿越校验 | medium | **P3**（**现状有守卫**：`fetchedFileName` 由 `BrowserUtil.extensionForMimeType` 白名单生成，形态恒为 `fetch_<ms>.<ext>`，不含分隔符） | **不修** | §3 登记（触发条件=将来出现别的写入者） |
| D3-F06 | `image_url` 的 `file://` 未复用 `stripFileUrlScheme` + `resolveMaybeRelative`（与 `--output` 不一致） | low | **P2**（触发面 = 写成 `file://相对路径`；结果是误导性的 “Image file not found”） | **不修** | §3 登记（方案已写死，3 行） |
| R3-F01 | `stop()` 的 reaper 丢弃 `waitFor(2s)` 返回值、且 `process` 已置空 → 存活 tracer 在全部可观测面上消失 | high | **P2**（触发面 = SIGKILL 后 2s 仍在的 D-state/ptrace-stopped 树；后果是进程泄漏不可见） | **已修** | 本支 |
| R3-F02 | `cleanupProotTmp()` 的 host 段无年龄/活跃门控（且 `isBusy` 看不到终端与 apk 修复），guest 段删不掉时静默 | medium | **P2**（机制级：`PROOT_TMP_DIR` 三消费者共用；损坏路径需 embedded-loader fallback 才会落到这个目录） | **已修** | 本支 |
| R3-F03 | `installGeneration` 未从磁盘 `readBootId` 恢复 → 重启后重置仍写 `1`，boot-id 不单调 | medium | **P2**（**当前无消费方**：全仓 `readBootId` 0 调用） | **不修** | §3 登记 |
| R3-F04 | `TerminalSession.killTermuxProcessTree` 未校验 `starttime`、无退出回读 | low | **P2**（PID 复用窗口；且只能误杀同 uid 的自家进程，含 app 自身） | **不修** | §3 登记（复用现成 `internalKillProcessTree`） |
| R3-F05 | `postRecycleMemoryRecoveryNow()` 的 `floor = 120L` 未对齐动态高水位 `512L` | low | **P3**（**刻意设计**：120MB 是「回收目标」，512MB 是「准入红线」，两把尺；报告把两者当同一阈值） | **不修（反驳报告）** | §3 注明 |

**计数**：修 5（2×P1 + 3×P2）；登记 4（P2）；不修/反驳 2（P3）。

---

## 2. 逐条证据

### 2.0 先证装置（`shared/work/incr1118/t2probe/`）

| 文件 | 用途 |
|---|---|
| `Probe.kt` | **pre 臂**：生产代码基线的逐行转写（`finally` 裸调用 / `withTimeoutOrNull` 外无 `try`），真协程真取消 |
| `ProbePost.kt` | **post 臂**：同场景，清理走 `CancelSafeCleanup.kt` + `ModelExecutionDispatcher.kt` 里**编译出的生产代码** |
| `ProbeFs.kt` | **真文件系统 + 真 fork**：新的 host 清扫循环体在真树上跑；`internalAwaitProcessGone` 对真 `sleep` 进程验证 |
| `extract.py` / `run.sh` / `mutate.sh` | 从生产文件按符号抽取纯函数（非重写）；28 条 JVM 单测；10 条变异 |

**pre 臂实测（基线）**：

```
PASS A stream-pre: cancel 标记写出 — cancel=true
PASS A stream-pre: client.ack 从未写出（缺陷）
PASS A stream-pre: run 目录未被回收（缺陷）
PASS B dispatch-pre: cancel 标记被跳过（缺陷）
PASS B dispatch-pre: poll 之后的清理整段未执行（缺陷）
```

即：**流式**里同步写 `cancel` 能成，但随后的 `delay` 轮询立即抛 `CancellationException` → `client.ack` 与删目录永不执行；**非流式**里连 `cancel` 都没写出去（整段 post-poll 被跳过）。这两条把报告的核心主张坐实了（报告是逻辑推断，这里是实测）。

**post 臂实测（修复后，生产代码）**：

```
PASS A stream-post: client.ack 写出 / run 目录被回收
PASS B dispatch-post: cancel 标记写出 / 清理执行
PASS C predicate: 死 worker 不取消
ALL POST-ARM EXPECTATIONS MET
```

**真 FS / 真进程实测**：

```
PASS 新鲜条目保留                  (5s 内的 loader 不再被删)
PASS 陈旧文件被回收                removed=[aged-junk, link-to-outside]
PASS 陈旧但含活跃子项的目录保留     (父 mtime 不动 = 写盲区)
PASS 符号链接按链接删除（本身没了）
PASS 符号链接目标未被波及
PASS 活进程 200ms 内不会被判为已回收   (真 sleep 进程, verdict=false)
PASS 被 SIGKILL 后能判为已回收        (verdict=true)
PASS pre 臂：刚写的 loader 被立刻删掉（缺陷）   ← 基线循环体的实测，removed=1
```

### 2.1 D3-F02 · 流式 finally 在取消下不清理

- **改法**：新增 `sandbox/offload/CancelSafeCleanup.kt` 的 `internalRunCancelCleanup(writeCancel, cleanup)`，两步都在 `withContext(NonCancellable)` 内；`ChatStreamOffloadHandler.stream` 的 `finally` 改为调用它（`cancel`/`cancel.death` 的选择与 `!terminalSeen` 守卫原样搬进 `writeCancel` 闭包）。
- **用户行为变化**：点「停止」后 worker 立刻释放（不再空等 ack 15s + drain 30s），`run-*` 目录当场回收 → 连续对话时的启动延迟与 native 峰值下降。最坏阻塞 = `CANCEL_ACK_TIMEOUT_MS + WORKER_EXIT_WAIT_MS` ≈ 11s，且跑在 `Dispatchers.IO`（`flowOn(IO)`）；常见情形是毫秒级（worker 收到 cancel 立即回 terminal）。
- **测试**：`CancelSafeCleanupTest`
  - `cleanup runs to completion even when the calling job is cancelled`（真取消，断言 **delay 之后**的 `settled` 与 helper 返回值）
  - `an unwrapped suspending cleanup is skipped — the negative control`（同一文件里放基线形态，证明这条用例有牙）
  - `a throwing cleanup is reported, not swallowed silently` / `a throwing cancel-write does not block the wait`
  - `the streaming finally routes its worker wait through the helper`（剥注释后的源码形状）
- **变异**：M1（helper 去掉 `NonCancellable`）→ 只红第 1 条；M2（finally 退回裸调用）→ 只红 wiring 那条。
  - **注**：M1 第一次跑是**绿的**——原断言只验 `writeCancel` 跑了（非挂起），`runCatching` 又把 `CancellationException` 吞成 false。已把断言移到 `delay` **之后**并加返回值断言，M1 才精确红。这条自查记录下来：**「取消场景的断言必须落在第一个挂起点之后」**。

### 2.2 D3-F03 · 非流式轮询被取消时跳过 cancel

- **改法**：`dispatchOnce` 的 poll + post-poll 整段包进 `try { … } finally { … }`；`finally` 里 `internalShouldCancelAbandonedRun(pollFinished, workerDied)` 为真时，`internalRunCancelCleanup { writeCancel; awaitTerminal + waitForWorkerReap + delete }`。
  - `pollFinished` 在 **poll 结束的那一刻**（post-poll 判定之前）置位 —— 这样「结果已拿到、post-poll 正在 settle 时被取消」不会给一个已经答复的 worker 再塞一个 `cancel`（那会把它误标成「用户丢弃」）。
  - `workerDied == true` 时仍不写 cancel（没人会读，且原有语义是留给 reaper 当孤儿）。
- **用户行为变化**：取消一次长生成后 worker 停止上游请求（不再计费/耗流量），且留下的是**可分类**的目录（有 `cancel`），reaper 能回收。
- **测试**：`ModelExecutionAbandonedPollTest` — 判据真值表 3 条 + wiring 2 条（其中一条断言 `pollFinished = true` **位于** `if (result != null)` **之前**：位置才是契约，token 存在不算）。
- **变异**：M3a（判据丢掉 `workerDied`）→ 红「死 worker 留给 reaper」；M3b（`finally` 分支恒假）→ 红 wiring。

### 2.3 D3-F01 · 只读冷缓存导致挂载视图分裂（修 P2 的理由）

- **先证**（读码 + 历史）：`Entry.resolvedHostPath` 只由 `MountedFoldersStore.add()` 写；`loadFromDisk()` 反序列化时字段缺失即 `null`，**全仓无任何回填/重解析**；`add()` 对解析失败的 URI 直接拒绝，所以 `null` 只来自「持久化 JSON 早于该字段」的历史条目。而 `applyMountedFoldersSnapshot` 是**按需现场解析**进 `PRootKernel.bindMounts`，终端 / `minis-fastio` / `file_read` 都走它。⇒ 同一台设备上四个入口看到两套视图（INV-01 违反）。
- **改法**：`buildSessionBindMounts` 在 store 段之后追加 `internalExternalMountFallbacks(mounts, PRootKernel.bindMounts)`，只补 `/var/minis/mounts/…` 前缀且 store 未覆盖的键；**store 的值永远优先**，纯 additive，不会重指已有挂载，也不搬 `/var/minis/{shared,logs,…}`。
- **用户行为变化**：挂载过的外部文件夹对 agent 的 `shell_execute` 也可见（与终端/读文件一致），不再出现「终端里能看到、agent 说文件夹是空的」。
- **为什么修 P2**：触发面是历史数据（窄触发）→ 按修复门本应登记；但它 ①是任务书 §本轮重点 3，②成本 6 行纯 additive + 一个纯函数表，③把 INV-01 从「违反」拉回「成立」——主会话若判「应严格登记」，回滚这 6 行 + `ExternalMountFallbackTest` 即可，测试会一起消失。
- **测试**：`ExternalMountFallbackTest`（冷缓存补齐 / store 优先 / 非 mounts 前缀忽略 / 空 host 忽略 + wiring）。
- **变异**：M4a（删掉合并调用）→ 红 wiring；M4b（允许快照覆盖 store）→ 红「store 的值优先」。

### 2.4 R3-F01 · `stop()` 的回收环 EFFECT 边开环

- **改法**：`stop()` 的 reaper 改为 `val gone = internalAwaitProcessGone(proc, REAP_WAIT_MS)`，`!gone` 时 `Log.e` 带 pid 与原因；`REAP_WAIT_MS = 2_000L` 提为具名常量（含「为什么不是更短/更长」）。
- **用户行为变化**：无直接可见变化（不阻塞 UI、不改超时）；变化是**可诊断性**——回收失败的 tracer 现在会留一条 error，不再「shell 说停了、进程还在」而无处可查。
- **测试**：`PersistentShellReapTest` — 假 `Process` 四条（窗内退出 / 超窗存活 / waitFor 说真但 isAlive 仍真 / waitFor 抛 InterruptedException）+ wiring 两条（`.waitFor(` 在文件内**只允许出现一次**且在 helper 内、`val exited =` 绑定；`REAP_WAIT_MS` 具名）。
- **门 ROT 事件（CI #3221 抓到的）**：既有邻居用例 `PersistentShellTreeTerminationTest > stop wires explicit SIGKILL …` 把断言锚在**字面量** `proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)` 上，我改掉这行它就红——这是「锚拼写而非锚决策」的典型。按改门三件齐处理：①断言改锚到决策（有界等待仍在异步 reaper 上、预算必须是具名常量 `REAP_WAIT_MS`）②补负向断言（`try { proc.waitFor(` 不得回来）③真树重跑（42 条全绿）+ 变异 M5a 同时翻红这两条。
- **变异**：M5a（reaper 退回丢弃返回值）→ 红线数断言（出现 2 个 `waitFor`）；M5b（verdict 只看 `waitFor`）→ 红「isAlive 仍真」。真进程臂：活 `sleep` → `false`，`destroyForcibly` 后 → `true`。

### 2.5 R3-F02 · `proot-tmp` 的 host 段无门控

- **先证**（读码）：`PROOT_TMP_DIR` 三处消费者 = `PersistentShell`、`TerminalSession`（PTY）、`RootfsManager.prootLoaderEnv()`（apk 修复子进程）；而清扫门只有 `shells.values.any { it.isBusy }`。且 host 段**没有任何年龄判据**（guest 段有）。实测基线循环体：刚写的 `fresh-loader` 下一次 tick 即被删（`removed=1`）。
- **改法**：host 段与 guest 段对齐 —— 年龄门控（`PROOT_TMP_HOST_MAX_AGE_MS = 5 分钟`，纯判据 `internalProotTmpEntryReapable`）+ 活跃度探测（复用 `internalGuestTmpNewestActivityMs` / `internalGuestTmpLooksLive` / `.minis-keep`）+ 无跟随删除（`internalGuestTmpDeleteTreeNoFollow`，符号链接按链接 unlink）。`sweepGuestTmpDir` 删不掉且无法下钻的分支改为 `Log.w`（原来静默 `false`）。
- **用户行为变化**：`proot-tmp` 里 5 分钟内的文件不再被无条件删除（终端/`apk fix` 与 agent 命令并发时不会互删）；删不掉的顽固条目留痕可查。
- **诚实边界**：正常启动路径都会显式设 `PROOT_LOADER`，此时 proot **不会**把 ELF loader 写进 `PROOT_TMP_DIR`（embedded-loader fallback 才写）——所以本条的损害路径是**机制级**，不是实测到的线上损坏。判 P2 而非 P1 就是这个原因；修它的理由是「门控不完整 + 成本低 + 任务书重点 5」。
- **测试**：`ProotTmpHostSweepTest`（新鲜保留 / 陈旧回收 / 边界与 guest 段一致 / 陈旧但有活跃子项保留 / mtime 未知按陈旧 + wiring 两条）。
- **变异**：M6a（去掉年龄门控）→ 红 2 条；M6b（改回 `deleteRecursively`）→ 红 wiring。真 FS 臂见 §2.0。

---

## 3. 未修项三件套（触发条件 / 方案 / 搁置理由）

**D3-F04 · `MemorySpikeRecorder` 配对缺失**
- 触发条件：需要可靠的「命令级 ΔRSS 归因」来定位内存尖峰时（现在归因表会被拒命令/取消命令污染一行）。
- 方案：把 `onCommandStart` 之后的 110 行整体包进 `try { … } finally { onCommandEndIfPending(...) }`（即报告草图），或把「已登记上下文」改成 `execute` 入口的一次性 holder，早退路径统一走 `endIfPending`。
- 搁置理由：只影响诊断日志、无用户行为变化（P2）；而它是给 `execute` 主体整体加一层 `try`（110 行重缩进）——改动面 ∝ 收益不成比例，且与本支的取消语义改动同函数，混在一起会放大回归面。

**D3-F06 · `image_url` 的 `file://` 一致性**
- 触发条件：出现「`file://相对路径` 形式的 `image_url`」，或有人在 `--output` 与 `image_url` 之间做对齐重构时。
- 方案（3 行，已核过 `stripFileUrlScheme` 对 `file:///abs` 幂等）：`url.startsWith("file://") -> resolveMaybeRelative(stripFileUrlScheme(url), cwd)`。
- 搁置理由：触发面向来被错误文案挡住（「Use … file:///host/path」），P2；且 `ModelUseOffloadHandler.kt` 不在本支已改动文件里（半径纪律）。

**D3-F05 · browser fetch 文件名的穿越校验**
- 触发条件：`BrowserActionResult.fetchedFileName` 出现**非** `extensionForMimeType` 白名单产物（即新增第二个写入者）。
- 方案：`val safe = File(fname).name.takeIf { it.isNotEmpty() && it != "." && it != ".." }`，再 `PRootKernel.safeResolveWithin(browserHostDir, safe)`。
- 搁置理由：现状**有守卫**（`browser/BrowserUtil.extensionForMimeType` 白名单 → 恒为 `fetch_<ms>.<known-ext>`），无可达穿越 → P3；给不可达路径建装置违反装置比例。

**R3-F03 · `installGeneration` 跨重启不单调**
- 触发条件：出现第一个 `RootfsEventLog.readBootId` 的生产消费方（当前 0 个）。
- 方案：`installGeneration = maxOf(installGeneration, RootfsEventLog.readBootId(context.filesDir).coerceAtLeast(0L)) + 1L`。
- 搁置理由：无消费方 = 无可观测后果（P2）；修它是给「将来可能读」付今天的钱。

**R3-F04 · 终端进程树未校验 `starttime`**
- 触发条件：真机上出现「终端关闭后误杀其它进程」，或 PID 复用被日志证实。
- 方案：把 `killTermuxProcessTree` 的 `/proc` 遍历换成现成的 `internalKillProcessTree(rootPid, procs, currentProcess, rootIsAlive)`（`PersistentShellStall.kt` 已有 starttime 校验 + 逆向信号顺序），`TerminalSession` 侧只需提供快照与 `sendSignal`。
- 搁置理由：P2（PID 复用窗口 + 只能误杀同 uid 进程）；且它要改的是终端回收环的**核心路径**，本支没有任何真机证据支撑「现在会误杀」，上装置不如先等证据。

**R3-F05 · GC 下限 120MB vs 高水位 512MB（反驳报告）**
- 报告主张：`floor` 应对齐 `appNativeHighWaterMarkMB()`，否则每轮多跑 ~240ms。
- 本支判断：**P3，不修**。两个数字服务不同目的 —— `appNativeHighWaterMarkMB` 是**准入/降级红线**（超过就拒绝或降级），`floor` 是**会话结束后的回收目标**（想压到紧基线）。对齐 512MB 会让会话回收在 150MB 就停手，等于放弃回收；而「多跑 ≤2 轮 × 120ms」发生在会话终止之后的后台线程。报告把「红线」当成了「目标」。

---

## 4. 跨域指针

| 项 | 归属域 | 说明 |
|---|---|---|
| R3-F04 的实施 | `sandbox/TerminalSession.kt`（本支 allowlist 内，但本支判 P2 不修） | 需要 `PersistentShellStall.internalKillProcessTree` 的调用形态改造 |
| D3-F05 的上游清洗 | `browser/BrowserUtil.kt` / `browser/BrowserUseManager.kt`（**allowlist 外**） | 现状安全依赖 `extensionForMimeType` 白名单；若该函数被改动，`BrowserUseOffloadHandler` 的写点必须同步加上 `File(fname).name` |
| `Status` 侧的 `proot-tmp` 消费者 | `RootfsManager.prootLoaderEnv()`（allowlist 内，未改） | 若将来为 apk 修复加显式「忙碌标志」，可把 host 清扫再加一道门控（当前用年龄门控覆盖，无需改它） |
| D3-F04 的 `MemorySpikeRecorder` | `sandbox/MemorySpikeRecorder.kt` + `ExecutionCoordinator.execute` | 建议与 `execute` 的下一次结构性改动合并做 |

---

## 5. 本地窄验证（不做全量 scan / 不做 gradle）

- **JVM 单测**：新增 5 个测试类 / **28 条** + 被改锚的邻居 `PersistentShellTreeTerminationTest` **14 条** = **42 条**全绿（`shared/work/incr1118/t2probe/run.sh`；生产纯函数由 `extract.py` 从真文件按符号抽取，不是重写）。
- **变异矩阵**：M1–M6b 共 **10 条**，每条**只**翻红它对应的臂（M5a 额外翻红邻居那条改锚后的用例，符合预期）；恢复后 42 条全绿。
- **相邻门**（逐个跑，`--root` 用位置参数）：`xproc_boundary` / `exec_gate_release` / `discarded_return_guard` / `process_logging_gate` / `modeluse_proto_single_source` / `readonly_mount_write_guard` / `sandbox_defect_field_contract` / `offload_host_path` / `offload_relative_path_unify` / `storage_budget` / `marker_boundary` / `claim_release_guard` / `outcome_known_chain` —— **13/13 rc=0**。
- **语法/注释体检**：6 个改动文件 kotlinc 单文件无 `syntax error`；块注释配平与基线一致（本轮自查抓到我自己的两处 `/*` 嵌套：`mounts/*` 与测试 KDoc 里的 `/* *\/`，均已修）。
- **CI**：见 §6（`head_sha` 与分支 tip 逐字符一致才收工）。

## 6. CI

- **#3221 / run `44017833140` @ `03d0e0c0`（分支 tip 逐字符一致）**：`scan-gate` / `build-apk` / `lint` **success**，`publish` skipped（main 门控），**`unit-tests` failure** ——
  唯一失败 = `PersistentShellTreeTerminationTest > stop wires explicit SIGKILL …`（因为断言锚在被我改掉的那行字面量上，见 §2.4「门 ROT 事件」）。**不是产品缺陷**：该用例的意图（有界等待在异步 reaper 上、caller 不阻塞）在我改完的代码里依然成立。
- 处置：改锚到决策 + 补负向断言（提交 `b368b1f0`），重 dispatch。
- **第二轮（停止节点）：#3226 / run `44027322035` @ `eb93e467`（分支 tip）= 五 job 全绿**。
  - `unit-tests` success：**9387 PASSED / 0 FAILED**，`BUILD SUCCESSFUL in 5m 3s`；本批 5 个新测试类的 **28 条逐条 PASSED**，改锚后的 `PersistentShellTreeTerminationTest > stop wires explicit SIGKILL …` PASSED（日志逐条核过，不是只看 job 绿）。
  - `scan-gate` success：`Summary: 115 passed, 0 failed` + 夹具自测**本轮真跑**：`scanners moved in this push (or the diff was unreadable) — running the fixture self-test` → `✅ ALL 354 CASES PASS`。
  - `lint` / `build-apk` success。**lint 首轮（attempt 1）红是 Maven Central 429**（`Could not GET …/hamcrest-*.jar 429`），非代码问题，`rerun-failed-jobs` 后绿。
  - `publish`：`Move the rolling tag android-latest` 与 `Publish to Releases` **两步 skipped**（main 门控），release 未被本支污染。
- **停止节点声明**：代码 + 测试的绿 sha = `eb93e467`；随后只追加了一个 docs 提交（本报告终稿 + §6 回填），`git diff eb93e467..tip --name-only` 仅 `fix-reports/FIX-W1118-sandbox.md`，**不含任何代码/测试差异**。未合 main（起手权在主会话）。
