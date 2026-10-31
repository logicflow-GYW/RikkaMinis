# FIX-IME-DICTATION-GUARD — 拖动消息列表不再掐断输入法语音听写

- 分支：`fix/ime-dictation-guard`
- 基线：main `5f808ae8`
- 提交：`9c201f61`（守卫）+ `2c1b29d5`（API 29 版本门 + 判据加固）
- CI：`Build RikkaMinis Android APK` run `41197434892` @ `2c1b29d5`（见 §4）

## 1. 症状

用**输入法自带的语音听写**（Gboard / 微信 / 豆包输入法的麦克风键，不是 app 内的语音功能）在对话框里说话，说着说着会突然中断——用户的原话是"比其他应用更容易断"。典型场景：agent 正在流式输出，用户一边说一边向上滑动看自己回复的内容，滑动一次听写就结束了。

## 2. 根因

已诊断，见 `/var/minis/shared/upstream-1031/VOICE-IME-DIAGNOSIS.md` ①（置信度高）。`ui/chat/ChatScreen.kt` 的 `LaunchedEffect(isUserDragging)` 在**用户手指拖动消息列表**时执行 `keyboardController?.hide()` + `focusManager.clearFocus()`：

```kotlin
LaunchedEffect(isUserDragging) {
    if (isUserDragging) {
        keyboardController?.hide()      // ← 收起键盘 = 结束 IME 的语音会话
        focusManager.clearFocus()
    }
}
```

关键机制：**IME 的语音听写跑在键盘进程里，键盘被收起它的录音会话就被终止**。上游 OpenMinis 在同一处代码上出过 issue #472 并已修复（`ChatScreen.kt:3788-3807`），我们分叉在该修复之前——修复前后 `activeRecordingConfigurations` 在本仓 0 命中。

本支只搬这一条。诊断里的 ②（IME burst flush 与 inputText mirror 的竞态，表现为"吞字"）不属本支：它要动 `ChatInputArea.kt`（本批另一支的独占文件），且是独立契约。

## 3. 改动面

```
 src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt         | 13 +
 src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ImeDictation.kt       | 38 +
 src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ImeDictationGuardWiringTest.kt | 294 +
 3 files changed, 345 insertions(+), 0 deletions(-)
```

### 3.1 新文件 `ui/chat/ImeDictation.kt`

`internal object ImeDictation { fun recordingActive(context): Boolean }`，语义照抄上游 `ReadingAnchor.kt:178-195`：Android 没有"IME 正在听写"的信号，但 API 29 起每个应用都能看到活跃录音配置（`AudioManager.activeRecordingConfigurations`，其他应用的身份被匿名化）；键盘开着时列表非空即 IME 在录。

**与上游的一处刻意偏离**：加 `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false`。本仓 `minSdk = 26`，`activeRecordingConfigurations` 是 API 29 才有的属性——上游 minSdk 更高所以它的副本没有这道门。没有门的话 Android 8/9 上每次拖动都会抛一次 `NoSuchMethodError`（被 `runCatching` 吞掉，功能不受影响，但白白抛异常）。KDoc 里写明了偏离原因与 #472 出处。

失败方向是**打开**的（fail-open）：`runCatching{…}.getOrDefault(false)` —— 查询失败读作"没在录音"，即回到修复前的行为。守卫绝不能把键盘锁在屏幕上。

### 3.2 `ChatScreen.kt`：`LaunchedEffect(isUserDragging)` 加守卫（+13 行）

```kotlin
if (ImeDictation.recordingActive(context)) {
    AppLogger.debug("ScrollFollow", "drag keeps the keyboard: audio recording active (IME voice typing)")
    return@LaunchedEffect
}
keyboardController?.hide()
focusManager.clearFocus()
```

`context` 用的是 `ChatScreen` 作用域里既有的 `val context = LocalContext.current`（第 209 行，与目标块同属一个 composable，期间无嵌套 `@Composable`）。注释里标了 `[fix/ime-dictation-guard]` 与上游 #472 / FIX-261022-07 出处；`ScrollFollow` 日志让真机回报时能直接判定守卫是否命中。

**刻意不动**（诊断 ③ 与任务书禁改清单一致）：`anyOverlayOpen` / `toolDetailIdForIme` 的 hide、发送后的 hide、drawer 打开的 hide —— 面板让位是刻意设计，保持现状。

### 3.3 `ImeDictationGuardWiringTest.kt`（JVM，6 例）

Compose 状态与 `AudioManager` 在纯 JVM 里驱动不了，所以按仓内 `ChatSlashMcpWiringTest` 的惯例读源码断言形状。三条判据：

| 判据 | 钉的是什么 | 为什么不能更弱 |
|---|---|---|
| ① 守卫在 hide **之前**、且 `return@LaunchedEffect` 真的存在、日志走 `ScrollFollow` | 顺序 + 早退 + 可观测 | 守卫放在 hide 之后 = 键盘已经收了，守卫只剩一行日志（判据 A2 臂证明能抓到） |
| ② `ChatScreen.kt` 里 `ImeDictation.recordingActive` 调用点**恰好 1 处** | 契约的宽度 | 新增第二条 hide 路径时若接了守卫、或留了杂散探针，都需要有人重新陈述契约 |
| ③ `ImeDictation` 真的读 `activeRecordingConfigurations`、从 `AUDIO_SERVICE` 取 `AudioManager`、`getOrDefault(false)` 是 fail-open、且带 API 29 门 | 守卫不能被掏空 | 只有 ①② 的话，`fun recordingActive() = false` 一样全绿 |

每条判据都配一条**负控**（把缺陷塞回真源码的副本，断言同一判据转红），否则删掉分支的测试会以绿色示人。源码扫描前先把注释与字符串**掩成空格并保留偏移**（`skeleton()`），这样 KDoc 里写着 `ImeDictation.recordingActive` 不会满足断言，`"ScrollFollow"` 这类字面量只有真代码能提供。

## 4. 验证证据

### 4.1 本地门（最终树 `2c1b29d5`）

- `sh scripts/scan/scan.sh` → **Summary: 113 passed, 0 failed**
- `python3 scripts/scan/test_scan.py`：
  - 在 `9c201f61` 上完整跑通 → **ALL 349 CASES PASS**（`EXIT=0`）。
  - 在 `2c1b29d5` 上长跑进程被沙箱**静默杀掉两次**（无 exit code、无 traceback，叙事见 §4.4）：被杀时 **263 ✅ / 0 ❌**，停在 `test_da1023_gates` 中途；随后按组单独复跑剩余部分，全部 0 失败：

    | 组 | 用例 | 失败 |
    |---|---|---|
    | `test_da1023_gates` | 22 | 0 |
    | `test_da1024_gates` | 8 | 0 |
    | `test_da1025_gates` | 8 | 0 |
    | `test_w1027_storage_budget` | 2 | 0 |
    | `test_da1026_gates` | 2 | 0 |
    | `test_auto_approve_gates` | 2 | 0 |
    | `test_primitive_selftests` | 3 | 0 |
    | `test_da1026_arch_gates` | 4 | 0 |
    | `test_trace_step_tag` | 4 | 0 |
    | `test_dv16_chat_ui_interaction` | 7 | 0 |
    | `test_da1017_external_fixtures` | 4 | 0 |

  - 权威证据仍是 CI 自己那一轮：同一 run 里打出 `✅ ALL 349 CASES PASS`，跑的就是 `2c1b29d5` 这棵树（§4.3）。
  - 最后剩的 `test_real_repo` 组（20 个门对真树）在本地同样被静默杀掉；它的等价覆盖有两条独立来源——本地 `scan.sh` **113/0**，以及 CI 日志里 **21 条 `on real repo exits 0`**。
- 未新增/修改任何 `scripts/scan/**` 门脚本（本批冻结），门数与夹具数与基线一致。

### 4.2 JVM 形状测试 + 变异臂

`java -cp … org.junit.runner.JUnitCore com.rikkaminis.app.ui.chat.ImeDictationGuardWiringTest` → **OK (6 tests)**。

9 条外部变异臂（每条把一种回归塞回真源码副本，跑同一套测试；**A0 是对照组，必须绿**）：

| 臂 | 变异 | 期望转红的判据 | 结果 |
|---|---|---|---|
| A0 | 原样副本 | 无 | **绿（对照）** |
| A1 | 整块删掉守卫（回到修复前形状） | ①+②（顺带把"单一调用点"的负控也带红） | 红 3 条 ✔ |
| A2 | 守卫移到 `keyboardController?.hide()` **之后** | ① | 红 ✔ |
| A3 | 删掉 `return@LaunchedEffect`（落到 hide） | ① | 红 ✔ |
| A4 | 日志标签改 `ChatVM` | ① | 红 ✔ |
| A5 | 把 `activeRecordingConfigurations` 换成 `emptyList<Any>()` | ③ | 红 ✔ |
| A6 | 再塞一个调用点 | ② | 红 ✔ |
| A7 | 删掉 API 29 版本门 | ③ | 红 ✔ |
| A8 | `getOrDefault(false)` → `}`（fail-closed） | ③ | 红 ✔ |

**A3 第一次跑是假绿**：`arm_len` 找的是文件里**第一处** `return@LaunchedEffect`（ChatScreen 里别的 effect 也用它），删错了位置。修正为"在守卫块内定位"后才红。这是「臂要比判据更小心」的第 N 次复现——**绿臂先怀疑臂，别急着相信自己**。同时顺手把判据窗口从"守卫后 400 字"收紧成"守卫→hide 的切片"，避免窗口溢出到相邻代码。

### 4.3 CI

`Build RikkaMinis Android APK` run [`41197434892`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/41197434892)，`workflow_dispatch`，attempt 1，**success**，11:34:45Z → 11:52:56Z ≈ 18.2 min。

判读（下载 job 日志逐条核，不只看结论）：

| 判据 | 结果 |
|---|---|
| `head_sha` 与本地 HEAD 逐字符一致 | `2c1b29d540a49b0d75d80bfdf7ac20b10cea79f6` ✔ |
| run 结论 | success，27 个日志段，1,689,787 字符，NUL 0 |
| 门 | `Summary: 113 passed, 0 failed` ×1 |
| 夹具 | `✅ ALL 349 CASES PASS` ×1 |
| 编译错误 | `^e: ` 0 行 |
| 真 `FAILED` 行 | 0（含 FAILED 的行全部是 `PASSED` 用例名子串） |
| 本支新用例 | `ImeDictationGuardWiringTest` 6/6 `PASSED`（两条判据 + 1 条实现判据 + 3 条负控） |

### 4.4 一次自伤（记下来，别再犯）

`test_scan.py` 被沙箱静默杀掉两次：**第一次**是因为输出重定向到文件后 Python 块缓冲，日志一直停在 180 秒前的行上，被 shell 的 stall-guard 判成「无输出且无 CPU 进展」而中断——加 `python3 -u` 才是对的（父进程在等子进程时 CPU 读数不涨，但**只要真有输出流出来，stall-guard 就不会误判**）。**第二次**即便加了 `-u` 仍在 `test_da1023_gates` 中途静默消失（无 exit code），与另一支并行会话同一时间也在跑同一套夹具相互叠加负载，最可能是内存压力下被杀。结论：**这套 349 例夹具在沙箱里是一个不可靠的长跑**，验证应当（a）靠 CI 的同一轮输出，或（b）按组 `--single` 分片跑。

## 5. 残留与触发条件

- **R1 真机未验（本支唯一硬缺口）**。沙箱无法驱动 IME 的语音会话，"拖动 → hide → 语音断"这条因果链目前靠**同源代码的上游修复注释**（上游明写 Gboard `stopVoiceInput reason SHUTDOWN right after our hide`）+ 本地 `inactive InputConnection` 日志（四天 53 次）双向佐证，没有一次"同帧"实证。**触发条件**：用户在真机上用 Gboard/微信输入法听写时向上滑列表；**判据**：logs 里出现 `ScrollFollow … drag keeps the keyboard: audio recording active`，且听写不中断。
- **R2 `activeRecordingConfigurations` 是进程级代理，不是"本 app 的 IME 在听写"**。任何后台录音（录音机、语音通话、其他 app 录音）都会让拖动不再收键盘。后果是软退化（键盘留着，用户仍可用返回键 / 点空白收），不是锁死。**触发条件**：用户一边后台录音一边拖动消息列表；**升级信号**：若真机上频繁出现"拖了键盘不收"的投诉，则需收紧判据（例如结合 `AudioRecordingConfiguration.clientAudioFormat` 采样率特征或本 app 自身 `AudioRecord` 占用排除法），而 API 不出包名，原样做只能做到这一步。
- **R3 API 26–28 上守卫恒为 false**（版本门）。Android 8/9 设备完全回到修复前行为——那里 `activeRecordingConfigurations` 不存在，这是平台边界不是遗漏。
- **R4 只覆盖滚动收键盘这一条路径**。发送后收、drawer 收、overlay（`anyOverlayOpen` / `toolDetailIdForIme`）收仍会中断听写，全部是刻意设计（诊断 ③ 已说明）。**触发条件**：用户报告"点开工具详情卡时听写断"——那是一条独立的契约，需要单独判定是否接受。
- **R5 诊断 ② 未修**（IME burst flush 与 inputText mirror 的竞态，表现为"吞字"而非"断"）。它要动 `ChatInputArea.kt`（本批另一支的独占文件），且需要来源标记 + 表驱动测试，单独立项。**触发条件**：用户报告"说的是词消失/后半句被吞"——那是 ② 不是本支。
- **R6 日志是 debug 级**：release 包是否保留取决于 `AppLogger.debug` 的现有策略，未改动。真机回报若拿不到这行，先查日志级别而不是怀疑守卫。
