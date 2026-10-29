# FIX-R5-D2：导航与崩溃包 3 项（R5 修后复扫批）

分支 `fix/r5-d2-nav-crashpkg` ｜ 基线 main `f3def6ce` ｜ worktree `shared/work/r5-1029/wt-d2`

## §1 缺陷与根因（按语义回本树核实，不采信报告行号）

| ID | 报告声称 | 回本树的核实结果 |
|---|---|---|
| D-F-06 | `KEY_LAUNCH_SESSION==2` 时 `onStart` 无条件排 route、`onResume` 立即清栈，导致相机/选择器/SAF/系统设置返回以及 `onNewIntent` 深链跳会话后用户被扔进空白新会话 | **成立**。`onStart` 里唯一的闸是 `hasResumedFromBackground`（只在**首次** ON_START 放行一次），之后**每次** ON_START 且 `mode==2` 都会 `pendingLaunchSessionRoute = newRoute`；`onResume` 无条件消费它并 `popUpTo(nav.graph.startDestinationId) { inclusive = true }`。子流程返回（相机/选择器/SAF/设置页）走 onStop→onStart→onResume，正好命中；后台态 `onNewIntent`（`minis://session/<id>`）在 onStart **之前**投递，于是 `handleDeepLink` 刚导航到的会话被随后排入的 draft route 顶掉。冷启一侧早有对称守卫（`AppNavigation` 的 `if (hasDeepLink) return`），warm 一侧从来没有。 |
| D-C-09① | 真实 SAF 保存路径 `crashSaveLauncher` 写失败不删 0 字节目标文档、不提示、仍 `finishClose` 销账 | **成立**。`ACTION_CREATE_DOCUMENT` 在用户确认文件名那一刻就创建了目标文档，catch 里只有一句 `Log.w`：目标 0 字节文件留在用户目录里、名字与真包一致，失败对用户完全不可见（这正是「报保存失败」被回成「没有报错，只有一个空文件」的机制）。`finishClose` 与 detector 自身 dispatch 路径的失败语义一致（toast + finishClose），故保留。 |
| D-C-09③ | `packageZip` 源文件全读失败仍返回 22 字节空 zip 报成功 | **成立但表述需校正**。`readable` 提前过滤了 `exists() && length() > 0`，所以「文件全不存在」已经被挡住；真窗口是「存在但读失败」（权限/IO 错/被并发清理）——逐条 `catch` 故意跳过坏条目，当**每一条**都失败时循环照常结束，产出的正是 22 字节 EOCD 记录，被调用方当成成功包 `Saved logs to …`。 |
| B-F-02① | 进入草稿时 `record` 早退**不清**旧槽位 ⇒ 崩溃恢复到已离开的旧会话 | **成立**。`CrashRecoveryStore.record` 在 `shouldPersistCrashRecovery` 为假时直接 return，而 nav 监听对草稿键（`__new__<uuid>`）正好走到这条分支：草稿不写（正确，无行可恢复），但上一个会话的记录被**原样保留**（错误）。 |
| B-F-02② | 草稿晋升真实会话后全链路无 `record` 调用 ⇒ 新会话全程不可恢复 | **成立**。`record` 全仓只有一个调用点（MainActivity 的 nav 监听），而该监听在草稿阶段发射过一次后，晋升只走 `ChatViewModelStore.rename`（改别名表，路由参数终身停在 `__new__<uuid>`）⇒ 不会有第二次发射。 |
| D-C-09②（登记不修） | SAF 选择器期间转屏丢包重弹 | 需跨重建持有 `pendingCrashZip`，与本支①不同一处、成本不为零 ⇒ 只在 §7 登记。 |

## §2 修法（每条一句可复核）

- **D-F-06**：加两个**一次性**抑制标记，在「决定发生的地方」arm、在 `onStart` 消费：
  - `selfLaunchedExternalUi`：`startActivityForResult(Intent, Int, Bundle?)` override 里 `super` **之后**置位（ActivityResultRegistry 的所有 launcher 与两个 `startActivity` 重载最终都汇到这一个方法；`super` 之后置位 ⇒ 启动抛异常 = 没离开 = 不 arm）。
  - `incomingWakeIntent`：`onNewIntent` 且「share 或 可解析深链」且「当前 lifecycle **不是** RESUMED」时置位（RESUMED 态没有后续 ON_START，arm 了只会误伤下一次真正的后台回归），同时 `pendingLaunchSessionRoute = null`。
  - `onStart` 守卫在 `mode` 读取之后、`if (mode != 2) return` 之前，两个标记任一为真即清空二者 + 清 pending + return；冷启首帧同样清一次，防陈旧标记吃掉第一次真回归。
- **D-C-09①**：catch 里补 `rollbackCreatedDocument(targetUri)`（先 `DocumentsContract.deleteDocument`，失败再 `contentResolver.delete`，两者都 best-effort）+ `Toast` 复用既有 `crash_freq_save_failed`（7 locale 已全）。
- **D-C-09③**：zip 循环加 `entriesWritten` 计数，写完 `closeEntry()` 才 `++`；为 0 时 `zipFile.delete()` + `return@withContext null`（`withContext` block 是 crossinline，只能用标签返回），交给调用方**既有**的 `?: run { zip_failed toast }` 分支。
- **B-F-02①**：`CrashRecoveryStore` 引入 `SlotWrite { WRITE / CLEAR / IGNORE }` 与纯函数 `slotWriteFor(sessionId)`（真会话=WRITE、草稿=CLEAR、空白=IGNORE），`record` 按表执行；新增 `clear(context)`，`clearIfMatches` 改为「比对后委托 clear」（删除侧语义不变）。
- **B-F-02②**：`ensureSession()` 在 `realSessionId = session.id` **之后**立即 `CrashRecoveryStore.record(context.applicationContext, session.id)`。

**这改变什么用户行为？**
1. 开着「新建对话」启动模式时，从相机/相册/文件选择器/设置页回来**不再**被扔进空白新会话（原会话与正在输入的草稿都还在）；
2. 后台通过通知深链（`minis://session/<id>`）唤醒，落地在**那个会话**而不是被替换成空白草稿；
3. 存崩溃包失败时用户**看见**失败提示，目录里不再留下一个与真包同名的 0 字节假包；
4. 所有崩溃日志都读不出来时提示「打包失败」，而不是「已保存」；
5. 进入新对话后崩溃/被 LMK 杀掉，恢复目标**不再是**用户已经离开的旧会话；新对话发出首条消息后，崩溃可恢复到**这个**新会话。

## §3 改动清单

| 文件 | 规模 | 内容 |
|---|---|---|
| `app/src/main/java/com/rikkaminis/app/MainActivity.kt` | +129/−5 | 两个抑制标记 + `onStart` 守卫 + `onNewIntent` 决策 + `startActivityForResult` override + `rollbackCreatedDocument` + SAF catch 分支 |
| `app/src/main/java/com/rikkaminis/app/CrashRecoveryStore.kt` | +53/−5 | `SlotWrite` 决策表 + `slotWriteFor` + `clear` + `record` 按表执行 + 类注释 |
| `app/src/main/java/com/rikkaminis/app/crash/CrashFrequencyDetector.kt` | +14/−0 | `entriesWritten` 计数 + 空包删除并返回 null |
| `app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionEntryExt.kt` | +14/−0 | 晋升处 `CrashRecoveryStore.record` |
| `app/src/test/java/com/rikkaminis/app/CrashRecoverySlotRecordTest.kt` | 新增 8 例 | 真 `CrashRecoveryStore` 对内存 `SharedPreferences`（`ContextWrapper(null)` + 单 override）+ 晋升写点形状 |
| `app/src/test/java/com/rikkaminis/app/LaunchSessionRerouteShapeTest.kt` | 新增 8 例 | MainActivity 导航时序形状 + 6 条内建负控 |

**未新增字符串**：失败提示复用既有 `crash_freq_save_failed`（values / zh / zh-rTW / ja / ko / de / ru 七份早已齐备），故无 i18n 增量。

**门脚本 / `scan.sh` / `test_scan.py` 零触碰**：门 113 / 夹具 349 与基线一致。

## §4 门判据与带牙证明（单门跑，不跑全量）

| 门 | 结果 | 备注 |
|---|---|---|
| `chat_session_id_binding_check.py` | `all criteria satisfied` | 判据 9/10/11（MainActivity 草稿 id 单源、nav 监听赋值、onSaveInstanceState）全绿 |
| `deeplink_settings_routes_check.py` | `DEEPLINK_SETTINGS_ROUTES_CHECK=PASS` | 判据 4（`runCatching { nav.navigate(action.route) }` 兜底）仍在 |
| `share_dir_prune_check.py` | `OK (arm1..arm6 green)` | arm4 的 `packageZip` 形状（suspend + IO + prune < zip + **禁裸 `return`**）在加计数后仍绿 —— 空包返回用 `return@withContext`，正是该禁令指向的写法 |
| `open_url_broker_scheme_check.py` | `all 23 criteria green` | U6 热深链分支 |
| `debug_leak_guard.py` | 2/2 ✅ | MainActivity 的 debug 引用面 |
| `skill_restore_session_cleanup_check.py` | `PASS` | `promoteDraftIfNeeded` 的 durable-write 顺序判据 |

## §5 验证四层

**层 1 · 沙箱 JVM（真生产切片，非镜像）**：`kotlinc` 单次编译 `stub/AndroidContent.kt`（手抄 android.content 全成员镜像）+ 真生产 `ChatRoutePresence.kt` / `LaunchSessionPolicy.kt` / `CrashRecoveryStore.kt` + 真测试源（含仓库既有 `FakeSharedPreferences`），`JUnitCore` 跑 **16 例全绿**。装置在 `shared/work/r5-1029/d2-probe/`。

**层 2 · 三条突变臂（同一个测试文件、同一装置，只换被测源）**：

| 臂 | 突变 | 结果 |
|---|---|---|
| M1 | `record` 的 `SlotWrite.CLEAR` 退回成裸早退（保留枚举，只退行为） | 红 1 例：`entering a composer draft drops the recorded chat` → `expected null, but was:<6f97e418-…>` ✅ MATCH |
| M2 | 删掉 `ensureSession` 里的 `record` 调用（源码副本 + cwd 切到副本根） | 红 2 例：`ensureSession must record the promoted session` + 负控用例 ✅ MATCH |
| M3 | 删掉 `onStart` 的抑制守卫整块（还原成修前形状） | 红 3 例：`shipped MainActivity passes every anchor` → `[onStart: no suppression guard]` 等 ✅ MATCH |

**层 3 · 结构核对**：4 个生产文件括号/引号配平（状态机扫描，含注释/字符串/raw string）全 `BALANCED`。

**层 4 · 云端 CI**（见 §6）：`compileReleaseKotlin` + `testReleaseUnitTest` 覆盖 Android 壳（`DocumentsContract`/`Toast`/`ActivityResult` override 的真实编译面，沙箱桩无法证明的一层）。

**证据边界（诚实声明）**：
- 导航时机（`onStart`/`onResume`/`onNewIntent`/`startActivityForResult` 的真实调用序列）**没有真机证据**，只有「源码形状 + 编译」两级；sdk 的 ActivityResultRegistry 汇流点（`ActivityCompat.startActivityForResult` → `Activity.startActivityForResult(3 args)`）是按 AOSP 语义判定的，未在真机验证。
- `packageZip` 的空包分支只有编译级 + 门级证据（它需要 `Context.cacheDir` 与真实文件系统，沙箱不建镜像装置）。
- SAF 回滚只有编译级证据（`DocumentsContract.deleteDocument` 的 provider 行为依机型而异，代码是 best-effort）。

## §6 CI

**run `40856282281`**（`Build RikkaMinis Android APK` #2904，**success**，2026-10-29T10:16:43Z → 10:38:51Z ≈ 22.1 min）
**head_sha `55157e5063c89590e1fcfbf8230612501ada463d`** 与本地 `git rev-parse HEAD` **逐字符一致**。

| 判据 | 读数 |
|---|---|
| job 步骤 | **27 步，0 个非 success**；skipped = 2（`Move the rolling tag android-latest` + `Publish to Releases`，均 main 门控 —— 分支构建的正确形态） |
| Kotlin 编译 | `^ e: ` 行 **0** |
| 单测 | `Summary: … passed, 0 failed` **7 条**（25 / 16 / 44 / 19 / 30 / 18 / **113**），`passed, [1-9]` **0 条** |
| 门集 | `Summary: 113 passed, 0 failed` ×1（与基线 **113** 一致，本支未加门） |
| 夹具 | `ALL 349 CASES PASS` ×1（与基线 **349** 一致） |
| 新用例 | `CrashRecoverySlotRecordTest` / `LaunchSessionRerouteShapeTest` 各有 8 处日志，逐名确认 `PASSED`（如 `entering a composer draft drops the recorded chat PASSED`、`shipped MainActivity passes every anchor PASSED`） |
| 日志 | 1,631,835 B，**NUL 0**；含 `FAILED` 的行全部是 `PASSED` 用例名子串（真 FAILED **0**） |

**停止节点达成**：分支 CI 绿 = 本支结束，**未合 main**（合 main 起手权在用户）。

**报告提交后的等价性判据**：`git diff --name-only 55157e50..HEAD -- . ':(exclude)fix-reports'` 必须为空（报告提交在绿之后，head_sha 与 tip 不同是正常形态）。

## §7 残留与登记（每条带触发条件）

- **R1 · `packageZip` 空包分支无行为装置**：证据停在编译级 + arm4 形状级。触发条件：若该分支日后被改动且需要行为证据 → 用 `File` 级镜像装置（把 `cacheDir` 换成临时目录）补一条。
- **R2 · 一次性抑制会吃掉「从别的应用回来」那一跳**：`selfLaunchedExternalUi` 在用户从被启动的应用里按 Home 离开、之后才从最近任务回到本应用时仍处于置位状态 ⇒ 这一次「新建对话」重排被抑制（下一次恢复正常）。刻意不修：区分「用户从对面应用回家」与「用户从对面应用回本应用」在 Activity 侧没有可用信号；代价（少一次重排）远小于收益（不再误清栈）。
- **R3 · 保存失败仍 `finishClose`**：失败后对话框关闭、无重试入口（与 detector 既有 dispatch 失败语义一致）。报告 D-C-09① 的修法方向未包含此项。触发条件：若用户报告「失败了想再选一个位置」→ 改为失败时不 `finishClose`，保留对话框。
- **R4 · D-C-09② 转屏丢包（登记不修）**：SAF 选择器期间转屏，`pendingCrashZip` 随 Activity 重建丢失 ⇒ 选择器返回后无包可写、静默关闭。修法需跨重建持有（`onSaveInstanceState` 存路径或 VM 持有），与本支①不同一处。触发条件：真机出现「选完位置没反应」报告。
- **R5 · 恢复时间戳只在入场/晋升时刷新**（报告 B-F-02 的第三个面，未分配到本支）：在**已有**会话里连续聊满 15 分钟再被杀，AUTO 窗口按入场时间判定为陈旧 ⇒ 不恢复。修法需要「聊天活动刷新时间戳」的额外写点（每 N 条消息或每次 turn 结束），会引入新的写放大，需产品判断。触发条件：用户报告「聊了很久崩了没恢复」。
- **R6 · 后台队列 VM 的理论误写窗口**：`ensureSession` 的 record 只在**草稿晋升**时执行，而草稿 VM 必是用户刚发首条消息的那个会话；唯一窗口是「发完立刻切到另一个会话（毫秒级）」时 record 晚于 nav 监听落盘，恢复目标短暂指向刚发消息的会话，下一次路由变化覆盖。刻意不修（要修得让 VM 知道「我是不是可见」）。
- **R7 · 真机未验**：本支三条的运行时面（ActivityResult 汇流、SAF 删除、深链时序）均未上真机。

## §8 身份链

- 来源报告：`shared/work/r5-1029/reports/R5-D.md`（F-02 / F-05 / F-06，基线 `f3def6ce`）
- 判级：`shared/work/r5-1029/TRIAGE.md`（D-F-06 M10 / D-C-09①③ / B-F-02）
- 任务书：`shared/work/r5-1029/tasks/TASK-R5-D2.md`
- 装置：`shared/work/r5-1029/d2-probe/`（stub + 编译/运行命令）
- worktree：`shared/work/r5-1029/wt-d2`（分支 `fix/r5-d2-nav-crashpkg`）
