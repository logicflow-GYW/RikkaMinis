# FIX-1027-SOUL-OVERWRITE — SOUL.md 覆盖前备份（P0 F-4 / 世界 W3）

**结论一句话**：`SoulStore` 的写路径现在在覆盖前把磁盘上的原字节复制成 `SOUL.md.recovered-<epochMillis>`（内容不同才备份、保留最近 3 份、best-effort 不阻断保存），并新增 `ReadState(Missing/Loaded/Unreadable)` 让「读不出」第一次可以被判别与记日志——五处写点一行未改，共享层修一次即全路径生效。

**分支**：`fix/soul-overwrite-guard`（基线 main `85058847`）· **提交**：`b2a5ad12`（源码 + 测试）
**改动面**：`agent/SoulStore.kt` +218/−18、新增 `SoulStoreOverwriteBackupTest.kt`（285 行 / 9 例），共 503 插入 18 删除，**无其他文件**。

---

## 1. 缺陷与根因（已回树核实）

链路（每行都在当前树上指得出来）：

| 环节 | 事实 |
|---|---|
| 写路径本身 | `SoulStore.save()` 走 `.tmp` + rename 原子写，失败清 tmp（`[audit-0917]`）——**缺陷不在写，在语义** |
| 读侧坍缩 | `load()` 把「文件不存在」和「文件在但读/解析失败」都返回 `null` |
| 回落 | `ConfigBuiltins.loadCurrent()` = `SoulStore.load(context) ?: parse(DEFAULT_CONTENT)`；`SoulSettingsScreen:108` 同款 |
| 五个写点 | `soul.name/style/lang/body` 四个 writer + Settings 保存 = `loadCurrent().copy(一个字段)` → `saveCurrent()` |
| 世界 W3 | 文件被清 / 被换 / 权限坏但**文件仍在** ⇒ 用户改**一个字段** ⇒ 整份用 DEFAULT 正文 + 该字段落盘 ⇒ **原正文永久丢失，零提示** |

即：契约（KDoc 里写着 `same behavior as the Settings page`）与实现一致，错的是契约本身——把「读不出」当成「不存在」。

## 2. 修法（共享层一次，半径最小）

### 2.1 覆盖前备份（`saveTo` / `backupBeforeOverwrite`）

- `saveTo(target, file)`（`SoulStore.kt:500`）= 原 `save()` 的写盘体，**Context-free**，先 `backupBeforeOverwrite` 再 `.tmp`+rename；`.tmp` 原子路径逐行未动。
- `backupBeforeOverwrite`（`:550`）顺序：target 不存在 → `Missing`（不备份）；读出字节与即将写入的 `serialize(file)` **相同** → `Identical`（不备份）；不同 → 复制成同目录 `SOUL.md.recovered-<epochMillis>` → `Written`，随后 `pruneBackups`（`:619`）保留最近 3 份（mtime 排序、同毫秒用名字里的单调 stamp 断连）；读抛异常 / 路径不是普通文件 → `Unreadable`，**`AppLogger.error` 点名绝对路径**并说明「前一份内容已不可从本机恢复」，**继续保存**；复制失败 → `Failed` + `AppLogger.warning`，**继续保存**。任何一条备份失败都不会让用户的 Save 变成失败。
- 备份是**字节复制**（`writeBytes(existing)`），不是 readText→writeText：解析能过但编码往返会变形的文件也必须逐字可恢复。
- 体量上限 1 MiB（`MAX_BACKUP_BYTES`）：正文的文档化上限是 1600 字符，超 1 MiB 的 SOUL.md 只可能是外来或损坏文件，三份复制会正好在「保护用户数据」的场景里把磁盘填满。判定在**读之前**（`target.length()`），所以异常大文件不会被拉进内存；超限 → `TooLarge` + warning，保存照常。
- 同毫秒两次写入会撞名（会静默丢掉前一份备份）⇒ `nextBackupFile`（`:605`）把 stamp 递增到空闲名，名字仍按时间字典序单调，`pruneBackups` 用它做 tie-break。

**为什么判据是「内容不同」而不是「这次是不是回落到了默认值」**：`saveTo` 的调用方拿不到这个信息——回落发生在 `ConfigBuiltins.loadCurrent()` 与 Settings 屏里，要传进来就得改五个 writer 的签名；而内容比较本地可判、可测，并且对未来新增的写点自动生效。代价 = 每次**真实**修改多一份几 KB 文件，3 份封顶。

### 2.2 可读性状态（`ReadState`）

- `ReadState`（`:402`）= `Missing` / `Loaded(file)` / `Unreadable(message)`；`loadFrom(target: File)`（`:442`）Context-free，`load(context)`（`:459`）改成委托它，**返回值契约不变**（`SoulFile?`），所以 5 个调用点零改动；`readState(context)`（`:466`）是新增的只读出口。
- `BackupOutcome`（`:417`，`internal`）把分支变成可断言的返回值，测试不必去 grep 日志。

### 2.3 明确没做的（按任务书）

未改 `ConfigBuiltins` 的 5 个写点、未改 `loadCurrent()` 的回落语义（Missing 回落默认是对的）、未动 `SoulMDParser` 解析/序列化、无 UI、无新门。

## 3. 装置与本地读数

装置形态：把「目标文件」抽成参数后，`SoulStore` 的真实 I/O 只需 `java.io.File`。探针用 **Java 桩**（`android.content.Context` / `ContextWrapper` 的 `getFilesDir()`）+ **捕获式 `AppLogger` 桩**，用 kotlinc 编译**真生产源码**（`SoulStore.kt` 原文件，非切片）与真测试文件，JUnitCore 跑真文件操作。
探针目录：`/var/minis/shared/work/p0-fix-1027/probe-soul/`（仓库树零改动，见 §7 sha 对拍）。

### 3.1 JVM 单测（必测 8 项全覆盖）

`nice java -cp … org.junit.runner.JUnitCore com.rikkaminis.app.agent.SoulStoreOverwriteBackupTest` → **`OK (10 tests)`**

| # | 任务书必测 | 用例 |
|---|---|---|
| 1 | 目标不存在 → 保存成功、0 备份 | `first ever save on a missing target writes the file and makes no backup` |
| 2 | 内容相同 → 0 备份 | `rewriting identical bytes makes no backup` |
| 3 | 内容不同 → 1 备份、逐字节等于原文 | `overwriting different bytes keeps a byte-exact copy of the old file`（原文件刻意含 CRLF + 中文 + 手写字节前缀，`contentEquals` 断言） |
| 4 | 连续 5 次 → 恰好 3 份且是最新 3 份 | `five consecutive content changes leave exactly three newest backups`（注入 clock：断言文件名 `…001000/…002000/…003000` 与内容 `body-2/3/4`，第 1 份被裁） |
| 5 | 目标存在但不可读 → 仍保存成功 | `an unreadable target never blocks the save`（读抛 `IOException("simulated EACCES")`：`backupBeforeOverwrite` 返回 `Unreadable`，`saveTo` 照常落新内容、0 备份）+ `a target that is not a regular file reports unreadable instead of throwing` |
| 6 | `readState` 三态 | `readState distinguishes missing loaded and unreadable`（真 `Context` 路径 `fileLocation`；Unreadable 用同路径换成目录；同时断言 `load()` 仍返回 null） |
| 7 | 原子性不回归 | `a concurrent reader never sees a truncated SOUL md`（64 KiB 正文、20 次真实改动、并发读线程断言每次读到的都是某个**完整**版本、无 `.tmp` 残留、备份仍恰好 3 份） |
| 8 | W3 端到端 | `world W3 - editing one field over an unreadable file keeps the old body recoverable`（旧文件 `我的正文` → 用 DEFAULT + 改 name 保存 → `.recovered-*` 逐字包含 `我的正文` 且等于原文） |
| 9 | 附加：异常大文件不复制 | `a target larger than the backup cap is not copied and the save still lands`（1 MiB+1 字节文件 → `TooLarge`、0 备份、保存照常落盘） |

同时用同一装置跑了既有两个测试类（形态断言 `fun save(` 内含 `_writeVersion.value += 1` 等）：
`SoulStoreTest + SoulStoreWriteVersionTest + SoulStoreOverwriteBackupTest` → **`OK (50 tests)`**（既有 40 例全绿 = 重构未破坏形态门与状态流契约）。

### 3.2 变异负控（4 组，改的是**副本**，仓库树 sha256 前后一致）

| 变异 | 结果 |
|---|---|
| 删掉 `backupBeforeOverwrite(target, text, clock, readExisting)` 调用 | `Tests run: 10, Failures: 4` —— 臂 3/4/7/8（备份内容、轮转、并发臂的轮转断言、W3） |
| 「内容相同不备份」改成恒假（总是备份） | `Failures: 1` —— 臂 2（内容相同不备份）。**臂 1 不受此变异影响**：目标不存在时函数在读之前就返回 `Missing`，没有文件可备份——这是正确行为，不是漏网 |
| `RECOVERED_KEEP = 3` → `99` | `Failures: 2` —— 臂 4 + 臂 7（后者也断言 3 份） |
| `.dropLast(RECOVERED_KEEP)` → `.drop(...)`（裁掉最新的） | `Failures: 1` —— 臂 4。⚠ 复跑时臂 7 偶然变绿（该臂的「恰好 3 份」断言在 `drop` 变异的轮转节奏下可能凑巧成立）⇒ **臂 4 才是这条变异的唯一判据**，臂 7 的轮转断言不可单独依赖 |

`sha256sum SoulStore.kt` 变异前后均为 `6a722da0b7ebb70c…`（前 16 字节），即负控全程未碰生产文件。

### 3.3 静态门 / 夹具（本地）

- `bash scripts/scan/scan.sh --jobs 4` → **`Summary: 113 passed, 0 failed`**（rc=0）
- `python3 scripts/scan/test_scan.py --jobs 4` → **`ALL 349 CASES PASS`**（rc=0）
- 本会话未撞 S28 `Errno 38`，无需逐门降级跑法。

## 4. 副作用核实结论（任务书要求「必须给结论」的那一条）

**结论：`.recovered-` 文件既不进任何备份归档，也不会被当成记忆文件读进来；唯一可见的影响是它会出现在 @ 提及索引里——无害，甚至有用。**

| 面 | 判据（代码 + 机械探针） | 结论 |
|---|---|---|
| 自动/手动备份的**产物归档** | `AutoBackupManager:182-183` / `BackupSettingsScreen:137-138` 给的 artifact roots 只有 `minis-global/shared` 与 `minis-global/mcp-servers`；`<filesDir>/minis-global/memory` **根本不是 root**。即便哪天被当成 root，`ArtifactBackupScope.select` 走扩展名白名单（`DEFAULT_ALLOWED_EXTENSIONS` 无 `recovered-*`），`File("SOUL.md.recovered-1").extension == "recovered-1"` ⇒ 计入 `nonTextSkipped`，不入档 | **不会进备份**，体积影响 = 0 |
| 备份的 **memoryFiles** 段 | `ConfigBackup` 用 `memoryRepo.listAllFiles()`；该函数过滤 `it.extension == "md" && name != GLOBAL/ROLLUP/SOUL` | **不会进** |
| 记忆注入 / 搜索 / 列表 UI | `MemoryRepository.getMemory`（`:263`）与 `listAllFiles`（`:571`）用同一谓词；`memory_write` 只写日期名文件 | **不会被当记忆读/列/搜** |
| 蒸馏候选（`memory_rollup`） | `dailyLogSizes` → `MemoryRollupEngine.isRollupCandidate` 要求 `yyyy-MM-dd` 词干；`SOUL.md.recovered-…` 的 `removeSuffix(".md")` 不匹配日期 | **不会被蒸馏** |
| @ 提及索引 | `FileMentionIndex:153` 把 `minis-global/memory` 当 `Scope.MEMORY` 扫，`scanDir` 只跳隐藏名与 `SKIP_DIR_NAMES` ⇒ `.recovered-*` 会成为一个候选条目 | **会被列出**：纯下拉噪音上限 3 条，且让用户/agent 能 @ 到恢复副本，**按 P3 保留不改** |

机械证据：`probe-soul/SideEffectProbe.kt`（跑**生产** `ArtifactBackupScope.select` + 复刻同一谓词）→ `OK (2 tests)`
- 在临时目录里放 `GLOBAL.md` / `2026-10-27.md` / `SOUL.md` / `SOUL.md.recovered-1700000000000`，`select()` 结果恰为 `["2026-10-27.md","GLOBAL.md","SOUL.md"]`，`nonTextSkipped >= 1`；
- `recovered.extension == "recovered-1700000000000"`、`listed(recovered) == false`、日期词干不匹配。

**附带发现（非本次引入，登记为观察）**：`SOUL.md` 本身也不在 `memoryFiles` 里——它靠 `soul.*` 配置面（`BACKED_UP_SCOPES` 含 `soul`）随备份走，且导入时经 `field.write(value)`（`ConfigBackup:1513`）落到注册表 writer 上 ⇒ **导入恢复也会经过 `SoulStore.save`，因此同样会先备份被覆盖的旧文件**（这是本次修法的额外收益）。

## 5. 残留（三件套：触发条件 / 方案 / 为什么本轮不做）

**R1（P2）「读不出」世界仍无备份——只能记日志。** 备份的前提是能读到字节；文件在但 `readText` 抛异常时本轮按任务书 `跳过备份但仍继续保存`，只留一条 ERROR。
- 触发条件：真机上出现「SOUL.md 存在但读不出」的日志（`SoulStore` ERROR 行）而被覆盖，用户要求找回。
- 方案：该分支改成 **rename 存放**（`target.renameTo(recovered)` 不需要读权限，只需目录写权限），再把新内容写到原路径——字节一字不丢。
- 为什么不本轮做：任务书明确写「读不到就跳过备份」；rename-aside 会改变「保存后 target 一定是新文件」这一语义（rename 失败时旧文件去哪、目录目标的处理都要重新定义），属于扩大半径。**登记，不修**。

**R2（P2）会话记忆面板的 SOUL.md 编辑不吃备份。** `SessionMemorySheet:213` 走 `memoryRepository.saveFile(name, full)`（`File(memoryDir,name).writeText`），绕过 `SoulStore.save`，因此该路径没有覆盖备份（它也一直是**非原子**写）。
- 触发条件：用户在聊天里的记忆面板直接编辑 SOUL.md 并保存，事后需要旧版本。
- 方案：`saveFile` 对 `name == SOUL_FILE` 转发到 `SoulStore.save`（顺带拿到原子写），或在 `saveFile` 内对 SOUL 调一次 `backupBeforeOverwrite`。
- 为什么不本轮做：`saveFile` 是所有记忆文件的通用写点（日报/GLOBAL 都走它），在通用路径里加 SOUL 专属备份是另一层判定；任务书把范围限定在 `SoulStore` 共享层。

**R3（P3）恢复副本的可发现性依赖文件浏览器 / 沙箱路径。** `.recovered-*` 落在 `<filesDir>/minis-global/memory/`（沙箱内 `/var/minis/memory/SOUL.md.recovered-*`），本轮不提供 UI 入口。
- 触发条件：真机出现一次「用户需要恢复但找不到文件」的反馈。
- 方案：Settings → Soul 加一行「最近备份：3 份（点击查看/恢复）」，复用 `listFiles + pruneBackups` 的同一谓词。
- 为什么不本轮做：任务书明确「不加 UI」；且恢复动作需要产品决定（替换 vs 另存）。

## 6. 未验证边界（诚实声明）

- **未跑真机**：本轮全部证据来自 JVM（真文件系统）+ 本地静态门；沙箱无 Android 运行时，`AppLogger` 是捕获桩，真实 `android.util.Log` 落盘/快照路径未实证。
- **未跑 Gradle 单测/编译**：沙箱无 Android SDK；`SoulStore.kt` 是用 kotlinc 编译**真源码**验证的（桩只替 `Context`/`AppLogger`），真实 K2 + Android 插件的编译由分支 CI 承担。
- **CI 状态**：见 §8。设备出口中断（sandbox-limits S35：代理上游 502 / 直连 000），推送与 dispatch 均未成功，**停止节点未达，绝不假回填**。
- 并发窗口只测了同进程 20 次写 + 一个读线程；跨进程（Settings UI vs `minis-config` worker）交错未测。

## 7. 仓库树完整性

- 探针与变异全部在 `/var/minis/shared/work/p0-fix-1027/probe-soul/`（`stubs/`、`jstub/`、`mutants/<name>/`、`SideEffectProbe.kt`），**仓库树只含两个被提交文件**。
- 变异前后 `sha256sum src/.../SoulStore.kt` = `6a722da0b7ebb70c…`（前 16 字节，两次一致）。

## 8. CI

见下方「CI 回填」段（网络恢复后追加）。当前状态：`git push` 失败 `TLS connect error: error:0A000126:SSL routines::unexpected eof while reading`；三分诊判据：经代理明文 HTTP `502`（15:58Z / 16:01Z 两次）、API `000`、直连 `000` ⇒ **代理活着、其上游断**（S35），与仓内配置无关（`git config --local` 的 proxy 与 `$HTTPS_PROXY` 逐字节相同）。
