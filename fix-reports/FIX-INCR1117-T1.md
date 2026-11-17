# FIX-INCR1117-T1 — spawn_agent 派发生命周期（A-F01 / F02 / F03 / F04 / F06 / F07）

基线 main `949566ac` · 分支 `fix/spawn-dispatch-lifecycle-1117` · worktree `incr1117/wt1`
停止节点 = **分支 CI 绿**（不合并 main；合并起手权在用户）
来源：`meta-audit/reports/incr1117/Incr_1117_A_Report.md`（A 包）· 判级：`incr1117/TRIAGE-incr1117.md`

---

## 1 覆盖项

六条全部**先证成立**，无一条证伪。

| ID | 报告位置 | 先证结论 | 修法一句话 |
|---|---|---|---|
| A-F01 | `ChatPromptAndTools.kt:562`（基线） | 确认 | 读取侧改走 `SubagentSkill.replyText`（先 `isNull` 再 `optString`），wire 形状不动 |
| A-F02 | `:517-552` | 确认 | 把「我们写进去的 pin 值」搬进 `SubagentDispatchPin`，与深度计数同住 |
| A-F03 | `:542-551` | 确认 | 还原动作包进 `withContext(NonCancellable)`，仍在 `finally` 内 |
| A-F04 | `:532-535` / `HeadlessChatRunner.kt:502,560` | 确认 | 等待段捕获取消 → `NonCancellable { vm.cancelStream() }` → 重抛 |
| A-F06 | `:356, 464-481` | 确认 | 函数入口加 `SubagentPrefs.isEnabled(context)` 硬门 |
| A-F07 | `:481-505` | 确认 | 等待秒数取 `min(剩余预算, 900)`，下限 1s，抽成纯函数 |

---

## 2 逐条：先证 → 修法 → 证据

### A-F01 · `optString` 把 `JSONObject.NULL` 读成字面 `"null"`

**先证（回源真码）**
- 写侧：`debug/ChatMutationMethods.kt:148`（基线）`put("responseText", result.responseText ?: JSONObject.NULL)` —— 空回答/无原因拒付写的是**真 JSON null**。
- 读侧（修前）：`executeSpawnAgentTool` 尾 `text = result.optString("responseText", "")`。
- 两库语义分歧：JSON-java 的 `optString(name, fallback)` 对 `JSONObject.NULL` 返回 fallback；Android 的返回 `JSON.toString(NULL)` = 字面 `"null"`。工具跑在 Android ⇒ `SubagentSkill.resultOf` 的 `text.ifBlank {}` **是死代码**（`"null"` 非空白）。
- 仓内惯例佐证：`debug/*`、`backup/OpenMinisBackupCompat.kt`、`ChatMutationMethods.kt:116-117` 等处一律 `if (!params.isNull(k)) params.optString(...)`，即本仓已知「JSON null 不能直接 optString」。
- **不改 wire 形状**：`minis-sessions-cli send` 与 scheduled-task 路径消费 `null`。

**修法**：`tools/SubagentSkill.kt:142` 新增 `replyText(reply, key = "responseText")` = `if (reply.isNull(key)) "" else reply.optString(key, "")`（`isNull` 在两库上语义一致）；`ChatPromptAndTools.kt:603` 调用它。

**证据**
- 纯测试 `SubagentSkillTest`：`a json null responseText reads as empty`、`replyText returns real text and treats an absent field as empty`、`an empty reply maps to the no-text envelope, never to the word null`、`a refusal with a json null reason says no reason given`。
- 接线测试 `SubagentBudgetGateTest.a json null reply is resolved before it becomes the answer text`（断言不再出现 `optString("responseText"`）。
- 变异臂 `m1_reply_via_optstring` → 该接线测试红。
- **诚实边界**：JVM 侧 json-20231013 对 NULL 返回 fallback，**纯测试无法复现 Android 的 `"null"`**（任务书 §3.5 已点名）。因此 `replyText` 的纯测试钉的是「契约形状」（null→空），Android 侧的差异由 `isNull` 这一个两库同义 API 消除，并由接线测试锁死「不许回退到 optString」。

### A-F02 · 并发派发时最后一次 `end` 没有 `ourPin` ⇒ 父会话永久置顶

**先证（回源真码）**：修前 `var ourPin: Long? = null` 是 `executeSpawnAgentTool` 的**局部变量**，只有 `depth == 1` 的那次派发赋值；`finally` 里 `end(...) == 0 && ourPin != null` 才算还原。两条重叠派发：A 拿到 depth 1（ourPin 有值），B 拿到 depth 2（ourPin 为 null）。若 A 先结束 → `end()` 返 1 → 跳过；B 后结束 → `end()` 返 0 但 `ourPin == null` → **跳过还原**，`pinSession(false)` 永不执行。

**修法**
- `data/SubagentDispatchPin.kt:67` `noteWrittenPin(sessionId, pinnedAt)` + `:77` `takeWrittenPin(sessionId): Long?`（读后即清），值存进 `writtenPins` 与 `depths` 同住；`clear()` 一并清（`SubagentDispatchPinTest.clear forgets every session` 断言不残留）。
- `ChatPromptAndTools.kt:546` 写完 pin 立刻记账；`ChatPromptAndTools.kt:573` 起的 `finally` 在 `end()==0` 时 `takeWrittenPin` 取值还原 —— 谁最后结束都能拿到**这个窗口真正写过的值**。
- `end` 语义保持「返回剩余深度」不变。

**证据**：`SubagentDispatchPinTest.the last end can undo a pin written by another dispatch`（深度 2 场景：第一次 end 不消费、第二次 end 取值 111、再取为 null ⇒ 不复活）；`a window that never pinned records nothing`；接线测试 `the pin value is restored from the shared window record, not a local`；变异臂 `m2_restore_reads_nothing`（`takeWrittenPin` → `null`）→ 该纯测试红。

### A-F03 · 停止后还原被 `runCatching` 吞掉 ⇒ 永久置顶

**先证（回源真码）**：修前 `finally { if (...) { runCatching { chatRepository.getSession(...)?.pinnedAt == ourPin; chatRepository.pinSession(..., false) } } }` —— `getSession` / `pinSession` 都是 **suspend（Room）**（`ChatRepository.kt:226`）。用户点停止 ⇒ 协程已 cancel ⇒ 进入 `runCatching` 的**第一个挂起点**立刻抛 `CancellationException` ⇒ 被 `runCatching` 吞掉 ⇒ 还原根本没发生。

**修法**：`ChatPromptAndTools.kt:573` 把整个还原段包进 `withContext(NonCancellable) { … }`，仍在 `finally` 内、仍只用既有 `chatRepository`（**没有** `GlobalScope` / 新 `Job`）。门锚点 D5（`} finally {` 先于 `SubagentDispatchPin.end(`）自然保持。

**证据**：接线测试 `the pin restore survives a cancelled coroutine`（`withContext(NonCancellable)` 必须在 finally 之后、`SubagentDispatchPin.end(` 必须在它之后）；变异臂 `m3_restore_cancellable`（把 `withContext(NonCancellable)` 换成 `run`）→ 该测试红。

### A-F04 · 停止父会话只解除等待，子会话继续跑工具/烧 token

**先证（回源真码）**：`HeadlessChatRunner.prompt` 的等待是 `withTimeoutOrNull(timeoutMs) { vm.isStreaming.first { !it } }`（基线 `:560-566`）；子会话的 turn 由它**自己的** `viewModelScope` 驱动，等待段没有任何 child cancel 路径。`ChatMutationMethods.prompt` 只是把结果映射回 JSON。⇒ 父 run 被 stop 时取消的是等待它的协程，子 turn 继续。

**修法**：`HeadlessChatRunner.kt:570` 起把等待包进 `try { … } catch (ce: CancellationException) { withContext(NonCancellable) { runCatching { vm.cancelStream() } }; throw ce }`。
- **只对取消**：`withTimeoutOrNull` 自己的超时返回 null ⇒ `finished=false` ⇒ 不 cancel，保留「子会话可能还在跑」的既有语义（`resultOf` 的超时文案就是这么写的）。
- KDoc 同步写明（`HeadlessChatRunner.kt:402`）：调用方被取消 = 这一轮也停。
- **`ScheduledTaskRunner` 不受影响**：`onTasksRemoved`（`:157-192`）只做簿记清理（`dispatched` map + `AgentRunBudgetCeilings.clear`），**从不取消在飞的 `fire()` 协程** —— 复核过代码路径。

**证据**：接线测试 `SubagentBudgetGateTest.a cancelled caller stops the turn it started`（`val finished = try {` → `withContext(NonCancellable)` → `vm.cancelStream()` → `throw ce` 顺序）；变异臂 `m6_no_child_cancel` → 该测试红。

### A-F06 · 执行侧不校验 `SubagentPrefs`（关掉开关仍能开新会话）

**先证（回源真码）**：`SubagentPrefs.isEnabled(context)` 只在 `ChatPromptAndTools.kt:1440`（构造 routing 提示词）与 schema 构造处读；`executeTool` 的 `SubagentSkill.NAME -> executeSpawnAgentTool(argsJson)`（`:356`）不看开关。⇒ 重放的历史 `spawn_agent` 调用、或手工构造的调用，在用户关掉开关后仍能开新会话。
- 对照：记忆工具是双门控（`ChatToolExecutors.kt:218-227` 的 `memoryToolDisabledMessage` 在执行侧；`_memoryEnabled` 在提示词侧）。

**修法**：`ChatPromptAndTools.kt:477` 函数入口（args 解析后、任何副作用前）加门，关闭即 `ToolExecutionResult("Error: sub-agent dispatch is disabled in Settings (runtime.subagentEnabled). The user can enable it in Settings → Agent Runtime.", false, toolTitle = title)` —— 文案沿用既有拒付风格（点名开关 + 给出开启路径）。

**证据**：接线测试 `the dispatch switch is checked at execution, not only in the schema`（门必须在 `ChatMutationMethods.prompt(` 之前）；变异臂 `m5_no_pref_gate`（`if (false)`）→ 该测试红。

### A-F07 · 等待不随剩余预算收缩（5s 预算可阻塞 900s）

**先证（回源真码）**：派发前只查一次 `traceObserver.activeRunBudget?.isExpired()`；`params.put("waitTimeout", SubagentSkill.WAIT_TIMEOUT_SECONDS)` 恒 900。预算 deadline 只在 turn 边界被检查，不约束工具内等待 ⇒ 剩余 5s 的 run 可以在工具里阻塞 900s 后才发现超期。

**修法**：`SubagentSkill.kt:63` 新增纯函数 `effectiveWaitSeconds(budgetRemainingMs: Long?, capSeconds: Int)`：`null` → cap；否则 `floor(剩余ms/1000)` 先 `coerceAtMost(cap)` 再取 `max(MIN_WAIT_SECONDS=1)`、最后 `min(cap)`。`ChatPromptAndTools.kt:522` 用 `traceObserver.activeRunBudget?.remaining()?.millisRemaining` 取值（`RemainingBudget.millisRemaining` 已存在，未改 `agent/runtime/**`），同一个 `waitSeconds` 也传给 `resultOf(timeoutSeconds = …)`，让超时文案说的就是真正等过的时间。

**证据**：`SubagentSkillTest` 六条（null→cap / 健康预算不缩 / 30s→30 / 5999ms→5 / 0、999ms、负值→1 / `Long.MAX_VALUE` 不溢出 / cap=0 仍为 0）；接线测试 `the wait is capped by the run budget, not only by the fixed constant`；变异臂 `m4_wait_ignores_budget` → `the wait shrinks…` + `a sub-second remainder…` 双红。

---

## 3 门与测试输出（本地窄验证，全量交 CI）

```
$ python3 scripts/scan/subagent_dispatch_check.py .
OK: sub-agent dispatch wiring holds (6 contracts)            rc=0
$ python3 scripts/scan/subagent_dispatch_check.py --selftest
SELFTEST arm CANCEL_SWALLOWED   red: cancel-not-rethrown
SELFTEST arm FIRE_AND_FORGET    red: dispatch-not-synchronous
SELFTEST arm GATE_AFTER_CALL    red: budget-deadline-after-call
SELFTEST arm HANDWRITTEN        red: result-map-not-delegated
SELFTEST arm NO_CALL            red: dispatch-call-missing
SELFTEST arm NO_CANCEL_ARM      red: cancel-catch-missing
SELFTEST arm NO_PIN             red: dispatch-pin-missing
SELFTEST arm PIN_LEAK           red: dispatch-unpin-outside-finally
SELFTEST arm UNBOUNDED          red: dispatch-wait-unbounded
SELFTEST OK                                                  rc=0
$ python3 scripts/scan/truncated_call_guard_check.py .      rc=0   （邻门）
```

**门脚本 0 改动**（`git diff 949566ac..HEAD -- scripts/` 为空）。

JVM（单次 kotlinc 编译，装置 `shared/work/incr1117/t1-jvm/run.sh`）：

```
JUnit version 4.13.2
............................................
Time: 0.434
OK (44 tests)          # SubagentSkillTest 22 / SubagentDispatchPinTest 9 / SubagentBudgetGateTest 13
```

**六条变异负控**（`t1-jvm/mutate.py`，每条只改一个生产文件、跑完 `git checkout -- <单文件>` 复原）：

```
baseline green: True
ARM m1_reply_via_optstring       red: a json null reply is resolved before it becomes the answer text
ARM m2_restore_reads_nothing     red: the last end can undo a pin written by another dispatch
ARM m3_restore_cancellable       red: the pin restore survives a cancelled coroutine
ARM m4_wait_ignores_budget       red: the wait shrinks to what the deadline has left | a sub-second remainder still waits a real second
ARM m5_no_pref_gate              red: the dispatch switch is checked at execution, not only in the schema
ARM m6_no_child_cancel           red: a cancelled caller stops the turn it started
```

**改写后的两个 Android 文件**（`ChatPromptAndTools.kt` / `HeadlessChatRunner.kt`）沙箱内无法整链编译（无 Android SDK），做了单文件 kotlinc 检查：**语法类错误 0 条**（`expecting|unexpected|syntax` 匹配数 0），其余错误全是缺 Android 符号引起的 `unresolved reference / cannot infer type`。括号配平由 44 条测试（含读这两个文件源码的 6 条接线断言）间接兜底 —— 真编译验证交 CI。

---

## 4 CI

- run **#3200** / id `44004230377` · workflow `build-apk.yml` · ref `fix/spawn-dispatch-lifecycle-1117`
- `head_sha` = `9b9304ba6c92921c9f42c4ba97dada8f126b4608` = 本地 HEAD **逐字符一致**
- 结论：**五 job 全 success**（`build-apk` / `scan-gate` / `lint` / `unit-tests` / `publish`），run `conclusion == success`

### 4.1 逐条证据（引用前逐条搜到的那一行）

| 证据 | 实际输出 |
|---|---|
| run 级 | `3200 9b9304ba6c92921c9f42c4ba97dada8f126b4608 completed success` |
| 单测总量 | `BUILD SUCCESSFUL`；日志内 `PASSED` 行 **9316** 条；`FAILED` 行 4 条**全是用例名**含 FAILED（`slot denied produces FAILED…` / `phase FAILED maps to Failed` / `terminal FAILED maps to Failed` / `…reaches a legal FAILED terminal`），均 `PASSED` ⇒ 真实失败 0 |
| 本批 13 条新/改测试 | 逐条 `PASSED`（`SubagentBudgetGateTest`: the wait is capped by the run budget / a json null reply is resolved / the pin restore survives a cancelled coroutine / the dispatch switch is checked at execution / the pin value is restored from the shared / a cancelled caller stops the turn；`SubagentSkillTest`: 4 条 reply 读取 + 6 条 wait 预算；`SubagentDispatchPinTest`: 2 条） |
| 门 | `OK: sub-agent dispatch wiring holds (6 contracts)`；`scan-gate base=949566ac… head=9b9304ba…`（基线核对一致）；`Summary: 115 passed, 0 failed` |
| 邻门 | 同一 gate 链里 `truncated_call_guard_check` 仍绿（115 门全绿的一部分） |
| **夹具（诚实边界）** | 本批未改 `scripts/scan/**` ⇒ 日志原文 `scripts/scan/** untouched in this push — skipping the fixture self-test`：**本次「CI 绿」不包含「门的变异负控跑过」这一维**。该维由本地 `--selftest`（9 臂全红，见 §3）与六条源码级变异臂补上 |
| 发布 | `publish` job 内 `Move the rolling tag android-latest` / `Publish to Releases` 均 **skipped**（`github.ref != main` 门控生效）⇒ 分支构建**没有**污染 release 资产 |
| 编译 | `build-apk` success ⇒ 两个只改了源码的 Android 文件（`ChatPromptAndTools.kt` / `HeadlessChatRunner.kt`）真编译通过 |

## 5 残留

1. **A-F04 的 RPC 语义扩展（行为变化，已声明）**：`HeadlessChatRunner.prompt` 也被 `ChatMutationMethods.prompt` 的 RPC 入口（debug `chat.prompt`、`minis-sessions-cli send`）复用 ⇒ 现在**调用方协程被取消也会停掉该轮**（此前会继续跑完）。这是同一条契约的两面：`send --wait` 的客户端断开后没人等答案，继续烧 token 无收益。`retry()` / `rerunFromToolBlock()` 的等待段**未**加同样处理（不在本次范围；它们没有「派发子会话」这一语义，记录于 §7）。
2. **A-F01 的 Android 侧无法本地取证**：纯测试在 JSON-java 上跑，`optString` 对 NULL 返回 fallback，与 Android 相反。本支靠「只用 `isNull`（两库同义）+ 接线测试禁止回退 optString」保证，没有真机读数。若以后要真机取证：在空回答派发后看父会话的 tool 结果文本里是否还出现 `null`。
3. **A-F07 的 1s 下限**：剩余预算 <1s 时仍会等 1s（保证走正常超时信封而不是新增「0 秒」形态）。派发前已经有一次 `isExpired()` 门，所以「剩余 0」的窗口极窄。
4. **置顶还原仍是 best-effort**：`runCatching` 保留（DB 异常不该让工具结果变成异常），只是不再被取消吞掉。

## 6 复现指令

```sh
# 门（0 改动，必须仍 rc=0）
cd <worktree> && python3 scripts/scan/subagent_dispatch_check.py .
python3 scripts/scan/subagent_dispatch_check.py --selftest
python3 scripts/scan/truncated_call_guard_check.py .

# 本域 JVM（44 条）
sh /var/minis/shared/work/incr1117/t1-jvm/run.sh

# 变异负控（六条，跑完自动复原单文件）
python3 /var/minis/shared/work/incr1117/t1-jvm/mutate.py
```

## 7 未修项（登记，不动手）

| 项 | 为什么不动 |
|---|---|
| `HeadlessChatRunner.retry()` / `rerunFromToolBlock()` 的等待段同样只在 caller 取消时断开等待 | 本次触发路径是「派发子会话后点停止」，只在 `prompt` 上；对 retry 来说「客户端断开但这一轮继续跑完」可能是更合理的行为，需要用户拍板再改（同族，非本次范围） |
| `ChatMutationMethods.prompt` 的 wire 形状（`responseText: null`） | CLI / scheduled 消费方依赖它，只修读者侧 |
| `AgentTools.kt` 子会话递归/深度门（A-F05） | 别的支 / 已登记 P2 |
| `AppendOnlyWriteGuard`、`ChatViewModel` | 本轮明确范围外 |
| `subagent_dispatch_check.py` 的 `mask_comments`+子串弱点（A-F10） | 门是冻结面，本支不许改；已登记 |

## 8 行为变化声明（用户可感知的部分）

1. **空回答/拒付不再显示 `null`**：派发子代理后，若子会话没产出文本，父模型读到的是 `(sub-agent finished without producing text)`；拒付时读到 `… no reason given` / 真实原因，而不是 `null`。
2. **停止 = 子会话也停**：在等待子代理的会话上点停止，子会话那一轮同时停（不再后台继续调工具）。
3. **并发派发后父会话回到原来的置顶状态**：两条重叠派发、先结束的那条不再让父会话永久置顶。
4. **停止后也能还原置顶**：点停止不再把父会话永久留在置顶区。
5. **关掉开关就真的没有**：`runtime.subagentEnabled` 关闭时，即使有残留/重放的 `spawn_agent` 调用，也不会开新会话（返回显式拒付文案）。
6. **预算不足时派发等待同比例缩短**：run 剩余 30s 时派发最多等 30s，超时文案随之说 30s。
7. **（RPC 侧，见 §5.1）调用方协程被取消时，该轮也会停**。
