# FIX-DEFERRED-1119 — 两条排队发现（R3 F-05 + R1 F-21）

分支 `fix/deferred-1119`（worktree `/var/minis/shared/work/r3full/deferred`，基线 `ecb03d92`）。
两条都是「上一次修复不完整」的回咬：F-05 是 `fix/offload-bounds-1119` 相邻区域留下的旁路，F-21 是 `runCatching` 同族第四次复发。

---

## 1 范围

| 文件 | 改动 |
|---|---|
| `browser/BrowserUseManager.kt` | +92 / −4（`tabDeathSignal`、两处 teardown 唤醒、`evaluateJavascript` 竞速、顶层 `awaitJsResultOrTabDeath`） |
| `ui/chat/ChatViewModel.kt` | +61 / −3（resume 提醒持久化改 `attemptUnlessCancelled`、顶层新助手） |
| `test/.../browser/BrowserJsAwaitTeardownTest.kt` | 新增，8 例 |
| `test/.../ui/chat/ChatResumePersistCancellationTest.kt` | 新增，5 例 |
| `fix-reports/FIX-DEFERRED-1119.md` | 本文件 |

未触碰：`scripts/scan/**`、`offload/**`、`data/**`、`mcp/**`、其它 `ui/chat/*`（R3-A 正在改 `ChatPromptAndTools.kt` / `AgentLoopEngine.kt` / `ChatSessionLifecycle.kt`）。

---

## 2 逐条先证与结论

### F-05 —— 成立，但报告给的机制**部分不成立**（挂死不是永久，是 60 s）

**成立的部分（读代码坐实）**：`evaluateJavascript`（`BrowserUseManager.kt:2337` 一带）为每次调用新建一个局部 `CompletableDeferred<String>`，它**不登记**在任何槽里；`onRendererGone`（`:809`）只唤醒 `navigationDeferred` 与 `asyncJsSlot.takeCurrent()`，`destroy()`（`:3125`）同理。渲染器在「入口的 `webViewInert()` 检查之后」才死时，回调永不抵达，没有任何路径能 complete 这个 deferred。

**被证伪的部分（报告原文「`deferred.await()` 永久挂起 ⇒ `lockForTab` 一直握着」）**：工具路径不是无限等，而是 **60 s**——

- `BrowserTabPool.executeSerialized`（`:1068`）= `withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) { mutex.withLock { … } }`，`TAB_SERIAL_WAIT_TIMEOUT_MS = 60_000L`（`:93`）；
- 隐式 tab 分支（`:1146`）同形。
- 两处的 KDoc 都写「超时只是拿锁的等待，拿到锁后动作走自己的超时」，但**实现上 `withTimeoutOrNull` 包住了整个 `withLock` 块**，所以动作也被这 60 s 截断。

⇒ 真实损害从「永久挂死（P0/L=X）」下调为「**每次调用白等 60 s，然后报错文案说「tab 忙，换个 tab 重试」**——而真实原因是渲染器已死，换 tab 的建议恰好是对的但理由是错的；同一 tab 后续每次工具调用都重复这 60 s」。修法与报告建议相同，级别按 `R=B/L=Z` 记（本支不改判级表，只在报告里纠正机制）。另外若将来有调用方在 `execute()` 之外直接 await（目前没有），才会退化成真无限等——修完后这条边也不存在了。

### F-21 —— 成立，但「会话又被标成 active」这半句不准确

**成立的部分**：`resume()` 用 `runCatching { chatRepository.appendMessage(…) }`（`ChatViewModel.kt:4970` 原址），`appendMessage` 是 **suspend** 的 Room 写入（`ChatRepository.kt:695`），Stop 触发 `streamJob?.cancel()` 后它抛 `CancellationException`，被 `runCatching` 当成普通失败吃掉 ⇒ 后续两条非挂起语句照常执行：`agentHistory.add(…)` 与 `SessionActivityTracker.setActive(…)`。

**准确的后果**：`agentHistory` 里多出一条**内存用户回合，`dbMessageId = null`**（行可能根本没写进库）——按 id 解析的压缩边界（`compactMarkerAnchoredOnRow` 一族）看到的是「内存有、库里没有」的回合。

**被修正的部分**：`setActive` 的残留**会被**随后的 finally 收掉——`cancelStream()` 不动 `sendEpoch` / `claimToken`，所以 finally 里 `if (claimToken === streamingClaimToken)` 成立，`SessionActivityTracker.setInactive(activeSessionId)` 照常执行。只有在 claim 已被别的回合抢走的并发场景下才会残留「active」，那是另一个已登记的 claim 竞争问题，不是本条的必然结果。⇒ 级别仍是 P2/L=Z（降级），修法不变。

---

## 3 修法细节（判据层）

### F-05：选「可被唤醒的终止信号」，不用超时常量

任务书给了两条路，倾向第 2 条。先看既有语义再拍：

- `AsyncJsSlot` 的假设是 **一次只有一个 in-flight**（`ifOwner` / `begin` 会驱逐前一个），它服务的是 `executeJS` / `awaitPromiseJs` 那条「一次一个桥式请求」的通道；
- `evaluateJavascript` 是**每次调用一个** deferred，这一层没有任何串行化（per-tab `Mutex` 在上一层 `BrowserTabPool`）。把并发调用塞进单槽会让它们互相驱逐（驱逐路径还会给对端 complete 一条 `superseded by a newer async JS request` 的假错误）。

所以取**多观察者**形态：新增 `private val tabDeathSignal = CompletableDeferred<Unit>()`，由 `onRendererGone` 与 `destroy()` 各 complete 一次，`evaluateJavascript` 改为

```kotlin
awaitJsResultOrTabDeath(deferred, tabDeathSignal) {
    deadTabAsyncJsAnswer(destroyed, rendererDead)!!
}
```

- **不加超时常量**：要修的事件是「回调永远不来」，不是「页面慢」；写死一个数会误杀大文档上的合法慢求值，而工具路径本来就有 60 s 外壳（§2）。信号是终止事件、幂等（`complete` 返回 false 即忽略）、无需 token、无需复位（渲染器死亡与 `destroy` 对本实例都是终态，tab 不会重建）。
- **顺序**：`onRendererGone` 里 `rendererDead = true` 先、`tabDeathSignal.complete(Unit)` 后——答案由该 latch 派生，反过来读到的就是旧值（变异臂 5 钉住）。
- **平手偏好**：`select` 里 result 子句在前，已送达的结果赢过已触发的死亡事件（变异臂 2 钉住）。
- 答案沿用桥自己的 reject 形状 `{"error":…}`，所以 `evaluateAndReturn` / `evaluateJSAndParse` / `fetch` 都走既有错误分支，不会把死 tab 答案当成功结果。

### F-21：把判据收成一处，而不是第四次内联 `is CancellationException`

同族已在 `ChatModelRouting` / `AgentLoopEngine` / `ConversationHistoryTool` 各修一次（本轮是第 4 次），所以新增顶层助手：

```kotlin
internal suspend fun <T> attemptUnlessCancelled(
    onFailure: (Exception) -> Unit,
    block: suspend () -> T,
): T?
```

取消永远原样抛出（不进 `onFailure`），普通失败交给调用方记日志并答 null（保持原 `getOrNull()` 形状，行号从 `runCatching` 的 `.onFailure{}.getOrNull()` 链变成一次调用）。

**不复用** `Throwable.asConsumerSideCancellation()`：那个谓词靠 `cause == null` 区分「流被拆」与「流报错」，DB 写入没有这层语义——写不进去就是写不进去，任何 `CancellationException` 都该原样抛。

---

## 4 证据

**本地装置** `shared/work/r3full/deferred-jvm/`（在 worktree 之外，不会被 `git add -A` 扫走）：

- `extract.py` 按**顶层声明边界**切出 4 个真实函数体（`awaitJsResultOrTabDeath` / `deadTabGoneMessage` / `deadTabAsyncJsAnswer` / `attemptUnlessCancelled`），写进各自的包，再与**仓库里那两个测试文件原样**一起编译 —— 断言跑的是生产文本，不是副本。切分按「下一行是 `/**` / `internal ` / `private ` / `@` / `class ` 或列 0 的 `}`」终止：F-05 的两个答案是**表达式体**（`= if … else …`），花括号配对会把 true 分支当成函数结尾（首版就这么挂过）。
- 基线：**13 例全绿**（F-05 8 例 / F-21 5 例）。
- 形状臂从真实源码取（`user.dir` = worktree 根），剥离器带**字符字面量**状态：`).trim('"')` 那种 `'"'` 会让「只处理字符串」的剥离器把后续大段代码当字符串吞掉（首版 `fun destroy() not found` 就是这个）。

**门**（单跑，未跑全量 `scan.sh` / `test_scan.py`）：

- `scripts/scan/browser_destroy_wakeup_check.py` → `Summary: 44 passed, 0 failed`（`BrowserUseManager.kt` 的 teardown 判据全绿，新增的第三个唤醒点没有被误判）；
- 涉及 `ChatViewModel` 且出现 `runCatching` 的 8 道门（`chat_session_id_binding` / `chat_ui_interaction` / `claim_release_guard` / `file_provider_paths_coverage` / `open_url_broker_scheme` / `readonly_mount_write_guard` / `skill_restore_session_cleanup` / `sysinfo_state_thread`）→ 全部 rc=0。

**关键断言是「有限时间内返回」，不是「不崩溃」**：F-05 的三条死亡臂都套 `withTimeout(3000)`，回归时表现为 `the wait never returned — the death event does not wake it`（变异臂 1 就是这个形态），而不是把测试跑挂。

---

## 5 变异负控（11 臂，`deferred-jvm/mutate.py`）

快照 + `finally` 还原 + sha256 校验；单臂单跑（`ARM=N`），基线必须 GREEN 才开跑。

| 臂 | 变异 | 期望红 | 结果 |
|---:|---|---|---|
| 1 | `awaitJsResultOrTabDeath` 退回 `result.await()` | 三条死亡臂全挂（超时） | RED-PRECISE（10 绿） |
| 2 | select 子句换成「死亡在前」 | 平手偏好 | RED-PRECISE |
| 3 | `onRendererGone` 不再 complete 信号 | teardown 接线 | RED-PRECISE |
| 4 | `destroy()` 不再 complete 信号 | teardown 接线 | RED-PRECISE |
| 5 | 删掉 `rendererDead = true`（latch 让位给信号） | latch 先于信号 | RED-PRECISE |
| 6 | 调用点答案换成常量 `""` | 形状臂「答案在竞速点由 latch 派生」 | RED-PRECISE |
| 7 | 删掉 `catch (CancellationException) { throw e }` | F-21 传播 + 顺序模型 | RED-PRECISE（11 绿） |
| 8 | CE 也交给 `onFailure` 再吞 | F-21 传播 + 顺序模型 | RED-PRECISE（11 绿） |
| 9 | 调用点整体退回 `runCatching{}.onFailure{}.getOrNull()` | 接线臂 | RED-PRECISE |
| 10 | `agentHistory.add` 挪到被守卫的持久化之前 | 顺序臂 | RED-PRECISE |
| 11 | 死亡答案在等待前先求值（`val captured = deathAnswer()`） | 「wake-up 时求值」 | RED-PRECISE |

**一处曾经 BLIND，值得记**：臂 6 第一次是 GREEN —— 形状臂断言 `deadTabAsyncJsAnswer(destroyed, rendererDead)!!` 出现过，而**入口的 `webViewInert()` 提前返回里也有同一句**，所以调用点换成常量仍然绿。这正是「锚点落在被测量分支之外」。修法 = 先在剥离后的函数体里定位竞速调用，再在它之后 200 字符内找答案表达式。

---

## 6 未修边界与新增天花板

1. **活着的渲染器卡死仍无超时**：本支只修「回调永远不会来」。若页面陷入死循环导致 `evaluateJavascript` 永不回调且渲染器未死，仍由工具路径的 60 s 外壳兜底（§2）。要根治需要在 `evaluateJavascript` 上加真正的超时语义，那会改所有调用方对「慢页面」的容忍度，不在本支范围。
2. **`ChatViewModel` 其余 4 处同族 `runCatching`（suspend 调用）未修**（登记进 `backlog.md` §FIX-DEFERRED-1119 R1）：
   - `:2750` `runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0` —— 取消时答 0，随后向用户弹 `sysmsg_revert_failed_db`（**用户可见的错误归因**，同族里最像本条的一处）；
   - `:2983` `runCatching { loadSession() }.onFailure{ log }`；
   - `:3127` `runCatching { chatRepository.getSession(sid) }.getOrNull()`；
   - `:5294` `runCatching { dao.deleteCompactMarkers(sid) }.onFailure{ log }.isSuccess`。
   另外 3 处（`:3295` `File.delete()`、`:3350` `pruneExpiredShareInboundFiles`、`:5515` `f.delete()`）是阻塞调用，**不属同族**（没有挂起点就不会从这里抛 CE）。
3. **静态门难做**：要判「这个 `runCatching` 块里是否含挂起调用」需要类型信息，纯词法门只能靠白名单（`dao.*` / `chatRepository.*` / `loadSession()`），会把第 4 处也误伤。搁置理由写在 backlog 里。

---

## 7 文件清单、CI 与收口

- 代码提交 `974ce7a10e00c8b71cea56de02f5bfeec3d78472`：4 文件（2 生产 + 2 测试），`+146 / −7`。
- 本地：装置 13 例绿；变异 11/11 RED-PRECISE；`browser_destroy_wakeup_check` 44 passed / 0 failed。
- **CI**：run **#3344** / `44478099263` @ `974ce7a10e00c8b71cea56de02f5bfeec3d78472`（本地 HEAD = 远端 = run `head_sha` 逐字符一致），五 job 全 success。
  - unit-tests **9,837 PASSED / 0 真 FAILED**（含 `FAILED` 字样的 4 行都是用例名里带 FAILED 且结尾 PASSED）；新增 13 例逐条 PASSED（`BrowserJsAwaitTeardownTest` 8 + `ChatResumePersistCancellationTest` 5）。
  - scan-gate **115 passed, 0 failed**，日志另有 `scripts/scan/** untouched in this push — skipping the fixture self-test`；`scripts/scan/**` 未改 ⇒ 夹具自检按机制跳过（绿不含夹具证据）。
  - 五个 job（scan-gate / lint / unit-tests / build-apk / publish）全 success；publish 的 tag / release 两步 skipped（main 门控），分支不产 release 资产。
- **停止节点**：CI 绿 + head_sha 一致即停，**未合 main**。
- 报告提交后不再 dispatch CI（绿的是代码不是提交；`git diff <code_sha>..HEAD -- src/` 为空）。

---

## 8 复现命令

```sh
# 本地装置（生产文本 + 仓库测试原样）
sh /var/minis/shared/work/r3full/deferred-jvm/run.sh

# 变异负控（基线 → 单臂 / 全臂）
cd /var/minis/shared/work/r3full/deferred-jvm
python3 mutate.py            # 只跑基线
python3 mutate.py ARM=1      # 单臂
python3 mutate.py ALL        # 全臂（约 12 分钟）

# 单门
cd /var/minis/shared/work/r3full/deferred
python3 scripts/scan/browser_destroy_wakeup_check.py "$PWD"
```

真机验证（可选，低频）：浏览器开一个 tab 后触发渲染器死亡（系统杀后台渲染器），再对该 tab 发一次浏览器动作 —— 修复前该动作白等 60 s 后报「tab 忙」；修复后立即返回「渲染器已被系统杀死，请开新 tab」。
