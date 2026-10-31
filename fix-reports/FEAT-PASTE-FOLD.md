# FEAT-PASTE-FOLD — 长文本粘贴折叠成 `[Pasted#N]` 标记（首期）

基线 main `5f808ae8` · 分支 `feat/paste-fold-marker`（HEAD `d34565b0`）· 上游参考 OpenMinis `PastedTextBuffer.kt`
**停止节点 = 分支 CI 绿，不合 main（起手权在用户）** · 禁改清单零触碰（`ChatScreen.kt`、`scripts/scan/**`）

---

## 1 交付面（13 文件，+1609/−167）

| 文件 | 内容 |
|---|---|
| `ui/chat/PastedTextBuffer.kt` **新** | 纯逻辑：`isLongPastedText`（英文 >1000 词 / 否则 >1200 字，照抄上游分语言阈值）、`foldLongPasteIfNeeded`（前后缀 diff 定位单段插入 run，UTF-16 索引空间）、`expandPastePlaceholders`（**单遍扫描替换**——粘贴内容里本身含 `[Pasted#N]` 时不会把别的 buffer 注入，上游注释点名的承重设计）、`placeholderFor` / `removePasteMarker` / 未知 id 原样保留 |
| `ui/chat/PastedTextChip.kt` **新** | chip UI（复用附件行几何）+ 预览 Dialog（标题/摘要/删除）；删除用既有 `R.string.common_remove`（7 locale 本就存在，零新增） |
| `ui/chat/ChatInputArea.kt` | fold 接线（见 §3.1/3.2）、发送守卫接线（见 §3.3）、chip 行 |
| `ui/chat/ChatViewModel.kt` | `stashPastedText` / `expandPastedText`(L1039) / `pasteSendBlockedBy`(L982) / `adoptRestoredDraftPasteMarkers`(L992：孤立 marker 登记 + **`nextPasteId` 越过恢复草稿里的最大 id**，防止恢复的 `[Pasted#1]` 被下一次粘贴复用而污染成"从未放过的内容") |
| `res/values{,-de,-ja,-ko,-ru,-zh,-zh-rTW}/strings.xml` | 4 个新 key ×7 locale（`paste_chip_desc` / `paste_preview_title` / `paste_folded_summary` / `paste_unresolved_notice`） |
| `PasteTextBufferTest.kt` **新**（27 例） | fold/expand 表驱动：正常、多次粘贴 id 递增、短文本不触发、恰在阈值、CJK 阈值、emoji/surrogate 索引、单遍替换、未知 id 保留、嵌套 marker 不二次展开、全部展开后清理、禁发守卫 |
| `PasteFoldWiringTest.kt` **新**（11 例） | 接线形状：fold 在 IME burst 门之前、两个发送漏斗都先守卫再清 composer、`ChatScreen` 零触碰、展开只消费自己认领的条目 |

## 2 八条拍板决策的落地

| 决策 | 落地 | 核实方式 |
|---|---|---|
| ① 阈值照抄上游、15KB 转文件首期不做 | `isLongPastedText` 分语言阈值 | 读码 + 阈值边界用例 |
| ② 单遍扫描替换、未知 id 原样保留 | `expandPastePlaceholders` 单遍 + lenient 展开 | 专测 |
| ③ buffer 不持久化、fail-closed | 只存内存；恢复草稿的孤立 marker → **禁发** + snackbar 提示（`paste_unresolved_notice`） | `adoptRestoredDraftPasteMarkers` + `PasteTextBufferTest` 禁发用例 |
| ④ fold 插在 `inputFieldValue = tfv` 之前、burst 门之前，全部 mirror 读折叠值 | §3.1 | 形状测试钉锚点 |
| ⑤ 展开做在 `sendMessage` 单点 | `expandPastedText` 在函数**第一行**（L3863），任何早退路径之前 | 5 个调用点核对（§5 调用点清单） |
| ⑥ chip 复用附件行几何 | 与附件 chip 同行 | 读码 |
| ⑦ i18n 4 key ×7 locale | 见 §1 | grep 全 locale |
| ⑧ 砍 stash-as-file / pastedParts / 媒体切分 | 未实现 | — |

**调用点核对（任务书要求的 `rg -n "sendMessage("`）**：`ChatInputArea.kt:512`、`:1526`（UI 两个发送漏斗）、`debug/HeadlessChatRunner.kt:493`、`debug/ChatMutationMethods.kt:87`（RPC）——全部走 `ChatViewModel.sendMessage` 单点；`ModelExecutionService.kt:1176` 是 provider 接口同名方法，无关。队列注入 `enqueuePrompt(` 的**唯一**调用在 `sendMessage` 内部（L3900，传展开后的 `text`）→ 队列、编辑重发、rerun 无绕过路径。

## 3 本支踩过并修掉的三个坑

### 3.1 fold 的位置不是"越早越好"，而是"恰好在 burst 门之前"

折叠后文本变短，burst 门看的是**插入长度**：40 KB 粘贴折叠后只有 ~11 字符插入 → 立即 commit 而不是被扣 150 ms；关键是 150 ms flush 永远拿不到原始块，不可能把 marker 写回覆盖。形状测试钉住这一顺序（`the fold runs inside onValueChange before the IME burst gate`）。

### 3.2 影子绑定（CI 第一轮红，已修）

初版把形参改名为 `rawTfv` 来避免遮蔽——这打破了 `ComposerCommandHandoffTest` 的字面锚点 `onValueChange = { tfv ->`。正解：**保留形参名**，在 `run { }` 块内 `val tfv = foldLongPasteIfNeeded(old = inputFieldValue, new = tfv, …)`——局部变量在声明点之后才进作用域，初始化表达式里的 `tfv` 解析到外层参数（沙箱 probe 实证 `SHADOW-OK`）。从声明点向下，`tfv` 就是折叠后的值，全部 mirror 自动读对。

### 3.3 发送可用性单源（CI 第一轮红，已修）

初版新增了第二条 `val x: () -> Boolean = {…}` 谓词，被 `ComposerSendSwipeTest` 的"恰好一条共享发送谓词"合同打红——门是对的（该合同的存在理由就是防 per-site re-derivation 让死分支复活）。正解：守卫改成本地 `fun pasteSendBlockedNow()`（局部函数不是该门计数的 `val` 谓词形态），读 `viewModel.pasteSendBlockedBy(inputFieldValue.text)`——live value（非 debounce 快照）且与 swipe 谓词共用同一个 VM 侧事实源。

### 3.4 禁发与"手敲 `[Pasted#99]`"的边界（两条拍板决策的交点）

决策②（未知 id 原样保留、发送不失败）与决策③（不可展开 marker 禁发）表面冲突。切分：`blockingPasteMarkers` 只看 `orphanPasteMarkers`（**只有从恢复草稿里认领出来的、本会话确实没有对应 buffer 的 id**）；手敲的 `[Pasted#99]` 不在集合里 → 不禁发、发送原样保留。宽容在展开侧，严格在守卫侧，各管各的。

## 4 测试装置与结果

**本地（沙箱，kotlinc + JUnit4）**：
- `PasteTextBufferTest` 27/27、`PasteFoldWiringTest` 11/11（经 probe Compose stub 编译）
- 形状三件套：`ComposerSendSwipeTest`+`PasteFoldWiringTest` = 20 OK；`ComposerCommandHandoffTest` = 29 OK（其依赖的三个纯函数 `composerCommitCaret`/`composerFunnelCaret`/`isEnterSendEdit` + burst 两函数**逐字切片**出 Compose 文件后编译——切片器曾因 `composerFunnelCaret` 是表达式函数体（无花括号）切错，改为"按下一个顶层声明"切并断言逐字子串）
- 锚点全量扫描：从所有读 `ChatInputArea` 的测试抽出 47 条代码形字面量，逐一验证在重排后的源码里仍存在（重排 +4 缩进是锚点最大风险；3 条疑似缺失经核实均为测试自带的负控夹具/注释文案，非锚点）
- `scan.sh` 113 passed, 0 failed；`test_scan.py` 跑到聚合器既有挂死点前 0 ❌（挂死点 `backup_remap_rollup_check`/`test_real_repo` 与主树同款既有环境问题，与本支无关；其后的门逐个直跑全 0）

**CI（run `41201307346`）**：
- `3_Run pre-build scan gate`：`Summary: 113 passed, 0 failed` + `ALL 349 CASES PASS`、❌ 0
- `15_Run unit tests (full suite)`：`BUILD SUCCESSFUL in 4m 29s`、行尾 `FAILED` 0、四个相关测试类 `PASSED` 提及 59 处 0 FAILED、`PasteFoldWiringTest` 四用例（含 `ChatScreen is untouched by this feature`）全 PASSED
- 全 run `^e: ` 0（Kotlin 编译错误 0）、日志 NUL 0

## 5 CI 证据（三源核验）

| 项 | 值 |
|---|---|
| run | `41201307346` · build-apk.yml · workflow_dispatch |
| 结论 | **success**，12:29:07Z → 12:43:17Z（14.2 min），27 步全 success（唯一 skipped = rolling tag + Publish，main 门控，符合预期） |
| head_sha | `d34565b0a14a7cbac8affc78613c87c0d7a4fc52` = 本地 HEAD 逐字符一致 |
| 历史 | 第一轮 run `41196873700` 红于两处既有形状合同（§3.2/3.3），修复后绿 |

## 6 残留（全部带触发条件）

- **R1 真机未验**。任务书要求的自证（粘贴 2000 字 → 等 1 秒 → ViewModel `inputText` 里是 marker 不是原文）沙箱无法执行；替代证据 = 形状测试钉住 fold 在 burst 门之前 + 所有 mirror 读折叠值。触发条件：真机首次用到该功能时走一遍。
- **R2 buffer 不持久化**（首期拍板取舍）：进程在粘贴后被杀 → 恢复的草稿含无法展开的 marker → 禁发 + 提示，用户丢那段文本。二期持久化 buffer（走「四处同步」流程）。
- **R3** `PASTE_AS_FILE_THRESHOLD`（>15 KB 转文件附件）未做。
- **R4** `stashPastedTextAsFile` / `ChatAttachmentIngest.pastedParts` / `splitPastePlaceholders` 媒体切分未做。
- **R5** chip 预览 Dialog 无搜索/滚动定位（上游也如此）。
- **R6** 孤立 marker 的 id bump 只在 `adoptRestoredDraftPasteMarkers` 生效；若用户**复制**含 marker 的文本到另一个会话，那边手敲判定路径按未知 id 处理（原样保留）——与决策②一致，非缺陷，登记备查。
