# FIX-W1118-provider — D6（provider 协议与网络）+ R5（provider 流控与取消环）判级与修复

- **分支**：`fix/w1118-provider`（worktree `shared/work/incr1118/wt3`）
- **基线**：`c85131ad`（= 当前 main，已含 FIX-1117B 五支）
- **代码 tip**：`598564a3`
- **来源**：`shared/work/meta-audit/reports/wave1118/Wave_1118_D6_Report.md`（F-01…F-09）、
  `Wave_1118_R5_Report.md`（F-01…F-05）
- **改动**：`provider/CallCancellation.kt`（+44/−8）、`agent/runtime/AgentExecutionBudget.kt`（+9/−3）、
  `provider/voice/VoiceProvider.kt`（注释 22 行）、`AgentExecutionBudgetTest.kt`（+26）、
  新增 `provider/CallCancellationTest.kt`（162 行）。
- **修了 2 条**（1 条 P1 + 1 条按要求指派的 P2-latent），**证伪 1 条**（D6 F-01），
  **9 条判 P2 登记**，**2 条跨域指针**。

---

## 1. 判级表（本支全部条目）

| ID | 报告级 | 我的判级 | 结论 | 去向 |
|---|---|---|---|---|
| **D6 F-01** 三家 `firstDataWatchdog` 未在 `finally` 取消 →「HTTP 200 空体挂起 30 分钟」 | high | **证伪（P3）** | 空体流不会挂：`callbackFlow` 收尾时会取消子协程，实测 collect 95 ms 返回（§3.1） | 不修；试改已回退 |
| **D6 F-02** `contentParts` 非空分支丢 `msg.audioParts` / `msg.imageParts` | high | **P2**（image 半边证伪、audio 半边 latent） | 图片在两条载体里是同一份字节、且 `contentParts` 分支已发；音频在当前树没有「contentParts 非空 + audioParts 非空」的构造点 | 登记（§5.1） |
| **D6 F-03** `OffloadPermissionManager` 先插后删 + 非 FIFO 淘汰 | high | **跨域** | 文件在 `offload/`，本支 allowlist 外 | 跨域指针（§6.1） |
| **D6 F-04** `[DONE]` 时 `Finished` 先于 `ToolCallComplete` | medium | **P2**（契约漂移） | 唯一消费者 `AgentLoopEngine` 把两者分别累加/暂存，`failOnSilentEmptyCompletion` 也不依赖顺序 | 登记（§5.2） |
| **D6 F-05** `executeOrCancel()` 阻塞期不响应取消 | medium | **P1** | **修**（§2.1） | 已修 |
| **D6 F-06** `cacheReadInputTokens` 显式 0 仍折成 null（OpenAI/Gemini） | medium | **P2**（展示语义漂移） | 影响缓存面板「0 与未知」的区分，不影响请求；三处对称改动，非顺路 | 登记（§5.3） |
| **D6 F-07** Responses API 缺跨消息 `call_id` 去重 | medium | **P2**（窄触发） | 需「历史里真的出现重复 call_id」+「useResponsesAPI 打开」；改 id 会牵动 `callId\|fcId` 配对约定，不是低风险单点 | 登记（§5.4） |
| **D6 F-08** Gemini `systemPrompt=""` 仍发 `{"text": ""}` | medium | **P2**（触发面窄） | 生产系统提示词非空；但确是单行守卫，列入 §5.5 待拍板 | 登记（§5.5） |
| **D6 F-09** `WeatherManager` 未关 `Response` | low | **跨域** | `offload/` 外 | 跨域指针（§6.2） |
| **R5 F-01** `consumeEstimatedTokens()` 未扣 `reservedChildTokens` | high | **P2**（latent：无生产调用者） | **修**——任务书 §7 明确指派 + 契约不变量被打破 + 3 行 + 有变异臂（§2.2） | 已修 |
| **R5 F-02** `ImageBudget.planRequestBudget` 假定压缩必然达标 | high | **P2**（窄触发：需 ≥5 张 ≥per-image 且压缩失败） | 估算失准只在「阶梯耗尽仍超单图上限」时发生，解码失败/用户把单图上限调得低于阶梯地板 | 登记（§5.6） |
| **R5 F-03** `ImagePartId` 用 32-bit `identityHashCode` 当键 | medium | **P2**（32-bit 碰撞） | 只有真碰撞才误删最新图；改成引用相等要动 provider 侧查表 API | 登记（§5.7） |
| **R5 F-04** `DisposableHandle` 未释放 | medium | **P1** | **修**（与 D6 F-05 同一处，一并重写） | 已修 |
| **R5 F-05** `compressUnderBudget` 在不可解码输入上空转 7 档 | low | **P2**（纯性能） | 只多花 CPU，不改变返回值 | 登记（§5.8） |

判级口径（POLICY）：P0 = 数据/凭据/崩溃/核心不可用；P1 = 常用路径可感知；P2 = latent / 窄触发 /
契约漂移 / 测试债。报告给的 `high` 多数是「代码与注释不一致」，不是「有人会撞上」——
这正是修复门要挡的那一类。

---

## 2. 修复逐条证据

### 2.1 D6 F-05 + R5 F-04：`executeOrCancel()` 真的取消（P1）

**这改变什么用户行为？** 用户在模型列表刷新/切供应商/离开页面时取消，网络请求当场断开，
而不是继续占着 OkHttp 线程把读等到超时（语音链 120 s，生成流 30 min）。

**代码（修前 / 修后）**

```kotlin
// 修前：hook 挂在「调用方 job」上——而调用方 body 正阻塞在 execute() 里，
//       它不可能进入完成态，所以 hook 永远不会在阅读期间触发。
currentCoroutineContext().job.invokeOnCompletion { cause ->
    if (cause is CancellationException) cancel()
}
try { execute() } catch (e: Throwable) { ensureActive(); throw e }
// 且 invokeOnCompletion 返回的 DisposableHandle 直接丢弃 → 长生命周期 job 上留下 Call 强引用。

// 修后（CallCancellation.kt：coroutineScope + 子 watcher + finally dispose）
internal suspend fun Call.executeOrCancel(): Response = coroutineScope {
    val scopeJob = currentCoroutineContext().job
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
    val handle = watcher.invokeOnCompletion { if (!scopeJob.isActive) cancel() }
    try { execute() } catch (e: Throwable) { currentCoroutineContext().ensureActive(); throw e }
    finally { handle.dispose(); watcher.cancel() }
}
```

`if (!scopeJob.isActive)` 这个守卫是必须的：我们自己的 `finally` 也会取消 watcher，
若无条件 `cancel()` 就会在正常返回后把调用方还要读的 body 打断（`CallCancellationTest`
的第二条用例正是钉这个方向）。

**为什么不是「另写一份」**：`VoiceProvider.executeRequest` 早已在本地实现了同一套
（da1020 ISSUE-05 的探针记录：旧 hook `promptCancelCount=0`）。这次把它收进共享函数，
四个 `*ModelsApi` + `VoiceProvider` 一起受益；VoiceProvider 的本地 watcher 保留
（它的 trampoline 无条件取消，属额外保险），只把那段「executeOrCancel 自己的 hook 到不了
完成态」的过期注释改成现状说明。

**装置：沙箱实测（真 okhttp 4.12 + 真 kotlinx 1.9.0 = 应用在用版本）**

- 测试文件：`test/.../provider/CallCancellationTest.kt`（新，真 socket，不 mock 取消机制）
  - 臂 1：服务端 accept 后一言不发；`delay(300)` 让读真的阻塞；`cancelAndJoin()` 计时 +
    断言 `call.isCanceled()`。
  - 臂 2（反向）：正常响应体必须仍可读，且 `isCanceled()` 为 false。
- 双臂（对同一份测试，只换被测实现）：

| 实现 | 结果 |
|---|---|
| 修前（`git` 基线字节） | `expected the blocked execute to be torn down promptly (<1500ms), took 2714ms` / `Tests run: 2, Failures: 1` |
| 修后 | `OK (2 tests)` |

- 计时探针（`shared/work/incr1118/t3probe/timing/Timing.kt`，读超时 3 s 代替 30 min 的同一形状）：
  `cancelToJoin=16ms`（修后） vs `cancelToJoin=2782ms`（修前）。
  生产上这条读超时是 120 s（语音）/1800 s（生成流），差值是「秒级 vs 半小时」。

**R5 F-04（句柄泄漏）**：`handle.dispose()` 在 finally 里。*不写测试*——WeakReference + GC
的断言在 CI 上不稳定，写出来也是靠 `System.gc()` 赌；改为在代码里显式 dispose + 上面臂 2
保证「dispose 早于 watcher.cancel 且不影响健康响应」，剩余部分靠评审。

### 2.2 R5 F-01：token 预算扣除 child 预留（P2-latent，任务书指派）

**这改变什么用户行为？** 目前没有——`AgentLoopEngine:768` 明确写着 engine 从不调用
`consumeEstimatedTokens`（token 维度是 snapshot-only），所以这是**契约修复**，不是可感知修复。
判级如实记 P2；动手理由有三：任务书 §7 明确指派、被打破的是该类自己在
`tryReserveChildBudget` 处写下的明文不变量、成本 3 行且有变异臂。

```kotlin
// 修前：available = cap - used          → 父级可花掉已承诺给 child 的配额；
//                                          随后 child 兑现自己「已获准」的预留反而被拒。
val available = cap - used - reservedChildTokens
if (available <= 0L || tokens > available) return Denied(TOKEN_BUDGET_EXCEEDED)
```

保留旧行为的一点：`cap=0` 时 `available=0` 仍拒绝（与旧的 `used >= cap` 等价），
所以「全预留」状态下消耗 0 token 也是 Denied —— 与旧实现对 `used >= cap` 的处理一致。

**装置**：`AgentExecutionBudgetTest` 新增
`parent consumption cannot spend tokens already reserved for a child`
（先 `tryReserveChildBudget(600)`，再断言 `consumeEstimatedTokens(800)` 被拒、状态不变、
400 可花、随后 `consumeChildTokens(600)` 能兑现）。

- 沙箱跑真实生产文件 + 真实仓库测试文件（kotlinc 2.1.0 + JUnitCore）：`OK (25 tests)`。
- 变异 M1（改回 `available = cap - used`）：`Tests run: 25, Failures: 1`，失败行正是新臂
  `expected Denied, got Allowed`（`AgentExecutionBudgetTest.kt:220`）。

---

## 3. 证伪

### 3.1 D6 F-01：空体流不会挂 30 分钟（INV-01 的「违反」判定不成立）

报告的核心断言是「`firstDataWatchdog` 是 `callbackFlow` 的子协程，未取消 ⇒ `channel.close()`
与 `awaitClose` 返回后，scope 仍等它 ⇒ collect 挂满 1800 s」。**前半段是代码事实，后半段不成立。**

**先证（用应用在用的 kotlinx-coroutines 1.9.0，非沙箱默认的 1.8.1）**
装置：`shared/work/incr1118/t3probe/wd/Topology.kt`，复刻生产拓扑
（`flow { emitAll(callbackFlow{…}.flowOn(IO)) }`，`channel.close()` + `awaitClose {}`，
子协程 `delay(10s)`）：

```
arm withFinallyCancel=false
    [parent] body returned, child.isActive=false
    [child] finally (cancelled)
  collect returned after 95ms
arm withFinallyCancel=true
    [child] finally (cancelled)
  collect returned after 9ms
```

1.8.1 同形状亦为 77 ms。即：**流收尾会取消（或已取消）该子协程，`collect` 不等它**，
30 分钟的 `delay` 从来没有机会阻塞调用方。三家 provider 都在 `awaitClose` 之前显式
`channel.close()`（OpenAI/Anthropic/Gemini 逐一确认），形状与探针一致。

因此「HTTP 200 + 空体 ⇒ 卡 30 分钟」不成立；空流依旧由
`failOnSilentEmptyCompletion` 立刻转成可重试的 `TransientError`。

**处置**：按 POLICY「证伪就写证伪理由，不许硬修」，我已把试改的三处 `finally` 取消
**回退**（`git checkout` 三家 provider），并删掉随之写的 `ProviderFirstDataWatchdogTest`
（它在基线上也是绿的，等于给已成立的行为加装饰性测试）。

**保留意见（给下一轮）**：本证伪是「流拓扑级」的实测，不是真 provider 的端到端跑
（沙箱无 Android SDK，无法编 provider）。若主会话想彻底闭合，可在合并前用真机/CI 加一条
「200 空体 → 5 s 内 TransientError」的契约测试——它不需要修任何代码，只作为回归护栏。

### 3.2 D6 F-02 的 image 半边

报告称 `contentParts.isNotEmpty()` 分支「完全未读取 `msg.imageParts`」⇒ 消息级图片丢失。
**不成立**：生产上图片永远同时写进两条载体——
`ChatStreamToolHelpers.kt:139-143`（restore 路径：同一个 `bytes` 同时进 `imageParts` 与
`contentParts.add(ImageData(bytes,...))`）、composer 路径（`ModelExecutionDispatcher.kt:234-240`
的注释与代码）、`ImageElisionPlan.kt:93-101`（elide 时两边同步删）。所以 `contentParts` 分支
经 `ImageData` 已经发出图片；未读 `msg.imageParts` 只对「手工构造、contentParts 非空而
imageParts 独有字节」的合成消息成立，生产无此构造点。

audio 半边是真的「未消费」，但同样没有构造点（§5.1）。

---

## 4. 未修项的三件套草案（P2 登记）

格式：**触发条件 / 方案 / 搁置理由**。

### 5.1 D6 F-02（audio 半边）：`contentParts` 非空时 `msg.audioParts` 被静默丢弃

- **触发条件**：出现「`contentParts` 非空 + `audioParts` 非空」的同一条 `LLMMessage`。
  当前唯一音频构造点是 `ModelUseOffloadHandler.kt:1317`（`content=…, audioParts=…`，
  contentParts 为空 ⇒ 走 legacy 分支，正常发送）；`mergeSameRoleNeighbors`
  （`ChatSessionLifecycle.kt:421-422`）虽会拼接 audioParts，但聊天历史里没有音频消息。
  **升级触发** = composer/聊天链路新增录音附件，或 model-use 允许 `input_audio` 与
  `content` 数组混用。
- **方案**：把 legacy 分支的「图片 → 音频 → 文本」组装提成一个纯 helper
  （参数：`images/audios/text/supportsImages/compress`），两个分支共用；Chat Completions
  与 Responses 各调一次。测试：`contentParts=[Text] + audioParts=[wav]` 的 body 必含
  `input_audio`，且图片不重复（沿用 `TurnImageCarry` 的内容去重）。
- **搁置理由**：无触发面（修复门），且一改动就是 30 行 × 2 个 builder 的热路径重排，
  风险远大于当前收益。**不要**只补 audio 而把 image 也补一遍——image 已经发过了，
  补进去就是重复发送。

### 5.2 D6 F-04：`[DONE]` 分支先发 `Finished` 再发 `ToolCallComplete`

- **触发条件**：出现依赖「终止事件必须最后」的消费者。
- **现状证据（为什么现在不是缺陷）**：`AgentLoopEngine.kt:1748/1836` 对
  `ToolCallComplete` 与 `Finished` 分别累加（`toolCalls.add` / `turnFinishReason=`），
  顺序无关；`LLMProvider.kt:234-236` 的 `failOnSilentEmptyCompletion` 也只看「有没有」。
- **方案**：把循环外那次 flush 提到 `[DONE]` 分支内，`Finished` 之后统一在循环外发；
  或反过来在 `[DONE]` 里先 flush 再发 `Finished` 并 `toolCallAccumulators.clear()`
  （报告草图即后者）。改完要跑 `MetaR3B08WiringTest` 与 tool-pairing 属性测试。
- **搁置理由**：契约漂移 ≠ 缺陷；改动会动到「截断工具调用」这条 T247/T248 老修复的
  收尾路径，收益为零而回归面不小。

### 5.3 D6 F-06：`cacheReadInputTokens` 显式 0 → null（OpenAI ×2 + Gemini）

- **触发条件**：用户看缓存面板/计费统计，需要区分「未命中缓存（0）」与「不支持/未上报」。
- **方案**：三处 `parse*Usage` 改成先判 `has(...) && !isNull(...)`（Anthropic 已是此形，
  `AnthropicProvider.kt:1509-1511` 为基准），补一条「显式 0 保持 0」的参数化测试。
- **搁置理由**：纯展示语义；三处对称改动 + 测试，不属于「同一函数顺路」。

### 5.4 D6 F-07：Responses API 缺 `call_id` 跨消息去重

- **触发条件**：`useResponsesAPI = true` 且历史里出现重复 `call_id`
  （跨供应商迁移、截断后重试的历史）。
- **方案**：为 `function_call` / `function_call_output` 写一个与
  `globallyDedupeToolCallIds` 对称的 pass，但**必须**沿用 `combineResponsesAPIIds` /
  `splitResponsesAPIIds` 的 `callId|fcId` 配对规则（改 `call_id` 而漏掉 `item_id` 会把
  配对拆散），并补一条 Responses 侧 body 的结构测试。
- **搁置理由**：窄触发 + 改错方向会破坏工具配对（比现状更糟）；需要一条能跑 Responses
  流的装置才动手。

### 5.5 D6 F-08：Gemini 空 `systemPrompt` 仍发 `{"text": ""}`

- **触发条件**：调用方传 `systemPrompt = ""`（而不是 null）。当前生产构造点都是非空常量
  或系统提示词装配结果，未观察到空串。
- **方案**：`GeminiProvider.buildRequestBody` 的守卫从 `systemPrompt != null` 改成
  `!systemPrompt.isNullOrEmpty()`（一行，与 `resolveSystemPrompt` 的 `isNullOrEmpty` 对齐），
  补一条「body 不含 systemInstruction」的用例。
- **搁置理由**：一行就能修，但它是**另一个函数**，不满足「顺路成本≈0」；留给主会话决定
  要不要单独放行（我倾向下一轮顺手带上，因为它零风险）。

### 5.6 R5 F-02：`planRequestBudget` 假定压缩必然达标

- **触发条件**：`compressUnderBudget` 阶梯耗尽仍 > `perImageMaxBytes()`，同时图片张数
  足够把估算撑到 `requestMaxBytes()` 附近。阶梯地板是 640px/q45（`ImageBudgetLadder`），
  默认单图上限 5 MB 下可解码图片**几乎必然达标**；真正会穿透的是两类：
  ① 字节 ≥5 MB 且**解码失败**（返回原字节）；② 用户把单图上限调到低于地板输出
  （例如 512 KB，用户可调）。
- **方案**：不要在校验阶段反推压缩结果（plan 期逐张压缩 = 请求路径上多一次全量解码）。
  可行方向是让 `compressUnderBudget` 返回「是否达标」（或加
  `compressUnderBudgetReport`），provider 侧把「不达标」的图直接换成
  `elidedImagePlaceholder` —— 但这要动 8+ 个 provider 内联点，属于跨函数改动。
- **搁置理由**：窄触发；修法要动多个 provider 序列化内联点，装置成本远超当前影响面。

### 5.7 R5 F-03：`ImagePartId` = 32-bit `identityHashCode`

- **触发条件**：同一请求里两张图片的 `System.identityHashCode` 真碰撞（需 ~65k 个存活堆
  对象规模），且被丢的是较旧那张 ⇒ 最新图被误 elide。
- **方案**：把 `ImagePartId` 从 `value class(Int)` 改成持有 `ByteArray` 引用、`equals`
  用 `===`（报告草图），或改用「列表下标 + 会话」作键。
- **搁置理由**：碰撞概率极低，且改了要同步 4 个 provider 的查表点（`droppedIds` 语义不变
  但键类型变）；顺手改反而扩大回归面。

### 5.8 R5 F-05：`compressUnderBudget` 对不可解码输入空转 7 档

- **触发条件**：传入 ≥`perImageMaxBytes()` 且无法解码的字节。
- **方案**：`compressBytes` 返回原引用（`candidate === input`）时直接 `break`
  （解码/编码失败，降档重试无意义）。
- **搁置理由**：纯 CPU 浪费（7 次失败解码），不改变返回值和线上行为；且它和 §5.6 是同一个
  函数族，等 §5.6 一起改更划算。

### 5.9（本支顺带发现，非报告条目）VoiceProvider 的本地取消 watcher 已冗余

- **触发条件**：下次有人改 `VoiceProvider.executeRequest`。
- **方案**：删掉 `watchJob` / `watchDisposable` 与 try/finally，改用共享
  `executeOrCancel()`（己含等价机制），同时改 `voice_cancel_guard_check.py` 的 A 臂断言
  与夹具（该门现在硬断言这段本地 watcher 存在）。
- **搁置理由**：删除要重做一次计时证明（120 s 读超时那档），且要动门脚本（本支禁碰
  `scripts/scan/**`）；保留的成本是 6 行重复代码 + 一次多余的 `call.cancel()`（无害）。

---

## 6. 跨域指针（本支 allowlist 外，交主会话分派）

### 6.1 D6 F-03 `offload/OffloadPermissionManager.kt`

`checkPermission` 先 `computeIfAbsent(sessionId)` 再 `pruneSessionMapsIfNeeded()`
（L619-625），淘汰用的是无序 `ConcurrentHashMap.keys.firstOrNull()`（L857-869），
与 KDoc 自述的「oldest-inserted first」矛盾，且 65 号会话可能当场把自己踢掉，
随后 `ALLOW_SESSION` 写进已脱离 map 的游离 Set（L708/L713）。
**建议判级 P2**（需进程内累计 64 个会话才会发生；`pruneSessionMapsIfNeeded` 也可改为
「先裁到 63 再插入 + 排除当前 sessionId」）。归 **offload 支**。

### 6.2 D6 F-09 `offload/WeatherManager.kt:57-70`

`newCall(...).execute()` 的 `Response` 未 `.use {}` / 未 `finally close()`，异常路径漏连接。
**建议判级 P2**（低概率连接泄漏，同域其它调用点已是 `.use` 形状）。归 **offload 支**。

---

## 7. 未确认清单的消化（不做处理，仅记录）

- D6 §5.4 的五条（`NetworkMonitor.stop()` 清理、`OffloadPermissionManager.init` 跨进程、
  `AnthropicModelsCache` 无 schemaVersion、`ModelsDevApi` 304 不刷新 mtime、Responses
  builder 对非 USER/ASSISTANT 角色的 contentParts）—— 与本次修复无交互，仍缺域外证据，
  维持「未确认」。
- R5 §5 的七条（超时先到层的异常类型、预算拒绝后的重试终态、`OfflineRetryHold` 与前台服务
  竞争、`StreamCancelBridge.dispose` 时序、`NetworkMonitor` 代理环境、`OfflineRetryHold`
  的 `heldMs += 500` 漂移、两定标环文件缺失）—— 同样维持未确认。
- **与本次修复相关的一条已闭合**：R5 §5.4「`dispose()` 是否在异常分类之前被调用」——
  新实现里 `dispose()` 只移除我们自己的 watcher hook，不触碰
  `asConsumerSideCancellation` 的任何状态，二者无耦合。

---

## 8. 收口

- 沙箱窄验证：`AgentExecutionBudgetTest`（25 绿 + 变异 1 红）、`CallCancellationTest`
  （2 绿 + 前臂 1 红）、`voice_cancel_guard_check.py`（PASS，selftest 37 臂全过）。
- 未跑全量 `scan.sh` / `test_scan.py` / gradle（POLICY）；分支 CI 由主会话看。
- **停止节点**：分支 CI 绿且 `head_sha` 与分支 tip 逐字符一致。**不合并 main。**
