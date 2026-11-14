# FIX-RC-A · 后台结果结构化 + UI 按结果分支（10 条）

| 项 | 值 |
|---|---|
| 分支 | `fix/rc-1114-a-result-plumbing` |
| 基线 | `acb8bdd`（R8 并集，非 main） |
| tip | **`a58aa163cfe66c4b3f0075a1ef7b782178490936`** |
| 提交数 | 8（U-04+U-05 合一条，U-13+U-14 合一条，其余一条一个 ID） |
| diff | 26 文件 / **+1289 −70**（main 12 / test 5 / res 7 语言 / 报告） |
| 门改动 | **0**（本支禁碰 `scripts/scan/**`，未碰） |
| 状态 | **分支 CI 绿**（run `43537882646`，head_sha 与代码 tip `49350e0` 逐字符一致；本报告为之后的纯文档提交）；**未合并 main、未 rebase** |

---

## 0 形态判定总表（先判 A/B 型，判错会白改）

| ID | 判定 | 依据（先证） | 修法落点 |
|---|---|---|---|
| U-01 | **B 型** | `suspend fun runNow(app: MinisApp)` 返回 `Unit`；4 处失败点各自只有 `Log.w` | 先改 API（新增 `AutoBackupRunOutcome`），再改 UI |
| U-02 | **A 型** ✓（与主会话预判一致） | `MirrorStore.syncNow(...): Outcome`（base 已有），`MirrorStore:190` 内部已在消费；只有 UI 丢弃 | 纯 UI |
| U-03 | **A 型 + 一处 B 型补充** | ①`ImportResult` 已带 4 个计数字段（base 已有），弹窗不读 → A 型；②`ConfigBackup` 两处数据丢失分支只 `Log.w` 且不计数 → 补 B 型（计入已有的 `readFailures` 通道，**不动导出契约**） | UI + 两行计数 |
| U-04 | 缺陷（守卫写反了语义） | `selectMirror` 里 `if (useCustomMirror[cat] == true)` 才 apply，而该 flag 默认 false → 点选后勾亮了但没生效 | 改 `selectMirror` |
| U-05 | 缺陷（文案承诺 ≠ 行为）+ 刻意设计 | `autoSelectFastest` 的 `hasPriorSelection` 守卫是**刻意**的（首启检测依赖同一规则）；真正的缺陷是行里 `selected.name` + `fastest.latencyMs` 拼两个不同镜像 | 保留不切换，改文案 + 显式切换按钮 |
| U-07 | 缺陷 | `onRendererGone` / `onReceivedError` 只 `AppLogger.warning` + release，无任何用户反馈；同函数 `PrintManager` 异常路径**已经**会 Toast | 补 Toast |
| U-12 | **B 型** ✓（与主会话预判一致） | `importFromGitHub(...): Skill?`，子目录递归在 `backgroundScope.launch` 里，失败只 `Log.w` | 先改 API（复用 `UpdateResult`），再改两个 UI 面 |
| U-13 | **刻意设计（§5.4）** | `enabled = prior?.enabled ?: false` + 注释 `§5.4: brand-new tasks enter OFF` + dev-history 记录 | **不改默认值**，只加「保存后立即启用」开关 + 状态提示 |
| U-14 | **B 型** ✓（与主会话预判一致） | `upsertTask(...): ScheduledTasksFile`；写盘失败时 `mutate` 返回**写前**文档，编辑场景里该文档仍含该任务 ⇒ 存在性检查恒为 true | 先改 API（`WriteOutcome`），再改 UI |
| U-20 | **A 型** | `refreshRemoteCatalog` 返回带 `error` 字段的结果；UI 无条件弹成功 toast，`error` 只进 `AppInfo.info` | 纯 UI |

**没有一条判错**（主会话预判 U-02 = A、U-01/U-12/U-14 = B，全部核实成立）。**没有证伪条目**——10 条全部成立、全部动手。

---

## 1 逐条

### U-01 · P1 · 自动备份失败仍弹「已保存」· **B 型**

**先证**（两臂探针 `probe.py`，base 命中 / post 不命中）：

```
[U-01] OK  backup/AutoBackupManager.kt
        needle: 'suspend fun runNow(app: MinisApp) {'
        base=True (want True)  post=False (want False)   ← 返回 Unit，调用方无法分支
[U-01] OK  backup/AutoBackupManager.kt
        needle: 'val externalMirrorFailed = !mirrorOk && MirrorStore.isEnabled(app)'
        base=False (want False)  post=True (want True)   ← 外部镜像失败从「只记日志」变成可上报
[U-01] OK  ui/settings/BackupSettingsScreen.kt
        needle: 'val outcome = withContext(Dispatchers.IO) { autoManager.runNow(application) }'
        base=False (want False)  post=True (want True)   ← UI 以前丢弃返回值
```

**修法**
1. 新文件 `backup/AutoBackupRunOutcome.kt`：`Success` / `Skipped` / `Partial(unreadableFields, externalMirrorFailed, remotePushFailed)` / `Failed(reason)`，分类逻辑抽到 `AutoBackupRunOutcomePolicy.classify`（纯逻辑，JVM 可测）。
   - **没有压成 Boolean**：四个失败点语义不同（什么都没做 / 部分缺 / 整体失败），压平正是原缺陷的成因。
2. `runNow` 返回该类型；`CancellationException` 原样重抛（取消不是备份结果）；**本地快照抛异常 = `Failed`**（下面没有兜底层），其余走 `classify` → `Partial`，且**每一层降级都上报**（不只第一个）。
3. UI 分支：`Failed` → `errorMessage`；`Skipped` → 「已有备份正在运行，本次未写入」；`Partial` → 「已保存，但不完整：<逐项>」；`Success` → 原 toast。
4. `runAsync`（无人值守路径）改为按 outcome 记日志，不再 `catch`。

**注意点（我改过一版）**：最初我把 `mirrorAutoSnapshot` 调用包进 `if (MirrorStore.isEnabled(...))`——那会**改变行为**（禁用时原本仍会镜像）。改成「调用保持无条件，flag 只决定 false 是否值得上报」。

**证据**：`AutoBackupRunOutcomeTest` 7 臂（含「每层都上报」「负字段数不报」）→ `OK (7 tests)`，整体 18 tests OK。

---

### U-02 · P1 · 镜像重试丢弃 Outcome · **A 型**

**先证**：`syncNow(context, force = true): Outcome` 在 base 就存在（探针 arm 两臂都 True = 未改 API 的证明），`MirrorStore.runAsync` 内部已在消费。UI 那行把返回值丢了，外层 `catch (e: Exception)` 因此**永远不可达**（syncNow 自己吞 Throwable）。

**修法**：捕获 `outcome` 并按 6 个分支给反馈。`Busy` 明确**不归成失败**（它是正常并发保护）；`NoChanges`/`Throttled` → 「已是最新」；`Unavailable` → 复用已有 `mirror_diag_unavailable`；`Disabled` 该行不渲染（保留臂只为让 `when` 穷尽，新增 Outcome 会强制在这里做决定）。

**`SilentMirrorWiringTest` 规格核对**：该测试 `count(code, "syncNow(context, force = true)")` 断言「恰好 1 处」。我的改法把返回值接住但**调用形态逐字保留**，所以计数仍为 1 —— **不是规格变更**。实测 `SilentMirrorWiringTest` 5 臂全绿（`OK (31 tests)` 合跑）。

---

### U-03 · P1 · 恢复汇总漏报 4 类资产 + 导出部分失败不计数

**先证（两件事都成立，报告与任务书标题各说了一半）**
- 报告原文：`ImportResult` 有 `thinkingRulesImported` / `scheduledTasksImported` / `artifactFilesImported` / `webdavConfigImported`，弹窗一行都不读（探针 4 臂 base=False / post=True）。
- 任务书标题「导出部分失败仍报成功」：`ConfigBackup` 里确有两条数据丢失分支只 `Log.w`、不计入 `readFailures`（而 `readFailures` 是两条手动导出路径唯一会提示「可能不完整」的通道，现在也被 U-01 折进 `Partial`）。

**修法**
1. UI 补齐 4 行（非零才显示），沿用 `backup_done_*` 命名。
2. `ConfigBackup` 两处补 `readFailures++`：
   - `JSONObject(json)` 解析失败的 provider（原来整条从 payload 消失，恢复时只是「少了一个供应商」）；
   - 技能降级到 SKILL.md-only（无归档 / 归档超 `MAX_SKILL_ARCHIVE_BYTES`，丢失 `scripts/`、`references/`）。

**关于「优先改 UI 侧」**：这两处**不是导出契约**，是在既有分支上加计数器；9 个锚定门全绿（见 §3），没有破坏任何门的契约。

**证据**：新增 `BackupExportReadFailureAccountingTest` 4 臂，含每臂的 discriminating negative control。**把 ConfigBackup 回退到 base 再跑 → 4 条全红**（`Tests run: 35, Failures: 4`），修复后全绿。

---

### U-04 · P2 · 点选镜像不启用 · 缺陷

**先证**：`selectMirror` 里 `if (useCustomMirror[mirror.category] == true) { applyMirror }`，而 `useCustomMirror` 默认 false（`MirrorSettingsScreen.kt:249`）⇒ 点行 → 绿勾出现、顶部卡片仍显示「官方默认源」、容器配置未改，必须再找顶部开关。探针：base=True / post=False。

**修法**：`selectMirror` 无条件置 `useCustomMirror[cat] = true` + `persist` + `applyMirror`（off-main，与原 `T10-L7` 一致）。这正是 `[da1022-F]` 已经为测速自动选择确立的契约：「A selection recorded without useCustomMirror=true never reaches the rootfs」。

**门**：`mirror_vm_thread_check` arm 9（`selectMirror` 必须 DISPATCH 而非内联 IO）仍绿；`--selftest` 36 臂 PASS。

---

### U-05 · P1 · 行内「旧镜像名 + 新最快延迟」

**先证 / 判定**：`autoSelectFastest` 的 `hasPriorSelection` 守卫是**刻意设计**——`decideAutoApply`（首启检测）复用同一规则，且「测速后静默改掉用户手选的镜像源」是高风险动作。**真正的缺陷是行渲染**：`selected.name` + `fastest.latencyMs`，一行里两个不同镜像。

**修法**（按任务书「保持不切换，但改文案」）
1. 新增 `fastestSuggestion(selectedId, fastest)`（新文件 `MirrorRowDisplay.kt`，纯逻辑）：最快镜像 **≠** 当前选择时才作为建议返回。
2. 行内：选中镜像配**它自己的**实测延迟（新增 `latencyOf(category, mirrorId)`）；最快镜像另起一段**带名字**的「最快：X (12ms)」+ 显式「启用」按钮。
3. `MirrorAutoSelectTest` 的既有 8 臂语义未动（守卫未改）。

**证据**：`MirrorRowDisplayTest` 5 臂；变异 m3（去掉 id 比较）→ 只红 `theAlreadySelectedMirrorIsNotAdvertisedAsASuggestion`。

---

### U-07 · P2 · 打印失败无提示

**先证**：`onRendererGone` 与 `onReceivedError` 都只 `AppLogger.warning` + `releasePrintWebView`，无 Toast；同函数 `catch (e: Exception)` 分支**已有** `file_print_failed_toast`。

**修法**：两处各补 Toast。渲染进程消失用新 key `file_print_renderer_gone` 作为原因文案；加载失败直接透传平台 `error.description`（能区分「文件缺失」与「渲染器不可用」，比自造文案准确）。

**门**：`filepreview_print_bounded_check` 真树 + `--selftest`（7 witness arms）全绿——该门管的是「不得整文件读入堆」，与本次改动无交集。

---

### U-12 · P1 · 技能导入立即报成功 · **B 型**

**先证**：`importFromGitHub(...): Skill?` 返回即刻非空；`downloadSiblingFiles` 在 `backgroundScope.launch` 里跑，失败只 `Log.w`。探针：base 签名 `): Skill?` = True，post = False。

**修法**：签名改为 `UpdateResult`（**复用 `updateFromURL` 已有的三态类型**，不另起一套 —— 任务书要求「按 `skill_file_save_result_check` 钉的契约形状来修」）。子目录递归**仍跑在 `backgroundScope`**，只是在那里 `await`：取消等待 ≠ 取消下载，`[T152]` 的修复（导航离开不再截断递归）完整保留。两个 UI 面（导入面板 / 技能浏览器 HUD）按结果分支；`PartialSuccess` 保留面板不自动消失，让原因可读。

**测试改动（规格变更，已在提交说明里写明）**：`SkillImportPreferredIdTest.importFromGitHub_signature_hasPreferredIdParameter` 的返回类型锚点从 `): Skill? =` 改为 `): UpdateResult =`（`preferredId` 透传这条契约不变），并**新增两条断言**：失败不得返回裸 null、`PartialSuccess` 必须可达。

---

### U-13 + U-14 · P1 · 定时任务编辑页（同文件，合一条 commit）

**U-13 先证**：`:343` `enabled = prior?.enabled ?: false` + 注释 `§5.4: brand-new tasks enter OFF`，dev-history 同记 ⇒ **刻意设计**。按主会话方案：**不改默认值**。
- 新增 `ScheduledTaskEditorPolicy`（纯逻辑，JVM 可测）：`enabledOnSave(priorEnabled, enableAfterSave)`（编辑恒用存量状态，绝不被开关改掉）+ `noticeAfterSave(taskEnabled, globalEnabled)`（全局关优先）。
- 编辑页加「保存后立即启用」开关（**仅新建时显示**，默认关）。
- 保存成功且落盘后：全局关 → 「已保存。定时任务的全局开关是关闭的…」；单任务关 → 「已保存。任务处于关闭状态，请在列表里打开开关。」；都开 → 不说话。

**U-14 先证**：`mutate` 写盘失败返回**写前**文档；编辑场景该文档仍含目标 id ⇒ `saved.tasks.any { it.id == effectiveId }` 恒 true ⇒ `shouldMarkMissing` false ⇒ `onDone()`。**任务书要求的"先确认能不能区分"结论：不能**（只有 `saved.tasks` 一个返回值）⇒ B 型。
- `mutateReporting` 返回 `WriteOutcome(file, persisted)`；`mutate` 保留旧形状作薄包装（4 个既有调用点零改动）。
- `upsertTask` 返回 `WriteOutcome`；编辑页 `!outcome.persisted` → 留在页面 + 显示写盘失败文案（复用拒绝删除那句，避免 7 语言新增）。
- `next === current`（transform 主动拒绝，如编辑目标已消失且 `allowInsert=false`）也记 `persisted = false`。

**证据**：`ScheduledTaskEditorPolicyTest` 6 臂；变异 m4（忽略开关）→ 只红 `aNewTaskStaysOffUnlessTheUserOptedIn`；m5（提示优先级反了）→ 只红 `bothOffIsReportedAsTheGlobalProblem`；m6（编辑被开关改状态）→ 只红 `anEditKeepsTheStoredStateAndIgnoresTheNewTaskSwitch`。

---

### U-20 · P2 · 刷新目录失败弹成功 toast · **A 型**

**先证**：`refreshRemoteCatalog` 返回带 `error` 的结果；UI 无条件弹 `provider_detail_refresh_catalog_result`，`error` 只进 `AppLogger.info`。探针：`if (error != null)` base=False / post=True。

**修法**：`error != null` → 「刷新失败：<reason>」（`LENGTH_LONG`），否则原计数 toast；info 日志保留两个数字。

---

## 2 证据（命令 + 实际输出）

### 2.1 两臂先证（`a-jvm/probe.py`）
对每条断言同时校验 **base 有缺陷形态** 与 **post 已消失**——单臂「post 里没有 X」不构成证据（X 可能本来就没有）。

```
$ python3 probe.py | tail -2
PROBE=PASS — every defect shape is present in base and gone in post
```
（22 条 arm，逐条输出 base/post 布尔值与期望值；U-04/U-05/U-13/U-14 为 base=True→post=False，U-01/U-03/U-12/U-20 的修复形态为 base=False→post=True，A 型条目另加「API 未变」的双 True arm 作对照。）

### 2.2 纯逻辑 JVM（`a-jvm/run.sh post`）
```
--- production file sha256 ---
ab2e17a16c8425989bbb3a14c926992a7e9b0dc638a46bc4e4ba126670199e11  AutoBackupRunOutcome.kt
fe6728cb62bbcaf576dba54cbbbe65f029691fc86aedb92e266e77843b205be3  MirrorCategory.kt
91a2270bd1d22f30a79bde84259b63ba2e774739e95435aba9bde3474808839d  MirrorRowDisplay.kt
250cb60d8fd6569fd6cb2f354f502b0d50a5b7137d118deafa42247f6f82bb8e  ScheduledTask.kt
2fe1d288d8c0411f4de36649b56a5c8121be1db28838497942a883f2ac9cfcc8  ScheduledTaskPolicy.kt
OK (18 tests)
```

### 2.3 变异负控（`a-jvm/mutate.sh m1..m6`）——各自只命中自己那组
```
m1 (classify 恒 Success)      → Failures: 4  (4 条 Partial 臂)
m2 (只上报第一层降级)          → Failures: 2  (everyDegradedLayer… + remotePush…)
m3 (建议不比较 id)            → Failures: 1  (theAlreadySelectedMirrorIsNotAdvertised…)
m4 (忽略「保存后启用」开关)     → Failures: 1  (aNewTaskStaysOffUnlessTheUserOptedIn)
m5 (提示优先级反转)            → Failures: 1  (bothOffIsReportedAsTheGlobalProblem)
m6 (编辑被开关改状态)          → Failures: 1  (anEditKeepsTheStoredState…)
```
零串扰。

### 2.4 source-shape + 既有测试（`a-jvm/shape.sh`）
```
OK (31 tests)   SkillImportPreferredIdTest + SkillBrowserUpdateTargetTest + SilentMirrorWiringTest
OK (35 tests)   + BackupExportReadFailureAccountingTest
```
**基线负控**：把 `ConfigBackup.kt` 回退到 base 后重跑 →
```
2) aSkillDegradedToSkillMdOnlyCountsAsAReadFailure(...)
3) theProviderAccountingProbeHasDiscriminatingPower(...)
4) theSkillAccountingProbeHasDiscriminatingPower(...)
FAILURES!!!  Tests run: 35, Failures: 4
```
（4 条新断言在基线上全红 = 真的在钉这次修复。）

`mirror_check.py`（镜像桩字段对拍，防窄桩假绿）：
```
MirrorEntry: real=7 mirror=7 missing=[] extra=[] type_drift=[]
MirrorTestResult: real=3 mirror=3 missing=[] extra=[] type_drift=[]
MIRROR_CHECK=PASS
```

`slice.py`（从真实文件切纯函数，逐行校验与源一致）：
```
SLICE=PASS — every sliced line is present verbatim in the real source
```

### 2.5 门（真树 + selftest，**19 个全绿**）

任务书 §3 的 10 个 + ConfigBackup 相关的 9 个（`discarded_return_guard` 用的是它的 `--self-test` 拼写）：

| 门 | 真树 | selftest（带牙标记） |
|---|---|---|
| backup_streaming_export_check | OK | `BACKUP_STREAMING_EXPORT_SELFTEST=PASS` |
| mirror_vm_thread_check | OK | `MIRROR_VM_THREAD_SELFTEST=PASS (36 arms)` |
| mirror_category_route_check | OK | `MIRROR_CATEGORY_ROUTE_SELFTEST=PASS (24 arms)` |
| skill_file_save_result_check | OK | 5/5 + offmain 3/3 + round3 4/4 |
| skill_import_offmain_check | OK | 17 arms |
| skill_frontmatter_roundtrip_check | OK | 23 arms |
| scheduled_store_release_guard | OK | `SCHEDULED_CANSAVE_SELFTEST=PASS` 等 |
| schedule_date_source_check | OK | `SCHEDULE_DATE_SOURCE_SELFTEST=PASS (35 arms)` |
| filepreview_print_bounded_check | OK | 7 witness arms |
| provider_remember_key_check | OK | `PROVIDER_REMEMBER_SELFTEST=PASS (141 arms)` |
| backup_remap_rollup_check | OK | `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` |
| discarded_return_guard | OK | `DISCARDED_RETURN_GUARD_SELFTEST=PASS (93 arms)` |
| envvar_config_group_check | OK | `ENVVAR_GROUP_SELFTEST=PASS` |
| mcp_oauth_lifecycle_check | OK | `MCP_OAUTH_LIFECYCLE_SELFTEST=PASS` |
| openminis_compat_field_parity_check | OK | `OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=PASS` |
| parts_json_funnel_check | OK | `PARTSJSON_FUNNEL_SELFTEST=PASS (48 arms)` |
| provider_import_id_roundtrip_check | OK | `PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS` |
| provider_ua_parity_check | OK | `PROVIDER_UA_PARITY_SELFTEST=PASS (60 arms)` |
| i18n_check（附加，见 §4） | OK | `I18N_CHECK_SELFTEST=PASS (20 fixtures)` |

### 2.6 i18n（7 语言 key 对齐）
新增 **21 个 key × 7 语言**（`values` / `zh` / `zh-rTW` / `ja` / `ko` / `de` / `ru`）：
```
values         keys=1651 diff=[]
values-zh      keys=1651 diff=[]
values-zh-rTW  keys=1651 diff=[]
values-ja      keys=1651 diff=[]
values-ko      keys=1651 diff=[]
values-de      keys=1651 diff=[]
values-ru      keys=1651 diff=[]
```
（key 集合逐语言完全一致；`i18n_check` ALL CLEAN，无 orphan。）

### 2.7 云端 CI（run `43537882646`，event = workflow_dispatch）

`head_sha = 49350e0b852ec4eaeed8e136a474d5793f38543d`，与本地 `git rev-parse HEAD` **逐字符一致**；五 job 全 `success`。
（同 sha 的另一次 dispatch `43537178876` 被并发策略 `cancelled`，与结论无关。）

**scan-gate**（job `132271294106`）：
```
Summary: 115 passed, 0 failed
##[notice]scanners moved in this push (or the diff was unreadable) — running the fixture self-test
ALL 356 CASES PASS
```
（`scanners moved` 计数 = 1、`scripts/scan/** untouched` = 0 ⇒ **夹具真跑**；本批虽零门改动，但基线里有别的门脚本在同 push 内变动，所以自检照跑。115 个 header 全部执行，`━━━ [N/115]` 计数 = 115。）

本批锚定的 19 个门在 CI 日志里**逐条按编号找到并确认有 OK/PASS 输出**：
`[96] backup streaming export` · `[81] mirror vm thread/state` · `[69] mirror category detail route wiring` · `[71] skill file save result + IO dispatch` · `[92] skill import off-main` · `[91] skill frontmatter roundtrip` · `[28] Scheduled-task store release guard` · `[42] Schedule date source guard` · `[66] filepreview print bounded read` · `[104] provider_remember_key_check` · `[49] backup remap + rollup` · `[20] Discarded-return guard` · `[103] envvar_config_group_check` · `[98] MCP OAuth lifecycle` · `[79] openminis compat field parity` · `[57] parts_json funnel` · `[68] provider import id + backup field parity` · `[55] provider customUserAgent parity` · `[2] i18n consistency check`（`✅ No orphan keys (1640 refs, 1653 defs)`）。

**unit-tests**（job `132271293701`）：`BUILD SUCCESSFUL in 1m 41s`，**9139 条用例，0 条 FAILED**（6 行含 `FAILED` 的全是用例名子串，如 `reason PERSISTENCE_FAILED maps to persistence_failed PASSED` —— 判读陷阱第 N 次复核）。

本批 4 个新测试类 **22 条用例逐条在册 PASSED**：
```
AutoBackupRunOutcomeTest (7)             cleanRunIsSuccess / unreadableFieldsAlone… / externalMirrorFailureAlone…
                                         remotePushFailureAlone… / everyDegradedLayerIsReported… / skippedAndFailed…
                                         aNegativeFieldCountIsNotReported
BackupExportReadFailureAccountingTest(4) anUnparseableProviderCounts… / aSkillDegradedToSkillMdOnlyCounts…
                                         theProviderAccountingProbeHasDiscriminatingPower / theSkill…Power
ScheduledTaskEditorPolicyTest (6)        aNewTaskStaysOff… / anEditKeepsTheStoredState… / aGlobalOffSaveSaysSo…
                                         aSavedButDisabledTask… / anEnabledTaskUnderAnEnabledMaster… / bothOff…
MirrorRowDisplayTest (5)                 aDifferentFastestMirror… / theAlreadySelectedMirrorIsNotAdvertised…
                                         nothingSelectedStillOffersTheFastest / aFailedFastest… / noMeasurement…
```
被改动的既有测试也在册：`SkillImportPreferredIdTest` 13 · `SilentMirrorWiringTest` 9 · `SkillBrowserUpdateTargetTest` 9。

`lint` / `build-apk` / `publish` 三个 job 均 `success`。

---

## 3 禁碰核对

- `scripts/scan/**`：**零改动**（`git diff --stat acb8bdd..HEAD -- scripts/` 为空）。
- B 支独占文件：`FileBrowserScreen.kt` / `FileBrowserViewModel.kt` / `StorageManagementScreen.kt` / `LogManagementScreen.kt` / `RuntimeLimitsScreen.kt` / `ModelEntryDetailScreen.kt` / `ModelEntryOverrideBuilder.kt` / `AddCustomModelScreen.kt` —— **一个没碰**。
- C 支独占：`SoulSettingsScreen.kt` / `AppearanceScreen.kt` / `EnvVarRepository.kt` / `TerminalSession.kt` / `MCPIntegrationsScreen.kt` / `MCPRepository.kt` —— **一个没碰**。
- `EnvVarRepository.kt`（R8 并集里 A 支改过）—— 未碰。

---

## 4 遗留 / 需下游知道的事

1. **提交顺序与 hunk 归属**：`BackupSettingsScreen.kt` 由 U-01/U-02/U-03 共改，已按 `[RC-Uxx]` 标记逐 hunk 拆进 3 条 commit（`stage.py` 的行级 patcher 完成，每步都跑 `i18n_check` 确认无 orphan）。tip 树与最终快照**逐字节一致**，唯一差异是 strings.xml 里 key 的**行序**（集合相同，见 §2.6）——因为分步脚本按「累计 key 顺序」重写该文件。
2. **`SilentMirrorWiringTest` 未改**（规格未变，见 U-02）。`SkillImportPreferredIdTest` **改了**（返回类型锚点 + 2 条新断言，提交说明已写明是规格变更）。
3. **U-05 未改自动切换**：`autoSelectFastest` / `decideAutoApply` 的守卫原样保留。若产品后续想「测速后自动切换」，那是独立决策（需重新评估首启检测的复用），不是本批范围。
4. **U-01 的 `mirrorAutoSnapshot` 调用语义未动**：禁用状态下仍会尝试镜像（原行为），`isEnabled` 只决定「false 是否上报为失败」。若认为禁用时不该调用，是另一处改动。
5. **`readFailures` 语义扩宽**：现在也包含「provider 无法序列化」「技能降级为 SKILL.md-only」。两条手动导出路径的提示文案（`backup_export_incomplete` / `webdav_uploaded + 警告`）语义仍然正确，只是覆盖更全。
6. **未做 UI 级验证**：Compose 无法在 JVM 跑，UI 分支的正确性靠 source-shape + 纯逻辑测试 + 两臂探针。真机验证（尤其 U-01 的 4 种 toast 分支、U-05 的行布局宽度）需要装包确认。
7. **装置位置**：`/var/minis/shared/work/rc-1114/a-jvm/`（`probe.py` 两臂探针 / `run.sh` 纯逻辑 / `shape.sh` 形态 / `mutate.sh` 变异 / `mirror_check.py` 桩对拍 / `slice.py` 切片 / `stage.py` 分步提交）。均为**一次性脚本，不入仓**。
