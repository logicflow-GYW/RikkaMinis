# FIX-META1028-E3：终端族五条 —— 清洗顺序 / 停滞判据 / 超时丢输出 / 广播时机 / guest tmp 顺序

- 分支：`fix/meta1028-e3-terminal`
- 基线：`bfb5b0e6`（main）
- 来源：B04 沙箱报告（`shared/work/meta-audit/reports/r2/Report__2__1.md`）F-08 / F-09 / F-10 / F-13 / F-14
- 改动面：3 生产文件 + 4 新测试文件
  - `sandbox/TerminalSanitizer.kt` +53/−12
  - `sandbox/PersistentShell.kt` +155/−45
  - `sandbox/TerminalSession.kt` +289/−47
  - 新测试：`TrackedTerminalSanitizerTest.kt`（16 例）、`TerminalSessionEnvScriptTest.kt`（13 例）、
    `TrackedStallAndTimeoutTest.kt`（9 例）、`TrackedShellDecisionShapeTest.kt`（11 例）
- `scripts/scan/**` **零触碰**；`RootfsManager.kt` / `PRootKernel.kt` / `ExecutionCoordinator.kt` /
  `GuestTmpSweepPlan.kt` **未改**（D3 支独占）
- 门 113 / 夹具 349 基线不变（本支不跑全量 scan —— 批次纪律）

> **⚠️ 未做真实终端验证**：按任务书硬约束，本支**没有**真跑终端 / 会话实验（reset、真实 shell 长驻、
> 真实清扫都没做）。所有结论来自「读真码 + 纯函数 JVM 测试 + 源码形状断言 + 变异臂」。
> 真机行为（PTY 广播的实际落地、`/proc` 深度语义、CR 折叠在真实终端观感）**未验证**。

---

## 1. 先证（五条各自回真树核实）

报告转写质量高（96-100%），但**行号基本对不上**，语义也不完全成立 —— 三条要改判（见下）。

### ① F-08 —— 报告说「清洗器顺序/边界问题导致特定序列残留」：**成立，且报告低估了**

报告原文（转写）：`TerminalSanitizer.kt:22-24, 34-41`。

真码（改前 `bfb5b0e6`）：

```kotlin
private val ANSI_REGEX = Regex(
    """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
)

fun sanitize(raw: String): String {
    val crFolded = foldCarriageReturns(raw)      // ← 先折叠
    val stripped = ANSI_REGEX.replace(crFolded, "") // ← 后剥离
    val cleaned = stripped.filter { ... }
```

三处独立缺陷，全部在**修前真码**上复现（探针 `probe-e3/F08Probe.kt`，读数 `F08_PROBE=FAIL(4)`）：

| # | 缺陷 | 症状（修前实测） |
|---|---|---|
| a | OSC body `[^\x07]*` **贪婪**（含 ESC 与换行） | 两个 OSC 8 超链接之间的正文被整段删掉：`before … IMPORTANT_LINK_TEXT … after` → `before  after` |
| b | **CR 折叠按字符下标算列**，而转义字节还没剥 | 彩色进度行 `ESC[33m…ESC[0m\rDone!          ` → `Done!          g 50%`（旧行尾巴残留）；短覆盖 `ESC[33mABC\rOK` → `OK33mABC`（转义头被覆盖后剩下的碎片） |
| c | CSI 缺 DEC 私有模式前缀 `?` | `ESC[?25l` / `ESC[?25h`（top / tqdm / npm / pip 的隐藏光标）不被匹配，输出里留下字面量 `[?25l` |

报告只笼统说「顺序/边界问题」，没指出 b 的机制（不可见字节占列），也没指出 c。

### ② F-09 —— 报告说「stall 守卫用 output.length 判进度」：**成立，机制比报告说的更狠**

真码（改前）：`PersistentShell.kt` 的 monitor 循环里

```kotlin
if (cb.output.length != stall.lastOutputLen) { stall.lastOutputLen = cb.output.length; stall.lastProgressMs = now }
```

`cb.output` 由 `IncrementalUtf8Budget` 填充，而该预算**到达 `shellOutputKb` 上限后每块都返回
`appendedUtf8Bytes = 0`** ⇒ `output.length` **冻结**。于是「仍在刷输出的命令」与「卡死的命令」在守卫眼里完全一样 ⇒
180 s 后 SIGINT、再 SIGKILL，**而 UI 还在滚它的输出**。

### ③ F-10 —— 报告说「超时丢弃已产生的输出」：**成立**

真码（改前）：timeout 分支 `CommandResult("[Command timed out after ${timeout/1000}s]", TIMEOUT_EXIT_CODE)` ——
**整段已捕获输出被替换成一行通知**。而同一函数的 stall 分支是 `cb.output.toString() + stallMessage(...)`
（保留输出 + 追加通知）—— 两条失败路径行为不一致，本身就是证据。

### ④ F-13 —— 报告说「环境变量注入转义不完整，含引号的值破坏 shell 行」：**部分证伪**

报告原文（转写）：`TerminalSession.kt:52-71, 469-482`，声称 POSIX 单引号转义不完整。

真码（改前）：

```kotlin
private fun applyTimezone(tz: String) {
    val line = "export TZ='${tz.replace("'", "'\\''")}'\r"   // ← 已经是 POSIX 正典
    sendRawBytes(...)
}
```

**转义本来就是对的**（`'\''` 是 POSIX 唯一正确写法），报告这一半**证伪**。

但读真码时发现了报告没说的、真正会破坏 shell 行的那一半：**广播是「无条件写进 PTY」**。
`applyEnvMap` 把六行 `export …\r` 直接打进终端，而终端当时可能在：

- 敲了一半的命令（`git commit -m "…`）：export 文本被**接到那一行后面**，随后的 `\r` **把拼接后的整行提交了** ——
  用户没写过的命令被执行了；
- `vim` / `nano` / REPL：export 行是**键盘输入**，落进文件缓冲区或解释器。

转义只解决「值里的引号」，不解决「什么时候打」。这才是 F-13 的真缺陷。修法按此重定向（§2.4）。

### ⑤ F-14 —— 报告说「三处环境/路径约定不一致」：**成立，但落点与报告不同**

报告原文：`PersistentShell.kt:382-383` vs `PRootKernel.kt:193-236` / `RootfsManager.kt:842-855`。

三方约定（改前真码）：

| 入口 | TMPDIR 是什么 | 与进程级 env 的先后 |
|---|---|---|
| `PersistentShell.startProcess` | `/tmp`（**guest**） | **在 `customEnvironment` 合并之前** ⇒ 合并可覆盖它 |
| `TerminalSession.buildTermuxEnv` | **根本没有这个 pin**（`rg -n TMPDIR` 全仓只有 PersistentShell 一处） | — |
| `RootfsManager.prootLoaderEnv` | 只给 `PROOT_TMP_DIR`（host 侧，给 proot 进程自己） | — |

`RootfsManager` 那一侧**不是分叉**，是另一件事（host 侧临时目录），报告把它当第三个分叉是误读。
真正的分叉是**两处**：agent shell 有 pin 但位置会输，交互式 PTY **根本没有 pin**。
交互式 PTY 之所以没有，是因为 Termux 用**显式 env 数组** exec PRoot，不是继承 `ProcessBuilder` 环境 ——
guest 看到什么取决于 Termux 的 JNI，不取决于本仓任何一行代码。⇒ 修法（§2.5）。

---

## 2. 修法（逐条）

### 2.1 F-08：改正则 + 换 pass 顺序

- **正则**：CSI 参数类换成 ECMA-48 全集 `[\x30-\x3F]*`（含 `?`），补中间字节 `[\x20-\x2F]*`，终字节 `[\x40-\x7E]`；
  OSC body 改为 `[^\x07\x1B]*`（**不能吃 ESC**，否则还是贪婪到下一个终止符）。
- **pass 顺序**：**先剥 ANSI，再折叠 CR**。CR 折叠的语义是「终端画布上的列覆盖」，
  而转义字节在真实终端里**不占列**；先剥才是在真实终端语义上折叠。
- 幂等性：剥完再折，折完没有 ANSI 残留，第二次 pass 无事可做 ⇒ 天然幂等（测试逐例断言两遍相等）。

### 2.2 F-09：进度信号换成「收到的字符数」

`CommandCallback` 新增 `AtomicLong receivedChars` + `totalCharsReceived`；`appendOutput` 变成**成员方法**
（原扩展函数删除），在**预算裁剪之前**累加接收数。守卫比较 `cb.totalCharsReceived` 而非 `cb.output.length`。
`AtomicLong` 而非 `Long`：读循环线程写、monitor 协程读。

### 2.3 F-10：超时保留已捕获输出

新增纯函数 `internalTimeoutOutput(captured, timeoutMs)`：有输出 ⇒ `captured + "\n" + notice`；
无输出 ⇒ **逐字节等于**历史单行结果（协调器按文本识别超时，且刻意不给 124 加 `(exit code: N)` 尾巴）。

### 2.4 F-13：广播延后到「确定空闲的提示符」

- 新增 `pendingEnv`（`LinkedHashMap`，同 key 后写覆盖）+ 单泵协程；
- `internalTerminalPromptState(pid) { childrenOf }`：**纯函数**，判定 PTY 状态
  （`IDLE_AT_PROMPT` / `FOREGROUND_RUNNING` / `UNKNOWN`）；深度 1 = guest shell，深度 2+ = shell 的子进程；
  **判不出来一律 `UNKNOWN`，调用方按「忙」处理**（fail closed，绝不盲打）；
- 空闲时先发 **Ctrl-U (0x15)** 丢掉用户敲了一半的行，再发 export 块；
- 超过 `ENV_BROADCAST_MAX_DEFER_MS`（10 min）仍不空闲 ⇒ **丢弃并 `Log.w`**（不往 TUI 里打字更重要；
  下次 `start()` 会从单一真源重新推导 TZ/代理）；
- 转义统一到共享的 `internalPosixSingleQuote`（agent shell 与 PTY 两个写者现在用**同一个函数**，
  差别只在行尾 `\n` vs `\r`）；
- 新增 `internalStripControlChars`：值里的 C0/DEL 先剥掉 —— 每行以 `\r` 提交，值里的 `\n` 会提交半行
  `export KEY='…` 并把 shell 留在续行提示符。

### 2.5 F-14：guest tmp 的 pin 位置 + 补齐 PTY 入口

- `PersistentShell.startProcess`：把 `env["TMPDIR"]/["TMP"] = "/tmp"` **移到 `customEnvironment` 合并之后**
  （同时把 `MINIS_CHAT_SESSION_ID` 也放到合并之后 —— 「后写胜」是本仓其它 PRoot 入口已有的约定）；
- `TerminalSession.buildTermuxEnv`：**补上同一对 pin**，位置在 `customEnvironment` 合并之后、
  用户 env 仓库之前（用户显式设的 guest `TMPDIR` 仍然可以赢）。

---

## 3. 证据

### 3.1 纯函数 JVM 测试（真实测试文件，桌面 JVM）

`probe-e3/run-tests.sh` —— 真实 `TerminalSanitizer.kt` 直接编译；Android 依赖的三个 helper
（`internalPosixSingleQuote` / `internalBuildEnvironmentScript` / `internalTerminalPromptState` /
`internalBuildTerminalEnvScript` / `internalStripControlChars` / `IncrementalUtf8Budget` /
`internalTruncateOutputDetailed` / `TruncateResult`）用 `probe-e3/slice_*.py` **逐字切片**自真树
（切片脚本断言签名唯一 + 关键体内字面量存在，切片不可能与真码悄悄漂移）。

```
JUnit 4.13.2 ................................................... 83 tests
OK (83 tests)
```

83 = 新增 49（16 + 13 + 9 + 11）+ 既有 `TerminalSanitizerTest` 34。

### 3.2 变异臂（每条修复都必须先红过）

`probe-e3/run_single_arm.py`：**一臂一进程**（上一批 6 臂一进程被沙箱杀掉两次并留下 stale `.armbak`，
让下一次 baseline 假红），镜像树 + `cwd` 指到镜像 ⇒ 形状测试读的是**被变异的副本**。

| 臂 | 回退的决定 | 红数 | 判定 |
|---|---|---|---|
| M1 | F-08 改前 ANSI 正则 | 5 | BITE |
| M2 | F-08 pass 顺序改回（先折叠） | 3 | BITE |
| M3 | F-09 守卫改回比较 `output.length` | 2 | BITE |
| M4 | F-10 超时分支改回丢弃输出 | 2 | BITE |
| M5 | F-13 PTY 写者改用反斜杠转义 | 3 | BITE |
| M6 | F-13 `UNKNOWN` 改判成 `IDLE_AT_PROMPT`（fail-open 方向） | 1 | BITE |
| M7 | F-14 guest tmp pin 挪回合并之前 | 1 | BITE |

**7/7 BITE**。每臂只红钉住它自己的用例（期望数写在 `mutate.py` 里，不符即判 DIFF 并让运行失败）。

### 3.3 类型检查

`probe-e3/run-typecheck.sh`：真实 `TerminalSession.kt` + 手写 Android/Termux/sandbox 桩 + **逐字切片**的
`internalPosixSingleQuote` ⇒ `TYPECHECK CLEAN`（真实 `PersistentShell.kt` 的编译面由 CI 覆盖）。

### 3.4 修前对照（形状两态）

- F-08：`F08_PROBE=FAIL(4)` → `F08_PROBE=PASS`（同一探针、同一夹具）。
- F-09/F-10/F-14：形状断言的正控（锚点在）与负控（改前形态必须**不**满足）成对存在；
  M3/M4/M7 三臂即负控的机械证据。

---

## 4. 影响面 / 行为变化（改了什么用户能看见）

| 改动 | 用户可见的变化 |
|---|---|
| F-08 | 终端输出里的 OSC 8 链接文本**不再被吞**；彩色进度行不再残留旧尾巴；`[?25l` 不再出现在输出里 |
| F-09 | 输出超过 `shellOutputKb` 仍在流动的命令**不再被 180 s 停滞守卫杀掉** |
| F-10 | 超时命令**保留已打印的内容**，末尾追加超时通知（原来只剩一行） |
| F-13 | 改时区 / 改代理**不再往正在跑前台程序的终端里打字**；空闲提示符下先清掉用户敲了一半的行再注入 |
| F-14 | guest 临时目录恒为 `/tmp`（交互式终端此前可能继承 host 路径 ⇒ rootfs 里永久垃圾） |

---

## 5. 残留（未在本支修，按半径纪律登记）

| ID | 残留 | 为什么不在本支修 |
|---|---|---|
| R1 | `RootfsManager` 侧给 host 侧子进程（`apk` 等）**没有** guest `/tmp` pin | 那是 host 侧目录，与 guest 工具链无关；任务书明令不改该文件（D3 支独占） |
| R2 | 已弃用的 `ShellExecutor`（`@Deprecated`，仅 androidTest 引用）同样无 guest tmp pin | 同上，且该类已弃用 |
| R3 | F-13 的广播延后最多 10 min，超时丢弃 | 丢的是「终端里的 TZ/代理更新」，下次 `start()` 会从单一真源重推；真机行为**未验证** |
| R4 | F-13 的「空闲提示符」判定依赖 `/proc/<pid>/task/<tid>/children`，**未在真机验证** | 任务书禁真跑终端；这是本条最需要真机复验的地方 |
| R5 | F-08 的 ANSI 正则仍不覆盖 DCS / APC / 8-bit CSI（`ESC _`…`ST`、`ESC ]` 的变体） | 当前无产出这些序列的工具；扩大正则面会引入误吃正文的风险，收益不抵 |
| R6 | CR 折叠仍按 **UTF-16 code unit** 计列，代理对（emoji / 组合字符）在终端里的实际列宽不同 | 与终端真实观感有关，需真机比对；改动面波及所有输出，收益/风险不划算 |
| R7 | `PersistentShell` 的 `MINIS_CHAT_SESSION_ID` 也被挪到合并之后 | 这是 F-14 的同一排序论证的顺带收敛，行为等价（该键在合并后原本就会被覆盖成同一个值）；已由 `claim_release_guard` 的既有约定佐证 |

---

## 6. 门 / CI

- `scripts/scan/**` **零触碰**（本支不建新门；`storage_budget_check` 的 C3 判据读
  `PersistentShell.startProcess` 里的 `TMPDIR`/`TMP`/`PROOT_TMP_DIR` 赋值 —— 改动后仍全部存在且形态不变，
  语义未变）。
- 本支**未跑**全量 scan / test_scan / gradle（批次纪律：多会话并行，只跑自家窄验证）。
- CI：`build-apk` 一次 dispatch；判读见 §7。

---

## 7. CI

见提交信息与后续回填（本节在 CI 绿后补写 run id / head_sha / 日志读数）。

---

## 8. 复现（探针）

`shared/work/meta-audit-1028/probe-e3/`：

| 文件 | 用途 |
|---|---|
| `run-f08.sh` / `F08Probe.kt` | F-08 修前/修后对照（`F08_PROBE=FAIL(4)` → `PASS`） |
| `run-tests.sh` | 真实测试文件的桌面 JVM 跑法（83 例） |
| `slice_*.py` / `slicelib.py` | 逐字切片（断言唯一性 + 关键字面量，防漂移） |
| `mutate.py` / `run_single_arm.py` | 7 个变异臂（一臂一进程 + 镜像树） |
| `run-typecheck.sh` | 真实 `TerminalSession.kt` + 桩的类型检查 |
| `stubs/` | Android / Termux / sandbox 单例桩（**不进仓库**） |
