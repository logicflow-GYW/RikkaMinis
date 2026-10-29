# FIX-R5-B1：恢复管线字段/单位 5 项（R5 修后复扫批）

- 分支 `fix/r5-b1-restore-fields`，基线 main `f3def6ce`
- 任务书 `shared/work/r5-1029/tasks/TASK-R5-B1.md`（覆盖 B-F-01 / B-F-05 / B-F-06 / B-C-01残留(F-12·F-15) / B-C-08）
- 独占文件：`ConfigBackup.kt` / `OpenMinisBackupCompat.kt` / `MemoryRestoreGuard.kt`（+ 两个测试文件）
- 没碰：`scripts/scan/**`、`scan.sh`、`test_scan.py`（门基线 113 / 夹具 349 不变）、`BackupStreamWriter.kt`（F-03 chars/bytes = 已登记 P2，触发条件未到）

---

## 1 每条一行

| ID | 读数 | 用户可见的变化 |
|---|---|---|
| **F-01** `.minisbak` createdAt 断链 | **成立，两侧都缺**：convert 只写 `exportedAt`（`convert` 顶层 3 个键），import 只读 `createdAt`（`optLong("createdAt", 0L)`），guard 对 `<=0` 全拒 | `.minisbak` 恢复现在能带回记忆文件；此前**每一个**转换包都被拒（含首启自生成的 SOUL.md/GLOBAL.md），只留一行 "backup carries no timestamp" |
| **F-06** 记忆守卫反向不对称 | **成立**（`isNotEmpty()` vs `isBlank()`；且缺反向规则） | 本机是 0 字节/纯空白的 GLOBAL.md、日报时，备份里的真内容会被写入（以前永远被"本机更新"挡住） |
| **F-05** 同名 Provider 覆盖 / Stage2 无 claimed | **成立，两处都成立且比报告多一处**（`existingGroupNames` 是循环前快照 ⇒ 重命名分支本就是死码） | 同标签的两个 Provider 各自拿回自己的思考规则（不再两个都压到最后一个）；备份里两个同名模型组会变成 `X` + `X (2)`，而不是并进一个并把 runConfig 覆盖一次 |
| **C-01残留 F-12** 容量降级静默 | **成立**（`includeArtifacts=false` 时 `artifacts` 是裸 `{}`，与"本机没有 artifacts"同形） | 恢复报告多一行 "the backup dropped its archive to fit the size cap" |
| **C-01残留 F-15** 导入侧不净化 | **成立**（导出侧 sanitize，导入侧 verbatim） | 悬空 `mediaRef` 不再写进消息表；体积交回既有 500 KB 字节封顶（见 §3 的两条策略分工） |
| **C-08** includesSecrets 假阳性 + 空白 binding | **成立，两条都成立** | 空壳 secrets 不再谎称"含密钥"（那条"请重新填 Key"的提醒回来了）；外层纯空白 modelBinding 不再挡内层真值 |

一句话行为改变：**恢复不再"报告成功但实际什么都没带过来"**（记忆 / 密钥提醒 / artifacts / 附件 / 同名归属）。

---

## 2 先证与证伪（按语义核，不按行号）

全部在基线 `f3def6ce` 真树上重定位过。报告行号多数对不上（ConfigBackup 3498 行，报告引用 `2867-2876` 不超出文件但所指代码已移），所以逐条按机制核。

| 报告声称 | 核实结果 |
|---|---|
| convert 顶层只写 `exportedAt` | **真**。`convert` 的 `doc` 只有 `format/version/exportedAt/includesSecrets` |
| import 只读 `createdAt` | **真**。Stage 6 `backupCreatedAt = root.optLong("createdAt", 0L)` |
| guard 对 `<=0L` 全拒 | **真**。`decideMemoryRestore` 第三条 KeepLocal |
| `localHasContent` 用 `isNotEmpty`，payload 侧用 `isBlank` | **真**，两侧量纲确实不一致 |
| "`overwriteExisting` 校验后加对称规则" | **半证伪**：`decideMemoryRestore` / `restoreMemoryFiles` 都没有 `overwriteExisting` 形参（那是 `.minisbak` 导入 sheet 的措辞漂移）。对称规则插在**空备份规则之后、时间戳比较之前**，语义等价 |
| F-05 `providerKeyToId` 单槽覆盖 ⇒ 规则错绑 | **真**。键 `providerType\0label`，同名第二条覆盖第一条；`restoreThinkingRules` 是 REPLACE ⇒ 第一条实例 0 规则、第二条只剩最后一组 |
| F-05 "`:1357` 查 matchedLiveId 加 `!in claimedInstanceIds`" | **已满足，未改**。`shouldMergeIntoExisting(matchedLiveId, claimedInstanceIds)` 就是那个守卫（FIX-R3-D/F-08 那一轮加的），告记为"无需动" |
| F-05 Stage2 无 `claimedGroupIds` | **真**，且**比报告多一处**：`existingGroupIds/existingGroupNames` 是循环前快照 ⇒ 就算加了 claimed 门，重命名分支也只会拿到过期名字集（第二条仍会撞名）。两处一起修 |
| F-12 降级写空 artifacts 对象 | **真**。`buildSections` 只在 `includeArtifacts && roots` 时填 archive/skipped |
| F-15 `importChatSections` / `convertChats` 未净化 | **真**，但修法改一处：`sanitizeChatParts` **不是幂等的**（二次会再截断已带 `… [truncated N chars]` 的文本），所以只在 `importChatSections` 这个唯一入库接缝净化（`.minisbak` 的输出也必经此处），convert 不重复净化 |
| C-08① `secrets.length()==2` 假阳性 | **真** |
| C-08② 外层纯空白 modelBinding 阻断内层 | **真**，`.ifEmpty` 只判空串 |

**测试夹具的形状证伪**：既有 `OpenMinisBackupCompatTest` 里 includesSecrets 的正例用 `{"inst-1":{"apiKey":"sk-live"}}`（扁平 map）——那**不是** `convertProviders` 读的形状（它走 `secrets.providers[]` 的 `instanceId`），旧判据 `length()>0` 才让它一直在过。已把夹具改成真实形状。

---

## 3 修法

**F-01（双侧）**
- `OpenMinisBackupCompat.convert`：`.put("createdAt", manifestCreatedAtMillis(manifest, now()))`；`manifestCreatedAtMillis` = `isoToMillis(manifest.created_at)` 取正值，否则调用方给的 `now()`。不拿 `snapshot_at` 当第二回退（它是中间快照的时刻，与被拒无关的另一个时刻排序会制造新的错序）。
- `MemoryRestoreGuard.documentCreatedAt(root)`：`createdAt` 优先，`<=0` 回退 `exportedAt`；两者都不可用仍返回 0 ⇒ 守卫保持 fail-closed。字符串形态的时间戳（手写/第三方包）一并接受。

**F-06**
- `localHasContent`：`isNotEmpty()` → `isNotBlank()`（与 payload 侧 `isBlank()` 同量纲）。
- 新增对称规则 `if (!localHasContent && !backupContentBlank) return Restore`，位置在空备份规则之后、时间戳两条守卫之前——那两条守卫的意义是"保护内容"，而这里没有内容可保护。
- 形参默认值 `localHasContent: Boolean = false` → `true`：KDoc 一直写"unknown 失败关闭"，默认值却相反；一旦有第二个调用方忘传，默认值就会替它放行覆盖。

**F-05（三处 + 一处门形状适配）**
- Stage 1：**单槽赋值逐字保留** `providerKeyToId["$type\u0000$label"] = instId`（`backup_remap_rollup_check` 的 M11/M11c 变异臂钉住这一行必须真的读回 key、且只能有一次赋值），同时把**非最后**的 id 按到达顺序 `add` 进 `idsByProviderKey[key]`。
- Stage 1.5：`pendingForThisKey = idsByProviderKey[key]`，非空就先 `removeAt(0)` 消费，否则回落单槽 map（单 Provider/key 的常规包行为逐字不变）。
- Stage 2：`claimedGroupIds` 门（本轮创建/合并过的组不再当合并目标）+ 两个集合 `existingGroupIds/existingGroupNames` 改成**循环内活读数**；两个分支各自登记。
- 首版曾把 Stage-1 key 表达式抽成局部 `ruleKey` 并多写一次读 → run 1 里 M11/M11c 直接失牙。结论：门的立场是对的（它保护的是"这个键真的被算出来并落在槽里"），改的是代码不是门。
- 队列是**追加**结构、单槽 map 保留其原义（"最后拥有该 key 的实例"），两者都真被读；没有为过门而留死码。

**F-12**
- 导出：`!includeArtifacts && roots 非空` 时 `artifacts.put("droppedForSize", true)`（`includeArtifacts=false` 只可能来自 `buildWithinCap` 的降级阶梯：`attempt.hadArtifacts` 为真才会走到，故这条声明永远是真的）。
- 导入：Stage 9 在 archive 检查之前读该标记 → `skipped.add("artifacts: the backup dropped its archive to fit the size cap …")`。

**F-15（两条体积策略分工）**
- `ConfigBackup.sanitizeIncomingParts(raw)`：空白原样返回；`sanitizeChatParts(raw, slimText = false)` 返回非 null 就用它；返回 null 时再区分"解析不了"（原样交给行级字节上限）与"解析了但没有可存活的 part"（写空串）。
- `sanitizeChatParts(partsJson, slimText: Boolean = true)`：`slimText` 只控制**细节流**（text 4000 / toolInput 2000 / toolOutput 500 三处裁剪）；`false` = 只删死媒体指针，体积交回 `capPartsJsonForRow` 的 500 KB 字节封顶。导出面默认 `true`，逐字不变。
- `importChatSections`：`capPartsJsonForRow(ConfigBackup.sanitizeIncomingParts(m.optString("partsJson")))`——先净化、再按字节封顶。
- **为什么加这个参数**（run 2 学到）：不加的话，导入侧 900K 文本先被 4000 字符裁剪，500 KB 封顶就再没有东西可截，封顶标记（`Content truncated at 500 KB — original length N chars`）消失 —— 两条策略按顺序互相吞掉，任何人都观察不到第二条在工作。

**C-08**
- `secretsCarryCredentials(secrets)`：按**值**判定（`providers[]` 的 `apiKey`/`manualOAuthToken`/`oauthToken`，`envVars[]` 的 `value`），空壳/空值都算"没有"。
- `modelBinding`：两侧先 `trim()` 再 `ifEmpty`。

---

## 4 证据（三源，均不含 CI 之外的口头推断）

**① JVM 实跑（生产文件逐字，非复刻）** `b1-harness/run.sh`
`MemoryRestoreGuard.kt` 与 `OpenMinisBackupCompat.kt` 不依赖 Android（org.json + java.* + javax.crypto），整文件编译进 JVM：
```
post: TOTAL_TEST_CLASSES=2 / OK (33 tests)
```
（`MemoryRestoreGuardTest` 27 + 新增 `R5B1CompatPinsTest` 6）

**② 前态对照臂** `b1-harness/run-pre.sh`（HEAD 版本生产文件 + 把新名字按旧语义实现的三行 shim）：
```
Tests run: 33,  Failures: 13
```
13 条失败 = 全部新判据逐条红（空白本机刷新 / isNotBlank / fail-closed 默认 / Stage6 体 / exportedAt 回退 / 时间戳不可用 / createdAt 写入 / 无 stamp 回退 / 形状解析 / 空壳 secrets / 按值判定 / 空白 binding / 旧读取对照臂）；既有 20 条在新旧两侧都绿 ⇒ 判据确实指着这次改动，而不是环境差异。

**③ 形状探针 + 变异臂** `b1-harness/probe_b1.py`（ConfigBackup 是 Android-bound，只能源码级钉 wiring）
```
post: PROBE=PASS (7/7)  ARMS=ALL MATCH
pre(HEAD): PROBE=FAIL (0/7)
```
7 条判据各配一条变异体（把 Stage6 退回 `optLong`／删掉旧 id 记录／退回单槽读／去掉 claimed 门／降级不写标记／导入不报降级／去掉净化），每条变异**只**打红它自己那条 ⇒ 判据互相独立、且都不是恒真。

**④ 导入管线整链 harness** `b1-harness/import_funnel/`（run 2 之后新增，补上"净化 + 封顶"这条链直到 org.json 真解析这一层）
`extract.py` 从两个生产文件**逐字切出 11 个成员**（`sanitizeChatParts` / `sanitizeIncomingParts` / `capPartsJsonForRow` / `buildTruncatedPartsJson` / `truncationMarker` / `takeByUtf8Bytes` / `ATTACHED_FILES_REGEX` / 三个体积常量 + 一个行读安全网），拼进 `ImportFunnelHarness.kt`，**不手抄、不改写**：
```
EXTRACTED 11 verbatim members
IMPORT_FUNNEL=PASS (5 groups)
```
5 组断言：死媒体指针被删（每个媒体变体 arm 都走到）/ 活的保留 / 仓库路径不被误删 / 导出面裁剪仍在（4000 字符）/ 导入面 900K 文本保住且带 500 KB 封顶标记。第 5 组就是 run 2 那条红用例的整链复现——它在本地产出为绿，与 CI run 3 的 PASSED 互相印证。

**⑤ 相关门单跑（未跑全量）**：`backup_remap_rollup_check` / `parts_json_funnel_check`（含 `--selftest` **48 arms PASS**）/ `backup_streaming_export_check` / `openminis_compat_field_parity_check` / `discarded_return_guard` / `envvar_config_group_check` / `mcp_oauth_lifecycle_check` / `provider_import_id_roundtrip_check` / `provider_ua_parity_check` —— **9/9 rc=0**。

**⑥ 语法层**：`kotlinc` 单编译 `ConfigBackup.kt`，过滤 `expecting|unexpected|unclosed|syntax` = 0 条（其余是预期内的 unresolved reference）。

---

## 5 CI 判读

三轮，同一分支：

- **run 1 `40856408577` FAILURE**（`b2521883`，完成 10:23:07Z）：`Run pre-build scan gate` 红，`backup_remap_rollup_check.py` 的 M11/M11c 变异臂失牙（首版把 Stage-1 key 表达式抽成局部 `ruleKey` 并多写一次读）。处理 = 改代码不改门 → 重构 Stage 1/1.5 → 本地 selftest 59 arms PASS。提交 `60488ac7`。
- **run 2 `40859772387` FAILURE**（`60488ac7`，完成 10:53:31Z）：门已绿，红在第 15 步 `Run unit tests (full suite)` —— `PartsJsonFunnelTest > importChatSections caps an oversized restored row`（`PartsJsonFunnelTest.kt:324`）。根因不是封顶本身，是**两个体积策略抢跑**：导入侧 `sanitizeIncomingParts` 先按导出策略把 900K 文本砍到 4000 字符，`capPartsJsonForRow` 的 500 KB 封顶便再没东西可截，"Content truncated at 500 KB — original length 900028 chars" 标记消失。修法 = `slimText` 参数（见 §3 F-15）。提交 `ac9fd486`。
- **run 3 `40863469329` success**（`ac9fd486`，11:11:36Z → 11:33:19Z，21m43s）：`head_sha ac9fd486b82793c1dea19e4bcdb9e66b68d06e67` 与本地 HEAD **逐字符一致**；27 步 = 25 success + 2 skipped（rolling tag / Publish，均 main 门控，分支上本应 skip）；日志 1,628,129 B、`NUL_BYTES=0`；`Summary: 113 passed, 0 failed`（门集，含 `[113/113] storage_budget_check`）+ 分片 16/18/19/25/30/44 全 0 failed；`ALL 349 CASES PASS` ×1；`^ e: ` 0；含 `FAILED` 的行 0；新增用例 **33 条全 PASSED**（`MemoryRestoreGuardTest` 27 + `R5B1CompatPinsTest` 6）；`PartsJsonFunnelTest` 8 条全 PASSED，含 run 2 红的那条。

教训（可复用）：**同一份数据被两条独立的体积策略加工时，先跑的那条若更激进，后一条的策略永远观察不到输入**。判据 = 每条策略各自的可观测标记必须能在同一路径上同时成立；本支把"导入面要不要裁剪细节"显式化成一个参数，而不是让两个常量按顺序互相吞掉。

---

## 6 残留（三件套：触发条件 / 触发后方案 / 为什么现在不做）

- **R1 F-05 规则组配对在"同名 Provider 数 > 规则组数"时仍是位置猜测**：wire 上 rules group 只带 `(providerType, providerLabel)`，没有 providerId，无法区分"只有第二个有规则"。触发条件 = 有人报"恢复后某个同名 Provider 的思考规则跑到了另一个实例上"。触发后方案 = 导出侧给 `thinkingRules[]` 元素加 `providerId`（导入侧有值就用、无值回退队列）。现在不做：改导出格式要动 `openminis_compat_field_parity_check` 的 53 键集，半径不成比例。
- **R2 F-15 同机恢复旧包时此前可解析的 mediaRef 现在会被丢弃**：导出侧从 sanitize 引入起就不再携带 mediaRef，所以这只影响"sanitize 之前的旧包 + 同机恢复"。触发条件 = 有用户报"恢复后聊天里的图片没了"。触发后方案 = 给 `sanitizeChatParts` 加 `keepMediaRef: (String) -> Boolean` 钩子，导入侧按文件是否存在决定。现在不做：跨设备死链的影响面更大，且路径是否带会话 id 需另证。
- **R3 不可解析的 partsJson 仍原样入库**（只受行级字节上限约束）：payload 的形状不该由导入侧改写。触发条件 = 出现"恢复后某条会话打不开"的真机报告。
- **R4 真机未验**：本次全部证据是 JVM 级 + 源码形状级 + CI 编译级；`MediaStore` / Compose 弹层 / 真实 `.minisbak` 包端到端未跑。
- **R5 Stage 2 每个 group 多读一次 `providerRepo.config.value`**（StateFlow 值，非查询）：组数是个位数，未测；若将来备份带几百个组再谈。
- **R6 体积策略职责已拆开，但导入面的上限名义上仍是两个常量**：`MAX_BACKUP_TEXT_CHARS`（导出细节）与 `MAX_MESSAGE_PARTS_JSON_BYTES`（落库封顶）各自独立成立，导入面实际只受后者约束。触发条件 = 有用户导回 1 MB 级单条 partsJson 且行读超时。触发后方案 = 给导入面加第三个常量，而不是复用前两个。

---

## 7 半径外（记录，不修）

- **F-03 chars vs bytes**（导出按字符、导入按字节）：已登记 backlog §B10 P2，触发条件 = 下次动 `BackupStreamWriter.kt`。本支未碰。
- `remapDefaultsIds` 的悬空 group id（1028B 已登记）：与本支 F-05 同文件不同函数，未动。
- 报告里 "`overwriteExisting` 校验" 与 "configRoot 读块" 两处措辞在真树上无对应代码：属报告版本代差，不展开。

---

## 8 附录：复现命令与 CI 读数

```
# JVM 双态
sh /var/minis/shared/work/r5-1029/b1-harness/run.sh
sh /var/minis/shared/work/r5-1029/b1-harness/run-pre.sh

# 形状探针 + 变异臂（默认读 worktree 生产文件；--file 指向前态副本）
python3 /var/minis/shared/work/r5-1029/b1-harness/probe_b1.py --arms
python3 /var/minis/shared/work/r5-1029/b1-harness/probe_b1.py --file pre/src/ConfigBackup.kt

# 导入管线整链（逐字抽取 11 个成员，org.json 真解析）
cd /var/minis/shared/work/r5-1029/b1-harness/import_funnel && python3 extract.py
```

本地（提交前全绿）：`run.sh` **OK (33 tests)**；`run-pre.sh` **13 failures**（对照臂，按设计红）；`probe --arms` **ARMS=ALL MATCH**；`parts_json_funnel_check .` PASS + `--selftest` **48 arms PASS**；`backup_remap_rollup_check .` OK；`IMPORT_FUNNEL=PASS (5 groups)`；`kotlinc` 语法零 error。

CI：**run 3 `40863469329` success**（`ac9fd486b82793c1dea19e4bcdb9e66b68d06e67`，21m43s，27 步 25 success + 2 main-门控 skip；日志 1,628,129 B / NUL 0；`Summary: 113 passed, 0 failed`、`ALL 349 CASES PASS`、`^ e: ` 0、FAILED 0；新用例 33 条全 PASSED）。
