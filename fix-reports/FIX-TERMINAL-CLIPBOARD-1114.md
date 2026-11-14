# FIX-TERMINAL-CLIPBOARD-1114：终端选择菜单的复制/粘贴双向失效

## 0 来源与判级

外部报告点 #6（"终端剪贴板双向打通"）。报告说"长按选词复制不生效"——**核实后比报告严重**：
Termux 自带选择菜单的 **复制和粘贴两个分支都是死的**。

判级 **P1**（用户可见功能整体缺失，常用路径）。分支 `fix/terminal-clipboard-1114` @ `c1375ac9`。

## 1 根因

`TerminalSession.kt` 的 `TermuxSessionClient` 把两个回调写成空实现：

```kotlin
override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession): Unit {}
override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
```

它们是 Termux 选择菜单与 OSC 52 进入 Android 剪贴板的**唯一通道**。反编译
`terminal-view-0.118.0.aar` + `terminal-emulator-0.118.0.aar`（`javap -c`）确认三条调用链：

| 用户动作 | 调用链 | 修复前 |
|---|---|---|
| 长按选中 → 菜单「复制」 | `TextSelectionCursorController$1.onActionItemClicked`(copy_text) → `TerminalSession.onCopyTextToClipboard(String)` → `TerminalSessionClient.onCopyTextToClipboard(this, text)` | **空实现**，什么都不做 |
| 长按 → 菜单「粘贴」 | 同上(paste_text) → `TerminalSession.onPasteTextFromClipboard()` → `TerminalSessionClient.onPasteTextFromClipboard(this)` | **空实现** |
| 程序输出 OSC 52 | `TerminalEmulator`（`Base64.decode`）→ `TerminalOutput.onCopyTextToClipboard` | **空实现** |

**为什么看起来没坏**：菜单项的 enabled 状态由 Termux 自己查 `ClipboardManager.hasPrimaryClip()` 决定
（`onCreateActionMode` 字节码里可见），所以粘贴菜单看上去是可点的；而软键盘 / IME 路径**从不经过这两个回调**，
所以终端日常可用，只有选择菜单和远端复制是死的。

## 2 修法

```kotlin
onCopyTextToClipboard  → writeClipboardText(context, text)         // ClipData.newPlainText("terminal", text)
onPasteTextFromClipboard → readClipboardText(context) → emulator.paste(text)
```

**为什么粘贴走 `TerminalEmulator.paste()` 而不是 `sendText()`**：那一个入口自带三件事，重写它们就是重造轮子
（反编译 `TerminalEmulator.paste(String)` 字节码逐条确认）：
1. 剥掉 clip 里的 ESC / C1 控制字符 —— 恶意剪贴板内容无法注入光标/OSC 序列；
2. `\r?\n` → `\r`（ICRNL 友好）；
3. 当 guest 打开了 DECSET 2004 时，用 `ESC[200~ … ESC[201~` 包裹 —— vim / readline ≥8 靠这个区分粘贴与打字。
我们的 `sendText()` 只做第 2 条。emulator 尚未就绪时（PTY 还在 boot）回落到原始 `session.write`，不丢文本。

**失败策略**：两个剪贴板辅助函数都 `runCatching` 包裹 —— 读失败（服务缺失 / Android 10+ 后台读剪贴板限制）
退化成「什么都没发生」，和修复前一样，**不能**让一次剪贴板异常掀掉整个终端会话。

## 3 证据

**L1 沙箱 JVM（kotlinc 2.1.0 + JUnit 4.13.2）**
- `TerminalClipboardWiringTest`：`OK (4 tests)`
- 变异负控 **4/4 红**，sha256 还原 `RESTORE_OK sha256 MATCH`：
  | 臂 | 变异 | 结果 |
  |---|---|---|
  | m1 | 复制回调体清空 | 1 红 |
  | m2 | 粘贴回调体清空 | 2 红 |
  | m3 | 粘贴绕过 emulator 走裸写 | 1 红 |
  | m4 | `readClipboardText` 去掉 runCatching | 1 红 |
- **m4 第一版是假绿**：断言写成 `block.contains("runCatching")`，而函数体里自带 `return@runCatching null`
  标签 —— 换掉包装后标签还在，断言照样绿。改成分别锚「签名区含 runCatching」（读侧）与
  「函数体首条语句是 runCatching」（写侧）后 m4 才真红。**这是本支最值得记的教训：标签不等于守卫。**

**L2 邻门**（本地真树）：`claim_release_guard` ✅（该门是本仓唯一锚 `TerminalSession` 的门，锚点是 `start()` 抢占分支，未受影响）

**L3 分支 CI** run [`43571731043`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/43571731043)
head_sha `c1375ac9`，本地 == 远端 == run **三方逐字符一致**，五 job 全 success。逐条搜标记：
- unit-tests：**9168 PASSED / 0 FAILED**（基线 9164，+4 = 本支四条用例）
- `TerminalClipboardWiringTest` 四条**逐条在册 PASSED**（含 `paste goes through the emulator …`）
- scan-gate：`Summary: 115 passed, 0 failed`；`##[notice]scripts/scan/** untouched in this push — skipping the fixture self-test` ⇒ **夹具没跑**（本支未动门脚本），不声称夹具全绿

## 4 残留

- **R1 真机行为级未验证**（沙箱无 Android 运行时，`ClipboardManager` 不存在）。
  **触发条件** = 装包含本支的包。
  **触发后方案** = ①终端里 `echo test` → 长按选中 → 菜单「复制」 → 粘贴到聊天输入框应出现 `test`；
  ②复制一段文本 → 终端长按 → 菜单「粘贴」 → 应出现在输入行；③`printf '\e]52;c;%s\a' "$(echo -n hi|base64)"` → 剪贴板应为 `hi`；
  ④vim 里粘贴应进入 insert 且不被自动缩进（bracketed paste 生效）。
  **搁置理由** = 沙箱内无 ClipboardManager / Compose，只能真机取判据。
- **R2 别名/字号等终端 UX 项**：见 backlog `§SANDBOX-TERM-1114`（#7/#10/#11/#12 均为登记不修）。
