# FIX-META1028-E6 — 设置屏两文件修复 B（B06 F-12 / F-13）

- **分支**：`fix/meta1028-e6-settings-b`
- **基线**：main `bfb5b0e6`
- **门提交**：`2628d8ce`
- **来源**：meta-audit-1028 第二轮货架报告 B06（设置屏）F-12 / F-13
- **改动面**：2 生产 `.kt` + 1 新生产 `.kt` + 2 新测试 `.kt` + 7 份 `strings.xml`
  （`scripts/scan/**`、`scan.sh`、`test_scan.py`、`data/**`、`sandbox/**` **零触碰**）

---

## 1. 先证结论（两条都真，但都与报告的措辞不同）

报告证据转写有损（本批通病：函数名为转写者概括，非真码标识符），故两条都**回真码按语义核**，不按报告行号对齐。

| ID | 报告声称 | 真码核实 | 判定 |
|---|---|---|---|
| F-12 | 底部栏排序区把条目拖到末尾槽位会顶走不可拖拽的 `SETTINGS` 行 ⇒ UI 顺序与磁盘配置失步 | **成立，但"末尾槽位"不是必要条件**：只要被拖行在 `SETTINGS` **正上方**且下移一格即触发。且"失步"是真三重失步（见 §2） | ✅ 真，修 |
| F-13 | 本地文件导入无 `isLoading` 门禁 + 硬编码英文报错 + 重命名弹窗丢弃 `update` 返回值 | **三条全真，且范围比报告大**：硬编码英文是 **5 处**（报告说"报错"未给数），其中 2 处在**详情页 update-from-file**，不在导入弹窗 | ✅ 真，修（按实质扩到同族 2 处，半径纪律内：同文件同一族） |

### 证伪记录（报告有一半错，已按纪律不硬修）

- 报告暗示 `ChatMenuPrefs.KEYS` 是过滤来源 —— 真码**没有 `KEYS` 成员**，只有 `ALL_ENTRIES` / `DEFAULT_ORDER`；过滤发生在 Compose 侧（`ChatActionCatalog.spec(it)?.defaultMenuVisible != false`）与 `settingsPinOrder()`。按语义取 `SETTINGS` 墙即可，无需碰 prefs 层。
- F-12 的"UI 顺序与磁盘配置失步"不是"写盘用错顺序"，而是**写盘用的正是错顺序**（`writePinOrder(prefs, next)` 落的就是被顶走后的列表）。修法因此不是改写盘，而是**让落点计算不产生错顺序**。

---

## 2. F-12：SETTINGS 墙

### 根因（真码）

`ChatMenuSettingsScreen.kt` 的 `DraggableActionRow` 落点计算（基线）：

```kotlin
val targetIndex = (currentIndex + delta).coerceIn(0, currentOrder.lastIndex)
```

`currentOrder.lastIndex` 是**列表最后一项**，而底部栏列表是 `settingsPinOrder(prefs)` =
`resolvePinOrder(prefs).filterNot { it == SETTINGS } + SETTINGS` —— **最后一项恒为 `SETTINGS`**。
`SETTINGS` 那一行被 compose 成 `draggable = !isSettings`（抓不住），但**别行的手势可以落到它头上**。

于是拖它上面那一行（`footer_token_usage`，index 10）下移一格 ⇒ `targetIndex = 11` ⇒ 与 `SETTINGS` 交换。

### 三重后果（不是"顺序失步"一句话）

1. **列表自相矛盾**：`SETTINGS` 排到倒数第二，而它自己那行仍显示"fixed right"提示（`chat_menu_settings_always_last`）。
2. **落盘错**：`commitFooterOrder` → `writePinOrder` 把这个顺序写进 `chatMenu.pinOrder`。
3. **拖拽完全无效**：抽屉渲染走 `resolvePinnedOrder` → `anchorSettingsLast`，**又把它锚回最后** —— 用户想排的那一行没动，唯一动的是他不该抓的那一行。

### 修法

- 抽纯函数 `ChatMenuDragReorder.kt`（新文件，生产 `.kt`）：
  - `chatMenuDragTargetIndex(order, currentIndex, delta, lockedKeys)` —— 逐步向 `currentIndex + delta` 走，**遇到 locked 行即停在上一个可达位**（墙不可穿越、不可落）；locked 行自身被拖也不动；越界 index 返回自身（no-op）。
  - `chatMenuDragSwap(order, from, to)` —— 交换两槽；no-op / 越界**返回同一实例**（`assertSame`），使"没落点的拖动不可能写出重排列表"。
- 调用点：`lockedKeys: Set<String> = emptySet()` 新增参数（默认空 ⇒ A/C 两区行为不变），底部栏传 `setOf(ChatMenuPrefs.SETTINGS)`。
- `currentLockedKeys by rememberUpdatedState(lockedKeys)` —— 与既有的 `currentDraggingEntry` / `currentDragOffset` 同一条冻结规则：**pointerInput 的协程在 launch 时冻结捕获的局部变量**，而底部栏的 `setOf(...)` 是每次重组新建的实例，不经 `rememberUpdatedState` 会留下一份陈旧集合。

**为什么是"墙"而不是"过滤"**：`SETTINGS` 行必须**始终在列表里**（否则取消固定后无从重新固定，`settingsPinOrder` 的 KDoc 明写）。过滤会丢行；夹取区间只改落点，不改列表成员。

---

## 3. F-13：技能导入 / 重命名三缺陷

### 3.1 本地文件导入无 busy 门禁（真）

`SkillImportSheet` 的 `GetContent` 回调在 `scope.launch` 里做最多 32 MiB 读 + 可能的解包，而 `isLoading` **只有 URL / 粘贴两个页签设置**，文件页签既不设也不读。第二次点"选择文件"⇒ 第二次导入并发 ⇒ 输家撞到已插入的 id ⇒ 报**内容错误**（"Invalid SKILL.md content"）而非"还在导入"。

修：`if (isLoading) return@rememberLauncherForActivityResult` + `isLoading = true`，按钮加 `enabled = !isLoading` 并把标签换成 `skill_import_in_progress`（文件页签此前连进度指示都没有）。

**busy 标志的清除位置是刻意的**：`isLoading = false` 写在 `scope.launch` **主侧**（IO 块之外），不写在 IO 块内 —— 标志属于 UI，从后台线程翻它是跨线程状态写（本仓在别处 pin 的读改写形状）。

### 3.2 硬编码英文报错（真，5 处不是 4 处）

| 位置 | 原文 | 改后 |
|---|---|---|
| 导入弹窗·读失败 | `"Failed to read file"` | `R.string.skill_file_error_read` |
| 导入弹窗·归档无效 | `"Invalid skill archive — no SKILL.md found at the root or one directory deep"` | `R.string.skill_file_error_archive` |
| 导入弹窗·内容无效 | `"Invalid SKILL.md content"` | `R.string.skill_import_error_format`（**复用既有键**，不新增） |
| 导入弹窗·URL / 粘贴 catch（2 处） | `"Error: ${e.message}"` | `R.string.skill_file_error_detail`（带 `%1$s`） |
| 详情页·update-from-file（3 处，同族扩到） | 同上三条 | 同上三条 |

**throwable 的 message 是数据不是散文**：只把框架本地化，`e.message ?: e.javaClass.simpleName` 作为参数传入 —— 避免"本地化失败时显示 null"。

**未改（刻意）**：`safTooLargeText` 返回的 `"File is too large to import (limit N MB)"` 也是英文字面量，但它在 `SafImportRead.kt`（**另一个文件**），且 backlog §7312 已登记其 i18n 后续（触发条件 = 下一批允许改资源文件或用户报"提示是英文的"）。本支**不跨文件抢**（半径纪律）；不碰它的另一个理由：它是"超限"不是"失败"，与本次三键语义不同族。

### 3.3 重命名丢弃 `update` 返回值（真）

```kotlin
// 基线
scope.launch { withContext(Dispatchers.IO) { skillRepository.update(skill.id, name = trimmed) } }
showEditNameDialog = false          // 无条件关闭
```

`SkillRepository.update` 返回 `Boolean`（`_skills.value.find { it.id == id } ?: return false`）。返回 false = 该 id 已不在仓库（技能被删 / 被重载掉），此时**关窗 = 假成功**：用户看到弹窗消失、名字没变，无任何提示。

修：`isRenaming` busy 门禁（写会重写 SKILL.md，双击不能发两份）+ `val ok = withContext(Dispatchers.IO) { … }` + `if (ok) showEditNameDialog = false else renameError = …`（失败时**弹窗保留**并内联显示 `skill_detail_error_update_failed`）。

**新增 3 个 `R.string`**，同步全部 **7 份** `values*/strings.xml`（`values` + de/ja/ko/ru/zh/zh-rTW；`values-night` 无 strings.xml，共 8 个目录 7 份文件）：

| key | en | zh | ja | ko | de | ru | zh-rTW |
|---|---|---|---|---|---|---|---|
| `skill_file_error_read` | Could not read the selected file | 无法读取所选文件 | 選択したファイルを読み込めませんでした | 선택한 파일을 읽을 수 없습니다 | Die ausgewählte Datei konnte nicht gelesen werden | Не удалось прочитать выбранный файл | 無法讀取所選檔案 |
| `skill_file_error_archive` | Invalid skill archive: no SKILL.md found inside | 技能包无效：其中未找到 SKILL.md | skill アーカイブが無効です: SKILL.md が見つかりません | 잘못된 skill 압축 파일입니다: SKILL.md 를 찾을 수 없습니다 | Ungültiges Skill-Archiv: keine SKILL.md enthalten | Недопустимый архив навыка: SKILL.md не найдена | 技能壓縮檔無效：其中未找到 SKILL.md |
| `skill_file_error_detail` | Import failed: %1$s | 导入失败：%1$s | インポートに失敗しました: %1$s | 가져오기 실패: %1$s | Import fehlgeschlagen: %1$s | Сбой импорта: %1$s | 匯入失敗：%1$s |

键名用 `skill_file_error_*` 而非 `skill_import_error_*`：后者在 `values/strings.xml` 已存在 3 个（`no_url` / `invalid` / `format`），新增键若继续用 `import_` 前缀会被误读成同一族；`file_` 表明"来自文件读取路径"（两条路径共用）。

---

## 4. 装置与证据

### 4.1 真 JVM 执行（27 例，期望值全写字面量）

- `ChatMenuDragReorderTest`（13 例）：墙不可穿越 / 长拖停在墙前 / 跨墙 = 阻挡步 / locked 行自身不动 / 自由移动不变 / 上下夹取 / delta=0 / 越界 / 空墙 = 修前行为 / 多格跳跃是**交换不是插入** / no-op 返回同一实例 / 模拟完整下拖后 `SETTINGS` 仍在最后（**并给出修前对照**：同一 walk 在空墙下把 `SETTINGS` 埋到 index 10）。
- `SkillsImportRenameGuardTest`（14 例）：busy 门禁三态（设置点 / IO 之前 / 主侧清除）/ 按钮 disabled / 英文串不回归 / 四键走资源 / 重命名绑 `val ok` + 条件关窗 / 关窗点只允许 3 处且必须被结果门控 / busy 门禁 / **7 份语言文件键齐全 + 译文不等于英文**（防"复制英文当译文"的静默兜底）。

### 4.2 变异矩阵（13 臂，每臂只红钉它的那几例）

| 臂 | 变异 | 红用例 |
|---|---|---|
| PRE(baseline `bfb5b0e6`) | 修前真树 + 修前资源 | **11 条红**（形状面全红，纯数学面因新文件不存在而不适用） |
| POST(worktree) | — | **27/27 绿** |
| M1 忽略 locked 集合 | `return (currentIndex + delta).coerceIn(...)` | 5 条墙用例 |
| M2 墙可穿越 | 删 `if (order[probe] in lockedKeys) return reachable` | 5 条墙用例 |
| M3 locked 行可拖 | 删 `if (order[currentIndex] in lockedKeys) return` | `draggingTheLockedRowItselfNeverMovesIt` |
| M4 no-op 返回副本 | `return order.toList()` | `noOpSwapReturnsTheSameListInstance` |
| M5 交换改插入 | `next.add(to, next.removeAt(from))` | `multiRowJumpSwapsTheTwoSlots_itDoesNotInsert` |
| W1 底部栏不传墙 | 删 `lockedKeys = setOf(...)` | `footerPassesSettingsAsTheWall` |
| W2 恢复内联夹取 | 换回 `(currentIndex + delta).coerceIn(...)` | `dragHandlerUsesThePureDecision` |
| W3 英文串回来 | 换回 `"Error: ${e.message}"` | `noEnglishErrorLiteralsRemain` |
| W4 去掉 busy 门禁 | 删 `if (isLoading) return@...` | `fileTabRejectsASecondPickWhileImporting` + `fileTabSetsAndClearsTheBusyFlag` |
| W5 无条件关窗 | `if (ok)` → 裸赋值 | `renameConsumesTheUpdateResult` + `renameIsNotDismissedUnconditionally` |
| W6 一份语言文件丢键 | values-ja 删 `skill_file_error_read` | `newKeysExistInEveryLocaleFile` + `translationsAreNotEnglishFallbacks` |

**M4 / M5 两臂是补出来的**：首轮 M5（交换→插入）全绿 = 装置有洞（纯矩阵只测了 ±1 与 no-op，交换与插入在单格移动上等价）。补一条多格跳跃用例后 M5 才红 —— **这轮的教训：抽"纯函数"时，"等价的另一种实现"本身就是最该钉的变异臂**。

### 4.3 协程行为探针（14 断言）

Compose 屏无法在沙箱编译（无 Android SDK），但 F-13 两处改动的**表达式形状**可以：把
`val failure: String? = try { withContext(Dispatchers.IO) { …labeled return… } } catch { getString(...) }`
与 `val ok = withContext(IO) { update(...) }` 逐字复刻进 `probe-e6/shape/TypeProbe.kt`，对**真 kotlinx-coroutines** 编译并运行：
8 条导入结局矩阵（成功/归档坏/内容坏/超限/读失败/抛异常带 message/不带 message）+ 6 条重命名矩阵（true→关窗无错、写真的发生了、写的是 trimmed 名；false→**弹窗留着**带原因、写仍被尝试）。**TYPEPROBE OK (14)**。

它证明的是语义（labeled return 集合 / `String?` 推断 / catch 分支 / `val ok` 绑定），**不**证明周围 Compose 调用点仍类型正确 —— 那一半交给 CI 的 Gradle 全量编译。

### 4.4 邻门（只跑自家窄验证，禁全量）

| 门 | rc |
|---|---|
| `skill_file_save_result_check.py` | 0（5/5 + offmain 3/3 + round3 4/4） |
| `skill_import_offmain_check.py` | 0（17 臂） |
| `skill_frontmatter_roundtrip_check.py` | 0（23 臂） |
| `chat_menu_prefs_listener_check.py` | 0（7 sites） |
| `i18n_check.py` | 0（ALL CLEAN —— 含新增 3 键无孤儿） |

`scan.sh` / `test_scan.py` **未跑**（纪律：多会话并行，禁全量）。

---

## 5. 行为变化（用户可见）

1. **底部栏**：拖 `SETTINGS` 上面那一行不能再把它顶走。想换的位次现在**真的生效**（此前被 `anchorSettingsLast` 吃掉）。
2. **技能导入·文件页签**：导入中按钮变"导入中…"并禁用，第二次点击被忽略（此前静默并发）。
3. **报错文案**：7 个语言下均为本地化文本（此前 5 处恒英文）。
4. **重命名失败**：弹窗保留 + 红色原因（此前关窗、无声无息）。

---

## 6. 残留（未修，登记）

| ID | 内容 | 为什么不动 | 触发条件 |
|---|---|---|---|
| R1 | `safTooLargeText`（`SafImportRead.kt`）仍是英文字面量，本支未改 | 跨文件（半径纪律），且 backlog §7312 已登记带触发条件的后续 | 下一批允许改资源文件 / 用户报"提示是英文的" |
| R2 | 底部栏 `SETTINGS` 未固定时，仍可被别行挤到中间（`settingsPinOrder` 只在**读**时锚尾，落盘的 `pinOrder` 不锚） | 本支只改落点计算；改落盘语义会动 `settingsPinOrder` 契约（另一族） | 用户报"取消固定后 SETTINGS 位置不对" |
| R3 | A / C 两区（`...` 菜单、附件 `+` 菜单）仍用 `emptySet()`：它们没有墙对象，落点 = `lastIndex` 是正确行为 | 无缺陷 | — |
| R4 | 重命名失败的**具体原因**未区分（`update` 只给 Boolean，不区分"id 没了" vs "写盘失败"） | 要区分得改 `SkillRepository.update` 返回类型（跨 `data/**`，禁区） | 用户报"重命名失败但技能还在" |
| R5 | 导入 busy 期间**弹窗可被下拉关闭**（`ModalBottomSheet.onDismissRequest` 不受 `isLoading` 门控），协程仍在跑 | 关闭后 `scope.launch` 随 composition 销毁而取消，属既有语义；改了要引入"不可取消的导入"这层设计 | 用户报"关掉弹窗后导入没完成" |
| R6 | 真机未验（沙箱无 Android 运行时；CI 只保证编译 + 单测 + 门） | — | — |

---

## 7. 环境事实（沙箱）

- `kotlinc` 的 `-cp` 需显式带上 `kotlin-stdlib-2.1.0.jar`（`/var/minis/shared/work/jvm-libs/`）；运行时同样要，否则 `NoClassDefFoundError: kotlin/KotlinNothingValueException`。
- `readlink -f $(which kotlinc)` 指向的 `lib/kotlin-stdlib.jar` 是**旧版本**，与 `-cp` 混用会 CLASSNOTFOUND；直接用 `jvm-libs` 下带版本号的 jar。
- 变异臂用**镜像树 + 改文本**（不重编译）跑形状断言、用**重编译**跑纯逻辑，两种分开 —— 形状臂镜像必须带齐**当前**（非基线）的 `strings.xml`，否则 locale 断言连带报红，看不出红来自哪。
- Kotlin **一个文件只能有一个 `package`**：想给 Compose/Material3 建全套 stub 必须拆成多文件；本轮评估后改为"只复刻表达式形状 + 真协程"（§4.3），成本从"补 400 行 stub"降到 1 个文件 200 行，且不重复 CI 的编译覆盖。

---

## 8. 收口状态

- [x] 本地 27 例绿；13 臂变异各钉其红；协程探针 14 断言绿；5 个邻门 rc=0
- [x] push + `gh-actions-dispatch`（run created 2026-10-28T13:15:00Z）
- [x] **CI 绿**：run [`40694700743`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40694700743) — conclusion `success`，20.7 min（13:15:03 → 13:35:43），job `122838263175`，**head_sha `2628d8ce739546a21677c3480dc0aff0bcfcbb10` 与门提交逐字符一致**
- [x] 本报告提交后 push；`git diff --name-only 2628d8ce..HEAD -- . ':(exclude)fix-reports'` = **空**

**CI 日志逐条判读**（1,503,788 字符 / NUL 0）：

| 判据 | 读数 |
|---|---|
| `Summary: 113 passed, 0 failed` | ×1 |
| `ALL 349 CASES PASS` | ×1 |
| 门头 `[N/113]` | 113 个，编号 1..113 互异 |
| `^ e: `（Kotlin 编译错） | **0** |
| `BUILD SUCCESSFUL` | ×4 |
| 含 `FAILED` 的行 | 6 条，**逐条确认全是 PASSED 用例名子串**（`RealAgentAdapterSkeletonTest` / `ChatAgentTraceObserverSchemaTest` ×4 / `TerminalErrorSurfacedTest`） |
| 新类 `ChatMenuDragReorderTest` | **17 条全 PASSED**（本地 27 = 本类 17 + `SkillsImportRenameGuardTest` 10） |
| 新类 `SkillsImportRenameGuardTest` | **10 条全 PASSED** |

**停止节点达成**，停在分支。

**合并 main 的起手权在用户**（本支有 `.kt` 验证面，不适用纯文本例外）。
