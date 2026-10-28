# FIX-R3-G — B11 tools / speech / a11y / mcp 9 条（F-04..F-10, F-12, F-13）

- 分支 `fix/r3-b11-tools`，基线 `2103729f`，9 笔提交（每 finding 一笔）。
- CI：dispatch run `40747076507`（#2863，build-apk @ `57c5a410`）。
- 本支不含 F-11：它在 `conversation/CompactOrchestration.kt`（禁改域，归 `wt-b11-compact` 支）。

## 1 结论

| ID | 判级 | 判定 | 修法一句话 | 用户行为变化 |
|---|---|---|---|---|
| F-04 | P1 | **成立** | 正则字符集去 `.`（保留分段点号）+ `internal` 化解析器供真消息测试 | 未知工具死循环熔断真正生效 |
| F-05 | P2 | 成立 | 共享 `AtomicBoolean cancelled` → 单调 `sessionToken`（AtomicLong） | cancel→start 后旧录音不再污染新会话 |
| F-06 | P2 | 成立 | system ASR `cancel()` 同步 bump token（teardown post 之前）+ `selectEngine` 刷新 locale 列表 | 取消后迟到的系统回调落不到新会话；切引擎后 locale 列表不再张冠李戴 |
| F-07 | P1 | 成立 | `speak`/`speakQueued` 检查引擎返回码，拒绝时回滚 drain + `isSpeaking=false` + 返回 false | 超 4000 字符/服务断连不再谎报 "speaking" |
| F-08 | P2 | 成立 | RFC 6749 §4.1.2.1 denial 分支：失败页 + `stop()` 触发 `onExternalCancel` + `htmlEscape` | 用户取消授权后等待立即返回，浏览器不再看到"complete" |
| F-09 | P2 | 成立 | `repairWithShizuku` 返回 `bound || getInstance() != null` | 修复失败时 CLI 不再在同一回合拿"not running" |
| F-10 | P1 | 成立 | 几何抽 `PinchTracks.forPinch`（纯函数）消除双重反转 + clamp + dispatch 拒绝补 countDown | `--scale 2.0` 真的放大；不再抛 IllegalArgumentException |
| F-12 | P2 | 成立 | 定界符放宽 `[A-Za-z_][\w.\-]*` + 注释行跳过 + 未闭合定界符不吞后续 | 含连字符/点号定界符、提及 heredoc 的注释之后的 bashism 不再漏报 |
| F-13 | P2 | 成立 | 仅 `frontmatter.isBlank()` 时做**严格形状**剥离（`---` 精确行 + key: value 行） | 以 `---` 水平线开头的技能正文不再丢第一节 |

## 2 先证（每条读真码后的判定）

- **F-04 成立**：`UnknownToolMessage.message("foobar", …)` 产出 `"Unknown tool: foobar. This agent can use: …"`（带句号，`tools/UnknownToolMessage.kt:66-68`，只读未改）；基线 `UNKNOWN_TOOL_RE_1` 字符集 `[a-zA-Z0-9_.\-]+` 贪婪捕获 `"foobar."` ⇒ `unk == toolName` 恒 false。行为红：喂 10 条**真实**消息后 `check` 仍 NONE（见 §4 GROUP e）。修法按任务书走字符集路线并保留 `EOF.json` 式分段点号（`(?:\.[a-zA-Z0-9_\-]+)*`）——比 `trimEnd('.')` 少一个魔法操作。`UNKNOWN_TOOL_RE_2` 同步收窄；另兼容 `unknown or forbidden tool` 措辞。
- **F-05 成立**：`cancelled` 是实例级单布尔，`selectLocale` cancel→start 背靠背时 start 先把它清回 false，旧 capture 协程已越过取消点继续 transcribe-and-deliver。token 化后每个 start 认领 `mySession`，4 个投递点全部 `isSessionLive(mySession)` 判活；`cancel()`/`shutdown()` 也 bump。附带修：`cancel()` 现在同时 `captureJob?.cancel()`（报告点名）。
- **F-06 成立（两半）**：①基线 KDoc 明说 cancel **不** bump——但回调都在主线程队列上，cancel 只是 post teardown，已入队的 `onResults/onError` 在 teardown 之前运行、仍匹配 live session ⇒ 迟到结果/ERROR_CLIENT 落进用户刚取消的会话；修法 = bump 提到 post 之前（KDoc 重写 + `@Volatile`）。②`SpeechRecognitionManager.selectEngine` 换引擎后不刷新 locale 列表，显示的还是旧引擎的（补 `refreshSupportedLocales()` + `resetLevels()`）。
- **F-07 成立**：基线 `tts?.speak(...)` 返回值被丢弃且 `_isSpeaking = true` 无条件写；引擎对超限文本回 ERROR 后**永不回调** ⇒ drain 计数永不归零、`android-speak` 报 `{"status":"speaking"}`。修后拒绝路径 `drain.invalidate()`（speak）/`drain.rejectPending()`（speakQueued，新增 API）+ 返回 Boolean。**返回类型保持 Boolean**：唯一调用点 `SpeakOffloadHandler.kt:137`（禁改域）按 boolean 语义消费，`put("speaking", spoke)` JSON 契约不破坏。
- **F-08 成立**：基线只匹配 `code`；`error=access_denied` 重定向落进同一个"Authorization complete"分支，回调不触发、服务不停止、wait 挂满 5 分钟。修法 = denial 分支（`code == null && error != null`，直接判 `error` 使 smart-cast 可见）+ `stop()`（`onExternalCancel` 保持 armed，由 stop 触发 → wait 以 Cancelled 恢复）。新增 `htmlEscape`（error 文本是重定向端可控输入，未转义 = 注入回环页面）。
- **F-09 成立**：基线 `return true` 无条件，KDoc 却承诺"true once the service has actually rebound"，两个调用方（Settings 行 + a11y CLI `svcOrThrow()`）都按 rebind 语义消费。修 = `return bound || MinisAccessibilityService.getInstance() != null`（最后一读容忍慢设备上 rebind 落在 invalidateCache 窗口内）。
- **F-10 成立（比报告重一处）**：①`finalOffset = initialOffset * scale` 之后再按 `scale > 1f` 反转起止 = 双重反转，任何 pinch 都向中心收敛；②小 `cx` 产生负坐标 ⇒ `StrokeDescription` 抛 IllegalArgumentException（报告有提）；③**报告没提**：`dispatchGesture` 被拒时**不发任何回调**，latch 空等满超时——两个 dispatch 站点都补了 `if (!dispatchGesture(...)) done.countDown()`。
- **F-12 成立**：①定界符 `[A-Za-z_]\w*` 在 `<<EOF-1`/`<<EOF.json` 处停在连字符/点号前捕获 `EOF`，闭合行永不匹配 ⇒ 整段余下脚本被当 heredoc body 吞掉；②注释行里的 `<<EOF` 也触发吞段；③真正未闭合的 heredoc 同样吞掉后续。修法三件：定界符放宽、`#` 行跳过、`hasClosingDelimiter` 预检（未闭合就不吞——运行时 shell 本来就会拒绝该脚本，扫描侧 fail closed to bash）。
- **F-13 成立**：生产 body 已由 `SkillRepository.parseSkillMd()` 剥离（`SkillInfo.frontmatter` 携带），二次剥离只影响 raw-markdown 调用方；基线 `startsWith("---")` + 任意行 `---` 扫描把"以水平线开头的正文"当 frontmatter 切掉第一节。修 = 仅 `frontmatter.isBlank()` 时调 `stripRealFrontmatter`（精确 `---` 行 + 至少一行中间行 + 非空行须像 `key:` mapping；`----` 与 `---|---` 不算），fallback description 语义保留。

## 3 修法（半径与一致性）

- **生产 10 文件**（任务书 9 文件 + `SpeechRecognitionManager.kt` 属 F-06 第二半，`speech/*.kt` 在允许集内）：`agent/ToolLoopDetector.kt`、`agent/shell/BashismDetector.kt`、`speech/{ProviderSpeechRecognitionEngine,SystemSpeechRecognitionEngine,SpeechRecognitionManager,TextToSpeechManager}.kt`、`accessibility/{AccessibilityRecoveryManager,MinisAccessibilityService}.kt`、`mcp/oauth/OAuthCallbackServer.kt`、`tools/SubagentSkill.kt`。
- **测试 12 文件**：修改 7（ToolLoopDetectorTest/BashismDetectorTest/OAuthCallbackServerFdTest/SpeechActiveEngineTest/SystemSpeechLifecycleShapeTest/TtsSpeakWiringTest/SubagentSkillTest）+ 新增 5（PinchTrackTest/A11yRepairResultShapeTest/ProviderSpeechSessionTokenShapeTest/TtsDrainRejectTest/OAuthCallbackHtmlEscapeTest）。
- **门脚本零触碰**：`git status` 全程无 `scripts/scan/**`、`scan.sh`、`test_scan.py`、`conversation/**`；门 113 / 夹具 349 与基线一致。
- 越界回滚记录：`sandbox/offload/SpeakOffloadHandler.kt` 曾被编辑（F-07 三态方案），确认在禁改域后 `git checkout --` 回滚，F-07 改为 boolean 契约内修。
- 纯函数优先（任务书 §3）：`extractUnknownToolName` private→internal、`PinchTracks.forPinch`、`stripRealFrontmatter`、`htmlEscape`、`TtsDrainTracker.rejectPending` 均可 JVM 直测；两个 dispatch 站点的 countDown 补丁是形状断言钉住。

## 4 装置与证据（`/var/minis/shared/work/probe-r3g/`）

**after 臂**（`run.sh`：真实生产文件 verbatim 切片 + 全部 11 个测试类，JUnitCore）：`COMPILE_RC=0`、**OK (123 tests)**。
**before 臂**（`run_pre2.sh`：完整 base worktree `pre-tree` @ `2103729f`，跑同一批测试；无法对基线编译的类用 shim/pre_probe 替代，红必须来自行为差异而非缺文件）：

| 组 | 装置 | 红 |
|---|---|---|
| a | 基线 BashismDetector + BashismReminder 真文件，跑新 4 条 heredoc 用例 | **4/4 红**（连字符/点号定界符、注释提及、未闭合） |
| b | 基线 OAuthCallbackServer 真文件 + 真回环 socket | **2/2 红**（denial 挂满 5s、error 文本未转义） |
| c | `Pinch.kt` shim = 基线算术 verbatim 复刻，跑 PinchTrackTest 9 条 | **6 红**（clamp/放大反向/越界/NaN） |
| d | 基线 speech/a11y 源文件在盘上，跑 4 个形状探针类 | **11 红**（TTS 3 + system 2 + provider 4 + a11y 2） |
| e | `pre_probe.kt` 走公开 API（基线 ToolLoopDetector/SubagentSkill） | **2 行为红**：F-04 = 10 条真消息后 level 仍 `NONE`；F-13 = 基线 prompt 输出 `Second section.`（第一节被吃） |

- 缺一不可的对照：GROUP e 若在修后跑会直接 fail（探针断言"基线必须不熔断"），说明红确实钉在基线行为上。
- 语法自查：全部改动文件 kotlinc 编译，语法类错误 0（仅"缺 Android 类路径"的解析类错误，属预期）。
- 门：`scan.sh` **113 passed, 0 failed**（本地一轮）。
- 变异臂：本轮 before/after 双臂已构成判据（before 红 = 缺陷、after 绿 = 修复），探针类各自带 negative-control 用例（"probe rejects a …"断言探针在坏形状上判红），未再叠变异臂。

## 5 CI 判读（run `40747076507`）

- run id `40747076507`（#2863），ref `fix/r3-b11-tools`，**conclusion = success，18:39:02 → 18:53:59（14.9 min）**。
- head_sha `57c5a41097d594c21a843396ed144a645b14fb93` 与本地 HEAD 逐字符一致（绿 sha = 提交 tip，无报告尾随差异）。
- 27 步，非 success 仅 2 条 skipped（`Move the rolling tag android-latest`、`Publish to Releases`，均 main 门控）= Publish 未执行，符合分支预期。
- 日志 1,582,445 B / NUL 0；判据逐条：`Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1、`^e: ` 0、含 FAILED 的行 6 条全部是 PASSED 用例名子串（`ChatAgentTraceObserverSchemaTest > phase FAILED maps to Failed PASSED` 等，既有判读陷阱）；`Task :app:testReleaseUnitTest` 真跑 + `BUILD SUCCESSFUL` ×3、`assembleRelease` ×2。
- 11 个测试类全部出现在 Gradle 测试日志（ToolLoopDetectorTest 23 / BashismDetectorTest 17 / SubagentSkillTest 32 / PinchTrackTest 9 / A11yRepairResultShapeTest 2 / TtsDrainRejectTest 4 / TtsSpeakWiringTest 17 / SystemSpeechLifecycleShapeTest 6 / ProviderSpeechSessionTokenShapeTest 4 / OAuthCallbackServerFdTest 4 / OAuthCallbackHtmlEscapeTest 5）——新增/扩展用例随 CI 单测执行。

## 6 残留（全部登记级，无一必修）

- **R1（F-07）**`speakQueued` 拒绝时只回滚最后一笔 enqueue；若同一 flush 之前已有多笔排队、引擎在中间拒绝，前面的仍在队列（`isSpeaking` 等它们，语义正确）——无需改，仅记录。
- **R2（F-08）**denial 页未引用 `error_description`（部分服务器用它解释原因）；要引用需二次转义 + 截断，属增强。
- **R3（F-10）**`PinchTracks.MIN/MAX_SCALE` 之外的 scale 直接 clamp，不向 CLI 报错；行为可预期（见 KDoc），真机未验（R6 同）。
- **R4（F-06）**`selectEngine` 现在同步刷新 locale；`SystemSpeechRecognitionEngine.fetchSupportedLocales` 是广播探测（慢），目前在调用线程执行——若真机出现卡顿，再挪协程（当前路径与基线一致）。
- **R5（F-13）**`stripRealFrontmatter` 只认 key: value 行；YAML 列表/嵌套 frontmatter 会被判"非 frontmatter"整体保留——对 raw-markdown 调用方是安全的回退。
- **R6** 真机未验：a11y pinch / OAuth 回调页 / ASR 会话竞争都是 Android 框架行为，本地装置 = JVM 纯函数 + 形状探针，真机验证留给下一轮设备窗口。

## 7 纪律自检

- 允许域外修改 1 次均即时回滚（`SpeakOffloadHandler.kt`）；最终 `git status` 干净、只含允许路径。
- 未跑全量 scan/test_scan/gradle（负载纪律）：门一轮 + 单文件语法自查 + 分组 JVM 探针。
- 每条修法都写了"这改变什么用户行为"（§1 末列）。
- `tools/UnknownToolMessage.kt` 只读（F-04 从消费端修，未动生产方）。

## 8 交接

- 分支 `fix/r3-b11-tools` @ 9 提交，绿后**停分支**，合并起手权在用户。
- before/after 装置可复跑：`sh /var/minis/shared/work/probe-r3g/run.sh`（after）、`run_pre2.sh`（before，含 base worktree `pre-tree`，用完可删）。
- F-11 与 F-01/02/03 在 `wt-b11-compact` / F 支的领地上，本支未碰。
