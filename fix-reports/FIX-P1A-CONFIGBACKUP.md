# FIX-P1A — ConfigBackup 三合一（FINDING-7 / RT-S5 / RT-S3）

- 分支：`fix/p1-configbackup`
- 基线：main `e6f2e4be`（= 任务书基线；`git merge-base --is-ancestor` 已核）
- 改动面：3 个生产/门文件 + 3 个测试文件
  - `backup/ConfigBackup.kt`
  - `ui/settings/BackupSettingsScreen.kt`
  - `scripts/scan/backup_remap_rollup_check.py`（门判据过时，见 §3-c）
  - `app/src/test/.../BackupIdRemapTest.kt` / `BackupStreamWriterTest.kt` / `ConfigBackupEnvvarsAndOverlayTest.kt`
- 停止节点：分支 CI 绿，**未合 main**

---

## 1. 缺陷与根因（已回树核实，行号为 `e6f2e4be` 树）

### 1.1 FINDING-7 — 声明带密钥，读不出就静默省略

`buildSections` 的 env vars 导出段（回树后 `ConfigBackup.kt:808` 附近）：

```kotlin
if (includeSecrets) {
    envVarRepo.getValue(entry.key)?.let { put("value", it) }
}
```

`getValue` 返回 null（W1：加密 store 已空 / 密钥不可解）时，`value` 字段**静默缺席**，但 payload 顶层照旧
`includesSecrets=true`，`envVars[i]` 里仍有 `key`/`note`。恢复后每个变量「存在但没有值」，与「用户本来就没设值」
在文档层面**不可区分**；`readFailures` 通道（同函数里已在统计注册表字段读失败）没有接纳这些读失败。

### 1.2 RT-S5 — 第二次导入把 id 打回悬空（**报告描述需修正一处**）

`remapDefaultsIds` 空 map / 未映射一律原样返回。回树核对 Stage 3 与两个消费侧后，实际伤害分两支：

| 字段族 | writer（`config/ConfigBuiltins.kt`）行为 | 真实后果 |
|---|---|---|
| `defaults.primaryGroup` / `subGroup` / `voiceInputGroup` / `voiceOutputGroup` | 校验 `modelGroups` 里有这个 id，否则 `throw InvalidValue("No group with id …")` | **并未悬空落库**：写被拒 → Stage 3 catch → 进 `skipped`。报告的「不进 skipped」在 scalar 侧不成立 |
| `defaults.agentLoopGroups` / `agentLoopEntries` | **过滤未知 id 后写回余集**（`[T-android-agentloop-dirty-data-skip]`，只留一行 Log） | 真伤害：第二次导入（更旧的包、无 `groups` 段）里每个 id 都映射不到 ⇒ 余集为空 ⇒ **本地列表被清空**，计数正常、界面正常、`skipped` 里一字未提 |

即：报告指出的「第二次导入产生悬空引用」在 scalar 侧表现为「有备份值却拒绝应用且没有可读解释」，在 list 侧表现为
**静默清空**（比悬空更重，且正是 3c 判据「计数正常、界面正常、数据已不对」的形状）。
两条的公共修法点相同：**悬空 id 不许写出去，且必须进 `skipped`**。

### 1.3 RT-S3 — 快照无原子写 + 秒级文件名 + 失败不中止导入

回树后：`ConfigBackup.kt:2105 snapshotFileName` / `:2114 writeSnapshotStreaming` / `:2139 listSnapshots`；
调用方 `ui/settings/BackupSettingsScreen.kt:316`。三个独立缺口叠在一起：

1. **直写最终文件名**（`File(dir, snapshotFileName())` 直接 `outputStream()`）⇒ ENOSPC / 进程被杀留下截断 JSON；
   名字、前缀、目录全部合法，`listSnapshots` 只按名字前后缀筛选 ⇒ 半成品成为「最新回滚点」。
2. **名字只到秒** ⇒ 同一秒两次恢复写同一路径：第二次在写入期间就截断了第一次（第一次是**唯一**回滚点，`SNAPSHOT_KEEP=1`），
   此时若第二次失败，用户手里**零个**可用回滚点。
3. **写失败不中止导入**：原注释写得很清楚「Best-effort: a failed snapshot never blocks the restore」——
   于是导入照跑，覆盖了配置，而「一键回滚」指向的正是那份半成品。
   另有一条 latent：prune 按 mtime 取最新 `SNAPSHOT_KEEP` 个，**时钟回拨**（W7）时刚写的那份 mtime 最小，会被自己删掉。

---

## 2. 修法

### 2.1 FINDING-7：per-entry 标记 + 计数进既有通道（不新增顶层字段）

新增纯函数 `buildEnvVarExportEntries(entries, includeSecrets, readValue): EnvVarExport`（`ConfigBackup.kt`，Android-free）：

- `includeSecrets=false` ⇒ 与今天一模一样（key/note/group），`missingSecretValues` 为空 —— 缺席是**被要求的形状**；
- `includeSecrets=true` + 读到值 ⇒ `value` 照常；
- `includeSecrets=true` + 读不到 ⇒ entry 上加 `_valueUnreadable: true`（`_` 前缀沿用 `_entryIds` 的「备份层注解」约定，
  import 忽略未知键），并把 key 收进 `missingSecretValues`；
- `buildSections` 里：`readFailures += missingSecretValues.size`，并 `Log.w` 点名（`summariseIds` 截断）。

**为什么是记账而不是新增 payload 字段**：任务书禁改 `buildPayloadObject` 的字段集合（payload 契约，`frameKeys` 硬编码清单、
streaming 骨架、`missingFrameKeys` 判定都在那一层），新增顶层段会牵动四处。entry 级注解 + 既有 `readFailures` 通道
达到同一判据——**「声明带了密钥」与「实际带了几个值」可以从文档本身对账**（有 `includesSecrets`、有逐项 `_valueUnreadable`、
有 `readFailures` 计数），且 import 语义零变化。

**一处语义扩边（有意）**：`readValue` 走 `runCatching { getValue(key) }.getOrNull()`。读**抛异常**与读**返回 null** 同为「读不到」。
改前一个抛异常的 credential 会让整份导出失败（比少几个值更糟，与任务书「不要给 export 加阻塞式失败」同向）。
写在报告里，避免被当成顺手改语义。

### 2.2 RT-S5：`FieldIdRemap` 结果类型（值 + 未映射 id），Stage 3 记账

```kotlin
internal class FieldIdRemap(val value: ConfigValue?, val unmapped: List<String> = emptyList())
```

- 无该 path 的映射表 ⇒ `value` 原对象、`unmapped` 空（同装置形态：这一份导入根本没有 remap 可做）；
- 有映射表但 id 映射不到（`Str`）⇒ `value = null`（**不写，保留本机现值**）+ `unmapped = [id]`；
- `Arr`：能映射的换成新 id，映射不到的**丢元素**并记账；若一个都没活下来 ⇒ `value = null`（否则写空数组 = 清空本地设置）；
- 空白 id 视作「没有选择」，原样通过、不记账（否则每次全新安装都刷一条噪音）。

Stage 3 侧：`value == null` ⇒ `skipped.add("<path>: referenced id(s) not present in this backup (… ) — local value kept")` 并 `continue`；
部分丢失 ⇒ 写余集 + `skipped` 记一条。**与会话绑定同形状但不同落点**：绑定 JSON 无映射时原样返回（它的消费端会重解析），
字段则是**整值覆盖**，所以「保留本机」只能靠不写 —— 这是两者 shape 差异，没有为抽象硬合并。

### 2.3 RT-S3：tmp+rename + 目录级唯一命名 + 失败中止

- `openSnapshotForWrite(dir)` → `<final>.json.tmp`；`commitSnapshotWrite` 只在 writer 干净关闭后 `renameTo(final)`，
  失败则删 tmp 并 `throw IOException`；
- `allocateSnapshotFile(dir, now)`：**按目录判重**（同名 `.json` 或同名 `.json.tmp` 存在就 `-2`/`-3`…）。
  **理由**：毫秒只是把窗口变窄——W7 是时钟会跳（手动改时区、NTP、RTC 陈旧），任何时间派生名都可能落到已存在的名字上；
  目录判重对时钟免疫，且保留可读的秒级词干（人可按名字排序/检索），不需要持久化状态。
  上限 1000 次命名空间耗尽则**闭合失败**（不覆盖唯一回滚点）。
- `listSnapshots` 只认 `.json`（`.json.tmp` 天然不匹配，注释与常量显式绑定，避免三处各写一遍前缀）；
- `pruneSnapshots(dir, justWritten)`：**永远保留刚写的那份**，其余按 mtime 取 `SNAPSHOT_KEEP-1` 个 —— 时钟回拨也删不掉新回滚点；
- `sweepStaleSnapshotTemps`：清扫被杀写入留下的 `.json.tmp`（`SNAPSHOT_TMP_STALE_MS = 1h`；写入是秒级，1h 有三个数量级余量，
  且时钟回拨时 `now - mtime` 为负 ⇒ 不扫，方向安全）；
- 调用方（`BackupSettingsScreen.restoreWithSnapshot`）：快照失败 ⇒ **中止导入**（`errorMessage` + `snapshotNote` + 托盘通知，
  全部复用既有文案与既有 dialog，不新增 UI），`operationBusy` 在 Main 上释放。

---

## 3. 装置与本地读数

### 3.1 JVM（真源码，Android-free 核心）

`probe-a/`（不入库）：`extract.py` 从**真** `ConfigBackup.kt` 逐字切片 24 个成员（16 个 object 成员 + 8 个顶层），
脚本内回读源码断言每段**逐字存在**；配 `ConfigValue.kt` / `BackupStreamWriter.kt` / `EnvVarRestorePlan.kt` 三个整文件，
kotlinc 编译三个**真测试类**（`BackupIdRemapTest` / `BackupStreamWriterTest` / `ConfigBackupEnvvarsAndOverlayTest`），JUnitCore 运行：

```
OK (51 tests)
```

（条目：remap 18 / 快照 16 / env+overlay 17。）

新增/改写的断言（全部期望值写死字面量，不从被测常量推导）：
- FINDING-7：标记 + 计数 + 「可对账」等式（无值条目数 − 标记数 = 报告的缺失数）；`includeSecrets=false` 必须无标记；
  另加一条**形状臂**读 `buildSections` 源码，断言 `buildEnvVarExportEntries(` / `runCatching {…getOrNull()}` / `readFailures += …` 三者同在，
  且旧的静默形态不存在；该形状臂自带三条变异负控（见 §3.2 M1b）。
- RT-S5：未映射 scalar ⇒ `null` + id 记账；全未映射数组 ⇒ 不写空数组；空白 id 不算悬空；两步导入（无 groups 段）⇒ 原值返回；
  语音组「不许走 entryMap」判别臂保留（防有人把 group 键挪到 entry 分支）。
- RT-S3：失败写 ⇒ 目录里零文件、零 tmp；**写入期间最终名不得存在**（tmp+rename 的判别臂，见 §3.2 M3b 的由来）；
  同秒两次 ⇒ 名字递增 `-2`/`-3` 且内容各自独立；时钟回拨 ⇒ 刚写的活下来；`.json.tmp` 不进列表、过期被清扫、新鲜的保留。

### 3.2 变异负控（7 组，全红在预期臂）

| # | 变异 | 落点 | 结果 |
|---|---|---|---|
| M1a | 删掉 per-entry `_valueUnreadable` 标记 | 切片 | 红 1（`unreadable secret value is marked per entry and counted`） |
| M1b | `readFailures += missingSecretValues.size` → `Unit` | **真源码** | 红 2（wiring + 形状臂自身的负控臂） |
| M2a | 未映射 scalar 恢复「原样返回」 | 切片 | 红 3（unmapped scalar / 语音组 / 两步导入） |
| M2b | 全未映射数组写成空数组 | 切片 | 红 1（silent-wipe 臂） |
| M3a | prune 恢复「按 mtime 取最新，不排除刚写的」 | 切片 | 红 1（clock rollback 臂） |
| M3b | 直写最终名（`tmp == target`） | 切片 | 红 1（**第一版没抓住**，见下） |
| M3c | 不做名称消解（永远用基础名） | 切片 | 红 1（同秒两写臂） |

**M3b 的诚实记录**：第一版装置的「中途抛 IOException ⇒ 目录里没有合法快照」在 `tmp == target` 下**仍然是绿的**——
因为失败路径会删掉 tmp（也就是那个最终文件），掩盖了非原子性。真实世界的失效是**被 kill**（catch 不跑）。
于是补了一条判别臂「写入期间最终名不得存在」（writer block 内部断言 `listSnapshots` 为空、`.json.tmp` 恰一个），
补上之后 M3b 才红。**这正是「装置通过 ≠ 判据有牙」的样本，写进报告而不是悄悄修掉。**

所有变异后都逐字节还原：`ConfigBackup.kt` sha256 前后一致（`41eba82f…`），切片文件 sha256 与 pristine 一致。

### 3.3 静态门（113 道）

```
║  Summary: 113 passed, 0 failed        （串行 MINIS_SCAN_JOBS=1；并行 --jobs 3 在本壳被回收，见 S28）
```

**过程中一道门变红，结论是「判据过时」而不是「真改坏」**：
`backup_remap_rollup_check` 的 `check_stage3_remap_consumed`（r19 #6）要求「`val <var> = remapDefaultsIds(…)`，且
`field.valueSchema.validate(<args>)` 的实参必须**文本上**派生自 `<var>`」。Stage 3 现在要 remap 结果的两半
（要写的值 + 没映射到的 id），所以形状变成 `val remap = …; val value = remap.value` —— 判据只认「一层直绑」，
把正确的接驳判成丢弃。修法不是弱化判据，而是让它跟一层**最近绑定的投影**：

- 新增 `_nearest_alias_rhs` / `_traces_to`：写点实参若是个裸标识符，解析它在**该位置之前最近一次** `val <name> = <rhs>`，
  递归（深度 2）判断是否追溯到 remap 局部；
- 新增自测臂 **w6b**：把 `val value = remap.value` 改成 `val value = decoded`（其余不动）必须变红 —— 证明别名层没把门弄瞎；
- 门体 `--selftest`：**59 arms matched（58 → 59）**，`BACKUP_REMAP_ROLLUP_SELFTEST=PASS`；真树 `OK`。

### 3.4 夹具（349 例）

`test_scan.py --jobs 4` 在本壳里会被中途回收（S28 家族：整跑 ~15 分钟的无输出进程被 stall-guard 打断两次，exit 130）。
改为**逐函数独立进程、分 4 块串行回放**（`probe-a/fixture_names.sh <start> <count>` → `--single` → 读 `@@TEST_RESULT@@` 尾部）：

```
48/48 组 PASS，349/349 例 PASS（36 + 87 + 140 + 86 = 349，与 ALL 349 CASES PASS 的基线一致）
```

### 3.5 第一轮 CI 抓到的一条：形状钉必须「带契约地」更新

run `40616286561` 在 `Run unit tests (full suite)`（7187 例中的 **1** 例）红了：

```
BackupFetchBusyGateTest > onlyTheFetchPathGainedAGateSite FAILED
  exactly one new operationBusy = false site (the fetch path's finally) expected:<10> but was:<11>
```

它钉的是**整文件** `operationBusy = false` 的字面量个数（10 = 改前基线），我加的快照中止分支合法地多出第 11 处。
**这恰好是这个钉要防的形态**——「有谁悄悄多了一个释放点」。所以修法不是把 10 改成 11：
- 计数改成 11，注释点名第 11 处是谁、为什么；
- 新增 `snapshotFailure_abortsBeforeImport`（⑦）把那一处的**契约**钉住：快照必须在 `ConfigBackup.import(` 之前发生、
  失败分支必须走既有 error dialog、必须落下 `operationBusy = false`、必须 `return@snapshotBeforeRestore`（不许掉进导入）。

本地验证（不靠 CI 反馈）：`kotlinc` 单独编译该测试类 rc=0；`JUnitCore` 跑 `OK (7 tests)`；
另用 python 复刻注释剥离 + 括号配平，逐条核对 7 个断言 + 一条负控（catch 区域不得吞掉 `ConfigBackup.import(`）全 PASS。

---

## 4. 副作用核实结论

1. **payload 字段集合零变化**：`buildPayloadObject` / `frameKeys` / streaming 骨架未改；`_valueUnreadable` 是 `envVars[]`
   条目内注解，import 只读 `key/note/group/value`（Stage 4 回树核对），未知键被忽略 ⇒ 老版本 app 读新备份、新版本读老备份都不受影响。
2. **`readFailures` 语义扩边**：现在 = 注册表字段读失败 + env 值读失败。消费方（本地导出 Toast / WebDAV 上传 Toast /
   自动备份日志）文案是「%1$d 个字段无法读取 — 此备份可能不完整」，语义仍成立（"备份不完整"）。措辞精度（字段 vs 值）
   登记为 R3，不改 7 语言 strings。
3. **`remapDefaultsIds` 契约变更**的独占性：全仓调用点只有 Stage 3 一处（`rg` 核过），测试类一处（同一提交内更新）。
   行为变化：以前「有备份值却被 writer 拒绝」，现在「不写、保留本机、并在 `skipped` 里点名」；以前 list 字段被静默清空，现在不清空。
4. **快照目录行为**：新名字只多出 `-2`/`-3` 后缀（同秒冲突时），正常路径名字与改前完全同形；`listSnapshots` 的返回集合
   在正常路径不变（`.json.tmp` 改前也不会被列出，前缀/后缀判据未变）。
5. **恢复流程**：快照失败从「照跑导入」改为「中止」。这是行为反转，风险见 R1。

---

## 5. 残留（三件套）

**R1｜快照失败 = 恢复被阻断，可能造成"死循环"**（P2，登记不修）
- 触发条件：设备空间不足（ENOSPC）或当前配置的导出超 payload 上限 ⇒ 快照永远失败 ⇒ 用户无法恢复。
- 触发后方案：加「仍要继续（不保留回滚点）」显式二次确认（需要 1 条新文案 × 7 语言 + 一个 dialog 分支）。
- 为什么本轮不做：任务书要求「不新增 UI 页面」，且中止是任务书点名的方向；oversize 场景下用户本来也导不出备份。
- 升级触发：用户报告「恢复被快照失败挡住，且清空聊天/腾空间后仍无法恢复」。

**R2｜`_valueUnreadable` 没有回读消费方**（P3）
- 触发条件：无（它是取证/对账标记，不是 UI 通道）。
- 触发后方案：恢复完成报告里统计该标记数，提示「N 个变量的值需要重新填」。
- 为什么本轮不做：属于 UI 增量；`readFailures` 已经给了同一信息的计数版。

**R3｜`backup_export_incomplete` 文案仍说"字段"**（P3）
- 触发条件：用户看到该 Toast 且想知道"到底是字段还是环境变量值"。
- 触发后方案：改成「%1$d 项无法读取」并同步 7 个 strings.xml。
- 为什么本轮不做：跨 7 语言文件改动，与三条缺陷无关，且语义不错。

**R4｜快照 `.tmp` 清扫只在本进程写快照时触发**（P3）
- 触发条件：进程被杀后再也不写快照（用户不再恢复）。
- 事后代价：一个几十 MB 的 `.tmp` 长期占空间（不进列表、不危及回滚）。
- 升级触发：设置页存储统计里出现无法解释的 `backup-snapshots` 占用。

---

## 6. 未验证边界（诚实声明）

- **未跑 Gradle**：沙箱无 Android SDK；`CompileReleaseKotlin` 与 Android 单测由分支 CI 承担。JVM 侧验证的是**被切片的核心逻辑**
  与三个真测试类（含新增臂），不是整个 module 编译。
- **`BackupSettingsScreen` 的改动（Compose/Android 面）未在本地编译**：只做了 `launch label@{ return@label }` 的语法/语义
  独立验证（写了一个最小 Kotlin 复现跑了 rc=0 + 「early → after」行为），以及字符串/作用域的人读核对。
- **真机行为未验**：ENOSPC 下的中止路径、`errorMessage` dialog 的实际观感、时钟回拨下手动改时间后的快照列表。
- **keystore 相关**：`getValue` 抛异常的形态（W1 steady state）只在代码层面对齐，没有真机复现。

---

## 7. 仓库树完整性

- 本支只改 6 个文件（见头部）；`probe-a/`、`build/`、日志全部在 `/var/minis/shared/work/`，**不入库**。
- 变异回放后真源码 `sha256` 前后一致（`41eba82f…`），切片与 pristine 一致。
- `git diff --stat`（提交前）：见 §8。

---

## 8. CI（分支 CI 绿 = 停止节点，已达成）

两轮：

| run | 结论 | 时间 | 说明 |
|---|---|---|---|
| `40616286561` | **failure** | 01:38 → 01:50（12 min） | `Run unit tests (full suite)`：7187 例中 **1** 例红 = `BackupFetchBusyGateTest.onlyTheFetchPathGainedAGateSite`（形状钉 10 → 11，见 §3.5）。修复方式 = 计数 +1 **且**新增契约测试 ⑦ 钉住第 11 处的行为（不是盲改字面量）。 |
| `40618444395` | **success** | 02:01 → 02:21（20m39s），attempt 1 | job `build` 27 步：唯一非 success = 步 23/24（滚动 tag + Publish，均 main 门控 skip）。`head_sha 94da40b4933238b18e0e52e195f08a2a058ff175` 与本地 HEAD 逐字符一致。 |

第二轮日志逐条核验（1,484,470 B / NUL 0）：

- `Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1、`[113/113]` ×1；
- Kotlin 编译错误（` e: `）**0**；含 `FAILED` 的行 6 条，全部是 PASSED 用例名的子串（判读陷阱第 N 次，逐条确认）；
- `BUILD SUCCESSFUL` 在册；
- 新测试类在真 SDK 环境全部 PASSED：`BackupIdRemapTest` **18**、`BackupStreamWriterTest` **16**、`ConfigBackupEnvvarsAndOverlayTest` **17**（= 本地 JVM 51 例同数）、`BackupFetchBusyGateTest` **7**（含新增 ⑦）。

报告回填后树完整性（绿 sha `94da40b4` 之后只追加报告提交）：

```
git diff --name-only 94da40b4..HEAD -- . ':(exclude)fix-reports'   → （收口行填：0）
```

**停止节点达成。未合 main。**
