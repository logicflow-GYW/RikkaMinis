# FIX-COMPOSER-RESIDUE-1117 — 发送后输入框残留（IME 迟到重放）

**分支** `fix/composer-post-send-residue-1117`（基线 main `fa3c1eb6`）
**生产** 1 文件 +58 行 / 1 新纯函数文件 · **测试** 2 新类 · 门 0 新建（扩既有门 0 处）

---

## 1 症状

用户报告（另一台设备、低频）：点发送后消息已经出现在转录里，但**同一段文字仍留在输入框**。本机（Redmi Note 12 Turbo / HyperOS + 系统输入法）从未复现。

## 2 根因

不是「发送没清空」——发送路径的清除是完整的三写漏斗（`clearComposerInput`：取消待刷突发 → `setInputText("")` → 直接写 `inputFieldValue = TextFieldValue("")` → 清粘贴缓冲 → 关菜单）。

唯一能让发送后 field 重新有字的通道是 **`onValueChange` 的迟到提交**：

1. `ChatInputArea.kt:1815` 的 T217-2 守卫只拦 `composition != null && < 500ms && text 非空` 的提交；
2. 守卫之后 `inputFieldValue = tfv` 是**无条件**的（在任何与 `inputText` 的比较之前）⇒ 迟到事件先把 field 写回；
3. 随后的 `inputText != tfv.text` 必然成立（VM 已空）⇒ 长文本走 150ms 防抖、flush 里 `shouldCommitImeBurst(live == snapshot)` 为真 ⇒ `setInputText(旧文本)`（短文本立即写）⇒ **VM 与持久化草稿槽一起被写回**。

因此这是「清空发生后，输入法重放它自己那份正文」——第三方中文/语音输入法在失焦/收键盘时用 `commitText(整段)`（`composition == null`）重放，或提交晚于 500ms，都会越过唯一的那道 composition 判据。

**已排除的三条**：`sendMessage` 内三处 `setInputText(text)` 都是「发送被拒绝」分支（流式中编辑 / 压缩中 / 无 provider），此时消息不会进转录；150ms 突发 flush 的 job 在主线程被 `cancel()` 后不可能跑到写回；草稿恢复只在「新建 VM + draft 会话」时发生，而发送是 draft→real 的别名晋升，VM 不重建。

## 3 修法（阶梯第 6 级：接既有单源 + 最小实现）

把守卫判据从「看 composition」换成「看内容指纹」：

- `clearComposerInput` 在**清空之前**记下被清掉的正文与时刻（`lastClearedText` / `lastClearedAtMs`）。选这个漏斗是因为**所有清空入口都经过它**（发送 / 回车发送 / 上滑发送 / slash / 清空对话 / 跨会话移动），一处覆盖。
- `onValueChange` 顶部（fold 之前）调用新纯函数 `isComposerClearResidue(...)`，命中即 `return@BasicTextField`（field 与 VM 都不写），首次命中考一条 `ComposerResidue` 日志。
- 判据（全部满足）：有指纹；incoming 非空；**field 与 VM 都还空**（用户一旦开始输入，守卫永久失效）；`0 ≤ dt < 1500ms`；incoming 等于被清正文，或是它 ≥4 字的连续前缀/后缀。
- 非重放的编辑到达时清掉指纹（防止窗口内误吞后续同形编辑）。

4 字下限与 1.5s 窗口是**硬件校准常量**（显式命名 + 理由写进 KDoc）：低于 4 字的匹配与「用户在空框里刚敲那几个字」不可区分，宁可漏一次残留也不吞按键；窗口按 `clearFocus` → `finishComposingText` → InputConnection 回调的实测量级取，且短于「发送后又粘贴同样文本」这一唯一合法重放场景。

## 4 验证

- **纯函数单测** `ComposerClearResiduePolicyTest`：15 条，期望全部为字面量（等值重放 / 长前缀 / 长后缀 / 4 字 `/` 3 字 / 单字 / 窗口外 / 窗口内最后 1ms / 时钟倒走 / field 非空 / VM 非空 / 无关文本 / 无指纹 / 空输入 / 常量契约）。
- **接线形状测试** `ComposerClearResidueWiringTest`：4 条（指纹在清空**之前**读取并记录 / 守卫带真指纹实参 / 守卫在 fold 之前 / 非重放编辑清指纹）。
- **变异负控** 8 臂全部 RED-PRECISE（每臂只翻对应断言；脚本快照 + `finally` 还原 + sha256 逐字节核对，不用 `git checkout`）。
- **门**：`chat_ui_interaction_check` rc=0。
- 沙箱无 Android/Compose 依赖，`ChatInputArea.kt` 的整体编译由分支 CI 覆盖。

## 5 影响面

发送/清空后的输入法重放不再把已发送正文写回输入框；草稿槽不再被残留文重新填充。无 schema / 依赖 / 字符串资源改动（7+1 语言零触碰）。

## 6 残留风险（登记，不修）

- **发送后 1.5s 内粘贴与刚发送内容相同的文本会被丢弃一次**（窗口内、field 为空、内容匹配）。判为可接受：该形态极罕见，代价是用户再粘贴一次；反向取舍（放宽）会让修复失效。
- **`composition != null` 的 T217-2 守卫保留原样**（500ms 内更宽松，两者叠加无害）。
- 真机行为级未验：需在那台低频设备（第三方输入法）上复现一次，看日志是否出现 `[fix/composer-post-send-residue] dropped a late IME replay`，同时确认输入框保持为空。

## 6.5 首轮 CI 红（真错，非 flake）

`B3RenderInputShapeTest > theClearFunnelCarriesAllThreeResets` FAILED（run `44067670806` @ `e7d8d9c5`）。
该测试从漏斗声明起量 **400 字符**窗口，要求 `cancelPendingImeBurst()` / `viewModel.setInputText("")` / `inputFieldValue =` / `lastTrueCaretEnd = 0` 都在窗口内；第一版把「读指纹 + 写指纹」连同 KDoc 塞进漏斗，把 `lastTrueCaretEnd = 0` 顶到窗口外。
修法 = 指纹逻辑外提成 `stampClearedBody` lambda（声明在漏斗之前），漏斗里只多一行调用；`lastTrueCaretEnd = 0` 的偏移回到 245 字符。接线测试随之改锚（lambda 声明位置 + 调用点在清空之前），变异臂 M7 改为「漏斗不调 stamp」。

**教训（同族第二次）**：既有形状测试带距离窗口时，新增逻辑一律外提成具名 lambda / 函数，**不要在窗口内加注释块** —— 上一次是 406 字符，这一次同样死在这条线上。

## 7 顺手核查（backlog）

- §1040-B3 **R5**（「跨 composable 清空站点未普查」）→ 已普查：`ui/**` 下 `setInputText("")` 仅漏斗一处，无裸站点 ⇒ 可结案。
- §1040-B3 **R2**（ChatScreen 分享注入 / 跨会话转移仍读滞后 `inputText`）→ 已在 dv24-B05 / incr1118 D2-F02 改走 `ComposerCommand.AppendText` ⇒ 可结案。
- §1040-B3 **R1**（Clear-Chat 直清）→ 已走 `ComposerCommand.Clear` ⇒ 已结案。
- §1044-B05 **R2**（`composerCommand` replay=0）→ latent、需新增 VM 字段，超出半径 ⇒ 不动。
