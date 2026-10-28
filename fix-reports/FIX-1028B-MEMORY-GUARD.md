# FIX-1028B-MEMORY-GUARD — memoryFiles 导入不再截断本地日报

分支 `fix/1028b-memory-files-guard`，基线 main `47c4bf4b`（`witness-1027/repo`）。
文件面：`backup/ConfigBackup.kt`（改 Stage 6）+ 新增 `backup/MemoryRestoreGuard.kt` +
新增 `backup/MemoryRestoreGuardTest.kt`。**零触碰**：`OpenMinisBackupCompat.kt`、
`SoulStore.kt`、`MemoryRepository.kt`、`SessionMemorySheet.kt`、任何既有测试、`scripts/scan/**`。

---

## 1. 先证（回真树读到的原文，不是采信登记）

backlog 把这条登记成 P2、危害写成「导入 14:50→14:55 落盘期间写入的条目被抹掉」——
**竞态是窄形式，不是唯一路径**。回 `47c4bf4b` 的树核到三处原文：

1. **导出侧无条件**，`ConfigBackup.kt:926-941`：

   ```kotlin
   val memoryFiles = JSONArray()
   if (memoryRepo != null) {
       val entries = memoryBackupEntries(
           listed = runCatching { memoryRepo.listAllFiles() }
               .getOrDefault(emptyList()).map { it.name },
       ) { name -> runCatching { memoryRepo.readFile(name) }.getOrNull() }
       for ((name, content) in entries) {
           memoryFiles.put(JSONObject().apply { put("name", name); put("content", content) })
       }
   }
   ```

   没有任何开关；`memoryBackupEntries`（`:3046`）把 `GLOBAL.md` + 每份日报 + `MEMORY-ROLLUP.md` 全带上。

2. **导入侧无条件整份覆盖**，`ConfigBackup.kt:1756-1784`（Stage 6）：对每份文件
   `memoryRepo.saveFile(name, content)`（`:1774`），只有「名字非法」与「写失败」两条出口。
   `saveFile`（`MemoryRepository.kt:610`）就是 `File(memoryDir, name).writeText(content)` —— 截断后重写。

3. **被判依据早就在 payload 里但没人读**：`buildPayloadObject`（`:483`）写 `createdAt`，
   全 `ConfigBackup.kt` 里 `createdAt` 的读者只有聊天历史（`:3121` / `:3162`），Stage 6 一次都没读。

**因此**：恢复任何一份比本地记忆旧的备份，就把 `GLOBAL.md` / 那份日报 / rollup 整份回退到导出时刻；
日报在备份里存在时被截断，备份比日报更早时该日报干脆不参与（不属于「保住」，而是「备份没这份」）。
用户界面上是一次「恢复成功」。唯一兜底是恢复前快照，而界面不提。

**判级修正**：登记时的 P2 判据是「竞态窗口很窄」。竞态不是必需条件 ⇒ 触发路径 = **常用操作**
（设置 → 备份 → 恢复任意旧备份），后果 = **用户数据丢失**、且界面报成功。按修复门判据这是 P0/P1 形态
（数据丢失 + 主路径必经），只是修法成本低，本批按 P1 施工。

---

## 2. 修法逐条

新增 `backup/MemoryRestoreGuard.kt`（纯 `java.io` + `org.json`，无 Android 依赖）：

- `MemoryRestoreDecision`：`Restore` / `KeepLocal(reason)`。
- `decideMemoryRestore(name, localExists, localLastModified, backupCreatedAt)`：
  | 本地 | backup `createdAt` | 判定 |
  |---|---|---|
  | 不存在 | 任意 | Restore（首次恢复，备份是唯一所有者） |
  | 存在，mtime > createdAt | > 0 | **KeepLocal**（本地更新，保留） |
  | 存在，mtime ≤ createdAt | > 0 | Restore |
  | 存在，mtime 读不出（0） | 任意 | **KeepLocal**（fail-closed） |
  | 存在，`createdAt` 缺失/≤0 | ≤ 0 | **KeepLocal**（fail-closed） |

  等号判 Restore：与导出同毫秒的写入就是导出自己那次读，不是「以后的编辑」。
- `restoreMemoryFiles(section, backupCreatedAt, localFile, saveFile, skipped)`：Stage 6 原样的循环体
  （名字校验、`missing name` / `unsafe name` / 写失败三条既有文案逐字保留），多一道 `decideMemoryRestore`。
  两个文件系统边缘以 lambda 注入，`MemoryRepository` 不参与 —— 它 `import android.util.Log`，JVM 编不过。

`ConfigBackup.kt` Stage 6 改成 11 行委托：

```kotlin
val memArr = root.optJSONArray("memoryFiles")
if (memArr != null && memoryRepo != null) {
    val memoryDir = memoryRepo.memoryDirectory()
    memoryFilesImported = restoreMemoryFiles(
        section = memArr,
        backupCreatedAt = root.optLong("createdAt", 0L),
        localFile = { name -> File(memoryDir, name) },
        saveFile = { name, content -> memoryRepo.saveFile(name, content) },
        skipped = skipped,
    )
} else if (memArr != null && memArr.length() > 0 && memoryRepo == null) { … }
```

三条设计上的取舍（各写进代码注释）：

1. **本地路径来自 `MemoryRepository.memoryDirectory()`**，不在 ConfigBackup 里另拼一套根
   （`File(filesDir, "minis-global/memory")`）。同一个根，守卫就不可能出现「检查 A 目录、写 B 目录」
   而静默放行。
2. **`saveFile` 不碰**：支 2 正把它接上 SOUL 转发，改它 = 撞车。守卫只决定「写不写」。
3. **计数与可见性**：`memoryFilesImported` 只计真正写入的（保留的不计，否则界面「N 个记忆文件已恢复」
   会报没发生的事）；被保留的逐条进 `ImportResult.skipped`（既有通道，`BackupSettingsScreen` 已有
   消费方 + `import()` 已逐条 `Log.w` 落日志）。**没有新增 `ImportResult` 字段**——加字段就要同时改
   `BackupSettingsScreen`，那是支 2 的文件面。

---

## 3. 装置（P1 级：最小复现 + 变异，不建新门）

`shared/work/batch-1028b/probe-b1/`：

- `run.sh`：**编译真源码**（`MemoryRestoreGuard.kt` 逐字）+ **真测试文件**，跑 JUnit。
  `SRCROOT` 可指向变异副本，`TESTROOT` 永远是工作树 ⇒ 变异体被真测试判。
- `mut.py`：5 条变异臂，每条把守卫改回缺陷形或改成一个可信的错形，跑真测试。
- `wiring.py`：守卫文件能编译，但 `ConfigBackup.kt` 编不了（3300 行 + `android.util.Base64` + 仓库依赖）
  ⇒ 接线只能文本钉。五条断言 + **自带负控**：同一套断言跑 `47c4bf4b` 版本必须**全 FAIL**，
  否则这个检查本身没牙。

### 3.1 用例读数（`MemoryRestoreGuardTest`，14 条，全绿）

| 用例 | 钉什么 |
|---|---|
| `absentLocalCopyIsRestored` | 首次恢复照写 |
| `olderLocalCopyIsOverwritten` | 本地更旧 ⇒ 覆盖（`backupStamp - 1`） |
| `sameMillisecondCountsAsTheExportsOwnRead` | 等号 ⇒ 覆盖（不是保留） |
| `newerLocalCopyIsKeptWithAVisibleReason` | 本地更新 ⇒ 保留 + 逐字 skipped 文案 |
| `unreadableLocalMtimeFailsClosed` | mtime=0 ⇒ 保留 |
| `payloadWithoutTimestampFailsClosed` | `createdAt=0` ⇒ 保留 |
| `newerLogSurvivesAnOlderBackupWhileUntouchedFilesAreRestored` | 端到端三分支同时成立：3 份 payload → 写 2 份、保留 1 份、`skipped` 恰好 1 行 |
| `keptFilesAreNotCountedAsImported` | 计数口径（written=0 而不是 1） |
| `unstatableLocalCopyIsKeptNotBlindlyOverwritten` | `File` 子类「存在但 mtime 恒 0」⇒ 不写 |
| `writeFailureIsReportedNotThrown` | 写失败仍是一行 skipped，不是 fatal |
| `payloadWithoutCreatedAtKeepsEveryExistingFile` | 老/手工 payload 全文保护 |
| `wholeFileIsRestoredWhenLocalCopyIsOlderEvenIfItIsLonger` | 判据是时间不是长度 |
| `unsafeAndNamelessEntriesAreStillRejected` | 三条既有文案逐字不变（`#1 missing name` / `../escape.md` / `sub/dir.md`） |
| `blankNameIsTrimmedBeforeValidation` | `"  GLOBAL.md  "` 仍能恢复 |

断言里的时间戳（`1_700_000_000_000L` ± 5 000 / 60 000）、文件正文、skipped 文案**全是字面量**，
没有一条从被测常量派生。

### 3.2 变异负控（5/5 只红钉住它的用例，其余保持绿）

| 臂 | 改动（= 缺陷形） | 结果 | 红的用例 |
|---|---|---|---|
| M1 | 删掉 mtime 比较 ⇒ 总是 Restore（**原始缺陷**） | Tests run 14, Failures 3 | `newerLogSurvives…` / `keptFilesAreNotCounted…` / `newerLocalCopyIsKept…` |
| M2 | mtime 读不出 ⇒ 当「很旧」（fail-open） | Failures 2 | `unstatableLocalCopy…` / `unreadableLocalMtimeFailsClosed` |
| M3 | 保留但静默 `continue`（不可见） | Failures 4 | `newerLogSurvives…` / `unstatable…` / `payloadWithoutCreatedAt…` / `keptFilesAreNotCounted…` |
| M4 | `createdAt` 缺失 ⇒ fail-open | Failures 2 | `payloadWithoutCreatedAtKeepsEveryExistingFile` / `payloadWithoutTimestampFailsClosed` |
| M5 | `<=` 改 `<`（等号算更新） | Failure 1 | `sameMillisecondCountsAsTheExportsOwnRead` |

`ALL ARMS MATCH`。每臂都有「本例应该绿而确实绿」的对照组（M1 里 6 个 decide 层用例全绿，
说明装置不是永远红）。

### 3.3 接线读数（`wiring.py`）

| 断言 | 修后树 | 基线 `47c4bf4b` |
|---|---|---|
| A1 Stage 6 委托 `restoreMemoryFiles(` | PASS | FAIL |
| A2 payload `createdAt` 真传给守卫 | PASS | FAIL |
| A3 `memoryRepo.saveFile(` 只出现 1 次且在注入 lambda 内 | PASS | FAIL |
| A4 本地路径派生自 `memoryDirectory()` | PASS | FAIL |
| A5 修复前那句裸覆盖不存在 | PASS | FAIL |

`WIRING OK`（负控 5/5 全 FAIL ⇒ 检查有牙）。

---

## 4. 本地预检

| 命令 | 读数 |
|---|---|
| `sh scripts/scan/scan.sh --jobs 3` | **113 passed, 0 failed** |
| `python3 scripts/scan/test_scan.py --single test_real_repo` | **20 cases, 0 failed**（`@@TEST_RESULT@@ test_real_repo 20 0`） |
| `sh probe-b1/run.sh` | COMPILE OK / `OK (14 tests)` |
| `python3 probe-b1/mut.py` | 5 臂全红（预期集合），`ALL ARMS MATCH` |
| `python3 probe-b1/wiring.py` | `WIRING OK`（负控全 FAIL） |

`scripts/scan/**` 一行未动，门数仍 113、夹具基数不变。

---

## 5. 云端 CI

见文末「CI 附录」（run 号、job、head_sha、逐条日志判读）。判读口径与前几批一致：
`Summary: 113 passed, 0 failed` ×1、`ALL <n> CASES PASS` ×1、`^ e: ` 0、含 `FAILED` 的行逐条核是否
只是 PASSED 用例名子串、`MemoryRestoreGuardTest` 是否真的在 CI 里跑（unique PASSED 计数）。

---

## 6. 诚实边界（未验 / 推测，逐条标）

1. **真机未验**。沙箱无 Android 运行时；本批证据三段：①门 + 夹具绿 ②守卫层 JVM 真码真测 14 例 + 5 变异
   ③接线文本钉（含负控）。缺的一段是 `MemoryRepository.saveFile` 到磁盘的那一跳——它没改，
   `writeText` 行为既有。
2. **`createdAt` 的取时点**：`buildPayloadObject`（`:483`）在 `buildSections` 之后取
   `System.currentTimeMillis()`，所以 `createdAt ≥ 任何被读文件的读取时刻`。我是按这个顺序推的
   （`buildSections` 先跑、拼 document 时再盖章），**没有跑一个导出实例去实测**；若未来有人把
   `createdAt` 改成「导出开始时取一次」，等号臂的语义就要重看。
3. **跨设备时钟偏差未处理**：`createdAt` 来自**源设备**时钟。源设备时钟快 ⇒ `createdAt` 大于本地
   真实写入时间 ⇒ 本地「更新」判定失效 ⇒ 覆盖本地。已登记残留 R2。单机恢复不受影响。
4. **`memoryFiles` 里若有 `SOUL.md` 名**（手工/篡改 payload，正常导出不会带：`listAllFiles` 排除它、
   `memoryBackupEntries` 只额外加 rollup），本守卫按同样规则判定后会走 `saveFile`。支 2 正在把
   `saveFile` 接 SOUL 转发，届时这条路径会经 `SoulStore`。已登记 R4（跨支复查项）。
5. **`skipped` 的 8 行截断**：`BackupSettingsScreen` 只显示前 8 行。多份日报同时被保留时，用户看到的是
   「N 项未应用」+ 前 8 条，且 memory 行排在 field/provider/skill 之后可能被挤出窗口。已登记 R1 的同族 R3。
6. **`MemoryRestoreGuardTest` 在 CI 上是否会因 mtime 精度失败**：断言依赖 `setLastModified` 生效，
   失败时 `assertTrue` 会当场报「fixture: could not set mtime」，不会静默变绿；但我只在 JVM（ext4/f2fs）
   上跑过，**未在 CI 的 runner 文件系统上实测**——这正是本轮 CI 要看的读数之一。

---

## 7. 新登记残留（三件套齐，全部登进 `/var/minis/shared/backlog.md`）

- **R1 「强制回滚记忆」通道缺失**（P2）：本修只做 fail-closed，没有任何路径能把本地记忆回退到备份时刻。
  触发条件：用户报告「恢复旧备份后 GLOBAL.md / 日报没变化」且他要的正是回退（或记忆被 agent 写坏需要回滚）。
  触发后方案：`ImportResult` 加 `memoryFilesKept` + 备份界面加「覆盖本地记忆」显式开关（默认关），
  开关打开时跳过守卫并照常走恢复前快照。搁置理由：反方向同样会丢数据，要不要给这个开关是 UI 决策；
  本批文件面不含 `BackupSettingsScreen`（支 2 在旁边施工）。
- **R2 跨设备时钟偏差**（P2）：`createdAt` 是源设备时钟。触发条件：出现「恢复后本地较新记忆被旧备份覆盖」
  的用户报告，或下批改导出 payload schema 时。触发后方案：导出侧每份记 `modifiedAt` + 内容 sha256，
  导入侧先用内容等价判断（相同 ⇒ 免写），再在时间不可信时降级为 `.conflict` 副本。搁置理由：需要改
  payload 格式（导出侧二阶门：新键要同时被 OpenMinisCompat / 兼容层消费），当前只有单机恢复场景。
- **R3 保留原因被 8 行窗口挤掉**（P2）：触发条件：用户反馈「记忆没恢复但没说为什么」。
  触发后方案：给 `ImportResult` 加 `memoryFilesKept` 计数 + 专用 UI 行（一条聚合，不占 8 行窗口）。
  搁置理由：要改 `BackupSettingsScreen`，本批文件面外。
- **R4 跨支复查：`SOUL.md` 名字走 memoryFiles 时的归属**（P2）：触发条件：支 2（`saveFile` 接 SOUL 转发）
  合入后。触发后方案：Stage 6 显式拒绝 `SOUL.md`（SOUL 有独立通道与独立备份语义）或显式允许并写进
  `OpenMinisBackupCompat` 的字段对照。搁置理由：本批文件面不含 `SoulStore.kt` / `OpenMinisBackupCompat.kt`，
  开工即撞车。

## 8. 收口状态

分支 `fix/1028b-memory-files-guard`，**停分支等放行**。未合并 main、未建并集、未清分支。

---

## CI 附录

- run **`40657670517`**（分支 dispatch，`2026-10-28T08:33:40Z` 创建）/ job **`122712570266`** / attempt **1** / **success**
  （`08:33:44Z → 08:54:26Z`，20m42s）。
- **`head_sha = 84fed05c6e8cb1a001e59a396195817aa2386394`** —— 与本地代码提交 `84fed05c` **逐字符一致**
  （报告提交 `69f526a1` 在 CI 之后，只动 `fix-reports/`）。
- 27 步：唯一非 success = 步 23「Move the rolling tag」+ 步 24「Publish to Releases」，均 **skipped**
  （main 门控，分支构建本就不该发布）。其余全 success，含**步 3 pre-build scan gate** 与
  **步 15 Run unit tests (full suite)**。
- 日志 `1,502,489` B / NUL `0`，逐条核：
  | 判据 | 读数 |
  |---|---|
  | `Summary: 113 passed, 0 failed` | **×1** |
  | `ALL 349 CASES PASS` | **×1**（349 = 本轮基线夹具数） |
  | 门头 `[N/113]` | 113 条，编号 1..113 **distinct 全齐** |
  | `^\s*e: `（Kotlin 编译错误） | **0** |
  | 含 `FAILED` 的行 | 6 条，逐条展开**全是 PASSED 用例名子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED…` 等），无真失败 |
  | `MemoryRestoreGuardTest > … PASSED` | **14**（新增用例在 CI 上真跑，`setLastModified` 夹具断言全过 ⇒ §6.6 的 runner 文件系统疑虑解除） |
  | `SoulStoreOverwriteBackupTest > a concurrent reader never sees a truncated SOUL md` | **PASSED**（已知 1% 假红未触发，attempt 1 一次过） |

### 报告提交后的收口核验

`git diff --name-only 84fed05c..HEAD -- . ':(exclude)fix-reports'` = **空**（源码树与绿 sha 完全一致）。

