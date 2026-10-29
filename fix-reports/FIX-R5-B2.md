# FIX-R5-B2：调度写盘与 WebDAV 五项（R5 修后复扫批 2026-10-29）

分支 `fix/r5-b2-sched-webdav` · 基线 `f3def6ce` · 来源货架报告 `R5-B.md`（判级 `TRIAGE.md`）
覆盖：B-F-04 / B-C-02①②③ / B-C-06① / B-F-07

> 本支的**每一条都按语义先证**（读真码确认机制后再动手），行号只作溯源。
> 五条全部成立，无一条被证伪；但**报告的三处边角判断与真码不符**，逐条记在 §2–§5。

## 1. 五条总览

| ID | 先证结论 | 修法 | 证据 |
|---|---|---|---|
| **F-04**（= C-02①） | **成立**。`deleteTask` 在 `mutate` 写败时拿回的是**写前文档**（`ScheduledTaskStorePolicy.visibleAfterWrite` 的 `current` 分支），却在 `:273` 无条件 `onTasksRemoved` ⇒ 内存锁清空 + 磁盘 `running` 标记仍在 = 下一拍 `process-restart` 假崩溃 | 按「任务确已从返回文档消失」**早退**（早退 = 距 release 调用最近的语句，门的 depth-1 形状不变）；真删除仍无条件释放 | 形状探针 3 条（pre 红 → post 绿）+ 门 `scheduled_store_release_guard` 改前后 rc=0 |
| **C-02②** | **成立**（`exportDocument` 的 KDoc 已承认空导出，但**原件随后被覆盖**这一步没有任何兜底） | `importDocument` 覆写前先留 `scheduled-tasks.json.unreadable-<stamp>` 逐字副本（保留 3 份），失败只记日志不阻塞恢复 | 形状探针 1 条（pre 红 → post 绿） |
| **C-02③** | **成立且比报告更精确**：`importDocument` 侧**本来就严格**（`optInt("version", -1)` + `RestorePlan` 拒任何 ≠ 支持版本），不对称只存在于**本机文件**读侧 | 新增 `declaredVersionOf`：缺键 = 本机形状（接受）；非正整数 = 不可读；> 支持版本 = 不可读；正整数且 ≤ 支持版本 = 接受（保留升级路径） | 形状探针 2 条（pre 红 → post 绿） |
| **C-06①** | **成立 ×2**：报告只点了 `!markerLanded` 早退；`prepareSession` 自己在 `updateSource`/`updateSessionTitle` 抛错时**同样遗留**，且此时 `fire` 的 catch 连 `sessionId` 都拿不到 | 新增 `dropNewSession`（自带失败兜底），早退分支与 `prepareSession` 的 try/catch 都只删**本轮新建**的那一个会话，然后照旧抛/返回 | 形状探针 3 条（pre 红 → post 绿） |
| **F-07** | **成立 ×3**（缺 status 默认放行 / propstat 内 href 覆盖路径 / 自身 404-403 in 207 返回空表） | ①两处 status 初值 `false` + 显式 2xx（`isSuccessStatus`）；②`if (!inPropstat) href = text`；③自身条目 `!ok` → `return null` | **新增 12 例 JVM 协议报文测试：pre 8/12 红 → post 12/12 绿**；既有 `WebDavClientTest` 44 例与新增合跑 **56/56 绿** |

## 2. F-04 —— 拒写不再释放运行锁（`ScheduledTasksStore.deleteTask`）

### 2.1 先证

- `mutate` 的返回值来自 `ScheduledTaskStorePolicy.visibleAfterWrite(current, next, writeSucceeded)`：
  **写败返回 `current`**（写前文档，任务与磁盘 `running` 标记都还在），写成才返回 `next`。
- 旧 `deleteTask` 不看返回值，直接 `ScheduledTaskRunner.onTasksRemoved(setOf(taskId))`；
  `onTasksRemoved` 会把 `runningTaskId` 清空并丢掉 `dispatched` 记录。
- `checkAndFireToday` 的判据是 `marker != null && marker.taskId != runningTaskId` ⇒ `crashFailure(task)`。
  于是「删失败 + 任务正在跑」= 每分钟给一个完全正常的任务记一次崩溃失败，5 次触发熔断停用。

### 2.2 修法与「保留刻意语义」

```kotlin
val next = mutate(context) { … }
if (next.tasks.any { it.id == taskId }) return next   // 写败：什么都没删，运行态不许动
ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))  // 真删除：仍无条件
```

- **`[da1016 B-7c]` 的「Unconditional on purpose」原样保留**（注释就地扩写为两句）：
  它讲的是「真删除不许因为『看起来没有在跑』就漏掉释放」——本支新增的早退讲的是
  「删除根本没发生」，两者正交：**落地就释放（无条件），没落地就没有释放的理由**。
- 早退写成「最近语句 + 早退」而不是 `if (…) { release() }`：门 `scheduled_store_release_guard`
  要求 release 调用出现在函数体 **depth 1**（嵌套 = 可能在 store 锁内持锁，与 `markMutex` 死锁），
  条件包裹会把 depth 变成 2。**改法与会话锁序契约一致，不是为了过门。**

### 2.3 屏幕半边

`ScheduledTaskEditScreen` 绑定 `deleteTask` 的返回值：任务仍在 → `deleteRefused = true` 并 `return@launch`
（**不 pop**），本屏渲染一行写失败提示。旧行为 = 弹窗关闭 + `onDone()`，用户在列表里看到任务还在，
却已经被「告知」删除成功。

> ⚠ 文案复用 `R.string.backup_err_write`（"Failed to write file: %1$s" + 文件名）。
> 这是**本支文件集约束下的取舍**：新增 `scheduled_delete_failed` 要动 7 个 `strings.xml`
> （不在 §3 的四文件集内，且 strings 是并行分支最容易撞的公共资源）。已登记残留 R1。

## 3. C-02② —— 恢复前先保住「读不出」的原件（`importDocument`）

### 3.1 先证

- `exportDocument` 在 `unreadable` 时导出的确实是**空任务集**（KDoc 已承认，并说明了为什么签名不能带失败：
  调用点全在 `backup/`，本支禁碰）⇒ 恢复前的回滚快照里，tasks 段是空的。
- `importDocument` 是**唯一**被允许在 `unreadable` 状态下写盘的路（KDoc 明文：恢复是用「我们确实有的字节」
  替换坏文件）。但它覆写的正是**唯一一份**读不出的原件。
- ⇒ 用户同时失去：①原文件（被覆盖）②快照里的任务（空集）。**报告这一条成立。**

### 3.2 修法

覆盖前调 `preserveUnreadableLocked(context)`：`copyTo` 一份逐字副本
`scheduled-tasks.json.unreadable-<stamp>`（**故意不带 `.json`**：没有任何代码会把它当任务文档捡起来），
按名字倒序保留最新 3 份；拷贝失败只 `AppLogger.warning`，**不阻塞用户的恢复动作**。

> 为什么不是「跳过空快照」：快照 payload 由 `ConfigBackup` 生成，改它就越出本支文件集（那是 B1 的半径）。
> 本文件能提供的兜底只有「原件字节」这一层——所以选任务书给的第一个选项。

## 4. C-02③ —— 本机文件的 version 读取口径（`loadLocked`）

### 4.1 先证（报告说「两处不一致」，方向对，细节要修正）

- 本机读侧：`optInt("version", ScheduledTasksCodec.VERSION)` + 只判 `> VERSION`
  ⇒ `0` / `-3` / `"v2"` / `null` **全部**被 coerce 成 v1：文档被宽松解析，下一次任何修改
  再按 v1 重写，写者关心的未知字段静默蒸发。
- 恢复读侧（`importDocument`）：`optInt("version", -1)` + `RestorePlan.decide` 拒任何
  `declaredVersion != supportedVersion` ⇒ **本来就已经严格**。所以不对称只在「本机文件」这一侧。

### 4.2 修法

`declaredVersionOf(root)`：缺键 → `null`（pre-versioning 文档 = 本机形状，接受）；
存在但不是正整数（含非数字、0、负数、非整数值）→ `INVALID_DECLARED_VERSION`（= 不可读）；
正整数且 `> VERSION` → 不可读；正整数且 `≤ VERSION` → 接受（**保留升级路径**：未来 v2 读 v1 仍可迁移，
若照搬「≠ 支持版本就拒」，一次版本升级会把所有老文件锁死）。

不可读 = 走既有 `unreadable` 通道（拒写 + 大声记日志），不新增第二种状态。

## 5. C-06① —— 放弃的一拍不再遗留空会话（`ScheduledTaskRunner`）

### 5.1 先证

- `prepareSession`：`ensureSession(app)` **先插库**，随后才 `updateSource` / `updateSessionTitle`。
- `fire` 在 `markRunning` 之后用 `ScheduledTaskStorePolicy.markerLanded` 门控；未落地时旧代码只复位
  `runningTaskId` / `AgentRunBudgetCeilings` 就 `return` ⇒ 库里留下一条空 `scheduled` 会话
  （有任务标题、零消息），用户会话抽屉里永久可见。
- **报告没点的第二半**：`prepareSession` 的两次写抛错时，`fire` 的 catch 里 `sessionId` 仍是 `null`，
  连清理对象都指不出来 ⇒ 同样遗留。

### 5.2 修法与「只删本轮新建的」

`dropNewSession(app, sessionId)`：`chatRepository.deleteSession(sessionId)`（整份删除：行 + 会话文件 + 媒体），
失败 `catch (t: Throwable)` 只记日志——**清理失败不许把 `fire` 自己的错误处理带下去**。

两处调用点的「这一条是本轮新建的」判据不需要额外证据：
`sid` 来自 `HeadlessChatRunner.ensureSession`（每拍新 id，永不复用），
且两处都在 **prompt 之前**（早退发生在派发线程启动之前）⇒ 用户既没看到、也没写进任何内容。

## 6. F-07 —— WebDAV 多状态解析 fail-closed

| # | 缺陷 | 修法 |
|---|---|---|
| ① | `propstatOk` / response 级 `statusOk` 初值 `true`（缺 `<status>` 默认放行）；`text.contains(" 200")` 漏 201/204/207 | 两处初值 `false`；新增 `isSuccessStatus`（取第一个三位数字 token，判首字符 `2`），`HTTP/1.1 207 Multi-Status` / `HTTP/2 200` / 裸 `200 OK` 都读对，空/截断/非数字一律**不算成功** |
| ② | `<propstat>` 内的 `<href>`（`lockdiscovery`、404 属性块）覆盖外层资源路径 | `"href" -> if (!inPropstat) href = text` |
| ③ | 被请求的集合自身在 207 里报 `404/403` → 返回 `emptyList()` ⇒ `ensureCollectionExists` 跳过 `MKCOL`、`testConnection` 假成功 | 自身条目 `!ok` → `return null` ⇒ `propfind` 抛 `WebDavNonDavResponse(207)`（fail-closed，用户看到失败而不是「没有备份」） |

> 与 backlog R10「propstat 组全失败 + response 级 200 → 丢弃」**同向**，没有反其道。
> 保留：真正空的 `207`（无任何 response）仍返回空表 ⇒ 空目录语义不变（有测试钉住）。

## 7. 证据装置与门

### 7.1 装置（全部本地可复跑，脚本在 `shared/work/r5-1029/`）

| 件 | 内容 | 读数 |
|---|---|---|
| `run_webdav_jvm_all.sh` | 真码 `WebDavClient.kt` + `WebDavSync.kt` + `BackupRotation.kt` + 既有 `WebDavClientTest`(44) + 新增 `WebDavMultistatusFailClosedTest`(12)，kotlinc + JUnitCore | **56/56 绿** |
| 对拍（修复前） | 把 `WebDavClient.kt` 换回 `HEAD` 版本，跑同一套新测试 | **8/12 红**（4 条绿的是「不许改变」的守卫：空 207、自身不入列表…） |
| `run_shape_prepost.sh` | `ScheduledTaskDeleteReleaseShapeTest`(9) 对 `wt-b2`（post）与 `HEAD` 三文件（pre）各跑一次 | **post 9/9 绿；pre 8/9 红** |
| 单门 | `scripts/scan/scheduled_store_release_guard.py .` + `schedule_date_source_check.py` + `backup_streaming_export_check.py` + `browser_destroy_wakeup_check.py` | 全 rc=0（改前改后一致） |

**pre 侧仍绿的那 1 条**是 `deleteTask_keepsTheUnconditionalReleaseContractForLandedDeletes`——
它钉的是**保留下来的刻意语义**（`[da1016 B-7c]` 注释 + `return next`），修复前后都该绿。它不是修复证据，
是防回归钉。

### 7.2 为什么不是「跑 CI 就算证据」

WebDAV 那 12 例是**真协议报文**驱动真解析器（反射取私有 `parseMultistatus`，不是复刻算法），
它们在修复前的树上 8 条必红 —— 这条 pre/post 对拍 + CI 绿 = 三源取二。

## 8. 残留（都带触发条件）

| ID | 残留 | 为什么这次不做 | 触发条件 |
|---|---|---|---|
| **R1** | 删除失败的提示复用了 backup 域的 `backup_err_write` | 新增专用 key 要动 7 个 `strings.xml`，越出本支文件集（并行分支公共资源） | 下一轮允许改 strings 时补 `scheduled_delete_failed`，一次替换屏上那行 |
| **R2** | `unreadable` 时**快照**里的 tasks 段仍是空集（本支只保住了原件字节） | 快照 payload 在 `ConfigBackup`（B1 半径），`exportDocument` 的签名带不了失败（KDoc 已说明） | 若 B1 给 `exportDocument` 加 `Result`/`null` 语义，这里再补「跳过空段」 |
| **R3** | `prepareSession` 里 `updateSource` 成功、`updateSessionTitle` 失败时，会话被删（标题写失败 → 整条会话丢弃） | 这一拍的会话本来就没派发，丢掉比留半成品更干净；且重试会累积孤儿 | 若将来「标题写失败」变成可容忍（如标题仅展示），改为只记日志不删 |
| **R4** | 自身条目 `!ok` → `WebDavNonDavResponse(207)`：少数把「目录不存在」也塞进 207 的服务器会从「假成功」变成「报错」 | 这正是 fail-closed 的目的（假成功比报错更贵）；backlog R10 同向 | 若真机日志出现该形态服务器，再考虑「自身 404 in 207 视同 404」的窄化映射 |
| **R5** | 全部证据 = CI 编译级 + JVM 级 + 源码形状级，**真机未验** | 删除失败/207 异常报文都是难触发的路径，真机造不出来 | 报「删了任务还在」「测试连接成功但备份失败」时按本条回查 |
