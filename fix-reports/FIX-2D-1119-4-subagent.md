# FIX-2D-1119-4 · 子代理派发 pin 计数泄漏（`SubagentDispatchPin.begin`）

分支 `fix/subagent-pin-1119`　基线 `b82b2963`（= 当时 main）
来源报告 `reports/r2d1119/3_Subagent_Report.md` F-01　判级 P1

---

## 1 范围

| 项 | 内容 |
|---|---|
| 改动文件 | `src/android/app/src/main/java/com/rikkaminis/app/data/SubagentDispatchPin.kt`（生产）<br>`src/android/app/src/test/java/com/rikkaminis/app/data/SubagentDispatchPinTest.kt`（测试）<br>`fix-reports/FIX-2D-1119-4-subagent.md`（本报告） |
| 不动 | `scripts/scan/**`、`ChatPromptAndTools.kt`、`ChatToolExecutors`、任何 `strings.xml`、其他支的域 |
| 判级 | F-01 P1（修）；F-02 P2（登记，不修） |

**这改变什么用户行为？**

一个父会话派发子代理时，`begin` 会先把「该会话有一个进行中的派发窗口」记进内存计数，再走两次 Room 往返（读 `pinned_at`、写 pin）。修前这两次往返没有任何回滚：Room 抛一次（磁盘满 / DB 已关 / 协程取消逃逸出 suspend 调用），计数永久 +1，`isDispatching(sessionId)` 永远为真，该会话此后每次派发都读到一个「永不关闭的窗口」。修后失败即把这次窗口的计数还回去并重抛，下一次派发看到的是干净状态。

---

## 2 逐条先证

### F-01（P1）成立

读 `SubagentDispatchPin.kt:126-134`（修前）：

```kotlin
lockFor(sessionId).withLock {
    val depth = depths.merge(sessionId, 1, Int::plus) ?: 1
    if (depth == 1 && readPinnedAt(sessionId) == null) {
        pin(sessionId)?.let { writtenPins[sessionId] = it }
    }
    depth
}
```

- `depths` 类型 = `ConcurrentHashMap<String, Int>`（`SubagentDispatchPin.kt:79`），`merge` 先于两个 Room 回调执行；
- `readPinnedAt` / `pin` 都是 `suspend (String) -> …`，调用点在 `withLock` 内、**无任何 try/catch**；
- 对照 `end`：`readPinnedAt` 与 `unpin` 各自被 `try { } catch (_: Throwable) { return@withLock false }` 包住（`:170-181`）。`begin` 侧没有对应物，**同一决定的两半不对称**；
- 回滚路径确认：修前**只有** `end` 会动 `depths`，而 `end` 只在调用点的 `finally` 里跑；`begin` 抛出时调用点（`ChatPromptAndTools.kt:548`）还没进入 `try { … } finally { end }`，所以 `end` 永远不会为这个窗口跑 —— 计数确实永久 +1。

调用点佐证（`ChatPromptAndTools.kt:548-582`）：`begin(...)` 在 `val result = try {` **之前**，`end(...)` 在 `finally` 里。`begin` 抛异常时 `end` 不会执行。

**旧 KDoc 说的是反的**：原注释写 "Never throws on cancellation: … a cancellation escape here would leak both the count and the pin"，但代码既没 catch 也没回滚 —— 只要有一个异常逃逸（不限于取消），泄漏就是真的。KDoc 已订正。

### F-02（P2）登记，不修

`optArgString` 未迁移的读取点清单（来自来源报告 §F-02，本次未逐点复核，属 P2 不修）：

- `tools/FileReadTool.kt`：`path`、`direction`
- `tools/FileWriteTool.kt`：`path`、`content`
- `ui/chat/ChatToolExecutors.kt`：同批残留的 `optString` 读取点

不在本支域内（`tools/**` 与 `ChatToolExecutors` 均禁碰），仅登记。

---

## 3 修法

`begin` 把「读 + 写 + 记账」整体放进 `try`，`catch (t: Throwable)` 里归还本次窗口的计数并把零值条目摘掉，然后 `throw t`。

```kotlin
val depth = depths.merge(sessionId, 1, Int::plus) ?: 1
try {
    if (depth == 1 && readPinnedAt(sessionId) == null) {
        pin(sessionId)?.let { writtenPins[sessionId] = it }
    }
} catch (t: Throwable) {
    depths.merge(sessionId, -1, Int::plus)
    if (depths[sessionId] == 0) depths.remove(sessionId)
    throw t
}
```

四个取舍点：

1. **`catch (Throwable)` 而不是 `catch (CancellationException)`**：Room 的失败（`SQLiteException` / `IllegalStateException` / 磁盘满）才是主路径，取消只是其中一种。用 `Throwable` 才能覆盖「读抛了」与「写抛了」两种。
2. **取消必须 rethrow**：`throw t` 原样重抛，不吞、不包装 —— 调用方的 `catch (ce: CancellationException) { throw ce }` 仍然看得到自己的取消（`ChatPromptAndTools.kt:561`）。
3. **零值条目要摘掉**：`depths.merge(-1)` 会把条目留成 `0`，而 `isDispatching` 用的是 `containsKey`。不摘的话修完仍是「永久认为在派发」。摘除在锁内做，`end` 用的也是锁内的 `compute { … -> null }`，两条路径的语义一致。
4. **`writtenPins` 不回滚**：如果 `pin` 已经落地、`writtenPins[sessionId] = it` 也执行了、之后才抛，回滚 `depths` 会让这条记录失去「对应窗口」，但它记录的是**真实写进 DB 的值**。删掉它 = 放弃唯一能撤销它的信息；留着它 = 后续 `end`（或下一个窗口关闭时）能把它撤掉。选择保留，行为写进用例（见 §5 M-用例 `F-01 a pin write that lands and then fails rolls the depth back`）。

### 为什么不是「先 pin 成功再 +1」

任务书给的两个方向里，调顺序（先读先 pin，成功后再 +1）会把「计数」和「窗口」拆成两个时刻：`pin` 成功、`+1` 之前若被取消，DB 已 pin 但计数没有窗口，`end` 无从触发 → 变成**另一种泄漏**（行被 pin 住不还原）。当前实现保持「计数先占位、失败必归还」，一个不变式（计数 != 0 ⟺ 存在一个已占位窗口）贯穿两条路径。

---

## 4 证据

### 4.1 JVM 行为用例（12 条全 PASSED，0 FAILED）

装置：`/var/minis/shared/work/r1119/subagent-jvm/{run.sh,RunTests.java}`（逐条打印 `PASSED <method>`）。

新增 4 条（其余 8 条为既有用例，一字未改）：

| 用例 | 断言（字面量期望） |
|---|---|
| `F-01 a Room failure while reading the current pin rolls the depth back` | `assertThrown(boom, thrown)`（类 + message）；`isDispatching == false`；`pinWrites == 0`；重开后 `open == 1`、`pinWrites == 1`、`value == 100L` |
| `F-01 a Room failure before the pin write rolls the depth back` | `assertThrown(boom, thrown)`；`isDispatching == false`；`pinWrites == 0`；重开后 `open == 1`、`value == 100L` |
| `F-01 a cancellation escaping the pin write rolls the depth back and is rethrown` | `assertThrown(cancelled, thrown)`；`isDispatching == false`；重开后 `open == 1`、`pinWrites == 1` |
| `F-01 a pin write that lands and then fails rolls the depth back` | `assertThrown(boom, thrown)`；`isDispatching == false`；`value == 100L`（写已落地）；重开后 `open == 1`、`pinWrites` 仍 1（被读成「用户已 pin」不收养）；`close == true`、`unpinWrites == 0`、`value` 仍 `100L` |

期望值全部写字面量（`1` / `0` / `100L` / `true`），没有一条写成实现的同式。`assertThrown` 断言类 + message 而非 identity —— 理由见 §4.4。

### 4.2 门

- `python3 scripts/scan/subagent_dispatch_check.py --root .` → `OK: sub-agent dispatch wiring holds (5 call-site contracts + 8 pin-window decisions)`，rc=0。
- `python3 scripts/scan/test_scan.py --single test_da1017_gates` → 14 pass / 0 fail，其中 `✅ subagent_dispatch_check selftest has teeth (SELFTEST OK)` + `✅ subagent_dispatch_check real tree green`。

D5 对 `begin` 的 8 条判据（NonCancellable / withLock / depth 计数 / 首窗才 pin / 先读后 pin / null 比较 / 末窗才还原 / 还原前读）在新代码下全部仍成立：`try` 包在 `if (depth == 1 && readPinnedAt(...) == null)` **外面**，`readPinnedAt(` 仍出现在 `pin(` 之前，`depths.merge` 仍是 `val depth = …` 的右侧。**门未被改，也无需改。**

### 4.3 CI

| 项 | 值 |
|---|---|
| run | **#3289 / `44369899875`** |
| head_sha | `cf3996465ab73118602700c2336120abbd9c7a13`（= 分支 tip，逐字符一致） |
| 五 job | lint `success` / build-apk `success` / unit-tests `success` / scan-gate `success` / publish `success` |
| unit-tests | `9754 PASSED` 行 / **0 FAILED** 行；`BUILD SUCCESSFUL in 5m 21s` + 第二条 `BUILD SUCCESSFUL`（instrumented 编译门） |
| `SubagentDispatchPinTest` | **12 条逐条 PASSED**（4 条新 F-01 + 8 条既有，名单见 §5 前段） |
| scan-gate | `Summary: 115 passed, 0 failed`，且 `✅ subagent_dispatch_check selftest has teeth (SELFTEST OK)` / `✅ real tree green` 都在 |
| publish | 第 6 步 `Move the rolling tag android-latest` **skipped**、第 7 步 `Publish to Releases` **skipped** —— main 门控生效，分支构建没有污染 release 资产 |

### 4.4 首轮 CI 红与装置缺陷（记录在案）

首轮 run **#3281 / `44364937198`** 在 `1a95b8d1` 上 **unit-tests failure**：4 条新用例全红，但失败文案自相矛盾——

```
expected same:<java.lang.RuntimeException: disk full>
     was not:<java.lang.RuntimeException: disk full>
```

类名和 message 一模一样，`assertSame` 却失败 ⇒ 实例不是同一个。原因是 **kotlinx.coroutines 的 stacktrace recovery**：异常跨协程边界（`withContext(NonCancellable)`）时会**复制**实例，副本保留类与 message、不保留 identity；而 recovery 只在 debug 模式开启，debug 模式又由 **`-ea`** 触发 —— Gradle 的 test task 默认带 `-ea`，我的本地装置当时没带。

两件事一起做：

1. **断言换形式**：`assertSame` 不是这里的契约（跨边界的 identity 本就不保证，且随 coroutines 版本/调试开关漂移）。新增 `assertThrown(expected, actual)` 断言 **类 + message**，即「失败没有被吞、也没有被包装」——这正是副本会保留的部分。
2. **装置对齐 CI**：`run.sh` 的 java 调用加 `-ea`，并在探针里实测确认：同一段 `withContext(NonCancellable) { throw original }`，不带 `-ea` 得到 `same=true`，带 `-ea` 得到 `same=false`。**本地绿必须是在能复现 CI 红的装置上得到的绿**。

装置对齐后做了反向验证：把 `assertSame` 临时塞回一条用例，`run.sh` 立刻复现 CI 的同一条红（`FAILED … :: identity expected sa…`），还原后 sha256 一致。**这条反证是本节的重点** —— 修完之后装置必须能重现被修的症状，否则「绿」没有意义。

5 条变异臂在 `-ea` 下重跑仍是 `ARMS 5, RED-PRECISE 5`。

---

## 5 变异负控（5 臂，全部 RED-PRECISE）

装置：`/var/minis/shared/work/r1119/subagent-jvm/mutate.py`（快照 + `finally` 还原 + sha256 校验，不用 `git checkout --`）。

| 臂 | 变异 | 预期红集 | 结果 |
|---|---|---|---|
| M1 | 完全去掉 `try/catch`（回修前形态） | 4 条 F-01 用例 | RED-PRECISE（4/4） |
| M2 | 回滚了但吞掉异常（`throw t` 删掉） | 4 条 F-01 用例 | RED-PRECISE（4/4） |
| M3 | 回滚但不摘零值条目（`depths.remove` 删掉） | 4 条 F-01 用例 | RED-PRECISE（4/4） |
| M4 | 只 catch `CancellationException`，Room 错误照旧泄漏 | 3 条（读失败 / 写失败 / 写落地后失败） | RED-PRECISE（3/3） |
| M5 | 只包 `pin` 写，读仍裸露 | 1 条（读失败） | RED-PRECISE（1/1） |

`BASELINE-AFTER-RESTORE: OK`（sha256 前后一致）。**装置带 `-ea` 跑**（与 CI 的 Gradle test task 一致，见 §4.4）。

**M4 的预期集是跑出来改的，不是猜的**：第一版写「读失败 + 写失败」两条，实跑 3 条红（写落地后失败那条也红，因为它同样依赖 `Throwable` 捕获）。以实测为准修预期集，并在此显式记录，避免看起来像放宽断言。

---

## 6 未修与取舍

| 项 | 判级 | 处置 |
|---|---|---|
| F-02 `optArgString` 未覆盖 `path` / `content` / `direction` 等 | P2 | 登记（§2），本支禁碰 `tools/**` 与 `ChatToolExecutors` |
| `pin` 落地但记账前抛 → 行保持 pinned、无人还原 | 残留风险 | 已在用例里**显式断言**该行为（不是漏网）。彻底解需在 `begin` 失败路径上再读一次 `pinned_at` 并比对，等于在失败路径上加一次 Room 往返；该路径只在「写成功 + 续体失败」时走到，代价与收益不划算。已在 §6 登记，若要收口另开一支。 |
| `windowLocks` / `depths` / `writtenPins` 无淘汰 | pre-existing | `end` 的 KDoc 已写明这是刻意设计（锁必须与等待者同寿命），不动 |

---

## 7 文件清单

```
 M src/android/app/src/main/java/com/rikkaminis/app/data/SubagentDispatchPin.kt   +23 −8
 M src/android/app/src/test/java/com/rikkaminis/app/data/SubagentDispatchPinTest.kt  +119 −0
 A fix-reports/FIX-2D-1119-4-subagent.md
```

生产改动 23 行（含 6 行 KDoc 订正）；测试 4 条新用例 + `FakeRepo` 三个注入点（`readThrows` / `pinThrows` / `pinThrowsAfterWrite`）。

---

## 8 复现

```sh
# 行为用例（12 条，逐条 PASSED）
sh /var/minis/shared/work/r1119/subagent-jvm/run.sh

# 变异负控（5 臂，应输出 ARMS 5, RED-PRECISE 5）
python3 /var/minis/shared/work/r1119/subagent-jvm/mutate.py

# 门（单跑，不启动全量）
cd <worktree> && python3 scripts/scan/subagent_dispatch_check.py --root .
cd <worktree> && python3 scripts/scan/test_scan.py --single test_da1017_gates
```

**手动复现原缺陷**（修前代码）：让 `readPinnedAt` 抛任意异常后捕获它，再断言 `SubagentDispatchPin.isDispatching(sid)` —— 修前为 `true`（永久），修后为 `false`。
