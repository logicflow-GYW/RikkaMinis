# FIX-R5-A3 — 分享暂存两条竞态（A-C-09① 增量登记 · A-F-07 in-flight 保活）

> ⚠ **跨界声明（任务书 §0 要求）**：本支修改的 `share/ShareReceiverActivity.kt`
> 与 `share/SharedShareStore.kt` 属 **R5-F 审计包**（其报告未到）。本支独占修这
> 两个文件；wave-2 判级请先查 drift：本报告的 §3 列出每一处改动与理由，
> `git diff f3def6ce..2c5d686d` 是唯一真值。

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `f3def6ce`（worktree `git rev-parse --short HEAD` 提交前逐字符一致） |
| 分支 | `fix/r5-a3-share-race` |
| worktree | `/var/minis/shared/work/r5-1029/wt-a3` |
| 提交序列 | `2c5d686d` 生产 2 + 测试 2（**CI 绿于此**）→ 报告回填（仅 `fix-reports/`） |
| CI | run **`40853640945`**（#2897）`success` 21.8 min @ `2c5d686d0072eaa8fa794eefdda583e035de0563`（与本地 HEAD 逐字符一致） |
| 门/夹具计数 | **113 门 / 349 夹具不变**（`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰） |
| 改动面 | 生产 3：`ShareReceiverActivity.kt` +92/−9 · `SharedShareStore.kt` +25/−7 · `StagedBytesLedger.kt` +65/−0｜测试 2 新建（+162 / +253）｜报告 1 |

**这改变什么用户行为？**（纪律 2）
- 从相册/文件管理器分享多张大图时转屏（或 Activity 被系统回收重建）：以前重建实例会把同一批文件**再拷一遍**（`shared-<新id>`，第二份落盘 + 第二次计入 500 MB 预算），已拷好的那几份则永远没人引用；现在重建实例直接接手已落盘的清单，**不再重复拷贝、不再重复计费**。
- 上一条分享刚在聊天页被消费（触发清理）时紧接着发起新分享：以前新分享已经写进 `share_extension/` 的文件会在「拷多文件 / 弹 provider 导入对话框等你按键」这段时间里被上一轮的清理当孤儿删掉，聊天框拿到**指向不存在文件的幽灵附件**；现在这些文件在创建之前就登记为 in-flight，清理不会碰它们。

## 2 缺陷与先证（按语义取证，不按报告行号）

两处窗口都先读真码确认存在，再动手。报告给的 `ShareReceiverActivity.kt:125-126`、
`:174`、`:228-281`、`SharedShareStore.kt:65-80` 行号在真树**大体对得上**（本支基线
与报告基线同为 `f3def6ce`），但下述机制链是重新走真码得出的，不依赖报告的那几个
数字。

### 2.1 A-C-09① 成立（孤儿文件 + 账本重复累加）

- 修前 `extractedItems` 是 `List` 字段，唯一赋值点在一次 `extractItems()` **返回之后**：
  `val items = if (restored.isNotEmpty()) restored else extractItems(); extractedItems = items`。
- `extractItems()` 内部 `handleSingleSend/handleMultipleSend/handleView` 逐个
  `copyUriToStaging`（每文件上限 100 MB、单文件超时 30 s），因此「全部拷完」与
  「`extractedItems` 有值」之间隔着**整个拷贝过程**（多文件时是 N 次 100 MB 级 IO）。
- `onSaveInstanceState` 只在 `extractedItems.isNotEmpty()` 时写 `STATE_ITEMS` ⇒ 在那段
  窗口里保存状态 = **不写**。
- 重建（转屏 / 低内存回收）时 `savedInstanceState != null` 且 **intent 是同一个**；既然
  没有 `STATE_ITEMS`，`restoredItems` 为空 ⇒ 走 `extractItems()` **重新拷一遍**：
  ①每个附件多一份 `shared-<新id>`；②`copySharedStream` 成功即 `stagedBytes.credit`，
  预算被重复累加；③第一份文件此后没有任何 item 引用它，只有 `cleanSharedFiles` 的
  「无人引用」判据能收它 —— 而它恰好在 §2.2 的窗口里同样会被当成孤儿处理，属于
  两条缺陷叠加。
- **verdict 复核**：报告 verdict「若拷贝途中转屏、`onSaveInstanceState` 保存为空，
  已拷文件成孤儿且账本重复累加」→ **成立**，且比报告描述更宽：`Lifecycle` 在
  `onStop` 之后即使被系统杀（进程活着）也会走同一路径。

### 2.2 A-F-07 成立（新分享落盘 → 写 prefs 之间被清理误删）

- 写入点：`copyUriToStaging`（`ACTION_SEND` / `SEND_MULTIPLE` / `VIEW`）与
  `AttachText` 分支都把文件写进 `filesDir/share_extension/`。
- 引用点：唯一把文件名写进 prefs 的是 `finishWithAttachmentFlow` →
  `SharedShareStore.savePendingShare`。二者之间隔着：多文件顺序拷贝、`withContext(Main)`
  切回、以及 `.json` 候选的 `promptProviderImportOrAttach` **对话框等用户按键**。
- 清理点（3 个生产调用点，纪律 3 普查）：`ChatScreen` 分享注入后 1 处、
  `ShareCoordinator.processPendingShare`（stale 分支）1 处、
  `ShareCoordinator.consumeBuffer`（TTL 过期分支）1 处。三处都在**同一进程**，
  且第一处就发生在「上一条分享刚被注入」这一刻 —— 正好与新分享的拷贝/等待期重合。
- 修前 `cleanSharedFiles` 的保活集只有 `loadPendingShare(context)?.items`；此时 prefs 里
  要么是上一条（已 `clearPendingShare` 后为 `null`），要么为空 ⇒ 新分享的文件
  `f.name in referenced == false` ⇒ `f.delete()`。
- **verdict 复核**：成立。补一条报告没写的放大条件：`MAX_ATTACHMENTS = 50` +
  单文件 100 MB ⇒ 该窗口最长可达「50 次拷贝 + 一次人工按键」，不是毫秒级。

## 3 修法（阶梯：停在第 6 级「最小实现」）

阶梯记录：①真要做吗 —— 是，两条都是用户可感知的数据损坏（幽灵附件 / 双份落盘）。
②代码库里已有 —— 保活集的**形状**已有（`referenced`），缺的是第二个来源；纯函数
外壳 `StagedBytesLedger` 已在，扩展它而不新建文件。③标准库 —— `synchronizedList` /
`ConcurrentHashMap.newKeySet` 即可，不引依赖。④平台原生 —— `onSaveInstanceState` 已在用。
⑤已装依赖 —— 无新增。⑥能一行吗 —— 不能（要在生产者侧登记 + 清理侧合并 + 顺序约束）。
⑦最小实现 —— 见下。

### 3.1 `StagedBytesLedger.kt`（+65，新符号 2 个 + 纯函数 1 个）

- `internal object StagedInFlightFiles`：进程级 `ConcurrentHashMap.newKeySet<String>()`，
  `begin/end/endAll/snapshot`。**为什么放这个文件**：本支只允许动三个文件，而这个
  文件的定位已经是「暂存域的 JVM 可测内核」（`StagedBytesLedger` + `admitAttachText`），
  登记表无 Android 依赖，放这里能进 JVM 单测；放 `SharedShareStore` 会引入
  `android.content.Context` 导致不可测。
- `internal fun shouldKeepStagedFile(fileName, referenced, inFlight)`：保活判定抽成纯
  函数（`referenced` ∪ `inFlight`），KDoc 写明**调用方必须先列目录再取快照**的原因。

### 3.2 `SharedShareStore.cleanSharedFiles`（+25/−7）

```kotlin
val listed = dir.listFiles().orEmpty()          // ← 先列
val referenced = loadPendingShare(context)?…    // ← 再取两组保活集
val inFlight = StagedInFlightFiles.snapshot()
for (f in listed) if (shouldKeepStagedFile(f.name, referenced, inFlight)) {…} else {delete}
```

- **顺序是判据的一部分**：生产者「先登记后建文件」，所以「列目录」时看到的任何
  条目，其登记必定早于后面的快照 ⇒ 反向窗口也被关掉（反过来写就有一段时间差）。
- **字节账目只计 `referenced`**（不在 in-flight 上重复计）：in-flight 文件的字节
  要么已在进程级账本里（还原路径），要么会在拷贝完成时 `credit`，把半截长度再加
  一遍会双计。
- KDoc 第 1 条同步改写（旧文写「唯一的记录就是 pending share」＝ 已不成立）。

### 3.3 `ShareReceiverActivity`（+92/−9）

1. `extractedItems` 由 `var List` 改为 `private val MutableList = Collections.synchronizedList(mutableListOf())`；
   `extractItems()` 不再自建列表、不再返回值，直接把活列表交给三个 handler（`val items = extractedItems`）。
   `onSaveInstanceState` 用 `stagedItemsSnapshot()`（`synchronized(extractedItems) { toList() }`）。
2. `restoreStagedItems(items)`：把还原集合灌回字段，并**重新登记**为 in-flight（还原的文件
   此刻没有任何 pending-share 引用，否则会被并发清理当孤儿删）。
3. 生产者侧 3 处 `begin(name)`（流拷贝 1、文本附件 1、还原 1），失败臂 3 处 `end(name)`
   （拷贝 Failed、写失败、丢弃），交接点 1 处 `endAll(attachmentNames(items))`
   —— **在 `savePendingShare` 之后**（保存成功 = prefs 成为引用；保存失败 = 真孤儿，
   必须允许下一轮清理收走，不能永久保护）。

## 4 覆盖面（调用点普查计数）

| 符号 | 生产调用点 | 说明 |
|---|---|---|
| `cleanSharedFiles(` | **3**（`ShareCoordinator` ×2、`ChatScreen` ×1） | 三处全部走同一个保活判定，无需各自加参数 |
| `savePendingShare(` | **1**（`ShareReceiverActivity.finishWithAttachmentFlow`） | 交接点唯一 ⇒ in-flight 释放点唯一 |
| `loadPendingShare(` | 2（`SharedShareStore.cleanSharedFiles`、`ShareCoordinator.processPendingShare`） | 语义未变 |
| `StagedInFlightFiles.begin(` | 3（流拷贝、文本附件、还原） | 覆盖所有落盘入口 |
| `StagedInFlightFiles.end(` | 3（拷贝失败、写失败、provider 导入消费） | 覆盖所有「文件不会再有引用」的出口 |
| `StagedInFlightFiles.endAll(` | 1（交接） | 交接 = prefs 接管 |

- 同族检查：`share_extension/` 的**所有**写入点都在 `copyUriToStaging` / AttachText
  两处（`rg -n "sharedFileDirectory(this)"` 只命中这两处 + `providerExportJsonOrNull` 的只读），
  无第三处遗漏入口。
- 半径外不动：`ShareCoordinator` / `ChatScreen` / `ChatViewModel` 未改（门 C8/C9 判据锚在
  它们身上，本支零触碰即零风险）。

## 5 验证四层

**L1 纯函数（JVM，19 例全绿）** — `ShareStagedInFlightTest`（10 例）
- 保活表：referenced / in-flight / 两者 / 皆无 / INLINE_TEXT 值不保活（5 例）；
- 登记表：claim→snapshot→release 生命周期、`endAll` 批量、空名与未知名的 no-op、
  **snapshot 是拷贝而非活视图**、4 线程 ×50 次并发 claim/release 无残留（5 例）。
- 期望值全部字面量（不从生产集合推导）。

**L2 形状（源码断言，9 例全绿）** — `ShareStagedRaceWiringTest`
每条判据对应一处接线事实：递增登记（含 `hasLine` 精确行匹配，防
`val items = extractedItems.toMutableList()` 这种"包含即通过"的假绿）· 快照在锁内 ·
还原重新登记 · 拷贝前登记且早于建文件 · 文本登记早于 `writeText` 且 credit 在其后 ·
**先列目录后取快照** · 交接后在 `savePendingShare` 之后释放 · 丢弃路径逐个释放 ·
登记表是进程级单例且 `snapshot` 是拷贝。

**L3 变异臂（12/12 全红，一条臂 = 一处回退）**

| 臂 | 变异 | 结果 |
|---|---|---|
| keeprule | 保活判定去掉 `inFlight`（**重编译 ledger**，行为臂） | RED ×3 |
| nolistsnapshot | `snapshot()` 返回活视图（重编译 ledger） | RED ×2 |
| referencedonly | `cleanSharedFiles` 只看 `referenced` | RED ×1 |
| snapfirst | 先取快照再列目录 | RED ×1 |
| releasefirst | 释放挪到 `savePendingShare` 之前 | RED ×1 |
| renotclaim | 还原时不重新登记 | RED ×1 |
| claimlate | `begin` 挪到建文件之后（修前形态） | RED ×1 |
| assigonce | 恢复「拷完再一次性赋值」 | RED ×1 |
| livesave | `onSaveInstanceState` 读活列表 | RED ×1 |
| notimesave | 快照不持锁 | RED ×1 |
| discardnorelease | 丢弃路径不释放 | RED ×1 |
| localhandlerlist | handler 拿私有拷贝（修前形态） | RED ×1 |

- 基线（未变异源码）用同一套 harness 跑 = `OK (19 tests)`，即「全红」不是 harness 假红。
- 首轮 `localhandlerlist` 是**假绿**（`contains("val items = extractedItems")` 命中了
  `...= extractedItems.toMutableList()`），改判据为精确行匹配后转红 —— 记在此处，
  因为它正是「形状断言退化成 contains」的典型。同理 `keeprule/nolistsnapshot` 两条
  初版只做文本变异，改判据后升级为重编译的**行为臂**。

**L4 云端（CI 真值）** — run `40853640945` / `success` / 27 步：
- `head_sha` = `2c5d686d0072eaa8fa794eefdda583e035de0563` 与本地逐字符一致；
- 27 步中唯一非 success = 2 skipped（`Move the rolling tag` + `Publish to Releases`，均 main 门控）；
- 日志 1,629,878 B、**NUL 0**；`Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1、
  `^e: ` **0**、真 FAILED 行 **0**（`FAILED` 子串全为 `PASSED` 用例名）、`Function not implemented` 0；
- 新增 19 条用例在日志里逐条 `PASSED`（10 + 9，`ShareStagedInFlightTest` /
  `ShareStagedRaceWiringTest`），0 FAILED；
- **门基线不变**：113 / 349（本支未改任何门脚本；三支相关门对真树单独复核 rc=0，见下）。

**门前置（本支相关三支，逐支 rc=0）**：`discarded_return_guard.py`（A-07/A-08 判据
锚在 `ShareReceiverActivity.debitStagedBytes` / AttachText 信用序）、
`readonly_mount_write_guard.py`（share 者 shape 判据）、
`file_provider_paths_coverage_check.py`（C12 `extractItems` 分支路由）。
其中 **C12 当场抓了一次真问题**：把 `extractItems()` 改成 `handleX(intent, extractedItems)`
后，门的判据是「分支调用实参里出现本地列表名 `items`」，而 `extractedItems` 里是
`Items`（大写 I）⇒ 门报「分支没把列表交给 handler」。修法不是改门，而是保留一个
**活别名** `val items = extractedItems`（同一对象，非拷贝）——门判据与代码语义同时成立。

## 6 残留（每条带触发条件 + 触发后方案）

| ID | 残留 | 触发条件 | 触发后方案 |
|---|---|---|---|
| R1 | in-flight 登记是**进程级**且只在「交接 / 丢弃 / 写失败」三处释放；进程活着但分享 Activity 被永久销毁（未到任何出口）时，那批名字会一直保护其文件不被清理 | 观察到 `share_extension/` 长期残留文件且 prefs 无 pending share，同时每次清理日志 `in flight` 计数 > 0 | `onDestroy` 里加 `if (isFinishing) endAll(staged)`；注意不能拦 `isChangingConfigurations`（转屏要保留） |
| R2 | 进程被杀后还原（`savedInstanceState` 存活、进程级账本归零）：文件还在、账本为 0，500 MB 预算短暂偏松 | 进程死亡后重建并继续分享 | 还原时按 `stagedItemsSnapshot()` 里 ATTACHMENT 文件的真实长度 `credit` 一次（需避免与"同进程转屏"重复累加 ⇒ 必须先能判定账本是否已被本进程重新初始化；当前无该信号） |
| R3 | `copySharedStream` 只 `catch (Exception)`；若 OOM 等 `Error` 穿透，`begin` 的登记不会被释放（文件也可能留着半截） | 观察到 in-flight 只增不减 + 半截文件 | 在 `copyUriToStaging` 外层加 `try/finally` 释放（`finally` 里按 outcome 决定），或让 core 一并捕获 `Throwable` 并按失败处理 |
| R4 | 清理与「登记」之间的**语义**竞态已关闭，但 `SharedPreferences.apply()` 是异步落盘：交接后立刻进程被杀，prefs 可能未写盘 ⇒ 文件成为孤儿（无害，会被下次清理收走） | 交接即被杀 | 不接受修复（代价是 `commit()` 阻塞主线程）；保持 `apply()` |
| R5 | 真机未验：本支证据全为 JVM + 源码形状 + CI 编译级；转屏窗口与对话框窗口在真机上的实际宽度未实测 | 真机复验批次 | 按 §7 的三步真机脚本执行 |

## 7 未验证边界

1. **真机转屏复现**（C-09①）：分享 10 张图 → 拷贝途中转屏 → 期望 `adb` 侧
   `share_extension/` 只出现 10 个文件（修前会出现 20 个 + 前 10 个无人引用）。
2. **对话框窗口**（F-07）：发起分享 `.json`（provider 导出形状）→ 停在导入对话框 →
   切到聊天页消费上一条分享 → 返回对话框点「作为附件添加」→ 期望附件可打开（修前文件已被删）。
3. **并发清理**（F-07 的核心复核）：把 `cleanSharedFiles` 在拷贝中途手动触发一次（debug 入口或
   临时日志钩子），期望 `in flight` 计数 > 0 且这些文件未被删。
4. 未覆盖：`MAX_ATTACHMENTS = 50` 的极端批量（沙箱无法构造 50 个真实 provider URI）。

## 8 附装置路径

- 报告：本文件
- 测试：`src/android/app/src/test/java/com/rikkaminis/app/share/ShareStagedInFlightTest.kt`、
  `…/ShareStagedRaceWiringTest.kt`
- 变异臂 harness：`/var/minis/shared/work/r5-1029/probe-a3/arms.sh`（12 臂 + 基线；
  臂根目录在 `probe-a3/arms/`，每条臂 = 源码文本变异 + 同一份已编译测试类，两条
  行为臂另重编译 `StagedBytesLedger.kt`）
- 编译/运行命令（沙箱 JVM）：
  `kotlinc -no-stdlib -jvm-target 17 -cp kotlin-stdlib-2.1.0.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar -d out <ledger> <两个测试类>`；
  `java -cp out:… org.junit.runner.JUnitCore com.rikkaminis.app.share.ShareStagedInFlightTest com.rikkaminis.app.share.ShareStagedRaceWiringTest`
  （工作目录须为 `src/android`，形状测试按 `user.dir` 向上找 `app/src/main/...`）
- CI 日志：`/var/minis/shared/work/r5-1029/probe-a3/logs/build/`
