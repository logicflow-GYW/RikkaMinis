# FIX-R4-V — B13（nav/ui 域）七条修复报告

- 分支：`fix/r4-b13-ui`（基线 main `2103729f`）
- 报告源：`meta-audit/reports/r4/B13-nav-ui-2026-10-29.md`
- 范围：B13 的 F-03 / F-08 / F-10 / F-11 / F-12 / F-13 / F-14（F-01..F-07、F-09、F-15 归 P/Q/S/T 支）
- 独占文件集：`ui/sandbox/{MirrorSettingsScreen,FileBrowserViewModel}.kt`、`ui/components/{ImageGalleryViewer,QuickTestSheet}.kt`、`ui/navigation/AppNavigation.kt`、`ui/onboarding/{OnboardingModelSelectionScreen,OnboardingScreen}.kt`、`ui/sessions/SessionsShared.kt`（＋新增 `ui/sandbox/SymlinkEntryResolution.kt` 与 5 个测试类）
- `scripts/scan/**`、`scan.sh`、`test_scan.py`、`ui/terminal/**`、`MainActivity.kt`、`strings.xml`：零触碰

## 0. 事故注记

开工时 `/var/minis/shared/work` 遭遇持续清空（08:11–08:17），第一次重建的 worktree 连同首个未提交
改动被削平；主会话重建 `witness-1027/wt-r4-v` 后续作。唯一幸存产物（F-11 的
`SymlinkEntryResolution.kt`）从 `/var/minis/mounts/背景包/_shared/r4-rescue/b13-ui.tar.gz`
原样回填，与丢失前逐字节一致。F-11 的地基实验（四臂 JVM）在丢失前后各跑一次，读数相同。

## 1. 判定总表（7 条：先证后修）

| # | 判定 | 修法 | 证据 |
|---|---|---|---|
| F-11 软链删除清空真实目录 | **成立（P0）** | 解析走 guest 映射 + 删除判定纯函数 | 四臂 JVM 实验 + 7 例测试 |
| F-08 画廊吞掉翻页滑动 | **成立（P1）** | 自管手势循环 + 归属纯函数 | 3 例测试（判定表 + 形状） |
| F-10 TERMINAL 目的地叠加 | **部分成立（P1）** | 本支可达三处加 `launchSingleTop` | 3 例测试，before 1/3 红 |
| F-03 首启镜像检测被共享结算抢先 | **成立（P2）** | 一次性结算开关 + 快照前置 | 4 例 wiring 测试，before 3/4 红 |
| F-12 QuickTest 缓存名/写失败 | **成立（P2）** | contentHashCode 命名 + 写失败可见 | 4 例测试（含负控） |
| F-13 引导完成按钮双击双写 | **成立（P2）** | 一次性 `submitted` 门 ×2 文件 | 3 例形状测试 |
| F-14 未来时间戳落「本周/刚刚」 | **成立（P2）** | 未来 → TODAY / ABSOLUTE_DATE | 14 例表 + before 臂 |

无证伪条目；F-10 为范围受限的部分修（见 §6 R1）。

## 2. F-11（P0）软链条目：解析与删除都不再跟随链接

**报告主张**：软链删除会清空目标目录；符号链接的 File 属性读出的是映射后条目。

**地基实验（JVM，两次独立复跑同读数，`EXP-RESULT` 见救援包）**：
- A 臂：`link.deleteRecursively()` → 目标目录 2 个文件 → **0 个**（目标目录本身还在）；
- B 臂：`link.delete()` → 目标 2 个文件完好；
- C 臂：容器内绝对软链宿主不可达 → `isDirectory=false, length=0`（「0 字节非目录」误读）；
- D 臂：映射后条目读作目标目录。

**修法**（`SymlinkEntryResolution.kt` 新增两个纯函数 + `FileBrowserViewModel` 三处接线）：
1. `resolveListedEntry(file, linkTarget, resolveGuestAbsolute)`：绝对目标走浏览器自己的
   guest→host 映射（有 sessionId 用 `resolvePathForSession`，否则 `resolveHostPath`；映射不
   到且宿主路径也不存在 → 保持「未知」= 链接本身，不伪造文件）；相对目标按链接父目录解析
   （原行为）。
2. `shouldDeleteRecursively(isDirectory, isSymlink)`：软链永不递归删除 —— `isDirectory` 是
   RESOLVED 结果，`isSymlink` 是 lstat 事实，删除决策必须同时看两者。
3. `deleteItem` 按判定分流；`FileItem.from` 增加 mapper 参数（默认 `{ null }`，不破坏既有调用）。

**测试**：`SymlinkEntryResolutionTest` 7 例 —— 判定表 4 组合、相对/绝对映射、unknown 不伪造、
真文件系统正向（unlink 后目标 2 文件完好）+ **负控**（`deleteRecursively()` 仍会清空目标，
证明夹具有牙）。

## 3. F-08（P1）画廊 1× 时吞掉翻页滑动

**报告主张**：`detectTransformGestures` 无条件消费拖动，翻页失效。

**核实**：成立。该识别器在超过 touch slop 后消费全部位置变化，而旧 lambda 只在 `scale > 1f`
时应用 pan —— 1× 时单指横扫被丢掉且到不了 `HorizontalPager`。

**修法**：`awaitEachGesture` 自管循环；归属判定抽成纯函数
`galleryPageOwnsDrag(scale, pressedPointers) = scale > 1f || pressedPointers > 1`；只有归属
页面时才应用缩放/平移并 `consume()`；1× 单指时一个事件都不消费。不用「scale 变化重 key
pointerInput」的方案（会打断进行中的捏合）。

**测试**：`GalleryGestureOwnershipTest` 3 例 —— 判定表 6 组字面量；「吞手势的识别器已消失」
（`detectTransformGestures` 调用形态禁现 + `awaitEachGesture` 在场 + `HorizontalPager` 在场）；
「consume 必须在归属分支内且全文件唯一」。

## 4. F-10（P1，部分修）TERMINAL 目的地不再叠加

**报告主张**：深链重入推第二份 TERMINAL → `terminalVisible` 反转 + initCommand 重放。

**核实**：反转机制成立（`MinisOpenUrlBroker.terminalVisible` 由 `TerminalScreen` 的
DisposableEffect 写，双份条目 = 旧页 onDispose 在新页仍可见时把标志按成 false）；且**不需要
用户连点**：冷启动深链分发在 NavController 恢复回退栈之后跑，「进程被杀→恢复出 TERMINAL→深链」
一次意图即得两份。initCommand 重放的同源。

**本支修法**：本支可达的三处导航（冷启动分发 / `onOpenTerminal` /
`onOpenTerminalWithCommand`）都加 `launchSingleTop = true`，仍走 `safeNavigate` 的 RESUMED 门，
`ChatViewModelStore.resolveSessionId` 草稿别名解析原样保留（da1023-F 契约）。

**测试**：`TerminalRouteSingleTopTest` 3 例 —— 对 AppNavigation.kt 里**每一处**
`Routes.terminal(` 都要求窗口内出现 `launchSingleTop = true`（新增调用点漏写即红）；两处聊天
入口必须仍做 resolveSessionId；所有 terminal 导航必须仍经 safeNavigate。before（基线原文）
1/3 红（防叠加例），after 3/3 绿。

## 5. F-03（P2）首启镜像检测：先拍快照、共享体不结算

**报告主张**：`autoDetectOnceIfNeeded` 先跑 `startAllTests`（共享结算会自动落「最快镜像」）
再读 `selectedMirrorId` 当已选集 → `decideAutoApply` 恒返空，首启静默等价于用户手动点
「测速并自动选择」（官方镜像照进）。

**修法**（只修接线，不动判定策略）：
- `startAllTests` 结算里的自动落盘循环放进 `if (settle)`；`settle` 来自一次性字段
  `settleOnComplete`（进入时读走并立即复位 true）。
- `autoDetectOnceIfNeeded` 在测速**之前**拍 `alreadySelected` 快照，调用前置
  `settleOnComplete = false`；结算完全交给 `decideAutoApply`。
- **为什么是字段不是参数**：`MirrorSpeedTestShapeTest` arm E4 把字面量
  `private fun startAllTests(context: Context)` 钉成锚并要求其函数体仍携带
  `autoSelectFastest` 尾段与 `NonCancellable`。门立场正确（共享体保留尾段、用户路径可达），
  按纪律改修法不改门。一次性语义由「入口同步读走+复位」保证（读与复位同块非挂起）。

**测试**：`MirrorAutoDetectWiringTest` 4 例（顺序型形状断言）：快照必须在测速前且全函数唯一；
boot 必须先置 false 再调；用户路径必须仍是结算重载；共享体自动落盘必须在 `if (settle)` 内且
persist 同分支、消费复位在 `currentJob?.cancel()` 之前。before 3/4 红 / after 4/4 绿。
另以 Python 模拟 arm E4 四条读数全 True（锚点未丢）。

## 6. F-12 / F-13 / F-14（P2）

**F-12** `QuickTestSheet`：① 缓存名 `data.hashCode()` 是身份哈希（Kotlin 数组不覆盖）→ 改
`quickTestClipFileName(data)` = 尺寸 + `contentHashCode()` 十六进制；② `runCatching{writeBytes}`
失败被吞、`isReady` 照翻 true、`play()` 静默失败 → 写入后校验
`isSuccess && file.length() == data.size`，失败置 `writeFailed`、`isReady=false` 并显示
`quicktest_clip_failed`（复用既有 key，strings 不在本支文件集）。测试 4 例（含负控：证明
`ByteArray.hashCode()` 是身份哈希）；既有 `QuickTestSheetContractTest` 三条形状断言按源码逐条
复核仍成立。

**F-13** `OnboardingModelSelectionScreen` + `OnboardingScreen`（ModelSelectionStep）：完成按钮
只有 `enabled = selected.isNotEmpty()` 一道门，而 `addGroup` 每次新 UUID、点击回调在重组禁用
之前可重入 → 双击两条同名分组。两处都加一次性 `submitted` 门，**先立旗再写**，enabled 追加
`&& !submitted`。测试 3 例（形状 + 旗在写之前 + 每文件唯一写点）。不做成纯函数单测：为三行
防重入旗造 composition 假件不成比例。

**F-14** `SessionsShared`：`datePeriod`/`relativeDate` 拿 `now - timestamp` 对正阈值比较，未来
时间戳差值为负，同时满足 `seconds < 60` 与 `diffDays < 7` → 「明天」的会话进 THIS_WEEK
（过去档）且标签「刚刚」。修法：`datePeriod(timestamp, nowMillis = now)`，`timestamp > now`
直接归 TODAY（最新非置顶档），Calendar「昨天/一月前」也锚到同一 nowMillis；标签判定抽成纯函数
`relativeDateBucket`，未来 → ABSOLUTE_DATE。测试 14 例（未来 4 + 过去 10）；before 臂用
基线原文抽取跑出 `future(+1d/+30d) -> THIS_WEEK`，过去档不变。

## 7. 本支最值钱的可复用手法

1. **「共享体被两个入口用」的缺陷，修法优先保住钉住共享体的形状门**：F-03 第一版用参数传意图，
   字面锚点失配会让 arm 静默丢锚（不是报红，是少一条检查）—— 比报红更危险。一次性字段 +
   入口同步消费，两端都保住。
2. **负控臂直接钉「缺陷本身的物理性质」**：F-12 的 `ByteArray.hashCode()` 是身份哈希、F-11 的
   `deleteRecursively()` 仍会清空目标 —— 把「为什么会坏」变成可执行断言，实现或平台语义回退
   时测试先红。
3. **before 臂用基线原文抽取跑同一套测试**：F-03 3/4 红、F-10 1/3 红、F-14 读数对比 —— 红
   的必须恰好是缺陷例，绿的是「不得改变」的不变量；说不清「基线哪段代码导致红」的就是装置假红。
4. **形状断言的窗口要给注释留余量**：F-10 首跑红是因为 400 字符窗口装不下我自己的解释注释；
   放宽到 800 并在 KDoc 说明「真缺 singleTop 的站点远在窗外」。
5. **改 Compose 手势前先问「这个识别器在什么条件下消费」**：`detectTransformGestures` 的消费
   不分档位 —— 这是 F-08 的根因，也是任何「嵌套手势 + 父滚动」组合的通病。

## 8. 残留（登记级，带触发条件）

| # | 内容 | 级 | 触发条件 |
|---|---|---|---|
| R1 | F-10 旋转半边：`TerminalScreen` 的 onDispose `stop()` + `MainActivity` 无 `configChanges` → 旋转杀会话。修点在 `ui/terminal/**` 与 manifest（本支禁改域） | P1 | 下一支拿到 `ui/terminal/**` 或 manifest 文件集时；最小修法 = MainActivity 加 `configChanges=orientation\|screenSize` 或 TerminalScreen 把 stop 改为「仅真正离场时」 |
| R2 | F-10 deep-link 直推：`MainActivity:781-792` 的 `Routes.terminal(` 无 singleTop（禁改域） | P2 | 同 R1 文件集；与 R1 同一笔提交顺手 |
| R3 | F-11 相同内容片段在两张卡片同时在场时共享同一缓存文件，一张销毁会删掉另一张的文件（既有 DisposableEffect(file) 语义） | P2 | 出现「同一音频回复出现两次」的真实场景再处理；当前 Quick Test 语义下不可达 |
| R4 | F-12 失败文案复用 `quicktest_clip_failed`（语义是「合成失败」）；专用 key 需动 strings 三 locale | P3 | strings 文件集开放且顺路时 |
| R5 | F-14 `datePeriod` 的「一月前」滚动窗口与 iOS 对齐是有意为之（原注释），未改 | P3 | 永不（刻意设计） |
| R6 | 真机未验：本支全部证据 = JVM 级 + 源码形状 + 门 | — | 下次真机回归时抽 F-11（删除软链）与 F-08（画廊翻页）两项 |

## 9. 验证读数

- 本地 JVM（kotlinc 2.1.0 + JUnit 4.13.2，真源码抽取）：SymlinkEntryResolutionTest **7/7** ·
  GalleryGestureOwnershipTest **3/3** · MirrorAutoDetectWiringTest **4/4**（before 3 红）·
  QuickTestClipFileNameTest **4/4** · OnboardingConfirmOnceTest **3/3** ·
  SessionDateBucketFutureTest **4/4**（14 断言）· TerminalRouteSingleTopTest **3/3**（before 1 红）
- 语法类错误（kotlinc 只筛 expecting/unexpected/syntax/Unclosed）：8 个改动/新增文件全 **0**
- 门（相关单门，非全量）：`readonly_mount_write_guard` / `sysinfo_state_thread_check` /
  `mirror_vm_thread_check` / `mirror_category_route_check` / `tts_voice_and_sanitizer_check
  (--root .)` / `provider_remember_key_check` / `mediaplayer_release_guard` /
  `webview_holder_url_source_check` / `chat_session_id_binding_check` /
  `open_url_broker_scheme_check` / `deeplink_settings_routes_check` 全 **rc=0**
- 提交：8 笔（F-11 → F-08 → F-14 → F-12 → F-13 → F-03 → F-03 重构 → F-10），已推远端
