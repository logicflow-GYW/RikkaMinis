# FIX-W1118-data（WAVE-1118 D5 数据层）修复报告

- **分支**：`fix/w1118-data`（worktree `/var/minis/shared/work/incr1118/wt8`）
- **基线**：`c85131ad`（= 当前 main，已含 FIX-1117B 五支）
- **报告来源**：`Wave_1118_D5_Report.md`（88 文件 / 24,380 行，17 条 INV + 8 条发现 + 5 条未确认）
- **allowlist 遵守**：只改 `data/**` 与其测试 + 本报告；未碰 `scripts/scan/**`、`service/**`、`scheduled/**`、`provider/**`、`sandbox/**`、`ui/**`
- **未跑全量** `scan.sh` / `test_scan.py` / gradle（本批无扫描器改动，夹具按设计跳过）

---

## ① 判级表（本支全部 8 条 + 报告未确认清单）

报告给的 `medium/low` 一律按触发路径重判（修复门：谁、在什么操作下会遇到）。

| ID | 报告严重度 | 本支判级 | 结论 | 去向 |
|---|---|---|---|---|
| F-02 `parseImportedModelEntries` 对 `supportsReasoning` 只判 `has()` | medium | **P1** | 确认成立（真码 3448 / 3500 行）。`has()` 对显式 `null` 为 true，`optBoolean` 把 NULL 读成 `false` ⇒「未知（继承 catalog／启发式）」被永久改写成「明确不支持」，聊天里 Deep Thinking 开关消失；`mergeImportInstanceJSON` 的 `restored == local` 幂等判断同时被破坏。**触发面**：用户从外部 JSON 导入 provider（iOS 端导出、手工编辑的备份）。本仓 `exportInstanceJSON` 只写非 null（`?.let { put(...) }`），所以**自己导出的包不会出现 null**——这点在判级时如实计入。 | **已修** |
| F-03 `updateMessageParts` 不刷新 `sessions.last_message` | medium | **P1** | 确认成立。消费者 = 会话抽屉（`ChatHistoryDrawer.kt:139/605` 读 `session.lastMessage`；`MoveToSessionSheet.kt:91` 同）。`rerunFromToolBlock` 先 `deleteMessagesAfter` 使被裁剪的 assistant 行**成为尾行**再原地改写，摘要于是停在被裁掉的工具调用／已删轮次上，直到下一次 append。 | **已修** |
| F-01 `loadAll` 自动发现分支漏 `version = parsed.version` | medium | **P2→顺路修**（判级下调） | 确认成立（真码：构造处无 `version`）。实际影响窗口只有「本次进程」：下一次 `loadAll` 的 DB 行路径经 `SkillMetadataSync.reconcile` 会把 version 纠回磁盘值；期间 UI／导出读到 `1.0.0`。触发面 = agent/用户直接在沙盒写 `SKILL.md`（本应用常见路径），属**误导性显示**而非数据丢失。 | **已修**（同一文件内、1 行＋抽一个构造函数） |
| F-05 `KeyRoulette.persistLocked` 非原子覆写 | low | **P2→顺路修** | 确认成立（`f.writeText(root.toString())`）。后果限于「轮询顺序回零」，同文件 `rewriteSanitized` 现成。 | **已修**（1 行＋测试） |
| F-06 `renameSkill` 不同步内存 `frontmatter` | low | **P2** | 确认成立。`update()` / `rescanFromDisk` 都会刷新该字段，只有 rename 不刷新。 | **已修**（同文件、抽纯函数） |
| F-07 `delete` / `loadAll` 清理 overrides 未递增 revision | low | **P2** | 确认成立，且**违反类 KDoc 自述的不变量**（「Bumped by every write to session_skill_overrides」）。 | **已修**（2 处各 1 行＋源码契约测试） |
| F-08 `PendingUpdateStore.getPending` 未校验目标版本 | low | **P2** | 确认成立，纯契约漂移：唯一调用方 `UpdateChecker.resumablePending` 已自行兜底（`UpdateChecker.kt:638`）；KDoc 却向未来调用方承诺了这个门。 | **已修**（纯门函数＋默认参数，6 行） |
| F-04 删会话未清 `webapp_shortcuts`（`path_scope=session_attachment`） | medium | **P2 / 跨域** | 部分成立：`WebAppShortcutDao` 确实没有按会话删除的接口（只有 draft→真 id 的两条 UPDATE），但清理点要挂在 `ChatRepository.deleteSession` 的注入链上——`MinisApp.kt`（`webAppShortcutRepository` 的构造点，不在 `data/**`）必须参与，**超出本支 allowlist**。 | **未修·登记**（见 ③ 与 ④） |

报告 §5.4 的 5 条未确认项：**均不升格**（证据不足，维持原判），逐条去向见 ④ 末尾。

---

## ② 修的逐条证据

### 前置：本地验证装置（沙箱无 Android SDK，`data/**` 大部分需 Context）

`shared/work/incr1118/jvm8/`（生成器 `build_jvm8.py`，可重跑）：

- **生产代码逐字拷贝**（不转写）：`KeyRoulette.kt`、`VersionCompare.kt`、`ProviderRepositoryUtils.kt` 整文件；`PendingUpdateStore.pendingTargetIsStale` 函数原文；
  `SkillRepository` 的 `ImportSource` / `Skill` / `ParsedSkill` / `skillFromDiscoveredMd` / `skillAfterRename` / `renameFrontmatterName` / `splitKeepingLineEndings` ＋ `SubagentSkill.extractFrontmatterBlock` 原文装进一个 `object SkillHarness`（唯一改动是外层容器——真身是需要 Context 的 class）；
- **测试文件除容器名外不改**（`SkillRepository.` → `SkillHarness.`）；
- **22 条测试全绿**（cwd = 修复后 worktree）。

> **未覆盖**：`ChatTailPartsPreviewRefreshTest` 需要 `ChatDao`/Room/Android 栈，沙箱编译不了 `ChatRepository`（它 import `android.database.sqlite.*`）。这一条**只有 CI 证据**（见下），本报告不把它算作本地证据。

### F-02 三态布尔（`ProviderRepositoryUtils.kt` + `ProviderRepository.kt`）

- 新增纯函数 `optionalBooleanOrNull(obj, key)`：`has && !isNull` 才取 `optBoolean`，否则 `null`。
- 3448 行 `supportsReasoning`、3500 行 overrides 层 `supportsReasoning` 改用它。
- **`declaresNoEffortTiers` 保持原来的内联写法**：它本来就有 `has(...) && !isNull(...)`（正确），而 `provider_import_id_roundtrip_check` arm 3 把 `optBoolean("declaresNoEffortTiers")` 当作「这个字段真被读了」的锚点。第一次提交顺手把它并进统一读取器，CI 这个门立刻红（第一轮 CI 见 ⑤）。按「门锚点是冻结面」的纪律，回退这处纯洁癖改动——统一读取器只服务 `supportsReasoning` 两个点。
- **测试**：`ProviderTriStateImportTest`（6 条）＝ 读取契约 5 条（absent→null、显式 null→null、true/false 保持、`"null"` 字符串仍是 false、非布尔标量维持 `optBoolean` 原答案）＋ **wiring 1 条**（读 `ProviderRepository.kt` 源码：两处 `optionalBooleanOrNull(...)` 必须在、`has("supportsReasoning")` 必须不在、`declaresNoEffortTiers` 的 `!isNull` 守卫必须仍在）。
- **变异/负控**：
  - 控制 1（基线树 `git show c85131ad:ProviderRepository.kt` 作为 cwd 的源）：wiring 用例**红**（`ProviderTriStateImportTest.kt:75`），契约 5 条仍绿（它们测的是新纯函数）——证明这条 wiring 断言真的在看调用点。
  - 删掉 helper 的 `!obj.isNull(key)`（等价于回到 `has()` 语义）→「explicit json null stays unknown」红。

### F-03 尾行摘要同步（`ChatRepository.kt` + `ChatDao.kt`）

- `updateMessageParts` 里**保留原样**的写调用 `dao.updateMessageParts(id, capPartsJsonForRow(partsJson))`（`parts_json_funnel_check` R4 就锚这一行字面量），紧随其后 `refreshTailPreviewAfterPartsRewrite(id, partsJson)`。
- 新私有函数：
  1. `dao.sessionIdOf(id)`（**新增的列投影**：`SELECT session_id FROM messages WHERE id = :id`）取会话 id —— 不能用 `dao.getMessage(id)`：那是整行读，会把可能 2 MB 的 `parts_json` 拖过 CursorWindow，`dao_loadmessages_check`（D/ISSUE-08）明确禁止仓库内出现 `dao.getMessage(`；
  2. `dao.lastMessageParts(sessionId) != capPartsJsonForRow(partsJson)` ⇒ 不是尾行，返回（尾行才是抽屉那一列的权威）；
  3. `extractTextPreview(partsJson)` 为 null ⇒ 返回（与 `updateSessionPreview` 同口径：绝不用空值覆盖好摘要）；
  4. 否则 `dao.updateLastMessage(sessionId, preview, now)`。
- **代价**：一次短列查询 + 一次尾行 `parts_json` 读（该值由漏斗封顶 500 KB；`lastMessageTailPerSession` 启动时天天读同一列，暴露面没有增加）+ 第二次 `capPartsJsonForRow` 字节计算。都在「用户点一次重跑 / 一个回合结束」的频率上。
- **测试**：`ChatTailPartsPreviewRefreshTest`（5 条，`ChatDao by` 委托到既有 `ChatRepositoryCreateSessionTest.RecordingDao` ＋ 4 个状态化 override，其中 `sessionIdOf`、`lastMessageParts`、`updateLastMessage`、`updateMessageParts` 都落到真实 fake 状态）：尾行→写 `s1 to "after trim"`；非尾行→**不写摘要但照旧改写 parts**；`[]`（无可用预览）→不写；未知 id→不抛。
- **变异（设计；本地无法编译 ChatRepository，见前置说明）**：去掉 `refreshTailPreviewAfterPartsRewrite` 调用 → 第 1 条红；去掉 `!= capPartsJsonForRow(...)` 判断 → 第 2 条红。

### F-05 原子写（`KeyRoulette.kt`）

- `persistLocked` 末行 `f.writeText(root.toString())` → `rewriteSanitized(f, root)`（同文件已有的 `.tmp` + `Files.move(ATOMIC_MOVE)`，失败回退非原子 move）；KDoc 补一句「热路径也用它」。
- **测试**：`KeyRouletteAtomicPersistTest`（2 条）：① 抽一次 key（`next` 双 key 才进 persist）后目标文件内容含新 provider 切片、含旧 provider 切片、无 `.tmp` 残留，**且 inode 变了**（rename 换 inode；原地 truncate 不换——这是两条路径唯一可靠的可观测差异，见下）；② 连续三次抽 key 后文件仍是可解析 JSON、两 provider 都在、无明文 key、无 `.tmp`。
- **变异/负控**：控制 2 把 harness 里那行改回 `f.writeText(root.toString())` →「a rotation draw replaces the state file…」**红**（`KeyRouletteAtomicPersistTest.kt:56`）。
- **判据选择记录（踩过一版）**：最初用「目标文件只读 ⇒ truncate 失败、rename 成功」区分两条路径——**沙箱/CI 以 root 跑，只读文件照样能写**，该用例对着 pre-fix 代码也是绿的（假绿）。已换成 inode 判据并删掉只读版。这条写在这里是因为它是「测试必须真的能红」的实例。

### F-01 / F-06 技能字段（`SkillRepository.kt`）

- 新增 companion 纯函数：
  - `skillFromDiscoveredMd(id, parsed, raw)` —— 唯一构造点，`version = parsed.version` 不再漏；
  - `skillAfterRename(current, newName, renamedDocument, now)` —— `frontmatter` 跟随刚写入的文档（`extractFrontmatterBlock` 取块，空块回退旧值，与 `update()` 的 `ifBlank` 极性一致）。
- 调用点：`loadAll` 自动发现分支改用前者；`renameSkill` 记住「实际写盘的新文档」再交给后者（null = 没改写，保留旧块）。
- **测试**：`SkillAutoDiscoverFieldsTest`（3 条：version=2.3.0 硬编码字面量；id/name/description/body/frontmatter 全落地；`importSource == SESSION` 保持）＋ `SkillRenameFrontmatterSyncTest`（4 条：内存 frontmatter 跟随 rename（用**生产** `renameFrontmatterName` 产出文档）、扩展键 `subagent`/`max_turns` 存活、`renamedDocument == null` 保留旧块、无 frontmatter 的文档不把旧块洗白）。
- **变异/负控**：控制 3 删 `version = parsed.version` →「the parsed version reaches the row」**红**；控制 4 去掉 `frontmatter = …` 那三行 → rename 的两条**红**（另两条断言的是「不该洗白」的守卫，按设计仍绿）。

### F-07 revision 递增（`SkillRepository.kt`）

- `delete(id)` 在删 `session_skill_overrides` 后递增；`loadAll` 的 stale 清理在 `staleIds.isNotEmpty()` 时递增（并合并进已有的同名 if 块）。
- **测试**：`SkillSessionOverridesRevisionContractTest`（2 条）：① 对源码中**每个** `DELETE FROM session_skill_overrides` 命中点做「所在类级函数体必须含 `_sessionOverridesRevision.value`」（函数边界按 4 空格缩进的 `fun` 头切）；② 门自检——`delete` 与 `loadAll` 必须仍出现在这些写者名单里（改名/搬迁会让 ① 静默失去覆盖）。
- **变异/负控**：控制 1（基线源码）→ ① **红**（`SkillSessionOverridesRevisionContractTest.kt:64`）；返回修复后树 → 绿。
- **诚实标注**：这不是行为测试（`SkillRepository` 需要 Context + SQLite，没有 JVM 缝）。它证明「每个写点都带 bump」，不证明「订阅方真的重查」——后者靠 `sessionOverridesRevision` 既有消费者。

### F-08 版本门（`PendingUpdateStore.kt`）

- 新增文件级 `internal fun pendingTargetIsStale(target, running)` ＝ `compareVersions(normalizeTag(target), normalizeTag(running)) <= 0`；`getPending(context, currentVersionName = BuildConfig.VERSION_NAME)` 在解析成功、未超 24h 之后加这个门（命中即 `clearPending` + 返回 null），KDoc 承诺与实现对齐。
- **测试**：`PendingTargetStaleGateTest`（5 条：同版本 stale、更旧 stale、更新 resumable、`v` 前缀 + `+build` 后缀不参与比较、`1.0.0-rc1` 对 `1.0.0` 视为 stale）。
- **变异/负控**：控制 5 把 `<= 0` 改成 `< 0` → 3 条红。
- **保留的双门**：`UpdateChecker.resumablePending` 里那份比较**没有删**——它带自己的 TAG 日志且先于 `verify`，删它属于「顺手动别人的分支」，且双门无害。

---

## ③ 未修项的三件套草案（F-04）

**F-04 · 删会话不清 `webapp_shortcuts`（`path_scope = "session_attachment"`）**

- **触发条件**：用户在会话附件上「添加到桌面」生成 web app 快捷方式，然后删除该会话（或该会话被空会话清扫掉）。桌面图标仍在，点击 → `WebAppPathResolver.resolveSession` 解析到 `minis-sessions/<已删 sid>/attachments/...` → 报「Source Missing」。
- **方案**（跨域，需两步）：
  1. `data/db/WebAppShortcutDao` 加 `@Query("DELETE FROM webapp_shortcuts WHERE path_scope = 'session_attachment' AND scope_context = :sessionId")`（与既有 `renameScopeContext` 同形，单语句，不读改写）；
  2. `ChatRepository.deleteSession`（或在 `onSessionDeleted` 钩子的 `MinisApp` 实现里）调用它——**后者更干净**：钩子已经是「跨库会话级清理」的唯一汇聚点，`MinisApp.kt:592` 已持有 `webAppShortcutRepository`，无需给 `ChatRepository` 加第二个 DAO 依赖。
- **搁置理由**：① 需要 `MinisApp.kt`（不在本支 allowlist，`data/**` 之外）；② 影响是「遗留一个坏图标」，不是数据/凭据损失；③ 报告自己标 probable（未能 100% 排除 `MinisApp` 已有替代清理），落地前应先在真机确认清理确实缺失。
- **落地支建议**：与「会话删除级联」相关的支一起做（`service/` 或 `MinisApp` 面），顺手补一条「删会话后 `webapp_shortcuts` 无残留」的 repository 级测试。

---

## ④ 跨域指针

1. **F-04** → `MinisApp.kt`（`data/**` 之外）+ `data/db/WebAppShortcutDao.kt`（本域，但单独立项更清楚）。同上三件套。
2. **报告 §5.4-4（`ConfigBackup.importChatSections` 是否接入 `capPartsJsonForRow`）** → 属 `backup/**`；`ChatRepository.kt:1446` 的 KDoc 已经断言「应复用」，本轮未核（本支不改 backup）。若下一轮审 backup，第一条就该核这个。
3. **报告 §5.4-1（`deleteEmptySessions` 候选集 > 999 触发 `SQLITE_MAX_VARIABLE_NUMBER`）** → 调用方在 `MinisApp` 启动清扫，不在本域；未升格。
4. **报告 §5.4-2 / §5.4-3 / §5.4-5** → 分别涉及 provider 空 `models` 导入语义、`SkillRepository.updateFromURL` 与 `delete` 的并发窗口、`MountedFoldersStore` × `PRootKernel` 联动；均**证据不足，维持不升格**，留给对应用户域的下一轮。
5. **本支未覆盖的验证面**：`ChatRepository` 侧改动（F-03）在沙箱里编译不了，只有 CI 单测证据；真机上「从工具块重跑 → 抽屉摘要立即变成裁剪后的文本」需要一次手测（见真机清单建议：在会话里触发一次 tool_use → 长按消息「从工具块重跑」→ 立刻返回会话列表看第二行）。

---

## ⑤ CI（两轮，第一轮红得有价值）

### 第一轮 `#3213` / run `44021044118` @ `b588be89` —— **scan-gate 红**（其余四个 job 绿）

`unit-tests` / `build-apk` / `lint` 全绿，只有 `scan-gate` 红，三条门报 **ROT**（我改了它们锚定的位置）：

| 门 | 报的行 | 我改了什么 |
|---|---|---|
| `parts_json_funnel_check` [R4] | `updateMessageParts writes the payload verbatim` | 把 `dao.updateMessageParts(id, capPartsJsonForRow(partsJson))` 拆成了 `capped` 变量，门按字面量锚这几句 |
| `dao_loadmessages_check` [D/ISSUE-08] | `direct DAO read — dao.getMessage( bypasses loadPageTolerant` | 新加的摘要刷新用 `dao.getMessage(id)` 取 `sessionId` = 整行读（正是这条门存在的理由） |
| `provider_import_id_roundtrip_check` [arm 3] | `does not optBoolean("declaresNoEffortTiers")` | 把这个本来正确的内联三态读「统一」进了 `optionalBooleanOrNull`，门的锚串消失 |

**处置（只改生产代码，不碰 `scripts/scan/**`——本支禁碰）**：
- R4：恢复原字面量调用，摘要刷新放到它**之后**；
- D/ISSUE-08：新增 `ChatDao.sessionIdOf(id)` = `SELECT session_id FROM messages WHERE id = :id`（单列投影，无 blob），尾行判定仍在 Kotlin 里做（保住可测性）；
- arm 3：回退 `declaresNoEffortTiers` 的纯洁癖改动（`supportsReasoning` 仍走统一读取器）。

**本地复核（18 个门，全部绿）**：三个失败门 + 14 个邻门 —— `chat_session_id_binding` / `chat_ui_interaction` / `compact_entry_pin_migration` / `entry_composite_key_guard` / `entry_id_rename_integrity` / `four_way_sync` / `model_entry_override_parity` / `openminis_compat_field_parity` / `provider_models_cache_wiring` / `share_dir_prune` / `skill_frontmatter_roundtrip` / `skill_import_offmain` / `skill_restore_session_cleanup` / `log_url_redaction_guard`。邻门清单由「`scripts/scan/*.py` 里提到我改的 6 个文件的脚本」反查得到，不是凭记忆挑的。

**这条修复本身的一个发现**：`ChatDao` 加抽象方法会打断 6 个测试 fake 的编译（`RacingDao` / `RecordingDao`×2 / `DaoLoadMessagesConvergenceTest` / `InertChatDao` / `PartsJsonFunnelTest`），已全部同步补 `sessionIdOf`——这正是日报里那条「给 DAO 接口加方法，先 grep `: ChatDao` 找全部测试桩」的复发预防。

### 第二轮 `#3219` / run `44023547185` @ `0277767c575932ea9d2541b259f453b34c850e56` —— **五 job 全绿**

| job | 结论 | 证据行 |
|---|---|---|
| `unit-tests` | success | `BUILD SUCCESSFUL in 6m 24s`；全程 `9386` 条 PASSED；**本批 27 条新用例逐条 PASSED**（`KeyRouletteAtomicPersistTest` 2 · `PendingTargetStaleGateTest` 5 · `ChatTailPartsPreviewRefreshTest` 5 · `ProviderTriStateImportTest` 6 · `SkillAutoDiscoverFieldsTest` 3 · `SkillRenameFrontmatterSyncTest` 4 · `SkillSessionOverridesRevisionContractTest` 2） |
| `scan-gate` | success | `Summary: 115 passed, 0 failed`；`parts_json_funnel_check: PASS` / `[dao-loadmessages] clean` / `PROVIDER_IMPORT_ID_ROUNDTRIP: clean (13 arms)`；**夹具真跑**（`scanners moved in this push … running the fixture self-test` → `ALL 354 CASES PASS`，本批未改 `scripts/scan/**`，这一跑是白赚的） |
| `build-apk` | success | 产物 `RikkaMinis-arm64-v8a.apk` 14.8 MB |
| `lint` | success | — |
| `publish` | success（**步骤级**：`Move the rolling tag` / `Publish to Releases` 均 **skipped**） | main 门控生效，分支不会污染 release 资产 |

**head_sha 核对**：`0277767c575932ea9d2541b259f453b34c850e56` 与代码 tip 逐字符一致。本报告随后单独提交，**tip 相对 CI sha 只差本文件**（`git diff --name-only 0277767c` 应为空）。

**停止节点**：分支 CI 绿 + sha 对齐 = 本支工作结束。**不合并 main**（并集合并由主会话做）。

