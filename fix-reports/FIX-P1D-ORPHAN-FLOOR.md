# P1-D — 孤儿清理的空集地板守卫（FINDING-26）

分支 `fix/p1-orphan-floor` ｜ 基线 `main e6f2e4be` ｜ 代码提交 `308120c7`

---

## 1. 任务与判据

任务书 `p1-fix-1027/TASK-P1-D.md`：`SessionFileStore.reclaimOrphans(liveSessionIds)` 的
`live` 集合完全来自 `dao.listSessions()`。当 `sessions` 表为空（库被重建 / 恢复只带回部分表 /
将来某次迁移清表）而 `filesDir` 完好时，**空集把全部会话目录判成孤儿**：用户点一次确认，
`minis-sessions/<sid>/`、`media/<date>/<sid>/`、`browser_tabs/<sid>.json` 三类一起
`deleteRecursively` —— 工作区、附件、offload 全灭。代码里没有任何「live 为空就不删」的地板。

判据（本次认可的唯一标准）：**「DB 说没有会话」不等于「磁盘上的会话目录是垃圾」。**
前者是「我们不知道」，后者是「我们知道它们是垃圾」——只有后者才允许删除。

行号复核：报告里 `:549-550 / :443-451` 是 `62caedb4` 时代；本树 `reclaimOrphans` 在
`SessionFileStore.kt:344`，UI 调用点在 `StorageManagementScreen.kt:556`（改动前 `:550`）。

## 2. 症状链与 caller 清单

症状链（W3/W2 世界：DB 行丢失而 filesDir 完好）：

```
Storage 页 reload():  liveIds = dao.listSessions().map { it.id }.toSet()   // 空集
   → 三个扫描把每个 minis-sessions/<sid>/、media/<date>/<sid>/、browser_tabs/<sid>.json
     都算成 "N 个残留项 / X MB"（只报数量，不点名）
   → 用户点「回收」→ AlertDialog 确认（文案只有 N 与 X MB）
   → reclaimOrphans(live) → 逐个 deleteRecursively + 删 media leaf + 删 tab 文件
   → 用户所有工作区 / 附件 / offload / 浏览历史消失，且没有任何一处记录被删了什么
```

**全部 caller（`grep -rn "reclaimOrphans\|scanOrphan" src/` 的结果，逐条判 `live` 来源）**

| # | 位置 | live 来源 | 是否可能为空 | 结论 |
|---|---|---|---|---|
| 1 | `ui/settings/StorageManagementScreen.kt:556`（确认按钮 lambda 内） | `chatRepository.dao.listSessions().map { it.id }.toSet()` | **是**（本条缺陷的唯一生产触发点） | 用户确认后删除；已加地板 + banner 预警 |
| 2 | `data/repository/ChatRepository.kt:294 reclaimOrphanSessionFiles()` | `observeSessions().first().map { it.id }` | **是**（同一 DB，且 `first()` 可能在 Room 尚未加载时给出空表） | **零生产调用者**（全仓 grep 只有定义）。地板会让它返回 `blocked` 报告而不是清空磁盘，正是它需要的语义 |
| 3 | `StorageManagementScreen.reload()` 的三个扫描（`scanOrphanSessionDirs` / `scanMediaOnce` / `scanOrphanBrowserTabFiles`） | 同上 `liveIds` | 是 | **只测量不删**，但 banner 是删除的唯一入口 —— 不给它同一判据，就会出现「banner 说可回收 N 项、点了却什么都没删」的假成功。已接同一判据 |
| 4 | `SessionFileStore.deleteSessionFiles(id)` | 显式 id（调用方已知会话） | 不适用 | 不经 live 集合，不需要地板 |
| 5 | `deleteOrphanMediaLeaves` / `deleteOrphanBrowserTabFiles` | private，只从 #1 进入 | 不适用 | 继承 #1 的守卫 |
| 6 | `sandbox/PRootKernel.kt:1551` `sessionsRoot.listFiles()` | — | — | 只读路径解析（找不到文件时回落 rootfs），不删 |
| 7 | `webapp/AddToHomeSheet.kt:207`、`webapp/WebAppPathResolver.kt:127` | — | — | 只取 `sessionsRoot` 当路径前缀，不删 |

**关于「自动清理路径」**：本仓**没有**自动删除型 orphan 清理（没有启动期 sweep、没有
WorkManager job）。自动的只有「扫描 + banner」，删除永远经用户确认。所以本条的守卫需求落在
两处而不是一处：store 层拒绝执行（数据安全）+ UI 层不假装可回收（不许静默跳过）。

## 3. 修法

### 3.1 store 层地板（必须项）

`SessionFileStore`：

- 新增 `ReclaimReport.blocked: Boolean = false` / `blockReason: String? = null`
  与 `ReclaimReport.BLOCK_NO_LIVE_SESSIONS = "no-live-sessions"`（**理由码，不是文案**：
  7 个语言文件的措辞归 UI，日志/统计认这个字符串）。
- 新增单点判据 `fun withEmptyLiveFloor(report, liveSessionIds)`：`report.totalDirs > 0 &&
  liveSessionIds.isEmpty()` ⇒ `report.copy(blocked = true, blockReason = …)`。
  **扫描半与删除半都调它**，所以「banner 承诺的」和「sweep 会做的」不可能漂移。
- `reclaimOrphans` 在删除前取 `withEmptyLiveFloor(scanOrphans(live), liveSessionIds)`；
  `blocked` ⇒ **三类全不删**、`AppLogger.warning` 记录原因与候选量、返回带计数与 `blocked`
  的报告。
- 空集 + **没有候选** ⇒ 普通 0 报告，不 blocked（新装机不能因为「无事可做」看到报错）。

### 3.2 判据的输入：raw set，不是 draft 加宽后的集合（本条的第二次判断）

`withActiveComposerOwner` 会补上 composer 的活动草稿 id。**它解决不了这条缺陷**，任务书也点了这一点：
一个草稿 id 只说明「这一个槽位占着文件」，对「DB 整体是否可信」零信息。若地板读加宽后的集合，
空 DB + 一个活动草稿 = 地板失效，全部真实会话目录照删。所以地板读**调用方传入的原始集合**，
`reclaimOrphans` 内部显式传 `liveSessionIds`（未加宽）而不是 `live`。这一点由专门用例
`an active draft does not unlock the empty-live-set floor` 钉住，并由变异 `widened-input`
证明该用例真有牙（见 §4）。

### 3.3 UI 层（建议项，已做）

- banner 用同一判据：blocked 时渲染新文案（`storage_orphan_banner_blocked`，
  已补 en / zh / zh-rTW；其余 5 个语言文件缺失即回退英文，i18n 门对缺失翻译只 WARN 不 FAIL），
  **不可点击、不显示「回收」动作标签**——不提供一个注定 no-op 的动作，避免「点了没反应 = 已清理」。
- 确认路径保留返回的报告：`blocked` ⇒ 保留 banner（现在以 blocked 形态渲染）并跳过重扫
  （磁盘没变）；非 blocked ⇒ 照旧清空 banner + 作废快照 + 重扫。
- **点名**（要删的是哪几个目录）留作残留 R1，理由见 §7。

### 3.4 刻意不做

- 不加「先自动备份再删」：另一支没做，且会给删除路径引入新的失败面。
- 不改 `deleteRecursively` 的实现；不碰 `backup/`（P1-A/B/C 在动）。
- 不建新门（P1 装置比例）。

## 4. 装置

### 4.1 JVM 用例（真生产文件 + 真测试文件，无切片）

`SessionFileStoreTest.kt`：原有 20 例 + 新增 5 例 = **25 例**。

新增：

| 用例 | 断言 |
|---|---|
| `reclaimOrphans refuses the whole sweep when the live set is empty` | `blocked=true`、`blockReason` 为理由码、候选**仍被测量**（2 session / 1 media / 1 tab）、三类文件**全在** |
| `reclaimOrphans with an empty live set and nothing to reclaim is not blocked` | `blocked=false`、`blockReason=null`、`totalDirs/totalBytes=0`（区分「拒绝」与「无事可做」） |
| `reclaimOrphans with a live session still deletes the other dirs` | 非空 live ⇒ 活会话存活、另 2 个删除、`sessionDirs=2`（既有行为不回归） |
| `an active draft does not unlock the empty-live-set floor` | 空 DB + 活动草稿 + 一个真实会话目录 ⇒ **blocked 且该目录存活**（钉住 raw-vs-widened） |
| `the banner and the sweep share one floor predicate` | `withEmptyLiveFloor`：空集 + 候选 ⇒ blocked 且计数保留；有 1 个 live ⇒ 不 blocked；空候选 ⇒ 不 blocked |

**既有用例的必要调整（5 处，全部记录在此）**：`reclaimOrphans(emptySet())` 在这些例里是
「没有会话是活的」的简写，现在这个写法本身就是「DB 什么都不知道」= 拒绝。改为
`s.reclaimOrphans(someLiveId())`（一个不占文件的活会话 id），并在测试类加了说明注释与
`someLiveId()` 辅助。5 处 = `reclaimOrphans deletes orphan session and media dirs` /
`deletes orphaned draft __new__ tab files` / `never touches non-session-shaped files` /
`draft-shaped names without a full UUID suffix stay un-reclaimable` /
`keeps the active draft's session dir, media and tab file`。**这一步不是放水**：这些用例断言的
是「非 live ⇒ 删 / 非会话形状 ⇒ 不删」，与地板无关；不给它们一个 live id 的话，它们会
因为地板（正确的）拒绝而开始测试另一个东西。

### 4.2 变异负控（副本上变异，仓库树零改动）

脚本 `probe-orphan/mutants.sh`，每个变异重编真源码 + 跑全类：

| 变异 | 改法 | 读数 | 红在哪个臂 |
|---|---|---|---|
| `no-floor` | 判据 `if (totalDirs > 0 && live.isEmpty())` → `if (false)` | **3 红 / 25** | 拒绝臂 + draft 臂 + 共享判据臂 |
| `widened-input` | 地板输入 `liveSessionIds` → `live`（加宽后） | **1 红 / 25** | `an active draft does not unlock the empty-live-set floor` |
| `ignored-block` | `if (report.blocked)` → `if (false)`（算了但不听） | **2 红 / 25** | 拒绝臂（文件被删）+ draft 臂；共享判据臂保持绿 = 它是纯函数臂，正确 |

`sha256(SessionFileStore.kt)` 变异前后一致（`da9b706f25254d4a…`），树未被污染。

**compile 判读纪律**：`kotlinc` 的 rc 直接取，不接 `grep` 管道（管道会吃掉 rc，制造假
`COMPILE FAILED`）。

## 5. 本地门读数（原文）

- JVM：`--- prod ---  OK (25 tests)`（真 `SessionFileStore.kt` + 真 `SessionFileStoreTest.kt`
  + `android.content.Context` Java 桩 + `ComposerDraftStore`/`AppLogger` 桩）
- 静态门：`Summary: 113 passed, 0 failed`（`sh scripts/scan/scan.sh --jobs 3`，4m49s）
  - 其中 `browser_tabs_lifecycle_check` 的 C3/C3b/C7/C8/C12 全绿：`reclaimOrphans` 体里
    `deleteOrphanBrowserTabFiles(` 与 `withActiveComposerOwner(` 仍在（C3/C8 锚点未破），
    UI 的 `scanOrphanBrowserTabFiles(liveIds)` ↔ `reclaimOrphans(live)` 的「同源」判据
    （C12b）经 `val` 内联后仍相等。**签名与删除半形状均未改动**，所以这道门不需要重判。
- 夹具：**48 组 / 349 例 / 0 失败**。
  - `--jobs 4` 在壳内直接撞 `scheduler: OSError: [Errno 38] Function not implemented`（已知），
    改为逐组 `--single <name>` 独立进程循环聚合（`probe-orphan/fixtures.sh`）。中途遇到一次
    整壳级 `Function not implemented` 抖动（`head`/`grep`/`rm` 都失败），分组补跑后全绿：
    首次 28 组 157 例 + 补跑 20 组 192 例 = 349。

## 6. CI 读数

`build-apk.yml` @ `fix/p1-orphan-floor`：run **#2797** / id `40611813992` / `success`
（20.6 min，00:55:06Z→01:15:41Z），**`head_sha = 308120c7e5bc…` 与本地 HEAD 逐字符一致**。

- 步骤：1–22 全 `success`（含 `Run unit tests (full suite)`、`Build APK with Gradle`、
  `Verify APK signature`、`Verify APK contents`）；23/24（滚动 tag + Publish）`skipped`
  —— 分支构建的预期门控，不是失败。
- 日志 1,482,982 B / NUL 0：
  - `Summary: 113 passed, 0 failed` ×1
  - `ALL 349 CASES PASS` ×1
  - `^ e: ` **0**
  - 含 `FAILED` 且不含 `PASSED` 的行 **0**
  - `SessionFileStoreTest` **25 条全 PASSED**（含 5 条新增：`refuses the whole sweep when the
    live set is empty` / `with an empty live set and nothing to reclaim is not blocked` /
    `with a live session still deletes the other dirs` /
    `an active draft does not unlock the empty-live-set floor` /
    `the banner and the sweep share one floor predicate`）—— 即在**真 Android SDK** 下
    25 例同样全绿，不是只在沙箱桩环境成立。


## 7. 残留（三件套：触发条件 / 方案 / 搁置理由）

**R1 — 对话框仍不点名要删的目录**（任务书允许留作残留）
- 触发条件：用户反馈「不知道清理会删掉什么」，或出现「回收后才发现丢东西」类报告。
- 方案：`ReclaimReport.sessionIds` 已经有 id 列表；改成在确认文案里附前 3 个目录名 +
  会话标题（`dao.getSession(id)` 反查，DB 空时必然读不到标题 ⇒ 只能显示目录名），
  或者干脆在删除前把 id 列表写进日志/报告（`AppLogger`）——后者零 UI 风险。
- 搁置理由：与本条危害（一次点击无声全删）不同级；改文案要动 7 个语言文件 + Compose 结构，
  装置比例不划算。**当前至少做到「不会删」与「不会假装可回收」**。

**R2 — 空 DB + 只有陈旧草稿槽的场景不再回收其文件**
- 触发条件：真机出现「没有任何会话，但 `minis-sessions/__new__<uuid>/` 长期占空间」。
- 方案：把地板判据从「DB 空」升级为「DB 空 **且** 本进程/本安装从未见过会话」——用持久化
  标记（SharedPreferences 记一个 `seenSessionEver`）区分「新装机」与「库被清空」；
  或给该场景一个显式二次确认（不是普通回收按钮）。
- 搁置理由：地板宁可多留垃圾也不误删数据；代价是磁盘，不是数据。

**R3 — 判据只覆盖 `reclaimOrphans` 一族**
- 触发条件：新增第二个「以 live 集合判定删除」的入口（例如将来做 offload 目录 sweep）。
- 方案：复用 `withEmptyLiveFloor`；到那时再评估是否值得加门（本轮遵守 P1 装置比例，不建新门）。
- 搁置理由：当前全仓只有这一个破坏性 sweep（§2 已逐条列尽 caller）。

**R4 — 未做真机 UI 走查**
- 触发条件：下个真机批次。
- 方案：造一个空 DB + 残留目录的状态（debug 方法清 `sessions` 表），看 blocked banner
  的换行/截断（新文案比其他横幅长一截），确认不可点击、无动作标签。
- 搁置理由：沙箱无 Android 运行时；CI 只保证编译 + 单测 + 门。

## 8. 方法备注（可复用）

1. **守卫要落在「谁付最大的代价」那一层，但可见性要落在「用户会点的那个动作」上。**
   只在 store 拒绝 = 用户点了确认、界面清空 banner、看起来成功（假成功）；只在 UI 提示 =
   任何新 caller（如 `ChatRepository.reclaimOrphanSessionFiles`）绕过去就全灭。本次两处都放，
   并用**一个共享判据函数**保证不漂移。
2. **「简写」在加守卫后会变成另一个命题。**5 个既有用例用 `emptySet()` 表示「没有活会话」，
   守卫上线后它变成「DB 什么都不知道」。这类语义漂移必须在测试里显式改名/改参数并写理由，
   否则下一个读者会以为守卫把既有行为改坏了。
3. **变异要挑「同一判据的不同输入」**：`widened-input`（raw → draft 加宽）只让 1 个臂变红，
   恰好证明那条臂是为这个决定而存在——比 3 个臂全红更能说明测试的结构。
4. **`--jobs N` 的 `Errno 38` 与整壳级 `Function not implemented` 是两种不同的环境故障**：
   前者是调度器 API 不可用（稳定复现，换 `--single`），后者是整壳 fork/exec 抖动
   （`head`/`grep`/`rm` 一起报错，等一会分组重跑即可）。把它们都当「测试失败」会误判。
