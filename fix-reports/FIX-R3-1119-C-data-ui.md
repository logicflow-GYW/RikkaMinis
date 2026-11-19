# FIX-R3-1119-C —— data + UI 八条（F-06 / F-07 / F-08 / F-09 / F-13 / F-14 / F-15 / F-16）

分支 `fix/r3-data-1119` · worktree `shared/work/r3full/data` · 基线 `ecb03d92`
代码 tip `4d5b6242` · 来源货架报告 `meta-audit/reports/fullscan1119r3/Rikkaminis_Fullscan_Report_Round3.md`
判级口径 `meta-audit/fullscan1119r3/TRIAGE-R3.md`

**未合 main**（任务书 §4：CI 绿 + head_sha 一致 = 停止节点）。

## 0 八条总览

| ID | 判级 | 先证结论 | 修法 | 变异臂 |
|---|---|---|---|---|
| F-06 | P0 | **成立**：`messagesLast` SQL 已是 `sort_order DESC`，快路径多一次 `.asReversed()` 翻成 ASC，而降级路径是 DESC ⇒ 预算裁剪保留最旧、丢最新 | 删掉 `.asReversed()`，两条路径同向；KDoc 写明「不要再加回」 | M1 |
| F-07 | P0 | **成立**：API 26–29 的 `SQLITE_MAX_VARIABLE_NUMBER = 999`，`NOT IN (:list)` 整表绑定 ⇒ 1200 条目录的每次保存整事务回滚 | 四个 `NOT IN` 删除全部按 900 分块（同族一起处理），分块策略是顶层纯函数 | M2 M3 M4 |
| F-08 | P0 | **成立**：`deleteRecursively()` 跟随目录软链 ⇒ 删技能可清空外部整棵树 | 新 `SkillTreeGuard.deleteTree`：软链按链接删，永不进入目标 | M5 M6 M10 |
| F-09 | P0 | **成立**：`walkTopDown()` 跟随软链、无环检测、无边界 ⇒ 环死循环 OOM + 导出打包目录外文件 | 同一 `SkillTreeGuard.listFilesUnder`（canonical + 前缀守卫 + fail-closed + visited + 深度上限），导出写入点再校验一次 | M7 M8b M9 M11 M12 |
| F-13 | P1 | **成立**：`runCatching` 吞 `CancellationException` ⇒ 用户按停止被记成工具失败并写 `.learnings/ERRORS.md` | 显式 `try/catch`：CE 透传，真失败保留原语义 | M13 |
| F-14 | P1 | **成立**：`RenderMdAudio` 是 `MinisAudioBlock` 的孪生组件，F-12 漏了它 ⇒ prepare/release 跨线程竞态 + 失败后兜底不可达 | 复用 F-12 的 `SerialMediaOps` 与 `prepareFailed`，不新造原语 | M14 M15 M16 M17 |
| F-15 | P1 | **成立**：`@JavascriptInterface` 跑在 JavaBridge 线程，三个错误早退直接写 `MutableState` | 三处都走 `post { }`，且每个 hop 重新查 latch | M18 M19 M20 M21 |
| F-16 | P1 | **成立**：无 `.bak` 时 `restoreOfficial` 直接 return ⇒ 关开关后沙箱永久走自定义源 | 纯决策 `decideMirrorRestore` 三态 + `officialConfigContent` 从同一目录取官方源 | M22 M23 M24 M25 M26 |

**无一证伪。** 八条全部按真码重新取证（不按报告行号）。

## 1 F-06 —— 备份裁剪丢最新消息

### 1.1 先证

`ChatDao.messagesLast`（`ChatDao.kt:131`）SQL 是 `ORDER BY sort_order DESC LIMIT :limit`；降级路径 `ChatRepository.kt:604` 是 `for (offset in (total - 1) downTo start)`，也是 DESC。快路径 `:584` 多一次 `.asReversed()` 把**只有健康路径**翻成 ASC。

`ChatBackupBudget.packMessagesIntoBudget`（`:129-160`）从头顺序装入、超预算即 `break` ⇒ 头部是什么就保留什么。因此预算不足时保留的是 200 条里最旧的一段，**最新的对话被静默丢弃**，且备份文件写出去之后无法恢复（FIX-META1028-D1 引入的回归）。

### 1.2 修法

删除 `.asReversed()`，两条路径同向（DESC）。KDoc 补写不变量与「不要再加回来」，并注明这是 FIX-META1028-D1 的回归。

### 1.3 证据

`ChatTailBackupOrderTest`（3 例，JUnit）：
- 快路径 200 条 ⇒ `first == m199`、`last == m0`，并逐条比对 `(199 downTo 0)`；
- 降级路径（DAO 抛 `CursorWindow` 异常）⇒ 同上；
- **预算只够 50 条 ⇒ 断言保留 `m199..m150`**（首末 id 写死，不是只断条数）；`budget - 1` 恰好掉到 49 条，证明切口就在边界上。

装置用**真实** `ChatRepository` + **真实** `ChatBackupBudget.packMessagesIntoBudget` + **真实** `ConfigBackup.utf8Length`（`extract.py` 逐字切出），只有 Android 专属服务（`AppLogger` / `MemorySpikeRecorder` / `SessionFileStore` / `CrashFrequencyDetector`）是桩。

## 2 F-07 —— `NOT IN` 未分块

### 2.1 先证

`syncAllIncremental` 把全部 `keepEntryIds` 一次传给 `deleteEntriesNotIn`。`minSdk = 26`，API 26–29 的 SQLite 3.18–3.28 硬上限 999，超限在**绑定阶段**报 `too many SQL variables`，整个 `@Transaction` 回滚 ⇒ `provider.db` 永不落库。同文件另有三个 `NOT IN`（instances / groups / meta）同一形状。

### 2.2 修法

- 新增 `SQLITE_MAX_VARIABLE_NUMBER = 999` 与 `SQL_DELETE_CHUNK_SIZE = 900` 两个常量，注释写清来源与为何取 900。
- 新增顶层纯函数 `chunkedIds(ids, chunkSize)`：空表返回空（SQLite 不接受 `NOT IN ()`），非正 chunkSize 抛 `IllegalArgumentException`。
- `syncAllIncremental` 的四个 `NOT IN` 删除全部改为 `for (chunk in chunkedIds(...)) delete*NotIn(chunk)`。
- **DAO 保持全部抽象方法**：分块策略放在调用点而非 DAO 默认方法里，Room 不需要为它生成 super-call 桥（仓库里唯一验证过的默认方法形态是 `syncAllIncremental` 自己）。
- 事务性不变：所有分块仍在同一个 `@Transaction` 内，任一块失败整事务回滚，不会留下半删的表。

### 2.3 证据

`ProviderConfigDaoChunkingTest`（7 例）：`chunkedIds` 的 900/300 切分与空表/非法 chunkSize；998 / 999 / 1000 / 1200 四种规模的每条语句都 `< 999`；1000 拆成 `900 + 100`；驱动**真实** `syncAllIncremental` 断言 1200 条 entry 的删除切成 900+300、instances/groups/meta 同样分块。

## 3 F-08 —— 删技能跟随符号链接

### 3.1 先证

`SkillRepository.delete` 调 `dir.deleteRecursively()`。`skillsDir` 是挂进 PRoot 的共享目录，用户与 agent 都能写入；技能目录里一个 `ref -> ../../memory` 就让删技能清空真实记忆目录，不可逆。

### 3.2 修法

新 `SkillTreeGuard.deleteTree(root)`（Android-free）：
- `Files.isSymbolicLink` 判链接，**链接按链接删**（`delete()`），永不 `canonicalFile` 进目标；
- 真目录后序递归（子先父后）；
- 不存在 ⇒ 返回 true（幂等）。

`delete()` 改为 `if (!SkillTreeGuard.deleteTree(dir)) Log.w(...)`，失败可见而非静默。

### 3.3 证据

`SkillTreeGuardTest` 的 F-08 段（4 例，全部在 JUnit 临时目录，`outside` 是同级临时目录，**绝不碰 `/var/minis/memory` 或 `skills`**）：
- 技能目录含指向外部树的链接 ⇒ 删完外部 `GLOBAL.md` 与嵌套文件完好；
- 指向外部文件的链接 ⇒ 链接被删、目标文件完好；
- 普通目录树 ⇒ 完全删除（**阴性对照**）；
- 不存在的树 ⇒ 返回 true。

`SkillTreeGuardWiringTest` 断言 `delete()` 真的调用 `SkillTreeGuard.deleteTree` 且函数体内不再出现 `deleteRecursively()`（注释剥离后判，因为修复的 KDoc 自己就提到旧形状）。

## 4 F-09 —— `walkTopDown()` 跟随链接、无环检测

### 4.1 先证

`listFilesUnder` 被 `listSkillFiles` / `exportSkillToZip` / `deleteStaleSiblingFiles` 三处调用。`walkTopDown()` 跟随软链：`ln -s . loop` ⇒ 无限下潜 OOM；指向根外的链接 ⇒ `exportSkillToZip` 把私有文件打进可分享 zip。

### 4.2 修法（与 F-08 共用同一原语，不写第二份）

`SkillTreeGuard.listFilesUnder(root)`：
1. **canonicalize**：每条路径过 `canonicalPath`，`..` 与绝对前缀先折叠；
2. **前缀守卫**：`canonical == rootCanonical || startsWith(rootCanonical + File.separator)`（`/root/ab` 不算在 `/root/a` 内）；
3. **fail-closed**：无法 canonicalize 或落在根外 ⇒ 跳过；
4. 软链既不下潜也不列出；`visited` 集合使环终止；`MAX_DEPTH = 64` 是兜底硬停。

`SkillRepository.listFilesUnder` 变成一行委托。`exportSkillToZip` 在 `putNextEntry` 之前再加一次 `SkillTreeGuard.isInside(dir, src)` —— 离开设备的写点不依赖遍历器正确。

### 4.3 证据

`SkillTreeGuardTest` 的 F-09 段（9 例）：
- `ln -s . loop` + `scripts/back -> scripts` 的环 ⇒ 有限终止且结果正确；
- 指向根外**文件**的链接 ⇒ 不列出；指向根外**目录**的链接 ⇒ 不下潜；
- **根内**的文件链接 ⇒ 不列出（这是「永不跟随链接」的独立见证：前缀守卫抓不到它）；
- 深度上限从两侧各钉一条（超限不可达 / 刚好可达）；
- 正常深目录完整列出（**阴性对照**）；隐藏项跳过；非目录根返回空；
- `isWithinRoot` 的共享前缀陷阱、`isInside` 的 `..` 与绝对路径逃逸。

**M7/M8 的纵深防御说明（重要）**：只拆「软链跳过」时 `visited` 仍挡住环，只拆 `visited` 时软链跳过仍挡住环 —— 两条互相掩盖，**单臂无法独立观测**。因此 `visited` 的有效性用**组合臂 M8b（两道同时拆）**证明：组合臂红，说明两条各自都在起作用。M7 靠根内**文件链接**独立翻红（前缀守卫抓不到它）。这是本批唯一一处「预期内不可单臂观测」，已在臂表注释中写明。

## 5 F-13 —— `conversation_history` 吞取消

### 5.1 先证

`executeConversationHistoryTool` 用 `runCatching { loadRows(sessionId) }`。`runCatching` 连 `CancellationException` 一起接住 ⇒ 用户按停止被转成 `success = false`，`ChatPromptAndTools.kt:401` 走 `toolFailureHook.recordFailure(...)`，把 `JobCancellationException` 永久写进该会话的 `.learnings/ERRORS.md` —— 一条由正常用户动作制造的「这个工具坏了」的误导记录。

### 5.2 修法

改成显式 `try { } catch (e: CancellationException) { throw e } catch (e: Exception) { ... }`，失败分支语义逐字保留（`success = false` + 带异常类型与 message 的输出）。

### 5.3 证据

`ConversationHistoryToolTest` 新增 2 例（原 23 例全过，共 25）：
- `loadRows` 抛 `CancellationException` ⇒ 断言异常**传播**（不是 `success = false`）；
- `loadRows` 抛 `IOException` ⇒ 仍 `success = false` 且输出含 `could not read this conversation's history` 与原始 message（**阴性对照**，防止修法把真失败也吞掉）。

## 6 F-14 —— 主聊天音频卡漏 F-12 的两半

### 6.1 先证

先读 `fix-reports/FIX-R3-C.md` §3：F-12 修的是 `MarkdownText.MinisAudioBlock`，而主聊天实际渲染的是 `StreamingMarkdownText.RenderMdAudio` —— 孪生组件被漏掉。两半缺陷都在：
1. `prepare()` 在 `Dispatchers.IO`、`onDispose` 在主线程 `release()`；协程取消无法中断已进入 native `prepare()` 的线程 ⇒ 同一 native 句柄并发 prepare/release；
2. clickable 只看 `prepared`，而 tap 处理器自带 `!prepared` 分支去调外部播放器 ⇒ **恰恰需要兜底的文件**（容器解码不了）把自己的兜底禁用了。

### 6.2 修法（复用 F-12 的原语，不新造）

- `serialOps = remember(player) { player?.let { SerialMediaOps() } }`；
- `LaunchedEffect` 内 `withContext(Dispatchers.IO) { ops.runPreparing { setDataSource; prepare; true } }`；
- `onDispose` 里 `ops.enqueue { p.release() }` + `ops.shutdown()`（**排队而非内联**，release 不阻塞主线程）；
- `prepareFailed` 状态 + 门 `enabled = file != null && (player == null || prepared || prepareFailed)`。

### 6.3 证据

`StreamingMarkdownAudioHostTest`（3 例，函数体级源码断言）：
- prepare 在 `runPreparing` 内、release 是 `enqueue { p.release() }`、有 `ops.shutdown()`、旧的 `onDispose { try { player?.release() ... } }` 已消失；
- `prepareFailed` 被记录（`prepareFailed = !ok`）且进入 enable 门；外部播放兜底调用仍在；
- `prepared` / `prepareFailed` / `isPlaying` / `positionMs` 四个状态仍各自 key 在 `resolvedMediaSourceKey(file)`（dv19-B06 契约的回归护栏）。

装置的两个真实坑（都修好了，记下来）：`stripComments` 必须带字符串状态 —— 文件里的 `"audio/*"` 会让朴素的 `/*` 检测吃掉整个后续文件；花括号配对必须在「字符串已抹白、长度不变」的副本上做，否则 `"${...}"` 模板会让配对错位。

## 7 F-15 —— KaTeX 回调在 JavaBridge 线程写 Compose 状态

### 7.1 先证

`AndroidBridge.onRendered` 跑在 Chromium 的 JavaBridge 线程。成功路径（`:336` 起）全部 `post { }` 切主线程，但三个错误早退（`:291` / `:296` / `:311`）直接写 `renderError`（`remember(cacheKey) { mutableStateOf<String?>(null) }`）。后台线程写 Compose 快照状态 ⇒ 最坏 `IllegalStateException` 崩溃，最好也是错误被静默丢失 ⇒ 该行永远停在空白占位 `Box`。

`onRendererGone` 的 `renderError` 写入**不动**：它是 `WebViewClient` 回调，框架在主线程投递。

### 7.2 修法

三处都改成：
```kotlin
post {
    if (!releaseLatch.canRunDeferred()) return@post
    renderError = ...
}
```
每个 hop 重新查 latch（行可能在回调与 hop 之间被拆掉，这是既有 S-07 契约）。

### 7.3 证据

`KatexBridgeThreadHopTest`（2 例）：
- `onRendered` 内**每一处** `renderError =` 都必须落在某个 `post { }` 块内（逐处判定，不是计数）；
- **每一个** `post { }` 块都必须以 `if (!releaseLatch.canRunDeferred()) return@post` 开头（逐块判定 —— 第一版写成全函数计数，M21 因此假绿：主体自带的 latch 检查加上任意一个保留检查就凑够了数）。

## 8 F-16 —— 关自定义镜像后沙箱永久锁死

### 8.1 先证

`restoreOfficial` 只有 `.bak` 路径。全新 Alpine rootfs 没有 `root/.npmrc`，`applyMirror` 的备份守卫（`if (!bakFile.exists() && configFile.exists())`）无物可备，随后写入自定义 `root/.npmrc`；关开关时找不到 `.bak` 就 `return` ⇒ UI 显示官方、沙箱内 `npm`/`npx` 永久走自定义源。

### 8.2 修法

- 新纯函数 `decideMirrorRestore(bakExists, configExists)` 三态：`RESTORE_FROM_BACKUP` / `OVERWRITE_WITH_OFFICIAL` / `NOTHING_TO_DO`。
- 新纯函数 `officialConfigContent(category)`：从**同一个** `MirrorCatalog` 取 `isOfficial` 条目，两个方向不会指向不同主机。NPM 写 `registry=https://registry.npmjs.org/` 而**不是删文件** —— `root/.npmrc` 可能还带用户自己的无关设置，契约是「镜像源回官方」而非「你的 npm 配置没了」。
- `restoreOfficial` 改为 `when (decideMirrorRestore(...))`，三个分支都实现（未实现的分支会静默什么都不做，正是原缺陷的形状）。

### 8.3 证据

`MirrorRestorePlanTest`（8 例，期望值全是硬字面量，官方主机名逐个拼出，并断言**不含** mirror 主机）：三态决策；NPM / ALPINE / PIP 三个官方体逐字比对；每类都有非空官方体；每类恰有一个 `isOfficial` 条目。
`MirrorRestoreWiringTest`（3 例）：`restoreOfficial` 真的调用 `decideMirrorRestore`、真的写 `officialConfigContent(category)`、旧的 `skipping restore` 早退已消失、三个枚举 action 都有分支。

## 9 变异负控（26 臂）

装置 `shared/work/r3full/harness/mutate.py`：每臂改一处、重跑对应组的 JVM 装置、记录哪些测试类变红、**先 `finally` 还原再校验 sha256**（不用 `git checkout`）。

| 臂 | 内容 | 结果 |
|---|---|---|
| M1 | F-06 快路径加回 `.asReversed()` | RED-PRECISE |
| M2 / M3 / M4 | F-07 entries 不分块 / instances 不分块 / chunkSize 提到 1200 | 三条 RED-PRECISE |
| M5 / M6 | F-08 链接按目标删 / 按 canonicalFile 删 | 两条 RED-PRECISE |
| M7 | F-09 遍历跟随链接 | RED-PRECISE（靠根内文件链接） |
| M8b | F-09 软链跳过 **与** visited 同时拆 | RED-PRECISE（组合臂，理由见 §4.3） |
| M9 | F-09 前缀守卫丢掉分隔符 | RED-PRECISE |
| M10 / M11 / M12 | F-08 接线回 `deleteRecursively` / F-09 接线回 `walkTopDown` / 导出写入点丢掉边界校验 | 三条 RED-PRECISE |
| M13 | F-13 去掉 CE 重抛 | RED-PRECISE |
| M14 / M15 / M16 / M17 | F-14 release 内联 / prepare 离开串行 worker / 门丢掉 prepareFailed / 标志不记录 | 四条 RED-PRECISE |
| M18 / M19 / M20 / M21 | F-15 三个错误分支各回后台线程 / hop 丢掉 latch 复查 | 四条 RED-PRECISE |
| M22 / M23 / M24 | F-16 无备份又跳过 / npm 官方体写镜像主机 / 官方体写空 | 三条 RED-PRECISE |
| M25 / M26 | F-16 接线回早退 / overwrite 分支写空 | 两条 RED-PRECISE |

**26 臂全 RED-PRECISE，零无关测试翻红。** 每臂还原后 sha256 与变异前逐字符一致。

## 10 本地装置与门

- JVM 装置 `shared/work/r3full/harness/`（`run.sh` / `extract.py` / `mutate.py` / `stubs/`）：**7 组 77 用例全绿**。
- 全量门本地：`python3 scripts/scan/scan_parallel.py --root . --jobs 4 --allow-local` ⇒ **115 passed, 0 failed**（`scripts/scan/**` 本支未改动）。

## 10.1 CI 证据（停止节点）

**run #3345 / `44479125062` @ `25098e82eb447d1a527f087ea74889fc45e7110f`**
本地 HEAD / 远端分支 tip / run `head_sha` **三方逐字符一致**。

| job | 结论 |
|---|---|
| unit-tests | success — **9870 PASSED 行 / 0 真 FAILED**，本支新增 44 条逐条 PASSED |
| scan-gate | success — `115 passed, 0 failed`；`scripts/scan/** untouched in this push — skipping the fixture self-test`（本支未动门，绿不含夹具证据） |
| lint | success |
| build-apk | success |
| publish | 两个发布步骤（Move the rolling tag / Publish to Releases）**skipped**（main 门控生效） |

**#3342 / #3343 的 lint 红是 Maven 依赖抖动，不是代码问题**：两次都失败在 `:app:generateDebugAndroidTestLintModel` 解析 `debugAndroidTestRuntimeClasspath` 时找不到 `hamcrest-integration-1.3.jar` / `hamcrest-library-1.3.jar` / `javawriter-2.1.1.jar`，**编译之前**的依赖解析阶段。三条 URL 实测 HTTP 200；同一代码形态的 #3341 与重跑后的 #3345 lint 均绿；本支未触碰任何构建文件（`git diff --name-only ecb03d92..HEAD` 无 gradle/settings 文件）。

### 10.2 首次 CI 抓到的真问题（已修）

#3341 单测抓到一条真失败：既有 `MediaHostSourceKeyTest` 把 `StreamingMarkdownText` 的「resolved-file-keyed 状态数」钉成**死字面量 4**，注释明写「本渲染器在该波次半径外，保持原有四个」。F-14 让 `prepareFailed` 成为第五个，字面量随之更新为 5，注释改为说明原因。
这正是该测试存在的意义：一个 keyed 状态族悄悄长大而无人察觉，就是它要挡的 dv19-B06 回归。已作为独立提交 `25098e82`（F-14 follow-up）。


## 11 残留与登记

1. **`MCPOAuthController.kt:123` 仍打 `state?.take(8)`** —— 同族（凭据进日志），但该文件不在本支文件集，未动。
2. **F-09 的 `MAX_DEPTH = 64`** 是兜底而非业务语义：真技能树远浅于此。若将来有合法更深的树，改这个常量即可，不要在遍历里加例外。
3. **F-16 的 `officialConfigContent(PIP)` 里 `trusted-host` 写死 `pypi.org`** —— 从 `baseURL` 解析 host 也可以，但官方源固定是 `pypi.org`，写死让「官方」这件事更难漂移。
4. **`deleteStaleSiblingFiles` 现在也走受限遍历**：它本来就自己做了 canonical 前缀校验，行为不变，只是遍历源换了（软链不再被列出，因此也不会被当作 stale 去删）。
