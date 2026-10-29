# FIX-R5-F3 — 语音会话竞态（R5-F F-F-05，P1）

分支 `fix/r5f-speech` @ `222dafac`（基线 main `35509888`）· CI run **#2916**（in_progress，见 §5）

## 1. 目标

报告 R5-F `C-09`（PARTIAL 残留）→ 判级台账 F-F-05（P1，B9 支）：语音会话竞态。两个独立窗口：

1. `ProviderSpeechRecognitionEngine.start` 的 `captureJob`（`Dispatchers.IO`）内，`listener.onError`/`onReadyForSpeech`/`onRmsDb` 的多个调用点不检查会话存活性，`cancel()`/`selectLocale()` 换会话后旧 job 的迟到回调会污染/杀死新会话；
2. `SpeechRecognitionManager.refreshSupportedLocales` 的异步广播回调（2500 ms 窗口）写 `_supportedLocales`/`_locale` 前不校验引擎身份，切引擎后迟到的系统语言表覆盖当前引擎的表。

这改变什么用户行为：用户切换识别语言（selectLocale 走 cancel+restart）后，旧会话的一条迟到 `onError` 不再把刚启动的新录音直接打死（麦克风按钮无端回到 IDLE、报一次 AudioError）；系统引擎里切到供应商引擎后的 2.5 秒内，语言选择器不再被系统离线语言表污染（该表不属于供应商引擎）。

## 2. 先证（按语义，不按报告行号）

报告行号在真树（基线 `35509888`）逐条核实，机制全部成立，两处细节修正：

| 报告说 | 真树核实 |
|---|---|
| `:145/:158/:164/:187` onError、`:173` onReadyForSpeech、`:182` onRmsDb 均未 `isSessionLive`，仅循环后 `:205` 检查 | **成立**。全文件 13 个 listener 调用点中 11 个在 `captureJob` 内无任何守卫；唯一的 `isSessionLive` 检查在捕获循环结束后的转写交接处（基线 `:205`）。另有两个**报告没点名**的同类点：空音频 `:208`、转写终态 `:267/:269/:286/:298`（转写链内逐候选有 `isSessionLive`，但候选耗尽后的最终 `onError` 只有链外层检查——token 在整个转写期间不会再变，实际等价于已守卫；真正裸奔的是捕获段的 8 个点） |
| `cancel()` 递增 token 立即起新会话时，旧 AudioRecord 释放抛异常 → 旧 job 调 onError → Manager `:263 区` 杀新会话 | **成立，且机制比报告说的更宽**。不止「释放抛异常」：`AudioRecord.read()` 是阻塞调用、不响应 job 取消（`cancel()` 注释自己写着），旧 job 至少还要走完最后一次 read + finally 释放；`selectLocale()` 的 restart 序列是 cancel → startRecord **背靠背**（Manager `:175-179`），新 `start()` 的 `tryBegin()` 在旧 job 还没执行 `finally` 时就可能成功——旧 job 的 `startRecording()`/`read()` 在新会话抢占期间抛出的任何异常都落在守卫之外。`onError` 的杀伤面也确认：Manager listener 的 `onError` 置 `_lastError`、`activeEngine = null`、`_state = IDLE`、`resetLevels()`，`onReadyForSpeech` 置 `RECORDING`、`onRmsDb` 推波形——三个都能污染新会话 |
| Manager `:123-131` 广播回调不校验 `currentEngine() === engine` | **成立**。`fetchSupportedLocales` 回调直接写 `_supportedLocales.value = locales` 并可能改写 `_locale`（fallback 重选）；`currentEngine()` 在回调时重新解析，`selectEngine("provider")` 后即指向 provider 引擎 |
| 判定条件 | 两条路径均可指认：① `selectLocale`（RECORDING 中）或任何外部 cancel+restart 序列；② `init()`/`selectEngine()` 触发的 `refreshSupportedLocales` 在 2500 ms 内返回，期间用户切引擎 |

线程契约补充（报告没提，但它是修法的一部分）：`SystemSpeechRecognitionEngine` 全部回调经 `Handler(Looper.getMainLooper())` 派发（`:45`），接口文档写明 caller 负责向 composer delta-append——而 provider 引擎的 11 个调用点全部在 `Dispatchers.IO` 工作线程**直调** listener。同一接口的两个实现线程契约不一致，provider 侧是偏航。

## 3. 修法（抽了两个纯判据函数，JVM 可测）

**统一投递闸门**（`deliver`）：`start()` 在取得 `myEpoch`/`mySession` 后定义一个闭包：

```kotlin
val deliver: (() -> Unit) -> Unit = { block ->
    mainHandler.post {
        if (!captureDeliverySuperseded(captureGuard.isCurrent(myEpoch), isSessionLive(mySession))) {
            block()
        }
    }
}
```

- 用本文件**已有的**两个失效信号（`CaptureEpochGuard` 单飞守卫 + `sessionToken` 会话令牌），不新造机制；判据收进顶层纯函数 `captureDeliverySuperseded(isCaptureCurrent, isSessionLive)`（真值表钉在 KDoc + 单测）。
- **为什么是 AND-both-stale（丢弃）**：`stop()` 只 forceRelease 捕获旗、故意保留 token（接口契约「Stop capture; a final result is still delivered via Listener」——stop 后还要投递终态）；`cancel()` 两个信号一起动（契约「no further callbacks will fire」）。丢弃条件 = 两信号都已离开本会话，恰好等价于「只有 cancel/shutdown 之后的残影被丢」。
- **谓词在 posted lambda 内部求值**，不在 post 时——cancel 可以落在 post 与 run 之间。
- 11 个调用点全部改走 `deliver { }`：捕获段 8 个（3×AUDIO_ERROR、onReadyForSpeech、onRmsDb、空音频）+ 转写段 3 个（空转写、onFinal、全候选失败两分支）。start() 内 2 个同步配置错误（无模型 / BUSY）保持直调——此时会话尚不存在，无竞态窗口。
- **回调切主线程**：`mainHandler`（`Handler(Looper.getMainLooper())`），与 System 引擎的线程契约对齐；不在 IO 线程直调 UI 面 listener。

**广播回调引擎身份校验**：`refreshSupportedLocales` 异步回调第一行加 `if (localeProbeSuperseded(engine.id, currentEngine()?.id)) return@fetchSupportedLocales`（顶层纯函数，真值表钉死）。判据用 **id 等价**而不是引用相等（`===`）：引擎注册表在 `init()` 重建、id 唯一，id 等价 ⇔ 同一引擎；且用 `currentEngine()` 双侧解析（而非 `=== engine`）——中途降级回退恰好回到被探测引擎时，数据仍描述在用引擎，不算过期。

`selectEngine`/`selectLocale`/`cancelRecording` 未动（它们的语义本来就对）；`SystemSpeechRecognitionEngine`/`TextToSpeechManager` 未碰（任务书禁碰清单）。

## 4. 验证

装置：`/var/minis/shared/work/r5-ef/probe-f3/`（切片器 + JVM 测试 + 形状探针 + 变异臂）+ 仓库侧 `ProviderSpeechDeliveryGateShapeTest`（进 CI）。

1. **JVM 单测 13/13 PASS**：`extract_slice.py` 从两个生产文件**逐字**切出两个判据函数 + `CaptureEpochGuard`（字节级 substring 断言，不手抄），拼进 JVM harness（kotlinc 2.1.0 + junit 4.13.2，`/var/minis/shared/work/jvm-libs/`）。`F3SpeechRaceTest`：两个判据函数全真值表（期望写死字面量）+ 组合测试用**真** epoch 守卫跑真序列——stop 后仍投递终态、cancel 后全哑、cancel→restart 旧哑新活、stop→restart 旧哑新活、单飞守卫仍拒第二次 start。
2. **形状探针 PASS**（词法区间判 owner，**禁用固定字符窗口**）：S1 captureJob 内 11 个 listener 调用点全部在 `deliver {}` lambda 体内（花括号配平定位，注释掩码后匹配——初版被 KDoc 里的 `listener.` 字样打红即此坑）；S2 恰好 2 个同步 pre-session 调用点且文本可指认；S3 deliver 体经 `mainHandler.post`；S4 deliver 体同时携带两个信号过 `captureDeliverySuperseded`；S5 每个 deliver 恰包 1 个调用；M1 manager 回调守卫是**第一条代码语句**且先于每处 `_supportedLocales`/`_locale` 写入；M2 locale 重验体仍在；括号/冲突标记配平。
3. **变异臂 8/8 全红且点名期望判据**：A1 删守卫→S4；A2 onRmsDb 裸调→S1；A3 `mainHandler.post` 换 `run`→S3；A4 删 manager 守卫→M1（写点先于守卫 + 首语句不是守卫）；A5 守卫挪到写之后→M1；J1 判据改 OR→3 例红（含 stop 后投递被误杀）；J2 probe 恒 false→2 例红；J3 probe 取反→4 例红。每臂后 `git checkout --` 复位，复位后基线复绿（13/13 + SHAPE PASS）。
4. **仓库侧形状测试 `ProviderSpeechDeliveryGateShapeTest`（7 tests，进 CI）**：既有 `ProviderSpeechSessionTokenShapeTest`（r3-B11-F05）只按「`isSessionLive` 出现次数 ≥4」钉 token 半边，对「裸 `listener.` 调用绕过闸门」完全不敏感（r3 那次修完，本轮 8 个点仍裸奔即是证明）。本类用同一家法（注释掩码 + 花括号配平 + 在测负控）钉闸门半边：调用点全覆盖、`mainHandler.post`、双信号、谓词在 posted lambda 内求值、真判据真值表（直调 internal 函数，非正则推导）、locale 回调身份守卫先于写入；三条负控（裸调用 / 无主线程派发 / 无身份守卫）保证探针不会在空壳实现上放行。本地以切片产物 + 真源文件实跑 **OK (7 tests)**。
5. **语法级整文件检查**：生产两文件 + 接口文件 kotlinc 全量报错全部为 `unresolved reference`（android.* / 协程 / 依赖面，本沙箱无 android.jar，预期内）；`expecting/unexpected/Unclosed/syntax` 类 0——本次新增形态（Java SAM `post(Runnable)` 接收 `() -> Unit` 尾随 lambda、函数型变量 `(() -> Unit) -> Unit`、多行 `deliver { }`）已在最小等价片段（javac stub Handler + Probe.kt）实编译实运行验证。CI 的 gradle 全量编译是最终裁决。

## 5. CI

**Run #1（实现提交 `222dafac`）** — `gh-actions-dispatch --workflow build-apk.yml --ref fix/r5f-speech`，run `40885967560` **#2916**，13:52:27Z → 14:07:29Z（~15 min）**completed success**；`head_sha 222dafacceabf5aacdd75476daf8481832022a91` 与本地 HEAD **逐字符一致**。日志（27 个 job 文件合并 3,308,681 B，NUL 0）：`Summary: 113 passed, 0 failed` 恰 1 + 6 个分片（16/18/19/25/30/44）全 0 failed；`ALL 349 CASES PASS` ×2；`e: ` 0；真 FAILED 0（8 条含 FAILED 的全是测试名子串 `... FAILED ... PASSED`）；同族既有门 `ProviderSpeechSessionTokenShapeTest` 4 条逐条 PASSED（token 半边未被打断）。

**Run #2（含仓库侧闸门测试 `335bfb59`）** — 14:22:59Z dispatch → 14:42:23Z **completed success**（run `40890900311` **#2926**，~19.5 min）；`head_sha 335bfb5977f7a62a1a99e52f36bf7eb21138f2ef` 与本地 HEAD **逐字符一致**。日志（27 个 job 文件合并 3,310,931 B，NUL 0）：`Summary: 113 passed, 0 failed` ×2；`ALL 349 CASES PASS` ×2；`e: ` 0；真 FAILED 0（8 条含 FAILED 的全是测试名子串）；**`ProviderSpeechDeliveryGateShapeTest` 7 条逐条 PASSED**（含 3 条负控）。

**停止节点达成**：分支 CI 绿 + head_sha 逐字符一致（run #2 @ `335bfb59`）。报告提交在绿之后（报告 commit 在 tip），`git diff --name-only <绿sha>..HEAD` 应仅含 fix-reports（收口时已复核）。

## 6. 残留与登记（同族顺带发现，只登记不修）

- **R1（P2，登记）**：语音输入全链路在 main 上**尚无生产调用点**——`rg -n "startRecording" src/android/app/src/main` 除 speech/ 包自身外 0 命中，`SpeechRecognitionManager` 仅被 `MinisApp.init` 引用，无 `availableEngines`/`activeEngine`/state 读方。这与同包 F-01/F-02 的「不可达」判词同源（UI 未接线）。修法价值：UI 接线当天这条竞态就会咬人，而闸门 + 仓库侧形状门自此成为该文件的长期契约。触发条件：语音输入 UI 接线（届时补真机复验 R5）。
- **R2（P2，登记）**：`transcribeJob` 的逐候选循环里 `isSessionLive` 检查在候选间隙，token 在转写期间不变所以实际等价已守卫——但它是「守卫靠外部不变量」而非「守卫在投递点」的形状。若未来有人在转写循环内加取消点，需把该循环的投递也走本支闸门模式（本支 deliver 已覆盖其终态调用）。触发条件：转写链引入会中取消/换会话语义。
- **R3（P2，登记）**：`Manager.onError` 对 `activeEngine` 的置空是共享状态写，本支把「迟到回调」堵在引擎侧；若未来第二个引擎实现再漏守卫，同一污染路径复现。治本 = Manager listener 侧也带会话代际（每个 startRecording 一代），属跨引擎 API 面改动，超本支半径。触发条件：新增第三个引擎实现或再次出现迟到回调事故。
- **R4（P3）**：`shutdown()` 在生产无调用点（KDoc 自认），引擎 scope 的释放依赖进程死亡。既有事实，不属本报告面。
- **R5（观测，P1 级）**：真机未验。本支证据 = JVM 判据 + 形状（本地 + 仓库）+ CI 编译级 + CI 单测；「切换识别语言不再打断新录音」的真机验证留给语音 UI 接线后的真机会话（`adb logcat | grep -E "ProviderASR|SpeechManager"` 看状态流序列）。

## 7. 半径

生产 2 文件 + 测试 1 文件 + 报告 1 文件：

| 文件 | 变更 |
|---|---|
| `speech/ProviderSpeechRecognitionEngine.kt` | 本支独占（闸门 + 11 个调用点 + 纯判据 + 主线程派发） |
| `speech/SpeechRecognitionManager.kt` | 本支独占（locale 广播身份守卫 + 纯判据） |
| `src/test/.../speech/ProviderSpeechDeliveryGateShapeTest.kt` | 新增（7 例：形状 + 真值表 + 三条负控） |

`scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**`、`TextToSpeechManager.kt`、`SystemSpeechRecognitionEngine.kt`、既有测试类 零触碰；`git diff --name-only 35509888 HEAD` = 恰上述 3 个非报告文件。门 113 / 夹具 349 不变（本支不动门脚本，全量验证由 CI 承担，本地按负载纪律只跑窄装置）。

## 8. 停止节点

分支 CI 绿 + head_sha 逐字符一致 = 停止节点。不合 main（起手权在用户）。报告提交后 `git diff --name-only <绿sha>..HEAD` 非 fix-reports 差异应为空（收口时复核回填）。
