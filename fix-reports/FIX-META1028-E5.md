# FIX-META1028-E5：设置屏四条 —— 保存拒绝被绑定 / 崩溃日志按 mtime / 可写性刷新证伪 / URL 锚点剥离

- 分支：`fix/meta1028-e5-settings-a`
- 基线：`bfb5b0e6`（main）
- 改动面：14 文件 **+937 / −56**
  - 生产 7：`ui/settings/ModelEntryDetailScreen.kt`（+52）、`AddCustomModelScreen.kt`（+53）、
    `LogManagementScreen.kt`（+16）、`MinisSkillsBrowserScreen.kt`（净删，旧两个 private helper 迁出）、
    新 `ModelEntrySavePolicy.kt`（66）、新 `CrashLogMerge.kt`（36）、新 `SkillSourceUrl.kt`（103）
  - 资源 3：`values/strings.xml`（+4）、`values-zh/strings.xml`（+2）、`values-zh-rTW/strings.xml`（+2）
  - 测试 4（新）：`ModelEntrySavePolicyTest`（6 例）、`CrashLogMergeTest`（4 例）、
    `SkillSourceUrlTest`（9 例）、`Meta1028E5WiringTest`（8 例）= **27 例**
- `scripts/scan/**`、`scan.sh`、`test_scan.py` **一行未动**；门 113 / 夹具 349 基线不变
- D4 独占文件（`BackupSettingsScreen` / `EnvironmentVariablesScreen` / `MCPIntegrationsScreen` /
  `ThinkingRule*`）、`sandbox/**`、`data/**` **零触碰**

---

## 1. 先证（四条各自回真树核实）

### F-07 —— 保存拒绝被吞掉后照样关窗：**成立，但报告的机制描述两处不准**

真码（`ProviderRepository.kt`）：

| 调用 | 返回类型 | 拒绝时返回什么 | 写了吗 |
|---|---|---|---|
| `updateEntry(entry)` :1651 | `String?` | `null`（**双义**：重名冲突 / 索引不存在） | 否 |
| `addEntry(entry)` :1599 | `String`（**非空**） | 返回**已占用该 key 的那条**的 id | 否 |

两个屏都把返回值丢掉 + 无条件 `onBack()` ⇒ 改完点保存、窗口弹出、改动消失、无任何提示。

**纠正报告两处**（证据差异表 §7-1/§7-2）：
1. 报告写"`addEntry` 返回的 false/空值被忽略"——真码 `addEntry` **不可能返回 null**，
   拒绝信号只能靠"它返回的 id 本来就在我们写前那份 id 集合里"反推（成功新增的 canonical id
   按构造不在快照中）。这是本条最关键的判据，写错就会写出一个永不触发的 `?: return`。
2. 报告把 `updateEntry` 的 `null` 当成单一语义（"重名冲突"），而它对"行已经不存在"也返回
   `null`。两者响应不同：重名要留屏提示改名，行没了要直接关窗——混为一谈会给已删除的条目
   弹"这个 ID 已被占用"。

### F-09 —— 崩溃日志按文件名排序：**成立**

`LogManagementScreen` 把 Java（`crash-<local stamp>.log`）与 NDK
（`native-crash-<UTC stamp>.log`，格式见 `cpp/crash_handler.cpp:272`）两个来源拼接后
**按文件名降序**再截断。两族前缀 `native-crash-`（`n`）与 `crash-`（`c`）相比，
所有 native 报告恒排在所有 Java 报告之前，与发生时间无关；100 条上限随后把**最新 Java 崩溃**
先丢掉——native 崩溃循环可以把 Java 列表整个饿死。即便不上限，顺序也是错的。

真实时间戳**本来就已按行捕获**：`AppLogger.listLogFileMetas` 的 `LogFileMeta.lastModified`
（epoch millis，列目录时一次 stat）。不需解析文件名、不碰 `T`/`_` 分隔符差异、不踩 UTC vs 本地时区坑。

### F-10 —— 从系统「所有文件访问权限」页返回不刷新：**证伪，登记不修**

`MinisApp.kt` 在 `Application.onCreate` 里注册了 `ActivityLifecycleCallbacks`，
`onActivityResumed` **无条件**调用 `mountedFoldersStore.refreshWritability()`（基线即存在）。
用户从系统设置页返回时 Activity 必然 resume ⇒ 刷新必然发生。
屏幕本身还通过 `store.entries.collectAsState()` 订阅状态。

我按报告先加了屏级 resume 刷新，实验后**回退**（`git checkout -- MountedFoldersScreen.kt`）：
它与全局钩子叠加 = 每次返回探针跑两遍，纯 IO 翻倍，不带来任何新行为。

### F-11 —— 未剥 `#fragment` / `?query`：**成立**（主会话已确认，我复核同一结论）

真码 `toRawGitHubUrl` 只做 `replace("/tree/", "/")` + `trimEnd('/')`。
带页内锚点的地址（`.../blob/main/skills/x#L10` 或 `...?tab=readme`）会把锚点拼进**派生路径中间**：

```
https://raw.githubusercontent.com/o/r/main/skills/x#L10/SKILL.md   ← 404
```

用户看到的失败提示是"没有找到 SKILL.md"，指向错误方向（真因是 URL 被自己拼坏）。
数据层（`SkillRepository`）用 `Uri.parse().pathSegments` 解析，不受影响——只有屏幕侧预取路径坏。

---

## 2. 修法

### 2.1 F-07：把"写到底成没成"提成有名字的判定（新增 `ModelEntrySavePolicy.kt`）

两种拒绝在类型上不一样，硬塞进一个 `if` 必然有一边写错，所以先提成纯函数：

```kotlin
internal enum class EntrySaveOutcome { SAVED, ID_TAKEN, ENTRY_GONE }

internal fun classifyUpdateResult(persistedId: String?, entryStillExists: Boolean): EntrySaveOutcome
internal fun addEntryRejected(persistedId: String, idsBefore: Set<String>): Boolean
```

- `ModelEntryDetailScreen`：`updateEntry` 之后**重读**一次 live 配置判断这条还在不在，
  再交由 `classifyUpdateResult` 分三路 —— `SAVED`/`ENTRY_GONE` 关窗，`ID_TAKEN` **留屏**
  并在 Model ID 字段下方显示内联错误；输入时清错误。
- `AddCustomModelScreen`：**写之前**拍一次 id 快照（`idsBefore`），写后拿返回值比对；
  命中快照 ⇒ 拒绝 ⇒ 留屏 + 内联错误。
- 两条新字符串进 `values` / `values-zh` / `values-zh-rTW`（key 三处一致，已核）。

### 2.2 F-09：排序换成真实 mtime（新增 `CrashLogMerge.kt`）

```kotlin
internal fun <T> newestFirstCapped(entries, limit, nameOf, modifiedOf): List<T>
//  mtime desc 为主序；文件名 desc 只做同秒去抖的确定性 tie-break；再 take(limit)
```

泛型化到 `(T) -> Long` 访问器，是为了让排序契约能在 JVM 上被字面量断言
（`AppLogger.LogFileMeta` 是 Android 绑定、无法离线编译）。

### 2.3 F-11：URL 先归一化再派生（新增 `SkillSourceUrl.kt`）

`stripUrlAnchor` 取 `#` 与 `?` 的**最早**出现位置截断（顺序无关），
`isSkillDirectoryUrl` / `toRawGitHubUrl` 全部基于归一化结果。
两个 helper 从屏幕文件迁出成 `internal`，屏幕改为
`val sourceUrl = stripUrlAnchor(url).trim()` 后沿用，`SkillRepository` 侧不动。

### 2.4 F-10：不修（证伪）

---

## 3. 装置与读数

### 3.1 真单测 27 例（JVM 真跑，源码非复刻）

| 类 | 例数 | 钉什么 |
|---|---|---|
| `ModelEntrySavePolicyTest` | 6 | 三态分类（含"行没了" vs "重名"分叉）；`addEntryRejected` 的"写前已有 = 拒绝" |
| `CrashLogMergeTest` | 4 | 跨族时间序（构造 native 早 / Java 晚的样本，断言 Java 在上）；上限取全局最新 N；同 mtime 确定性 |
| `SkillSourceUrlTest` | 9 | `#`/`?`/两者/顺序颠倒/尾部斜杠的表驱动；`isSkillDirectoryUrl` 锚点不改变判定；`toRawGitHubUrl` 全谱 |
| `Meta1028E5WiringTest` | 8 | **形状断言**：读源码，确认返回值被绑定、拒绝分支不 onBack、快照在写之前、日志无文件名排序残留 |

读数：`OK (27 tests)`，Time 0.179 s，rc=0。

### 3.2 变异负控 10 臂（`probe-e5/arm.py`，一臂一进程）

每臂只回退**一个**缺陷形态，断言"只有钉它的用例变红"：

| 臂 | 变异 | 判红用例 |
|---|---|---|
| M1 | detail 忽略 `updateEntry` 返回值 | `detailScreen_bindsTheUpdateResult`、`detailScreen_routesRefusalAwayFromOnBack` |
| M2 | detail 拒绝分支也 onBack | `detailScreen_routesRefusalAwayFromOnBack` |
| M3 | detail 静默 return 不提示 | `detailScreen_routesRefusalAwayFromOnBack`、`detailScreen_surfacesTheRefusalToTheUser` |
| M4 | add 忽略 `addEntry` 返回值 | `addScreen_bindsTheAddResult`、`addScreen_guardsOnBackWithTheRefusal` |
| M5 | add 快照取在写之后 | `addScreen_snapshotPrecedesTheWrite` |
| M6 | 退回文件名排序 | `logScreen_mergesCrashListsByTimestampNotName`、`logScreen_noNameSortLeftInTheCrashMerge` |
| M7 | 形状对但按名字比较 | `logScreen_mergesCrashListsByTimestampNotName` |
| M8 | 不剥 `#fragment` | 5 条 `toRawGitHubUrl_*` + `stripUrlAnchor_table` + `isSkillDirectoryUrl_anchorAndQueryDoNotChangeVerdict` |
| M9 | 不剥 `?query` | 3 条 `toRawGitHubUrl_*` + `stripUrlAnchor_table` + 2 条 `isSkillDirectoryUrl_*` |
| M10 | 只切最后一个分隔符 | `stripUrlAnchor_table` |

10/10 `RESULT MATCH`，每臂 `RESTORED OK`（sha256 逐字节回校）。

### 3.3 两态对比（形状断言确实是"钉缺陷"而非"描述现状"）

`probe-e5/prepost.py` 把三屏临时换回 `bfb5b0e6` 版、跑同一套 `Meta1028E5WiringTest`：

```
PRE-FIX rc=1 failed=[addScreen_bindsTheAddResult, addScreen_guardsOnBackWithTheRefusal,
 addScreen_snapshotPrecedesTheWrite, detailScreen_bindsTheUpdateResult,
 detailScreen_routesRefusalAwayFromOnBack, detailScreen_surfacesTheRefusalToTheUser,
 logScreen_mergesCrashListsByTimestampNotName, logScreen_noNameSortLeftInTheCrashMerge]
RESULT ALL_RED
```

8/8 形状断言在修前形态上全红，修后全绿，三屏 `RESTORED OK`。

### 3.4 窄门 9 道（钉住本支改动面）

| 门 | 结果 |
|---|---|
| `model_entry_override_parity_check` | rc=0 |
| `entry_id_rename_integrity_check` | rc=0 |
| `provider_remember_key_check` | rc=0（**中途曾红，见 §5-2**） |
| `skill_frontmatter_roundtrip_check` | rc=0 |
| `sysinfo_state_thread_check` | rc=0 |
| `readonly_mount_write_guard` | rc=0 |
| `chat_menu_prefs_listener_check` | rc=0 |
| `filepreview_print_bounded_check` | rc=0 |
| `i18n_check` | rc=0（`ALL CLEAN`；`values-zh`/`zh-rTW` 各 4 条 missing 是既有的 `notif_storage_budget_*`，与本支无关） |

---

## 4. CI

| 项 | 值 |
|---|---|
| run | `#2845`（`build-apk.yml`，`workflow_dispatch`，ref `fix/meta1028-e5-settings-a`） |
| 创建 | 2026-10-28T13:54:33Z |
| head_sha | `<待回填>` |
| 结论 | `<待回填>` |

分支 push 不触发 CI（`build-apk.yml` 只认 main），故走 `gh-actions-dispatch`；
本支改了 `src/**`，必须全量构建（廉价门不适用）。

---

## 5. 诚实边界

1. **真机未验**。全部证据来自 JVM（纯逻辑 + 源码形状）+ 窄门；Compose 渲染、
   `AppLogger.listLogFileMetas` 的真实 `lastModified`、以及"拒绝时留屏"的实际观感
   都只能在真机上确认。CI 只保证编译 + 单测 + 113 门。
2. **C21 一度判红，是我自己把门推出去的**（值得记）：`provider_remember_key_check`
   的 guard 识别有 900 字符窗口，我插入的 `idsBefore` 快照 + 注释把 guard 起点推到
   窗口下限外 **4 个字符**，门判"无可行 guard"。修法不是改门，而是把注释与快照上移到
   guard **之前**（`if` 起点之前的文字不计入窗口）。教训：**门的窗口/距离判据会让
   "只是加了注释"变成红**——加长写前注释前先跑一遍钉这文件的门。
3. `Meta1028E5WiringTest` 是**形状断言**（读源码正则），不是行为测试：它能钉"返回值被绑定 /
   拒绝分支不 onBack / 快照在写之前"，钉不了"用户真的看到提示"。行为面由
   `ModelEntrySavePolicyTest` 的纯函数断言承担，两者互补。
4. `CrashLogMerge` 的 tie-break 用文件名，这是**刻意**的：同秒两条报告需要确定性顺序，
   但它永远不压过 mtime 主序（M7 臂专门钉这一点）。

---

## 6. 新登记残留（三件套齐全，待并入 backlog）

| ID | 内容 | 触发条件 | 触发后方案 | 为何不本次修 |
|---|---|---|---|---|
| **R1** | `AddCustomModelScreen` 的 `idsBefore` 快照与 `addEntry` 之间仍有窗口（快照→写之间若别人新增同 id，判定会把"别人刚写的"误认成"我们的"⇒ 误报拒绝） | 真机出现"第一次保存提示已存在，再点一次成功" | 让 `addEntry` 返回显式结果（`AddEntryResult` sealed：`Added(id)` / `Duplicate(existingId)`），数据层改返回值类型 | 改 `ProviderRepository` 公共签名 ⇒ 跨分支冲突面大（D1 支同文件），且需要全仓 7 个调用点同步 |
| **R2** | 两个内联错误的文案只覆盖"重名"，没覆盖"父实例已被删除"（`ENTRY_GONE` 直接关窗，用户不知道为何退出） | 用户报"改了个模型结果窗口自己关了" | 关窗前给一条 Toast/Snackbar 说明"该模型已被删除" | 需要父屏 snackbar host 接线（跨文件），超出本条半径 |
| **R3** | `crash-` / `native-crash-` 的 `lastModified` 在文件被**拷贝**（而非原地生成）时会变成拷贝时刻，排序依据失真 | 用户从别处拷入历史崩溃日志 | 在 `AppLogger` 落盘时把时间写进文件首行，读取时优先用它 | 需改 native 侧 `crash_handler.cpp` 格式 + 解析，跨 native/Java 两层 |

---

## 7. 证据差异表（报告声称 vs 真树核实）

| # | B06 报告的说法 | 真树核实 | 影响 |
|---|---|---|---|
| 7-1 | "`addEntry` 失败返回空/false 被忽略" | `addEntry` 返回**非空** `String`，拒绝时返回**别人**的 id | 若照报告写 `?: return`，该分支**永不触发**——修了等于没修 |
| 7-2 | "`updateEntry` 返回 null = 重名冲突" | `null` **双义**：重名冲突 **或** 行已不存在 | 照报告写会给已删除条目弹"ID 已被占用" |
| 7-3 | "崩溃日志按文件名排序导致顺序错" | 成立；且报告未提 **100 条上限会饿死最新 Java 崩溃** | 只改排序不改上限顺序仍然错，故按"全局最新 N"实现 |
| 7-4 | "mtime 需要解析文件名获取" | `LogFileMeta.lastModified` **已捕获**（列目录时一次 stat） | 无需解析文件名，绕开 `T`/`_` 与 UTC/本地时区两个坑 |
| 7-5 | F-10 "返回未刷新可写性" | **证伪**：`MinisApp` 全局 `onActivityResumed` 已无条件刷新（基线即有） | 加屏级刷新 = 双倍探针 IO 无新行为，已回退 |
| 7-6 | F-11 "未剥 fragment/query" | 成立（`toRawGitHubUrl` 只 `replace` + `trimEnd`） | 与报告一致；补充：数据层用 `pathSegments`，只有屏幕预取路径受影响 |
| 7-7 | 报告给的调用点行号 | 与本支改动后行号有漂移（`AddCustomModelScreen` 写路径整体后移） | 按语义定位，不按行号 |

---

## 8. 附录：证据索引

| 路径 | 内容 |
|---|---|
| `probe-e5/runtests.py` | 跑当前树 27 例（一进程一 JVM，nice 10） |
| `probe-e5/arm.py` | 10 变异臂（每臂备份→变异→编译→跑→断言红集→还原→sha256 回校） |
| `probe-e5/prepost.py` | 前/后两态（三屏换回基线版，跑同一套形状断言） |
| `probe-e5/m1..m10.log` | 每臂原始读数 |
| `probe-e5/prepost.log` | 两态原始读数 |
