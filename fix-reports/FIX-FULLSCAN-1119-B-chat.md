# FIX-FULLSCAN-1119-B-chat — chat 域取消/竞态/估算六条（F-04 · F-05 · F-06 · F-07 · F-08 · F-12）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `ecb03d92`（= `origin/main`，worktree `git log --oneline -1` 实测） |
| 分支 | `fix/chat-cancel-1119` |
| worktree | `/var/minis/shared/work/r3d1119f/chat` |
| 提交序列 | `6e98d0f3` 生产+测试（13 文件 +968/−25）· `a3276040` 门形态补齐（2 文件 +90/−26）· 本报告 |
| 分支尖端 / CI 绿 sha | `a3276040dc0324554551880e1baf247d7fb7419c`（run **#3320** / id `44449259213`，五 job success） |
| 三方 sha | 本地 `git rev-parse HEAD` == `git ls-remote origin` == CI `run.head_sha`：逐字符一致（§5） |
| 改动量 | 生产 **5 文件 +291/−23**；测试 **8 文件 +670/−2**（含新增 6 个测试类 + 1 个切片助手） |
| 门/夹具 | `scripts/scan/**` **零触碰**（`git diff --stat ecb03d92..HEAD -- scripts/scan` 为空） |
| 禁止面 | `ChatViewModel.kt` **零改动**（F-21 排队，未越界）；`provider/**`、`tools/FileReadTool.kt`、`backup/**`、`offload/**` 零触碰 |

## 2 缺陷与先证（逐条，先读码确认机制，无一条被证伪）

**共同母题**：`CancellationException` 被 `catch (e: Exception)` 吞掉；跨协程的 job/epoch 认领没有成对撤销。

### F-04【P1】`cancelStream()` 撤销不了 claim，`runRerunStreamTail` 在 `viewModelScope` 起流 —— **成立**

- 读码确认（`ChatViewModel.kt:4749` `cancelStream()`）：函数体写 `_isStreaming.value = false`、`streamJob?.cancel()`、`compactJob?.cancel()`、`sendShellJob?.cancel()`，**没有任何 `streamingClaimEpoch` / `streamingClaimToken` 的写入**。只有「新回合」才重新铸币（`ChatViewModel.kt:3497/3768/4394/4869`、`ChatRetryOps.kt:51`、`ChatModelRouting.kt:326`、`ChatQueueInterruption.kt:541`）。
- 因此 `ChatTurnPersistence.kt:389` 的旧守卫 `sendEpoch != streamingClaimEpoch || claimToken !== streamingClaimToken` 在 Stop 之后**仍然成立**（epoch 与 token 都没变）⇒ 准备期点 Stop 后，tail 的撤销检查通过并起流。
- 第二半：`streamJob = viewModelScope.launch(Dispatchers.IO)`。`viewModelScope` 不随 Stop 取消，shell 被取消后这个**兄弟** launch 仍会执行。
- 判级 P1 成立：Stop 是最高频交互；准备期（系统提示词构建、DB 回读）在长会话里可达数百毫秒。

### F-05【P1】`switchModelAndRerun` 不取消 `compactJob` —— **成立**

- 读码确认（`ChatModelRouting.kt` Phase 1）：只 `sendShellJob?.cancel()` + `streamJob?.cancel()`；`compactJob` 在全文件**零出现**（`rg -n compactJob ChatModelRouting.kt` → 0 命中，改前）。
- 对比：`cancelStream()` 有 `compactJob?.cancel()`（`ChatViewModel.kt:4766`，`fix/compact-cancel-on-stop-1002` 加的），`clearChat()` 也有（`:3228`）。切换路径是**同族遗漏点**。
- 后果链：旧压缩在切换后继续跑 → 完成时 `_compactSummary.value = summary` + `_cachedLatestMarker = marker` + `chatRepository.dao.insertCompactMarker(marker)`（`CompactOrchestration.kt:454` 附近）→ 新回合的 transcript 已被倒带，压缩描述的是被删掉的那段。
- **只取消不够**（任务书点名）：`CompactOrchestration.kt` 的提交块（marker insert → `_compactSummary` publish → `withContext(Main)` 灰化）是活的窗口，cancel 落在块内仍可能落库。所以修法必须含**等待**（见 §3）。

### F-06【P1】`switchModelAndRerun` 的 `catch (e: Exception)` 吞 `CancellationException` —— **成立**

- `ChatModelRouting.kt:381`（改前）：`catch (e: Exception) { Log.w(...); _error.value = "Model switch failed: ${e.message}"; return@launch }`。
- `CancellationException : Exception`（Kotlin 事实）。Stop 取消 shell ⇒ 正在 `chatRepository.loadMessages` 的挂起点抛 CE ⇒ 走这条 catch ⇒ 用户看到「Model switch failed: StandaloneCoroutine was cancelled」。

### F-07【P1】`resumeQueueAfterCancel` 检查与认领之间夹磁盘读 —— **成立**

- 改前结构（`ChatQueueInterruption.kt:504-546`）：`if (_isStreaming.value) return@launch`（:507）→ `val baseSystemPrompt = systemPromptForSession()`（:531，同步磁盘读：SoulStore / memory / skill 目录）→ `streamingClaimToken = Any(); _isStreaming.value = true`（:541-543）。
- 两个 `resumeQueueAfterCancel` 协程（双 Stop、Stop + 压缩成功 kick `CompactOrchestration.kt:655`）都在 build 期间读到 false ⇒ 都认领 ⇒ 第二个 `streamJob =` 覆盖第一个引用，**两条 agent loop 同时写同一会话**。
- 判级 P1：这是「同会话双流」里最危险的一类（消息交错写、转录损坏）。

### F-08【P1】`injectQueuedPromptsAsNewTurn` 的 catch 吞 CE —— **成立**

- `AgentLoopEngine.kt:4612`（改前）：`catch (e: Exception) { AppLogger.warning(...); null }` ⇒ Stop 被读成「注入失败」，循环 `handled == null` 继续下一回合 ⇒ 下一回合可再起压缩（与 F-05 同族第二入口）。

### F-12【P1】`estimateMessageTokensFull` 漏计 `ToolResult.imageData` —— **成立（主会话已实锤，本支复核）**

- 改前 `ContextEstimator.kt:76`：`is AgentContentPart.ToolResult -> total += ContextCompactor.estimateTokens(part.content)`，**不含 `imageData`**。
- 同文件另外两处图片路径都计：`ImageData` 分支（:83-85）与 legacy `imageParts`（:90-92），公式都是 `ceil(4N/3)/CHARS_PER_TOKEN`。
- `AgentContentPart.ToolResult` 确有 `imageData: ByteArray?` 字段（`data/model/AgentContentPart.kt:28-38`），`read_image` / 截图工具确实填它。
- 后果：1 MiB 工具图片按 0 token 计 ⇒ 预检 / 自动压缩 / trim 全部读「还有余量」⇒ 请求超窗 400。

**公式唯一性复核（任务书要求）**：`rg -n "estimateMessageTokens|estimateMessageTokensFull"` → `ContextCompactor.estimateMessageTokens`（`ContextCompactor.kt:304`）**已委托**到 `ContextEstimator.estimateMessageTokensFull`，全仓**没有第二份消息级算式**。本次把三处图片算式收敛成一个私有函数 `imageDataTokenEstimate(bytes)`，从结构上消灭「第二份」。

## 3 修法（阶梯第 6 级：最小实现 + 单一判据函数）

**共同原则**：把判据抽成纯函数（JVM 可测、可变异），调用点只留一行接线。

### F-04
1. 新增纯函数 `streamClaimStillOwned(claimIsActive, streaming, sendEpoch, claimEpoch, callerToken, currentToken)`（`ChatTurnPersistence.kt`）。四信号：协程存活、`_isStreaming` 仍 true、epoch 相等、token 同引用。
   - `streaming` 是**旧守卫缺的那个信号**：Stop 只清旗标，epoch/token 不变。
   - `claimIsActive` 用 `coroutineContext[Job]?.isActive != false`。
2. 新增 `launchTurnStream(parentContext, block)` = `CoroutineScope(parentContext).launch(Dispatchers.IO)`。tail 改为 `streamJob = launchTurnStream(coroutineContext) { ... }` ⇒ **子协程**，继承 shell 的取消；从已取消的协程 launch 得到的是「已取消、body 永不执行」的 job。
3. `viewModelScope` import 从该文件删除（不再使用）。

### F-05
1. Phase 1 捕获 `val preSwitchCompact = compactJob` 并 `preSwitchCompact?.cancel()`（**用局部变量**，避免 await 时读到新值）。
2. shell 内、**DB 回读与倒带之前**调 `awaitCancelledCompact(preSwitchCompact)`。
3. `awaitCancelledCompact`：`withTimeoutOrNull(COMPACT_CANCEL_AWAIT_MS = 5_000L) { job.cancelAndJoin() }`，job 非活跃时直接返回。
   - **有序化而非仅取消**：旧压缩的任何写入严格早于倒带 ⇒ `dropCompactMarkerIfRewound(sid)`（已在同处调用）能看到并清掉孤儿 marker + summary；之后不可能再有旧写入落到新回合。
   - **有界**：卡在 provider 调用里的压缩不能挟持新回合；超时最坏退化到改前顺序，而不是无限阻塞。

### F-06 / F-08
新增 `rethrowCancellationOr(e: Exception, onGeneric: (Exception) -> Unit)`（`ChatModelRouting.kt`）：`if (e is CancellationException) throw e; onGeneric(e)`。
- 两处调用点（`ChatModelRouting.kt:381`、`AgentLoopEngine.kt:4612`）改用它，**失败分支语义逐字保留**（同一句 log / 同一句 `_error.value`）。
- 没有写成 `catch (e: Exception) { if (e !is CancellationException) ... }` 的两份副本 —— 一份策略、两个站点。

### F-07
1. 新增 `claimQueuedDrainSlot(streaming: MutableStateFlow<Boolean>, mintAndClaim: () -> Unit): Boolean` = `synchronized(streaming) { if (streaming.value) return false; mintAndClaim(); true }`。
   - **复用既有同步原语**：监视器就是 `_isStreaming` 这个 StateFlow 实例本身（每 VM 一份、所有流式状态读者共享的真相），**不新建第二把锁**。
2. 调用点：磁盘读（`systemPromptForSession()`）留在临界区**外**；`claimQueuedDrainSlot` 内按既有发布序铸 token → 加 epoch → 翻旗标。
3. **门形态适配（第二次提交的原因）**：第 21 门（`claim_release_guard.py`）从字面量 `_isStreaming.value = true` 派生主体、并要求「认领→启动」之间无分支或「身份守卫」。所以：
   - 旗标字面量**回到调用点**（仍在监视器内），门的 C-B8b 主体派生保持可见；
   - claim token 改为在协程**外**预铸 `val drainToken = Any()`，起流前加守卫 `if (streamEpoch != streamingClaimEpoch || drainToken !== streamingClaimToken) return@launch` —— 满足 C-B8c，且**不是**自比较（token 捕获于协程外）。
   - 早期 `if (_isStreaming.value) return@launch` 保留为**纯快路径**（省一次磁盘读），KDoc 明写它不是保护。

### F-12
1. 新增私有 `imageDataTokenEstimate(bytes: Int): Long` = `(bytes*4/3 + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN`。
2. 三个调用点（`ToolResult.imageData`、`ImageData.data`、legacy `img.data`）**全部**改用它 ⇒ 结构上不可能再分叉。
3. `ToolResult` 分支：文本照旧 + `part.imageData?.let { total += imageDataTokenEstimate(it.size) }`。

## 4 行为影响（「这改变什么用户行为」）

| ID | 改动前 | 改动后 |
|---|---|---|
| F-04 | 准备期点 Stop 后仍会起流；用户看到「停了又自己跑起来」 | Stop 之后该回合永不启流（四信号 + 子协程双保险） |
| F-05 | 流式中切模型，旧压缩完成会把摘要/marker 盖到新回合 | 切换先取消并等待旧压缩，其写入严格早于倒带并被 `dropCompactMarkerIfRewound` 清掉 |
| F-06 | Stop / 连切模型弹出「Model switch failed: …was cancelled」 | 不再弹；真实 DB 失败仍弹同一句 banner |
| F-07 | 双 Stop 可致同会话两条流并发写转录 | 只有一个 drain 拿到 `streamJob`；另一个静默退出（打一行 info） |
| F-08 | Stop 落在工具收尾边界时循环进入下一回合 | 取消传播，不进下一回合；真实 IO 失败仍按原样 log + 落回正常派发（排队提示不丢） |
| F-12 | 工具读图/截图按 0 token 计，预检「还有余量」而请求 400 | 按 base64 上界计入（1 MiB ≈ 349,526 token 上界），与其它两条图片路径同值 |

**未改变**（刻意的）：`_isStreaming` 的发布顺序、`dropCompactMarkerIfRewound` 的调用位置、排队提示在真实失败时仍会落回正常派发、`ContextEstimator` 的既有全部期望值（§5.3 逐字回归）。

## 5 验证（三层，全部留证据）

### 5.1 本地 JVM 装置（`/var/minis/shared/work/r3d1119f/harness/`）

`extract.py` 从生产文件**逐字抽取**被测函数（断言非空切片），与生产的纯文件一起 `kotlinc` 编译，跑仓库自己的测试类；`run.sh` 一键跑；`mutate.py` 做变异负控。

```
$ sh run.sh
extracted ok: /var/minis/shared/work/r3d1119f/harness/gen
JUnit version 4.13.2
..........................
Time: 0.81
OK (26 tests)
```

**交错用例（任务书硬约束 3）—— 全部真交错，非顺序执行**：

| 用例 | 交错如何被强制 |
|---|---|
| `QueuedDrainClaimRaceTest.two concurrent claims - exactly one wins` | `CyclicBarrier(2)` 同时放两个线程进临界区，赢家内部 `Thread.sleep(80)` **拉宽** 检查→翻旗窗口；去掉监视器后两线程都读到 false ⇒ 两票通过（M8 实测变红） |
| `StreamClaimRevocationTest.a stream launched from a cancelled setup shell never runs` | shell 停在自己的 `delay(60_000)`（模拟提示词构建），外部 `cancel()` 后再让它执行 launch —— 断言 job 已取消且 body 未跑 |
| `SwitchModelCompactCancelTest.a compact already past its commit cannot overwrite the new marker` | 压缩先写完 marker（`commitDone` 信号），再 `awaitCancellation()` 挂在 `withContext(Main)` 处；此时 cancel + await，然后新回合写自己的 marker —— 断言顺序是 `[old, new]` 且新 marker 是最后一个 |

### 5.2 变异负控（`mutate.py`，**12 臂全 RED，全部只翻对应用例**）

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | 删掉 `ToolResult.imageData` 计入 | RED `ToolResultImageEstimateTest` |
| M2 | 图片公式 `4/3` → `2` | RED `ToolResultImageEstimateTest` |
| M3 | `streamClaimStillOwned` 去掉 `streaming` 信号 | RED `StreamClaimRevocationTest` |
| M4 | `launchTurnStream` 换回 `CoroutineScope(Dispatchers.IO)`（脱离父上下文） | RED `StreamClaimRevocationTest` |
| M5 | 删 `preSwitchCompact?.cancel()` | RED `SwitchModelCompactCancelTest` |
| M6 | 删 `awaitCancelledCompact(...)` | RED `SwitchModelCompactCancelTest` |
| M7 | `rethrowCancellationOr` 去掉 `throw e` | RED `ModelSwitchCancellationTest` + `QueueInterruptCancellationTest`（**预期重叠**：F-06/F-08 钉同一策略） |
| M8 | `synchronized` → `run`（监视器失效） | RED `QueuedDrainClaimRaceTest` |
| M9 | 切换站点退回裸 banner | RED `ModelSwitchCancellationTest` |
| M10 | 队列注入站点退回吞 CE | RED `QueueInterruptCancellationTest` |
| M11 | 删起流前的身份守卫 | RED `QueuedDrainClaimRaceTest` |
| M12 | token 改为协程内铸造 | RED `QueuedDrainClaimRaceTest` |

还原纪律：内存快照 + `finally` 逐字节写回 + sha256 对拍（**不用 `git checkout --`**）；脚本末尾再做一次全量 sha 校验 + 干净基线跑，输出 `post-restore baseline: GREEN`。

### 5.3 既有期望零漂移

- 既有形状测试 `TurnEntryClaimShellTest` / `CompactCancelOnStopTest` / `QueuedDrainPersistenceTest` 本地重跑 **26 tests OK**（`TurnEntryClaimShellTest` 的 tail 守卫断言按新形态更新：`streamClaimStillOwned(` + `launchTurnStream(coroutineContext)`，并新增「不得回到 `streamJob = viewModelScope.launch(`」的负控臂）。
- `PrewindowBudgetTest` 的估算期望（`contentPartsNonEmptyIgnoresLegacyContent` = 2L、`legacyPathCountsContentAndTopLevelImages` = 1002L、CJK、reasoning 回显）逐字重跑通过（探针 `harness/probe/EstimatorRegressionProbe.kt`，5 tests OK），另加「无图片的 ToolResult 估算不变」= 4L。

### 5.4 本地未跑（如实声明）

- 全量 `scan.sh` / 全量 `test_scan.py` / gradle：按任务书**禁跑**。改用「单跑受影响门 + CI」取证。
- 受影响门 `claim_release_guard.py` 单跑：**改后绿**（`✅ Claim/release guard: no ungated release, self-bound gate, unguarded settlement or unminted claim found`）；改前该门在 CI #3314 报 2 findings（见 §5.5），形成天然前后对照。

### 5.5 CI（分支门，`build-apk.yml`）

| 项 | 值 |
|---|---|
| 首轮 | run **#3314** / id `44442785250` @ `6e98d0f3` → **FAILED**：`scan-gate` 红，`[21/115] Claim/release ownership guard (rc=1)` 报 **2 findings**（C-B8b 未持有 shell / C-B8c 无撤销守卫）——**都是我这轮改动引起的，不是既有缺陷** |
| 处置 | 不放松门、不改 `scripts/scan/**`：旗标字面量回调用点、token 提到协程外 + 起流前守卫（§3 F-07 第 3 点） |
| 终轮 | run **#3320** / id `44449259213` @ `a3276040` → **success**，五 job：`lint` / `unit-tests` / `build-apk` / `scan-gate` / `publish` 全 success |
| head_sha | `a3276040dc0324554551880e1baf247d7fb7419c`（三方逐字符一致） |

**证据行逐条摘录（终轮）**：

- scan-gate：`Summary: 115 passed, 0 failed`（115 门全跑）+ `[notice]scripts/scan/** untouched in this push — skipping the fixture self-test (~121s); scan.sh ran all 115 gates over this tree` ⇒ **门跑了，夹具按机制跳过**（本支未改门，绿不含「门的牙」这一维）。
- unit-tests：`BUILD SUCCESSFUL in 6m 32s`，`PASSED` 行 **9,850**（基线 9,824 + 本次新增 26）；`FAILED` 字样只出现在 PASSED 的用例名里（如 `…produces FAILED without calling provider PASSED`），**无一条真实失败行**。
- 新用例逐条在 CI 内命中（全部 PASSED）：`ToolResultImageEstimateTest` 3、`StreamClaimRevocationTest` 6、`QueuedDrainClaimRaceTest` 6、`ModelSwitchCancellationTest` 3、`QueueInterruptCancellationTest` 3、`SwitchModelCompactCancelTest` 4、`TurnEntryClaimShellTest` 5、`CompactCancelOnStopTest` 10。
- publish：`Move the rolling tag android-latest` = **skipped**、`Publish to Releases` = **skipped** ⇒ main 门控生效（job 显示 success 是「步骤全跳过」的正常形态，不是发布）。

### 5.6 独立于 CI 的证据（三源取二）

三元组：**证据类型** = 本地 JVM 实跑 + 变异负控；**观测值** = `OK (26 tests)` 与 12 臂全 RED 且只翻对应用例（M7 双红为文档化预期）；**支撑断言** = 六条修复各自的决定点被测试钉住、回退即红。

## 6 未修 / 未验证（诚实声明）

1. **未在真机验证**：本轮全部为 JVM + CI 证据。Stop 竞态、双 Stop 并发、切换模型时的压缩交错都依赖真机时序；建议在下一版装机后按 §4 表逐条对照日志（F-04 看 `tail aborted before stream (claim revoked; … streaming=false)`，F-05 看 `[Compact] history rewound past marker anchor`，F-07 看 `another turn claimed streaming — dropping this drain attempt`）。
2. **`_isStreaming` 的旗标字面量留在调用点**：这是为满足第 21 门的形状判据（门从字面量派生主体），不是语义需要。若后续把门升级为「认领助手白名单」，可再收回助手内部。
3. **`awaitCancelledCompact` 的 5s 上限是校准值**：压缩的提交块通常远小于此（一次 DB insert + 一次 Main 切换）；超时只会在压缩被 provider 卡死时触发，此时最坏退化到改前顺序。
4. **同族余点（登记不修，超出本支文件集）**：
   - `ChatModelRouting.kt:561` `rotateForNewTurn` 里的 `catch (e: Exception)` 同样会吞 CE（它是同步轮转路径，无 suspend，CE 不可达 —— 判 P2 latent）。
   - `ChatQueueInterruption.kt:828` `dropContinuationReminderTail` 的 `runCatching` 在 `viewModelScope.launch` 内，会吞该协程的 CE（不跨回合，P2）。
   - `ChatViewModel.kt` 的 F-21（`resume()` 的 `runCatching` 吞 CE）按任务书排队，本支未碰。

## 7 可复用教训

1. **「撤销」必须有成对写点**：`cancelStream()` 清旗标却不铸币，等于撤销了一半；守卫若只比 epoch/token，Stop 与「自己」无法区分。修这种守卫时，先列出**所有**在 Stop 路径上被写的字段，再看守卫读了哪几个 —— 差集就是漏洞。
2. **`viewModelScope.launch` 是「永不被 Stop 取消」的作用域**：凡是从「可能被取消的 setup 协程」里起流，必须用 `coroutineContext` 的子作用域，否则取消只到 shell 为止。
3. **取消 ≠ 完成**：`job.cancel()` 只打标记，提交块仍在跑。要保证「旧写入不会晚于新回合」，必须 `cancelAndJoin`（且带超时），并把「等待点」放在**新回合的倒带之前** —— 顺序本身就是修法。
4. **形状门会惩罚「把字面量藏进助手」**：把 `_isStreaming.value = true` 挪进 lambda 后，`claim_release_guard.py` 的主体派生立刻失明（C-B8b）。正确反应是让字面量留在调用点 + 补门认可的身份守卫，**不是**放宽门。
5. **同一个判据函数被两个站点共享时，变异臂必然双红**：M7 让 F-06/F-08 两个测试类同时红 —— 这不是「测试粗网」，而是「一条策略两个入口」的正确映射；判精度时要把这种情况写进预期，否则会误判为多红。
6. **JVM 装置抽取生产函数时必须断言切片**：`extract.py` 每段都校验长度与终止标记；否则标记漂移会静默产出「测了个空函数」的假绿。
