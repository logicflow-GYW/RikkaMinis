# FIX-DEAD-CODE-1027 — 零行为清理支（死 import / 死码 / 警告族 / 注释漂移）

- 分支：`chore/dead-code-sweep-1027`（基点 `origin/main` = `ad7893a34`）
- 工作树：`/var/minis/shared/work/backlog-drain-1027/wt-s3`
- 提交：`f799cbc1f`（user.dir 警告族）→ `5d6358597`（死 import）→ `db7371fe5`（死函数）→ `8b2e4d6c3`（5 处可空标注补齐）
- 总改动面：**243 文件 / +127 / −2799**（`src/main` 99 · `src/test` 142 · `src/androidTest` 2），`scripts/scan/**`、`scan.sh`、`test_scan.py`、`_kotlin_*` **零触碰**

---

## 1. 背景与范围

任务书给了三条起点项，并允许在「无法改变运行时行为」的前提下按同族扩面。本支的取舍只有一条主线：**凡不能由「编译」证伪的改动都不做**。因此：

- 做的：删掉不可能被引用到的符号（死 import / 零调用方 private 函数）、把一处「CI 已在报的可空性警告」按全族统一、修正被本次改动弄成假话的注释。
- 没做的：任何并发 / 生命周期 / 持久化 / 迁移 / 权限 / 取消语义相关的删除（即使它看起来是死码）。

## 2. 起点项逐条判决

### 2.1 §1021-H R3 死 import（`ChatModels.kt:52`）—— 删了

核实：`rg -n MinisOpenUrlBroker` 在全仓 8 处命中，其中 `ChatModels.kt` 只有 import 一行；同类命中在 `TerminalScreen.kt`（真用 5 处）、`DebugRPCHandler.kt`（全限定名直呼，不依赖 import）、`MinisOpenUrlBrokerSchemeTest.kt`（测试）。⇒ 本条成立。

删改后该 import 归入 2.2 的批量清理一起落地（同一语义）。文件头那行「Full import block copied (unused=warnings)」同时改掉（见 §3.3）。

### 2.2 §1035-A4-补 R9 / §1021-H R4 `user.dir` 可空性警告族 —— 111 站点统一，另补 5 处类型标注

**先定位「CI 到底在报哪些站点」**：拉 main CI `40413463677`（#2766）日志（1,499,650 B），`grep -c "Java type mismatch"` = **118**。逐条把 `文件:行:列` 回贴源码行：

| 归类 | 条数 | 处置 |
|---|---:|---|
| `File(System.getProperty("user.dir"))` 直接喂 `File(String)` | **111** | 统一形态 |
| 同一批 walk-up 循环的 `dir = dir.parentFile`（`var dir` 被推断成非空） | 5 | 补 `: java.io.File?` |
| `GuestPathParseMatrixTest:137`、`ChatTurnPartsJsonTest:277` | 2 | **不动**（不同族，见 §7 R1） |

同时用 `rg -c 'File\(System\.getProperty\("user\.dir"\)\)' src/android/app/src/test` 数出全仓 **111 处 / 107 文件** —— 与 CI 报出的集合**逐个相等**（`src/main`、`src/androidTest` 各 0 处），所以「只统一已存在且被 CI 报出的站点」这条约束是**机械满足**的，不存在我顺手扩到未报警站点的可能。

**统一形态**（全族一致，只有一个写法）：

```kotlin
System.getProperty("user.dir") ?: error("user.dir unset")
```

选 `?: error(...)` 而不是 `?.let { File(it) }` 的理由是硬条件：有两处站点的目标是**非空**类型

```kotlin
val root: File = candidates.firstOrNull { it.exists() }
    ?: File(System.getProperty("user.dir"))
```

`?.let` 在这两处返回 `File?`、类型不过；`error()` 的 `Nothing` 是唯一能覆盖全部 111 个站点的单一形态。运行时语义不变（`user.dir` 由 JVM 保证非空）；原来 `File(null)` 抛 NPE 的不可达分支，现在抛 `IllegalStateException`。

**补的 5 处**（`ChatBottomFollowTest` / `QueuedDrainPersistenceTest` / `ToolPassTransitionPolicyTest` / `TurnVisibleContentTest` / `UserRowVisibilityParityTest`）：这些 walk-up 循环写的是 `var dir = java.io.File(...)`，推断类型非空，而循环体里 `dir = dir.parentFile` 是 `File?` —— 于是同一行声明在 CI 里留下**第二条**警告（`:173/:189/:213/:184/:225`）。加 `: java.io.File?` 让声明与循环实际做的事一致，零行为变化（同初值、同循环、同 parentFile 上溯）。

### 2.3 §1026-B R3 `AccessibilityOffloadHandler` 二次构造漏 `booleanFlags` —— **核实后判定不成立，不改，只登记**

任务书要求先核实再动手。核实方式不是读代码猜，而是**编译真源码跑对照**：

- 装置：`probe/s3args/` —— 直接 `cp` 仓库里的 `OffloadArgs.kt` + `OffloadOutput.kt` 编译（只有 `AppLogger` 打了单方法空桩，`OUTPUT_FLAGS` 是真实值 `{compact, quiet, q, help, h}`），org.json 用真 jar。
- 方法：`notifySub` 是唯一一处 0-flag 重建（`:990` `OffloadArgs(newArgv)`），它构造的 argv 形态是固定的 `[event, <watch|once>, --package?, --text-contains?, --duration?, --timeout?, --type notification_state_changed, --compact?]`。枚举 **4000 种 argv**（含把 `compact` / `contains` / `inline` / `help` / `-5` 当**值**传的对抗样本），分别用「带 handler booleanFlags」与「不带」解析，逐字段对比。

结果：

```
cases=4000 mismatches=0
demo argv=[event, watch, --package, com.tencent.mm, --text-contains, contains, --duration, 5000, --type, notification_state_changed, --compact]
   noFlags  : pos=[event, watch] flags=[compact] package=com.tencent.mm text=contains type=notification_state_changed duration=5000 compact=true
   withFlags: pos=[event, watch] flags=[compact] package=com.tencent.mm text=contains type=notification_state_changed duration=5000 compact=true
```

原因也说清了：`OffloadArgs.init` 里 `effectiveBooleanFlags = booleanFlags + OffloadOutput.OUTPUT_FLAGS`，而 `notifySub` 唯一会发出的 flag 是 `--compact`（恒在 OUTPUT_FLAGS 里）；其余五个 key（`package` / `text-contains` / `duration` / `timeout` / `type`）在**两个集合里都不存在**，解析分支必然一致；handler 声明的 23 个 flag 名里没有一个是这五个 key。⇒ 该调用点缺 set 不产生任何可观察差异，它读的 flag 集合为空。

**判决：不是缺陷，不改。** 但它是一个「形态不一致」的隐患（下一个给 `notifySub` 加 flag 的人会踩），登记在 §7 R2。

## 3. 自行扩面（同族，全部编译可证伪）

### 3.1 死 import：2605 条 / 134 文件

（其中 1 条是起点项 §1021-H R3 手工删的，其余 2604 条由扫描器批量删除；下文数字均含那 1 条。）

判据（一条规则）：import 的末段符号在**文件正文**零出现即死。三个消歧点都做了：

- **注释与 KDoc 先抹掉**（否则 KDoc 里提一句 `[LaunchedEffect]` 就能让死 import 永不被发现）；
- **字符串保留**（宁可漏判，不可误删）；
- **隐式使用白名单**：`getValue`/`setValue`/`provideDelegate`（`by` 委托）+ 全部运算符约定名（`plus/minus/times/div/rem/inc/dec/not/unaryMinus/unaryPlus/contains/get/set/invoke/compareTo/rangeTo/rangeUntil/iterator/next/hasNext/equals/hashCode/toString` + `componentN`）——这些零文本出现也是必需的。

**第一版扫描器错得很有教育意义**：用正则 `/\*.*?\*/` 去块注释，在 Kotlin 里会被「字符串里的 `/*`」或「KDoc 里提到 `/*`」拖到很远的下一个 `*/`，一口气吞掉真代码。实测 `PRootKernel.kt` 从 75,282 B 被剪到 29,165 B，于是 `withContext`/`Dispatchers` 被判成死（而 `:276` 正在调用）。第一版报 2726 条 / 142 文件，**假阳性 122 条（4.5%）**。改用仓库自带的 `scripts/scan/_kotlin_lexer.analyse`（字符串感知、嵌套注释感知、等长视图）后：**2604 条 / 134 文件**，并且 `analyse` 解析失败的文件 **fail-closed 跳过**（本轮 0 个）。

分布（解释「为什么量这么大」）：

| 来源 | 条数 | 文件 |
|---|---:|---:|
| `com/rikkaminis/app/ui/chat/` 包（含 main/test 两侧） | 2363 | 37 |
| 其余 `src/main` | 212 | 68 |
| 其余 `src/test` | 28 | 27 |
| `src/androidTest` | 2 | 2 |

拆分族是大头，原因写在它自己的文件头里：`[T-android-split-chat]` 的整批「verbatim 搬移」把 `ChatScreen.kt` / `ChatViewModel.kt` 的**整个 import 块**复制到了每个新文件（`ChatFlatItems.kt` 267 个 import、其中 261 个不用）。

### 3.2 死函数：删 8 个，拒 5 组

判据：`private`（文件/类作用域，别的文件够不到）+ 本文件零文本引用（同样抹注释、同白名单）。共 13 个候选，**逐个人工读过现场**后删 8 个：

| 文件 | 函数 | 为什么正文不需要它 |
|---|---|---|
| `sandbox/offload/AlarmOffloadHandler.kt` | `wrap(text, args)` | 所有调用点直接走 `OffloadOutput.formatBody` |
| `tools/AgentTraceRecorder.kt` | `truncate(s, max)` | 只是 `sanitize(s, max)` 的别名；8 个 trace 字段全直呼 `sanitize` |
| `ui/chat/MinisTextKitSelection.kt` | `distance(a, b)` | 活的命中测试在 `within(anchor)` 里内联算同一段斜边 |
| `ui/settings/OffloadPermissionScreen.kt` | `performShizukuAction(...)` | Shizuku 行改成了导航入口（`onStatusRowClick` → `ShizukuPermissionScreen`），动作归那个屏幕 |
| `test/.../ModelListProviderRegistryTest.kt` | `ok(key)` | 零调用 |
| `test/.../ui/B3RenderInputShapeTest.kt` | `nextAfter(...)` | 零调用 |
| `test/.../ui/chat/ComposerSendSwipeTest.kt` | `bodyOf(...)` + 8 行 KDoc | 零调用；`skeleton()`/`braceSlice()` 各另有 4 处使用，保持 |
| `test/.../ui/sandbox/FilePreviewPdfKeyAndSaveTest.kt` | `countOccurrences(...)` | 零调用 |

**没删的**（见 §4）：`generationMatches`（生命周期域）、`makeRawSkill` / `toolResult`（KDoc 记录的夹具形态）、`watch` / `handleDeepLink`（**根本不是代码** —— 它们在三引号夹具字符串 `preFixActivity` 里，是扫描器的字符串盲区，正是「每条都人工核」才没删错）。

### 3.3 注释漂移：10 处文件头

拆分族 10 个文件头写着 `Full import block copied (unused=warnings)` / `unused imports are warnings, not errors`。这批话在本支落地后**变成假话**，而且它原本的前提就是错的：**Kotlin 编译器不报未使用 import 警告**（所以那些 import 从来没产生过警告，只是 IDE 幽灵）。逐条改成事实陈述（`Imports pruned to used symbols (2026-10-27)`），`ChatInputArea.kt` 里「a later pass can prune」的注记改成「已由 2026-10-27 清理」。

## 4. 拒绝与登记（不动，附理由）

| 项 | 为什么不删/不改 |
|---|---|
| `ModelExecutionService.generationMatches(capturedGeneration)` | 零调用，但它坐在 worker 自回收 / generation 判定路径上。那里出现「有检查但没人调」更像 **lost wiring**（该被 self-reap 分支调用而没调），删掉会把线索一起埋掉；且硬约束 1 禁止碰生命周期/并发域。登记 §7 R2 |
| `SubagentSkillTest.makeRawSkill` / `SanitizeAgentHistoryUserNeighborTest.toolResult` | 零调用，但 KDoc 明写它是「old shape / 反例形态」，属于刻意保留的夹具形态（P3 刻意设计） |
| `MainActivitySessionIdResolutionTest` 的 `watch` / `handleDeepLink` | 在三引号夹具字符串里，不是声明；删了会破坏夹具 |
| 名 `truncate` / `distance` 之外的 5 个非 `private`、或跨文件可达的同名疑似项 | 不满足「零调用方」判据（含 RPC 名表 / 反射字符串检查）——本轮没有此类删除 |

## 5. 验证（本地，CI 之外的三源）

1. **结构自检**：对 243 个改过的 `.kt` 跑 `_kotlin_lexer.analyse` + `mask_code` 括号/圆括号配平 —— **243/243 通过，0 问题**（词法未闭合 = 会连累后续所有门，这是最低成本的兜底）。
2. **扫描器幂等**：在改完的树上重跑同一个死 import 扫描器 → `files=0 imports=0`（可重复、无残留）。
3. **树等价证明**：三次提交前先把全部改动 `git write-tree` 存成树哈希 `ff9c953133e4b7a94228afa1fb520ea785829367`，按 hunk 拆成三个补丁分别落三次提交后，`git rev-parse HEAD^{tree}` **逐字符相同** —— 证明「按语义拆提交」没有丢/多任何一字节。
4. **门抽样**：18 道与被改文件相关的门在本地逐条跑，**18/18 rc=0**（`chat_ui_interaction`、`provider_remember_key`、`models_provider_field`、`share_dir_prune`、`sysinfo_state_thread`、`ime_inset_guard`、`stale_closure_guard`、`legacy_pipeline_guard`、`debug_leak_guard`、`mediaplayer_release_guard`、`filepreview_print_bounded`、`file_uri_parse_guard`、`webview_holder_url_source`、`provider_ua_parity`、`offload_relative_path_unify`、`thinking_wiring`、`truncated_call_guard`、`tool_result_image_branch`）。全量 112 门按任务书交给 CI。
5. **专项探针**：§2.3 的 4000 例对照（真源码编译）。

## 6. CI 读数回填

- **run** `40433787861` / **#2768** / **attempt 1** / **`success`**
- **head_sha** `8b2e4d6c3729d28f135110bb888b853e78d549ca` —— 与本地 `git rev-parse HEAD` **逐字符一致**
- **时长** 01:38:43Z → 01:41:19Z…**01:56:19Z**（约 16 分钟）；26 步中唯一 `skipped` = **Publish**（`if: ref == refs/heads/main` 门控，分支构建按设计跳过 ⇒ 未污染 release 资产）
- **日志** 1,465,357 B（上一次 main run #2766 = 1,499,650 B）/ **NUL 字节 0** / 解包单文件 `0_build.txt`

| 读数 | 结果 |
|---|---|
| `Summary: 112 passed, 0 failed` | **恰 1 次** |
| `ALL N CASES PASS` | **恰 1 次**，N = **347** |
| `^ e: ` 编译错误 | **0** |
| 门头 `[N/112]` 编号 | **112 个互异**（1..112 全覆盖） |
| 含 `FAILED` 的行 | 6 行，**逐行确认全是 PASSED 用例名的子串**（判读陷阱） |

**警告数变化（本支的核心可观测收益，两 run 逐条分类对拍）**：

| 类别 | #2766（基线 main） | #2768（本支） | Δ |
|---|---:|---:|---:|
| 全部 `w:` | 376 | **255** | **−121** |
| `Java type mismatch: 'kotlin.String?' → 'kotlin.String'` | 111 | **0** | **−111** |
| `Java type mismatch: 'java.io.File?' → 'java.io.File'` | 5 | **0** | **−5** |
| `Condition is always 'true'` | 19 | 14 | **−5**（附带收益，见下） |
| 其它类别 | 241 | 241 | 0 |

三处细节值得记：

1. **完胜预测**：赛前预测「111 + 5 消失、剩 2 条无关的」，实测**逐条对上**（且脚本按警告文本分类，不是按条数差算的）。
2. **附带收益**：那 5 条 `Condition is always 'true'` 正是 walk-up 循环里的 `while (dir != null)`——加 `: java.io.File?` 之后编译器不再能证明恒真，警告一起消失。**加可空标注把两条独立判据同时修好了**，这是「不写 `!!`」这个选择的额外红利。
3. **剩下 2 条是无关族**（`[a-z]` 与 `dir` 的其它来源），本支按半径纪律未动，见 §7 R1。`ChatTurnPartsJsonTest` 的行号从 277 → 276 是因为 import 清理把该文件顶上一行删掉了，属预期漂移。

## 7. 残留（每条带「触发条件 / 触发后方案 / 搁置理由」）

- **R1｜余下 2 条 `Java type mismatch` 未动**（`GuestPathParseMatrixTest:137` 的 `path(scanned) in listOf(...)`、`ChatTurnPartsJsonTest:277` 的 `buildSingleLlmMessage(..., dir.parentFile)`）。
  **触发条件**：这两个文件下次被同族（可空性/类型）改动触碰，或 CI 把类型警告升级为错误。
  **触发后方案**：给 `path()`/`dir` 补显式类型标注（不是本族的 `File(System.getProperty(...))`，形态要单独定）。
  **搁置理由**：与 `user.dir` 族无关（两个文件都不调用 `user.dir`），半径纪律不允许顺手扩。
- **R2｜`ModelExecutionService.generationMatches` 零调用（疑似 lost wiring）**。
  **触发条件**：下次排查 worker 自回收 / OOM / generation 竞态时；或有人要给 self-reap 加检查时。
  **触发后方案**：先 `git log -S generationMatches` 查它是否曾被杀掉的 reaper 分支调用；若确有过调用则按「修复」立项（带触发路径证据 + 真机/单测见证），若从来没有则降级为死码删除。
  **搁置理由**：生命周期/并发域，硬约束 1；且「删掉」会让一条潜在真 bug 的线索消失。
- **R3｜`AccessibilityOffloadHandler:990` 的 `OffloadArgs(newArgv)` 缺 booleanFlags（形态不一致，非缺陷）**。
  **触发条件**：有人给 `notifySub` 增加任何 flag（例如 `--all`、`--since`）时。
  **触发后方案**：把 `handle()` 的 flag 集合提成文件级常量 `BOOLEAN_FLAGS`，两处构造共用（本支不动，因为那是结构改动而非零行为清理）。
  **搁置理由**：本轮 4000 例证明当前无可观察差异；改动属重构，硬约束 4 禁止。
- **R4｜Kotlin 不报未使用 import ⇒ import 卫生没有 CI 守护**。
  **触发条件**：下一次有人假设「CI 会替我们看 import 干净」（本次 10 处文件头就是这么假设的）。
  **触发后方案**：把 `probe/sweep_dead_imports.py` 移植成一道门（判据已经是机械的、幂等的、fail-closed 的），进 `scripts/scan/`。
  **搁置理由**：新增门 = 新装置，按修复门要先给 P 级判定；本支是零风险清理支，不适合顺手加护栏。
- **R5｜死函数扫描器的字符串盲区**（三引号夹具里的 `private fun` 会被当声明）。
  **触发条件**：下次再跑批量死函数清理。
  **触发后方案**：用 `_kotlin_lexer` 的 `views['code']` 与 `views['comments']` 位置回贴，判「该 `private fun` 是否落在字符串内」。
  **搁置理由**：本支 13 个候选已逐个手工核过，为一次性任务建装置不划算（装置比例纪律）。

## 8. 诚实边界

- **没有本地编译**。沙箱无 Android SDK / Gradle 依赖缓存，`compileReleaseKotlin` 跑不了；因此「删掉的符号确实不需要」这件事，唯一的机器证伪是 CI 的主源集 + 单测编译。我在本地能做的只有词法/括号自检和 18 门抽样。
- **扫描器不是零假阳性**。第一版 4.5% 假阳性已经实测（§3.1）；第二版换成仓库自带字符串感知词法器后重新复核过，但我不能声称「绝对零假阳性」——它仍靠「符号零文本出现」这一条判据，凡是 Kotlin 里存在**不出现在文本中**的隐式使用，就只能靠白名单挡住。白名单是照 Kotlin 语言规范列的，若有遗漏，表现会是编译错误（CI 会把文件与符号指名报出来）。
- **警告数已经是实测**：预测（111 + 5 消失、剩 2 条无关）与 CI 日志逐条分类对拍完全一致，另多出 5 条 `Condition is always 'true'` 的附带消失；读数见 §6 表。
- **未做真机验证**：本支不改变任何运行时行为，也没有可观察的 UI 变化，因此没有真机面；「零行为」的主张建立在「只删不可达符号 + 只加可空标注 + 只改注释」这条结构性论证上，而不是运行证据。
- **本报告只覆盖本支**，不评价同批其它会话的分支，也不含合并 main 的动作（合并起手权在用户）。
