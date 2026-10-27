# 交付报告：feat/scheduled-tasks-l0-1004（定时自主任务 L0）

> 2026-10-04。简报：`/var/minis/shared/work/task-brief-1004-B-scheduled-tasks-l0.md`（规格唯一权威）。
> 分支 `feat/scheduled-tasks-l0-1004` @ `9f9bb630` + merge `6eecc9b8`（origin/main @ 66cac97a），已推送 origin。
> 前身：中断会话交接 `HANDOFF-2026-10-04-scheduled-tasks-l0-1004.md`（5 文件 WIP 已由本会话续完）。

## 0. TL;DR

- 简报 §6 施工清单 1–4、6 全部落地；§6.5 按条件句跳过（任务书 A 已于同日撤回，见 backlog §55）；§9 三件套中报告/backlog/dev-history 已交，**真机清单交用户，agent 不自宣闭环**。
- §7 阶梯：JVM 一级 ✅（35 测试 + 熔断变异负向对照变红）；静态门禁 ✅（i18n 从红转绿，四层同步绿；debug 守卫仅剩 2 处 **main 基线遗留**，与 B2 一并登记）；**CI ✅（run 37213523354 全绿）**；真机 = 待用户逐项验。

## 1. 接线点签名核对结果（简报 §2 表格逐项）

| 组件 | 简报/交接说法 | 实测（以文件原文为准） | 结论 |
|---|---|---|---|
| 前台 hook | `MinisApp.kt:811-818` AutoBackup 调用点旁 | `onActivityStarted` 内 `if (wasBackgrounded) { AutoBackupManager.runIfDue(...) }` 区段，同款追加 | ✅ 一致，已接 |
| 结算链 | `MinisApp.kt:774-776` 单 listener | `setCompletionListener { ... }` 内追加 runner 钩子（B5：不设第二 listener） | ✅ 已接 |
| 派发链 | `ChatMutationMethods.prompt`（wait=false） | 签名 `(Context, JSONObject)`，status "Running" 判定 | ✅ runner 既有实现吻合 |
| 预算 | `AgentExecutionBudget`（字段 :25-34） | `agent/runtime/AgentExecutionBudget.kt`，`maxEstimatedTokens: Long?` | ✅ 接线见 §2 |
| 通知 | `BackgroundTaskNotifier.notifyWorkCompleted` | `(tag, title, body, deepLink)` | ✅ runner :251 既有实现吻合 |
| 共享配置区 | 「与 ProviderConfig 同级目录，施工时确认」 | `filesDir/minis-config/scheduled-tasks.json`（MountedFoldersStore 同目录惯例，在 DocumentsProvider 可见范围之外） | ✅ 已确认并注释 |
| `SettingsScaffold` | `onBack/actions/navigation` 槽位 | 全部吻合；另核实**列表页 onBack=null = 顶层子页无返回箭头**（README 原文枚举清单，且 RuntimeLimits 传非空 onBack 属历史例外，未效仿） | ✅ |

## 2. B3 核实结论（交接待决 ③）与引擎接线（4.1）

- **deadline 换算**：引擎字段是绝对单调时刻（`elapsedRealtime() + N分钟`），ceiling 是时长 → `now + min(user, ceiling)`，毫秒精确（非分钟取整）。`AgentLoopEngine.kt` 预算构建段（:258 前后）。
- **token 强制面（诚实边界）**：`consumeEstimatedTokens` / `tryReserveChildBudget` / `consumeChildTokens` 生产代码**零调用方**（grep 全仓核实）→ token 上限接线后仅进快照记账，**不被强制**；强制维度 = deadline + maxTurns（`:343`/`:363`/`:566`）。已照接线（引擎未来启用 consume 即自动生效），本报告为「§5.1 超限即终」的准确口径：**token 维度暂不闭合，deadline/turns 闭合**。
- 引擎行为：非 scheduled 会话 `lookup` 返回 null → 预算与原实现完全一致（`maxEstimatedTokens = null` 原样保留），行为零变化。
- **传递闭包惰性**：`maxEstimatedTokens` 非 null 时不影响其它维度（child/token 记账分支全部零调用方）。

## 3. 五条护栏状态（简报 §5）

| # | 护栏 | 状态 |
|---|---|---|
| 5.1 | 预算封顶 | ✅ deadline/maxTurns 引擎侧 min 强制；token 快照记账（见 §2 诚实边界） |
| 5.2 | 单飞跳过 | ✅ `@Volatile runningTaskId` + 持久 marker 双检；policy 测试覆盖 list-order 单飞与 overlap 降级；`skip reason=overlap` 日志接线 |
| 5.3 | 失败熔断 | ✅ `completeRun` streak≥3 → enabled=false + suspendedByFuse + 通知；恢复仅 UI（resumeTask）；负向对照已验 |
| 5.4 | 默认关闭 | ✅ `globalEnabled=false` 出厂 + 任务 `enabled=false` 出厂；编辑既有任务保留原 enabled（新建才强制 off）；双开关均需手动 |
| 5.5 | SENSE 打点 | ✅ `[scheduled-run]` 每判定一行（fire/skip/miss/day-dedup/done/fail/suspend/abort）+ `MemorySpikeRecorder.onEvent`；§8 数据源即此 |

**派发链与子代理开关**：照简报拍板执行，`ChatMutationMethods.prompt` 直连，不经 SubagentPrefs/minis-sessions-cli；runner 的 2 处 debug import 带内联豁免（B1，见 §5）。

## 4. 差异记录（简报 vs 实现，全部有依据）

1. **tasks.json 落位**：`filesDir/minis-config/`（简报「施工时确认」项）——不在 minis-global/，agent 与 DocumentsProvider 均不可见。
2. **备份载荷**：新增 `scheduledTasks` 段（B4 判定：**必须改**——minis-config 不在任何现有导出路径/artifactRoots）。四层同步：`ExportSections.scheduledTasks` + `export/exportToWriter` 参数 → `buildPayloadObject` 条件写入（null=省略）→ `frameKeys` 增补 → `import` 增加 **suspend 回调** `onScheduledTasks`（比照 webdavConfig 同步回调先例，调用方挂起调 store）+ `ImportResult.scheduledTasksImported: Boolean`。FORMAT_VERSION 不动（纯增量段，旧备份照常导入，测试锁定）。restore 时清 running 标记（进程本地语义）。
3. **UI 布局**：编辑页才放删除（AlertDialog 确认）；列表行 trailing = Switch + 熔断恢复按钮。新建走编辑页同屏（taskId=null），非独立向导——字段量少（7 项），同屏即向导，符合「最小可用」。
4. **编辑保留运行态**：lastRunDate/failStreak/suspendedByFuse/enabled 编辑时不重置（防「编辑重置当日门禁」与「编辑清熔断」两个暗坑）；新建强制 off。
5. **§6.5 跳过**：任务书 A 已撤回（负决策，backlog §55），条件句不成立。
6. **豁免语法修正**：交接 B1 的「import 行上一行加内联豁免」实测守卫 `strip_comments` 剥行尾注释 → 豁免必须是**独立注释行**（已按此落地，严格形式守卫验证通过）。
7. **测试文件归属**：codec/policy 测试 android-free 可本地跑；store/runner 的 JVM 测试依赖 android.jar，按交接口径由 CI `testReleaseUnitTest` 覆盖（本分支已含 35 个可跑测试，负向对照在沙箱实证）。

## 5. B1 审计登记（本分支新 debug 引用）

`ScheduledTaskRunner.kt:7-10`：
```kotlin
// debug-ok: scheduled dispatch goes through the same headless chain as SessionsOffloadHandler (brief §5.5, user-consented); no DebugServer/5321 markers in this closure
import com.rikkaminis.app.debug.ChatMutationMethods
// debug-ok: same audited scheduled dispatch chain (HeadlessChatRunner.ensureSession)
import com.rikkaminis.app.debug.HeadlessChatRunner
```
闭包核实：两文件及其 import（ChatViewModel/ChatViewModelStore/SessionBadgeStore/InputAttachment 等主 UI 固有类）不含 `DebugServer/5321/minis-debug` marker 串（grep 核实）；release 可达性（MinisApp 前台）先例 = SessionsOffloadHandler（在 ALLOWED_FILES）。R8 传递闭包未逐类核（marker 串 grep 为主要依据，如 CI apk 模式 guard 红则此处升级审计）。

## 6. 验证阶梯状态（简报 §7）

1. **JVM 单测 ✅**（本地）：35 tests OK（窗口判定/单飞/熔断/原子写损坏恢复/codec 往返）。**负向对照 ✅**：熔断变异为「永不熔断」（`streak < 0` + `fuseTripped=false`）→ 23 policy 测试中 2 红（`third consecutive failure trips the fuse` / `timeout outcome also counts toward the fuse`）——符合「必须红」纪律。
2. **静态门禁 ✅（含注意事项）**：18 门禁 = 17 绿 + i18n 绿（36 键 × 7 语言全同步，从交接时的红转绿）；debug 守卫严格形式 = 仅剩 2 处 **main 基线遗留**（`OkHttpNetTraceListener.kt:45`、`NetTraceRedactionTest.kt:6`，f4b9d3be/66cac97a 均红，fail-open B2 所致）——不在本分支修（纪律），B2 立项见 backlog §56。**CI 门禁**：scan-gate/build-apk 走 PR/main 触发，本分支已跑 workflow_dispatch（run 37213285493）。
3. **真机清单（交用户逐项验，逐条对应简报原文）**：
   - [ ] 建任务（窗口含当下）→ 杀 app → 重开 → 任务在独立会话跑完 → 通知到达 → 用户聊天未受扰
   - [ ] 同日重开 app → 只跑一次（lastRunDate 生效）
   - [ ] 故意造 prompt 令任务失败 ×3 → 自动挂起 + 通知 → UI 恢复后能再跑
   - [ ] 任务运行中杀 app → 重启后 lastRunDate 未推进 → 窗口内可重试
   - [ ] 全局开关关 → 无任何检查发生（`[scheduled-run]` 无日志）
   - [ ] （本次新增建议）导出备份 → 恢复到干净安装 → 任务列表完整、running 标记不复活
   - [ ] （本次新增建议）编辑已启用任务 → 保存后仍保持启用且当日不重跑

## 7. CI 状态与风险

- **run `37213523354`（workflow_dispatch @ cdaf6b49）：✅ SUCCESS**——pre-build scan gate / 全量单测（含 scheduled 35 测试与 backup payload 测试）/ instrumented compile gate / assembleRelease / APK 签名与内容验证全绿。
- 首轮 run `37213285493` 红（预期内的首次真实编译暴露）：`ExportSections` 非 data class 无 `copy()`（改为 buildSections 直传参数）+ 编辑屏漏 `MinisTextButton` import——均修复于 `cdaf6b49`。
- 合并路径：CI 已绿 → 开 PR（scan-gate 会再跑）→ squash/merge 由用户定。**分支含 merge commit（6eecc9b8），不 force-push。**
- 行号基线：本报告行号以 `cdaf6b49` 为准。

## 8. 文件清单（本会话改动，21 files +1450）

**新文件**：`scheduled/{ScheduledTask,ScheduledTaskPolicy,ScheduledTasksStore,ScheduledTaskRunner}.kt`、`agent/runtime/AgentRunBudgetCeilings.kt`、`ui/settings/{ScheduledTasksScreen,ScheduledTaskEditScreen}.kt`、`test/.../scheduled/{ScheduledTaskPolicyTest,ScheduledTasksCodecTest}.kt`
**修改**：`AgentLoopEngine.kt`（预算 min 接线）、`MinisApp.kt`（2 处 hook）、`SettingsScreen.kt`（入口行）、`AppNavigation.kt`（路由）、`ConfigBackup.kt`（四层）、`BackupSettingsScreen.kt`（3 导出 1 导入）、`AutoBackupManager.kt`（1 导出）、`ScheduledTasksStore.kt`（备份出入接口）、7 × strings.xml

## 9. 下一步

1. ~~等 CI~~ ✅ run 37213523354 全绿 → 开 PR（scan-gate 再跑）→ 用户拍板合并
2. B2 单独立项（backlog §56 已登记触发条件）
3. 合并后两周 `[scheduled-run]` 数据回收 → 按简报 §8 决定是否升级
4. 真机清单（§6.3）交用户逐项验——合并前或合并后均可，七项全过前 L0 不算交付闭环
