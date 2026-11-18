# FIX-2D-1119-7-diag — 诊断域两条 P1（HangDetector 首跳自愈 / FaultAttribution 子串劫持）

支：`fix/hang-attribution-1119`｜worktree `/var/minis/shared/work/r1119/diag`｜基线 `b82b2963`（= 当前 main）

## 1 范围

本批审的是 11-17 + 11-18 两天全量改动，本支只碰诊断域的两条 P1：

| ID | 一句话 |
|---|---|
| F-01（P1） | 长卡顿（≥13s）恢复后首跳即静默自愈，清零计数并解熔断 |
| F-03（P1） | `FaultAttribution` 的 `" 500"` 等子串无词界，劫持含毫秒数的连接超时 |

**F-06（DebugRPC 缺内部 `BuildConfig.DEBUG`）= P3，按判级台账不动**：外层 `MinisApp` 已用 `if (BuildConfig.DEBUG)` 才启动 `DebugServer`，且所有客户端（含 loopback）必须带 token。有守卫的现状，不是缺陷。

**F-02 / F-04 / F-05（P2，probable）= 登记不修**，见 §6。

触碰文件（4 个，全部在 `diagnostics/`）：

| 文件 | 变化 |
|---|---|
| `diagnostics/HangDetector.kt` | +36 |
| `diagnostics/FaultAttribution.kt` | +38 / −2 |
| `test/.../HangDetectorSelfHealTest.kt` | +122 |
| `test/.../FaultAttributionTest.kt` | +166 |

`scripts/scan/**`、`strings.xml`、其他支的域一律未碰。

## 2 逐条先证

### F-01：算式成立，且比报告描述的更糟

报告给的触发链是「14s 卡顿 → 恢复后 `now - lastHangAt ≈ 11.5s ≥ 10s` → 首跳清零」。读真码后确认成立，并且顺手发现一处报告没点出的性质：

```kotlin
// recordHang —— 只在跨越阈值那一刻写一次
val newCount = synchronized(hangCounterLock) {
    val n = prefs.getInt(KEY_HANG_COUNT, 0) + 1
    prefs.edit()
        .putInt(KEY_HANG_COUNT, n)
        .putLong(KEY_LAST_HANG_AT, System.currentTimeMillis())   // ← 唯一写点
        .apply()
    ...
}
```

全文件只有这一个 `KEY_LAST_HANG_AT` 写点（`grep` 确认：`markHealthyTick`/`resetHangCount` 只写 0，`stampHangRecovery` 是本支新增）。于是：

- 卡顿持续 `D` ms 时，恢复瞬间 `now - lastHangAt = D - 3000`；
- `D ≥ 13000` ⇒ 已经 ≥ 10s ⇒ `QuietHealPolicy.mayResetOnHealthyTick` 返回 true；
- **卡顿持续时间被当成了「安静时间」**，于是卡得越久，自愈得越快 —— 与计数器存在的理由正好相反。

先证手段：把三个真实写点/读点抄成算式逐一核对（`recordHang` 写 → `watchLoop` 的 `since < HANG_THRESHOLD_MS` 分支 → `healQuietHang` → `markHealthyTick` → `QuietHealPolicy.mayResetOnHealthyTick`），并核 `mayAttemptHeal` 不会挡（本进程计数的 `countedThisProcess = true` 直接放行）。结论：**成立，`concrete` 无误。**

### F-03：成立，且旧规则比报告举的样本更宽

```kotlin
// 修前
private val SERVICE_PATTERNS = listOf(
    "http 4", "http 5", " 400", " 401", " 403", " 429", " 500", " 502", " 503", " 504", ...
)
if (SERVICE_PATTERNS.any { raw.contains(it) }) { return Result(Layer.SERVICE, ...) }
```

`contains(" 500")` 只约束左边（一个空格），右边不约束。逐条实测（脚本核算，非推断）：

| 样本 | 修前命中 | 归因 | 正确归因 |
|---|---|---|---|
| `connect timed out after 500 ms` | `" 500"` | SERVICE | CONNECTION |
| `connect timed out: proxy retry in 400 ms` | `" 400"` | SERVICE | CONNECTION |
| `read timed out after 2500 ms (connect 5020 ms)` | `" 502"` | SERVICE | CONNECTION |
| `connect timed out, elapsed 5000ms` | `" 500"` | SERVICE | CONNECTION |
| `connect timed out, port 4030 refused` | `" 403"` | SERVICE | CONNECTION |
| `connect timed out, ratio 500.5` | `" 500"` | SERVICE | CONNECTION |

注意报告举的 `waited 1500 ms` **不成立**（`" 500"` 需要前导空格，`1500` 前面是 `1`）—— 我把它从用例里换成真成立的形态，见 §3。

另：报告没提 `status=500` / `code: 503` 这两种形态**修前压根不命中**（没有前导空格），所以词界修法是严格变宽而非收窄。

## 3 修法

### F-01：恢复时写时间戳（而不是「用 now 代替 lastHangAt」）

新增一个只写时间戳、不碰计数的私有函数：

```kotlin
private fun stampHangRecovery(nowMs: Long) {
    val ctx = appContext ?: return
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    synchronized(hangCounterLock) {
        prefs.edit().putLong(KEY_LAST_HANG_AT, nowMs).apply()
    }
}
```

调用点在 `watchLoop` 的 `if (wasHang)` 收尾块内 —— 正好是「已计数的卡顿真的结束了」那条路径，位于 post-recovery 采样之后、`episode ENDED` 日志之前：

```kotlin
if (wasHang) {
    writeStallSample("post-recovery", episodePeakSinceMs, escalation)
    stampHangRecovery(now)          // ← F-01
    println("[T-HANG-DIAG] hang episode ENDED ...")
}
```

**为什么不用「恢复时用 `now` 而不是 `lastHangAt` 判断」**（任务书给的备选）：那样等于绕过持久化时间戳，下一次进程启动时 `last_hang_at_ms` 仍是旧值，`LaunchCycleBeacon` 与本进程的自愈判据会各用一套时钟 —— 两套时钟就是同族第 7 次。写时间戳让「安静期」只有一个定义。

**语义后果（有意）**：一次 14s 卡顿现在需要「恢复 + 再安静 10s」才清零，而不是恢复即清零。这正是 KDoc 与 `RESET_AFTER_QUIET_MS` 字面承诺的语义；此前实现比承诺弱。

**不改 `mayAttemptHeal`**：F-01 与 F-02 是不同入口（F-02 是 `markHealthyTick` 的公开入口没过进程存活门控，P2 probable，不修）。本支只收口「已计数卡顿的安静期起点」。

### F-03：状态码改成带边界正则

```kotlin
private val STATUS_CODE_PATTERN = Regex(
    "(?<![\\d.])(400|401|403|429|500|502|503|504)(?!\\d)(?!\\.\\d)(?!\\s*ms)",
)
```

三条 lookaround 各管一件事：
- `(?<![\d.])` 左侧：前一个字符是数字或点 ⇒ 这些数字是更长数字的尾巴（`1500`、`1.500`），不是状态码；
- `(?!\d)` / `(?!\.\d)` 右侧：同理（`5000`、`500.5`）；
- `(?!\s*ms)` 右侧：紧跟时长单位 ⇒ 是时长不是状态码。

`=`、`:`、空白、引号、串首尾都仍是合法边界 ⇒ `HTTP 500` / `status=500` / `error code: 503` / 裸 `500` 全部照旧命中，其中 `=` 与 `:` 两种形态是**修前不命中**的（严格变宽）。

`SERVICE_PATTERNS` 里删掉 8 条空格前缀数字串，其余短语（`http 4`/`http 5`/`bad request`/`first chunk timeout`…）一字未动。

**没有选「先剥离 `\d+\s*ms` 再匹配」**：剥离只解决毫秒数这一种形状（`5000ms` 无空格、`port 4030`、`ratio 500.5` 都不带 `ms`），而且要在匹配前改写输入，等于多一条需要被测试覆盖的路径。词界是就地约束，没有额外状态。

**「这改变什么用户行为？」**：
- F-01：用户遭遇一次长卡顿时，渲染降级（`_renderBreakerActive`）与「上次启动一直卡」的落地首页熔断**不再在恢复后 500ms 内被抹掉**，而要再证明 10s 稳定。真卡顿期间不再出现「刚卡完就恢复正常」的假象，异常退出的归因也不会被降级成 `silent_kill`。
- F-03：`FaultAttribution` 目前**无生产调用方**（面板是已登记的后续项），所以用户可见行为今天不变；变的是「接上面板时不会把用户自己的网络故障报成中转站的锅」。这一条与 `[W1118-T6 R6 F-01]` 同源。

## 4 证据

### 4.1 沙箱 JVM 装置（`/var/minis/shared/work/r1119/diag-jvm/`）

三个测试类全绿：**33 例 0 失败**（11 + 9 + 12，含既有 22 例一字未改）。

逐条 PASSED（新增部分）：

```
QuietHealPolicyTest（11 例，既有，未改）
HangDetectorSelfHealTest（9 例，+2）
  PASSED the quiet clock restarts when a counted hang episode ends            ← 新增
  PASSED the recovery-stamp probe rejects a source without a working recovery stamp ← 新增
  PASSED watchdog self-heals from its healthy heartbeat branch
  PASSED the heal is the only in-file caller of the quiet-period reset
  PASSED the process-local flag flips together with the counter under the lock
  PASSED the wire checks reject a source with the self-heal removed
  PASSED a self-healed stall no longer reads as an abnormal exit
  PASSED an unrecovered stall still reads as crash_or_stall
  PASSED a real crash artefact stays abnormal even with a settled counter
FaultAttributionTest（12 例，+5）
  PASSED a millisecond count is not read as an http status code               ← 新增
  PASSED the status-code boundary holds on both sides of the digits           ← 新增
  PASSED real http status codes are still attributed to the service           ← 新增
  PASSED the token rule rejects the pre-fix substring vocabulary              ← 新增
  PASSED status codes are matched by the token pattern, not by a substring    ← 新增
  PASSED a first-chunk budget failure is attributed to the service
  PASSED an http status is attributed to the service
  PASSED a connection-leg timeout stays on the connection layer
  PASSED bad credentials still win over both layers
  PASSED unknown text is reported as unclear
  PASSED the service list is consulted before the connection list
  PASSED the order probe rejects the pre-fix ordering
```

装置的两处设计要点（都会影响证据可信度）：

1. **`LaunchCycleBeacon.crashVerdict` 用脚本逐字切出来**（`extract_shim.py`），不是手抄。锚点找不到、括号不配平、块过短、块里没有 `crash_or_stall` 字面量 —— 任一情况都 `exit 1` 并打印 `EXTRACT-FAILED`，不会静默生成一个空壳让测试「绿」。
2. **`HangDetector.kt` 不参与编译**，只作为文本被形状探针读取。它依赖 `Handler`/`Looper`/`MinisApp`，编进来只会引入一堆与判据无关的桩。

### 4.2 变异负控（8 臂，全 RED-PRECISE）

`python3 /var/minis/shared/work/r1119/diag-jvm/mutate.py`（快照 + `finally` 还原 + sha256 校验；入口拒绝脏树）：

| 臂 | 变异 | 判据 | 红的用例 |
|---|---|---|---|
| M1 | 删掉 `stampHangRecovery(now)` | RED-PRECISE | quiet-clock 用例 + 其负控 |
| M2 | 戳记移到 `healQuietHang()` **之后** | RED-PRECISE | 同上（戳记在消费时钟之后才写） |
| M3 | 戳记顺带把 `KEY_HANG_COUNT` 归零 | RED-PRECISE | 同上（自愈必须「挣得」，不能白送） |
| M4 | 去掉 `synchronized(hangCounterLock)` | RED-PRECISE | quiet-clock 用例（与 `recordHang` 的竞争回来了） |
| M5 | 状态码退回 8 条空格前缀子串 | RED-PRECISE | 4 例（行为 + 形状） |
| M6 | 正则去掉左侧 `(?<![\d.])` | RED-PRECISE | 边界用例 + 形状探针（`retried 1500 times` 被读成 500） |
| M7 | 正则去掉 `(?!\s*ms)` | RED-PRECISE | 3 例（毫秒样本全部复活） |
| M8 | 删掉 `STATUS_CODE_PATTERN` 判定块 | RED-PRECISE | 真状态码用例 + 形状探针 |

`BASELINE-AFTER-RESTORE: 33 passed, 0 failed`；`HangDetector.kt` sha256 `1db094cb…8ff3311`、`FaultAttribution.kt` sha256 `4f6429ca…f7858d`，与变异前逐字节一致。

两处臂的初版写错了，值得记：

- **M2/M4 第一版报 RED-WRONG-ARM**，原因不是测试漏，而是**我的臂写成了多步替换却没告诉脚本**（第一版脚本只支持单条 edit）。改成「一条臂 = 一串有序 edit」后两条都精确红。**变异 BLIND/WRONG-ARM 先怀疑臂，别急着改测试。**
- **M6 第一版预期集多写了「pre-fix 子串词汇」那条**：去掉左侧边界后所有毫秒样本仍被 `ms` 守卫挡住，那条用例本来就该绿。预期集写宽了会让 RED-OVERBROAD 与真漏网混在一起，无法区分。

### 4.3 云端 CI

代码/测试提交 `b2252554` 与含本报告的提交 `06de9d0f` 都跑了全量 CI（分支 push 不触发，两次都是手动 dispatch）：

| run | 测试的提交 | 结论 |
|---|---|---|
| **#3293** / `44373325007` | `06de9d0f8d33bac837cb5724c605c637799cf170`（含本报告全文） | **五 job 全 success** |
| #3292 / `44373120997` | `b2252554e60327bc69911745f0082911ceee598e` | 同分支并发被 #3293 取代（`cancelled`，无失败 job） |

`head_sha` = `06de9d0f…cf170`，与本地 HEAD 及 `git ls-remote origin fix/hang-attribution-1119` **逐字符一致**。

五个 job：`scan-gate` / `build-apk` / `unit-tests` / `lint` / `publish` 全 `success`；publish 的 `Move the rolling tag android-latest` 与 `Publish to Releases` 两步 **skipped**（main 门控，符合预期）。

单测：**9757 行 PASSED，0 真失败**（6 行含 `FAILED` 字样的全是 PASSED 的用例名，如 `slot denied produces FAILED without calling provider PASSED`）。本支 7 条新用例逐条 `grep -cF` **各 1 命中**：

| 用例 | 命中 |
|---|---|
| `the quiet clock restarts when a counted hang episode ends` | 1 |
| `the recovery-stamp probe rejects a source without a working recovery stamp` | 1 |
| `a millisecond count is not read as an http status code` | 1 |
| `the status-code boundary holds on both sides of the digits` | 1 |
| `real http status codes are still attributed to the service` | 1 |
| `the token rule rejects the pre-fix substring vocabulary` | 1 |
| `status codes are matched by the token pattern, not by a substring` | 1 |

scan-gate：`Summary: 115 passed, 0 failed`。日志同时有 `scripts/scan/** untouched in this push — skipping the fixture self-test (~121s)` —— 本支未动门脚本，夹具按机制跳过，**这次的绿不含夹具跑过的证据**（与既往口径一致）。

## 5 变异负控表

见 §4.2（8 臂，含变异内容、判据、红的用例、还原校验）。

## 6 未修与取舍

| ID | 判级 | 为什么不修 |
|---|---|---|
| **F-06** DebugRPC 缺内部 `BuildConfig.DEBUG` | **P3** | 外层 `MinisApp` 已有 `if (BuildConfig.DEBUG)` 才启动 `DebugServer`，且所有客户端（含 loopback）必须带 token（`DebugServer.kt:20-60`）。有守卫的现状。判级台账已注明「永不动」。 |
| F-02 `markHealthyTick()` 未过 `mayAttemptHeal` 门控 | P2 probable | 需要「某调用方在进程启动 10s 内调用 `markHealthyTick` 且遗留 `last_hang_at` 距今 > 10s」两个条件同时成立；现有唯一外部调用点是 `ChatScreen` 的 `LaunchedEffect`。修它会动 `markHealthyTick` 的公开语义（F-01 的自愈路径也走它），风险 > 收益。 |
| F-04 `finalizeLocked` 遇 `CancellationException` 跳过释放 `runningTaskId` | P2 probable | 属 `scheduled/` 域，本支禁碰。已在 §6.1 登记给下游。 |
| F-05 跨存储无事务 | P2 | 同上，`scheduled/` 域。 |

### 6.1 本支发现但按纪律不修的（登记）

1. **`CONNECTION_PATTERNS` 里的 `"ping"` 仍是无词界子串** —— `stopping` / `mapping` / `shipping` 都会命中。同族（F-03）但方向相反：它把非连接文本拉进 CONNECTION。本支只处理了任务书点名的状态码类；`ping` 这一条需要先确认 OkHttp 的 h2 ping 失败文本到底长什么样（是否有 `PING`/`ping failed` 固定形态），属「需要取证再动」，登记不修。
2. **`"expired"` 同时在 `SERVICE_PATTERNS` 与 `CREDENTIAL_PATTERNS`（`expired key`）** —— 因为 credentials 先查，`"token expired"` 走 CREDENTIALS（对），但 `"session expired"` 也走 CREDENTIALS（可能错）。判级 P2，登记。
3. **`SERVICE_PATTERNS` 里的 `"http 4"` / `"http 5"` 仍是无词界子串** —— `"http 4xx"` 命中（对），但理论上 `"http 4"` 也能被 `"http 40"` 之外的怪串命中。实际上 `"http 4"`/`"http 5"` 前缀后必然跟数字，风险极低；且现在状态码有专门的正则臂兜住。登记不修。

### 6.2 判据的边界（自我限制）

- 形状探针看的是**源码文本**，不是编译产物。它们能挡住「调用被删/被移位/被改成写计数」，挡不住「`stampHangRecovery` 内部实现被改成一个空函数」—— 后者由 M3（戳记顺带写计数）与 M4（去掉锁）覆盖了一半，剩下一半（戳记写成常量）无对应臂。**这是形状探针的固有限制**，真机行为需一次长卡顿验证（见 §8）。
- F-03 的样本是我构造的，不是线上日志里抓的。真实 `failureReason` 的措辞由 provider 决定，可能还有本支没覆盖的形态。

## 7 文件清单

```
src/android/app/src/main/java/com/rikkaminis/app/diagnostics/HangDetector.kt      +36
src/android/app/src/main/java/com/rikkaminis/app/diagnostics/FaultAttribution.kt +38 / -2
src/android/app/src/test/java/com/rikkaminis/app/diagnostics/HangDetectorSelfHealTest.kt +122
src/android/app/src/test/java/com/rikkaminis/app/diagnostics/FaultAttributionTest.kt     +166
fix-reports/FIX-2D-1119-7-diag.md                                                本文件
```

合计 4 个源码/测试文件 +362 / −2（不含本报告）。

沙箱装置不入库：`/var/minis/shared/work/r1119/diag-jvm/{run.sh,mutate.py,extract_shim.py,RunTests.java,stub/}`。

## 8 复现

```sh
# 1. 沙箱侧：33 例全绿（含 7 例新增）
sh /var/minis/shared/work/r1119/diag-jvm/run.sh

# 2. 变异负控：8 臂全 RED-PRECISE（要求工作树干净，否则拒绝启动）
python3 /var/minis/shared/work/r1119/diag-jvm/mutate.py

# 3. 云端 CI（分支 push 不触发，必须手动 dispatch）
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
   --workflow build-apk.yml --ref fix/hang-attribution-1119
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs \
   --branch fix/hang-attribution-1119
```

**真机验证（未做，需一次真卡顿）**：
- F-01：制造一次 ≥13s 的主线程阻塞（或等一次真卡顿），恢复后观察 `stall-<date>.log` 的 post-recovery 样本时间戳与 `[T-HANG-DIAG] hang count reset after quiet period` 的时间差 —— 修后应 ≥10s，修前是 ~0.5s。日志里 `hang episode ENDED peak=...` 之后 10s 内不应出现 reset 行。
- F-03：`FaultAttribution` 无生产调用方，无真机可验；只有接上面板后才有观测点。

## 9 CI 结论

**停止节点达成**：run #3293 / `44373325007` @ `06de9d0f8d33bac837cb5724c605c637799cf170` 五 job 全 success，`head_sha` 与分支 tip 逐字符一致，publish 两步 skipped（main 门控）。**未合 main。**

一处流程注记：本报告是在代码提交之后才写完的，因此「代码提交那次 run」的 `head_sha`（`b2252554`）自然不等于最终 tip。按既有口径，对最终 tip 再 dispatch 一次（#3293）而不是把报告塞进代码提交。两次 run 都已记录在 §4.3。
