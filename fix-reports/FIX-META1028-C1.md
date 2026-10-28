# FIX-META1028-C1 — 草稿转正数据丢失族（B03 F-01 / F-03 / F-04）

- 分支：`fix/meta1028-c1-draft-migrate`
- 基线：main `bfb5b0e6`（报告基线 `47c4bf4b`；`47c4bf4b..bfb5b0e6` 只动了 ConfigBackup / SoulStore / OpenMinisBackupCompat 等 12 文件，`ui/chat` 未被动 → 行号无漂移，逐条亲眼核对）
- 代码提交：`0aebd999`（5 文件 +736 / −38）
- 来源报告：`/var/minis/shared/work/meta-audit/reports/Report__3_.md`（B03）F-01 / F-03 / F-04
- CI：见 §6

---

## 1. 缺陷（三条，全部落在 `ui/chat/ChatSessionEntryExt.kt`）

| ID | 位置（`bfb5b0e6`） | 缺陷 | 判定 |
|---|---|---|---|
| F-01 | `promoteDraftIfNeeded` :116-118 | `if (text.isBlank()) return false` 早于 `_attachments.value` 读取 → 纯附件草稿点 New Chat / 首条发送时整包附件静默丢失 | **真** |
| F-03 | `migrateDraftResources` :348-356 + `copyRecursive` :396-405 | ①`!target.exists() && !child.renameTo(target)` 把同名条目挡在 if 外（既不合并也不拷贝）；②`listFiles()?.all { … } ?: true` 把 I/O 失败折叠成成功；③目录分支后漏删源目录；④调用点丢弃返回值 | **真（4 条独立）** |
| F-04 | `promoteDraftIfNeeded` :147-180 | IO 线程直接写 Compose 状态；转正窗口结束时无条件清空输入框/附件，抹掉窗口期内新输入 | **部分**（拆分见 §2.3） |

---

## 2. 先证（每条回真树核对触发路径，不采信报告行号）

### 2.1 F-01 = 真

1. 两个 New Chat 入口**都无条件调用并忽略返回值**，随后照常导航：
   - `ChatScreen.kt:2382`（顶栏）`viewModel.promoteDraftIfNeeded(); onNewChat()`
   - `ChatScreen.kt:2028`（抽屉）`viewModel.promoteDraftIfNeeded()` → `historyDrawerScope.launch { close(); finally { onNewChat() } }`
2. 早退发生在读附件之前（原文已核，`:116-118`）：`val text = …; if (text.isBlank()) return false; val stagedAttachments = _attachments.value`。
3. 「纯附件是有效输入」有三处现成证据，全部在真树里：
   - `ChatViewModel.kt:3703` —— `if (trimmed.isBlank() && _attachments.value.isEmpty()) return`，注释原文「T180: allow attachments-only sends (no caption). Mirrors iOS」；
   - `PromoteDraftPolicy.shouldSkipCleanup` 专门有 `hasAttachments` 保护位（附件本身就被当作「不许回收的内容」）；
   - `buildUserPartsJson("")`（`ChatMessageJson.kt:47`）在 `mediaRefPartsJson` 非空时**不写文本 part**，只写 mediaRef → 纯附件行是合法 payload。
4. 附件唯一的家是内存：`_attachments` 只在 `ChatViewModelUiStateExt.kt:82`（用户手动清）、`ChatViewModel.kt:2927`（发送成功）、`ChatViewModel.kt:3522/3556`（编辑态）被写，**草稿槽 `ComposerDraftStore` 只存文本**（`saveText`/`restoreText`）。所以草稿 VM 被丢弃 = 附件蒸发，没有恢复路径。
5. **报告的一半是错的**：报告称「转正失败时 `finally` 只写回文本、丢弃全部附件」。实测 `_attachments.value = emptyList()` 只在 `persisted == true` 之后执行（:179），失败路径**从不清附件**——VM 存活期间附件一直在。真正的损失面只是「VM 被丢弃」，这不是本支能修的（需要落盘层，见 §7 R1）。

### 2.2 F-03 = 真（四条独立成立）

1. 同名冲突：`target.exists()` 为真时整条 `if` 短路 → 既不 rename 也不 copy（`:350`）。触发面真实存在：`dst` 由 `mkdirs()` 先建好，同名子目录/文件可由工具产物（`.traces`、`workspace/<固定名>`）或**上一次部分失败的迁移**留下。
2. `?: true`（`:399`）：`listFiles()` 返回 null（权限 / I/O）被当成「全部拷贝成功」。
3. 目录分支没有 `src.delete()`：`copyRecursive` 只在文件分支删源（`:402`），目录分支只递归 —— 跨设备/权限导致 rename 失败时源目录永久滞留。
4. 调用点 `runCatching { … }.onFailure { Log.w }`：只记异常，**不记 `false`**，而落回 false 的路径（②③）不抛异常 → 完全静默；只有末尾 `leftover` 检查会打一行「keeping …」，不说是哪一条。

### 2.3 F-04 = 部分

- **真**：转正末尾无条件 `_inputText.value = ""` / `_attachments.value = emptyList()` / `publishPendingComposerText(null)`，而 IO 半段包含 `prepareUserAttachments`（图片缩放 + 落盘）与 DB 写，窗口可达数十至数百毫秒。窗口期内新输入的字符/新加的附件被一并抹掉。
- **真（次要）**：`_messages.value = _messages.value + …` 是非原子读改写，在 IO 线程上与流式写入方并发。
- **不成立（不对号入座）**：`MutableStateFlow.value` 本身是线程安全的 CAS，**「后台线程写 StateFlow 会破坏数据」不成立**；Compose 通过 `collectAsState` 订阅，跨线程发布也不会丢值。所以这条的实质是「**清了不该清的东西**」+「读改写不在同一线程」，不是「跨线程写坏了状态」。报告把两者混为一谈，本支按实质修，KDoc 里写明。

---

## 3. 修法

### F-01（`ChatSessionEntryExt.kt` + `PromoteDraftPolicy.kt`）

```kotlin
val stagedAttachments = _attachments.value      // 读在前面
val committedAtCapture = _inputText.value
val pendingAtCapture = _pendingComposerText.value
val text = composerTextForPromotion(pendingAtCapture, committedAtCapture)
if (!hasPromotableInput(text, stagedAttachments.size)) return false
```

- `hasPromotableInput(text, stagedAttachments)` = `text.isNotBlank() || stagedAttachments > 0`，与 `send()`、`shouldSkipCleanup` 同一契约（三处一致性写进 KDoc）。
- 标题：`promoteTitleForDraft(text, stagedAttachments.firstOrNull()?.fileName)` —— 有正文取 50 字前缀；无正文取首枚附件文件名；两者皆空返回 `""`，由调用点 `ifBlank { "New Chat" }` 兜底（沿用同文件 `updateTitleAndCategory` 的同一字面量，不在 policy 层再写一份）。

### F-03（新增 `DraftResourceMigration.kt`，`migrateDraftResources` 改成委托）

`migrateDraftEntry(src, dst)` = 目标名空闲 → rename（一次系统调用搬整棵子树）；否则 **MERGE**：

- 目录 → 逐子项递归（嵌套仍能走 rename 快路径），全部成功后 `src.delete()`；
- 文件 · 目标不存在 → 临时名拷贝 + rename（半个文件永远不会顶着正式名留在真实会话里）+ 删源；
- 文件 · 目标存在 → **绝不覆盖**（真实会话同名文件可能是草稿之后写的）；字节相同则删源（上一次「拷完没删成」的幂等收敛），字节不同则保留源并返回 `false`；
- `listFiles() == null` → `false`（不是「空目录」）；
- 调用点逐个检查返回值，失败条目与原因各打一行 `Log.w`，并用 `complete` 标志防止「列不出目录 → leftover 判定看不见 → 把草稿树删掉」。

### F-04（`ChatSessionEntryExt.kt`）

状态写入整体搬进 `withContext(Dispatchers.Main.immediate) { … }`（`persisted = true` 也在其中，`finally` 语义不变），并且**每份副本只在仍是入场快照时才清**：

- `if (_inputText.value == committedAtCapture) _inputText.value = ""`
- `if (_pendingComposerText.value == pendingAtCapture) publishPendingComposerText(null)`
- 附件按 `id` 只摘掉本次转正的那几枚（`filterNot { it.id in stagedIds }`）——窗口期新加的附件留给下一稿，已入库的那几枚不会二次发送。

---

## 4. 这改变什么用户行为？

1. **纯附件草稿点 New Chat（或直接发首条）不再消失**：以前是「无提示地全部丢掉」，现在是转正成一个会话，标题是首枚附件文件名，气泡里带图/文件，可以点 Resume 立即发送。
2. **草稿期产生的工具产物/附件在转正后一定可达**：同名冲突从「静默留在 `__new__*`」变成「合并进真实会话」；真正无法合并的（同名不同内容）才保留并在日志里点名，草稿树整体保留。
3. **转正期间新敲的字不会被吞**：窗口期内的输入留在输入框里，不再被后台协程收尾时清成空。

---

## 5. 证据

### 5.1 JVM 单测（真源码，真临时目录）

`kotlinc` 单次编译（skill `sandbox-jvm-testing` §1）`PromoteDraftPolicy.kt` + `DraftResourceMigration.kt` + 两个新测试类 → `OK (16 tests)`：

- `DraftResourceMigrationTest`（7 例）：同名冲突必须合并 / `listFiles()==null` 必须报失败 / 合并后的空目录必须删 / 幂等（相同字节删源、不同字节保留且不覆盖）/ rename 快路径搬整棵子树 / 文件撞目录报失败。
- `DraftPromotionInputTest`（9 例）：纯附件可转正、空白无附件不可转正、文本优先标题、无正文用首枚附件名、50 字截断与 trim、两源皆空返回空串（交给调用点兜底）+ 两条源码形状判据。

**每条缺陷都有「修前算法」实例作为见证**：测试里逐字转录了 `bfb5b0e6` 的 `!target.exists() && !child.renameTo(target)` / `?: true` / 目录不删源 三个形态（`legacyMoveEntry` / `legacyCopyRecursive`），先断言它们在**同一份夹具**上确实产生缺陷（文件滞留 / 假成功 / 空目录残留），再断言新实现不产生 —— 夹具因此被证明「有牙」，而不是「改了以后刚好绿」。

### 5.2 变异臂（6 臂，每臂只回退一处）

| 臂 | 回退内容 | 变红用例 |
|---|---|---|
| M1 | `listFiles() ?: return false` → `return true` | `a directory that cannot be listed reports failure instead of success` |
| M2 | 合并成功后不删源目录 | 3 例（合并目录残留 / 快路径 / 同名冲突） |
| M3 | 目标存在时直接 `return false`（= 修前「跳过」） | 4 例（含 F-03 主见证） |
| M4 | 相同字节不删源（幂等收敛失效） | `an identical copy left by a previous run is dropped from the draft side` |
| M5 | `hasPromotableInput` 丢掉附件项 | `an attachments-only draft is promotable` |
| M6 | `promoteTitleForDraft` 不看附件名 | 2 例（首枚附件名 / trim+cap） |

脚本：`/var/minis/shared/work/meta-audit-1028/probe-c1/{mutate.py,arm.py}`（臂逐条独立进程跑——11 会话并行，kotlinc 是全沙箱最重的活）。**踩坑记录**：整脚本一次跑六臂时被 OOM/负载杀掉两次，第二次留下 `DraftResourceMigration.kt.armbak`（变异体还在树里），导致下一次「baseline」直接假红 —— 已给脚本加「启动先恢复 stale `.armbak`」，并且 `arm.py` 一次只跑一臂。

### 5.3 既有装置未被破坏

- `PromoteDraftPolicyTest`（纯函数，9 例）本地 JVM 实跑：`OK (9 tests)`。
- `PromoteDraftVisibilityTest` 无法本地编译（依赖 `ChatHistoryDrawer.kt` 的 Compose 类型），改用**判据转录探针** `probe-c1/shape_check.py` 复刻它的 `promoteShape` / `promoteViolations`（durable 写必须带 `text`、必须在 `ensureSession` 之后、composer 字面量清空必须在 durable 之后）：
  - 修改后树 `durable=4469 ensure=1516 composerLiteral=6248` → **PASS**；
  - 修前树 `bfb5b0e6` `durable=3622 ensure=876 composerLiteral=4249` → PASS（基线本来就没问题，符合预期）；
  - 转录的 pre-fix 形态 → **VIOLATION: never persists…**（判据有牙）。
- 仓库静态门（单文件、廉价）：`skill_restore_session_cleanup_check.py` = PASS（该门是本文件唯一的门消费者，含 promote 顺序 / `promoteInFlight` 声明+释放 / `persisted` 必须在 durable 之后翻转等 9 个臂）；`chat_session_id_binding_check.py` = all criteria satisfied。
- **形状编译探针**（skill §9，本支改动了函数形态：包 `withContext` lambda + 变长实参 + 前导点续行）：`probe-c1/shape/ShapeProbe.kt` 用同样的 coroutines 库复刻新形态（`withContext(Main.immediate)` 内给外层 `var` 赋值、跨行 `.ifBlank { }`、`mapTo(HashSet())`、条件清空），`kotlinc` 干净通过。**诚实边界**：这只是「新形态是合法 Kotlin」，不等于 `ChatSessionEntryExt.kt`（Room/Compose/VM 依赖）编得过，最终仍以 CI 为准。

---

## 6. CI

- 分支 push 不触发（`build-apk.yml` 只认 main）→ 手动 `gh-actions-dispatch --workflow build-apk.yml --ref fix/meta1028-c1-draft-migrate`。
- run **#2825 / id `40679873501`**，head_sha `0aebd9991999fee86c649702719f4eb1e5753b81`（与本地 HEAD 逐字符一致）。
- **conclusion: success**，job `build` 11:25:44 → 11:47（约 21 分钟），27 步。

**日志读数**（逐段解包 `/tmp/c1logs.zip`，NUL 已剥离）：

| 项 | 读数 |
|---|---|
| 27 步非 success | 仅 2 条 skipped：`Move the rolling tag android-latest`（分支不建 tag）+ `Publish to Releases`（main 门控，符合预期） |
| 门 | `Summary: 113 passed, 0 failed`（末段汇总）；门头 18 个 id 互异、最大 113；`FAILED` 行 **0** |
| 夹具 | `ALL 349 CASES PASS` |
| `DraftResourceMigrationTest` | **7 PASSED / 0 真 FAILED** |
| `DraftPromotionInputTest` | **9 PASSED / 0 真 FAILED** |
| `PromoteDraftPolicyTest`（最近邻，既有） | 9 PASSED / 0 |
| `PromoteDraftVisibilityTest`（最近邻，既有；本支改了它扫描的那个函数） | **12 PASSED / 0** —— 含「pre-fix title-only shape is caught」等负控，判据在改后仍然有牙 |
| 编译 | APK step `BUILD SUCCESSFUL`，`^ e: ` 行 **0** |
| 日志体量 | 648,485 B（含 3,635 NUL，`androidTest` 相关段的惯例） |

---

## 7. 残留（登记用，本支不修）

- **R1（P2）**：附件只活在内存 —— 转正失败且 VM 被丢弃时，附件仍无恢复通道（本支只保证「不再因为空白文本而主动丢弃」，不解决「进程/VM 死亡即丢失」）。要修需要给 `ComposerDraftStore` 加附件清单持久化（含 SAF Uri 权限回收问题），与 `promoteDraftIfNeeded` 不在同一半径内。
- **R2（P2）**：空白正文 + 有附件时，落库的文本 part 是原始空白串（`send()` 会 trim）。行为无害（纯空白 part 不影响模型），但两处口径不完全一致。
- **R3（P2）**：`mergeFile` 的「字节相同」判定按 64 KiB 全量比对；同名且都很大的文件（数百 MB 视频）会在转正路径上多读一遍。触发条件极窄（必须先出现同名冲突 + 上次迁移失败），未加尺寸上限。
- **R4（P3）**：`migrateDraftResources` 里 `browser_tabs/<sid>.json` 的搬移逻辑（`audit-0917` 那段）未纳入 `migrateDraftEntry` —— 它是单文件、语义不同（真实文件优先 + 删草稿副本），保持原样。
- **R5（P2）**：`_messages.value = _messages.value + …` 在主线程上仍是读改写；若流式写入方在别的线程同时改，理论上仍能丢一次更新（窗口极小）。彻底修法是给 `_messages` 一个 `update {}` 或统一写入口，超出本支半径。

---

## 8. 复现

```sh
# 1. 桩+测试一次编译（skill sandbox-jvm-testing §1：不分批编译）
cd /var/minis/shared/work/meta-audit-1028/wt-c1
LIB=/var/minis/shared/work/jvm-libs
kotlinc -nowarn -cp "$LIB/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIB/junit-4.13.2.jar" \
  src/android/app/src/main/java/com/rikkaminis/app/ui/chat/PromoteDraftPolicy.kt \
  src/android/app/src/main/java/com/rikkaminis/app/ui/chat/DraftResourceMigration.kt \
  src/android/app/src/test/java/com/rikkaminis/app/ui/chat/DraftResourceMigrationTest.kt \
  src/android/app/src/test/java/com/rikkaminis/app/ui/chat/DraftPromotionInputTest.kt \
  -d /tmp/c1.jar

# 2. 跑（cwd 必须在 src/android/app，形状判据要从这里向上找源码）
cd src/android/app && java -cp "/tmp/c1.jar:$LIB/kotlin-stdlib-2.1.0.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar:$LIB/kotlinx-coroutines-core-jvm-1.8.1.jar" \
  org.junit.runner.JUnitCore com.rikkaminis.app.ui.chat.DraftResourceMigrationTest \
  com.rikkaminis.app.ui.chat.DraftPromotionInputTest

# 3. 变异臂（一次一臂，避免与并行会话抢内存）
python3 -u /var/minis/shared/work/meta-audit-1028/probe-c1/arm.py M3

# 4. 既有判据转录探针 + 单文件门
python3 -u /var/minis/shared/work/meta-audit-1028/probe-c1/shape_check.py
python3 scripts/scan/skill_restore_session_cleanup_check.py .
```
