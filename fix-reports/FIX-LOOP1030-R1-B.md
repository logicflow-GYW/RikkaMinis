# FIX-LOOP1030-R1-B — 压力上报按采样点分流 + 标出真越线

**分支** `fix/symptom-a-report-source`（叠在 `fix/symptom-a-diagnostics` 上，同文件面）｜**基线** origin/main `569e77a3`

## 1. 症状与证据

`MemoryPressureGate` 的开发日志四天 **3881 行**，其中几乎每一行都以
`admission delayed 500ms` / `admission throttled (...)` 结尾——但真正发生在请求上的
准入动作 **≤29 行**。审计据此把「内存门升到 3875 次」当成事实，进而怀疑闸门本身
在抖动。实际是**观测口径错位**：

- `pressureListener: (MemoryPressureLevel, Long) -> Unit`，没有来源信息；
- 唯一的调用点 `notify(level)` 被两个完全不同的采样点共用：
  - `sampleAndNotify()` —— 1s 治理器 tick，**只观测，不产生任何准入/回收动作**；
  - `SessionConcurrencyManager.acquireSlot` —— 准入路径，这里的 500ms/2s 等待和
    回收才真的发生在用户请求上；
- 组装层（`MinisApp`）按 `level` 决定文案，于是 tick 行也被写成"准入延迟"。

第二种同形：ELEVATED 行里 **96.2% 是滞回带续报**（anon 300–450，档位没变），与
「刚越过 450」在同一条日志通道里无法区分——要调阈值的人分不清"事件"和"存量"。

## 2. 根因

一条日志通道承载两种语义（周期观测 / 准入门控），文案按最常见的那种（准入）写死。
读者按字面理解 → 门控计数被高估两个数量级。判据缺失：写点没有携带「为什么写」。

## 3. 修法

- `pressureListener: (level, anonMb, src, crossed) -> Unit`；
  `notify(level, src)` 的 `src` **必填**（无默认值，避免新调用点静默沿用错口径）。
- `crossed = level != lastNotifiedLevel`（与 `tierListener` 的跃迁判定同一次比较，单源）。
- 组装层按 src 分流：
  - `SRC_TICK` → `observe-only tick sample (no admission effect)`
  - 准入 → 原两条文案（逐字保留，grep 不变）
  - 行尾追加 `src=$src crossed=$crossed`。
- 准入调用点传**已有的** local `src`（`agent-admission` / `shell-admission`），
  不新增第三套命名。

判据：任何"门控生效"的读数必须能从 `src != tick` 的行里取；单个 tick 行不再声称
自己延迟了任何请求。

## 4. 验证

| 面 | 读数 |
|---|---|
| 探针（真源逐字复制 `MemoryPressureGate.kt`） | `MemoryPressureGateTest` **24/24 PASS** |
| 组装口径形状（读 `MinisApp.kt`） | `MemoryPressureGateReportSourceTest` **4/4 PASS** |
| 变异臂 | **4/4 RED-AS-EXPECTED**：C1 监听器丢来源→1 红；C2 `crossed` 恒真→1 红；C3 组装丢 tick 分支→1 红；C4 回到按 level 决定→2 红 |
| 本地门 | `scan.sh` **113 passed, 0 failed** |
| 本地夹具 | `test_scan.py` **ALL 349 CASES PASS** |
| CI | 见 §4.1 |

### 4.1 CI

- **run `40987299653`**（`fix/symptom-a-report-source`）**success 22 min 30 s**（01:41:11Z → 02:03:41Z），attempt 1，`workflow_dispatch`。
- `head_sha 5a0bb8ba2b710bd7f374fdcf91a60e5d410e1b75` 与本地 HEAD **逐字符一致**（SHA-MATCH）。
- 27 步 = **25 success + 2 skipped**（`Move the rolling tag android-latest` / `Publish to Releases`，均 main 门控，分支上必 skip）。
- job 日志 3,355,532 B / **NUL 0**：`Summary: 113 passed, 0 failed` ×2、`ALL 349 CASES PASS` ×2、`^e: file` **0**、真 ` FAILED$` **0**（含 FAILED 的行全是 PASSED 用例名子串）。
- 本支用例两遍全 PASSED：`MemoryPressureGateReportSourceTest` **4 例**（含负控 `the listener no longer decides on level alone`）、`MemoryPressureGateTest` **10 例**（含 `notify carries the sampling source and the crossing flag`）。
- **首轮红（诚实记录）**：`40985451287` failure @2.4 min，`e: file … SessionConcurrencyManager.kt:98:45 Unresolved reference 'src'` —— `src = "agent-admission"` 里的 `src` 是**命名实参**不是局部变量，我在 notify 处误当变量传。修 = 两侧统一走新常量 `MemoryPressureGate.SRC_ADMISSION`（顺带把影子采样的字面量也收进来，单一事实源）。同轮补修 `SessionConcurrencyManagerLockTest.kt:44` 里漏改的两参监听器桩——**教训：改 lambda 签名后要 `rg -l '<字段名>' src/android/app/src/test` 全 test 树扫，不能只看包内同名文件**。

## 5. 残留（带触发条件）

- **R1 行数不变**：仍每 tick 一行，只是口径正确。｜触发条件：单日 `MemoryPressureGate`
  行数 > 5000 时考虑 5s 聚合｜方案：按 (src, level, crossed) 聚合，只在跃迁或换档时打行。
- **R2 `crossed` 只反映相对上一次通知的净变化**：两次 tick 之间跨过多次档位时只能
  看到首尾。｜触发条件：需要逐次跃迁序列时｜方案：在 `internalNextTier` 里把每次
  跃迁落 `MemorySpikeRecorder`（已有通道）。
- **R3 准入来源沿用既有 local `src`**：新增第三种准入来源时必须显式传新值，否则
  仍归到 agent-admission。｜触发条件：新增准入调用点｜方案：把 `SRC_*` 收进
  `MemoryPressureGate` 并在枚举上做穷举。
- **R4 真机未验**：全部证据 = JVM 级（真源切片）+ 源码形状 + CI 编译级。
  ｜触发条件：下一次自然出现 ELEVATED/CRITICAL｜方案：`grep 'src=' ` 看 tick/准入
  两类的真实比例，确认与 29/3881 同量级。
