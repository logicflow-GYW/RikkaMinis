# FIX-R5-A1：镜像写入完整性 7 项（R5 修后复扫批）

- 分支：`fix/r5-a1-mirror-integrity`
- 基线：main `f3def6ce`
- 提交：`c6940ed7`（七项修复）+ `a7af0cbb`（编译修复，见 §5）
- CI：run **40854953415 success** @ `a7af0cbbb720b4fa24bad0e543cbf4e8349d05bd`（与本地 tip 逐字符一致）
- 来源报告：`reports/R5-A.md`（C-01/C-02/C-03/C-05 + F-01/F-02/F-03/F-05）
- 改动面：生产 2 文件 +211/−25（`MirrorStore.kt` +88/−17、`MirrorPlan.kt` +123/−8）；测试 3 文件 +425/−0
- 未触碰：`scripts/scan/**`、`scan.sh`、`test_scan.py`（门 113 / 夹具 349 与基线一致）、`MirrorScope`/`ArtifactBackupScope`/`StagedBytesLedger`/`ShareReceiverActivity`/`SharedShareStore`/`MirrorTarget`/`MirrorSettingsScreen`

## 1. 判定表

| ID | 先证结论 | 修法 | 证据 |
|---|---|---|---|
| A-F-01 | 成立（失败半边）。`"wt"` 在 open 即截断，写异常/流 null 后 0 字节或半截文档以可见名留在 Downloads，且点间距窗内无 `previous/` 可回退 | 失败即删条目 → 下次同步走 IS_PENDING 插入路径重建。**不做** delete+insert 前置（同名插入会被 MediaStore 改名 `foo (1).md`，每次覆写都分叉镜像） | 形状断言 `aFailedOverwriteDropsTheTruncatedRow`（两种失败形态都必须到 `dropHalfWritten` + `resolver.delete`），旧树该断言红 |
| A-F-02 | 成立。源读失败 `?: continue` 后仍把全量 `currentEntries` 写 manifest ⇒ 该文件在镜像里永久缺失，`pending` 读 0、状态行健康 | 写循环收集 `skipped`，`MirrorPlan.manifestEntries` 把被跳过条目回退为旧记录（旧 manifest 无则剔除 → 下次以 `added` 重试） | `manifestEntries_*` 4 例（纯函数，期望值全字面量）+ 形状断言 `unreadableSourceFilesAreNotRecordedAsSynced`（禁止 `Manifest(now, currentEntries,`） |
| A-F-03① | 成立。`classifyHealth` 无回拨分支：`idle` 为负 ⇒ 陈旧判据永不触发，死掉的触发链恒读 Healthy | 补 `nowMs < lastSyncMs` → `pending>0` 即 `Stale(pending, 0)`；年龄未知报 0 分钟（载荷是告警不是数字） | `health_clockRollbackWithPendingIsStale`（报告判决原样输入）、`..._nothingPendingStaysHealthy`、`..._unreadablePendingStaysHealthy` |
| A-F-03② | 成立。点名单秒级精度会与既有同名，`filterNot { it in victims }` 删掉所有同名元素 ⇒ keep=3 只留 2 | 拼点前 `.distinct()`；并把两处手写算术（`MirrorStore.runLocked` 与 `MirrorPlan.nextManifest`）收敛为单一纯函数 `rotatePoints` | `rotatePoints_duplicateNameDoesNotShortenTheKeptList`（报告判决原样输入）、`..._deletesExactlyThePointsBeyondKeep`、`..._keepZeroListsNothingAndDeletesOnlyStampedNames`、`nextManifest_duplicatePointNameKeepsTheFullKeep` |
| A-F-05 | 成立。`drop(KEEP_AUTO)` 把回拨后刚写的副本当最旧第一个删，日志仍报成功 | 新增 `MirrorPlan.autoSnapshotVictims`：刚写副本独占一槽（保留 `keep-1` 个其他）⇒ 配额精确，而不是 `protect` 后的 keep+1（那会每回拨一天多攒一个文件） | `autoSnapshotVictims_rolledBackNameIsNotAVictim` 等 5 例（含入参顺序无关、keep=1、欠额不删） |
| A-C-01④ | 成立。Failed 后 60s 内 Throttled/Busy 覆盖 `KEY_RESULT` ⇒ 告警与重试按钮一起消失 | `record()` 改走 `MirrorPlan.nextRecordedOutcome`：**未尝试任何工作**的结果不覆盖失败 | `nextRecordedOutcome_throttledAndBusyDoNotClearAFailure`、形状断言 `aRecordedFailureSurvivesAnAttemptFreeOutcome`（禁止 `putString(KEY_RESULT, text)`） |
| A-C-02①② | = F-03 的两个面（同文件），随 F-03 一并闭 | — | 同上 |
| A-C-03② | 成立。重试撞 Busy 写 `"busy"` ⇒ classifyHealth 判 Healthy（假恢复） | 同 C-01④（busy 归属「未尝试」），不在 classifyHealth 上另做特判：`record` 不来污染，`busy` 出现在 prefs 就必然不是覆盖失败得来 | 同上测试对 + `health_*` 三例 |
| **A-C-05②** | **证伪**。`selectLocalRotationVictims` 先 `drop(keep)` 再过滤**不会**造成超保留：生产唯一入参（`listLocal`）是「有戳在前、无戳在后」的新→旧序，无戳项本就落在窗口外；把 filter 提前只会**保留更多** | **不改**（登记，见 §4） | `hoisting the eligibility filter never deletes a file the shipped order keeps`（5×4×保护位=80 组矩阵，断言「提前过滤的受害者 ⊆ 现状受害者」）+ `retention above keep comes from the protection, not the filter order`（keep=2 且保护位最旧时，两种顺序都留 3 = keep+1，超保留来自 R3-D 的保护，不来自顺序） |

## 2. 逐条细节

### 2.1 A-F-01 半截条目回收（`MediaStoreTarget.write`）

先证：`resolver.openOutputStream(existing, "wt")` 在 open 时截断，`write` 抛 IOException 被外层 `runCatching` 变成 `false`，条目留在原地（0 字节/半截）；`runLocked` 第 3 步的「10 分钟点间距窗内不归档旧字节」是刻意设计（backlog §META-1029SILENT R2），所以这里没有任何副本可回退。

修法：新增 `overwrite()` / `dropHalfWritten()`：
- 两种失败形态（写抛异常、流为 null）都走 `dropHalfWritten` → `resolver.delete(existing)`；
- 只删不重建：下次同步的 diff 仍把该文件判为 changed（进入 `toWrite` 的前提就是「manifest 与源不一致」），走 IS_PENDING 插入路径原子重建；
- 不改成 delete+insert 前置：同名插入会被 MediaStore 改名 `foo (1).md`（类注释里的实测行为），覆写一次分叉一次。

### 2.2 A-F-02 读失败不得记成「已同步」

先证：`val bytes = runCatching { src.readBytes() }.getOrNull() ?: continue` → 该文件这轮没写进 `current/`，但第 5 步 `Manifest(now, currentEntries, kept)` 用的是 step 1 的走查结果，其中就有它的新 `(size, mtime)` ⇒ 下一轮 diff 判 `unchanged`，永不重写；`pendingCount` 读 manifest ⇒ 0；状态行 Healthy。这正是「计数正常、界面正常、数据已不对」那一族。

修法：`skipped: LinkedHashSet<String>`；`MirrorPlan.manifestEntries(current, previous, skipped)`：被跳过 → 用旧 manifest 的同名条目（旧无 → 剔除，下次以 `added` 回来）。附带 `Log.w` 点名前 3 个。

### 2.3 A-F-03 回拨不盲 + 点名去重

先证①：`idle = nowMs - lastSyncMs` 为负时 `idle >= staleAfterMs` 恒假 ⇒ `Healthy`。回拨是 `shouldWrite`/`shouldMintPoint` 都已按「未知不算足够近」处理过的前提，唯独状态行漏了。
先证②：`existingPoints + point` 不去重；`filterNot { it in victims }` 删所有同名元素 ⇒ 报告判决输入（keep=3 + 新名撞最旧名）只留 2 个；且 `deleteTree` 会删掉 manifest 仍列着的点。

修法：`rotatePoints(existing, newPoint, keep)` 单一实现（`distinct` → `previousVictims` → `filterNot` → `sortedDescending`），`MirrorStore` 用它取 `victims`/`kept`，`nextManifest` 委托它。此前同一套算术有两份手抄（一份在 I/O 壳、一份在纯层），正是「修一半丢一半」的温床。

### 2.4 A-F-05 刚写的日备份独占一槽

先证：`all.drop(KEEP_AUTO)`，`all` 按名字戳降序；回拨时刚写的副本戳最旧 ⇒ 排最后 ⇒ 被第一个删，而 `Log.i("mirrored ...")` 照旧。
修法：`MirrorPlan.autoSnapshotVictims(snapshots, freshName, keep)` —— `others = 排除刚写` → 降序 → `drop(keep-1)`。配额恒等（保留 `keep-1` 个其他 + 刚写 = `keep`），而不是 `RotationVictims(protect=…)` 的 keep+1（回拨持续时每天多攒一个）。

### 2.5 A-C-01④ / A-C-03② 失败不被「没干活」的结果洗掉

先证：`record()` 无条件 `putString(KEY_RESULT, text)`；`lastSyncMs` 只在 ok 时更新 ⇒ Failed 之后一次 Throttled（合流窗内）或 Busy（单飞锁被占）就把唯一失败通道改写成健康，而 `classifyHealth` 只认 `failed:`/`unavailable` 前缀 ⇒ 告警与重试按钮一起消失。重试撞 Busy 的路径就是用户最容易看到的那个形态（假恢复）。

修法：`MirrorPlan.nextRecordedOutcome(previous, next)`：
- 上一条是 `failed:`/`unavailable` 且本次是 `throttled`/`busy` → **保留**旧值；
- 本次是 `ok …` 或 `no-changes` → 接受（都代表完成了一轮：写了字节，或对着可读目标跑完了读+diff）；
- 其余（含新的失败）→ 接受。

**偏离报告**：报告写「Throttled/NoChanges 都不覆盖 Failed」，本支让 `no-changes` 覆盖。理由：`no-changes` 需要目标可读 + manifest 可解码 + 走查完成 + diff 为空，是「管线确实通了且有活干完」的证据；不让它清，则一次瞬时失败会在一个安静安装上留下永不消失的告警（再也等不到 `ok`）。而「Failed → NoChanges」在真码里基本不可达（失败必然留下未写条目 ⇒ 下一轮 diff 非空），所以这个放行不会掩盖残留故障。

## 3. 报告证据 vs 真码（差异表）

| 报告说法 | 真码读数 | 处理 |
|---|---|---|
| A-C-05② 先 filter 再 drop 能减少超保留 | 生产入参新→旧且无戳在后 ⇒ 两种顺序结果等价；提前过滤只能保留更多 | 证伪，不改，测试钉住（§1 末行） |
| F-05 可委托 `BackupRotation.rotationVictims(protect=…)` | 委托后配额变 keep+1，回拨持续时每月多攒文件 | 自写 `autoSnapshotVictims`（配额精确），保留 `rotationVictims` 供本地轮转用 |
| F-03 只需两处各加一次 `.distinct()` | 同一算术被手抄两份，只改一处下次必复发（本仓同族复发 ≥2 次的历史） | 收敛为 `rotatePoints` 单一实现 |
| C-01④「Throttled/NoChanges 都不覆盖」 | `no-changes` 是完成态证据；禁它清会让告警永远挂着 | 只挡 `throttled`/`busy`（§2.5） |
| F-01「写失败不清条目」，行号 241-245 | 真码机制一致（`wt` 在 open 截断），行号已漂到 236-238 | 按语义修 |

## 4. 验证

**本地（沙箱 JVM，`kotlinc -no-stdlib`）**：
- `MirrorPlanTest` 50 例（新增 24）+ `SelMatrixTest` 2 例 = **52/52 绿**（`a1-harness/run.sh`）
- **红臂（判据有牙）**：`gen_pre.py` 生成「pre 版 MirrorPlan + 旧逻辑桩」（`nextRecordedOutcome` 恒取新值、`rotatePoints` 不去重、`autoSnapshotVictims` 无保护位、`manifestEntries` 恒返回全量、`classifyHealth` 旧体）⇒ **11 例转红**，恰为新增判据里能区分的那些；`autoSnapshotVictims_underQuotaDeletesNothing` / `..._freshNewestAlsoKeepsSeven` / `manifestEntries_noSkipsIsTheCurrentSelection` 在旧码上本就正确 → 保持绿（不是每条都该是变异检测器）。
- **形状断言**（`MediaStoreTarget`/`runLocked` 依赖 ContentResolver+Context，无 JVM 装置）：`SilentMirrorWiringTest` 9/9 绿（原 6 + 新 3）；把 `MirrorStore.kt` 换成 `f3def6ce` 版本重跑 ⇒ **恰新 3 条红**，原 6 条仍绿（说明新代码没破坏已钉的契约）。
- 单门：`backup_streaming_export_check.py .` → `arm1..arm11 green`；`--selftest` → 30 组夹具全 PASS + `BACKUP_STREAMING_EXPORT_SELFTEST=PASS`。

**云端（run 40854953415 @ `a7af0cbbb720`，success，15.4 min）**：
- 23 步中唯一非 success = 2 skipped（rolling tag / Publish，main 门控）；`Run pre-build scan gate`、`Run unit tests (full suite)`、`Compile instrumented tests`、`Build APK` 全 success
- 日志 1,629,777 B / NUL **0** / `^ e: ` **0** / 含 `FAILED` 的 6 行逐条确认全是 **PASSED 用例名子串**（`Persistence_FAILED`、`phase FAILED maps to Failed` 等）
- `Summary: 113 passed, 0 failed`（门）+ 分片 16/18/19/25/30/44 各 0 failed + `ALL 349 CASES PASS` + `[113/113]`
- 新用例在日志里逐个可见（10 个代表名各 1 次，含两条 C-05② 证伪测试与 3 条形状断言）

## 5. 失败链记录（避免后人重踩）

run 40853219557（commit `c6940ed7`）在 `:app:compileReleaseKotlin` 失败：

```
e: MirrorStore.kt:481:77 The feature "break continue in inline lambdas" is only available since language version 2.2
```

我在 `?: run { skipped += e.relPath; continue }` 里用了 2.2 才允许的「inline lambda 内 continue」。**本地 `kotlinc` 默认语言版本 ≥2.2，放过了它** —— 这是本支唯一一处本地绿/云端红。已改为 `val src = …?.file; val bytes = src?.let { … }` + 显式空检查（`a7af0cbb`）。教训：本仓语言版本低于 2.2，本地纯 JVM 装置跑不出这类差异，涉及 `run{}`/`let{}` 控制流时不要用 `continue`/`break`。

## 6. 残留（登记，未修）

| # | 内容 | 为什么现在不修 | 触发条件 |
|---|---|---|---|
| R1 | `previous/<stamp>/` 撞名时是同目录混写（旧点字节被新点覆写）；回拨铸出的旧名点会在**同一轮**被轮转删掉 | 报告只判「keep 少一个」（已闭环）；改名能力要引入碰撞后缀（`BackupRotation` 有 `-N` 后缀机制，`previous/` 没有），属于新装置 | 有人给 `previous/` 点引入碰撞后缀时一并做；或出现「回滚点内容混了两轮」的真机报告 |
| R2 | 归档读失败（`target.read(currentPath(rel)) ?: continue`）后仍覆写 `current/`，旧字节既没进 `previous/` 也被覆盖 = 镜像失去自保 | 与 F-02 同一循环但形态不同（这是「备份没做成还继续写」），改动会让该文件这轮不同步（镜像滞后），需要单独权衡 | 真机出现「回滚点里没有某文件，但 current 已被新内容覆盖」的报告 |
| R3 | `MirrorPlan.nextManifest` 仍无生产调用点（逻辑已由 `rotatePoints` 收敛，不再手抄） | 让它被 `runLocked` 直接调用要连带返回 `victims`，改 `runLocked` 结构风险大于收益；现在两者共用同一纯函数，漂移面已消除 | 下次给 manifest 结构加字段时顺手把 `runLocked` 切到 `nextManifest` |
| R4 | 回拨态下 `Stale` 的分钟数报 0（「0 分钟未写入」） | 墙钟无法界定真实年龄；新增 `Health` 变体要 7 份 i18n，且核心载荷是告警+重试 | 有人反馈这句话误导；或 i18n 批次顺手 |
| R5 | 全支证据止于 CI 编译级 + JVM 级 + 源码形状级：`MediaStoreTarget`（真 MediaStore 改名/截断/删除语义）、`record()` 的 prefs 时序、状态行渲染都未真机验证 | 任务书禁跑 gradle/真机；属并集树/真机验范围 | 并集树合并后真机走一遍：改一个文件 → 断网/占满空间触发写失败 → 看下载目录无半截文件、下次同步自愈 |
| R6 | `selectLocalRotationVictims` 的保护位仍允许 keep+1（本地副本数最多 4 而 `AUTO_BACKUP_KEEP=3`） | 这正是 R3-D F-01 的设计（刚写的副本必须活），且下一轮自纠；改成「保护位挤掉一个历史副本」是产品取舍不是缺陷 | 用户反馈本地历史变短 |
| R7 | C-01② 两阶段写：进程被杀会留下孤儿 `previous/<point>/` 与半写 `current/`（无事务边界） | 报告未分配给本支；要真修需 journal/两阶段提交，装置比例远超 P1 | 真机出现「回滚点目录存在但 manifest 不列」的实际损坏 |

## 7. 复现 / 负控

```sh
# 绿臂（52 例）
sh /var/minis/shared/work/r5-1029/a1-harness/run.sh

# 红臂：pre 版 MirrorPlan + 旧逻辑桩（11 例转红）
python3 /var/minis/shared/work/r5-1029/a1-harness/gen_pre.py
sh /var/minis/shared/work/r5-1029/a1-harness/run_pre.sh

# 形状断言（9/9），旧树对照：把 MirrorStore.kt 换成 f3def6ce 版本后恰 3 红
cd /var/minis/shared/work/r5-1029/a1-harness/pre-tree && java -cp … org.junit.runner.JUnitCore com.rikkaminis.app.backup.SilentMirrorWiringTest
```

`a1-harness/` 是脚手架（不进仓库）：`run.sh` / `run_pre.sh` / `gen_pre.py` / `gen_sel.py` / `SelHarness.kt`（`AutoBackupManager.selectLocalRotationVictims` 的逐字切片，参数表+表达式体两步定位——首版在默认参数 lambda `{ true }` 处截断，第二版在表达式体尾行少取一行，两次都被「切片不编译/少最后一行」当场抓到）。
