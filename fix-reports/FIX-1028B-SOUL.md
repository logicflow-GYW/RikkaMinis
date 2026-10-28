# FIX-1028B-2：SOUL 域三件 —— 读不出也能备份 / 面板写走原子 + 备份 / 测试假红根治

- 分支：`fix/1028b-soul-atomic-and-flake`
- 基线：`47c4bf4b`（main）
- 改动面：3 生产文件（`agent/SoulStore.kt` +148/−20、`data/repository/MemoryRepository.kt` +21/−0、
  `test/.../SoulStoreOverwriteBackupTest.kt` +87/−32）+ 1 新测试文件
  `test/.../data/repository/MemorySaveFileSoulForwardingTest.kt`（5 例）
- `scripts/scan/**` **一行未动**（不建新门）；`ui/chat/SessionMemorySheet.kt` **未改**（理由见 §2.2）
- 门 113 / 夹具 349 基线不变

---

## 1. 先证（三条各自回真树核实）

### ① L9089 —— 「读不出」世界仍无备份（`SoulStore.kt:backupBeforeOverwrite`）

原文（改动前）：

```kotlin
val existing = try { readExisting(target) } catch (t: Throwable) {
    AppLogger.error(TAG, "SOUL.md at … exists but cannot be read (${t.message}) — overwriting it without a backup. …")
    return BackupOutcome.Unreadable        // ← 备份被跳过，保存继续
}
```

核实：`saveTo` 的注释与 `README` 级 KDoc 都写「nothing here may turn a user's Save into a failure」，即
读失败被**刻意**降级为「只留一条 ERROR，照常覆盖」。于是 W3 的两半只有一半被保护：
文件被清空/被换掉能恢复（拷贝路径），**文件在但读不了**（chmod / EIO / 外部编辑器换 inode）则前一份内容彻底消失。
第五条测试在改动前把这条行为钉成 `assertEquals(BackupOutcome.Unreadable, outcome)` + `assertEquals(0, backups.size)` ——
**测试是对的，缺的是实现**。

判据（本批可复用）：`copy` 需要读权限，`rename` 只需要**目录**写权限。旧实现只有 `copy`，
所以在「权限坏」这一格上它没有可用的工具。

### ② L9096 —— 面板保存绕过 SoulStore（`MemoryRepository.kt:610`）

原文：`fun saveFile(name: String, content: String) { File(memoryDir, name).writeText(content) }`

核实（`rg -n "saveFile\("` 全仓，5 个生产调用点）：

| 调用点 | 写的是 SOUL.md 吗 | 拿到备份/原子写吗（改动前） |
|---|---|---|
| `ui/chat/SessionMemorySheet.kt:215` | 是（AutoFile 编辑保存） | **否** |
| `ui/settings/MemoryManagementScreen.kt:498` | 是（MemoryFileEditScreen） | **否** |
| `backup/ConfigBackup.kt:1774`（Stage 6 记忆恢复） | 可能是 | **否** |
| 其余（日报 / GLOBAL / ROLLUP） | 否 | 不需要 |
| `SoulStore.save`（Settings → Soul、minis-config、`ConfigBuiltins` 5 处） | 是 | 是 |

即**同一个文件有两种耐久等级**：官方写入器原子 + 备份，两个编辑器入口裸写。且两个入口都在
`catch (_: Exception) { /* toast omitted */ }` 里 —— 写失败连提示都没有。

### ③ L9195 —— CI 假红 1%（`SoulStoreOverwriteBackupTest` 并发断言）

原文：`val allowed = LinkedHashSet<String>()`，写线程 `add`、读线程 `text !in allowed`（+ 诊断行 `allowed.first()`）。

核实：backlog 已记录一次真实失败（run `40630682850`）与 JDK 级复现（500 次试验 5 次对**已存在键**
`contains` 返回 false）。本次探针把这条复现做成了可重复读数（§3.3）：**变异体 60 轮 6 红 / 修复后 200 轮 0 红**，
且失败信息与 CI 那条**逐字相同**：`reader saw 65599 bytes that are not any complete version (old=65598)`
（65599 B 恰是 version-10..20 的完整长度，`old` 是 version-0 的长度 —— 完整内容被容器报成不存在）。

---

## 2. 修法

### 2.1 ① rename-aside（`SoulStore.kt`）

读失败不再返回「Unreadable + 覆盖」：

```kotlin
} catch (t: Throwable) {
    return renameAside(target, clock(), "unreadable (${t.message})")
}
```

`renameAside(target, stamp, reason)`：

- `target.renameTo(nextBackupFile(target, stamp))` 成功 → `BackupOutcome.Renamed` + `pruneBackups`，
  日志降级为 **WARNING**（内容仍在盘上，只是挪了名字，可能还需要 chmod 才能打开）；
- 改名失败 → `BackupOutcome.RenameFailed` + **ERROR**，保存继续（不阻塞用户的 Save，这是本文件既有契约）；
- 目标不是普通文件（目录 / FIFO / 断链）→ 早返回 `BackupOutcome.NotARegularFile` + ERROR，**不**改名：
  那不是人格内容，而且非文件占住 `SOUL.md.recovered-*` 命名空间后 `pruneBackups`（`isFile` 过滤）永远收不走它。
  「我们选择不动」与「我们试过但动不了」是两个不同的出口，因此是两个不同的枚举值。

两个出口都不吞异常、都不静默：一个有 WARNING 说「内容在哪」，一个有 ERROR 说「真的丢了」。
`Unreadable` 枚举值随之删除（全仓除测试外无引用，已 grep 核实）。

顺带（同一函数的半径内）：`saveTo` 的 `.tmp` + rename 抽成 `writeAtomically(target, text)`，
供 `saveTo`（解析后文本）与新增的 `saveRawTo`（原样文本）共用 —— 两份拷贝就是下一次编辑丢 `.tmp` 清理的地方。

### 2.2 ② `saveFile` 对 SOUL 早返回转发

```kotlin
fun saveFile(name: String, content: String) {
    if (name == SOUL_FILE) {
        com.rikkaminis.app.agent.SoulStore.saveRawTo(File(memoryDir, name), content)
        return
    }
    File(memoryDir, name).writeText(content)   // 其余文件名：逐字未变
}
```

三个决定各有理由：

1. **早返回而不是改公共实现** —— 这个方法同时是日报 / GLOBAL / ROLLUP 的通用写点，`ConfigBackup` Stage 6
   按名字调它恢复记忆文件；签名、其余文件语义必须逐字不变（`scripts/scan/backup_remap_rollup_check.py`
   还以 `memoryRepo.saveFile(name, content)` 这段拼写当形状锚）。
2. **`saveRawTo` 而不是 `saveTo`** —— 调用方交来的是编辑器拼回**全文原文**；走 `saveTo` 会 `parse` → `serialize`，
   悄悄重排 frontmatter、丢掉 `emoji:`、强加尾换行。「同样保护、不同文本语义」→ 保护下沉到 parser 之下。
   测试 `saveFile_writesSoulPersonaByteExact`（既有）+ 新增 3 例共同钉住字节保真。
3. **不改 `SessionMemorySheet.kt`** —— 它已经在保存后调 `SoulStore.refreshCache(context)`（另一处
   `MemoryManagementScreen` 也已改成 IO 线程 + refreshCache），而 `memory_ui_wiring_check` 门正好把
   「saveFile 之后必须能到 refreshCache」当判据。转发层 `saveRawTo` 无 Context（`MemoryRepository` 只有 `memoryDir`），
   缓存/版本号仍由调用方 refresh —— 配对关系不变，两处 UI 一行都不用改。

### 2.3 ③ 容器换成写时复制

```kotlin
val allowed = CopyOnWriteArraySet<String>()   // was LinkedHashSet
```

只改容器，**断言口径一个字没动**（仍是「读者看到的每一份文本都必须是某个完整版本」）——
断言语义是对的，错的是它脚下那个非线程安全容器。`CopyOnWriteArraySet` 每次读拿到稳定快照，
且保留插入顺序，所以失败信息里的 `allowed.first()` 仍然指向 version-0。写入方是单线程 + 21 个元素，
COW 的复制成本可忽略。

---

## 3. 装置与读数

装置目录 `/var/minis/shared/work/batch-1028b/probe-b2/`（可复现）：
`build.sh`（单次 kotlinc 编译**真实** `SoulStore.kt` + **真实** `MemoryRepository.kt` + **真实** CI 测试类）、
`run.sh`、`stress.sh`（单方法重复跑）、`mut.py`（5 条变异臂）、`SaveFileRace.java`（经 `saveFile` 的原子性对照臂）。

Stub 只有五个：`android.content.Context` / `ContextWrapper`（Java 写，因为 SoulStore 用 `context.filesDir`
而测试 `override fun getFilesDir()`，两种拼写只有在 Java 父类下同时成立）、`android.util.Log`、
`AppLogger`、`AgentRuntimeLimitsPrefs`、`MemoryRollupEngine`。**被测代码零复刻**。

### 3.1 两个 CI 测试类（真实源码，JUnit 4 真跑）

| 类 | 例数 | 结果 |
|---|---|---|
| `SoulStoreOverwriteBackupTest` | 12（原 8 + 新 4） | OK |
| `MemorySaveFileSoulForwardingTest`（新） | 5 | OK |
| 合计 | **17** | **OK (17 tests)** |

新增用例（断言值全部写死字面量，不从被测常量推导）：

- `an unreadable target is moved aside verbatim instead of being overwritten blind`
  —— 钉 `Renamed` + 兄弟文件**确切名字** `SOUL.md.recovered-1700000000777` + 逐字原文（含 CRLF）
- `the save still lands after the unreadable file was moved aside` —— 保存照常落地，且恰好 1 份、内容是覆盖前原文
- `a rename that cannot happen is reported instead of swallowed` —— 读失败 + 目录被抽走 ⇒ `RenameFailed`
- `a target that is not a regular file is reported and left alone` —— 目录仍是目录、0 份 recovered
- `saveFile keeps the previous SOUL md before overwriting it` / `… goes through the atomic write path`
  （inode 变化 + 不留 `.tmp`）/ `… identical text makes no backup`
- 对照臂 `saveFile on a daily log keeps the plain in-place write` + `saveFile on GLOBAL md is still a plain overwrite`
  —— 防「改通用路径改坏」，这是本次最真实的回归风险

### 3.2 变异负控（5 条，`mut.py`，每条只红钉住它的用例）

| 臂 | 变异内容（还原成修复前/错的形态） | 读数 |
|---|---|---|
| **M1** | rename-aside 退回「读失败就不备份，只报错」 | `Tests run: 17, Failures: 2` —— 恰为两条 rename 用例 |
| **M2** | 去掉 `saveFile` 里的 SOUL 转发（退回裸 `writeText`） | `Failures: 2` —— 恰好 2 条的备份/原子用例 |
| **M3** | `saveRawTo` 直接 `target.writeText(text)`（去掉 `.tmp`+rename） | `Failures: 2` —— 原子性用例 **+ 既有并发读者用例**（后者本来就钉原子写，说明它真有牙） |
| **M4** | 目录分支改成 `renameAside(...)`（把目录也挪走） | `Failures: 1` —— 恰好「not a regular file … left alone」 |
| **M5** | 测试容器退回 `LinkedHashSet` | 见 §3.3（概率臂，用重复跑判） |

每臂其余 13–16 例保持绿 = 装置不是「永远红」。

### 3.3 ③ 的成功判据：重复跑，不是单次绿

`stress.sh` 用 `OneTest` 只跑 `a concurrent reader never sees a truncated SOUL md`，一轮一个 JVM：

| 容器 | 轮数 | 结果 |
|---|---|---|
| `LinkedHashSet`（M5 变异体） | 60 | **54 pass / 6 fail（≈10%）**，每轮信息都逐字等于 CI 那条：`reader saw 65599 bytes that are not any complete version (old=65598)` |
| `CopyOnWriteArraySet`（修复后） | **200** | **200 pass / 0 fail** |

即：①这条假红**在本机可复现**，且复现出来的文本与 CI 失败文本**同一个**（不是相似的另一种）；
②修复后在 200 轮窗口内 0 次。观测窗口的诚实说明见 §5。

### 3.4 经 `saveFile` 的原子性（probe-only，刻意不进 CI）

`SaveFileRace.java`：读者线程死循环 `Files.readString(SOUL.md)`，写者用 `repo.saveFile("SOUL.md", …)`
连写 20 个 256 KB 版本，允许集合用 COW：

| 目标树 | 读数 |
|---|---|
| 修复后 | `ATOMIC-OK | final=262155B backups=3 tmpExists=false` |
| M3（就地写） | `PARTIAL-SEEN: 110592 bytes, not a complete version` |

这条**只放探针**：它是竞态检测，对正确实现不可能假红但对变异是概率检测 —— 进 CI 就是再造一个 flaky。

### 3.5 本地门与夹具

- `sh scripts/scan/scan.sh --jobs 3` → **113 passed, 0 failed**
- `python3 scripts/scan/test_scan.py --single test_real_repo` → **20 例 0 失败**（`@@TEST_RESULT@@ test_real_repo 20 0`）

> 门被掏空的检查（本批特意核过）：动 `SoulStore` 内部结构前先 `rg -ln "SoulStore|saveFile" scripts/scan/`，
> 命中的 6 道门逐条读判据 —— `memory_ui_wiring_check` 判的是「`saveFile(` 之后能到 `SoulStore.refreshCache(`」，
> 我没删任何 `refreshCache` 调用，判据承重不变；`backup_remap_rollup_check` 以 `memoryRepo.saveFile(name, content)`
> 作 `ConfigBackup` 侧形状锚，签名未变。真树 113/0 复核通过。

---

## 4. CI

- run：**`40660001494`**（workflow `Build RikkaMinis Android APK`，ref `fix/1028b-soul-atomic-and-flake`，dispatch 于 2026-10-28T08:53:06Z）
- job：**`122720364904`** —— **success**，27 步唯一非 success = 2 skipped（滚动 tag + Publish，main 门控，预期）
- head_sha：**`3563091b`**（= 本分支代码提交，逐字符一致）
- 完成时刻 09:14:19Z（约 21 分钟）；日志 1,502,026 B / NUL 0
- 日志判读：
  - `Summary: 113 passed, 0 failed` 恰 1 条；`ALL 349 CASES PASS` 恰 1 条；`^ e: ` = 0
  - `MemorySaveFileSoulForwardingTest >` 5 条全 PASSED（含 `saveFile on SOUL md goes through the atomic write path`）
  - `SoulStoreOverwriteBackupTest >` 12 条全 PASSED（含 `a concurrent reader never sees a truncated SOUL md`）
  - 日志中 6 条含 `FAILED` 的行逐条核过，全是 PASSED 用例名的子串（本仓已知判读陷阱）

---

## 5. 诚实边界

1. **真机未验。** 沙箱与 CI 都是 JVM（Linux）。`renameTo` 的语义在 Android 私有目录（f2fs）上相同，
   但「chmod 000 后 rename 仍成功」这条只在 Linux 上实测过；Android 侧无运行时可用。
2. **「读不出」的触发路径本身很窄**：应用私有目录里的文件被 chmod / EIO / 外部编辑器换 inode，
   现实中多半来自 root、adb 或一次异常断电。它属于「用户报告的回归」之外的第二类，因此本条按 P2→P1 边缘处理，
   修它是「顺手成本≈0 且改的是同一个函数」。
3. **`.recovered-*` 仍无 UI 入口**（backlog 已登记 L9359）：用户要知道文件名才找得到。本次把「读不出」
   的内容也放进了同一个命名空间，但**没有**新增入口 —— 半径之外。
4. **被挪走的文件保留原来的坏权限位**（chmod 000 挪过去还是 000）。日志里写明了「可能还需 chmod」，
   不做自动 chmod（那会在用户不知情时放宽权限）。
5. **`renameAside` 不 fsync 目录**：断电窗口下这次改名可能没落盘 —— 与文件里其余写路径同级
   （`.tmp` 写入同样不 fsync）。不做单点加固，避免「只有一条路径 fsync」的错觉安全。
6. **`saveFile` 的 SOUL 分支不更新 `cachedMetadata` / `writeVersion`**（`saveRawTo` 无 Context）。
   两个已知调用点都紧跟 `refreshCache`（门也钉着），但**新调用点若忘记就是旧病**（见 §6 R4）。
7. **③ 的读数与 CI 的 1% 不同量级**（本机 10% / 60 轮）。方向性证据成立（同一文本、可复现、修复后 0/200），
   但**不能**把 10% 当成线上概率：容器竞争窗口随 JVM 版本、核数、负载而变。CI 侧的观测窗口是「这一次 run」，
   真正确认要等后续若干轮 CI 都不再出现该失败（见 §6 R5）。
8. **M5 是概率臂**：单跑一次可能绿（本机第一次跑就绿），所以它的判据只能是重复跑的失败率，不是单例断言。

---

## 6. 新登记残留（三件套齐全，待并入 backlog）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `.recovered-*` 无用户可见入口（L9359 同族，本次把「读不出」也放进该命名空间后覆盖面更大） | 用户报告「人格被改了 / 想找回上一版」且找不到文件 | Settings → Soul 加「恢复点」列表（列 `SOUL.md.recovered-*`：时间 + 首行），点选 → 复制成 `.bak` 再让用户确认覆盖 | 半径外（UI 新功能）；且备份存在时数据未丢 |
| R2 | `SoulStore.load` 仍对「读不出」返回 `null`，五个写入点仍会拿默认内容覆盖 | 用户报告「SOUL.md 莫名其妙变回默认」 | 让 `load` 的 null 携带 `ReadState`（或新增 `loadOrNull(reason)`），写入点遇到 Unreadable 时**先提示后写**；配合 R1 的恢复入口 | 本批只保证「可恢复」，未改变「用户会看到被替换」（行为面变更需要提示文案 + 7 语言） |
| R3 | `renameAside` 与 `.tmp` 写均不 fsync 目录 | 出现「崩溃/断电后 SOUL.md 内容异常」类真机报告 | 统一在 `writeAtomically` 与 `renameAside` 后 `FileOutputStream.fd.sync()` + 目录 sync（Android API 26+ 可用 `Os.fsync`） | 单点 fsync 会造「只有这条路径安全」的错觉；应整文件一次做 |
| R4 | `saveFile` 的 SOUL 分支不刷新 `SoulStore` 缓存/版本号，靠调用方 `refreshCache` | 新增任何 `saveFile("SOUL.md", …)` 调用点且忘了 refresh | 在 `SoulStore` 提供 `saveRawAndRefresh(context, text)`；`MemoryRepository` 加可空 `contextProvider`，或让 UI 层只走新方法 | 现有 2 个调用点都已 refresh，门 `memory_ui_wiring_check` 也钉着；先观察是否真的出现第三个 |
| R5 | ③ 的修复只有「本机 200 轮 + 一次 CI」证据，CI 侧长期无假红尚未被观测 | 后续 CI 再出现同一条失败（即使容器已换） | 说明还有第二个竞态源（例如 `readText` 与 rename 的交互），把 `stress.sh` 拉长到 1000 轮 + 打开 `-Xcomp` 复现 | 单次 run 不能证明「不再发生」；等观测 |
| R6 | `backupBeforeOverwrite` 的 `TooLarge`（>1 MiB）分支仍不 rename-aside | 有人把 SOUL.md 写成 >1 MiB（外部编辑器/导入）且随后被覆盖 | 该分支也改 rename（rename 的成本与大小无关，`tooLarge` 只是「不拷贝进内存」的理由） | 半径纪律：本批只改「读不出」；且与 R2/R3 同族，建议一并做 |
