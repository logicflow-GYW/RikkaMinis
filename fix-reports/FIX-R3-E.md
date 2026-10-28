# FIX-R3-E — B10 scheduled / share / rollup（F-04 / F-07 / F-11 / F-14）

- 基线 `main @ 2103729f`
- 分支 `fix/r3-b10-sched`
- 来源报告 `meta-audit/reports/r3/B10-backup-sched-2026-10-29.md`（F-04 P1 / F-07 P1 / F-11 P2 / F-14 P1）
- 判级表 `meta-audit-1029/TRIAGE-R3.md`
- 改动面：7 个生产文件 + 3 个新测试文件（`scripts/scan/**` / `scan.sh` / `test_scan.py` / `backup/**` / `provider/**` / `conversation/**` / `ui/preview/**` 零触碰；manifest 也未改 —— 见 §3.4 为什么不需要 `configChanges`）

---

## 1. 任务与判定

| 发现 | 判级 | 先证结论 | 处理 |
|---|---|---|---|
| F-04 并发双触发 | P1 | **成立，但是「可能」不是「必然」**（报告把两项都写成必然，时序要重演 —— §2.1）；报告另有两处过度表述已补正 | 修两半 |
| F-07 写失败假成功 + 读失败折叠成空 | P1 | **成立，且比报告重**：报告只讲「保存/恢复报成功」，漏了「读失败 → 下一次任意写就把空表固化为真相」与「外版本文件被静默降级」 | 修 |
| F-11 判定口径分叉 | P2 | **成立**：执行器、候选判据、提示词挑文件三处都用未去重的条目；section 用去重后的 ⇒ 合并改变分类时可永久空转 | 修（一行 + 一处调用） |
| F-14 分享暂存三缺陷 | P1 | **成立 ×3**：重建重拷（无 `configChanges`、`onCreate` 不看 `savedInstanceState`）；清理无差别删 + 账本清零；AttachText 写失败留半截文件 | 修 |

**「这改变什么用户行为？」（一句/条）**

- F-04A：每日任务不会在同一天被触发两次（不再出现重复的定时任务会话与多余的模型调用）。
- F-04B：被删/被恢复替换的任务不会在后台偷偷跑一次（不再有无主的 headless 会话与复活的 running 标记）。
- F-07：磁盘写失败或任务文件不可读时，编辑页/恢复弹窗不再报假成功；一次开关点击不再把全部定时任务清空。
- F-11：不再出现「提示词反复推荐同一个日志、`memory_rollup` 每次返回空、后面更小的日志永远排不上」的饿死。
- F-14：旋转屏幕不再产生幽灵暂存文件与虚高的 500MB 账本；连续两次分享不再被前一次的清理删掉文件。

---

## 2. 先证（逐条，含我自己的时序重演）

### 2.1 F-04A：`finalizeRun` 在 `checkMutex` 之外结算

代码事实（基线）：`checkAndFireToday` 全程持 `checkMutex`；`finalizeRun`（由 `onSessionSettled` 在 tracker 线程 `launch`）**不持**任何 runner 锁，它做两件事：`ScheduledTasksStore.completeRun`（落盘 `lastRunDate` + 清 marker）与 `runningTaskId = null`（内存）。

真实交错（我重演出的最小序）：

| 步 | 线程 | 动作 | 观察值 |
|---|---|---|---|
| T1 | 前台心跳 | 取 `checkMutex`；`load()` 得快照 **S**（T1.`lastRunDate` = 昨天） | S |
| T2 | tracker | `finalizeRun`：`completeRun` 落盘 `lastRunDate` = 今天、清 marker；`runningTaskId = null` | 磁盘已更新 |
| T3 | 前台心跳 | `evaluate(today, now, S.tasks, runningTaskId = null)` | **陈旧 tasks + 新鲜 null** |
| T4 | 前台心跳 | `ALREADY_RAN` 看 S 的昨天 → 不成立；`OVERLAP` 前置 `firing = runningTaskId != null` = false → 不成立 ⇒ **Fire** | 同日第二次触发 |

- 窗口 = `load()`（L209）→ `evaluate()`（L223）之间的同步代码（几十微秒）+ `evaluate` 循环（微秒）。
- **性质**：低概率但非零（心跳每分钟一次 × 数十微秒窗口 ⇒ 长期运行会出现一次），报告写的「会绕过 ALREADY_RAN 重复触发」作为形态正确，作为频率表述过头。**「可能」不是「必然」** —— 这也是本支不用「随便加个 try」而是把它做成临界区不变量的原因。
- **第二个变体（报告没提）**：`finalizeRun` 落在 L212 的 marker 检查之前 ⇒ marker 快照非 null + `runningTaskId` 已 null ⇒ 走 crash 分支。此时 store 侧 marker 已被 `completeRun` 清掉，`ScheduledTaskTransitions.crashFailure` 的 `if (file.running?.taskId != taskId) return Result(file, null, false)` 直接 no-op ⇒ **不计失败**，只打出假的 `abort task=… reason=process-restart` 日志。报告的「误记 process-restart 失败」应修正为「假 abort 日志（不计数）」。

### 2.2 F-04B：`fire` 的 `prepareSession` 挂起窗口与 `deleteTask` 交错

| 步 | 线程/调用 | 动作 | 状态 |
|---|---|---|---|
| T1 | 心跳 → `checkAndFireToday` 持 `checkMutex` → `fire` | `runningTaskId = task.id`；`prepareSession` **挂起**（建会话 + 改 source + 改标题，DB 操作，不持 store 锁） | 任务仍在文档 |
| T2 | 用户删任务 | `deleteTask` → `mutate`（storeLock：删任务 + `running = null`，落盘）→ 释放 storeLock → `onTasksRemoved(setOf(taskId))` → **阻塞在 `checkMutex`** | 磁盘上任务已删 |
| T3 | `prepareSession` 返回 → `fire` 继续 | 旧代码：`markRunning` **无条件写 marker**（= 复活一个已删任务的 running 条目）→ `dispatched[sid] = …` → `prompt(...)` **启动 headless 会话** | 已删任务在跑 |
| T4 | `fire` 返回 → 释放 `checkMutex` | `onTasksRemoved` 拿到锁：清内存（`runningTaskId`、`dispatched`），**磁盘 marker 不归它管** | 孤儿 marker |
| T5 | 下一次心跳 | marker 非 null 且 `marker.taskId != runningTaskId` ⇒ crash 分支 ⇒ `crashFailure` ⇒ 任务已不存在（`idx < 0`）⇒ 只 `clearMarker` + 假 abort 日志 | marker 被清 |

- 后果：①已删任务真的在后台跑（真实的模型调用/通知）；②假 abort 日志。
- **补正**：报告写「留下永久孤儿 running 标记」——不准确，下一次心跳（≤60s）会清掉它。真正的危害是 T3 的「跑起来了」，不是「永久标记」。

### 2.3 F-07：写失败吞掉 + 读失败折叠成空

- `writeLocked`（基线 L-) `try { AtomicFileWrite.write(...) } catch { AppLogger.warning(...) }`，**无返回值**；`mutate` 无条件 `return next`、`importDocument` 无条件 `return applied`。⇒ 消费方（编辑页 `saved.tasks.any { it.id == effectiveId }`、`ConfigBackup.scheduledTasksImported`）看到的都是「成功」。
- `loadLocked` 把 `readText()`/`parse()` 的任何异常折叠成 `ScheduledTasksFile()` —— 与「这台设备真的没有任务」同值。**下一次任意 `mutate` 就把它写回磁盘**：一次全局开关点击（`setGlobalEnabled`）即可清空全部任务。这是报告没写的一层（报告只写「静默返回空任务列表」）。
- 第三个缺口（报告只提了一句「不校验 version」）：`ScheduledTasksCodec.parse` 宽松读未知字段，**外版本文件会被本 build 当作 v1 重新编码**，下一次写即静默剥掉 v2 新增字段。备份恢复侧已有版本门（`ScheduledTasksRestorePlan.decide` 的 `BAD_VERSION`），**本地活动文件没有**。

### 2.4 F-11：三处口径分叉

| 消费点 | 基线判据 | 合并翻转后 |
|---|---|---|
| `MemoryRollupRunner.runOnce:87` | `distillableEntries(entries)`（未去重） | 有货 → 走 `buildRollupText` |
| `runOnce:144`（提示词挑文件） | `isRollupCandidate` → `distillableEntries(extractEntries(text))`（未去重） | 判定入选 → 反复被推荐 |
| `buildRollupText` | `distillableEntries(dedupeEntries(entries))`（去重后） | 空 → `NOTHING_TO_DISTILL` |

翻转机理（可构造）：同标题两条，第一条收敛（无 `STRICT_CONVERGED_SIGNALS` 靠「有标题」得 LESSON），第二条含弱未完成词（`待验证`）且无强收敛词；合并后的正文含未完成词 ⇒ `isWeakUnfinished && !isStrictlyConverged` ⇒ **TRANSIENT**。于是「最老的未蒸馏日志」永久占据候选位，每次返回空，后面的日志永远排不上（报告 §F-11 的「空转饿死」成立）。

### 2.5 F-14：分享暂存三缺陷

1. **重建重拷**：manifest 中 `ShareReceiverActivity` 无 `android:configChanges`，`onCreate` 也不看 `savedInstanceState` ⇒ 旋转/深色切换/分屏重建都会**重跑 `extractItems()`**：对同一 intent 再拷一份 `shared-<新id>`，`stagedBytes.credit()` 再记一次；第一份没有任何条目引用（`PendingShare` 只登记第二份）⇒ 任何清理路径都够不到它，其字节却永远留在进程级账本里。
2. **清理无差别删**：`cleanSharedFiles` 遍历整个暂存目录删除**全部文件**、再 `resetStagedBytes()`。消费方（`ChatScreen`）在「上一次分享已注入」之后调用，而新的分享可能已经开始写文件 ⇒ 删掉别人正在用的文件；账本被清零又会让仍在盘上的保留文件不计入 500MB 预算（低估）。
3. **AttachText 半截文件**：`File.writeText()` 无 try/catch ⇒ 中途失败时半截 `.txt` 留在盘上，`credit` 未执行、也没有 item 引用它 ⇒ 无人记账的孤儿。

---

## 3. 修法

### 3.1 F-07（`ScheduledTasksStore.kt`）

- `writeLocked` 返回 `Boolean`（是否落地）；`_revision` 仍只在落地时 bump（既有 `ScheduledTaskRevisionPolicy` 契约不变）。
- `mutate`：写失败或文件不可读时**返回写前文档**（`ScheduledTaskStorePolicy.visibleAfterWrite`）。编辑页的 `saved.tasks.any { it.id == effectiveId }` 因此变 false ⇒ `recordMissing` 不关页，不假成功。
- `importDocument`：写失败 ⇒ `applied = false` + notice「kept the existing task(s) as-is」（`ConfigBackup` 不再把 `scheduledTasksImported` 记成 true）。
- 新增 `unreadable` 语义（`@Volatile`）：`loadLocked` 在「读/解析失败」或「`version` > 本 build」时置位并**返回空文件**，但空文件从此**不可被写回**：
  - **0 字节 / 纯空白文件不在此列**：那是「这台设备真的没有任务」，不是读失败（非空字节无法变成文档才算失败）。否则空文件会把用户锁死在没有自救路径的状态里，而它本来就不含任何需要保护的数据。
  - `mutate` / `completeRun` / `crashFailure` 全部在写前检查并拒绝（日志点名）；
  - **`importDocument` 是唯一豁免**（整体替换是显式自救路径，写的是刚从备份解析出的字节），成功写后清除 `unreadable`；额外补一条：`unreadable` + 备份段为空 ⇒ 拒绝（否则空段会盖掉读不出的文件）。
  - `exportDocument` 在不可读时导出空集但打 WARNING（签名不能改：调用点全在 `backup/**` 与备份屏，本支禁改；恢复侧 `ScheduledTasksRestorePlan` 空集守卫会拒绝把它盖到有任务设备上）。
- 为什么**不**像 `MountedFoldersStore` 那样「把坏文件改名隔离」：那个先例的 store 一定会在坏文件之后继续写（用户改开关是常态）；这里的写路径没有一个是「必须成功」的，拒写 + 保留原字节 + 日志足够，而一次误判就把用户文件挪走是新的数据损失面。恢复路径（`importDocument`）保留 ⇒ 用户不会被困死。

### 3.2 F-04（`ScheduledTaskRunner.kt` + `ScheduledTaskPolicy.kt`）

**F-04A（结算与检查互斥）**：`finalizeRun` 拆成两个：

```
finalizeRun(...)      = checkMutex.withLock { finalizeLocked(...) }   // onSessionSettled 用
finalizeLocked(...)   = 原结算体                                       // reaper / fire 失败路径用（已持锁）
```

- 为什么必须拆分：kotlinx `Mutex` **不可重入**；`reapAbandonedDispatch`（`checkAndFireToday` 内）与 `fire` 的两条失败路径本来就在锁内，直接调 `finalizeRun` 会自死锁。
- 锁序不变：仍是 `checkMutex → storeLock`。反向（storeLock → checkMutex）依旧禁止 —— `importDocument`（L189-191）与 `deleteTask`（L266-269）的 `onTasksRemoved` 都在 storeLock 之外，`scheduled_store_release_guard.py` 门绿。
- 两序皆安全：check 先行 ⇒ `evaluate` 仍看到运行中的 `runningTaskId` ⇒ OVERLAP；settle 先行 ⇒ 下次 `load` 读到已计入的 `lastRunDate` ⇒ ALREADY_RAN。**这两条被 §4 的回归用例钉住**，并且「陈旧 tasks + null 锁」这个危险配对本身也有一条用例（证明缺陷机理、证明为什么必须靠互斥消灭它）。

**F-04B（派发前再准入）**：`markRunning` 变成 CAS：

```
ScheduledTaskStorePolicy.markRunningDecision(file, taskId, sessionId, startedAt)
  = file                                    // globalEnabled=false / 任务不在 / 任务 disabled → 原样返回（mutate 因 `next === current` 跳过写入）
  = file.copy(running = ScheduledRunMarker…) // 允许
```

- 为什么把校验放进 `mutate` 的 transform（同一 storeLock 临界区）而不是「先 load 检查再 markRunning」：后者有两次 TOCTOU —— 检查与写入之间 `deleteTask` 的 storeLock 段仍可插入（它**不需要** `checkMutex`），marker 仍会被写到一个刚被删的任务上。CAS 形态把「检查 + 写」压进同一个临界区，窗口为零。
- `fire` 收到的文件若 `markerLanded == false`（任务在 `prepareSession` 期间被删/被恢复覆盖/被关掉）⇒ 放弃派发：清 `runningTaskId`、`AgentRunBudgetCeilings.clear(sid)`、**不写 `dispatched`、不 `prompt`**、日志 `abandon reason=removed-mid-fire`。
- 顺带把 `globalEnabled` / `enabled` 也纳入准入：它们是 fire pass 自己的前置条件，`prepareSession` 挂起期间用户可能已撤销；`mutate` 写失败同样会让 `markerLanded` 为 false ⇒ 保守不派发（避免产生无 marker 的孤儿会话）。

### 3.3 F-11（`MemoryRollupEngine.kt`）

```kotlin
fun distillableEntries(rawEntries: List<Entry>): List<Entry> {
    val entries = dedupeEntries(rawEntries)
    val distillable =
        entries.filter { classify(it) != RollupClass.TRANSIENT }
    return distillable
}
```

- 一行语义（去重后分类）把三处消费自动统一：`isRollupCandidate`（提示词挑文件）、`runOnce:87`、`buildRollupText`（后者去掉双重去重）。
- **为什么写成三段而不是一个表达式**：`scripts/scan/backup_remap_rollup_check.py` 把 `entries.filter { classify(it) != RollupClass.TRANSIENT }` 这一行当作 fixture 锚点（arm M21 把它变回旧缺陷），而该门本支禁改 —— 见 §7 第一轮 CI 失败。表达式单独成行后锚点逐字仍在，且它现在读的就是去重后的列表，臂的语义不变。
- 单条日志的行为不变（`dedupeEntries` 对单条是恒等）；只有「同标题多条」的判定从「按原条目」改为「按合并后条目」—— 正是缺陷所在。
- 未建装置：P2 窄触发，只加 5 条 JVM 用例（§4.2），不建探针/变异框架（`checks` 里无新门）。

### 3.4 F-14（`ShareReceiverActivity` / `SharedShareStore` / `StagedBytesLedger`）

1. **重建复用已暂存条目**（不是 `configChanges`）：
   - `onCreate` 从 `savedInstanceState` 读回 `PendingShare` JSON（`STATE_ITEMS`），非空则**跳过 `extractItems()`**；`onSaveInstanceState` 写入当前 `extractedItems`。`PendingShare.toJson` 是纯序列化，无副作用，条目里只有 name/value 字符串（体积可忽略）。
   - **为什么不用 `android:configChanges`**：它挡不住「后台进程被回收后重建」（`savedInstanceState` 仍非空、intent 仍是同一个），那一类重建照样重拷；而 `savedInstanceState` 方案两者同时覆盖，且不需要 Activity 手动承担配置变更（深色/字体缩放）的重建职责。manifest 因此**零改动**。
   - 清掉「谁引用了这份文件」的歧义：重建后 items 里的 `ATTACHMENT` 值直接指向第一次拷贝出的文件，`cleanSharedFiles` 的 keep 集（§2）会保留它们。
2. **按引用清理 + 账本对账**：`cleanSharedFiles` 读 `loadPendingShare` 的 `ATTACHMENT` 值作为 keep 集（**pending share 本身就是「这些文件属于谁」的唯一记录**），删除其余文件；随后 `StagedBytesLedger.set(剩余文件字节和)` 做**绝对对账**（不是清零）：既不会低估保留文件、也不依赖每个删除路径记得 debit。
   - 两个现有调用点语义自动正确：`processPendingShare` 的过期分支先 `clearPendingShare` 再清理 ⇒ keep 空 ⇒ 全删（正确）；`ChatScreen` 消费后清理 ⇒ 若新分享已暂存，它的文件被保留（修复竞争）。
3. **AttachText 写失败**：`try/catch` + 失败 `delete()` 半截文件 + 只对落地的写入 `credit`。

---

## 4. 装置与变异臂

### 4.1 JVM 表驱动（新增 3 个测试类，25 例）

| 类 | 例数 | 覆盖 |
|---|---|---|
| `scheduled/ScheduledTaskStorePolicyTest` | 12 | `visibleAfterWrite`（落地/未落地）、`markRunningDecision`（存在+启用+全局开 → 写；任务不存在 / disabled / fused / 全局关 → 身份返回即拒写；已有他人 marker → 覆盖）、`markerLanded` 四种、以及 **F-04A 的三个交错序**（危险配对 → Fire；check 先行 → OVERLAP；settle 先行 → ALREADY_RAN） |
| `share/ShareStagedCleanupPolicyTest` | 8 | keep 规则表（引用保留 / 孤儿删除 / INLINE_TEXT 不入集 / 空 pending 全删）、`set()` 对账（正值/负值钳零/归零/生命周期序列/reset≡set(0)） |
| `workspace/MemoryRollupConsistencyTest` | 5 | 合并翻转（原条目 LESSON → 合并后 TRANSIENT）、`distillableEntries` 空、候选与 section 口径一致（同一日志两侧同为空）、可蒸馏日志仍通过同一门 |

跑法：`/var/minis/shared/work/meta-audit-1029/r3e-jvm/run.sh`（kotlinc 编纯生产文件 + 用例，JUnitCore 跑；含既有回归类 `ScheduledTaskPolicyTest` 23 / `ScheduledTasksCodecTest` 12 / `MemoryRollupEngineTest` 21）。

**并发判据不靠 `sleep`**：F-04 的判据全部是「纯函数交错序」（`evaluate` + `completeRun` 组合），互斥本身由 `finalizeRun` 的锁形状保证（结构判据，`rg` 可核）；没有任何 `Thread.sleep` 式的时序赌博。

### 4.2 变异臂（4/4 全部打中预期用例，源码 sha256 复原一致）

| 臂 | 把什么塞回去 | 结果 |
|---|---|---|
| A1 | `markRunningDecision` 去掉两道准入（= 旧的无条件写） | `ScheduledTaskStorePolicyTest` **FAILURES=4**（拒绝类用例全红） |
| A2 | `visibleAfterWrite` 恒返回 `next`（= 旧的假成功） | `ScheduledTaskStorePolicyTest` **FAILURES=1** |
| A3 | `distillableEntries` 改回 `entries.filter`（= 旧的未去重口径） | `MemoryRollupConsistencyTest` **FAILURES=2** |
| A4 | `StagedBytesLedger.set` 改为恒 `0`（= 旧的 reset 语义） | `ShareStagedCleanupPolicyTest` **FAILURES=2** |

（`/var/minis/shared/work/meta-audit-1029/r3e-jvm/arms.sh` + `arms.log`；每臂后 `sha256sum` 与改前一致。）

### 4.3 装置比例

- F-11 判级 P2 ⇒ **不建门、不建探针**，只 5 条用例。
- F-04/F-07/F-14 生产代码是 Android-bound（`Context`/`AtomicFileWrite`/`AppLogger`），判据被抽成纯核（`ScheduledTaskStorePolicy`、keep 规则、对账），与既有 `ScheduledTaskRevisionPolicy` 同一惯例。

---

## 5. 本地验证

| 项 | 结果 |
|---|---|
| `scan.sh --jobs 3`（全量门） | **113 passed, 0 failed**（含 `backup_remap_rollup_check`：selftest 59 臂 + 真树绿） |
| JVM 全套（含既有回归） | **81 例 / 0 失败**（新增 25 + 既有 56） |
| 变异臂 | **4/4 MATCH**，源码 sha256 复原一致 |
| 夹具关键组（`--single`） | `test_scheduled_store_release` 8/0、`test_real_repo` 20/0、`test_da1026_gates` 2/0、`test_w1027_storage_budget` 2/0、`test_da1016_gates`（含 backup_remap 组）18/0 |
| 夹具全量（`--jobs 4` / `--jobs 1`） | **沙箱阻断**：`scheduler: OSError: [Errno 38] Function not implemented`（S28，19 会话并发下偶发；与改动无关，同一命令在本支改动前也会失败）⇒ 交 CI 跑全量 |
| 括号/注释配平 | 7 个生产文件 + 2 个测试文件 OK（第 3 个测试文件因反引号函数名含撇号触发检查器假阳性，已由真实编译器/运行验证） |
| 形状门 | `scheduled_store_release_guard.py` 绿（锁序未破）；`schedule_date_source_check.py` 绿 |

---

## 6. 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | F-04B 放弃派发时，`prepareSession` 已建的空会话壳留在 DB（`source=scheduled`、无消息） | 用户在 `prepareSession` 挂起窗口内删任务/恢复备份（罕见） | 在放弃分支调会话删除（`ChatDao.deleteSession` 或 repository 封装），并确认不留反向引用 | 删会话要动 `data/**` 与 `conversation/**` 的清理契约，超出本支「只碰 scheduled/share/rollup」的半径；空会话无消息、无额度消耗，危害低 |
| R2 | `unreadable` 时 `exportDocument` 导出空任务集（签名无法携带失败） | 文件损坏且用户在同一时刻导出配置备份 | 给 `ConfigBackup.export` 增加「scheduledTasks 段缺失」的表达（调用点全在 `backup/**`，需独立支） | 恢复侧已有空集守卫（不会清空别处设备），且带 WARNING 日志 |
| R3 | 编辑页在「写失败」与「记录被删」两种情形下走同一个 `recordMissing` 提示（文案偏「不见了」） | 磁盘满/只读导致 upsert 写失败 | 在 `ScheduledTaskEditScreen` 区分「保存失败」（新 i18n key × 7 locale） | 需改 `ui/settings/**` + 7 份 strings，超出本支触碰清单；当前行为已是 fail-closed（不关页、不假成功） |
| R4 | 分享暂存 items 进 `Bundle` 时按 `PendingShare.toJson` 全量序列化（含 base64 图片的 inline text） | 用户在对话框打开期间旋转屏幕且分享内容接近 64MB | 只保存 ATTACHMENT 文件名 + INLINE_TEXT 的取值指针/token | 当前实现只在 >1MB 的超大文本上多占一次 Bundle 体积；崩溃面为「重建时丢条目」而非数据损坏 |
| R5 | `ScheduledTasksCodec.parse` 仍宽松读未知字段（拒绝只发生在 `loadLocked` 的 version 门） | 未来新增 v2 字段且某分支绕过 store 直接 parse | 把版本校验下沉进 codec（`parse` 返回 `v` 或抛 `UnsupportedVersion`） | 现有唯一 parse 入口是 store（`loadLocked` / `importDocument`），已全覆盖 |
| R6 | `markerLanded` 采用 `file.running?.taskId == taskId`（写失败或拒绝都读作未落地，保守放弃派发） | 磁盘短暂满且任务本应正常开跑 | 让 `mutate` 返回 `(file, wrote)` 元组以便区分「拒绝」与「写失败」 | 两者都应放弃派发（无 marker 的运行无法结算），区分只影响日志措辞 |
| R7 | 真机未验证（全部证据 = JVM 表驱动 + 源码形状 + CI 编译级） | 合并前需真机证据时 | 装真机后：①旋转屏分享一次看暂存目录只有一份文件；②连续两次分享验证前一次清理不误删；③改系统时间越过窗口验证同一天不重复触发（需把任务窗口设窄） | 本支收口标准是「分支 CI 绿即停」，真机验证不在本支范围 |

---

## 7. CI 判读

### 7.1 第一轮（#2862 / `40746373171` @ `59dde38e`）：**failure，且是本支自己打出来的真洞**

失败在第 3 步 `Run pre-build scan gate`，唯一红项是夹具 `backup_remap_rollup_check` 缺 `BACKUP_REMAP_ROLLUP_SELFTEST=PASS`。根因不是编译、不是用例：

- `scripts/scan/backup_remap_rollup_check.py` 的 arm M21 把源码里**逐字的一行**当 fixture 锚点：
  `        entries.filter { classify(it) != RollupClass.TRANSIENT }`，变异成 `        entries` 以重建旧缺陷。
- 我第一版把 `distillableEntries` 写成 `dedupeEntries(entries).filter { … }` 单个表达式 ⇒ 该行不复存在 ⇒ 门取不到锚点 ⇒ selftest 少一臂 ⇒ 夹具断言「标记必须出现」判红。
- **这正说明门是结构钉住型**：它不允许「语义等价但形状不同」的改法。而 `scripts/scan/**` 在本支禁改（硬约束 2），所以正确的修法是**让表达式单独成一行**（`val distillable = / entries.filter { … } / return distillable`）—— 锚点逐字仍在，且它读的已经是去重后的列表，臂的语义没有漂移。复跑 `--selftest` = **59 arms matched**。
- 教训（写进残留/后续纪律）：**改动被某道门当作 fixture 锚点的源码前，先 `rg -n "<表达式片段>" scripts/scan/` 反查谁钉了它**，再决定怎么写，而不是等 CI 用 21 分钟告诉你。

### 7.2 第二轮（#2864 / `40748826699` @ `f699d004`）：**success**

| 判据 | 读数 |
|---|---|
| 结论 / 时长 | success，20.6 min（18:50:23Z → 19:11:58Z） |
| `head_sha` | `f699d0047b2db07dcd3c015d62e845b47d621773` —— 与本地 `HEAD` **逐字符一致** |
| 步骤 | 27 步，唯一非 success = **2 个 skipped**（`Move the rolling tag` / `Publish to Releases`，均 main 门控）⇒ 0 个 failure |
| 日志体量 | 1,579,246 字节；`tr -d '\0'` 后字节数不变 ⇒ **NUL 0** |
| 门（scan gate 步） | `Summary: 113 passed, 0 failed` ×1 |
| 夹具 | `ALL 349 CASES PASS` ×1 |
| Kotlin 编译 | `^e: ` 计数 **0**；`^> Task.*FAILED` 计数 **0** |
| 含 `FAILED` 的行 | 6 条，逐条确认全是 **PASSED 用例名子串**（如 `slot denied produces FAILED without calling provider PASSED`）—— 判读陷阱复核 |
| 新测试类在 CI 真跑 | `ScheduledTaskStorePolicyTest` 12 PASSED / `ShareStagedCleanupPolicyTest` 8 / `MemoryRollupConsistencyTest` 5（与本地 25 例一一对应） |

> 「本地夹具全量跑不动」的缺口（S28 `Function not implemented`）由这一步补上：349 例在 CI 全绿，包括 `backup_remap_rollup_check` 的 59 臂。

---

## 8. 停止节点与结论

四条全部先证后修：

- **F-04** 两半都修。其中 F-04B 的修法从「load 后复查」升级为 **storeLock 内 CAS**——因为前者还留着第二个 TOCTOU（`deleteTask` 的 storeLock 段不需要 `checkMutex`，marker 照样会被写到刚删的任务上）。报告的「永久孤儿标记」补正为「活到下一次心跳（≤60s）」，真正的危害是「已删任务真的跑起来了」。
- **F-07** 补上报告漏掉的两层：写失败被折叠成成功（编辑页/恢复弹窗都不再假成功）+ 读失败折叠成空表后被下一次任意写**固化为真相**（一次开关点击即清空全部任务）+ 外版本文件被静默降级写。修法是「不可读 ⇒ 拒写、保留原字节、只留整体替换这一条自救路」，并明确 **0 字节文件是「真的没有任务」不是读失败**。
- **F-11** 一行语义统一三处口径（执行器 / 候选判据 / 提示词挑文件），单条日志行为不变。
- **F-14** 三个缺陷全修，且 **manifest 零改动**——用 `savedInstanceState` 携带已暂存条目，而不是 `configChanges`（后者挡不住进程回收后的重建）。

**停止节点达成 = 分支 CI 绿（#2864 @ `f699d004`），不合并 main。** 合并起手权在用户。

