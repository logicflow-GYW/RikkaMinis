# FIX-FW-DEAD-SHUTDOWN-LEVER — 内存压力→回收 :modelservice 的杠杆是死环（L0）

**分支** `fix/fw-dead-shutdown-lever` ｜ **基线** main `039c9ab2` ｜ **来源** LOOP-1030 闭环审计 R2（`shared/work/loop-audit-1030/reports/R2-worker.md`，W5 判 L0「建议删」）

**结论一句话**：这条杠杆的**唯一能达成的效果**（quiescent worker 自杀）早已被既有的 quiescence 逻辑无条件达成 ⇒ 它写不写世界状态都一样。所以**删干净**，而不是接进 drain 语义。

---

## §1 调用图取证（我自己复核过，不是照抄任务书）

基线 `039c9ab2` 上的**全部**出现点（`git grep -nE "writeShutdownRequest|shutdownRequested|FILE_SHUTDOWN|SHUTDOWN_MARKER_STALE_MS|cleanupStaleShutdownMarker|maybeReclaimModelService" 039c9ab2 -- src/ scripts/ assets/`，共 36 条）：

### 生产代码（`src/android/app/src/main/**`）

| # | 位置 | 是什么 | 判定 |
|---|---|---|---|
| 1 | `ExecutionCoordinator.kt:1155` | `maybeReclaimModelService(nativeNowMb = nativeNow)` 调用 | **写者入口**（GC 之后那一步） |
| 2 | `ExecutionCoordinator.kt:1173` | `maybeReclaimModelService` 定义 | **写者** |
| 3 | `ExecutionCoordinator.kt:1196` | `ModelExecutionMailbox.writeShutdownRequest(root)` | **全仓唯一写者** |
| 4 | `ExecutionCoordinator.kt:1301` | `cleanupStaleShutdownMarker(ctx)` | 清理者（删文件，非读者） |
| 5–9 | `ExecutionCoordinator.kt:1306/1308/1315/1320/1324/1328/1344` | 清理函数的 KDoc / 函数体 / `FILE_SHUTDOWN` 引用 / `SHUTDOWN_MARKER_STALE_MS` | 清理者 + 自指（注释与常量） |
| 10 | `ChatStreamOffloadHandler.kt:78` | KDoc：`Read by … maybeReclaimModelService` | 自指（注释） |
| 11 | `ChatStreamOffloadHandler.kt:116` | 注释：`maybeReclaimModelService skips …` | 自指（注释） |
| 12 | `ModelExecutionLifecycle.kt:28` | KDoc：`[shutdownRequested] is the main process's EXPLICIT drain request` | 自指（注释） |
| 13 | `ModelExecutionLifecycle.kt:87` | `transition(…, shutdownRequested: Boolean)` **形参** | **自指**：函数体一次都没读它（见下） |
| 14 | `ModelExecutionMailbox.kt:55` | `const val FILE_SHUTDOWN = "shutdown"` | 文件名常量 |
| 15 | `ModelExecutionMailbox.kt:117–118` | `writeShutdownRequest(dir)` | 写者 API |
| 16 | `ModelExecutionMailbox.kt:122–123` | `shutdownRequested(dir) = File(dir, FILE_SHUTDOWN).exists()` | 读者 API（唯一的"读文件"原语） |
| 17 | `ModelExecutionService.kt:554` | 传 `shutdownRequested = false`（dirMissing 分支） | 硬编码假值 |
| 18 | `ModelExecutionService.kt:567` | `val shutdownRequested = shutdownRequested()` | **唯一真实读点**（每次 request finish 读一次文件） |
| 19 | `ModelExecutionService.kt:571` | 把它传给 `transition(...)` | 被忽略（见 #13） |
| 20 | `ModelExecutionService.kt:590` | `"… shutdownRequested=$shutdownRequested pid=…"` | **自指：只进日志** |
| 21 | `ModelExecutionService.kt:658–660` | 私有 `fun shutdownRequested()` 包装 | 读者包装层 |

**判定口径**（任务书要求）：有没有一处代码**根据这个值改变行为**。

- #18→#19：值确实被读出来，但 `transition()` 的 `when` 只有 `current` + `isQuiescent(quiescence)` 两个输入，`shutdownRequested` 在函数体里出现 **0 次**（原文自陈 "does not change the transition outcome today"）。
- #18→#20：第二个出口是日志字符串。
- #4/#7–#9 是**清理者**：它们只在标记文件存在时删它，不因它改变任何 worker/主进程行为。

⇒ **一枚"读文件 → 传参 → 参数被丢弃"的完整链路，行为侧消费者 = 0。**

### 测试代码（`src/android/app/src/test/**`，15 处 + 1 条往返测）

- `ModelExecutionLifecycleTest.kt` ×10：每条 `transition(...)` 调用都带 `shutdownRequested = false/true`，而**同一个测试同时断言这两个值给出同一结果**（`idle worker proceeds to stopping regardless of shutdown request`）——测试自己就是"这个参数是被忽略的"的书面证据。
- `ProviderExecConcurrencyTest.kt` ×4：全部 `shutdownRequested = false`。
- `ModelExecutionMailboxTest.kt:63–65`：`shutdown request marker` 往返测（写标 → 读标为 true），只测协议原语，不测任何行为。

### 其它位置

`scripts/**`、`assets/**`、`src/android/app/src/main/assets/**` 命中 **0**；`docs/dev-history/**` 有 3 条（历史档案，属历史记录，不改，见 §5 R7）。

### 真机日志取证（我自己 `grep -c` 复核）

| 文件 | 行数 | `shutdown REQUESTED` | `direction-A` |
|---|---|---|---|
| `minis-2026-10-28.log` | 198,372 | **0** | 0 |
| `minis-2026-10-29.log` | 272,055 | **0** | 0 |
| `minis-2026-10-30.log` | 39,046 | **0** | 4（**全是当天我自己工具调用的回显**，逐条核过：`D/ToolChain[…] ToolCallComplete … name=shell_execute/file_edit`） |

worker 侧 `request finished (holding)` 行一共 **7,825** 条，其中 `shutdownRequested=false` **7,825** 条、`=true` **1** 条 —— 那 1 条也是我自己命令的回显（`10-30 12:25:02.626 … D/ToolChain[Provider](25259)`，命令串里带了这个词）。**真实 `true` = 0。**

也就是说：**三天里这个标记要么从未被写、要么写了零消费**。任务书的判断成立，我的复核没有证伪它。

---

## §2 选择：删（不是接进 drain 语义）

### 删掉的完整链路（9 个文件）

生产（5）：
1. `ExecutionCoordinator.kt`：`maybeReclaimModelService` 整个函数 + 它的调用点 + `cleanupStaleShutdownMarker` + `SHUTDOWN_MARKER_STALE_MS` + 三条随删除失效的 import（`SuppressLint` / `ChatStreamOffloadHandler` / `ModelExecutionMailbox`）。**保留** `postRecycleMemoryRecoveryNow` 的整段多轮 GC（那是唯一有真 EFFECT 的记忆动作面：回读 native before/after 并归零，不是"请求别人去做"）+ 未动 `recycleIdleShells`。
2. `ModelExecutionLifecycle.kt`：`transition` 的 `shutdownRequested` 形参 + KDoc 段（改成"没有这条杠杆"的说明 + 复活指引）。
3. `ModelExecutionMailbox.kt`：`FILE_SHUTDOWN` + `writeShutdownRequest` + `shutdownRequested` + 类 KDoc 措辞。
4. `ModelExecutionService.kt`：唯一的真读点（`val shutdownRequested = shutdownRequested()`）、两处实参、日志字段、私有包装层、以及只为它存在的 `stagingRoot()`。
5. `ChatStreamOffloadHandler.kt`：`activeStreams` 的两处 KDoc（该计数器的**唯一生产读者**就是被删的那个 guard，注释不改就变成新谎言）。

测试（5）：`ModelExecutionLifecycleTest`（`idle worker proceeds to stopping regardless of shutdown request` 收敛成一条断言，改名 + 注释说明"两列合一"）、`ProviderExecConcurrencyTest`（4 个命名实参）、`ModelExecutionMailboxTest`（往返测 → **反向删除断言**）、`ActiveStreamsSourceShapeTest`（**第一次 CI 唯一红点**，见 §3.6 —— 它钉的是被删的那个 guard，按"门的立场是对的"重锚而不删）、新增 `ModelExecutionLifecycleTransitionTableTest`。

### 为什么不选「接进 drain 语义」

接进去**不产生新能力**：主进程的请求只可能有两种时机——
- **quiescent 时**：`ACTIVE/QUIESCE_PENDING/DRAINED → STOPPING → DEAD` 已经无条件发生，请求是空操作（世界状态相同）。
- **in-flight 时**：要让它有意义就必须**中途抢占**（截断 stream.jsonl、决定半截内容归属、接冷启动恢复策略）。那是 P0 级的新设计（它直接决定"用户的半截回答要不要救"——正是 LOOP-1030 R2 那条 P0 candidate 的语义面），**不是 P2 清理**，而且会在一个刚被判定"闭环 L2"的环上再加一条没人验证过的路径。

按修复门：这条是 **P2（latent 死码清理）**。P2 的出口是"删掉 or 登记"，不是"顺手造一个新功能面"。所以删。

**顺带回答"那内存压力下到底还能不能回收 :modelservice"**：不能，而且过去也不能。这个能力**从来没有存在过**——这次删除只是把"文档/代码暗示它有"变成"明确没有"。复活路径写进 §5 R4。

---

## §3 证据

### 1) 行为不变证明（双编译差分，70 格全等）

装置 `/var/minis/shared/work/probe-fw-lever/`：
- `old/Lifecycle.kt` = `git show 039c9ab2:…/ModelExecutionLifecycle.kt`（旧状态机，带第三参数）
- `new/Lifecycle.kt` = 改动后的状态机
- `MainOld.kt` / `MainNew.kt` = **同一张输入矩阵**：5 状态（ACTIVE/QUIESCE_PENDING/DRAINED/STOPPING/DEAD）× 7 组 quiescence（idle / active1 / active2+queued1 / queued1 / unacked1 / unflushed / unflushed+active1）× 旧侧再 × `shutdownRequested ∈ {false,true}` = **70 格**
- 各自**独立编译**（不共享符号，杜绝"两边引用同一份实现"的假对比），各自输出 `stat=… q=… req=… -> next=… kill=…`

```
$ diff old_table.txt new_table.txt && echo IDENTICAL_70_CELLS
IDENTICAL_70_CELLS
```

即：旧机器在 `shutdownRequested=false` 与 `=true` 两列的每一个格子，和新机器的对应格子**逐字符相同**。这就是"删掉的是空转参数"的可执行证明。

### 2) 表驱动单测（期望值写死字面量）

新增 `ModelExecutionLifecycleTransitionTableTest`（3 个用例）：
- `every cell of the transition matrix matches its literal`：35 格（5 状态 × 7 quiescence），每格的 `next` 与 `shouldKill(next, q)` 都是**字面量**（不从被测常量推导）。
- `transition matrix has the expected shape`：35 / 5 状态 / 7 输入的计数守卫（防止"删掉几行表格"式假绿）。
- `transition takes no shutdown flag`：`transition` 的 `parameterTypes.size == 2` —— **绊线**：`shutdownRequested: Boolean = false` 这种带默认值的复活，对每个调用点和每个行为测试都不可见，只有这条能抓（见臂 A1）。

同时 `ModelExecutionMailboxTest` 的往返测改成 **`dead shutdown lever stays deleted`**：用 **java 反射**（不是 `KClass.members`——kotlin-reflect 不在单测 classpath 上时它会抛 `KotlinReflectionNotSupportedError`，被 try/catch 吞掉就成了永久绿的假测试）断言 `writeShutdownRequest` / `shutdownRequested` / `FILE_SHUTDOWN` 都不存在。

**为什么改测试而不是保留**：任务书允许（"改测试而不是改门"）。逐条理由：
- `LifecycleTest` 的 10 个实参：参数不存在了，调用不编译——这是删除的必然结果，**语义没有一条丢失**（该文件里"忽略 shutdown 请求"的那条断言本来就在测"两列同值"，删掉一列后断言仍然成立，注释说明了合并）。
- `ProviderExecConcurrencyTest` 的 4 个 `= false`：同族，纯实参。
- `MailboxTest` 的往返测：测的是被删 API 本身，**替换**成删除断言（净增保护，不是净减）。

### 3) 变异臂（4 条，全部 RED-AS-EXPECTED）

`nice -n 10 python3 probe-fw-lever/arms.py`（每组都是**真源码 + 真仓库测试**重新编译运行，不是手抄副本）：

```
ARM                                                          RESULT       FAILED TESTS
BASELINE lifecycle+table                                      PASS         OK []
BASELINE mailbox                                              PASS         OK []
BASELINE provider-exec concurrency                            PASS         OK []
A1 re-add shutdownRequested param (defaulted, unread)         FAIL         OK ['transition']
A2 STOPPING+quiescent never reaches DEAD                      FAIL         OK ['every', 'shouldKill']
A3 busy worker transitions to STOPPING                        FAIL         OK ['busy', 'every', 'queued', 'stopping', 'streaming', 'unflushed']
A4 re-add writeShutdownRequest/shutdownRequested/FILE_SHUTDOWN FAIL        OK ['dead']
ARMS=ALL_AS_EXPECTED
```

- **A1** = 把死参数**带默认值**加回去（`shutdownRequested: Boolean = false`，无人读）→ 所有行为格子仍然全绿，**只有** `transition takes no shutdown flag` 变红。这正是原杠杆能活这么久的方式。
- **A2** = `STOPPING -> DEAD` 改成 `STOPPING -> STOPPING`（该死不死的泄漏）→ 矩阵红。
- **A3** = 非 quiescent 返回 STOPPING（会切断在飞 stream）→ 6 条行为测试红。
- **A4** = 把 `FILE_SHUTDOWN` + 两个函数加回 Mailbox → 删除断言红。

### 4) 其它本地门

- `sh scripts/scan/scan.sh` → **`Summary: 113 passed, 0 failed`**（`scripts/**` 零触碰，基线不变）。
- `python3 scripts/scan/test_scan.py .` → **沙箱内两次跑到第 36 道门前后挂死**（两次都停在同一点，`ps` 显示进程 CPU 时间不再增长；把当时应在运行的那道门单独跑 `--selftest` 是 1 秒通过 ⇒ 不是该门本身，且 `scripts/**` 本次零改动）。这一项的权威读数交给 CI（CI 会跑完整夹具套件并打印 `ALL N CASES PASS`，见 §4）；本地能给的等价证据是：**113 道门在真树上全绿**（上面那条 `scan.sh` 就是夹具套件里 "REAL repo tree" 那一半）+ `git diff --name-only -- scripts/` = 空。
- `probe-fw-lever/balance.py`（跳字符串/注释/嵌套注释的括号配平 + 冲突标记扫描）→ 9 个 .kt 文件 `BALANCE=OK`。
- `kotlinc sandbox/ExecutionCoordinator.kt ModelExecutionService.kt ChatStreamOffloadHandler.kt` → 只按语法类错误过滤（`expecting|unexpected|syntax error|unclosed`）**0 命中**（沙箱无 Android SDK，语义级只能交给 CI）。
- JVM 实跑：`ModelExecutionLifecycleTest`（9）+ `ModelExecutionLifecycleTransitionTableTest`（3）= **12/12 OK**；`ModelExecutionMailboxTest` **10/10 OK**；`ProviderExecConcurrencyTest` **5/5 OK**。

### 5) 跨进程协议面（硬约束 2）

`ModelExecutionMailbox.writeState` 的 schema = `{state, active, unacked, at}`，`readState` / `readStateName` / `FILE_STATE` **一个 byte 未动**（`git diff` 里这两个函数零改动）。`shutdown` 标记一直是**独立文件**，不在 state.json 里，所以删它不涉及任何 state.json 字段。

### 6) 第一次 CI 唯一红点：`ActiveStreamsSourceShapeTest`（我的取证漏了一类"读者"）

**CI run `41005655384` @ `895498f0` → failure**：`8549 tests completed, 1 failed`，唯一失败用例 `ActiveStreamsSourceShapeTest > readers use an atomic get`。

**它为什么红**：这条是**源码形状门**（读 `ExecutionCoordinator.kt` 原文），断言

```kotlin
src.contains("ChatStreamOffloadHandler.activeStreams.get() > 0")          // 那个 guard
src.contains("activeStreams=\${ChatStreamOffloadHandler.activeStreams.get()}")  // 那个 guard 的日志行
```

—— 也就是**它把"guard 存在"当契约钉住了**。guard 随杠杆删除，断言自然失主。

**我的取证为什么漏**：我按**符号**搜了 test 树（`rg -n "shutdownRequested|writeShutdownRequest|FILE_SHUTDOWN|maybeReclaim"`），而这条门钉的是**被删 guard 里的另一个符号**（`activeStreams`）+ **另一个生产文件的原文**。教训（本机第 N 次同族）：**改生产文件前，除符号外还要 `rg -l "<文件名>" src/test/`** —— 源码形状门匹配的是文件里的任意字面量，不是它引用的符号名。

**修法（改门 = 改修法，不改立场）**：门的立场（`ISSUE-04`：这个计数器只许原子读）是对的，错的只是它钉了一个**即将不存在的站点**。所以**重锚**而不是删：

- 从"必须存在这个 guard"改成"**生产树里任何 `activeStreams` 出现点，除声明文件外，都必须是 `activeStreams.get()`**"。
- 覆盖面从 1 个文件扩到 `src/android/app/src/main/java` 全树 ⇒ 比原来**严格**（第三个文件里新加的裸读也会被抓）。
- 当日读者数 = 0，**打印不硬断言**（断言"0 读者"会禁止将来合法的消费者）。

**新门的牙（A5，一次性探针）**：在 `offload/` 下临时放一个 `ArmProbeTmp.kt`，内含 `fun badRead() = ChatStreamOffloadHandler.activeStreams.value` → 门红，且报错点名文件：

```
AssertionError: every activeStreams read must be an atomic get(); offenders:
  [ArmProbeTmp.kt: …ChatStreamOffloadHandler.activeStreams.value…]
```

探针文件用完立刻删除（`git status` 确认干净）。

> 这条也改写了我对 §1 的表述：**"生产读者 = 0" 仍然成立**（guard 是写者/守卫，不是行为读者），但**"钉住这条链路的读者 = 0" 不成立** —— 有一道测试门把它当契约。形状门也是读者，只是它读的是文本不是值。这类"读者"不会出现在符号 grep 的结果里。

---

## §4 CI 读数

**两条 run**：第一次红的原因与修法已在 §3.6；下表是最终绿的那一条。

| 项 | 值 |
|---|---|
| run | `41007282401`（attempt 1，`workflow_dispatch`） |
| head_sha | `0faf65d033aa418973ed5aa522a199c43427d1f2` —— 与本地 `HEAD` **逐字符一致** |
| 结论 | **success**，05:14:10Z → 05:36:10Z ≈ **22.0 min** |
| 步骤 | 27 步 = **25 success + 2 skipped**（`Move the rolling tag android-latest` / `Publish to Releases`，main 门控，分支上本该跳） |
| 门 | `Summary: 113 passed, 0 failed` + 夹具 **`ALL 349 CASES PASS`**（`scripts/**` 零改动，与基线一致） |
| 单测 | **8,549 条 PASSED 行，0 条真 FAILED**（先排除 `PASSED` 子串误判） |
| 编译 | `e: file` **0**；全 27 步日志 `1,681,853 B / NUL 0` |
| 本支用例 | `ModelExecutionLifecycleTransitionTableTest` 4/4、`ModelExecutionLifecycleTest` 9/9、`ModelExecutionMailboxTest` 10/10（含 `dead shutdown lever stays deleted`）、`ActiveStreamsSourceShapeTest` 2/2（含重锚后的 `any reader of the counter goes through the atomic get`）、`ProviderExecConcurrencyTest` 5/5 —— 逐条 PASSED |
| 首次红 | `41005655384` @ `895498f0` **failure**：`8549 tests completed, 1 failed` = `ActiveStreamsSourceShapeTest > readers use an atomic get`（见 §3.6） |

---

## §5 残留（每条带触发条件）

- **R1 `ChatStreamOffloadHandler.activeStreams` 现在没有任何生产读者**（它的唯一读者是被删的那个 guard）。保留为进程内 gauge（删它要动每条 stream 的 entry/exit 路径，收益为零）。**触发条件**：将来任何代码想按"流活跃度"决策时，先确认这个计数器语义仍然成立（stream 起止配对由自己的 try/finally 保证，见该处注释）。
- **R2 `ModelExecutionMailbox.readState` / `readStateName` 也没有生产调用点**（`state.json` 仍由 worker 每个 transition 前写，供崩溃取证/诊断）。**触发条件**：真要做"主进程读 worker 实时状态"时，先确认它读的是 worker 自己写的权威值（worker 侧注释已声明 authoritative），别拿主进程计数器顶替。
- **R3 旧安装可能存在 `cache/model-exec/shutdown` 残文件**：删代码后它**没有读者**，是惰性垃圾；`cacheDir` 本来就由系统回收。**触发条件**：若将来有人做"升级后清理遗留文件"的通用机制，把它顺手纳入即可——**不要**为它单独加一次性清理代码（那就是新一轮死逻辑）。
- **R4「内存压力下主动回收 :modelservice」能力现在明确不存在（负决策登记，建议进 backlog）**。复活路径必须满足两条，否则必然重演死环：①**读数是 worker 自己的**——主进程看不到 worker 进程的 native 堆（它只能看到 app 自身 `Debug.getNativeHeapAllocatedSize()`），所以压力判据要么搬到 worker 内，要么由 worker 上报；②**决策必须在状态机里被读**——新增 `when` 分支 + 配套测试，而不是写一个标记文件盼着别人来读。**触发条件**：真机上出现"因 :modelservice 常驻导致主进程内存被推过线 / lmkd 或系统回收"的日志证据（例如 `launch-beacon` 里 silent_kill 与 worker 在飞时间相关）。
- **R5 真机未验**：本支删除的是**行为恒等**的死码（70 格差分证明），生产行为按定义不变，所以没有真机验证面；证据等级 = CI 编译级 + JVM 级 + 源码形状级。**触发条件**：若真机上出现 `request finished (holding)` 行的格式变化导致外部解析脚本失配（该行少了 `shutdownRequested=` 字段），按需补回字段（它是日志字段，不承载行为）。
- **R6 日志窗口**：`/var/minis/logs/` 里只剩 `minis-2026-10-28/29/30`，“三天 0 次”的窗口以这三个文件为准（10-27 及更早已被轮转掉）。
- **R7 `docs/dev-history/**` 里有 3 处历史记述提到这条杠杆**（`rikkaminis-dev-history.md:10805/14171/30225`）。那是**档案**，记录的是当时发生的事，不该按今天的结论改写。**触发条件**：下次 `dev-history-sync` 重建档案时，新条目应写明"2026-10-30 判定 L0 死环并删除"，让档案自身带上后续。
