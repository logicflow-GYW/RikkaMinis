# FIX-1028-BACKUP-CHANNEL：恢复通道四合一（A-5 / A-6 / A-16=B-5 / B-12）

分支 `fix/p1028-backup-channel-guards`，基线 main `12bdcebb`。
工作树 `/var/minis/shared/work/p1028-fix/wt-backup`。

四条缺陷是同一个形状：**读不出来 → 折叠成「没有」→ 下一次写入把它固化成新真相 / 或界面报成功**。
本支把它们逐个拆开：每一处「读不到」都必须与「确实是空」区分开，并且区分的结果要能到用户眼前。

---

## 0. 改动面

| 文件 | 变化 |
|---|---|
| `backup/BackupRotation.kt` | **新增**（纯 java.io/java.text）：唯一的轮转排序键 + 唯一的删除规则 |
| `backup/OpenMinisBackupCompat.kt` | A-5 计数 + A-6 出口（+82/−20） |
| `backup/ConfigBackup.kt` | A-5/A-6 读取端、A-16 结果回传、B-12 快照排序与清理（+109/−14） |
| `scheduled/ScheduledTasksRestorePlan.kt` | **新增**（纯函数）：恢复决策（空集地板 / 版本闸门） |
| `scheduled/ScheduledTasksStore.kt` | `importDocument` 返回 `ScheduledTasksRestoreOutcome`，单锁内读改写 |
| `backup/WebDavSync.kt` | 远端列表/轮转改按名字时间戳，`protectName` 保护刚上传的那份 |
| `backup/AutoBackupManager.kt` | 本地列表/轮转同款，未知年龄不删 |
| `ui/settings/BackupSettingsScreen.kt` | 两处时间显示改用列表实际排序键 |
| 5 个测试文件（2 新增 3 扩充） | 见 §3 |

`src/` 之外零改动：`scan.sh` / `test_scan.py` / `scripts/scan/*` 一行未动（**不建新门**）。

---

## 1. 逐条先证（原文取自 `12bdcebb`，行号为该提交内行号）

### A-5 JSONL 行级解析失败无计数

`backup/OpenMinisBackupCompat.kt:459`（三个 JSONL 基址共用同一循环）：

```kotlin
runCatching { JSONObject(t) }.getOrNull()?.let { records.add(it) }
```

半截行（截断包 / 传输中断 / 半写分片）在这里被丢弃，**不计数、不进任何通道**。
缺片有通道（P1-B 的 `shardsMissing` → `chatTruncated` → `chatTruncationNotes`），坏行没有：
同样「少了消息」，一个报、一个不报，因为前者是「片不存在」、后者是「片存在但内容坏」。
报告 A-5 的判据成立；顺带确认 `JsonlShardRead` 是 P1-B 新建的结构，本支按任务书要求复用而不是另造。

### A-6 转换层 `notes` 全文件无消费方

三点写入，全部写给空气（`OpenMinisBackupCompat.kt:680 / :856 / :881`）：

```kotlin
notes.add("provider \"$label\"：没有模型条目，仍按 0 模型导出")
notes.add("skill \"$skillId\"：${t.message ?: "打包失败"}")
notes.add("memory \"${f.relativeTo(root).path}\"：超过上限跳过")
```

`grep -rn notes` 在本树上除局部传递（`convertProviders/convertSkills/convertMemory` 参数）外**没有任何读出点**，
也没有进 `doc`。三条都是用户级文案（「这个 provider 一个模型都没带过来」「这个 skill 没打包成功」），
而它们描述的场景恰恰是「恢复看起来成功、内容却变少」。

### A-16 = B-5 scheduled tasks 空集覆盖（双伤）

`backup/ConfigBackup.kt:1982`（`12bdcebb`）：

```kotlin
onScheduledTasks(scheduledObj)
scheduledTasksImported = true
```

`ScheduledTasksStore.importDocument`（`scheduled/ScheduledTasksStore.kt:73-76`）：

```kotlin
suspend fun importDocument(context: Context, document: JSONObject) {
    lock.withLock {
        val file = ScheduledTasksCodec.parse(document.toString())
        writeLocked(context, file.copy(running = null))
    }
}
```

`parse` 逐条防御式丢弃（未知/空 id 直接跳过），所以「包内 3 条、全坏」与「包内 0 条」都得到空文档，
空文档无条件落盘 ⇒ **本机全部定时任务被清空**，而 `importDocument` 返回 `Unit`、调用方随即置
`scheduledTasksImported = true`。`version` 字段同样是只写不读（`parse` 里不读、`decide` 里不存在）。
A 支（W8 对端格式漂移）与 B 支（W9 多副本仲裁）**各自独立命中同一处**，是这轮最硬的交叉验证信号。

### B-12 轮转 / 快照的仲裁键不可靠

四处，同一个错误假设（「mtime / 服务器时间 = 写作顺序」）：

| 位置（`12bdcebb`） | 原文 |
|---|---|
| `ConfigBackup.kt:2386` | `}?.sortedByDescending { it.lastModified() } ?: emptyList()`（`listSnapshots`） |
| `ConfigBackup.kt:2353` | `.drop((SNAPSHOT_KEEP - 1).coerceAtLeast(0))`（`pruneSnapshots`，先按 mtime 排过） |
| `AutoBackupManager.kt:70 / :81` | `?.sortedByDescending { it.lastModified() }` / `if (keep <= 0) files else files.drop(keep)` |
| `WebDavSync.kt:216 / :265 / :180` | `sortedByDescending { it.lastModified }` / `.drop(keep)`（远端，键是 `getlastmodified`） |

危害链（全部静默）：
1. 恢复流程先写快照再导入；时钟回拨时新快照 mtime 看起来比旧的**老** ⇒ `listSnapshots` 把错的那行排第一
   （`BackupSettingsScreen` 的一键回滚取 `snapshotFiles.firstOrNull()`），并且 `pruneSnapshots`
   把刚写的那份删掉（`SNAPSHOT_KEEP = 1` ⇒ 没有第二份回滚点）。
2. 远端服务器不给 `getlastmodified` ⇒ `resourceToBackupItem` 全部 `Instant.EPOCH` ⇒「最新优先」退化成
   **服务器列表顺序**，轮转删掉的是任意一份（很可能就是刚上传的）。
3. 一次 `touch` / 一次文件管理器恢复就能重排历史，内容一个字节都没变。

而文件名里**本来就带时间戳**（`rikkaminis-snapshot-yyyyMMdd-HHmmss.json`、`rikkaminis-auto-…`、
`rikkaminis-backup-auto-…`），KDoc 里写着「单一事实源」却没有任何读取方使用它。

---

## 2. 修法

### 2.1 新增 `BackupRotation`（唯一的排序键 + 删除规则）

- `stamp(now)`：`yyyyMMdd-HHmmss`。原来三处各自拼这个格式（快照 / 自动备份 / 远端推送），现在共用一处——
  读者解析的就是写者写出的形状，写者改写形状会让**所有文件变成「年龄未知」从而永不回收**，这个耦合必须显式化。
- `stampMillis(name, prefix, suffix)`：解析名字里的时间戳；接受秒精度与分钟精度
  （`ConfigBackup.suggestedFileName` 的本地导出名就是分钟精度，用户手工塞进 WebDAV 目录的也是这种）；
  接受 `-2` 同秒冲突后缀；**严格解析**（`isLenient = false`，`20261308-000000` 返回 null 而不是滚成 2027 年 1 月）。
- `newestFirst(...)`：名字时间戳降序；**未知年龄垫底**；同秒按冲突副本优先（`a` 是 `b` 的 stem + `-<digits>` ⇒ `a` 更晚写）。
- `rotationVictims(items, name, key, keep, protect)`：删除候选 = 有年龄的条目越过 keep 的那部分，**减掉 protect**；
  **未知年龄永不为候选**。`keep <= 0` 保留「没有历史」语义（所有有年龄的都是候选）。

### 2.2 A-5：计数而不是丢弃

- `JsonlShardRead` 加 `unparsableLines: Int = 0`（复用 P1-B 的结构，未另造）。
- `readJsonlShards` 数非空但解析失败的行。
- `ShardLoss` 累计器同时收「缺片成员名」与「坏行数」，`shardLossReport` 在任一非零时产出报告段；
  **只在坏行非零时**写 `unparsableLines` 键，缺片键维持原语义（任务书禁区：不改既有键语义）。
- 读取端 `chatTruncationNotes` 解码成**独立一行**，不与缺片文案合并（缺片=包少了一片，坏行=片在内容坏，
  用户能做的事不同）。

### 2.3 A-6：给 notes 一个出口

- 转换产物 `doc.put("conversionNotes", JSONArray(notes))`（非空才写）。
- `ConfigBackup.import` 读进 `skipped`（既有通道，备份页已在渲染），前缀 `conversion: `；
  空项过滤掉（否则 skipped 里会出现空壳行）。
- **没有新 UI、没有新 section**：任务书要求走既有路径。

### 2.4 A-16：恢复的地板与版本闸门

新增纯函数 `ScheduledTasksRestorePlan.decide(declaredVersion, supportedVersion, rawEntries, parsedTasks, liveTasks)`：
- 版本存在且 ≠ 本机支持的版本（**更高更低都算不认识**）⇒ 拒绝 + 说明；
- `parsedTasks == 0 && liveTasks > 0` ⇒ 拒绝 + 说明（「包内无可用任务，本机 N 条保留」）；
- 其余 ⇒ 应用；若 `rawEntries > parsedTasks` 附一行「N 条无法解析已跳过」。

`ScheduledTasksStore.importDocument` 改为在**同一把锁内**读本机文档 → 决策 → （仅当应用）写入，
返回 `ScheduledTasksRestoreOutcome(applied, notice)`；`ConfigBackup.import` 只在 `applied` 时置
`scheduledTasksImported = true`，`notice` 进 `skipped`。
**runner 释放改成早返回后的顶层语句**（拒绝时不释放：拒绝的恢复什么都没移除，释放会把一个合法运行中的
任务的 single-flight 状态丢掉）——首版写在 `if (applied) { … }` 里，被 `scheduled_store_release_guard`
判红（要求释放调用在函数顶层层级），按「判据锚的是锁序不是拼写」重排成早返回，**门未改一行**。

### 2.5 B-12：轮转键搬到文件名

- `ConfigBackup.snapshotFileName` / `WebDavSync.backup` / `WebDavSync.backupAuto` / `AutoBackupManager.runLocked`
  统一用 `BackupRotation.stamp()`。
- `listSnapshots` / `AutoBackupManager.listLocal` / `WebDavSync.listAutoBackupEntries` / 手动远端列表
  全部 `newestFirst`（远端键 = 名字时间戳，退回到「真实的」`getlastmodified`；`Instant.EPOCH` 视为未知）。
- `pruneSnapshots` / `rotateLocalAutoBackups` / `pruneAutoBackups` 全部 `rotationVictims`：
  **刚写的那份受保护**（`protect` / `protectName`），**未知年龄不删**。
- 显示时间改按同一个键（`snapshotDisplayMillis` / `localStampMillis`）：名字排的序、mtime 显示的时间，
  一旦两者矛盾，用户看到的就是「最新那份显示成最旧」——而这行正是一键回滚的第一行。

### 2.6 取舍（写下来的理由）

- **不做全文启发式**：不猜「没有时间戳的文件」的新旧，直接归类为「未知 ⇒ 不删」。宁可多留一个文件，不可删错一个。
- **不改 `AutoBackupLocalStore.isBackupName`**：匹配规则（写者）没变，只是读者开始用名字里的时间戳；
  两者的一致性由测试钉住（`BackupRotationTest` 的 writer/reader 对，`AutoBackupFetchHardeningTest` 的选择）。
- **`pruneAutoBackups` 的 `protectName` 放在参数末尾**：既有调用点 `pruneAutoBackups(cfg, keep = N, client)`
  按位置绑定 `client`，新参数插在中间会打断它（本地编译当场发现，见 §3 的第一次红）。

---

## 3. 装置与变异读数

### 3.1 装置（P1 级：每条一组最小复现，不建新门）

复用 `p1-fix-1027/probe*` 的两套装置，按本支需要在 `p1028-fix/probe/` 重搭：

- `probe/extract.py`：从**真树**的 `ConfigBackup.kt` 逐字提取 18 个成员（快照族 + `chatTruncationNotes` +
  `conversionNotes`），包成 `object ConfigBackup`，并对每块做 sha256 + 「回读源码断言逐字存在」。
  清单见 `probe/build/EXTRACT-MANIFEST.txt`。
- `probe/run.sh`：编译 **真源码**（`BackupRotation.kt` / `OpenMinisBackupCompat.kt` / `ScheduledTask.kt` /
  `ScheduledTasksRestorePlan.kt` + 切片）+ 5 个测试类，跑 JUnit。
- `probe/run-webdav.sh`：`WebDavClient.kt` + `WebDavSync.kt` + `WebDavAutoBackupTest`，对着 MockWebServer 跑。
- `probe/mut.py`：6 个变异臂。

### 3.2 基线读数（真树，verbatim 源码）

```
BackupRotationTest                 OK (12 tests)
ConfigBackupSnapshotRotationTest   OK (5 tests)
ChatTruncationNotesTest            OK (9 tests)
OpenMinisBackupCompatTest          OK (28 tests)
ScheduledTasksRestorePlanTest      OK (8 tests)
WebDavAutoBackupTest               OK (10 tests)     ← 含 3 个新增轮转用例
```

合计 **72 例**（新增 27 例：12+5+2+3+2+3）。

### 3.3 变异负控（6 组，全部把代码改回**修复前的字面形状**）

| 臂 | 复刻的旧形状 | 变红用例 | 其余 |
|---|---|---|---|
| `a5` | `runCatching { JSONObject(t) }.getOrNull()?.let { records.add(it) }` | OpenMinisBackupCompat 2 例（半截行计数 / sessions+rules 计数） | 全绿 |
| `a6` | 不写 `conversionNotes` | OpenMinisBackupCompat 1 例（provider 无模型条目） | 全绿 |
| `a16-empty` | 空集地板短路（`if (false && …)`） | RestorePlan 2 例（空集拒绝 / 全坏条目拒绝） | 全绿 |
| `a16-version` | 版本闸门短路 | RestorePlan 2 例（未来版本 / 更低版本） | 全绿 |
| `b12-local` | `listSnapshots` 按 mtime + `pruneSnapshots` 旧 `drop(KEEP-1)` | 快照 4 例（顺序 / 刚写的不被删 / 未知保留 / 同秒冲突） | display 那 1 例仍绿（本臂没动显示函数） |
| `b12-remote` | 远端按 `lastModified` 排序 + `.drop(keep)` | WebDav 3 例（未知年龄不删 / 保护刚推的 / 无日期时按名字排） | 全绿 |

每条臂都只打红「钉住它」的用例，且不同的臂打红不同的用例 = 判据不是「一起红/一起绿」的形状。

> 两处测试是**因为变异才写对的**：
> 1. 同秒冲突：首版用「名字降序」做 tie-break，实测 `…-120000.json` 排在 `…-120000-2.json` 前面
>    （`-` 45 < `.` 46），即「两个同秒回滚点里更新的那个排第二」。改成「识别冲突副本」规则后才对。
> 2. 远端 `b12-remote` 首轮只红 1 例：夹具里服务器列表顺序恰好与名字一致，旧实现「碰巧也排对」。
>    把夹具的列表顺序改成**故意乱序**（10-27 在前、10-28 在后）后，旧实现会把最新的 10-28 当最旧删掉——
>    用例才有牙。

### 3.4 门与夹具（本地）

- `sh scripts/scan/scan.sh --jobs 3` ⇒ **113 passed, 0 failed**（首次 112/1：`scheduled_store_release_guard`，
  见 §2.4 的说明，重排后绿，门脚本零改动）。
- `python3 scripts/scan/test_scan.py --jobs 4` ⇒ **ALL 349 CASES PASS**。
- `test_scan.py --single test_real_repo` ⇒ `20 0`；`--single test_scheduled_store_release` ⇒ `8 0`。

---

## 4. CI 读数

| 项 | 值 |
|---|---|
| run | `40640155380` / attempt **1** / **success** |
| job | `122656179196`（`build`） |
| 时间 | 2026-10-28T05:48:48Z → 06:05:55Z（约 17 min） |
| head_sha | `d0bcb1b7bba2f48319ce98ce96370429b9ced2ff` = 本地 HEAD **逐字符一致** |
| 步骤 | 27 步，**唯一非 success = 2 个 skipped**（滚动 tag / Publish，均 main 门控）⇒ 分支构建，符合预期 |

日志（`curl` 取 `redirect_url` 后下载，645,501 B zip / 27 文件 / 解压 2,981,686 B，NUL 0）逐条核对：

- `Summary: 113 passed, 0 failed` ×2（廉价门 + 构建内门各一次）
- `ALL 349 CASES PASS` ×2
- `^ e: ` 0、`Function not implemented` 0
- 12 条含 `FAILED` 的行**逐条确认全是 PASSED 用例名子串**（判读陷阱第 N 次复核）
- 新增/扩充的测试在 CI 内**真实执行**（unique PASSED 用例数）：`BackupRotationTest` 12 /
  `ConfigBackupSnapshotRotationTest` 5 / `ScheduledTasksRestorePlanTest` 8 /
  `ChatTruncationNotesTest` 9 / `OpenMinisBackupCompatTest` 28 / `WebDavAutoBackupTest` 10 /
  `AutoBackupFetchHardeningTest` 15；关键用例名（半截行计数 / 无模型条目 notes / 空集拒绝 /
  未来版本拒绝 / 本地轮转按名字 / 未知年龄不删 / 刚推的那份不删）**逐条命中**

---

## 5. 诚实边界（没验证的 / 是推测的）

1. **`ScheduledTasksStore.importDocument` 没有设备级行为测试**：它要 `Context`，本仓单测全 android-free
   （无 Robolectric），所以「本机 2 条任务 + 空包 ⇒ 文件没变」这条**端到端**只在 `decide()` 层被钉住，
   存储层的读改写接线由 CI 编译保证、靠人工读码确认。这是 A-16 唯一的证据缺口。
2. **`AutoBackupManager.rotateLocalAutoBackups` / `listLocal` 同样只能靠 CI 编译**（`Context` + `Log`）；
   它的选择逻辑被 `BackupRotationTest` 与 `AutoBackupFetchHardeningTest`（CI 内跑）分两层覆盖，
   本地只验了纯函数层。
3. **真机行为未验**：时钟真的回拨时快照/备份的实际取舍没有在设备上做过（沙箱无 Android 运行时）。
   JVM 侧用「名字带 2030 年 / 2020 年时间戳 + mtime 相反」模拟，等价于「写者拿到一个偏移的时钟」，
   但不等价于系统级时钟跳变（后者还可能影响 `AtomicFileWrite` 的临时名、`lastModified` 的精度）。
4. **远端 `getlastmodified` 的畸形值**（非空但解析失败）走 `resourceToBackupItem` 的既有逻辑，
   本支只把「缺失 ⇒ EPOCH」纳入未知判定；「畸形 ⇒ 解析异常被吞成 null ⇒ EPOCH」同一条路，未单独造夹具。
5. **`conversionNotes` 的上限未设**（notes 理论上可随 package 规模增长）。当前三条写入点都是「一 provider / 一 skill /
   一 memory 文件一条」，一个包最多几十条；`chatTruncated` 的 `missingShards` 有 `MISSING_SHARD_REPORT_CAP`，
   这里没有对齐。属推测无风险，登记见 §6。

---

## 6. 残留（带触发条件 + 方案）

| # | 残留 | 触发条件 | 方案 |
|---|---|---|---|
| R1 | `conversionNotes` 无条数上限（skipped 列表可能很长） | 出现 notes ≥ 50 条的真实包（或用户反馈「跳过多到看不清」） | 与 `MISSING_SHARD_REPORT_CAP` 同款：保留前 N 条 + 「另有 M 条」尾行 |
| R2 | 未知时间戳的快照/备份**永不回收**（快照目录可能长期多出一两个文件） | 快照目录条目数 > 3（正常路径只有 1） | 在备份页快照列表给「无法判定时间」的行加删除入口（用户可手动判定），或加「陈旧且未知 ⇒ 移到 .trash」 |
| R3 | `AutoBackupManager.selectLocalRotationVictims` 的 `victimEligible` 默认 `{ true }`：未来新增调用方若忘传 eligibility，会退回到「未知也删」 | 出现第三个调用点 | 把默认值改成「必须显式传」，由编译器强制（当前保持默认是为了不动既有边界测试） |
| R4 | 远端手动备份列表只做显示排序（不删），其 `manualBackupStampMillis` 未覆盖所有历史命名（如 `openminis-backup-*` 前缀已覆盖，其他未知前缀落到 mtime） | 发现某代际命名带不出的包 | 在 `BackupRotation` 增加命名清单；解析器已是表驱动（`STAMP_SHAPES`） |
| R5 | `ScheduledTasksStore` 的写失败仍只 log（`writeLocked` 返回 Unit）：恢复写失败时外部仍看到 `applied = true` | 出现「恢复成功但任务列表是空的」的真实反馈 | 让 `writeLocked` 返回 Boolean，`importDocument` 在写失败时返回 `applied = false` + 说明（B-10 同族，属另一支的 `servers.json` 面） |
