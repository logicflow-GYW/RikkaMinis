# FIX-INCR1113-B — 聊天循环 / UI 重组 / 顺序测试（F-03 / F-05 / F-07 / F-08）

## 1 覆盖 ID

| 项 | 值 |
|---|---|
| 来源报告 | `/var/minis/shared/work/meta-audit/reports/r7/INCR-1113.md`（外部模型对 `451170a^..4402b69` 的增量审计） |
| 本支覆盖 | **F-03 · F-05 · F-07 · F-08**（4/4 全部动手，0 条登记不修） |
| worktree | `/var/minis/shared/work/incr1113-b/repo` |
| 分支 | `fix/incr1113-loop` |
| 基线 | `4402b69`（main：`Merge ci/lint-probe-1113`） |
| 提交序列 | `74af265` F-03 · `9685771` F-05 · `8819365` F-07 · `439dd41` F-08 |
| 分支尖端 | `439dd415d073fabf2ea605e65c39ae31afdb6b8a` |
| 改动量 | 9 文件 **+721 / −107**（生产 4 · 测试 4 · 门 1；新增测试文件 1） |
| 禁碰项 | 全部未触碰（A 支独占的 9 个文件 `git diff --name-only` 零命中）；`scan.sh` 未动 |

## 2 逐条先证结论

### F-03 · P1 · `AgentLoopEngine.kt:1948` + `ContextOvershootRetry.kt:77`

**先证：成立，且比报告描述的更严重一层。**

1. 相对佐证臂确实失效。`ContextOvershootRetry.shouldShrinkAndRetry` 原第 77 行 `if (estimatedInput * 2 < window.toLong()) return false`，`window` 来自 `host.effectiveContextWindowTokens()` = `LLMModel.contextWindowTokens`（未知 id → 1M 启发式，`ChatViewModel.kt:2324`）。真实 128K 端点 + 100K 历史 ⇒ `100_000 * 2 < 1_000_000` ⇒ 不收缩，直接进 fallback 链。
2. 600K 那一档也确实空转：`trimContextHistoryWindow(1_000_000, 600_000)` 的预算 = `1_000_000 * 95/100 = 950_000`，`baseTokens(600_000) <= budget` ⇒ 函数首行即 `return`，一条不删 ⇒ `if (host.agentHistory.size >= before)` 命中 ⇒ 放弃 `continue`。
3. **报告未点出的第三层**：`val overshootRetry = ContextOvershootRetry()` 在 `turnLoop@` 循环体**内部**（原 `:1337`），而 `turnLoop@` 的迭代单位是**模型调用**不是用户消息（`runAgentLoop` 每次 send / resume / retryLast / 队列 drain 各进一次，`turnLoop@ for (turn in 0 until maxTurnsThisRun)` 在 `:898`）。所以 `finish=length` 续写 `continue` 进下一 turn ⇒ 新实例、`alreadyShrunk` 归零，且 `maxTokens` 在 turn 顶部被 preflight 重新放大 ⇒ 同一用户回合可反复 400/重试。KDoc 里 "one bounded shrink-and-retry per turn" 是过度承诺。
4. T7 事件缺口确认：`rollbackTurnBlocksTo` 三处（`:2013` 超限 / `:2186` 瞬态 / `:2409` fallback），只有超限分支 `continue` 前没有 `ProviderAttemptFinished` / `RetryRequested`；瞬态分支在 `:2121` / `:2219` 有。`AgentRunReducer` 要求 `CALLING_MODEL` 收到 `ProviderAttemptFinished` 才离开该相，因此超限重试后 reducer 停在 `CALLING_MODEL`，下一次 `ProviderAttemptStarted` 是同相自转移，trace 把两次设备请求记成一次。

**修法**：三条必做全做，第 4 条（续写轮重入）也做了。

- `shouldShrinkAndRetry` 改为**两臂取或**：相对臂（估算 ≥ 半窗）**或** 绝对臂（`estimatedInput >= ABSOLUTE_CORROBORATION_TOKENS = 32_000L`）。32K 写字面量并注明理由（主流小窗口模型下限；推导值会跟着漂移的常量一起动）。`isContextTooLarge` + `historyMessages > MIN_MESSAGES_TO_TRIM` 两道门原样保留，未放宽成"任何 400 都重试"。
- 窗口校准：新增 `parseReportedLimit(detail)`（6 条真实文案形态，含 "maximum context length is N" / "maximum prompt length is N" / "context window of N" / "context_window_exceeded: limit N" / "at most N tokens"，且**不**把 "at least N input tokens"（那是 400-teacher 的请求侧计数）当上限）与 `effectiveWindow(window, reportedLimit) = min(window, reportedLimit)`。**只作上限**：解析值大于当前窗口时忽略，因为窗口还带着用户 group `contextLimitTokens` 的钳制，不能被更宽松的端点抬高。超限分支的 `trimContextHistoryWindow` 与日志都用校准后的窗口。
- T7 事件：`continue` 前补 `ProviderAttemptFinished(TRANSIENT_FAILURE)` + `RetryRequested("context-overshoot")`，形状与瞬态分支逐字对齐（含前后 `t7State` 相位切换），未自造字段。
- latch 作用域：`overshootRetry` 提到 `turnLoop@` 之上（`runAgentLoop` 函数体作用域），删掉 turn 内的声明。KDoc 补一节说明「per user turn ≠ per model call」以及 `reset()` 的适用场景。

### F-05 · P1（本批自带的假保护）· `loop_persist_slice_check.py:351` + `LoopPersistSliceTest.kt:100`

**先证：成立。**

- 真树三处 rollback（`:2013` / `:2186` / `:2409`，用共享 `call_args` 全文件定位，非行正则）。
- 门规则 `if len(rollbacks) < 2:`，注释写「a path that silently disappears still trips this」——3 处删 1 剩 2 全绿，注释是假话。
- Kotlin 测试 `rollbackSites >= 2` 同样口径、同样注释。
- 测试对**未剥注释**的全文跑正则 ⇒ `// rollbackTurnBlocksTo(...)` 也计数。

**修法**：按角色点名，不按计数。

- 门新增 `ROLLBACK_ROLES = [("context-overshoot", ("overshootRetry",)), ("transient-retry", ("retryDelays",)), ("provider-fallback", ("FallbackSelected",))]` 与 `rollback_role(code, pos)`：从调用点向外走**封闭 `if` 块**（用共享 `if_blocks`），取最内层且条件或块体内含该角色标记的那一支。锚点是分支离不开的标识符（latch / 重试预算 / T7 事件），重写或移动代码时保留，删除路径时一起消失。无角色 ⇒ fail-closed。缺角色报 `missing rollback role: <name>`，不再报计数差。
- 剥注释已由共享 `code_view`（`mask_code`：注释 + 字符串内部清空）保证，门内未新写第二套。
- 注释已改为与实现一致（R2 规则描述同步）。
- 夹具新增两条，且都是旧规则**抓不到**的形态：
  - `rollback-role-deleted`（删掉超限那处，剩两处）⇒ 必须红且报 `missing rollback role 'context-overshoot'`；
  - `rollback-commented-out`（把一处注释掉）⇒ 必须红。
- `GOOD_ENGINE` 夹具同步改成三处各自在带角色标记的 `if` 内（否则合规夹具会被新规则判红），`BAD_ROLLBACK_SWITCHED` 相应改为全量替换。
- Kotlin 测试改为先 `stripComments` 再按角色断言（`rollbackRoles()`），断言三条角色集合逐字相等，且 `unclassified` 会直接让断言失败。

### F-07 · P1（测试假绿）· `SystemPromptSegmentOrderTest.kt:59`

**先证：成立，四条全部复现。**

1. `buildStringBody()` 用 `src.substring(blockStart)` 到文件末尾。`ChatPromptAndTools.kt` 2378 行，`buildSystemPrompt` 在 `:1334`、`executeSpawnAgentTool` 在 `:442`（**在其之前**）——但 `appendToolFailureBlock` / `executeShellCommand` 等在其后，且 `append(...)` 出现在多处。实测：真树 `buildStringBody()` 返回 **1964 字符**（配对后），旧实现返回 ~4 万字符。
2. 子代理断言只 `whole.contains("orderForSubagentContext")`，全文子串。真树该调用在 `:547`，函数是 `executeSpawnAgentTool`（`:442` 起）。
3. `stripComments` 保留字符串字面量（KDoc 声称两者都剥）——**KDoc 是假述**，实现是对的（锚点本身就是字面量）。
4. `theRollupSizeHintIsInTheVolatileTail` 只查 `"rollupSizeHint != null"`（是**守卫条件**，不是 append）；`theStableFragmentsStayInTheStandingRegion` 不约束 `globalMemoryFragment` 与 `rollupFragment` 的相对顺序。

**修法**：

- `buildStringBody()` 改括号配对（跳过字符串字面量），并加 fail-closed 断言：截出长度必须 `< 文件长度/2`，否则报「matcher 退回全文切片」。
- 新增 `braceBlock(src, from)` / `functionBodyOf(src, signature)` 两个共享 helper；子代理断言改为 `functionBodyOf(..., "internal suspend fun ChatViewModel.executeSpawnAgentTool(")` 内查 `orderForSubagentContext`，**并**要求该函数体内出现 `MemoryPromptOrder.Key.DAILY_MEMORY`（顺序 helper 只能保留喂进去的东西）。
- hint 断言改锚在**发射**上：`body.indexOf("Note: Daily logs are large")`，并额外要求前 60 字符内有 `append(`（防裸字符串）+ 后 120 字符内有 `\$rollupSizeHint`（防常量文案）。真树实测 `hint=-1`（旧锚点因 `append("\n\nNote: Daily logs are large (` 被换行/拼接打断而找不到），新锚点 `hint2=1620` 命中。
- 补同区段相对顺序断言：`globalMemoryFragment` < `rollupFragment`；`dailyMemoryFragment` < size hint（新测试 `theVolatileTailKeepsItsRelativeOrder`）。
- KDoc 改为「comments stripped, literals kept（锚点本身就是字面量）」，未改实现。

### F-08 · P1 · `ChatScreen.kt:247` + `LargeContentGuard.kt:276`

**先证（1）ChatScreen：死分支成立，且是"修在不可达分支上"。**

- `FlatChatItem.AssistantThinking` 的构造点全仓**只有一处**：`legacy/LegacyFlatChatBuilder.kt:174`（用共享 `code_view` 扫 main 树，注释/字符串已清空，排除注释误命中）。
- 该 builder 无生产调用点：`buildFlatChatItems` 的调用方只有 `legacy/StableChatRowLedger.kt`（`FROZEN_LEGACY_FILES` 之一，文件头写明 RUNTIME-DEAD），而 `StableChatRowLedger()` 在 main 树**零实例化**。
- `ChatScreen.kt:5159` 的 `[audit-0920]` 注释明确记录 `AGGREGATE_MESSAGE_ITEMS` 常量已被删除（`build.gradle.kts: 0 hits`，无 variant override），ledger 分支 174 行随之删除 ⇒ 聚合管线是唯一路径，`flatItems` 唯一赋值点 `:3268` 来自 `buildAggregateChatItemsIncremental`，该函数只产出 `UserBubble` + `AssistantMessageItem`。
- 真实渲染走 `AssistantMessageItem -> AssistantMessageView`，后者 `ChatAssistantMessageUI.kt:279` 自己解析 `message.thinkingLevel ?: ThinkingLevel.MEDIUM`，**未接收** `currentThinkingLevel`。

**修法（1）**：删掉 `:247` 的顶层 `collectAsState` 与分支内的读取；分支保留为**显式 no-op 臂**并在注释里写明为什么保留（`when` 对 sealed 类型穷尽，改成 `else` 会让新子类型静默不渲染）。**未**给 `AssistantMessageView` 接 `currentThinkingLevel`（那是新功能，不在本条半径内）。

**先证（2）LargeContentGuard：成立。**

`:233 wasDegraded = remember(stableKey) { mutableStateOf(false) }`；`:276 if (!isStreaming && wasDegraded.value) { :277 wasDegraded.value = false; :281 LaunchedEffect(...) { log } }`。组合期同步写 `MutableState` ⇒ 立即失效本作用域 ⇒ 下一次重组读 false ⇒ `if` 不再进 ⇒ 上一趟注册的 `LaunchedEffect` 在 body 运行前被 dispose ⇒ 日志可能永不输出；同时日志被推迟到 `renderer()` 之后。

**修法（2）**：复位搬进 effect——`if (!isStreaming && wasDegraded.value) { LaunchedEffect(stableKey, content.length) { wasDegraded.value = false; ...log... } }`。组合期只读。**为什么不用 `AtomicBoolean` 方案**：`MutableState` 的写仍然发生在 effect 里（Compose 允许，effect 在 composition 提交后运行），不会触发"写 state → 重组 → 取消 effect"这条链；effect 的 key 在 latch 仍为 true 时注册，写 false 只影响**下一次**组合是否再注册，不会取消当前 effect。保留了两条性质：fires once per (stableKey, content.length)、日志不依赖主线程 renderer 完成。

**自动化验证面**：本仓有 Compose instrumented 依赖（`androidTestImplementation("androidx.compose.ui:ui-test-junit4")`），但 **`androidTest` 不在 CI 里跑**（`unit-tests` job 是 `./gradlew test`）⇒ 加 instrumented 测试等于加一个永不执行的检查，正是本批要修的同一类假保护。改为新增 JVM 源码级测试 `LargeContentGuardStreamEndLatchTest`（2 例），钉住形状：effect 声明前不得有 `wasDegraded.value =` 写、effect body 内必须有复位与 `stream-end-swap` 日志、降级路径仍走 `LaunchedEffect`。已手工变异验证（见 §4）。

## 3 修法汇总（改了什么用户行为）

| 条 | 用户可见变化 |
|---|---|
| F-03 | 真窗口 128K 的端点在 100K 历史下会**自愈**（裁剪后重发同一回合），不再直接把整个 fallback 链走完；同一用户回合最多收缩一次（续写轮不再重复 400）；trace 里 provider 尝试数恢复真实值。 |
| F-05 | 无用户可见变化（门与测试）。防的是「回滚路径被删掉而无人发现」——那会让失败尝试的 partial 块活到重试回合，用户看到重复的工具块/半截文本。 |
| F-07 | 无用户可见变化（测试）。防的是提示词缓存顺序被悄悄改回去（每日记忆片段回到缓存前缀 ⇒ 每次 memory_write 全量 cache miss）。 |
| F-08 | 切换思考档位不再触发整个 ChatScreen + 所有列表项闭包重组（死分支上的 `collectAsState` 已移除）；流结束换渲染时 `[Perf][ContentDiag] stream-end-swap` 日志恢复可靠输出。 |

## 4 证据

### 4.1 F-03 纯 JVM 单测（真树源码，去掉 package 后独立编译）

```
$ kotlinc ContextOvershootRetry.kt ContextOvershootRetryTest.kt -d out
$ java org.junit.runner.JUnitCore ContextOvershootRetryTest
OK (20 tests)
```

**手工变异（两条，各自变红）**：

| 变异 | 结果 |
|---|---|
| `val absoluteArm = estimatedInput >= ABSOLUTE_CORROBORATION_TOKENS` → `= false` | `Failures: 3` — `aBigInputCorroboratesEvenWhenTheGuessedWindowSaysOtherwise` / `theAbsoluteFloorIsThirtyTwoThousandTokens` / `theLatchStillWinsOverTheAbsoluteArm` 全红 |
| `effectiveWindow` 加 `if (true) return window` | `Failures: 1` — `aReportedLimitCapsTheGuessedWindow` 红 |

核心回归锚点：`(true, 100_000, 1_000_000, 20)` 现在返回 **true**（改动前为 false），`(true, 10_000, 1_000_000, 20)` 仍 false，`(true, 499_999, 1_000_000, 40)` 因绝对臂**现在为 true**（旧断言 `assertFalse(499_999)` 已按新语义改写为 `20_000`，避免把「绝对臂生效」误判成回归）。

### 4.2 F-05 门 selftest + 真树

```
$ python3 scripts/scan/loop_persist_slice_check.py --selftest
  violating fixtures red: 29 / 29 ; clean fixtures: 3
LOOP_PERSIST_SLICE_SELFTEST=PASS (29 violating + 3 clean arms)
$ python3 scripts/scan/loop_persist_slice_check.py .
PASS loop_persist_slice_check (...)
rc=0
```

（夹具 27 → 29，新增 `rollback-role-deleted` / `rollback-commented-out`；`test_scan.py` 的 `DA1016_GATES` 标记字符串是**旧六夹具句**且仍逐字命中，故 `test_scan.py` 未改动——实测 `marker_present True`。）

**角色识别探针**（Kotlin，直接复用测试里的 `rollbackRoles`/`stripComments` 真代码，对真树跑）：

```
$ java HarnessKt AgentLoopEngine.kt
roles=[context-overshoot, provider-fallback, transient-retry]

# 变异 1：注释掉超限那处
roles=[provider-fallback, transient-retry]        ← 少了 context-overshoot ⇒ 断言红
# 变异 2：删掉 fallback 那处
roles=[context-overshoot, transient-retry]        ← 少了 provider-fallback ⇒ 断言红
```

### 4.3 F-07 单测 + 五个手工变异

```
$ java org.junit.runner.JUnitCore SystemPromptSegmentOrderTest   （真树）
OK (6 tests)
```

| 变异（对真树 `ChatPromptAndTools.kt` 做，测试跑在变异树上） | 变红的测试 |
|---|---|
| M1 交换 `globalMemoryFragment` / `rollupFragment` 两段 | `theStableFragmentsStayInTheStandingRegion` |
| M2 去掉 hint 的 `$rollupSizeHint` 插值 | `theRollupSizeHintIsInTheVolatileTail` + `theVolatileTailKeepsItsRelativeOrder` |
| M3 删掉 `.let(MemoryPromptOrder::orderForSubagentContext)` | `theSubagentContextStillCarriesTheDailyFragment` |
| M4 把 daily 片段移到 `Runtime context:` 之上 | `theDailyMemoryFragmentIsInTheVolatileTail` |
| M5 从子代理列表删掉 `DAILY_MEMORY` | `theSubagentContextStillCarriesTheDailyFragment` |

未变异（orig）基线：`OK (6 tests)`。**每条断言都能红**，这是本条的核心交付。

### 4.4 F-08 新增测试 + 两个手工变异

```
$ java org.junit.runner.JUnitCore LargeContentGuardStreamEndLatchTest   （真树）
OK (2 tests)
```

| 变异（对真树 `LargeContentGuard.kt` 做） | 结果 |
|---|---|
| M1 把 `wasDegraded.value = false` 移回 `LaunchedEffect` 之前（复现缺陷） | `the stream-end latch is cleared inside the effect, not in composition` 红 |
| M2 从 effect body 删掉复位 | 同一测试红 |

### 4.5 邻门（本地窄验证，未跑全量）

`thinking_wiring_guard` / `chat_ui_interaction_check` / `legacy_pipeline_guard` / `loop_persist_slice_check` / `truncated_call_guard_check` / `provider_remember_key_check` / `turn_tail_finalize_order` / `stale_closure_guard` / `loop_tool_status_transition_check` / `session_sheet_id_binding_check` / `deeplink_settings_routes_check` / `chat_session_id_binding_check` / `file_uri_parse_guard_check` / `file_provider_paths_coverage_check` —— **14 门全部 rc=0**（按「门脚本里被改到的锚点文件/符号」挑选：`AgentLoopEngine.kt` / `ChatScreen.kt` / `LargeContentGuard.kt` 的全部引用门）。

括号/注释配平用共享 lexer `_kotlin_lexer.analyse` 逐个校验 7 个改动/新增 .kt：全部 `OK`（这层检查是 11-13 `enum_parse_safety_check` 抓到我编辑引入不平衡那次之后固定加进流程的）。

### 4.6 CI

见 §5。

## 5 CI

| 项 | 值 |
|---|---|
| run | `43443967050`（Build RikkaMinis Android APK） |
| head_sha | `439dd415d073fabf2ea605e65c39ae31afdb6b8a` |
| 与分支 tip | **逐字符一致** |
| 结果 | 见下（dispatch 后状态） |

## 6 残留风险

1. **F-03 的窗口校准依赖错误文案形态。** `parseReportedLimit` 覆盖 6 种真实见过的写法，解析不出就回落 `windowNow`（不放宽、不缩小）。若某端点用完全不同的措辞（例如只回 "input too long" 不带数字），校准不生效——但绝对佐证臂仍然让自愈可以触发，只是 trim 预算仍按猜的窗口算，可能「trim 不出东西」而放弃。这是设计上的降级路径，不是遗漏。
2. **F-03 的 latch 作用域改了但 `reset()` 无生产调用者。** 当前 `runAgentLoop` 每次用户消息新建一个实例，所以不需要 reset。若未来有人把实例提到 `ChatViewModel` 字段，必须自己调 `reset()`——KDoc 已写明。
3. **F-08 的 `AssistantThinking` 分支保留为 no-op 而非删除。** 若 legacy 管线真的复活（`AGGREGATE_MESSAGE_ITEMS` 恢复），这里会静默不渲染思考块。注释已写明「这是唯一要回填的地方」，且 `legacy_pipeline_guard` 对 legacy 包的符号表是双向冻结的（新增/减少符号都红），复活不会无声发生。
4. **F-08 的 LargeContentGuard 修复无真机验证。** 只有源码级测试 + 变异负控。真机证据需要「流式降级 → 流结束」的一条 `[Perf][ContentDiag] stream-end-swap` 日志；未在本批采集（需要长回复触发 `STREAM_DEGRADE_CHARS`）。
5. **`androidTest` 不在 CI 中执行** 是本批发现的基础设施缺口（F-08 因此改用源码级测试）。这本身值得一条 backlog，但不在本支半径内——已在本报告 §2 F-08 记录判据，交主会话决定是否登记。

## 7 复现

```sh
cd /var/minis/shared/work/incr1113-b/repo     # 分支 fix/incr1113-loop @ 439dd41

# F-03
mkdir -p /tmp/r && cd /tmp/r \
  && cp .../ui/chat/ContextOvershootRetry.kt .../test/.../ContextOvershootRetryTest.kt . \
  && sed -i 's/^package .*//' *.kt \
  && kotlinc -cp <stdlib>:<junit> *.kt -d out \
  && java -cp out:<stdlib>:<junit>:<hamcrest> org.junit.runner.JUnitCore ContextOvershootRetryTest

# F-05
python3 scripts/scan/loop_persist_slice_check.py --selftest
python3 scripts/scan/loop_persist_slice_check.py .

# F-07 / F-08（源码级测试，工作目录必须是仓库根）
java -cp <test-out>:<jars> org.junit.runner.JUnitCore SystemPromptSegmentOrderTest
java -cp <test-out>:<jars> org.junit.runner.JUnitCore LargeContentGuardStreamEndLatchTest
```

变异复现：把对应断言依赖的那一处按 §4 表格改回缺陷形态，重跑同一个测试类，对应方法必须变红。

## 8 未修登记

**本支 0 条登记不修**（4/4 全部动手）。

顺带发现、**未动手**、建议主会话登记 backlog 的两项（半径纪律：不在本条触发路径上）：

1. **`androidTest` 不在 CI 执行** —— `unit-tests` job 只跑 `./gradlew test`，`src/androidTest/**` 的 40+ 个 Compose/仪器测试从不运行。影响：任何以 instrumented test 形式存在的保护都是装饰。触发条件：需要一条 UI 行为证据时；方案：CI 加 emulator job（成本高）或把可源码化的断言迁到 JVM（本支 F-08 采用的做法）。
2. **`legacy/LegacyFlatChatBuilder.kt` 的 `AssistantThinking` 构造点** —— 该行是死代码，但删除它会改动 `FROZEN_LEGACY_SYMBOLS` 的冻结契约（`legacy_pipeline_guard` 双向检查），属于"dead module 的整体退役"决策，不是本条的半径。
