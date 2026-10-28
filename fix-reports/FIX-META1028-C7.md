# FIX-META1028-C7 — 会话记忆面板 + 记忆工具 3 条修复

**分支** `fix/meta1028-c7-tool-executors` ｜ **基线** `bfb5b0e6`（报告声明基线 `47c4bf4b`）
**来源** 货架报告 B03 的 F-08 / F-09 / F-10
**改动面** 2 生产 `.kt` + 1 新测试文件；`scripts/scan/**`、`scan.sh`、`test_scan.py` **零触碰**
**CI** run `40680825520` ｜ **停分支，不合 main**

---

## 1. 动手前的一句话：这改变什么用户行为？

| ID | 修前用户看到 | 修后用户看到 |
|---|---|---|
| F-08 | 打开 /memory 面板时**卡顿掉帧**（四个文件在组合期同步读，本机实测 272,690 字节）；AutoFile 保存失败时**界面毫无反应**，编辑框里是用户的字、盘上是旧字，零提示 | 面板首帧先空一格再填入，不卡；保存失败时编辑区下方出现红色原因，同时日志有完整堆栈 |
| F-09 | 失败的 `memory_write` 也有一条「成功写入」的操作记录 → 点 Edit 预填从未落盘的文字，Save/Revoke 报 NotFound；`memory_rollup` 行标签写着 `memory_get`（把自己伪装成只读搜索） | 失败写入的行不带可编辑/可撤销体（两个按钮按既有口径隐藏）；rollup 行标签写 `memory_rollup` |
| F-10 | 同一秒内两次 `browser_use`，前一次工具卡的缩略图与 `minis://browser/screenshot_…` 全变成后一张图 | 两次截图是两个文件，前一张不复存在 |

---

## 2. 先证（每条都回真树点名，不采信报告行号）

```
root  = src/android/app/src/main/java/com/rikkaminis/app/ui/chat

SessionMemorySheet.kt:96-98     val autoItems = remember(memoryRepository, context, autoItemsRefreshTick) {
                                    buildAutoInjectedItems(context, memoryRepository)   ← 组合期 4 次 readText
SessionMemorySheet.kt:228       } catch (_: Exception) { /* fall through; UI toast omitted on failure */ }
SessionMemorySheet.kt:245       errorMessage = null,                                    ← 硬编码
ChatToolExecutors.kt:153        "screenshot_${System.currentTimeMillis() / 1000}.jpg"    ← 秒级
ChatToolExecutors.kt:237-243    writtenContent = content,                               ← 不看 result.success
ChatToolExecutors.kt:287-291    isWrite = false                                         ← rollup
SessionMemorySheet.kt:443       val opLabel = if (record.isWrite) "memory_write" else "memory_get"
```

**规模事实（F-08 的判级依据，本机实测）**：`/var/minis/memory` 93 个文件 / 6,573,677 B，最大日报 274,201 B；四个快照文件合计 **272,690 B**（SOUL 484 + GLOBAL 33,914 + 今日 93,162 + 昨日 136,504）。JVM 上把这四份 `readText` 一遍，20 次中位 **2 ms / 最大 10 ms** —— 真机（冷页缓存 + f2fs + 主线程还要排版）远不止这个数，且**两个日报全天在长**。

**跨层完整性先证**（改 `MemoryToolRecord` 是加字段，缺一处就静默失效）：

| 角色 | 位置 |
|---|---|
| 数据类声明 | `SessionMemorySheet.kt:564` |
| 构造点 | `ChatToolExecutors.kt` 3 处（write / get / rollup） |
| UI 消费点 | `SessionMemorySheet.kt` 行标签（原 `isWrite` 派生）+ `MemoryWriteDetailBody`（走 `writtenContent`，不受影响） |
| 其它构造点 | 全仓 grep 后**无第三处**（ChatViewModel 只做 `copy`/匹配，不构造） |

---

## 3. 修法

### F-08 快照移出组合期 + 保存失败可见

```
var autoFiles by remember { mutableStateOf<AutoInjectedFiles?>(null) }
LaunchedEffect(memoryRepository, autoItemsRefreshTick) {
    autoFiles = withContext(Dispatchers.IO) {
        readAutoInjectedFiles(memoryRepository::readFile, memoryRepository::loadGlobalMd)
    }
}
val autoItems = remember(memoryRepository, context, autoItemsRefreshTick, autoFiles) {
    buildAutoInjectedItems(context, autoFiles)     // 纯函数，只读快照 + 字符串资源
}
```

- **为什么是两阶段而不是把 `remember` 整块搬进 IO**：装配里要 `context.getString(...)`（只有组合期才有 `LocalContext`），且 `LazyColumn` 消费的是 `List<AutoItem>`。把 IO 那一半搬走，组合期剩下的是「对已落地的字节做纯计算」。
- **为什么不是 `remember { ... }` 里直接 `async`**：`autoItemsRefreshTick` 仍必须是建造者的键（门禁 `memory_ui_wiring_check` 的 C 面钉这条），所以 tick 同时驱动 IO effect 与装配键；二者一致，重建语义与原来完全相同（三处成功路径的 `autoItemsRefreshTick++` 一个字没动）。
- **首帧空一格**：`autoFiles == null` → `emptyList()`。用「一帧空列表」换「永不阻塞首帧」，和进度条是同一类取舍里更便宜的那个。
- **保存失败**：`catch (e: Exception)` → `Log.w(MEMORY_SHEET_TAG, "memory file save failed (<name>): <msg>", e)` + `editErrorMessage = e.message ?: e.toString()`；编辑器 `errorMessage = editErrorMessage`。**留在编辑态是刻意的** —— 用户缓冲区此刻是他那段文字的唯一副本，退出编辑等于丢弃。
- 注入上限 `memoryInjectLines()` 也随快照一起在 IO 趟读走（prefs 是 SharedPreferences 读，同样不该在组合期）。

### F-09 记录只说自己真的做过的事

- `memoryWriteRecordContent(success, content) = if (success && content.isNotBlank()) content else null`。**null 不是新语义**：`MemorySheetMode.Write` 的 `canEditOrRevoke = m.record.writtenContent != null` 早就用 null 表示「不可编辑/不可撤销」，只是从来没人给它赋过 null。
- `MemoryToolRecord` 加 `toolName: String`（无默认值 —— **故意的**，逼每个构造点自己报名，否则会静默缺字段）。三个常量：`MEMORY_TOOL_WRITE` / `MEMORY_TOOL_GET` / `MEMORY_TOOL_ROLLUP`。
- 行标签 `val opLabel = record.toolName`。`isWrite` **保持原值不动**：write=true、get/rollup=false —— 它是会话面板的点击路由判别符（Write 详情体要 `writtenContent`，rollup 没有），翻转它只会让 rollup 点进去是空页。**标签与路由分家**是本条的核心。

### F-10 截图名 = 毫秒 + 单调序号

```
private val browserScreenshotSequence = AtomicLong(0L)
internal fun browserScreenshotFilename(ts: Long, seq: Long) = "screenshot_${ts}_$seq.jpg"
→ browserScreenshotFilename(System.currentTimeMillis(), nextBrowserScreenshotSequence())
```

- 时间戳从秒升毫秒（10 位 → 13 位），序号在**进程级单调递增、不回绕、不同步也不清零**，所以撞毫秒 / 时钟回调 / 跨会话同毫秒三种情形都不会产生同名。
- 新名字仍以 `screenshot_` 开头且词典序随时间戳递增，`BrowserUseManager.pruneScreenshotCache` 的 mtime 排序（不是按名排）不受影响。

---

## 4. 验证（三源：JVM 单测 / 变异臂 / 源码形状两态）

**① 本地 JVM 单测：15 例全绿**（`probe-c7/run.sh`，kotlinc 2.1.0 + JUnit 4.13，编译的是**逐字切片**）

装置说明：两个生产文件都带 Compose / Android，整文件编不过。用 `probe-c7/extract.py` 按声明名 + 括号配平**逐字切片** 10 个声明（3 常量 + 2 纯函数 + 2 数据类 + 3 个记录相关），切片必须 verbatim 存在于源码否则脚本直接 FATAL（防止装置悄悄测了一份拷贝）。

- 纯决策：`writtenContent` 四态真值表、三个 op 标签互异、文件名形状（字面量 `screenshot_1772000000123_7.jpg`）
- IO 读集：`readAutoInjectedFiles` 用假 map 跑，**逐条点名**读到的是 `["SOUL.md", "2026-03-01.md", "2026-02-28.md"]`；跨月边界（2026-03-01 → 2026-02-28）、闰年（2028-02-29）、注入上限随快照传递
- 接线探针（4 组）：IO 越出组合期 / 保存失败进编辑器 / 行标签来自记录自身 / 三个记录点各自报名（+ 写入体受结果门控）

**② 变异臂 8/8 CAUGHT**

| 臂 | 变异 | 红 |
|---|---|---|
| M1 | `writtenContent` 回到无条件（修前形态） | 2 |
| M2 | rollup 标签常量改成 `memory_get`（修前形态） | 2 |
| M3 | 文件名回到秒级无序号（修前形态） | 3 |
| M4 | 序号冻成常量（同毫秒必撞） | 1 |
| M5 | 昨日 = 今日（日期窗口错） | 2 |
| M6 | 上限硬编码 200（修前形态） | 1 |
| M7 | 今日槽读昨日文件（读集错位） | 1 |
| M8 | rollup 标签空串（空药丸） | 2 |

每条变异**只红钉它自己的用例**，没有一条是全红 —— 断言是分层钉的，不是一锅粥。

**③ 源码形状两态（装置最有力的一条）**

同一套 4 组探针，两个树各跑一遍（`probe-c7/wiring_base.py`）：

| 探针 | 修后树 | 基线 `47c4bf4b` |
|---|---|---|
| io-probe | GREEN | **RED 5** |
| save-error-probe | GREEN | **RED 4** |
| pill-probe | GREEN | **RED 2** |
| record-sites-probe | GREEN | **RED 4** |

基线上 4 组共 15 条命中，其中 3 条直接点名修前原文（`isWrite` 派生标签、空 `catch`、组合期 `buildAutoInjectedItems(context, memoryRepository)`）。**没有这半个方向，接线探针就是自证的**。

**④ 对照臂（与生产口径同源的 Java 探针）**

- F-08：四文件 272,690 B，20 次读中位 2 ms / 最大 10 ms（JVM 上是热缓存；真机主线程 + 冷页更差）
- F-10：旧形状 200 次调用 → **1 个**不同名字；新形状 200 次 → **200 个**。另有一例直接落盘证明同秒第二次写把第一次的 16 字节换成 64 字节（前一张整体消失）

**⑤ 门禁（实树 + selftest 全 rc=0）**

`memory_ui_wiring_check`（113 门之一，直接钉本文件）、`memory_gate_provider_fields_check`（钉 `isWrite` 不翻转）、`tool_title_error_branch_check`（钉 ChatToolExecutors 的 artifactWriter 判定链）。三个 selftest 各出牙标记。

**⑥ 逐条四点：括号/注释配平**（抹掉字符串与注释后）两生产文件 + 新测试文件 = 0 不平衡。

---

## 5. 形状断言前/后（逐条对照）

| 形状 | 修前 | 修后 |
|---|---|---|
| 快照读取位置 | `remember(...) { buildAutoInjectedItems(context, memoryRepository) }` | `LaunchedEffect(...) { autoFiles = withContext(Dispatchers.IO) { readAutoInjectedFiles(...) } }` + `remember(..., autoFiles) { buildAutoInjectedItems(context, autoFiles) }` |
| 保存失败 | `catch (_: Exception) { /* 无 */ }` | `catch (e: Exception) { Log.w(...); editErrorMessage = e.message ?: e.toString() }` |
| 编辑器错误入参 | `errorMessage = null` | `errorMessage = editErrorMessage` |
| 写入记录体 | `writtenContent = content` | `writtenContent = memoryWriteRecordContent(result.success, content)` |
| 行标签来源 | `if (record.isWrite) "memory_write" else "memory_get"` | `record.toolName`（三常量） |
| 截图名 | `screenshot_<秒>.jpg` | `screenshot_<毫秒>_<单调序号>.jpg` |

---

## 6. 残留（登记不修，四条各带三件套）

- **R1** 快照 IO 趟失败（`readFile`/`readGlobalMd` 抛异常）会让 `autoFiles` 停在 null 或旧值 → 面板一直显示上一次的快照，无任何提示。
  **触发条件** = 用户报「面板行数不刷新」且日志有读异常。
  **方案** = IO 趟 `runCatching` 包一层，失败时把原因沉到 Section 1 的 footer。
  **搁置理由** = 读路径当前是「读不到返回空串」，与读失败不可区分，单独加错误面会牵动 `MemoryRepository` 契约（跨支文件，不在本支半径）。
- **R2** 保存失败后仍留在编辑态，没有「重试 / 放弃编辑」出口。
  **触发条件** = 真机出现保存失败报告。
  **方案** = 工具栏在 `editErrorMessage != null` 时补「放弃编辑」。
  **搁置理由** = 当前取舍（保住缓冲区）比丢弃更安全，缺的是出口不是行为。
- **R3** `MemoryToolRecord.toolName` 是 `String` 而不是枚举/sealed。
  **触发条件** = 出现第 4 个 memory 工具。
  **方案** = `MemoryToolOp { WRITE, GET, ROLLUP }` + rollup 详情体（backlog 2953 行 R4 已登记同一件事，方案逐字一致）。
  **搁置理由** = 三态下字符串够用，枚举要连带改 `MemoryDetailScreens` 的详情体（本支文件面之外）。
- **R4** 截图序号进程内单调递增，进程重启（app 冷启动）后回到 0。
  **触发条件** = 有人报「重启后首张截图与前一次活体周期的某张同名」。
  **方案** = 文件名只留时间戳 + 短随机后缀（去掉序号），或序号持久化到 session 目录。
  **搁置理由** = 重启后毫秒时间戳本身已是 13 位新值，撞名需要「同毫秒 + 同序号」两个条件同时成立，概率上不需要现在付这个钱。

**F-08 与 backlog 的关系**：它闭合了 backlog §4024 R3（「孪生屏 `SessionMemorySheet` 的 `saveFile` 仍在点击线程同步执行」，触发条件 = sheet 侧出现大文件卡顿报告）—— 本条把 **读取**那一半移出了组合期；`saveFile` 的**写入**仍在点击线程（见 R2 的出口缺口），未动，因为改写入要连带 `autoItemsRefreshTick` 的时机语义，成本与风险都越过半径。

---

## 7. 未验证

- **真机行为**（面板首帧是否真的不卡、保存失败的红字、rollup 行标签、同一秒两次截图）—— 沙箱无 Android 运行时，CI 只保证编译 + 单测 + 门禁。
- **截图名的唯一性在并发下的形状**：`AtomicLong.incrementAndGet()` 保证不重号，但两个线程拿到的可能是 (ts=100, seq=1) 与 (ts=101, seq=2) 之外的任何配对 —— 只需「(ts, seq) 二元组不重复」即可，该性质成立。
- **`pruneScreenshotCache` 的名序假设**：它按 mtime 排序（不是名字），本改动不影响；但文件名里的时间戳从秒变毫秒会让「按名字排」的第三方代码行为变化 —— 全仓 grep 无此类消费方。

---

## 8. 证据清单

| 项 | 位置 |
|---|---|
| 装置（切片器 / 跑测 / 变异臂 / 基线探针） | `shared/work/meta-audit-1028/probe-c7/{extract.py,run.sh,arm.py,mut.sh,wiring_base.py}` |
| 对照臂 | `probe-c7/SnapshotReadCostTest.java`（F-08 规模）、`probe-c7/ScreenshotNameCollisionTest.java`（F-10 唯一性） |
| 新测试 | `src/android/app/src/test/java/com/rikkaminis/app/ui/chat/MemoryRecordScreenshotAndSheetIoTest.kt`（15 例） |
| 门禁读数 | 三门实树 rc=0、selftest rc=0，牙标记各 1 次 |

**负载纪律**：全程 `nice -n 10`；未跑 `scan.sh` / `test_scan.py` / gradle；同一时刻只有一个 JVM；四个相关门（而非 113 门全量）逐个直跑。
