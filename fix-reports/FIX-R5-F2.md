# FIX-R5-F2 — 沙箱软链导航（进入即空 / 预览失败）+ 终端复用后 initCommand 不重投

**分支** `fix/r5f-sandboxnav`
**基线** main `35509888`（R5-AD 已并入）
**来源** R5-F 报告 `F-F-02`（P1）· `F-C-10` 终端项（P1）· 判级 `TRIAGE-R5-EF.md` B7 支
**改动面** 生产 3 文件（`ui/sandbox/FileBrowserViewModel.kt` +95 / `ui/navigation/AppNavigation.kt` +31 / `ui/terminal/TerminalScreen.kt` +30）+ 测试 2 新文件（12 + 7 例）+ 改既有测试 1 类（`TerminalSessionInitOrderTest` 判据改内容定位 + 加 1 条新牙）。`scripts/scan/**`、`scan.sh`、`test_scan.py`、`SymlinkEntryResolution.kt`、`assets/skills/**` 零触碰 → 门数与夹具数不变（113 / 349）。`formattedDate` 未来时间戳按判级 **P3 不修**，只在本报告 §6 登记。

---

## 1. 目标

报告两条，都是「容器里能看见、点进去就没了」这一族：

1. **F-F-02**：`ln -s /var/minis/workspace/x dirLink` 这类**容器绝对路径**软链，列表里能看见（R4-V F-11 已修），但点进去是空目录、点预览是空内容。
2. **F-C-10（终端项）**：终端已经在前台时，再从 `open_terminal?init_command=…` 打一条命令，**静默无效**。

**这改变什么用户行为？** ①容器里 `ln -s` 出来的目录/文件，点进去能看到内容、点开能预览；②终端开着时再触发一次带命令的深链，命令会落到输入行。

## 2. 先证（按语义，不按报告行号）

### 2.1 F-F-02：三处断点，其中两处是同一根因

**① 条目描述用了解析结果，读取路径却还是软链本身。** `FileItem.from`（`FileBrowserViewModel.kt:166-215`）里 `resolved = resolveListedEntry(file, rawTarget, resolveGuestAbsolute)`，但 `resolved` **只**用于 `isDirectory / size / modifiedMs`，`file = file`（原软链）。⇒ 行有文件夹外观（R4-V F-11 的成果），但所有「打开」类消费者拿到的都是软链自己的宿主路径。

**② 进入目录时解析的是「软链所在的路径」，不是软链指向的路径。** `navigateTo`（`:376`）把 `item.name` 追加到 `relativePath`，`loadItems`（`:580` 附近）再算 `currentHostPath` —— 得到的是 `<root>/dirLink`，接着 `toPath().toRealPath()`：

- PRoot 写进软链的是**壳里看到的** `/var/minis/workspace/x`，Android 宿主没有 `/var/minis` ⇒ `toRealPath()` 抛 `IOException`；
- 旧码 `catch { hostPath }` 直接回退成字面路径 ⇒ `listFiles()` 对「指向不存在目标」的软链返回 **null** ⇒ `files = emptyList()`，`isEmpty = true`，**且不设 errorMessage**（用户看到「空文件夹」，没有任何线索）。
- `dirLink/sub` 更深一层同样断：`<root>/dirLink/sub` 本身已不是软链，`isSymbolicLink` 为 false，任何「只解析叶子」的修法都覆盖不到。

**③ 预览路径有三个投递点，且接收侧无法自救。** `AppNavigation.kt:793 / 1249 / 1301` 三处都传 `item.file.absolutePath`；`FILE_PREVIEW` 目的地（`:1332`）用 `FileItem.from(file)` **默认 mapper `{ null }`** 重建 item ⇒ 即便接收侧想再解析也没有 guest→host 上下文（sessionId 不在路由参数里）。所以「在预览入口统一过一次 `resolveGuestAbsolute`」在这条链上是**做不到**的，必须在投递侧就给宿主可读路径。

**④ 为什么不能「直接把 `file` 设成 `resolved`」**：`deleteItem`（`:447-500`）用 `item.file` 删除，`shouldDeleteRecursively(item.isDirectory, item.isSymlink)` 为 false 时走 `item.file.delete()` —— 这正是 R4-V F-11 的成果（**unlink 名字**，不碰目标树）。把 `file` 换成 resolved 会让长按删除变成「删目标目录」，属于把 R4-V 修过的缺陷原样还回去。另有两处旁观者同样依赖 `file` 是 lstat 事实：`linuxPathOf(item)`（只读挂载/共享目录守卫按壳内路径判）与 `FileBrowserScreen:425` 的 `WebAppPathResolver.inferScope(item.file)`。⇒ **选「加字段」，不选「换字段」**（§3）。

### 2.2 F-C-10：`LaunchedEffect(Unit)` + 未键控的 `remember` 组合出的静默丢命令

**① 投递点**：`TerminalScreen.kt:98` 的 `LaunchedEffect(Unit)` 里 `terminalSession.state.first { RUNNING } → delay(100) → sendText(safe)`。`Unit` 永不变化 ⇒ 只在**首次组合**跑一次。

**② 复用形态**：`AppNavigation.kt:765 / 776` 两处 `launchSingleTop = true`（R4-V F-10 为了不出现两个 TerminalScreen 而加）。`Routes.TERMINAL` 内 `remember { TerminalSession(...) }` 未键控 ⇒ 新参数到达时既不会重建会话对象，也不会重跑投递。

**③ 库侧行为（用 navigation 2.8.5 的字节码核，不靠记忆）** —— 这是修法能否成立的前提，所以逐层做实：

| 层 | 证据（`navigation-runtime/2.8.5` + `navigation-common/2.8.5` + `navigation-compose/2.8.5`） |
|---|---|
| `NavController.navigate` | 调用 `launchSingleTopInternal(NavDestination, Bundle)`（字节码 offset 314） |
| `launchSingleTopInternal` | 找到匹配下标后逐个 pop，并 `new NavBackStackEntry(entry, entry.destination.addInDefaultArgs(args))` —— **无条件**用新 args 造新 entry；copy 构造保留 `id` 与 `savedState`（字段 #41/#44） |
| entry 刷新 | `navigate` 随后调 `updateBackStackLifecycle$…`；`updateBackStack` 里 `_visibleEntries.tryEmit(populateVisibleEntries())`（offset 250-257） |
| Compose 侧 | `NavHostKt` 对 `visibleEntries` 用 `collectAsState`（字节码 offset 1384） ⇒ 重组，content lambda 以新 entry 重跑，`backStackEntry.arguments` 返回新的 `immutableArgs` |

⇒ **新参数一定会走到 content lambda**，所以「效果键控」就能生效。

**④ 幂等性不能靠库的 Entry 相等**：`NavBackStackEntry.equals` 比较 `id` + `destination` + **`getLifecycle()` 实例**（offset 47-58）+ `savedStateRegistry` + `immutableArgs`；copy 构造会新建 `LifecycleRegistry` ⇒ 替换后的 entry 与旧 entry **永不相等**，`StateFlow` 不会去重，每次 singleTop 都会 emit + 重组。所以「同一命令连续两次不叠加」**必须**由我们自己的键保证（键 = 命令字符串），不能寄望于库的抑制 —— 修法与注释都按这个口径写（初稿注释曾写成「immutableArgs 相等 ⇒ Flow 抑制」，实测字节码后 **改掉了**，见 §4.3 的口径修正）。

**⑤ 不许出现第二个 PTY**：`sessionId` 在 `TerminalSession.start()` 里被烤进 proot 参数与 env（`:141/197/673`），所以「换 session」= 换 PTY 对象，而不是在同一对象上再 start 一次；`start()` 全文件只有 1 个调用点（形状判据 C 钉住）。

## 3. 修法（阶梯：停在「新增一个字段 + 一个纯函数」，没造第二套解析）

### 3.1 F-F-02

- **`FileItem.hostPath: File = file`**（默认值引用前参，已用 kotlinc 实测可编译）：读取用 `hostPath`，删除/改名用 `file`。`from()` 里 `hostPath = resolved`。
  - 影响面清点：`FileItem` 是 UI 数据类，构造点 4 处（`ChatInputArea:1332`、`ChatScreen:3919`、预览目的地 `:1332` 的兜底、`from` 自身）；前两处用具名参数 + 默认值 ⇒ **源码零改动**；`SavedStateHandle`/`remember` 键不涉及（路由参数传的是 `absolutePath` 字符串）。
- **`internal fun resolveListingHostDir(hostDir, mapGuest)`**（新增纯函数，放在 ViewModel 文件里；`SymlinkEntryResolution.kt` 按任务书禁碰）：
  1. 快路径 = 原来的 `toRealPath()`（覆盖一切非软链路径与能被宿主字面解析的软链）；
  2. 失败则**逐级上溯到最深的软链祖先**，用 `resolveListedEntry` 取映射目标，再把下游 `tail` 段重基（`dirLink/sub` ⇒ `target/sub`）；
  3. 判据：`target != node`（排除「无法映射就返回自身」的 refuse 分支）**且** `target.exists()`（不把悬空链重基到虚构路径）；都没有就原样返回 —— 与旧行为一致。
  - 为什么不是「记一个 hostDir override / 按逻辑路径 memo」：那需要在 `goBack`、面包屑跳转、逐层深入三处维护一致性（还要深度栈），而单点解析天然覆盖任意深度 —— 臂 C（去掉 tail 重基）只红「深度」一条，正是这个设计的判据。
  - `navigateTo` 因此**不需要改**：它只产出逻辑路径，解析统一在 `loadItems` 走同一个 mapper（`::resolveGuestAbsoluteTarget`，与 `FileItem.from` 同一个函数，不引入第二套解析）。
- **三处 `Routes.filePreview` 站点**改传 `item.hostPath.absolutePath`。非软链条目 `hostPath == file` ⇒ 常规路径零行为变化。

### 3.2 F-C-10

- `AppNavigation`：`remember(sessionId) { TerminalSession(...) }`。
- `TerminalScreen`：
  - 启动效果 `LaunchedEffect(terminalSession)`（会话换了就启新的）；
  - 投递效果 `LaunchedEffect(terminalSession, initCommand)`（命令变了就重投；同一条命令 → 同一个 key → 不重启 ⇒ 不叠加）；
  - 释放效果 `DisposableEffect(terminalSession)`（键控换了对象后，旧闭合包会 stop 错的会话 ⇒ 必须一起键控；否则新 PTY 永不停）。

## 4. 验证

### 4.1 JVM（真夹具，期望值写死字面量）

`ui/sandbox/FileBrowserViewModel.kt` 引 `androidx.lifecycle`/StateFlow 不能整文件编译 ⇒ `f2-probe/extract.py` **逐字切片**（`formatFileSize` / `resolveListingHostDir` / `data class FileItem` 含 `from`）到 shadow 包，切片后回读源码做「每行都存在」断言（`verbatim check OK`）。

`SymlinkListingNavTest`（12 例，真实 `Files.createSymbolicLink` 夹具）：`dirLink -> /var/minis/workspace/real`、`brokenLink`、`relLink`、`plain.txt`；断言 `hostPath` 指向映射后的真实目录、`file` 仍是链接（`assertSame`）、尺寸/目录性、深度（`dirLink/sub` 列表 1 项且是 `y.txt`）、悬空链不虚构、无 mapper 时 refuse、快路径、`hostPath` 默认值。**12/12 OK**。
`TerminalInitCommandShapeTest`（6 例，源码形状 + 逐条负控）：同源变异必须报出对应问题标签；负控分别覆盖「未键控 remember / 投递键回 Unit / 投递键丢 session / 启动键回 Unit / 释放键回 Unit / 预览站点传回 `item.file.absolutePath`」。**6/6 OK**（合计 19 例）。

### 4.2 变异臂（把缺陷塞回去，必须红）

JVM 层（`f2-probe/arms.py`，改的是**生成副本**不是生产源码）：

| 臂 | 塞回去的形态 | 期望红 | 结果 |
|---|---|---|---|
| A | `from()` 里 `hostPath = file` | `symlinkDirEntry_hostPathIsTheMappedTarget` · `relativeLink_resolvesAgainstItsParent` | MATCH |
| B | `resolveListingHostDir` 退回「`toRealPath` 否则字面」 | `…followsTheLinkTheUserJustEntered` · `…followsTheLinkAtDepth` | MATCH |
| C | 解析但**不重基 tail**（只修一层） | `…followsTheLinkAtDepth` | MATCH |
| D | `from()` 里 `file = resolved`（被否掉的选项） | `symlinkDirEntry_keepsTheLinkAsFile_soDeleteUnlinksTheName` | MATCH |

形状层（`f2-probe/shapes.py`，8 条判据各配一条「塞回去」臂）：C1 三处预览站点 / C2 `remember(sessionId)` / C3 投递键 / C4 启动键 / C5 释放键 / C6 `loadItems` 走 helper / C7 `hostPath = resolved` / C8 删除仍按链接谓词 —— live 全绿，**8/8 臂各自只红自己那一条**（`MATCH`）。

### 4.3 口径修正（自己抓到的假解释）

初稿在 `TerminalScreen` 注释里写「同一命令 ⇒ `immutableArgs` 相等 ⇒ `visibleEntries` 抑制 ⇒ 不叠加」。拉字节码后确认 `NavBackStackEntry.equals` 还比较 `getLifecycle()` 实例、而 copy 构造新建 registry ⇒ **两个 entry 永不相等**，该抑制并不存在。幂等性的真实来源是**键是命令字符串**，注释已按这个口径重写（§2.2 ④）。这条记在这里，是为了让后来者不要用一句听起来自洽的库行为替换一次实测。

### 4.4 门（只跑相关门，禁全量）

本支文件被 7 道门引用（`chat_session_id_binding`、`deeplink_settings_routes`、`mirror_category_route`、`open_url_broker_scheme`、`provider_remember_key`、`readonly_mount_write_guard`、`sysinfo_state_thread`）：**改前 7/7 绿，改后 7/7 绿**（`provider_remember_key` 需带 root 参数）。括号配平：三份生产文件的原始 `{}/()` 计数与基线逐字节相同（`0` 增量）。

## 5. CI

**第一轮 `40887216709` @ `97616902` —— FAILURE**（9 分钟，step 15 `Run unit tests (full suite)`）。唯一失败用例：`TerminalSessionInitOrderTest > screen prefills only after RUNNING is published`，原因不是实现错，而是**既有判据把预填钉在 `LaunchedEffect(Unit)` 里** —— 而 F-C-10 的正解恰恰要求换键。这是「门/夹具抓到真改动」的又一次，按仓库纪律走「判据跟上契约，契约不放宽」：

- 判据改为**按内容定位**（找出 body 含 `sendText(` 的那个 `LaunchedEffect` 块），原有三条时序不变量**一条不减**（等 RUNNING 而非 `delay(500)`、等待在发送之前、控制字符过滤仍在）；
- 新增两条牙：键里必须有 `initCommand`（否则复用目的地仍静默丢命令）、必须有 `terminalSession`（否则换 session 时继续往被替换的 PTY 写）；
- 新增负控：**时序正确但键仍是 `Unit` 的预填必须红**（并断言时序两支在该夹具上静默，保证这条测试只隔离键）。
- 改判据后本地单跑该类 `OK (6 tests)`。

**第二轮 `40889704268` @ `fe992fbe` —— success**（attempt 1，14:15:40Z→14:37:53Z，≈22.2 min）：

| 判据 | 读数 |
|---|---|
| `head_sha` | `fe992fbe8b2244d63ef509bafe1b1412d6aa6609`，与本地 HEAD **逐字符一致** |
| 步数 | 27（25 success + 2 skipped = 滚动 tag + Publish，main 门控） |
| 日志 | 1,656,789 B，NUL 0 |
| 门 | `Summary: 113 passed, 0 failed` ×1 |
| 夹具 | `ALL 349 CASES PASS` ×1 |
| 编译 | `^ e: ` 0 |
| 单测 | 含 `FAILED` 且非 `PASSED` 子串的行 = 0 |
| 本支测试 | `SymlinkListingNavTest` 12 PASSED · `TerminalInitCommandShapeTest` 7 PASSED · `TerminalSessionInitOrderTest` 6 PASSED（含新增的键牙） |

## 6. 残留与登记（只登记不修）

| # | 内容 | 级 | 触发条件 |
|---|---|---|---|
| R1 | `ChatLinkResolver.kt:66` 用 `FileItem.from(hostFile)` **默认 mapper** ⇒ 聊天里点到「容器绝对路径软链」的沙箱链接，预览仍打不开（同族、异文件、本支之外） | P2 | 用户/AI 在消息里发一条指向软链的沙箱文件链接 |
| R2 | 悬空相对链（`ln -s nowhere x`）仍列出空目录且不报错（与修复前一致，未顺手改） | P3 | 真机反馈「空文件夹没有原因」 |
| R3 | 面包屑显示**链接名**而列表显示**目标内容**；`currentLinuxPath`（复制绝对路径）给的是壳内逻辑路径（对 shell 正确）。两者天然不同，属设计 | P3 | 用户质疑「路径复制出来和看到的不一样」 |
| R4 | `FileItem.formattedDate` 对未来时间戳（`daysAgo < 0`）落到 `shortDateFormatter` 分支，不按「未来」措辞 | **P3（判级已定，不修）** | 只在设备时钟回拨/文件时间戳被写成未来时出现 |
| R5 | `resolveListingHostDir` 在解析失败路径上会**上溯到 `/`**（有界：路径深度），跑在 `Dispatchers.IO` | P3 | 无需处理，记形状 |
| R6 | 同一条命令连续两次深链 = 刻意 no-op（键相同）。用户若期望「再来一次」，需换命令 | P3（行为约定） | 真机反馈「同一命令第二次没反应」 |
| R7 | 真机未验：本支证据 = CI 编译级 + JVM 真实夹具 + 源码形状 | P1（观测） | 真机走一遍「进软链目录 / 预览软链文件 / 终端已开时打命令」 |

## 7. 半径

- 开工前写的「这改变什么用户行为」：见 §1 末。
- **文件集偏差（唯一一处，需主会话知悉）**：任务书 §1 的本支独占文件是 `FileBrowserViewModel.kt` + `AppNavigation.kt`，但 `F-C-10` 的修复点（`LaunchedEffect(Unit)` 投递体）**在 `TerminalScreen.kt` 内**，无法在 `AppNavigation.kt` 里等效完成：
  - AppNavigation 侧唯一能做的是「键控菜单/重组子树」，而 `key(initCommand){ TerminalScreen(...) }` 会触发 `DisposableEffect` 的 `onDispose → stop()`，**先杀 PTY 再起一个**，正是 R4-V F-10 明令禁止的「第二个 PTY」；
  - 因此新增 `ui/terminal/TerminalScreen.kt` 到本支文件集（改动 30 行，仅三处 effect 的键 + 注释）。
  - 交集核验：8 份 R5-E/F 任务书里只有 F2 提到 terminal；远端已推的兄弟支 `fix/r5e-openai` / `r5e-gemini` / `r5f-markdown` / `r5f-speech` 相对 `35509888` 对 `TerminalScreen.kt`/`FileBrowserViewModel.kt`/`AppNavigation.kt` 的改动数均为 **0**。
- `SymlinkEntryResolution.kt`（判纯函数无问题、任务书禁碰）未改；新解析逻辑作为独立纯函数放在 ViewModel 文件里，**复用**它的 `resolveListedEntry` 而不是复制一份。
- `formattedDate`、`ChatLinkResolver`、`FileBrowserScreen`、`FilePreviewScreen` 一字未动。

## 8. 停止节点

分支 CI 绿 + `head_sha` 与本地 HEAD 逐字符一致 = 停止节点。**不合并 main**（起手权在用户）。报告提交在绿之后 ⇒ `<绿 sha>..HEAD` 的非 `fix-reports/**` 差异为空。
