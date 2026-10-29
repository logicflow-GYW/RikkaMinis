# FIX-R4-T — 日志/凭据工厂四条（B12：F-07 / F-08 / F-09 / F-14）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `2103729f`（worktree `git log -1` 实测一致） |
| 分支 | `fix/r4-b12-log` |
| 提交序列 | `86a6b7e9` F-07 · `2c6103c2` F-08 · `d32602f6` F-09 · `d9bc3cd9` F-14 · `6137a76a` 门修复（注释形状）· `59c7b88d` 测试 |
| 改动量 | 3 文件 **+486 / −23**（生产 2 文件 +318/−23；测试 1 文件 +168/−0）；`scripts/scan/**`、`scan.sh`、`test_scan.py` **零触碰** |
| 影响面 | `logging/AppLogger.kt`、`util/EncryptedPrefsFactory.kt`、新测试 `logging/LogByteAccountingR4Test.kt` |
| 事故说明 | 首次 worktree 在 2026-10-29T08:0x 的 `/var/minis/shared` 清空中丢失（0 笔 commit）；本条为重建后的全套重放，改动语义与首轮一致 |

## 2 缺陷与先证

四条**全部成立**，其中 F-08 的成因与报告不同（见 §2.2）。报告行号有漂移，按语义重取证。

### 2.1 F-07 成立：debug 分卷按 UTF-16 字符计量

真码 `writeQueuedLine` 的 debug 分支（基线 `AppLogger.kt:517`，现 `:639`）：

- 种子 `debugBytesWritten = if (file.exists()) file.length() else 0L`（**字节**）
- 阈值 `DEBUG_MAX_BYTES: Long = 5L * 1024 * 1024`（**字节**）
- 写入 `PrintWriter(FileWriter(file, true))`（UTF-8 编码，**字节**）
- 而每次累加是 `line.length + 1`（**UTF-16 码元数**）

中文一行 20 字 = 20 个码元，落盘 61 字节。⇒ 名义 5 MB 的分卷实际在 12–15 MB 才轮转，4 个分卷 50–60 MB。

**与报告的差异**：报告写判据是 `debugBytesWritten >= DEBUG_MAX_BYTES`，真码是 `>`（基线 `:518`）。差 1 字节，不影响结论。

### 2.2 F-08 成立，但报告的「证据块」是另一版代码

真码与报告证据的逐条对照（`EncryptedPrefsFactory.kt`，基线 233 行）：

| 报告证据块声称 | 基线真码 |
|---|---|
| `is KeystoreRecoveryPlan.Outcome.FallbackToInMemory -> { ... }` 的 `when` 分支 | 无此结构；`createOrRecover` 用 `if (prefs != null && outcome.succeeded)` + 末尾 `Log.e(...)` |
| `registry.markDegraded(fileName, outcome.lastError)`（双参） | `registry.markDegraded(fileName)`（单参，`DegradeRegistry` 也只提供单参） |
| `inMemoryStores.getOrPut(fileName) { InMemorySharedPreferences() }` | 无此 map；实例缓存走 `DegradeRegistry.resolve` |
| `AppLogger.error(TAG, "step=keystore_degraded ...")` | **不存在**；只有 `Log.e(TAG, "DEGRADED: ...")` |
| `putString(k, null)` / `remove(k)` 缺陷 | **成立**（见下） |
| 生产零消费 `isDegraded` / `degradedStores` | **成立** |

即：报告把「KDoc 声称的调用来当成实现」了 —— 类头 KDoc（基线 `:47-50`）原文写着 "visible to telemetry through `AppLogger.error("EncryptedPrefs", ...)` which emits a structured `step=keystore_degraded` line that `error-snapshot-*.log` captures"，**但代码里这行从未存在**。所以本条的实质是「契约写了、实现漏了」，不是「实现有 bug」。

三条独立缺陷面：

1. **可见性**：降级后 `safeCreate` 只 `Log.e` 到 logcat。logcat 行要进文件需 logcat tailer 正在跑（日志开关开启时），且永不进 `error-snapshot-*.log`（快照只由 `AppLogger.error` 触发）。生产 `isDegraded`/`degradedStores` 消费点 0（`rg` 全仓只命中定义处 + `KeystoreRecoveryPlanTest`）⇒ 用户输入 API Key、UI 显示已保存、冷启动后全空、全程无痕。
2. **降级存储的契约**：`InMemoryEditor.putString(k, null)` 把 null 存进 `staged` ⇒ `contains(k)` 为 true 而 `getString(k)` 返回默认值（「读到空」被当成「键存在」）；`remove(k)` 不清 `staged[k]`，而 `commit()` 先应用 `removed` 再应用 `staged` ⇒ `putString(k, x).remove(k)` 提交后是**一次写入**，用户删掉的密钥复活（「删了但读出来还在」）。
3. **并发**：`InMemorySharedPreferences.values` 是裸 `mutableMapOf`，无同步 —— 属 DESIGN-08，**本支不动**（已登记）。

### 2.3 F-09 成立：三条主线程可达的磁盘 I/O

| 路径 | 真码 | 主线程可达点 |
|---|---|---|
| `error()` → `dumpErrorSnapshot` → `file.writeText(...)`（最多 500 行环形缓冲） | 基线 `:392` | 全 UI 侧失败日志都走 `AppLogger.error` |
| `setEnabled(false)` → `stopCapture()` → `writeQueue.flushAndStop(2_000)`（`Thread.join(2s)`） | 基线 `:163-168` | `LogManagementScreen` 的 `onCheckedChange` |
| `init()` → `pruneOldLogs()`（三次全目录扫描 + 每项 `lastModified/length/delete`） | 基线 `:155` | `MinisApp.onCreate` |

**与报告的差异**：报告写 `if (enabled) { startCapture(); pruneOldLogs() }`，真码是 `if (enabled) startCapture()` 后跟**无条件** `pruneOldLogs()`（关闭日志时也扫）。结论不变，且说明「关闭日志的用户每次冷启动仍付三次目录扫描」。

### 2.4 F-14 成立：RPC limit 无上限 + readFully 把 I/O 竞态伪装成「文件不存在」

- `DebugRPCHandler.handleLogsRead` 取 `params.optInt("limit", 524_288)` 原样传入；对比同文件 `handleReadFile` 有 4 MB 限幅。
- `readLogSegment` 按 `(end - start)` 分配 `ByteArray` ⇒ `limit=50_000_000` 时分配整个文件（上限 = 200 MB 存储预算），再构造同尺寸 `String` 与 JSON 体，全在 RPC 线程。
- 读用 `raf.readFully(buf)`；`length()` 与 read 之间若发生 rotate/clear/truncate ⇒ `EOFException` ⇒ `catch (Exception) { null }` ⇒ 调用方报 `-32602 "Log file not found"`。

**与报告的差异**：报告签名写 `offset: Long`、`LogSegment(name=...)`，真码是 `offset: Int`、`LogSegment(totalSize, content, bytesRead, truncated)`；行号整体漂移 ~10 行。缺陷形态一致。

## 3 修法

### 3.1 F-07（`86a6b7e9`）

新增顶层 `internal fun utf8ByteLength(line: String): Long`（现 `AppLogger.kt:1021`），手写分支遍历、零分配；`writeQueuedLine` 改为 `debugBytesWritten += utf8ByteLength(line) + 1L`（现 `:639`）。

不用 `line.toByteArray(Charsets.UTF_8).size` 的理由写在 KDoc 里：该行在 drain 线程上每行跑一次，流式会话可达数万行，字节数组是纯垃圾。语义与 JDK 编码器逐分支对齐，含「孤立代理项编码为 `'?'`（1 字节）」。

### 3.2 F-08（`2c6103c2` + `6137a76a`）

1. **让契约成真**：`createOrRecover` 的降级路径在 `registry.markDegraded(fileName)` **之后**追加 `AppLogger.error("EncryptedPrefs", "step=keystore_degraded file=… attempts=… drops=… consequence=credentials_in_memory_only next_cold_start_starts_empty reentry_required=true")`（现 `:71` / `:90`）。顺序是刻意的：快照唤醒的观察者必须已经看到 `isDegraded == true`。
2. **`Created` 携带 `attempts`/`drops`**，让日志带真实恢复计数而不是常数。
3. **降级存储契约**（现 `:270` / `:284`）：`putString(k, null)` / `putStringSet(k, null)` 走 `remove(k)`；`remove(k)` 清 `staged[k]`；所有 `put*` 走私有 `stage()`，写 staged 同时取消同键的 `removed` ⇒ **同键最后操作赢**，两个方向都成立。

装置比例：一条 ERROR 行（而非新 UI 面），因为快照环已经带场景，且横幅需要 7 份 i18n + 新 UI 状态，超出「降级罕见」的影响面。锁（DESIGN-08）不动。

### 3.3 F-09（`d32602f6`）

两个单线程 daemon 执行器（现 `:151` / `:153`）：

- `AppLogger-Control`：`startCapture` / `stopCapture` / `pruneOldLogs` 串行。串行的意义不是性能，是**顺序**——prune 必须在 `startCapture` 建好异步 writer 之后跑（deferral WARN 才进文件），而 setEnabled 的开关不能被交错的 start/stop 打乱。
- `AppLogger-Snapshot`：快照写盘。

三处改动：

- `dumpErrorSnapshot` 在调用线程**拼接**（`contentIncluding` 持环锁、触发行只有调用线程确定正确），把 `file.writeText` 交给快照 worker；executor 拒绝时退回同步写（宁慢不丢现场）——现 `:477`。
- `stopCapture` 在 `ring = null` **之前** `flushSnapshots(500)`（现 `:285`），崩溃现场不因关开关丢失。
- `setEnabled` 只置 `enabled` + 写 prefs，控制动作进队列，且队列内**重查意图**（`if (enabled == value)`）——否则「关→开→排队中的关」会把用户刚打开的日志关掉。
- `init()` 保留同步 `startCapture()`（其文档契约是「onCreate 后续代码的 println 必须能落盘」），只把 `pruneOldLogs()` 进队列（现 `:187`）。

### 3.4 F-14（`d9bc3cd9`）

- 新增 `MAX_LOG_SEGMENT_BYTES = 4 * 1024 * 1024` 与纯函数 `segmentBounds(fileSize, offset, limit)`（现常量与函数在文件尾）：限幅 + 负数/越界全部折叠成空读，不抛。
- `readLogSegment` 用 `segmentBounds` 计算窗口（现 `:867`），读改用 `internal fun readAtMost`（现 `:900`）——短读容忍，返回实际存在的字节。
- `LogSegment` KDoc 写明**按字节对齐**的取舍：行对齐会让 `offset + bytesRead` 落在最后一行内部，分页客户端会重复读同一行而无法前进；字节对齐的代价是接缝处可能撕裂一个多字节字符。

## 4 覆盖面

- F-07：`rg` 全仓 `debugBytesWritten` 只有 3 个写点（种子 / 累加 / 轮转清零），无第二处字符计量。
- F-08：`InMemoryEditor` 是该方法族的唯一实现；`TextKeyedPrefs` 等其它包装类不动。`isDegraded`/`degradedStores` 仍是 0 消费点（见 R2）。
- F-09：`AppLogger` 的其余 @Synchronized 入口（`clearLogs`、`readLog` 等）调用方已在 IO 线程或只做内存操作，未改。
- F-14：`readLogSegment` 是唯一调用 `readFully` 的日志读取点；`readLog`（全文读）另有限幅路径，不在本条范围。

## 5 验证四层

| 层 | 读数 |
|---|---|
| 纯函数 + 契约（JUnit，本地真跑） | `OK (11 tests)` — `LogByteAccountingR4Test`（utf8 编码边界 / 孤立代理项 / 加性 / 限幅 / 折叠）+ `InMemoryPrefsContractR4Test`（null put = remove、同键最后操作赢两向、clear+put、commit 报 false） |
| 探针（真实生产文件编译 + 真实文件 I/O） | **94 checks / 0 red**（`R4T_PROBE=PASS`）——含 5 MB 真文件读、越界 offset、遍历拒绝、`readAtMost` 在 EOF 返回 0（`readFully` 会抛）、以及全部源码形状断言 |
| 变异臂 | **14 / 14 MATCH**（分三批跑：M1–M6 / M7–M10 / M11–M14，M14 收紧判据后复跑）——每条修复被回退后，探针必在**点名的断言**上红 |
| 门（本地，逐个跑，未跑全量） | 15 道相关门 **15 / 15 rc=0**：`process_logging_gate` · `liveness_static_guard` · `discarded_return_guard` · `log_url_redaction_guard` · `prefs_listener_holder_guard` · `skill_import_offmain_check` · `share_dir_prune_check` · `readonly_mount_write_guard` · `mediaplayer_release_guard` · `mcp_oauth_lifecycle_check` · `loop_counter_reset_guard` · `compact_decision_gate_check` · `anthropic_thinking_budget_check` · `file_provider_paths_coverage_check` · `chat_menu_prefs_listener_check` |
| 云端 | 见 §5.1 |

变异臂清单（点名的失败断言）：

| 臂 | 回退的修复 | 探针红在 |
|---|---|---|
| M1 | 计数退回 `line.length + 1` | `F-07 debug counter uses utf8ByteLength` |
| M2 | utf8 的 3 字节分支改 1 | `utf8ByteLength matches JDK encoder` |
| M3 | 代理对 4 字节改 2 | 同上（emoji 行） |
| M4 | 去掉 `MAX_LOG_SEGMENT_BYTES` 限幅 | `limit clamped to 4 MiB` |
| M5 | `readAtMost` 改回抛 EOFException | `readAtMost at EOF returns 0` |
| M6 | seek 越过窗口 | `readLogSegment clips to EOF` |
| M7 | 快照写盘改回调用线程 | `F-09 snapshot write goes to the worker` |
| M8 | `pruneOldLogs()` 回主线程 | `F-09 init schedules the prune off-thread` |
| M9 | `setEnabled` 直接 start/stop | `F-09 setEnabled does not call start/stopCapture on the caller thread` |
| M10 | 删掉 `flushSnapshots(500)` | `F-09 stopCapture flushes pending snapshots` |
| M11 | flush 移到 `ring = null` 之后 | `F-09 snapshot flush precedes ring teardown` |
| M12 | `AppLogger.error` 退回 `Log.e` | `F-08 degradation logs through AppLogger` |
| M13 | 删掉 `markDegraded` | `F-08 markDegraded runs BEFORE the log line` |
| M14 | `step=keystore_degraded` 改名 | `F-08 log line is greppable` |

**M14 的一次假绿值得记**：第一版判据是 `contains("step=keystore_degraded")`，而我为门加的注释里正好拼了这个词 ⇒ 探针被注释满足、变异不红。判据收紧成 `"step=keystore_degraded file="`（带参数形状）后才 MATCH。**形状断言必须避开注释污染的裸词。**

### 5.1 云端 CI

（待填：run id / head_sha / 步数 / 日志判读）

## 6 残留（R 条目，每条带触发条件 + 触发后方案）

| 编号 | 内容 | 触发条件 | 触发后方案 |
|---|---|---|---|
| R1 | F-07 的 `healTornTail` 追加字节不计入计数（下一次 reopen 重新 seed 会纠正） | 尾部修复后立刻做字节阈值判断 | 在 `healTornTail` 后重设 `debugBytesWritten = file.length()`（现顺序已如此，仅跨轮实例不一致） |
| R2 | F-08 未做用户可见横幅：降级仍只在日志/快照里可见 | 用户报「密钥保存了但重启没了」 | Settings 顶部读 `degradedStores()` 出 banner（需 7 份 i18n + UI 状态，属新面） |
| R3 | `InMemorySharedPreferences.values` 无同步（并发 CME） | 降级 + 多线程读写 prefs | 归 DESIGN-08，已登记；修法是加锁或用 `ConcurrentHashMap` |
| R4 | `outcome.drops` 把「文件本来不存在」也计一次 destructive step | 首次安装即降级（文件从未存在）时日志报 "2 destructive step(s)" 误导 | 让 `KeystoreRecoveryIo.drop` 返回 Boolean，`drops++` 只在真删时发生（要改 plan + 三处测试） |
| R5 | `setEnabled(true)` 异步化后，开关打开到 `startCapture` 真正执行之间有毫秒级窗口，期间日志行只到 logcat | 用户在开关后立刻复现某行缺失 | 让 `setEnabled` 返回可等待句柄，或 UI 侧 `awaitIdle()` |
| R6 | 快照异步化后，进程被硬杀时最后一条快照可能未落地（旧代码也没有 `fsync`，等级相同） | 需要「崩溃现场必达」 | 在 crash handler 里调用同步变体（新增 `dumpErrorSnapshotBlocking()`） |
| R7 | 快速连点开关时，`startCapture` 若撞上 control 线程正在 `stopCapture`（含 2s join），主线程会等待 | 用户连点日志开关 | 把 `startCapture` 也全异步（与 init 的同步契约冲突，需重设计） |
| R8 | F-14 分片按字节对齐 ⇒ 多字节字符可跨分片撕裂成 `U+FFFD` | 客户端按 `offset + bytesRead` 分页读 CJK 日志 | 在 RPC/客户端侧拼完整后再解码，或读侧回退到行边界并同步调整 offset 语义（属 `DebugRPCHandler`，非本支文件集） |
| R9 | 真机未验（全部证据 = JVM 真跑 + 源码形状 + 云端编译/单测） | 需要真机读数 | Keystore 降级需构造 AEAD 失败，成本高；主线程卡顿量化需 systrace |

## 7 未验证边界

- 「主线程卡顿时长」只有机制证据（同步 `writeText` / `Thread.join(2s)` / 三次目录扫描），没有真机 systrace 数值。
- Keystore 三阶段回退链（重试 ×3 → 删本 store XML → 删共享 keyset + alias → 降级）**未改动**，本支只改可见性与降级存储语义；回退链本身的行为由既有 `KeystoreRecoveryPlanTest` 守护。
- F-08 的「读到空 vs 读不出」在**恢复链**层面未动：`FileRecoveryIo.backup` 对「文件不存在」返回 `true // nothing to lose`，随后的 `drop` 仍执行（对不存在的文件是 no-op），因此只有计数（R4）与日志措辞受影响，不产生数据损失。
- 并发截断（rotate 与 read 竞争）无法在 JVM 稳态复现；证据是 `readAtMost` 在「seek 到 EOF 后读」返回 0 而 `readFully` 抛（探针真跑）+ 变异臂 M5。

## 8 附装置路径

- 探针与变异臂：`/root/r4t/probe/`（`R4TProbe.kt` 94 断言、`arms.py` 14 臂、`probe_compile.py`、4 个 Android/miniAndro 桩）。放 `/root` 是本次事故后的决定（`/var/minis/shared` 在 08:0x 被清空，装置曾连带丢失）。
- 单测运行器：`/root/r4t/run_unit_tests.sh`（kotlinc + JUnit 4.13.2 + hamcrest，编译真实生产文件）。
- 改动重放脚本：`/root/r4t/apply_stages.py`（四阶段，每步断言锚点唯一）。
- 门：仓库内 `scripts/scan/*.py`，逐个调用，未跑全量。
