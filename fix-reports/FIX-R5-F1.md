# FIX-R5-F1 —— 死标签旁路 + 下载静默失败（R5-F 唯一生产支）

来源：`meta-audit/reports/r5/R5-F-external-surface.md`（F-F-01 + F-C-01b）。基线 main `35509888`，
分支 `fix/r5f-browser`，绿前 sha `2dc99ab2`（`02ea8c5c` 实现 + `2dc99ab2` 判据修正）。
改动面 4 文件 +538/−15：生产 `BrowserUseManager.kt`（+78/−9）、`BrowserTabPool.kt`（+17/−4），
测试 `RendererDeadShortCircuitTest.kt`（新，418 行）、`BrowserTeardownAndFailureSignalTest.kt`（+25/−2，pin 随文案搬家）；
加本报告共 5 文件 +697/−15。
`scripts/scan/**` / `scan.sh` / `test_scan.py` 零触碰（门 113 / 夹具 349 不变）；AgentLoopEngine / ChatSessionLifecycle 零触碰。

## §1 修复台账

| # | 报告条目 | 判级 | 一句话 | 状态 |
|---|---|---|---|---|
| 1 | F-F-01 | P1 | 渲染进程死后四个入口照常支起永不完成的 waiter，烧满 30/60s 超时 | 修 |
| 2 | F-C-01b | P1 | 两条下载失败分支只写 Log.w：面板无行、聊天无通知 | 修 |

## §2 先证（前态证据，全部机械可复核）

**F-F-01**（`probe-f1/arm_slot.py`，三条臂 ALL ARMS MATCH）：
- **机制层**：`beginAsyncJsRequest()` 把 deferred 登记进 `asyncJsSlot`；唯一的完成者是
  ①桥回调（活在渲染进程里）②自己 await 之后的收尾 ③teardown 的 `takeCurrent()`——**只触发一次**
  （`onRendererGone` 是系统回调，renderer 死一次就不再来）。⇒ teardown 之后才注册的请求
  没有任何完成者，`withTimeoutOrNull(30_000/60_000)` 必然烧满。三臂：A「teardown 先于注册」
  烧满超时（缺陷形态）；B「注册在飞时 teardown」立即收到 `{"error":…}`（既有修法有效 ⇒
  缺陷只在「之后注册」这一形态）；C「迟到 teardown 不改写已解决 waiter」。
  `check_semantics()` 逐条对照 `AsyncJsSlot.kt` 真源（entry 单槽 / begin 驱逐 / finish 带 token）。
- **前态形状**：四站点（executeJS :1834 / awaitPromiseJs :2002 / reloadAndWait :2183 /
  loadBlankPage :2231）函数体内 `inert_guard_at=-1` 且 first_arm > 0 ⇒ 全部 UNGUARDED。
  `evaluateJavascript()`、`navigate()`、`captureWebViewBitmap()` 三个入口在 r3-B09 已有守卫
  （`BrowserTeardownAndFailureSignalTest` F-02 pin 在），本支是**补齐同族剩余四处**。

**F-C-01b**（`probe-f1/arm_download.py`，ALL ARMS MATCH）：
- `startUrlDownload` 的 `sessionWorkspaceDir() ?: run` 分支：仅 `Log.w`，无 `reportDownloadFailure`；
- `saveBlobDownload` 的 `sanitizeDownloadName(filename) ?: run` 分支：仅 `Log.w`，无 `reportDownloadFailure`；
- 对照臂：`saveBlobDownload` 的 no-session 分支（r3-B09 F-10 修过）确实调用了漏斗；
- `reportDownloadFailure` 本体四形状齐备（FAILED 状态 / destination=null / 前插 `_downloads` /
  `onDownloadEvent("Download failed: …")`）⇒「接上漏斗」就等于「面板查得到」。

**这改变什么用户行为？**——渲染进程死掉的标签页上，`execute_js`/`fetch` 立刻返回
「该标签已死，开新标签」而不是静默挂满 30/60 秒后报一个假的「超时」；`set_viewport`/
`set_user_agent`/`clear_data` 不再在死标签上空等 30 秒；无会话时的 URL 下载和不安全文件名的
blob 下载会出现在下载面板的失败记录里（此前只在 logcat）。

## §3 修法（原契约 / 被破坏方式 / 新契约）

### F-F-01 —— 四处死标签旁路
- **原契约**：任何 browser 动作在「无活 WebView」的标签上必须立刻回答死标签错误
  （r3-B09 在 navigate / captureWebViewBitmap / evaluateJavascript 三处立的规矩）。
- **被破坏方式**：同族还有四个入口没有守卫，且它们都**先支 waiter 再干活**——
  `executeJS`/`awaitPromiseJs` 调 `beginAsyncJsRequest()` 占槽；
  `reloadAndWait`/`loadBlankPage` 装 `navigationDeferred` + 置 `_isLoading`。
  死标签上槽/deferred 永不被完成。
- **新契约**：四站点在支起任何 waiter **之前**判定 `webViewInert()`：
  - `executeJS`：`if (webViewInert()) return webViewGoneResult()`（与 navigate() 同形一行）；
  - `awaitPromiseJs`：`deadTabAsyncJsAnswer(destroyed, rendererDead)?.let { return it }`——
    返回桥自己的 reject 形状 `{"error":…}`（fetch 解析成 "Fetch failed: …"，与 teardown
    完成在飞 waiter 的形状一致）；
  - `reloadAndWait`/`loadBlankPage`：早退 + `Log.w`，**不**完成 deferred、**不**置 loading
    （「reload 发生了」正是这条路径不能声称的事）。
- **文案单源**：新增顶层纯函数 `deadTabGoneMessage(destroyed)`（两类成因两种建议，JVM 可测）
  与 `deadTabAsyncJsAnswer(destroyed, rendererDead)`（null = 放行）；`webViewGoneResult()`
  改为引用前者 ⇒ BrowserActionResult 路径与 async-JS reject 形状**共享同一份文案**，
  同一个死标签不会给 fetch 和 navigate 两种说法。

### F-C-01b —— 两条静默失败接上漏斗
- **原契约**：每个下载失败都应同时可见（面板 FAILED 行 + 聊天通知）——r3-B09 F-10 立的规矩。
- **被破坏方式**：`startUrlDownload` 无会话分支、`saveBlobDownload` 不安全文件名分支只写 Log.w。
- **新契约**：两处都改走 `reportDownloadFailure(filename, reason)`，reason 用固定串
  `no session is bound to this browser pool` / `unsafe download filename`。
  实现细节：`guessFileName` 提升到 dir 检查**之前**（漏斗需要名字；raw 名只进 logcat，
  面板标签经漏斗内再 sanitize 退化为通用 "download"——不安全名本来就不该上面板）。

## §4 验证证据（三源取二）

1. **纯判据真对象（JVM）**：`RendererDeadShortCircuitTest` 9 例 + 既有
   `BrowserTeardownAndFailureSignalTest` 9 例 = **18/18 绿**（kotlinc 单编 + JUnitCore，
   0.585s）。纯函数从 `BrowserUseManager.kt` **逐字切片**（`extract_members.py`，含逐字回读
   断言），期望值全部写死字面量；JSON 形状断言经 `JSONObject(...).getString("error")` 解析后
   与字面量比对（绕开 quote 对 em-dash 的转义差异，见 §5）。
2. **形状断言（缩进域，无字符窗）**：`bodyOf` 按声明+括号配平取函数体（支持表达式体
   `= if … else …`），守卫位置用**索引序**（guard < beginAsyncJsRequest / navigationDeferred /
   _isLoading），`codeOnly` 先剥注释（注释里提到 evaluateJavascript 不算支起 waiter）。
   另有 `deadTabGoneMessage` 逐字 pin（two-cause 区分随文案搬家，既有测试同步更新）。
3. **变异臂 9/9 MATCH**（`probe-f1/arms.py`）：基线 18 绿；每条臂把缺陷塞回去一个形态，
   期望只打红自己的判据——
   M1/M2 删 executeJS/awaitPromiseJs 守卫 → 红；M3/M4 删两处早退 → 红；
   M5 早退里伪造 `navigationDeferred?.complete(…)` → 红；
   M6 webViewGoneResult 改回内联文案 → 红（本支判据 + 既有 navigate pin 双红）；
   M7 `deadTabAsyncJsAnswer` 只看 destroyed → 三条判据全红（真行为变异，从真源重切）；
   M8/M9 删两处漏斗调用 → 红。
4. **7 道相关门**（改前改后各跑一遍，全绿）：browser_destroy_wakeup / browser_cookie_ui_refresh /
   browser_tab_exec_serial / browser_tabs_lifecycle / browser_history_snapshot /
   claim_release_guard / downloads_atomic_update。**门与夹具零触碰**。

## §5 证伪与纠偏（本支抓到的自己的错）

1. **探针死循环（用户「跑太久应该出问题了」抓到）**：`expressionBody` 里 `i = nl` 而下一轮
   `indexOf('\n', i)` 返回 i 自己 ⇒ 永不推进，JUnitCore 空转 9 分钟 CPU 被杀。修 `i = nl + 1`
   并留注释。教训：**自写切片器的循环推进变量必须越过查找起点**。
2. **臂判据假绿（9/9 全「绿」的假象）**：第一版臂脚本 `(\S+)\(` 匹配不了含空格的测试方法名
   ⇒ 全部解析成 `failed=[]` 误判绿；且 java 继承了错误 cwd，测试读源码直接 FileNotFound。
   修：正则 `(.+?)\(` + 显式 `cwd=ROOT` + 「无 OK/Tests run 标记 = 没跑成」判定。
   **「failed 为空」不等于「跑过且全绿」——判据必须先证明 run 存在。**
3. **M5 判据过窄（变异臂抓到）**：伪造完成判据原写 `deferred.complete(`，漏掉同族写法
   `navigationDeferred?.complete(` ⇒ M5 全绿。放宽为 `.complete(` 并在注释里点名 M5。
   **判据写完必须跑「把缺陷塞回去」的臂，否则更严只是自称。**
4. **JSONObject.quote 转义差异**：JVM 参考实现把 em-dash 转成 `\u2014`，Android libcore 不转
   ⇒ 期望字面量若带 wire 文本会钉住编码事故而非契约。改为解析后比对；文案本身由两个
   独立测试用字面量钉死。
5. **报告判级核对**：报告 F-F-01 的行号与真树对得上；`awaitPromiseJs` 返回 `String?` 无法直接
   返回 `webViewGoneResult()`，采用桥 reject 形状 + 共享文案函数，未造新文案。

## §6 残留（登记不修，带触发条件）

| # | 事项 | 级 | 触发条件 |
|---|---|---|---|
| R1 | `setUserAgent` 仍只查 `destroyed` 不查 `rendererDead`（C-02 半边，报告已判登记） | P2 | 用户报 UA 切换在死标签上卡住 |
| R2 | `fetch` 在「fetch 入口之后、awaitPromiseJs 注册之前」的竞态窗内仍可能报 "Fetch failed: timed out"（60s 后） | P2 | 真机日志出现 fetch timed out 且标签同期死亡 |
| R3 | `startUrlDownload` 的 guessFileName 提前计算在正常路径上多算一次（纯函数，无副作用） | P3 | 不触发（刻意简化：`ponytail` 已注） |
| R4 | 真机未验（全部证据 = JVM + 形状 + 门） | P1 观测 | 用户报浏览器行为异常时先查本支 |
| R5 | `loadBlankPage` 早退后 createTab 的后续 read 走 evaluateJavascript 守卫返回错误 JSON——链路已闭合但未端到端真机走查 | P2 | 真机 tab 池复用路径出问题 |

## §7 半径与边界

- 生产只动两个独占文件（BrowserUseManager / BrowserTabPool）；`WebPreviewShortcut.pin`、
  cookie UI、tab 生命周期门钉的形状全部未动（7 门改前改后双绿为证）。
- `fetch` 未加第二道守卫：`awaitPromiseJs` 已在其唯一生产调用点的上游短路（rg 全库 1 caller），
  再加一层是半径外。
- 既有测试 `BrowserTeardownAndFailureSignalTest` 的 pin 随文案搬家同步更新（成员切片改为
  `deadTabGoneMessage` 顶层域 + webViewGoneResult 委托认定），判据语义不变
  （two-cause 区分仍在）。

## §8 停止节点

- 分支 CI：run `40888915809`（dispatch @ 2026-10-29T14:10:51Z，HEAD `2dc99ab2`）。
  **结果见文末补记。** CI 绿 + head_sha 与本地逐字符一致 = 停止节点达成，不合 main。
- 装置：`/var/minis/shared/work/r5-ef/probe-f1/`（arm_slot.py / arm_download.py /
  extract_members.py / arms.py / jvm/stub+out / logs/）。

---

### CI 补记（收口时回填）

**run `40888915809` — success**（`workflow_dispatch`，attempt 1，2026-10-29T14:10:57Z 起，
14:32:55Z 完成 ≈ 22 min）。`head_sha = 2dc99ab231b3e85dc19be67393005cdb4fe1c20b`，
与本地 HEAD **逐字符一致**（防「绿的是别人分支」）。

- **27 步 = 25 success + 2 skipped**：唯一两个非 success 是 `Move the rolling tag android-latest`
  与 `Publish to Releases`，均为 main 门控——**分支上 skip 是正确行为**（main 上才真发布）。
- **日志** 3,311,654 B（27 个条目解压后），**NUL 0**（`tr -d '\0' | wc -c` 同值）。
- **门**：`Summary: 113 passed, 0 failed` ×2；**夹具**：`ALL 349 CASES PASS` ×2 —— 与基线
  （门 113 / 夹具 349）一致，本支未增删门。
- **编译**：`^e: ` 0；**真 FAILED 行** 0（`grep -n " FAILED$\|^FAILED"` 无输出）。
- **本支用例**：`RendererDeadShortCircuitTest` **9/9 PASSED**（含 `a live tab is not
  short-circuited` —— 负控：活标签不被短路）、`BrowserTeardownAndFailureSignalTest` **9/9
  PASSED**（含 `navigate refuses a dead tab instead of burning the timeout` 的既有 pin，
  证明文案搬家没有削弱它）。

CI 绿 + head_sha 逐字符一致 = **停止节点达成，不合 main**。报告提交在绿之后，
`git diff --name-only 2dc99ab2..HEAD` 只应含 `fix-reports/FIX-R5-F1.md`。
