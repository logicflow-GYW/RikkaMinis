# FIX-USER-MSG-FOLD-1114：用户消息气泡折叠缺失（L1 渲染层）

## 0 来源与判定

用户报告（2026-11-14）：粘贴长文本 → 输入框折叠成 chip → 发出后气泡**整段展开**，折叠形同虚设。

判级 **P1**（可感知体验降级，常用路径边界错）。用户当场确认按 L1 实施（渲染层折叠），
不做 L2（数据层保留折叠块结构）。

## 1 根因（一行）

折叠是**输入框专用**的：`[Pasted#N]` chip 只活在 `PastedTextBuffer`（纯 UI 状态），
`ChatViewModel.sendMessage()` 第一行 `expandPastedText(rawText)` 就把正文展开，之后
同一个字符串既进 DB、也进请求体、也进气泡。气泡唯一的折叠是 `LargeContentGuard`，阈值
`LARGE_MESSAGE_THRESHOLD_CHARS = 32_000`——那是给 legacy 200KB tool_result 行准备的
markdown GC 风暴地板，不是阅读折叠，1.5 万字的粘贴直接从下面钻过去。

**真机日志实证**（今天，同一条）：
```
14:59:58.967 [ChatViewModel] [Paste] stashed #1 (14929 chars, inline=false)
15:00:05.001 [ChatViewModel] [Paste] expanded 0 marker(s) + 1 detached block(s) on send
```
内容确实送到了模型（11-14 那支修好的），气泡侧没有任何折叠。

## 2 修法

| 文件 | 改动 |
|---|---|
| `ui/chat/MessageFoldPolicy.kt`（新增 63 行） | 整套折叠策略的纯 Kotlin：两端阈值 + 预览长度 + 判据 `shouldCollapseMessage`。`LARGE_MESSAGE_*` 从 `LargeContentGuard.kt` 移入（同包，引用不变） |
| `ui/chat/LargeContentGuard.kt` | 新增 `collapseThresholdChars` / `previewChars` 参数，**默认值 = 原常量** ⇒ 助手/工具三处调用点行为零变化 |
| `ui/chat/ChatUserMessageUI.kt` | 用户气泡传 `USER_MESSAGE_COLLAPSE_THRESHOLD_CHARS = 4_000` + `USER_MESSAGE_PREVIEW_CHARS = 600` |
| `test/.../MessageFoldPolicyTest.kt`（新增 10 例） | 阈值字面量、边界（`>` 不是 `>=`）、流式不折叠、14 929 字折叠、**两处接线断言** |

**为什么助手不跟着降到 4K**：折叠长回答会藏起用户正要的东西；32K 地板的存在理由是
markdown 重解析成本（Issue #17 GC 风暴），不是可读性。两端诉求相反，所以两端阈值分开。

**为什么不选 L2**：数据层要多开四条边（parts 新类型 / 老消息兼容 / 备份带过去 / fork 与
跨会话重映射）+ 一个门，换来的只是"气泡能认出这是粘贴块"。600 字预览已经够认。

## 3 证据

**L1 沙箱 JVM（kotlinc 2.1.0 + JUnit 4.13.2）**
- 编译 `MessageFoldPolicy.kt` + `MessageFoldPolicyTest.kt` 专项树：`OK (10 tests)`
- 变异负控 **5/5 精确翻红**，sha256 还原校验 `RESTORE_OK sha256 MATCH`：
  | 臂 | 变异 | 结果 |
  |---|---|---|
  | m1 | `>` → `>=`（边界归折叠侧） | 2 红 |
  | m2 | 阈值 4000 → 8000 | 1 红 |
  | m3 | 去掉 `!isStreaming` 守卫 | 1 红 |
  | m4 | 用户气泡不传 `collapseThresholdChars` | 1 红 |
  | m5 | 用户气泡不传 `previewChars` | 1 红 |

**L2 邻门**（本地真树）：`chat_ui_interaction_check` GATE OK / `file_provider_paths_coverage_check` 0 violation

**L3 分支 CI** run [`43569848833`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/43569848833)
head_sha `004c0e24dedc`，本地 HEAD == 远端 tip == run.head_sha **三方逐字符一致**，五 job 全 success。
逐条搜标记（不只信绿）：
- unit-tests：`BUILD SUCCESSFUL in 6m 26s`，**9174 PASSED / 0 FAILED**（基线 9164，+10 = 本支新增 10 例，对得上）
- `MessageFoldPolicyTest` 十条用例逐条在册 PASSED（含 `a 15K paste folds` / `the user bubble passes the short threshold to the guard`）
- scan-gate：`Summary: 115 passed, 0 failed`，但 `##[notice]scripts/scan/** untouched in this push — skipping the fixture self-test` ⇒ **夹具没跑**（本支未动门脚本），**不声称夹具全绿**

## 4 残留

- **R1 真机行为级未验证**（沙箱无 Compose 运行时）。触发条件 = 装包含本支的包；
  触发后方案 = 粘贴 >4000 字发送 → 气泡应显示前 600 字 + 「Large content (N KB) / Tap to expand」，
  点展开后全文渲染；助手长回答（>32K）行为不变。搁置理由 = Compose 布局在沙箱内无判据。
- **R2 折叠状态的既有语义**（`rememberSaveable`，重开会话复位）对用户气泡同样适用，
  本支不改。若日后要"记住展开过"，属独立工单。
