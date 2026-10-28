# FIX-R3-D：B10 backup 核心 10 条（P1 7 / P2 3）

- 分支：`fix/r3-b10-backup`（基线 `2103729f`，HEAD `d92e8353`）
- CI：run **#2865** / `40751862639` **success**（`head_sha` 与本地 HEAD 逐字符一致）
- 改动面：**16 文件 +1228 / −57**（生产 7 · 测试 9）
- 停止节点：分支 CI 绿即停，**未合并 main**

---

## 1 背景：报告说的是什么，真码是什么

| # | 级 | 报告说法 | 真码 | 判定 |
|---|---|---|---|---|
| F-01 | P1 | 本地轮转删掉自己刚写的副本 | `selectLocalRotationVictims` = `files.drop(keep).filter(victimEligible)`，无保护位；`runLocked` 写完 → 轮转 → **再读该文件推送** | 成立 |
| F-02 | P1 | 空 map 原样放行 → 清空本机 agentLoop 列表 | `if (map.isEmpty()) return FieldIdRemap(value)`；Stage 3 只跳过 `value == null` ⇒ 异机 id 进 writer 求交集得空 | 成立 |
| F-05 | P1 | 空 content 覆盖本机 GLOBAL.md | 导出：`readFile` 把「不存在/读不到」折叠成 `""`，且 `listAllFiles` 无条件列 GLOBAL.md；恢复：`decideMemoryRestore` 只看 mtime | 部分成立（SOUL 半边 1028B-2 已修） |
| F-06 | P2 | 符号链接越界 | walk 只防环，`shared/bridge_bak -> ../../mcp-servers/oauth` 会被走进并归档 | 成立（窄触发） |
| F-08 | P1 | 同标签双 provider 互相覆盖 | 第 2 个条目 merge 命中**本次刚建**的第 1 个 | 成立 |
| F-09 | P1 | `modelBinding` 紧凑串未转 JSON | `.minisbak` 写 `"group:<id>"`，本侧所有读者要 `{"type":"group","groupId":…}` | 成立 |
| F-10 | P1 | 404 propstat 抹掉 200 | 一个 `statusOk` 判整个 `<response>`，第二个 `404 propstat` 覆盖前值 ⇒ 整条丢弃 | 成立 |
| F-12 | P2 | `skipped` 无消费方；裸 `{}` 报 archive empty | 导出 `skipped` 嵌在「有文件进包」分支内；导入 `has("archive")` 判空对象 | 成立 |
| F-13 | P1 | ①漏写 `includesSecrets` ②解析失败静默丢段 | ①基线该文件 `includesSecrets` 计数 **0**；②基线已有 `shapes.custom("secrets-root")` / `("provider-config-root")` 进修补**不是我引入的缺** | ① 成立 · ② **证伪** |
| F-15 | P2 | 只过滤 `image`/`video`，漏 `mediaRef` | 今天写入的行是 `mediaRef`（`relativePath` / `linuxPath`），旧过滤表匹配不到 | 成立 |

两条附带更正：
- 任务书标题写「P1 8 条」，表内实为 **7×P1 + 3×P2**（F-06/F-12/F-15 是 P2）。按表执行。
- F-05 的「SOUL 半边已修」确认属实：`decideMemoryRestore` 之外没有第二条写 SOUL 的路径，本支未重复改。

---

## 2 先证：改前读数是红的

本支的「改前」不是独立历史树重跑，而是**每个修复配一条等价回退臂**：把修复退回改前形状，跑同一批测试，命名测试必须变红。全部 10 条臂 **MATCH**（红 → 绿全部对上）。

| 臂 | 退回的改前形状 | 变红的测试 |
|---|---|---|
| A1 | F-02 空 map 捷径 | `emptyMapCannotClearTheLocalAgentLoopList`、`emptyMapsStillResolveThisInstallsOwnIds`、`secondImportWithNoGroupsSectionKeepsLocalDefault`、`voiceGroupNeverRoutesThroughEntryMap` |
| A2 | F-01 去掉 `protectName` | `rolledBackStampDoesNotMakeTheJustWrittenCopyTheVictim` |
| A3 | F-05 去掉空内容守卫 | `blankPayloadWithRealContentIsKeptThroughTheStage6Body`、`emptyPayloadNeverOverwritesRealLocalContent` |
| A4 | F-06 不解析真实路径 | `aSymlinkEscapingTheSharedRootIsNotArchived`、`aSymlinkToADeniedDirectoryInsideTheRootIsStillDenied`、`aSymlinkedFileEscapingTheSharedRootIsNotArchived` |
| A5 | F-10 单个 status 判整条 | `a404GroupCannotEraseTheAnsweredGroupsValues`、`anEntryWhoseEveryGroupFailedIsStillDropped` |
| A6 | F-15 去掉 `mediaRef` | `mediaRefPartsAndPointersAreDropped`、`mediaOnlyPartsStillCollapseToNull` |
| A7 | F-09 绑定原样透传 | `compact model bindings are translated into the persisted json shape`、`convertsPlaintextPackageEndToEnd`、`session binding falls back to the wrapper when the inner key is blank` |
| A8 | F-08 忽略本次认领 | `an instance this run already claimed is not`、`mergeDecisionRespectsWhatThisRunAlreadyClaimed`、`the third duplicate still appends` |
| A9 | F-05 退回 `readFile` 折叠语义 | `exportReaderSeparatesAbsentFromEmpty` |
| A10 | F-13 漏写 `includesSecrets` | `converted document declares includesSecrets when credentials ride along`、`converted document does not claim secrets it does not carry` |

两条方法学备注（都是本支踩过的）：
1. **臂的判定依据是「类命中」，不是失败名列表**。早期输出 `failing=(none)` 是显示正则 `^\d+\) (\w+)\(` 吞掉了带空格的反引号测试名（Kotlin ``fun `a b c` ``），判据一直成立。修正则后每条臂都给出具体名字（上表）。**别把「名字没打出来」当成「没有失败」**。
2. **臂的 classpath 不能依赖 `/tmp`**：`/tmp` 里的 `xmlpull` / `kxml2` jar 被收割器清掉后，全部臂 `COMPILE-FAIL`（报 `unresolved reference 'xmlpull'`，而脚本只打 stdout 所以看不到）。jar 移入 `/var/minis/shared/work/jvm-libs/`（`kxml2-2.3.0.jar` 自带 `org.xmlpull.v1`）后恢复。

---

## 3 修法

### F-01 · 轮转不删自己刚写的副本

- `selectLocalRotationVictims(..., protectName: String? = null)`：过滤 `it.name != protectName`。
- **为什么用 ThreadLocal 而不是加参数**：`backup_streaming_export_check` 把这个调用钉成字面形状 `rotateLocalAutoBackups(app)` / `rotateLocalAutoBackups(context)`（那道门的存在理由是一个死守卫让 `filesDir/backup-autos` 无限增长，见其 arm 1），本支不得动 `scripts/scan/**`。所以**规则放在可测的纯边界上**（`protectName`），ThreadLocal 只做管道：
  - `withFreshlyWritten(file) { block }`：写名 → 执行 → `finally` 恢复（异常不留残留保护，嵌套时恢复而非清空）；
  - `freshlyWrittenName()`（internal）：暴露给测试钉住「块内可见 / 块外消失 / 嵌套恢复」。
- 两个写点接线：`runLocked`（夜间跑）与 `BackupSettingsScreen` 的 fetch-to-local（行 1360 附近）。

### F-02 · 空 map 不再是「原值放行」

删掉捷径，`remapDefaultsIds` 增加 `localGroupIds` / `localEntryIds`（默认为空）：

- 值 ∈ 本机 id 集合 → 原样保留（同机恢复、旧包无 `groups` 段的正常路径）；
- map 能解析 → 用新 id；
- 都解析不了 → `value = null` + `unmapped` 上报（Stage 3 记进 `skipped`），**不写**。

调用侧在导入开始前**只读一次**本机 id（ConfigBackup.kt:1590），不是每字段查库。判据是「第二次导入不能引入新的悬空引用」，不是「永不重映射」。

### F-05 · 空载荷不覆盖真实内容（两侧都修）

- 导出侧新增 `readMemoryFileForBackup(dir, name): String?`：`null` = 不存在/读不到 → **payload 里没有这个条目**；`""` = 真的是空文件 → 照常携带（内容即事实）。`memoryBackupEntries` 改用它，`MemoryRepository.readFile` 的语义**不动**（其它 caller 见 §6 R2）。
- 恢复侧 `decideMemoryRestore` 增 `backupContentBlank` + `localHasContent`：备份空 + 本机有内容 → `KeepLocal`（reason 写明）；`localHasContent` 双向 fail-closed —— 读不到 / 是目录 / 路径消失一律答 `true`（"假设有内容值得保护"），因为这条判据唯一的用途是「允许空载荷覆盖吗」。

### F-06 · 先解析，再判归属

`ArtifactBackupScope.select`：`rootCanon` + `rootPrefix`，`resolveInside(child)` = `child.canonicalFile` 必须等于根或以 `根 + separator` 开头，任何异常 → `null`（fail-closed 视为非本根产物）。walk 里**先解析再判**：目录/文件的 `DENIED_DIRS` 检查都用 `child.name` 与 `target.name` 双查（链接名与真实名）。

### F-08 · 本次导入认领过的实例不是 merge 目标

新增 `claimedInstanceIds`（本 run 认领集合）+ 纯函数：

```kotlin
internal fun shouldMergeIntoExisting(matchedLiveInstanceId: String?, claimedThisRun: Set<String>): Boolean =
    matchedLiveInstanceId != null && matchedLiveInstanceId !in claimedThisRun
```

被本次认领 → 走 append 路径拿 `"<label> (2)"` 重命名（与撞上既有实例同一条路）。merge 与新建两处都登记认领。

### F-09 · 紧凑绑定翻译

`convertModelBinding(raw)`（顶层纯函数）：`group:<id>` → `{"type":"group","groupId":…}`；`entry:<uuid>` → `{"type":"entry","entryId":…}`；空 id / 未知前缀 / 已是 JSON → **逐字节原样返回**（这是翻译，不是校验）。翻译后走 `remapSessionBindingJson` 同一出口。

### F-10 · 按 propstat 分组，任一 2xx 组即存在

`parseMultistatus` 增 `inPropstat` / `sawPropstat` / `acceptedPropstat` / `propstatOk` + 四个 `staged*` 字段：新 propstat 组开始时清空暂存并重置 `propstatOk`；组内属性只暂存，组结束且 2xx 才提交到 response 级。收尾判据：

```kotlin
val ok = if (sawPropstat) acceptedPropstat else statusOk
```

无 propstat 组时才回退 response 级 status（老夹具靠这条）。

### F-12 · `skipped` 有读者，裸 `{}` 不再误报

- 导出：`artifacts.put("skipped", n)` 移出 `fileCount > 0` 分支 —— 全被略过时也报（原本读起来是「这里什么都没有」）。
- 导入：读 `skipped` 进恢复报告（`artifacts: N file(s) were left out at export`）；`archive empty` 只在 `has("archive") && archive.isEmpty()` 时报，裸 `{}`（旧导出 / 只带 skipped 的新形状）保持安静。

### F-13 · 转换包必须声明它携带的凭据

`.put("includesSecrets", secrets != null && secrets.length() > 0)` —— 值与转换器实际搬运的一致（env 值 + provider 凭据都走 `secrets.json`）。后半条（解析失败静默丢段）**证伪**：基线已有 `shapes.custom("secrets-root")` / `("provider-config-root")`，失败进 conversion notes。

### F-15 · `mediaRef` 一并丢弃

- `sanitizeChatParts` 的媒体判定加 `mediaRef`（与既有 `image_url` / `video_url` 别名同族）。
- `toolResult` 分支 `value.remove("mediaRef")`：toolResult 正文保留（它是证据），只删接收端解析不了的私有指针。
- **门冲突（重要）**：把媒体判定拆成多行后 `parts_json_funnel_check` 判红（R11/R12）——门的 family 推导把它读成「按类别区分 image/video 的量化面」，而该门的 KDoc 明确这个丢弃器对 image/video 同等对待、刻意不在家族内。**正解是恢复单行（语义不变），不是改门**。代码里留了注释说明「单行是刻意的」。

---

## 4 证据（改后）

| 装置 | 读数 |
|---|---|
| 静态门 `scan.sh --jobs 2` | **113 passed, 0 failed** · `0 violation(s)` |
| 夹具自测 `test_scan.py --jobs 4` | **ALL 349 CASES PASS** |
| JVM 探针 `run.sh` | **OK (98 tests)** |
| 变异臂 `arms.py` | **10/10 MATCH**（明细见 §2） |
| 形状检查 `shape_check.py` | **ALL SHAPE CHECKS PASS (40)** · `C2 forbidden paths untouched — none touched` |

新增/扩充 9 个测试类（全部 JVM 可跑，无 Android 依赖）：`AutoBackupFetchHardeningTest`（含回拨时钟 + 保护域边界）、`ArtifactBackupScopeTest`（越界链接三向 + 根内链接仍跟随）、`MemoryRestoreGuardTest`（空载荷四态 + 不可读 fail-closed + 保留不计入 imported）、`BackupProviderMergeTest`（认领集合 + 第三个重复仍 append）、`WebDavClientTest`（双 propstat / 组全失败 / response 级回退）、`ConfigBackupPayloadTest`（`mediaRef` 丢弃 + 媒体-only 仍为 null）、`OpenMinisBackupCompatTest`（`includesSecrets` 两向 + 绑定翻译三态）、`BackupIdRemapTest`（空 map 不再清空 + 本机 id 保留）、`BackupMemoryRollupTest`（`null` 与 `""` 分离）。

---

## 5 CI

- run **#2865** / id `40751862639`，**success**，`head_sha = d92e8353240f40b093c472a592198437dbcdf3aa` = 本地 HEAD。
- 起止 `2026-10-28T19:10:16Z → 19:32:09Z`（**21m53s**）；job `123040384588`（build）。
- **27 步：25 success + 2 skipped**，0 非 success。2 skipped = 滚动 tag + Publish —— 分支上 Publish 被 `if: main` 门控，**符合「分支不发布」的预期**，不是失败。
- 日志判读（1,579,538 bytes，**NUL 0**）：
  - `Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1、`^e: ` **0**；
  - 含 `FAILED` 的 6 行**全是 PASSED 用例名的子串**（`slot denied produces FAILED without calling provider PASSED`、`reason PERSISTENCE_FAILED maps to…`、`phase FAILED maps to Failed PASSED`、`terminal FAILED maps to Failed PASSED`、`reason EXECUTION_FAILED maps to…`、`the fixed tail still reaches a legal FAILED terminal PASSED`）。

---

## 6 残留（登记，不修）

| # | 内容 | 为什么不修 |
|---|---|---|
| R1 | **F-03 chars vs bytes**：导出预算按字符、导入前置上限按字节 ⇒ CJK 内容「导得出去、导不回来」 | 已登记 P2，任务书明确本批不动 `BackupStreamWriter.kt` |
| R2 | `MemoryRepository.readFile` 仍把「不存在」折叠成 `""`（caller：`MemoryManagementScreen:400` 等） | 导出侧已用独立读取器根治本缺陷；改签名要 rg 全部 caller，半径大于收益 |
| R3 | F-01 保护按**文件名 + 线程域**：`withFreshlyWritten` 块外触发的轮转看不到标记；两个线程各写不同名文件互不保护 | 同名文件在同一目录里就是同一文件，实际无差；跨线程场景本支不构造 |
| R4 | F-06 的 `resolveInside` 只覆盖 **sharedRoot** 的 walk；`mcpRoot` 走 `select` 的另一分支，未加同一守卫 | 窄触发（需目录里存在指向外部的链接）+ 半径纪律；mcp 根的既有 `DENIED_DIRS` / 扩展名白名单不变 |
| R5 | F-09 未知前缀原样返回 ⇒ 转成 JSON 后若 id 在本机不存在，`remapSessionBindingJson` 保留原值（`restoreFromBinding` 走 fallback chain，可感知但不静默） | 「翻译不校验」是刻意契约：校验会让一次纯格式转换开始拒绝包 |
| R6 | F-10 有 propstat 组但全失败、而 response 级 status=200 → 丢弃；一个 2xx 组 + 一个 404 组里属性分散在不同组时只提交 2xx 组 | RFC 4918 允许但实测服务器不发这种形状；丢弃是 fail-closed 方向 |
| R7 | F-12 的 skipped 只进恢复报告文本，没有 UI 聚合计数 | 需要新 i18n 7 份，属体验优化非缺陷 |
| R8 | F-13 的 `includesSecrets` 按「`secrets.json` 非空」判定，依赖「凭据只走 secrets.json」这个既有约定（转换器本就不搬 `provider_config` 里裸放的 apiKey） | 自洽：不搬的东西不该声明携带 |
| R9 | F-15 媒体-only 消息在预算层被替换为占位文本 `"[media message elided]"`（`ChatBackupBudget` 既有行为），直连路径返回 `null` parts | 既有形状，本支只负责不让私有路径进包 |
| R10 | **真机未验**：全部证据 = CI 编译级 + JVM 级 + 源码形状 | 本支无可用于真机的构造（需 WebDAV 服务器 + 两次设备间导入） |

---

## 7 复现

```sh
cd /var/minis/shared/work/meta-audit-1029/probe-r3d
python3 extract.py && sh run.sh          # 98 tests
python3 shape_check.py                   # 40 checks
python3 arms.py                          # 10 臂，退货后逐条变红
cd /var/minis/shared/work/meta-audit-1029/wt-b10-backup
sh scripts/scan/scan.sh --jobs 2         # 113/0
python3 scripts/scan/test_scan.py --jobs 4   # 349
```

变异臂的 classpath 用 `/var/minis/shared/work/jvm-libs/`（**不要**用 `/tmp`，会被收割）。

---

## 8 这改变什么用户行为

- **F-01**：时钟回拨或拉旧备份后，当天的本地副本不再被自己删掉；随后 WebDAV 推送不再 `FileNotFoundException`，设置页的「今天已备份」不再与事实相反。
- **F-02**：导入一个不含 `groups` / `providers` 段的旧包，本机 agentLoop 列表与 compaction 模型 pin **不再被清空**；失败的映射出现在恢复报告的 skipped 里。
- **F-05**：从没建过 `GLOBAL.md` 的设备导出的包，恢复到有 `GLOBAL.md` 的设备上不再把它截成 0 字节；恢复报告里能看到「备份是空的，保留本地」。
- **F-06**：`includeSecrets=false` 的包不再把 `shared/` 下指向 `mcp-servers/oauth` 的链接目录打进去（明文 OAuth token 不外流到 WebDAV）。
- **F-08**：备份里两个同标签 provider 恢复后是**两个**（第二个叫 `"<label> (2)"`），不是一个被另一个覆盖。
- **F-09**：`.minisbak` 恢复的会话真的按组路由并保留 fallback 链，不再静默退回 `modelId`。
- **F-10**：远端备份存在但服务器把可选属性放 404 组时，列表**不再显示为空**（不再误报「你没有远程备份」）。
- **F-12**：恢复报告能说「导出时略过了 N 个文件」；裸 `{}` 的包不再冒出假的 `archive empty`。
- **F-13**：含密钥的 `.minisbak` 恢复时不再同时弹「此备份不含 API 密钥」——那条自相矛盾的警告是训练用户忽略警告的那条。
- **F-15**：备份消息里不再携带源设备的 `mediaRef` 私有路径；接收端看到的是可理解的占位而不是坏附件。
