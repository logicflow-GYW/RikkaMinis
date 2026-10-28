# FIX-P1B-BACKUP-WRITE-READ — 自动备份截断件冒充最新备份 + JSONL 缺片静默截断（FINDING-14 / RT-S1）

**结论一句话**：两个「读取端把不完整的东西当成完整的」各自在共享层修掉——自动备份改成 `temp + rename` 原子流式写（中途失败只留 `.json.tmp`，`listLocal` / 轮转 / 设置页三处共用一条匹配规则，失败的一次写既不会被列为备份、也占不到滚动槽位），JSONL 分片读取改成「缺片不是序列终点」（中间空洞跳过继续读、末尾连续 3 个空号判正常结束、真实缺片数写进既有 `chatTruncated` 段）。

**分支**：`fix/p1-backup-io`（基线 main `e6f2e4be`）· **绿 sha**：`efea8ea1`（CI run `40616455267` / #2801 **success**）
**改动面**：6 文件（3 生产改动 + 1 新增生产 + 2 测试）+ 1 个门重锚；**未触碰** `ConfigBackup.kt` / `scripts/scan/scan.sh` / `test_scan.py` / `_kotlin_*`。

| 文件 | 变更 | 作用 |
|---|---|---|
| `backup/AutoBackupLocalStore.kt` | 新增 137 行（纯 JVM） | 命名规则 + 原子流式写 + 临时文件回收（决策与 I/O 分离，离线可编真源码） |
| `backup/AutoBackupManager.kt` | +23/−4 | `listLocal` 走统一匹配规则；`runLocked` 走原子写 + 清陈旧 `.tmp` |
| `backup/OpenMinisBackupCompat.kt` | +112/−14 | `readJsonlShards` 返回「记录 + 真实缺片」，`convert` 把缺片写进 `chatTruncated` |
| `ui/settings/BackupSettingsScreen.kt` | +11/−4 | 同目录第二个写点（fetch-to-local）改走既有 `tools.AtomicFileWrite` |
| `scripts/scan/backup_streaming_export_check.py` | +68 | arm8 判据重锚（原子写是第二种合法 sink 拼写）+ 两套新夹具 |
| `test/.../AutoBackupLocalStoreTest.kt` | 新增 10 例 | FINDING-14 全部断言（含「失败不能占滚动槽位」） |
| `test/.../OpenMinisBackupCompatTest.kt` | +5 例（19 → 24） | RT-S1：中间缺片 / 首片缺 / 正常包无标记 / 流间不串味 / 关聊天时的规则缺片 |

---

## 1. 缺陷与根因（回树核实，行号为当前树）

### FINDING-14：自动备份直接写最终文件名

| 环节 | 事实 |
|---|---|
| 写 | `AutoBackupManager.runLocked` 原为 `local.bufferedWriter().use { ConfigBackup.exportToWriter(…) }`，`local` 就是最终名字 `<prefix><stamp>.json` |
| 失败形态 | 中途 `ENOSPC` / 进程被杀（SIGKILL 抓不住，`catch` 不跑）⇒ 磁盘上留下**截断 JSON**，名字、前缀、目录全部合法 |
| 读取端 | `listLocal()` 的匹配规则是 `startsWith(LOCAL_FILE_PREFIX)` 一条，`.json.tmp` 也命中 |
| 后果链 | ①设置页把它显示成最新备份 ②`rotateLocalAutoBackups` 把它算作 `AUTO_BACKUP_KEEP=3` 里的一份，**挤掉一份真正完好的老备份** ③用户点恢复才见 `Malformed JSON` |
| 第二个写点 | `BackupSettingsScreen:1212` 的 fetch-to-local 同样是 `local.writeText(json)`，同一目录、同一前缀、同样无标记 |

### RT-S1：JSONL 分片中间缺一片，后面全丢且无标记

| 环节 | 事实 |
|---|---|
| 读 | `readJsonlShards`：`readMember(work, keys, name) ?: break` —— 第一处读不到就被当成「序列结束」 |
| 世界 W3 | 分片序列 `data/messages.jsonl` / `-0002` / `-0003` 中 `-0002` 不在包里 ⇒ `-0003` 及其后**全部丢弃** |
| 后果 | 消息静默变少（4 条 → 2 条），导入侧仍报「成功」；导入链路本来有 `chatTruncated` 通道（`ConfigBackup:1806` 读 `sessionsDropped`/`messagesDropped` 并写进 `ImportResult.skipped`），转换层从未写过它 |

---

## 2. 修法

### 2.1 FINDING-14：`temp + rename`（选首选方案）

`AutoBackupLocalStore`（新文件，纯 `java.io`）：

- `fileName(prefix, stamp)` = `<prefix><stamp>.json`；`isBackupName(name, prefix)` = `startsWith(prefix) && endsWith(".json")`。**写入端与读取端共用这一对**，测试断言两者互相认同（`fileName` 的产物必须被 `isBackupName` 接受、被 `isTempName` 拒绝）。
- `writeAtomically(target) { writer -> … }`：写同级 `<name>.json.tmp` → `body` → `flush` → `fd.sync()` → `renameTo` → 成功才出现最终名。`body` 抛异常 / rename 失败 ⇒ 删 tmp、**原样重抛**。`inline`（不带 suspend 签名）让生产侧的 suspend 导出体可以直接传进来，本对象对协程零认知。
- `deleteStaleTemps(dir, prefix)`：被 SIGKILL 打断的写不会走到清理分支，留下的 `.tmp` 对读取端是隐形的（回收不掉）⇒ `runLocked` 每次开头顺手清一次，避免「每被杀一次就永久漏一个文档」。
- `AutoBackupManager.listLocal` 改走 `isBackupName`；轮转与设置页列表都经由 `listLocal` ⇒ **一处判定，三处消费**（任务书要求「避免两套判定」）。

**为什么不用既有的 `tools/AtomicFileWrite`**：它把整份内容作为 `String` 传入，而自动备份是流式导出（tens of MB，`[fix/da1023-backup-streaming-fetch]` 专门为消除「先拼整份 String」的 OOM 形态而改）。同一惯用法（同级 temp + rename + 失败清理），换成流式签名。fetch-to-local 那个写点内容本来就是内存 String ⇒ 直接用 `AtomicFileWrite`，不造第二套。

**为什么 rename 失败不回退成「直接写最终名」**：那正是本条要消灭的形态。宁可整次备份失败（`runLocked` 抛 ⇒ `KEY_LAST_RUN` 不落 ⇒ 下一次前台自动重试），也不要一个看起来成功、恢复时才发现读不出来的文件。

**备选方案（`.ok` 旁标 + 写完 parse 校验）被否**：多一个必须与主文件同步的状态文件，且老备份没有 `.ok` 会被误判成坏的——修缺陷不能顺手把历史数据判成无效。

### 2.2 RT-S1：缺片不是序列终点

- `readJsonlShards` 返回 `JsonlShardRead(records, missingShards)`。
- 循环遇空**不 break**，记进 `pendingHoles` 继续找下一片；**命中后续片时**才把 `pendingHoles` 兑现成真正的缺片。
- 终点判据：连续 `SHARD_GAP_TOLERANCE = 3` 个序号都读不到 ⇒ 判序列结束，**末尾这串不算缺片**（否则每个正常包都会被报成「缺 N 片」）。
- 取 3 的理由写在常量 KDoc：必须 > 1（否则「缺一片」又变终点，退回原缺陷）；正常包代价 = 每流末尾多 2 次 `File.isFile`（3 个流 = 6 次 stat，可忽略）；连续 3 片都缺已是包被截断，继续往后找只会让损坏的包「看起来更完整」。
- 报告：`convert` 收集三个流（`data/messages` / `data/sessions` / `data/thinking_rules`）的缺片成员名，非空时写 `doc.chatTruncated = { shardsMissing, missingShards[≤32], sessionsShardsMissing, messagesShardsMissing, thinkingRulesShardsMissing }`。**正常包 ⇒ 完全不出这个键**（文档形状与升级前逐键一致）。

---

## 3. 装置与本地证据

**装置比例（P1）**：不建新门、不建新 harness 目录。两条都收进既有测试文件；FINDING-14 的新增逻辑放纯 `java.io` 文件，因此**沙箱里能编真源码 + 跑真测试文件**（不是复刻件）。

```
kotlinc AutoBackupLocalStore.kt OpenMinisBackupCompat.kt \
        AutoBackupLocalStoreTest.kt OpenMinisBackupCompatTest.kt   → rc=0
JUnitCore AutoBackupLocalStoreTest        → OK (10 tests)
JUnitCore OpenMinisBackupCompatTest       → OK (24 tests)   # 19 旧 + 5 新
两者同跑（变异脚本 baseline）              → OK (34 tests)
```

### 变异负控（5 组，全部红在预期臂）

| 变异 | 退化 | 结果 |
|---|---|---|
| M1 | `readJsonlShards` 恢复 `?: break`（缺片=终点） | 4 红：中间缺片 / 首片缺 / 流间不串味 / 关聊天时的规则缺片 |
| M2 | 尾巴连续空号也记成缺片 | 5 红（含 `a complete package reports no shard loss`） |
| M3 | `isBackupName` 退回 `startsWith` 单条 | 2 红：`.json.tmp` 被当成备份 / 其他前缀文件被当成备份 |
| M4 | `tmp = target`（写最终名，但保留清理） | 1 红：失败写毁掉上一份好备份 |
| **M4b** | **整段换回修复前写法 `target.bufferedWriter().use { … }`（无 temp、无清理）** | **3 红**：失败后留下截断件本身 / 上一份好备份被毁 / 失败写占掉滚动槽位 |

M4 只有 1 红暴露了一个事实：`tmp = target` 时 catch 里的 `tmp.delete()` 顺手删掉了截断件，所以「失败后不留文件」那条臂反而绿——**真正的判据是 M4b 这种忠实复刻**（无清理），它 3 红。这条记在 §7 作为装置教训。

还原验证：每组变异后 `sha256sum -c` 对两个生产文件逐字节一致，再复跑得 `OK (34 tests)`。

## 4. 本地门

**静态门（113 道，最终树 `efea8ea1`）**：

```
$ bash scripts/scan/scan.sh --jobs 3     # 第一次（未重锚前）
╔══════════════════════════════════════════════════╗
║  Summary: 112 passed, 1 failed
╚══════════════════════════════════════════════════╝
FAIL: arm8: runLocked has no File bound to the export writer — re-anchor this
criterion, the uploaded file cannot be identified   [96/113 I/ISSUE-10]
```

这是**装置正确报警**而不是缺陷：`backup_streaming_export_check` 的 arm8 把「导出流进去的 File」锚在 `file.bufferedWriter()` / `FileOutputStream(file)` 这一种**拼写**上；原子写把 `FileOutputStream` 移进了 `AutoBackupLocalStore`，同一个 `local` 现在以 `writeAtomically(local)` 的形式出现在调用点。按门自己的提示（"re-anchor this criterion"）重锚：

- `_file_sinks` 增加第二种合法拼写：`AutoBackupLocalStore.writeAtomically(<name>)`，且仍要求 `<name>` 在同一函数体里是 `File` 绑定 —— 判据的实质（**上传的 File 必须是文档流进去的那个 File**）没变。
- 新增两套夹具：`compliant-auto-atomic-sink`（新拼写合法）+ `violation-auto-push-other-atomic`（原子写 + 上传另一个 File ⇒ 必须红）。`--selftest` 全 35 条 PASS（`BACKUP_STREAMING_EXPORT_SELFTEST=PASS`），含这两条：新拼写被接受、且**没有钝化**（换文件仍然被抓成 `uploads ` 并点名两种 sink）。

重锚后（最终树）：

```
$ bash scripts/scan/scan.sh --jobs 3
╔══════════════════════════════════════════════════╗
║  Summary: 113 passed, 0 failed
╚══════════════════════════════════════════════════╝
```

**夹具（48 组 / 349 例，跑在 `abf1dd32` 树）**：

```
test_da1025_gates           8 0     test_auto_approve_gates      2 0
test_w1027_storage_budget   2 0     test_primitive_selftests     3 0
test_da1026_gates           2 0     test_da1026_arch_gates       4 0
test_trace_step_tag         4 0     test_dv16_chat_ui_interaction 7 0
test_da1017_external_fixtures 4 0   test_real_repo              20 0
（前 38 组）                 293 0
合计 349 例，0 失败
```

`test_real_repo`（20 例）在最终树 `efea8ea1` 复跑：`@@TEST_RESULT@@ test_real_repo 20 0`。最终树相对夹具树的增量只是逐字恢复的两个原函数（见 §5.0），不触任何夹具面。

沙箱事实（第 N 次）：一次跑完 48 组会在中途被整壳打断（本次 38/48 处断）。逐组独立进程（`--single`）续跑剩余 10 组，逐组 `@@TEST_RESULT@@ <组名> <用例数> <失败数>` 全部 `0` 失败；用例数 293 + 56 = **349**，与基线一致。

## 5. CI

### 5.0 第一次 run 失败：本支自伤（已修，记录在案）

| run | sha | 结论 | 失败点 |
|---|---|---|---|
| #2799（`40615400193`） | `abf1dd32` | **failure** | 步骤 15 `Run unit tests (full suite)` → `:app:compileReleaseKotlin FAILED`：`AutoBackupManager.kt:96 Unresolved reference 'isEnabled'`（设置页 5 处未解析引用是它的下游） |

原因：把纯逻辑抽到 `AutoBackupLocalStore` 时，替换区间的**起点落早了一屏**，连带删掉了夹在中间的 `isEnabled` / `setEnabled` 两个公共函数。修复 = 按父提交逐字恢复（`efea8ea1`），`AutoBackupManager` 相对父提交的差异收敛回三处（listLocal 谓词 / 清陈旧 `.tmp` / 原子写）。

**可复用手艺**：`AutoBackupManager` 依赖 Android 运行时（`Context` / `Log` / `MinisApp`），本地 JVM 测试编不到它 —— 所以「抽函数」这类搬动**不能靠测试兜底**，替换前必须整段读完被替换的区间。本次补的兜底装置（未入库，probe 材料）：一套 6 个小桩（`Context`/`Log`/`MinisApp`/`ConfigBackup`/`WebDavSync`/`WebDavConfigStore`/`ScheduledTasksStore`）+ 三个生产文件一起过 `kotlinc` ⇒ `rc=0`，这类错误从此在本地就能抓住。

### 5.1 最终 run：#2801（id `40616455267`）success

```
run        2801
head_sha   efea8ea1907ef60fa3426e6c0589380534f9df28   ← 与本地 HEAD 逐字符一致
conclusion success completed（01:40:36Z → 01:57:40Z，17.1 min）
job        122579971351 success
steps      0 个非 success；唯一 skipped = 滚动 tag + Publish（均 main 门控）
日志        1,484,887 B / NUL 0
  Summary: 113 passed, 0 failed      ×1
  ALL 349 CASES PASS                 ×1
  ` e: ` 行                          ×0
  含 FAILED 的行 6 条，逐条确认全是 PASSED 用例名（判读陷阱）
  AutoBackupLocalStoreTest 10 例     全 PASSED
  OpenMinisBackupCompatTest 新 5 例  全 PASSED（missing middle message shard… PASSED）
```

报告回填后核对：`git diff --name-only efea8ea1..HEAD -- . ':(exclude)fix-reports'` = **0**（绿 sha 之后只有报告提交）。

## 6. 残留三件套

### R1（本支最硬的一条欠账）`chatTruncated` 的新键暂无读取方

缺片数、缺片名字确实写进了转换文档的 `chatTruncated` 段，但**当前没有任何代码读它** —— `ConfigBackup.import` 只读 `sessionsDropped` / `messagesDropped`。也就是说：**消息这次不会再被静默丢掉了（后面的片真的读回来了），但「这个包缺了一片」这句话还没到用户眼前。**

- 触发条件：下一次有人动 `ConfigBackup.kt`（P1-A 合并后顺路，或用户报告「导入后消息比源设备少」）。
- 触发后方案：在 `ConfigBackup.import` 的 `root.optJSONObject("chatTruncated")?.let { tr -> … }` 块里加 6 行（本支禁区，逐字给在 §8.1）。
- 搁置理由：任务书明令「本支不得修改 `ConfigBackup.kt`，需要改就停下来在报告里说明」——P1-A 正在同一文件的其他段落施工。键已经就位，补丁是纯加法、对现有读取方无影响（`optInt(k,0)`）；放在禁区外做就是这一条。

### R2 整次自动备份失败，用户侧不可见

`runAsync` 的 `catch (t: Throwable) { Log.w(TAG, "auto backup failed: …") }` 是既有形态（本支未改）：磁盘满 / rename 失败会让这一次备份整体失败，`KEY_LAST_RUN` 不落 ⇒ **次日前台自动重试**（自愈），但设置页不会告诉用户「昨天那次没成」。

- 触发条件：用户报告「为什么本地/远端没有昨天的备份」；或 Settings 的自动备份区块下一次改版。
- 触发后方案：把「上次失败原因」写进 `backup_prefs`（与本支同名 prefs，1 个 key），设置页沿用既有的 `lastRunLabel` 一行显示；不新增页面。
- 搁置理由：属 UI 可观测性，任务书要求「不加 UI」；且失败的下一次重试已自愈，实际危害是「延迟一天」而不是「静默丢数据」（截断件那条已经由本支消灭）。

### R3 尾部被截断的包仍然没有标记

本支能报告的是**洞**（后面还有片被读到）。若包是在中间被截断的（只剩前 3 片，后面的片根本不在包里），序列末尾那串连续空号会被判成「正常结束」⇒ 不报缺片。因为**包里没有分片总数可对账**：`manifest.json` 的字段集（`created_at/snapshot_at/app/device_name/backup_id/categories/integrity`）不含 counts，`files.index.jsonl` 只索引 blob 文件。

- 触发条件：上游给 `manifest.json` 加分片清单/总数；或真机出现「导入成功但聊天明显少一截，且没有任何提示」的报告。
- 触发后方案：有总数 ⇒ 直接对账（缺哪片一目了然）；没有总数 ⇒ 唯一可用的旁证是 `integrity` 段（若它对每个成员有摘要），用它逐个成员验；两者都没有时不要靠启发式猜（猜错的方向是「把正常包报成坏包」）。
- 搁置理由：判据所需的数据不在包里，任何自造阈值（比如「末尾缺 2 片就算截断」）都会把正常包误判成损坏包——这是修缺陷时最容易新造的假警报。

### R4 陈旧 `.tmp` 只在下一次自动备份时回收

`deleteStaleTemps` 挂在 `runLocked` 开头。若用户关掉自动备份、或长期不打开前台，被杀留下的 `.tmp` 会一直占盘（一次最多一个文档体量）。它对新代码是隐形的（`listLocal` 不认），只是不回收。

- 触发条件：真机报「存储越来越大但备份只有 3 份」；或 Settings 的存储用量明细接入备份目录。
- 触发后方案：把同一个 `deleteStaleTemps` 挂到 `listLocal` 的调用点（设置页读列表时顺手清），或进 `StorageBudget` 的清理路径。
- 搁置理由：P3 级（不损坏数据、不谎报成功，只占盘一个文件）；挂到 `listLocal` 会把清理变成读列表的副作用，需要先决定「读操作允许有写副作用吗」。

## 7. 诚实边界 / 装置教训

**没有真机验证的两件事**：①磁盘满（`ENOSPC`）②导出中途被 SIGKILL。两者在沙箱用 `IOException` 模拟（测试里恰好写到「合法 JSON 前缀 + 立刻抛」的形态，也就是 ENOSPC 会留下的字节形状）。CI 只保证编译 + 单测 + 113 门；「Android f2fs 上 `rename` 行为」「被杀时 tmp 是否残留」需要真机才能确认——但设计上两个分支都不依赖具体结果：残留 ⇒ 下次 `deleteStaleTemps` 收掉；rename 成 ⇒ 完整备份。

**fsync 的边界**：写了 `fd.sync()`（文件内容落盘），**没有**目录项 fsync（Android 无简单 API）。极端掉电窗口 ⇒ 可能「备份文件消失」，而不是「备份文件截断」。这个方向是刻意的：丢一份备份可以重做，读到半份备份会毁掉恢复。

**装置教训（本轮最值钱的一条）**：第一版负控 M4 只把 `tmp = tempFor(target)` 改成 `tmp = target`（保留 `catch` 里的清理）⇒ 只有 1 条臂变红，因为 catch 顺手把截断件删了，「失败后不留文件」那条臂反而绿。**变异必须复刻修复前的代码形状（M4b：`target.bufferedWriter().use { body(it) }`，无 temp 无清理），而不是复刻一个近似** —— 换 M4b 后 3 条臂同时红，才证明这套断言真的钉住了缺陷。

**一处超出 5 个源码文件的改动**：`scripts/scan/backup_streaming_export_check.py` 的 arm8 重锚（+68 行，含两套新夹具）。理由与证据在 §4：门自己报的就是 "re-anchor this criterion"，重锚后判据实质未变且用变异夹具证明了没有钝化。若主会话希望把这个改动独立成支，`git cherry-pick` 只取该文件即可（与源码改动零耦合）。

**没改的东西（刻意）**：`ConfigBackup.kt` 一行未动（P1-A 的施工面）、`scripts/scan/scan.sh` / `test_scan.py` / `_kotlin_*` 原语零触碰、无新 UI、无新门。

## 8. 交接项

### 8.1 ConfigBackup.kt 的 6 行补丁（R1 的落地形态）

位置：`ConfigBackup.kt`，`import` 里读 `chatTruncated` 的那个 `let` 块（当前树 `:1806-1817`），在既有 `if (s > 0 || m > 0) { … }` **之后**追加：

```kotlin
            // [FIX-P1B / RT-S1] 转换层（OpenMinisBackupCompat）在 JSONL 分片缺片
            // 时写 shardsMissing：源包不完整，消息永久丢失，必须让用户知道。
            val shards = tr.optInt("shardsMissing", 0)
            if (shards > 0) {
                skipped.add(
                    "backup package was incomplete: $shards data shard(s) missing " +
                        "(chat/rule history restored partially; the source device still holds the full copy)"
                )
            }
```

纯加法；`ImportResult.skipped` 已经是被 UI 显示的通道（`BackupSettingsScreen` 导入结果），无需新 UI。

### 8.2 合并注意

- 本支文件面：`backup/AutoBackupLocalStore.kt`(新) / `backup/AutoBackupManager.kt` / `backup/OpenMinisBackupCompat.kt` / `ui/settings/BackupSettingsScreen.kt` / 两个测试文件 / `scripts/scan/backup_streaming_export_check.py`。
- 与 **P1-A** 的文件级交集只有 `BackupSettingsScreen.kt`：本支动 `:1209-1224`（fetch-to-local 写点），P1-A 动 `:316` 一带（快照调用）⇒ 行级不重叠；`ConfigBackup.kt` 本支零触碰。
- 与本支相关的既有基线段落（`[fix/da1023-backup-streaming-fetch]`、`[T-backup-streaming-export]`）的断言全部保留且绿。

### 8.3 真机复核清单（合并后一次性验）

1. 设置页自动备份区：点「立即备份」→ 目录里只出现一个 `.json`，无 `.tmp` 残留（沙箱 `ls /var/minis/.../filesDir/backup-autos`）。
2. 手动造 `rikkaminis-auto-<stamp>.json.tmp`（内容半截）→ 下拉刷新列表：**不出现**；再跑一次「立即备份」→ 该 `.tmp` 被回收。
3. 用 `-f` 构造一个缺 `data/messages-0002.jsonl` 的 `.minisbak` → 导入：消息条数 = 其余分片之和；等 §8.1 打完补丁后，导入结果里应出现 "backup package was incomplete: 1 data shard(s) missing"。
