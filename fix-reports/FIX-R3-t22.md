# FIX-R3-t22 — `file_provider_paths_coverage_check` + `schedule_date_source_check`

第三轮（dual-gate bypass）收口批 · 修法报告
分支 `fix/r3-filepaths-schedule` · 基线 `main` @ `f0f14d46d`

---

## 1. 门与轴

| 门 | 见证 | 轴 | 修前判决 | 修后 |
|---|---|---|---|---|
| `file_provider_paths_coverage_check.py` | #24 dual-gate / #25 dual-gate / #26 symmetric | 存在→效果 / 枚举完整性 | 1 CAUGHT + 2 BLIND | 3 CAUGHT |
| `schedule_date_source_check.py` | #42 dual-gate / #43 symmetric / #44 legacy-axis | 存在→效果 / 数据流 / 顺序 | 2 BLIND（+1 非本门缝） | 2 CAUGHT + 1 交外门 |

**两门共同的失效形态**（与前四批同族，这次长出两副新面孔）：

1. **「参数到达」被当成「参数生效」**（#42）：门盯着 `handleList → resolveDateRange → queryMedia(range.startMs, range.endMs)` 的实参传递，`queryMedia` 的函数体可以把两个 bound 全做成死守卫（`if (startMs != null && false)`），SQL 里一条谓词都不加，`ContentResolver` 直接回答整个媒体库 —— 每个 `--start/--end/--days` 都是静默 no-op，而门是绿的。
2. **「拒绝被消费」被当成「拒绝被生产」**（#43）：门严查 `handleList` 必须枚举 `is Invalid ->` 分支，却不问 `resolvePhotoRange` 在 `endMs < startMs` 时到底返回了什么。把拒绝改成 `Window(endMs, startMs)` 静默交换，`"endMs < startMs"` 子串与 `PhotoRange.Invalid(` 调用都还在，门一路绿，用户拿到一个恒空却 exit 0 的答案。
3. **「缓冲区被消费」被当成「每一项被投递」**（#25）：C9 的两条判据（在 IO 上下文里 `consumeBuffer` + `cleanSharedFiles`、调用 `addAttachmentFromStagedShare`）**被 ATTACHMENT 分支单独满足**；把 `INLINE_TEXT ->` 分支体清空，用户分享的整段文字被读走、丢掉，门绿。
4. **「入口存在」被当成「入口连通」**（#26）：门把 `ShareReceiverActivity` 的暂存/记账/清理/消费侧全查了一遍，唯独不查它自己的 `when (intent?.action)` —— 删掉 `Intent.ACTION_VIEW -> handleView(...)`，「用其他应用打开 → RikkaMinis」这一整条入口静默失效（activity 带着空 list 直接 finish）。

## 2. 见证重放表（pre-fix / post-fix 两轮，同一批补丁）

基线树 `f0f14d46d`。`probe/t22-prefix-blind.json`（未加固的两门）vs `probe/t22-post.json`（加固后），补丁存 `probe/t22-w<k>.patch`，每条跑完 `git checkout --` 还原并校验 sha256（`restored: true` ×12）。

| # | kind | 目标（等价最小编辑） | 修前 rc（门） | 修前判决 | 修后 rc | 修后判决 |
|---|---|---|---|---|---|---|
| 24 | dual-gate | `PhotosOffloadHandler.kt:689` `File(outDir, …)` → `File(context.cacheDir, …)` | 1（file）· 0（schedule） | **CAUGHT** | 1 | CAUGHT |
| 25 | dual-gate | `ChatScreen.kt:439-448` 清空 `INLINE_TEXT ->` 分支体 | 0（file）· 0（deeplink） | **BLIND_CONFIRMED** | **1**（file） | **CAUGHT** |
| 26 | symmetric | `ShareReceiverActivity.kt:109` 删 `ACTION_VIEW -> handleView(...)` | 0（file） | **BLIND_CONFIRMED** | **1** | **CAUGHT** |
| 42 | dual-gate | `PhotosOffloadHandler.kt:244,248` 两处 bound 守卫 `&& false` | 0（schedule）· 0（file） | **BLIND_CONFIRMED** | **1**（schedule） | **CAUGHT** |
| 43 | symmetric | `resolvePhotoRange` 拒绝分支改 `Window(endMs, startMs)` | 0（schedule） | **BLIND_CONFIRMED** | **1** | **CAUGHT** |
| 44 | legacy-axis | `ScheduledTaskRevisionPolicy.nextRevision` `+ 1L` → `+ 0L` | 0（schedule）· 0（scheduled_store_release_guard） | **BLIND_CONFIRMED**（非本门缝） | 0 | 交外门（§7 R1） |

**修前 5/6 BLIND_CONFIRMED，修后 5/6 CAUGHT（红在自己那条判据上，归因见 `probe/t22-attribution.json`）：**

```
#24 file     C10 [PhotosOffloadHandler.kt] `context.cacheDir` directory root built from …
#25 file     C9b the share drain's `INLINE_TEXT` arm is EMPTY — the item is consumed from the buffer and then dropped
#26 file     C12 the manifest registers `android.intent.action.VIEW` …, but extractItems() has no branch for it
#42 schedule queryMedia(…) never applies the `startMs` bound on a reachable path … (同时报 `endMs`)
#43 schedule resolvePhotoRange returns Window(endMs, startMs) — the two bounds are SWAPPED
```

### 2.1 两条补丁描述漂移（如实登记，未编造编辑）

| # | 报告里的标识符 | 基线树实际情况 | 处置 |
|---|---|---|---|
| 43 | `PhotoRangeResult.InvertedRange(startStr ?: "", endStr ?: "")` | 现树是 `PhotoRange.Invalid("--end", endRaw.orEmpty())`（`sealed class PhotoRange` + `Invalid(flag, raw)`，`Window` 也在同一 sealed 家族里） | 在**现树同一条缝上**构造等价编辑（拒绝 → `Window(endMs, startMs)`）。缝的形态不变：非法区间被当成成功结果返回 |
| 44 | `ScheduledTasksStore.bumpRevision()` / `_revision.value` 的 `+ 1L` | `ScheduledTasksStore.kt:232` 已委托 `ScheduledTaskRevisionPolicy.nextRevision(_revision.value, wrote)`；`+ 1L` 在 `ScheduledTaskPolicy.kt` 的策略函数里 | 等价的 `+ 0L` 编辑打在策略函数上。**但本门从不扫这两个文件**（见 §7 R1） |

### 2.2 dual-gate 见证的另一半门「不该管这条缝」

#24 / #25 / #42 的另一半门在补丁下保持 rc=0，且这是**正确**的，不是漏修：

- #24 的 y 门（`schedule_date_source_check`）管的是 `PhotosOffloadHandler` 的**日期解析**；把导出文件写到 `cacheDir` 与日期无关。
- #25 的 y 门（`deeplink_settings_routes_check`）管的是 `ChatScreen` 的 `pendingChatAction` / `pendingHtmlPreview` 深链落点，不管分享缓冲区。
- #42 的 y 门（`file_provider_paths_coverage_check`）管的是 provider 根目录覆盖，不管 SQL 谓词。

这三条缝的**归属**：x 门（已修）+ 各自的功能域门，都不在对方门里重复覆盖（重复覆盖 ≠ 空白，spec §3 的反面）。

## 3. 判据改造（形态对照）

| 判据 | 旧（存在/拼写） | 新（效果/派生） |
|---|---|---|
| **C9b** 分享投递（#25） | C9 只查「在 IO 里 `consumeBuffer` + `cleanSharedFiles`」「调用 `addAttachmentFromStagedShare`」——ATTACHMENT 分支单独满足 | 从树里派生 `for (item in pending.items)` → `when (…kind)` 的**每一个分支**（枚举用共享 `_kotlin_ast.when_branches`）：分支体非空 + 至少一次**活调用**把 `item.value` 交给**非 logger** 的 sink（把值交给 `AppLogger` 正是见证形态，不算投递）；再把 `PendingShare.Kind` 的**枚举常量**回比一次——被 `else` 吞掉的 kind 同样红 |
| **C12** 入口连通（#26） | 完全不查 `ShareReceiverActivity.extractItems()` | 清单是唯一事实源：从 `AndroidManifest.xml` 读 `ShareReceiverActivity` 的全部 `<action>`，逐一要求 `extractItems()` 有对应分支；**并且**该分支必须把 item list 交给 handler，handler 的对应参数必须真的 `+=` / `.add(`（参数按实参位次定位，改名 `out`/`into` 不影响判据） |
| **photos 窗口到 SQL**（#42） | 只锚 `handleList → resolveDateRange → queryMedia(...)` 的实参传递 | 两半都判：①`queryMedia` 内每个 `Long?` bound 必须在**可行**守卫（`if_blocks` + `path_feasible`，`&& false` 折叠即死）里既加 SQL 谓词（字面量视图判 `>= ?`）又喂 `selectionArgs`，且被填充的集合必须经 `value_sources` 一跳连到 `query(…)` 的 selection/args 实参；②每个 `queryMedia(...)` 调用点的 bound 位次不得传 `null`，且必须对本位次对应的 `PhotoRange.Window` 字段（**字段名从 data class 声明派生**）有 `.field` 读——交换位次即红 |
| **photos 拒绝被生产**（#43） | 子串 `"endMs < startMs"` + `PhotoRange.Invalid(` 存在 | 找**可达**的双 bound 比较守卫：块内必须有 `return`，且返回表达式**不得**构造 `Window`；再对**每一个** `return PhotoRange.Window(...)` 判参序（`Window(endMs, startMs)` = 交换即红） |

## 4. 四层证据

### L1 本门

| 门 | 真树 | `--selftest` | 臂数 |
|---|---|---|---|
| `file_provider_paths_coverage_check` | rc=0 / `Summary: 0 violation(s) found` | `FILE_PROVIDER_PATHS_SELFTEST=PASS (42 arms)` | 36 → **42** |
| `schedule_date_source_check` | rc=0 / `schedule-date-source: OK …` | `SCHEDULE_DATE_SOURCE_SELFTEST=PASS (35 arms)` | 29 → **35** |

新增臂（全部「在变异代码上恰好红、真树绿」；schedule 侧另带逐臂归因断言，防「撞错判据」的假臂）：

- file：`WITNESS share drain INLINE_TEXT arm emptied` / `… arm only logs the text` / `… arm deleted, else swallows it` / `WITNESS extractItems drops ACTION_VIEW` / `WITNESS ACTION_VIEW routed to a handler that fills nothing` / `WITNESS manifest registers an action extractItems never routes`
- schedule：`photos/window-guard-dead` / `photos/predicate-dangling` / `photos/call-site-null` / `photos/call-site-swapped` / `photos/window-args-swapped` / `photos/invert-returns-window`
- 精度臂（防误伤）：`photos/compliant` 不得被报「SWAPPED」；`photos/album-drops-size`（窗口完好、只缺尺寸）不得被窗口规则命中。

### L2 反攻边界

见 §2：5 条 BLIND 全部翻红，`restored: true` ×12，`git status src/` 全程干净。

### L3 邻门

`sh /var/minis/shared/tools/gate_run.sh` 逐门持锁跑，全 `verdict=GREEN`：

```
子集: file_provider_paths_coverage_check / schedule_date_source_check   ran=2 pass=2 fail=0 sandbox=0
逐个: deeplink_settings_routes_check · scheduled_store_release_guard · share_dir_prune_check ·
      file_preview_url_encode_check · parts_json_funnel_check ·
      offload_permission_reset_sync_check · chat_session_id_binding_check · collection_copy_on_mutate_check
      8/8 GREEN
```

### L4 全量

**本地全量未跑成，交 CI 承担**（纪律允许、且本轮环境如此）：逐门独立进程循环在 109 门里 78 门撞 `OSError: [Errno 38] Function not implemented`（连 `basename`/`grep`/`cat`/`/tmp` 写入都 ENOSYS，registry S28 施工期变体 ③）。`test_scan.py` 344 夹具同理交 CI。

## 5. CI 读数

（见 §5 回填）

## 6. 残留（均 P2，各带触发条件 / 触发后方案 / 搁置理由）

- **R2｜manifest 派生只认 `ShareReceiverActivity` 一个字面类名**。触发条件：新增第二个分享入口（如 `SendMultipleActivity`、`ShareShortcutActivity`）时。触发后方案：把 `activity_actions()` 提到 `check()` 的模块级循环，按 `<activity>` 里**含 `android.intent.action.SEND*` 的 intent-filter** 自动枚举，而不是点名。搁置理由：现在只有一个入口；提前泛化会让判据在「哪些 activity 算分享入口」上引入新的拼写假设。
- **R3｜C9b 的 sink 判据是「非 logger 的活调用」，不是「投递到 composer」**。触发条件：出现 `viewModel.recordShareText(item.value)` 之类「消费了但没投递」的中间层时。触发后方案：把 sink 收敛成从树派生的 `ComposerCommand` 家族（枚举 sealed class 的 `AppendText`/`SetText` 等子类）并要求实参引用；或要求 sink 的接收者是注入进 `ChatScreen` 的 `ChatViewModel` 实例。搁置理由：静态上「投递」只能验到「值进了某个非日志函数」；再收紧会把合法重构（先记 pending 再统一 flush）判红。
- **R4｜`queryMedia` 的 bound 位次按签名里 `Long?` 的出现顺序绑定 `PhotoRange.Window` 的字段顺序**。触发条件：`queryMedia` 增加第三个 `Long?` 参数（如 `minSize`），或 `Window` 增删字段时。触发后方案：改为按名字对齐（参数名 vs `Window` 字段名），名字对不上时 fail-closed 提示。搁置理由：当前 2 个 bound 与 `Window` 的 2 个字段一一对应；名字对齐会在有人重命名局部变量时误报。
- **R5｜C12 的 handler 填充判据只认 `+=` / `.add(` / `.addAll(`**。触发条件：handler 改为返回 list（`fun buildX(intent): List<Item>` 由调用方 `items += buildX(...)`）时。触发后方案：解析调用点的赋值形态，认可「返回值被 `+=` 到 list」。搁置理由：现树三个 handler 全是 `out +=` 形态。
- **R6｜`_entry_routes_manifest_actions` 对缺失 `AndroidManifest.xml` 直接判红**。触发条件：把门移植到没有 manifest 的合成夹具树时。触发后方案：夹具强制配套 manifest（本轮已在本门 `write_fixture` 里补上 `CLEAN_MANIFEST`），保持 fail-closed。搁置理由：本门只跑真树 + 自己的夹具。
- **R1（外门，未修，见 §7）**：`scheduled_store_release_guard.bump_stalls` 对 `nextRevision` **函数体**盲。
- **R7｜共享原语 `_kotlin_ast.when_branches` 的分支 body 区间会在多行臂处截断**（按顶层换行切，`bend` 只是到本段换行）。触发条件：主会话要收编别的门时。触发后方案：让 `when_branches` 在 `->` 之后若遇 `{` 就 `match_bracket` 收成完整块区间（对现有调用者是纯扩张，`bend` 变大不影响「取带体」的语义），或在模块里补一个 `when_branch_blocks()`。搁置理由：本轮我在自己的门内用 `match_bracket` 收紧（4 行），共享模块属冻结面。

## 7. `test_scan.py` 注册状态（冻结面，未动）

- 两门既有注册**无需改动**，收尾标记形态保持：
  - `file_provider_paths_coverage_check` → `FILE_PROVIDER_PATHS_SELFTEST=PASS`（子串断言，42 臂仍是 `PASS`）
  - `schedule_date_source_check` → `SELFTEST=PASS (schedule-date-source: 2 files x 2 arms, teeth on every criterion)`（逐字节保留该行）
- **建议主会话追加**（可选，非必需）：
  - `("file_provider_paths_coverage_check", "FILE_PROVIDER_PATHS_SELFTEST=PASS (42 arms)", (), (REPO_ROOT,))` —— 钉住臂数，防「门被掏空成 `sys.exit(0)` 但仍打印 PASS」
  - `("schedule_date_source_check", "SCHEDULE_DATE_SOURCE_SELFTEST=PASS (35 arms)", (REPO_ROOT,))` —— 现有注册只钉了第一行，第二行的臂数标记目前没人见证（batch-7 R13 同族）。

### R1（外门缝）：`#44` 的真实归属

本门**从不读** `ScheduledTasksStore.kt` / `ScheduledTaskPolicy.kt`（`SCAN_FILES` 只有 Runner / CalendarOffload / PhotosOffload），所以报告里「前两轮在本门 S-11 臂里加固过 `_revision`」这个前提**不成立**——S-11 那族判据在 `scheduled_store_release_guard.py`（`scan_revision_stream` + `bump_stalls`）。

我按纪律没动它（冻结面），但把外门的盲点复现了：

```
probe/t22-w44.patch : ScheduledTaskPolicy.nextRevision  `current + 1L` → `current + 0L`
python3 scripts/scan/scheduled_store_release_guard.py .   → rc=0   （盲）
```

外门的 `bump_stalls` 只验**调用点**的形状（`_revision.value = …nextRevision(_revision.value, wrote)`，非自赋值、非常量），不追 `nextRevision` 的**函数体**。建议修法（主会话采纳时用它自己的臂体系落地）：

```
在 scan_revision_stream 里，把 `nextRevision(` 的实参 `wrote` 与定义体连起来判：
  fb = function_body(policy_code, "nextRevision")
  要求 fb 里存在一条可达的 `current + N` / `current + step` 且 N ≠ 0（const_bool/字面量折叠），
  并且返回值随 current 变化（不能 return 常量）。
触发条件：下一次改 `ScheduledTaskRevisionPolicy` 时；搁置理由：本轮 #44 的补丁描述与本门树对不上，
且归属外门（冻结面）。
```

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t22

# 真树 + 自检
python3 scripts/scan/file_provider_paths_coverage_check.py .          # rc=0
python3 scripts/scan/file_provider_paths_coverage_check.py --selftest # 42 arms
python3 scripts/scan/schedule_date_source_check.py .                  # rc=0
python3 scripts/scan/schedule_date_source_check.py --selftest         # 35 arms

# 修前/修后反攻（自动把两门换回 HEAD 跑一遍再换回加固版）
sh probe/t22_ab.sh
# 逐条归因（红在哪条判据上）
python3 probe/t22_attrib.py

# 邻门（持锁、自动 ENOSYS 重试）
for g in deeplink_settings_routes_check scheduled_store_release_guard share_dir_prune_check \
         file_preview_url_encode_check parts_json_funnel_check \
         offload_permission_reset_sync_check chat_session_id_binding_check collection_copy_on_mutate_check; do
  sh /var/minis/shared/tools/gate_run.sh --gate scripts/scan/$g.py --repo .
done
```

## 9. 自伤记录（可复用）

1. **`probe/t22-pre.json` 被第二轮的 `pre` 跑覆盖** —— 第一版脚本把「修前」和「已修后」两次 `pre` 都写成同一个文件名，等意识到时原始读数已丢。修法：A/B 对照脚本（`probe/t22_ab.sh`）先把两门 `git show HEAD:` 取回跑「未加固」，产物另存 `t22-prefix-blind.json`，再换回加固版跑 `post`。**教训：证据文件名必须带阶段（`-prefix-blind` / `-post`），不能靠「跑的顺序」区分。**
2. **`_sig_params(cv, "queryMedia(")` 命中调用点而非声明** —— `code.find(marker)` 先撞上 `handleList` 里的 `queryMedia(MediaStore…)`，参数表被解析成空 ⇒ 假绿（「queryMedia 不再有两个 Long 界」这条直接不成立却报红/绿都不对）。改成按 `\bfun\s+<name>\s*\(` 定位声明。**教训：按名字取签名必须锚 `fun` 关键字，不能锚裸名字。**
3. **SQL 谓词在字符串字面量里** —— `>= ?` 被 `code_view` 抹成空格，结构化通道上永远搜不到。改用等长的 `literal_view` 在同偏移读。**教训：词法谓词（SQL / 正则 / 路径模板）一律在 literal view 上判，结构判在 code view 上判。**
4. **`return PhotoRange.Window(` 只取第一处** —— `days` 分支先返回一个与 bound 无关的 `Window(...)`，交换检测挂在不该挂的那一处上，臂当场「expected VIOLATIONS, got clean」。改成遍历**每一个** Window 返回。**教训：同一族「谁与谁对齐」的判据要枚举全部站点，不能取首个匹配。**
5. **手写 `when_branches` 被共享原语替代** —— 初版自写「花括号深度 + `->`」扫描器，违背硬约束 #1；`_kotlin_ast.when_branches` 已存在（按顶层换行切分支）。改用共享原语 + 只做「把 span 收紧到 `{ … }`」这一层（共享原语的 span 到下一个顶层换行为止，多行臂会被截断 —— 这是共享原语的真实缺口，见 R7）。**教训：写扫描器前先 `grep '^def ' scripts/scan/_kotlin_*.py`。**
6. **`_inside`/`_regions_in` 之间被吃掉空行** —— `file_edit` 删掉一个空行后两个 `def` 粘在同一行，`SyntaxError`。**教训：删除空行类「无意义」编辑不要做。**
